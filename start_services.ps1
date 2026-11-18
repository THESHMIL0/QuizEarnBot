$attached = adb devices | Select-String "127\.0\.0\.1:\d+\s+device"
$devices = @()
foreach ($line in $attached) {
    $devices += ($line.Line -split "\s+")[0]
}
if ($devices.Count -eq 0) {
    $devices = @("127.0.0.1:5555", "127.0.0.1:5585", "127.0.0.1:5596", "127.0.0.1:5605")
}

$svcComponent = "com.quizearn.assistant/com.quizearn.assistant.QuizEarnAccessibilityService"

foreach ($dev in $devices) {
    Write-Host ">>> Starting overlay service on $dev..."
    $sdkRaw = (adb -s $dev shell getprop ro.build.version.sdk 2>$null)
    $sdkInt = 30
    if ($sdkRaw -and ($sdkRaw.Trim() -match "^\d+$")) {
        $sdkInt = [int]$sdkRaw.Trim()
    }

    adb -s $dev shell appops set com.quizearn.assistant SYSTEM_ALERT_WINDOW allow 2>$null
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

    $offerAction = if ($dev -eq "127.0.0.1:5555") { "com.quizearn.assistant.AUTO_OFFER_ON" } else { "com.quizearn.assistant.AUTO_OFFER_OFF" }
    if ($sdkInt -ge 26) {
        adb -s $dev shell am start-foreground-service -n com.quizearn.assistant/.FloatingOverlayService
        Start-Sleep -Milliseconds 400
        adb -s $dev shell am start-foreground-service -a com.quizearn.assistant.START -n com.quizearn.assistant/.FloatingOverlayService
        adb -s $dev shell am start-foreground-service -a $offerAction -n com.quizearn.assistant/.FloatingOverlayService
    } else {
        adb -s $dev shell am startservice -n com.quizearn.assistant/.FloatingOverlayService
        Start-Sleep -Milliseconds 400
        adb -s $dev shell am startservice -a com.quizearn.assistant.START -n com.quizearn.assistant/.FloatingOverlayService
        adb -s $dev shell am startservice -a $offerAction -n com.quizearn.assistant/.FloatingOverlayService
    }
}