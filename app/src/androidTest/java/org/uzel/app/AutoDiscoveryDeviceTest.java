package org.uzel.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import android.test.InstrumentationTestRunner;
import java.io.File;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONObject;

/** Explicit two-device test. Only HTTPS discovery transports signaling; no code files or pins. */
@SuppressWarnings("deprecation")
public final class AutoDiscoveryDeviceTest extends InstrumentationTestCase {
    public void testConfigure()throws Exception {
        Bundle args=((InstrumentationTestRunner)getInstrumentation()).getArguments();
        if(!"configure".equals(args.getString("action","")))return;
        String bootstrap=AutoDiscoveryManager.validateUrl(args.getString("bootstrap",""));
        if(bootstrap.isEmpty())throw new IllegalArgumentException("Explicit HTTPS bootstrap required");
        NodeController controller=NodeController.get(getInstrumentation().getTargetContext());
        NodeController.Snapshot before=controller.snapshot();
        assertFalse("Controller must not be sharing during configuration",before.sharing);
        assertFalse("Controller must be idle during configuration",before.busy);
        assertFalse("Controller must not have an active VPN during configuration",before.vpnConnected);
        controller.setBootstrapUrl(bootstrap);
        assertEquals("Normalized bootstrap URL must be applied",bootstrap,controller.snapshot().discoveryURL);
    }

    public void testHold()throws Exception {
        Bundle args=((InstrumentationTestRunner)getInstrumentation()).getArguments();
        if(!"hold".equals(args.getString("action","")))return;
        String clientValue=args.getString("client","");
        if(!"true".equals(clientValue)&&!"false".equals(clientValue))throw new IllegalArgumentException("Explicit client true/false required");
        boolean client=Boolean.parseBoolean(clientValue);
        int seconds=Integer.parseInt(args.getString("holdSeconds","480"));
        if(seconds<60||seconds>900)throw new IllegalArgumentException("holdSeconds 60..900");
        String bootstrap=AutoDiscoveryManager.validateUrl(args.getString("bootstrap",""));
        if(bootstrap.isEmpty())throw new IllegalArgumentException("Explicit HTTPS bootstrap required");
        Context context=getInstrumentation().getTargetContext();
        NodeController controller=NodeController.get(context);
        NodeController.Snapshot originalState=controller.snapshot();
        assertFalse("Stop existing sharing before this explicit test",originalState.sharing);
        assertFalse("Controller must be idle",originalState.busy);
        String originalUrl=originalState.discoveryURL;
        Set<String> originalSelection=new HashSet<>(AppSelection.load(context));
        File stop=new File(context.getFilesDir(),"auto-vpn-stop");
        File bootstrapOff=new File(context.getFilesDir(),"auto-bootstrap-off");
        Files.deleteIfExists(stop.toPath());Files.deleteIfExists(bootstrapOff.toPath());
        Activity activity=null;
        boolean connected=false,bootstrapDisabled=false,connectedWithoutBootstrap=false;
        try {
            activity=getInstrumentation().startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            if(client){
                Set<String> selected=new HashSet<>();selected.add("org.uzel.probe.vpn");AppSelection.save(context,selected);
                assertEquals("Install probe APK first",selected,new HashSet<>(AppSelection.validPackages(context)));
                assertNull("User must grant Android VPN permission in app first",VpnService.prepare(context));
            }
            controller.setBootstrapUrl(bootstrap);
            controller.startSharing();
            long startDeadline=SystemClock.elapsedRealtime()+90_000;
            while(SystemClock.elapsedRealtime()<startDeadline&&!stop.exists()){
                NodeController.Snapshot snapshot=controller.snapshot();
                if(snapshot.sharing&&!snapshot.busy&&snapshot.uploadBps>0)break;
                Thread.sleep(250);
            }
            NodeController.Snapshot ready=controller.snapshot();
            assertTrue("Actual sharing and physical upload measurement required",ready.sharing&&!ready.busy&&ready.uploadBps>0);
            long end=SystemClock.elapsedRealtime()+seconds*1000L,nextStatus=0;
            boolean connectRequested=false;
            while(SystemClock.elapsedRealtime()<end&&!stop.exists()){
                if(bootstrapOff.isFile()){
                    controller.setBootstrapUrl("");Files.deleteIfExists(bootstrapOff.toPath());bootstrapDisabled=true;
                    emit(new JSONObject().put("event","bootstrap_disabled"));
                }
                NodeController.Snapshot snapshot=controller.snapshot();
                int announced=0,paired=0,reachable=0;
                NodeController.Peer only=null;
                for(NodeController.Peer peer:snapshot.peers){
                    if(peer.paired)paired++;
                    if(peer.reachable)reachable++;
                    if(peer.autoDiscovered&&peer.announced){announced++;only=peer;}
                }
                if(client&&!connectRequested&&!snapshot.busy&&"ONLINE".equals(snapshot.discoveryState)){
                    assertTrue("Use an isolated bootstrap with exactly one other announced auto peer",announced<=1);
                    if(announced==1){controller.connect(only.id);connectRequested=true;}
                }
                if(snapshot.vpnConnected){
                    connected=true;
                    if(bootstrapDisabled&&snapshot.discoveryURL.isEmpty())connectedWithoutBootstrap=true;
                }
                if(SystemClock.elapsedRealtime()>=nextStatus){
                    emit(new JSONObject().put("event","state").put("discovery",snapshot.discoveryState)
                            .put("pairing",snapshot.pairingState).put("vpn",snapshot.vpnConnected).put("status",snapshot.status)
                            .put("announced",announced).put("paired",paired).put("reachable",reachable).put("clients",snapshot.clients)
                            .put("bootstrapConfigured",!snapshot.discoveryURL.isEmpty()).put("bootstrapHosting",snapshot.bootstrapHosting)
                            .put("shared",snapshot.sharedBytes).put("received",snapshot.receivedBytes)
                            .put("uploadBytesPerSecond",snapshot.uploadBps).put("limitBytesPerSecond",snapshot.shareLimitBps)
                            .put("error",snapshot.errorDetail));
                    nextStatus=SystemClock.elapsedRealtime()+2000;
                }
                Thread.sleep(200);
            }
            if(client){
                assertTrue("Client never established actual VPN through automatic discovery",connected);
                if(bootstrapDisabled)assertTrue("VPN did not remain available after removing bootstrap",connectedWithoutBootstrap);
            }
        }finally{
            try{controller.stopSharing();}
            finally{
                try{controller.setBootstrapUrl(originalUrl);}
                finally{
                    AppSelection.save(context,originalSelection);
                    try{Files.deleteIfExists(stop.toPath());Files.deleteIfExists(bootstrapOff.toPath());}
                    finally{if(activity!=null){Activity finalActivity=activity;getInstrumentation().runOnMainSync(finalActivity::finish);}}
                }
            }
        }
    }
    private void emit(JSONObject value){Bundle status=new Bundle();status.putString("auto_vpn",value.toString());getInstrumentation().sendStatus(0,status);}
}
