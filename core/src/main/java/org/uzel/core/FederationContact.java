package org.uzel.core;

import java.io.*;
import java.net.*;
import java.util.*;

/** Public signed directory endpoint. The code carries certificates and signatures, never private keys. */
public final class FederationContact {
 public static final long TTL_MILLIS=30L*24*60*60*1000;
 static final int MAX_BYTES=32768;
 private static final String PREFIX="peernet://";
 public final String host,fingerprint;
 public final int port;
 public final long issuedAt,expiresAt;
 public final List<FederationContact> backups;
 private final byte[] encoded;
 private FederationContact(String host,int port,String fingerprint,long issued,long expires,List<FederationContact> backups,byte[] encoded) {
  this.host=host;this.port=port;this.fingerprint=fingerprint;issuedAt=issued;expiresAt=expires;
  this.backups=Collections.unmodifiableList(new ArrayList<>(backups));this.encoded=encoded.clone();
 }
 public static FederationContact create(TlsIdentity identity,String host,int port,long now)throws IOException {
  return create(identity,host,port,now,Collections.emptyList());
 }
 public static FederationContact create(TlsIdentity identity,String host,int port,long now,List<FederationContact> backups)throws IOException {
  try{new Invitation(host,port,identity.fingerprint,identity.fingerprint);}catch(IllegalArgumentException e){throw new IOException("Contact endpoint",e);}
  if(backups.size()>7)throw new IOException("Contact backup count");
  ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
  out.writeUTF("PEERNET-FEDERATION-CONTACT-1");out.writeUTF(host);out.writeInt(port);out.writeLong(now);out.writeLong(Math.addExact(now,TTL_MILLIS));
  out.writeInt(backups.size());for(FederationContact b:backups){if(!b.backups.isEmpty())throw new IOException("Nested contacts");DiscoveryWire.field(out,b.encoded);}
  return parseBytes(DiscoveryWire.signed(identity,bytes.toByteArray()),System.currentTimeMillis(),true);
 }
 public String encode(){return PREFIX+Base64.getUrlEncoder().withoutPadding().encodeToString(encoded);}
 public static FederationContact parse(String code)throws IOException {
  if(code==null || !code.startsWith(PREFIX) || code.length()>MAX_BYTES*4/3+32)throw new IOException("Contact code size/version");
  try{return parseBytes(Base64.getUrlDecoder().decode(code.substring(PREFIX.length())),System.currentTimeMillis(),true);}
  catch(IllegalArgumentException e){throw new IOException("Contact code encoding",e);}
 }
 static FederationContact parseBytes(byte[] encoded,long now,boolean allowBackups)throws IOException {
  return parseBytes(encoded,now,allowBackups,false);
 }
 private static FederationContact parseBytes(byte[] encoded,long now,boolean allowBackups,boolean expiredBackup)throws IOException {
  byte[][] verified=DiscoveryWire.verify(encoded,MAX_BYTES);String fingerprint=DiscoveryWire.fingerprint(verified[0]);
  DataInputStream in=new DataInputStream(new ByteArrayInputStream(verified[1]));
  if(!"PEERNET-FEDERATION-CONTACT-1".equals(in.readUTF()))throw new IOException("Contact version");
  String host=in.readUTF();int port=in.readInt();long issued=in.readLong(),expires=in.readLong();
  try{new Invitation(host,port,fingerprint,fingerprint);}catch(IllegalArgumentException e){throw new IOException("Contact endpoint",e);}
  if(issued<0 || issued>now+30000 || !expiredBackup && expires<=now || expires-issued!=TTL_MILLIS)throw new IOException("Contact expired");
  int count=in.readInt();if(count<0 || count>7 || !allowBackups && count!=0)throw new IOException("Contact backups");
  List<FederationContact> backups=new ArrayList<>();Set<String> pins=new HashSet<>();pins.add(fingerprint);
  for(int i=0;i<count;i++){FederationContact backup=parseBytes(DiscoveryWire.field(in,8192),now,false,true);if(!pins.add(backup.fingerprint))throw new IOException("Duplicate contacts");if(backup.expiresAt>now)backups.add(backup);}
  DiscoveryWire.end(in);return new FederationContact(host,port,fingerprint,issued,expires,backups,encoded);
 }
 /** Resolve once and connect to the returned IP, preventing a second DNS lookup/rebinding. */
 public InetSocketAddress address(boolean allowPrivate)throws IOException {
  if(expiresAt<=System.currentTimeMillis())throw new IOException("Contact expired");
  return address(InetAddress.getAllByName(host),allowPrivate);
 }
 public InetSocketAddress address(InetAddress[] addresses,boolean allowPrivate)throws IOException {
  if(expiresAt<=System.currentTimeMillis())throw new IOException("Contact expired");
  if(addresses==null || addresses.length==0 || addresses.length>32)throw new UnknownHostException(host);
  for(InetAddress ip:addresses) {
   if(ip==null || !allowed(ip,allowPrivate))throw new IOException("Contact address is not allowed");
  }
  return new InetSocketAddress(addresses[0],port);
 }
 private static boolean allowed(InetAddress ip,boolean allowPrivate) {
  if(PublicAddress.allowed(ip))return true;
  // Explicit LAN setting permits LAN/loopback fixtures, never wildcard, multicast or link-local metadata endpoints.
  if(!allowPrivate || ip.isAnyLocalAddress() || ip.isMulticastAddress() || ip.isLinkLocalAddress())return false;
  byte[] b=ip.getAddress();return ip.isLoopbackAddress() || ip.isSiteLocalAddress() || b.length==16 && (b[0]&0xfe)==0xfc;
 }
 byte[] bytes(){return encoded.clone();}
}
