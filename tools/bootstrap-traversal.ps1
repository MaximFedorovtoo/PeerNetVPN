param([string]$ToolsRoot=(Join-Path $PSScriptRoot '../.tools'))
$ErrorActionPreference='Stop'
# Origin: https://github.com/webrtc-sdk/android/releases/tag/v144.7559.15
# MavenCentral publisher io.github.webrtc-sdk, BSD-3-Clause WebRTC Android build.
# SHA256 independently matched the MavenCentral artifact .aar.sha256 sidecar locally.
$version='144.7559.15'
$expected='fc7c8d027eb33860ccbb7847988f7f942d44a92d1f575348bba6c0250cde4071'
$target=Join-Path ([IO.Path]::GetFullPath($ToolsRoot)) 'traversal'
New-Item -ItemType Directory -Force $target | Out-Null
$archive=Join-Path $target "android-$version.aar"
if(!(Test-Path -LiteralPath $archive) -or (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne $expected){
    & curl.exe --fail --location --retry 3 --output $archive "https://repo.maven.apache.org/maven2/io/github/webrtc-sdk/android/$version/android-$version.aar"
    if($LASTEXITCODE -ne 0){throw 'WebRTC download failed'}
}
if((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne $expected){throw 'WebRTC SHA256 mismatch'}
Write-Output "Verified WebRTC $version SHA256 $expected"
