package gd.script.gdcc.backend.c.build;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Concurrent cold initialization, registry growth, cached lookups and quiescent generation reuse.
class GdccCacheConcurrencyTest {
    @TempDir
    Path temporary;

    @Test
    void cachesPublishSafelyOnZig() throws IOException, InterruptedException {
        var toolchain = RuntimeProbeToolchain.zig();
        Assumptions.assumeTrue(toolchain != null, "Zig is required");
        var result = toolchain.compileLinkAndRun(temporary, "cache_concurrency", probe(),
                List.of(RuntimeProbeToolchain.GODOT_INCLUDE_DIR.resolve("godot_binding.c")), List.of());
        assertEquals(0, result.exitCode(), result::diagnostic);
        assertTrue(result.output().contains("OK concurrent caches"), result::diagnostic);
    }

    @Test
    void cachesPublishSafelyOnTinyCc() throws IOException, InterruptedException {
        var toolchain = RuntimeProbeToolchain.tcc();
        Assumptions.assumeTrue(toolchain != null, "TinyCC is required");
        var result = toolchain.compileLinkAndRun(temporary, "cache_concurrency", probe(),
                List.of(RuntimeProbeToolchain.GODOT_INCLUDE_DIR.resolve("godot_binding.c")), List.of());
        assertEquals(0, result.exitCode(), result::diagnostic);
        assertTrue(result.output().contains("OK concurrent caches"), result::diagnostic);
    }

    private static String probe() {
        var sites = IntStream.range(0, 300)
                .mapToObj(index -> "CACHE_SITE(" + index + ")")
                .collect(Collectors.joining("\n"));
        return GdccStaticStringRuntimeSmokeTest.FAKE_ENGINE
                + CProbeSources.load("cache_concurrency_probe.c").replace("/* CACHE_SITES */", sites);
    }
}
