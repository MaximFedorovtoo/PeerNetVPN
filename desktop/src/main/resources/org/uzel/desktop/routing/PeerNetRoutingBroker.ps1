#requires -Version 5.1
#requires -RunAsAdministrator
[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][ValidatePattern('^PeerNet-Routing-[0-9a-f-]{36}$')][string]$PipeName,
    [Parameter(Mandatory=$true)][ValidateRange(1,2147483647)][int]$ClientPid,
    [Parameter(Mandatory=$true)][long]$ClientCreatedMs,
    [Parameter(Mandatory=$true)][ValidatePattern('^S-1-5-21-[0-9-]+$')][string]$ClientSid
)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$PSModuleAutoLoadingPreference='None'
$systemModules=[IO.Path]::Combine([Environment]::SystemDirectory,'WindowsPowerShell\v1.0\Modules')
foreach($module in @('Microsoft.PowerShell.Management','Microsoft.PowerShell.Utility','Microsoft.PowerShell.Security')){
    Import-Module ([IO.Path]::Combine($systemModules,"$module\$module.psd1")) -ErrorAction Stop
}

function Assert-ProtectedPath([string]$Path) {
    $trusted=@('S-1-5-18','S-1-5-32-544','S-1-5-80-956008885-3418522649-1831038044-1853292631-2271478464')
    $item=Get-Item -LiteralPath $Path -Force
    while($null -ne $item) {
        if($item.Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Untrusted routing installation'}
        $acl=Get-Acl -LiteralPath $item.FullName
        if($acl.GetOwner([Security.Principal.SecurityIdentifier]).Value -notin $trusted){throw 'Untrusted routing owner'}
        $dangerous=0x500D0040
        if($item.FullName -ieq $Path -or $item.FullName -ieq $PSScriptRoot){$dangerous=$dangerous -bor 0x116}
        foreach($rule in $acl.GetAccessRules($true,$true,[Security.Principal.SecurityIdentifier])){
            if($rule.AccessControlType -eq 'Allow' -and
                -not ($rule.PropagationFlags -band [Security.AccessControl.PropagationFlags]::InheritOnly) -and
                $rule.IdentityReference.Value -notin $trusted -and
                ([long]$rule.FileSystemRights -band $dangerous)){throw 'Untrusted routing permissions'}
        }
        if($item -is [IO.FileInfo]){$item=$item.Directory}else{$item=$item.Parent}
    }
}

function ConvertTo-PeerNetConfig($Model) {
    $props=@($Model.PSObject.Properties.Name)
    if((($props|Sort-Object) -join ',') -ne 'bypassLan,excludes,logLevel,proxies'){throw 'Invalid routing request'}
    if($Model.logLevel -cne 'Error' -or $Model.bypassLan -isnot [bool] -or $Model.bypassLan -or @($Model.proxies).Count -ne 1){throw 'Invalid routing request'}
    $p=$Model.proxies[0]
    $expected='appNames,password,socks5ProxyEndpoint,socks5Transport,supportedAddressFamilies,supportedProtocols,username'
    if(((@($p.PSObject.Properties.Name)|Sort-Object) -join ',') -ne $expected){throw 'Invalid routing rule'}
    if($p.socks5ProxyEndpoint -isnot [string] -or $p.socks5ProxyEndpoint -cnotmatch '^127\.0\.0\.1:([1-9][0-9]{0,4})$' -or [int]$Matches[1] -gt 65535){throw 'Invalid local endpoint'}
    if($p.socks5Transport -cne 'TCP' -or (@($p.supportedProtocols) -join ',') -cne 'TCP,UDP' -or
        (@($p.supportedAddressFamilies) -join ',') -cne 'IPv4,IPv6'){throw 'Invalid protocols'}
    foreach($credential in @($p.username,$p.password)){
        if($credential -isnot [string] -or $credential.Length -eq 0 -or [Text.Encoding]::UTF8.GetByteCount($credential) -gt 255){throw 'Invalid credentials'}
    }
    $carriers=@('peernet.exe','uzelio.exe','uzel.exe','java.exe','javaw.exe','proxifyre.exe','powershell.exe','pwsh.exe')
    $names=@($p.appNames)
    if($names.Count -lt 1 -or $names.Count -gt 128){throw 'Select applications'}
    $seen=@{}
    foreach($name in $names){
        if($name -isnot [string] -or $name.Length -gt 260 -or $name -notmatch '^.[^\x00-\x1f\\/:*?"<>|]*\.exe$' -or
            $name -match '[\x00-\x1f\\/:*?"<>|]' -or $name -in $carriers -or $seen.ContainsKey($name)){throw 'Invalid executable name'}
        $seen[$name]=$true
        foreach($carrier in $carriers){
            if($name.IndexOf($carrier,[StringComparison]::OrdinalIgnoreCase) -ge 0){throw 'Executable overlaps carrier exclusion'}
        }
    }
    if((@($Model.excludes) -join ',').ToLowerInvariant() -cne ($carriers -join ',')){throw 'Invalid exclusions'}
    # Rebuild fixed schema; never interpret user data as a command or executable path.
    return ([ordered]@{logLevel='Error';bypassLan=$false;proxies=@([ordered]@{
        appNames=$names;socks5ProxyEndpoint=$p.socks5ProxyEndpoint;username=$p.username;password=$p.password;
        socks5Transport='TCP';supportedProtocols=@('TCP','UDP');supportedAddressFamilies=@('IPv4','IPv6')
    });excludes=$carriers}|ConvertTo-Json -Depth 6 -Compress)
}

$installation=Join-Path ([Environment]::GetFolderPath('ProgramFiles')) 'PeerNet-Routing-2.6.4'
if($PSScriptRoot -ine $installation){throw 'Broker must run from its protected installation'}
Assert-ProtectedPath $installation
$manifest=Get-Content -LiteralPath (Join-Path $installation 'peernet-routing-install.json') -Raw | ConvertFrom-Json
foreach($entry in $manifest.files){
    if($entry.name -match '[\\/:]' -or $entry.sha256 -notmatch '^[a-f0-9]{64}$'){throw 'Invalid installation manifest'}
    $payload=Join-Path $installation $entry.name
    Assert-ProtectedPath $payload
    if((Get-FileHash -LiteralPath $payload -Algorithm SHA256).Hash -ine $entry.sha256){throw 'Routing payload integrity failure'}
}
Add-Type -TypeDefinition @'
using System;
using System.Text;
using System.Diagnostics;
using System.ComponentModel;
using System.IO;
using System.Threading;
using System.Runtime.InteropServices;
using Microsoft.Win32.SafeHandles;
public static class PeerNetPipeClient {
    [DllImport("kernel32.dll",SetLastError=true)]
    public static extern bool GetNamedPipeClientProcessId(SafePipeHandle pipe,out uint client);
}
public sealed class PeerNetChildJob : IDisposable {
    // Topshelf 4.3 emits this only after WhenStarted and native Start have returned successfully.
    private const string ReadyMarker="The ProxiFyreService service is now running, press Control+C to exit.";
    [StructLayout(LayoutKind.Sequential)] private struct SecurityAttributes {
        public int Size; public IntPtr Descriptor;
        [MarshalAs(UnmanagedType.Bool)] public bool Inherit;
    }
    [StructLayout(LayoutKind.Sequential)] private struct BasicLimits {
        public long ProcessTime, JobTime; public uint Flags;
        public UIntPtr MinimumWorkingSet, MaximumWorkingSet; public uint ActiveProcesses;
        public UIntPtr Affinity; public uint Priority, Scheduling;
    }
    [StructLayout(LayoutKind.Sequential)] private struct IoCounters {
        public ulong ReadOps, WriteOps, OtherOps, ReadBytes, WriteBytes, OtherBytes;
    }
    [StructLayout(LayoutKind.Sequential)] private struct ExtendedLimits {
        public BasicLimits Basic; public IoCounters Io;
        public UIntPtr ProcessMemory, JobMemory, PeakProcessMemory, PeakJobMemory;
    }
    [StructLayout(LayoutKind.Sequential, CharSet=CharSet.Unicode)] private struct Startup {
        public int Size; public IntPtr Reserved, Desktop, Title;
        public uint X, Y, Width, Height, XChars, YChars, Fill, Flags;
        public ushort Show, ReservedBytes; public IntPtr ReservedData, Input, Output, Error;
    }
    [StructLayout(LayoutKind.Sequential)] private struct ProcessInformation {
        public IntPtr Process, Thread; public uint Pid, Tid;
    }
    [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)]
    private static extern IntPtr CreateJobObjectW(IntPtr security,string name);
    [DllImport("kernel32.dll",SetLastError=true)]
    private static extern bool SetInformationJobObject(IntPtr job,int kind,ref ExtendedLimits limits,uint size);
    [DllImport("kernel32.dll",SetLastError=true)]
    private static extern bool AssignProcessToJobObject(IntPtr job,IntPtr process);
    [DllImport("kernel32.dll",CharSet=CharSet.Unicode,SetLastError=true)]
    private static extern bool CreateProcessW(string application,StringBuilder command,IntPtr processSecurity,IntPtr threadSecurity,
        bool inherit,uint flags,IntPtr environment,string directory,ref Startup startup,out ProcessInformation process);
    [DllImport("kernel32.dll",SetLastError=true)] private static extern uint ResumeThread(IntPtr thread);
    [DllImport("kernel32.dll",SetLastError=true)]
    private static extern bool CreatePipe(out IntPtr read,out IntPtr write,ref SecurityAttributes security,uint size);
    [DllImport("kernel32.dll",SetLastError=true)]
    private static extern bool SetHandleInformation(IntPtr handle,uint mask,uint flags);
    [DllImport("kernel32.dll")] private static extern bool TerminateProcess(IntPtr process,uint code);
    [DllImport("kernel32.dll")] private static extern bool CloseHandle(IntPtr handle);
    private IntPtr job;
    private FileStream console;
    private Thread drain;
    private readonly ManualResetEvent ready=new ManualResetEvent(false);
    public PeerNetChildJob() {
        job=CreateJobObjectW(IntPtr.Zero,null);
        if(job==IntPtr.Zero)throw new Win32Exception(Marshal.GetLastWin32Error());
        var limits=new ExtendedLimits();limits.Basic.Flags=0x2000;
        if(!SetInformationJobObject(job,9,ref limits,(uint)Marshal.SizeOf(typeof(ExtendedLimits)))) {
            int error=Marshal.GetLastWin32Error();Dispose();throw new Win32Exception(error);
        }
    }
    public Process Start(string executable,string directory) {
        IntPtr read=IntPtr.Zero,write=IntPtr.Zero;
        var security=new SecurityAttributes {Size=Marshal.SizeOf(typeof(SecurityAttributes)),Inherit=true};
        if(!CreatePipe(out read,out write,ref security,4096))throw new Win32Exception(Marshal.GetLastWin32Error());
        ProcessInformation child=new ProcessInformation();
        try {
            // The child inherits only the writer. Parent reader, job and control pipe are not inherited.
            if(!SetHandleInformation(read,1,0))throw new Win32Exception(Marshal.GetLastWin32Error());
            var startup=new Startup();startup.Size=Marshal.SizeOf(typeof(Startup));startup.Flags=0x101;
            startup.Show=0;startup.Output=write;startup.Error=write;
            if(!CreateProcessW(executable,new StringBuilder("\""+executable+"\""),IntPtr.Zero,IntPtr.Zero,true,
                0x08000004,IntPtr.Zero,directory,ref startup,out child))throw new Win32Exception(Marshal.GetLastWin32Error());
            if(!AssignProcessToJobObject(job,child.Process))throw new Win32Exception(Marshal.GetLastWin32Error());
            var process=Process.GetProcessById((int)child.Pid);
            console=new FileStream(new SafeFileHandle(read,true),FileAccess.Read,4096,false);read=IntPtr.Zero;
            drain=new Thread(DrainConsole);drain.IsBackground=true;drain.Name="Uzelio readiness";drain.Start();
            if(ResumeThread(child.Thread)==uint.MaxValue)throw new Win32Exception(Marshal.GetLastWin32Error());
            return process;
        }catch {if(child.Process!=IntPtr.Zero)TerminateProcess(child.Process,1);throw;}
        finally {
            if(read!=IntPtr.Zero)CloseHandle(read);CloseHandle(write);
            if(child.Thread!=IntPtr.Zero)CloseHandle(child.Thread);
            if(child.Process!=IntPtr.Zero)CloseHandle(child.Process);
        }
    }
    private void DrainConsole() {
        try {
            using(var text=new StreamReader(console,Encoding.UTF8,true,1024)) {
                int matched=0,value;
                while((value=text.Read())>=0) {
                    // Retain at most one marker's matching index; never retain or persist log lines.
                    if(value==ReadyMarker[matched]) {
                        if(++matched==ReadyMarker.Length){ready.Set();matched=0;}
                    }else matched=value==ReadyMarker[0]?1:0;
                }
            }
        }catch(IOException){}catch(ObjectDisposedException){}
    }
    public bool WaitReady(int milliseconds) {return ready.WaitOne(milliseconds);}
    public void Dispose() {
        if(job!=IntPtr.Zero){CloseHandle(job);job=IntPtr.Zero;}
        if(drain==null || drain.Join(2000))ready.Close();
        if(console!=null)console.Dispose();
    }
}
'@
$mutexSecurity=[Security.AccessControl.MutexSecurity]::new()
$mutexSecurity.SetSecurityDescriptorSddlForm('O:BAG:SYD:P(A;;GA;;;SY)(A;;GA;;;BA)')
$created=$false
$mutex=[Threading.Mutex]::new($false,'Global\PeerNet-Routing-2.6.4',[ref]$created,$mutexSecurity)
$held=$false;$pipe=$null;$child=$null;$childCreated=$null;$writer=$null;$job=$null
$configPath=Join-Path $installation 'app-config.json'
try {
    try{$held=$mutex.WaitOne(0)}catch [Threading.AbandonedMutexException]{$held=$true}
    if(-not $held){throw 'Another Uzelio routing session is active'}
    # Recover protected credentials from a previous abrupt helper termination before accepting a new request.
    if(Test-Path -LiteralPath $configPath){Assert-ProtectedPath $configPath;Remove-Item -LiteralPath $configPath -Force}
    $security=[IO.Pipes.PipeSecurity]::new()
    $security.SetSecurityDescriptorSddlForm("O:BAG:SYD:P(A;;GA;;;SY)(A;;GA;;;BA)(A;;GRGW;;;$ClientSid)")
    $pipe=[IO.Pipes.NamedPipeServerStream]::new($PipeName,[IO.Pipes.PipeDirection]::InOut,1,
        [IO.Pipes.PipeTransmissionMode]::Byte,[IO.Pipes.PipeOptions]::Asynchronous,65536,65536,$security)
    $waiting=$pipe.BeginWaitForConnection($null,$null)
    if(-not $waiting.AsyncWaitHandle.WaitOne(30000)){throw 'Routing client timed out'}
    $pipe.EndWaitForConnection($waiting);$waiting.AsyncWaitHandle.Dispose()
    [uint32]$actualClient=0
    if(-not [PeerNetPipeClient]::GetNamedPipeClientProcessId($pipe.SafePipeHandle,[ref]$actualClient) -or $actualClient -ne $ClientPid){throw 'Routing client identity mismatch'}
    $parent=Get-Process -Id $ClientPid -ErrorAction Stop
    $parentCreated=[DateTimeOffset]::new($parent.StartTime.ToUniversalTime()).ToUnixTimeMilliseconds()
    if($parentCreated -ne $ClientCreatedMs){throw 'Routing client creation identity mismatch'}
    $reader=[IO.StreamReader]::new($pipe,[Text.UTF8Encoding]::new($false,$true),$false,4096,$true)
    $writer=[IO.StreamWriter]::new($pipe,[Text.UTF8Encoding]::new($false),4096,$true);$writer.AutoFlush=$true
    $request=[Text.StringBuilder]::new()
    # Bound privileged memory and wait time even for a malformed/compromised client.
    $one=[char[]]::new(1)
    do {
        $read=$reader.ReadAsync($one,0,1)
        if(-not $read.Wait(10000)){throw 'Routing request timed out'}
        if($read.Result -eq 0){throw 'Routing client closed'}
        if($one[0] -eq [char]10){break}
        if($request.Length -ge 65536){throw 'Routing request too large'}
        [void]$request.Append($one[0])
    }while($true)
    $configuration=ConvertTo-PeerNetConfig ($request.ToString()|ConvertFrom-Json)
    if(Test-Path -LiteralPath $configPath){Assert-ProtectedPath $configPath;Remove-Item -LiteralPath $configPath -Force}
    $configAcl=[Security.AccessControl.FileSecurity]::new()
    $configAcl.SetSecurityDescriptorSddlForm('O:BAG:SYD:P(A;;FA;;;SY)(A;;FA;;;BA)')
    $configFile=[IO.File]::Create($configPath,4096,[IO.FileOptions]::None,$configAcl)
    try{$bytes=[Text.Encoding]::UTF8.GetBytes($configuration);$configFile.Write($bytes,0,$bytes.Length)}
    finally{$configFile.Dispose();$bytes=$null}
    $request.Clear()|Out-Null;$configuration=$null
    $job=[PeerNetChildJob]::new()
    $child=$job.Start((Join-Path $installation 'ProxiFyre.exe'),$installation)
    $childCreated=$child.StartTime.ToUniversalTime().Ticks
    if(-not $job.WaitReady(15000)){throw 'Routing engine readiness timed out'}
    $child.Refresh()
    if($child.HasExited){throw 'Routing engine failed'}
    $writer.WriteLine('STARTED')
    $stop=$reader.ReadLineAsync()
    while(-not $stop.Wait(250)){
        $child.Refresh()
        if($child.HasExited -or $parent.HasExited){break}
    }
}
catch {
    # Never echo exception data, request content, credentials, command lines or engine logs.
    if($null -ne $writer){try{$writer.WriteLine('FAILED')}catch{}}
}
finally {
    if($null -ne $child){
        try{
            $current=Get-Process -Id $child.Id -ErrorAction Stop
            if($current.StartTime.ToUniversalTime().Ticks -eq $childCreated){$current.Kill();$current.WaitForExit(5000)|Out-Null}
        }catch{}
        $child.Dispose()
    }
    # Closing this job handle also kills its exact child on broker crash/termination; no service/global process kill.
    if($null -ne $job){$job.Dispose()}
    if($held -and (Test-Path -LiteralPath $configPath)){Remove-Item -LiteralPath $configPath -Force}
    if($null -ne $pipe){$pipe.Dispose()}
    if($held){$mutex.ReleaseMutex()}
    $mutex.Dispose()
}
