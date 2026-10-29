package gd.script.gdcc.frontend.parse;

import dev.superice.gdparser.frontend.ast.AnnotationStatement;
import dev.superice.gdparser.frontend.ast.ClassDeclaration;
import dev.superice.gdparser.frontend.ast.DeclarationKind;
import dev.superice.gdparser.frontend.ast.ErrorStatement;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Locks the parser-expressible `const` shapes the const pipeline depends on (const plan P0
/// fixtures). These probes anchor what gdparser 0.6.0 can express so later stages diagnose on
/// real AST shapes instead of assumptions:
///
/// - plain / typed / inferred (`:=`) const at class and local level all produce
///   `DeclarationKind.CONST` `VariableDeclaration` nodes;
/// - `@export` / `@onready` const stay expressible as annotation+declaration pairs — placement
///   rejection is a sema concern, not a parse error;
/// - `static const` is already rejected at CST level: the `static` keyword reports a structural
///   error and the surviving const recovers without the static flag, so no AST shape can carry
///   a static const;
/// - const without an initializer is a structural `ErrorStatement`, never a CONST declaration.
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
    void parseAnnotatedConstStaysExpressibleForSemaRejection() {
        // Placement rejection (@export/@onready on const) is owned by sema; the parser only
        // needs to surface the annotation+declaration pair.
        var unit = parse("const_annotated.gd", """
                @export const EXPORTED = 1
                @onready const READY = 2
                """);

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
    }

    @Test
    void parseStaticConstReportsStructuralErrorAndDropsStaticFlag() {
        // The grammar rejects `static const` at CST level: `static` becomes an error node and the
        // const itself recovers as a plain non-static declaration — no AST shape expresses a
        // static const for sema to diagnose.
        var diagnostics = new DiagnosticManager();
        var unit = parserService.parseUnit(
                Path.of("tmp", "const_static.gd"),
                "static const X = 1\n",
                diagnostics
        );

        var errorStatement = assertInstanceOf(ErrorStatement.class, unit.ast().statements().get(0));
        var recoveredConst = assertInstanceOf(VariableDeclaration.class, unit.ast().statements().get(1));
        assertAll(
                () -> assertEquals("static", errorStatement.sourceText()),
                () -> assertTrue(diagnostics.snapshot().asList().stream().anyMatch(
                        diagnostic -> diagnostic.category().equals("parse.lowering"))),
                () -> assertConstDecl(recoveredConst, "X", null)
        );
    }

    @Test
    void parseInitializerlessConstIsStructuralErrorWithoutDeclaration() {
        // `const` must carry an initializer: both the untyped and typed initializerless forms
        // surface as an ErrorStatement only — no CONST VariableDeclaration is produced at all.
        for (var source : new String[]{"const X\n", "const X: int\n"}) {
            var diagnostics = new DiagnosticManager();
            var unit = parserService.parseUnit(Path.of("tmp", "const_no_init.gd"), source, diagnostics);

            var errorStatement = assertInstanceOf(ErrorStatement.class, unit.ast().statements().getFirst());
            assertAll(
                    () -> assertEquals(source.trim(), errorStatement.sourceText()),
                    () -> assertTrue(diagnostics.snapshot().asList().stream().anyMatch(
                            diagnostic -> diagnostic.category().equals("parse.lowering")))
            );
        }
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
