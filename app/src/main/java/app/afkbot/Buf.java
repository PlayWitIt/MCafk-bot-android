package app.afkbot;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Minimal Minecraft protocol packet writer. */
final class Buf {
    private final ByteArrayOutputStream out = new ByteArrayOutputStream(64);

    Buf(int packetId) {
        varInt(packetId);
    }

    Buf() {
    }

    Buf varInt(int v) {
        while ((v & ~0x7F) != 0) {
            out.write((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.write(v);
        return this;
    }

    Buf bytes(byte[] b) {
        out.write(b, 0, b.length);
        return this;
    }

    Buf byteArray(byte[] b) {
        varInt(b.length);
        return bytes(b);
    }

    Buf str(String s) {
        return byteArray(s.getBytes(StandardCharsets.UTF_8));
    }

    Buf u8(int v) {
        out.write(v & 0xFF);
        return this;
    }

    Buf bool(boolean b) {
        return u8(b ? 1 : 0);
    }

    Buf u16(int v) {
        out.write((v >>> 8) & 0xFF);
        out.write(v & 0xFF);
        return this;
    }

    Buf i32(int v) {
        out.write(v >>> 24);
        out.write(v >>> 16);
        out.write(v >>> 8);
        out.write(v);
        return this;
    }

    Buf i64(long v) {
        i32((int) (v >>> 32));
        return i32((int) v);
    }

    Buf f32(float f) {
        return i32(Float.floatToIntBits(f));
    }

    Buf f64(double d) {
        return i64(Double.doubleToLongBits(d));
    }

    Buf uuid(UUID u) {
        i64(u.getMostSignificantBits());
        return i64(u.getLeastSignificantBits());
    }

    byte[] toBytes() {
        return out.toByteArray();
    }

    static int varIntSize(int v) {
        int n = 1;
        while ((v & ~0x7F) != 0) {
            n++;
            v >>>= 7;
        }
        return n;
    }

    /** Packet reader over a byte array. */
    static final class In {
        final byte[] b;
        int p;

        In(byte[] b, int start) {
            this.b = b;
            this.p = start;
        }

        int remaining() {
            return b.length - p;
        }

        int u8() throws IOException {
            if (p >= b.length) throw new IOException("Packet too short");
            return b[p++] & 0xFF;
        }

        boolean bool() throws IOException {
            return u8() != 0;
        }

        int varInt() throws IOException {
            int v = 0, shift = 0, x;
            do {
                x = u8();
                v |= (x & 0x7F) << shift;
                shift += 7;
                if (shift > 35) throw new IOException("VarInt too big");
            } while ((x & 0x80) != 0);
            return v;
        }

        int u16() throws IOException {
            return (u8() << 8) | u8();
        }

        int i32() throws IOException {
            return (u8() << 24) | (u8() << 16) | (u8() << 8) | u8();
        }

        long i64() throws IOException {
            return ((long) i32() << 32) | (i32() & 0xFFFFFFFFL);
        }

        float f32() throws IOException {
            return Float.intBitsToFloat(i32());
        }

        double f64() throws IOException {
            return Double.longBitsToDouble(i64());
        }

        byte[] bytes(int n) throws IOException {
            if (n < 0 || n > remaining()) throw new IOException("Bad length " + n);
            byte[] r = new byte[n];
            System.arraycopy(b, p, r, 0, n);
            p += n;
            return r;
        }

        byte[] byteArray() throws IOException {
            return bytes(varInt());
        }

        String str() throws IOException {
            return new String(byteArray(), StandardCharsets.UTF_8);
        }

        UUID uuid() throws IOException {
            return new UUID(i64(), i64());
        }
    }
}
