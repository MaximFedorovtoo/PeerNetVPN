package org.uzel.app;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.test.InstrumentationTestCase;
import java.io.DataInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.security.auth.x500.X500Principal;
import org.uzel.core.NetworkAccess;
import org.uzel.core.Node;
import org.uzel.core.PeerRoute;
import org.uzel.core.TlsIdentity;

/** Real Android TLS/AndroidKeyStore coverage, with all network traffic confined to loopback. */
@SuppressWarnings("deprecation")
public final class AndroidTransportTest extends InstrumentationTestCase {
    private final List<String> aliases = new ArrayList<>();
    private final List<Node> nodes = new ArrayList<>();

    public void testPublicDirectAndParticipantRelayCarryTlsEcho() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        ExecutorService echoWorker = Executors.newSingleThreadExecutor();
        try {
            // Generate all three first: a key manager must select the requested alias,
            // even when other node identities (including the real device key) coexist.
            for (int i=0; i<3; i++) createTemporaryIdentity(store, i);
            TlsIdentity relayIdentity = TlsIdentity.fromKeyStore(store,null,aliases.get(0));
            TlsIdentity clientIdentity = TlsIdentity.fromKeyStore(store,null,aliases.get(1));
            TlsIdentity targetIdentity = TlsIdentity.fromKeyStore(store,null,aliases.get(2));
            assertFalse(relayIdentity.fingerprint.equals(clientIdentity.fingerprint));
            assertFalse(clientIdentity.fingerprint.equals(targetIdentity.fingerprint));
            assertFalse(relayIdentity.fingerprint.equals(targetIdentity.fingerprint));

            Node relay = start(relayIdentity), client = start(clientIdentity), target = start(targetIdentity);
            PeerRoute relayRoute = relay.publicRoute("127.0.0.1");
            PeerRoute clientDirect = client.publicRoute("127.0.0.1");
            PeerRoute targetDirect = target.publicRoute("127.0.0.1");
            relay.setAdvertisedRoute(relayRoute);
            client.setAdvertisedRoute(clientDirect);
            target.setAdvertisedRoute(targetDirect);
            target.country = "JP";
            Node.Info direct = client.inspect(targetDirect);
            assertTrue(direct.sharing);
            assertEquals("JP",direct.country);
            assertTrue(direct.upload>0);

            try (ServerSocket echo = new ServerSocket(0,2,InetAddress.getByName("127.0.0.1"))) {
                echo.setSoTimeout(20000);
                Future<?> echoResult = echoWorker.submit(() -> {
                    try {
                        for (int i=0; i<2; i++) {
                            try (Socket accepted = echo.accept()) {
                                accepted.setSoTimeout(20000);
                                byte[] bytes = new byte[257];
                                new DataInputStream(accepted.getInputStream()).readFully(bytes);
                                accepted.getOutputStream().write(bytes);
                                accepted.getOutputStream().flush();
                            }
                        }
                    } catch(IOException failure) { throw new RuntimeException(failure); }
                });
                AtomicBoolean directLost = new AtomicBoolean();
                try (Node.Session directSession=client.connect(targetDirect,()->directLost.set(true))) {
                    assertTrue(directSession.isLive());
                    echo(directSession,echo.getLocalPort(),11);
                    assertFalse("Direct TLS session must remain live",directLost.get());
                }
                try (Node.RelayLease clientLease=client.registerRelay(relayRoute);
                     Node.RelayLease targetLease=target.registerRelay(relayRoute)) {
                    assertTrue(clientLease.isLive());
                    assertTrue(targetLease.isLive());
                    assertTrue(clientLease.route().isRelay());
                    assertTrue(targetLease.route().isRelay());
                    assertEquals(clientIdentity.fingerprint,clientLease.route().fingerprint);
                    assertEquals(targetIdentity.fingerprint,targetLease.route().fingerprint);
                    client.setAdvertisedRoute(clientLease.route());
                    target.setAdvertisedRoute(targetLease.route());
                    Node.Info relayed = client.inspect(targetLease.route());
                    assertTrue(relayed.sharing);
                    assertEquals("JP",relayed.country);
                    assertTrue(relayed.upload>0);
                    AtomicBoolean relayLost = new AtomicBoolean();
                    try (Node.Session session=client.connect(targetLease.route(),()->relayLost.set(true))) {
                        assertTrue(session.isLive());
                        echo(session,echo.getLocalPort(),97);
                        assertFalse("Relayed end-to-end TLS session must remain live",relayLost.get());
                        assertTrue(clientLease.isLive());
                        assertTrue(targetLease.isLive());
                    }
                }
                echoResult.get(30,TimeUnit.SECONDS);
            }
        } finally {
            for (int i=nodes.size()-1;i>=0;i--) nodes.get(i).close();
            nodes.clear();
            echoWorker.shutdownNow();
            // Never address DeviceIdentity's persistent alias: delete only UUID aliases
            // generated by this invocation, including on TLS/assertion failures.
            for(String alias:aliases) store.deleteEntry(alias);
            aliases.clear();
        }
    }

    private void createTemporaryIdentity(KeyStore store,int index) throws Exception {
        String alias="uzel-transport-test-"+UUID.randomUUID()+"-"+index;
        assertFalse("Test alias must not replace an existing identity",store.containsAlias(alias));
        aliases.add(alias);
        long now=System.currentTimeMillis();
        KeyPairGenerator generator=KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC,"AndroidKeyStore");
        generator.initialize(new KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_SIGN|KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_NONE,KeyProperties.DIGEST_SHA256)
                .setCertificateSubject(new X500Principal("CN=Uzel isolated transport test "+index))
                .setCertificateSerialNumber(new BigInteger(120,new SecureRandom()).add(BigInteger.ONE))
                .setCertificateNotBefore(new Date(now-86400000L))
                .setCertificateNotAfter(new Date(now+7L*86400000L)).build());
        generator.generateKeyPair();
    }

    private Node start(TlsIdentity identity) throws Exception {
        // TEST ONLY: allow private loopback exit addresses and synthetic upload speed.
        // Production uses PublicAddress and measured physical upload instead.
        Node node=new Node(identity,new LoopbackOnlyNetwork(),address->true);
        node.setFailureObserver(error -> android.util.Log.e("UzelTLS", "server transport", error));
        nodes.add(node);
        node.start(0);
        node.setMeasuredUpload(12_000_000);
        node.setPublicMode(true);
        return node;
    }

    private void echo(Node.Session session,int port,int seed) throws IOException {
        byte[] sent=new byte[257];
        for(int i=0;i<sent.length;i++) sent[i]=(byte)(seed+i*31);
        byte[] received=new byte[sent.length];
        try(Socket stream=session.openTcp("127.0.0.1",port)) {
            stream.setSoTimeout(20000);
            stream.getOutputStream().write(sent);
            stream.getOutputStream().flush();
            new DataInputStream(stream.getInputStream()).readFully(received);
        }
        assertTrue("Echo payload must survive the complete TLS transport",Arrays.equals(sent,received));
    }

    private static final class LoopbackOnlyNetwork extends NetworkAccess {
        @Override public InetAddress[] resolve(String host) throws IOException {
            if(!"127.0.0.1".equals(host)) throw new IOException("Test prohibits non-loopback resolution");
            return new InetAddress[]{InetAddress.getByAddress(new byte[]{127,0,0,1})};
        }
        @Override public Socket open(InetAddress address,int port) throws IOException {
            if(!address.isLoopbackAddress()) throw new IOException("Test prohibits external traffic");
            return super.open(address,port);
        }
        @Override public DatagramSocket datagram() throws IOException {
            throw new IOException("Datagrams are not used by this isolated transport test");
        }
    }
}
