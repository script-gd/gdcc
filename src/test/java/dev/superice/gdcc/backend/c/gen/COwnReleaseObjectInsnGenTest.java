package dev.superice.gdcc.backend.c.gen;

import dev.superice.gdcc.backend.CodegenContext;
import dev.superice.gdcc.backend.ProjectInfo;
import dev.superice.gdcc.enums.GodotVersion;
import dev.superice.gdcc.enums.LifecycleProvenance;
import dev.superice.gdcc.exception.InvalidInsnException;
import dev.superice.gdcc.gdextension.ExtensionAPI;
import dev.superice.gdcc.gdextension.ExtensionGdClass;
import dev.superice.gdcc.lir.LirBasicBlock;
import dev.superice.gdcc.lir.LirClassDef;
import dev.superice.gdcc.lir.LirFunctionDef;
import dev.superice.gdcc.lir.LirModule;
import dev.superice.gdcc.lir.insn.TryOwnObjectInsn;
import dev.superice.gdcc.lir.insn.TryReleaseObjectInsn;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.type.GdIntType;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdVoidType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class COwnReleaseObjectInsnGenTest {
    @Test
    @DisplayName("GDCC RefCounted object should use own_object with gdcc pointer conversion")
    void gdccRefCountedShouldUseOwnObject() {
        var workerClass = new LirClassDef("Worker", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var objectClass = new LirClassDef("MyObject", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());

        var func = new LirFunctionDef("own_obj");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("obj", new GdObjectType("MyObject"));

        var entry = new LirBasicBlock("entry");
        entry.instructions().add(new TryOwnObjectInsn("obj", LifecycleProvenance.USER_EXPLICIT));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        workerClass.addFunction(func);

        var module = new LirModule("test_module", List.of(workerClass, objectClass));
        var codegen = new CCodegen();
        codegen.prepare(newContext(new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()), List.of(workerClass, objectClass)), module);

        var body = codegen.generateFuncBody(workerClass, func);
        assertTrue(body.contains("own_object(gdcc_object_to_godot_object_ptr($obj, MyObject_object_ptr));"));
        assertFalse(body.contains("try_own_object(gdcc_object_to_godot_object_ptr($obj, MyObject_object_ptr));"));
    }

    @Test
    @DisplayName("Unknown object type should use try_release_object")
    void unknownObjectTypeShouldUseTryReleaseObject() {
        var workerClass = new LirClassDef("Worker", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("release_obj");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("obj", new GdObjectType("UnknownObject"));

        var entry = new LirBasicBlock("entry");
        entry.instructions().add(new TryReleaseObjectInsn("obj", LifecycleProvenance.USER_EXPLICIT));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        workerClass.addFunction(func);

        var module = new LirModule("test_module", List.of(workerClass));
        var codegen = new CCodegen();
        codegen.prepare(newContext(new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()), List.of(workerClass)), module);

        var body = codegen.generateFuncBody(workerClass, func);
        assertTrue(body.contains("try_release_object($obj);"));
    }

    @Test
    @DisplayName("Non-refcounted engine object should emit no own/release calls")
    void nonRefCountedEngineObjectShouldEmitNoCode() {
        var nodeClass = new ExtensionGdClass(
                "Node", false, false, "Object", "core",
                List.of(), List.of(), List.of(), List.of(), List.of()
        );
        var api = new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(nodeClass), List.of(), List.of());

        var workerClass = new LirClassDef("Worker", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("own_node");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("obj", new GdObjectType("Node"));

        var entry = new LirBasicBlock("entry");
        entry.instructions().add(new TryOwnObjectInsn("obj", LifecycleProvenance.USER_EXPLICIT));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        workerClass.addFunction(func);

        var module = new LirModule("test_module", List.of(workerClass));
        var codegen = new CCodegen();
        codegen.prepare(newContext(api, List.of(workerClass)), module);

        var body = codegen.generateFuncBody(workerClass, func);
        assertFalse(body.contains("own_object("));
        assertFalse(body.contains("try_own_object("));
        assertFalse(body.contains("release_object("));
        assertFalse(body.contains("try_release_object("));
    }

    @Test
    @DisplayName("Non-object variable should throw InvalidInsnException")
    void nonObjectVariableShouldThrow() {
        var workerClass = new LirClassDef("Worker", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("invalid_own");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("notObj", GdIntType.INT);

        var entry = new LirBasicBlock("entry");
        entry.instructions().add(new TryOwnObjectInsn("notObj", LifecycleProvenance.USER_EXPLICIT));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        workerClass.addFunction(func);

        var module = new LirModule("test_module", List.of(workerClass));
        var codegen = new CCodegen();
        codegen.prepare(newContext(new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()), List.of(workerClass)), module);

        var ex = assertThrows(InvalidInsnException.class, () -> codegen.generateFuncBody(workerClass, func));
        assertInstanceOf(InvalidInsnException.class, ex);
    }

    @Test
    @DisplayName("AUTO_GENERATED provenance should be rejected for try_own_object")
    void autoGeneratedTryOwnShouldThrow() {
        var workerClass = new LirClassDef("Worker", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("invalid_auto_own");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("obj", new GdObjectType("UnknownObject"));

        var entry = new LirBasicBlock("entry");
        entry.instructions().add(new TryOwnObjectInsn("obj", LifecycleProvenance.AUTO_GENERATED));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        workerClass.addFunction(func);

        var module = new LirModule("test_module", List.of(workerClass));
        var codegen = new CCodegen();
        codegen.prepare(newContext(new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()), List.of(workerClass)), module);

        var ex = assertThrows(InvalidInsnException.class, () -> codegen.generateFuncBody(workerClass, func));
        assertTrue(ex.getMessage().contains("AUTO_GENERATED"));
    }

    @Test
    @DisplayName("INTERNAL provenance on normal variable should throw InvalidInsnException")
    void internalProvenanceOnNormalVariableShouldThrow() {
        var workerClass = new LirClassDef("Worker", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("invalid_internal_release");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("obj", new GdObjectType("UnknownObject"));

        var entry = new LirBasicBlock("entry");
        entry.instructions().add(new TryReleaseObjectInsn("obj", LifecycleProvenance.INTERNAL));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        workerClass.addFunction(func);

        var module = new LirModule("test_module", List.of(workerClass));
        var codegen = new CCodegen();
        codegen.prepare(newContext(new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()), List.of(workerClass)), module);

        var ex = assertThrows(InvalidInsnException.class, () -> codegen.generateFuncBody(workerClass, func));
        assertTrue(ex.getMessage().contains("INTERNAL"));
    }

    private CodegenContext newContext(ExtensionAPI api, List<LirClassDef> gdccClasses) {
        var classRegistry = new ClassRegistry(api);
        for (var gdccClass : gdccClasses) {
            classRegistry.addGdccClass(gdccClass);
        }
        ProjectInfo projectInfo = new ProjectInfo("TestProject", GodotVersion.V451, Path.of(".")) {
        };
        return new CodegenContext(projectInfo, classRegistry, true);
    }
}
