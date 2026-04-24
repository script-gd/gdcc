package dev.superice.gdcc.api;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiVirtualPathTest {
    @Test
    void parseNormalizesRootAndNestedAbsolutePaths() {
        var root = VirtualPath.parse("/");

        assertTrue(root.isRoot());
        assertEquals("/", root.text());
        assertEquals("/", root.name());
        assertEquals(List.of(), root.segments());
        assertEquals("/", root.prefixText(0));

        var nested = VirtualPath.parse("/src/main.gd");

        assertFalse(nested.isRoot());
        assertEquals("/src/main.gd", nested.text());
        assertEquals("main.gd", nested.name());
        assertEquals(List.of("src", "main.gd"), nested.segments());
        assertEquals("/src", nested.prefixText(1));
        assertEquals("/src/main.gd", nested.prefixText(2));
        assertEquals("/src/generated", VirtualPath.parse("/src").child("generated").text());
        assertThrows(UnsupportedOperationException.class, () -> nested.segments().add("other"));
    }

    @Test
    @SuppressWarnings("DataFlowIssue")
    void parseRejectsInvalidBoundaryPaths() {
        var nullError = assertThrows(NullPointerException.class, () -> VirtualPath.parse(null));
        assertEquals("path must not be null", nullError.getMessage());

        var blankError = assertThrows(IllegalArgumentException.class, () -> VirtualPath.parse("   "));
        assertEquals("path must not be blank", blankError.getMessage());

        assertInvalidPath("src/main.gd", "path must start with '/'");
        assertInvalidPath("/src//main.gd", "path must not contain empty segments");
        assertInvalidPath("/src/", "path must not contain empty segments");
        assertInvalidPath("/src/./main.gd", "path must not contain '.' segments");
        assertInvalidPath("/src/../main.gd", "path must not contain '..' segments");
        assertInvalidPath("\\src\\main.gd", "path must use '/' separators");
    }

    @Test
    void childRejectsInvalidSegments() {
        var base = VirtualPath.parse("/src");

        var emptyError = assertThrows(IllegalArgumentException.class, () -> base.child(""));
        assertTrue(emptyError.getMessage().contains("path must not contain empty segments"));

        var dotError = assertThrows(IllegalArgumentException.class, () -> base.child("."));
        assertTrue(dotError.getMessage().contains("path must not contain '.' segments"));

        var parentError = assertThrows(IllegalArgumentException.class, () -> base.child(".."));
        assertTrue(parentError.getMessage().contains("path must not contain '..' segments"));

        var separatorError = assertThrows(IllegalArgumentException.class, () -> base.child("nested/child"));
        assertTrue(separatorError.getMessage().contains("path segment must not contain separators"));
    }

    private static void assertInvalidPath(String rawPath, String messageFragment) {
        var error = assertThrows(IllegalArgumentException.class, () -> VirtualPath.parse(rawPath));
        assertTrue(error.getMessage().contains(messageFragment));
        assertTrue(error.getMessage().contains("'" + rawPath + "'"));
    }
}
