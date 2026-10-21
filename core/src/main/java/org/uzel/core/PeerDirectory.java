package org.uzel.core;

import java.io.*;
import java.security.*;
import java.util.*;

/** Bounded, signed soft-state catalog. Authenticity does not imply honest geography. */
public final class PeerDirectory {
    public static final long TTL_MILLIS=120_000;
    private final LinkedHashMap<String,Record> records=new LinkedHashMap<>();
    public synchronized boolean accept(Record record,long now){
        if(record.issuedAt>now+30_000 || record.expiresAt<=now || record.expiresAt-record.issuedAt!=TTL_MILLIS)return false;
        records.values().removeIf(r->r.expiresAt<=now);
        Record old=records.get(record.fingerprint);
        if(old!=null && record.sequence<=old.sequence)return false;
        if(old==null && records.size()>=256)return false;
        records.put(record.fingerprint,record);return true;
    }
    public synchronized List<Record> records(){
        long now=System.currentTimeMillis();records.values().removeIf(r->r.expiresAt<=now);
        return new ArrayList<>(records.values());
    }
    public synchronized void clear(){records.clear();}
    public static final class Record {
        public final PeerRoute route;
        public final String fingerprint,country;
        public final long sequence,issuedAt,expiresAt;
        private final byte[] encoded;
        private Record(PeerRoute route,String country,long sequence,long issuedAt,long expiresAt,byte[] encoded){
            this.route=route;this.fingerprint=route.fingerprint;this.country=country;this.sequence=sequence;
            this.issuedAt=issuedAt;this.expiresAt=expiresAt;this.encoded=encoded.clone();
        }
        static Record create(TlsIdentity identity,PeerRoute route,String country,long sequence)throws IOException {
            if(!identity.fingerprint.equals(route.fingerprint) || !country.matches("[A-Z]{2}|"))throw new IOException("Record identity");
            try{
                long now=System.currentTimeMillis();ByteArrayOutputStream body=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(body);
                out.writeInt(1);out.writeUTF(route.encode());out.writeUTF(country);out.writeLong(sequence);out.writeLong(now);out.writeLong(now+TTL_MILLIS);
                byte[] cert=identity.certificateBytes(),payload=body.toByteArray(),sig=identity.sign(payload);
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();out=new DataOutputStream(bytes);
                out.writeInt(cert.length);out.write(cert);out.writeInt(payload.length);out.write(payload);out.writeInt(sig.length);out.write(sig);
                return new Record(route,country,sequence,now,now+TTL_MILLIS,bytes.toByteArray());
            }catch(GeneralSecurityException e){throw new IOException("Signing record",e);}
        }
        public String encode(){return Base64.getUrlEncoder().withoutPadding().encodeToString(encoded);}
        private static byte[] field(DataInputStream in,int max)throws IOException{
            int n=in.readInt();if(n<1 || n>max)throw new IOException("Record field");byte[] data=new byte[n];in.readFully(data);return data;
        }
        public static Record parse(String value)throws IOException {
            if(value==null || value.length()>8192)throw new IOException("Record size");
            try{
                byte[] encoded=Base64.getUrlDecoder().decode(value);DataInputStream in=new DataInputStream(new ByteArrayInputStream(encoded));
                byte[] cert=field(in,4096),body=field(in,2048),sig=field(in,128);if(in.available()!=0)throw new IOException("Record trailing data");
                String fingerprint=TlsIdentity.fingerprint(TlsIdentity.verifyRecord(cert,body,sig));
                in=new DataInputStream(new ByteArrayInputStream(body));if(in.readInt()!=1)throw new IOException("Record version");
                PeerRoute route=PeerRoute.parse(in.readUTF()).asDiscovered();String country=in.readUTF();long sequence=in.readLong(),issued=in.readLong(),expiry=in.readLong();
                if(!fingerprint.equals(route.fingerprint) || !country.matches("[A-Z]{2}|") || sequence<0 || issued<0 || expiry-issued!=TTL_MILLIS || in.available()!=0)throw new IOException("Record fields");
                return new Record(route,country,sequence,issued,expiry,encoded);
            }catch(GeneralSecurityException|IllegalArgumentException e){throw new IOException("Invalid signed record",e);}
        }
    }
}
