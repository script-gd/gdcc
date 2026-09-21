package gd.script.gdcc.frontend.sema.analyzer.support;

import dev.superice.gdparser.frontend.ast.BinaryExpression;
import dev.superice.gdparser.frontend.ast.Expression;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.LiteralExpression;
import dev.superice.gdparser.frontend.ast.UnaryExpression;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.scope.GdScriptClassConstant;
import gd.script.gdcc.scope.GdScriptEnumConstant;
import gd.script.gdcc.util.StringUtil;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/// Evaluates the restricted constant-expression subset accepted for script enum member values.
///
/// The evaluator is intentionally fail-closed: only shapes that reduce to a 64-bit int without any
/// runtime semantics are accepted — integer literals, unary `-`/`+`/`~`, the int binary operators
/// `+ - * / % << >> & | ^`, and identifiers that resolve to an earlier member of the same enum, an
/// earlier same-class enum constant, or a global int constant/enum value. Everything else (calls,
/// attribute/subscript access, ternaries, floats, strings, power, comparisons, ...) is rejected so
/// the owning enum is skipped with a skeleton diagnostic instead of silently materializing a wrong
/// value.
///
/// Identifier lookup order mirrors lexical value resolution: same-enum earlier members first, then
/// same-class earlier enum constants, then the ancestor-enum-name blocker, then globals. The
/// ancestor blocker exists because classes are filled in source order, not inheritance order: an
/// inherited enum constant name must be diagnosed as unsupported instead of falling through to a
/// same-named global constant and producing a silently wrong value.
///
/// Arithmetic follows Java `long` semantics, which match Godot's int64 behavior for the supported
/// operators: `+`/`-`/`*` wrap on overflow, `/` truncates toward zero, and `%` keeps the
/// dividend's sign. Division and remainder by zero are rejected. Shifts are deliberately
/// fail-closed instead of adopting Java's distance-masking: a negative or `> 63` shift count is
/// rejected, aligning with the runtime shift guards instead of silently producing a masked value.
public final class FrontendEnumConstantEvaluator {
    private FrontendEnumConstantEvaluator() {
    }

    /// Outcome of one enum member value evaluation.
    public sealed interface Evaluation {
        /// The expression reduced to a concrete int value.
        record Resolved(long value) implements Evaluation {
        }

        /// The expression is outside the supported subset; `reason` is appended to the owning
        /// `sema.class_skeleton` diagnostic message by the caller.
        record Rejected(@NotNull String reason) implements Evaluation {
        }
    }

    /// Evaluates one enum member value expression.
    ///
    /// - `earlierEnumMembers`: members of the same enum that were already evaluated (source order).
    /// - `classConstants`: the script-source constant table of the owning class as collected so far.
    /// - `ancestorEnumNames`: enum group/member names declared by GDCC ancestors (blocker set).
    /// - `classRegistry`: global constant/enum/language-constant lookup.
    public static @NotNull Evaluation evaluate(
            @NotNull Expression expression,
            @NotNull Map<String, Long> earlierEnumMembers,
            @NotNull List<? extends GdScriptClassConstant> classConstants,
            @NotNull Set<String> ancestorEnumNames,
            @NotNull ClassRegistry classRegistry
    ) {
        return evaluateExpression(expression, new LookupContext(
                Objects.requireNonNull(earlierEnumMembers, "earlierEnumMembers must not be null"),
                Objects.requireNonNull(classConstants, "classConstants must not be null"),
                Objects.requireNonNull(ancestorEnumNames, "ancestorEnumNames must not be null"),
                Objects.requireNonNull(classRegistry, "classRegistry must not be null")
        ));
    }

    /// Read-only lookup views threaded through the recursive evaluation.
    private record LookupContext(
            @NotNull Map<String, Long> earlierEnumMembers,
            @NotNull List<? extends GdScriptClassConstant> classConstants,
            @NotNull Set<String> ancestorEnumNames,
            @NotNull ClassRegistry classRegistry
    ) {
    }

    private static @NotNull Evaluation evaluateExpression(
            @NotNull Expression expression,
            @NotNull LookupContext context
    ) {
        return switch (expression) {
            case LiteralExpression literal -> evaluateLiteral(literal);
            case UnaryExpression unary -> evaluateUnary(unary, context);
            case BinaryExpression binary -> evaluateBinary(binary, context);
            case IdentifierExpression identifier -> resolveIdentifier(identifier.name(), context);
            default -> new Evaluation.Rejected(
                    "expression of kind '" + expression.getClass().getSimpleName()
                            + "' is not a supported int constant expression"
            );
        };
    }

    private static @NotNull Evaluation evaluateLiteral(@NotNull LiteralExpression literal) {
        if (!literal.kind().equals("integer")) {
            return new Evaluation.Rejected(
                    "literal '" + literal.sourceText() + "' is not an int constant"
            );
        }
        var value = StringUtil.parseGdIntegerLexeme(literal.sourceText());
        if (value == null) {
            return new Evaluation.Rejected(
                    "integer literal '" + literal.sourceText() + "' is malformed or overflows 64-bit int"
            );
        }
        return new Evaluation.Resolved(value);
    }

    private static @NotNull Evaluation evaluateUnary(
            @NotNull UnaryExpression unary,
            @NotNull LookupContext context
    ) {
        var operandEvaluation = evaluateExpression(unary.operand(), context);
        if (operandEvaluation instanceof Evaluation.Rejected) {
            return operandEvaluation;
        }
        var operand = ((Evaluation.Resolved) operandEvaluation).value();
        return switch (unary.operator()) {
            case "-" -> new Evaluation.Resolved(-operand);
            case "+" -> operandEvaluation;
            case "~" -> new Evaluation.Resolved(~operand);
            default -> new Evaluation.Rejected(
                    "unary operator '" + unary.operator() + "' is not supported in enum constants"
            );
        };
    }

    private static @NotNull Evaluation evaluateBinary(
            @NotNull BinaryExpression binary,
            @NotNull LookupContext context
    ) {
        var leftEvaluation = evaluateExpression(binary.left(), context);
        if (leftEvaluation instanceof Evaluation.Rejected) {
            return leftEvaluation;
        }
        var rightEvaluation = evaluateExpression(binary.right(), context);
        if (rightEvaluation instanceof Evaluation.Rejected) {
            return rightEvaluation;
        }
        var left = ((Evaluation.Resolved) leftEvaluation).value();
        var right = ((Evaluation.Resolved) rightEvaluation).value();
        return switch (binary.operator()) {
            case "+" -> new Evaluation.Resolved(left + right);
            case "-" -> new Evaluation.Resolved(left - right);
            case "*" -> new Evaluation.Resolved(left * right);
            case "/" -> right == 0
                    ? new Evaluation.Rejected("division by zero in enum constant expression")
                    : new Evaluation.Resolved(left / right);
            case "%" -> right == 0
                    ? new Evaluation.Rejected("remainder by zero in enum constant expression")
                    : new Evaluation.Resolved(left % right);
            case "<<" -> evaluateShift(left, right, true);
            case ">>" -> evaluateShift(left, right, false);
            case "&" -> new Evaluation.Resolved(left & right);
            case "|" -> new Evaluation.Resolved(left | right);
            case "^" -> new Evaluation.Resolved(left ^ right);
            default -> new Evaluation.Rejected(
                    "binary operator '" + binary.operator() + "' is not supported in enum constants"
            );
        };
    }

    /// Shift counts stay fail-closed: Java would silently mask the distance to its low 6 bits,
    /// which would publish a constant the runtime shift guards reject. Only 0..63 is legal.
    private static @NotNull Evaluation evaluateShift(long left, long right, boolean leftShift) {
        if (right < 0 || right > 63) {
            return new Evaluation.Rejected(
                    "shift count " + right + " is out of the 0..63 range in enum constant expression"
            );
        }
        return new Evaluation.Resolved(leftShift ? left << right : left >> right);
    }

    private static @NotNull Evaluation resolveIdentifier(@NotNull String name, @NotNull LookupContext context) {
        var earlierMemberValue = context.earlierEnumMembers().get(name);
        if (earlierMemberValue != null) {
            return new Evaluation.Resolved(earlierMemberValue);
        }
        for (var classConstant : context.classConstants()) {
            if (!classConstant.name().equals(name)) {
                continue;
            }
            if (classConstant.declaration() instanceof GdScriptEnumConstant enumConstant) {
                return new Evaluation.Resolved(enumConstant.value());
            }
            // The only other constant shape on the table is a named enum group, which is a
            // Dictionary value and therefore never usable as an int constant.
            return new Evaluation.Rejected(
                    "'" + name + "' is a named enum group (Dictionary), not an int constant"
            );
        }
        // The blocker runs before the global fallback: ancestor enum constants share names with
        // globals by coincidence only, and silently picking the global value would be wrong.
        if (context.ancestorEnumNames().contains(name)) {
            return new Evaluation.Rejected(
                    "'" + name + "' is an inherited enum constant; referencing it requires "
                            + "inheritance-order evaluation, which is not supported"
            );
        }
        var globalEnumValue = context.classRegistry().findGlobalEnumValueByBareName(name);
        if (globalEnumValue != null) {
            return new Evaluation.Resolved(globalEnumValue.value());
        }
        var globalConstant = context.classRegistry().findGlobalConstant(name);
        if (globalConstant != null) {
            return new Evaluation.Resolved(globalConstant.value());
        }
        if (context.classRegistry().findGdScriptLanguageConstant(name) != null) {
            return new Evaluation.Rejected(
                    "'" + name + "' is a float language constant, not an int constant"
            );
        }
        return new Evaluation.Rejected("unknown identifier '" + name + "' in enum constant expression");
    }
}
