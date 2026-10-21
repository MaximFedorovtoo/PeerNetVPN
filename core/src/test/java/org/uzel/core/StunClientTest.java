package org.uzel.core;

import static org.junit.Assert.*;
import org.junit.Test;
import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.*;

public class StunClientTest {
    private static final byte[] TX={1,2,3,4,5,6,7,8,9,10,11,12};
    private static final InetSocketAddress SERVER=new InetSocketAddress("127.0.0.1",19302);

    private byte[] response(String address,int port,byte[] tx)throws Exception {
        byte[] ip=InetAddress.getByName(address).getAddress();
        int value=ip.length==4?8:20;
        ByteBuffer b=ByteBuffer.allocate(24+value);
        b.putShort((short)0x0101).putShort((short)(4+value)).putInt(0x2112a442).put(tx);
        b.putShort((short)0x0020).putShort((short)value).put((byte)0).put((byte)(ip.length==4?1:2));
        b.putShort((short)(port^0x2112));
        byte[] mask=ByteBuffer.allocate(16).putInt(0x2112a442).put(tx).array();
        for(int i=0;i<ip.length;i++)b.put((byte)(ip[i]^mask[i]));
        return b.array();
    }
    private InetSocketAddress parse(byte[] packet)throws IOException {
        return StunClient.parseBindingResponse(packet,packet.length,SERVER,SERVER,TX);
    }
    private void invalid(byte[] packet)throws Exception {
        try{parse(packet);fail("Invalid STUN packet accepted");}catch(IOException expected){}
    }
    @Test public void decodesIpv4AndIpv6XorMappedAddress()throws Exception {
        assertEquals(new InetSocketAddress("203.0.113.7",54321),parse(response("203.0.113.7",54321,TX)));
        assertEquals(new InetSocketAddress("2001:db8::1234",12000),parse(response("2001:db8::1234",12000,TX)));
    }
    @Test public void rejectsCookieTransactionTypeSourceAndTrailingBytes()throws Exception {
        byte[] good=response("203.0.113.7",54321,TX);
        byte[] cookie=good.clone();cookie[4]^=1;invalid(cookie);
        byte[] transaction=good.clone();transaction[8]^=1;invalid(transaction);
        byte[] type=good.clone();type[0]=0;invalid(type);
        invalid(Arrays.copyOf(good,good.length-1));
        invalid(Arrays.copyOf(good,good.length+4));
        try{StunClient.parseBindingResponse(good,good.length,new InetSocketAddress("127.0.0.1",19303),SERVER,TX);fail();}catch(IOException expected){}
    }
    @Test public void rejectsMalformedFamilyLengthReservedAndDuplicateMapping()throws Exception {
        byte[] good=response("203.0.113.7",54321,TX);
        byte[] family=good.clone();family[25]=3;invalid(family);
        byte[] length=good.clone();length[23]=7;invalid(length);
        byte[] reserved=good.clone();reserved[24]=1;invalid(reserved);
        byte[] duplicate=Arrays.copyOf(good,good.length+12);
        System.arraycopy(good,20,duplicate,good.length,12);duplicate[3]=24;invalid(duplicate);
        byte[] attrLength=good.clone();attrLength[23]=(byte)255;invalid(attrLength);
        byte[] empty=Arrays.copyOf(good,20);empty[3]=0;invalid(empty);
    }
    @Test public void skipsPaddedOptionalAttributeSafely()throws Exception {
        byte[] good=response("203.0.113.7",54321,TX);
        byte[] extended=Arrays.copyOf(good,good.length+8);extended[3]=20;
        ByteBuffer.wrap(extended,good.length,8).putShort((short)0x8022).putShort((short)1).put((byte)'x').put(new byte[3]);
        assertEquals(new InetSocketAddress("203.0.113.7",54321),parse(extended));
    }
    @Test public void realSocketIgnoresWrongTransactionAndRestoresTimeout()throws Exception {
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try(DatagramSocket server=new DatagramSocket(0,InetAddress.getLoopbackAddress());
            DatagramSocket client=new DatagramSocket(0,InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(3000);client.setSoTimeout(77);
            Future<?> answered=worker.submit(()->{
                try{
                    DatagramPacket request=new DatagramPacket(new byte[128],128);server.receive(request);
                    assertEquals(20,request.getLength());
                    assertEquals(0x0001,ByteBuffer.wrap(request.getData()).getShort()&65535);
                    byte[] tx=Arrays.copyOfRange(request.getData(),8,20);
                    byte[] bad=tx.clone();bad[0]^=1;
                    byte[] first=response("203.0.113.9",23456,bad);
                    server.send(new DatagramPacket(first,first.length,request.getSocketAddress()));
                    byte[] good=response("203.0.113.9",23456,tx);
                    server.send(new DatagramPacket(good,good.length,request.getSocketAddress()));
                }catch(Exception failure){throw new RuntimeException(failure);}
            });
            assertEquals(new InetSocketAddress("203.0.113.9",23456),StunClient.binding(client,new InetSocketAddress(InetAddress.getLoopbackAddress(),server.getLocalPort()),2000));
            assertEquals(77,client.getSoTimeout());answered.get(3,TimeUnit.SECONDS);
        }finally{worker.shutdownNow();}
    }
    @Test public void timeoutIsBoundedAndRestoresTimeout()throws Exception {
        try(DatagramSocket server=new DatagramSocket(0,InetAddress.getLoopbackAddress());
            DatagramSocket client=new DatagramSocket(0,InetAddress.getLoopbackAddress())) {
            client.setSoTimeout(91);long start=System.nanoTime();
            try{StunClient.binding(client,new InetSocketAddress(InetAddress.getLoopbackAddress(),server.getLocalPort()),250);fail();}catch(SocketTimeoutException expected){}
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<2000);
            assertEquals(91,client.getSoTimeout());
        }
    }
}
