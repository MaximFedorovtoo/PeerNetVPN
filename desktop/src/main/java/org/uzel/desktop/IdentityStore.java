package org.uzel.desktop;

import org.uzel.core.TlsIdentity;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.*;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import java.io.*;
import java.math.BigInteger;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.*;

/** Owner-only persistent EC key. Corruption fails closed: never silently changes identity. */
public final class IdentityStore {
    private IdentityStore(){}
    public static synchronized TlsIdentity load(Path root)throws Exception {
        Files.createDirectories(root);restrict(root);
        Path file=root.resolve("identity.p12");
        // PKCS12 integrity password is not an access control boundary; OS permissions protect the key.
        // Storage compatibility: existing PKCS12 identities use this password and certificate label.
        char[] password="PeerNet local identity".toCharArray();
        KeyStore store=KeyStore.getInstance("PKCS12");
        if(Files.exists(file)) {
            restrict(file);try(InputStream in=Files.newInputStream(file)){store.load(in,password);}
        } else {
            store.load(null,password);
            KeyPairGenerator generator=KeyPairGenerator.getInstance("EC");generator.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair key=generator.generateKeyPair();Instant now=Instant.now();
            X500Name name=new X500Name("CN=PeerNet");
            X509Certificate cert=new JcaX509CertificateConverter().getCertificate(new JcaX509v3CertificateBuilder(name,
                new BigInteger(159,new SecureRandom()).add(BigInteger.ONE),Date.from(now.minusSeconds(300)),
                Date.from(now.plusSeconds(20L*365*86400)),name,key.getPublic())
                .build(new JcaContentSignerBuilder("SHA256withECDSA").build(key.getPrivate())));
            cert.verify(key.getPublic());store.setKeyEntry("node",key.getPrivate(),password,new java.security.cert.Certificate[]{cert});
            Path temp=Files.createTempFile(root,"identity-",".tmp");
            try {restrict(temp);try(OutputStream out=Files.newOutputStream(temp)){store.store(out,password);}replace(temp,file);}
            finally{Files.deleteIfExists(temp);}
        }
        try{return TlsIdentity.fromKeyStore(store,password,"node");}finally{Arrays.fill(password,'\0');}
    }
    static void replace(Path temp,Path target)throws IOException {
        try{Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
        catch(AtomicMoveNotSupportedException e){Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING);}
    }
    private static void restrict(Path path)throws IOException {
        PosixFileAttributeView posix=Files.getFileAttributeView(path,PosixFileAttributeView.class);
        if(posix!=null){posix.setPermissions(PosixFilePermissions.fromString(Files.isDirectory(path)?"rwx------":"rw-------"));return;}
        AclFileAttributeView acl=Files.getFileAttributeView(path,AclFileAttributeView.class);
        if(acl!=null){
            AclEntry entry=AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(Files.getOwner(path))
                .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build();acl.setAcl(List.of(entry));
        }
    }
}
