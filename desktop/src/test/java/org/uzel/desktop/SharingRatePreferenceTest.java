package org.uzel.desktop;

import org.junit.Test;
import java.nio.file.*;
import static org.junit.Assert.*;

public class SharingRatePreferenceTest {
    @Test public void unlimitedIsDefaultAndDoesNotNeedAMeasurement()throws Exception {
        Path root=Files.createTempDirectory("peernet-rate-");
        assertEquals(0,SharingRatePreference.load(root).bytesPerSecond());
        assertEquals(0,SharingRatePreference.unlimited().effectiveBytesPerSecond(0));
    }
    @Test public void manualMbpsRejectsBelowCeilingAndKeepsUnknownMeasurementAsDraft() {
        assertEquals(2,SharingRatePreference.minimumBytesPerSecond(4));
        assertEquals(125000,SharingRatePreference.manualMbps("1",0).bytesPerSecond());
        assertThrows(IllegalArgumentException.class,()->SharingRatePreference.manualMbps("1",375001));
        assertEquals(125000,SharingRatePreference.manualMbps("1",375000).bytesPerSecond());
        assertEquals(125001,SharingRatePreference.manualMbps("1.000008",375001).bytesPerSecond());
        assertEquals(1,SharingRatePreference.manualMbps("0.000001",3).bytesPerSecond());
        for(String invalid:new String[]{"0","-1","NaN","Infinity","99999999999999999999999","1e3"})
            assertThrows(invalid,IllegalArgumentException.class,()->SharingRatePreference.manualMbps(invalid,3));
    }
    @Test public void persistedPreferenceIsRevalidatedAgainstLatestUpload()throws Exception {
        Path root=Files.createTempDirectory("peernet-rate-");
        SharingRatePreference rate=SharingRatePreference.manualMbps("2,5",900000);
        rate.save(root);SharingRatePreference loaded=SharingRatePreference.load(root);
        assertEquals(rate,loaded);assertEquals(312500,loaded.bytesPerSecond());
        assertEquals(333334,loaded.effectiveBytesPerSecond(1000000));
        Files.writeString(root.resolve("sharing-rate.properties"),"bytesPerSecond=-1\n");
        assertThrows(java.io.IOException.class,()->SharingRatePreference.load(root));
    }
}
