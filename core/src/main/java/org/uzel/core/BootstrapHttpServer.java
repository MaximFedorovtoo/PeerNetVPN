package org.uzel.core;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded discovery-only HTTP origin. Publish solely behind a validating HTTPS gateway. */
public final class BootstrapHttpServer implements AutoCloseable {
    public static final int MAX_BODY=131072;
    @FunctionalInterface public interface Exchange { byte[] exchange(byte[] request)throws IOException; }
    private final Exchange handler;
    private final ServerSocket listener;
    private final Set<Socket> sockets=ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed=new AtomicBoolean();
    private final long requestDeadlineMillis;
    private final ScheduledThreadPoolExecutor deadlines=new ScheduledThreadPoolExecutor(1,r->{Thread t=new Thread(r,"peernet-bootstrap-deadline");t.setDaemon(true);return t;});
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(4,4,0,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16),r->{Thread t=new Thread(r,"peernet-bootstrap-http");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private final Map<InetAddress,Integer> requests=new HashMap<>();
    private long window=System.nanoTime();
    private int total;

    public BootstrapHttpServer(InetAddress bind,int port,Exchange handler)throws IOException {
        this(bind,port,handler,10000);
    }
    BootstrapHttpServer(InetAddress bind,int port,Exchange handler,long requestDeadlineMillis)throws IOException {
        if(bind==null||bind.isAnyLocalAddress()||handler==null)throw new IllegalArgumentException("Explicit bind and handler required");
        if(requestDeadlineMillis<1)throw new IllegalArgumentException("Request deadline");
        this.requestDeadlineMillis=requestDeadlineMillis;deadlines.setRemoveOnCancelPolicy(true);
        this.handler=handler;listener=new ServerSocket();
        try{listener.setReuseAddress(true);listener.bind(new InetSocketAddress(bind,port),16);}
        catch(IOException failure){listener.close();workers.shutdownNow();deadlines.shutdownNow();throw failure;}
        Thread acceptor=new Thread(this::accept,"peernet-bootstrap-accept");acceptor.setDaemon(true);acceptor.start();
    }
    public int port(){return listener.getLocalPort();}
    private void accept(){
        while(!closed.get())try{
            Socket socket=listener.accept();socket.setSoTimeout(5000);
            synchronized(sockets){
                if(closed.get()){socket.close();break;}
                sockets.add(socket);
            }
            ScheduledFuture<?> deadline=null;
            try{
                // Starts at accept, not worker dispatch: queue/header/body/response share one deadline.
                deadline=deadlines.schedule(()->{try{socket.close();}catch(IOException ignored){}},requestDeadlineMillis,TimeUnit.MILLISECONDS);
                ScheduledFuture<?> timer=deadline;
                workers.execute(()->{try{serve(socket);}finally{timer.cancel(false);}});
            }catch(RejectedExecutionException full){if(deadline!=null)deadline.cancel(false);sockets.remove(socket);socket.close();}
        }catch(IOException failure){if(!closed.get())close();}
    }
    private synchronized boolean admitted(InetAddress address){
        long now=System.nanoTime();
        if(now-window>=TimeUnit.MINUTES.toNanos(1)){window=now;total=0;requests.clear();}
        Integer count=requests.get(address);
        if(total>=600||(count!=null&&count>=120)||(count==null&&requests.size()>=128))return false;
        requests.put(address,count==null?1:count+1);total++;return true;
    }
    private void serve(Socket socket){
        try{
            Socket connection=socket;
            if(!admitted(connection.getInetAddress())){reply(connection,429,"Too Many Requests",new byte[0]);return;}
            InputStream input=connection.getInputStream();
            String headers=readHeaders(input);
            String[] lines=headers.split("\r\n",-1);
            String[] request=lines[0].split(" ",-1);
            if(request.length!=3||!"HTTP/1.1".equals(request[2]))throw new BadRequest(400,"Bad Request");
            Map<String,String> fields=new HashMap<>();
            for(int i=1;i<lines.length;i++){
                if(lines[i].isEmpty())continue;
                int colon=lines[i].indexOf(':');
                if(colon<1)throw new BadRequest(400,"Bad Request");
                String name=lines[i].substring(0,colon).toLowerCase(Locale.ROOT);
                if(!name.matches("[a-z0-9!#$%&'*+.^_`|~-]+"))throw new BadRequest(400,"Bad Request");
                String value=lines[i].substring(colon+1).trim();
                if(fields.put(name,value)!=null)throw new BadRequest(400,"Bad Request");
            }
            if(!fields.containsKey("host")||fields.get("host").isEmpty()||fields.containsKey("transfer-encoding"))throw new BadRequest(400,"Bad Request");
            if(fields.containsKey("expect"))throw new BadRequest(417,"Expectation Failed");
            int length=0;
            if(fields.containsKey("content-length")){
                String value=fields.get("content-length");
                if(!value.matches("[0-9]{1,9}"))throw new BadRequest(400,"Bad Request");
                length=Integer.parseInt(value);
                if(length>MAX_BODY)throw new BadRequest(413,"Payload Too Large");
            }
            if("GET".equals(request[0])&&"/health".equals(request[1])&&length==0){
                reply(connection,200,"OK","ok\n".getBytes(StandardCharsets.US_ASCII));return;
            }
            if(!"POST".equals(request[0])||!"/v1/exchange".equals(request[1]))throw new BadRequest(404,"Not Found");
            if(!fields.containsKey("content-length"))throw new BadRequest(411,"Length Required");
            if(length==0)throw new BadRequest(400,"Bad Request");
            byte[] body=new byte[length];new DataInputStream(input).readFully(body);
            byte[] response;
            try{response=handler.exchange(body);}catch(IOException|IllegalArgumentException invalid){throw new BadRequest(400,"Bad Request");}
            if(response==null||response.length>MAX_BODY)throw new BadRequest(500,"Internal Server Error");
            reply(connection,200,"OK",response);
        }catch(BadRequest invalid){
            try{reply(socket,invalid.status,invalid.reason,new byte[0]);}catch(IOException ignored){}
        }catch(IOException|RuntimeException ignored){
            // No request bodies, signed records, secrets or internal errors are logged.
        }finally{sockets.remove(socket);try{socket.close();}catch(IOException ignored){}}
    }
    private static String readHeaders(InputStream input)throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();int state=0;
        while(bytes.size()<8192){
            int value=input.read();if(value<0)throw new EOFException();
            if(value!=9&&value!=10&&value!=13&&(value<32||value>126))throw new BadRequest(400,"Bad Request");
            bytes.write(value);
            state=(state==0||state==2)?(value==13?state+1:0):(value==10?state+1:value==13?1:0);
            if(state==4)return new String(bytes.toByteArray(),StandardCharsets.US_ASCII);
        }
        throw new BadRequest(431,"Request Header Fields Too Large");
    }
    private static void reply(Socket socket,int status,String reason,byte[] body)throws IOException {
        OutputStream output=socket.getOutputStream();
        output.write(("HTTP/1.1 "+status+" "+reason+"\r\nContent-Type: application/octet-stream\r\nContent-Length: "+body.length
                +"\r\nCache-Control: no-store\r\nConnection: close\r\nX-Content-Type-Options: nosniff\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        output.write(body);output.flush();
    }
    private static final class BadRequest extends IOException {
        final int status;final String reason;
        BadRequest(int status,String reason){this.status=status;this.reason=reason;}
    }
    @Override public void close(){
        if(!closed.compareAndSet(false,true))return;
        try{listener.close();}catch(IOException ignored){}
        synchronized(sockets){for(Socket socket:sockets)try{socket.close();}catch(IOException ignored){}sockets.clear();}
        workers.shutdownNow();
        deadlines.shutdownNow();
    }
}
