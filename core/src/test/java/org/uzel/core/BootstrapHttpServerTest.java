package org.uzel.core;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

public class BootstrapHttpServerTest {
    @Test public void deadlineRunsIndependentlyOfBusyWorkersAndCoversQueue()throws Exception {
        java.util.concurrent.CountDownLatch entered=new java.util.concurrent.CountDownLatch(4),release=new java.util.concurrent.CountDownLatch(1);
        java.util.List<Socket> clients=new java.util.ArrayList<>();
        try(BootstrapHttpServer server=new BootstrapHttpServer(InetAddress.getLoopbackAddress(),0,body->{
            entered.countDown();try{release.await(3,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException e){throw new InterruptedIOException();}return body;
        },400)){
            for(int i=0;i<4;i++){Socket client=new Socket(InetAddress.getLoopbackAddress(),server.port());clients.add(client);
                client.getOutputStream().write("POST /v1/exchange HTTP/1.1\r\nHost: localhost\r\nContent-Length: 1\r\n\r\nx".getBytes(StandardCharsets.US_ASCII));}
            assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS));
            Socket queued=new Socket(InetAddress.getLoopbackAddress(),server.port());clients.add(queued);queued.setSoTimeout(1500);
            queued.getOutputStream().write("GET /health HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            try{assertEquals(-1,queued.getInputStream().read());}catch(SocketException reset){/* queued socket expired */}
            for(int i=0;i<4;i++){clients.get(i).setSoTimeout(1000);try{assertEquals(-1,clients.get(i).getInputStream().read());}catch(SocketException reset){}}
        }finally{release.countDown();for(Socket socket:clients)socket.close();}
    }
    @Test public void absoluteDeadlineClosesSlowTrickleHeadersAndBodies()throws Exception {
        for(boolean body:new boolean[]{false,true}){
            AtomicInteger calls=new AtomicInteger();
            try(BootstrapHttpServer server=new BootstrapHttpServer(InetAddress.getLoopbackAddress(),0,request->{calls.incrementAndGet();return request;},300);
                Socket socket=new Socket(InetAddress.getLoopbackAddress(),server.port())){
                socket.setSoTimeout(1500);long start=System.nanoTime();
                if(body)socket.getOutputStream().write("POST /v1/exchange HTTP/1.1\r\nHost: localhost\r\nContent-Length: 1000\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                Thread writer=new Thread(()->{try{for(int i=0;i<30;i++){socket.getOutputStream().write('x');socket.getOutputStream().flush();Thread.sleep(40);}}catch(IOException|InterruptedException stopped){}});
                writer.start();
                try{assertEquals(-1,socket.getInputStream().read());}catch(SocketException reset){/* deadline reset */}
                finally{writer.interrupt();writer.join(1000);}
                assertTrue("Absolute deadline, despite continuous bytes",System.nanoTime()-start<1_200_000_000L);assertEquals(0,calls.get());
                assertTrue(request(server,"GET /health HTTP/1.1\r\nHost: localhost\r\n\r\n").endsWith("ok\n"));
            }
        }
    }
    private String request(BootstrapHttpServer server,String text)throws Exception {
        try(Socket socket=new Socket(InetAddress.getLoopbackAddress(),server.port())){
            socket.setSoTimeout(3000);
            socket.getOutputStream().write(text.getBytes(StandardCharsets.US_ASCII));
            return new String(socket.getInputStream().readAllBytes(),StandardCharsets.US_ASCII);
        }
    }
    @Test public void exchangesOnlyExactBoundedRouteAndNeverLogsPayload()throws Exception {
        AtomicInteger calls=new AtomicInteger();
        try(BootstrapHttpServer server=new BootstrapHttpServer(InetAddress.getLoopbackAddress(),0,body->{calls.incrementAndGet();return body;})){
            String response=request(server,"POST /v1/exchange HTTP/1.1\r\nHost: localhost\r\nContent-Length: 3\r\n\r\nabc");
            assertTrue(response.startsWith("HTTP/1.1 200"));assertTrue(response.endsWith("abc"));
            assertTrue(request(server,"GET /health HTTP/1.1\r\nHost: localhost\r\n\r\n").endsWith("ok\n"));
            assertTrue(request(server,"POST /other HTTP/1.1\r\nHost: localhost\r\nContent-Length: 3\r\n\r\nabc").startsWith("HTTP/1.1 404"));
            assertEquals(1,calls.get());
        }
    }
    @Test public void rejectsOversizeAmbiguousAndTransferEncodedRequestsBeforeHandler()throws Exception {
        AtomicInteger calls=new AtomicInteger();
        try(BootstrapHttpServer server=new BootstrapHttpServer(InetAddress.getLoopbackAddress(),0,body->{calls.incrementAndGet();return body;})){
            assertTrue(request(server,"POST /v1/exchange HTTP/1.1\r\nHost: localhost\r\nContent-Length: 131073\r\n\r\n").startsWith("HTTP/1.1 413"));
            assertTrue(request(server,"POST /v1/exchange HTTP/1.1\r\nHost: localhost\r\nContent-Length: 1\r\nContent-Length: 2\r\n\r\na").startsWith("HTTP/1.1 400"));
            assertTrue(request(server,"POST /v1/exchange HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n").startsWith("HTTP/1.1 400"));
            assertTrue(request(server,"POST /v1/exchange HTTP/1.1\r\nHost: localhost\r\n\r\n").startsWith("HTTP/1.1 411"));
            assertEquals(0,calls.get());
        }
    }
    @Test public void rejectsBadFramesAndBoundsHandlerOutput()throws Exception {
        try(BootstrapHttpServer server=new BootstrapHttpServer(InetAddress.getLoopbackAddress(),0,body->{throw new IOException("private detail");})){
            String result=request(server,"POST /v1/exchange HTTP/1.1\r\nHost: localhost\r\nContent-Length: 1\r\n\r\na");
            assertTrue(result.startsWith("HTTP/1.1 400"));assertFalse(result.contains("private detail"));
        }
        try(BootstrapHttpServer server=new BootstrapHttpServer(InetAddress.getLoopbackAddress(),0,body->new byte[131073])){
            assertTrue(request(server,"POST /v1/exchange HTTP/1.1\r\nHost: localhost\r\nContent-Length: 1\r\n\r\na").startsWith("HTTP/1.1 500"));
        }
    }
    @Test public void closeReleasesListenerAndPendingHeaderReaders()throws Exception {
        BootstrapHttpServer server=new BootstrapHttpServer(InetAddress.getLoopbackAddress(),0,body->body);
        int port=server.port();
        try(Socket socket=new Socket(InetAddress.getLoopbackAddress(),port)){
            socket.setSoTimeout(2000);socket.getOutputStream().write('P');
            server.close();server.close();
            try{assertEquals(-1,socket.getInputStream().read());}catch(SocketException reset){/* closed during accept */}
        }
        try(ServerSocket rebound=new ServerSocket(port,1,InetAddress.getLoopbackAddress())){assertEquals(port,rebound.getLocalPort());}
    }
}
