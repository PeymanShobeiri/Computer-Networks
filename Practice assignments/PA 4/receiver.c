#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <poll.h>
#include <arpa/inet.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>

#include "multicast.h"

#define CHUNK_SIZE        8192
#define MAX_CHUNKS        65535
#define MAX_FILENAME_LEN  256
#define MAX_FILES         20
#define MCAST_ADDRESS     "239.0.0.1"
#define SENDER_PORT       5000
#define RECEIVER_PORT     5000

typedef enum {
    MSG_CHUNK = 1,
    MSG_NACK  = 2
} msg_type_t;

typedef struct {
    msg_type_t type;
    uint16_t   file_id;
    uint16_t   chunk_id;
    uint16_t   total_chunks;
    uint32_t   checksum;
    uint16_t   length;
    char       filename[MAX_FILENAME_LEN]; // this is the path form the sender
} packet_header_t;

typedef struct {
    packet_header_t hdr;
    char data[CHUNK_SIZE];
} packet_t;


static inline uint32_t checksum_method(const char *buf, int len)
{
    unsigned int sum = 0;
    for (int i = 0; i < len; i++) {
        sum += (unsigned char)buf[i];
    }
    return (sum & 0xFF);
}

typedef struct {
    int   file_id;
    int   total_chunks;
    char  original_filename[MAX_FILENAME_LEN];
    int   received_flags[MAX_CHUNKS];
    int   chunk_size[MAX_CHUNKS];
    char *chunk_data[MAX_CHUNKS];
} recv_file_t;

static recv_file_t g_recv_files[MAX_FILES];
static int         g_num_files = 0;
// defualt folder name (only one useage)
static char g_output_folder[256] = "received_files";

static recv_file_t *get_or_create_recv_file(int file_id, const char *fname);
static void handle_chunk(mcast_t *m, packet_t *pkt);
static void handle_completed_file(recv_file_t *rf);
static void send_nack(mcast_t *m, int file_id, int chunk_id);

int main(int argc, char *argv[])
{
    // selected folder name from the client
    if (argc > 1) {
        strncpy(g_output_folder, argv[1], sizeof(g_output_folder));
        g_output_folder[sizeof(g_output_folder)-1] = '\0';
    }
    mkdir(g_output_folder, 0777);

    //join multicast
    mcast_t *m = multicast_init(MCAST_ADDRESS, SENDER_PORT, RECEIVER_PORT);
    multicast_setup_recv(m);

    printf("[Receiver] listening on %s:%d, writing files to '%s'\n",MCAST_ADDRESS, RECEIVER_PORT, g_output_folder);

    while (1) {
        int rc = multicast_check_receive(m);
        if (rc > 0) {
            packet_t pkt;
            memset(&pkt, 0, sizeof(pkt));
            int cnt = multicast_receive(m, &pkt, sizeof(pkt));
            if (cnt > 0) {
                if (pkt.hdr.type == MSG_CHUNK) {
                    handle_chunk(m, &pkt);
                }
            }
        }

        // check for lost chunks
        for (int i = 0; i < g_num_files; i++) {
            recv_file_t *rf = &g_recv_files[i];
            if (rf->total_chunks > 0) {
                int missing_any = 0;
                for (int c = 0; c < rf->total_chunks; c++) {
                    if (!rf->received_flags[c]) {
                        send_nack(m, rf->file_id, c);
                        missing_any = 1;
                        break;
                    }
                }
                // all are there --> finalized
                if (!missing_any) {
                    handle_completed_file(rf);
                }
            }
        }
        usleep(500000);
    }

    multicast_destroy(m);
    return 0;
}

static recv_file_t *get_or_create_recv_file(int file_id, const char *fname)
{
    for (int i = 0; i < g_num_files; i++) {
        if (g_recv_files[i].file_id == file_id) {
            return &g_recv_files[i];
        }
    }
    if (g_num_files >= MAX_FILES) {
        fprintf(stderr, "[Receiver] too many files.\n");
        exit(1);
    }
    recv_file_t *rf = &g_recv_files[g_num_files++];
    memset(rf, 0, sizeof(*rf));
    rf->file_id = file_id;

    // Copy name
    strncpy(rf->original_filename, fname, sizeof(rf->original_filename));
    rf->original_filename[sizeof(rf->original_filename)-1] = '\0';

    return rf;
}

static void handle_chunk(mcast_t *m, packet_t *pkt)
{
    uint16_t fid   = pkt->hdr.file_id;
    uint16_t cid   = pkt->hdr.chunk_id;
    uint16_t total = pkt->hdr.total_chunks;
    uint32_t csum  = pkt->hdr.checksum;
    uint16_t len   = pkt->hdr.length;

    if (cid >= MAX_CHUNKS) {
        return;
    }

    recv_file_t *rf = get_or_create_recv_file(fid, pkt->hdr.filename);

    if (rf->total_chunks == 0) {
        rf->total_chunks = total;
    }
    if (rf->received_flags[cid]) {
        return;
    }

    uint32_t calc = checksum_method(pkt->data, len);
    if (calc != csum) {
        fprintf(stderr, "[Receiver] corrupt chunk %u of file %u -> NACK.\n", cid, fid);
        send_nack(m, fid, cid);
        return;
    }

    //store datas
    rf->chunk_data[cid] = malloc(len);
    memcpy(rf->chunk_data[cid], pkt->data, len);
    rf->chunk_size[cid] = len;
    rf->received_flags[cid] = 1;

    printf("[Receiver] received chunk %u/%u (file=%u '%s'), size=%u\n",
           cid, total, fid, rf->original_filename, len);
}

static void handle_completed_file(recv_file_t *rf)
{
    if (rf->total_chunks <= 0) {
        return;
    }
    for (int c = 0; c < rf->total_chunks; c++) {
        if (!rf->chunk_data[c]) {
            return;
        }
    }

    // finding the base name  removing folder
    char base_name[MAX_FILENAME_LEN];
    memset(base_name, 0, sizeof(base_name));
    strncpy(base_name, rf->original_filename, sizeof(base_name));
    base_name[sizeof(base_name)-1] = '\0';

    char *slash_ptr = strrchr(base_name, '/');
    if (slash_ptr) {
        slash_ptr++;
    }
    else {
        slash_ptr = base_name;
    }

    // creating path
    char outpath[512];
    snprintf(outpath, sizeof(outpath), "%s/%s", g_output_folder, slash_ptr);

    FILE *fp = fopen(outpath, "wb");
    if (!fp) {
        perror("[Receiver] fopen for writing");
        return;
    }
    for (int c = 0; c < rf->total_chunks; c++) {
        fwrite(rf->chunk_data[c], 1, rf->chunk_size[c], fp);
    }
    fclose(fp);

    printf("[Receiver] completed file_id=%d => wrote '%s' (%d chunks)\n",
           rf->file_id, outpath, rf->total_chunks);

    // free memory
    for (int c = 0; c < rf->total_chunks; c++) {
        free(rf->chunk_data[c]);
        rf->chunk_data[c] = NULL;
    }
    rf->total_chunks = 0;
}

static void send_nack(mcast_t *m, int file_id, int chunk_id)
{
    packet_t pkt;
    memset(&pkt, 0, sizeof(pkt));
    pkt.hdr.type      = MSG_NACK;
    pkt.hdr.file_id   = file_id;
    pkt.hdr.chunk_id  = chunk_id;

    multicast_send(m, &pkt, sizeof(packet_header_t));
    printf("[Receiver] Sent NACK for file=%d chunk=%d\n", file_id, chunk_id);
}
