package gd.script.gdcc.frontend.sema.analyzer;

import dev.superice.gdparser.frontend.ast.Block;
import dev.superice.gdparser.frontend.ast.Node;
import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.scope.BlockScope;
import gd.script.gdcc.frontend.sema.FrontendAnalysisData;
import gd.script.gdcc.frontend.sema.FrontendBodySemanticSupportPolicy;
import gd.script.gdcc.frontend.sema.FrontendInterfaceSurface;
import gd.script.gdcc.frontend.sema.FrontendTypedLexicalEnvironment;
import gd.script.gdcc.frontend.sema.analyzer.support.FrontendPropertyInitializerSupport;
import gd.script.gdcc.frontend.sema.patch.FrontendCallableExportBatch;
import gd.script.gdcc.frontend.sema.resolver.FrontendVisibleValueDomain;
import gd.script.gdcc.frontend.sema.resolver.FrontendVisibleValueResolveRequest;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.scope.ResolveRestriction;
import gd.script.gdcc.scope.Scope;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.Objects;

/// Statement-local context passed through the body SuiteResolver.
///
/// The context deliberately carries the typed lexical environment as an explicit dependency so later
/// owner procedures cannot fall back to hidden analyzer-local side-table snapshots. Callable roots
/// also share one export batch with their nested suites; child overlays stay isolated while their
/// exported transactions are deferred to the callable boundary.
public record FrontendSuiteContext(
        @NotNull Path sourcePath,
        @NotNull Node callableOwner,
        @Nullable Block currentBlockRoot,
        @NotNull Scope currentScope,
        @Nullable BlockScope currentBlockScope,
        @NotNull ResolveRestriction restriction,
        boolean staticContext,
        @Nullable FrontendPropertyInitializerSupport.PropertyInitializerContext propertyInitializerContext,
        @NotNull FrontendInterfaceSurface interfaceSurface,
        @NotNull FrontendTypedLexicalEnvironment typedEnvironment,
        @NotNull FrontendAnalysisData analysisData,
        @NotNull DiagnosticManager diagnosticManager,
        @NotNull ClassRegistry classRegistry,
        @Nullable FrontendCallableExportBatch exportBatch
) {
    public FrontendSuiteContext {
        Objects.requireNonNull(sourcePath, "sourcePath must not be null");
        Objects.requireNonNull(callableOwner, "callableOwner must not be null");
        Objects.requireNonNull(currentScope, "currentScope must not be null");
        Objects.requireNonNull(restriction, "restriction must not be null");
        Objects.requireNonNull(interfaceSurface, "interfaceSurface must not be null");
        Objects.requireNonNull(typedEnvironment, "typedEnvironment must not be null");
        Objects.requireNonNull(analysisData, "analysisData must not be null");
        Objects.requireNonNull(diagnosticManager, "diagnosticManager must not be null");
        Objects.requireNonNull(classRegistry, "classRegistry must not be null");
    }

    public @NotNull FrontendSuiteContext withChildBlock(@NotNull Block block, @NotNull BlockScope blockScope) {
        var childEnvironment = new FrontendTypedLexicalEnvironment(
                blockScope,
                analysisData,
                typedEnvironment,
                interfaceSurface.typedLexicalBaseline()
        );
        return new FrontendSuiteContext(
                sourcePath,
                callableOwner,
                block,
                blockScope,
                blockScope,
                restriction,
                staticContext,
                propertyInitializerContext,
                interfaceSurface,
                childEnvironment,
                analysisData,
                diagnosticManager,
                classRegistry,
                exportBatch
        );
    }

    public @NotNull FrontendVisibleValueResolveRequest visibleValueResolveRequest(
            @NotNull String name,
            @NotNull Node useSite
    ) {
        return new FrontendVisibleValueResolveRequest(name, useSite, restriction, visibleValueDomainForCurrentBody());
    }

    private @NotNull FrontendVisibleValueDomain visibleValueDomainForCurrentBody() {
        if (currentBlockRoot == null || currentBlockScope == null) {
            return FrontendVisibleValueDomain.EXECUTABLE_BODY;
        }
        return FrontendBodySemanticSupportPolicy.forBlockScopeKind(currentBlockScope.kind()).visibleValueDomain();
    }
}
