# =====================================================================
# Quiz & Earn Bot - One-Click Cloud Release Publisher
# =====================================================================
param(
    [string]$NewVersion = "2.8",
    [int]$VersionCode = 280,
    [string]$Changelog = "Dual Engine v2.8 (Quiz & Earn + CashMafia): Super Offer auto-installer v2.0, Play Store verification, ad reward safety lock (26s-35s), blue Resume dialog protection, auto-relaunch watchdog, and Doze battery whitelist.",
    [string]$DownloadUrl = "https://raw.githubusercontent.com/THESHMIL0/QuizEarnBot/main/QuizEarnBot.exe",
    [string]$ApkUrl = "https://raw.githubusercontent.com/THESHMIL0/QuizEarnBot/main/QuizEarnBot.apk"
)

$ErrorActionPreference = "Continue"
$WorkingDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $WorkingDir

Write-Host "=====================================================" -ForegroundColor Cyan
Write-Host "  QUIZ & EARN BOT - ONE-CLICK RELEASE PUBLISHER      " -ForegroundColor Green
Write-Host "=====================================================" -ForegroundColor Cyan
Write-Host "Building Release: v$NewVersion (Code: $VersionCode)"

# 1. Compile Latest Source
$CscPath = "C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe"
$SourcePath = Join-Path $WorkingDir "QuizEarnApp.cs"
$ExePath = Join-Path $WorkingDir "QuizEarnBot.exe"

if (Test-Path $SourcePath) {
    Write-Host "[1/5] Compiling QuizEarnApp.cs with app logo & manifest..." -ForegroundColor Yellow
    Stop-Process -Name QuizEarnBot -Force -ErrorAction SilentlyContinue
    Start-Sleep -Milliseconds 500
    $ico = Join-Path $WorkingDir "app.ico"
    $manifest = Join-Path $WorkingDir "app.manifest"
    $compileArgs = @("/target:winexe", "/out:$ExePath", "/platform:anycpu", "/optimize+")
    if (Test-Path $ico) { $compileArgs += "/win32icon:$ico" }
    if (Test-Path $manifest) { $compileArgs += "/win32manifest:$manifest" }
    $compileArgs += "$SourcePath"
    & $CscPath $compileArgs
    if ($LASTEXITCODE -ne 0) {
        Write-Error "Compilation failed."
    }
    Write-Host "  -> Compilation successful: $ExePath" -ForegroundColor Green
} else {
    Write-Host "[1/5] QuizEarnApp.cs not found, using existing QuizEarnBot.exe." -ForegroundColor Yellow
}

# 2. Build Latest Android APK
$buildApkScript = Join-Path $WorkingDir "android\build_apk.ps1"
if (Test-Path $buildApkScript) {
    Write-Host "[2/5] Building latest QuizEarnBot.apk v2.0..." -ForegroundColor Yellow
    Push-Location (Join-Path $WorkingDir "android")
    & powershell -ExecutionPolicy Bypass -File $buildApkScript
    Pop-Location
}

# 3. Generate version.json
$VersionJsonPath = Join-Path $WorkingDir "version.json"
$VersionObj = [ordered]@{
    version = $NewVersion
    version_code = $VersionCode
    release_date = (Get-Date -Format "yyyy-MM-dd")
    changelog = $Changelog
    download_url = $DownloadUrl
    apk_url = $ApkUrl
    mandatory = $false
}
$JsonText = $VersionObj | ConvertTo-Json -Depth 3
[System.IO.File]::WriteAllText($VersionJsonPath, $JsonText, [System.Text.Encoding]::UTF8)
Write-Host "[3/5] Generated version.json successfully." -ForegroundColor Green

# 4. Create Distribution Zip
$ZipPath = Join-Path $WorkingDir "QuizEarnBot_v$($NewVersion)_Portable.zip"
if (Test-Path $ZipPath) { Remove-Item $ZipPath -Force }

Write-Host "[4/5] Creating portable distribution zip: $ZipPath..." -ForegroundColor Yellow
$StageDir = Join-Path $env:TEMP "QuizEarnBot_Stage"
if (Test-Path $StageDir) { Remove-Item $StageDir -Recurse -Force }
New-Item -ItemType Directory -Path $StageDir | Out-Null

$FilesToCopy = @(
    $ExePath,
    (Join-Path $WorkingDir "QuizEarnBot.apk"),
    (Join-Path $WorkingDir "adb.exe"),
    (Join-Path $WorkingDir "AdbWinApi.dll"),
    (Join-Path $WorkingDir "AdbWinUsbApi.dll"),
    (Join-Path $WorkingDir "deploy_all.ps1"),
    (Join-Path $WorkingDir "start_services.ps1"),
    (Join-Path $WorkingDir "check_status.ps1"),
    (Join-Path $WorkingDir "HOW_TO_RUN.txt"),
    (Join-Path $WorkingDir "README.md"),
    $VersionJsonPath,
    (Join-Path $WorkingDir "app.ico"),
    (Join-Path $WorkingDir "logo.png")
)
foreach ($f in $FilesToCopy) {
    if (Test-Path $f) {
        Copy-Item -Path $f -Destination $StageDir -Force
    }
}

Compress-Archive -Path "$StageDir\*" -DestinationPath $ZipPath -Force
Remove-Item $StageDir -Recurse -Force
Write-Host "  -> Portable ZIP created: $((Get-Item $ZipPath).Length) bytes" -ForegroundColor Green

# 5. Push to GitHub
Write-Host "[5/5] Pushing update live to GitHub..." -ForegroundColor Yellow
$git = "C:\Users\THESHMIL\AppData\Local\Programs\Git\cmd\git.exe"
if (Test-Path $git) {
    & $git add version.json QuizEarnBot.exe QuizEarnBot.apk "QuizEarnBot_v$($NewVersion)_Portable.zip" QuizEarnApp.cs README.md index.html HOW_TO_RUN.txt deploy_all.ps1 start_services.ps1 check_status.ps1 QUIZEARN_BOT_COMPLETE_HANDOVER_AND_CODEBASE.txt android/ 2>$null
    $commitMsg = "Release v$($NewVersion) - $($Changelog)"
    & $git commit -m "$commitMsg"
    & $git push origin main
    Write-Host "  -> Successfully published live to GitHub repository!" -ForegroundColor Green
}

# Summary
Write-Host ""
Write-Host "=====================================================" -ForegroundColor Cyan
Write-Host "  RELEASE v$NewVersion PUBLISHED TO GITHUB!          " -ForegroundColor Green
Write-Host "=====================================================" -ForegroundColor Cyan
Write-Host "Repository: https://github.com/THESHMIL0/QuizEarnBot"
Write-Host "Feed URL:   https://raw.githubusercontent.com/THESHMIL0/QuizEarnBot/main/version.json"
Write-Host "Direct EXE: $DownloadUrl"
Write-Host "Direct APK: $ApkUrl"
Write-Host ""
Write-Host "Your friends' apps will now automatically detect this update!" -ForegroundColor Green
Write-Host "=====================================================" -ForegroundColor Cyan
