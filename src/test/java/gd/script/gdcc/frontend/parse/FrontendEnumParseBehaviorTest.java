package gd.script.gdcc.frontend.parse;

import dev.superice.gdparser.frontend.ast.EnumDeclaration;
import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/// Locks the gdparser AST shapes that the skeleton enum pre-pass depends on. These probes exist so
/// enum acceptance rules anchor parser behavior explicitly instead of assuming it.
class FrontendEnumParseBehaviorTest {
    private final GdScriptParserService parserService = new GdScriptParserService();

    @Test
    void parseNamedAndAnonymousEnumsProduceTypedDeclarations() {
        var unit = parse("enum_shapes.gd", """
                enum State { IDLE, JUMP = 5 }
                enum { RED, GREEN }
                """);

        var named = assertInstanceOf(EnumDeclaration.class, unit.ast().statements().get(0));
        assertAll(
                () -> assertEquals("State", named.name()),
                () -> assertEquals(2, named.members().size()),
                () -> assertEquals("IDLE", named.members().getFirst().name()),
                () -> assertNull(named.members().getFirst().value()),
                () -> assertNotNull(named.members().get(1).value())
        );

        var anonymous = assertInstanceOf(EnumDeclaration.class, unit.ast().statements().get(1));
        assertAll(
                () -> assertNull(anonymous.name()),
                () -> assertEquals(2, anonymous.members().size())
        );
    }

    @Test
    void parseEmptyEnumsProducePhantomBlankMember() {
        // gdparser error recovery maps an empty enum body to one phantom member with an empty
        // name and keeps parse diagnostics empty, so the skeleton pre-pass owns the
        // "at least one member" diagnostic for both naming forms.
        var named = assertInstanceOf(EnumDeclaration.class,
                parse("enum_empty_named.gd", "enum State {}\n").ast().statements().getFirst());
        assertAll(
                () -> assertEquals("State", named.name()),
                () -> assertEquals(1, named.members().size()),
                () -> assertTrue(named.members().getFirst().name().isBlank())
        );

        var anonymous = assertInstanceOf(EnumDeclaration.class,
                parse("enum_empty_anonymous.gd", "enum {}\n").ast().statements().getFirst());
        assertAll(
                () -> assertNull(anonymous.name()),
                () -> assertEquals(1, anonymous.members().size()),
                () -> assertTrue(anonymous.members().getFirst().name().isBlank())
        );
    }

    @Test
    void parseTrailingCommaEnumKeepsOnlyRealMembers() {
        var unit = parse("enum_trailing_comma.gd", """
                enum { A, B, }
                """);

        var anonymous = assertInstanceOf(EnumDeclaration.class, unit.ast().statements().getFirst());
        assertEquals(2, anonymous.members().size());
    }

    private FrontendSourceUnit parse(String fileName, String source) {
        var diagnostics = new DiagnosticManager();
        var unit = parserService.parseUnit(Path.of("tmp", fileName), source, diagnostics);
        assertTrue(diagnostics.snapshot().isEmpty());
        return unit;
    }
}
