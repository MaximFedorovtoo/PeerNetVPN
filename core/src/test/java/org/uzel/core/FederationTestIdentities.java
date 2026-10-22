package org.uzel.core;

import java.io.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.*;
import java.security.spec.ECGenParameterSpec;
import java.text.SimpleDateFormat;
import java.util.*;

/** Fast real self-signed test identities, using only standard JCA and DER encoding. */
final class FederationTestIdentities {
 private static byte[] der(int tag,byte[]... children)throws IOException {
  ByteArrayOutputStream body=new ByteArrayOutputStream();for(byte[] child:children)body.write(child);byte[] value=body.toByteArray();ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(tag);
  if(value.length<128)out.write(value.length);else{int bytes=0;for(int n=value.length;n>0;n>>>=8)bytes++;out.write(128|bytes);for(int i=bytes-1;i>=0;i--)out.write(value.length>>>(i*8));}out.write(value);return out.toByteArray();
 }
 static TlsIdentity create()throws Exception {
  KeyPairGenerator generator=KeyPairGenerator.getInstance("EC");generator.initialize(new ECGenParameterSpec("secp256r1"));KeyPair key=generator.generateKeyPair();
  byte[] algorithm=der(0x30,new byte[]{6,8,42,(byte)134,72,(byte)206,61,4,3,2});
  byte[] name=der(0x30,der(0x31,der(0x30,new byte[]{6,3,85,4,3},der(12,"Federation fixture".getBytes(StandardCharsets.UTF_8)))));
  SimpleDateFormat time=new SimpleDateFormat("yyMMddHHmmss'Z'",Locale.ROOT);time.setTimeZone(TimeZone.getTimeZone("UTC"));long now=System.currentTimeMillis();
  byte[] valid=der(0x30,der(23,time.format(new Date(now-86400000)).getBytes(StandardCharsets.US_ASCII)),der(23,time.format(new Date(now+86400000)).getBytes(StandardCharsets.US_ASCII)));
  byte[] tbs=der(0x30,der(0xa0,der(2,new byte[]{2})),der(2,new BigInteger(120,new SecureRandom()).add(BigInteger.ONE).toByteArray()),algorithm,name,valid,name,key.getPublic().getEncoded());
  Signature signer=Signature.getInstance("SHA256withECDSA");signer.initSign(key.getPrivate());signer.update(tbs);byte[] signature=signer.sign();byte[] bits=new byte[signature.length+1];System.arraycopy(signature,0,bits,1,signature.length);
  byte[] certificate=der(0x30,tbs,algorithm,der(3,bits));X509Certificate cert=(X509Certificate)CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(certificate));cert.verify(key.getPublic());
  KeyStore store=KeyStore.getInstance("PKCS12");store.load(null,null);char[] password="fixture-password".toCharArray();store.setKeyEntry("node",key.getPrivate(),password,new java.security.cert.Certificate[]{cert});return TlsIdentity.fromKeyStore(store,password,"node");
 }
}
