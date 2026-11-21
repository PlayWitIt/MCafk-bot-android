package app.afkbot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.os.PowerManager;

/** Foreground service: keeps the bot connected while the phone is locked. */
public class AfkService extends Service {
    static final String ACTION_START = "app.afkbot.START";
    static final String ACTION_STOP = "app.afkbot.STOP";
    private static final String CHANNEL = "afk";
    private final int NOTIF_ID = 1 + Slot.current(); // one ongoing notification per bot slot

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
        ModStore.init(this);
        if (bot == null) {
            acquireLocks();
            Bot.Config c = Settings.get(this);
            Hub.alertSink = new Hub.AlertSink() {
                @Override
                public void alert(String title, String text) {
                    postAlert(title, text);
                }
            };
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
            Hub.bot = bot;
            Hub.log("Starting AFK bot for " + c.address);
            bot.start();
            listener = new Hub.Listener() {
                @Override
                public void onChange() {
                    updateNotification();
                }
            };
            Hub.addListener(listener);
            beat.post(heartbeat);
        }
        return START_NOT_STICKY;
    }

    private static final String ALERT_CHANNEL = "alerts";
    private int alertId = 100 + Slot.current() * 1000; // bot slots never replace each other's alerts
    private String lastAlert = "";
    private long lastAlertAt = 0;

    /** Heads-up notification for kicks, disconnects, deaths and safety logouts. */
    private void postAlert(final String title, final String text) {
        Hub.post(new Runnable() {
            @Override
            public void run() {
                String key = title + "|" + text;
                long now = System.currentTimeMillis();
                if (key.equals(lastAlert) && now - lastAlertAt < 10 * 60 * 1000L) return; // no spam while reconnecting
                lastAlert = key;
                lastAlertAt = now;
                NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (nm.getNotificationChannel(ALERT_CHANNEL) == null) {
                    NotificationChannel ch = new NotificationChannel(ALERT_CHANNEL, "Kicks, deaths and safety logouts", NotificationManager.IMPORTANCE_HIGH);
                    ch.setDescription("Tells you when the bot is kicked, disconnected, dies or logs out for safety");
                    ch.enableVibration(true);
                    nm.createNotificationChannel(ch);
                }
                Intent open = new Intent(AfkService.this, Slot.mainActivity(Slot.current()));
                open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                PendingIntent pi = PendingIntent.getActivity(AfkService.this, 2, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                Notification n = new Notification.Builder(AfkService.this, ALERT_CHANNEL)
                        .setSmallIcon(android.R.drawable.stat_notify_error)
                        .setContentTitle(title)
                        .setContentText(text)
                        .setStyle(new Notification.BigTextStyle().bigText(text))
                        .setAutoCancel(true)
                        .setContentIntent(pi)
                        .setCategory(Notification.CATEGORY_ALARM)
                        .build();
                nm.notify(alertId++, n);
            }
        });
    }

    private void shutdown() {
        if (bot != null) {
            Bot b = bot;
            bot = null;
            b.onExit = null;
            b.stop();
            Hub.bot = null;
        }
        if (listener != null) {
            Hub.removeListener(listener);
            listener = null;
        }
        releaseLocks();
        beat.removeCallbacks(heartbeat);
        Hub.running = false;
        Hub.status("Stopped", Hub.GREY);
        Hub.detail("");
        MainActivity.publishSlot(this, true);
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

    private final android.os.Handler beat = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable heartbeat = new Runnable() {
        public void run() {
            MainActivity.publishSlot(AfkService.this, true);
            beat.postDelayed(this, Slot.HEARTBEAT_MS);
        }
    };

    private void updateNotification() {
        MainActivity.publishSlot(this, false); // the other bot slots' switchers show this
        String text = Hub.status;
        if (text.equals(lastNotif) || bot == null) return;
        lastNotif = text;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTIF_ID, buildNotification(text));
    }

    /** Our own status bar icon (res/drawable/ic_stat_afk.xml), or the system one if it's somehow missing. */
    private int statIcon() {
        int id = getResources().getIdentifier("ic_stat_afk", "drawable", getPackageName());
        return id != 0 ? id : android.R.drawable.ic_media_play;
    }

    /** "AFK Bot", plus the account name so several running bots can be told apart. */
    private String title() {
        String name = Settings.accountName(this);
        return name.isEmpty() ? "AFK Bot" : "AFK Bot · " + name;
    }

    private Notification buildNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "AFK connection", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Shows that the AFK bot is connected");
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(this, Slot.mainActivity(Slot.current()));
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stop = new Intent(this, Slot.service()).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(statIcon())
                .setColor(MainActivity.ACCENT)
                .setContentTitle(title())
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openPi)
                .addAction(new Notification.Action.Builder(null, "Stop", stopPi).build())
                .build();
    }

    /** The bot service of bot slot 1 (runs in its own process, ":bot1"; see Slot). */
    public static final class Bot1 extends AfkService {
    }

    /** The bot service of bot slot 2 (runs in its own process, ":bot2"; see Slot). */
    public static final class Bot2 extends AfkService {
    }

    /** The bot service of bot slot 3 (runs in its own process, ":bot3"; see Slot). */
    public static final class Bot3 extends AfkService {
    }
}
