param([switch]$Unsigned,[string]$JdkDirectory)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'invoke-java.ps1')
$taskJdk = Resolve-VaultBridgeJdk -ProjectRoot $taskRoot -JdkDirectory $JdkDirectory
$taskSdk = Join-Path $taskRoot 'tools\android-sdk'
$taskBuildTools = (Get-ChildItem -LiteralPath (Join-Path $taskSdk 'build-tools') -Directory | Select-Object -First 1).FullName
$taskAndroidJar = Join-Path $taskSdk 'platforms\android-35\android.jar'
$taskZxing = Join-Path $taskRoot 'tools\downloads\zxing-core-3.5.3.jar'
$taskStamp = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
$taskBuild = Join-Path ([IO.Path]::GetTempPath()) ('VaultBridge-build-' + $taskStamp)
$taskClasses = Join-Path $taskBuild 'classes'
$taskDex = Join-Path $taskBuild 'dex'
$taskDist = Join-Path $taskRoot 'dist\0.5\0.5.2'
New-Item -ItemType Directory -Path $taskClasses,$taskDex,$taskDist -Force | Out-Null
$taskResources = Join-Path $taskBuild 'res'
Copy-Item -LiteralPath (Join-Path $taskRoot 'android\res') -Destination $taskResources -Recurse
Copy-Item -LiteralPath (Join-Path $taskRoot 'android\AndroidManifest.xml') -Destination (Join-Path $taskBuild 'AndroidManifest.xml')
Copy-Item -LiteralPath $taskAndroidJar -Destination (Join-Path $taskBuild 'android.jar')
function Assert-TaskExit([string]$Stage) { if ($LASTEXITCODE -ne 0) { throw "Android $Stage failed (exit=$LASTEXITCODE)." } }
$taskSources = @(Get-ChildItem -LiteralPath (Join-Path $taskRoot 'android\src') -Filter '*.java' -Recurse -File | ForEach-Object FullName)
Invoke-VaultBridgeJava -JdkDirectory $taskJdk -Tool javac -ArgumentDirectory $taskBuild -Arguments (@('-encoding','UTF-8','--release','8','-classpath',($taskAndroidJar+';'+$taskZxing),'-d',$taskClasses)+$taskSources)
Add-Type -AssemblyName System.IO.Compression.FileSystem
[IO.Compression.ZipFile]::CreateFromDirectory($taskClasses, (Join-Path $taskBuild 'app.jar'), [IO.Compression.CompressionLevel]::Optimal, $false)
Invoke-VaultBridgeJava -JdkDirectory $taskJdk -ArgumentDirectory $taskBuild -Arguments @('-cp',(Join-Path $taskBuildTools 'lib\d8.jar'),'com.android.tools.r8.D8','--release','--min-api','26','--lib',$taskAndroidJar,'--output',$taskDex,(Join-Path $taskBuild 'app.jar'),$taskZxing)
& (Join-Path $taskBuildTools 'aapt2.exe') compile --dir $taskResources -o (Join-Path $taskBuild 'resources.zip')
Assert-TaskExit 'resource compilation'
& (Join-Path $taskBuildTools 'aapt2.exe') link -o (Join-Path $taskBuild 'base.apk') --manifest (Join-Path $taskBuild 'AndroidManifest.xml') -I (Join-Path $taskBuild 'android.jar') --min-sdk-version 26 --target-sdk-version 35 --version-code 12 --version-name 0.5.2 --auto-add-overlay (Join-Path $taskBuild 'resources.zip')
Assert-TaskExit 'APK packaging'
$taskArchive = [IO.Compression.ZipFile]::Open((Join-Path $taskBuild 'base.apk'), [IO.Compression.ZipArchiveMode]::Update)
try { [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($taskArchive, (Join-Path $taskDex 'classes.dex'), 'classes.dex', [IO.Compression.CompressionLevel]::Optimal) | Out-Null } finally { $taskArchive.Dispose() }
& (Join-Path $taskBuildTools 'zipalign.exe') -f -p 4 (Join-Path $taskBuild 'base.apk') (Join-Path $taskBuild 'aligned.apk')
Assert-TaskExit 'alignment'
if ($Unsigned) {
    $taskApk = Join-Path $taskDist 'VaultBridge-Android-0.5.2-unsigned.apk'
    Copy-Item -LiteralPath (Join-Path $taskBuild 'aligned.apk') -Destination $taskApk
} else {
    $taskKey = Join-Path $taskRoot 'tools\vaultbridge-local-test.p12'
    # Conventional local test signing, never a user production signing key.
    if (-not (Test-Path -LiteralPath $taskKey)) {
        throw 'Existing local test signing key missing. Use -Unsigned; do not generate or replace a signing key automatically.'
    }
    $taskApk = Join-Path $taskDist 'VaultBridge-Android-0.5.2.apk'
    Invoke-VaultBridgeJava -JdkDirectory $taskJdk -ArgumentDirectory $taskBuild -Arguments @('-jar',(Join-Path $taskBuildTools 'lib\apksigner.jar'),'sign','--ks',$taskKey,'--ks-key-alias','androiddebugkey','--ks-pass','pass:android','--key-pass','pass:android','--out',$taskApk,(Join-Path $taskBuild 'aligned.apk'))
    Invoke-VaultBridgeJava -JdkDirectory $taskJdk -ArgumentDirectory $taskBuild -Arguments @('-jar',(Join-Path $taskBuildTools 'lib\apksigner.jar'),'verify','--verbose',$taskApk)
}
$taskBadging = & (Join-Path $taskBuildTools 'aapt2.exe') dump badging $taskApk
Assert-TaskExit 'manifest inspection'
$taskBadging | Write-Output
if (-not ($taskBadging -match "^package: name='io.vaultbridge.app' versionCode='12' versionName='0.5.2'")) { throw 'Packaged identity/version differs from the intended local release; do not distribute.' }
if ($Unsigned) { Write-Output 'Unsigned APK built. Sign with your own key before installing; device behavior remains unverified.' }
else { Write-Output 'APK built and local test signature verified. Device behavior remains unverified.' }
