package gd.script.gdcc.frontend.sema;

import dev.superice.gdparser.frontend.ast.Block;
import dev.superice.gdparser.frontend.ast.ForStatement;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import gd.script.gdcc.frontend.scope.BlockScope;
import gd.script.gdcc.frontend.scope.BlockScopeKind;
import org.jetbrains.annotations.NotNull;

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
    /// 1. `expectedScope.kind()` must map to a policy that enters the suite resolver.
    /// 2. The scope graph must map `body` to the exact `expectedScope` instance.
    /// 3. The Interface surface must list `body` as a supported suite-entry root.
    /// 4. The declaration index must contain `body`, including an explicit empty entry for a body without locals.
    /// 5. Indexed declarations must have contiguous source order, matching binding/declaration/scope identities,
    ///    and a source-facing typed baseline equal to the published lexical binding type.
    /// 6. A `FOR_BODY` must additionally contain the iterator entry identified by its owning `ForStatement`.
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
        if (!policy.entersSuiteResolver()) {
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
            requireCompleteDeclaration(
                    analysisData,
                    interfaceSurface,
                    body,
                    expectedScope,
                    declaration
            );
        }

        if (expectedScope.kind() == BlockScopeKind.FOR_BODY
                && declarations.stream().noneMatch(
                declaration -> declaration.kind() == FrontendBodyLocalDeclaration.Kind.ITERATOR
        )) {
            throw incomplete(body, "`for` body declaration index does not contain its iterator");
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
        }
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
