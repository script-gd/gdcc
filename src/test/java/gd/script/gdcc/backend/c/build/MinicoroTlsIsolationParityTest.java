package gd.script.gdcc.backend.c.build;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Four-thread TLS isolation gate for the coroutine context switch (contract:
/// doc/gdcc_runtime_lib.md §Coroutine Runtime). minicoro keeps the currently running coroutine in
/// thread-local storage (`mco_running()`); if that storage ever degrades to a plain global (the
/// `MCO_NO_MULTITHREAD` fallback the gdcc patch forbids under tcc), concurrent resume/yield on
/// different threads corrupts each other's identity checks.
///
/// Determinism design: all four threads park INSIDE their coroutine bodies on one-shot
/// mutex-protected barriers (no volatile flag spinning — that would be an ISO C data race), in
/// enter/checked pairs: every thread must sample its step-0 identity before any thread may yield,
/// so a shared (non-TLS) current-coroutine pointer would hand at least three threads another
/// thread's coroutine — deterministically. (Without the "checked" barrier a scheduler could run
/// one thread's whole workload before the next resumes, and even a shared pointer would fake-pass
/// each check.) After the main pair each thread cancels a suspended sibling coroutine mid-flight
/// under the same two-phase discipline, proving cancellation stays thread-local. Cross-thread
/// failure accounting uses one slot per thread, aggregated by the main thread after join. The
/// Windows leg builds the barrier from CRITICAL_SECTION + a manual-reset event because the
/// vendored winapi subset carries no CONDITION_VARIABLE.
///
/// The probe TU additionally carries a compile-time anchor: it fails to compile when
/// `MCO_NO_MULTITHREAD` is defined. Both compiler legs must produce the same event set. The
/// generated-code coroutine behavior built on top of this mechanism is covered end-to-end by the
/// tcc test-suite subset (coroutine cases); this probe isolates the TLS/cancel mechanism itself.
///
/// Each leg is assumption-gated on its own toolchain; the parity test requires both.
class MinicoroTlsIsolationParityTest {

    /// The probe is a single TU: it pulls the implementation in through minicoro.c itself, so
    /// its compile-time anchors can see the TLS selection chain's result (the chain's macros
    /// live inside MINICORO_IMPL and are invisible to a declarations-only TU).
    private static final List<Path> RUNTIME_SOURCES = List.of();

    private static final List<String> EXPECTED_LINES = List.of(
            "EV t0 done steps=250",
            "EV t1 done steps=250",
            "EV t2 done steps=250",
            "EV t3 done steps=250",
            "OK tls_isolation");

    @TempDir
    private static Path sharedDir;

    private static RuntimeProbeToolchain zig;
    private static RuntimeProbeToolchain tcc;

    @BeforeAll
    static void findToolchains() {
        zig = RuntimeProbeToolchain.zig();
        tcc = RuntimeProbeToolchain.tcc();
    }

    @Test
    void coroutineTlsIsolationHoldsOnZigArtifact() throws IOException, InterruptedException {
        Assumptions.assumeTrue(zig != null, "zig is required for the baseline leg");
        var run = zig.compileLinkAndRun(sharedDir, "tls_probe", PROBE, RUNTIME_SOURCES, List.of());
        assertEquals(0, run.exitCode(), run::diagnostic);
        assertEquals(EXPECTED_LINES, sortedLines(run), run::diagnostic);
    }

    @Test
    void coroutineTlsIsolationHoldsOnTccArtifact() throws IOException, InterruptedException {
        Assumptions.assumeTrue(tcc != null, "tinycc CLI is required for the tcc leg");
        var run = tcc.compileLinkAndRun(sharedDir, "tls_probe", PROBE, RUNTIME_SOURCES, List.of());
        assertEquals(0, run.exitCode(), run::diagnostic);
        assertEquals(EXPECTED_LINES, sortedLines(run), run::diagnostic);
    }

    @Test
    void coroutineTlsBehaviorIsIdenticalAcrossCompilers() throws IOException, InterruptedException {
        Assumptions.assumeTrue(zig != null && tcc != null, "both toolchains are required for the parity assertion");
        var zigRun = zig.compileLinkAndRun(sharedDir, "tls_probe", PROBE, RUNTIME_SOURCES, List.of());
        var tccRun = tcc.compileLinkAndRun(sharedDir, "tls_probe", PROBE, RUNTIME_SOURCES, List.of());
        assertEquals(0, zigRun.exitCode(), zigRun::diagnostic);
        assertEquals(0, tccRun.exitCode(), tccRun::diagnostic);
        // Thread completion order is intentionally nondeterministic; the sorted event set is not.
        assertEquals(sortedLines(zigRun), sortedLines(tccRun),
                () -> "tcc artifact diverged from the zig baseline:\nzig:\n" + zigRun.diagnostic() + "\ntcc:\n" + tccRun.diagnostic());
    }

    private static List<String> sortedLines(RuntimeProbeToolchain.ProbeRun run) {
        return run.outputLines().stream().filter(line -> !line.isBlank()).sorted().collect(Collectors.toList());
    }

    private static final String PROBE = CProbeSources.load("minicoro_tls_probe.c");
}
