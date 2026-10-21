package org.uzel.app;

import android.content.Context;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.uzel.core.Invitation;
import org.uzel.core.Node;
import org.uzel.core.PairingCode;
import org.uzel.core.TlsIdentity;
import org.uzel.app.traversal.TraversalTransport;

/** Ephemeral pairing owner. Codes/credentials are never logged or persisted. */
final class PairingManager implements AutoCloseable {
    interface Listener { void available(Invitation peer); void unavailable(String fingerprint); }
    interface DescriptionSink { void description(String encoded); void failed(); void connected(); }
    private final Context context;
    private final PhysicalNetwork physical;
    private final Node node;
    private final Listener listener;
    private final ExecutorService serial=Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor();
    private final Map<String,Pair> active=new HashMap<>();
    private final PairingCode.ReplayWindow replay=new PairingCode.ReplayWindow();
    private volatile boolean closed;
    private volatile String state="IDLE",code="",diagnostic="";
    private Pair pending;
    private TlsIdentity identity;
    private static final class Pair {
        TraversalTransport transport;
        PairingCode offer,remote;
        Node.TransportRegistration registration;
        final String localToken=TlsIdentity.randomSecret();
        boolean consumed,disposed,activated;
        String targetFingerprint;
        DescriptionSink sink;
        long deadline=System.currentTimeMillis()+300_000;
    }
    PairingManager(Context context,PhysicalNetwork physical,Node node,Listener listener){
        this.context=context;this.physical=physical;this.node=node;this.listener=listener;
        timer.scheduleWithFixedDelay(()->submit(()->{
            if(pending!=null&&System.currentTimeMillis()>pending.deadline){dispose(pending);pending=null;code="";state="EXPIRED";}
        }),1,1,TimeUnit.SECONDS);
    }
    String state(){return state;}
    String code(){return code;}
    String diagnostic(){return diagnostic;}
    void offer(){submit(()->{
        if(active.size()>=3){state="CAPACITY";return;}
        cancelPending();diagnostic="";state="GATHERING";
        try {Pair pair=create();pending=pair;pair.transport.createOffer();}
        catch(Exception|LinkageError failure){diagnostic=failure.getClass().getSimpleName()+": "+failure.getMessage();failPending();}
    });}
    void accept(String value){acceptVerified(value,null,null);}
    void offerTo(String fingerprint,DescriptionSink sink){submit(()->{
        if(fingerprint==null||!fingerprint.matches("[0-9a-f]{64}")||sink==null||active.size()>=3||pending!=null||active.containsKey(fingerprint)){if(sink!=null)sink.failed();return;}
        diagnostic="";state="GATHERING";
        try{Pair pair=create();pair.targetFingerprint=fingerprint;pair.sink=sink;pending=pair;pair.transport.createOffer();}
        catch(Exception|LinkageError failure){failPending();sink.failed();}
    });}
    void acceptFrom(String value,String fingerprint,DescriptionSink sink){
        if(fingerprint==null||!fingerprint.matches("[0-9a-f]{64}")||sink==null)return;
        acceptVerified(value,fingerprint,sink);
    }
    private void acceptVerified(String value,String expected,DescriptionSink sink){
        if(value==null||value.length()>100_000){state="INVALID";return;}
        submit(()->{
            try {
                PairingCode verified=PairingCode.parseAndVerify(value,System.currentTimeMillis());
                if(expected!=null&&!expected.equals(verified.fingerprint))throw new IOException("Target identity mismatch");
                if(verified.fingerprint.equals(node.invitation("127.0.0.1").fingerprint))throw new IOException("Self pairing");
                if("offer".equals(verified.type)){
                    if(active.size()>=3||active.containsKey(verified.fingerprint))throw new IOException("Already paired or full");
                    if(!replay.claim(verified,System.currentTimeMillis()))throw new IOException("Replayed offer");
                    if(expected!=null&&pending!=null){
                        String own=node.invitation("127.0.0.1").fingerprint;
                        if(!expected.equals(pending.targetFingerprint))throw new IOException("Pairing busy");
                        if(verified.fingerprint.compareTo(own)>=0)return;
                    }
                    if(expected!=null&&pending!=null)pending.sink=null;
                    cancelPending();state="GATHERING";
                    Pair pair=create();pair.targetFingerprint=expected;pair.sink=sink;pending=pair;pair.remote=verified;pair.offer=verified;
                    pair.deadline=Math.min(pair.deadline,verified.expiresAt);
                    register(pair);pair.transport.acceptOffer(verified.sdp);
                }else{
                    Pair pair=pending;
                    if(pair==null||pair.offer==null||pair.remote!=null||pair.consumed)throw new IOException("No matching offer");
                    if(pair.targetFingerprint!=null&&!pair.targetFingerprint.equals(verified.fingerprint))throw new IOException("Target identity mismatch");
                    if(expected!=null&&!expected.equals(pair.targetFingerprint))throw new IOException("No automatic offer");
                    PairingCode.verifyAnswer(verified,pair.offer,System.currentTimeMillis());
                    if(active.containsKey(verified.fingerprint)||!replay.claim(verified,System.currentTimeMillis()))throw new IOException("Duplicate answer");
                    pair.consumed=true;pair.remote=verified;register(pair);
                    code="";state="CONNECTING";pair.transport.acceptAnswer(verified.sdp);
                }
            }catch(Exception|LinkageError failure){if(expected!=null){sink.failed();return;}diagnostic=failure.getClass().getSimpleName()+": "+failure.getMessage();state="INVALID";}
        });
    }
    void cancel(){submit(()->{cancelPending();state="IDLE";});}
    void cancelTargeted(String fingerprint){submit(()->{if(pending!=null&&fingerprint.equals(pending.targetFingerprint)){cancelPending();state="IDLE";}});}
    private Pair create()throws Exception {
        if(identity==null)identity=DeviceIdentity.load();
        Pair pair=new Pair();
        pair.transport=new TraversalTransport(context,physical.network,node.port(),new TraversalTransport.Listener(){
            public void description(String type,String sdp){submit(()->{
                if(closed||pair.disposed||pending!=pair)return;
                try {
                    Invitation own=new Invitation("127.0.0.1",node.port(),identity.fingerprint,pair.localToken);
                    PairingCode result;
                    if("offer".equals(type)){result=PairingCode.offer(identity,sdp,own,System.currentTimeMillis());pair.offer=result;}
                    else result=PairingCode.answer(identity,sdp,own,pair.offer,System.currentTimeMillis());
                    pair.deadline=Math.min(pair.deadline,result.expiresAt);
                    if(pair.sink!=null){code="";pair.sink.description(result.encode());state="CONNECTING";}
                    else{code=result.encode();state="offer".equals(type)?"OFFER_READY":"ANSWER_READY";}
                }catch(Exception failure){diagnostic=failure.getClass().getSimpleName()+": "+failure.getMessage();failPending();}
            });}
            public void state(String next){submit(()->{
                if(pair.disposed||closed)return;
                if("CONNECTED".equals(next)){
                    if(pair.activated)return;
                    if(pair.remote==null||System.currentTimeMillis()>pair.deadline){dispose(pair);state="EXPIRED";return;}
                    pair.activated=true;
                    active.put(pair.remote.fingerprint,pair);
                    if(pending==pair){pending=null;code="";state="CONNECTED";}
                    if(pair.sink!=null)pair.sink.connected();
                    listener.available(pair.remote.invitation);
                }else if("FAILED".equals(next)||"CLOSED".equals(next)||"DISCONNECTED".equals(next)){
                    dispose(pair);if(pending==pair){pending=null;code="";state="UNAVAILABLE";}else if(pending==null)state="UNAVAILABLE";
                }
            });}
            public void failed(String reason){submit(()->{
                if(pair.disposed||closed)return;diagnostic=reason;dispose(pair);
                if(pending==pair){pending=null;code="";state="UNAVAILABLE";}else if(pending==null)state="UNAVAILABLE";
            });}
        });
        return pair;
    }
    private void register(Pair pair)throws IOException {
        pair.registration=node.registerPeerTransport(pair.remote.fingerprint,pair.transport::openStream,pair.localToken);
    }
    private void cancelPending(){if(pending!=null){dispose(pending);pending=null;}code="";}
    private void failPending(){cancelPending();state="UNAVAILABLE";}
    private void dispose(Pair pair){
        if(pair.disposed)return;pair.disposed=true;
        if(pair.sink!=null&&!pair.activated)pair.sink.failed();
        if(pair.remote!=null){active.remove(pair.remote.fingerprint,pair);listener.unavailable(pair.remote.fingerprint);}
        if(pair.registration!=null)pair.registration.close();
        if(pair.transport!=null)pair.transport.close();
    }
    private void submit(Runnable task){if(!closed)try{serial.execute(()->{if(!closed)task.run();});}catch(java.util.concurrent.RejectedExecutionException ignored){}}
    @Override public void close(){
        if(closed)return;closed=true;timer.shutdownNow();
        serial.execute(()->{cancelPending();for(Pair pair:active.values().toArray(new Pair[0]))dispose(pair);active.clear();code="";state="IDLE";});
        serial.shutdown();
    }
}
