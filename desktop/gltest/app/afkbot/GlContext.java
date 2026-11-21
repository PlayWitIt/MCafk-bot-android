package app.afkbot;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/** A headless OpenGL ES 2 context (Mesa's surfaceless EGL platform + a pbuffer) for running GameRenderer on a PC. */
final class GlContext {
    private static final Linker L = Linker.nativeLinker();
    private static final SymbolLookup EGL = SymbolLookup.libraryLookup("libEGL.so.1", Arena.global());

    private static MethodHandle h(String n, FunctionDescriptor fd) {
        return L.downcallHandle(EGL.find(n).orElseThrow(), fd);
    }

    static void create(int w, int hgt) throws Throwable {
        Arena a = Arena.global();
        MemorySegment d = (MemorySegment) h("eglGetPlatformDisplay", FunctionDescriptor.of(ADDRESS, JAVA_INT, ADDRESS, ADDRESS))
                .invoke(0x31DD, MemorySegment.NULL, MemorySegment.NULL); // EGL_PLATFORM_SURFACELESS_MESA
        int ok = (int) h("eglInitialize", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS)).invoke(d, MemorySegment.NULL, MemorySegment.NULL);
        if (ok == 0) throw new IllegalStateException("eglInitialize failed");
        MemorySegment attrs = a.allocateArray(JAVA_INT, 0x3033, 0x0001, 0x3040, 0x0004, 0x3024, 8, 0x3023, 8, 0x3022, 8, 0x3021, 8, 0x3025, 24, 0x3038);
        MemorySegment cfg = a.allocate(ADDRESS), n = a.allocate(JAVA_INT);
        h("eglChooseConfig", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS)).invoke(d, attrs, cfg, 1, n);
        if (n.get(JAVA_INT, 0) < 1) throw new IllegalStateException("no EGL config");
        MemorySegment config = cfg.get(ADDRESS, 0);
        h("eglBindAPI", FunctionDescriptor.of(JAVA_INT, JAVA_INT)).invoke(0x30A0); // EGL_OPENGL_ES_API
        MemorySegment ctx = (MemorySegment) h("eglCreateContext", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS))
                .invoke(d, config, MemorySegment.NULL, a.allocateArray(JAVA_INT, 0x3098, 2, 0x3038)); // ES 2
        MemorySegment surf = (MemorySegment) h("eglCreatePbufferSurface", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS))
                .invoke(d, config, a.allocateArray(JAVA_INT, 0x3057, w, 0x3056, hgt, 0x3038));
        ok = (int) h("eglMakeCurrent", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS)).invoke(d, surf, surf, ctx);
        if (ok == 0) throw new IllegalStateException("eglMakeCurrent failed");
    }
}
