$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Split-Path $PSScriptRoot -Parent))
Push-Location $root
try {
    New-Item -ItemType Directory -Force .tools/search-ui-check | Out-Null
    $cp='.tools/test-libs/junit.jar;.tools/test-libs/hamcrest.jar'
    & '.tools/jdk-17.0.20.1+1/bin/javac.exe' -encoding UTF-8 -cp $cp -d .tools/search-ui-check app/src/main/java/org/uzel/app/SearchPresentation.java tools/verification/SearchPresentationTest.java
    if($LASTEXITCODE -ne 0){throw 'Search UI compilation failed'}
    & '.tools/jdk-17.0.20.1+1/bin/java.exe' -cp ($cp+';.tools/search-ui-check') org.junit.runner.JUnitCore org.uzel.app.SearchPresentationTest
    if($LASTEXITCODE -ne 0){throw 'Search UI regression failed'}
} finally {Pop-Location}
