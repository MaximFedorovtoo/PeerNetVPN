package org.uzel.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** One immutable end-signed payload; storage roles and holder ACKs are separate from its author. */
final class FederationRecord {
 final int kind;
 final String id,owner,digest;
 final long version,expiresAt;
 final Object value;
 private final byte[] encoded;
 private FederationRecord(int kind,String id,String owner,long version,long expires,Object value,byte[] bytes)throws IOException {
  this.kind=kind;this.id=id;this.owner=owner;this.version=version;expiresAt=expires;this.value=value;encoded=bytes.clone();
  ByteArrayOutputStream tagged=new ByteArrayOutputStream();tagged.write(kind);tagged.write(encoded);digest=hex(DiscoveryWire.hash(tagged.toByteArray()));
 }
 static FederationRecord of(DiscoveryPresence p)throws IOException {return new FederationRecord(1,"p:"+p.fingerprint,p.fingerprint,p.issuedAt,p.expiresAt,p,p.encode());}
 static FederationRecord of(DiscoverySignal s)throws IOException {return new FederationRecord(2,"s:"+s.senderFingerprint+":"+s.id,s.senderFingerprint,s.issuedAt,s.expiresAt,s,s.encode());}
 static FederationRecord of(PeerDirectory.Record r)throws IOException {return new FederationRecord(3,"r:"+r.fingerprint,r.fingerprint,r.sequence,r.expiresAt,r,r.encode().getBytes(StandardCharsets.US_ASCII));}
 static FederationRecord parse(int kind,byte[] bytes,long now)throws IOException {
  if(kind==1)return of(DiscoveryPresence.parse(bytes,now));if(kind==2)return of(DiscoverySignal.parse(bytes,now));
  if(kind==3){if(bytes.length>8192)throw new IOException("Route size");PeerDirectory.Record r=PeerDirectory.Record.parse(new String(bytes,StandardCharsets.US_ASCII));if(r.issuedAt>now+30000 || r.expiresAt<=now)throw new IOException("Route expired");return of(r);}
  throw new IOException("Federation payload kind");
 }
 byte[] bytes(){return encoded.clone();}
 int size(){return encoded.length+5;}
 static List<FederationRecord> frame(byte[] frame,long now)throws IOException {
  if(frame==null || frame.length>DiscoveryStore.MAX_FRAME)throw new IOException("Discovery frame size");
  DataInputStream in=new DataInputStream(new ByteArrayInputStream(frame));if(in.readInt()!=0x555a4431)throw new IOException("Discovery version");int count=in.readInt();if(count<0 || count>384)throw new IOException("Discovery count");
  List<FederationRecord> records=new ArrayList<>();for(int i=0;i<count;i++){int kind=in.readUnsignedByte();if(kind!=1 && kind!=2)throw new IOException("Discovery kind");records.add(parse(kind,DiscoveryWire.field(in,kind==1?8192:100000),now));}DiscoveryWire.end(in);return records;
 }
 static byte[] discoveryFrame(Collection<FederationRecord> records)throws IOException {
  ByteArrayOutputStream body=new ByteArrayOutputStream();DataOutputStream fields=new DataOutputStream(body);int count=0;
  for(FederationRecord r:records)if(r.kind!=3 && body.size()+r.size()+8<=DiscoveryStore.MAX_FRAME){fields.writeByte(r.kind);DiscoveryWire.field(fields,r.encoded);count++;}
  ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);out.writeInt(0x555a4431);out.writeInt(count);body.writeTo(out);return bytes.toByteArray();
 }
 static List<FederationRecord> bounded(Collection<FederationRecord> source) {
  List<List<FederationRecord>> kinds=Arrays.asList(new ArrayList<>(),new ArrayList<>(),new ArrayList<>());
  for(FederationRecord record:source)kinds.get(record.kind-1).add(record);
  for(List<FederationRecord> group:kinds)Collections.shuffle(group);
  List<FederationRecord> result=new ArrayList<>();int bytes=0;int[] positions=new int[3];boolean progress=true;
  while(result.size()<64 && progress) {
   progress=false;
   for(int kind=0;kind<3 && result.size()<64;kind++) {
    List<FederationRecord> group=kinds.get(kind);
    while(positions[kind]<group.size()) {
     FederationRecord record=group.get(positions[kind]++);
     if(bytes+record.size()<=DiscoveryStore.MAX_FRAME){result.add(record);bytes+=record.size();progress=true;break;}
    }
   }
  }
  return result;
 }
 static void latest(Map<String,FederationRecord> into,FederationRecord r) {
  FederationRecord previous=into.get(r.id);if(previous==null || r.version>previous.version || r.version==previous.version && r.digest.compareTo(previous.digest)>0)into.put(r.id,r);
 }
 static String hex(byte[] bytes){StringBuilder result=new StringBuilder(bytes.length*2);for(byte b:bytes)result.append(Character.forDigit((b>>>4)&15,16)).append(Character.forDigit(b&15,16));return result.toString();}
}
