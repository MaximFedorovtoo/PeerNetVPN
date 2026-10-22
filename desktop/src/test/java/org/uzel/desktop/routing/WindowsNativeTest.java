package org.uzel.desktop.routing;

import org.junit.Test;
import org.junit.Assume;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Native pipe identity probes only. No elevation, driver, proxifier, system settings or external traffic. */
public class WindowsNativeTest {
    private void windows(){Assume.assumeTrue(System.getProperty("os.name","").startsWith("Windows"));}
    @Test public void authoritativeDirectoriesResolveToExistingOsLocations()throws Exception{
        windows();assertTrue(WindowsNative.programFiles().isAbsolute());assertTrue(Files.isDirectory(WindowsNative.programFiles()));
        assertTrue(Files.isRegularFile(WindowsNative.powershell()));
    }
    @Test public void limitedNativeProcessQueryMatchesCurrentJavaCreationTime()throws Exception{
        windows();
        java.lang.reflect.Method query;
        try{query=WindowsNative.class.getMethod("processCreatedMillis",long.class);}
        catch(NoSuchMethodException missing){fail("Native limited-rights creation query missing");return;}
        long actual=(long)query.invoke(null,ProcessHandle.current().pid());
        assertEquals(ProcessHandle.current().info().startInstant().orElseThrow().toEpochMilli(),actual);
    }
    @Test(timeout=15000) public void refusesAnUntrustedPipeServerBeforeWritingAnyData()throws Exception{
        windows();String name="PeerNet-Routing-"+UUID.randomUUID();
        String script="$p=[IO.Pipes.NamedPipeServerStream]::new('"+name+"',[IO.Pipes.PipeDirection]::InOut,1);"+
            "[Console]::WriteLine('WAIT');$p.WaitForConnection();$b=$p.ReadByte();[Console]::WriteLine(('BYTE='+$b));$p.Dispose()";
        Process process=new ProcessBuilder(WindowsNative.powershell().toString(),"-NoProfile","-NonInteractive","-EncodedCommand",
            Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE))).redirectErrorStream(true).start();
        try{
            BufferedReader stdout=new BufferedReader(new InputStreamReader(process.getInputStream(),StandardCharsets.US_ASCII));
            assertEquals("WAIT",stdout.readLine());
            IOException rejected=assertThrows(IOException.class,()->WindowsNative.openPipe(name,ProcessHandle.current().pid(),0));
            assertTrue(rejected.getMessage().contains("identity mismatch"));
            assertTrue(process.waitFor(5,TimeUnit.SECONDS));
            assertEquals("BYTE=-1",stdout.readLine()); // Client sent no bytes to a squatting server.
            assertEquals(0,process.exitValue());
        }finally{process.destroyForcibly();}
    }
}
