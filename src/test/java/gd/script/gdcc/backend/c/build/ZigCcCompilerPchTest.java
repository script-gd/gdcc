package gd.script.gdcc.backend.c.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Pure-Java PCH round gate: a [FakeProcessLauncher] plus a fixed zig version drives the real
/// [ZigCcCompiler] PCH logic without real zig processes, and the cache root is pinned inside
/// `@TempDir` so no shared compiler cache is touched. Anchors:
/// - happy path: round one builds, probes and publishes the entry; whitelisted TUs carry
///   `-include-pch` while `minicoro.c` never does; a second round reuses the installed entry
///   (probe still runs, no rebuild) and leaves no temporary litter;
/// - fallback: a failing PCH build degrades the round to no-PCH with the fixed fallback line
///   leading the log; a poisoned installed entry whose one allowed self-heal rebuild also
///   fails degrades the same way — PCH problems never fail the build;
/// - concurrency: two rounds sharing one cache root install exactly one entry, reference the
///   same pch path, and leave no temporary litter;
/// - cancellation: interrupting during the PCH build destroys the started child, starts no TU
///   and no link, and surfaces through the interrupted channel.
class ZigCcCompilerPchTest {
    private static final String VERSION = "0.16.0-test";
    private static final List<String> TU_NAMES = List.of("entry.c", "godot_binding.c", "minicoro.c", "gdcc_coroutine.c");

    @Test
    void pchIsBuiltProbedAndReusedAcrossRounds(@TempDir Path tempDir) throws IOException {
        var projectDir = Files.createDirectories(tempDir.resolve("project"));
        var includeDirs = writeIncludeDirs(tempDir);
        var sources = writeTuSources(projectDir);
        var cacheRoot = tempDir.resolve("cache");
        var launcher = new FakeProcessLauncher();
        var compiler = launcher.newCompilerWithPch(VERSION, cacheRoot);

        var first = compiler.compile(projectDir, includeDirs, sources, "probe", COptimizationLevel.DEBUG, TargetPlatform.getNativePlatform());

        assertTrue(first.success(), () -> "first round failed:\n" + first.buildLog());
        assertFalse(first.buildLog().contains("PCH unavailable"), () -> "no fallback may happen:\n" + first.buildLog());
        assertFalse(first.buildLog().contains("Command: "), () -> "success log carries no Command sections:\n" + first.buildLog());
        // Round one: pch build + probe + 4 TUs + link.
        assertEquals(7, launcher.recordedCommands().size(), () -> "commands: " + launcher.recordedCommands());
        assertEquals(1, pchBuildCount(launcher));
        assertEquals(1, probeCount(launcher));
        var firstTus = tuCommands(launcher.recordedCommands());
        assertEquals(4, firstTus.size());
        // Whitelist: Godot-binding consumers carry the pch; minicoro.c never does.
        for (var tuCommand : firstTus) {
            var shouldUsePch = !tuCommand.getLast().endsWith("minicoro.c");
            assertEquals(shouldUsePch, tuCommand.contains("-include-pch"), () -> "pch usage mismatch: " + tuCommand);
        }
        // The installed entry is complete, with the exact prefix header content.
        var keyDir = singleKeyDir(cacheRoot);
        var pchFile = keyDir.resolve("gdcc_godot_prefix.pch");
        assertTrue(Files.isRegularFile(keyDir.resolve(".ready")), "marker published last: " + keyDir);
        assertTrue(Files.isRegularFile(pchFile));
        assertEquals("#include <godot_binding.h>\n", Files.readString(keyDir.resolve("gdcc_godot_prefix.h")));
        var entryTu = firstTus.stream().filter(cmd -> cmd.getLast().endsWith("entry.c")).findFirst().orElseThrow();
        assertEquals(pchFile.toString(), entryTu.get(entryTu.indexOf("-include-pch") + 1), "-include-pch must point at the installed pch");
        var pchContent = Files.readAllBytes(pchFile);
        var pchMtime = Files.getLastModifiedTime(pchFile);

        var second = compiler.compile(projectDir, includeDirs, sources, "probe", COptimizationLevel.DEBUG, TargetPlatform.getNativePlatform());

        assertTrue(second.success(), () -> "second round failed:\n" + second.buildLog());
        // Round two reuses the entry: probe + 4 TUs + link, no rebuild.
        var roundTwo = launcher.recordedCommands().subList(7, launcher.recordedCommands().size());
        assertEquals(6, roundTwo.size(), () -> "round-two commands: " + roundTwo);
        assertEquals(1, pchBuildCount(launcher), "the installed entry must be reused, not rebuilt");
        assertEquals(2, probeCount(launcher), "every round probes the pch before use");
        assertArrayEquals(pchContent, Files.readAllBytes(pchFile), "reused pch is untouched");
        assertEquals(pchMtime, Files.getLastModifiedTime(pchFile));
        assertNoTemporaryLitter(keyDir);
    }

    @Test
    void pchBuildFailureDegradesTheRoundToNoPchWithTheFallbackLine(@TempDir Path tempDir) throws IOException {
        var projectDir = Files.createDirectories(tempDir.resolve("project"));
        var includeDirs = writeIncludeDirs(tempDir);
        var sources = writeTuSources(projectDir);
        var cacheRoot = tempDir.resolve("cache");
        // Only the PCH build fails (the scenario a broken header cannot produce: the prefix
        // header shares godot_binding.h with normal TUs, so a genuinely broken header would
        // fail those too); TUs and the link run normally.
        var launcher = new FakeProcessLauncher().failCommandsWithExitOne(ZigCcCompilerPchTest::isPchBuildCommand);
        var compiler = launcher.newCompilerWithPch(VERSION, cacheRoot);

        var result = compiler.compile(projectDir, includeDirs, sources, "probe", COptimizationLevel.DEBUG, TargetPlatform.getNativePlatform());

        assertTrue(result.success(), () -> "a PCH failure degrades, it never fails the build:\n" + result.buildLog());
        assertTrue(result.buildLog().startsWith("[gdcc] PCH unavailable this round: pch build failed (exit 1)\n"),
                () -> "the fixed fallback line leads the log:\n" + result.buildLog());
        assertFalse(result.buildLog().contains("Command: "), () -> "degraded success keeps the success log shape:\n" + result.buildLog());
        assertTrue(tuCommands(launcher.recordedCommands()).stream().noneMatch(cmd -> cmd.contains("-include-pch")),
                "a degraded round compiles every TU without the pch");
        assertEquals(1, pchBuildCount(launcher));
        assertEquals(0, probeCount(launcher), "a failed build never reaches the probe");
        assertTrue(Files.isRegularFile(result.artifacts().getFirst()));
        assertFalse(hasReadyEntry(cacheRoot), "no consumable entry may be published");
    }

    @Test
    void poisonedEntryWhoseRebuildAlsoFailsDegradesToNoPch(@TempDir Path tempDir) throws IOException {
        var projectDir = Files.createDirectories(tempDir.resolve("project"));
        var includeDirs = writeIncludeDirs(tempDir);
        var sources = writeTuSources(projectDir);
        var cacheRoot = tempDir.resolve("cache");
        var launcher = new FakeProcessLauncher();
        var compiler = launcher.newCompilerWithPch(VERSION, cacheRoot);
        assertTrue(compiler.compile(projectDir, includeDirs, sources, "probe", COptimizationLevel.DEBUG, TargetPlatform.getNativePlatform()).success());
        // Poison the installed entry: it stays complete on disk, but every PCH-touching
        // process now fails — the reuse probe fails (self-heal trigger), and the one allowed
        // rebuild fails too.
        launcher.failCommandsWithExitOne(cmd -> isPchBuildCommand(cmd) || isProbeCommand(cmd));

        var result = compiler.compile(projectDir, includeDirs, sources, "probe", COptimizationLevel.DEBUG, TargetPlatform.getNativePlatform());

        assertTrue(result.success(), () -> "even a failed self-heal must not fail the build:\n" + result.buildLog());
        assertTrue(result.buildLog().startsWith("[gdcc] PCH unavailable this round: installed pch failed the probe and the one allowed rebuild also failed"),
                () -> "the fallback line names the heal attempt:\n" + result.buildLog());
        var roundTwo = launcher.recordedCommands().subList(7, launcher.recordedCommands().size());
        assertTrue(tuCommands(roundTwo).stream().noneMatch(cmd -> cmd.contains("-include-pch")),
                () -> "the healed-then-failed round compiles without the pch: " + roundTwo);
        assertEquals(2, pchBuildCount(launcher), "the initial build plus the single allowed self-heal rebuild");
        assertEquals(2, probeCount(launcher), "the initial probe plus the failed reuse probe");
        assertFalse(hasReadyEntry(cacheRoot), "the poisoned entry is gone and the failed rebuild publishes nothing");
    }

    @Test
    void concurrentRoundsShareOneCacheRootWithoutCorruption(@TempDir Path tempDir) throws Exception {
        var includeDirs = writeIncludeDirs(tempDir);
        var cacheRoot = tempDir.resolve("cache");
        // Both PCH builds block until released below, so the two rounds are guaranteed to
        // overlap inside the PCH build/publish phase — the race is exercised deterministically,
        // not left to scheduler luck.
        var launchers = List.of(
                new FakeProcessLauncher().blockCommandsUntilDestroyed(ZigCcCompilerPchTest::isPchBuildCommand),
                new FakeProcessLauncher().blockCommandsUntilDestroyed(ZigCcCompilerPchTest::isPchBuildCommand));
        var results = Collections.synchronizedList(new ArrayList<CCompileResult>());
        var threads = new ArrayList<Thread>();
        // Two independent rounds in different project dirs (different per-project locks) race
        // on the same cache root; whichever installs first, the entries are interchangeable.
        for (var i = 0; i < 2; i++) {
            var projectDir = Files.createDirectories(tempDir.resolve("project-" + i));
            var sources = writeTuSources(projectDir);
            var compiler = launchers.get(i).newCompilerWithPch(VERSION, cacheRoot);
            threads.add(Thread.ofVirtual().start(() -> {
                try {
                    results.add(compiler.compile(projectDir, includeDirs, sources, "probe", COptimizationLevel.DEBUG, TargetPlatform.getNativePlatform()));
                } catch (IOException exception) {
                    throw new AssertionError("compile must surface failures through CCompileResult", exception);
                }
            }));
        }
        for (var launcher : launchers) {
            launcher.awaitFirstProcess();
        }
        // Both rounds are now parked inside their PCH build; release them together.
        for (var launcher : launchers) {
            launcher.startedProcesses().forEach(FakeProcess::destroyForcibly);
        }
        for (var thread : threads) {
            thread.join(java.time.Duration.ofSeconds(30));
            assertFalse(thread.isAlive(), "a racing round hung");
        }

        assertEquals(2, results.size());
        for (var result : results) {
            assertTrue(result.success(), () -> "a racing round failed:\n" + result.buildLog());
            assertFalse(result.buildLog().contains("PCH unavailable"), () -> "no fallback may happen:\n" + result.buildLog());
        }
        var keyDir = singleKeyDir(cacheRoot);
        assertTrue(Files.isRegularFile(keyDir.resolve(".ready")));
        assertNoTemporaryLitter(keyDir);
        var pchPath = keyDir.resolve("gdcc_godot_prefix.pch").toString();
        for (var launcher : launchers) {
            var entryTu = tuCommands(launcher.recordedCommands()).stream()
                    .filter(cmd -> cmd.getLast().endsWith("entry.c")).findFirst().orElseThrow();
            assertEquals(pchPath, entryTu.get(entryTu.indexOf("-include-pch") + 1),
                    "both rounds reference the single installed pch");
        }
    }

    @Test
    void tuRejectionOfPchRetriesTheWholeRoundWithoutPch(@TempDir Path tempDir) throws IOException {
        var projectDir = Files.createDirectories(tempDir.resolve("project"));
        var includeDirs = writeIncludeDirs(tempDir);
        var sources = writeTuSources(projectDir);
        var cacheRoot = tempDir.resolve("cache");
        // The probe passes, but the whitelisted entry TU then rejects the pch with a real
        // clang validation diagnostic (the exact phrasing recorded for mtime mismatches).
        var launcher = new FakeProcessLauncher()
                .outputProvider(cmd -> isPchTuRejection(cmd)
                        ? "fatal error: file 'godot_binding.h' has been modified since the precompiled header was built\n"
                        : "")
                .failCommandsWithExitOne(ZigCcCompilerPchTest::isPchTuRejection);
        var compiler = launcher.newCompilerWithPch(VERSION, cacheRoot);

        var result = compiler.compile(projectDir, includeDirs, sources, "probe", COptimizationLevel.DEBUG, TargetPlatform.getNativePlatform());

        assertTrue(result.success(), () -> "a pch rejection must retry without pch, never fail the build:\n" + result.buildLog());
        assertTrue(result.buildLog().startsWith("[gdcc] zig rejected the PCH during TU compilation"),
                () -> "the retry note leads the log:\n" + result.buildLog());
        var tus = tuCommands(launcher.recordedCommands());
        assertEquals(8, tus.size(), () -> "every TU compiled twice (pch attempt + no-pch retry): " + tus);
        assertEquals(3, tus.stream().filter(cmd -> cmd.contains("-include-pch")).count(),
                "only the first attempt's whitelisted TUs used the pch");
        assertTrue(tus.subList(4, 8).stream().noneMatch(cmd -> cmd.contains("-include-pch")),
                "the retry recompiled every TU without the pch (PCH/no-PCH objects never mix)");
        assertTrue(Files.isRegularFile(result.artifacts().getFirst()));
    }

    /// The whitelisted entry TU of the pch attempt, failing with the rejection diagnostic.
    private static boolean isPchTuRejection(List<String> cmd) {
        return cmd.getLast().endsWith("entry.c") && cmd.contains("-include-pch");
    }

    @Test
    void interruptDuringPchBuildDestroysTheChildAndStartsNoTu(@TempDir Path tempDir) throws Exception {
        var projectDir = Files.createDirectories(tempDir.resolve("project"));
        var includeDirs = writeIncludeDirs(tempDir);
        var sources = writeTuSources(projectDir);
        var launcher = new FakeProcessLauncher().blockCommandsUntilDestroyed(ZigCcCompilerPchTest::isPchBuildCommand);
        var compiler = launcher.newCompilerWithPch(VERSION, tempDir.resolve("cache"));
        var resultRef = new AtomicReference<CCompileResult>();
        var interruptRestored = new AtomicBoolean();
        var compileThread = Thread.ofVirtual().name("gdcc-test-compile").start(() -> {
            try {
                var result = compiler.compile(projectDir, includeDirs, sources, "probe", COptimizationLevel.DEBUG, TargetPlatform.getNativePlatform());
                interruptRestored.set(Thread.currentThread().isInterrupted());
                resultRef.set(result);
            } catch (IOException exception) {
                throw new AssertionError("compile must surface failures through CCompileResult", exception);
            }
        });

        // The version probe is a fixed-value lambda here, so the PCH build is the first child.
        assertTrue(launcher.awaitFirstProcess(), "the PCH build must be running before the cancel");
        compileThread.interrupt();
        compileThread.join(java.time.Duration.ofSeconds(30));
        assertFalse(compileThread.isAlive(), "compile must return promptly once the child is destroyed");

        var result = resultRef.get();
        assertFalse(result.success());
        assertEquals("Failed to run zig: interrupted", result.buildLog(), "a cancel during the PCH phase uses the interrupted channel, never a PCH fallback");
        assertTrue(result.artifacts().isEmpty());
        assertTrue(interruptRestored.get());
        assertTrue(launcher.startedProcesses().stream().allMatch(FakeProcess::destroyForciblyCalled),
                "the started PCH child must be forcibly destroyed");
        assertTrue(tuCommands(launcher.recordedCommands()).isEmpty(), "no TU may start after a cancelled PCH phase");
        assertEquals(0, launcher.countCommandsContaining("-shared"), "cancel never reaches the link");
    }

    private static boolean isPchBuildCommand(List<String> cmd) {
        return cmd.contains("-x") && cmd.contains("c-header");
    }

    private static boolean isProbeCommand(List<String> cmd) {
        return cmd.stream().anyMatch(arg -> arg.contains("gdcc_pch_probe_"));
    }

    private static long pchBuildCount(FakeProcessLauncher launcher) {
        return launcher.recordedCommands().stream().filter(ZigCcCompilerPchTest::isPchBuildCommand).count();
    }

    private static long probeCount(FakeProcessLauncher launcher) {
        return launcher.recordedCommands().stream().filter(ZigCcCompilerPchTest::isProbeCommand).count();
    }

    /// TU compile commands, excluding the PCH build (header mode) and the PCH probe (whose
    /// source name carries the probe prefix).
    private static List<List<String>> tuCommands(List<List<String>> commands) {
        return commands.stream()
                .filter(cmd -> cmd.contains("-c") && !isPchBuildCommand(cmd) && !isProbeCommand(cmd))
                .toList();
    }

    /// The single key directory under `<cacheRoot>/pch/` — exactly one entry must exist.
    private static Path singleKeyDir(Path cacheRoot) throws IOException {
        try (var entries = Files.list(cacheRoot.resolve("pch"))) {
            var keyDirs = entries.filter(Files::isDirectory).toList();
            assertEquals(1, keyDirs.size(), () -> "expected exactly one pch key dir under " + cacheRoot + ": " + keyDirs);
            return keyDirs.getFirst();
        }
    }

    private static boolean hasReadyEntry(Path cacheRoot) throws IOException {
        var pchRoot = cacheRoot.resolve("pch");
        if (!Files.isDirectory(pchRoot)) {
            return false;
        }
        try (var walk = Files.walk(pchRoot)) {
            return walk.anyMatch(path -> path.getFileName().toString().equals(".ready"));
        }
    }

    private static void assertNoTemporaryLitter(Path keyDir) throws IOException {
        try (var entries = Files.list(keyDir)) {
            var litter = entries.filter(path -> {
                var name = path.getFileName().toString();
                return name.contains(".tmp-") || name.startsWith("gdcc_pch_probe_");
            }).toList();
            assertEquals(List.of(), litter, () -> "temporary build/probe files must not survive the round in " + keyDir);
        }
    }

    /// Minimal stand-in for the production `[<root>/gdcc, <root>/godot]` include pair; the
    /// content only feeds the cache-key tree hash.
    private static List<Path> writeIncludeDirs(Path tempDir) throws IOException {
        var gdcc = tempDir.resolve("inc/gdcc");
        var godot = tempDir.resolve("inc/godot");
        Files.createDirectories(gdcc);
        Files.createDirectories(godot);
        Files.writeString(godot.resolve("godot_binding.h"), "#pragma once\n");
        Files.writeString(gdcc.resolve("gdcc_helper.h"), "#pragma once\n#include <godot_binding.h>\n");
        return List.of(gdcc, godot);
    }

    private static List<Path> writeTuSources(Path dir) throws IOException {
        var sources = new ArrayList<Path>();
        var index = 0;
        for (var fileName : TU_NAMES) {
            var source = dir.resolve(fileName);
            Files.writeString(source, "int gdcc_probe_%d(void) { return %d; }\n".formatted(index, index));
            sources.add(source);
            index++;
        }
        return sources;
    }
}
