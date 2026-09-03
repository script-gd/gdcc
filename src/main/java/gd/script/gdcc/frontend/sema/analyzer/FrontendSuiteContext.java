package gd.script.gdcc.frontend.sema.analyzer;

import dev.superice.gdparser.frontend.ast.Block;
import dev.superice.gdparser.frontend.ast.LambdaExpression;
import dev.superice.gdparser.frontend.ast.Node;
import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.scope.BlockScope;
import gd.script.gdcc.frontend.sema.FrontendAnalysisData;
import gd.script.gdcc.frontend.sema.FrontendInterfaceSurface;
import gd.script.gdcc.frontend.sema.FrontendTypedLexicalEnvironment;
import gd.script.gdcc.frontend.sema.analyzer.support.FrontendPropertyInitializerSupport;
import gd.script.gdcc.frontend.sema.patch.FrontendCallableExportBatch;
import gd.script.gdcc.frontend.sema.resolver.FrontendVisibleValueDomain;
import gd.script.gdcc.frontend.sema.resolver.FrontendVisibleValueResolveRequest;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.scope.ResolveRestriction;
import gd.script.gdcc.scope.Scope;
import gd.script.gdcc.type.GdType;
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
///
/// `currentCallableReturnType` is frozen at callable-root creation and threaded through child
/// blocks so return-value expected typing does not re-query a second skeleton path.
///
/// `visibleValueDomain` is explicit instead of derived from `currentBlockRoot`: expression-rooted
/// islands (parameter defaults) carry no block root, and a null-block fallback would misclassify
/// them as `EXECUTABLE_BODY`.
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
        @Nullable FrontendCallableExportBatch exportBatch,
        @Nullable GdType currentCallableReturnType,
        @Nullable NestedLambdaResolver nestedLambdaResolver,
        @NotNull FrontendVisibleValueDomain visibleValueDomain
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
        Objects.requireNonNull(visibleValueDomain, "visibleValueDomain must not be null");
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
                exportBatch,
                currentCallableReturnType,
                nestedLambdaResolver,
                visibleValueDomain
        );
    }

    public @NotNull FrontendVisibleValueResolveRequest visibleValueResolveRequest(
            @NotNull String name,
            @NotNull Node useSite
    ) {
        return new FrontendVisibleValueResolveRequest(name, useSite, restriction, visibleValueDomain);
    }

    /// True only inside the parameter-default island context built by
    /// `FrontendParameterDefaultMetadataOwner`; used to funnel visibility violations
    /// (parameter/local/capture hits, `self` under static restriction, await, get-node) into the
    /// owner's single anchored diagnostic instead of ordinary body diagnostics.
    public boolean isParameterDefaultIsland() {
        return visibleValueDomain == FrontendVisibleValueDomain.PARAMETER_DEFAULT;
    }

    /// Triggers the nested suite resolution of a recorded lambda encountered by an enclosing
    /// statement's owner procedure. Supplied by `FrontendSuiteResolver`; null in contexts that can
    /// never contain a recorded lambda (property initializers stay fail-closed).
    @FunctionalInterface
    public interface NestedLambdaResolver {
        void resolveNestedLambda(@NotNull FrontendSuiteContext outerContext, @NotNull LambdaExpression lambda);
    }
}
