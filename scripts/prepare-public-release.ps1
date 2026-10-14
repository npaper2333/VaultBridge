param([string]$Destination)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
if (-not $Destination) { $Destination = Join-Path $taskRoot ('work\public-release-' + (Get-Date -Format 'yyyyMMdd-HHmmss')) }
$taskDestination = [IO.Path]::GetFullPath($Destination)
$taskWorkRoot = [IO.Path]::GetFullPath((Join-Path $taskRoot 'work')) + [IO.Path]::DirectorySeparatorChar
if (-not $taskDestination.StartsWith($taskWorkRoot,[StringComparison]::OrdinalIgnoreCase)) { throw 'Public staging must stay in this project work directory.' }
if (Test-Path -LiteralPath $taskDestination) { throw 'Use a new directory; existing staging is preserved.' }
$taskFiles = @('.gitignore','README.md','LICENSE','CHANGELOG.md','CONTRIBUTING.md','SECURITY.md')
$taskFiles += @(Get-ChildItem -LiteralPath (Join-Path $taskRoot 'desktop') -Filter '*.cs' -File | ForEach-Object { 'desktop/' + $_.Name })
$taskFiles += @('desktop/app.config','desktop/app.manifest','android/AndroidManifest.xml')
foreach ($taskDirectory in @('android/src','android/res')) {
    $taskFiles += @(Get-ChildItem -LiteralPath (Join-Path $taskRoot $taskDirectory) -Recurse -File | Where-Object { $_.Extension -in @('.java','.xml') } | ForEach-Object { $_.FullName.Substring($taskRoot.Length+1).Replace('\','/') })
}
$taskFiles += @(Get-ChildItem -LiteralPath (Join-Path $taskRoot 'tests') -File | Where-Object { $_.Extension -in @('.java','.cs','.json') } | ForEach-Object { 'tests/' + $_.Name })
$taskFiles += @('scripts/prepare-tools.ps1','scripts/build-icon.ps1','scripts/build-desktop.ps1','scripts/build-android.ps1','scripts/test.ps1','scripts/package.ps1','scripts/prepare-public-release.ps1')
$taskFiles += @('docs/quick-start.md','docs/desktop-quick-start.md','docs/android-quick-start.md','docs/appearance.md','docs/acceptance.md','docs/design.md','docs/protocol.md','docs/third-party.md','docs/building.md','docs/testing.md','docs/faq.md','docs/roadmap.md','docs/file-transfer.md','docs/updates.md','docs/update-plan.md','docs/release-v0.3.4.md','docs/release-v0.4.0.md','docs/release-v0.4.2.md','docs/repository-rules.md')
$taskFiles += @('licenses/QRCoder-MIT.txt','licenses/ZXing-Apache-2.0.txt','licenses/ZXing-NOTICE.txt','licenses/JSON-java-Public-Domain.txt')
$taskRulesSource = Join-Path $taskRoot 'docs/repository-rules.md'
foreach ($taskName in $taskFiles) { if (-not (Test-Path -LiteralPath (Join-Path $taskRoot $taskName) -PathType Leaf)) { throw "Missing public source: $taskName" } }
if (-not (Test-Path -LiteralPath $taskRulesSource -PathType Leaf)) { throw 'Missing public maintenance rules.' }
New-Item -ItemType Directory -Path $taskDestination | Out-Null
foreach ($taskName in $taskFiles) {
    $taskTarget = Join-Path $taskDestination $taskName
    New-Item -ItemType Directory -Path (Split-Path -Parent $taskTarget) -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $taskRoot $taskName) -Destination $taskTarget
}
Copy-Item -LiteralPath $taskRulesSource -Destination (Join-Path $taskDestination 'AGENTS.md')
$taskSourceHashes = foreach ($taskName in ($taskFiles + @('AGENTS.md') | Sort-Object)) {
    $taskSource = if ($taskName -eq 'AGENTS.md') { $taskRulesSource } else { Join-Path $taskRoot $taskName }
    $taskExpectedHash = (Get-FileHash -LiteralPath $taskSource -Algorithm SHA256).Hash.ToLowerInvariant()
    $taskCopiedHash = (Get-FileHash -LiteralPath (Join-Path $taskDestination $taskName) -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($taskCopiedHash -ne $taskExpectedHash) { throw "Source copy mismatch: $taskName" }
    [pscustomobject]@{Path=$taskName;SHA256=$taskCopiedHash}
}
$taskManifest = $taskDestination + '-manifest.json'
$taskSourceHashes | ConvertTo-Json | Set-Content -LiteralPath $taskManifest -Encoding UTF8
Write-Output "Public source staging: $taskDestination"
Write-Output "Source files: $($taskSourceHashes.Count); manifest outside staging: $taskManifest"
Write-Output 'Inspect sources and docs before git initialization or publication; no keys/tools/artifacts are copied.'
