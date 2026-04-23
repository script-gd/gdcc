package dev.superice.gdcc.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiMultiModuleIsolationTest {
    @Test
    void compileOnOneModuleDoesNotBlockOtherModuleOperations(@TempDir Path tempDir) throws Exception {
        var compiler = ApiCompileTestSupport.RecordingCompiler.blockingSuccess();
        var api = ApiCompileTestSupport.newApi(compiler);

        api.createModule("alpha", "Alpha Module");
        api.setCompileOptions("alpha", ApiCompileTestSupport.compileOptions(tempDir.resolve("alpha-project")));
        api.putFile("alpha", "/src/alpha.gd", validSource("AlphaModule"));

        api.createModule("beta", "Beta Module");
        api.setCompileOptions("beta", ApiCompileTestSupport.compileOptions(tempDir.resolve("beta-project")));

        var alphaTaskId = api.compile("alpha");
        assertTrue(compiler.awaitEntered());
        ApiCompileTestSupport.awaitSnapshot(
                api,
                alphaTaskId,
                snapshot -> snapshot.state() == CompileTaskSnapshot.State.RUNNING
                        && snapshot.stage() == CompileTaskSnapshot.Stage.BUILDING_NATIVE,
                "BUILDING_NATIVE"
        );

        try {
            try (var executor = Executors.newSingleThreadExecutor()) {
                var betaFuture = executor.submit(() -> {
                    api.putFile("beta", "/src/beta.gd", validSource("BetaModule"));
                    return api.readFile("beta", "/src/beta.gd");
                });

                assertEquals(validSource("BetaModule"), betaFuture.get(1, TimeUnit.SECONDS));

                compiler.release();
                assertEquals(CompileResult.Outcome.SUCCESS, ApiCompileTestSupport.awaitResult(api, alphaTaskId).outcome());
                assertEquals(validSource("BetaModule"), api.readFile("beta", "/src/beta.gd"));
            }
        } finally {
            compiler.release();
        }
    }

    private static String validSource(String className) {
        return """
                class_name %s
                extends RefCounted
                
                func value() -> int:
                    return 1
                """.formatted(className);
    }
}
