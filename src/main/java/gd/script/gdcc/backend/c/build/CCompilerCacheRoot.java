package gd.script.gdcc.backend.c.build;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Map;

/// Cache-root resolution shared by the C compiler caches (zig PCH cache, tcc object cache):
/// `GDCC_SHARED_C_COMPILER_CACHE` when set and creatable, else the project parent's
/// `shared-compiler-cache/` when it already exists as a directory, else the project's own
/// `compiler-cache/`. Extracted from [ZigCcCompiler] verbatim; the decision table is anchored
/// by `CCompilerCacheRootTest`.
///
/// Only the environment-hit branch creates directories; the shared/project fallbacks are pure
/// observations so read-only project trees never fail a build here, and any resolution problem
/// degrades to the project-local cache instead of throwing.
final class CCompilerCacheRoot {

    static final String PROJECT_CACHE_DIR_NAME = "compiler-cache";
    static final String SHARED_CACHE_DIR_NAME = "shared-compiler-cache";
    static final String SHARED_CACHE_ENV = "GDCC_SHARED_C_COMPILER_CACHE";

    private CCompilerCacheRoot() {
    }

    static @NotNull Path resolve(@NotNull Path projectDir) {
        return resolve(projectDir, System.getenv());
    }

    static @NotNull Path resolve(@NotNull Path projectDir, @NotNull Map<String, String> environment) {
        var normalizedProjectDir = projectDir.toAbsolutePath().normalize();
        var envCacheValue = environment.get(SHARED_CACHE_ENV);
        if (envCacheValue != null && !envCacheValue.isBlank()) {
            try {
                var envCacheDir = Path.of(envCacheValue).toAbsolutePath().normalize();
                Files.createDirectories(envCacheDir);
                if (Files.isDirectory(envCacheDir)) {
                    return envCacheDir;
                }
            } catch (IOException | InvalidPathException exception) {
                // Fall back to the project-location cache rule below.
            }
        }

        var projectParent = normalizedProjectDir.getParent();
        if (projectParent == null) {
            return normalizedProjectDir.resolve(PROJECT_CACHE_DIR_NAME);
        }

        var sharedCacheDir = projectParent.resolve(SHARED_CACHE_DIR_NAME);
        if (Files.exists(sharedCacheDir) && !Files.isDirectory(sharedCacheDir)) {
            return normalizedProjectDir.resolve(PROJECT_CACHE_DIR_NAME);
        }
        if (Files.isDirectory(sharedCacheDir)) {
            return sharedCacheDir.toAbsolutePath().normalize();
        }

        return normalizedProjectDir.resolve(PROJECT_CACHE_DIR_NAME);
    }
}
