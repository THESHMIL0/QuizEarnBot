$attached = adb devices | Select-String "127\.0\.0\.1:\d+\s+device"
$devices = @()
foreach ($line in $attached) {
    $devices += ($line.Line -split "\s+")[0]
}
if ($devices.Count -eq 0) {
    $devices = @("127.0.0.1:5555", "127.0.0.1:5585", "127.0.0.1:5596", "127.0.0.1:5605")
}

Write-Host "=== BlueStacks Multi-Instance Status ==="
foreach ($d in $devices) {
    $sdkRaw = (adb -s $d shell getprop ro.build.version.sdk 2>$null)
    $sdk = if ($sdkRaw) { $sdkRaw.Trim() } else { "N/A" }

    $sizeRaw = (adb -s $d shell wm size 2>$null)
    $size = if ($sizeRaw) { $sizeRaw.Trim() } else { "N/A" }

    $a11yRaw = (adb -s $d shell settings get secure enabled_accessibility_services 2>$null)
    $a11y = if ($a11yRaw) { $a11yRaw.Trim() } else { "None" }

    $pidOfRaw = (adb -s $d shell pidof com.quizearn.assistant 2>$null)
    $pidOf = if ($pidOfRaw) { $pidOfRaw.Trim() } else { "" }

    $appPidRaw = (adb -s $d shell pidof com.quiz.earn.money.game 2>$null)
    $appPid = if ($appPidRaw) { $appPidRaw.Trim() } else { "" }

    $cashRaw = (adb -s $d shell pidof com.rayole.cashmafia2 2>$null)
    $cashPid = if ($cashRaw) { $cashRaw.Trim() } else { "" }

    $focusRaw = (adb -s $d shell "dumpsys window | grep mCurrentFocus" 2>$null)
    $focus = if ($focusRaw) { $focusRaw.Trim() } else { "" }

    Write-Host "[$d] SDK $sdk | $size | Assistant PID: '$pidOf' | Quiz PID: '$appPid' | CashMafia PID: '$cashPid'"
    Write-Host "    A11y: $a11y"
    Write-Host "    Focus: $focus"
}
