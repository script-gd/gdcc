package dev.superice.gdcc.backend.c.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ZigCcCompilerCachePathTest {

    @Test
    public void usesProjectCacheWhenSharedCacheIsMissing(@TempDir Path tempDir) throws IOException {
        var projectDir = tempDir.resolve("project-a");
        Files.createDirectories(projectDir);

        var cacheRoot = ZigCcCompiler.resolveCompilerCacheRoot(projectDir);

        assertEquals(projectDir.toAbsolutePath().normalize().resolve("compiler-cache"), cacheRoot);
    }

    @Test
    public void usesSharedCacheWhenSharedCacheDirectoryExists(@TempDir Path tempDir) throws IOException {
        var workspaceDir = tempDir.resolve("workspace");
        var projectDir = workspaceDir.resolve("project-a");
        var sharedCacheDir = workspaceDir.resolve("shared-compiler-cache");
        Files.createDirectories(projectDir);
        Files.createDirectories(sharedCacheDir);

        var cacheRoot = ZigCcCompiler.resolveCompilerCacheRoot(projectDir);

        assertEquals(sharedCacheDir.toAbsolutePath().normalize(), cacheRoot);
    }

    @Test
    public void fallsBackToProjectCacheWhenSharedCachePathIsAFile(@TempDir Path tempDir) throws IOException {
        var workspaceDir = tempDir.resolve("workspace");
        var projectDir = workspaceDir.resolve("project-a");
        var sharedCachePath = workspaceDir.resolve("shared-compiler-cache");
        Files.createDirectories(projectDir);
        Files.createDirectories(sharedCachePath.getParent());
        Files.writeString(sharedCachePath, "not-a-directory");

        var cacheRoot = ZigCcCompiler.resolveCompilerCacheRoot(projectDir);

        assertEquals(projectDir.toAbsolutePath().normalize().resolve("compiler-cache"), cacheRoot);
    }
}
