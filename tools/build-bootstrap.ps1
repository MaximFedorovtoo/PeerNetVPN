$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Split-Path $PSScriptRoot -Parent))
$jdk = Join-Path $root '.tools/jdk-17.0.20.1+1/bin'
$classes = Join-Path $root '.tools/bootstrap-dist'
$output = Join-Path $root 'dist/synology'
New-Item -ItemType Directory -Force $classes,$output | Out-Null
$sources = @(Get-ChildItem (Join-Path $root 'core/src/main/java') -Recurse -Filter *.java | ForEach-Object FullName)
$sources += Join-Path $PSScriptRoot 'bootstrap/BootstrapMain.java'
& "$jdk/javac.exe" -encoding UTF-8 -d $classes $sources
if ($LASTEXITCODE -ne 0) { throw 'Bootstrap compilation failed' }
& "$jdk/jar.exe" --create --file "$output/peernet-bootstrap.jar" --main-class org.uzel.bootstrap.BootstrapMain -C $classes .
if ($LASTEXITCODE -ne 0) { throw 'Bootstrap packaging failed' }
Copy-Item (Join-Path $PSScriptRoot 'bootstrap/docker-compose.yml') "$output/docker-compose.yml"
Get-FileHash "$output/peernet-bootstrap.jar"
