package org.uzel.core;

import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.net.HttpURLConnection;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

/** Bounded physical-uplink calibration against explicitly published LibreSpeed upload handlers. */
public final class UploadMeasurement {
    private static final String[] ENDPOINTS={
            "https://speedtest.selectel.ru/empty.php",
            "https://speedtest.59telecom.ru/backend/empty"
    };
    private static final int SAMPLE_BYTES=1024*1024,MAX_ATTEMPTED_BYTES=24*SAMPLE_BYTES;
    private static final long ATTEMPT_NANOS=20_000_000_000L,TOTAL_NANOS=45_000_000_000L;
    @FunctionalInterface public interface Connections {HttpURLConnection open(String url)throws IOException;}
    private UploadMeasurement(){}

    public static long upload(Connections connections,BooleanSupplier cancelled)throws IOException {
        return upload(connections,cancelled,System::nanoTime);
    }
    static long upload(Connections connections,BooleanSupplier cancelled,java.util.function.LongSupplier clock)throws IOException {
        final long started=clock.getAsLong();
        byte[] bytes=new byte[16384];SecureRandom random=new SecureRandom();random.nextBytes(bytes);
        AtomicReference<Attempt> active=new AtomicReference<>();
        ScheduledExecutorService watchdog=Executors.newSingleThreadScheduledExecutor(r->{
            Thread thread=new Thread(r,"uzel-upload-deadline");thread.setDaemon(true);return thread;
        });
        watchdog.scheduleAtFixedRate(()->{
            Attempt attempt=active.get();
            if(attempt!=null && (cancelled.getAsBoolean() || clock.getAsLong()-started>=TOTAL_NANOS
                    || clock.getAsLong()-attempt.started>=ATTEMPT_NANOS))attempt.abort();
        },100,100,TimeUnit.MILLISECONDS);
        int budget=MAX_ATTEMPTED_BYTES;long minimum=Long.MAX_VALUE;IOException failure=null;
        try{
            for(String endpoint:ENDPOINTS){
                long warmRate=0;
                for(int sample=0;sample<3 && budget>=262144;sample++){
                    int sampleBytes=sample==0?262144:(int)Math.min(budget,Math.max(262144,Math.min(16L*SAMPLE_BYTES,warmRate*3)));
                    if(sampleBytes>budget)break;
                    checkCancelled(cancelled,started,clock);
                    // Reserve the entire request before opening it, including failed/partial writes.
                    budget-=sampleBytes;
                    HttpURLConnection connection=null;Attempt attempt=null;
                    try{
                        connection=connections.open(endpoint+"?r="+Long.toHexString(random.nextLong()));
                        attempt=new Attempt(connection,clock.getAsLong());active.set(attempt);
                        if(!(connection instanceof HttpsURLConnection) || !"https".equals(connection.getURL().getProtocol()))
                            throw new IOException("HTTPS upload endpoint required");
                        connection.setConnectTimeout(8000);connection.setReadTimeout(8000);
                        connection.setInstanceFollowRedirects(false);connection.setUseCaches(false);
                        connection.setRequestMethod("POST");connection.setDoOutput(true);
                        connection.setFixedLengthStreamingMode(sampleBytes);
                        connection.setRequestProperty("Content-Type","application/octet-stream");
                        connection.setRequestProperty("Content-Encoding","identity");
                        connection.setRequestProperty("Accept-Encoding","identity");
                        connection.setRequestProperty("Cache-Control","no-store");
                        checkAttempt(attempt,cancelled,started,clock);
                        long transferStarted;
                        try(OutputStream out=connection.getOutputStream()){
                            transferStarted=clock.getAsLong();
                            for(int remaining=sampleBytes;remaining>0;remaining-=bytes.length){
                                checkAttempt(attempt,cancelled,started,clock);
                                out.write(bytes,0,Math.min(bytes.length,remaining));
                            }
                        }
                        checkAttempt(attempt,cancelled,started,clock);
                        int status=connection.getResponseCode();
                        if(status!=200)throw new IOException("Upload endpoint returned HTTP "+status);
                        try(InputStream response=connection.getInputStream()){
                            validateResponse(status,cacheControl(connection),response);
                        }
                        checkAttempt(attempt,cancelled,started,clock);
                        long rate=bytesPerSecond(sampleBytes,clock.getAsLong()-transferStarted);
                        warmRate=rate;if(sample>0)minimum=Math.min(minimum,rate);
                    }catch(IOException e){
                        checkCancelled(cancelled,started,clock);failure=e;break; // switch provider, never replay redirects
                    }finally{active.compareAndSet(attempt,null);if(connection!=null)connection.disconnect();}
                }
                if(minimum!=Long.MAX_VALUE && failure==null)return minimum;
            }
            checkCancelled(cancelled,started,clock);
            if(minimum!=Long.MAX_VALUE)return minimum;
            throw new IOException("Upload calibration unavailable from supported providers",failure);
        }finally{watchdog.shutdownNow();}
    }
    static void validateResponse(int status,String cacheControl,InputStream body)throws IOException {
        boolean noStore=false,noCache=false;
        if(cacheControl!=null)for(String directive:cacheControl.toLowerCase(Locale.ROOT).split(",")){
            String name=directive.trim();if(name.equals("no-store"))noStore=true;if(name.equals("no-cache"))noCache=true;
        }
        // LibreSpeed empty handlers deliberately return no content; HTML/login/proxy responses fail.
        int firstByte=body.read();
        if(status!=200 || !noStore || !noCache || firstByte!=-1){
            String diagnostic=cacheControl==null?"<missing>":cacheControl.replace('\r',' ').replace('\n',' ');
            if(diagnostic.length()>160)diagnostic=diagnostic.substring(0,160);
            throw new IOException("Invalid LibreSpeed upload response: HTTP="+status+", Cache-Control="+diagnostic+", firstByte="+firstByte);
        }
    }
    private static String cacheControl(HttpURLConnection connection){
        StringBuilder all=new StringBuilder();
        // Android's getHeaderField(name) returns only one occurrence. LibreSpeed commonly sends two.
        for(Map.Entry<String,List<String>> field:connection.getHeaderFields().entrySet()){
            if(field.getKey()==null || !field.getKey().equalsIgnoreCase("Cache-Control"))continue;
            for(String value:field.getValue())if(value!=null){if(all.length()>0)all.append(',');all.append(value);}
        }
        return all.toString();
    }
    static long bytesPerSecond(int bytes,long elapsedNanos){
        return Math.max(3,(long)(bytes*1_000_000_000.0/Math.max(1,elapsedNanos)));
    }
    private static void checkCancelled(BooleanSupplier cancelled,long started,java.util.function.LongSupplier clock)throws InterruptedIOException {
        if(cancelled.getAsBoolean() || Thread.currentThread().isInterrupted() || clock.getAsLong()-started>=TOTAL_NANOS)
            throw new InterruptedIOException("Upload calibration cancelled or timed out");
    }
    private static void checkAttempt(Attempt attempt,BooleanSupplier cancelled,long started,java.util.function.LongSupplier clock)throws IOException {
        checkCancelled(cancelled,started,clock);
        if(attempt.aborted.get() || clock.getAsLong()-attempt.started>=ATTEMPT_NANOS)throw new IOException("Upload attempt timed out");
    }
    private static final class Attempt {
        final HttpURLConnection connection;final long started;final AtomicBoolean aborted=new AtomicBoolean();
        Attempt(HttpURLConnection connection,long started){this.connection=connection;this.started=started;}
        void abort(){if(aborted.compareAndSet(false,true))connection.disconnect();}
    }
}
