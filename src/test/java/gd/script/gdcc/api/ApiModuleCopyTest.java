package gd.script.gdcc.api;

import gd.script.gdcc.exception.ApiModuleAlreadyExistsException;
import gd.script.gdcc.exception.ApiModuleNotFoundException;
import gd.script.gdcc.exception.ApiPathNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// `API.copyModule` contract: a copy is a point-in-time snapshot that shares immutable VFS
/// leaves with its source, carries options and the class-name map, and resets compile runtime
/// state. Positive anchors prove fidelity and independent compilability; negative anchors pin
/// the error surface and post-copy isolation in both directions.
class ApiModuleCopyTest {
    @Test
    void copyModuleOverridesNameWithoutChangingSource() {
        try (var api = new API()) {
            api.createModule("diag", "Diagnostics");
            api.putFile("diag", "/src/main.gd", "extends Node\n");

            var copy = api.copyModule("diag", "private-build", "  My Game  ");

            assertEquals("private-build", copy.moduleId());
            assertEquals("My Game", copy.moduleName());
            assertEquals("Diagnostics", api.getModule("diag").moduleName());
            assertEquals("extends Node\n", api.readFile("private-build", "/src/main.gd"));
            assertEquals("Diagnostics", api.copyModule("diag", "inherited", null).moduleName());
            assertThrows(IllegalArgumentException.class, () -> api.copyModule("diag", "bad", "  "));
            assertEquals(List.of("diag", "inherited", "private-build"),
                    api.listModules().stream().map(ModuleSnapshot::moduleId).toList());
        }
    }

    @Test
    void copyModuleCopiesVfsOptionsAndClassMapVerbatim() {
        // The fixed clock makes `updatedAt` deterministic so entry-snapshot equality covers the
        // full file metadata (content, display/absolute paths, byte count, timestamp).
        var api = new API(ApiCompileTestSupport.FIXED_CLOCK);
        api.createModule("src", "Source Module");
        api.putFile(
                "src",
                "/src/main.gd",
                "class_name CopyMain\nextends Node\n",
                "res://main.gd",
                "E:/proj/main.gd"
        );
        api.putFile("src", "/src/util/helper.gd", "extends RefCounted\n");
        api.createLink("src", "/out", VfsEntrySnapshot.LinkKind.LOCAL, "E:/proj/out");
        var options = ApiCompileTestSupport.compileOptions(Path.of("E:/proj"));
        api.setCompileOptions("src", options);
        api.setTopLevelCanonicalNameMap("src", Map.of("CopyMain", "game.CopyMain"));

        var snapshot = api.copyModule(" src ", "copy");

        assertEquals("copy", snapshot.moduleId());
        assertEquals("Source Module", snapshot.moduleName());
        assertEquals(options, snapshot.compileOptions());
        assertEquals(Map.of("CopyMain", "game.CopyMain"), snapshot.topLevelCanonicalNameMap());
        assertFalse(snapshot.hasLastCompileResult());
        // Root children: the `/src` directory and the `/out` link.
        assertEquals(2, snapshot.rootEntryCount());
        assertThrows(
                UnsupportedOperationException.class,
                () -> snapshot.topLevelCanonicalNameMap().put("Other", "game.Other")
        );

        // Entry snapshots are records, so equality pins every metadata field at once.
        assertEquals(api.readEntry("src", "/src/main.gd"), api.readEntry("copy", "/src/main.gd"));
        assertEquals(api.readEntry("src", "/out"), api.readEntry("copy", "/out"));
        assertEquals(
                api.readFile("src", "/src/util/helper.gd"),
                api.readFile("copy", "/src/util/helper.gd")
        );
        assertEquals(1, api.listDirectory("copy", "/src/util").size());
    }

    @Test
    void copyModuleStripsPublishedOutputLinksButKeepsUserContent(@TempDir Path tempDir) {
        var compiler = ApiCompileTestSupport.RecordingCompiler.succeeding();
        var api = ApiCompileTestSupport.newApi(compiler);
        api.createModule("src", "Source");
        api.putFile("src", "/src/main.gd", "extends Node\n");
        api.setCompileOptions("src", ApiCompileTestSupport.compileOptions(tempDir));
        // A user file next to the managed output dirs: it must survive both the source's own
        // publication sweep and the copy.
        api.putFile("src", "/__build__/notes.txt", "keep me\n");
        var taskId = api.compile("src");
        assertEquals(CompileResult.Outcome.SUCCESS, ApiCompileTestSupport.awaitResult(api, taskId).outcome());
        // Sanity: the source really published managed output links under the default root.
        assertEquals(
                VfsEntrySnapshot.Kind.DIRECTORY,
                api.readEntry("src", "/__build__/generated").kind()
        );

        api.copyModule("src", "copy");

        // The copy starts with a clean publication slate: no inherited generated/artifacts
        // links dangling onto the source's build directory, while user content carries over.
        assertThrows(ApiPathNotFoundException.class, () -> api.readEntry("copy", "/__build__/generated"));
        assertThrows(ApiPathNotFoundException.class, () -> api.readEntry("copy", "/__build__/artifacts"));
        assertEquals("keep me\n", api.readFile("copy", "/__build__/notes.txt"));
    }

    @Test
    void copyModuleResetsCompileRuntimeState(@TempDir Path tempDir) {
        var compiler = ApiCompileTestSupport.RecordingCompiler.succeeding();
        var api = ApiCompileTestSupport.newApi(compiler);
        api.createModule("src", "Source");
        api.putFile("src", "/src/main.gd", "extends Node\n");
        api.setCompileOptions("src", ApiCompileTestSupport.compileOptions(tempDir));
        var taskId = api.compile("src");
        assertEquals(CompileResult.Outcome.SUCCESS, ApiCompileTestSupport.awaitResult(api, taskId).outcome());
        assertTrue(api.getModule("src").hasLastCompileResult());

        var copy = api.copyModule("src", "copy");

        // The copy starts with no compile history even though the source has one; the source's
        // own last result stays untouched.
        assertFalse(copy.hasLastCompileResult());
        assertNull(api.getLastCompileResult("copy"));
        assertNotNull(api.getLastCompileResult("src"));
    }

    @Test
    @SuppressWarnings("DataFlowIssue")
    void copyModuleRejectsMissingSourceExistingTargetAndBadParams() {
        var api = new API();
        api.createModule("src", "Source");

        var missingSource = assertThrows(
                ApiModuleNotFoundException.class,
                () -> api.copyModule("missing", "copy")
        );
        assertEquals("Module 'missing' does not exist", missingSource.getMessage());

        // Self-copy normalizes to an occupied target id, so it is rejected as already-exists.
        var existingTarget = assertThrows(
                ApiModuleAlreadyExistsException.class,
                () -> api.copyModule("src", " src ")
        );
        assertEquals("Module 'src' already exists", existingTarget.getMessage());

        var nullSource = assertThrows(NullPointerException.class, () -> api.copyModule(null, "copy"));
        assertEquals("sourceModuleId must not be null", nullSource.getMessage());
        var blankTarget = assertThrows(IllegalArgumentException.class, () -> api.copyModule("src", "  "));
        assertEquals("newModuleId must not be blank", blankTarget.getMessage());

        // Failed copies must not leave a half-registered target behind.
        assertEquals(List.of("src"), api.listModules().stream().map(ModuleSnapshot::moduleId).toList());
    }

    @Test
    void copyModuleIsolationAfterCopy() {
        var api = new API();
        api.createModule("src", "Source");
        api.putFile("src", "/src/a.gd", "extends Node\n");
        api.copyModule("src", "copy");

        // Writes replace nodes rather than mutating shared leaves, so neither side observes the
        // other's edits after the copy point.
        api.putFile("src", "/src/a.gd", "extends Node2D\n");
        api.putFile("src", "/src/b.gd", "extends RefCounted\n");
        api.putFile("copy", "/src/c.gd", "extends Object\n");

        assertEquals("extends Node\n", api.readFile("copy", "/src/a.gd"));
        // Each side only sees its own post-copy additions.
        assertEquals(
                List.of("a.gd", "c.gd"),
                api.listDirectory("copy", "/src").stream().map(VfsEntrySnapshot::name).toList()
        );
        assertEquals(
                List.of("a.gd", "b.gd"),
                api.listDirectory("src", "/src").stream().map(VfsEntrySnapshot::name).toList()
        );
        assertThrows(ApiPathNotFoundException.class, () -> api.readFile("src", "/src/c.gd"));
    }

    @Test
    void copyModuleCompilesIndependently(@TempDir Path tempDir) {
        var compiler = ApiCompileTestSupport.RecordingCompiler.succeeding();
        var api = ApiCompileTestSupport.newApi(compiler);
        api.createModule("src", "Source");
        api.putFile("src", "/src/main.gd", "extends Node\n");
        api.setCompileOptions("src", ApiCompileTestSupport.compileOptions(tempDir.resolve("src-build")));
        api.copyModule("src", "copy");
        // The caller redirects the copy's build directory so two modules never build into the
        // same host directory (the editor compile flow relies on exactly this).
        api.setCompileOptions("copy", ApiCompileTestSupport.compileOptions(tempDir.resolve("copy-build")));

        var taskId = api.compile("copy");

        assertEquals(CompileResult.Outcome.SUCCESS, ApiCompileTestSupport.awaitResult(api, taskId).outcome());
        assertEquals(1, compiler.invocationCount());
        assertTrue(api.getModule("copy").hasLastCompileResult());
        assertFalse(api.getModule("src").hasLastCompileResult());
    }

    @Test
    void copyModuleWaitsForSourceModuleGate() throws Exception {
        var api = new API();
        api.createModule("src", "Source");
        api.putFile("src", "/src/a.gd", "extends Node\n");
        try (var blocker = ApiCompileTestSupport.blockModuleOperation(api, "src")) {
            assertTrue(blocker.awaitEntered());
            var copied = new CountDownLatch(1);
            var failure = new AtomicReference<Throwable>();
            var copyThread = Thread.ofVirtual().start(() -> {
                try {
                    api.copyModule("src", "copy");
                    copied.countDown();
                } catch (Throwable throwable) {
                    failure.set(throwable);
                }
            });

            // The copy queues behind the in-flight source operation instead of reading a
            // mid-mutation tree; once the gate frees, the snapshot completes.
            assertFalse(copied.await(200, TimeUnit.MILLISECONDS));
            blocker.release();
            assertTrue(copied.await(30, TimeUnit.SECONDS));
            copyThread.join(TimeUnit.SECONDS.toMillis(30));
            if (failure.get() != null) {
                throw new AssertionError("copyModule failed after the source gate was released", failure.get());
            }
            assertEquals("extends Node\n", api.readFile("copy", "/src/a.gd"));
        }
    }
}
