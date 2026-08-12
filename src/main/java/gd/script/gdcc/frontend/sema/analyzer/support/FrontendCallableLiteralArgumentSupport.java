package gd.script.gdcc.frontend.sema.analyzer.support;

import dev.superice.gdparser.frontend.ast.ArrayExpression;
import dev.superice.gdparser.frontend.ast.DictionaryExpression;
import dev.superice.gdparser.frontend.ast.Expression;
import gd.script.gdcc.frontend.sema.FrontendExpressionType;
import gd.script.gdcc.frontend.sema.FrontendExpressionTypeStatus;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.scope.FunctionDef;
import gd.script.gdcc.scope.ParameterDef;
import gd.script.gdcc.type.GdArrayType;
import gd.script.gdcc.type.GdDictionaryType;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.type.GdVariantType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;

/// Shared container-literal argument preview / rank / rewrite helpers for bare, chain, and
/// constructor call selection.
///
/// Contract (see `doc/module_impl/frontend/frontend_container_literal_implementation.md` §4):
/// - preview never finalizes literals and never writes `expressionTypes` / `containerLiteralPlans`
/// - ranking reuses `FrontendContainerLiteralSemanticSupport.rankLiteralAgainstParameter`
/// - **specificity** for bare / constructor / chain instance / chain static all share
///   `literalAggregateRank` + `compareCandidateRanks` (via `isStrictlyMoreSpecificByLiteralAggregate`)
/// - chain applicability still uses index-aware `parameterCompatibilityRank` (encoded ranks /
///   reject-as-0) injected into `ScopeMethodResolver`; candidate-level specificity is injected
///   separately as `ScopeMethodResolver.CandidateSpecificity`
/// - after a callable is selected, `argumentTypes()` must record the contextual construction type
///   (for example `Array[int]`), not the preliminary generic `Array` / `Dictionary` snapshot
public final class FrontendCallableLiteralArgumentSupport {
    /// Encodes multi-element `worstRank` + `totalRank` into one int for **applicability** ranks
    /// only (`parameterCompatibilityRank` → `matchesArguments`). Candidate-level specificity no
    /// longer aggregates these packed ints; it uses `literalAggregateRank` instead.
    private static final int LITERAL_RANK_SCALE = 10_000;

    private FrontendCallableLiteralArgumentSupport() {
    }

    /// Child-source extractor for overload preview. Implementations must resolve children with
    /// `finalizeWindow=false` and `expected=null` so speculative preview never freezes facts.
    @FunctionalInterface
    public interface ChildSourceResolver {
        @Nullable FrontendExpressionType resolveChild(@NotNull Expression expression);
    }

    /// Collects preliminary child source types for one array/dictionary literal.
    ///
    /// Returns `null` when any child is not `RESOLVED`/`DYNAMIC` (candidate is treated as
    /// inapplicable by the caller).
    public static @Nullable List<GdType> preliminaryChildSourceTypesOrNull(
            @NotNull Expression literal,
            @NotNull ChildSourceResolver childSourceResolver
    ) {
        Objects.requireNonNull(literal, "literal must not be null");
        Objects.requireNonNull(childSourceResolver, "childSourceResolver must not be null");
        if (literal instanceof ArrayExpression arrayExpression) {
            var sources = new ArrayList<GdType>(arrayExpression.elements().size());
            for (var element : arrayExpression.elements()) {
                var childType = childSourceResolver.resolveChild(element);
                if (childType == null
                        || (childType.status() != FrontendExpressionTypeStatus.RESOLVED
                        && childType.status() != FrontendExpressionTypeStatus.DYNAMIC)) {
                    return null;
                }
                sources.add(Objects.requireNonNull(childType.publishedType(), "publishedType must not be null"));
            }
            return List.copyOf(sources);
        }
        if (literal instanceof DictionaryExpression dictionaryExpression) {
            var sources = new ArrayList<GdType>(dictionaryExpression.entries().size() * 2);
            for (var entry : dictionaryExpression.entries()) {
                var keyType = childSourceResolver.resolveChild(entry.key());
                if (keyType == null
                        || (keyType.status() != FrontendExpressionTypeStatus.RESOLVED
                        && keyType.status() != FrontendExpressionTypeStatus.DYNAMIC)) {
                    return null;
                }
                var valueType = childSourceResolver.resolveChild(entry.value());
                if (valueType == null
                        || (valueType.status() != FrontendExpressionTypeStatus.RESOLVED
                        && valueType.status() != FrontendExpressionTypeStatus.DYNAMIC)) {
                    return null;
                }
                sources.add(Objects.requireNonNull(keyType.publishedType(), "publishedType must not be null"));
                sources.add(Objects.requireNonNull(valueType.publishedType(), "publishedType must not be null"));
            }
            return List.copyOf(sources);
        }
        return null;
    }

    /// Builds a child-source resolver from an expected-type-aware nested resolver (bare-call path).
    public static @NotNull ChildSourceResolver fromContextualResolver(
            @NotNull FrontendExpressionSemanticSupport.ContextualNestedExpressionResolver nestedResolver
    ) {
        Objects.requireNonNull(nestedResolver, "nestedResolver must not be null");
        return expression -> nestedResolver.resolve(expression, false, null);
    }

    /// Builds a child-source resolver from chain `ExpressionTypeResolver` (CHAIN_BINDING path).
    public static @NotNull ChildSourceResolver fromChainExpressionTypeResolver(
            @NotNull BiFunction<Expression, Boolean, FrontendChainReductionHelper.ExpressionTypeResult> resolve
    ) {
        Objects.requireNonNull(resolve, "resolve must not be null");
        return expression -> {
            var result = resolve.apply(expression, false);
            return switch (result.status()) {
                case RESOLVED -> FrontendExpressionType.resolved(
                        Objects.requireNonNull(result.type(), "type must not be null")
                );
                case DYNAMIC -> FrontendExpressionType.dynamic(
                        Objects.requireNonNull(result.detailReason(), "detailReason must not be null")
                );
                case BLOCKED -> FrontendExpressionType.blocked(
                        result.type(),
                        Objects.requireNonNull(result.detailReason(), "detailReason must not be null")
                );
                case DEFERRED -> FrontendExpressionType.deferred(
                        Objects.requireNonNull(result.detailReason(), "detailReason must not be null")
                );
                case FAILED -> FrontendExpressionType.failed(
                        Objects.requireNonNull(result.detailReason(), "detailReason must not be null")
                );
                case UNSUPPORTED -> FrontendExpressionType.unsupported(
                        Objects.requireNonNull(result.detailReason(), "detailReason must not be null")
                );
            };
        };
    }

    /// Fixed-prefix element-boundary rank for one container-literal argument against one parameter.
    /// Non-literal arguments return `null` so the caller falls back to ordinary boundary ranking.
    public static @Nullable FrontendContainerLiteralSemanticSupport.CandidateRank rankLiteralArgumentOrNull(
            @NotNull ClassRegistry classRegistry,
            @NotNull Expression argument,
            @NotNull ChildSourceResolver childSourceResolver,
            @NotNull GdType parameterType
    ) {
        if (!(argument instanceof ArrayExpression) && !(argument instanceof DictionaryExpression)) {
            return null;
        }
        var childSources = preliminaryChildSourceTypesOrNull(argument, childSourceResolver);
        if (childSources == null) {
            return new FrontendContainerLiteralSemanticSupport.CandidateRank(true, 0, 0);
        }
        return FrontendContainerLiteralSemanticSupport.rankLiteralAgainstParameter(
                classRegistry,
                argument,
                childSources,
                parameterType
        );
    }

    /// Index-aware **applicability** rank used by `ScopeMethodResolver` for chain instance/static
    /// selection (`matchesArguments`). Specificity among applicable candidates is decided by
    /// `ScopeMethodResolver.CandidateSpecificity` → `literalAggregateRank`, not by comparing these
    /// packed ints across parameters.
    ///
    /// For container literals, returns an encoded element-boundary rank (`worst * scale + total`);
    /// rejected literals yield `0`. Non-literal positions reuse ordinary frontend boundary rank.
    public static int parameterCompatibilityRank(
            @NotNull ClassRegistry classRegistry,
            @NotNull List<? extends Expression> argumentExpressions,
            @NotNull ChildSourceResolver childSourceResolver,
            int argumentIndex,
            @NotNull GdType sourceType,
            @NotNull GdType targetType
    ) {
        Objects.requireNonNull(classRegistry, "classRegistry must not be null");
        Objects.requireNonNull(argumentExpressions, "argumentExpressions must not be null");
        Objects.requireNonNull(childSourceResolver, "childSourceResolver must not be null");
        Objects.requireNonNull(sourceType, "sourceType must not be null");
        Objects.requireNonNull(targetType, "targetType must not be null");
        if (argumentIndex >= 0 && argumentIndex < argumentExpressions.size()) {
            var argument = argumentExpressions.get(argumentIndex);
            var literalRank = rankLiteralArgumentOrNull(
                    classRegistry,
                    argument,
                    childSourceResolver,
                    targetType
            );
            if (literalRank != null) {
                return encodeLiteralRank(literalRank);
            }
        }
        return FrontendVariantBoundaryCompatibility.frontendBoundarySpecificityRank(
                classRegistry,
                sourceType,
                targetType
        );
    }

    /// Aggregate element-boundary rank across fixed arguments for one callable candidate.
    ///
    /// Container-literal ranks keep their multi-element `totalRank`; non-literal args contribute a
    /// single matrix decision. Empty literals contribute `CandidateRank.EMPTY` (vacuously best).
    ///
    /// Uses `FunctionDef.getParameters()` order. For GDCC instance methods that still carry a
    /// synthetic leading `self`, prefer the list-based overload with
    /// `ScopeResolvedMethod.parameters()` types so ranking stays aligned with user-visible fixed
    /// parameters.
    public static @NotNull FrontendContainerLiteralSemanticSupport.CandidateRank literalAggregateRank(
            @NotNull ClassRegistry classRegistry,
            @NotNull FunctionDef callable,
            @NotNull List<? extends Expression> argumentExpressions,
            @NotNull List<GdType> preliminaryArgumentTypes,
            @NotNull ChildSourceResolver childSourceResolver
    ) {
        return literalAggregateRank(
                classRegistry,
                fixedParameterTypes(callable),
                argumentExpressions,
                preliminaryArgumentTypes,
                childSourceResolver
        );
    }

    /// Same aggregation as the `FunctionDef` overload, but consumes an already-normalized fixed
    /// parameter type list (for example `ScopeResolvedMethod.parameters()` types after synthetic
    /// `self` stripping). Shared by bare, constructor, and chain specificity.
    public static @NotNull FrontendContainerLiteralSemanticSupport.CandidateRank literalAggregateRank(
            @NotNull ClassRegistry classRegistry,
            @NotNull List<GdType> fixedParameterTypes,
            @NotNull List<? extends Expression> argumentExpressions,
            @NotNull List<GdType> preliminaryArgumentTypes,
            @NotNull ChildSourceResolver childSourceResolver
    ) {
        Objects.requireNonNull(classRegistry, "classRegistry must not be null");
        Objects.requireNonNull(fixedParameterTypes, "fixedParameterTypes must not be null");
        Objects.requireNonNull(argumentExpressions, "argumentExpressions must not be null");
        Objects.requireNonNull(preliminaryArgumentTypes, "preliminaryArgumentTypes must not be null");
        Objects.requireNonNull(childSourceResolver, "childSourceResolver must not be null");
        var aggregate = FrontendContainerLiteralSemanticSupport.CandidateRank.EMPTY;
        var fixedPrefixCount = Math.min(preliminaryArgumentTypes.size(), fixedParameterTypes.size());
        for (var index = 0; index < fixedPrefixCount; index++) {
            var parameterType = fixedParameterTypes.get(index);
            var argument = index < argumentExpressions.size() ? argumentExpressions.get(index) : null;
            FrontendContainerLiteralSemanticSupport.CandidateRank operandRank;
            if (argument instanceof ArrayExpression || argument instanceof DictionaryExpression) {
                var rank = rankLiteralArgumentOrNull(
                        classRegistry,
                        argument,
                        childSourceResolver,
                        parameterType
                );
                if (rank == null || rank.rejected()) {
                    return new FrontendContainerLiteralSemanticSupport.CandidateRank(true, 0, 0);
                }
                operandRank = rank;
            } else {
                var decision = FrontendVariantBoundaryCompatibility.determineFrontendBoundaryDecision(
                        classRegistry,
                        preliminaryArgumentTypes.get(index),
                        parameterType
                );
                if (decision == FrontendVariantBoundaryCompatibility.Decision.REJECT) {
                    return new FrontendContainerLiteralSemanticSupport.CandidateRank(true, 0, 0);
                }
                var specificity = FrontendVariantBoundaryCompatibility.decisionSpecificityRank(decision);
                operandRank = new FrontendContainerLiteralSemanticSupport.CandidateRank(
                        false,
                        specificity,
                        specificity
                );
            }
            aggregate = mergeCandidateRanks(aggregate, operandRank);
        }
        return aggregate;
    }

    /// §5.4 candidate specificity: `literalAggregateRank` then `compareCandidateRanks`, with
    /// `fallback` when aggregates tie (classic Pareto / omitted-default rules).
    public static boolean isStrictlyMoreSpecificByLiteralAggregate(
            @NotNull ClassRegistry classRegistry,
            @NotNull List<GdType> candidateFixedParameterTypes,
            @NotNull List<GdType> baselineFixedParameterTypes,
            @NotNull List<? extends Expression> argumentExpressions,
            @NotNull List<GdType> preliminaryArgumentTypes,
            @NotNull ChildSourceResolver childSourceResolver,
            @NotNull BooleanSupplier fallbackWhenAggregateTies
    ) {
        Objects.requireNonNull(fallbackWhenAggregateTies, "fallbackWhenAggregateTies must not be null");
        var candidateRank = literalAggregateRank(
                classRegistry,
                candidateFixedParameterTypes,
                argumentExpressions,
                preliminaryArgumentTypes,
                childSourceResolver
        );
        var baselineRank = literalAggregateRank(
                classRegistry,
                baselineFixedParameterTypes,
                argumentExpressions,
                preliminaryArgumentTypes,
                childSourceResolver
        );
        var literalCompare = FrontendContainerLiteralSemanticSupport.compareCandidateRanks(
                candidateRank,
                baselineRank
        );
        if (literalCompare != 0) {
            return literalCompare < 0;
        }
        return fallbackWhenAggregateTies.getAsBoolean();
    }

    /// Whether a fixed parameter accepts a container-literal argument by element-boundary rank.
    /// Returns `null` when the argument is not a container literal (caller uses ordinary matrix).
    public static @Nullable Boolean literalArgumentCompatibleOrNull(
            @NotNull ClassRegistry classRegistry,
            @NotNull Expression argument,
            @NotNull ChildSourceResolver childSourceResolver,
            @NotNull GdType parameterType
    ) {
        var rank = rankLiteralArgumentOrNull(classRegistry, argument, childSourceResolver, parameterType);
        if (rank == null) {
            return null;
        }
        return !rank.rejected();
    }

    /// Rewrites a preliminary generic argument snapshot into contextual types for selected
    /// container-literal parameters. Non-literal arguments and Variant parameters stay unchanged.
    ///
    /// This does not finalize expression types or plans; it only rewrites the call-site snapshot
    /// published on `FrontendResolvedCall.argumentTypes()`.
    public static @NotNull List<GdType> rewriteArgumentTypes(
            @NotNull List<? extends Expression> argumentExpressions,
            @NotNull List<GdType> preliminaryArgumentTypes,
            @Nullable List<GdType> selectedFixedParameterTypesOrNull
    ) {
        Objects.requireNonNull(argumentExpressions, "argumentExpressions must not be null");
        Objects.requireNonNull(preliminaryArgumentTypes, "preliminaryArgumentTypes must not be null");
        if (selectedFixedParameterTypesOrNull == null || selectedFixedParameterTypesOrNull.isEmpty()) {
            return List.copyOf(preliminaryArgumentTypes);
        }
        var rewritten = new ArrayList<GdType>(preliminaryArgumentTypes.size());
        for (var index = 0; index < preliminaryArgumentTypes.size(); index++) {
            var preliminary = preliminaryArgumentTypes.get(index);
            if (index >= selectedFixedParameterTypesOrNull.size()
                    || index >= argumentExpressions.size()) {
                rewritten.add(preliminary);
                continue;
            }
            var argument = argumentExpressions.get(index);
            var parameterType = selectedFixedParameterTypesOrNull.get(index);
            if (parameterType instanceof GdVariantType) {
                rewritten.add(preliminary);
                continue;
            }
            if (argument instanceof ArrayExpression && parameterType instanceof GdArrayType arrayType) {
                rewritten.add(arrayType);
                continue;
            }
            if (argument instanceof DictionaryExpression
                    && parameterType instanceof GdDictionaryType dictionaryType) {
                rewritten.add(dictionaryType);
                continue;
            }
            rewritten.add(preliminary);
        }
        return List.copyOf(rewritten);
    }

    public static @NotNull List<GdType> fixedParameterTypes(@NotNull FunctionDef callable) {
        return Objects.requireNonNull(callable, "callable must not be null").getParameters().stream()
                .map(ParameterDef::getType)
                .toList();
    }

    /// Encodes a literal candidate rank for index-aware scope ranking.
    ///
    /// - rejected → `0` (inapplicable)
    /// - empty / vacuous (`worstRank == Integer.MAX_VALUE`) → `Integer.MAX_VALUE` (best, no overflow)
    /// - ordinary → `worst * scale + min(total, scale-1)` so higher worst wins before total
    static int encodeLiteralRank(@NotNull FrontendContainerLiteralSemanticSupport.CandidateRank rank) {
        if (rank.rejected()) {
            return 0;
        }
        // Empty literals and other vacuous aggregates use Integer.MAX_VALUE worstRank.
        if (rank.worstRank() == Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        // Non-literal matrix ranks stay in 0..4; literal multi-element totals are clamped so the
        // packed int never overflows (worst is at most decisionSpecificityRank max = 4).
        return rank.worstRank() * LITERAL_RANK_SCALE + Math.min(rank.totalRank(), LITERAL_RANK_SCALE - 1);
    }

    static @NotNull FrontendContainerLiteralSemanticSupport.CandidateRank mergeCandidateRanks(
            @NotNull FrontendContainerLiteralSemanticSupport.CandidateRank left,
            @NotNull FrontendContainerLiteralSemanticSupport.CandidateRank right
    ) {
        if (left.rejected() || right.rejected()) {
            return new FrontendContainerLiteralSemanticSupport.CandidateRank(true, 0, 0);
        }
        return new FrontendContainerLiteralSemanticSupport.CandidateRank(
                false,
                Math.min(left.worstRank(), right.worstRank()),
                left.totalRank() + right.totalRank()
        );
    }
}
