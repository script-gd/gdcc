package dev.superice.gdcc.frontend.lowering.pass;

import dev.superice.gdcc.frontend.lowering.FrontendLoweringContext;
import dev.superice.gdcc.frontend.lowering.FrontendLoweringPass;
import dev.superice.gdcc.frontend.lowering.FunctionLoweringContext;
import dev.superice.gdcc.frontend.sema.FrontendAnalysisData;
import dev.superice.gdcc.frontend.sema.FrontendSourceClassRelation;
import dev.superice.gdcc.frontend.sema.analyzer.support.FrontendPropertyInitializerSupport;
import dev.superice.gdcc.lir.LirClassDef;
import dev.superice.gdcc.lir.LirFunctionDef;
import dev.superice.gdcc.lir.LirModule;
import dev.superice.gdcc.lir.LirParameterDef;
import dev.superice.gdcc.lir.LirPropertyDef;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdparser.frontend.ast.ClassDeclaration;
import dev.superice.gdparser.frontend.ast.ConstructorDeclaration;
import dev.superice.gdparser.frontend.ast.DeclarationKind;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.Statement;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;

/// Collects the first function-shaped lowering units after semantic analysis and class skeleton
/// publication complete.
///
/// The pass keeps the current LIR in skeleton/shell form:
/// - executable callables reuse their published `LirFunctionDef`
/// - property initializers get hidden synthetic helper scaffolds
/// - no basic blocks or instructions are emitted yet; later CFG/body passes materialize the default
///   pipeline's executable callable and property-init bodies
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
                case FunctionDeclaration functionDeclaration ->
                        contexts.add(buildExecutableContext(sourceClassRelation, owningClass, functionDeclaration, analysisData));
                case ConstructorDeclaration constructorDeclaration ->
                        contexts.add(buildExecutableContext(sourceClassRelation, owningClass, constructorDeclaration, analysisData));
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
