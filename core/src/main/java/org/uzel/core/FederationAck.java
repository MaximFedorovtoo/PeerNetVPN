package org.uzel.core;
import java.io.*;

/** Holder-signed promise for one exact payload, issued only after its atomic durable write. */
final class FederationAck {
 static final String PROCESS_CRASH="PROCESS_CRASH",DIRECTORY_SYNCED="DIRECTORY_SYNCED";
 final String holder,digest,id;
 final String durability;
 final long version,retainedUntil;
 private final byte[] encoded;
 private FederationAck(String holder,String digest,String id,long version,long until,String durability,byte[] encoded){this.holder=holder;this.digest=digest;this.id=id;this.version=version;retainedUntil=until;this.durability=durability;this.encoded=encoded.clone();}
 static FederationAck create(TlsIdentity identity,FederationRecord record)throws IOException {
  return create(identity,record,PROCESS_CRASH);
 }
 static FederationAck create(TlsIdentity identity,FederationRecord record,String durability)throws IOException {
  ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);out.writeUTF("PEERNET-FEDERATION-ACK-2");out.writeUTF(record.digest);out.writeUTF(record.id);out.writeLong(record.version);out.writeLong(record.expiresAt);out.writeUTF(durability);
  return parse(DiscoveryWire.signed(identity,bytes.toByteArray()),System.currentTimeMillis());
 }
 static FederationAck parse(byte[] bytes,long now)throws IOException {
  byte[][] verified=DiscoveryWire.verify(bytes,8192);DataInputStream in=new DataInputStream(new ByteArrayInputStream(verified[1]));if(!"PEERNET-FEDERATION-ACK-2".equals(in.readUTF()))throw new IOException("Replica ACK version");
  String digest=in.readUTF(),id=in.readUTF();long version=in.readLong(),until=in.readLong();String durability=in.readUTF();DiscoveryWire.end(in);
  if(!digest.matches("[0-9a-f]{64}") || !id.matches("[pr]:[0-9a-f]{64}|s:[0-9a-f]{64}:[0-9a-f]{64}") || version<0 || until<=now || until>now+DiscoveryWire.TTL+30000)throw new IOException("Replica ACK fields");
  if(!PROCESS_CRASH.equals(durability) && !DIRECTORY_SYNCED.equals(durability))throw new IOException("Replica ACK durability");
  return new FederationAck(DiscoveryWire.fingerprint(verified[0]),digest,id,version,until,durability,bytes);
 }
 boolean matches(FederationRecord record){return digest.equals(record.digest) && id.equals(record.id) && version==record.version && retainedUntil==record.expiresAt;}
 byte[] bytes(){return encoded.clone();}
}
