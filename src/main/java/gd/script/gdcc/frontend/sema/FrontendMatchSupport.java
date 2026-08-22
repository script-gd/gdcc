package gd.script.gdcc.frontend.sema;

import dev.superice.gdparser.frontend.ast.ArrayExpression;
import dev.superice.gdparser.frontend.ast.AttributeExpression;
import dev.superice.gdparser.frontend.ast.DictionaryExpression;
import dev.superice.gdparser.frontend.ast.Expression;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.LiteralExpression;
import dev.superice.gdparser.frontend.ast.MatchStatement;
import dev.superice.gdparser.frontend.ast.PatternBindingExpression;
import gd.script.gdcc.type.GdExtensionTypeEnum;
import gd.script.gdcc.type.GdNilType;
import gd.script.gdcc.type.GdStringNameType;
import gd.script.gdcc.type.GdStringType;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.type.GdVariantType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/// Pure classification and plan-construction helper for `match` statements.
///
/// Route classification and bind collection inspect only the pattern AST shape and never decide
/// legality. Constantness / type-family helpers consume already-published analysis facts so
/// type-check, compile gate and CFG builder share one source instead of re-deriving them.
///
/// Pattern-context contract: `_` is a wildcard and `var x` is a binding only inside the match
/// pattern recursion served by this helper; ordinary expression contexts are unaffected.
public final class FrontendMatchSupport {
    private static final String WILDCARD_NAME = "_";

    /// Routes whose lowering is compile-ready. The set grows monotonically and must never lose an
    /// already-ready route. All six routes are currently ready. Match routes carry no operation
    /// descriptors, so no registry class exists.
    private static final Set<FrontendMatchPatternRoute> LOWERING_READY_ROUTES = Set.of(
            FrontendMatchPatternRoute.WILDCARD,
            FrontendMatchPatternRoute.BINDING,
            FrontendMatchPatternRoute.LITERAL,
            FrontendMatchPatternRoute.EXPRESSION,
            FrontendMatchPatternRoute.ARRAY,
            FrontendMatchPatternRoute.DICTIONARY
    );

    private FrontendMatchSupport() {
    }

    /// Whether a route's lowering is compile-ready; the compile gate blocks any match section
    /// containing a pattern whose route is not ready.
    public static boolean isRouteLoweringReady(@NotNull FrontendMatchPatternRoute route) {
        Objects.requireNonNull(route, "route must not be null");
        return LOWERING_READY_ROUTES.contains(route);
    }

    /// Classifies one pattern expression by AST shape only.
    ///
    /// `_` is recognized by name (gdparser has no wildcard node); a bare identifier is not a
    /// binding but an EXPRESSION pattern. Any evaluable expression is a legal pattern (gdcc
    /// deliberately extends Godot's identifier/attribute shape whitelist);
    /// constantness only selects the lowering sub-mode (constant vs runtime comparison),
    /// never legality, and is never decided here.
    public static @NotNull FrontendMatchPatternRoute classifyPatternRoute(@NotNull Expression pattern) {
        Objects.requireNonNull(pattern, "pattern must not be null");
        return switch (pattern) {
            case IdentifierExpression identifier -> WILDCARD_NAME.equals(identifier.name())
                    ? FrontendMatchPatternRoute.WILDCARD
                    : FrontendMatchPatternRoute.EXPRESSION;
            case PatternBindingExpression _ -> FrontendMatchPatternRoute.BINDING;
            case LiteralExpression _ -> FrontendMatchPatternRoute.LITERAL;
            case ArrayExpression _ -> FrontendMatchPatternRoute.ARRAY;
            case DictionaryExpression _ -> FrontendMatchPatternRoute.DICTIONARY;
            default -> FrontendMatchPatternRoute.EXPRESSION;
        };
    }

    /// Collects every bind inside one pattern tree in source order.
    ///
    /// `topLevel` marks binds that are the whole section pattern (subject binds, refinable); the
    /// recursion marks nested binds false (sub-element binds, always `Variant`). Dictionary entry
    /// keys are deliberately skipped: Godot parses keys as constant expressions, never patterns,
    /// so a key can not introduce a binding.
    public static @NotNull List<FrontendMatchBindingPlan> collectPatternBindings(
            @NotNull Expression pattern,
            boolean topLevel
    ) {
        Objects.requireNonNull(pattern, "pattern must not be null");
        var bindings = new ArrayList<FrontendMatchBindingPlan>();
        collectPatternBindingsInto(pattern, topLevel, bindings);
        return List.copyOf(bindings);
    }

    /// Builds the structural plan for a whole `match` statement: per-section pattern routes and
    /// the complete bind inventory, without any resolved type or lowering fact.
    public static @NotNull FrontendMatchPlan buildPlan(@NotNull MatchStatement statement) {
        Objects.requireNonNull(statement, "statement must not be null");
        var sections = new ArrayList<FrontendMatchSectionPlan>();
        for (var section : statement.sections()) {
            var patterns = new ArrayList<FrontendMatchPatternPlan>();
            for (var pattern : section.patterns()) {
                patterns.add(new FrontendMatchPatternPlan(
                        pattern,
                        classifyPatternRoute(pattern),
                        collectPatternBindings(pattern, true)
                ));
            }
            sections.add(new FrontendMatchSectionPlan(section, List.copyOf(patterns), section.guard() != null));
        }
        return new FrontendMatchPlan(statement, List.copyOf(sections));
    }

    /// Whether an EXPRESSION / dictionary-key operand is a compile-time constant from published
    /// facts. Constantness only selects the match lowering sub-mode (and dictionary-key legality);
    /// it never decides pattern legality.
    public static boolean isConstantPatternOperand(
            @NotNull FrontendAnalysisData analysisData,
            @NotNull Expression expression
    ) {
        Objects.requireNonNull(analysisData, "analysisData must not be null");
        Objects.requireNonNull(expression, "expression must not be null");
        if (expression instanceof LiteralExpression) {
            return true;
        }
        var binding = analysisData.symbolBindings().get(expression);
        if (binding != null && (binding.kind() == FrontendBindingKind.CONSTANT
                || binding.kind() == FrontendBindingKind.LITERAL)) {
            return true;
        }
        if (!(expression instanceof AttributeExpression attributeExpression)
                || attributeExpression.steps().isEmpty()) {
            return false;
        }
        var lastStep = attributeExpression.steps().getLast();
        var member = analysisData.resolvedMembers().get(lastStep);
        return member != null
                && member.status() == FrontendMemberResolutionStatus.RESOLVED
                && member.bindingKind() == FrontendBindingKind.CONSTANT;
    }

    /// Published type of a lowering-ready expression, or `null` when the fact is missing / unstable.
    public static @Nullable GdType publishedTypeOrNull(
            @NotNull FrontendAnalysisData analysisData,
            @NotNull Expression expression
    ) {
        Objects.requireNonNull(analysisData, "analysisData must not be null");
        Objects.requireNonNull(expression, "expression must not be null");
        var published = analysisData.expressionTypes().get(expression);
        if (published == null) {
            return null;
        }
        return switch (published.status()) {
            case RESOLVED, DYNAMIC -> published.publishedType();
            case BLOCKED, DEFERRED, FAILED, UNSUPPORTED -> null;
        };
    }

    /// Godot `Variant.Type` family used by match type-strict equality.
    ///
    /// `Variant` / unknown types return `null` so callers keep a runtime type-family gate.
    /// String and StringName stay distinct families; crossover is a separate match exception.
    public static @Nullable GdExtensionTypeEnum typeFamilyOrNull(@Nullable GdType type) {
        if (type == null || type instanceof GdVariantType) {
            return null;
        }
        if (type instanceof GdNilType) {
            return GdExtensionTypeEnum.NIL;
        }
        return type.getGdExtensionType();
    }

    /// String / StringName are the only Godot match type-family exception.
    public static boolean isStringFamily(@Nullable GdExtensionTypeEnum family) {
        return family == GdExtensionTypeEnum.STRING || family == GdExtensionTypeEnum.STRING_NAME;
    }

    public static boolean isStringLikeType(@Nullable GdType type) {
        return type instanceof GdStringType || type instanceof GdStringNameType;
    }

    /// Static type-strict equality used by LITERAL / constant-submode tests.
    ///
    /// `null` means a family is unknown (`Variant` / unpublished), so the caller must keep a
    /// runtime type-family gate rather than folding.
    public static boolean familiesCompatibleForMatch(
            @Nullable GdExtensionTypeEnum subjectFamily,
            @Nullable GdExtensionTypeEnum patternFamily
    ) {
        if (subjectFamily == null || patternFamily == null) {
            return false;
        }
        return subjectFamily == patternFamily
                || (isStringFamily(subjectFamily) && isStringFamily(patternFamily));
    }

    private static void collectPatternBindingsInto(
            @NotNull Expression pattern,
            boolean topLevel,
            @NotNull ArrayList<FrontendMatchBindingPlan> sink
    ) {
        switch (pattern) {
            case PatternBindingExpression binding ->
                    sink.add(new FrontendMatchBindingPlan(binding.name(), binding, topLevel));
            case ArrayExpression array -> {
                for (var element : array.elements()) {
                    collectPatternBindingsInto(element, false, sink);
                }
            }
            case DictionaryExpression dictionary -> {
                for (var entry : dictionary.entries()) {
                    collectPatternBindingsInto(entry.value(), false, sink);
                }
            }
            default -> {
            }
        }
    }
}
