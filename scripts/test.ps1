param([switch]$SkipDesktopUi,[string]$JdkDirectory,[string]$ReportDirectory)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'invoke-java.ps1')
$taskStamp = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
$taskReport = if ($ReportDirectory) { [IO.Path]::GetFullPath($ReportDirectory) } else { Join-Path $taskRoot ('work\validation-'+$taskStamp) }
if ($taskReport -match '[^\x00-\x7f]' -and -not $ReportDirectory) { $taskReport = Join-Path ([IO.Path]::GetTempPath()) ('VaultBridge-validation-'+$taskStamp);Write-Output 'Using an ASCII temporary report directory for the retained desktop Java interoperability host.' }
if ($taskReport -match '[^\x00-\x7f]') { throw 'ReportDirectory must be ASCII because the retained desktop self-test launches Java directly.' }
if ((Test-Path -LiteralPath $taskReport) -and @(Get-ChildItem -LiteralPath $taskReport -Force).Count -ne 0) { throw 'Use a new ReportDirectory; earlier test evidence is preserved.' }
New-Item -ItemType Directory -Path $taskReport -Force | Out-Null
$taskExe = Join-Path $taskRoot 'dist\0.5\0.5.2\VaultBridge-Desktop-0.5.2.exe'
$taskJdk = Resolve-VaultBridgeJdk -ProjectRoot $taskRoot -JdkDirectory $JdkDirectory
$taskDependencies = Join-Path $taskReport 'dependencies'
New-Item -ItemType Directory -Path $taskDependencies -Force | Out-Null
$taskZxing = Join-Path $taskDependencies 'zxing-core-3.5.3.jar'
$taskJson = Join-Path $taskDependencies 'json-20240303.jar'
Copy-Item -LiteralPath (Join-Path $taskRoot 'tools\downloads\zxing-core-3.5.3.jar') -Destination $taskZxing
Copy-Item -LiteralPath (Join-Path $taskRoot 'tools\downloads\json-20240303.jar') -Destination $taskJson
$taskAppearanceCases = Join-Path $taskReport 'appearance-cases.json'
Copy-Item -LiteralPath (Join-Path $taskRoot 'tests\appearance-cases.json') -Destination $taskAppearanceCases
$taskClasses = Join-Path $taskReport 'classes'
New-Item -ItemType Directory -Path $taskClasses -Force | Out-Null
$taskJavaSources = @(
    'android/src/io/vaultbridge/app/Engine.java', 'android/src/io/vaultbridge/app/LanClient.java',
    'android/src/io/vaultbridge/app/PathState.java', 'android/src/io/vaultbridge/app/DirectoryEngine.java',
    'android/src/io/vaultbridge/app/LocationState.java', 'android/src/io/vaultbridge/app/AppearanceConfig.java',
    'android/src/io/vaultbridge/app/TransferEngine.java', 'android/src/io/vaultbridge/app/TransferClient.java',
    'android/src/io/vaultbridge/app/TransferLanding.java',
    'android/src/io/vaultbridge/app/FeedbackDraftStore.java',
    'android/src/io/vaultbridge/app/SendPickerModel.java', 'android/src/io/vaultbridge/app/SourceSessionModel.java',
    'tests/SendPickerModelTest.java', 'tests/SourceSessionModelTest.java',
    'android/src/io/vaultbridge/app/AccentDraft.java', 'tests/AccentDraftTest.java',
    'android/src/io/vaultbridge/app/UpdateInfo.java', 'android/src/io/vaultbridge/app/UpdateClient.java',
    'android/src/io/vaultbridge/app/RecentFiles.java', 'android/src/io/vaultbridge/app/PreviewRules.java',
    'android/src/io/vaultbridge/app/AppearancePreferences.java', 'android/src/io/vaultbridge/app/FeedbackClient.java', 'android/src/io/vaultbridge/app/UiTheme.java', 'tests/SettingsFeedbackTest.java',
    'tests/DirectoryTest.java', 'tests/EngineTest.java', 'tests/Base64.java', 'tests/LanInteropTest.java',
    'tests/AppearanceTest.java', 'tests/TransferTest.java', 'tests/TransferLandingTest.java', 'tests/UpdateTest.java',
    'tests/RecentFilesTest.java', 'tests/PreviewRulesTest.java'
) | ForEach-Object { Join-Path $taskRoot $_ }
Invoke-VaultBridgeJava -JdkDirectory $taskJdk -Tool javac -ArgumentDirectory $taskReport -Arguments (@('-encoding','UTF-8','--release','8','-classpath',($taskZxing+';'+$taskJson),'-d',$taskClasses)+$taskJavaSources)
$taskClasspath = $taskClasses + ';' + $taskZxing + ';' + $taskJson
Invoke-VaultBridgeJava -JdkDirectory $taskJdk -ArgumentDirectory $taskReport -Arguments @('-cp',$taskClasspath,'io.vaultbridge.app.SettingsFeedbackTest',$taskReport) | Tee-Object -FilePath (Join-Path $taskReport 'settings-feedback-results.txt')
foreach ($taskTestName in @('SendPickerModelTest','SourceSessionModelTest','AccentDraftTest')) {
    Invoke-VaultBridgeJava -JdkDirectory $taskJdk -ArgumentDirectory $taskReport -Arguments @('-cp',$taskClasspath,('io.vaultbridge.app.'+$taskTestName)) | Tee-Object -FilePath (Join-Path $taskReport ($taskTestName + '-results.txt'))
}
# Starts only a hidden self-test against a new loopback listener and isolated generated notes.
$taskTestProcess = Start-Process -FilePath $taskExe -ArgumentList @('--self-test', ('"' + $taskReport + '"'), ('"' + (Join-Path $taskJdk 'bin\java.exe') + '"'), ('"' + $taskClasspath + '"')) -WindowStyle Hidden -Wait -PassThru
Get-Content -LiteralPath (Join-Path $taskReport 'desktop-results.txt')
if ($taskTestProcess.ExitCode -ne 0) { throw 'Desktop tests failed.' }
Invoke-VaultBridgeJava -JdkDirectory $taskJdk -ArgumentDirectory $taskReport -Arguments @('-cp',$taskClasspath,'io.vaultbridge.app.EngineTest',$taskReport) | Tee-Object -FilePath (Join-Path $taskReport 'android-core-results.txt')
Write-Output "Validation reports: $taskReport"

Invoke-VaultBridgeJava -JdkDirectory $taskJdk -ArgumentDirectory $taskReport -Arguments @('-cp',$taskClasspath,'io.vaultbridge.app.DirectoryTest',$taskReport) | Tee-Object -FilePath (Join-Path $taskReport 'directory-results.txt')

Invoke-VaultBridgeJava -JdkDirectory $taskJdk -ArgumentDirectory $taskReport -Arguments @('-Xmx768m','-cp',$taskClasspath,'io.vaultbridge.app.TransferLandingTest',$taskReport) | Tee-Object -FilePath (Join-Path $taskReport 'android-transfer-landing-results.txt')

Invoke-VaultBridgeJava -JdkDirectory $taskJdk -ArgumentDirectory $taskReport -Arguments @('-cp',$taskClasspath,'io.vaultbridge.app.UpdateTest',$taskReport) | Tee-Object -FilePath (Join-Path $taskReport 'android-update-results.txt')

Invoke-VaultBridgeJava -JdkDirectory $taskJdk -ArgumentDirectory $taskReport -Arguments @('-cp',$taskClasspath,'io.vaultbridge.app.RecentFilesTest',$taskReport) | Tee-Object -FilePath (Join-Path $taskReport 'android-recent-files-results.txt')

Invoke-VaultBridgeJava -JdkDirectory $taskJdk -ArgumentDirectory $taskReport -Arguments @('-cp',$taskClasspath,'io.vaultbridge.app.PreviewRulesTest',$taskReport) | Tee-Object -FilePath (Join-Path $taskReport 'android-preview-rules-results.txt')

if (-not $SkipDesktopUi) {
    # Keep the released Windows bytes intact; its UI fixture path is ../tests from the EXE.
    $taskUiHost = Join-Path $taskReport 'desktop-ui-host'
    $taskUiBin = Join-Path $taskUiHost 'bin'
    $taskUiFixtures = Join-Path $taskUiHost 'tests'
    New-Item -ItemType Directory -Path $taskUiBin,$taskUiFixtures -Force | Out-Null
    $taskUiExe = Join-Path $taskUiBin ([IO.Path]::GetFileName($taskExe))
    Copy-Item -LiteralPath $taskExe -Destination $taskUiExe
    Copy-Item -LiteralPath ($taskExe + '.config') -Destination ($taskUiExe + '.config')
    Copy-Item -LiteralPath (Join-Path $taskRoot 'tests\appearance-cases.json') -Destination (Join-Path $taskUiFixtures 'appearance-cases.json')
    $taskUiProcess = Start-Process -FilePath $taskUiExe -ArgumentList @('--ui-test', ('"' + $taskReport + '"')) -WindowStyle Hidden -Wait -PassThru
    Get-Content -LiteralPath (Join-Path $taskReport 'appearance-results.txt')
    if ($taskUiProcess.ExitCode -ne 0) { throw 'Native appearance checks failed.' }
}

Invoke-VaultBridgeJava -JdkDirectory $taskJdk -ArgumentDirectory $taskReport -Arguments @('-cp',$taskClasspath,'io.vaultbridge.app.AppearanceTest',$taskAppearanceCases) | Tee-Object -FilePath (Join-Path $taskReport 'android-appearance-results.txt')

Invoke-VaultBridgeJava -JdkDirectory $taskJdk -Tool javac -ArgumentDirectory $taskReport -Arguments @('-encoding','UTF-8','--release','8','-classpath',$taskClasspath,'-d',$taskClasses,(Join-Path $taskRoot 'android\src\io\vaultbridge\app\UiTheme.java'),(Join-Path $taskRoot 'tests\UiThemeTest.java'))
Invoke-VaultBridgeJava -JdkDirectory $taskJdk -ArgumentDirectory $taskReport -Arguments @('-cp',$taskClasspath,'io.vaultbridge.app.UiThemeTest',$taskAppearanceCases) | Tee-Object -FilePath (Join-Path $taskReport 'android-ui-theme-results.txt')

