package gd.script.gdcc.frontend.sema.analyzer;

import dev.superice.gdparser.frontend.ast.Block;
import dev.superice.gdparser.frontend.ast.ConstructorDeclaration;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.scope.BlockScope;
import gd.script.gdcc.frontend.sema.FrontendAnalysisData;
import gd.script.gdcc.frontend.sema.FrontendInterfaceSurface;
import gd.script.gdcc.frontend.sema.FrontendTypedLexicalEnvironment;
import gd.script.gdcc.frontend.sema.analyzer.support.FrontendPropertyInitializerSupport;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.scope.ResolveRestriction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.Objects;

/// Body-suite coordinator for the staged semantic pipeline.
///
/// Phase D runs no-op owner procedures by default. Its job is to prove that executable body roots are
/// entered only through `FrontendInterfaceSurface` and that overlay facts are exported through an
/// ordered patch transaction instead of direct stable side-table writes.
public class FrontendSuiteResolver {
    private final @NotNull FrontendStatementResolver statementResolver;

    public FrontendSuiteResolver() {
        this(new FrontendStatementResolver());
    }

    public FrontendSuiteResolver(@NotNull FrontendStatementResolver statementResolver) {
        this.statementResolver = Objects.requireNonNull(statementResolver, "statementResolver must not be null");
    }

    public void resolve(
            @NotNull FrontendInterfaceSurface interfaceSurface,
            @NotNull ClassRegistry classRegistry,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull DiagnosticManager diagnosticManager
    ) {
        Objects.requireNonNull(interfaceSurface, "interfaceSurface must not be null");
        Objects.requireNonNull(classRegistry, "classRegistry must not be null");
        Objects.requireNonNull(analysisData, "analysisData must not be null");
        Objects.requireNonNull(diagnosticManager, "diagnosticManager must not be null");

        for (var callableOwner : interfaceSurface.suiteEntryRoots().callableOwners()) {
            resolveCallableOwner(interfaceSurface, callableOwner, classRegistry, analysisData, diagnosticManager);
        }
        for (var propertyInitializer : interfaceSurface.suiteEntryRoots().propertyInitializers()) {
            resolvePropertyInitializer(interfaceSurface, propertyInitializer, classRegistry, analysisData, diagnosticManager);
        }
    }

    public void resolveSuite(@NotNull FrontendSuiteContext context, @NotNull Block block) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(block, "block must not be null");
        if (!context.interfaceSurface().suiteEntryRoots().containsSupportedBlock(block)) {
            return;
        }
        for (var statement : block.statements()) {
            statementResolver.resolveStatement(context, statement, this::resolveChildSuite);
        }
        context.typedEnvironment().exportPatchTransaction().applyTo(context.analysisData());
        context.analysisData().updateDiagnostics(context.diagnosticManager().snapshot());
    }

    private void resolveCallableOwner(
            @NotNull FrontendInterfaceSurface interfaceSurface,
            @NotNull Node callableOwner,
            @NotNull ClassRegistry classRegistry,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull DiagnosticManager diagnosticManager
    ) {
        var body = callableBody(callableOwner);
        if (body == null || !interfaceSurface.suiteEntryRoots().containsSupportedBlock(body)) {
            return;
        }
        var bodyScope = analysisData.scopesByAst().get(body);
        if (!(bodyScope instanceof BlockScope blockScope)) {
            return;
        }
        var environment = new FrontendTypedLexicalEnvironment(blockScope, analysisData);
        var context = new FrontendSuiteContext(
                sourcePathFor(interfaceSurface, callableOwner, analysisData),
                callableOwner,
                body,
                blockScope,
                blockScope,
                restrictionForCallable(callableOwner),
                isStaticCallable(callableOwner),
                null,
                interfaceSurface,
                environment,
                analysisData,
                diagnosticManager,
                classRegistry
        );
        resolveSuite(context, body);
    }

    private void resolvePropertyInitializer(
            @NotNull FrontendInterfaceSurface interfaceSurface,
            @NotNull VariableDeclaration propertyInitializer,
            @NotNull ClassRegistry classRegistry,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull DiagnosticManager diagnosticManager
    ) {
        var propertyContext = FrontendPropertyInitializerSupport.contextOrNull(
                analysisData.scopesByAst(),
                propertyInitializer
        );
        if (propertyContext == null) {
            return;
        }
        var classScope = propertyContext.declaringClassScope();
        var environment = new FrontendTypedLexicalEnvironment(classScope, analysisData);
        var context = new FrontendSuiteContext(
                sourcePathFor(interfaceSurface, propertyInitializer, analysisData),
                propertyInitializer,
                null,
                classScope,
                null,
                FrontendPropertyInitializerSupport.restrictionFor(propertyInitializer),
                propertyInitializer.isStatic(),
                propertyContext,
                interfaceSurface,
                environment,
                analysisData,
                diagnosticManager,
                classRegistry
        );
        statementResolver.resolvePropertyInitializer(context, propertyInitializer);
        context.typedEnvironment().exportPatchTransaction().applyTo(analysisData);
        analysisData.updateDiagnostics(diagnosticManager.snapshot());
    }

    private void resolveChildSuite(@NotNull FrontendSuiteContext parentContext, @NotNull Block childBlock) {
        if (!parentContext.interfaceSurface().suiteEntryRoots().containsSupportedBlock(childBlock)) {
            return;
        }
        var childScope = parentContext.analysisData().scopesByAst().get(childBlock);
        if (!(childScope instanceof BlockScope blockScope)) {
            return;
        }
        resolveSuite(parentContext.withChildBlock(childBlock, blockScope), childBlock);
    }

    private static @Nullable Block callableBody(@NotNull Node callableOwner) {
        return switch (callableOwner) {
            case FunctionDeclaration functionDeclaration -> functionDeclaration.body();
            case ConstructorDeclaration constructorDeclaration -> constructorDeclaration.body();
            default -> null;
        };
    }

    private static @NotNull ResolveRestriction restrictionForCallable(@NotNull Node callableOwner) {
        if (callableOwner instanceof FunctionDeclaration functionDeclaration && functionDeclaration.isStatic()) {
            return ResolveRestriction.staticContext();
        }
        return ResolveRestriction.instanceContext();
    }

    private static boolean isStaticCallable(@NotNull Node callableOwner) {
        return callableOwner instanceof FunctionDeclaration functionDeclaration && functionDeclaration.isStatic();
    }

    private static @NotNull Path sourcePathFor(
            @NotNull FrontendInterfaceSurface interfaceSurface,
            @NotNull Node entryRoot,
            @NotNull FrontendAnalysisData analysisData
    ) {
        var sourcePath = interfaceSurface.suiteEntryRoots().sourcePathFor(entryRoot);
        if (sourcePath != null) {
            return sourcePath;
        }
        return analysisData.moduleSkeleton().sourceClassRelations().getFirst().unit().path();
    }
}
