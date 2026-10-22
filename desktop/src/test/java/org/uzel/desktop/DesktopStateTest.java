package org.uzel.desktop;

import org.junit.Test;
import java.nio.file.*;
import static org.junit.Assert.*;

public class DesktopStateTest {
    @Test public void renamedLauncherKeepsLegacyDataOverrideAndAcceptsNewName(){
        String old=System.getProperty("peernet.data"),current=System.getProperty("uzelio.data");
        try{
            System.clearProperty("uzelio.data");System.setProperty("peernet.data","legacy-state");
            assertEquals(Path.of("legacy-state"),DesktopMain.dataDirectory());
            System.setProperty("uzelio.data","isolated-state");
            assertEquals(Path.of("isolated-state"),DesktopMain.dataDirectory());
        }finally{
            if(old==null)System.clearProperty("peernet.data");else System.setProperty("peernet.data",old);
            if(current==null)System.clearProperty("uzelio.data");else System.setProperty("uzelio.data",current);
        }
    }
    @Test public void identitySurvivesRestartAndCorruptionIsNeverReplaced() throws Exception {
        Path root=Files.createTempDirectory("peernet-identity-");
        String first=IdentityStore.load(root).fingerprint;
        assertEquals(first,IdentityStore.load(root).fingerprint);
        Files.write(root.resolve("identity.p12"),new byte[]{1,2,3});
        assertThrows(Exception.class,()->IdentityStore.load(root));
        assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(root.resolve("identity.p12")));
    }
    @Test public void settingsValidateAndPersist() throws Exception {
        assertThrows(IllegalArgumentException.class,()->new DesktopConfig("",0,false));
        assertThrows(IllegalArgumentException.class,()->new DesktopConfig("https://wrong/path",7777,false));
        assertThrows(IllegalArgumentException.class,()->new DesktopConfig("localhost",65536,false));
        Path root=Files.createTempDirectory("peernet-settings-");
        DesktopConfig value=new DesktopConfig("node.example.org",7777,true);
        value.save(root);
        assertEquals(value,DesktopConfig.load(root));
    }
}
