package gd.script.gdcc.cli;

import gd.script.gdcc.backend.c.build.CCompilerKind;
import gd.script.gdcc.backend.c.build.COptimizationLevel;
import gd.script.gdcc.exception.GodotConfigException;
import gd.script.gdcc.exception.GodotProjectException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GodotProjectInputTest {
    @Test
    void appliesDefaultsWhenSettingsAreAbsent(@TempDir Path tempDir) throws IOException {
        var projectFile = writeProject(tempDir, "GdccTest", "");

        var input = GodotProjectInput.load(projectFile);

        assertEquals("GdccTest", input.config().projectName());
        assertEquals(List.of("addons/gdcc/*"), input.config().excludedGlobs());
        assertEquals(COptimizationLevel.DEBUG, input.config().optimizationLevel());
        assertFalse(input.config().strictMode());
        assertNull(input.config().cCompilerKind());
        assertEquals(projectFile.toRealPath(), input.projectFile());
        assertEquals(projectFile.toRealPath().getParent(), input.projectRoot());
    }

    @Test
    void readsExplicitProjectSettings(@TempDir Path tempDir) throws IOException {
        var projectFile = writeProject(tempDir, "Tuned", """
                [gdcc]
                sync/excluded_globs=PackedStringArray("addons/gdcc/*", "generated/*")
                build/optimization_level="RELEASE"
                build/strict_mode=true
                build/c_compiler_kind="ZIG_CC"
                """);

        var input = GodotProjectInput.load(projectFile);

        assertEquals(List.of("addons/gdcc/*", "generated/*"), input.config().excludedGlobs());
        assertEquals(COptimizationLevel.RELEASE, input.config().optimizationLevel());
        assertEquals(true, input.config().strictMode());
        assertEquals(CCompilerKind.ZIG_CC, input.config().cCompilerKind());
    }

    @Test
    void autoCompilerKindMapsToNullAndPlainArraysAreAccepted(@TempDir Path tempDir) throws IOException {
        var projectFile = writeProject(tempDir, "Coerce", """
                [gdcc]
                sync/excluded_globs=["a/*", "b/*"]
                build/c_compiler_kind="AUTO"
                build/strict_mode=false
                """);

        var input = GodotProjectInput.load(projectFile);

        assertEquals(List.of("a/*", "b/*"), input.config().excludedGlobs());
        assertNull(input.config().cCompilerKind());
        assertFalse(input.config().strictMode());
    }

    @Test
    void missingOrBlankProjectNameIsRejected(@TempDir Path tempDir) throws IOException {
        var missing = tempDir.resolve("game/project.godot");
        Files.createDirectories(missing.getParent());
        Files.writeString(missing, "config_version=5\n", StandardCharsets.UTF_8);
        assertThrows(GodotConfigException.class, () -> GodotProjectInput.load(missing));

        var blank = writeProject(tempDir, "", "");
        assertThrows(GodotConfigException.class, () -> GodotProjectInput.load(blank));

        var nonString = tempDir.resolve("other/project.godot");
        Files.createDirectories(nonString.getParent());
        Files.writeString(nonString, "[application]\nconfig/name=5\n", StandardCharsets.UTF_8);
        assertThrows(GodotConfigException.class, () -> GodotProjectInput.load(nonString));
    }

    @Test
    void wrongSettingTypesAndValuesAreRejected(@TempDir Path tempDir) throws IOException {
        var badLevelValue = writeProject(tempDir.resolve("a"), "A", """
                [gdcc]
                build/optimization_level="FAST"
                """);
        var exception = assertThrows(GodotConfigException.class, () -> GodotProjectInput.load(badLevelValue));
        assertTrue(exception.getMessage().contains("optimization_level"), exception.getMessage());

        var badLevelType = writeProject(tempDir.resolve("b"), "B", """
                [gdcc]
                build/optimization_level=1
                """);
        assertThrows(GodotConfigException.class, () -> GodotProjectInput.load(badLevelType));

        var badStrict = writeProject(tempDir.resolve("c"), "C", """
                [gdcc]
                build/strict_mode="yes"
                """);
        assertThrows(GodotConfigException.class, () -> GodotProjectInput.load(badStrict));

        var badKind = writeProject(tempDir.resolve("d"), "D", """
                [gdcc]
                build/c_compiler_kind="CLANG"
                """);
        assertThrows(GodotConfigException.class, () -> GodotProjectInput.load(badKind));

        var badGlobs = writeProject(tempDir.resolve("e"), "E", """
                [gdcc]
                sync/excluded_globs=5
                """);
        assertThrows(GodotConfigException.class, () -> GodotProjectInput.load(badGlobs));

        // A `[Type]` suffix poisons the whole value: it must not decode through the plain
        // identifier or PackedStringArray paths into an accepted setting.
        var suffixedBool = writeProject(tempDir.resolve("f"), "F", """
                [gdcc]
                build/strict_mode=true[false]
                """);
        assertThrows(GodotConfigException.class, () -> GodotProjectInput.load(suffixedBool));

        var typedGlobs = writeProject(tempDir.resolve("g"), "G", """
                [gdcc]
                sync/excluded_globs=PackedStringArray[int]("src/*")
                """);
        assertThrows(GodotConfigException.class, () -> GodotProjectInput.load(typedGlobs));
    }

    @Test
    void featureOverrideSiblingsOfConsumedSettingsAreRejected(@TempDir Path tempDir) throws IOException {
        var projectFile = writeProject(tempDir, "Overridden", """
                [gdcc]
                build/optimization_level="DEBUG"
                build/optimization_level.release="RELEASE"
                """);

        var exception = assertThrows(GodotConfigException.class, () -> GodotProjectInput.load(projectFile));
        assertTrue(exception.getMessage().contains("feature-override"), exception.getMessage());
    }

    @Test
    void invalidSyntaxAndBadUtf8AreRejected(@TempDir Path tempDir) throws IOException {
        var malformed = tempDir.resolve("game/project.godot");
        Files.createDirectories(malformed.getParent());
        Files.writeString(malformed, "[application]\nconfig/name=\"unclosed\n", StandardCharsets.UTF_8);
        assertThrows(GodotConfigException.class, () -> GodotProjectInput.load(malformed));

        var badUtf8 = tempDir.resolve("bad/project.godot");
        Files.createDirectories(badUtf8.getParent());
        Files.write(badUtf8, new byte[]{'[', 'a', ']', '\n', 'x', '=', '"', (byte) 0xFF, (byte) 0xFE, '"', '\n'});
        var exception = assertThrows(GodotConfigException.class, () -> GodotProjectInput.load(badUtf8));
        assertTrue(exception.getMessage().contains("UTF-8"), exception.getMessage());
    }

    @Test
    void projectFileValidationRejectsMissingAndNonFilePaths(@TempDir Path tempDir) throws IOException {
        var missing = tempDir.resolve("game/project.godot");
        var missingException = assertThrows(GodotProjectException.class, () -> GodotProjectInput.load(missing));
        assertTrue(missingException.getMessage().contains("does not exist"), missingException.getMessage());

        var directory = tempDir.resolve("dir/project.godot");
        Files.createDirectories(directory);
        var dirException = assertThrows(GodotProjectException.class, () -> GodotProjectInput.load(directory));
        assertTrue(dirException.getMessage().contains("not a regular file"), dirException.getMessage());
    }

    @Test
    void defaultWorkDirectoryLivesUnderCliCacheWithIdentity(@TempDir Path tempDir) throws IOException {
        var first = writeProject(tempDir.resolve("game"), "GdccTest", "");
        var sameNameElsewhere = writeProject(tempDir.resolve("other"), "GdccTest", "");

        var firstInput = GodotProjectInput.load(first);
        var otherInput = GodotProjectInput.load(sameNameElsewhere);

        var expectedPrefix = firstInput.projectRoot().resolve(".godot").resolve("gdcc").resolve("cli");
        assertTrue(firstInput.defaultWorkDirectory().startsWith(expectedPrefix));
        // Same project name, different roots → same readable prefix, different identity hash.
        assertTrue(firstInput.identity().startsWith("GdccTest_"));
        assertTrue(otherInput.identity().startsWith("GdccTest_"));
        assertTrue(!firstInput.identity().equals(otherInput.identity()));
    }

    @Test
    void explicitOutputDirectoryFollowsProjectBoundaries(@TempDir Path tempDir) throws IOException {
        var projectFile = writeProject(tempDir.resolve("game"), "Game", "");
        var input = GodotProjectInput.load(projectFile);
        var otherProject = writeProject(tempDir.resolve("other"), "Other", "");

        // Inside the CLI cache subtree: allowed.
        var cacheOutput = input.cliCacheRoot().resolve("custom");
        assertEquals(cacheOutput, input.workDirectory(cacheOutput));

        // Outside every project: allowed.
        var outside = tempDir.resolve("build/demo");
        assertEquals(outside.toAbsolutePath().normalize(), input.workDirectory(outside));

        // Inside the project resource tree (including .godot but outside the cli subtree): refused.
        assertThrows(GodotProjectException.class, () -> input.workDirectory(input.projectRoot()));
        assertThrows(GodotProjectException.class,
                () -> input.workDirectory(input.projectRoot().resolve("src/build")));
        assertThrows(GodotProjectException.class,
                () -> input.workDirectory(input.projectRoot().resolve(".godot/other")));
        // Ancestor directories of the project: refused.
        assertThrows(GodotProjectException.class, () -> input.workDirectory(tempDir));
        // Inside another project's resource tree: refused.
        assertThrows(GodotProjectException.class,
                () -> input.workDirectory(otherProject.getParent().resolve("out")));
        // Inside another project's dot-directory (not its resource tree): allowed.
        var otherCache = otherProject.getParent().resolve(".godot/anything");
        assertEquals(otherCache.toAbsolutePath().normalize(), input.workDirectory(otherCache));
        // Blank output: refused like file mode.
        assertThrows(IllegalArgumentException.class, () -> input.workDirectory(Path.of("")));
    }

    @Test
    void outputCollidingWithExistingFileIsRejected(@TempDir Path tempDir) throws IOException {
        var projectFile = writeProject(tempDir.resolve("game"), "Game", "");
        var input = GodotProjectInput.load(projectFile);

        var existingFile = Files.writeString(tempDir.resolve("occupied"), "x\n");
        assertThrows(GodotProjectException.class, () -> input.workDirectory(existingFile));
        assertThrows(GodotProjectException.class,
                () -> input.workDirectory(existingFile.resolve("nested")));
    }

    @Test
    void symlinkedOutputAncestorsCannotSmuggleResourceTreeWrites(@TempDir Path tempDir)
            throws IOException {
        var projectFile = writeProject(tempDir.resolve("game"), "Game", "");
        var input = GodotProjectInput.load(projectFile);
        try {
            // An external link into the resource tree must not launder a forbidden -o.
            Files.createDirectories(input.projectRoot().resolve("src"));
            Files.createSymbolicLink(tempDir.resolve("into-src"), input.projectRoot().resolve("src"));
            // The same trick pointed at the project ancestor must also fail.
            Files.createSymbolicLink(tempDir.resolve("to-parent"), tempDir);
            // A link that resolves outside every project stays legal.
            Files.createSymbolicLink(tempDir.resolve("outside-link"), tempDir.resolve("elsewhere-target"));
            Files.createDirectories(tempDir.resolve("elsewhere-target"));
        } catch (UnsupportedOperationException | IOException exception) {
            return; // platform without symlink support: nothing to verify
        }

        assertThrows(GodotProjectException.class,
                () -> input.workDirectory(tempDir.resolve("into-src/build")));
        assertThrows(GodotProjectException.class,
                () -> input.workDirectory(tempDir.resolve("to-parent")));
        var outside = input.workDirectory(tempDir.resolve("outside-link/build"));
        assertEquals(tempDir.resolve("outside-link/build").toAbsolutePath().normalize(), outside);
    }

    @Test
    void danglingOutputSymlinkIsRejectedBeforeModuleWork(@TempDir Path tempDir)
            throws IOException {
        var projectFile = writeProject(tempDir.resolve("game"), "Game", "");
        var input = GodotProjectInput.load(projectFile);
        try {
            Files.createSymbolicLink(tempDir.resolve("broken-link"), tempDir.resolve("missing-target"));
        } catch (UnsupportedOperationException | IOException exception) {
            return; // platform without symlink support: nothing to verify
        }

        assertThrows(GodotProjectException.class,
                () -> input.workDirectory(tempDir.resolve("broken-link")));
        assertThrows(GodotProjectException.class,
                () -> input.workDirectory(tempDir.resolve("broken-link/nested")));
    }

    private static Path writeProject(Path root, String name, String extra) throws IOException {
        var projectFile = root.resolve("project.godot");
        Files.createDirectories(projectFile.getParent());
        var content = "config_version=5\n\n[application]\nconfig/name=\"" + name + "\"\n" + extra;
        Files.writeString(projectFile, content, StandardCharsets.UTF_8);
        return projectFile;
    }
}
