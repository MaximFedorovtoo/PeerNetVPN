package org.uzel.core;

import java.io.*;
import java.security.*;
import java.security.spec.*;
import java.security.interfaces.ECPublicKey;

/** Signed availability claim; neither country nor speed is independently attested. */
public final class DiscoveryPresence {
    public final String fingerprint,country;
    public final int clients;
    public final long upload,issuedAt,expiresAt;
    final byte[] encryptionKey;
    private final byte[] encoded;
    private DiscoveryPresence(String fingerprint,String country,int clients,long upload,long issued,long expires,byte[] key,byte[] encoded){
        this.fingerprint=fingerprint;this.country=country;this.clients=clients;this.upload=upload;issuedAt=issued;expiresAt=expires;encryptionKey=key;this.encoded=encoded;
    }
    public byte[] encode(){return encoded.clone();}
    static DiscoveryPresence create(TlsIdentity identity,String country,int clients,long upload,byte[] key,long now)throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
        out.writeUTF("UZEL-DISCOVERY-PRESENCE-1");out.writeUTF(country);out.writeInt(clients);out.writeLong(upload);out.writeLong(now);out.writeLong(now+DiscoveryWire.TTL);DiscoveryWire.field(out,key);
        return parse(DiscoveryWire.signed(identity,bytes.toByteArray()),now);
    }
    public static DiscoveryPresence parse(byte[] encoded,long now)throws IOException {
        byte[][] verified=DiscoveryWire.verify(encoded,8192);DataInputStream in=new DataInputStream(new ByteArrayInputStream(verified[1]));
        if(!in.readUTF().equals("UZEL-DISCOVERY-PRESENCE-1"))throw new IOException("Presence version");
        String country=in.readUTF();int clients=in.readInt();long upload=in.readLong(),issued=in.readLong(),expires=in.readLong();byte[] key=DiscoveryWire.field(in,256);DiscoveryWire.end(in);
        if(!country.matches("[A-Z]{2}|") || clients<0 || clients>3 || upload<=0)throw new IOException("Presence fields");
        DiscoveryWire.time(issued,expires,now);publicKey(key);
        return new DiscoveryPresence(DiscoveryWire.fingerprint(verified[0]),country,clients,upload,issued,expires,key,encoded.clone());
    }
    static PublicKey publicKey(byte[] encoded)throws IOException {
        try{
            PublicKey key=KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(encoded));
            AlgorithmParameters parameters=AlgorithmParameters.getInstance("EC");parameters.init(new ECGenParameterSpec("secp256r1"));
            ECParameterSpec expected=parameters.getParameterSpec(ECParameterSpec.class),actual=((ECPublicKey)key).getParams();
            if(!actual.getCurve().equals(expected.getCurve()) || !actual.getGenerator().equals(expected.getGenerator())
                    || !actual.getOrder().equals(expected.getOrder()) || actual.getCofactor()!=expected.getCofactor())throw new IOException("Presence key curve");
            return key;
        }catch(GeneralSecurityException|ClassCastException e){throw new IOException("Presence key",e);}
    }
}
