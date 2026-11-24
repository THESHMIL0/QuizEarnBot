$shell = New-Object -ComObject Shell.Application
$shell.ShellExecute("C:\Program Files\BlueStacks_nxt\HD-Player.exe", "--instance Tiramisu64_3", "C:\Program Files\BlueStacks_nxt", "open", 1)
Write-Host "Player 3 launched via Windows Shell.Application"
