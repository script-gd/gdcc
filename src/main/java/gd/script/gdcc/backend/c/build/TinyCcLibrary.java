package gd.script.gdcc.backend.c.build;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/// Process-lifetime FFM binding of libtcc (vendored tinycc, see `src/main/c/tinycc/`). Loads the
/// bundle's `bin/libtcc` once per process through [SymbolLookup] held by a shared
/// arena that is deliberately never closed — a loaded native library cannot be safely unloaded
/// while upcall stubs or states might still reference it, so the mapping lives for the rest of
/// the process. A loading attempt that fails halfway closes its temporary arena instead, and the
/// failure is never cached: the next [require] call retries from scratch.
///
/// Bound API surface (signatures per `src/main/c/tinycc/libtcc.h`; C pointers map to
/// [ValueLayout#ADDRESS], C `int` to [ValueLayout#JAVA_INT]): `tcc_new`, `tcc_delete`,
/// `tcc_set_lib_path`, `tcc_set_error_func`, `tcc_set_options`, `tcc_set_output_type`,
/// `tcc_add_include_path`, `tcc_add_file`, `tcc_compile_string`, `tcc_output_file`.
///
/// Threading: the lookup/linker handles are thread-safe, but the states and confined arenas they
/// serve are not — every caller-visible method must run on the round-owning thread, which
/// [TinyCcCompiler] guarantees by holding the global tcc lock.
final class TinyCcLibrary {

    /// `TCC_OUTPUT_DLL` from `libtcc.h`: compile and link straight to a shared library.
    static final int TCC_OUTPUT_DLL = 4;

    private static final FunctionDescriptor DESC_NEW = FunctionDescriptor.of(ValueLayout.ADDRESS);
    private static final FunctionDescriptor DESC_DELETE = FunctionDescriptor.ofVoid(ValueLayout.ADDRESS);
    private static final FunctionDescriptor DESC_SET_LIB_PATH = FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS);
    private static final FunctionDescriptor DESC_SET_ERROR_FUNC = FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS);
    /// Shared by `tcc_set_options`, `tcc_add_include_path`, `tcc_add_file`, `tcc_compile_string`
    /// and `tcc_output_file`, all `int (TCCState*, const char*)`.
    private static final FunctionDescriptor DESC_INT_STRING = FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS);
    private static final FunctionDescriptor DESC_SET_OUTPUT_TYPE = FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT);
    /// `TCCErrorFunc`: `void (*)(void *opaque, const char *msg)`.
    private static final FunctionDescriptor DESC_ERROR_FUNC = FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS);
    private static final MethodHandle ERROR_FUNC_HANDLE;

    static {
        try {
            ERROR_FUNC_HANDLE = MethodHandles.lookup().findVirtual(ErrorFuncTrampoline.class, "accept",
                    MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class));
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /// Published only after every symbol bound successfully; a failure leaves this null so the
    /// next [require] retries (mirroring the [ZigUtil] probe-cache semantics).
    private static volatile @Nullable TinyCcLibrary INSTANCE;

    private final Arena arena;
    private final Linker linker;
    private final MethodHandle tccNew;
    private final MethodHandle tccDelete;
    private final MethodHandle tccSetLibPath;
    private final MethodHandle tccSetErrorFunc;
    private final MethodHandle tccSetOptions;
    private final MethodHandle tccSetOutputType;
    private final MethodHandle tccAddIncludePath;
    private final MethodHandle tccAddFile;
    private final MethodHandle tccCompileString;
    private final MethodHandle tccOutputFile;

    private TinyCcLibrary(@NotNull Arena arena, @NotNull Linker linker, @NotNull SymbolLookup lookup, @NotNull Path libraryPath) throws IOException {
        this.arena = arena;
        this.linker = linker;
        this.tccNew = bind(linker, lookup, "tcc_new", DESC_NEW, libraryPath);
        this.tccDelete = bind(linker, lookup, "tcc_delete", DESC_DELETE, libraryPath);
        this.tccSetLibPath = bind(linker, lookup, "tcc_set_lib_path", DESC_SET_LIB_PATH, libraryPath);
        this.tccSetErrorFunc = bind(linker, lookup, "tcc_set_error_func", DESC_SET_ERROR_FUNC, libraryPath);
        this.tccSetOptions = bind(linker, lookup, "tcc_set_options", DESC_INT_STRING, libraryPath);
        this.tccSetOutputType = bind(linker, lookup, "tcc_set_output_type", DESC_SET_OUTPUT_TYPE, libraryPath);
        this.tccAddIncludePath = bind(linker, lookup, "tcc_add_include_path", DESC_INT_STRING, libraryPath);
        this.tccAddFile = bind(linker, lookup, "tcc_add_file", DESC_INT_STRING, libraryPath);
        this.tccCompileString = bind(linker, lookup, "tcc_compile_string", DESC_INT_STRING, libraryPath);
        this.tccOutputFile = bind(linker, lookup, "tcc_output_file", DESC_INT_STRING, libraryPath);
    }

    /// The process-wide binding, loading `libraryPath` on first use. Once loaded, the instance
    /// is fixed for the rest of the process even if a later call resolves a different path — a
    /// bundle directory that fed a load is treated as an immutable, versioned artifact.
    static @NotNull TinyCcLibrary require(@NotNull Path libraryPath) throws IOException {
        var cached = INSTANCE;
        if (cached != null) {
            return cached;
        }
        synchronized (TinyCcLibrary.class) {
            if (INSTANCE == null) {
                INSTANCE = load(libraryPath);
            }
            return Objects.requireNonNull(INSTANCE);
        }
    }

    /// One uncached loading attempt. Any failure releases every native resource the attempt
    /// acquired (closing the arena unloads a partially bound library) and escapes as an
    /// [IOException]; nothing is published.
    private static @NotNull TinyCcLibrary load(@NotNull Path libraryPath) throws IOException {
        var arena = Arena.ofShared();
        try {
            return new TinyCcLibrary(arena, Linker.nativeLinker(), SymbolLookup.libraryLookup(libraryPath, arena), libraryPath);
        } catch (Throwable t) {
            try {
                arena.close();
            } catch (Throwable closeFailure) {
                t.addSuppressed(closeFailure);
            }
            if (t instanceof Error error) {
                throw error;
            }
            if (t instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("Failed to load libtcc from " + libraryPath + ": " + t.getMessage(), t);
        }
    }

    private static @NotNull MethodHandle bind(@NotNull Linker linker, @NotNull SymbolLookup lookup, @NotNull String symbol, @NotNull FunctionDescriptor descriptor, @NotNull Path libraryPath) throws IOException {
        var address = lookup.find(symbol);
        if (address.isEmpty()) {
            throw new IOException("libtcc at " + libraryPath + " does not export " + symbol);
        }
        return linker.downcallHandle(address.get(), descriptor);
    }

    /// Test-only hook: drops the cached instance so loading behavior can be exercised repeatedly.
    /// An already-loaded library stays mapped in its own arena — it is never unloaded.
    static void clearCachedLibraryForTesting() {
        synchronized (TinyCcLibrary.class) {
            INSTANCE = null;
        }
    }

    /// NULL means libtcc could not allocate the state.
    @Nullable MemorySegment newState() {
        var state = call(() -> (MemorySegment) tccNew.invokeExact());
        return MemorySegment.NULL.equals(state) ? null : state;
    }

    void delete(@NotNull MemorySegment state) {
        // A signature-polymorphic invokeExact only gets the void static type in statement
        // position; an expression lambda body would bind it as Object and fail exactness.
        callVoid(() -> tccDelete.invokeExact(state));
    }

    void setLibPath(@NotNull MemorySegment state, @NotNull MemorySegment path) {
        callVoid(() -> tccSetLibPath.invokeExact(state, path));
    }

    /// `opaque` is always NULL: the handler instance is bound into the upcall stub itself.
    void setErrorFunc(@NotNull MemorySegment state, @NotNull MemorySegment errorFuncStub) {
        callVoid(() -> tccSetErrorFunc.invokeExact(state, MemorySegment.NULL, errorFuncStub));
    }

    int setOptions(@NotNull MemorySegment state, @NotNull MemorySegment options) {
        return call(() -> (int) tccSetOptions.invokeExact(state, options));
    }

    int setOutputType(@NotNull MemorySegment state, int outputType) {
        return call(() -> (int) tccSetOutputType.invokeExact(state, outputType));
    }

    int addIncludePath(@NotNull MemorySegment state, @NotNull MemorySegment path) {
        return call(() -> (int) tccAddIncludePath.invokeExact(state, path));
    }

    int addFile(@NotNull MemorySegment state, @NotNull MemorySegment file) {
        return call(() -> (int) tccAddFile.invokeExact(state, file));
    }

    /// Not used by the compiler rounds (they compile files); bound for API completeness and to
    /// let tests drive the diagnostic callback without any fixture files.
    int compileString(@NotNull MemorySegment state, @NotNull MemorySegment source) {
        return call(() -> (int) tccCompileString.invokeExact(state, source));
    }

    int outputFile(@NotNull MemorySegment state, @NotNull MemorySegment file) {
        return call(() -> (int) tccOutputFile.invokeExact(state, file));
    }

    /// A fresh diagnostic upcall stub living in `arena`. The stub address dies with that arena,
    /// so it must never be reused across rounds.
    @NotNull MemorySegment newErrorFuncStub(@NotNull Arena arena, @NotNull TinyCcNative.ErrorHandler handler, @NotNull AtomicReference<Throwable> failureSink) {
        return linker.upcallStub(ERROR_FUNC_HANDLE.bindTo(new ErrorFuncTrampoline(handler, failureSink)), DESC_ERROR_FUNC, arena);
    }

    /// `invokeExact` declares [Throwable]; for these fixed signatures only linkage or
    /// exactness mismatches are reachable, and those are programming errors in the binding
    /// itself — never a compile-round failure.
    private static <T> T call(@NotNull Downcall<T> downcall) {
        try {
            return downcall.invoke();
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("libtcc downcall binding mismatch", t);
        }
    }

    private static void callVoid(@NotNull VoidDowncall downcall) {
        try {
            downcall.invoke();
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("libtcc downcall binding mismatch", t);
        }
    }

    @FunctionalInterface
    private interface Downcall<T> {
        T invoke() throws Throwable;
    }

    @FunctionalInterface
    private interface VoidDowncall {
        void invoke() throws Throwable;
    }

    /// Bodies the libtcc error callback. The native message buffer is released by libtcc as soon
    /// as the callback returns, so the message is copied into a Java String before dispatching;
    /// any failure — including one thrown by the handler — is recorded and swallowed because an
    /// exception must never escape through a native frame.
    private static final class ErrorFuncTrampoline {
        private final TinyCcNative.ErrorHandler handler;
        private final AtomicReference<Throwable> failureSink;

        ErrorFuncTrampoline(@NotNull TinyCcNative.ErrorHandler handler, @NotNull AtomicReference<Throwable> failureSink) {
            this.handler = handler;
            this.failureSink = failureSink;
        }

        @SuppressWarnings("unused") // invoked from native code through the upcall stub
        void accept(MemorySegment opaque, MemorySegment msg) {
            try {
                // Upcall ADDRESS parameters arrive as zero-length segments, so the C string can
                // only be read after reinterpret resizing; the copy must complete before the
                // callback returns because libtcc releases the native buffer afterwards.
                handler.onError(msg.reinterpret(Long.MAX_VALUE).getString(0));
            } catch (Throwable t) {
                failureSink.compareAndSet(null, t);
            }
        }
    }
}
