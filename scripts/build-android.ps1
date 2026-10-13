param([switch]$Unsigned)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskJdk = (Get-ChildItem -LiteralPath (Join-Path $taskRoot 'tools') -Directory | Where-Object Name -Like 'jdk-17*' | Select-Object -First 1).FullName
$taskJava = Join-Path $taskJdk 'bin\java.exe'
$taskJavac = Join-Path $taskJdk 'bin\javac.exe'
$taskSdk = Join-Path $taskRoot 'tools\android-sdk'
$taskBuildTools = (Get-ChildItem -LiteralPath (Join-Path $taskSdk 'build-tools') -Directory | Select-Object -First 1).FullName
$taskAndroidJar = Join-Path $taskSdk 'platforms\android-35\android.jar'
$taskZxing = Join-Path $taskRoot 'tools\downloads\zxing-core-3.5.3.jar'
$taskStamp = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
$taskBuild = Join-Path ([IO.Path]::GetTempPath()) ('VaultBridge-build-' + $taskStamp)
$taskClasses = Join-Path $taskBuild 'classes'
$taskDex = Join-Path $taskBuild 'dex'
$taskDist = Join-Path $taskRoot 'dist'
New-Item -ItemType Directory -Path $taskClasses,$taskDex,$taskDist -Force | Out-Null
$taskResources = Join-Path $taskBuild 'res'
Copy-Item -LiteralPath (Join-Path $taskRoot 'android\res') -Destination $taskResources -Recurse
Copy-Item -LiteralPath (Join-Path $taskRoot 'android\AndroidManifest.xml') -Destination (Join-Path $taskBuild 'AndroidManifest.xml')
Copy-Item -LiteralPath $taskAndroidJar -Destination (Join-Path $taskBuild 'android.jar')
function Assert-TaskExit([string]$Stage) { if ($LASTEXITCODE -ne 0) { throw "Android $Stage failed (exit=$LASTEXITCODE)." } }
$taskSources = @(Get-ChildItem -LiteralPath (Join-Path $taskRoot 'android\src') -Filter '*.java' -Recurse -File | ForEach-Object FullName)
& $taskJavac '-J-Dfile.encoding=UTF-8' -encoding UTF-8 --release 8 -classpath ($taskAndroidJar + ';' + $taskZxing) -d $taskClasses $taskSources
Assert-TaskExit 'Java compilation'
Add-Type -AssemblyName System.IO.Compression.FileSystem
[IO.Compression.ZipFile]::CreateFromDirectory($taskClasses, (Join-Path $taskBuild 'app.jar'), [IO.Compression.CompressionLevel]::Optimal, $false)
& $taskJava -cp (Join-Path $taskBuildTools 'lib\d8.jar') com.android.tools.r8.D8 --release --min-api 26 --lib $taskAndroidJar --output $taskDex (Join-Path $taskBuild 'app.jar') $taskZxing
Assert-TaskExit 'DEX compilation'
& (Join-Path $taskBuildTools 'aapt2.exe') compile --dir $taskResources -o (Join-Path $taskBuild 'resources.zip')
Assert-TaskExit 'resource compilation'
& (Join-Path $taskBuildTools 'aapt2.exe') link -o (Join-Path $taskBuild 'base.apk') --manifest (Join-Path $taskBuild 'AndroidManifest.xml') -I (Join-Path $taskBuild 'android.jar') --min-sdk-version 26 --target-sdk-version 35 --version-code 6 --version-name 0.4.0 --auto-add-overlay (Join-Path $taskBuild 'resources.zip')
Assert-TaskExit 'APK packaging'
$taskArchive = [IO.Compression.ZipFile]::Open((Join-Path $taskBuild 'base.apk'), [IO.Compression.ZipArchiveMode]::Update)
try { [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($taskArchive, (Join-Path $taskDex 'classes.dex'), 'classes.dex', [IO.Compression.CompressionLevel]::Optimal) | Out-Null } finally { $taskArchive.Dispose() }
& (Join-Path $taskBuildTools 'zipalign.exe') -f -p 4 (Join-Path $taskBuild 'base.apk') (Join-Path $taskBuild 'aligned.apk')
Assert-TaskExit 'alignment'
if ($Unsigned) {
    $taskApk = Join-Path $taskDist 'VaultBridge-Android-0.4.0-unsigned.apk'
    Copy-Item -LiteralPath (Join-Path $taskBuild 'aligned.apk') -Destination $taskApk
} else {
    $taskKey = Join-Path $taskRoot 'tools\vaultbridge-local-test.p12'
    # Conventional local test signing, never a user production signing key.
    if (-not (Test-Path -LiteralPath $taskKey)) {
        throw 'Existing local test signing key missing. Use -Unsigned; do not generate or replace a signing key automatically.'
    }
    $taskApk = Join-Path $taskDist 'VaultBridge-Android-0.4.0.apk'
    & $taskJava -jar (Join-Path $taskBuildTools 'lib\apksigner.jar') sign --ks $taskKey --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out $taskApk (Join-Path $taskBuild 'aligned.apk')
    Assert-TaskExit 'signing'
    & $taskJava -jar (Join-Path $taskBuildTools 'lib\apksigner.jar') verify --verbose $taskApk
    Assert-TaskExit 'signature verification'
}
& (Join-Path $taskBuildTools 'aapt2.exe') dump badging $taskApk
Assert-TaskExit 'manifest inspection'
if ($Unsigned) { Write-Output 'Unsigned APK built. Sign with your own key before installing; device behavior remains unverified.' }
else { Write-Output 'APK built and local test signature verified. Device behavior remains unverified.' }
