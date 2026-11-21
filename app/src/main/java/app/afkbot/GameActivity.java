package app.afkbot;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.ActivityInfo;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

/**
 * Play mode (experimental): the full game view, built into AFK Bot. It drives the same connection as the AFK bot
 * (no second login), with our own renderer (GameRenderer) and HUD/controls (GameHud). Everything extra it needs
 * (light data, extra packets) is switched on only while this screen is open and dropped when it closes, so the AFK
 * bot and the light 3D view cost exactly what they did before.
 */
public class GameActivity extends Activity implements GameHud.Host {
    private GLSurfaceView gl;
    private GameRenderer renderer;
    private GameHud hud;
    private boolean wasManual;
    private int savedViewDistance = -1; // the bot's own view distance, put back when Play mode closes

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 28)
            getWindow().getAttributes().layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;

        renderer = new GameRenderer();
        gl = new GLSurfaceView(this);
        gl.setEGLContextClientVersion(2);
        gl.setEGLConfigChooser(8, 8, 8, 8, 24, 0);
        gl.setPreserveEGLContextOnPause(true);
        gl.setRenderer(renderer);
        gl.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        hud = new GameHud(this, renderer, this);
        settingsChanged();
        FrameLayout root = new FrameLayout(this);
        root.addView(gl);
        root.addView(hud);
        setContentView(root);
        hideSystemBars();

        // switch on what only this screen needs
        GameState.open = true;
        MapData.keepLight = true;
        MapData.changes = renderer;
        Bot bot = Hub.bot;
        if (bot != null) {
            wasManual = bot.manual;
            renderer.yaw = World.selfYaw;
            renderer.pitch = bot.lookPitch;
            bot.lookYaw = renderer.yaw;
            bot.manual = true; // the player is driven from here (anti-AFK pauses meanwhile)
            bot.reloadTerrain(); // chunks already loaded came without light: have the server send them again
        }
        loadAssets();
    }

    private void loadAssets() {
        final String v = Hub.versionName;
        final ProtoDef.VersionData vd = Hub.versionData;
        if (v == null || vd == null) {
            GameAssetStore.status = "Start AFK first - Play mode uses the bot's connection";
            return;
        }
        final android.content.Context app = getApplicationContext();
        new Thread(new Runnable() {
            public void run() {
                try {
                    GameAssetStore.Loaded a = GameAssetStore.load(app, v, vd);
                    hud.assets = a;
                    renderer.assets = a;
                } catch (final Exception e) {
                    GameAssetStore.status = "Couldn't load the game files: " + e.getMessage();
                }
            }
        }, "game-assets").start();
    }

    private void hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                c.hide(WindowInsets.Type.systemBars());
            }
        } else {
            //noinspection deprecation
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus) hideSystemBars();
    }

    @Override
    protected void onResume() {
        super.onResume();
        gl.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        gl.onPause();
        Bot b = Hub.bot;
        if (b != null) { // let go of every key, so the player doesn't keep walking in the background
            b.inForward = b.inStrafe = 0;
            b.inJump = false;
            b.inSprint = false;
            if (b.isMining()) b.stopMining(true);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        renderer.stop();
        MapData.changes = null;
        MapData.keepLight = false;
        GameState.open = false;
        MapData.dropLight();
        GameState.breaking.clear();
        Bot b = Hub.bot;
        if (b != null) {
            b.inForward = b.inStrafe = 0;
            b.inJump = b.inSprint = b.inSneak = false;
            b.manual = wasManual;
            if (savedViewDistance >= 0 && b.config() != null) {
                b.config().viewDistance = savedViewDistance;
                b.applySettings();
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (hud.screen != GameHud.NONE) {
            hud.screen = GameHud.NONE;
            return;
        }
        if (Inv.openId >= 0) {
            Bot b = Hub.bot;
            if (b != null) b.closeContainer();
            return;
        }
        hud.screen = GameHud.PAUSE;
    }

    // ---- GameHud.Host ----

    @Override
    public void openChat() {
        final EditText e = new EditText(this);
        e.setHint("Message, or /command");
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT);
        e.setImeOptions(EditorInfo.IME_ACTION_SEND);
        final AlertDialog d = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setView(e)
                .setPositiveButton("Send", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface dd, int w) {
                        send(e.getText().toString());
                    }
                })
                .setNegativeButton("Cancel", null)
                .create();
        e.setOnEditorActionListener(new android.widget.TextView.OnEditorActionListener() {
            public boolean onEditorAction(android.widget.TextView v, int a, android.view.KeyEvent ev) {
                send(e.getText().toString());
                d.dismiss();
                return true;
            }
        });
        d.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            public void onDismiss(android.content.DialogInterface dd) {
                hideSystemBars();
            }
        });
        d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        d.show();
    }

    private void send(String t) {
        t = t.trim();
        if (t.isEmpty()) return;
        Bot b = Hub.bot;
        if (b == null) Toast.makeText(this, "Not connected", Toast.LENGTH_SHORT).show();
        else b.sendChat(t);
    }

    @Override
    public void leave() {
        finish();
    }

    @Override
    public void settingsChanged() {
        renderer.renderDistance = hud.renderDistance;
        renderer.fov = hud.fov;
        Bot b = Hub.bot;
        if (b != null) { // ask the server for as many chunks as we draw (it may send fewer)
            Bot.Config c = b.config();
            if (c != null && c.viewDistance != hud.renderDistance) {
                if (savedViewDistance < 0) savedViewDistance = c.viewDistance;
                c.viewDistance = Math.max(savedViewDistance, hud.renderDistance);
                b.applySettings();
            }
        }
    }
}
