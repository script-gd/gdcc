package dev.superice.gdcc.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiMappedClassCompileTest {
    @Test
    void compilePropagatesTopLevelCanonicalNameMapIntoLoweringAndBackend(@TempDir Path tempDir) throws Exception {
        var compiler = ApiCompileTestSupport.RecordingCompiler.succeeding();
        var api = ApiCompileTestSupport.newApi(compiler);
        var projectPath = tempDir.resolve("mapped-project");
        var topLevelCanonicalNameMap = Map.of(
                "MappedCoordinator", "RuntimeMappedCoordinator",
                "Worker", "RuntimeMappedWorker"
        );

        api.createModule("demo", "Mapped Demo");
        api.setCompileOptions("demo", ApiCompileTestSupport.compileOptions(projectPath));
        api.setTopLevelCanonicalNameMap("demo", topLevelCanonicalNameMap);
        api.putFile("demo", "/src/worker.gd", """
                class_name Worker
                extends RefCounted
                
                func value() -> int:
                    return 11
                """);
        api.putFile("demo", "/src/coordinator.gd", """
                class_name MappedCoordinator
                extends RefCounted
                
                func run() -> int:
                    var worker: Worker = Worker.new()
                    return worker.value()
                """);

        var result = ApiCompileTestSupport.awaitResult(api, api.compile("demo"));
        var entrySource = Files.readString(projectPath.resolve("entry.c"));

        assertEquals(CompileResult.Outcome.SUCCESS, result.outcome());
        assertTrue(result.success());
        assertTrue(result.diagnostics().isEmpty());
        assertEquals(topLevelCanonicalNameMap, result.topLevelCanonicalNameMap());
        assertTrue(entrySource.contains("GD_STATIC_SN(u8\"RuntimeMappedCoordinator\")"), entrySource);
        assertTrue(entrySource.contains("GD_STATIC_SN(u8\"RuntimeMappedWorker\")"), entrySource);
        assertFalse(entrySource.contains("GD_STATIC_SN(u8\"MappedCoordinator\")"), entrySource);
        assertFalse(entrySource.contains("GD_STATIC_SN(u8\"Worker\")"), entrySource);
        assertEquals(1, compiler.invocationCount());
    }
}
