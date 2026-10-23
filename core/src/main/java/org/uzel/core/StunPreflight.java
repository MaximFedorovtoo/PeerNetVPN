package org.uzel.core;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Short reachability hint for ICE servers, not proof of peer connectivity. Never returns a NAT mapping. */
public final class StunPreflight {
    public record Server(String host,int port){
        public Server{
            if(host==null||host.isBlank()||host.length()>253||port<1||port>65535)
                throw new IllegalArgumentException("STUN server");
        }
        String uri(){return StunPreflight.uri(host,port);}
    }
    public static final List<Server> DEFAULT_SERVERS=List.of(
        new Server("stun.cloudflare.com",3478),new Server("stun.l.google.com",19302));
    // Native DNS may ignore interruption. Reuse a fixed, bounded daemon pool instead of creating one per pairing.
    private static final ThreadPoolExecutor WORKERS=new ThreadPoolExecutor(8,8,30,TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(64),task->{Thread t=new Thread(task,"stun-preflight");t.setDaemon(true);return t;});
    static {WORKERS.allowCoreThreadTimeOut(true);}
    private StunPreflight(){}

    public static List<String> select(NetworkAccess network)throws IOException{return select(network,DEFAULT_SERVERS,3000);}
    public static List<String> select(NetworkAccess network,List<Server> servers,int timeoutMs)throws IOException{
        Objects.requireNonNull(network);servers=List.copyOf(servers);
        if(servers.isEmpty()||servers.size()>8||timeoutMs<250||timeoutMs>3000)throw new IllegalArgumentException("STUN preflight bounds");
        Selection selection=new Selection(network,System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeoutMs));
        try{
            for(int i=0;i<servers.size();i++){
                Server server=servers.get(i);final int index=i;
                selection.submit(()->selection.resolve(server,index));
            }
            int phase=selection.pending.arriveAndDeregister();
            long remaining=selection.deadline-System.nanoTime();
            if(remaining>0)try{selection.pending.awaitAdvanceInterruptibly(phase,remaining,TimeUnit.NANOSECONDS);}
                catch(TimeoutException expired){/* The shared deadline also bounds DNS. */}
            synchronized(selection.gate){
                selection.closed.set(true);
                if(!selection.responsive.isEmpty())return List.copyOf(new LinkedHashSet<>(selection.responsive.values()));
            }
            List<String> fallback=new ArrayList<>();for(Server server:servers)fallback.add(server.uri());return List.copyOf(fallback);
        }catch(InterruptedException interrupted){
            Thread.currentThread().interrupt();throw new InterruptedIOException("STUN preflight cancelled");
        }finally{selection.close();}
    }
    private static String uri(String host,int port){return "stun:"+(host.indexOf(':')>=0?"["+host+"]":host)+":"+port;}
    private static final class Selection {
        final NetworkAccess network;final long deadline;final Object gate=new Object();
        final AtomicBoolean closed=new AtomicBoolean();
        final Phaser pending=new Phaser(1);
        final Queue<FutureTask<Void>> tasks=new ConcurrentLinkedQueue<>();
        final Set<DatagramSocket> sockets=ConcurrentHashMap.newKeySet();
        final NavigableMap<Long,String> responsive=new TreeMap<>();
        Selection(NetworkAccess network,long deadline){this.network=network;this.deadline=deadline;}
        void submit(Runnable operation){
            if(closed.get())return;
            pending.register();
            FutureTask<Void> task=new FutureTask<>(()->{
                try{if(!closed.get())operation.run();}finally{pending.arriveAndDeregister();}
            },null);
            tasks.add(task);
            try{WORKERS.execute(task);}catch(RejectedExecutionException busy){pending.arriveAndDeregister();task.cancel(false);}
        }
        void resolve(Server server,int serverIndex){
            try{
                InetAddress[] addresses=network.resolve(server.host());Set<InetAddress> seen=new HashSet<>();
                for(int i=0;i<addresses.length&&!closed.get();i++){
                    InetAddress address=addresses[i];final int addressIndex=i;
                    if(address==null||!seen.add(address)||address.isAnyLocalAddress()||address.isMulticastAddress())continue;
                    submit(()->probe(new InetSocketAddress(address,server.port()),((long)serverIndex<<32)|Integer.toUnsignedLong(addressIndex)));
                }
            }catch(IOException|RuntimeException unavailable){/* Keep the full configured fallback if nothing replies. */}
        }
        void probe(InetSocketAddress server,long order){
            DatagramSocket socket=null;
            try{
                if(closed.get())return;
                long remaining=TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime());
                if(remaining<250)return;
                socket=network.datagram();sockets.add(socket);
                if(closed.get())return;
                remaining=TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime());
                if(remaining<250)return;
                // Only validated responses satisfy a probe. The returned mapped address is deliberately discarded.
                StunClient.binding(socket,server,(int)Math.min(3000,remaining));
                synchronized(gate){if(!closed.get()&&System.nanoTime()<deadline)
                    responsive.put(order,uri(server.getAddress().getHostAddress(),server.getPort()));}
            }catch(IOException|RuntimeException unavailable){/* A failed probe cannot become a selected endpoint. */}
            finally{if(socket!=null){socket.close();sockets.remove(socket);}}
        }
        void close(){
            closed.set(true);
            for(DatagramSocket socket:sockets)socket.close();
            for(FutureTask<Void> task:tasks)task.cancel(true);
            WORKERS.purge();
        }
    }
}
