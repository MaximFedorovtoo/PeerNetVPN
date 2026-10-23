package org.uzel.core;

import org.junit.Test;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class StunPreflightTest {
    private Class<?> helper()throws Exception{
        try{return Class.forName("org.uzel.core.StunPreflight");}
        catch(ClassNotFoundException missing){fail("Bounded STUN preflight is missing");return null;}
    }
    @SuppressWarnings("unchecked")
    private List<String> select(NetworkAccess network,List<InetSocketAddress> endpoints,int millis)throws Exception{
        Class<?> type=helper(),server=Class.forName(type.getName()+"$Server");List<Object> servers=new ArrayList<>();
        for(InetSocketAddress address:endpoints)servers.add(server.getConstructor(String.class,int.class).newInstance(address.getHostString(),address.getPort()));
        try{return (List<String>)type.getMethod("select",NetworkAccess.class,List.class,int.class).invoke(null,network,servers,millis);}
        catch(java.lang.reflect.InvocationTargetException failure){if(failure.getCause() instanceof IOException io)throw io;throw failure;}
    }
    @Test(timeout=5000) public void choosesResponsiveServerRatherThanItsReturnedNatMapping()throws Exception{
        try(Responder ready=new Responder();DatagramSocket silent=new DatagramSocket(0,InetAddress.getLoopbackAddress())){
            InetSocketAddress a=ready.address(),b=new InetSocketAddress("127.0.0.1",silent.getLocalPort());
            List<String> result=select(new NetworkAccess(),List.of(a,b),400);
            assertEquals(List.of("stun:127.0.0.1:"+a.getPort()),result);
            assertFalse(result.toString().contains("203.0.113.9"));
        }
    }
    @Test(timeout=5000) public void preservesEveryResponsiveEndpoint()throws Exception{
        try(Responder a=new Responder();Responder b=new Responder()){
            assertEquals(List.of("stun:127.0.0.1:"+a.address().getPort(),"stun:127.0.0.1:"+b.address().getPort()),
                select(new NetworkAccess(),List.of(a.address(),b.address()),1000));
        }
    }
    @Test(timeout=5000) public void deadlineIncludesSlowDnsAndFallbackRetainsConfiguredHostnames()throws Exception{
        NetworkAccess slow=new NetworkAccess(){@Override public InetAddress[] resolve(String host){
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while(System.nanoTime()<until)try{Thread.sleep(100);}catch(InterruptedException ignored){}
            return new InetAddress[0];
        }};
        long before=System.nanoTime();
        assertEquals(List.of("stun:slow.invalid:3478"),select(slow,List.of(InetSocketAddress.createUnresolved("slow.invalid",3478)),300));
        assertTrue("DNS exceeded whole selection deadline",System.nanoTime()-before<TimeUnit.SECONDS.toNanos(1));
    }
    @Test(timeout=5000) public void timeoutClosesEveryOwnedProbeSocket()throws Exception{
        List<DatagramSocket> opened=Collections.synchronizedList(new ArrayList<>());
        NetworkAccess tracked=new NetworkAccess(){@Override public DatagramSocket datagram()throws IOException{
            DatagramSocket socket=super.datagram();opened.add(socket);return socket;
        }};
        try(DatagramSocket silent=new DatagramSocket(0,InetAddress.getLoopbackAddress())){
            select(tracked,List.of(new InetSocketAddress("127.0.0.1",silent.getLocalPort())),300);
            assertFalse(opened.isEmpty());for(DatagramSocket socket:opened)assertTrue(socket.isClosed());
        }
    }
    private static final class Responder implements AutoCloseable {
        final DatagramSocket socket;final ExecutorService worker=Executors.newSingleThreadExecutor();
        Responder()throws Exception{
            socket=new DatagramSocket(0,InetAddress.getByName("127.0.0.1"));socket.setSoTimeout(100);
            worker.execute(()->{while(!socket.isClosed())try{
                DatagramPacket request=new DatagramPacket(new byte[128],128);socket.receive(request);
                if(request.getLength()!=20)continue;
                byte[] message=request.getData();
                ByteBuffer response=ByteBuffer.allocate(32).putShort((short)0x0101).putShort((short)12).putInt(0x2112a442)
                    .put(message,8,12).putShort((short)0x0020).putShort((short)8).put((byte)0).put((byte)1)
                    .putShort((short)(12000^0x2112)).putInt(0xcb007109^0x2112a442);
                socket.send(new DatagramPacket(response.array(),32,request.getSocketAddress()));
            }catch(SocketTimeoutException ignored){}catch(IOException closed){break;}});
        }
        InetSocketAddress address(){return new InetSocketAddress("127.0.0.1",socket.getLocalPort());}
        @Override public void close(){socket.close();worker.shutdownNow();}
    }
}
