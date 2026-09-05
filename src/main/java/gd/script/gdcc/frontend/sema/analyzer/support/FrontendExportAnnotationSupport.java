package gd.script.gdcc.frontend.sema.analyzer.support;

import dev.superice.gdparser.frontend.ast.Expression;
import dev.superice.gdparser.frontend.ast.LiteralExpression;
import dev.superice.gdparser.frontend.ast.UnaryExpression;
import gd.script.gdcc.frontend.sema.FrontendGdAnnotation;
import gd.script.gdcc.util.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Objects;

/// Single implementation of the export-annotation argument contract: name classification,
/// arity/literal/comma/empty structural validation, and Godot `hint_string` encoding. Both the
/// skeleton property mapping and the usage analyzer consume this helper so the export argument
/// rules live exactly once.
///
/// Only literal arguments are accepted: int/float/String literals and numeric unary `+`/`-`.
/// Constant references, enum members, or arbitrary expressions are malformed (a deliberate
/// narrowing of Godot's constant-expression acceptance). The hint_string encoding follows Godot:
/// explicitly written arguments joined with `,`, with defaults never materialized — e.g.
/// `@export_range(0, 100)` encodes as `"0,100"`, not `"0,100,1"`.
public final class FrontendExportAnnotationSupport {
    private FrontendExportAnnotationSupport() {
    }

    /// Three-state evaluation result for one annotation.
    public sealed interface Evaluation {
        /// The annotation does not belong to the currently supported export family.
        record NotExportFamily() implements Evaluation {
        }

        /// Well-formed export annotation: `hintKey` is the LIR annotation key (the annotation
        /// name) and `hintStringValue` is the Godot hint_string payload (possibly empty).
        record Supported(@NotNull String hintKey, @NotNull String hintStringValue) implements Evaluation {
        }

        /// Export-family annotation with malformed arguments; `reason` is the ready-made
        /// `sema.annotation_usage` diagnostic message.
        record Malformed(@NotNull String reason) implements Evaluation {
        }
    }

    /// Positional argument shapes derived from the Godot 4.5.1 registration signatures.
    private enum ArgShape {
        /// No arguments at all (`@export`, `@export_multiline`, layer flags, ...).
        NONE,
        /// Exactly one string argument; empty and comma-containing values stay legal because the
        /// placeholder hint_string is not a list.
        ONE_STRING,
        /// One or more string arguments joined into a list hint_string (`@export_enum`,
        /// `@export_flags`).
        STRING_VARARG_MIN1,
        /// Zero or more string arguments joined into a list hint_string (`@export_file`,
        /// `@export_node_path`, `@export_exp_easing`, ...).
        STRING_VARARG_MIN0,
        /// `@export_range`: min/max numeric, optional numeric step, then string extra hints.
        RANGE
    }

    private static final Map<String, ArgShape> ARG_SHAPES = Map.ofEntries(
            Map.entry("export", ArgShape.NONE),
            Map.entry("export_range", ArgShape.RANGE),
            Map.entry("export_enum", ArgShape.STRING_VARARG_MIN1),
            Map.entry("export_flags", ArgShape.STRING_VARARG_MIN1),
            Map.entry("export_flags_2d_render", ArgShape.NONE),
            Map.entry("export_flags_2d_physics", ArgShape.NONE),
            Map.entry("export_flags_2d_navigation", ArgShape.NONE),
            Map.entry("export_flags_3d_render", ArgShape.NONE),
            Map.entry("export_flags_3d_physics", ArgShape.NONE),
            Map.entry("export_flags_3d_navigation", ArgShape.NONE),
            Map.entry("export_flags_avoidance", ArgShape.NONE),
            Map.entry("export_file", ArgShape.STRING_VARARG_MIN0),
            Map.entry("export_file_path", ArgShape.STRING_VARARG_MIN0),
            Map.entry("export_dir", ArgShape.NONE),
            Map.entry("export_global_file", ArgShape.STRING_VARARG_MIN0),
            Map.entry("export_global_dir", ArgShape.NONE),
            Map.entry("export_multiline", ArgShape.NONE),
            Map.entry("export_placeholder", ArgShape.ONE_STRING),
            Map.entry("export_exp_easing", ArgShape.STRING_VARARG_MIN0),
            Map.entry("export_color_no_alpha", ArgShape.NONE),
            Map.entry("export_node_path", ArgShape.STRING_VARARG_MIN0)
    );

    public static boolean isExportFamilyAnnotation(@NotNull String name) {
        return ARG_SHAPES.containsKey(name);
    }

    /// Evaluates one annotation's arguments structurally. Type-compatibility checks against the
    /// annotated property are deliberately out of scope here — they belong to the usage analyzer,
    /// which owns the typed facts.
    public static @NotNull Evaluation evaluate(@NotNull FrontendGdAnnotation annotation) {
        Objects.requireNonNull(annotation, "annotation must not be null");
        var shape = ARG_SHAPES.get(annotation.name());
        if (shape == null) {
            return new Evaluation.NotExportFamily();
        }
        var name = annotation.name();
        var arguments = annotation.arguments();
        var hint = new StringBuilder();
        switch (shape) {
            case NONE -> {
                if (!arguments.isEmpty()) {
                    return new Evaluation.Malformed(arityMessage(name, "no arguments", arguments.size()));
                }
            }
            case ONE_STRING -> {
                if (arguments.size() != 1) {
                    return new Evaluation.Malformed(arityMessage(name, "exactly 1 argument", arguments.size()));
                }
                var error = appendStringArgument(hint, name, arguments.getFirst(), 1, false);
                if (error != null) {
                    return new Evaluation.Malformed(error);
                }
            }
            case STRING_VARARG_MIN0, STRING_VARARG_MIN1 -> {
                if (shape == ArgShape.STRING_VARARG_MIN1 && arguments.isEmpty()) {
                    return new Evaluation.Malformed(arityMessage(name, "at least 1 argument", 0));
                }
                for (var i = 0; i < arguments.size(); i++) {
                    if (i > 0) {
                        hint.append(',');
                    }
                    var error = appendStringArgument(hint, name, arguments.get(i), i + 1, true);
                    if (error != null) {
                        return new Evaluation.Malformed(error);
                    }
                }
            }
            case RANGE -> {
                if (arguments.size() < 2) {
                    return new Evaluation.Malformed(arityMessage(name, "at least 2 arguments", arguments.size()));
                }
                // Positional contract: min/max/step are numeric; extra hints are strings and may
                // only start at the fourth argument (`@export_range(0, 100, "or_greater")` is
                // malformed because the third formal parameter is numeric).
                for (var i = 0; i < Math.min(arguments.size(), 3); i++) {
                    if (i > 0) {
                        hint.append(',');
                    }
                    var error = appendNumberArgument(hint, name, arguments.get(i), i + 1);
                    if (error != null) {
                        return new Evaluation.Malformed(error);
                    }
                }
                for (var i = 3; i < arguments.size(); i++) {
                    hint.append(',');
                    var error = appendStringArgument(hint, name, arguments.get(i), i + 1, true);
                    if (error != null) {
                        return new Evaluation.Malformed(error);
                    }
                }
            }
        }
        return new Evaluation.Supported(name, hint.toString());
    }

    /// Decodes one string argument into the hint buffer. List-type parts reject empty strings
    /// (Godot parity for every non-placeholder form) and commas (the engine would mis-split the
    /// joined hint_string).
    private static @Nullable String appendStringArgument(
            @NotNull StringBuilder hint,
            @NotNull String name,
            @NotNull Expression argument,
            int index,
            boolean listPart
    ) {
        var decoded = decodeStringArgument(argument);
        if (decoded == null) {
            return "@" + name + " argument " + index + " must be a string literal";
        }
        if (listPart) {
            if (decoded.isEmpty()) {
                return "@" + name + " argument " + index + " must not be empty";
            }
            if (decoded.contains(",")) {
                return "@" + name + " argument " + index + " must not contain ','";
            }
        }
        hint.append(decoded);
        return null;
    }

    /// String literal decoding failures (malformed lexeme, invalid escape) deliberately map to
    /// the same diagnostic as a non-literal argument: from the user's perspective the argument
    /// is not a usable string literal.
    private static @Nullable String decodeStringArgument(@NotNull Expression argument) {
        if (!(argument instanceof LiteralExpression literal) || !literal.kind().equals("string")) {
            return null;
        }
        try {
            return StringUtil.decodeGdStringLexeme(literal.sourceText());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static @Nullable String appendNumberArgument(
            @NotNull StringBuilder hint,
            @NotNull String name,
            @NotNull Expression argument,
            int index
    ) {
        var value = evaluateNumberArgument(argument);
        if (value == null) {
            return "@" + name + " argument " + index + " must be a number literal";
        }
        hint.append(value);
        return null;
    }

    /// Evaluates an int/float literal optionally signed by unary `+`/`-`, rendered in the
    /// shortest lossless form (integral floats render without a fraction: `1.0` -> `1`).
    private static @Nullable String evaluateNumberArgument(@NotNull Expression argument) {
        var negative = false;
        var operand = argument;
        if (argument instanceof UnaryExpression unary
                && (unary.operator().equals("-") || unary.operator().equals("+"))) {
            negative = unary.operator().equals("-");
            operand = unary.operand();
        }
        if (!(operand instanceof LiteralExpression literal)) {
            return null;
        }
        try {
            return switch (literal.kind()) {
                case "integer" -> renderIntegerLiteral(literal.sourceText(), negative);
                case "float" -> renderFloatLiteral(literal.sourceText(), negative);
                default -> null;
            };
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    /// Parses gdparser integer lexemes including `0x`/`0b`/`0o` prefixes and `_` separators
    /// (same lexeme set as the container literal helper). A literal that overflows 64-bit long
    /// still maps to the generic number-literal diagnostic — the fixed diagnostic template set
    /// has no dedicated overflow row.
    private static @NotNull String renderIntegerLiteral(@NotNull String sourceText, boolean negative) {
        var clean = sourceText.replace("_", "");
        var radix = 10;
        if (clean.startsWith("0x") || clean.startsWith("0X")) {
            radix = 16;
            clean = clean.substring(2);
        } else if (clean.startsWith("0b") || clean.startsWith("0B")) {
            radix = 2;
            clean = clean.substring(2);
        } else if (clean.startsWith("0o") || clean.startsWith("0O")) {
            radix = 8;
            clean = clean.substring(2);
        }
        var value = Long.parseLong(clean, radix);
        return Long.toString(negative ? -value : value);
    }

    private static @NotNull String renderFloatLiteral(@NotNull String sourceText, boolean negative) {
        var value = Double.parseDouble(sourceText.replace("_", ""));
        if (negative) {
            value = -value;
        }
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            // Integral floats render without a fraction to match Godot's hint_string shape.
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }

    private static @NotNull String arityMessage(@NotNull String name, @NotNull String spec, int actual) {
        return "@" + name + " expects " + spec + ", but got " + actual + " argument(s)";
    }
}
