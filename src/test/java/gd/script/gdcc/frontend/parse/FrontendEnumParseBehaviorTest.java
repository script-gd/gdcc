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
    void parseEmptyEnumsRetainsDeclarationsWithoutMembersOrRecoveries() {
        var namedUnit = parse("enum_empty_named.gd", "enum State {}\n");
        var named = assertInstanceOf(EnumDeclaration.class, namedUnit.ast().statements().getFirst());
        assertAll(
                () -> assertEquals("State", named.name()),
                () -> assertTrue(named.members().isEmpty()),
                () -> assertTrue(namedUnit.parseRecoveries().isEmpty())
        );

        var anonymousUnit = parse("enum_empty_anonymous.gd", "enum {}\n");
        var anonymous = assertInstanceOf(EnumDeclaration.class, anonymousUnit.ast().statements().getFirst());
        assertAll(
                () -> assertNull(anonymous.name()),
                () -> assertTrue(anonymous.members().isEmpty()),
                () -> assertTrue(anonymousUnit.parseRecoveries().isEmpty())
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
