package org.uzel.desktop;

import org.uzel.desktop.routing.WindowsNative;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.security.*;

/** One-time, explicit installer action. The broker used by ordinary connections is always protected. */
public final class WindowsRoutingSetup {
    private WindowsRoutingSetup(){}
    public static void install()throws IOException {
        if(!new WindowsAppRouter().availability().supported())throw new IOException("Нужна Windows x64");
        Path stage=Files.createTempDirectory("PeerNet-Routing-Setup-");
        Path script=stage.resolve("tools/setup-windows-routing.ps1");
        Path broker=stage.resolve("desktop/src/main/resources/org/uzel/desktop/routing/PeerNetRoutingBroker.ps1");
        byte[] setupBytes=extract("/setup-windows-routing.ps1",script);
        byte[] brokerBytes=extract("/org/uzel/desktop/routing/PeerNetRoutingBroker.ps1",broker);
        String bootstrap=verifiedBootstrap(script,broker,setupBytes,brokerBytes);
        String encoded=Base64.getEncoder().encodeToString(bootstrap.getBytes(StandardCharsets.UTF_16LE));
        String command="$ErrorActionPreference='Stop'; try {$p=Start-Process -FilePath '"+
            WindowsNative.powershell().toString().replace("'","''")+"' -Verb RunAs -WindowStyle Hidden -PassThru -Wait -ArgumentList "+
            "@('-NoProfile','-NonInteractive','-EncodedCommand','"+encoded+"'); exit $p.ExitCode}catch{exit 1223}";
        Process process=new ProcessBuilder(WindowsNative.powershell().toString(),"-NoProfile","-NonInteractive","-EncodedCommand",
            Base64.getEncoder().encodeToString(command.getBytes(StandardCharsets.UTF_16LE)))
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try{
            // Do not terminate an in-progress driver installation on UI cancellation.
            int result=process.waitFor();
            if(result!=0)throw new IOException(result==1223?"Установка не разрешена Windows":"Не удалось установить сетевой компонент Windows");
            if(!new WindowsAppRouter().availability().installed())throw new IOException("Установка не завершена; проверьте запрос Windows");
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new InterruptedIOException("Установка ещё может выполняться");}
        finally{
            if(!process.isAlive())for(Path file:List.of(script,broker)){Files.deleteIfExists(file);Path parent=file.getParent();while(parent!=null&&parent.startsWith(stage)){try{Files.deleteIfExists(parent);}catch(DirectoryNotEmptyException busy){break;}parent=parent.getParent();}}
        }
    }
    static String verifiedBootstrap(Path script,Path broker,byte[] setupBytes,byte[] brokerBytes)throws IOException {
        // Capture first, compare to hashes carried by the running application, execute those SAME bytes.
        // No elevated -File invocation and no path is opened a second time after validation.
        return "$ErrorActionPreference='Stop';try{$s=[IO.File]::ReadAllBytes('"+script.toString().replace("'","''")+"');"+
            "$b=[IO.File]::ReadAllBytes('"+broker.toString().replace("'","''")+"');"+
            "$h=[Security.Cryptography.SHA256]::Create();"+
            "if([BitConverter]::ToString($h.ComputeHash($s)).Replace('-','') -cne '"+hash(setupBytes)+"'){throw 'Setup integrity failure'};"+
            "if([BitConverter]::ToString($h.ComputeHash($b)).Replace('-','') -cne '"+hash(brokerBytes)+"'){throw 'Broker integrity failure'};"+
            "$h.Dispose();$code=[ScriptBlock]::Create([Text.Encoding]::UTF8.GetString($s));"+
            "& $code -AcceptThirdPartyLicenses -InstallPrerequisites -BrokerPayload $b;exit 0}catch{exit 1}";
    }
    private static String hash(byte[] bytes)throws IOException {try{return HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException e){throw new IOException(e);}}
    private static byte[] extract(String resource,Path destination)throws IOException {
        Files.createDirectories(destination.getParent());
        try(InputStream stream=WindowsRoutingSetup.class.getResourceAsStream(resource)){
            if(stream==null)throw new IOException("В сборке отсутствует установщик Windows");byte[] bytes=stream.readAllBytes();Files.write(destination,bytes,StandardOpenOption.CREATE_NEW);return bytes;
        }
    }
}
