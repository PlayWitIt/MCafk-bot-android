package app.afkbot;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements Hub.Listener {
    // ---- design tokens ----
    static final int BG = 0xFF0B0D12, SURFACE = 0xFF141821, SURFACE2 = 0xFF1B2030, STROKE = 0xFF262C3D,
            TEXT = 0xFFE8EAF0, MUTED = 0xFF8A93A6, ACCENT = 0xFF8B5CF6, ACCENT2 = 0xFF22D3EE,
            GOOD = 0xFF34D399, BAD = 0xFFF87171, WARN = 0xFFFBBF24;

    private static final String[] TABS = {"Home", "Radar", "Modules", "Chat", "Account"};
    private static final String[] TAB_ICONS = {"◉", "◎", "☰", "✉", "⚇"};

    private final Handler ui = new Handler(Looper.getMainLooper());
    private FrameLayout pages;
    private View[] pageViews = new View[TABS.length];
    private TextView[] tabLabels = new TextView[TABS.length], tabIcons = new TextView[TABS.length];
    private int tab = 0;
    private boolean resumed;

    private Bot.Config cfg;

    // Home
    private TextView statusDot, statusText, statusSub, statHealth, statNearest, statUptime, statPos, statActions, statNext, recent;
    private Button startStop;
    private RadarView miniRadar;
    // Radar
    private RadarView radar;
    private TextView radarRange, nearbyList;
    // Chat
    private TextView chatText;
    private ScrollView chatScroll;
    private EditText chatInput;
    private int shownChat = -1;
    // Account
    private TextView account, codeBox, fullLog;
    private Button signIn, signOut, openLink, copyCode, battery;
    private ScrollView logScroll;
    private String lastLog = "";

    private volatile Thread signInThread;
    private volatile boolean cancelSignIn;

    private final Runnable radarTick = new Runnable() {
        @Override
        public void run() {
            if (!resumed) return;
            if (tab == 1) {
                radar.invalidate();
                refreshNearby();
            } else if (tab == 0) {
                miniRadar.invalidate();
            }
            ui.postDelayed(this, 500); // 2 frames per second is plenty and nearly free
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        setTheme(android.R.style.Theme_DeviceDefault_NoActionBar);
        super.onCreate(b);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(SURFACE);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        cfg = app.afkbot.Settings.get(this);
        buildUi();
        if (b != null) tab = b.getInt("tab", 0);
        selectTab(tab);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("tab", tab);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        Hub.addListener(this);
        Auth.wake();
        onChange();
        ui.post(radarTick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        ui.removeCallbacks(radarTick);
        Hub.removeListener(this);
        save();
    }

    private void save() {
        app.afkbot.Settings.save(this, cfg);
    }

    // ================================================================== layout

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        // header
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(18), dp(14), dp(18), dp(8));
        TextView logo = new TextView(this);
        logo.setText("AFK");
        logo.setTextColor(Color.WHITE);
        logo.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        logo.setTextSize(13);
        logo.setGravity(Gravity.CENTER);
        logo.setBackground(gradient(ACCENT, ACCENT2, dp(9)));
        header.addView(logo, new LinearLayout.LayoutParams(dp(38), dp(30)));
        TextView title = text("  AFK Bot", 20, TEXT, true);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        statusDot = text("●", 14, MUTED, false);
        header.addView(statusDot);
        root.addView(header);

        pages = new FrameLayout(this);
        root.addView(pages, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        pageViews[0] = buildHome();
        pageViews[1] = buildRadar();
        pageViews[2] = buildModules();
        pageViews[3] = buildChat();
        pageViews[4] = buildAccount();
        for (View v : pageViews) pages.addView(v);

        // bottom navigation
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setBackgroundColor(SURFACE);
        nav.setPadding(dp(4), dp(6), dp(4), dp(8));
        for (int i = 0; i < TABS.length; i++) {
            final int idx = i;
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            item.setPadding(0, dp(4), 0, dp(2));
            tabIcons[i] = text(TAB_ICONS[i], 18, MUTED, false);
            tabIcons[i].setGravity(Gravity.CENTER);
            tabLabels[i] = text(TABS[i], 11, MUTED, false);
            tabLabels[i].setGravity(Gravity.CENTER);
            item.addView(tabIcons[i]);
            item.addView(tabLabels[i]);
            item.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectTab(idx);
                }
            });
            nav.addView(item, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        root.addView(nav);
        setContentView(root);
    }

    private void selectTab(int i) {
        tab = i;
        for (int k = 0; k < TABS.length; k++) {
            pageViews[k].setVisibility(k == i ? View.VISIBLE : View.GONE);
            int col = k == i ? TEXT : MUTED;
            tabLabels[k].setTextColor(col);
            tabIcons[k].setTextColor(k == i ? ACCENT : MUTED);
        }
        onChange();
        if (resumed) {
            ui.removeCallbacks(radarTick);
            ui.post(radarTick);
        }
    }

    // ---------------------------------------------------------------- Home

    private View buildHome() {
        LinearLayout col = page();

        LinearLayout hero = card(col);
        statusText = text("Stopped", 22, TEXT, true);
        hero.addView(statusText);
        statusSub = text("", 13, MUTED, false);
        hero.addView(statusSub);
        startStop = bigButton("Start AFK");
        startStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggle();
            }
        });
        hero.addView(startStop);

        LinearLayout grid1 = row(col);
        statHealth = tile(grid1, "Health");
        statNearest = tile(grid1, "Nearest player");
        LinearLayout grid2 = row(col);
        statUptime = tile(grid2, "Online for");
        statPos = tile(grid2, "Position");
        LinearLayout grid3 = row(col);
        statActions = tile(grid3, "Anti-AFK actions");
        statNext = tile(grid3, "Next action");

        LinearLayout rc = card(col);
        LinearLayout rh = row(rc);
        rh.addView(text("Radar", 15, TEXT, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView open = text("Open ›", 13, ACCENT, true);
        rh.addView(open);
        miniRadar = new RadarView(this);
        miniRadar.setCompact(true);
        miniRadar.setRange(48);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        rc.addView(miniRadar, lp);
        View.OnClickListener toRadar = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectTab(1);
            }
        };
        rc.setOnClickListener(toRadar);
        miniRadar.setOnClickListener(toRadar);

        LinearLayout ac = card(col);
        ac.addView(text("Recent activity", 15, TEXT, true));
        recent = text("", 12, MUTED, false);
        recent.setTypeface(Typeface.MONOSPACE);
        recent.setPadding(0, dp(6), 0, 0);
        ac.addView(recent);
        return scroll(col);
    }

    private TextView tile(LinearLayout parent, String name) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setPadding(dp(14), dp(12), dp(14), dp(12));
        t.setBackground(round(SURFACE, dp(14), STROKE));
        t.addView(text(name, 12, MUTED, false));
        TextView v = text("-", 17, TEXT, true);
        v.setSingleLine(true);
        t.addView(v);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        lp.setMargins(dp(4), 0, dp(4), 0);
        parent.addView(t, lp);
        return v;
    }

    // ---------------------------------------------------------------- Radar

    private View buildRadar() {
        LinearLayout col = page();
        LinearLayout rc = card(col);
        LinearLayout head = row(rc);
        head.addView(text("Radar", 17, TEXT, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        radarRange = text("", 13, MUTED, false);
        head.addView(radarRange);
        rc.addView(text("North is up. Pinch to zoom, tap to change range.", 12, MUTED, false));
        radar = new RadarView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        rc.addView(radar, lp);
        radar.onRangeChanged = new Runnable() {
            @Override
            public void run() {
                radarRange.setText(String.format(Locale.US, "%.0f block radius", radar.getRange()));
            }
        };
        radar.setRange(32);

        LinearLayout chips = chipRow(rc);
        legendChip(chips, "Players", RadarView.C_PLAYER, 0);
        legendChip(chips, "Hostile", RadarView.C_HOSTILE, 1);
        legendChip(chips, "Animals", RadarView.C_ANIMAL, 2);
        legendChip(chips, "Items", RadarView.C_ITEM, 3);
        legendChip(chips, "Other", RadarView.C_OTHER, 4);

        LinearLayout nc = card(col);
        nc.addView(text("Nearby", 15, TEXT, true));
        nearbyList = text("", 13, TEXT, false);
        nearbyList.setTypeface(Typeface.MONOSPACE);
        nearbyList.setPadding(0, dp(6), 0, 0);
        nc.addView(nearbyList);
        return scroll(col);
    }

    private void legendChip(LinearLayout parent, String name, final int color, final int which) {
        final TextView chip = text("● " + name, 12, TEXT, false);
        chip.setPadding(dp(10), dp(6), dp(10), dp(6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, dp(6), 0);
        parent.addView(chip, lp);
        final boolean[] on = {which != 4};
        styleChip(chip, color, on[0]);
        chip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                on[0] = !on[0];
                styleChip(chip, color, on[0]);
                if (which == 0) radar.showPlayers = on[0];
                if (which == 1) radar.showHostile = on[0];
                if (which == 2) radar.showAnimals = on[0];
                if (which == 3) radar.showItems = on[0];
                if (which == 4) radar.showOther = on[0];
                radar.invalidate();
                refreshNearby();
            }
        });
    }

    private void styleChip(TextView chip, int color, boolean on) {
        chip.setBackground(round(on ? (color & 0x00FFFFFF) | 0x26000000 : SURFACE2, dp(20), on ? color : STROKE));
        chip.setTextColor(on ? TEXT : MUTED);
    }

    private void refreshNearby() {
        if (!World.inWorld) {
            nearbyList.setText("Not in a world. Start AFK to see what's around you.");
            return;
        }
        List<World.Dot> dots = World.snapshot(cfg.friendSet());
        StringBuilder sb = new StringBuilder();
        int shown = 0, players = 0, hostile = 0, animals = 0, items = 0;
        for (World.Dot d : dots) {
            if (d.kind == 'p') players++;
            else if (d.kind == 'h') hostile++;
            else if (d.kind == 'a') animals++;
            else if (d.kind == 'i') items++;
        }
        sb.append(String.format(Locale.US, "%d players  %d hostile  %d animals  %d items\n\n", players, hostile, animals, items));
        // players first, then everything else by distance
        for (int pass = 0; pass < 2 && shown < 40; pass++) {
            for (World.Dot d : dots) {
                if ((pass == 0) != (d.kind == 'p') || !radar.visible(d)) continue;
                String arrow = Math.abs(d.dy) > 3 ? (d.dy > 0 ? " ▲" : " ▼") : "";
                sb.append(String.format(Locale.US, "%-18s %5.0fm  %s%s\n", trim(d.label + (d.friend ? " ★" : ""), 18), d.dist, dir(d), arrow));
                if (++shown >= 40) break;
            }
        }
        if (shown == 0) sb.append("Nothing in sight.");
        nearbyList.setText(sb.toString());
    }

    private static String dir(World.Dot d) {
        double a = Math.toDegrees(Math.atan2(d.dx, -d.dz)); // 0 = north, clockwise
        String[] n = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        return n[(int) Math.round(((a % 360) + 360) % 360 / 45) % 8];
    }

    private static String trim(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }

    // ---------------------------------------------------------------- Modules

    private View buildModules() {
        LinearLayout col = page();
        col.addView(text("Modules", 22, TEXT, true));
        col.addView(text("Changes apply right away, even while connected.", 13, MUTED, false));

        LinearLayout afk = module(col, "Anti-AFK", "Does something every few seconds so the server doesn't kick you for idling.", cfg.antiAfk, new Toggle() {
            public void set(boolean v) {
                cfg.antiAfk = v;
            }
        });
        afk.addView(label("Every (seconds)"));
        afk.addView(numberField(String.valueOf(cfg.afkSeconds), new IntSetter() {
            public void set(int v) {
                cfg.afkSeconds = Math.max(2, v);
            }
        }));
        LinearLayout opts = chipRow(afk);
        optionChip(opts, "Jump", cfg.afkJump, new Toggle() {
            public void set(boolean v) {
                cfg.afkJump = v;
            }
        });
        optionChip(opts, "Spin", cfg.afkSpin, new Toggle() {
            public void set(boolean v) {
                cfg.afkSpin = v;
            }
        });
        optionChip(opts, "Swing", cfg.afkSwing, new Toggle() {
            public void set(boolean v) {
                cfg.afkSwing = v;
            }
        });
        optionChip(opts, "Sneak", cfg.afkSneak, new Toggle() {
            public void set(boolean v) {
                cfg.afkSneak = v;
            }
        });
        LinearLayout opts2 = chipRow(afk);
        optionChip(opts2, "Random timing", cfg.afkRandom, new Toggle() {
            public void set(boolean v) {
                cfg.afkRandom = v;
            }
        });

        LinearLayout log = module(col, "Auto Log", "Disconnects you before you get hurt. You get an alert with the reason.", cfg.autoLog, new Toggle() {
            public void set(boolean v) {
                cfg.autoLog = v;
            }
        });
        log.addView(label("Log out below (hearts, 0 = off)"));
        log.addView(numberField(String.valueOf(cfg.logHearts), new IntSetter() {
            public void set(int v) {
                cfg.logHearts = Math.max(0, Math.min(10, v));
            }
        }));
        log.addView(label("Log out if a player is within (blocks, 0 = off)"));
        log.addView(numberField(String.valueOf(cfg.logDistance), new IntSetter() {
            public void set(int v) {
                cfg.logDistance = Math.max(0, Math.min(256, v));
            }
        }));
        log.addView(label("Rejoin after (minutes, 0 = stay offline)"));
        log.addView(numberField(String.valueOf(cfg.rejoinMinutes), new IntSetter() {
            public void set(int v) {
                cfg.rejoinMinutes = Math.max(0, Math.min(1440, v));
            }
        }));
        LinearLayout lo = chipRow(log);
        optionChip(lo, "Ignore friends", cfg.logIgnoreFriends, new Toggle() {
            public void set(boolean v) {
                cfg.logIgnoreFriends = v;
            }
        });

        module(col, "Visual Range", "Alerts you when a player comes into view, and logs when they leave.", cfg.visualRange, new Toggle() {
            public void set(boolean v) {
                cfg.visualRange = v;
            }
        });
        module(col, "Auto Respawn", "Respawns right after you die. Off = log out instead.", cfg.autoRespawn, new Toggle() {
            public void set(boolean v) {
                cfg.autoRespawn = v;
            }
        });
        module(col, "Auto Reconnect", "Rejoins after a kick or lost connection, waiting longer each time.", cfg.autoReconnect, new Toggle() {
            public void set(boolean v) {
                cfg.autoReconnect = v;
            }
        });

        LinearLayout fr = card(col);
        fr.addView(text("Friends", 16, TEXT, true));
        fr.addView(text("Names separated by commas. Friends show in blue on the radar, don't trigger alerts, and are ignored by Auto Log.", 12, MUTED, false));
        EditText f = edit("Steve, Alex", InputType.TYPE_CLASS_TEXT);
        f.setText(cfg.friends);
        f.addTextChangedListener(new SimpleWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                cfg.friends = s.toString();
                save();
            }
        });
        fr.addView(f);
        return scroll(col);
    }

    interface Toggle {
        void set(boolean v);
    }

    interface IntSetter {
        void set(int v);
    }

    private LinearLayout module(LinearLayout parent, String name, String desc, boolean on, final Toggle t) {
        final LinearLayout c = card(parent);
        LinearLayout head = row(c);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(text(name, 16, TEXT, true));
        titles.addView(text(desc, 12, MUTED, false));
        head.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        final Switch sw = new Switch(this);
        sw.setChecked(on);
        int[][] states = {{android.R.attr.state_checked}, {}};
        sw.setThumbTintList(new ColorStateList(states, new int[]{Color.WHITE, 0xFFB4BCD0}));
        sw.setTrackTintList(new ColorStateList(states, new int[]{ACCENT, STROKE}));
        head.addView(sw);
        c.setAlpha(1f);
        sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean v) {
                t.set(v);
                save();
                Hub.log(name(b) + (v ? " enabled" : " disabled"));
            }

            private String name(View b) {
                return ((TextView) ((LinearLayout) ((LinearLayout) b.getParent()).getChildAt(0)).getChildAt(0)).getText().toString();
            }
        });
        return c;
    }

    private void optionChip(LinearLayout parent, String name, boolean on, final Toggle t) {
        final TextView chip = text(name, 13, TEXT, false);
        chip.setPadding(dp(12), dp(7), dp(12), dp(7));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, dp(6), 0);
        parent.addView(chip, lp);
        final boolean[] state = {on};
        styleChip(chip, ACCENT, on);
        chip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                state[0] = !state[0];
                styleChip(chip, ACCENT, state[0]);
                t.set(state[0]);
                save();
            }
        });
    }

    private EditText numberField(String value, final IntSetter s) {
        EditText e = edit("", InputType.TYPE_CLASS_NUMBER);
        e.setText(value);
        e.addTextChangedListener(new SimpleWatcher() {
            @Override
            public void afterTextChanged(Editable ed) {
                try {
                    s.set(Integer.parseInt(ed.toString().trim()));
                    save();
                } catch (NumberFormatException ignored) {
                }
            }
        });
        return e;
    }

    abstract static class SimpleWatcher implements TextWatcher {
        @Override
        public void beforeTextChanged(CharSequence s, int a, int b, int c) {
        }

        @Override
        public void onTextChanged(CharSequence s, int a, int b, int c) {
        }
    }

    // ---------------------------------------------------------------- Chat

    private View buildChat() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(14), dp(4), dp(14), dp(10));
        LinearLayout cc = new LinearLayout(this);
        cc.setOrientation(LinearLayout.VERTICAL);
        cc.setPadding(dp(14), dp(12), dp(14), dp(12));
        cc.setBackground(round(SURFACE, dp(16), STROKE));
        cc.addView(text("Chat", 17, TEXT, true));
        chatScroll = new ScrollView(this);
        chatText = text("", 13, TEXT, false);
        chatText.setTextIsSelectable(true);
        chatText.setLineSpacing(dp(2), 1f);
        chatScroll.addView(chatText);
        cc.addView(chatScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        col.addView(cc, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout input = new LinearLayout(this);
        input.setOrientation(LinearLayout.HORIZONTAL);
        input.setGravity(Gravity.CENTER_VERTICAL);
        input.setPadding(0, dp(8), 0, 0);
        chatInput = edit("Message, or /command", InputType.TYPE_CLASS_TEXT);
        chatInput.setImeOptions(EditorInfo.IME_ACTION_SEND);
        chatInput.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int a, KeyEvent e) {
                sendChat();
                return true;
            }
        });
        input.addView(chatInput, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button send = smallButton("Send", ACCENT);
        send.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendChat();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(8);
        input.addView(send, lp);
        col.addView(input);
        col.addView(text("Commands (starting with /) work everywhere. Plain messages may be refused on servers that require signed chat.", 11, MUTED, false));
        return col;
    }

    private void sendChat() {
        String t = chatInput.getText().toString().trim();
        if (t.isEmpty()) return;
        Bot b = Hub.bot;
        if (b == null) {
            toast("Start AFK first");
            return;
        }
        b.sendChat(t);
        chatInput.setText("");
    }

    // ---------------------------------------------------------------- Account / settings

    private View buildAccount() {
        LinearLayout col = page();

        LinearLayout ac = card(col);
        ac.addView(text("Microsoft account", 16, TEXT, true));
        account = text("", 14, TEXT, false);
        account.setPadding(0, dp(4), 0, 0);
        ac.addView(account);
        codeBox = text("", 14, TEXT, false);
        codeBox.setPadding(dp(12), dp(10), dp(12), dp(10));
        codeBox.setBackground(round(SURFACE2, dp(12), ACCENT));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(10);
        ac.addView(codeBox, clp);
        LinearLayout codeRow = row(ac);
        openLink = smallButton("Open microsoft.com/link", ACCENT);
        copyCode = smallButton("Copy code", SURFACE2);
        codeRow.addView(openLink, weight());
        codeRow.addView(copyCode, weight());
        openLink.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String code = Hub.authCode;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.microsoft.com/link" + (code != null ? "?otc=" + code : ""))));
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
                ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("code", code));
                toast("Code copied");
            }
        });
        LinearLayout accRow = row(ac);
        signIn = smallButton("Sign in with Microsoft", ACCENT);
        signOut = smallButton("Sign out", SURFACE2);
        accRow.addView(signIn, weight());
        accRow.addView(signOut, weight());
        signIn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (signInThread != null) cancelSignIn = true;
                else startSignIn();
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

        LinearLayout sv = card(col);
        sv.addView(text("Server", 16, TEXT, true));
        sv.addView(label("Address"));
        EditText addr = edit("constantiam.net", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        addr.setText(cfg.address);
        addr.addTextChangedListener(new SimpleWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                cfg.address = s.toString().trim();
                save();
            }
        });
        sv.addView(addr);
        final EditText offName = edit("Username for offline server", InputType.TYPE_CLASS_TEXT);
        offName.setText(cfg.offlineName);
        offName.addTextChangedListener(new SimpleWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                cfg.offlineName = s.toString().trim();
                save();
            }
        });
        LinearLayout offRow = chipRow(sv);
        optionChip(offRow, "Offline / cracked server", cfg.offline, new Toggle() {
            public void set(boolean v) {
                cfg.offline = v;
                offName.setVisibility(v ? View.VISIBLE : View.GONE);
            }
        });
        offName.setVisibility(cfg.offline ? View.VISIBLE : View.GONE);
        sv.addView(offName);

        LinearLayout bg = card(col);
        bg.addView(text("Background", 16, TEXT, true));
        bg.addView(text("Samsung and other phones may stop apps in the background. Allow AFK Bot to run unrestricted.", 12, MUTED, false));
        battery = smallButton("Allow running in the background", ACCENT);
        battery.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                askBattery();
            }
        });
        bg.addView(battery);

        LinearLayout lc = card(col);
        LinearLayout lr = row(lc);
        lr.addView(text("Full log", 16, TEXT, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button copy = smallButton("Copy log", SURFACE2);
        copy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("log", Hub.logText()));
                toast("Log copied");
            }
        });
        lr.addView(copy);
        logScroll = new ScrollView(this);
        fullLog = text("", 11, MUTED, false);
        fullLog.setTypeface(Typeface.MONOSPACE);
        fullLog.setTextIsSelectable(true);
        logScroll.addView(fullLog);
        lc.addView(logScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(300)));

        String ver = "";
        try {
            ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        TextView about = text("AFK Bot " + ver + "  •  github.com/PlayWitIt/MCafk-bot-android", 11, MUTED, false);
        about.setGravity(Gravity.CENTER);
        about.setPadding(0, dp(12), 0, dp(12));
        col.addView(about);
        return scroll(col);
    }

    // ================================================================== state -> UI

    @Override
    public void onChange() {
        boolean running = Hub.running;
        int sc = Hub.color == Hub.GREEN ? GOOD : Hub.color == Hub.RED ? BAD : Hub.color == Hub.YELLOW ? WARN : MUTED;
        statusDot.setTextColor(sc);
        if (tab == 0) {
            statusText.setText(Hub.status);
            statusText.setTextColor(sc == MUTED ? TEXT : sc);
            String name = Auth.signedInName(this);
            statusSub.setText((cfg.offline ? cfg.offlineName : name != null ? name : "Not signed in") + "  •  " + cfg.address);
            startStop.setText(running ? "Stop" : "Start AFK");
            startStop.setBackground(running ? round(0xFF3A1D24, dp(14), BAD) : gradient(ACCENT, ACCENT2, dp(14)));
            boolean live = running && World.inWorld;
            float hp = Hub.statHealth;
            statHealth.setText(live ? String.format(Locale.US, "%.1f ♥", hp / 2) : "-");
            statHealth.setTextColor(live && hp <= 8 ? BAD : TEXT);
            statNearest.setText(live ? Hub.statNearest : "-");
            statUptime.setText(live ? Hub.statUptime : "-");
            statPos.setText(live ? Hub.statPos : "-");
            statActions.setText(live ? String.valueOf(Hub.statActions) : "-");
            statNext.setText(live ? Hub.statNextAction : "-");
            StringBuilder sb = new StringBuilder();
            for (String l : Hub.recentLog(7)) sb.append(l).append('\n');
            recent.setText(sb.length() == 0 ? "Nothing yet." : sb.toString().trim());
        } else if (tab == 3) {
            int v = Hub.chatVersion;
            if (v != shownChat) {
                shownChat = v;
                String t = Hub.chatText();
                chatText.setText(t.isEmpty() ? "Chat from the server shows up here." : t);
                chatScroll.post(new Runnable() {
                    @Override
                    public void run() {
                        chatScroll.fullScroll(View.FOCUS_DOWN);
                    }
                });
            }
        } else if (tab == 4) {
            updateAccount();
        }
    }

    private void updateAccount() {
        String name = Auth.signedInName(this);
        boolean signing = signInThread != null;
        String err = Hub.authError;
        if (name != null) {
            account.setText("Signed in as " + name);
            account.setTextColor(GOOD);
        } else if (signing) {
            account.setText(Hub.authCode == null ? "Finishing sign-in..." : "Waiting for you to sign in...");
            account.setTextColor(TEXT);
        } else if (err != null) {
            account.setText("Sign-in failed: " + err);
            account.setTextColor(BAD);
        } else {
            account.setText("Not signed in");
            account.setTextColor(MUTED);
        }
        signIn.setText(signing ? "Cancel" : name != null ? "Switch account" : "Sign in with Microsoft");
        signOut.setVisibility(name != null && !signing ? View.VISIBLE : View.GONE);
        String code = Hub.authCode;
        boolean showCode = signing && code != null;
        codeBox.setVisibility(showCode ? View.VISIBLE : View.GONE);
        openLink.setVisibility(showCode ? View.VISIBLE : View.GONE);
        copyCode.setVisibility(showCode ? View.VISIBLE : View.GONE);
        if (showCode) {
            codeBox.setText("Your code:  " + code + "\n\n1. Tap \"Open microsoft.com/link\" and enter the code\n2. Sign in with the account that owns Minecraft\n3. Come back to this app - it finishes by itself");
        }
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        battery.setVisibility(pm.isIgnoringBatteryOptimizations(getPackageName()) ? View.GONE : View.VISIBLE);
        String l = Hub.logText();
        if (!l.equals(lastLog)) {
            lastLog = l;
            fullLog.setText(l);
            logScroll.post(new Runnable() {
                @Override
                public void run() {
                    logScroll.fullScroll(View.FOCUS_DOWN);
                }
            });
        }
    }

    // ================================================================== actions

    private void toggle() {
        if (Hub.running) {
            startService(new Intent(this, AfkService.class).setAction(AfkService.ACTION_STOP));
            return;
        }
        save();
        if (cfg.address.trim().isEmpty()) {
            toast("Enter the server address in Account first");
            selectTab(4);
            return;
        }
        if (!cfg.offline && Auth.signedInName(this) == null) {
            toast("Sign in with Microsoft first");
            selectTab(4);
            return;
        }
        Hub.running = true;
        Hub.status("Starting...", Hub.YELLOW);
        startForegroundService(new Intent(this, AfkService.class).setAction(AfkService.ACTION_START));
    }

    private void startSignIn() {
        cancelSignIn = false;
        Hub.authError = null;
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
                    String m = e.getMessage() != null ? e.getMessage() : e.toString();
                    Hub.authError = m;
                    Hub.log("Sign-in failed: " + m);
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

    // ================================================================== view helpers

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private LinearLayout page() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(14), dp(4), dp(14), dp(20));
        return col;
    }

    private ScrollView scroll(View content) {
        ScrollView s = new ScrollView(this);
        s.setFillViewport(true);
        s.addView(content);
        return s;
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        t.setPadding(0, dp(1), 0, dp(1));
        return t;
    }

    private TextView label(String s) {
        TextView t = text(s, 12, MUTED, false);
        t.setPadding(0, dp(10), 0, dp(2));
        return t;
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(14), dp(16), dp(16));
        c.setBackground(round(SURFACE, dp(16), STROKE));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        parent.addView(c, lp);
        return c;
    }

    private LinearLayout row(LinearLayout parent) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        parent.addView(r, lp);
        return r;
    }

    /** A horizontally scrolling row for chips, so they never overflow narrow screens. */
    private LinearLayout chipRow(LinearLayout parent) {
        android.widget.HorizontalScrollView h = new android.widget.HorizontalScrollView(this);
        h.setHorizontalScrollBarEnabled(false);
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        h.addView(r);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        parent.addView(h, lp);
        return r;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(0, 0, dp(6), 0);
        return lp;
    }

    static GradientDrawable round(int fill, int radius, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(radius);
        g.setStroke(2, stroke);
        return g;
    }

    static GradientDrawable gradient(int a, int b, int radius) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{a, b});
        g.setCornerRadius(radius);
        return g;
    }

    private Button bigButton(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(16);
        b.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        b.setStateListAnimator(null);
        b.setBackground(gradient(ACCENT, ACCENT2, dp(14)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        lp.topMargin = dp(14);
        b.setLayoutParams(lp);
        return b;
    }

    private Button smallButton(String s, int color) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(13);
        b.setStateListAnimator(null);
        b.setPadding(dp(14), dp(8), dp(14), dp(8));
        b.setBackground(round(color, dp(12), color == SURFACE2 ? STROKE : color));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private EditText edit(String hint, int type) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setInputType(type);
        e.setSingleLine(true);
        e.setTextColor(TEXT);
        e.setHintTextColor(0xFF5B6478);
        e.setTextSize(15);
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        e.setBackground(round(SURFACE2, dp(12), STROKE));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        e.setLayoutParams(lp);
        return e;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
