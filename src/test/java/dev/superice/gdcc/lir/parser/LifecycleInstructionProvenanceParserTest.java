package dev.superice.gdcc.lir.parser;

import dev.superice.gdcc.enums.LifecycleProvenance;
import dev.superice.gdcc.lir.insn.DestructInsn;
import dev.superice.gdcc.lir.insn.TryOwnObjectInsn;
import dev.superice.gdcc.lir.insn.TryReleaseObjectInsn;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

public class LifecycleInstructionProvenanceParserTest {
    @Test
    public void parseAndSerialize_shouldRoundTripLifecycleProvenance() throws Exception {
        var parser = new SimpleLirBlockInsnParser();
        var serializer = new SimpleLirBlockInsnSerializer();
        var input = """
                destruct $v0 "AUTO_GENERATED";
                try_own_object $obj "INTERNAL";
                try_release_object $obj "USER_EXPLICIT";
                """;

        var parsed = parser.parse(new StringReader(input));
        var d0 = assertInstanceOf(DestructInsn.class, parsed.getFirst());
        assertEquals(LifecycleProvenance.AUTO_GENERATED, d0.getProvenance());

        var own = assertInstanceOf(TryOwnObjectInsn.class, parsed.get(1));
        assertEquals(LifecycleProvenance.INTERNAL, own.getProvenance());

        var release = assertInstanceOf(TryReleaseObjectInsn.class, parsed.getLast());
        assertEquals(LifecycleProvenance.USER_EXPLICIT, release.getProvenance());

        var sw = new StringWriter();
        serializer.serialize(parsed, sw);
        var serialized = sw.toString();

        assertEquals(input, serialized);

        var reparsed = parser.parse(new StringReader(serialized));
        var d1 = assertInstanceOf(DestructInsn.class, reparsed.getFirst());
        assertEquals(LifecycleProvenance.AUTO_GENERATED, d1.getProvenance());
    }

    @Test
    public void parseLegacyLifecycleInstruction_shouldDefaultToUnknown() {
        var parser = new SimpleLirBlockInsnParser();
        var input = """
                destruct $v0;
                try_own_object $obj;
                try_release_object $obj;
                """;

        var parsed = parser.parse(new StringReader(input));

        var d0 = assertInstanceOf(DestructInsn.class, parsed.getFirst());
        assertEquals(LifecycleProvenance.UNKNOWN, d0.getProvenance());

        var own = assertInstanceOf(TryOwnObjectInsn.class, parsed.get(1));
        assertEquals(LifecycleProvenance.UNKNOWN, own.getProvenance());

        var release = assertInstanceOf(TryReleaseObjectInsn.class, parsed.getLast());
        assertEquals(LifecycleProvenance.UNKNOWN, release.getProvenance());
    }
}
