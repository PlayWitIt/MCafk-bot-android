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
    private ProtoDef.VersionData vdata;
    private int useSequence;

    // ---- manual control (3D tab) ----
    /** True while you drive the player from the 3D tab (anti-AFK pauses). */
    volatile boolean manual;
    volatile float inForward, inStrafe, lookYaw, lookPitch;
    volatile boolean inJump, inSneak, inSprint;
    private boolean onGroundM = true, wasSneak, wasSprint, inWater;
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
            } catch (Kick k) {
                Hub.log("Kicked: " + k.getMessage());
                Hub.status("Kicked: " + firstLine(k.getMessage()), Hub.RED);
                Hub.alert("Kicked from " + serverLabel, firstLine(k.getMessage()));
                emitMod("kicked", "reason", k.getMessage());
            } catch (Exception e) {
                if (!stopped) {
                    String m = e instanceof SocketTimeoutException ? "server stopped responding (timed out)"
                            : e instanceof EOFException ? "server closed the connection" : String.valueOf(e.getMessage());
                    Hub.log("Disconnected: " + m);
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
            int wait = Math.min(300, 10 * (1 << Math.min(5, Math.max(0, failures - 1))));
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
        vdata = null;
        Hub.VersionLoader vl = Hub.versionLoader;
        if (vl != null && Proto.PROTOCOLS[idx] >= 764) {
            try {
                vdata = vl.load(Proto.PROTOCOLS[idx]);
                Hub.versionData = vdata;
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
            java.util.Map<String, Object> m = vdata.readPacket("open_window", p);
            Object t = m.get("windowTitle");
            Inv.title = t instanceof String && ((String) t).startsWith("{") ? Text.fromJson((String) t) : Text.strip(Text.flatten(t));
            Inv.openId = Inv.num(m.get("windowId"));
            Inv.slots = new java.util.ArrayList<Inv.Item>();
            Inv.status = "";
            Inv.version++;
            Hub.log("Opened " + (Inv.title.isEmpty() ? "container" : Inv.title));
        } else if (pid == id(Proto.P_C_WINDOW_ITEMS) && vdata != null) {
            java.util.Map<String, Object> m = vdata.readPacket("window_items", p);
            int wid = Inv.num(m.get("windowId"));
            java.util.List<Inv.Item> list = new java.util.ArrayList<Inv.Item>();
            Object items = m.get("items");
            if (items instanceof java.util.List) for (Object o : (java.util.List<?>) items) list.add(Inv.fromSlot(o, vdata));
            Inv.stateId = Inv.num(m.get("stateId"));
            if (wid == 0) Inv.inventory = list;
            else if (wid == Inv.openId) Inv.slots = list;
            Inv.version++;
        } else if (pid == id(Proto.P_C_SET_SLOT) && vdata != null) {
            java.util.Map<String, Object> m = vdata.readPacket("set_slot", p);
            int wid = Inv.num(m.get("windowId")), slot = Inv.num(m.get("slot"));
            Inv.stateId = Inv.num(m.get("stateId"));
            java.util.List<Inv.Item> target = wid == 0 ? Inv.inventory : wid == Inv.openId ? Inv.slots : null;
            if (target != null && slot >= 0 && slot < target.size()) {
                java.util.List<Inv.Item> copy = new java.util.ArrayList<Inv.Item>(target);
                copy.set(slot, Inv.fromSlot(m.get("item"), vdata));
                if (wid == 0) Inv.inventory = copy;
                else Inv.slots = copy;
                Inv.version++;
            }
        } else if (pid == id(Proto.P_C_CLOSE_WINDOW)) {
            Inv.openId = -1;
            Inv.version++;
        } else {
            return false;
        }
        return true;
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
                    // look at the block first, like a real client
                    synchronized (Bot.this) {
                        double ex = x, ey = y + 1.62, ez = z;
                        double dx = bx + 0.5 - ex, dy = by + 0.5 - ey, dz = bz + 0.5 - ez;
                        yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                        pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
                        lookYaw = yaw;
                        lookPitch = pitch;
                    }
                    sendMove(true, true, onGroundM);
                    send(new Buf(id(Proto.P_S_SWING)).varInt(0));
                    Buf b = new Buf(id(Proto.P_S_USE_ITEM_ON)).varInt(0).i64(packPos(bx, by, bz)).varInt(face)
                            .f32(0.5f).f32(face == 1 ? 1f : 0.5f).f32(0.5f).bool(false);
                    if (proto >= 768) b.bool(false);
                    b.varInt(++useSequence);
                    send(b);
                    Inv.status = "Opening...";
                    Inv.version++;
                } catch (IOException ignored) {
                }
            }
        });
    }

    /** Clicks a slot in the open container. mode 1 + button 0 = shift-click (moves the stack across). */
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

    boolean containersSupported() {
        return vdata != null && id(Proto.P_S_WINDOW_CLICK) >= 0;
    }

    private static final java.util.regex.Pattern CHAT_LINE = java.util.regex.Pattern.compile("^<([A-Za-z0-9_]{1,16})> (.*)$");

    private void emitChat(String sender, String message, String raw, boolean system) {
        try {
            JSONObject o = new JSONObject().put("message", message).put("raw", raw).put("system", system);
            if (sender != null) o.put("sender", sender);
            ModEngine.emit("chat", o);
        } catch (org.json.JSONException ignored) {
        }
    }

    private static void emitMod(String event, String key, Object value) {
        try {
            ModEngine.emit(event, new JSONObject().put(key, value));
        } catch (org.json.JSONException ignored) {
        }
    }

    String playerName() {
        return playerName;
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
                    p.str();
                    p.str();
                    if (p.bool()) p.str();
                }
            }
            if ((actions & 0x02) != 0 && p.bool()) { // chat session
                p.uuid();
                p.i64();
                p.byteArray();
                p.byteArray();
            }
            if ((actions & 0x04) != 0) p.varInt(); // game mode
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
        Hub.chat((name != null ? "<" + name + "> " : "<?> ") + msg);
        emitChat(name, msg, (name != null ? "<" + name + "> " : "") + msg, false);
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
        double nx, ny, nz;
        float nyaw, npitch;
        if (proto >= 768) {
            tid = p.varInt();
            nx = p.f64();
            ny = p.f64();
            nz = p.f64();
            p.f64();
            p.f64();
            p.f64();
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
        synchronized (this) {
            x = (flags & 0x01) != 0 ? x + nx : nx;
            y = (flags & 0x02) != 0 ? y + ny : ny;
            z = (flags & 0x04) != 0 ? z + nz : nz;
            yaw = (flags & 0x08) != 0 ? yaw + nyaw : nyaw;
            pitch = (flags & 0x10) != 0 ? pitch + npitch : npitch;
            groundY = y;
            jumping = false;
            vxM = vyM = vzM = 0;
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
        if (proto >= 768) b.u8(onGround ? 1 : 0);
        else b.bool(onGround);
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
            if (manual) {
                jumping = false;
                ground = physicsStep();
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
            } else if (tick % 20 == 0) {
                doMove = true;
            }
            detail = tick % 10 == 0;
            World.selfX = x;
            World.selfY = groundY;
            World.selfZ = z;
            World.selfYaw = yaw;
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
        if (manual) {
            if (inSneak != wasSneak) {
                wasSneak = inSneak;
                setSneak(inSneak);
                sneakTicks = 0;
            }
            if (inSprint != wasSprint && id(Proto.P_S_ENTITY_ACTION) >= 0 && selfEntityId != Integer.MIN_VALUE) {
                wasSprint = inSprint;
                int act = proto >= 771 ? (inSprint ? 1 : 2) : (inSprint ? 3 : 4);
                send(new Buf(id(Proto.P_S_ENTITY_ACTION)).varInt(selfEntityId).varInt(act).varInt(0));
            }
        } else if (wasSneak || wasSprint) {
            if (wasSneak) setSneak(false);
            wasSneak = false;
            wasSprint = false;
        }
        if (sneakTicks > 0 && --sneakTicks == 0 && !manual) setSneak(false);
        if (detail) updateDetail();
        if (tick % 20 == 0) ModEngine.emit("second", null);
        if (tick % 200 == 0) MapData.trim(World.selfX, World.selfZ, Math.max(cfg.viewDistance, Hub.serverViewDistance) + 3);
    }

    // ===================================================================================
    // Movement physics (close to vanilla) for driving the player from the 3D tab
    // ===================================================================================

    private static final double W = 0.3, H = 1.8, STEP = 0.6;

    /** One 50 ms tick of walking physics. Must hold the lock. Returns onGround. */
    private boolean physicsStep() {
        yaw = lookYaw;
        pitch = Math.max(-90, Math.min(90, lookPitch));
        float f = inForward, s = inStrafe;
        if (inSneak) {
            f *= 0.3f;
            s *= 0.3f;
        }
        inWater = (MapData.flagsAt((int) Math.floor(x), (int) Math.floor(y + 0.4), (int) Math.floor(z)) & 2) != 0;
        boolean sprint = inSprint && f > 0 && !inSneak;
        double speed = 0.1 * (sprint ? 1.3 : 1.0);
        double slip = onGroundM ? 0.6 * 0.91 : 0.91;
        double accel = inWater ? 0.02 : onGroundM ? speed * (0.21600002 / (slip * slip * slip)) : (sprint ? 0.026 : 0.02);
        double len = f * f + s * s;
        if (len >= 1.0E-4) {
            len = Math.sqrt(len);
            if (len < 1) len = 1;
            double ff = f / len * accel, ss = s / len * accel;
            double sin = Math.sin(Math.toRadians(yaw)), cos = Math.cos(Math.toRadians(yaw));
            vxM += ss * cos - ff * sin;
            vzM += ff * cos + ss * sin;
        }
        if (inJump) {
            if (inWater) vyM += 0.04;
            else if (onGroundM) {
                vyM = 0.42;
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
        move(vxM, vyM, vzM);
        if (inWater) {
            vyM = vyM * 0.8 - 0.02;
            vxM *= 0.8;
            vzM *= 0.8;
        } else {
            vyM = (vyM - 0.08) * 0.98;
            vxM *= slip;
            vzM *= slip;
        }
        if (Math.abs(vxM) < 0.003) vxM = 0;
        if (Math.abs(vzM) < 0.003) vzM = 0;
        return onGroundM;
    }

    private boolean groundBelow(double px, double pz) {
        return collides(px - W, y - 0.6, pz - W, px + W, y - 0.01, pz + W);
    }

    private static boolean solid(int bx, int by, int bz) {
        return (MapData.flagsAt(bx, by, bz) & 1) != 0;
    }

    private static boolean collides(double x0, double y0, double z0, double x1, double y1, double z1) {
        for (int bx = (int) Math.floor(x0); bx <= (int) Math.floor(x1 - 1e-7); bx++)
            for (int by = (int) Math.floor(y0); by <= (int) Math.floor(y1 - 1e-7); by++)
                for (int bz = (int) Math.floor(z0); bz <= (int) Math.floor(z1 - 1e-7); bz++)
                    if (solid(bx, by, bz)) return true;
        return false;
    }

    /** Moves with collision: Y first, then X and Z, with step-up onto blocks up to 0.6 high. */
    private void move(double dx, double dy, double dz) {
        double ox = x, oy = y, oz = z;
        double ny = sweep(1, dy);
        boolean hitY = ny != dy;
        y += ny;
        onGroundM = hitY && dy < 0;
        if (hitY) vyM = 0;
        double nx = sweep(0, dx);
        x += nx;
        double nz = sweep(2, dz);
        z += nz;
        boolean hitH = nx != dx || nz != dz;
        if (hitH && (onGroundM || dy < 0 && oy - y < 0.01) && !inWater) {
            // try stepping up
            double sx = x, sy = y, sz = z;
            x = ox;
            z = oz;
            y = oy;
            double up = sweep(1, STEP);
            y += up;
            double sx2 = sweep(0, dx);
            x += sx2;
            double sz2 = sweep(2, dz);
            z += sz2;
            double down = sweep(1, -up - 0.01);
            y += down;
            double gained = Math.abs(x - ox) + Math.abs(z - oz);
            if (gained > Math.abs(sx - ox) + Math.abs(sz - oz) + 1e-4) {
                onGroundM = true;
                nx = sx2;
                nz = sz2;
            } else {
                x = sx;
                y = sy;
                z = sz;
            }
        }
        if (nx != dx) vxM = 0;
        if (nz != dz) vzM = 0;
    }

    /** How far we can move along an axis (0 x, 1 y, 2 z) before hitting a solid block (vanilla-style AABB clipping). */
    private double sweep(int axis, double d) {
        if (d == 0) return 0;
        double[] lo = {x - W, y, z - W}, hi = {x + W, y + H, z + W};
        // the area swept by the box along the axis
        double[] slo = lo.clone(), shi = hi.clone();
        if (d > 0) shi[axis] += d;
        else slo[axis] += d;
        int x0 = (int) Math.floor(slo[0]), x1 = (int) Math.floor(shi[0] - 1e-7);
        int y0 = (int) Math.floor(slo[1]), y1 = (int) Math.floor(shi[1] - 1e-7);
        int z0 = (int) Math.floor(slo[2]), z1 = (int) Math.floor(shi[2] - 1e-7);
        if ((long) (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1) > 4000) return 0; // absurd move, refuse
        for (int bx = x0; bx <= x1; bx++)
            for (int by = y0; by <= y1; by++)
                for (int bz = z0; bz <= z1; bz++) {
                    if (!solid(bx, by, bz)) continue;
                    double[] b0 = {bx, by, bz}, b1 = {bx + 1, by + 1, bz + 1};
                    // must overlap on the other two axes
                    boolean overlap = true;
                    for (int a = 0; a < 3; a++) {
                        if (a == axis) continue;
                        if (hi[a] <= b0[a] + 1e-7 || lo[a] >= b1[a] - 1e-7) overlap = false;
                    }
                    if (!overlap) continue;
                    if (d > 0 && b0[axis] >= hi[axis] - 1e-7) d = Math.min(d, b0[axis] - hi[axis]);
                    else if (d < 0 && b1[axis] <= lo[axis] + 1e-7) d = Math.max(d, b1[axis] - lo[axis]);
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

    private void send(Buf b) throws IOException {
        byte[] body = b.toBytes();
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
                Proto.P_C_PLAYER_INFO, Proto.P_C_PLAYER_REMOVE, Proto.P_C_PLAYER_CHAT};
        for (int k : want) if (ids[k] == pid) return true;
        if (MapData.supported && (pid == id(Proto.P_C_MAP_CHUNK) || pid == id(Proto.P_C_UNLOAD_CHUNK))) return true;
        if (pid == id(Proto.P_C_BLOCK_CHANGE) || pid == id(Proto.P_C_MULTI_BLOCK) || pid == id(Proto.P_C_WINDOW_ITEMS)
                || pid == id(Proto.P_C_SET_SLOT) || pid == id(Proto.P_C_OPEN_WINDOW) || pid == id(Proto.P_C_CLOSE_WINDOW)) return true;
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
