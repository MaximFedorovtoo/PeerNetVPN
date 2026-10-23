package org.uzel.desktop;

import org.uzel.core.*;
import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.*;

/** HTTPS transport for the SAME signed discovery frames used by Android. A presence never authorizes a route. */
final class DesktopDiscoveryClient implements AutoCloseable {
    @FunctionalInterface interface Connections {HttpURLConnection open(URL url)throws IOException;}
    @FunctionalInterface interface Publisher {DiscoveryPresence current()throws IOException;}
    private final String fingerprint,endpoint;
    private final Connections connections;
    private final long deadlineMillis;
    private final DiscoveryStore cache=new DiscoveryStore();
    private final Map<String,DiscoverySignal> outgoing=new LinkedHashMap<>();
    private Runnable signalListener;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(task->{Thread thread=new Thread(task,"uzelio-bootstrap-discovery");thread.setDaemon(true);return thread;});
    private final ScheduledExecutorService watchdog=Executors.newSingleThreadScheduledExecutor(task->{Thread thread=new Thread(task,"uzelio-bootstrap-deadline");thread.setDaemon(true);return thread;});
    private Publisher publisher;
    private CompletableFuture<Boolean> pending;
    private HttpURLConnection activeRequest;
    private long generation,lastAttempt;
    private boolean closed,paused;
    private String state="NOT_STARTED",detail="";

    static DesktopDiscoveryClient configured(Path root,String fingerprint){
        try{
            Properties settings=new Properties();Path file=root.resolve("discovery.properties");
            if(Files.exists(file)){if(Files.size(file)>8192)throw new IOException("Discovery settings size");try(InputStream input=Files.newInputStream(file)){settings.load(input);}}
            String endpoint=settings.getProperty("bootstrapUrl");if(endpoint==null)endpoint=defaultEndpoint();
            return new DesktopDiscoveryClient(fingerprint,endpoint,url->(HttpURLConnection)url.openConnection(Proxy.NO_PROXY),()->null,12_000);
        }catch(IOException|RuntimeException invalid){
            DesktopDiscoveryClient disabled=disabled(fingerprint);disabled.detail="Не удалось прочитать настройки каталога объявлений";return disabled;
        }
    }
    static DesktopDiscoveryClient disabled(String fingerprint){return new DesktopDiscoveryClient(fingerprint,"",url->{throw new IOException("Not configured");},()->null,12_000);}
    static String defaultEndpoint()throws IOException {
        try(InputStream input=DesktopDiscoveryClient.class.getResourceAsStream("/bootstrap_config.xml")){
            if(input==null)throw new IOException("Missing shared discovery configuration");byte[] bytes=input.readNBytes(8193);if(bytes.length>8192)throw new IOException("Discovery configuration size");
            Matcher found=Pattern.compile("<string\\s+name=[\"']bootstrap_default_url[\"'][^>]*>([^<]+)</string>").matcher(new String(bytes,StandardCharsets.UTF_8));
            if(!found.find())throw new IOException("Missing discovery endpoint");return validateEndpoint(found.group(1));
        }
    }
    static String validateEndpoint(String value){
        if(value==null)throw new IllegalArgumentException("Discovery endpoint");value=value.trim();if(value.isEmpty())return "";
        try{
            URI uri=new URI(value);String path=uri.getPath();
            if(value.length()>2048 || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null || uri.getPort()==0 || uri.getPort()>65535)
                throw new IllegalArgumentException("HTTPS discovery endpoint required");
            if(path==null || path.isEmpty() || path.equals("/"))return value.replaceAll("/+$","")+"/v1/exchange";
            if(!path.equals("/v1/exchange"))throw new IllegalArgumentException("Discovery exchange path required");return value;
        }catch(URISyntaxException invalid){throw new IllegalArgumentException("Discovery endpoint",invalid);}
    }
    DesktopDiscoveryClient(String fingerprint,String endpoint,Connections connections,Publisher publisher,long deadlineMillis){
        this.fingerprint=Objects.requireNonNull(fingerprint);this.endpoint=validateEndpoint(endpoint);this.connections=Objects.requireNonNull(connections);this.publisher=Objects.requireNonNull(publisher);
        if(deadlineMillis<1 || deadlineMillis>20_000)throw new IllegalArgumentException("Discovery deadline");this.deadlineMillis=deadlineMillis;
        if(this.endpoint.isEmpty()){state="NOT_CONFIGURED";detail="Каталог объявлений не задан";}
    }
    synchronized void setPublisher(Publisher publisher){this.publisher=Objects.requireNonNull(publisher);}
    synchronized void setSignalListener(Runnable listener){signalListener=listener;}
    synchronized void clearSignalListener(Runnable listener){if(signalListener==listener)signalListener=null;}
    DiscoveryPresence presence(String target){return presences().stream().filter(p->p.fingerprint.equals(target)).findFirst().orElse(null);}
    List<DiscoverySignal> signals(){return cache.signalsFor(fingerprint,System.currentTimeMillis());}
    void publishSignal(DiscoverySignal signal)throws IOException {
        long now=System.currentTimeMillis();DiscoverySignal checked=DiscoverySignal.parse(signal.encode(),now);
        synchronized(this){
            if(closed || paused || !checked.senderFingerprint.equals(fingerprint))throw new IOException("Signaling unavailable");
            outgoing.values().removeIf(value->value.expiresAt<=now);if(outgoing.size()>=8)throw new IOException("Signaling capacity");outgoing.put(checked.id,checked);
        }
        CompletableFuture<Boolean> exchange=refresh();exchange.whenComplete((result,failure)->refresh());
    }
    synchronized void cancelSignals(Collection<String> ids){for(String id:ids)outgoing.remove(id);}
    synchronized CompletableFuture<Boolean> refresh(){
        if(closed || paused || endpoint.isEmpty())return CompletableFuture.completedFuture(false);
        if(pending!=null && !pending.isDone())return pending;
        long epoch=generation;lastAttempt=System.nanoTime();state="SEARCHING";detail="";
        pending=CompletableFuture.supplyAsync(()->exchange(epoch),worker);return pending;
    }
    synchronized void refreshIfDue(){if(!closed && !paused && (lastAttempt==0 || System.nanoTime()-lastAttempt>=TimeUnit.SECONDS.toNanos(5)))refresh();}
    synchronized DesktopBackend.DiscoveryStatus status(){return new DesktopBackend.DiscoveryStatus(state,detail,presences().size());}
    List<DiscoveryPresence> presences(){return cache.presences(System.currentTimeMillis()).stream().filter(p->!p.fingerprint.equals(fingerprint)).toList();}
    private synchronized boolean current(long epoch){return !closed && !paused && generation==epoch && !Thread.currentThread().isInterrupted();}
    private boolean exchange(long epoch){
        HttpURLConnection connection=null;ScheduledFuture<?> deadline=null;AtomicBoolean timedOut=new AtomicBoolean();long started=System.nanoTime();
        try{
            if(!current(epoch))throw new InterruptedIOException("Discovery cancelled");
            Publisher currentPublisher;synchronized(this){currentPublisher=publisher;}
            DiscoveryStore upload=new DiscoveryStore();DiscoveryPresence own=currentPublisher.current();
            if(own!=null){if(!fingerprint.equals(own.fingerprint))throw new IOException("Discovery publication identity");upload.accept(own,System.currentTimeMillis());}
            synchronized(this){long now=System.currentTimeMillis();outgoing.values().removeIf(signal->signal.expiresAt<=now);for(DiscoverySignal signal:outgoing.values())upload.accept(signal,now);}
            byte[] request=upload.exportFrame(System.currentTimeMillis());
            connection=connections.open(new URL(endpoint));if(!(connection instanceof HttpsURLConnection) || !"https".equalsIgnoreCase(connection.getURL().getProtocol()))throw new IOException("HTTPS required");
            synchronized(this){if(!current(epoch))throw new InterruptedIOException("Discovery cancelled");activeRequest=connection;}
            HttpURLConnection active=connection;deadline=watchdog.schedule(()->{timedOut.set(true);active.disconnect();},deadlineMillis,TimeUnit.MILLISECONDS);
            connection.setConnectTimeout((int)Math.min(5000,deadlineMillis));connection.setReadTimeout((int)Math.min(5000,deadlineMillis));connection.setInstanceFollowRedirects(false);connection.setUseCaches(false);
            connection.setRequestMethod("POST");connection.setDoOutput(true);connection.setRequestProperty("Content-Type","application/octet-stream");connection.setFixedLengthStreamingMode(request.length);
            try(OutputStream output=connection.getOutputStream()){output.write(request);}
            if(connection.getResponseCode()!=200 || connection.getContentLengthLong()>DiscoveryStore.MAX_FRAME)throw new IOException("Discovery response unavailable");
            byte[] response;
            try(InputStream input=connection.getInputStream();ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
                byte[] chunk=new byte[4096];int count;
                while((count=input.read(chunk))!=-1){
                    if(!current(epoch) || timedOut.get() || System.nanoTime()-started>=TimeUnit.MILLISECONDS.toNanos(deadlineMillis))throw new InterruptedIOException("Discovery deadline");
                    if(bytes.size()+count>DiscoveryStore.MAX_FRAME)throw new IOException("Discovery frame too large");bytes.write(chunk,0,count);
                }
                response=bytes.toByteArray();
            }
            Runnable notified;
            synchronized(this){
                if(!current(epoch) || timedOut.get() || System.nanoTime()-started>=TimeUnit.MILLISECONDS.toNanos(deadlineMillis))throw new InterruptedIOException("Discovery cancelled");
                cache.ingestFrame(response,System.currentTimeMillis());state="ONLINE";detail="";
                notified=signalListener;
            }
            if(notified!=null)notified.run();
            return true;
        }catch(IOException|RuntimeException failure){
            synchronized(this){if(current(epoch)){state="UNAVAILABLE";detail="Не удалось обновить каталог объявлений; свежие сохранённые объявления ещё могут отображаться";}}
            return false;
        }finally{if(deadline!=null)deadline.cancel(false);synchronized(this){if(activeRequest==connection)activeRequest=null;}if(connection!=null)connection.disconnect();}
    }
    synchronized void resume(){if(!closed)paused=false;}
    void pause(){HttpURLConnection active;synchronized(this){++generation;paused=true;active=activeRequest;if(!closed && !endpoint.isEmpty()){state="NOT_STARTED";detail="";}}if(active!=null)active.disconnect();}
    @Override public void close(){synchronized(this){closed=true;outgoing.clear();signalListener=null;}pause();worker.shutdownNow();watchdog.shutdownNow();cache.clear();}
}
