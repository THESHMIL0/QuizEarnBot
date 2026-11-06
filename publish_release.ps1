# =====================================================================
# Quiz & Earn Bot - One-Click Cloud Release Publisher
# =====================================================================
param(
    [string]$NewVersion = "2.1",
    [int]$VersionCode = 210,
    [string]$Changelog = "Settings menu with Cloud Auto-Updater, Fixed Bonus Rewards claiming across all apps, Pure OLED Black theme enhancements.",
    [string]$DownloadUrl = "https://raw.githubusercontent.com/THESHMIL0/QuizEarnBot/main/QuizEarnBot.exe"
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
    Write-Host "[1/4] Compiling QuizEarnApp.cs with app logo & manifest..." -ForegroundColor Yellow
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
    Write-Host "[1/4] QuizEarnApp.cs not found, using existing QuizEarnBot.exe." -ForegroundColor Yellow
}

# 2. Generate version.json
$VersionJsonPath = Join-Path $WorkingDir "version.json"
$VersionObj = @{
    version = $NewVersion
    version_code = $VersionCode
    release_date = (Get-Date -Format "yyyy-MM-dd")
    changelog = $Changelog
    download_url = $DownloadUrl
    mandatory = $false
}
$JsonText = $VersionObj | ConvertTo-Json -Depth 3
[System.IO.File]::WriteAllText($VersionJsonPath, $JsonText, [System.Text.Encoding]::UTF8)
Write-Host "[2/4] Generated version.json successfully." -ForegroundColor Green

# 3. Create Distribution Zip
$ZipPath = Join-Path $WorkingDir "QuizEarnBot_v$($NewVersion)_Portable.zip"
if (Test-Path $ZipPath) { Remove-Item $ZipPath -Force }

Write-Host "[3/4] Creating portable distribution zip: $ZipPath..." -ForegroundColor Yellow
$StageDir = Join-Path $env:TEMP "QuizEarnBot_Stage"
if (Test-Path $StageDir) { Remove-Item $StageDir -Recurse -Force }
New-Item -ItemType Directory -Path $StageDir | Out-Null

$FilesToCopy = @($ExePath, (Join-Path $WorkingDir "adb.exe"), (Join-Path $WorkingDir "AdbWinApi.dll"), (Join-Path $WorkingDir "AdbWinUsbApi.dll"), $VersionJsonPath, (Join-Path $WorkingDir "app.ico"), (Join-Path $WorkingDir "logo.png"))
foreach ($f in $FilesToCopy) {
    if (Test-Path $f) {
        Copy-Item -Path $f -Destination $StageDir -Force
    }
}

Compress-Archive -Path "$StageDir\*" -DestinationPath $ZipPath -Force
Remove-Item $StageDir -Recurse -Force
Write-Host "  -> Portable ZIP created: $((Get-Item $ZipPath).Length) bytes" -ForegroundColor Green

# 4. Push to GitHub
Write-Host "[4/4] Pushing update live to GitHub..." -ForegroundColor Yellow
$git = "C:\Users\THESHMIL\AppData\Local\Programs\Git\cmd\git.exe"
if (Test-Path $git) {
    & $git add version.json QuizEarnBot.exe "QuizEarnBot_v$($NewVersion)_Portable.zip" QuizEarnApp.cs README.md index.html app.ico logo.png app.manifest .gitignore 2>$null
    $commitMsg = "Release v$($NewVersion) - $($Changelog)"
    & $git commit -m "$commitMsg" 2>$null
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
Write-Host ""
Write-Host "Your friends' apps will now automatically detect this update!" -ForegroundColor Green
Write-Host "=====================================================" -ForegroundColor Cyan
