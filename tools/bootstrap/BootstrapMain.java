package org.uzel.bootstrap;

import java.net.InetAddress;
import java.util.concurrent.CountDownLatch;
import org.uzel.core.BootstrapHttpServer;
import org.uzel.core.DiscoveryStore;

/** Discovery-only process: no tunnel, proxy, files or persistent participant secrets. */
public final class BootstrapMain {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: bind-address port");
        InetAddress bind = "container".equals(args[0]) ? InetAddress.getLocalHost() : InetAddress.getByName(args[0]);
        DiscoveryStore store = new DiscoveryStore();
        BootstrapHttpServer server = new BootstrapHttpServer(bind, Integer.parseInt(args[1]),
                frame -> store.exchange(frame, System.currentTimeMillis()));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { server.close(); store.clear(); }));
        System.out.println("PeerNet discovery ready on port " + server.port());
        new CountDownLatch(1).await();
    }
}
