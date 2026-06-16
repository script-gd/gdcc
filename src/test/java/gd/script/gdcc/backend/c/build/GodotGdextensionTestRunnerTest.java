package gd.script.gdcc.backend.c.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    public void prepareProjectShouldCopyGeneratedArtifactsIntoProjectBin() throws Exception {
        var projectDir = tempDir.resolve("project");
        var artifactDir = tempDir.resolve("artifacts");
        Files.createDirectories(artifactDir);
        var library = artifactDir.resolve("libgdcc_benchmark_release_x86_64.so");
        var header = artifactDir.resolve("entry.h");
        Files.writeString(library, "native-library", StandardCharsets.UTF_8);
        Files.writeString(header, "native-header", StandardCharsets.UTF_8);

        var runner = new GodotGdextensionTestRunner(projectDir);
        runner.prepareProject(new GodotGdextensionTestRunner.ProjectSetup(
                List.of(library, header),
                List.of(),
                null
        ));

        assertEquals("native-library", Files.readString(projectDir.resolve("bin/libgdcc_benchmark_release_x86_64.so"), StandardCharsets.UTF_8));
        assertEquals("native-header", Files.readString(projectDir.resolve("bin/entry.h"), StandardCharsets.UTF_8));
    }

    @Test
    public void prepareProjectShouldRejectMissingGeneratedArtifact() throws Exception {
        var projectDir = tempDir.resolve("project");
        var artifactDir = tempDir.resolve("artifacts");
        Files.createDirectories(artifactDir);
        var missingLibrary = artifactDir.resolve("libgdcc_benchmark_release_x86_64.so");

        var runner = new GodotGdextensionTestRunner(projectDir);
        var error = assertThrows(IOException.class, () -> runner.prepareProject(new GodotGdextensionTestRunner.ProjectSetup(
                List.of(missingLibrary),
                List.of(),
                null
        )));

        assertTrue(error.getMessage().contains("Artifact not found"));
        assertTrue(error.getMessage().contains(missingLibrary.getFileName().toString()));
    }

    @Test
    public void prepareProjectShouldInstallManagedScriptResourcesAndUseReleaseLibraryKey() throws Exception {
        var projectDir = tempDir.resolve("project");
        var runner = new GodotGdextensionTestRunner(projectDir);
        var setup = new GodotGdextensionTestRunner.ProjectSetup(
                createPortableDynamicLibraryArtifacts(),
                List.of(
                        new GodotGdextensionTestRunner.SceneNodeSpec("CompiledTarget", "BenchmarkCompiled", ".", Map.of()),
                        new GodotGdextensionTestRunner.SceneNodeSpec(
                                "InterpreterTarget",
                                "Node",
                                ".",
                                Map.of("process_mode", "1"),
                                "res://benchmark/interpreter/algorithm/int_loop.gd"
                        )
                ),
                List.of(new ScriptResourceSpec(
                        "res://benchmark/interpreter/algorithm/int_loop.gd",
                        "extends Node\n"
                )),
                null,
                gd.script.gdcc.backend.c.build.COptimizationLevel.RELEASE
        );

        runner.prepareProject(setup);

        var installedScript = projectDir.resolve("benchmark/interpreter/algorithm/int_loop.gd");
        assertEquals("extends Node\n", Files.readString(installedScript, StandardCharsets.UTF_8));
        var sceneText = Files.readString(projectDir.resolve("main.tscn"), StandardCharsets.UTF_8);
        assertTrue(sceneText.contains("path=\"res://benchmark/interpreter/algorithm/int_loop.gd\""));
        assertTrue(sceneText.contains("script = ExtResource(\"2_script\")"));
        assertTrue(sceneText.contains("process_mode = 1"));
        var gdextensionText = Files.readString(projectDir.resolve("GDExtensionTest.gdextension"), StandardCharsets.UTF_8);
        assertTrue(gdextensionText.contains("[libraries]"));
        assertTrue(gdextensionText.contains(".release = "), gdextensionText);
        assertTrue(gdextensionText.contains(".debug = "), gdextensionText);
    }

    @Test
    public void prepareProjectShouldRemoveStaleManagedScriptResourcesBetweenCases() throws Exception {
        var projectDir = tempDir.resolve("project");
        var runner = new GodotGdextensionTestRunner(projectDir);
        var staleScript = projectDir.resolve("benchmark/interpreter/stale_case.gd");
        Files.createDirectories(staleScript.getParent());
        Files.writeString(staleScript, "extends Node\n", StandardCharsets.UTF_8);

        runner.prepareProject(new GodotGdextensionTestRunner.ProjectSetup(
                createPortableDynamicLibraryArtifacts(),
                List.of(),
                List.of(new ScriptResourceSpec(
                        "res://benchmark/interpreter/current_case.gd",
                        "extends Node\n"
                )),
                null,
                gd.script.gdcc.backend.c.build.COptimizationLevel.RELEASE
        ));

        assertFalse(Files.exists(staleScript), "Stale benchmark script should be removed before installing the next case");
        assertTrue(Files.exists(projectDir.resolve("benchmark/interpreter/current_case.gd")));
    }

    @Test
    public void prepareProjectShouldRejectSceneScriptThatWasNotDeclaredAsManagedResource() {
        var projectDir = tempDir.resolve("project");
        var runner = new GodotGdextensionTestRunner(projectDir);

        var error = assertThrows(IOException.class, () -> runner.prepareProject(new GodotGdextensionTestRunner.ProjectSetup(
                createPortableDynamicLibraryArtifacts(),
                List.of(new GodotGdextensionTestRunner.SceneNodeSpec(
                        "InterpreterTarget",
                        "Node",
                        ".",
                        Map.of(),
                        "res://benchmark/interpreter/missing.gd"
                )),
                List.of(),
                null,
                gd.script.gdcc.backend.c.build.COptimizationLevel.RELEASE
        )));

        assertTrue(error.getMessage().contains("missing.gd"));
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
