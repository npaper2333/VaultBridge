$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskDist = Join-Path $taskRoot 'dist'
$taskLicenses = Join-Path $taskDist 'licenses'
New-Item -ItemType Directory -Path $taskLicenses -Force | Out-Null
Get-ChildItem -LiteralPath (Join-Path $taskRoot 'licenses') -File | ForEach-Object { Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $taskLicenses $_.Name) -Force }
Copy-Item -LiteralPath (Join-Path $taskRoot 'LICENSE') -Destination (Join-Path $taskDist 'LICENSE.txt') -Force
Copy-Item -LiteralPath (Join-Path $taskRoot 'docs\quick-start.md') -Destination (Join-Path $taskDist 'VaultBridge-0.4.0-guide.md') -Force
foreach ($taskGuide in @('file-transfer.md','acceptance.md')) { Copy-Item -LiteralPath (Join-Path $taskRoot ('docs\' + $taskGuide)) -Destination (Join-Path $taskDist $taskGuide) -Force }
$taskArtifacts = @('VaultBridge-Desktop-0.4.0.exe','VaultBridge-Android-0.4.0.apk','VaultBridge-Desktop-0.4.0.exe.config')
$taskChecksums = foreach ($taskName in $taskArtifacts) { $taskHash = (Get-FileHash -LiteralPath (Join-Path $taskDist $taskName) -Algorithm SHA256).Hash.ToLowerInvariant(); "$taskHash  $taskName" }
$taskChecksums | Set-Content -LiteralPath (Join-Path $taskDist 'SHA256SUMS-0.4.0.txt') -Encoding ASCII
$taskBundle = Join-Path $taskDist ('VaultBridge-0.4.0-local-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.zip')
Compress-Archive -LiteralPath @((Join-Path $taskDist $taskArtifacts[0]), (Join-Path $taskDist $taskArtifacts[1]), (Join-Path $taskDist $taskArtifacts[2]), (Join-Path $taskDist 'VaultBridge-0.4.0-guide.md'), (Join-Path $taskDist 'SHA256SUMS-0.4.0.txt'), (Join-Path $taskDist 'LICENSE.txt'), $taskLicenses,(Join-Path $taskDist 'file-transfer.md'),(Join-Path $taskDist 'acceptance.md')) -DestinationPath $taskBundle -CompressionLevel Optimal
Write-Output "Local distribution: $taskBundle"
Get-ChildItem -LiteralPath $taskDist -File | Select-Object Name,Length

Copy-Item -LiteralPath (Join-Path $taskRoot 'docs\desktop-quick-start.md') -Destination (Join-Path $taskDist 'VaultBridge-Desktop-0.4.0-guide.md') -Force
$taskDesktopHashes = $taskChecksums | Where-Object { $_ -like '*Desktop*' }
$taskDesktopHashes | Set-Content -LiteralPath (Join-Path $taskDist 'SHA256SUMS-Desktop-0.4.0.txt') -Encoding ASCII
$taskDesktopBundle = Join-Path $taskDist ('VaultBridge-Desktop-0.4.0-portable-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.zip')
Compress-Archive -LiteralPath @((Join-Path $taskDist $taskArtifacts[0]),(Join-Path $taskDist $taskArtifacts[2]),(Join-Path $taskDist 'VaultBridge-Desktop-0.4.0-guide.md'),(Join-Path $taskDist 'SHA256SUMS-Desktop-0.4.0.txt'),(Join-Path $taskDist 'LICENSE.txt'),$taskLicenses,(Join-Path $taskDist 'file-transfer.md'),(Join-Path $taskDist 'acceptance.md')) -DestinationPath $taskDesktopBundle -CompressionLevel Optimal
Write-Output "Desktop distribution: $taskDesktopBundle"

Copy-Item -LiteralPath (Join-Path $taskRoot 'docs\android-quick-start.md') -Destination (Join-Path $taskDist 'VaultBridge-Android-0.4.0-guide.md') -Force
$taskAndroidHashes = $taskChecksums | Where-Object { $_ -like '*Android*' }
$taskAndroidHashes | Set-Content -LiteralPath (Join-Path $taskDist 'SHA256SUMS-Android-0.4.0.txt') -Encoding ASCII
$taskAndroidBundle = Join-Path $taskDist ('VaultBridge-Android-0.4.0-local-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.zip')
Compress-Archive -LiteralPath @((Join-Path $taskDist $taskArtifacts[1]),(Join-Path $taskDist 'VaultBridge-Android-0.4.0-guide.md'),(Join-Path $taskDist 'SHA256SUMS-Android-0.4.0.txt'),(Join-Path $taskDist 'LICENSE.txt'),$taskLicenses,(Join-Path $taskDist 'file-transfer.md'),(Join-Path $taskDist 'acceptance.md')) -DestinationPath $taskAndroidBundle -CompressionLevel Optimal
Write-Output "Android distribution: $taskAndroidBundle"
