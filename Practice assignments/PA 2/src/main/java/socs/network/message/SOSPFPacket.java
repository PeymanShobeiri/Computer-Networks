package socs.network.message;

import java.io.*;
import java.util.Vector;

public class SOSPFPacket implements Serializable {

  //for inter-process communication
  public String srcProcessIP;
  public short srcProcessPort;
  public String srcIP;
  public String dstIP;
  public short sospfType;     //  0/1/2/3  -> hello/link state update/rejection/delete a port
  public String routerID;

  //used by HELLO message to identify the sender of the message
  //e.g. when router A sends HELLO to its neighbor, it has to fill this field with its own
  //simulated IP address
  public String neighborID; //neighbor's simulated IP address

  //used by LSAUPDATE
  public Vector<LSA> lsaArray = null;

}
