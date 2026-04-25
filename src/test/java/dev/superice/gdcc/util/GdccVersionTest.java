package dev.superice.gdcc.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GdccVersionTest {
    @Test
    void currentReadsGeneratedMetadataAndCachesIt() {
        var first = GdccVersion.current();
        var second = GdccVersion.current();

        assertSame(first, second);
        assertAll(
                () -> assertEquals("1.0-SNAPSHOT", first.version()),
                () -> assertFalse(first.branch().isBlank()),
                () -> assertTrue(first.commit().matches("unknown|[0-9a-fA-F]{7,}"), first.commit())
        );
    }

    @Test
    void displayTextIncludesVersionBranchAndCommit() {
        var info = GdccVersion.current();

        assertEquals("gdcc " + info.version() + " (" + info.branch() + " " + info.commit() + ")", GdccVersion.displayText());
    }
}
