package gd.script.gdcc.frontend.sema.analyzer.support;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.function.BiPredicate;

/// Shared dominance-selection loop for frontend callable overload ranking.
///
/// Callers still own their argument-specific comparison rules. This helper only implements the
/// common "single non-dominated candidate wins, multiple non-dominated candidates stay ambiguous"
/// shape used by bare calls and constructor resolution.
///
/// Current users:
///
/// - {@link FrontendExpressionSemanticSupport#selectCallableOverload(List, List)}
/// - {@link FrontendConstructorResolutionSupport}
///
/// New frontend callable overload paths that need the same dominance-selection shape should reuse
/// this helper instead of copying another select-most-specific loop.
final class FrontendCallableOverloadRankingSupport {
    private FrontendCallableOverloadRankingSupport() {
    }

    static <T> @Nullable T selectMostSpecificApplicable(
            @NotNull List<? extends T> applicable,
            @NotNull BiPredicate<T, T> isStrictlyMoreSpecific
    ) {
        Objects.requireNonNull(applicable, "applicable must not be null");
        Objects.requireNonNull(isStrictlyMoreSpecific, "isStrictlyMoreSpecific must not be null");

        T selected = null;
        for (var candidate : applicable) {
            var dominated = false;
            for (var other : applicable) {
                if (candidate == other) {
                    continue;
                }
                if (isStrictlyMoreSpecific.test(other, candidate)) {
                    dominated = true;
                    break;
                }
            }
            if (dominated) {
                continue;
            }
            if (selected != null) {
                return null;
            }
            selected = candidate;
        }
        return selected;
    }
}
