package gd.script.gdcc.backend.c.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class GodotGdextensionTestRunnerTest {
    @TempDir
    private Path tempDir;

    @Test
    public void prepareProjectShouldWriteGodotExtensionListConfig() throws Exception {
        var projectDir = tempDir.resolve("project");
        var staleExtensionListPath = projectDir.resolve(".godot").resolve("extension_list.cfg");
        Files.createDirectories(staleExtensionListPath.getParent());
        Files.writeString(staleExtensionListPath, "res://stale.gdextension\n", StandardCharsets.UTF_8);

        var runner = new GodotGdextensionTestRunner(projectDir);
        runner.prepareProject(new GodotGdextensionTestRunner.ProjectSetup(createPortableDynamicLibraryArtifacts(), List.of(), null));

        assertTrue(Files.isDirectory(projectDir.resolve(".godot")));
        assertTrue(Files.exists(projectDir.resolve("GDExtensionTest.gdextension")));
        assertTrue(Files.exists(projectDir.resolve("main.tscn")));
        assertEquals(
                "res://GDExtensionTest.gdextension\n",
                Files.readString(staleExtensionListPath, StandardCharsets.UTF_8)
        );
    }

    @Test
    public void prepareProjectShouldAcceptWasmArtifactAsDynamicLibrary() throws Exception {
        var projectDir = tempDir.resolve("project");
        var artifactDir = tempDir.resolve("artifacts");
        Files.createDirectories(artifactDir);
        var wasmLibrary = artifactDir.resolve("demo_debug_wasm32.wasm");
        Files.writeString(wasmLibrary, "", StandardCharsets.UTF_8);

        var runner = new GodotGdextensionTestRunner(projectDir);
        runner.prepareProject(new GodotGdextensionTestRunner.ProjectSetup(List.of(wasmLibrary), List.of(), null));

        assertTrue(Files.exists(projectDir.resolve("bin/demo_debug_wasm32.wasm")));
        var gdextensionText = Files.readString(projectDir.resolve("GDExtensionTest.gdextension"), StandardCharsets.UTF_8);
        assertTrue(gdextensionText.contains("res://bin/demo_debug_wasm32.wasm"), gdextensionText);
    }

    @Test
    public void defaultRunOptionsShouldAllowPerRunFrameBudgetOverride() {
        var runOptions = GodotGdextensionTestRunner.defaultRunOptions(true);
        var customRunOptions = runOptions.withQuitAfterFrames(60);

        assertEquals(10, runOptions.quitAfterFrames());
        assertTrue(runOptions.headless());
        assertEquals(60, customRunOptions.quitAfterFrames());
        assertTrue(customRunOptions.headless());
        assertEquals(runOptions.processTimeout(), customRunOptions.processTimeout());
        assertEquals(runOptions.forceKillDelay(), customRunOptions.forceKillDelay());
    }

    private List<Path> createPortableDynamicLibraryArtifacts() throws Exception {
        var artifactDir = tempDir.resolve("artifacts");
        Files.createDirectories(artifactDir);
        var linuxLibrary = artifactDir.resolve("libgdcc_test.so");
        var windowsLibrary = artifactDir.resolve("gdcc_test.dll");
        var macosLibrary = artifactDir.resolve("libgdcc_test.dylib");
        Files.writeString(linuxLibrary, "", StandardCharsets.UTF_8);
        Files.writeString(windowsLibrary, "", StandardCharsets.UTF_8);
        Files.writeString(macosLibrary, "", StandardCharsets.UTF_8);
        return List.of(linuxLibrary, windowsLibrary, macosLibrary);
    }
}
