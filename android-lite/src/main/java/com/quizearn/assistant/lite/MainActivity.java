package com.quizearn.assistant.lite;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final int REQ_POST_NOTIFICATIONS = 1002;

    private TextView txtOverlayStatus;
    private TextView txtAccessStatus;
    private TextView txtBatteryStatus;
    private Button btnOverlay;
    private Button btnAccess;
    private Button btnBattery;
    private Button btnLaunch;
    private Button btnTargetApp;

    private static final String[] TARGET_PACKAGES = new String[]{
        "com.quiz.earn.money.game",
        "com.rayole.cashmafia2"
    };
    private static final String[] TARGET_LABELS = new String[]{
        "Target: Quiz & Earn",
        "Target: CashMafia"
    };
    private int selectedTargetIdx = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SharedPreferences prefs = getSharedPreferences("QuizEarnLitePrefs", Context.MODE_PRIVATE);
        selectedTargetIdx = prefs.getInt("selected_target_idx", 0);
        if (selectedTargetIdx < 0 || selectedTargetIdx >= TARGET_PACKAGES.length) {
            selectedTargetIdx = 0;
        }
        QuizEarnAccessibilityService.setPreferredQuizPackage(this, TARGET_PACKAGES[selectedTargetIdx]);

        DisplayMetrics dm = getResources().getDisplayMetrics();
        int padH = Math.max(dp(16), Math.min(dp(28), (int) (dm.widthPixels * 0.055f / dm.density * dm.density)));

        ScrollView scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(Color.parseColor("#000000"));
        scrollView.setFillViewport(true);
        scrollView.setFitsSystemWindows(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#000000"));
        root.setPadding(padH, dp(28), padH, dp(28));
        root.setGravity(Gravity.CENTER_HORIZONTAL);

        LinearLayout contentCol = new LinearLayout(this);
        contentCol.setOrientation(LinearLayout.VERTICAL);
        int maxColWidth = Math.min(dm.widthPixels - (padH * 2), dp(540));
        contentCol.setLayoutParams(new LinearLayout.LayoutParams(
            maxColWidth > 0 ? maxColWidth : LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        TextView title = new TextView(this);
        title.setText("Quiz & Earn Lite");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);

        TextView subtitle = new TextView(this);
        subtitle.setText("Pure Math & Ads Solver • Auto-Stops & Alerts at 25 Gems");
        subtitle.setTextColor(Color.parseColor("#10B981"));
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        subtitle.setPadding(0, dp(4), 0, dp(20));
        subtitle.setGravity(Gravity.CENTER);

        contentCol.addView(title);
        contentCol.addView(subtitle);

        // 1. Overlay Permission Card
        LinearLayout cardOverlay = createCard(
            "1. FLOATING HUD PERMISSION",
            "Required to display the floating start/pause bubble over the quiz app."
        );
        txtOverlayStatus = createStatusBadge("Checking...");
        btnOverlay = createActionButton("GRANT OVERLAY PERMISSION", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openOverlaySettingsSafely();
            }
        });
        cardOverlay.addView(txtOverlayStatus);
        cardOverlay.addView(btnOverlay);
        contentCol.addView(cardOverlay);

        // 2. Accessibility Service Card
        LinearLayout cardAccess = createCard(
            "2. ACCESSIBILITY SERVICE",
            "Calculates math questions, collects gems, closes ads, and stops at 25 gems."
        );
        txtAccessStatus = createStatusBadge("Checking...");
        btnAccess = createActionButton("ENABLE ACCESSIBILITY", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openAccessibilitySettingsSafely();
            }
        });
        cardAccess.addView(txtAccessStatus);
        cardAccess.addView(btnAccess);
        contentCol.addView(cardAccess);

        // 3. Target App Selector Card
        LinearLayout cardTarget = createCard(
            "3. TARGET APPLICATION",
            "Switch between Quiz & Earn and CashMafia."
        );
        btnTargetApp = createActionButton(TARGET_LABELS[selectedTargetIdx], new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectedTargetIdx = (selectedTargetIdx + 1) % TARGET_PACKAGES.length;
                getSharedPreferences("QuizEarnLitePrefs", Context.MODE_PRIVATE)
                    .edit()
                    .putInt("selected_target_idx", selectedTargetIdx)
                    .apply();
                QuizEarnAccessibilityService.setPreferredQuizPackage(MainActivity.this, TARGET_PACKAGES[selectedTargetIdx]);
                btnTargetApp.setText(TARGET_LABELS[selectedTargetIdx]);
            }
        });
        cardTarget.addView(btnTargetApp);
        contentCol.addView(cardTarget);

        // 4. Battery Optimization Card
        LinearLayout cardBattery = createCard(
            "4. BACKGROUND STABILITY (RECOMMENDED)",
            "Prevents OEM battery killers from pausing the bot during ad playback."
        );
        txtBatteryStatus = createStatusBadge("Checking...");
        btnBattery = createActionButton("DISABLE BATTERY OPTIMIZATION", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestIgnoreBatteryOptimizationsSafely();
            }
        });
        cardBattery.addView(txtBatteryStatus);
        cardBattery.addView(btnBattery);
        contentCol.addView(cardBattery);

        // Launch Button
        btnLaunch = new Button(this);
        btnLaunch.setText("LAUNCH FLOATING HUD");
        btnLaunch.setTextColor(Color.BLACK);
        btnLaunch.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        btnLaunch.setTypeface(null, Typeface.BOLD);
        btnLaunch.setMinimumHeight(dp(48));
        btnLaunch.setPadding(dp(20), dp(14), dp(20), dp(14));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#10B981"));
        bg.setCornerRadius(dp(14));
        btnLaunch.setBackground(bg);

        btnLaunch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startFloatingService();
            }
        });

        LinearLayout launchContainer = new LinearLayout(this);
        launchContainer.setOrientation(LinearLayout.VERTICAL);
        launchContainer.setPadding(0, dp(8), 0, dp(16));
        launchContainer.addView(btnLaunch);
        contentCol.addView(launchContainer);

        TextView infoText = new TextView(this);
        infoText.setText("Lite Mode Highlights:\n• Fast Math Quiz & Captcha solver (0.05s response)\n• Auto-watches & closes ads when timers expire\n• Stops immediately at 25 gems and sounds an alarm alert\n• Zero Super Offer automation or Play Store downloads (100% safe)");
        infoText.setTextColor(Color.parseColor("#9CA3AF"));
        infoText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        infoText.setLineSpacing(dp(4), 1f);
        infoText.setPadding(dp(4), dp(4), dp(4), dp(16));
        contentCol.addView(infoText);

        root.addView(contentCol);
        scrollView.addView(root);
        setContentView(scrollView);

        requestNotificationPermissionIfNeeded();
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                try {
                    requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_POST_NOTIFICATIONS);
                } catch (Exception ignored) {}
            }
        }
    }

    private void openOverlaySettingsSafely() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
            } catch (Exception e2) {
                try {
                    Intent details = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()));
                    startActivity(details);
                } catch (Exception e3) {
                    Toast.makeText(this, "Enable 'Display over other apps' in Settings.", Toast.LENGTH_LONG).show();
                }
            }
        }
    }

    private void openAccessibilitySettingsSafely() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (Exception ignored) {
                Toast.makeText(this, "Open Settings > Accessibility > Quiz & Earn Lite.", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void requestIgnoreBatteryOptimizationsSafely() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception ignored) {
                Toast.makeText(this, "Set Battery usage to Unrestricted in App Info.", Toast.LENGTH_LONG).show();
            }
        }
    }

    private boolean isAccessibilityEnabledInSettings() {
        try {
            int enabled = Settings.Secure.getInt(getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0);
            if (enabled != 1) return false;
            String services = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (TextUtils.isEmpty(services)) return false;
            return services.contains(getPackageName() + "/" + QuizEarnAccessibilityService.class.getName()) ||
                   services.contains(getPackageName() + "/.QuizEarnAccessibilityService");
        } catch (Exception ignored) {
            return false;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshPermissions();
    }

    private void refreshPermissions() {
        boolean hasOverlay = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            hasOverlay = Settings.canDrawOverlays(this);
        }

        boolean serviceBound = QuizEarnAccessibilityService.isServiceActive;
        boolean settingEnabled = isAccessibilityEnabledInSettings();
        boolean hasAccess = serviceBound || settingEnabled;

        if (hasOverlay) {
            txtOverlayStatus.setText("✓ Overlay Permission Granted");
            txtOverlayStatus.setTextColor(Color.parseColor("#10B981"));
            btnOverlay.setEnabled(false);
            btnOverlay.setText("GRANTED");
        } else {
            txtOverlayStatus.setText("✕ Overlay Permission Needed");
            txtOverlayStatus.setTextColor(Color.parseColor("#EF4444"));
            btnOverlay.setEnabled(true);
            btnOverlay.setText("GRANT OVERLAY PERMISSION");
        }

        if (serviceBound) {
            txtAccessStatus.setText("✓ Accessibility Service Active");
            txtAccessStatus.setTextColor(Color.parseColor("#10B981"));
            btnAccess.setEnabled(false);
            btnAccess.setText("ENABLED");
        } else if (settingEnabled) {
            txtAccessStatus.setText("● Enabled (Waiting for OS bind)");
            txtAccessStatus.setTextColor(Color.parseColor("#F59E0B"));
            btnAccess.setEnabled(true);
            btnAccess.setText("OPEN ACCESSIBILITY SETTINGS");
        } else {
            txtAccessStatus.setText("✕ Accessibility Service Disabled");
            txtAccessStatus.setTextColor(Color.parseColor("#EF4444"));
            btnAccess.setEnabled(true);
            btnAccess.setText("ENABLE ACCESSIBILITY");
        }

        boolean ignoringBattery = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            ignoringBattery = (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName()));
        }
        if (ignoringBattery) {
            txtBatteryStatus.setText("✓ Unrestricted Background Execution");
            txtBatteryStatus.setTextColor(Color.parseColor("#10B981"));
            btnBattery.setEnabled(false);
            btnBattery.setText("UNRESTRICTED");
        } else {
            txtBatteryStatus.setText("● Battery Optimized");
            txtBatteryStatus.setTextColor(Color.parseColor("#F59E0B"));
            btnBattery.setEnabled(true);
            btnBattery.setText("DISABLE BATTERY OPTIMIZATION");
        }

        btnLaunch.setEnabled(hasOverlay && hasAccess);
        btnLaunch.setAlpha((hasOverlay && hasAccess) ? 1.0f : 0.5f);
    }

    private void startFloatingService() {
        Intent intent = new Intent(this, FloatingOverlayService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }

        String preferred = TARGET_PACKAGES[selectedTargetIdx];
        String secondary = TARGET_PACKAGES[(selectedTargetIdx + 1) % TARGET_PACKAGES.length];
        String[] order = new String[]{preferred, secondary};
        for (String pkg : order) {
            try {
                Intent launchIntent = getPackageManager().getLaunchIntentForPackage(pkg);
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(launchIntent);
                    break;
                }
            } catch (Exception ignored) {}
        }
        finish();
    }

    private LinearLayout createCard(String titleText, String descText) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#111115"));
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), Color.parseColor("#1F1F26"));
        card.setBackground(bg);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        );
        lp.setMargins(0, 0, 0, dp(12));
        card.setLayoutParams(lp);

        TextView t = new TextView(this);
        t.setText(titleText);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        t.setTypeface(null, Typeface.BOLD);
        card.addView(t);

        TextView d = new TextView(this);
        d.setText(descText);
        d.setTextColor(Color.parseColor("#9CA3AF"));
        d.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        d.setPadding(0, dp(4), 0, dp(10));
        card.addView(d);

        return card;
    }

    private TextView createStatusBadge(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setPadding(0, 0, 0, dp(8));
        return tv;
    }

    private Button createActionButton(String label, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        b.setTypeface(null, Typeface.BOLD);
        b.setMinimumHeight(dp(40));
        b.setPadding(dp(12), dp(8), dp(12), dp(8));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#1F1F26"));
        bg.setCornerRadius(dp(8));
        b.setBackground(bg);

        if (listener != null) b.setOnClickListener(listener);
        return b;
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(value * Math.max(0.75f, density));
    }
}
