package dev.superice.gdcc.backend.c.gen;

import dev.superice.gdcc.backend.CodegenContext;
import dev.superice.gdcc.backend.ProjectInfo;
import dev.superice.gdcc.enums.GodotVersion;
import dev.superice.gdcc.exception.InvalidInsnException;
import dev.superice.gdcc.gdextension.ExtensionAPI;
import dev.superice.gdcc.gdextension.ExtensionGdClass;
import dev.superice.gdcc.lir.LirBasicBlock;
import dev.superice.gdcc.lir.LirClassDef;
import dev.superice.gdcc.lir.LirFunctionDef;
import dev.superice.gdcc.lir.LirModule;
import dev.superice.gdcc.lir.insn.AssignInsn;
import dev.superice.gdcc.lir.insn.ReturnInsn;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.type.GdIntType;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdStringType;
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

public class CAssignInsnGenTest {
    @Test
    @DisplayName("assign int should generate direct assignment")
    void assignIntShouldGenerateDirectAssignment() {
        var workerClass = new LirClassDef("Worker", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("assign_int");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("a", GdIntType.INT);
        func.createAndAddVariable("b", GdIntType.INT);
        addEntryAssignAndReturn(func, new AssignInsn("a", "b"));
        workerClass.addFunction(func);

        var module = new LirModule("test_module", List.of(workerClass));
        var codegen = newCodegen(module, emptyApi(), List.of(workerClass));

        var body = codegen.generateFuncBody(workerClass, func);
        assertTrue(body.contains("$a = $b;"), body);
    }

    @Test
    @DisplayName("assign String should copy rhs and destroy old target value")
    void assignStringShouldCopyAndDestroyOldValue() {
        var workerClass = new LirClassDef("Worker", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("assign_string");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("a", GdStringType.STRING);
        func.createAndAddVariable("b", GdStringType.STRING);
        addEntryAssignAndReturn(func, new AssignInsn("a", "b"));
        workerClass.addFunction(func);

        var module = new LirModule("test_module", List.of(workerClass));
        var codegen = newCodegen(module, emptyApi(), List.of(workerClass));

        var body = codegen.generateFuncBody(workerClass, func);
        assertTrue(body.contains("godot_String_destroy(&$a);"), body);
        assertTrue(body.contains("godot_new_String_with_String(&$b);"), body);
        assertTrue(body.contains("$a = __gdcc_tmp_string_"), body);
    }

    @Test
    @DisplayName("assign RefCounted object should follow capture-assign-own-release order")
    void assignRefCountedObjectShouldFollowObjectSlotWriteSemantics() {
        var workerClass = new LirClassDef("Worker", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("assign_object");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("dst", new GdObjectType("RefCounted"));
        func.createAndAddVariable("src", new GdObjectType("RefCounted"));
        addEntryAssignAndReturn(func, new AssignInsn("dst", "src"));
        workerClass.addFunction(func);

        var refCountedClass = new ExtensionGdClass(
                "RefCounted", true, true, "Object", "core",
                List.of(), List.of(), List.of(), List.of(), List.of()
        );
        var api = new ExtensionAPI(
                null, List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(refCountedClass), List.of(), List.of()
        );
        var module = new LirModule("test_module", List.of(workerClass));
        var codegen = newCodegen(module, api, List.of(workerClass));

        var body = codegen.generateFuncBody(workerClass, func);
        assertTrue(body.contains("__gdcc_tmp_old_obj_"), body);
        assertTrue(body.contains(" = $dst;"), body);
        assertTrue(body.contains("$dst = $src;"), body);
        assertTrue(body.contains("own_object($dst);"), body);
        assertTrue(body.contains("release_object(__gdcc_tmp_old_obj_"), body);
        assertFalse(body.contains("try_own_object($dst);"), body);
        assertFalse(body.contains("try_release_object(__gdcc_tmp_old_obj_"), body);

        var captureIndex = body.indexOf(" = $dst;");
        var assignIndex = body.indexOf("$dst = $src;");
        var ownIndex = body.indexOf("own_object($dst);");
        var releaseIndex = body.indexOf("release_object(__gdcc_tmp_old_obj_");
        assertTrue(captureIndex >= 0, body);
        assertTrue(assignIndex >= 0, body);
        assertTrue(ownIndex >= 0, body);
        assertTrue(releaseIndex >= 0, body);
        assertTrue(captureIndex < assignIndex, body);
        assertTrue(assignIndex < ownIndex, body);
        assertTrue(ownIndex < releaseIndex, body);
    }

    @Test
    @DisplayName("assign should fail-fast when source type is not assignable to target type")
    void assignShouldFailWhenTypesAreNotAssignable() {
        var workerClass = new LirClassDef("Worker", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("assign_mismatch");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("a", GdIntType.INT);
        func.createAndAddVariable("b", GdStringType.STRING);
        addEntryAssignAndReturn(func, new AssignInsn("a", "b"));
        workerClass.addFunction(func);

        var module = new LirModule("test_module", List.of(workerClass));
        var codegen = newCodegen(module, emptyApi(), List.of(workerClass));

        var ex = assertThrows(InvalidInsnException.class, () -> codegen.generateFuncBody(workerClass, func));
        assertInstanceOf(InvalidInsnException.class, ex);
    }

    private void addEntryAssignAndReturn(LirFunctionDef func, AssignInsn assignInsn) {
        var entry = new LirBasicBlock("entry");
        entry.instructions().add(assignInsn);
        entry.instructions().add(new ReturnInsn(null));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
    }

    private ExtensionAPI emptyApi() {
        return new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private CCodegen newCodegen(LirModule module, ExtensionAPI api, List<LirClassDef> gdccClasses) {
        var classRegistry = new ClassRegistry(api);
        for (var gdccClass : gdccClasses) {
            classRegistry.addGdccClass(gdccClass);
        }
        ProjectInfo projectInfo = new ProjectInfo("TestProject", GodotVersion.V451, Path.of(".")) {
        };
        var ctx = new CodegenContext(projectInfo, classRegistry);
        var codegen = new CCodegen();
        codegen.prepare(ctx, module);
        return codegen;
    }
}

