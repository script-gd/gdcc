package gd.script.gdcc.backend.c.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ScriptResourceSpecTest {
    @TempDir
    private Path tempDir;

    @Test
    void resolveProjectPathShouldMapResPathIntoProjectTree() {
        var spec = new ScriptResourceSpec(
                "res://benchmark/interpreter/algorithm/int_loop.gd",
                "extends Node\n"
        );

        assertEquals(
                tempDir.resolve("benchmark/interpreter/algorithm/int_loop.gd").normalize(),
                spec.resolveProjectPath(tempDir)
        );
    }

    @Test
    void managedScriptRootShouldUseTopTwoSegmentsOfResourcePath() {
        var spec = new ScriptResourceSpec(
                "res://benchmark/interpreter/algorithm/int_loop.gd",
                "extends Node\n"
        );

        assertEquals(
                tempDir.resolve("benchmark/interpreter").normalize(),
                spec.managedScriptRoot(tempDir)
        );
    }

    @Test
    void writeToProjectShouldCreateParentDirectoriesAndWriteContent() throws Exception {
        var spec = new ScriptResourceSpec(
                "res://benchmark/measurement/algorithm/int_loop.gd",
                "extends Node\n"
        );

        spec.writeToProject(tempDir);

        var scriptPath = tempDir.resolve("benchmark/measurement/algorithm/int_loop.gd");
        assertEquals("extends Node\n", Files.readString(scriptPath, StandardCharsets.UTF_8));
    }

    @Test
    void constructorShouldRejectBlankOrNonResResourcePath() {
        var blankError = assertThrows(
                IllegalArgumentException.class,
                () -> new ScriptResourceSpec("  ", "extends Node\n")
        );
        assertEquals("resourcePath must not be blank", blankError.getMessage());

        var invalidPrefixError = assertThrows(
                IllegalArgumentException.class,
                () -> new ScriptResourceSpec("benchmark/interpreter/int_loop.gd", "extends Node\n")
        );
        assertEquals(
                "resourcePath must start with res:// : benchmark/interpreter/int_loop.gd",
                invalidPrefixError.getMessage()
        );
    }
}
