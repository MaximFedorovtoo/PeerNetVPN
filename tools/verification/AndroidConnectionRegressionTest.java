package org.uzel.core;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import static org.junit.Assert.*;
import org.uzel.app.NodeController;

/** Runs the real Android controller with JVM-only permission/selection gates. TLS is real. */
@SuppressWarnings("unchecked")
public class AndroidConnectionRegressionTest extends NetworkTest {
    private static Object allocate(Class<?> type)throws Exception {
        Class<?> unsafe=Class.forName("sun.misc.Unsafe");Field field=unsafe.getDeclaredField("theUnsafe");field.setAccessible(true);
        return unsafe.getMethod("allocateInstance",Class.class).invoke(field.get(null),type);
    }
    private static void put(Object target,String name,Object value)throws Exception {
        Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value);
    }
    private static Object get(Object target,String name)throws Exception {
        Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);
    }
    private NodeController controller(Node node)throws Exception {
        NodeController result=(NodeController)allocate(NodeController.class);
        put(result,"node",node);put(result,"generation",new AtomicLong());put(result,"vpnGeneration",new AtomicLong());
        put(result,"pendingAutoId","");put(result,"peers",new ConcurrentHashMap<>());
        ExecutorService worker=Executors.newSingleThreadExecutor();cleanup.add(()->worker.shutdownNow());put(result,"work",worker);
        return result;
    }
    private Object automatic()throws Exception {
        Object manager=allocate(Class.forName("org.uzel.app.AutoDiscoveryManager"));
        put(manager,"cancellations",new AtomicLong());put(manager,"pending","");
        ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor();worker.shutdown();
        put(manager,"worker",worker);return manager;
    }
    @Test public void switchingToAutomaticPeerRevokesOldVpnBeforePairing()throws Exception {
        Node own=node(0),oldPeer=node(1),incoming=node(3);
        Node.Session old=own.connect(oldPeer.invitation("127.0.0.1"),()->{});cleanup.add(old);
        Node.Session inbound=incoming.connect(own.invitation("127.0.0.1"),()->{});cleanup.add(inbound);
        NodeController controller=controller(own);put(controller,"session",old);put(controller,"vpnConnected",true);
        put(controller,"automatic",automatic());
        DiscoveryIdentity identity=new DiscoveryIdentity(keys.get(2));
        DiscoveryPresence presence=identity.presence("DE",0,1000,System.currentTimeMillis());
        NodeController.Peer peer=new NodeController.Peer();peer.id=presence.fingerprint;peer.autoDiscovered=true;
        put(peer,"presence",presence);
        ((Map<String,NodeController.Peer>)get(controller,"peers")).put(peer.id,peer);
        controller.connect(peer.id);
        assertFalse("Replacement must revoke old outgoing session immediately",old.isLive());
        assertEquals(false,get(controller,"vpnConnected"));assertEquals(peer.id,get(controller,"pendingAutoId"));
        assertTrue("Own sharing stays active",own.isSharing());assertTrue("Incoming client stays live",inbound.isLive());
        assertEquals(1,own.clientCount());
        try(java.net.ServerSocket destination=new java.net.ServerSocket(0,1,java.net.InetAddress.getLoopbackAddress());
            java.net.Socket stream=inbound.openTcp("127.0.0.1",destination.getLocalPort());
            java.net.Socket accepted=destination.accept()){
            stream.getOutputStream().write(42);assertEquals(42,accepted.getInputStream().read());
        }
    }
    @Test public void disconnectLeavesSharingAndInvalidatesOnlyOutgoingSession()throws Exception {
        Node own=node(0),peer=node(1);Node.Session session=own.connect(peer.invitation("127.0.0.1"),()->{});cleanup.add(session);
        NodeController controller=controller(own);put(controller,"session",session);put(controller,"vpnConnected",true);put(controller,"busy",true);
        controller.disconnect();
        assertFalse(session.isLive());assertTrue(own.isSharing());assertEquals(false,get(controller,"vpnConnected"));
        assertEquals(false,get(controller,"busy"));assertEquals("SHARING",get(controller,"status"));
    }
    @Test public void verificationFromCancelledAttemptCannotConsumeSamePeerReplacement()throws Exception {
        Node own=node(0),peerNode=node(1);NodeController controller=controller(own);
        NodeController.Peer peer=new NodeController.Peer();peer.id=peerNode.invitation("127.0.0.1").fingerprint;
        put(peer,"invite",peerNode.invitation("127.0.0.1"));put(controller,"pendingAutoId",peer.id);
        ExecutorService probes=Executors.newSingleThreadExecutor();cleanup.add(()->probes.shutdownNow());put(controller,"peerWork",probes);
        CountDownLatch blocked=new CountDownLatch(1),release=new CountDownLatch(1);
        probes.submit(()->{blocked.countDown();try{release.await();}catch(InterruptedException ignored){}});
        assertTrue(blocked.await(2,TimeUnit.SECONDS));
        java.lang.reflect.Method verify=NodeController.class.getDeclaredMethod("verifyAutomaticPeer",Node.class,NodeController.Peer.class,long.class);verify.setAccessible(true);
        verify.invoke(controller,own,peer,0L);
        controller.disconnect();put(controller,"pendingAutoId",peer.id);put(controller,"status","CONNECTING");
        release.countDown();probes.submit(()->{}).get(5,TimeUnit.SECONDS);
        assertEquals("Stale verification must not clear replacement request",peer.id,get(controller,"pendingAutoId"));
        assertEquals("CONNECTING",get(controller,"status"));
    }
}
