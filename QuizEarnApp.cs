using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Sockets;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Windows.Forms;
using Microsoft.Win32;

[assembly: AssemblyTitle("Quiz & Earn Automation Suite")]
[assembly: AssemblyDescription("Desktop controller and multi-instance watchdog for Quiz & Earn & CashMafia.")]
[assembly: AssemblyConfiguration("Release")]
[assembly: AssemblyCompany("Quiz & Earn Tools")]
[assembly: AssemblyProduct("Quiz & Earn Auto-Bot")]
[assembly: AssemblyCopyright("Copyright (c) 2026 Quiz & Earn Tools")]
[assembly: AssemblyVersion("2.9.0.0")]
[assembly: AssemblyFileVersion("2.9.0.0")]

public class QuizEarnApp : Form
{
    private const string PKG_QUIZ_EARN = "com.quiz.earn.money.game";
    private const string PKG_CASH_MAFIA = "com.rayole.cashmafia2";
    private const string PKG_BOT_ASSISTANT = "com.quizearn.assistant";
    private const string A11Y_COMPONENT = "com.quizearn.assistant/com.quizearn.assistant.QuizEarnAccessibilityService";
    private const string OVERLAY_COMPONENT = "com.quizearn.assistant/.FloatingOverlayService";

    [DllImport("kernel32.dll", CharSet = CharSet.Auto, SetLastError = true)]
    private static extern uint SetThreadExecutionState(uint esFlags);
    private const uint ES_CONTINUOUS = 0x80000000;
    private const uint ES_SYSTEM_REQUIRED = 0x00000001;
    private const uint ES_DISPLAY_REQUIRED = 0x00000002;

    [DllImport("user32.dll")]
    private static extern bool SetForegroundWindow(IntPtr hWnd);

    [DllImport("user32.dll")]
    private static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);

    [DllImport("user32.dll")]
    private static extern bool GetWindowRect(IntPtr hWnd, out RECT lpRect);

    [DllImport("user32.dll")]
    private static extern bool IsWindowVisible(IntPtr hWnd);

    [DllImport("user32.dll")]
    private static extern bool EnumWindows(EnumWindowsProc lpEnumFunc, IntPtr lParam);
    private delegate bool EnumWindowsProc(IntPtr hWnd, IntPtr lParam);

    [DllImport("user32.dll")]
    private static extern int GetWindowThreadProcessId(IntPtr hWnd, out int processId);

    [DllImport("user32.dll", CharSet = CharSet.Auto)]
    private static extern int GetWindowText(IntPtr hWnd, StringBuilder lpString, int nMaxCount);

    [DllImport("dwmapi.dll")]
    private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attr, ref int attrValue, int attrSize);
    private const int DWMWA_USE_IMMERSIVE_DARK_MODE_BEFORE_20H1 = 19;
    private const int DWMWA_USE_IMMERSIVE_DARK_MODE = 20;
    private const int DWMWA_BORDER_COLOR = 34;
    private const int DWMWA_CAPTION_COLOR = 35;
    private const int DWMWA_TEXT_COLOR = 36;

    [StructLayout(LayoutKind.Sequential)]
    private struct RECT { public int Left, Top, Right, Bottom; }

    // Synchronized state & I/O locks (thread-safe BeginInvoke UI updates)
    private readonly object statsLock = new object();
    private readonly object logLock = new object();

    // UI Elements
    private Label lblTitle, lblStatus, lblPort;
    private Button btnToggle, btnWatchdog, btnRestartApp, btnResetAdId, btnSendLastSS;
    private Label lblTodaySolved, lblGems, lblCaptcha, lblStats;
    private RichTextBox txtLog;
    private ComboBox cmbTargetApp;

    // Booster & Auto-Claimer
    private volatile bool isBoosting = false;
    private Thread boosterThread = null;
    private int boostSecondsRemaining = 0;
    private string boostTargetPackage = "";
    private Panel pnlBooster;
    private Label lblBoostStatus;
    private Button btnBoost1m, btnBoost5m, btnBoost12m, btnBoostStop;
    private volatile bool isAutoOffering = false;
    private Thread autoOfferThread = null;
    private Button btnAutoOffer, btnAutoOfferStop;

    // Bot Threading & ADB
    private Thread botThread;
    private volatile bool isRunning = false;
    private volatile bool isAdbConnected = false;
    private volatile bool autoMuteEnabled = true;
    private volatile bool turboSpeedEnabled = true;
    private volatile bool autoRecoverBlueStacks = true;
    private volatile string targetAppPackage = PKG_QUIZ_EARN;
    private string adbPort = "";
    private string adbExe = "adb";
    private int devWidth = 900;
    private int devHeight = 1600;

    // Statistics & Collect-Gem Guard
    private int questionsSolved = 0, todaySolved = 0, gemsEarned = 0, currentOfferGems = 0;
    private int totalGems = 0, adsClosed = 0;
    private int collectGemNoAdCount = 0;
    private DateTime adReloadCooldownUntil = DateTime.MinValue;
    private string lastCaptcha = "None";
    private string lastSolvedTarget = "";
    private DateTime lastSolveTime = DateTime.MinValue;
    private DateTime lastProgressTime = DateTime.Now;

    // Data & Analytics
    private int todayCoinsClaimed = 0, totalCoinsClaimed = 0;
    private int today25GemsCount = 0, total25GemsCount = 0;
    private int todayOffersClaimed = 0, totalOffersClaimed = 0;
    private bool hasCountedThis25GemCycle = false;
    private bool isPending25thGemStop = false;
    private List<string> claimsHistory = new List<string>();

    // Cloud Updater
    private const string APP_VERSION = "2.9";
    private const int APP_VERSION_CODE = 290;
    private string updateFeedUrl = "https://raw.githubusercontent.com/THESHMIL0/QuizEarnBot/main/version.json";
    private string remoteVersion = "", remoteChangelog = "", remoteDownloadUrl = "";
    private int remoteVersionCode = 0;
    private bool autoCheckUpdates = true;

    // Navigation Views
    private Panel pnlHomeView, pnlDataView, pnlSettingsView, pnlBottomNav;
    private Button btnNavHome, btnNavData, btnNavSettings;
    private Label lblCurrentVersion, lblUpdateStatus;
    private Button btnCheckUpdate;
    private DarkToggleSwitch chkAutoCheckUpdates, chkSettingsAlwaysOnTop, chkSettingsAutoMute, chkSettingsTurbo, chkSettingsAutoRecoverBst;
    private Label lblDataCoinsVal, lblData25GemsVal, lblData25GemsSub, lblDataOffersVal, lblDataQuizzesVal;
    private Label lblLifetimeCoins, lblLifetime25Gems, lblLifetimeQuizzes, lblDataDate;
    private RichTextBox txtClaimsLog;
    private Button btnDataRefresh, btnDataResetToday;

    private string GetTargetPackage()
    {
        return string.IsNullOrEmpty(targetAppPackage) ? PKG_QUIZ_EARN : targetAppPackage;
    }

    private string GetTargetAppLabel()
    {
        return GetTargetPackage() == PKG_CASH_MAFIA ? "CashMafia" : "Quiz & Earn";
    }

    private bool IsKnownQuizApp(string text)
    {
        if (string.IsNullOrEmpty(text)) return false;
        return text.Contains(PKG_QUIZ_EARN) || text.Contains(PKG_CASH_MAFIA);
    }

    public static void ApplyDwmDarkTo(IntPtr hwnd)
    {
        try
        {
            int dark = 1, black = 0x00000000, textGray = 0x00909095;
            DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, ref dark, sizeof(int));
            DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE_BEFORE_20H1, ref dark, sizeof(int));
            DwmSetWindowAttribute(hwnd, DWMWA_CAPTION_COLOR, ref black, sizeof(int));
            DwmSetWindowAttribute(hwnd, DWMWA_BORDER_COLOR, ref black, sizeof(int));
            DwmSetWindowAttribute(hwnd, DWMWA_TEXT_COLOR, ref textGray, sizeof(int));
        }
        catch { }
    }

    protected override void OnHandleCreated(EventArgs e)
    {
        base.OnHandleCreated(e);
        ApplyDwmDarkTo(this.Handle);
    }

    protected override void OnFormClosing(FormClosingEventArgs e)
    {
        isRunning = false;
        isAutoOffering = false;
        isBoosting = false;
        AllowSleep();
        base.OnFormClosing(e);
    }

    public QuizEarnApp()
    {
        this.AutoScaleDimensions = new SizeF(96F, 96F);
        this.AutoScaleMode = AutoScaleMode.Dpi;
        DetectAdbExecutable();
        InitializeComponent();
        PreventSleep();
    }

    private void PreventSleep()
    {
        try { SetThreadExecutionState(ES_CONTINUOUS | ES_SYSTEM_REQUIRED | ES_DISPLAY_REQUIRED); } catch { }
    }

    private void AllowSleep()
    {
        try { SetThreadExecutionState(ES_CONTINUOUS); } catch { }
    }

    private void SafeBeginInvoke(Action action)
    {
        try
        {
            if (!this.IsDisposed && this.IsHandleCreated)
            {
                if (this.InvokeRequired) this.BeginInvoke(action);
                else action();
            }
        }
        catch { }
    }

    private void DetectAdbExecutable()
    {
        string localAdb = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "adb.exe");
        if (File.Exists(localAdb)) { adbExe = localAdb; return; }

        try
        {
            string appDataDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "QuizEarnBot", "bin");
            if (!Directory.Exists(appDataDir)) Directory.CreateDirectory(appDataDir);
            string appDataAdb = Path.Combine(appDataDir, "adb.exe");
            Assembly asm = Assembly.GetExecutingAssembly();
            foreach (string fileName in new string[] { "adb.exe", "AdbWinApi.dll", "AdbWinUsbApi.dll" })
            {
                string targetPath = Path.Combine(appDataDir, fileName);
                if (!File.Exists(targetPath) || new FileInfo(targetPath).Length == 0)
                {
                    using (Stream resStream = asm.GetManifestResourceStream(fileName))
                    {
                        if (resStream != null)
                        {
                            using (FileStream fs = new FileStream(targetPath, FileMode.Create, FileAccess.Write))
                            {
                                byte[] buffer = new byte[65536];
                                int read;
                                while ((read = resStream.Read(buffer, 0, buffer.Length)) > 0) fs.Write(buffer, 0, read);
                            }
                        }
                    }
                }
            }
            if (File.Exists(appDataAdb)) { adbExe = appDataAdb; return; }
        }
        catch { }

        string[] candidates = new string[]
        {
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), @"Android\Sdk\platform-tools\adb.exe"),
            @"C:\Program Files\BlueStacks_nxt\HD-Adb.exe",
            @"C:\Program Files (x86)\BlueStacks_nxt\HD-Adb.exe",
            @"C:\Program Files\BlueStacks\HD-Adb.exe",
            @"C:\LDPlayer\LDPlayer9\adb.exe",
            @"D:\LDPlayer\LDPlayer9\adb.exe",
            @"C:\Program Files\Nox\bin\nox_adb.exe",
            @"C:\Program Files\Microvirt\MEmu\adb.exe",
            "adb"
        };
        foreach (string c in candidates)
        {
            if (c == "adb" || File.Exists(c)) { adbExe = c; break; }
        }
    }

    private void InitializeComponent()
    {
        this.Text = "Quiz & Earn + CashMafia Auto-Bot v" + APP_VERSION;
        try
        {
            string icoPath = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "app.ico");
            if (File.Exists(icoPath)) this.Icon = new Icon(icoPath);
            else this.Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath);
        }
        catch { }

        this.ClientSize = new Size(560, 860);
        this.MinimumSize = new Size(540, 740);
        this.StartPosition = FormStartPosition.CenterScreen;
        this.BackColor = Color.Black;
        this.ForeColor = Color.FromArgb(165, 165, 175);
        this.Font = new Font("Segoe UI", 9.5f, FontStyle.Regular);
        this.FormBorderStyle = FormBorderStyle.Sizable;
        this.MaximizeBox = true;

        // Bottom Navigation Bar (Docked Bottom for Responsive Resize / DPI)
        pnlBottomNav = new Panel();
        pnlBottomNav.Dock = DockStyle.Bottom;
        pnlBottomNav.Height = 56;
        pnlBottomNav.BackColor = Color.FromArgb(6, 6, 8);
        pnlBottomNav.Padding = new Padding(12, 8, 12, 8);
        pnlBottomNav.Paint += delegate(object s, PaintEventArgs pe)
        {
            pe.Graphics.DrawLine(new Pen(Color.FromArgb(26, 26, 30), 1), 0, 0, pnlBottomNav.Width, 0);
        };
        this.Controls.Add(pnlBottomNav);

        TableLayoutPanel navGrid = new TableLayoutPanel();
        navGrid.Dock = DockStyle.Fill;
        navGrid.ColumnCount = 3;
        navGrid.RowCount = 1;
        navGrid.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 33.33f));
        navGrid.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 33.33f));
        navGrid.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 33.34f));
        pnlBottomNav.Controls.Add(navGrid);

        btnNavHome = CreateNavButton("HOME");
        btnNavHome.Click += delegate { SwitchToHomeView(); };
        btnNavData = CreateNavButton("DATA");
        btnNavData.Click += delegate { SwitchToDataView(); };
        btnNavSettings = CreateNavButton("SETTINGS");
        btnNavSettings.Click += delegate { SwitchToSettingsView(); };

        navGrid.Controls.Add(btnNavHome, 0, 0);
        navGrid.Controls.Add(btnNavData, 1, 0);
        navGrid.Controls.Add(btnNavSettings, 2, 0);

        // 1. HOME VIEW
        pnlHomeView = new Panel();
        pnlHomeView.Dock = DockStyle.Fill;
        pnlHomeView.BackColor = Color.Black;
        pnlHomeView.Padding = new Padding(18, 14, 18, 10);
        this.Controls.Add(pnlHomeView);

        // Top Bar
        Panel topRow = new Panel();
        topRow.Dock = DockStyle.Top;
        topRow.Height = 64;
        pnlHomeView.Controls.Add(topRow);

        lblTitle = new Label();
        lblTitle.Text = "QuizEarn & CashMafia Bot v" + APP_VERSION;
        lblTitle.Font = new Font("Segoe UI", 14.5f, FontStyle.Bold);
        lblTitle.ForeColor = Color.FromArgb(225, 225, 232);
        lblTitle.Location = new Point(0, 2);
        lblTitle.AutoSize = true;
        topRow.Controls.Add(lblTitle);

        lblStatus = new Label();
        lblStatus.Text = "READY - Waiting for ADB";
        lblStatus.Font = new Font("Segoe UI", 8.5f, FontStyle.Bold);
        lblStatus.ForeColor = Color.FromArgb(130, 130, 140);
        lblStatus.Location = new Point(2, 30);
        lblStatus.AutoSize = true;
        topRow.Controls.Add(lblStatus);

        lblPort = new Label();
        lblPort.Text = "ADB: Auto-Detecting...";
        lblPort.Font = new Font("Consolas", 8f);
        lblPort.ForeColor = Color.FromArgb(100, 105, 115);
        lblPort.Location = new Point(2, 47);
        lblPort.AutoSize = true;
        topRow.Controls.Add(lblPort);

        btnWatchdog = new Button();
        btnWatchdog.Anchor = AnchorStyles.Top | AnchorStyles.Right;
        btnWatchdog.Size = new Size(168, 34);
        btnWatchdog.Location = new Point(topRow.Width - 168, 6);
        btnWatchdog.Font = new Font("Segoe UI", 8.5f, FontStyle.Bold);
        btnWatchdog.FlatStyle = FlatStyle.Flat;
        btnWatchdog.Cursor = Cursors.Hand;
        btnWatchdog.Click += new EventHandler(BtnWatchdog_Click);
        topRow.Controls.Add(btnWatchdog);

        // Target App + Main Controls Stack
        Panel controlsBlock = new Panel();
        controlsBlock.Dock = DockStyle.Top;
        controlsBlock.Height = 296;
        pnlHomeView.Controls.Add(controlsBlock);
        controlsBlock.BringToFront();

        // Target App Selector Row
        Label lblTarget = new Label();
        lblTarget.Text = "Target App:";
        lblTarget.Font = new Font("Segoe UI", 9f, FontStyle.Bold);
        lblTarget.ForeColor = Color.FromArgb(150, 150, 160);
        lblTarget.Location = new Point(0, 6);
        lblTarget.AutoSize = true;
        controlsBlock.Controls.Add(lblTarget);

        cmbTargetApp = new ComboBox();
        cmbTargetApp.DropDownStyle = ComboBoxStyle.DropDownList;
        cmbTargetApp.BackColor = Color.FromArgb(16, 16, 20);
        cmbTargetApp.ForeColor = Color.White;
        cmbTargetApp.Font = new Font("Segoe UI", 9f, FontStyle.Bold);
        cmbTargetApp.Items.Add("Quiz & Earn (com.quiz.earn.money.game)");
        cmbTargetApp.Items.Add("CashMafia (com.rayole.cashmafia2)");
        cmbTargetApp.Location = new Point(90, 2);
        cmbTargetApp.Width = 428;
        cmbTargetApp.Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right;
        cmbTargetApp.SelectedIndexChanged += delegate
        {
            targetAppPackage = cmbTargetApp.SelectedIndex == 1 ? PKG_CASH_MAFIA : PKG_QUIZ_EARN;
            SaveStatsToFile();
            Log(string.Format("[Target] Active target switched to {0} ({1}).", GetTargetAppLabel(), targetAppPackage));
        };
        controlsBlock.Controls.Add(cmbTargetApp);

        btnToggle = new Button();
        btnToggle.Text = "[ > ]  START BOT";
        btnToggle.Font = new Font("Segoe UI", 11.5f, FontStyle.Bold);
        btnToggle.ForeColor = Color.White;
        btnToggle.BackColor = Color.FromArgb(14, 48, 34);
        btnToggle.FlatStyle = FlatStyle.Flat;
        btnToggle.FlatAppearance.BorderColor = Color.FromArgb(24, 110, 74);
        btnToggle.FlatAppearance.BorderSize = 1;
        btnToggle.Location = new Point(0, 36);
        btnToggle.Size = new Size(518, 46);
        btnToggle.Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right;
        btnToggle.Cursor = Cursors.Hand;
        btnToggle.Click += new EventHandler(BtnToggle_Click);
        controlsBlock.Controls.Add(btnToggle);

        TableLayoutPanel utilityRow = new TableLayoutPanel();
        utilityRow.Location = new Point(0, 90);
        utilityRow.Size = new Size(518, 36);
        utilityRow.Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right;
        utilityRow.ColumnCount = 3;
        utilityRow.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 33.33f));
        utilityRow.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 33.33f));
        utilityRow.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 33.34f));
        controlsBlock.Controls.Add(utilityRow);

        btnRestartApp = CreateFlatButton("Restart App", Color.FromArgb(14, 14, 18), Color.FromArgb(180, 180, 190), Color.FromArgb(32, 32, 38));
        btnRestartApp.Click += new EventHandler(BtnRestartApp_Click);
        btnResetAdId = CreateFlatButton("Reset Ad ID", Color.FromArgb(14, 14, 18), Color.FromArgb(180, 180, 190), Color.FromArgb(32, 32, 38));
        btnResetAdId.Click += new EventHandler(BtnResetAdId_Click);
        btnSendLastSS = CreateFlatButton("Auto SS to Gallery", Color.FromArgb(14, 26, 42), Color.FromArgb(95, 185, 255), Color.FromArgb(42, 90, 150));
        btnSendLastSS.Click += new EventHandler(BtnSendLastSS_Click);

        utilityRow.Controls.Add(btnRestartApp, 0, 0);
        utilityRow.Controls.Add(btnResetAdId, 1, 0);
        utilityRow.Controls.Add(btnSendLastSS, 2, 0);

        // Offer Claimer + Booster Panel
        pnlBooster = new Panel();
        pnlBooster.Location = new Point(0, 134);
        pnlBooster.Size = new Size(518, 86);
        pnlBooster.Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right;
        pnlBooster.BackColor = Color.FromArgb(8, 8, 10);
        pnlBooster.Paint += delegate(object s, PaintEventArgs pe)
        {
            ControlPaint.DrawBorder(pe.Graphics, pnlBooster.ClientRectangle, Color.FromArgb(26, 26, 32), ButtonBorderStyle.Solid);
        };
        controlsBlock.Controls.Add(pnlBooster);

        btnAutoOffer = new Button();
        btnAutoOffer.Text = "[ AUTO-CLAIM ALL OFFERS (1-Click) ]";
        btnAutoOffer.Font = new Font("Segoe UI", 9.5f, FontStyle.Bold);
        btnAutoOffer.ForeColor = Color.White;
        btnAutoOffer.BackColor = Color.FromArgb(16, 16, 20);
        btnAutoOffer.FlatStyle = FlatStyle.Flat;
        btnAutoOffer.FlatAppearance.BorderColor = Color.FromArgb(36, 36, 44);
        btnAutoOffer.FlatAppearance.BorderSize = 1;
        btnAutoOffer.Location = new Point(8, 8);
        btnAutoOffer.Size = new Size(412, 34);
        btnAutoOffer.Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right;
        btnAutoOffer.Cursor = Cursors.Hand;
        btnAutoOffer.Click += new EventHandler(BtnAutoOffer_Click);
        pnlBooster.Controls.Add(btnAutoOffer);

        btnAutoOfferStop = new Button();
        btnAutoOfferStop.Text = "Stop";
        btnAutoOfferStop.Font = new Font("Segoe UI", 9f, FontStyle.Bold);
        btnAutoOfferStop.ForeColor = Color.FromArgb(200, 140, 140);
        btnAutoOfferStop.BackColor = Color.FromArgb(24, 12, 14);
        btnAutoOfferStop.FlatStyle = FlatStyle.Flat;
        btnAutoOfferStop.FlatAppearance.BorderColor = Color.FromArgb(55, 24, 28);
        btnAutoOfferStop.FlatAppearance.BorderSize = 1;
        btnAutoOfferStop.Location = new Point(426, 8);
        btnAutoOfferStop.Size = new Size(84, 34);
        btnAutoOfferStop.Anchor = AnchorStyles.Top | AnchorStyles.Right;
        btnAutoOfferStop.Cursor = Cursors.Hand;
        btnAutoOfferStop.Click += new EventHandler(BtnAutoOfferStop_Click);
        pnlBooster.Controls.Add(btnAutoOfferStop);

        btnBoost1m = CreateBoostButton("+1m", 8);
        btnBoost1m.Click += delegate { StartUsageBooster(1); };
        btnBoost5m = CreateBoostButton("+5m", 62);
        btnBoost5m.Click += delegate { StartUsageBooster(5); };
        btnBoost12m = CreateBoostButton("+12m", 116);
        btnBoost12m.Click += delegate { StartUsageBooster(12); };

        btnBoostStop = new Button();
        btnBoostStop.Text = "Stop Boost";
        btnBoostStop.Font = new Font("Segoe UI", 8f, FontStyle.Bold);
        btnBoostStop.ForeColor = Color.FromArgb(190, 190, 200);
        btnBoostStop.BackColor = Color.FromArgb(14, 14, 18);
        btnBoostStop.FlatStyle = FlatStyle.Flat;
        btnBoostStop.FlatAppearance.BorderColor = Color.FromArgb(30, 30, 36);
        btnBoostStop.Location = new Point(176, 48);
        btnBoostStop.Size = new Size(76, 30);
        btnBoostStop.Cursor = Cursors.Hand;
        btnBoostStop.Click += new EventHandler(BtnBoostStop_Click);
        pnlBooster.Controls.Add(btnBoostStop);

        lblBoostStatus = new Label();
        lblBoostStatus.Text = "Idle (Auto-Claim or Manual Booster)";
        lblBoostStatus.Font = new Font("Segoe UI", 8f);
        lblBoostStatus.ForeColor = Color.FromArgb(135, 135, 145);
        lblBoostStatus.Location = new Point(258, 55);
        lblBoostStatus.Size = new Size(252, 20);
        lblBoostStatus.Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right;
        pnlBooster.Controls.Add(lblBoostStatus);

        // Live 4-Metric Bar
        TableLayoutPanel statsPanel = new TableLayoutPanel();
        statsPanel.Location = new Point(0, 226);
        statsPanel.Size = new Size(518, 64);
        statsPanel.Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right;
        statsPanel.BackColor = Color.FromArgb(8, 8, 10);
        statsPanel.ColumnCount = 4;
        for (int i = 0; i < 4; i++) statsPanel.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 25f));
        statsPanel.Paint += delegate(object s, PaintEventArgs pe)
        {
            ControlPaint.DrawBorder(pe.Graphics, statsPanel.ClientRectangle, Color.FromArgb(24, 24, 28), ButtonBorderStyle.Solid);
        };
        controlsBlock.Controls.Add(statsPanel);

        lblTodaySolved = CreateMetricLabel("0\nSolved");
        lblGems = CreateMetricLabel("0/25\nOffer Gems");
        lblCaptcha = CreateMetricLabel("None\nLast Captcha");
        lblStats = CreateMetricLabel("0\nAds Closed");
        statsPanel.Controls.Add(lblTodaySolved, 0, 0);
        statsPanel.Controls.Add(lblGems, 1, 0);
        statsPanel.Controls.Add(lblCaptcha, 2, 0);
        statsPanel.Controls.Add(lblStats, 3, 0);

        // Log Header + Scrollable Log Console
        Panel logArea = new Panel();
        logArea.Dock = DockStyle.Fill;
        pnlHomeView.Controls.Add(logArea);
        logArea.BringToFront();

        Panel logHeader = new Panel();
        logHeader.Dock = DockStyle.Top;
        logHeader.Height = 24;
        logArea.Controls.Add(logHeader);

        Label lblLogTitle = new Label();
        lblLogTitle.Text = "Activity Log:";
        lblLogTitle.Font = new Font("Segoe UI", 8.5f, FontStyle.Bold);
        lblLogTitle.ForeColor = Color.FromArgb(120, 120, 130);
        lblLogTitle.Location = new Point(0, 3);
        lblLogTitle.AutoSize = true;
        logHeader.Controls.Add(lblLogTitle);

        Button btnClearLog = new Button();
        btnClearLog.Text = "Clear Log";
        btnClearLog.Font = new Font("Segoe UI", 7.5f, FontStyle.Bold);
        btnClearLog.ForeColor = Color.FromArgb(150, 150, 160);
        btnClearLog.BackColor = Color.FromArgb(12, 12, 16);
        btnClearLog.FlatStyle = FlatStyle.Flat;
        btnClearLog.FlatAppearance.BorderColor = Color.FromArgb(28, 28, 34);
        btnClearLog.Size = new Size(72, 22);
        btnClearLog.Anchor = AnchorStyles.Top | AnchorStyles.Right;
        btnClearLog.Location = new Point(logHeader.Width - 72, 0);
        btnClearLog.Cursor = Cursors.Hand;
        btnClearLog.Click += delegate { if (txtLog != null) txtLog.Clear(); };
        logHeader.Controls.Add(btnClearLog);

        txtLog = new RichTextBox();
        txtLog.Dock = DockStyle.Fill;
        txtLog.BackColor = Color.FromArgb(5, 5, 7);
        txtLog.ForeColor = Color.FromArgb(165, 175, 185);
        txtLog.Font = new Font("Consolas", 8.75f);
        txtLog.BorderStyle = BorderStyle.FixedSingle;
        txtLog.ReadOnly = true;
        txtLog.ScrollBars = RichTextBoxScrollBars.Vertical;
        logArea.Controls.Add(txtLog);
        txtLog.BringToFront();

        // 2. DATA VIEW
        BuildDataView();

        // 3. SETTINGS VIEW
        BuildSettingsView();

        // Ensure Bottom Navigation bar docks before Fill views (highest z-index docks first in WinForms)
        pnlBottomNav.SendToBack();

        LoadStatsFromFile();
        cmbTargetApp.SelectedIndex = (targetAppPackage == PKG_CASH_MAFIA) ? 1 : 0;
        UpdateWatchdogButtonState();
        UpdateDashboard(lastCaptcha);
        UpdateDataDashboard();
        SwitchToHomeView();

        if (autoCheckUpdates)
        {
            System.Windows.Forms.Timer updateDelayTimer = new System.Windows.Forms.Timer();
            updateDelayTimer.Interval = 3500;
            updateDelayTimer.Tick += delegate
            {
                updateDelayTimer.Stop();
                updateDelayTimer.Dispose();
                CheckForUpdatesAsync(false);
            };
            updateDelayTimer.Start();
        }

        Log("QuizEarn & CashMafia Auto-Bot v" + APP_VERSION + " initialized (Clean offer recovery mode).");
        Log("Collect Gem stall guard + Android 7-15 SDK-aware Watchdog ready.");

        Thread watcherThread = new Thread(new ThreadStart(UniversalAdbAutoWatchLoop));
        watcherThread.IsBackground = true;
        watcherThread.Start();
    }

    private Button CreateNavButton(string label)
    {
        Button b = new Button();
        b.Text = label;
        b.Dock = DockStyle.Fill;
        b.Margin = new Padding(4, 0, 4, 0);
        b.Font = new Font("Segoe UI", 9.5f, FontStyle.Bold);
        b.FlatStyle = FlatStyle.Flat;
        b.FlatAppearance.BorderSize = 1;
        b.Cursor = Cursors.Hand;
        return b;
    }

    private Button CreateFlatButton(string text, Color bg, Color fg, Color border)
    {
        Button b = new Button();
        b.Text = text;
        b.Dock = DockStyle.Fill;
        b.Margin = new Padding(2, 0, 2, 0);
        b.Font = new Font("Segoe UI", 8.5f, FontStyle.Bold);
        b.BackColor = bg;
        b.ForeColor = fg;
        b.FlatStyle = FlatStyle.Flat;
        b.FlatAppearance.BorderColor = border;
        b.FlatAppearance.BorderSize = 1;
        b.Cursor = Cursors.Hand;
        return b;
    }

    private Button CreateBoostButton(string text, int x)
    {
        Button b = new Button();
        b.Text = text;
        b.Location = new Point(x, 48);
        b.Size = new Size(50, 30);
        b.Font = new Font("Segoe UI", 8.5f, FontStyle.Bold);
        b.ForeColor = Color.FromArgb(200, 200, 210);
        b.BackColor = Color.FromArgb(14, 14, 18);
        b.FlatStyle = FlatStyle.Flat;
        b.FlatAppearance.BorderColor = Color.FromArgb(30, 30, 36);
        b.Cursor = Cursors.Hand;
        pnlBooster.Controls.Add(b);
        return b;
    }

    private Label CreateMetricLabel(string text)
    {
        Label l = new Label();
        l.Text = text;
        l.Dock = DockStyle.Fill;
        l.Font = new Font("Segoe UI", 9.5f, FontStyle.Bold);
        l.ForeColor = Color.FromArgb(205, 205, 215);
        l.TextAlign = ContentAlignment.MiddleCenter;
        return l;
    }

    private void BuildDataView()
    {
        pnlDataView = new Panel();
        pnlDataView.Dock = DockStyle.Fill;
        pnlDataView.BackColor = Color.Black;
        pnlDataView.Padding = new Padding(18, 14, 18, 10);
        pnlDataView.Visible = false;
        this.Controls.Add(pnlDataView);

        Label lblDataTitle = new Label();
        lblDataTitle.Text = "Earnings & Performance Data";
        lblDataTitle.Font = new Font("Segoe UI", 14f, FontStyle.Bold);
        lblDataTitle.ForeColor = Color.White;
        lblDataTitle.Dock = DockStyle.Top;
        lblDataTitle.Height = 28;

        lblDataDate = new Label();
        lblDataDate.Text = "Today: " + DateTime.Now.ToString("dddd, MMM d, yyyy");
        lblDataDate.Font = new Font("Segoe UI", 8.5f, FontStyle.Bold);
        lblDataDate.ForeColor = Color.FromArgb(135, 135, 145);
        lblDataDate.Dock = DockStyle.Top;
        lblDataDate.Height = 22;

        TableLayoutPanel cardsGrid = new TableLayoutPanel();
        cardsGrid.Dock = DockStyle.Top;
        cardsGrid.Height = 160;
        cardsGrid.ColumnCount = 2;
        cardsGrid.RowCount = 2;
        cardsGrid.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50f));
        cardsGrid.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50f));
        cardsGrid.RowStyles.Add(new RowStyle(SizeType.Percent, 50f));
        cardsGrid.RowStyles.Add(new RowStyle(SizeType.Percent, 50f));

        lblDataCoinsVal = CreateDataCard(cardsGrid, 0, 0, "COINS CLAIMED TODAY", "+0 Coins");
        lblData25GemsVal = CreateDataCard(cardsGrid, 1, 0, "25-GEM CYCLES HIT", "0 times");
        lblDataOffersVal = CreateDataCard(cardsGrid, 0, 1, "OFFERS CLAIMED TODAY", "0 claims");
        lblDataQuizzesVal = CreateDataCard(cardsGrid, 1, 1, "QUIZZES & ADS TODAY", "0 Solved | 0 Ads");

        Panel lifeBar = new Panel();
        lifeBar.Dock = DockStyle.Top;
        lifeBar.Height = 52;
        lifeBar.BackColor = Color.FromArgb(8, 8, 10);

        lblData25GemsSub = new Label { Text = "0 Gems today", Location = new Point(8, 6), AutoSize = true, ForeColor = Color.Gray, Font = new Font("Segoe UI", 8f) };
        lblLifetimeCoins = new Label { Text = "Lifetime: 0 Coins", Location = new Point(8, 24), AutoSize = true, ForeColor = Color.Silver, Font = new Font("Segoe UI", 9f, FontStyle.Bold) };
        lblLifetime25Gems = new Label { Text = "Cycles: 0", Location = new Point(200, 24), AutoSize = true, ForeColor = Color.Silver, Font = new Font("Segoe UI", 9f, FontStyle.Bold) };
        lblLifetimeQuizzes = new Label { Text = "Solved: 0", Location = new Point(350, 24), AutoSize = true, ForeColor = Color.Silver, Font = new Font("Segoe UI", 9f, FontStyle.Bold) };
        lifeBar.Controls.AddRange(new Control[] { lblData25GemsSub, lblLifetimeCoins, lblLifetime25Gems, lblLifetimeQuizzes });

        Panel dataActions = new Panel { Dock = DockStyle.Bottom, Height = 44 };

        TableLayoutPanel dataBtnGrid = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 2 };
        dataBtnGrid.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50f));
        dataBtnGrid.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50f));
        dataActions.Controls.Add(dataBtnGrid);

        btnDataRefresh = CreateFlatButton("Refresh Data", Color.FromArgb(14, 14, 18), Color.White, Color.FromArgb(32, 32, 38));
        btnDataRefresh.Click += delegate { UpdateDataDashboard(); };
        btnDataResetToday = CreateFlatButton("Reset Today Stats", Color.FromArgb(14, 14, 18), Color.FromArgb(180, 140, 140), Color.FromArgb(40, 28, 30));
        btnDataResetToday.Click += delegate
        {
            if (ShowDarkConfirm("Reset Today Stats", "Reset today's coins, 25-gem cycles, and claim history to 0?"))
            {
                lock (statsLock)
                {
                    todayCoinsClaimed = 0; today25GemsCount = 0; todayOffersClaimed = 0; todaySolved = 0; gemsEarned = 0;
                    claimsHistory.Clear();
                }
                SaveStatsToFile();
                UpdateDataDashboard();
                UpdateDashboard(lastCaptcha);
                Log("[Data] Today's statistics reset.");
            }
        };
        dataBtnGrid.Controls.Add(btnDataRefresh, 0, 0);
        dataBtnGrid.Controls.Add(btnDataResetToday, 1, 0);

        txtClaimsLog = new RichTextBox();
        txtClaimsLog.Dock = DockStyle.Fill;
        txtClaimsLog.BackColor = Color.FromArgb(5, 5, 7);
        txtClaimsLog.ForeColor = Color.FromArgb(185, 185, 195);
        txtClaimsLog.Font = new Font("Consolas", 9f);
        txtClaimsLog.BorderStyle = BorderStyle.FixedSingle;
        txtClaimsLog.ReadOnly = true;
        txtClaimsLog.ScrollBars = RichTextBoxScrollBars.Vertical;

        // Add Fill first (index 0 -> docks last), then Bottom -> Top in reverse visual order
        pnlDataView.Controls.Add(txtClaimsLog);
        pnlDataView.Controls.Add(lifeBar);
        pnlDataView.Controls.Add(cardsGrid);
        pnlDataView.Controls.Add(lblDataDate);
        pnlDataView.Controls.Add(lblDataTitle);
        pnlDataView.Controls.Add(dataActions);
    }

    private Label CreateDataCard(TableLayoutPanel parent, int col, int row, string head, string val)
    {
        Panel p = new Panel { Dock = DockStyle.Fill, Margin = new Padding(4), BackColor = Color.FromArgb(8, 8, 10), Padding = new Padding(10) };
        Label lh = new Label { Text = head, Dock = DockStyle.Top, Height = 18, Font = new Font("Segoe UI", 7.5f, FontStyle.Bold), ForeColor = Color.Gray };
        Label lv = new Label { Text = val, Dock = DockStyle.Fill, Font = new Font("Segoe UI", 13.5f, FontStyle.Bold), ForeColor = Color.White };
        p.Controls.Add(lv);
        p.Controls.Add(lh);
        parent.Controls.Add(p, col, row);
        return lv;
    }

    private void BuildSettingsView()
    {
        pnlSettingsView = new Panel();
        pnlSettingsView.Dock = DockStyle.Fill;
        pnlSettingsView.BackColor = Color.Black;
        pnlSettingsView.Padding = new Padding(18, 14, 18, 10);
        pnlSettingsView.AutoScroll = true;
        pnlSettingsView.Visible = false;
        this.Controls.Add(pnlSettingsView);

        Label lblSettingsTitle = new Label { Text = "Settings & Cloud Updater", Font = new Font("Segoe UI", 14.5f, FontStyle.Bold), ForeColor = Color.White, Location = new Point(18, 14), AutoSize = true };
        pnlSettingsView.Controls.Add(lblSettingsTitle);

        Panel pnlUpdateCard = new Panel { BackColor = Color.FromArgb(8, 8, 10), Location = new Point(18, 52), Size = new Size(508, 92), Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right };
        pnlSettingsView.Controls.Add(pnlUpdateCard);

        lblCurrentVersion = new Label { Text = "Current Installed: v" + APP_VERSION, Font = new Font("Segoe UI", 11.5f, FontStyle.Bold), ForeColor = Color.White, Location = new Point(12, 12), AutoSize = true };
        lblUpdateStatus = new Label { Text = "Status: Up to date.", Font = new Font("Segoe UI", 8.5f), ForeColor = Color.Gray, Location = new Point(14, 42), Size = new Size(320, 36) };
        btnCheckUpdate = new Button { Text = "Check for Updates", Location = new Point(342, 24), Size = new Size(152, 36), Anchor = AnchorStyles.Top | AnchorStyles.Right, FlatStyle = FlatStyle.Flat, BackColor = Color.FromArgb(18, 18, 22), ForeColor = Color.White, Font = new Font("Segoe UI", 9f, FontStyle.Bold), Cursor = Cursors.Hand };
        btnCheckUpdate.Click += delegate { CheckForUpdatesAsync(true); };
        pnlUpdateCard.Controls.AddRange(new Control[] { lblCurrentVersion, lblUpdateStatus, btnCheckUpdate });

        Panel pnlPrefsCard = new Panel { BackColor = Color.FromArgb(8, 8, 10), Location = new Point(18, 156), Size = new Size(508, 210), Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right };
        pnlSettingsView.Controls.Add(pnlPrefsCard);

        chkAutoCheckUpdates = new DarkToggleSwitch { Checked = autoCheckUpdates };
        CreateToggleRow(pnlPrefsCard, "Auto-check for updates on startup", chkAutoCheckUpdates, 16, () => { autoCheckUpdates = chkAutoCheckUpdates.Checked; SaveStatsToFile(); });

        chkSettingsAutoMute = new DarkToggleSwitch { Checked = autoMuteEnabled };
        CreateToggleRow(pnlPrefsCard, "Auto-Mute Ads while bot runs", chkSettingsAutoMute, 52, () => { autoMuteEnabled = chkSettingsAutoMute.Checked; SaveStatsToFile(); });

        chkSettingsTurbo = new DarkToggleSwitch { Checked = turboSpeedEnabled };
        CreateToggleRow(pnlPrefsCard, "Turbo Speed Quiz Solver", chkSettingsTurbo, 88, () => { turboSpeedEnabled = chkSettingsTurbo.Checked; SaveStatsToFile(); });

        chkSettingsAlwaysOnTop = new DarkToggleSwitch { Checked = false };
        CreateToggleRow(pnlPrefsCard, "Always On Top window mode", chkSettingsAlwaysOnTop, 124, () => { this.TopMost = chkSettingsAlwaysOnTop.Checked; });

        chkSettingsAutoRecoverBst = new DarkToggleSwitch { Checked = autoRecoverBlueStacks };
        CreateToggleRow(pnlPrefsCard, "Auto-Watchdog: Recover BlueStacks & Android APK Bot", chkSettingsAutoRecoverBst, 160, () => { autoRecoverBlueStacks = chkSettingsAutoRecoverBst.Checked; UpdateWatchdogButtonState(); SaveStatsToFile(); });
    }

    private void SwitchToHomeView()
    {
        SafeBeginInvoke(delegate
        {
            pnlHomeView.Visible = true;
            pnlDataView.Visible = false;
            pnlSettingsView.Visible = false;
            SetActiveNavButton(btnNavHome);
        });
    }

    private void SwitchToDataView()
    {
        SafeBeginInvoke(delegate
        {
            UpdateDataDashboard();
            pnlHomeView.Visible = false;
            pnlDataView.Visible = true;
            pnlSettingsView.Visible = false;
            SetActiveNavButton(btnNavData);
        });
    }

    private void SwitchToSettingsView()
    {
        SafeBeginInvoke(delegate
        {
            pnlHomeView.Visible = false;
            pnlDataView.Visible = false;
            pnlSettingsView.Visible = true;
            chkAutoCheckUpdates.Checked = autoCheckUpdates;
            chkSettingsAutoMute.Checked = autoMuteEnabled;
            chkSettingsTurbo.Checked = turboSpeedEnabled;
            chkSettingsAlwaysOnTop.Checked = this.TopMost;
            chkSettingsAutoRecoverBst.Checked = autoRecoverBlueStacks;
            SetActiveNavButton(btnNavSettings);
        });
    }

    private void SetActiveNavButton(Button activeBtn)
    {
        foreach (Button b in new Button[] { btnNavHome, btnNavData, btnNavSettings })
        {
            if (b == activeBtn) { b.ForeColor = Color.White; b.BackColor = Color.FromArgb(24, 24, 30); b.FlatAppearance.BorderColor = Color.FromArgb(55, 55, 65); }
            else { b.ForeColor = Color.FromArgb(125, 125, 135); b.BackColor = Color.FromArgb(8, 8, 10); b.FlatAppearance.BorderColor = Color.FromArgb(22, 22, 26); }
        }
    }

    private void Log(string message)
    {
        string line = string.Format("[{0:HH:mm:ss}] {1}\n", DateTime.Now, message);
        lock (logLock)
        {
            try
            {
                string logFile = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "bot_run.log");
                File.AppendAllText(logFile, line, Encoding.UTF8);
            }
            catch { }
        }

        SafeBeginInvoke(delegate
        {
            if (txtLog != null && !txtLog.IsDisposed)
            {
                if (txtLog.TextLength > 80000) txtLog.Clear();
                txtLog.AppendText(line);
                txtLog.SelectionStart = txtLog.Text.Length;
                txtLog.ScrollToCaret();
            }
        });
    }

    private void LoadStatsFromFile()
    {
        lock (statsLock)
        {
            try
            {
                string path = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "quizearn_stats.json");
                if (!File.Exists(path)) return;
                string json = File.ReadAllText(path, Encoding.UTF8);
                string todayStr = DateTime.Now.ToString("yyyy-MM-dd");
                Match mDate = Regex.Match(json, @"""todayDate"":\s*""([^""]+)""");
                bool isSameDay = mDate.Success && mDate.Groups[1].Value == todayStr;

                if (isSameDay)
                {
                    todaySolved = ExtractInt(json, "todaySolved", 0);
                    todayCoinsClaimed = ExtractInt(json, "todayCoinsClaimed", 0);
                    today25GemsCount = ExtractInt(json, "today25GemsCount", 0);
                    todayOffersClaimed = ExtractInt(json, "todayOffersClaimed", 0);
                }
                gemsEarned = ExtractInt(json, "gemsEarned", 0);
                adsClosed = ExtractInt(json, "adsClosed", 0);
                totalGems = ExtractInt(json, "totalSolved", 0);
                totalCoinsClaimed = ExtractInt(json, "totalCoinsClaimed", 0);
                total25GemsCount = ExtractInt(json, "total25GemsCount", 0);
                totalOffersClaimed = ExtractInt(json, "totalOffersClaimed", 0);

                autoCheckUpdates = ExtractBool(json, "autoCheckUpdates", true);
                autoRecoverBlueStacks = ExtractBool(json, "autoRecoverBlueStacks", true);
                autoMuteEnabled = ExtractBool(json, "autoMuteEnabled", true);
                turboSpeedEnabled = ExtractBool(json, "turboSpeedEnabled", true);

                Match mPkg = Regex.Match(json, @"""targetAppPackage"":\s*""([^""]+)""");
                if (mPkg.Success && IsKnownQuizApp(mPkg.Groups[1].Value)) targetAppPackage = mPkg.Groups[1].Value;

                claimsHistory.Clear();
                if (isSameDay)
                {
                    Match mHist = Regex.Match(json, @"""claimsHistory"":\s*\[(.*?)\]", RegexOptions.Singleline);
                    if (mHist.Success)
                    {
                        foreach (Match it in Regex.Matches(mHist.Groups[1].Value, @"""((?:\\.|[^""\\])*)"""))
                        {
                            claimsHistory.Add(it.Groups[1].Value.Replace("\\\"", "\"").Replace("\\\\", "\\"));
                        }
                    }
                }
            }
            catch { }
        }
    }

    private int ExtractInt(string json, string key, int defVal)
    {
        Match m = Regex.Match(json, @"""" + key + @""":\s*(\d+)");
        int v; return (m.Success && int.TryParse(m.Groups[1].Value, out v)) ? v : defVal;
    }

    private bool ExtractBool(string json, string key, bool defVal)
    {
        Match m = Regex.Match(json, @"""" + key + @""":\s*(true|false)", RegexOptions.IgnoreCase);
        bool v; return (m.Success && bool.TryParse(m.Groups[1].Value, out v)) ? v : defVal;
    }

    private void SaveStatsToFile()
    {
        lock (statsLock)
        {
            try
            {
                string path = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "quizearn_stats.json");
                string tmpPath = path + ".tmp";
                StringBuilder histJson = new StringBuilder();
                for (int i = 0; i < claimsHistory.Count; i++)
                {
                    if (i > 0) histJson.Append(", ");
                    histJson.Append("\"" + claimsHistory[i].Replace("\\", "\\\\").Replace("\"", "\\\"") + "\"");
                }

                string json = string.Format(
                    "{{\n  \"todayDate\": \"{0}\",\n  \"todaySolved\": {1},\n  \"totalSolved\": {2},\n  \"gemsEarned\": {3},\n  \"adsClosed\": {4},\n  \"todayCoinsClaimed\": {5},\n  \"totalCoinsClaimed\": {6},\n  \"today25GemsCount\": {7},\n  \"total25GemsCount\": {8},\n  \"todayOffersClaimed\": {9},\n  \"totalOffersClaimed\": {10},\n  \"autoCheckUpdates\": {11},\n  \"autoRecoverBlueStacks\": {12},\n  \"autoMuteEnabled\": {13},\n  \"turboSpeedEnabled\": {14},\n  \"targetAppPackage\": \"{15}\",\n  \"claimsHistory\": [{16}]\n}}",
                    DateTime.Now.ToString("yyyy-MM-dd"), todaySolved, totalGems + todaySolved, gemsEarned, adsClosed,
                    todayCoinsClaimed, totalCoinsClaimed, today25GemsCount, total25GemsCount, todayOffersClaimed, totalOffersClaimed,
                    autoCheckUpdates.ToString().ToLower(), autoRecoverBlueStacks.ToString().ToLower(),
                    autoMuteEnabled.ToString().ToLower(), turboSpeedEnabled.ToString().ToLower(),
                    GetTargetPackage(), histJson.ToString()
                );

                File.WriteAllText(tmpPath, json, Encoding.UTF8);
                File.Copy(tmpPath, path, true);
                try { File.Delete(tmpPath); } catch { }
            }
            catch { }
        }
    }

    private void AddClaimHistory(string text)
    {
        lock (statsLock)
        {
            string entry = string.Format("[{0:hh:mm tt}] {1}", DateTime.Now, text);
            claimsHistory.Insert(0, entry);
            if (claimsHistory.Count > 100) claimsHistory.RemoveRange(100, claimsHistory.Count - 100);
        }
        SaveStatsToFile();
        UpdateDataDashboard();
    }

    private void UpdateDashboard(string lastCaptchaDigit)
    {
        SafeBeginInvoke(delegate
        {
            lblTodaySolved.Text = string.Format("{0}\nSolved", todaySolved);
            lblGems.Text = string.Format("{0}/25\nOffer Gems", currentOfferGems);
            lblCaptcha.Text = string.Format("{0}\nLast Captcha", lastCaptchaDigit);
            lblStats.Text = string.Format("{0}\nAds Closed", adsClosed);
            UpdateDataDashboard();
        });
    }

    private void UpdateDataDashboard()
    {
        SafeBeginInvoke(delegate
        {
            lblDataCoinsVal.Text = string.Format("+{0:N0} Coins", todayCoinsClaimed);
            lblData25GemsVal.Text = string.Format("{0} times", today25GemsCount);
            lblData25GemsSub.Text = string.Format("{0} Gems earned today", gemsEarned);
            lblDataOffersVal.Text = string.Format("{0} claims", todayOffersClaimed);
            lblDataQuizzesVal.Text = string.Format("{0} Solved | {1} Ads", todaySolved, adsClosed);
            lblLifetimeCoins.Text = string.Format("Lifetime: {0:N0} Coins", totalCoinsClaimed);
            lblLifetime25Gems.Text = string.Format("Cycles: {0}", total25GemsCount);
            lblLifetimeQuizzes.Text = string.Format("Solved: {0:N0}", totalGems + todaySolved);
            lblDataDate.Text = "Today: " + DateTime.Now.ToString("dddd, MMM d, yyyy");

            if (txtClaimsLog != null)
            {
                txtClaimsLog.Clear();
                List<string> snapshot;
                lock (statsLock) { snapshot = new List<string>(claimsHistory); }
                if (snapshot.Count == 0) txtClaimsLog.AppendText("No claims recorded yet today.");
                else foreach (string item in snapshot) txtClaimsLog.AppendText(item + "\n");
            }
        });
    }

    private void UpdatePortLabel(string text)
    {
        SafeBeginInvoke(delegate { lblPort.Text = text; });
    }

    private string RunAdbDevice(string deviceId, string arguments)
    {
        if (string.IsNullOrEmpty(deviceId)) return RunAdb(arguments);
        return RunAdb(string.Format("-s {0} {1}", deviceId, arguments));
    }

    private string RunAdb(string arguments)
    {
        return RunAdbInternal(arguments, 0);
    }

    private string RunAdbInternal(string arguments, int retryDepth)
    {
        try
        {
            using (Process p = new Process())
            {
                p.StartInfo.FileName = adbExe;
                bool isGlobalCmd = string.IsNullOrEmpty(adbPort) || arguments.StartsWith("-s ") ||
                    arguments.StartsWith("devices") || arguments.StartsWith("connect") ||
                    arguments.StartsWith("disconnect") || arguments.StartsWith("kill-server") ||
                    arguments.StartsWith("start-server");
                p.StartInfo.Arguments = isGlobalCmd ? arguments : string.Format("-s {0} {1}", adbPort, arguments);
                p.StartInfo.UseShellExecute = false;
                p.StartInfo.RedirectStandardOutput = true;
                p.StartInfo.RedirectStandardError = true;
                p.StartInfo.CreateNoWindow = true;

                StringBuilder sb = new StringBuilder();
                p.OutputDataReceived += (s, e) => { if (e.Data != null) sb.AppendLine(e.Data); };
                p.ErrorDataReceived += (s, e) => { if (e.Data != null) sb.AppendLine(e.Data); };

                p.Start();
                p.BeginOutputReadLine();
                p.BeginErrorReadLine();

                bool exited = p.WaitForExit(6000);
                if (!exited)
                {
                    try { p.Kill(); } catch { }
                    try { p.WaitForExit(500); } catch { }
                }
                else
                {
                    p.WaitForExit();
                }

                string res = sb.ToString();
                if (retryDepth == 0 && !isGlobalCmd && (res.Contains("not found") || res.Contains("offline") || res.Contains("error: device")))
                {
                    EnsureConnected();
                    if (!string.IsNullOrEmpty(adbPort)) return RunAdbInternal(arguments, 1);
                }
                return res;
            }
        }
        catch { return ""; }
    }

    private void UpdateScreenResolution()
    {
        try
        {
            string outSize = RunAdb("shell wm size");
            Match m = Regex.Match(outSize, @"(?:Override size|Physical size):\s*(\d+)x(\d+)");
            if (m.Success)
            {
                int w = int.Parse(m.Groups[1].Value);
                int h = int.Parse(m.Groups[2].Value);
                if (w > 0 && h > 0 && (devWidth != w || devHeight != h))
                {
                    devWidth = w; devHeight = h;
                    Log(string.Format("[Screen] Resolution detected: {0}x{1}", devWidth, devHeight));
                }
            }
        }
        catch { }
    }

    private void AdbTap(int x, int y)
    {
        RunAdb(string.Format("shell input tap {0} {1}", Math.Max(1, Math.Min(devWidth - 1, x)), Math.Max(1, Math.Min(devHeight - 1, y))));
    }

    private void AdbTapRef(int refX, int refY)
    {
        int actualX = (devWidth == 900) ? refX : (int)Math.Round((double)refX * devWidth / 900.0);
        int actualY = (devHeight == 1600) ? refY : (int)Math.Round((double)refY * devHeight / 1600.0);
        AdbTap(actualX, actualY);
    }

    private void AdbSwipeRef(int x1, int y1, int x2, int y2, int durationMs)
    {
        int sx1 = (int)Math.Round((double)x1 * devWidth / 900.0);
        int sy1 = (int)Math.Round((double)y1 * devHeight / 1600.0);
        int sx2 = (int)Math.Round((double)x2 * devWidth / 900.0);
        int sy2 = (int)Math.Round((double)y2 * devHeight / 1600.0);
        RunAdb(string.Format("shell input swipe {0} {1} {2} {3} {4}", sx1, sy1, sx2, sy2, durationMs));
    }

    private void AdbKey(int keyCode) { RunAdb(string.Format("shell input keyevent {0}", keyCode)); }

    private int GetDeviceSdkInt(string deviceId)
    {
        string raw = RunAdbDevice(deviceId, "shell getprop ro.build.version.sdk").Trim();
        int sdk; return int.TryParse(raw, out sdk) ? sdk : 30;
    }

    private void StartOrStopOverlayService(string deviceId, string action)
    {
        int sdk = GetDeviceSdkInt(deviceId);
        string cmd = (sdk >= 26 && action == "START")
            ? string.Format("shell am start-foreground-service -a {0}.{1} {2}", PKG_BOT_ASSISTANT, action, OVERLAY_COMPONENT)
            : string.Format("shell am startservice -a {0}.{1} {2}", PKG_BOT_ASSISTANT, action, OVERLAY_COMPONENT);
        RunAdbDevice(deviceId, cmd);
    }

    private void EnsureAccessibilityEnabled(string deviceId, string currentServices)
    {
        string cur = (currentServices ?? "").Trim();
        if (cur == "null") cur = "";
        if (cur.Contains(A11Y_COMPONENT)) return;

        string next = string.IsNullOrEmpty(cur) ? A11Y_COMPONENT : (cur + ":" + A11Y_COMPONENT);
        RunAdbDevice(deviceId, string.Format("shell settings put secure enabled_accessibility_services \"{0}\"", next));
        RunAdbDevice(deviceId, "shell settings put secure accessibility_enabled 1");
    }

    private void LaunchTargetAppOnDevice(string deviceId)
    {
        string pkg = GetTargetPackage();
        RunAdbDevice(deviceId, string.Format("shell monkey -p {0} -c android.intent.category.LAUNCHER 1", pkg));
    }

    private void ForceStopTargetApp(string deviceId, string pkg)
    {
        RunAdbDevice(deviceId, "shell input keyevent 3");
        Thread.Sleep(250);
        string suStop = RunAdbDevice(deviceId, string.Format("shell su -c \"am force-stop {0}\"", pkg));
        if (suStop.Contains("not found") || suStop.Contains("Permission denied"))
        {
            RunAdbDevice(deviceId, string.Format("shell am force-stop {0}", pkg));
        }
        RunAdbDevice(deviceId, string.Format("shell am kill {0}", pkg));
    }

    private List<string> GetAllConnectedAdbDevices()
    {
        List<string> result = new List<string>();
        string raw = RunAdb("devices");
        foreach (string line in raw.Split('\n'))
        {
            string tr = line.Trim();
            if (string.IsNullOrEmpty(tr) || tr.StartsWith("List of") || tr.StartsWith("*")) continue;
            string[] parts = tr.Split(new char[] { ' ', '\t' }, StringSplitOptions.RemoveEmptyEntries);
            if (parts.Length >= 2 && parts[1].Equals("device", StringComparison.OrdinalIgnoreCase))
            {
                string devId = parts[0];
                // CRITICAL: User requirement - EXE bot must ONLY target BlueStacks/emulators, NEVER touch physical phones!
                if (!IsEmulatorDevice(devId)) continue;

                if (devId.StartsWith("emulator-"))
                {
                    int emuPort;
                    if (int.TryParse(devId.Substring(9), out emuPort))
                    {
                        string ipPort = "127.0.0.1:" + (emuPort + 1);
                        if (raw.Contains(ipPort)) continue;
                    }
                }
                if (!result.Contains(devId)) result.Add(devId);
            }
        }
        return result;
    }

    private bool EnsureConnected()
    {
        CleanOfflineAdbDevices();
        List<string> connected = GetAllConnectedAdbDevices();
        string chosen = connected.FirstOrDefault(id => IsEmulatorDevice(id));
        if (!string.IsNullOrEmpty(chosen))
        {
            adbPort = chosen;
            UpdatePortLabel("Emulator: " + chosen);
            UpdateScreenResolution();
            return true;
        }

        foreach (string port in DiscoverUniversalAdbPorts())
        {
            if (port.StartsWith("127.0.0.1:"))
            {
                string res = RunAdb("connect " + port);
                if (res.Contains("connected to") || res.Contains("already connected"))
                {
                    if (RunAdbDevice(port, "get-state").Trim().Equals("device", StringComparison.OrdinalIgnoreCase))
                    {
                        adbPort = port;
                        UpdatePortLabel("Emulator: " + port);
                        UpdateScreenResolution();
                        return true;
                    }
                }
            }
        }

        UpdatePortLabel("No BlueStacks Connected");
        return false;
    }

    private bool IsEmulatorDevice(string id)
    {
        if (string.IsNullOrEmpty(id)) return false;
        string lower = id.ToLower();
        return lower.StartsWith("emulator-") || lower.StartsWith("127.0.0.1:") || lower.StartsWith("localhost:");
    }

    private static bool IsTcpPortOpen(int port)
    {
        try
        {
            using (var tcp = new TcpClient())
            {
                var ar = tcp.BeginConnect("127.0.0.1", port, null, null);
                if (ar.AsyncWaitHandle.WaitOne(25)) { tcp.EndConnect(ar); return true; }
            }
        }
        catch { }
        return false;
    }

    private void CleanOfflineAdbDevices()
    {
        try
        {
            string raw = RunAdb("devices");
            foreach (string line in raw.Split('\n'))
            {
                string tr = line.Trim();
                if (string.IsNullOrEmpty(tr) || tr.StartsWith("List of") || tr.StartsWith("*")) continue;
                string[] parts = tr.Split(new char[] { ' ', '\t' }, StringSplitOptions.RemoveEmptyEntries);
                if (parts.Length >= 2 && parts[1].Equals("offline", StringComparison.OrdinalIgnoreCase))
                {
                    RunAdb("disconnect " + parts[0]);
                }
            }
        }
        catch { }
    }

    private List<string> DiscoverUniversalAdbPorts()
    {
        List<string> ports = new List<string>();
        foreach (var inst in DiscoverBlueStacksInstances())
        {
            foreach (string p in inst.CandidatePorts)
            {
                string ep = "127.0.0.1:" + p;
                if (!ports.Contains(ep)) ports.Add(ep);
            }
        }
        foreach (int p in new int[] { 5555, 5556, 5565, 5575, 5585, 5595, 5605, 5615, 62001, 21503, 7555, 16384, 58526 })
        {
            string ep = "127.0.0.1:" + p;
            if (!ports.Contains(ep) && IsTcpPortOpen(p)) ports.Add(ep);
        }
        return ports;
    }

    private class BlueStacksInstance
    {
        public string InstanceName;
        public string DisplayName;
        public string Port;
        public List<string> CandidatePorts = new List<string>();
    }

    private List<BlueStacksInstance> DiscoverBlueStacksInstances()
    {
        Dictionary<string, BlueStacksInstance> dict = new Dictionary<string, BlueStacksInstance>(StringComparer.OrdinalIgnoreCase);
        foreach (string confPath in new string[] {
            @"C:\ProgramData\BlueStacks_nxt\bluestacks.conf",
            @"D:\ProgramData\BlueStacks_nxt\bluestacks.conf",
            @"E:\ProgramData\BlueStacks_nxt\bluestacks.conf",
            @"C:\ProgramData\BlueStacks\bluestacks.conf" })
        {
            if (!File.Exists(confPath)) continue;
            try
            {
                string conf = File.ReadAllText(confPath);

                // Collect display names
                foreach (Match m in Regex.Matches(conf, @"bst\.instance\.([^.]+)\.display_name=""([^""]+)"""))
                {
                    string inst = m.Groups[1].Value.Trim();
                    string disp = m.Groups[2].Value.Trim();
                    if (!dict.ContainsKey(inst))
                    {
                        dict[inst] = new BlueStacksInstance { InstanceName = inst, DisplayName = disp };
                    }
                    else
                    {
                        dict[inst].DisplayName = disp;
                    }
                }

                // Collect candidate ports (status.adb_port and adb_port)
                foreach (Match m in Regex.Matches(conf, @"bst\.instance\.([^.]+)\.(?:status\.)?adb_port=""(\d+)"""))
                {
                    string inst = m.Groups[1].Value.Trim();
                    string port = m.Groups[2].Value.Trim();
                    if (!dict.ContainsKey(inst))
                    {
                        dict[inst] = new BlueStacksInstance { InstanceName = inst, DisplayName = inst };
                    }
                    if (!dict[inst].CandidatePorts.Contains(port))
                    {
                        dict[inst].CandidatePorts.Add(port);
                    }
                    if (m.Value.Contains("status.adb_port"))
                    {
                        dict[inst].Port = port;
                    }
                    else if (string.IsNullOrEmpty(dict[inst].Port))
                    {
                        dict[inst].Port = port;
                    }
                }
                if (dict.Count > 0) break;
            }
            catch { }
        }

        // Add standard candidate port mappings for known instances if missing
        foreach (var kvp in dict)
        {
            var inst = kvp.Value;
            if (inst.InstanceName.Equals("Tiramisu64", StringComparison.OrdinalIgnoreCase))
            {
                if (!inst.CandidatePorts.Contains("5556")) inst.CandidatePorts.Add("5556");
                if (!inst.CandidatePorts.Contains("5555")) inst.CandidatePorts.Add("5555");
            }
            else if (inst.InstanceName.Equals("Tiramisu64_3", StringComparison.OrdinalIgnoreCase))
            {
                if (!inst.CandidatePorts.Contains("5585")) inst.CandidatePorts.Add("5585");
            }
            else if (inst.InstanceName.Equals("Tiramisu64_4", StringComparison.OrdinalIgnoreCase))
            {
                if (!inst.CandidatePorts.Contains("5595")) inst.CandidatePorts.Add("5595");
            }

            if (string.IsNullOrEmpty(inst.Port) && inst.CandidatePorts.Count > 0)
            {
                inst.Port = inst.CandidatePorts[0];
            }
        }

        return dict.Values.ToList();
    }

    private bool IsBlueStacksProcessRunning()
    {
        try { return Process.GetProcessesByName("HD-Player").Length > 0; } catch { return false; }
    }

    private bool IsInstanceOnline(BlueStacksInstance inst, List<string> onlineDevices, out string activeEndpoint)
    {
        activeEndpoint = null;
        if (inst == null) return false;

        foreach (string port in inst.CandidatePorts)
        {
            string ep = "127.0.0.1:" + port;
            if (onlineDevices != null && onlineDevices.Contains(ep))
            {
                activeEndpoint = ep;
                inst.Port = port;
                return true;
            }
        }

        foreach (string port in inst.CandidatePorts)
        {
            int p;
            if (int.TryParse(port, out p) && IsTcpPortOpen(p))
            {
                string ep = "127.0.0.1:" + port;
                RunAdb("connect " + ep);
                string state = RunAdbDevice(ep, "get-state").Trim();
                if (state.Equals("device", StringComparison.OrdinalIgnoreCase))
                {
                    activeEndpoint = ep;
                    inst.Port = port;
                    return true;
                }
            }
        }

        return false;
    }

    private string DetectBlueStacks(out string instanceArg)
    {
        instanceArg = "";
        string[] candidates = new string[] {
            @"C:\Program Files\BlueStacks_nxt\HD-Player.exe",
            @"C:\Program Files (x86)\BlueStacks_nxt\HD-Player.exe",
            @"C:\Program Files\BlueStacks\HD-Player.exe"
        };
        foreach (string c in candidates) if (File.Exists(c)) return c;
        try
        {
            using (RegistryKey rk = Registry.LocalMachine.OpenSubKey(@"SOFTWARE\BlueStacks_nxt"))
            {
                if (rk != null && rk.GetValue("InstallDir") != null)
                {
                    string p = Path.Combine(rk.GetValue("InstallDir").ToString(), "HD-Player.exe");
                    if (File.Exists(p)) return p;
                }
            }
        }
        catch { }
        return "";
    }

    private bool RecoverBlueStacksInstance(BlueStacksInstance inst)
    {
        if (!autoRecoverBlueStacks || inst == null) return false;
        Log(string.Format("[Watchdog] Recovering BlueStacks instance '{0}' ({1})...", inst.DisplayName, inst.InstanceName));

        // 1. Disconnect stale/offline ADB sockets for candidate ports
        foreach (string p in inst.CandidatePorts)
        {
            RunAdb("disconnect 127.0.0.1:" + p);
        }

        // 2. Locate HD-Player.exe
        string dummy;
        string bstExe = DetectBlueStacks(out dummy);
        if (string.IsNullOrEmpty(bstExe) || !File.Exists(bstExe))
        {
            Log("[Watchdog] Error: HD-Player.exe not found.");
            return false;
        }

        // 3. Launch HD-Player.exe with proper working directory
        try
        {
            ProcessStartInfo psi = new ProcessStartInfo
            {
                FileName = bstExe,
                Arguments = "--instance " + inst.InstanceName,
                WorkingDirectory = Path.GetDirectoryName(bstExe),
                UseShellExecute = true
            };
            Process.Start(psi);
            Log(string.Format("[Watchdog] Launched HD-Player.exe --instance {0}", inst.InstanceName));
        }
        catch (Exception ex)
        {
            Log("[Watchdog] Launch failed: " + ex.Message);
            return false;
        }

        // 4. Poll for boot completion (up to 90s, checking every 3s)
        string readyEndpoint = null;
        for (int wait = 0; wait < 90; wait += 3)
        {
            Thread.Sleep(3000);
            foreach (string p in inst.CandidatePorts)
            {
                int pNum;
                if (int.TryParse(p, out pNum) && IsTcpPortOpen(pNum))
                {
                    string ep = "127.0.0.1:" + p;
                    RunAdb("connect " + ep);
                    string state = RunAdbDevice(ep, "get-state").Trim();
                    if (state.Equals("device", StringComparison.OrdinalIgnoreCase))
                    {
                        string boot = RunAdbDevice(ep, "shell getprop sys.boot_completed").Trim();
                        if (boot == "1")
                        {
                            readyEndpoint = ep;
                            inst.Port = p;
                            break;
                        }
                    }
                }
            }
            if (readyEndpoint != null) break;
        }

        if (readyEndpoint == null)
        {
            Log(string.Format("[Watchdog] Instance '{0}' boot timeout. Will retry next cycle.", inst.DisplayName));
            return false;
        }

        Log(string.Format("[Watchdog] Instance '{0}' booted successfully on {1}!", inst.DisplayName, readyEndpoint));
        Thread.Sleep(2500);

        // 5. Post-boot service activation (ZERO APK REINSTALL/TOUCH)
        try
        {
            string curServices = RunAdbDevice(readyEndpoint, "shell settings get secure enabled_accessibility_services");
            EnsureAccessibilityEnabled(readyEndpoint, curServices);
            LaunchTargetAppOnDevice(readyEndpoint);
            StartOrStopOverlayService(readyEndpoint, "START");
        }
        catch { }

        Log(string.Format("[Watchdog] Instance '{0}' fully recovered and apps started.", inst.DisplayName));
        return true;
    }

    private bool EnsureBlueStacksAliveAndReady(string callerContext)
    {
        if (!autoRecoverBlueStacks) return false;
        if (EnsureConnected()) return true;

        Log(string.Format("[Watchdog] Recovery triggered during {0}...", callerContext));
        var insts = DiscoverBlueStacksInstances();
        if (insts.Count > 0)
        {
            RecoverBlueStacksInstance(insts[0]);
        }
        return EnsureConnected();
    }

    private void UniversalAdbAutoWatchLoop()
    {
        int checkCycle = 0;
        HashSet<string> knownActiveInstances = new HashSet<string>(StringComparer.OrdinalIgnoreCase);

        while (!this.IsDisposed)
        {
            try
            {
                checkCycle++;
                CleanOfflineAdbDevices();
                List<string> onlineDevices = GetAllConnectedAdbDevices();
                var instances = DiscoverBlueStacksInstances();

                // 1. Check discovered instances
                foreach (var inst in instances)
                {
                    string activeEp;
                    bool isOnline = IsInstanceOnline(inst, onlineDevices, out activeEp);
                    if (isOnline)
                    {
                        knownActiveInstances.Add(inst.InstanceName);
                        if (!onlineDevices.Contains(activeEp)) onlineDevices.Add(activeEp);
                    }
                    else
                    {
                        // Instance is crashed or offline!
                        bool shouldRecover = autoRecoverBlueStacks && 
                            (knownActiveInstances.Contains(inst.InstanceName) || (onlineDevices.Count == 0 && instances.IndexOf(inst) == 0));

                        if (shouldRecover)
                        {
                            Log(string.Format("[Watchdog] Instance '{0}' ({1}) is crashed/offline! Recovering...", inst.DisplayName, inst.InstanceName));
                            if (RecoverBlueStacksInstance(inst))
                            {
                                onlineDevices = GetAllConnectedAdbDevices();
                            }
                        }
                    }
                }

                // 2. Also check universal discovery ports
                foreach (int p in new int[] { 5555, 5556, 5585, 5595 })
                {
                    string ep = "127.0.0.1:" + p;
                    if (!onlineDevices.Contains(ep) && IsTcpPortOpen(p))
                    {
                        RunAdb("connect " + ep);
                        if (RunAdbDevice(ep, "get-state").Trim().Equals("device", StringComparison.OrdinalIgnoreCase))
                            onlineDevices.Add(ep);
                    }
                }

                // 3. For all online devices, maintain apps and services
                string targetPkg = GetTargetPackage();
                foreach (string dev in onlineDevices)
                {
                    if (!autoRecoverBlueStacks) continue;
                    string batchCmd = string.Format("shell \"getprop sys.boot_completed; echo '###'; settings get secure enabled_accessibility_services; echo '###'; pidof {0}; echo '###'; pidof {1}; echo '###'; dumpsys window | grep mCurrentFocus\"", targetPkg, PKG_BOT_ASSISTANT);
                    string[] parts = RunAdbDevice(dev, batchCmd).Split(new string[] { "###" }, StringSplitOptions.None);
                    if (parts.Length >= 5 && parts[0].Trim() == "1")
                    {
                        EnsureAccessibilityEnabled(dev, parts[1]);
                        bool isLauncher = parts[4].Contains("launcher") || parts[4].Contains("com.bluestacks");
                        bool relaunched = false;
                        if (string.IsNullOrEmpty(parts[2].Trim()) || isLauncher)
                        {
                            LaunchTargetAppOnDevice(dev);
                            relaunched = true;
                            Thread.Sleep(2000);
                        }
                        if (relaunched || string.IsNullOrEmpty(parts[3].Trim()))
                        {
                            StartOrStopOverlayService(dev, "START");
                        }
                    }
                }

                isAdbConnected = onlineDevices.Count > 0;
                if (isAdbConnected && (string.IsNullOrEmpty(adbPort) || !onlineDevices.Contains(adbPort)))
                {
                    adbPort = onlineDevices[0];
                }

                SafeBeginInvoke(delegate
                {
                    UpdatePortLabel(isAdbConnected
                        ? string.Format("Connected ({0} device{1})", onlineDevices.Count, onlineDevices.Count == 1 ? "" : "s")
                        : "Waiting for ADB / BlueStacks...");
                    if (!isRunning && !isAutoOffering && !isBoosting)
                    {
                        lblStatus.Text = isAdbConnected
                            ? (autoRecoverBlueStacks ? "WATCHDOG ACTIVE" : "READY (Watchdog Paused)")
                            : "WAITING FOR DEVICE";
                        lblStatus.ForeColor = isAdbConnected && autoRecoverBlueStacks ? Color.FromArgb(76, 175, 80) : Color.FromArgb(140, 140, 150);
                    }
                });

                if ((checkCycle == 1 || checkCycle % 5 == 0) && onlineDevices.Count > 0)
                {
                    Log(string.Format("[Watchdog] Status OK: {0} connected device(s) on {1}.", onlineDevices.Count, GetTargetAppLabel()));
                }
            }
            catch { }
            Thread.Sleep(10000);
        }
    }

    private void BtnWatchdog_Click(object sender, EventArgs e)
    {
        autoRecoverBlueStacks = !autoRecoverBlueStacks;
        UpdateWatchdogButtonState();
        SaveStatsToFile();
        Log(string.Format("[Watchdog] {0} by user.", autoRecoverBlueStacks ? "ACTIVATED (ON)" : "PAUSED (OFF)"));
        new Thread(() =>
        {
            string action = autoRecoverBlueStacks ? "START" : "STOP";
            foreach (string dev in GetAllConnectedAdbDevices()) StartOrStopOverlayService(dev, action);
        }) { IsBackground = true }.Start();
    }

    private void UpdateWatchdogButtonState()
    {
        SafeBeginInvoke(delegate
        {
            if (btnWatchdog == null) return;
            btnWatchdog.Text = autoRecoverBlueStacks ? "[ WATCHDOG: ON ]" : "[ WATCHDOG: OFF ]";
            btnWatchdog.ForeColor = autoRecoverBlueStacks ? Color.FromArgb(76, 175, 80) : Color.FromArgb(160, 160, 170);
            btnWatchdog.BackColor = autoRecoverBlueStacks ? Color.FromArgb(12, 32, 18) : Color.FromArgb(16, 16, 20);
            btnWatchdog.FlatAppearance.BorderColor = autoRecoverBlueStacks ? Color.FromArgb(46, 125, 50) : Color.FromArgb(34, 34, 40);
        });
    }

    private void BtnRestartApp_Click(object sender, EventArgs e)
    {
        Log("[Action] Restarting " + GetTargetAppLabel() + " across connected devices...");
        new Thread(() =>
        {
            EnsureConnected();
            string pkg = GetTargetPackage();
            foreach (string dev in GetAllConnectedAdbDevices())
            {
                ForceStopTargetApp(dev, pkg);
                Thread.Sleep(600);
                LaunchTargetAppOnDevice(dev);
                EnsureAccessibilityEnabled(dev, RunAdbDevice(dev, "shell settings get secure enabled_accessibility_services"));
                StartOrStopOverlayService(dev, "START");
            }
            Log("[App] Clean restart complete (no uninstall/data loss).");
        }) { IsBackground = true }.Start();
    }

    private void BtnResetAdId_Click(object sender, EventArgs e)
    {
        Log("[Action] Resetting Advertising ID...");
        new Thread(() =>
        {
            EnsureConnected();
            RunAdb("shell pm clear com.google.android.gms");
            Thread.Sleep(800);
            RunAdb("shell am broadcast -a com.google.android.gms.ads.identifier.service.START");
            Log("[Ad ID] Google Advertising ID cache cleared.");
        }) { IsBackground = true }.Start();
    }

    private void BtnSendLastSS_Click(object sender, EventArgs e)
    {
        Log("[Screenshot] Capturing screen & syncing to device Gallery...");
        new Thread(() =>
        {
            EnsureConnected();
            string destName = string.Format("Screenshot_{0:yyyyMMdd_HHmmss}.png", DateTime.Now);
            string remotePath = "/sdcard/Pictures/Screenshots/" + destName;
            string dcimPath = "/sdcard/DCIM/Screenshots/" + destName;
            int capturedCount = 0;
            foreach (string dev in GetAllConnectedAdbDevices())
            {
                try
                {
                    RunAdbDevice(dev, "shell mkdir -p /sdcard/Pictures/Screenshots /sdcard/DCIM/Screenshots");

                    // If file picker (DocumentsUI) is in foreground, dismiss it first
                    // so we capture the ACTUAL app screen, not the picker overlay
                    string topActivity = RunAdbDevice(dev, "shell dumpsys window windows").ToLower();
                    if (topActivity.Contains("com.android.documentsui") || topActivity.Contains("pickactivity") || topActivity.Contains("filepickerdialog"))
                    {
                        Log("[Screenshot] File picker detected on " + dev + " - dismissing to capture real screen...");
                        RunAdbDevice(dev, "shell input keyevent KEYCODE_BACK");
                        Thread.Sleep(800); // wait for picker to close and app to come to foreground
                    }

                    string tempPath = "/data/local/tmp/_ss_tmp.png";
                    RunAdbDevice(dev, "shell rm -f " + tempPath);

                    // Use dedicated 15-second timeout screencap into temp path first
                    bool ok = RunAdbScreencap(dev, tempPath);
                    if (!ok)
                    {
                        Log(string.Format("[Screenshot] screencap timed out on {0}, retrying...", dev));
                        Thread.Sleep(500);
                        ok = RunAdbScreencap(dev, tempPath);
                    }

                    long sz = 0;
                    if (ok)
                    {
                        string sizeOut = RunAdbDevice(dev, "shell stat -c %s " + tempPath).Trim();
                        long.TryParse(sizeOut, out sz);
                    }

                    if (sz > 10000)
                    {
                        // Safely copy verified valid screenshot into destination directories
                        RunAdbDevice(dev, string.Format("shell \"cp {0} {1} && cp {0} {2} && rm -f {0}\"", tempPath, remotePath, dcimPath));

                        // Broadcast media scanner so Gallery, Recent Images, and Photo Picker index the real image immediately
                        RunAdbDevice(dev, "shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d \"file://" + remotePath + "\"");
                        RunAdbDevice(dev, "shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d \"file://" + dcimPath + "\"");
                        RunAdbDevice(dev, "shell cmd media.scanner scan " + remotePath + " 2>/dev/null; true");
                        RunAdbDevice(dev, "shell cmd media.scanner scan " + dcimPath + " 2>/dev/null; true");

                        capturedCount++;
                        Log(string.Format("[Screenshot] Captured {0} bytes on {1} -> {2}", sz, dev, destName));
                    }
                    else
                    {
                        Log(string.Format("[Screenshot] ERROR: Captured image invalid ({0} bytes) on {1}.", sz, dev));
                        RunAdbDevice(dev, "shell rm -f " + tempPath + " " + remotePath + " " + dcimPath);
                    }
                }
                catch (Exception ex)
                {
                    Log("[Screenshot] Error on " + dev + ": " + ex.Message);
                }
            }
            if (capturedCount > 0)
                Log(string.Format("[Screenshot] Done! Saved to Gallery on {0} device(s): {1}", capturedCount, destName));
            else
                Log("[Screenshot] WARNING: Screenshot failed on all devices. Check ADB connection.");
        }) { IsBackground = true }.Start();
    }

    // Dedicated screencap with 15-second timeout.
    // NOTE: screencap writes to the DEVICE file (not stdout), so we must NOT redirect stdout.
    // Redirecting stdout from screencap causes a binary pipe deadlock even though -p writes to path.
    private bool RunAdbScreencap(string deviceId, string remotePath)
    {
        try
        {
            using (System.Diagnostics.Process p = new System.Diagnostics.Process())
            {
                p.StartInfo.FileName = adbExe;
                // screencap -p <remotePath> writes to file on device, NOT to stdout
                p.StartInfo.Arguments = string.Format("-s {0} shell screencap -p {1}", deviceId, remotePath);
                p.StartInfo.UseShellExecute = false;
                // DO NOT redirect stdout/stderr — avoids binary pipe deadlock with screencap
                p.StartInfo.RedirectStandardOutput = false;
                p.StartInfo.RedirectStandardError = false;
                p.StartInfo.CreateNoWindow = true;
                p.Start();
                bool exited = p.WaitForExit(15000); // 15 seconds
                if (!exited)
                {
                    try { p.Kill(); } catch { }
                    try { p.WaitForExit(500); } catch { }
                    return false;
                }
                return p.ExitCode == 0;
            }
        }
        catch { return false; }
    }

    private void BtnToggle_Click(object sender, EventArgs e)
    {
        if (!isRunning) StartBot(); else StopBot();
    }

    private void StartBot()
    {
        isRunning = true;
        collectGemNoAdCount = 0;
        isPending25thGemStop = false;
        hasCountedThis25GemCycle = false;
        lastProgressTime = DateTime.Now;

        SafeBeginInvoke(delegate
        {
            btnToggle.Text = "[ || ]  STOP BOT (RUNNING)";
            btnToggle.BackColor = Color.FromArgb(65, 22, 24);
            btnToggle.FlatAppearance.BorderColor = Color.FromArgb(145, 50, 55);
            lblStatus.Text = "RUNNING (" + GetTargetAppLabel() + ")";
        });

        if (autoMuteEnabled) SilenceAudio(true);

        botThread = new Thread(new ThreadStart(BotWorkerLoop)) { IsBackground = true };
        botThread.Start();
        Log(">>> Bot started for " + GetTargetAppLabel() + ".");
    }

    private void StopBot()
    {
        isRunning = false;
        isPending25thGemStop = false;
        hasCountedThis25GemCycle = false;

        SafeBeginInvoke(delegate
        {
            btnToggle.Text = "[ > ]  START BOT";
            btnToggle.BackColor = Color.FromArgb(14, 48, 34);
            btnToggle.FlatAppearance.BorderColor = Color.FromArgb(24, 110, 74);
            if (!lblStatus.Text.Contains("25 Gems"))
                lblStatus.Text = isAdbConnected ? "READY (" + adbPort + ")" : "WAITING FOR ADB";
        });

        if (autoMuteEnabled) SilenceAudio(false);
        Log("<<< Bot stopped.");
    }

    private void SilenceAudio(bool mute)
    {
        try { RunAdb("shell cmd media_session volume --stream 3 --set " + (mute ? "0" : "12")); } catch { }
    }

    private string GetCurrentFocus()
    {
        string dump = RunAdb("shell \"dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity'\"");
        Match m = Regex.Match(dump, @"ActivityRecord\{[^\r\n]+");
        if (m.Success) return m.Value;
        Match wm = Regex.Match(RunAdb("shell \"dumpsys window | grep mCurrentFocus\""), @"mCurrentFocus=Window\{[^\r\n]+");
        return wm.Success ? wm.Value : "";
    }

    private string ExtractPackageName(string rawFocus)
    {
        Match m = Regex.Match(rawFocus ?? "", @"([a-zA-Z0-9_]+(?:\.[a-zA-Z0-9_]+)+)/");
        return m.Success ? m.Groups[1].Value : "";
    }

    private string DumpAndReadXml(string remotePath)
    {
        try
        {
            RunAdb(string.Format("shell uiautomator dump --compressed {0}", remotePath));
            string content = RunAdb("shell cat " + remotePath);
            return (!string.IsNullOrEmpty(content) && content.Contains("<hierarchy")) ? content : "";
        }
        catch { return ""; }
    }

    private bool TryFindNodeBounds(string xml, string keyword, out int centerX, out int centerY)
    {
        centerX = 0; centerY = 0;
        if (string.IsNullOrEmpty(xml) || string.IsNullOrEmpty(keyword)) return false;
        int safeBottom = (int)(devHeight * 0.97);

        MatchCollection nodes = Regex.Matches(xml, @"<node\s+[^>]*>", RegexOptions.Singleline);
        for (int pass = 0; pass < 4; pass++)
        {
            foreach (Match n in nodes)
            {
                string tag = n.Value;
                bool isClickable = tag.IndexOf("clickable=\"true\"", StringComparison.OrdinalIgnoreCase) >= 0 || tag.IndexOf("Button", StringComparison.OrdinalIgnoreCase) >= 0;
                bool isExact = tag.IndexOf(string.Format("text=\"{0}\"", keyword), StringComparison.OrdinalIgnoreCase) >= 0 ||
                               tag.IndexOf(string.Format("content-desc=\"{0}\"", keyword), StringComparison.OrdinalIgnoreCase) >= 0;
                bool isContains = !tag.ToLower().Contains("to start using") && tag.IndexOf(keyword, StringComparison.OrdinalIgnoreCase) >= 0;

                bool ok = (pass == 0 && isExact && isClickable) || (pass == 1 && isExact) ||
                          (pass == 2 && isContains && isClickable) || (pass == 3 && isContains);
                if (!ok) continue;

                Match bm = Regex.Match(tag, @"bounds=""\[(\d+),(\d+)\]\[(\d+),(\d+)\]""");
                if (bm.Success)
                {
                    int x1 = int.Parse(bm.Groups[1].Value), y1 = int.Parse(bm.Groups[2].Value);
                    int x2 = int.Parse(bm.Groups[3].Value), y2 = int.Parse(bm.Groups[4].Value);
                    if (y2 < safeBottom && x2 > x1) { centerX = (x1 + x2) / 2; centerY = (y1 + y2) / 2; return true; }
                }
            }
        }
        return false;
    }

    private bool isLastDismissCrash = false;

    private bool DismissAppDialogs()
    {
        isLastDismissCrash = false;
        string cur = GetCurrentFocus();
        if (string.IsNullOrEmpty(cur) || IsKnownQuizApp(cur)) return false;

        string xml = DumpAndReadXml("/sdcard/app_popup.xml");
        if (string.IsNullOrEmpty(xml)) return false;

        bool isCrash = Regex.IsMatch(xml, @"(keeps stopping|has stopped|isn't responding)", RegexOptions.IgnoreCase);
        foreach (Match n in Regex.Matches(xml, @"<node\s+[^>]*>", RegexOptions.Singleline))
        {
            string tag = n.Value;
            bool match = (isCrash && Regex.IsMatch(tag, @"text=""[^""]*(Close app|Close|OK)\b[^""]*""", RegexOptions.IgnoreCase)) ||
                         Regex.IsMatch(tag, @"text=""[^""]*(don.*?allow|\bdeny\b|Not now|Maybe later|Dismiss|OK|Continue|Accept|Agree|Got it|While using the app|Only this time|\bAllow\b)\b[^""]*""", RegexOptions.IgnoreCase) ||
                         Regex.IsMatch(tag, @"content-desc=""[^""]*(Close|Dismiss|OK|Continue|Accept|Agree|Got it|Allow)\b[^""]*""", RegexOptions.IgnoreCase);
            if (!match) continue;

            Match bm = Regex.Match(tag, @"bounds=""\[(\d+),(\d+)\]\[(\d+),(\d+)\]""");
            if (bm.Success)
            {
                int cx = (int.Parse(bm.Groups[1].Value) + int.Parse(bm.Groups[3].Value)) / 2;
                int cy = (int.Parse(bm.Groups[2].Value) + int.Parse(bm.Groups[4].Value)) / 2;
                if (cx > 0 && cy > 0 && cx < devWidth && cy < devHeight)
                {
                    Log(string.Format("[Dialog Dismiss] Auto-dismissing dialog button at ({0}, {1})...", cx, cy));
                    AdbTap(cx, cy);
                    if (isCrash) isLastDismissCrash = true;
                    Thread.Sleep(1100);
                    return true;
                }
            }
        }
        return false;
    }

    private void StartUsageBooster(int minutes)
    {
        if (isBoosting) { Log("[Booster] Already running. Click Stop Boost first."); return; }
        if (!isAdbConnected) { Log("[Booster] Connect ADB first."); return; }
        isBoosting = true;
        boostSecondsRemaining = minutes * 60;
        boosterThread = new Thread(BoosterWorkerLoop) { IsBackground = true };
        boosterThread.Start(minutes);
    }

    private void BtnBoostStop_Click(object sender, EventArgs e)
    {
        if (isBoosting)
        {
            isBoosting = false;
            Log("[Booster] Usage Booster stopped by user.");
            SafeBeginInvoke(() => lblBoostStatus.Text = "Booster: Stopped");
        }
    }

    private void BoosterWorkerLoop(object param)
    {
        int minutes = (int)param;
        try
        {
            string cur = GetCurrentFocus();
            boostTargetPackage = (!string.IsNullOrEmpty(cur) && !IsKnownQuizApp(cur) && !cur.Contains("launcher")) ? ExtractPackageName(cur) : "";
            int ticks = 0;
            while (isBoosting && boostSecondsRemaining > 0)
            {
                SafeBeginInvoke(() => lblBoostStatus.Text = string.Format("Boost {0:D2}:{1:D2} ({2})", boostSecondsRemaining / 60, boostSecondsRemaining % 60, string.IsNullOrEmpty(boostTargetPackage) ? "App" : boostTargetPackage));
                if (ticks % 10 == 0) DismissAppDialogs();
                if (ticks % 15 == 0) AdbSwipeRef(450, 700, 450, 725, 120);
                Thread.Sleep(1000);
                boostSecondsRemaining--;
                ticks++;
            }
            if (isBoosting)
            {
                LaunchTargetAppOnDevice(adbPort);
                Thread.Sleep(2500);
                SafeBeginInvoke(() => lblBoostStatus.Text = string.Format("Boost complete ({0}m)!", minutes));
            }
        }
        finally { isBoosting = false; }
    }

    private void BtnAutoOffer_Click(object sender, EventArgs e) { StartAutoOfferClaimer(); }
    private void BtnAutoOfferStop_Click(object sender, EventArgs e) { StopAutoOfferClaimer(); }

    private void StartAutoOfferClaimer()
    {
        if (isAutoOffering)
        {
            Log("[💎 Claimer] Auto-Offer Claimer is already running!");
            return;
        }
        if (!EnsureConnected())
        {
            if (autoRecoverBlueStacks && !IsBlueStacksProcessRunning())
            {
                Log("[🛡️ Watchdog] BlueStacks not running. Auto-launching BlueStacks before starting claimer...");
                EnsureBlueStacksAliveAndReady("Auto-Offer Claimer Startup");
            }
            if (!EnsureConnected())
            {
                Log("[💎 Claimer] ADB not connected. Check emulator connection.");
                return;
            }
        }
        isAdbConnected = true;
        isAutoOffering = true;
        isBoosting = true;
        Log("[💎 Claimer] Starting 1-Click Auto-Offer & Coin Claimer...");
        SafeBeginInvoke(() => {
            if (lblBoostStatus != null) lblBoostStatus.Text = "Claimer: Initializing...";
        });
        autoOfferThread = new Thread(AutoOfferWorkerLoop) { IsBackground = true };
        autoOfferThread.Start();
    }

    private void StopAutoOfferClaimer()
    {
        if (isAutoOffering || isBoosting)
        {
            isAutoOffering = false;
            isBoosting = false;
            Log("[💎 Claimer] Auto-Offer Claimer stopped by user.");
            SafeBeginInvoke(() => {
                if (lblBoostStatus != null)
                {
                    lblBoostStatus.Text = "Claimer: Stopped";
                    lblBoostStatus.ForeColor = Color.FromArgb(135, 135, 140);
                }
            });
        }
    }

    private bool IsUsageCompleted(string xml, int buttonY = 0)
    {
        string cardScope = xml;
        if (buttonY > 0)
        {
            try
            {
                MatchCollection matches = Regex.Matches(xml, @"<node [^>]*text=""([^""]*)""[^>]*bounds=""\[(\d+),(\d+)\]\[(\d+),(\d+)\]""");
                List<string> nearTexts = new List<string>();
                foreach (Match m in matches)
                {
                    string t = m.Groups[1].Value.Trim();
                    if (string.IsNullOrEmpty(t)) continue;
                    int y1 = int.Parse(m.Groups[3].Value);
                    int y2 = int.Parse(m.Groups[5].Value);
                    int yc = (y1 + y2) / 2;
                    if (Math.Abs(yc - buttonY) <= 140)
                    {
                        nearTexts.Add(t);
                    }
                }
                if (nearTexts.Count > 0) cardScope = string.Join(" | ", nearTexts);
            }
            catch { }
        }

        return cardScope.IndexOf("Usage verified", StringComparison.OrdinalIgnoreCase) >= 0 ||
               Regex.IsMatch(cardScope, @"\b100\s*%") ||
               Regex.IsMatch(cardScope, @"\b0s\s*more\s*needed", RegexOptions.IgnoreCase);
    }

    private int ParseSecondsNeeded(string xml, int buttonY = 0)
    {
        string cardScope = xml;
        if (buttonY > 0)
        {
            try
            {
                MatchCollection matches = Regex.Matches(xml, @"<node [^>]*text=""([^""]*)""[^>]*bounds=""\[(\d+),(\d+)\]\[(\d+),(\d+)\]""");
                List<string> nearTexts = new List<string>();
                foreach (Match m in matches)
                {
                    string t = m.Groups[1].Value.Trim();
                    if (string.IsNullOrEmpty(t)) continue;
                    int y1 = int.Parse(m.Groups[3].Value);
                    int y2 = int.Parse(m.Groups[5].Value);
                    int yc = (y1 + y2) / 2;
                    if (Math.Abs(yc - buttonY) <= 140)
                    {
                        nearTexts.Add(t);
                    }
                }
                if (nearTexts.Count > 0)
                {
                    cardScope = string.Join(" | ", nearTexts);
                    Log(string.Format("[💎 Claimer] Card node texts near Y={0}: {1}", buttonY, cardScope.Length > 100 ? cardScope.Substring(0, 100) + "..." : cardScope));
                }
            }
            catch { }
        }

        if (cardScope.IndexOf("Usage verified", StringComparison.OrdinalIgnoreCase) >= 0 ||
            Regex.IsMatch(cardScope, @"\b100\s*%") ||
            Regex.IsMatch(cardScope, @"\b0s\s*more\s*needed", RegexOptions.IgnoreCase))
        {
            return 0;
        }

        Match m1 = Regex.Match(cardScope, @"(\d+)\s*m(?:in)?\s*(\d+)\s*s(?:ec)?\s*more\s*needed", RegexOptions.IgnoreCase);
        if (m1.Success)
        {
            int mins = int.Parse(m1.Groups[1].Value);
            int secs = int.Parse(m1.Groups[2].Value);
            int total = (mins * 60) + secs;
            return total > 0 ? total + 4 : 0;
        }

        Match m1b = Regex.Match(cardScope, @"(\d+)\s*m(?:in)?\s*more\s*needed", RegexOptions.IgnoreCase);
        if (m1b.Success)
        {
            int mins = int.Parse(m1b.Groups[1].Value);
            return mins > 0 ? (mins * 60) + 4 : 0;
        }

        Match m2 = Regex.Match(cardScope, @"(\d+)\s*s(?:ec)?\s*more\s*needed", RegexOptions.IgnoreCase);
        if (m2.Success)
        {
            int secs = int.Parse(m2.Groups[1].Value);
            return secs > 0 ? secs + 4 : 0;
        }

        Match mProgress = Regex.Match(cardScope, @"(\d+)\s*m\s*(\d+)\s*s\s*\/\s*(\d+)\s*m\s*(\d+)\s*s", RegexOptions.IgnoreCase);
        if (mProgress.Success)
        {
            int doneSec = int.Parse(mProgress.Groups[1].Value) * 60 + int.Parse(mProgress.Groups[2].Value);
            int totalSec = int.Parse(mProgress.Groups[3].Value) * 60 + int.Parse(mProgress.Groups[4].Value);
            int diff = totalSec - doneSec;
            if (diff > 0) return diff + 4;
            return 0;
        }

        Match mProgressSec = Regex.Match(cardScope, @"(\d+)\s*s\s*\/\s*(\d+)\s*m\s*(\d+)\s*s", RegexOptions.IgnoreCase);
        if (mProgressSec.Success)
        {
            int doneSec = int.Parse(mProgressSec.Groups[1].Value);
            int totalSec = int.Parse(mProgressSec.Groups[3].Value) * 60 + int.Parse(mProgressSec.Groups[4].Value);
            int diff = totalSec - doneSec;
            if (diff > 0) return diff + 4;
            return 0;
        }

        Match m3 = Regex.Match(cardScope, @"Use\s*app\s*for\s*(\d+)\s*(?:more\s*)?(?:min|minutes)", RegexOptions.IgnoreCase);
        if (m3.Success)
        {
            int mins = int.Parse(m3.Groups[1].Value);
            return mins > 0 ? (mins * 60) + 4 : 0;
        }

        Match m4 = Regex.Match(cardScope, @"using\s*it\s*for\s*(\d+)\s*(?:min|minutes)", RegexOptions.IgnoreCase);
        if (m4.Success)
        {
            int mins = int.Parse(m4.Groups[1].Value);
            return mins > 0 ? (mins * 60) + 4 : 0;
        }

        return 0;
    }

    private bool FindAndClickReadyOfferCard(string xml, HashSet<string> skippedApps, out string clickedTitle)
    {
        clickedTitle = "";
        try
        {
            MatchCollection titleNodes = Regex.Matches(xml, @"<node [^>]*text=""([^""]+)""[^>]*bounds=""\[(\d+),(\d+)\]\[(\d+),(\d+)\]""");

            foreach (Match tm in titleNodes)
            {
                string rawTitle = tm.Groups[1].Value.Trim();
                string cleanTitle = WebUtility.HtmlDecode(rawTitle).Trim();
                int tx1 = int.Parse(tm.Groups[2].Value);
                int ty1 = int.Parse(tm.Groups[3].Value);
                int tx2 = int.Parse(tm.Groups[4].Value);
                int ty2 = int.Parse(tm.Groups[5].Value);

                if (tx1 < 50 || tx1 > 220 || (ty2 - ty1) < 15 || (ty2 - ty1) > 100) continue;

                if (cleanTitle == "Progress" || cleanTitle == "How to Complete" || cleanTitle.Contains("Important Guidelines") ||
                    cleanTitle == "Load More" || cleanTitle == "Pending Offers" || cleanTitle.Contains("active pending") ||
                    cleanTitle == "My Super Offers" || cleanTitle == "Refresh offers" || cleanTitle.Contains("Unlocks in") ||
                    cleanTitle.StartsWith("Installed") || cleanTitle.Contains("ago") || cleanTitle.Contains("Use app for") ||
                    cleanTitle.EndsWith("tasks"))
                    continue;

                if (ty1 < 100 || ty2 > 1540) continue;

                if (skippedApps != null && (skippedApps.Contains(rawTitle) || skippedApps.Contains(cleanTitle)))
                {
                    continue;
                }

                int cardTop = Math.Max(0, ty1 - 30);
                int cardBottom = ty1 + 200;

                MatchCollection cardTexts = Regex.Matches(xml, @"<node [^>]*text=""([^""]+)""[^>]*bounds=""\[(\d+),(\d+)\]\[(\d+),(\d+)\]""");
                List<string> texts = new List<string>();
                foreach (Match cm in cardTexts)
                {
                    string ct = cm.Groups[1].Value.Trim();
                    if (string.IsNullOrEmpty(ct)) continue;
                    int cy1 = int.Parse(cm.Groups[3].Value);
                    int cy2 = int.Parse(cm.Groups[5].Value);
                    if (cy1 >= cardTop && cy2 <= cardBottom + 10)
                    {
                        texts.Add(ct);
                    }
                }

                string cardContent = string.Join(" | ", texts);

                int tasksDone = -1;
                int tasksTotal = -1;
                Match mTasks = Regex.Match(cardContent, @"(\d+)\s*/\s*(\d+)\s*tasks", RegexOptions.IgnoreCase);
                if (mTasks.Success)
                {
                    tasksDone = int.Parse(mTasks.Groups[1].Value);
                    tasksTotal = int.Parse(mTasks.Groups[2].Value);
                }

                bool isMilestonesText = cardContent.IndexOf("All milestones completed", StringComparison.OrdinalIgnoreCase) >= 0;
                bool isCooldown = Regex.IsMatch(cardContent, @"Unlocks\s*in\s*(?:\d+h|\d+m|[1-9]\d*s)", RegexOptions.IgnoreCase);
                bool isReady = Regex.IsMatch(cardContent, @"(?:Use\s*app\s*for|Claim|Collect|Open\s*App|Usage\s*verified|Ready)", RegexOptions.IgnoreCase);

                if (tasksDone >= 0 && tasksTotal > 0 && tasksDone >= tasksTotal)
                {
                    Log(string.Format("[Claimer] '{0}' is 100% completed ({1}/{2} tasks). Skipping.", cleanTitle, tasksDone, tasksTotal));
                    if (skippedApps != null) { skippedApps.Add(rawTitle); skippedApps.Add(cleanTitle); }
                    continue;
                }

                if (isCooldown)
                {
                    if (skippedApps != null) { skippedApps.Add(rawTitle); skippedApps.Add(cleanTitle); }
                    continue;
                }

                if (isReady)
                {
                    int tapX = 450;
                    int tapY = ty1 + 75;
                    clickedTitle = cleanTitle;
                    if (skippedApps != null) { skippedApps.Add(rawTitle); skippedApps.Add(cleanTitle); }
                    Log(string.Format("[💎 Claimer] Found ready offer '{0}'! Opening card at ({1}, {2})...", cleanTitle, tapX, tapY));
                    AdbTap(tapX, tapY);
                    return true;
                }

                if (isMilestonesText && (tasksDone < tasksTotal || tasksDone < 0))
                {
                    int tapX = 450;
                    int tapY = ty1 + 75;
                    clickedTitle = cleanTitle;
                    if (skippedApps != null) { skippedApps.Add(rawTitle); skippedApps.Add(cleanTitle); }
                    string taskInfo = (tasksDone >= 0 && tasksTotal > 0) ? string.Format(" ({0}/{1} tasks)", tasksDone, tasksTotal) : "";
                    Log(string.Format("[🎁 Bonus Verifier] '{0}'{1} has pending Bonus! Opening card to verify...", cleanTitle, taskInfo));
                    AdbTap(tapX, tapY);
                    return true;
                }
            }
        }
        catch (Exception ex)
        {
            Log("[Claimer] Error scanning offer cards: " + ex.Message);
        }

        return false;
    }

    private bool CheckAndClickLoadMore(string xml)
    {
        try
        {
            if (string.IsNullOrEmpty(xml)) return false;

            Match nodeMatch = Regex.Match(xml, @"<node\b[^>]*(?:text|content-desc)=""[^""]*Load More[^""]*""[^>]*>", RegexOptions.IgnoreCase);
            if (nodeMatch.Success)
            {
                string tag = nodeMatch.Value;
                int tapX = 450;
                int tapY = 1450;

                Match bMatch = Regex.Match(tag, @"bounds=""\[(\d+),(\d+)\]\[(\d+),(\d+)\]""");
                if (bMatch.Success)
                {
                    int x1 = int.Parse(bMatch.Groups[1].Value);
                    int y1 = int.Parse(bMatch.Groups[2].Value);
                    int x2 = int.Parse(bMatch.Groups[3].Value);
                    int y2 = int.Parse(bMatch.Groups[4].Value);
                    if (x2 > x1 && y2 > y1 && (x2 - x1) > 50 && (y2 - y1) > 15)
                    {
                        tapX = (x1 + x2) / 2;
                        tapY = (y1 + y2) / 2;
                    }
                }

                Log(string.Format("[💎 Claimer] Found 'Load More' button at ({0}, {1}). Tapping to reveal additional active offers...", tapX, tapY));
                AdbTap(tapX, tapY);
                Thread.Sleep(3000);
                return true;
            }
        }
        catch (Exception ex)
        {
            Log("[Claimer] Error in CheckAndClickLoadMore: " + ex.Message);
        }
        return false;
    }

    private void ProcessOfferAppDetails(string targetTitle, HashSet<string> cycleCompletedApps, HashSet<string> cooldownBonusApps = null)
    {
        int consecutiveLoops = 0;
        int scrollAttempts = 0;
        int consecutiveVerifyTaps = 0;
        string appTitle = targetTitle ?? "";
        string offerPkg = "";

        try
        {
            while (isAutoOffering && consecutiveLoops < 20)
            {
                consecutiveLoops++;
                string xml = DumpAndReadXml("/sdcard/detail_chk.xml");
                if (string.IsNullOrEmpty(xml))
                {
                    Thread.Sleep(1000);
                    continue;
                }

                Match tm = Regex.Match(xml, @"<node[^>]*class=""android\.widget\.TextView""[^>]*bounds=""\[102,\d+\]\[\d+,\d+\]""[^>]*text=""([^""]+)""");
                if (tm.Success) appTitle = tm.Groups[1].Value.Trim();
                if (string.IsNullOrEmpty(appTitle))
                {
                    Match tm2 = Regex.Match(xml, @"<node[^>]*text=""([^""]+)""[^>]*class=""android\.widget\.TextView""");
                    if (tm2.Success) appTitle = tm2.Groups[1].Value.Trim();
                }

                MatchCollection pms = Regex.Matches(xml, @"<node[^>]*text=""([a-zA-Z0-9_]+(?:\.[a-zA-Z0-9_]+)+)""");
                foreach (Match pm in pms)
                {
                    string candidate = pm.Groups[1].Value.Trim();
                    if (candidate != "com.quiz.earn.money.game" &&
                        !candidate.StartsWith("android.") &&
                        !candidate.StartsWith("com.android.") &&
                        !candidate.StartsWith("com.google.") &&
                        !candidate.Contains("accessibility"))
                    {
                        offerPkg = candidate;
                        break;
                    }
                }

                // 1. Check for Claim / Collect Button first! (Matches "Claim", "CLAIM", "Collect", "COLLECT", "Collect Coins")
                int cx, cy;
                bool hasClaimBtn = (TryFindNodeBounds(xml, "Claim", out cx, out cy) ||
                                    TryFindNodeBounds(xml, "CLAIM", out cx, out cy) ||
                                    TryFindNodeBounds(xml, "Collect Coins", out cx, out cy) ||
                                    TryFindNodeBounds(xml, "Collect", out cx, out cy) ||
                                    TryFindNodeBounds(xml, "COLLECT", out cx, out cy) ||
                                    TryFindNodeBounds(xml, "Claim Coins", out cx, out cy) ||
                                    TryFindNodeBounds(xml, "Claim Reward", out cx, out cy)) && cy < 1560;
                if (hasClaimBtn)
                {
                    int rewardCoins = 100;
                    try
                    {
                        MatchCollection cms = Regex.Matches(xml, @"<node[^>]*text=""([0-9]{2,5})""[^>]*bounds=""\[(\d+),(\d+)\]\[(\d+),(\d+)\]""");
                        int bestDist = int.MaxValue;
                        foreach (Match cm in cms)
                        {
                            int ny = (int.Parse(cm.Groups[3].Value) + int.Parse(cm.Groups[5].Value)) / 2;
                            int dist = Math.Abs(ny - cy);
                            if (dist < bestDist && dist < 160)
                            {
                                bestDist = dist;
                                int val;
                                if (int.TryParse(cm.Groups[1].Value, out val) && val > 0)
                                {
                                    rewardCoins = val;
                                }
                            }
                        }
                    }
                    catch { }

                    Log(string.Format("[💎 Claimer] Milestone / Bonus ready! Tapping CLAIM (+{0} Coins) at ({1}, {2})...", rewardCoins, cx, cy));
                    AdbTap(cx, cy);

                    lock (statsLock)
                    {
                        todayCoinsClaimed += rewardCoins;
                        totalCoinsClaimed += rewardCoins;
                        todayOffersClaimed++;
                        totalOffersClaimed++;
                    }
                    string titleTag = string.IsNullOrEmpty(appTitle) ? (string.IsNullOrEmpty(targetTitle) ? "Offer Milestone" : targetTitle) : appTitle;
                    AddClaimHistory(string.Format("{0} (+{1:N0} Coins)", titleTag, rewardCoins));
                    SaveStatsToFile();
                    UpdateDataDashboard();

                    consecutiveVerifyTaps = 0;
                    Thread.Sleep(2000);

                    // Check for reward confirmation popup / dialog
                    string popupXml = DumpAndReadXml("/sdcard/claim_popup.xml");
                    int px, py;
                    if (TryFindNodeBounds(popupXml, "Awesome", out px, out py) ||
                        TryFindNodeBounds(popupXml, "OK", out px, out py) ||
                        TryFindNodeBounds(popupXml, "Collect", out px, out py) ||
                        TryFindNodeBounds(popupXml, "Continue", out px, out py) ||
                        TryFindNodeBounds(popupXml, "Claim", out px, out py))
                    {
                        Log(string.Format("[💎 Claimer] Dismissing reward confirmation dialog at ({0}, {1})...", px, py));
                        AdbTap(px, py);
                        Thread.Sleep(1500);
                    }

                    continue; // Re-check if next milestone unlocked on this same page!
                }

                // 2. Check for "Verify Usage" OR "Open App" Button!
                int vx = 0, vy = 0;
                bool isVerify = TryFindNodeBounds(xml, "Verify Usage", out vx, out vy) ||
                                TryFindNodeBounds(xml, "Verify", out vx, out vy);
                int ox = 0, oy = 0;
                bool isOpenApp = !isVerify && TryFindNodeBounds(xml, "Open App", out ox, out oy);

                int actionX = isVerify ? vx : (isOpenApp ? ox : 0);
                int actionY = isVerify ? vy : (isOpenApp ? oy : 0);

                if ((isVerify || isOpenApp) && actionY < 1560)
                {
                    // CASE A: Button is "Open App" AND usage is already 100% or verified!
                    if (isOpenApp && IsUsageCompleted(xml, actionY))
                    {
                        Log(string.Format("[💎 Claimer] Usage is already 100% / verified! Tapping 'Open App' button at ({0}, {1}) to trigger Claim unlock...", actionX, actionY));
                        AdbTap(actionX, actionY);
                        Thread.Sleep(4500); // Allow target app to launch and Quiz & Earn to register open

                        Log("[💎 Claimer] Returning to Quiz & Earn to collect reward...");
                        LaunchTargetAppOnDevice(adbPort);
                        Thread.Sleep(3000);
                        continue; // Re-check screen: Claim button is now unlocked and Section 1 will claim it!
                    }

                    // CASE B: Button is "Verify Usage", tap it first to submit/refresh usage verification!
                    if (isVerify)
                    {
                        Log(string.Format("[💎 Claimer] Found 'Verify Usage' at ({0}, {1}). Tapping to verify usage...", actionX, actionY));
                        AdbTap(actionX, actionY);
                        Thread.Sleep(4000); // Allow Quiz & Earn to query UsageStats & update card UI

                        string afterVerifyXml = DumpAndReadXml("/sdcard/after_verify.xml");
                        if (!string.IsNullOrEmpty(afterVerifyXml))
                        {
                            xml = afterVerifyXml;
                            int ncx, ncy;
                            bool nHasClaim = (TryFindNodeBounds(xml, "Claim", out ncx, out ncy) ||
                                              TryFindNodeBounds(xml, "CLAIM", out ncx, out ncy) ||
                                              TryFindNodeBounds(xml, "Collect Coins", out ncx, out ncy) ||
                                              TryFindNodeBounds(xml, "Collect", out ncx, out ncy) ||
                                              TryFindNodeBounds(xml, "COLLECT", out ncx, out ncy)) && ncy < 1560;
                            if (nHasClaim)
                            {
                                Log(string.Format("[💎 Claimer] Verified! 'Claim/Collect' button unlocked at ({0}, {1}). Proceeding to claim...", ncx, ncy));
                                continue;
                            }
                        }
                    }

                    int staySeconds = ParseSecondsNeeded(xml, actionY);
                    if (isOpenApp && staySeconds <= 0 && !IsUsageCompleted(xml, actionY))
                    {
                        staySeconds = 65;
                    }

                    if (staySeconds > 0)
                    {
                        Log(string.Format("[💎 Claimer] Usage required: {0}s ({1}m {2}s) in app '{3}'. Launching to complete time...",
                            staySeconds, staySeconds / 60, staySeconds % 60, string.IsNullOrEmpty(offerPkg) ? "target app" : offerPkg));

                        if (isOpenApp)
                        {
                            Log(string.Format("[💎 Claimer] Tapping 'Open App' button on screen at ({0}, {1})...", actionX, actionY));
                            AdbTap(actionX, actionY);
                            Thread.Sleep(3500);
                        }
                        else if (!string.IsNullOrEmpty(offerPkg))
                        {
                            RunAdb(string.Format("shell monkey -p {0} -c android.intent.category.LAUNCHER 1", offerPkg));
                            Thread.Sleep(3500);
                        }
                        else
                        {
                            AdbTap(actionX, actionY);
                            Thread.Sleep(3500);
                        }

                        DismissAppDialogs();

                        string targetPkg = ExtractPackageName(GetCurrentFocus());
                        if (targetPkg == "com.android.vending" || targetPkg.Contains("vending") || targetPkg.Contains("google.android.finsky"))
                        {
                            Log("[Claimer] Google Play Store opened instead of installed offer app. Closing and skipping...");
                            AdbKey(4);
                            Thread.Sleep(600);
                            RunAdb("shell am force-stop com.android.vending");
                            LaunchTargetAppOnDevice(adbPort);
                            Thread.Sleep(1500);
                            if (!string.IsNullOrEmpty(appTitle)) cycleCompletedApps.Add(appTitle);
                            break;
                        }

                        if ((string.IsNullOrEmpty(targetPkg) || targetPkg == "com.quiz.earn.money.game") && !string.IsNullOrEmpty(offerPkg))
                        {
                            Log(string.Format("[Claimer] Target app did not launch on button tap. Direct launching '{0}'...", offerPkg));
                            RunAdb(string.Format("shell monkey -p {0} -c android.intent.category.LAUNCHER 1", offerPkg));
                            Thread.Sleep(3500);
                            targetPkg = ExtractPackageName(GetCurrentFocus());
                        }

                        if (targetPkg == "com.quiz.earn.money.game")
                        {
                            Log("[Claimer] Warning: Target app could not be opened. Returning to offers list...");
                            break;
                        }

                        Log(string.Format("[💎 Claimer] Staying active in app '{0}' for {1}s...", targetPkg, staySeconds));

                        int remaining = staySeconds;
                        int keepAlive = 0;
                        int crashCount = 0;
                        while (isAutoOffering && remaining > 0)
                        {
                            if (autoRecoverBlueStacks && !IsBlueStacksProcessRunning())
                            {
                                EnsureBlueStacksAliveAndReady(string.Format("Offer App Usage ({0})", targetPkg));
                                break;
                            }
                            int rm = remaining / 60;
                            int rs = remaining % 60;
                            string pkgShort = string.IsNullOrEmpty(targetPkg) ? "Offer App" : targetPkg;
                            if (pkgShort.Contains("."))
                            {
                                string[] pts = pkgShort.Split('.');
                                pkgShort = pts[pts.Length - 1];
                            }
                            string status = string.Format("💎 {0:D2}:{1:D2} left ({2})", rm, rs, pkgShort);

                            SafeBeginInvoke(() => {
                                if (lblBoostStatus != null)
                                {
                                    lblBoostStatus.Text = status;
                                    lblBoostStatus.ForeColor = Color.FromArgb(180, 180, 185);
                                }
                            });

                            if (keepAlive == 3 || keepAlive == 7 || (keepAlive > 0 && keepAlive % 10 == 0))
                            {
                                bool dialogDismissed = DismissAppDialogs();
                                if (dialogDismissed && isLastDismissCrash)
                                {
                                    crashCount++;
                                    Log(string.Format("[💎 Claimer] App keeps stopping! Crash count={0}/2 (app={1})", crashCount, targetPkg));
                                    if (crashCount >= 2)
                                    {
                                        Log("[💎 Claimer] App keeps stopping repeatedly! Returning to Quiz & Earn...");
                                        LaunchTargetAppOnDevice(adbPort);
                                        Thread.Sleep(3000);
                                        if (!string.IsNullOrEmpty(appTitle)) cycleCompletedApps.Add(appTitle);
                                        break;
                                    }
                                }
                            }

                            if (keepAlive % 15 == 0)
                            {
                                AdbSwipeRef(450, 700, 450, 720, 120);
                            }

                            Thread.Sleep(1000);
                            remaining--;
                            keepAlive++;
                        }

                        if (!isAutoOffering) return;

                        Log("[💎 Claimer] Required time completed! Returning to Quiz & Earn...");
                        LaunchTargetAppOnDevice(adbPort);
                        Thread.Sleep(3000);

                        consecutiveVerifyTaps = 0;

                        string retXml = DumpAndReadXml("/sdcard/after_usage.xml");
                        int rcx, rcy;
                        bool rHasClaim = (TryFindNodeBounds(retXml, "Claim", out rcx, out rcy) ||
                                          TryFindNodeBounds(retXml, "CLAIM", out rcx, out rcy) ||
                                          TryFindNodeBounds(retXml, "Collect Coins", out rcx, out rcy) ||
                                          TryFindNodeBounds(retXml, "Collect", out rcx, out rcy) ||
                                          TryFindNodeBounds(retXml, "COLLECT", out rcx, out rcy) ||
                                          TryFindNodeBounds(retXml, "Claim Coins", out rcx, out rcy) ||
                                          TryFindNodeBounds(retXml, "Claim Reward", out rcx, out rcy)) && rcy < 1560;
                        if (rHasClaim)
                        {
                            Log(string.Format("[💎 Claimer] Claim button already visible at ({0}, {1})! Claiming...", rcx, rcy));
                            continue;
                        }

                        int rvx, rvy;
                        if ((TryFindNodeBounds(retXml, "Verify Usage", out rvx, out rvy) ||
                             TryFindNodeBounds(retXml, "Verify", out rvx, out rvy)) && rvy < 1560)
                        {
                            Log(string.Format("[💎 Claimer] Returned from app! Tapping 'Verify Usage' at ({0}, {1}) to verify newly elapsed time...", rvx, rvy));
                            AdbTap(rvx, rvy);
                            Thread.Sleep(4000);
                        }
                        continue;
                    }
                    else
                    {
                        if (isVerify)
                        {
                            consecutiveVerifyTaps++;
                            if (consecutiveVerifyTaps <= 2)
                            {
                                Log(string.Format("[💎 Claimer] Usage appears fulfilled. Tapping 'Verify Usage' again ({0}/2) to confirm with server...", consecutiveVerifyTaps));
                                AdbTap(actionX, actionY);
                                Thread.Sleep(4000);
                                continue;
                            }
                        }
                        else if (isOpenApp)
                        {
                            Log(string.Format("[💎 Claimer] Tapping 'Open App' button on screen at ({0}, {1})...", actionX, actionY));
                            AdbTap(actionX, actionY);
                            Thread.Sleep(4500);
                            Log("[💎 Claimer] Returning to Quiz & Earn to check Claim button...");
                            LaunchTargetAppOnDevice(adbPort);
                            Thread.Sleep(3000);
                            continue;
                        }
                    }
                }

                // 3. Scroll Down to Reveal Lower Milestones & "BONUS REWARDS" section
                if (scrollAttempts < 3)
                {
                    scrollAttempts++;
                    Log(string.Format("[💎 Claimer] Scrolling down to inspect BONUS REWARDS & lower milestones ({0}/3)...", scrollAttempts));
                    AdbSwipeRef(450, 1100, 450, 400, 350);
                    Thread.Sleep(1500);
                    continue;
                }

                // 4. Check if the app's remaining milestone or bonus is on cooldown
                string cdStr = "";
                Match cdMatch = Regex.Match(xml, @"text=""Unlocks\s*in\s*""[^>]*>\s*<node[^>]*text=""([0-9hm\s]+)""", RegexOptions.IgnoreCase);
                if (cdMatch.Success) cdStr = cdMatch.Groups[1].Value.Trim();
                else
                {
                    Match cdMatch2 = Regex.Match(xml, @"(?:Unlocks\s*in|After)\s*([0-9hm\s]+)", RegexOptions.IgnoreCase);
                    if (cdMatch2.Success) cdStr = cdMatch2.Groups[1].Value.Trim();
                }

                string displayTitle = string.IsNullOrEmpty(appTitle) ? targetTitle : appTitle;
                if (!string.IsNullOrEmpty(cdStr))
                {
                    Log(string.Format("[🎁 Bonus Verified] '{0}' Bonus is locked on cooldown: Unlocks in {1}. Verified not missed!", displayTitle, cdStr));
                    if (cooldownBonusApps != null)
                    {
                        if (!string.IsNullOrEmpty(targetTitle)) cooldownBonusApps.Add(targetTitle);
                        if (!string.IsNullOrEmpty(appTitle)) cooldownBonusApps.Add(appTitle);
                    }
                }
                else
                {
                    if (xml.Contains("All milestones completed") || xml.Contains("Bonus claimed") || xml.Contains("Completed"))
                    {
                        Log(string.Format("[✅ Verified] '{0}' Bonus & all milestones have been verified and processed.", displayTitle));
                    }
                    else
                    {
                        Log(string.Format("[💎 Claimer] Verified '{0}': No active or claimable bonus found right now. Returning to offers list...", displayTitle));
                    }
                }

                break;
            }
        }
        finally
        {
            if (cycleCompletedApps != null)
            {
                if (!string.IsNullOrEmpty(targetTitle))
                {
                    cycleCompletedApps.Add(targetTitle);
                    cycleCompletedApps.Add(WebUtility.HtmlDecode(targetTitle).Trim());
                }
                if (!string.IsNullOrEmpty(appTitle))
                {
                    cycleCompletedApps.Add(appTitle);
                    cycleCompletedApps.Add(WebUtility.HtmlDecode(appTitle).Trim());
                }
            }

            NavigateBackFromOfferDetails();
        }
    }

    private void NavigateBackFromOfferDetails()
    {
        try
        {
            Log("[💎 Claimer] Exiting offer details page back to offers list...");
            // Step 1: Tap UI back arrow at top-left (bounds [24,60][84,120], center (54, 90) on 900x1600)
            AdbTapRef(54, 90);
            Thread.Sleep(1200);

            // Step 2: Fallback keyevent back
            AdbKey(4);
            Thread.Sleep(1000);

            // Step 3: Check if still on details page
            string checkXml = DumpAndReadXml("/sdcard/check_exit.xml");
            if (!string.IsNullOrEmpty(checkXml) && (checkXml.Contains("REWARDS") || checkXml.Contains("Remove Wrong App") || checkXml.Contains("Initial Install Reward")))
            {
                Log("[💎 Claimer] Still on details page! Retrying tap at (54, 90)...");
                AdbTapRef(54, 90);
                Thread.Sleep(1500);
            }
        }
        catch { }
    }

    private void AutoOfferWorkerLoop()
    {
        try
        {
            Log("[💎 Claimer] Initializing Auto-Offer & Coin Claimer...");
            int totalAppsProcessed = 0;

            List<string> targetDevices = GetAllConnectedAdbDevices();
            if (targetDevices.Count == 0)
            {
                EnsureConnected();
                if (!string.IsNullOrEmpty(adbPort)) targetDevices.Add(adbPort);
            }

            Log(string.Format("[💎 Claimer] Found {0} online instance(s) to process: {1}", targetDevices.Count, string.Join(", ", targetDevices)));

            foreach (string dev in targetDevices)
            {
                if (!isAutoOffering) break;
                adbPort = dev;
                UpdatePortLabel("Claimer: " + adbPort);
                Log(string.Format("[💎 Claimer] === Processing Instance {0} ===", dev));

                HashSet<string> cycleCompletedApps = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
                HashSet<string> cooldownBonusApps = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
                string lastOpenedTitle = "";
                int consecutiveEmptyDumps = 0;
                int unrecognizedCount = 0;

                while (isAutoOffering)
                {
                    if (autoRecoverBlueStacks && !IsBlueStacksProcessRunning())
                    {
                        EnsureBlueStacksAliveAndReady("Auto-Offer Claimer Loop");
                        Thread.Sleep(2000);
                        continue;
                    }

                    string curFocus = GetCurrentFocus();
                    if (!curFocus.Contains("com.quiz.earn.money.game") && !curFocus.Contains("vending"))
                    {
                        LaunchTargetAppOnDevice(dev);
                        Thread.Sleep(2000);
                    }

                    string xml = DumpAndReadXml("/sdcard/offer_flow.xml");
                    if (string.IsNullOrEmpty(xml) || !xml.Contains("<hierarchy"))
                    {
                        consecutiveEmptyDumps++;
                        if (consecutiveEmptyDumps >= 3)
                        {
                            Log(string.Format("[💎 Claimer] Instance {0} ADB unresponsive. Verifying...", dev));
                            EnsureConnected();
                            consecutiveEmptyDumps = 0;
                        }
                        Thread.Sleep(1000);
                        continue;
                    }
                    consecutiveEmptyDumps = 0;

                    // SCREEN A: Inside an offer app details page
                    if (xml.Contains("REWARDS") || xml.Contains("Remove Wrong App") || xml.Contains("Usage Progress") || xml.Contains("Initial Install Reward"))
                    {
                        unrecognizedCount = 0;
                        Log("[💎 Claimer] Inside offer app details page. Processing milestones & bonus...");
                        ProcessOfferAppDetails(lastOpenedTitle, cycleCompletedApps, cooldownBonusApps);
                        totalAppsProcessed++;
                        Thread.Sleep(1500);

                        string verifyXml = DumpAndReadXml("/sdcard/verify_back.xml");
                        if (!string.IsNullOrEmpty(verifyXml) && (verifyXml.Contains("REWARDS") || verifyXml.Contains("Remove Wrong App") || verifyXml.Contains("Initial Install Reward")))
                        {
                            Log("[💎 Claimer] Still on detail page. Calling NavigateBackFromOfferDetails()...");
                            NavigateBackFromOfferDetails();
                        }
                        continue;
                    }

                    // SCREEN B: On "My Super Offers" list
                    if (xml.Contains("My Super Offers") || xml.Contains("Pending Offers") || xml.Contains("How to Complete") || xml.Contains("tasks"))
                    {
                        unrecognizedCount = 0;
                        if (xml.Contains("Failed to load offers"))
                        {
                            Log("[💎 Claimer] Detected 'Failed to load offers'. Tapping Refresh button...");
                            AdbTap(846, 90);
                            Thread.Sleep(3000);
                            continue;
                        }

                        if (Regex.IsMatch(xml, @"Unlocks\s*in\s*0[ms]", RegexOptions.IgnoreCase))
                        {
                            Log("[💎 Claimer] Detected offer with 0m cooldown! Tapping Refresh offers...");
                            AdbTap(846, 90);
                            Thread.Sleep(3000);
                            cooldownBonusApps.Clear();
                            cycleCompletedApps.Clear();
                            continue;
                        }

                        Log("[💎 Claimer] On 'My Super Offers' list. Scanning for ready apps & bonus rewards...");

                        if (CheckAndClickLoadMore(xml))
                        {
                            xml = DumpAndReadXml("/sdcard/offer_flow.xml");
                        }

                        string clickedTitle;
                        bool foundApp = FindAndClickReadyOfferCard(xml, cycleCompletedApps, out clickedTitle);
                        if (foundApp)
                        {
                            lastOpenedTitle = clickedTitle;
                            Thread.Sleep(2500);
                            continue;
                        }
                        else
                        {
                            bool foundOnScroll = false;
                            for (int scrollPass = 1; scrollPass <= 4; scrollPass++)
                            {
                                Log(string.Format("[💎 Claimer] Scrolling down to find more ready offers (page {0}/4)...", scrollPass));
                                AdbSwipeRef(450, 1200, 450, 400, 350);
                                Thread.Sleep(2000);
                                string scrolledXml = DumpAndReadXml("/sdcard/offer_flow.xml");

                                if (CheckAndClickLoadMore(scrolledXml))
                                {
                                    scrolledXml = DumpAndReadXml("/sdcard/offer_flow.xml");
                                }

                                if (Regex.IsMatch(scrolledXml, @"Unlocks\s*in\s*0[ms]", RegexOptions.IgnoreCase))
                                {
                                    Log("[💎 Claimer] Detected offer with 0m cooldown! Tapping Refresh offers...");
                                    AdbTap(846, 90);
                                    Thread.Sleep(3000);
                                    cooldownBonusApps.Clear();
                                    cycleCompletedApps.Clear();
                                    scrolledXml = DumpAndReadXml("/sdcard/offer_flow.xml");
                                }
                                if (FindAndClickReadyOfferCard(scrolledXml, cycleCompletedApps, out clickedTitle))
                                {
                                    lastOpenedTitle = clickedTitle;
                                    foundOnScroll = true;
                                    break;
                                }
                            }

                            if (!foundOnScroll)
                            {
                                MatchCollection cdMatches = Regex.Matches(xml, @"Unlocks\s*in\s*([0-9hm\s]+)", RegexOptions.IgnoreCase);
                                string nextUnlock = "a few hours";
                                if (cdMatches.Count > 0)
                                {
                                    nextUnlock = cdMatches[0].Groups[1].Value.Trim();
                                }
                                Log(string.Format("[💎 Claimer] [{0}] All pending offers are on cooldown or completed! Next offer unlocks in ~{1}.", dev, nextUnlock));

                                SafeBeginInvoke(() => {
                                    if (lblBoostStatus != null)
                                    {
                                        lblBoostStatus.Text = string.Format("Cooldown (Next: {0})", nextUnlock);
                                        lblBoostStatus.ForeColor = Color.FromArgb(150, 150, 155);
                                    }
                                });

                                AdbKey(4);
                                Thread.Sleep(1500);
                                break;
                            }
                            continue;
                        }
                    }

                    // SCREEN C0: 25 Gems Milestone / Start Offer Screen
                    if (xml.Contains("Gems Collected") || (xml.Contains("25") && xml.Contains("1000") && (xml.Contains("Start Offer") || xml.Contains("Start"))))
                    {
                        unrecognizedCount = 0;
                        Log("[💎 Claimer] 25 Gems Milestone reached! Tapping 'Start Offer' button...");
                        int sox, soy;
                        if (TryFindNodeBounds(xml, "Start Offer", out sox, out soy) || TryFindNodeBounds(xml, "Start", out sox, out soy))
                        {
                            AdbTap(sox, soy);
                        }
                        else
                        {
                            AdbTap(450, 1226);
                        }
                        Thread.Sleep(3500);
                        continue;
                    }

                    // SCREEN C: On Quiz screen with "My Offers" button
                    if (xml.Contains("captchas for Guaranteed") || xml.Contains("My Offers") || (xml.Contains("Quiz") && xml.Contains("Super Offer")))
                    {
                        unrecognizedCount = 0;
                        Log("[💎 Claimer] Found Quiz screen. Opening 'My Offers'...");
                        int tx, ty;
                        if (TryFindNodeBounds(xml, "My Offers", out tx, out ty))
                        {
                            AdbTap(tx, ty);
                        }
                        else
                        {
                            AdbTap(450, 832);
                        }
                        Thread.Sleep(3000);
                        continue;
                    }

                    // SCREEN D: On Home screen
                    if (xml.Contains("SUPER OFFER") || (xml.Contains("Guaranteed") && xml.Contains("1,000")) || (xml.Contains("Go to Wallet") && xml.Contains("Tasks")))
                    {
                        unrecognizedCount = 0;
                        Log("[💎 Claimer] On Home screen. Tapping Super Offer card...");
                        int scx, scy;
                        if (TryFindNodeBounds(xml, "SUPER OFFER", out scx, out scy) ||
                            TryFindNodeBounds(xml, "1,000", out scx, out scy) ||
                            TryFindNodeBounds(xml, "Claim", out scx, out scy) ||
                            TryFindNodeBounds(xml, "Collect", out scx, out scy) ||
                            TryFindNodeBounds(xml, "Collect Coins", out scx, out scy))
                        {
                            AdbTap(scx, Math.Min(scy + 40, 640));
                        }
                        else
                        {
                            AdbTap(265, 570);
                        }
                        Thread.Sleep(3000);
                        continue;
                    }

                    // SCREEN E: Ad playing
                    if (xml.Contains("Ad") || xml.Contains("Reward in") || xml.Contains("video will end in") || xml.Contains("Sponsored"))
                    {
                        unrecognizedCount = 0;
                        Log("[💎 Claimer] Ad detected during claim flow. Dismissing...");
                        DismissAppDialogs();
                        AdbTap(846, 60);
                        AdbTap(54, 60);
                        Thread.Sleep(2000);
                        continue;
                    }

                    unrecognizedCount++;
                    if (unrecognizedCount <= 3)
                    {
                        Log(string.Format("[💎 Claimer] Screen unrecognized ({0}/3), waiting for screen to settle...", unrecognizedCount));
                        Thread.Sleep(2000);
                        continue;
                    }

                    unrecognizedCount = 0;
                    Log("[💎 Claimer] Navigating to Quiz & Earn Home screen...");
                    LaunchTargetAppOnDevice(dev);
                    Thread.Sleep(2500);
                }
            }

            Log(string.Format("[💎 Claimer] Auto-Offer session complete across all instances! Processed {0} apps.", totalAppsProcessed));
            try { System.Media.SystemSounds.Asterisk.Play(); } catch { }
        }
        catch (Exception ex)
        {
            Log("[💎 Claimer] Error: " + ex.Message);
        }
        finally
        {
            isAutoOffering = false;
            isBoosting = false;
            SafeBeginInvoke(() => {
                if (lblBoostStatus != null)
                {
                    lblBoostStatus.Text = "Claimer: Idle";
                    lblBoostStatus.ForeColor = Color.FromArgb(110, 110, 115);
                }
            });
        }
    }

    private bool CheckAndEscapeForeignApp()
    {
        if (isBoosting || isAutoOffering) return false;
        string focus = GetCurrentFocus();
        if (string.IsNullOrEmpty(focus)) return false;

        if (focus.Contains("com.android.chrome"))
        {
            AdbKey(4); Thread.Sleep(400);
            RunAdb("shell am force-stop com.android.chrome");
            LaunchTargetAppOnDevice(adbPort);
            Thread.Sleep(2500);
            return true;
        }
        return false;
    }

    private bool TryFindAndClickAdButton(string xml, int elapsedSeconds)
    {
        if (string.IsNullOrEmpty(xml)) return false;
        if (xml.Contains("Select the correct number") || xml.Contains("Collect Gem") || xml.Contains("Congratulations")) return false;

        if (Regex.IsMatch(xml, @"(lose your reward|resume video|keep watching)", RegexOptions.IgnoreCase))
        {
            int rx, ry;
            if (TryFindNodeBounds(xml, "RESUME", out rx, out ry) || TryFindNodeBounds(xml, "Continue", out rx, out ry))
                AdbTap(rx, ry);
            else AdbTapRef(650, 610);
            return true;
        }

        foreach (Match m in Regex.Matches(xml, @"<node [^>]+>"))
        {
            string txt = m.Value;
            if (Regex.IsMatch(txt, @"(Sponsored|Install|Download|Learn More|Google Play)", RegexOptions.IgnoreCase)) continue;
            bool isClose = Regex.IsMatch(txt, @"(close|dismiss|mbridge_iv_close|close_button|btn_close|tt_video_ad_close|iv_close|al_close_button|no thanks|close ad)", RegexOptions.IgnoreCase);
            Match bm = Regex.Match(txt, @"bounds=""\[(\d+),(\d+)\]\[(\d+),(\d+)\]""");
            if (!bm.Success) continue;

            int x1 = int.Parse(bm.Groups[1].Value), y1 = int.Parse(bm.Groups[2].Value);
            int x2 = int.Parse(bm.Groups[3].Value), y2 = int.Parse(bm.Groups[4].Value);
            int w = x2 - x1, h = y2 - y1;
            int cx = (x1 + x2) / 2, cy = (y1 + y2) / 2;

            if (isClose && w >= 10 && w <= (int)(devWidth * 0.42) && h >= 10 && h <= (int)(devHeight * 0.16))
            {
                AdbTap(cx, cy);
                return true;
            }
            if (elapsedSeconds >= 20 && w >= 12 && w <= 200 && h >= 12 && h <= 200 &&
                ((x1 >= (int)(devWidth * 0.72) || x1 <= (int)(devWidth * 0.15)) && y1 <= (int)(devHeight * 0.14)))
            {
                AdbTap(cx, cy);
                return true;
            }
        }
        return false;
    }

    private bool CloseAdActively()
    {
        Log("[Ad] Waiting for ad playback & reward callback...");
        DateTime start = DateTime.Now;
        bool sawAdLeftMainScreen = false;

        while ((DateTime.Now - start).TotalSeconds < 45 && isRunning)
        {
            Thread.Sleep(1000);
            double elapsed = (DateTime.Now - start).TotalSeconds;
            string focus = GetCurrentFocus();
            bool inAd = !IsKnownQuizApp(focus) && (focus.Contains("Ad") || focus.Contains("Unity") || focus.Contains("IronSource") || focus.Contains("AppLovin") || focus.Contains("Vungle"));
            if (inAd) sawAdLeftMainScreen = true;

            if (elapsed >= 6)
            {
                string chkXml = DumpAndReadXml("/sdcard/ad_check.xml");
                bool onCollectGem = chkXml.Contains("Collect Gem") || chkXml.Contains("Watch an ad to collect") || chkXml.Contains("Congratulations");

                if (onCollectGem && !sawAdLeftMainScreen)
                {
                    Log("[Ad Warning] Rewarded ad failed to load (still on Collect Gem screen).");
                    return false;
                }

                if (IsKnownQuizApp(focus) && (chkXml.Contains("Select the correct number") || sawAdLeftMainScreen))
                {
                    Log("[Ad] Returned cleanly from ad.");
                    adsClosed++; SaveStatsToFile(); UpdateDashboard(lastCaptcha);
                    return true;
                }

                if (TryFindAndClickAdButton(chkXml, (int)elapsed))
                {
                    sawAdLeftMainScreen = true;
                    Thread.Sleep(1200);
                }
            }

            if (elapsed >= 28 && !IsKnownQuizApp(GetCurrentFocus()))
            {
                AdbKey(4);
                Thread.Sleep(1000);
            }
        }
        AdbKey(4);
        return sawAdLeftMainScreen;
    }

    private void RestartQuizAppCleanly(string reason)
    {
        string pkg = GetTargetPackage();
        Log(string.Format("[Recovery] {0} -> Cleanly restarting {1}...", reason, GetTargetAppLabel()));
        ForceStopTargetApp(adbPort, pkg);
        Thread.Sleep(800);
        LaunchTargetAppOnDevice(adbPort);
        adReloadCooldownUntil = DateTime.Now.AddSeconds(8);
        Thread.Sleep(3000);
    }

    private void BotWorkerLoop()
    {
        try
        {
            if (!EnsureConnected())
            {
                if (autoRecoverBlueStacks) EnsureBlueStacksAliveAndReady("Bot Startup");
                if (!EnsureConnected()) { Log("[Error] No ADB device online."); StopBot(); return; }
            }

            UpdateScreenResolution();
            while (isRunning)
            {
                if (isBoosting) { Thread.Sleep(1000); continue; }
                string focus = GetCurrentFocus();
                if (CheckAndEscapeForeignApp()) { lastProgressTime = DateTime.Now; continue; }

                if (focus.Contains("AdActivity") || focus.Contains("UnityPlayerActivity") || focus.Contains("IronSource"))
                {
                    CloseAdActively();
                    lastProgressTime = DateTime.Now;
                    continue;
                }

                string xml = DumpAndReadXml("/sdcard/cur.xml");
                if (string.IsNullOrEmpty(xml)) { Thread.Sleep(turboSpeedEnabled ? 220 : 600); continue; }

                Match mGemSingle = Regex.Match(xml, @"(?:text|content-desc)=""(\d{1,2})\s*/\s*25");
                if (mGemSingle.Success) { currentOfferGems = int.Parse(mGemSingle.Groups[1].Value); UpdateDashboard(lastCaptcha); }

                // CASE 1: True 25/25 Gems Milestone
                if (currentOfferGems >= 25 || xml.Contains("Super Offer Unlocked") || xml.Contains("Super Task Unlocked"))
                {
                    if (!hasCountedThis25GemCycle)
                    {
                        hasCountedThis25GemCycle = true;
                        lock (statsLock) { today25GemsCount++; total25GemsCount++; }
                        AddClaimHistory("25 Gems Milestone Hit");
                    }
                    Log("[ALERT] 25 GEMS TARGET REACHED! Stopping cleanly.");
                    StopBot();
                    return;
                }

                // CASE 4: Congratulations / Collect Gem (CHECKED BEFORE Quiz Math/Captcha!)
                if (xml.Contains("Congratulations") || xml.Contains("Collect Gem") || xml.Contains("+1 Gem") || xml.Contains("Watch an ad to collect"))
                {
                    lastProgressTime = DateTime.Now;
                    if (DateTime.Now < adReloadCooldownUntil) { Thread.Sleep(800); continue; }

                    int cx, cy;
                    if (TryFindNodeBounds(xml, "Collect Gem", out cx, out cy) || TryFindNodeBounds(xml, "Collect Reward", out cx, out cy))
                        AdbTap(cx, cy);
                    else AdbTapRef(450, 560);

                    Thread.Sleep(2200);
                    bool adPlayed = CloseAdActively();
                    if (!adPlayed)
                    {
                        collectGemNoAdCount++;
                        Log(string.Format("[Collect Gem] Ad did not load (attempt {0}/2).", collectGemNoAdCount));
                        if (collectGemNoAdCount >= 2)
                        {
                            collectGemNoAdCount = 0;
                            RestartQuizAppCleanly("Rewarded ad unavailable on Collect Gem");
                        }
                        else { AdbKey(4); Thread.Sleep(1500); }
                    }
                    else
                    {
                        collectGemNoAdCount = 0;
                        gemsEarned++;
                        SaveStatsToFile();
                        UpdateDashboard(lastCaptcha);
                    }
                    continue;
                }

                // CASE 5: Quiz Solver (Excludes /25 progress banners!)
                int qMinY = (int)(devHeight * 0.10), qMaxY = (int)(devHeight * 0.40);
                int optMinY = (int)(devHeight * 0.25), optMaxY = (int)(devHeight * 0.68);
                int btnX = 0, btnY = 0;

                // 5A. Math Quiz (single-pass + exact division check)
                Match mMath = Regex.Match(xml, @"(?:text|content-desc)=""(\d+)\s*([\+\-\*xX]|÷|/)\s*(\d+)(?:\s*=\s*\?)?""[^>]*bounds=""\[(\d+),(\d+)\]\[(\d+),(\d+)\]""");
                if (mMath.Success && !mMath.Value.Contains("/25"))
                {
                    int mcy = (int.Parse(mMath.Groups[5].Value) + int.Parse(mMath.Groups[7].Value)) / 2;
                    if (mcy >= qMinY && mcy <= qMaxY)
                    {
                        int n1 = int.Parse(mMath.Groups[1].Value), n2 = int.Parse(mMath.Groups[3].Value);
                        string op = mMath.Groups[2].Value;
                        int ans = -1;
                        if (op == "+") ans = n1 + n2;
                        else if (op == "-") ans = n1 - n2;
                        else if (op == "*" || op.Equals("x", StringComparison.OrdinalIgnoreCase)) ans = n1 * n2;
                        else if ((op == "/" || op == "÷") && n2 != 0 && n1 % n2 == 0) ans = n1 / n2;

                        if (ans >= 0 && TryFindOptionInZone(xml, ans.ToString(), optMinY, optMaxY, mcy, out btnX, out btnY))
                        {
                            string key = "MATH:" + n1 + op + n2;
                            if (key != lastSolvedTarget || (DateTime.Now - lastSolveTime).TotalMilliseconds >= 2500)
                            {
                                AdbTap(btnX, btnY);
                                RecordSolved(key, ans.ToString());
                                Thread.Sleep(turboSpeedEnabled ? 200 : 550);
                                continue;
                            }
                        }
                    }
                }

                // 5B. Number Captcha Solver
                foreach (Match m in Regex.Matches(xml, @"<node [^>]*text=""(\d{3,6})""[^>]*bounds=""\[(\d+),(\d+)\]\[(\d+),(\d+)\]"""))
                {
                    int qcy = (int.Parse(m.Groups[3].Value) + int.Parse(m.Groups[5].Value)) / 2;
                    if (qcy >= qMinY && qcy <= qMaxY)
                    {
                        string num = m.Groups[1].Value;
                        if (TryFindOptionInZone(xml, num, optMinY, optMaxY, qcy, out btnX, out btnY))
                        {
                            string key = "CAP:" + num;
                            if (key != lastSolvedTarget || (DateTime.Now - lastSolveTime).TotalMilliseconds >= 2500)
                            {
                                AdbTap(btnX, btnY);
                                RecordSolved(key, num);
                                Thread.Sleep(turboSpeedEnabled ? 200 : 550);
                                break;
                            }
                        }
                        break;
                    }
                }

                if ((DateTime.Now - lastProgressTime).TotalSeconds > 90)
                {
                    string state = RunAdbDevice(adbPort, "get-state").Trim();
                    if (!state.Equals("device", StringComparison.OrdinalIgnoreCase))
                    {
                        Log(string.Format("[Recovery] Active device {0} lost connection ({1})! Attempting recovery...", adbPort, state));
                        var inst = DiscoverBlueStacksInstances().FirstOrDefault(x => x.CandidatePorts.Any(p => adbPort.Contains(p)));
                        if (inst != null && autoRecoverBlueStacks)
                        {
                            RecoverBlueStacksInstance(inst);
                            EnsureConnected();
                        }
                    }
                    else
                    {
                        RestartQuizAppCleanly("Inactivity watchdog (90s)");
                    }
                    lastProgressTime = DateTime.Now;
                }
                Thread.Sleep(turboSpeedEnabled ? 150 : 500);
            }
        }
        catch (Exception ex) { Log("[Error] " + ex.Message); StopBot(); }
    }

    private bool TryFindOptionInZone(string xml, string answerText, int optMinY, int optMaxY, int questionY, out int outX, out int outY)
    {
        outX = 0; outY = 0;
        string pattern = string.Format(@"<node [^>]*(?:text|content-desc)=""{0}""[^>]*bounds=""\[(\d+),(\d+)\]\[(\d+),(\d+)\]""", Regex.Escape(answerText));
        foreach (Match ma in Regex.Matches(xml, pattern))
        {
            int cy = (int.Parse(ma.Groups[2].Value) + int.Parse(ma.Groups[4].Value)) / 2;
            if (cy >= optMinY && cy <= optMaxY && cy > questionY)
            {
                outX = (int.Parse(ma.Groups[1].Value) + int.Parse(ma.Groups[3].Value)) / 2;
                outY = cy;
                return true;
            }
        }
        return false;
    }

    private void RecordSolved(string key, string displayAns)
    {
        lastSolvedTarget = key;
        lastSolveTime = DateTime.Now;
        lastCaptcha = displayAns;
        lastProgressTime = DateTime.Now;
        collectGemNoAdCount = 0;
        lock (statsLock) { questionsSolved++; todaySolved++; }
        SaveStatsToFile();
        UpdateDashboard(displayAns);
    }

    private Panel CreateToggleRow(Panel parent, string text, DarkToggleSwitch toggle, int y, Action onToggle)
    {
        Panel row = new Panel { Location = new Point(12, y), Size = new Size(484, 30), Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right, Cursor = Cursors.Hand };
        Label lbl = new Label { Text = text, Location = new Point(4, 5), Size = new Size(420, 20), ForeColor = Color.FromArgb(205, 205, 215), Cursor = Cursors.Hand };
        toggle.Location = new Point(436, 5);
        toggle.Anchor = AnchorStyles.Top | AnchorStyles.Right;
        toggle.CheckedChanged += delegate { if (onToggle != null) onToggle(); };
        lbl.Click += delegate { toggle.Checked = !toggle.Checked; };
        row.Controls.Add(lbl);
        row.Controls.Add(toggle);
        parent.Controls.Add(row);
        return row;
    }

    private void ShowDarkAlert(string title, string message)
    {
        SafeBeginInvoke(() => MessageBox.Show(this, message, title, MessageBoxButtons.OK, MessageBoxIcon.Information));
    }

    private bool ShowDarkConfirm(string title, string message)
    {
        return MessageBox.Show(this, message, title, MessageBoxButtons.YesNo, MessageBoxIcon.Question) == DialogResult.Yes;
    }

    private void CheckForUpdatesAsync(bool manual)
    {
        new Thread(() =>
        {
            try
            {
                ServicePointManager.SecurityProtocol = (SecurityProtocolType)3072;
                using (WebClient wc = new WebClient())
                {
                    wc.Headers["User-Agent"] = "QuizEarnBot/" + APP_VERSION;
                    string json = wc.DownloadString(updateFeedUrl);
                    Match mv = Regex.Match(json, @"""version"":\s*""([^""]+)""");
                    Match mvc = Regex.Match(json, @"""version_code"":\s*(\d+)");
                    Match murl = Regex.Match(json, @"""download_url"":\s*""([^""]+)""");
                    Match mlog = Regex.Match(json, @"""changelog"":\s*""([^""]+)""");

                    remoteVersion = mv.Success ? mv.Groups[1].Value : "";
                    remoteVersionCode = mvc.Success ? int.Parse(mvc.Groups[1].Value) : 0;
                    remoteDownloadUrl = murl.Success ? murl.Groups[1].Value : "";
                    remoteChangelog = mlog.Success ? mlog.Groups[1].Value : "";

                    bool hasNew = remoteVersionCode > APP_VERSION_CODE && !string.IsNullOrEmpty(remoteDownloadUrl);
                    SafeBeginInvoke(() =>
                    {
                        if (hasNew)
                        {
                            lblUpdateStatus.Text = "New Version v" + remoteVersion + " available!";
                            if (ShowDarkConfirm("Update v" + remoteVersion, remoteChangelog + "\n\nDownload and restart now?"))
                            {
                                ApplyUpdateAndRestart(remoteDownloadUrl);
                            }
                        }
                        else
                        {
                            lblUpdateStatus.Text = "Up to date (v" + APP_VERSION + ").";
                            if (manual) ShowDarkAlert("Up to Date", "You are running the latest version (v" + APP_VERSION + ").");
                        }
                    });
                }
            }
            catch { if (manual) ShowDarkAlert("Update Check", "Could not reach update server."); }
        }) { IsBackground = true }.Start();
    }

    private void ApplyUpdateAndRestart(string downloadUrl)
    {
        new Thread(() =>
        {
            try
            {
                string baseDir = AppDomain.CurrentDomain.BaseDirectory;
                string tempExe = Path.Combine(baseDir, "QuizEarnBot_update.exe");
                string finalExe = Path.Combine(baseDir, "QuizEarnBot.exe");
                string bat = Path.Combine(baseDir, "apply_update.bat");

                using (WebClient wc = new WebClient()) wc.DownloadFile(downloadUrl, tempExe);
                if (!File.Exists(tempExe) || new FileInfo(tempExe).Length < 10240) throw new Exception("Invalid update package.");

                string pid = Process.GetCurrentProcess().Id.ToString();
                string script = string.Format(
                    "@echo off\r\n:waitloop\r\ntasklist /FI \"PID eq {0}\" | find \"{0}\" >nul\r\nif not errorlevel 1 (timeout /t 1 /nobreak >nul & goto waitloop)\r\nmove /y \"{1}\" \"{2}\" >nul\r\nstart \"\" \"{2}\"\r\ndel \"%~f0\" >nul\r\n",
                    pid, tempExe, finalExe);
                File.WriteAllText(bat, script, Encoding.ASCII);
                Process.Start(new ProcessStartInfo { FileName = bat, CreateNoWindow = true, UseShellExecute = false });
                SafeBeginInvoke(() => Application.Exit());
            }
            catch (Exception ex) { Log("[Update Error] " + ex.Message); }
        }) { IsBackground = true }.Start();
    }

    private static Mutex appMutex = null;

    [STAThread]
    public static void Main(string[] args)
    {
        bool createdNew;
        appMutex = new Mutex(true, "QuizEarnBot_SingleInstance_Mutex_9921", out createdNew);
        if (!createdNew) return;

        Application.EnableVisualStyles();
        Application.SetCompatibleTextRenderingDefault(false);
        QuizEarnApp app = new QuizEarnApp();
        if (args != null && args.Length > 0 && args[0].ToLower().Contains("auto"))
        {
            app.Shown += delegate { app.StartBot(); };
        }
        Application.Run(app);
    }
}

public class DarkToggleSwitch : Control
{
    private bool _checked = false;
    public event EventHandler CheckedChanged;
    public bool Checked { get { return _checked; } set { if (_checked != value) { _checked = value; Invalidate(); if (CheckedChanged != null) CheckedChanged(this, EventArgs.Empty); } } }
    public DarkToggleSwitch() { SetStyle(ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer | ControlStyles.SupportsTransparentBackColor, true); Size = new Size(38, 20); Cursor = Cursors.Hand; }
    protected override void OnClick(EventArgs e) { base.OnClick(e); Checked = !Checked; }
    protected override void OnPaint(PaintEventArgs pe)
    {
        Graphics g = pe.Graphics;
        g.SmoothingMode = SmoothingMode.AntiAlias;
        int d = Height - 1;
        using (GraphicsPath path = new GraphicsPath())
        {
            path.AddArc(0, 0, d, d, 90, 180);
            path.AddArc(Width - d - 1, 0, d, d, 270, 180);
            path.CloseFigure();
            using (SolidBrush bg = new SolidBrush(_checked ? Color.FromArgb(16, 185, 129) : Color.FromArgb(24, 24, 30))) g.FillPath(bg, path);
            int k = Height - 6;
            using (SolidBrush knob = new SolidBrush(_checked ? Color.White : Color.Gray)) g.FillEllipse(knob, _checked ? Width - k - 3 : 3, 3, k, k);
        }
    }
}
