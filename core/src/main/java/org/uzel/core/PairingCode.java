package org.uzel.core;

import java.io.*;
import java.nio.charset.*;
import java.security.*;
import java.util.*;

/** Confidential, signed manual signaling. Never publish or log this invitation-bearing code. */
public final class PairingCode {
    private static final String PREFIX="uzel-pair://";
    private static final int MAGIC=0x555a5031,MAX_TEXT=65536,MAX_SDP=32768;
    public static final long LIFETIME_MS=300000;
    public final String type,sdp,fingerprint,nonce,offerHash;
    public final Invitation invitation;
    public final long issuedAt,expiresAt;
    private final String encoded;
    private PairingCode(String type,String sdp,Invitation invitation,String nonce,String offerHash,long issuedAt,long expiresAt,String encoded){
        this.type=type;this.sdp=sdp;this.invitation=invitation;this.fingerprint=invitation.fingerprint;
        this.nonce=nonce;this.offerHash=offerHash;this.issuedAt=issuedAt;this.expiresAt=expiresAt;this.encoded=encoded;
    }
    public String encode(){return encoded;}
    public static PairingCode offer(TlsIdentity identity,String sdp,Invitation invitation,long now)throws IOException {
        return create(identity,"offer",sdp,invitation,"",now,now+LIFETIME_MS);
    }
    public static PairingCode answer(TlsIdentity identity,String sdp,Invitation invitation,PairingCode offer,long now)throws IOException {
        offer.validateTime(now);if(!offer.type.equals("offer"))throw new IOException("Expected offer");
        return create(identity,"answer",sdp,invitation,hash(offer.encoded),now,Math.min(now+LIFETIME_MS,offer.expiresAt));
    }
    private static PairingCode create(TlsIdentity identity,String type,String sdp,Invitation invitation,String offerHash,long now,long expires)throws IOException {
        if(invitation==null || !identity.fingerprint.equals(invitation.fingerprint))throw new IOException("Pairing identity mismatch");
        if(sdp==null)throw new IOException("Missing SDP");
        byte[] description=sdp.getBytes(StandardCharsets.UTF_8);
        if(description.length==0 || description.length>MAX_SDP)throw new IOException("SDP size");
        try {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
            out.writeInt(MAGIC);out.writeUTF(type);out.writeLong(now);out.writeLong(expires);
            out.writeUTF(TlsIdentity.randomSecret());out.writeUTF(offerHash);out.writeUTF(invitation.encode());write(out,description);
            byte[] payload=bytes.toByteArray();bytes.reset();
            write(out,identity.certificateBytes());write(out,payload);write(out,identity.sign(payload));
            String code=PREFIX+Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
            return parseAndVerify(code,now);
        }catch(GeneralSecurityException e){throw new IOException("Pairing signature",e);}
    }
    public static PairingCode parseAndVerify(String text,long now)throws IOException {
        if(text==null || text.length()>MAX_TEXT || !text.startsWith(PREFIX))throw new IOException("Pairing format");
        try {
            String base=text.substring(PREFIX.length());byte[] envelope=Base64.getUrlDecoder().decode(base);
            if(!Base64.getUrlEncoder().withoutPadding().encodeToString(envelope).equals(base))throw new IOException("Noncanonical pairing");
            DataInputStream in=new DataInputStream(new ByteArrayInputStream(envelope));
            byte[] cert=read(in,4096),payload=read(in,40000),signature=read(in,128);
            if(in.available()!=0)throw new IOException("Trailing pairing data");
            String fingerprint=TlsIdentity.fingerprint(TlsIdentity.verifyRecord(cert,payload,signature));
            in=new DataInputStream(new ByteArrayInputStream(payload));if(in.readInt()!=MAGIC)throw new IOException("Pairing version");
            String type=in.readUTF();long issued=in.readLong(),expires=in.readLong();
            String nonce=in.readUTF(),offerHash=in.readUTF();Invitation invitation=Invitation.parse(in.readUTF());
            String sdp=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(read(in,MAX_SDP))).toString();
            if(in.available()!=0 || sdp.isEmpty() || !fingerprint.equals(invitation.fingerprint) || !nonce.matches("[0-9a-f]{64}")
                    || !(type.equals("offer") && offerHash.isEmpty() || type.equals("answer") && offerHash.matches("[0-9a-f]{64}")))throw new IOException("Pairing fields");
            PairingCode code=new PairingCode(type,sdp,invitation,nonce,offerHash,issued,expires,text);code.validateTime(now);return code;
        }catch(GeneralSecurityException|IllegalArgumentException e){throw new IOException("Invalid pairing",e);}
    }
    private void validateTime(long now)throws IOException {
        if(issuedAt<0 || issuedAt>now+30000 || expiresAt<=issuedAt || expiresAt-issuedAt>LIFETIME_MS || now>=expiresAt)throw new IOException("Pairing expired or clock mismatch");
    }
    public static void verifyAnswer(PairingCode answer,PairingCode originalOffer,long now)throws IOException {
        answer.validateTime(now);originalOffer.validateTime(now);
        if(!answer.type.equals("answer") || !originalOffer.type.equals("offer") || !answer.offerHash.equals(hash(originalOffer.encoded))
                || answer.expiresAt>originalOffer.expiresAt)throw new IOException("Answer belongs to another offer");
    }
    private static String hash(String value)throws IOException {
        try {byte[] bytes=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result=new StringBuilder();for(byte b:bytes)result.append(String.format(Locale.ROOT,"%02x",b&255));return result.toString();
        }catch(NoSuchAlgorithmException e){throw new IOException(e);}
    }
    private static void write(DataOutputStream out,byte[] bytes)throws IOException {out.writeInt(bytes.length);out.write(bytes);}
    private static byte[] read(DataInputStream in,int max)throws IOException {
        int length=in.readInt();if(length<=0 || length>max || length>in.available())throw new IOException("Pairing length");
        byte[] bytes=new byte[length];in.readFully(bytes);return bytes;
    }
    /** No eviction of live entries: a full window rejects new codes until one expires. */
    public static final class ReplayWindow {
        private final Map<String,Long> used=new HashMap<>();
        public synchronized boolean claim(PairingCode code,long now)throws IOException {
            code.validateTime(now);used.values().removeIf(expiry->expiry<=now);
            String key=code.type+":"+code.fingerprint+":"+code.nonce;
            if(used.containsKey(key) || used.size()>=128)return false;used.put(key,code.expiresAt);return true;
        }
    }
}
