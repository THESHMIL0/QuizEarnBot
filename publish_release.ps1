# =====================================================================
# Quiz & Earn Bot - One-Click Cloud Release Publisher
# =====================================================================
param(
    [string]$NewVersion = "2.1",
    [int]$VersionCode = 210,
    [string]$Changelog = "Settings menu with Cloud Auto-Updater, Fixed Bonus Rewards claiming across all apps, Pure OLED Black theme enhancements.",
    [string]$DownloadUrl = "https://raw.githubusercontent.com/THESHMIL0/QuizEarnBot/main/QuizEarnBot.exe"
)

$ErrorActionPreference = "Stop"
$WorkingDir = Split-Path -Parent $MyInvocation.MyCommand.Path

Write-Host "=====================================================" -ForegroundColor Cyan
Write-Host "  QUIZ & EARN BOT - ONE-CLICK RELEASE PUBLISHER      " -ForegroundColor Green
Write-Host "=====================================================" -ForegroundColor Cyan
Write-Host "Building Release: v$NewVersion (Code: $VersionCode)"

# 1. Compile Latest Source
$CscPath = "C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe"
$SourcePath = Join-Path $WorkingDir "QuizEarnApp.cs"
$ExePath = Join-Path $WorkingDir "QuizEarnBot.exe"

if (Test-Path $SourcePath) {
    Write-Host "[1/4] Compiling QuizEarnApp.cs..." -ForegroundColor Yellow
    & $CscPath /target:winexe /out:"$ExePath" /platform:anycpu /optimize+ "$SourcePath"
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
$adbPath = Join-Path $WorkingDir "adb.exe"
$dll1 = Join-Path $WorkingDir "AdbWinApi.dll"
$dll2 = Join-Path $WorkingDir "AdbWinUsbApi.dll"
$FilesToZip = @($ExePath, $adbPath, $dll1, $dll2, $VersionJsonPath) | Where-Object { Test-Path $_ }

Compress-Archive -Path $FilesToZip -DestinationPath $ZipPath -Force
Write-Host "  -> Portable ZIP created: $(Get-Item $ZipPath | Select-Object -ExpandProperty Length) bytes" -ForegroundColor Green

# 4. Summary & Instructions
Write-Host ""
Write-Host "=====================================================" -ForegroundColor Cyan
Write-Host "  RELEASE READY TO SHARE WITH FRIENDS!              " -ForegroundColor Green
Write-Host "=====================================================" -ForegroundColor Cyan
Write-Host "1. Upload 'version.json' and 'QuizEarnBot.exe' to your host (e.g. GitHub raw / cloud host)."
Write-Host "2. When your friends launch their app, it will detect v$NewVersion automatically!"
Write-Host "3. They can click '[ Update & Restart Now ]' or '[ Ignore / Later ]' in their Settings tab."
Write-Host "=====================================================" -ForegroundColor Cyan
