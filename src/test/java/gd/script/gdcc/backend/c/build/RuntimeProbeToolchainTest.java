package gd.script.gdcc.backend.c.build;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Focused anchors for [RuntimeProbeToolchain#runProcess] itself — the process lifecycle every
/// probe test relies on. Output capture is file-redirected, so these tests pin the remaining
/// contract points: merged streams, the timeout kill path, and that a descendant outliving the
/// child cannot stall the call. POSIX-gated because the fixtures need `sh`/`sleep` semantics.
class RuntimeProbeToolchainTest {

    @Test
    void completesCleanlyWhenTheChildWritesAndExits() throws Exception {
        var sh = TinyCcCliTestSupport.findOnPath("sh");
        Assumptions.assumeTrue(sh != null, "sh is required for the process lifecycle anchors");
        var run = RuntimeProbeToolchain.runProcess(
                List.of(sh.toString(), "-c", "printf hello; printf err >&2"), Duration.ofSeconds(10), null);
        assertEquals(0, run.exitCode());
        // stderr is merged into the same output file by the harness (redirectErrorStream).
        assertTrue(run.output().contains("hello") && run.output().contains("err"), run.output());
    }

    @Test
    void timeoutKillsAStuckChildAndConverges() {
        var sh = TinyCcCliTestSupport.findOnPath("sh");
        Assumptions.assumeTrue(sh != null, "sh is required for the process lifecycle anchors");
        var assertion = assertThrows(AssertionError.class, () ->
                RuntimeProbeToolchain.runProcess(List.of(sh.toString(), "-c", "exec sleep 600"), Duration.ofSeconds(2), null));
        assertTrue(assertion.getMessage().contains("did not exit"), assertion.getMessage());
    }

    @Test
    void aGrandchildHoldingTheOutputFileDoesNotStallTheCall() throws Exception {
        // The child exits immediately after spawning a background descendant that inherits the
        // output file handle. File redirection means there is no pipe reader to strand: the call
        // must return the child's captured output without waiting for the descendant.
        var sh = TinyCcCliTestSupport.findOnPath("sh");
        Assumptions.assumeTrue(sh != null, "sh is required for the process lifecycle anchors");
        var start = System.nanoTime();
        var run = RuntimeProbeToolchain.runProcess(
                List.of(sh.toString(), "-c", "printf ready; sleep 30 & exit 0"), Duration.ofSeconds(30), null);
        var elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        assertEquals(0, run.exitCode(), run::diagnostic);
        assertTrue(run.output().contains("ready"), run::diagnostic);
        assertTrue(elapsedMillis < 15_000,
                "the call must return right after the child exits, not wait out the descendant (took " + elapsedMillis + "ms)");
    }
}
