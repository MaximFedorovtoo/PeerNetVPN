package org.uzel.core;

import java.net.InetAddress;

public final class PublicAddress {
    private PublicAddress() { }
    public static boolean allowed(InetAddress address) {
        if(address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] b=address.getAddress();
        if(b.length==4) {
            int a=b[0]&255,c=b[1]&255,d=b[2]&255;
            if(a==0 || a>=224 || a==127 || a==10 || (a==100 && c>=64 && c<=127)) return false;
            if(a==169 && c==254 || a==172 && c>=16 && c<=31 || a==192 && c==168) return false;
            if(a==192 && c==0 || a==198 && (c==18||c==19||c==51&&d==100)
                    || a==203 && c==0 && d==113) return false;
            return true;
        }
        // Only global unicast 2000::/3, excluding documentation, Teredo and 6to4.
        if ((b[0]&0xe0)!=0x20) return false;
        if ((b[0]&255)==0x20 && (b[1]&255)==0x02) return false;
        if ((b[0]&255)==0x20 && (b[1]&255)==0x01) {
            if(b[2]==0 && b[3]==0) return false;
            if((b[2]&255)==0x0d && (b[3]&255)==0xb8) return false;
        }
        return true;
    }
}
