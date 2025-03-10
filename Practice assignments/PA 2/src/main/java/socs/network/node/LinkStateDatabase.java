package socs.network.node;

import socs.network.message.LSA;
import socs.network.message.LinkDescription;
import java.util.*;

public class LinkStateDatabase {

  // linkStateID => LSA instance
  HashMap<String, LSA> _store = new HashMap<>();

  private RouterDescription rd = null;

  public LinkStateDatabase(RouterDescription routerDescription) {
    rd = routerDescription;
    LSA l = initLinkStateDatabase();
    _store.put(l.linkStateID, l);
  }

  // this is for saving the neighbor and the port
  class Edge {
    String neighbor;
    int port;
    Edge(String neighbor, int port) {
      this.neighbor = neighbor;
      this.port = port;
    }
  }

  // pair template
  private static class Pair<f, s> {
    f first;
    s second;
    Pair(f first, s second) {
      this.first = first;
      this.second = second;
    }
  }

  public String getShortestPath(String destinationIP) {
    String source = rd.simulatedIPAddress;
    if (source.equals(destinationIP)) {
      return source;
    }
    // create a graph -> node = IP & edge = neighbor IP + port number
    Map<String, List<Edge>> graph = new HashMap<>();
    for (LSA lsa : _store.values()) {
      String routerID = lsa.linkStateID;
      List<Edge> edges = new ArrayList<>();
      for (LinkDescription ld : lsa.links) {
        if (ld.portNum != -1 && !ld.linkID.equals(routerID)) {
          edges.add(new Edge(ld.linkID, ld.portNum));
        }
      }
      graph.put(routerID, edges);
    }

    // BFS
    Map<String, Pair<String, Integer>> pred = new HashMap<>();
    Queue<String> queue = new LinkedList<>();
    queue.add(source);
    pred.put(source, new Pair<>(null, -1));

    boolean found = false;
    while (!queue.isEmpty()) {
      String current = queue.poll();
      if (current.equals(destinationIP)) {
        found = true;
        break;
      }
      List<Edge> neighbors = graph.get(current);
      if (neighbors == null)
        continue;
      for (Edge edge : neighbors) {
        if (!pred.containsKey(edge.neighbor)) {
          pred.put(edge.neighbor, new Pair<>(current, edge.port));
          queue.add(edge.neighbor);
        }
      }
    }
    if (!found) {
      return "No path from " + source + " to " + destinationIP;
    }

    // back track --> nodes = IPs, portsUsed = ports
    List<String> nodes = new ArrayList<>();
    List<Integer> portsUsed = new ArrayList<>();
    String cur = destinationIP;
    while (cur != null) {
      nodes.add(cur);
      Pair<String, Integer> info = pred.get(cur);
      if (info != null) {
        cur = info.first;
        if (info.second != -1) {
          portsUsed.add(info.second);
        }
      }
      else {
        break;
      }
    }
    //dest -> source ==> rev(both)
    Collections.reverse(nodes);
    Collections.reverse(portsUsed);
    StringBuilder sb = new StringBuilder();
    sb.append(nodes.get(0));
    for (int i = 1; i < nodes.size(); i++) {
      sb.append(" ->(").append(portsUsed.get(i - 1)).append(") ").append(nodes.get(i));
    }
    return sb.toString();
  }



  //initialize the linkstate database by adding an entry about the router itself
  private LSA initLinkStateDatabase() {
    LSA lsa = new LSA();
    lsa.linkStateID = rd.simulatedIPAddress;
    lsa.lsaSeqNumber = Integer.MIN_VALUE;
    LinkDescription ld = new LinkDescription();
    ld.linkID = rd.simulatedIPAddress;
    ld.portNum = -1;
    lsa.links.add(ld);
    return lsa;
  }

  public String toString() {
    StringBuilder sb = new StringBuilder();
    for (LSA lsa: _store.values()) {
      sb.append(lsa.linkStateID).append("(" + lsa.lsaSeqNumber + ")").append(":\t");
      for (LinkDescription ld : lsa.links) {
        sb.append(ld.linkID).append(",").append(ld.portNum).append("\t");
      }
      sb.append("\n");
    }
    return sb.toString();
  }
}

