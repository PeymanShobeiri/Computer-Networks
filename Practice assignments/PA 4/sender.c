#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <poll.h>
#include <arpa/inet.h>
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
    char       filename[MAX_FILENAME_LEN];
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
    char     filename[MAX_FILENAME_LEN];
    int      file_id;
    int      total_chunks;
    int      chunk_size[MAX_CHUNKS];
    uint32_t chunk_checksums[MAX_CHUNKS];
    char    *chunk_data[MAX_CHUNKS];
} file_info_t;

static file_info_t g_files[MAX_FILES];
static int g_file_count = 0;

static void load_files_into_memory(char **filenames, int count);
static void free_loaded_files(void);
static void handle_nack(mcast_t *m, packet_t *pkt);

int main(int argc, char *argv[])
{
    if (argc < 2) {
        fprintf(stderr, "Usage: %s <file1> [file2] ...\n", argv[0]);
        exit(1);
    }

    mcast_t *m = multicast_init(MCAST_ADDRESS, SENDER_PORT, RECEIVER_PORT);
    load_files_into_memory(&argv[1], argc - 1);
    printf("[Sender] Loaded %d file(s). Starting multicast...\n", g_file_count);
    struct pollfd fds[1];
    fds[0].fd = m->sock;
    fds[0].events = POLLIN;
    while (1) {
        for (int fidx = 0; fidx < g_file_count; fidx++) {
            file_info_t *fi = &g_files[fidx];
            for (int cidx = 0; cidx < fi->total_chunks; cidx++) {

                packet_t pkt;
                memset(&pkt, 0, sizeof(pkt));

                pkt.hdr.type         = MSG_CHUNK;
                pkt.hdr.file_id      = fi->file_id;
                pkt.hdr.chunk_id     = cidx;
                pkt.hdr.total_chunks = fi->total_chunks;
                pkt.hdr.checksum     = fi->chunk_checksums[cidx];
                pkt.hdr.length       = fi->chunk_size[cidx];
                strncpy(pkt.hdr.filename, fi->filename, MAX_FILENAME_LEN);
                memcpy(pkt.data, fi->chunk_data[cidx], fi->chunk_size[cidx]);
                int packet_size = sizeof(packet_header_t) + fi->chunk_size[cidx];
                multicast_send(m, &pkt, packet_size);
                // usleep(5000);
                usleep(1000);
                // NACK
                int rc = poll(fds, 1, 0);
                if (rc > 0 && (fds[0].revents & POLLIN)) {
                    packet_t incoming;
                    memset(&incoming, 0, sizeof(incoming));
                    int cnt = recvfrom(m->sock, &incoming, sizeof(incoming), 0, NULL, NULL);
                    if (cnt > 0 && incoming.hdr.type == MSG_NACK) {
                        handle_nack(m, &incoming);
                    }
                }
            }
        }
    }
    free_loaded_files();
    multicast_destroy(m);
    return 0;
}

static void load_files_into_memory(char **filenames, int count)
{
    g_file_count = 0;
    for (int i = 0; i < count; i++) {
        if (g_file_count >= MAX_FILES) {
            fprintf(stderr, "[Sender] Too many files.\n");
            return;
        }

        file_info_t *fi = &g_files[g_file_count];
        fi->file_id = g_file_count;
        strncpy(fi->filename, filenames[i], MAX_FILENAME_LEN);
        FILE *fp = fopen(filenames[i], "rb");
        if (!fp) {
            perror("[Sender] fopen");
            continue;
        }
        //size
        fseek(fp, 0, SEEK_END);
        long fsize = ftell(fp);
        fseek(fp, 0, SEEK_SET);

        int total_chunks = (fsize + CHUNK_SIZE - 1) / CHUNK_SIZE;
        fi->total_chunks = total_chunks;

        for (int c = 0; c < total_chunks; c++) {
            long remain = fsize - ((long)c * CHUNK_SIZE);
            int to_read = (remain < CHUNK_SIZE) ? (int)remain : (int)CHUNK_SIZE;

            if (c >= MAX_CHUNKS) {
                fprintf(stderr, "[Sender] exceeded MAX_CHUNKS.\n");
                fclose(fp);
                goto done_reading;
            }

            fi->chunk_data[c] = (char *)malloc(to_read);
            if (!fi->chunk_data[c]) {
                fprintf(stderr, "[Sender] out of memory.\n");
                exit(1);
            }
            int nread = fread(fi->chunk_data[c], 1, to_read, fp);

            fi->chunk_size[c]     = nread;
            fi->chunk_checksums[c] = checksum_method(fi->chunk_data[c], nread);
        }

    done_reading:
        fclose(fp);

        printf("[Sender] File '%s' loaded: %ld bytes, %d chunks.\n",
               fi->filename, fsize, total_chunks);
        g_file_count++;
    }
}

static void free_loaded_files(void)
{
    for (int i = 0; i < g_file_count; i++) {
        file_info_t *fi = &g_files[i];
        for (int c = 0; c < fi->total_chunks; c++) {
            free(fi->chunk_data[c]);
            fi->chunk_data[c] = NULL;
        }
    }
}

static void handle_nack(mcast_t *m, packet_t *pkt)
{
    uint16_t fid = pkt->hdr.file_id;
    uint16_t cid = pkt->hdr.chunk_id;

    if (fid >= g_file_count) {
        return;
    }
    file_info_t *fi = &g_files[fid];
    if (cid >= fi->total_chunks) {
        return;
    }
    packet_t out;
    memset(&out, 0, sizeof(out));
    out.hdr.type         = MSG_CHUNK;
    out.hdr.file_id      = fid;
    out.hdr.chunk_id     = cid;
    out.hdr.total_chunks = fi->total_chunks;
    out.hdr.length       = fi->chunk_size[cid];
    out.hdr.checksum     = fi->chunk_checksums[cid];

    strncpy(out.hdr.filename, fi->filename, MAX_FILENAME_LEN);

    memcpy(out.data, fi->chunk_data[cid], fi->chunk_size[cid]);

    int packet_size = sizeof(packet_header_t) + fi->chunk_size[cid];
    multicast_send(m, &out, packet_size);

    printf("[Sender] Resent file_id=%u chunk_id=%u on NACK.\n", fid, cid);
}
