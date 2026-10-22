package org.uzel.core;

import org.junit.Test;
import java.net.InetAddress;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class PolicyTest {
    @Test public void fourthDeviceIsRejectedButSameDeviceCanReuseLease() {
        Admission gate = new Admission();
        assertFalse(gate.join("a"));
        gate.setSharing(true);
        assertTrue(gate.join("a"));
        assertTrue(gate.join("a"));
        assertTrue(gate.join("b"));
        assertTrue(gate.join("c"));
        assertFalse(gate.join("d"));
        assertEquals(3, gate.size());
        gate.leave("b");
        assertTrue(gate.join("d"));
        gate.setSharing(false);
        assertFalse(gate.contains("a"));
        assertEquals(0, gate.size());
    }

    @Test public void limiterPacesCombinedConcurrentOutputAtExplicitRate() throws Exception {
        Pacer pacer = new Pacer();
        pacer.setShareRateLimit(60_000); // One aggregate budget, independent of speed probes.
        ExecutorService pool = Executors.newFixedThreadPool(2);
        long started = System.nanoTime();
        Callable<Void> copy = () -> { for (int i=0;i<15;i++) pacer.acquire(2048); return null; };
        try {
            Future<Void> a = pool.submit(copy), b = pool.submit(copy);
            a.get(4, TimeUnit.SECONDS); b.get(4, TimeUnit.SECONDS);
            long elapsed = System.nanoTime()-started;
            assertTrue("Both clients must share a single budget", elapsed >= 1_000_000_000L);
            assertTrue("Pacing should not stall", elapsed < 3_000_000_000L);
        } finally { pool.shutdownNow(); }
        assertEquals(60_000, pacer.limit());
    }

    @Test public void unlimitedDefaultDoesNotBlock() throws Exception {
        Pacer pacer=new Pacer();
        assertEquals(0,pacer.limit());
        ExecutorService pool=Executors.newSingleThreadExecutor();
        try {pool.submit(()->{pacer.acquire(65536);return null;}).get(1,TimeUnit.SECONDS);}
        finally {pool.shutdownNow();}
    }

    @Test public void limiterRejectsNegativeButSupportsEntirePositiveLongRange() throws Exception {
        Pacer pacer=new Pacer();
        try {pacer.setShareRateLimit(-1);fail("negative rate");}
        catch(IllegalArgumentException expected){}
        pacer.setShareRateLimit(Long.MAX_VALUE);
        pacer.acquire(65536);
        assertEquals(Long.MAX_VALUE,pacer.limit());
    }

    @Test public void switchingToUnlimitedWakesPacedOutput() throws Exception {
        Pacer pacer=new Pacer();pacer.setShareRateLimit(1);
        ExecutorService pool=Executors.newSingleThreadExecutor();
        try {
            Future<?> output=pool.submit(()->{pacer.acquire(65536);return null;});
            Thread.sleep(50);assertFalse(output.isDone());
            pacer.setShareRateLimit(0);
            output.get(1,TimeUnit.SECONDS);
        } finally {pool.shutdownNow();}
    }

    @Test public void stopWakesActiveAndQueuedOutput() throws Exception {
        Pacer pacer=new Pacer();pacer.setShareRateLimit(1);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        Callable<Boolean> output=()->{try{pacer.acquire(65536);return false;}catch(java.io.IOException expected){return true;}};
        try {
            Future<Boolean> first=pool.submit(output),second=pool.submit(output);
            Thread.sleep(50);pacer.stop();
            assertTrue(first.get(1,TimeUnit.SECONDS));assertTrue(second.get(1,TimeUnit.SECONDS));
        } finally {pool.shutdownNow();}
    }

    @Test public void publicPolicyBlocksLocalNetworksAndMappedLoopback() throws Exception {
        for (String ip : new String[]{"127.0.0.1","10.2.3.4","192.168.0.1","172.20.0.1",
                "169.254.169.254","100.64.0.1","224.0.0.1","0.0.0.0","::1","fe80::1","fc00::1",
                "::ffff:127.0.0.1"}) {
            assertFalse(ip, PublicAddress.allowed(InetAddress.getByName(ip)));
        }
        assertTrue(PublicAddress.allowed(InetAddress.getByName("1.1.1.1")));
        assertTrue(PublicAddress.allowed(InetAddress.getByName("2606:4700:4700::1111")));
    }

    @Test public void invitationRoundTripsAndRejectsBadOrHugeInput() {
        String pin = "ab".repeat(32);
        String token = "cd".repeat(32);
        Invitation invitation = new Invitation("192.168.1.5", 39281, pin, token);
        Invitation decoded = Invitation.parse(invitation.encode());
        assertEquals("192.168.1.5", decoded.host);
        assertEquals(39281, decoded.port);
        assertEquals(pin, decoded.fingerprint);
        assertEquals(token, decoded.secret);
        for (String bad : new String[]{"http://example.com", "uzel://x:0/a/b",
                "uzel://user:pass@host:42/"+pin+"/"+token, "x".repeat(4097)}) {
            try { Invitation.parse(bad); fail("Must reject malformed invitation"); }
            catch (IllegalArgumentException expected) { }
        }
    }
}
