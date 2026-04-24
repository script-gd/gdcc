package dev.superice.gdcc.api;

import dev.superice.gdcc.backend.c.build.COptimizationLevel;
import dev.superice.gdcc.backend.c.build.TargetPlatform;
import dev.superice.gdcc.enums.GodotVersion;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
}
