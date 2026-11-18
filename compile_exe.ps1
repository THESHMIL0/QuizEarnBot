# =====================================================================
# Quiz & Earn Bot v2.9 - Portable C# EXE Compiler (.NET 4.x csc.exe)
# =====================================================================
$ErrorActionPreference = "Stop"
$WorkingDir = $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($WorkingDir)) { $WorkingDir = (Get-Location).Path }

$cscCandidates = @(
    "C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe",
    "C:\Windows\Microsoft.NET\Framework\v4.0.30319\csc.exe"
)
$CscPath = $cscCandidates | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $CscPath) {
    Write-Error "Could not locate .NET Framework 4.x csc.exe."
    exit 1
}

$SourcePath = Join-Path $WorkingDir "QuizEarnApp.cs"
$ExePath = Join-Path $WorkingDir "QuizEarnBot.exe"
$ico = Join-Path $WorkingDir "app.ico"
$manifest = Join-Path $WorkingDir "app.manifest"

if (-not (Test-Path $SourcePath)) {
    Write-Error "QuizEarnApp.cs not found in $WorkingDir"
    exit 1
}

Stop-Process -Name QuizEarnBot -Force -ErrorAction SilentlyContinue
Start-Sleep -Milliseconds 600

$compileArgs = @(
    "/target:winexe",
    "/out:$ExePath",
    "/platform:anycpu",
    "/optimize+",
    "/codepage:65001",
    "/nologo"
)
if (Test-Path $ico) { $compileArgs += "/win32icon:$ico" }
if (Test-Path $manifest) { $compileArgs += "/win32manifest:$manifest" }
$compileArgs += $SourcePath

Write-Host "Compiling $SourcePath -> $ExePath (UTF-8 codepage 65001)..." -ForegroundColor Cyan
& $CscPath @compileArgs
if ($LASTEXITCODE -ne 0) {
    Write-Error "csc.exe compilation failed with exit code $LASTEXITCODE"
    exit $LASTEXITCODE
}

Write-Host "Build succeeded: $ExePath" -ForegroundColor Green
exit 0
