#requires -Version 5.1
#requires -RunAsAdministrator
<#
.SYNOPSIS
Installs the dedicated Uzelio Windows application router. Run a reviewed copy in Windows PowerShell 5.1.
.DESCRIPTION
Fresh protected Program Files staging only; never registers a persistent elevated task or ProxiFyre service.
ProxiFyre is AGPL-3.0. Its exact corresponding source is downloaded alongside the payload.
The signed Windows Packet Filter driver is separately licensed: personal use is free;
commercial use/distribution requires the appropriate NT Kernel Resources license.
See https://www.ntkernel.com/windows-packet-filter/ and the downloaded source LICENSE.
DNS issued by the Windows shared resolver, fragmented IPv6 and helper crashes can bypass routing.
#>
[CmdletBinding()]
param(
    [switch]$AcceptThirdPartyLicenses,
    [switch]$InstallPrerequisites,
    [byte[]]$BrokerPayload
)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$PSModuleAutoLoadingPreference='None'
$systemModules=[IO.Path]::Combine([Environment]::SystemDirectory,'WindowsPowerShell\v1.0\Modules')
foreach($module in @('Microsoft.PowerShell.Management','Microsoft.PowerShell.Utility','Microsoft.PowerShell.Security','NetSecurity')){
    Import-Module ([IO.Path]::Combine($systemModules,"$module\$module.psd1")) -ErrorAction Stop
}
if($PSVersionTable.PSEdition -ne 'Desktop' -or -not [Environment]::Is64BitProcess -or
    $env:PROCESSOR_ARCHITECTURE -ne 'AMD64'){throw 'Use native x64 Windows PowerShell 5.1'}
if(-not $AcceptThirdPartyLicenses){throw 'Review ProxiFyre AGPL-3.0 and Windows Packet Filter terms, then use -AcceptThirdPartyLicenses'}
$parent=[IO.DirectoryInfo]::new([Environment]::GetFolderPath('ProgramFiles'))
$destination=Join-Path $parent.FullName 'PeerNet-Routing-2.6.4'
if($null -eq $BrokerPayload -or $BrokerPayload.Length -eq 0){
    $brokerSource=Join-Path (Split-Path -Parent $PSScriptRoot) 'desktop\src\main\resources\org\uzel\desktop\routing\PeerNetRoutingBroker.ps1'
    if(-not (Test-Path -LiteralPath $brokerSource -PathType Leaf)){throw 'Uzelio broker source is missing'}
}
$trusted=@('S-1-5-18','S-1-5-32-544','S-1-5-80-956008885-3418522649-1831038044-1853292631-2271478464')
if([IO.DriveInfo]::new($parent.Root.FullName).DriveType -ne 'Fixed'){throw 'Program Files must be on a fixed local drive'}
for($ancestor=$parent;$null -ne $ancestor;$ancestor=$ancestor.Parent){
    if($ancestor.Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Program Files parent chain contains a reparse point'}
    $acl=Get-Acl -LiteralPath $ancestor.FullName
    if($acl.GetOwner([Security.Principal.SecurityIdentifier]).Value -notin $trusted){throw 'Untrusted Program Files parent owner'}
    $dangerous=0x500D0040
    if($ancestor.FullName -eq $parent.FullName){$dangerous=$dangerous -bor 4}
    foreach($rule in $acl.GetAccessRules($true,$true,[Security.Principal.SecurityIdentifier])){
        if($rule.AccessControlType -eq 'Allow' -and
            -not ($rule.PropagationFlags -band [Security.AccessControl.PropagationFlags]::InheritOnly) -and
            $rule.IdentityReference.Value -notin $trusted -and ([long]$rule.FileSystemRights -band $dangerous)){
            throw 'Untrusted Program Files parent mutation permissions'
        }
    }
}
if(Test-Path -LiteralPath $destination){
    # Update only our broker, never overlay binaries or an unrelated installation.
    if($null -eq $BrokerPayload -or $BrokerPayload.Length -eq 0){throw 'Use the Uzelio application to update an existing routing component'}
    function Assert-UpdatePath([string]$Path){
        $item=Get-Item -LiteralPath $Path -Force
        if($item.Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Untrusted update path'}
        $acl=Get-Acl -LiteralPath $Path
        if($acl.GetOwner([Security.Principal.SecurityIdentifier]).Value -notin $trusted){throw 'Untrusted update owner'}
        foreach($rule in $acl.GetAccessRules($true,$true,[Security.Principal.SecurityIdentifier])){
            if($rule.AccessControlType -eq 'Allow' -and -not ($rule.PropagationFlags -band [Security.AccessControl.PropagationFlags]::InheritOnly) -and
                $rule.IdentityReference.Value -notin $trusted -and ([long]$rule.FileSystemRights -band 0x500D0156)){throw 'Untrusted update permissions'}
        }
    }
    Assert-UpdatePath $destination
    $manifestPath=Join-Path $destination 'peernet-routing-install.json'
    Assert-UpdatePath $manifestPath
    $mutexAcl=[Security.AccessControl.MutexSecurity]::new()
    $mutexAcl.SetSecurityDescriptorSddlForm('O:BAG:SYD:P(A;;GA;;;SY)(A;;GA;;;BA)')
    $created=$false;$held=$false
    $mutex=[Threading.Mutex]::new($false,'Global\PeerNet-Routing-2.6.4',[ref]$created,$mutexAcl)
    try{
        try{$held=$mutex.WaitOne(0)}catch [Threading.AbandonedMutexException]{$held=$true}
        if(-not $held){throw 'Disconnect Uzelio before updating the routing component'}
        $manifest=Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
        if($manifest.version -ne '2.6.4' -or $manifest.binarySha256 -ne 'c7da1dc5fdf9aaa7828fbf5c2076c4f612cba7889dcac2e3d06dc2e1dee4c3df'){throw 'Unrecognized routing installation'}
        $hash=[Security.Cryptography.SHA256]::Create()
        try{$newHash=[BitConverter]::ToString($hash.ComputeHash($BrokerPayload)).Replace('-','').ToLowerInvariant()}finally{$hash.Dispose()}
        $brokerEntry=$null
        foreach($entry in $manifest.files){
            if($entry.name -match '[\\/:]' -or $entry.sha256 -notmatch '^[a-f0-9]{64}$'){throw 'Invalid routing manifest'}
            $file=Join-Path $destination $entry.name;Assert-UpdatePath $file
            $actual=(Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash
            # A crash after replacing the broker but before replacing its manifest is recoverable.
            if($actual -ine $entry.sha256 -and -not ($entry.name -ceq 'PeerNetRoutingBroker.ps1' -and $actual -ieq $newHash)){throw 'Installed routing payload was modified'}
            if($entry.name -ceq 'PeerNetRoutingBroker.ps1'){if($null -ne $brokerEntry){throw 'Duplicate broker entry'};$brokerEntry=$entry}
        }
        if($null -eq $brokerEntry){throw 'Missing broker manifest entry'}
        $brokerEntry.sha256=$newHash
        $fileAcl=[Security.AccessControl.FileSecurity]::new()
        $fileAcl.SetSecurityDescriptorSddlForm('O:BAG:SYD:P(A;;FA;;;SY)(A;;FA;;;BA)(A;;GR;;;BU)(A;;RC;;;OW)')
        function Replace-ProtectedFile([string]$Target,[byte[]]$Bytes){
            $temporary=Join-Path $destination ([Guid]::NewGuid().ToString()+'.update')
            try{
                $file=[IO.File]::Create($temporary,4096,[IO.FileOptions]::None,$fileAcl)
                try{$file.Write($Bytes,0,$Bytes.Length);$file.Flush($true)}finally{$file.Dispose()}
                [IO.File]::Replace($temporary,$Target,[NullString]::Value)
            }finally{if([IO.File]::Exists($temporary)){[IO.File]::Delete($temporary)}}
        }
        Replace-ProtectedFile (Join-Path $destination 'PeerNetRoutingBroker.ps1') $BrokerPayload
        Replace-ProtectedFile $manifestPath ([Text.Encoding]::UTF8.GetBytes(($manifest|ConvertTo-Json -Depth 5)))
        Write-Output 'Uzelio protected routing broker updated'
        return
    }catch{[IO.File]::WriteAllText((Join-Path $destination 'update-error.txt'),$_.Exception.Message);throw}
    finally{if($held){$mutex.ReleaseMutex()};$mutex.Dispose()}
}
# Open the reviewed broker without write/delete sharing before staging executable content.
if($null -ne $BrokerPayload -and $BrokerPayload.Length -gt 0){$brokerStream=[IO.MemoryStream]::new($BrokerPayload,$false)}
else{$brokerStream=[IO.File]::Open((Resolve-Path -LiteralPath $brokerSource).Path,[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::Read)}
Add-Type -AssemblyName System.IO.Compression,System.IO.Compression.FileSystem
Add-Type -TypeDefinition @'
using System;
using System.IO;
using System.ComponentModel;
using System.Runtime.InteropServices;
public static class PeerNetProtectedDirectory {
    [StructLayout(LayoutKind.Sequential)] private struct SecurityAttributes {
        public int Length; public IntPtr Descriptor;
        [MarshalAs(UnmanagedType.Bool)] public bool InheritHandle;
    }
    [DllImport("kernel32.dll",CharSet=CharSet.Unicode,ExactSpelling=true,SetLastError=true)]
    private static extern bool CreateDirectoryW(string path,ref SecurityAttributes attributes);
    public static void CreateNew(string path,byte[] descriptor) {
        var pinned=GCHandle.Alloc(descriptor,GCHandleType.Pinned);
        try {
            var attributes=new SecurityAttributes {Length=Marshal.SizeOf(typeof(SecurityAttributes)),Descriptor=pinned.AddrOfPinnedObject()};
            if(!CreateDirectoryW(path,ref attributes))throw new Win32Exception(Marshal.GetLastWin32Error());
        }finally{pinned.Free();}
    }
}
'@
$security=[Security.AccessControl.DirectorySecurity]::new()
# OWNER RIGHTS suppresses the extracting user's implicit ability to rewrite DACLs before the final ownership pass.
$security.SetSecurityDescriptorSddlForm('O:BAG:SYD:P(A;OICI;FA;;;SY)(A;OICI;FA;;;BA)(A;OICI;GRGX;;;BU)(A;OICI;RC;;;OW)')
[PeerNetProtectedDirectory]::CreateNew($destination,$security.GetSecurityDescriptorBinaryForm())
function Get-PinnedFile([string]$Url,[string]$Name,[string]$Hash,[switch]$Microsoft,[switch]$Driver){
    $path=Join-Path $destination $Name
    Invoke-WebRequest -UseBasicParsing -Uri $Url -OutFile $path
    if((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -ine $Hash){throw "Download integrity failure: $Name"}
    if($Microsoft -or $Driver){
        $signature=Get-AuthenticodeSignature -LiteralPath $path
        if($signature.Status -ne 'Valid' -or
            ($Microsoft -and $signature.SignerCertificate.Subject -notmatch '(?:^|, )O=Microsoft Corporation(?:,|$)') -or
            ($Driver -and $signature.SignerCertificate.Subject -notmatch '(?:^|, )O=The Anti-Cloud Corporation(?:,|$)')){
            throw "Publisher verification failed: $Name"
        }
    }
    return $path
}
try{
    $zip=Get-PinnedFile 'https://github.com/wiresock/proxifyre/releases/download/v2.6.4/ProxiFyre-v2.6.4-x64.zip' 'proxifyre-payload.zip' 'c7da1dc5fdf9aaa7828fbf5c2076c4f612cba7889dcac2e3d06dc2e1dee4c3df'
    $source=Get-PinnedFile 'https://codeload.github.com/wiresock/proxifyre/zip/5e3bc845d73a47a0931b1233c745580cb916f4ba' 'proxifyre-corresponding-source.zip' '9d55c4e8ecf4083384fcc70b73d8d986e53e67a12dd0fd00fab058cda0f5bd2c'
    $framework=Get-ItemPropertyValue 'HKLM:\SOFTWARE\Microsoft\NET Framework Setup\NDP\v4\Full' -Name Release
    if($framework -lt 461808){throw 'Install Microsoft .NET Framework 4.7.2 or later'}
    $driverReady=Test-Path 'HKLM:\SYSTEM\CurrentControlSet\Services\NDISRD'
    $runtimeReady=$false
    if(Test-Path 'HKLM:\SOFTWARE\Microsoft\VisualStudio\14.0\VC\Runtimes\x64'){
        $runtimeReady=(Get-ItemPropertyValue 'HKLM:\SOFTWARE\Microsoft\VisualStudio\14.0\VC\Runtimes\x64' -Name Installed) -eq 1
    }
    if(( -not $driverReady -or -not $runtimeReady) -and -not $InstallPrerequisites){throw 'Missing Windows Packet Filter or Visual C++ runtime. Review prerequisites and rerun with -InstallPrerequisites in a fresh destination'}
    if(-not $runtimeReady){
        $runtime=Get-PinnedFile 'https://download.visualstudio.microsoft.com/download/pr/0b44c2d1-8944-4834-a01a-c9a225f8088a/CC0FF0EB1DC3F5188AE6300FAEF32BF5BEEBA4BDD6E8E445A9184072096B713B/VC_redist.x64.exe' 'VC_redist.x64.exe' 'cc0ff0eb1dc3f5188ae6300faef32bf5beeba4bdd6e8e445a9184072096b713b' -Microsoft
        $installed=Start-Process -FilePath $runtime -ArgumentList '/install','/quiet','/norestart' -WindowStyle Hidden -Wait -PassThru
        if($installed.ExitCode -notin @(0,3010)){throw 'Visual C++ runtime installation failed'}
        if($installed.ExitCode -eq 3010){Write-Warning 'Windows restart required before routing'}
        Remove-Item -LiteralPath $runtime -Force
    }
    if(-not $driverReady){
        $driver=Get-PinnedFile 'https://github.com/wiresock/ndisapi/releases/download/v3.6.2/Windows.Packet.Filter.3.6.2.1.x64.msi' 'Windows.Packet.Filter.3.6.2.1.x64.msi' '9c388c0b7f189f7fa98720bae2caecf7d64f30910838b80b438ecf8956b8502c' -Driver
        $installed=Start-Process -FilePath (Join-Path ([Environment]::SystemDirectory) 'msiexec.exe') -ArgumentList @('/i',('"{0}"' -f $driver),'/qn','/norestart') -WindowStyle Hidden -Wait -PassThru
        if($installed.ExitCode -notin @(0,3010)){throw 'Windows Packet Filter installation failed'}
        if($installed.ExitCode -eq 3010){Write-Warning 'Windows restart required before routing'}
        Remove-Item -LiteralPath $driver -Force
    }
    $archive=[IO.Compression.ZipFile]::OpenRead($zip)
    try{
        foreach($entry in $archive.Entries){
            if($entry.FullName -match '[\\/:]' -or [string]::IsNullOrWhiteSpace($entry.FullName)){throw 'Unexpected ZIP layout'}
        }
        [IO.Compression.ZipFileExtensions]::ExtractToDirectory($archive,$destination)
    }finally{$archive.Dispose()}
    Remove-Item -LiteralPath $zip -Force
    $brokerTarget=Join-Path $destination 'PeerNetRoutingBroker.ps1'
    $target=[IO.File]::Open($brokerTarget,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
    try{$brokerStream.CopyTo($target)}finally{$target.Dispose()}
    $files=@(Get-ChildItem -LiteralPath $destination -File | Where-Object {$_.Extension -in @('.exe','.dll','.config','.ps1')} | ForEach-Object{
        [ordered]@{name=$_.Name;sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}
    })
    [ordered]@{version='2.6.4';sourceCommit='5e3bc845d73a47a0931b1233c745580cb916f4ba';
        sourceUrl='https://codeload.github.com/wiresock/proxifyre/zip/5e3bc845d73a47a0931b1233c745580cb916f4ba';
        sourceSha256='9d55c4e8ecf4083384fcc70b73d8d986e53e67a12dd0fd00fab058cda0f5bd2c';
        binarySha256='c7da1dc5fdf9aaa7828fbf5c2076c4f612cba7889dcac2e3d06dc2e1dee4c3df';files=$files
    }|ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $destination 'peernet-routing-install.json') -Encoding UTF8
    & (Join-Path ([Environment]::SystemDirectory) 'icacls.exe') (Join-Path $destination '*') /setowner '*S-1-5-32-544' /T /Q | Out-Null
    if($LASTEXITCODE -ne 0){throw 'Routing payload ownership assignment failed'}
    # Same program-specific inbound exception used by upstream: redirection listeners use dynamic ports.
    foreach($protocol in @('TCP','UDP')){
        New-NetFirewallRule -Name "PeerNet-Routing-2.6.4-$protocol" -DisplayName "Uzelio application routing ($protocol)" -Direction Inbound -Action Allow -Program (Join-Path $destination 'ProxiFyre.exe') -Protocol $protocol -Profile Any | Out-Null
    }
    Write-Output "Installed protected Windows routing helper: $destination"
    Write-Output 'Every connect requires UAC. Rules match executable basenames for all Windows users. DNS, IPv6 fragments, and helper crashes can remain direct.'
}catch{
    [IO.File]::WriteAllText((Join-Path $destination 'setup-error.txt'),$_.Exception.Message)
    # Preserve only the newly created protected directory for inspection; never delete a computed recursive target.
    Write-Warning "Installation incomplete. Inspect the dedicated protected directory: $destination"
    throw
}finally{$brokerStream.Dispose()}
