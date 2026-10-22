package org.uzel.desktop.routing;

import org.junit.Test;
import java.nio.file.Path;
import java.util.List;
import java.lang.reflect.InvocationTargetException;
import static org.junit.Assert.*;

/** Runs without a driver or administrator privileges. */
public class WindowsRoutingConfigTest {
    private String config(List<String> apps,String user,String password)throws Exception {
        try {
            Class<?> type=Class.forName("org.uzel.desktop.routing.WindowsRoutingConfig");
            return (String)type.getMethod("generate",List.class,String.class,int.class,String.class,String.class)
                .invoke(null,apps,"127.0.0.1",1080,user,password);
        }catch(ClassNotFoundException missing){fail("Windows routing config generator is missing");return null;}
        catch(InvocationTargetException failure){if(failure.getCause() instanceof IllegalArgumentException invalid)throw invalid;throw failure;}
    }
    @Test public void createsOnlyExplicitExactNamesAndAuthenticatedDualProtocolRule()throws Exception {
        String json=config(List.of("firefox.exe","custom app.exe"),"peer-user","peer-secret");
        assertTrue(json.contains("\"appNames\":[\"firefox.exe\",\"custom app.exe\"]"));
        assertTrue(json.contains("\"username\":\"peer-user\""));
        assertTrue(json.contains("\"password\":\"peer-secret\""));
        assertTrue(json.contains("\"supportedProtocols\":[\"TCP\",\"UDP\"]"));
        assertTrue(json.contains("\"supportedAddressFamilies\":[\"IPv4\",\"IPv6\"]"));
        assertTrue(json.contains("\"bypassLan\":false"));
        assertTrue(json.contains("\"uzelio.exe\""));
        assertFalse(json.contains("\"appNames\":[\"\"]"));
    }
    @Test public void credentialsAreJsonEscapedWithoutBecomingProperties()throws Exception {
        String json=config(List.of("firefox.exe"),"u\"\\\n","p\t\r\u0001");
        assertTrue(json.contains("\"username\":\"u\\\"\\\\\\n\""));
        assertTrue(json.contains("\"password\":\"p\\t\\r\\u0001\""));
    }
    @Test public void rejectsCatchAllPathsAmbiguityAndCarrierProcesses()throws Exception {
        for(List<String> names:List.of(List.<String>of(),List.of(""),List.of(" "),List.of("firefox"),
                List.of("C:\\Apps\\firefox.exe"),List.of("../firefox.exe"),List.of("firefox.exe","FIREFOX.EXE"),
                List.of("java.exe"),List.of("javaw.exe"),List.of("PeerNet.exe"),List.of("ProxiFyre.exe"),List.of("bad\n.exe"),
                List.of("Uzelio.exe"),List.of("customUZELIO.exe"),List.of("myjava.exe"),List.of("customPeerNet.exe"),List.of("a".repeat(261)+".exe"))){
            assertThrows(names.toString(),IllegalArgumentException.class,()->config(names,"u","p"));
        }
    }
    @Test public void rejectsPayloadThatCannotFitTheProtectedPipeBuffer()throws Exception {
        List<String> names=java.util.stream.IntStream.range(0,128).mapToObj(n->"я".repeat(250)+n+".exe").toList();
        assertThrows(IllegalArgumentException.class,()->config(names,"u","p"));
    }
    @Test public void rejectsMissingAndOversizedAuthentication()throws Exception {
        assertThrows(IllegalArgumentException.class,()->config(List.of("firefox.exe"),"","p"));
        assertThrows(IllegalArgumentException.class,()->config(List.of("firefox.exe"),"u",""));
        assertThrows(IllegalArgumentException.class,()->config(List.of("firefox.exe"),"u","я".repeat(128)));
    }
}
