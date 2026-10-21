package org.uzel.app;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.test.InstrumentationTestCase;

/** Explicit real-provider test: at most 3 MiB upload; never changes or disconnects a VPN. */
@SuppressWarnings("deprecation")
public final class InternetMeasurementTest extends InstrumentationTestCase {
    public void testUploadOverExistingPhysicalNetwork() throws Exception {
        ConnectivityManager manager=(ConnectivityManager)getInstrumentation().getTargetContext()
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        assertNotNull(manager);
        Network selected=null;NetworkCapabilities selectedCapabilities=null;
        for(Network candidate:manager.getAllNetworks()){
            NetworkCapabilities capabilities=manager.getNetworkCapabilities(candidate);
            if(capabilities==null || !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    || !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN))continue;
            selected=candidate;selectedCapabilities=capabilities;
            if(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))break;
        }
        assertNotNull("No existing physical INTERNET/NOT_VPN network",selected);
        final long started=System.nanoTime();
        long bytesPerSecond=InternetMeasurement.upload(new PhysicalNetwork(selected),
                ()->System.nanoTime()-started>50_000_000_000L);
        assertTrue("Conservative upload bytes/sec must be positive",bytesPerSecond>=3);
        Bundle result=new Bundle();result.putString("upload_bytes_per_second",Long.toString(bytesPerSecond));
        result.putString("physical_transport",selectedCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                ?"wifi":selectedCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)?"cellular":"other");
        result.putString("upload_payload_limit","3145728 bytes including attempted requests");
        getInstrumentation().sendStatus(0,result);
    }
}
