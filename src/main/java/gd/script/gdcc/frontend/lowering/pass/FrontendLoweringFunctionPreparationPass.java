package gd.script.gdcc.frontend.lowering.pass;

import gd.script.gdcc.frontend.lowering.FrontendLoweringContext;
import gd.script.gdcc.frontend.lowering.FrontendLoweringPass;
import gd.script.gdcc.frontend.lowering.FunctionLoweringContext;
import gd.script.gdcc.frontend.scope.CallableScope;
import gd.script.gdcc.frontend.sema.FrontendAnalysisData;
import gd.script.gdcc.frontend.sema.FrontendLambdaPlan;
import gd.script.gdcc.frontend.sema.FrontendSourceClassRelation;
import gd.script.gdcc.frontend.sema.analyzer.support.FrontendPropertyInitializerSupport;
import gd.script.gdcc.lir.LirCaptureDef;
import gd.script.gdcc.lir.LirClassDef;
import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.lir.LirModule;
import gd.script.gdcc.lir.LirParameterDef;
import gd.script.gdcc.lir.LirPropertyDef;
import gd.script.gdcc.scope.ScopeValueKind;
import gd.script.gdcc.type.GdObjectType;
import dev.superice.gdparser.frontend.ast.ClassDeclaration;
import dev.superice.gdparser.frontend.ast.ConstructorDeclaration;
import dev.superice.gdparser.frontend.ast.DeclarationKind;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.LambdaExpression;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.Statement;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;

/// Collects the first function-shaped lowering units after semantic analysis and class skeleton
/// publication complete.
///
/// The pass keeps the current LIR in skeleton/shell form:
/// - executable callables reuse their published `LirFunctionDef`
/// - property initializers get hidden synthetic helper scaffolds
/// - recorded lambdas get hidden synthesized `_lambda_<k>` shells from their published
///   `FrontendLambdaPlan`
/// - accepted parameter defaults get hidden synthetic `_default_<func>$<param>` /
///   `_default_s_<func>$<param>` shells referenced by `LirParameterDef.defaultValueFunc`
/// - no basic blocks or instructions are emitted yet; later CFG/body passes materialize the
///   executable callable, property-init, lambda and parameter-default bodies into these shells
public final class FrontendLoweringFunctionPreparationPass implements FrontendLoweringPass {
    /// Compiler-owned helper namespace. Source members that start with this prefix must already have
    /// been rejected by skeleton-driven skipped-subtree recovery before preparation runs.
    private static final String PROPERTY_INIT_PREFIX = "_field_init_";

    @Override
    public void run(@NotNull FrontendLoweringContext context) {
        var analysisData = context.requireAnalysisData();
        var lirModule = context.requireLirModule();
        context.publishFunctionLoweringContexts(buildFunctionLoweringContexts(analysisData, lirModule));
    }

    private @NotNull List<FunctionLoweringContext> buildFunctionLoweringContexts(
            @NotNull FrontendAnalysisData analysisData,
            @NotNull LirModule lirModule
    ) {
        Objects.requireNonNull(analysisData, "analysisData must not be null");
        Objects.requireNonNull(lirModule, "lirModule must not be null");

        var classByAstOwner = new IdentityHashMap<Node, LirClassDef>();
        var sourceRelationByAstOwner = new IdentityHashMap<Node, FrontendSourceClassRelation>();
        var contexts = new ArrayList<FunctionLoweringContext>();

        indexSourceRelations(analysisData, lirModule, classByAstOwner, sourceRelationByAstOwner);
        for (var sourceClassRelation : analysisData.moduleSkeleton().sourceClassRelations()) {
            visitStatements(
                    sourceClassRelation.unit().ast().statements(),
                    sourceClassRelation,
                    sourceClassRelation.topLevelClassDef(),
                    analysisData,
                    classByAstOwner,
                    sourceRelationByAstOwner,
                    contexts
            );
        }
        return List.copyOf(contexts);
    }

    private void indexSourceRelations(
            @NotNull FrontendAnalysisData analysisData,
            @NotNull LirModule lirModule,
            @NotNull IdentityHashMap<Node, LirClassDef> classByAstOwner,
            @NotNull IdentityHashMap<Node, FrontendSourceClassRelation> sourceRelationByAstOwner
    ) {
        for (var sourceClassRelation : analysisData.moduleSkeleton().sourceClassRelations()) {
            indexClassOwner(sourceClassRelation.astOwner(), sourceClassRelation.topLevelClassDef(), lirModule, classByAstOwner);
            indexSourceRelation(sourceClassRelation.astOwner(), sourceClassRelation, sourceRelationByAstOwner);
            for (var innerClassRelation : sourceClassRelation.innerClassRelations()) {
                indexClassOwner(innerClassRelation.astOwner(), innerClassRelation.classDef(), lirModule, classByAstOwner);
                indexSourceRelation(innerClassRelation.astOwner(), sourceClassRelation, sourceRelationByAstOwner);
            }
        }
    }

    private void visitStatements(
            @NotNull List<Statement> statements,
            @NotNull FrontendSourceClassRelation sourceClassRelation,
            @NotNull LirClassDef owningClass,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull IdentityHashMap<Node, LirClassDef> classByAstOwner,
            @NotNull IdentityHashMap<Node, FrontendSourceClassRelation> sourceRelationByAstOwner,
            @NotNull List<FunctionLoweringContext> contexts
    ) {
        for (var statement : statements) {
            switch (statement) {
                case FunctionDeclaration functionDeclaration -> {
                    var executableContext = buildExecutableContext(
                            sourceClassRelation,
                            owningClass,
                            functionDeclaration,
                            analysisData
                    );
                    contexts.add(executableContext);
                    collectParameterDefaultContexts(
                            sourceClassRelation,
                            owningClass,
                            functionDeclaration,
                            executableContext.targetFunction(),
                            analysisData,
                            contexts
                    );
                    collectLambdaContexts(
                            functionDeclaration.body(),
                            sourceClassRelation,
                            owningClass,
                            analysisData,
                            contexts
                    );
                }
                case ConstructorDeclaration constructorDeclaration -> {
                    contexts.add(buildExecutableContext(
                            sourceClassRelation,
                            owningClass,
                            constructorDeclaration,
                            analysisData
                    ));
                    collectLambdaContexts(
                            constructorDeclaration.body(),
                            sourceClassRelation,
                            owningClass,
                            analysisData,
                            contexts
                    );
                }
                case VariableDeclaration variableDeclaration -> {
                    var propertyInitContext = buildPropertyInitContextOrNull(
                            sourceClassRelation,
                            owningClass,
                            variableDeclaration,
                            analysisData
                    );
                    if (propertyInitContext != null) {
                        contexts.add(propertyInitContext);
                    }
                }
                case ClassDeclaration classDeclaration -> visitStatements(
                        classDeclaration.body().statements(),
                        requireSourceClassRelation(classDeclaration, sourceRelationByAstOwner),
                        requireOwningClass(classDeclaration, classByAstOwner),
                        analysisData,
                        classByAstOwner,
                        sourceRelationByAstOwner,
                        contexts
                );
                default -> {
                }
            }
        }
    }

    private @NotNull FunctionLoweringContext buildExecutableContext(
            @NotNull FrontendSourceClassRelation sourceClassRelation,
            @NotNull LirClassDef owningClass,
            @NotNull FunctionDeclaration functionDeclaration,
            @NotNull FrontendAnalysisData analysisData
    ) {
        requirePublishedScope(functionDeclaration, "callable owner", analysisData);
        requirePublishedScope(functionDeclaration.body(), "callable body", analysisData);
        var targetFunction = requireSkeletonFunction(
                owningClass,
                functionDeclaration.name(),
                functionDeclaration.isStatic(),
                functionDeclaration.parameters().size()
        );
        requireShellOnlyExecutableFunction(owningClass, targetFunction);
        ensureExecutableSelfParameter(owningClass, targetFunction);
        return new FunctionLoweringContext(
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                sourceClassRelation.unit().path(),
                sourceClassRelation,
                owningClass,
                targetFunction,
                functionDeclaration,
                functionDeclaration.body(),
                analysisData
        );
    }

    private @NotNull FunctionLoweringContext buildExecutableContext(
            @NotNull FrontendSourceClassRelation sourceClassRelation,
            @NotNull LirClassDef owningClass,
            @NotNull ConstructorDeclaration constructorDeclaration,
            @NotNull FrontendAnalysisData analysisData
    ) {
        requirePublishedScope(constructorDeclaration, "callable owner", analysisData);
        requirePublishedScope(constructorDeclaration.body(), "callable body", analysisData);
        var targetFunction = requireSkeletonFunction(owningClass, "_init", false, constructorDeclaration.parameters().size());
        requireShellOnlyExecutableFunction(owningClass, targetFunction);
        ensureExecutableSelfParameter(owningClass, targetFunction);
        return new FunctionLoweringContext(
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                sourceClassRelation.unit().path(),
                sourceClassRelation,
                owningClass,
                targetFunction,
                constructorDeclaration,
                constructorDeclaration.body(),
                analysisData
        );
    }

    private FunctionLoweringContext buildPropertyInitContextOrNull(
            @NotNull FrontendSourceClassRelation sourceClassRelation,
            @NotNull LirClassDef owningClass,
            @NotNull VariableDeclaration variableDeclaration,
            @NotNull FrontendAnalysisData analysisData
    ) {
        if (variableDeclaration.kind() != DeclarationKind.VAR || variableDeclaration.value() == null) {
            return null;
        }
        requirePublishedScope(variableDeclaration, "property declaration", analysisData);
        if (!FrontendPropertyInitializerSupport.isSupportedPropertyInitializer(
                analysisData.scopesByAst(),
                variableDeclaration
        )) {
            return null;
        }
        var initializerExpression = Objects.requireNonNull(
                variableDeclaration.value(),
                "supported property initializer must have a value"
        );
        requirePublishedScope(initializerExpression, "property initializer expression", analysisData);

        var propertyDef = requireProperty(owningClass, variableDeclaration.name());
        var targetFunction = requireOrCreatePropertyInitFunction(owningClass, propertyDef);
        return new FunctionLoweringContext(
                FunctionLoweringContext.Kind.PROPERTY_INIT,
                sourceClassRelation.unit().path(),
                sourceClassRelation,
                owningClass,
                targetFunction,
                variableDeclaration,
                initializerExpression,
                analysisData
        );
    }

    /// Materializes one hidden synthetic shell per parameter whose default expression survived the
    /// sema sweep, then publishes the frozen `PARAMETER_DEFAULT_INIT` context shape: `sourceOwner`
    /// stays the `Parameter` node and `loweringRoot` the default expression, so CFG/body passes
    /// keep reading the original AST identities from the shared side tables. Parameters whose
    /// `defaultValueFunc` is still null were rejected by the sweep and already carry upstream
    /// diagnostics, so they are skipped without touching LIR. The closing reverse scan pins the
    /// sema/lowering invariant: default metadata on a parameter with no surviving AST default
    /// expression is skeleton drift and fails fast instead of lowering a dangling reference.
    private void collectParameterDefaultContexts(
            @NotNull FrontendSourceClassRelation sourceClassRelation,
            @NotNull LirClassDef owningClass,
            @NotNull FunctionDeclaration functionDeclaration,
            @NotNull LirFunctionDef owningFunction,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull List<FunctionLoweringContext> contexts
    ) {
        var materializedParameterNames = new HashSet<String>();
        for (var parameter : functionDeclaration.parameters()) {
            var defaultValue = parameter.defaultValue();
            if (defaultValue == null) {
                continue;
            }
            var parameterName = parameter.name().trim();
            // Lookup by name, not AST index: the executable context build already injected the
            // leading `self` parameter into instance functions, so AST parameter indices no longer
            // line up with LIR parameter indices.
            var parameterDef = owningFunction.getParameter(parameterName);
            if (parameterDef == null) {
                throw new IllegalStateException(
                        "Parameter skeleton drifted for '"
                                + owningClass.getName()
                                + "."
                                + owningFunction.getName()
                                + "': no LIR parameter named '"
                                + parameterName
                                + "'"
                );
            }
            var shellName = parameterDef.defaultValueFunc();
            if (shellName == null) {
                continue;
            }
            var shell = synthesizeParameterDefaultShell(owningClass, owningFunction, parameterDef, shellName);
            materializedParameterNames.add(parameterName);
            contexts.add(new FunctionLoweringContext(
                    FunctionLoweringContext.Kind.PARAMETER_DEFAULT_INIT,
                    sourceClassRelation.unit().path(),
                    sourceClassRelation,
                    owningClass,
                    shell,
                    parameter,
                    defaultValue,
                    analysisData
            ));
        }
        for (var parameterDef : owningFunction.getParameters()) {
            if (parameterDef.getDefaultValueFunc() != null
                    && !materializedParameterNames.contains(parameterDef.getName())) {
                throw new IllegalStateException(
                        "Parameter '"
                                + parameterDef.getName()
                                + "' of '"
                                + owningClass.getName()
                                + "."
                                + owningFunction.getName()
                                + "' carries default metadata '"
                                + parameterDef.getDefaultValueFunc()
                                + "' but has no AST default expression; the sema sweep must keep "
                                + "metadata and AST defaults in sync"
                );
            }
        }
    }

    /// The shell name is owned by sema (`LirParameterDef.defaultValueFunc`); preparation never
    /// re-derives it, so metadata and shell can never drift apart. An already existing function
    /// with the same name is a reserved-prefix violation or a repeated preparation run — both are
    /// programmer errors, never silently reused or overwritten. The shell returns the parameter's
    /// declared slot type (the ABI output the omitted argument is completed with) and mirrors the
    /// owning function's static flag; instance functions get a leading `self` parameter typed as
    /// the owning class, which is exactly the slot the default island binds `self` to.
    private @NotNull LirFunctionDef synthesizeParameterDefaultShell(
            @NotNull LirClassDef owningClass,
            @NotNull LirFunctionDef owningFunction,
            @NotNull LirParameterDef parameterDef,
            @NotNull String shellName
    ) {
        if (owningClass.hasFunction(shellName)) {
            throw new IllegalStateException(
                    "Class '"
                            + owningClass.getName()
                            + "' already declares a function named '"
                            + shellName
                            + "'; source members must be rejected by the reserved '_default_' prefix"
            );
        }
        var shell = new LirFunctionDef(shellName);
        shell.setHidden(true);
        shell.setStatic(owningFunction.isStatic());
        shell.setReturnType(parameterDef.type());
        if (!owningFunction.isStatic()) {
            shell.addParameter(new LirParameterDef(
                    "self",
                    new GdObjectType(owningClass.getName()),
                    null,
                    shell
            ));
        }
        owningClass.addFunction(shell);
        return shell;
    }

    /// Discovers every `LambdaExpression` reachable from a supported executable body and appends a
    /// synthesized `LAMBDA_BODY` context for each one. Discovered lambdas are
    /// exactly the ones the interface phase records, so each must carry a published
    /// `FrontendLambdaPlan`; a missing plan means published-fact corruption and fails fast instead
    /// of silently skipping the lambda. Nested lambdas are found by
    /// recursing into each discovered lambda body. Property-initializer expressions stay outside
    /// this scan surface: their lambdas are unrecorded and already carry upstream error
    /// diagnostics, so the pipeline stops before preparation.
    private void collectLambdaContexts(
            @NotNull Node root,
            @NotNull FrontendSourceClassRelation sourceClassRelation,
            @NotNull LirClassDef owningClass,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull List<FunctionLoweringContext> contexts
    ) {
        for (var child : root.getChildren()) {
            if (child instanceof LambdaExpression lambdaExpression) {
                contexts.add(buildLambdaContext(
                        sourceClassRelation,
                        owningClass,
                        lambdaExpression,
                        analysisData
                ));
                collectLambdaContexts(
                        lambdaExpression.body(),
                        sourceClassRelation,
                        owningClass,
                        analysisData,
                        contexts
                );
                continue;
            }
            collectLambdaContexts(
                    child,
                    sourceClassRelation,
                    owningClass,
                    analysisData,
                    contexts
            );
        }
    }

    private @NotNull FunctionLoweringContext buildLambdaContext(
            @NotNull FrontendSourceClassRelation sourceClassRelation,
            @NotNull LirClassDef owningClass,
            @NotNull LambdaExpression lambdaExpression,
            @NotNull FrontendAnalysisData analysisData
    ) {
        requirePublishedScope(lambdaExpression.body(), "lambda body", analysisData);
        var plan = requireLambdaPlan(lambdaExpression, owningClass, analysisData);
        if (!plan.owningClassCanonicalName().equals(owningClass.getName())) {
            throw new IllegalStateException(
                    "Lambda plan '"
                            + plan.syntheticName()
                            + "' belongs to class '"
                            + plan.owningClassCanonicalName()
                            + "' but was discovered while preparing '"
                            + owningClass.getName()
                            + "'"
            );
        }
        var lambdaScope = requireLambdaCallableScope(lambdaExpression, analysisData);
        var targetFunction = synthesizeLambdaShell(
                owningClass,
                lambdaExpression,
                lambdaScope,
                plan
        );
        // Coroutine bridge: lambda shells are synthesized here, long after the
        // skeleton pass consumed `coroutineFunctions`, so sema records lambda owners by AST
        // identity in `coroutineLambdaOwners`. A marked owner needs both facts: `setCoroutine`
        // is the LIR/backend attribute, while `markCoroutineFunction` feeds the body-lowering
        // membership check (`FrontendBodyLoweringSession.isTargetFunctionCoroutine`).
        if (analysisData.coroutineLambdaOwners().contains(lambdaExpression)) {
            targetFunction.setCoroutine(true);
            analysisData.markCoroutineFunction(targetFunction);
        }
        return new FunctionLoweringContext(
                FunctionLoweringContext.Kind.LAMBDA_BODY,
                sourceClassRelation.unit().path(),
                sourceClassRelation,
                owningClass,
                targetFunction,
                lambdaExpression,
                lambdaExpression.body(),
                analysisData
        );
    }

    private @NotNull FrontendLambdaPlan requireLambdaPlan(
            @NotNull LambdaExpression lambdaExpression,
            @NotNull LirClassDef owningClass,
            @NotNull FrontendAnalysisData analysisData
    ) {
        var plan = analysisData.lambdaPlans().get(lambdaExpression);
        if (plan == null) {
            throw new IllegalStateException(
                    "lambdaPlans() is missing a published FrontendLambdaPlan for LambdaExpression at "
                            + lambdaExpression.range()
                            + " while preparing class '"
                            + owningClass.getName()
                            + "'; recorded lambdas must publish a complete plan before lowering"
            );
        }
        return plan;
    }

    private @NotNull CallableScope requireLambdaCallableScope(
            @NotNull LambdaExpression lambdaExpression,
            @NotNull FrontendAnalysisData analysisData
    ) {
        var scope = analysisData.scopesByAst().get(lambdaExpression);
        if (!(scope instanceof CallableScope callableScope)) {
            throw new IllegalStateException(
                    "lambda expression scope has not been published for LambdaExpression@"
                            + System.identityHashCode(lambdaExpression)
            );
        }
        return callableScope;
    }

    /// Materializes the hidden lambda shell:
    /// `setLambda(true)` + `setHidden(true)` + `setStatic(true)`, source parameters with their
    /// inventory-resolved types (no injected `self`; self only ever arrives as a capture),
    /// `<captures>` from the published plan, and the declared return type published on the plan.
    /// `setLambda(true)` must precede `addCapture` because captures are only legal on lambda
    /// functions.
    private @NotNull LirFunctionDef synthesizeLambdaShell(
            @NotNull LirClassDef owningClass,
            @NotNull LambdaExpression lambdaExpression,
            @NotNull CallableScope lambdaScope,
            @NotNull FrontendLambdaPlan plan
    ) {
        if (owningClass.hasFunction(plan.syntheticName())) {
            throw new IllegalStateException(
                    "Class '"
                            + owningClass.getName()
                            + "' already declares a function named '"
                            + plan.syntheticName()
                            + "'; source members must be rejected by the reserved '_lambda_' prefix"
            );
        }
        var function = new LirFunctionDef(plan.syntheticName());
        function.setLambda(true);
        function.setHidden(true);
        function.setStatic(true);
        function.setReturnType(plan.returnType());
        for (var parameter : lambdaExpression.parameters()) {
            var parameterName = parameter.name().trim();
            var binding = lambdaScope.resolveValueHere(parameterName);
            if (binding == null || binding.kind() != ScopeValueKind.PARAMETER) {
                throw new IllegalStateException(
                        "Lambda parameter '"
                                + parameterName
                                + "' has no published PARAMETER binding on its CallableScope; "
                                + "variable inventory must bind every planned lambda parameter"
                );
            }
            function.addParameter(new LirParameterDef(parameterName, binding.type(), null, function));
            if (parameter.variadic()) {
                function.setVararg(true);
            }
        }
        for (var capture : plan.captures()) {
            function.addCapture(new LirCaptureDef(capture.name(), capture.type(), function));
        }
        owningClass.addFunction(function);
        return function;
    }

    private void requirePublishedScope(
            @NotNull Node astNode,
            @NotNull String role,
            @NotNull FrontendAnalysisData analysisData
    ) {
        var scope = analysisData.scopesByAst().get(astNode);
        if (scope == null) {
            throw new IllegalStateException(
                    role + " scope has not been published for "
                            + astNode.getClass().getSimpleName()
                            + "@"
                            + System.identityHashCode(astNode)
            );
        }
    }

    private @NotNull LirClassDef requireOwningClass(
            @NotNull Node astOwner,
            @NotNull IdentityHashMap<Node, LirClassDef> classByAstOwner
    ) {
        var classDef = classByAstOwner.get(astOwner);
        if (classDef == null) {
            throw new IllegalStateException(
                    "No class skeleton was indexed for "
                            + astOwner.getClass().getSimpleName()
                            + "@"
                            + System.identityHashCode(astOwner)
            );
        }
        return classDef;
    }

    private @NotNull FrontendSourceClassRelation requireSourceClassRelation(
            @NotNull Node astOwner,
            @NotNull IdentityHashMap<Node, FrontendSourceClassRelation> sourceRelationByAstOwner
    ) {
        var sourceClassRelation = sourceRelationByAstOwner.get(astOwner);
        if (sourceClassRelation == null) {
            throw new IllegalStateException(
                    "No source class relation was indexed for "
                            + astOwner.getClass().getSimpleName()
                            + "@"
                            + System.identityHashCode(astOwner)
            );
        }
        return sourceClassRelation;
    }

    private @NotNull LirFunctionDef requireSkeletonFunction(
            @NotNull LirClassDef owningClass,
            @NotNull String sourceFunctionName,
            boolean isStatic,
            int parameterCount
    ) {
        var functionName = "_init".equals(sourceFunctionName) ? "_init" : sourceFunctionName.trim();
        var matches = owningClass.getFunctions().stream()
                .filter(function -> function.getName().equals(functionName))
                .filter(function -> function.isStatic() == isStatic)
                .filter(function -> matchesExecutableParameterShape(owningClass, function, isStatic, parameterCount))
                .toList();
        if (matches.size() != 1) {
            throw new IllegalStateException(
                    "Expected exactly one function skeleton for "
                            + owningClass.getName()
                            + "."
                            + functionName
                            + " with static="
                            + isStatic
                            + " and parameterCount="
                            + parameterCount
                            + ", but found "
                            + matches.size()
            );
        }
        return matches.getFirst();
    }

    /// Shared skeleton metadata keeps executable instance functions user-parameter-only. Preparation
    /// upgrades them to backend-facing shells by injecting the leading `self` parameter exactly once.
    private void ensureExecutableSelfParameter(
            @NotNull LirClassDef owningClass,
            @NotNull LirFunctionDef function
    ) {
        if (function.isStatic()) {
            return;
        }
        var expectedSelfType = new GdObjectType(owningClass.getName());
        var firstParameter = function.getParameter(0);
        if (firstParameter != null && firstParameter.name().equals("self")) {
            if (!firstParameter.type().getTypeName().equals(expectedSelfType.getTypeName())) {
                throw new IllegalStateException(
                        "Executable function '"
                                + owningClass.getName()
                                + "."
                                + function.getName()
                                + "' has self parameter type '"
                                + firstParameter.type().getTypeName()
                                + "', expected '"
                                + expectedSelfType.getTypeName()
                                + "'"
                );
            }
            return;
        }
        if (function.getParameter("self") != null) {
            throw new IllegalStateException(
                    "Executable function '"
                            + owningClass.getName()
                            + "."
                            + function.getName()
                            + "' must expose self as the leading parameter"
            );
        }
        function.addParameter(0, new LirParameterDef("self", expectedSelfType, null, function));
    }

    private boolean matchesExecutableParameterShape(
            @NotNull LirClassDef owningClass,
            @NotNull LirFunctionDef function,
            boolean isStatic,
            int sourceParameterCount
    ) {
        if (isStatic) {
            return function.getParameterCount() == sourceParameterCount;
        }
        if (function.getParameterCount() == sourceParameterCount) {
            return true;
        }
        if (function.getParameterCount() != sourceParameterCount + 1) {
            return false;
        }
        var firstParameter = function.getParameter(0);
        return firstParameter != null
                && firstParameter.name().equals("self")
                && firstParameter.type().getTypeName().equals(owningClass.getName());
    }

    private @NotNull LirPropertyDef requireProperty(
            @NotNull LirClassDef owningClass,
            @NotNull String propertyName
    ) {
        var matches = owningClass.getProperties().stream()
                .filter(property -> property.getName().equals(propertyName))
                .toList();
        if (matches.size() != 1) {
            throw new IllegalStateException(
                    "Expected exactly one property skeleton for "
                            + owningClass.getName()
                            + "."
                            + propertyName
                            + ", but found "
                            + matches.size()
            );
        }
        return matches.getFirst();
    }

    private @NotNull LirFunctionDef requireOrCreatePropertyInitFunction(
            @NotNull LirClassDef owningClass,
            @NotNull LirPropertyDef propertyDef
    ) {
        var initFuncName = propertyDef.getInitFunc();
        if (initFuncName == null || initFuncName.isBlank()) {
            initFuncName = PROPERTY_INIT_PREFIX + propertyDef.getName();
            propertyDef.setInitFunc(initFuncName);
        }
        var resolvedInitFuncName = initFuncName;
        var matches = owningClass.getFunctions().stream()
                .filter(function -> function.getName().equals(resolvedInitFuncName))
                .toList();
        if (matches.isEmpty()) {
            return requireCompatiblePropertyInitShell(
                    owningClass,
                    propertyDef,
                    createPropertyInitShell(owningClass, propertyDef, resolvedInitFuncName)
            );
        }
        if (matches.size() != 1) {
            throw new IllegalStateException(
                    "Expected at most one property init shell for "
                            + owningClass.getName()
                            + "."
                            + resolvedInitFuncName
                            + ", but found "
                            + matches.size()
            );
        }

        return requireCompatiblePropertyInitShell(owningClass, propertyDef, matches.getFirst());
    }

    private @NotNull LirFunctionDef createPropertyInitShell(
            @NotNull LirClassDef owningClass,
            @NotNull LirPropertyDef propertyDef,
            @NotNull String initFuncName
    ) {
        var function = new LirFunctionDef(initFuncName);
        function.setStatic(propertyDef.isStatic());
        function.setHidden(true);
        function.setReturnType(propertyDef.getType());
        if (!propertyDef.isStatic()) {
            function.addParameter(new LirParameterDef(
                    "self",
                    new GdObjectType(owningClass.getName()),
                    null,
                    function
            ));
        }
        owningClass.addFunction(function);
        return function;
    }

    /// Preparation is the last shell-only stage for executable callable skeletons. Any existing
    /// blocks or entry metadata mean a later lowering phase already mutated the function shape.
    private void requireShellOnlyExecutableFunction(
            @NotNull LirClassDef owningClass,
            @NotNull LirFunctionDef function
    ) {
        if (function.getBasicBlockCount() != 0 || !function.getEntryBlockId().isEmpty()) {
            throw new IllegalStateException(
                    "Executable function '"
                            + owningClass.getName()
                            + "."
                            + function.getName()
                            + "' must remain shell-only during preparation"
            );
        }
    }

    /// `initFunc` may already point at a synthetic shell created by an earlier phase or by a
    /// previous preparation run. Reuse is only legal when that shell still matches the property's
    /// backend-facing contract.
    private @NotNull LirFunctionDef requireCompatiblePropertyInitShell(
            @NotNull LirClassDef owningClass,
            @NotNull LirPropertyDef propertyDef,
            @NotNull LirFunctionDef function
    ) {
        if (!function.isHidden()) {
            throw new IllegalStateException(
                    "Property init function '"
                            + owningClass.getName()
                            + "."
                            + function.getName()
                            + "' must be hidden"
            );
        }
        if (function.isStatic() != propertyDef.isStatic()) {
            throw new IllegalStateException(
                    "Property init function '"
                            + owningClass.getName()
                            + "."
                            + function.getName()
                            + "' static flag does not match property '"
                            + propertyDef.getName()
                            + "'"
            );
        }
        if (!Objects.equals(function.getReturnType().getTypeName(), propertyDef.getType().getTypeName())) {
            throw new IllegalStateException(
                    "Property init function '"
                            + owningClass.getName()
                            + "."
                            + function.getName()
                            + "' return type does not match property '"
                            + propertyDef.getName()
                            + "'"
            );
        }
        if (function.getBasicBlockCount() != 0 || !function.getEntryBlockId().isEmpty()) {
            throw new IllegalStateException(
                    "Property init function '"
                            + owningClass.getName()
                            + "."
                            + function.getName()
                            + "' must remain shell-only during preparation"
            );
        }
        if (propertyDef.isStatic()) {
            if (function.getParameterCount() != 0) {
                throw new IllegalStateException(
                        "Static property init function '"
                                + owningClass.getName()
                                + "."
                                + function.getName()
                                + "' must not declare parameters"
                );
            }
            return function;
        }

        if (function.getParameterCount() != 1) {
            throw new IllegalStateException(
                    "Property init function '"
                            + owningClass.getName()
                            + "."
                            + function.getName()
                            + "' must declare exactly one self parameter"
            );
        }
        var selfParameter = Objects.requireNonNull(function.getParameter(0), "self parameter must exist");
        if (!selfParameter.name().equals("self")) {
            throw new IllegalStateException(
                    "Property init function '"
                            + owningClass.getName()
                            + "."
                            + function.getName()
                            + "' must declare the self parameter as 'self'"
            );
        }
        var expectedSelfType = new GdObjectType(owningClass.getName());
        if (!Objects.equals(selfParameter.type().getTypeName(), expectedSelfType.getTypeName())) {
            throw new IllegalStateException(
                    "Property init function '"
                            + owningClass.getName()
                            + "."
                            + function.getName()
                            + "' self parameter type does not match owning class"
            );
        }
        return function;
    }

    private void indexClassOwner(
            @NotNull Node astOwner,
            @NotNull LirClassDef classDef,
            @NotNull LirModule lirModule,
            @NotNull IdentityHashMap<Node, LirClassDef> classByAstOwner
    ) {
        var previous = classByAstOwner.put(astOwner, classDef);
        if (previous != null) {
            throw new IllegalStateException("Duplicate class owner encountered while preparing lowering");
        }
        if (lirModule.getClassDefs().stream().noneMatch(candidate -> candidate == classDef)) {
            throw new IllegalStateException(
                    "Indexed class skeleton '" + classDef.getName() + "' is not part of the published LIR module"
            );
        }
    }

    private void indexSourceRelation(
            @NotNull Node astOwner,
            @NotNull FrontendSourceClassRelation sourceClassRelation,
            @NotNull IdentityHashMap<Node, FrontendSourceClassRelation> sourceRelationByAstOwner
    ) {
        var previous = sourceRelationByAstOwner.put(astOwner, sourceClassRelation);
        if (previous != null) {
            throw new IllegalStateException(
                    "Duplicate source class relation encountered while preparing lowering"
            );
        }
    }
}
