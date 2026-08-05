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

/// Anchors the frozen contract for the unified `is_instance_of` LIR instruction.
/// Verifies: single opcode surface, operand structure, serialization format, parsing round-trip,
/// and type-name variants (builtin / object / parameterized container).
class IsInstanceOfInsnContractTest {

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

    // --- Opcode and operand structure ---

    @Test
    void opcodeIsSingleUnifiedIsInstanceOf() {
        var insn = new IsInstanceOfInsn("result", "Node2D", "value");
        assertEquals(GdInstruction.IS_INSTANCE_OF, insn.opcode());
        assertEquals("is_instance_of", insn.opcode().opcode());
    }

    @Test
    void operandStructureIsStringThenVariable() {
        var insn = new IsInstanceOfInsn("result", "int", "value");
        var operands = insn.operands();

        assertEquals(2, operands.size());
        var first = assertInstanceOf(StringOperand.class, operands.getFirst());
        var second = assertInstanceOf(VariableOperand.class, operands.get(1));
        assertEquals("int", first.value());
        assertEquals("value", second.id());
    }

    @Test
    void operandKindsMatchEnumDeclaration() {
        var declared = GdInstruction.IS_INSTANCE_OF.operandKinds();
        assertEquals(List.of(OperandKind.STRING, OperandKind.VARIABLE), declared);
        assertEquals(2, GdInstruction.IS_INSTANCE_OF.minOperands());
        assertEquals(2, GdInstruction.IS_INSTANCE_OF.maxOperands());
    }

    @Test
    void returnKindIsRequiredBecauseResultIsAlwaysBool() {
        assertEquals(ReturnKind.REQUIRED, GdInstruction.IS_INSTANCE_OF.returnKind());
    }

    @Test
    void implementsTypeInstructionGrouping() {
        var insn = new IsInstanceOfInsn("r", "String", "v");
        assertInstanceOf(TypeInstruction.class, insn);
    }

    // --- Type name variants (unified surface: one opcode for all type families) ---

    @ParameterizedTest
    @ValueSource(strings = {
            "int",
            "float",
            "String",
            "PackedInt32Array",
            "Array",
            "Dictionary",
            "Node2D",
            "CharacterBody2D",
            "Array[int]",
            "Dictionary[String, int]",
    })
    void typeNameVariantsAllUseSameOpcodeAndStructure(String typeName) {
        var insn = new IsInstanceOfInsn("result", typeName, "value");
        assertEquals(GdInstruction.IS_INSTANCE_OF, insn.opcode());
        assertEquals(typeName, insn.typeName());
        assertEquals("value", insn.valueId());

        var operands = insn.operands();
        assertEquals(2, operands.size());
        assertEquals(typeName, ((StringOperand) operands.getFirst()).value());
    }

    // --- Serialization format ---

    @Test
    void serializesToDocumentedFormat() {
        var insn = new IsInstanceOfInsn("result", "Node2D", "value");
        assertEquals("$result = is_instance_of \"Node2D\" $value;\n", serialize(insn));
    }

    @Test
    void serializesParameterizedContainerTypeText() {
        var insn = new IsInstanceOfInsn("r", "Array[int]", "arr");
        assertEquals("$r = is_instance_of \"Array[int]\" $arr;\n", serialize(insn));
    }

    @Test
    void serializesBuiltinTypeName() {
        var insn = new IsInstanceOfInsn("out", "int", "x");
        assertEquals("$out = is_instance_of \"int\" $x;\n", serialize(insn));
    }

    // --- Parsing ---

    @Test
    void parsesObjectClassTypeTest() {
        var insns = parse("$result = is_instance_of \"Node2D\" $value;\n");
        assertEquals(1, insns.size());
        var insn = assertInstanceOf(IsInstanceOfInsn.class, insns.getFirst());
        assertAll(
                () -> assertEquals("result", insn.resultId()),
                () -> assertEquals("Node2D", insn.typeName()),
                () -> assertEquals("value", insn.valueId())
        );
    }

    @Test
    void parsesBuiltinTypeTest() {
        var insns = parse("$r = is_instance_of \"int\" $x;\n");
        var insn = assertInstanceOf(IsInstanceOfInsn.class, insns.getFirst());
        assertEquals("int", insn.typeName());
        assertEquals("x", insn.valueId());
    }

    @Test
    void parsesParameterizedContainerTypeTest() {
        var insns = parse("$r = is_instance_of \"Dictionary[String, int]\" $d;\n");
        var insn = assertInstanceOf(IsInstanceOfInsn.class, insns.getFirst());
        assertEquals("Dictionary[String, int]", insn.typeName());
        assertEquals("d", insn.valueId());
    }

    // --- Round-trip: serialize → parse → same instruction ---

    @ParameterizedTest
    @ValueSource(strings = {"int", "Node2D", "Array[int]", "Dictionary[String, int]"})
    void roundTripPreservesInstruction(String typeName) {
        var original = new IsInstanceOfInsn("result", typeName, "value");
        var text = serialize(original);
        var parsed = assertInstanceOf(IsInstanceOfInsn.class, parse(text).getFirst());

        assertTrue(original.checkEquals(parsed),
                () -> "Round-trip failed for type '" + typeName + "': " + text);
    }

    // --- Negative: null fields rejected ---
    // NOTE: Empty/blank type names are rejected by frontend resolution;
    // LIR layer only guards against null (defensive programming boundary).

    @Test
    void nullTypeNameRejected() {
        assertThrows(NullPointerException.class,
                () -> new IsInstanceOfInsn("result", null, "value"));
    }

    @Test
    void nullValueIdRejected() {
        assertThrows(NullPointerException.class,
                () -> new IsInstanceOfInsn("result", "int", null));
    }

    // --- Negative: parse errors for malformed input ---

    @Test
    void parseMissingStringOperandFails() {
        assertThrows(LirInsnParsingException.class,
                () -> parse("$r = is_instance_of $value;\n"));
    }

    @Test
    void parseMissingVariableOperandFails() {
        assertThrows(LirInsnParsingException.class,
                () -> parse("$r = is_instance_of \"int\";\n"));
    }

    @Test
    void parseExtraOperandFails() {
        assertThrows(LirInsnParsingException.class,
                () -> parse("$r = is_instance_of \"int\" $x $y;\n"));
    }

    // --- resultId is nullable (unused result allowed) ---

    @Test
    void nullResultIdAllowedForUnusedResult() {
        var insn = new IsInstanceOfInsn(null, "int", "value");
        assertNull(insn.resultId());
        assertEquals("int", insn.typeName());
    }

    @Test
    void nullResultIdSerializesWithoutAssignmentPrefix() {
        var insn = new IsInstanceOfInsn(null, "int", "value");
        var text = serialize(insn);
        assertEquals("is_instance_of \"int\" $value;\n", text);
    }

    // --- Negative contract: no separate opcode for `is not` or type-family split ---

    @Test
    void noSeparateOpcodeForIsNotOrTypeFamilySplit() {
        var allOpcodes = java.util.Arrays.stream(GdInstruction.values())
                .map(GdInstruction::opcode)
                .toList();
        assertFalse(allOpcodes.contains("is_not_instance_of"));
        assertFalse(allOpcodes.contains("is_builtin_type"));
        assertFalse(allOpcodes.contains("is_object_class"));
        assertFalse(allOpcodes.contains("is_typed_container"));
    }
}
