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

/// Anchors the frozen LIR contract for `object_cast`.
/// Text opcode and operand layout use `$value`; Java API exposes [ObjectCastInsn#valueId()].
class ObjectCastInsnContractTest {

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
    void opcodeIsObjectCast() {
        var insn = new ObjectCastInsn("result", "Node2D", "value");
        assertEquals(GdInstruction.OBJECT_CAST, insn.opcode());
        assertEquals("object_cast", insn.opcode().opcode());
    }

    @Test
    void javaApiExposesValueIdNotObjectId() {
        var insn = new ObjectCastInsn("result", "Node", "src");
        assertEquals("src", insn.valueId());
        // Operand layout still STRING + VARIABLE (text-compatible with pre-rename LIR).
        var second = assertInstanceOf(VariableOperand.class, insn.operands().get(1));
        assertEquals("src", second.id());
    }

    @Test
    void operandStructureIsStringThenVariable() {
        var insn = new ObjectCastInsn("result", "CharacterBody2D", "obj");
        var operands = insn.operands();
        assertEquals(2, operands.size());
        assertEquals("CharacterBody2D", ((StringOperand) operands.getFirst()).value());
        assertEquals("obj", ((VariableOperand) operands.get(1)).id());
    }

    @Test
    void operandKindsMatchEnumDeclaration() {
        assertEquals(List.of(OperandKind.STRING, OperandKind.VARIABLE),
                GdInstruction.OBJECT_CAST.operandKinds());
        assertEquals(2, GdInstruction.OBJECT_CAST.minOperands());
        assertEquals(2, GdInstruction.OBJECT_CAST.maxOperands());
    }

    @Test
    void returnKindIsOptional() {
        assertEquals(ReturnKind.OPTIONAL, GdInstruction.OBJECT_CAST.returnKind());
    }

    @Test
    void implementsTypeInstructionGrouping() {
        assertInstanceOf(TypeInstruction.class, new ObjectCastInsn("r", "Node", "v"));
    }

    // --- className is opaque canonical text at LIR layer ---

    @ParameterizedTest
    @ValueSource(strings = {
            "Object",
            "Node",
            "Node2D",
            "RefCounted",
            "Outer__sub__Inner",
    })
    void classNameVariantsPreserveOpaqueText(String className) {
        var insn = new ObjectCastInsn("result", className, "value");
        assertEquals(className, insn.className());
        assertEquals(className, ((StringOperand) insn.operands().getFirst()).value());
    }

    // --- Serialization (text format stable across rename) ---

    @Test
    void serializesToDocumentedFormat() {
        var insn = new ObjectCastInsn("result", "Node2D", "value");
        assertEquals("$result = object_cast \"Node2D\" $value;\n", serialize(insn));
    }

    @Test
    void serializesInnerClassCanonicalNameUnchanged() {
        var insn = new ObjectCastInsn("r", "Outer__sub__Inner", "v");
        assertEquals("$r = object_cast \"Outer__sub__Inner\" $v;\n", serialize(insn));
    }

    // --- Parsing ---

    @Test
    void parsesObjectCastWithValueId() {
        var insns = parse("$result = object_cast \"Node2D\" $value;\n");
        assertEquals(1, insns.size());
        var insn = assertInstanceOf(ObjectCastInsn.class, insns.getFirst());
        assertAll(
                () -> assertEquals("result", insn.resultId()),
                () -> assertEquals("Node2D", insn.className()),
                () -> assertEquals("value", insn.valueId())
        );
    }

    @Test
    void parsesCanonicalInnerClassNameWithoutRewriting() {
        var insns = parse("$r = object_cast \"Outer__sub__Inner\" $obj;\n");
        var insn = assertInstanceOf(ObjectCastInsn.class, insns.getFirst());
        assertEquals("Outer__sub__Inner", insn.className());
        assertEquals("obj", insn.valueId());
    }

    // --- Round-trip ---

    @ParameterizedTest
    @ValueSource(strings = {"Node", "Node2D", "Outer__sub__Inner", "RefCounted"})
    void roundTripPreservesInstruction(String className) {
        var original = new ObjectCastInsn("result", className, "value");
        var text = serialize(original);
        var parsed = assertInstanceOf(ObjectCastInsn.class, parse(text).getFirst());

        assertTrue(original.checkEquals(parsed),
                () -> "Round-trip failed for class '" + className + "': " + text);
    }

    // --- Optional result ---

    @Test
    void nullResultIdAllowed() {
        var insn = new ObjectCastInsn(null, "Node", "value");
        assertNull(insn.resultId());
        assertEquals("Node", insn.className());
        assertEquals("value", insn.valueId());
    }

    @Test
    void nullResultIdSerializesWithoutAssignmentPrefix() {
        var insn = new ObjectCastInsn(null, "Node", "value");
        assertEquals("object_cast \"Node\" $value;\n", serialize(insn));
    }

    @Test
    void parsesWithoutResultAssignment() {
        var insns = parse("object_cast \"Node\" $value;\n");
        var insn = assertInstanceOf(ObjectCastInsn.class, insns.getFirst());
        assertNull(insn.resultId());
        assertEquals("Node", insn.className());
        assertEquals("value", insn.valueId());
    }

    // --- Negative: null fields ---

    @Test
    void nullClassNameRejected() {
        assertThrows(NullPointerException.class,
                () -> new ObjectCastInsn("result", null, "value"));
    }

    @Test
    void nullValueIdRejected() {
        assertThrows(NullPointerException.class,
                () -> new ObjectCastInsn("result", "Node", null));
    }

    // --- Negative: parse errors ---

    @Test
    void parseMissingStringOperandFails() {
        assertThrows(LirInsnParsingException.class,
                () -> parse("$r = object_cast $value;\n"));
    }

    @Test
    void parseMissingVariableOperandFails() {
        assertThrows(LirInsnParsingException.class,
                () -> parse("$r = object_cast \"Node\";\n"));
    }

    @Test
    void parseExtraOperandFails() {
        assertThrows(LirInsnParsingException.class,
                () -> parse("$r = object_cast \"Node\" $x $y;\n"));
    }

    @Test
    void parseInvalidOperandKindFails() {
        assertThrows(LirInsnParsingException.class,
                () -> parse("$r = object_cast Node $x;\n"));
    }

    // --- Rename / separation from builtin_cast ---

    @Test
    void isSeparateFromBuiltinCast() {
        assertNotEquals(GdInstruction.BUILTIN_CAST, GdInstruction.OBJECT_CAST);
        var objectCast = new ObjectCastInsn("r", "Node", "v");
        var builtinCast = new BuiltinCastInsn("r", "int", "v");
        assertNotEquals(objectCast.opcode(), builtinCast.opcode());
    }
}
