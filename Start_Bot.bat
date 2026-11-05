@echo off
title Quiz & Earn Auto-Bot Launcher
powershell -Command "Unblock-File -Path '.\*' -ErrorAction SilentlyContinue"
start "" "%~dp0QuizEarnBot.exe"

