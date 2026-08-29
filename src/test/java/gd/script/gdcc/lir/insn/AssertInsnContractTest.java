package gd.script.gdcc.lir.insn;

import gd.script.gdcc.enums.GdInstruction;
import gd.script.gdcc.enums.GdInstruction.OperandKind;
import gd.script.gdcc.enums.GdInstruction.ReturnKind;
import gd.script.gdcc.exception.LirInsnParsingException;
import gd.script.gdcc.lir.LirBasicBlock;
import gd.script.gdcc.lir.LirInstruction;
import gd.script.gdcc.lir.LirInstruction.VariableOperand;
import gd.script.gdcc.lir.parser.SimpleLirBlockInsnParser;
import gd.script.gdcc.lir.parser.SimpleLirBlockInsnSerializer;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/// Anchors the frozen LIR contract for `assert` (`gdcc_low_ir.md` §Misc Instructions):
/// `assert $<cond>` / `assert $<cond> $<msg>` with 1..2 VARIABLE operands, no result,
/// no lifecycle provenance. Condition-is-bool and message-is-String rules are
/// deliberately NOT checked here — they are backend generator validation.
class AssertInsnContractTest {

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
    void opcodeIsAssert() {
        var insn = new AssertInsn("cond", null);
        assertEquals(GdInstruction.ASSERT, insn.opcode());
        assertEquals("assert", insn.opcode().opcode());
    }

    @Test
    void operandStructureOmitsMessageWhenNull() {
        var insn = new AssertInsn("cond", null);
        var operands = insn.operands();
        assertEquals(1, operands.size());
        assertEquals("cond", assertInstanceOf(VariableOperand.class, operands.getFirst()).id());
    }

    @Test
    void operandStructureIncludesMessageWhenPresent() {
        var insn = new AssertInsn("cond", "msg");
        var operands = insn.operands();
        assertEquals(2, operands.size());
        assertEquals("cond", assertInstanceOf(VariableOperand.class, operands.getFirst()).id());
        assertEquals("msg", assertInstanceOf(VariableOperand.class, operands.get(1)).id());
    }

    @Test
    void operandKindsMatchEnumDeclaration() {
        assertEquals(List.of(OperandKind.VARIABLE, OperandKind.VARIABLE), GdInstruction.ASSERT.operandKinds());
        assertEquals(1, GdInstruction.ASSERT.minOperands());
        assertEquals(2, GdInstruction.ASSERT.maxOperands());
    }

    @Test
    void returnKindIsNone() {
        assertEquals(ReturnKind.NONE, GdInstruction.ASSERT.returnKind());
    }

    @Test
    void implementsMiscInstructionAndIsNotLifecycle() {
        LirInstruction insn = new AssertInsn("cond", null);
        assertInstanceOf(MiscInstruction.class, insn);
        assertFalse(insn instanceof LifecycleInstruction);
        assertFalse(insn instanceof ControlFlowInstruction);
    }

    @Test
    void resultIdIsAlwaysNull() {
        assertNull(new AssertInsn("cond", "msg").resultId());
    }

    // --- Serialization ---

    @Test
    void serializesConditionOnlyToDocumentedFormat() {
        assertEquals("assert $cond;\n", serialize(new AssertInsn("cond", null)));
    }

    @Test
    void serializesConditionAndMessageToDocumentedFormat() {
        assertEquals("assert $cond $msg;\n", serialize(new AssertInsn("cond", "msg")));
    }

    // --- Parsing ---

    @Test
    void parsesAssertWithConditionOnly() {
        var insns = parse("assert $cond;\n");
        assertEquals(1, insns.size());
        var insn = assertInstanceOf(AssertInsn.class, insns.getFirst());
        assertAll(
                () -> assertNull(insn.resultId()),
                () -> assertEquals("cond", insn.conditionId()),
                () -> assertNull(insn.messageId())
        );
    }

    @Test
    void parsesAssertWithConditionAndMessage() {
        var insns = parse("assert $cond $msg;\n");
        assertEquals(1, insns.size());
        var insn = assertInstanceOf(AssertInsn.class, insns.getFirst());
        assertAll(
                () -> assertNull(insn.resultId()),
                () -> assertEquals("cond", insn.conditionId()),
                () -> assertEquals("msg", insn.messageId())
        );
    }

    @Test
    void parseResultPrefixIsDiscarded() {
        // Official contract: ReturnKind.NONE is not enforced by the generic parser.
        // Concrete AssertInsn always reports no result; the `$r =` prefix is silently
        // discarded at toConcrete() and is not a backend fail-fast.
        var insn = assertInstanceOf(AssertInsn.class, parse("$r = assert $cond;\n").getFirst());
        assertNull(insn.resultId());
        assertEquals("cond", insn.conditionId());
        assertEquals("assert $cond;\n", serialize(insn));
    }

    // --- Round-trip ---

    @Test
    void roundTripPreservesConditionOnly() {
        var original = new AssertInsn("cond", null);
        var text = serialize(original);
        var parsed = assertInstanceOf(AssertInsn.class, parse(text).getFirst());
        assertTrue(original.checkEquals(parsed), () -> "Round-trip failed: " + text);
    }

    @Test
    void roundTripPreservesConditionAndMessage() {
        var original = new AssertInsn("cond", "msg");
        var text = serialize(original);
        var parsed = assertInstanceOf(AssertInsn.class, parse(text).getFirst());
        assertTrue(original.checkEquals(parsed), () -> "Round-trip failed: " + text);
    }

    // --- Not a terminator ---

    @Test
    void assertIsNotABlockTerminator() {
        var block = new LirBasicBlock("entry", List.of(
                new AssertInsn("cond", null),
                new ReturnInsn(null)
        ));
        assertEquals(2, block.getInstructionCount());
        assertInstanceOf(ReturnInsn.class, block.getTerminator());
        assertInstanceOf(AssertInsn.class, block.getNonTerminatorInstructions().getFirst());
    }

    @Test
    void assertAloneDoesNotSatisfyTerminatorRequirement() {
        var block = new LirBasicBlock("entry", List.of(new AssertInsn("cond", "msg")));
        assertNull(block.getTerminator());
    }

    // --- Negative: null fields ---

    @Test
    void nullConditionIdRejectedByRecord() {
        assertThrows(NullPointerException.class, () -> new AssertInsn(null, null));
    }

    @Test
    void nullMessageIdIsAllowed() {
        var insn = new AssertInsn("cond", null);
        assertNull(insn.messageId());
        assertEquals(1, insn.operands().size());
    }

    // --- Negative: parse errors ---

    @Test
    void parseMissingOperandFails() {
        var ex = assertThrows(LirInsnParsingException.class, () -> parse("assert;\n"));
        assertTrue(ex.reason.contains("Invalid operand count"), ex.reason);
    }

    @Test
    void parseExtraOperandFails() {
        var ex = assertThrows(LirInsnParsingException.class, () -> parse("assert $c $m $extra;\n"));
        assertTrue(ex.reason.contains("Invalid operand count"), ex.reason);
    }

    @Test
    void parseNonVariableConditionFails() {
        var ex = assertThrows(LirInsnParsingException.class, () -> parse("assert true;\n"));
        assertTrue(ex.reason.contains("Expected variable operand"), ex.reason);
    }

    @Test
    void parseNonVariableMessageFails() {
        var ex = assertThrows(LirInsnParsingException.class, () -> parse("assert $cond \"msg\";\n"));
        assertTrue(ex.reason.contains("Expected variable operand"), ex.reason);
    }
}
