package org.uzel.desktop;

import org.uzel.desktop.routing.WindowsRoutingConfig;
import org.uzel.desktop.routing.WindowsNative;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Call activate/deactivate on a worker: activation requires UAC.
 * Matching is machine-wide by executable basename, including other installations/users with that name.
 * This is not a VPN kill switch: shared DNS, fragmented IPv6 and helper failure can remain direct.
 */
public final class WindowsAppRouter implements AutoCloseable {
    public record Availability(boolean supported,boolean installed,String detail){}
    public record Snapshot(boolean active,String status,String detail){}
    // Protected installation and IPC names are retained to update existing installations safely.
    private static final String INSTALL_NAME="PeerNet-Routing-2.6.4";
    private Connection connection;
    private long generation;
    private Snapshot state=new Snapshot(false,"OFF","");
    private final Path installation;
    public WindowsAppRouter(){
        Path directory=Path.of("C:\\Program Files");
        if(System.getProperty("os.name","").toLowerCase(Locale.ROOT).startsWith("windows"))
            try{directory=WindowsNative.programFiles();}catch(IOException failure){directory=Path.of("C:\\Unavailable-Windows-Routing");}
        installation=directory.resolve(INSTALL_NAME);
    }
    public WindowsAppRouter(Path userDataRoot){this();Objects.requireNonNull(userDataRoot);}
    public Availability availability(){
        boolean supported=System.getProperty("os.name","").toLowerCase(Locale.ROOT).startsWith("windows") &&
            Set.of("amd64","x86_64").contains(System.getProperty("os.arch","").toLowerCase(Locale.ROOT));
        boolean present=supported && Files.isRegularFile(installation.resolve("PeerNetRoutingBroker.ps1")) &&
            Files.isRegularFile(installation.resolve("ProxiFyre.exe")) && Files.isRegularFile(installation.resolve("peernet-routing-install.json"));
        boolean installed=present && brokerMatchesBundled(installation.resolve("PeerNetRoutingBroker.ps1"));
        return new Availability(supported,installed,!supported?"WINDOWS_X64_REQUIRED":installed?"READY":present?"UPDATE_WINDOWS_ROUTING_SETUP":"RUN_WINDOWS_ROUTING_SETUP");
    }
    static boolean brokerMatchesBundled(Path broker){
        try(InputStream expected=WindowsAppRouter.class.getResourceAsStream("/org/uzel/desktop/routing/PeerNetRoutingBroker.ps1")){
            if(expected==null)return false;
            byte[] bytes=expected.readAllBytes();
            return Files.size(broker)==bytes.length && Arrays.equals(bytes,Files.readAllBytes(broker));
        }catch(IOException unavailable){return false;}
    }
    public synchronized Snapshot snapshot(){return state;}
    public void activate(List<Path> selected,DesktopTrafficService.ProxyEndpoint endpoint)throws IOException {
        deactivate();Objects.requireNonNull(endpoint);
        final long epoch;
        synchronized(this){epoch=++generation;}
        if(!availability().installed())throw new IOException(availability().detail());
        List<String> names=new ArrayList<>();
        if(selected==null)throw new IOException("Select applications");
        for(Path path:selected){
            if(path==null || !path.isAbsolute() || !Files.isRegularFile(path))throw new IOException("Application executable unavailable");
            Path canonical=path.toRealPath();
            if(!canonical.equals(path.normalize()))throw new IOException("Select the canonical application executable");
            names.add(canonical.getFileName().toString());
        }
        final String configuration;
        try{configuration=WindowsRoutingConfig.generate(names,endpoint.host(),endpoint.port(),endpoint.username(),endpoint.password());}
        catch(IllegalArgumentException invalid){throw new IOException(invalid.getMessage());}
        String name="PeerNet-Routing-"+UUID.randomUUID();
        long clientPid=ProcessHandle.current().pid();
        long created=ProcessHandle.current().info().startInstant().orElseThrow(()->new IOException("Process identity unavailable")).toEpochMilli();
        String broker=installation.resolve("PeerNetRoutingBroker.ps1").toString().replace("'","''");
        String brokerCommand="$ErrorActionPreference='Stop';try{Import-Module ([IO.Path]::Combine([Environment]::SystemDirectory,'WindowsPowerShell\\v1.0\\Modules\\Microsoft.PowerShell.Management\\Microsoft.PowerShell.Management.psd1')); & '"+broker+"' -PipeName '"+name+"' -ClientPid "+clientPid+" -ClientCreatedMs "+created+" -ClientSid 'PEERNET_CLIENT_SID'}catch{[IO.File]::WriteAllText('"+installation.resolve("startup-error.txt").toString().replace("'","''")+"',$_.Exception.Message);exit 1}";
        String command="$ErrorActionPreference='Stop'; $PSModuleAutoLoadingPreference='None'; "+
            "$modules=[IO.Path]::Combine([Environment]::SystemDirectory,'WindowsPowerShell\\v1.0\\Modules'); "+
            "foreach($m in @('Microsoft.PowerShell.Management','Microsoft.PowerShell.Security')){Import-Module ([IO.Path]::Combine($modules,($m+'\\'+$m+'.psd1')))}; "+
            "$b='"+broker+"'; $trusted=@('S-1-5-18','S-1-5-32-544','S-1-5-80-956008885-3418522649-1831038044-1853292631-2271478464'); "+
            "$expected=Join-Path ([Environment]::GetFolderPath('ProgramFiles')) 'PeerNet-Routing-2.6.4\\PeerNetRoutingBroker.ps1'; "+
            "if($b -ine $expected){exit 2}; "+
            "for($i=Get-Item -LiteralPath $b -Force;$null -ne $i;){ "+
            "if($i.Attributes -band [IO.FileAttributes]::ReparsePoint){exit 2}; "+
            "$acl=Get-Acl -LiteralPath $i.FullName;if($acl.GetOwner([Security.Principal.SecurityIdentifier]).Value -notin $trusted){exit 2}; "+
            "$mask=0x500D0040;if($i.FullName -ieq $b -or $i.FullName -ieq (Split-Path -Parent $b)){$mask=$mask -bor 0x116}; "+
            "foreach($r in $acl.GetAccessRules($true,$true,[Security.Principal.SecurityIdentifier])){ "+
            "if($r.AccessControlType -eq 'Allow' -and -not ($r.PropagationFlags -band [Security.AccessControl.PropagationFlags]::InheritOnly) "+
            "-and $r.IdentityReference.Value -notin $trusted -and ([long]$r.FileSystemRights -band $mask)){exit 2}}; "+
            "if($i -is [IO.FileInfo]){$i=$i.Directory}else{$i=$i.Parent}}; "+
            "$sid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value; "+
            "$bc='"+brokerCommand.replace("'","''")+"'.Replace('PEERNET_CLIENT_SID',$sid); "+
            "$a=@('-NoProfile','-NonInteractive','-ExecutionPolicy','RemoteSigned','-EncodedCommand',[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($bc))); "+
            "try {$p=Start-Process -FilePath (Join-Path ([Environment]::SystemDirectory) 'WindowsPowerShell\\v1.0\\powershell.exe') "+
            "-Verb RunAs -WindowStyle Hidden -PassThru -ArgumentList $a -ErrorAction Stop; "+
            "$ms=[DateTimeOffset]::new($p.StartTime.ToUniversalTime()).ToUnixTimeMilliseconds(); "+
            "[Console]::WriteLine(('{0}:{1}' -f $p.Id,$ms));exit 0}catch{exit 1}";
        synchronized(this){if(generation!=epoch)throw new InterruptedIOException("Windows routing cancelled");
            state=new Snapshot(false,"AWAITING_UAC","Approve Windows routing access");}
        ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"windows-routing-pipe");t.setDaemon(true);return t;});
        Connection candidate=null;
        try{
            Process launcher=new ProcessBuilder(powershell(),"-NoProfile","-NonInteractive","-EncodedCommand",
                Base64.getEncoder().encodeToString(command.getBytes(StandardCharsets.UTF_16LE)))
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if(!launcher.waitFor(90,TimeUnit.SECONDS)){launcher.destroy();throw new IOException("Windows routing elevation timed out");}
            if(launcher.exitValue()!=0)throw new IOException("Windows routing elevation declined or unavailable");
            String launched=new String(launcher.getInputStream().readNBytes(256),StandardCharsets.US_ASCII).trim();
            if(!launched.matches("[1-9][0-9]*:[0-9]+"))throw new IOException("Windows broker launch identity unavailable");
            String[] identity=launched.split(":");
            long brokerPid=Long.parseLong(identity[0]),brokerCreated=Long.parseLong(identity[1]);
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
            while(candidate==null && System.nanoTime()<deadline){
                synchronized(this){if(generation!=epoch)throw new InterruptedIOException("Windows routing cancelled");}
                try{candidate=new Connection(WindowsNative.openPipe(name,brokerPid,brokerCreated));}
                catch(FileNotFoundException absent){Thread.sleep(100);}
            }
            if(candidate==null)throw new IOException("Windows routing broker unavailable");
            Connection opened=candidate;
            synchronized(this){if(generation!=epoch)throw new InterruptedIOException("Windows routing cancelled");connection=opened;}
            // Secrets travel only through the client-PID-verified local named pipe.
            opened.output.write(configuration);opened.output.newLine();opened.output.flush();
            Future<String> ready=worker.submit(opened.input::readLine);
            String response=ready.get(20,TimeUnit.SECONDS);
            if(!"STARTED".equals(response))throw new IOException("Windows routing engine did not start");
            synchronized(this){if(generation!=epoch)throw new InterruptedIOException("Windows routing cancelled");
                state=new Snapshot(true,"STARTED","Engine is running; verify selected-app traffic. Name rules apply to all Windows users; DNS and fail-direct limits apply");}
            Thread monitor=new Thread(()->monitor(opened,epoch),"windows-routing-monitor");monitor.setDaemon(true);monitor.start();
            candidate=null;
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();failed(epoch);throw new InterruptedIOException("Windows routing interrupted");}
        catch(ExecutionException|TimeoutException failure){failed(epoch);throw new IOException("Windows routing broker did not respond");}
        catch(IOException|RuntimeException failure){failed(epoch);if(failure instanceof IOException io)throw io;throw new IOException("Windows routing failed");}
        finally{if(candidate!=null)candidate.close();worker.shutdownNow();}
    }
    private static String powershell()throws IOException{return WindowsNative.powershell().toString();}
    private void monitor(Connection opened,long epoch){
        try{opened.input.readLine();}catch(IOException ignored){}finally{failed(epoch);opened.close();}
    }
    private synchronized void failed(long epoch){
        if(generation!=epoch)return;
        if(connection!=null)connection.close();connection=null;
        state=new Snapshot(false,"FAILED","Windows routing unavailable; applications may use ordinary routing");
    }
    public void deactivate()throws IOException {
        Connection previous;
        synchronized(this){++generation;previous=connection;connection=null;state=new Snapshot(false,"OFF","");}
        // EOF is the broker's revocation signal, also covering abrupt Java termination.
        if(previous!=null)previous.close();
    }
    private static final class Connection {
        final WindowsNative.Pipe pipe;final BufferedReader input;final BufferedWriter output;
        Connection(WindowsNative.Pipe pipe){this.pipe=pipe;
            input=new BufferedReader(new InputStreamReader(pipe.input(),StandardCharsets.UTF_8));
            output=new BufferedWriter(new OutputStreamWriter(pipe.output(),StandardCharsets.UTF_8));}
        void close(){pipe.close();}
    }
    @Override public void close(){try{deactivate();}catch(IOException ignored){}}
}
