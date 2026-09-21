package gd.script.gdcc.backend.c.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Pins the content-hash publish scheme of `GodotEditorHotReloadTestSession`: each
/// changed-content publication uses a fresh `bin/` path and the `.gdextension` is retargeted
/// before the swap flag appears. macOS dyld caches images by path, so reloading a previously
/// loaded path can reuse the old image (upstream godotengine/godot#90108 and #112202).
/// Byte-identical content is a no-op and keeps the existing path. Everything here runs without
/// a Godot binary.
class GodotEditorHotReloadTestSessionTest {
    private static final String BUILD_FILE_NAME = "libhr_e2e_dummy_debug_x86_64.so";
    private static final String DRIVER_SOURCE = "extends SceneTree\n";

    @TempDir
    Path tempDir;

    @Test
    void prepareProjectPublishesV1UnderContentHashedName() throws IOException {
        var v1 = writeLibrary("v1", "v1-bytes".getBytes());
        var session = newSession();

        session.prepareProject(v1, DRIVER_SOURCE);

        var published = singlePublishedLibrary();
        assertTrue(published.getFileName().toString().matches(
                        "libhr_e2e_dummy_debug_x86_64-[0-9a-f]{16}\\.so"),
                "v1 must be published under a content-hash-suffixed name: " + published.getFileName());
        assertEquals("v1-bytes", Files.readString(published));
        assertEquals(expectedMetadata(published.getFileName().toString()), readMetadata());
    }

    @Test
    void swapLibraryPublishesNewContentUnderNewHashedNameAndRewritesMetadata() throws IOException {
        var v1 = writeLibrary("v1", "v1-bytes".getBytes());
        var session = newSession();
        session.prepareProject(v1, DRIVER_SOURCE);
        var v1PublishedName = singlePublishedLibrary().getFileName().toString();

        var v2 = writeLibrary("v2", "v2-bytes-longer".getBytes());
        session.swapLibrary(v2);

        var v2PublishedName = singlePublishedLibrary().getFileName().toString();
        assertNotEquals(v1PublishedName, v2PublishedName,
                "new content must be published under a fresh path so dyld cannot serve a cached image");
        assertTrue(v2PublishedName.matches("libhr_e2e_dummy_debug_x86_64-[0-9a-f]{16}\\.so"),
                "unexpected published name shape: " + v2PublishedName);
        assertEquals("v2-bytes-longer", Files.readString(projectDir().resolve("bin").resolve(v2PublishedName)));
        assertEquals(expectedMetadata(v2PublishedName), readMetadata());
        assertTrue(Files.isRegularFile(projectDir().resolve(GodotEditorHotReloadTestSession.SWAP_FLAG_FILE_NAME)),
                "swap flag must be written after the library and metadata");
    }

    @Test
    void swapLibraryWithIdenticalContentKeepsPathAndOnlyDropsFlag() throws IOException {
        var v1 = writeLibrary("v1", "same-bytes".getBytes());
        var session = newSession();
        session.prepareProject(v1, DRIVER_SOURCE);
        var v1PublishedName = singlePublishedLibrary().getFileName().toString();
        var metadataBefore = readMetadata();

        var v2 = writeLibrary("v2", "same-bytes".getBytes());
        session.swapLibrary(v2);

        assertEquals(v1PublishedName, singlePublishedLibrary().getFileName().toString(),
                "byte-identical content must keep the same path (reload is a no-op on every platform)");
        assertEquals(metadataBefore, readMetadata(), "identical content must not rewrite metadata");
        assertTrue(Files.isRegularFile(projectDir().resolve(GodotEditorHotReloadTestSession.SWAP_FLAG_FILE_NAME)),
                "the driver still needs the swap flag to proceed");
    }

    @Test
    void swapAcrossTwoGenerationsKeepsEveryPathDistinct() throws IOException {
        var session = newSession();
        session.prepareProject(writeLibrary("v1", "gen1".getBytes()), DRIVER_SOURCE);
        var gen1Name = singlePublishedLibrary().getFileName().toString();

        session.swapLibrary(writeLibrary("v2", "gen2".getBytes()));
        var gen2Name = singlePublishedLibrary().getFileName().toString();
        session.swapLibrary(writeLibrary("v3", "gen3".getBytes()));
        var gen3Name = singlePublishedLibrary().getFileName().toString();

        assertNotEquals(gen1Name, gen2Name);
        assertNotEquals(gen2Name, gen3Name);
        assertNotEquals(gen1Name, gen3Name);
        assertEquals(expectedMetadata(gen3Name), readMetadata());
    }

    @Test
    void swapLibraryRejectsForeignBuildOutputName() throws IOException {
        var v1 = writeLibrary("v1", "v1-bytes".getBytes());
        var session = newSession();
        session.prepareProject(v1, DRIVER_SOURCE);

        var foreign = tempDir.resolve("other").resolve("libother.so");
        Files.createDirectories(foreign.getParent());
        Files.writeString(foreign, "other-bytes");

        assertThrows(IOException.class, () -> session.swapLibrary(foreign));
    }

    private GodotEditorHotReloadTestSession newSession() {
        return new GodotEditorHotReloadTestSession(projectDir());
    }

    private Path projectDir() {
        return tempDir.resolve("project");
    }

    /// Writes a fake library with the constant build output name into a per-generation directory,
    /// mirroring how the real builds produce the same file name for every generation.
    private Path writeLibrary(String generationDir, byte[] content) throws IOException {
        var dir = tempDir.resolve(generationDir);
        Files.createDirectories(dir);
        var library = dir.resolve(BUILD_FILE_NAME);
        Files.write(library, content);
        return library;
    }

    /// Returns the only non-hidden file in `bin/` (staged `.*.new` files must never leak through).
    private Path singlePublishedLibrary() throws IOException {
        try (var stream = Files.list(projectDir().resolve("bin"))) {
            var published = stream.filter(path -> !path.getFileName().toString().startsWith(".")).toList();
            assertEquals(1, published.size(), "bin/ must hold exactly the current generation: " + published);
            return published.getFirst();
        }
    }

    private String readMetadata() throws IOException {
        return Files.readString(projectDir().resolve(GodotEditorHotReloadTestSession.GDEXTENSION_FILE_NAME));
    }

    private static String expectedMetadata(String publishedFileName) {
        return GdextensionMetadataFile.render(
                "res://bin/" + publishedFileName,
                COptimizationLevel.DEBUG,
                TargetPlatform.getNativePlatform());
    }
}
