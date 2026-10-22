package org.uzel.desktop;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;

public record DesktopConfig(String advertisedHost,int port,boolean allowPrivate) {
    public DesktopConfig {
        advertisedHost=advertisedHost.trim();
        if(advertisedHost.length()>253 || advertisedHost.isEmpty() || advertisedHost.contains("://") ||
                !advertisedHost.matches("[a-zA-Z0-9.:_-]+") || port<1 || port>65535)
            throw new IllegalArgumentException("Укажите имя или IP компьютера и порт от 1 до 65535");
    }
    public static DesktopConfig load(Path root)throws IOException {
        Path file=root.resolve("settings.properties");
        if(!Files.exists(file))return new DesktopConfig("localhost",7777,false);
        Properties p=new Properties();try(InputStream in=Files.newInputStream(file)){p.load(in);}
        try{return new DesktopConfig(p.getProperty("host","localhost"),Integer.parseInt(p.getProperty("port","7777")),Boolean.parseBoolean(p.getProperty("allowPrivate","false")));}
        catch(RuntimeException e){throw new IOException("Повреждены настройки Uzelio",e);}
    }
    public void save(Path root)throws IOException {
        Files.createDirectories(root);Properties p=new Properties();p.setProperty("host",advertisedHost);
        p.setProperty("port",Integer.toString(port));p.setProperty("allowPrivate",Boolean.toString(allowPrivate));
        Path temp=Files.createTempFile(root,"settings-",".tmp");
        try {try(OutputStream out=Files.newOutputStream(temp)){p.store(out,"Uzelio local settings");} IdentityStore.replace(temp,root.resolve("settings.properties"));}
        finally{Files.deleteIfExists(temp);}
    }
}
