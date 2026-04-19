package dev.superice.gdcc.backend.c.gen;

import dev.superice.gdcc.backend.CodegenContext;
import dev.superice.gdcc.backend.ProjectInfo;
import dev.superice.gdcc.enums.GodotVersion;
import dev.superice.gdcc.exception.InvalidInsnException;
import dev.superice.gdcc.gdextension.ExtensionAPI;
import dev.superice.gdcc.lir.LirBasicBlock;
import dev.superice.gdcc.lir.LirClassDef;
import dev.superice.gdcc.lir.LirFunctionDef;
import dev.superice.gdcc.lir.LirModule;
import dev.superice.gdcc.lir.insn.LiteralBoolInsn;
import dev.superice.gdcc.lir.insn.LiteralFloatInsn;
import dev.superice.gdcc.lir.insn.LiteralIntInsn;
import dev.superice.gdcc.lir.insn.LiteralNilInsn;
import dev.superice.gdcc.lir.insn.LiteralNullInsn;
import dev.superice.gdcc.lir.insn.LiteralStringInsn;
import dev.superice.gdcc.lir.insn.LiteralStringNameInsn;
import dev.superice.gdcc.lir.insn.NewDataInstruction;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.type.GdBoolType;
import dev.superice.gdcc.type.GdFloatType;
import dev.superice.gdcc.type.GdIntType;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdStringNameType;
import dev.superice.gdcc.type.GdStringType;
import dev.superice.gdcc.type.GdType;
import dev.superice.gdcc.type.GdVariantType;
import dev.superice.gdcc.type.GdVoidType;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CNewDataInsnGenTest {
    @Test
    @DisplayName("literal_bool should use assignExpr")
    void literalBoolShouldAssignExpr() {
        var body = generateBody("b", GdBoolType.BOOL, false, new LiteralBoolInsn("b", true));
        assertTrue(body.contains("$b = true;"));
    }

    @Test
    @DisplayName("literal_int should use assignExpr")
    void literalIntShouldAssignExpr() {
        var body = generateBody("i", GdIntType.INT, false, new LiteralIntInsn("i", 7));
        assertTrue(body.contains("$i = 7;"));
    }

    @Test
    @DisplayName("literal_float should use assignExpr")
    void literalFloatShouldAssignExpr() {
        var body = generateBody("f", GdFloatType.FLOAT, false, new LiteralFloatInsn("f", 1.5));
        assertTrue(body.contains("$f = 1.5;"));
    }

    @Test
    @DisplayName("literal_null should assign NULL to object variable")
    void literalNullShouldAssignNull() {
        var body = generateBody("obj", new GdObjectType("Node"), false, new LiteralNullInsn("obj"));
        assertTrue(body.contains("$obj = NULL;"));
    }

    @Test
    @DisplayName("literal_nil should call godot_new_Variant_nil for non-ref")
    void literalNilShouldCallAssignForNonRef() {
        var body = generateBody("v", GdVariantType.VARIANT, false, new LiteralNilInsn("v"));
        assertTrue(body.contains("godot_new_Variant_nil()"));
    }

    @Test
    @DisplayName("literal_string should call non-ref constructor for non-ref variable")
    void literalStringShouldCallAssignForNonRef() {
        var body = generateBody("s", GdStringType.STRING, false, new LiteralStringInsn("s", "hello"));
        assertTrue(body.contains("godot_new_String_with_utf8_chars(u8\"hello\")"));
    }

    @Test
    @DisplayName("literal_string_name should call non-ref constructor for non-ref variable")
    void literalStringNameShouldCallAssignForNonRef() {
        var body = generateBody("sn", GdStringNameType.STRING_NAME, false, new LiteralStringNameInsn("sn", "hero"));
        assertTrue(body.contains("godot_new_StringName_with_utf8_chars(u8\"hero\")"));
    }

    @Test
    @DisplayName("literal_string_name should normalize frontend quoted StringName syntax")
    void literalStringNameShouldNormalizeFrontendQuotedSyntax() {
        var body = generateBody("sn", GdStringNameType.STRING_NAME, false, new LiteralStringNameInsn("sn", "&\"hero\""));
        assertTrue(body.contains("godot_new_StringName_with_utf8_chars(u8\"hero\")"));
    }

    @Test
    @DisplayName("literal_string should call init function for ref variable")
    void literalStringShouldCallInitForRef() {
        var body = generateBody("s", GdStringType.STRING, true, new LiteralStringInsn("s", "hello"));
        assertTrue(body.contains("godot_string_new_with_utf8_chars($s, u8\"hello\");"));
    }

    @Test
    @DisplayName("literal_string_name should call init function for ref variable")
    void literalStringNameShouldCallInitForRef() {
        var body = generateBody("sn", GdStringNameType.STRING_NAME, true, new LiteralStringNameInsn("sn", "hero"));
        assertTrue(body.contains("godot_string_name_new_with_utf8_chars($sn, u8\"hero\");"));
    }

    @Test
    @DisplayName("literal_bool with non-bool result should throw")
    void literalBoolTypeMismatchShouldThrow() {
        assertTypeMismatchThrows("x", GdIntType.INT, new LiteralBoolInsn("x", true));
    }

    @Test
    @DisplayName("literal_int with non-int result should throw")
    void literalIntTypeMismatchShouldThrow() {
        assertTypeMismatchThrows("x", GdBoolType.BOOL, new LiteralIntInsn("x", 1));
    }

    @Test
    @DisplayName("literal_float with non-float result should throw")
    void literalFloatTypeMismatchShouldThrow() {
        assertTypeMismatchThrows("x", GdIntType.INT, new LiteralFloatInsn("x", 1.0));
    }

    @Test
    @DisplayName("literal_null with non-object result should throw")
    void literalNullTypeMismatchShouldThrow() {
        assertTypeMismatchThrows("x", GdStringType.STRING, new LiteralNullInsn("x"));
    }

    @Test
    @DisplayName("literal_nil with non-variant-nil result should throw")
    void literalNilTypeMismatchShouldThrow() {
        assertTypeMismatchThrows("x", GdStringType.STRING, new LiteralNilInsn("x"));
    }

    @Test
    @DisplayName("literal_string with non-string result should throw")
    void literalStringTypeMismatchShouldThrow() {
        assertTypeMismatchThrows("x", GdStringNameType.STRING_NAME, new LiteralStringInsn("x", "bad"));
    }

    @Test
    @DisplayName("literal_string_name with non-string-name result should throw")
    void literalStringNameTypeMismatchShouldThrow() {
        assertTypeMismatchThrows("x", GdStringType.STRING, new LiteralStringNameInsn("x", "bad"));
    }

    private void assertTypeMismatchThrows(@NotNull String variableId,
                                          @NotNull GdType variableType,
                                          @NotNull NewDataInstruction instruction) {
        var workerClass = new LirClassDef("Worker", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("new_data_mismatch");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable(variableId, variableType);

        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(instruction);
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        workerClass.addFunction(func);

        var module = new LirModule("test_module", List.of(workerClass));
        var codegen = newCodegen(module, List.of(workerClass));
        var ex = assertThrows(InvalidInsnException.class, () -> codegen.generateFuncBody(workerClass, func));
        assertInstanceOf(InvalidInsnException.class, ex);
    }

    private @NotNull String generateBody(@NotNull String variableId,
                                         @NotNull GdType variableType,
                                         boolean variableRef,
                                         @NotNull NewDataInstruction instruction) {
        var workerClass = new LirClassDef("Worker", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("new_data_test");
        func.setReturnType(GdVoidType.VOID);
        if (variableRef) {
            func.createAndAddRefVariable(variableId, variableType);
        } else {
            func.createAndAddVariable(variableId, variableType);
        }

        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(instruction);
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        workerClass.addFunction(func);

        var module = new LirModule("test_module", List.of(workerClass));
        var codegen = newCodegen(module, List.of(workerClass));
        return codegen.generateFuncBody(workerClass, func);
    }

    private CCodegen newCodegen(LirModule module, List<LirClassDef> gdccClasses) {
        var classRegistry = new ClassRegistry(new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()));
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
