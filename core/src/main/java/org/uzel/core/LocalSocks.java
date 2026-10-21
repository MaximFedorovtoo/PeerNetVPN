package org.uzel.core;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Authenticated loopback bridge; all destination traffic travels through the peer session. */
public final class LocalSocks implements AutoCloseable {
    private static final InetAddress LOOPBACK;
    static { try { LOOPBACK=InetAddress.getByAddress(new byte[]{127,0,0,1}); }
        catch(UnknownHostException e){throw new ExceptionInInitializerError(e);} }
    private final Node node;
    private final Node.Session session;
    private final String username=TlsIdentity.randomSecret(), password=TlsIdentity.randomSecret();
    private final Set<Flow> flows=ConcurrentHashMap.newKeySet();
    private final Semaphore permits=new Semaphore(16);
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(0,64,30,TimeUnit.SECONDS,
            new SynchronousQueue<>(),r->{Thread t=new Thread(r,"uzel-socks");t.setDaemon(true);return t;});
    private volatile ServerSocket listener;
    private volatile boolean closed;

    public LocalSocks(Node node,Node.Session session){this.node=Objects.requireNonNull(node);this.session=Objects.requireNonNull(session);}
    public String username(){return username;}
    public String password(){return password;}
    public int port(){ServerSocket s=listener;return s==null?0:s.getLocalPort();}
    public synchronized void start() throws IOException {
        if(closed || listener!=null || !session.isLive())throw new IOException("SOCKS unavailable");
        listener=new ServerSocket(0,16,LOOPBACK);
        workers.execute(this::accept);
    }
    private boolean live(){return !closed && session.isLive();}
    private void accept() {
        while(live())try {
            Socket socket=listener.accept();
            if(!socket.getInetAddress().equals(LOOPBACK) || !permits.tryAcquire()){Node.closeQuietly(socket);continue;}
            Flow flow=new Flow(socket);flows.add(flow);
            if(!live()){flow.close();permits.release();break;}
            try {workers.execute(()->{try{handle(flow);}catch(IOException|RejectedExecutionException ignored){}
                finally{flow.close();flows.remove(flow);permits.release();}});}
            catch(RejectedExecutionException e){flow.close();flows.remove(flow);permits.release();}
        }catch(IOException e){break;}
    }
    private void handle(Flow flow) throws IOException {
        Socket local=flow.local;local.setSoTimeout(10000);
        DataInputStream in=new DataInputStream(local.getInputStream());
        DataOutputStream out=new DataOutputStream(local.getOutputStream());
        if(in.readUnsignedByte()!=5)return;
        int methods=in.readUnsignedByte();boolean auth=false;
        for(int i=0;i<methods;i++)if(in.readUnsignedByte()==2)auth=true;
        out.write(new byte[]{5,(byte)(auth?2:255)});out.flush();if(!auth)return;
        if(in.readUnsignedByte()!=1)return;
        String user=credential(in),pass=credential(in);
        boolean valid=TlsIdentity.constantEquals(username,user)&TlsIdentity.constantEquals(password,pass);
        out.write(new byte[]{1,(byte)(valid?0:1)});out.flush();if(!valid || !live())return;
        if(in.readUnsignedByte()!=5)return;
        int command=in.readUnsignedByte();if(in.readUnsignedByte()!=0)return;
        String host=SocksPacket.readHost(in,in.readUnsignedByte());int port=in.readUnsignedShort();
        if(!live())return;
        if(command==1 && port!=0)tcp(flow,host,port,out);
        else if(command==3)udp(flow,host,port,in,out);
        else reply(out,7,0);
    }
    private static String credential(DataInputStream in) throws IOException {
        int n=in.readUnsignedByte();if(n==0)throw new IOException("Empty credential");
        byte[] b=new byte[n];in.readFully(b);return new String(b,StandardCharsets.US_ASCII);
    }
    private static void reply(DataOutputStream out,int status,int port) throws IOException {
        out.write(new byte[]{5,(byte)status,0,1,127,0,0,1});out.writeShort(port);out.flush();
    }
    private Socket remote(Flow flow,Socket socket) throws IOException {
        synchronized(flow){if(flow.stopped || !live()){session.release(socket);throw new IOException("Stopped");}
            flow.remote=socket;return socket;}
    }
    private void tcp(Flow flow,String host,int port,DataOutputStream out) throws IOException {
        Socket remote;
        try{remote=remote(flow,session.openTcp(host,port));}
        catch(IOException e){reply(out,5,0);return;}
        reply(out,0,0);flow.local.setSoTimeout(60000);
        Future<?> upload=workers.submit(()->{
            try{node.pump(flow.local.getInputStream(),remote.getOutputStream(),false,null);remote.shutdownOutput();}
            catch(IOException e){flow.close();}
        });
        try{node.pump(remote.getInputStream(),out,false,node.receivedBytes);}
        finally{flow.close();upload.cancel(true);}
    }
    private void udp(Flow flow,String host,int requestedPort,DataInputStream control,DataOutputStream out) throws IOException {
        // This endpoint is IPv4 loopback; never resolve the client's claimed association address.
        if(!host.equals("0.0.0.0") && !host.equals("127.0.0.1")){reply(out,2,0);return;}
        DatagramSocket udp=new DatagramSocket(0,LOOPBACK);
        synchronized(flow){if(flow.stopped || !live()){udp.close();return;}flow.udp=udp;}
        Socket remote;
        try{remote=remote(flow,session.openUdp());}
        catch(IOException e){reply(out,5,0);return;}
        DataOutputStream peerOut=new DataOutputStream(remote.getOutputStream());
        DataInputStream peerIn=new DataInputStream(remote.getInputStream());
        reply(out,0,udp.getLocalPort());flow.local.setSoTimeout(1000);
        Future<?> upload=workers.submit(()->{
            byte[] buffer=new byte[65508];
            try{while(live() && !flow.stopped){
                DatagramPacket packet=new DatagramPacket(buffer,buffer.length);udp.receive(packet);
                InetSocketAddress source=(InetSocketAddress)packet.getSocketAddress();
                if(!source.getAddress().equals(LOOPBACK) || (requestedPort!=0 && source.getPort()!=requestedPort)
                        || (flow.source!=null && !flow.source.equals(source)) || packet.getLength()>65507)continue;
                byte[] bytes=Arrays.copyOf(packet.getData(),packet.getLength());
                try{SocksPacket.parse(bytes);}catch(IOException malformed){continue;}
                if(!live())break;
                flow.source=source;peerOut.writeInt(bytes.length);peerOut.write(bytes);peerOut.flush();
            }}catch(IOException ignored){}finally{flow.close();}
        });
        Future<?> download=null;
        try{
            download=workers.submit(()->{
                try{while(live() && !flow.stopped){
                    int n=peerIn.readInt();if(n<7 || n>65507)throw new IOException("Datagram length");
                    byte[] bytes=new byte[n];peerIn.readFully(bytes);SocksPacket parsed=SocksPacket.parse(bytes);
                    InetSocketAddress source=flow.source;
                    if(source!=null && live()){udp.send(new DatagramPacket(bytes,n,source));node.receivedBytes.addAndGet(parsed.payload.length);}
                }}catch(IOException ignored){}finally{flow.close();}
            });
            while(live() && !flow.stopped)try{control.read();break;}
            catch(SocketTimeoutException ignored){}
        }finally{flow.close();upload.cancel(true);if(download!=null)download.cancel(true);}
    }
    private final class Flow implements AutoCloseable {
        final Socket local;
        Socket remote;DatagramSocket udp;
        volatile InetSocketAddress source;
        volatile boolean stopped;
        Flow(Socket local){this.local=local;}
        public synchronized void close(){
            if(stopped)return;stopped=true;Node.closeQuietly(local);Node.closeQuietly(udp);
            if(remote!=null)session.release(remote);
        }
    }
    @Override public synchronized void close(){
        if(closed)return;closed=true;Node.closeQuietly(listener);flows.forEach(Flow::close);workers.shutdownNow();
    }
}
