$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Split-Path $PSScriptRoot -Parent))
Push-Location $root
try {
    $output='.tools/android-connection-check'
    New-Item -ItemType Directory -Force "$output/stubs/android/net","$output/stubs/org/uzel/app","$output/classes" | Out-Null
    # Only replace platform permission/installed-package queries; the controller and TLS lifecycle stay real.
    Set-Content -Encoding UTF8 "$output/stubs/android/net/VpnService.java" 'package android.net; public class VpnService extends android.app.Service { public static android.content.Intent prepare(android.content.Context context){return null;} public android.os.IBinder onBind(android.content.Intent value){return null;} }'
    Set-Content -Encoding UTF8 "$output/stubs/org/uzel/app/AppSelection.java" 'package org.uzel.app; public final class AppSelection { public static boolean vpnReady(android.content.Context context){return true;} public static java.util.List<String> validPackages(android.content.Context context){return java.util.List.of("test.selected.app");} }'
    $cp='core/build/classes/java/main;core/build/classes/java/test;app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes;.tools/android-sdk/platforms/android-35/android.jar;.tools/test-libs/junit.jar;.tools/test-libs/hamcrest.jar'
    & '.tools/jdk-17.0.20.1+1/bin/javac.exe' -encoding UTF-8 -cp $cp -d "$output/classes" "$output/stubs/android/net/VpnService.java" "$output/stubs/org/uzel/app/AppSelection.java" tools/verification/AndroidConnectionRegressionTest.java
    if($LASTEXITCODE -ne 0){throw 'Regression compilation failed; build Android debug and core tests first'}
    & '.tools/jdk-17.0.20.1+1/bin/java.exe' -cp ("$output/classes;"+$cp) org.junit.runner.JUnitCore org.uzel.core.AndroidConnectionRegressionTest
    if($LASTEXITCODE -ne 0){throw 'Android connection regression failed'}
} finally {Pop-Location}
