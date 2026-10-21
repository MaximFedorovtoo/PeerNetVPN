package org.uzel.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;
import android.test.InstrumentationTestCase;
import android.test.InstrumentationTestRunner;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONObject;

/** Explicit live test: direct transport, no adb port forwarding, no implicit VPN consent. */
@SuppressWarnings("deprecation")
public final class NatVpnDeviceTest extends InstrumentationTestCase {
    public void testHold()throws Exception {
        Bundle args=((InstrumentationTestRunner)getInstrumentation()).getArguments();
        if(!"hold".equals(args.getString("action","")))return;
        boolean wantNativeLog=Boolean.parseBoolean(args.getString("nativeLog","false")),nativeLogging=false;
        boolean offer="offer".equals(args.getString("role",""));
        if(!offer&&!"answer".equals(args.getString("role","")))throw new IllegalArgumentException("Explicit role required");
        boolean client=Boolean.parseBoolean(args.getString("client","false"));
        boolean checkMainDisconnect=Boolean.parseBoolean(args.getString("checkMainDisconnect","false"));
        if(checkMainDisconnect&&!client)throw new IllegalArgumentException("Disconnect regression requires client");
        int seconds=Integer.parseInt(args.getString("holdSeconds","480"));
        if(seconds<60||seconds>900)throw new IllegalArgumentException("holdSeconds 60..900");
        Context context=getInstrumentation().getTargetContext();
        NodeController controller=NodeController.get(context);
        assertFalse("Stop existing sharing before this explicit test",controller.snapshot().sharing);
        assertFalse("Controller must be idle",controller.snapshot().busy);
        Set<String> original=new HashSet<>(AppSelection.load(context));
        File input=new File(context.getFilesDir(),"nat-pair-in.txt"),output=new File(context.getFilesDir(),"nat-pair-out.txt");
        File stop=new File(context.getFilesDir(),"nat-vpn-stop");
        Files.deleteIfExists(input.toPath());Files.deleteIfExists(output.toPath());Files.deleteIfExists(stop.toPath());
        Activity activity=null;boolean connected=false;
        try {
            activity=getInstrumentation().startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            if(client){
                Set<String> selected=new HashSet<>();selected.add("org.uzel.probe.vpn");AppSelection.save(context,selected);
                assertEquals("Install probe APK first",selected,new HashSet<>(AppSelection.validPackages(context)));
                assertNull("User must grant Android VPN permission in app first",VpnService.prepare(context));
            }
            controller.startSharing();
            long startDeadline=System.currentTimeMillis()+90_000;
            while(System.currentTimeMillis()<startDeadline){
                NodeController.Snapshot s=controller.snapshot();
                if(s.sharing&&!s.busy&&s.uploadBps>0)break;
                Thread.sleep(250);
            }
            assertTrue("Real upload measurement required",controller.snapshot().uploadBps>0);
            if(offer)controller.createPairing();
            String previous="";long end=System.currentTimeMillis()+seconds*1000L,nextStatus=0;
            boolean connectRequested=false,mainDisconnectChecked=false,reconnectedAfterMainDisconnect=false;
            while(System.currentTimeMillis()<end&&!stop.exists()){
                if(input.isFile()){
                    if(input.length()>100_000)throw new IllegalArgumentException("Pairing input bounds");
                    String text=new String(Files.readAllBytes(input.toPath()),StandardCharsets.UTF_8).trim();
                    Files.delete(input.toPath());controller.acceptPairing(text);
                }
                NodeController.Snapshot s=controller.snapshot();
                if(wantNativeLog&&!nativeLogging&&!"IDLE".equals(s.pairingState))try{
                    org.webrtc.Logging.enableLogToDebugOutput(org.webrtc.Logging.Severity.LS_INFO);nativeLogging=true;
                }catch(UnsatisfiedLinkError notLoadedYet){/* Transport loads its native library asynchronously. */}
                if(!s.pairingCode.isEmpty()&&!previous.equals(s.pairingCode)){
                    Files.write(output.toPath(),s.pairingCode.getBytes(StandardCharsets.UTF_8));previous=s.pairingCode;
                    emit(new JSONObject().put("event","code_ready").put("pairing",s.pairingState));
                }
                if(client&&!connectRequested&&"CONNECTED".equals(s.pairingState)){
                    for(NodeController.Peer peer:s.peers)if(peer.reachable){controller.connect(peer.id);connectRequested=true;break;}
                }
                if(s.vpnConnected){
                    connected=true;
                    if(checkMainDisconnect&&!mainDisconnectChecked){
                        getInstrumentation().runOnMainSync(controller::disconnect);
                        assertFalse("Main-thread disconnect must invalidate VPN synchronously",controller.snapshot().vpnConnected);
                        mainDisconnectChecked=true;connectRequested=false;
                        emit(new JSONObject().put("event","main_disconnect_checked"));
                    }else if(mainDisconnectChecked)reconnectedAfterMainDisconnect=true;
                }
                if(System.currentTimeMillis()>=nextStatus){
                    emit(new JSONObject().put("event","state").put("pairing",s.pairingState).put("vpn",s.vpnConnected)
                            .put("status",s.status).put("clients",s.clients).put("shared",s.sharedBytes).put("received",s.receivedBytes)
                            .put("uploadBytesPerSecond",s.uploadBps).put("limitBytesPerSecond",s.shareLimitBps).put("detail",s.errorDetail));
                    nextStatus=System.currentTimeMillis()+2000;
                }
                Thread.sleep(200);
            }
            if(client)assertTrue("Client never established actual VPN over direct transport",connected);
            if(checkMainDisconnect)assertTrue("VPN must reconnect after main-thread disconnect",reconnectedAfterMainDisconnect);
        }finally{
            controller.stopSharing();AppSelection.save(context,original);
            Files.deleteIfExists(input.toPath());Files.deleteIfExists(output.toPath());Files.deleteIfExists(stop.toPath());
            if(activity!=null){Activity finalActivity=activity;getInstrumentation().runOnMainSync(finalActivity::finish);}
        }
    }
    private void emit(JSONObject value){Bundle status=new Bundle();status.putString("nat_vpn",value.toString());getInstrumentation().sendStatus(0,status);}
}
