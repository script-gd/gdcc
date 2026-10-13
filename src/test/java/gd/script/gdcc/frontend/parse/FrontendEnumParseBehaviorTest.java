package gd.script.gdcc.frontend.parse;

import dev.superice.gdparser.frontend.ast.EnumDeclaration;
import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.diagnostic.FrontendDiagnosticSeverity;
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
        // gdparser recovery still maps an empty enum body to one phantom member with an empty
        // name, but now also reports the missing enumerator/identifier itself (`parse.lowering`),
        // so the parser owns the empty-enum diagnostic and the skeleton pre-pass only skips the
        // subtree instead of emitting its own "at least one member" error.
        var namedDiagnostics = new DiagnosticManager();
        var named = assertInstanceOf(EnumDeclaration.class,
                parserService.parseUnit(
                        Path.of("tmp", "enum_empty_named.gd"),
                        "enum State {}\n",
                        namedDiagnostics
                ).ast().statements().getFirst());
        assertAll(
                () -> assertEquals("State", named.name()),
                () -> assertEquals(1, named.members().size()),
                () -> assertTrue(named.members().getFirst().name().isBlank()),
                () -> assertTrue(namedDiagnostics.snapshot().asList().stream().anyMatch(
                        diagnostic -> diagnostic.message().contains("Missing enumerator"))),
                () -> assertTrue(namedDiagnostics.snapshot().asList().stream().allMatch(
                        diagnostic -> diagnostic.severity() == FrontendDiagnosticSeverity.ERROR))
        );

        var anonymousDiagnostics = new DiagnosticManager();
        var anonymous = assertInstanceOf(EnumDeclaration.class,
                parserService.parseUnit(
                        Path.of("tmp", "enum_empty_anonymous.gd"),
                        "enum {}\n",
                        anonymousDiagnostics
                ).ast().statements().getFirst());
        assertAll(
                () -> assertNull(anonymous.name()),
                () -> assertEquals(1, anonymous.members().size()),
                () -> assertTrue(anonymous.members().getFirst().name().isBlank()),
                () -> assertTrue(anonymousDiagnostics.snapshot().asList().stream().anyMatch(
                        diagnostic -> diagnostic.message().contains("Missing enumerator")))
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
