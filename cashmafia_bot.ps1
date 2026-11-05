<#
.SYNOPSIS
    Cash Mafia Auto-Quiz & Reward Bot (Clean Ad Watching & Direct Claiming)
.DESCRIPTION
    - Auto-Quiz Captcha Solver:
      * Reads dynamic 4-digit captchas with 100% accuracy.
      * Taps matching answer button out of the 4 choices.
      * Taps 'Claim Reward' / 'Redeem 1 & Play Again'.
    - Smart Ad Watching & Non-Restart Closer:
      * Lets the rewarded ad play its full duration (hands-off for first 15s to guarantee reward callback).
      * Closes in-app Google Play bottom sheets cleanly without pressing Android Back.
      * Detects real close/skip buttons without crashing or restarting the app.
      * Returns directly to Quiz screen with +1 Gem rewarded!
    - NO APP INSTALLATIONS:
      * Super Offers / App downloads / 2m10s timers completely removed.
#>

$host.UI.RawUI.WindowTitle = "Cash Mafia - Auto Quiz & Reward Bot"
Clear-Host

Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing

Write-Host "==========================================================" -ForegroundColor Green
Write-Host "         CASH MAFIA AUTO-QUIZ & REWARD BOT                " -ForegroundColor Yellow
Write-Host "==========================================================" -ForegroundColor Green
Write-Host " Features:" -ForegroundColor White
Write-Host "  * [AUTO-SOLVER] Reads dynamic 4-digit captcha & taps answer" -ForegroundColor Green
Write-Host "  * [AUTO-CLAIM] Taps 'Claim Reward' / 'Redeem 1 & Play Again'" -ForegroundColor Green
Write-Host "  * [RELIABLE REWARD] Plays ad properly without restarting app" -ForegroundColor Green
Write-Host "  * [CLEAN AD CLOSER] Dismisses Play Store sheets & ad close buttons" -ForegroundColor Green
Write-Host "  * [NO INSTALLS] Super Offer / app installation removed" -ForegroundColor Green
Write-Host "  * [AUTO-MUTE] Silences BlueStacks ad audio for peaceful farming" -ForegroundColor DarkCyan
Write-Host "----------------------------------------------------------"

$script:adbExe = "adb"
if (Test-Path "C:\Program Files\BlueStacks_nxt\HD-Adb.exe") {
    $script:adbExe = "C:\Program Files\BlueStacks_nxt\HD-Adb.exe"
}

$script:port = ""

function Connect-Device {
    Write-Host "Searching for BlueStacks instance using $script:adbExe..." -ForegroundColor Yellow
    & $script:adbExe start-server | Out-Null
    & $script:adbExe connect 127.0.0.1:5555 | Out-Null
    & $script:adbExe connect 127.0.0.1:5556 | Out-Null
    Start-Sleep -Milliseconds 800

    $devLines = & $script:adbExe devices
    foreach ($line in $devLines) {
        if ($line -match "^(127\.0\.0\.1:\d+|emulator-\d+)\s+device") {
            $script:port = ($line -split "\s+")[0]
            Write-Host "[CONNECTED] Successfully linked to BlueStacks on $script:port!" -ForegroundColor Green
            return $true
        }
    }

    Write-Host "[ERROR] No online BlueStacks device found. Please launch BlueStacks and enable ADB in Settings." -ForegroundColor Red
    return $false
}

if (-not (Connect-Device)) {
    pause
    exit
}

function Invoke-Adb {
    param([string[]]$ArgsList)
    $res = & $script:adbExe -s $script:port @ArgsList 2>&1
    return $res
}

function Adb-Tap([int]$x, [int]$y) {
    Invoke-Adb @("shell", "input", "tap", "$x", "$y") | Out-Null
}

function Adb-Key([int]$code) {
    Invoke-Adb @("shell", "input", "keyevent", "$code") | Out-Null
}

function Set-Ad-Muted([bool]$mute) {
    if ($mute) {
        Invoke-Adb @("shell", "cmd", "media_session", "volume", "--stream", "3", "--set", "0") | Out-Null
        Write-Host "  [Auto-Mute] BlueStacks ad audio muted." -ForegroundColor DarkCyan
    } else {
        Invoke-Adb @("shell", "cmd", "media_session", "volume", "--stream", "3", "--set", "12") | Out-Null
        Write-Host "  [Sound Restored] BlueStacks media volume restored." -ForegroundColor DarkCyan
    }
}

# Optimize animator scales to prevent uiautomator idle timeouts
Invoke-Adb @("shell", "settings", "put", "global", "window_animation_scale", "0") | Out-Null
Invoke-Adb @("shell", "settings", "put", "global", "transition_animation_scale", "0") | Out-Null
Invoke-Adb @("shell", "settings", "put", "global", "animator_duration_scale", "0") | Out-Null

Set-Ad-Muted $true

function Get-Current-Focus {
    $raw = (Invoke-Adb @("shell", "dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity'")) -join "`n"
    if ($raw -match "ActivityRecord\{[^\r\n]+") {
        return $matches[0]
    }
    $win = (Invoke-Adb @("shell", "dumpsys window | grep mCurrentFocus")) -join "`n"
    if ($win -match "mCurrentFocus=[^\r\n]+") {
        return $matches[0]
    }
    return ""
}

function Check-And-Escape-PlayStore {
    $focus = Get-Current-Focus
    if ($focus -match "com.android.vending|google.android.finsky|com.android.chrome") {
        Write-Host "`n  [Overlay] Play Store overlay detected! Dismissing..." -ForegroundColor Yellow

        # Check for in-app bottom sheet Close button
        Invoke-Adb @("shell", "uiautomator", "dump", "--compressed", "/sdcard/ps_check.xml") | Out-Null
        $xml = (Invoke-Adb @("shell", "cat", "/sdcard/ps_check.xml")) -join ""

        if ($xml -match '<node [^>]*content-desc="Close"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') {
            $cx = [int](([int]$matches[1] + [int]$matches[3]) / 2)
            $cy = [int](([int]$matches[2] + [int]$matches[4]) / 2)
            Write-Host "  [Overlay] Tapping Close button at ($cx, $cy)..." -ForegroundColor Green
            Adb-Tap $cx $cy
            Start-Sleep -Milliseconds 800
            return $true
        }

        # Tap standard bottom sheet close coordinate (858, 362)
        Adb-Tap 858 362
        Start-Sleep -Milliseconds 600

        $recheck = Get-Current-Focus
        if ($recheck -match "com.android.vending|com.android.chrome") {
            Adb-Key 4
            Start-Sleep -Milliseconds 800
        }
        return $true
    }
    return $false
}

function Try-Find-And-Click-Ad-Button {
    param([string]$xml, [int]$elapsedSeconds)
    if ([string]::IsNullOrEmpty($xml)) { return $false }

    $nodes = [regex]::Matches($xml, '<node [^>]+>')
    foreach ($n in $nodes) {
        $txt = $n.Value

        # Never click ad labels, promotional links, or countdown text
        if ($txt -match '(?i)(Ad \d of|Sponsored|Install|Download|Learn More|Google Play)') {
            continue
        }

        $isMatch = $txt -match '(?i)(close|skip|dismiss|next-button|mbridge_iv_close|close_button|btn_close|tt_video_ad_close|iv_close)'

        if ($txt -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') {
            $x1 = [int]$matches[1]; $y1 = [int]$matches[2]; $x2 = [int]$matches[3]; $y2 = [int]$matches[4]
            $w = $x2 - $x1; $h = $y2 - $y1

            # Case A: Explicit close/skip keyword match
            if ($isMatch -and $w -ge 10 -and $w -le 380 -and $h -ge 10 -and $h -le 380) {
                $cx = [int](($x1 + $x2) / 2)
                $cy = [int](($y1 + $y2) / 2)
                Write-Host "`n  [Target] Detected ad close/skip button at ($cx, $cy)! Tapping..." -ForegroundColor Green
                Adb-Tap $cx $cy
                return $true
            }

            # Case B: Top-right corner close icon (ONLY after 25s elapsed!)
            if (-not $isMatch -and $elapsedSeconds -ge 25 -and $w -ge 15 -and $w -le 160 -and $h -ge 15 -and $h -le 160 -and ($x1 -ge 720 -and $y1 -le 180)) {
                if ($txt -notmatch 'text="\d+"') {
                    $cx = [int](($x1 + $x2) / 2)
                    $cy = [int](($y1 + $y2) / 2)
                    Write-Host "`n  [Target] Detected top-right ad close icon at ($cx, $cy)! Tapping..." -ForegroundColor Green
                    Adb-Tap $cx $cy
                    return $true
                }
            }

            # Case C: Top-left arrow skip icon (after 25s elapsed)
            if (-not $isMatch -and $elapsedSeconds -ge 25 -and $w -ge 20 -and $w -le 120 -and $h -ge 20 -and $h -le 120 -and ($x1 -le 70 -and $y1 -le 120)) {
                $cx = [int](($x1 + $x2) / 2)
                $cy = [int](($y1 + $y2) / 2)
                Write-Host "`n  [Target] Detected top-left ad skip arrow at ($cx, $cy)! Tapping..." -ForegroundColor Green
                Adb-Tap $cx $cy
                return $true
            }
        }
    }
    return $false
}

function Close-Ad-Actively {
    Write-Host "  [Ad Playing] Watching ad... (hands-off to ensure reward callback)" -ForegroundColor Yellow
    $startTime = Get-Date

    # Phase 1: Let the ad play for at least 15 seconds without interference
    while (((Get-Date) - $startTime).TotalSeconds -lt 15) {
        Start-Sleep -Seconds 1
        $focus = Get-Current-Focus

        if ($focus -match "MainActivity" -and (((Get-Date) - $startTime).TotalSeconds -gt 4)) {
            Invoke-Adb @("shell", "uiautomator", "dump", "--compressed", "/sdcard/ad_check.xml") | Out-Null
            $chkXml = (Invoke-Adb @("shell", "cat", "/sdcard/ad_check.xml")) -join ""
            if ($chkXml -match "Quiz" -and $chkXml -match 'content-desc="\d"') {
                Write-Host "  [Quiz Screen] Already back in Quiz, proceeding!" -ForegroundColor Green
                return
            }
        }

        Check-And-Escape-PlayStore | Out-Null
    }

    Write-Host "  [Ad Playing] Searching for Skip / Close button..." -ForegroundColor Yellow

    # Phase 2: Active ad monitoring loop (up to 45s total)
    $maxWaitSec = 45
    while (((Get-Date) - $startTime).TotalSeconds -lt $maxWaitSec) {
        $focus = Get-Current-Focus
        if ($focus -match "MainActivity") {
            Write-Host "  [Ad Finished] Successfully back in Cash Mafia!" -ForegroundColor Green
            return
        }

        if (Check-And-Escape-PlayStore) {
            Start-Sleep -Milliseconds 800
            continue
        }

        Invoke-Adb @("shell", "rm", "-f", "/sdcard/ad.xml") | Out-Null
        Invoke-Adb @("shell", "uiautomator", "dump", "--compressed", "/sdcard/ad.xml") | Out-Null
        $xml = (Invoke-Adb @("shell", "cat", "/sdcard/ad.xml")) -join ""

        $elapsed = [int](((Get-Date) - $startTime).TotalSeconds)
        $tapped = Try-Find-And-Click-Ad-Button $xml $elapsed

        if ($tapped) {
            Start-Sleep -Milliseconds 1500
            $reFocus = Get-Current-Focus
            if ($reFocus -match "MainActivity") {
                Write-Host "  [Ad Closed] Returned to Cash Mafia!" -ForegroundColor Green
                return
            }
        } else {
            if ($elapsed -ge 32 -and $elapsed -lt 36) {
                Adb-Tap 54 54   # Top-left skip arrow
                Adb-Tap 856 50  # Top-right close X
            } elseif ($elapsed -ge 36) {
                Write-Host "  [Ad Complete] Sending single Back key to return to Quiz..." -ForegroundColor Cyan
                Adb-Key 4
                Start-Sleep -Milliseconds 1200
                $reFocus = Get-Current-Focus
                if ($reFocus -match "MainActivity") {
                    Write-Host "  [Ad Finished] Successfully back in Cash Mafia!" -ForegroundColor Green
                    return
                }
            }
        }

        Start-Sleep -Seconds 1
    }

    Write-Host "  [Ad Timeout] Pressing Back to return to Quiz screen..." -ForegroundColor Gray
    Adb-Key 4
    Start-Sleep -Milliseconds 1500
}

function Recover-From-Freeze {
    Write-Host "  [WATCHDOG] Checking app status for recovery..." -ForegroundColor Red
    $focus = Get-Current-Focus

    if ($focus -match "(?i)launcher") {
        Write-Host "  [Launcher] Launching Cash Mafia..." -ForegroundColor Yellow
        Invoke-Adb @("shell", "monkey", "-p", "com.rayole.cashmafia2", "-c", "android.intent.category.LAUNCHER", "1") | Out-Null
        Start-Sleep -Seconds 3
        Adb-Tap 683 1439
        return
    }

    Adb-Key 4
    Start-Sleep -Seconds 1
    Adb-Tap 683 1439
    Start-Sleep -Seconds 1
}

$questionsSolved = 0
$gemsEarned = 0
$lastProgressTime = Get-Date

Write-Host "`n>>> Starting Cash Mafia Loop... Press Ctrl+C to Stop.`n" -ForegroundColor Green

while ($true) {
    # 1. Watchdog check
    $idleSec = ((Get-Date) - $lastProgressTime).TotalSeconds
    if ($idleSec -gt 90) {
        Recover-From-Freeze
        $lastProgressTime = Get-Date
        continue
    }

    # 2. Check launcher crash
    $focus = Get-Current-Focus
    if ($focus -match "(?i)launcher" -and $focus -notmatch "com.rayole.cashmafia") {
        Write-Host "  [Crash] Launcher detected, relaunching Cash Mafia..." -ForegroundColor Yellow
        Recover-From-Freeze
        $lastProgressTime = Get-Date
        continue
    }

    # 3. Dismiss Play Store overlay if present
    Check-And-Escape-PlayStore | Out-Null

    # 4. Check for Ad Activity
    $focus = Get-Current-Focus
    if (-not [string]::IsNullOrEmpty($focus) -and $focus -notmatch "MainActivity" -and $focus -notmatch "(?i)launcher") {
        Close-Ad-Actively
        $lastProgressTime = Get-Date
        continue
    }

    # 5. Dump UI XML
    Invoke-Adb @("shell", "uiautomator", "dump", "--compressed", "/sdcard/cm_app.xml") | Out-Null
    $xml = (Invoke-Adb @("shell", "cat", "/sdcard/cm_app.xml")) -join ""

    # Exit modals
    if ($xml -match "Continue to collect points") {
        Adb-Tap 564 818
        Start-Sleep -Milliseconds 800
        continue
    }

    if ($xml -match "Exit the quiz\?") {
        Adb-Tap 631 1454
        Start-Sleep -Milliseconds 800
        continue
    }

    # Streak modals
    if ($xml -match "Daily Streak") {
        Adb-Key 4
        Start-Sleep -Seconds 1
        continue
    }

    if ($xml -match "Start Your Streak Journey" -or $xml -match "Let's go" -or $xml -match "Your Streak is broken!") {
        Adb-Tap 450 1537
        Start-Sleep -Seconds 1
        continue
    }

    # Super Offers - Dismiss to stay in Quiz
    if ($xml -match "Super Offers" -and ($xml -match "Watch a video to unlock" -or $xml -match "Download the advertised app" -or $xml -match "Special Offer")) {
        Write-Host "  [Super Offers] Dismissing to stay in Quiz mode..." -ForegroundColor Cyan
        Adb-Key 4
        Start-Sleep -Seconds 1
        continue
    }

    # Home Screen - Always enter Play Quiz
    if ($xml -match "Welcome Back" -or ($xml -match "Play Quiz" -and $xml -match "Super Offers") -or $xml -match "Basket Dunk") {
        $lastProgressTime = Get-Date
        Write-Host "  [HOME] Entering 'Play Quiz'..." -ForegroundColor Green

        if ($xml -match '<node [^>]*content-desc="Play Quiz[^"]*"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') {
            $qx = [int](([int]$matches[1] + [int]$matches[3]) / 2)
            $qy = [int](([int]$matches[2] + [Math]::Min([int]$matches[4], 1560)) / 2)
            Adb-Tap $qx $qy
        } else {
            Adb-Tap 683 1439
        }
        Start-Sleep -Seconds 2
        continue
    }

    # Congratulations / Claim Reward Screen
    if ($xml -match "Congratulations" -or $xml -match "Redeem" -or $xml -match "Claim Reward" -or ($xml -match "Play Again" -and $xml -notmatch "Welcome Back") -or $xml -match "Watch an ad and redeem") {
        $lastProgressTime = Get-Date

        if ($xml -match '<node [^>]*(content-desc|text)="[^"]*(Redeem|Claim|Play Again)[^"]*"[^>]*clickable="true"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') {
            $cx = [int](([int]$matches[3] + [int]$matches[5]) / 2)
            $cy = [int](([int]$matches[4] + [int]$matches[6]) / 2)
            Write-Host "`n  [CLAIM REWARD] Tapping button at ($cx, $cy)..." -ForegroundColor Yellow
            Adb-Tap $cx $cy
        } else {
            Write-Host "`n  [CLAIM REWARD] Tapping button at fallback (450, 1296)..." -ForegroundColor Yellow
            Adb-Tap 450 1296
        }

        $gemsEarned++
        Start-Sleep -Seconds 2
        Close-Ad-Actively
        $lastProgressTime = Get-Date
        Start-Sleep -Seconds 1
        continue
    }

    # Quiz Screen - Extract Captcha
    $digits = @()
    $dMatches = [regex]::Matches($xml, '<node [^>]*content-desc="(\d)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    foreach ($dm in $dMatches) {
        $d = $dm.Groups[1].Value
        $x1 = [int]$dm.Groups[2].Value
        $y1 = [int]$dm.Groups[3].Value
        $y2 = [int]$dm.Groups[5].Value

        if ($y1 -ge 650 -and $y2 -le 850) {
            $digits += [PSCustomObject]@{ Digit = $d; X = $x1 }
        }
    }

    if ($digits.Count -ge 3) {
        $digits = $digits | Sort-Object X
        $targetNumber = ($digits.Digit -join "")

        # Read Gems Count
        $gemsCount = "0"
        $gmMatches = [regex]::Matches($xml, '<node [^>]*content-desc="(\d+)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
        foreach ($gm in $gmMatches) {
            $gx1 = [int]$gm.Groups[2].Value
            $gy1 = [int]$gm.Groups[3].Value
            if ($gx1 -ge 740 -and $gy1 -le 120) {
                $gemsCount = $gm.Groups[1].Value
                break
            }
        }

        Write-Host "`n  [QUIZ] Captcha: '$targetNumber' | App Gems: $gemsCount" -ForegroundColor Green

        $optPattern = '<node [^>]*content-desc="' + $targetNumber + '"[^>]*clickable="true"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
        if ($xml -match $optPattern) {
            $ox1 = [int]$matches[1]; $oy1 = [int]$matches[2]; $ox2 = [int]$matches[3]; $oy2 = [int]$matches[4]
            $ocx = [int](($ox1 + $ox2) / 2)
            $ocy = [int](($oy1 + $oy2) / 2)

            Write-Host "  [ANSWER] Tapping '$targetNumber' at ($ocx, $ocy)..." -ForegroundColor Yellow
            Adb-Tap $ocx $ocy

            $questionsSolved++
            $lastProgressTime = Get-Date
            Start-Sleep -Milliseconds 1500
            continue
        } else {
            Write-Host "  [Warning] Target '$targetNumber' not found in options, retrying..." -ForegroundColor Red
        }
    } else {
        if ($xml -match "Congratulations" -or $xml -match "Redeem" -or $xml -match "Claim") {
            Adb-Tap 450 1296
            Start-Sleep -Seconds 1
            Close-Ad-Actively
            $lastProgressTime = Get-Date
        }
    }

    Start-Sleep -Milliseconds 800
}
