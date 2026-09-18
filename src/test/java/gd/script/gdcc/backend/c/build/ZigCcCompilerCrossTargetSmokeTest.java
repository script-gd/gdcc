package gd.script.gdcc.backend.c.build;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Cross-target smoke for the release link: for every target the current zig toolchain can
/// actually build — host, linux cross (zig's bundled libc), and windows cross — a release
/// build must link and produce the artifact. Linux targets exercise `-flto=thin`; windows
/// targets exercise ThinLTO on the MSVC ABI when running on a Windows host, and the
/// substituted MinGW build without LTO everywhere else (the msvc→gnu rule never enters the
/// ThinLTO fallback decision). macOS needs a Darwin sysroot to link from a non-macOS host,
/// so it is not in this matrix; native macOS CI covers host dylib builds.
///
/// Known limitations (deliberately not smoke-tested here, unrelated to LTO): web-wasm32 fails
/// by design — minicoro locks `MCO_USE_ASM` and zig ships no Emscripten sysroot — and android
/// linking needs the NDK/Bionic. The Emscripten backend is a separate post-MVP project;
/// `webAndAndroidRealBuildsRemainKnownLimitations` is the explicit skip record.
///
/// CONCURRENT with one parameterized invocation per target: the per-target builds are
/// independent probe projects, so they pack into the shared fork-join pool instead of
/// serializing five cold cross-target builds inside one test.
@Execution(ExecutionMode.CONCURRENT)
public class ZigCcCompilerCrossTargetSmokeTest {
    /// Targets the current toolchain can build end to end: host + linux cross + windows cross.
    private static final List<TargetPlatform> BUILDABLE_TARGETS = List.of(
            TargetPlatform.LINUX_X86_64,
            TargetPlatform.LINUX_AARCH64,
            TargetPlatform.LINUX_RISCV64,
            TargetPlatform.WINDOWS_X86_64,
            TargetPlatform.WINDOWS_AARCH64);

    private static @NotNull Stream<TargetPlatform> buildableTargets() {
        return BUILDABLE_TARGETS.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("buildableTargets")
    public void releaseBuildLinksForBuildableCrossTarget(TargetPlatform target, @TempDir Path tempDir) throws IOException {
        if (ZigUtil.findZig() == null) {
            Assumptions.abort("Zig not found; skipping cross-target link smoke test");
            return;
        }
        var projectDir = Files.createDirectories(tempDir.resolve("project"));
        var result = compileProbe(projectDir, target);
        assertTrue(result.success(), () -> target + " release link smoke failed:\n" + result.buildLog());
        assertTrue(Files.isRegularFile(result.artifacts().getFirst()),
                () -> target + " must produce the shared library artifact");
        assertEquals(target.sharedLibraryFileName("probe"), result.artifacts().getFirst().getFileName().toString());
    }

    @Test
    public void androidAndWebTargetsKeepDefaultThinLtoDecision() {
        // Pure command-level anchor: android/web build failures are sysroot/runtime limitations,
        // so the LTO decision must not special-case them into the full-LTO fallback.
        for (var zigTarget : List.of("x86_64-linux-android", "aarch64-linux-android", "wasm32-emscripten")) {
            assertEquals(CLtoMode.THIN, ZigCcCompiler.resolveLtoMode(zigTarget, false, COptimizationLevel.RELEASE), zigTarget);
            assertEquals(CLtoMode.NONE, ZigCcCompiler.resolveLtoMode(zigTarget, false, COptimizationLevel.DEBUG), zigTarget);
        }
    }

    @Test
    @Disabled("Known environment limitations: web-wasm32 needs the Emscripten coroutine backend "
            + "(minicoro locks MCO_USE_ASM, zig ships no Emscripten sysroot) and android linking "
            + "needs the NDK/Bionic — post-MVP toolchain work, not LTO issues.")
    public void webAndAndroidRealBuildsRemainKnownLimitations(@TempDir Path tempDir) throws IOException {
        // Enabled once the Emscripten backend / android toolchain lands: the probe must then
        // compile and link exactly like any other buildable target above.
        for (var target : List.of(TargetPlatform.WEB_WASM32, TargetPlatform.ANDROID_X86_64, TargetPlatform.ANDROID_AARCH64)) {
            var projectDir = Files.createDirectories(tempDir.resolve(target.name().toLowerCase(Locale.ROOT)));
            var result = compileProbe(projectDir, target);
            assertTrue(result.success(), () -> target + " release link failed:\n" + result.buildLog());
            assertTrue(Files.isRegularFile(result.artifacts().getFirst()));
        }
    }

    /// A minimal self-contained TU keeps the smoke fast and independent of the Godot include
    /// tree while still exercising the full per-TU compile + link pipeline per target.
    private static @NotNull CCompileResult compileProbe(@NotNull Path projectDir, @NotNull TargetPlatform target) throws IOException {
        var probe = projectDir.resolve("probe.c");
        Files.writeString(probe, """
                __attribute__((visibility("default"))) int gdextension_entry(void) { return 42; }
                """);
        return new ZigCcCompiler().compile(projectDir, List.of(), List.of(probe), "probe", COptimizationLevel.RELEASE, target);
    }
}
