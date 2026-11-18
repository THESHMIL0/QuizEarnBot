# Quiz & Earn Auto-Bot (v2.8)

Automated background solver, multi-instance gem farmer, and Super Offer milestone auto-installer for **Quiz & Earn** (`com.quiz.earn.money.game`) and **CashMafia** (`com.rayole.cashmafia2`).

## Features
- **Dual Engine v2.8**: Seamlessly switch between Quiz & Earn and CashMafia from the floating HUD or dashboard.
- **Super Offer Automation v2.0**:
  - Automatically claims 25-gem milestone.
  - Plays rewarded video ads with reward safety protection (26s–35s minimums).
  - Taps Play Store install and verifies installation.
  - Dual-launch strategy (Intent + ADB Monkey fallback) for guaranteed app launch on BlueStacks.
  - Active 2-minute engagement simulation with touch & swipe simulation.
  - Recovers from crashed offer apps automatically using "Stuck? Restart Offer".
  - Verifies and claims guaranteed 1,000 Coin reward.
  - Silent post-claim uninstallation of sponsored apps to keep storage clean.
- **Multi-Instance BlueStacks Support**: Auto-detects all connected emulator ports (`5555`, `5585`, `5596`, etc.), runs dedicated offer downloads on Player 1, and pure gem farming on secondary players.
- **Pure OLED Stealth Black Theme**: Zero eye strain with floating HUD overlay showing real-time gem counts.
- **Cloud Auto-Updater**: Friends receive instant update prompts on launch with 1-click update.
- **Doze & Battery Optimization Bypass**: Whitelisted for continuous background operation without being paused by the OS.

## Downloads
- **Windows Portable ZIP (v2.8)**: [QuizEarnBot_v2.8_Portable.zip](https://github.com/THESHMIL0/QuizEarnBot/raw/main/QuizEarnBot_v2.8_Portable.zip)
- **Direct Windows EXE**: [QuizEarnBot.exe](https://github.com/THESHMIL0/QuizEarnBot/raw/main/QuizEarnBot.exe)
- **Android Bot APK**: [QuizEarnBot.apk](https://github.com/THESHMIL0/QuizEarnBot/raw/main/QuizEarnBot.apk)

## Quick Start
1. Open BlueStacks 5 (Resolution: 900x1600 Portrait).
2. Enable ADB in BlueStacks Settings -> Advanced.
3. Run `deploy_all.ps1` in PowerShell, or double-click `QuizEarnBot.exe`.
