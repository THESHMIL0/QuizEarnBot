package com.quizearn.assistant;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {

    private TextView txtOverlayStatus;
    private TextView txtAccessStatus;
    private Button btnOverlay;
    private Button btnAccess;
    private Button btnLaunch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(Color.parseColor("#000000"));
        scrollView.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#000000"));
        root.setPadding(dp(24), dp(40), dp(24), dp(30));
        root.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = new TextView(this);
        title.setText("Quiz Math Assistant");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);

        TextView subtitle = new TextView(this);
        subtitle.setText("Instant Math Solver • Floating HUD");
        subtitle.setTextColor(Color.parseColor("#10B981"));
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        subtitle.setPadding(0, dp(4), 0, dp(30));
        subtitle.setGravity(Gravity.CENTER);

        root.addView(title);
        root.addView(subtitle);

        LinearLayout cardOverlay = createCard("1. FLOATING OVERLAY PERMISSION", "Required to display the floating HUD on top of the game screen.");
        txtOverlayStatus = createStatusBadge("Checking...");
        btnOverlay = createActionButton("GRANT OVERLAY PERMISSION", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(MainActivity.this)) {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                }
            }
        });
        cardOverlay.addView(txtOverlayStatus);
        cardOverlay.addView(btnOverlay);
        root.addView(cardOverlay);

        LinearLayout cardAccess = createCard("2. ACCESSIBILITY SERVICE", "Allows the assistant to detect math questions and tap the answer.");
        txtAccessStatus = createStatusBadge("Checking...");
        btnAccess = createActionButton("ENABLE ACCESSIBILITY", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                startActivity(intent);
            }
        });
        cardAccess.addView(txtAccessStatus);
        cardAccess.addView(btnAccess);
        root.addView(cardAccess);

        btnLaunch = new Button(this);
        btnLaunch.setText("LAUNCH FLOATING HUD");
        btnLaunch.setTextColor(Color.BLACK);
        btnLaunch.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        btnLaunch.setTypeface(null, Typeface.BOLD);
        btnLaunch.setPadding(dp(20), dp(16), dp(20), dp(16));

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
        launchContainer.setPadding(0, dp(20), 0, dp(20));
        launchContainer.addView(btnLaunch);
        root.addView(launchContainer);

        TextView infoText = new TextView(this);
        infoText.setText("Quick Guide:\n1. Enable both permissions above.\n2. Tap 'Launch Floating HUD'.\n3. Open Quiz & Earn and tap START on the floating bubble.\n4. Questions will solve instantly on screen.");
        infoText.setTextColor(Color.parseColor("#9CA3AF"));
        infoText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        infoText.setLineSpacing(dp(4), 1f);
        infoText.setPadding(dp(8), dp(10), dp(8), dp(20));
        root.addView(infoText);

        scrollView.addView(root);
        setContentView(scrollView);
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

        boolean hasAccess = QuizEarnAccessibilityService.isServiceActive;

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

        if (hasAccess) {
            txtAccessStatus.setText("✓ Accessibility Service Active");
            txtAccessStatus.setTextColor(Color.parseColor("#10B981"));
            btnAccess.setEnabled(false);
            btnAccess.setText("ENABLED");
        } else {
            txtAccessStatus.setText("✕ Accessibility Service Disabled");
            txtAccessStatus.setTextColor(Color.parseColor("#EF4444"));
            btnAccess.setEnabled(true);
            btnAccess.setText("ENABLE ACCESSIBILITY");
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

        String[] targets = new String[]{"com.quiz.earn.money.game", "com.rayole.cashmafia2"};
        for (String pkg : targets) {
            Intent launchIntent = getPackageManager().getLaunchIntentForPackage(pkg);
            if (launchIntent != null) {
                startActivity(launchIntent);
                break;
            }
        }
    }

    private LinearLayout createCard(String title, String desc) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#08080A"));
        bg.setStroke(dp(1), Color.parseColor("#1F1F26"));
        bg.setCornerRadius(dp(16));
        card.setBackground(bg);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, 0, 0, dp(16));
        card.setLayoutParams(params);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        t.setTypeface(null, Typeface.BOLD);

        TextView d = new TextView(this);
        d.setText(desc);
        d.setTextColor(Color.parseColor("#9CA3AF"));
        d.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        d.setPadding(0, dp(4), 0, dp(12));

        card.addView(t);
        card.addView(d);
        return card;
    }

    private TextView createStatusBadge(String initialText) {
        TextView badge = new TextView(this);
        badge.setText(initialText);
        badge.setTextColor(Color.parseColor("#9CA3AF"));
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        badge.setPadding(0, 0, 0, dp(10));
        return badge;
    }

    private Button createActionButton(String text, View.OnClickListener onClick) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#16161A"));
        bg.setStroke(dp(1), Color.parseColor("#27272A"));
        bg.setCornerRadius(dp(10));
        btn.setBackground(bg);

        btn.setOnClickListener(onClick);
        return btn;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}

