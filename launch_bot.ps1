$shell = New-Object -ComObject Shell.Application
$shell.ShellExecute("C:\Users\THESHMIL\Desktop\Quiz_Earn_Bot\QuizEarnBot.exe", "", "C:\Users\THESHMIL\Desktop\Quiz_Earn_Bot", "open", 1)
Start-Sleep -Seconds 2
$p = Get-Process -Name QuizEarnBot -ErrorAction SilentlyContinue
if ($p) {
    Write-Host "SUCCESS: QuizEarnBot running with PID $($p.Id)"
} else {
    Write-Host "QuizEarnBot process not detected yet."
}
