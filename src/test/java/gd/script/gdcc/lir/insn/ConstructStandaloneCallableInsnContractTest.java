package gd.script.gdcc.lir.insn;

import gd.script.gdcc.enums.GdInstruction;
import gd.script.gdcc.enums.GdInstruction.OperandKind;
import gd.script.gdcc.enums.GdInstruction.ReturnKind;
import gd.script.gdcc.exception.LirInsnParsingException;
import gd.script.gdcc.lir.LirInstruction;
import gd.script.gdcc.lir.LirInstruction.StringOperand;
import gd.script.gdcc.lir.parser.SimpleLirBlockInsnParser;
import gd.script.gdcc.lir.parser.SimpleLirBlockInsnSerializer;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/// Anchors the frozen LIR contract for `construct_standalone_callable`.
class ConstructStandaloneCallableInsnContractTest {

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
    void opcodeIsConstructStandaloneCallable() {
        var insn = new ConstructStandaloneCallableInsn("cb", StandaloneCallableKind.UTILITY, "", "print");
        assertEquals(GdInstruction.CONSTRUCT_STANDALONE_CALLABLE, insn.opcode());
        assertEquals("construct_standalone_callable", insn.opcode().opcode());
    }

    @Test
    void implementsConstructionInstruction() {
        assertInstanceOf(
                ConstructionInstruction.class,
                new ConstructStandaloneCallableInsn("r", StandaloneCallableKind.UTILITY, "", "print")
        );
    }

    @Test
    void operandKindsMatchEnumDeclaration() {
        var declared = GdInstruction.CONSTRUCT_STANDALONE_CALLABLE.operandKinds();
        assertEquals(List.of(OperandKind.STRING, OperandKind.STRING, OperandKind.STRING), declared);
        assertEquals(3, GdInstruction.CONSTRUCT_STANDALONE_CALLABLE.minOperands());
        assertEquals(3, GdInstruction.CONSTRUCT_STANDALONE_CALLABLE.maxOperands());
        assertEquals(ReturnKind.REQUIRED, GdInstruction.CONSTRUCT_STANDALONE_CALLABLE.returnKind());
    }

    @Test
    void serializesKindOwnerAndName() {
        var insn = new ConstructStandaloneCallableInsn(
                "cb",
                StandaloneCallableKind.STATIC_GDCC,
                "Worker",
                "build"
        );
        assertEquals("$cb = construct_standalone_callable \"static_gdcc\" \"Worker\" \"build\";\n", serialize(insn));
    }

    @Test
    void parsesUtilityWithEmptyOwner() {
        var insn = assertInstanceOf(
                ConstructStandaloneCallableInsn.class,
                parse("$cb = construct_standalone_callable \"utility\" \"\" \"print\";\n").getFirst()
        );
        assertEquals("cb", insn.resultId());
        assertEquals(StandaloneCallableKind.UTILITY, insn.kind());
        assertEquals("", insn.ownerName());
        assertEquals("print", insn.callableName());
    }

    @Test
    void roundTripPreservesOperands() {
        var original = new ConstructStandaloneCallableInsn(
                "result",
                StandaloneCallableKind.STATIC_ENGINE,
                "JSON",
                "parse_string"
        );
        var parsed = assertInstanceOf(
                ConstructStandaloneCallableInsn.class,
                parse(serialize(original)).getFirst()
        );
        assertTrue(original.checkEquals(parsed), () -> "round-trip failed: " + serialize(original));
    }

    @Test
    void parseRejectsUnknownKind() {
        var ex = assertThrows(
                LirInsnParsingException.class,
                () -> parse("$cb = construct_standalone_callable \"lambda\" \"\" \"print\";\n")
        );
        assertTrue(ex.getMessage().contains("unknown construct_standalone_callable kind"), ex.getMessage());
    }

    @Test
    void parseRejectsMissingOwnerForStaticKind() {
        var ex = assertThrows(
                LirInsnParsingException.class,
                () -> parse("$cb = construct_standalone_callable \"static_gdcc\" \"\" \"build\";\n")
        );
        assertTrue(ex.getMessage().contains("owner must not be blank"), ex.getMessage());
    }

    @Test
    void parseRejectsUtilityWithOwner() {
        var ex = assertThrows(
                LirInsnParsingException.class,
                () -> parse("$cb = construct_standalone_callable \"utility\" \"Global\" \"print\";\n")
        );
        assertTrue(ex.getMessage().contains("utility owner must be empty"), ex.getMessage());
    }

    @Test
    void parseRejectsEmptyCallableName() {
        var ex = assertThrows(
                LirInsnParsingException.class,
                () -> parse("$cb = construct_standalone_callable \"utility\" \"\" \"\";\n")
        );
        assertTrue(ex.getMessage().contains("callableName"), ex.getMessage());
    }

    @Test
    void parseRejectsMissingOperand() {
        var ex = assertThrows(
                LirInsnParsingException.class,
                () -> parse("$cb = construct_standalone_callable \"utility\" \"print\";\n")
        );
        assertTrue(ex.getMessage().contains("operand"), ex.getMessage());
    }

    @Test
    void operandsAreKindThenOwnerThenName() {
        var insn = new ConstructStandaloneCallableInsn("cb", StandaloneCallableKind.UTILITY, "", "lerp");
        assertEquals("utility", ((StringOperand) insn.operands().get(0)).value());
        assertEquals("", ((StringOperand) insn.operands().get(1)).value());
        assertEquals("lerp", ((StringOperand) insn.operands().get(2)).value());
    }
}
