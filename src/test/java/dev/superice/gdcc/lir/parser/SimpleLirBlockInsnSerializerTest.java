package dev.superice.gdcc.lir.parser;

import dev.superice.gdcc.enums.LifecycleProvenance;
import dev.superice.gdcc.lir.LirInstruction;
import dev.superice.gdcc.lir.insn.*;
import org.junit.jupiter.api.Test;

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
}
