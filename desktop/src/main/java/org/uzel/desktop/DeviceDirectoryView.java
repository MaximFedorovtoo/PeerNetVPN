package org.uzel.desktop;

import org.uzel.core.DiscoveryPresence;
import java.util.*;

/** An observed subset of signed announcements, never a global or reachability census. */
public final class DeviceDirectoryView {
    public record Device(String fingerprint,String country,int clients,long advertisedUpload,long expiresAt){}
    private DeviceDirectoryView(){}
    public static List<Device> fromPresences(Collection<DiscoveryPresence> presences,long now) {
        Map<String,Device> result=new TreeMap<>();
        for(DiscoveryPresence p:presences)if(p.expiresAt>now)
            result.put(p.fingerprint,new Device(p.fingerprint,p.country,p.clients,p.upload,p.expiresAt));
        return List.copyOf(result.values());
    }
    /** null selects all countries; empty selects announcements without a country. */
    public static List<Device> filter(List<Device> devices,String country) {
        return devices.stream().filter(d->country==null || country.equals(d.country())).toList();
    }
    public static String regionName(String country) {
        return country.isEmpty()?"Регион не указан":new Locale("",country).getDisplayCountry(new Locale("ru"));
    }
}
