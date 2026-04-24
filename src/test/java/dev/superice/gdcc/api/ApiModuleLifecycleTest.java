package dev.superice.gdcc.api;

import dev.superice.gdcc.backend.c.build.COptimizationLevel;
import dev.superice.gdcc.backend.c.build.TargetPlatform;
import dev.superice.gdcc.enums.GodotVersion;
import dev.superice.gdcc.exception.ApiModuleAlreadyExistsException;
import dev.superice.gdcc.exception.ApiModuleNotFoundException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiModuleLifecycleTest {
    @Test
    void createModuleReturnsNormalizedSnapshotWithDefaultState() {
        var api = new API();

        var snapshot = api.createModule(" demo ", " Demo Module ");

        assertEquals("demo", snapshot.moduleId());
        assertEquals("Demo Module", snapshot.moduleName());
        assertEquals(GodotVersion.V451, snapshot.compileOptions().godotVersion());
        assertNull(snapshot.compileOptions().projectPath());
        assertEquals(COptimizationLevel.DEBUG, snapshot.compileOptions().optimizationLevel());
        assertEquals(TargetPlatform.getNativePlatform(), snapshot.compileOptions().targetPlatform());
        assertFalse(snapshot.compileOptions().strictMode());
        assertEquals(CompileOptions.DEFAULT_OUTPUT_MOUNT_ROOT, snapshot.compileOptions().outputMountRoot());
        assertTrue(snapshot.topLevelCanonicalNameMap().isEmpty());
        assertFalse(snapshot.hasLastCompileResult());
        assertEquals(0, snapshot.rootEntryCount());
        assertThrows(
                UnsupportedOperationException.class,
                () -> snapshot.topLevelCanonicalNameMap().put("Hero", "RuntimeHero")
        );
    }

    @Test
    @SuppressWarnings("DataFlowIssue")
    void createModuleRejectsMissingBoundaryInputs() {
        var api = new API();

        var nullIdError = assertThrows(NullPointerException.class, () -> api.createModule(null, "Demo"));
        assertEquals("moduleId must not be null", nullIdError.getMessage());

        var blankIdError = assertThrows(IllegalArgumentException.class, () -> api.createModule(" \t ", "Demo"));
        assertEquals("moduleId must not be blank", blankIdError.getMessage());

        var nullNameError = assertThrows(NullPointerException.class, () -> api.createModule("demo", null));
        assertEquals("moduleName must not be null", nullNameError.getMessage());

        var blankNameError = assertThrows(IllegalArgumentException.class, () -> api.createModule("demo", "   "));
        assertEquals("moduleName must not be blank", blankNameError.getMessage());
    }

    @Test
    void createModuleRejectsDuplicateNormalizedModuleId() {
        var api = new API();
        api.createModule("demo", "Demo");

        var duplicateError = assertThrows(
                ApiModuleAlreadyExistsException.class,
                () -> api.createModule(" demo ", "Other Demo")
        );

        assertEquals("Module 'demo' already exists", duplicateError.getMessage());
        assertEquals(List.of("demo"), api.listModules().stream().map(ModuleSnapshot::moduleId).toList());
    }

    @Test
    void getModuleReturnsSnapshotAndRejectsMissingModule() {
        var api = new API();
        api.createModule("worker", "Worker Module");

        var snapshot = api.getModule(" worker ");

        assertEquals("worker", snapshot.moduleId());
        assertEquals("Worker Module", snapshot.moduleName());

        var missingError = assertThrows(ApiModuleNotFoundException.class, () -> api.getModule("missing"));
        assertEquals("Module 'missing' does not exist", missingError.getMessage());
    }

    @Test
    void listModulesReturnsSnapshotsSortedByModuleId() {
        var api = new API();
        var beta = api.createModule("beta", "Beta");
        var alpha = api.createModule("alpha", "Alpha");

        var modules = api.listModules();

        assertEquals(List.of("alpha", "beta"), modules.stream().map(ModuleSnapshot::moduleId).toList());
        assertEquals(List.of(alpha, beta), modules);
    }

    @Test
    void deleteModuleReturnsRemovedSnapshotAndAllowsRecreateSameId() {
        var api = new API();
        api.createModule("demo", "Demo");

        var removed = api.deleteModule(" demo ");

        assertEquals("demo", removed.moduleId());
        assertTrue(api.listModules().isEmpty());

        var missingError = assertThrows(ApiModuleNotFoundException.class, () -> api.deleteModule("demo"));
        assertEquals("Module 'demo' does not exist", missingError.getMessage());

        var recreated = api.createModule("demo", "Recreated Demo");
        assertEquals("Recreated Demo", recreated.moduleName());
    }
}
