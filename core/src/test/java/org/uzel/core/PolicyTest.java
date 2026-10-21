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

    @Test public void limiterPacesCombinedConcurrentOutputAtOneThird() throws Exception {
        Pacer pacer = new Pacer();
        pacer.setMeasuredUpload(180_000); // bytes/s; 60,000 total bytes/s allowed
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

    @Test public void unknownSpeedCannotForward() throws Exception {
        try { new Pacer().acquire(100); fail("No unmeasured unlimited fallback"); }
        catch (java.io.IOException expected) { }
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
