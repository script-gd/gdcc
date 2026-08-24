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

/// Anchors the frozen LIR contract for `await` (`gdcc_low_ir.md` §Coroutine Instructions):
/// `$<result_id> = await $<operand_id>` with exactly one VARIABLE operand and a REQUIRED result.
/// Result type rules are deliberately NOT checked here — they are delegated to backend
/// generator validation per the frozen contract.
class AwaitInsnContractTest {

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
    void opcodeIsAwait() {
        var insn = new AwaitInsn("result", "operand");
        assertEquals(GdInstruction.AWAIT, insn.opcode());
        assertEquals("await", insn.opcode().opcode());
    }

    @Test
    void operandStructureIsSingleVariable() {
        var insn = new AwaitInsn("result", "sig");
        var operands = insn.operands();
        assertEquals(1, operands.size());
        assertEquals("sig", assertInstanceOf(VariableOperand.class, operands.getFirst()).id());
    }

    @Test
    void operandKindsMatchEnumDeclaration() {
        assertEquals(List.of(OperandKind.VARIABLE), GdInstruction.AWAIT.operandKinds());
        assertEquals(1, GdInstruction.AWAIT.minOperands());
        assertEquals(1, GdInstruction.AWAIT.maxOperands());
    }

    @Test
    void returnKindIsRequired() {
        // await is value-producing; the result id is mandatory per the LIR contract.
        assertEquals(ReturnKind.REQUIRED, GdInstruction.AWAIT.returnKind());
    }

    @Test
    void implementsCoroutineInstructionGrouping() {
        // Grouping parity with other instruction families. Not being a ControlFlowInstruction
        // is enforced behaviorally below (terminator tests), and by the type system itself.
        assertInstanceOf(CoroutineInstruction.class, new AwaitInsn("r", "v"));
    }

    // --- Serialization ---

    @Test
    void serializesToDocumentedFormat() {
        var insn = new AwaitInsn("result", "operand");
        assertEquals("$result = await $operand;\n", serialize(insn));
    }

    // --- Parsing ---

    @Test
    void parsesAwaitWithResultAndOperand() {
        var insns = parse("$result = await $operand;\n");
        assertEquals(1, insns.size());
        var insn = assertInstanceOf(AwaitInsn.class, insns.getFirst());
        assertAll(
                () -> assertEquals("result", insn.resultId()),
                () -> assertEquals("operand", insn.operandId())
        );
    }

    // --- Round-trip ---

    @Test
    void roundTripPreservesInstruction() {
        var original = new AwaitInsn("result", "operand");
        var text = serialize(original);
        var parsed = assertInstanceOf(AwaitInsn.class, parse(text).getFirst());

        assertTrue(original.checkEquals(parsed),
                () -> "Round-trip failed: " + text);
    }

    // --- Not a terminator / control-flow unaffected ---

    @Test
    void awaitIsNotABlockTerminator() {
        var block = new LirBasicBlock("entry", List.of(
                new AwaitInsn("0", "sig"),
                new ReturnInsn(null)
        ));
        // Await sits in the ordinary instruction region; the terminator stays the return.
        assertEquals(2, block.getInstructionCount());
        assertInstanceOf(ReturnInsn.class, block.getTerminator());
        assertInstanceOf(AwaitInsn.class, block.getNonTerminatorInstructions().getFirst());
    }

    @Test
    void awaitAloneDoesNotSatisfyTerminatorRequirement() {
        var block = new LirBasicBlock("entry", List.of(new AwaitInsn("0", "sig")));
        assertNull(block.getTerminator());
    }

    // --- Negative: null fields ---

    @Test
    void nullResultIdRejectedByRecord() {
        assertThrows(NullPointerException.class,
                () -> new AwaitInsn(null, "operand"));
    }

    @Test
    void nullOperandIdRejectedByRecord() {
        assertThrows(NullPointerException.class,
                () -> new AwaitInsn("result", null));
    }

    // --- Negative: parse errors (fail-fast on malformed forms) ---

    @Test
    void parseMissingResultFails() {
        var ex = assertThrows(LirInsnParsingException.class,
                () -> parse("await $operand;\n"));
        assertTrue(ex.getMessage().contains("requires a result"), ex.getMessage());
    }

    @Test
    void parseMissingOperandFails() {
        var ex = assertThrows(LirInsnParsingException.class,
                () -> parse("$r = await;\n"));
        assertTrue(ex.reason.contains("Invalid operand count"), ex.reason);
    }

    @Test
    void parseExtraOperandFails() {
        assertThrows(LirInsnParsingException.class,
                () -> parse("$r = await $x $y;\n"));
    }

    @Test
    void parseNonVariableOperandFails() {
        assertThrows(LirInsnParsingException.class,
                () -> parse("$r = await \"sig\";\n"));
    }
}
