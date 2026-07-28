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
import gd.script.gdcc.lir.insn.ObjectIsNullInsn;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdBoolType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdVoidType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObjectIsNullInsnGenTest {
    @Test
    @DisplayName("object_is_null should use gdcc_object_is_null_raw_and_id on engine fat pointers")
    void engineObjectIsNullUsesRawAndId() {
        var nodeClass = new ExtensionGdClass(
                "Node", false, false, "Object", "core",
                List.of(), List.of(), List.of(), List.of(), List.of()
        );
        var api = new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(nodeClass), List.of(), List.of());
        var workerClass = new LirClassDef("Worker", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("is_null");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("obj", new GdObjectType("Node"));
        func.createAndAddVariable("result", GdBoolType.BOOL);
        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new ObjectIsNullInsn("result", "obj"));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        workerClass.addFunction(func);

        var codegen = new CCodegen();
        codegen.prepare(newContext(api, List.of(workerClass)), new LirModule("test_module", List.of(workerClass)));

        var body = codegen.generateFuncBody(workerClass, func);
        assertTrue(body.contains("gdcc_object_is_null_raw_and_id((GDExtensionObjectPtr)($obj).ptr, $obj.instance_id)"), body);
        assertFalse(body.contains("$result = ($obj == NULL);"), body);
        assertFalse(body.contains("godot_object_get_instance_id($obj)"), body);
    }

    @Test
    @DisplayName("GDCC object_is_null should use null-query raw operand and instance_id without object_ptr")
    void gdccObjectIsNullUsesNullQueryRawAndId() {
        var workerClass = new LirClassDef("Worker", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var objectClass = new LirClassDef("MyObject", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("is_null_gdcc");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("obj", new GdObjectType("MyObject"));
        func.createAndAddVariable("result", GdBoolType.BOOL);
        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new ObjectIsNullInsn("result", "obj"));
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
    @DisplayName("non-object object_is_null target should fail fast")
    void nonObjectTargetFailsFast() {
        var workerClass = new LirClassDef("Worker", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("is_null_int");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("value", GdIntType.INT);
        func.createAndAddVariable("result", GdBoolType.BOOL);
        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new ObjectIsNullInsn("result", "value"));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        workerClass.addFunction(func);

        var api = new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        var codegen = new CCodegen();
        codegen.prepare(newContext(api, List.of(workerClass)), new LirModule("test_module", List.of(workerClass)));

        var ex = assertThrows(InvalidInsnException.class, () -> codegen.generateFuncBody(workerClass, func));
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
