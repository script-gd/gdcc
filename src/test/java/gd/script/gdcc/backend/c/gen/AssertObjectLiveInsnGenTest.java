package gd.script.gdcc.backend.c.gen;

import gd.script.gdcc.backend.CodegenContext;
import gd.script.gdcc.backend.ProjectInfo;
import gd.script.gdcc.enums.GodotVersion;
import gd.script.gdcc.exception.InvalidInsnException;
import gd.script.gdcc.gdextension.ExtensionAPI;
import gd.script.gdcc.gdextension.ExtensionGdClass;
import gd.script.gdcc.lir.LirBasicBlock;
import gd.script.gdcc.lir.LirClassDef;
import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.lir.LirModule;
import gd.script.gdcc.lir.insn.AssertObjectLiveInsn;
import gd.script.gdcc.lir.insn.GotoInsn;
import gd.script.gdcc.lir.insn.ReturnInsn;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdSignalType;
import gd.script.gdcc.type.GdVoidType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssertObjectLiveInsnGenTest {
    @Test
    @DisplayName("engine object guard should hard-fail via gdcc_object_is_null_raw_and_id")
    void engineObjectGuardUsesRawAndIdCheck() {
        var nodeClass = new ExtensionGdClass(
                "Node", false, false, "Object", "core",
                List.of(), List.of(), List.of(), List.of(), List.of()
        );
        var api = new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(nodeClass), List.of(), List.of());
        var workerClass = new LirClassDef("Worker", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("assert_node");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("obj", new GdObjectType("Node"));
        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new AssertObjectLiveInsn("obj"));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        workerClass.addFunction(func);

        var codegen = new CCodegen();
        codegen.prepare(newContext(api, List.of(workerClass)), new LirModule("test_module", List.of(workerClass)));

        var body = codegen.generateFuncBody(workerClass, func);
        assertTrue(body.contains("gdcc_object_is_null_raw_and_id((GDExtensionObjectPtr)($obj).ptr, $obj.instance_id)"), body);
        assertTrue(body.contains("assert_object_live failed: object 'obj' is null or freed"), body);
        assertTrue(body.contains("goto __finally__;"), body);
        assertFalse(body.contains("godot_object_get_instance_id($obj)"), body);
        assertFalse(body.contains("_return_val"), body);
    }

    @Test
    @DisplayName("non-void Signal return should publish a default Signal before leaving the live-fail edge")
    void signalReturnPublishesDefaultOnLiveFail() {
        var nodeClass = new ExtensionGdClass(
                "Node", false, false, "Object", "core",
                List.of(), List.of(), List.of(), List.of(), List.of()
        );
        var api = new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(nodeClass), List.of(), List.of());
        var workerClass = new LirClassDef("Worker", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("assert_node_return_signal");
        func.setReturnType(new GdSignalType());
        func.createAndAddVariable("obj", new GdObjectType("Node"));
        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new AssertObjectLiveInsn("obj"));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        workerClass.addFunction(func);

        var codegen = new CCodegen();
        codegen.prepare(newContext(api, List.of(workerClass)), new LirModule("test_module", List.of(workerClass)));

        var body = codegen.generateFuncBody(workerClass, func);
        assertTrue(body.contains("gdcc_object_is_null_raw_and_id((GDExtensionObjectPtr)($obj).ptr, $obj.instance_id)"), body);
        assertTrue(body.contains("assert_object_live failed: object 'obj' is null or freed"), body);
        assertTrue(body.contains("godot_new_Signal()"), body);
        assertTrue(body.contains("_return_val = godot_new_Signal_with_Signal("), body);
        assertTrue(body.contains("goto __finally__;"), body);
    }

    @Test
    @DisplayName("GDCC object guard should use null-query raw operand and instance_id without object_ptr")
    void gdccObjectGuardUsesNullQueryRawAndId() {
        var workerClass = new LirClassDef("Worker", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var objectClass = new LirClassDef("MyObject", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("assert_gdcc");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("obj", new GdObjectType("MyObject"));
        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new AssertObjectLiveInsn("obj"));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        workerClass.addFunction(func);

        var api = new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        var codegen = new CCodegen();
        codegen.prepare(newContext(api, List.of(workerClass, objectClass)), new LirModule("test_module", List.of(workerClass, objectClass)));

        var body = codegen.generateFuncBody(workerClass, func);
        assertTrue(body.contains("gdcc_object_is_null_raw_and_id((GDExtensionObjectPtr)($obj).ptr, $obj.instance_id)"), body);
        assertFalse(body.contains("MyObject_object_ptr"), body);
        assertFalse(body.contains("godot_object_get_instance_id($obj)"), body);
    }

    @Test
    @DisplayName("assert_object_live inside __finally__ should fail fast instead of self-jumping")
    void assertInsideFinallyFailsFast() {
        var nodeClass = new ExtensionGdClass(
                "Node", false, false, "Object", "core",
                List.of(), List.of(), List.of(), List.of(), List.of()
        );
        var api = new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(nodeClass), List.of(), List.of());
        var workerClass = new LirClassDef("Worker", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("assert_in_finally");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("obj", new GdObjectType("Node"));

        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new GotoInsn("__finally__"));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");

        var finallyBlock = new LirBasicBlock("__finally__");
        finallyBlock.appendInstruction(new AssertObjectLiveInsn("obj"));
        finallyBlock.appendInstruction(new ReturnInsn(null));
        func.addBasicBlock(finallyBlock);
        workerClass.addFunction(func);

        var codegen = new CCodegen();
        codegen.prepare(newContext(api, List.of(workerClass)), new LirModule("test_module", List.of(workerClass)));

        var ex = assertThrows(InvalidInsnException.class, () -> codegen.generateFuncBody(workerClass, func));
        assertTrue(ex.getMessage().contains("__finally__"), ex.getMessage());
    }

    @Test
    @DisplayName("non-object assert_object_live target should fail fast")
    void nonObjectTargetFailsFast() {
        var workerClass = new LirClassDef("Worker", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("assert_int");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("value", GdIntType.INT);
        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new AssertObjectLiveInsn("value"));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        workerClass.addFunction(func);

        var api = new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        var codegen = new CCodegen();
        codegen.prepare(newContext(api, List.of(workerClass)), new LirModule("test_module", List.of(workerClass)));

        var ex = assertThrows(InvalidInsnException.class, () -> codegen.generateFuncBody(workerClass, func));
        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("must be an object type"), ex.getMessage());
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
