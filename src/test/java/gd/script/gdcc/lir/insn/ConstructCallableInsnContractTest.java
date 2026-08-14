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

/// Anchors the frozen LIR contract for `construct_callable`.
///
/// Covers opcode shape, `(receiver, method_name)` operands, serialize/parse round-trip,
/// and rejection of the old one-operand form.
class ConstructCallableInsnContractTest {

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
    void opcodeIsConstructCallable() {
        var insn = new ConstructCallableInsn("result", "self", "_handler");
        assertEquals(GdInstruction.CONSTRUCT_CALLABLE, insn.opcode());
        assertEquals("construct_callable", insn.opcode().opcode());
    }

    @Test
    void implementsConstructionInstruction() {
        assertInstanceOf(ConstructionInstruction.class, new ConstructCallableInsn("r", "self", "_handler"));
    }

    @Test
    void operandKindsMatchEnumDeclaration() {
        var declared = GdInstruction.CONSTRUCT_CALLABLE.operandKinds();
        assertEquals(List.of(OperandKind.VARIABLE, OperandKind.STRING), declared);
        assertEquals(2, GdInstruction.CONSTRUCT_CALLABLE.minOperands());
        assertEquals(2, GdInstruction.CONSTRUCT_CALLABLE.maxOperands());
        assertEquals(ReturnKind.REQUIRED, GdInstruction.CONSTRUCT_CALLABLE.returnKind());
    }

    @Test
    void operandsAreReceiverThenMethodName() {
        var insn = new ConstructCallableInsn("cb", "recv", "_handler");
        assertEquals("recv", ((VariableOperand) insn.operands().getFirst()).id());
        assertEquals("_handler", ((StringOperand) insn.operands().get(1)).value());
    }

    @Test
    void serializesReceiverAndQuotedMethodName() {
        var insn = new ConstructCallableInsn("cb", "recv", "_handler");
        assertEquals("$cb = construct_callable $recv \"_handler\";\n", serialize(insn));
    }

    @Test
    void parsesReceiverAndQuotedMethodName() {
        var insns = parse("$cb = construct_callable $self \"_handler\";\n");
        assertEquals(1, insns.size());
        var insn = assertInstanceOf(ConstructCallableInsn.class, insns.getFirst());
        assertEquals("cb", insn.resultId());
        assertEquals("self", insn.receiverVarId());
        assertEquals("_handler", insn.methodName());
    }

    @Test
    void roundTripPreservesOperands() {
        var original = new ConstructCallableInsn("result", "other", "ready");
        var parsed = assertInstanceOf(
                ConstructCallableInsn.class,
                parse(serialize(original)).getFirst()
        );
        assertTrue(original.checkEquals(parsed), () -> "round-trip failed: " + serialize(original));
    }

    @Test
    void parseRejectsOldOneOperandForm() {
        var ex = assertThrows(
                LirInsnParsingException.class,
                () -> parse("$cb = construct_callable \"_handler\";\n")
        );
        assertTrue(ex.getMessage().contains("operand"), ex.getMessage());
    }

    @Test
    void parseRejectsMissingMethodName() {
        var ex = assertThrows(
                LirInsnParsingException.class,
                () -> parse("$cb = construct_callable $self;\n")
        );
        assertTrue(ex.getMessage().contains("operand"), ex.getMessage());
    }

    @Test
    void parseRejectsStringReceiver() {
        var ex = assertThrows(
                LirInsnParsingException.class,
                () -> parse("$cb = construct_callable \"self\" \"_handler\";\n")
        );
        assertTrue(
                ex.getMessage().contains("variable") || ex.getMessage().contains("Expected"),
                ex.getMessage()
        );
    }

    @Test
    void parseRejectsVariableMethodName() {
        assertThrows(
                LirInsnParsingException.class,
                () -> parse("$cb = construct_callable $self $name;\n")
        );
    }

    @Test
    void doesNotCollideWithConstructSignalOpcode() {
        assertNotEquals(GdInstruction.CONSTRUCT_CALLABLE, GdInstruction.CONSTRUCT_SIGNAL);
        assertEquals("construct_callable", GdInstruction.CONSTRUCT_CALLABLE.opcode());
        assertEquals("construct_signal", GdInstruction.CONSTRUCT_SIGNAL.opcode());
    }
}
