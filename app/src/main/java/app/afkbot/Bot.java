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

    static final class Config {
        String address = "";
        boolean offline = false;
        String offlineName = "AFKBot";
        int jumpSeconds = 30;
        boolean autoReconnect = true;
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
                break;
            } catch (Auth.AuthException a) {
                Hub.log("Login problem: " + a.getMessage());
                if (a.getMessage() != null && a.getMessage().toLowerCase(Locale.US).contains("sign in")) {
                    Hub.status(a.getMessage(), Hub.RED);
                    break;
                }
            } catch (Kick k) {
                Hub.log("Kicked: " + k.getMessage());
                Hub.status("Kicked: " + firstLine(k.getMessage()), Hub.RED);
            } catch (Exception e) {
                if (!stopped) {
                    String m = e instanceof SocketTimeoutException ? "server stopped responding (timed out)"
                            : e instanceof EOFException ? "server closed the connection" : String.valueOf(e.getMessage());
                    Hub.log("Disconnected: " + m);
                    Hub.status("Disconnected", Hub.RED);
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

    private void cleanup() {
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
            throw new IOException("Server not reachable (" + e.getMessage() + ")");
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

        while (!stopped) {
            Buf.In p = readPacket(in);
            if (p == null) continue;
            int pid = p.varInt();
            handle(pid, p, din);
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
        int sid = id(Proto.C_S_SETTINGS);
        if (sid < 0) return;
        Buf b = new Buf(sid).str("en_us").u8(2) // view distance 2: less data for the phone
                .varInt(0).bool(true).u8(0x7F).varInt(1).bool(false).bool(true);
        if (proto >= 768) b.varInt(2); // particles: minimal
        send(b);
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
            synchronized (this) {
                if (joinedAt == 0) joinedAt = System.currentTimeMillis();
            }
            Hub.log("Joined the server!");
            if (legacy() && id(Proto.P_S_SETTINGS) >= 0) {
                send(new Buf(id(Proto.P_S_SETTINGS)).str("en_us").u8(2).varInt(0).bool(true).u8(0x7F).varInt(1));
            }
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
            if (died) {
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
                if (msg.length() > 300) msg = msg.substring(0, 300) + "...";
                Hub.log("[Chat] " + msg);
            }
        } else if (pid == id(Proto.P_C_RESOURCE_PACK)) {
            answerResourcePack(p, id(Proto.P_S_RESOURCE_PACK));
        } else if (pid == id(Proto.P_C_COOKIE_REQ)) {
            send(new Buf(id(Proto.P_S_COOKIE_RESP)).str(p.str()).bool(false));
        }
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
            first = !havePos;
            havePos = true;
            if (first) nextJumpAt = System.currentTimeMillis() + 5000;
        }
        send(new Buf(id(Proto.P_S_TELEPORT_CONFIRM)).varInt(tid));
        sendMove(true, true, false);
        if (first) {
            if (!loadedSent && id(Proto.P_S_PLAYER_LOADED) >= 0) {
                send(new Buf(id(Proto.P_S_PLAYER_LOADED)));
                loadedSent = true;
            }
            Hub.log(String.format(Locale.US, "In the world at %.1f, %.1f, %.1f - AFK mode on (jump every %ds)", x, y, z, cfg.jumpSeconds));
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

    private void onTick() throws IOException {
        if (state != PLAY) return;
        boolean doJumpStart = false, doMove = false, ground = true, detail;
        synchronized (this) {
            if (!havePos || respawnPending) return;
            tick++;
            long now = System.currentTimeMillis();
            if (jumping) {
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
            } else if (now >= nextJumpAt) {
                jumping = true;
                groundY = y;
                vy = 0.42;
                yaw = (yaw + (rnd.nextFloat() * 120f - 60f)) % 360f;
                pitch = rnd.nextFloat() * 20f - 5f;
                jumps++;
                nextJumpAt = now + Math.max(2, cfg.jumpSeconds) * 1000L;
                doJumpStart = true;
            } else if (tick % 20 == 0) {
                doMove = true;
            }
            detail = tick % 20 == 0;
        }
        if (doJumpStart) {
            send(new Buf(id(Proto.P_S_SWING)).varInt(0));
            sendMove(true, true, true);
        } else if (doMove) {
            sendMove(true, false, ground);
        }
        if (detail) updateDetail();
    }

    private void updateDetail() {
        long left;
        String d;
        synchronized (this) {
            left = Math.max(0, (nextJumpAt - System.currentTimeMillis()) / 1000);
            long up = joinedAt > 0 ? (System.currentTimeMillis() - joinedAt) / 1000 : 0;
            d = String.format(Locale.US, "Position %.1f, %.1f, %.1f\nHealth %.0f/20  •  Jumps %d  •  Next jump in %ds\nOnline for %s",
                    x, groundY, z, health, jumps, left, duration(up));
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
        return pid == id(Proto.P_C_KEEPALIVE) || pid == id(Proto.P_C_PING) || pid == id(Proto.P_C_POSITION)
                || pid == id(Proto.P_C_LOGIN) || pid == id(Proto.P_C_DISCONNECT) || pid == id(Proto.P_C_START_CONFIG)
                || pid == id(Proto.P_C_HEALTH) || pid == id(Proto.P_C_SYSTEM_CHAT) || pid == id(Proto.P_C_RESOURCE_PACK)
                || pid == id(Proto.P_C_COOKIE_REQ);
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
            if (sp < 0) throw new IOException("server did not report its version");
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

    private static String[] srvLookup(String host) {
        try {
            URL u = new URL("https://dns.google/resolve?type=SRV&name=" + URLEncoder.encode("_minecraft._tcp." + host, "UTF-8"));
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
            if (ans == null) return null;
            for (int i = 0; i < ans.length(); i++) {
                JSONObject a = ans.getJSONObject(i);
                if (a.optInt("type") != 33) continue;
                String[] parts = a.getString("data").trim().split("\\s+");
                if (parts.length < 4) continue;
                String target = parts[3];
                if (target.endsWith(".")) target = target.substring(0, target.length() - 1);
                return new String[]{target, parts[2]};
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
