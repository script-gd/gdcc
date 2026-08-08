package gd.script.gdcc.lir.insn;

import gd.script.gdcc.enums.GdInstruction;
import gd.script.gdcc.enums.GdInstruction.OperandKind;
import gd.script.gdcc.enums.GdInstruction.ReturnKind;
import gd.script.gdcc.exception.LirInsnParsingException;
import gd.script.gdcc.lir.LirInstruction;
import gd.script.gdcc.lir.LirInstruction.StringOperand;
import gd.script.gdcc.lir.LirInstruction.VariableOperand;
import gd.script.gdcc.lir.parser.SimpleLirBlockInsnParser;
import gd.script.gdcc.lir.parser.SimpleLirBlockInsnSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/// Anchors the frozen LIR contract for `builtin_cast`.
/// Covers opcode shape, required result, opaque target text (incl. parameterized containers),
/// serialize/parse round-trip, and negative parse cases.
class BuiltinCastInsnContractTest {

    private static List<LirInstruction> parse(String input) {
        return new SimpleLirBlockInsnParser().parse(new StringReader(input));
    }

    private static String serialize(LirInstruction insn) {
        var sw = new StringWriter();
        try {
            new SimpleLirBlockInsnSerializer().serialize(List.of(insn), sw);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return sw.toString();
    }

    // --- Opcode / shape ---

    @Test
    void opcodeIsBuiltinCast() {
        var insn = new BuiltinCastInsn("result", "int", "value");
        assertEquals(GdInstruction.BUILTIN_CAST, insn.opcode());
        assertEquals("builtin_cast", insn.opcode().opcode());
    }

    @Test
    void operandStructureIsStringThenVariable() {
        var insn = new BuiltinCastInsn("result", "float", "value");
        var operands = insn.operands();

        assertEquals(2, operands.size());
        var first = assertInstanceOf(StringOperand.class, operands.getFirst());
        var second = assertInstanceOf(VariableOperand.class, operands.get(1));
        assertEquals("float", first.value());
        assertEquals("value", second.id());
    }

    @Test
    void operandKindsMatchEnumDeclaration() {
        var declared = GdInstruction.BUILTIN_CAST.operandKinds();
        assertEquals(List.of(OperandKind.STRING, OperandKind.VARIABLE), declared);
        assertEquals(2, GdInstruction.BUILTIN_CAST.minOperands());
        assertEquals(2, GdInstruction.BUILTIN_CAST.maxOperands());
    }

    @Test
    void returnKindIsRequired() {
        assertEquals(ReturnKind.REQUIRED, GdInstruction.BUILTIN_CAST.returnKind());
    }

    @Test
    void implementsTypeInstructionGrouping() {
        assertInstanceOf(TypeInstruction.class, new BuiltinCastInsn("r", "int", "v"));
    }

    // --- Target type text variants (opaque; not re-resolved at LIR layer) ---

    @ParameterizedTest
    @ValueSource(strings = {
            "int",
            "float",
            "String",
            "StringName",
            "PackedInt32Array",
            "Array",
            "Dictionary",
            "Array[int]",
            "Dictionary[String, int]",
            "Vector2",
            "Color",
    })
    void targetTypeNameVariantsPreserveOpaqueText(String targetTypeName) {
        var insn = new BuiltinCastInsn("result", targetTypeName, "value");
        assertEquals(targetTypeName, insn.targetTypeName());
        assertEquals(targetTypeName, ((StringOperand) insn.operands().getFirst()).value());
    }

    // --- Serialization ---

    @Test
    void serializesToDocumentedFormat() {
        var insn = new BuiltinCastInsn("result", "int", "value");
        assertEquals("$result = builtin_cast \"int\" $value;\n", serialize(insn));
    }

    @Test
    void serializesParameterizedContainerTypeTextUnchanged() {
        var insn = new BuiltinCastInsn("r", "Array[int]", "arr");
        assertEquals("$r = builtin_cast \"Array[int]\" $arr;\n", serialize(insn));
    }

    @Test
    void serializesDictionaryParameterizedTypeTextUnchanged() {
        var insn = new BuiltinCastInsn("r", "Dictionary[String, int]", "d");
        assertEquals("$r = builtin_cast \"Dictionary[String, int]\" $d;\n", serialize(insn));
    }

    // --- Parsing ---

    @Test
    void parsesBuiltinTarget() {
        var insns = parse("$result = builtin_cast \"int\" $value;\n");
        assertEquals(1, insns.size());
        var insn = assertInstanceOf(BuiltinCastInsn.class, insns.getFirst());
        assertAll(
                () -> assertEquals("result", insn.resultId()),
                () -> assertEquals("int", insn.targetTypeName()),
                () -> assertEquals("value", insn.valueId())
        );
    }

    @Test
    void parsesParameterizedContainerTargetWithoutRewriting() {
        var insns = parse("$r = builtin_cast \"Array[int]\" $arr;\n");
        var insn = assertInstanceOf(BuiltinCastInsn.class, insns.getFirst());
        assertEquals("Array[int]", insn.targetTypeName());
        assertEquals("arr", insn.valueId());
    }

    // --- Round-trip ---

    @ParameterizedTest
    @ValueSource(strings = {"int", "StringName", "Array[int]", "Dictionary[String, int]", "PackedByteArray"})
    void roundTripPreservesInstruction(String targetTypeName) {
        var original = new BuiltinCastInsn("result", targetTypeName, "value");
        var text = serialize(original);
        var parsed = assertInstanceOf(BuiltinCastInsn.class, parse(text).getFirst());

        assertTrue(original.checkEquals(parsed),
                () -> "Round-trip failed for target '" + targetTypeName + "': " + text);
    }

    // --- Negative: null fields ---

    @Test
    void nullResultIdRejected() {
        assertThrows(NullPointerException.class,
                () -> new BuiltinCastInsn(null, "int", "value"));
    }

    @Test
    void nullTargetTypeNameRejected() {
        assertThrows(NullPointerException.class,
                () -> new BuiltinCastInsn("result", null, "value"));
    }

    @Test
    void nullValueIdRejected() {
        assertThrows(NullPointerException.class,
                () -> new BuiltinCastInsn("result", "int", null));
    }

    // --- Negative: parse errors ---

    @Test
    void parseMissingResultFails() {
        var ex = assertThrows(LirInsnParsingException.class,
                () -> parse("builtin_cast \"int\" $value;\n"));
        assertTrue(ex.getMessage().contains("requires a result"), ex.getMessage());
    }

    @Test
    void parseMissingStringOperandFails() {
        assertThrows(LirInsnParsingException.class,
                () -> parse("$r = builtin_cast $value;\n"));
    }

    @Test
    void parseMissingVariableOperandFails() {
        assertThrows(LirInsnParsingException.class,
                () -> parse("$r = builtin_cast \"int\";\n"));
    }

    @Test
    void parseExtraOperandFails() {
        assertThrows(LirInsnParsingException.class,
                () -> parse("$r = builtin_cast \"int\" $x $y;\n"));
    }

    @Test
    void parseInvalidOperandKindFails() {
        // First operand must be string; bare identifier is not accepted.
        assertThrows(LirInsnParsingException.class,
                () -> parse("$r = builtin_cast int $x;\n"));
    }

    // --- Contract boundaries: this opcode is not for Object / is-test / pack ---

    @Test
    void objectCastIsSeparateOpcode() {
        assertNotEquals(GdInstruction.OBJECT_CAST, GdInstruction.BUILTIN_CAST);
        assertEquals("object_cast", GdInstruction.OBJECT_CAST.opcode());
        assertEquals("builtin_cast", GdInstruction.BUILTIN_CAST.opcode());
    }
}
