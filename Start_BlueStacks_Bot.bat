@echo off
title BlueStacks Quiz and Earn Background Bot
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0bluestacks_bot.ps1"
pause
