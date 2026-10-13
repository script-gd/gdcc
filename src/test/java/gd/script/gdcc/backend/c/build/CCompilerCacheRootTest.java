package gd.script.gdcc.backend.c.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Decision table of [CCompilerCacheRoot]: the zig PCH cache and the tcc object cache share
/// this exact resolution, so these cases anchor the behavior both backends rely on. (Migrated
/// from `ZigCcCompilerCachePathTest` when the resolver moved out of [ZigCcCompiler].)
class CCompilerCacheRootTest {

    @Test
    void usesProjectCacheWhenSharedCacheIsMissing(@TempDir Path tempDir) throws IOException {
        var projectDir = tempDir.resolve("project-a");
        Files.createDirectories(projectDir);

        var cacheRoot = CCompilerCacheRoot.resolve(projectDir, Map.of());

        assertEquals(projectDir.toAbsolutePath().normalize().resolve(CCompilerCacheRoot.PROJECT_CACHE_DIR_NAME), cacheRoot);
    }

    @Test
    void usesSharedCacheWhenSharedCacheDirectoryExists(@TempDir Path tempDir) throws IOException {
        var workspaceDir = tempDir.resolve("workspace");
        var projectDir = workspaceDir.resolve("project-a");
        var sharedCacheDir = workspaceDir.resolve(CCompilerCacheRoot.SHARED_CACHE_DIR_NAME);
        Files.createDirectories(projectDir);
        Files.createDirectories(sharedCacheDir);

        var cacheRoot = CCompilerCacheRoot.resolve(projectDir, Map.of());

        assertEquals(sharedCacheDir.toAbsolutePath().normalize(), cacheRoot);
    }

    @Test
    void fallsBackToProjectCacheWhenSharedCachePathIsAFile(@TempDir Path tempDir) throws IOException {
        var workspaceDir = tempDir.resolve("workspace");
        var projectDir = workspaceDir.resolve("project-a");
        var sharedCachePath = workspaceDir.resolve(CCompilerCacheRoot.SHARED_CACHE_DIR_NAME);
        Files.createDirectories(projectDir);
        Files.createDirectories(sharedCachePath.getParent());
        Files.writeString(sharedCachePath, "not-a-directory");

        var cacheRoot = CCompilerCacheRoot.resolve(projectDir, Map.of());

        assertEquals(projectDir.toAbsolutePath().normalize().resolve(CCompilerCacheRoot.PROJECT_CACHE_DIR_NAME), cacheRoot);
    }

    @Test
    void usesEnvironmentCacheAndCreatesItWhenMissing(@TempDir Path tempDir) throws IOException {
        var workspaceDir = tempDir.resolve("workspace");
        var projectDir = workspaceDir.resolve("project-a");
        var envCacheDir = tempDir.resolve("env").resolve(CCompilerCacheRoot.SHARED_CACHE_DIR_NAME);
        var workspaceSharedCacheDir = workspaceDir.resolve(CCompilerCacheRoot.SHARED_CACHE_DIR_NAME);
        Files.createDirectories(projectDir);
        Files.createDirectories(workspaceSharedCacheDir);

        var cacheRoot = CCompilerCacheRoot.resolve(
                projectDir,
                Map.of(CCompilerCacheRoot.SHARED_CACHE_ENV, envCacheDir.toString())
        );

        assertEquals(envCacheDir.toAbsolutePath().normalize(), cacheRoot);
        assertTrue(Files.isDirectory(envCacheDir));
    }

    @Test
    void fallsBackToCurrentBehaviorWhenEnvironmentCacheIsBlank(@TempDir Path tempDir) throws IOException {
        var workspaceDir = tempDir.resolve("workspace");
        var projectDir = workspaceDir.resolve("project-a");
        var sharedCacheDir = workspaceDir.resolve(CCompilerCacheRoot.SHARED_CACHE_DIR_NAME);
        Files.createDirectories(projectDir);
        Files.createDirectories(sharedCacheDir);

        var cacheRoot = CCompilerCacheRoot.resolve(
                projectDir,
                Map.of(CCompilerCacheRoot.SHARED_CACHE_ENV, " ")
        );

        assertEquals(sharedCacheDir.toAbsolutePath().normalize(), cacheRoot);
    }

    @Test
    void fallsBackToCurrentBehaviorWhenEnvironmentCacheIsAFile(@TempDir Path tempDir) throws IOException {
        var workspaceDir = tempDir.resolve("workspace");
        var projectDir = workspaceDir.resolve("project-a");
        var envCachePath = tempDir.resolve("env-cache");
        Files.createDirectories(projectDir);
        Files.writeString(envCachePath, "not-a-directory");

        var cacheRoot = CCompilerCacheRoot.resolve(
                projectDir,
                Map.of(CCompilerCacheRoot.SHARED_CACHE_ENV, envCachePath.toString())
        );

        assertEquals(projectDir.toAbsolutePath().normalize().resolve(CCompilerCacheRoot.PROJECT_CACHE_DIR_NAME), cacheRoot);
    }
}
