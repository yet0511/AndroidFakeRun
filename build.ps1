#requires -Version 5.1
param([switch]$SkipBootstrap)
$ErrorActionPreference = 'Stop'
$Root = $PSScriptRoot
$Workspace = Split-Path $Root -Parent

if (-not $SkipBootstrap) {
    & (Join-Path $Workspace 'tools\bootstrap-toolchain.ps1')
    if ($LASTEXITCODE -ne 0) { throw 'Toolchain bootstrap failed.' }
}

$env:JAVA_HOME = 'C:\envs\Java\jdk-17'
$env:ANDROID_HOME = Join-Path $Workspace '.toolchain\android-sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$Gradle = Join-Path $Workspace '.toolchain\gradle-8.9\bin\gradle.bat'
$Dotnet = Join-Path $Workspace '.toolchain\dotnet\dotnet.exe'
$AdbDir = Join-Path $env:ANDROID_HOME 'platform-tools'
$Output = Join-Path $Root 'publish\win-x64'

& $Gradle -p (Join-Path $Root 'companion') assembleRelease
if ($LASTEXITCODE -ne 0) { throw 'Android companion build failed.' }

& $Dotnet publish (Join-Path $Root 'desktop\AndroidFakeRun.csproj') -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -o $Output
if ($LASTEXITCODE -ne 0) { throw 'Windows desktop build failed.' }

New-Item -ItemType Directory -Force -Path (Join-Path $Output 'platform-tools') | Out-Null
Copy-Item (Join-Path $AdbDir '*') (Join-Path $Output 'platform-tools') -Recurse -Force
New-Item -ItemType Directory -Force -Path (Join-Path $Output 'companion') | Out-Null
Copy-Item (Join-Path $Root 'companion\app\build\outputs\apk\release\app-release.apk') (Join-Path $Output 'companion\AndroidFakeRun.Companion.apk') -Force
Copy-Item (Join-Path $Root 'README.md') (Join-Path $Output '使用说明.md') -Force
Write-Output "Build complete: $Output"
