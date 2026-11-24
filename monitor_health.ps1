$devices = @("127.0.0.1:5555", "127.0.0.1:5585", "127.0.0.1:5595")
$timestamp = (Get-Date).ToString("yyyy-MM-dd HH:mm:ss")
Write-Host "================== [$timestamp] 3-HOUR MONITOR HEALTH CHECK =================="

foreach ($d in $devices) {
    # 1. Connectivity & Auto-Reconnect
    $rawState = (& adb -s $d get-state 2>&1 | Out-String).Trim()
    if ($rawState -ne "device") {
        Write-Host "[$d] Port offline/desynced ($rawState)! Auto-reconnecting ADB..."
        & adb disconnect $d | Out-Null
        Start-Sleep -Milliseconds 500
        & adb connect $d | Out-Null
        Start-Sleep -Milliseconds 500
        $rawState = (& adb -s $d get-state 2>&1 | Out-String).Trim()
        Write-Host "[$d] Reconnect result: $rawState"
    }

    # 2. Fast PID checks
    $appPid = (& adb -s $d shell pidof com.quiz.earn.money.game 2>&1 | Out-String).Trim()
    $botPid = (& adb -s $d shell pidof com.quizearn.assistant 2>&1 | Out-String).Trim()

    Write-Host "[$d] Quiz App PID: '$appPid' | Assistant PID: '$botPid'"

    # 3. Auto-relaunch Quiz & Earn if not running
    if ([string]::IsNullOrWhiteSpace($appPid)) {
        Write-Host "  -> Launching Quiz & Earn on $d..."
        & adb -s $d shell monkey -p com.quiz.earn.money.game -c android.intent.category.LAUNCHER 1 | Out-Null
    }

    # 4. Auto-restart Assistant if not running
    if ([string]::IsNullOrWhiteSpace($botPid)) {
        Write-Host "  -> Starting Assistant on $d..."
        & adb -s $d shell am start-foreground-service -n com.quizearn.assistant/.FloatingOverlayService | Out-Null
        & adb -s $d shell am broadcast -a com.quizearn.assistant.START_SOLVING | Out-Null
    }
}
Write-Host "================================================================================"
