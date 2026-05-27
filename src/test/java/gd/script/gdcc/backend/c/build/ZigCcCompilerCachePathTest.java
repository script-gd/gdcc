package gd.script.gdcc.backend.c.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ZigCcCompilerCachePathTest {
    private static final String SHARED_CACHE_ENV = "GDCC_SHARED_C_COMPILER_CACHE";

    @Test
    public void usesProjectCacheWhenSharedCacheIsMissing(@TempDir Path tempDir) throws IOException {
        var projectDir = tempDir.resolve("project-a");
        Files.createDirectories(projectDir);

        var cacheRoot = ZigCcCompiler.resolveCompilerCacheRoot(projectDir, Map.of());

        assertEquals(projectDir.toAbsolutePath().normalize().resolve("compiler-cache"), cacheRoot);
    }

    @Test
    public void usesSharedCacheWhenSharedCacheDirectoryExists(@TempDir Path tempDir) throws IOException {
        var workspaceDir = tempDir.resolve("workspace");
        var projectDir = workspaceDir.resolve("project-a");
        var sharedCacheDir = workspaceDir.resolve("shared-compiler-cache");
        Files.createDirectories(projectDir);
        Files.createDirectories(sharedCacheDir);

        var cacheRoot = ZigCcCompiler.resolveCompilerCacheRoot(projectDir, Map.of());

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

        var cacheRoot = ZigCcCompiler.resolveCompilerCacheRoot(projectDir, Map.of());

        assertEquals(projectDir.toAbsolutePath().normalize().resolve("compiler-cache"), cacheRoot);
    }

    @Test
    public void usesEnvironmentCacheAndCreatesItWhenMissing(@TempDir Path tempDir) throws IOException {
        var workspaceDir = tempDir.resolve("workspace");
        var projectDir = workspaceDir.resolve("project-a");
        var envCacheDir = tempDir.resolve("env").resolve("shared-compiler-cache");
        var workspaceSharedCacheDir = workspaceDir.resolve("shared-compiler-cache");
        Files.createDirectories(projectDir);
        Files.createDirectories(workspaceSharedCacheDir);

        var cacheRoot = ZigCcCompiler.resolveCompilerCacheRoot(
                projectDir,
                Map.of(SHARED_CACHE_ENV, envCacheDir.toString())
        );

        assertEquals(envCacheDir.toAbsolutePath().normalize(), cacheRoot);
        assertTrue(Files.isDirectory(envCacheDir));
    }

    @Test
    public void fallsBackToCurrentBehaviorWhenEnvironmentCacheIsBlank(@TempDir Path tempDir) throws IOException {
        var workspaceDir = tempDir.resolve("workspace");
        var projectDir = workspaceDir.resolve("project-a");
        var sharedCacheDir = workspaceDir.resolve("shared-compiler-cache");
        Files.createDirectories(projectDir);
        Files.createDirectories(sharedCacheDir);

        var cacheRoot = ZigCcCompiler.resolveCompilerCacheRoot(
                projectDir,
                Map.of(SHARED_CACHE_ENV, " ")
        );

        assertEquals(sharedCacheDir.toAbsolutePath().normalize(), cacheRoot);
    }

    @Test
    public void fallsBackToCurrentBehaviorWhenEnvironmentCacheIsAFile(@TempDir Path tempDir) throws IOException {
        var workspaceDir = tempDir.resolve("workspace");
        var projectDir = workspaceDir.resolve("project-a");
        var envCachePath = tempDir.resolve("env-cache");
        Files.createDirectories(projectDir);
        Files.writeString(envCachePath, "not-a-directory");

        var cacheRoot = ZigCcCompiler.resolveCompilerCacheRoot(
                projectDir,
                Map.of(SHARED_CACHE_ENV, envCachePath.toString())
        );

        assertEquals(projectDir.toAbsolutePath().normalize().resolve("compiler-cache"), cacheRoot);
    }
}
