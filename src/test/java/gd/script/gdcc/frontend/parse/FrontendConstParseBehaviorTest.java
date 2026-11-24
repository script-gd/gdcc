package gd.script.gdcc.frontend.parse;

import dev.superice.gdparser.frontend.ast.AnnotationStatement;
import dev.superice.gdparser.frontend.ast.ClassDeclaration;
import dev.superice.gdparser.frontend.ast.DeclarationKind;
import dev.superice.gdparser.frontend.ast.ErrorExpression;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.LiteralExpression;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import dev.superice.gdparser.frontend.gdscript.DiagnosticCodes;
import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.diagnostic.FrontendDiagnosticSeverity;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Locks the parser-expressible `const` shapes the const pipeline depends on. Invalid modifier
/// and annotation placement still leave the declaration available. A missing initializer
/// leaves an explicitly damaged value slot instead of dropping the declaration.
class FrontendConstParseBehaviorTest {
    private final GdScriptParserService parserService = new GdScriptParserService();

    @Test
    void parseTopLevelConstDeclarationsProduceConstKind() {
        var unit = parse("const_class_shapes.gd", """
                const PLAIN = 1
                const TYPED: int = 2
                const INFERRED := 3
                """);

        var plain = assertInstanceOf(VariableDeclaration.class, unit.ast().statements().get(0));
        var typed = assertInstanceOf(VariableDeclaration.class, unit.ast().statements().get(1));
        var inferred = assertInstanceOf(VariableDeclaration.class, unit.ast().statements().get(2));
        assertAll(
                () -> assertConstDecl(plain, "PLAIN", null),
                () -> assertConstDecl(typed, "TYPED", "int"),
                // `:=` surfaces as a TypeRef marker, mirroring the var inferred form.
                () -> assertConstDecl(inferred, "INFERRED", ":=")
        );
    }

    @Test
    void parseInnerClassConstDeclarationsProduceConstKind() {
        var unit = parse("const_inner_class.gd", """
                class Inner:
                    const VALUE = 4
                """);

        var inner = assertInstanceOf(ClassDeclaration.class, unit.ast().statements().getFirst());
        var decl = assertInstanceOf(VariableDeclaration.class, inner.body().statements().getFirst());
        assertConstDecl(decl, "VALUE", null);
    }

    @Test
    void parseLocalConstDeclarationsProduceConstKind() {
        var unit = parse("const_local_shapes.gd", """
                func f():
                    const PLAIN = 5
                    const TYPED: float = 6.0
                """);

        var function = assertInstanceOf(FunctionDeclaration.class, unit.ast().statements().getFirst());
        var plain = assertInstanceOf(VariableDeclaration.class, function.body().statements().get(0));
        var typed = assertInstanceOf(VariableDeclaration.class, function.body().statements().get(1));
        assertAll(
                () -> assertConstDecl(plain, "PLAIN", null),
                () -> assertConstDecl(typed, "TYPED", "float")
        );
    }

    @Test
    void parseAnnotatedConstRetainsDeclarationsAndReportsInvalidTargets() {
        var source = """
                @export const EXPORTED = 1
                @onready const READY = 2
                """;
        var diagnostics = new DiagnosticManager();
        var unit = parserService.parseUnit(Path.of("tmp", "const_annotated.gd"), source, diagnostics);

        assertEquals(4, unit.ast().statements().size());
        var exportAnnotation = assertInstanceOf(AnnotationStatement.class, unit.ast().statements().get(0));
        var exported = assertInstanceOf(VariableDeclaration.class, unit.ast().statements().get(1));
        var onreadyAnnotation = assertInstanceOf(AnnotationStatement.class, unit.ast().statements().get(2));
        var ready = assertInstanceOf(VariableDeclaration.class, unit.ast().statements().get(3));
        assertAll(
                () -> assertEquals("export", exportAnnotation.name()),
                () -> assertConstDecl(exported, "EXPORTED", null),
                () -> assertEquals("onready", onreadyAnnotation.name()),
                () -> assertConstDecl(ready, "READY", null)
        );
        assertEquals(2, unit.parseRecoveries().size());
        assertEquals(2, diagnostics.snapshot().size());
        var annotations = List.of(exportAnnotation, onreadyAnnotation);
        var errors = diagnostics.snapshot().asList();
        for (var index = 0; index < annotations.size(); index++) {
            var annotation = annotations.get(index);
            var recovery = unit.parseRecoveries().get(index);
            var diagnostic = errors.get(index);
            assertEquals(DiagnosticCodes.ANNOTATION_INVALID_TARGET, diagnostic.category());
            assertEquals(FrontendDiagnosticSeverity.ERROR, diagnostic.severity());
            assertEquals("@" + annotation.name(), source.substring(diagnostic.range().startByte(), diagnostic.range().endByte()));
            assertEquals(diagnostic.category(), recovery.code());
            assertEquals(diagnostic.range(), recovery.range());
            assertSame(annotation, recovery.owner());
            assertEquals("annotation.target", recovery.facet());
        }
    }

    @Test
    void parseStaticConstReportsStructuralErrorAndDropsStaticFlag() {
        var source = "static const X = 1\n";
        var diagnostics = new DiagnosticManager();
        var unit = parserService.parseUnit(
                Path.of("tmp", "const_static.gd"),
                source,
                diagnostics
        );

        assertEquals(1, unit.ast().statements().size());
        var recoveredConst = assertInstanceOf(VariableDeclaration.class, unit.ast().statements().getFirst());
        var recovery = assertSingleRecovery(unit, diagnostics, DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN, source, "static");
        assertAll(
                () -> assertEquals("Expected \"func\" or \"var\" after \"static\".", recovery.message()),
                () -> assertNull(recovery.owner()),
                () -> assertNull(recovery.facet()),
                () -> assertConstDecl(recoveredConst, "X", null)
        );
    }

    @Test
    void parseInitializerlessConstRetainsDeclarationWithDamagedValueSlot() {
        for (var suffix : List.of("", ": int")) {
            var source = "const X" + suffix + "\n";
            var diagnostics = new DiagnosticManager();
            var unit = parserService.parseUnit(Path.of("tmp", "const_no_init.gd"), source, diagnostics);
            assertEquals(1, unit.ast().statements().size());
            var declaration = assertInstanceOf(VariableDeclaration.class, unit.ast().statements().getFirst());
            var recovery = assertSingleRecovery(
                    unit, diagnostics, DiagnosticCodes.SLOT_CONSTANT_VALUE, source, suffix.isEmpty() ? "X" : "int");
            assertAll(
                    () -> assertConstDecl(declaration, "X", suffix.isEmpty() ? null : "int"),
                    () -> assertInstanceOf(ErrorExpression.class, declaration.value()),
                    () -> assertSame(declaration, recovery.owner()),
                    () -> assertEquals("constant.value", recovery.facet())
            );
        }
    }

    @Test
    void parseInferredConstAcrossExplicitLineContinuation() {
        var unit = parse("const_continued_inference.gd", "const C : \\\n = 1\n");
        assertEquals(1, unit.ast().statements().size());
        var declaration = assertInstanceOf(VariableDeclaration.class, unit.ast().statements().getFirst());
        assertConstDecl(declaration, "C", ": \\\n =");
        assertEquals("1", assertInstanceOf(LiteralExpression.class, declaration.value()).sourceText());
    }

    private static FrontendParseRecovery assertSingleRecovery(
            FrontendSourceUnit unit, DiagnosticManager diagnostics, String code, String source, String anchor
    ) {
        var errors = diagnostics.snapshot().asList();
        assertEquals(1, errors.size());
        assertEquals(1, unit.parseRecoveries().size());
        var diagnostic = errors.getFirst();
        var recovery = unit.parseRecoveries().getFirst();
        assertEquals(code, diagnostic.category());
        assertEquals(FrontendDiagnosticSeverity.ERROR, diagnostic.severity());
        assertEquals(anchor, source.substring(diagnostic.range().startByte(), diagnostic.range().endByte()));
        assertEquals(code, recovery.code());
        assertEquals(diagnostic.range(), recovery.range());
        return recovery;
    }

    private static void assertConstDecl(
            VariableDeclaration declaration,
            String expectedName,
            String expectedTypeText
    ) {
        assertAll(
                () -> assertEquals(DeclarationKind.CONST, declaration.kind()),
                () -> assertEquals(expectedName, declaration.name()),
                () -> assertFalse(declaration.isStatic()),
                () -> assertEquals("const_statement", declaration.sourceNodeType()),
                () -> assertNotNull(declaration.value()),
                () -> {
                    if (expectedTypeText == null) {
                        assertNull(declaration.type());
                    } else {
                        assertEquals(expectedTypeText, declaration.type().sourceText());
                    }
                }
        );
    }

    private FrontendSourceUnit parse(String fileName, String source) {
        var diagnostics = new DiagnosticManager();
        var unit = parserService.parseUnit(Path.of("tmp", fileName), source, diagnostics);
        assertTrue(diagnostics.snapshot().isEmpty());
        return unit;
    }
}
