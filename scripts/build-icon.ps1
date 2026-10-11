$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskWork = Join-Path $taskRoot ('work\icon-build-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Path $taskWork -Force | Out-Null
$taskSource = Join-Path $taskWork 'GenerateIcon.cs'
$taskCode = 'class GenerateIcon { static void Main(string[] args) { VaultBridge.AppIcon.WriteIco(args[0]); VaultBridge.AppIcon.WritePreview(args[1]); } }'
[IO.File]::WriteAllText($taskSource, $taskCode, (New-Object Text.UTF8Encoding($false)))
$taskCompiler = 'C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe'
$taskGenerator = Join-Path $taskWork 'GenerateIcon.exe'
& $taskCompiler /nologo /target:exe /r:System.Drawing.dll "/out:$taskGenerator" (Join-Path $taskRoot 'desktop\AppIcon.cs') $taskSource
if ($LASTEXITCODE -ne 0) { throw 'Icon generator compilation failed.' }
& $taskGenerator (Join-Path $taskRoot 'desktop\vaultbridge.ico') (Join-Path $taskWork 'icon-preview.png')
if ($LASTEXITCODE -ne 0) { throw 'Icon generation failed.' }
Write-Output "Icon preview: $taskWork\icon-preview.png"
