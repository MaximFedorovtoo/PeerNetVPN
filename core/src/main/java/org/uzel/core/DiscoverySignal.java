package org.uzel.core;

import java.io.*;

/** Signed opaque signaling envelope. Forwarders never need SDP or invitation secrets. */
public final class DiscoverySignal {
    public final String senderFingerprint,recipientFingerprint,id;
    public final long issuedAt,expiresAt;
    final DiscoveryPresence sender;
    final byte[] recipientKeyHash,iv,ciphertext,aad;
    private final byte[] encoded;
    private DiscoverySignal(DiscoveryPresence sender,String recipient,String id,long issued,long expires,byte[] keyHash,byte[] iv,byte[] ciphertext,byte[] aad,byte[] encoded){
        this.sender=sender;senderFingerprint=sender.fingerprint;recipientFingerprint=recipient;this.id=id;issuedAt=issued;expiresAt=expires;
        recipientKeyHash=keyHash;this.iv=iv;this.ciphertext=ciphertext;this.aad=aad;this.encoded=encoded;
    }
    public byte[] encode(){return encoded.clone();}
    static byte[] header(DiscoveryPresence sender,String recipient,String id,long issued,long expires,byte[] keyHash,byte[] iv)throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
        out.writeUTF("UZEL-DISCOVERY-SIGNAL-1");DiscoveryWire.field(out,sender.encode());out.writeUTF(recipient);out.writeUTF(id);
        out.writeLong(issued);out.writeLong(expires);DiscoveryWire.field(out,keyHash);DiscoveryWire.field(out,iv);return bytes.toByteArray();
    }
    static DiscoverySignal create(TlsIdentity identity,byte[] header,byte[] ciphertext,long now)throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();bytes.write(header);DiscoveryWire.field(new DataOutputStream(bytes),ciphertext);
        return parse(DiscoveryWire.signed(identity,bytes.toByteArray()),now);
    }
    public static DiscoverySignal parse(byte[] encoded,long now)throws IOException {
        byte[][] verified=DiscoveryWire.verify(encoded,100000);DataInputStream in=new DataInputStream(new ByteArrayInputStream(verified[1]));
        if(!in.readUTF().equals("UZEL-DISCOVERY-SIGNAL-1"))throw new IOException("Signal version");
        DiscoveryPresence sender=DiscoveryPresence.parse(DiscoveryWire.field(in,8192),now);String recipient=in.readUTF(),id=in.readUTF();
        long issued=in.readLong(),expires=in.readLong();byte[] keyHash=DiscoveryWire.field(in,32),iv=DiscoveryWire.field(in,12);
        int aadLength=verified[1].length-in.available();byte[] ciphertext=DiscoveryWire.field(in,65552);DiscoveryWire.end(in);
        DiscoveryWire.time(issued,expires,now);
        if(!recipient.matches("[0-9a-f]{64}") || !id.matches("[0-9a-f]{64}") || keyHash.length!=32 || iv.length!=12 || ciphertext.length<16
                || expires>sender.expiresAt || !sender.fingerprint.equals(DiscoveryWire.fingerprint(verified[0])))throw new IOException("Signal fields");
        return new DiscoverySignal(sender,recipient,id,issued,expires,keyHash,iv,ciphertext,java.util.Arrays.copyOf(verified[1],aadLength),encoded.clone());
    }
}
