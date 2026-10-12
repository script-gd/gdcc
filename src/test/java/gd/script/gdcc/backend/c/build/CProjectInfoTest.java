package gd.script.gdcc.backend.c.build;

import gd.script.gdcc.enums.GodotVersion;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Pins the [CProjectInfo] backend-selection contract: the five-arg compatibility constructor
/// keeps the pre-selection behavior (zig), the canonical constructor carries the requested kind,
/// and the kind participates in value equality (two infos differing only in kind must never
/// collapse in maps/caches keyed by project info).
class CProjectInfoTest {
    @Test
    void fiveArgCompatibilityConstructorPinsTheZigBackend() {
        var debug = new CProjectInfo(
                "demo", GodotVersion.V451, Path.of("p"), COptimizationLevel.DEBUG, TargetPlatform.LINUX_X86_64);
        assertEquals(CCompilerKind.ZIG_CC, debug.getCCompilerKind());

        var release = new CProjectInfo(
                "demo", GodotVersion.V451, Path.of("p"), COptimizationLevel.RELEASE, TargetPlatform.LINUX_X86_64);
        assertEquals(CCompilerKind.ZIG_CC, release.getCCompilerKind());
    }

    @Test
    void canonicalConstructorCarriesAndRequiresTheCompilerKind() {
        var info = new CProjectInfo(
                "demo", GodotVersion.V451, Path.of("p"),
                COptimizationLevel.DEBUG, TargetPlatform.LINUX_X86_64, CCompilerKind.TINY_CC);
        assertEquals(CCompilerKind.TINY_CC, info.getCCompilerKind());

        // Dispatch reaches for the kind on every round; a null would surface as an opaque NPE at
        // compile time, so the constructor rejects it at the boundary instead.
        var exception = assertThrows(NullPointerException.class, () -> new CProjectInfo(
                "demo", GodotVersion.V451, Path.of("p"),
                COptimizationLevel.DEBUG, TargetPlatform.LINUX_X86_64, null));
        assertEquals("cCompilerKind must not be null", exception.getMessage());
    }

    @Test
    void compilerKindParticipatesInEquality() {
        var zig = new CProjectInfo(
                "demo", GodotVersion.V451, Path.of("p"),
                COptimizationLevel.DEBUG, TargetPlatform.LINUX_X86_64, CCompilerKind.ZIG_CC);
        var zigAgain = new CProjectInfo(
                "demo", GodotVersion.V451, Path.of("p"),
                COptimizationLevel.DEBUG, TargetPlatform.LINUX_X86_64, CCompilerKind.ZIG_CC);
        var tcc = new CProjectInfo(
                "demo", GodotVersion.V451, Path.of("p"),
                COptimizationLevel.DEBUG, TargetPlatform.LINUX_X86_64, CCompilerKind.TINY_CC);

        assertEquals(zig, zigAgain);
        assertEquals(zig.hashCode(), zigAgain.hashCode());
        assertNotEquals(zig, tcc);
        // The five-arg compatibility shape equals the canonical shape pinned to zig.
        assertEquals(
                new CProjectInfo("demo", GodotVersion.V451, Path.of("p"),
                        COptimizationLevel.DEBUG, TargetPlatform.LINUX_X86_64),
                zig
        );
    }
}
