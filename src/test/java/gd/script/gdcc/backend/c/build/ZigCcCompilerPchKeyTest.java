package gd.script.gdcc.backend.c.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Pure-Java gate for the PCH cache key — no process is ever started. Anchors the key
/// contract: identical inputs produce an identical key, while the zig version, the resolved
/// target, the optimization level, the actual LTO token, the include dir set, the include dir
/// ORDER (`-I` order is semantic) and any include-tree content change each produce a
/// different key. The key directory layout (`<cacheRoot>/pch/<key>/`) relies on the truncated
/// SHA-256 hex shape asserted here.
class ZigCcCompilerPchKeyTest {
    private static final String VERSION = "0.16.0";
    private static final String TARGET = "x86_64-linux-gnu";

    @Test
    void sameInputsProduceTheSameKey(@TempDir Path tempDir) throws IOException {
        var includes = writeIncludeTree(tempDir.resolve("inc"));
        var first = ZigCcCompiler.resolvePchCacheKey(VERSION, TARGET, CLtoMode.THIN, COptimizationLevel.RELEASE, includes);
        var second = ZigCcCompiler.resolvePchCacheKey(VERSION, TARGET, CLtoMode.THIN, COptimizationLevel.RELEASE, includes);
        assertEquals(first, second);
        assertTrue(first.matches("[0-9a-f]{32}"), "truncated SHA-256 hex, safe as a directory name: " + first);
    }

    @Test
    void zigVersionChangeChangesTheKey(@TempDir Path tempDir) throws IOException {
        var includes = writeIncludeTree(tempDir.resolve("inc"));
        assertNotEquals(
                ZigCcCompiler.resolvePchCacheKey("0.15.2", TARGET, CLtoMode.THIN, COptimizationLevel.RELEASE, includes),
                ZigCcCompiler.resolvePchCacheKey(VERSION, TARGET, CLtoMode.THIN, COptimizationLevel.RELEASE, includes),
                "a zig upgrade must invalidate every cached PCH");
    }

    @Test
    void zigTargetChangeChangesTheKey(@TempDir Path tempDir) throws IOException {
        var includes = writeIncludeTree(tempDir.resolve("inc"));
        assertNotEquals(
                ZigCcCompiler.resolvePchCacheKey(VERSION, "aarch64-linux-gnu", CLtoMode.THIN, COptimizationLevel.RELEASE, includes),
                ZigCcCompiler.resolvePchCacheKey(VERSION, TARGET, CLtoMode.THIN, COptimizationLevel.RELEASE, includes),
                "PCH files are target-specific machine state");
    }

    @Test
    void optimizationLevelChangeChangesTheKey(@TempDir Path tempDir) throws IOException {
        var includes = writeIncludeTree(tempDir.resolve("inc"));
        assertNotEquals(
                ZigCcCompiler.resolvePchCacheKey(VERSION, TARGET, CLtoMode.NONE, COptimizationLevel.DEBUG, includes),
                ZigCcCompiler.resolvePchCacheKey(VERSION, TARGET, CLtoMode.THIN, COptimizationLevel.RELEASE, includes),
                "clang hard-errors when a PCH built for one -O level is consumed by another");
    }

    @Test
    void everyLtoModeProducesADistinctKey(@TempDir Path tempDir) throws IOException {
        var includes = writeIncludeTree(tempDir.resolve("inc"));
        var none = ZigCcCompiler.resolvePchCacheKey(VERSION, TARGET, CLtoMode.NONE, COptimizationLevel.RELEASE, includes);
        var full = ZigCcCompiler.resolvePchCacheKey(VERSION, TARGET, CLtoMode.FULL, COptimizationLevel.RELEASE, includes);
        var thin = ZigCcCompiler.resolvePchCacheKey(VERSION, TARGET, CLtoMode.THIN, COptimizationLevel.RELEASE, includes);
        assertNotEquals(none, full, "a full-LTO fallback must never reuse a no-LTO entry");
        assertNotEquals(full, thin, "a full-LTO fallback must never reuse a ThinLTO entry");
        assertNotEquals(none, thin);
    }

    @Test
    void nestedHeaderContentChangeChangesTheKey(@TempDir Path tempDir) throws IOException {
        var includes = writeIncludeTree(tempDir.resolve("inc"));
        var before = ZigCcCompiler.resolvePchCacheKey(VERSION, TARGET, CLtoMode.NONE, COptimizationLevel.DEBUG, includes);
        // The tree hash walks nested directories exactly like top-level files.
        var nested = includes.get(1).resolve("gdextension/gdextension_interface.h");
        Files.writeString(nested, Files.readString(nested) + "\n/* tampered */\n");
        assertNotEquals(before, ZigCcCompiler.resolvePchCacheKey(VERSION, TARGET, CLtoMode.NONE, COptimizationLevel.DEBUG, includes),
                "any header content change must invalidate the PCH built from it");
    }

    @Test
    void addedOrRemovedHeaderFileChangesTheKey(@TempDir Path tempDir) throws IOException {
        var includes = writeIncludeTree(tempDir.resolve("inc"));
        var before = ZigCcCompiler.resolvePchCacheKey(VERSION, TARGET, CLtoMode.NONE, COptimizationLevel.DEBUG, includes);
        var added = includes.get(0).resolve("gdcc_new_helper.h");
        Files.writeString(added, "#pragma once\n");
        var withAdded = ZigCcCompiler.resolvePchCacheKey(VERSION, TARGET, CLtoMode.NONE, COptimizationLevel.DEBUG, includes);
        assertNotEquals(before, withAdded, "a new header changes the tree hash");
        Files.delete(added);
        assertEquals(before, ZigCcCompiler.resolvePchCacheKey(VERSION, TARGET, CLtoMode.NONE, COptimizationLevel.DEBUG, includes),
                "removing the added file restores the original key (content-defined, not stateful)");
    }

    @Test
    void includeDirOrderChangeChangesTheKey(@TempDir Path tempDir) throws IOException {
        // Two directories carrying a same-named header: with swapped -I order the include
        // resolution differs, so the key must differ too.
        var first = tempDir.resolve("first");
        var second = tempDir.resolve("second");
        writeFile(first.resolve("shared.h"), "/* from first */\n");
        writeFile(second.resolve("shared.h"), "/* from first */\n");
        assertNotEquals(
                ZigCcCompiler.resolvePchCacheKey(VERSION, TARGET, CLtoMode.NONE, COptimizationLevel.DEBUG, List.of(first, second)),
                ZigCcCompiler.resolvePchCacheKey(VERSION, TARGET, CLtoMode.NONE, COptimizationLevel.DEBUG, List.of(second, first)),
                "-I order is semantic and must be preserved by the key");
    }

    @Test
    void includeDirSetChangeChangesTheKey(@TempDir Path tempDir) throws IOException {
        var includes = writeIncludeTree(tempDir.resolve("inc"));
        var full = ZigCcCompiler.resolvePchCacheKey(VERSION, TARGET, CLtoMode.NONE, COptimizationLevel.DEBUG, includes);
        assertNotEquals(full,
                ZigCcCompiler.resolvePchCacheKey(VERSION, TARGET, CLtoMode.NONE, COptimizationLevel.DEBUG, List.of(includes.getFirst())),
                "dropping an include dir changes the flag surface");
        var extra = tempDir.resolve("extra");
        writeFile(extra.resolve("extra.h"), "#pragma once\n");
        var extended = new java.util.ArrayList<>(includes);
        extended.add(extra);
        assertNotEquals(full,
                ZigCcCompiler.resolvePchCacheKey(VERSION, TARGET, CLtoMode.NONE, COptimizationLevel.DEBUG, extended),
                "adding an include dir changes the flag surface");
    }

    @Test
    void sharedIncludeVsProjectIncludeRootsChangeTheKey(@TempDir Path tempDir) throws IOException {
        // Byte-identical trees under different roots (workspace shared-include vs project
        // include): the normalized absolute paths enter the key, so the roots must not share
        // one entry — a workspace-level header refresh must not silently poison projects that
        // still pin their own include tree.
        var shared = writeIncludeTree(tempDir.resolve("workspace/shared-include"));
        var project = writeIncludeTree(tempDir.resolve("workspace/project-a/include"));
        assertNotEquals(
                ZigCcCompiler.resolvePchCacheKey(VERSION, TARGET, CLtoMode.NONE, COptimizationLevel.DEBUG, shared),
                ZigCcCompiler.resolvePchCacheKey(VERSION, TARGET, CLtoMode.NONE, COptimizationLevel.DEBUG, project));
    }

    /// Mirrors the production include dir shape `[<root>/gdcc, <root>/godot]` with a nested
    /// subdirectory, so the tests exercise recursive tree hashing.
    private static List<Path> writeIncludeTree(Path root) throws IOException {
        var gdcc = root.resolve("gdcc");
        var godot = root.resolve("godot");
        writeFile(gdcc.resolve("gdcc_helper.h"), "#pragma once\n#include <godot_binding.h>\n");
        writeFile(godot.resolve("godot_binding.h"), "#pragma once\n#include <gdextension/gdextension_interface.h>\n");
        writeFile(godot.resolve("gdextension/gdextension_interface.h"), "#pragma once\ntypedef int GDExtensionBool;\n");
        return List.of(gdcc, godot);
    }

    private static void writeFile(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
