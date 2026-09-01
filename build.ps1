# One-time setup + build script for Afusekt Unlock LSPosed module.
$ErrorActionPreference = "Stop"
$base = Split-Path -Parent $PSScriptRoot
$sdk = Join-Path $base "android-sdk"
$lsp = $PSScriptRoot

Write-Host "=== Afusekt Unlock build ==="

# 1. Gradle wrapper jar (required, ~63 KB)
$wrapperJar = Join-Path $lsp "gradle\wrapper\gradle-wrapper.jar"
if (-not (Test-Path $wrapperJar) -or (Get-Item $wrapperJar).Length -lt 50000) {
    Write-Host "Downloading gradle-wrapper.jar ..."
    $urls = @(
        "https://github.com/gradle/gradle/raw/v8.2.0/gradle/wrapper/gradle-wrapper.jar",
        "https://raw.githubusercontent.com/gradle/gradle/v8.2.0/gradle/wrapper/gradle-wrapper.jar"
    )
    $ok = $false
    foreach ($url in $urls) {
        try {
            Invoke-WebRequest -Uri $url -OutFile $wrapperJar -TimeoutSec 120
            if ((Get-Item $wrapperJar).Length -gt 50000) { $ok = $true; break }
        } catch { Write-Host "  failed: $url" }
    }
    if (-not $ok) { throw "Could not download gradle-wrapper.jar. Check network or copy it manually." }
}

# 2. Android SDK (platform 34 + build-tools)
$cmdlineTools = Join-Path $sdk "cmdline-tools\latest"
if (-not (Test-Path (Join-Path $cmdlineTools "bin\sdkmanager.bat"))) {
    Write-Host "Installing Android commandline tools ..."
    $zip = Join-Path $sdk "downloads\cmdline-tools.zip"
    New-Item -ItemType Directory -Force -Path (Split-Path $zip) | Out-Null
    Invoke-WebRequest -Uri "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip" -OutFile $zip -TimeoutSec 300
    Expand-Archive -Path $zip -DestinationPath (Join-Path $sdk "cmdline-tools-tmp") -Force
    New-Item -ItemType Directory -Force -Path (Join-Path $sdk "cmdline-tools") | Out-Null
    Move-Item (Join-Path $sdk "cmdline-tools-tmp\cmdline-tools") $cmdlineTools -Force
    Remove-Item -Recurse -Force (Join-Path $sdk "cmdline-tools-tmp")
}

$env:ANDROID_HOME = $sdk
$sdkmanager = Join-Path $cmdlineTools "bin\sdkmanager.bat"
Write-Host "Installing SDK packages ..."
1..20 | ForEach-Object { "y" } | & $sdkmanager --sdk_root=$sdk --licenses | Out-Null
& $sdkmanager --sdk_root=$sdk "platform-tools" "platforms;android-34" "build-tools;34.0.0"

# 3. local.properties
$sdkEscaped = $sdk -replace '\\', '\\'
"sdk.dir=$sdkEscaped" | Set-Content (Join-Path $lsp "local.properties") -Encoding ASCII

# 4. Build
Push-Location $lsp
try {
    & .\gradlew.bat :app:assembleRelease --no-daemon
    $apk = Join-Path $lsp "app\build\outputs\apk\release\app-release.apk"
    if (-not (Test-Path $apk)) {
        $apk = Join-Path $lsp "app\build\outputs\apk\release\app-release-unsigned.apk"
    }
    if (Test-Path $apk) {
        $out = Join-Path $lsp "output\zoevip-lsp.apk"
        New-Item -ItemType Directory -Force -Path (Split-Path $out) | Out-Null
        Copy-Item $apk $out -Force
        Write-Host ""
        Write-Host "Build OK: $out"
    } else {
        throw "APK not found after build"
    }
} finally {
    Pop-Location
}
