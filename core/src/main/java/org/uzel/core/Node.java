package org.uzel.core;

import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Predicate;
import java.util.function.Consumer;

/** Direct, encrypted peer exit. A live reciprocal-sharing lease gates every flow. */
public final class Node implements AutoCloseable {
    static final int MAGIC=0x555a4c31,INFO=1,PROBE=2,MEASURE=3,JOIN=4,TCP=5,UDP=6,JOIN_PUBLIC=7,DIRECTORY=8,DISCOVERY=9;
    final TlsIdentity identity;
    final NetworkAccess network;
    private final Predicate<InetAddress> publicAddress;
    private final String secret;
    private final Admission admission=new Admission();
    private final Pacer pacer=new Pacer();
    private final Map<String,Integer> relayAttachments=new HashMap<>();
    private final Map<String,TransportRegistration> peerTransports=new HashMap<>();
    private final PeerDirectory directory=new PeerDirectory();
    private final AtomicLong recordSequence=new AtomicLong(System.currentTimeMillis());
    final ParticipantRelay relaySupport;
    private volatile boolean publicMode,forwardingPaused;
    private final AtomicLong publicGeneration=new AtomicLong();
    private volatile PeerRoute advertisedRoute;
    private final ConcurrentMap<String,Socket> controls=new ConcurrentHashMap<>();
    private final ConcurrentMap<Socket,String> flows=new ConcurrentHashMap<>();
    private final Set<Closeable> sockets=ConcurrentHashMap.newKeySet();
    private final Set<Socket> publicSockets=ConcurrentHashMap.newKeySet();
    private final Set<Session> sessions=ConcurrentHashMap.newKeySet();
    private final Semaphore accepted=new Semaphore(80);
    private final AtomicBoolean measuring=new AtomicBoolean();
    private volatile Consumer<Throwable> failureObserver;
    @FunctionalInterface public interface DiscoveryExchange {byte[] exchange(byte[] frame)throws IOException;}
    private volatile DiscoveryExchange discoveryExchange;
    public void setDiscoveryExchange(DiscoveryExchange handler){discoveryExchange=handler;}
    private final AtomicInteger observedFailures=new AtomicInteger();
    final ThreadPoolExecutor workers=new ThreadPoolExecutor(0,160,30,TimeUnit.SECONDS,
            new SynchronousQueue<>(),r->{Thread t=new Thread(r,"uzel-net");t.setDaemon(true);return t;});
    private volatile SSLServerSocket listener;
    private volatile boolean sharing;
    private volatile long measured;
    private volatile long requestedShareRate;
    public volatile String country="";
    public final AtomicLong sharedBytes;
    public final AtomicLong receivedBytes;

    public Node(TlsIdentity identity,NetworkAccess network,Predicate<InetAddress> publicAddress) {
        this(identity,network,publicAddress,TlsIdentity.randomSecret());
    }
    public Node(TlsIdentity identity,NetworkAccess network,Predicate<InetAddress> publicAddress,String secret) {
        this(identity,network,publicAddress,secret,new AtomicLong(),new AtomicLong());
    }
    /** Optional owner-lifetime counters keep late I/O completions visible across node replacement. */
    public Node(TlsIdentity identity,NetworkAccess network,Predicate<InetAddress> publicAddress,AtomicLong sharedBytes,AtomicLong receivedBytes) {
        this(identity,network,publicAddress,TlsIdentity.randomSecret(),sharedBytes,receivedBytes);
    }
    private Node(TlsIdentity identity,NetworkAccess network,Predicate<InetAddress> publicAddress,String secret,AtomicLong sharedBytes,AtomicLong receivedBytes) {
        this.identity=identity;this.network=network;this.publicAddress=publicAddress;
        this.sharedBytes=Objects.requireNonNull(sharedBytes);this.receivedBytes=Objects.requireNonNull(receivedBytes);
        if(secret==null || !secret.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("secret");
        this.secret=secret;
        relaySupport=new ParticipantRelay(this);
    }
    public synchronized void start(int port) throws IOException {
        if(listener!=null) throw new IOException("Already started");
        try {
            listener=(SSLServerSocket)identity.context(null).getServerSocketFactory().createServerSocket(port);
            listener.setNeedClientAuth(true);
            listener.setEnabledProtocols(new String[]{"TLSv1.3","TLSv1.2"});
            sharing=true;admission.setSharing(true);
            workers.execute(this::acceptLoop);
        } catch(java.security.GeneralSecurityException e){throw new IOException(e);}
    }
    public boolean isSharing(){return sharing;}
    /** Nonblocking logical revocation. The owner must subsequently close on its network worker. */
    public void stopAdmission(){sharing=false;measured=0;pacer.stop();}
    /** Revoke public requests before asynchronous TLS cleanup without taking network locks. */
    public void stopPublicAdmission(){forwardingPaused=true;publicMode=false;publicGeneration.incrementAndGet();pacer.cancelPending();advertisedRoute=null;}
    /** Optional test diagnostics. Disabled by default and bounded to eight failures per observer. */
    public void setFailureObserver(Consumer<Throwable> observer){failureObserver=observer;observedFailures.set(0);}
    private void reportFailure(Throwable error){
        Consumer<Throwable> observer=failureObserver;
        if(observer!=null && observedFailures.getAndIncrement()<8)try{observer.accept(error);}catch(RuntimeException ignored){}
    }
    public long measuredUpload(){return measured;}
    public long limit(){return pacer.limit();}
    /** User choice in bytes/s; zero selects unlimited sharing. */
    public long requestedShareRateLimit(){return requestedShareRate;}
    public long minimumShareRateLimit(){long speed=measured;return speed/3+(speed%3==0?0:1);}
    public synchronized void setShareRateLimit(long bytesPerSecond){
        if(bytesPerSecond<0 || (bytesPerSecond>0 && bytesPerSecond<minimumShareRateLimit()))
            throw new IllegalArgumentException("Sharing speed below minimum");
        requestedShareRate=bytesPerSecond;
        updateShareRate();
    }
    private void updateShareRate(){pacer.setShareRateLimit(requestedShareRate==0?0:Math.max(requestedShareRate,minimumShareRateLimit()));}
    public int port(){SSLServerSocket s=listener;return s==null?0:s.getLocalPort();}
    public int clientCount(){synchronized(admission){return attachedIdentities().size();}}
    private Set<String> attachedIdentities(){
        Set<String> ids=new HashSet<>(relayAttachments.keySet());ids.addAll(controls.keySet());
        peerTransports.forEach((id,r)->{if(r.live)ids.add(id);});return ids;
    }
    @FunctionalInterface public interface PeerTransport { Socket openStream() throws IOException; }
    /** Identity-bound private transport; a closed registration never falls back to a physical endpoint. */
    public TransportRegistration registerPeerTransport(String fingerprint,PeerTransport provider)throws IOException {
        return registerPeerTransport(fingerprint,provider,TlsIdentity.randomSecret());
    }
    public TransportRegistration registerPeerTransport(String fingerprint,PeerTransport provider,String inboundToken)throws IOException {
        if(fingerprint==null || !fingerprint.matches("[0-9a-f]{64}") || provider==null)throw new IOException("Transport identity");
        if(inboundToken==null || !inboundToken.matches("[0-9a-f]{64}") || inboundToken.equals(secret))throw new IOException("Transport token");
        synchronized(admission){
            if(!sharing || fingerprint.equals(identity.fingerprint))throw new IOException("Transport unavailable");
            Set<String> ids=attachedIdentities();
            if(!ids.contains(fingerprint) && ids.size()>=3)throw new IOException("CAPACITY");
            if(!peerTransports.containsKey(fingerprint) && peerTransports.size()>=64)throw new IOException("Transport history full");
            TransportRegistration old=peerTransports.get(fingerprint);if(old!=null)old.close();
            TransportRegistration registration=new TransportRegistration(fingerprint,provider,inboundToken);
            peerTransports.put(fingerprint,registration);return registration;
        }
    }
    public final class TransportRegistration implements AutoCloseable {
        private final String fingerprint,inboundToken;private final PeerTransport provider;
        private final Set<Socket> owned=new HashSet<>();private boolean live=true;
        private TransportRegistration(String fingerprint,PeerTransport provider,String inboundToken){this.fingerprint=fingerprint;this.provider=provider;this.inboundToken=inboundToken;}
        public boolean isLive(){synchronized(admission){return live && sharing && peerTransports.get(fingerprint)==this;}}
        private void track(Socket socket)throws IOException {synchronized(admission){
            if(!isLive()){closeQuietly(socket);throw new IOException("Transport closed");}
            owned.removeIf(Socket::isClosed);
            if(owned.size()>=128){closeQuietly(socket);throw new IOException("Transport streams full");}owned.add(socket);
        }}
        private SSLSocket open(String host,String token,int op)throws IOException {
            if(!isLive())throw new IOException("Transport closed");
            Socket plain=provider.openStream();if(plain==null)throw new IOException("No transport socket");track(plain);
            SSLSocket tls=wrap(plain,host,fingerprint,token,op);track(tls);return tls;
        }
        public void close(){synchronized(admission){
            if(!live)return;live=false;owned.forEach(Node::closeQuietly);owned.clear();
            if(peerTransports.get(fingerprint)==this){
                closeQuietly(controls.get(fingerprint));admission.leave(fingerprint);
                flows.forEach((socket,id)->{if(id.equals(fingerprint))closeQuietly(socket);});
                sessions.forEach(s->{if(s.fingerprint().equals(fingerprint))s.close();});
            }
        }}
    }
    private TransportRegistration transport(String id){synchronized(admission){return peerTransports.get(id);}}
    boolean retainRelay(String id){synchronized(admission){
        Set<String> ids=attachedIdentities();
        if(!sharing || measured<=0 || !publicMode || (!ids.contains(id) && ids.size()>=3))return false;
        relayAttachments.put(id,relayAttachments.getOrDefault(id,0)+1);return true;
    }}
    void releaseRelay(String id){synchronized(admission){
        Integer count=relayAttachments.get(id);if(count!=null){if(count<=1)relayAttachments.remove(id);else relayAttachments.put(id,count-1);}
    }}
    public boolean isPublicMode(){return publicMode;}
    public void setPublicMode(boolean enabled){
        synchronized(admission){
            publicMode=enabled;
            if(!enabled){
                publicGeneration.incrementAndGet();advertisedRoute=null;
                pacer.cancelPending();
                publicSockets.forEach(Node::closeQuietly);publicSockets.clear();
                controls.values().forEach(Node::closeQuietly);flows.keySet().forEach(Node::closeQuietly);
                sessions.forEach(Session::close);
            }
        }
        // Relay locks may acquire admission: never take them while holding admission.
        if(!enabled){relaySupport.stop();directory.clear();forwardingPaused=false;}
    }
    public PeerRoute publicRoute(String host){return PeerRoute.direct(host,port(),identity.fingerprint);}
    public void setAdvertisedRoute(PeerRoute route){
        if(route==null || !identity.fingerprint.equals(route.fingerprint))throw new IllegalArgumentException("Own route");
        advertisedRoute=route;
    }
    public PeerDirectory directory(){return directory;}
    public PeerDirectory.Record publish(PeerRoute route)throws IOException{
        if(!publicMode || !sharing)throw new IOException("Public mode disabled");
        setAdvertisedRoute(route);
        PeerDirectory.Record record=PeerDirectory.Record.create(identity,route,country,recordSequence.incrementAndGet());
        directory.accept(record,System.currentTimeMillis());return record;
    }
    public RelayLease registerRelay(PeerRoute route)throws IOException{return new RelayLease(relaySupport.register(route));}
    public final class RelayLease implements AutoCloseable {
        private final ParticipantRelay.Registration registration;
        RelayLease(ParticipantRelay.Registration registration){this.registration=registration;}
        public boolean isLive(){return registration.isLive();}
        public PeerRoute route(){return registration.route;}
        public void close(){registration.close();}
    }
    public synchronized void setMeasuredUpload(long bytesPerSecond){
        if(bytesPerSecond<0 || bytesPerSecond>125_000_000_000L)throw new IllegalArgumentException("speed");
        measured=bytesPerSecond;updateShareRate();
        if(bytesPerSecond<=0)pacer.cancelPending();
        if(bytesPerSecond<=0)synchronized(admission) {
            controls.values().forEach(Node::closeQuietly);
            flows.keySet().forEach(Node::closeQuietly);
        }
        if(bytesPerSecond<=0)relaySupport.stop();
    }
    public Invitation invitation(String host){return new Invitation(host,port(),identity.fingerprint,secret);}

    private void acceptLoop() {
        while(sharing) {
            try {
                SSLSocket socket=(SSLSocket)listener.accept();
                if(!accepted.tryAcquire()){closeQuietly(socket);continue;}
                sockets.add(socket);
                try { workers.execute(()-> {
                    try {handle(socket);} catch(Exception error) {reportFailure(error);}
                    finally {publicSockets.remove(socket);sockets.remove(socket);closeQuietly(socket);accepted.release();}
                });} catch(RejectedExecutionException e){sockets.remove(socket);closeQuietly(socket);accepted.release();}
            }catch(IOException e){break;}
        }
    }
    private SSLSocket open(Invitation peer,int op) throws IOException {
        if(!sharing) throw new IOException("NEED_SHARING");
        TransportRegistration registered=transport(peer.fingerprint);
        if(registered!=null)return registered.open(peer.host,peer.secret,op);
        Socket plain=network.open(peer.host,peer.port);
        return wrap(plain,peer.host,peer.fingerprint,peer.secret,op);
    }
    SSLSocket open(PeerRoute route,int op)throws IOException {
        long epoch;
        synchronized(admission){
            if(!sharing || !publicMode)throw new IOException("Public mode disabled");
            epoch=publicGeneration.get();publicSockets.removeIf(Socket::isClosed);
        }
        TransportRegistration registered=transport(route.fingerprint);
        Socket plain=registered!=null?null:route.isRelay()?relaySupport.openTunnel(route)
                :route.discovered?network.open(destination(route.host,route.port),route.port):network.open(route.host,route.port);
        SSLSocket socket=registered!=null?registered.open(route.host,"",op):wrap(plain,route.host,route.fingerprint,"",op);
        synchronized(admission){
            if(!sharing || !publicMode || epoch!=publicGeneration.get()){closeQuietly(socket);throw new IOException("Public mode changed");}
            publicSockets.add(socket);
        }
        return socket;
    }
    private SSLSocket wrap(Socket plain,String host,String pin,String token,int op)throws IOException {
        try {
            SSLSocket socket=(SSLSocket)identity.context(pin).getSocketFactory()
                    .createSocket(plain,host,plain.getPort(),true);
            socket.setEnabledProtocols(new String[]{"TLSv1.3","TLSv1.2"});
            socket.setSoTimeout(15000);socket.startHandshake();
            DataOutputStream out=new DataOutputStream(socket.getOutputStream());
            out.writeInt(MAGIC);out.writeUTF(token);out.writeByte(op);out.flush();
            return socket;
        } catch(Exception e){closeQuietly(plain);throw e instanceof IOException?(IOException)e:new IOException(e);}
    }
    private void handle(SSLSocket socket) throws Exception {
        socket.setSoTimeout(15000);socket.startHandshake();
        String id=TlsIdentity.fingerprint(socket.getSession().getPeerCertificates()[0]);
        DataInputStream in=new DataInputStream(socket.getInputStream());
        DataOutputStream out=new DataOutputStream(socket.getOutputStream());
        if(in.readInt()!=MAGIC)throw new IOException("Authentication");
        String token=in.readUTF();
        boolean publicRequest=token.isEmpty();
        long publicEpoch=0;
        if(publicRequest){
            synchronized(admission){
                if(!publicMode)throw new IOException("Public mode disabled");
                publicEpoch=publicGeneration.get();publicSockets.add(socket);
            }
        }else if(!TlsIdentity.constantEquals(secret,token)){
            synchronized(admission){
                TransportRegistration registered=peerTransports.get(id);
                if(registered==null || !registered.isLive() || !TlsIdentity.constantEquals(registered.inboundToken,token))throw new IOException("Authentication");
                registered.track(socket);
            }
        }
        int op=in.readUnsignedByte();
        if(!sharing) throw new IOException("Sharing disabled");
        if(op==INFO || op==PROBE) {
            out.writeInt(0);out.writeBoolean(sharing);out.writeLong(measured);
            out.writeInt(clientCount());out.writeUTF(country);out.flush();return;
        }
        if(op==DISCOVERY){
            synchronized(admission){
                TransportRegistration registered=peerTransports.get(id);
                if(socket.isClosed() || !sharing || measured<=0 || (publicRequest && (!publicMode || publicEpoch!=publicGeneration.get()))
                        || !(admission.contains(id) || registered!=null && registered.isLive()))throw new IOException("Discovery requires live peer");
                if(flows.values().stream().filter(id::equals).count()>=24)throw new IOException("Discovery capacity");
                flows.put(socket,id);
            }
            try{
                byte[] request=readDiscovery(in);DiscoveryExchange handler=discoveryExchange;
                if(handler==null)throw new IOException("Discovery disabled");
                byte[] response=handler.exchange(request);
                if(response==null || response.length>DiscoveryStore.MAX_FRAME)throw new IOException("Discovery response size");
                synchronized(admission){if(socket.isClosed() || !sharing || measured<=0
                        || (publicRequest && (!publicMode || publicEpoch!=publicGeneration.get())))throw new IOException("Discovery revoked");}
                out.writeInt(0);out.writeInt(response.length);out.write(response);out.flush();
            }finally{flows.remove(socket);}return;
        }
        if(op==DIRECTORY){if(!publicRequest)throw new IOException("Public mode required");exchangeDirectory(in,out);return;}
        if(op>=ParticipantRelay.REGISTER && op<=ParticipantRelay.ATTACH){
            if(!publicRequest)throw new IOException("Public relay required");relaySupport.handle(op,socket,id,in,out);return;
        }
        if(op==MEASURE){receiveMeasurement(in,out);return;}
        if(op==JOIN && publicRequest)throw new IOException("Private JOIN requires invitation secret");
        if(op==JOIN || op==JOIN_PUBLIC){if(op==JOIN_PUBLIC && !publicRequest)throw new IOException("Public mode required");join(socket,id,in,out,op==JOIN_PUBLIC,publicEpoch);return;}
        // Registration and lease revocation share a lock: no untracked flow can
        // slip past cleanup between checking membership and registering itself.
        synchronized(admission) {
            if(socket.isClosed())throw new IOException("Transport closed");
            if(publicRequest && (!publicMode || publicEpoch!=publicGeneration.get()))throw new IOException("Public mode changed");
            if(!admission.contains(id) || measured<=0){out.writeInt(1);out.flush();return;}
            long existing=flows.values().stream().filter(id::equals).count();
            if(existing>=24){out.writeInt(2);out.flush();return;}
            flows.put(socket,id);
        }
        try {
            if(op==TCP) tcp(in,out);
            else if(op==UDP) udp(socket,in,out);
        } finally {flows.remove(socket);}
    }
    private void join(SSLSocket socket,String id,DataInputStream in,DataOutputStream out,boolean publicJoin,long publicEpoch) throws IOException {
        TransportRegistration reverseTransport=transport(id);
        Invitation privateReverse=null;PeerRoute publicReverse=null;
        if(publicJoin){
            PeerRoute claimed=PeerRoute.parse(in.readUTF());
            if(!claimed.fingerprint.equals(id))throw new IOException("Reverse identity mismatch");
            if(claimed.isRelay()){
                publicReverse=PeerRoute.viaRelay(validateReverseEndpoint(claimed.relay),id);
            }else if(socket.getInetAddress().isLoopbackAddress() && advertisedRoute!=null && advertisedRoute.isRelay()){
                publicReverse=validateReverseEndpoint(claimed);
            }else publicReverse=PeerRoute.direct(socket.getInetAddress().getHostAddress(),claimed.port,id);
        }else{
            Invitation claimed=Invitation.parse(in.readUTF());
            if(!claimed.fingerprint.equals(id))throw new IOException("Reverse identity mismatch");
            privateReverse=new Invitation(socket.getInetAddress().getHostAddress(),claimed.port,id,claimed.secret);
        }
        synchronized(admission){
            if(socket.isClosed())throw new IOException("Transport closed");
            if(publicJoin && (!publicMode || publicEpoch!=publicGeneration.get()))throw new IOException("Public mode changed");
            Set<String> ids=attachedIdentities();
            if((!ids.contains(id) && ids.size()>=3) || controls.putIfAbsent(id,socket)!=null){out.writeInt(2);out.flush();return;}
        }
        boolean admitted=false;
        try {
            if(measured<=0){out.writeInt(4);out.flush();return;}
            Info back=publicJoin?inspect(publicReverse):inspect(privateReverse);
            if(!back.sharing || back.upload<=0){out.writeInt(1);out.flush();return;}
            synchronized(admission){
                if(socket.isClosed())throw new IOException("Transport closed");
                if(publicJoin && (!publicMode || publicEpoch!=publicGeneration.get()))throw new IOException("Public mode changed");
                if(reverseTransport!=transport(id) || (reverseTransport!=null && !reverseTransport.isLive()))throw new IOException("Reverse transport changed");
                if(!admission.join(id)){out.writeInt(2);out.flush();return;}
                admitted=true;
            }
            out.writeInt(0);out.flush();
            socket.setSoTimeout(10000);
            int tick=0;
            while(sharing && measured>0 && admission.contains(id)) {
                if(in.readInt()!=MAGIC)break;
                if(++tick%3==0){
                    Info check=publicJoin?inspect(publicReverse):inspect(privateReverse);
                    if(!check.sharing || check.upload<=0)break;
                }
                out.writeInt(MAGIC);out.flush();
            }
        } finally {
            synchronized(admission) {
                if(admitted) {
                    admission.leave(id);
                    flows.forEach((flow,owner)->{if(owner.equals(id))closeQuietly(flow);});
                }
                controls.remove(id,socket);
            }
        }
    }
    private PeerRoute validateReverseEndpoint(PeerRoute route)throws IOException {
        PeerRoute own=advertisedRoute;
        if(own!=null && own.isRelay() && own.relay.encode().equals(route.encode()))return own.relay;
        return route.asDiscovered(); // DNS is checked and pinned to its answer at each connection.
    }
    private InetAddress destination(String host,int port) throws IOException {
        if(host==null || host.length()>253 || port<1 || port>65535)throw new IOException("Destination");
        InetAddress[] addresses=network.resolve(host);
        if(addresses.length==0)throw new IOException("No address");
        for(InetAddress address:addresses) if(!publicAddress.test(address))throw new IOException("Private destination denied");
        return addresses[0];
    }
    private void tcp(DataInputStream in,DataOutputStream out) throws IOException {
        String host=in.readUTF();int port=in.readInt();
        Socket target;
        try {target=network.open(destination(host,port),port);}
        catch(IOException e){out.writeInt(5);out.flush();return;}
        sockets.add(target);
        try {
            target.setSoTimeout(60000);out.writeInt(0);out.flush();
            Future<?> up=workers.submit(()->{
                try {pump(in,target.getOutputStream(),true,null);target.shutdownOutput();}
                catch(IOException ignored){closeQuietly(target);}
            });
            try {pump(target.getInputStream(),out,true,null);}
            finally {closeQuietly(target);up.cancel(true);}
        } catch(RejectedExecutionException e){throw new IOException("Busy",e);}
        finally{sockets.remove(target);closeQuietly(target);}
    }
    private void udp(Socket owner,DataInputStream in,DataOutputStream out) throws IOException {
        long forwardingEpoch=publicGeneration.get();
        DatagramSocket udp=network.datagram();
        sockets.add(udp);
        Set<InetSocketAddress> destinations=ConcurrentHashMap.newKeySet();
        out.writeInt(0);out.flush();
        Future<?> receiver=workers.submit(()->{
            byte[] buffer=new byte[65507];
            try {
                while(sharing && !owner.isClosed()) {
                    DatagramPacket packet=new DatagramPacket(buffer,buffer.length);udp.receive(packet);
                    if(!destinations.contains(packet.getSocketAddress()))continue;
                    byte[] data=SocksPacket.encode(packet.getAddress(),packet.getPort(),Arrays.copyOf(buffer,packet.getLength()));
                    if(data.length>65507)continue;
                    requireForwarding(forwardingEpoch);
                    pacer.acquire(data.length);
                    requireForwarding(forwardingEpoch);
                    out.writeInt(data.length);out.write(data);out.flush();
                    sharedBytes.addAndGet(packet.getLength());
                }
            }catch(IOException ignored){closeQuietly(owner);}
        });
        try {
            while(sharing) {
                int length=in.readInt();
                if(length<7 || length>65507)throw new IOException("Datagram length");
                byte[] bytes=new byte[length];in.readFully(bytes);
                SocksPacket packet=SocksPacket.parse(bytes);
                InetSocketAddress address=new InetSocketAddress(destination(packet.host,packet.port),packet.port);
                if(destinations.size()>=64 && !destinations.contains(address))throw new IOException("UDP destinations");
                destinations.add(address);
                requireForwarding(forwardingEpoch);
                pacer.acquire(packet.payload.length);
                requireForwarding(forwardingEpoch);
                udp.send(new DatagramPacket(packet.payload,packet.payload.length,address));
                sharedBytes.addAndGet(packet.payload.length);
            }
        }finally{receiver.cancel(true);sockets.remove(udp);udp.close();}
    }
    private void requireForwarding(long epoch)throws IOException {
        if(!sharing || measured<=0 || forwardingPaused || epoch!=publicGeneration.get())throw new IOException("Forwarding revoked");
    }
    void pump(InputStream in,OutputStream out,boolean pace,AtomicLong count) throws IOException {
        long forwardingEpoch=publicGeneration.get();
        requireForwarding(forwardingEpoch);
        byte[] buffer=new byte[2048];
        int length;
        while(sharing && (length=in.read(buffer))!=-1) {
            requireForwarding(forwardingEpoch);
            if(pace)pacer.acquire(length);
            requireForwarding(forwardingEpoch);
            out.write(buffer,0,length);out.flush();
            if(pace)sharedBytes.addAndGet(length);
            if(count!=null)count.addAndGet(length);
        }
    }
    public Info inspect(Invitation peer) throws IOException {
        long started=System.nanoTime();
        try(Socket s=open(peer,INFO)){
            DataInputStream in=new DataInputStream(s.getInputStream());checkStatus(in);
            boolean ready=in.readBoolean();long speed=in.readLong();int clients=in.readInt();String country=in.readUTF();
            if(!country.matches("[A-Z]{2}|") || clients<0 || clients>3 || speed<0)throw new IOException("Invalid status");
            return new Info(ready,speed,clients,country,(int)((System.nanoTime()-started)/1_000_000));
        }
    }
    public byte[] exchangeDiscovery(Invitation peer,byte[] frame)throws IOException {
        if(!sharing || measured<=0)throw new IOException("Discovery requires measured sharing");
        if(frame==null || frame.length>DiscoveryStore.MAX_FRAME)throw new IOException("Discovery frame size");
        try(Socket socket=open(peer,DISCOVERY)){
            DataOutputStream out=new DataOutputStream(socket.getOutputStream());out.writeInt(frame.length);out.write(frame);out.flush();
            DataInputStream in=new DataInputStream(socket.getInputStream());checkStatus(in);return readDiscovery(in);
        }
    }
    private static byte[] readDiscovery(DataInputStream in)throws IOException {
        int size=in.readInt();if(size<0 || size>DiscoveryStore.MAX_FRAME)throw new IOException("Discovery frame size");
        byte[] frame=new byte[size];in.readFully(frame);return frame;
    }
    public Info inspect(PeerRoute peer)throws IOException{
        long started=System.nanoTime();
        try(Socket s=open(peer,INFO)){
            DataInputStream in=new DataInputStream(s.getInputStream());checkStatus(in);
            boolean ready=in.readBoolean();long speed=in.readLong();int clients=in.readInt();String country=in.readUTF();
            if(!country.matches("[A-Z]{2}|") || clients<0 || clients>3 || speed<0)throw new IOException("Invalid status");
            return new Info(ready,speed,clients,country,(int)((System.nanoTime()-started)/1_000_000));
        }
    }
    public List<PeerDirectory.Record> exchangeDirectory(PeerRoute peer)throws IOException{
        try(Socket s=open(peer,DIRECTORY)){
            DataOutputStream out=new DataOutputStream(s.getOutputStream());writeRecords(out);out.flush();
            DataInputStream in=new DataInputStream(s.getInputStream());checkStatus(in);return readRecords(in);
        }
    }
    private void exchangeDirectory(DataInputStream in,DataOutputStream out)throws IOException{
        readRecords(in);out.writeInt(0);writeRecords(out);out.flush();
    }
    private void writeRecords(DataOutputStream out)throws IOException{
        List<PeerDirectory.Record> records=directory.records();int n=Math.min(32,records.size());
        // Rotating samples allow larger bounded catalogs to converge instead of starving the tail.
        Collections.shuffle(records);out.writeInt(n);for(int i=0;i<n;i++)out.writeUTF(records.get(i).encode());
    }
    private List<PeerDirectory.Record> readRecords(DataInputStream in)throws IOException{
        int n=in.readInt();if(n<0 || n>32)throw new IOException("Directory count");
        List<PeerDirectory.Record> result=new ArrayList<>();
        for(int i=0;i<n;i++){
            PeerDirectory.Record r=PeerDirectory.Record.parse(in.readUTF());
            if(directory.accept(r,System.currentTimeMillis()))result.add(r);
        }
        return result;
    }
    public static final class Info {
        public final boolean sharing;public final long upload;public final int clients,latencyMs;public final String country;
        Info(boolean sharing,long upload,int clients,String country,int latencyMs){
            this.sharing=sharing;this.upload=upload;this.clients=clients;this.country=country;this.latencyMs=latencyMs;
        }
    }
    static void checkStatus(DataInputStream in) throws IOException {
        int code=in.readInt();
        if(code!=0)throw new IOException(code==2?"CAPACITY":code==4?"NEED_MEASUREMENT":code==1?"NEED_SHARING":"UNREACHABLE");
    }
    private void receiveMeasurement(DataInputStream in,DataOutputStream out) throws IOException {
        if(!measuring.compareAndSet(false,true)){out.writeInt(2);out.flush();return;}
        try {
            out.writeInt(0);out.flush();
            long total=0,start=System.nanoTime();byte[] buffer=new byte[16384];
            while(true) {
                int n=in.readInt();if(n==0)break;
                if(n<0 || n>buffer.length || total+n>8*1024*1024)throw new IOException("Probe limit");
                in.readFully(buffer,0,n);total+=n;
                if(System.nanoTime()-start>10_000_000_000L)throw new IOException("Probe timeout");
            }
            out.writeLong(total);out.flush();
        }finally{measuring.set(false);}
    }
    /** Peer-path benchmark; Android uses a physical-internet HTTPS measurement instead. */
    public long measure(Invitation peer) throws IOException {
        try(Socket s=open(peer,MEASURE)) {
            DataInputStream in=new DataInputStream(s.getInputStream());checkStatus(in);
            DataOutputStream out=new DataOutputStream(s.getOutputStream());
            byte[] bytes=new byte[16384];new java.security.SecureRandom().nextBytes(bytes);
            long total=0,start=System.nanoTime();
            while(total<1024*1024) {out.writeInt(bytes.length);out.write(bytes);total+=bytes.length;}
            out.writeInt(0);out.flush();
            if(in.readLong()!=total)throw new IOException("Incomplete probe");
            long elapsed=Math.max(1,System.nanoTime()-start);
            long speed=Math.max(3,(long)(total*800_000_000.0/elapsed));
            setMeasuredUpload(speed);return speed;
        }
    }
    public Session connect(Invitation peer,Runnable lost) throws IOException {
        if(!sharing)throw new IOException("NEED_SHARING");
        if(measured<=0)throw new IOException("NEED_MEASUREMENT");
        SSLSocket control=open(peer,JOIN);
        try {
            DataOutputStream out=new DataOutputStream(control.getOutputStream());
            Invitation reverse;
            synchronized(admission){
                TransportRegistration registered=peerTransports.get(peer.fingerprint);
                if(registered!=null && !registered.isLive())throw new IOException("Transport closed");
                reverse=registered==null?invitation(control.getLocalAddress().getHostAddress())
                        :new Invitation(control.getLocalAddress().getHostAddress(),port(),identity.fingerprint,registered.inboundToken);
            }
            out.writeUTF(reverse.encode());out.flush();
            checkStatus(new DataInputStream(control.getInputStream()));
            Session session=new Session(peer,control,lost);
            synchronized(admission){if(control.isClosed() || !sharing)throw new IOException("Transport closed");sessions.add(session);}
            workers.execute(session::heartbeat);return session;
        }catch(Exception e){closeQuietly(control);throw e instanceof IOException?(IOException)e:new IOException(e);}
    }
    public Session connect(PeerRoute peer,Runnable lost)throws IOException{
        if(measured<=0)throw new IOException("NEED_MEASUREMENT");
        PeerRoute own=advertisedRoute;if(own==null)throw new IOException("Own public route required");
        SSLSocket control=open(peer,JOIN_PUBLIC);
        try{
            DataOutputStream out=new DataOutputStream(control.getOutputStream());out.writeUTF(own.encode());out.flush();
            checkStatus(new DataInputStream(control.getInputStream()));
            Session session=new Session(peer,control,lost);
            synchronized(admission){if(control.isClosed() || !sharing)throw new IOException("Transport closed");sessions.add(session);}
            workers.execute(session::heartbeat);return session;
        }catch(Exception e){closeQuietly(control);throw e instanceof IOException?(IOException)e:new IOException(e);}
    }
    public final class Session implements AutoCloseable {
        private final Invitation peer;private final PeerRoute publicPeer;private final Socket control;private final Runnable lost;
        private final Set<Socket> streams=ConcurrentHashMap.newKeySet();
        private final AtomicBoolean live=new AtomicBoolean(true),closed=new AtomicBoolean();
        Session(Invitation peer,Socket control,Runnable lost){this.peer=peer;this.publicPeer=null;this.control=control;this.lost=lost;}
        Session(PeerRoute peer,Socket control,Runnable lost){this.peer=null;this.publicPeer=peer;this.control=control;this.lost=lost;}
        private String fingerprint(){return peer==null?publicPeer.fingerprint:peer.fingerprint;}
        public boolean isLive(){return live.get() && sharing;}
        /** Revoke new streams without performing TLS I/O; close still releases all resources. */
        public void invalidate(){live.set(false);}
        private void heartbeat() {
            try {
                DataOutputStream out=new DataOutputStream(control.getOutputStream());
                DataInputStream in=new DataInputStream(control.getInputStream());
                control.setSoTimeout(12000);
                while(isLive()) {
                    out.writeInt(MAGIC);out.flush();
                    if(in.readInt()!=MAGIC)break;
                    Thread.sleep(2000);
                }
            }catch(IOException|InterruptedException ignored){}
            finally {boolean notify=live.get();close();if(notify)lost.run();}
        }
        public Socket openTcp(String host,int port) throws IOException {
            Socket s=openStream(TCP);
            try {
                DataOutputStream out=new DataOutputStream(s.getOutputStream());out.writeUTF(host);out.writeInt(port);out.flush();
                checkStatus(new DataInputStream(s.getInputStream()));s.setSoTimeout(60000);return s;
            }catch(IOException e){release(s);throw e;}
        }
        public Socket openUdp() throws IOException {
            Socket s=openStream(UDP);
            try{checkStatus(new DataInputStream(s.getInputStream()));s.setSoTimeout(35000);return s;}
            catch(IOException e){release(s);throw e;}
        }
        private Socket openStream(int op) throws IOException {
            if(!isLive())throw new IOException("NEED_SHARING");
            streams.removeIf(Socket::isClosed);
            if(streams.size()>=24)throw new IOException("Too many streams");
            Socket s=publicPeer==null?open(peer,op):open(publicPeer,op);streams.add(s);
            if(!isLive()){release(s);throw new IOException("Stopped");}
            return s;
        }
        public void release(Socket socket){streams.remove(socket);closeQuietly(socket);}
        @Override public void close(){
            invalidate();
            if(!closed.compareAndSet(false,true))return;
            closeQuietly(control);streams.forEach(Node::closeQuietly);streams.clear();sessions.remove(this);
        }
    }
    @Override public synchronized void close(){
        discoveryExchange=null;
        stopAdmission();admission.setSharing(false);
        relaySupport.stop();directory.clear();
        synchronized(admission){peerTransports.values().forEach(TransportRegistration::close);}
        publicSockets.forEach(Node::closeQuietly);publicSockets.clear();
        sessions.forEach(Session::close);closeQuietly(listener);
        sockets.forEach(Node::closeQuietly);sockets.clear();
        controls.clear();flows.clear();workers.shutdownNow();
    }
    static void closeQuietly(Closeable closeable){if(closeable!=null)try{closeable.close();}catch(IOException ignored){}}
}
