package org.uzel.desktop;

import org.uzel.core.NetworkAccess;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Opt-in Windows acceptance probe, never part of automatic tests. Requires installed driver and UAC.
 * Two real encrypted peers on this PC isolate Windows classification from NAT reachability.
 * Fixed injected upload is a test fixture only; no production speed result is saved.
 */
public final class DesktopWindowsRoutingProbe {
    public static void main(String[] args)throws Exception {
        if(args.length!=3)throw new IllegalArgumentException("test-state selected.exe direct.exe");
        Path state=Path.of(args[0]);Files.createDirectories(state);
        Path selected=Path.of(args[1]).toRealPath(),direct=Path.of(args[2]).toRealPath();
        try(var exit=new DesktopTrafficService(IdentityStore.load(state.resolve("exit")),new NetworkAccess(),org.uzel.core.PublicAddress::allowed,c->10_000_000);
            var client=new DesktopTrafficService(IdentityStore.load(state.resolve("client")),new NetworkAccess(),org.uzel.core.PublicAddress::allowed,c->10_000_000);
            var router=new WindowsAppRouter()){
            exit.startSharing(0);client.startSharing(0);client.connect(exit.invitation("127.0.0.1"),()->{});
            System.out.println("Real encrypted peer session established; test fixture upload, not a speed measurement.");
            run(selected,"tcp");run(selected,"udp");
            check(client.snapshot().receivedBytes()==0,"Baseline must be direct");
            System.out.println("Windows permission requested for two isolated probe programs only.");
            router.activate(List.of(selected),client.localProxy());
            System.out.println("Routing helper: "+router.snapshot().status());
            for(String protocol:List.of("tcp","udp")){
                long before=client.snapshot().receivedBytes();run(selected,protocol);
                Thread.sleep(350);long after=client.snapshot().receivedBytes();
                check(after>before,"Selected "+protocol+" bypassed Uzelio");
                System.out.println("PASS selected "+protocol+" through Uzelio, received bytes="+(after-before));
                before=after;run(direct,protocol);Thread.sleep(350);
                check(client.snapshot().receivedBytes()==before,"Unselected "+protocol+" was captured");
                System.out.println("PASS unselected "+protocol+" direct");
            }
            router.deactivate();Thread.sleep(1500);
            long before=client.snapshot().receivedBytes();run(selected,"tcp");Thread.sleep(350);
            check(client.snapshot().receivedBytes()==before,"Routing not revoked");
            System.out.println("PASS routing revoked; sharing remains="+client.snapshot().sharing());
        }
    }
    private static void run(Path file,String protocol)throws Exception {
        Process process=new ProcessBuilder(file.toString(),protocol).inheritIO().start();
        if(!process.waitFor(25,TimeUnit.SECONDS)){process.destroyForcibly();throw new IllegalStateException("Probe timeout: "+protocol);}
        check(process.exitValue()==0,"Probe failed: "+protocol);
    }
    private static void check(boolean value,String message){if(!value)throw new IllegalStateException(message);}
}
