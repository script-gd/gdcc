package gd.script.gdcc.api;

import gd.script.gdcc.backend.c.build.CCompilerKind;
import gd.script.gdcc.backend.c.build.COptimizationLevel;
import gd.script.gdcc.backend.c.build.TargetPlatform;
import gd.script.gdcc.enums.GodotVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Pins the options-freeze contract: a compile task consumes the `CompileOptions` snapshot frozen
/// at the start of its run, so a later `setCompileOptions` — even one issued while the task is
/// still holding the module gate — never alters the in-flight round. The frozen snapshot stays
/// observable through the finished task's result while the module's current options move on.
class ApiCompileOptionsFreezeTest {
    @Test
    void optionChangeAfterFreezeDoesNotAffectTheInFlightCompile(@TempDir Path tempDir) throws Exception {
        var compiler = ApiCompileTestSupport.RecordingCompiler.blockingSuccess();
        var api = ApiCompileTestSupport.newApi(compiler);

        api.createModule("demo", "Freeze Demo");
        api.putFile("demo", "/src/demo.gd", "extends RefCounted\n");
        api.setCompileOptions("demo", new CompileOptions(
                GodotVersion.V451,
                tempDir.resolve("freeze-project"),
                COptimizationLevel.DEBUG,
                TargetPlatform.getNativePlatform(),
                false,
                CompileOptions.DEFAULT_OUTPUT_MOUNT_ROOT,
                CCompilerKind.TINY_CC
        ));

        var taskId = api.compile("demo");
        // The request is frozen when the runner starts, long before the native compiler runs, so
        // reaching the (latched) compiler proves the freeze already happened.
        assertTrue(compiler.awaitEntered(), "the compile must reach the native build stage");
        try {
            // The module gate is held for the whole in-flight compile: this mutation is issued
            // during the build but can only be applied after the frozen request was consumed.
            var setter = Thread.ofVirtual().start(() -> api.setCompileOptions("demo", new CompileOptions(
                    GodotVersion.V451,
                    tempDir.resolve("freeze-project"),
                    COptimizationLevel.DEBUG,
                    TargetPlatform.getNativePlatform(),
                    false,
                    CompileOptions.DEFAULT_OUTPUT_MOUNT_ROOT,
                    CCompilerKind.ZIG_CC
            )));
            compiler.release();
            ApiCompileTestSupport.awaitTask(api, taskId);
            setter.join(TimeUnit.SECONDS.toMillis(30));
        } finally {
            compiler.release();
        }

        var frozen = Objects.requireNonNull(api.getLastCompileResult("demo")).compileOptions();
        assertEquals(
                CCompilerKind.TINY_CC,
                frozen.cCompilerKind(),
                "the in-flight round kept the options frozen before the mutation"
        );
        assertEquals(
                CCompilerKind.ZIG_CC,
                api.getCompileOptions("demo").cCompilerKind(),
                "the module's current options reflect the later set"
        );
    }
}
