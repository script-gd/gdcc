package gd.script.gdcc.backend.c.build;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Unit gate for the [CProcessRegistry] hard contract: mutually exclusive register/cancel,
/// "register after close destroys immediately", one-shot snapshot, and the register/cancel race
/// in which every process must end up exactly one of snapshotted or destroyed.
class CProcessRegistryTest {

    @Test
    void cancelSnapshotsEverythingRegisteredBeforeTheClose() {
        var registry = new CProcessRegistry();
        var first = new FakeProcess(0, "");
        var second = new FakeProcess(0, "");

        registry.register(first);
        registry.register(second);

        assertFalse(registry.isClosed());
        var snapshot = registry.cancelAndSnapshot();
        assertTrue(registry.isClosed());
        assertEquals(List.of(first, second), snapshot);
        assertFalse(first.destroyForciblyCalled(), "snapshot processes are destroyed by the canceller, not by register");
        assertFalse(second.destroyForciblyCalled());
    }

    @Test
    void registerAfterCloseDestroysImmediatelyAndNeverJoinsASnapshot() {
        var registry = new CProcessRegistry();
        var early = new FakeProcess(0, "");
        registry.register(early);
        var snapshot = registry.cancelAndSnapshot();
        assertEquals(List.of(early), snapshot);

        var late = new FakeProcess(0, "", true);
        registry.register(late);

        assertTrue(late.destroyForciblyCalled(), "a closed registry must destroy on register, not enqueue");
        assertFalse(late.isAlive(), "the destroyed blocking process must report itself dead");
        assertEquals(List.of(), registry.cancelAndSnapshot(), "the late process must never reach a later snapshot");
    }

    @Test
    void cancelAndSnapshotIsOneShot() {
        var registry = new CProcessRegistry();
        var process = new FakeProcess(0, "");
        registry.register(process);

        var first = registry.cancelAndSnapshot();
        var second = registry.cancelAndSnapshot();

        assertEquals(List.of(process), first);
        assertTrue(second.isEmpty(), "repeated cancel must not hand the same processes out twice");
        assertTrue(registry.isClosed());
    }

    @Test
    void registerRacingWithCancelLandsExactlyInSnapshotOrDestroyed() throws InterruptedException {
        // Deterministic two-phase race: phase-A workers fully register their batch and signal
        // completion; phase-B workers are held back until the registry is closed. The cancel
        // therefore provably interleaves with registration — phase A must land in the snapshot
        // exactly, phase B must be destroyed by the closed register calls.
        var phaseAWorkers = 2;
        var phaseBWorkers = 2;
        var processesPerWorker = 100;
        var registry = new CProcessRegistry();
        var phaseAProcesses = Collections.synchronizedList(new ArrayList<FakeProcess>());
        var phaseBProcesses = Collections.synchronizedList(new ArrayList<FakeProcess>());
        var phaseADone = new CountDownLatch(phaseAWorkers);
        var releasePhaseB = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(phaseAWorkers + phaseBWorkers);
        List<Process> snapshot;
        try (executor) {
            for (var w = 0; w < phaseAWorkers; w++) {
                executor.submit(() -> {
                    for (var i = 0; i < processesPerWorker; i++) {
                        var process = new FakeProcess(0, "");
                        phaseAProcesses.add(process);
                        registry.register(process);
                    }
                    phaseADone.countDown();
                });
            }
            for (var w = 0; w < phaseBWorkers; w++) {
                executor.submit(() -> {
                    try {
                        releasePhaseB.await();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError("phase-B worker interrupted before release", exception);
                    }
                    for (var i = 0; i < processesPerWorker; i++) {
                        var process = new FakeProcess(0, "");
                        phaseBProcesses.add(process);
                        registry.register(process);
                    }
                });
            }

            assertTrue(phaseADone.await(30, TimeUnit.SECONDS), "phase-A registrations must complete before the cancel");
            // phaseADone.await returning establishes happens-before: every phase-A register
            // call is visible to this cancel, so the snapshot must contain exactly phase A.
            snapshot = registry.cancelAndSnapshot();
            releasePhaseB.countDown();
        }

        assertEquals(phaseAWorkers * processesPerWorker, snapshot.size(),
                "the snapshot contains exactly the phase-A batch — no phase-B process may leak in");
        assertTrue(snapshot.containsAll(phaseAProcesses));
        assertTrue(phaseBProcesses.stream().noneMatch(snapshot::contains));
        assertEquals(phaseBWorkers * processesPerWorker, phaseBProcesses.size());
        assertTrue(phaseBProcesses.stream().allMatch(FakeProcess::destroyForciblyCalled),
                "every phase-B process registered after the close must be destroyed by register");
        assertTrue(phaseAProcesses.stream().noneMatch(FakeProcess::destroyForciblyCalled),
                "phase-A processes belong to the canceller via the snapshot, not to register");
    }
}
