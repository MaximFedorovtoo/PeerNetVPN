package org.uzel.desktop;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;

/** Routing scope is independent from the retained executable selection. */
public enum DesktopRoutingMode {
    SELECTED_APPLICATIONS, ALL_TRAFFIC;

    static DesktopRoutingMode load(Path root)throws IOException {
        Path file=root.resolve("routing-mode.properties");if(!Files.exists(file))return SELECTED_APPLICATIONS;
        if(Files.size(file)>1024)throw new IOException("Повреждён режим подключения");Properties saved=new Properties();
        try(InputStream input=Files.newInputStream(file)){saved.load(input);return valueOf(saved.getProperty("mode","SELECTED_APPLICATIONS"));}
        catch(IllegalArgumentException invalid){throw new IOException("Повреждён режим подключения",invalid);}
    }
    void save(Path root)throws IOException {
        Files.createDirectories(root);Path temporary=Files.createTempFile(root,"routing-mode-",".tmp");
        try{Properties saved=new Properties();saved.setProperty("mode",name());try(OutputStream output=Files.newOutputStream(temporary)){saved.store(output,"Uzelio routing scope");}IdentityStore.replace(temporary,root.resolve("routing-mode.properties"));}
        finally{Files.deleteIfExists(temporary);}
    }
}
