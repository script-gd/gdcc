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

/// Anchors the frozen LIR contract for `construct_signal`.
///
/// Covers opcode shape, `(receiver, signal_name)` operands, serialize/parse round-trip,
/// and negative operand-count / operand-kind cases.
class ConstructSignalInsnContractTest {

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
    void opcodeIsConstructSignal() {
        var insn = new ConstructSignalInsn("result", "self", "pinged");
        assertEquals(GdInstruction.CONSTRUCT_SIGNAL, insn.opcode());
        assertEquals("construct_signal", insn.opcode().opcode());
    }

    @Test
    void implementsConstructionInstruction() {
        assertInstanceOf(ConstructionInstruction.class, new ConstructSignalInsn("r", "self", "pinged"));
    }

    @Test
    void operandKindsMatchEnumDeclaration() {
        var declared = GdInstruction.CONSTRUCT_SIGNAL.operandKinds();
        assertEquals(List.of(OperandKind.VARIABLE, OperandKind.STRING), declared);
        assertEquals(2, GdInstruction.CONSTRUCT_SIGNAL.minOperands());
        assertEquals(2, GdInstruction.CONSTRUCT_SIGNAL.maxOperands());
        assertEquals(ReturnKind.REQUIRED, GdInstruction.CONSTRUCT_SIGNAL.returnKind());
    }

    @Test
    void operandsAreReceiverThenSignalName() {
        var insn = new ConstructSignalInsn("sig", "recv", "ready");
        assertEquals("recv", ((VariableOperand) insn.operands().getFirst()).id());
        assertEquals("ready", ((StringOperand) insn.operands().get(1)).value());
    }

    @Test
    void serializesReceiverAndQuotedSignalName() {
        var insn = new ConstructSignalInsn("sig", "recv", "pinged");
        assertEquals("$sig = construct_signal $recv \"pinged\";\n", serialize(insn));
    }

    @Test
    void parsesReceiverAndQuotedSignalName() {
        var insns = parse("$sig = construct_signal $self \"pinged\";\n");
        assertEquals(1, insns.size());
        var insn = assertInstanceOf(ConstructSignalInsn.class, insns.getFirst());
        assertEquals("sig", insn.resultId());
        assertEquals("self", insn.receiverVarId());
        assertEquals("pinged", insn.signalName());
    }

    @Test
    void roundTripPreservesOperands() {
        var original = new ConstructSignalInsn("result", "other", "changed");
        var parsed = assertInstanceOf(
                ConstructSignalInsn.class,
                parse(serialize(original)).getFirst()
        );
        assertTrue(original.checkEquals(parsed), () -> "round-trip failed: " + serialize(original));
    }

    @Test
    void parseRejectsMissingSignalName() {
        var ex = assertThrows(
                LirInsnParsingException.class,
                () -> parse("$sig = construct_signal $self;\n")
        );
        assertTrue(ex.getMessage().contains("operand"), ex.getMessage());
    }

    @Test
    void parseRejectsStringReceiver() {
        var ex = assertThrows(
                LirInsnParsingException.class,
                () -> parse("$sig = construct_signal \"self\" \"pinged\";\n")
        );
        assertTrue(
                ex.getMessage().contains("variable") || ex.getMessage().contains("Expected"),
                ex.getMessage()
        );
    }

    @Test
    void parseRejectsVariableSignalName() {
        assertThrows(
                LirInsnParsingException.class,
                () -> parse("$sig = construct_signal $self $name;\n")
        );
    }

    @Test
    void doesNotCollideWithConstructCallableOpcode() {
        assertNotEquals(GdInstruction.CONSTRUCT_CALLABLE, GdInstruction.CONSTRUCT_SIGNAL);
        assertEquals("construct_callable", GdInstruction.CONSTRUCT_CALLABLE.opcode());
        assertEquals("construct_signal", GdInstruction.CONSTRUCT_SIGNAL.opcode());
    }
}
