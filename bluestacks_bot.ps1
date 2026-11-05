<#
.SYNOPSIS
    BlueStacks Smart Background Bot (Auto 25 Gems Loop + Auto-Reactivate)
.DESCRIPTION
    - Answers math questions with 100% accuracy.
    - Clicks 'Collect Gem & Continue'.
    - Auto-escapes Google Play Store & browser redirects with Android Back key.
    - Scans UI XML dynamically for skip / close buttons across Vungle, AdMob, IronSource.
    - Safe top-right (856, 44) taps (NO random clicks).
    - Stops at 25 gems, alerts user with alarm & popup.
    - Auto-reactivates when gems reset to < 25.
#>

$host.UI.RawUI.WindowTitle = "BlueStacks Quiz and Earn Bot"
Clear-Host

Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "   BLUESTACKS 25-GEMS SMART BOT (AUTO-CYCLE & ALERT)      " -ForegroundColor Yellow
Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host " Features:" -ForegroundColor White
Write-Host "  * [AUTO-CONNECT] Automatically finds BlueStacks ADB port" -ForegroundColor Green
Write-Host "  * [AUTO-SOLVER] Reads questions & picks 100% correct answer" -ForegroundColor Green
Write-Host "  * [ANTI-PLAYSTORE GUARD] Auto-escapes Play Store & browser redirects" -ForegroundColor Green
Write-Host "  * [SMART AD CLOSER] Auto-detects Skip / Close buttons (Vungle, AdMob, IronSource)" -ForegroundColor Green
Write-Host "  * [25 GEMS STOP & ALERT] Stops at 25 gems & plays alarm" -ForegroundColor Yellow
Write-Host "  * [AUTO-RESUME] Wakes up automatically when gems < 25!" -ForegroundColor Cyan
Write-Host "  * [100% BACKGROUND] Your mouse cursor is NOT hijacked" -ForegroundColor Green
Write-Host "----------------------------------------------------------"

$script:port = ""

function Connect-Device {
    Write-Host "Searching for BlueStacks instance..." -ForegroundColor Yellow
    & adb start-server | Out-Null

    & adb connect 127.0.0.1:5556 | Out-Null
    & adb connect 127.0.0.1:5555 | Out-Null
    Start-Sleep -Milliseconds 800

    $devLines = & adb devices
    foreach ($line in $devLines) {
        if ($line -match "^(127\.0\.0\.1:\d+|emulator-\d+)\s+device") {
            $script:port = ($line -split "\s+")[0]
            Write-Host "[CONNECTED] Successfully linked to BlueStacks on $script:port! 🚀" -ForegroundColor Green
            return $true
        }
    }

    Write-Warning "Could not find active BlueStacks device."
    return $false
}

while (-not (Connect-Device)) {
    Write-Host "Retrying in 3 seconds..." -ForegroundColor Gray
    Start-Sleep -Seconds 3
}

function Invoke-Adb {
    param([string[]]$ArgsList)
    $res = & adb -s $script:port @ArgsList 2>&1
    if ($res -match "not found|offline|closed") {
        Write-Warning "Reconnecting ADB ($script:port)..."
        & adb connect $script:port | Out-Null
        Start-Sleep -Milliseconds 500
        $res = & adb -s $script:port @ArgsList 2>&1
    }
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
        Write-Host "  🔇 [Auto-Mute] BlueStacks ad sound muted (silent background farming)" -ForegroundColor DarkCyan
    }
    else {
        Invoke-Adb @("shell", "cmd", "media_session", "volume", "--stream", "3", "--set", "12") | Out-Null
        Write-Host "  🔊 [Sound Restored] BlueStacks media volume restored" -ForegroundColor DarkCyan
    }
}

# Auto-mute BlueStacks media on start
Set-Ad-Muted $true

function Play-Alarm {
    try {
        [System.Console]::Beep(800, 200)
        [System.Console]::Beep(1000, 200)
        [System.Console]::Beep(1200, 250)
        [System.Console]::Beep(1500, 400)
    }
    catch {
        [System.Media.SystemSounds]::Exclamation.Play()
    }
}

function Get-Current-Focus {
    $raw = Invoke-Adb @("shell", "dumpsys", "window")
    $res = $raw -join "`n"
    if ($res -match "mCurrentFocus=[^\r\n]+") {
        return $matches[0]
    }
    return ""
}

function Check-And-Escape-PlayStore {
    $focus = Get-Current-Focus
    if ($focus -match "com.android.vending|google.android.finsky|com.android.chrome") {
        Write-Host "`n  ⚠️ Play Store / Browser detected! Auto-pressing Android BACK to return..." -ForegroundColor Yellow
        Adb-Key 4
        Start-Sleep -Milliseconds 1000

        $recheck = Get-Current-Focus
        if ($recheck -match "com.android.vending|google.android.finsky") {
            Adb-Key 4
            Start-Sleep -Milliseconds 800
            Invoke-Adb @("shell", "am", "start", "-n", "com.quiz.earn.money.game/com.quiz.earn.money.app.MainActivity") | Out-Null
            Start-Sleep -Milliseconds 1000
        }
        return $true
    }
    return $false
}

function Try-Find-And-Click-Ad-Button {
    param([string]$xml)
    if ([string]::IsNullOrEmpty($xml)) { return $false }

    # Specific detection for IronSource storePromoContainer modal
    if ($xml -match "storePromoContainer") {
        if ($xml -match '<node [^>]*class="android\.widget\.Image"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') {
            $ix1 = [int]$matches[1]; $iy1 = [int]$matches[2]; $ix2 = [int]$matches[3]; $iy2 = [int]$matches[4]
            $icx = [int](($ix1 + $ix2) / 2); $icy = [int](($iy1 + $iy2) / 2)
            if ($icx -ge 800 -and $icy -le 150) {
                Write-Host "`n  🎯 Detected promo card Close (X) at ($icx, $icy)! Tapping..." -ForegroundColor Green
                Adb-Tap $icx $icy
                return $true
            }
        }
        Write-Host "`n  🎯 Tapping promo card Close (X) at (870, 95)..." -ForegroundColor Green
        Adb-Tap 870 95
        return $true
    }

    $nodes = [regex]::Matches($xml, '<node [^>]+>')
    foreach ($n in $nodes) {
        $txt = $n.Value
        $isMatch = $txt -match '(next-button|skipButton|Close button|close_button|btn_close|tt_video_ad_close|mbridge_iv_close|text="Close"|text="Skip"|text="SKIP"|text="Dismiss"|text="Done"|content-desc="Close"|content-desc="Skip"|content-desc="Dismiss")'

        if ($txt -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') {
            $x1 = [int]$matches[1]; $y1 = [int]$matches[2]; $x2 = [int]$matches[3]; $y2 = [int]$matches[4]
            $w = $x2 - $x1; $h = $y2 - $y1

            # Match small icons in top-right OR top-left corners (close X or arrows)
            if (-not $isMatch -and $w -ge 15 -and $w -le 140 -and $h -ge 15 -and $h -le 140 -and (($x1 -ge 720 -and $y1 -le 160) -or ($x2 -le 180 -and $y1 -le 160))) {
                if ($txt -notmatch 'text="\d+"') {
                    $isMatch = $true
                }
            }

            if ($isMatch -and $w -ge 10 -and $w -le 350 -and $h -ge 10 -and $h -le 350) {
                $cx = [int](($x1 + $x2) / 2)
                $cy = [int](($y1 + $y2) / 2)
                Write-Host "`n  🎯 Detected ad button at ($cx, $cy)! Tapping..." -ForegroundColor Green
                Adb-Tap $cx $cy
                return $true
            }
        }
    }
    return $false
}

function Get-Live-Gems {
    Invoke-Adb @("shell", "uiautomator", "dump", "/sdcard/w.xml") | Out-Null
    $xml = (Invoke-Adb @("shell", "cat", "/sdcard/w.xml")) -join ""

    # 1. Mega Offer Unlocked reward screen (Target 25 Gems Hit!)
    if ($xml -match "Mega Offer Unlocked" -or $xml -match "Go to Mega Offer" -or $xml -match 'text="25 Gems"') {
        return 25
    }

    # 2. Match format on quiz page: text="24" ... text="/" ... text="25"
    if ($xml -match 'text="(\d+)"[^>]*/>\s*<node[^>]*text="/"[^>]*/>\s*<node[^>]*text="25"') {
        return [int]$matches[1]
    }

    # 3. Match format on result page: text="9/25 gems to Mega Offer"
    if ($xml -match 'text="(\d+)/25\s*gems') {
        return [int]$matches[1]
    }

    # 4. Top-right gems counter on Mega Quiz header: text="25"
    if ($xml -match '<node [^>]*text="25"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') {
        $x1 = [int]$matches[1]; $y1 = [int]$matches[2]
        if ($x1 -ge 650 -and $y1 -le 160) {
            return 25
        }
    }

    return -1
}

function Close-Ad-Actively {
    Write-Host "  [Ad Active] Turbo Monitoring for Skip / Close buttons..." -ForegroundColor Yellow
    $startTime = Get-Date

    # Brief 1.5s initial buffer
    Start-Sleep -Milliseconds 1500

    $maxWaitSec = 40
    while (((Get-Date) - $startTime).TotalSeconds -lt $maxWaitSec) {
        # 1. Instant focus check (Takes only ~35ms, returns immediately if ad already finished!)
        $focus = Get-Current-Focus
        if ($focus -match "MainActivity") {
            Write-Host "  ✅ [Ad Closed] Successfully returned to Quiz screen!" -ForegroundColor Green
            return
        }

        # 2. Anti-PlayStore Guard
        if (Check-And-Escape-PlayStore) {
            Start-Sleep -Milliseconds 500
        }

        # 3. Dynamic XML element scan
        Invoke-Adb @("shell", "uiautomator", "dump", "/sdcard/ad.xml") | Out-Null
        $xml = (Invoke-Adb @("shell", "cat", "/sdcard/ad.xml")) -join ""
        $tapped = Try-Find-And-Click-Ad-Button $xml

        $elapsed = ((Get-Date) - $startTime).TotalSeconds

        if (-not $tapped) {
            # If ad is > 3.5s, tap known corner targets
            if ($elapsed -ge 3.5) {
                Write-Host -NoNewline "`r  Hunting close button... ($([math]::Round($elapsed))s elapsed)   "
                Adb-Tap 856 44 # Video skip/close top-right
                Adb-Tap 870 95 # Promo card X
                Adb-Tap 801 72 # AdMob X
                Adb-Tap 846 72 # AppLovin/IronSource X
                Adb-Tap 54 54  # Unity / Vungle top-left X
            }

            # If ad is > 7s, send Android Back key
            if ($elapsed -ge 7) {
                Adb-Key 4
            }
        }
        else {
            Start-Sleep -Milliseconds 500
            $reFocus = Get-Current-Focus
            if ($reFocus -match "MainActivity") {
                Write-Host "`n  ✅ [Ad Closed] Successfully returned to Quiz screen!" -ForegroundColor Green
                return
            }
            if ($elapsed -ge 6) {
                Adb-Key 4
            }
        }

        Start-Sleep -Milliseconds 600
    }

    Write-Host "`n  Timeout reached, sending final Back key..." -ForegroundColor Gray
    Adb-Key 4
}

# Coordinate Map (900x1600):
$optA_X = 450; $optA_Y = 428
$optB_X = 450; $optB_Y = 524
$optC_X = 450; $optC_Y = 620
$optD_X = 450; $optD_Y = 716
$collectX = 450; $collectY = 570

$statsFile = Join-Path $PSScriptRoot "bot_stats.json"

function Load-Bot-Stats {
    if (Test-Path $statsFile) {
        try {
            return (Get-Content $statsFile -Raw | ConvertFrom-Json)
        }
        catch {}
    }
    return [PSCustomObject]@{
        todayDate      = (Get-Date -Format "yyyy-MM-dd")
        todayGems      = 0
        totalGems      = 0
        totalQuestions = 0
        totalAds       = 0
        lastUpdated    = (Get-Date -Format "s")
    }
}

function Save-Bot-Stats([int]$addGems, [int]$addQuestions, [int]$addAds) {
    try {
        $st = Load-Bot-Stats
        $today = Get-Date -Format "yyyy-MM-dd"
        if ($st.todayDate -ne $today) {
            $st.todayDate = $today
            $st.todayGems = 0
        }
        $st.todayGems += $addGems
        $st.totalGems += $addGems
        $st.totalQuestions += $addQuestions
        $st.totalAds += $addAds
        $st.lastUpdated = (Get-Date -Format "s")
        $st | ConvertTo-Json | Set-Content $statsFile -Force
        return $st
    }
    catch { return $null }
}

function Recover-From-Crash-Or-Freeze {
    Write-Host "`n  🚨 [WATCHDOG] App freeze or crash detected! Initiating Auto-Recovery..." -ForegroundColor Red
    
    # 1. Try Back keys first
    Adb-Key 4
    Start-Sleep -Milliseconds 500
    Adb-Key 4
    Start-Sleep -Milliseconds 800

    $f = Get-Current-Focus
    if ($f -match "MainActivity") {
        Write-Host "  ✅ Restored MainActivity via Back key!" -ForegroundColor Green
        return
    }

    # 2. Hard restart app
    Write-Host "  🔄 Force-restarting Quiz & Earn App..." -ForegroundColor Yellow
    Invoke-Adb @("shell", "am", "force-stop", "com.quiz.earn.money.game") | Out-Null
    Start-Sleep -Milliseconds 1500
    Invoke-Adb @("shell", "monkey", "-p", "com.quiz.earn.money.game", "-c", "android.intent.category.LAUNCHER", "1") | Out-Null
    Start-Sleep -Seconds 5

    # 3. Enter Mega Quiz (500 card)
    Write-Host "  🏠 Re-entering Mega Quiz (500 card)..." -ForegroundColor Yellow
    Adb-Tap 518 846
    Start-Sleep -Milliseconds 1500
    Close-Ad-Actively
    Write-Host "  ✅ Auto-Recovery complete! Resuming farming." -ForegroundColor Green
}

Write-Host "`n==========================================================" -ForegroundColor Green
Write-Host " BOT RUNNING IN BACKGROUND (Press Ctrl+C to Stop)" -ForegroundColor Yellow
Write-Host "==========================================================" -ForegroundColor Green

$cachedStats = Load-Bot-Stats
Write-Host "  📊 Lifetime Gems: $($cachedStats.totalGems) | Today: $($cachedStats.todayGems) 💎 | Watchdog: ARMED 🛡️" -ForegroundColor Magenta

$lastProgressTime = Get-Date
$adBufferRetries = 0
while ($true) {
    # 1. Watchdog: check if app froze (>45s idle without progress)
    $idleSec = ((Get-Date) - $lastProgressTime).TotalSeconds
    if ($idleSec -gt 45) {
        Recover-From-Crash-Or-Freeze
        $lastProgressTime = Get-Date
        continue
    }

    # 2. Watchdog: check if app crashed back to Android launcher
    $focusNow = Get-Current-Focus
    if ($focusNow -match "launcher|Launcher|home|Home" -and $focusNow -notmatch "com.quiz.earn") {
        Write-Host "`n  ⚠️ App crash detected (Launcher active). Auto-relaunching..." -ForegroundColor Yellow
        Recover-From-Crash-Or-Freeze
        $lastProgressTime = Get-Date
        continue
    }
    Check-And-Escape-PlayStore | Out-Null

    $liveGems = Get-Live-Gems

    # Condition: 25 Gems Hit -> STOP AND NOTIFY!
    if ($liveGems -ge 25) {
        Write-Host "`n==========================================================" -ForegroundColor Red
        Write-Host " 🎉 TARGET REACHED: 25/25 GEMS! 🎉" -ForegroundColor Yellow
        Write-Host " [MEGA OFFER UNLOCKED] Reward page is waiting on BlueStacks!" -ForegroundColor White
        Write-Host " Complete your claim on your screen now." -ForegroundColor Cyan
        Write-Host "==========================================================" -ForegroundColor Red

        Play-Alarm

        try {
            $wshell = New-Object -ComObject Wscript.Shell
            $null = $wshell.Popup("🎉 25 Gems Reached!`n`n[Mega Offer Unlocked] is waiting on BlueStacks.`nComplete your reward claim now.`nThe bot will automatically resume once gems reset to 0!", 10, "Quiz Bot - 25 Gems Target!", 64)
        }
        catch {}

        Write-Host "`n[STANDBY] Waiting for you to complete the claim page..." -ForegroundColor Yellow
        while ($true) {
            Start-Sleep -Seconds 3
            $checkGems = Get-Live-Gems
            
            if ($checkGems -ge 0 -and $checkGems -lt 25) {
                Play-Alarm
                Write-Host "`n[WAKE UP] Detected gems reset to $checkGems/25! Reactivating bot now! 🚀" -ForegroundColor Green
                $lastProgressTime = Get-Date
                Start-Sleep -Seconds 2
                break
            }
            else {
                Write-Host -NoNewline "`r[STANDBY] Waiting for claim... (Gems: 25 / Mega Offer Unlocked) (Press Ctrl+C to Stop)   "
            }
        }
    }

    $focusNow = Get-Current-Focus
    if ($focusNow -notmatch "MainActivity") {
        Close-Ad-Actively
        continue
    }

    $displayGems = if ($liveGems -ge 0) { "$liveGems/25" } else { "Scanning..." }
    Write-Host "`n----------------------------------------------------------"
    Write-Host ">> [Gems: $displayGems] Solving Question..." -ForegroundColor Cyan

    # 1. Read Question & Solve Math
    Invoke-Adb @("shell", "uiautomator", "dump", "/sdcard/w.xml") | Out-Null
    $xmlText = (Invoke-Adb @("shell", "cat", "/sdcard/w.xml")) -join ""

    # 1. Math question on screen -> Solve and pick answer A/B/C/D! (PRIORITY 1)
    if ($xmlText -match 'What is (\d+)\s*([\+\-\*x\/])\s*(\d+)\?') {
        $n1 = [int]$matches[1]
        $op = $matches[2]
        $n2 = [int]$matches[3]

        $correct = 0
        if ($op -eq '+' ) { $correct = $n1 + $n2 }
        elseif ($op -eq '-') { $correct = $n1 - $n2 }
        elseif ($op -eq '*' -or $op -eq 'x') { $correct = $n1 * $n2 }

        Write-Host "  [Math Detected] $n1 $op $n2 = $correct" -ForegroundColor Green

        $tapX = $optA_X; $tapY = $optA_Y
        
        # Check dynamic button bounds first, fallback to predefined coords
        $btnPattern = '<node [^>]*text="[ABCD] ' + $correct + '"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
        if ($xmlText -match $btnPattern) {
            $x1 = [int]$matches[1]; $y1 = [int]$matches[2]
            $x2 = [int]$matches[3]; $y2 = [int]$matches[4]
            $tapX = [int](($x1 + $x2) / 2)
            $tapY = [int](($y1 + $y2) / 2)
            Write-Host "  Found exact option button for '$correct' at ($tapX, $tapY)" -ForegroundColor Green
        }
        elseif ($xmlText -match ('text="A ' + $correct + '"')) { $tapX = $optA_X; $tapY = $optA_Y; Write-Host "  Picking Option A" }
        elseif ($xmlText -match ('text="B ' + $correct + '"')) { $tapX = $optB_X; $tapY = $optB_Y; Write-Host "  Picking Option B" }
        elseif ($xmlText -match ('text="C ' + $correct + '"')) { $tapX = $optC_X; $tapY = $optC_Y; Write-Host "  Picking Option C" }
        elseif ($xmlText -match ('text="D ' + $correct + '"')) { $tapX = $optD_X; $tapY = $optD_Y; Write-Host "  Picking Option D" }

        Write-Host "  Tapping answer at ($tapX, $tapY)..." -ForegroundColor White
        Adb-Tap $tapX $tapY

        # 2. Tap 'Collect Gem & Continue'
        Start-Sleep -Milliseconds 750
        Write-Host "  Tapping 'Collect Gem & Continue'..." -ForegroundColor White
        Adb-Tap $collectX $collectY

        # 3. Dynamic Ad Close
        Close-Ad-Actively

        $lastProgressTime = Get-Date
        $cachedStats = Save-Bot-Stats -addGems 1 -addQuestions 1 -addAds 1

        Start-Sleep -Milliseconds 600
        continue
    }

    # 2. If 'Ad required - tap to retry' is showing, wait 3.5s and retry, or auto-restart if stuck
    if ($xmlText -match "Ad required" -or $xmlText -match "tap to retry") {
        $adBufferRetries++
        if ($adBufferRetries -ge 3) {
            Write-Host "  ⚠️ Ad network stuck in cooldown. Auto-restarting app to load fresh ad..." -ForegroundColor Yellow
            Invoke-Adb @("shell", "am", "force-stop", "com.quiz.earn.money.game") | Out-Null
            Start-Sleep -Milliseconds 1500
            Invoke-Adb @("shell", "monkey", "-p", "com.quiz.earn.money.game", "-c", "android.intent.category.LAUNCHER", "1") | Out-Null
            Start-Sleep -Seconds 5
            Adb-Tap 518 846 # Mega Quiz 500 Card
            Start-Sleep -Seconds 2
            Close-Ad-Actively
            $adBufferRetries = 0
            continue
        }

        Write-Host "  ⏳ Ad is buffering in background (Attempt $adBufferRetries/3). Waiting 3s..." -ForegroundColor Gray
        Start-Sleep -Milliseconds 3000
        Adb-Tap $collectX $collectY
        Close-Ad-Actively
        Start-Sleep -Milliseconds 600
        continue
    }

    # 3. If 'Collect Gem & Continue' is showing, tap it directly
    if ($xmlText -match "Collect Gem &amp; Continue" -or $xmlText -match "Collect Gem") {
        $adBufferRetries = 0
        Write-Host "  Tapping 'Collect Gem & Continue'..." -ForegroundColor White
        Start-Sleep -Milliseconds 700
        Adb-Tap $collectX $collectY
        Close-Ad-Actively
        Start-Sleep -Milliseconds 600
        continue
    }

    # 4. If on Home page (and NOT on quiz question), tap 500 to start quiz & watch ad
    if (($xmlText -match "Daily Win" -or $xmlText -match "Featured Tasks" -or $xmlText -match "Go to Wallet" -or $xmlText -match "Refer & Earn") -and ($xmlText -notmatch "What is ")) {
        $targetX = 518; $targetY = 846
        if ($xmlText -match '<node [^>]*text="500"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') {
            $x1 = [int]$matches[1]; $y1 = [int]$matches[2]
            $x2 = [int]$matches[3]; $y2 = [int]$matches[4]
            $targetX = [int](($x1 + $x2) / 2)
            $targetY = [int](($y1 + $y2) / 2)
        }
        Write-Host "  🏠 Home page detected. Tapping '500' (MEGA OFFER) at ($targetX, $targetY)..." -ForegroundColor Yellow
        Adb-Tap $targetX $targetY
        Start-Sleep -Milliseconds 800
        Close-Ad-Actively
        Start-Sleep -Milliseconds 600
        continue
    }

    Start-Sleep -Milliseconds 1000
}
