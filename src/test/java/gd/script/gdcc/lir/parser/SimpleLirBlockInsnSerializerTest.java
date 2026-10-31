package gd.script.gdcc.lir.parser;

import gd.script.gdcc.enums.LifecycleProvenance;
import gd.script.gdcc.lir.LirInstruction;
import gd.script.gdcc.lir.insn.*;
import gd.script.gdcc.lir.insn.*;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class SimpleLirBlockInsnSerializerTest {
    @Test
    public void serialize_basicSequence_matchesExpectedLines() throws Exception {
        var insnList = List.<LirInstruction>of(
                new LineNumberInsn(9),
                new LiteralStringInsn("0", "Camera init"),
                new PackVariantInsn("1", "0"),
                new CallGlobalInsn(null, "print", List.of(new LirInstruction.VariableOperand("1"))),
                new DestructInsn("1"),
                new DestructInsn("0"),
                new ReturnInsn(null)
        );

        var serializer = new SimpleLirBlockInsnSerializer();
        var sw = new StringWriter();
        serializer.serialize(insnList, sw);
        var out = sw.toString();
        System.out.println(out);

        assertTrue(out.contains("line_number 9;"));
        assertTrue(out.contains("$0 = literal_string \"Camera init\";"));
        assertTrue(out.contains("$1 = pack_variant $0;"));
        assertTrue(out.contains("call_global \"print\" $1;"));
        assertTrue(out.contains("destruct $1;"));
        assertTrue(out.contains("destruct $0;"));
        assertTrue(out.contains("return;"));
    }

    @Test
    public void serialize_objectIsNullKeepsResultAndOperand() throws Exception {
        var insnList = List.<LirInstruction>of(new ObjectIsNullInsn("result", "obj"));

        var serializer = new SimpleLirBlockInsnSerializer();
        var sw = new StringWriter();
        serializer.serialize(insnList, sw);

        assertEquals("$result = object_is_null $obj;\n", sw.toString());
    }

    @Test
    public void serialize_assertObjectLiveHasNoResultAndNoProvenance() throws Exception {
        var insnList = List.<LirInstruction>of(new AssertObjectLiveInsn("obj"));

        var serializer = new SimpleLirBlockInsnSerializer();
        var sw = new StringWriter();
        serializer.serialize(insnList, sw);

        assertEquals("assert_object_live $obj;\n", sw.toString());
    }

    @Test
    public void serialize_assertConditionOnlyHasNoResultAndNoProvenance() throws Exception {
        var serializer = new SimpleLirBlockInsnSerializer();
        var sw = new StringWriter();
        serializer.serialize(List.of(new AssertInsn("cond", null)), sw);
        assertEquals("assert $cond;\n", sw.toString());
    }

    @Test
    public void serialize_assertConditionAndMessageHasNoResultAndNoProvenance() throws Exception {
        var serializer = new SimpleLirBlockInsnSerializer();
        var sw = new StringWriter();
        serializer.serialize(List.of(new AssertInsn("cond", "msg")), sw);
        assertEquals("assert $cond $msg;\n", sw.toString());
    }

    @Test
    public void serialize_lifecycleInstructionWithProvenance_appendsEnumToken() throws Exception {
        var insnList = List.<LirInstruction>of(
                new DestructInsn("0", LifecycleProvenance.AUTO_GENERATED),
                new TryOwnObjectInsn("obj", LifecycleProvenance.INTERNAL),
                new TryReleaseObjectInsn("obj", LifecycleProvenance.USER_EXPLICIT),
                new DestructInsn("1")
        );
        var serializer = new SimpleLirBlockInsnSerializer();
        var sw = new StringWriter();
        serializer.serialize(insnList, sw);
        var out = sw.toString();

        assertTrue(out.contains("destruct $0 \"AUTO_GENERATED\";"));
        assertTrue(out.contains("try_own_object $obj \"INTERNAL\";"));
        assertTrue(out.contains("try_release_object $obj \"USER_EXPLICIT\";"));
        // Unknown stays backward-compatible with old syntax.
        assertTrue(out.contains("destruct $1;"));
    }

    @Test
    public void serialize_indexedInstructionsUseVariableIndexOperand() throws Exception {
        var insnList = List.<LirInstruction>of(
                new VariantGetIndexedInsn("result", "arr", "idx"),
                new VariantSetIndexedInsn("arr", "idx", "value")
        );

        var serializer = new SimpleLirBlockInsnSerializer();
        var sw = new StringWriter();
        serializer.serialize(insnList, sw);
        var out = sw.toString();

        assertTrue(out.contains("$result = variant_get_indexed $arr $idx;"), out);
        assertTrue(out.contains("variant_set_indexed $arr $idx $value;"), out);
    }

    @Test
    public void serialize_namedInstructionsUseStringNameVariableOperand() throws Exception {
        var insnList = List.<LirInstruction>of(
                new VariantGetNamedInsn("result", "obj", "name"),
                new VariantSetNamedInsn("obj", "name", "value")
        );

        var serializer = new SimpleLirBlockInsnSerializer();
        var sw = new StringWriter();
        serializer.serialize(insnList, sw);
        var out = sw.toString();

        assertTrue(out.contains("$result = variant_get_named $obj $name;"), out);
        assertTrue(out.contains("variant_set_named $obj $name $value;"), out);
    }

    @Test
    public void serialize_callIntrinsicInstruction() throws Exception {
        var insnList = List.<LirInstruction>of(
                new CallIntrinsicInsn("f", "c_int_to_float", List.of(new LirInstruction.VariableOperand("i")))
        );

        var serializer = new SimpleLirBlockInsnSerializer();
        var sw = new StringWriter();
        serializer.serialize(insnList, sw);
        var out = sw.toString();

        assertTrue(out.contains("$f = call_intrinsic \"c_int_to_float\" $i;"), out);
    }

    @Test
    public void serialize_callIntrinsicVectorInstructionMatchesTextShape() throws Exception {
        var insnList = List.<LirInstruction>of(
                new CallIntrinsicInsn("v", "c_vector3i_to_vector3", List.of(new LirInstruction.VariableOperand("vi")))
        );

        var serializer = new SimpleLirBlockInsnSerializer();
        var sw = new StringWriter();
        serializer.serialize(insnList, sw);

        assertEquals("$v = call_intrinsic \"c_vector3i_to_vector3\" $vi;\n", sw.toString());
    }

    @Test
    public void serialize_callIntrinsicRangeIteratorInstructionMatchesTextShape() throws Exception {
        var insnList = List.<LirInstruction>of(
                new CallIntrinsicInsn("iter", "gdcc.for_range_iter.init", List.of(
                        new LirInstruction.VariableOperand("start"),
                        new LirInstruction.VariableOperand("end"),
                        new LirInstruction.VariableOperand("step")
                ))
        );

        var serializer = new SimpleLirBlockInsnSerializer();
        var sw = new StringWriter();
        serializer.serialize(insnList, sw);

        assertEquals("$iter = call_intrinsic \"gdcc.for_range_iter.init\" $start $end $step;\n", sw.toString());
    }

    @Test
    public void serialize_assignInstruction() throws Exception {
        var insnList = List.<LirInstruction>of(new AssignInsn("a", "b"));

        var serializer = new SimpleLirBlockInsnSerializer();
        var sw = new StringWriter();
        serializer.serialize(insnList, sw);
        var out = sw.toString();

        assertTrue(out.contains("$a = assign $b;"), out);
    }

    @Test
    public void serialize_floatOperandUsesCanonicalDoubleSpellings() throws Exception {
        // Canonical output is Java `Double.toString`: integral doubles keep the float marker
        // (`1.0`, never `1`), signed zero keeps its sign, and non-finite values use exactly the
        // spellings the parser recognizes as FLOAT tokens.
        var insnList = List.<LirInstruction>of(
                new LiteralFloatInsn("integral", 1.0),
                new LiteralFloatInsn("neg_zero", -0.0),
                new LiteralFloatInsn("nan", Double.NaN),
                new LiteralFloatInsn("inf", Double.POSITIVE_INFINITY),
                new LiteralFloatInsn("neg_inf", Double.NEGATIVE_INFINITY),
                new LiteralFloatInsn("precise", 0.30000000000000004)
        );

        var serializer = new SimpleLirBlockInsnSerializer();
        var sw = new StringWriter();
        serializer.serialize(insnList, sw);
        var out = sw.toString();

        assertAll(
                () -> assertTrue(out.contains("$integral = literal_float 1.0;"), out),
                () -> assertTrue(out.contains("$neg_zero = literal_float -0.0;"), out),
                () -> assertTrue(out.contains("$nan = literal_float NaN;"), out),
                () -> assertTrue(out.contains("$inf = literal_float Infinity;"), out),
                () -> assertTrue(out.contains("$neg_inf = literal_float -Infinity;"), out),
                () -> assertTrue(out.contains("$precise = literal_float 0.30000000000000004;"), out)
        );
    }

    @Test
    public void serialize_literalAndConstructChainRoundTripsExactly() throws Exception {
        // The constant materialization chain shape: scalar leaf literals feeding a
        // construct_builtin of variable-only operands, then an assign of the result.
        var insnList = List.<LirInstruction>of(
                new LiteralFloatInsn("cfg_const_leaf_0", 1.0),
                new LiteralFloatInsn("cfg_const_leaf_1", -0.5),
                new ConstructBuiltinInsn("out_vector2", List.of(
                        new LirInstruction.VariableOperand("cfg_const_leaf_0"),
                        new LirInstruction.VariableOperand("cfg_const_leaf_1")
                )),
                new LiteralIntInsn("cfg_const_leaf_2", Long.MIN_VALUE),
                new LiteralStringInsn("cfg_const_leaf_3", "payload"),
                new AssignInsn("alias_out", "out_vector2")
        );

        var serializer = new SimpleLirBlockInsnSerializer();
        var sw = new StringWriter();
        serializer.serialize(insnList, sw);
        var reparsed = new SimpleLirBlockInsnParser().parse(new StringReader(sw.toString()));

        assertEquals(insnList.size(), reparsed.size());
        for (var i = 0; i < insnList.size(); i++) {
            var expected = insnList.get(i);
            var index = i;
            assertTrue(expected.checkEquals(reparsed.get(i)),
                    () -> "instruction " + index + ": " + expected);
        }
    }

    @Test
    public void serialize_stringOperandEscapesXmlUnsafeChars() throws Exception {
        // The serialized text must stay inside the XML 1.0 character set so DOM embedding
        // never sees a real NUL/control char, while the five named escapes keep their
        // historical short spellings.
        var insnList = List.<LirInstruction>of(
                new LiteralStringInsn("with_nul", "a\0b"),
                new LiteralStringInsn("with_controls", "\u0001\u001F\u007F"),
                new LiteralStringInsn("lone_surrogate", "\uD800"),
                new LiteralStringInsn("noncharacter", "\uFFFE"),
                new LiteralStringInsn("backslash_u", "a\\u0000b"),
                new LiteralStringInsn("kept_raw", "中文\uD83D\uDE00")
        );
        var sw = new StringWriter();
        new SimpleLirBlockInsnSerializer().serialize(insnList, sw);
        var out = sw.toString();
        assertAll(
                () -> assertTrue(out.contains("\"a\\u0000b\""), out),
                () -> assertTrue(out.contains("\"\\u0001\\u001F\\u007F\""), out),
                () -> assertTrue(out.contains("\"\\uD800\""), out),
                () -> assertTrue(out.contains("\"\\uFFFE\""), out),
                // A real backslash escapes first, so literal `\u0000` text stays distinct
                // from the serialized form of a real NUL.
                () -> assertTrue(out.contains("\"a\\\\u0000b\""), out),
                // XML-legal text (multi-byte and valid surrogate pairs) passes through raw.
                () -> assertTrue(out.contains("\"中文\uD83D\uDE00\""), out)
        );
    }

    @Test
    public void serialize_stringOperandRoundTripsXmlUnsafePayloads() throws Exception {
        var payloads = List.of("plain", "a\0b", "\u0001\u001F\u007F\u0085", "\uFFFE",
                "中文\uD83D\uDE00", "a\\u0000b", "a\0" + "1", "\uD800", "line\nand\ttab");
        var insnList = payloads.stream()
                .<LirInstruction>map(payload -> new LiteralStringInsn("s", payload))
                .toList();

        var sw = new StringWriter();
        new SimpleLirBlockInsnSerializer().serialize(insnList, sw);
        var reparsed = new SimpleLirBlockInsnParser().parse(new StringReader(sw.toString()));

        assertEquals(payloads.size(), reparsed.size());
        for (var i = 0; i < payloads.size(); i++) {
            var literal = assertInstanceOf(LiteralStringInsn.class, reparsed.get(i));
            var index = i;
            assertEquals(payloads.get(i), literal.value(), () -> "payload " + index);
        }
    }
}
