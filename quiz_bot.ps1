<#
.SYNOPSIS
    Quiz & Earn Auto-Collector Bot (AI Vision & Auto-Detect)
.DESCRIPTION
    Automates: Click Option A -> Auto-Detect 'Collect Diamonds' -> Auto-Detect/Wait Ad -> Click Close (X) -> Repeat to 25 Diamonds.
#>

Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Runtime.WindowsRuntime

$asTaskGeneric = ([System.WindowsRuntimeSystemExtensions].GetMethods() | ? { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0]
Function Await($WinRtTask, $ResultType) {
    $asTask = $asTaskGeneric.MakeGenericMethod($ResultType)
    $netTask = $asTask.Invoke($null, @($WinRtTask))
    $netTask.Wait(-1) | Out-Null
    $netTask.Result
}

try {
    [Windows.Media.Ocr.OcrEngine, Windows.Foundation, ContentType = WindowsRuntime] | Out-Null
    [Windows.Graphics.Imaging.BitmapDecoder, Windows.Foundation, ContentType = WindowsRuntime] | Out-Null
    [Windows.Storage.Streams.InMemoryRandomAccessStream, Windows.Foundation, ContentType = WindowsRuntime] | Out-Null
    $script:ocrEngine = [Windows.Media.Ocr.OcrEngine]::TryCreateFromUserProfileLanguages()
} catch {
    $script:ocrEngine = $null
}

Add-Type -MemberDefinition @"
[DllImport("user32.dll", CharSet = CharSet.Auto, CallingConvention = CallingConvention.StdCall)]
public static extern void mouse_event(uint dwFlags, uint dx, uint dy, uint cButtons, uint dwExtraInfo);

[DllImport("user32.dll")]
public static extern short GetAsyncKeyState(int vKey);

[DllImport("user32.dll")]
public static extern IntPtr GetForegroundWindow();

[DllImport("user32.dll")]
public static extern bool GetWindowRect(IntPtr hWnd, out RECT lpRect);

[StructLayout(LayoutKind.Sequential)]
public struct RECT {
    public int Left;
    public int Top;
    public int Right;
    public int Bottom;
}
"@ -Name "Win32Mouse" -Namespace "QuizBotAPI"

$configPath = Join-Path $PSScriptRoot "bot_config.json"
$VK_SPACE = 0x20
$VK_ESCAPE = 0x1B

function Test-KeyDown([int]$vKey) {
    return ([QuizBotAPI.Win32Mouse]::GetAsyncKeyState($vKey) -band 0x8000) -ne 0
}

function Click-At([int]$x, [int]$y) {
    [System.Windows.Forms.Cursor]::Position = New-Object System.Drawing.Point($x, $y)
    Start-Sleep -Milliseconds 80
    [QuizBotAPI.Win32Mouse]::mouse_event(0x02, $x, $y, 0, 0) # Left down
    Start-Sleep -Milliseconds 60
    [QuizBotAPI.Win32Mouse]::mouse_event(0x04, $x, $y, 0, 0) # Left up
    Start-Sleep -Milliseconds 100
}

function Capture-ScreenOcr([System.Drawing.Rectangle]$bounds) {
    if (-not $script:ocrEngine) { return $null }
    try {
        $bmp = New-Object System.Drawing.Bitmap($bounds.Width, $bounds.Height)
        $gfx = [System.Drawing.Graphics]::FromImage($bmp)
        $gfx.CopyFromScreen($bounds.X, $bounds.Y, 0, 0, $bounds.Size, [System.Drawing.CopyPixelOperation]::SourceCopy)
        $gfx.Dispose()

        $ms = New-Object System.IO.MemoryStream
        $bmp.Save($ms, [System.Drawing.Imaging.ImageFormat]::Bmp)
        $bytes = $ms.ToArray()
        $ms.Dispose()
        $bmp.Dispose()

        $ras = New-Object Windows.Storage.Streams.InMemoryRandomAccessStream
        $writer = New-Object Windows.Storage.Streams.DataWriter($ras)
        $writer.WriteBytes($bytes)
        $null = Await ($writer.StoreAsync()) ([uint32])
        $ras.Seek(0)

        $decoder = Await ([Windows.Graphics.Imaging.BitmapDecoder]::CreateAsync($ras)) ([Windows.Graphics.Imaging.BitmapDecoder])
        $softwareBitmap = Await ($decoder.GetSoftwareBitmapAsync()) ([Windows.Graphics.Imaging.SoftwareBitmap])
        $result = Await ($script:ocrEngine.RecognizeAsync($softwareBitmap)) ([Windows.Media.Ocr.OcrResult])
        return $result
    } catch {
        return $null
    }
}

function Find-TextLocation([string[]]$keywords, [System.Drawing.Rectangle]$bounds) {
    $ocr = Capture-ScreenOcr $bounds
    if (-not $ocr) { return $null }

    foreach ($line in $ocr.Lines) {
        foreach ($w in $line.Words) {
            foreach ($kw in $keywords) {
                if ($w.Text -like "*$kw*") {
                    $centerX = [int]($bounds.X + $w.BoundingRect.X + ($w.BoundingRect.Width / 2))
                    $centerY = [int]($bounds.Y + $w.BoundingRect.Y + ($w.BoundingRect.Height / 2))
                    return @{
                        Found = $true
                        Text = $w.Text
                        X = $centerX
                        Y = $centerY
                    }
                }
            }
        }
    }
    return $null
}

function Calibrate-Point([string]$promptMessage) {
    Write-Host "`n>>> $promptMessage" -ForegroundColor Yellow
    Write-Host "Hover mouse over the target, then press [SPACEBAR]..." -ForegroundColor Cyan

    while (Test-KeyDown $VK_SPACE) { Start-Sleep -Milliseconds 50 }
    while (-not (Test-KeyDown $VK_SPACE)) {
        if (Test-KeyDown $VK_ESCAPE) { Write-Host "`nSetup cancelled." -ForegroundColor Red; exit }
        $pos = [System.Windows.Forms.Cursor]::Position
        Write-Host -NoNewline "`rTarget: X=$($pos.X), Y=$($pos.Y)  [Press SPACE to save]   "
        Start-Sleep -Milliseconds 40
    }
    $finalPos = [System.Windows.Forms.Cursor]::Position
    Write-Host "`n[Captured] X=$($finalPos.X), Y=$($finalPos.Y)" -ForegroundColor Green
    [System.Media.SystemSounds]::Beep.Play()
    Start-Sleep -Milliseconds 300
    return @{ X = $finalPos.X; Y = $finalPos.Y }
}

Clear-Host
Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "      QUIZ & EARN AUTO-COLLECTOR (AI AUTO-DETECT)         " -ForegroundColor Yellow
Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "Features:"
Write-Host "  * [AUTO-DETECT] AI Vision detects 'Collect Diamonds' & buttons" -ForegroundColor Green
Write-Host "  * [TIMER SYNC] Waits for ad completion & automatically closes 'X'" -ForegroundColor Green
Write-Host "  * [SAFE STOP] Press ESC at ANY time to immediately exit" -ForegroundColor Magenta
Write-Host "----------------------------------------------------------"

$config = $null
if (Test-Path $configPath) {
    try { $config = Get-Content $configPath -Raw | ConvertFrom-Json } catch {}
}

Write-Host "`nChoose Bot Mode:" -ForegroundColor Yellow
Write-Host "  [1] AI Auto-Detect Mode (Recommended - Auto-detects buttons on screen)" -ForegroundColor Green
Write-Host "  [2] Quick Manual Calibration (Pinpoint exact click spots)" -ForegroundColor White

$modeChoice = Read-Host "`nSelect Mode (1 or 2) [Default: 1]"
if ($modeChoice -eq "" -or $modeChoice -eq "1") {
    $useAutoDetect = $true
} else {
    $useAutoDetect = $false
}

if (-not $config) {
    $config = [PSCustomObject]@{
        OptionA = @{ X = 0; Y = 0 }
        CollectBtn = @{ X = 0; Y = 0 }
        AdCloseBtn = @{ X = 0; Y = 0 }
        AdWaitSeconds = 32
    }
}

if (-not $useAutoDetect -or ($config.OptionA.X -eq 0 -and $config.OptionA.Y -eq 0)) {
    Write-Host "`n--- ONE-TIME SETUP ---" -ForegroundColor Cyan
    Write-Host "We will set reference points for Option A and Ad 'X' close button." -ForegroundColor White
    $config.OptionA = Calibrate-Point "1. Point to OPTION A button"
    $config.CollectBtn = Calibrate-Point "2. Point to 'COLLECT DIAMONDS' / 'CLAIM' button"
    $config.AdCloseBtn = Calibrate-Point "3. Point to AD CLOSE 'X' mark (usually top-right or top-left)"
    
    $adSec = Read-Host "`nEnter ad wait time in seconds [Default: 32]"
    if ([int]::TryParse($adSec, [ref]$null) -and [int]$adSec -gt 0) {
        $config.AdWaitSeconds = [int]$adSec
    }
    $config | ConvertTo-Json -Depth 4 | Set-Content $configPath -Force
}

$targetDiamonds = 25
$diamondInput = Read-Host "`nEnter target diamonds [Default: 25]"
if ([int]::TryParse($diamondInput, [ref]$null) -and [int]$diamondInput -gt 0) {
    $targetDiamonds = [int]$diamondInput
}

Write-Host "`n==========================================================" -ForegroundColor Green
Write-Host " BOT READY! Auto-Detect Enabled: $useAutoDetect" -ForegroundColor Yellow
Write-Host " Target: $targetDiamonds Diamonds" -ForegroundColor Cyan
Write-Host " Switch to the Quiz & Earn app window now." -ForegroundColor White
Write-Host " Starting in 5 seconds... (Press ESC to cancel)" -ForegroundColor Red
Write-Host "==========================================================" -ForegroundColor Green

for ($i = 5; $i -gt 0; $i--) {
    if (Test-KeyDown $VK_ESCAPE) { Write-Host "`nAborted."; exit }
    Write-Host "$i..." -ForegroundColor Yellow
    Start-Sleep -Seconds 1
}

$currentDiamonds = 0
$screenBounds = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds

for ($cycle = 1; $cycle -le $targetDiamonds; $cycle++) {
    if (Test-KeyDown $VK_ESCAPE) { Write-Host "`n[STOPPED] Exited by user."; break }

    Write-Host "`n----------------------------------------------------------"
    Write-Host ">> [Cycle $cycle / $targetDiamonds] Answering Question..." -ForegroundColor Cyan

    # 1. Click Option A
    $optX = $config.OptionA.X
    $optY = $config.OptionA.Y
    if ($useAutoDetect) {
        $detectedA = Find-TextLocation @("A.", "A)", "Option A") $screenBounds
        if ($detectedA) {
            $optX = $detectedA.X
            $optY = $detectedA.Y
            Write-Host "  [AI Detected] Found Option A at ($optX, $optY)" -ForegroundColor Green
        }
    }
    Write-Host "  [1/4] Clicking Option A at ($optX, $optY)..." -ForegroundColor White
    Click-At $optX $optY

    # 2. Wait and Auto-Detect Collect Diamonds
    Write-Host "  [2/4] Watching for 'Collect Diamonds' / 'Claim' popup..." -ForegroundColor Yellow
    $collectClicked = $false
    $claimKeywords = @("Collect", "Claim", "Diamond", "Diamonds", "Reward", "Get")
    
    for ($waitTick = 0; $waitTick -lt 30; $waitTick++) {
        if (Test-KeyDown $VK_ESCAPE) { exit }
        
        if ($useAutoDetect) {
            $foundClaim = Find-TextLocation $claimKeywords $screenBounds
            if ($foundClaim) {
                Write-Host "  [AI Detected] Popup button '$($foundClaim.Text)' at ($($foundClaim.X), $($foundClaim.Y))!" -ForegroundColor Green
                Click-At $foundClaim.X $foundClaim.Y
                $collectClicked = $true
                break
            }
        }
        Start-Sleep -Milliseconds 150
    }

    if (-not $collectClicked) {
        Write-Host "  [Fallback] Clicking saved Collect position ($($config.CollectBtn.X), $($config.CollectBtn.Y))..." -ForegroundColor Gray
        Click-At $config.CollectBtn.X $config.CollectBtn.Y
    }

    # 3. Ad Wait & Auto-Close
    $waitSec = $config.AdWaitSeconds
    Write-Host "  [3/4] Ad started. Waiting ${waitSec}s for video ad to complete..." -ForegroundColor Yellow
    for ($t = $waitSec; $t -gt 0; $t--) {
        if (Test-KeyDown $VK_ESCAPE) { Write-Host "`n[STOPPED] User pressed ESC."; exit }
        Write-Host -NoNewline "`r  Playing Ad... ${t}s remaining (Press ESC to cancel)   "
        Start-Sleep -Seconds 1
    }
    Write-Host ""

    # 4. Close Ad
    $closeKeywords = @("Close", "Skip", ">>", "X")
    $closeX = $config.AdCloseBtn.X
    $closeY = $config.AdCloseBtn.Y

    if ($useAutoDetect) {
        $foundClose = Find-TextLocation $closeKeywords $screenBounds
        if ($foundClose) {
            $closeX = $foundClose.X
            $closeY = $foundClose.Y
            Write-Host "  [AI Detected] Ad close button '$($foundClose.Text)' at ($closeX, $closeY)!" -ForegroundColor Green
        }
    }

    Write-Host "  [4/4] Clicking Close 'X' at ($closeX, $closeY)..." -ForegroundColor White
    Click-At $closeX $closeY
    Start-Sleep -Milliseconds 1500
    # Secondary tap to dismiss ad overlays
    Click-At $closeX $closeY

    $currentDiamonds++
    Write-Host "[SUCCESS] Collected Diamond $currentDiamonds / $targetDiamonds! 💎" -ForegroundColor Green
    Start-Sleep -Seconds 2
}

[System.Media.SystemSounds]::Exclamation.Play()
Write-Host "`n==========================================================" -ForegroundColor Green
Write-Host " COMPLETED! Successfully gathered $currentDiamonds Diamonds! 🎉" -ForegroundColor Yellow
Write-Host "==========================================================" -ForegroundColor Green
Write-Host "Press any key to exit..."
$null = $Host.UI.RawUI.ReadKey("NoEcho,IncludeKeyDown")
