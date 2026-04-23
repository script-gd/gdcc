package dev.superice.gdcc.api;

import dev.superice.gdcc.exception.ApiDirectoryNotEmptyException;
import dev.superice.gdcc.exception.ApiEntryTypeMismatchException;
import dev.superice.gdcc.exception.ApiPathNotFoundException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiVirtualFileSystemTest {
    private static final Instant FIXED_TIME = Instant.parse("2026-04-22T10:15:30Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_TIME, ZoneOffset.UTC);

    @Test
    void putFileCreatesParentsAndDirectoryViewsStaySorted() {
        var api = new API(FIXED_CLOCK);
        api.createModule("demo", "Demo");

        var generatedDir = api.createDirectory("demo", "/src/generated");
        var sameDir = api.createDirectory("demo", "/src/generated");
        var mainSource = "extends Node\n";
        var mainFile = api.putFile("demo", "/src/main.gd", mainSource);
        api.createDirectory("demo", "/assets/textures");
        api.putFile("demo", "/README.md", "# demo\n");

        assertEquals("/src/generated", generatedDir.path());
        assertEquals("generated", generatedDir.name());
        assertEquals(0, generatedDir.childCount());
        assertEquals(generatedDir, sameDir);

        assertEquals("/src/main.gd", mainFile.path());
        assertEquals("main.gd", mainFile.name());
        assertEquals(VfsEntrySnapshot.Kind.FILE, mainFile.kind());
        assertEquals(mainSource.length(), mainFile.byteCount());
        assertEquals(FIXED_TIME, mainFile.updatedAt());
        assertEquals(mainSource, api.readFile("demo", "/src/main.gd"));

        var rootEntry = assertInstanceOf(VfsEntrySnapshot.DirectoryEntrySnapshot.class, api.readEntry("demo", "/"));
        assertEquals("/", rootEntry.path());
        assertEquals("/", rootEntry.name());
        assertEquals(3, rootEntry.childCount());

        var srcEntry = assertInstanceOf(VfsEntrySnapshot.DirectoryEntrySnapshot.class, api.readEntry("demo", "/src"));
        assertEquals(2, srcEntry.childCount());

        var fileEntry = assertInstanceOf(VfsEntrySnapshot.FileEntrySnapshot.class, api.readEntry("demo", "/src/main.gd"));
        assertEquals(mainFile, fileEntry);

        assertEquals(
                List.of("README.md", "assets", "src"),
                api.listDirectory("demo", "/").stream().map(VfsEntrySnapshot::name).toList()
        );
        assertEquals(
                List.of("generated", "main.gd"),
                api.listDirectory("demo", "/src").stream().map(VfsEntrySnapshot::name).toList()
        );
        assertEquals(3, api.getModule("demo").rootEntryCount());
    }

    @Test
    void putFileCanExposeDisplayPathWhileKeepingVirtualPathStable() {
        var api = new API(FIXED_CLOCK);
        api.createModule("demo", "Demo");

        var created = api.putFile("demo", "/src/main.gd", "extends Node\n", "/display/main.gd");
        var preserved = api.putFile("demo", "/src/main.gd", "extends RefCounted\n");
        var entry = assertInstanceOf(VfsEntrySnapshot.FileEntrySnapshot.class, api.readEntry("demo", "/src/main.gd"));

        assertEquals("/display/main.gd", created.path());
        assertEquals("/src/main.gd", created.virtualPath());
        assertEquals("/display/main.gd", preserved.path());
        assertEquals("/src/main.gd", preserved.virtualPath());
        assertEquals("/display/main.gd", entry.path());
        assertEquals("/src/main.gd", entry.virtualPath());
        assertEquals("main.gd", entry.name());
    }

    @Test
    void deletePathHonorsRecursiveFlagAndUpdatesModuleSnapshot() {
        var api = new API(FIXED_CLOCK);
        api.createModule("demo", "Demo");
        api.putFile("demo", "/src/main.gd", "main");
        api.putFile("demo", "/src/nested/util.gd", "util");

        var nonRecursiveError = assertThrows(
                ApiDirectoryNotEmptyException.class,
                () -> api.deletePath("demo", "/src", false)
        );
        assertEquals(
                "Directory '/src' in module 'demo' is not empty; recursive delete required",
                nonRecursiveError.getMessage()
        );

        var removed = assertInstanceOf(
                VfsEntrySnapshot.DirectoryEntrySnapshot.class,
                api.deletePath("demo", "/src", true)
        );

        assertEquals("/src", removed.path());
        assertEquals(2, removed.childCount());
        assertEquals(0, api.getModule("demo").rootEntryCount());

        var missingError = assertThrows(ApiPathNotFoundException.class, () -> api.readEntry("demo", "/src"));
        assertEquals("Path '/src' does not exist in module 'demo'", missingError.getMessage());
    }

    @Test
    void fileAndDirectoryBoundariesAreStrict() {
        var api = new API(FIXED_CLOCK);
        api.createModule("demo", "Demo");
        api.putFile("demo", "/src", "plain file");
        api.createDirectory("demo", "/dir");

        var fileParentError = assertThrows(
                ApiEntryTypeMismatchException.class,
                () -> api.putFile("demo", "/src/main.gd", "child")
        );
        assertEquals("Path '/src' in module 'demo' is a file, not a directory", fileParentError.getMessage());

        var nestedDirectoryError = assertThrows(
                ApiEntryTypeMismatchException.class,
                () -> api.createDirectory("demo", "/src/generated")
        );
        assertEquals("Path '/src' in module 'demo' is a file, not a directory", nestedDirectoryError.getMessage());

        var listFileError = assertThrows(
                ApiEntryTypeMismatchException.class,
                () -> api.listDirectory("demo", "/src")
        );
        assertEquals("Path '/src' in module 'demo' is a file, not a directory", listFileError.getMessage());

        var overwriteDirectoryError = assertThrows(
                ApiEntryTypeMismatchException.class,
                () -> api.putFile("demo", "/dir", "body")
        );
        assertEquals("Path '/dir' in module 'demo' is a directory, not a file", overwriteDirectoryError.getMessage());

        var readDirectoryAsFileError = assertThrows(
                ApiEntryTypeMismatchException.class,
                () -> api.readFile("demo", "/dir")
        );
        assertEquals("Path '/dir' in module 'demo' is a directory, not a file", readDirectoryAsFileError.getMessage());
    }

    @Test
    @SuppressWarnings("DataFlowIssue")
    void missingPathsAndRootBoundaryOperationsFailClearly() {
        var api = new API(FIXED_CLOCK);
        api.createModule("demo", "Demo");

        var missingReadError = assertThrows(ApiPathNotFoundException.class, () -> api.readFile("demo", "/missing.gd"));
        assertEquals("Path '/missing.gd' does not exist in module 'demo'", missingReadError.getMessage());

        var missingDirectoryError = assertThrows(
                ApiPathNotFoundException.class,
                () -> api.listDirectory("demo", "/missing")
        );
        assertEquals("Path '/missing' does not exist in module 'demo'", missingDirectoryError.getMessage());

        var rootDeleteError = assertThrows(
                IllegalArgumentException.class,
                () -> api.deletePath("demo", "/", true)
        );
        assertEquals("path '/' cannot be deleted; delete the module instead", rootDeleteError.getMessage());

        var rootFileError = assertThrows(
                IllegalArgumentException.class,
                () -> api.putFile("demo", "/", "body")
        );
        assertEquals("path '/' cannot be used as a file path", rootFileError.getMessage());

        var invalidPathError = assertThrows(
                IllegalArgumentException.class,
                () -> api.readEntry("demo", "src/main.gd")
        );
        assertTrue(invalidPathError.getMessage().contains("path must start with '/'"));

        var nullContentError = assertThrows(
                NullPointerException.class,
                () -> api.putFile("demo", "/main.gd", null)
        );
        assertEquals("content must not be null", nullContentError.getMessage());

        var blankDisplayPathError = assertThrows(
                IllegalArgumentException.class,
                () -> api.putFile("demo", "/main.gd", "body", "   ")
        );
        assertEquals("displayPath must not be blank", blankDisplayPathError.getMessage());
    }
}
