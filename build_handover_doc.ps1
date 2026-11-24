# PowerShell script to generate the exhaustive QUIZEARN_BOT_COMPLETE_HANDOVER_AND_CODEBASE.txt
$ErrorActionPreference = "Stop"

$outPath1 = "C:\Users\THESHMIL\Desktop\Quiz_Earn_Bot\QUIZEARN_BOT_COMPLETE_HANDOVER_AND_CODEBASE.txt"
$outPath2 = "c:\Users\THESHMIL\Downloads\yt channale\cashmafia\QUIZEARN_BOT_COMPLETE_HANDOVER_AND_CODEBASE.txt"

$sb = [System.Text.StringBuilder]::new()

function Add-Header($title) {
    [void]$sb.AppendLine("`r`n" + ("=" * 80))
    [void]$sb.AppendLine("  $title")
    [void]$sb.AppendLine("=" * 80 + "`r`n")
}

function Add-SubHeader($title) {
    [void]$sb.AppendLine("`r`n" + ("-" * 80))
    [void]$sb.AppendLine("  $title")
    [void]$sb.AppendLine("-" * 80 + "`r`n")
}

function Add-Text($text) {
    [void]$sb.AppendLine($text)
}

function Add-CodeFile($filePath, $fileLabel) {
    Add-SubHeader "SOURCE CODE: $fileLabel`r`nLocation: $filePath"
    if (Test-Path $filePath) {
        $content = [System.IO.File]::ReadAllText($filePath, [System.Text.Encoding]::UTF8)
        [void]$sb.AppendLine($content)
    } else {
        [void]$sb.AppendLine("[ERROR: File not found at $filePath]")
    }
}

Write-Host ">>> Building comprehensive master handover document..."

Add-Header "QUIZEARN & CASHMAFIA MULTI-INSTANCE BOT - MASTER HANDOVER & CODEBASE"
Add-Text @'
Operating System: Windows 10/11 x64
Target Emulators: BlueStacks 5 (Multi-Instance Manager)
Android Version: Android 11 / 12 / 13 (Nougat/Pie/Tiramisu 64-bit)
Resolution: 900 x 1600 (Portrait, DPI 240/320)
Target Apps:
  1. Quiz & Earn: com.quiz.earn.money.game
  2. CashMafia:   com.rayole.cashmafia2
Assistant Bot Package: com.quizearn.assistant
Root Project Directory: C:\Users\THESHMIL\Desktop\Quiz_Earn_Bot
Secondary Workspace:    c:\Users\THESHMIL\Downloads\yt channale\cashmafia

PURPOSE OF THIS FILE:
This document contains the COMPLETE technical overview, architecture, bug logs,
logic flows, build scripts, deployment scripts, and FULL source code for the
QuizEarn Bot system. Any AI model reading this file will have 100% of the context
required to understand, run, debug, expand, or rewrite any component of this bot.
'@

Add-Header "TABLE OF CONTENTS"
Add-Text @'
1. EXECUTIVE SUMMARY & EARNINGS LIFECYCLE
2. ARCHITECTURAL EVOLUTION (C# Desktop Automation vs. Native Android Accessibility Service)
3. BLUESTACKS MULTI-INSTANCE TOPOLOGY & PORT CONFIGURATION
4. COMPLETE FINITE STATE MACHINE (FSM) & STEP-BY-STEP WORKFLOW
   4.1. Phase 1: Math Captcha Solving & Gem Farming (All Instances)
   4.2. Phase 2: Super Offer Trigger at 25 Gems (Player 1 Dedicated)
   4.3. Phase 3: Rewarded Video Ad Handling & Play Store Transition
   4.4. Phase 4: Google Play Store Installation Automation
   4.5. Phase 5: Dual App Launch & 2-Minute Engagement Simulation
   4.6. Phase 6: Quiz App Return, Installation Verification & Coin Claiming
   4.7. Phase 7: Post-Offer Teardown & Silent App Uninstallation
5. CRITICAL BUG FIXES & HARD-WON RULES (THE "DO NOT BREAK" LIST)
   5.1. The Warning Text Misclick Loop Trap & Clickable Filter Fix
   5.2. Rewarded Video Ad Premature Close Fix (26s - 35s Minimums)
   5.3. The "Close Ad? You will lose your reward" Confirmation Dialog Trap
   5.4. Bottom Navigation Bar Protection (Tabs: Community, Tasks, Wallet, Profile)
   5.5. Dual Launch Mechanism (Android Intent + ADB Monkey Fallback)
   5.6. Broken App / Crash Recovery Flow ("Stuck? Restart Offer")
   5.7. Verification Failure & Ad Network Preload Cycle
6. COMPILATION & BUILD PIPELINE (NO ANDROID STUDIO / NO GRADLE REQUIRED)
7. DEPLOYMENT & ADB ORCHESTRATION SCRIPTS
8. FULL SOURCE CODE: CONFIGURATION & RES FILES
   8.1. AndroidManifest.xml
   8.2. accessibility_service_config.xml
   8.3. strings.xml
   8.4. colors.xml
   8.5. themes.xml
9. FULL SOURCE CODE: BUILD & ORCHESTRATION POWERSHELL SCRIPTS
   9.1. build_apk.ps1
   9.2. deploy_all.ps1
   9.3. start_services.ps1
   9.4. check_status.ps1
10. FULL SOURCE CODE: ANDROID JAVA BOT ENGINE
   10.1. PortraitFixActivity.java
   10.2. MainActivity.java
   10.3. FloatingOverlayService.java
   10.4. QuizEarnAccessibilityService.java
11. FUTURE AI QUICK-START GUIDE & HOW TO ADD MORE BLUESTACKS INSTANCES
'@

Add-Header "1. EXECUTIVE SUMMARY & EARNINGS LIFECYCLE"
Add-Text @'
The QuizEarn Bot is an automated background farming system designed to maximize daily
revenue from mobile reward applications, primarily "Quiz & Earn" (com.quiz.earn.money.game).

The application rewards users in two primary ways:
1. Small Gem/Coin Rewards: Solving simple arithmetic equations (addition, subtraction,
   multiplication) and viewing interstitial ads.
2. Major Super Offer Rewards: Every 25 gems collected unlocks a "Super Offer" milestone
   that pays a guaranteed 1,000 Coins. Completing a Super Offer requires:
   a. Watching a sponsored video ad.
   b. Navigating to the Google Play Store and installing the advertised third-party app.
   c. Launching and actively using the downloaded app for 2 full minutes.
   d. Returning to Quiz & Earn and clicking "Verify Installation".
   e. Claiming the 1,000 Coin reward.
   f. Uninstalling the app to prevent storage overflow and duplicate offer bans.

The system runs across multiple BlueStacks emulator instances simultaneously:
- Player 1 (Port 5555): The "Master Offer Runner". It farms gems, triggers Super Offers,
  downloads the apps, simulates 2 minutes of active play, verifies, and claims 1,000 coins.
- Players 3, 4, 5+ (Ports 5585, 5596, 5605+): Pure "Gem Farmers". They continuously solve
  math captchas and collect gems, but NEVER download apps (Super Offer disabled). This
  prevents disk bloat and eliminates high network load across multi-instances.
'@

Add-Header "2. ARCHITECTURAL EVOLUTION (C# VS. NATIVE ACCESSIBILITY SERVICE)"
Add-Text @'
Originally, the project began as a Windows C# desktop application (QuizEarnApp.cs / CashMafiaApp.cs).
In that architecture:
- Windows PC sent ADB commands: `adb exec-out screencap` and `adb shell uiautomator dump`.
- PC parsed the screen pixels or XML node hierarchy on the host machine.
- PC calculated tap coordinates and executed `adb shell input tap X Y`.

WHY WE MIGRATED TO NATIVE ANDROID ACCESSIBILITY SERVICE (QuizEarnBot.apk):
1. Latency: ADB screen captures took 1.5 - 2.5 seconds per frame. With multi-instances,
   ADB daemon port saturation caused massive delays and timeouts.
2. Stability: Native Android Accessibility Service runs INSIDE the emulator's ART runtime.
   It receives window content events (AccessibilityEvent) at 50ms intervals.
3. Zero ADB Overhead: Tap gestures are executed directly using Android's native
   `dispatchGesture(GestureDescription, ...)` with microsecond precision.
4. Resilience: The APK has an always-on foreground service (FloatingOverlayService) with
   a stealth OLED floating HUD bubble that displays live status, gem counts, and current action.
'@

Add-Header "3. BLUESTACKS MULTI-INSTANCE TOPOLOGY & PORT CONFIGURATION"
Add-Text @'
BlueStacks assigns ADB ports sequentially starting from 5555:
- Instance 1 (Player 1) -> 127.0.0.1:5555
  Config: AUTO_OFFER_ON (isSuperOfferEnabled = true)
  Role: Farms gems + Downloads apps + 2-min usage + Claims 1,000 Coins

- Instance 2 (Player 2) -> (Reserved / Skipped if inactive)

- Instance 3 (Player 3) -> 127.0.0.1:5585
  Config: AUTO_OFFER_OFF (isSuperOfferEnabled = false)
  Role: Pure Gem Farmer (Captchas only, NO downloads)

- Instance 4 (Player 4) -> 127.0.0.1:5596
  Config: AUTO_OFFER_OFF (isSuperOfferEnabled = false)
  Role: Pure Gem Farmer (Captchas only, NO downloads)

- Instance 5 (Player 5) -> 127.0.0.1:5605
  Config: AUTO_OFFER_OFF (isSuperOfferEnabled = false)
  Role: Pure Gem Farmer (Captchas only, NO downloads)

Display Profile:
- All instances must be configured to Portrait: 900 x 1600 resolution.
- DPI: 240 or 320.
'@

Add-Header "4. COMPLETE FINITE STATE MACHINE (FSM) & STEP-BY-STEP WORKFLOW"
Add-Text @'
The accessibility service executes in `solveScreen(AccessibilityNodeInfo root)`.
Here is the step-by-step lifecycle:

4.1. Phase 1: Math Captcha Solving & Gem Farming (All Instances)
- Detects Quiz & Earn screen.
- Scans node tree for arithmetic questions (e.g., "14 + 23 = ?" or "89 - 41 = ?").
- Computes correct answer in memory.
- Searches child nodes for the button whose text equals the answer string.
- Dispatches instantaneous gesture tap to that button.
- Detects the interstitial ad that follows:
  * Reads countdown timer if present (e.g. "Reward in 15 seconds" or "Ad will close in 5s").
  * Once the countdown finishes, detects and taps the "X" / Close button or forward chevron.

4.2. Phase 2: Super Offer Trigger at 25 Gems (Player 1 Dedicated)
- When the gem counter reaches 25 (e.g. "25/25" or "25 Gems Collected!"), the home screen
  card displays "Super Offer - Guaranteed 1,000 Coins!".
- Bot checks `isSuperOfferEnabled`. If true (Player 1):
  * Taps "Start Offer" card on Home (approx Y=849).
  * Transitions to Offer Details page.
  * Taps "Start Offer" button on Details page (approx Y=1192).

4.3. Phase 3: Rewarded Video Ad Handling & Play Store Transition
- Sets `offerState = OFFER_STATE_AD_PLAYING`.
- Enforces minimum ad watch time of 26s to 35s to prevent ad network disqualification.
- At ~30-35s, taps the "Skip to Play Store" pill coordinate at (760, 88) or taps the ad's
  install banner.
- Ad SDK fires an intent opening Google Play Store (com.android.vending).

4.4. Phase 4: Google Play Store Installation Automation
- Bot detects `com.android.vending` foreground window.
- Captures a baseline list of installed packages (`snapshotInstalledPackages()`).
- Locates the green "Install" button (bounds approx [203, 453] or text="Install").
- Dispatches gesture tap on "Install".
- Enters `OFFER_STATE_INSTALLING` and monitors package installation.
- Automatically handles any "Download pending" or "Install over any network" dialogs.

4.5. Phase 5: Dual App Launch & 2-Minute Engagement Simulation
- Once Play Store changes "Install" to "Open", or package manager reports a newly installed
  package, the bot brings Quiz & Earn back to front.
- Quiz & Earn displays the "App Usage Progress" screen with a 2-minute progress bar.
- The bot launches the newly installed app using a DUAL-LAUNCH STRATEGY:
  1. `context.getPackageManager().getLaunchIntentForPackage(pkg)`
  2. `Runtime.getRuntime().exec("monkey -p " + pkg + " -c android.intent.category.LAUNCHER 1")`
- Bot enters `OFFER_STATE_APP_ACTIVE` for 125 seconds.
- Every 4-6 seconds, the bot executes micro-swipes and gentle taps in the center of the screen
  to prevent Android from sleeping and to simulate genuine user engagement.
- If the app crashes repeatedly (2+ times), bot triggers the "Stuck? Restart Offer" recovery.

4.6. Phase 6: Quiz App Return, Installation Verification & Coin Claiming
- After 125 seconds of active usage, bot calls `bringQuizAppToFront()`.
- Quiz app transitions to "Verify Installation" screen.
- Bot locates the "Verify Installation" button (approx Y=1192) and taps it.
- State transitions to `OFFER_STATE_VERIFYING`.
- Bot scans for success tokens: "Verification Successful", "+100 Coins", or "View My Offers".
- Bot taps "Claim" or "View My Offers" to credit the 1,000 coins to the account balance.

4.7. Phase 7: Post-Offer Teardown & Silent App Uninstallation
- Once coins are claimed (or if verification fatally failed), the bot uninstalls the downloaded app:
  `Runtime.getRuntime().exec("pm uninstall " + offerTargetPackage)`.
- Resets all offer state variables to `OFFER_STATE_IDLE`.
- Flushes ad cooldown timer.
- Resumes math captcha gem farming for the next 25 gems.
'@

Add-Header "5. CRITICAL BUG FIXES & HARD-WON RULES (THE 'DO NOT BREAK' LIST)"
Add-Text @'
These are subtle bugs discovered through live testing. Any future AI MUST preserve these fixes:

5.1. The Warning Text Misclick Loop Trap & Clickable Filter Fix (CRITICAL)
- Symptom: The bot was stuck in an infinite loop on the Offer Details page, spam-tapping
  coordinate (489, 597) every 2 seconds, logging:
  "⚡ Tapping Restart Offer / Try Different App at Rect(186, 573 - 792, 622)".
- Root Cause: The Offer Details page contains an informational warning text:
  "⚠️ If you already have a pending offer for the app shown in the ad, skip and try a different app."
  The bot's detector had: `t.contains("try a different app")`. It matched this warning text!
  Because the text node was NOT a button, tapping it did nothing, creating an infinite loop.
- The Fix:
  In `QuizEarnAccessibilityService.java`:
  Only match `restartOfferBtn` if:
  1. `item.node.isClickable()` is TRUE.
  2. Text does NOT contain "pending offer", "skip and try", "already have a pending", "shown in the ad".

5.2. Rewarded Video Ad Premature Close Fix (26s - 35s Minimums)
- Symptom: Video ads were closing after 6-7 seconds. The ad network failed to grant the
  reward, resulting in "You will lose your reward" or no Play Store transition.
- Root Cause: Early webview close button heuristics were firing at `adElapsed >= 7s`.
- The Fix:
  Raised `minAdElapsed` from 6s to 26s for non-countdown ads.
  Countdowns are given up to 35-38s before any skip or close attempt.

5.3. The 'Close Ad? You will lose your reward' Confirmation Dialog Trap
- Symptom: If an ad close was attempted, a dialog appeared: "Close Ad? You will lose your reward"
  with two buttons: [CLOSE] and [RESUME]. The bot clicked [CLOSE] or missed [RESUME].
- The Fix:
  Target the blue [RESUME] button explicitly:
  1. Match text: `t.equals("resume")`.
  2. Coordinate fallback: `(0.68 * screenW, 0.60 * screenH)` which lands directly on the
     blue RESUME button.

5.4. Bottom Navigation Bar Protection (Tabs: Community, Tasks, Wallet, Profile)
- Symptom: Bot was clicking "Community" or "Tasks" at the bottom of the screen instead of
  offer buttons.
- Root Cause: Bottom navigation bar buttons are located at bounds Y=1479 to Y=1600.
- The Fix:
  1. `isForbiddenElement()` blacklists "community", "tasks", "wallet", "profile", "home".
  2. All offer action buttons enforce: `item.rect.centerY() < 1400`.

5.5. Dual Launch Mechanism (Android Intent + ADB Monkey Fallback)
- Symptom: In BlueStacks, calling `context.startActivity(launchIntent)` for newly downloaded
  apps often failed silently or was blocked by background activity start restrictions.
- The Fix:
  Execute BOTH:
  ```java
  context.startActivity(launchIntent);
  Runtime.getRuntime().exec("monkey -p " + pkg + " -c android.intent.category.LAUNCHER 1");
  ```
  This guarantees immediate app launch in BlueStacks emulators.

5.6. Broken App / Crash Recovery Flow ('Stuck? Restart Offer')
- Symptom: Some third-party apps downloaded from Play Store crash instantly on Android 11/12
  emulators ("App keeps stopping" dialog), leaving the bot stranded on the 2-minute bar.
- The Fix:
  If `downloadedAppCrashCount >= 2` or `offerAppLaunchAttempts >= 5`:
  Bot scrolls up to reveal "Stuck? Restart Offer" button (safely above bottom nav bar),
  taps it, confirms the restart dialog, uninstalls the broken package, and resets.

5.7. Verification Failure & Ad Network Preload Cycle
- Symptom: If Quiz & Earn fails to detect the installed app, repeatedly clicking Verify
  does nothing.
- The Fix:
  Bot allows up to 3 verify retries if package is in OS. If still failing:
  1. Taps "Go Back" button (or coordinate 450, 1310).
  2. Uninstalls the problematic package.
  3. Restarts Quiz & Earn cleanly with `FLAG_ACTIVITY_CLEAR_TASK`.
  4. Imposes an 8-second ad-preload cooldown so the app fetches fresh ad networks.
'@

Add-Header "6. COMPILATION & BUILD PIPELINE (NO ANDROID STUDIO / NO GRADLE REQUIRED)"
Add-Text @'
The entire build system is completely standalone. It uses Microsoft OpenJDK 17 and Android
SDK Build-Tools directly via PowerShell (`android\build_apk.ps1`).

Requirements on Host Machine:
- JDK 17: C:\Program Files\Microsoft\jdk-17.0.21.21-hotspot\bin
- Android SDK: C:\Users\THESHMIL\Android\Sdk
- Build Tools: build-tools\34.0.0 (aapt2.exe, d8.bat, zipalign.exe, apksigner.bat)
- Target Platform: platforms\android-34\android.jar
- Keystore: C:\Users\THESHMIL\Desktop\Quiz_Earn_Bot\android\release.jks
  (Alias: quizassistant, Password: pass:QuizEarn2026Safe)

7-Step Build Steps in `build_apk.ps1`:
1. Clean build directory (android\build).
2. aapt2 compile: Compiles res/ directory into resources.zip.
3. aapt2 link: Links AndroidManifest.xml + resources.zip against android.jar,
   generates R.java and unaligned.apk.
4. javac: Compiles all Java source files with -source 17 -target 17 into .class files.
5. d8: Converts all bytecode .class files into Dalvik Executable (classes.dex).
6. jar -uf + zipalign 4: Injects classes.dex into unaligned.apk and 4-byte page-aligns it.
7. apksigner: Signs APK with v1, v2, and v3 signature schemes using release.jks.

To build the APK at any time, run in PowerShell:
powershell -ExecutionPolicy Bypass -File "C:\Users\THESHMIL\Desktop\Quiz_Earn_Bot\android\build_apk.ps1"
'@

Add-Header "7. DEPLOYMENT & ADB ORCHESTRATION SCRIPTS"
Add-Text @'
Deploying and starting the bot across all connected BlueStacks instances is automated:

To Deploy to All Devices:
powershell -ExecutionPolicy Bypass -File "C:\Users\THESHMIL\Desktop\Quiz_Earn_Bot\deploy_all.ps1"
This script:
1. Detects all connected 127.0.0.1:XXXX devices via `adb devices`.
2. Installs QuizEarnBot.apk with -r -d flags.
3. Automatically grants SYSTEM_ALERT_WINDOW permission (`appops set`).
4. Enables Accessibility Service in Android secure settings:
   settings put secure enabled_accessibility_services com.quizearn.assistant/com.quizearn.assistant.QuizEarnAccessibilityService
   settings put secure accessibility_enabled 1
5. Starts FloatingOverlayService.
6. Sends com.quizearn.assistant.START intent.
7. Configures Player 1 (127.0.0.1:5555) with AUTO_OFFER_ON.
8. Configures Players 3, 4, 5 with AUTO_OFFER_OFF.

To Check Live Status of All Instances:
powershell -ExecutionPolicy Bypass -File "C:\Users\THESHMIL\Desktop\Quiz_Earn_Bot\check_status.ps1"
'@

Add-Header "8. FULL SOURCE CODE: CONFIGURATION & RESOURCE FILES"

Add-CodeFile "C:\Users\THESHMIL\Desktop\Quiz_Earn_Bot\android\src\main\AndroidManifest.xml" "AndroidManifest.xml"
Add-CodeFile "C:\Users\THESHMIL\Desktop\Quiz_Earn_Bot\android\src\main\res\xml\accessibility_service_config.xml" "accessibility_service_config.xml"
Add-CodeFile "C:\Users\THESHMIL\Desktop\Quiz_Earn_Bot\android\src\main\res\values\strings.xml" "strings.xml"
Add-CodeFile "C:\Users\THESHMIL\Desktop\Quiz_Earn_Bot\android\src\main\res\values\colors.xml" "colors.xml"
Add-CodeFile "C:\Users\THESHMIL\Desktop\Quiz_Earn_Bot\android\src\main\res\values\themes.xml" "themes.xml"

Add-Header "9. FULL SOURCE CODE: BUILD & ORCHESTRATION POWERSHELL SCRIPTS"

Add-CodeFile "C:\Users\THESHMIL\Desktop\Quiz_Earn_Bot\android\build_apk.ps1" "build_apk.ps1"
Add-CodeFile "C:\Users\THESHMIL\Desktop\Quiz_Earn_Bot\deploy_all.ps1" "deploy_all.ps1"
Add-CodeFile "C:\Users\THESHMIL\Desktop\Quiz_Earn_Bot\start_services.ps1" "start_services.ps1"
Add-CodeFile "C:\Users\THESHMIL\Desktop\Quiz_Earn_Bot\check_status.ps1" "check_status.ps1"

Add-Header "10. FULL SOURCE CODE: ANDROID JAVA BOT ENGINE"

Add-CodeFile "C:\Users\THESHMIL\Desktop\Quiz_Earn_Bot\android\src\main\java\com\quizearn\assistant\PortraitFixActivity.java" "PortraitFixActivity.java"
Add-CodeFile "C:\Users\THESHMIL\Desktop\Quiz_Earn_Bot\android\src\main\java\com\quizearn\assistant\MainActivity.java" "MainActivity.java"
Add-CodeFile "C:\Users\THESHMIL\Desktop\Quiz_Earn_Bot\android\src\main\java\com\quizearn\assistant\FloatingOverlayService.java" "FloatingOverlayService.java"
Add-CodeFile "C:\Users\THESHMIL\Desktop\Quiz_Earn_Bot\android\src\main\java\com\quizearn\assistant\QuizEarnAccessibilityService.java" "QuizEarnAccessibilityService.java"

Add-Header "11. FUTURE AI QUICK-START GUIDE & HOW TO ADD MORE BLUESTACKS INSTANCES"
Add-Text @'
HOW TO ADD MORE BLUESTACKS INSTANCES:
1. Open BlueStacks Multi-Instance Manager on Windows.
2. Clone an existing instance or create a new Pie 64-bit / Android 11 instance.
3. Configure Display: Resolution = 900 x 1600 (Portrait), DPI = 240 or 320.
4. Launch the new instance. Note its ADB port by running:
   adb devices
   (e.g., 127.0.0.1:5615).
5. Open deploy_all.ps1, start_services.ps1, and check_status.ps1.
   Add the new port to the $devices fallback array:
   $devices = @("127.0.0.1:5555", "127.0.0.1:5585", "127.0.0.1:5596", "127.0.0.1:5605", "127.0.0.1:5615")
6. Run deploy_all.ps1. The script will install the APK, enable accessibility permissions,
   set AUTO_OFFER_OFF (since only Player 1 downloads apps), and start solving math captchas!

COMMON ADB COMMANDS FOR LIVE DEBUGGING:
- Live Logcat stream:
  adb -s 127.0.0.1:5555 logcat -s QuizEarnAssistant:V
- Take instant screenshot:
  adb -s 127.0.0.1:5555 exec-out screencap -p > screen.png
- Dump UI hierarchy to XML:
  adb -s 127.0.0.1:5555 shell uiautomator dump /sdcard/ui.xml && adb -s 127.0.0.1:5555 pull /sdcard/ui.xml
- Send custom broadcast to toggle Super Offers:
  adb -s 127.0.0.1:5555 shell am start-foreground-service -a com.quizearn.assistant.AUTO_OFFER_ON -n com.quizearn.assistant/.FloatingOverlayService
  adb -s 127.0.0.1:5555 shell am start-foreground-service -a com.quizearn.assistant.AUTO_OFFER_OFF -n com.quizearn.assistant/.FloatingOverlayService
- Force restart Quiz & Earn:
  adb -s 127.0.0.1:5555 shell am force-stop com.quiz.earn.money.game && adb -s 127.0.0.1:5555 shell monkey -p com.quiz.earn.money.game -c android.intent.category.LAUNCHER 1

SUMMARY:
With this file, you have the complete knowledge base and entire code of the system.
You can modify any feature in QuizEarnAccessibilityService.java, run build_apk.ps1,
run deploy_all.ps1, and the updated bot will be running across all emulators in seconds!
'@

Add-Header "END OF MASTER HANDOVER DOCUMENT"

Write-Host ">>> Writing output file 1: $outPath1"
[System.IO.File]::WriteAllText($outPath1, $sb.ToString(), [System.Text.Encoding]::UTF8)

Write-Host ">>> Writing output file 2: $outPath2"
[System.IO.File]::WriteAllText($outPath2, $sb.ToString(), [System.Text.Encoding]::UTF8)

$f1 = Get-Item $outPath1
$f2 = Get-Item $outPath2

Write-Host ">>> SUCCESS!"
Write-Host "File 1: $($f1.FullName) (Bytes: $($f1.Length))"
Write-Host "File 2: $($f2.FullName) (Bytes: $($f2.Length))"
