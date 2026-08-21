package gd.script.gdcc.frontend.sema;

import dev.superice.gdparser.frontend.ast.ArrayExpression;
import dev.superice.gdparser.frontend.ast.DictionaryExpression;
import dev.superice.gdparser.frontend.ast.Expression;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.LiteralExpression;
import dev.superice.gdparser.frontend.ast.MatchStatement;
import dev.superice.gdparser.frontend.ast.PatternBindingExpression;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/// Pure classification and plan-construction helper for `match` statements.
///
/// The helper only inspects the pattern AST shape; it never reads source text, resolves names,
/// writes scopes or side tables, and never decides pattern legality (unresolvable expressions
/// fail in the ordinary resolution pipeline). Route classification and
/// bind collection therefore stay centralized here instead of being scattered across variable
/// inventory, type-check, compile gate and CFG builder (aligned with `FrontendForLoopSupport`).
///
/// Pattern-context contract: `_` is a wildcard and `var x` is a binding only inside the match
/// pattern recursion served by this helper; ordinary expression contexts are unaffected.
public final class FrontendMatchSupport {
    private static final String WILDCARD_NAME = "_";

    /// Routes whose lowering is compile-ready. The set grows monotonically with the graduation
    /// steps and must never lose an already-ready route: Step 1/2 keep it empty so every legal
    /// `match` stays route-not-ready at the compile gate (fail-closed intermediate state);
    /// Step 3 adds WILDCARD / BINDING / LITERAL / EXPRESSION; Step 4 adds ARRAY /
    /// DICTIONARY. Match routes carry no operation descriptors, so no registry class exists.
    private static final Set<FrontendMatchPatternRoute> LOWERING_READY_ROUTES = Set.of();

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
    /// deliberately extends Godot's identifier/attribute shape whitelist, see plan R9);
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
