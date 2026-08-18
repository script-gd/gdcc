package gd.script.gdcc.frontend.sema.analyzer;

import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.diagnostic.FrontendRange;
import gd.script.gdcc.frontend.scope.BlockScope;
import gd.script.gdcc.frontend.scope.ClassScope;
import gd.script.gdcc.frontend.sema.FrontendAnalysisData;
import gd.script.gdcc.frontend.sema.FrontendAstSideTable;
import gd.script.gdcc.frontend.sema.FrontendContainerLiteralPlan;
import gd.script.gdcc.frontend.sema.FrontendDeclaredTypeSupport;
import gd.script.gdcc.frontend.sema.FrontendExpressionType;
import gd.script.gdcc.frontend.sema.FrontendExpressionTypeStatus;
import gd.script.gdcc.frontend.sema.FrontendForIterationPlan;
import gd.script.gdcc.frontend.sema.FrontendForLoopSupport;
import gd.script.gdcc.frontend.sema.FrontendIterableSemantics;
import gd.script.gdcc.frontend.sema.analyzer.support.FrontendCallableReturnTypeSupport;
import gd.script.gdcc.frontend.sema.analyzer.support.FrontendPropertyInitializerSupport;
import gd.script.gdcc.frontend.sema.analyzer.support.FrontendAssignmentSemanticSupport;
import gd.script.gdcc.frontend.sema.analyzer.support.FrontendChainReductionFacade;
import gd.script.gdcc.frontend.sema.analyzer.support.FrontendChainReductionHelper;
import gd.script.gdcc.frontend.sema.analyzer.support.FrontendVariantBoundaryCompatibility;
import gd.script.gdcc.scope.ClassDef;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.scope.PropertyDef;
import gd.script.gdcc.scope.ResolveRestriction;
import gd.script.gdcc.scope.Scope;
import gd.script.gdcc.scope.ScopeValue;
import gd.script.gdcc.type.GdCompilerType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.type.GdVariantType;
import gd.script.gdcc.type.GdVoidType;
import gd.script.gdcc.util.type.ExplicitCastSupport;
import dev.superice.gdparser.frontend.ast.ASTNodeHandler;
import dev.superice.gdparser.frontend.ast.ASTWalker;
import dev.superice.gdparser.frontend.ast.ArrayExpression;
import dev.superice.gdparser.frontend.ast.AssertStatement;
import dev.superice.gdparser.frontend.ast.Block;
import dev.superice.gdparser.frontend.ast.CastExpression;
import dev.superice.gdparser.frontend.ast.ClassDeclaration;
import dev.superice.gdparser.frontend.ast.ConstructorDeclaration;
import dev.superice.gdparser.frontend.ast.DeclarationKind;
import dev.superice.gdparser.frontend.ast.DictionaryExpression;
import dev.superice.gdparser.frontend.ast.ElifClause;
import dev.superice.gdparser.frontend.ast.Expression;
import dev.superice.gdparser.frontend.ast.ExpressionStatement;
import dev.superice.gdparser.frontend.ast.ForStatement;
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
    /// Godot `range(...)` accepts one to three positional arguments (stop / start+stop / start+stop+step).
    private static final int MAX_RANGE_ARGUMENT_COUNT = 3;

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
        if (variableDeclaration.value() != null) {
            visitNestedCastExpressions(access, variableDeclaration.value());
        }
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

        var propertyValue = Objects.requireNonNull(
                variableDeclaration.value(),
                "property initializer must not be null"
        );
        visitNestedCastExpressions(access, propertyValue);

        var publishedProperty = publishedPropertyOrNull(access, variableDeclaration);
        if (publishedProperty == null) {
            return;
        }
        var initializerType = stableExpressionTypeOrNull(
                access.analysisData(),
                propertyValue,
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
        if (returnValue != null) {
            visitNestedCastExpressions(access, returnValue);
        }
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

        visitNestedCastExpressions(access, condition);

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
        var conditionType = Objects.requireNonNull(
                publishedConditionType.publishedType(),
                "publishedType must not be null for stable condition expression"
        );
        if (conditionType instanceof GdCompilerType compilerOnlyType) {
            throw new IllegalStateException(
                    "compiler-only type leaked into frontend condition fact: "
                            + compilerOnlyType.getTypeName()
            );
        }
    }

    /// Walks one expression tree and type-checks nested casts and published container-literal plans.
    ///
    /// Static hard-pair validity uses `ExplicitCastSupport`; unstable value operands keep
    /// their upstream diagnostic owner and are skipped here. Container-literal REJECT / duplicate-key
    /// issues are consumed only from the published plan (no second key reduction).
    protected void visitNestedCastExpressions(
            @NotNull TypeCheckAccess access,
            @Nullable Expression expression
    ) {
        Objects.requireNonNull(access, "access must not be null");
        if (expression == null) {
            return;
        }
        if (expression instanceof CastExpression castExpression) {
            visitCastExpression(access, castExpression);
        }
        if (expression instanceof ArrayExpression || expression instanceof DictionaryExpression) {
            visitContainerLiteralPlan(access, expression);
        }
        for (var child : expression.getChildren()) {
            if (child instanceof Expression childExpression) {
                visitNestedCastExpressions(access, childExpression);
            }
        }
    }

    /// Emits element/key/value REJECT and frozen duplicate-key diagnostics for one published plan.
    protected void visitContainerLiteralPlan(
            @NotNull TypeCheckAccess access,
            @NotNull Expression literalExpression
    ) {
        Objects.requireNonNull(access, "access must not be null");
        Objects.requireNonNull(literalExpression, "literalExpression must not be null");
        var plan = access.analysisData().containerLiteralPlans().get(literalExpression);
        if (plan == null) {
            // Unstable / openEnded / ABI-failed roots publish no plan; upstream owns diagnostics.
            return;
        }
        var resultType = plan.resultType();
        for (var operand : plan.operands()) {
            if (operand.decision() != FrontendVariantBoundaryCompatibility.Decision.REJECT) {
                continue;
            }
            // Upstream FAILED/BLOCKED children do not appear in plans; only stable REJECT boundaries.
            var message = switch (operand.role()) {
                case ARRAY_ELEMENT -> "Cannot have an element of type \""
                        + operand.sourceType().getTypeName()
                        + "\" in an array of type \""
                        + resultType.getTypeName()
                        + "\".";
                case DICTIONARY_KEY -> "Cannot have a key of type \""
                        + operand.sourceType().getTypeName()
                        + "\" in a dictionary of type \""
                        + resultType.getTypeName()
                        + "\".";
                case DICTIONARY_VALUE -> "Cannot have a value of type \""
                        + operand.sourceType().getTypeName()
                        + "\" in a dictionary of type \""
                        + resultType.getTypeName()
                        + "\".";
            };
            var anchor = operandAnchorExpression(literalExpression, operand);
            access.diagnosticManager().error(
                    TYPE_CHECK_CATEGORY,
                    message,
                    access.sourcePath(),
                    FrontendRange.fromAstRange(anchor.range())
            );
        }
        for (var issue : plan.duplicateKeyIssues()) {
            var anchor = dictionaryEntryKeyExpression(literalExpression, issue.duplicateEntryIndex());
            access.diagnosticManager().error(
                    TYPE_CHECK_CATEGORY,
                    "Key " + issue.keyDisplay()
                            + " was already used in this dictionary; first occurrence is entry #"
                            + (issue.firstEntryIndex() + 1),
                    access.sourcePath(),
                    FrontendRange.fromAstRange(anchor.range())
            );
        }
    }

    private static @NotNull Expression operandAnchorExpression(
            @NotNull Expression literalExpression,
            @NotNull FrontendContainerLiteralPlan.OperandPlan operand
    ) {
        if (literalExpression instanceof ArrayExpression arrayExpression) {
            return arrayExpression.elements().get(operand.sourceIndex());
        }
        if (literalExpression instanceof DictionaryExpression dictionaryExpression) {
            var entry = dictionaryExpression.entries().get(operand.sourceIndex());
            return switch (operand.role()) {
                case DICTIONARY_KEY -> entry.key();
                case DICTIONARY_VALUE -> entry.value();
                case ARRAY_ELEMENT -> throw new IllegalStateException(
                        "ARRAY_ELEMENT role cannot appear on a dictionary literal plan"
                );
            };
        }
        throw new IllegalStateException(
                "Container literal plan attached to non-container expression: "
                        + literalExpression.getClass().getSimpleName()
        );
    }

    private static @NotNull Expression dictionaryEntryKeyExpression(
            @NotNull Expression literalExpression,
            int entryIndex
    ) {
        if (!(literalExpression instanceof DictionaryExpression dictionaryExpression)) {
            throw new IllegalStateException("Duplicate key issues require a DictionaryExpression");
        }
        return dictionaryExpression.entries().get(entryIndex).key();
    }

    /// Validates one published `value as T` against the shared explicit-cast classifier.
    ///
    /// Emits `sema.type_check` only when both source and target facts are hard/stable and
    /// `ExplicitCastSupport.checkAllowed` rejects the pair. Runtime-open sources are not
    /// rejected here (`sema.unsafe_cast` is owned by expression publication).
    protected void visitCastExpression(
            @NotNull TypeCheckAccess access,
            @NotNull CastExpression castExpression
    ) {
        Objects.requireNonNull(access, "access must not be null");
        Objects.requireNonNull(castExpression, "castExpression must not be null");

        var publishedCastType = stableExpressionTypeOrNull(
                access.analysisData(),
                castExpression,
                "CastExpression"
        );
        if (publishedCastType == null) {
            return;
        }
        var targetType = Objects.requireNonNull(
                publishedCastType.publishedType(),
                "publishedType must not be null for stable cast expression"
        );
        if (targetType instanceof GdCompilerType compilerOnlyType) {
            throw new IllegalStateException(
                    "compiler-only type leaked into frontend cast result fact: "
                            + compilerOnlyType.getTypeName()
            );
        }

        var publishedValueType = stableExpressionTypeOrNull(
                access.analysisData(),
                castExpression.value(),
                "CastExpression value"
        );
        if (publishedValueType == null) {
            return;
        }
        // DYNAMIC / Variant sources stay runtime-open; unsafe warning is published elsewhere.
        if (publishedValueType.status() == FrontendExpressionTypeStatus.DYNAMIC
                || publishedValueType.publishedType() instanceof GdVariantType) {
            return;
        }
        var sourceType = Objects.requireNonNull(
                publishedValueType.publishedType(),
                "publishedType must not be null for stable cast value"
        );
        if (sourceType instanceof GdCompilerType compilerOnlyType) {
            throw new IllegalStateException(
                    "compiler-only type leaked into frontend cast source fact: "
                            + compilerOnlyType.getTypeName()
            );
        }
        if (ExplicitCastSupport.checkAllowed(
                access.assignmentSemanticContext().classRegistry(),
                sourceType,
                targetType
        )) {
            return;
        }
        access.diagnosticManager().error(
                TYPE_CHECK_CATEGORY,
                "Invalid cast. Cannot convert from \"" + sourceType.getTypeName()
                        + "\" to \"" + targetType.getTypeName() + "\".",
                access.sourcePath(),
                FrontendRange.fromAstRange(castExpression.range())
        );
    }

    /// Type-checks the header of one `for iterator[: Type] in expr` statement against the already
    /// published `FrontendForIterationPlan`.
    ///
    /// The route decides the header contract:
    /// - `RANGE_CALL` validates the bare `range(...)` argument arity (1..3) and that every argument
    ///   enters the `int` slot; the callee/call root are never treated as an ordinary call here.
    /// - `INT_SHORTHAND` validates that the single stop operand enters the `int` slot.
    /// - every other route (generic Variant today, reserved specialized routes later) classifies a
    ///   stable iterable type and reports hard types that cannot implement Godot iteration semantics.
    ///
    /// Independently of the route, an explicit iterator type must be able to receive the semantic
    /// element type via the shared typed-boundary matrix. Body traversal is owned by the caller so it
    /// always happens regardless of route classification.
    protected void visitForHeader(
            @NotNull TypeCheckAccess access,
            @NotNull ForStatement forStatement
    ) {
        Objects.requireNonNull(access, "access must not be null");
        Objects.requireNonNull(forStatement, "forStatement must not be null");
        visitNestedCastExpressions(access, forStatement.iterable());
        var plan = requirePublishedForIterationPlan(access.analysisData(), forStatement);
        switch (plan.route()) {
            case RANGE_CALL -> visitRangeCallHeader(access, forStatement, plan);
            case INT_SHORTHAND -> visitIntShorthandHeader(access, plan);
            default -> visitOrdinaryIterableHeader(access, forStatement);
        }
        visitExplicitIteratorTypeConversion(access, forStatement, plan);
    }

    /// Validates a bare `range(...)` header: argument arity must be 1..3 and each present argument
    /// must enter the `int` slot. A dynamic argument that already carries a stable non-`int` type is
    /// reported at its own argument position; a literal/dynamic `step == 0` is a valid empty range and
    /// is never rejected here.
    private static void visitRangeCallHeader(
            @NotNull TypeCheckAccess access,
            @NotNull ForStatement forStatement,
            @NotNull FrontendForIterationPlan plan
    ) {
        var arguments = plan.sourceOperands();
        if (arguments.isEmpty() || arguments.size() > MAX_RANGE_ARGUMENT_COUNT) {
            reportRangeArityMismatch(access, forStatement, arguments.size());
        }
        for (var index = 0; index < arguments.size(); index++) {
            checkIntSlotOperand(access, arguments.get(index), "range(...) argument #" + (index + 1));
        }
    }

    /// Validates the integer shorthand `for i in n`: the single stop operand must enter the `int`
    /// slot. The implicit `0` start and `1` step are lowering constants, not source operands, so they
    /// are not type-checked here.
    private static void visitIntShorthandHeader(
            @NotNull TypeCheckAccess access,
            @NotNull FrontendForIterationPlan plan
    ) {
        checkIntSlotOperand(access, plan.sourceOperands().getFirst(), "for-in integer shorthand iterable");
    }

    /// Ordinary iterable header contract: dynamic types remain runtime-open, while a stable hard type
    /// that cannot implement Godot's iteration protocol is rejected without blocking body traversal.
    private static void visitOrdinaryIterableHeader(
            @NotNull TypeCheckAccess access,
            @NotNull ForStatement forStatement
    ) {
        var iterableType = stableNonCompilerExpressionTypeOrNull(
                access,
                forStatement.iterable(),
                "for-in iterable"
        );
        if (iterableType == null) {
            return;
        }
        var classification = FrontendForLoopSupport.classifyIterableSemantics(iterableType);
        if (classification instanceof FrontendIterableSemantics.NonIterable(var nonIterableType)) {
            reportNonIterableType(access, forStatement, nonIterableType);
        }
    }

    /// An explicit `for i: Type in expr` iterator type must receive the semantic element type.
    /// Inferred iterators mirror that type and therefore need no compatibility check.
    private static void visitExplicitIteratorTypeConversion(
            @NotNull TypeCheckAccess access,
            @NotNull ForStatement forStatement,
            @NotNull FrontendForIterationPlan plan
    ) {
        if (plan.declaredIteratorTypeRef() == null) {
            return;
        }
        if (access.checkAssignmentCompatible(plan.exposedIteratorType(), plan.semanticElementType())) {
            return;
        }
        reportIteratorTypeMismatch(access, forStatement, plan);
    }

    /// Checks that one source operand enters the `int` slot, reporting a mismatch anchored at the
    /// operand itself. Unstable operand facts keep their upstream diagnostic owner and are skipped.
    private static void checkIntSlotOperand(
            @NotNull TypeCheckAccess access,
            @NotNull Expression operand,
            @NotNull String subject
    ) {
        var operandType = stableNonCompilerExpressionTypeOrNull(access, operand, subject);
        if (operandType == null) {
            return;
        }
        if (access.checkAssignmentCompatible(GdIntType.INT, operandType)) {
            return;
        }
        access.diagnosticManager().error(
                TYPE_CHECK_CATEGORY,
                subject + " type '" + operandType.getTypeName() + "' is not assignable to 'int'",
                access.sourcePath(),
                FrontendRange.fromAstRange(operand.range())
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

    /// The iteration plan is the single route/element-type truth and must already be published by the
    /// for-iteration resolution owner before type-check runs. A missing plan means the upstream phase
    /// boundary was not honored, so this path fails fast instead of silently skipping the header.
    private static @NotNull FrontendForIterationPlan requirePublishedForIterationPlan(
            @NotNull FrontendAnalysisData analysisData,
            @NotNull ForStatement forStatement
    ) {
        Objects.requireNonNull(analysisData, "analysisData must not be null");
        Objects.requireNonNull(forStatement, "forStatement must not be null");
        var plan = analysisData.forIterationPlans().get(forStatement);
        if (plan != null) {
            return plan;
        }
        throw new IllegalStateException("for-in iteration plan has not been published for ForStatement");
    }

    /// Returns the stable published type of one expression, or null when the fact is still unstable so
    /// its upstream diagnostic owner stays authoritative. Mirrors the condition contract by rejecting
    /// any compiler-only type that must never leak into a source-facing for-in fact.
    private static @Nullable GdType stableNonCompilerExpressionTypeOrNull(
            @NotNull TypeCheckAccess access,
            @NotNull Expression expression,
            @NotNull String ownerDescription
    ) {
        var publishedType = stableExpressionTypeOrNull(access.analysisData(), expression, ownerDescription);
        if (publishedType == null) {
            return null;
        }
        var type = Objects.requireNonNull(
                publishedType.publishedType(),
                "publishedType must not be null for stable expression"
        );
        if (type instanceof GdCompilerType compilerOnlyType) {
            throw new IllegalStateException(
                    "compiler-only type leaked into frontend for-in fact: "
                            + compilerOnlyType.getTypeName()
            );
        }
        return type;
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

    private static void reportRangeArityMismatch(
            @NotNull TypeCheckAccess access,
            @NotNull ForStatement forStatement,
            int actualArgumentCount
    ) {
        Objects.requireNonNull(access, "access must not be null");
        Objects.requireNonNull(forStatement, "forStatement must not be null");
        access.diagnosticManager().error(
                TYPE_CHECK_CATEGORY,
                "range(...) expects between 1 and " + MAX_RANGE_ARGUMENT_COUNT
                        + " arguments but got " + actualArgumentCount,
                access.sourcePath(),
                FrontendRange.fromAstRange(forStatement.iterable().range())
        );
    }

    private static void reportIteratorTypeMismatch(
            @NotNull TypeCheckAccess access,
            @NotNull ForStatement forStatement,
            @NotNull FrontendForIterationPlan plan
    ) {
        Objects.requireNonNull(access, "access must not be null");
        Objects.requireNonNull(forStatement, "forStatement must not be null");
        Objects.requireNonNull(plan, "plan must not be null");
        access.diagnosticManager().error(
                TYPE_CHECK_CATEGORY,
                "for-in iterator declared type '" + plan.exposedIteratorType().getTypeName()
                        + "' cannot receive iterated element type '" + plan.semanticElementType().getTypeName() + "'",
                access.sourcePath(),
                FrontendRange.fromAstRange(
                        Objects.requireNonNull(forStatement.iteratorType(), "iteratorType must not be null").range()
                )
        );
    }

    private static void reportNonIterableType(
            @NotNull TypeCheckAccess access,
            @NotNull ForStatement forStatement,
            @NotNull GdType iterableType
    ) {
        access.diagnosticManager().error(
                TYPE_CHECK_CATEGORY,
                "Unable to iterate on value of type \"" + iterableType.getTypeName() + "\"",
                access.sourcePath(),
                FrontendRange.fromAstRange(forStatement.iterable().range())
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
            scanNestedLambdaBodies(variableDeclaration.value());
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
            scanNestedLambdaBodies(returnStatement.value());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleAssertStatement(@NotNull AssertStatement assertStatement) {
            if (supportedExecutableBlockDepth <= 0) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            var access = callbackAccess();
            visitConditionExpression(access, assertStatement.condition(), assertStatement);
            if (assertStatement.message() != null) {
                visitNestedCastExpressions(access, assertStatement.message());
            }
            scanNestedLambdaBodies(assertStatement.condition());
            scanNestedLambdaBodies(assertStatement.message());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleExpressionStatement(
                @NotNull ExpressionStatement expressionStatement
        ) {
            if (supportedExecutableBlockDepth <= 0) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            visitNestedCastExpressions(callbackAccess(), expressionStatement.expression());
            scanNestedLambdaBodies(expressionStatement.expression());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleIfStatement(@NotNull IfStatement ifStatement) {
            if (supportedExecutableBlockDepth <= 0 || isNotPublished(ifStatement)) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            visitConditionExpression(callbackAccess(), ifStatement.condition(), ifStatement);
            scanNestedLambdaBodies(ifStatement.condition());
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
            scanNestedLambdaBodies(elifClause.condition());
            walkSupportedExecutableBlock(elifClause.body());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleWhileStatement(@NotNull WhileStatement whileStatement) {
            if (supportedExecutableBlockDepth <= 0 || isNotPublished(whileStatement)) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            visitConditionExpression(callbackAccess(), whileStatement.condition(), whileStatement);
            scanNestedLambdaBodies(whileStatement.condition());
            walkSupportedExecutableBlock(whileStatement.body());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        /// `for` shares the while-style executable-depth and published-fact guard. The header contract
        /// is route-aware (via the published iteration plan) while the body is always traversed, so
        /// route classification can only affect header/iterator diagnostics and never re-defers the
        /// for body into an unsupported boundary.
        @Override
        public @NotNull FrontendASTTraversalDirective handleForStatement(@NotNull ForStatement forStatement) {
            if (supportedExecutableBlockDepth <= 0 || isNotPublished(forStatement)) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            visitForHeader(callbackAccess(), forStatement);
            scanNestedLambdaBodies(forStatement.iterable());
            walkSupportedExecutableBlock(forStatement.body());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        /// Recorded lambdas re-enter here from `scanNestedLambdaBodies`: the body already resolved
        /// through its nested suite, so type-check walks it as an independent callable island that
        /// inherits the enclosing callable's restriction/static context. The return
        /// slot is the declared return type published on the `FrontendLambdaPlan`, so a
        /// mismatched or bare `return` gets the ordinary `sema.type_check` diagnostics instead of
        /// drifting against the synthesized shell's return boundary downstream. Unrecorded lambdas
        /// (property initializer / parameter default / skipped subtrees) publish no plan and no
        /// body facts, so they stay fail-closed.
        @Override
        public @NotNull FrontendASTTraversalDirective handleLambdaExpression(@NotNull LambdaExpression lambdaExpression) {
            if (isNotPublished(lambdaExpression)
                    || isNotPublished(lambdaExpression.body())
                    || !analysisData.lambdaPlans().containsKey(lambdaExpression)) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            walkCallableBody(
                    lambdaExpression,
                    lambdaExpression.body(),
                    Objects.requireNonNull(analysisData.lambdaPlans().get(lambdaExpression)).returnType(),
                    currentRestriction,
                    currentStaticContext
            );
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleMatchStatement(@NotNull MatchStatement matchStatement) {
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        /// Lambda bodies are separate suite-entry roots that the statement walk never reaches, so
        /// nested lambdas must be re-entered explicitly from the expression trees carrying them —
        /// mirroring the loop-control analyzer's callable-boundary scan. The scan stops at each
        /// lambda node; `handleLambdaExpression` decides whether its body has published facts.
        private void scanNestedLambdaBodies(@Nullable Node node) {
            if (node == null) {
                return;
            }
            if (node instanceof LambdaExpression lambdaExpression) {
                astWalker.walk(lambdaExpression);
                return;
            }
            for (var child : node.getChildren()) {
                if (child instanceof LambdaExpression lambdaExpression) {
                    astWalker.walk(lambdaExpression);
                    continue;
                }
                scanNestedLambdaBodies(child);
            }
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
            return FrontendCallableReturnTypeSupport.resolveFunctionReturnSlot(functionDeclaration, currentClass);
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
