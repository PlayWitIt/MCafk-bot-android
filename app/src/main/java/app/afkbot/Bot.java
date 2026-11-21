package app.afkbot;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** A tiny headless Minecraft: Java Edition client that only stays connected and jumps now and then. */
final class Bot {

    interface SessionSource {
        Auth.Session get(boolean forceRefresh) throws Exception;
    }

    /** All module settings. Fields are volatile so the screen can change them while the bot runs. */
    static final class Config {
        volatile String address = "";
        volatile boolean offline = false;
        volatile String offlineName = "AFKBot";
        volatile boolean autoReconnect = true;
        volatile boolean autoRespawn = true;
        // Anti-AFK
        volatile boolean antiAfk = true, afkJump = true, afkSpin = true, afkSwing = true, afkSneak = false, afkRandom = true;
        volatile int afkSeconds = 30;
        // Auto Log
        volatile boolean autoLog = true, logIgnoreFriends = true;
        volatile int logHearts = 5, logDistance = 24, rejoinMinutes = 0;
        // Visual Range
        volatile boolean visualRange = true;
        volatile String friends = "";
        // Map / render distance
        volatile boolean minimap = true;
        volatile boolean view3d = false;
        volatile int viewDistance = 6;

        /** Chunks are only requested while something shows terrain. */
        boolean wantChunks() {
            return minimap || view3d;
        }

        float logoutHealth() {
            return autoLog && logHearts > 0 ? logHearts * 2f : 0;
        }

        int logoutDistance() {
            return autoLog ? logDistance : 0;
        }

        World.Friends friendSet() {
            return new World.Friends(friends);
        }
    }

    /** We left on purpose to stay safe (low health, player nearby, died). */
    static final class SafetyLogout extends IOException {
        SafetyLogout(String m) {
            super(m);
        }
    }

    /** Disconnect reason sent by the server. */
    static final class Kick extends IOException {
        Kick(String m) {
            super(m);
        }
    }

    /** A problem that reconnecting will not fix. */
    static final class Fatal extends Exception {
        Fatal(String m) {
            super(m);
        }
    }

    private static final int HANDSHAKE = 0, LOGIN = 1, CONFIG = 2, PLAY = 3;
    private static final int MAX_PACKET = 8 * 1024 * 1024;

    private final Config cfg;
    private final SessionSource sessions;
    private volatile boolean stopped;
    private volatile Socket socket;
    private Thread thread;
    Runnable onExit;

    // --- per-connection state ---
    private int proto;
    private int[] ids;
    private int playerType = -1;
    private String kinds = "";
    private String[] typeNames = new String[0];
    private volatile int selfEntityId = Integer.MIN_VALUE;
    private volatile double nearestPlayer = -1;
    private int sneakTicks;
    private volatile boolean batchPending;
    private volatile String pendingLogout;
    /** How many times the server moved us back (rejected movement). */
    static volatile int corrections;
    private long lastCorrectionLog;
    private ProtoDef.VersionData vdata;
    private int useSequence;

    // ---- manual control (3D tab) ----
    /** True while you drive the player from the 3D tab (anti-AFK pauses). */
    volatile boolean manual;
    volatile float inForward, inStrafe, lookYaw, lookPitch;
    volatile boolean inJump, inSneak, inSprint;
    // auto-walk target (used by "Go through" for button-operated doors)
    private volatile double goalX = Double.NaN, goalZ;
    private volatile long goalUntil;
    private volatile boolean manualBeforeGoal;
    private boolean onGroundM = true, wasSneak, wasSprint, inWater, hCollideM;
    private int lastInputFlags = -1;
    private double vxM, vyM, vzM;
    private float lastHealthEvent = -1;
    private volatile int state;
    private int threshold = -1;
    private Cipher encrypt, decrypt;
    private OutputStream out;
    private final Inflater inflater = new Inflater();
    private final Deflater deflater = new Deflater();
    private Auth.Session session;
    private String playerName;
    private ScheduledExecutorService ticker;
    private final Random rnd = new Random();

    // --- player state (guarded by this) ---
    private boolean havePos, jumping, loadedSent, respawnPending;
    private double x, y, z, groundY, vy;
    private float yaw, pitch;
    private float health = 20;
    private int tick, jumps;
    private long nextJumpAt, joinedAt;
    private String serverLabel = "";

    /** The live settings this bot runs with (Play mode adjusts the view distance while it's open). */
    Config config() {
        return cfg;
    }

    Bot(Config cfg, SessionSource sessions) {
        this.cfg = cfg;
        this.sessions = sessions;
    }

    void start() {
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "afk-bot");
        thread.start();
    }

    void stop() {
        stopped = true;
        closeSocket();
        if (thread != null) thread.interrupt();
    }

    private void closeSocket() {
        Socket s = socket;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    // ===================================================================================
    // Reconnect loop
    // ===================================================================================

    private void loop() {
        int failures = 0;
        int minWait = 0;
        boolean claimLatest = false;
        while (!stopped) {
            long playStart = 0;
            try {
                connectOnce(claimLatest);
            } catch (Fatal f) {
                Hub.log("Stopped: " + f.getMessage());
                Hub.status("Error: " + f.getMessage(), Hub.RED);
                Hub.alert("AFK Bot stopped", f.getMessage());
                break;
            } catch (Auth.AuthException a) {
                Hub.log("Login problem: " + a.getMessage());
                if (a.getMessage() != null && a.getMessage().toLowerCase(Locale.US).contains("sign in")) {
                    Hub.status(a.getMessage(), Hub.RED);
                    Hub.alert("AFK Bot stopped", a.getMessage());
                    break;
                }
            } catch (SafetyLogout s) {
                Hub.log("SAFETY LOGOUT: " + s.getMessage());
                emitMod("leave", "reason", s.getMessage());
                Hub.alert("Logged out for safety", s.getMessage());
                cleanup();
                if (cfg.rejoinMinutes <= 0) {
                    Hub.status("Logged out for safety: " + s.getMessage(), Hub.RED);
                    break;
                }
                for (int i = cfg.rejoinMinutes * 60; i > 0 && !stopped; i--) {
                    Hub.status("Safety logout (" + s.getMessage() + "). Rejoining in " + duration(i), Hub.YELLOW);
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ie) {
                        break;
                    }
                }
                failures = 0;
                continue;
            } catch (Auth.RateLimited rl) {
                Hub.log(rl.getMessage() + " - waiting 2 minutes before trying again");
                Hub.status("Mojang login limit - waiting", Hub.YELLOW);
                minWait = 120;
            } catch (Kick k) {
                Hub.log("Kicked: " + k.getMessage());
                String rs = recentSent();
                if (!rs.isEmpty()) Hub.log("Last things sent before the kick: " + rs);
                String km = String.valueOf(k.getMessage()).toLowerCase(Locale.US);
                if (km.contains("wait") || km.contains("too fast") || km.contains("throttl")) minWait = 60;
                Hub.status("Kicked: " + firstLine(k.getMessage()), Hub.RED);
                Hub.alert("Kicked from " + serverLabel, firstLine(k.getMessage()));
                emitMod("kicked", "reason", k.getMessage());
            } catch (Exception e) {
                if (!stopped) {
                    String m = e instanceof SocketTimeoutException ? "server stopped responding (timed out)"
                            : e instanceof EOFException ? "server closed the connection" : String.valueOf(e.getMessage());
                    Hub.log("Disconnected: " + m);
                    String rs = recentSent();
                    if (!rs.isEmpty()) Hub.log("Last things sent before the disconnect: " + rs);
                    Hub.status("Disconnected", Hub.RED);
                    if (joinedAt > 0) Hub.alert("Disconnected from " + serverLabel, m + (cfg.autoReconnect ? " - reconnecting" : ""));
                    emitMod("leave", "reason", m);
                }
            } finally {
                playStart = joinedAt;
                cleanup();
            }
            if (stopped) break;

            boolean playedLong = playStart > 0 && System.currentTimeMillis() - playStart > 120000;
            if (playedLong) failures = 0;
            else failures++;
            // If the server is newer than this app knows, alternate between "use server's version" and
            // "claim the newest version we know" (works on servers with ViaVersion).
            if (playStart == 0 && unknownNewer) claimLatest = !claimLatest;

            if (!cfg.autoReconnect) {
                Hub.status("Disconnected (auto-reconnect is off)", Hub.RED);
                break;
            }
            int wait = Math.max(minWait, Math.min(300, 10 * (1 << Math.min(5, Math.max(0, failures - 1)))));
            minWait = 0;
            for (int i = wait; i > 0 && !stopped; i--) {
                Hub.status("Reconnecting in " + i + "s", Hub.YELLOW);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    break;
                }
            }
        }
        cleanup();
        if (stopped) Hub.status("Stopped", Hub.GREY);
        Hub.detail("");
        Hub.running = false;
        Hub.fire();
        if (onExit != null) onExit.run();
    }

    private boolean unknownNewer;

    private static String firstLine(String s) {
        if (s == null) return "";
        int i = s.indexOf('\n');
        return i < 0 ? s : s.substring(0, i);
    }

    /** Walks in a straight line to (x, z) for at most ms milliseconds (stops early when there). */
    void walkTo(double gx, double gz, long ms) {
        if (Double.isNaN(goalX)) manualBeforeGoal = manual; // remember whether you were driving
        goalZ = gz;
        goalUntil = System.currentTimeMillis() + ms;
        goalX = gx;
        manual = true;
    }

    /**
     * Presses the button/lever that works a door, then walks through the doorway before it closes.
     * Returns a message for the user.
     */
    String goThroughDoor(final int[] door) {
        final int st = MapData.stateAt(door[0], door[1], door[2]);
        if (Shapes.type(st) != Shapes.DOOR && Shapes.type(st) != Shapes.GATE && Shapes.type(st) != Shapes.TRAPDOOR) return "That isn't a door";
        // nearest button or lever within 3 blocks of the door that we can reach
        int[] best = null;
        double bestD = 1e9;
        for (int dx = -3; dx <= 3; dx++)
            for (int dy = -2; dy <= 3; dy++)
                for (int dz = -3; dz <= 3; dz++) {
                    int bx = door[0] + dx, by = door[1] + dy, bz = door[2] + dz;
                    int t = Shapes.type(MapData.stateAt(bx, by, bz));
                    if (t != Shapes.BUTTON && t != Shapes.LEVER) continue;
                    int[] b = {bx, by, bz, 1};
                    double r = World.reach(b);
                    if (r <= 4.5 && r < bestD) {
                        bestD = r;
                        best = b;
                    }
                }
        boolean open = "true".equals(Shapes.prop(st, "open"));
        if (best == null && !open) return "No button or lever within reach of this door - walk closer to the button";
        if (best != null && !open) useBlock(best[0], best[1], best[2], faceToward(best));
        // walk through along the door's axis, to the far side
        String facing = Shapes.prop(st, "facing");
        boolean alongZ = "north".equals(facing) || "south".equals(facing);
        double cx = door[0] + 0.5, cz = door[2] + 0.5;
        double gx = cx, gz = cz;
        if (alongZ) gz = cz + (World.selfZ < cz ? 1.3 : -1.3);
        else gx = cx + (World.selfX < cx ? 1.3 : -1.3);
        final double fgx = gx, fgz = gz;
        final ScheduledExecutorService t = ticker;
        if (t == null) return "Not connected";
        // wait a moment for the door to open, then go
        t.schedule(new Runnable() {
            @Override
            public void run() {
                walkTo(fgx, fgz, 2500);
            }
        }, best != null && !open ? 250 : 0, TimeUnit.MILLISECONDS);
        return best != null && !open ? "Pressing the button and walking through..." : "Walking through...";
    }

    /** Makes the bot do its anti-AFK action right now (used by mods). */
    void forceAction() {
        synchronized (this) {
            nextJumpAt = 0;
        }
    }

    /** Disconnects like a safety logout (used by mods). */
    void modDisconnect(String reason) {
        pendingLogout = reason;
        closeSocket();
    }

    private void cleanup() {
        pendingLogout = null;
        lastInputFlags = -1;
        Inv.reset();
        manual = false;
        lastHealthEvent = -1;
        if (ticker != null) {
            ticker.shutdownNow();
            ticker = null;
        }
        closeSocket();
        socket = null;
        synchronized (this) {
            havePos = false;
            jumping = false;
            loadedSent = false;
            respawnPending = false;
            joinedAt = 0;
        }
        World.reset();
        MapData.clear();
        nearestPlayer = -1;
        selfEntityId = Integer.MIN_VALUE;
        sneakTicks = 0;
        encrypt = decrypt = null;
        threshold = -1;
        state = HANDSHAKE;
    }

    // ===================================================================================
    // Connection
    // ===================================================================================

    private void connectOnce(boolean claimLatest) throws Exception {
        String addr = cfg.address.trim();
        if (addr.isEmpty()) throw new Fatal("Enter a server address");
        String host = addr;
        int port = -1;
        int colon = addr.lastIndexOf(':');
        if (colon > 0 && addr.indexOf(':') == colon) {
            host = addr.substring(0, colon);
            try {
                port = Integer.parseInt(addr.substring(colon + 1).trim());
            } catch (NumberFormatException e) {
                throw new Fatal("Bad port in address: " + addr);
            }
        }
        String connectHost = host;
        int connectPort = port > 0 ? port : 25565;
        if (port <= 0 && !host.matches("[0-9.]+")) {
            String[] srv = srvLookup(host);
            if (srv != null) {
                connectHost = srv[0];
                connectPort = Integer.parseInt(srv[1]);
                Hub.log("Found SRV record: " + connectHost + ":" + connectPort);
                if (connectHost.indexOf('_') >= 0) {
                    // Android's resolver rejects hostnames with '_', so look the address up ourselves.
                    String ip = resolveIp(connectHost);
                    if (ip != null) {
                        Hub.log("Server address: " + ip + ":" + connectPort);
                        connectHost = ip;
                    } else {
                        Hub.log("Could not look up the address of " + connectHost);
                    }
                }
            }
        }
        serverLabel = addr;

        Hub.status("Pinging " + addr + "...", Hub.YELLOW);
        int serverProto;
        try {
            serverProto = ping(host, connectHost, connectPort);
        } catch (Kick k) {
            throw k;
        } catch (Exception e) {
            boolean usedSrv = !connectHost.equals(host) || connectPort != (port > 0 ? port : 25565);
            if (!usedSrv) throw new IOException("Server not reachable (" + e.getMessage() + ")");
            Hub.log("SRV address didn't answer (" + e.getMessage() + "), trying " + host + ":25565 directly");
            connectHost = host;
            connectPort = 25565;
            try {
                serverProto = ping(host, connectHost, connectPort);
            } catch (Kick k) {
                throw k;
            } catch (Exception e2) {
                throw new IOException("Server not reachable (" + e2.getMessage() + ")");
            }
        }

        int idx = -1;
        int last = Proto.PROTOCOLS.length - 1;
        for (int i = 0; i <= last; i++) if (Proto.PROTOCOLS[i] == serverProto) idx = i;
        unknownNewer = false;
        if (idx >= 0) {
            proto = serverProto;
        } else if (serverProto > Proto.PROTOCOLS[last]) {
            unknownNewer = true;
            idx = last;
            proto = claimLatest ? Proto.PROTOCOLS[last] : serverProto;
            Hub.log("Server is newer than this app knows; trying protocol " + proto + " with " + Proto.NAMES[last] + " packet layout.");
        } else if (serverProto < 764) {
            throw new Fatal("Server runs a Minecraft version this app can't speak (protocol " + serverProto + "). Supported: 1.12.2 and 1.20.2 or newer.");
        } else {
            for (int i = 0; i <= last; i++) if (Proto.PROTOCOLS[i] < serverProto) idx = i;
            proto = serverProto;
        }
        ids = Proto.IDS[idx];
        String[] vn = Proto.NAMES[idx].split("/");
        Hub.versionName = vn[vn.length - 1];
        vdata = null;
        Hub.VersionLoader vl = Hub.versionLoader;
        if (vl != null && Proto.PROTOCOLS[idx] >= 764) {
            try {
                vdata = vl.load(Proto.PROTOCOLS[idx]);
                Hub.versionData = vdata;
                Shapes.setVersion(vdata);
                Hub.log("Chests, inventory and block names ready");
            } catch (Exception e) {
                Hub.log("Container support unavailable: " + e.getMessage());
            }
        }
        MapData.setVersion(idx, serverProto > 0 ? Math.min(proto, Proto.PROTOCOLS[Proto.PROTOCOLS.length - 1]) : proto);
        playerType = Proto.PLAYER_TYPE[idx];
        kinds = Proto.ENTITY_KINDS[idx];
        typeNames = Proto.ENTITY_NAMES[idx].split(",", -1);

        if (cfg.offline) {
            String n = cfg.offlineName.trim();
            if (!n.matches("[A-Za-z0-9_]{3,16}")) throw new Fatal("Offline username must be 3-16 letters, numbers or _");
            session = new Auth.Session(n, UUID.nameUUIDFromBytes(("OfflinePlayer:" + n).getBytes(StandardCharsets.UTF_8)).toString(), "");
        } else {
            session = sessions.get(false);
        }
        playerName = session.name;

        Hub.status("Connecting to " + addr + "...", Hub.YELLOW);
        Socket s = new Socket();
        socket = s;
        if (stopped) throw new IOException("stopped");
        s.connect(new InetSocketAddress(connectHost, connectPort), 15000);
        s.setTcpNoDelay(true);
        s.setSoTimeout(45000);
        s.setKeepAlive(true);
        out = s.getOutputStream();
        final DecryptIn din = new DecryptIn(new BufferedInputStream(s.getInputStream(), 16384));
        DataInputStream in = new DataInputStream(din);

        send(new Buf(0x00).varInt(proto).str(host).u16(connectPort).varInt(2));
        state = LOGIN;
        Buf ls = new Buf(id(Proto.L_S_START)).str(playerName);
        if (!legacy()) ls.uuid(parseUuid(session.uuid));
        send(ls);
        Hub.log("Logging in as " + playerName + "...");

        ticker = Executors.newSingleThreadScheduledExecutor();
        ticker.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                try {
                    onTick();
                } catch (Throwable t) {
                    Hub.log("Tick error: " + t);
                    closeSocket();
                }
            }
        }, 50, 50, TimeUnit.MILLISECONDS);

        try {
            while (!stopped) {
                Buf.In p = readPacket(in);
                if (p == null) continue;
                int pid = p.varInt();
                handle(pid, p, din);
            }
        } catch (IOException e) {
            String why = pendingLogout;
            if (why != null && !(e instanceof SafetyLogout)) throw new SafetyLogout(why);
            throw e;
        }
    }

    /** 1.12.2: no configuration phase, older login and chat formats. */
    private boolean legacy() {
        return proto < 764;
    }

    private int id(int key) {
        return ids[key];
    }

    private static UUID parseUuid(String s) {
        String h = s.replace("-", "");
        if (h.length() != 32) return UUID.nameUUIDFromBytes(s.getBytes(StandardCharsets.UTF_8));
        return new UUID(new BigInteger(h.substring(0, 16), 16).longValue(), new BigInteger(h.substring(16), 16).longValue());
    }

    // ===================================================================================
    // Packet handling
    // ===================================================================================

    private void handle(int pid, Buf.In p, DecryptIn din) throws Exception {
        switch (state) {
            case LOGIN:
                handleLogin(pid, p, din);
                break;
            case CONFIG:
                handleConfig(pid, p);
                break;
            case PLAY:
                handlePlay(pid, p);
                break;
            default:
                break;
        }
    }

    private void handleLogin(int pid, Buf.In p, DecryptIn din) throws Exception {
        if (pid == id(Proto.L_C_DISCONNECT)) {
            throw new Kick(Text.strip(Text.fromJson(p.str())));
        } else if (pid == id(Proto.L_C_ENCRYPT)) {
            String serverId = p.str();
            byte[] pub = p.byteArray();
            byte[] token = p.byteArray();
            boolean shouldAuth = p.remaining() <= 0 || p.bool();
            byte[] secret = new byte[16];
            new SecureRandom().nextBytes(secret);
            if (shouldAuth) {
                if (cfg.offline)
                    throw new Fatal("This server requires a real Minecraft account. Turn off \"offline server\" and sign in with Microsoft.");
                String hash = serverHash(serverId, secret, pub);
                try {
                    Auth.joinServer(session, hash);
                } catch (Auth.AuthException e) {
                    Hub.log(e.getMessage());
                    session = sessions.get(true);
                    Auth.joinServer(session, hash);
                }
            }
            PublicKey key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(pub));
            Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            rsa.init(Cipher.ENCRYPT_MODE, key);
            byte[] encSecret = rsa.doFinal(secret);
            rsa.init(Cipher.ENCRYPT_MODE, key);
            byte[] encToken = rsa.doFinal(token);
            send(new Buf(id(Proto.L_S_ENCRYPT)).byteArray(encSecret).byteArray(encToken));
            Cipher e = Cipher.getInstance("AES/CFB8/NoPadding");
            e.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(secret, "AES"), new IvParameterSpec(secret));
            Cipher d = Cipher.getInstance("AES/CFB8/NoPadding");
            d.init(Cipher.DECRYPT_MODE, new SecretKeySpec(secret, "AES"), new IvParameterSpec(secret));
            synchronized (this) {
                encrypt = e;
            }
            din.cipher = d;
            decrypt = d;
            Hub.log("Encryption enabled" + (shouldAuth ? " (account verified with Mojang)" : ""));
        } else if (pid == id(Proto.L_C_COMPRESS)) {
            threshold = p.varInt();
        } else if (pid == id(Proto.L_C_SUCCESS)) {
            if (legacy()) p.str();
            else p.uuid();
            String name = p.str();
            Hub.log("Login accepted as " + name);
            if (legacy()) {
                state = PLAY;
                Hub.status("Loading world...", Hub.YELLOW);
                return;
            }
            send(new Buf(id(Proto.L_S_ACK)));
            state = CONFIG;
            sendClientSettings();
            Hub.status("Loading world...", Hub.YELLOW);
        } else if (pid == id(Proto.L_C_PLUGIN_REQ)) {
            int msgId = p.varInt();
            send(new Buf(id(Proto.L_S_PLUGIN_RESP)).varInt(msgId).bool(false));
        } else if (pid == id(Proto.L_C_COOKIE_REQ)) {
            send(new Buf(id(Proto.L_S_COOKIE_RESP)).str(p.str()).bool(false));
        }
    }

    private void sendClientSettings() throws IOException {
        sendSettings(id(Proto.C_S_SETTINGS));
    }

    private void sendSettings(int sid) throws IOException {
        if (sid < 0) return;
        int vd = Math.max(2, Math.min(32, cfg.viewDistance));
        Buf b = new Buf(sid).str("en_us").u8(vd).varInt(0).bool(true).u8(0x7F).varInt(1);
        if (!legacy()) {
            b.bool(false).bool(true);
            if (proto >= 768) b.varInt(2); // particles: minimal
        }
        send(b);
    }

    /**
     * Call after turning terrain (map or 3D) on: resumes chunk loading and makes the server resend
     * nearby chunks by briefly lowering the render distance and raising it again.
     */
    void reloadTerrain() {
        final ScheduledExecutorService t = ticker;
        if (t == null || state != PLAY) return;
        t.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    if (batchPending && id(Proto.P_S_CHUNK_BATCH_RECEIVED) >= 0)
                        send(new Buf(id(Proto.P_S_CHUNK_BATCH_RECEIVED)).f32(20f));
                    batchPending = false;
                    int keep = cfg.viewDistance;
                    cfg.viewDistance = 2;
                    sendSettings(id(Proto.P_S_SETTINGS));
                    cfg.viewDistance = keep;
                } catch (IOException ignored) {
                }
            }
        });
        t.schedule(new Runnable() {
            @Override
            public void run() {
                try {
                    if (state == PLAY) sendSettings(id(Proto.P_S_SETTINGS));
                    Hub.log("Reloading terrain around you...");
                } catch (IOException ignored) {
                }
            }
        }, 1500, TimeUnit.MILLISECONDS);
    }

    /** Re-sends client settings (e.g. after the render distance was changed in the app). */
    void applySettings() {
        final ScheduledExecutorService t = ticker;
        if (t == null) return;
        t.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    if (state == CONFIG) sendSettings(id(Proto.C_S_SETTINGS));
                    else if (state == PLAY) sendSettings(id(Proto.P_S_SETTINGS));
                    Hub.log("Render distance set to " + cfg.viewDistance + " chunks");
                } catch (IOException ignored) {
                }
            }
        });
    }

    private void handleConfig(int pid, Buf.In p) throws Exception {
        if (pid == id(Proto.C_C_DISCONNECT)) {
            throw new Kick(readReason(p));
        } else if (pid == id(Proto.C_C_FINISH)) {
            send(new Buf(id(Proto.C_S_FINISH)));
            state = PLAY;
        } else if (pid == id(Proto.C_C_KEEPALIVE)) {
            send(new Buf(id(Proto.C_S_KEEPALIVE)).i64(p.i64()));
        } else if (pid == id(Proto.C_C_PING)) {
            send(new Buf(id(Proto.C_S_PONG)).i32(p.i32()));
        } else if (pid == id(Proto.C_C_KNOWN_PACKS)) {
            int n = p.varInt();
            Buf b = new Buf(id(Proto.C_S_KNOWN_PACKS)).varInt(n);
            for (int i = 0; i < n; i++) b.str(p.str()).str(p.str()).str(p.str());
            send(b);
        } else if (pid == id(Proto.C_C_COOKIE_REQ)) {
            send(new Buf(id(Proto.C_S_COOKIE_RESP)).str(p.str()).bool(false));
        } else if (pid == id(Proto.C_C_RESOURCE_PACK)) {
            answerResourcePack(p, id(Proto.C_S_RESOURCE_PACK));
        } else if (pid == id(Proto.C_C_REGISTRY_DATA) && proto >= 766) {
            // remember the biome list (biome ids in chunks are positions in it) for the Play screen's colours
            try {
                String reg = p.str();
                if (reg.equals("minecraft:worldgen/biome")) {
                    int n = p.varInt();
                    String[] names = new String[Math.max(0, Math.min(n, 4096))];
                    for (int i = 0; i < names.length; i++) {
                        String key = p.str();
                        names[i] = key.startsWith("minecraft:") ? key.substring(10) : key;
                        if (p.bool()) { // inline data (servers without the vanilla pack): skip it
                            int t = p.u8();
                            if (t != 0) MapData.skipNbt(p, t);
                        }
                    }
                    GameState.biomeNames = names;
                }
            } catch (IOException ignored) {
                // never let this stop the login
            }
        } else if (pid == id(Proto.C_C_CODE_OF_CONDUCT)) {
            Hub.log("Server code of conduct: " + firstLine(p.str()));
            send(new Buf(id(Proto.C_S_ACCEPT_COC)));
            Hub.log("Code of conduct accepted automatically");
        }
    }

    private void answerResourcePack(Buf.In p, int respId) throws IOException {
        if (respId < 0) return;
        if (proto >= 765) {
            UUID u = p.uuid();
            send(new Buf(respId).uuid(u).varInt(3)); // accepted
            send(new Buf(respId).uuid(u).varInt(4)); // downloaded
            send(new Buf(respId).uuid(u).varInt(0)); // loaded
        } else {
            send(new Buf(respId).varInt(3));
            send(new Buf(respId).varInt(0));
        }
        Hub.log("Server resource pack accepted (not downloaded, not needed for AFK)");
    }

    private String readReason(Buf.In p) throws IOException {
        String s = proto >= 765 ? Text.fromNbt(p) : Text.fromJson(p.str());
        return Text.strip(s).trim();
    }

    private void handlePlay(int pid, Buf.In p) throws Exception {
        if (pid == id(Proto.P_C_KEEPALIVE)) {
            send(new Buf(id(Proto.P_S_KEEPALIVE)).i64(p.i64()));
        } else if (pid == id(Proto.P_C_PING)) {
            send(new Buf(id(Proto.P_S_PONG)).i32(p.i32()));
        } else if (pid == id(Proto.P_C_POSITION)) {
            onTeleport(p);
        } else if (pid == id(Proto.P_C_LOGIN)) {
            selfEntityId = p.i32();
            World.clearEntities();
            World.inWorld = true;
            if (!legacy()) {
                try {
                    p.bool(); // hardcore
                    int worlds = p.varInt();
                    for (int i = 0; i < worlds; i++) p.str();
                    p.varInt(); // max players
                    Hub.serverViewDistance = p.varInt();
                    p.varInt(); // simulation distance
                    p.bool();
                    p.bool();
                    p.bool();
                    onDimension(p);
                    readSecureChat(p);
                } catch (IOException ignored) {
                    MapData.setDimension("");
                }
            }
            synchronized (this) {
                if (joinedAt == 0) joinedAt = System.currentTimeMillis();
            }
            Hub.log("Joined the server!");
            emitMod("join", "server", serverLabel);
            if (legacy()) sendSettings(id(Proto.P_S_SETTINGS));
            Hub.status("Online on " + serverLabel + " as " + playerName, Hub.GREEN);
        } else if (pid == id(Proto.P_C_DISCONNECT)) {
            throw new Kick(readReason(p));
        } else if (pid == id(Proto.P_C_START_CONFIG)) {
            synchronized (this) {
                havePos = false;
                jumping = false;
            }
            send(new Buf(id(Proto.P_S_CONFIG_ACK)));
            state = CONFIG;
            Hub.log("Server is switching worlds/reconfiguring...");
        } else if (pid == id(Proto.P_C_HEALTH)) {
            float h = p.f32();
            if (p.remaining() > 0) {
                GameState.food = p.varInt();
                if (p.remaining() >= 4) GameState.saturation = p.f32();
            }
            boolean died;
            synchronized (this) {
                died = h <= 0 && health > 0;
                health = h;
                if (h > 0) respawnPending = false;
            }
            if (h != lastHealthEvent) {
                lastHealthEvent = h;
                emitMod("health", "health", h);
            }
            if (died) emitMod("death", "pos", posText());
            if (h > 0 && cfg.logoutHealth() > 0 && h < cfg.logoutHealth()) {
                throw new SafetyLogout(String.format(Locale.US, "health dropped to %.1f/20 (%.1f hearts) at %s", h, h / 2, posText()));
            }
            if (died && !cfg.autoRespawn) {
                throw new SafetyLogout("you died at " + posText() + " (auto-respawn is off)");
            }
            if (died) {
                Hub.alert("You died", "Died at " + posText() + " - respawning");
                Hub.log("You died - respawning automatically");
                synchronized (this) {
                    respawnPending = true;
                }
                ScheduledExecutorService t = ticker;
                if (t != null) t.schedule(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            send(new Buf(id(Proto.P_S_CLIENT_COMMAND)).varInt(0));
                        } catch (IOException ignored) {
                        }
                    }
                }, 1500, TimeUnit.MILLISECONDS);
            }
        } else if (pid == id(Proto.P_C_SYSTEM_CHAT)) {
            String msg = readReason(p);
            boolean overlay = p.remaining() > 0 && (legacy() ? p.u8() == 2 : p.bool());
            if (!overlay && !msg.isEmpty()) {
                if (msg.length() > 400) msg = msg.substring(0, 400) + "...";
                Hub.chat(msg);
                // many servers send player chat as "<Name> message" system messages
                java.util.regex.Matcher mm = CHAT_LINE.matcher(msg);
                if (mm.matches()) emitChat(mm.group(1), mm.group(2), msg, true);
                else emitChat(null, msg, msg, true);
            }
        } else if (handleEntity(pid, p)) {
            // handled
        } else if (pid == id(Proto.P_C_RESOURCE_PACK)) {
            answerResourcePack(p, id(Proto.P_S_RESOURCE_PACK));
        } else if (pid == id(Proto.P_C_COOKIE_REQ)) {
            send(new Buf(id(Proto.P_S_COOKIE_RESP)).str(p.str()).bool(false));
        }
    }

    // ----- entities, player list and chat -----

    private char kindOf(int type) {
        return type >= 0 && type < kinds.length() ? kinds.charAt(type) : 'o';
    }

    private String nameOf(int type) {
        return type >= 0 && type < typeNames.length ? typeNames[type] : "";
    }

    /**
     * Entity metadata, read only far enough to learn which item a dropped item entity is (entry 8, the item stack)
     * so the Play screen can draw it. Entries before it use simple types; anything else stops the parse.
     */
    private void readItemMetadata(Buf.In p) throws IOException {
        int eid = p.varInt();
        World.Ent e = World.get(eid);
        if (e == null || e.kind != 'i' || vdata == null || vdata.metaTypes.length == 0) return;
        String slotType = proto >= 766 ? "Slot" : "slot";
        for (int guard = 0; guard < 32; guard++) {
            int key = p.u8();
            if (key == 0xFF) return;
            int t = p.varInt();
            String type = t >= 0 && t < vdata.metaTypes.length ? vdata.metaTypes[t] : null;
            if (type == null) return;
            switch (type) {
                case "byte": p.u8(); break;
                case "boolean": p.u8(); break;
                case "int": case "pose": case "direction": case "block_state": case "optional_block_state": p.varInt(); break;
                case "long": readVarLong(p); break;
                case "float": p.f32(); break;
                case "string": p.str(); break;
                case "component":
                    if (proto >= 765) skipAnonNbt(p); else p.str();
                    break;
                case "optional_component":
                    if (p.bool()) {
                        if (proto >= 765) skipAnonNbt(p); else p.str();
                    }
                    break;
                case "item_stack": {
                    Inv.Item it = Inv.fromSlot(vdata.def.read(slotType, p), vdata);
                    if (it != null) e.item = vdata.itemId(it.id);
                    return;
                }
                default:
                    return; // a type we don't need to understand; the item stack would have come first
            }
        }
    }

    private static void skipAnonNbt(Buf.In p) throws IOException {
        int t = p.u8();
        if (t != 0) MapData.skipNbt(p, t);
    }

    private boolean handleEntity(int pid, Buf.In p) throws IOException {
        if (pid == id(Proto.P_C_SPAWN_ENTITY) || pid == id(Proto.P_C_SPAWN_PLAYER) || pid == id(Proto.P_C_SPAWN_LIVING)) {
            int eid = p.varInt();
            UUID u = p.uuid();
            int type = pid == id(Proto.P_C_SPAWN_PLAYER) ? -2 : p.varInt();
            char kind = type == -2 ? 'p' : kindOf(type);
            if (type == playerType && type >= 0) kind = 'p';
            if (eid == selfEntityId) return true;
            World.Ent e = new World.Ent(eid, kind, kind == 'p' ? "player" : nameOf(type), u, p.f64(), p.f64(), p.f64());
            World.add(e);
            if (kind == 'p') onPlayerSeen(e);
        } else if (pid == id(Proto.P_C_ENTITY_METADATA)) {
            if (GameState.open) readItemMetadata(p);
        } else if (pid == id(Proto.P_C_ENTITY_DESTROY)) {
            int n = p.varInt();
            for (int i = 0; i < n; i++) {
                World.Ent e = World.remove(p.varInt());
                if (e != null && e.kind == 'p') {
                    if (cfg.visualRange) Hub.log(World.label(e) + " left visual range");
                    emitMod("playerLeave", "name", World.label(e));
                }
            }
            updateNearest();
        } else if (pid == id(Proto.P_C_REL_MOVE) || pid == id(Proto.P_C_MOVE_LOOK)) {
            int eid = p.varInt();
            World.moveBy(eid, (short) p.u16() / 4096.0, (short) p.u16() / 4096.0, (short) p.u16() / 4096.0);
            World.Ent e = World.get(eid);
            if (e != null && e.kind == 'p') checkPlayers();
        } else if (pid == id(Proto.P_C_ENTITY_TELEPORT) || pid == id(Proto.P_C_ENTITY_SYNC)) {
            int eid = p.varInt();
            World.moveTo(eid, p.f64(), p.f64(), p.f64());
            World.Ent e = World.get(eid);
            if (e != null && e.kind == 'p') checkPlayers();
        } else if (pid == id(Proto.P_C_RESPAWN)) {
            World.clearEntities(); // changed world
            nearestPlayer = -1;
            if (!legacy()) {
                try {
                    onDimension(p);
                } catch (IOException ignored) {
                }
            }
            return false;
        } else if (pid == id(Proto.P_C_MAP_CHUNK)) {
            if (MapData.supported) {
                try {
                    MapData.readChunk(p, proto, World.selfY);
                } catch (IOException e) {
                    MapData.status = "Couldn't read a chunk: " + e.getMessage();
                }
            }
        } else if (pid == id(Proto.P_C_UPDATE_LIGHT)) {
            if (MapData.keepLight) MapData.readLightUpdate(p);
        } else if (pid == id(Proto.P_C_UPDATE_TIME)) {
            GameState.worldAge = p.i64();
            GameState.worldAgeAt = System.currentTimeMillis();
            long t;
            if (proto >= 775) { // 26.1: a list of world clocks (id, total ticks, ...); the first is the day clock
                t = GameState.dayTime;
                if (p.varInt() > 0) {
                    p.varInt(); // clock id
                    t = readVarLong(p);
                }
                GameState.dayTicks = true;
            } else {
                t = p.i64();
                GameState.dayTicks = proto >= 768 ? p.bool() : t >= 0; // older: negative = daylight cycle off
                t = Math.abs(t);
            }
            GameState.dayTime = t;
            GameState.dayTimeAt = System.currentTimeMillis();
        } else if (pid == id(Proto.P_C_EXPERIENCE)) {
            GameState.xpBar = p.f32();
            GameState.xpLevel = p.varInt();
        } else if (pid == id(Proto.P_C_GAME_STATE)) {
            int reason = p.u8();
            float v = p.f32();
            if (reason == 1) GameState.rain = 1;
            else if (reason == 2) GameState.rain = 0;
            else if (reason == 7) GameState.rain = Math.max(0, Math.min(1, v));
            else if (reason == 3) GameState.gameMode = (int) v;
        } else if (pid == id(Proto.P_C_BREAK_ANIM)) {
            p.varInt(); // who is breaking it
            long v = p.i64();
            int bx = (int) (v >> 38), by = (int) (v << 52 >> 52), bz = (int) (v << 26 >> 38);
            int stage = (byte) p.u8();
            if (stage >= 0 && stage <= 9) GameState.breaking.put(GameState.key(bx, by, bz), stage);
            else GameState.breaking.remove(GameState.key(bx, by, bz));
        } else if (pid == id(Proto.P_C_UNLOAD_CHUNK)) {
            int cz = p.i32();
            int cx = p.i32();
            MapData.unload(cx, cz);
        } else if (pid == id(Proto.P_C_CHUNK_BATCH_FINISHED)) {
            // Only ask for more chunks when the map is on (saves data otherwise).
            if (cfg.wantChunks()) send(new Buf(id(Proto.P_S_CHUNK_BATCH_RECEIVED)).f32(20f));
            else batchPending = true;
        } else if (pid == id(Proto.P_C_PLAYER_INFO)) {
            readPlayerInfo(p);
        } else if (pid == id(Proto.P_C_PLAYER_REMOVE)) {
            int n = p.varInt();
            for (int i = 0; i < n; i++) World.removeName(p.uuid());
        } else if (pid == id(Proto.P_C_PLAYER_CHAT)) {
            readPlayerChat(p);
        } else if (pid == id(Proto.P_C_PROFILELESS_CHAT)) {
            readProfilelessChat(p);
        } else if (pid == id(Proto.P_C_BLOCK_CHANGE)) {
            long v = p.i64();
            int bx = (int) (v >> 38), by = (int) (v << 52 >> 52), bz = (int) (v << 26 >> 38);
            MapData.setBlock(bx, by, bz, p.varInt());
        } else if (pid == id(Proto.P_C_MULTI_BLOCK)) {
            long v = p.i64();
            int sx = (int) (v >> 42), sz = (int) (v << 22 >> 42), sy = (int) (v << 44 >> 44);
            int n = p.varInt();
            for (int i = 0; i < n; i++) {
                long rec = readVarLong(p);
                int st = (int) (rec >>> 12);
                int lx = (int) ((rec >> 8) & 15), lz = (int) ((rec >> 4) & 15), ly = (int) (rec & 15);
                MapData.setBlock(sx * 16 + lx, sy * 16 + ly, sz * 16 + lz, st);
            }
        } else if (pid == id(Proto.P_C_OPEN_WINDOW) && vdata != null) {
            java.util.Map<String, Object> m;
            try {
                m = vdata.readPacket("open_window", p);
            } catch (IOException e) {
                logOnce("Couldn't read the container title: " + e.getMessage());
                return true;
            }
            Object t = m.get("windowTitle");
            Inv.title = t instanceof String && ((String) t).startsWith("{") ? Text.fromJson((String) t) : Text.strip(Text.flatten(t));
            Inv.openId = Inv.num(m.get("windowId"));
            Inv.slots = new java.util.ArrayList<Inv.Item>();
            Inv.status = "";
            Inv.version++;
            Hub.log("Opened " + (Inv.title.isEmpty() ? "container" : Inv.title));
        } else if (pid == id(Proto.P_C_WINDOW_ITEMS) && vdata != null) {
            // read item by item so one unusual item can't lose the whole inventory
            int wid = proto >= 768 ? p.varInt() : p.u8();
            int stateId = p.varInt();
            int n = p.varInt();
            java.util.List<Inv.Item> list = new java.util.ArrayList<Inv.Item>();
            String slotType = proto >= 766 ? "Slot" : "slot";
            String failed = null;
            for (int i = 0; i < n && i < 400; i++) {
                if (failed != null) {
                    list.add(null);
                    continue;
                }
                try {
                    Inv.Item it = Inv.fromSlot(vdata.def.read(slotType, p), vdata);
                    if (it != null && !Inv.plausible(it, vdata)) throw new IOException("unexpected item data");
                    list.add(it);
                } catch (IOException e) {
                    failed = "slot " + i + ": " + e.getMessage();
                    list.add(null);
                }
            }
            if (failed != null) logOnce("Some items couldn't be read (" + failed + ") - they show as empty");
            Inv.stateId = stateId;
            if (wid == 0) {
                if (Inv.inventory.isEmpty()) Hub.log("Inventory received (" + countItems(list) + " stacks)");
                Inv.inventory = list;
            } else if (wid == Inv.openId) {
                Inv.slots = list;
                mirrorPlayerSlots(list);
                Hub.log("Container contents: " + countItems(list.subList(0, Math.max(0, list.size() - 36))) + " stacks");
            }
            Inv.version++;
        } else if (pid == id(Proto.P_C_SET_SLOT) && vdata != null) {
            java.util.Map<String, Object> m;
            try {
                m = vdata.readPacket("set_slot", p);
            } catch (IOException e) {
                logOnce("An item update couldn't be read: " + e.getMessage());
                return true;
            }
            int wid = Inv.num(m.get("windowId")), slot = Inv.num(m.get("slot"));
            Inv.stateId = Inv.num(m.get("stateId"));
            java.util.List<Inv.Item> target = wid == 0 ? Inv.inventory : wid == Inv.openId ? Inv.slots : null;
            if (target != null && slot >= 0 && slot < target.size()) {
                java.util.List<Inv.Item> copy = new java.util.ArrayList<Inv.Item>(target);
                copy.set(slot, Inv.fromSlot(m.get("item"), vdata));
                if (wid == 0) Inv.inventory = copy;
                else {
                    Inv.slots = copy;
                    mirrorPlayerSlots(copy);
                }
                Inv.version++;
            }
        } else if (pid == id(Proto.P_C_HELD_SLOT)) {
            Inv.held = Math.max(0, Math.min(8, p.u8()));
            Inv.version++;
        } else if (pid == id(Proto.P_C_CLOSE_WINDOW)) {
            Inv.openId = -1;
            Inv.version++;
        } else {
            return false;
        }
        return true;
    }

    private final java.util.HashSet<String> logged = new java.util.HashSet<String>();

    private void logOnce(String msg) {
        if (logged.add(msg)) Hub.log(msg);
    }

    private static int countItems(java.util.List<Inv.Item> l) {
        int n = 0;
        for (Inv.Item it : l) if (it != null) n++;
        return n;
    }

    /** Doors, trapdoors, gates, buttons and levers near the player, nearest first: {block, name, distance}. */
    static java.util.List<Object[]> nearbyUsable(int range) {
        java.util.List<Object[]> out = new java.util.ArrayList<Object[]>();
        ProtoDef.VersionData vd = Hub.versionData;
        if (vd == null || !World.inWorld) return out;
        int px = (int) Math.floor(World.selfX), py = (int) Math.floor(World.selfY), pz = (int) Math.floor(World.selfZ);
        for (int x = px - range; x <= px + range; x++)
            for (int y = py - range; y <= py + range; y++)
                for (int z = pz - range; z <= pz + range; z++) {
                    int st = MapData.stateAt(x, y, z);
                    if (st <= 0) continue;
                    int t = Shapes.type(st);
                    if (t != Shapes.DOOR && t != Shapes.TRAPDOOR && t != Shapes.GATE && t != Shapes.BUTTON && t != Shapes.LEVER) continue;
                    if (t == Shapes.DOOR && "upper".equals(Shapes.prop(st, "half"))) continue; // list each door once
                    int[] b = {x, y, z, 1};
                    out.add(new Object[]{b, vd.blockName(st), World.reach(b), st});
                }
        java.util.Collections.sort(out, new java.util.Comparator<Object[]>() {
            @Override
            public int compare(Object[] a, Object[] b) {
                return Double.compare((Double) a[2], (Double) b[2]);
            }
        });
        return out;
    }

    /** Container blocks within range of the player, nearest first: {x, y, z} plus name. */
    static java.util.List<Object[]> nearbyContainers(int range) {
        java.util.List<Object[]> out = new java.util.ArrayList<Object[]>();
        ProtoDef.VersionData vd = Hub.versionData;
        if (vd == null || !World.inWorld) return out;
        int px = (int) Math.floor(World.selfX), py = (int) Math.floor(World.selfY), pz = (int) Math.floor(World.selfZ);
        for (int x = px - range; x <= px + range; x++)
            for (int y = py - range; y <= py + range; y++)
                for (int z = pz - range; z <= pz + range; z++) {
                    int st = MapData.stateAt(x, y, z);
                    if (st <= 0) continue;
                    String name = vd.blockName(st);
                    if (!isContainer(name)) continue;
                    int[] b = {x, y, z, 1};
                    out.add(new Object[]{b, name, World.reach(b)});
                }
        java.util.Collections.sort(out, new java.util.Comparator<Object[]>() {
            @Override
            public int compare(Object[] a, Object[] b) {
                return Double.compare((Double) a[2], (Double) b[2]);
            }
        });
        return out;
    }

    static boolean isContainer(String n) {
        return n.endsWith("Chest") || n.endsWith("Barrel") || n.endsWith("Shulker Box") || n.equals("Hopper") || n.equals("Dispenser")
                || n.equals("Dropper") || n.endsWith("Furnace") || n.equals("Smoker") || n.equals("Crafter") || n.equals("Brewing Stand")
                || n.endsWith("Chest Boat") || n.equals("Decorated Pot") || n.equals("Chiseled Bookshelf");
    }

    /** Face of the block that points towards the player (what a real click would hit). */
    /**
     * The face to click and the point on it, {face, cx, cy, cz} in block-local coordinates: the centre of that
     * face of the block's real outline. If the requested face can't be seen from the eye, the visible face
     * closest to the line of sight is used instead.
     */
    static float[] facePoint(int bx, int by, int bz, int face, double ex, double ey, double ez) {
        float[][] boxes = Shapes.outline(MapData.stateAt(bx, by, bz));
        float x0 = 1, y0 = 1, z0 = 1, x1 = 0, y1 = 0, z1 = 0;
        for (float[] q : boxes) {
            x0 = Math.min(x0, q[0]); y0 = Math.min(y0, q[1]); z0 = Math.min(z0, q[2]);
            x1 = Math.max(x1, q[3]); y1 = Math.max(y1, q[4]); z1 = Math.max(z1, q[5]);
        }
        if (x1 <= x0 || y1 <= y0 || z1 <= z0) { x0 = y0 = z0 = 0; x1 = y1 = z1 = 1; }
        double lx = ex - bx, ly = ey - by, lz = ez - bz; // eye in block-local coordinates
        boolean[] vis = {ly < y0, ly > y1, lz < z0, lz > z1, lx < x0, lx > x1};
        if (face < 0 || face > 5 || !vis[face]) {
            double cx = (x0 + x1) / 2 - lx, cy = (y0 + y1) / 2 - ly, cz = (z0 + z1) / 2 - lz; // eye -> centre
            double[] score = {cy, -cy, cz, -cz, cx, -cx}; // face normals: down, up, north, south, west, east
            int best = -1;
            for (int f = 0; f < 6; f++) if (vis[f] && (best < 0 || score[f] > score[best])) best = f;
            face = best < 0 ? 1 : best;
        }
        float mx = (x0 + x1) / 2, my = (y0 + y1) / 2, mz = (z0 + z1) / 2;
        switch (face) {
            case 0: return new float[]{0, mx, y0, mz};
            case 1: return new float[]{1, mx, y1, mz};
            case 2: return new float[]{2, mx, my, z0};
            case 3: return new float[]{3, mx, my, z1};
            case 4: return new float[]{4, x0, my, mz};
            default: return new float[]{5, x1, my, mz};
        }
    }

    static int faceToward(int[] b) {
        double dx = World.selfX - (b[0] + 0.5), dy = World.selfY + 1.62 - (b[1] + 0.5), dz = World.selfZ - (b[2] + 0.5);
        double ax = Math.abs(dx), ay = Math.abs(dy), az = Math.abs(dz);
        if (ay >= ax && ay >= az) return dy > 0 ? 1 : 0;
        if (ax >= az) return dx > 0 ? 5 : 4;
        return dz > 0 ? 3 : 2;
    }

    private static long readVarLong(Buf.In p) throws IOException {
        long v = 0;
        int shift = 0, b;
        do {
            b = p.u8();
            v |= (long) (b & 0x7F) << shift;
            shift += 7;
        } while ((b & 0x80) != 0 && shift < 70);
        return v;
    }

    // ----- interacting with blocks and containers -----

    private static long packPos(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    private void writeContainerId(Buf b, int id) {
        if (proto >= 768) b.varInt(id);
        else b.u8(id);
    }

    /** Right-clicks a block (opens chests, barrels, shulkers, doors...). face: 0 down 1 up 2 north 3 south 4 west 5 east. */
    void useBlock(final int bx, final int by, final int bz, final int face) {
        final ScheduledExecutorService t = ticker;
        if (t == null || state != PLAY || id(Proto.P_S_USE_ITEM_ON) < 0) return;
        t.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    // Like a real client: aim at a point on a face we can actually see, and report that exact
                    // point (anticheats drop clients whose click point is inside the block or on a hidden face).
                    float[] hit;
                    synchronized (Bot.this) {
                        hit = facePoint(bx, by, bz, face, x, y + 1.62, z);
                        double ex = x, ey = y + 1.62, ez = z;
                        double dx = bx + hit[1] - ex, dy = by + hit[2] - ey, dz = bz + hit[3] - ez;
                        yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                        pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
                        lookYaw = yaw;
                        lookPitch = pitch;
                    }
                    sendMove(true, true, onGroundM);
                    Buf b = new Buf(id(Proto.P_S_USE_ITEM_ON)).varInt(0).i64(packPos(bx, by, bz)).varInt((int) hit[0])
                            .f32(hit[1]).f32(hit[2]).f32(hit[3]).bool(false);
                    if (proto >= 768) b.bool(false);
                    b.varInt(++useSequence);
                    send(b);
                    send(new Buf(id(Proto.P_S_SWING)).varInt(0));
                    Inv.status = "Opening...";
                    Inv.version++;
                } catch (IOException ignored) {
                }
            }
        });
    }

    /** An open container's last 36 slots are your inventory (27 main + 9 hotbar): keep window 0 in step, like a real client. */
    private static void mirrorPlayerSlots(java.util.List<Inv.Item> win) {
        java.util.List<Inv.Item> inv = Inv.inventory;
        if (win.size() < 36 || inv.size() < 45) return;
        java.util.List<Inv.Item> copy = new java.util.ArrayList<Inv.Item>(inv);
        for (int k = 0; k < 36; k++) copy.set(9 + k, win.get(win.size() - 36 + k));
        Inv.inventory = copy;
    }

    /** Clicks a slot in the open container. mode 1 + button 0 = shift-click (moves the stack across). */
    /** Clicks a slot of your own inventory (window 0; 9-35 main, 36-44 hotbar). mode 2 + button n = swap with hotbar n. */
    void clickInventory(final int slot, final int button, final int mode) {
        final ScheduledExecutorService t = ticker;
        if (t == null || state != PLAY || Inv.openId >= 0) return;
        t.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Buf b = new Buf(id(Proto.P_S_WINDOW_CLICK));
                    writeContainerId(b, 0);
                    b.varInt(Inv.stateId).u16(slot).u8(button).varInt(mode).varInt(0);
                    if (proto >= 770) b.bool(false);
                    else if (proto >= 766) b.varInt(0);
                    else b.bool(false);
                    send(b);
                } catch (IOException ignored) {
                }
            }
        });
    }

    void clickSlot(final int slot, final int button, final int mode) {
        final ScheduledExecutorService t = ticker;
        final int wid = Inv.openId;
        if (t == null || state != PLAY || wid < 0) return;
        t.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Buf b = new Buf(id(Proto.P_S_WINDOW_CLICK));
                    writeContainerId(b, wid);
                    b.varInt(Inv.stateId).u16(slot).u8(button).varInt(mode).varInt(0); // no predicted changes
                    // carried item: empty (server works out the result and sends the new contents)
                    if (proto >= 770) b.bool(false);
                    else if (proto >= 766) b.varInt(0);
                    else b.bool(false);
                    send(b);
                } catch (IOException ignored) {
                }
            }
        });
    }

    void closeContainer() {
        final ScheduledExecutorService t = ticker;
        final int wid = Inv.openId;
        Inv.openId = -1;
        Inv.version++;
        if (t == null || wid < 0) return;
        t.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Buf b = new Buf(id(Proto.P_S_CLOSE_WINDOW));
                    writeContainerId(b, wid);
                    send(b);
                } catch (IOException ignored) {
                }
            }
        });
    }

    // ----- left / right click -----

    /** Mining progress 0..1 (or -1 when not mining), for the screen. */
    static volatile float miningProgress = -1;
    private volatile int[] miningBlock;
    private ScheduledFutureHolder miningTask = new ScheduledFutureHolder();

    private static final class ScheduledFutureHolder {
        java.util.concurrent.ScheduledFuture<?> f;
    }

    private void lookAt(double tx, double ty, double tz) throws IOException {
        synchronized (this) {
            double dx = tx - x, dy = ty - (y + 1.62), dz = tz - z;
            yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
            lookYaw = yaw;
            lookPitch = pitch;
        }
        sendMove(true, true, onGroundM);
    }

    /** Selects a hotbar slot (0-8). */
    void selectSlot(final int slot) {
        Inv.held = slot;
        Inv.version++;
        final ScheduledExecutorService t = ticker;
        if (t == null || state != PLAY || id(Proto.P_S_HELD_SLOT) < 0) return;
        t.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    send(new Buf(id(Proto.P_S_HELD_SLOT)).u16(slot));
                } catch (IOException ignored) {
                }
            }
        });
    }

    /** How many ticks it takes to break a block with what's in your hand (vanilla formula, no enchantments). -1 = unbreakable. */
    int breakTicks(int state) {
        ProtoDef.VersionData vd = vdata;
        if (vd == null) return -1;
        int bi = vd.blockIndex(state);
        if (bi < 0 || bi >= vd.hardness.length) return -1;
        double hard = vd.hardness[bi];
        if (hard < 0) return -1;
        if (hard == 0) return 0;
        Inv.Item held = Inv.heldItem();
        String item = held == null ? "" : held.name;
        String mat = vd.material[bi];
        double speed = 1;
        boolean right = mat.contains("pickaxe") && item.endsWith("Pickaxe") || mat.contains("axe") && !mat.contains("pickaxe") && item.endsWith(" Axe")
                || mat.contains("shovel") && item.endsWith("Shovel") || mat.contains("hoe") && item.endsWith("Hoe");
        if (right) {
            if (item.startsWith("Wooden")) speed = 2;
            else if (item.startsWith("Stone")) speed = 4;
            else if (item.startsWith("Iron")) speed = 6;
            else if (item.startsWith("Diamond")) speed = 8;
            else if (item.startsWith("Netherite")) speed = 9;
            else if (item.startsWith("Golden")) speed = 12;
        }
        boolean canHarvest = vd.harvest[bi].length == 0;
        if (!canHarvest && held != null) for (int h : vd.harvest[bi]) if (h == held.id) canHarvest = true;
        if (!onGroundM && manual || inWater) speed /= 5;
        double perTick = speed / hard / (canHarvest ? 30 : 100);
        return perTick >= 1 ? 0 : (int) Math.ceil(1 / perTick);
    }

    /** Starts breaking a block (like holding left click). Finishes by itself after the right time. */
    String startMining(final int[] b) {
        final ScheduledExecutorService t = ticker;
        if (t == null || state != PLAY || id(Proto.P_S_BLOCK_DIG) < 0) return "Not connected";
        if (World.reach(b) > 4.5) return "Too far - get within 4.5 blocks";
        int st = MapData.stateAt(b[0], b[1], b[2]);
        if (st <= 0) return "Nothing there";
        final int ticks = breakTicks(st);
        if (ticks < 0) return "That block can't be broken";
        stopMining(false);
        miningBlock = b;
        miningProgress = 0;
        final int face = faceToward(b);
        final long started = System.currentTimeMillis();
        t.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    lookAt(b[0] + 0.5, b[1] + 0.5, b[2] + 0.5);
                    send(new Buf(id(Proto.P_S_BLOCK_DIG)).varInt(0).i64(packPos(b[0], b[1], b[2])).u8(face).varInt(++useSequence));
                    send(new Buf(id(Proto.P_S_SWING)).varInt(0));
                    if (ticks == 0) {
                        miningProgress = -1;
                        miningBlock = null;
                    }
                } catch (IOException ignored) {
                }
            }
        });
        if (ticks == 0) return "Broken";
        miningTask.f = t.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                int[] mb = miningBlock;
                if (mb == null) return;
                long elapsed = System.currentTimeMillis() - started;
                float prog = Math.min(1f, elapsed / (ticks * 50f));
                miningProgress = prog;
                try {
                    send(new Buf(id(Proto.P_S_SWING)).varInt(0)); // arm keeps swinging while mining, like the real client
                    if (prog >= 1f && elapsed >= ticks * 50L + 100) {
                        send(new Buf(id(Proto.P_S_BLOCK_DIG)).varInt(2).i64(packPos(mb[0], mb[1], mb[2])).u8(face).varInt(++useSequence));
                        miningBlock = null;
                        miningProgress = -1;
                        java.util.concurrent.ScheduledFuture<?> f = miningTask.f;
                        if (f != null) f.cancel(false);
                    }
                } catch (IOException ignored) {
                }
            }
        }, 200, 200, TimeUnit.MILLISECONDS);
        return String.format(Locale.US, "Mining... about %.1f s", ticks / 20.0);
    }

    /** Stops mining (cancel = tell the server you let go). */
    void stopMining(boolean cancel) {
        java.util.concurrent.ScheduledFuture<?> f = miningTask.f;
        if (f != null) f.cancel(false);
        final int[] mb = miningBlock;
        miningBlock = null;
        miningProgress = -1;
        final ScheduledExecutorService t = ticker;
        if (cancel && mb != null && t != null && state == PLAY) {
            t.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        send(new Buf(id(Proto.P_S_BLOCK_DIG)).varInt(1).i64(packPos(mb[0], mb[1], mb[2])).u8(faceToward(mb)).varInt(++useSequence));
                    } catch (IOException ignored) {
                    }
                }
            });
        }
    }

    boolean isMining() {
        return miningBlock != null;
    }

    /** Left click on an entity. */
    String attack(final World.Dot target) {
        final ScheduledExecutorService t = ticker;
        if (t == null || state != PLAY || id(Proto.P_S_USE_ENTITY) < 0) return "Not connected";
        if (proto > 774) return "Attacking isn't supported on this server version yet";
        if (target.dist > 3.5) return "Too far to hit - get within 3 blocks";
        t.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    lookAt(World.selfX + target.dx, World.selfY + target.dy + 1.0, World.selfZ + target.dz);
                    send(new Buf(id(Proto.P_S_USE_ENTITY)).varInt(target.id).varInt(1).bool(inSneak));
                    send(new Buf(id(Proto.P_S_SWING)).varInt(0));
                } catch (IOException ignored) {
                }
            }
        });
        return "Hit " + target.label;
    }

    /** Right click on an entity (trade, ride, etc.). */
    String interact(final World.Dot target) {
        final ScheduledExecutorService t = ticker;
        if (t == null || state != PLAY || id(Proto.P_S_USE_ENTITY) < 0) return "Not connected";
        if (proto > 774) return "Not supported on this server version yet";
        if (target.dist > 3.5) return "Too far - get within 3 blocks";
        t.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    lookAt(World.selfX + target.dx, World.selfY + target.dy + 1.0, World.selfZ + target.dz);
                    send(new Buf(id(Proto.P_S_USE_ENTITY)).varInt(target.id).varInt(0).varInt(0).bool(inSneak));
                    send(new Buf(id(Proto.P_S_SWING)).varInt(0));
                } catch (IOException ignored) {
                }
            }
        });
        return "Used " + target.label;
    }

    /** Right click in the air with the held item (eat, drink, throw...). */
    void useItem() {
        final ScheduledExecutorService t = ticker;
        if (t == null || state != PLAY || id(Proto.P_S_USE_ITEM) < 0) return;
        t.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Buf b = new Buf(id(Proto.P_S_USE_ITEM)).varInt(0).varInt(++useSequence);
                    synchronized (Bot.this) {
                        if (proto >= 767) b.f32(yaw).f32(pitch);
                    }
                    send(b);
                    send(new Buf(id(Proto.P_S_SWING)).varInt(0));
                } catch (IOException ignored) {
                }
            }
        });
    }

    boolean containersSupported() {
        return vdata != null && id(Proto.P_S_WINDOW_CLICK) >= 0;
    }

    private static final java.util.regex.Pattern CHAT_LINE = java.util.regex.Pattern.compile("^<([A-Za-z0-9_]{1,16})> (.*)$");

    private void emitChat(String sender, String message, String raw, boolean system) {
        try {
            JSONObject o = new JSONObject().put("message", message).put("raw", raw).put("system", system);
            if (sender != null) o.put("sender", sender);
            NonIntel.chat(sender, message, raw, system);
            ModEngine.emit("chat", o);
        } catch (org.json.JSONException ignored) {
        }
    }

    private static void emitMod(String event, String key, Object value) {
        NonIntel.botEvent(event, value == null ? null : String.valueOf(value));
        try {
            ModEngine.emit(event, new JSONObject().put(key, value));
        } catch (org.json.JSONException ignored) {
        }
    }

    String playerName() {
        return playerName;
    }

    /**
     * Whether the server enforces secure (signed) chat. Then a command with a message in it (/w, /msg) sent unsigned,
     * as this bot sends commands, gets you kicked; the Naughty or Nice add-on won't send whispers there.
     */
    volatile boolean secureChat;

    /** The rest of the 1.20.5+ login packet after the dimension, up to its last field: "enforces secure chat". */
    private void readSecureChat(Buf.In p) {
        secureChat = false;
        if (proto < 766) return;
        try {
            p.i64(); // hashed seed
            p.u8(); // game mode
            p.u8(); // previous game mode
            p.bool(); // debug
            p.bool(); // flat
            if (p.bool()) { // death location
                p.str();
                p.i64();
            }
            p.varInt(); // portal cooldown
            if (proto >= 768) p.varInt(); // sea level
            secureChat = p.bool();
            if (secureChat) Hub.log("This server enforces secure chat: whispers can't be sent from the app here (it would get you kicked).");
        } catch (IOException | RuntimeException e) {
            secureChat = false;
        }
    }

    /** Reads the dimension part of the login/respawn packet. */
    private void onDimension(Buf.In p) throws IOException {
        String name;
        if (proto >= 766) {
            p.varInt();
            name = p.str();
        } else {
            String type = p.str();
            name = p.str();
            if (!name.contains("nether") && type.contains("nether")) name = type;
        }
        World.dimension = name;
        MapData.setDimension(name);
    }

    private void onPlayerSeen(World.Ent e) throws SafetyLogout {
        String name = World.label(e);
        boolean friend = cfg.friendSet().contains(name);
        double d = distanceTo(e);
        NonIntel.seen(name, e.x, e.y, e.z, d, World.dimension);
        try {
            ModEngine.emit("playerEnter", new JSONObject().put("name", name).put("distance", Math.round(d)).put("friend", friend));
        } catch (org.json.JSONException ignored) {
        }
        if (cfg.visualRange) {
            String msg = String.format(Locale.US, "%s%s entered visual range (%.0f blocks)", name, friend ? " (friend)" : "", d);
            Hub.log(msg);
            if (!friend) Hub.alert("Player nearby", msg);
        }
        checkPlayers();
    }

    private double distanceTo(World.Ent e) {
        double dx = e.x - World.selfX, dy = e.y - World.selfY, dz = e.z - World.selfZ;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** The skin URL inside a player's "textures" property (base64 JSON), or null. */
    private static String skinUrl(String base64) {
        try {
            String json = new String(android.util.Base64.decode(base64, android.util.Base64.DEFAULT), StandardCharsets.UTF_8);
            JSONObject skin = new JSONObject(json).optJSONObject("textures");
            skin = skin == null ? null : skin.optJSONObject("SKIN");
            return skin == null ? null : skin.optString("url", null);
        } catch (Exception e) {
            return null;
        }
    }

    private void readPlayerInfo(Buf.In p) throws IOException {
        if (legacy()) {
            int action = p.varInt();
            int n = p.varInt();
            for (int i = 0; i < n; i++) {
                UUID u = p.uuid();
                if (action == 0) {
                    World.putName(u, p.str());
                    int props = p.varInt();
                    for (int k = 0; k < props; k++) {
                        p.str();
                        p.str();
                        if (p.bool()) p.str();
                    }
                    p.varInt();
                    p.varInt();
                    if (p.bool()) p.str();
                } else if (action == 1 || action == 2) {
                    p.varInt();
                } else if (action == 3) {
                    if (p.bool()) p.str();
                } else if (action == 4) {
                    World.removeName(u);
                }
            }
            return;
        }
        int actions = p.u8();
        int n = p.varInt();
        for (int i = 0; i < n; i++) {
            UUID u = p.uuid();
            if ((actions & 0x01) != 0) { // add player
                World.putName(u, p.str());
                int props = p.varInt();
                for (int k = 0; k < props; k++) {
                    String key = p.str(), value = p.str();
                    if (p.bool()) p.str();
                    if (key.equals("textures")) World.putSkin(u, skinUrl(value)); // for the Play screen's player models
                }
            }
            if ((actions & 0x02) != 0 && p.bool()) { // chat session
                p.uuid();
                p.i64();
                p.byteArray();
                p.byteArray();
            }
            if ((actions & 0x04) != 0) { // game mode (ours drives the Play HUD: no hearts in creative)
                int gm = p.varInt();
                if (playerName != null && playerName.equals(World.nameOf(u))) GameState.gameMode = gm;
            }
            if ((actions & 0x08) != 0) p.varInt(); // listed
            if ((actions & 0x10) != 0) p.varInt(); // latency
            if ((actions & 0x20) != 0 && p.bool()) { // display name
                if (proto >= 765) Text.fromNbt(p);
                else p.str();
            }
            if ((actions & 0x40) != 0) p.varInt(); // list order
            if ((actions & 0x80) != 0) p.bool(); // show hat
        }
    }

    private void readPlayerChat(Buf.In p) throws IOException {
        if (proto >= 770) p.varInt(); // global index
        UUID sender = p.uuid();
        p.varInt();
        if (p.bool()) p.bytes(256);
        String msg = p.str();
        String name = World.nameOf(sender);
        // 1.20.5+: the rest says what kind of message it is. Whispers (/w, /msg) are kinds of their own: vanilla numbers
        // its chat kinds chat, emote, msg incoming (3), msg outgoing (4)..., and an outgoing one names who it went to.
        int kind = -1;
        String target = null;
        if (proto >= 766) {
            try {
                p.i64(); // timestamp
                p.i64(); // salt
                int prev = p.varInt();
                for (int i = 0; i < prev; i++) if (p.varInt() == 0) p.bytes(256);
                if (p.bool()) Text.fromNbt(p); // unsigned content
                if (p.varInt() == 2) { // partly filtered: a bit set
                    int longs = p.varInt();
                    for (int i = 0; i < longs; i++) p.i64();
                }
                kind = p.varInt();
                if (kind != 0) {
                    Text.fromNbt(p); // sender's display name
                    if (p.bool()) target = Text.strip(Text.fromNbt(p)).trim();
                }
            } catch (IOException | RuntimeException e) {
                kind = -1;
            }
        }
        // Handed on as "X whispers to you: …" / "You whisper to X: …", which the Naughty or Nice add-on reads as DMs.
        if (name != null && kind == 3 && target == null) {
            Hub.chat(name + " whispers to you: " + msg);
            emitChat(null, name + " whispers to you: " + msg, name + " whispers to you: " + msg, false);
            return;
        }
        if (kind == 4 && target != null && !target.isEmpty()) {
            Hub.chat("You whisper to " + target + ": " + msg);
            emitChat(null, "You whisper to " + target + ": " + msg, "You whisper to " + target + ": " + msg, false);
            return;
        }
        Hub.chat((name != null ? "<" + name + "> " : "<?> ") + msg);
        emitChat(name, msg, (name != null ? "<" + name + "> " : "") + msg, false);
    }

    /**
     * Chat that isn't signed (a server's /say, or a whisper whose text wasn't signed, as on servers without secure
     * chat): the text, its kind, who it's from and (for whispers you sent) who to.
     */
    private void readProfilelessChat(Buf.In p) throws IOException {
        String msg = Text.strip(proto >= 765 ? Text.fromNbt(p) : Text.fromJson(p.str())).trim();
        int kind = p.varInt();
        // Before 1.20.5 the kind is the registry id itself; after, it's the id plus one (0 would be a kind spelled out).
        if (proto < 766) kind++;
        String name = null, target = null;
        if (kind != 0) {
            name = Text.strip(proto >= 765 ? Text.fromNbt(p) : Text.fromJson(p.str())).trim();
            if (p.bool()) target = Text.strip(proto >= 765 ? Text.fromNbt(p) : Text.fromJson(p.str())).trim();
        }
        String line;
        if (kind == 3 && name != null && !name.isEmpty() && target == null) line = name + " whispers to you: " + msg;
        else if (kind == 4 && target != null && !target.isEmpty()) line = "You whisper to " + target + ": " + msg;
        else if (kind == 1 && name != null && !name.isEmpty()) line = "<" + name + "> " + msg;
        else line = (name != null && !name.isEmpty() ? "[" + name + "] " : "") + msg;
        if (line.length() > 400) line = line.substring(0, 400) + "...";
        Hub.chat(line);
        if (kind == 1 && name != null && !name.isEmpty()) emitChat(name, msg, line, false);
        else emitChat(null, line, line, true);
    }

    private void updateNearest() {
        World.Dot d = World.nearestThreat(cfg.logIgnoreFriends ? cfg.friendSet() : null);
        nearestPlayer = d == null ? -1 : d.dist;
    }

    private void checkPlayers() throws SafetyLogout {
        updateNearest();
        double d = nearestPlayer;
        int lim = cfg.logoutDistance();
        if (d >= 0 && lim > 0 && d <= lim && World.inWorld) {
            World.Dot t = World.nearestThreat(cfg.logIgnoreFriends ? cfg.friendSet() : null);
            String who = t != null ? t.label : "a player";
            throw new SafetyLogout(String.format(Locale.US, "%s came within %.0f blocks of you at %s", who, d, posText()));
        }
    }

    private synchronized String posText() {
        return String.format(Locale.US, "%.0f, %.0f, %.0f", x, groundY, z);
    }

    /** Sends a chat message, or a command if it starts with "/". Safe to call from any thread. */
    void sendChat(final String text) {
        final ScheduledExecutorService t = ticker;
        if (t == null || state != PLAY) {
            Hub.chat("(not connected - message not sent)");
            return;
        }
        t.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    String m = text.length() > 256 ? text.substring(0, 256) : text;
                    if (legacy()) {
                        send(new Buf(id(Proto.P_S_CHAT_MESSAGE)).str(m));
                    } else if (m.startsWith("/")) {
                        Buf b = new Buf(id(Proto.P_S_CHAT_COMMAND)).str(m.substring(1));
                        if (proto < 766) b.i64(System.currentTimeMillis()).i64(0).varInt(0).varInt(0).bytes(new byte[3]);
                        send(b);
                    } else {
                        Buf b = new Buf(id(Proto.P_S_CHAT_MESSAGE)).str(m).i64(System.currentTimeMillis()).i64(rnd.nextLong())
                                .bool(false).varInt(0).bytes(new byte[3]);
                        if (proto >= 770) b.u8(0); // checksum: 0 = not checked
                        send(b);
                    }
                    Hub.chat("> " + m);
                } catch (IOException e) {
                    Hub.chat("(send failed: " + e.getMessage() + ")");
                }
            }
        });
    }

    private void onTeleport(Buf.In p) throws IOException {
        int tid, flags;
        double nx, ny, nz, tvx = 0, tvy = 0, tvz = 0;
        float nyaw, npitch;
        if (proto >= 768) {
            tid = p.varInt();
            nx = p.f64();
            ny = p.f64();
            nz = p.f64();
            tvx = p.f64();
            tvy = p.f64();
            tvz = p.f64();
            nyaw = p.f32();
            npitch = p.f32();
            flags = p.i32();
        } else {
            nx = p.f64();
            ny = p.f64();
            nz = p.f64();
            nyaw = p.f32();
            npitch = p.f32();
            flags = p.u8();
            tid = p.varInt();
        }
        boolean first;
        double ox0, oy0, oz0;
        synchronized (this) {
            ox0 = x;
            oy0 = y;
            oz0 = z;
            float oldYaw = yaw, oldPitch = pitch;
            x = (flags & 0x01) != 0 ? x + nx : nx;
            y = (flags & 0x02) != 0 ? y + ny : ny;
            z = (flags & 0x04) != 0 ? z + nz : nz;
            yaw = (flags & 0x08) != 0 ? yaw + nyaw : nyaw;
            pitch = (flags & 0x10) != 0 ? pitch + npitch : npitch;
            groundY = y;
            jumping = false;
            if (proto >= 768) {
                // 1.21.2+: the packet carries a velocity too, and flags 0x20/0x40/0x80 mean "add to yours" per axis.
                // Anticheat setbacks often keep your velocity; zeroing it makes the next move wrong again.
                double cx = vxM, cy = vyM, cz = vzM;
                if ((flags & 0x100) != 0) { // rotate the current velocity by the rotation change (vanilla order: pitch, then yaw)
                    double a = Math.toRadians(oldPitch - pitch), c = Math.cos(a), sn = Math.sin(a);
                    double ny2 = cy * c + cz * sn, nz2 = cz * c - cy * sn;
                    cy = ny2;
                    cz = nz2;
                    a = Math.toRadians(oldYaw - yaw);
                    c = Math.cos(a);
                    sn = Math.sin(a);
                    double nx2 = cx * c + cz * sn;
                    nz2 = cz * c - cx * sn;
                    cx = nx2;
                    cz = nz2;
                }
                vxM = (flags & 0x20) != 0 ? cx + tvx : tvx;
                vyM = (flags & 0x40) != 0 ? cy + tvy : tvy;
                vzM = (flags & 0x80) != 0 ? cz + tvz : tvz;
            } else {
                vxM = vyM = vzM = 0;
            }
            lookYaw = yaw;
            lookPitch = pitch;
            first = !havePos;
            World.selfX = x;
            World.selfY = y;
            World.selfZ = z;
            World.selfYaw = yaw;
            havePos = true;
            if (first) nextJumpAt = System.currentTimeMillis() + 5000;
        }
        if (!first) {
            corrections++;
            double moved = Math.sqrt((x - ox0) * (x - ox0) + (y - oy0) * (y - oy0) + (z - oz0) * (z - oz0));
            long now = System.currentTimeMillis();
            if (now - lastCorrectionLog > 3000) {
                lastCorrectionLog = now;
                Hub.log(String.format(Locale.US, "Server corrected your position by %.2f blocks (from %.2f, %.2f, %.2f to %.2f, %.2f, %.2f) %s%s",
                        moved, ox0, oy0, oz0, x, y, z, blocksAround(), manual ? " - movement was rejected" : "")
                        + String.format(Locale.US, " [flags 0x%x, velocity %.3f %.3f %.3f]", flags, tvx, tvy, tvz));
            }
        }
        if (traceSent) System.out.println(String.format(Locale.US, "[TP] id=%d flags=0x%x to %.3f %.3f %.3f yaw %.1f pitch %.1f", tid, flags, x, y, z, yaw, pitch));
        send(new Buf(id(Proto.P_S_TELEPORT_CONFIRM)).varInt(tid));
        sendMove(true, true, false);
        checkPlayers();
        if (first) {
            if (!loadedSent && id(Proto.P_S_PLAYER_LOADED) >= 0) {
                send(new Buf(id(Proto.P_S_PLAYER_LOADED)));
                loadedSent = true;
            }
            Hub.log(String.format(Locale.US, "In the world at %.1f, %.1f, %.1f - AFK mode on", x, y, z));
            Hub.status("Online on " + serverLabel + " as " + playerName, Hub.GREEN);
        }
    }

    /** Sends a movement packet with the current position (and rotation if look=true). */
    private void sendMove(boolean pos, boolean look, boolean onGround) throws IOException {
        Buf b;
        synchronized (this) {
            if (pos && look) b = new Buf(id(Proto.P_S_POSITION_LOOK)).f64(x).f64(y).f64(z).f32(yaw).f32(pitch);
            else if (pos) b = new Buf(id(Proto.P_S_POSITION)).f64(x).f64(y).f64(z);
            else b = new Buf(id(Proto.P_S_LOOK)).f32(yaw).f32(pitch);
        }
        if (proto >= 768) b.u8((onGround ? 1 : 0) | (manual && hCollideM ? 2 : 0));
        else b.bool(onGround);
        if (traceSent) System.out.println(String.format(Locale.US, "[MOVE] t%d %.5f %.5f %.5f yaw %.2f ground=%b hcoll=%b v=%.4f %.4f %.4f in=%d",
                tick, x, y, z, yaw, onGround, hCollideM, vxM, vyM, vzM, lastInputFlags));
        send(b);
    }

    // ===================================================================================
    // 20 ticks per second: jump physics + position heartbeat
    // ===================================================================================

    private int nextAfkDelaySeconds() {
        int base = Math.max(2, cfg.afkSeconds);
        if (!cfg.afkRandom) return base;
        return Math.max(2, (int) Math.round(base * (0.5 + rnd.nextDouble())));
    }

    private void onTick() throws IOException {
        if (state != PLAY) return;
        boolean doAction = false, doMove = false, ground = true, detail, startJump = false;
        synchronized (this) {
            if (!havePos || respawnPending) return;
            tick++;
            long now = System.currentTimeMillis();
            if (!Double.isNaN(goalX)) {
                // steer straight at the goal; stop when there or out of time
                double gdx = goalX - x, gdz = goalZ - z;
                if (Math.sqrt(gdx * gdx + gdz * gdz) < 0.2 || now > goalUntil) {
                    goalX = Double.NaN;
                    inForward = 0;
                    manual = manualBeforeGoal;
                } else {
                    lookYaw = (float) Math.toDegrees(Math.atan2(-gdx, gdz));
                    lookPitch = 0;
                    inForward = 1;
                    inStrafe = 0;
                }
            }
            if (manual) {
                jumping = false;
                ground = physicsStep();
                driveDiag(now);
                groundY = y;
                doMove = true;
                nextJumpAt = now + nextAfkDelaySeconds() * 1000L; // anti-AFK waits while you drive
            } else if (jumping) {
                y += vy;
                vy = (vy - 0.08) * 0.98;
                if (y <= groundY) {
                    y = groundY;
                    jumping = false;
                    ground = true;
                    onGroundM = true;
                    vxM = vzM = 0;
                    vyM = (0 - 0.08) * 0.98; // landed: velocity zeroed, then this tick's gravity, as in vanilla
                } else {
                    ground = false;
                }
                doMove = true;
            } else if (cfg.antiAfk && now >= nextJumpAt) {
                doAction = true;
                if (cfg.afkJump) {
                    jumping = true;
                    startJump = true;
                    groundY = y;
                    vy = 0.42;
                }
                if (cfg.afkSpin) {
                    yaw = (yaw + (rnd.nextFloat() * 120f - 60f)) % 360f;
                    pitch = rnd.nextFloat() * 20f - 5f;
                }
                jumps++;
                nextJumpAt = now + nextAfkDelaySeconds() * 1000L;
            } else {
                // standing still: keep simulating like a real client, so onGround and the downward velocity are
                // already right when you start walking (and you fall if the block under you goes away)
                double px = x, py = y, pz = z;
                ground = physicsStep(false);
                groundY = y;
                if (Math.abs(x - px) + Math.abs(y - py) + Math.abs(z - pz) > 1e-6 || tick % 20 == 0) doMove = true;
            }
            detail = tick % 10 == 0;
            World.selfX = x;
            World.selfY = groundY;
            World.selfZ = z;
            World.selfYaw = yaw;
        }
        // Vanilla order each tick: held keys (player_input), then sprint/sneak state, then the move itself.
        // Anticheats check each move against the keys they already know about, so the move must come last.
        if (manual) {
            sendInputs(true);
            if (inSneak != wasSneak) {
                wasSneak = inSneak;
                if (proto < 768) setSneak(inSneak);
                else if (proto < 771 && id(Proto.P_S_ENTITY_ACTION) >= 0 && selfEntityId != Integer.MIN_VALUE)
                    send(new Buf(id(Proto.P_S_ENTITY_ACTION)).varInt(selfEntityId).varInt(inSneak ? 0 : 1).varInt(0));
                sneakTicks = 0;
            }
            if (inSprint != wasSprint && id(Proto.P_S_ENTITY_ACTION) >= 0 && selfEntityId != Integer.MIN_VALUE) {
                wasSprint = inSprint;
                int act = proto >= 771 ? (inSprint ? 1 : 2) : (inSprint ? 3 : 4);
                send(new Buf(id(Proto.P_S_ENTITY_ACTION)).varInt(selfEntityId).varInt(act).varInt(0));
            }
        } else if (wasSneak || wasSprint || lastInputFlags > 0) {
            if (wasSneak) setSneak(false);
            if (wasSprint && id(Proto.P_S_ENTITY_ACTION) >= 0 && selfEntityId != Integer.MIN_VALUE)
                send(new Buf(id(Proto.P_S_ENTITY_ACTION)).varInt(selfEntityId).varInt(proto >= 771 ? 2 : 4).varInt(0));
            sendInputs(false);
            wasSneak = false;
            wasSprint = false;
        }
        if (doAction) {
            if (cfg.afkSwing) send(new Buf(id(Proto.P_S_SWING)).varInt(0));
            if (cfg.afkSneak) setSneak(true);
            sendMove(true, true, true);
        } else if (doMove && manual) {
            sendMove(true, true, ground);
        } else if (doMove) {
            sendMove(true, false, ground);
        }
        if (sneakTicks > 0 && --sneakTicks == 0 && !manual) setSneak(false);
        // 1.21.2+ clients end every tick with this packet; anticheats use it to line up movement
        if (proto >= 768 && id(Proto.P_S_TICK_END) >= 0) send(new Buf(id(Proto.P_S_TICK_END)));
        if (detail) updateDetail();
        if (tick % 20 == 0) {
            ModEngine.emit("second", null);
            if (NonIntel.installed) NonIntel.second(World.snapshot(null));
        }
        if (tick % 200 == 0) MapData.trim(World.selfX, World.selfZ, Math.max(cfg.viewDistance, Hub.serverViewDistance) + 3);
    }

    // ===================================================================================
    // Movement physics (close to vanilla) for driving the player from the 3D tab
    // ===================================================================================

    // Vanilla's player box is built from floats: half width 0.6F / 2 = 0.30000001192..., height 1.8F = 1.79999995...
    // Using 0.3 exactly leaves you 0.000000012 inside a wall you touch, which strict servers reject as moving into a block.
    private static final double W = 0.6f / 2.0f, H = 1.8f, STEP = 0.6f;

    /** One 50 ms tick of walking physics. Must hold the lock. Returns onGround. */
    private boolean physicsStep() {
        return physicsStep(true);
    }

    /** drive=false: a tick with no keys held (a real client keeps simulating while you stand still). */
    private boolean physicsStep(boolean drive) {
        // like vanilla, don't simulate until the chunk you're in has arrived (unloaded blocks count as solid,
        // and you'd be standing inside one)
        if (!MapData.loaded((int) Math.floor(x), (int) Math.floor(z))) return onGroundM;
        if (drive) {
            yaw = lookYaw;
            pitch = Math.max(-90, Math.min(90, lookPitch));
        }
        boolean inSneak = drive && this.inSneak, inSprint = drive && this.inSprint, inJump = drive && this.inJump;
        // keys are either pressed or not (like WASD); the client then scales input by 0.98
        float f = !drive ? 0 : inForward > 0.3f ? 1 : inForward < -0.3f ? -1 : 0;
        float s = !drive ? 0 : inStrafe > 0.3f ? 1 : inStrafe < -0.3f ? -1 : 0;
        if (inSneak) {
            f *= 0.3f;
            s *= 0.3f;
        }
        f *= 0.98f;
        s *= 0.98f;
        inWater = (MapData.flagsAt((int) Math.floor(x), (int) Math.floor(y + 0.4), (int) Math.floor(z)) & 2) != 0;
        boolean sprint = inSprint && f > 0 && !inSneak && !hCollideM;
        double speed = 0.1 * (sprint ? 1.3 : 1.0);
        // vanilla: acceleration uses the slipperiness of the block below; velocity then decays by slipperiness * 0.91
        String below = Shapes.id(MapData.stateAt((int) Math.floor(x), (int) Math.floor(y - 0.5000001), (int) Math.floor(z)));
        double blockSlip = slipperiness(below);
        double slip = onGroundM ? blockSlip * 0.91 : 0.91;
        double accel = inWater ? 0.02 : onGroundM ? speed * (0.21600002 / (blockSlip * blockSlip * blockSlip)) : (sprint ? 0.026 : 0.02);
        double len = f * f + s * s;
        if (len >= 1.0E-4) {
            len = Math.sqrt(len);
            if (len < 1) len = 1;
            double ff = f / len * accel, ss = s / len * accel;
            double sin = Math.sin(Math.toRadians(yaw)), cos = Math.cos(Math.toRadians(yaw));
            vxM += ss * cos - ff * sin;
            vzM += ff * cos + ss * sin;
        }
        boolean climbing = climbable(Shapes.id(MapData.stateAt((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z))));
        if (inJump) {
            if (inWater) vyM += 0.04;
            else if (onGroundM) {
                vyM = 0.42 * jumpFactor((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
                if (sprint) {
                    vxM += -Math.sin(Math.toRadians(yaw)) * 0.2;
                    vzM += Math.cos(Math.toRadians(yaw)) * 0.2;
                }
            }
        }
        // sneaking stops you walking off edges
        if (inSneak && onGroundM) {
            if (!groundBelow(x + vxM, z)) vxM = 0;
            if (!groundBelow(x, z + vzM)) vzM = 0;
        }
        if (climbing) { // ladders, vines...: limited fall speed, sneaking holds you in place
            vxM = Math.max(-0.15, Math.min(0.15, vxM));
            vzM = Math.max(-0.15, Math.min(0.15, vzM));
            vyM = Math.max(vyM, -0.15);
            if (inSneak && vyM < 0) vyM = 0;
        }
        // cobweb, berry bush, powder snow scale this tick's movement
        double[] stuck = stuckFactor((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
        if (stuck != null) move(vxM * stuck[0], vyM * stuck[1], vzM * stuck[2]);
        else move(vxM, vyM, vzM);
        if (stuck != null) {
            vxM = vyM = vzM = 0;
        }
        if (climbing && hCollideM) vyM = 0.2; // climb up when walking into a ladder
        if (inWater) {
            vyM = vyM * 0.8 - 0.02;
            vxM *= 0.8;
            vzM *= 0.8;
        } else {
            vyM = (vyM - 0.08) * 0.98;
            vxM *= slip;
            vzM *= slip;
            double sf = speedFactor((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
            vxM *= sf;
            vzM *= sf;
        }
        if (Math.abs(vxM) < 0.003) vxM = 0;
        if (Math.abs(vzM) < 0.003) vzM = 0;
        return onGroundM;
    }

    // ---- drive diagnostics: every 2 s while a direction is held, say what is happening ----
    private long diagAt;
    private double diagX, diagZ;
    private int diagCorr, diagBlocked, diagTicks;

    private void driveDiag(long now) {
        boolean pressing = Math.abs(inForward) > 0.3f || Math.abs(inStrafe) > 0.3f || inJump;
        if (hCollideM && pressing) diagBlocked++;
        if (pressing) diagTicks++;
        if (diagAt == 0) {
            diagAt = now;
            diagX = x;
            diagZ = z;
            diagCorr = corrections;
            return;
        }
        if (now - diagAt < 2000) return;
        if (diagTicks > 0) {
            double moved = Math.sqrt((x - diagX) * (x - diagX) + (z - diagZ) * (z - diagZ));
            int bx = (int) Math.floor(x), by = (int) Math.floor(y), bz = (int) Math.floor(z);
            boolean loaded = MapData.stateAt(bx, by, bz) >= 0;
            String why;
            if (!loaded) why = "the chunk you're in isn't loaded yet (keep Show terrain or 3D on)";
            else if (corrections > diagCorr) why = "the server pulled you back " + (corrections - diagCorr) + "x (see 'Server corrected' lines)";
            else if (moved < 0.2 && diagBlocked > diagTicks / 2) why = "a block is in the way (" + blocksAround() + ")";
            else if (moved < 0.2) why = "not moving although keys are held (" + blocksAround() + ", onGround=" + onGroundM + ")";
            else why = "ok";
            Hub.log(String.format(Locale.US, "Drive: held %.1fs, moved %.2f blocks, at %.1f, %.1f, %.1f - %s", diagTicks / 20.0, moved, x, y, z, why));
        }
        diagAt = now;
        diagX = x;
        diagZ = z;
        diagCorr = corrections;
        diagBlocked = 0;
        diagTicks = 0;
    }

    /** One-tap movement test: walks forward 1.5 s, then back, and logs whether you moved and whether the server rejected it. */
    String testWalk() {
        final ScheduledExecutorService t = ticker;
        if (t == null || state != PLAY || !havePos) return "Start AFK first";
        final boolean wasManual = manual;
        final double sx = x, sz = z;
        final int c0 = corrections;
        final double[] fwd = new double[1];
        final int[] corrFwd = new int[1];
        Hub.log("Walk test: forward 1.5 s, then back 1.5 s...");
        manual = true;
        inForward = 1;
        t.schedule(new Runnable() {
            @Override
            public void run() {
                synchronized (Bot.this) {
                    fwd[0] = Math.sqrt((x - sx) * (x - sx) + (z - sz) * (z - sz));
                }
                corrFwd[0] = corrections - c0;
                inForward = -1;
            }
        }, 1500, TimeUnit.MILLISECONDS);
        t.schedule(new Runnable() {
            @Override
            public void run() {
                inForward = 0;
                int corr = corrections - c0;
                String result;
                if (corr > 0) result = "FAILED - the server pulled you back " + corr + " time(s): the server/anticheat is rejecting the movement";
                else if (fwd[0] < 0.5) result = "FAILED - you didn't move (" + blocksAround() + (MapData.stateAt((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z)) < 0 ? ", chunk not loaded" : "") + ")";
                else result = String.format(Locale.US, "OK - walked %.1f blocks forward and back with no corrections", fwd[0]);
                Hub.log("Walk test result: " + result);
                manual = wasManual;
            }
        }, 3300, TimeUnit.MILLISECONDS);
        return "Testing walking for 3 seconds - see the log";
    }

    // ---- vanilla block properties that change movement ----

    static double slipperiness(String id) {
        if (id.equals("ice") || id.equals("packed_ice") || id.equals("frosted_ice")) return 0.98;
        if (id.equals("blue_ice")) return 0.989;
        if (id.equals("slime_block")) return 0.8;
        return 0.6;
    }

    /** Soul sand / honey slow you down (block at your feet, otherwise the one just below). */
    private static double speedFactor(int bx, int by, int bz) {
        String at = Shapes.id(MapData.stateAt(bx, by, bz));
        if (at.equals("water") || at.equals("bubble_column")) return 1;
        double f = blockSpeed(at);
        if (f != 1) return f;
        return blockSpeed(Shapes.id(MapData.stateAt(bx, by - 1, bz)));
    }

    private static double blockSpeed(String id) {
        return id.equals("soul_sand") || id.equals("honey_block") ? 0.4 : 1;
    }

    private static double jumpFactor(int bx, int by, int bz) {
        String at = Shapes.id(MapData.stateAt(bx, by, bz)), below = Shapes.id(MapData.stateAt(bx, by - 1, bz));
        return at.equals("honey_block") || below.equals("honey_block") ? 0.5 : 1;
    }

    private static double[] stuckFactor(int bx, int by, int bz) {
        for (int dy = 0; dy <= 1; dy++) {
            String id = Shapes.id(MapData.stateAt(bx, by + dy, bz));
            if (id.equals("cobweb")) return new double[]{0.25, 0.05, 0.25};
            if (id.equals("sweet_berry_bush")) return new double[]{0.8, 0.75, 0.8};
            if (id.equals("powder_snow")) return new double[]{0.9, 1.5, 0.9};
        }
        return null;
    }

    private static boolean climbable(String id) {
        return id.equals("ladder") || id.equals("vine") || id.equals("scaffolding") || id.startsWith("twisting_vines")
                || id.startsWith("weeping_vines") || id.startsWith("cave_vines");
    }

    /** "stone below, air at feet" style description for the correction log. */
    private String blocksAround() {
        int bx = (int) Math.floor(x), by = (int) Math.floor(y), bz = (int) Math.floor(z);
        String below = Shapes.id(MapData.stateAt(bx, by - 1, bz)), feet = Shapes.id(MapData.stateAt(bx, by, bz)),
                head = Shapes.id(MapData.stateAt(bx, by + 1, bz));
        return "below=" + (below.isEmpty() ? "?" : below) + " feet=" + (feet.isEmpty() ? "air" : feet) + " head=" + (head.isEmpty() ? "air" : head);
    }

    private boolean groundBelow(double px, double pz) {
        return collides(px - W, y - 0.6, pz - W, px + W, y - 0.01, pz + W);
    }

    /** Collision boxes of the block at a position (unloaded chunks count as solid so you never walk into the void). */
    private static float[][] boxesAt(int bx, int by, int bz) {
        int st = MapData.stateAt(bx, by, bz);
        if (st < 0) return Shapes.FULL;
        if (st == 0) return Shapes.NONE;
        if (Shapes.known()) return Shapes.collision(st);
        return (MapData.flagsAt(bx, by, bz) & 1) != 0 ? Shapes.FULL : Shapes.NONE;
    }

    private static boolean collides(double x0, double y0, double z0, double x1, double y1, double z1) {
        for (int bx = (int) Math.floor(x0); bx <= (int) Math.floor(x1 - 1e-7); bx++)
            for (int by = (int) Math.floor(y0) - 1; by <= (int) Math.floor(y1 - 1e-7); by++) // -1: fences/walls are 1.5 tall
                for (int bz = (int) Math.floor(z0); bz <= (int) Math.floor(z1 - 1e-7); bz++)
                    for (float[] b : boxesAt(bx, by, bz))
                        // (double) bx: int + float is float maths, which is only 0.25-block precise past x/z = 2 million
                        if (x1 > (double) bx + b[0] + 1e-7 && x0 < (double) bx + b[3] - 1e-7 && y1 > (double) by + b[1] + 1e-7 && y0 < (double) by + b[4] - 1e-7
                                && z1 > (double) bz + b[2] + 1e-7 && z0 < (double) bz + b[5] - 1e-7) return true;
        return false;
    }

    /**
     * Moves with collision exactly like vanilla Entity.move/collide: Y first, then the larger of X/Z last; if a wall
     * stops us and we were on the ground before this move (or landed during it), try each step height up to 0.6
     * that a nearby block's top offers, lowest first, and keep the first one that gets us further sideways.
     */
    private void move(double dx, double dy, double dz) {
        boolean wasGround = onGroundM;
        double[] r = collideFrom(x, y, z, dx, dy, dz);
        boolean vColl = r[1] != dy;
        if ((r[0] != dx || r[2] != dz) && (wasGround || vColl && dy < 0) && !inWater) {
            double baseY = vColl ? y + r[1] : y; // start from where we landed if we hit the ground this tick
            double have = r[0] * r[0] + r[2] * r[2];
            for (float h : stepHeights(x, baseY, z, dx, dz, vColl, (float) r[1])) {
                double[] t = collideFrom(x, baseY, z, dx, h, dz);
                if (t[0] * t[0] + t[2] * t[2] > have) {
                    r = new double[]{t[0], t[1] + (baseY - y), t[2]};
                    break;
                }
            }
        }
        x += r[0];
        y += r[1];
        z += r[2];
        boolean hx = Math.abs(r[0] - dx) >= 1.0E-5, hz = Math.abs(r[2] - dz) >= 1.0E-5; // Mth.equal
        vColl = r[1] != dy;
        onGroundM = vColl && dy < 0;
        hCollideM = hx || hz;
        if (hx) vxM = 0;
        if (hz) vzM = 0;
        if (vColl) vyM = 0;
    }

    /** Movement allowed from a box at (ox, oy, oz): Y, then X and Z (vanilla moves the bigger of X/Z last). */
    private double[] collideFrom(double ox, double oy, double oz, double dx, double dy, double dz) {
        double ry = sweepFrom(ox, oy, oz, 1, dy);
        oy += ry;
        double rx, rz;
        if (Math.abs(dx) < Math.abs(dz)) {
            rz = sweepFrom(ox, oy, oz, 2, dz);
            rx = sweepFrom(ox, oy, oz + rz, 0, dx);
        } else {
            rx = sweepFrom(ox, oy, oz, 0, dx);
            rz = sweepFrom(ox + rx, oy, oz, 2, dz);
        }
        return new double[]{rx, ry, rz};
    }

    /** Candidate step-up heights (vanilla collectCandidateStepUpHeights): tops/bottoms of nearby boxes, 0..0.6, sorted. */
    private float[] stepHeights(double ox, double oy, double oz, double dx, double dz, boolean vColl, float skip) {
        double x0 = Math.min(ox - W, ox - W + dx), x1 = Math.max(ox + W, ox + W + dx);
        double z0 = Math.min(oz - W, oz - W + dz), z1 = Math.max(oz + W, oz + W + dz);
        double y0 = vColl ? oy : oy - 1.0E-5F, y1 = oy + H + STEP;
        java.util.TreeSet<Float> out = new java.util.TreeSet<Float>();
        for (int bx = (int) Math.floor(x0); bx <= (int) Math.floor(x1 - 1e-7); bx++)
            for (int by = (int) Math.floor(y0) - 1; by <= (int) Math.floor(y1 - 1e-7); by++)
                for (int bz = (int) Math.floor(z0); bz <= (int) Math.floor(z1 - 1e-7); bz++)
                    for (float[] q : boxesAt(bx, by, bz)) {
                        double bx0 = (double) bx + q[0], bx1 = (double) bx + q[3], by0 = (double) by + q[1], by1 = (double) by + q[4];
                        double bz0 = (double) bz + q[2], bz1 = (double) bz + q[5];
                        if (bx1 <= x0 || bx0 >= x1 || by1 <= y0 || by0 >= y1 || bz1 <= z0 || bz0 >= z1) continue;
                        for (double c : new double[]{by0, by1}) {
                            float f = (float) (c - oy);
                            if (f >= 0 && f <= (float) STEP && f != skip) out.add(f); // float compare, like vanilla
                        }
                    }
        float[] r = new float[out.size()];
        int i = 0;
        for (Float f : out) r[i++] = f;
        return r;
    }

    /** 1.21.2+: tell the server which movement keys are held (anticheats compare this with how you move). */
    private void sendInputs(boolean active) throws IOException {
        if (proto < 768 || id(Proto.P_S_PLAYER_INPUT) < 0) return;
        int flags = 0;
        if (active) {
            if (inForward > 0.3f) flags |= 0x01;
            if (inForward < -0.3f) flags |= 0x02;
            if (inStrafe > 0.3f) flags |= 0x04;
            if (inStrafe < -0.3f) flags |= 0x08;
            if (inJump) flags |= 0x10;
            if (inSneak) flags |= 0x20;
            if (inSprint) flags |= 0x40;
        }
        if (flags == lastInputFlags) return;
        lastInputFlags = flags;
        send(new Buf(id(Proto.P_S_PLAYER_INPUT)).u8(flags));
    }

    /** How far we can move along an axis (0 x, 1 y, 2 z) before hitting a block's real shape (vanilla-style AABB clipping). */
    private double sweep(int axis, double d) {
        return sweepFrom(x, y, z, axis, d);
    }

    private double sweepFrom(double px, double py, double pz, int axis, double d) {
        if (d == 0) return 0;
        double[] lo = {px - W, py, pz - W}, hi = {px + W, py + H, pz + W};
        double[] slo = lo.clone(), shi = hi.clone();
        if (d > 0) shi[axis] += d;
        else slo[axis] += d;
        int x0 = (int) Math.floor(slo[0]), x1 = (int) Math.floor(shi[0] - 1e-7);
        int y0 = (int) Math.floor(slo[1]) - 1, y1 = (int) Math.floor(shi[1] - 1e-7);
        int z0 = (int) Math.floor(slo[2]), z1 = (int) Math.floor(shi[2] - 1e-7);
        if ((long) (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1) > 4000) return 0; // absurd move, refuse
        for (int bx = x0; bx <= x1; bx++)
            for (int by = y0; by <= y1; by++)
                for (int bz = z0; bz <= z1; bz++) {
                    for (float[] bb : boxesAt(bx, by, bz)) {
                        // double first: int + float would be float maths (0.25-block steps at 2M+ coordinates)
                        double[] b0 = {(double) bx + bb[0], (double) by + bb[1], (double) bz + bb[2]}, b1 = {(double) bx + bb[3], (double) by + bb[4], (double) bz + bb[5]};
                        boolean overlap = true;
                        for (int a = 0; a < 3; a++) {
                            if (a == axis) continue;
                            if (hi[a] <= b0[a] + 1e-7 || lo[a] >= b1[a] - 1e-7) overlap = false;
                        }
                        if (!overlap) continue;
                        if (d > 0 && b0[axis] >= hi[axis] - 1e-7) d = Math.min(d, b0[axis] - hi[axis]);
                        else if (d < 0 && b1[axis] <= lo[axis] + 1e-7) d = Math.max(d, b1[axis] - lo[axis]);
                    }
                }
        return d;
    }

    private void setSneak(boolean on) throws IOException {
        if (on) sneakTicks = 20;
        if (proto >= 768 && id(Proto.P_S_PLAYER_INPUT) >= 0) send(new Buf(id(Proto.P_S_PLAYER_INPUT)).u8(on ? 0x20 : 0));
        if (proto < 771 && id(Proto.P_S_ENTITY_ACTION) >= 0 && selfEntityId != Integer.MIN_VALUE)
            send(new Buf(id(Proto.P_S_ENTITY_ACTION)).varInt(selfEntityId).varInt(on ? 0 : 1).varInt(0));
    }

    private void updateDetail() {
        long left;
        String d;
        synchronized (this) {
            left = Math.max(0, (nextJumpAt - System.currentTimeMillis()) / 1000);
            long up = joinedAt > 0 ? (System.currentTimeMillis() - joinedAt) / 1000 : 0;
            Hub.statHealth = health;
            Hub.statPos = String.format(Locale.US, "%.0f, %.0f, %.0f", x, groundY, z);
            Hub.statUptime = duration(up);
            Hub.statActions = jumps;
            Hub.statNextAction = cfg.antiAfk ? left + "s" : "off";
            double np = nearestPlayer;
            Hub.statNearest = np < 0 ? "none" : String.format(Locale.US, "%.0f m", np);
            d = String.format(Locale.US, "Position %.1f, %.1f, %.1f  •  Health %.0f/20", x, groundY, z, health);
        }
        Hub.detail(d);
    }

    private static String duration(long s) {
        if (s < 60) return s + "s";
        if (s < 3600) return (s / 60) + "m " + (s % 60) + "s";
        return (s / 3600) + "h " + ((s / 60) % 60) + "m";
    }

    // ===================================================================================
    // Framing, compression, encryption
    // ===================================================================================

    // last packets we sent, so an unexplained disconnect can say what we were doing
    private final int[] sentIds = new int[48];
    private int sentPos;
    private final Object sentLock = new Object();
    /** Desktop debugging: print every packet sent (id + size). */
    static volatile boolean traceSent;

    private static final int[] SENT_KEYS = {Proto.P_S_USE_ITEM_ON, Proto.P_S_WINDOW_CLICK, Proto.P_S_CLOSE_WINDOW,
            Proto.P_S_SWING, Proto.P_S_POSITION, Proto.P_S_POSITION_LOOK, Proto.P_S_LOOK, Proto.P_S_PLAYER_INPUT,
            Proto.P_S_TICK_END, Proto.P_S_BLOCK_DIG, Proto.P_S_USE_ENTITY, Proto.P_S_USE_ITEM, Proto.P_S_HELD_SLOT,
            Proto.P_S_KEEPALIVE, Proto.P_S_PONG, Proto.P_S_TELEPORT_CONFIRM, Proto.P_S_CHAT_MESSAGE,
            Proto.P_S_CHAT_COMMAND, Proto.P_S_ENTITY_ACTION, Proto.P_S_CHUNK_BATCH_RECEIVED, Proto.P_S_CLIENT_COMMAND};
    private static final String[] SENT_NAMES = {"open/use block", "inventory click", "close container", "swing arm",
            "move", "move+look", "look", "input", "tick end", "mine", "hit/use entity", "use item", "hotbar slot",
            "keepalive", "pong", "teleport confirm", "chat", "command", "sprint/sneak", "chunks ack", "respawn"};

    private String recentSent() {
      synchronized (sentLock) {
        StringBuilder sb = new StringBuilder();
        String last = null;
        int run = 0;
        for (int i = 0; i < sentIds.length; i++) {
            int pid = sentIds[(sentPos + i) % sentIds.length];
            if (pid <= 0) continue;
            String n = "0x" + Integer.toHexString(pid - 1);
            for (int k = 0; k < SENT_KEYS.length; k++) if (id(SENT_KEYS[k]) == pid - 1) { n = SENT_NAMES[k]; break; }
            if (n.equals("tick end")) continue; // sent every tick
            if (n.equals(last)) { run++; continue; }
            if (last != null) sb.append(last).append(run > 1 ? " x" + run : "").append(", ");
            last = n;
            run = 1;
        }
        if (last != null) sb.append(last).append(run > 1 ? " x" + run : "");
        return sb.toString();
      }
    }

    private void send(Buf b) throws IOException {
        byte[] body = b.toBytes();
        if (state == PLAY && body.length > 0) {
            synchronized (sentLock) {
                int pid = 0, shift = 0, i = 0, c;
                do { c = body[i++] & 255; pid |= (c & 127) << shift; shift += 7; } while ((c & 128) != 0 && i < body.length && i < 3);
                sentIds[sentPos] = pid + 1;
                sentPos = (sentPos + 1) % sentIds.length;
                if (traceSent && pid != id(Proto.P_S_TICK_END)) System.out.println("[SENT] 0x" + Integer.toHexString(pid) + " " + body.length + "b");
            }
        }
        byte[] frame;
        int th = threshold;
        if (th >= 0) {
            Buf inner = new Buf();
            if (body.length >= th) {
                byte[] comp;
                synchronized (deflater) {
                    deflater.reset();
                    deflater.setInput(body);
                    deflater.finish();
                    ByteArrayOutputStream bo = new ByteArrayOutputStream(body.length);
                    byte[] tmp = new byte[8192];
                    while (!deflater.finished()) {
                        int n = deflater.deflate(tmp);
                        bo.write(tmp, 0, n);
                    }
                    comp = bo.toByteArray();
                }
                inner.varInt(body.length).bytes(comp);
            } else {
                inner.varInt(0).bytes(body);
            }
            byte[] ib = inner.toBytes();
            frame = new Buf().varInt(ib.length).bytes(ib).toBytes();
        } else {
            frame = new Buf().varInt(body.length).bytes(body).toBytes();
        }
        synchronized (this) {
            OutputStream o = out;
            if (o == null) throw new IOException("not connected");
            if (encrypt != null) frame = encrypt.update(frame);
            o.write(frame);
            o.flush();
        }
    }

    private static int readVarInt(DataInputStream in) throws IOException {
        int v = 0, shift = 0, b;
        do {
            b = in.readUnsignedByte();
            v |= (b & 0x7F) << shift;
            shift += 7;
            if (shift > 35) throw new IOException("VarInt too big");
        } while ((b & 0x80) != 0);
        return v;
    }

    /** Reads one packet. Returns null when the packet is one we don't care about (skipped without inflating). */
    private Buf.In readPacket(DataInputStream in) throws IOException {
        int len = readVarInt(in);
        if (len <= 0 || len > MAX_PACKET) throw new IOException("Bad packet length " + len);
        byte[] data = new byte[len];
        in.readFully(data);
        if (threshold < 0) return new Buf.In(data, 0);
        Buf.In h = new Buf.In(data, 0);
        int dataLen = h.varInt();
        if (dataLen == 0) return new Buf.In(data, h.p);
        if (dataLen > MAX_PACKET) throw new IOException("Packet too big " + dataLen);
        try {
            inflater.reset();
            inflater.setInput(data, h.p, data.length - h.p);
            // Peek the packet id first so big packets (chunks etc.) are never fully decompressed.
            byte[] head = new byte[Math.min(5, dataLen)];
            int got = 0;
            while (got < head.length) {
                int n = inflater.inflate(head, got, head.length - got);
                if (n == 0) {
                    if (inflater.finished() || inflater.needsInput()) break;
                }
                got += n;
            }
            int pid = new Buf.In(head, 0).varInt();
            if (!interesting(pid)) return null;
            byte[] full = new byte[dataLen];
            System.arraycopy(head, 0, full, 0, got);
            int pos = got;
            while (pos < dataLen) {
                int n = inflater.inflate(full, pos, dataLen - pos);
                if (n == 0 && (inflater.finished() || inflater.needsInput())) break;
                pos += n;
            }
            return new Buf.In(full, 0);
        } catch (DataFormatException e) {
            throw new IOException("Bad compressed packet");
        }
    }

    private boolean interesting(int pid) {
        if (state != PLAY) return true;
        int[] want = {Proto.P_C_KEEPALIVE, Proto.P_C_PING, Proto.P_C_POSITION, Proto.P_C_LOGIN, Proto.P_C_DISCONNECT,
                Proto.P_C_START_CONFIG, Proto.P_C_HEALTH, Proto.P_C_SYSTEM_CHAT, Proto.P_C_RESOURCE_PACK, Proto.P_C_COOKIE_REQ,
                Proto.P_C_SPAWN_ENTITY, Proto.P_C_SPAWN_PLAYER, Proto.P_C_SPAWN_LIVING, Proto.P_C_ENTITY_DESTROY,
                Proto.P_C_REL_MOVE, Proto.P_C_MOVE_LOOK, Proto.P_C_ENTITY_TELEPORT, Proto.P_C_ENTITY_SYNC, Proto.P_C_RESPAWN,
                Proto.P_C_PLAYER_INFO, Proto.P_C_PLAYER_REMOVE, Proto.P_C_PLAYER_CHAT, Proto.P_C_PROFILELESS_CHAT};
        for (int k : want) if (ids[k] == pid) return true;
        if (MapData.supported && (pid == id(Proto.P_C_MAP_CHUNK) || pid == id(Proto.P_C_UNLOAD_CHUNK))) return true;
        // only decoded while the Play screen is open (keeps the AFK bot as light as before)
        if (GameState.open && (pid == id(Proto.P_C_UPDATE_LIGHT) || pid == id(Proto.P_C_UPDATE_TIME) || pid == id(Proto.P_C_EXPERIENCE)
                || pid == id(Proto.P_C_GAME_STATE) || pid == id(Proto.P_C_BREAK_ANIM) || pid == id(Proto.P_C_ENTITY_METADATA))) return true;
        if (pid == id(Proto.P_C_BLOCK_CHANGE) || pid == id(Proto.P_C_MULTI_BLOCK) || pid == id(Proto.P_C_WINDOW_ITEMS)
                || pid == id(Proto.P_C_SET_SLOT) || pid == id(Proto.P_C_OPEN_WINDOW) || pid == id(Proto.P_C_CLOSE_WINDOW)
                || pid == id(Proto.P_C_HELD_SLOT)) return true;
        return pid == id(Proto.P_C_CHUNK_BATCH_FINISHED);
    }

    /** Input stream that starts decrypting once a cipher is set. */
    static final class DecryptIn extends FilterInputStream {
        volatile Cipher cipher;
        private final byte[] one = new byte[1];

        DecryptIn(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int n = read(one, 0, 1);
            return n <= 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = in.read(b, off, len);
            if (n > 0 && cipher != null) {
                byte[] dec = cipher.update(b, off, n);
                if (dec != null) System.arraycopy(dec, 0, b, off, dec.length);
            }
            return n;
        }
    }

    private static String serverHash(String serverId, byte[] secret, byte[] pub) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-1");
        sha.update(serverId.getBytes(StandardCharsets.ISO_8859_1));
        sha.update(secret);
        sha.update(pub);
        return new BigInteger(sha.digest()).toString(16);
    }

    // ===================================================================================
    // Server list ping (to learn the server's version) and SRV lookup
    // ===================================================================================

    private int ping(String handshakeHost, String host, int port) throws Exception {
        Socket s = new Socket();
        socket = s;
        try {
            s.connect(new InetSocketAddress(host, port), 15000);
            s.setSoTimeout(15000);
            out = s.getOutputStream();
            threshold = -1;
            encrypt = null;
            int asProto = Proto.PROTOCOLS[Proto.PROTOCOLS.length - 1];
            send(new Buf(0x00).varInt(asProto).str(handshakeHost).u16(port).varInt(1));
            send(new Buf(0x00));
            DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
            Buf.In p = readPacket(in);
            if (p == null || p.varInt() != 0) throw new IOException("unexpected status reply");
            JSONObject j = new JSONObject(p.str());
            JSONObject v = j.optJSONObject("version");
            int sp = v != null ? v.optInt("protocol", -1) : -1;
            String vn = v != null ? Text.strip(v.optString("name", "?")) : "?";
            JSONObject pl = j.optJSONObject("players");
            String players = pl != null ? pl.optInt("online") + "/" + pl.optInt("max") : "?";
            Object desc = j.opt("description");
            String motd = desc == null ? "" : Text.strip(desc instanceof String ? (String) desc : Text.fromJson(desc.toString())).replace('\n', ' ').trim();
            Hub.log("Server found: " + vn + " (protocol " + sp + "), players " + players + (motd.isEmpty() ? "" : " - " + motd));
            if (sp <= 0) {
                // Some servers (proxies / multi-version plugins) send protocol -1; read the version from its name instead.
                int guess = protocolFromName(vn);
                if (guess > 0) {
                    Hub.log("Server didn't give a protocol number; using " + guess + " from its version name \"" + vn + "\"");
                    sp = guess;
                } else {
                    sp = Proto.PROTOCOLS[Proto.PROTOCOLS.length - 1];
                    Hub.log("Server didn't give a protocol number; trying " + Proto.NAMES[Proto.PROTOCOLS.length - 1]);
                }
            }
            return sp;
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
            }
            socket = null;
            out = null;
        }
    }

    /** Finds the best-matching known version (e.g. "1.21.11") inside a server's version name. */
    static int protocolFromName(String name) {
        int best = -1, bestLen = 0;
        for (int i = 0; i < Proto.NAMES.length; i++) {
            for (String v : Proto.NAMES[i].split("/")) {
                int at = name.indexOf(v);
                while (at >= 0) {
                    int end = at + v.length();
                    boolean startOk = at == 0 || !Character.isDigit(name.charAt(at - 1)) && name.charAt(at - 1) != '.';
                    boolean endOk = end >= name.length() || !Character.isDigit(name.charAt(end)) && !(name.charAt(end) == '.' && end + 1 < name.length() && Character.isDigit(name.charAt(end + 1)));
                    if (startOk && endOk && v.length() > bestLen) {
                        best = Proto.PROTOCOLS[i];
                        bestLen = v.length();
                    }
                    at = name.indexOf(v, at + 1);
                }
            }
        }
        return best;
    }

        private static String[] srvLookup(String host) {
        return resolveSrv("_minecraft._tcp." + host, 0);
    }

    /** Follows SRV records, including providers that point an SRV record at another SRV/CNAME name. */
    private static String[] resolveSrv(String name, int depth) {
        if (depth > 4) return null;
        JSONArray ans = dnsQuery(name, "SRV");
        if (ans == null) return null;
        String cname = null;
        for (int i = 0; i < ans.length(); i++) {
            JSONObject a = ans.optJSONObject(i);
            if (a == null) continue;
            int type = a.optInt("type");
            String data = a.optString("data", "").trim();
            if (type == 5) {
                cname = stripDot(data);
                continue;
            }
            if (type != 33) continue;
            String[] parts = data.split("\\s+");
            if (parts.length < 4) continue;
            String target = stripDot(parts[3]);
            if (target.isEmpty()) continue;
            return new String[]{target, parts[2]};
        }
        if (cname != null && !cname.equals(name)) return resolveSrv(cname, depth + 1);
        return null;
    }

    /** A / AAAA lookup over DNS-over-HTTPS. Prefers IPv4. */
    private static String resolveIp(String host) {
        for (String type : new String[]{"A", "AAAA"}) {
            String name = host;
            for (int hop = 0; hop < 5; hop++) {
                JSONArray ans = dnsQuery(name, type);
                if (ans == null) break;
                String next = null;
                for (int i = 0; i < ans.length(); i++) {
                    JSONObject a = ans.optJSONObject(i);
                    if (a == null) continue;
                    int t = a.optInt("type");
                    String data = stripDot(a.optString("data", "").trim());
                    if ((t == 1 && type.equals("A")) || (t == 28 && type.equals("AAAA"))) return data;
                    if (t == 5) next = data;
                }
                if (next == null || next.equals(name)) break;
                name = next;
            }
        }
        return null;
    }

    private static String stripDot(String s) {
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }

    private static JSONArray dnsQuery(String name, String type) {
        String[] servers = {"https://dns.google/resolve", "https://cloudflare-dns.com/dns-query"};
        for (String srv : servers) {
            try {
                URL u = new URL(srv + "?type=" + type + "&name=" + URLEncoder.encode(name, "UTF-8"));
                HttpURLConnection h = (HttpURLConnection) u.openConnection();
                h.setConnectTimeout(5000);
                h.setReadTimeout(5000);
                h.setRequestProperty("Accept", "application/dns-json");
                InputStream in = h.getInputStream();
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[2048];
                int n;
                while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
                in.close();
                JSONObject j = new JSONObject(new String(b.toByteArray(), StandardCharsets.UTF_8));
                JSONArray ans = j.optJSONArray("Answer");
                return ans == null ? new JSONArray() : ans;
            } catch (Exception ignored) {
            }
        }
        return null;
    }
}
