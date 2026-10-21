package org.uzel.core;

import org.junit.Test;
import static org.junit.Assert.*;
import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.net.URL;
import java.security.cert.Certificate;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class UploadMeasurementTest {
    @Test public void setupIsExcludedButServerConfirmedDrainIsIncluded()throws Exception {
        AtomicLong clock=new AtomicLong();List<Fake> opened=new ArrayList<>();
        long rate=UploadMeasurement.upload(url->{Fake c=new Fake(url,200,"");c.clock=clock;c.setupNanos=5_000_000_000L;opened.add(c);return c;},()->false,clock::get);
        assertEquals(1024*1024,rate);
        assertEquals(3,opened.size());assertEquals(262144,opened.get(0).sent);
        assertEquals(3*1024*1024,opened.get(1).sent);assertEquals(3*1024*1024,opened.get(2).sent);
    }
    @Test public void totalDeadlinePreventsFallbackAfterBlockedSetup()throws Exception {
        AtomicLong clock=new AtomicLong();List<Fake> opened=new ArrayList<>();
        try{UploadMeasurement.upload(url->{Fake c=new Fake(url,200,"");c.clock=clock;c.setupNanos=46_000_000_000L;opened.add(c);return c;},()->false,clock::get);fail("deadline");}
        catch(InterruptedIOException expected){}
        assertEquals(1,opened.size());assertTrue(opened.get(0).disconnected);assertEquals(0,opened.get(0).sent);
    }
    @Test public void failedLargeMainConsumesReservedBudgetAndWarmupAloneIsNeverResult()throws Exception {
        List<Fake> opened=new ArrayList<>();
        long speed=UploadMeasurement.upload(url->{Fake c=new Fake(url,opened.size()==1?503:200,"");opened.add(c);return c;},()->false);
        assertTrue(speed>=3);assertEquals(4,opened.size());
        assertEquals(24*1024*1024,opened.stream().mapToInt(c->c.sent).sum());
        assertEquals(16*1024*1024,opened.get(1).sent);
        List<Fake> failed=new ArrayList<>();
        try{UploadMeasurement.upload(url->{Fake c=new Fake(url,failed.isEmpty()?200:503,"");failed.add(c);return c;},()->false);fail("Only warmup succeeded");}
        catch(IOException expected){}
    }
    @Test public void responseRequiresEmptyBodyAndExactNoCacheContract() throws Exception {
        UploadMeasurement.validateResponse(200,"no-store, no-cache, must-revalidate",new ByteArrayInputStream(new byte[0]));
        for(int code:new int[]{204,301,403,413,500})reject(code,"no-cache, no-store","");
        reject(200,null,"");reject(200,"no-store-not-really, no-cache-not-really","");
        reject(200,"no-cache, no-store","<html>OK</html>");
        reject(200,"no-cache, no-store"," ");
    }
    private static void reject(int status,String cache,String body) throws Exception {
        try{UploadMeasurement.validateResponse(status,cache,new ByteArrayInputStream(body.getBytes()));fail("Invalid upload response");}
        catch(IOException expected){}
    }
    @Test public void estimateHasNoHiddenReserve() {
        assertEquals(1048576L,UploadMeasurement.bytesPerSecond(1048576,1_000_000_000L));
        assertEquals(524288L,UploadMeasurement.bytesPerSecond(1048576,2_000_000_000L));
        assertEquals(3L,UploadMeasurement.bytesPerSecond(1,Long.MAX_VALUE));
    }
    @Test public void primaryUsesWarmupAndTwoAdaptiveSamplesWithNormalHttpsDefaults() throws Exception {
        List<Fake> opened=new ArrayList<>();
        long rate=UploadMeasurement.upload(url->{Fake c=new Fake(url,200,"");opened.add(c);return c;},()->false);
        assertTrue(rate>=3);assertEquals(3,opened.size());
        for(Fake c:opened){
            assertTrue(c.getURL().toString().startsWith("https://speedtest.selectel.ru/empty.php?"));
            assertTrue(c.sent>=262144 && c.sent<=16*1024*1024);assertEquals("POST",c.getRequestMethod());
            assertFalse(c.getInstanceFollowRedirects());assertFalse(c.getUseCaches());assertTrue(c.disconnected);
            assertEquals("identity",c.getRequestProperty("Content-Encoding"));
        }
    }
    @Test public void failedAttemptConsumesBudgetAndFallsBackWithinTwentyFourMiB() throws Exception {
        List<Fake> opened=new ArrayList<>();
        long speed=UploadMeasurement.upload(url->{Fake c=new Fake(url,opened.isEmpty()?503:200,"");opened.add(c);return c;},()->false);
        assertTrue(speed>=3);assertEquals(4,opened.size());assertEquals(24*1024*1024,opened.stream().mapToInt(c->c.sent).sum());
        assertTrue(opened.get(1).getURL().toString().startsWith("https://speedtest.59telecom.ru/backend/empty?"));
    }
    @Test public void partialFailedWriteReservesWholeAttemptRatherThanRetryingUnlimitedData() throws Exception {
        List<Fake> opened=new ArrayList<>();
        try{
            UploadMeasurement.upload(url->{Fake c=new Fake(url,200,"");c.failWrite=true;opened.add(c);return c;},()->false);
            fail("All providers failed");
        }catch(IOException expected){}
        assertEquals(2,opened.size());assertTrue(opened.stream().mapToInt(c->c.sent).sum()<=24*1024*1024);
        assertTrue(opened.stream().allMatch(c->c.disconnected));
    }
    @Test public void html200IsNotASuccessAndFallsBack() throws Exception {
        List<Fake> opened=new ArrayList<>();
        UploadMeasurement.upload(url->{Fake c=new Fake(url,200,opened.isEmpty()?"<html>proxy</html>":"");opened.add(c);return c;},()->false);
        assertEquals(4,opened.size());
        assertTrue(opened.get(1).getURL().toString().contains("59telecom"));
    }
    @Test public void connectionFactoryFailureAlsoSwitchesProviderInsideSameBudget() throws Exception {
        List<String> attempted=new ArrayList<>();List<Fake> opened=new ArrayList<>();
        UploadMeasurement.upload(url->{attempted.add(url);if(attempted.size()==1)throw new IOException("No physical route");
            Fake c=new Fake(url,200,"");opened.add(c);return c;},()->false);
        assertEquals(4,attempted.size());assertEquals(3,opened.size());
        assertTrue(attempted.get(1).startsWith("https://speedtest.59telecom.ru/backend/empty?"));
        assertEquals(24*1024*1024-262144,opened.stream().mapToInt(c->c.sent).sum());
    }
    @Test public void cancellationNeverStartsFallback() throws Exception {
        AtomicBoolean cancelled=new AtomicBoolean();List<Fake> opened=new ArrayList<>();
        try{
            UploadMeasurement.upload(url->{Fake c=new Fake(url,200,"");opened.add(c);cancelled.set(true);return c;},cancelled::get);
            fail("Cancelled");
        }catch(InterruptedIOException expected){}
        assertEquals(1,opened.size());assertTrue(opened.get(0).disconnected);assertEquals(0,opened.get(0).sent);
    }
    private static final class Fake extends HttpsURLConnection {
        final int status;final byte[] body;int sent;boolean failWrite,disconnected;
        AtomicLong clock;long setupNanos;
        Fake(String url,int status,String body)throws IOException{super(new URL(url));this.status=status;this.body=body.getBytes();}
        @Override public OutputStream getOutputStream(){if(clock!=null)clock.addAndGet(setupNanos);return new OutputStream(){
            public void write(int value)throws IOException{sent++;if(failWrite)throw new IOException("Write failed");}
            public void write(byte[] b,int off,int n)throws IOException{sent+=n;if(failWrite)throw new IOException("Write failed");}
        };}
        @Override public int getResponseCode(){if(clock!=null)clock.addAndGet(sent*1_000_000_000L/(1024*1024));return status;}
        @Override public String getHeaderField(String key){return "Cache-Control".equalsIgnoreCase(key)?"post-check=0, pre-check=0":null;}
        @Override public Map<String,List<String>> getHeaderFields(){
            return Collections.singletonMap("Cache-Control",Arrays.asList("post-check=0, pre-check=0","no-store, no-cache, must-revalidate"));
        }
        @Override public InputStream getInputStream(){return new ByteArrayInputStream(body);}
        @Override public String getCipherSuite(){return "TLS_FAKE";}
        @Override public Certificate[] getLocalCertificates(){return new Certificate[0];}
        @Override public Certificate[] getServerCertificates(){return new Certificate[0];}
        @Override public void connect(){}
        @Override public void disconnect(){disconnected=true;}
        @Override public boolean usingProxy(){return false;}
    }
}
