package gd.script.gdcc.backend.c.build;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.foreign.MemorySegment;

/// Narrow, replaceable view of the stateful libtcc calls of one compile round. The production
/// implementation delegates to [TinyCcLibrary]; tests drive failure paths with a pure-Java fake.
///
/// Contract for every implementation:
/// - all methods are called from the single thread that owns the round (the production FFM
///   arenas are confined to it);
/// - `state` is the token returned by [newState] and stays valid until [delete];
/// - every `int` result is a raw libtcc status where `!= 0` means failure (including values
///   beyond `-1`, e.g. `tcc_add_file`'s `FILE_NOT_FOUND=-2`/`FILE_NOT_RECOGNIZED=-3`).
interface TinyCcNative {

    /// A fresh `TCCState`; null means `tcc_new` returned NULL. A state is never reused across
    /// rounds: a failed round leaves libtcc-internal state that cannot be trusted.
    @Nullable MemorySegment newState();

    void delete(@NotNull MemorySegment state);

    /// `tcc_set_lib_path` returns void, but it must run before [setOutputType]: expanding the
    /// output type resolves the `{B}` include/library/CRT paths against this lib path.
    void setLibPath(@NotNull MemorySegment state, @NotNull String path);

    int setOptions(@NotNull MemorySegment state, @NotNull String options);

    int setOutputType(@NotNull MemorySegment state, int outputType);

    int addIncludePath(@NotNull MemorySegment state, @NotNull String path);

    int addFile(@NotNull MemorySegment state, @NotNull String file);

    int outputFile(@NotNull MemorySegment state, @NotNull String file);

    /// Registers the diagnostic callback before any call that can emit diagnostics. The handler
    /// always receives an already-copied Java String — libtcc releases the native message buffer
    /// as soon as its callback returns.
    void setErrorHandler(@NotNull MemorySegment state, @NotNull ErrorHandler handler);

    /// A failure inside the diagnostic upcall machinery itself (e.g. copying the message threw).
    /// Anything non-null aborts the round even when every native call returned success: an
    /// upcall that silently lost a diagnostic makes the round's success unverifiable.
    @Nullable Throwable callbackFailure();

    @FunctionalInterface
    interface ErrorHandler {
        void onError(@NotNull String message);
    }
}
