package gd.script.gdcc.frontend.sema.analyzer.support;

import dev.superice.gdparser.frontend.ast.EnumMember;
import dev.superice.gdparser.frontend.ast.Expression;
import gd.script.gdcc.frontend.constant.GdConstantValue;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.scope.GdScriptClassConstant;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/// Thin enum-pre-pass adapter over `FrontendConstantEvaluator`.
///
/// The general evaluator owns all arithmetic now — this class only freezes the legacy enum
/// surface through `EvaluationPolicy.enumPrePass()` (int literals plus the int operator set) and
/// the shared `FrontendClassConstPrepassLookup` in enum mode, then unwraps the result back into
/// the legacy `Resolved(long)`/`Rejected(reason)` shape the skeleton builder consumes.
/// Evaluation still reduces to a concrete int or rejects with a human-readable reason — never
/// throws for source input — and the legacy rejection wording is preserved by the policy's
/// `ENUM` domain.
///
/// Identifier lookup order mirrors lexical value resolution: same-enum earlier members first,
/// then same-class already-published constants (earlier class `const` int values included), then
/// the failed/declared/ancestor blockers, then globals. The blockers exist because classes are
/// filled in source order, not inheritance order: a name claimed by the class or an ancestor
/// must be diagnosed instead of falling through to a same-named global constant and producing a
/// silently wrong value.
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

    /// Evaluates one enum member value expression inside the joint const/enum pre-pass.
    ///
    /// - `earlierEnumMembers`: members of the same enum that were already evaluated (source order).
    /// - `classContext`: the shared per-class name views (usable constants, declared-member mask,
    ///   failed-const names so far, ancestor blocker).
    /// - `selfMember`: the enum member being evaluated, so a name resolving back to it reports a
    ///   self-reference rather than a generic blocker.
    /// - `classRegistry`: global constant/enum/language-constant lookup.
    public static @NotNull Evaluation evaluate(
            @NotNull Expression expression,
            @NotNull Map<String, Long> earlierEnumMembers,
            @NotNull FrontendClassConstPrepassContext classContext,
            @Nullable EnumMember selfMember,
            @NotNull ClassRegistry classRegistry
    ) {
        var result = FrontendConstantEvaluator.evaluate(
                Objects.requireNonNull(expression, "expression must not be null"),
                FrontendClassConstPrepassLookup.forEnumPrePass(
                        Objects.requireNonNull(classContext, "classContext must not be null"),
                        Objects.requireNonNull(earlierEnumMembers, "earlierEnumMembers must not be null"),
                        selfMember,
                        Objects.requireNonNull(classRegistry, "classRegistry must not be null")
                ),
                EvaluationPolicy.enumPrePass()
        );
        return switch (result) {
            case gd.script.gdcc.frontend.constant.Evaluation.Resolved resolved -> {
                // The enum policy only admits int shapes, so a non-int value here is a wiring
                // bug in this adapter rather than a source-facing outcome.
                if (!(resolved.value() instanceof GdConstantValue.IntValue intValue)) {
                    throw new IllegalStateException(
                            "enum constant evaluation must only resolve int values, got '"
                                    + resolved.value().type().getTypeName() + "'");
                }
                yield new Evaluation.Resolved(intValue.value());
            }
            case gd.script.gdcc.frontend.constant.Evaluation.Rejected rejected ->
                    new Evaluation.Rejected(rejected.reason());
        };
    }

    /// Convenience overload for callers that only carry the pre-joint-pre-pass facts: the
    /// ancestor enum names become the script-enum blocker set, the constant table becomes the
    /// usable view, and there is no declared-member or failed-const mask.
    public static @NotNull Evaluation evaluate(
            @NotNull Expression expression,
            @NotNull Map<String, Long> earlierEnumMembers,
            @NotNull List<? extends GdScriptClassConstant> classConstants,
            @NotNull Set<String> ancestorEnumNames,
            @NotNull ClassRegistry classRegistry
    ) {
        return evaluate(
                expression,
                earlierEnumMembers,
                new FrontendClassConstPrepassContext(
                        Objects.requireNonNull(classConstants, "classConstants must not be null"),
                        Map.of(),
                        Set.of(),
                        new FrontendAncestorMemberBlocker(
                                Objects.requireNonNull(ancestorEnumNames, "ancestorEnumNames must not be null"),
                                Set.of(),
                                null,
                                Objects.requireNonNull(classRegistry, "classRegistry must not be null")
                        )
                ),
                null,
                classRegistry
        );
    }
}
