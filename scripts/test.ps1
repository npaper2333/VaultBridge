param([switch]$SkipDesktopUi)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskReport = Join-Path $taskRoot ('work\validation-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Path $taskReport -Force | Out-Null
$taskExe = Join-Path $taskRoot 'dist\VaultBridge-Desktop-0.4.0.exe'
$taskJdk = (Get-ChildItem -LiteralPath (Join-Path $taskRoot 'tools') -Directory | Where-Object Name -Like 'jdk-17*' | Select-Object -First 1).FullName
$taskZxing = Join-Path $taskRoot 'tools\downloads\zxing-core-3.5.3.jar'
$taskJson = Join-Path $taskRoot 'tools\downloads\json-20240303.jar'
$taskClasses = Join-Path $taskReport 'classes'
New-Item -ItemType Directory -Path $taskClasses -Force | Out-Null
& (Join-Path $taskJdk 'bin\javac.exe') '-J-Dfile.encoding=UTF-8' -encoding UTF-8 --release 8 -classpath ($taskZxing + ';' + $taskJson) -d $taskClasses (Join-Path $taskRoot 'android\src\io\vaultbridge\app\Engine.java') (Join-Path $taskRoot 'android\src\io\vaultbridge\app\LanClient.java') (Join-Path $taskRoot 'android\src\io\vaultbridge\app\PathState.java') (Join-Path $taskRoot 'android\src\io\vaultbridge\app\DirectoryEngine.java') (Join-Path $taskRoot 'android\src\io\vaultbridge\app\LocationState.java') (Join-Path $taskRoot 'tests\DirectoryTest.java') (Join-Path $taskRoot 'tests\EngineTest.java') (Join-Path $taskRoot 'tests\Base64.java') (Join-Path $taskRoot 'tests\LanInteropTest.java') (Join-Path $taskRoot 'android\src\io\vaultbridge\app\AppearanceConfig.java') (Join-Path $taskRoot 'tests\AppearanceTest.java') (Join-Path $taskRoot 'android\src\io\vaultbridge\app\TransferEngine.java') (Join-Path $taskRoot 'android\src\io\vaultbridge\app\TransferClient.java') (Join-Path $taskRoot 'android\src\io\vaultbridge\app\TransferLanding.java') (Join-Path $taskRoot 'tests\TransferTest.java') (Join-Path $taskRoot 'tests\TransferLandingTest.java')
if ($LASTEXITCODE -ne 0) { throw 'Java test compilation failed.' }
$taskClasspath = $taskClasses + ';' + $taskZxing + ';' + $taskJson
# Starts only a hidden self-test against a new loopback listener and isolated generated notes.
$taskTestProcess = Start-Process -FilePath $taskExe -ArgumentList @('--self-test', ('"' + $taskReport + '"'), ('"' + (Join-Path $taskJdk 'bin\java.exe') + '"'), ('"' + $taskClasspath + '"')) -WindowStyle Hidden -Wait -PassThru
Get-Content -LiteralPath (Join-Path $taskReport 'desktop-results.txt')
if ($taskTestProcess.ExitCode -ne 0) { throw 'Desktop tests failed.' }
& (Join-Path $taskJdk 'bin\java.exe') -cp $taskClasspath io.vaultbridge.app.EngineTest $taskReport | Tee-Object -FilePath (Join-Path $taskReport 'android-core-results.txt')
if ($LASTEXITCODE -ne 0) { throw 'Android core tests failed.' }
Write-Output "Validation reports: $taskReport"

& (Join-Path $taskJdk 'bin\java.exe') -cp $taskClasspath io.vaultbridge.app.DirectoryTest $taskReport | Tee-Object -FilePath (Join-Path $taskReport 'directory-results.txt')
if ($LASTEXITCODE -ne 0) { throw 'Directory tests failed.' }

& (Join-Path $taskJdk 'bin\java.exe') -Xmx768m -cp $taskClasspath io.vaultbridge.app.TransferLandingTest $taskReport | Tee-Object -FilePath (Join-Path $taskReport 'android-transfer-landing-results.txt')
if ($LASTEXITCODE -ne 0) { throw 'Android safe transfer landing checks failed.' }

if (-not $SkipDesktopUi) {
    $taskUiProcess = Start-Process -FilePath $taskExe -ArgumentList @('--ui-test', ('"' + $taskReport + '"')) -WindowStyle Hidden -Wait -PassThru
    Get-Content -LiteralPath (Join-Path $taskReport 'appearance-results.txt')
    if ($taskUiProcess.ExitCode -ne 0) { throw 'Native appearance checks failed.' }
}

& (Join-Path $taskJdk 'bin\java.exe') -cp $taskClasspath io.vaultbridge.app.AppearanceTest (Join-Path $taskRoot 'tests\appearance-cases.json') | Tee-Object -FilePath (Join-Path $taskReport 'android-appearance-results.txt')
if ($LASTEXITCODE -ne 0) { throw 'Android appearance configuration checks failed.' }

& (Join-Path $taskJdk 'bin\javac.exe') '-J-Dfile.encoding=UTF-8' -encoding UTF-8 --release 8 -classpath $taskClasspath -d $taskClasses (Join-Path $taskRoot 'android\src\io\vaultbridge\app\UiTheme.java') (Join-Path $taskRoot 'tests\UiThemeTest.java')
if ($LASTEXITCODE -ne 0) { throw 'Android UI palette test compilation failed.' }
& (Join-Path $taskJdk 'bin\java.exe') -cp $taskClasspath io.vaultbridge.app.UiThemeTest (Join-Path $taskRoot 'tests\appearance-cases.json') | Tee-Object -FilePath (Join-Path $taskReport 'android-ui-theme-results.txt')
if ($LASTEXITCODE -ne 0) { throw 'Android UI palette checks failed.' }

