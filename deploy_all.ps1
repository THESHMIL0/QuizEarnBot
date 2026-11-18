$apkPath = Join-Path $PSScriptRoot "QuizEarnBot.apk"
if (-not (Test-Path $apkPath)) {
    Write-Error "QuizEarnBot.apk not found at $apkPath. Run build_apk.ps1 first."
    exit 1
}

# Probe common BlueStacks 5 multi-instance ADB ports automatically
$candidatePorts = @(5555, 5565, 5575, 5585, 5595, 5596, 5605, 5615)
foreach ($p in $candidatePorts) {
    adb connect "127.0.0.1:$p" 2>$null | Out-Null
}

$attached = adb devices | Select-String "127\.0\.0\.1:\d+\s+device"
$devices = @()
foreach ($line in $attached) {
    $devices += ($line.Line -split "\s+")[0]
}
$devices = $devices | Select-Object -Unique

if ($devices.Count -eq 0) {
    Write-Warning "No online BlueStacks ADB instances detected. Check BlueStacks > Settings > Advanced > Android Debug Bridge."
    exit 1
}

Write-Host "Found BlueStacks devices: $($devices -join ', ')"
$svcComponent = "com.quizearn.assistant/com.quizearn.assistant.QuizEarnAccessibilityService"

foreach ($dev in $devices) {
    Write-Host ">>> Deploying to $dev..."
    $sdkRaw = (adb -s $dev shell getprop ro.build.version.sdk 2>$null)
    $sdkInt = 30
    if ($sdkRaw -and ($sdkRaw.Trim() -match "^\d+$")) {
        $sdkInt = [int]$sdkRaw.Trim()
    }

    # -r replace, -d downgrade, -g grant runtime permissions (API 23+)
    adb -s $dev install -r -d -g $apkPath
    adb -s $dev shell appops set com.quizearn.assistant SYSTEM_ALERT_WINDOW allow 2>$null
    if ($sdkInt -ge 33) {
        adb -s $dev shell appops set com.quizearn.assistant POST_NOTIFICATION allow 2>$null
    }
    adb -s $dev shell dumpsys deviceidle whitelist +com.quizearn.assistant 2>$null | Out-Null

    # Append accessibility service safely without clobbering existing enabled services
    $existingA11y = (adb -s $dev shell settings get secure enabled_accessibility_services 2>$null).Trim()
    if ([string]::IsNullOrEmpty($existingA11y) -or $existingA11y -eq "null") {
        $newA11y = $svcComponent
    } elseif ($existingA11y -notlike "*$svcComponent*") {
        $newA11y = "$($existingA11y):$svcComponent"
    } else {
        $newA11y = $existingA11y
    }

    adb -s $dev shell settings put secure enabled_accessibility_services $newA11y
    adb -s $dev shell settings put secure accessibility_enabled 1

    # API-aware service start: Android 7.x (API < 26) does NOT support start-foreground-service
    if ($sdkInt -ge 26) {
        adb -s $dev shell am start-foreground-service -n com.quizearn.assistant/.FloatingOverlayService
        Start-Sleep -Milliseconds 400
        adb -s $dev shell am start-foreground-service -a com.quizearn.assistant.START -n com.quizearn.assistant/.FloatingOverlayService
        $offerAction = if ($dev -eq "127.0.0.1:5555") { "com.quizearn.assistant.AUTO_OFFER_ON" } else { "com.quizearn.assistant.AUTO_OFFER_OFF" }
        adb -s $dev shell am start-foreground-service -a $offerAction -n com.quizearn.assistant/.FloatingOverlayService
    } else {
        adb -s $dev shell am startservice -n com.quizearn.assistant/.FloatingOverlayService
        Start-Sleep -Milliseconds 400
        adb -s $dev shell am startservice -a com.quizearn.assistant.START -n com.quizearn.assistant/.FloatingOverlayService
        $offerAction = if ($dev -eq "127.0.0.1:5555") { "com.quizearn.assistant.AUTO_OFFER_ON" } else { "com.quizearn.assistant.AUTO_OFFER_OFF" }
        adb -s $dev shell am startservice -a $offerAction -n com.quizearn.assistant/.FloatingOverlayService
    }

    $role = if ($dev -eq "127.0.0.1:5555") { "Player 1 (Master Auto-Offer ON)" } else { "Gem Farmer (Auto-Offer OFF)" }
    Write-Host "    Deployed SDK $sdkInt -> $role on $dev"
}