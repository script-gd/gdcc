package gd.script.gdcc.frontend.sema.analyzer.support;

import dev.superice.gdparser.frontend.ast.Expression;
import gd.script.gdcc.frontend.constant.GdConstantValue;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.scope.GdScriptClassConstant;
import gd.script.gdcc.scope.GdScriptEnumConstant;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/// Thin enum-pre-pass adapter over `FrontendConstantEvaluator`.
///
/// The general evaluator owns all arithmetic now — this class only freezes the legacy enum
/// surface through `EvaluationPolicy.enumPrePass()` (int literals plus the int operator set) and
/// the enum-specific `LookupContext` below, then unwraps the result back into the legacy
/// `Resolved(long)`/`Rejected(reason)` shape the skeleton builder consumes. Evaluation still
/// reduces to a concrete int or rejects with a human-readable reason — never throws for source
/// input — and the legacy rejection wording is preserved by the policy's `ENUM` domain.
///
/// Identifier lookup order mirrors lexical value resolution: same-enum earlier members first,
/// then same-class earlier enum constants, then the ancestor-enum-name blocker, then globals.
/// The ancestor blocker exists because classes are filled in source order, not inheritance
/// order: an inherited enum constant name must be diagnosed as unsupported instead of falling
/// through to a same-named global constant and producing a silently wrong value.
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
        var result = FrontendConstantEvaluator.evaluate(
                Objects.requireNonNull(expression, "expression must not be null"),
                new EnumConstantLookup(
                        Objects.requireNonNull(earlierEnumMembers, "earlierEnumMembers must not be null"),
                        Objects.requireNonNull(classConstants, "classConstants must not be null"),
                        Objects.requireNonNull(ancestorEnumNames, "ancestorEnumNames must not be null"),
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

    /// Enum-facing `LookupContext`: reproduces the legacy lookup order — earlier same-enum
    /// members, then earlier same-class enum constants, then the ancestor blocker, then global
    /// enum values/constants — and marks everything the enum surface cannot consume as
    /// `NonConstant`/`Blocked` instead of falling through to globals.
    ///
    /// `resolveMember`/`callableRegistry`/`selectCall` are unreachable here: the enum policy
    /// denies attribute access and registered member calls before the evaluator consults them.
    private record EnumConstantLookup(
            @NotNull Map<String, Long> earlierEnumMembers,
            @NotNull List<? extends GdScriptClassConstant> classConstants,
            @NotNull Set<String> ancestorEnumNames,
            @NotNull ClassRegistry classRegistry
    ) implements LookupContext {

        @Override
        public @NotNull LookupResult resolve(@NotNull String name) {
            var earlierMemberValue = earlierEnumMembers.get(name);
            if (earlierMemberValue != null) {
                return new LookupResult.ResolvedConstant(
                        new GdConstantValue.IntValue(earlierMemberValue));
            }
            for (var classConstant : classConstants) {
                if (!classConstant.name().equals(name)) {
                    continue;
                }
                if (classConstant.declaration() instanceof GdScriptEnumConstant enumConstant) {
                    return new LookupResult.ResolvedConstant(
                            new GdConstantValue.IntValue(enumConstant.value()));
                }
                // The only other constant shape on the table is a named enum group, which is a
                // Dictionary value and therefore never usable as an int constant.
                return new LookupResult.NonConstant(
                        "'" + name + "' is a named enum group (Dictionary), not an int constant"
                );
            }
            // The blocker runs before the global fallback: ancestor enum constants share names
            // with globals by coincidence only, and silently picking the global value would be
            // wrong.
            if (ancestorEnumNames.contains(name)) {
                return new LookupResult.Blocked(
                        "'" + name + "' is an inherited enum constant; referencing it requires "
                                + "inheritance-order evaluation, which is not supported"
                );
            }
            var globalEnumValue = classRegistry.findGlobalEnumValueByBareName(name);
            if (globalEnumValue != null) {
                return new LookupResult.ResolvedConstant(
                        new GdConstantValue.IntValue(globalEnumValue.value()));
            }
            var globalConstant = classRegistry.findGlobalConstant(name);
            if (globalConstant != null) {
                return new LookupResult.ResolvedConstant(
                        new GdConstantValue.IntValue(globalConstant.value()));
            }
            if (classRegistry.findGdScriptLanguageConstant(name) != null) {
                return new LookupResult.NonConstant(
                        "'" + name + "' is a float language constant, not an int constant"
                );
            }
            return new LookupResult.Unknown();
        }

        @Override
        public @NotNull LookupResult resolveMember(
                @NotNull Object namespaceIdentity,
                @NotNull String memberName
        ) {
            return new LookupResult.Unknown();
        }

        @Override
        public @NotNull FrontendConstCallableRegistry callableRegistry() {
            return FrontendConstCallableRegistry.empty();
        }
    }
}
