package org.uzel.app;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.test.InstrumentationTestCase;
import android.test.InstrumentationTestRunner;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONObject;
import org.uzel.core.StunClient;

/** Opt-in physical UDP diagnostic. No VPN, USB payload, port scan or production behavior. */
@SuppressWarnings("deprecation")
public final class NatTraversalProbeTest extends InstrumentationTestCase {
    private static final byte[] MAGIC="PNVPNNAT".getBytes(StandardCharsets.US_ASCII);
    private static final String[] STUN={"stun.l.google.com","stun.cloudflare.com"};
    private static final int[] PORTS={19302,3478};

    public void testHold() throws Exception {
        Bundle args=((InstrumentationTestRunner)getInstrumentation()).getArguments();
        // Ordinary instrumentation suite runs have no external network side effects.
        if(!"hold".equals(args.getString("action","")))return;
        Context context=getInstrumentation().getTargetContext();
        int seconds=Integer.parseInt(args.getString("holdSeconds","240"));
        if(seconds<120||seconds>300)throw new IllegalArgumentException("holdSeconds must be 120..300");
        byte[] secret=secret(args.getString("sharedSecret",""));
        String transport=args.getString("network","");
        if(!"cellular".equals(transport)&&!"wifi".equals(transport))throw new IllegalArgumentException("Choose network=cellular or wifi explicitly");
        String family=args.getString("family","ipv4");
        if(!"ipv4".equals(family)&&!"ipv6".equals(family))throw new IllegalArgumentException("family must be ipv4 or ipv6");
        ConnectivityManager manager=context.getSystemService(ConnectivityManager.class);
        Network physical=findNetwork(manager,"wifi".equals(transport)?NetworkCapabilities.TRANSPORT_WIFI:NetworkCapabilities.TRANSPORT_CELLULAR);
        PhysicalNetwork access=new PhysicalNetwork(physical);
        File peerFile=new File(context.getFilesDir(),"nat-peer.json"),stop=new File(context.getFilesDir(),"nat-stop");
        if(stop.exists()&&!stop.delete())throw new IOException("Cannot clear stale stop marker");
        if(peerFile.exists()&&!peerFile.delete())throw new IOException("Cannot clear stale peer candidate");
        InetSocketAddress firstServer=null,secondServer=null;
        int requestsReceived=0,matchingAcks=0,sent=0,invalid=0,unexpected=0;
        boolean unexpectedReported=false;
        try(DatagramSocket socket=access.datagram()) {
            socket.setSoTimeout(25);
            for(int serverIndex=0;serverIndex<STUN.length;serverIndex++) {
                String host=STUN[serverIndex];
                try {
                    InetSocketAddress server=resolveServer(access,host,PORTS[serverIndex],family,firstServer);
                    if(firstServer==null)firstServer=server;else secondServer=server;
                    InetSocketAddress mapped=StunClient.binding(socket,server,5000);
                    JSONObject result=new JSONObject().put("event","candidate").put("server",host)
                            .put("serverAddress",server.getAddress().getHostAddress()).put("serverPort",server.getPort())
                            .put("address",mapped.getAddress().getHostAddress()).put("port",mapped.getPort())
                            .put("localPort",socket.getLocalPort()).put("network",transport).put("family",family);
                    emit(result);
                }catch(IOException failure){emit(new JSONObject().put("event","stun_failed").put("server",host).put("reason",failure.getClass().getSimpleName()));}
            }
            emit(new JSONObject().put("event","ready").put("localPort",socket.getLocalPort()).put("network",transport)
                    .put("distinctStunEndpoints",firstServer!=null&&secondServer!=null&&!firstServer.equals(secondServer))
                    .put("signaling","Candidate exchange is diagnostic only; no automatic serverless bootstrap proved."));
            long end=System.nanoTime()+seconds*1_000_000_000L,nextStatus=0,nextKeepAlive=System.nanoTime()+20_000_000_000L;
            long punchEnd=0,nextSend=0,seenFileTime=-1;
            InetSocketAddress peer=null;
            Set<String> issued=new HashSet<>(),acked=new HashSet<>(),received=new HashSet<>();
            ArrayDeque<byte[]> pendingAcks=new ArrayDeque<>();
            SecureRandom random=new SecureRandom();
            while(System.nanoTime()<end&&!stop.exists()) {
                long now=System.nanoTime();
                if(peer==null&&peerFile.isFile()&&peerFile.lastModified()!=seenFileTime) {
                    seenFileTime=peerFile.lastModified();
                    try {
                        peer=readPeer(peerFile,secret);
                        punchEnd=now+30_000_000_000L;
                        emit(new JSONObject().put("event","peer_accepted").put("address",peer.getAddress().getHostAddress()).put("port",peer.getPort())
                                .put("maxSeconds",30).put("maxPacketsPerSecond",10));
                    }catch(Exception rejected){emit(new JSONObject().put("event","peer_rejected").put("reason","Invalid numeric endpoint or test credential"));}
                }
                if(peer!=null&&now<punchEnd&&sent<300&&now>=nextSend) {
                    byte[] nonce=pendingAcks.poll();int type=2;
                    if(nonce==null){nonce=new byte[16];random.nextBytes(nonce);issued.add(hex(nonce));type=1;}
                    byte[] packet=packet(type,nonce,secret);
                    socket.send(new DatagramPacket(packet,packet.length,peer));sent++;
                    nextSend=now+100_000_000L; // ALL packets, including ACKs, share a 10/s cap.
                }
                DatagramPacket incoming=new DatagramPacket(new byte[65],65);
                try {
                    socket.receive(incoming);
                    if(peer==null||System.nanoTime()>=punchEnd||incoming.getLength()!=64) {invalid++;continue;}
                    byte[] data=incoming.getData();
                    if(!valid(data,secret)){invalid++;continue;}
                    if(!peer.equals(incoming.getSocketAddress())) {
                        unexpected++;
                        if(!unexpectedReported) {
                            unexpectedReported=true;
                            emit(new JSONObject().put("event","authenticated_unexpected_source")
                                    .put("address",incoming.getAddress().getHostAddress()).put("port",incoming.getPort())
                                    .put("policy","Counted only; no packets sent to this unconfigured endpoint"));
                        }
                        continue;
                    }
                    byte[] nonce=Arrays.copyOfRange(data,16,32);String token=hex(nonce);
                    if(data[8]==1) {
                        if(received.size()<300&&received.add(token)) {
                            requestsReceived++;
                            if(pendingAcks.size()<32)pendingAcks.add(nonce);
                        }
                    } else if(issued.contains(token)&&acked.add(token))matchingAcks++;
                }catch(SocketTimeoutException idle){/* Keep socket and mapping alive while signaling arrives. */}
                if(peer==null&&firstServer!=null&&System.nanoTime()>=nextKeepAlive) {
                    try{InetSocketAddress mapped=StunClient.binding(socket,firstServer,2000);
                        emit(new JSONObject().put("event","keepalive_mapping").put("address",mapped.getAddress().getHostAddress()).put("port",mapped.getPort()));
                    }catch(IOException ignored){}
                    nextKeepAlive=System.nanoTime()+20_000_000_000L;
                }
                if(System.nanoTime()>=nextStatus) {
                    emit(counts("progress",requestsReceived,matchingAcks,sent,invalid,unexpected));
                    nextStatus=System.nanoTime()+2_000_000_000L;
                }
            }
            emit(counts("finished",requestsReceived,matchingAcks,sent,invalid,unexpected));
        } finally {
            Arrays.fill(secret,(byte)0);
            if(peerFile.exists())peerFile.delete();if(stop.exists())stop.delete();
        }
        // A failed NAT experiment is diagnostic data, not a fabricated connectivity pass.
        // Root evaluates verifiedBidirectional and the individual authenticated counts.
    }

    private static Network findNetwork(ConnectivityManager manager,int transport)throws IOException {
        for(Network network:manager.getAllNetworks()) {
            NetworkCapabilities caps=manager.getNetworkCapabilities(network);
            if(caps!=null&&!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)&&caps.hasTransport(transport)
                    &&caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))return network;
        }
        throw new IOException("Requested physical network unavailable");
    }
    private static InetSocketAddress resolveServer(PhysicalNetwork access,String host,int port,String family,InetSocketAddress previous)throws IOException {
        InetSocketAddress fallback=null;
        for(InetAddress address:access.resolve(host)) {
            if(("ipv4".equals(family)&&address instanceof Inet4Address)||("ipv6".equals(family)&&address instanceof Inet6Address)) {
                InetSocketAddress candidate=new InetSocketAddress(address,port);fallback=candidate;
                if(!candidate.equals(previous))return candidate;
            }
        }
        if(fallback!=null)return fallback;
        throw new IOException("STUN DNS has no requested address family");
    }
    private static InetSocketAddress readPeer(File file,byte[] expectedSecret)throws Exception {
        if(file.length()<1||file.length()>1024)throw new IOException("Candidate file bounds");
        byte[] bytes=new byte[1025];int size=0;
        try(FileInputStream in=new FileInputStream(file)){int count;while(size<bytes.length&&(count=in.read(bytes,size,bytes.length-size))>0)size+=count;}
        if(size>1024)throw new IOException("Candidate file bounds");
        JSONObject value=new JSONObject(new String(bytes,0,size,StandardCharsets.UTF_8));
        if(!MessageDigest.isEqual(expectedSecret,secret(value.getString("secret"))))throw new IOException("Test credential mismatch");
        String literal=value.getString("address");InetAddress address;
        if(literal.contains(":")&&literal.matches("[0-9a-fA-F:.]{2,45}"))address=InetAddress.getByName(literal);
        else {
            String[] parts=literal.split("\\.");if(parts.length!=4)throw new IOException("Numeric IP required");
            byte[] ip=new byte[4];for(int i=0;i<4;i++){if(!parts[i].matches("[0-9]{1,3}"))throw new IOException("Numeric IP required");int octet=Integer.parseInt(parts[i]);if(octet>255)throw new IOException("IP range");ip[i]=(byte)octet;}
            address=InetAddress.getByAddress(ip);
        }
        int port=value.getInt("port");
        if(port<1||port>65535||address.isAnyLocalAddress()||address.isMulticastAddress()||address.isLoopbackAddress()
                ||address.isLinkLocalAddress()||"255.255.255.255".equals(address.getHostAddress()))throw new IOException("Invalid explicit candidate");
        return new InetSocketAddress(address,port);
    }
    private static byte[] secret(String text) {
        if(!text.matches("[0-9a-fA-F]{64}"))throw new IllegalArgumentException("32-byte sharedSecret hex required");
        byte[] bytes=new byte[32];for(int i=0;i<bytes.length;i++)bytes[i]=(byte)Integer.parseInt(text.substring(i*2,i*2+2),16);return bytes;
    }
    private static byte[] packet(int type,byte[] nonce,byte[] secret)throws Exception {
        byte[] bytes=ByteBuffer.allocate(64).put(MAGIC).put((byte)type).put(new byte[7]).put(nonce).put(new byte[32]).array();
        System.arraycopy(mac(bytes,secret),0,bytes,32,32);return bytes;
    }
    private static boolean valid(byte[] bytes,byte[] secret)throws Exception {
        if(bytes[8]!=1&&bytes[8]!=2)return false;
        for(int i=0;i<8;i++)if(bytes[i]!=MAGIC[i])return false;
        for(int i=9;i<16;i++)if(bytes[i]!=0)return false;
        return MessageDigest.isEqual(mac(bytes,secret),Arrays.copyOfRange(bytes,32,64));
    }
    private static byte[] mac(byte[] bytes,byte[] secret)throws Exception {
        Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret,"HmacSHA256"));mac.update(bytes,0,32);return mac.doFinal();
    }
    private static String hex(byte[] bytes){StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(Character.forDigit((b>>>4)&15,16)).append(Character.forDigit(b&15,16));return out.toString();}
    private static JSONObject counts(String event,int requests,int acknowledgments,int sent,int invalid,int unexpected)throws Exception {
        return new JSONObject().put("event",event).put("authenticatedRequestsReceived",requests).put("matchingAcksReceived",acknowledgments)
                .put("packetsSent",sent).put("authenticatedUnexpectedSource",unexpected).put("invalidOrUnrelatedPackets",invalid).put("verifiedBidirectional",requests>0&&acknowledgments>0);
    }
    private void emit(JSONObject data){Bundle status=new Bundle();status.putString("nat_probe",data.toString());getInstrumentation().sendStatus(0,status);}
}
