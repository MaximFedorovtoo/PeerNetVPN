package org.uzel.core;

import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/** Bounded federation protocol, separate from unchanged Android peer protocol v1. */
public final class FederationExchange {
 static final int MAGIC=0x504e4633,MAX_FRAME=524288,TIMEOUT=3000;
 static final int PUBLISH=1,GOSSIP=2,REPLICA=3,QUERY=4,RESPONSE=5;
 private static final ScheduledThreadPoolExecutor DEADLINES=new ScheduledThreadPoolExecutor(1,r->{Thread t=new Thread(r,"peernet-directory-deadlines");t.setDaemon(true);return t;});
 static {DEADLINES.setRemoveOnCancelPolicy(true);}
 static ScheduledFuture<?> deadline(Socket socket){return deadline(socket,8000);}
 static ScheduledFuture<?> deadline(Socket socket,long millis){return DEADLINES.schedule(()->{try{socket.close();}catch(IOException ignored){}},Math.max(1,millis),TimeUnit.MILLISECONDS);}
 private FederationExchange(){}
 static final class Message {
  final int operation;
  final List<FederationRecord> records;
  final List<FederationContact> contacts;
  final List<FederationAck> acks;
  final String queryId;
  final String recipient;
  final int hops;
  final long queryDeadline;
  final List<String> visited;
  final boolean partial;
  Message(int operation,List<FederationRecord> records,List<FederationContact> contacts,List<FederationAck> acks,String queryId,int hops,long deadline,List<String> visited,boolean partial) {
   this(operation,records,contacts,acks,queryId,hops,deadline,visited,partial,"");
  }
  Message(int operation,List<FederationRecord> records,List<FederationContact> contacts,List<FederationAck> acks,String queryId,int hops,long deadline,List<String> visited,boolean partial,String recipient) {
   this.operation=operation;this.records=Collections.unmodifiableList(new ArrayList<>(records));this.contacts=Collections.unmodifiableList(new ArrayList<>(contacts));this.acks=Collections.unmodifiableList(new ArrayList<>(acks));this.queryId=queryId;this.hops=hops;queryDeadline=deadline;this.visited=Collections.unmodifiableList(new ArrayList<>(visited));this.partial=partial;this.recipient=recipient;
  }
  static Message simple(int operation,List<FederationRecord> records,List<FederationContact> contacts){return new Message(operation,records,contacts,Collections.emptyList(),"",0,0,Collections.emptyList(),true);}
 }
 public static final class Result {
  public final byte[] discoveryFrame;
  public final List<FederationContact> contacts;
  public final List<PeerDirectory.Record> routes;
  /** A bounded lookup cannot prove global absence, including an empty result. */
  public final boolean partial;
  public final int visitedServers;
  Result(Message message)throws IOException {
   discoveryFrame=FederationRecord.discoveryFrame(message.records);contacts=Collections.unmodifiableList(new ArrayList<>(message.contacts));
   List<PeerDirectory.Record> routes=new ArrayList<>();for(FederationRecord r:message.records)if(r.kind==3)routes.add((PeerDirectory.Record)r.value);this.routes=Collections.unmodifiableList(routes);partial=message.partial;visitedServers=message.visited.size();
  }
 }
 /** Only the authenticated caller's signed publications are uploaded; cached lookup results are never republished. */
 public static Result exchange(TlsIdentity identity,Socket connected,FederationContact contact,byte[] discovery)throws IOException {
  List<FederationRecord> publications=new ArrayList<>();for(FederationRecord r:FederationRecord.frame(discovery,System.currentTimeMillis()))if(r.owner.equals(identity.fingerprint))publications.add(r);
  return new Result(transact(identity,connected,contact,Message.simple(PUBLISH,FederationRecord.bounded(publications),Collections.emptyList()),8000));
 }
 static Message transact(TlsIdentity identity,Socket connected,FederationContact contact,Message request,long timeout)throws IOException {
  byte[] encoded=encode(request);if(!connected.isConnected() || connected.isClosed())throw new IOException("Connected socket required");if(contact.expiresAt<=System.currentTimeMillis())throw new IOException("Contact expired");
  ScheduledFuture<?> deadline=deadline(connected,timeout);
  try(SSLSocket tls=(SSLSocket)identity.context(contact.fingerprint).getSocketFactory().createSocket(connected,contact.host,contact.port,true)) {
   tls.setUseClientMode(true);tls.setSoTimeout((int)Math.max(1,Math.min(TIMEOUT,timeout)));tls.startHandshake();send(tls,encoded);Message response=receive(tls);
   if(response.operation!=RESPONSE || !request.queryId.equals(response.queryId) || !request.recipient.equals(response.recipient))throw new IOException("Federation response correlation");return response;
  }catch(java.security.GeneralSecurityException e){throw new IOException("Federation TLS",e);}finally{deadline.cancel(false);}
 }
 static byte[] encode(Message message)throws IOException {
  if(message.records.size()>64 || message.contacts.size()>8 || message.acks.size()>64 || message.visited.size()>8)throw new IOException("Federation counts");
  ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);out.writeInt(MAGIC);out.writeByte(message.operation);
  out.writeInt(message.records.size());int total=0;for(FederationRecord r:message.records){total+=r.size();if(total>DiscoveryStore.MAX_FRAME)throw new IOException("Federation payload budget");out.writeByte(r.kind);DiscoveryWire.field(out,r.bytes());}
  out.writeInt(message.contacts.size());for(FederationContact c:message.contacts)DiscoveryWire.field(out,c.bytes());
  out.writeInt(message.acks.size());for(FederationAck ack:message.acks)DiscoveryWire.field(out,ack.bytes());
  out.writeUTF(message.recipient);out.writeUTF(message.queryId);out.writeByte(message.hops);out.writeLong(message.queryDeadline);out.writeInt(message.visited.size());for(String pin:message.visited)out.writeUTF(pin);out.writeBoolean(message.partial);
  if(bytes.size()>MAX_FRAME)throw new IOException("Federation frame size");return bytes.toByteArray();
 }
 static void send(Socket socket,byte[] body)throws IOException {DataOutputStream out=new DataOutputStream(socket.getOutputStream());out.writeInt(body.length);out.write(body);out.flush();}
 static Message receive(Socket socket)throws IOException {
  DataInputStream input=new DataInputStream(socket.getInputStream());int length=input.readInt();if(length<24 || length>MAX_FRAME)throw new IOException("Federation frame size");byte[] body=new byte[length];input.readFully(body);return decode(body,System.currentTimeMillis());
 }
 static Message decode(byte[] bytes,long now)throws IOException {
  if(bytes.length>MAX_FRAME)throw new IOException("Federation frame size");DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes));if(in.readInt()!=MAGIC)throw new IOException("Federation version");int op=in.readUnsignedByte();if(op<PUBLISH || op>RESPONSE)throw new IOException("Federation operation");
  int count=count(in,64),total=0;List<FederationRecord> records=new ArrayList<>();for(int i=0;i<count;i++){int kind=in.readUnsignedByte();byte[] encoded=DiscoveryWire.field(in,kind==1?8192:kind==3?8192:100000);total+=encoded.length+5;if(total>DiscoveryStore.MAX_FRAME)throw new IOException("Federation payload budget");records.add(FederationRecord.parse(kind,encoded,now));}
  count=count(in,8);List<FederationContact> contacts=new ArrayList<>();for(int i=0;i<count;i++)contacts.add(FederationContact.parseBytes(DiscoveryWire.field(in,FederationContact.MAX_BYTES),now,true));
  count=count(in,64);List<FederationAck> acks=new ArrayList<>();for(int i=0;i<count;i++)acks.add(FederationAck.parse(DiscoveryWire.field(in,8192),now));
  String recipient=in.readUTF(),id=in.readUTF();int hops=in.readUnsignedByte();long deadline=in.readLong();count=count(in,8);List<String> visited=new ArrayList<>();for(int i=0;i<count;i++){String pin=in.readUTF();if(!pin.matches("[0-9a-f]{64}") || visited.contains(pin))throw new IOException("Query visited identities");visited.add(pin);}boolean partial=in.readBoolean();DiscoveryWire.end(in);
  if(hops>2 || !id.isEmpty() && !id.matches("[0-9a-f]{64}") || op==QUERY && (id.isEmpty() || deadline<=now || deadline>now+10000) || op!=QUERY && op!=RESPONSE && (!id.isEmpty() || deadline!=0 || hops!=0 || !visited.isEmpty()))throw new IOException("Query bounds");
  if(op==GOSSIP && !records.isEmpty() || op!=RESPONSE && !acks.isEmpty() || op==QUERY && !records.isEmpty())throw new IOException("Federation operation payload");
  if(!recipient.isEmpty() && (!recipient.matches("[0-9a-f]{64}") || op!=QUERY && op!=RESPONSE))throw new IOException("Query recipient");
  return new Message(op,records,contacts,acks,id,hops,deadline,visited,partial,recipient);
 }
 private static int count(DataInputStream in,int max)throws IOException {int count=in.readInt();if(count<0 || count>max)throw new IOException("Federation count");return count;}
}
