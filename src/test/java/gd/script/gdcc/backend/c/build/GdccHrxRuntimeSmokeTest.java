package gd.script.gdcc.backend.c.build;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Zig-gated pure-C smoke tests for the HRX hot-reload thunk runtime
/// (`gdcc/gdcc_hrx.c`; contract: doc/module_impl/backend/hot_reload_implementation.md).
/// The fixtures fake the Godot interface at the GDExtension function-pointer level while the
/// shared thunk page uses REAL executable memory on the host, so on x86_64 the emitted machine
/// code actually executes: call/is_valid/free/get_argument_count all run through real thunks,
/// and "reload" is simulated by deinitialize -> initialize_core cycles with new rebind tables.
/// Skipped via assumption when no zig is on the machine.
class GdccHrxRuntimeSmokeTest {
    private static final Path GODOT_INCLUDE_DIR = Path.of("src/main/c/codegen/include_451/godot").toAbsolutePath().normalize();
    private static final Path GDCC_INCLUDE_DIR = Path.of("src/main/c/codegen/include_451/gdcc").toAbsolutePath().normalize();

    @TempDir
    private static Path sharedDir;

    private static Path zig;
    private static List<Path> runtimeObjects;

    @BeforeAll
    static void compileRuntimeObjects() throws IOException, InterruptedException {
        zig = ZigUtil.findZig();
        Assumptions.assumeTrue(zig != null, "Zig executable is required for HRX runtime C smoke tests");
        runtimeObjects = List.of(
                compileObject(zig, GODOT_INCLUDE_DIR.resolve("godot_binding.c"), sharedDir.resolve("godot_binding.o")),
                // Test hooks enable the image-state reset seam used to simulate fresh DSO images.
                compileObject(zig, GDCC_INCLUDE_DIR.resolve("gdcc_hrx.c"), sharedDir.resolve("gdcc_hrx.o"),
                        "-DGDCC_HRX_TEST_HOOKS")
        );
    }

    @Test
    void thunkTemplatesShouldMatchFrozenBytes() throws IOException, InterruptedException {
        // ABI freeze anchor: the hand-assembled templates need ZERO runtime patching (the
        // spec arrives via the callback's first argument, the hub via `spec->hub`); every
        // byte is pinned exactly, so any silent drift fails here. All three architecture
        // variants are data on every host, so they are pinned everywhere (execution of the
        // host variant is covered by the other probes).
        var source = CProbeSources.load("hrx/templates_probe.c");
        var execution = compileLinkAndRun("templates_probe", HrxSmokeProbeSources.FAKE_ENGINE + source, runtimeObjects);
        assertEquals(0, execution.exitCode(), execution::diagnostic);
        assertTrue(execution.output().contains("OK templates"), execution::diagnostic);
    }

    @Test
    void execmemProbeShouldSucceedOnHost() throws IOException, InterruptedException {
        // The real RW->write->RX->execute round trip must work on the dev host (Linux x86_64);
        // HRX_UNAVAILABLE fail-closed behavior is anchored by the unavailable-mode probe below.
        var source = HrxSmokeProbeSources.EXECMEM_PROBE;
        var execution = compileLinkAndRun("execmem_probe", HrxSmokeProbeSources.FAKE_ENGINE + source, runtimeObjects);
        assertEquals(0, execution.exitCode(), execution::diagnostic);
        assertTrue(execution.output().contains("OK execmem"), execution::diagnostic);
    }

    @Test
    void lambdaCallableShouldRoundTripThroughRealThunks() throws IOException, InterruptedException {
        // Creation -> registry/refcount publication -> thunk execution (arg0 swap, pass-through),
        // is_valid / get_argument_count data answers, then deinitialize invalidation and the
        // engine-standard error path, and finally free-thunk -> live sweeper reclaim.
        var source = HrxSmokeProbeSources.LAMBDA_ROUNDTRIP_PROBE;
        var execution = compileLinkAndRun("lambda_roundtrip_probe", HrxSmokeProbeSources.FAKE_ENGINE + source, runtimeObjects);
        assertEquals(0, execution.exitCode(), execution::diagnostic);
        assertTrue(execution.output().contains("OK lambda_roundtrip"), execution::diagnostic);
    }

    @Test
    void rebindShouldUpgradeSurvivorsAndFailClosedOnSchemaMismatch() throws IOException, InterruptedException {
        // Simulated reload: generation 1 creates two lambdas; deinitialize invalidates;
        // generation 2 arrives with a rebind table whose entry for A is schema-compatible
        // (rebound to the NEW implementation, argument count updated) and whose entry for B
        // has a different descriptor (stays UNBOUND: is_valid false, impl never runs, engine
        // error path on call).
        var source = HrxSmokeProbeSources.REBIND_MISMATCH_PROBE;
        var execution = compileLinkAndRun("rebind_probe", HrxSmokeProbeSources.FAKE_ENGINE + source, runtimeObjects);
        assertEquals(0, execution.exitCode(), execution::diagnostic);
        assertTrue(execution.output().contains("OK rebind"), execution::diagnostic);
    }

    @Test
    void rebindShouldRespectTheCallsiteContextGate() throws IOException, InterruptedException {
        // Same impl_key + same schema descriptor + MATCHING context rebinds (A); a context
        // mismatch with identical key+schema fails closed (B — the statement-swap fix);
        // NULL vs non-NULL never matches (C); NULL==NULL matches (D, standalone shape).
        var source = CProbeSources.load("hrx/rebind_context_gate_probe.c");
        var execution = compileLinkAndRun("rebind_context_gate_probe", HrxSmokeProbeSources.FAKE_ENGINE + source, runtimeObjects);
        assertEquals(0, execution.exitCode(), execution::diagnostic);
        assertTrue(execution.output().contains("OK rebind_context_gate"), execution::diagnostic);
    }

    @Test
    void rebindShouldFailClosedOnContextMismatch() throws IOException, InterruptedException {
        // The swap fix at runtime level: a survivor whose context differs from the new entry's
        // (same key + same schema) stays UNBOUND — the old Callable goes invalid and never runs.
        var source = CProbeSources.load("hrx/rebind_context_mismatch_probe.c");
        var execution = compileLinkAndRun("rebind_context_mismatch_probe", HrxSmokeProbeSources.FAKE_ENGINE + source, runtimeObjects);
        assertEquals(0, execution.exitCode(), execution::diagnostic);
        assertTrue(execution.output().contains("OK rebind_context_mismatch"), execution::diagnostic);
    }

    @Test
    void callsiteContextShouldBeHeapCopiedAndVersionGuarded() throws IOException, InterruptedException {
        // Cross-generation safety: (1) the spec stores a HEAP COPY of the identity's
        // context (generated .rodata dies at dlclose); (2) a legacy-ABI spec is rejected by
        // the version guard even when key+schema+context would all match; (3) shell_free
        // only touches the appended field under abi_version >= 2, so a legacy block carrying
        // a poison pointer there is swept without dereferencing it.
        var source = CProbeSources.load("hrx/callsite_context_lifecycle_probe.c");
        var execution = compileLinkAndRun("callsite_context_lifecycle_probe", HrxSmokeProbeSources.FAKE_ENGINE + source, runtimeObjects);
        assertEquals(0, execution.exitCode(), execution::diagnostic);
        assertTrue(execution.output().contains("OK callsite_context_lifecycle"), execution::diagnostic);
    }

    @Test
    void schemaMismatchedStandaloneShouldYieldFreshSpecOnRecreate() throws IOException, InterruptedException {
        // Simulated reload with a CHANGED standalone schema: the surviving old spec cannot
        // rebind and stays a fail-closed zombie. The new generation's same-key create must
        // detach that zombie from the intern table and build a FRESH spec instead of
        // inheriting the NULLed implementation (fail-closed must only isolate OLD Callables).
        var source = CProbeSources.load("hrx/standalone_schema_recreate_probe.c");
        var execution = compileLinkAndRun("standalone_schema_recreate_probe", HrxSmokeProbeSources.FAKE_ENGINE + source, runtimeObjects);
        assertEquals(0, execution.exitCode(), execution::diagnostic);
        assertTrue(execution.output().contains("OK standalone-schema-recreate"), execution::diagnostic);
    }

    @Test
    void sharedStandaloneShouldRefcountInOrderAndSweepWithInternDetach() throws IOException, InterruptedException {
        // Interning shares one (thunk, spec) per identity: two creations are equal under
        // Godot's default identity; the FIRST free must NOT invalidate (refcount order:
        // decrement first, only the zero crossing marks dead and sweeps); after the sweep the
        // identity is detached from the interning table so a recreate gets a FRESH spec
        // instead of a corpse.
        var source = CProbeSources.load("hrx/standalone_shared_probe.c");
        var execution = compileLinkAndRun("standalone_shared_probe", HrxSmokeProbeSources.FAKE_ENGINE + source, runtimeObjects);
        assertEquals(0, execution.exitCode(), execution::diagnostic);
        assertTrue(execution.output().contains("OK standalone_shared"), execution::diagnostic);
    }

    @Test
    void deadInternedSpecShouldNeverBeReusedBeforeSweep() throws IOException, InterruptedException {
        // In the sweeper-less window after deinitialize, a freed standalone is only marked
        // dead. A same-identity recreation in that window must NOT resurrect the corpse
        // (interning lookup rejects dead specs); the old shell is reclaimed by the next
        // generation's two-phase sweep.
        var source = CProbeSources.load("hrx/dead_interned_probe.c");
        var execution = compileLinkAndRun("dead_interned_probe", HrxSmokeProbeSources.FAKE_ENGINE + source, runtimeObjects);
        assertEquals(0, execution.exitCode(), execution::diagnostic);
        assertTrue(execution.output().contains("OK dead_interned"), execution::diagnostic);
    }

    @Test
    void freeThunkShouldGuardRefcountUnderflow() throws IOException, InterruptedException {
        // A duplicated free (engine anomaly: the same thunk entered twice with the same
        // correct userdata) must be a no-op: the guard reads refcount==0 and returns without
        // touching state; the shell survives until the next generation sweeps.
        var source = CProbeSources.load("hrx/underflow_probe.c");
        var execution = compileLinkAndRun("underflow_probe", HrxSmokeProbeSources.FAKE_ENGINE + source, runtimeObjects);
        assertEquals(0, execution.exitCode(), execution::diagnostic);
        assertTrue(execution.output().contains("OK underflow"), execution::diagnostic);
    }

    @Test
    void sweepShouldUseRebindTableDestroyWithFingerprintAndDescriptorGates() throws IOException, InterruptedException {
        // Phase-2 destruction contract: the destroy_fn comes from the REBIND TABLE (the spec's
        // own pointers were NULLed by deinitialize), and only when BOTH the 128-bit
        // fingerprint AND the byte-exact descriptor match. Spec A matches: table destroy runs.
        // Spec B has its fingerprint hand-tampered to equal the table entry's while its
        // descriptor differs: the byte-compare gate must still refuse destruction and leak the
        // capture block intentionally (shell metadata is still freed).
        var source = CProbeSources.load("hrx/sweep_destroy_gates_probe.c");
        var execution = compileLinkAndRun("sweep_destroy_gates_probe", HrxSmokeProbeSources.FAKE_ENGINE + source, runtimeObjects);
        assertEquals(0, execution.exitCode(), execution::diagnostic);
        assertTrue(execution.output().contains("OK sweep_destroy_gates"), execution::diagnostic);
    }

    @Test
    void sweepReentrancyShouldQueuePendingAndDrainAtDepthZero() throws IOException, InterruptedException {
        // A destroy_fn that drops ANOTHER Callable reenters the free thunk mid-sweep: the
        // nested spec must queue on the pending list (pending_next) and be drained only after
        // the outer sweep unwinds, in order, with both capture blocks destroyed exactly once.
        var source = CProbeSources.load("hrx/sweep_reentrancy_probe.c");
        var execution = compileLinkAndRun("sweep_reentrancy_probe", HrxSmokeProbeSources.FAKE_ENGINE + source, runtimeObjects);
        assertEquals(0, execution.exitCode(), execution::diagnostic);
        var events = execution.output().lines().filter(line -> line.startsWith("EV ")).map(line -> line.substring(3)).toList();
        assertEquals(List.of("destroy:A", "destroy:B", "done"), events, execution::diagnostic);
    }

    @Test
    void sharedThunkPageShouldServeAllSpecsAndStayConstantAcrossGenerations() throws IOException, InterruptedException {
        // Every spec's four function pointers reference the hub's single shared thunk page at
        // frozen role offsets. The page is written once at hub creation — before any Callable
        // exists — and never again: a simulated reload keeps the OLD Callable's pointers
        // unchanged, and new-generation specs reuse the same addresses.
        var source = CProbeSources.load("hrx/thunkpage_probe.c");
        var execution = compileLinkAndRun("thunkpage_probe", HrxSmokeProbeSources.FAKE_ENGINE + source, runtimeObjects);
        assertEquals(0, execution.exitCode(), execution::diagnostic);
        assertTrue(execution.output().contains("OK thunkpage"), execution::diagnostic);
    }

    @Test
    void failedHubCreationTombstoneMustBeCleanedForLaterTakeover() throws IOException, InterruptedException {
        // A NULL binding slot (tombstone) is left behind when a hub creation fails, because
        // the engine keeps the freshly appended slot even when create_callback returns NULL
        // (object.cpp:2116-2147). If it is not dropped before creating, the tombstone shadows
        // every later lookup of the token (first match wins) and NO generation could ever
        // take over the hub — old Callables would never rebind.
        var source = CProbeSources.load("hrx/tombstone_probe.c");
        var execution = compileLinkAndRun("tombstone_probe", HrxSmokeProbeSources.FAKE_ENGINE + source, runtimeObjects);
        assertEquals(0, execution.exitCode(), execution::diagnostic);
        assertTrue(execution.output().contains("OK tombstone"), execution::diagnostic);
    }

    @Test
    void stackedTombstonesShouldAllBeSweptBeforeHubCreation() throws IOException, InterruptedException {
        // Repeated failed creations (or an unfixed runtime build) can leave a STACK of NULL
        // tombstones ahead of a valid hub slot. The bounded sweep in initialize_core frees
        // them one per iteration until the real hub surfaces: it must be taken over (never
        // shadowed by a fresh duplicate hub).
        var source = CProbeSources.load("hrx/stacked_tombstone_probe.c");
        var execution = compileLinkAndRun("stacked_tombstone_probe", HrxSmokeProbeSources.FAKE_ENGINE + source, runtimeObjects);
        assertEquals(0, execution.exitCode(), execution::diagnostic);
        assertTrue(execution.output().contains("OK stacked-tombstone"), execution::diagnostic);
    }

    @Test
    void anchorShouldCoexistWithOccupiedSlotZeroAndKeepCallbacksNull() throws IOException, InterruptedException {
        // The hub anchor must mount through get_instance_binding even when a foreign binding
        // already occupies the object (set_instance_binding would hard-fail on slot 0). The
        // callbacks handed to the engine must have free/reference == NULL (they are copied
        // into the slot and would dangle across a reload), and a generation restart must not
        // recreate the hub.
        var source = CProbeSources.load("hrx/anchor_probe.c");
        var execution = compileLinkAndRun("anchor_probe", HrxSmokeProbeSources.FAKE_ENGINE + source, runtimeObjects);
        assertEquals(0, execution.exitCode(), execution::diagnostic);
        assertTrue(execution.output().contains("OK anchor"), execution::diagnostic);
    }

    @Test
    void mismatchedHubShouldBeOrphanedAndReplaced() throws IOException, InterruptedException {
        // A foreign/corrupt binding under our token (bad magic): never traverse it, free the
        // binding, mount a fresh hub, and leave the old one an unreachable island.
        var source = CProbeSources.load("hrx/mismatch_probe.c");
        var execution = compileLinkAndRun("mismatch_probe", HrxSmokeProbeSources.FAKE_ENGINE + source, runtimeObjects);
        assertEquals(0, execution.exitCode(), execution::diagnostic);
        assertTrue(execution.output().contains("OK mismatch"), execution::diagnostic);
    }

    @Test
    void mismatchedHubWithFailedProbeShouldFreezeUnavailable() throws IOException, InterruptedException {
        // A foreign/corrupt anchor is NOT proof of a working thunk-page environment: even
        // after orphaning it, a failed execmem probe must freeze the process into
        // HRX_UNAVAILABLE instead of mounting a fresh hub and activating.
        var source = CProbeSources.load("hrx/mismatch_probe_false.c");
        var execution = compileLinkAndRun("mismatch_probe_false", HrxSmokeProbeSources.FAKE_ENGINE + source, runtimeObjects);
        assertEquals(0, execution.exitCode(), execution::diagnostic);
        assertTrue(execution.output().contains("OK mismatch-probe"), execution::diagnostic);
    }

    @Test
    void unavailableModeShouldFailClosedAndConsumeCaptures() throws IOException, InterruptedException {
        // Editor process without executable memory: creation is refused (never a silent
        // fallback to the direct path), the error is reported exactly once, and the caller's
        // capture block is consumed with its free_func (the "failed creation drops the last
        // reference" semantics), so no half-built state escapes.
        var source = CProbeSources.load("hrx/unavailable_probe.c");
        var execution = compileLinkAndRun("unavailable_probe", HrxSmokeProbeSources.FAKE_ENGINE + source, runtimeObjects);
        assertEquals(0, execution.exitCode(), execution::diagnostic);
        assertTrue(execution.output().contains("OK unavailable"), execution::diagnostic);
    }

    @Test
    void directModeShouldKeepTheLegacyDispatchIntact() throws IOException, InterruptedException {
        // Non-editor process: no hub, no anchor, no executable memory; the two creation
        // entries keep library function pointers and raw userdata, and the HRX creation
        // APIs stay defensively inert.
        var source = CProbeSources.load("hrx/direct_probe.c");
        var execution = compileLinkAndRun("direct_probe", HrxSmokeProbeSources.FAKE_ENGINE + source, runtimeObjects);
        assertEquals(0, execution.exitCode(), execution::diagnostic);
        assertTrue(execution.output().contains("OK direct"), execution::diagnostic);
    }

    @Test
    void identitylessWaiterSpecShouldNeverRebindAcrossReload() throws IOException, InterruptedException {
        // Coroutine signal waiters carry no rebind identity: callable this generation,
        // permanently invalid after a reload — a stray connection can never jump into
        // unloaded code. Its post-reload destruction is intentionally skipped (unknown
        // layout) while the shell is reclaimed.
        var source = CProbeSources.load("hrx/waiter_no_rebind_probe.c");
        var execution = compileLinkAndRun("waiter_no_rebind_probe", HrxSmokeProbeSources.FAKE_ENGINE + source, runtimeObjects);
        assertEquals(0, execution.exitCode(), execution::diagnostic);
        assertTrue(execution.output().contains("OK waiter_no_rebind"), execution::diagnostic);
    }

    @Test
    void retainShouldRebuildAnEqualLookupKeyForSignalDetach() throws IOException, InterruptedException {
        // The coroutine bulk-cancel detach needs a Callable EQUAL to the connection's under
        // Godot's default identity. In thunk mode that is only possible by retaining the same
        // spec: the retained key shares (thunk, spec), drops only the refcount on destroy,
        // and refuses dead/NULL specs.
        var source = HrxSmokeProbeSources.RETAIN_PROBE;
        var execution = compileLinkAndRun("retain_probe", HrxSmokeProbeSources.FAKE_ENGINE + source, runtimeObjects);
        assertEquals(0, execution.exitCode(), execution::diagnostic);
        assertTrue(execution.output().contains("OK retain"), execution::diagnostic);
    }

    @Test
    void gdccHrxRuntimeShouldCompileForEveryBuildableTargetIsa() throws IOException, InterruptedException {
        // Buildability regression pin: LINUX_RISCV64 (no thunk template set) must still
        // COMPILE the runtime — the probe fails closed there instead of breaking the build.
        // The aarch64 and Win64 variants pin the ISA-specific branches (icache flush,
        // VirtualAlloc) that the x86_64 host build never compiles.
        for (var target : List.of("riscv64-linux-gnu", "aarch64-linux-gnu", "x86_64-windows-gnu")) {
            var output = sharedDir.resolve("gdcc_hrx_" + target + ".o");
            var command = new ArrayList<String>();
            command.add(zig.toString());
            command.add("cc");
            command.add("-std=c23");
            command.add("-D_DEFAULT_SOURCE");
            command.add("-target");
            command.add(target);
            command.add("-I" + GODOT_INCLUDE_DIR);
            command.add("-I" + GDCC_INCLUDE_DIR);
            command.add("-c");
            command.add(GDCC_INCLUDE_DIR.resolve("gdcc_hrx.c").toString());
            command.add("-o");
            command.add(output.toString());
            var process = new ProcessBuilder(command).redirectErrorStream(true).start();
            var processOutput = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.waitFor(), () -> String.join(" ", command) + "\n" + processOutput);
        }
    }

    // ---------------------------------------------------------------------------
    // Harness (mirrors GdccCoroutineRuntimeSmokeTest conventions).
    // ---------------------------------------------------------------------------

    private static CompileResult compileLinkAndRun(String probeName, String source, List<Path> extraObjects)
            throws IOException, InterruptedException {
        var probeSource = sharedDir.resolve(probeName + ".c");
        Files.writeString(probeSource, source, StandardCharsets.UTF_8);
        var probeObject = compileObject(zig, probeSource, sharedDir.resolve(probeName + ".o"));
        var objects = new ArrayList<Path>();
        objects.add(probeObject);
        objects.addAll(extraObjects);
        var executable = sharedDir.resolve(probeName);
        var linked = linkExecutable(zig, objects, executable);
        assertEquals(0, linked.exitCode(), linked::diagnostic);
        return runExecutable(executable);
    }

    private static Path compileObject(Path zig, Path source, Path output, String... extraFlags) throws IOException, InterruptedException {
        var command = new ArrayList<String>();
        command.add(zig.toString());
        command.add("cc");
        command.add("-std=c23");
        command.add("-D_DEFAULT_SOURCE");
        command.add("-I" + GODOT_INCLUDE_DIR);
        command.add("-I" + GDCC_INCLUDE_DIR);
        command.addAll(List.of(extraFlags));
        command.add("-c");
        command.add(source.toString());
        command.add("-o");
        command.add(output.toString());

        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        var processOutput = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        var exitCode = process.waitFor();
        assertEquals(0, exitCode, () -> String.join(" ", command) + "\n" + processOutput);
        return output;
    }

    private static CompileResult linkExecutable(Path zig, List<Path> objects, Path output) throws IOException, InterruptedException {
        var command = new ArrayList<String>();
        command.add(zig.toString());
        command.add("cc");
        for (var object : objects) {
            command.add(object.toString());
        }
        command.add("-o");
        command.add(output.toString());

        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        var processOutput = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        var exitCode = process.waitFor();
        return new CompileResult(command, exitCode, processOutput, output);
    }

    private static CompileResult runExecutable(Path executable) throws IOException, InterruptedException {
        var command = List.of(executable.toString());
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        var processOutput = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        var exitCode = process.waitFor();
        return new CompileResult(command, exitCode, processOutput, executable);
    }

    private record CompileResult(List<String> command, int exitCode, String output, Path outputPath) {
        String diagnostic() {
            return String.join(" ", command) + "\n" + output;
        }
    }

}
