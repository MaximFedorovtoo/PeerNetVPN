package org.uzel.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;

/** Memory-only ECDH key, certified by the existing signing-only TLS identity. */
public final class DiscoveryIdentity {
    private final TlsIdentity identity;private final KeyPair encryption;
    private DiscoveryPresence current;
    private final Map<String,Long> opened=new HashMap<>();
    public DiscoveryIdentity(TlsIdentity identity)throws IOException {
        this.identity=Objects.requireNonNull(identity);
        try{KeyPairGenerator generator=KeyPairGenerator.getInstance("EC");generator.initialize(new ECGenParameterSpec("secp256r1"));encryption=generator.generateKeyPair();}
        catch(GeneralSecurityException e){throw new IOException(e);}
    }
    public synchronized DiscoveryPresence presence(String country,int clients,long upload,long now)throws IOException {
        current=DiscoveryPresence.create(identity,country,clients,upload,encryption.getPublic().getEncoded(),now);return current;
    }
    public synchronized DiscoverySignal seal(DiscoveryPresence recipient,String pairingCode,long now)throws IOException {
        if(current==null)throw new IOException("Publish presence first");
        DiscoveryPresence.parse(current.encode(),now);DiscoveryPresence.parse(recipient.encode(),now);
        PairingCode code=PairingCode.parseAndVerify(pairingCode,now);
        if(!code.fingerprint.equals(identity.fingerprint))throw new IOException("Signal sender identity");
        byte[] plaintext=pairingCode.getBytes(StandardCharsets.UTF_8);if(plaintext.length>65536)throw new IOException("Signal plaintext size");
        byte[] iv=new byte[12];new SecureRandom().nextBytes(iv);
        long expires=Math.min(Math.min(current.expiresAt,recipient.expiresAt),now+DiscoveryWire.TTL);
        byte[] header=DiscoverySignal.header(current,recipient.fingerprint,TlsIdentity.randomSecret(),now,expires,DiscoveryWire.hash(recipient.encryptionKey),iv);
        byte[] encrypted=crypt(Cipher.ENCRYPT_MODE,recipient.encryptionKey,iv,header,plaintext);
        return DiscoverySignal.create(identity,header,encrypted,now);
    }
    public synchronized String open(DiscoverySignal input,long now)throws IOException {
        DiscoverySignal signal=DiscoverySignal.parse(input.encode(),now);
        if(!signal.recipientFingerprint.equals(identity.fingerprint) || !MessageDigest.isEqual(signal.recipientKeyHash,DiscoveryWire.hash(encryption.getPublic().getEncoded())))throw new IOException("Signal recipient");
        opened.values().removeIf(expiry->expiry<=now);
        String replay=signal.senderFingerprint+":"+signal.id;
        if(opened.containsKey(replay) || opened.size()>=128)throw new IOException("Signal replay or capacity");
        byte[] plaintext=crypt(Cipher.DECRYPT_MODE,signal.sender.encryptionKey,signal.iv,signal.aad,signal.ciphertext);
        String code=new String(plaintext,StandardCharsets.UTF_8);Arrays.fill(plaintext,(byte)0);
        PairingCode verified=PairingCode.parseAndVerify(code,now);
        if(!verified.fingerprint.equals(signal.senderFingerprint))throw new IOException("Signal payload identity");
        opened.put(replay,signal.expiresAt);return code;
    }
    private byte[] crypt(int mode,byte[] remote,byte[] iv,byte[] aad,byte[] payload)throws IOException {
        byte[] shared=null,key=null;
        try{
            KeyAgreement agreement=KeyAgreement.getInstance("ECDH");agreement.init(encryption.getPrivate());agreement.doPhase(DiscoveryPresence.publicKey(remote),true);shared=agreement.generateSecret();
            // RFC 5869 extract+single-block expand, domain and full signed header bound into the key.
            Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(DiscoveryWire.hash(aad),"HmacSHA256"));byte[] prk=mac.doFinal(shared);
            mac.init(new SecretKeySpec(prk,"HmacSHA256"));mac.update("UZEL-DISCOVERY-AES256GCM-1".getBytes(StandardCharsets.US_ASCII));mac.update((byte)1);key=mac.doFinal();Arrays.fill(prk,(byte)0);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(mode,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv));cipher.updateAAD(aad);return cipher.doFinal(payload);
        }catch(GeneralSecurityException e){throw new IOException("Signal encryption/authentication",e);}
        finally{if(shared!=null)Arrays.fill(shared,(byte)0);if(key!=null)Arrays.fill(key,(byte)0);}
    }
}
