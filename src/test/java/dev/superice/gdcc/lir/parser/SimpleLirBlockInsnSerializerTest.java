package dev.superice.gdcc.lir.parser;

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
}
