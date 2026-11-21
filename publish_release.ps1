# =====================================================================
# Quiz & Earn Bot v2.9 - One-Click Cloud Release Publisher
# =====================================================================
param(
    [string]$NewVersion = "2.9",
    [int]$VersionCode = 290,
    [string]$Changelog = "v2.9: Added Quiz & Earn Lite APK (Auto-Stops & Alerts at 25 Gems, pure math + ads solver). Exe watchdog strictly filters BlueStacks instances only.",
    [string]$DownloadUrl = "https://raw.githubusercontent.com/THESHMIL0/QuizEarnBot/main/QuizEarnBot.exe",
    [string]$ApkUrl = "https://raw.githubusercontent.com/THESHMIL0/QuizEarnBot/main/QuizEarnBot.apk",
    [string]$ApkLiteUrl = "https://raw.githubusercontent.com/THESHMIL0/QuizEarnBot/main/QuizEarnBotLite.apk"
)

$ErrorActionPreference = "Stop"
$WorkingDir = $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($WorkingDir)) { $WorkingDir = (Get-Location).Path }
Set-Location $WorkingDir

Write-Host "=====================================================" -ForegroundColor Cyan
Write-Host "  QUIZ & EARN BOT - RELEASE PUBLISHER (v$NewVersion)" -ForegroundColor Green
Write-Host "=====================================================" -ForegroundColor Cyan

# 1. Compile QuizEarnApp.cs via compile_exe.ps1 (strict exit check)
$compileScript = Join-Path $WorkingDir "compile_exe.ps1"
if (Test-Path $compileScript) {
    Write-Host "[1/5] Running compile_exe.ps1..." -ForegroundColor Yellow
    & powershell -ExecutionPolicy Bypass -File $compileScript
    if ($LASTEXITCODE -ne 0) {
        Write-Error "EXE compilation failed. Release aborted."
        exit $LASTEXITCODE
    }
} else {
    $CscPath = "C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe"
    $SourcePath = Join-Path $WorkingDir "QuizEarnApp.cs"
    $ExePath = Join-Path $WorkingDir "QuizEarnBot.exe"
    if (-not (Test-Path $SourcePath)) {
        Write-Error "QuizEarnApp.cs missing."
        exit 1
    }
    Stop-Process -Name QuizEarnBot -Force -ErrorAction SilentlyContinue
    Start-Sleep -Milliseconds 600
    $ico = Join-Path $WorkingDir "app.ico"
    $manifest = Join-Path $WorkingDir "app.manifest"
    $compileArgs = @("/target:winexe", "/out:$ExePath", "/platform:anycpu", "/optimize+", "/codepage:65001", "/nologo")
    if (Test-Path $ico) { $compileArgs += "/win32icon:$ico" }
    if (Test-Path $manifest) { $compileArgs += "/win32manifest:$manifest" }
    $compileArgs += $SourcePath
    & $CscPath @compileArgs
    if ($LASTEXITCODE -ne 0) {
        Write-Error "csc.exe compilation failed. Release aborted."
        exit $LASTEXITCODE
    }
}

$ExePath = Join-Path $WorkingDir "QuizEarnBot.exe"

# 2. Build Android APKs
$buildApkScript = Join-Path $WorkingDir "android\build_apk.ps1"
if (Test-Path $buildApkScript) {
    Write-Host "[2/5] Building Android QuizEarnBot.apk..." -ForegroundColor Yellow
    Push-Location (Join-Path $WorkingDir "android")
    try {
        & powershell -ExecutionPolicy Bypass -File $buildApkScript
        if ($LASTEXITCODE -ne 0) {
            Write-Error "Full APK build failed. Release aborted."
            exit $LASTEXITCODE
        }
    } finally {
        Pop-Location
    }
}
$buildLiteScript = Join-Path $WorkingDir "android-lite\build_apk.ps1"
if (Test-Path $buildLiteScript) {
    Write-Host "[2B/5] Building Android QuizEarnBotLite.apk..." -ForegroundColor Yellow
    Push-Location (Join-Path $WorkingDir "android-lite")
    try {
        & powershell -ExecutionPolicy Bypass -File $buildLiteScript
        if ($LASTEXITCODE -ne 0) {
            Write-Error "Lite APK build failed. Release aborted."
            exit $LASTEXITCODE
        }
    } finally {
        Pop-Location
    }
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
    apk_lite_version = "1.0-Lite"
    apk_lite_url = $ApkLiteUrl
    mandatory = $false
}
$JsonText = $VersionObj | ConvertTo-Json -Depth 3
[System.IO.File]::WriteAllText($VersionJsonPath, $JsonText, [System.Text.UTF8Encoding]::new($false))
Write-Host "[3/5] Generated version.json (v$NewVersion / $VersionCode)." -ForegroundColor Green

# 4. Create Portable Distribution Zip
$ZipPath = Join-Path $WorkingDir "QuizEarnBot_v$($NewVersion)_Portable.zip"
if (Test-Path $ZipPath) { Remove-Item $ZipPath -Force }

Write-Host "[4/5] Creating portable zip: $ZipPath..." -ForegroundColor Yellow
$StageDir = Join-Path $env:TEMP ("QuizEarnBot_Stage_" + [Guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $StageDir | Out-Null

$FilesToCopy = @(
    $ExePath,
    (Join-Path $WorkingDir "QuizEarnBot.apk"),
    (Join-Path $WorkingDir "QuizEarnBotLite.apk"),
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
Write-Host "  -> Portable ZIP created ($((Get-Item $ZipPath).Length) bytes)" -ForegroundColor Green

# 5. Push via git if available on PATH
Write-Host "[5/5] Checking git repository for publishing..." -ForegroundColor Yellow
$gitCmd = Get-Command git -ErrorAction SilentlyContinue
if ($gitCmd) {
    & git add version.json QuizEarnBot.exe QuizEarnBot.apk QuizEarnBotLite.apk "QuizEarnBot_v$($NewVersion)_Portable.zip" QuizEarnApp.cs compile_exe.ps1 publish_release.ps1 app.manifest android/ android-lite/ README.md index.html HOW_TO_RUN.txt deploy_all.ps1 start_services.ps1 check_status.ps1 QUIZEARN_BOT_COMPLETE_HANDOVER_AND_CODEBASE.txt QUIZEARN_EXE_BOT_FULL_HANDOVER_AND_CODE.txt
    & git commit -m "Release v$NewVersion - $Changelog"
    & git push origin main
    Write-Host "  -> Published v$NewVersion to GitHub." -ForegroundColor Green
} else {
    Write-Host "  -> git not found on PATH; skipping push step." -ForegroundColor Gray
}
