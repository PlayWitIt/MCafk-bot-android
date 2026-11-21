package android.opengl;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.Buffer;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Desktop stand-in for android.opengl.GLES20 that calls the real OpenGL ES 2 library (Mesa) through Java's foreign
 * function API, so the Play screen's GameRenderer runs unmodified on a PC (desktop/run.sh gl). Only the calls the
 * renderer makes are here, with Android's signatures.
 */
public class GLES20 {
    public static final int GL_ARRAY_BUFFER = 0x8892, GL_ELEMENT_ARRAY_BUFFER = 0x8893, GL_STATIC_DRAW = 0x88E4,
            GL_BACK = 0x0405, GL_BLEND = 0x0BE2, GL_CULL_FACE = 0x0B44, GL_DEPTH_TEST = 0x0B71, GL_POLYGON_OFFSET_FILL = 0x8037,
            GL_COLOR_BUFFER_BIT = 0x4000, GL_DEPTH_BUFFER_BIT = 0x0100, GL_COMPILE_STATUS = 0x8B81, GL_LINK_STATUS = 0x8B82,
            GL_VERTEX_SHADER = 0x8B31, GL_FRAGMENT_SHADER = 0x8B30, GL_LEQUAL = 0x0203,
            GL_LINES = 0x0001, GL_TRIANGLES = 0x0004, GL_FLOAT = 0x1406, GL_SHORT = 0x1402, GL_UNSIGNED_SHORT = 0x1403,
            GL_UNSIGNED_BYTE = 0x1401, GL_SRC_ALPHA = 0x0302, GL_ONE_MINUS_SRC_ALPHA = 0x0303, GL_SRC_COLOR = 0x0300,
            GL_DST_COLOR = 0x0306, GL_TEXTURE0 = 0x84C0, GL_TEXTURE_2D = 0x0DE1, GL_TEXTURE_MAG_FILTER = 0x2800,
            GL_TEXTURE_MIN_FILTER = 0x2801, GL_TEXTURE_WRAP_S = 0x2802, GL_TEXTURE_WRAP_T = 0x2803, GL_NEAREST = 0x2600,
            GL_CLAMP_TO_EDGE = 0x812F, GL_REPEAT = 0x2901, GL_ONE = 1, GL_RGBA = 0x1908, GL_NO_ERROR = 0;

    private static final Linker L = Linker.nativeLinker();
    private static final SymbolLookup GL = SymbolLookup.libraryLookup("libGLESv2.so.2", Arena.global());

    private static MethodHandle h(String name, FunctionDescriptor fd) {
        return L.downcallHandle(GL.find(name).orElseThrow(() -> new IllegalStateException("no " + name)), fd);
    }

    private static RuntimeException wrap(Throwable t) {
        return t instanceof RuntimeException ? (RuntimeException) t : new RuntimeException(t);
    }

    private static MemorySegment ptr(Buffer b) {
        return b == null ? MemorySegment.NULL : MemorySegment.ofBuffer(b);
    }

    private static final FunctionDescriptor V_I = FunctionDescriptor.ofVoid(JAVA_INT), V_II = FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT),
            V_III = FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT, JAVA_INT), V_IIII = FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT),
            V_FFFF = FunctionDescriptor.ofVoid(JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT);

    private static final MethodHandle ACTIVE_TEXTURE = h("glActiveTexture", V_I), ATTACH = h("glAttachShader", V_II), BIND_BUFFER = h("glBindBuffer", V_II),
            BIND_TEXTURE = h("glBindTexture", V_II), BLEND = h("glBlendFunc", V_II), BUFFER_DATA = h("glBufferData", FunctionDescriptor.ofVoid(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_INT)),
            CLEAR = h("glClear", V_I), CLEAR_COLOR = h("glClearColor", V_FFFF), COMPILE = h("glCompileShader", V_I),
            CREATE_PROGRAM = h("glCreateProgram", FunctionDescriptor.of(JAVA_INT)), CREATE_SHADER = h("glCreateShader", FunctionDescriptor.of(JAVA_INT, JAVA_INT)),
            CULL = h("glCullFace", V_I), DELETE_BUFFERS = h("glDeleteBuffers", FunctionDescriptor.ofVoid(JAVA_INT, ADDRESS)),
            DELETE_TEXTURES = h("glDeleteTextures", FunctionDescriptor.ofVoid(JAVA_INT, ADDRESS)), DEPTH_FUNC = h("glDepthFunc", V_I),
            DEPTH_MASK = h("glDepthMask", FunctionDescriptor.ofVoid(JAVA_BYTE)), DISABLE = h("glDisable", V_I), ENABLE = h("glEnable", V_I),
            DISABLE_ATTR = h("glDisableVertexAttribArray", V_I), ENABLE_ATTR = h("glEnableVertexAttribArray", V_I),
            DRAW_ARRAYS = h("glDrawArrays", V_III), DRAW_ELEMENTS = h("glDrawElements", FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS)),
            GEN_BUFFERS = h("glGenBuffers", FunctionDescriptor.ofVoid(JAVA_INT, ADDRESS)), GEN_TEXTURES = h("glGenTextures", FunctionDescriptor.ofVoid(JAVA_INT, ADDRESS)),
            ATTRIB_LOC = h("glGetAttribLocation", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS)),
            UNIFORM_LOC = h("glGetUniformLocation", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS)),
            PROGRAM_LOG = h("glGetProgramInfoLog", FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS)),
            SHADER_LOG = h("glGetShaderInfoLog", FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS)),
            PROGRAM_IV = h("glGetProgramiv", FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT, ADDRESS)),
            SHADER_IV = h("glGetShaderiv", FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT, ADDRESS)),
            LINE_WIDTH = h("glLineWidth", FunctionDescriptor.ofVoid(JAVA_FLOAT)), LINK = h("glLinkProgram", V_I),
            POLYGON_OFFSET = h("glPolygonOffset", FunctionDescriptor.ofVoid(JAVA_FLOAT, JAVA_FLOAT)),
            SHADER_SOURCE = h("glShaderSource", FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS)),
            TEX_PARAM = h("glTexParameteri", V_III), TEX_IMAGE = h("glTexImage2D", FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS)),
            U1F = h("glUniform1f", FunctionDescriptor.ofVoid(JAVA_INT, JAVA_FLOAT)), U1I = h("glUniform1i", V_II),
            U2F = h("glUniform2f", FunctionDescriptor.ofVoid(JAVA_INT, JAVA_FLOAT, JAVA_FLOAT)),
            U3F = h("glUniform3f", FunctionDescriptor.ofVoid(JAVA_INT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT)),
            U4F = h("glUniform4f", FunctionDescriptor.ofVoid(JAVA_INT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT)),
            UMAT4 = h("glUniformMatrix4fv", FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT, JAVA_BYTE, ADDRESS)),
            USE = h("glUseProgram", V_I), ATTRIB_PTR = h("glVertexAttribPointer", FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_BYTE, JAVA_INT, ADDRESS)),
            VIEWPORT = h("glViewport", V_IIII), GET_ERROR = h("glGetError", FunctionDescriptor.of(JAVA_INT)),
            ATTRIB4F = h("glVertexAttrib4f", FunctionDescriptor.ofVoid(JAVA_INT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT)),
            READ_PIXELS = h("glReadPixels", FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS));

    public static void glActiveTexture(int t) { try { ACTIVE_TEXTURE.invoke(t); } catch (Throwable e) { throw wrap(e); } }
    public static void glAttachShader(int p, int s) { try { ATTACH.invoke(p, s); } catch (Throwable e) { throw wrap(e); } }
    public static void glBindBuffer(int t, int b) { try { BIND_BUFFER.invoke(t, b); } catch (Throwable e) { throw wrap(e); } }
    public static void glBindTexture(int t, int b) { try { BIND_TEXTURE.invoke(t, b); } catch (Throwable e) { throw wrap(e); } }
    public static void glBlendFunc(int s, int d) { try { BLEND.invoke(s, d); } catch (Throwable e) { throw wrap(e); } }
    public static void glBufferData(int t, int size, Buffer data, int usage) { try { BUFFER_DATA.invoke(t, (long) size, ptr(data), usage); } catch (Throwable e) { throw wrap(e); } }
    public static void glClear(int m) { try { CLEAR.invoke(m); } catch (Throwable e) { throw wrap(e); } }
    public static void glClearColor(float r, float g, float b, float a) { try { CLEAR_COLOR.invoke(r, g, b, a); } catch (Throwable e) { throw wrap(e); } }
    public static void glCompileShader(int s) { try { COMPILE.invoke(s); } catch (Throwable e) { throw wrap(e); } }
    public static int glCreateProgram() { try { return (int) CREATE_PROGRAM.invoke(); } catch (Throwable e) { throw wrap(e); } }
    public static int glCreateShader(int t) { try { return (int) CREATE_SHADER.invoke(t); } catch (Throwable e) { throw wrap(e); } }
    public static void glCullFace(int m) { try { CULL.invoke(m); } catch (Throwable e) { throw wrap(e); } }
    public static void glDepthFunc(int f) { try { DEPTH_FUNC.invoke(f); } catch (Throwable e) { throw wrap(e); } }
    public static void glDepthMask(boolean f) { try { DEPTH_MASK.invoke((byte) (f ? 1 : 0)); } catch (Throwable e) { throw wrap(e); } }
    public static void glDisable(int c) { try { DISABLE.invoke(c); } catch (Throwable e) { throw wrap(e); } }
    public static void glEnable(int c) { try { ENABLE.invoke(c); } catch (Throwable e) { throw wrap(e); } }
    public static void glDisableVertexAttribArray(int i) { try { DISABLE_ATTR.invoke(i); } catch (Throwable e) { throw wrap(e); } }
    public static void glEnableVertexAttribArray(int i) { try { ENABLE_ATTR.invoke(i); } catch (Throwable e) { throw wrap(e); } }
    public static void glDrawArrays(int m, int f, int c) { try { DRAW_ARRAYS.invoke(m, f, c); } catch (Throwable e) { throw wrap(e); } }
    public static void glDrawElements(int m, int c, int t, int offset) { try { DRAW_ELEMENTS.invoke(m, c, t, MemorySegment.ofAddress(offset)); } catch (Throwable e) { throw wrap(e); } }
    public static void glLineWidth(float w) { try { LINE_WIDTH.invoke(w); } catch (Throwable e) { throw wrap(e); } }
    public static void glLinkProgram(int p) { try { LINK.invoke(p); } catch (Throwable e) { throw wrap(e); } }
    public static void glPolygonOffset(float f, float u) { try { POLYGON_OFFSET.invoke(f, u); } catch (Throwable e) { throw wrap(e); } }
    public static void glTexParameteri(int t, int p, int v) { try { TEX_PARAM.invoke(t, p, v); } catch (Throwable e) { throw wrap(e); } }
    public static void glUniform1f(int l, float a) { try { U1F.invoke(l, a); } catch (Throwable e) { throw wrap(e); } }
    public static void glUniform1i(int l, int a) { try { U1I.invoke(l, a); } catch (Throwable e) { throw wrap(e); } }
    public static void glUniform2f(int l, float a, float b) { try { U2F.invoke(l, a, b); } catch (Throwable e) { throw wrap(e); } }
    public static void glUniform3f(int l, float a, float b, float c) { try { U3F.invoke(l, a, b, c); } catch (Throwable e) { throw wrap(e); } }
    public static void glUniform4f(int l, float a, float b, float c, float d) { try { U4F.invoke(l, a, b, c, d); } catch (Throwable e) { throw wrap(e); } }
    public static void glUseProgram(int p) { try { USE.invoke(p); } catch (Throwable e) { throw wrap(e); } }
    public static void glViewport(int x, int y, int w, int h) { try { VIEWPORT.invoke(x, y, w, h); } catch (Throwable e) { throw wrap(e); } }
    public static void glVertexAttrib4f(int i, float a, float b, float c, float d) { try { ATTRIB4F.invoke(i, a, b, c, d); } catch (Throwable e) { throw wrap(e); } }
    public static int glGetError() { try { return (int) GET_ERROR.invoke(); } catch (Throwable e) { throw wrap(e); } }

    public static void glVertexAttribPointer(int i, int size, int type, boolean norm, int stride, int offset) {
        try { ATTRIB_PTR.invoke(i, size, type, (byte) (norm ? 1 : 0), stride, MemorySegment.ofAddress(offset)); } catch (Throwable e) { throw wrap(e); }
    }

    public static void glVertexAttribPointer(int i, int size, int type, boolean norm, int stride, Buffer ptr) {
        try { ATTRIB_PTR.invoke(i, size, type, (byte) (norm ? 1 : 0), stride, ptr(ptr)); } catch (Throwable e) { throw wrap(e); }
    }

    public static void glTexImage2D(int t, int level, int internal, int w, int h, int border, int format, int type, Buffer px) {
        try { TEX_IMAGE.invoke(t, level, internal, w, h, border, format, type, ptr(px)); } catch (Throwable e) { throw wrap(e); }
    }

    public static void glGenBuffers(int n, int[] out, int off) { gen(GEN_BUFFERS, n, out, off); }
    public static void glGenTextures(int n, int[] out, int off) { gen(GEN_TEXTURES, n, out, off); }

    private static void gen(MethodHandle h, int n, int[] out, int off) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment s = a.allocateArray(JAVA_INT, n);
            h.invoke(n, s);
            for (int i = 0; i < n; i++) out[off + i] = s.getAtIndex(JAVA_INT, i);
        } catch (Throwable e) {
            throw wrap(e);
        }
    }

    public static void glDeleteBuffers(int n, int[] ids, int off) { del(DELETE_BUFFERS, n, ids, off); }
    public static void glDeleteTextures(int n, int[] ids, int off) { del(DELETE_TEXTURES, n, ids, off); }

    private static void del(MethodHandle h, int n, int[] ids, int off) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment s = a.allocateArray(JAVA_INT, n);
            for (int i = 0; i < n; i++) s.setAtIndex(JAVA_INT, i, ids[off + i]);
            h.invoke(n, s);
        } catch (Throwable e) {
            throw wrap(e);
        }
    }

    public static int glGetAttribLocation(int p, String name) { return loc(ATTRIB_LOC, p, name); }
    public static int glGetUniformLocation(int p, String name) { return loc(UNIFORM_LOC, p, name); }

    private static int loc(MethodHandle h, int p, String name) {
        try (Arena a = Arena.ofConfined()) {
            return (int) h.invoke(p, a.allocateUtf8String(name));
        } catch (Throwable e) {
            throw wrap(e);
        }
    }

    public static void glGetProgramiv(int p, int pname, int[] out, int off) { iv(PROGRAM_IV, p, pname, out, off); }
    public static void glGetShaderiv(int s, int pname, int[] out, int off) { iv(SHADER_IV, s, pname, out, off); }

    private static void iv(MethodHandle h, int obj, int pname, int[] out, int off) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment s = a.allocate(JAVA_INT);
            h.invoke(obj, pname, s);
            out[off] = s.get(JAVA_INT, 0);
        } catch (Throwable e) {
            throw wrap(e);
        }
    }

    public static String glGetProgramInfoLog(int p) { return log(PROGRAM_LOG, p); }
    public static String glGetShaderInfoLog(int s) { return log(SHADER_LOG, s); }

    private static String log(MethodHandle h, int obj) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment buf = a.allocate(4096);
            h.invoke(obj, 4096, MemorySegment.NULL, buf);
            return buf.getUtf8String(0);
        } catch (Throwable e) {
            throw wrap(e);
        }
    }

    public static void glShaderSource(int s, String src) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment str = a.allocateUtf8String(src);
            MemorySegment arr = a.allocate(ADDRESS);
            arr.set(ADDRESS, 0, str);
            SHADER_SOURCE.invoke(s, 1, arr, MemorySegment.NULL);
        } catch (Throwable e) {
            throw wrap(e);
        }
    }

    public static void glUniformMatrix4fv(int l, int count, boolean transpose, float[] v, int off) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment s = a.allocateArray(JAVA_FLOAT, 16L * count);
            for (int i = 0; i < 16 * count; i++) s.setAtIndex(JAVA_FLOAT, i, v[off + i]);
            UMAT4.invoke(l, count, (byte) (transpose ? 1 : 0), s);
        } catch (Throwable e) {
            throw wrap(e);
        }
    }

    /** Desktop extra: read back the frame (RGBA, bottom row first). */
    public static byte[] readPixels(int w, int h) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment s = a.allocate((long) w * h * 4);
            READ_PIXELS.invoke(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, s);
            return s.toArray(ValueLayout.JAVA_BYTE);
        } catch (Throwable e) {
            throw wrap(e);
        }
    }
}
