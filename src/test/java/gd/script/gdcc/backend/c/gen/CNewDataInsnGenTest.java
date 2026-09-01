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
import gd.script.gdcc.lir.insn.LiteralBoolInsn;
import gd.script.gdcc.lir.insn.LiteralFloatInsn;
import gd.script.gdcc.lir.insn.LiteralIntInsn;
import gd.script.gdcc.lir.insn.LiteralNilInsn;
import gd.script.gdcc.lir.insn.LiteralNodePathInsn;
import gd.script.gdcc.lir.insn.LiteralNullInsn;
import gd.script.gdcc.lir.insn.LiteralStringInsn;
import gd.script.gdcc.lir.insn.LiteralStringNameInsn;
import gd.script.gdcc.lir.insn.NewDataInstruction;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdBoolType;
import gd.script.gdcc.type.GdFloatType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdNodePathType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdStringNameType;
import gd.script.gdcc.type.GdStringType;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.type.GdVariantType;
import gd.script.gdcc.type.GdVoidType;
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
    @DisplayName("literal_int should preserve godot_int64-width values")
    void literalIntShouldPreserveInt64Width() {
        var body = generateBody("i", GdIntType.INT, false, new LiteralIntInsn("i", 4_294_967_296L));
        assertTrue(body.contains("$i = 4294967296;"));
    }

    @Test
    @DisplayName("literal_float should use assignExpr")
    void literalFloatShouldAssignExpr() {
        var body = generateBody("f", GdFloatType.FLOAT, false, new LiteralFloatInsn("f", 1.5));
        assertTrue(body.contains("$f = 1.5;"));
    }

    @Test
    @DisplayName("literal_float should render positive infinity as godot_inf macro")
    void literalFloatPositiveInfinityShouldRenderGodotInfMacro() {
        var body = generateBody("f", GdFloatType.FLOAT, false, new LiteralFloatInsn("f", Double.POSITIVE_INFINITY));
        assertTrue(body.contains("$f = godot_inf;"));
    }

    @Test
    @DisplayName("literal_float should render negative infinity as negated godot_inf macro")
    void literalFloatNegativeInfinityShouldRenderNegatedGodotInfMacro() {
        var body = generateBody("f", GdFloatType.FLOAT, false, new LiteralFloatInsn("f", Double.NEGATIVE_INFINITY));
        assertTrue(body.contains("$f = -godot_inf;"));
    }

    @Test
    @DisplayName("literal_float should render NaN as math.h NAN macro")
    void literalFloatNaNShouldRenderMathNanMacro() {
        var body = generateBody("f", GdFloatType.FLOAT, false, new LiteralFloatInsn("f", Double.NaN));
        assertTrue(body.contains("$f = NAN;"));
    }

    @Test
    @DisplayName("literal_null should assign a zeroed fat pointer to object variable")
    void literalNullShouldAssignNull() {
        var body = generateBody("obj", new GdObjectType("Node"), false, new LiteralNullInsn("obj"));
        assertTrue(body.contains("$obj = (gdcc_Node_fat_ptr){ 0 };"));
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
    @DisplayName("literal_string should preserve payload that intentionally contains quotes")
    void literalStringShouldPreserveQuotedPayload() {
        var body = generateBody("s", GdStringType.STRING, false, new LiteralStringInsn("s", "\"hero\""));
        assertTrue(body.contains("godot_new_String_with_utf8_chars(u8\"\\\"hero\\\"\")"));
    }

    @Test
    @DisplayName("literal_string_name should call non-ref constructor for non-ref variable")
    void literalStringNameShouldCallAssignForNonRef() {
        var body = generateBody("sn", GdStringNameType.STRING_NAME, false, new LiteralStringNameInsn("sn", "hero"));
        assertTrue(body.contains("godot_new_StringName_with_utf8_chars(u8\"hero\")"));
    }

    @Test
    @DisplayName("literal_string_name should preserve payload that intentionally contains quotes")
    void literalStringNameShouldPreserveQuotedPayload() {
        var body = generateBody("sn", GdStringNameType.STRING_NAME, false, new LiteralStringNameInsn("sn", "\"hero\""));
        assertTrue(body.contains("godot_new_StringName_with_utf8_chars(u8\"\\\"hero\\\"\")"));
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
        assertTypeMismatchThrows(GdIntType.INT, new LiteralBoolInsn("x", true));
    }

    @Test
    @DisplayName("literal_int with non-int result should throw")
    void literalIntTypeMismatchShouldThrow() {
        assertTypeMismatchThrows(GdBoolType.BOOL, new LiteralIntInsn("x", 1));
    }

    @Test
    @DisplayName("literal_float with non-float result should throw")
    void literalFloatTypeMismatchShouldThrow() {
        assertTypeMismatchThrows(GdIntType.INT, new LiteralFloatInsn("x", 1.0));
    }

    @Test
    @DisplayName("literal_null with non-object result should throw")
    void literalNullTypeMismatchShouldThrow() {
        assertTypeMismatchThrows(GdStringType.STRING, new LiteralNullInsn("x"));
    }

    @Test
    @DisplayName("literal_nil with non-variant-nil result should throw")
    void literalNilTypeMismatchShouldThrow() {
        assertTypeMismatchThrows(GdStringType.STRING, new LiteralNilInsn("x"));
    }

    @Test
    @DisplayName("literal_string with non-string result should throw")
    void literalStringTypeMismatchShouldThrow() {
        assertTypeMismatchThrows(GdStringNameType.STRING_NAME, new LiteralStringInsn("x", "bad"));
    }

    @Test
    @DisplayName("literal_string_name with non-string-name result should throw")
    void literalStringNameTypeMismatchShouldThrow() {
        assertTypeMismatchThrows(GdStringType.STRING, new LiteralStringNameInsn("x", "bad"));
    }

    @Test
    @DisplayName("literal_string_name should reject raw frontend lexeme syntax")
    void literalStringNameRawLexemeShouldThrow() {
        var ex = assertInvalidInsnThrows(
                "sn",
                GdStringNameType.STRING_NAME,
                new LiteralStringNameInsn("sn", "&\"hero\"")
        );
        assertTrue(ex.getMessage().contains("LiteralStringNameInsn must carry normalized runtime payload"));
        assertTrue(ex.getMessage().contains("&\"hero\""));
    }

    @Test
    @DisplayName("literal_node_path should call non-ref constructor for non-ref variable")
    void literalNodePathShouldCallAssignForNonRef() {
        var body = generateBody("p", GdNodePathType.NODE_PATH, false, new LiteralNodePathInsn("p", "a/b"));
        assertTrue(body.contains("godot_new_NodePath_with_utf8_chars(u8\"a/b\")"));
    }

    @Test
    @DisplayName("literal_node_path should escape quotes and backslashes in payload")
    void literalNodePathShouldEscapeQuotedPayload() {
        var body = generateBody("p", GdNodePathType.NODE_PATH, false, new LiteralNodePathInsn("p", "say \"hi\"\\n"));
        assertTrue(body.contains("godot_new_NodePath_with_utf8_chars(u8\"say \\\"hi\\\"\\\\n\")"));
    }

    @Test
    @DisplayName("literal_node_path should escape non-ASCII payload as unicode escapes")
    void literalNodePathShouldEscapeNonAsciiPayload() {
        // C literal escaping is shared with other string-like literals: non-ASCII BMP code points
        // render as unicode escapes.
        var body = generateBody("p", GdNodePathType.NODE_PATH, false, new LiteralNodePathInsn("p", "路径"));
        assertTrue(body.contains("godot_new_NodePath_with_utf8_chars(u8\"\\u8DEF\\u5F84\")"));
    }

    @Test
    @DisplayName("literal_node_path should materialize payload that looks like a raw lexeme")
    void literalNodePathShouldMaterializeLexemeShapedPayload() {
        // `^"^\"foo\""` decodes to `^"foo"`; payload-only contract must not reject it.
        var body = generateBody("p", GdNodePathType.NODE_PATH, false, new LiteralNodePathInsn("p", "^\"foo\""));
        assertTrue(body.contains("godot_new_NodePath_with_utf8_chars(u8\"^\\\"foo\\\"\")"));
    }

    @Test
    @DisplayName("literal_node_path with non-node-path result should throw")
    void literalNodePathTypeMismatchShouldThrow() {
        assertTypeMismatchThrows(GdStringType.STRING, new LiteralNodePathInsn("x", "bad"));
    }

    @Test
    @DisplayName("literal_node_path without result should throw")
    void literalNodePathMissingResultShouldThrow() {
        var ex = assertInvalidInsnThrows("x", GdNodePathType.NODE_PATH, new LiteralNodePathInsn(null, "a/b"));
        assertTrue(ex.getMessage().contains("missing result variable ID"));
    }

    @Test
    @DisplayName("literal_node_path with ref result should throw")
    void literalNodePathRefResultShouldThrow() {
        // GDExtension exposes no in-place NodePath constructor, so ref results are fail-closed.
        var ex = assertRefInvalidInsnThrows("p", GdNodePathType.NODE_PATH, new LiteralNodePathInsn("p", "a/b"));
        assertTrue(ex.getMessage().contains("cannot be a reference"));
    }

    private void assertTypeMismatchThrows(@NotNull GdType variableType,
                                          @NotNull NewDataInstruction instruction) {
        var ex = assertInvalidInsnThrows("x", variableType, instruction);
        assertInstanceOf(InvalidInsnException.class, ex);
    }

    private @NotNull InvalidInsnException assertInvalidInsnThrows(@NotNull String variableId,
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
        return assertThrows(InvalidInsnException.class, () -> codegen.generateFuncBody(workerClass, func));
    }

    private @NotNull InvalidInsnException assertRefInvalidInsnThrows(@NotNull String variableId,
                                                                     @NotNull GdType variableType,
                                                                     @NotNull NewDataInstruction instruction) {
        var workerClass = new LirClassDef("Worker", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("new_data_ref_mismatch");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddRefVariable(variableId, variableType);

        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(instruction);
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        workerClass.addFunction(func);

        var module = new LirModule("test_module", List.of(workerClass));
        var codegen = newCodegen(module, List.of(workerClass));
        return assertThrows(InvalidInsnException.class, () -> codegen.generateFuncBody(workerClass, func));
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
        var nodeClass = new ExtensionGdClass(
                "Node", false, false, "Object", "core",
                List.of(), List.of(), List.of(), List.of(), List.of()
        );
        var classRegistry = new ClassRegistry(new ExtensionAPI(
                null, List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(nodeClass), List.of(), List.of()
        ));
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
