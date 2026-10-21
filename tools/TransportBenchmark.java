package org.uzel.core;

import java.net.*;
import java.io.*;
import java.util.Arrays;

/** Local direct-path comparison. Compile separately against the retained 0.1 test classes. */
public final class TransportBenchmark {
    public static void main(String[] args) throws Exception {
        NetworkTest.identities();
        byte[] payload=new byte[131072];Arrays.fill(payload,(byte)37);
        try(Node server=new Node(NetworkTest.keys.get(0),new NetworkAccess(),a->true);
            Node client=new Node(NetworkTest.keys.get(1),new NetworkAccess(),a->true);
            ServerSocket echo=new ServerSocket(0,8,InetAddress.getLoopbackAddress())){
            server.start(0);client.start(0);server.setMeasuredUpload(3_000_000);client.setMeasuredUpload(3_000_000);
            Thread worker=new Thread(()->{
                try{for(int i=0;i<6;i++)try(Socket s=echo.accept()){
                    byte[] bytes=s.getInputStream().readNBytes(payload.length);
                    s.getOutputStream().write(bytes);s.getOutputStream().flush();
                }}catch(IOException e){throw new RuntimeException(e);}
            });worker.setDaemon(true);worker.start();
            try(Node.Session session=client.connect(server.invitation("127.0.0.1"),()->{})){
                long elapsed=0;
                for(int i=0;i<6;i++){
                    long start=System.nanoTime();
                    try(Socket s=session.openTcp("127.0.0.1",echo.getLocalPort())){
                        s.getOutputStream().write(payload);s.getOutputStream().flush();
                        if(!Arrays.equals(payload,s.getInputStream().readNBytes(payload.length)))throw new AssertionError("Echo mismatch");
                    }
                    long duration=System.nanoTime()-start;if(i>0)elapsed+=duration;
                }
                System.out.printf("five_transfers_ms=%.1f aggregate_payload_bytes_per_second=%.0f threads=%d%n",
                        elapsed/1e6,5d*2*payload.length/(elapsed/1e9),Thread.activeCount());
            }
        }
    }
}
