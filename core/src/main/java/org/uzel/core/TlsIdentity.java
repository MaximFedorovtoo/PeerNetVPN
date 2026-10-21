package org.uzel.core;

import javax.net.ssl.*;
import java.security.*;
import java.security.cert.*;

/** TLS identity; the invitation, not a public CA, authenticates a server. */
public final class TlsIdentity {
    private final KeyManager[] managers;
    private final X509Certificate certificate;
    private final PrivateKey signingKey;
    public final String fingerprint;
    private TlsIdentity(KeyManager[] managers, X509Certificate certificate,PrivateKey signingKey) throws Exception {
        this.managers=managers;this.certificate=certificate;this.signingKey=signingKey; fingerprint=fingerprint(certificate);
    }
    public static TlsIdentity fromKeyStore(KeyStore store,char[] password,String alias) throws Exception {
        X509Certificate cert=(X509Certificate)store.getCertificate(alias);
        PrivateKey key=(PrivateKey)store.getKey(alias,password);
        if(cert==null || key==null)throw new KeyStoreException("Missing node key");
        // AndroidKeyStore may contain unrelated aliases. TLS must select the exact
        // certificate whose fingerprint is advertised and whose key signs records.
        X509ExtendedKeyManager manager=new X509ExtendedKeyManager(){
            private String pick(String[] types){for(String type:types)if("EC".equals(type))return alias;return null;}
            public String[] getClientAliases(String type,Principal[] issuers){return "EC".equals(type)?new String[]{alias}:null;}
            public String[] getServerAliases(String type,Principal[] issuers){return getClientAliases(type,issuers);}
            public String chooseClientAlias(String[] types,Principal[] issuers,java.net.Socket socket){return pick(types);}
            public String chooseServerAlias(String type,Principal[] issuers,java.net.Socket socket){return pick(new String[]{type});}
            public String chooseEngineClientAlias(String[] types,Principal[] issuers,SSLEngine engine){return pick(types);}
            public String chooseEngineServerAlias(String type,Principal[] issuers,SSLEngine engine){return pick(new String[]{type});}
            public X509Certificate[] getCertificateChain(String selected){return alias.equals(selected)?new X509Certificate[]{cert}:null;}
            public PrivateKey getPrivateKey(String selected){return alias.equals(selected)?key:null;}
        };
        return new TlsIdentity(new KeyManager[]{manager},cert,key);
    }
    byte[] certificateBytes() throws CertificateEncodingException {return certificate.getEncoded();}
    byte[] sign(byte[] bytes) throws GeneralSecurityException {
        Signature signature=Signature.getInstance("SHA256withECDSA");signature.initSign(signingKey);signature.update(bytes);return signature.sign();
    }
    static X509Certificate verifyRecord(byte[] certificate,byte[] payload,byte[] signature)throws GeneralSecurityException {
        X509Certificate cert=(X509Certificate)CertificateFactory.getInstance("X.509").generateCertificate(new java.io.ByteArrayInputStream(certificate));
        checkSelfSigned(new X509Certificate[]{cert});
        Signature check=Signature.getInstance("SHA256withECDSA");check.initVerify(cert);check.update(payload);
        if(!check.verify(signature))throw new SignatureException("Record signature");
        return cert;
    }
    public SSLContext context(String pin) throws GeneralSecurityException {
        X509TrustManager trust=new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            public void checkClientTrusted(X509Certificate[] chain,String auth) throws CertificateException {
                // Possession is proved by TLS. The separate 256-bit secret authorizes membership.
                checkSelfSigned(chain);
            }
            public void checkServerTrusted(X509Certificate[] chain,String auth) throws CertificateException {
                checkSelfSigned(chain);
                if(pin==null || !constantEquals(pin,fingerprint(chain[0])))
                    throw new CertificateException("Unrecognized peer certificate");
            }
        };
        SSLContext context=SSLContext.getInstance("TLS");
        context.init(managers,new TrustManager[]{trust},new SecureRandom());
        return context;
    }
    private static void checkSelfSigned(X509Certificate[] chain) throws CertificateException {
        if(chain==null || chain.length!=1) throw new CertificateException("Expected a node certificate");
        try {
            chain[0].checkValidity();
            chain[0].verify(chain[0].getPublicKey());
            if(!"EC".equals(chain[0].getPublicKey().getAlgorithm())) throw new CertificateException("EC required");
        } catch(GeneralSecurityException e) { throw new CertificateException("Invalid node certificate",e); }
    }
    public static String fingerprint(java.security.cert.Certificate cert) throws CertificateException {
        try {return hex(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()));}
        catch(NoSuchAlgorithmException e){throw new CertificateException(e);}
    }
    public static boolean constantEquals(String a,String b) {
        return a!=null && b!=null && MessageDigest.isEqual(
                a.getBytes(java.nio.charset.StandardCharsets.UTF_8),b.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    public static String randomSecret() {byte[] b=new byte[32];new SecureRandom().nextBytes(b);return hex(b);}
    private static String hex(byte[] bytes) {
        StringBuilder out=new StringBuilder(bytes.length*2);
        for(byte b:bytes) out.append(Character.forDigit((b>>>4)&15,16)).append(Character.forDigit(b&15,16));
        return out.toString();
    }
}
