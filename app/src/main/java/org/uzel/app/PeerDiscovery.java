package org.uzel.app;

import android.content.Context;
import android.net.nsd.*;
import java.nio.charset.StandardCharsets;
import java.util.function.BiConsumer;
import java.util.concurrent.atomic.AtomicBoolean;

/** DNS-SD hints never authorize a peer: only an imported invitation can do that. */
final class PeerDiscovery implements AutoCloseable {
    private final NsdManager manager;
    private NsdManager.RegistrationListener registration;
    private NsdManager.DiscoveryListener discovery;
    private final AtomicBoolean resolving=new AtomicBoolean();
    PeerDiscovery(Context context){manager=(NsdManager)context.getSystemService(Context.NSD_SERVICE);}
    void start(String fingerprint,int port,BiConsumer<String,String> found) {
        NsdServiceInfo info=new NsdServiceInfo();info.setServiceName("Uzel-"+fingerprint.substring(0,8));
        info.setServiceType("_uzel._tcp.");info.setPort(port);info.setAttribute("key",fingerprint);
        registration=new NsdManager.RegistrationListener(){
            public void onServiceRegistered(NsdServiceInfo s){}
            public void onRegistrationFailed(NsdServiceInfo s,int code){}
            public void onServiceUnregistered(NsdServiceInfo s){}
            public void onUnregistrationFailed(NsdServiceInfo s,int code){}
        };
        manager.registerService(info,NsdManager.PROTOCOL_DNS_SD,registration);
        discovery=new NsdManager.DiscoveryListener(){
            public void onDiscoveryStarted(String type){}
            public void onDiscoveryStopped(String type){}
            public void onStartDiscoveryFailed(String type,int error){}
            public void onStopDiscoveryFailed(String type,int error){}
            public void onServiceLost(NsdServiceInfo s){}
            public void onServiceFound(NsdServiceInfo s){
                if(!resolving.compareAndSet(false,true))return;
                manager.resolveService(s,new NsdManager.ResolveListener(){
                    public void onResolveFailed(NsdServiceInfo value,int error){resolving.set(false);}
                    public void onServiceResolved(NsdServiceInfo value){
                        resolving.set(false);
                        byte[] key=value.getAttributes().get("key");
                        if(key!=null && value.getHost()!=null && value.getPort()==39281) {
                            String pin=new String(key,StandardCharsets.US_ASCII);
                            if(pin.matches("[0-9a-f]{64}") && !pin.equals(fingerprint))
                                found.accept(pin,value.getHost().getHostAddress());
                        }
                    }
                });
            }
        };
        manager.discoverServices("_uzel._tcp.",NsdManager.PROTOCOL_DNS_SD,discovery);
    }
    @Override public void close(){
        if(discovery!=null)try{manager.stopServiceDiscovery(discovery);}catch(IllegalArgumentException ignored){}
        if(registration!=null)try{manager.unregisterService(registration);}catch(IllegalArgumentException ignored){}
        discovery=null;registration=null;
    }
}
