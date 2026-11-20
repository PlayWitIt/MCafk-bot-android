package app.afkbot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.os.PowerManager;

/** Foreground service: keeps the bot connected while the phone is locked. */
public class AfkService extends Service {
    static final String ACTION_START = "app.afkbot.START";
    static final String ACTION_STOP = "app.afkbot.STOP";
    private static final String CHANNEL = "afk";
    private static final int NOTIF_ID = 1;

    private Bot bot;
    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifi;
    private Hub.Listener listener;
    private String lastNotif = "";

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            Hub.log("Stopping...");
            shutdown();
            return START_NOT_STICKY;
        }
        startForeground(NOTIF_ID, buildNotification("Starting..."));
        if (bot == null) {
            acquireLocks();
            SharedPreferences p = getSharedPreferences("settings", MODE_PRIVATE);
            Bot.Config c = new Bot.Config();
            c.address = p.getString("address", "constantiam.net");
            c.offline = p.getBoolean("offline", false);
            c.offlineName = p.getString("offlineName", "AFKBot");
            c.jumpSeconds = Math.max(2, p.getInt("jumpSeconds", 30));
            c.autoReconnect = p.getBoolean("autoReconnect", true);
            final Context ctx = getApplicationContext();
            bot = new Bot(c, new Bot.SessionSource() {
                @Override
                public Auth.Session get(boolean force) throws Exception {
                    return Auth.session(ctx, force);
                }
            });
            bot.onExit = new Runnable() {
                @Override
                public void run() {
                    Hub.post(new Runnable() {
                        @Override
                        public void run() {
                            shutdown();
                        }
                    });
                }
            };
            Hub.running = true;
            Hub.log("Starting AFK bot for " + c.address);
            bot.start();
            listener = new Hub.Listener() {
                @Override
                public void onChange() {
                    updateNotification();
                }
            };
            Hub.addListener(listener);
        }
        return START_NOT_STICKY;
    }

    private void shutdown() {
        if (bot != null) {
            Bot b = bot;
            bot = null;
            b.onExit = null;
            b.stop();
        }
        if (listener != null) {
            Hub.removeListener(listener);
            listener = null;
        }
        releaseLocks();
        Hub.running = false;
        Hub.status("Stopped", Hub.GREY);
        Hub.detail("");
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        if (bot != null) shutdown();
        super.onDestroy();
    }

    private void acquireLocks() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AFKBot::connection");
        wake.setReferenceCounted(false);
        wake.acquire();
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wm != null) {
            //noinspection deprecation
            wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "AFKBot::wifi");
            wifi.setReferenceCounted(false);
            wifi.acquire();
        }
    }

    private void releaseLocks() {
        if (wake != null && wake.isHeld()) wake.release();
        wake = null;
        if (wifi != null && wifi.isHeld()) wifi.release();
        wifi = null;
    }

    private void updateNotification() {
        String text = Hub.status;
        if (text.equals(lastNotif) || bot == null) return;
        lastNotif = text;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTIF_ID, buildNotification(text));
    }

    private Notification buildNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "AFK connection", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Shows that the AFK bot is connected");
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stop = new Intent(this, AfkService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("AFK Bot")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openPi)
                .addAction(new Notification.Action.Builder(null, "Stop", stopPi).build())
                .build();
    }
}
