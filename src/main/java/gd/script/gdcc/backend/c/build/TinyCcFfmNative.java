package gd.script.gdcc.backend.c.build;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/// Production [TinyCcNative]: delegates to the process-lifetime [TinyCcLibrary] binding and
/// marshals every string through the round's confined arena (`allocateFrom` produces a
/// null-terminated UTF-8 native string), so all native memory of a round is released in one shot
/// when the round arena closes after `tcc_delete`.
final class TinyCcFfmNative implements TinyCcNative {
    private final TinyCcLibrary library;
    private final Arena arena;
    private final AtomicReference<Throwable> callbackFailure = new AtomicReference<>();

    private TinyCcFfmNative(@NotNull TinyCcLibrary library, @NotNull Arena arena) {
        this.library = library;
        this.arena = arena;
    }

    /// Round-scoped factory: locates the bundle's libtcc for `targetPlatform`, loads it once per
    /// process and ties all marshaling of this round to `roundArena`.
    static @NotNull TinyCcNative create(@NotNull Path bundleRoot, @NotNull TargetPlatform targetPlatform, @NotNull Arena roundArena) throws IOException {
        return new TinyCcFfmNative(TinyCcLibrary.require(TinyCcBundle.libraryPath(bundleRoot, targetPlatform)), roundArena);
    }

    @Override
    public @Nullable MemorySegment newState() {
        return library.newState();
    }

    @Override
    public void delete(@NotNull MemorySegment state) {
        library.delete(state);
    }

    @Override
    public void setLibPath(@NotNull MemorySegment state, @NotNull String path) {
        library.setLibPath(state, arena.allocateFrom(path));
    }

    @Override
    public int setOptions(@NotNull MemorySegment state, @NotNull String options) {
        return library.setOptions(state, arena.allocateFrom(options));
    }

    @Override
    public int setOutputType(@NotNull MemorySegment state, int outputType) {
        return library.setOutputType(state, outputType);
    }

    @Override
    public int addIncludePath(@NotNull MemorySegment state, @NotNull String path) {
        return library.addIncludePath(state, arena.allocateFrom(path));
    }

    @Override
    public int addFile(@NotNull MemorySegment state, @NotNull String file) {
        return library.addFile(state, arena.allocateFrom(file));
    }

    @Override
    public int outputFile(@NotNull MemorySegment state, @NotNull String file) {
        return library.outputFile(state, arena.allocateFrom(file));
    }

    @Override
    public void setErrorHandler(@NotNull MemorySegment state, @NotNull ErrorHandler handler) {
        library.setErrorFunc(state, library.newErrorFuncStub(arena, handler, callbackFailure));
    }

    @Override
    public @Nullable Throwable callbackFailure() {
        return callbackFailure.get();
    }
}
