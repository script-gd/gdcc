package dev.superice.gdcc.lir.parser;

import dev.superice.gdcc.enums.LifecycleProvenance;
import dev.superice.gdcc.exception.LirInsnParsingException;
import dev.superice.gdcc.lir.LirInstruction;
import dev.superice.gdcc.lir.insn.*;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class SimpleLirBlockInsnParserTest {

    // --- helpers ---
    private static List<LirInstruction> parse(String input) {
        var parser = new SimpleLirBlockInsnParser();
        return parser.parse(new StringReader(input));
    }

    @SuppressWarnings("SameParameterValue")
    private static void assertParseError(String line, int expectedLine, int expectedCol, String expectedReasonSubstring) {
        var parser = new SimpleLirBlockInsnParser();
        var ex = assertThrows(LirInsnParsingException.class, () -> parser.parse(new StringReader(line)));
        assertEquals(expectedLine, ex.lineNumber);
        assertEquals(expectedCol, ex.columnNumber);
        assertEquals(line, ex.lirLine);
        assertTrue(ex.reason.contains(expectedReasonSubstring), () -> "Expected reason to contain '" + expectedReasonSubstring + "' but was '" + ex.reason + "'");
    }

    // --- tests ---
    @Test
    public void parse_validInstructions() {
        var input = """
                $0 = literal_string "Camera init";
                call_global "print" $1;
                goto entry;
                go_if $2 true_bb false_bb;
                return $3;
                """;

        var insns = parse(input);
        assertEquals(5, insns.size());

        assertInstanceOf(LiteralStringInsn.class, insns.getFirst());
        var s0 = (LiteralStringInsn) insns.getFirst();
        assertEquals("0", s0.resultId());
        assertEquals("Camera init", s0.value());

        assertInstanceOf(CallGlobalInsn.class, insns.get(1));
        var c1 = (CallGlobalInsn) insns.get(1);
        assertNull(c1.resultId());
        assertEquals("print", c1.functionName());
        assertEquals(1, c1.args().size());
        assertInstanceOf(LirInstruction.VariableOperand.class, c1.args().getFirst());

        assertInstanceOf(GotoInsn.class, insns.get(2));
        var g = (GotoInsn) insns.get(2);
        assertEquals("entry", g.targetBbId());

        assertInstanceOf(GoIfInsn.class, insns.get(3));
        var gi = (GoIfInsn) insns.get(3);
        assertEquals("2", gi.conditionVarId());
        assertEquals("true_bb", gi.trueBbId());
        assertEquals("false_bb", gi.falseBbId());

        assertInstanceOf(ReturnInsn.class, insns.get(4));
        var r = (ReturnInsn) insns.get(4);
        assertEquals("3", r.returnValueId());
    }

    @Test
    public void parse_stringLikeLiteralsNormalizeRuntimePayloads() {
        var input = """
                $0 = literal_string "line\\nbreak";
                $1 = literal_string_name "Node_2D";
                $2 = literal_string "tab\\tquote\\"";
                """;

        var insns = parse(input);
        assertEquals(3, insns.size());

        var stringInsn = assertInstanceOf(LiteralStringInsn.class, insns.get(0));
        var stringNameInsn = assertInstanceOf(LiteralStringNameInsn.class, insns.get(1));
        var escapedStringInsn = assertInstanceOf(LiteralStringInsn.class, insns.get(2));

        assertAll(
                () -> assertEquals("line\nbreak", stringInsn.value()),
                () -> assertEquals("Node_2D", stringNameInsn.value()),
                () -> assertEquals("tab\tquote\"", escapedStringInsn.value())
        );
    }

    @Test
    public void parse_moreOpcodes() {
        var input = """
                $1 = literal_int 42;
                $2 = literal_float 3.14;
                $3 = literal_bool true;
                $4 = unary_op "NEGATE" $5;
                $6 = binary_op "ADD" $7 $8;
                """;
        var insns = parse(input);
        assertEquals(5, insns.size());
        assertInstanceOf(LiteralIntInsn.class, insns.getFirst());
        assertInstanceOf(LiteralFloatInsn.class, insns.get(1));
        assertInstanceOf(LiteralBoolInsn.class, insns.get(2));
        assertInstanceOf(UnaryOpInsn.class, insns.get(3));
        assertInstanceOf(BinaryOpInsn.class, insns.get(4));
    }

    @Test
    public void parse_indexedInstructionsUseVariableIndexOperand() {
        var input = """
                $result = variant_get_indexed $arr $idx;
                variant_set_indexed $arr $idx $value;
                """;
        var insns = parse(input);
        assertEquals(2, insns.size());

        var getInsn = assertInstanceOf(VariantGetIndexedInsn.class, insns.getFirst());
        assertEquals("result", getInsn.resultId());
        assertEquals("arr", getInsn.variantId());
        assertEquals("idx", getInsn.indexId());

        var setInsn = assertInstanceOf(VariantSetIndexedInsn.class, insns.getLast());
        assertEquals("arr", setInsn.variantId());
        assertEquals("idx", setInsn.indexId());
        assertEquals("value", setInsn.valueId());
    }

    @Test
    public void parse_namedInstructionsUseStringNameVariableOperand() {
        var input = """
                $result = variant_get_named $obj $name;
                variant_set_named $obj $name $value;
                """;
        var insns = parse(input);
        assertEquals(2, insns.size());

        var getInsn = assertInstanceOf(VariantGetNamedInsn.class, insns.getFirst());
        assertEquals("result", getInsn.resultId());
        assertEquals("obj", getInsn.namedVariantId());
        assertEquals("name", getInsn.nameId());

        var setInsn = assertInstanceOf(VariantSetNamedInsn.class, insns.getLast());
        assertEquals("obj", setInsn.namedVariantId());
        assertEquals("name", setInsn.nameId());
        assertEquals("value", setInsn.valueId());
    }

    @Test
    public void parse_assignInstruction() {
        var input = "$a = assign $b;";
        var insns = parse(input);
        assertEquals(1, insns.size());

        var assignInsn = assertInstanceOf(AssignInsn.class, insns.getFirst());
        assertEquals("a", assignInsn.resultId());
        assertEquals("b", assignInsn.sourceId());
    }

    @SuppressWarnings("TrailingWhitespacesInTextBlock")
    @Test
    public void parse_whitespaceVariants() {
        // tabs, leading and trailing spaces, empty lines
        var input = """
                \t$0 = literal_string "WithTab";   
                
                  call_global   "print"   $0;\t
                \tgoto   entry  ;
                """;
        var insns = parse(input);
        assertEquals(3, insns.size());
        assertInstanceOf(LiteralStringInsn.class, insns.getFirst());
        var s = (LiteralStringInsn) insns.getFirst();
        assertEquals("WithTab", s.value());
        assertInstanceOf(CallGlobalInsn.class, insns.get(1));
        assertInstanceOf(GotoInsn.class, insns.get(2));
    }

    @Test
    public void parse_unknownOpcodeReportsPosition() {
        var line = "$0 = unknown_op 1;";
        // opcode starts after "$0 = " which is 5 chars, so column should point to 6 (1-based)
        assertParseError(line, 1, 6, "Unknown opcode");
    }

    @Test
    public void parse_missingVariableIdReportsColumn() {
        var line = "$ = literal_int 5;";
        // column should point right after '$'
        assertParseError(line, 1, 2, "Expected variable id");
    }

    @Test
    public void parse_wrongOperandTypeReportsPosition() {
        var line = "$0 = literal_int \"abc\";";
        var col = line.indexOf('"') + 1;
        assertParseError(line, 1, col, "Expected integer operand");
    }

    @Test
    public void parse_invalidOperandCountReports() {
        var line = "goto a b;"; // goto expects 1 operand
        var parser = new SimpleLirBlockInsnParser();
        var ex = assertThrows(LirInsnParsingException.class, () -> parser.parse(new StringReader(line)));
        assertEquals(1, ex.lineNumber);
        assertEquals(line, ex.lirLine);
        assertTrue(ex.reason.contains("Invalid operand count") || ex.reason.contains("Too many operands"));
        // parser may report position at the end of the line; ensure column is within valid bounds
        assertTrue(ex.columnNumber >= 1 && ex.columnNumber <= line.length() + 1, () -> "Unexpected column: " + ex.columnNumber);
    }

    @Test
    public void parse_errorPositionWithLeadingTabsAndSpaces() {
        var line = "\t  unknown_x 1;"; // leading tab + spaces
        var col = line.indexOf("unknown_x") + 1;
        assertParseError(line, 1, col, "Unknown opcode");
    }

    @Test
    public void parse_errorPositionWithTrailingWhitespace() {
        var line = "   $0 = literal_int    \"notint\"   ;  ";
        var col = line.indexOf('"') + 1;
        assertParseError(line, 1, col, "Expected integer operand");
    }

    @Test
    public void parse_lifecycleInstructionsWithAndWithoutProvenance() {
        var input = """
                destruct $0;
                try_own_object $obj "INTERNAL";
                try_release_object $obj "AUTO_GENERATED";
                """;
        var insns = parse(input);
        assertEquals(3, insns.size());

        var destructInsn = assertInstanceOf(DestructInsn.class, insns.getFirst());
        assertEquals(LifecycleProvenance.UNKNOWN, destructInsn.getProvenance());

        var ownInsn = assertInstanceOf(TryOwnObjectInsn.class, insns.get(1));
        assertEquals(LifecycleProvenance.INTERNAL, ownInsn.getProvenance());

        var releaseInsn = assertInstanceOf(TryReleaseObjectInsn.class, insns.getLast());
        assertEquals(LifecycleProvenance.AUTO_GENERATED, releaseInsn.getProvenance());
    }

    @Test
    public void parse_lifecycleInstructionWithUnknownProvenanceShouldFail() {
        var line = "destruct $0 \"NOT_A_PROVENANCE\";";
        var parser = new SimpleLirBlockInsnParser();
        var ex = assertThrows(LirInsnParsingException.class, () -> parser.parse(new StringReader(line)));
        assertEquals(1, ex.lineNumber);
        assertEquals(line, ex.lirLine);
        assertTrue(ex.reason.contains("Unknown lifecycle provenance"));
    }
}
