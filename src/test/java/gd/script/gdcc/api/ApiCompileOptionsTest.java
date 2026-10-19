package gd.script.gdcc.api;

import gd.script.gdcc.backend.c.build.CCompilerKind;
import gd.script.gdcc.backend.c.build.COptimizationLevel;
import gd.script.gdcc.backend.c.build.TargetPlatform;
import gd.script.gdcc.enums.GodotVersion;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiCompileOptionsTest {
    @Test
    void setCompileOptionsReplacesModuleSnapshotAndDoesNotLeakAcrossRecreate() {
        var api = new API();
        api.createModule("demo", "Demo");

        var updatedOptions = new CompileOptions(
                GodotVersion.V451,
                Path.of("tmp", "demo-build"),
                COptimizationLevel.RELEASE,
                TargetPlatform.WEB_WASM32,
                true,
                " /artifacts "
        );

        var returned = api.setCompileOptions("demo", updatedOptions);

        assertEquals(updatedOptions, returned);
        assertEquals(Path.of("tmp", "demo-build"), returned.projectPath());
        assertEquals(COptimizationLevel.RELEASE, returned.optimizationLevel());
        assertEquals(TargetPlatform.WEB_WASM32, returned.targetPlatform());
        assertEquals("/artifacts", returned.outputMountRoot());
        assertEquals(returned, api.getCompileOptions("demo"));
        assertEquals(returned, api.getModule("demo").compileOptions());

        api.deleteModule("demo");
        var recreated = api.createModule("demo", "Demo Recreated");

        assertEquals(CompileOptions.defaults(), recreated.compileOptions());
        assertEquals(CompileOptions.defaults(), api.getCompileOptions("demo"));
    }

    @Test
    @SuppressWarnings("DataFlowIssue")
    void compileOptionsBoundaryValidationAndApiSetterFailuresStayExplicit() {
        var nullGodotVersion = assertThrows(
                NullPointerException.class,
                () -> new CompileOptions(null, null, COptimizationLevel.DEBUG, TargetPlatform.WEB_WASM32, false, "/build")
        );
        assertEquals("godotVersion must not be null", nullGodotVersion.getMessage());

        var blankProjectPath = assertThrows(
                IllegalArgumentException.class,
                () -> new CompileOptions(
                        GodotVersion.V451,
                        Path.of(""),
                        COptimizationLevel.DEBUG,
                        TargetPlatform.WEB_WASM32,
                        false,
                        "/build"
                )
        );
        assertEquals("projectPath must not be blank", blankProjectPath.getMessage());

        var nullOptimization = assertThrows(
                NullPointerException.class,
                () -> new CompileOptions(GodotVersion.V451, null, null, TargetPlatform.WEB_WASM32, false, "/build")
        );
        assertEquals("optimizationLevel must not be null", nullOptimization.getMessage());

        var nullTargetPlatform = assertThrows(
                NullPointerException.class,
                () -> new CompileOptions(GodotVersion.V451, null, COptimizationLevel.DEBUG, null, false, "/build")
        );
        assertEquals("targetPlatform must not be null", nullTargetPlatform.getMessage());

        var invalidMountRoot = assertThrows(
                IllegalArgumentException.class,
                () -> new CompileOptions(
                        GodotVersion.V451,
                        null,
                        COptimizationLevel.DEBUG,
                        TargetPlatform.WEB_WASM32,
                        false,
                        "build/output"
                )
        );
        assertEquals("outputMountRoot path must start with '/': 'build/output'", invalidMountRoot.getMessage());

        var blankMountRoot = assertThrows(
                IllegalArgumentException.class,
                () -> new CompileOptions(
                        GodotVersion.V451,
                        null,
                        COptimizationLevel.DEBUG,
                        TargetPlatform.WEB_WASM32,
                        false,
                        "   "
                )
        );
        assertEquals("outputMountRoot must not be blank", blankMountRoot.getMessage());

        var api = new API();
        api.createModule("demo", "Demo");

        var nullCompileOptions = assertThrows(
                NullPointerException.class,
                () -> api.setCompileOptions("demo", null)
        );
        assertEquals("compileOptions must not be null", nullCompileOptions.getMessage());
    }

    @Test
    void compilerKindIsAutoByDefaultAndResolvesByOptimizationLevel() {
        // The compatibility constructor (and the canonical one given an explicit null) leaves the
        // backend undecided; the level-based default resolves debug to tinycc and release to zig.
        var debugAuto = new CompileOptions(
                GodotVersion.V451, null, COptimizationLevel.DEBUG, TargetPlatform.LINUX_X86_64, false, "/build");
        assertNull(debugAuto.cCompilerKind());
        assertEquals(CCompilerKind.TINY_CC, debugAuto.effectiveCCompilerKind());

        var releaseAuto = new CompileOptions(
                GodotVersion.V451, null, COptimizationLevel.RELEASE, TargetPlatform.LINUX_X86_64, false, "/build");
        assertNull(releaseAuto.cCompilerKind());
        assertEquals(CCompilerKind.ZIG_CC, releaseAuto.effectiveCCompilerKind());

        var explicitNull = new CompileOptions(
                GodotVersion.V451, null, COptimizationLevel.RELEASE, TargetPlatform.LINUX_X86_64, false, "/build", null);
        assertNull(explicitNull.cCompilerKind());
        assertEquals(CCompilerKind.ZIG_CC, explicitNull.effectiveCCompilerKind());

        // An explicit kind wins over the level-based default on both levels.
        var zigOnDebug = new CompileOptions(
                GodotVersion.V451, null, COptimizationLevel.DEBUG, TargetPlatform.LINUX_X86_64, false, "/build",
                CCompilerKind.ZIG_CC);
        assertEquals(CCompilerKind.ZIG_CC, zigOnDebug.effectiveCCompilerKind());
        var tinyCcOnRelease = new CompileOptions(
                GodotVersion.V451, null, COptimizationLevel.RELEASE, TargetPlatform.LINUX_X86_64, false, "/build",
                CCompilerKind.TINY_CC);
        assertEquals(CCompilerKind.TINY_CC, tinyCcOnRelease.effectiveCCompilerKind());

        // The bootstrap profile is debug + auto, i.e. the tinycc backend.
        assertNull(CompileOptions.defaults().cCompilerKind());
        assertEquals(CCompilerKind.TINY_CC, CompileOptions.defaults().effectiveCCompilerKind());
    }

    @Test
    void apiSetCompileOptionsRoundTripsTheCompilerKind() {
        var api = new API();
        api.createModule("demo", "Demo");

        var zig = new CompileOptions(
                GodotVersion.V451, null, COptimizationLevel.DEBUG, TargetPlatform.getNativePlatform(),
                false, CompileOptions.DEFAULT_OUTPUT_MOUNT_ROOT, CCompilerKind.ZIG_CC);
        assertEquals(CCompilerKind.ZIG_CC, api.setCompileOptions("demo", zig).cCompilerKind());
        assertEquals(zig, api.getCompileOptions("demo"));

        var auto = new CompileOptions(
                GodotVersion.V451, null, COptimizationLevel.RELEASE, TargetPlatform.getNativePlatform(),
                false, CompileOptions.DEFAULT_OUTPUT_MOUNT_ROOT, null);
        assertNull(api.setCompileOptions("demo", auto).cCompilerKind());
        assertEquals(auto, api.getCompileOptions("demo"));
    }

    @Test
    void stripNativeSymbolsDefaultsToFalseAndRoundTripsThroughTheApi() {
        // Every pre-strip constructor shape leaves the flag unset, so existing builds keep
        // zig's default DWARF output and full symbol table byte-identically.
        assertNull(CompileOptions.defaults().stripNativeSymbols());
        assertFalse(CompileOptions.defaults().isStripNativeSymbols());
        var sixArg = new CompileOptions(
                GodotVersion.V451, null, COptimizationLevel.RELEASE, TargetPlatform.LINUX_X86_64, false, "/build");
        assertNull(sixArg.stripNativeSymbols());
        assertFalse(sixArg.isStripNativeSymbols());
        var sevenArgNull = new CompileOptions(
                GodotVersion.V451, null, COptimizationLevel.RELEASE, TargetPlatform.LINUX_X86_64, false, "/build", null);
        assertFalse(sevenArgNull.isStripNativeSymbols());

        var api = new API();
        api.createModule("demo", "Demo");
        var stripped = new CompileOptions(
                GodotVersion.V451, null, COptimizationLevel.RELEASE, TargetPlatform.LINUX_X86_64,
                false, CompileOptions.DEFAULT_OUTPUT_MOUNT_ROOT, CCompilerKind.ZIG_CC, true);
        assertTrue(stripped.isStripNativeSymbols());
        assertEquals(stripped, api.setCompileOptions("demo", stripped));
        assertTrue(api.getCompileOptions("demo").isStripNativeSymbols());
    }
}
