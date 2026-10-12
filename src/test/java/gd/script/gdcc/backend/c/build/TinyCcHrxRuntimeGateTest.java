package gd.script.gdcc.backend.c.build;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// tinycc leg of the HRX runtime gates (contract:
/// doc/module_impl/backend/hot_reload_implementation.md §7). The probe sources are shared
/// byte-identical with the zig baseline ([GdccHrxRuntimeSmokeTest]) through
/// [HrxSmokeProbeSources], so a pass here proves the tcc-compiled runtime behaves the same as
/// the zig artifact: the execmem probe succeeds, all four thunk kinds execute on real
/// executable memory, a retained Callable keeps an equal lookup key across a simulated
/// unload/reload cycle, and rebind upgrades compatible survivors while descriptor mismatches
/// fail closed. Assumption-gated on the tinycc CLI work tree.
class TinyCcHrxRuntimeGateTest {

    /// `GDCC_HRX_TEST_HOOKS` enables the image-state reset seam used to simulate fresh DSO
    /// images between generations.
    private static final List<String> DEFINES = List.of("GDCC_HRX_TEST_HOOKS");
    private static final List<Path> RUNTIME_SOURCES = List.of(
            RuntimeProbeToolchain.GODOT_INCLUDE_DIR.resolve("godot_binding.c"),
            RuntimeProbeToolchain.GDCC_INCLUDE_DIR.resolve("gdcc_hrx.c"));

    @TempDir
    private static Path sharedDir;

    private static RuntimeProbeToolchain tcc;

    @BeforeAll
    static void requireTcc() {
        tcc = RuntimeProbeToolchain.tcc();
        Assumptions.assumeTrue(tcc != null,
                "tinycc CLI is required for the tcc HRX runtime gates (GDCC_TINYCC_TCC unset, no build/tinycc work tree)");
    }

    @Test
    void execmemProbeRunsOnTccArtifact() throws IOException, InterruptedException {
        var execution = tcc.compileLinkAndRun(sharedDir, "execmem_probe",
                HrxSmokeProbeSources.FAKE_ENGINE + HrxSmokeProbeSources.EXECMEM_PROBE,
                RUNTIME_SOURCES, DEFINES);
        assertEquals(0, execution.exitCode(), execution::diagnostic);
        assertTrue(execution.output().contains("OK execmem"), execution::diagnostic);
    }

    @Test
    void fourThunkKindsExecuteOnTccArtifact() throws IOException, InterruptedException {
        var execution = tcc.compileLinkAndRun(sharedDir, "lambda_roundtrip_probe",
                HrxSmokeProbeSources.FAKE_ENGINE + HrxSmokeProbeSources.LAMBDA_ROUNDTRIP_PROBE,
                RUNTIME_SOURCES, DEFINES);
        assertEquals(0, execution.exitCode(), execution::diagnostic);
        assertTrue(execution.output().contains("OK lambda_roundtrip"), execution::diagnostic);
    }

    @Test
    void rebindUpgradeAndMismatchFailClosedOnTccArtifact() throws IOException, InterruptedException {
        var execution = tcc.compileLinkAndRun(sharedDir, "rebind_probe",
                HrxSmokeProbeSources.FAKE_ENGINE + HrxSmokeProbeSources.REBIND_MISMATCH_PROBE,
                RUNTIME_SOURCES, DEFINES);
        assertEquals(0, execution.exitCode(), execution::diagnostic);
        assertTrue(execution.output().contains("OK rebind"), execution::diagnostic);
    }

    @Test
    void retainedCallableKeepsEqualLookupKeyAcrossReloadOnTccArtifact() throws IOException, InterruptedException {
        var execution = tcc.compileLinkAndRun(sharedDir, "retain_probe",
                HrxSmokeProbeSources.FAKE_ENGINE + HrxSmokeProbeSources.RETAIN_PROBE,
                RUNTIME_SOURCES, DEFINES);
        assertEquals(0, execution.exitCode(), execution::diagnostic);
        assertTrue(execution.output().contains("OK retain"), execution::diagnostic);
    }
}
