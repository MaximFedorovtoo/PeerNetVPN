package org.uzel.app;

import java.io.*;
import java.util.function.BooleanSupplier;
import org.uzel.core.UploadMeasurement;

/** Conservative physical uplink estimate. Probe bytes are never shared-traffic credit. */
final class InternetMeasurement {
    static long upload(PhysicalNetwork network,BooleanSupplier cancelled)throws IOException {
        return UploadMeasurement.upload(network::http,cancelled);
    }
}
