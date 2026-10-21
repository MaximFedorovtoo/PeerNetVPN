package org.uzel.core;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Single-hop participant relay. Outer TLS authorizes attachments; inner TLS authenticates exits. */
final class ParticipantRelay {
    static final int REGISTER=20,CONNECT=21,ATTACH=22;
    private final Node node;
    private final Object lock=new Object();
    private final Map<String,HostedRegistration> hosted=new HashMap<>();
    private final Map<String,Circuit> circuits=new HashMap<>();
    private final Set<Registration> registrations=ConcurrentHashMap.newKeySet();
    private final Set<Socket> bridges=ConcurrentHashMap.newKeySet();
    ParticipantRelay(Node node){this.node=node;}

    void handle(int op,Socket socket,String id,DataInputStream in,DataOutputStream out)throws IOException {
        if(node.measuredUpload()<=0){out.writeInt(4);out.flush();return;}
        if(op==REGISTER)host(socket,id,in,out);
        else if(op==CONNECT)connect(socket,id,in,out);
        else if(op==ATTACH)attach(socket,id,in,out);
    }
    private void host(Socket socket,String id,DataInputStream in,DataOutputStream out)throws IOException {
        HostedRegistration registration=new HostedRegistration(id,socket);
        synchronized(lock){
            if(hosted.containsKey(id) || !node.retainRelay(id)){out.writeInt(2);out.flush();return;}
            hosted.put(id,registration);
        }
        try{
            socket.setSoTimeout(10000);out.writeInt(0);out.flush();
            while(active() && !socket.isClosed()){
                if(in.readInt()!=Node.MAGIC)throw new IOException("Relay heartbeat");
                String ticket;
                try{ticket=registration.requests.poll(1,TimeUnit.SECONDS);}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new InterruptedIOException();}
                out.writeUTF(ticket==null?"":ticket);out.flush();
            }
        }finally{
            synchronized(lock){hosted.remove(id,registration);}
            registration.close();node.releaseRelay(id);
        }
    }
    private boolean active(){return node.isSharing() && node.isPublicMode() && node.measuredUpload()>0;}
    private void connect(Socket socket,String client,DataInputStream in,DataOutputStream out)throws IOException {
        String target=in.readUTF();Circuit circuit;
        synchronized(lock){
            HostedRegistration registration=hosted.get(target);
            if(registration==null || registration.socket.isClosed() || !active()){out.writeInt(5);out.flush();return;}
            long count=circuits.values().stream().filter(c->c.registration==registration).count();
            if(circuits.size()>=48 || count>=24 || client.equals(target) || !node.retainRelay(client)){out.writeInt(2);out.flush();return;}
            String ticket=TlsIdentity.randomSecret();circuit=new Circuit(ticket,client,registration,socket);
            circuits.put(ticket,circuit);
            if(!registration.requests.offer(ticket)){circuit.close();throw new IOException("Relay queue full");}
        }
        try{
            Socket targetSocket;
            try{targetSocket=circuit.attached.get(8,TimeUnit.SECONDS);}
            catch(InterruptedException e){Thread.currentThread().interrupt();throw new InterruptedIOException();}
            catch(ExecutionException|TimeoutException e){throw new IOException("Relay target unavailable",e);}
            socket.setSoTimeout(60000);targetSocket.setSoTimeout(60000);
            out.writeInt(0);out.flush();
            transfer(socket,targetSocket,true);
        }finally{circuit.close();}
    }
    private void attach(Socket socket,String target,DataInputStream in,DataOutputStream out)throws IOException {
        String ticket=in.readUTF();Circuit circuit;
        synchronized(lock){
            circuit=circuits.get(ticket);
            if(circuit==null || !circuit.registration.id.equals(target) || circuit.registration!=hosted.get(target)
                    || System.nanoTime()-circuit.created>TimeUnit.SECONDS.toNanos(8) || !circuit.claimed.compareAndSet(false,true)){
                out.writeInt(5);out.flush();return;
            }
            out.writeInt(0);out.flush();
            if(!circuit.attached.complete(socket))throw new IOException("Expired relay ticket");
        }
        try{circuit.finished.await();}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new InterruptedIOException();}
        finally{circuit.close();}
    }

    Registration register(PeerRoute relay)throws IOException {
        if(relay.isRelay() || !active())throw new IOException("Direct public relay and measurement required");
        Socket control=node.open(relay,REGISTER);
        try{
            Node.checkStatus(new DataInputStream(control.getInputStream()));
            Registration registration=new Registration(relay,control);registrations.add(registration);
            node.workers.execute(registration::run);return registration;
        }catch(Exception e){Node.closeQuietly(control);throw e instanceof IOException?(IOException)e:new IOException(e);}
    }
    Socket openTunnel(PeerRoute route)throws IOException {
        Socket outer=node.open(route.relay,CONNECT);
        try{
            DataOutputStream out=new DataOutputStream(outer.getOutputStream());out.writeUTF(route.fingerprint);out.flush();
            Node.checkStatus(new DataInputStream(outer.getInputStream()));
            // A real connected TCP pair gives Android Conscrypt a genuine socket FD.
            // Check the exact source port, so another local app cannot claim the bridge.
            Socket local=null,accepted=null;
            try(ServerSocket listener=new ServerSocket(0,4,InetAddress.getByName("127.0.0.1"))){
                listener.setSoTimeout(2000);
                local=new Socket();local.connect(new InetSocketAddress("127.0.0.1",listener.getLocalPort()),2000);
                for(int attempts=0;attempts<4;attempts++){
                    Socket candidate=listener.accept();
                    if(candidate.getInetAddress().isLoopbackAddress() && candidate.getPort()==local.getLocalPort()){accepted=candidate;break;}
                    Node.closeQuietly(candidate);
                }
                if(accepted==null)throw new IOException("Local relay bridge collision");
                final Socket bridge=accepted,client=local;
                bridges.add(bridge);bridges.add(outer);bridges.add(client);
                if(!active())throw new IOException("Relay stopped");
                node.workers.execute(()->{
                    try{transfer(bridge,outer,false);}catch(IOException ignored){}
                    // The returned client belongs to the inner TLS caller. Closing
                    // our peer endpoint signals EOF while preserving buffered data;
                    // closing client itself makes Android Conscrypt reject reads.
                    finally{Node.closeQuietly(bridge);Node.closeQuietly(outer);
                        bridges.remove(bridge);bridges.remove(outer);bridges.remove(client);}
                });
                return local;
            }catch(Exception e){
                Node.closeQuietly(local);Node.closeQuietly(accepted);
                if(local!=null)bridges.remove(local);if(accepted!=null)bridges.remove(accepted);bridges.remove(outer);
                throw e instanceof IOException?(IOException)e:new IOException(e);
            }
        }catch(Exception e){Node.closeQuietly(outer);throw e instanceof IOException?(IOException)e:new IOException(e);}
    }
    private void transfer(Socket a,Socket b,boolean pace)throws IOException {
        Future<?> forward;
        try{forward=node.workers.submit(()->{
            try{node.pump(a.getInputStream(),b.getOutputStream(),pace,null);b.shutdownOutput();}
            catch(IOException ignored){Node.closeQuietly(a);Node.closeQuietly(b);}
        });}catch(RejectedExecutionException e){throw new IOException("Relay busy",e);}
        try{node.pump(b.getInputStream(),a.getOutputStream(),pace,null);}
        finally{Node.closeQuietly(a);Node.closeQuietly(b);forward.cancel(true);}
    }
    void stop(){
        registrations.forEach(Registration::close);
        List<HostedRegistration> hosts;List<Circuit> connections;
        synchronized(lock){hosts=new ArrayList<>(hosted.values());connections=new ArrayList<>(circuits.values());}
        hosts.forEach(HostedRegistration::close);connections.forEach(Circuit::close);
        bridges.forEach(Node::closeQuietly);bridges.clear();
    }
    final class Registration implements AutoCloseable {
        final PeerRoute route,relay;
        final Socket control;
        final AtomicBoolean live=new AtomicBoolean(true);
        final Set<Socket> channels=ConcurrentHashMap.newKeySet();
        final Semaphore slots=new Semaphore(24);
        Registration(PeerRoute relay,Socket control){this.relay=relay;this.control=control;route=PeerRoute.viaRelay(relay,node.identity.fingerprint);}
        boolean isLive(){return live.get() && active() && !control.isClosed();}
        void run(){
            try{
                DataInputStream in=new DataInputStream(control.getInputStream());DataOutputStream out=new DataOutputStream(control.getOutputStream());
                control.setSoTimeout(12000);
                while(isLive()){
                    out.writeInt(Node.MAGIC);out.flush();String ticket=in.readUTF();
                    if(ticket.isEmpty())continue;
                    if(!ticket.matches("[0-9a-f]{64}") || !slots.tryAcquire())throw new IOException("Relay requests");
                    try{node.workers.execute(()->{try{serve(ticket);}finally{slots.release();}});}
                    catch(RejectedExecutionException e){slots.release();throw new IOException("Relay busy",e);}
                }
            }catch(IOException ignored){}finally{close();}
        }
        private void serve(String ticket){
            Socket outer=null,local=null;
            try{
                if(!isLive())return;
                outer=node.open(relay,ATTACH);channels.add(outer);
                DataOutputStream out=new DataOutputStream(outer.getOutputStream());out.writeUTF(ticket);out.flush();
                Node.checkStatus(new DataInputStream(outer.getInputStream()));
                local=new Socket();channels.add(local);
                // Never use a relay-provided address or port for the loopback destination.
                local.connect(new InetSocketAddress("127.0.0.1",node.port()),2000);
                if(!isLive())return;
                transfer(outer,local,false);
            }catch(IOException|RuntimeException ignored){}
            finally{Node.closeQuietly(outer);Node.closeQuietly(local);if(outer!=null)channels.remove(outer);if(local!=null)channels.remove(local);}
        }
        @Override public void close(){
            if(!live.getAndSet(false))return;
            Node.closeQuietly(control);channels.forEach(Node::closeQuietly);channels.clear();registrations.remove(this);
        }
    }
    private final class HostedRegistration implements AutoCloseable {
        final String id;final Socket socket;final BlockingQueue<String> requests=new ArrayBlockingQueue<>(24);
        HostedRegistration(String id,Socket socket){this.id=id;this.socket=socket;}
        public void close(){
            Node.closeQuietly(socket);List<Circuit> owned=new ArrayList<>();
            synchronized(lock){for(Circuit circuit:circuits.values())if(circuit.registration==this)owned.add(circuit);}
            owned.forEach(Circuit::close);requests.clear();
        }
    }
    private final class Circuit implements AutoCloseable {
        final String ticket,client;final HostedRegistration registration;final Socket request;
        final long created=System.nanoTime();final AtomicBoolean claimed=new AtomicBoolean(),closed=new AtomicBoolean();
        final CompletableFuture<Socket> attached=new CompletableFuture<>();final CountDownLatch finished=new CountDownLatch(1);
        Circuit(String ticket,String client,HostedRegistration registration,Socket request){this.ticket=ticket;this.client=client;this.registration=registration;this.request=request;}
        public void close(){
            if(!closed.compareAndSet(false,true))return;
            synchronized(lock){circuits.remove(ticket,this);registration.requests.remove(ticket);
                attached.completeExceptionally(new IOException("Relay closed"));
                if(!attached.isCompletedExceptionally())Node.closeQuietly(attached.getNow(null));
            }
            Node.closeQuietly(request);node.releaseRelay(client);finished.countDown();
        }
    }
}
