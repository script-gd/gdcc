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
import dev.superice.gdcc.type.GdArrayType;
import dev.superice.gdcc.type.GdDictionaryType;
import dev.superice.gdcc.type.GdFloatType;
import dev.superice.gdcc.type.GdIntType;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdStringType;
import dev.superice.gdcc.type.GdStringNameType;
import dev.superice.gdcc.type.GdVariantType;
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
        assertTrue(body.contains("$a = godot_new_String_with_String(&$b);"), body);
        assertFalse(body.contains("__gdcc_tmp_string_"), body);
    }

    @Test
    @DisplayName("assign self String should stage a stable carrier before destroy and consume it into the slot")
    void assignSelfStringShouldUseStableCarrier() {
        var workerClass = new LirClassDef("Worker", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("assign_self_string");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("a", GdStringType.STRING);
        addEntryAssignAndReturn(func, new AssignInsn("a", "a"));
        workerClass.addFunction(func);

        var module = new LirModule("test_module", List.of(workerClass));
        var codegen = newCodegen(module, emptyApi(), List.of(workerClass));

        var body = codegen.generateFuncBody(workerClass, func);
        assertTrue(body.contains("godot_String __gdcc_tmp_string_0 = godot_new_String_with_String(&$a);"), body);
        assertTrue(body.contains("godot_String_destroy(&$a);"), body);
        assertTrue(body.contains("$a = __gdcc_tmp_string_0;"), body);
        assertFalse(body.contains("godot_String_destroy(&__gdcc_tmp_string_0);"), body);
    }

    @Test
    @DisplayName("assign self Variant should stage a stable carrier before destroy and consume it into the slot")
    void assignSelfVariantShouldUseStableCarrier() {
        var workerClass = new LirClassDef("Worker", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("assign_self_variant");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("payload", GdVariantType.VARIANT);
        addEntryAssignAndReturn(func, new AssignInsn("payload", "payload"));
        workerClass.addFunction(func);

        var module = new LirModule("test_module", List.of(workerClass));
        var codegen = newCodegen(module, emptyApi(), List.of(workerClass));

        var body = codegen.generateFuncBody(workerClass, func);
        assertTrue(body.contains("godot_Variant __gdcc_tmp_variant_0 = godot_new_Variant_with_Variant(&$payload);"), body);
        assertTrue(body.contains("godot_Variant_destroy(&$payload);"), body);
        assertTrue(body.contains("$payload = __gdcc_tmp_variant_0;"), body);
        assertFalse(body.contains("godot_Variant_destroy(&__gdcc_tmp_variant_0);"), body);
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

    @Test
    @DisplayName("assign should fail-fast when target variable is ref")
    void assignShouldFailWhenTargetIsRefVariable() {
        var workerClass = new LirClassDef("Worker", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("assign_ref_target");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddRefVariable("a", GdIntType.INT);
        func.createAndAddVariable("b", GdIntType.INT);
        addEntryAssignAndReturn(func, new AssignInsn("a", "b"));
        workerClass.addFunction(func);

        var module = new LirModule("test_module", List.of(workerClass));
        var codegen = newCodegen(module, emptyApi(), List.of(workerClass));

        var ex = assertThrows(InvalidInsnException.class, () -> codegen.generateFuncBody(workerClass, func));
        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("Cannot assign to reference variable"), ex.getMessage());
    }

    @Test
    @DisplayName("assign GDCC object to engine object should convert pointer kind")
    void assignGdccObjectToEngineObjectShouldConvertPointerKind() {
        var workerClass = new LirClassDef("Worker", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var gdccNodeClass = new LirClassDef("MyGdccNode", "Node", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("assign_gdcc_to_engine");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("dst", new GdObjectType("Node"));
        func.createAndAddVariable("src", new GdObjectType("MyGdccNode"));
        addEntryAssignAndReturn(func, new AssignInsn("dst", "src"));
        workerClass.addFunction(func);

        var nodeClass = new ExtensionGdClass(
                "Node", false, true, "Object", "core",
                List.of(), List.of(), List.of(), List.of(), List.of()
        );
        var api = new ExtensionAPI(
                null, List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(nodeClass), List.of(), List.of()
        );
        var module = new LirModule("test_module", List.of(workerClass, gdccNodeClass));
        var codegen = newCodegen(module, api, List.of(workerClass, gdccNodeClass));

        var body = codegen.generateFuncBody(workerClass, func);
        assertTrue(body.contains("gdcc_object_to_godot_object_ptr($src, MyGdccNode_object_ptr)"), body);
        assertTrue(body.contains("$dst = gdcc_object_to_godot_object_ptr($src, MyGdccNode_object_ptr);"), body);
    }

    @Test
    @DisplayName("assign unknown object should use try_own_object and try_release_object")
    void assignUnknownObjectShouldUseTryOwnRelease() {
        var workerClass = new LirClassDef("Worker", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("assign_unknown_object");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("dst", new GdObjectType("UnknownObject"));
        func.createAndAddVariable("src", new GdObjectType("UnknownObject"));
        addEntryAssignAndReturn(func, new AssignInsn("dst", "src"));
        workerClass.addFunction(func);

        var module = new LirModule("test_module", List.of(workerClass));
        var codegen = newCodegen(module, emptyApi(), List.of(workerClass));

        var body = codegen.generateFuncBody(workerClass, func);
        assertTrue(body.contains("try_own_object($dst);"), body);
        assertTrue(body.contains("try_release_object(__gdcc_tmp_old_obj_"), body);
        assertFalse(body.contains("\nown_object($dst);\n"), body);
        assertFalse(body.contains("\nrelease_object(__gdcc_tmp_old_obj_"), body);
    }

    @Test
    @DisplayName("assign should allow Array[T] to Array[Variant] covariance")
    void assignShouldAllowArrayToVariantArrayCovariance() {
        var workerClass = new LirClassDef("Worker", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("assign_array_to_variant_array");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("dst", new GdArrayType(GdVariantType.VARIANT));
        func.createAndAddVariable("src", new GdArrayType(GdIntType.INT));
        addEntryAssignAndReturn(func, new AssignInsn("dst", "src"));
        workerClass.addFunction(func);

        var module = new LirModule("test_module", List.of(workerClass));
        var codegen = newCodegen(module, emptyApi(), List.of(workerClass));

        var body = codegen.generateFuncBody(workerClass, func);
        assertTrue(body.contains("godot_new_Array_with_Array(&$src);"), body);
        assertTrue(body.contains("$dst = godot_new_Array_with_Array(&$src);"), body);
        assertFalse(body.contains("__gdcc_tmp_array_"), body);
    }

    @Test
    @DisplayName("assign should allow Array[SubClass] to Array[SuperClass] covariance")
    void assignShouldAllowArrayObjectCovariance() {
        var workerClass = new LirClassDef("Worker", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("assign_array_object_covariance");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("dst", new GdArrayType(new GdObjectType("Node")));
        func.createAndAddVariable("src", new GdArrayType(new GdObjectType("Node3D")));
        addEntryAssignAndReturn(func, new AssignInsn("dst", "src"));
        workerClass.addFunction(func);

        var nodeClass = new ExtensionGdClass(
                "Node", false, true, "Object", "core",
                List.of(), List.of(), List.of(), List.of(), List.of()
        );
        var node3dClass = new ExtensionGdClass(
                "Node3D", false, true, "Node", "core",
                List.of(), List.of(), List.of(), List.of(), List.of()
        );
        var api = new ExtensionAPI(
                null, List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(nodeClass, node3dClass), List.of(), List.of()
        );
        var module = new LirModule("test_module", List.of(workerClass));
        var codegen = newCodegen(module, api, List.of(workerClass));

        var body = codegen.generateFuncBody(workerClass, func);
        assertTrue(body.contains("godot_new_Array_with_Array(&$src);"), body);
        assertTrue(body.contains("$dst = godot_new_Array_with_Array(&$src);"), body);
        assertFalse(body.contains("__gdcc_tmp_array_"), body);
    }

    @Test
    @DisplayName("assign should still reject non-covariant Array element mismatch")
    void assignShouldRejectNonCovariantArrayElementMismatch() {
        var workerClass = new LirClassDef("Worker", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("assign_array_mismatch");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("dst", new GdArrayType(GdFloatType.FLOAT));
        func.createAndAddVariable("src", new GdArrayType(GdIntType.INT));
        addEntryAssignAndReturn(func, new AssignInsn("dst", "src"));
        workerClass.addFunction(func);

        var module = new LirModule("test_module", List.of(workerClass));
        var codegen = newCodegen(module, emptyApi(), List.of(workerClass));

        var ex = assertThrows(InvalidInsnException.class, () -> codegen.generateFuncBody(workerClass, func));
        assertInstanceOf(InvalidInsnException.class, ex);
    }

    @Test
    @DisplayName("assign should allow Dictionary[K, V] to Dictionary[Variant, Variant] covariance")
    void assignShouldAllowDictionaryToVariantDictionaryCovariance() {
        var workerClass = new LirClassDef("Worker", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("assign_dictionary_to_variant_dictionary");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("dst", new GdDictionaryType(GdVariantType.VARIANT, GdVariantType.VARIANT));
        func.createAndAddVariable("src", new GdDictionaryType(GdStringNameType.STRING_NAME, GdIntType.INT));
        addEntryAssignAndReturn(func, new AssignInsn("dst", "src"));
        workerClass.addFunction(func);

        var module = new LirModule("test_module", List.of(workerClass));
        var codegen = newCodegen(module, emptyApi(), List.of(workerClass));

        var body = codegen.generateFuncBody(workerClass, func);
        assertTrue(body.contains("godot_new_Dictionary_with_Dictionary(&$src);"), body);
        assertTrue(body.contains("$dst = godot_new_Dictionary_with_Dictionary(&$src);"), body);
        assertFalse(body.contains("__gdcc_tmp_dictionary_"), body);
    }

    @Test
    @DisplayName("assign should allow Dictionary value object covariance")
    void assignShouldAllowDictionaryObjectValueCovariance() {
        var workerClass = new LirClassDef("Worker", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("assign_dictionary_object_covariance");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("dst", new GdDictionaryType(GdStringNameType.STRING_NAME, new GdObjectType("Node")));
        func.createAndAddVariable("src", new GdDictionaryType(GdStringNameType.STRING_NAME, new GdObjectType("Node3D")));
        addEntryAssignAndReturn(func, new AssignInsn("dst", "src"));
        workerClass.addFunction(func);

        var nodeClass = new ExtensionGdClass(
                "Node", false, true, "Object", "core",
                List.of(), List.of(), List.of(), List.of(), List.of()
        );
        var node3dClass = new ExtensionGdClass(
                "Node3D", false, true, "Node", "core",
                List.of(), List.of(), List.of(), List.of(), List.of()
        );
        var api = new ExtensionAPI(
                null, List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(nodeClass, node3dClass), List.of(), List.of()
        );
        var module = new LirModule("test_module", List.of(workerClass));
        var codegen = newCodegen(module, api, List.of(workerClass));

        var body = codegen.generateFuncBody(workerClass, func);
        assertTrue(body.contains("godot_new_Dictionary_with_Dictionary(&$src);"), body);
        assertTrue(body.contains("$dst = godot_new_Dictionary_with_Dictionary(&$src);"), body);
        assertFalse(body.contains("__gdcc_tmp_dictionary_"), body);
    }

    @Test
    @DisplayName("assign should still reject non-covariant Dictionary key/value mismatch")
    void assignShouldRejectNonCovariantDictionaryMismatch() {
        var workerClass = new LirClassDef("Worker", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("assign_dictionary_mismatch");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("dst", new GdDictionaryType(GdStringType.STRING, GdFloatType.FLOAT));
        func.createAndAddVariable("src", new GdDictionaryType(GdIntType.INT, GdIntType.INT));
        addEntryAssignAndReturn(func, new AssignInsn("dst", "src"));
        workerClass.addFunction(func);

        var module = new LirModule("test_module", List.of(workerClass));
        var codegen = newCodegen(module, emptyApi(), List.of(workerClass));

        var ex = assertThrows(InvalidInsnException.class, () -> codegen.generateFuncBody(workerClass, func));
        assertInstanceOf(InvalidInsnException.class, ex);
    }

    private void addEntryAssignAndReturn(LirFunctionDef func, AssignInsn assignInsn) {
        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(assignInsn);
        entry.appendInstruction(new ReturnInsn(null));
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
