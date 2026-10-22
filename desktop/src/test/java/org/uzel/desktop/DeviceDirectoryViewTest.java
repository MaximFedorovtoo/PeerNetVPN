package org.uzel.desktop;

import org.junit.Test;
import org.uzel.core.*;
import java.nio.file.Files;
import java.util.List;
import static org.junit.Assert.*;

public class DeviceDirectoryViewTest {
    @Test public void groupsRealUnexpiredSignedPresencesAndFiltersCountries()throws Exception {
        long now=System.currentTimeMillis();
        DiscoveryIdentity a=new DiscoveryIdentity(IdentityStore.load(Files.createTempDirectory("region-a-")));
        DiscoveryIdentity b=new DiscoveryIdentity(IdentityStore.load(Files.createTempDirectory("region-b-")));
        DiscoveryPresence ru=a.presence("RU",1,1200000,now);
        DiscoveryPresence unknown=b.presence("",0,900000,now);
        var devices=DeviceDirectoryView.fromPresences(List.of(ru,unknown),now);
        assertEquals(2,devices.size());
        assertEquals(1,DeviceDirectoryView.filter(devices,"RU").size());
        assertEquals(1,DeviceDirectoryView.filter(devices,"").size());
        assertTrue(DeviceDirectoryView.filter(devices,"DE").isEmpty());
        assertTrue(DeviceDirectoryView.fromPresences(List.of(ru),Long.MAX_VALUE).isEmpty());
        assertEquals(1,DeviceDirectoryView.fromPresences(List.of(ru,ru),now).size());
    }
}
