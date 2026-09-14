package gd.script.gdcc.util;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

public class ResourceExtractorTest {

    @Test
    public void testExtractIntoEmptyDir(@TempDir Path tempDir) throws IOException {
        var loader = getClass().getClassLoader();
        ResourceExtractor.extract("extractor_test", tempDir, loader);

        // a.txt should be present
        var a = tempDir.resolve("a.txt");
        assertTrue(Files.exists(a));
        var aContent = Files.readString(a, StandardCharsets.UTF_8).trim();
        assertFalse(aContent.isEmpty());

        // o.zip should be expanded into o/ with b/c.txt/d.txt and b/e.txt
        var oDir = tempDir.resolve("o");
        assertTrue(Files.exists(oDir) && Files.isDirectory(oDir));
        assertTrue(Files.exists(oDir.resolve("c.txt")));
        assertTrue(Files.exists(oDir.resolve("d.txt")));
        assertTrue(Files.exists(oDir.resolve("b")));
        assertTrue(Files.exists(oDir.resolve("b").resolve("e.txt")));
    }

    @Test
    public void testExtractWithExistingDifferentFiles(@TempDir Path tempDir) throws IOException {
        var loader = getClass().getClassLoader();
        // create target that already contains a.txt with different content
        var a = tempDir.resolve("a.txt");
        Files.writeString(a, "old-content", StandardCharsets.UTF_8);

        ResourceExtractor.extract("extractor_test", tempDir, loader);

        // a.txt should be replaced with resource content
        var aContent = Files.readString(a, StandardCharsets.UTF_8).trim();
        assertNotEquals("old-content", aContent);
        assertFalse(aContent.isEmpty());

        // ensure no leftover temp files named .gdcc-* remain
        try (var stream = Files.list(tempDir)) {
            stream.forEach(p -> assertFalse(p.getFileName().toString().contains(".gdcc-")));
        }
    }

    @Test
    public void testExtractSpecificSuccessful(@TempDir Path tempDir) throws IOException {
        var loader = getClass().getClassLoader();
        ResourceExtractor.extractSpecific("extractor_test", List.of("a.txt", "o.zip"), tempDir, loader);

        assertTrue(Files.exists(tempDir.resolve("a.txt")));
        assertTrue(Files.exists(tempDir.resolve("o")));
    }

    @Test
    public void testExtractSpecificMissingResource(@TempDir Path tempDir) {
        var loader = getClass().getClassLoader();
        var ex = assertThrows(IOException.class, () -> ResourceExtractor.extractSpecific("extractor_test", List.of("a.txt", "not-exist.txt"), tempDir, loader));
        assertTrue(ex.getMessage().contains("Requested resource not found"));
        // ensure nothing was extracted
        assertFalse(Files.exists(tempDir.resolve("a.txt")));
    }

    @Test
    public void testListResourceFilesRecursivelyReturnsSortedRelativePaths() throws IOException {
        var loader = getClass().getClassLoader();

        var resources = ResourceExtractor.listResourceFilesRecursively("extractor_test", loader);

        assertEquals(List.of("a.txt", "o.zip"), resources);
    }

    @Test
    public void testListResourceFilesRecursivelyFindsNestedTestSuiteScripts() throws IOException {
        var loader = getClass().getClassLoader();

        var resources = ResourceExtractor.listResourceFilesRecursively("unit_test/script", loader);

        assertTrue(resources.contains("smoke/basic_arithmetic.gd"), () -> "Expected nested test-suite script, got " + resources);
    }

    @Test
    public void testJarResourcesCanBeListedAndExtracted(@TempDir Path tempDir) throws IOException {
        var jar = tempDir.resolve("resources.jar");
        try (var zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            writeJarEntry(zip, "jar_root/");
            writeJarEntry(zip, "jar_root/a.txt", "alpha");
            writeJarEntry(zip, "jar_root/nested/");
            writeJarEntry(zip, "jar_root/nested/b.txt", "beta");
        }

        try (var loader = new URLClassLoader(new URL[]{jar.toUri().toURL()})) {
            var resources = ResourceExtractor.listResourceFilesRecursively("jar_root", loader);
            assertEquals(List.of("a.txt", "nested/b.txt"), resources);

            var out = tempDir.resolve("out");
            ResourceExtractor.extract("jar_root", out, loader);
            assertEquals("alpha", Files.readString(out.resolve("a.txt"), StandardCharsets.UTF_8));
            assertEquals("beta", Files.readString(out.resolve("nested").resolve("b.txt"), StandardCharsets.UTF_8));
        }
    }

    @Test
    public void testJarExtractionSkipsIdenticalFilesWithoutTouchingMtime(@TempDir Path tempDir) throws IOException, InterruptedException {
        // clang PCH validation rejects a pch whose recorded header mtimes no longer match the
        // files on disk; blindly replacing identical resources on every build would churn
        // those mtimes and permanently disable the pch cache for packaged (jar) runs.
        var jar = tempDir.resolve("resources.jar");
        try (var zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            writeJarEntry(zip, "jar_root/");
            writeJarEntry(zip, "jar_root/a.txt", "alpha");
        }

        try (var loader = new URLClassLoader(new URL[]{jar.toUri().toURL()})) {
            var out = tempDir.resolve("out");
            ResourceExtractor.extract("jar_root", out, loader);
            var extracted = out.resolve("a.txt");
            var firstMtime = Files.getLastModifiedTime(extracted);
            // Make a later mtime observable on filesystems with coarse timestamp granularity.
            Thread.sleep(20);

            ResourceExtractor.extract("jar_root", out, loader);
            assertEquals(firstMtime, Files.getLastModifiedTime(extracted),
                    "re-extracting identical content must not replace the file");
            assertEquals("alpha", Files.readString(extracted, StandardCharsets.UTF_8));
        }
    }

    @Test
    public void testJarExtractionReplacesWhenContentDiffers(@TempDir Path tempDir) throws IOException {
        var out = tempDir.resolve("out");
        Files.createDirectories(out);
        Files.writeString(out.resolve("a.txt"), "stale");

        var jar = tempDir.resolve("resources.jar");
        try (var zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            writeJarEntry(zip, "jar_root/");
            writeJarEntry(zip, "jar_root/a.txt", "alpha");
        }
        try (var loader = new URLClassLoader(new URL[]{jar.toUri().toURL()})) {
            ResourceExtractor.extract("jar_root", out, loader);
            assertEquals("alpha", Files.readString(out.resolve("a.txt"), StandardCharsets.UTF_8),
                    "changed content must still be extracted");
        }
    }

    private static void writeJarEntry(@NotNull ZipOutputStream zip, @NotNull String name) throws IOException {
        writeJarEntry(zip, name, "");
    }

    private static void writeJarEntry(@NotNull ZipOutputStream zip, @NotNull String name, @NotNull String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
