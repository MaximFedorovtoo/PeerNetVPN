package org.uzel.desktop;

import org.junit.*;
import org.uzel.desktop.routing.WindowsNative;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public class WindowsRoutingSetupTest {
    @Test public void modifiedSetupAndBrokerNeverExecute()throws Exception {
        Assume.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        Path root=Files.createTempDirectory("routing-bootstrap-test-");Path setup=root.resolve("setup.ps1"),broker=root.resolve("broker.ps1"),marker=root.resolve("executed");
        byte[] original=("param([switch]$AcceptThirdPartyLicenses,[switch]$InstallPrerequisites,[byte[]]$BrokerPayload)\n[IO.File]::WriteAllText('"+marker+"','verified')").getBytes(StandardCharsets.UTF_8);
        byte[] payload="trusted broker".getBytes(StandardCharsets.UTF_8);
        Files.write(setup,original);Files.write(broker,payload);
        String bootstrap=WindowsRoutingSetup.verifiedBootstrap(setup,broker,original,payload);
        Files.writeString(setup,"[IO.File]::WriteAllText('"+marker+"','untrusted')");
        assertEquals(1,run(bootstrap));assertFalse(Files.exists(marker));
        Files.write(setup,original);Files.writeString(broker,"untrusted broker");
        assertEquals(1,run(bootstrap));assertFalse(Files.exists(marker));
        Files.write(broker,payload);assertEquals(0,run(bootstrap));assertEquals("verified",Files.readString(marker));
    }
    @Test public void capturedBrokerBytesSurvivePathMutation()throws Exception {
        Assume.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        Path root=Files.createTempDirectory("routing-capture-test-");Path setup=root.resolve("setup.ps1"),broker=root.resolve("broker.ps1"),marker=root.resolve("captured");
        byte[] original=("param([switch]$AcceptThirdPartyLicenses,[switch]$InstallPrerequisites,[byte[]]$BrokerPayload)\n"+
            "[IO.File]::WriteAllText('"+broker+"','replaced');[IO.File]::WriteAllBytes('"+marker+"',$BrokerPayload)").getBytes(StandardCharsets.UTF_8);
        byte[] payload="trusted broker".getBytes(StandardCharsets.UTF_8);Files.write(setup,original);Files.write(broker,payload);
        assertEquals(0,run(WindowsRoutingSetup.verifiedBootstrap(setup,broker,original,payload)));
        assertArrayEquals(payload,Files.readAllBytes(marker));assertEquals("replaced",Files.readString(broker));
    }
    private int run(String code)throws Exception {
        Process process=new ProcessBuilder(WindowsNative.powershell().toString(),"-NoProfile","-NonInteractive","-EncodedCommand",Base64.getEncoder().encodeToString(code.getBytes(StandardCharsets.UTF_16LE))).redirectErrorStream(true).start();
        assertTrue("Bootstrap hung",process.waitFor(10,TimeUnit.SECONDS));return process.exitValue();
    }
}
