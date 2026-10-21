package org.uzel.core;

public final class Invitation {
    public final String host, fingerprint, secret;
    public final int port;
    public Invitation(String host,int port,String fingerprint,String secret) {
        if(host==null || host.isEmpty() || host.length()>253 || !host.matches("[A-Za-z0-9.:%_-]+")
                || host.contains("%") || port<1 || port>65535
                || fingerprint==null || !fingerprint.matches("[0-9a-f]{64}")
                || secret==null || !secret.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid invitation");
        this.host=host; this.port=port; this.fingerprint=fingerprint; this.secret=secret;
    }
    public String encode() {
        return "uzel://"+(host.contains(":")?"["+host+"]":host)+":"+port+"/"+fingerprint+"/"+secret;
    }
    public static Invitation parse(String value) {
        if(value==null || value.length()>1024) throw new IllegalArgumentException("Invalid invitation");
        try {
            java.net.URI u=new java.net.URI(value.trim());
            if(!"uzel".equals(u.getScheme()) || u.getUserInfo()!=null || u.getQuery()!=null
                    || u.getFragment()!=null) throw new IllegalArgumentException("Invalid invitation");
            String[] path=u.getPath().split("/",-1);
            if(path.length!=3) throw new IllegalArgumentException("Invalid invitation");
            String host=u.getHost();
            if(host!=null && host.startsWith("[")) host=host.substring(1,host.length()-1);
            return new Invitation(host,u.getPort(),path[1],path[2]);
        } catch(java.net.URISyntaxException | NullPointerException e) {
            throw new IllegalArgumentException("Invalid invitation");
        }
    }
}
