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

/// Anchors the frozen LIR contract for `literal_node_path`.
///
/// Covers opcode shape, the single decoded-payload STRING operand, serialize/parse round-trip
/// (including payloads that intentionally look like raw source lexemes), and negative parse cases.
/// Result-variable type and ref checks are backend concerns and stay out of this layer.
class LiteralNodePathInsnContractTest {

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
    void opcodeIsLiteralNodePath() {
        var insn = new LiteralNodePathInsn("result", "a/b");
        assertEquals(GdInstruction.LITERAL_NODE_PATH, insn.opcode());
        assertEquals("literal_node_path", insn.opcode().opcode());
        assertInstanceOf(NewDataInstruction.class, insn);
    }

    @Test
    void operandKindsMatchEnumDeclaration() {
        assertEquals(List.of(OperandKind.STRING), GdInstruction.LITERAL_NODE_PATH.operandKinds());
        assertEquals(1, GdInstruction.LITERAL_NODE_PATH.minOperands());
        assertEquals(1, GdInstruction.LITERAL_NODE_PATH.maxOperands());
        assertEquals(ReturnKind.REQUIRED, GdInstruction.LITERAL_NODE_PATH.returnKind());
    }

    @Test
    void exposesPayloadAsSingleStringOperand() {
        var insn = new LiteralNodePathInsn("result", "a/b");
        assertEquals(List.of(new StringOperand("a/b")), insn.operands());
    }

    @Test
    void serializesPayloadWithLirEscapes() {
        // Quotes and newlines inside the payload use LIR escaping; the payload itself stays decoded.
        var insn = new LiteralNodePathInsn("r", "a\"b\nc");
        assertEquals("$r = literal_node_path \"a\\\"b\\nc\";\n", serialize(insn));
    }

    @Test
    void parsesPayloadWithSpacesAndUtf8() {
        var insns = parse("$r = literal_node_path \"A B 路径\";\n");
        assertEquals(1, insns.size());
        var insn = assertInstanceOf(LiteralNodePathInsn.class, insns.getFirst());
        assertEquals("r", insn.resultId());
        assertEquals("A B 路径", insn.value());
    }

    @Test
    void roundTripPreservesLexemeShapedPayload() {
        // A decoded payload may legitimately look like a raw lexeme (`^"^\"foo\""` decodes to
        // `^"foo"`); the instruction layer must round-trip it without any shape rejection.
        var original = new LiteralNodePathInsn("r", "^\"foo\"");
        var parsed = assertInstanceOf(LiteralNodePathInsn.class, parse(serialize(original)).getFirst());
        assertEquals("^\"foo\"", parsed.value());
        assertTrue(original.checkEquals(parsed), () -> "round-trip failed: " + serialize(original));
    }

    @Test
    void roundTripPreservesWhitespaceAndUnicodePayload() {
        var original = new LiteralNodePathInsn("r", "A B 路径");
        var parsed = assertInstanceOf(LiteralNodePathInsn.class, parse(serialize(original)).getFirst());
        assertTrue(original.checkEquals(parsed));
    }

    @Test
    void parseRejectsNonStringOperand() {
        assertThrows(LirInsnParsingException.class, () -> parse("$r = literal_node_path 1;\n"));
    }

    @Test
    void parseRejectsMissingAndExtraOperands() {
        assertThrows(LirInsnParsingException.class, () -> parse("$r = literal_node_path;\n"));
        assertThrows(LirInsnParsingException.class, () -> parse("$r = literal_node_path \"a\" \"b\";\n"));
    }

    @Test
    void parseWithoutResultProducesNullResultId() {
        // The generic parser does not enforce ReturnKind.REQUIRED; backend codegen rejects a
        // missing result instead.
        var insn = assertInstanceOf(LiteralNodePathInsn.class, parse("literal_node_path \"a/b\";\n").getFirst());
        assertNull(insn.resultId());
        assertEquals("a/b", insn.value());
    }
}
