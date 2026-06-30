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
import gd.script.gdcc.lir.insn.PackVariantInsn;
import gd.script.gdcc.lir.insn.ReturnInsn;
import gd.script.gdcc.lir.insn.UnpackVariantInsn;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdArrayType;
import gd.script.gdcc.type.GdDictionaryType;
import gd.script.gdcc.type.GdccForRangeIterType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdStringNameType;
import gd.script.gdcc.type.GdStringType;
import gd.script.gdcc.type.GdVariantType;
import gd.script.gdcc.type.GdVoidType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CPackUnpackVariantInsnGenTest {
    @Test
    @DisplayName("unpack_variant to String should use assignment semantics")
    void unpackVariantToStringShouldUseAssignmentSemantics() {
        var workerClass = new LirClassDef("Worker", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("unpack_string");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("result", GdStringType.STRING);
        func.createAndAddVariable("variant", GdVariantType.VARIANT);

        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new UnpackVariantInsn("result", "variant"));
        entry.appendInstruction(new ReturnInsn(null));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        workerClass.addFunction(func);

        var module = new LirModule("test_module", List.of(workerClass));
        var codegen = newCodegen(module, emptyApi(), List.of(workerClass));

        var body = codegen.generateFuncBody(workerClass, func);
        assertTrue(body.contains("godot_String_destroy(&$result);"));
        assertTrue(body.contains("$result = godot_new_String_with_Variant(&$variant);"));
    }

    @Test
    @DisplayName("unpack_variant to int should use numeric Variant helper")
    void unpackVariantToIntShouldUseNumericVariantHelper() {
        var workerClass = new LirClassDef("Worker", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("unpack_int");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("result", GdIntType.INT);
        func.createAndAddVariable("variant", GdVariantType.VARIANT);

        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new UnpackVariantInsn("result", "variant"));
        entry.appendInstruction(new ReturnInsn(null));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        workerClass.addFunction(func);

        var module = new LirModule("test_module", List.of(workerClass));
        var codegen = newCodegen(module, emptyApi(), List.of(workerClass));

        var body = codegen.generateFuncBody(workerClass, func);
        assertTrue(body.contains("$result = godot_new_int_with_Variant(&$variant);"));
    }

    @Test
    @DisplayName("unpack_variant to RefCounted object should release and consume owned return")
    void unpackVariantToRefCountedObjectShouldReleaseAndConsumeOwnedReturn() {
        var workerClass = new LirClassDef("Worker", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("unpack_object");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("result", new GdObjectType("RefCounted"));
        func.createAndAddVariable("variant", GdVariantType.VARIANT);

        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new UnpackVariantInsn("result", "variant"));
        entry.appendInstruction(new ReturnInsn(null));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        workerClass.addFunction(func);

        var api = new ExtensionAPI(
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(new ExtensionGdClass("RefCounted", true, true, "Object", "core", List.of(), List.of(), List.of(), List.of(), List.of())),
                List.of(),
                List.of()
        );
        var module = new LirModule("test_module", List.of(workerClass));
        var codegen = newCodegen(module, api, List.of(workerClass));

        var body = codegen.generateFuncBody(workerClass, func);
        assertTrue(body.contains("__gdcc_tmp_old_obj_"), "Should capture old object into temp before overwriting");
        assertTrue(body.contains("= $result;"), "Captured old temp should be initialized from result slot");
        assertTrue(body.contains("$result = (godot_RefCounted*)godot_new_Object_with_Variant(&$variant);"));
        assertFalse(body.contains("own_object($result)"), "OWNED return should not be owned again");
        assertTrue(body.contains("release_object(__gdcc_tmp_old_obj_"), "Should release captured old value");

        var captureIndex = body.indexOf("= $result;");
        var assignIndex = body.indexOf("$result = (godot_RefCounted*)godot_new_Object_with_Variant(&$variant);");
        var releaseOldIndex = body.indexOf("release_object(__gdcc_tmp_old_obj_");
        assertTrue(captureIndex < assignIndex, "Capture should happen before assignment");
        assertTrue(assignIndex < releaseOldIndex, "Assignment should happen before release of old value");
    }

    @Test
    @DisplayName("unpack_variant to typed Array should use normalized Array symbol without generic suffix")
    void unpackVariantToTypedArrayShouldUseNormalizedArraySymbol() {
        var workerClass = new LirClassDef("Worker", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("unpack_typed_array");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("result", new GdArrayType(GdStringNameType.STRING_NAME));
        func.createAndAddVariable("variant", GdVariantType.VARIANT);

        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new UnpackVariantInsn("result", "variant"));
        entry.appendInstruction(new ReturnInsn(null));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        workerClass.addFunction(func);

        var module = new LirModule("test_module", List.of(workerClass));
        var codegen = newCodegen(module, emptyApi(), List.of(workerClass));

        var body = codegen.generateFuncBody(workerClass, func);
        assertTrue(body.contains("$result = godot_new_Array_with_Variant(&$variant);"));
        assertFalse(body.contains("godot_new_Array["));
    }

    @Test
    @DisplayName("pack_variant from GDCC object should use gdcc object pack path")
    void packVariantFromGdccObjectShouldUseObjectPackPath() {
        var targetClass = new LirClassDef("TargetClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var workerClass = new LirClassDef("Worker", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("pack_object");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("result", GdVariantType.VARIANT);
        func.createAndAddVariable("value", new GdObjectType("TargetClass"));

        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new PackVariantInsn("result", "value"));
        entry.appendInstruction(new ReturnInsn(null));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        workerClass.addFunction(func);

        var module = new LirModule("test_module", List.of(workerClass, targetClass));
        var codegen = newCodegen(module, emptyApi(), List.of(workerClass, targetClass));

        var body = codegen.generateFuncBody(workerClass, func);
        assertTrue(body.contains("godot_Variant_destroy(&$result);"));
        assertTrue(body.contains("$result = gdcc_new_Variant_with_gdcc_Object($value);"));
        assertFalse(body.contains("godot_new_Variant_with_Object("));
    }

    @Test
    @DisplayName("pack_variant from typed Array should use normalized Array symbol without generic suffix")
    void packVariantFromTypedArrayShouldUseNormalizedArraySymbol() {
        var workerClass = new LirClassDef("Worker", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("pack_typed_array");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("result", GdVariantType.VARIANT);
        func.createAndAddVariable("value", new GdArrayType(GdStringNameType.STRING_NAME));

        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new PackVariantInsn("result", "value"));
        entry.appendInstruction(new ReturnInsn(null));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        workerClass.addFunction(func);

        var module = new LirModule("test_module", List.of(workerClass));
        var codegen = newCodegen(module, emptyApi(), List.of(workerClass));

        var body = codegen.generateFuncBody(workerClass, func);
        assertTrue(body.contains("$result = godot_new_Variant_with_Array(&$value);"));
        assertFalse(body.contains("godot_new_Variant_with_Array["));
    }

    @Test
    @DisplayName("unpack_variant to typed Dictionary should use normalized Dictionary symbol without generic suffix")
    void unpackVariantToTypedDictionaryShouldUseNormalizedDictionarySymbol() {
        var workerClass = new LirClassDef("Worker", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("unpack_typed_dictionary");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("result", new GdDictionaryType(GdStringNameType.STRING_NAME, GdVariantType.VARIANT));
        func.createAndAddVariable("variant", GdVariantType.VARIANT);

        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new UnpackVariantInsn("result", "variant"));
        entry.appendInstruction(new ReturnInsn(null));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        workerClass.addFunction(func);

        var module = new LirModule("test_module", List.of(workerClass));
        var codegen = newCodegen(module, emptyApi(), List.of(workerClass));

        var body = codegen.generateFuncBody(workerClass, func);
        assertTrue(body.contains("$result = godot_new_Dictionary_with_Variant(&$variant);"));
        assertFalse(body.contains("godot_new_Dictionary["));
    }

    @Test
    @DisplayName("pack_variant should reject compiler-only source")
    void packVariantShouldRejectCompilerOnlySource() {
        var workerClass = new LirClassDef("Worker", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("pack_compiler_only");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("result", GdVariantType.VARIANT);
        func.createAndAddVariable("value", GdccForRangeIterType.FOR_RANGE_ITER);

        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new PackVariantInsn("result", "value"));
        entry.appendInstruction(new ReturnInsn(null));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        workerClass.addFunction(func);

        var module = new LirModule("test_module", List.of(workerClass));
        var codegen = newCodegen(module, emptyApi(), List.of(workerClass));

        var ex = assertThrows(InvalidInsnException.class, () -> codegen.generateFuncBody(workerClass, func));
        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("compiler-only type leaked into Variant pack source variable 'value'"), ex.getMessage());
    }

    @Test
    @DisplayName("unpack_variant should reject compiler-only target")
    void unpackVariantShouldRejectCompilerOnlyTarget() {
        var workerClass = new LirClassDef("Worker", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("unpack_compiler_only");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("result", GdccForRangeIterType.FOR_RANGE_ITER);
        func.createAndAddVariable("variant", GdVariantType.VARIANT);

        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new UnpackVariantInsn("result", "variant"));
        entry.appendInstruction(new ReturnInsn(null));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        workerClass.addFunction(func);

        var module = new LirModule("test_module", List.of(workerClass));
        var codegen = newCodegen(module, emptyApi(), List.of(workerClass));

        var ex = assertThrows(InvalidInsnException.class, () -> codegen.generateFuncBody(workerClass, func));
        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("compiler-only type leaked into Variant unpack target: GdccForRangeIter"), ex.getMessage());
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
