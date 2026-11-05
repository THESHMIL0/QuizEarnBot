using System;
using System.Collections.Generic;
using System.Drawing;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Text.RegularExpressions;
using System.Threading;
using System.Windows.Forms;

public class CashMafiaApp : Form
{
    private Label lblTitle;
    private Label lblStatus;
    private Label lblPort;
    private Button btnToggle;
    private Label lblGems;
    private Label lblTodaySolved;
    private Label lblStats;
    private Label lblCaptcha;
    private RichTextBox txtLog;
    private CheckBox chkAlwaysOnTop;
    private CheckBox chkAutoMute;
    private CheckBox chkTurboSpeed;

    private Thread botThread;
    private volatile bool isRunning = false;
    private string adbPort = "emulator-5554";
    private string adbExe = "adb";

    private int questionsSolved = 0;
    private int todaySolved = 0;
    private int gemsEarned = 0;
    private int adsClosed = 0;
    private string lastCaptcha = "None";
    private DateTime botStartTime;
    private DateTime lastProgressTime = DateTime.Now;

    public CashMafiaApp()
    {
        Control.CheckForIllegalCrossThreadCalls = false;
        if (File.Exists(@"C:\Program Files\BlueStacks_nxt\HD-Adb.exe"))
        {
            adbExe = @"C:\Program Files\BlueStacks_nxt\HD-Adb.exe";
        }
        InitializeComponent();
    }

    private void InitializeComponent()
    {
        this.Text = "Cash Mafia - Auto Quiz & Reward Bot";
        this.Size = new Size(560, 720);
        this.StartPosition = FormStartPosition.CenterScreen;
        this.BackColor = Color.FromArgb(16, 20, 26);
        this.ForeColor = Color.White;
        this.Font = new Font("Segoe UI", 9.5f, FontStyle.Regular);
        this.FormBorderStyle = FormBorderStyle.FixedSingle;
        this.MaximizeBox = false;

        // Header Title
        lblTitle = new Label();
        lblTitle.Text = "💰 Cash Mafia Auto-Bot";
        lblTitle.Font = new Font("Segoe UI", 16f, FontStyle.Bold);
        lblTitle.ForeColor = Color.FromArgb(243, 244, 246);
        lblTitle.Location = new Point(20, 16);
        lblTitle.AutoSize = true;
        this.Controls.Add(lblTitle);

        lblStatus = new Label();
        lblStatus.Text = "🔴 STOPPED (Click Start to Begin)";
        lblStatus.Font = new Font("Segoe UI", 10f, FontStyle.Bold);
        lblStatus.ForeColor = Color.FromArgb(239, 68, 68);
        lblStatus.Location = new Point(24, 48);
        lblStatus.AutoSize = true;
        this.Controls.Add(lblStatus);

        lblPort = new Label();
        lblPort.Text = "BlueStacks: Auto-Detecting...";
        lblPort.Font = new Font("Segoe UI", 8.5f);
        lblPort.ForeColor = Color.FromArgb(156, 163, 175);
        lblPort.Location = new Point(310, 22);
        lblPort.AutoSize = true;
        this.Controls.Add(lblPort);

        // Big ON/OFF Button
        btnToggle = new Button();
        btnToggle.Text = "▶ START CASH MAFIA BOT";
        btnToggle.Font = new Font("Segoe UI", 13f, FontStyle.Bold);
        btnToggle.ForeColor = Color.White;
        btnToggle.BackColor = Color.FromArgb(16, 185, 129); // Emerald Green
        btnToggle.FlatStyle = FlatStyle.Flat;
        btnToggle.FlatAppearance.BorderSize = 0;
        btnToggle.Location = new Point(20, 80);
        btnToggle.Size = new Size(505, 54);
        btnToggle.Cursor = Cursors.Hand;
        btnToggle.Click += new EventHandler(BtnToggle_Click);
        this.Controls.Add(btnToggle);

        // Stats Dashboard Container
        Panel statsPanel = new Panel();
        statsPanel.BackColor = Color.FromArgb(27, 34, 45);
        statsPanel.Location = new Point(20, 146);
        statsPanel.Size = new Size(505, 95);
        statsPanel.BorderStyle = BorderStyle.None;
        this.Controls.Add(statsPanel);

        // Stat Card 1: Today Solved
        lblTodaySolved = new Label();
        lblTodaySolved.Text = "0\nToday Solved";
        lblTodaySolved.Font = new Font("Segoe UI", 11f, FontStyle.Bold);
        lblTodaySolved.ForeColor = Color.FromArgb(96, 165, 250); // Sky blue
        lblTodaySolved.TextAlign = ContentAlignment.MiddleCenter;
        lblTodaySolved.Location = new Point(10, 10);
        lblTodaySolved.Size = new Size(110, 75);
        statsPanel.Controls.Add(lblTodaySolved);

        // Stat Card 2: Gems Earned
        lblGems = new Label();
        lblGems.Text = "0\nGems Earned";
        lblGems.Font = new Font("Segoe UI", 11f, FontStyle.Bold);
        lblGems.ForeColor = Color.FromArgb(52, 211, 153); // Emerald
        lblGems.TextAlign = ContentAlignment.MiddleCenter;
        lblGems.Location = new Point(130, 10);
        lblGems.Size = new Size(110, 75);
        statsPanel.Controls.Add(lblGems);

        // Stat Card 3: Last Captcha
        lblCaptcha = new Label();
        lblCaptcha.Text = "None\nLast Captcha";
        lblCaptcha.Font = new Font("Segoe UI", 11f, FontStyle.Bold);
        lblCaptcha.ForeColor = Color.FromArgb(251, 191, 36); // Amber
        lblCaptcha.TextAlign = ContentAlignment.MiddleCenter;
        lblCaptcha.Location = new Point(250, 10);
        lblCaptcha.Size = new Size(110, 75);
        statsPanel.Controls.Add(lblCaptcha);

        // Stat Card 4: Ads Closed
        lblStats = new Label();
        lblStats.Text = "0\nAds Watched";
        lblStats.Font = new Font("Segoe UI", 11f, FontStyle.Bold);
        lblStats.ForeColor = Color.FromArgb(167, 139, 250); // Violet
        lblStats.TextAlign = ContentAlignment.MiddleCenter;
        lblStats.Location = new Point(370, 10);
        lblStats.Size = new Size(125, 75);
        statsPanel.Controls.Add(lblStats);

        // Checkboxes bar
        chkAlwaysOnTop = new CheckBox();
        chkAlwaysOnTop.Text = "Always On Top";
        chkAlwaysOnTop.Location = new Point(22, 250);
        chkAlwaysOnTop.AutoSize = true;
        chkAlwaysOnTop.ForeColor = Color.FromArgb(209, 213, 219);
        chkAlwaysOnTop.CheckedChanged += (s, e) => { this.TopMost = chkAlwaysOnTop.Checked; };
        this.Controls.Add(chkAlwaysOnTop);

        chkAutoMute = new CheckBox();
        chkAutoMute.Text = "Auto-Mute Ads";
        chkAutoMute.Checked = true;
        chkAutoMute.Location = new Point(155, 250);
        chkAutoMute.AutoSize = true;
        chkAutoMute.ForeColor = Color.FromArgb(209, 213, 219);
        this.Controls.Add(chkAutoMute);

        chkTurboSpeed = new CheckBox();
        chkTurboSpeed.Text = "Turbo Speed";
        chkTurboSpeed.Checked = true;
        chkTurboSpeed.Location = new Point(285, 250);
        chkTurboSpeed.AutoSize = true;
        chkTurboSpeed.ForeColor = Color.FromArgb(209, 213, 219);
        this.Controls.Add(chkTurboSpeed);

        // Activity Log Console
        Label lblLogTitle = new Label();
        lblLogTitle.Text = "📋 Activity Log";
        lblLogTitle.Font = new Font("Segoe UI", 10f, FontStyle.Bold);
        lblLogTitle.ForeColor = Color.FromArgb(156, 163, 175);
        lblLogTitle.Location = new Point(20, 280);
        lblLogTitle.AutoSize = true;
        this.Controls.Add(lblLogTitle);

        txtLog = new RichTextBox();
        txtLog.Location = new Point(20, 305);
        txtLog.Size = new Size(505, 360);
        txtLog.BackColor = Color.FromArgb(11, 14, 19);
        txtLog.ForeColor = Color.FromArgb(209, 213, 219);
        txtLog.Font = new Font("Consolas", 9f);
        txtLog.BorderStyle = BorderStyle.None;
        txtLog.ReadOnly = true;
        this.Controls.Add(txtLog);

        LoadStatsFromFile();
        Log("💡 Cash Mafia Auto-Quiz & Reward Bot initialized.");
        Log("⚡ Super Offer app installation removed as requested.");
        Log("🛡️ App restart bug fixed: Clean ad playback & rewarded claiming active.");
    }

    private void Log(string message)
    {
        if (txtLog.InvokeRequired)
        {
            txtLog.BeginInvoke(new Action(() => Log(message)));
            return;
        }

        string timestamp = DateTime.Now.ToString("HH:mm:ss");
        string line = string.Format("[{0}] {1}\n", timestamp, message);

        txtLog.AppendText(line);
        txtLog.SelectionStart = txtLog.Text.Length;
        txtLog.ScrollToCaret();

        try
        {
            string logFile = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "bot_run.log");
            File.AppendAllText(logFile, line);
        }
        catch { }
    }

    private void LoadStatsFromFile()
    {
        try
        {
            string statsPath = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "cashmafia_stats.json");
            if (File.Exists(statsPath))
            {
                string json = File.ReadAllText(statsPath);
                Match mDate = Regex.Match(json, @"""todayDate"":\s*""([^""]+)""");
                string today = DateTime.Now.ToString("yyyy-MM-dd");

                Match mTot = Regex.Match(json, @"""totalSolved"":\s*(\d+)");
                if (mTot.Success) questionsSolved = int.Parse(mTot.Groups[1].Value);

                Match mGems = Regex.Match(json, @"""gemsEarned"":\s*(\d+)");
                if (mGems.Success) gemsEarned = int.Parse(mGems.Groups[1].Value);

                Match mAds = Regex.Match(json, @"""adsClosed"":\s*(\d+)");
                if (mAds.Success) adsClosed = int.Parse(mAds.Groups[1].Value);

                if (mDate.Success && mDate.Groups[1].Value == today)
                {
                    Match mToday = Regex.Match(json, @"""todaySolved"":\s*(\d+)");
                    if (mToday.Success) todaySolved = int.Parse(mToday.Groups[1].Value);
                }
                else
                {
                    todaySolved = 0;
                }

                UpdateDashboard("None");
            }
        }
        catch { }
    }

    private void SaveStatsToFile()
    {
        try
        {
            string statsPath = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "cashmafia_stats.json");
            string json = string.Format(
                "{{\n  \"todayDate\": \"{0}\",\n  \"todaySolved\": {1},\n  \"totalSolved\": {2},\n  \"gemsEarned\": {3},\n  \"adsClosed\": {4},\n  \"lastUpdated\": \"{5}\"\n}}",
                DateTime.Now.ToString("yyyy-MM-dd"),
                todaySolved,
                questionsSolved,
                gemsEarned,
                adsClosed,
                DateTime.Now.ToString("s")
            );
            File.WriteAllText(statsPath, json);
        }
        catch { }
    }

    private void UpdateDashboard(string lastCaptchaDigit)
    {
        if (this.InvokeRequired)
        {
            this.BeginInvoke(new Action(() => UpdateDashboard(lastCaptchaDigit)));
            return;
        }

        lblTodaySolved.Text = string.Format("{0}\nToday Solved", todaySolved);
        lblGems.Text = string.Format("{0}\nGems Earned", gemsEarned);
        lblStats.Text = string.Format("{0}\nAds Watched", adsClosed);
        lblCaptcha.Text = string.Format("{0}\nLast Captcha", lastCaptchaDigit);
    }

    private void BtnToggle_Click(object sender, EventArgs e)
    {
        if (!isRunning)
        {
            StartBot();
        }
        else
        {
            StopBot();
        }
    }

    private void StartBot()
    {
        isRunning = true;
        botStartTime = DateTime.Now;
        lastProgressTime = DateTime.Now;

        btnToggle.Text = "⏹ STOP CASH MAFIA BOT";
        btnToggle.BackColor = Color.FromArgb(239, 68, 68); // Red

        lblStatus.Text = "🟢 RUNNING (Auto-Quiz + Reward Active)";
        lblStatus.ForeColor = Color.FromArgb(16, 185, 129);

        Log(">>> Starting Cash Mafia Bot...");

        botThread = new Thread(new ThreadStart(BotWorkerLoop));
        botThread.IsBackground = true;
        botThread.Start();
    }

    private void StopBot()
    {
        isRunning = false;

        btnToggle.Text = "▶ START CASH MAFIA BOT";
        btnToggle.BackColor = Color.FromArgb(16, 185, 129);

        lblStatus.Text = "🔴 STOPPED";
        lblStatus.ForeColor = Color.FromArgb(239, 68, 68);

        if (chkAutoMute.Checked)
        {
            SetAdMuted(false);
        }

        Log("<<< Bot stopped.");
    }

    private string RunAdb(string command)
    {
        try
        {
            ProcessStartInfo psi = new ProcessStartInfo();
            psi.FileName = adbExe;
            psi.Arguments = string.Format("-s {0} {1}", adbPort, command);
            psi.UseShellExecute = false;
            psi.RedirectStandardOutput = true;
            psi.RedirectStandardError = true;
            psi.CreateNoWindow = true;

            using (Process p = Process.Start(psi))
            {
                string output = p.StandardOutput.ReadToEnd();
                p.WaitForExit(6000);
                return output;
            }
        }
        catch (Exception ex)
        {
            return "ERR: " + ex.Message;
        }
    }

    private void AdbTap(int x, int y)
    {
        RunAdb(string.Format("shell input tap {0} {1}", x, y));
    }

    private void AdbKey(int keyCode)
    {
        RunAdb(string.Format("shell input keyevent {0}", keyCode));
    }

    private void SetAdMuted(bool mute)
    {
        if (mute)
        {
            RunAdb("shell cmd media_session volume --stream 3 --set 0");
            Log("🔇 Auto-Mute: BlueStacks media audio muted.");
        }
        else
        {
            RunAdb("shell cmd media_session volume --stream 3 --set 12");
            Log("🔊 Audio: BlueStacks sound restored.");
        }
    }

    private bool EnsureConnected()
    {
        string[] ports = new string[] { "127.0.0.1:5555", "127.0.0.1:5556", "emulator-5554" };
        foreach (string p in ports)
        {
            RunAdbDirect(string.Format("connect {0}", p));
            Thread.Sleep(300);
            string devList = RunAdbDirect("devices");
            if (devList.Contains(p) && devList.Contains("device"))
            {
                adbPort = p;
                return true;
            }
        }

        string rawDevs = RunAdbDirect("devices");
        Match m = Regex.Match(rawDevs, @"(127\.0\.0\.1:\d+|emulator-\d+)\s+device");
        if (m.Success)
        {
            adbPort = m.Groups[1].Value;
            return true;
        }
        return false;
    }

    private string RunAdbDirect(string arguments)
    {
        try
        {
            ProcessStartInfo psi = new ProcessStartInfo(adbExe, arguments);
            psi.UseShellExecute = false;
            psi.RedirectStandardOutput = true;
            psi.CreateNoWindow = true;
            using (Process p = Process.Start(psi))
            {
                string o = p.StandardOutput.ReadToEnd();
                p.WaitForExit(4000);
                return o;
            }
        }
        catch { return ""; }
    }

    private string GetCurrentFocus()
    {
        string dump = RunAdb("shell \"dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity'\"");
        Match m = Regex.Match(dump, @"ActivityRecord\{[^\r\n]+");
        if (m.Success) return m.Value;

        string winDump = RunAdb("shell \"dumpsys window | grep mCurrentFocus\"");
        Match wm = Regex.Match(winDump, @"mCurrentFocus=Window\{[^\r\n]+");
        if (wm.Success) return wm.Value;

        return "";
    }

    private bool CheckAndEscapePlayStore()
    {
        string focus = GetCurrentFocus();
        if (focus.Contains("com.android.vending") || focus.Contains("google.android.finsky") || focus.Contains("com.android.chrome"))
        {
            Log("⚠️ In-app Google Play overlay detected! Dismissing overlay...");

            // First check if the Google Play bottom sheet 'Close' button is present
            RunAdb("shell uiautomator dump --compressed /sdcard/ps_check.xml");
            string xml = RunAdb("shell cat /sdcard/ps_check.xml");

            Match mClose = Regex.Match(xml, @"<node [^>]*content-desc=""Close""[^>]*bounds=""\[(\d+),(\d+)\]\[(\d+),(\d+)\]""");
            if (mClose.Success)
            {
                int cx = (int.Parse(mClose.Groups[1].Value) + int.Parse(mClose.Groups[3].Value)) / 2;
                int cy = (int.Parse(mClose.Groups[2].Value) + int.Parse(mClose.Groups[4].Value)) / 2;
                Log(string.Format("🎯 Dismissing Play Store sheet via Close button at ({0}, {1})...", cx, cy));
                AdbTap(cx, cy);
                Thread.Sleep(1000);
                return true;
            }

            // Fallback for Play Store sheet close icon bounds
            AdbTap(858, 362);
            Thread.Sleep(800);

            string recheck = GetCurrentFocus();
            if (recheck.Contains("com.android.vending") || recheck.Contains("com.android.chrome"))
            {
                // Full screen app: send a single Back key
                AdbKey(4);
                Thread.Sleep(800);
            }
            return true;
        }
        return false;
    }

    private bool TryFindAndClickAdButton(string xml, int elapsedSeconds)
    {
        if (string.IsNullOrEmpty(xml)) return false;

        MatchCollection matches = Regex.Matches(xml, @"<node [^>]+>");
        foreach (Match m in matches)
        {
            string txt = m.Value;

            // Never click promotional labels, app links, countdown text, or Install buttons
            if (Regex.IsMatch(txt, @"(Ad \d of|Sponsored|Install|Download|Learn More|Google Play)", RegexOptions.IgnoreCase))
            {
                continue;
            }

            bool isMatch = Regex.IsMatch(txt, @"(close|skip|dismiss|next-button|mbridge_iv_close|close_button|btn_close|tt_video_ad_close|iv_close)", RegexOptions.IgnoreCase);

            Match bMatch = Regex.Match(txt, @"bounds=""\[(\d+),(\d+)\]\[(\d+),(\d+)\]""");
            if (bMatch.Success)
            {
                int x1 = int.Parse(bMatch.Groups[1].Value);
                int y1 = int.Parse(bMatch.Groups[2].Value);
                int x2 = int.Parse(bMatch.Groups[3].Value);
                int y2 = int.Parse(bMatch.Groups[4].Value);
                int w = x2 - x1;
                int h = y2 - y1;

                // Case A: Explicit close/skip keyword match
                if (isMatch && w >= 10 && w <= 380 && h >= 10 && h <= 380)
                {
                    int cx = (x1 + x2) / 2;
                    int cy = (y1 + y2) / 2;
                    Log(string.Format("🎯 Detected ad close/skip button at ({0}, {1})! Tapping...", cx, cy));
                    AdbTap(cx, cy);
                    return true;
                }

                // Case B: Top-right corner close/skip icon (ONLY after 25s elapsed!)
                // Prevents prematurely tapping AdChoices/Info/Mute icons
                if (!isMatch && elapsedSeconds >= 25 && w >= 15 && w <= 160 && h >= 15 && h <= 160 && (x1 >= 720 && y1 <= 180))
                {
                    if (!Regex.IsMatch(txt, @"text=""\d+""")) // not a countdown number
                    {
                        int cx = (x1 + x2) / 2;
                        int cy = (y1 + y2) / 2;
                        Log(string.Format("🎯 Detected top-right ad close icon at ({0}, {1})! Tapping...", cx, cy));
                        AdbTap(cx, cy);
                        return true;
                    }
                }

                // Case C: Top-left arrow skip icon (after 25s elapsed)
                if (!isMatch && elapsedSeconds >= 25 && w >= 20 && w <= 120 && h >= 20 && h <= 120 && (x1 <= 70 && y1 <= 120))
                {
                    int cx = (x1 + x2) / 2;
                    int cy = (y1 + y2) / 2;
                    Log(string.Format("🎯 Detected top-left ad skip arrow at ({0}, {1})! Tapping...", cx, cy));
                    AdbTap(cx, cy);
                    return true;
                }
            }
        }
        return false;
    }

    private void CloseAdActively()
    {
        Log("📺 [Ad Playing] Watching ad... (hands-off to ensure reward callback)");
        DateTime start = DateTime.Now;

        // Phase 1: Let the ad play for at least 15 seconds without interference
        while ((DateTime.Now - start).TotalSeconds < 15)
        {
            Thread.Sleep(1000);
            string focus = GetCurrentFocus();

            // If already back in Cash Mafia
            if (focus.Contains("MainActivity") && (DateTime.Now - start).TotalSeconds > 4)
            {
                RunAdb("shell uiautomator dump --compressed /sdcard/ad_check.xml");
                string chkXml = RunAdb("shell cat /sdcard/ad_check.xml");
                if (chkXml.Contains("Quiz") && Regex.IsMatch(chkXml, @"content-desc=""\d"""))
                {
                    Log("✅ [Quiz Screen] Already back in Quiz, proceeding!");
                    adsClosed++;
                    SaveStatsToFile();
                    UpdateDashboard(lastCaptcha);
                    return;
                }
            }

            // Dismiss any Google Play overlay that popped up without pressing Back
            CheckAndEscapePlayStore();
        }

        Log("🔍 [Ad Playing] Searching for Skip / Close button...");

        // Phase 2: Active ad monitoring loop (up to 45s total)
        while ((DateTime.Now - start).TotalSeconds < 45)
        {
            string focus = GetCurrentFocus();
            if (focus.Contains("MainActivity"))
            {
                Log("✅ [Ad Finished] Successfully back in Cash Mafia!");
                adsClosed++;
                SaveStatsToFile();
                UpdateDashboard(lastCaptcha);
                return;
            }

            if (CheckAndEscapePlayStore())
            {
                Thread.Sleep(800);
                continue;
            }

            RunAdb("shell rm -f /sdcard/ad.xml");
            RunAdb("shell uiautomator dump --compressed /sdcard/ad.xml");
            string xml = RunAdb("shell cat /sdcard/ad.xml");
            double elapsed = (DateTime.Now - start).TotalSeconds;
            bool tapped = TryFindAndClickAdButton(xml, (int)elapsed);

            if (tapped)
            {
                Thread.Sleep(1500);
                string reFocus = GetCurrentFocus();
                if (reFocus.Contains("MainActivity"))
                {
                    Log("✅ [Ad Closed] Successfully back in Cash Mafia!");
                    adsClosed++;
                    SaveStatsToFile();
                    UpdateDashboard(lastCaptcha);
                    return;
                }
            }
            else
            {
                // After 32 seconds, try gentle corner taps
                if (elapsed >= 32 && elapsed < 36)
                {
                    AdbTap(54, 54);   // Top-left skip arrow
                    AdbTap(856, 50);  // Top-right close X
                }
                else if (elapsed >= 36)
                {
                    // Single Back key after 36s (full ad playback complete)
                    Log("⏱️ [Ad Completed] Sending single Back key to return to Quiz...");
                    AdbKey(4);
                    Thread.Sleep(1200);
                    string reFocus = GetCurrentFocus();
                    if (reFocus.Contains("MainActivity"))
                    {
                        Log("✅ [Ad Finished] Successfully back in Cash Mafia!");
                        adsClosed++;
                        SaveStatsToFile();
                        UpdateDashboard(lastCaptcha);
                        return;
                    }
                }
            }

            Thread.Sleep(1000);
        }

        // Phase 3: Final fallback to return to Quiz without restarting MainActivity
        Log("⏱️ [Ad Timeout] Ad elapsed. Pressing Back to return to Quiz screen...");
        AdbKey(4);
        Thread.Sleep(1500);
    }

    private void RecoverFromFreeze()
    {
        Log("🚨 [WATCHDOG] Checking app status for recovery...");
        string focus = GetCurrentFocus();

        if (focus.ToLower().Contains("launcher"))
        {
            Log("📱 Launching Cash Mafia from Launcher...");
            RunAdb("shell monkey -p com.rayole.cashmafia2 -c android.intent.category.LAUNCHER 1");
            Thread.Sleep(3000);
            AdbTap(683, 1439);
            return;
        }

        // If inside Cash Mafia or ad overlay, press Back once
        AdbKey(4);
        Thread.Sleep(1000);

        // Tap Play Quiz if on Home screen
        AdbTap(683, 1439);
        Thread.Sleep(1500);
    }

    private class DigitItem
    {
        public string Digit;
        public int X;
    }

    private void BotWorkerLoop()
    {
        try
        {
            if (!EnsureConnected())
            {
                Log("❌ Could not connect to BlueStacks ADB. Is BlueStacks running?");
                StopBot();
                return;
            }

            Log(string.Format("🚀 Linked to BlueStacks on {0}!", adbPort));
            if (this.InvokeRequired)
            {
                this.BeginInvoke(new Action(() => {
                    lblPort.Text = string.Format("BlueStacks: {0} (Connected)", adbPort);
                    lblPort.ForeColor = Color.FromArgb(16, 185, 129);
                }));
            }

            // Optimize animators to prevent uiautomator idle timeouts
            RunAdb("shell settings put global window_animation_scale 0");
            RunAdb("shell settings put global transition_animation_scale 0");
            RunAdb("shell settings put global animator_duration_scale 0");

            if (chkAutoMute.Checked)
            {
                SetAdMuted(true);
            }

            int consecutiveFails = 0;

            while (isRunning)
            {
                // 1. Watchdog check for freeze (generous 90s to avoid interrupting valid ads)
                double idleSec = (DateTime.Now - lastProgressTime).TotalSeconds;
                if (idleSec > 90)
                {
                    RecoverFromFreeze();
                    lastProgressTime = DateTime.Now;
                    continue;
                }

                // 2. Watchdog check for launcher crash
                string focus = GetCurrentFocus();
                if (focus.ToLower().Contains("launcher") && !focus.Contains("com.rayole.cashmafia"))
                {
                    Log("⚠️ App crash detected (Launcher active). Relaunching Cash Mafia...");
                    RecoverFromFreeze();
                    lastProgressTime = DateTime.Now;
                    continue;
                }

                // 3. Dismiss Play Store overlay if present
                CheckAndEscapePlayStore();

                // 4. Ad Activity in foreground
                focus = GetCurrentFocus();
                if (!string.IsNullOrEmpty(focus) && !focus.Contains("MainActivity") && !focus.ToLower().Contains("launcher"))
                {
                    CloseAdActively();
                    lastProgressTime = DateTime.Now;
                    continue;
                }

                // 5. Dump UI XML
                RunAdb("shell uiautomator dump --compressed /sdcard/cm_app.xml");
                string xml = RunAdb("shell cat /sdcard/cm_app.xml");

                // CASE 1: Exit confirmation modals
                if (xml.Contains("Continue to collect points"))
                {
                    Log("[Dialog] Dismissing Exit dialog (tapping 'Continue to collect points')...");
                    AdbTap(564, 818);
                    Thread.Sleep(800);
                    continue;
                }

                if (xml.Contains("Exit the quiz?"))
                {
                    Log("[Dialog] Dismissing Exit quiz dialog (tapping Cancel)...");
                    AdbTap(631, 1454);
                    Thread.Sleep(800);
                    continue;
                }

                // CASE 2: Daily Streak modals
                if (xml.Contains("Daily Streak"))
                {
                    Log("[Streak] Dismissing Daily Streak screen...");
                    AdbKey(4);
                    Thread.Sleep(1000);
                    continue;
                }

                if (xml.Contains("Start Your Streak Journey") || xml.Contains("Let's go") || xml.Contains("Your Streak is broken!"))
                {
                    Log("[Streak] Dismissing Streak Journey modal...");
                    AdbTap(450, 1537);
                    Thread.Sleep(1000);
                    continue;
                }

                // CASE 3: Super Offers / Special Offers Screen - Dismiss without installing!
                if (xml.Contains("Super Offers") && (xml.Contains("Watch a video to unlock") || xml.Contains("Download the advertised app") || xml.Contains("Verify Installation") || xml.Contains("Use the app") || xml.Contains("Special Offer") || xml.Contains("Jackpot Offer")))
                {
                    lastProgressTime = DateTime.Now;
                    Log("ℹ️ Super Offers screen detected. Dismissing to stay in Quiz mode...");
                    AdbKey(4);
                    Thread.Sleep(1000);
                    continue;
                }

                // CASE 4: Home Screen ("Welcome Back" or "Play Quiz")
                if (xml.Contains("Welcome Back") || (xml.Contains("Play Quiz") && xml.Contains("Super Offers")) || xml.Contains("Basket Dunk"))
                {
                    lastProgressTime = DateTime.Now;
                    Log("🏠 [HOME SCREEN] Entering 'Play Quiz'...");

                    Match mQuizNode = Regex.Match(xml, @"<node [^>]*content-desc=""Play Quiz[^""]*""[^>]*bounds=""\[(\d+),(\d+)\]\[(\d+),(\d+)\]""");
                    if (mQuizNode.Success)
                    {
                        int qx = (int.Parse(mQuizNode.Groups[1].Value) + int.Parse(mQuizNode.Groups[3].Value)) / 2;
                        int qy = (int.Parse(mQuizNode.Groups[2].Value) + Math.Min(int.Parse(mQuizNode.Groups[4].Value), 1560)) / 2;
                        AdbTap(qx, qy);
                    }
                    else
                    {
                        AdbTap(683, 1439);
                    }
                    Thread.Sleep(2000);
                    continue;
                }

                // CASE 5: Congratulations / Claim Reward modal
                if (xml.Contains("Congratulations") || xml.Contains("Redeem") || xml.Contains("Claim Reward") || (xml.Contains("Play Again") && !xml.Contains("Welcome Back")) || xml.Contains("Watch an ad and redeem"))
                {
                    lastProgressTime = DateTime.Now;

                    // Find button dynamically from XML bounds (must be clickable="true")
                    Match mClaim = Regex.Match(xml, @"<node [^>]*(content-desc|text)=""[^""]*(Redeem|Claim|Play Again)[^""]*""[^>]*clickable=""true""[^>]*bounds=""\[(\d+),(\d+)\]\[(\d+),(\d+)\]""");
                    if (mClaim.Success)
                    {
                        int cx = (int.Parse(mClaim.Groups[3].Value) + int.Parse(mClaim.Groups[5].Value)) / 2;
                        int cy = (int.Parse(mClaim.Groups[4].Value) + int.Parse(mClaim.Groups[6].Value)) / 2;
                        Log(string.Format("🎁 [CLAIM REWARD] Tapping button at ({0}, {1})...", cx, cy));
                        AdbTap(cx, cy);
                    }
                    else
                    {
                        Log("🎁 [CLAIM REWARD] Tapping button at fallback (450, 1296)...");
                        AdbTap(450, 1296);
                    }

                    gemsEarned++;
                    SaveStatsToFile();
                    UpdateDashboard(lastCaptcha);

                    Thread.Sleep(2000);
                    CloseAdActively();
                    lastProgressTime = DateTime.Now;
                    Thread.Sleep(1000);
                    continue;
                }

                // CASE 6: Quiz Screen (Extract captcha digits)
                List<DigitItem> digits = new List<DigitItem>();
                MatchCollection dMatches = Regex.Matches(xml, @"<node [^>]*content-desc=""(\d)""[^>]*bounds=""\[(\d+),(\d+)\]\[(\d+),(\d+)\]""");
                foreach (Match dm in dMatches)
                {
                    string d = dm.Groups[1].Value;
                    int x1 = int.Parse(dm.Groups[2].Value);
                    int y1 = int.Parse(dm.Groups[3].Value);
                    int y2 = int.Parse(dm.Groups[5].Value);
                    if (y1 >= 650 && y2 <= 850)
                    {
                        digits.Add(new DigitItem { Digit = d, X = x1 });
                    }
                }

                if (digits.Count >= 3)
                {
                    consecutiveFails = 0;
                    digits = digits.OrderBy(o => o.X).ToList();
                    string targetNumber = string.Join("", digits.Select(o => o.Digit).ToArray());
                    lastCaptcha = targetNumber;

                    // Read Gems in top right
                    string gemsCount = "0";
                    MatchCollection gmMatches = Regex.Matches(xml, @"<node [^>]*content-desc=""(\d+)""[^>]*bounds=""\[(\d+),(\d+)\]\[(\d+),(\d+)\]""");
                    foreach (Match gm in gmMatches)
                    {
                        int gx1 = int.Parse(gm.Groups[2].Value);
                        int gy1 = int.Parse(gm.Groups[3].Value);
                        if (gx1 >= 740 && gy1 <= 120)
                        {
                            gemsCount = gm.Groups[1].Value;
                            break;
                        }
                    }

                    Log(string.Format("📝 [QUIZ] Captcha: '{0}' | App Gems: {1} 💎", targetNumber, gemsCount));

                    // Match option button
                    string optPattern = string.Format(@"<node [^>]*content-desc=""{0}""[^>]*clickable=""true""[^>]*bounds=""\[(\d+),(\d+)\]\[(\d+),(\d+)\]""", targetNumber);
                    Match optMatch = Regex.Match(xml, optPattern);
                    if (optMatch.Success)
                    {
                        int ox1 = int.Parse(optMatch.Groups[1].Value);
                        int oy1 = int.Parse(optMatch.Groups[2].Value);
                        int ox2 = int.Parse(optMatch.Groups[3].Value);
                        int oy2 = int.Parse(optMatch.Groups[4].Value);
                        int ocx = (ox1 + ox2) / 2;
                        int ocy = (oy1 + oy2) / 2;

                        Log(string.Format("👉 [ANSWER] Tapping '{0}' at ({1}, {2})...", targetNumber, ocx, ocy));
                        AdbTap(ocx, ocy);

                        questionsSolved++;
                        todaySolved++;
                        lastProgressTime = DateTime.Now;
                        SaveStatsToFile();
                        UpdateDashboard(targetNumber);

                        Thread.Sleep(1500);
                        continue;
                    }
                    else
                    {
                        Log(string.Format("⚠️ Target option '{0}' not found in options, retrying...", targetNumber));
                    }
                }
                else
                {
                    consecutiveFails++;
                    if (xml.Contains("Congratulations") || xml.Contains("Redeem") || xml.Contains("Claim"))
                    {
                        Log("🎁 [Fallback] Congratulations/Claim detected, tapping...");
                        AdbTap(450, 1296);
                        Thread.Sleep(1500);
                        CloseAdActively();
                        consecutiveFails = 0;
                        lastProgressTime = DateTime.Now;
                    }
                    else if (consecutiveFails >= 5)
                    {
                        Log("⚠️ Waiting for quiz digits or screen transition...");
                        consecutiveFails = 0;
                    }
                }

                Thread.Sleep(800);
            }
        }
        catch (Exception ex)
        {
            Log("ERROR in bot loop: " + ex.Message);
        }
    }

    [STAThread]
    public static void Main()
    {
        try
        {
            AppDomain.CurrentDomain.UnhandledException += (s, e) => {
                try { File.AppendAllText(Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "crash.log"), "[AppDomain] " + e.ExceptionObject.ToString() + "\n"); } catch {}
            };
            Application.ThreadException += (s, e) => {
                try { File.AppendAllText(Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "crash.log"), "[UIThread] " + e.Exception.ToString() + "\n"); } catch {}
            };
            Application.SetUnhandledExceptionMode(UnhandledExceptionMode.CatchException);

            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            Application.Run(new CashMafiaApp());
        }
        catch (Exception ex)
        {
            try
            {
                File.WriteAllText(Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "crash.log"), ex.ToString());
            }
            catch { }
        }
    }
}
