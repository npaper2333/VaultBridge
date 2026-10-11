param([switch]$Download)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskTools = Join-Path $taskRoot 'tools'
$taskDownloadDir = Join-Path $taskTools 'downloads'
New-Item -ItemType Directory -Path $taskDownloadDir -Force | Out-Null
if (-not $Download) { Write-Output 'Use -Download to fetch project-local tools from Microsoft, Google, Maven Central and NuGet.'; exit 0 }
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$ProgressPreference = 'SilentlyContinue'
function Get-TaskDownload([string]$Url, [string]$Name) {
    $taskDestination = Join-Path $taskDownloadDir $Name
    if (-not (Test-Path -LiteralPath ($taskDestination + '.complete'))) { Invoke-WebRequest -Uri $Url -OutFile $taskDestination -UseBasicParsing -TimeoutSec 180; Set-Content -LiteralPath ($taskDestination + '.complete') -Value 'downloaded' -Encoding ASCII }
    Write-Output "Downloaded: $Name"
    return $taskDestination
}
$taskJdkZip = Get-TaskDownload 'https://aka.ms/download-jdk/microsoft-jdk-17-windows-x64.zip' 'microsoft-jdk-17-windows-x64.zip'
if (-not (Get-ChildItem -LiteralPath $taskTools -Directory | Where-Object Name -Like 'jdk-17*')) { Expand-Archive -LiteralPath $taskJdkZip[-1] -DestinationPath $taskTools }
$taskRepository = Get-TaskDownload 'https://dl.google.com/android/repository/repository2-3.xml' 'android-repository.xml'
[xml]$taskRepoXml = Get-Content -LiteralPath $taskRepository[-1] -Raw
$taskPackages = @(@{ Id = 'platforms;android-35'; Dir = 'platforms'; Name = 'platform-35.zip' }, @{ Id = 'build-tools;35.0.0'; Dir = 'build-tools'; Name = 'build-tools-35.zip' })
foreach ($taskPackage in $taskPackages) {
    $taskPackageNode = $taskRepoXml.SelectNodes("//*[local-name()='remotePackage']") | Where-Object { $_.path -eq $taskPackage.Id }
    if (-not $taskPackageNode) { throw "Missing Android package: $($taskPackage.Id)" }
    $taskArchiveNode = $taskPackageNode.SelectNodes("./*[local-name()='archives']/*[local-name()='archive']") | Where-Object { -not $_.'host-os' -or $_.'host-os' -eq 'windows' } | Select-Object -First 1
    $taskArchiveUrl = 'https://dl.google.com/android/repository/' + $taskArchiveNode.complete.url
    $taskArchive = Get-TaskDownload $taskArchiveUrl $taskPackage.Name
    $taskActualSha1 = (Get-FileHash -LiteralPath $taskArchive[-1] -Algorithm SHA1).Hash.ToLowerInvariant()
    if ($taskActualSha1 -ne $taskArchiveNode.complete.checksum.InnerText.Trim().ToLowerInvariant()) { throw "Android download checksum mismatch: $($taskPackage.Name)" }
    $taskTargetDir = Join-Path $taskTools ('android-sdk\' + $taskPackage.Dir)
    New-Item -ItemType Directory -Path $taskTargetDir -Force | Out-Null
    if (-not (Get-ChildItem -LiteralPath $taskTargetDir -Directory)) { Expand-Archive -LiteralPath $taskArchive[-1] -DestinationPath $taskTargetDir }
}
$taskZxing = Get-TaskDownload 'https://repo.maven.apache.org/maven2/com/google/zxing/core/3.5.3/core-3.5.3.jar' 'zxing-core-3.5.3.jar'
$taskJson = Get-TaskDownload 'https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar' 'json-20240303.jar'
$taskQrcode = Get-TaskDownload 'https://api.nuget.org/v3-flatcontainer/qrcoder/1.6.0/qrcoder.1.6.0.nupkg' 'qrcoder-1.6.0.zip'
$taskQrcodeDir = Join-Path $taskTools 'qrcoder-1.6.0'
if (-not (Test-Path -LiteralPath $taskQrcodeDir)) { Expand-Archive -LiteralPath $taskQrcode[-1] -DestinationPath $taskQrcodeDir }
$taskQrLicense = Get-TaskDownload 'https://raw.githubusercontent.com/codebude/QRCoder/v1.6.0/LICENSE.txt' 'qrcoder-LICENSE.txt'
$taskZxingLicense = Get-TaskDownload 'https://raw.githubusercontent.com/zxing/zxing/zxing-3.5.3/LICENSE' 'zxing-LICENSE.txt'
Get-ChildItem -LiteralPath $taskDownloadDir -File | ForEach-Object { [PSCustomObject]@{ File = $_.Name; SHA256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash } } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $taskTools 'download-checksums.json') -Encoding UTF8
Write-Output 'Project-local tools ready. No global environment or system configuration changed.'
