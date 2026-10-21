package org.uzel.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import java.io.File;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.HashMap;
import org.uzel.core.Node;
import android.net.VpnService;
import android.os.Bundle;
import android.test.InstrumentationTestCase;
import android.test.InstrumentationTestRunner;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.uzel.core.Invitation;
import org.uzel.core.PeerRoute;

/** Explicitly invoked live-device helper. Default suite execution only reads state. */
@SuppressWarnings("deprecation")
public final class LiveDeviceTest extends InstrumentationTestCase {
    public void testRun() throws Exception {
        Bundle args=((InstrumentationTestRunner)getInstrumentation()).getArguments();
        Context context=getInstrumentation().getTargetContext();
        NodeController controller=NodeController.get(context);
        String action=args.getString("action","snapshot");
        if("usbServe".equals(action)||"usbConnect".equals(action)) {
            runUsb(args,context,controller,"usbConnect".equals(action));return;
        }
        boolean emitContacts=Boolean.parseBoolean(args.getString("emitContacts","false"));
        int hold=Integer.parseInt(args.getString("holdSeconds", "snapshot".equals(action)?"0":"120"));
        if(hold<0||hold>600)throw new IllegalArgumentException("holdSeconds must be 0..600");
        if(!new HashSet<>(Arrays.asList("snapshot","start","connect","disconnect","stop")).contains(action))
            throw new IllegalArgumentException("Unknown live action");
        // Snapshot is read-only even when instrumentation runs the full test suite.
        Activity activity=null;
        if("start".equals(action)||"connect".equals(action)) {
            activity=getInstrumentation().startActivitySync(new Intent(context,MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            getInstrumentation().waitForIdleSync();
            if(args.containsKey("public")) controller.setPublicNetwork(Boolean.parseBoolean(args.getString("public")));
            if(args.containsKey("advertisedHost")) controller.setAdvertisedHost(args.getString("advertisedHost"));
            if(args.containsKey("apps")) {
                String value=args.getString("apps","").trim();
                Set<String> apps=new HashSet<>();
                if(!value.isEmpty())for(String item:value.split(","))apps.add(item.trim());
                AppSelection.save(context,apps);
                controller.disconnect();
                assertEquals("Requested probe apps must be installed/launchable",apps,AppSelection.load(context));
            }
            controller.startSharing();
        }
        if("disconnect".equals(action))controller.disconnect();
        if("stop".equals(action))controller.stopSharing();
        String peerId=args.getString("peerId","");
        if("connect".equals(action)) {
            String contact=args.getString("contact","");
            if(!contact.isEmpty()) {
                if(contact.length()>8192)throw new IllegalArgumentException("Contact too long");
                controller.addInvitation(contact);
                if(peerId.isEmpty())peerId=contact.startsWith("uzel://")
                        ?Invitation.parse(contact).fingerprint:PeerRoute.parse(contact).fingerprint;
            }
            if(peerId.isEmpty())throw new IllegalArgumentException("connect requires peerId or contact");
            assertFalse("Choose at least one installed probe application",AppSelection.validPackages(context).isEmpty());
        }
        long deadline=System.nanoTime()+hold*1_000_000_000L;
        boolean clicked=false;
        do {
            NodeController.Snapshot snapshot=controller.snapshot();
            if("connect".equals(action)&&!clicked&&snapshot.sharing&&!snapshot.busy) {
                final Activity owner=activity;
                final String target=peerId;
                // Invoke the real app handler. It opens the normal Android VPN consent
                // dialog and receives its actual result; this test never grants consent.
                boolean needsConsent=VpnService.prepare(context)!=null;
                if(needsConsent)event("VPN_CONSENT_REQUIRED");
                getInstrumentation().runOnMainSync(()->{
                    try {
                        Method connect=MainActivity.class.getDeclaredMethod("connect",String.class);
                        connect.setAccessible(true);
                        connect.invoke(owner,target);
                    }catch(Exception failure){throw new RuntimeException(failure);}
                });
                clicked=true;
            }
            emit(controller.snapshot(),emitContacts);
            if(System.nanoTime()>=deadline)break;
            Thread.sleep(Math.min(2000,Math.max(1,(deadline-System.nanoTime())/1_000_000L)));
        }while(true);
        NodeController.Snapshot end=controller.snapshot();
        if("start".equals(action))assertTrue("Sharing failed: "+end.status,end.sharing);
        if("connect".equals(action))assertTrue("VPN did not connect: "+end.status,end.vpnConnected);
        if("stop".equals(action))assertFalse("Sharing must stop",end.sharing);
        // Deliberately no stop/disconnect in finally: holdSeconds lets two devices be
        // coordinated. Root owns lifecycle cleanup with explicit stop/disconnect runs.
    }

    /** USB bridge diagnostic only; never evidence of public internet reachability. */
    @SuppressWarnings("unchecked")
    private void runUsb(Bundle args,Context context,NodeController controller,boolean connect) throws Exception {
        assertFalse("Stop existing PeerNetVPN sharing before an isolated USB test",controller.snapshot().sharing);
        assertFalse("Wait for existing PeerNetVPN operation before USB test",controller.snapshot().busy);
        int hold=Integer.parseInt(args.getString("holdSeconds","600"));
        int port=Integer.parseInt(args.getString("usbPort","39282"));
        if(hold<1||hold>600||port<1024||port>65535)throw new IllegalArgumentException("Invalid USB hold/port");
        SharedPreferences nodePrefs=context.getSharedPreferences("node",Context.MODE_PRIVATE);
        SharedPreferences appPrefs=context.getSharedPreferences("vpn_apps",Context.MODE_PRIVATE);
        String priorPeers=nodePrefs.getString("peers",null),priorPublicPeers=nodePrefs.getString("publicPeers",null);
        boolean priorPublic=controller.snapshot().publicNetwork;
        boolean priorPublicExisted=nodePrefs.contains("publicNetwork");
        boolean priorAppsExisted=appPrefs.contains("selected");
        Set<String> priorApps=new HashSet<>(AppSelection.load(context));
        Field peerField=NodeController.class.getDeclaredField("peers");peerField.setAccessible(true);
        Map<String,NodeController.Peer> livePeers=(Map<String,NodeController.Peer>)peerField.get(controller);
        Map<String,NodeController.Peer> priorPeerMap=new HashMap<>(livePeers);
        Field nodeField=NodeController.class.getDeclaredField("node");nodeField.setAccessible(true);
        File stop=new File(context.getFilesDir(),"live-device-stop");
        if(stop.exists()&&!stop.delete())throw new IllegalStateException("Cannot clear old test stop marker");
        Activity activity=null;
        try {
            activity=getInstrumentation().startActivitySync(new Intent(context,MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            getInstrumentation().waitForIdleSync();
            controller.setPublicNetwork(false); // No catalog refresh may rewrite the USB route.
            if(args.containsKey("apps")) {
                Set<String> selected=new HashSet<>();
                String list=args.getString("apps","");
                if(!list.trim().isEmpty())for(String app:list.split(","))selected.add(app.trim());
                AppSelection.save(context,selected);
                assertEquals("Requested probe apps must be installed",selected,AppSelection.load(context));
            }
            if(connect)assertFalse("Select the VPN probe app",AppSelection.validPackages(context).isEmpty());
            controller.startSharing();
            long prepareDeadline=System.nanoTime()+90_000_000_000L;
            Node own=null;
            while(System.nanoTime()<prepareDeadline&&!stop.exists()) {
                NodeController.Snapshot state=controller.snapshot();
                own=(Node)nodeField.get(controller);
                if(own!=null&&state.sharing&&!state.busy&&own.measuredUpload()>0)break;
                emit(state,false);Thread.sleep(1000);
            }
            assertNotNull("Controller node must start",own);
            assertTrue("Real internet upload measurement must succeed",own.measuredUpload()>0);
            assertFalse("Controller startup must finish",controller.snapshot().busy);
            PeerRoute advertised=PeerRoute.direct("127.0.0.1",port,own.publicRoute("127.0.0.1").fingerprint);
            own.setPublicMode(true);own.setAdvertisedRoute(advertised);
            Bundle ready=new Bundle();ready.putString("live_event","USB_DIAGNOSTIC_READY");
            ready.putString("ownFingerprint",advertised.fingerprint);
            ready.putString("live_diagnostic_contact",advertised.encode());
            ready.putString("transport","explicit USB ADB bridge; not public-internet reachability");
            getInstrumentation().sendStatus(0,ready);
            if(connect) {
                String contact=args.getString("contact","");
                if(contact.isEmpty())throw new IllegalArgumentException("usbConnect requires server public contact");
                PeerRoute target=PeerRoute.parse(contact);
                assertFalse("USB target must be a direct route",target.isRelay());
                assertEquals("USB target must be loopback","127.0.0.1",target.host);
                assertEquals("USB target port must match bridge",port,target.port);
                controller.addInvitation(contact);
                final Activity owner=activity;
                if(VpnService.prepare(context)!=null)event("VPN_CONSENT_REQUIRED");
                getInstrumentation().runOnMainSync(()->{
                    try{Method handler=MainActivity.class.getDeclaredMethod("connect",String.class);
                        handler.setAccessible(true);handler.invoke(owner,target.fingerprint);
                    }catch(Exception failure){throw new RuntimeException(failure);}
                });
            }
            long deadline=System.nanoTime()+hold*1_000_000_000L;
            boolean everConnected=false;
            long nextSnapshot=0;
            while(System.nanoTime()<deadline&&!stop.exists()) {
                // Only this test-created sharing instance may receive the override.
                assertSame("Physical network must stay stable during USB diagnostic",own,nodeField.get(controller));
                own.setAdvertisedRoute(advertised);
                NodeController.Snapshot state=controller.snapshot();
                everConnected|=state.vpnConnected;
                if(System.nanoTime()>=nextSnapshot){emit(state,false);nextSnapshot=System.nanoTime()+2_000_000_000L;}
                Thread.sleep(250);
            }
            if(connect)assertTrue("USB diagnostic VPN must have connected",everConnected);
        } finally {
            controller.disconnect();controller.stopSharing();
            controller.setPublicNetwork(priorPublic);
            livePeers.clear();livePeers.putAll(priorPeerMap);
            SharedPreferences.Editor restore=nodePrefs.edit();
            if(priorPeers==null)restore.remove("peers");else restore.putString("peers",priorPeers);
            if(priorPublicPeers==null)restore.remove("publicPeers");else restore.putString("publicPeers",priorPublicPeers);
            if(!priorPublicExisted)restore.remove("publicNetwork");
            if(!restore.commit())throw new IllegalStateException("Failed to restore node test settings");
            SharedPreferences.Editor apps=appPrefs.edit();
            if(priorAppsExisted)apps.putStringSet("selected",priorApps);else apps.remove("selected");
            if(!apps.commit())throw new IllegalStateException("Failed to restore app selection");
            if(stop.exists())stop.delete();
            if(activity!=null){final Activity owner=activity;getInstrumentation().runOnMainSync(owner::finish);}
            event("USB_DIAGNOSTIC_CLEANED_UP");
        }
    }
    private void event(String value) {
        Bundle result=new Bundle();result.putString("live_event",value);getInstrumentation().sendStatus(0,result);
    }

    private void emit(NodeController.Snapshot state,boolean contacts) throws Exception {
        JSONObject snapshot=new JSONObject();
        snapshot.put("status",state.status);snapshot.put("sharing",state.sharing);
        snapshot.put("vpnConnected",state.vpnConnected);snapshot.put("busy",state.busy);
        snapshot.put("publicNetwork",state.publicNetwork);snapshot.put("networkDetail",state.networkDetail);
        snapshot.put("publicIp",state.publicIp);snapshot.put("country",state.country);
        snapshot.put("clients",state.clients);snapshot.put("sharedBytes",state.sharedBytes);
        snapshot.put("receivedBytes",state.receivedBytes);snapshot.put("uploadBps",state.uploadBps);
        JSONArray peers=new JSONArray();
        for(NodeController.Peer peer:state.peers) {
            if(peers.length()>=64)break;
            JSONObject p=new JSONObject();p.put("id",peer.id);p.put("host",peer.host);
            p.put("reachable",peer.reachable);p.put("country",peer.country);p.put("latencyMs",peer.latencyMs);
            peers.put(p);
        }
        snapshot.put("peers",peers);
        Bundle result=new Bundle();result.putString("live_snapshot",snapshot.toString());
        // Invitation is a secret: output only under an explicit local test argument.
        if(contacts){result.putString("live_publicContact",state.publicContact);result.putString("live_invitation",state.invitation);}
        getInstrumentation().sendStatus(0,result);
    }
}
