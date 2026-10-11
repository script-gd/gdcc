package gd.script.gdcc.backend.c.build;

import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/// In-process serialization of native build rounds per project directory, shared by every
/// [CCompiler] implementation. Concurrent rounds on one directory would overwrite each other's
/// intermediate objects and the final artifact regardless of which compiler drives them, so the
/// lock must be compiler-neutral: a zig round and a tinycc round on the same `projectDir` are
/// mutually exclusive. Keys are normalized absolute paths, so textual variants of one directory
/// share a single lock. External gdcc instances are out of scope: cross-process coordination on
/// a shared directory is the caller's own responsibility. Entries are kept for the process
/// lifetime — one small lock per project directory ever built.
final class CProjectBuildLock {
    private static final ConcurrentHashMap<Path, ReentrantLock> LOCKS = new ConcurrentHashMap<>();

    private CProjectBuildLock() {
    }

    /// The lock serializing native build rounds of one project directory within this process.
    static @NotNull ReentrantLock require(@NotNull Path projectDir) {
        return LOCKS.computeIfAbsent(projectDir.toAbsolutePath().normalize(), key -> new ReentrantLock());
    }
}
