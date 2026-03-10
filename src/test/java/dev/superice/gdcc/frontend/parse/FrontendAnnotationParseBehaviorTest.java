package dev.superice.gdcc.frontend.parse;

import dev.superice.gdparser.frontend.ast.AnnotationStatement;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.LiteralExpression;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import dev.superice.gdcc.frontend.diagnostic.DiagnosticManager;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontendAnnotationParseBehaviorTest {
    private final GdScriptParserService parserService = new GdScriptParserService();

    @Test
    void parseExportVariableProducesLeadingAnnotationAndGenericVariableNodeType() {
        var unit = parse("export_var.gd", """
                @export var hp: int = 1
                """);

        assertTrue(unit.parseDiagnostics().isEmpty());
        assertEquals(2, unit.ast().statements().size());

        var exportAnnotation = assertInstanceOf(AnnotationStatement.class, unit.ast().statements().getFirst());
        assertEquals("export", exportAnnotation.name());
        assertTrue(exportAnnotation.arguments().isEmpty());

        var declaration = assertInstanceOf(VariableDeclaration.class, unit.ast().statements().getLast());
        assertEquals("hp", declaration.name());
        assertEquals("variable_statement", declaration.sourceNodeType());
    }

    @Test
    void parseOnreadyVariableProducesLeadingAnnotationAndGenericVariableNodeType() {
        var unit = parse("onready_var.gd", """
                @onready var target = $Node
                """);

        assertTrue(unit.parseDiagnostics().isEmpty());
        assertEquals(2, unit.ast().statements().size());

        var onreadyAnnotation = assertInstanceOf(AnnotationStatement.class, unit.ast().statements().getFirst());
        assertEquals("onready", onreadyAnnotation.name());
        assertTrue(onreadyAnnotation.arguments().isEmpty());

        var declaration = assertInstanceOf(VariableDeclaration.class, unit.ast().statements().getLast());
        assertEquals("target", declaration.name());
        assertEquals("variable_statement", declaration.sourceNodeType());
    }

    @Test
    void parseParameterizedAnnotationKeepsArgumentsAndLeavesGenericVariableNodeType() {
        var unit = parse("export_range_var.gd", """
                @export_range(0, 20, 0.5) var speed: float = 1.0
                """);

        assertTrue(unit.parseDiagnostics().isEmpty());
        assertEquals(2, unit.ast().statements().size());

        var exportRange = assertInstanceOf(AnnotationStatement.class, unit.ast().statements().getFirst());
        assertEquals("export_range", exportRange.name());
        assertEquals(3, exportRange.arguments().size());
        assertEquals("0", assertInstanceOf(LiteralExpression.class, exportRange.arguments().get(0)).sourceText());
        assertEquals("20", assertInstanceOf(LiteralExpression.class, exportRange.arguments().get(1)).sourceText());
        assertEquals("0.5", assertInstanceOf(LiteralExpression.class, exportRange.arguments().get(2)).sourceText());

        var declaration = assertInstanceOf(VariableDeclaration.class, unit.ast().statements().getLast());
        assertEquals("speed", declaration.name());
        assertEquals("variable_statement", declaration.sourceNodeType());
    }

    @Test
    void parseMultipleLeadingAnnotationsPreservesOrderBeforeFunction() {
        var unit = parse("annotated_function.gd", """
                @rpc("authority")
                @warning_ignore("unused_parameter")
                func ping(value):
                    pass
                """);

        assertTrue(unit.parseDiagnostics().isEmpty());
        assertEquals(3, unit.ast().statements().size());

        var rpcAnnotation = assertInstanceOf(AnnotationStatement.class, unit.ast().statements().getFirst());
        assertEquals("rpc", rpcAnnotation.name());
        assertEquals("\"authority\"", assertInstanceOf(LiteralExpression.class, rpcAnnotation.arguments().getFirst()).sourceText());

        var ignoreAnnotation = assertInstanceOf(AnnotationStatement.class, unit.ast().statements().get(1));
        assertEquals("warning_ignore", ignoreAnnotation.name());
        assertEquals("\"unused_parameter\"", assertInstanceOf(LiteralExpression.class, ignoreAnnotation.arguments().getFirst()).sourceText());

        var functionDeclaration = assertInstanceOf(FunctionDeclaration.class, unit.ast().statements().getLast());
        assertEquals("ping", functionDeclaration.name());
    }

    @Test
    void parseRegionWarningAnnotationsAsStandaloneAnnotationStatements() {
        var unit = parse("region_warning_annotations.gd", """
                @warning_ignore_start("unused_variable")
                var tmp := 1
                @warning_ignore_restore("unused_variable")
                var keep := 2
                """);

        assertTrue(unit.parseDiagnostics().isEmpty());
        assertEquals(4, unit.ast().statements().size());
        assertEquals(
                "warning_ignore_start",
                assertInstanceOf(AnnotationStatement.class, unit.ast().statements().getFirst()).name()
        );
        assertEquals(
                "warning_ignore_restore",
                assertInstanceOf(AnnotationStatement.class, unit.ast().statements().get(2)).name()
        );
        assertEquals("tmp", assertInstanceOf(VariableDeclaration.class, unit.ast().statements().get(1)).name());
        assertEquals("keep", assertInstanceOf(VariableDeclaration.class, unit.ast().statements().getLast()).name());
    }

    private FrontendSourceUnit parse(String fileName, String source) {
        var diagnostics = new DiagnosticManager();
        var unit = parserService.parseUnit(Path.of("tmp", fileName), source, diagnostics);
        assertEquals(unit.parseDiagnostics(), diagnostics.snapshot().asList());
        return unit;
    }
}
