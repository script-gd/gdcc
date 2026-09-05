package gd.script.gdcc.frontend.sema.analyzer.support;

import dev.superice.gdparser.frontend.ast.Expression;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.LiteralExpression;
import dev.superice.gdparser.frontend.ast.UnaryExpression;
import dev.superice.gdparser.frontend.ast.Range;
import dev.superice.gdparser.frontend.ast.Point;
import gd.script.gdcc.frontend.sema.FrontendGdAnnotation;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/// Unit-level anchoring of the export argument contract: hint_string encoding precision
/// (numeric rendering, string forms, escapes) and every malformed shape. Pipeline-level
/// placement/type diagnostics live in `FrontendAnnotationUsageAnalyzerTest`.
class FrontendExportAnnotationSupportTest {
    private static final Range SYNTHETIC_RANGE = new Range(0, 1, new Point(0, 0), new Point(0, 1));

    @Test
    void evaluateEncodesRangeHintStringFromExplicitArgumentsOnly() {
        assertAll(
                // Defaults are never materialized: no step when only min/max are written.
                () -> assertSupported(annotate("export_range", intLit("0"), intLit("100")), "export_range", "0,100"),
                () -> assertSupported(
                        annotate("export_range", intLit("0"), intLit("20"), floatLit("0.5")),
                        "export_range",
                        "0,20,0.5"
                ),
                () -> assertSupported(
                        annotate("export_range", intLit("0"), intLit("100"), intLit("1"), stringLit("\"or_greater\"")),
                        "export_range",
                        "0,100,1,or_greater"
                ),
                // Signed numeric literals and integral floats render in shortest lossless form.
                () -> assertSupported(
                        annotate("export_range", unary("-", floatLit("1.5")), unary("+", intLit("2"))),
                        "export_range",
                        "-1.5,2"
                ),
                () -> assertSupported(
                        annotate("export_range", floatLit("0.0"), floatLit("1.0")),
                        "export_range",
                        "0,1"
                ),
                // Hex/binary/octal/underscored integer literal forms normalize to decimal.
                () -> assertSupported(
                        annotate("export_range", intLit("0x0"), intLit("0xFF")),
                        "export_range",
                        "0,255"
                ),
                () -> assertSupported(
                        annotate("export_range", intLit("0b0"), intLit("1_000")),
                        "export_range",
                        "0,1000"
                ),
                () -> assertSupported(
                        annotate("export_range", intLit("0o0"), intLit("0o17")),
                        "export_range",
                        "0,15"
                )
        );
    }

    @Test
    void evaluateDecodesStringArgumentFormsAndEscapes() {
        assertAll(
                () -> assertSupported(
                        annotate("export_enum", stringLit("\"Warrior\""), stringLit("\"Mage\"")),
                        "export_enum",
                        "Warrior,Mage"
                ),
                // Single-quoted and raw forms decode through the shared string lexeme decoder.
                () -> assertSupported(
                        annotate("export_enum", stringLit("'A'"), stringLit("'B'")),
                        "export_enum",
                        "A,B"
                ),
                () -> assertSupported(
                        annotate("export_file", stringLit("r\"res://a.txt\"")),
                        "export_file",
                        "res://a.txt"
                ),
                () -> assertSupported(
                        annotate("export_file", stringLit("\"\"\"res://a.txt\"\"\"")),
                        "export_file",
                        "res://a.txt"
                ),
                () -> assertSupported(
                        annotate("export_placeholder", stringLit("\"line\\t\\U01F600\"")),
                        "export_placeholder",
                        "line\t\uD83D\uDE00"
                ),
                // `Name:value` enum entries pass through untouched.
                () -> assertSupported(
                        annotate("export_enum", stringLit("\"Name:2\"")),
                        "export_enum",
                        "Name:2"
                )
        );
    }

    @Test
    void evaluateHandlesZeroAndVarargArityPerSignature() {
        assertAll(
                () -> assertSupported(annotate("export"), "export", ""),
                () -> assertSupported(annotate("export_multiline"), "export_multiline", ""),
                () -> assertSupported(annotate("export_dir"), "export_dir", ""),
                () -> assertSupported(annotate("export_global_dir"), "export_global_dir", ""),
                () -> assertSupported(annotate("export_color_no_alpha"), "export_color_no_alpha", ""),
                () -> assertSupported(annotate("export_flags_2d_render"), "export_flags_2d_render", ""),
                () -> assertSupported(annotate("export_flags_avoidance"), "export_flags_avoidance", ""),
                () -> assertSupported(annotate("export_file"), "export_file", ""),
                () -> assertSupported(
                        annotate("export_file", stringLit("\"*.png\""), stringLit("\"*.jpg\"")),
                        "export_file",
                        "*.png,*.jpg"
                ),
                () -> assertSupported(
                        annotate("export_node_path", stringLit("\"Node2D\""), stringLit("\"Sprite2D\"")),
                        "export_node_path",
                        "Node2D,Sprite2D"
                ),
                () -> assertSupported(
                        annotate("export_exp_easing", stringLit("\"attenuation\""), stringLit("\"positive_only\"")),
                        "export_exp_easing",
                        "attenuation,positive_only"
                ),
                // The placeholder hint_string is not a list: empty and comma text stay legal.
                () -> assertSupported(annotate("export_placeholder", stringLit("\"\"")), "export_placeholder", ""),
                () -> assertSupported(annotate("export_placeholder", stringLit("\"a,b\"")), "export_placeholder", "a,b")
        );
    }

    @Test
    void evaluateRejectsArityMismatches() {
        assertAll(
                () -> assertMalformed(annotate("export_range", intLit("0")), "@export_range expects at least 2 arguments, but got 1 argument(s)"),
                () -> assertMalformed(annotate("export_enum"), "@export_enum expects at least 1 argument, but got 0 argument(s)"),
                () -> assertMalformed(annotate("export_flags"), "@export_flags expects at least 1 argument, but got 0 argument(s)"),
                () -> assertMalformed(annotate("export_multiline", stringLit("\"x\"")), "@export_multiline expects no arguments, but got 1 argument(s)"),
                () -> assertMalformed(annotate("export", intLit("1")), "@export expects no arguments, but got 1 argument(s)"),
                () -> assertMalformed(
                        annotate("export_placeholder", stringLit("\"a\""), stringLit("\"b\"")),
                        "@export_placeholder expects exactly 1 argument, but got 2 argument(s)"
                ),
                () -> assertMalformed(annotate("export_dir", stringLit("\"res://\"")), "@export_dir expects no arguments, but got 1 argument(s)")
        );
    }

    @Test
    void evaluateRejectsNonLiteralAndMistypedArguments() {
        assertAll(
                // Constant references are not literal arguments (deliberate Godot narrowing).
                () -> assertMalformed(
                        annotate("export_range", new IdentifierExpression("MIN", SYNTHETIC_RANGE), intLit("1")),
                        "@export_range argument 1 must be a number literal"
                ),
                () -> assertMalformed(
                        annotate("export_enum", new IdentifierExpression("NAMED", SYNTHETIC_RANGE)),
                        "@export_enum argument 1 must be a string literal"
                ),
                // A string literal is not a number argument, and unary minus does not fix that.
                () -> assertMalformed(
                        annotate("export_range", stringLit("\"0\""), intLit("1")),
                        "@export_range argument 1 must be a number literal"
                ),
                () -> assertMalformed(
                        annotate("export_range", unary("-", stringLit("\"a\"")), intLit("1")),
                        "@export_range argument 1 must be a number literal"
                )
        );
    }

    @Test
    void evaluateRejectsStringArgumentContentRules() {
        assertAll(
                () -> assertMalformed(annotate("export_enum", stringLit("\"A,B\"")), "@export_enum argument 1 must not contain ','"),
                () -> assertMalformed(annotate("export_file", stringLit("\"\"")), "@export_file argument 1 must not be empty"),
                () -> assertMalformed(annotate("export_node_path", stringLit("\"\"")), "@export_node_path argument 1 must not be empty"),
                // The third formal parameter of export_range is numeric, so a string there is
                // malformed instead of becoming an extra hint.
                () -> assertMalformed(
                        annotate("export_range", intLit("0"), intLit("100"), stringLit("\"or_greater\"")),
                        "@export_range argument 3 must be a number literal"
                ),
                // Invalid escapes inside a string literal surface as "not a usable string literal".
                () -> assertMalformed(annotate("export_file", stringLit("\"bad\\qescape\"")), "@export_file argument 1 must be a string literal")
        );
    }

    @Test
    void evaluateClassifiesNonExportAnnotations() {
        assertAll(
                () -> assertInstanceOf(
                        FrontendExportAnnotationSupport.Evaluation.NotExportFamily.class,
                        FrontendExportAnnotationSupport.evaluate(annotate("onready"))
                ),
                () -> assertInstanceOf(
                        FrontendExportAnnotationSupport.Evaluation.NotExportFamily.class,
                        FrontendExportAnnotationSupport.evaluate(annotate("export_group", stringLit("\"g\"")))
                ),
                () -> assertInstanceOf(
                        FrontendExportAnnotationSupport.Evaluation.NotExportFamily.class,
                        FrontendExportAnnotationSupport.evaluate(annotate("tool"))
                )
        );
    }

    private static @NotNull FrontendGdAnnotation annotate(@NotNull String name, @NotNull Expression... arguments) {
        return new FrontendGdAnnotation(name, List.of(arguments), null);
    }

    private static @NotNull LiteralExpression stringLit(@NotNull String lexeme) {
        return new LiteralExpression("string", lexeme, SYNTHETIC_RANGE);
    }

    private static @NotNull LiteralExpression intLit(@NotNull String sourceText) {
        return new LiteralExpression("integer", sourceText, SYNTHETIC_RANGE);
    }

    private static @NotNull LiteralExpression floatLit(@NotNull String sourceText) {
        return new LiteralExpression("float", sourceText, SYNTHETIC_RANGE);
    }

    private static @NotNull UnaryExpression unary(@NotNull String operator, @NotNull Expression operand) {
        return new UnaryExpression(operator, operand, SYNTHETIC_RANGE);
    }

    private static void assertSupported(@NotNull FrontendGdAnnotation annotation, @NotNull String hintKey, @NotNull String hintString) {
        var evaluation = FrontendExportAnnotationSupport.evaluate(annotation);
        var supported = assertInstanceOf(FrontendExportAnnotationSupport.Evaluation.Supported.class, evaluation);
        assertEquals(hintKey, supported.hintKey());
        assertEquals(hintString, supported.hintStringValue());
    }

    private static void assertMalformed(@NotNull FrontendGdAnnotation annotation, @NotNull String expectedReason) {
        var evaluation = FrontendExportAnnotationSupport.evaluate(annotation);
        var malformed = assertInstanceOf(FrontendExportAnnotationSupport.Evaluation.Malformed.class, evaluation);
        assertEquals(expectedReason, malformed.reason());
    }
}
