using System.Net;
using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Windows.Forms;
using System.Runtime.InteropServices;
using System.Net.Sockets;
using System.Reflection;
using Microsoft.Win32;

[assembly: AssemblyTitle("Quiz & Earn Automation Suite")]
[assembly: AssemblyDescription("Automation utility and reward assistant for Quiz & Earn on BlueStacks.")]
[assembly: AssemblyConfiguration("Release")]
[assembly: AssemblyCompany("Quiz & Earn Tools")]
[assembly: AssemblyProduct("Quiz & Earn Auto-Bot")]
[assembly: AssemblyCopyright("Copyright © 2026 Quiz & Earn Tools")]
[assembly: AssemblyTrademark("")]
[assembly: AssemblyCulture("")]
[assembly: AssemblyVersion("2.6.0.0")]
[assembly: AssemblyFileVersion("2.6.0.0")]

public class QuizEarnApp : Form
{
    [DllImport("kernel32.dll", CharSet = CharSet.Auto, SetLastError = true)]
    private static extern uint SetThreadExecutionState(uint esFlags);
    private const uint ES_CONTINUOUS = 0x80000000;
    private const uint ES_SYSTEM_REQUIRED = 0x00000001;
    private const uint ES_DISPLAY_REQUIRED = 0x00000002;

    [DllImport("user32.dll")]
    private static extern bool SetForegroundWindow(IntPtr hWnd);

    [DllImport("user32.dll")]
    private static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);

    [DllImport("dwmapi.dll")]
    private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attr, ref int attrValue, int attrSize);
    private const int DWMWA_USE_IMMERSIVE_DARK_MODE_BEFORE_20H1 = 19;
    private const int DWMWA_USE_IMMERSIVE_DARK_MODE = 20;
    private const int DWMWA_BORDER_COLOR = 34;
    private const int DWMWA_CAPTION_COLOR = 35;
    private const int DWMWA_TEXT_COLOR = 36;

    public static void ApplyDwmDarkTo(IntPtr hwnd)
    {
        try
        {
            int darkMode = 1;
            DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, ref darkMode, sizeof(int));
            DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE_BEFORE_20H1, ref darkMode, sizeof(int));
            int blackColor = 0x00000000;
            DwmSetWindowAttribute(hwnd, DWMWA_CAPTION_COLOR, ref blackColor, sizeof(int));
            DwmSetWindowAttribute(hwnd, DWMWA_BORDER_COLOR, ref blackColor, sizeof(int));
            int textColor = 0x00808080;
            DwmSetWindowAttribute(hwnd, DWMWA_TEXT_COLOR, ref textColor, sizeof(int));
        }
        catch {}
    }

    private void ApplyDwmDarkMode()
    {
        try
        {
            ApplyDwmDarkTo(this.Handle);
            Log("[DWM] Titlebar DarkMode applied.");
        }
        catch (Exception ex)
        {
            Log("[DWM Error] " + ex.Message);
        }
    }

    protected override void OnHandleCreated(EventArgs e)
    {
        base.OnHandleCreated(e);
        ApplyDwmDarkMode();
    }

    protected override void OnShown(EventArgs e)
    {
        base.OnShown(e);
        ApplyDwmDarkMode();
    }

    protected override void OnFormClosing(FormClosingEventArgs e)
    {
        base.OnFormClosing(e);
        try {
            File.AppendAllText(Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "close_reason.log"), string.Format("[CLOSE] Reason: {0}, Cancel: {1}\nStackTrace:\n{2}\n", e.CloseReason, e.Cancel, Environment.StackTrace));
        } catch {}
    }

    // UI Elements
    private Label lblTitle;
    private Label lblStatus;
    private Label lblPort;
    private Button btnToggle;
    private Button btnRestartApp;
    private Button btnResetAdId;
    private Button btnTimerToggle;
    private Label lblTodaySolved;
    private Label lblGems;
    private Label lblCaptcha;
    private Label lblStats;
    private RichTextBox txtLog;
    private CheckBox chkAlwaysOnTop;
    private CheckBox chkAutoMute;
    private CheckBox chkTurboSpeed;

    // One-Click App Usage Booster Elements (AFK Time Farmer for Sponsored Offer Apps)
    private volatile bool isBoosting = false;
    private Thread boosterThread = null;
    private int boostSecondsRemaining = 0;
    private int boostTotalSeconds = 0;
    private string boostTargetPackage = "";
    private Panel pnlBooster;
    private Label lblBoostTitle;
    private Label lblBoostStatus;
    private Button btnBoost1m;
    private Button btnBoost5m;
    private Button btnBoost12m;

    // Dedicated Auto-Offer Coin Claimer Elements
    private volatile bool isAutoOffering = false;
    private Thread autoOfferThread = null;
    private Button btnAutoOffer;
    private Button btnAutoOfferStop;

    // Ad Countdown Timer
    private System.Windows.Forms.Timer uiCountdownTimer;
    private int countdownSeconds = 30;
    private bool isTimerRunning = false;

    // Threading & ADB
    private Thread botThread;
    private volatile bool isRunning = false;
    private string adbPort = "127.0.0.1:5555";
    private string adbExe = "adb";
    private volatile bool isAdbConnected = false;
    private int devWidth = 900;
    private int devHeight = 1600;

    // Statistics & State
    private int questionsSolved = 0;
    private int todaySolved = 0;
    private int gemsEarned = 0;
    private int currentOfferGems = 0;
    private int totalGems = 0;
    private int adsClosed = 0;
    private string lastCaptcha = "None";
    private string lastSolvedTarget = "";
    private DateTime lastSolveTime = DateTime.MinValue;
    private DateTime botStartTime;
    private DateTime lastProgressTime = DateTime.Now;

    // Data & Analytics State
    private int todayCoinsClaimed = 0;
    private int totalCoinsClaimed = 0;
    private int today25GemsCount = 0;
    private int total25GemsCount = 0;
    private int todayOffersClaimed = 0;
    private int totalOffersClaimed = 0;
    private bool hasCountedThis25GemCycle = false;
    private List<string> claimsHistory = new List<string>();

    // App Version & Cloud Auto-Updater State
    private const string APP_VERSION = "2.6";
    private const int APP_VERSION_CODE = 260;
    private bool autoRecoverBlueStacks = true;
    private string updateFeedUrl = "https://raw.githubusercontent.com/THESHMIL0/QuizEarnBot/main/version.json";
    private string remoteVersion = "";
    private int remoteVersionCode = 0;
    private string remoteChangelog = "";
    private string remoteDownloadUrl = "";
    private bool isUpdateAvailable = false;
    private bool isUpdateIgnored = false;
    private bool autoCheckUpdates = true;

    // UI Navigation & Views
    private Panel pnlHomeView;
    private Panel pnlDataView;
    private Panel pnlSettingsView;
    private Panel pnlBottomNav;
    private Button btnNavHome;
    private Button btnNavData;
    private Button btnNavSettings;

    // Settings View UI Elements
    private Label lblCurrentVersion;
    private Label lblUpdateStatus;
    private Button btnCheckUpdate;
    private Panel pnlUpdateCard;
    private DarkToggleSwitch chkAutoCheckUpdates;
    private DarkToggleSwitch chkSettingsAlwaysOnTop;
    private DarkToggleSwitch chkSettingsAutoMute;
    private DarkToggleSwitch chkSettingsTurbo;
    private DarkToggleSwitch chkSettingsAutoRecoverBst;

    // Data View UI Elements
    private Label lblDataCoinsVal;
    private Label lblData25GemsVal;
    private Label lblData25GemsSub;
    private Label lblDataOffersVal;
    private Label lblDataQuizzesVal;
    private Label lblLifetimeCoins;
    private Label lblLifetime25Gems;
    private Label lblLifetimeQuizzes;
    private Label lblDataDate;
    private RichTextBox txtClaimsLog;
    private Button btnDataRefresh;
    private Button btnDataResetToday;

    public QuizEarnApp()
    {
        Control.CheckForIllegalCrossThreadCalls = false;
        DetectAdbExecutable();
        InitializeComponent();
        PreventSleep();
    }

    private void PreventSleep()
    {
        try { SetThreadExecutionState(ES_CONTINUOUS | ES_SYSTEM_REQUIRED | ES_DISPLAY_REQUIRED); } catch {}
    }

    private void AllowSleep()
    {
        try { SetThreadExecutionState(ES_CONTINUOUS); } catch {}
    }

    private void DetectAdbExecutable()
    {
        // 1. Check local directory alongside QuizEarnBot.exe
        string localAdb = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "adb.exe");
        if (File.Exists(localAdb))
        {
            adbExe = localAdb;
            return;
        }

        // 2. Extract embedded zero-install ADB toolchain if bundled inside the executable
        try
        {
            string appDataDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "QuizEarnBot", "bin");
            if (!Directory.Exists(appDataDir)) Directory.CreateDirectory(appDataDir);
            string appDataAdb = Path.Combine(appDataDir, "adb.exe");

            string[] requiredFiles = new string[] { "adb.exe", "AdbWinApi.dll", "AdbWinUsbApi.dll" };
            Assembly asm = Assembly.GetExecutingAssembly();

            foreach (string fileName in requiredFiles)
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
                                byte[] buffer = new byte[81920];
                                int read;
                                while ((read = resStream.Read(buffer, 0, buffer.Length)) > 0)
                                {
                                    fs.Write(buffer, 0, read);
                                }
                            }
                        }
                    }
                }
            }

            if (File.Exists(appDataAdb))
            {
                adbExe = appDataAdb;
                return;
            }
        }
        catch {}

        // 3. Search common emulator and Android SDK locations
        string[] candidates = new string[]
        {
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), @"Android\Sdk\platform-tools\adb.exe"),
            @"C:\Program Files\BlueStacks_nxt\HD-Adb.exe",
            @"C:\Program Files (x86)\BlueStacks_nxt\HD-Adb.exe",
            @"C:\Program Files\BlueStacks\HD-Adb.exe",
            @"C:\LDPlayer\LDPlayer9\adb.exe",
            @"D:\LDPlayer\LDPlayer9\adb.exe",
            @"C:\LDPlayer\LDPlayer4\adb.exe",
            @"C:\Program Files\Nox\bin\nox_adb.exe",
            @"C:\Program Files\Microvirt\MEmu\adb.exe",
            @"C:\Program Files\Netease\MuMuPlayerGlobal-12.0\shell\adb.exe",
            "adb"
        };

        foreach (string c in candidates)
        {
            if (c == "adb" || File.Exists(c))
            {
                adbExe = c;
                break;
            }
        }
    }

    private void InitializeComponent()
    {
        this.Text = "Quiz & Earn - Auto Quiz & Reward Bot";
        try
        {
            string icoPath = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "app.ico");
            if (File.Exists(icoPath))
                this.Icon = new Icon(icoPath);
            else
                this.Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath);
        }
        catch {}
        this.ClientSize = new Size(544, 835);
        this.StartPosition = FormStartPosition.CenterScreen;
        // Pure OLED Pitch Black Dark++ Theme (Stealth Minimalist)
        this.BackColor = Color.Black;
        this.ForeColor = Color.FromArgb(135, 135, 140);
        this.Font = new Font("Segoe UI", 9.5f, FontStyle.Regular);
        this.FormBorderStyle = FormBorderStyle.FixedSingle;
        this.MaximizeBox = false;
        this.AutoScroll = false;

        // ==========================================
        // 1. HOME VIEW CONTAINER
        // ==========================================
        pnlHomeView = new Panel();
        pnlHomeView.Location = new Point(0, 0);
        pnlHomeView.Size = new Size(544, 780);
        pnlHomeView.BackColor = Color.Black;
        pnlHomeView.AutoScroll = false;
        this.Controls.Add(pnlHomeView);

        // Header Logo & Title
        PictureBox picLogo = new PictureBox();
        picLogo.Location = new Point(20, 14);
        picLogo.Size = new Size(30, 30);
        picLogo.SizeMode = PictureBoxSizeMode.Zoom;
        picLogo.BackColor = Color.Transparent;
        try
        {
            string pngPath = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "logo.png");
            string icoPath = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "app.ico");
            if (File.Exists(pngPath)) picLogo.Image = Image.FromFile(pngPath);
            else if (File.Exists(icoPath)) picLogo.Image = new Icon(icoPath, 64, 64).ToBitmap();
            else if (this.Icon != null) picLogo.Image = this.Icon.ToBitmap();
        }
        catch {}
        pnlHomeView.Controls.Add(picLogo);

        lblTitle = new Label();
        lblTitle.Text = "Quiz & Earn Auto-Bot";
        lblTitle.Font = new Font("Segoe UI", 16f, FontStyle.Bold);
        lblTitle.ForeColor = Color.FromArgb(160, 160, 165);
        lblTitle.Location = new Point(56, 14);
        lblTitle.AutoSize = true;
        pnlHomeView.Controls.Add(lblTitle);

        // Internal Status & Port instances (Hidden from UI per user request)
        lblStatus = new Label();
        lblStatus.Text = "READY";
        lblPort = new Label();
        lblPort.Text = "ADB: Connected";

        // Main Start/Stop Button (Dark++ Pure Stealth)
        btnToggle = new Button();
        btnToggle.Text = "[ > ]  START QUIZ & EARN BOT";
        btnToggle.Font = new Font("Segoe UI", 12f, FontStyle.Bold);
        btnToggle.ForeColor = Color.FromArgb(150, 150, 155);
        btnToggle.BackColor = Color.FromArgb(8, 8, 8);
        btnToggle.FlatStyle = FlatStyle.Flat;
        btnToggle.FlatAppearance.BorderColor = Color.FromArgb(24, 24, 24);
        btnToggle.FlatAppearance.BorderSize = 1;
        btnToggle.Location = new Point(20, 52);
        btnToggle.Size = new Size(505, 48);
        btnToggle.Cursor = Cursors.Hand;
        btnToggle.Click += new EventHandler(BtnToggle_Click);
        pnlHomeView.Controls.Add(btnToggle);

        // Utility Buttons Row: App Restart & Reset Ad ID (Pure Dark++, Ad Timer hidden per user request)
        int btnY = 108;
        btnRestartApp = new Button();
        btnRestartApp.Text = "Restart App";
        btnRestartApp.Font = new Font("Segoe UI", 9.5f, FontStyle.Bold);
        btnRestartApp.ForeColor = Color.FromArgb(120, 120, 125);
        btnRestartApp.BackColor = Color.FromArgb(6, 6, 6);
        btnRestartApp.FlatStyle = FlatStyle.Flat;
        btnRestartApp.FlatAppearance.BorderColor = Color.FromArgb(18, 18, 18);
        btnRestartApp.FlatAppearance.BorderSize = 1;
        btnRestartApp.Location = new Point(20, btnY);
        btnRestartApp.Size = new Size(248, 36);
        btnRestartApp.Cursor = Cursors.Hand;
        btnRestartApp.Click += new EventHandler(BtnRestartApp_Click);
        pnlHomeView.Controls.Add(btnRestartApp);

        btnResetAdId = new Button();
        btnResetAdId.Text = "Reset Ad ID";
        btnResetAdId.Font = new Font("Segoe UI", 9.5f, FontStyle.Bold);
        btnResetAdId.ForeColor = Color.FromArgb(120, 120, 125);
        btnResetAdId.BackColor = Color.FromArgb(6, 6, 6);
        btnResetAdId.FlatStyle = FlatStyle.Flat;
        btnResetAdId.FlatAppearance.BorderColor = Color.FromArgb(18, 18, 18);
        btnResetAdId.FlatAppearance.BorderSize = 1;
        btnResetAdId.Location = new Point(277, btnY);
        btnResetAdId.Size = new Size(248, 36);
        btnResetAdId.Cursor = Cursors.Hand;
        btnResetAdId.Click += new EventHandler(BtnResetAdId_Click);
        pnlHomeView.Controls.Add(btnResetAdId);

        // Timer toggle button instance kept in memory for events but not shown
        btnTimerToggle = new Button();

        // One-Click App Usage Booster & Auto-Claim Container (Pure Dark++ Stealth)
        pnlBooster = new Panel();
        pnlBooster.BackColor = Color.FromArgb(4, 4, 4);
        pnlBooster.Location = new Point(20, 152);
        pnlBooster.Size = new Size(505, 88);
        pnlBooster.BorderStyle = BorderStyle.None;
        pnlBooster.Paint += delegate(object s, PaintEventArgs pe) {
            ControlPaint.DrawBorder(pe.Graphics, pnlBooster.ClientRectangle, Color.FromArgb(20, 20, 20), ButtonBorderStyle.Solid);
        };
        pnlHomeView.Controls.Add(pnlBooster);

        // Row 1: Dedicated 1-Click Auto-Offer Coin Claimer
        btnAutoOffer = new Button();
        btnAutoOffer.Text = "[ AUTO-CLAIM ALL OFFERS (1-Click) ]";
        btnAutoOffer.Font = new Font("Segoe UI", 9.5f, FontStyle.Bold);
        btnAutoOffer.ForeColor = Color.FromArgb(220, 220, 225);
        btnAutoOffer.BackColor = Color.FromArgb(12, 12, 14);
        btnAutoOffer.FlatStyle = FlatStyle.Flat;
        btnAutoOffer.FlatAppearance.BorderColor = Color.FromArgb(28, 28, 32);
        btnAutoOffer.FlatAppearance.BorderSize = 1;
        btnAutoOffer.Location = new Point(8, 8);
        btnAutoOffer.Size = new Size(398, 34);
        btnAutoOffer.Cursor = Cursors.Hand;
        btnAutoOffer.Click += new EventHandler(BtnAutoOffer_Click);
        pnlBooster.Controls.Add(btnAutoOffer);

        btnAutoOfferStop = new Button();
        btnAutoOfferStop.Text = "Stop";
        btnAutoOfferStop.Font = new Font("Segoe UI", 9f, FontStyle.Bold);
        btnAutoOfferStop.ForeColor = Color.FromArgb(140, 140, 145);
        btnAutoOfferStop.BackColor = Color.FromArgb(10, 10, 12);
        btnAutoOfferStop.FlatStyle = FlatStyle.Flat;
        btnAutoOfferStop.FlatAppearance.BorderColor = Color.FromArgb(24, 24, 28);
        btnAutoOfferStop.FlatAppearance.BorderSize = 1;
        btnAutoOfferStop.Location = new Point(412, 8);
        btnAutoOfferStop.Size = new Size(84, 34);
        btnAutoOfferStop.Cursor = Cursors.Hand;
        btnAutoOfferStop.Click += new EventHandler(BtnAutoOfferStop_Click);
        pnlBooster.Controls.Add(btnAutoOfferStop);

        // Row 2: Manual Booster Presets & Live Countdown
        lblBoostTitle = new Label();
        lblBoostTitle.Text = "Manual:";
        lblBoostTitle.Font = new Font("Segoe UI", 8.5f, FontStyle.Bold);
        lblBoostTitle.ForeColor = Color.FromArgb(85, 85, 95);
        lblBoostTitle.Location = new Point(8, 52);
        lblBoostTitle.AutoSize = true;
        pnlBooster.Controls.Add(lblBoostTitle);

        btnBoost1m = new Button();
        btnBoost1m.Text = "+1m";
        btnBoost1m.Font = new Font("Segoe UI", 8.5f, FontStyle.Bold);
        btnBoost1m.ForeColor = Color.FromArgb(180, 180, 185);
        btnBoost1m.BackColor = Color.FromArgb(8, 8, 10);
        btnBoost1m.FlatStyle = FlatStyle.Flat;
        btnBoost1m.FlatAppearance.BorderColor = Color.FromArgb(24, 24, 28);
        btnBoost1m.FlatAppearance.BorderSize = 1;
        btnBoost1m.Location = new Point(62, 48);
        btnBoost1m.Size = new Size(54, 30);
        btnBoost1m.Cursor = Cursors.Hand;
        btnBoost1m.Click += delegate { StartUsageBooster(1); };
        pnlBooster.Controls.Add(btnBoost1m);

        btnBoost5m = new Button();
        btnBoost5m.Text = "+5m";
        btnBoost5m.Font = new Font("Segoe UI", 8.5f, FontStyle.Bold);
        btnBoost5m.ForeColor = Color.FromArgb(180, 180, 185);
        btnBoost5m.BackColor = Color.FromArgb(8, 8, 10);
        btnBoost5m.FlatStyle = FlatStyle.Flat;
        btnBoost5m.FlatAppearance.BorderColor = Color.FromArgb(24, 24, 28);
        btnBoost5m.FlatAppearance.BorderSize = 1;
        btnBoost5m.Location = new Point(120, 48);
        btnBoost5m.Size = new Size(54, 30);
        btnBoost5m.Cursor = Cursors.Hand;
        btnBoost5m.Click += delegate { StartUsageBooster(5); };
        pnlBooster.Controls.Add(btnBoost5m);

        btnBoost12m = new Button();
        btnBoost12m.Text = "+12m";
        btnBoost12m.Font = new Font("Segoe UI", 8.5f, FontStyle.Bold);
        btnBoost12m.ForeColor = Color.FromArgb(180, 180, 185);
        btnBoost12m.BackColor = Color.FromArgb(8, 8, 10);
        btnBoost12m.FlatStyle = FlatStyle.Flat;
        btnBoost12m.FlatAppearance.BorderColor = Color.FromArgb(24, 24, 28);
        btnBoost12m.FlatAppearance.BorderSize = 1;
        btnBoost12m.Location = new Point(178, 48);
        btnBoost12m.Size = new Size(60, 30);
        btnBoost12m.Cursor = Cursors.Hand;
        btnBoost12m.Click += delegate { StartUsageBooster(12); };
        pnlBooster.Controls.Add(btnBoost12m);

        lblBoostStatus = new Label();
        lblBoostStatus.Text = "Idle (Tap Auto-Claim to farm offers)";
        lblBoostStatus.Font = new Font("Segoe UI", 7.5f, FontStyle.Regular);
        lblBoostStatus.ForeColor = Color.FromArgb(110, 110, 115);
        lblBoostStatus.Location = new Point(245, 54);
        lblBoostStatus.AutoSize = true;
        pnlBooster.Controls.Add(lblBoostStatus);

        // Stats Dashboard Container (Pitch Black Panel)
        Panel statsPanel = new Panel();
        statsPanel.BackColor = Color.FromArgb(4, 4, 4);
        statsPanel.Location = new Point(20, 248);
        statsPanel.Size = new Size(505, 95);
        statsPanel.BorderStyle = BorderStyle.None;
        statsPanel.Paint += delegate(object s, PaintEventArgs pe) {
            ControlPaint.DrawBorder(pe.Graphics, statsPanel.ClientRectangle, Color.FromArgb(20, 20, 20), ButtonBorderStyle.Solid);
        };
        pnlHomeView.Controls.Add(statsPanel);

        // Stat Card 1: Today Solved
        lblTodaySolved = new Label();
        lblTodaySolved.Text = "0\nToday Solved";
        lblTodaySolved.Font = new Font("Segoe UI", 11f, FontStyle.Bold);
        lblTodaySolved.ForeColor = Color.FromArgb(150, 150, 155);
        lblTodaySolved.TextAlign = ContentAlignment.MiddleCenter;
        lblTodaySolved.Location = new Point(10, 10);
        lblTodaySolved.Size = new Size(115, 75);
        statsPanel.Controls.Add(lblTodaySolved);

        // Stat Card 2: Gems Earned
        lblGems = new Label();
        lblGems.Text = "0\nGems Earned";
        lblGems.Font = new Font("Segoe UI", 11f, FontStyle.Bold);
        lblGems.ForeColor = Color.FromArgb(150, 150, 155);
        lblGems.TextAlign = ContentAlignment.MiddleCenter;
        lblGems.Location = new Point(135, 10);
        lblGems.Size = new Size(115, 75);
        statsPanel.Controls.Add(lblGems);

        // Stat Card 3: Last Captcha
        lblCaptcha = new Label();
        lblCaptcha.Text = "None\nLast Captcha";
        lblCaptcha.Font = new Font("Segoe UI", 11f, FontStyle.Bold);
        lblCaptcha.ForeColor = Color.FromArgb(150, 150, 155);
        lblCaptcha.TextAlign = ContentAlignment.MiddleCenter;
        lblCaptcha.Location = new Point(260, 10);
        lblCaptcha.Size = new Size(110, 75);
        statsPanel.Controls.Add(lblCaptcha);

        // Stat Card 4: Ads Watched
        lblStats = new Label();
        lblStats.Text = "0\nAds Watched";
        lblStats.Font = new Font("Segoe UI", 11f, FontStyle.Bold);
        lblStats.ForeColor = Color.FromArgb(150, 150, 155);
        lblStats.TextAlign = ContentAlignment.MiddleCenter;
        lblStats.Location = new Point(380, 10);
        lblStats.Size = new Size(115, 75);
        statsPanel.Controls.Add(lblStats);

        // Options Bar instances kept in memory with optimal settings (Hidden from UI per user request)
        chkAlwaysOnTop = new CheckBox();
        chkAlwaysOnTop.Checked = false;

        chkAutoMute = new CheckBox();
        chkAutoMute.Checked = true;

        chkTurboSpeed = new CheckBox();
        chkTurboSpeed.Checked = true;

        // Activity Log Label
        Label lblLogTitle = new Label();
        lblLogTitle.Text = "Activity Log:";
        lblLogTitle.Font = new Font("Segoe UI", 9f, FontStyle.Bold);
        lblLogTitle.ForeColor = Color.FromArgb(75, 75, 80);
        lblLogTitle.Location = new Point(20, 352);
        lblLogTitle.AutoSize = true;
        pnlHomeView.Controls.Add(lblLogTitle);

        // Activity Log Box (Pure OLED Pitch Black, No Scrollbars)
        txtLog = new RichTextBox();
        txtLog.Location = new Point(20, 374);
        txtLog.Size = new Size(505, 396);
        txtLog.BackColor = Color.Black;
        txtLog.ForeColor = Color.FromArgb(115, 120, 125);
        txtLog.Font = new Font("Consolas", 9f);
        txtLog.BorderStyle = BorderStyle.None;
        txtLog.ReadOnly = true;
        txtLog.ScrollBars = RichTextBoxScrollBars.None;
        pnlHomeView.Controls.Add(txtLog);

        // ==========================================
        // 2. DATA VIEW CONTAINER (Analytics & Stats)
        // ==========================================
        pnlDataView = new Panel();
        pnlDataView.Location = new Point(0, 0);
        pnlDataView.Size = new Size(544, 780);
        pnlDataView.BackColor = Color.Black;
        pnlDataView.AutoScroll = false;
        pnlDataView.Visible = false;
        this.Controls.Add(pnlDataView);

        // Header Title
        Label lblDataTitle = new Label();
        lblDataTitle.Text = "📊  Earnings & Performance Data";
        lblDataTitle.Font = new Font("Segoe UI", 15f, FontStyle.Bold);
        lblDataTitle.ForeColor = Color.FromArgb(220, 220, 225);
        lblDataTitle.Location = new Point(20, 16);
        lblDataTitle.AutoSize = true;
        pnlDataView.Controls.Add(lblDataTitle);

        // Header Subtitle
        Label lblDataSubtitle = new Label();
        lblDataSubtitle.Text = "Live tracking of Coins claimed, 25-Gem cycles & offer completions";
        lblDataSubtitle.Font = new Font("Segoe UI", 8.5f);
        lblDataSubtitle.ForeColor = Color.FromArgb(120, 120, 130);
        lblDataSubtitle.Location = new Point(22, 46);
        lblDataSubtitle.AutoSize = true;
        pnlDataView.Controls.Add(lblDataSubtitle);

        // Date Display
        lblDataDate = new Label();
        lblDataDate.Text = "Today: " + DateTime.Now.ToString("dddd, MMMM dd, yyyy");
        lblDataDate.Font = new Font("Segoe UI", 9f, FontStyle.Bold);
        lblDataDate.ForeColor = Color.FromArgb(140, 140, 145);
        lblDataDate.Location = new Point(22, 68);
        lblDataDate.AutoSize = true;
        pnlDataView.Controls.Add(lblDataDate);

        // Card 1: Today's Coins Claimed
        Panel pnlCardCoins = new Panel();
        pnlCardCoins.BackColor = Color.FromArgb(6, 6, 6);
        pnlCardCoins.Location = new Point(20, 96);
        pnlCardCoins.Size = new Size(246, 95);
        pnlCardCoins.Paint += delegate(object s, PaintEventArgs pe) {
            ControlPaint.DrawBorder(pe.Graphics, pnlCardCoins.ClientRectangle, Color.FromArgb(22, 22, 26), ButtonBorderStyle.Solid);
        };
        pnlDataView.Controls.Add(pnlCardCoins);

        Label lblCardCoinsHead = new Label();
        lblCardCoinsHead.Text = "COINS CLAIMED TODAY";
        lblCardCoinsHead.Font = new Font("Segoe UI", 8f, FontStyle.Bold);
        lblCardCoinsHead.ForeColor = Color.FromArgb(140, 140, 145);
        lblCardCoinsHead.Location = new Point(12, 10);
        lblCardCoinsHead.AutoSize = true;
        pnlCardCoins.Controls.Add(lblCardCoinsHead);

        lblDataCoinsVal = new Label();
        lblDataCoinsVal.Text = "+0 Coins";
        lblDataCoinsVal.Font = new Font("Segoe UI", 18f, FontStyle.Bold);
        lblDataCoinsVal.ForeColor = Color.FromArgb(235, 235, 240);
        lblDataCoinsVal.Location = new Point(10, 32);
        lblDataCoinsVal.AutoSize = true;
        pnlCardCoins.Controls.Add(lblDataCoinsVal);

        Label lblCardCoinsSub = new Label();
        lblCardCoinsSub.Text = "Farmed from offer milestones & bonuses";
        lblCardCoinsSub.Font = new Font("Segoe UI", 7.5f);
        lblCardCoinsSub.ForeColor = Color.FromArgb(100, 100, 105);
        lblCardCoinsSub.Location = new Point(12, 68);
        lblCardCoinsSub.AutoSize = true;
        pnlCardCoins.Controls.Add(lblCardCoinsSub);

        // Card 2: 25-Gem Cycles Hit
        Panel pnlCard25Gems = new Panel();
        pnlCard25Gems.BackColor = Color.FromArgb(6, 6, 6);
        pnlCard25Gems.Location = new Point(278, 96);
        pnlCard25Gems.Size = new Size(246, 95);
        pnlCard25Gems.Paint += delegate(object s, PaintEventArgs pe) {
            ControlPaint.DrawBorder(pe.Graphics, pnlCard25Gems.ClientRectangle, Color.FromArgb(22, 22, 26), ButtonBorderStyle.Solid);
        };
        pnlDataView.Controls.Add(pnlCard25Gems);

        Label lblCard25GemsHead = new Label();
        lblCard25GemsHead.Text = "25-GEM CYCLES HIT";
        lblCard25GemsHead.Font = new Font("Segoe UI", 8f, FontStyle.Bold);
        lblCard25GemsHead.ForeColor = Color.FromArgb(140, 140, 145);
        lblCard25GemsHead.Location = new Point(12, 10);
        lblCard25GemsHead.AutoSize = true;
        pnlCard25Gems.Controls.Add(lblCard25GemsHead);

        lblData25GemsVal = new Label();
        lblData25GemsVal.Text = "0 times";
        lblData25GemsVal.Font = new Font("Segoe UI", 18f, FontStyle.Bold);
        lblData25GemsVal.ForeColor = Color.FromArgb(235, 235, 240);
        lblData25GemsVal.Location = new Point(10, 32);
        lblData25GemsVal.AutoSize = true;
        pnlCard25Gems.Controls.Add(lblData25GemsVal);

        lblData25GemsSub = new Label();
        lblData25GemsSub.Text = "0 Gems earned from quizzes today";
        lblData25GemsSub.Font = new Font("Segoe UI", 7.5f);
        lblData25GemsSub.ForeColor = Color.FromArgb(100, 100, 105);
        lblData25GemsSub.Location = new Point(12, 68);
        lblData25GemsSub.AutoSize = true;
        pnlCard25Gems.Controls.Add(lblData25GemsSub);

        // Row 2: Offers Claimed Today & Quizzes Solved
        Panel pnlCardOffers = new Panel();
        pnlCardOffers.BackColor = Color.FromArgb(5, 5, 5);
        pnlCardOffers.Location = new Point(20, 200);
        pnlCardOffers.Size = new Size(246, 75);
        pnlCardOffers.Paint += delegate(object s, PaintEventArgs pe) {
            ControlPaint.DrawBorder(pe.Graphics, pnlCardOffers.ClientRectangle, Color.FromArgb(20, 20, 24), ButtonBorderStyle.Solid);
        };
        pnlDataView.Controls.Add(pnlCardOffers);

        Label lblCardOffersHead = new Label();
        lblCardOffersHead.Text = "OFFERS CLAIMED TODAY";
        lblCardOffersHead.Font = new Font("Segoe UI", 7.5f, FontStyle.Bold);
        lblCardOffersHead.ForeColor = Color.FromArgb(140, 140, 145);
        lblCardOffersHead.Location = new Point(12, 8);
        lblCardOffersHead.AutoSize = true;
        pnlCardOffers.Controls.Add(lblCardOffersHead);

        lblDataOffersVal = new Label();
        lblDataOffersVal.Text = "0 claims";
        lblDataOffersVal.Font = new Font("Segoe UI", 15f, FontStyle.Bold);
        lblDataOffersVal.ForeColor = Color.FromArgb(200, 200, 205);
        lblDataOffersVal.Location = new Point(10, 26);
        lblDataOffersVal.AutoSize = true;
        pnlCardOffers.Controls.Add(lblDataOffersVal);

        Label lblCardOffersSub = new Label();
        lblCardOffersSub.Text = "Milestones & bonus stages";
        lblCardOffersSub.Font = new Font("Segoe UI", 7.5f);
        lblCardOffersSub.ForeColor = Color.FromArgb(100, 100, 105);
        lblCardOffersSub.Location = new Point(12, 54);
        lblCardOffersSub.AutoSize = true;
        pnlCardOffers.Controls.Add(lblCardOffersSub);

        Panel pnlCardQuizzes = new Panel();
        pnlCardQuizzes.BackColor = Color.FromArgb(5, 5, 5);
        pnlCardQuizzes.Location = new Point(278, 200);
        pnlCardQuizzes.Size = new Size(246, 75);
        pnlCardQuizzes.Paint += delegate(object s, PaintEventArgs pe) {
            ControlPaint.DrawBorder(pe.Graphics, pnlCardQuizzes.ClientRectangle, Color.FromArgb(20, 20, 24), ButtonBorderStyle.Solid);
        };
        pnlDataView.Controls.Add(pnlCardQuizzes);

        Label lblCardQuizzesHead = new Label();
        lblCardQuizzesHead.Text = "QUIZZES & ADS TODAY";
        lblCardQuizzesHead.Font = new Font("Segoe UI", 7.5f, FontStyle.Bold);
        lblCardQuizzesHead.ForeColor = Color.FromArgb(140, 140, 145);
        lblCardQuizzesHead.Location = new Point(12, 8);
        lblCardQuizzesHead.AutoSize = true;
        pnlCardQuizzes.Controls.Add(lblCardQuizzesHead);

        lblDataQuizzesVal = new Label();
        lblDataQuizzesVal.Text = "0 Solved | 0 Ads";
        lblDataQuizzesVal.Font = new Font("Segoe UI", 14f, FontStyle.Bold);
        lblDataQuizzesVal.ForeColor = Color.FromArgb(200, 200, 205);
        lblDataQuizzesVal.Location = new Point(10, 26);
        lblDataQuizzesVal.AutoSize = true;
        pnlCardQuizzes.Controls.Add(lblDataQuizzesVal);

        Label lblCardQuizzesSub = new Label();
        lblCardQuizzesSub.Text = "Captcha solver activity";
        lblCardQuizzesSub.Font = new Font("Segoe UI", 7.5f);
        lblCardQuizzesSub.ForeColor = Color.FromArgb(100, 100, 105);
        lblCardQuizzesSub.Location = new Point(12, 54);
        lblCardQuizzesSub.AutoSize = true;
        pnlCardQuizzes.Controls.Add(lblCardQuizzesSub);

        // Row 3: Lifetime Totals Box
        Panel pnlLifetime = new Panel();
        pnlLifetime.BackColor = Color.FromArgb(5, 5, 7);
        pnlLifetime.Location = new Point(20, 284);
        pnlLifetime.Size = new Size(504, 76);
        pnlLifetime.Paint += delegate(object s, PaintEventArgs pe) {
            ControlPaint.DrawBorder(pe.Graphics, pnlLifetime.ClientRectangle, Color.FromArgb(20, 20, 24), ButtonBorderStyle.Solid);
        };
        pnlDataView.Controls.Add(pnlLifetime);

        Label lblLifeHead = new Label();
        lblLifeHead.Text = "LIFETIME TOTALS";
        lblLifeHead.Font = new Font("Segoe UI", 8f, FontStyle.Bold);
        lblLifeHead.ForeColor = Color.FromArgb(140, 140, 145);
        lblLifeHead.Location = new Point(12, 8);
        lblLifeHead.AutoSize = true;
        pnlLifetime.Controls.Add(lblLifeHead);

        lblLifetimeCoins = new Label();
        lblLifetimeCoins.Text = "0 Coins";
        lblLifetimeCoins.Font = new Font("Segoe UI", 11f, FontStyle.Bold);
        lblLifetimeCoins.ForeColor = Color.FromArgb(210, 210, 215);
        lblLifetimeCoins.Location = new Point(12, 30);
        lblLifetimeCoins.Size = new Size(160, 22);
        pnlLifetime.Controls.Add(lblLifetimeCoins);

        Label lblLifeCoinsSub = new Label();
        lblLifeCoinsSub.Text = "All-Time Coins";
        lblLifeCoinsSub.Font = new Font("Segoe UI", 7.5f);
        lblLifeCoinsSub.ForeColor = Color.FromArgb(100, 100, 105);
        lblLifeCoinsSub.Location = new Point(12, 52);
        lblLifeCoinsSub.AutoSize = true;
        pnlLifetime.Controls.Add(lblLifeCoinsSub);

        lblLifetime25Gems = new Label();
        lblLifetime25Gems.Text = "0 times (0 gems)";
        lblLifetime25Gems.Font = new Font("Segoe UI", 11f, FontStyle.Bold);
        lblLifetime25Gems.ForeColor = Color.FromArgb(210, 210, 215);
        lblLifetime25Gems.Location = new Point(180, 30);
        lblLifetime25Gems.Size = new Size(170, 22);
        pnlLifetime.Controls.Add(lblLifetime25Gems);

        Label lblLifeGemsSub = new Label();
        lblLifeGemsSub.Text = "All-Time 25-Gem Cycles";
        lblLifeGemsSub.Font = new Font("Segoe UI", 7.5f);
        lblLifeGemsSub.ForeColor = Color.FromArgb(100, 100, 105);
        lblLifeGemsSub.Location = new Point(180, 52);
        lblLifeGemsSub.AutoSize = true;
        pnlLifetime.Controls.Add(lblLifeGemsSub);

        lblLifetimeQuizzes = new Label();
        lblLifetimeQuizzes.Text = "0 Solved";
        lblLifetimeQuizzes.Font = new Font("Segoe UI", 11f, FontStyle.Bold);
        lblLifetimeQuizzes.ForeColor = Color.FromArgb(210, 210, 215);
        lblLifetimeQuizzes.Location = new Point(360, 30);
        lblLifetimeQuizzes.Size = new Size(130, 22);
        pnlLifetime.Controls.Add(lblLifetimeQuizzes);

        Label lblLifeQuizSub = new Label();
        lblLifeQuizSub.Text = "All-Time Solved";
        lblLifeQuizSub.Font = new Font("Segoe UI", 7.5f);
        lblLifeQuizSub.ForeColor = Color.FromArgb(100, 100, 105);
        lblLifeQuizSub.Location = new Point(360, 52);
        lblLifeQuizSub.AutoSize = true;
        pnlLifetime.Controls.Add(lblLifeQuizSub);

        // Row 4: Claims Log
        Label lblClaimsLogHead = new Label();
        lblClaimsLogHead.Text = "Today's Claims & Milestone History:";
        lblClaimsLogHead.Font = new Font("Segoe UI", 8.5f, FontStyle.Bold);
        lblClaimsLogHead.ForeColor = Color.FromArgb(140, 140, 145);
        lblClaimsLogHead.Location = new Point(20, 368);
        lblClaimsLogHead.AutoSize = true;
        pnlDataView.Controls.Add(lblClaimsLogHead);

        txtClaimsLog = new RichTextBox();
        txtClaimsLog.Location = new Point(20, 390);
        txtClaimsLog.Size = new Size(504, 335);
        txtClaimsLog.BackColor = Color.Black;
        txtClaimsLog.ForeColor = Color.FromArgb(180, 180, 185);
        txtClaimsLog.Font = new Font("Consolas", 9f);
        txtClaimsLog.BorderStyle = BorderStyle.None;
        txtClaimsLog.ReadOnly = true;
        txtClaimsLog.ScrollBars = RichTextBoxScrollBars.None;
        pnlDataView.Controls.Add(txtClaimsLog);

        // Row 5: Action Buttons
        btnDataRefresh = new Button();
        btnDataRefresh.Text = "Refresh Data";
        btnDataRefresh.Font = new Font("Segoe UI", 9f, FontStyle.Bold);
        btnDataRefresh.ForeColor = Color.FromArgb(180, 180, 185);
        btnDataRefresh.BackColor = Color.FromArgb(8, 8, 10);
        btnDataRefresh.FlatStyle = FlatStyle.Flat;
        btnDataRefresh.FlatAppearance.BorderColor = Color.FromArgb(24, 24, 28);
        btnDataRefresh.FlatAppearance.BorderSize = 1;
        btnDataRefresh.Location = new Point(20, 735);
        btnDataRefresh.Size = new Size(246, 36);
        btnDataRefresh.Cursor = Cursors.Hand;
        btnDataRefresh.Click += delegate { UpdateDataDashboard(); };
        pnlDataView.Controls.Add(btnDataRefresh);

        btnDataResetToday = new Button();
        btnDataResetToday.Text = "Reset Today's Count";
        btnDataResetToday.Font = new Font("Segoe UI", 9f, FontStyle.Bold);
        btnDataResetToday.ForeColor = Color.FromArgb(140, 140, 145);
        btnDataResetToday.BackColor = Color.FromArgb(8, 8, 10);
        btnDataResetToday.FlatStyle = FlatStyle.Flat;
        btnDataResetToday.FlatAppearance.BorderColor = Color.FromArgb(24, 24, 28);
        btnDataResetToday.FlatAppearance.BorderSize = 1;
        btnDataResetToday.Location = new Point(278, 735);
        btnDataResetToday.Size = new Size(246, 36);
        btnDataResetToday.Cursor = Cursors.Hand;
        btnDataResetToday.Click += delegate {
            if (ShowDarkConfirm("Reset Today's Stats", "Are you sure you want to reset today's coins, 25-gem count, and claims log back to 0?"))
            {
                todayCoinsClaimed = 0;
                today25GemsCount = 0;
                todayOffersClaimed = 0;
                todaySolved = 0;
                gemsEarned = 0;
                claimsHistory.Clear();
                SaveStatsToFile();
                UpdateDataDashboard();
                UpdateDashboard(lastCaptcha);
                Log("[Data] Today's statistics have been reset to 0.");
            }
        };
        pnlDataView.Controls.Add(btnDataResetToday);

        // ==========================================
        // 3. SETTINGS & UPDATER VIEW CONTAINER
        // ==========================================
        pnlSettingsView = new Panel();
        pnlSettingsView.Location = new Point(0, 0);
        pnlSettingsView.Size = new Size(544, 780);
        pnlSettingsView.BackColor = Color.Black;
        pnlSettingsView.AutoScroll = false;
        pnlSettingsView.Visible = false;
        this.Controls.Add(pnlSettingsView);

        // Header Title
        Label lblSettingsTitle = new Label();
        lblSettingsTitle.Text = "Settings & Updates";
        lblSettingsTitle.Font = new Font("Segoe UI", 15f, FontStyle.Bold);
        lblSettingsTitle.ForeColor = Color.FromArgb(220, 220, 225);
        lblSettingsTitle.Location = new Point(20, 16);
        lblSettingsTitle.AutoSize = true;
        pnlSettingsView.Controls.Add(lblSettingsTitle);

        Label lblSettingsSub = new Label();
        lblSettingsSub.Text = "Manage in-app cloud updates, bot automation, and preferences";
        lblSettingsSub.Font = new Font("Segoe UI", 8.5f);
        lblSettingsSub.ForeColor = Color.FromArgb(120, 120, 130);
        lblSettingsSub.Location = new Point(22, 46);
        lblSettingsSub.AutoSize = true;
        pnlSettingsView.Controls.Add(lblSettingsSub);

        // Card 1: Cloud Auto-Updater Card
        pnlUpdateCard = new Panel();
        pnlUpdateCard.BackColor = Color.FromArgb(6, 6, 6);
        pnlUpdateCard.Location = new Point(20, 72);
        pnlUpdateCard.Size = new Size(504, 90);
        pnlUpdateCard.Paint += delegate(object s, PaintEventArgs pe) {
            ControlPaint.DrawBorder(pe.Graphics, pnlUpdateCard.ClientRectangle, Color.FromArgb(22, 22, 26), ButtonBorderStyle.Solid);
        };
        pnlSettingsView.Controls.Add(pnlUpdateCard);

        Label lblUpdateHead = new Label();
        lblUpdateHead.Text = "APP VERSION & CLOUD UPDATER";
        lblUpdateHead.Font = new Font("Segoe UI", 8f, FontStyle.Bold);
        lblUpdateHead.ForeColor = Color.FromArgb(140, 140, 145);
        lblUpdateHead.Location = new Point(14, 12);
        lblUpdateHead.AutoSize = true;
        pnlUpdateCard.Controls.Add(lblUpdateHead);

        lblCurrentVersion = new Label();
        lblCurrentVersion.Text = string.Format("Current Installed: v{0}", APP_VERSION);
        lblCurrentVersion.Font = new Font("Segoe UI", 12f, FontStyle.Bold);
        lblCurrentVersion.ForeColor = Color.FromArgb(235, 235, 240);
        lblCurrentVersion.Location = new Point(12, 32);
        lblCurrentVersion.AutoSize = true;
        pnlUpdateCard.Controls.Add(lblCurrentVersion);

        lblUpdateStatus = new Label();
        lblUpdateStatus.Text = "Status: Up to date. Click 'Check for Updates' to verify.";
        lblUpdateStatus.Font = new Font("Segoe UI", 8.5f);
        lblUpdateStatus.ForeColor = Color.FromArgb(140, 140, 145);
        lblUpdateStatus.Location = new Point(14, 58);
        lblUpdateStatus.Size = new Size(300, 20);
        pnlUpdateCard.Controls.Add(lblUpdateStatus);

        btnCheckUpdate = new Button();
        btnCheckUpdate.Text = "Check for Updates";
        btnCheckUpdate.Font = new Font("Segoe UI", 9f, FontStyle.Bold);
        btnCheckUpdate.ForeColor = Color.FromArgb(200, 200, 205);
        btnCheckUpdate.BackColor = Color.FromArgb(12, 12, 14);
        btnCheckUpdate.FlatStyle = FlatStyle.Flat;
        btnCheckUpdate.FlatAppearance.BorderColor = Color.FromArgb(28, 28, 32);
        btnCheckUpdate.FlatAppearance.BorderSize = 1;
        btnCheckUpdate.Location = new Point(334, 26);
        btnCheckUpdate.Size = new Size(156, 36);
        btnCheckUpdate.Cursor = Cursors.Hand;
        btnCheckUpdate.Click += delegate { CheckForUpdatesAsync(true); };
        pnlUpdateCard.Controls.Add(btnCheckUpdate);

        // Card 2: Automation & Preferences
        Panel pnlPrefsCard = new Panel();
        pnlPrefsCard.BackColor = Color.FromArgb(6, 6, 6);
        pnlPrefsCard.Location = new Point(20, 178);
        pnlPrefsCard.Size = new Size(504, 210);
        pnlPrefsCard.Paint += delegate(object s, PaintEventArgs pe) {
            ControlPaint.DrawBorder(pe.Graphics, pnlPrefsCard.ClientRectangle, Color.FromArgb(22, 22, 26), ButtonBorderStyle.Solid);
        };
        pnlSettingsView.Controls.Add(pnlPrefsCard);

        Label lblPrefsHead = new Label();
        lblPrefsHead.Text = "BOT AUTOMATION & PREFERENCES";
        lblPrefsHead.Font = new Font("Segoe UI", 8f, FontStyle.Bold);
        lblPrefsHead.ForeColor = Color.FromArgb(140, 140, 145);
        lblPrefsHead.Location = new Point(14, 10);
        lblPrefsHead.AutoSize = true;
        pnlPrefsCard.Controls.Add(lblPrefsHead);

        chkAutoCheckUpdates = new DarkToggleSwitch();
        chkAutoCheckUpdates.Checked = autoCheckUpdates;
        CreateToggleRow(pnlPrefsCard, "Auto-check for updates on startup", chkAutoCheckUpdates, 34, () => {
            autoCheckUpdates = chkAutoCheckUpdates.Checked;
            SaveStatsToFile();
        });

        chkSettingsAutoMute = new DarkToggleSwitch();
        chkSettingsAutoMute.Checked = true;
        CreateToggleRow(pnlPrefsCard, "Auto-Mute Ads while solving quizzes (keeps PC quiet)", chkSettingsAutoMute, 66, () => {
            if (chkAutoMute != null) chkAutoMute.Checked = chkSettingsAutoMute.Checked;
        });

        chkSettingsTurbo = new DarkToggleSwitch();
        chkSettingsTurbo.Checked = true;
        CreateToggleRow(pnlPrefsCard, "Turbo Speed Solver (fast answering mode)", chkSettingsTurbo, 98, () => {
            if (chkTurboSpeed != null) chkTurboSpeed.Checked = chkSettingsTurbo.Checked;
        });

        chkSettingsAlwaysOnTop = new DarkToggleSwitch();
        chkSettingsAlwaysOnTop.Checked = false;
        CreateToggleRow(pnlPrefsCard, "Always On Top window mode", chkSettingsAlwaysOnTop, 130, () => {
            this.TopMost = chkSettingsAlwaysOnTop.Checked;
        });

        chkSettingsAutoRecoverBst = new DarkToggleSwitch();
        chkSettingsAutoRecoverBst.Checked = autoRecoverBlueStacks;
        CreateToggleRow(pnlPrefsCard, "Auto-launch BlueStacks on crash / disconnect", chkSettingsAutoRecoverBst, 162, () => {
            autoRecoverBlueStacks = chkSettingsAutoRecoverBst.Checked;
            SaveStatsToFile();
        });

        // Card 3: About Standalone
        Panel pnlAboutCard = new Panel();
        pnlAboutCard.BackColor = Color.FromArgb(4, 4, 4);
        pnlAboutCard.Location = new Point(20, 400);
        pnlAboutCard.Size = new Size(504, 115);
        pnlAboutCard.Paint += delegate(object s, PaintEventArgs pe) {
            ControlPaint.DrawBorder(pe.Graphics, pnlAboutCard.ClientRectangle, Color.FromArgb(20, 20, 24), ButtonBorderStyle.Solid);
        };
        pnlSettingsView.Controls.Add(pnlAboutCard);

        Label lblAboutHead = new Label();
        lblAboutHead.Text = "ABOUT QUIZEARNBOT PORTABLE";
        lblAboutHead.Font = new Font("Segoe UI", 8f, FontStyle.Bold);
        lblAboutHead.ForeColor = Color.FromArgb(140, 140, 145);
        lblAboutHead.Location = new Point(14, 10);
        lblAboutHead.AutoSize = true;
        pnlAboutCard.Controls.Add(lblAboutHead);

        Label lblAboutText = new Label();
        lblAboutText.Text = string.Format("• Version: v{0} (Pure OLED Monochrome Stealth Build)\n• Architecture: Standalone Portable (Zero installation, zero registry impact)\n• Universal ADB Engine: BlueStacks, LDPlayer, Nox, USB Phone\n• Automated Day Rollover: Real-time Coin & 25-Gem stats tracker", APP_VERSION);
        lblAboutText.Font = new Font("Segoe UI", 8f);
        lblAboutText.ForeColor = Color.FromArgb(120, 120, 125);
        lblAboutText.Location = new Point(14, 32);
        lblAboutText.Size = new Size(476, 72);
        pnlAboutCard.Controls.Add(lblAboutText);

        // ==========================================
        // 4. BOTTOM NAVIGATION BAR
        // ==========================================
        pnlBottomNav = new Panel();
        pnlBottomNav.Location = new Point(0, 780);
        pnlBottomNav.Size = new Size(544, 55);
        pnlBottomNav.BackColor = Color.FromArgb(4, 4, 4);
        pnlBottomNav.Paint += delegate(object s, PaintEventArgs pe) {
            pe.Graphics.DrawLine(new Pen(Color.FromArgb(20, 20, 22), 1), 0, 0, pnlBottomNav.Width, 0);
        };
        this.Controls.Add(pnlBottomNav);

        btnNavHome = new Button();
        btnNavHome.Text = "HOME";
        btnNavHome.Font = new Font("Segoe UI", 10f, FontStyle.Bold);
        btnNavHome.ForeColor = Color.White;
        btnNavHome.BackColor = Color.FromArgb(20, 20, 24);
        btnNavHome.FlatStyle = FlatStyle.Flat;
        btnNavHome.FlatAppearance.BorderColor = Color.FromArgb(45, 45, 52);
        btnNavHome.FlatAppearance.BorderSize = 1;
        btnNavHome.Location = new Point(14, 7);
        btnNavHome.Size = new Size(164, 40);
        btnNavHome.Cursor = Cursors.Hand;
        btnNavHome.Click += delegate { SwitchToHomeView(); };
        pnlBottomNav.Controls.Add(btnNavHome);

        btnNavData = new Button();
        btnNavData.Text = "DATA";
        btnNavData.Font = new Font("Segoe UI", 10f, FontStyle.Bold);
        btnNavData.ForeColor = Color.FromArgb(110, 110, 115);
        btnNavData.BackColor = Color.FromArgb(6, 6, 8);
        btnNavData.FlatStyle = FlatStyle.Flat;
        btnNavData.FlatAppearance.BorderColor = Color.FromArgb(18, 18, 20);
        btnNavData.FlatAppearance.BorderSize = 1;
        btnNavData.Location = new Point(188, 7);
        btnNavData.Size = new Size(164, 40);
        btnNavData.Cursor = Cursors.Hand;
        btnNavData.Click += delegate { SwitchToDataView(); };
        pnlBottomNav.Controls.Add(btnNavData);

        btnNavSettings = new Button();
        btnNavSettings.Text = "SETTINGS";
        btnNavSettings.Font = new Font("Segoe UI", 10f, FontStyle.Bold);
        btnNavSettings.ForeColor = Color.FromArgb(110, 110, 115);
        btnNavSettings.BackColor = Color.FromArgb(6, 6, 8);
        btnNavSettings.FlatStyle = FlatStyle.Flat;
        btnNavSettings.FlatAppearance.BorderColor = Color.FromArgb(18, 18, 20);
        btnNavSettings.FlatAppearance.BorderSize = 1;
        btnNavSettings.Location = new Point(362, 7);
        btnNavSettings.Size = new Size(164, 40);
        btnNavSettings.Cursor = Cursors.Hand;
        btnNavSettings.Click += delegate { SwitchToSettingsView(); };
        pnlBottomNav.Controls.Add(btnNavSettings);

        // Ad Countdown Timer Setup
        uiCountdownTimer = new System.Windows.Forms.Timer();
        uiCountdownTimer.Interval = 1000;
        uiCountdownTimer.Tick += new EventHandler(UiCountdownTimer_Tick);

        LoadStatsFromFile();
        UpdateDashboard(lastCaptcha);
        UpdateDataDashboard();
        SwitchToHomeView();

        // Background auto-check for updates after 3.5 seconds
        if (autoCheckUpdates)
        {
            System.Windows.Forms.Timer updateDelayTimer = new System.Windows.Forms.Timer();
            updateDelayTimer.Interval = 3500;
            updateDelayTimer.Tick += delegate {
                updateDelayTimer.Stop();
                updateDelayTimer.Dispose();
                CheckForUpdatesAsync(false);
            };
            updateDelayTimer.Start();
        }

        Log("Quiz & Earn Auto-Bot initialized.");
        Log("Pure OLED Pitch Black Theme loaded (Dark++).");
        Log("Universal ADB Auto-Discovery active (BlueStacks, LDPlayer, Nox, Phone).");
        Log("Ready. Start ADB on your emulator or phone, then click [START].");

        // Universal ADB Auto-Watcher Loop (Continuous Background Thread)
        Thread watcherThread = new Thread(new ThreadStart(UniversalAdbAutoWatchLoop));
        watcherThread.IsBackground = true;
        watcherThread.Start();
    }

    private void UniversalAdbAutoWatchLoop()
    {
        while (!this.IsDisposed)
        {
            try
            {
                if (!isRunning)
                {
                    bool connected = EnsureConnected();
                    if (connected)
                    {
                        if (!isAdbConnected)
                        {
                            isAdbConnected = true;
                            UpdatePortLabel(adbPort);
                            Log(string.Format("[ADB] Auto-connected to device on {0}! Ready to start.", adbPort));
                            if (this.InvokeRequired)
                            {
                                SafeBeginInvoke(new Action(delegate {
                                    if (!isRunning)
                                    {
                                        lblStatus.Text = string.Format("READY (Connected: {0})", adbPort);
                                        lblStatus.ForeColor = Color.FromArgb(135, 135, 140);
                                    }
                                }));
                            }
                        }
                    }
                    else
                    {
                        if (isAdbConnected)
                        {
                            isAdbConnected = false;
                            UpdatePortLabel("Waiting for ADB (Turn on ADB)...");
                            if (this.InvokeRequired)
                            {
                                SafeBeginInvoke(new Action(delegate {
                                    if (!isRunning)
                                    {
                                        lblStatus.Text = "WAITING FOR ADB (Turn on ADB)";
                                        lblStatus.ForeColor = Color.FromArgb(95, 95, 100);
                                    }
                                }));
                            }
                            Log("[ADB] Device connection lost. Auto-reconnecting...");
                        }
                    }
                }
            }
            catch {}
            Thread.Sleep(2500);
        }
    }

    private void BtnRestartApp_Click(object sender, EventArgs e)
    {
        Log("[Action] Manual App Restart requested.");
        RestartApp();
    }

    private void RestartApp()
    {
        new Thread(new ThreadStart(delegate {
            try
            {
                if (autoRecoverBlueStacks && !IsBlueStacksProcessRunning())
                {
                    EnsureBlueStacksAliveAndReady("Restart App");
                }
                else
                {
                    EnsureConnected();
                }
                RunAdb("shell am force-stop com.quiz.earn.money.game");
                RunAdb("shell am force-stop com.rayole.cashmafia2");
                Thread.Sleep(800);
                RunAdb("shell monkey -p com.quiz.earn.money.game -c android.intent.category.LAUNCHER 1");
                Log("[App] Quiz & Earn restarted cleanly.");
            }
            catch (Exception ex)
            {
                Log("[Error] Failed to restart app: " + ex.Message);
            }
        })).Start();
    }

    private void BtnResetAdId_Click(object sender, EventArgs e)
    {
        Log("[Action] Resetting Ad ID and clearing cache...");
        new Thread(new ThreadStart(delegate {
            try
            {
                EnsureConnected();
                RunAdb("shell pm clear com.google.android.gms");
                Thread.Sleep(800);
                RunAdb("shell am broadcast -a com.google.android.gms.ads.identifier.service.START");
                Log("[Ad ID] Google Advertising ID & cache reset successfully.");
            }
            catch (Exception ex)
            {
                Log("[Error] Failed to reset Ad ID: " + ex.Message);
            }
        })).Start();
    }

    private void BtnTimerToggle_Click(object sender, EventArgs e)
    {
        if (!isTimerRunning)
        {
            countdownSeconds = 30;
            isTimerRunning = true;
            uiCountdownTimer.Start();
            btnTimerToggle.Text = "Timer: 30s";
            Log("[Timer] Ad countdown timer started (30s).");
        }
        else
        {
            BtnTimerRestart_Click(sender, e);
        }
    }

    private void BtnTimerRestart_Click(object sender, EventArgs e)
    {
        countdownSeconds = 30;
        UpdateTimerDisplay();
    }

    private void UiCountdownTimer_Tick(object sender, EventArgs e)
    {
        countdownSeconds--;
        UpdateTimerDisplay();
        if (countdownSeconds <= 0)
        {
            uiCountdownTimer.Stop();
            isTimerRunning = false;
            btnTimerToggle.Text = "Timer: Ready";
            PlayTimerNoise();
            Log("[Timer] Ad countdown elapsed. Auto-dismissing...");
            new Thread(new ThreadStart(CloseAdActively)).Start();
        }
    }

    private void UpdateTimerDisplay()
    {
        if (this.InvokeRequired)
        {
            this.BeginInvoke(new Action(UpdateTimerDisplay));
            return;
        }
        btnTimerToggle.Text = string.Format("Timer: {0}s", countdownSeconds);
    }

    private void PlayTimerNoise()
    {
        try { System.Media.SystemSounds.Beep.Play(); } catch {}
    }

    private void Play25GemsMilestoneAlert()
    {
        new Thread(new ThreadStart(delegate {
            try
            {
                SilenceAudio(false);
                Console.Beep(987, 200);
                Thread.Sleep(60);
                Console.Beep(1318, 250);
                Thread.Sleep(60);
                Console.Beep(1760, 450);
                System.Media.SystemSounds.Exclamation.Play();
            }
            catch {}
        })).Start();
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

        btnToggle.Text = "[ || ]  STOP BOT (RUNNING)";
        btnToggle.BackColor = Color.FromArgb(14, 14, 14);
        btnToggle.ForeColor = Color.FromArgb(165, 165, 170);
        btnToggle.FlatAppearance.BorderColor = Color.FromArgb(36, 36, 36);

        lblStatus.Text = "RUNNING (Auto-Quiz + Reward Active)";
        lblStatus.ForeColor = Color.FromArgb(145, 145, 150);

        if (chkAutoMute.Checked)
        {
            SilenceAudio(true);
        }

        botThread = new Thread(new ThreadStart(BotWorkerLoop));
        botThread.IsBackground = true;
        botThread.Start();
        Log(">>> Bot started.");
    }

    private void StopBot()
    {
        isRunning = false;

        btnToggle.Text = "[ > ]  START QUIZ & EARN BOT";
        btnToggle.BackColor = Color.FromArgb(8, 8, 8);
        btnToggle.ForeColor = Color.FromArgb(150, 150, 155);
        btnToggle.FlatAppearance.BorderColor = Color.FromArgb(24, 24, 24);

        lblStatus.Text = isAdbConnected ? string.Format("READY (Connected: {0})", adbPort) : "WAITING FOR ADB (Turn on ADB)";
        lblStatus.ForeColor = isAdbConnected ? Color.FromArgb(135, 135, 140) : Color.FromArgb(95, 95, 100);

        SilenceAudio(false);
        if (IsEmulatorDevice(adbPort))
        {
            RunAdb("shell settings put global window_animation_scale 1.0");
            RunAdb("shell settings put global transition_animation_scale 1.0");
            RunAdb("shell settings put global animator_duration_scale 1.0");
        }
        Log("<<< Bot stopped.");
    }

    private void SilenceAudio(bool mute)
    {
        try
        {
            if (mute)
            {
                RunAdb("shell cmd media_session volume --stream 3 --set 0");
                Log("[Audio] Device media audio muted.");
            }
            else
            {
                RunAdb("shell cmd media_session volume --stream 3 --set 12");
                Log("[Audio] Device sound restored.");
            }
        }
        catch {}
    }

    private void SafeBeginInvoke(Action action)
    {
        try {
            if (this.IsHandleCreated && !this.IsDisposed) {
                this.BeginInvoke(action);
            }
        } catch {}
    }

    private void Log(string message)
    {
        if (this.InvokeRequired)
        {
            try {
                if (this.IsHandleCreated && !this.IsDisposed) {
                    this.BeginInvoke(new Action<string>(Log), message);
                }
            } catch {}
            return;
        }
        string time = DateTime.Now.ToString("HH:mm:ss");
        string line = string.Format("[{0}] {1}\n", time, message);
        txtLog.AppendText(line);
        txtLog.SelectionStart = txtLog.Text.Length;
        txtLog.ScrollToCaret();

        try
        {
            string logFile = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "bot_run.log");
            File.AppendAllText(logFile, line);
        }
        catch {}
    }

    private void LoadStatsFromFile()
    {
        try
        {
            string path = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "quizearn_stats.json");
            if (!File.Exists(path))
            {
                path = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "cashmafia_stats.json");
            }
            if (File.Exists(path))
            {
                string json = File.ReadAllText(path);

                string fileDate = "";
                Match mDate = Regex.Match(json, @"""todayDate"":\s*""([^""]+)""");
                if (mDate.Success) fileDate = mDate.Groups[1].Value;

                string todayStr = DateTime.Now.ToString("yyyy-MM-dd");
                bool isSameDay = (fileDate == todayStr);

                Match mToday = Regex.Match(json, @"""todaySolved"":\s*(\d+)");
                if (mToday.Success && isSameDay) todaySolved = int.Parse(mToday.Groups[1].Value);

                Match mGems = Regex.Match(json, @"""gemsEarned"":\s*(\d+)");
                if (mGems.Success) gemsEarned = int.Parse(mGems.Groups[1].Value);

                Match mAds = Regex.Match(json, @"""adsClosed"":\s*(\d+)");
                if (mAds.Success) adsClosed = int.Parse(mAds.Groups[1].Value);

                Match mTot = Regex.Match(json, @"""totalSolved"":\s*(\d+)");
                if (mTot.Success) totalGems = int.Parse(mTot.Groups[1].Value);

                Match mTodayCoins = Regex.Match(json, @"""todayCoinsClaimed"":\s*(\d+)");
                if (mTodayCoins.Success && isSameDay) todayCoinsClaimed = int.Parse(mTodayCoins.Groups[1].Value);

                Match mTotalCoins = Regex.Match(json, @"""totalCoinsClaimed"":\s*(\d+)");
                if (mTotalCoins.Success) totalCoinsClaimed = int.Parse(mTotalCoins.Groups[1].Value);

                Match mToday25 = Regex.Match(json, @"""today25GemsCount"":\s*(\d+)");
                if (mToday25.Success && isSameDay) today25GemsCount = int.Parse(mToday25.Groups[1].Value);

                Match mTotal25 = Regex.Match(json, @"""total25GemsCount"":\s*(\d+)");
                if (mTotal25.Success) total25GemsCount = int.Parse(mTotal25.Groups[1].Value);

                Match mTodayOff = Regex.Match(json, @"""todayOffersClaimed"":\s*(\d+)");
                if (mTodayOff.Success && isSameDay) todayOffersClaimed = int.Parse(mTodayOff.Groups[1].Value);

                Match mTotalOff = Regex.Match(json, @"""totalOffersClaimed"":\s*(\d+)");
                if (mTotalOff.Success) totalOffersClaimed = int.Parse(mTotalOff.Groups[1].Value);

                Match mAutoUp = Regex.Match(json, @"""autoCheckUpdates"":\s*(true|false)", RegexOptions.IgnoreCase);
                if (mAutoUp.Success) autoCheckUpdates = bool.Parse(mAutoUp.Groups[1].Value);

                Match mBst = Regex.Match(json, @"""autoRecoverBlueStacks"":\s*(true|false)", RegexOptions.IgnoreCase);
                if (mBst.Success) autoRecoverBlueStacks = bool.Parse(mBst.Groups[1].Value);

                Match mFeed = Regex.Match(json, @"""updateFeedUrl"":\s*""([^""]+)""");
                if (mFeed.Success && !string.IsNullOrEmpty(mFeed.Groups[1].Value))
                {
                    string feed = mFeed.Groups[1].Value.Trim();
                    if (feed.Contains("raw.githubusercontent.com/THESHMIL/"))
                        feed = feed.Replace("raw.githubusercontent.com/THESHMIL/", "raw.githubusercontent.com/THESHMIL0/");
                    updateFeedUrl = feed;
                }

                claimsHistory.Clear();
                if (isSameDay)
                {
                    Match mHist = Regex.Match(json, @"""claimsHistory"":\s*\[(.*?)\]", RegexOptions.Singleline);
                    if (mHist.Success)
                    {
                        MatchCollection items = Regex.Matches(mHist.Groups[1].Value, @"""((?:\\.|[^""\\])*)""");
                        foreach (Match it in items)
                        {
                            string decoded = it.Groups[1].Value.Replace("\\\"", "\"").Replace("\\\\", "\\");
                            claimsHistory.Add(decoded);
                        }
                    }
                }
            }
        }
        catch {}
    }

    private void SaveStatsToFile()
    {
        try
        {
            string path = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "quizearn_stats.json");
            StringBuilder histJson = new StringBuilder();
            if (claimsHistory != null)
            {
                for (int i = 0; i < claimsHistory.Count; i++)
                {
                    if (i > 0) histJson.Append(", ");
                    string escaped = claimsHistory[i].Replace("\\", "\\\\").Replace("\"", "\\\"");
                    histJson.Append("\"" + escaped + "\"");
                }
            }

            string json = string.Format(
                "{{\n" +
                "  \"todayDate\": \"{0}\",\n" +
                "  \"todaySolved\": {1},\n" +
                "  \"totalSolved\": {2},\n" +
                "  \"gemsEarned\": {3},\n" +
                "  \"adsClosed\": {4},\n" +
                "  \"todayCoinsClaimed\": {5},\n" +
                "  \"totalCoinsClaimed\": {6},\n" +
                "  \"today25GemsCount\": {7},\n" +
                "  \"total25GemsCount\": {8},\n" +
                "  \"todayOffersClaimed\": {9},\n" +
                "  \"totalOffersClaimed\": {10},\n" +
                "  \"autoCheckUpdates\": {11},\n" +
                "  \"autoRecoverBlueStacks\": {12},\n" +
                "  \"updateFeedUrl\": \"{13}\",\n" +
                "  \"claimsHistory\": [{14}]\n" +
                "}}",
                DateTime.Now.ToString("yyyy-MM-dd"),
                todaySolved,
                totalGems + todaySolved,
                gemsEarned,
                adsClosed,
                todayCoinsClaimed,
                totalCoinsClaimed,
                today25GemsCount,
                total25GemsCount,
                todayOffersClaimed,
                totalOffersClaimed,
                autoCheckUpdates.ToString().ToLower(),
                autoRecoverBlueStacks.ToString().ToLower(),
                updateFeedUrl.Replace("\\", "\\\\").Replace("\"", "\\\""),
                histJson.ToString()
            );
            File.WriteAllText(path, json);
        }
        catch {}
    }

    private void SwitchToHomeView()
    {
        if (this.InvokeRequired)
        {
            this.BeginInvoke(new Action(SwitchToHomeView));
            return;
        }

        if (pnlHomeView != null) pnlHomeView.Visible = true;
        if (pnlDataView != null) pnlDataView.Visible = false;
        if (pnlSettingsView != null) pnlSettingsView.Visible = false;

        SetActiveNavButton(btnNavHome);
    }

    private void SwitchToDataView()
    {
        if (this.InvokeRequired)
        {
            this.BeginInvoke(new Action(SwitchToDataView));
            return;
        }

        UpdateDataDashboard();

        if (pnlHomeView != null) pnlHomeView.Visible = false;
        if (pnlDataView != null) pnlDataView.Visible = true;
        if (pnlSettingsView != null) pnlSettingsView.Visible = false;

        SetActiveNavButton(btnNavData);
    }

    private void SwitchToSettingsView()
    {
        if (this.InvokeRequired)
        {
            this.BeginInvoke(new Action(SwitchToSettingsView));
            return;
        }

        if (pnlHomeView != null) pnlHomeView.Visible = false;
        if (pnlDataView != null) pnlDataView.Visible = false;
        if (pnlSettingsView != null) pnlSettingsView.Visible = true;

        if (chkAutoCheckUpdates != null) chkAutoCheckUpdates.Checked = autoCheckUpdates;
        if (chkSettingsAutoMute != null && chkAutoMute != null) chkSettingsAutoMute.Checked = chkAutoMute.Checked;
        if (chkSettingsTurbo != null && chkTurboSpeed != null) chkSettingsTurbo.Checked = chkTurboSpeed.Checked;
        if (chkSettingsAlwaysOnTop != null) chkSettingsAlwaysOnTop.Checked = this.TopMost;
        if (chkSettingsAutoRecoverBst != null) chkSettingsAutoRecoverBst.Checked = autoRecoverBlueStacks;

        SetActiveNavButton(btnNavSettings);
    }

    private void SetActiveNavButton(Button activeBtn)
    {
        Button[] all = new Button[] { btnNavHome, btnNavData, btnNavSettings };
        foreach (Button b in all)
        {
            if (b == null) continue;
            if (b == activeBtn)
            {
                b.ForeColor = Color.White;
                b.BackColor = Color.FromArgb(20, 20, 24);
                b.FlatAppearance.BorderColor = Color.FromArgb(45, 45, 52);
            }
            else
            {
                b.ForeColor = Color.FromArgb(110, 110, 115);
                b.BackColor = Color.FromArgb(6, 6, 8);
                b.FlatAppearance.BorderColor = Color.FromArgb(18, 18, 20);
            }
        }
    }

    private void ShowDarkAlert(string title, string message, string buttonText = "OK")
    {
        if (this.InvokeRequired)
        {
            this.BeginInvoke(new Action(() => ShowDarkAlert(title, message, buttonText)));
            return;
        }

        using (Form dlg = new Form())
        {
            dlg.Text = title;
            dlg.ClientSize = new Size(430, 185);
            dlg.StartPosition = FormStartPosition.CenterParent;
            dlg.FormBorderStyle = FormBorderStyle.FixedDialog;
            dlg.MaximizeBox = false;
            dlg.MinimizeBox = false;
            dlg.ShowInTaskbar = false;
            dlg.BackColor = Color.FromArgb(8, 8, 10);
            dlg.ForeColor = Color.FromArgb(220, 220, 225);
            try { dlg.Icon = this.Icon; } catch {}

            dlg.HandleCreated += delegate {
                ApplyDwmDarkTo(dlg.Handle);
            };

            dlg.Paint += delegate(object s, PaintEventArgs pe) {
                ControlPaint.DrawBorder(pe.Graphics, dlg.ClientRectangle, Color.FromArgb(36, 36, 44), ButtonBorderStyle.Solid);
            };

            bool isSuccess = title.IndexOf("Up to Date", StringComparison.OrdinalIgnoreCase) >= 0 || title.IndexOf("Success", StringComparison.OrdinalIgnoreCase) >= 0;
            bool isWarning = title.IndexOf("Check", StringComparison.OrdinalIgnoreCase) >= 0 || title.IndexOf("Warning", StringComparison.OrdinalIgnoreCase) >= 0 || title.IndexOf("Error", StringComparison.OrdinalIgnoreCase) >= 0;

            Label lblBadge = new Label();
            lblBadge.Font = new Font("Segoe UI", 12f, FontStyle.Bold);
            lblBadge.Size = new Size(24, 24);
            lblBadge.Location = new Point(22, 18);
            lblBadge.TextAlign = ContentAlignment.MiddleCenter;
            if (isSuccess)
            {
                lblBadge.Text = "âœ“";
                lblBadge.ForeColor = Color.FromArgb(70, 210, 130);
            }
            else if (isWarning)
            {
                lblBadge.Text = "!";
                lblBadge.ForeColor = Color.FromArgb(240, 170, 50);
            }
            else
            {
                lblBadge.Text = "âœ¦";
                lblBadge.ForeColor = Color.FromArgb(170, 170, 180);
            }
            dlg.Controls.Add(lblBadge);

            Label lblTitle = new Label();
            lblTitle.Text = title;
            lblTitle.Font = new Font("Segoe UI", 11.5f, FontStyle.Bold);
            lblTitle.ForeColor = Color.White;
            lblTitle.Location = new Point(50, 19);
            lblTitle.AutoSize = true;
            dlg.Controls.Add(lblTitle);

            Label lblMsg = new Label();
            lblMsg.Text = message;
            lblMsg.Font = new Font("Segoe UI", 9.5f);
            lblMsg.ForeColor = Color.FromArgb(170, 170, 175);
            lblMsg.Location = new Point(24, 52);
            lblMsg.Size = new Size(382, 60);
            dlg.Controls.Add(lblMsg);

            Button btnOk = new Button();
            btnOk.Text = buttonText;
            btnOk.Font = new Font("Segoe UI", 9.5f, FontStyle.Bold);
            btnOk.ForeColor = Color.White;
            btnOk.BackColor = Color.FromArgb(24, 24, 28);
            btnOk.FlatStyle = FlatStyle.Flat;
            btnOk.FlatAppearance.BorderColor = Color.FromArgb(48, 48, 56);
            btnOk.FlatAppearance.BorderSize = 1;
            btnOk.Location = new Point(140, 124);
            btnOk.Size = new Size(150, 38);
            btnOk.Cursor = Cursors.Hand;
            btnOk.TabStop = false;
            btnOk.MouseEnter += delegate { btnOk.BackColor = Color.FromArgb(36, 36, 42); btnOk.FlatAppearance.BorderColor = Color.FromArgb(70, 70, 80); };
            btnOk.MouseLeave += delegate { btnOk.BackColor = Color.FromArgb(24, 24, 28); btnOk.FlatAppearance.BorderColor = Color.FromArgb(48, 48, 56); };
            btnOk.Click += delegate { dlg.Close(); };
            dlg.Controls.Add(btnOk);

            dlg.AcceptButton = btnOk;
            dlg.CancelButton = btnOk;

            dlg.ShowDialog(this);
        }
    }

    private bool ShowDarkConfirm(string title, string message, string yesText = "Yes", string noText = "No")
    {
        if (this.InvokeRequired)
        {
            return (bool)this.Invoke(new Func<bool>(() => ShowDarkConfirm(title, message, yesText, noText)));
        }

        bool result = false;
        using (Form dlg = new Form())
        {
            dlg.Text = title;
            dlg.ClientSize = new Size(430, 185);
            dlg.StartPosition = FormStartPosition.CenterParent;
            dlg.FormBorderStyle = FormBorderStyle.FixedDialog;
            dlg.MaximizeBox = false;
            dlg.MinimizeBox = false;
            dlg.ShowInTaskbar = false;
            dlg.BackColor = Color.FromArgb(8, 8, 10);
            dlg.ForeColor = Color.FromArgb(220, 220, 225);
            try { dlg.Icon = this.Icon; } catch {}

            dlg.HandleCreated += delegate {
                ApplyDwmDarkTo(dlg.Handle);
            };

            dlg.Paint += delegate(object s, PaintEventArgs pe) {
                ControlPaint.DrawBorder(pe.Graphics, dlg.ClientRectangle, Color.FromArgb(36, 36, 44), ButtonBorderStyle.Solid);
            };

            Label lblBadge = new Label();
            lblBadge.Font = new Font("Segoe UI", 12f, FontStyle.Bold);
            lblBadge.Size = new Size(24, 24);
            lblBadge.Location = new Point(22, 18);
            lblBadge.TextAlign = ContentAlignment.MiddleCenter;
            lblBadge.Text = "?";
            lblBadge.ForeColor = Color.FromArgb(140, 140, 220);
            dlg.Controls.Add(lblBadge);

            Label lblTitle = new Label();
            lblTitle.Text = title;
            lblTitle.Font = new Font("Segoe UI", 11.5f, FontStyle.Bold);
            lblTitle.ForeColor = Color.White;
            lblTitle.Location = new Point(50, 19);
            lblTitle.AutoSize = true;
            dlg.Controls.Add(lblTitle);

            Label lblMsg = new Label();
            lblMsg.Text = message;
            lblMsg.Font = new Font("Segoe UI", 9.5f);
            lblMsg.ForeColor = Color.FromArgb(170, 170, 175);
            lblMsg.Location = new Point(24, 52);
            lblMsg.Size = new Size(382, 60);
            dlg.Controls.Add(lblMsg);

            Button btnYes = new Button();
            btnYes.Text = yesText;
            btnYes.Font = new Font("Segoe UI", 9.5f, FontStyle.Bold);
            btnYes.ForeColor = Color.White;
            btnYes.BackColor = Color.FromArgb(32, 32, 38);
            btnYes.FlatStyle = FlatStyle.Flat;
            btnYes.FlatAppearance.BorderColor = Color.FromArgb(60, 60, 72);
            btnYes.FlatAppearance.BorderSize = 1;
            btnYes.Location = new Point(80, 124);
            btnYes.Size = new Size(125, 38);
            btnYes.Cursor = Cursors.Hand;
            btnYes.TabStop = false;
            btnYes.MouseEnter += delegate { btnYes.BackColor = Color.FromArgb(44, 44, 52); btnYes.FlatAppearance.BorderColor = Color.FromArgb(80, 80, 95); };
            btnYes.MouseLeave += delegate { btnYes.BackColor = Color.FromArgb(32, 32, 38); btnYes.FlatAppearance.BorderColor = Color.FromArgb(60, 60, 72); };
            btnYes.Click += delegate { result = true; dlg.Close(); };
            dlg.Controls.Add(btnYes);

            Button btnNo = new Button();
            btnNo.Text = noText;
            btnNo.Font = new Font("Segoe UI", 9.5f, FontStyle.Bold);
            btnNo.ForeColor = Color.FromArgb(160, 160, 170);
            btnNo.BackColor = Color.FromArgb(14, 14, 18);
            btnNo.FlatStyle = FlatStyle.Flat;
            btnNo.FlatAppearance.BorderColor = Color.FromArgb(32, 32, 40);
            btnNo.FlatAppearance.BorderSize = 1;
            btnNo.Location = new Point(225, 124);
            btnNo.Size = new Size(125, 38);
            btnNo.Cursor = Cursors.Hand;
            btnNo.TabStop = false;
            btnNo.MouseEnter += delegate { btnNo.BackColor = Color.FromArgb(24, 24, 30); btnNo.FlatAppearance.BorderColor = Color.FromArgb(48, 48, 58); };
            btnNo.MouseLeave += delegate { btnNo.BackColor = Color.FromArgb(14, 14, 18); btnNo.FlatAppearance.BorderColor = Color.FromArgb(32, 32, 40); };
            btnNo.Click += delegate { result = false; dlg.Close(); };
            dlg.Controls.Add(btnNo);

            dlg.AcceptButton = btnYes;
            dlg.CancelButton = btnNo;

            dlg.ShowDialog(this);
        }
        return result;
    }

    private void ShowUpdateDialog(string newVersion, string changelog, string downloadUrl)
    {
        if (this.InvokeRequired)
        {
            this.BeginInvoke(new Action(() => ShowUpdateDialog(newVersion, changelog, downloadUrl)));
            return;
        }

        Form dlg = new Form();
        dlg.Text = "Update Available - Quiz & Earn Bot";
        dlg.ClientSize = new Size(460, 280);
        dlg.StartPosition = FormStartPosition.CenterParent;
        dlg.FormBorderStyle = FormBorderStyle.FixedDialog;
        dlg.MaximizeBox = false;
        dlg.MinimizeBox = false;
        dlg.ShowInTaskbar = false;
        dlg.BackColor = Color.FromArgb(8, 8, 10);
        dlg.ForeColor = Color.FromArgb(220, 220, 225);
        dlg.AutoScroll = false;
        try { dlg.Icon = this.Icon; } catch {}
        dlg.HandleCreated += delegate {
            ApplyDwmDarkTo(dlg.Handle);
        };

        dlg.Paint += delegate(object s, PaintEventArgs pe) {
            ControlPaint.DrawBorder(pe.Graphics, dlg.ClientRectangle, Color.FromArgb(32, 32, 38), ButtonBorderStyle.Solid);
        };

        Label lblTitle = new Label();
        lblTitle.Text = string.Format("New Update Available! (v{0})", newVersion);
        lblTitle.Font = new Font("Segoe UI", 12.5f, FontStyle.Bold);
        lblTitle.ForeColor = Color.White;
        lblTitle.Location = new Point(22, 16);
        lblTitle.AutoSize = true;
        dlg.Controls.Add(lblTitle);

        Label lblSub = new Label();
        lblSub.Text = "A new version of Quiz & Earn Bot is ready to install:";
        lblSub.Font = new Font("Segoe UI", 8.5f);
        lblSub.ForeColor = Color.FromArgb(140, 140, 145);
        lblSub.Location = new Point(24, 44);
        lblSub.AutoSize = true;
        dlg.Controls.Add(lblSub);

        TextBox txtNotes = new TextBox();
        txtNotes.Multiline = true;
        txtNotes.ReadOnly = true;
        txtNotes.ScrollBars = ScrollBars.None;
        txtNotes.BackColor = Color.Black;
        txtNotes.ForeColor = Color.FromArgb(180, 180, 185);
        txtNotes.Font = new Font("Consolas", 8.5f);
        txtNotes.BorderStyle = BorderStyle.FixedSingle;
        txtNotes.Location = new Point(22, 68);
        txtNotes.Size = new Size(416, 75);
        txtNotes.Text = string.IsNullOrEmpty(changelog) ? "• General performance enhancements and bug fixes." : changelog;
        dlg.Controls.Add(txtNotes);

        Label lblStatus = new Label();
        lblStatus.Text = "";
        lblStatus.Font = new Font("Segoe UI", 8.5f);
        lblStatus.ForeColor = Color.FromArgb(160, 160, 165);
        lblStatus.Location = new Point(22, 150);
        lblStatus.Size = new Size(416, 20);
        dlg.Controls.Add(lblStatus);

        ProgressBar prg = new ProgressBar();
        prg.Location = new Point(22, 174);
        prg.Size = new Size(416, 6);
        prg.Visible = false;
        dlg.Controls.Add(prg);

        Button btnUpdate = new Button();
        btnUpdate.Text = "Update Now";
        btnUpdate.Font = new Font("Segoe UI", 9.5f, FontStyle.Bold);
        btnUpdate.ForeColor = Color.White;
        btnUpdate.BackColor = Color.FromArgb(24, 24, 28);
        btnUpdate.FlatStyle = FlatStyle.Flat;
        btnUpdate.FlatAppearance.BorderColor = Color.FromArgb(48, 48, 56);
        btnUpdate.FlatAppearance.BorderSize = 1;
        btnUpdate.Location = new Point(22, 210);
        btnUpdate.Size = new Size(202, 44);
        btnUpdate.Cursor = Cursors.Hand;
        dlg.Controls.Add(btnUpdate);

        Button btnLater = new Button();
        btnLater.Text = "Later";
        btnLater.Font = new Font("Segoe UI", 9.5f, FontStyle.Bold);
        btnLater.ForeColor = Color.FromArgb(135, 135, 140);
        btnLater.BackColor = Color.FromArgb(10, 10, 12);
        btnLater.FlatStyle = FlatStyle.Flat;
        btnLater.FlatAppearance.BorderColor = Color.FromArgb(24, 24, 28);
        btnLater.FlatAppearance.BorderSize = 1;
        btnLater.Location = new Point(236, 210);
        btnLater.Size = new Size(202, 44);
        btnLater.Cursor = Cursors.Hand;
        btnLater.Click += delegate {
            isUpdateIgnored = true;
            dlg.Close();
        };
        dlg.Controls.Add(btnLater);

        bool isDownloaded = false;
        string updaterBat = "";

        btnUpdate.Click += delegate {
            if (isDownloaded && !string.IsNullOrEmpty(updaterBat))
            {
                try
                {
                    ProcessStartInfo psi = new ProcessStartInfo();
                    psi.FileName = updaterBat;
                    psi.CreateNoWindow = true;
                    psi.UseShellExecute = false;
                    psi.WindowStyle = ProcessWindowStyle.Hidden;
                    Process.Start(psi);
                    Application.Exit();
                }
                catch (Exception ex)
                {
                    ShowDarkAlert("Update Error", "Could not launch updater: " + ex.Message);
                }
                return;
            }

            btnLater.Visible = false;
            btnUpdate.Enabled = false;
            btnUpdate.Location = new Point(22, 210);
            btnUpdate.Size = new Size(416, 44);
            btnUpdate.Text = "Downloading update...";
            prg.Visible = true;
            prg.Value = 0;
            lblStatus.Text = "Connecting to update server...";

            Thread downloadThread = new Thread(new ThreadStart(delegate {
                try
                {
                    System.Net.ServicePointManager.SecurityProtocol = (System.Net.SecurityProtocolType)3072;
                    string baseDir = AppDomain.CurrentDomain.BaseDirectory;
                    string tempExe = Path.Combine(baseDir, "QuizEarnBot_update.exe");
                    string finalExe = Path.Combine(baseDir, "QuizEarnBot.exe");
                    updaterBat = Path.Combine(baseDir, "apply_update.bat");

                    using (System.Net.WebClient wc = new System.Net.WebClient())
                    {
                        wc.Headers["User-Agent"] = "QuizEarnBot/" + APP_VERSION;
                        wc.DownloadProgressChanged += (s, pe) => {
                            dlg.BeginInvoke(new Action(() => {
                                prg.Value = pe.ProgressPercentage;
                                lblStatus.Text = string.Format("Downloading update... {0}%", pe.ProgressPercentage);
                                btnUpdate.Text = string.Format("Downloading {0}%...", pe.ProgressPercentage);
                            }));
                        };
                        wc.DownloadFile(downloadUrl, tempExe);
                    }

                    FileInfo fi = new FileInfo(tempExe);
                    if (!fi.Exists || fi.Length < 10240)
                    {
                        throw new Exception("Downloaded file is incomplete or invalid.");
                    }

                    string batScript = string.Format(
                        "@echo off\r\n" +
                        "timeout /t 1 /nobreak > nul\r\n" +
                        "copy /y \"{0}\" \"{1}\" > nul\r\n" +
                        "del \"{0}\" > nul\r\n" +
                        "start \"\" \"{1}\"\r\n" +
                        "del \"%~f0\" > nul\r\n",
                        tempExe, finalExe
                    );
                    File.WriteAllText(updaterBat, batScript);

                    isDownloaded = true;

                    dlg.BeginInvoke(new Action(() => {
                        prg.Value = 100;
                        lblStatus.Text = "✓ Update downloaded successfully! Restart the app to apply.";
                        lblStatus.ForeColor = Color.FromArgb(235, 235, 240);
                        btnUpdate.Text = "Restart App Now";
                        btnUpdate.BackColor = Color.FromArgb(32, 32, 38);
                        btnUpdate.FlatAppearance.BorderColor = Color.FromArgb(70, 70, 80);
                        btnUpdate.Enabled = true;
                    }));
                }
                catch (Exception ex)
                {
                    dlg.BeginInvoke(new Action(() => {
                        lblStatus.Text = "Download failed: " + ex.Message;
                        lblStatus.ForeColor = Color.FromArgb(220, 110, 110);
                        btnUpdate.Text = "Retry Download";
                        btnUpdate.Enabled = true;
                        btnLater.Visible = true;
                        btnUpdate.Size = new Size(202, 44);
                        btnLater.Location = new Point(236, 210);
                        btnLater.Size = new Size(202, 44);
                    }));
                }
            }));
            downloadThread.IsBackground = true;
            downloadThread.Start();
        };

        dlg.ShowDialog(this);
        dlg.Dispose();
    }

    private void CheckForUpdatesAsync(bool manualCheck)
    {
        Thread t = new Thread(new ThreadStart(delegate {
            try
            {
                if (manualCheck)
                {
                    SafeBeginInvoke(new Action(() => {
                        if (lblUpdateStatus != null) lblUpdateStatus.Text = "Checking update server...";
                        if (btnCheckUpdate != null) btnCheckUpdate.Enabled = false;
                    }));
                }

                System.Net.ServicePointManager.SecurityProtocol = (System.Net.SecurityProtocolType)3072;

                if (string.IsNullOrEmpty(updateFeedUrl) || updateFeedUrl.Contains("raw.githubusercontent.com/THESHMIL/"))
                {
                    updateFeedUrl = "https://raw.githubusercontent.com/THESHMIL0/QuizEarnBot/main/version.json";
                }

                string json = "";
                using (System.Net.WebClient wc = new System.Net.WebClient())
                {
                    wc.Headers["User-Agent"] = "QuizEarnBot/" + APP_VERSION;
                    wc.Headers["Cache-Control"] = "no-cache";
                    json = wc.DownloadString(updateFeedUrl);
                }

                if (!string.IsNullOrEmpty(json))
                {
                    Match mv = Regex.Match(json, @"""version"":\s*""([^""]+)""");
                    if (mv.Success) remoteVersion = mv.Groups[1].Value.Trim();

                    Match mvc = Regex.Match(json, @"""(?:versionCode|version_code)"":\s*(\d+)");
                    if (mvc.Success) remoteVersionCode = int.Parse(mvc.Groups[1].Value);

                    Match murl = Regex.Match(json, @"""(?:downloadUrl|download_url)"":\s*""([^""]+)""");
                    if (murl.Success) remoteDownloadUrl = murl.Groups[1].Value.Trim();

                    Match mlog = Regex.Match(json, @"""changelog"":\s*""((?:\\.|[^""\\])*)""");
                    if (mlog.Success) remoteChangelog = mlog.Groups[1].Value.Replace("\\n", "\n").Replace("\\\"", "\"");

                    bool isNewerVersion = (remoteVersionCode > APP_VERSION_CODE) || 
                                          (!string.IsNullOrEmpty(remoteVersion) && remoteVersion != APP_VERSION && remoteVersionCode >= APP_VERSION_CODE);

                    if (isNewerVersion && !string.IsNullOrEmpty(remoteDownloadUrl))
                    {
                        isUpdateAvailable = true;

                        SafeBeginInvoke(new Action(() => {
                            if (lblUpdateStatus != null)
                            {
                                lblUpdateStatus.Text = string.Format("New Version v{0} available!", remoteVersion);
                                lblUpdateStatus.ForeColor = Color.FromArgb(235, 235, 240);
                            }
                            if (btnNavSettings != null)
                            {
                                btnNavSettings.Text = "SETTINGS ✦";
                            }
                            if (btnCheckUpdate != null) btnCheckUpdate.Enabled = true;

                            Log(string.Format("[Update] New version v{0} detected! Opening update prompt...", remoteVersion));
                            ShowUpdateDialog(remoteVersion, remoteChangelog, remoteDownloadUrl);
                        }));
                        return;
                    }
                }

                // Up to date
                isUpdateAvailable = false;
                SafeBeginInvoke(new Action(() => {
                    if (lblUpdateStatus != null)
                    {
                        lblUpdateStatus.Text = string.Format("Status: Up to date (v{0}).", APP_VERSION);
                        lblUpdateStatus.ForeColor = Color.FromArgb(140, 140, 145);
                    }
                    if (btnNavSettings != null) btnNavSettings.Text = "SETTINGS";
                    if (btnCheckUpdate != null) btnCheckUpdate.Enabled = true;
                    if (manualCheck)
                    {
                        Log("[Update] App is up to date (v" + APP_VERSION + ").");
                        ShowDarkAlert("Quiz & Earn Bot - Up to Date", "You are already running the latest version of Quiz & Earn Bot (v" + APP_VERSION + ")!\n\nNo updates are needed.");
                    }
                }));
            }
            catch
            {
                SafeBeginInvoke(new Action(() => {
                    if (lblUpdateStatus != null)
                    {
                        lblUpdateStatus.Text = "Status: Up to date (offline mode).";
                        lblUpdateStatus.ForeColor = Color.FromArgb(135, 135, 140);
                    }
                    if (btnCheckUpdate != null) btnCheckUpdate.Enabled = true;
                    if (manualCheck)
                    {
                        ShowDarkAlert("Update Check", "Could not reach the update server.\nPlease check your internet connection.");
                    }
                }));
            }
        }));
        t.IsBackground = true;
        t.Start();
    }

    private void AddClaimHistory(string text)
    {
        try
        {
            string entry = string.Format("[{0}] {1}", DateTime.Now.ToString("hh:mm tt"), text);
            if (claimsHistory == null) claimsHistory = new List<string>();
            claimsHistory.Insert(0, entry);
            if (claimsHistory.Count > 100)
            {
                claimsHistory.RemoveRange(100, claimsHistory.Count - 100);
            }
            SaveStatsToFile();
            UpdateDataDashboard();
        }
        catch {}
    }

    private void UpdateDataDashboard()
    {
        if (this.InvokeRequired)
        {
            this.BeginInvoke(new Action(UpdateDataDashboard));
            return;
        }

        if (lblDataCoinsVal != null)
            lblDataCoinsVal.Text = string.Format("+{0:N0} Coins", todayCoinsClaimed);

        if (lblData25GemsVal != null)
            lblData25GemsVal.Text = string.Format("{0} times", today25GemsCount);

        if (lblData25GemsSub != null)
            lblData25GemsSub.Text = string.Format("{0} Gems earned from quizzes today", gemsEarned);

        if (lblDataOffersVal != null)
            lblDataOffersVal.Text = string.Format("{0} claims", todayOffersClaimed);

        if (lblDataQuizzesVal != null)
            lblDataQuizzesVal.Text = string.Format("{0} Solved | {1} Ads", todaySolved, adsClosed);

        if (lblLifetimeCoins != null)
            lblLifetimeCoins.Text = string.Format("{0:N0} Coins", totalCoinsClaimed);

        if (lblLifetime25Gems != null)
            lblLifetime25Gems.Text = string.Format("{0} times", total25GemsCount);

        if (lblLifetimeQuizzes != null)
            lblLifetimeQuizzes.Text = string.Format("{0:N0} Solved", (totalGems + todaySolved));

        if (lblDataDate != null)
            lblDataDate.Text = "Today: " + DateTime.Now.ToString("dddd, MMM d, yyyy");

        if (txtClaimsLog != null)
        {
            txtClaimsLog.Clear();
            if (claimsHistory == null || claimsHistory.Count == 0)
            {
                txtClaimsLog.SelectionColor = Color.FromArgb(90, 90, 95);
                txtClaimsLog.AppendText("No claims recorded yet today.\nStart the 1-Click Auto-Offer Claimer or Quiz Solver to see live earnings!");
            }
            else
            {
                for (int i = 0; i < claimsHistory.Count; i++)
                {
                    string item = claimsHistory[i];
                    if (item.Contains("Coins") || item.Contains("+") || item.Contains("25 Gem") || item.Contains("Milestone"))
                        txtClaimsLog.SelectionColor = Color.FromArgb(220, 220, 225);
                    else
                        txtClaimsLog.SelectionColor = Color.FromArgb(140, 140, 145);

                    txtClaimsLog.AppendText(item + "\n");
                }
            }
        }
    }

    private void UpdateDashboard(string lastCaptchaDigit)
    {
        if (this.InvokeRequired)
        {
            SafeBeginInvoke(new Action(delegate { UpdateDashboard(lastCaptchaDigit); }));
            return;
        }

        lblTodaySolved.Text = string.Format("{0}\nToday Solved", todaySolved);
        lblGems.Text = string.Format("{0}/25\nOffer Progress", currentOfferGems);
        lblCaptcha.Text = string.Format("{0}\nLast Captcha", lastCaptchaDigit);
        lblStats.Text = string.Format("{0}\nAds Watched", adsClosed);

        UpdateDataDashboard();
    }

    private void UpdatePortLabel(string text)
    {
        if (this.InvokeRequired)
        {
            SafeBeginInvoke(new Action(delegate { UpdatePortLabel(text); }));
            return;
        }
        if (text.StartsWith("Connected:") || text.StartsWith("ADB:") || text.StartsWith("Waiting") || text.StartsWith("Device") || text.StartsWith("Allow"))
        {
            lblPort.Text = text;
        }
        else
        {
            lblPort.Text = "ADB: " + text;
        }
    }

    private string RunAdb(string arguments)
    {
        try
        {
            Process p = new Process();
            p.StartInfo.FileName = adbExe;
            bool isGlobalCmd = string.IsNullOrEmpty(adbPort) || 
                               arguments.StartsWith("devices") || 
                               arguments.StartsWith("connect") || 
                               arguments.StartsWith("disconnect") || 
                               arguments.StartsWith("reconnect") || 
                               arguments.StartsWith("kill-server") || 
                               arguments.StartsWith("start-server");
            p.StartInfo.Arguments = isGlobalCmd ? arguments : string.Format("-s {0} {1}", adbPort, arguments);
            p.StartInfo.UseShellExecute = false;
            p.StartInfo.RedirectStandardOutput = true;
            p.StartInfo.RedirectStandardError = true;
            p.StartInfo.CreateNoWindow = true;

            StringBuilder sb = new StringBuilder();
            p.OutputDataReceived += delegate(object sender, DataReceivedEventArgs e) {
                if (e.Data != null) sb.AppendLine(e.Data);
            };
            p.ErrorDataReceived += delegate(object sender, DataReceivedEventArgs e) {
                if (e.Data != null) sb.AppendLine(e.Data);
            };

            p.Start();
            p.BeginOutputReadLine();
            p.BeginErrorReadLine();
            bool exited = p.WaitForExit(6000);
            if (!exited)
            {
                try { p.Kill(); } catch {}
            }
            string res = sb.ToString();
            if ((res.Contains("device not found") || res.Contains("device offline")) && !isGlobalCmd && adbPort != "emulator-5554")
            {
                adbPort = "emulator-5554";
                return RunAdb(arguments);
            }
            return res;
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
                if (w > 0 && h > 0)
                {
                    if (devWidth != w || devHeight != h)
                    {
                        devWidth = w;
                        devHeight = h;
                        Log(string.Format("[Screen] Device resolution detected: {0}x{1}", devWidth, devHeight));
                    }
                }
            }
        }
        catch {}
    }

    private void AdbTap(int x, int y)
    {
        RunAdb(string.Format("shell input tap {0} {1}", x, y));
    }

    private void AdbTapRef(int refX, int refY)
    {
        if (devWidth <= 0 || devHeight <= 0 || (devWidth == 900 && devHeight == 1600))
        {
            AdbTap(refX, refY);
            return;
        }
        int actualX = (int)Math.Round((double)refX * devWidth / 900.0);
        int actualY = (int)Math.Round((double)refY * devHeight / 1600.0);
        AdbTap(actualX, actualY);
    }

    private void AdbKey(int keyCode)
    {
        RunAdb(string.Format("shell input keyevent {0}", keyCode));
    }

    private static bool IsTcpPortOpen(int port)
    {
        try
        {
            using (var tcp = new TcpClient())
            {
                var ar = tcp.BeginConnect("127.0.0.1", port, null, null);
                bool ok = ar.AsyncWaitHandle.WaitOne(20);
                if (ok)
                {
                    tcp.EndConnect(ar);
                    return true;
                }
            }
        }
        catch {}
        return false;
    }

    private List<string> DiscoverUniversalAdbPorts()
    {
        List<string> candidatePorts = new List<string>();

        // 1. BlueStacks 5 & 4 bluestacks.conf files across drives
        string[] confCandidates = new string[]
        {
            @"C:\ProgramData\BlueStacks_nxt\bluestacks.conf",
            @"D:\ProgramData\BlueStacks_nxt\bluestacks.conf",
            @"E:\ProgramData\BlueStacks_nxt\bluestacks.conf",
            @"C:\ProgramData\BlueStacks\bluestacks.conf",
            @"D:\ProgramData\BlueStacks\bluestacks.conf"
        };

        foreach (string conf in confCandidates)
        {
            try
            {
                if (File.Exists(conf))
                {
                    string[] lines = File.ReadAllLines(conf);
                    foreach (string line in lines)
                    {
                        Match m = Regex.Match(line, @"(?:status\.adb_port|adb_port)=""(\d+)""");
                        if (m.Success)
                        {
                            string p = "127.0.0.1:" + m.Groups[1].Value;
                            if (!candidatePorts.Contains(p)) candidatePorts.Add(p);
                        }
                    }
                }
            }
            catch {}
        }

        // 2. Comprehensive well-known emulator ADB port list (Probing listening ports)
        int[] commonPorts = new int[]
        {
            5555, 5556, 5565, 5575, 5585, 5595, 5605, 5615, 5625, 5635, 5645, 5655, // BlueStacks, LDPlayer, Genymotion
            62001, 62025, 62026, 59865, // NoxPlayer
            21503, 21513, 21523,        // MEmu
            7555, 16384, 16416,         // MuMu Player
            58526                       // Windows Subsystem for Android (WSA)
        };

        foreach (int port in commonPorts)
        {
            string ep = "127.0.0.1:" + port;
            if (!candidatePorts.Contains(ep) && IsTcpPortOpen(port))
            {
                candidatePorts.Add(ep);
            }
        }

        // Fallback common ports
        if (!candidatePorts.Contains("127.0.0.1:5575")) candidatePorts.Add("127.0.0.1:5575");
        if (!candidatePorts.Contains("127.0.0.1:5555")) candidatePorts.Add("127.0.0.1:5555");
        if (!candidatePorts.Contains("127.0.0.1:5556")) candidatePorts.Add("127.0.0.1:5556");
        if (!candidatePorts.Contains("emulator-5554")) candidatePorts.Add("emulator-5554");

        return candidatePorts;
    }

    private bool IsEmulatorDevice(string id)
    {
        if (string.IsNullOrEmpty(id)) return false;
        string lower = id.ToLower();
        return lower.StartsWith("emulator-") || lower.StartsWith("127.0.0.1:") || lower.StartsWith("localhost:");
    }

    private bool EnsureConnected()
    {
        // 1. Check existing connected devices (ONLY BlueStacks / Emulators! Physical phones are ignored)
        string devOut = RunAdb("devices");
        foreach (string line in devOut.Split('\n'))
        {
            string trimmed = line.Trim();
            if (string.IsNullOrEmpty(trimmed) || trimmed.StartsWith("List of") || trimmed.StartsWith("*"))
                continue;

            string[] parts = trimmed.Split(new char[] { ' ', '\t' }, StringSplitOptions.RemoveEmptyEntries);
            if (parts.Length >= 2 && parts[1].ToLower() == "device")
            {
                string id = parts[0];
                if (IsEmulatorDevice(id))
                {
                    adbPort = id;
                    UpdatePortLabel(adbPort);
                    UpdateScreenResolution();
                    return true;
                }
            }
        }

        // 2. Discover and connect listening emulator ports
        List<string> ports = DiscoverUniversalAdbPorts();
        foreach (string port in ports)
        {
            if (port.StartsWith("127.0.0.1:"))
            {
                string res = RunAdb(string.Format("connect {0}", port));
                if (res.Contains("connected to") || res.Contains("already connected"))
                {
                    adbPort = port;
                    UpdatePortLabel(adbPort);
                    UpdateScreenResolution();
                    return true;
                }
            }
        }

        // 3. Final verification: ONLY match emulators
        devOut = RunAdb("devices");
        foreach (string line in devOut.Split('\n'))
        {
            string trimmed = line.Trim();
            if (string.IsNullOrEmpty(trimmed) || trimmed.StartsWith("List of") || trimmed.StartsWith("*"))
                continue;

            string[] parts = trimmed.Split(new char[] { ' ', '\t' }, StringSplitOptions.RemoveEmptyEntries);
            if (parts.Length >= 2 && parts[1].ToLower() == "device")
            {
                string id = parts[0];
                if (IsEmulatorDevice(id))
                {
                    adbPort = id;
                    UpdatePortLabel(adbPort);
                    UpdateScreenResolution();
                    return true;
                }
            }
        }

        UpdatePortLabel("Start BlueStacks! (Phone ignored)");
        Log("[ADB] No BlueStacks instance found. Physical phones are ignored (use the Mobile APK on phone).");
        return false;
    }

    private bool IsBlueStacksProcessRunning()
    {
        try
        {
            Process[] procs = Process.GetProcessesByName("HD-Player");
            return procs != null && procs.Length > 0;
        }
        catch
        {
            return false;
        }
    }

    private string DetectBlueStacks(out string instanceArg)
    {
        instanceArg = "";
        string bstExe = "";

        // 1. Check standard 64-bit and 32-bit Program Files
        string[] candidates = new string[] {
            @"C:\Program Files\BlueStacks_nxt\HD-Player.exe",
            @"C:\Program Files (x86)\BlueStacks_nxt\HD-Player.exe",
            @"C:\Program Files\BlueStacks\HD-Player.exe",
            @"C:\Program Files (x86)\BlueStacks\HD-Player.exe"
        };
        foreach (string c in candidates)
        {
            if (File.Exists(c)) { bstExe = c; break; }
        }

        // 2. Registry fallback
        if (string.IsNullOrEmpty(bstExe))
        {
            try
            {
                using (RegistryKey rk = Registry.LocalMachine.OpenSubKey(@"SOFTWARE\BlueStacks_nxt"))
                {
                    if (rk != null)
                    {
                        object dir = rk.GetValue("InstallDir");
                        if (dir != null)
                        {
                            string p = Path.Combine(dir.ToString(), "HD-Player.exe");
                            if (File.Exists(p)) bstExe = p;
                        }
                    }
                }
            }
            catch {}
        }

        // 3. Detect instance name from bluestacks.conf
        string[] confCandidates = new string[]
        {
            @"C:\ProgramData\BlueStacks_nxt\bluestacks.conf",
            @"D:\ProgramData\BlueStacks_nxt\bluestacks.conf",
            @"E:\ProgramData\BlueStacks_nxt\bluestacks.conf",
            @"C:\ProgramData\BlueStacks\bluestacks.conf"
        };

        foreach (string confPath in confCandidates)
        {
            if (File.Exists(confPath))
            {
                try
                {
                    string conf = File.ReadAllText(confPath);
                    string portNum = "5555";
                    if (!string.IsNullOrEmpty(adbPort) && adbPort.Contains(":"))
                    {
                        string[] pp = adbPort.Split(':');
                        if (pp.Length == 2) portNum = pp[1].Trim();
                    }

                    Match m = Regex.Match(conf, @"bst\.instance\.([^.]+)\.status\.adb_port=""" + portNum + @"""");
                    if (m.Success)
                    {
                        instanceArg = "--instance " + m.Groups[1].Value.Trim();
                        break;
                    }
                    else
                    {
                        Match m5555 = Regex.Match(conf, @"bst\.instance\.([^.]+)\.status\.adb_port=""5555""");
                        if (m5555.Success)
                        {
                            instanceArg = "--instance " + m5555.Groups[1].Value.Trim();
                            break;
                        }
                        else
                        {
                            Match mAny = Regex.Match(conf, @"bst\.instance\.([^.]+)\.display_name");
                            if (mAny.Success)
                            {
                                instanceArg = "--instance " + mAny.Groups[1].Value.Trim();
                                break;
                            }
                        }
                    }
                }
                catch {}
            }
        }

        return bstExe;
    }

    private bool EnsureBlueStacksAliveAndReady(string callerContext)
    {
        if (!autoRecoverBlueStacks) return false;

        bool bstRunning = IsBlueStacksProcessRunning();
        bool adbAlive = false;

        if (bstRunning)
        {
            try
            {
                string state = RunAdb("get-state").Trim();
                if (state.Contains("device"))
                {
                    adbAlive = true;
                }
            }
            catch {}
        }

        if (bstRunning && adbAlive)
        {
            return true;
        }

        Log(string.Format("[ðŸ›¡ï¸ Watchdog] BlueStacks crash/closure detected during {0}! Initializing recovery...", callerContext));

        if (!bstRunning)
        {
            string bstArgs;
            string bstExe = DetectBlueStacks(out bstArgs);
            if (string.IsNullOrEmpty(bstExe) || !File.Exists(bstExe))
            {
                Log("[ðŸ›¡ï¸ Watchdog] Could not find BlueStacks executable (HD-Player.exe) to auto-launch.");
                return false;
            }

            Log(string.Format("[ðŸ›¡ï¸ Watchdog] Auto-launching BlueStacks ({0} {1})...", Path.GetFileName(bstExe), bstArgs));
            try
            {
                ProcessStartInfo psi = new ProcessStartInfo();
                psi.FileName = bstExe;
                psi.Arguments = bstArgs;
                psi.UseShellExecute = true;
                Process.Start(psi);
            }
            catch (Exception ex)
            {
                Log("[ðŸ›¡ï¸ Watchdog] Error launching BlueStacks: " + ex.Message);
                return false;
            }
        }
        else
        {
            Log("[ðŸ›¡ï¸ Watchdog] BlueStacks process is running but ADB connection dropped. Waiting for ADB recovery...");
        }

        // Wait for Android OS to boot: sys.boot_completed == 1
        Log("[ðŸ›¡ï¸ Watchdog] Waiting for BlueStacks Android OS to finish booting (up to 90s)...");
        int waitSeconds = 0;
        bool bootCompleted = false;

        while (waitSeconds < 90)
        {
            Thread.Sleep(3000);
            waitSeconds += 3;

            RunAdb("connect 127.0.0.1:5555");

            string bootProp = RunAdb("shell getprop sys.boot_completed").Trim();
            if (bootProp == "1")
            {
                bootCompleted = true;
                break;
            }

            if (waitSeconds % 15 == 0)
            {
                Log(string.Format("[ðŸ›¡ï¸ Watchdog] Still booting Android OS... ({0}s elapsed)", waitSeconds));
            }
        }

        if (!bootCompleted)
        {
            Log("[ðŸ›¡ï¸ Watchdog] Android boot timeout (90s exceeded). Could not complete BlueStacks recovery.");
            return false;
        }

        Log("[ðŸ›¡ï¸ Watchdog] BlueStacks Android OS boot completed! Stabilizing services (4s)...");
        Thread.Sleep(4000);

        EnsureConnected();

        Log("[ðŸ›¡ï¸ Watchdog] Relaunching Quiz & Earn to resume automation...");
        RunAdb("shell monkey -p com.quiz.earn.money.game -c android.intent.category.LAUNCHER 1");
        Thread.Sleep(3500);

        Log(string.Format("[ðŸ›¡ï¸ Watchdog] Recovery successful! Resuming {0}...", callerContext));
        return true;
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

    private string ExtractPackageName(string rawFocus)
    {
        if (string.IsNullOrEmpty(rawFocus)) return "";
        Match m = Regex.Match(rawFocus, @"([a-zA-Z0-9_]+(?:\.[a-zA-Z0-9_]+)+)/");
        if (m.Success) return m.Groups[1].Value;
        return "";
    }

    private void StartUsageBooster(int minutes)
    {
        if (isBoosting)
        {
            Log(string.Format("[⚡ Booster] Boost is already active ({0}s left). Click Stop first if you want to switch.", boostSecondsRemaining));
            return;
        }

        if (!isAdbConnected)
        {
            Log("[⚡ Booster] ADB not connected yet! Turn on ADB in your emulator or phone first.");
            return;
        }

        isBoosting = true;
        boostTotalSeconds = minutes * 60;
        boostSecondsRemaining = boostTotalSeconds;

        boosterThread = new Thread(new ParameterizedThreadStart(BoosterWorkerLoop));
        boosterThread.IsBackground = true;
        boosterThread.Start(minutes);
    }

    private void BtnBoostStop_Click(object sender, EventArgs e)
    {
        if (isBoosting)
        {
            isBoosting = false;
            Log("[⚡ Booster] Usage Booster stopped by user.");
            SafeBeginInvoke(new Action(() => {
                if (lblBoostStatus != null)
                {
                    lblBoostStatus.Text = "Booster: Stopped";
                    lblBoostStatus.ForeColor = Color.FromArgb(135, 135, 140);
                }
            }));
        }
        else
        {
            Log("[⚡ Booster] No boost session is currently running.");
        }
    }

    private void BoosterWorkerLoop(object param)
    {
        int minutes = (int)param;
        Log(string.Format("[⚡ Booster] Starting {0}-minute AFK Usage Boost...", minutes));

        try
        {
            // 1. Identify or launch target sponsored app
            string currentFocus = GetCurrentFocus();
            string targetPkg = "";

            if (!string.IsNullOrEmpty(currentFocus) && 
                !currentFocus.Contains("com.quiz.earn.money.game") && 
                !currentFocus.Contains("launcher") && 
                !currentFocus.Contains("systemui"))
            {
                // Already inside target app
                targetPkg = ExtractPackageName(currentFocus);
                if (string.IsNullOrEmpty(targetPkg)) targetPkg = currentFocus;
                Log(string.Format("[⚡ Booster] Active in target offer app: {0}", targetPkg));
            }
            else
            {
                // Currently in Quiz & Earn: scan screen for "Open App" button
                Log("[⚡ Booster] Scanning screen for 'Open App' button...");
                RunAdb("shell rm -f /sdcard/boost_ui.xml && shell uiautomator dump --compressed /sdcard/boost_ui.xml");
                string xml = RunAdb("shell cat /sdcard/boost_ui.xml");

                Match m = Regex.Match(xml, @"text=""Open App""[^>]*bounds=""\[(\d+),(\d+)\]\[(\d+),(\d+)\]""", RegexOptions.IgnoreCase);
                if (m.Success)
                {
                    int x1 = int.Parse(m.Groups[1].Value);
                    int y1 = int.Parse(m.Groups[2].Value);
                    int x2 = int.Parse(m.Groups[3].Value);
                    int y2 = int.Parse(m.Groups[4].Value);
                    int tapX = (x1 + x2) / 2;
                    int tapY = (y1 + y2) / 2;
                    Log(string.Format("[⚡ Booster] Found 'Open App' button! Launching offer app at ({0}, {1})...", tapX, tapY));
                    AdbTap(tapX, tapY);
                    Thread.Sleep(3000);
                }

                currentFocus = GetCurrentFocus();
                if (!string.IsNullOrEmpty(currentFocus) && !currentFocus.Contains("com.quiz.earn.money.game") && !currentFocus.Contains("launcher"))
                {
                    targetPkg = ExtractPackageName(currentFocus);
                    if (string.IsNullOrEmpty(targetPkg)) targetPkg = currentFocus;
                    Log(string.Format("[⚡ Booster] Launched offer app: {0}", targetPkg));
                }
                else
                {
                    Log("[⚡ Booster] Note: If app did not open automatically, tap 'Open App' manually. Booster will keep it alive!");
                }
            }

            boostTargetPackage = targetPkg;
            DismissAppDialogs();

            int keepAliveCounter = 0;
            while (isBoosting && boostSecondsRemaining > 0)
            {
                int mLeft = boostSecondsRemaining / 60;
                int sLeft = boostSecondsRemaining % 60;
                string pkgDisplay = string.IsNullOrEmpty(boostTargetPackage) ? "Offer App" : boostTargetPackage;
                if (pkgDisplay.Contains("."))
                {
                    string[] parts = pkgDisplay.Split('.');
                    pkgDisplay = parts[parts.Length - 1];
                }

                string statusText = string.Format("⚡ {0:D2}:{1:D2} left ({2})", mLeft, sLeft, pkgDisplay);
                SafeBeginInvoke(new Action(() => {
                    if (lblBoostStatus != null)
                    {
                        lblBoostStatus.Text = statusText;
                        lblBoostStatus.ForeColor = Color.FromArgb(180, 180, 185);
                    }
                }));

                // Auto-dismiss blocking notification/permission dialogs if they appear
                if (keepAliveCounter == 3 || keepAliveCounter == 7 || (keepAliveCounter > 0 && keepAliveCounter % 20 == 0))
                {
                    DismissAppDialogs();
                }

                // Every 15 seconds: ensure app is active and send non-intrusive micro-swipe
                if (keepAliveCounter % 15 == 0)
                {
                    if (autoRecoverBlueStacks && !IsBlueStacksProcessRunning())
                    {
                        EnsureBlueStacksAliveAndReady("Usage Booster Loop");
                    }
                    string curFocus = GetCurrentFocus();
                    if (!string.IsNullOrEmpty(boostTargetPackage) && !string.IsNullOrEmpty(curFocus) && 
                        !curFocus.Contains(boostTargetPackage) && !curFocus.Contains("com.quiz.earn.money.game"))
                    {
                        // Relaunch if minimized
                        RunAdb(string.Format("shell monkey -p {0} -c android.intent.category.LAUNCHER 1", boostTargetPackage));
                    }

                    // Keep screen and app active via gentle swipe in safe space (center drag 20px)
                    RunAdb("shell input swipe 450 700 450 720 120");
                }

                Thread.Sleep(1000);
                boostSecondsRemaining--;
                keepAliveCounter++;
            }

            if (isBoosting)
            {
                Log(string.Format("[⚡ Booster] COMPLETE! {0} minute(s) fulfilled.", minutes));
                Log("[⚡ Booster] Returning to Quiz & Earn to collect reward...");

                // Switch back to Quiz & Earn
                RunAdb("shell monkey -p com.quiz.earn.money.game -c android.intent.category.LAUNCHER 1");
                Thread.Sleep(1500);

                try { System.Media.SystemSounds.Asterisk.Play(); } catch {}

                SafeBeginInvoke(new Action(() => {
                    if (lblBoostStatus != null)
                    {
                        lblBoostStatus.Text = "⚡ Completed! Ready to claim coins.";
                        lblBoostStatus.ForeColor = Color.FromArgb(200, 200, 205);
                    }
                }));
            }
        }
        catch (Exception ex)
        {
            Log("[⚡ Booster] Error: " + ex.Message);
        }
        finally
        {
            isBoosting = false;
        }
    }

    private bool TryFindNodeBounds(string xml, string textKeyword, out int centerX, out int centerY)
    {
        centerX = 0;
        centerY = 0;
        try
        {
            if (string.IsNullOrEmpty(xml) || string.IsNullOrEmpty(textKeyword)) return false;

            MatchCollection nodes = Regex.Matches(xml, @"<node\s+[^>]*>", RegexOptions.Singleline);
            
            // Pass 1: Look for exact text or content-desc match on a clickable or Button node
            foreach (Match n in nodes)
            {
                string tag = n.Value;
                bool isClickable = tag.IndexOf("clickable=\"true\"", StringComparison.OrdinalIgnoreCase) >= 0;
                bool isButton = tag.IndexOf("Button", StringComparison.OrdinalIgnoreCase) >= 0;
                
                bool isExactMatch = tag.IndexOf(string.Format("text=\"{0}\"", textKeyword), StringComparison.OrdinalIgnoreCase) >= 0 ||
                                    tag.IndexOf(string.Format("content-desc=\"{0}\"", textKeyword), StringComparison.OrdinalIgnoreCase) >= 0;

                if (isExactMatch && (isClickable || isButton))
                {
                    Match bm = Regex.Match(tag, @"bounds=""\[(\d+),(\d+)\]\[(\d+),(\d+)\]""");
                    if (bm.Success)
                    {
                        int x1 = int.Parse(bm.Groups[1].Value);
                        int y1 = int.Parse(bm.Groups[2].Value);
                        int x2 = int.Parse(bm.Groups[3].Value);
                        int y2 = int.Parse(bm.Groups[4].Value);
                        if (y2 < 1560)
                        {
                            centerX = (x1 + x2) / 2;
                            centerY = (y1 + y2) / 2;
                            return true;
                        }
                    }
                }
            }

            // Pass 2: Look for exact match on ANY node
            foreach (Match n in nodes)
            {
                string tag = n.Value;
                bool isExactMatch = tag.IndexOf(string.Format("text=\"{0}\"", textKeyword), StringComparison.OrdinalIgnoreCase) >= 0 ||
                                    tag.IndexOf(string.Format("content-desc=\"{0}\"", textKeyword), StringComparison.OrdinalIgnoreCase) >= 0;

                if (isExactMatch)
                {
                    Match bm = Regex.Match(tag, @"bounds=""\[(\d+),(\d+)\]\[(\d+),(\d+)\]""");
                    if (bm.Success)
                    {
                        int x1 = int.Parse(bm.Groups[1].Value);
                        int y1 = int.Parse(bm.Groups[2].Value);
                        int x2 = int.Parse(bm.Groups[3].Value);
                        int y2 = int.Parse(bm.Groups[4].Value);
                        if (y2 < 1560)
                        {
                            centerX = (x1 + x2) / 2;
                            centerY = (y1 + y2) / 2;
                            return true;
                        }
                    }
                }
            }

            // Pass 3: Look for clickable nodes containing keyword (excluding descriptive helper text)
            foreach (Match n in nodes)
            {
                string tag = n.Value;
                bool isClickable = tag.IndexOf("clickable=\"true\"", StringComparison.OrdinalIgnoreCase) >= 0;
                bool isButton = tag.IndexOf("Button", StringComparison.OrdinalIgnoreCase) >= 0;
                bool isLongInstruction = tag.IndexOf("to start using", StringComparison.OrdinalIgnoreCase) >= 0;

                if ((isClickable || isButton) && !isLongInstruction && tag.IndexOf(textKeyword, StringComparison.OrdinalIgnoreCase) >= 0)
                {
                    Match bm = Regex.Match(tag, @"bounds=""\[(\d+),(\d+)\]\[(\d+),(\d+)\]""");
                    if (bm.Success)
                    {
                        int x1 = int.Parse(bm.Groups[1].Value);
                        int y1 = int.Parse(bm.Groups[2].Value);
                        int x2 = int.Parse(bm.Groups[3].Value);
                        int y2 = int.Parse(bm.Groups[4].Value);
                        if (y2 < 1560)
                        {
                            centerX = (x1 + x2) / 2;
                            centerY = (y1 + y2) / 2;
                            return true;
                        }
                    }
                }
            }

            // Pass 4: Fallback to any node containing keyword (excluding descriptive helper text)
            foreach (Match n in nodes)
            {
                string tag = n.Value;
                bool isLongInstruction = tag.IndexOf("to start using", StringComparison.OrdinalIgnoreCase) >= 0;
                if (!isLongInstruction && tag.IndexOf(textKeyword, StringComparison.OrdinalIgnoreCase) >= 0)
                {
                    Match bm = Regex.Match(tag, @"bounds=""\[(\d+),(\d+)\]\[(\d+),(\d+)\]""");
                    if (bm.Success)
                    {
                        int x1 = int.Parse(bm.Groups[1].Value);
                        int y1 = int.Parse(bm.Groups[2].Value);
                        int x2 = int.Parse(bm.Groups[3].Value);
                        int y2 = int.Parse(bm.Groups[4].Value);
                        if (y2 < 1560)
                        {
                            centerX = (x1 + x2) / 2;
                            centerY = (y1 + y2) / 2;
                            return true;
                        }
                    }
                }
            }
        }
        catch {}
        return false;
    }

    private bool TryFindExactNodeBounds(string xml, string exactText, out int centerX, out int centerY)
    {
        return TryFindNodeBounds(xml, exactText, out centerX, out centerY);
    }

    private string DumpAndReadXml(string remotePath)
    {
        try
        {
            RunAdb(string.Format("shell uiautomator dump {0}", remotePath));
            string content = RunAdb(string.Format("shell cat {0}", remotePath));
            return content ?? "";
        }
        catch { return ""; }
    }

    private bool DismissAppDialogs()
    {
        try
        {
            string cur = GetCurrentFocus();
            if (string.IsNullOrEmpty(cur) || cur.Contains("com.quiz.earn.money.game"))
            {
                return false;
            }

            string xml = DumpAndReadXml("/sdcard/app_popup.xml");
            if (string.IsNullOrEmpty(xml)) return false;

            MatchCollection nodes = Regex.Matches(xml, @"<node\s+[^>]*>", RegexOptions.Singleline);
            
            // Priority 1: Check for "Don't allow" variants or "Deny"
            foreach (Match n in nodes)
            {
                string tag = n.Value;
                bool isDeny = Regex.IsMatch(tag, @"text=""[^""]*don.*?allow[^""]*""", RegexOptions.IgnoreCase) ||
                              Regex.IsMatch(tag, @"text=""[^""]*\bdeny\b[^""]*""", RegexOptions.IgnoreCase) ||
                              Regex.IsMatch(tag, @"resource-id=""[^""]*permission_deny[^""]*""", RegexOptions.IgnoreCase);

                if (isDeny)
                {
                    Match bm = Regex.Match(tag, @"bounds=""\[(\d+),(\d+)\]\[(\d+),(\d+)\]""");
                    if (bm.Success)
                    {
                        int x1 = int.Parse(bm.Groups[1].Value);
                        int y1 = int.Parse(bm.Groups[2].Value);
                        int x2 = int.Parse(bm.Groups[3].Value);
                        int y2 = int.Parse(bm.Groups[4].Value);
                        int cx = (x1 + x2) / 2;
                        int cy = (y1 + y2) / 2;
                        if (cx > 0 && cy > 0 && cx < 900 && cy < 1600)
                        {
                            Log(string.Format("[💎 Claimer] Dismissing notification/permission dialog ('Don't allow') at ({0}, {1})...", cx, cy));
                            AdbTap(cx, cy);
                            Thread.Sleep(1200);
                            return true;
                        }
                    }
                }
            }

            // Priority 2: Check for other common startup modal dismissal buttons: "Not now", "Maybe later", "Dismiss"
            foreach (Match n in nodes)
            {
                string tag = n.Value;
                bool isDismiss = Regex.IsMatch(tag, @"text=""[^""]*(Not now|Maybe later|Dismiss)\b[^""]*""", RegexOptions.IgnoreCase);
                if (isDismiss)
                {
                    Match bm = Regex.Match(tag, @"bounds=""\[(\d+),(\d+)\]\[(\d+),(\d+)\]""");
                    if (bm.Success)
                    {
                        int x1 = int.Parse(bm.Groups[1].Value);
                        int y1 = int.Parse(bm.Groups[2].Value);
                        int x2 = int.Parse(bm.Groups[3].Value);
                        int y2 = int.Parse(bm.Groups[4].Value);
                        int cx = (x1 + x2) / 2;
                        int cy = (y1 + y2) / 2;
                        if (cx > 0 && cy > 0 && cx < 900 && cy < 1600)
                        {
                            Log(string.Format("[💎 Claimer] Dismissing startup prompt at ({0}, {1})...", cx, cy));
                            AdbTap(cx, cy);
                            Thread.Sleep(1200);
                            return true;
                        }
                    }
                }
            }
        }
        catch {}
        return false;
    }

    private int ParseSecondsNeeded(string xml, int buttonY = 0)
    {
        // 1. Isolate text near buttonY (+/- 180px) to inspect the specific active card rather than global page
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
                    if (Math.Abs(yc - buttonY) <= 180)
                    {
                        nearTexts.Add(t);
                    }
                }
                if (nearTexts.Count > 0)
                {
                    cardScope = string.Join(" | ", nearTexts);
                    Log(string.Format("[ðŸ’Ž Claimer] Card node texts near Y={0}: {1}", buttonY, cardScope.Length > 100 ? cardScope.Substring(0, 100) + "..." : cardScope));
                }
            }
            catch {}
        }

        // 0. Check if usage is already verified or 100% completed on this card!
        if (cardScope.IndexOf("Usage verified", StringComparison.OrdinalIgnoreCase) >= 0 ||
            Regex.IsMatch(cardScope, @"\b100\s*%", RegexOptions.IgnoreCase))
        {
            return 0;
        }

        // A. e.g. "1m 8s more needed" or "7m 55s more needed"
        Match m1 = Regex.Match(cardScope, @"(\d+)\s*m(?:in)?\s*(\d+)\s*s(?:ec)?\s*more\s*needed", RegexOptions.IgnoreCase);
        if (m1.Success)
        {
            int mins = int.Parse(m1.Groups[1].Value);
            int secs = int.Parse(m1.Groups[2].Value);
            return (mins * 60) + secs + 4;
        }

        // B. e.g. "1m more needed" or "5 min more needed"
        Match m1b = Regex.Match(cardScope, @"(\d+)\s*m(?:in)?\s*more\s*needed", RegexOptions.IgnoreCase);
        if (m1b.Success)
        {
            int mins = int.Parse(m1b.Groups[1].Value);
            return (mins * 60) + 4;
        }

        // C. e.g. "45s more needed" or "30 sec more needed"
        Match m2 = Regex.Match(cardScope, @"(\d+)\s*s(?:ec)?\s*more\s*needed", RegexOptions.IgnoreCase);
        if (m2.Success)
        {
            int secs = int.Parse(m2.Groups[1].Value);
            return secs + 4;
        }

        // D. e.g. "Usage Progress 4m 5s / 12m 0s"
        Match mProgress = Regex.Match(cardScope, @"(\d+)\s*m\s*(\d+)\s*s\s*\/\s*(\d+)\s*m\s*(\d+)\s*s", RegexOptions.IgnoreCase);
        if (mProgress.Success)
        {
            int doneSec = int.Parse(mProgress.Groups[1].Value) * 60 + int.Parse(mProgress.Groups[2].Value);
            int totalSec = int.Parse(mProgress.Groups[3].Value) * 60 + int.Parse(mProgress.Groups[4].Value);
            int diff = totalSec - doneSec;
            if (diff > 0) return diff + 4;
            return 0;
        }

        // E. e.g. "Usage Progress 1s / 12m 0s"
        Match mProgressSec = Regex.Match(cardScope, @"(\d+)\s*s\s*\/\s*(\d+)\s*m\s*(\d+)\s*s", RegexOptions.IgnoreCase);
        if (mProgressSec.Success)
        {
            int doneSec = int.Parse(mProgressSec.Groups[1].Value);
            int totalSec = int.Parse(mProgressSec.Groups[2].Value) * 60 + int.Parse(mProgressSec.Groups[3].Value);
            int diff = totalSec - doneSec;
            if (diff > 0) return diff + 4;
            return 0;
        }

        // F. e.g. "Use app for 12 more minutes" or "Use app for 12 minutes"
        Match m3 = Regex.Match(cardScope, @"Use\s*app\s*for\s*(\d+)\s*(?:more\s*)?(?:min|minutes)", RegexOptions.IgnoreCase);
        if (m3.Success)
        {
            int mins = int.Parse(m3.Groups[1].Value);
            return (mins * 60) + 4;
        }

        // G. e.g. "Tap 'Open App' to start using it for 12 min"
        Match m4 = Regex.Match(cardScope, @"using\s*it\s*for\s*(\d+)\s*(?:min|minutes)", RegexOptions.IgnoreCase);
        if (m4.Success)
        {
            int mins = int.Parse(m4.Groups[1].Value);
            return (mins * 60) + 4;
        }

        // H. Fallback: Check if cardScope contains "Xm Ys"
        Match mFall = Regex.Match(cardScope, @"(\d+)\s*m\s*(\d+)\s*s", RegexOptions.IgnoreCase);
        if (mFall.Success)
        {
            int mins = int.Parse(mFall.Groups[1].Value);
            int secs = int.Parse(mFall.Groups[2].Value);
            return (mins * 60) + secs + 4;
        }

        return 65;
    }
    private bool FindAndClickReadyOfferCard(string xml, HashSet<string> skippedApps, out string clickedTitle)
    {
        clickedTitle = "";
        try
        {
            // Find all offer titles on the list (Every offer title on the Super Offers list is at X=105)
            MatchCollection titleNodes = Regex.Matches(xml, @"<node [^>]*text=""([^""]+)""[^>]*bounds=""\[105,(\d+)\]\[(\d+),(\d+)\]""");
            
            foreach (Match tm in titleNodes)
            {
                string rawTitle = tm.Groups[1].Value.Trim();
                string cleanTitle = WebUtility.HtmlDecode(rawTitle).Trim();
                int ty1 = int.Parse(tm.Groups[2].Value);
                int ty2 = int.Parse(tm.Groups[4].Value);

                // Ignore headers and guidelines
                if (cleanTitle == "Progress" || cleanTitle == "How to Complete" || cleanTitle.Contains("Important Guidelines") || cleanTitle == "Load More")
                    continue;

                // Ignore off-screen cards
                if (ty1 < 100 || ty2 > 1540) continue;

                // DEDUPLICATION: If this app was already processed or verified in this cycle, skip it!
                if (skippedApps != null && (skippedApps.Contains(rawTitle) || skippedApps.Contains(cleanTitle)))
                {
                    continue;
                }

                // Slice XML for this card's vertical window [ty1 - 30 to ty1 + 200]
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

                // Parse task progress if present (e.g. "6/7 tasks" or "6 / 7 tasks")
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
                bool isReady = Regex.IsMatch(cardContent, @"(?:Use\s*app\s*for|Claim|Open\s*App)", RegexOptions.IgnoreCase);

                // Truly 100% finished: all tasks including bonus are completed (e.g. 7/7 tasks)
                if (tasksDone >= 0 && tasksTotal > 0 && tasksDone >= tasksTotal)
                {
                    Log(string.Format("[Claimer] '{0}' is 100% completed ({1}/{2} tasks). Skipping.", cleanTitle, tasksDone, tasksTotal));
                    if (skippedApps != null) { skippedApps.Add(rawTitle); skippedApps.Add(cleanTitle); }
                    continue;
                }

                // If on cooldown on the card itself (e.g. "Unlocks in 4h")
                if (isCooldown)
                {
                    if (skippedApps != null) { skippedApps.Add(rawTitle); skippedApps.Add(cleanTitle); }
                    continue;
                }

                // CASE A: Standard ready offer (milestone ready to use or claim)
                if (isReady)
                {
                    int tapX = 450;
                    int tapY = ty1 + 75;
                    clickedTitle = cleanTitle;
                    if (skippedApps != null) { skippedApps.Add(rawTitle); skippedApps.Add(cleanTitle); }
                    Log(string.Format("[ðŸ’Ž Claimer] Found ready offer '{0}'! Opening card at ({1}, {2})...", cleanTitle, tapX, tapY));
                    AdbTap(tapX, tapY);
                    return true;
                }

                // CASE B: "All milestones completed!" but tasks remain (e.g. 6/7 tasks) or unverified bonus!
                // The Bonus is pending inside the detail view - must open to check if claimable or on cooldown!
                if (isMilestonesText && (tasksDone < tasksTotal || tasksDone < 0))
                {
                    int tapX = 450;
                    int tapY = ty1 + 75;
                    clickedTitle = cleanTitle;
                    if (skippedApps != null) { skippedApps.Add(rawTitle); skippedApps.Add(cleanTitle); }
                    string taskInfo = (tasksDone >= 0 && tasksTotal > 0) ? string.Format(" ({0}/{1} tasks)", tasksDone, tasksTotal) : "";
                    Log(string.Format("[ðŸŽ Bonus Verifier] '{0}'{1} has pending Bonus! Opening card to verify...", cleanTitle, taskInfo));
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
    private void ProcessOfferAppDetails(string targetTitle, HashSet<string> cycleCompletedApps, HashSet<string> cooldownBonusApps = null)
    {
        int consecutiveLoops = 0;
        int scrollAttempts = 0;
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

                // Extract App Title for logging and cooldown tracking
                Match tm = Regex.Match(xml, @"<node[^>]*class=""android\.widget\.TextView""[^>]*bounds=""\[102,\d+\]\[\d+,\d+\]""[^>]*text=""([^""]+)""");
                if (tm.Success) appTitle = tm.Groups[1].Value.Trim();
                if (string.IsNullOrEmpty(appTitle))
                {
                    Match tm2 = Regex.Match(xml, @"<node[^>]*text=""([^""]+)""[^>]*class=""android\.widget\.TextView""");
                    if (tm2.Success) appTitle = tm2.Groups[1].Value.Trim();
                }

                // Extract Offer App Package Name from top of detail page (e.g. com.ind.circuitpath, wni.WeathernewsTouch.jp)
                Match pm = Regex.Match(xml, @"<node[^>]*text=""([a-zA-Z0-9_]+(?:\.[a-zA-Z0-9_]+)+)""");
                if (pm.Success)
                {
                    string candidate = pm.Groups[1].Value.Trim();
                    if (candidate != "com.quiz.earn.money.game" && !candidate.StartsWith("android."))
                    {
                        offerPkg = candidate;
                    }
                }

                // 1. Check for Claim Button first! (Matches "Claim" in text or content-desc)
                int cx, cy;
                if (TryFindNodeBounds(xml, "Claim", out cx, out cy))
                {
                    // SAFETY: Never tap navigation bar (cy < 1560)
                    if (cy < 1560)
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
                        catch {}

                        Log(string.Format("[💎 Claimer] Milestone / Bonus ready! Tapping CLAIM (+{0} Coins) at ({1}, {2})...", rewardCoins, cx, cy));
                        AdbTap(cx, cy);

                        todayCoinsClaimed += rewardCoins;
                        totalCoinsClaimed += rewardCoins;
                        todayOffersClaimed++;
                        totalOffersClaimed++;
                        string titleTag = string.IsNullOrEmpty(appTitle) ? (string.IsNullOrEmpty(targetTitle) ? "Offer Milestone" : targetTitle) : appTitle;
                        AddClaimHistory(string.Format("{0} (+{1:N0} Coins)", titleTag, rewardCoins));
                        SaveStatsToFile();
                        UpdateDataDashboard();

                        Thread.Sleep(3000);
                        RunAdb("shell monkey -p com.quiz.earn.money.game -c android.intent.category.LAUNCHER 1");
                        Thread.Sleep(2000);
                        continue; // Re-check if next milestone unlocked!
                    }
                }

                // 2. Check for Open App OR Verify Usage Button!
                int ox, oy;
                bool hasActionBtn = TryFindNodeBounds(xml, "Open App", out ox, out oy);
                bool isVerifyBtn = false;
                if (!hasActionBtn)
                {
                    hasActionBtn = TryFindNodeBounds(xml, "Verify Usage", out ox, out oy);
                    isVerifyBtn = hasActionBtn;
                }

                if (hasActionBtn)
                {
                    if (oy < 1560)
                    {
                    int staySeconds = ParseSecondsNeeded(xml, oy);
                    if (staySeconds <= 0)
                    {
                        Log(string.Format("[ðŸ’Ž Claimer] Usage requirement fulfilled! Quick-tapping '{0}' at ({1}, {2})...", isVerifyBtn ? "Verify Usage" : "Open App", ox, oy));
                        AdbTap(ox, oy);
                        Thread.Sleep(3000);
                        continue; // Re-check screen for Claim button!
                    }

                    Log(string.Format("[ðŸ’Ž Claimer] Found '{0}' for Bonus / Milestone! Time required: {1}s ({2}m {3}s)...", isVerifyBtn ? "Verify Usage" : "Open App", staySeconds, staySeconds / 60, staySeconds % 60));

                    if (isVerifyBtn && !string.IsNullOrEmpty(offerPkg))
                    {
                        Log(string.Format("[ðŸ’Ž Claimer] Launching '{0}' to complete remaining usage time...", offerPkg));
                        RunAdb(string.Format("shell monkey -p {0} -c android.intent.category.LAUNCHER 1", offerPkg));
                        Thread.Sleep(3500);
                    }
                    else
                    {
                        AdbTap(ox, oy);
                        Thread.Sleep(3500);
                    }

                    // Check and dismiss any notification / permission popup immediately on launch!
                    DismissAppDialogs();

                        string targetPkg = ExtractPackageName(GetCurrentFocus());
                        if (targetPkg == "com.android.vending" || targetPkg.Contains("vending") || targetPkg.Contains("google.android.finsky"))
                        {
                            Log("[Claimer] Google Play Store opened instead of installed offer app (requires Google login or app install). Closing and skipping app...");
                            AdbKey(4);
                            Thread.Sleep(600);
                            RunAdb("shell am force-stop com.android.vending");
                            RunAdb("shell monkey -p com.quiz.earn.money.game -c android.intent.category.LAUNCHER 1");
                            Thread.Sleep(1500);
                            if (!string.IsNullOrEmpty(appTitle))
                            {
                                cycleCompletedApps.Add(appTitle);
                            }
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

                            SafeBeginInvoke(new Action(() => {
                                if (lblBoostStatus != null)
                                {
                                    lblBoostStatus.Text = status;
                                    lblBoostStatus.ForeColor = Color.FromArgb(180, 180, 185);
                                }
                            }));

                            // Auto-dismiss blocking notification/permission dialogs if they appear after splash screen
                            if (keepAlive == 3 || keepAlive == 7 || (keepAlive > 0 && keepAlive % 20 == 0))
                            {
                                DismissAppDialogs();
                            }

                            if (keepAlive % 15 == 0)
                            {
                                RunAdb("shell input swipe 450 700 450 720 120");
                            }

                            Thread.Sleep(1000);
                            remaining--;
                            keepAlive++;
                        }

                        if (!isAutoOffering) return;

                        Log("[💎 Claimer] Required time completed! Returning to Quiz & Earn...");
                        RunAdb("shell monkey -p com.quiz.earn.money.game -c android.intent.category.LAUNCHER 1");
                        Thread.Sleep(3000);
                        continue; // Re-check screen for Claim button!
                    }
                }

                // 3. Scroll Down to Reveal Lower Milestones & "BONUS REWARDS" section
                // Many apps have 6+ standard milestones; BONUS REWARDS is at the bottom!
                if (scrollAttempts < 3)
                {
                    scrollAttempts++;
                    Log(string.Format("[ðŸ’Ž Claimer] Scrolling down to inspect BONUS REWARDS & lower milestones ({0}/3)...", scrollAttempts));
                    RunAdb("shell input swipe 450 1100 450 400 350");
                    Thread.Sleep(1500);
                    continue; // Re-dump XML in the new scrolled position
                }

                // 4. Check if the app's remaining milestone or bonus is on cooldown
                string cdStr = "";
                Match cdMatch = Regex.Match(xml, @"text=""Unlocks\s*in\s*""[^>]*>\s*<node[^>]*text=""([0-9hm\s]+)""", RegexOptions.IgnoreCase);
                if (cdMatch.Success)
                {
                    cdStr = cdMatch.Groups[1].Value.Trim();
                }
                else
                {
                    Match cdMatch2 = Regex.Match(xml, @"(?:Unlocks\s*in|After)\s*([0-9hm\s]+)", RegexOptions.IgnoreCase);
                    if (cdMatch2.Success) cdStr = cdMatch2.Groups[1].Value.Trim();
                }

                string displayTitle = string.IsNullOrEmpty(appTitle) ? targetTitle : appTitle;
                if (!string.IsNullOrEmpty(cdStr))
                {
                    Log(string.Format("[ðŸŽ Bonus Verified] '{0}' Bonus is locked on cooldown: Unlocks in {1}. Verified not missed!", displayTitle, cdStr));
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
                        Log(string.Format("[âœ… Verified] '{0}' Bonus & all milestones have been verified and processed.", displayTitle));
                    }
                    else
                    {
                        Log(string.Format("[ðŸ’Ž Claimer] Verified '{0}': No active or claimable bonus found right now. Returning to offers list...", displayTitle));
                    }
                }

                break;
            }
        }
        finally
        {
            // CRITICAL FIX: ALWAYS record this app as processed in the cycle so the bot NEVER loops on the same app!
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

            AdbKey(4); // BlueStacks / Android hardware Back button (KEYCODE_BACK)
            Thread.Sleep(2000);
        }
    }

    private void AutoOfferWorkerLoop()
    {
        try
        {
            Log("[💎 Claimer] Initializing Auto-Offer Claimer...");
            int totalAppsProcessed = 0;
            HashSet<string> cycleCompletedApps = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            HashSet<string> cooldownBonusApps = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            string lastOpenedTitle = "";
            int consecutiveEmptyDumps = 0;

            while (isAutoOffering)
            {
                if (autoRecoverBlueStacks && !IsBlueStacksProcessRunning())
                {
                    EnsureBlueStacksAliveAndReady("Auto-Offer Claimer Loop");
                    Thread.Sleep(2000);
                    continue;
                }

                string curFocus = GetCurrentFocus();
                if (!curFocus.Contains("com.quiz.earn.money.game"))
                {
                    RunAdb("shell monkey -p com.quiz.earn.money.game -c android.intent.category.LAUNCHER 1");
                    Thread.Sleep(2000);
                }

                string xml = DumpAndReadXml("/sdcard/offer_flow.xml");
                if (string.IsNullOrEmpty(xml))
                {
                    consecutiveEmptyDumps++;
                    if (consecutiveEmptyDumps >= 3 && autoRecoverBlueStacks && !IsBlueStacksProcessRunning())
                    {
                        EnsureBlueStacksAliveAndReady("Auto-Offer XML Dump Failure");
                        consecutiveEmptyDumps = 0;
                        continue;
                    }
                    Thread.Sleep(1000);
                    continue;
                }
                consecutiveEmptyDumps = 0;

                // SCREEN A: Are we inside an app detail page? (Look for REWARDS or Remove Wrong App)
                if (xml.Contains("REWARDS") || xml.Contains("Remove Wrong App") || xml.Contains("Usage Progress") || xml.Contains("Initial Install Reward"))
                {
                    Log("[💎 Claimer] Inside offer app details page. Processing milestones & bonus...");
                    ProcessOfferAppDetails(lastOpenedTitle, cycleCompletedApps, cooldownBonusApps);
                    totalAppsProcessed++;
                    Thread.Sleep(1500);

                    // Safeguard: Verify if still on detail page (e.g. if notification banner intercepted back)
                    string verifyXml = DumpAndReadXml("/sdcard/verify_back.xml");
                    if (verifyXml.Contains("REWARDS") || verifyXml.Contains("Remove Wrong App") || verifyXml.Contains("Initial Install Reward"))
                    {
                        Log("[💎 Claimer] Still on detail page. Sending hardware Back button again...");
                        AdbKey(4);
                        Thread.Sleep(1500);
                    }
                    continue;
                }

                // SCREEN B: Are we on "My Super Offers" list?
                if (xml.Contains("My Super Offers") || xml.Contains("Pending Offers") || xml.Contains("How to Complete"))
                {
                    if (xml.Contains("Failed to load offers"))
                    {
                        Log("[💎 Claimer] Detected 'Failed to load offers'. Tapping Refresh button...");
                        AdbTap(846, 90);
                        Thread.Sleep(3000);
                        continue;
                    }

                    // Check if an offer's cooldown just hit 0m/0s - if so, refresh the list to reveal the "Use app" button!
                    if (Regex.IsMatch(xml, @"Unlocks\s*in\s*0[ms]", RegexOptions.IgnoreCase))
                    {
                        Log("[💎 Claimer] Detected offer with 0m cooldown! Tapping Refresh offers...");
                        AdbTap(846, 90);
                        Thread.Sleep(3000);
                        cooldownBonusApps.Clear(); // Cooldown finished, clear skipped cache
                        cycleCompletedApps.Clear();
                        continue;
                    }

                    Log("[💎 Claimer] On 'My Super Offers' list. Scanning for ready apps & bonus rewards...");
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
                        // Check if Load More button is visible on page 1 and tap it to reveal all offers
                        int lmx, lmy;
                        if (TryFindNodeBounds(xml, "Load More", out lmx, out lmy) && lmy < 1550)
                        {
                            Log(string.Format("[💎 Claimer] Found 'Load More' button at ({0}, {1}). Tapping to reveal more offers...", lmx, lmy));
                            AdbTap(lmx, lmy);
                            Thread.Sleep(2500);
                            xml = DumpAndReadXml("/sdcard/offer_flow.xml");
                            if (FindAndClickReadyOfferCard(xml, cycleCompletedApps, out clickedTitle))
                            {
                                lastOpenedTitle = clickedTitle;
                                Thread.Sleep(2500);
                                continue;
                            }
                        }

                        // Check lower cards by scrolling down up to 4 times
                        bool foundOnScroll = false;
                        for (int scrollPass = 1; scrollPass <= 4; scrollPass++)
                        {
                            Log(string.Format("[💎 Claimer] Scrolling down to find more ready offers (page {0}/4)...", scrollPass));
                            RunAdb("shell input swipe 450 1200 450 400 350");
                            Thread.Sleep(2000);
                            string scrolledXml = DumpAndReadXml("/sdcard/offer_flow.xml");

                            // Check Load More on scrolled page too
                            if (TryFindNodeBounds(scrolledXml, "Load More", out lmx, out lmy) && lmy < 1550)
                            {
                                AdbTap(lmx, lmy);
                                Thread.Sleep(2500);
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
                            // Find the shortest cooldown so user knows exactly when the next offer unlocks!
                            MatchCollection cdMatches = Regex.Matches(xml + " " + DumpAndReadXml("/sdcard/offer_flow.xml"), @"Unlocks\s*in\s*([0-9hm\s]+)", RegexOptions.IgnoreCase);
                            string nextUnlock = "a few hours";
                            if (cdMatches.Count > 0)
                            {
                                nextUnlock = cdMatches[0].Groups[1].Value.Trim();
                            }
                            Log(string.Format("[💎 Claimer] All pending offers are on cooldown or completed for this cycle! Next offer unlocks in ~{0}. Returning to Quiz...", nextUnlock));
                            
                            SafeBeginInvoke(new Action(() => {
                                if (lblBoostStatus != null)
                                {
                                    lblBoostStatus.Text = string.Format("Cooldown (Next: {0})", nextUnlock);
                                    lblBoostStatus.ForeColor = Color.FromArgb(150, 150, 155);
                                }
                            }));

                            AdbKey(4);
                            Thread.Sleep(1500);
                            break;
                        }
                        continue;
                    }
                }

                // SCREEN C: Are we on Quiz screen?
                if (xml.Contains("My Offers") || xml.Contains("Super Offer") || xml.Contains("captchas for Guaranteed"))
                {
                    Log("[💎 Claimer] Found Quiz screen. Opening 'My Offers'...");
                    int tx, ty;
                    if (TryFindNodeBounds(xml, "My Offers", out tx, out ty))
                    {
                        AdbTap(tx, ty);
                    }
                    else
                    {
                        AdbTapRef(450, 832);
                    }
                    Thread.Sleep(3000);
                    continue;
                }

                // SCREEN D: Are we on Home screen?
                if (xml.Contains("SUPER OFFER") || xml.Contains("Guaranteed") || xml.Contains("1,000"))
                {
                    Log("[💎 Claimer] On Home screen. Tapping Super Offer 1,000 card...");
                                        int scx, scy;
                    if (TryFindNodeBounds(xml, "SUPER OFFER", out scx, out scy) || TryFindNodeBounds(xml, "1,000", out scx, out scy))
                    {
                        AdbTap(scx, scy + 40);
                    }
                    else
                    {
                        AdbTap(240, 370);
                    }
                    Thread.Sleep(3000);
                    continue;
                }

                Log("[💎 Claimer] Screen unrecognized, opening Quiz & Earn main page...");
                RunAdb("shell monkey -p com.quiz.earn.money.game -c android.intent.category.LAUNCHER 1");
                Thread.Sleep(2500);
            }

            Log(string.Format("[💎 Claimer] Auto-Offer session complete! Processed {0} apps.", totalAppsProcessed));
            try { System.Media.SystemSounds.Asterisk.Play(); } catch {}
        }
        catch (Exception ex)
        {
            Log("[💎 Claimer] Error: " + ex.Message);
        }
        finally
        {
            isAutoOffering = false;
            isBoosting = false;
            SafeBeginInvoke(new Action(() => {
                if (lblBoostStatus != null)
                {
                    lblBoostStatus.Text = "Claimer: Idle";
                    lblBoostStatus.ForeColor = Color.FromArgb(110, 110, 115);
                }
            }));
        }
    }

    private void BtnAutoOffer_Click(object sender, EventArgs e)
    {
        StartAutoOfferClaimer();
    }

    private void BtnAutoOfferStop_Click(object sender, EventArgs e)
    {
        StopAutoOfferClaimer();
    }

    private void StartAutoOfferClaimer()
    {
        if (isAutoOffering)
        {
            Log("[💎 Claimer] Auto-Offer Claimer is already running!");
            return;
        }

        if (!isAdbConnected)
        {
            if (!EnsureConnected())
            {
                if (autoRecoverBlueStacks && !IsBlueStacksProcessRunning())
                {
                    Log("[ðŸ›¡ï¸ Watchdog] BlueStacks not running. Auto-launching BlueStacks before starting claimer...");
                    EnsureBlueStacksAliveAndReady("Auto-Offer Claimer Startup");
                }
                Log("[💎 Claimer] ADB not connected yet! Turn on ADB in emulator/phone first.");
                return;
            }
            isAdbConnected = true;
        }

        isAutoOffering = true;
        isBoosting = true;
        Log("[💎 Claimer] Starting 1-Click Auto-Offer Claimer...");

        autoOfferThread = new Thread(new ThreadStart(AutoOfferWorkerLoop));
        autoOfferThread.IsBackground = true;
        autoOfferThread.Start();
    }

    private void StopAutoOfferClaimer()
    {
        if (isAutoOffering || isBoosting)
        {
            isAutoOffering = false;
            isBoosting = false;
            Log("[💎 Claimer] Auto-Offer Claimer stopped by user.");
            SafeBeginInvoke(new Action(() => {
                if (lblBoostStatus != null)
                {
                    lblBoostStatus.Text = "Claimer: Stopped";
                    lblBoostStatus.ForeColor = Color.FromArgb(135, 135, 140);
                }
            }));
        }
    }

    private bool CheckAndEscapeForeignApp()
    {
        if (isBoosting || isAutoOffering) return false;
        string focus = GetCurrentFocus();
        if (string.IsNullOrEmpty(focus)) return false;

        // 1. Google Play Store or Chrome browser redirect
        if (focus.Contains("com.android.vending") || focus.Contains("google.android.finsky") || focus.Contains("com.android.chrome"))
        {
            Log("[Guard] Play Store / Browser overlay detected! Closing and returning to Quiz & Earn...");
            AdbKey(4);
            Thread.Sleep(400);
            RunAdb("shell am force-stop com.android.vending");
            RunAdb("shell am force-stop com.android.chrome");
            Thread.Sleep(400);
            RunAdb("shell monkey -p com.quiz.earn.money.game -c android.intent.category.LAUNCHER 1");
            Thread.Sleep(800);
            return true;
        }

        // 2. Filter known activities that are legitimate
        bool isOurApp = focus.Contains("com.quiz.earn.money.game") || focus.Contains("com.rayole.cashmafia2");
        bool isAd = focus.Contains("AdActivity") || focus.Contains("UnityPlayerActivity") || 
                    focus.Contains("IronSource") || focus.Contains("Vungle") || 
                    focus.Contains("AppLovin") || focus.Contains("mbridge") || 
                    focus.Contains("com.google.android.gms") || focus.Contains("com.tapjoy") ||
                    focus.Contains("FullScreenAd") || focus.Contains("RewardedAd");
        bool isSystem = focus.ToLower().Contains("launcher") || focus.Contains("SystemUI") || focus.Contains("NexusLauncher");

        if (isOurApp || isAd) return false;

        // 3. Android Home / Launcher screen
        if (isSystem)
        {
            Log("[Watchdog] Launcher active. Relaunching Quiz & Earn...");
            RunAdb("shell monkey -p com.quiz.earn.money.game -c android.intent.category.LAUNCHER 1");
            Thread.Sleep(2000);
            AdbTapRef(683, 1439);
            return true;
        }

        // 4. Foreign Third-Party App opened by ad (e.g. installed shopping/ad apps)
        Match mPkg = Regex.Match(focus, @"([a-zA-Z0-9_\.]+\.[a-zA-Z0-9_]+)");
        string foreignPkg = mPkg.Success ? mPkg.Groups[1].Value : "";
        if (!string.IsNullOrEmpty(foreignPkg) && !foreignPkg.Contains("com.quiz.earn") && !foreignPkg.Contains("com.rayole") && !foreignPkg.Contains("android.") && !foreignPkg.Contains("quizearn.assistant"))
        {
            Log(string.Format("[Guard] Foreign ad app opened ({0})! Force-closing and restoring Quiz & Earn...", foreignPkg));
            RunAdb("shell am force-stop " + foreignPkg);
            Thread.Sleep(300);
            AdbKey(4);
            Thread.Sleep(400);
            RunAdb("shell monkey -p com.quiz.earn.money.game -c android.intent.category.LAUNCHER 1");
            Thread.Sleep(1200);
            return true;
        }

        return false;
    }

    private bool TryFindAndClickAdButton(string xml, int elapsedSeconds)
    {
        if (string.IsNullOrEmpty(xml)) return false;

        // Specific detection for Playable / Interactive Ads (e.g. Zivame, Reward granted)
        if (xml.Contains("Reward granted") || xml.Contains("Reward Granted") || xml.Contains("reveal matching pairs") || xml.Contains("matching pairs") || xml.Contains("Complete for an instant reward") || xml.Contains("Zivame"))
        {
            Log("[Ad] Interactive / Playable ad detected! Tapping top-right Close (X)...");
            // Direct hit on the exact X button (image coordinates scale to center 824, 71)
            AdbTapRef(824, 71);
            Thread.Sleep(300);
            AdbTapRef(850, 70);
            Thread.Sleep(300);
            AdbTapRef(824, 71);
            Thread.Sleep(500);

            string reFocus = GetCurrentFocus();
            if (reFocus.Contains("AdActivity") || reFocus.Contains("com.android.vending"))
            {
                AdbKey(4);
            }
            return true;
        }

        // Specific detection for IronSource storePromoContainer modal
        if (xml.Contains("storePromoContainer"))
        {
            Match mImg = Regex.Match(xml, @"<node [^>]*class=""android\.widget\.Image""[^>]*bounds=""\[(\d+),(\d+)\]\[(\d+),(\d+)\]""");
            if (mImg.Success)
            {
                int ix1 = int.Parse(mImg.Groups[1].Value);
                int iy1 = int.Parse(mImg.Groups[2].Value);
                int ix2 = int.Parse(mImg.Groups[3].Value);
                int iy2 = int.Parse(mImg.Groups[4].Value);
                int icx = (ix1 + ix2) / 2;
                int icy = (iy1 + iy2) / 2;
                if (icx >= (int)(devWidth * 0.85) && icy <= (int)(devHeight * 0.12))
                {
                    Log(string.Format("[Ad] Detected promo card Close (X) at ({0}, {1})! Tapping...", icx, icy));
                    AdbTap(icx, icy);
                    return true;
                }
            }
            Log("[Ad] Tapping promo card Close (X) fallback...");
            AdbTapRef(870, 95);
            return true;
        }

        MatchCollection matches = Regex.Matches(xml, @"<node [^>]+>");
        foreach (Match m in matches)
        {
            string txt = m.Value;

            if (Regex.IsMatch(txt, @"(Ad \d of|Sponsored|\bOpen\b|\bOPEN\b|\bPlay\b|\bInstall\b|\bDownload\b|\bGet\b|\bUpdate\b|\bShop\b|\bVisit\b|Learn More|Google Play)", RegexOptions.IgnoreCase))
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

                // Explicit match
                if (isMatch && w >= 10 && w <= 380 && h >= 10 && h <= 380)
                {
                    int cx = (x1 + x2) / 2;
                    int cy = (y1 + y2) / 2;
                    Log(string.Format("[Ad] Detected ad close/skip button at ({0}, {1})! Tapping...", cx, cy));
                    AdbTap(cx, cy);
                    return true;
                }

                // Top-right corner after 25s (Guard against tapping 'OPEN' or 'INSTALL' headers)
                if (!isMatch && elapsedSeconds >= 25 && w >= 15 && w <= 160 && h >= 15 && h <= 160 && (x1 >= (int)(devWidth * 0.78) && y1 <= (int)(devHeight * 0.12)))
                {
                    if (!Regex.IsMatch(txt, @"text=""\d+""") && !Regex.IsMatch(txt, @"\b(Open|Play|Install|Get|Update|Shop|Visit)\b", RegexOptions.IgnoreCase))
                    {
                        int cx = (x1 + x2) / 2;
                        int cy = (y1 + y2) / 2;
                        Log(string.Format("[Ad] Detected top-right ad close icon at ({0}, {1})! Tapping...", cx, cy));
                        AdbTap(cx, cy);
                        return true;
                    }
                }

                // Top-left arrow after 25s
                if (!isMatch && elapsedSeconds >= 25 && w >= 20 && w <= 120 && h >= 20 && h <= 120 && (x1 <= (int)(devWidth * 0.10) && y1 <= (int)(devHeight * 0.10)))
                {
                    if (!Regex.IsMatch(txt, @"\b(Open|Play|Install|Get|Update|Shop|Visit)\b", RegexOptions.IgnoreCase))
                    {
                        int cx = (x1 + x2) / 2;
                        int cy = (y1 + y2) / 2;
                        Log(string.Format("[Ad] Detected top-left ad skip arrow at ({0}, {1})! Tapping...", cx, cy));
                        AdbTap(cx, cy);
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void CloseAdActively()
    {
        Log("[Ad] Watching ad... (hands-off to ensure reward callback)");
        DateTime start = DateTime.Now;

        // Phase 1: Wait 15 seconds without premature clicks
        while ((DateTime.Now - start).TotalSeconds < 15)
        {
            Thread.Sleep(1000);
            string focus = GetCurrentFocus();

            if ((DateTime.Now - start).TotalSeconds > 3)
            {
                RunAdb("shell uiautomator dump --compressed /sdcard/ad_check.xml");
                string chkXml = RunAdb("shell cat /sdcard/ad_check.xml");
                if (chkXml.Contains("Quiz") || chkXml.Contains("Congratulations") || chkXml.Contains("Collect Gem") || chkXml.Contains("Super Offer") || chkXml.Contains("Select the correct number"))
                {
                    Log("[Ad] Successfully back in Quiz, proceeding!");
                    adsClosed++;
                    SaveStatsToFile();
                    UpdateDashboard(lastCaptcha);
                    return;
                }

                if (chkXml.Contains("Reward granted") || chkXml.Contains("Reward Granted") || chkXml.Contains("reveal matching pairs") || chkXml.Contains("Complete for an instant reward"))
                {
                    Log("[Ad] 'Reward granted' detected during playback! Closing ad immediately...");
                    AdbTapRef(824, 71);
                    Thread.Sleep(300);
                    AdbTapRef(850, 70);
                    Thread.Sleep(300);
                    AdbKey(4);
                    Thread.Sleep(1000);
                    string reFocus = GetCurrentFocus();
                    if (reFocus.Contains("MainActivity"))
                    {
                        adsClosed++;
                        SaveStatsToFile();
                        UpdateDashboard(lastCaptcha);
                        return;
                    }
                }
            }

            if (CheckAndEscapeForeignApp())
            {
                adsClosed++;
                SaveStatsToFile();
                UpdateDashboard(lastCaptcha);
                return;
            }
        }

        Log("[Ad] Searching for Skip / Close button...");

        // Phase 2: Active close hunt
        while ((DateTime.Now - start).TotalSeconds < 45)
        {
            string focus = GetCurrentFocus();
            if (focus.Contains("MainActivity"))
            {
                Log("[Ad] Successfully back in Quiz!");
                adsClosed++;
                SaveStatsToFile();
                UpdateDashboard(lastCaptcha);
                return;
            }

            if (CheckAndEscapeForeignApp())
            {
                adsClosed++;
                SaveStatsToFile();
                UpdateDashboard(lastCaptcha);
                return;
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
                    Log("[Ad Closed] Successfully back in Quiz!");
                    adsClosed++;
                    SaveStatsToFile();
                    UpdateDashboard(lastCaptcha);
                    return;
                }
            }
            else
            {
                if (elapsed >= 24 && elapsed < 32)
                {
                    AdbTapRef(54, 54);
                    AdbTapRef(824, 71);
                    AdbTapRef(856, 50);
                }
                else if (elapsed >= 32)
                {
                    Log("[Ad] Sending Back key to return to Quiz...");
                    AdbKey(4);
                    Thread.Sleep(1200);
                    string reFocus = GetCurrentFocus();
                    if (reFocus.Contains("MainActivity"))
                    {
                        Log("[Ad Finished] Successfully back in Quiz!");
                        adsClosed++;
                        SaveStatsToFile();
                        UpdateDashboard(lastCaptcha);
                        return;
                    }
                }
            }

            Thread.Sleep(1000);
        }

        // Phase 3: Final fallback
        Log("[Ad Timeout] Pressing Back to return to Quiz screen...");
        AdbKey(4);
        Thread.Sleep(1500);
    }

    private void RecoverFromFreeze()
    {
        if (autoRecoverBlueStacks && (!IsBlueStacksProcessRunning() || !EnsureConnected()))
        {
            EnsureBlueStacksAliveAndReady("Quiz Solver Freeze Recovery");
            return;
        }

        Log("[Watchdog] Checking app status for recovery...");
        if (CheckAndEscapeForeignApp()) return;

        Log("[Watchdog] Relaunching Quiz & Earn to clear freeze...");
        AdbKey(4);
        Thread.Sleep(800);
        RunAdb("shell monkey -p com.quiz.earn.money.game -c android.intent.category.LAUNCHER 1");
        Thread.Sleep(2500);
        AdbTapRef(683, 1439);
        Thread.Sleep(1000);
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
                if (autoRecoverBlueStacks && !IsBlueStacksProcessRunning())
                {
                    Log("[ðŸ›¡ï¸ Watchdog] BlueStacks not running on bot start. Launching automatically...");
                    EnsureBlueStacksAliveAndReady("Quiz Solver Startup");
                }

                if (!EnsureConnected())
                {
                    Log("[ADB] No active device detected yet. Waiting for ADB to come online (turn on ADB in emulator/phone)...");
                int waitSec = 0;
                while (isRunning && !EnsureConnected() && waitSec < 45)
                {
                    Thread.Sleep(2000);
                    waitSec += 2;
                }

                if (!EnsureConnected())
                {
                    Log("[Error] Timed out waiting for ADB. Please turn on ADB on your emulator or phone, then click Start.");
                    StopBot();
                    return;
                }
            }
        }

            Log(string.Format("[ADB] Connected to device on {0}!", adbPort));
            UpdateScreenResolution();

            // Optimize animators for emulators only
            if (IsEmulatorDevice(adbPort))
            {
                RunAdb("shell settings put global window_animation_scale 0");
                RunAdb("shell settings put global transition_animation_scale 0");
                RunAdb("shell settings put global animator_duration_scale 0");
            }

            if (chkAutoMute.Checked)
            {
                SilenceAudio(true);
            }

            while (isRunning)
            {
                if (isBoosting)
                {
                    Thread.Sleep(1000);
                    continue;
                }

                double idleSec = (DateTime.Now - lastProgressTime).TotalSeconds;
                if (idleSec > 45)
                {
                    RecoverFromFreeze();
                    lastProgressTime = DateTime.Now;
                    continue;
                }

                string focus = GetCurrentFocus();
                if (string.IsNullOrEmpty(focus) && autoRecoverBlueStacks && !IsBlueStacksProcessRunning())
                {
                    EnsureBlueStacksAliveAndReady("Quiz Solver Loop");
                    lastProgressTime = DateTime.Now;
                    continue;
                }
                if (CheckAndEscapeForeignApp())
                {
                    lastProgressTime = DateTime.Now;
                    Thread.Sleep(800);
                    continue;
                }

                if (focus.Contains("AdActivity") || focus.Contains("UnityPlayerActivity") || focus.Contains("IronSource") || focus.Contains("VungleActivity"))
                {
                    Log("[Ad] Ad activity detected in foreground!");
                    CloseAdActively();
                    lastProgressTime = DateTime.Now;
                    continue;
                }

                string xml = RunAdb("shell \"uiautomator dump --compressed /sdcard/cur.xml >/dev/null 2>&1 && cat /sdcard/cur.xml\"");

                if (string.IsNullOrEmpty(xml) || xml.Contains("ERROR"))
                {
                    Thread.Sleep(chkTurboSpeed.Checked ? 200 : 600);
                    continue;
                }

                // Live Gem Count reading from Quiz screen
                Match mGemCount = Regex.Match(xml, @"text=""(\d{1,2})""[^>]*bounds=""\[\d+,\d+\]\[\d+,\d+\]""\s*/>\s*<node[^>]*text=""/""[^>]*bounds=""\[\d+,\d+\]\[\d+,\d+\]""\s*/>\s*<node[^>]*text=""25""");
                if (mGemCount.Success)
                {
                    currentOfferGems = int.Parse(mGemCount.Groups[1].Value);
                    UpdateDashboard(lastCaptcha);
                }

                // CASE 1: 25 GEMS STOPPING AND ALERT (Super Offer Unlocked)
                if (currentOfferGems >= 25 || xml.Contains("25/25 gems") || xml.Contains("Super Offer Unlocked"))
                {
                    if (!hasCountedThis25GemCycle)
                    {
                        hasCountedThis25GemCycle = true;
                        today25GemsCount++;
                        total25GemsCount++;
                        AddClaimHistory(string.Format("25 Gems Milestone Hit (Cycle #{0})", total25GemsCount));
                        SaveStatsToFile();
                        UpdateDataDashboard();
                    }

                    Log("[ALERT] 25 GEMS TARGET REACHED! 1,000 Coins Super Offer unlocked. Bot paused for claim.");
                    if (this.InvokeRequired)
                    {
                        SafeBeginInvoke(new Action(delegate {
                            lblStatus.Text = "STANDBY - 25 Gems Hit!";
                            lblStatus.ForeColor = Color.FromArgb(155, 155, 160);
                        }));
                    }

                    Play25GemsMilestoneAlert();

                    while (isRunning)
                    {
                        Thread.Sleep(3000);
                        RunAdb("shell uiautomator dump --compressed /sdcard/cur.xml");
                        string recheckXml = RunAdb("shell cat /sdcard/cur.xml");
                        if (!recheckXml.Contains("25/25") && !recheckXml.Contains("Super Offer Unlocked"))
                        {
                            Log("[Bot] Resuming farming after claim!");
                            currentOfferGems = 0;
                            hasCountedThis25GemCycle = false;
                            break;
                        }
                    }
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
                    AdbTapRef(450, 1537);
                    Thread.Sleep(1000);
                    continue;
                }

                // CASE 3: Home Screen (Tap on 1,000 Super Offer 'Claim')
                if ((xml.Contains("SUPER OFFER") || xml.Contains("1,000") || xml.Contains("Guaranteed") || xml.Contains("Hi, ")) && (xml.Contains("Claim") || xml.Contains("Go to Wallet") || xml.Contains("Daily Win")))
                {
                    lastProgressTime = DateTime.Now;
                    Log("[HOME] Home screen detected. Tapping on 1,000 Super Offer...");

                    Match mClaimBtn = Regex.Match(xml, @"<node [^>]*text=""Claim""[^>]*bounds=""\[(\d+),(\d+)\]\[(\d+),(\d+)\]""");
                    if (mClaimBtn.Success)
                    {
                        int cx = (int.Parse(mClaimBtn.Groups[1].Value) + int.Parse(mClaimBtn.Groups[3].Value)) / 2;
                        int cy = (int.Parse(mClaimBtn.Groups[2].Value) + int.Parse(mClaimBtn.Groups[4].Value)) / 2;
                        AdbTap(cx, cy);
                    }
                    else
                    {
                        AdbTapRef(200, 650);
                    }
                    Thread.Sleep(2000);
                    continue;
                }

                if (xml.Contains("Welcome Back") || (xml.Contains("Play Quiz") && xml.Contains("Super Offers")) || xml.Contains("Basket Dunk"))
                {
                    lastProgressTime = DateTime.Now;
                    Log("[HOME SCREEN] Entering 'Play Quiz'...");

                    Match mQuizNode = Regex.Match(xml, @"<node [^>]*content-desc=""Play Quiz[^""]*""[^>]*bounds=""\[(\d+),(\d+)\]\[(\d+),(\d+)\]""");
                    if (mQuizNode.Success)
                    {
                        int qx = (int.Parse(mQuizNode.Groups[1].Value) + int.Parse(mQuizNode.Groups[3].Value)) / 2;
                        int qy = (int.Parse(mQuizNode.Groups[2].Value) + Math.Min(int.Parse(mQuizNode.Groups[4].Value), devHeight - 40)) / 2;
                        AdbTap(qx, qy);
                    }
                    else
                    {
                        AdbTapRef(683, 1439);
                    }
                    Thread.Sleep(2000);
                    continue;
                }

                // CASE 4: Congratulations / Collect Gem modal
                if (xml.Contains("Congratulations") || xml.Contains("Collect Gem") || xml.Contains("+1 Gem") || xml.Contains("Watch an ad to collect") || xml.Contains("Redeem") || xml.Contains("Claim Reward") || (xml.Contains("Play Again") && !xml.Contains("Welcome Back")))
                {
                    lastProgressTime = DateTime.Now;
                    Log("[CLAIM] Congratulations! Tapping 'Collect Gem' to watch ad...");

                    Match mCollect = Regex.Match(xml, @"<node [^>]*text=""[^""]*(Collect Gem|Redeem|Claim|Play Again)[^""]*""[^>]*bounds=""\[(\d+),(\d+)\]\[(\d+),(\d+)\]""");
                    if (mCollect.Success)
                    {
                        int cx = (int.Parse(mCollect.Groups[2].Value) + int.Parse(mCollect.Groups[4].Value)) / 2;
                        int cy = (int.Parse(mCollect.Groups[3].Value) + int.Parse(mCollect.Groups[5].Value)) / 2;
                        AdbTap(cx, cy);
                    }
                    else
                    {
                        AdbTapRef(450, 560);
                    }

                    Match mGemsProg = Regex.Match(xml, @"text=""(\d+)/25\s*gems""");
                    if (mGemsProg.Success)
                    {
                        currentOfferGems = int.Parse(mGemsProg.Groups[1].Value) + 1;
                    }
                    else
                    {
                        currentOfferGems++;
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

                // CASE 5: Quiz Screen Solving (Math Quiz & Captcha Quiz)
                int qMinY = (int)(devHeight * 0.18);
                int qMaxY = (int)(devHeight * 0.40);
                int optMinY = (int)(devHeight * 0.42);
                int optMaxY = (int)(devHeight * 0.68);

                int btnX = 0, btnY = 0;

                // --- 5A. MATH QUIZ ---
                Match mMath = Regex.Match(xml, @"(?:text|content-desc)=""(\d+)\s*([\+\-\–\—\*\×xX]|÷|\/)\s*(\d+)(?:\s*=\s*\?)?""[^>]*bounds=""\[(\d+),(\d+)\]\[(\d+),(\d+)\]""");
                if (mMath.Success)
                {
                    int my1 = int.Parse(mMath.Groups[5].Value);
                    int my2 = int.Parse(mMath.Groups[7].Value);
                    int mcy = (my1 + my2) / 2;
                    if (mcy >= qMinY && mcy <= qMaxY)
                    {
                        int n1 = int.Parse(mMath.Groups[1].Value);
                        string op = mMath.Groups[2].Value;
                        int n2 = int.Parse(mMath.Groups[3].Value);
                        int ans = -1;
                        if (op == "+") ans = n1 + n2;
                        else if (op == "-" || op == "–" || op == "—") ans = n1 - n2;
                        else if (op == "*" || op == "×" || op.Equals("x", StringComparison.OrdinalIgnoreCase)) ans = n1 * n2;
                        else if ((op == "/" || op == "÷") && n2 != 0) ans = n1 / n2;

                        if (ans >= 0)
                        {
                            string mathTarget = string.Format("MATH:{0}{1}{2}={3}", n1, op, n2, ans);
                            if (mathTarget == lastSolvedTarget && (DateTime.Now - lastSolveTime).TotalMilliseconds < 2500)
                            {
                                Thread.Sleep(200);
                                continue;
                            }

                            string ansStr = ans.ToString();
                            Log(string.Format("[Solver] Math in Question Zone: {0} {1} {2} = {3}", n1, op, n2, ans));

                            // Find answer button strictly in Options Zone
                            MatchCollection mAllAns = Regex.Matches(xml, string.Format(@"<node [^>]*text=""{0}""[^>]*bounds=""\[(\d+),(\d+)\]\[(\d+),(\d+)\]""", ansStr));
                            foreach (Match ma in mAllAns)
                            {
                                int ay1 = int.Parse(ma.Groups[2].Value);
                                int ay2 = int.Parse(ma.Groups[4].Value);
                                int acy = (ay1 + ay2) / 2;
                                if (acy >= (optMinY - 40) && acy <= (optMaxY + 60))
                                {
                                    btnX = (int.Parse(ma.Groups[1].Value) + int.Parse(ma.Groups[3].Value)) / 2;
                                    btnY = acy;
                                    break;
                                }
                            }

                            if (btnX > 0 && btnY > 0)
                            {
                                Log(string.Format("[ANSWER] Tapping Math '{0}' at ({1}, {2})...", ansStr, btnX, btnY));
                                AdbTap(btnX, btnY);
                                lastSolvedTarget = mathTarget;
                                lastSolveTime = DateTime.Now;
                                questionsSolved++;
                                todaySolved++;
                                lastProgressTime = DateTime.Now;
                                SaveStatsToFile();
                                UpdateDashboard(ansStr);
                                Thread.Sleep(chkTurboSpeed.Checked ? 200 : 600);
                                continue;
                            }
                        }
                    }
                }

                // --- 5B. CAPTCHA QUIZ (Number Matching) ---
                string targetNumber = "";
                MatchCollection mNumNodes = Regex.Matches(xml, @"<node [^>]*text=""(\d{3,6})""[^>]*bounds=""\[(\d+),(\d+)\]\[(\d+),(\d+)\]""");

                // 1. Find question target number strictly in Question Zone
                foreach (Match m in mNumNodes)
                {
                    int y1 = int.Parse(m.Groups[3].Value);
                    int y2 = int.Parse(m.Groups[5].Value);
                    int cy = (y1 + y2) / 2;
                    if (cy >= qMinY && cy <= qMaxY)
                    {
                        targetNumber = m.Groups[1].Value;
                        break;
                    }
                }

                // Fallback: Content-desc digit items in Question Zone
                if (string.IsNullOrEmpty(targetNumber))
                {
                    List<DigitItem> digits = new List<DigitItem>();
                    MatchCollection dMatches = Regex.Matches(xml, @"<node [^>]*content-desc=""(\d)""[^>]*bounds=""\[(\d+),(\d+)\]\[(\d+),(\d+)\]""");
                    foreach (Match dm in dMatches)
                    {
                        string d = dm.Groups[1].Value;
                        int x1 = int.Parse(dm.Groups[2].Value);
                        int y1 = int.Parse(dm.Groups[3].Value);
                        int y2 = int.Parse(dm.Groups[5].Value);
                        int cy = (y1 + y2) / 2;
                        if (cy >= qMinY && cy <= qMaxY)
                        {
                            digits.Add(new DigitItem { Digit = d, X = x1 });
                        }
                    }

                    if (digits.Count >= 3)
                    {
                        digits = digits.OrderBy(o => o.X).ToList();
                        targetNumber = string.Join("", digits.Select(o => o.Digit).ToArray());
                    }
                }

                if (!string.IsNullOrEmpty(targetNumber))
                {
                    string captchaTarget = "CAPTCHA:" + targetNumber;
                    if (captchaTarget == lastSolvedTarget && (DateTime.Now - lastSolveTime).TotalMilliseconds < 2500)
                    {
                        Thread.Sleep(200);
                        continue;
                    }

                    lastCaptcha = targetNumber;
                    Log(string.Format("[QUIZ] Target: '{0}' | Progress: {1}/25", targetNumber, currentOfferGems));

                    // 2. Find option button strictly in Options Zone
                    foreach (Match m in mNumNodes)
                    {
                        if (m.Groups[1].Value == targetNumber)
                        {
                            int y1 = int.Parse(m.Groups[3].Value);
                            int y2 = int.Parse(m.Groups[5].Value);
                            int cy = (y1 + y2) / 2;
                            if (cy >= (optMinY - 40) && cy <= (optMaxY + 60))
                            {
                                int x1 = int.Parse(m.Groups[2].Value);
                                int x2 = int.Parse(m.Groups[4].Value);
                                btnX = (x1 + x2) / 2;
                                btnY = cy;
                                break;
                            }
                        }
                    }

                    // Fallback: search with content-desc in Options Zone
                    if (btnX == 0)
                    {
                        string optPattern = string.Format(@"<node [^>]*content-desc=""{0}""[^>]*bounds=""\[(\d+),(\d+)\]\[(\d+),(\d+)\]""", targetNumber);
                        MatchCollection optMatches = Regex.Matches(xml, optPattern);
                        foreach (Match optMatch in optMatches)
                        {
                            int oy1 = int.Parse(optMatch.Groups[2].Value);
                            int oy2 = int.Parse(optMatch.Groups[4].Value);
                            int ocy = (oy1 + oy2) / 2;
                            if (ocy >= (optMinY - 40) && ocy <= (optMaxY + 60))
                            {
                                int ox1 = int.Parse(optMatch.Groups[1].Value);
                                int ox2 = int.Parse(optMatch.Groups[3].Value);
                                btnX = (ox1 + ox2) / 2;
                                btnY = ocy;
                                break;
                            }
                        }
                    }

                    if (btnX > 0 && btnY > 0)
                    {
                        Log(string.Format("[ANSWER] Tapping '{0}' at ({1}, {2})...", targetNumber, btnX, btnY));
                        AdbTap(btnX, btnY);

                        lastSolvedTarget = captchaTarget;
                        lastSolveTime = DateTime.Now;
                        questionsSolved++;
                        todaySolved++;
                        lastProgressTime = DateTime.Now;
                        SaveStatsToFile();
                        UpdateDashboard(lastCaptcha);
                        Thread.Sleep(chkTurboSpeed.Checked ? 200 : 600);
                        continue;
                    }
                }

                // Fallback check for any lingering ad close buttons
                if (TryFindAndClickAdButton(xml, 25))
                {
                    adsClosed++;
                    SaveStatsToFile();
                    UpdateDashboard(lastCaptcha);
                    lastProgressTime = DateTime.Now;
                    Thread.Sleep(800);
                    continue;
                }

                Thread.Sleep(chkTurboSpeed.Checked ? 120 : 500);
            }
        }
        catch (Exception ex)
        {
            Log("[Error] Loop exception: " + ex.Message);
            StopBot();
        }
    }

    private Panel CreateToggleRow(Panel parent, string text, DarkToggleSwitch toggle, int y, Action onToggle)
    {
        Panel row = new Panel();
        row.BackColor = Color.Transparent;
        row.Location = new Point(12, y);
        row.Size = new Size(480, 28);
        row.Cursor = Cursors.Hand;

        Label lbl = new Label();
        lbl.Text = text;
        lbl.Font = new Font("Segoe UI", 9f);
        lbl.ForeColor = toggle.Checked ? Color.FromArgb(220, 220, 225) : Color.FromArgb(140, 140, 145);
        lbl.Location = new Point(4, 4);
        lbl.Size = new Size(410, 20);
        lbl.Cursor = Cursors.Hand;

        toggle.Location = new Point(430, 4);
        toggle.Size = new Size(38, 20);

        Action updateColor = () => {
            lbl.ForeColor = toggle.Checked ? Color.FromArgb(225, 225, 230) : Color.FromArgb(140, 140, 145);
        };

        toggle.CheckedChanged += delegate {
            updateColor();
            if (onToggle != null) onToggle();
        };

        lbl.Click += delegate {
            toggle.Checked = !toggle.Checked;
        };
        row.Click += delegate {
            toggle.Checked = !toggle.Checked;
        };

        lbl.MouseEnter += delegate { if (!toggle.Checked) lbl.ForeColor = Color.FromArgb(185, 185, 190); };
        lbl.MouseLeave += delegate { updateColor(); };

        row.Controls.Add(lbl);
        row.Controls.Add(toggle);
        parent.Controls.Add(row);

        return row;
    }

    private static Mutex appMutex = null;

    [STAThread]
    public static void Main(string[] args)
    {
        bool createdNew;
        appMutex = new Mutex(true, "QuizEarnBot_SingleInstance_Mutex_9921", out createdNew);
        if (!createdNew)
        {
            try {
                Process current = Process.GetCurrentProcess();
                foreach (Process p in Process.GetProcessesByName(current.ProcessName)) {
                    if (p.Id != current.Id && p.MainWindowHandle != IntPtr.Zero) {
                        ShowWindow(p.MainWindowHandle, 9);
                        SetForegroundWindow(p.MainWindowHandle);
                        return;
                    }
                }
            } catch {}
        }

        AppDomain.CurrentDomain.UnhandledException += delegate(object s, UnhandledExceptionEventArgs e) {
            try { File.AppendAllText(Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "crash.log"), "[FATAL] " + e.ExceptionObject.ToString() + "\n"); } catch {}
        };
        Application.SetUnhandledExceptionMode(UnhandledExceptionMode.CatchException);
        Application.ThreadException += delegate(object s, ThreadExceptionEventArgs e) {
            try { File.AppendAllText(Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "crash.log"), "[UI THREAD ERROR] " + e.Exception.ToString() + "\n"); } catch {}
        };

        Application.EnableVisualStyles();
        Application.SetCompatibleTextRenderingDefault(false);
        QuizEarnApp app = new QuizEarnApp();
        if (args != null && args.Length > 0)
        {
            string a0 = args[0].ToLower();
            if (a0.Contains("auto") || a0.Contains("start"))
            {
                app.Shown += delegate {
                    Thread.Sleep(500);
                    app.StartBot();
                };
            }
            else if (a0.Contains("offer") || a0.Contains("claim"))
            {
                app.Shown += delegate {
                    Thread.Sleep(500);
                    app.StartAutoOfferClaimer();
                };
            }
        }
        try
        {
            Application.Run(app);
        }
        catch (Exception ex)
        {
            File.WriteAllText(Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "run_error.log"), "[APP RUN CRASH] " + ex.ToString());
        }
    }
}

public class DarkToggleSwitch : Control
{
    private bool _checked = false;
    private bool _isHovered = false;
    public event EventHandler CheckedChanged;

    public bool Checked
    {
        get { return _checked; }
        set
        {
            if (_checked != value)
            {
                _checked = value;
                this.Invalidate();
                if (CheckedChanged != null) CheckedChanged(this, EventArgs.Empty);
            }
        }
    }

    public DarkToggleSwitch()
    {
        this.SetStyle(ControlStyles.UserPaint |
                      ControlStyles.AllPaintingInWmPaint |
                      ControlStyles.OptimizedDoubleBuffer |
                      ControlStyles.ResizeRedraw |
                      ControlStyles.SupportsTransparentBackColor, true);
        this.Size = new Size(38, 20);
        this.BackColor = Color.Transparent;
        this.Cursor = Cursors.Hand;
        this.TabStop = false;
    }

    protected override void OnMouseEnter(EventArgs e)
    {
        base.OnMouseEnter(e);
        _isHovered = true;
        this.Invalidate();
    }

    protected override void OnMouseLeave(EventArgs e)
    {
        base.OnMouseLeave(e);
        _isHovered = false;
        this.Invalidate();
    }

    protected override void OnClick(EventArgs e)
    {
        base.OnClick(e);
        this.Checked = !this.Checked;
    }

    protected override void OnPaint(PaintEventArgs pe)
    {
        Graphics g = pe.Graphics;
        g.SmoothingMode = SmoothingMode.AntiAlias;
        g.PixelOffsetMode = PixelOffsetMode.HighQuality;

        int w = this.Width;
        int h = this.Height;
        int d = h - 1;

        using (GraphicsPath path = new GraphicsPath())
        {
            path.AddArc(0, 0, d, d, 90, 180);
            path.AddArc(w - d - 1, 0, d, d, 270, 180);
            path.CloseFigure();

            if (_checked)
            {
                Color bgColor = _isHovered ? Color.FromArgb(20, 200, 140) : Color.FromArgb(16, 185, 129);
                Color borderColor = _isHovered ? Color.FromArgb(25, 225, 160) : Color.FromArgb(5, 150, 105);

                using (SolidBrush bgBrush = new SolidBrush(bgColor))
                {
                    g.FillPath(bgBrush, path);
                }
                using (Pen borderPen = new Pen(borderColor, 1f))
                {
                    g.DrawPath(borderPen, path);
                }

                int knobSize = h - 6;
                int knobX = w - knobSize - 3;
                int knobY = 3;

                using (SolidBrush knobBrush = new SolidBrush(Color.White))
                {
                    g.FillEllipse(knobBrush, knobX, knobY, knobSize, knobSize);
                }
            }
            else
            {
                Color bgColor = _isHovered ? Color.FromArgb(28, 28, 34) : Color.FromArgb(18, 18, 22);
                Color borderColor = _isHovered ? Color.FromArgb(58, 58, 68) : Color.FromArgb(38, 38, 46);
                Color knobColor = _isHovered ? Color.FromArgb(150, 150, 160) : Color.FromArgb(100, 100, 110);

                using (SolidBrush bgBrush = new SolidBrush(bgColor))
                {
                    g.FillPath(bgBrush, path);
                }
                using (Pen borderPen = new Pen(borderColor, 1f))
                {
                    g.DrawPath(borderPen, path);
                }

                int knobSize = h - 8;
                int knobX = 4;
                int knobY = 4;

                using (SolidBrush knobBrush = new SolidBrush(knobColor))
                {
                    g.FillEllipse(knobBrush, knobX, knobY, knobSize, knobSize);
                }
            }
        }
    }
}
