package org.uzel.core;

import java.io.*;
import java.security.*;
import java.util.*;

final class DiscoveryWire {
    static final long TTL=120_000;
    static final int MAX_FRAME=131072;
    static void field(DataOutputStream out,byte[] value)throws IOException {out.writeInt(value.length);out.write(value);}
    static byte[] field(DataInputStream in,int max)throws IOException {
        int length=in.readInt();if(length<1 || length>max || length>in.available())throw new IOException("Discovery field size");
        byte[] value=new byte[length];in.readFully(value);return value;
    }
    static void end(DataInputStream in)throws IOException {if(in.available()!=0)throw new IOException("Discovery trailing bytes");}
    static void time(long issued,long expires,long now)throws IOException {
        if(issued<0 || issued>now+30_000 || expires<=issued || expires-issued>TTL || expires<=now)throw new IOException("Discovery expired");
    }
    static byte[] hash(byte[] bytes)throws IOException {try{return MessageDigest.getInstance("SHA-256").digest(bytes);}catch(GeneralSecurityException e){throw new IOException(e);}}
    static byte[] signed(TlsIdentity identity,byte[] payload)throws IOException {
        try{ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
            field(out,identity.certificateBytes());field(out,payload);field(out,identity.sign(payload));return bytes.toByteArray();
        }catch(GeneralSecurityException e){throw new IOException(e);}
    }
    static byte[][] verify(byte[] encoded,int max)throws IOException {
        if(encoded==null || encoded.length>max)throw new IOException("Discovery size");
        DataInputStream in=new DataInputStream(new ByteArrayInputStream(encoded));
        byte[] cert=field(in,4096),payload=field(in,max),signature=field(in,128);end(in);
        try{TlsIdentity.verifyRecord(cert,payload,signature);return new byte[][]{cert,payload};}catch(GeneralSecurityException e){throw new IOException("Discovery signature",e);}
    }
    static String fingerprint(byte[] cert)throws IOException {
        try{return TlsIdentity.fingerprint(java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(cert)));}
        catch(GeneralSecurityException e){throw new IOException(e);}
    }
}
