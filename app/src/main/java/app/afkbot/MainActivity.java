package app.afkbot;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
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
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity implements Hub.Listener {
    private static final int BG = 0xFF121212, CARD = 0xFF1E1E1E, FG = 0xFFEEEEEE, DIM = 0xFFAAAAAA, ACCENT = 0xFF4CAF50;

    private TextView account, codeBox, status, detail, log;
    private Button signIn, signOut, startStop, openLink, copyCode, battery;
    private EditText address, jumpSecs, offlineName;
    private CheckBox offline, reconnect;
    private ScrollView logScroll;
    private volatile Thread signInThread;
    private volatile boolean cancelSignIn;
    private String lastLog = "";

    @Override
    protected void onCreate(Bundle b) {
        setTheme(android.R.style.Theme_DeviceDefault);
        super.onCreate(b);
        getWindow().setStatusBarColor(BG);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        buildUi();
        loadSettings();
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Hub.addListener(this);
        onChange();
    }

    @Override
    protected void onPause() {
        super.onPause();
        Hub.removeListener(this);
        saveSettings();
    }

    // ------------------------------------------------------------------ UI

    private void buildUi() {
        ScrollView root = new ScrollView(this);
        root.setBackgroundColor(BG);
        root.setFillViewport(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        col.setPadding(pad, pad, pad, pad);
        root.addView(col);

        TextView title = text("AFK Bot", 26, FG);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(title);
        col.addView(text("Keeps your Minecraft: Java account online on a server and jumps now and then so you aren't kicked for being idle.", 13, DIM));

        // --- Status card
        LinearLayout sc = card(col);
        status = text("Stopped", 20, FG);
        status.setTypeface(Typeface.DEFAULT_BOLD);
        sc.addView(status);
        detail = text("", 14, DIM);
        sc.addView(detail);
        startStop = button("Start AFK");
        startStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggle();
            }
        });
        sc.addView(startStop);

        // --- Account card
        LinearLayout ac = card(col);
        ac.addView(label("Account"));
        account = text("", 15, FG);
        ac.addView(account);
        codeBox = text("", 15, FG);
        codeBox.setVisibility(View.GONE);
        codeBox.setPadding(0, dp(8), 0, dp(8));
        ac.addView(codeBox);
        LinearLayout codeRow = row(ac);
        openLink = button("Open microsoft.com/link");
        copyCode = button("Copy code");
        codeRow.addView(openLink, weight());
        codeRow.addView(copyCode, weight());
        openLink.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String code = Hub.authCode;
                String url = "https://www.microsoft.com/link" + (code != null ? "?otc=" + code : "");
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Exception e) {
                    toast("No browser found");
                }
            }
        });
        copyCode.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String code = Hub.authCode;
                if (code == null) return;
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("code", code));
                toast("Code copied");
            }
        });
        LinearLayout accRow = row(ac);
        signIn = button("Sign in with Microsoft");
        signOut = button("Sign out");
        accRow.addView(signIn, weight());
        accRow.addView(signOut, weight());
        signIn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (signInThread != null) {
                    cancelSignIn = true;
                } else {
                    startSignIn();
                }
            }
        });
        signOut.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Auth.signOut(MainActivity.this);
                Hub.log("Signed out");
                onChange();
            }
        });

        // --- Server card
        LinearLayout sv = card(col);
        sv.addView(label("Server address (default: constantiam.net)"));
        address = edit("constantiam.net", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        sv.addView(address);
        sv.addView(label("Jump every (seconds)"));
        jumpSecs = edit("30", InputType.TYPE_CLASS_NUMBER);
        sv.addView(jumpSecs);
        reconnect = check("Reconnect automatically if disconnected");
        sv.addView(reconnect);
        offline = check("Offline / cracked server (no Microsoft login)");
        sv.addView(offline);
        offlineName = edit("Username for offline server", InputType.TYPE_CLASS_TEXT);
        sv.addView(offlineName);
        offline.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                offlineName.setVisibility(offline.isChecked() ? View.VISIBLE : View.GONE);
            }
        });
        battery = button("Let it run in the background (battery settings)");
        battery.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                askBattery();
            }
        });
        sv.addView(battery);

        // --- Log card
        LinearLayout lc = card(col);
        LinearLayout lr = row(lc);
        TextView lt = label("Activity log");
        lr.addView(lt, weight());
        Button clear = button("Copy log");
        clear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("log", Hub.logText()));
                toast("Log copied");
            }
        });
        lr.addView(clear);
        logScroll = new ScrollView(this);
        log = text("", 12, FG);
        log.setTypeface(Typeface.MONOSPACE);
        log.setTextIsSelectable(true);
        logScroll.addView(log);
        lc.addView(logScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(320)));

        setContentView(root);
    }

    @Override
    public void onChange() {
        boolean running = Hub.running;
        status.setText(Hub.status);
        status.setTextColor(Hub.color == Hub.GREY ? FG : Hub.color);
        String d = Hub.detail;
        detail.setText(d);
        detail.setVisibility(d.isEmpty() ? View.GONE : View.VISIBLE);
        startStop.setText(running ? "Stop" : "Start AFK");
        tint(startStop, running ? 0xFFC62828 : ACCENT);

        String name = Auth.signedInName(this);
        boolean signing = signInThread != null;
        account.setText(name != null ? "Signed in as " + name : signing ? "Waiting for you to sign in..." : "Not signed in");
        signIn.setText(signing ? "Cancel sign-in" : name != null ? "Switch account" : "Sign in with Microsoft");
        signOut.setVisibility(name != null && !signing ? View.VISIBLE : View.GONE);
        String code = Hub.authCode;
        boolean showCode = signing && code != null;
        codeBox.setVisibility(showCode ? View.VISIBLE : View.GONE);
        openLink.setVisibility(showCode ? View.VISIBLE : View.GONE);
        copyCode.setVisibility(showCode ? View.VISIBLE : View.GONE);
        if (showCode) {
            codeBox.setText("1. Tap \"Open microsoft.com/link\"\n2. Enter this code:  " + code + "\n3. Sign in with the account that owns Minecraft\nThis screen updates by itself when you're done.");
        }
        offlineName.setVisibility(offline.isChecked() ? View.VISIBLE : View.GONE);
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        battery.setVisibility(pm.isIgnoringBatteryOptimizations(getPackageName()) ? View.GONE : View.VISIBLE);

        String l = Hub.logText();
        if (!l.equals(lastLog)) {
            lastLog = l;
            log.setText(l);
            logScroll.post(new Runnable() {
                @Override
                public void run() {
                    logScroll.fullScroll(View.FOCUS_DOWN);
                }
            });
        }
    }

    // ------------------------------------------------------------------ actions

    private void toggle() {
        if (Hub.running) {
            startService(new Intent(this, AfkService.class).setAction(AfkService.ACTION_STOP));
            return;
        }
        saveSettings();
        if (address.getText().toString().trim().isEmpty()) {
            toast("Enter the server address first");
            return;
        }
        if (!offline.isChecked() && Auth.signedInName(this) == null) {
            toast("Sign in with Microsoft first (or tick offline server)");
            return;
        }
        Hub.running = true;
        Hub.status("Starting...", Hub.YELLOW);
        startForegroundService(new Intent(this, AfkService.class).setAction(AfkService.ACTION_START));
    }

    private void startSignIn() {
        cancelSignIn = false;
        Hub.log("Starting Microsoft sign-in...");
        final android.content.Context ctx = getApplicationContext();
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Auth.signIn(ctx, new Auth.Cancel() {
                        @Override
                        public boolean cancelled() {
                            return cancelSignIn;
                        }
                    });
                } catch (Exception e) {
                    Hub.log("Sign-in failed: " + e.getMessage());
                } finally {
                    signInThread = null;
                    Hub.fire();
                }
            }
        }, "sign-in");
        signInThread = t;
        t.start();
        onChange();
    }

    private void askBattery() {
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception ignored) {
                toast("Open Settings > Apps > AFK Bot > Battery and choose Unrestricted");
            }
        }
    }

    private void loadSettings() {
        SharedPreferences p = getSharedPreferences("settings", MODE_PRIVATE);
        address.setText(p.getString("address", "constantiam.net"));
        jumpSecs.setText(String.valueOf(p.getInt("jumpSeconds", 30)));
        reconnect.setChecked(p.getBoolean("autoReconnect", true));
        offline.setChecked(p.getBoolean("offline", false));
        offlineName.setText(p.getString("offlineName", ""));
    }

    private void saveSettings() {
        int secs = 30;
        try {
            secs = Math.max(2, Integer.parseInt(jumpSecs.getText().toString().trim()));
        } catch (NumberFormatException ignored) {
        }
        getSharedPreferences("settings", MODE_PRIVATE).edit()
                .putString("address", address.getText().toString().trim())
                .putInt("jumpSeconds", secs)
                .putBoolean("autoReconnect", reconnect.isChecked())
                .putBoolean("offline", offline.isChecked())
                .putString("offlineName", offlineName.getText().toString().trim())
                .apply();
    }

    // ------------------------------------------------------------------ view helpers

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setPadding(0, dp(2), 0, dp(2));
        return t;
    }

    private TextView label(String s) {
        TextView t = text(s, 13, DIM);
        t.setPadding(0, dp(8), 0, dp(2));
        return t;
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(12), dp(14), dp(14));
        GradientDrawable g = new GradientDrawable();
        g.setColor(CARD);
        g.setCornerRadius(dp(12));
        c.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        parent.addView(c, lp);
        return c;
    }

    private LinearLayout row(LinearLayout parent) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        parent.addView(r);
        return r;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(0, 0, dp(6), 0);
        return lp;
    }

    private Button button(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        tint(b, 0xFF37474F);
        return b;
    }

    private void tint(Button b, int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(8));
        b.setBackground(g);
        b.setPadding(dp(12), dp(10), dp(12), dp(10));
        if (b.getLayoutParams() instanceof LinearLayout.LayoutParams) return;
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        b.setLayoutParams(lp);
    }

    private EditText edit(String hint, int type) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setInputType(type);
        e.setSingleLine(true);
        e.setTextColor(FG);
        e.setHintTextColor(0xFF666666);
        return e;
    }

    private CheckBox check(String s) {
        CheckBox c = new CheckBox(this);
        c.setText(s);
        c.setTextColor(FG);
        return c;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
