package gd.script.gdcc.backend.c.build;

import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/// Shared discovery for the vendored tinycc CLI in tests. The bundle ships only `libtcc` for
/// in-process use, so the CLI comes from the `build-script/build-tinycc-cli-<platform>` work tree
/// (`build/tinycc/<platform>/`); the Gradle `buildTinyccCli` task (gradle/tinycc.gradle.kts) builds
/// it before `test` starts whenever it is missing or stale — tests never spawn a build themselves.
/// A work tree is trusted only when the script's `cli-verified` marker is present: the scripts
/// write it last, after their `u8"..."` patch probe passes, so a killed or half-finished build is
/// never picked up. No system/PATH compiler is consulted (an unpatched tcc would fail the u8
/// contract). Overrides: `GDCC_TINYCC_TCC` points at a specific binary; `GDCC_TINYCC_HOME`
/// supplies the `-B` runtime root. All consumers stay assumption-gated: no tcc, no test.
///
/// Public so the test-suite package (`gd.script.gdcc.test_suite`) can drive tcc-backed runs.
public final class TinyCcCliTestSupport {
    private TinyCcCliTestSupport() {
    }

    /// A [CCompiler] bound to the discovered bundle, or null when no usable bundle exists.
    /// Callers gate on the null result; the compiler uses the in-process FFM backend exactly as
    /// production does, with the bundle root pinned to this machine's test discovery.
    public static @Nullable CCompiler newBundleCompiler() {
        var bundleRoot = findTinyCcBundleRoot();
        return bundleRoot == null ? null : new TinyCcCompiler(() -> bundleRoot, TinyCcFfmNative::create);
    }

    /// Locates the patched tcc CLI binary: explicit env override, then the platform work tree
    /// (binary plus `cli-verified` marker). Returns null when no patched CLI is available.
    static @Nullable Path findTinyCcCli() {
        var envOverride = System.getenv("GDCC_TINYCC_TCC");
        if (envOverride != null && !envOverride.isBlank()) {
            var override = Path.of(envOverride);
            return Files.isExecutable(override) ? override : null;
        }
        var platformKey = nativePlatformKey();
        if (platformKey == null) {
            return null;
        }
        var workTree = Path.of("build/tinycc", platformKey).toAbsolutePath().normalize();
        var binary = workTree.resolve(isWindowsHost() ? "tcc.exe" : "tcc");
        return Files.isExecutable(binary) && Files.isRegularFile(workTree.resolve("cli-verified")) ? binary : null;
    }

    /// The `-B` runtime root for the CLI, or null to keep the compiler's built-in search paths.
    /// Order: `GDCC_TINYCC_HOME` override, the built bundle (doubles as a bundle layout
    /// validation), then the binary's own directory when it carries a usable runtime layout.
    static @Nullable Path resolveRuntimeRoot(Path tccBinary) {
        var homeOverride = System.getenv("GDCC_TINYCC_HOME");
        if (homeOverride != null && !homeOverride.isBlank()) {
            var root = Path.of(homeOverride);
            return isUsableRuntimeRoot(root) ? root : null;
        }
        var platformKey = nativePlatformKey();
        if (platformKey != null) {
            var bundleRoot = Path.of("build/tinycc-bundle", platformKey).toAbsolutePath().normalize();
            if (isUsableRuntimeRoot(bundleRoot)) {
                return bundleRoot;
            }
        }
        var parent = tccBinary.toAbsolutePath().normalize().getParent();
        return parent != null && isUsableRuntimeRoot(parent) ? parent : null;
    }

    /// Locates the tinycc bundle for the in-process (FFM) tests: `GDCC_TINYCC_HOME` override,
    /// then the built bundle `build/tinycc-bundle/<platformKey>/`. Unlike [resolveRuntimeRoot]
    /// the root must additionally carry the libtcc shared library under `bin/` — that is the
    /// object the FFM binding loads. Returns null when no such bundle is available.
    public static @Nullable Path findTinyCcBundleRoot() {
        var homeOverride = System.getenv("GDCC_TINYCC_HOME");
        if (homeOverride != null && !homeOverride.isBlank()) {
            var root = Path.of(homeOverride);
            return isUsableBundleRoot(root) ? root : null;
        }
        var platformKey = nativePlatformKey();
        if (platformKey == null) {
            return null;
        }
        var bundleRoot = Path.of("build/tinycc-bundle", platformKey).toAbsolutePath().normalize();
        return isUsableBundleRoot(bundleRoot) ? bundleRoot : null;
    }

    /// A bundle usable by the FFM tests: the runtime layout plus the loadable libtcc binary.
    private static boolean isUsableBundleRoot(Path root) {
        var libraryName = isWindowsHost() ? "libtcc.dll" : isMacOSHost() ? "libtcc.dylib" : "libtcc.so";
        return isUsableRuntimeRoot(root)
                && Files.isRegularFile(root.resolve("bin").resolve(libraryName));
    }

    /// A tcc runtime root must carry its own headers and the runtime archive — at the root on
    /// Linux (`{B}` expands for libraries), under `lib/` on Windows (`{B}/lib`).
    private static boolean isUsableRuntimeRoot(Path root) {
        var archive = isWindowsHost() ? root.resolve("lib/libtcc1.a") : root.resolve("libtcc1.a");
        return Files.isRegularFile(root.resolve("include/tccdefs.h")) && Files.isRegularFile(archive);
    }

    /// The bundle/work-tree key segment for the host, or null when no tinycc CLI exists for it.
    private static @Nullable String nativePlatformKey() {
        return switch (TargetPlatform.getNativePlatform()) {
            case LINUX_X86_64 -> "linux-x86_64";
            case WINDOWS_X86_64 -> "windows-x86_64";
            case LINUX_AARCH64 -> "linux-aarch64";
            case MACOS_AARCH64 -> "macos-aarch64";
            default -> null;
        };
    }

    private static boolean isWindowsHost() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static boolean isMacOSHost() {
        var osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return osName.contains("mac") || osName.contains("darwin");
    }

    static @Nullable Path findOnPath(String name) {
        var pathEnv = System.getenv("PATH");
        if (pathEnv == null) {
            return null;
        }
        for (var dir : pathEnv.split(java.io.File.pathSeparator)) {
            var candidate = Path.of(dir).resolve(name);
            if (Files.isExecutable(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /// GNU objdump is the only disassembler whose output format the parser accepts.
    static @Nullable Path findObjdump() {
        var candidate = findOnPath("objdump");
        if (candidate == null) {
            return null;
        }
        try {
            var process = new ProcessBuilder(candidate.toString(), "--version").redirectErrorStream(true).start();
            var output = new String(process.getInputStream().readAllBytes());
            return process.waitFor() == 0 && output.contains("GNU") ? candidate : null;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }
}
