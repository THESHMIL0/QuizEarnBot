<#
.SYNOPSIS
  Grabs screenshot from Windows Clipboard and imports directly into BlueStacks Gallery.
#>
Add-Type -AssemblyName System.Windows.Forms, System.Drawing

$img = [System.Windows.Forms.Clipboard]::GetImage()
if ($null -eq $img) {
    Write-Host "[!] No image found in clipboard!" -ForegroundColor Yellow
    Write-Host "Press 'Win + Shift + S' to snip your app profile, then run this again." -ForegroundColor Cyan
    Start-Sleep -Seconds 3
    exit 1
}

$tempDir = "$env:TEMP\BlueStacksScreenshots"
if (-not (Test-Path $tempDir)) { New-Item -ItemType Directory -Path $tempDir -Force | Out-Null }
$timestamp = Get-Date -Format "yyyyMMdd_HHmmss"
$tempFile = "$tempDir\Screenshot_$timestamp.png"
$img.Save($tempFile, [System.Drawing.Imaging.ImageFormat]::Png)
$img.Dispose()

$adb = "C:\Program Files\BlueStacks_nxt\HD-Adb.exe"
if (-not (Test-Path $adb)) {
    Write-Host "[ERROR] BlueStacks ADB not found at $adb" -ForegroundColor Red
    pause
    exit 1
}

# Find all running BlueStacks devices
$devices = (& $adb devices) | Where-Object { $_ -match "\tdevice$" } | ForEach-Object { ($_ -split "\t")[0] }

if ($null -eq $devices -or $devices.Count -eq 0) {
    Write-Host "[ERROR] No running BlueStacks instance detected!" -ForegroundColor Red
    pause
    exit 1
}

foreach ($dev in $devices) {
    Write-Host ">>> Importing screenshot to BlueStacks device: $dev..." -ForegroundColor Green
    & $adb -s $dev shell mkdir -p /sdcard/Pictures/Screenshots
    & $adb -s $dev push $tempFile "/sdcard/Pictures/Screenshots/Screenshot_$timestamp.png" | Out-Null
    & $adb -s $dev shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file:///sdcard/Pictures/Screenshots/Screenshot_$timestamp.png" | Out-Null
}

Write-Host "`n[SUCCESS] Screenshot imported to BlueStacks Gallery!" -ForegroundColor Green
Write-Host "You can now open Quiz & Earn, tap 'Upload Screenshot', and select it from Gallery to claim your 350 Coins!`n" -ForegroundColor Yellow
Start-Sleep -Seconds 4

