package gd.script.gdcc.backend.c.gen;

import gd.script.gdcc.backend.CodegenContext;
import gd.script.gdcc.backend.ProjectInfo;
import gd.script.gdcc.enums.GodotVersion;
import gd.script.gdcc.exception.InvalidInsnException;
import gd.script.gdcc.gdextension.ExtensionAPI;
import gd.script.gdcc.lir.LirBasicBlock;
import gd.script.gdcc.lir.LirClassDef;
import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.lir.LirModule;
import gd.script.gdcc.lir.insn.VariantIsNilInsn;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdBoolType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdVariantType;
import gd.script.gdcc.type.GdVoidType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VariantIsNilInsnGenTest {
    @Test
    @DisplayName("variant_is_nil should compare godot_variant_get_type against NIL")
    void variantIsNilUsesGetTypeNilCompare() {
        var workerClass = new LirClassDef("Worker", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("is_nil");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("value", GdVariantType.VARIANT);
        func.createAndAddVariable("result", GdBoolType.BOOL);
        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new VariantIsNilInsn("result", "value"));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        workerClass.addFunction(func);

        var codegen = new CCodegen();
        codegen.prepare(newContext(List.of(workerClass)), new LirModule("test_module", List.of(workerClass)));

        var body = codegen.generateFuncBody(workerClass, func);
        assertTrue(body.contains("godot_variant_get_type(&$value) == GDEXTENSION_VARIANT_TYPE_NIL"), body);
    }

    @Test
    @DisplayName("non-Variant variant_is_nil operand should fail fast")
    void nonVariantOperandFailsFast() {
        var workerClass = new LirClassDef("Worker", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("is_nil_int");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("value", GdIntType.INT);
        func.createAndAddVariable("result", GdBoolType.BOOL);
        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new VariantIsNilInsn("result", "value"));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        workerClass.addFunction(func);

        var codegen = new CCodegen();
        codegen.prepare(newContext(List.of(workerClass)), new LirModule("test_module", List.of(workerClass)));

        var ex = assertThrows(InvalidInsnException.class, () -> codegen.generateFuncBody(workerClass, func));
        assertTrue(ex.getMessage().contains("must be Variant"), ex.getMessage());
    }

    private CodegenContext newContext(List<LirClassDef> gdccClasses) {
        var api = new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        var classRegistry = new ClassRegistry(api);
        for (var gdccClass : gdccClasses) {
            classRegistry.addGdccClass(gdccClass);
        }
        ProjectInfo projectInfo = new ProjectInfo("TestProject", GodotVersion.V451, Path.of(".")) {
        };
        return new CodegenContext(projectInfo, classRegistry, true);
    }
}
