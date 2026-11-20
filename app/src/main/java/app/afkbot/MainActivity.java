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
import android.view.MotionEvent;
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

    private static final String[] TABS = {"Home", "Map", "3D", "Modules", "Chat", "Account"};
    private static final String[] TAB_ICONS = {"\u25C9", "\u25CE", "\u25A3", "\u2630", "\u2709", "\u2687"};
    static final int T_HOME = 0, T_MAP = 1, T_3D = 2, T_MODULES = 3, T_CHAT = 4, T_ACCOUNT = 5;

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
    private TextView radarRange, nearbyList, mapInfo, viewLabel;
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
            if (tab == T_3D) {
                if (view3d != null) view3d.tick();
                if (view3d == null && cfg.view3d && World.inWorld) update3dLifecycle();
                info3d.setVisibility(view3d == null || !World.inWorld ? View.VISIBLE : View.GONE);
                if (Inv.version != shownInvVersion) refreshContainer();
                if (Inv.version != shownHotbar || (hotbar != null && hotbar.getChildCount() == 0 && World.inWorld)) refreshHotbar();
                if (target != null || targetEntity != null) updateTarget();
                if (TextureStore.busy) texChip.setText("Textures: " + TextureStore.status);
                else if (texChip != null && System.currentTimeMillis() % 2000 < 500) updateTexChip();
                Bot b = Hub.bot;
                if (drive && b != null && !b.manual) setDrive(true);
            }
            if (tab == T_MAP) {
                radar.invalidate();
                refreshNearby();
                String st = MapData.status;
                int n = MapData.chunkCount();
                mapInfo.setText(!cfg.minimap ? "Terrain hidden - showing entities only." : !World.inWorld ? "Start AFK to load the map." :
                        !MapData.supported ? "Terrain isn't available for this server version - showing entities only." :
                                n + " chunks  \u2022  drag to move, pinch to zoom, double-tap to re-centre" + (st.isEmpty() ? "" : "  \u2022  " + st));
                updateViewLabel();
            } else if (tab == T_HOME) {
                miniRadar.invalidate();
                if (System.currentTimeMillis() - lastStorageScan > 2000) {
                    refreshStorage();
                    refreshUsable();
                }
            }
            syncContainerDialog();
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
        ModStore.init(this);
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
        update3dLifecycle();
        ui.post(radarTick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        ui.removeCallbacks(radarTick);
        Hub.removeListener(this);
        update3dLifecycle();
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
        pageViews[2] = build3d();
        pageViews[3] = buildModules();
        pageViews[4] = buildChat();
        pageViews[5] = buildAccount();
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
        final int navPad = dp(8);
        final LinearLayout navRef = nav;
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public android.view.WindowInsets onApplyWindowInsets(View v, android.view.WindowInsets in) {
                int top, bottom, ime, left, right;
                if (Build.VERSION.SDK_INT >= 30) {
                    android.graphics.Insets bars = in.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
                    android.graphics.Insets kb = in.getInsets(android.view.WindowInsets.Type.ime());
                    top = bars.top;
                    bottom = bars.bottom;
                    left = bars.left;
                    right = bars.right;
                    ime = kb.bottom;
                } else {
                    top = in.getSystemWindowInsetTop();
                    bottom = in.getSystemWindowInsetBottom();
                    left = in.getSystemWindowInsetLeft();
                    right = in.getSystemWindowInsetRight();
                    ime = 0;
                }
                boolean keyboard = ime > bottom;
                // keyboard open: push everything up above it; otherwise extend the tab bar under the system buttons
                v.setPadding(left, top, right, keyboard ? ime : 0);
                navRef.setPadding(dp(4), dp(6), dp(4), navPad + (keyboard ? 0 : bottom));
                navRef.setVisibility(keyboard ? View.GONE : View.VISIBLE);
                return in;
            }
        });
        setContentView(root);
        root.requestApplyInsets();
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
        update3dLifecycle();
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

        buildStorageCard(col);
        buildUsableCard(col);

        LinearLayout rc = card(col);
        LinearLayout rh = row(rc);
        rh.addView(text("Map", 15, TEXT, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
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
                selectTab(T_MAP);
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

    private TextView layerLabel;
    private android.widget.SeekBar layerSeek;
    private TextView surfaceChip, layerChip;

    private View buildRadar() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(12), dp(4), dp(12), dp(8));

        // the map fills the top of the page, with zoom / re-centre buttons on top of it
        FrameLayout frame = new FrameLayout(this);
        radar = new RadarView(this);
        frame.addView(radar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.VERTICAL);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
        blp.setMargins(0, 0, dp(10), dp(10));
        frame.addView(btns, blp);
        mapButton(btns, "+", new Runnable() {
            public void run() {
                radar.zoom(0.7f);
            }
        });
        mapButton(btns, "−", new Runnable() {
            public void run() {
                radar.zoom(1.43f);
            }
        });
        mapButton(btns, "⌖", new Runnable() {
            public void run() {
                radar.recenter();
            }
        });
        radarRange = text("", 1, MUTED, false); // not shown; the map draws its own scale
        radar.setRange(32);
        col.addView(frame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.5f));

        // controls below the map (scroll on their own)
        LinearLayout ctl = new LinearLayout(this);
        ctl.setOrientation(LinearLayout.VERTICAL);
        ScrollView cs = new ScrollView(this);
        cs.addView(ctl);
        col.addView(cs, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout chips = chipRow(ctl);
        legendChip(chips, "Players", RadarView.C_PLAYER, 0);
        legendChip(chips, "Hostile", RadarView.C_HOSTILE, 1);
        legendChip(chips, "Animals", RadarView.C_ANIMAL, 2);
        legendChip(chips, "Items", RadarView.C_ITEM, 3);
        legendChip(chips, "Other", RadarView.C_OTHER, 4);
        mapInfo = text("", 12, MUTED, false);
        mapInfo.setPadding(dp(2), dp(6), 0, 0);
        ctl.addView(mapInfo);

        // view height: surface, or a chosen Y layer
        LinearLayout lc = card(ctl);
        LinearLayout lh = chipRow(lc);
        surfaceChip = text("Surface", 13, TEXT, false);
        layerChip = text("Layer (Y level)", 13, TEXT, false);
        for (TextView t : new TextView[]{surfaceChip, layerChip}) {
            t.setPadding(dp(12), dp(7), dp(12), dp(7));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.setMargins(0, 0, dp(6), 0);
            lh.addView(t, clp);
        }
        final TextView myY = text("My Y", 13, TEXT, false);
        myY.setPadding(dp(12), dp(7), dp(12), dp(7));
        myY.setBackground(round(SURFACE2, dp(20), STROKE));
        lh.addView(myY);
        layerLabel = text("", 13, MUTED, false);
        lc.addView(layerLabel);
        layerSeek = new android.widget.SeekBar(this);
        layerSeek.setMax(384); // Y -64 .. 320
        layerSeek.setProgress(64 + 64);
        layerSeek.setProgressTintList(ColorStateList.valueOf(ACCENT));
        layerSeek.setThumbTintList(ColorStateList.valueOf(ACCENT2));
        lc.addView(layerSeek);
        surfaceChip.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                MapData.layerY = Integer.MIN_VALUE;
                updateLayerUi();
            }
        });
        layerChip.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                MapData.layerY = layerSeek.getProgress() - 64;
                updateLayerUi();
            }
        });
        myY.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                int y = (int) Math.floor(World.selfY);
                layerSeek.setProgress(Math.max(0, Math.min(384, y + 64)));
                MapData.layerY = y;
                updateLayerUi();
            }
        });
        layerSeek.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(android.widget.SeekBar sb, int v, boolean user) {
                if (user) {
                    MapData.layerY = v - 64;
                    updateLayerUi();
                }
            }

            public void onStartTrackingTouch(android.widget.SeekBar sb) {
            }

            public void onStopTrackingTouch(android.widget.SeekBar sb) {
            }
        });
        updateLayerUi();

        // render distance + terrain
        LinearLayout vc = card(ctl);
        vc.addView(text("Render distance", 15, TEXT, true));
        viewLabel = text("", 13, MUTED, false);
        vc.addView(viewLabel);
        final android.widget.SeekBar seek = new android.widget.SeekBar(this);
        seek.setMax(14); // 2..16 chunks
        seek.setProgress(Math.max(0, Math.min(14, cfg.viewDistance - 2)));
        seek.setProgressTintList(ColorStateList.valueOf(ACCENT));
        seek.setThumbTintList(ColorStateList.valueOf(ACCENT2));
        vc.addView(seek);
        seek.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(android.widget.SeekBar sb, int v, boolean user) {
                cfg.viewDistance = v + 2;
                updateViewLabel();
            }

            public void onStartTrackingTouch(android.widget.SeekBar sb) {
            }

            public void onStopTrackingTouch(android.widget.SeekBar sb) {
                save();
                Bot b = Hub.bot;
                if (b != null) b.applySettings();
            }
        });
        vc.addView(text("Chunks around you the server sends. Higher shows more but uses more data and battery. The server can lower it.", 12, MUTED, false));
        LinearLayout tr = chipRow(vc);
        optionChip(tr, "Show terrain", cfg.minimap, new Toggle() {
            public void set(boolean v) {
                cfg.minimap = v;
                if (v) {
                    Bot b = Hub.bot;
                    if (b != null) b.reloadTerrain();
                } else {
                    Hub.log("Map terrain hidden (stored terrain is kept)");
                }
            }
        });
        updateViewLabel();

        LinearLayout nc = card(ctl);
        nc.addView(text("Nearby", 15, TEXT, true));
        nearbyList = text("", 12, TEXT, false);
        nearbyList.setTypeface(Typeface.MONOSPACE);
        nearbyList.setPadding(0, dp(6), 0, 0);
        nc.addView(nearbyList);
        return col;
    }

    private void mapButton(LinearLayout parent, String label, final Runnable r) {
        TextView b = text(label, 20, TEXT, true);
        b.setGravity(Gravity.CENTER);
        b.setBackground(round(0xCC141821, dp(12), STROKE));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(44), dp(44));
        lp.topMargin = dp(8);
        parent.addView(b, lp);
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                r.run();
            }
        });
    }

    private void updateLayerUi() {
        boolean layer = MapData.layerY != Integer.MIN_VALUE;
        styleChip(surfaceChip, ACCENT, !layer);
        styleChip(layerChip, ACCENT, layer);
        layerSeek.setAlpha(layer ? 1f : 0.4f);
        layerLabel.setText(layer ? "Showing the first solid block at or below Y " + MapData.layerY + " (caves, tunnels, bases)"
                : "Showing the top block of every column. Pick Layer to look underground.");
        if (radar != null) radar.invalidate();
    }

    // ---------------------------------------------------------------- 3D

    private FrameLayout holder3d;
    private World3DView view3d;
    private TextView info3d, targetText;
    private Button openBtn;
    private LinearLayout targetBar, driveControls, containerPanel, containerList;
    private TextView containerTitle;
    private OverlayView overlay;
    private int radius3d = 16;
    private boolean firstPerson3d = true, drive;
    private int shownInvVersion = -1;
    private volatile int[] target;
    private volatile World.Dot targetEntity;
    private Button mineBtn;
    private LinearLayout hotbar;
    private int shownHotbar = -1;
    private TextView texChip;
    private boolean texturesOffByUser;

    private View build3d() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(12), dp(4), dp(12), dp(8));
        LinearLayout c = module(col, "3D view", "See the real world around you. Tap a block to select it, drive to move, open chests.", cfg.view3d, new Toggle() {
            public void set(boolean v) {
                cfg.view3d = v;
                if (v) {
                    Bot b = Hub.bot;
                    if (b != null && !cfg.minimap) b.reloadTerrain();
                } else {
                    setDrive(false);
                }
                update3dLifecycle();
            }
        });
        LinearLayout modes = chipRow(c);
        final TextView fp = text("First person", 13, TEXT, false), orbit = text("Orbit", 13, TEXT, false);
        for (TextView t : new TextView[]{fp, orbit}) {
            t.setPadding(dp(12), dp(7), dp(12), dp(7));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, dp(6), 0);
            modes.addView(t, lp);
        }
        styleChip(fp, ACCENT, firstPerson3d);
        styleChip(orbit, ACCENT, !firstPerson3d);
        fp.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                firstPerson3d = true;
                styleChip(fp, ACCENT, true);
                styleChip(orbit, ACCENT, false);
                if (view3d != null) view3d.firstPerson = true;
            }
        });
        orbit.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                firstPerson3d = false;
                styleChip(fp, ACCENT, false);
                styleChip(orbit, ACCENT, true);
                if (view3d != null) view3d.firstPerson = false;
            }
        });
        final TextView[] rchips = new TextView[3];
        final int[] radii = {12, 16, 24};
        for (int i = 0; i < 3; i++) {
            final int r = radii[i];
            final TextView t = text(r + "m", 13, TEXT, false);
            t.setPadding(dp(12), dp(7), dp(12), dp(7));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, dp(6), 0);
            modes.addView(t, lp);
            rchips[i] = t;
            styleChip(t, ACCENT2, r == radius3d);
            t.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    for (int k = 0; k < 3; k++) styleChip(rchips[k], ACCENT2, radii[k] == r);
                    radius3d = r;
                    if (view3d != null) view3d.radius = r;
                }
            });
        }
        LinearLayout acts = chipRow(c);
        final TextView driveChip = text("✥ Drive", 13, TEXT, true);
        driveChip.setPadding(dp(14), dp(8), dp(14), dp(8));
        acts.addView(driveChip);
        styleChip(driveChip, GOOD, false);
        driveChip.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                setDrive(!drive);
                styleChip(driveChip, GOOD, drive);
            }
        });
        TextView sp = text(" ", 13, TEXT, false);
        acts.addView(sp);
        actionChip(acts, "Inventory", new Runnable() {
            public void run() {
                showInventory();
            }
        });
        texChip = text("Textures", 13, TEXT, true);
        texChip.setPadding(dp(14), dp(8), dp(14), dp(8));
        LinearLayout.LayoutParams tcl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tcl.setMargins(dp(8), 0, 0, 0);
        acts.addView(texChip, tcl);
        texChip.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                texturesClicked();
            }
        });
        updateTexChip();

        holder3d = new FrameLayout(this);
        holder3d.setBackground(round(SURFACE, dp(16), STROKE));
        holder3d.setClipToOutline(true);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        hl.topMargin = dp(10);
        col.addView(holder3d, hl);

        overlay = new OverlayView(this);
        holder3d.addView(overlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        info3d = text("", 13, MUTED, false);
        info3d.setGravity(Gravity.CENTER);
        info3d.setPadding(dp(20), dp(20), dp(20), dp(20));
        holder3d.addView(info3d, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // selected block bar (top)
        targetBar = new LinearLayout(this);
        targetBar.setOrientation(LinearLayout.HORIZONTAL);
        targetBar.setGravity(Gravity.CENTER_VERTICAL);
        targetBar.setPadding(dp(12), dp(6), dp(6), dp(6));
        targetBar.setBackground(round(0xDD0B0D12, dp(12), STROKE));
        targetText = text("", 13, TEXT, false);
        targetBar.addView(targetText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        mineBtn = smallButton("\u26CF Mine", 0xFFB45309);
        LinearLayout.LayoutParams mb = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38));
        mb.rightMargin = dp(6);
        mineBtn.setLayoutParams(mb);
        targetBar.addView(mineBtn);
        mineBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                leftClick();
            }
        });
        openBtn = smallButton("\u270B Use", ACCENT);
        LinearLayout.LayoutParams ob = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38));
        openBtn.setLayoutParams(ob);
        targetBar.addView(openBtn);
        openBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                rightClick();
            }
        });
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        tl.setMargins(dp(8), dp(8), dp(8), 0);
        holder3d.addView(targetBar, tl);
        targetBar.setVisibility(View.GONE);

        // drive controls (bottom)
        driveControls = new LinearLayout(this);
        driveControls.setOrientation(LinearLayout.HORIZONTAL);
        driveControls.setGravity(Gravity.BOTTOM);
        JoystickView stick = new JoystickView(this);
        driveControls.addView(stick, new LinearLayout.LayoutParams(dp(130), dp(130)));
        View spacer = new View(this);
        driveControls.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1));
        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.VERTICAL);
        btns.addView(holdButton("Jump", new Toggle() {
            public void set(boolean v) {
                Bot b = Hub.bot;
                if (b != null) b.inJump = v;
            }
        }, false));
        btns.addView(holdButton("Sneak", new Toggle() {
            public void set(boolean v) {
                Bot b = Hub.bot;
                if (b != null) b.inSneak = v;
            }
        }, true));
        btns.addView(holdButton("Sprint", new Toggle() {
            public void set(boolean v) {
                Bot b = Hub.bot;
                if (b != null) b.inSprint = v;
            }
        }, true));
        driveControls.addView(btns);
        FrameLayout.LayoutParams dl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        dl.setMargins(dp(10), 0, dp(10), dp(62));
        hotbar = new LinearLayout(this);
        hotbar.setOrientation(LinearLayout.HORIZONTAL);
        hotbar.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams hbl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50), Gravity.BOTTOM);
        hbl.setMargins(dp(6), 0, dp(6), dp(6));
        holder3d.addView(hotbar, hbl);
        holder3d.addView(driveControls, dl);
        driveControls.setVisibility(View.GONE);

        // container panel (bottom sheet)
        containerPanel = new LinearLayout(this);
        containerPanel.setOrientation(LinearLayout.VERTICAL);
        containerPanel.setPadding(dp(12), dp(10), dp(12), dp(10));
        containerPanel.setBackground(round(0xF2141821, dp(16), ACCENT));
        LinearLayout ch = row(containerPanel);
        containerTitle = text("Container", 15, TEXT, true);
        ch.addView(containerTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button takeAll = smallButton("Take all", ACCENT);
        takeAll.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)));
        ch.addView(takeAll);
        Button closeC = smallButton("Close", SURFACE2);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36));
        cl.leftMargin = dp(6);
        closeC.setLayoutParams(cl);
        ch.addView(closeC);
        takeAll.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                takeAll();
            }
        });
        closeC.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Bot b = Hub.bot;
                if (b != null) b.closeContainer();
            }
        });
        ScrollView cs = new ScrollView(this);
        containerList = new LinearLayout(this);
        containerList.setOrientation(LinearLayout.VERTICAL);
        cs.addView(containerList);
        containerPanel.addView(cs, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220)));
        FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        pl.setMargins(dp(8), 0, dp(8), dp(8));
        holder3d.addView(containerPanel, pl);
        containerPanel.setVisibility(View.GONE);
        return col;
    }

    private View holdButton(String label, final Toggle t, final boolean toggle) {
        final TextView b = text(label, 13, TEXT, true);
        b.setGravity(Gravity.CENTER);
        b.setBackground(round(0xCC141821, dp(14), STROKE));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(76), dp(44));
        lp.topMargin = dp(8);
        b.setLayoutParams(lp);
        final boolean[] on = {false};
        b.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, MotionEvent e) {
                if (toggle) {
                    if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                        on[0] = !on[0];
                        t.set(on[0]);
                        b.setBackground(round(on[0] ? (ACCENT & 0x00FFFFFF) | 0xCC000000 : 0xCC141821, dp(14), on[0] ? ACCENT : STROKE));
                    }
                } else {
                    if (e.getActionMasked() == MotionEvent.ACTION_DOWN) t.set(true);
                    if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL) t.set(false);
                    b.setBackground(round(e.getActionMasked() == MotionEvent.ACTION_DOWN ? (ACCENT & 0x00FFFFFF) | 0xCC000000 : 0xCC141821, dp(14), STROKE));
                }
                return true;
            }
        });
        return b;
    }

    private void setDrive(boolean on) {
        drive = on;
        Bot b = Hub.bot;
        if (b != null) {
            if (on) {
                b.lookYaw = World.selfYaw;
                if (view3d != null) {
                    view3d.fpYaw = b.lookYaw;
                    view3d.fpPitch = b.lookPitch;
                }
            } else {
                b.inForward = b.inStrafe = 0;
                b.inJump = false;
            }
            b.manual = on;
        }
        if (on && !World.inWorld) toast("Start AFK first");
        if (driveControls != null) driveControls.setVisibility(on && view3d != null ? View.VISIBLE : View.GONE);
        if (view3d != null) view3d.setContinuous(on);
        if (on) Hub.log("Drive mode on - anti-AFK pauses while you control the player");
    }

    private void takeAll() {
        Bot b = Hub.bot;
        if (b == null) return;
        List<Inv.Item> s = Inv.slots;
        int n = Inv.containerSize(), delay = 0;
        for (int i = 0; i < n && i < s.size(); i++) {
            if (s.get(i) == null) continue;
            final int slot = i;
            ui.postDelayed(new Runnable() {
                public void run() {
                    Bot bb = Hub.bot;
                    if (bb != null && Inv.openId >= 0) bb.clickSlot(slot, 0, 1);
                }
            }, delay);
            delay += 150;
        }
    }

    private void refreshContainer() {
        shownInvVersion = Inv.version;
        boolean open = Inv.openId >= 0 && view3d != null;
        containerPanel.setVisibility(open ? View.VISIBLE : View.GONE);
        if (!open) return;
        containerTitle.setText(Inv.title.isEmpty() ? "Container" : Inv.title);
        containerList.removeAllViews();
        List<Inv.Item> s = Inv.slots;
        int n = Inv.containerSize(), shown = 0;
        for (int i = 0; i < n && i < s.size(); i++) {
            Inv.Item it = s.get(i);
            if (it == null) continue;
            shown++;
            final int slot = i;
            LinearLayout r = row(containerList);
            TextView t = text(it.name + "  ×" + it.count + (it.detail.isEmpty() ? "" : "\n" + it.detail), 13, TEXT, false);
            r.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            Button take = smallButton("Take", SURFACE2);
            take.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)));
            r.addView(take);
            take.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    Bot b = Hub.bot;
                    if (b != null) b.clickSlot(slot, 0, 1);
                }
            });
        }
        if (shown == 0) containerList.addView(text(s.isEmpty() ? "Loading..." : "Empty.", 13, MUTED, false));
    }

    private void showInventory() {
        List<Inv.Item> inv = Inv.inventory;
        StringBuilder sb = new StringBuilder();
        String[] area = new String[46];
        for (int i = 0; i < 46; i++) area[i] = i < 5 ? "Crafting" : i < 9 ? "Armor" : i < 36 ? "Backpack" : i < 45 ? "Hotbar" : "Off-hand";
        String last = "";
        for (int i = 0; i < inv.size() && i < 46; i++) {
            Inv.Item it = inv.get(i);
            if (it == null) continue;
            if (!area[i].equals(last)) {
                sb.append(sb.length() > 0 ? "\n" : "").append(area[i]).append('\n');
                last = area[i];
            }
            sb.append("  ").append(it.name).append(" ×").append(it.count).append(it.detail.isEmpty() ? "" : "  (" + it.detail + ")").append('\n');
        }
        showText("Inventory", sb.length() == 0 ? (World.inWorld ? "Your inventory is empty (or not sent yet)." : "Start AFK first.") : sb.toString());
    }

    /** Creates the GL view only while the 3D tab is visible and switched on; destroys it otherwise. */
    private void update3dLifecycle() {
        if (holder3d == null || cfg == null) return;
        boolean want = cfg.view3d && tab == T_3D && resumed;
        if (want && view3d == null) {
            if (Hub.versionName != null && TextureStore.current == null && TextureStore.have(this, Hub.versionName) && !texturesOffByUser) TextureStore.load(this, Hub.versionName);
            view3d = new World3DView(this);
            view3d.radius = radius3d;
            view3d.firstPerson = firstPerson3d;
            view3d.fpYaw = World.selfYaw;
            Bot b = Hub.bot;
            if (b != null) view3d.fpPitch = b.lookPitch;
            view3d.listener = new World3DView.Listener() {
                public void onSelect(final int[] block) {
                    target = block;
                    targetEntity = null;
                    ui.post(new Runnable() {
                        public void run() {
                            updateTarget();
                        }
                    });
                }

                public void onLabels(List<Object[]> labels) {
                    overlay.setLabels(labels);
                }

                public void onSelectEntity(final World.Dot e) {
                    target = null;
                    targetEntity = e;
                    ui.post(new Runnable() {
                        public void run() {
                            updateTarget();
                        }
                    });
                }

                public void onLook(float yaw, float pitch) {
                    Bot b = Hub.bot;
                    if (b != null) {
                        b.lookYaw = yaw;
                        b.lookPitch = pitch;
                    }
                }
            };
            holder3d.addView(view3d, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            view3d.setContinuous(drive);
            view3d.tick();
        } else if (!want && view3d != null) {
            view3d.onPause();
            holder3d.removeView(view3d);
            view3d = null;
            if (drive && tab != T_3D) setDrive(false);
        }
        overlay.crosshair = view3d != null && firstPerson3d;
        boolean showInfo = view3d == null || !World.inWorld;
        info3d.setText(!cfg.view3d ? "3D view is off. Turn it on above." : !World.inWorld ? "Start AFK to see the world around you." : "");
        info3d.setVisibility(showInfo ? View.VISIBLE : View.GONE);
        driveControls.setVisibility(drive && view3d != null ? View.VISIBLE : View.GONE);
        if (view3d == null) targetBar.setVisibility(View.GONE);
        refreshContainer();
    }

    private void updateTarget() {
        int[] t = target;
        World.Dot e = targetEntity;
        Bot b = Hub.bot;
        if ((t == null && e == null) || view3d == null) {
            targetBar.setVisibility(View.GONE);
            return;
        }
        if (e != null) {
            // refresh its distance from the live entity list
            for (World.Dot d : World.snapshot(cfg.friendSet())) if (d.id == e.id) e = d;
            targetEntity = e;
            String kind = e.kind == 'p' ? "player" : e.kind == 'h' ? "hostile" : e.kind == 'a' ? "animal" : "entity";
            targetText.setText(String.format(Locale.US, "%s (%s)  \u2022  %.1f m%s", e.label, kind, e.dist, e.dist > 3.5 ? "  (too far)" : ""));
            mineBtn.setText("\u2694 Hit");
            mineBtn.setVisibility(b != null ? View.VISIBLE : View.GONE);
            openBtn.setVisibility(b != null ? View.VISIBLE : View.GONE);
            mineBtn.setAlpha(e.dist <= 3.5 ? 1f : 0.45f);
            openBtn.setAlpha(e.dist <= 3.5 ? 1f : 0.45f);
            targetBar.setVisibility(View.VISIBLE);
            return;
        }
        String name = "Block";
        ProtoDef.VersionData vd = Hub.versionData;
        int st = MapData.stateAt(t[0], t[1], t[2]);
        if (vd != null && st > 0) name = vd.blockName(st);
        double r = World3DView.reach(t);
        float prog = Bot.miningProgress;
        String extra = b != null && b.isMining() && prog >= 0 ? String.format(Locale.US, "  \u2022  mining %d%%", Math.round(prog * 100)) : r > 4.5 ? "  (too far)" : "";
        targetText.setText(String.format(Locale.US, "%s  \u2022  %d, %d, %d  \u2022  %.1f m%s", name, t[0], t[1], t[2], r, extra));
        mineBtn.setText(b != null && b.isMining() ? "\u25A0 Stop" : "\u26CF Mine");
        mineBtn.setVisibility(b != null && b.containersSupported() ? View.VISIBLE : View.GONE);
        openBtn.setVisibility(b != null && b.containersSupported() ? View.VISIBLE : View.GONE);
        mineBtn.setAlpha(r <= 4.5 ? 1f : 0.45f);
        openBtn.setAlpha(r <= 4.5 ? 1f : 0.45f);
        targetBar.setVisibility(View.VISIBLE);
    }

    /** Left click: mine the block (tap again to stop) or hit the player/mob. */
    private void leftClick() {
        Bot b = Hub.bot;
        if (b == null) return;
        World.Dot e = targetEntity;
        if (e != null) {
            toast(b.attack(e));
            return;
        }
        int[] t = target;
        if (t == null) return;
        if (b.isMining()) {
            b.stopMining(true);
            toast("Stopped mining");
        } else {
            toast(b.startMining(t));
        }
        updateTarget();
    }

    /** Right click: use/open the block or place the held block against it; use the player/mob; else use the held item. */
    private void rightClick() {
        Bot b = Hub.bot;
        if (b == null) return;
        World.Dot e = targetEntity;
        if (e != null) {
            toast(b.interact(e));
            return;
        }
        int[] t = target;
        if (t == null) {
            b.useItem();
            return;
        }
        double r = World.reach(t);
        if (r > 4.5) {
            toast(String.format(Locale.US, "Too far (%.1f m) - get within 4.5 blocks", r));
            return;
        }
        int st = MapData.stateAt(t[0], t[1], t[2]);
        ProtoDef.VersionData vd = Hub.versionData;
        String name = vd != null && st > 0 ? vd.blockName(st) : "block";
        if (Bot.isContainer(name)) {
            openBlock(t);
            return;
        }
        b.useBlock(t[0], t[1], t[2], t[3]); // on the tapped face: opens doors / presses buttons / places your held block
        Inv.Item held = Inv.heldItem();
        toast(held != null ? "Used " + held.name + " on " + name : "Used " + name);
    }

    private void refreshHotbar() {
        if (hotbar == null) return;
        shownHotbar = Inv.version;
        hotbar.removeAllViews();
        Bot bot = Hub.bot;
        boolean show = view3d != null && bot != null && World.inWorld && bot.containersSupported();
        hotbar.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) return;
        List<Inv.Item> inv = Inv.inventory;
        for (int i = 0; i < 9; i++) {
            final int slot = i;
            Inv.Item it = 36 + i < inv.size() ? inv.get(36 + i) : null;
            String label = it == null ? "" : shortName(it.name) + (it.count > 1 ? "\n" + it.count : "");
            TextView c = text(label, 9, TEXT, true);
            c.setGravity(Gravity.CENTER);
            c.setMaxLines(2);
            boolean sel = Inv.held == i;
            c.setBackground(round(sel ? 0xDD3B2A6B : 0xB30B0D12, dp(8), sel ? ACCENT2 : STROKE));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
            lp.setMargins(dp(2), 0, dp(2), 0);
            hotbar.addView(c, lp);
            c.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    Bot b = Hub.bot;
                    if (b != null) b.selectSlot(slot);
                }
            });
        }
        TextView use = text("\u270B", 18, TEXT, true);
        use.setGravity(Gravity.CENTER);
        use.setBackground(round(0xCC8B5CF6, dp(8), ACCENT));
        LinearLayout.LayoutParams ul = new LinearLayout.LayoutParams(dp(44), ViewGroup.LayoutParams.MATCH_PARENT);
        ul.setMargins(dp(4), 0, 0, 0);
        hotbar.addView(use, ul);
        use.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Bot b = Hub.bot;
                if (b == null) return;
                b.useItem();
                Inv.Item held = Inv.heldItem();
                toast(held != null ? "Used " + held.name : "Nothing in your hand");
            }
        });
    }

    private static String shortName(String n) {
        String[] w = n.split(" ");
        if (w.length == 1) return n.length() > 8 ? n.substring(0, 8) : n;
        String last = w[w.length - 1];
        String first = w[0];
        String s = first.length() > 4 ? first.substring(0, 4) : first;
        return s + " " + (last.length() > 6 ? last.substring(0, 6) : last);
    }

    // ----- textures -----

    private void updateTexChip() {
        if (texChip == null) return;
        String v = Hub.versionName;
        boolean have = v != null && TextureStore.have(this, v);
        boolean on = TextureStore.current != null;
        texChip.setText(TextureStore.busy ? "Textures: downloading..." : on ? "Textures: on" : have ? "Textures: off" : "Get textures");
        styleChip(texChip, ACCENT2, on);
    }

    private void texturesClicked() {
        final String v = Hub.versionName;
        final ProtoDef.VersionData vd = Hub.versionData;
        if (v == null || vd == null) {
            toast("Start AFK first (textures match the server's Minecraft version)");
            return;
        }
        if (TextureStore.busy) {
            toast(TextureStore.status);
            return;
        }
        if (TextureStore.current != null) {
            TextureStore.current = null; // turn off (kept on the phone)
            texturesOffByUser = true;
            updateTexChip();
            return;
        }
        if (TextureStore.have(this, v)) {
            texturesOffByUser = false;
            if (TextureStore.load(this, v) == null) toast(TextureStore.status);
            updateTexChip();
            return;
        }
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Download block textures?")
                .setMessage("Downloads Minecraft " + v + "'s block textures straight from Mojang (about 1.2 MB, only the needed parts of the game file) and keeps them on this phone. Nothing is shared.")
                .setPositiveButton("Download", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        final android.content.Context app = getApplicationContext();
                        new Thread(new Runnable() {
                            public void run() {
                                try {
                                    TextureStore.download(app, v, vd.blockIds);
                                    Hub.log("Textures for " + v + " downloaded");
                                } catch (Exception e) {
                                    TextureStore.status = "Texture download failed: " + e.getMessage();
                                    Hub.log(TextureStore.status);
                                }
                                ui.post(new Runnable() {
                                    public void run() {
                                        toast(TextureStore.status);
                                        updateTexChip();
                                    }
                                });
                            }
                        }, "textures").start();
                        updateTexChip();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Joystick: up = forward, sideways = strafe. */
    private final class JoystickView extends View {
        private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private float kx, ky;

        JoystickView(android.content.Context c) {
            super(c);
        }

        @Override
        protected void onDraw(android.graphics.Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, r = Math.min(cx, cy) - dp(4);
            p.setColor(0x660B0D12);
            c.drawCircle(cx, cy, r, p);
            p.setStyle(android.graphics.Paint.Style.STROKE);
            p.setStrokeWidth(dp(2));
            p.setColor(0x88FFFFFF);
            c.drawCircle(cx, cy, r, p);
            p.setStyle(android.graphics.Paint.Style.FILL);
            p.setColor(ACCENT);
            c.drawCircle(cx + kx * r * 0.6f, cy + ky * r * 0.6f, r * 0.38f, p);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
            float cx = getWidth() / 2f, cy = getHeight() / 2f, r = Math.min(cx, cy) * 0.6f;
            if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                kx = ky = 0;
            } else {
                kx = (e.getX() - cx) / r;
                ky = (e.getY() - cy) / r;
                float len = (float) Math.sqrt(kx * kx + ky * ky);
                if (len > 1) {
                    kx /= len;
                    ky /= len;
                }
            }
            Bot b = Hub.bot;
            if (b != null) {
                b.inForward = Math.abs(ky) < 0.15f ? 0 : -ky;
                b.inStrafe = Math.abs(kx) < 0.15f ? 0 : -kx;
            }
            invalidate();
            return true;
        }
    }

    /** Draws name tags and the crosshair on top of the 3D view. */
    private final class OverlayView extends View {
        private final android.graphics.Paint tp = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint bg = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private volatile List<Object[]> labels = new java.util.ArrayList<Object[]>();
        boolean crosshair;
        private long lastPost;

        OverlayView(android.content.Context c) {
            super(c);
            tp.setTextSize(dp(12));
            tp.setTypeface(Typeface.DEFAULT_BOLD);
            tp.setTextAlign(android.graphics.Paint.Align.CENTER);
            bg.setColor(0x99000000);
        }

        void setLabels(List<Object[]> l) {
            labels = l;
            long now = System.currentTimeMillis();
            if (now - lastPost > 60) {
                lastPost = now;
                postInvalidate();
            }
        }

        @Override
        protected void onDraw(android.graphics.Canvas c) {
            if (view3d == null) return;
            for (Object[] l : labels) {
                float x = (Float) l[0], y = (Float) l[1];
                String s = (String) l[2];
                float w = tp.measureText(s) / 2 + dp(6);
                c.drawRoundRect(x - w, y - dp(15), x + w, y + dp(4), dp(6), dp(6), bg);
                tp.setColor(0xFF000000 | (Integer) l[3]);
                c.drawText(s, x, y, tp);
            }
            if (crosshair) {
                float cx = getWidth() / 2f, cy = getHeight() / 2f;
                tp.setColor(0xCCFFFFFF);
                c.drawRect(cx - dp(8), cy - dp(1), cx + dp(8), cy + dp(1), tp);
                c.drawRect(cx - dp(1), cy - dp(8), cx + dp(1), cy + dp(8), tp);
            }
        }
    }

    private void updateViewLabel() {
        int sv = Hub.serverViewDistance;
        int blocks = cfg.viewDistance * 16;
        viewLabel.setText(cfg.viewDistance + " chunks (about " + blocks + " blocks)" + (sv > 0 ? "  \u2022  server max " + sv : ""));
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
        buildModsSection(col);
        TextView bi = text("Built-in modules", 16, TEXT, true);
        bi.setPadding(dp(2), dp(18), 0, 0);
        col.addView(bi);

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

    // ---------------------------------------------------------------- Mods

    private static final int PICK_MOD = 42;
    private LinearLayout modsList;
    private int shownModsVersion = -1;

    private void buildModsSection(LinearLayout col) {
        LinearLayout c = card(col);
        LinearLayout head = row(c);
        head.addView(text("Mods", 18, TEXT, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView help = text("How to write one", 13, ACCENT, true);
        head.addView(help);
        help.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showText("Writing a mod", MOD_HELP);
            }
        });
        c.addView(text("Small JavaScript add-ons that react to chat, players, health and more. They run sandboxed: no files, no internet, and a broken mod is switched off automatically.", 12, MUTED, false));
        LinearLayout r1 = chipRow(c);
        actionChip(r1, "Examples", new Runnable() {
            public void run() {
                showExamples();
            }
        });
        actionChip(r1, "From file", new Runnable() {
            public void run() {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                try {
                    startActivityForResult(i, PICK_MOD);
                } catch (Exception e) {
                    toast("No file picker available");
                }
            }
        });
        actionChip(r1, "Paste code", new Runnable() {
            public void run() {
                editCode("Paste a mod", "", "Install", new CodeSaver() {
                    public void save(String code) {
                        installMod(code);
                    }
                });
            }
        });
        actionChip(r1, "From link", new Runnable() {
            public void run() {
                askLink();
            }
        });
        modsList = new LinearLayout(this);
        modsList.setOrientation(LinearLayout.VERTICAL);
        col.addView(modsList);
        refreshMods();
    }

    private void actionChip(LinearLayout parent, String name, final Runnable r) {
        TextView t = text(name, 13, TEXT, true);
        t.setPadding(dp(14), dp(8), dp(14), dp(8));
        t.setBackground(round(SURFACE2, dp(20), ACCENT));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, dp(8), 0);
        parent.addView(t, lp);
        t.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                r.run();
            }
        });
    }

    private void refreshMods() {
        if (modsList == null) return;
        shownModsVersion = ModEngine.version;
        modsList.removeAllViews();
        List<ModEngine.Mod> mods = ModEngine.all();
        if (mods.isEmpty()) {
            TextView t = text("No mods yet. Tap Examples to try some.", 13, MUTED, false);
            t.setPadding(dp(4), dp(10), 0, 0);
            modsList.addView(t);
            return;
        }
        for (final ModEngine.Mod m : mods) {
            LinearLayout c = card(modsList);
            LinearLayout head = row(c);
            LinearLayout titles = new LinearLayout(this);
            titles.setOrientation(LinearLayout.VERTICAL);
            titles.addView(text(m.name + (m.version.isEmpty() ? "" : "  v" + m.version), 15, TEXT, true));
            if (!m.description.isEmpty()) titles.addView(text(m.description, 12, MUTED, false));
            boolean on = ModStore.isEnabled(this, m.id);
            String state = !on ? "Off" : m.running ? "Running" : m.lastError != null ? "Stopped: " + m.lastError : "Starting...";
            TextView st = text(state, 12, !on ? MUTED : m.running ? GOOD : BAD, false);
            titles.addView(st);
            head.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            Switch sw = new Switch(this);
            sw.setChecked(on);
            int[][] states = {{android.R.attr.state_checked}, {}};
            sw.setThumbTintList(new ColorStateList(states, new int[]{Color.WHITE, 0xFFB4BCD0}));
            sw.setTrackTintList(new ColorStateList(states, new int[]{ACCENT, STROKE}));
            head.addView(sw);
            sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(android.widget.CompoundButton b, boolean v) {
                    ModStore.setEnabled(MainActivity.this, m.id, v);
                    ui.postDelayed(new Runnable() {
                        public void run() {
                            refreshMods();
                        }
                    }, 400);
                }
            });
            LinearLayout acts = chipRow(c);
            actionChip(acts, "Edit", new Runnable() {
                public void run() {
                    editCode("Edit " + m.name, m.code, "Save", new CodeSaver() {
                        public void save(String code) {
                            try {
                                ModStore.saveCode(MainActivity.this, m.id, code);
                                toast("Saved");
                            } catch (Exception e) {
                                toast("Couldn't save: " + e.getMessage());
                            }
                            refreshModsSoon();
                        }
                    });
                }
            });
            actionChip(acts, "Log", new Runnable() {
                public void run() {
                    String l = m.logText();
                    showText(m.name + " log", l.isEmpty() ? "Nothing logged yet." : l);
                }
            });
            actionChip(acts, "Reset data", new Runnable() {
                public void run() {
                    ModStore.clearData(MainActivity.this, m.id);
                    toast("Saved data cleared");
                }
            });
            actionChip(acts, "Delete", new Runnable() {
                public void run() {
                    new android.app.AlertDialog.Builder(MainActivity.this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                            .setTitle("Delete " + m.name + "?")
                            .setPositiveButton("Delete", new android.content.DialogInterface.OnClickListener() {
                                public void onClick(android.content.DialogInterface d, int w) {
                                    ModStore.delete(MainActivity.this, m.id);
                                    refreshMods();
                                }
                            })
                            .setNegativeButton("Cancel", null)
                            .show();
                }
            });
        }
    }

    private void refreshModsSoon() {
        ui.postDelayed(new Runnable() {
            public void run() {
                refreshMods();
            }
        }, 500);
    }

    private void installMod(String code) {
        try {
            String id = ModStore.install(this, code);
            ModEngine.Mod m = ModEngine.get(id);
            toast("Installed " + (m != null ? m.name : id));
        } catch (Exception e) {
            toast("Couldn't install: " + e.getMessage());
        }
        refreshModsSoon();
    }

    private void showExamples() {
        try {
            final String[] files = getAssets().list("mods");
            if (files == null || files.length == 0) {
                toast("No examples found");
                return;
            }
            final String[] codes = new String[files.length];
            String[] labels = new String[files.length];
            for (int i = 0; i < files.length; i++) {
                java.io.InputStream in = getAssets().open("mods/" + files[i]);
                codes[i] = ModStore.readAll(in);
                in.close();
                labels[i] = ModEngine.meta(codes[i], "name", files[i]) + "\n" + ModEngine.meta(codes[i], "description", "");
            }
            new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Example mods (tap to install)")
                    .setItems(labels, new android.content.DialogInterface.OnClickListener() {
                        public void onClick(android.content.DialogInterface d, int which) {
                            installMod(codes[which]);
                        }
                    })
                    .setNegativeButton("Close", null)
                    .show();
        } catch (Exception e) {
            toast("Couldn't open examples: " + e.getMessage());
        }
    }

    private void askLink() {
        final EditText e = edit("https://raw.githubusercontent.com/.../mod.js", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(dp(20), dp(8), dp(20), 0);
        wrap.addView(e);
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Install a mod from a link")
                .setMessage("Only install mods from people you trust. A GitHub file link works too.")
                .setView(wrap)
                .setPositiveButton("Download", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        final String url = e.getText().toString().trim();
                        if (!url.startsWith("http")) {
                            toast("Enter a link starting with https://");
                            return;
                        }
                        new Thread(new Runnable() {
                            public void run() {
                                try {
                                    final String code = ModStore.download(url);
                                    ui.post(new Runnable() {
                                        public void run() {
                                            editCode("Review before installing", code, "Install", new CodeSaver() {
                                                public void save(String c) {
                                                    installMod(c);
                                                }
                                            });
                                        }
                                    });
                                } catch (final Exception ex) {
                                    ui.post(new Runnable() {
                                        public void run() {
                                            toast("Download failed: " + ex.getMessage());
                                        }
                                    });
                                }
                            }
                        }).start();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != PICK_MOD || res != RESULT_OK || data == null || data.getData() == null) return;
        try {
            java.io.InputStream in = getContentResolver().openInputStream(data.getData());
            String code = ModStore.readAll(in);
            in.close();
            editCode("Review before installing", code, "Install", new CodeSaver() {
                public void save(String c) {
                    installMod(c);
                }
            });
        } catch (Exception e) {
            toast("Couldn't read the file: " + e.getMessage());
        }
    }

    interface CodeSaver {
        void save(String code);
    }

    private void editCode(String title, String code, String okLabel, final CodeSaver saver) {
        final EditText e = new EditText(this);
        e.setText(code);
        e.setTypeface(Typeface.MONOSPACE);
        e.setTextSize(12);
        e.setTextColor(TEXT);
        e.setHint("// @name My Mod\nmod.on('chat', function (e) {\n  bot.log(e.raw);\n});");
        e.setHintTextColor(0xFF5B6478);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        e.setSingleLine(false);
        e.setHorizontallyScrolling(true);
        e.setGravity(Gravity.TOP | Gravity.START);
        e.setMinLines(14);
        e.setBackground(round(SURFACE2, dp(10), STROKE));
        e.setPadding(dp(10), dp(10), dp(10), dp(10));
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(dp(14), dp(8), dp(14), 0);
        wrap.addView(e, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(380)));
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(title)
                .setView(wrap)
                .setPositiveButton(okLabel, new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        saver.save(e.getText().toString());
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showText(String title, String body) {
        TextView t = text(body, 12, TEXT, false);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextIsSelectable(true);
        t.setPadding(dp(20), dp(10), dp(20), dp(10));
        ScrollView s = new ScrollView(this);
        s.addView(t);
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(title)
                .setView(s)
                .setPositiveButton("Close", null)
                .show();
    }

    private static final String MOD_HELP =
            "A mod is a JavaScript file. Start it with a header:\n\n" +
            "// @name My Mod\n// @description What it does\n// @version 1.0\n\n" +
            "EVENTS  mod.on(name, function (e) { ... })\n" +
            "  join         e.server\n" +
            "  leave        e.reason\n" +
            "  kicked       e.reason\n" +
            "  chat         e.message, e.sender, e.raw, e.system\n" +
            "  playerEnter  e.name, e.distance, e.friend\n" +
            "  playerLeave  e.name\n" +
            "  health       e.health (0-20)\n" +
            "  death        e.pos\n" +
            "  second       every second while online\n\n" +
            "TIMERS\n" +
            "  mod.every(ms, fn)   repeat, returns an id\n" +
            "  mod.after(ms, fn)   once\n" +
            "  mod.cancel(id)\n\n" +
            "SAVED DATA (kept between restarts)\n" +
            "  mod.storage.get(key, default)\n" +
            "  mod.storage.set(key, value)\n\n" +
            "BOT\n" +
            "  bot.name, bot.server, bot.online()\n" +
            "  bot.pos()      {x, y, z, yaw}\n" +
            "  bot.health()   0-20\n" +
            "  bot.players()  [{name, distance, dx, dy, dz, friend}]\n" +
            "  bot.entities() same, with kind: player/hostile/animal/item\n" +
            "  bot.chat(text)       (max 1 per 1.5 s)\n" +
            "  bot.command('home')  same as chat('/home')\n" +
            "  bot.jump()           do the anti-AFK action now\n" +
            "  bot.log(text)        shows in the mod log\n" +
            "  bot.alert(title, text)  phone notification\n" +
            "  bot.disconnect(reason)  safety logout\n\n" +
            "EXAMPLE\n" +
            "mod.on('chat', function (e) {\n" +
            "  if (e.message === '!ping') bot.chat('pong');\n" +
            "});\n\n" +
            "Mods can't use files, the internet or Java. Each event must finish quickly; a mod that keeps failing is turned off.";

    // ---------------------------------------------------------------- Storage (works from any tab)

    private LinearLayout storageList;
    private long lastStorageScan;
    private android.app.AlertDialog containerDialog;
    private LinearLayout dialogList;
    private TextView dialogTitle;
    private int dialogVersion = -1;

    private void buildStorageCard(LinearLayout col) {
        LinearLayout c = card(col);
        LinearLayout head = row(c);
        head.addView(text("Storage nearby", 15, TEXT, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView inv = text("Inventory", 13, ACCENT, true);
        head.addView(inv);
        inv.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showInventory();
            }
        });
        c.addView(text("Chests, barrels, shulkers and more within 8 blocks. You must be within 4.5 blocks to open one (walk closer with Drive on the 3D tab).", 12, MUTED, false));
        storageList = new LinearLayout(this);
        storageList.setOrientation(LinearLayout.VERTICAL);
        c.addView(storageList);
        refreshStorage();
    }

    private void refreshStorage() {
        if (storageList == null) return;
        lastStorageScan = System.currentTimeMillis();
        storageList.removeAllViews();
        Bot b = Hub.bot;
        if (b == null || !World.inWorld) {
            storageList.addView(text("Start AFK to see storage around you.", 13, MUTED, false));
            return;
        }
        if (!b.containersSupported()) {
            storageList.addView(text("Opening containers needs a 1.20.2+ server.", 13, MUTED, false));
            return;
        }
        List<Object[]> found = Bot.nearbyContainers(8);
        if (found.isEmpty()) {
            storageList.addView(text("No storage blocks within 8 blocks" + (MapData.chunkCount() == 0 ? " (terrain not loaded yet - keep \"Show terrain\" on in the Map tab)" : "") + ".", 13, MUTED, false));
            return;
        }
        int shown = 0;
        for (final Object[] f : found) {
            if (++shown > 8) break;
            final int[] blk = (int[]) f[0];
            double dist = (Double) f[2];
            LinearLayout r = row(storageList);
            TextView t = text(String.format(Locale.US, "%s  •  %.1f m\n%d, %d, %d", f[1], dist, blk[0], blk[1], blk[2]), 13, dist <= 4.5 ? TEXT : MUTED, false);
            r.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            Button open = smallButton(dist <= 4.5 ? "Open" : "Too far", dist <= 4.5 ? ACCENT : SURFACE2);
            open.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)));
            r.addView(open);
            open.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    openBlock(blk);
                }
            });
        }
    }

    private LinearLayout usableList;

    private void buildUsableCard(LinearLayout col) {
        LinearLayout c = card(col);
        c.addView(text("Doors & switches nearby", 15, TEXT, true));
        c.addView(text("Open doors, trapdoors and gates, and press buttons and levers (that's how iron doors open). Within 4.5 blocks.", 12, MUTED, false));
        usableList = new LinearLayout(this);
        usableList.setOrientation(LinearLayout.VERTICAL);
        c.addView(usableList);
    }

    private void refreshUsable() {
        if (usableList == null) return;
        usableList.removeAllViews();
        Bot b = Hub.bot;
        if (b == null || !World.inWorld || !b.containersSupported()) {
            usableList.addView(text(b == null || !World.inWorld ? "Start AFK to see doors and switches." : "Needs a 1.20.2+ server.", 13, MUTED, false));
            return;
        }
        List<Object[]> found = Bot.nearbyUsable(6);
        if (found.isEmpty()) {
            usableList.addView(text("No doors, buttons or levers within 6 blocks.", 13, MUTED, false));
            return;
        }
        int shown = 0;
        for (final Object[] f : found) {
            if (++shown > 8) break;
            final int[] blk = (int[]) f[0];
            String name = (String) f[1];
            int st = (Integer) f[3];
            double dist = (Double) f[2];
            int t = Shapes.type(st);
            String state = "";
            boolean iron = name.startsWith("Iron ");
            if (t == Shapes.DOOR || t == Shapes.TRAPDOOR || t == Shapes.GATE)
                state = "true".equals(Shapes.prop(st, "open")) ? " (open)" : " (closed)";
            if (t == Shapes.LEVER) state = "true".equals(Shapes.prop(st, "powered")) ? " (on)" : " (off)";
            LinearLayout r = row(usableList);
            String hint = iron && (t == Shapes.DOOR || t == Shapes.TRAPDOOR) ? "\nopened by a button or lever - tap Go through" : "";
            r.addView(text(String.format(Locale.US, "%s%s  \u2022  %.1f m%s", name, state, dist, hint), 13, dist <= 4.5 ? TEXT : MUTED, false),
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            if (t == Shapes.DOOR || t == Shapes.GATE) {
                Button go = smallButton("Go through", iron ? ACCENT : SURFACE2);
                LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36));
                glp.rightMargin = dp(6);
                go.setLayoutParams(glp);
                r.addView(go);
                go.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        Bot bb = Hub.bot;
                        if (bb != null) toast(bb.goThroughDoor(blk));
                    }
                });
            }
            if (iron && (t == Shapes.DOOR || t == Shapes.TRAPDOOR)) continue;
            Button use = smallButton(dist <= 4.5 ? (t == Shapes.BUTTON ? "Press" : t == Shapes.LEVER ? "Flip" : "Use") : "Too far", dist <= 4.5 ? ACCENT : SURFACE2);
            use.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)));
            r.addView(use);
            use.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    openBlock(blk);
                }
            });
        }
    }

    /** Uses a block: opens containers, toggles doors/levers, presses buttons (from Home, Map or 3D). */
    private void openBlock(int[] blk) {
        Bot b = Hub.bot;
        if (b == null || !World.inWorld) {
            toast("Start AFK first");
            return;
        }
        if (!b.containersSupported()) {
            toast("This server version isn't supported for containers");
            return;
        }
        double r = World3DView.reach(blk);
        if (r > 4.5) {
            toast(String.format(Locale.US, "Too far (%.1f m) - get within 4.5 blocks first", r));
            return;
        }
        int st = MapData.stateAt(blk[0], blk[1], blk[2]);
        ProtoDef.VersionData vd = Hub.versionData;
        String name = vd != null && st > 0 ? vd.blockName(st) : "block";
        boolean container = Bot.isContainer(name);
        b.useBlock(blk[0], blk[1], blk[2], Bot.faceToward(blk));
        toast(container ? "Opening " + name + "..." : "Used " + name);
        if (container) {
            final int before = Inv.version;
            ui.postDelayed(new Runnable() {
                public void run() {
                    if (Inv.openId < 0 && Inv.version == before)
                        toast("The server didn't open it - it may be locked, blocked by a block above, or you're too far");
                }
            }, 2500);
        } else {
            ui.postDelayed(new Runnable() {
                public void run() {
                    refreshUsable();
                }
            }, 700);
        }
    }

    /** Shows the open container in a dialog, unless the 3D tab is showing its own panel. */
    private void syncContainerDialog() {
        boolean open = Inv.openId >= 0;
        boolean panelVisible = tab == T_3D && view3d != null;
        if (!open || panelVisible) {
            if (containerDialog != null) {
                android.app.AlertDialog d = containerDialog;
                containerDialog = null;
                d.setOnDismissListener(null);
                d.dismiss();
            }
            return;
        }
        if (containerDialog == null) {
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(dp(16), dp(8), dp(16), 0);
            dialogTitle = text("Container", 16, TEXT, true);
            box.addView(dialogTitle);
            ScrollView sv = new ScrollView(this);
            dialogList = new LinearLayout(this);
            dialogList.setOrientation(LinearLayout.VERTICAL);
            sv.addView(dialogList);
            box.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(360)));
            containerDialog = new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setView(box)
                    .setPositiveButton("Close", null)
                    .setNeutralButton("Take all", null)
                    .create();
            containerDialog.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
                public void onDismiss(android.content.DialogInterface d) {
                    containerDialog = null;
                    Bot b = Hub.bot;
                    if (b != null && Inv.openId >= 0) b.closeContainer();
                }
            });
            containerDialog.show();
            containerDialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    takeAll();
                }
            });
            dialogVersion = -1;
        }
        if (dialogVersion == Inv.version) return;
        dialogVersion = Inv.version;
        dialogTitle.setText(Inv.title.isEmpty() ? "Container" : Inv.title);
        dialogList.removeAllViews();
        List<Inv.Item> s = Inv.slots;
        int n = Inv.containerSize(), shown = 0;
        for (int i = 0; i < n && i < s.size(); i++) {
            Inv.Item it = s.get(i);
            if (it == null) continue;
            shown++;
            final int slot = i;
            LinearLayout r = row(dialogList);
            r.addView(text(it.name + "  ×" + it.count + (it.detail.isEmpty() ? "" : "\n" + it.detail), 14, TEXT, false),
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            Button take = smallButton("Take", ACCENT);
            take.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)));
            r.addView(take);
            take.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    Bot b = Hub.bot;
                    if (b != null) b.clickSlot(slot, 0, 1);
                }
            });
        }
        if (shown == 0) dialogList.addView(text(s.isEmpty() ? "Loading contents..." : "This container is empty.", 13, MUTED, false));
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
        } else if (tab == T_MODULES) {
            if (ModEngine.version != shownModsVersion) refreshMods();
        } else if (tab == T_CHAT) {
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
        } else if (tab == T_ACCOUNT) {
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
            selectTab(T_ACCOUNT);
            return;
        }
        if (!cfg.offline && Auth.signedInName(this) == null) {
            toast("Sign in with Microsoft first");
            selectTab(T_ACCOUNT);
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
