# Tests only broker native job definitions with an inert local child; no elevation, network, driver or routing.
$ErrorActionPreference='Stop'
$root=Resolve-Path (Join-Path $PSScriptRoot '../../../../../../../..')
$source=Join-Path $root 'desktop/src/main/resources/org/uzel/desktop/routing/PeerNetRoutingBroker.ps1'
$tokens=$null;$errors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile($source,[ref]$tokens,[ref]$errors)
if($errors.Count){throw $errors[0].Message}
$native=$ast.Find({param($n) $n -is [Management.Automation.Language.StringConstantExpressionAst] -and $n.Value.Contains('public sealed class PeerNetChildJob')},$true)
if($null -eq $native){throw 'Actual broker job definition missing'}
Add-Type -TypeDefinition $native.Value
$testDirectory=Join-Path $root '.tools/windows-routing-job-test'
[IO.Directory]::CreateDirectory($testDirectory)|Out-Null
$childExecutable=Join-Path $testDirectory ('HarmlessWait-'+[guid]::NewGuid().ToString('N')+'.exe')
$childSource=[IO.Path]::ChangeExtension($childExecutable,'.cs')
[IO.File]::WriteAllText($childSource,'public static class HarmlessWait { public static void Main() {System.Threading.Thread.Sleep(500); System.Console.Write(new string(''x'', 100000)); System.Console.WriteLine("The ProxiFyreService service is now running, press Control+C to exit."); System.Threading.Thread.Sleep(60000);} }')
$compiler=Join-Path ([Runtime.InteropServices.RuntimeEnvironment]::GetRuntimeDirectory()) 'csc.exe'
& $compiler /nologo /target:exe ("/out:$childExecutable") $childSource
if($LASTEXITCODE -ne 0){throw 'Inert child compilation failed'}
$job=$null;$child=$null
try{
    $job=[PeerNetChildJob]::new()
    $child=$job.Start($childExecutable,$testDirectory)
    Start-Sleep -Milliseconds 200
    $child.Refresh()
    if($child.HasExited){throw 'Inert child exited unexpectedly'}
    if($job.WaitReady(50)){throw 'Readiness must not be inferred from process liveness'}
    if(-not $job.WaitReady(5000)){throw 'Exact running marker not detected'}
    $job.Dispose();$job=$null
    if(-not $child.WaitForExit(5000)){throw 'Job close did not terminate its owned child'}
    Write-Output 'Broker waited for the exact marker, drained 100KB without storing lines, then killed its owned child; no elevated code executed'
}finally{
    if($null -ne $job){$job.Dispose()}
    if($null -ne $child){$child.Dispose()}
    Remove-Item -LiteralPath $childExecutable -Force
    Remove-Item -LiteralPath $childSource -Force
}
