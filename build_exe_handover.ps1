# PowerShell script to build the exhaustive QUIZEARN_EXE_BOT_FULL_HANDOVER_AND_CODE.txt
$ErrorActionPreference = "Stop"

$outPath1 = "C:\Users\THESHMIL\Desktop\Quiz_Earn_Bot\QUIZEARN_EXE_BOT_FULL_HANDOVER_AND_CODE.txt"
$outPath2 = "c:\Users\THESHMIL\Downloads\yt channale\cashmafia\QUIZEARN_EXE_BOT_FULL_HANDOVER_AND_CODE.txt"

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
    Add-SubHeader "FULL SOURCE CODE: $fileLabel`r`nLocation: $filePath"
    if (Test-Path $filePath) {
        $content = [System.IO.File]::ReadAllText($filePath, [System.Text.Encoding]::UTF8)
        [void]$sb.AppendLine($content)
    } else {
        [void]$sb.AppendLine("[ERROR: File not found at $filePath]")
    }
}

Write-Host ">>> Assembling comprehensive EXE Bot Master Handover..."

Add-Header "QUIZEARN WINDOWS EXE BOT (QuizEarnBot.exe) - MASTER HANDOVER & COMPLETE SOURCE"
Add-Text @'
Operating System: Windows 10 / 11 x64
Target Runtime: .NET Framework 4.0 / 4.8 (Windows Forms / C# 5)
Compiler: C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe (No Visual Studio required)
Application Name: QuizEarnBot.exe (Quiz & Earn Auto-Bot)
Source File: QuizEarnApp.cs (~6,180 lines)
GitHub Repository: https://github.com/THESHMIL0/QuizEarnBot
Update Feed: https://raw.githubusercontent.com/THESHMIL0/QuizEarnBot/main/version.json
Current Version: v2.8 (Version Code: 280)
Primary Project Directory: C:\Users\THESHMIL\Desktop\Quiz_Earn_Bot
Secondary Workspace:       c:\Users\THESHMIL\Downloads\yt channale\cashmafia

PURPOSE OF THIS FILE:
This document contains the COMPLETE technical blueprint, historical context,
chat work history, architecture, feature mechanics, update protocols, and the
FULL, UNABRIDGED source code of the Windows EXE Bot (QuizEarnApp.cs) and all its
supporting tools. Any AI (such as Gemini 1.5 Pro / Argon) reading this file will
have 100% of the information needed to understand, maintain, debug, refactor,
or expand QuizEarnBot.exe without missing a single detail.
'@

Add-Header "TABLE OF CONTENTS"
Add-Text @'
1. EXECUTIVE SUMMARY & WHAT THE EXE BOT DOES
2. DUAL OPERATIONAL MODES: PC-AUTOMATION VS. WATCHDOG COMPANION
3. FULL WORK HISTORY, USER SESSIONS & EVOLUTION CHRONOLOGY
   3.1. Origins: Standalone ADB Screen Automation
   3.2. Evolution: Why We Added the In-App Android Accessibility Service APK
   3.3. Transformation of the EXE into an Autonomous Multi-Instance Command Center
   3.4. The Cloud Auto-Updater Protocol & 1-Click Friend Updates
   3.5. Dual App Support: Quiz & Earn (com.quiz.earn.money.game) + CashMafia (com.rayole.cashmafia2)
4. USER INTERFACE & VISUAL DESIGN ARCHITECTURE (Pure OLED Black)
   4.1. Color Palette & Typography
   4.2. Tab 1: HOME View (Control Center & Live Automation)
   4.3. Tab 2: DATA View (Analytics, Stats, Historical Log)
   4.4. Tab 3: SETTINGS View (Ports, Watchdogs, Updates, Changelog)
5. CORE ENGINES & SUBSYSTEMS INSIDE QuizEarnApp.cs
   5.1. Win32 Power & Display Keep-Alive Engine (SetThreadExecutionState)
   5.2. ADB Communication & Process Runner Engine
   5.3. Math Quiz Solver & Equation Evaluator Engine
   5.4. Screen Capture & UI XML Hierarchy Parser Engine
   5.5. 25-Gem Milestone & Super Offer Claimer Engine
   5.6. 10-Offer Bonus & Daily Task Automation Engine
   5.7. Cloud Auto-Updater & Binary Self-Replacement Engine
   5.8. Multi-Instance BlueStacks Watchdog & Auto-Relaunch Engine
   5.9. Persistent JSON Stats Tracker (quizearn_stats.json)
6. COMPILATION, SIGNING & PACKAGING TOOLCHAIN
   6.1. Standalone Compilation via csc.exe
   6.2. DPI-Aware Manifest (app.manifest) & Icon Embedding (app.ico)
   6.3. Portable ZIP Packaging Pipeline (QuizEarnBot_v2.8_Portable.zip)
   6.4. Automated Cloud Release Publisher (publish_release.ps1)
7. HARD-WON BUGS, EDGE CASES & DEVELOPER NOTES
   7.1. Swapping Running Executables on Windows during Self-Update
   7.2. WinStation / Desktop Detection in Windows Sessions
   7.3. ADB Port Saturation & Socket Timeout Prevention
   7.4. Coordinate Math for BlueStacks 900x1600 Portrait Windows
8. FULL VERBATIM SOURCE CODES
   8.1. compile_exe.ps1
   8.2. publish_release.ps1
   8.3. app.manifest
   8.4. version.json
   8.5. HOW_TO_RUN.txt
   8.6. CashMafiaApp.cs (Companion CashMafia engine)
   8.7. QuizEarnApp.cs (Complete Main Windows Engine, ~6,180 lines)
'@

Add-Header "1. EXECUTIVE SUMMARY & WHAT THE EXE BOT DOES"
Add-Text @'
QuizEarnBot.exe is a high-performance Windows desktop application built with C# and
Windows Forms on .NET Framework 4.0 / 4.8.

The application exists to automate mobile reward earning apps running in BlueStacks 5
emulators, specifically:
- Quiz & Earn (com.quiz.earn.money.game)
- CashMafia (com.rayole.cashmafia2)

Revenue Generation Mechanism:
1. Solving Math Captchas: Both apps present users with continuous arithmetic questions
   (e.g., "47 + 19 = ?", "83 - 29 = ?") accompanied by 4 multiple-choice buttons.
   Each correct answer awards gems and interstitial ad impressions.
2. Accumulating 25 Gems: Once 25 gems are earned, the apps unlock a "Super Offer" card
   granting a guaranteed 1,000 Coin payout upon installing a sponsored app from Google Play,
   running it for 2 minutes, and claiming verification.
3. Daily Bonus Tasks & Offerwalls: 10 daily partner offers, daily check-ins, and reward
   milestones that can be claimed systematically.

QuizEarnBot.exe coordinates all of this from the user''s Windows desktop. It can either
control BlueStacks directly over ADB (Mode A), or act as the autonomous multi-instance
watchdog and control center while the internal Android Accessibility Service APK
(QuizEarnBot.apk) does zero-latency execution inside the emulator (Mode B).
'@

Add-Header "2. DUAL OPERATIONAL MODES: PC-AUTOMATION VS. WATCHDOG COMPANION"
Add-Text @'
QuizEarnBot.exe supports two distinct operating architectures:

MODE A: STANDALONE PC-DRIVEN AUTOMATION (External Controller)
- The PC connects to BlueStacks instances via ADB on localhost ports (5555, 5585, etc.).
- Takes screenshots via `adb exec-out screencap -p` or dumps the accessibility tree via
  `adb shell uiautomator dump`.
- Uses internal string parsing and OCR-free node tree inspection to detect questions.
- Solves arithmetic equations inside QuizEarnApp.cs in memory.
- Sends touch events via `adb shell input tap X Y`.
- Pros: Requires no APK installed in Android.
- Cons: Slower (1.5–2.5 seconds per cycle due to ADB image/XML dump latency).

MODE B: AUTONOMOUS MULTI-INSTANCE WATCHDOG & COMMAND CENTER (Recommended)
- The heavy lifting (question solving, ad timer waits, Play Store installs, 2-minute app
  simulation, and verification) is handled natively by the Android Accessibility Service
  APK (QuizEarnBot.apk) running inside BlueStacks at 50ms latency.
- QuizEarnBot.exe acts as the master desktop control center and watchdog:
  * Watches BlueStacks processes (HD-Player.exe) and connected ADB ports.
  * Checks if the Accessibility Service is enabled; if disabled, auto-enables it via ADB secure settings.
  * Checks if the Quiz app or APK bot crashed; if so, cleanly relaunches them.
  * Prevents Windows from going into sleep or standby using `SetThreadExecutionState`.
  * Logs daily earnings, 25-gem cycles, and errors into `quizearn_stats.json`.
  * Provides a beautiful 3-tab OLED GUI for one-click controls and live stats.
  * Handles cloud auto-updates so all friends running the EXE get updates automatically.
'@

Add-Header "3. FULL WORK HISTORY, USER SESSIONS & EVOLUTION CHRONOLOGY"
Add-Text @'
To give any AI complete context on how this project reached its current state, here is the
chronological evolution of the system based on actual user sessions and problem-solving:

3.1. Phase 1: Standalone ADB Automation (QuizEarnApp.cs & CashMafiaApp.cs)
- Initially, the bot was purely a C# desktop app (`QuizEarnApp.cs` and `CashMafiaApp.cs`).
- It used ADB commands to take screenshots, find math question coordinates, evaluate
  arithmetic, and click answer choices.
- It worked well for a single emulator instance, but as the user scaled to 4 BlueStacks
  instances (Player 1, Player 3, Player 4, Player 5), the ADB daemon became saturated.
  Four instances taking screenshots every 2 seconds caused ADB socket timeouts, high CPU,
  and dropped taps.

3.2. Phase 2: Creation of the In-App Android Accessibility Service (QuizEarnBot.apk)
- To eliminate ADB latency entirely, we built a native Android Accessibility Service APK
  (`com.quizearn.assistant`).
- The APK runs directly inside each BlueStacks instance. It receives accessibility events
  at 50ms intervals, evaluates math questions instantly in memory, and dispatches native
  gestures (`dispatchGesture`) with 0ms latency.
- It includes a floating OLED HUD bubble on screen with START/STOP controls, a live gem
  counter, and an AUTO-OFFER toggle.

3.3. Phase 3: The Super Offer Automation & Live Debugging
- The user wanted Player 1 (port 5555) to fully automate the 25-gem "Super Offer" cycle:
  Quiz -> 25 Gems -> Start Offer -> Ad -> Google Play Store -> Install -> 2-min usage ->
  Verify -> Claim 1,000 Coins -> Uninstall app.
- Players 3, 4, 5 (ports 5585, 5596, 5605) were designated as pure "Gem Farmers" (solving
  captchas only, with downloads disabled to save disk and bandwidth).
- We encountered and resolved several critical edge-case bugs:
  1. Ad Premature Close: Video ads were closing at 6-7s. We enforced 26s–35s minimums.
  2. "Close Ad? You will lose your reward": When the close confirmation dialog popped up,
     the bot targeted the blue [RESUME] button at coordinates (0.68 * W, 0.60 * H).
  3. The Warning Text Loop Trap: The bot got stuck spamming taps at (489, 597).
     Root cause: The warning text "⚠️ If you already have a pending offer for the app shown
     in the ad, skip and try a different app" matched `t.contains("try a different app")`.
     Because it was informational text, clicking did nothing. We fixed it by requiring
     `item.node.isClickable()` and filtering out "pending offer" / "skip and try" text.
  4. BlueStacks App Launching: `context.startActivity(launchIntent)` often failed in BlueStacks.
     We implemented a dual-launch strategy using Android Intent + `monkey -p <pkg> ... 1`.
  5. App Keeps Stopping / Crash Recovery: If a downloaded app crashes repeatedly, the bot
     scrolls up to reveal "Stuck? Restart Offer" under the 2-minute progress bar, uninstalls
     the broken package, and resets cleanly.

3.4. Phase 4: Cloud Auto-Updater & Friend Distribution
- The user needed a seamless way to share the bot with friends without having to send new
  files manually every time an update was made.
- We built the Cloud Auto-Updater protocol in `QuizEarnApp.cs` and `publish_release.ps1`:
  * Every release compiles `QuizEarnBot.exe`, generates `version.json` with a version code,
    packages `QuizEarnBot_vX.X_Portable.zip`, and pushes live to GitHub.
  * When friends open `QuizEarnBot.exe`, it queries `version.json` on launch.
  * If the remote version code is higher than their local version code, the app prompts
    them with the changelog and a 1-click "Update Now" button.
  * It downloads the latest binary in the background, swaps the executable, and relaunches.

3.5. Phase 5: Dual App Support (Quiz & Earn + CashMafia)
- We added seamless support for CashMafia (`com.rayole.cashmafia2`) alongside Quiz & Earn
  (`com.quiz.earn.money.game`).
- The user can toggle the active target app directly from the GUI or the floating HUD.
'@

Add-Header "4. USER INTERFACE & VISUAL DESIGN ARCHITECTURE (Pure OLED Black)"
Add-Text @'
QuizEarnApp.cs features a custom-drawn, high-end "Stealth OLED Black" interface
specifically engineered for zero eye strain during overnight farming sessions.

4.1. Color Palette:
- Pure OLED Background: #000000
- Deep Card Surface: #08080A
- Card Border: #1F1F26
- Accent Emerald (Success/Active): #10B981
- Accent Red (Stop/Danger): #EF4444
- Accent Purple (Special Offers): #8B5CF6
- Text Primary: #FFFFFF
- Text Muted: #9CA3AF

4.2. Tab 1: HOME View
- System Header: Shows app title, version badge, and current active emulator status.
- Primary Action Hero:
  * Large [ START QUIZ EARN BOT ] button with real-time pulsing status.
  * [ AUTO-CLAIM ALL OFFERS (1-Click) ] button for rapid milestone redemption.
- Live Metrics Bar:
  * Solved Captchas Counter.
  * Gems Collected (X / 25).
  * Super Offers Completed today.
  * Total Coins Earned today.
- Instance Target Selector: Dropdown to select between Player 1 (5555), Player 3 (5585),
  Player 4 (5596), or All Connected Instances.

4.3. Tab 2: DATA View
- Historical Analytics Dashboard:
  * Daily coins earned chart / table.
  * Total 25-gem milestone cycles completed over time.
  * Session runtime counter.
- Live Console Log:
  * Scrollable terminal-style output displaying every ADB command, tap coordinate,
    OCR result, and milestone event in real-time.
- Export / Reset buttons: Allows clearing stats or exporting data to CSV/JSON.

4.4. Tab 3: SETTINGS View
- Emulator Configuration:
  * ADB executable path (auto-detects local `adb.exe`).
  * Custom port override (e.g. 5555, 5585, etc.).
  * Watchdog timer intervals.
- Automation Preferences:
  * Auto-recover BlueStacks on freeze/crash toggle.
  * Screen keep-alive toggle (prevents PC sleep).
  * Target app switcher: Quiz & Earn vs. CashMafia.
- Cloud Auto-Updater Card:
  * Displays Current Version (e.g. v2.8) and Latest Cloud Version.
  * Changelog preview box.
  * [ CHECK FOR UPDATES ] button and [ UPDATE NOW ] one-click updater.
'@

Add-Header "5. CORE ENGINES & SUBSYSTEMS INSIDE QuizEarnApp.cs"
Add-Text @'
Below is an in-depth breakdown of the major subsystems implemented in `QuizEarnApp.cs`:

5.1. Win32 Power & Display Keep-Alive Engine:
```csharp
[DllImport("kernel32.dll", CharSet = CharSet.Auto, SetLastError = true)]
private static extern uint SetThreadExecutionState(uint esFlags);
private const uint ES_CONTINUOUS = 0x80000000;
private const uint ES_SYSTEM_REQUIRED = 0x00000001;
private const uint ES_DISPLAY_REQUIRED = 0x00000002;
```
Called when the bot starts solving to prevent Windows from locking the screen or entering
sleep mode during long 24/7 farming sessions.

5.2. ADB Communication Engine:
- `RunAdbCommand(string arguments, int timeoutMs)`
- Spawns a hidden `adb.exe` process with redirected stdout and stderr.
- Automatically handles process timeouts, port scanning (`adb devices`), and connection
  retries (`adb connect 127.0.0.1:XXXX`).

5.3. Math Quiz Solver & OCR-Free Tree Parser:
- Inspects screen node hierarchy for arithmetic questions.
- Uses regex patterns like `(\d+)\s*([\+\-\*])\s*(\d+)` to parse numbers and operands.
- Calculates the mathematical result in memory:
  * Addition (`A + B`)
  * Subtraction (`A - B`)
  * Multiplication (`A * B`)
- Matches the calculated answer against candidate button labels on screen.
- Emits instantaneous tap commands to the winning button bounds.

5.4. Cloud Auto-Updater Subsystem:
- Asynchronously queries `https://raw.githubusercontent.com/THESHMIL0/QuizEarnBot/main/version.json`.
- Parses `version`, `version_code`, `changelog`, and `download_url`.
- If `remoteVersionCode > APP_VERSION_CODE`:
  * Displays "Update Available!" notification.
  * On user confirmation, downloads the latest `QuizEarnBot.exe` to a temporary file (`tempExe`).
  * Spawns a detached cmd/PowerShell self-updater script that waits for the current process
    to exit, replaces `QuizEarnBot.exe`, and relaunches the new version cleanly.

5.5. Multi-Instance Watchdog Subsystem:
- Periodically checks if the BlueStacks process (`HD-Player.exe`) is responding.
- Checks if `com.quizearn.assistant` is alive in Android memory (`pidof com.quizearn.assistant`).
- Checks if the Accessibility Service is active in secure settings.
- If an instance crashes or freezes, the watchdog:
  * Restarts the target app (`am start ...`).
  * Re-enables the Accessibility Service (`settings put secure enabled_accessibility_services ...`).
  * Brings Quiz & Earn back to focus.

5.6. Persistent Analytics Tracker (quizearn_stats.json):
- Serializes daily performance:
  * Date stamps
  * Coins accumulated
  * Gems accrued
  * Completed Super Offers
- Loads automatically on application startup to preserve continuous earning records.
'@

Add-Header "6. COMPILATION, SIGNING & PACKAGING TOOLCHAIN"
Add-Text @'
QuizEarnBot.exe does NOT require Visual Studio, MSBuild, or heavy IDEs to build.
It compiles directly using the built-in Microsoft .NET Framework compiler present on
all Windows PCs:

Compiler Path:
`C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe`

Compilation Command in `compile_exe.ps1`:
```powershell
$WorkingDir = 'C:\Users\THESHMIL\Desktop\Quiz_Earn_Bot'
$CscPath = 'C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe'
$SourcePath = Join-Path $WorkingDir 'QuizEarnApp.cs'
$ExePath = Join-Path $WorkingDir 'QuizEarnBot.exe'
$ico = Join-Path $WorkingDir 'app.ico'
$manifest = Join-Path $WorkingDir 'app.manifest'
$compileArgs = @('/target:winexe', "/out:$ExePath", '/platform:anycpu', '/optimize+')
if (Test-Path $ico) { $compileArgs += "/win32icon:$ico" }
if (Test-Path $manifest) { $compileArgs += "/win32manifest:$manifest" }
$compileArgs += $SourcePath
& $CscPath $compileArgs
```

Release Publisher in `publish_release.ps1`:
1. Compiles `QuizEarnApp.cs` into `QuizEarnBot.exe`.
2. Builds the Android APK (`QuizEarnBot.apk`) using JDK 17 and AAPT2.
3. Generates the updated `version.json` with the new version code.
4. Packages `QuizEarnBot_vX.X_Portable.zip` with all binaries, DLLs, and scripts.
5. Commits and pushes everything live to GitHub (`main` branch).
'@

Add-Header "7. HARD-WON BUGS, EDGE CASES & DEVELOPER NOTES"
Add-Text @'
Below are the critical technical hurdles solved during the development of QuizEarnBot.exe:

7.1. Swapping Running Executables on Windows during Self-Update:
On Windows, a running `.exe` file is locked by the OS kernel and cannot be directly
overwritten. To achieve seamless 1-click cloud updates:
- `QuizEarnApp.cs` downloads the new executable to `%TEMP%\QuizEarnBot_new.exe`.
- It writes a temporary batch script (`update.bat`) that loops until the current PID exits:
  ```bat
  :loop
  tasklist | findstr /i "QuizEarnBot.exe" >nul
  if not errorlevel 1 (
      timeout /t 1 >nul
      goto loop
  )
  move /y "%TEMP%\QuizEarnBot_new.exe" "C:\Users\...\QuizEarnBot.exe"
  start "" "C:\Users\...\QuizEarnBot.exe"
  del "%~f0"
  ```
- It launches `update.bat` in a detached process and cleanly calls `Application.Exit()`.

7.2. WinStation / Desktop Detection in Windows Sessions:
When running bots on unattended virtual machines or headless servers, Windows can drop
into a disconnected WinStation session where GUI rendering is throttled.
We implemented `check_winstation.ps1` and `check_windows.ps1` to detect whether BlueStacks
is running in an interactive session and prevent GDI capture crashes.

7.3. ADB Port Saturation & Socket Timeout Prevention:
Calling `adb exec-out` hundreds of times per minute can exhaust Windows ephemeral TCP ports
(TIME_WAIT state).
We mitigated this by:
1. Reusing persistent ADB daemon connections.
2. Migrating high-frequency micro-loops into the Android APK (`QuizEarnBot.apk`).
3. Limiting PC-side poll checks to 500ms–1000ms intervals.
'@

Add-Header "8. FULL SOURCE CODES FOR ALL EXE BOT TOOLS"

Add-CodeFile "C:\Users\THESHMIL\Desktop\Quiz_Earn_Bot\compile_exe.ps1" "compile_exe.ps1"
Add-CodeFile "C:\Users\THESHMIL\Desktop\Quiz_Earn_Bot\publish_release.ps1" "publish_release.ps1"
Add-CodeFile "C:\Users\THESHMIL\Desktop\Quiz_Earn_Bot\app.manifest" "app.manifest"
Add-CodeFile "C:\Users\THESHMIL\Desktop\Quiz_Earn_Bot\version.json" "version.json"
Add-CodeFile "C:\Users\THESHMIL\Desktop\Quiz_Earn_Bot\HOW_TO_RUN.txt" "HOW_TO_RUN.txt"
Add-CodeFile "C:\Users\THESHMIL\Desktop\Quiz_Earn_Bot\CashMafiaApp.cs" "CashMafiaApp.cs"
Add-CodeFile "C:\Users\THESHMIL\Desktop\Quiz_Earn_Bot\QuizEarnApp.cs" "QuizEarnApp.cs (Main C# Engine)"

Add-Header "END OF MASTER EXE BOT HANDOVER DOCUMENT"

Write-Host ">>> Writing output file 1: $outPath1"
[System.IO.File]::WriteAllText($outPath1, $sb.ToString(), [System.Text.Encoding]::UTF8)

Write-Host ">>> Writing output file 2: $outPath2"
[System.IO.File]::WriteAllText($outPath2, $sb.ToString(), [System.Text.Encoding]::UTF8)

$f1 = Get-Item $outPath1
$f2 = Get-Item $outPath2

Write-Host ">>> SUCCESS!"
Write-Host "File 1: $($f1.FullName) (Bytes: $($f1.Length))"
Write-Host "File 2: $($f2.FullName) (Bytes: $($f2.Length))"
