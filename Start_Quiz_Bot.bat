@echo off
title Quiz & Earn Auto-Collector Bot
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0quiz_bot.ps1"
pause
