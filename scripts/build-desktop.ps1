$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskOutput = Join-Path $taskRoot 'dist\0.5\0.5.2'
New-Item -ItemType Directory -Path $taskOutput -Force | Out-Null
& (Join-Path $PSScriptRoot 'build-icon.ps1')
$taskQrLibrary = Join-Path $taskRoot 'tools\qrcoder-1.6.0\lib\net40\QRCoder.dll'
if (-not (Test-Path -LiteralPath $taskQrLibrary)) { $taskQrLibrary = Join-Path $taskRoot 'tools\qrcoder-1.6.0\lib\net35\QRCoder.dll' }
if (-not (Test-Path -LiteralPath $taskQrLibrary)) { throw 'Run scripts/prepare-tools.ps1 -Download first.' }
$taskCompiler = 'C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe'
$taskSources = @(Get-ChildItem -LiteralPath (Join-Path $taskRoot 'desktop') -Filter '*.cs' -File | ForEach-Object FullName)
$taskReferences = @('/r:System.Windows.Forms.dll','/r:System.Drawing.dll','/r:System.Web.Extensions.dll','/r:System.Security.dll','/r:System.IO.Compression.dll','/r:System.IO.Compression.FileSystem.dll',"/r:$taskQrLibrary")
& $taskCompiler /nologo /target:winexe /platform:anycpu /optimize+ /utf8output "/out:$taskOutput\VaultBridge-Desktop-0.5.2.exe" "/resource:$taskQrLibrary,QRCoder.dll" "/resource:$taskRoot\desktop\vaultbridge.ico,VaultBridge.ico" "/win32icon:$taskRoot\desktop\vaultbridge.ico" "/win32manifest:$taskRoot\desktop\app.manifest" $taskReferences $taskSources
if ($LASTEXITCODE -ne 0) { throw 'Desktop compilation failed.' }
Copy-Item -LiteralPath (Join-Path $taskRoot 'desktop\app.config') -Destination (Join-Path $taskOutput 'VaultBridge-Desktop-0.5.2.exe.config') -Force
Write-Output 'Desktop EXE and application DPI configuration built.'
