# Executes only the pure validator; never UAC, native imports, the broker or routing.
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$root=Resolve-Path (Join-Path $PSScriptRoot '../../../../../../../..')
$broker=Join-Path $root 'desktop/src/main/resources/org/uzel/desktop/routing/PeerNetRoutingBroker.ps1'
$tokens=$null;$errors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile($broker,[ref]$tokens,[ref]$errors)
if($errors.Count){throw $errors[0].Message}
$function=$ast.Find({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'ConvertTo-PeerNetConfig'},$true)
if($null -eq $function){throw 'Config validator missing'}
. ([scriptblock]::Create($function.Extent.Text))
function New-Model {
    return ('{"logLevel":"Error","bypassLan":false,"proxies":[{"appNames":["firefox.exe"],"socks5ProxyEndpoint":"127.0.0.1:1080","username":"secret-user","password":"secret-password","socks5Transport":"TCP","supportedProtocols":["TCP","UDP"],"supportedAddressFamilies":["IPv4","IPv6"]}],"excludes":["PeerNet.exe","uzelio.exe","uzel.exe","java.exe","javaw.exe","ProxiFyre.exe","powershell.exe","pwsh.exe"]}'|ConvertFrom-Json)
}
$valid=New-Model
$valid.proxies[0].password='quote'+[char]34+'slash\newline'+[char]10
$result=ConvertTo-PeerNetConfig $valid | ConvertFrom-Json
if($result.proxies[0].password -cne $valid.proxies[0].password){throw 'Credential escaping failed'}
$cases=@(
    {param($m) $m.proxies[0].appNames=@('')},
    {param($m) $m.proxies[0].appNames=@('C:\apps\firefox.exe')},
    {param($m) $m.proxies[0].appNames=@('firefox.exe','FIREFOX.exe')},
    {param($m) $m.proxies[0].appNames=@('java.exe')},
    {param($m) $m.proxies[0].appNames=@('myjava.exe')},
    {param($m) $m.proxies[0].appNames=@('PeerNet.exe')},
    {param($m) $m.proxies[0].appNames=@('customPeerNet.exe')},
    {param($m) $m.proxies[0].appNames=@('Uzelio.exe')},
    {param($m) $m.proxies[0].appNames=@('customUZELIO.exe')},
    {param($m) $m.proxies[0].socks5ProxyEndpoint='evil.test:1080'},
    {param($m) $m.proxies[0].socks5ProxyEndpoint='127.0.0.1:65536'},
    {param($m) $m.proxies[0].password=''},
    {param($m) $m.proxies[0].password=([string][char]0x044f)*128},
    {param($m) $m.bypassLan=$true},
    {param($m) $m.proxies[0].supportedAddressFamilies=@('IPv4')},
    {param($m) $m.excludes=@()},
    {param($m) $m.excludes=@($m.excludes | Where-Object {$_ -ine 'uzelio.exe'})},
    {param($m) $m | Add-Member arbitraryExecutable 'C:\evil.exe'}
)
foreach($case in $cases){
    $model=New-Model;& $case $model
    $rejected=$false
    try{ConvertTo-PeerNetConfig $model|Out-Null}catch{$rejected=$true}
    if(-not $rejected){throw 'Unsafe routing model accepted'}
}
Write-Output "Broker schema validation passed: $($cases.Count+1) cases; no elevated code executed"
