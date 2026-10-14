$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskVersion = '0.4.2'
$taskStamp = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
$taskDist = Join-Path $taskRoot 'dist'
$taskStage = Join-Path $taskRoot ('work\packaging-' + $taskVersion + '-' + $taskStamp)
$taskArtifacts = @("VaultBridge-Desktop-$taskVersion.exe", "VaultBridge-Android-$taskVersion.apk", "VaultBridge-Desktop-$taskVersion.exe.config")
foreach ($taskName in $taskArtifacts) { if (-not (Test-Path -LiteralPath (Join-Path $taskDist $taskName) -PathType Leaf)) { throw "Build the signed release artifact first: $taskName" } }
$taskLicenses = Join-Path $taskStage 'licenses'
New-Item -ItemType Directory -Path $taskLicenses -Force | Out-Null
Get-ChildItem -LiteralPath (Join-Path $taskRoot 'licenses') -File | ForEach-Object { Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $taskLicenses $_.Name) -Force }
Copy-Item -LiteralPath (Join-Path $taskRoot 'LICENSE') -Destination (Join-Path $taskStage 'LICENSE.txt')
$taskGuides = @{ 'quick-start.md' = "VaultBridge-$taskVersion-guide.md"; 'desktop-quick-start.md' = "VaultBridge-Desktop-$taskVersion-guide.md"; 'android-quick-start.md' = "VaultBridge-Android-$taskVersion-guide.md" }
$taskDocumentNames = @{ 'file-transfer.md' = "VaultBridge-$taskVersion-file-transfer.md"; 'acceptance.md' = "VaultBridge-$taskVersion-acceptance.md"; 'updates.md' = "VaultBridge-$taskVersion-updates.md" }
function Write-TaskStandaloneGuide([string]$Source, [string]$Name) {
    $taskText = Get-Content -LiteralPath $Source -Raw
    foreach ($taskMapping in @($taskGuides.GetEnumerator()) + @($taskDocumentNames.GetEnumerator())) {
        $taskText = $taskText.Replace(('](' + $taskMapping.Key + ')'), ('](' + $taskMapping.Value + ')'))
    }
    $taskText | Set-Content -LiteralPath (Join-Path $taskDist $Name) -Encoding UTF8 -NoNewline
}
foreach ($taskGuide in $taskGuides.GetEnumerator()) {
    Copy-Item -LiteralPath (Join-Path $taskRoot ('docs\' + $taskGuide.Key)) -Destination (Join-Path $taskStage $taskGuide.Value)
    Write-TaskStandaloneGuide (Join-Path $taskStage $taskGuide.Value) $taskGuide.Value
}
$taskCommon = @((Join-Path $taskStage 'LICENSE.txt'), $taskLicenses)
foreach ($taskGuide in @('file-transfer.md', 'acceptance.md', 'updates.md')) {
    $taskGuidePath = Join-Path $taskStage $taskGuide
    Copy-Item -LiteralPath (Join-Path $taskRoot ('docs\' + $taskGuide)) -Destination $taskGuidePath
    Write-TaskStandaloneGuide $taskGuidePath $taskDocumentNames[$taskGuide]
    $taskCommon += $taskGuidePath
}
$taskChecksums = foreach ($taskName in $taskArtifacts) { $taskHash = (Get-FileHash -LiteralPath (Join-Path $taskDist $taskName) -Algorithm SHA256).Hash.ToLowerInvariant(); "$taskHash  $taskName" }
$taskSumName = "SHA256SUMS-$taskVersion.txt"
$taskChecksums | Set-Content -LiteralPath (Join-Path $taskStage $taskSumName) -Encoding ASCII
Copy-Item -LiteralPath (Join-Path $taskStage $taskSumName) -Destination (Join-Path $taskDist $taskSumName) -Force
$taskBundle = Join-Path $taskDist ("VaultBridge-$taskVersion-local-$taskStamp.zip")
$taskBundleFiles = @($taskArtifacts | ForEach-Object { Join-Path $taskDist $_ }) + @((Join-Path $taskStage $taskGuides['quick-start.md']), (Join-Path $taskStage $taskSumName)) + $taskCommon
Compress-Archive -LiteralPath $taskBundleFiles -DestinationPath $taskBundle -CompressionLevel Optimal
Write-Output "Local distribution: $taskBundle"

$taskDesktopHashes = $taskChecksums | Where-Object { $_ -like '*Desktop*' }
$taskDesktopSum = "SHA256SUMS-Desktop-$taskVersion.txt"
$taskDesktopHashes | Set-Content -LiteralPath (Join-Path $taskStage $taskDesktopSum) -Encoding ASCII
Copy-Item -LiteralPath (Join-Path $taskStage $taskDesktopSum) -Destination (Join-Path $taskDist $taskDesktopSum) -Force
$taskDesktopBundle = Join-Path $taskDist ("VaultBridge-Desktop-$taskVersion-portable-$taskStamp.zip")
$taskDesktopFiles = @((Join-Path $taskDist $taskArtifacts[0]), (Join-Path $taskDist $taskArtifacts[2]), (Join-Path $taskStage $taskGuides['desktop-quick-start.md']), (Join-Path $taskStage $taskDesktopSum)) + $taskCommon
Compress-Archive -LiteralPath $taskDesktopFiles -DestinationPath $taskDesktopBundle -CompressionLevel Optimal
Write-Output "Desktop distribution: $taskDesktopBundle"

$taskAndroidHashes = $taskChecksums | Where-Object { $_ -like '*Android*' }
$taskAndroidSum = "SHA256SUMS-Android-$taskVersion.txt"
$taskAndroidHashes | Set-Content -LiteralPath (Join-Path $taskStage $taskAndroidSum) -Encoding ASCII
Copy-Item -LiteralPath (Join-Path $taskStage $taskAndroidSum) -Destination (Join-Path $taskDist $taskAndroidSum) -Force
$taskAndroidBundle = Join-Path $taskDist ("VaultBridge-Android-$taskVersion-local-$taskStamp.zip")
$taskAndroidFiles = @((Join-Path $taskDist $taskArtifacts[1]), (Join-Path $taskStage $taskGuides['android-quick-start.md']), (Join-Path $taskStage $taskAndroidSum)) + $taskCommon
Compress-Archive -LiteralPath $taskAndroidFiles -DestinationPath $taskAndroidBundle -CompressionLevel Optimal
Write-Output "Android distribution: $taskAndroidBundle"
Write-Output "Packaging staging retained: $taskStage"
