$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskDist = Join-Path $taskRoot 'dist'
$taskLicenses = Join-Path $taskDist 'licenses'
New-Item -ItemType Directory -Path $taskLicenses -Force | Out-Null
Get-ChildItem -LiteralPath (Join-Path $taskRoot 'licenses') -File | ForEach-Object { Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $taskLicenses $_.Name) -Force }
Copy-Item -LiteralPath (Join-Path $taskRoot 'LICENSE') -Destination (Join-Path $taskDist 'LICENSE.txt') -Force
Copy-Item -LiteralPath (Join-Path $taskRoot 'docs\quick-start.md') -Destination (Join-Path $taskDist '使用说明.md') -Force
$taskArtifacts = @('VaultBridge-Desktop-0.3.4.exe','VaultBridge-Android-0.3.4.apk','VaultBridge-Desktop-0.3.4.exe.config')
$taskChecksums = foreach ($taskName in $taskArtifacts) { $taskHash = (Get-FileHash -LiteralPath (Join-Path $taskDist $taskName) -Algorithm SHA256).Hash.ToLowerInvariant(); "$taskHash  $taskName" }
$taskChecksums | Set-Content -LiteralPath (Join-Path $taskDist 'SHA256SUMS.txt') -Encoding ASCII
$taskBundle = Join-Path $taskDist ('VaultBridge-0.3.4-local-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.zip')
Compress-Archive -LiteralPath @((Join-Path $taskDist $taskArtifacts[0]), (Join-Path $taskDist $taskArtifacts[1]), (Join-Path $taskDist $taskArtifacts[2]), (Join-Path $taskDist '使用说明.md'), (Join-Path $taskDist 'SHA256SUMS.txt'), (Join-Path $taskDist 'LICENSE.txt'), $taskLicenses) -DestinationPath $taskBundle -CompressionLevel Optimal
Write-Output "Local distribution: $taskBundle"
Get-ChildItem -LiteralPath $taskDist -File | Select-Object Name,Length

Copy-Item -LiteralPath (Join-Path $taskRoot 'docs\desktop-quick-start.md') -Destination (Join-Path $taskDist '电脑端使用说明-0.3.4.md') -Force
$taskDesktopHashes = $taskChecksums | Where-Object { $_ -like '*Desktop*' }
$taskDesktopHashes | Set-Content -LiteralPath (Join-Path $taskDist 'SHA256SUMS-Desktop-0.3.4.txt') -Encoding ASCII
$taskDesktopBundle = Join-Path $taskDist ('VaultBridge-Desktop-0.3.4-portable-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.zip')
Compress-Archive -LiteralPath @((Join-Path $taskDist $taskArtifacts[0]),(Join-Path $taskDist $taskArtifacts[2]),(Join-Path $taskDist '电脑端使用说明-0.3.4.md'),(Join-Path $taskDist 'SHA256SUMS-Desktop-0.3.4.txt'),(Join-Path $taskDist 'LICENSE.txt'),$taskLicenses) -DestinationPath $taskDesktopBundle -CompressionLevel Optimal
Write-Output "Desktop distribution: $taskDesktopBundle"

Copy-Item -LiteralPath (Join-Path $taskRoot 'docs\android-quick-start.md') -Destination (Join-Path $taskDist 'VaultBridge-Android-0.3.4-guide.md') -Force
$taskAndroidHashes = $taskChecksums | Where-Object { $_ -like '*Android*' }
$taskAndroidHashes | Set-Content -LiteralPath (Join-Path $taskDist 'SHA256SUMS-Android-0.3.4.txt') -Encoding ASCII
$taskAndroidBundle = Join-Path $taskDist ('VaultBridge-Android-0.3.4-local-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.zip')
Compress-Archive -LiteralPath @((Join-Path $taskDist $taskArtifacts[1]),(Join-Path $taskDist 'VaultBridge-Android-0.3.4-guide.md'),(Join-Path $taskDist 'SHA256SUMS-Android-0.3.4.txt'),(Join-Path $taskDist 'LICENSE.txt'),$taskLicenses) -DestinationPath $taskAndroidBundle -CompressionLevel Optimal
Write-Output "Android distribution: $taskAndroidBundle"
