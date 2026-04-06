package dev.superice.gdcc.frontend.sema.analyzer;

import dev.superice.gdcc.frontend.diagnostic.DiagnosticManager;
import dev.superice.gdcc.frontend.diagnostic.FrontendRange;
import dev.superice.gdcc.frontend.scope.BlockScope;
import dev.superice.gdcc.frontend.scope.ClassScope;
import dev.superice.gdcc.frontend.sema.FrontendAnalysisData;
import dev.superice.gdcc.frontend.sema.FrontendAstSideTable;
import dev.superice.gdcc.frontend.sema.FrontendDeclaredTypeSupport;
import dev.superice.gdcc.frontend.sema.FrontendExpressionType;
import dev.superice.gdcc.frontend.sema.analyzer.support.FrontendPropertyInitializerSupport;
import dev.superice.gdcc.frontend.sema.analyzer.support.FrontendAssignmentSemanticSupport;
import dev.superice.gdcc.frontend.sema.analyzer.support.FrontendChainReductionFacade;
import dev.superice.gdcc.frontend.sema.analyzer.support.FrontendChainReductionHelper;
import dev.superice.gdcc.scope.ClassDef;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.scope.PropertyDef;
import dev.superice.gdcc.scope.ResolveRestriction;
import dev.superice.gdcc.scope.Scope;
import dev.superice.gdcc.scope.ScopeValue;
import dev.superice.gdcc.type.GdType;
import dev.superice.gdcc.type.GdVariantType;
import dev.superice.gdcc.type.GdVoidType;
import dev.superice.gdparser.frontend.ast.ASTNodeHandler;
import dev.superice.gdparser.frontend.ast.ASTWalker;
import dev.superice.gdparser.frontend.ast.AssertStatement;
import dev.superice.gdparser.frontend.ast.Block;
import dev.superice.gdparser.frontend.ast.ClassDeclaration;
import dev.superice.gdparser.frontend.ast.ConstructorDeclaration;
import dev.superice.gdparser.frontend.ast.DeclarationKind;
import dev.superice.gdparser.frontend.ast.ElifClause;
import dev.superice.gdparser.frontend.ast.Expression;
import dev.superice.gdparser.frontend.ast.FrontendASTTraversalDirective;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.IfStatement;
import dev.superice.gdparser.frontend.ast.LambdaExpression;
import dev.superice.gdparser.frontend.ast.MatchStatement;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.ReturnStatement;
import dev.superice.gdparser.frontend.ast.SourceFile;
import dev.superice.gdparser.frontend.ast.Statement;
import dev.superice.gdparser.frontend.ast.TypeRef;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import dev.superice.gdparser.frontend.ast.WhileStatement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/// Diagnostics-only frontend typed-contract analyzer.
///
/// Current contract:
/// - require upstream phases to have already published scope/binding/member/call/expression facts
/// - walk only the statement roots that own local/property/return/condition contracts
/// - emit diagnostics without creating any new side table
/// - preserve upstream diagnostic ownership for unstable expression roots
///
/// The protected callback surface exists so tests can lock the traversal/context contract without
/// duplicating the visitor logic in test-only analyzers.
public class FrontendTypeCheckAnalyzer {
    private static final @NotNull String TYPE_CHECK_CATEGORY = "sema.type_check";
    private static final @NotNull String TYPE_HINT_CATEGORY = "sema.type_hint";

    private static @NotNull String parameterizedGdccConstructorUnsupportedMessage(@NotNull ClassDef currentClass) {
        return "GDCC custom class constructor '" + Objects.requireNonNull(currentClass, "currentClass must not be null").getName()
                + "._init(...)' currently supports only zero parameters; parameterized '_init' would not be honored "
                + "by the runtime construction path";
    }

    public void analyze(
            @NotNull ClassRegistry classRegistry,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull DiagnosticManager diagnosticManager
    ) {
        Objects.requireNonNull(classRegistry, "classRegistry must not be null");
        Objects.requireNonNull(analysisData, "analysisData must not be null");
        Objects.requireNonNull(diagnosticManager, "diagnosticManager must not be null");

        var moduleSkeleton = analysisData.moduleSkeleton();
        analysisData.diagnostics();

        var scopesByAst = analysisData.scopesByAst();

        for (var sourceClassRelation : moduleSkeleton.sourceClassRelations()) {
            var sourceFile = sourceClassRelation.unit().ast();
            if (!scopesByAst.containsKey(sourceFile)) {
                throw new IllegalStateException(
                        "Scope graph has not been published for source file: " + sourceClassRelation.unit().path()
                );
            }
        }

        for (var sourceClassRelation : moduleSkeleton.sourceClassRelations()) {
            new AstWalkerTypeCheckVisitor(
                    sourceClassRelation.unit().path(),
                    classRegistry,
                    analysisData,
                    scopesByAst,
                    diagnosticManager
            ).walk(sourceClassRelation.unit().ast());
        }
    }

    protected void visitOrdinaryLocalInitializer(
            @NotNull TypeCheckAccess access,
            @NotNull VariableDeclaration variableDeclaration
    ) {
        Objects.requireNonNull(access, "access must not be null");
        Objects.requireNonNull(variableDeclaration, "variableDeclaration must not be null");
        if (!hasExplicitDeclaredType(variableDeclaration.type())) {
            return;
        }

        var localSlot = publishedOrdinaryLocalSlotOrNull(access, variableDeclaration);
        if (localSlot == null) {
            return;
        }
        var initializerType = stableExpressionTypeOrNull(
                access.analysisData(),
                Objects.requireNonNull(variableDeclaration.value(), "local initializer must not be null"),
                "Local initializer for '" + variableDeclaration.name() + "'"
        );
        if (initializerType == null) {
            return;
        }
        var publishedInitializerType = Objects.requireNonNull(
                initializerType.publishedType(),
                "publishedType must not be null for stable initializer type"
        );
        if (access.checkAssignmentCompatible(localSlot.type(), publishedInitializerType)) {
            return;
        }

        reportInitializerTypeMismatch(
                access,
                variableDeclaration,
                "Local variable '" + variableDeclaration.name() + "'",
                localSlot.type(),
                publishedInitializerType
        );
    }

    protected void visitPropertyInitializer(
            @NotNull TypeCheckAccess access,
            @NotNull VariableDeclaration variableDeclaration
    ) {
        Objects.requireNonNull(access, "access must not be null");
        Objects.requireNonNull(variableDeclaration, "variableDeclaration must not be null");

        var publishedProperty = publishedPropertyOrNull(access, variableDeclaration);
        if (publishedProperty == null) {
            return;
        }
        var initializerType = stableExpressionTypeOrNull(
                access.analysisData(),
                Objects.requireNonNull(variableDeclaration.value(), "property initializer must not be null"),
                "Property initializer for '" + variableDeclaration.name() + "'"
        );
        if (initializerType == null) {
            return;
        }
        var publishedInitializerType = Objects.requireNonNull(
                initializerType.publishedType(),
                "publishedType must not be null for stable initializer type"
        );
        if (!access.checkAssignmentCompatible(publishedProperty.getType(), publishedInitializerType)) {
            reportInitializerTypeMismatch(
                    access,
                    variableDeclaration,
                    "Property '" + variableDeclaration.name() + "'",
                    publishedProperty.getType(),
                    publishedInitializerType
            );
            return;
        }
        if (hasExplicitDeclaredType(variableDeclaration.type())) {
            return;
        }
        if (!(publishedProperty.getType() instanceof GdVariantType)) {
            throw new IllegalStateException(
                    "Property '" + variableDeclaration.name()
                            + "' is missing an explicit type but published non-Variant metadata"
            );
        }

        reportPropertyTypeHint(access, variableDeclaration, publishedInitializerType);
    }

    protected void visitReturnStatement(
            @NotNull TypeCheckAccess access,
            @NotNull ReturnStatement returnStatement
    ) {
        Objects.requireNonNull(access, "access must not be null");
        Objects.requireNonNull(returnStatement, "returnStatement must not be null");
        var returnSlot = Objects.requireNonNull(
                access.context().currentCallableReturnSlot(),
                "currentCallableReturnSlot must not be null while checking return statements"
        );
        var returnValue = returnStatement.value();
        if (returnValue == null) {
            if (returnSlot instanceof GdVoidType) {
                return;
            }
            if (returnSlot instanceof GdVariantType) {
                return;
            }
            reportBareReturnNotAllowed(access, returnStatement, returnSlot);
            return;
        }
        if (returnSlot instanceof GdVoidType) {
            reportValuedReturnNotAllowed(access, returnStatement);
            return;
        }

        var publishedReturnType = stableExpressionTypeOrNull(
                access.analysisData(),
                returnValue,
                "Return value for " + describeCallableOwner(access)
        );
        if (publishedReturnType == null) {
            return;
        }
        var valueType = Objects.requireNonNull(
                publishedReturnType.publishedType(),
                "publishedType must not be null for stable return value"
        );
        if (access.checkAssignmentCompatible(returnSlot, valueType)) {
            return;
        }
        reportReturnTypeMismatch(access, returnStatement, returnSlot, valueType);
    }

    protected void visitConditionExpression(
            @NotNull TypeCheckAccess access,
            @NotNull Expression condition,
            @NotNull Node owner
    ) {
        Objects.requireNonNull(access, "access must not be null");
        Objects.requireNonNull(condition, "condition must not be null");
        Objects.requireNonNull(owner, "owner must not be null");

        var publishedConditionType = stableExpressionTypeOrNull(
                access.analysisData(),
                condition,
                owner.getClass().getSimpleName() + " condition"
        );
        if (publishedConditionType == null) {
            return;
        }
        // Conditions only require a stable published typed fact here.
        // Godot-compatible truthiness stays a downstream lowering/runtime concern instead of being
        // reinterpreted here as a strict-bool source-language contract.
        Objects.requireNonNull(
                publishedConditionType.publishedType(),
                "publishedType must not be null for stable condition expression"
        );
    }

    protected record TypeCheckAccess(
            @NotNull Path sourcePath,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull DiagnosticManager diagnosticManager,
            @NotNull FrontendAssignmentSemanticSupport.Context assignmentSemanticContext,
            @NotNull TypeCheckVisitContext context
    ) {
        public TypeCheckAccess {
            Objects.requireNonNull(sourcePath, "sourcePath must not be null");
            Objects.requireNonNull(analysisData, "analysisData must not be null");
            Objects.requireNonNull(diagnosticManager, "diagnosticManager must not be null");
            Objects.requireNonNull(assignmentSemanticContext, "assignmentSemanticContext must not be null");
            Objects.requireNonNull(context, "context must not be null");
        }

        /// Type-check intentionally delegates the full typed-boundary matrix to the shared helper.
        /// See `doc/module_impl/frontend/frontend_implicit_conversion_matrix.md` for the single
        /// compatibility source of truth and
        /// `doc/module_impl/frontend/frontend_lowering_(un)pack_implementation.md` for the matching
        /// consumer/materialization contract; do not reintroduce local
        /// `Variant`/`Nil`/scalar special cases here.
        public boolean checkAssignmentCompatible(
                @NotNull GdType slotType,
                @NotNull GdType valueType
        ) {
            return FrontendAssignmentSemanticSupport.checkAssignmentCompatible(
                    assignmentSemanticContext,
                    slotType,
                    valueType
            );
        }
    }

    protected record TypeCheckVisitContext(
            @Nullable ClassDef currentClass,
            @Nullable GdType currentCallableReturnSlot,
            @NotNull ResolveRestriction currentRestriction,
            boolean currentStaticContext,
            int executableBodyDepth,
            @Nullable FrontendPropertyInitializerSupport.PropertyInitializerContext currentPropertyInitializerContext
    ) {
        public TypeCheckVisitContext {
            Objects.requireNonNull(currentRestriction, "currentRestriction must not be null");
        }
    }

    /// Type-check owns the final typed contract only after expr typing has published the root fact.
    /// A missing root entry means the upstream phase boundary itself was not honored, so this path
    /// fails fast instead of silently treating that gap like a recoverable source error.
    private static @NotNull FrontendExpressionType requirePublishedExpressionType(
            @NotNull FrontendAnalysisData analysisData,
            @NotNull Expression expression,
            @NotNull String ownerDescription
    ) {
        Objects.requireNonNull(analysisData, "analysisData must not be null");
        Objects.requireNonNull(expression, "expression must not be null");
        Objects.requireNonNull(ownerDescription, "ownerDescription must not be null");
        var publishedType = analysisData.expressionTypes().get(expression);
        if (publishedType != null) {
            return publishedType;
        }
        throw new IllegalStateException(
                ownerDescription + " expression type has not been published yet"
        );
    }

    /// Only already-stable expression facts own a typed slot contract here. Unstable roots keep
    /// their upstream diagnostic owner and must not be translated into a second type-check error.
    private static @Nullable FrontendExpressionType stableExpressionTypeOrNull(
            @NotNull FrontendAnalysisData analysisData,
            @NotNull Expression expression,
            @NotNull String ownerDescription
    ) {
        var publishedType = requirePublishedExpressionType(analysisData, expression, ownerDescription);
        return switch (publishedType.status()) {
            case RESOLVED, DYNAMIC -> publishedType;
            case BLOCKED, DEFERRED, FAILED, UNSUPPORTED -> null;
        };
    }

    private static boolean hasExplicitDeclaredType(@Nullable TypeRef typeRef) {
        return typeRef != null
                && !typeRef.sourceText().trim().isEmpty()
                && !FrontendDeclaredTypeSupport.isInferredTypeRef(typeRef);
    }

    private static @Nullable ScopeValue publishedOrdinaryLocalSlotOrNull(
            @NotNull TypeCheckAccess access,
            @NotNull VariableDeclaration variableDeclaration
    ) {
        Objects.requireNonNull(access, "access must not be null");
        Objects.requireNonNull(variableDeclaration, "variableDeclaration must not be null");
        var publishedScope = access.analysisData().scopesByAst().get(variableDeclaration);
        if (!(publishedScope instanceof BlockScope blockScope)) {
            return null;
        }
        var localSlot = blockScope.resolveValueHere(variableDeclaration.name().trim());
        return localSlot != null && localSlot.declaration() == variableDeclaration ? localSlot : null;
    }

    private static @Nullable PropertyDef publishedPropertyOrNull(
            @NotNull TypeCheckAccess access,
            @NotNull VariableDeclaration variableDeclaration
    ) {
        Objects.requireNonNull(access, "access must not be null");
        Objects.requireNonNull(variableDeclaration, "variableDeclaration must not be null");
        var propertyInitializerContext = access.context().currentPropertyInitializerContext();
        if (propertyInitializerContext == null) {
            return null;
        }
        return propertyInitializerContext.declaringClass().getProperties().stream()
                .filter(property -> property.getName().equals(variableDeclaration.name().trim()))
                .filter(property -> property.isStatic() == variableDeclaration.isStatic())
                .findFirst()
                .orElse(null);
    }

    private static void reportInitializerTypeMismatch(
            @NotNull TypeCheckAccess access,
            @NotNull VariableDeclaration variableDeclaration,
            @NotNull String subject,
            @NotNull GdType slotType,
            @NotNull GdType valueType
    ) {
        Objects.requireNonNull(access, "access must not be null");
        Objects.requireNonNull(variableDeclaration, "variableDeclaration must not be null");
        Objects.requireNonNull(subject, "subject must not be null");
        Objects.requireNonNull(slotType, "slotType must not be null");
        Objects.requireNonNull(valueType, "valueType must not be null");
        access.diagnosticManager().error(
                TYPE_CHECK_CATEGORY,
                subject + " initializer type '" + valueType.getTypeName()
                        + "' is not assignable to declared slot type '" + slotType.getTypeName() + "'",
                access.sourcePath(),
                FrontendRange.fromAstRange(variableDeclaration.range())
        );
    }

    private static void reportPropertyTypeHint(
            @NotNull TypeCheckAccess access,
            @NotNull VariableDeclaration variableDeclaration,
            @NotNull GdType recommendedType
    ) {
        Objects.requireNonNull(access, "access must not be null");
        Objects.requireNonNull(variableDeclaration, "variableDeclaration must not be null");
        Objects.requireNonNull(recommendedType, "recommendedType must not be null");

        var explicitTypeSuggestion = ": " + recommendedType.getTypeName();
        var typeRef = variableDeclaration.type();
        var message = FrontendDeclaredTypeSupport.isInferredTypeRef(typeRef)
                ? "Property '" + variableDeclaration.name()
                  + "' uses ':=' but MVP does not infer property types. Add an explicit type such as '"
                  + explicitTypeSuggestion + "'."
                : "Property '" + variableDeclaration.name()
                  + "' has no explicit type and MVP does not infer property types. Add an explicit type such as '"
                  + explicitTypeSuggestion + "'.";
        access.diagnosticManager().warning(
                TYPE_HINT_CATEGORY,
                message,
                access.sourcePath(),
                FrontendRange.fromAstRange(variableDeclaration.range())
        );
    }

    private static void reportValuedReturnNotAllowed(
            @NotNull TypeCheckAccess access,
            @NotNull ReturnStatement returnStatement
    ) {
        Objects.requireNonNull(access, "access must not be null");
        Objects.requireNonNull(returnStatement, "returnStatement must not be null");
        access.diagnosticManager().error(
                TYPE_CHECK_CATEGORY,
                describeCallableOwner(access) + " returns 'void' and does not accept 'return expr'",
                access.sourcePath(),
                FrontendRange.fromAstRange(returnStatement.range())
        );
    }

    private static void reportReturnTypeMismatch(
            @NotNull TypeCheckAccess access,
            @NotNull ReturnStatement returnStatement,
            @NotNull GdType slotType,
            @NotNull GdType valueType
    ) {
        Objects.requireNonNull(access, "access must not be null");
        Objects.requireNonNull(returnStatement, "returnStatement must not be null");
        Objects.requireNonNull(slotType, "slotType must not be null");
        Objects.requireNonNull(valueType, "valueType must not be null");
        access.diagnosticManager().error(
                TYPE_CHECK_CATEGORY,
                "Return value type '" + valueType.getTypeName()
                        + "' is not assignable to callable return slot type '" + slotType.getTypeName() + "'",
                access.sourcePath(),
                FrontendRange.fromAstRange(returnStatement.range())
        );
    }

    private static void reportBareReturnNotAllowed(
            @NotNull TypeCheckAccess access,
            @NotNull ReturnStatement returnStatement,
            @NotNull GdType slotType
    ) {
        Objects.requireNonNull(access, "access must not be null");
        Objects.requireNonNull(returnStatement, "returnStatement must not be null");
        Objects.requireNonNull(slotType, "slotType must not be null");
        access.diagnosticManager().error(
                TYPE_CHECK_CATEGORY,
                "Bare 'return' is only allowed for callables returning 'void' or 'Variant', but current return slot type is '"
                        + slotType.getTypeName() + "'",
                access.sourcePath(),
                FrontendRange.fromAstRange(returnStatement.range())
        );
    }

    private static @NotNull String describeCallableOwner(@NotNull TypeCheckAccess access) {
        Objects.requireNonNull(access, "access must not be null");
        var currentClass = access.context().currentClass();
        var currentReturnSlot = Objects.requireNonNull(
                access.context().currentCallableReturnSlot(),
                "currentCallableReturnSlot must not be null while describing callable owner"
        );
        if (currentReturnSlot instanceof GdVoidType) {
            return currentClass == null ? "Callable" : "Callable on class '" + currentClass.getName() + "'";
        }
        return currentClass == null
                ? "Callable"
                : "Callable on class '" + currentClass.getName() + "'";
    }

    private final class AstWalkerTypeCheckVisitor implements ASTNodeHandler {
        private final @NotNull Path sourcePath;
        private final @NotNull FrontendAnalysisData analysisData;
        private final @NotNull FrontendAstSideTable<Scope> scopesByAst;
        private final @NotNull DiagnosticManager diagnosticManager;
        private final @NotNull ASTWalker astWalker;
        private final @NotNull FrontendAssignmentSemanticSupport.Context assignmentSemanticContext;
        private int supportedExecutableBlockDepth;
        private @Nullable ClassDef currentClass;
        private @Nullable GdType currentCallableReturnSlot;
        private @NotNull ResolveRestriction currentRestriction = ResolveRestriction.unrestricted();
        private boolean currentStaticContext;
        private @Nullable FrontendPropertyInitializerSupport.PropertyInitializerContext currentPropertyInitializerContext;

        private AstWalkerTypeCheckVisitor(
                @NotNull Path sourcePath,
                @NotNull ClassRegistry classRegistry,
                @NotNull FrontendAnalysisData analysisData,
                @NotNull FrontendAstSideTable<Scope> scopesByAst,
                @NotNull DiagnosticManager diagnosticManager
        ) {
            this.sourcePath = Objects.requireNonNull(sourcePath, "sourcePath must not be null");
            Objects.requireNonNull(classRegistry, "classRegistry must not be null");
            this.analysisData = Objects.requireNonNull(analysisData, "analysisData must not be null");
            this.scopesByAst = Objects.requireNonNull(scopesByAst, "scopesByAst must not be null");
            this.diagnosticManager = Objects.requireNonNull(diagnosticManager, "diagnosticManager must not be null");
            astWalker = new ASTWalker(this);
            var chainReduction = new FrontendChainReductionFacade(
                    analysisData,
                    scopesByAst,
                    () -> currentRestriction,
                    () -> currentStaticContext,
                    () -> currentPropertyInitializerContext,
                    classRegistry,
                    this::resolvePublishedExpressionType
            );
            assignmentSemanticContext = FrontendAssignmentSemanticSupport.createContext(
                    analysisData.symbolBindings(),
                    scopesByAst,
                    analysisData.moduleSkeleton(),
                    () -> currentRestriction,
                    classRegistry,
                    chainReduction
            );
        }

        private void walk(@NotNull SourceFile sourceFile) {
            astWalker.walk(Objects.requireNonNull(sourceFile, "sourceFile must not be null"));
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleNode(@NotNull Node node) {
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleSourceFile(@NotNull SourceFile sourceFile) {
            walkClassContainer(sourceFile, sourceFile.statements());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleClassDeclaration(@NotNull ClassDeclaration classDeclaration) {
            if (isNotPublished(classDeclaration)) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            walkClassContainer(classDeclaration, classDeclaration.body().statements());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleFunctionDeclaration(
                @NotNull FunctionDeclaration functionDeclaration
        ) {
            if (isNotPublished(functionDeclaration)) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            reportParameterizedGdccConstructorIfNeeded(functionDeclaration);
            walkCallableBody(
                    functionDeclaration,
                    functionDeclaration.body(),
                    resolveFunctionReturnSlot(functionDeclaration),
                    functionDeclaration.isStatic()
                            ? ResolveRestriction.staticContext()
                            : ResolveRestriction.instanceContext(),
                    functionDeclaration.isStatic()
            );
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleConstructorDeclaration(
                @NotNull ConstructorDeclaration constructorDeclaration
        ) {
            if (isNotPublished(constructorDeclaration)) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            reportParameterizedGdccConstructorIfNeeded(constructorDeclaration);
            walkCallableBody(
                    constructorDeclaration,
                    constructorDeclaration.body(),
                    GdVoidType.VOID,
                    ResolveRestriction.instanceContext(),
                    false
            );
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        /// Parameterized GDCC `_init(...)` definitions are a semantic contract violation, not just a
        /// compile-mode lowering gap. Report them on the declaration itself so they cannot fail
        /// silently when no constructor call site is analyzed yet.
        private void reportParameterizedGdccConstructorIfNeeded(@NotNull ConstructorDeclaration constructorDeclaration) {
            if (constructorDeclaration.parameters().isEmpty() || currentClass == null) {
                return;
            }
            diagnosticManager.error(
                    TYPE_CHECK_CATEGORY,
                    parameterizedGdccConstructorUnsupportedMessage(currentClass),
                    sourcePath,
                    FrontendRange.fromAstRange(constructorDeclaration.range())
            );
        }

        /// Frontend source still models ordinary `_init` declarations as functions in the common
        /// path, so the semantic guard must live here as well instead of relying only on the legacy
        /// `ConstructorDeclaration` node kind.
        private void reportParameterizedGdccConstructorIfNeeded(@NotNull FunctionDeclaration functionDeclaration) {
            if (!functionDeclaration.name().equals("_init")
                    || functionDeclaration.isStatic()
                    || functionDeclaration.parameters().isEmpty()
                    || currentClass == null) {
                return;
            }
            diagnosticManager.error(
                    TYPE_CHECK_CATEGORY,
                    parameterizedGdccConstructorUnsupportedMessage(currentClass),
                    sourcePath,
                    FrontendRange.fromAstRange(functionDeclaration.range())
            );
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleBlock(@NotNull Block block) {
            if (supportedExecutableBlockDepth <= 0 || isNotPublished(block)) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            walkStatements(block.statements());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleVariableDeclaration(
                @NotNull VariableDeclaration variableDeclaration
        ) {
            if (supportedExecutableBlockDepth > 0) {
                if (isOrdinaryLocalInitializer(variableDeclaration)) {
                    visitOrdinaryLocalInitializer(callbackAccess(), variableDeclaration);
                }
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            if (!FrontendPropertyInitializerSupport.isSupportedPropertyInitializer(scopesByAst, variableDeclaration)) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            walkPropertyInitializer(variableDeclaration);
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleReturnStatement(@NotNull ReturnStatement returnStatement) {
            if (supportedExecutableBlockDepth <= 0) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            visitReturnStatement(callbackAccess(), returnStatement);
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleAssertStatement(@NotNull AssertStatement assertStatement) {
            if (supportedExecutableBlockDepth <= 0) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            visitConditionExpression(callbackAccess(), assertStatement.condition(), assertStatement);
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleIfStatement(@NotNull IfStatement ifStatement) {
            if (supportedExecutableBlockDepth <= 0 || isNotPublished(ifStatement)) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            visitConditionExpression(callbackAccess(), ifStatement.condition(), ifStatement);
            walkSupportedExecutableBlock(ifStatement.body());
            for (var elifClause : ifStatement.elifClauses()) {
                astWalker.walk(elifClause);
            }
            if (ifStatement.elseBody() != null) {
                walkSupportedExecutableBlock(ifStatement.elseBody());
            }
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleElifClause(@NotNull ElifClause elifClause) {
            if (supportedExecutableBlockDepth <= 0 || isNotPublished(elifClause)) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            visitConditionExpression(callbackAccess(), elifClause.condition(), elifClause);
            walkSupportedExecutableBlock(elifClause.body());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleWhileStatement(@NotNull WhileStatement whileStatement) {
            if (supportedExecutableBlockDepth <= 0 || isNotPublished(whileStatement)) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            visitConditionExpression(callbackAccess(), whileStatement.condition(), whileStatement);
            walkSupportedExecutableBlock(whileStatement.body());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleLambdaExpression(@NotNull LambdaExpression lambdaExpression) {
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleMatchStatement(@NotNull MatchStatement matchStatement) {
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        private void walkClassContainer(@NotNull Node classOwner, @NotNull List<Statement> statements) {
            var previousClass = currentClass;
            var previousRestriction = currentRestriction;
            var previousStaticContext = currentStaticContext;
            var previousCallableReturnSlot = currentCallableReturnSlot;
            var previousPropertyInitializerContext = currentPropertyInitializerContext;
            currentClass = requireClassScope(classOwner).getCurrentClass();
            currentRestriction = ResolveRestriction.unrestricted();
            currentStaticContext = false;
            currentCallableReturnSlot = null;
            currentPropertyInitializerContext = null;
            try {
                walkNonExecutableContainerStatements(statements);
            } finally {
                currentPropertyInitializerContext = previousPropertyInitializerContext;
                currentCallableReturnSlot = previousCallableReturnSlot;
                currentStaticContext = previousStaticContext;
                currentRestriction = previousRestriction;
                currentClass = previousClass;
            }
        }

        private void walkCallableBody(
                @NotNull Node callableOwner,
                @Nullable Block body,
                @NotNull GdType returnSlot,
                @NotNull ResolveRestriction restriction,
                boolean staticContext
        ) {
            if (isNotPublished(callableOwner) || isNotPublished(body)) {
                return;
            }
            var previousRestriction = currentRestriction;
            var previousStaticContext = currentStaticContext;
            var previousCallableReturnSlot = currentCallableReturnSlot;
            var previousPropertyInitializerContext = currentPropertyInitializerContext;
            currentRestriction = Objects.requireNonNull(restriction, "restriction must not be null");
            currentStaticContext = staticContext;
            currentCallableReturnSlot = Objects.requireNonNull(returnSlot, "returnSlot must not be null");
            currentPropertyInitializerContext = null;
            try {
                walkSupportedExecutableBlock(body);
            } finally {
                currentPropertyInitializerContext = previousPropertyInitializerContext;
                currentCallableReturnSlot = previousCallableReturnSlot;
                currentStaticContext = previousStaticContext;
                currentRestriction = previousRestriction;
            }
        }

        private void walkPropertyInitializer(@NotNull VariableDeclaration variableDeclaration) {
            var previousRestriction = currentRestriction;
            var previousStaticContext = currentStaticContext;
            var previousCallableReturnSlot = currentCallableReturnSlot;
            var previousPropertyInitializerContext = currentPropertyInitializerContext;
            currentRestriction = FrontendPropertyInitializerSupport.restrictionFor(variableDeclaration);
            currentStaticContext = variableDeclaration.isStatic();
            currentCallableReturnSlot = null;
            currentPropertyInitializerContext = FrontendPropertyInitializerSupport.contextFor(
                    scopesByAst,
                    variableDeclaration
            );
            try {
                visitPropertyInitializer(callbackAccess(), variableDeclaration);
            } finally {
                currentPropertyInitializerContext = previousPropertyInitializerContext;
                currentCallableReturnSlot = previousCallableReturnSlot;
                currentStaticContext = previousStaticContext;
                currentRestriction = previousRestriction;
            }
        }

        private void walkStatements(@NotNull List<Statement> statements) {
            for (var statement : statements) {
                astWalker.walk(statement);
            }
        }

        private void walkNonExecutableContainerStatements(@NotNull List<Statement> statements) {
            var previousDepth = supportedExecutableBlockDepth;
            supportedExecutableBlockDepth = 0;
            try {
                walkStatements(statements);
            } finally {
                supportedExecutableBlockDepth = previousDepth;
            }
        }

        private void walkSupportedExecutableBlock(@Nullable Block block) {
            if (isNotPublished(block)) {
                return;
            }
            supportedExecutableBlockDepth++;
            try {
                astWalker.walk(block);
            } finally {
                supportedExecutableBlockDepth--;
            }
        }

        private boolean isOrdinaryLocalInitializer(@NotNull VariableDeclaration variableDeclaration) {
            return variableDeclaration.kind() == DeclarationKind.VAR
                    && variableDeclaration.value() != null
                    && scopesByAst.get(variableDeclaration) instanceof BlockScope;
        }

        private @NotNull GdType resolveFunctionReturnSlot(@NotNull FunctionDeclaration functionDeclaration) {
            Objects.requireNonNull(functionDeclaration, "functionDeclaration must not be null");
            if (functionDeclaration.name().equals("_init")) {
                return GdVoidType.VOID;
            }
            var currentClassDef = Objects.requireNonNull(
                    currentClass,
                    "currentClass must not be null while resolving function return slot"
            );
            return currentClassDef.getFunctions().stream()
                    .filter(function -> function.getName().equals(functionDeclaration.name()))
                    .filter(function -> function.isStatic() == functionDeclaration.isStatic())
                    .filter(function -> function.getParameterCount() == functionDeclaration.parameters().size())
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "Function skeleton has not been published for: "
                                    + currentClassDef.getName() + "." + functionDeclaration.name()
                    ))
                    .getReturnType();
        }

        private @NotNull ClassScope requireClassScope(@NotNull Node classOwner) {
            var publishedScope = scopesByAst.get(Objects.requireNonNull(classOwner, "classOwner must not be null"));
            if (publishedScope instanceof ClassScope classScope) {
                return classScope;
            }
            throw new IllegalStateException("Class scope has not been published for node: " + classOwner.getClass().getSimpleName());
        }

        private boolean isNotPublished(@Nullable Node node) {
            return node == null || !scopesByAst.containsKey(node);
        }

        private @NotNull TypeCheckAccess callbackAccess() {
            return new TypeCheckAccess(
                    sourcePath,
                    analysisData,
                    diagnosticManager,
                    assignmentSemanticContext,
                    snapshotContext()
            );
        }

        private @NotNull TypeCheckVisitContext snapshotContext() {
            return new TypeCheckVisitContext(
                    currentClass,
                    currentCallableReturnSlot,
                    currentRestriction,
                    currentStaticContext,
                    supportedExecutableBlockDepth,
                    currentPropertyInitializerContext
            );
        }

        private @NotNull FrontendChainReductionHelper.ExpressionTypeResult resolvePublishedExpressionType(
                @NotNull Expression expression,
                boolean finalizeWindow
        ) {
            Objects.requireNonNull(expression, "expression must not be null");
            var publishedType = analysisData.expressionTypes().get(expression);
            if (publishedType != null) {
                return FrontendChainReductionHelper.ExpressionTypeResult.fromPublished(publishedType);
            }
            return FrontendChainReductionHelper.ExpressionTypeResult.deferred(
                    "Type-check dependency lookup could not find a published expression type for "
                            + expression.getClass().getSimpleName()
            );
        }
    }
}
