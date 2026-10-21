$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Split-Path $PSScriptRoot -Parent))
Push-Location $root
try {
    $cp='core/build/classes/java/main;core/build/classes/java/test;app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes;.tools/android-sdk/platforms/android-35/android.jar;.tools/test-libs/junit.jar;.tools/test-libs/hamcrest.jar'
    New-Item -ItemType Directory -Force .tools/discovery-refresh-check | Out-Null
    & '.tools/jdk-17.0.20.1+1/bin/javac.exe' -encoding UTF-8 -cp $cp -d .tools/discovery-refresh-check tools/verification/DiscoveryRefreshRegressionTest.java
    if($LASTEXITCODE -ne 0){throw 'Regression compilation failed; run core:test and app:assembleDebug first'}
    & '.tools/jdk-17.0.20.1+1/bin/java.exe' -cp ($cp+';.tools/discovery-refresh-check') org.junit.runner.JUnitCore org.uzel.core.DiscoveryRefreshRegressionTest
    if($LASTEXITCODE -ne 0){throw 'Regression failed'}
} finally {Pop-Location}
