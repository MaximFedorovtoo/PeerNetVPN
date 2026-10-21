package org.uzel.core;

/** Public, secret-free endpoint. At most one relay hop is supported. */
public final class PeerRoute {
    public final String host,fingerprint;
    public final int port;
    public final PeerRoute relay;
    final boolean discovered;
    private PeerRoute(String host,int port,String fingerprint,PeerRoute relay) {
        this(host,port,fingerprint,relay,false);
    }
    private PeerRoute(String host,int port,String fingerprint,PeerRoute relay,boolean discovered) {
        // Reuse the strict endpoint and fingerprint syntax, never an invitation secret.
        new Invitation(host,port,fingerprint,fingerprint);
        this.host=host;this.port=port;this.fingerprint=fingerprint;this.relay=relay;this.discovered=discovered;
    }
    PeerRoute asDiscovered(){return new PeerRoute(host,port,fingerprint,relay==null?null:relay.asDiscovered(),true);}
    public static PeerRoute direct(String host,int port,String fingerprint){return new PeerRoute(host,port,fingerprint,null);}
    public static PeerRoute viaRelay(PeerRoute relay,String target){
        if(relay==null || relay.isRelay() || relay.fingerprint.equals(target))throw new IllegalArgumentException("Relay route");
        return new PeerRoute(relay.host,relay.port,target,relay);
    }
    public boolean isRelay(){return relay!=null;}
    public String encode(){return "uzel-public://"+(host.contains(":")?"["+host+"]":host)+":"+port+"/"+fingerprint+(relay==null?"":"/"+relay.fingerprint);}
    public static PeerRoute parse(String value){
        if(value==null || value.length()>1024)throw new IllegalArgumentException("Route length");
        try{
            java.net.URI u=new java.net.URI(value);
            if(!"uzel-public".equals(u.getScheme()) || u.getUserInfo()!=null || u.getQuery()!=null || u.getFragment()!=null)throw new IllegalArgumentException("Route");
            String host=u.getHost();if(host!=null && host.startsWith("["))host=host.substring(1,host.length()-1);
            String[] path=u.getPath().split("/",-1);
            if(path.length==2)return direct(host,u.getPort(),path[1]);
            if(path.length==3)return viaRelay(direct(host,u.getPort(),path[2]),path[1]);
            throw new IllegalArgumentException("Route");
        }catch(java.net.URISyntaxException|NullPointerException e){throw new IllegalArgumentException("Route",e);}
    }
}
