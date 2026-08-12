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

import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/// Anchors the frozen LIR contract for `construct_container_literal`.
///
/// Covers opcode shape, VariableOperand-only operands, empty varargs, order-preserving
/// serialize/parse round-trip, and negative parse/construction cases.
class ConstructContainerLiteralInsnContractTest {

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

    @Test
    void opcodeIsConstructContainerLiteral() {
        var insn = new ConstructContainerLiteralInsn("result", List.of(new VariableOperand("a")));
        assertEquals(GdInstruction.CONSTRUCT_CONTAINER_LITERAL, insn.opcode());
        assertEquals("construct_container_literal", insn.opcode().opcode());
    }

    @Test
    void implementsConstructionInstruction() {
        assertInstanceOf(
                ConstructionInstruction.class,
                new ConstructContainerLiteralInsn("r", List.of())
        );
    }

    @Test
    void operandKindsMatchEnumDeclaration() {
        var declared = GdInstruction.CONSTRUCT_CONTAINER_LITERAL.operandKinds();
        assertEquals(List.of(OperandKind.VARARGS), declared);
        assertEquals(0, GdInstruction.CONSTRUCT_CONTAINER_LITERAL.minOperands());
        assertEquals(Integer.MAX_VALUE, GdInstruction.CONSTRUCT_CONTAINER_LITERAL.maxOperands());
        assertEquals(ReturnKind.REQUIRED, GdInstruction.CONSTRUCT_CONTAINER_LITERAL.returnKind());
    }

    @Test
    void preservesOperandOrder() {
        var insn = new ConstructContainerLiteralInsn(
                "result",
                List.of(
                        new VariableOperand("e0"),
                        new VariableOperand("e1"),
                        new VariableOperand("e2")
                )
        );
        assertEquals(
                List.of("e0", "e1", "e2"),
                insn.operands().stream()
                        .map(op -> ((VariableOperand) op).id())
                        .toList()
        );
    }

    @Test
    void emptyOperandsAreLegal() {
        var insn = new ConstructContainerLiteralInsn("empty", List.of());
        assertTrue(insn.operands().isEmpty());
        assertEquals("$empty = construct_container_literal;\n", serialize(insn));
    }

    @Test
    void serializesArrayElementOrder() {
        var insn = new ConstructContainerLiteralInsn(
                "arr",
                List.of(new VariableOperand("a"), new VariableOperand("b"))
        );
        assertEquals("$arr = construct_container_literal $a $b;\n", serialize(insn));
    }

    @Test
    void serializesDictionaryKeyValueOrder() {
        var insn = new ConstructContainerLiteralInsn(
                "dict",
                List.of(
                        new VariableOperand("k0"),
                        new VariableOperand("v0"),
                        new VariableOperand("k1"),
                        new VariableOperand("v1")
                )
        );
        assertEquals(
                "$dict = construct_container_literal $k0 $v0 $k1 $v1;\n",
                serialize(insn)
        );
    }

    @Test
    void parsesEmptyOperands() {
        var insns = parse("$empty = construct_container_literal;\n");
        assertEquals(1, insns.size());
        var insn = assertInstanceOf(ConstructContainerLiteralInsn.class, insns.getFirst());
        assertEquals("empty", insn.resultId());
        assertTrue(insn.operands().isEmpty());
    }

    @Test
    void parsesArrayOperandOrder() {
        var insns = parse("$arr = construct_container_literal $e0 $e1 $e2;\n");
        var insn = assertInstanceOf(ConstructContainerLiteralInsn.class, insns.getFirst());
        assertEquals(
                List.of("e0", "e1", "e2"),
                insn.operands().stream().map(op -> ((VariableOperand) op).id()).toList()
        );
    }

    @Test
    void parsesDictionaryKeyValueOrderWithEvenCount() {
        var insns = parse("$d = construct_container_literal $k0 $v0 $k1 $v1;\n");
        var insn = assertInstanceOf(ConstructContainerLiteralInsn.class, insns.getFirst());
        assertEquals(4, insn.operands().size());
        assertEquals("k0", ((VariableOperand) insn.operands().get(0)).id());
        assertEquals("v0", ((VariableOperand) insn.operands().get(1)).id());
        assertEquals("k1", ((VariableOperand) insn.operands().get(2)).id());
        assertEquals("v1", ((VariableOperand) insn.operands().get(3)).id());
    }

    @Test
    void roundTripPreservesArrayOrder() {
        var original = new ConstructContainerLiteralInsn(
                "result",
                List.of(new VariableOperand("a"), new VariableOperand("b"), new VariableOperand("c"))
        );
        var parsed = assertInstanceOf(
                ConstructContainerLiteralInsn.class,
                parse(serialize(original)).getFirst()
        );
        assertTrue(original.checkEquals(parsed), () -> "round-trip failed: " + serialize(original));
    }

    @Test
    void roundTripPreservesDictionaryOrder() {
        var original = new ConstructContainerLiteralInsn(
                "result",
                List.of(
                        new VariableOperand("k0"),
                        new VariableOperand("v0"),
                        new VariableOperand("k1"),
                        new VariableOperand("v1")
                )
        );
        var parsed = assertInstanceOf(
                ConstructContainerLiteralInsn.class,
                parse(serialize(original)).getFirst()
        );
        assertTrue(original.checkEquals(parsed));
    }

    @Test
    void roundTripEmptyOperands() {
        var original = new ConstructContainerLiteralInsn("empty", List.of());
        var parsed = assertInstanceOf(
                ConstructContainerLiteralInsn.class,
                parse(serialize(original)).getFirst()
        );
        assertTrue(original.checkEquals(parsed));
    }

    @Test
    void rejectsNonVariableOperandInRecord() {
        var ex = assertThrows(
                IllegalArgumentException.class,
                () -> new ConstructContainerLiteralInsn(
                        "r",
                        List.of(new VariableOperand("ok"), new StringOperand("bad"))
                )
        );
        assertTrue(ex.getMessage().contains("VariableOperand"), ex.getMessage());
    }

    @Test
    void parseRejectsStringOperand() {
        var ex = assertThrows(
                LirInsnParsingException.class,
                () -> parse("$r = construct_container_literal \"bad\";\n")
        );
        assertTrue(
                ex.getMessage().contains("variable") || ex.getMessage().contains("Expected"),
                ex.getMessage()
        );
    }

    @Test
    void parseRejectsIntOperand() {
        assertThrows(
                LirInsnParsingException.class,
                () -> parse("$r = construct_container_literal 1;\n")
        );
    }

    @Test
    void parseWithoutResultProducesNullResultId() {
        // Parser currently permits a null resultId for REQUIRED construction opcodes;
        // backend rejects missing / ref / non-container results at codegen time.
        var insn = assertInstanceOf(
                ConstructContainerLiteralInsn.class,
                parse("construct_container_literal $a;\n").getFirst()
        );
        assertNull(insn.resultId());
        assertEquals(1, insn.operands().size());
    }

    @Test
    void doesNotCollideWithEmptyConstructArrayOpcode() {
        assertNotEquals(GdInstruction.CONSTRUCT_ARRAY, GdInstruction.CONSTRUCT_CONTAINER_LITERAL);
        assertNotEquals(GdInstruction.CONSTRUCT_DICTIONARY, GdInstruction.CONSTRUCT_CONTAINER_LITERAL);
        assertEquals("construct_array", GdInstruction.CONSTRUCT_ARRAY.opcode());
        assertEquals("construct_container_literal", GdInstruction.CONSTRUCT_CONTAINER_LITERAL.opcode());
    }
}
