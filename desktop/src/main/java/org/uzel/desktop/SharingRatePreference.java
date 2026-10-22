package org.uzel.desktop;

import java.io.*;
import java.math.*;
import java.nio.file.*;
import java.util.Properties;

/** Reusable preference only. Saving it never enables a traffic backend. Units are decimal Mbit/s. */
public record SharingRatePreference(long bytesPerSecond) {
    public static final long MAX_BYTES_PER_SECOND=125_000_000_000L;
    public SharingRatePreference {
        if(bytesPerSecond<0 || bytesPerSecond>MAX_BYTES_PER_SECOND)
            throw new IllegalArgumentException("Недопустимая скорость раздачи");
    }
    public static SharingRatePreference unlimited(){return new SharingRatePreference(0);}
    public static SharingRatePreference manualMbps(String input,long measuredUploadBytesPerSecond) {
        if(input==null || input.length()>40 || !input.trim().matches("[0-9]+([.,][0-9]+)?"))
            throw new IllegalArgumentException("Введите скорость в Мбит/с, например 10 или 2,5");
        try {
            BigDecimal mbps=new BigDecimal(input.trim().replace(',','.'));
            long bytes=mbps.multiply(BigDecimal.valueOf(125000)).setScale(0,RoundingMode.CEILING).longValueExact();
            if(bytes<=0 || bytes<minimumBytesPerSecond(measuredUploadBytesPerSecond))
                throw new IllegalArgumentException("Лимит не может быть ниже трети измеренной отдачи");
            return new SharingRatePreference(bytes);
        }catch(ArithmeticException e){throw new IllegalArgumentException("Слишком большая скорость",e);}
    }
    public static long minimumBytesPerSecond(long measuredUploadBytesPerSecond) {
        if(measuredUploadBytesPerSecond<0)throw new IllegalArgumentException("Измеренная скорость не может быть отрицательной");
        return measuredUploadBytesPerSecond/3+(measuredUploadBytesPerSecond%3==0?0:1);
    }
    /** After a new probe raises the minimum, match the core's effective floor without losing user intent. */
    public long effectiveBytesPerSecond(long measuredUploadBytesPerSecond) {
        long floor=minimumBytesPerSecond(measuredUploadBytesPerSecond);
        return bytesPerSecond==0?0:Math.max(bytesPerSecond,floor);
    }
    public String mbps(){return BigDecimal.valueOf(bytesPerSecond).multiply(new BigDecimal("0.000008")).stripTrailingZeros().toPlainString();}
    public static SharingRatePreference load(Path root)throws IOException {
        Path file=root.resolve("sharing-rate.properties");
        if(!Files.exists(file))return unlimited();
        Properties properties=new Properties();try(InputStream in=Files.newInputStream(file)){properties.load(in);}
        try{return new SharingRatePreference(Long.parseLong(properties.getProperty("bytesPerSecond","0")));}
        catch(RuntimeException e){throw new IOException("Повреждена настройка скорости раздачи",e);}
    }
    public void save(Path root)throws IOException {
        Files.createDirectories(root);Properties properties=new Properties();properties.setProperty("bytesPerSecond",Long.toString(bytesPerSecond));
        Path temp=Files.createTempFile(root,"sharing-rate-",".tmp");
        try{try(OutputStream out=Files.newOutputStream(temp)){properties.store(out,"Uzelio rate preference; does not enable sharing");}IdentityStore.replace(temp,root.resolve("sharing-rate.properties"));}
        finally{Files.deleteIfExists(temp);}
    }
}
