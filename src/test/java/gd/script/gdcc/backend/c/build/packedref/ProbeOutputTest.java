package gd.script.gdcc.backend.c.build.packedref;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Anchors the {@link ProbeOutput} parsing contract from both directions: well-formed probe
/// output must survive noise, and malformed PROBE lines must be rejected (they indicate
/// probe-generator bugs that would otherwise silently corrupt golden comparisons).
class ProbeOutputTest {

    @Test
    void parsesProbeLinesInOrderIgnoringEngineNoise() {
        var output = ProbeOutput.parse("""
                Godot Engine v4.5.2.stable.official.6ce3de25a - https://godotengine.org
                PROBE|LOCAL_ALIAS|int=2,2;string=2,2
                some engine warning on stdout
                PROBE|FOR_ITER|sum=6;size=4;mutation_visits=4
                """);

        assertEquals(List.of("LOCAL_ALIAS", "FOR_ITER"), output.caseNames());
        assertEquals("int=2,2;string=2,2", output.requirePayload("LOCAL_ALIAS"));
        assertEquals(
                List.of("PROBE|LOCAL_ALIAS|int=2,2;string=2,2", "PROBE|FOR_ITER|sum=6;size=4;mutation_visits=4"),
                output.probeLines()
        );
    }

    @Test
    void toleratesCrLfAndBlankLines() {
        var output = ProbeOutput.parse("\r\nPROBE|STATIC_VAR|2\r\n\r\n");

        assertEquals(List.of("STATIC_VAR"), output.caseNames());
        assertEquals("2", output.requirePayload("STATIC_VAR"));
    }

    @Test
    void ignoresLookalikeLinesWithoutExactPrefix() {
        // "PROBE" alone and other prefixes must be treated as noise, not as malformed input.
        var output = ProbeOutput.parse("PROBE\nPROBEX|CASE|1\nprobe|CASE|1\n");

        assertTrue(output.caseNames().isEmpty());
    }

    @Test
    void rejectsMissingPayloadSegment() {
        assertThrows(IllegalArgumentException.class, () -> ProbeOutput.parse("PROBE|ONLY_CASE\n"));
    }

    @Test
    void rejectsEmptyPayload() {
        assertThrows(IllegalArgumentException.class, () -> ProbeOutput.parse("PROBE|CASE|\n"));
    }

    @Test
    void rejectsEmptyCaseName() {
        assertThrows(IllegalArgumentException.class, () -> ProbeOutput.parse("PROBE||payload\n"));
    }

    @Test
    void rejectsCaseNameOutsideContractAlphabet() {
        // Lowercase/mixed names break the registry/golden naming contract.
        assertThrows(IllegalArgumentException.class, () -> ProbeOutput.parse("PROBE|mixedCase|1\n"));
    }

    @Test
    void rejectsPayloadContainingPipe() {
        // The format is exactly three segments; a fourth segment means a generator bug.
        assertThrows(IllegalArgumentException.class, () -> ProbeOutput.parse("PROBE|CASE|a|b\n"));
    }

    @Test
    void rejectsDuplicateCase() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ProbeOutput.parse("PROBE|CASE|1\nPROBE|CASE|2\n")
        );
    }

    @Test
    void requirePayloadFailsForAbsentCase() {
        var output = ProbeOutput.parse("PROBE|CASE|1\n");

        assertThrows(IllegalArgumentException.class, () -> output.requirePayload("OTHER"));
    }
}
