package org.uzel.core;

import java.io.*;
import java.util.*;

/** Bounded soft-state gossip cache. It stores signed records, never decrypted signals. */
public final class DiscoveryStore {
    public static final int MAX_FRAME=131072;
    private final Map<String,DiscoveryPresence> presences=new LinkedHashMap<>();
    private final Map<String,DiscoverySignal> signals=new LinkedHashMap<>();
    private void expire(long now){presences.values().removeIf(p->p.expiresAt<=now);signals.values().removeIf(s->s.expiresAt<=now);}
    public synchronized boolean accept(DiscoveryPresence input,long now)throws IOException {
        DiscoveryPresence record=DiscoveryPresence.parse(input.encode(),now);expire(now);DiscoveryPresence old=presences.get(record.fingerprint);
        if(old!=null && record.issuedAt<=old.issuedAt || old==null && presences.size()>=256)return false;
        presences.put(record.fingerprint,record);return true;
    }
    public synchronized boolean accept(DiscoverySignal input,long now)throws IOException {
        DiscoverySignal signal=DiscoverySignal.parse(input.encode(),now);expire(now);String id=signal.senderFingerprint+":"+signal.id;
        if(signals.containsKey(id) || signals.size()>=128)return false;
        long owner=signals.values().stream().filter(s->s.senderFingerprint.equals(signal.senderFingerprint)).count();
        if(owner>=8)return false;signals.put(id,signal);return true;
    }
    public synchronized List<DiscoveryPresence> presences(long now){expire(now);return Collections.unmodifiableList(new ArrayList<>(presences.values()));}
    public synchronized List<DiscoverySignal> signalsFor(String fingerprint,long now){expire(now);List<DiscoverySignal> result=new ArrayList<>();for(DiscoverySignal s:signals.values())if(s.recipientFingerprint.equals(fingerprint))result.add(s);return Collections.unmodifiableList(result);}
    public synchronized List<DiscoverySignal> signals(long now){expire(now);return Collections.unmodifiableList(new ArrayList<>(signals.values()));}
    public synchronized void clear(){presences.clear();signals.clear();}
    public synchronized byte[] exportFrame(long now)throws IOException {
        expire(now);List<byte[]> records=new ArrayList<>();
        // Random samples prevent permanent starvation of a tail larger than one frame.
        List<DiscoveryPresence> ps=new ArrayList<>(presences.values());Collections.shuffle(ps);
        List<DiscoverySignal> ss=new ArrayList<>(signals.values());Collections.shuffle(ss);
        int size=8;
        for(DiscoverySignal signal:ss){byte[] encoded=signal.encode();if(size+5+encoded.length<=MAX_FRAME){records.add(tag(2,encoded));size+=5+encoded.length;}}
        for(DiscoveryPresence presence:ps){byte[] encoded=presence.encode();if(size+5+encoded.length<=MAX_FRAME){records.add(tag(1,encoded));size+=5+encoded.length;}}
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);out.writeInt(0x555a4431);out.writeInt(records.size());for(byte[] record:records)out.write(record);return bytes.toByteArray();
    }
    private static byte[] tag(int kind,byte[] value)throws IOException {ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);out.writeByte(kind);DiscoveryWire.field(out,value);return bytes.toByteArray();}
    public synchronized void ingestFrame(byte[] frame,long now)throws IOException {
        if(frame==null || frame.length>MAX_FRAME)throw new IOException("Discovery frame size");
        DataInputStream in=new DataInputStream(new ByteArrayInputStream(frame));if(in.readInt()!=0x555a4431)throw new IOException("Discovery frame version");
        int count=in.readInt();if(count<0 || count>384)throw new IOException("Discovery record count");
        List<Object> verified=new ArrayList<>();
        for(int i=0;i<count;i++){int kind=in.readUnsignedByte();byte[] value=DiscoveryWire.field(in,kind==1?8192:100000);
            if(kind==1)verified.add(DiscoveryPresence.parse(value,now));else if(kind==2)verified.add(DiscoverySignal.parse(value,now));else throw new IOException("Discovery record kind");}
        DiscoveryWire.end(in);
        for(Object record:verified)if(record instanceof DiscoveryPresence)accept((DiscoveryPresence)record,now);else accept((DiscoverySignal)record,now);
    }
    public synchronized byte[] exchange(byte[] frame,long now)throws IOException {ingestFrame(frame,now);return exportFrame(now);}
}
