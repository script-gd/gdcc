package dev.superice.gdcc.backend.c.gen;

import dev.superice.gdcc.backend.CodegenContext;
import dev.superice.gdcc.backend.ProjectInfo;
import dev.superice.gdcc.enums.GodotVersion;
import dev.superice.gdcc.gdextension.ExtensionAPI;
import dev.superice.gdcc.gdextension.ExtensionGdClass;
import dev.superice.gdcc.lir.LirBasicBlock;
import dev.superice.gdcc.lir.LirClassDef;
import dev.superice.gdcc.lir.LirFunctionDef;
import dev.superice.gdcc.lir.LirModule;
import dev.superice.gdcc.lir.insn.PackVariantInsn;
import dev.superice.gdcc.lir.insn.ReturnInsn;
import dev.superice.gdcc.lir.insn.UnpackVariantInsn;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdStringType;
import dev.superice.gdcc.type.GdVariantType;
import dev.superice.gdcc.type.GdVoidType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

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
        entry.instructions().add(new UnpackVariantInsn("result", "variant"));
        entry.instructions().add(new ReturnInsn(null));
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
    @DisplayName("unpack_variant to RefCounted object should release and consume owned return")
    void unpackVariantToRefCountedObjectShouldReleaseAndConsumeOwnedReturn() {
        var workerClass = new LirClassDef("Worker", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("unpack_object");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("result", new GdObjectType("RefCounted"));
        func.createAndAddVariable("variant", GdVariantType.VARIANT);

        var entry = new LirBasicBlock("entry");
        entry.instructions().add(new UnpackVariantInsn("result", "variant"));
        entry.instructions().add(new ReturnInsn(null));
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
        assertTrue(body.contains("release_object($result);"));
        assertTrue(body.contains("$result = (godot_RefCounted*)godot_new_Object_with_Variant(&$variant);"));
        assertFalse(body.contains("own_object($result);"));
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
        entry.instructions().add(new PackVariantInsn("result", "value"));
        entry.instructions().add(new ReturnInsn(null));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        workerClass.addFunction(func);

        var module = new LirModule("test_module", List.of(workerClass, targetClass));
        var codegen = newCodegen(module, emptyApi(), List.of(workerClass, targetClass));

        var body = codegen.generateFuncBody(workerClass, func);
        assertTrue(body.contains("godot_Variant_destroy(&$result);"));
        assertTrue(body.contains("$result = godot_new_Variant_with_gdcc_Object($value);"));
        assertFalse(body.contains("godot_new_Variant_with_gdcc_Object($value->_object);"));
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
