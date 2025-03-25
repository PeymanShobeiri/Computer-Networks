package socs.network.node;

import socs.network.message.LSA;
import socs.network.message.LinkDescription;

import java.util.*;

public class LinkStateDatabase {

  // linkStateID => LSA instance
  public HashMap<String, LSA> _store = new HashMap<>();

  private RouterDescription rd;

  public LinkStateDatabase(RouterDescription routerDescription) {
    rd = routerDescription;
    LSA initialLSA = initLinkStateDatabase();
    _store.put(initialLSA.linkStateID, initialLSA);
  }

  // this is for saving the neighbor and the port
  static class Edge {
    String neighbor;
    int port;
    int weight;

    Edge(String neighbor, int port, int weight) {
      this.neighbor = neighbor;
      this.port = port;
      this.weight = weight;
    }
  }

  // For the priority queue
  static class NodeDistance {
    String node;
    int dist;

    NodeDistance(String node, int dist) {
      this.node = node;
      this.dist = dist;
    }
  }


  public String getShortestPath(String destinationIP) {
    String source = rd.simulatedIPAddress;
    if (source.equals(destinationIP)) {
      return source;
    }

    // create graph -> node ==> list of Edge -> (neighbor, port, weight)
    Map<String, List<Edge>> graph = new HashMap<>();
    for (LSA lsa : _store.values()) {
      String routerID = lsa.linkStateID;
      List<Edge> edges = new ArrayList<>();
      for (LinkDescription ld : lsa.links) {
        if (!ld.linkID.equals(routerID)) {
          edges.add(new Edge(ld.linkID, ld.portNum, ld.weight));
        }
      }
      graph.put(routerID, edges);
    }

    if (!graph.containsKey(destinationIP) && !destinationIP.equals(source)) {
      return "No path from " + source + " to " + destinationIP;
    }

    // Dijkstra
    Map<String, Integer> dist = new HashMap<>();
    Map<String, String> pred = new HashMap<>();
    Map<String, Edge> usedEdge = new HashMap<>();

    for (String node : graph.keySet()) {
      dist.put(node, Integer.MAX_VALUE);
      pred.put(node, null);
      usedEdge.put(node, null);
    }

    dist.put(source, 0);
    pred.put(source, null);
    usedEdge.put(source, null);
    // select next closest node
    PriorityQueue<NodeDistance> pq = new PriorityQueue<>(Comparator.comparingInt(nd -> nd.dist));
    pq.add(new NodeDistance(source, 0));

    while (!pq.isEmpty()) {
      NodeDistance top = pq.poll();
      String curNode = top.node;
      int curDist = top.dist;
      // found the best route
      if (curNode.equals(destinationIP)) {
        break;
      }
      if (curDist > dist.get(curNode)) {
        continue;
      }
      List<Edge> neighbors = graph.get(curNode);
      if (neighbors == null)
        continue;

      for (Edge e : neighbors) {
        int newDist = curDist + e.weight;
        if (newDist < dist.get(e.neighbor)) {
          dist.put(e.neighbor, newDist);
          pred.put(e.neighbor, curNode);
          usedEdge.put(e.neighbor, e);
          pq.add(new NodeDistance(e.neighbor, newDist));
        }
      }
    }

    if (!dist.containsKey(destinationIP) || dist.get(destinationIP) == Integer.MAX_VALUE) {
      return "No path from " + source + " to " + destinationIP;
    }

    // nodes are stored but they are backward so:
    List<Edge> pathEdges = new ArrayList<>();
    String crawl = destinationIP;
    while (crawl != null && !crawl.equals(source)) {
      Edge e = usedEdge.get(crawl);
      if (e == null) break;
      pathEdges.add(e);
      crawl = pred.get(crawl);
    }
    Collections.reverse(pathEdges);

    // create the path
    StringBuilder sb = new StringBuilder();
    sb.append(source);
    String curNodeName = source;
    for (Edge e : pathEdges) {
      sb.append(" ->(p=").append(e.port).append(",w=").append(e.weight).append(") ");
      sb.append(e.neighbor);
      curNodeName = e.neighbor;
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
    ld.weight = 0;
    lsa.links.add(ld);
    return lsa;
  }

  public String toString() {
    StringBuilder sb = new StringBuilder();
    for (LSA lsa : _store.values()) {
      sb.append(lsa.linkStateID).append("(").append(lsa.lsaSeqNumber).append("):\t");
      for (LinkDescription ld : lsa.links) {
        sb.append(ld.linkID).append(",").append(ld.portNum).append(",").append(ld.weight).append("\t");
      }
      sb.append("\n");
    }
    return sb.toString();
  }

}
