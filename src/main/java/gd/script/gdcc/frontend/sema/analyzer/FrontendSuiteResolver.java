package gd.script.gdcc.frontend.sema.analyzer;

import dev.superice.gdparser.frontend.ast.Block;
import dev.superice.gdparser.frontend.ast.ConstructorDeclaration;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.Parameter;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.diagnostic.FrontendRange;
import gd.script.gdcc.frontend.scope.BlockScope;
import gd.script.gdcc.frontend.scope.CallableScope;
import gd.script.gdcc.frontend.sema.FrontendAnalysisData;
import gd.script.gdcc.frontend.sema.FrontendBodyStructuralCompleteness;
import gd.script.gdcc.frontend.sema.FrontendInterfaceSurface;
import gd.script.gdcc.frontend.sema.FrontendSemanticStage;
import gd.script.gdcc.frontend.sema.FrontendTypedLexicalEnvironment;
import gd.script.gdcc.frontend.sema.analyzer.support.FrontendCallableReturnTypeSupport;
import gd.script.gdcc.frontend.sema.analyzer.support.FrontendPropertyInitializerSupport;
import gd.script.gdcc.frontend.sema.patch.FrontendCallableExportBatch;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.scope.ResolveRestriction;
import gd.script.gdcc.scope.ScopeValueKind;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/// Body-suite coordinator for the staged semantic pipeline.
///
/// The resolver uses statement-local owner procedures. Tests may inject a custom
/// `FrontendStatementResolver` to record traversal shape, but production body facts must flow through
/// the typed lexical environment and ordered patch transaction.
public class FrontendSuiteResolver {
    private static final @NotNull String UNSUPPORTED_BINDING_SUBTREE_CATEGORY =
            "sema.unsupported_binding_subtree";
    private static final @NotNull String UNSUPPORTED_CHAIN_ROUTE_CATEGORY = "sema.unsupported_chain_route";

    private final @NotNull FrontendStatementResolver statementResolver;

    public FrontendSuiteResolver() {
        this(new FrontendStatementResolver(new FrontendBodyOwnerProcedures()));
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

    /// Resolves one root or nested suite and defers its stable publication to the callable export batch.
    ///
    /// Child environments retain separate pending and committed overlays. Their transactions join the
    /// root callable's batch rather than becoming visible through stable side tables mid-resolution.
    public void resolveSuite(@NotNull FrontendSuiteContext context, @NotNull Block block) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(block, "block must not be null");
        var blockScope = requireBlockScope(context, block);
        FrontendBodyStructuralCompleteness.requireStructurallyCompleteBody(
                context.analysisData(),
                context.interfaceSurface(),
                block,
                blockScope
        );
        for (var statement : block.statements()) {
            statementResolver.resolveStatement(context, statement, this::resolveChildSuite);
        }
        var transaction = context.typedEnvironment().exportPatchTransaction();
        var exportBatch = context.exportBatch();
        if (exportBatch != null) {
            exportBatch.accumulate(transaction);
        } else {
            transaction.applyTo(context.analysisData());
        }
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
        if (body == null) {
            throw new IllegalStateException("Suite entry callable owner has no executable body");
        }
        var bodyScope = analysisData.scopesByAst().get(body);
        if (!(bodyScope instanceof BlockScope blockScope)) {
            throw new IllegalStateException("Suite entry callable body has no published BlockScope");
        }
        var environment = new FrontendTypedLexicalEnvironment(
                blockScope,
                analysisData,
                null,
                interfaceSurface.typedLexicalBaseline()
        );
        var exportBatch = new FrontendCallableExportBatch();
        var currentCallableReturnType = FrontendCallableReturnTypeSupport.resolveReturnTypeOrNull(
                callableOwner,
                blockScope.owningClassOrNull()
        );
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
                classRegistry,
                exportBatch,
                currentCallableReturnType
        );
        runCallableEntryVarTypePost(context, callableOwner);
        resolveSuite(context, body);
        // Stable export is ordered but non-atomic: queued transactions are not preflighted together,
        // and a later failure does not roll back patches or transactions that were already applied.
        exportBatch.applyTo(analysisData);
    }

    /// Publishes callable-entry var-type-post facts before the first body statement is resolved.
    ///
    /// Parameters are not statement roots, so this complements rather than bypasses the
    /// statement-local var-type-post procedure. Both paths publish through the same overlay,
    /// owner stage, and callable-scoped export batch.
    private void runCallableEntryVarTypePost(
            @NotNull FrontendSuiteContext context,
            @NotNull Node callableOwner
    ) {
        var parameters = callableParameters(callableOwner);
        for (var parameter : parameters) {
            publishCallableEntryParameterSlotType(context, parameter);
            reportUnsupportedParameterDefault(context, parameter);
        }
        // Make every parameter visible to the first statement without publishing stable facts.
        context.typedEnvironment().flushPendingFacts();
    }

    private void publishCallableEntryParameterSlotType(
            @NotNull FrontendSuiteContext context,
            @NotNull Parameter parameter
    ) {
        var scope = context.analysisData().scopesByAst().get(parameter);
        if (!(scope instanceof CallableScope callableScope)) {
            throw new IllegalStateException("Parameter '" + parameter.name().trim() + "' has no published callable scope");
        }
        var slot = callableScope.resolveValueHere(parameter.name().trim());
        if (slot == null || slot.kind() != ScopeValueKind.PARAMETER || slot.declaration() != parameter) {
            throw new IllegalStateException("Parameter '" + parameter.name().trim() + "' inventory slot drifted");
        }
        var baselineType = context.interfaceSurface().typedLexicalBaseline().typeFor(parameter);
        if (baselineType == null) {
            throw new IllegalStateException("Parameter '" + parameter.name().trim() + "' is missing typed baseline");
        }
        context.typedEnvironment().putSlotType(FrontendSemanticStage.VAR_TYPE_POST, parameter, baselineType);
    }

    private static void reportUnsupportedParameterDefault(
            @NotNull FrontendSuiteContext context,
            @NotNull Parameter parameter
    ) {
        if (parameter.defaultValue() == null) {
            return;
        }
        context.diagnosticManager().error(
                UNSUPPORTED_BINDING_SUBTREE_CATEGORY,
                "Binding analysis is not supported in parameter default",
                context.sourcePath(),
                FrontendRange.fromAstRange(parameter.defaultValue().range())
        );
        context.diagnosticManager().error(
                UNSUPPORTED_CHAIN_ROUTE_CATEGORY,
                "Chain binding analysis is not supported in parameter default",
                context.sourcePath(),
                FrontendRange.fromAstRange(parameter.defaultValue().range())
        );
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
        var environment = new FrontendTypedLexicalEnvironment(
                classScope,
                analysisData,
                null,
                interfaceSurface.typedLexicalBaseline()
        );
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
                classRegistry,
                null,
                null
        );
        statementResolver.resolvePropertyInitializer(context, propertyInitializer);
        // Property initializers are independent roots and do not join a callable export batch.
        context.typedEnvironment().exportPatchTransaction().applyTo(analysisData);
        analysisData.updateDiagnostics(diagnosticManager.snapshot());
    }

    /// Resolves a child block with its own overlay while sharing the parent's callable export batch.
    private void resolveChildSuite(@NotNull FrontendSuiteContext parentContext, @NotNull Block childBlock) {
        var blockScope = requireBlockScope(parentContext, childBlock);
        resolveSuite(parentContext.withChildBlock(childBlock, blockScope), childBlock);
    }

    private static @NotNull BlockScope requireBlockScope(
            @NotNull FrontendSuiteContext context,
            @NotNull Block block
    ) {
        var scope = context.analysisData().scopesByAst().get(block);
        if (scope instanceof BlockScope blockScope) {
            return blockScope;
        }
        throw new IllegalStateException("Suite body has no published BlockScope");
    }

    private static @Nullable Block callableBody(@NotNull Node callableOwner) {
        return switch (callableOwner) {
            case FunctionDeclaration functionDeclaration -> functionDeclaration.body();
            case ConstructorDeclaration constructorDeclaration -> constructorDeclaration.body();
            default -> null;
        };
    }

    private static @NotNull List<Parameter> callableParameters(@NotNull Node callableOwner) {
        return switch (callableOwner) {
            case FunctionDeclaration functionDeclaration -> functionDeclaration.parameters();
            case ConstructorDeclaration constructorDeclaration -> constructorDeclaration.parameters();
            default -> List.of();
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
