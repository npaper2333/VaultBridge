param([switch]$AndroidOnly)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskDesktopVersion = '0.5.2'
$taskAndroidVersion = '0.5.2'
$taskStamp = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
$taskDist = Join-Path $taskRoot 'dist\0.5\0.5.2'
$taskDesktopDist = Join-Path $taskRoot 'dist\0.5\0.5.2'
$taskScope = if ($AndroidOnly) { "Android-$taskAndroidVersion" } else { "Android-$taskAndroidVersion-Windows-$taskDesktopVersion" }
$taskPrefix = "VaultBridge-$taskScope-$taskStamp"
$taskStage = Join-Path $taskRoot ('work\packaging-' + $taskScope + '-' + $taskStamp)
$taskApkName = "VaultBridge-Android-$taskAndroidVersion.apk"
$taskExeName = "VaultBridge-Desktop-$taskDesktopVersion.exe"
$taskConfigName = $taskExeName + '.config'
$taskArtifactPaths = @{}
$taskArtifactPaths[$taskApkName] = Join-Path $taskDist $taskApkName
$taskArtifactPaths[$taskExeName] = Join-Path $taskDesktopDist $taskExeName
$taskArtifactPaths[$taskConfigName] = Join-Path $taskDesktopDist $taskConfigName
$taskArtifacts = @($taskApkName)
if (-not $AndroidOnly) { $taskArtifacts += @($taskExeName, $taskConfigName) }
foreach ($taskName in $taskArtifacts) { if (-not (Test-Path -LiteralPath $taskArtifactPaths[$taskName] -PathType Leaf)) { throw "Build the signed release artifact first: $taskName" } }
$taskLicenses = Join-Path $taskStage 'licenses'
New-Item -ItemType Directory -Path $taskLicenses -Force | Out-Null
Get-ChildItem -LiteralPath (Join-Path $taskRoot 'licenses') -File | ForEach-Object { Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $taskLicenses $_.Name) -Force }
Copy-Item -LiteralPath (Join-Path $taskRoot 'LICENSE') -Destination (Join-Path $taskStage 'LICENSE.txt')
$taskGuides = @{ 'android-quick-start.md' = "$taskPrefix-android-guide.md" }
if (-not $AndroidOnly) { $taskGuides['quick-start.md'] = "$taskPrefix-guide.md"; $taskGuides['desktop-quick-start.md'] = "$taskPrefix-desktop-guide.md" }
$taskDocumentNames = @{ 'file-transfer.md' = "$taskPrefix-file-transfer.md"; 'acceptance.md' = "$taskPrefix-acceptance.md"; 'updates.md' = "$taskPrefix-updates.md"; 'file-library.md' = "$taskPrefix-file-library.md" }
$taskDocumentNames['settings-feedback.md'] = "$taskPrefix-settings-feedback.md"
function Write-TaskStandaloneGuide([string]$Source, [string]$Name, [string]$Directory = $taskDist) {
    $taskText = Get-Content -LiteralPath $Source -Raw
    foreach ($taskMapping in @($taskGuides.GetEnumerator()) + @($taskDocumentNames.GetEnumerator())) {
        $taskText = $taskText.Replace(('](' + $taskMapping.Key + ')'), ('](' + $taskMapping.Value + ')'))
    }
    $taskGuideTarget = Join-Path $Directory $Name
    if (Test-Path -LiteralPath $taskGuideTarget) { throw "Existing guide is preserved; use a new timestamp: $Name" }
    $taskText | Set-Content -LiteralPath $taskGuideTarget -Encoding UTF8 -NoNewline
}
foreach ($taskGuide in $taskGuides.GetEnumerator()) {
    Copy-Item -LiteralPath (Join-Path $taskRoot ('docs\' + $taskGuide.Key)) -Destination (Join-Path $taskStage $taskGuide.Value)
    $taskGuideDirectory = if ($taskGuide.Key -eq 'desktop-quick-start.md') { $taskDesktopDist } else { $taskDist }
    Write-TaskStandaloneGuide (Join-Path $taskStage $taskGuide.Value) $taskGuide.Value $taskGuideDirectory
}
$taskCommon = @((Join-Path $taskStage 'LICENSE.txt'), $taskLicenses)
foreach ($taskGuide in @('file-transfer.md', 'acceptance.md', 'updates.md', 'file-library.md', 'settings-feedback.md')) {
    $taskGuidePath = Join-Path $taskStage $taskGuide
    Copy-Item -LiteralPath (Join-Path $taskRoot ('docs\' + $taskGuide)) -Destination $taskGuidePath
    Write-TaskStandaloneGuide $taskGuidePath $taskDocumentNames[$taskGuide]
    if (-not $AndroidOnly -and $taskDesktopDist -ne $taskDist) { Write-TaskStandaloneGuide $taskGuidePath $taskDocumentNames[$taskGuide] $taskDesktopDist }
    $taskCommon += $taskGuidePath
}
$taskChecksums = foreach ($taskName in $taskArtifacts) { $taskHash = (Get-FileHash -LiteralPath $taskArtifactPaths[$taskName] -Algorithm SHA256).Hash.ToLowerInvariant(); "$taskHash  $taskName" }
$taskSumName = "$taskPrefix-SHA256SUMS.txt"
$taskChecksums | Set-Content -LiteralPath (Join-Path $taskStage $taskSumName) -Encoding ASCII
Copy-Item -LiteralPath (Join-Path $taskStage $taskSumName) -Destination (Join-Path $taskDist $taskSumName)
if (-not $AndroidOnly) {
    $taskBundle = Join-Path $taskDist ("VaultBridge-Android-$taskAndroidVersion-Windows-$taskDesktopVersion-local-$taskStamp.zip")
    $taskBundleFiles = @($taskArtifacts | ForEach-Object { $taskArtifactPaths[$_] }) + @((Join-Path $taskStage $taskGuides['quick-start.md']), (Join-Path $taskStage $taskSumName)) + $taskCommon
    Compress-Archive -LiteralPath $taskBundleFiles -DestinationPath $taskBundle -CompressionLevel Optimal
    Write-Output "Local distribution (Android $taskAndroidVersion / Windows $taskDesktopVersion): $taskBundle"

    $taskDesktopHashes = $taskChecksums | Where-Object { $_ -like '*Desktop*' }
    $taskDesktopSum = "$taskPrefix-Desktop-SHA256SUMS.txt"
    $taskDesktopHashes | Set-Content -LiteralPath (Join-Path $taskStage $taskDesktopSum) -Encoding ASCII
    Copy-Item -LiteralPath (Join-Path $taskStage $taskDesktopSum) -Destination (Join-Path $taskDesktopDist $taskDesktopSum)
    $taskDesktopBundle = Join-Path $taskDesktopDist ("VaultBridge-Desktop-$taskDesktopVersion-portable-$taskStamp.zip")
    $taskDesktopFiles = @($taskArtifactPaths[$taskExeName], $taskArtifactPaths[$taskConfigName], (Join-Path $taskStage $taskGuides['desktop-quick-start.md']), (Join-Path $taskStage $taskDesktopSum)) + $taskCommon
    Compress-Archive -LiteralPath $taskDesktopFiles -DestinationPath $taskDesktopBundle -CompressionLevel Optimal
    Write-Output "Desktop distribution ($taskDesktopVersion): $taskDesktopBundle"
}

$taskAndroidHashes = $taskChecksums | Where-Object { $_ -like '*Android*' }
$taskAndroidSum = if ($AndroidOnly) { $taskSumName } else { "$taskPrefix-Android-SHA256SUMS.txt" }
if (-not $AndroidOnly) {
    $taskAndroidHashes | Set-Content -LiteralPath (Join-Path $taskStage $taskAndroidSum) -Encoding ASCII
    Copy-Item -LiteralPath (Join-Path $taskStage $taskAndroidSum) -Destination (Join-Path $taskDist $taskAndroidSum)
}
$taskAndroidBundle = Join-Path $taskDist ("VaultBridge-Android-$taskAndroidVersion-local-$taskStamp.zip")
$taskAndroidFiles = @($taskArtifactPaths[$taskApkName], (Join-Path $taskStage $taskGuides['android-quick-start.md']), (Join-Path $taskStage $taskAndroidSum)) + $taskCommon
Compress-Archive -LiteralPath $taskAndroidFiles -DestinationPath $taskAndroidBundle -CompressionLevel Optimal
Write-Output "Android distribution: $taskAndroidBundle"
Write-Output "Packaging staging retained: $taskStage"
