package org.uzel.desktop.routing;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** ProxiFyre 2.6.4 exact executable-name rules; paths would be substring rules and are forbidden. */
public final class WindowsRoutingConfig {
    private WindowsRoutingConfig(){}
    public static final Set<String> CARRIERS=Set.of("peernet.exe","uzelio.exe","uzel.exe","java.exe","javaw.exe","proxifyre.exe","powershell.exe","pwsh.exe");
    public static String generate(List<String> executableNames,String host,int port,String username,String password){
        if(!"127.0.0.1".equals(host) || port<1 || port>65535)throw new IllegalArgumentException("Local authenticated SOCKS endpoint required");
        credential(username);credential(password);
        if(executableNames==null || executableNames.isEmpty() || executableNames.size()>128)throw new IllegalArgumentException("Select 1 to 128 applications");
        Set<String> seen=new HashSet<>();StringJoiner names=new StringJoiner(",");
        for(String name:executableNames){
            if(name==null || name.isBlank() || name.length()>260 || !name.toLowerCase(Locale.ROOT).endsWith(".exe") ||
                    name.chars().anyMatch(c->c<32 || "\\/:*?\"<>|".indexOf(c)>=0) ||
                    CARRIERS.stream().anyMatch(carrier->name.toLowerCase(Locale.ROOT).contains(carrier)) || !seen.add(name.toLowerCase(Locale.ROOT)))
                throw new IllegalArgumentException("Invalid, duplicate or carrier executable name");
            names.add(quote(name));
        }
        String configuration="{\"logLevel\":\"Error\",\"bypassLan\":false,\"proxies\":[{\"appNames\":["+names+"],"+
            "\"socks5ProxyEndpoint\":"+quote(host+":"+port)+",\"username\":"+quote(username)+",\"password\":"+quote(password)+","+
            "\"socks5Transport\":\"TCP\",\"supportedProtocols\":[\"TCP\",\"UDP\"],\"supportedAddressFamilies\":[\"IPv4\",\"IPv6\"]}],"+
            "\"excludes\":[\"PeerNet.exe\",\"uzelio.exe\",\"uzel.exe\",\"java.exe\",\"javaw.exe\",\"ProxiFyre.exe\",\"powershell.exe\",\"pwsh.exe\"]}";
        if(configuration.getBytes(StandardCharsets.UTF_8).length>65000)throw new IllegalArgumentException("Too many executable names for Windows routing");
        return configuration;
    }
    private static void credential(String value){
        if(value==null || value.isEmpty() || value.getBytes(StandardCharsets.UTF_8).length>255)
            throw new IllegalArgumentException("SOCKS credentials must contain 1 to 255 UTF-8 bytes");
    }
    private static String quote(String value){
        StringBuilder out=new StringBuilder("\"");
        for(char c:value.toCharArray())switch(c){
            case '"'->out.append("\\\"");case '\\'->out.append("\\\\");case '\n'->out.append("\\n");
            case '\r'->out.append("\\r");case '\t'->out.append("\\t");
            default->{if(c<32)out.append(String.format(Locale.ROOT,"\\u%04x",(int)c));else out.append(c);}
        }
        return out.append('"').toString();
    }
}
