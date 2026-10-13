package gd.script.gdcc.backend.c.build;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Anchors [TinyCcObjCache] without any native work: the key changes when any single input is
/// perturbed and is stable for identical inputs (both directions pinned, so a dropped key input
/// fails a test), and the publish/hit/evict semantics guarantee that only complete entries are
/// ever consumed. The optimization level is absent from the key API by design — DEBUG and
/// RELEASE share entries because tcc has no optimization pipeline.
class TinyCcObjCacheTest {

    private static final String BUNDLE_VERSION = "bundleFormat=1\ntinyccCommit=abc\nplatform=linux-x86_64\n";

    private static @NotNull Path writeTu(@NotNull Path dir, @NotNull String name, @NotNull String content) throws IOException {
        Files.createDirectories(dir);
        var file = dir.resolve(name);
        Files.writeString(file, content);
        return file;
    }

    private static @NotNull Path writeIncludeTree(@NotNull Path root) throws IOException {
        Files.createDirectories(root.resolve("gdcc"));
        Files.writeString(root.resolve("gdcc/helper.h"), "#define GDCC_HELPER 1\n");
        return root;
    }

    private static @NotNull java.util.List<String> includeHashes(@NotNull java.util.List<Path> includeDirs) throws IOException {
        return TinyCcObjCache.hashIncludeTrees(includeDirs);
    }

    @Test
    void sameInputsProduceTheSameKey(@TempDir Path tempDir) throws IOException {
        var includeDir = writeIncludeTree(tempDir.resolve("inc"));
        var tu = writeTu(tempDir, "a.c", "int a(void) { return 1; }\n");
        var content = Files.readAllBytes(tu);

        var first = TinyCcObjCache.requireKey(tu, content, List.of(includeDir), includeHashes(List.of(includeDir)), BUNDLE_VERSION, TargetPlatform.LINUX_X86_64);
        var second = TinyCcObjCache.requireKey(tu, content, List.of(includeDir), includeHashes(List.of(includeDir)), BUNDLE_VERSION, TargetPlatform.LINUX_X86_64);

        assertEquals(first, second);
        assertTrue(first.matches("[0-9a-f]{32}"), "the key is a 16-byte truncated SHA-256 hex: " + first);
    }

    @Test
    void tuContentChangeChangesTheKey(@TempDir Path tempDir) throws IOException {
        var includeDir = writeIncludeTree(tempDir.resolve("inc"));
        var tu = writeTu(tempDir, "a.c", "int a(void) { return 1; }\n");
        var before = TinyCcObjCache.requireKey(tu, Files.readAllBytes(tu), List.of(includeDir), includeHashes(List.of(includeDir)), BUNDLE_VERSION, TargetPlatform.LINUX_X86_64);

        Files.writeString(tu, "int a(void) { return 2; }\n");
        var after = TinyCcObjCache.requireKey(tu, Files.readAllBytes(tu), List.of(includeDir), includeHashes(List.of(includeDir)), BUNDLE_VERSION, TargetPlatform.LINUX_X86_64);

        assertNotEquals(before, after);
    }

    @Test
    void tuPathChangeChangesTheKeyEvenForIdenticalContent(@TempDir Path tempDir) throws IOException {
        var includeDir = writeIncludeTree(tempDir.resolve("inc"));
        var content = "int a(void) { return 1; }\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var tuA = writeTu(tempDir.resolve("project-a"), "a.c", new String(content, java.nio.charset.StandardCharsets.UTF_8));
        var tuB = writeTu(tempDir.resolve("project-b"), "a.c", new String(content, java.nio.charset.StandardCharsets.UTF_8));

        // STT_FILE/__FILE__ bake the TU path into the object, so identical sources in different
        // directories must never share an entry.
        assertNotEquals(
                TinyCcObjCache.requireKey(tuA, content, List.of(includeDir), includeHashes(List.of(includeDir)), BUNDLE_VERSION, TargetPlatform.LINUX_X86_64),
                TinyCcObjCache.requireKey(tuB, content, List.of(includeDir), includeHashes(List.of(includeDir)), BUNDLE_VERSION, TargetPlatform.LINUX_X86_64));
    }

    @Test
    void bundleVersionChangeChangesTheKey(@TempDir Path tempDir) throws IOException {
        var includeDir = writeIncludeTree(tempDir.resolve("inc"));
        var tu = writeTu(tempDir, "a.c", "int a(void) { return 1; }\n");
        var content = Files.readAllBytes(tu);

        var before = TinyCcObjCache.requireKey(tu, content, List.of(includeDir), includeHashes(List.of(includeDir)), BUNDLE_VERSION, TargetPlatform.LINUX_X86_64);
        var after = TinyCcObjCache.requireKey(tu, content, List.of(includeDir), includeHashes(List.of(includeDir)),
                BUNDLE_VERSION.replace("abc", "def"), TargetPlatform.LINUX_X86_64);

        assertNotEquals(before, after);
    }

    @Test
    void targetPlatformChangeChangesTheKey(@TempDir Path tempDir) throws IOException {
        var includeDir = writeIncludeTree(tempDir.resolve("inc"));
        var tu = writeTu(tempDir, "a.c", "int a(void) { return 1; }\n");
        var content = Files.readAllBytes(tu);

        var linux = TinyCcObjCache.requireKey(tu, content, List.of(includeDir), includeHashes(List.of(includeDir)), BUNDLE_VERSION, TargetPlatform.LINUX_X86_64);
        var windows = TinyCcObjCache.requireKey(tu, content, List.of(includeDir), includeHashes(List.of(includeDir)), BUNDLE_VERSION, TargetPlatform.WINDOWS_X86_64);

        assertNotEquals(linux, windows);
    }

    @Test
    void includeTreeContentChangeChangesTheKey(@TempDir Path tempDir) throws IOException {
        var includeDir = writeIncludeTree(tempDir.resolve("inc"));
        var tu = writeTu(tempDir, "a.c", "int a(void) { return 1; }\n");
        var content = Files.readAllBytes(tu);
        var before = TinyCcObjCache.requireKey(tu, content, List.of(includeDir), includeHashes(List.of(includeDir)), BUNDLE_VERSION, TargetPlatform.LINUX_X86_64);

        Files.writeString(includeDir.resolve("gdcc/helper.h"), "#define GDCC_HELPER 2\n");
        var after = TinyCcObjCache.requireKey(tu, content, List.of(includeDir), includeHashes(List.of(includeDir)), BUNDLE_VERSION, TargetPlatform.LINUX_X86_64);

        assertNotEquals(before, after);
    }

    @Test
    void includeDirectoryOrderChangeChangesTheKey(@TempDir Path tempDir) throws IOException {
        var includeA = writeIncludeTree(tempDir.resolve("inc_a"));
        var includeB = writeIncludeTree(tempDir.resolve("inc_b"));
        var tu = writeTu(tempDir, "a.c", "int a(void) { return 1; }\n");
        var content = Files.readAllBytes(tu);

        // `-I` order is semantic: swapping two directories that carry a same-named header must
        // change the key.
        assertNotEquals(
                TinyCcObjCache.requireKey(tu, content, List.of(includeA, includeB), includeHashes(List.of(includeA, includeB)), BUNDLE_VERSION, TargetPlatform.LINUX_X86_64),
                TinyCcObjCache.requireKey(tu, content, List.of(includeB, includeA), includeHashes(List.of(includeB, includeA)), BUNDLE_VERSION, TargetPlatform.LINUX_X86_64));
    }

    @Test
    void includeRootPathChangeChangesTheKeyEvenForIdenticalTrees(@TempDir Path tempDir) throws IOException {
        var includeA = writeIncludeTree(tempDir.resolve("root-a"));
        var includeB = writeIncludeTree(tempDir.resolve("root-b"));
        var tu = writeTu(tempDir, "a.c", "int a(void) { return 1; }\n");
        var content = Files.readAllBytes(tu);

        assertNotEquals(
                TinyCcObjCache.requireKey(tu, content, List.of(includeA), includeHashes(List.of(includeA)), BUNDLE_VERSION, TargetPlatform.LINUX_X86_64),
                TinyCcObjCache.requireKey(tu, content, List.of(includeB), includeHashes(List.of(includeB)), BUNDLE_VERSION, TargetPlatform.LINUX_X86_64));
    }

    @Test
    void publishMakesTheEntryHittableAndEvictRemovesIt(@TempDir Path tempDir) throws IOException {
        var cacheRoot = tempDir.resolve("cache");
        var temp = TinyCcObjCache.newTempObjectPath(cacheRoot, "k1");
        Files.writeString(temp, "object bytes");

        assertNull(TinyCcObjCache.findHit(cacheRoot, "k1"), "a temp file must never count as a hit");
        var published = TinyCcObjCache.publish(cacheRoot, "k1", temp);
        assertEquals(TinyCcObjCache.finalPath(cacheRoot, "k1"), published);
        assertEquals(published, TinyCcObjCache.findHit(cacheRoot, "k1"));
        assertEquals("object bytes", Files.readString(published));

        TinyCcObjCache.evict(cacheRoot, "k1");
        assertNull(TinyCcObjCache.findHit(cacheRoot, "k1"), "an evicted entry must miss");
        TinyCcObjCache.evict(cacheRoot, "k1");
        assertNull(TinyCcObjCache.findHit(cacheRoot, "k1"), "evicting an absent entry is a no-op");
    }

    @Test
    void publishReplacesASameKeyEntryLastWriterWins(@TempDir Path tempDir) throws IOException {
        var cacheRoot = tempDir.resolve("cache");
        var firstTemp = TinyCcObjCache.newTempObjectPath(cacheRoot, "k1");
        Files.writeString(firstTemp, "first");
        TinyCcObjCache.publish(cacheRoot, "k1", firstTemp);

        var secondTemp = TinyCcObjCache.newTempObjectPath(cacheRoot, "k1");
        Files.writeString(secondTemp, "second");
        var published = TinyCcObjCache.publish(cacheRoot, "k1", secondTemp);

        // Concurrent same-key writers race atomically; the entry must end up complete, never
        // a half-written mix.
        assertEquals("second", Files.readString(published));
    }

    @Test
    void hitRequiresARegularFile(@TempDir Path tempDir) throws IOException {
        var cacheRoot = tempDir.resolve("cache");
        // A directory squatting on the entry name is not consumable.
        Files.createDirectories(TinyCcObjCache.finalPath(cacheRoot, "k1"));

        assertNull(TinyCcObjCache.findHit(cacheRoot, "k1"));
    }

    @Test
    void hitOnAMissingCacheRootIsAPlainMiss(@TempDir Path tempDir) {
        assertNull(TinyCcObjCache.findHit(tempDir.resolve("never-created"), "k1"));
    }

    @Test
    void tempObjectNameEndsInDotOAndNeverEqualsTheFinalPath(@TempDir Path tempDir) throws IOException {
        var cacheRoot = tempDir.resolve("cache");
        var temp = TinyCcObjCache.newTempObjectPath(cacheRoot, "k1");

        assertTrue(temp.getFileName().toString().endsWith(".o"),
                "the temp name keeps the .o extension so the linker classifies it as an object: " + temp);
        assertTrue(temp.getFileName().toString().startsWith("k1.tmp-"), () -> "temp name shape: " + temp);
        assertNotEquals(TinyCcObjCache.finalPath(cacheRoot, "k1"), temp);
        assertEquals(TinyCcObjCache.objectDirectory(cacheRoot), temp.getParent(),
                "temp and final paths share a directory, guaranteeing the same filesystem for the atomic move");
    }

    @Test
    void tuAdjacentHeaderChangeChangesTheKey(@TempDir Path tempDir) throws IOException {
        var includeDir = writeIncludeTree(tempDir.resolve("inc"));
        var tu = writeTu(tempDir, "a.c", "#include \"local.h\"\nint a(void) { return LOCAL; }\n");
        Files.writeString(tempDir.resolve("local.h"), "#define LOCAL 1\n");
        var content = Files.readAllBytes(tu);
        var before = TinyCcObjCache.requireKey(tu, content, List.of(includeDir), includeHashes(List.of(includeDir)), BUNDLE_VERSION, TargetPlatform.LINUX_X86_64);

        // Quote-includes resolve next to the TU: the header is a real compilation input even
        // though it never appears in includeDirs.
        Files.writeString(tempDir.resolve("local.h"), "#define LOCAL 2\n");
        assertNotEquals(before, TinyCcObjCache.requireKey(tu, content, List.of(includeDir), includeHashes(List.of(includeDir)), BUNDLE_VERSION, TargetPlatform.LINUX_X86_64),
                "editing a TU-adjacent header must change the key");

        var restored = TinyCcObjCache.requireKey(tu, content, List.of(includeDir), includeHashes(List.of(includeDir)), BUNDLE_VERSION, TargetPlatform.LINUX_X86_64);
        Files.writeString(tempDir.resolve("extra.h"), "#define EXTRA 1\n");
        assertNotEquals(restored, TinyCcObjCache.requireKey(tu, content, List.of(includeDir), includeHashes(List.of(includeDir)), BUNDLE_VERSION, TargetPlatform.LINUX_X86_64),
                "adding a TU-adjacent header must change the key");
    }

    @Test
    void nonHeaderFilesNextToTheTuDoNotEnterTheKey(@TempDir Path tempDir) throws IOException {
        var includeDir = writeIncludeTree(tempDir.resolve("inc"));
        var tu = writeTu(tempDir, "a.c", "int a(void) { return 1; }\n");
        var content = Files.readAllBytes(tu);
        var before = TinyCcObjCache.requireKey(tu, content, List.of(includeDir), includeHashes(List.of(includeDir)), BUNDLE_VERSION, TargetPlatform.LINUX_X86_64);

        // The project directory also carries the shared-library artifact and build metadata;
        // hashing non-headers would make every round self-invalidating.
        Files.writeString(tempDir.resolve("notes.txt"), "build notes\n");
        assertEquals(before, TinyCcObjCache.requireKey(tu, content, List.of(includeDir), includeHashes(List.of(includeDir)), BUNDLE_VERSION, TargetPlatform.LINUX_X86_64));
    }

    @Test
    void directorySymlinkInTheIncludeTreeIsRejected(@TempDir Path tempDir) throws IOException {
        var includeDir = writeIncludeTree(tempDir.resolve("inc"));
        var linkTarget = Files.createDirectories(tempDir.resolve("external"));
        Files.writeString(linkTarget.resolve("config.h"), "#define X 1\n");
        try {
            Files.createSymbolicLink(includeDir.resolve("vendor"), linkTarget);
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            org.junit.jupiter.api.Assumptions.abort("symlinks unavailable here: " + e);
            return;
        }
        var tu = writeTu(tempDir, "a.c", "int a(void) { return 1; }\n");

        // The native include search would follow the link into content the hash cannot see.
        org.junit.jupiter.api.Assertions.assertThrows(IOException.class, () ->
                TinyCcObjCache.requireKey(tu, Files.readAllBytes(tu), List.of(includeDir), includeHashes(List.of(includeDir)), BUNDLE_VERSION, TargetPlatform.LINUX_X86_64));
    }

    @Test
    void publishToADirectoryPlaceholderFails(@TempDir Path tempDir) throws IOException {
        var cacheRoot = tempDir.resolve("cache");
        Files.createDirectories(TinyCcObjCache.finalPath(cacheRoot, "k1").resolve("child"));
        var temp = TinyCcObjCache.newTempObjectPath(cacheRoot, "k1");
        Files.writeString(temp, "object bytes");

        org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
                () -> TinyCcObjCache.publish(cacheRoot, "k1", temp),
                "a squatted final path must fail the atomic move — never a silent overwrite");
    }

    @Test
    void entriesLiveUnderTheSharedResolverCacheRoot(@TempDir Path tempDir) throws IOException {
        var projectDir = tempDir.resolve("workspace/project-a");
        Files.createDirectories(projectDir);
        var envCache = tempDir.resolve("env-cache");

        var cacheRoot = CCompilerCacheRoot.resolve(projectDir, Map.of(CCompilerCacheRoot.SHARED_CACHE_ENV, envCache.toString()));

        assertEquals(envCache.toAbsolutePath().normalize().resolve(TinyCcObjCache.OBJ_DIR_NAME),
                TinyCcObjCache.objectDirectory(cacheRoot));
    }
}
