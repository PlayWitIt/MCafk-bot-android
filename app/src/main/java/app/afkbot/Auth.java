package app.afkbot;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.UUID;

/**
 * Microsoft account -> Xbox Live -> Minecraft Java sign-in, using the
 * device-code flow (you type a short code at microsoft.com/link).
 */
final class Auth {
    private Auth() {
    }

    // Same public client + scope used by the popular open-source bot library (Minecraft for Nintendo Switch title).
    private static final String CLIENT_ID = "00000000441cc96b";
    private static final String SCOPE = "service::user.auth.xboxlive.com::MBI_SSL";
    private static final String UA = "MinecraftLauncher/2.2.10675";

    static final class Session {
        final String name, uuid, accessToken;

        Session(String name, String uuid, String accessToken) {
            this.name = name;
            this.uuid = uuid;
            this.accessToken = accessToken;
        }
    }

    static final class AuthException extends Exception {
        AuthException(String m) {
            super(m);
        }
    }

    interface Cancel {
        boolean cancelled();
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("auth", Context.MODE_PRIVATE);
    }

    static String signedInName(Context c) {
        SharedPreferences p = prefs(c);
        return p.getString("msa_refresh", null) == null ? null : p.getString("mc_name", "?");
    }

    static void signOut(Context c) {
        prefs(c).edit().clear().apply();
    }

    /** Full interactive sign-in. Blocks until the user enters the code (or it expires). */
    static Session signIn(Context c, Cancel cancel) throws Exception {
        JSONObject dc = new JSONObject(post("https://login.live.com/oauth20_connect.srf", form(
                "client_id", CLIENT_ID, "scope", SCOPE, "response_type", "device_code"), "application/x-www-form-urlencoded", null));
        String userCode = dc.getString("user_code");
        String deviceCode = dc.getString("device_code");
        String verify = dc.optString("verification_uri", "https://www.microsoft.com/link");
        int interval = Math.max(1, dc.optInt("interval", 5));
        long expires = System.currentTimeMillis() + dc.optInt("expires_in", 900) * 1000L;
        Hub.authCode = userCode;
        Hub.authUrl = verify;
        Hub.log("Sign-in code: " + userCode + "  (enter it at " + verify + ")");
        Hub.fire();
        try {
            while (System.currentTimeMillis() < expires) {
                Thread.sleep(interval * 1000L);
                if (cancel != null && cancel.cancelled()) throw new AuthException("Sign-in cancelled");
                Resp r = request("POST", "https://login.live.com/oauth20_token.srf?client_id=" + CLIENT_ID, form(
                        "client_id", CLIENT_ID, "device_code", deviceCode,
                        "grant_type", "urn:ietf:params:oauth:grant-type:device_code"), "application/x-www-form-urlencoded", null);
                JSONObject j = new JSONObject(r.body);
                if (j.has("error")) {
                    String e = j.getString("error");
                    if (e.equals("authorization_pending")) continue;
                    if (e.equals("slow_down")) {
                        interval += 5;
                        continue;
                    }
                    throw new AuthException("Microsoft sign-in failed: " + j.optString("error_description", e));
                }
                Hub.authCode = null;
                Hub.log("Microsoft account accepted, getting Minecraft profile...");
                return finish(c, j);
            }
            throw new AuthException("Sign-in code expired, try again");
        } finally {
            Hub.authCode = null;
            Hub.fire();
        }
    }

    /** Returns a usable session, refreshing tokens silently if needed. */
    static synchronized Session session(Context c, boolean forceRefresh) throws Exception {
        SharedPreferences p = prefs(c);
        String refresh = p.getString("msa_refresh", null);
        if (refresh == null) throw new AuthException("Not signed in. Tap \"Sign in with Microsoft\" first.");
        long exp = p.getLong("mc_expires", 0);
        if (!forceRefresh && System.currentTimeMillis() < exp - 10 * 60 * 1000L) {
            return new Session(p.getString("mc_name", ""), p.getString("mc_uuid", ""), p.getString("mc_token", ""));
        }
        Hub.log("Refreshing Minecraft login...");
        Resp r = request("POST", "https://login.live.com/oauth20_token.srf", form(
                "client_id", CLIENT_ID, "scope", SCOPE, "grant_type", "refresh_token", "refresh_token", refresh),
                "application/x-www-form-urlencoded", null);
        JSONObject j = new JSONObject(r.body);
        if (j.has("error") || r.code != 200) {
            String e = j.optString("error_description", j.optString("error", "HTTP " + r.code));
            if (r.code == 400 || r.code == 401) {
                p.edit().remove("msa_refresh").apply();
                throw new AuthException("Saved login expired (" + e + "). Please sign in again.");
            }
            throw new IOException("Token refresh failed: " + e);
        }
        return finish(c, j);
    }

    private static Session finish(Context c, JSONObject msa) throws Exception {
        String msaToken = msa.getString("access_token");
        String newRefresh = msa.optString("refresh_token", null);
        if (newRefresh != null) prefs(c).edit().putString("msa_refresh", newRefresh).apply();

        String[] xsts = xboxXsts(msaToken);
        JSONObject mc = new JSONObject(post("https://api.minecraftservices.com/authentication/login_with_xbox",
                new JSONObject().put("identityToken", "XBL3.0 x=" + xsts[0] + ";" + xsts[1]).toString(), "application/json", null));
        String mcToken = mc.getString("access_token");
        long expiresIn = mc.optLong("expires_in", 86400);

        Resp pr = request("GET", "https://api.minecraftservices.com/minecraft/profile", null, null, mcToken);
        if (pr.code == 404)
            throw new AuthException("This Microsoft account doesn't own Minecraft: Java Edition (no profile found).");
        if (pr.code != 200) throw new IOException("Profile request failed: HTTP " + pr.code + " " + pr.body);
        JSONObject prof = new JSONObject(pr.body);
        String name = prof.getString("name");
        String id = prof.getString("id");
        prefs(c).edit()
                .putString("mc_token", mcToken)
                .putLong("mc_expires", System.currentTimeMillis() + expiresIn * 1000L)
                .putString("mc_name", name)
                .putString("mc_uuid", id)
                .apply();
        Hub.log("Signed in as " + name);
        return new Session(name, id, mcToken);
    }

    // ---------------- Xbox Live ----------------

    private static KeyPair keyPair;

    /** @return {userHash, xstsToken} */
    private static String[] xboxXsts(String msaToken) throws Exception {
        if (keyPair == null) {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp256r1"));
            keyPair = g.generateKeyPair();
        }
        JSONObject jwk = jwk((ECPublicKey) keyPair.getPublic());

        JSONObject user = new JSONObject()
                .put("RelyingParty", "http://auth.xboxlive.com")
                .put("TokenType", "JWT")
                .put("Properties", new JSONObject()
                        .put("AuthMethod", "RPS")
                        .put("SiteName", "user.auth.xboxlive.com")
                        .put("RpsTicket", "t=" + msaToken));
        String userToken = new JSONObject(xbl("https://user.auth.xboxlive.com/user/authenticate", user, "2")).getString("Token");

        String deviceToken = null, titleToken = null;
        try {
            JSONObject dev = new JSONObject()
                    .put("Properties", new JSONObject()
                            .put("AuthMethod", "ProofOfPossession")
                            .put("Id", "{" + UUID.randomUUID() + "}")
                            .put("DeviceType", "Nintendo")
                            .put("SerialNumber", "{" + UUID.randomUUID() + "}")
                            .put("Version", "0.0.0")
                            .put("ProofKey", jwk))
                    .put("RelyingParty", "http://auth.xboxlive.com")
                    .put("TokenType", "JWT");
            deviceToken = new JSONObject(xbl("https://device.auth.xboxlive.com/device/authenticate", dev, "1")).getString("Token");
            JSONObject title = new JSONObject()
                    .put("Properties", new JSONObject()
                            .put("AuthMethod", "RPS")
                            .put("DeviceToken", deviceToken)
                            .put("RpsTicket", "t=" + msaToken)
                            .put("SiteName", "user.auth.xboxlive.com")
                            .put("ProofKey", jwk))
                    .put("RelyingParty", "http://auth.xboxlive.com")
                    .put("TokenType", "JWT");
            titleToken = new JSONObject(xbl("https://title.auth.xboxlive.com/title/authenticate", title, "1")).getString("Token");
        } catch (Exception e) {
            Hub.log("Xbox device/title step skipped: " + e.getMessage());
            deviceToken = null;
            titleToken = null;
        }

        JSONObject props = new JSONObject()
                .put("UserTokens", new JSONArray().put(userToken))
                .put("ProofKey", jwk)
                .put("SandboxId", "RETAIL");
        if (deviceToken != null) props.put("DeviceToken", deviceToken);
        if (titleToken != null) props.put("TitleToken", titleToken);
        JSONObject xreq = new JSONObject()
                .put("RelyingParty", "rp://api.minecraftservices.com/")
                .put("TokenType", "JWT")
                .put("Properties", props);
        Resp r = xblRaw("https://xsts.auth.xboxlive.com/xsts/authorize", xreq, "1");
        if (r.code != 200) {
            long xerr = 0;
            try {
                xerr = new JSONObject(r.body).optLong("XErr", 0);
            } catch (Exception ignored) {
            }
            throw new AuthException(xboxError(xerr, r));
        }
        JSONObject x = new JSONObject(r.body);
        String uhs = x.getJSONObject("DisplayClaims").getJSONArray("xui").getJSONObject(0).getString("uhs");
        return new String[]{uhs, x.getString("Token")};
    }

    private static String xboxError(long xerr, Resp r) {
        if (xerr == 2148916233L) return "This account has no Xbox profile yet. Sign in once at xbox.com to create one, then try again.";
        if (xerr == 2148916235L) return "Xbox Live isn't available in your account's country/region.";
        if (xerr == 2148916236L || xerr == 2148916237L) return "Your account needs adult verification (South Korea). Verify at account.microsoft.com.";
        if (xerr == 2148916238L) return "This is a child account. An adult must add it to a Microsoft family group first.";
        if (xerr == 2148916227L) return "This account is banned from Xbox Live.";
        if (xerr == 2148916229L) return "This account isn't allowed to play online (parental controls).";
        if (xerr == 2148916234L) return "Accept the Xbox terms of service at xbox.com first.";
        return "Xbox Live sign-in failed (HTTP " + r.code + ", XErr " + xerr + ")";
    }

    private static String xbl(String url, JSONObject body, String contract) throws Exception {
        Resp r = xblRaw(url, body, contract);
        if (r.code != 200) throw new IOException(new URL(url).getHost() + " returned HTTP " + r.code + " " + r.body);
        return r.body;
    }

    private static Resp xblRaw(String url, JSONObject body, String contract) throws Exception {
        String s = body.toString();
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setRequestMethod("POST");
        h.setDoOutput(true);
        h.setConnectTimeout(20000);
        h.setReadTimeout(20000);
        h.setRequestProperty("Content-Type", "application/json");
        h.setRequestProperty("Accept", "application/json");
        h.setRequestProperty("Cache-Control", "no-store, must-revalidate, no-cache");
        h.setRequestProperty("x-xbl-contract-version", contract);
        h.setRequestProperty("Signature", sign(new URL(url).getPath(), s));
        return send(h, s.getBytes(StandardCharsets.UTF_8));
    }

    private static JSONObject jwk(ECPublicKey k) throws Exception {
        return new JSONObject()
                .put("kty", "EC")
                .put("x", b64url(unsigned32(k.getW().getAffineX())))
                .put("y", b64url(unsigned32(k.getW().getAffineY())))
                .put("crv", "P-256")
                .put("alg", "ES256")
                .put("use", "sig");
    }

    private static String sign(String path, String body) throws Exception {
        long ts = (System.currentTimeMillis() / 1000L + 11644473600L) * 10000000L;
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(ByteBuffer.allocate(4).putInt(1).array());
        b.write(0);
        b.write(ByteBuffer.allocate(8).putLong(ts).array());
        b.write(0);
        b.write("POST".getBytes(StandardCharsets.UTF_8));
        b.write(0);
        b.write(path.getBytes(StandardCharsets.UTF_8));
        b.write(0);
        b.write(0); // empty authorization token
        b.write(body.getBytes(StandardCharsets.UTF_8));
        b.write(0);
        Signature sig = Signature.getInstance("SHA256withECDSA");
        sig.initSign(keyPair.getPrivate());
        sig.update(b.toByteArray());
        byte[] p1363 = derToP1363(sig.sign());
        ByteBuffer h = ByteBuffer.allocate(4 + 8 + 64);
        h.putInt(1).putLong(ts).put(p1363);
        return Base64.encodeToString(h.array(), Base64.NO_WRAP);
    }

    private static byte[] derToP1363(byte[] der) {
        // SEQUENCE { INTEGER r, INTEGER s }
        int i = 2;
        if ((der[1] & 0x80) != 0) i = 2 + (der[1] & 0x7F);
        int rl = der[i + 1];
        byte[] r = new byte[rl];
        System.arraycopy(der, i + 2, r, 0, rl);
        i = i + 2 + rl;
        int sl = der[i + 1];
        byte[] s = new byte[sl];
        System.arraycopy(der, i + 2, s, 0, sl);
        byte[] out = new byte[64];
        System.arraycopy(unsigned32(new BigInteger(1, r)), 0, out, 0, 32);
        System.arraycopy(unsigned32(new BigInteger(1, s)), 0, out, 32, 32);
        return out;
    }

    private static byte[] unsigned32(BigInteger v) {
        byte[] b = v.toByteArray();
        byte[] out = new byte[32];
        if (b.length >= 32) System.arraycopy(b, b.length - 32, out, 0, 32);
        else System.arraycopy(b, 0, out, 32 - b.length, b.length);
        return out;
    }

    private static String b64url(byte[] b) {
        return Base64.encodeToString(b, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    // ---------------- Mojang session server ----------------

    /** Tells Mojang we are joining the server (needed for online-mode servers). */
    static void joinServer(Session s, String serverHash) throws Exception {
        JSONObject body = new JSONObject()
                .put("accessToken", s.accessToken)
                .put("selectedProfile", s.uuid.replace("-", ""))
                .put("serverId", serverHash);
        Resp r = request("POST", "https://sessionserver.mojang.com/session/minecraft/join", body.toString(), "application/json", null);
        if (r.code == 204 || r.code == 200) return;
        if (r.code == 403 || r.code == 401) throw new AuthException("Mojang rejected the login token (HTTP " + r.code + "). It will be refreshed.");
        throw new IOException("Mojang session server error HTTP " + r.code + " " + r.body);
    }

    // ---------------- HTTP helpers ----------------

    static final class Resp {
        final int code;
        final String body;

        Resp(int code, String body) {
            this.code = code;
            this.body = body;
        }
    }

    private static String form(String... kv) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < kv.length; i += 2) {
            if (sb.length() > 0) sb.append('&');
            sb.append(URLEncoder.encode(kv[i], "UTF-8")).append('=').append(URLEncoder.encode(kv[i + 1], "UTF-8"));
        }
        return sb.toString();
    }

    private static String post(String url, String body, String type, String bearer) throws Exception {
        Resp r = request("POST", url, body, type, bearer);
        if (r.code / 100 != 2) throw new IOException(new URL(url).getHost() + " returned HTTP " + r.code + " " + r.body);
        return r.body;
    }

    static Resp request(String method, String url, String body, String type, String bearer) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setRequestMethod(method);
        h.setConnectTimeout(20000);
        h.setReadTimeout(20000);
        h.setRequestProperty("User-Agent", UA);
        h.setRequestProperty("Accept", "application/json");
        if (bearer != null) h.setRequestProperty("Authorization", "Bearer " + bearer);
        if (type != null) h.setRequestProperty("Content-Type", type);
        if (body != null) h.setDoOutput(true);
        return send(h, body == null ? null : body.getBytes(StandardCharsets.UTF_8));
    }

    private static Resp send(HttpURLConnection h, byte[] body) throws IOException {
        try {
            if (body != null) {
                OutputStream o = h.getOutputStream();
                o.write(body);
                o.close();
            }
            int code = h.getResponseCode();
            InputStream in = code >= 400 ? h.getErrorStream() : h.getInputStream();
            String text = "";
            if (in != null) {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
                in.close();
                text = new String(b.toByteArray(), StandardCharsets.UTF_8);
            }
            return new Resp(code, text);
        } finally {
            h.disconnect();
        }
    }
}
