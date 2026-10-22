package org.uzel.desktop;

import org.uzel.core.*;
import java.io.*;
import java.net.*;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.*;

/** Physical desktop exit and authenticated per-application SOCKS bridge. Network calls run on the owner's worker. */
public final class DesktopTrafficService implements AutoCloseable {
    @FunctionalInterface public interface UploadProbe {long measure(BooleanSupplier cancelled)throws IOException;}
    public record Snapshot(boolean sharing,boolean connected,boolean measuring,long sharedBytes,long receivedBytes,
                           long measuredUpload,long shareRateLimit,long requestedShareRateLimit,int clients,String country,String status){}
    /** Credentials are delivered only to the local application configuration, never status strings or logs. */
    public record ProxyEndpoint(String host,int port,String username,String password){
        @Override public String toString(){return "SOCKS5 "+host+":"+port+" (authenticated)";}
    }
    private final TlsIdentity identity;
    private final NetworkAccess network;
    private final Predicate<InetAddress> destinationPolicy;
    private final UploadProbe probe;
    private Node node;
    private Node.Session session;
    private LocalSocks socks;
    private long lifecycle,connectionGeneration;
    private final AtomicLong sharedTotal=new AtomicLong(),receivedTotal=new AtomicLong();
    private boolean measuring,closed;
    private String status="OFF";
    private SharingRatePreference preference=SharingRatePreference.unlimited();

    public DesktopTrafficService(TlsIdentity identity){
        this(identity,new NetworkAccess(),PublicAddress::allowed,
            cancelled->UploadMeasurement.upload(url->(HttpURLConnection)new URL(url).openConnection(Proxy.NO_PROXY),cancelled));
    }
    /** Package-private dependency injection keeps permissive loopback destination policies out of the desktop UI. */
    DesktopTrafficService(TlsIdentity identity,NetworkAccess network,Predicate<InetAddress> destinationPolicy,UploadProbe probe){
        this.identity=Objects.requireNonNull(identity);this.network=Objects.requireNonNull(network);
        this.destinationPolicy=Objects.requireNonNull(destinationPolicy);this.probe=Objects.requireNonNull(probe);
    }
    public void startSharing(DesktopConfig config)throws IOException {startSharing(config.port(),config.advertisedHost());}
    public void startSharing(int port)throws IOException {startSharing(port,null);}
    private void startSharing(int port,String host)throws IOException {
        final Node started;final long epoch;
        synchronized(this){
            if(closed)throw new IOException("Traffic service closed");
            if(node!=null)throw new IOException("Sharing already started");
            started=new Node(identity,network,destinationPolicy,sharedTotal,receivedTotal);
            try{started.start(port);started.setPublicMode(true);started.setShareRateLimit(preference.bytesPerSecond());
                if(host!=null)started.setAdvertisedRoute(started.publicRoute(host));}
            catch(Exception e){started.close();throw e instanceof IOException?(IOException)e:new IOException(e);}
            node=started;epoch=++lifecycle;measuring=true;status="MEASURING";
        }
        measure(started,epoch);
    }
    public void remeasure()throws IOException {
        final Node current;final long epoch;final ConnectionResources previous;
        synchronized(this){
            current=requireNode();if(measuring)throw new IOException("Measurement already running");
            previous=detachConnection();
            current.setMeasuredUpload(0);epoch=++lifecycle;measuring=true;status="MEASURING";
        }
        previous.close();
        measure(current,epoch);
    }
    private void measure(Node current,long epoch)throws IOException {
        try{
            long measured=probe.measure(()->cancelled(current,epoch));
            if(measured<=0 || measured>SharingRatePreference.MAX_BYTES_PER_SECOND)throw new IOException("Upload measurement unavailable");
            synchronized(this){
                if(cancelled(current,epoch))throw new InterruptedIOException("Measurement cancelled");
                current.setMeasuredUpload(measured);measuring=false;status="SHARING";
            }
        }catch(IOException|RuntimeException e){
            stopSharing(current,epoch,"MEASUREMENT_FAILED");
            if(e instanceof IOException io)throw io;throw new IOException("Upload measurement failed",e);
        }
    }
    private synchronized boolean cancelled(Node current,long epoch){return closed || node!=current || lifecycle!=epoch || Thread.currentThread().isInterrupted();}
    public void stopSharing(){stopSharing(null,0,"OFF");}
    private void stopSharing(Node expected,long epoch,String stoppedStatus){
        final Node old;final ConnectionResources previous;
        synchronized(this){
            if(expected!=null && (node!=expected || lifecycle!=epoch))return;
            ++lifecycle;old=node;previous=detachConnection();node=null;
            measuring=false;status=stoppedStatus;
            if(old!=null)old.stopAdmission();
        }
        previous.close();if(old!=null)old.close();
    }
    private record ConnectionResources(LocalSocks socks,Node.Session session){
        void close(){if(socks!=null)socks.close();if(session!=null)session.close();}
    }
    /** Called only while holding this service's monitor, so revocation and generation changes are indivisible. */
    private ConnectionResources detachConnection(){
        ++connectionGeneration;ConnectionResources previous=new ConnectionResources(socks,session);socks=null;session=null;
        if(previous.session()!=null)previous.session().invalidate();return previous;
    }
    public void disconnect(){disconnect(null,0);}
    private boolean disconnect(Node expected,long epoch){
        final ConnectionResources previous;
        synchronized(this){
            if(expected!=null && (node!=expected || connectionGeneration!=epoch))return false;
            previous=detachConnection();if(node!=null && !measuring)status="SHARING";
        }
        previous.close();return true;
    }
    @FunctionalInterface private interface Connector {Node.Session connect(Node node,Runnable lost)throws IOException;}
    public void connect(Invitation peer,Runnable lost)throws IOException {connect((current,callback)->current.connect(peer,callback),lost);}
    public void connect(PeerRoute peer,Runnable lost)throws IOException {connect((current,callback)->current.connect(peer,callback),lost);}
    private void connect(Connector connector,Runnable lost)throws IOException {
        Objects.requireNonNull(lost);disconnect();final Node current;final long epoch;
        synchronized(this){current=requireNode();if(measuring || current.measuredUpload()<=0)throw new IOException("NEED_MEASUREMENT");
            epoch=++connectionGeneration;status="CONNECTING";}
        Node.Session connected=null;LocalSocks bridge=null;
        try{
            connected=connector.connect(current,()->connectionLost(current,epoch,lost));
            bridge=new LocalSocks(current,connected);bridge.start();
            synchronized(this){
                if(node!=current || epoch!=connectionGeneration || !connected.isLive())throw new IOException("Connection cancelled");
                session=connected;socks=bridge;status="CONNECTED";connected=null;bridge=null;
            }
        }catch(IOException|RuntimeException e){
            synchronized(this){if(node==current && epoch==connectionGeneration)status="CONNECTION_FAILED";}
            if(e instanceof IOException io)throw io;throw new IOException("Peer connection failed",e);
        }finally{if(bridge!=null)bridge.close();if(connected!=null)connected.close();}
    }
    private void connectionLost(Node expected,long epoch,Runnable callback){
        if(disconnect(expected,epoch))callback.run();
    }
    public synchronized ProxyEndpoint localProxy(){
        if(socks==null || session==null || !session.isLive())return null;
        return new ProxyEndpoint("127.0.0.1",socks.port(),socks.username(),socks.password());
    }
    public synchronized void setShareRatePreference(SharingRatePreference choice){
        Objects.requireNonNull(choice);
        if(node!=null)node.setShareRateLimit(choice.bytesPerSecond());preference=choice;
    }
    public synchronized Snapshot snapshot(){
        Node current=node;return new Snapshot(current!=null && current.isSharing() && current.measuredUpload()>0,
            session!=null && session.isLive() && socks!=null,measuring,
            sharedTotal.get(),receivedTotal.get(),
            current==null?0:current.measuredUpload(),current==null?0:current.limit(),preference.bytesPerSecond(),
            current==null?0:current.clientCount(),current==null?"":current.country,status);
    }
    private Node requireNode()throws IOException {if(node==null || !node.isSharing())throw new IOException("NEED_SHARING");return node;}
    public synchronized Invitation invitation(String host)throws IOException {return requireNode().invitation(host);}
    public synchronized PeerRoute publicRoute(String host)throws IOException {return requireNode().publicRoute(host);}
    public synchronized void publishRoute(PeerRoute route)throws IOException {requireNode().publish(route);}
    public synchronized void setDiscoveryExchange(Node.DiscoveryExchange exchange)throws IOException {requireNode().setDiscoveryExchange(exchange);}
    public synchronized PeerDirectory directory()throws IOException {return requireNode().directory();}
    /** Package-only adapter for discovery; callers must tolerate stop/restart invalidating the returned node. */
    synchronized Node node()throws IOException {return requireNode();}
    @Override public void close(){synchronized(this){closed=true;}stopSharing();}
}
