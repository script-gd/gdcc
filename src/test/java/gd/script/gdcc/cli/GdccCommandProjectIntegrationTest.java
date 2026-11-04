package gd.script.gdcc.cli;

import gd.script.gdcc.api.ModuleSnapshot;
import gd.script.gdcc.api.VfsEntrySnapshot;
import gd.script.gdcc.backend.c.build.CCompilerKind;
import gd.script.gdcc.backend.c.build.COptimizationLevel;
import gd.script.gdcc.cli.CliCompileTestSupport.Terminal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.jetbrains.annotations.NotNull;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Project-mode end-to-end coverage: `gdcc <project.godot> [x.gd ...]` collects auto-discovered
/// `.gd3` plus explicit `.gd` into one module compile, with project settings merged under
/// explicit CLI options.
class GdccCommandProjectIntegrationTest {
    @Test
    void projectEntryCompilesDiscoveredGd3IntoDefaultWorkDirectory(@TempDir Path tempDir) throws IOException {
        var projectFile = writeProject(tempDir.resolve("game"), "AlphaGame", "");
        writeFile(tempDir.resolve("game/a.gd3"), validSource("Alpha"));
        writeFile(tempDir.resolve("game/nested/b.gd3"), validSource("Beta"));
        var terminal = new Terminal();

        var exitCode = terminal.command().commandLine().execute(projectFile.toString());

        assertEquals(0, exitCode);
        var moduleId = singleModuleId(terminal);
        var workDir = tempDir.resolve("game/.godot/gdcc/cli").resolve(moduleId).toRealPath();
        assertEquals(workDir, terminal.api.getCompileOptions(moduleId).projectPath());

        assertEntry(terminal, moduleId, "/src/a.gd3", "res://a.gd3");
        assertEntry(terminal, moduleId, "/src/nested/b.gd3", "res://nested/b.gd3");
        var result = CliCompileTestSupport.awaitLastResult(terminal.api, moduleId);
        assertEquals(List.of("res://a.gd3", "res://nested/b.gd3"), result.sourcePaths());
    }

    @Test
    void explicitGdJoinsGd3InOneModuleAndCrossReferencesCompile(@TempDir Path tempDir) throws IOException {
        var projectFile = writeProject(tempDir.resolve("game"), "Game", "");
        writeFile(tempDir.resolve("game/shared.gd3"), """
                class_name SharedBox
                extends RefCounted

                func value() -> int:
                    return 3
                """);
        var helper = writeFile(tempDir.resolve("game/helper.gd"), """
                extends SharedBox

                func double() -> int:
                    return value() * 2
                """);
        var terminal = new Terminal();

        var exitCode = terminal.command().commandLine().execute(
                "-o", tempDir.resolve("build/demo").toString(),
                projectFile.toString(),
                helper.toString()
        );

        assertEquals(0, exitCode, terminal.errText());
        assertEntry(terminal, "demo", "/src/shared.gd3", "res://shared.gd3");
        assertEntry(terminal, "demo", "/src/helper.gd", "res://helper.gd");
        var result = CliCompileTestSupport.awaitLastResult(terminal.api, "demo");
        assertEquals(List.of("res://helper.gd", "res://shared.gd3"), result.sourcePaths());
    }

    @Test
    void unlistedGdWithSyntaxErrorsNeverEntersTheModule(@TempDir Path tempDir) throws IOException {
        var projectFile = writeProject(tempDir.resolve("game"), "Game", "");
        writeFile(tempDir.resolve("game/ok.gd3"), validSource("Ok"));
        writeFile(tempDir.resolve("game/broken.gd"), "this is not gdscript (((\n");
        var terminal = new Terminal();

        var exitCode = terminal.command().commandLine().execute(
                "-o", tempDir.resolve("build/demo").toString(),
                projectFile.toString()
        );

        // The unspecified .gd would fail the frontend had it been collected; success proves it
        // stayed outside both the VFS and the compile task.
        assertEquals(0, exitCode, terminal.errText());
        assertEntry(terminal, "demo", "/src/ok.gd3", "res://ok.gd3");
        assertEquals(List.of("ok.gd3"), terminal.api.listDirectory("demo", "/src").stream()
                .map(VfsEntrySnapshot::name).toList());
    }

    @Test
    void projectSettingsApplyAndExplicitCliOptionsOverride(@TempDir Path tempDir) throws IOException {
        var projectFile = writeProject(tempDir.resolve("game"), "Game", """
                [gdcc]
                build/optimization_level="RELEASE"
                build/strict_mode=true
                build/c_compiler_kind="ZIG_CC"
                """);
        writeFile(tempDir.resolve("game/a.gd3"), validSource("A"));
        var inherited = new Terminal();
        var overridden = new Terminal();

        var inheritedExit = inherited.command().commandLine().execute(
                "-o", tempDir.resolve("build/inherit").toString(),
                projectFile.toString());
        var overriddenExit = overridden.command().commandLine().execute(
                "--opt", "debug", "--backend", "tcc",
                "-o", tempDir.resolve("build/explicit").toString(),
                projectFile.toString());

        assertEquals(0, inheritedExit);
        var inheritedOptions = inherited.api.getCompileOptions("inherit");
        assertEquals(COptimizationLevel.RELEASE, inheritedOptions.optimizationLevel());
        assertEquals(true, inheritedOptions.strictMode());
        assertEquals(CCompilerKind.ZIG_CC, inheritedOptions.cCompilerKind());

        assertEquals(0, overriddenExit);
        var overriddenOptions = overridden.api.getCompileOptions("explicit");
        assertEquals(COptimizationLevel.DEBUG, overriddenOptions.optimizationLevel());
        assertEquals(true, overriddenOptions.strictMode());
        assertEquals(CCompilerKind.TINY_CC, overriddenOptions.cCompilerKind());
    }

    @Test
    void explicitGdOnlyWithoutAnyGd3IsALegalSourceSet(@TempDir Path tempDir) throws IOException {
        var projectFile = writeProject(tempDir.resolve("game"), "Game", "");
        var helper = writeFile(tempDir.resolve("game/helper.gd"), validSource("Helper"));
        var terminal = new Terminal();

        var exitCode = terminal.command().commandLine().execute(
                "-o", tempDir.resolve("build/demo").toString(),
                projectFile.toString(), helper.toString()
        );

        assertEquals(0, exitCode, terminal.errText());
        assertEntry(terminal, "demo", "/src/helper.gd", "res://helper.gd");
    }

    @Test
    void multipleProjectPositionalsFailBeforeModuleCreation(@TempDir Path tempDir) throws IOException {
        var first = writeProject(tempDir.resolve("one"), "One", "");
        var second = writeProject(tempDir.resolve("two"), "Two", "");
        var terminal = new Terminal();

        var exitCode = terminal.command().commandLine().execute(first.toString(), second.toString());

        assertEquals(GdccCommand.EXIT_USAGE, exitCode);
        assertTrue(terminal.errText().contains("Only one project.godot"), terminal.errText());
        terminal.assertCompilerNotInvoked();
        assertTrue(terminal.api.listModules().isEmpty());
    }

    @Test
    void nonGdCompanionArgumentFailsBeforeModuleCreation(@TempDir Path tempDir) throws IOException {
        var projectFile = writeProject(tempDir.resolve("game"), "Game", "");
        writeFile(tempDir.resolve("game/a.gd3"), validSource("A"));
        var gd3Arg = writeFile(tempDir.resolve("game/b.gd3"), validSource("B"));
        var terminal = new Terminal();

        var exitCode = terminal.command().commandLine().execute(
                projectFile.toString(), gd3Arg.toString());

        assertEquals(GdccCommand.EXIT_USAGE, exitCode);
        assertTrue(terminal.errText().contains("only .gd"), terminal.errText());
        terminal.assertCompilerNotInvoked();
        assertTrue(terminal.api.listModules().isEmpty());
    }

    @Test
    void badProjectConfigFailsBeforeModuleCreation(@TempDir Path tempDir) throws IOException {
        var projectFile = writeProject(tempDir.resolve("game"), "Game", """
                [gdcc]
                build/optimization_level="TURBO"
                """);
        writeFile(tempDir.resolve("game/a.gd3"), validSource("A"));
        var terminal = new Terminal();

        var exitCode = terminal.command().commandLine().execute(projectFile.toString());

        assertEquals(GdccCommand.EXIT_USAGE, exitCode);
        assertTrue(terminal.errText().contains("optimization_level"), terminal.errText());
        terminal.assertCompilerNotInvoked();
        assertTrue(terminal.api.listModules().isEmpty());
    }

    @Test
    void missingProjectFileFailsBeforeModuleCreation(@TempDir Path tempDir) throws IOException {
        var terminal = new Terminal();

        var exitCode = terminal.command().commandLine().execute(
                tempDir.resolve("game/project.godot").toString());

        assertEquals(GdccCommand.EXIT_USAGE, exitCode);
        assertTrue(terminal.errText().contains("does not exist"), terminal.errText());
        terminal.assertCompilerNotInvoked();
        assertTrue(terminal.api.listModules().isEmpty());
    }

    @Test
    void emptyProjectSourceSetFailsBeforeModuleCreation(@TempDir Path tempDir) throws IOException {
        var projectFile = writeProject(tempDir.resolve("game"), "Game", "");
        var terminal = new Terminal();

        var exitCode = terminal.command().commandLine().execute(projectFile.toString());

        assertEquals(GdccCommand.EXIT_USAGE, exitCode);
        assertTrue(terminal.errText().contains("no sources to compile"), terminal.errText());
        terminal.assertCompilerNotInvoked();
        assertTrue(terminal.api.listModules().isEmpty());
    }

    @Test
    void outputInsideProjectResourceTreeFailsBeforeModuleCreation(@TempDir Path tempDir) throws IOException {
        var projectFile = writeProject(tempDir.resolve("game"), "Game", "");
        writeFile(tempDir.resolve("game/a.gd3"), validSource("A"));
        var terminal = new Terminal();

        var exitCode = terminal.command().commandLine().execute(
                "-o", tempDir.resolve("game/build").toString(),
                projectFile.toString());

        assertEquals(GdccCommand.EXIT_USAGE, exitCode);
        assertTrue(terminal.errText().contains("outside the project resource tree"), terminal.errText());
        terminal.assertCompilerNotInvoked();
        assertTrue(terminal.api.listModules().isEmpty());
    }

    @Test
    void outputCollidingWithExistingFileIsUsageErrorBeforeModule(@TempDir Path tempDir)
            throws IOException {
        var projectFile = writeProject(tempDir.resolve("game"), "Game", "");
        writeFile(tempDir.resolve("game/a.gd3"), validSource("A"));
        var occupied = writeFile(tempDir.resolve("occupied"), "x\n");
        var terminal = new Terminal();

        var exitCode = terminal.command().commandLine().execute(
                "-o", occupied.toString(), projectFile.toString());

        assertEquals(GdccCommand.EXIT_USAGE, exitCode);
        assertTrue(terminal.errText().contains("non-directory"), terminal.errText());
        terminal.assertCompilerNotInvoked();
        assertTrue(terminal.api.listModules().isEmpty());
    }

    @Test
    void mismatchedProjectOptionFailsWhileMatchIsAccepted(@TempDir Path tempDir) throws IOException {
        var projectFile = writeProject(tempDir.resolve("game"), "Game", "");
        var otherProject = writeProject(tempDir.resolve("other"), "Other", "");
        writeFile(tempDir.resolve("game/a.gd3"), validSource("A"));
        var mismatched = new Terminal();
        var matched = new Terminal();

        var mismatchedExit = mismatched.command().commandLine().execute(
                "--project", otherProject.toString(),
                "-o", tempDir.resolve("build/mismatch").toString(),
                projectFile.toString());
        var matchedExit = matched.command().commandLine().execute(
                "--project", projectFile.toString(),
                "-o", tempDir.resolve("build/match").toString(),
                projectFile.toString());

        assertEquals(GdccCommand.EXIT_USAGE, mismatchedExit);
        assertTrue(mismatched.errText().contains("same project.godot"), mismatched.errText());
        assertTrue(mismatched.api.listModules().isEmpty());

        // Matching --project is accepted but emits no sidecar .gdextension in project mode.
        assertEquals(0, matchedExit, matched.errText());
        assertFalse(Files.exists(tempDir.resolve("build/match/match.gdextension")));
        assertFalse(matched.outText().contains("Metadata:"), matched.outText());
    }

    @Test
    void frontendFailureInCollectedSourcesIsCompileFailureNotUsageError(@TempDir Path tempDir) throws IOException {
        var projectFile = writeProject(tempDir.resolve("game"), "Game", "");
        writeFile(tempDir.resolve("game/broken.gd3"), "this is not gdscript (((\n");
        var terminal = new Terminal();

        var exitCode = terminal.command().commandLine().execute(
                "-o", tempDir.resolve("build/demo").toString(),
                projectFile.toString());

        assertEquals(GdccCommand.EXIT_COMPILE_FAILED, exitCode);
        assertTrue(terminal.errText().contains("Compile failed"), terminal.errText());
        assertEquals(List.of("demo"), terminal.api.listModules().stream().map(ModuleSnapshot::moduleId).toList());
    }

    // ------------------------------------------------------------------ publish

    /// The publish contract end-to-end: one module, one task, library named by
    /// `<name>_<platform>_<level>_<arch>-<hash16>`, descriptor under `bin/` and no sidecar
    /// `.gdextension` next to the build output.
    @Test
    void successfulProjectBuildPublishesLibraryAndDescriptor(@TempDir Path tempDir) throws IOException {
        var projectFile = writeProject(tempDir.resolve("game"), "AlphaGame", "");
        writeFile(tempDir.resolve("game/a.gd3"), validSource("Alpha"));
        var workDir = tempDir.resolve("build/demo");
        var terminal = new Terminal();

        var exitCode = terminal.command().commandLine().execute(
                "-o", workDir.toString(), projectFile.toString());

        assertEquals(0, exitCode, terminal.errText());
        var binDir = tempDir.resolve("game/bin");
        var descriptorPath = binDir.resolve("gdcc.gdextension");
        var libraries = Files.list(binDir)
                .filter(p -> p.getFileName().toString().endsWith(".so"))
                .map(p -> p.getFileName().toString())
                .toList();
        assertEquals(1, libraries.size(), "exactly one published library expected");
        var libName = libraries.getFirst();
        assertTrue(
                libName.matches("libAlphaGame_linux_debug_x86_64-[0-9a-f]{16}\\.so"),
                "unexpected library name: " + libName
        );
        assertEquals("dummy", Files.readString(binDir.resolve(libName)));

        var descriptor = GodotConfigFile.parse(Files.readString(descriptorPath), descriptorPath.toString());
        var managed = descriptor.findEntry("configuration", "gdcc_managed").orElseThrow().value();
        assertTrue(assertInstanceOf(GodotConfigFile.BoolValue.class, managed).flag());
        var entrySymbol = descriptor.findEntry("configuration", "entry_symbol").orElseThrow().value();
        assertEquals("gdextension_entry", assertInstanceOf(GodotConfigFile.StringValue.class, entrySymbol).text());
        var linux = descriptor.findEntry("libraries", "linux.x86_64").orElseThrow().value();
        assertEquals("res://bin/" + libName, assertInstanceOf(GodotConfigFile.StringValue.class, linux).text());
        var owned = descriptor.findEntry("configuration", "gdcc_libraries").orElseThrow().value();
        assertEquals(List.of("res://bin/" + libName),
                assertInstanceOf(GodotConfigFile.StringArrayValue.class, owned).items());

        assertTrue(terminal.outText().contains("Published: " + descriptorPath), terminal.outText());
        assertFalse(Files.exists(workDir.resolve("demo.gdextension")));
        assertEquals(1, terminal.api.listModules().size());
    }

    /// Debug then release target the same `platform.arch` key; publishing twice replaces the
    /// mapping while the earlier library file is left in `bin/`.
    @Test
    void debugThenReleaseRepublishUpdatesTheCanonicalKey(@TempDir Path tempDir) throws IOException {
        var projectFile = writeProject(tempDir.resolve("game"), "Game", "");
        writeFile(tempDir.resolve("game/a.gd3"), validSource("A"));
        var binDir = tempDir.resolve("game/bin");
        var first = new Terminal();
        var second = new Terminal();

        assertEquals(0, first.command().commandLine().execute(
                "-o", tempDir.resolve("build/one").toString(), projectFile.toString()));
        assertEquals(0, second.command().commandLine().execute(
                "--opt", "release",
                "-o", tempDir.resolve("build/two").toString(), projectFile.toString()));

        var descriptor = GodotConfigFile.parse(
                Files.readString(binDir.resolve("gdcc.gdextension")), "gdcc.gdextension");
        var mapping = assertInstanceOf(GodotConfigFile.StringValue.class,
                descriptor.findEntry("libraries", "linux.x86_64").orElseThrow().value()).text();
        assertTrue(mapping.contains("linux_release_x86_64-"), mapping);
        assertEquals(2, Files.list(binDir).filter(p -> p.getFileName().toString().endsWith(".so")).count());
    }

    @Test
    void unmanagedDescriptorFailsBeforeModuleCreationAndStaysUntouched(@TempDir Path tempDir) throws IOException {
        var projectFile = writeProject(tempDir.resolve("game"), "Game", "");
        writeFile(tempDir.resolve("game/a.gd3"), validSource("A"));
        var descriptorPath = writeFile(
                tempDir.resolve("game/bin/gdcc.gdextension"),
                "[configuration]\n\nentry_symbol=\"gdextension_entry\"\n");
        var original = Files.readString(descriptorPath);
        var terminal = new Terminal();

        var exitCode = terminal.command().commandLine().execute(projectFile.toString());

        assertEquals(GdccCommand.EXIT_USAGE, exitCode);
        assertTrue(terminal.errText().contains("unmanaged"), terminal.errText());
        assertEquals(original, Files.readString(descriptorPath));
        terminal.assertCompilerNotInvoked();
        assertTrue(terminal.api.listModules().isEmpty());
    }

    // ------------------------------------------------------------------ locking

    /// Same-JVM acquisition reports contention via OverlappingFileLockException, which is exactly
    /// how a second CLI run must fail — before the module exists.
    @Test
    void projectLockContentionFailsBeforeModuleCreation(@TempDir Path tempDir) throws IOException {
        var projectFile = writeProject(tempDir.resolve("game"), "Game", "");
        writeFile(tempDir.resolve("game/a.gd3"), validSource("A"));
        var projectLock = tempDir.resolve("game/.godot/gdcc/cli/publish.lock");
        var terminal = new Terminal();

        try (var ignored = gd.script.gdcc.util.FileLockGuard.tryAcquire(projectLock)) {
            var exitCode = terminal.command().commandLine().execute(
                    "-o", tempDir.resolve("build/demo").toString(), projectFile.toString());

            assertEquals(GdccCommand.EXIT_USAGE, exitCode);
            assertTrue(terminal.errText().contains("already being published"), terminal.errText());
            terminal.assertCompilerNotInvoked();
            assertTrue(terminal.api.listModules().isEmpty());
        }
    }

    @Test
    void outputLockContentionFailsBeforeModuleCreation(@TempDir Path tempDir) throws IOException {
        var projectFile = writeProject(tempDir.resolve("game"), "Game", "");
        writeFile(tempDir.resolve("game/a.gd3"), validSource("A"));
        var workDir = tempDir.resolve("build/demo");
        Files.createDirectories(workDir);
        var terminal = new Terminal();

        try (var ignored = gd.script.gdcc.util.FileLockGuard.tryAcquire(workDir.resolve(".gdcc.lock"))) {
            var exitCode = terminal.command().commandLine().execute(
                    "-o", workDir.toString(), projectFile.toString());

            assertEquals(GdccCommand.EXIT_USAGE, exitCode);
            assertTrue(terminal.errText().contains("locked by another gdcc run"), terminal.errText());
            terminal.assertCompilerNotInvoked();
            assertTrue(terminal.api.listModules().isEmpty());
        }
    }

    /// Real two-process mutual exclusion: a separate JVM holding the project lock must block a
    /// run using a different `-o` for the same project, and an output-lock holder must block a
    /// run reusing that directory.
    @Test
    void locksAreExclusiveAcrossProcesses(@TempDir Path tempDir) throws Exception {
        var projectFile = writeProject(tempDir.resolve("game"), "Game", "");
        writeFile(tempDir.resolve("game/a.gd3"), validSource("A"));

        // Case 1: project lock held by another process; this run uses a different -o.
        var projectLock = tempDir.resolve("game/.godot/gdcc/cli/publish.lock");
        var holder = LockHolderProcess.spawn(projectLock);
        try {
            assertEquals(LockHolderProcess.ACQUIRED_MARKER, LockHolderProcess.awaitMarker(holder));
            var terminal = new Terminal();
            var exitCode = terminal.command().commandLine().execute(
                    "-o", tempDir.resolve("build/other").toString(), projectFile.toString());
            assertEquals(GdccCommand.EXIT_USAGE, exitCode, terminal.errText());
            terminal.assertCompilerNotInvoked();
        } finally {
            holder.destroyForcibly();
            holder.waitFor();
        }

        // Case 2: output lock held by another process on the very -o this run wants.
        var workDir = tempDir.resolve("build/demo");
        Files.createDirectories(workDir);
        var outputHolder = LockHolderProcess.spawn(workDir.resolve(".gdcc.lock"));
        try {
            assertEquals(LockHolderProcess.ACQUIRED_MARKER, LockHolderProcess.awaitMarker(outputHolder));
            var terminal = new Terminal();
            var exitCode = terminal.command().commandLine().execute(
                    "-o", workDir.toString(), projectFile.toString());
            assertEquals(GdccCommand.EXIT_USAGE, exitCode, terminal.errText());
            terminal.assertCompilerNotInvoked();
        } finally {
            outputHolder.destroyForcibly();
            outputHolder.waitFor();
        }
    }

    /// Interrupting a run requests cancellation but the locks stay held until the task reaches a
    /// terminal state — only then may the next run take them.
    @Test
    void cancellationKeepsLocksUntilTheTaskTerminates(@TempDir Path tempDir) throws Exception {
        var projectFile = writeProject(tempDir.resolve("game"), "Game", "");
        writeFile(tempDir.resolve("game/a.gd3"), validSource("A"));
        var compiler = CliCompileTestSupport.TestCompiler.blockingSuccessUninterruptible();
        var terminal = new Terminal(compiler);
        var projectLock = tempDir.resolve("game/.godot/gdcc/cli/publish.lock");
        var workDir = tempDir.resolve("build/demo");

        var exitCode = new java.util.concurrent.atomic.AtomicInteger(-1);
        var run = new Thread(() ->
                exitCode.set(terminal.command().commandLine().execute(
                        "-o", workDir.toString(), projectFile.toString())));
        run.start();
        try {
            assertTrue(compiler.awaitEntered(), "compiler did not start");

            run.interrupt();
            // Wait until the run thread actually consumed the interrupt — the flag disappearing
            // means cancellation was requested and the CLI is now in the terminal-state wait,
            // the window a premature lock release would have to live in.
            var deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            while (run.isInterrupted() && System.nanoTime() < deadline) {
                Thread.sleep(1);
            }
            assertFalse(run.isInterrupted(), "run thread never consumed the interrupt");

            // While the cancelled-but-still-running task has not terminated, both locks must
            // stay held — probe continuously so a release-and-regrab window cannot slip past.
            var holdDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(300);
            while (System.nanoTime() < holdDeadline) {
                try (var probe = gd.script.gdcc.util.FileLockGuard.tryAcquire(projectLock)) {
                    assertNull(probe, "project lock was released while the cancelled task still runs");
                }
                try (var probe = gd.script.gdcc.util.FileLockGuard.tryAcquire(workDir.resolve(".gdcc.lock"))) {
                    assertNull(probe, "output lock was released while the cancelled task still runs");
                }
                Thread.sleep(2);
            }
        } finally {
            compiler.release();
        }

        run.join(java.util.concurrent.TimeUnit.SECONDS.toMillis(30));
        assertFalse(run.isAlive(), "run thread did not finish after compiler release");
        assertEquals(GdccCommand.EXIT_INTERRUPTED, exitCode.get());

        try (var reacquired = gd.script.gdcc.util.FileLockGuard.tryAcquire(projectLock)) {
            assertNotNull(reacquired);
        }
        try (var reacquired = gd.script.gdcc.util.FileLockGuard.tryAcquire(workDir.resolve(".gdcc.lock"))) {
            assertNotNull(reacquired);
        }
    }

    /// An interrupt landing mid-publication (after the library install, before the descriptor
    /// commit) is a cancellation, not a publish failure: the run exits 130 and the descriptor is
    /// never committed. The barrier proves the publish path was actually reached; an
    /// unreferenced library may legitimately be left installed.
    @Test
    void interruptionAtPublishTimeReturns130WithoutCommit(@TempDir Path tempDir) throws Exception {
        var projectFile = writeProject(tempDir.resolve("game"), "Game", "");
        writeFile(tempDir.resolve("game/a.gd3"), validSource("A"));
        var barrierReached = new java.util.concurrent.CountDownLatch(1);
        var releasePublish = new java.util.concurrent.CountDownLatch(1);
        var previousBarrier = GodotProjectPublisher.midPublishBarrier;
        GodotProjectPublisher.midPublishBarrier = () -> {
            barrierReached.countDown();
            try {
                releasePublish.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        var terminal = new Terminal(CliCompileTestSupport.TestCompiler.succeeding());
        var exitCode = new java.util.concurrent.atomic.AtomicInteger(-1);

        var run = new Thread(() -> exitCode.set(terminal.command().commandLine().execute(
                "-o", tempDir.resolve("build/demo").toString(), projectFile.toString())));
        try {
            run.start();
            assertTrue(barrierReached.await(30, java.util.concurrent.TimeUnit.SECONDS),
                    "publish never reached the mid-publication barrier");
            run.interrupt();
            releasePublish.countDown();
            run.join(java.util.concurrent.TimeUnit.SECONDS.toMillis(30));

            assertFalse(run.isAlive(), "run thread did not finish");
            assertEquals(GdccCommand.EXIT_INTERRUPTED, exitCode.get());
            assertFalse(Files.exists(tempDir.resolve("game/bin/gdcc.gdextension")));
        } finally {
            GodotProjectPublisher.midPublishBarrier = previousBarrier;
            releasePublish.countDown();
            run.join(java.util.concurrent.TimeUnit.SECONDS.toMillis(5));
        }
    }

    private static void assertEntry(Terminal terminal, String moduleId, String virtualPath, String displayPath) {
        var entry = org.junit.jupiter.api.Assertions.assertInstanceOf(
                VfsEntrySnapshot.FileEntrySnapshot.class, terminal.api.readEntry(moduleId, virtualPath));
        assertEquals(virtualPath, entry.virtualPath());
        assertEquals(displayPath, entry.path());
        assertFalse(terminal.api.readFile(moduleId, virtualPath).isBlank());
    }

    private static String singleModuleId(Terminal terminal) {
        var modules = terminal.api.listModules();
        assertEquals(1, modules.size());
        return modules.getFirst().moduleId();
    }

    private static Path writeProject(Path root, String name, String extra) throws IOException {
        var projectFile = root.resolve("project.godot");
        Files.createDirectories(projectFile.getParent());
        Files.writeString(projectFile,
                "config_version=5\n\n[application]\nconfig/name=\"" + name + "\"\n" + extra,
                StandardCharsets.UTF_8);
        return projectFile;
    }

    private static Path writeFile(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        return Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    private static String validSource(String className) {
        return """
                class_name %s
                extends RefCounted

                func value() -> int:
                    return 3
                """.formatted(className);
    }
}
