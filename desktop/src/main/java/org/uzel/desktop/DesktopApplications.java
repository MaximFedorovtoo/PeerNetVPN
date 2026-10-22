package org.uzel.desktop;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;

/** Installed/running Windows executables. The traffic driver selects process basenames, not full paths. */
public final class DesktopApplications {
    private static final int MAX_SELECTED=64,MAX_INVENTORY=4096,MAX_OUTPUT_BYTES=8*1024*1024;
    private static final Set<String> CARRIERS=Set.of("peernet.exe","uzelio.exe","uzel.exe","java.exe","javaw.exe","proxifyre.exe","powershell.exe","pwsh.exe");
    /** One process-name group; every process with processName() shares the same routing choice. */
    public record App(String name,Path executable){
        public App {Objects.requireNonNull(name);Objects.requireNonNull(executable);}
        public String processName(){return basename(executable);}
    }
    private DesktopApplications(){}

    public static List<Path> canonicalSelection(List<Path> selection)throws IOException {
        if(selection==null || selection.size()>MAX_SELECTED)throw new IOException("Выберите не более 64 приложений");
        LinkedHashMap<String,Path> paths=new LinkedHashMap<>();
        for(Path value:selection){
            Path path=canonicalExecutable(value);String process=basename(path);Path previous=paths.putIfAbsent(process,path);
            if(previous!=null && !previous.equals(path))throw new IOException("Одинаковое имя процесса у разных файлов: "+process);
        }
        return List.copyOf(paths.values());
    }
    private static Path canonicalExecutable(Path value)throws IOException {
        if(value==null)throw new IOException("Не указан исполняемый файл");
        if(value.toAbsolutePath().toString().startsWith("\\\\"))throw new IOException("Выберите локальный исполняемый файл");
        Path canonical=value.toRealPath();String process=basename(canonical);
        if(canonical.toString().startsWith("\\\\") || !Files.isRegularFile(canonical) || !process.endsWith(".exe") || CARRIERS.stream().anyMatch(process::contains))
            throw new IOException("Недопустимый исполняемый файл приложения: "+canonical.getFileName());
        return canonical;
    }
    private static String basename(Path path){return path.getFileName().toString().toLowerCase(Locale.ROOT);}

    public static List<Path> load(Path root)throws IOException {
        Path file=root.resolve("applications.properties");if(!Files.exists(file))return List.of();
        if(Files.size(file)>256*1024)throw new IOException("Повреждён список приложений");
        Properties properties=new Properties();
        try(InputStream input=Files.newInputStream(file)){properties.load(input);}
        catch(IllegalArgumentException e){throw new IOException("Повреждён список приложений",e);}
        try{
            int count=Integer.parseInt(properties.getProperty("count"));
            if(count<0 || count>MAX_SELECTED)throw new IllegalArgumentException();
            List<Path> selected=new ArrayList<>();for(int i=0;i<count;i++)selected.add(Path.of(Objects.requireNonNull(properties.getProperty("app."+i))));
            return canonicalSelection(selected);
        }catch(RuntimeException e){throw new IOException("Повреждён список приложений",e);}
    }
    public static void save(Path root,List<Path> selection)throws IOException {
        List<Path> canonical=canonicalSelection(selection);Properties properties=new Properties();properties.setProperty("count",Integer.toString(canonical.size()));
        for(int i=0;i<canonical.size();i++)properties.setProperty("app."+i,canonical.get(i).toString());
        Files.createDirectories(root);Path temporary=Files.createTempFile(root,"applications-",".tmp");
        try{
            try(OutputStream output=Files.newOutputStream(temporary)){properties.store(output,"Uzelio process-name selections; selecting one executable covers its basename group");}
            IdentityStore.replace(temporary,root.resolve("applications.properties"));
        }finally{Files.deleteIfExists(temporary);}
    }

    /** Bounded best-effort inventory; missing shortcuts or denied process metadata never invent an entry. */
    public static List<App> discover(){
        if(!System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("windows"))return List.of();
        List<String> inventory=new ArrayList<>(startMenuTargets());
        try(Stream<ProcessHandle> processes=ProcessHandle.allProcesses()){
            processes.limit(MAX_INVENTORY).forEach(process->{
                try{process.info().command().ifPresent(command->{
                    try{Path executable=Path.of(command);inventory.add(encodedLine(executable.getFileName().toString(),executable));}
                    catch(RuntimeException ignored){}
                });}catch(SecurityException ignored){}
            });
        }catch(SecurityException ignored){}
        return decodeInventory(inventory);
    }
    /** Decoder shared by the Start Menu and running-process sources. Invalid or disappeared files are skipped. */
    static List<App> decodeInventory(List<String> lines){
        Map<String,List<App>> groups=new TreeMap<>();Set<Path> seen=new HashSet<>();
        for(String line:lines){
            if(line==null || line.length()>16384)continue;int separator=line.indexOf('\t');if(separator<1)continue;
            try{
                String name=new String(Base64.getDecoder().decode(line.substring(0,separator)),StandardCharsets.UTF_8);
                Path path=canonicalExecutable(Path.of(new String(Base64.getDecoder().decode(line.substring(separator+1)),StandardCharsets.UTF_8)));
                if(name.isBlank() || name.length()>512 || name.chars().anyMatch(Character::isISOControl) || !seen.add(path))continue;
                groups.computeIfAbsent(basename(path),ignored->new ArrayList<>()).add(new App(name,path));
            }catch(IOException|RuntimeException ignored){}
        }
        List<App> result=new ArrayList<>();
        groups.forEach((process,apps)->{
            App representative=apps.get(0);
            String name=apps.size()==1?representative.name():representative.name()+" ["+process+", "+apps.size()+" файлов — группа процессов]";
            result.add(new App(name,representative.executable()));
        });
        result.sort(Comparator.comparing(App::name,String.CASE_INSENSITIVE_ORDER));return List.copyOf(result);
    }
    private static String encodedLine(String name,Path path){
        return Base64.getEncoder().encodeToString(name.getBytes(StandardCharsets.UTF_8))+"\t"+
            Base64.getEncoder().encodeToString(path.toString().getBytes(StandardCharsets.UTF_8));
    }
    // Static source only: no path, label or other user input is interpolated into PowerShell code.
    private static final String INVENTORY_SCRIPT="""
        $ErrorActionPreference = 'Stop'
        $shell = New-Object -ComObject WScript.Shell
        $roots = @((Join-Path $env:ProgramData 'Microsoft\\Windows\\Start Menu\\Programs'), (Join-Path $env:APPDATA 'Microsoft\\Windows\\Start Menu\\Programs'))
        foreach ($root in $roots) {
            Get-ChildItem -LiteralPath $root -Filter '*.lnk' -File -Recurse -ErrorAction SilentlyContinue | Select-Object -First 2048 | ForEach-Object {
                try {
                    $shortcut = $shell.CreateShortcut($_.FullName)
                    $target = $shortcut.TargetPath
                    if ($target -and $target.Length -le 2048 -and $_.BaseName.Length -le 256 -and $target.EndsWith('.exe', [StringComparison]::OrdinalIgnoreCase)) {
                        $name = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($_.BaseName))
                        $path = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($target))
                        [Console]::WriteLine($name + [char]9 + $path)
                    }
                } catch { }
            }
        }
        """;
    private static List<String> startMenuTargets(){
        Process process=null;ExecutorService reader=Executors.newSingleThreadExecutor(task->{Thread thread=new Thread(task,"uzelio-app-inventory");thread.setDaemon(true);return thread;});
        try{
            String encoded=Base64.getEncoder().encodeToString(INVENTORY_SCRIPT.getBytes(StandardCharsets.UTF_16LE));
            process=new ProcessBuilder("powershell.exe","-NoLogo","-NoProfile","-NonInteractive","-WindowStyle","Hidden","-EncodedCommand",encoded).redirectErrorStream(true).start();
            Process running=process;
            Future<byte[]> output=reader.submit(()->{try(InputStream input=running.getInputStream()){
                byte[] bytes=input.readNBytes(MAX_OUTPUT_BYTES+1);if(bytes.length>MAX_OUTPUT_BYTES)throw new IOException("Application inventory too large");return bytes;
            }});
            if(!process.waitFor(20,TimeUnit.SECONDS)){process.destroyForcibly();return List.of();}
            return new String(output.get(1,TimeUnit.SECONDS),StandardCharsets.UTF_8).lines().limit(MAX_INVENTORY).toList();
        }catch(InterruptedException e){Thread.currentThread().interrupt();return List.of();}
        catch(IOException|ExecutionException|TimeoutException e){return List.of();}
        finally{if(process!=null && process.isAlive())process.destroyForcibly();reader.shutdownNow();}
    }
}
