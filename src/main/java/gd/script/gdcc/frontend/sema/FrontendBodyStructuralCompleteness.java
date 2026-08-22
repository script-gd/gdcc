package gd.script.gdcc.frontend.sema;

import dev.superice.gdparser.frontend.ast.Block;
import dev.superice.gdparser.frontend.ast.ForStatement;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.PatternBindingExpression;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import gd.script.gdcc.frontend.scope.BlockScope;
import gd.script.gdcc.frontend.scope.BlockScopeKind;
import gd.script.gdcc.scope.ScopeValueKind;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

/// Verifies that a structurally supported body has the complete Interface-phase surface required by the
/// segmented body pipeline.
///
/// This class is the completeness half of the body-entry contract. Structural support is decided separately
/// by [FrontendBodySemanticSupportPolicy]; this verifier proves that the current analysis run actually
/// published every required structural fact for one supported body. It inspects only the scope graph and the
/// immutable [FrontendInterfaceSurface]: suite-entry roots, the body declaration index, and the source-facing
/// typed baseline.
///
/// Completeness is bidirectional for published body inventory:
/// - every indexed declaration must agree with scope identity, binding identity, and typed baseline
/// - every `LOCAL` binding in the body's [BlockScope] inventory must appear in the declaration index
/// - indexed `sourceOrder` must be contiguous and non-decreasing by AST start-byte range
///
/// Typed overlays, expression types, slot refinements, iteration plans, diagnostics, and compile readiness are
/// deliberately excluded. Consequently, later semantic facts can refine a body after entry, but cannot make an
/// incomplete body appear complete or change whether [gd.script.gdcc.frontend.sema.analyzer.FrontendSuiteResolver]
/// enters it.
///
/// Every failure is an [IllegalStateException] because a missing or inconsistent structural fact is a phase
/// protocol breach, not a recoverable source error. This utility never mutates side tables and never publishes
/// diagnostics.
public final class FrontendBodyStructuralCompleteness {
    /// This class is a stateless certificate utility and is not instantiated.
    private FrontendBodyStructuralCompleteness() {
    }

    /// Requires all structural facts needed to enter `body` through `FrontendSuiteResolver`.
    ///
    /// Validation proceeds from the coarsest body-entry fact to declaration-level identities:
    ///
    /// 1. `expectedScope.kind()` must map to a policy that is a supported suite body root.
    /// 2. The scope graph must map `body` to the exact `expectedScope` instance.
    /// 3. The Interface surface must list `body` as a supported suite-entry root.
    /// 4. The declaration index must contain `body`, including an explicit empty entry for a body without locals.
    /// 5. Indexed declarations must have contiguous `sourceOrder`, non-decreasing AST start-byte order,
    ///    matching binding/declaration/scope identities, and a source-facing typed baseline equal to the
    ///    published lexical binding type.
    /// 6. Every published `LOCAL` value in `expectedScope` must have a matching declaration-index entry.
    /// 7. A `FOR_BODY` must contain exactly one iterator entry identified by its owning `ForStatement`, and
    ///    that entry must be the synthetic 0th item occupying `sourceOrder` 0 at the head of the body
    ///    inventory list.
    /// 8. A `MATCH_SECTION_BODY` may contain zero or more `PATTERN_BIND` entries occupying a contiguous
    ///    prefix (`sourceOrder 0..k-1`); ordinary locals follow at `sourceOrder >= k`.
    ///
    /// The method intentionally returns no boolean. A caller may enter a supported suite only after this method
    /// returns normally; any missing fact is an internal phase-order or publication error that must stop analysis.
    ///
    /// @param analysisData     the stable analysis surface supplying the published AST-to-scope graph
    /// @param interfaceSurface the immutable Interface-phase declaration, baseline, and suite-entry facts
    /// @param body             the supported body whose structural surface is being certified
    /// @param expectedScope    the exact block scope that the caller intends to use while resolving `body`
    /// @throws NullPointerException  if any argument is `null`
    /// @throws IllegalStateException if structural support or any required Interface-phase fact is missing or
    /// inconsistent
    public static void requireStructurallyCompleteBody(
            @NotNull FrontendAnalysisData analysisData,
            @NotNull FrontendInterfaceSurface interfaceSurface,
            @NotNull Block body,
            @NotNull BlockScope expectedScope
    ) {
        Objects.requireNonNull(analysisData, "analysisData");
        Objects.requireNonNull(interfaceSurface, "interfaceSurface");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(expectedScope, "expectedScope");

        var policy = FrontendBodySemanticSupportPolicy.forBlockScopeKind(expectedScope.kind());
        if (!policy.isSupportedSuiteBodyRoot()) {
            throw incomplete(body, "scope kind " + expectedScope.kind() + " is not a supported suite body");
        }
        if (analysisData.scopesByAst().get(body) != expectedScope) {
            throw incomplete(body, "body scope identity does not match the published scope graph");
        }
        if (!interfaceSurface.suiteEntryRoots().containsSupportedBlock(body)) {
            throw incomplete(body, "suite entry roots do not contain the supported body");
        }

        var declarationIndex = interfaceSurface.bodyDeclarationIndex();
        if (!declarationIndex.containsBodyRoot(body)) {
            throw incomplete(body, "body declaration index does not contain the supported body");
        }

        var declarations = declarationIndex.declarationsFor(body);
        for (var sourceOrder = 0; sourceOrder < declarations.size(); sourceOrder++) {
            var declaration = declarations.get(sourceOrder);
            if (declaration.sourceOrder() != sourceOrder) {
                throw incomplete(body, "declaration source order is not contiguous");
            }
            if (sourceOrder > 0
                    && declarationStartByte(declaration) < declarationStartByte(declarations.get(sourceOrder - 1))) {
                throw incomplete(body, "declaration source order does not match AST range order");
            }
            requireCompleteDeclaration(
                    analysisData,
                    interfaceSurface,
                    body,
                    expectedScope,
                    declaration
            );
        }

        requireScopeInventoryPublished(declarationIndex, body, expectedScope);

        if (expectedScope.kind() == BlockScopeKind.FOR_BODY) {
            requireForBodyIteratorInventory(body, declarations);
        }
        if (expectedScope.kind() == BlockScopeKind.MATCH_SECTION_BODY) {
            requireMatchSectionPatternBindInventory(body, declarations);
        }
    }

    /// Requires one indexed body-local declaration to agree with all other published structural views.
    ///
    /// Ordinary locals must be `VariableDeclaration` nodes owned by `expectedScope`. Iterators instead use the
    /// owning `ForStatement` as declaration identity, live in its `FOR_BODY` scope, and require the statement to
    /// remain mapped to the parent scope where the for header is analyzed. Both kinds must resolve back to the
    /// exact indexed binding and have a matching source-facing typed baseline.
    ///
    /// @param analysisData       the stable AST-to-scope graph
    /// @param interfaceSurface   the Interface-phase baseline and declaration-index surface
    /// @param body               the body that owns the indexed declaration
    /// @param expectedScope      the exact scope that must contain the indexed binding
    /// @param indexedDeclaration the declaration-index entry being certified
    /// @throws IllegalStateException if declaration, binding, scope, body ownership, or baseline identities drift
    private static void requireCompleteDeclaration(
            @NotNull FrontendAnalysisData analysisData,
            @NotNull FrontendInterfaceSurface interfaceSurface,
            @NotNull Block body,
            @NotNull BlockScope expectedScope,
            @NotNull FrontendBodyLocalDeclaration indexedDeclaration
    ) {
        var declaration = indexedDeclaration.declaration();
        var binding = indexedDeclaration.binding();
        if (binding.declaration() != declaration) {
            throw incomplete(body, "indexed binding declaration identity drifted");
        }
        var baselineType = interfaceSurface.typedLexicalBaseline().typeFor(declaration);
        if (baselineType == null || !baselineType.equals(binding.type())) {
            throw incomplete(body, "indexed declaration is missing its source-facing typed baseline");
        }

        switch (indexedDeclaration.kind()) {
            case ORDINARY_VAR -> {
                if (!(declaration instanceof VariableDeclaration variableDeclaration)
                        || analysisData.scopesByAst().get(variableDeclaration) != expectedScope
                        || expectedScope.resolveValueHere(variableDeclaration.name().trim()) != binding) {
                    throw incomplete(body, "ordinary local declaration, binding, and scope identities disagree");
                }
            }
            case ITERATOR -> {
                if (!(declaration instanceof ForStatement forStatement)
                        || forStatement.body() != body
                        || analysisData.scopesByAst().get(forStatement) != expectedScope.getParentScope()
                        || expectedScope.resolveValueHere(forStatement.iterator().trim()) != binding) {
                    throw incomplete(body, "iterator declaration, binding, and `for` scope identities disagree");
                }
            }
            case PATTERN_BIND -> {
                if (!(declaration instanceof PatternBindingExpression patternBinding)
                        || analysisData.scopesByAst().get(patternBinding) != expectedScope
                        || expectedScope.resolveValueHere(patternBinding.name()) != binding) {
                    throw incomplete(
                            body,
                            "pattern-bind declaration, binding, and `match` section scope identities disagree"
                    );
                }
            }
        }
    }

    /// Requires every published `LOCAL` scope binding to appear in the body declaration index.
    ///
    /// This is the reverse half of inventory completeness: the index is only a view over `BlockScope`
    /// inventory, so a producer that omits an accepted local must fail before suite entry rather than only
    /// when that local is later looked up.
    private static void requireScopeInventoryPublished(
            @NotNull FrontendBodyDeclarationIndex declarationIndex,
            @NotNull Block body,
            @NotNull BlockScope expectedScope
    ) {
        for (var value : expectedScope.localValues()) {
            if (value.kind() != ScopeValueKind.LOCAL) {
                continue;
            }
            if (!(value.declaration() instanceof Node declarationNode)
                    || (!(declarationNode instanceof VariableDeclaration)
                    && !(declarationNode instanceof ForStatement)
                    && !(declarationNode instanceof PatternBindingExpression))) {
                throw incomplete(
                        body,
                        "scope inventory contains a local that is not a body declaration-index identity"
                );
            }
            var indexedDeclaration = declarationIndex.declarationFor(declarationNode);
            if (indexedDeclaration == null
                    || indexedDeclaration.binding().declaration() != value.declaration()
                    || indexedDeclaration.binding().kind() != value.kind()) {
                throw incomplete(
                        body,
                        "scope inventory local is missing from body declaration index"
                );
            }
        }
    }

    /// Requires the Interface-phase `FOR_BODY` inventory contract: exactly one iterator entry as the
    /// synthetic 0th item, list head, `sourceOrder == 0`. Ordinary body locals may follow only at contiguous
    /// `sourceOrder >= 1`.
    private static void requireForBodyIteratorInventory(
            @NotNull Block body,
            @NotNull List<FrontendBodyLocalDeclaration> declarations
    ) {
        var iteratorCount = 0;
        for (var declaration : declarations) {
            if (declaration.kind() == FrontendBodyLocalDeclaration.Kind.ITERATOR) {
                iteratorCount++;
            }
        }
        if (iteratorCount != 1) {
            throw incomplete(body, "`for` body declaration index must contain exactly one iterator");
        }
        var firstDeclaration = declarations.getFirst();
        if (firstDeclaration.kind() != FrontendBodyLocalDeclaration.Kind.ITERATOR
                || firstDeclaration.sourceOrder() != 0) {
            throw incomplete(body, "`for` body iterator must be the first declaration with sourceOrder 0");
        }
    }

    /// Requires `PATTERN_BIND` entries, if any, to occupy a contiguous prefix at the head of the
    /// `MATCH_SECTION_BODY` inventory. Ordinary locals may follow only after that prefix.
    private static void requireMatchSectionPatternBindInventory(
            @NotNull Block body,
            @NotNull List<FrontendBodyLocalDeclaration> declarations
    ) {
        var seenOrdinary = false;
        for (var declaration : declarations) {
            if (declaration.kind() == FrontendBodyLocalDeclaration.Kind.PATTERN_BIND) {
                if (seenOrdinary) {
                    throw incomplete(body, "`match` section pattern binds must occupy a contiguous prefix");
                }
                continue;
            }
            if (declaration.kind() == FrontendBodyLocalDeclaration.Kind.ORDINARY_VAR) {
                seenOrdinary = true;
                continue;
            }
            throw incomplete(body, "`match` section declaration index contains an unexpected kind");
        }
    }

    private static int declarationStartByte(@NotNull FrontendBodyLocalDeclaration declaration) {
        return declaration.declaration().range().startByte();
    }

    /// Creates the uniform protocol-breach exception used by this certificate.
    ///
    /// Including the body's start byte keeps failures attributable to a concrete Interface body even though the
    /// exception represents an internal invariant violation rather than a source diagnostic.
    ///
    /// @param body   the incomplete body used as the failure anchor
    /// @param detail the specific structural invariant that was violated
    /// @return an exception ready to be thrown by the failing certificate check
    private static @NotNull IllegalStateException incomplete(@NotNull Block body, @NotNull String detail) {
        return new IllegalStateException(
                "Structurally supported body at byte " + body.range().startByte() + " is incomplete: " + detail
        );
    }
}
