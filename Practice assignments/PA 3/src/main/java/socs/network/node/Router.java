package socs.network.node;

import socs.network.message.LSA;
import socs.network.message.LinkDescription;
import socs.network.message.SOSPFPacket;
import socs.network.util.Configuration;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;

public class Router {

  protected LinkStateDatabase lsd;
  protected RouterDescription rd = new RouterDescription();

  // up to 4 ports
  protected Link[] ports = new Link[4];

  private ServerSocket serverSocket;
  private boolean running = true;

  // queue for the requests
  private Queue<ConnectionContext> requestQueue = new LinkedList<>();
  private ConnectionContext currentRequest = null; // user who is choosing yes or no

  private static class ConnectionContext {
    Socket socket;
    ObjectInputStream in;
    ObjectOutputStream out;
    SOSPFPacket packet;
  }

  public Router(Configuration config) {
    try {
      rd.processIPAddress = "127.0.0.1";
      rd.simulatedIPAddress = config.getString("socs.network.router.ip");
      rd.processPortNumber = config.getShort("socs.network.router.processPort");
    }
    catch (Exception e) {
      System.out.println("Error reading config: " + e);
    }

    rd.status = null;
    lsd = new LinkStateDatabase(rd);

    try {
      serverSocket = new ServerSocket(rd.processPortNumber);
      Thread listener = new Thread(() -> {
        while (running) {
          try {
            Socket client = serverSocket.accept();
            handleIncomingConnection(client);
          }
          catch (IOException e) {
            if (running) {
              e.printStackTrace();
            }
          }
        }
      });
      listener.start();
    }
    catch (IOException e) {
      e.printStackTrace();
      System.exit(1);
    }
  }

  private void handleIncomingConnection(Socket clientSocket) {
    try {
      ObjectOutputStream out = new ObjectOutputStream(clientSocket.getOutputStream());
      ObjectInputStream in = new ObjectInputStream(clientSocket.getInputStream());
      SOSPFPacket packet = (SOSPFPacket) in.readObject();

      if (packet.sospfType == 0) { //hello
        processInboundHELLO(packet, clientSocket, out, in);

      }
      else if (packet.sospfType == 1) { //LSA update
        boolean changed = false;
        for (LSA lsa : packet.lsaArray) {
          LSA current = lsd._store.get(lsa.linkStateID);
          if (current == null) {
            lsd._store.put(lsa.linkStateID, lsa);
            changed = true;
          }
          else {
            if (lsa.lsaSeqNumber > current.lsaSeqNumber) {
              lsd._store.put(lsa.linkStateID, lsa);
              changed = true;
            }
          }
        }
        if (changed) {
          floodLSA(packet.srcIP);  // flood it to others
        }
        out.close();
        in.close();
        clientSocket.close();

      }
      else if (packet.sospfType == 2) { // reject
        System.out.println("[info] Attach request was rejected by " + packet.srcIP);
        out.close();
        in.close();
        clientSocket.close();
      }
      else if (packet.sospfType == 3) { // disconnect
        processRemoteDisconnect(packet);
        out.close();
        in.close();
        clientSocket.close();
      }

    }
    catch (IOException | ClassNotFoundException e) {
      e.printStackTrace();
    }
  }

  private void floodLSA(String ignoreIP) {
    SOSPFPacket update = new SOSPFPacket();
    update.sospfType = 1;
    update.srcIP = rd.simulatedIPAddress;
    update.srcProcessIP = rd.processIPAddress;
    update.srcProcessPort = rd.processPortNumber;
    update.routerID = rd.simulatedIPAddress;
    update.lsaArray = new Vector<>(lsd._store.values());

    for (Link link : ports) {
      if (link == null)
        continue;
      if (link.router2.simulatedIPAddress.equals(ignoreIP))
        continue;

      try (Socket sock = new Socket(link.remoteProcessIP, link.remoteProcessPort);
           ObjectOutputStream oos = new ObjectOutputStream(sock.getOutputStream())) {
        oos.writeObject(update);
        oos.flush();
      }
      catch (IOException e) {
        System.out.println("the neighbor " + link.router2.simulatedIPAddress + " is down or not reachable");
      }
    }
  }

  private void processInboundHELLO(SOSPFPacket packet, Socket clientSocket, ObjectOutputStream out, ObjectInputStream in) throws IOException {
    Link link = findLinkBySimIP(packet.srcIP);
    if (link == null) {
      // first time? -> ask y/n questions
      ConnectionContext cur = new ConnectionContext();
      cur.socket = clientSocket;
      cur.in = in;
      cur.out = out;
      cur.packet = packet;

      synchronized (requestQueue) {
        requestQueue.add(cur);
      }
      PromptNextRequest();
    }
    else {
      RouterStatus status = link.router2.status;
      if (status == null) status = RouterStatus.INIT;

      if (status == RouterStatus.INIT) {
        System.out.println("received a HELLO from " + link.router2.simulatedIPAddress + " set state to TWO_WAY");
        link.router2.status = RouterStatus.TWO_WAY;
      }
      else {
        out.close();
        in.close();
        clientSocket.close();
      }
    }
  }


  private synchronized void PromptNextRequest() {
    if (currentRequest == null) {
      ConnectionContext next;
      synchronized (requestQueue) {
        next = requestQueue.poll();
      }
      if (next != null) {
        currentRequest = next;
        System.out.println("[info] Received HELLO from " + currentRequest.packet.srcIP);
        System.out.println("[info] Do you accept this request? (Y/N)");
      }
    }
  }

  private synchronized void processYes() {
    if (currentRequest == null) {
      System.out.println("No pending attach request to accept.");
      return;
    }
    ConnectionContext cur = currentRequest;
    Link link = findLinkBySimIP(cur.packet.srcIP);

    if (link == null) {
      int freePort = getFreePort();
      if (freePort < 0) {
        System.out.println("No free ports => rejecting " + cur.packet.srcIP);
        sendReject(cur.packet, cur);
        closeContext(cur);
        currentRequest = null;
        PromptNextRequest();
        return;
      }
      RouterDescription remote = new RouterDescription();
      remote.processIPAddress = cur.packet.srcProcessIP;
      remote.processPortNumber = cur.packet.srcProcessPort;
      remote.simulatedIPAddress = cur.packet.srcIP;
      remote.status = RouterStatus.TWO_WAY;

      link = new Link(rd, remote);
      link.remoteProcessIP = remote.processIPAddress;
      link.remoteProcessPort = remote.processPortNumber;
      link.weight = 1;
      ports[freePort] = link;
      System.out.println("set " + cur.packet.srcIP + " state to TWO_WAY");
    }
    else {
      if (link.router2.status == null) {
        link.router2.status = RouterStatus.INIT;
        System.out.println("set " + cur.packet.srcIP + " state to INIT");
      }
    }

    sendHELLOBack(link, cur.out, cur.packet.srcIP);
    closeContext(cur);
    currentRequest = null;
    PromptNextRequest();
  }

  private synchronized void processNo() {
    if (currentRequest == null) {
      System.out.println("No pending attach request to reject.");
      return;
    }
    ConnectionContext cur = currentRequest;
    System.out.println("rejecting attach from " + cur.packet.srcIP);
    sendReject(cur.packet, cur);
    closeContext(cur);
    currentRequest = null;
    PromptNextRequest();
  }

  private void sendHELLOBack(Link link, ObjectOutputStream out, String neighborIP) {
    try {
      SOSPFPacket reply = new SOSPFPacket();
      reply.sospfType = 0; // hello :)
      reply.srcIP = rd.simulatedIPAddress;
      reply.srcProcessIP = rd.processIPAddress;
      reply.srcProcessPort = rd.processPortNumber;
      reply.routerID = rd.simulatedIPAddress;
      reply.neighborID = neighborIP;

      System.out.println("sending HELLO back to " + neighborIP);
      out.writeObject(reply);
      out.flush();
    }
    catch (IOException e) {
      e.printStackTrace();
    }
  }

  private void sendReject(SOSPFPacket packet, ConnectionContext cur) {
    try {
      SOSPFPacket rejectPkt = new SOSPFPacket();
      rejectPkt.sospfType = 2;
      rejectPkt.srcIP = rd.simulatedIPAddress;
      rejectPkt.srcProcessIP = rd.processIPAddress;
      rejectPkt.srcProcessPort = rd.processPortNumber;
      rejectPkt.routerID = rd.simulatedIPAddress;
      rejectPkt.neighborID = packet.srcIP;
      cur.out.writeObject(rejectPkt);
      cur.out.flush();
    }
    catch (IOException e) {
      e.printStackTrace();
    }
  }

  private void closeContext(ConnectionContext cur) {
    try {
      cur.out.close();
    }
    catch (IOException e) {
      e.printStackTrace();
    }
    try {
      cur.in.close();
    }
    catch (IOException e) {
      e.printStackTrace();
    }
    try {
      cur.socket.close();
    }
    catch (IOException e) {
      e.printStackTrace();
    }
  }


  private void processstart() {
    for (int i = 0; i < 4; i++) {
      if (ports[i] == null) continue;
      System.out.println("[start] Sending HELLO to " + ports[i].router2.simulatedIPAddress);
      try (Socket sock = new Socket(ports[i].remoteProcessIP, ports[i].remoteProcessPort);
           ObjectOutputStream out = new ObjectOutputStream(sock.getOutputStream());
           ObjectInputStream in = new ObjectInputStream(sock.getInputStream())) {

        // send first hello
        SOSPFPacket hello = new SOSPFPacket();
        hello.sospfType = 0; // hello
        hello.srcIP = rd.simulatedIPAddress;
        hello.srcProcessIP = rd.processIPAddress;
        hello.srcProcessPort = rd.processPortNumber;
        hello.routerID = rd.simulatedIPAddress;
        hello.neighborID = ports[i].router2.simulatedIPAddress;

        out.writeObject(hello);
        out.flush();

        // answer
        try {
          SOSPFPacket response = (SOSPFPacket) in.readObject();
          if (response.sospfType == 0) {
            if (ports[i].router2.status == null) {
              ports[i].router2.status = RouterStatus.INIT;
              System.out.println("[start] set " + ports[i].router1.simulatedIPAddress + " state to INIT");
            }
            if (ports[i].router2.status == RouterStatus.INIT) {
              System.out.println("[start] received HELLO from " + response.srcIP);
              ports[i].router2.status = RouterStatus.TWO_WAY;
              System.out.println("[start] set " + ports[i].router2.simulatedIPAddress + " state to TWO_WAY");
            }
            else if (ports[i].router2.status == RouterStatus.TWO_WAY) {
              System.out.println("[start] router " + response.srcIP + " is already TWO_WAY");
            }
          }
          else if (response.sospfType == 2) {
            System.out.println("[start] Attach request was rejected by " + response.srcIP);
            ports[i] = null;
          }
        }
        catch (EOFException eof) {
          System.out.println("[start] no response from " + ports[i].router2.simulatedIPAddress);
        }
      }
      catch (Exception e) {
        e.printStackTrace();
      }
    }
    // after all, build LSD for any that reached TWO_WAY
    buildInitialLSA();
    floodLSA(null);
  }

  private void processAttach(String processIP, short processPort, String simulatedIP) {
    // can not attach twice the same router
    Link existing = findLinkBySimIP(simulatedIP);
    if (existing != null) {
      System.out.println("[error] Router already attached to " + simulatedIP);
      return;
    }
    int freePort = getFreePort();
    if (freePort < 0) {
      System.out.println("No free ports available for this router!!!");
      return;
    }
    if (rd.processIPAddress.equals(processIP) && rd.processPortNumber == processPort) {
      System.err.println("[error] The router cannot attach to itself!!!");
      return;
    }

    RouterDescription remote = new RouterDescription();
    remote.processIPAddress = processIP;
    remote.processPortNumber = processPort;
    remote.simulatedIPAddress = simulatedIP;
    remote.status = null;
    Link link = new Link(rd, remote);
    link.remoteProcessIP = processIP;
    link.remoteProcessPort = processPort;
    link.weight = 1;
    ports[freePort] = link;
    System.out.println("Attaching " + simulatedIP + " on port " + freePort);
  }

  private void processConnect(String processIP, short processPort, String simulatedIP) {
    // attach + start
    processAttach(processIP, processPort, simulatedIP);
    processstart();
  }

  private Link findLinkBySimIP(String simIP) {
    for (Link link : ports) {
      if (link == null)
        continue;
      if (link.router2.simulatedIPAddress.equals(simIP)) {
        return link;
      }
    }
    return null;
  }

  private int getFreePort() {
    for (int i = 0; i < 4; i++) {
      if (ports[i] == null) return i;
    }
    return -1;
  }


  private void processDetect(String destinationIP) {
    String path = lsd.getShortestPath(destinationIP);
    System.out.println(path);
  }

  private void processDisconnect(short portNumber) {
    if (portNumber < 0 || portNumber > 3) {
      System.out.println("Invalid port number");
      return;
    }
    if (ports[portNumber] == null) {
      System.out.println("No link at port " + portNumber);
      return;
    }
    Link link = ports[portNumber];
    String neighborIP = link.router2.simulatedIPAddress;
    ports[portNumber] = null;

    LSA selfLsa = lsd._store.get(rd.simulatedIPAddress);
    if (selfLsa != null) {
      selfLsa.lsaSeqNumber++;
      selfLsa.links.removeIf(ld -> ld.linkID.equals(neighborIP));
      lsd._store.put(rd.simulatedIPAddress, selfLsa);
    }
    floodLSA(null);

    // send the disconnect message of type 3 to the neighbor
    try (Socket sock = new Socket(link.remoteProcessIP, link.remoteProcessPort);
         ObjectOutputStream out = new ObjectOutputStream(sock.getOutputStream())) {
      SOSPFPacket disconnectMsg = new SOSPFPacket();
      disconnectMsg.sospfType = 3;
      disconnectMsg.srcIP = rd.simulatedIPAddress;
      disconnectMsg.srcProcessIP = rd.processIPAddress;
      disconnectMsg.srcProcessPort = rd.processPortNumber;
      disconnectMsg.routerID = rd.simulatedIPAddress;
      disconnectMsg.neighborID = neighborIP;
      out.writeObject(disconnectMsg);
      out.flush();
    }
    catch (IOException e) {
      System.out.println("Cannot send the disconnect message to " + neighborIP);
    }
  }

  private void processRemoteDisconnect(SOSPFPacket packet) {
    // find that link -> remove it -> update LSD -> inform others
    Link link = findLinkBySimIP(packet.srcIP);
    if (link != null) {
      for (int i = 0; i < 4; i++) {
        if (ports[i] != null && ports[i].router2.simulatedIPAddress.equals(packet.srcIP)) {
          ports[i] = null;
          break;
        }
      }
      // update
      LSA selfLsa = lsd._store.get(rd.simulatedIPAddress);
      if (selfLsa != null) {
        selfLsa.lsaSeqNumber++;
        selfLsa.links.removeIf(ld -> ld.linkID.equals(packet.srcIP));
        lsd._store.put(rd.simulatedIPAddress, selfLsa);
      }
      floodLSA(null);
    }
  }


  private void buildInitialLSA() {
    LSA selfLsa = lsd._store.get(rd.simulatedIPAddress);
    if (selfLsa == null) {
      selfLsa = new LSA();
      selfLsa.linkStateID = rd.simulatedIPAddress;
      selfLsa.lsaSeqNumber = 0;
    }
    else {
      selfLsa.lsaSeqNumber++;
    }

    LinkedList<LinkDescription> newLinks = new LinkedList<>();
    for (int i = 0; i < 4; i++) {
      if (ports[i] != null && ports[i].router2.status == RouterStatus.TWO_WAY) {
        LinkDescription ld = new LinkDescription();
        ld.linkID = ports[i].router2.simulatedIPAddress;
        ld.portNum = i;
        ld.weight = ports[i].weight;
        newLinks.add(ld);
      }
    }
    LinkDescription selfLd = new LinkDescription();
    selfLd.linkID = rd.simulatedIPAddress;
    selfLd.portNum = -1;
    selfLd.weight = 0;
    newLinks.add(selfLd);
    selfLsa.links = newLinks;
    lsd._store.put(rd.simulatedIPAddress, selfLsa);
  }

  private void processNeighbors() {
    for (Link link : ports) {
      if (link != null && link.router2.status == RouterStatus.TWO_WAY) {
        System.out.println(link.router2.simulatedIPAddress);
      }
    }
  }


  private void processUpdateWeight(int port, int newWeight) {
    if (port < 0 || port >= 4) {
      System.out.println("Invalid port number for updateWeight");
      return;
    }
    Link link = ports[port];
    if (link == null) {
      System.out.println("No link on port " + port);
      return;
    }
    System.out.println(" Changing link on port " + port + " from weight=" + link.weight + " to weight=" + newWeight);
    link.weight = newWeight;
    // update LSA
    LSA selfLsa = lsd._store.get(rd.simulatedIPAddress);
    if (selfLsa != null) {
      selfLsa.lsaSeqNumber++;
      for (LinkDescription ld : selfLsa.links) {
        if (ld.linkID.equals(link.router2.simulatedIPAddress)) {
          ld.weight = newWeight;
          break;
        }
      }
      lsd._store.put(rd.simulatedIPAddress, selfLsa);
    }
    floodLSA(null);
  }

  private void processQuit() {
    System.out.println("Disconnecting from all neighbors, killing the router ...");
    for (int i = 0; i < 4; i++) {
      if (ports[i] != null) {
        processDisconnect((short) i);
      }
    }
    // remove the router form the LSA
    lsd._store.remove(rd.simulatedIPAddress);
    floodLSA(null);
    // kill
    running = false;
    try {
      serverSocket.close();
    }
    catch (IOException e) {
      e.printStackTrace();
    }
    System.exit(0);
  }


  public void terminal() {
    try (BufferedReader br = new BufferedReader(new InputStreamReader(System.in))) {
      System.out.print(">> ");
      String command = br.readLine();
      while (command != null) {
        if (command.startsWith("detect ")) {
          String[] cmdLine = command.split(" ");
          processDetect(cmdLine[1]);
        }
        else if (command.startsWith("disconnect ")) {
          String[] cmdLine = command.split(" ");
          processDisconnect(Short.parseShort(cmdLine[1]));
        }
        else if (command.equals("quit")) {
          processQuit();
          break;
        }
        else if (command.startsWith("attach ")) {
          String[] cmdLine = command.split(" ");
          processAttach(cmdLine[1], Short.parseShort(cmdLine[2]), cmdLine[3]);
        }
        else if (command.equals("start")) {
          processstart();
        }
        else if (command.startsWith("connect ")) {
          String[] cmdLine = command.split(" ");
          processConnect(cmdLine[1], Short.parseShort(cmdLine[2]), cmdLine[3]);
        }
        else if (command.equals("neighbors")) {
          processNeighbors();
        }
        else if (command.equalsIgnoreCase("Y")) {
          processYes();
        }
        else if (command.equalsIgnoreCase("N")) {
          processNo();
        }
        else if (command.startsWith("updateWeight ")) {
          String[] cmdLine = command.split(" ");
          if (cmdLine.length < 3) {
            System.out.println("the command is used like: updateWeight <#port> <weight>");
          }
          else {
            int port = Integer.parseInt(cmdLine[1]);
            int weight = Integer.parseInt(cmdLine[2]);
            processUpdateWeight(port, weight);
          }
        }
        else {
          System.out.println("Invalid command or incomplete input!");
        }
        System.out.print(">> ");
        command = br.readLine();
      }
    }
    catch (Exception e) {
      e.printStackTrace();
    }
  }
}
