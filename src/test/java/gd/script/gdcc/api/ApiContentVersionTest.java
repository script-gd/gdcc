package gd.script.gdcc.api;

import gd.script.gdcc.exception.ApiEntryTypeMismatchException;
import gd.script.gdcc.exception.ApiPathNotFoundException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// `contentVersion` advances only when a mutation actually lands (plan §2.3.1): idempotent
/// re-creation and failed mutations must not invalidate in-flight analyses, while every real
/// change must be visible to the freeze/publish version pair.
class ApiContentVersionTest {

    @Test
    void idempotentAndFailedMutationsDoNotAdvanceContentVersion() {
        var api = new API();
        try {
            api.createModule("m", "M");
            api.putFile("m", "/src/main.gd", "extends RefCounted\n");
            var baseline = api.getModuleContentVersion("m");

            // Idempotent mkdir on an existing directory is a no-op.
            api.createDirectory("m", "/src");
            api.createDirectory("m", "/");
            // Failed mutations must not advance the version either.
            assertThrows(ApiPathNotFoundException.class, () -> api.deletePath("m", "/missing", false));
            assertThrows(
                    ApiEntryTypeMismatchException.class,
                    () -> api.createLink("m", "/src", VfsEntrySnapshot.LinkKind.VIRTUAL, "/elsewhere")
            );
            assertThrows(
                    IllegalArgumentException.class,
                    () -> api.createLink("m", "/link", VfsEntrySnapshot.LinkKind.LOCAL, "  ")
            );
            assertThrows(ApiEntryTypeMismatchException.class, () -> api.createDirectory("m", "/src/main.gd"));

            // A failed creation must not leave its auto-created parent directories behind either:
            // that would mutate frozen inputs at an unchanged version.
            assertThrows(
                    IllegalArgumentException.class,
                    () -> api.createLink("m", "/newdir/link", VfsEntrySnapshot.LinkKind.LOCAL, "  ")
            );
            assertThrows(ApiPathNotFoundException.class, () -> api.listDirectory("m", "/newdir"));

            assertEquals(baseline, api.getModuleContentVersion("m"),
                    "no-op and failed mutations must leave the content version untouched");
        } finally {
            api.close();
        }
    }

    @Test
    void failedMutationsLeaveNoAutoCreatedParentsBehind() {
        var api = new API();
        try {
            api.createModule("m", "M");
            api.putFile("m", "/plain.txt", "not a directory");
            api.putFile("m", "/other.txt", "another file");
            var baseline = api.getModuleContentVersion("m");

            // VIRTUAL target that can never resolve (a mid-path segment is a file).
            assertThrows(
                    ApiEntryTypeMismatchException.class,
                    () -> api.createLink("m", "/newdir2/link", VfsEntrySnapshot.LinkKind.VIRTUAL, "/plain.txt/child")
            );
            assertThrows(ApiPathNotFoundException.class, () -> api.listDirectory("m", "/newdir2"));

            // Blank displayPath validation must also run before parent creation.
            assertThrows(
                    IllegalArgumentException.class,
                    () -> api.putFile("m", "/newdir3/a.gd", "extends RefCounted\n", "  ")
            );
            assertThrows(ApiPathNotFoundException.class, () -> api.listDirectory("m", "/newdir3"));

            // A never-resolvable target fails cleanly even when the link would overwrite a file:
            // the replaced leaf must be restored. (The target must not traverse the new link
            // itself — that shape resolves to a legal CYCLE instead.)
            assertThrows(
                    ApiEntryTypeMismatchException.class,
                    () -> api.createLink("m", "/plain.txt", VfsEntrySnapshot.LinkKind.VIRTUAL, "/other.txt/child")
            );
            assertEquals("not a directory", api.readFile("m", "/plain.txt"));

            assertEquals(baseline, api.getModuleContentVersion("m"),
                    "failed mutations must leave neither parent directories nor version bumps behind");
        } finally {
            api.close();
        }
    }

    @Test
    void selfReferencingLinkOverwritingFileStaysALegalCycle() {
        // Overwriting a file with a link that points into itself must resolve as a CYCLE broken
        // link on the prospective tree — not fail as a type mismatch against the replaced file.
        var api = new API();
        try {
            api.createModule("m", "M");
            api.putFile("m", "/selfref", "x");

            var link = api.createLink("m", "/selfref", VfsEntrySnapshot.LinkKind.VIRTUAL, "/selfref/child");

            assertEquals(VfsEntrySnapshot.BrokenReason.CYCLE, link.brokenReason());
        } finally {
            api.close();
        }
    }

    @Test
    void successfulMutationsAdvanceContentVersion() {
        var api = new API();
        try {
            api.createModule("m", "M");
            api.putFile("m", "/src/main.gd", "extends RefCounted\n");
            var baseline = api.getModuleContentVersion("m");

            var afterMkdir = api.getModuleContentVersion("m");
            api.createDirectory("m", "/data");
            assertNotEquals(baseline, api.getModuleContentVersion("m"), "real mkdir must bump");

            api.createLink("m", "/data/alias", VfsEntrySnapshot.LinkKind.VIRTUAL, "/src");
            assertNotEquals(afterMkdir, api.getModuleContentVersion("m"), "link creation must bump");

            var afterLink = api.getModuleContentVersion("m");
            api.deletePath("m", "/data/alias", false);
            assertNotEquals(afterLink, api.getModuleContentVersion("m"), "delete must bump");
        } finally {
            api.close();
        }
    }

    @Test
    void recreatedModuleNeverServesPreviousGenerationSnapshot() {
        var api = new API();
        try {
            api.createModule("m", "M");
            api.putFile("m", "/main.gd", "extends RefCounted\n");
            api.analyze("m");
            var oldGeneration = api.getModuleContentVersion("m").moduleGeneration();
            assertNotNull(api.getLatestAnalysisSnapshot("m"), "first generation must publish a snapshot");

            api.deleteModule("m");
            api.createModule("m", "M");

            assertAll(
                    () -> assertNotEquals(oldGeneration, api.getModuleContentVersion("m").moduleGeneration(),
                            "recreate must start a new generation"),
                    () -> assertNull(api.getLatestAnalysisSnapshot("m"),
                            "a fresh generation has no snapshot yet; the old one must never leak")
            );
        } finally {
            api.close();
        }
    }
}
