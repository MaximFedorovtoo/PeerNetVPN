package org.uzel.core;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

/** Minimal RFC 8489 Binding diagnostic; never a relay or a NAT traversal guarantee. */
public final class StunClient {
    private static final int COOKIE=0x2112a442,MAX_PACKET=1024;
    private StunClient(){}

    /** Uses the caller's bound socket, restores its timeout and never closes it. */
    public static InetSocketAddress binding(DatagramSocket socket,InetSocketAddress server,int timeoutMs)throws IOException {
        if(socket==null||socket.isClosed()||!socket.isBound()||server==null||server.isUnresolved()
                ||server.getPort()<1||timeoutMs<250||timeoutMs>10000)throw new IllegalArgumentException("STUN binding arguments");
        synchronized(socket){
            int priorTimeout=socket.getSoTimeout();
            byte[] transaction=new byte[12];new SecureRandom().nextBytes(transaction);
            byte[] request=ByteBuffer.allocate(20).putShort((short)0x0001).putShort((short)0).putInt(COOKIE).put(transaction).array();
            long deadline=System.nanoTime()+timeoutMs*1_000_000L;
            long nextSend=0;int retryMs=500,received=0;
            try{
                while(System.nanoTime()<deadline){
                    long now=System.nanoTime();
                    if(now>=nextSend){socket.send(new DatagramPacket(request,request.length,server));nextSend=now+retryMs*1_000_000L;retryMs=Math.min(2000,retryMs*2);}
                    long wait=Math.min(deadline,nextSend)-System.nanoTime();
                    socket.setSoTimeout((int)Math.max(1,Math.min(10000,(wait+999999)/1_000_000)));
                    DatagramPacket packet=new DatagramPacket(new byte[MAX_PACKET+1],MAX_PACKET+1);
                    try{socket.receive(packet);}catch(SocketTimeoutException retry){continue;}
                    if(++received>64)throw new IOException("Too many unrelated STUN packets");
                    try{return parseBindingResponse(packet.getData(),packet.getLength(),(InetSocketAddress)packet.getSocketAddress(),server,transaction);}
                    catch(IOException invalid){/* Different transactions/sources cannot satisfy this binding. */}
                }
                throw new SocketTimeoutException("STUN binding timed out");
            }finally{if(!socket.isClosed())socket.setSoTimeout(priorTimeout);}
        }
    }

    static InetSocketAddress parseBindingResponse(byte[] data,int length,InetSocketAddress source,
            InetSocketAddress server,byte[] transaction)throws IOException {
        if(data==null||length<20||length>MAX_PACKET||length>data.length||transaction==null||transaction.length!=12
                ||server==null||server.isUnresolved()||!server.equals(source))throw new IOException("STUN source/size");
        ByteBuffer buffer=ByteBuffer.wrap(data,0,length);
        int type=buffer.getShort()&65535,bodyLength=buffer.getShort()&65535;
        if(type!=0x0101||(bodyLength&3)!=0||bodyLength+20!=length||buffer.getInt()!=COOKIE
                ||!MessageDigest.isEqual(transaction,Arrays.copyOfRange(data,8,20)))throw new IOException("STUN response header");
        buffer.position(20);InetSocketAddress mapped=null;
        while(buffer.position()<length){
            if(buffer.remaining()<4)throw new IOException("STUN truncated attribute");
            int attribute=buffer.getShort()&65535,size=buffer.getShort()&65535;
            int padded=(size+3)&~3,start=buffer.position();
            if(padded>buffer.remaining())throw new IOException("STUN attribute length");
            if(attribute==0x0020){
                if(mapped!=null||size<4)throw new IOException("STUN duplicate/malformed mapping");
                int reserved=buffer.get()&255,family=buffer.get()&255;
                int addressLength=family==1?4:family==2?16:0;
                if(reserved!=0||addressLength==0||size!=addressLength+4)throw new IOException("STUN address family");
                int port=(buffer.getShort()&65535)^(COOKIE>>>16);
                if(port==0)throw new IOException("STUN mapped port");
                byte[] address=new byte[addressLength],mask=ByteBuffer.allocate(16).putInt(COOKIE).put(transaction).array();
                for(int i=0;i<address.length;i++)address[i]=(byte)(buffer.get()^mask[i]);
                mapped=new InetSocketAddress(InetAddress.getByAddress(address),port);
            }
            buffer.position(start+padded);
        }
        if(mapped==null)throw new IOException("STUN XOR-MAPPED-ADDRESS missing");
        return mapped;
    }
}
