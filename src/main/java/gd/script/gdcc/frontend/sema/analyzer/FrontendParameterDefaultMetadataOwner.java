package gd.script.gdcc.frontend.sema.analyzer;

import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.Parameter;
import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.diagnostic.FrontendDiagnosticSeverity;
import gd.script.gdcc.frontend.diagnostic.FrontendRange;
import gd.script.gdcc.frontend.scope.CallableScope;
import gd.script.gdcc.frontend.sema.FrontendAnalysisData;
import gd.script.gdcc.frontend.sema.FrontendExpressionType;
import gd.script.gdcc.frontend.sema.FrontendExpressionTypeStatus;
import gd.script.gdcc.frontend.sema.FrontendInterfaceSurface;
import gd.script.gdcc.frontend.sema.FrontendSyntheticPropertyHelperSupport;
import gd.script.gdcc.frontend.sema.FrontendTypedLexicalEnvironment;
import gd.script.gdcc.frontend.sema.resolver.FrontendVisibleValueDomain;
import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.lir.LirParameterDef;
import gd.script.gdcc.scope.ClassDef;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.scope.ResolveRestriction;
import gd.script.gdcc.scope.ScopeValueKind;
import gd.script.gdcc.type.GdType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/// Single owner of source-function parameter-default semantics and `LirParameterDef.defaultValueFunc`
/// metadata. Driven by `FrontendSuiteResolver` once per module, before any callable body
/// or property initializer resolves, so body call sites only ever observe finalized metadata.
///
/// The sweep runs three phases module-wide:
/// 1. structural validation — defaults must form a contiguous trailing suffix and variadic
///    parameters must not carry one (`sema.invalid_parameter_default_order`, anchored at the
///    violating parameter, which never receives metadata);
/// 2. placeholder write — every structurally legal defaulted parameter gets its deterministic
///    synthetic name (`_default_<func>$<param>`; static functions always use `_default_s_`) via
///    in-place `removeParameter`/`addParameter`, so cross-referencing default calls
///    (`func g(x = f(1))`) already see arity metadata;
/// 3. island analysis — each default expression resolves through the `PARAMETER_DEFAULT`
///    visible-value island; failures (visibility violations or incomplete facts) reclaim the
///    metadata back to `null` and emit exactly one
///    `sema.unsupported_parameter_default_expression` anchored at the default root, unless the
///    island already produced an error diagnostic of its own.
///
/// `ConstructorDeclaration`, `_init` functions, and lambdas are never swept: parameterized `_init`
/// keeps its existing rejection path and lambda parameter defaults stay fail-closed in the
/// variable analyzer. The skeleton keeps writing `defaultValueFunc = null`; this owner is the only
/// writer, which removes the publish-then-fail dangling-metadata window.
public final class FrontendParameterDefaultMetadataOwner {
    public static final @NotNull String INVALID_PARAMETER_DEFAULT_ORDER_CATEGORY =
            "sema.invalid_parameter_default_order";
    public static final @NotNull String UNSUPPORTED_PARAMETER_DEFAULT_EXPRESSION_CATEGORY =
            "sema.unsupported_parameter_default_expression";
    /// Static functions always synthesize `_default_s_` names so the prefix encodes staticness by
    /// construction. The whole `_default_` namespace (including `_default_s_`) is compiler-owned
    /// via `FrontendSyntheticPropertyHelperSupport`.
    private static final @NotNull String STATIC_PREFIX = "_default_s_";

    private final @NotNull FrontendStatementResolver statementResolver;

    public FrontendParameterDefaultMetadataOwner(@NotNull FrontendStatementResolver statementResolver) {
        this.statementResolver = Objects.requireNonNull(statementResolver, "statementResolver must not be null");
    }

    public void sweep(
            @NotNull FrontendInterfaceSurface interfaceSurface,
            @NotNull ClassRegistry classRegistry,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull DiagnosticManager diagnosticManager
    ) {
        Objects.requireNonNull(interfaceSurface, "interfaceSurface must not be null");
        Objects.requireNonNull(classRegistry, "classRegistry must not be null");
        Objects.requireNonNull(analysisData, "analysisData must not be null");
        Objects.requireNonNull(diagnosticManager, "diagnosticManager must not be null");

        // Phases 1+2 run for the whole module before any island analysis, so a default expression
        // calling another defaulted source function always reads its placeholder metadata.
        var pendingIslands = new ArrayList<PendingDefaultIsland>();
        for (var callableOwner : interfaceSurface.suiteEntryRoots().callableOwners()) {
            if (!(callableOwner instanceof FunctionDeclaration functionDeclaration) || isInitFunction(functionDeclaration)) {
                continue;
            }
            collectFunctionDefaults(functionDeclaration, interfaceSurface, analysisData, diagnosticManager, pendingIslands);
        }
        for (var island : pendingIslands) {
            analyzeIsland(island, interfaceSurface, classRegistry, analysisData, diagnosticManager);
        }
    }

    private static boolean isInitFunction(@NotNull FunctionDeclaration functionDeclaration) {
        return functionDeclaration.name().trim().equals("_init");
    }

    /// Runs structural validation (phase 1) and placeholder metadata writes (phase 2) for one
    /// function, appending every published placeholder to `pendingIslands`.
    private void collectFunctionDefaults(
            @NotNull FunctionDeclaration functionDeclaration,
            @NotNull FrontendInterfaceSurface interfaceSurface,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull DiagnosticManager diagnosticManager,
            @NotNull List<PendingDefaultIsland> pendingIslands
    ) {
        var parameters = functionDeclaration.parameters();
        var violatingParameters = validateParameterOrder(
                functionDeclaration,
                parameters,
                interfaceSurface,
                analysisData,
                diagnosticManager
        );
        if (parameters.stream().noneMatch(parameter -> parameter.defaultValue() != null)) {
            return;
        }
        if (!(analysisData.scopesByAst().get(functionDeclaration) instanceof CallableScope callableScope)) {
            // Not a published callable scope: the function sits outside the supported surface (its
            // own upstream diagnostic already fired) and must not receive metadata.
            return;
        }
        var owningClassDef = callableScope.owningClassOrNull();
        var functionDef = owningClassDef == null
                ? null
                : functionSkeletonOrNull(owningClassDef, functionDeclaration);
        if (functionDef == null) {
            // The skeleton rejected this function (e.g. reserved prefix) with its own diagnostic.
            return;
        }
        // Godot GDScript forbids any same-name function redeclaration in a class (single member
        // namespace). gdcc tolerates overload sets for builtin/extension classes, but parameter
        // defaults on source functions stay fail-closed under any same-name sibling (static pair
        // or same-staticness overload): each defaulted parameter earns one anchored diagnostic and
        // never receives metadata. This also keeps `functionSkeletonOrNull`'s
        // (name, static, arity) match unambiguous for every surviving function.
        var hasSameNameSibling = owningClassDef.getFunctions().stream()
                .anyMatch(function -> function != functionDef
                        && function.getName().equals(functionDef.getName()));
        if (hasSameNameSibling) {
            for (var parameter : parameters) {
                if (parameter.defaultValue() == null || violatingParameters.contains(parameter)) {
                    continue;
                }
                diagnosticManager.error(
                        UNSUPPORTED_PARAMETER_DEFAULT_EXPRESSION_CATEGORY,
                        "Parameter default values are not supported on function '"
                                + functionDef.getName()
                                + "' because the class declares another function with the same name.",
                        sourcePathFor(interfaceSurface, functionDeclaration, analysisData),
                        FrontendRange.fromAstRange(parameter.defaultValue().range())
                );
            }
            return;
        }
        for (var index = 0; index < parameters.size(); index++) {
            var parameter = parameters.get(index);
            if (parameter.defaultValue() == null || violatingParameters.contains(parameter)) {
                continue;
            }
            var existing = functionDef.getParameter(index);
            if (existing == null || !existing.name().equals(parameter.name().trim())) {
                throw new IllegalStateException(
                        "Parameter skeleton drifted for '" + functionDef.getName() + "' at index " + index
                );
            }
            var syntheticName = syntheticDefaultFunctionName(
                    functionDeclaration.isStatic(),
                    functionDef.getName(),
                    parameter
            );
            functionDef.removeParameter(index);
            functionDef.addParameter(index, new LirParameterDef(
                    existing.name(),
                    existing.type(),
                    syntheticName,
                    functionDef
            ));
            pendingIslands.add(new PendingDefaultIsland(
                    functionDeclaration,
                    parameter,
                    index,
                    functionDef,
                    callableScope
            ));
        }
    }

    /// Godot order rule: mandatory prefix, contiguous defaulted suffix, optional variadic tail.
    /// Every violating parameter earns exactly one diagnostic and is excluded from later phases;
    /// the remaining parameters keep flowing so sibling subtrees stay analyzed.
    private @NotNull Set<Parameter> validateParameterOrder(
            @NotNull FunctionDeclaration functionDeclaration,
            @NotNull List<Parameter> parameters,
            @NotNull FrontendInterfaceSurface interfaceSurface,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull DiagnosticManager diagnosticManager
    ) {
        var violatingParameters = Collections.newSetFromMap(new IdentityHashMap<Parameter, Boolean>());
        var seenDefault = false;
        for (var parameter : parameters) {
            if (parameter.variadic()) {
                if (parameter.defaultValue() != null) {
                    violatingParameters.add(parameter);
                    reportInvalidOrder(
                            interfaceSurface,
                            analysisData,
                            diagnosticManager,
                            functionDeclaration,
                            parameter,
                            "The rest parameter '" + parameter.name().trim() + "' cannot have a default value."
                    );
                }
                continue;
            }
            if (parameter.defaultValue() != null) {
                seenDefault = true;
                continue;
            }
            if (seenDefault) {
                violatingParameters.add(parameter);
                reportInvalidOrder(
                        interfaceSurface,
                        analysisData,
                        diagnosticManager,
                        functionDeclaration,
                        parameter,
                        "Cannot have mandatory parameters after optional parameters ('"
                                + parameter.name().trim() + "')."
                );
            }
        }
        return violatingParameters;
    }

    private static void reportInvalidOrder(
            @NotNull FrontendInterfaceSurface interfaceSurface,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull DiagnosticManager diagnosticManager,
            @NotNull FunctionDeclaration functionDeclaration,
            @NotNull Parameter parameter,
            @NotNull String detail
    ) {
        diagnosticManager.error(
                INVALID_PARAMETER_DEFAULT_ORDER_CATEGORY,
                detail,
                sourcePathFor(interfaceSurface, functionDeclaration, analysisData),
                FrontendRange.fromAstRange(parameter.range())
        );
    }

    /// Island semantic analysis (phase 3): resolves the default expression through the
    /// `PARAMETER_DEFAULT` visible-value island and publishes facts into the ordinary AST-identity
    /// side tables. On failure the placeholder metadata is reclaimed so the parameter falls back
    /// to required for every later arity check.
    private void analyzeIsland(
            @NotNull PendingDefaultIsland island,
            @NotNull FrontendInterfaceSurface interfaceSurface,
            @NotNull ClassRegistry classRegistry,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull DiagnosticManager diagnosticManager
    ) {
        var functionDeclaration = island.functionDeclaration();
        var parameter = island.parameter();
        var defaultValue = Objects.requireNonNull(
                parameter.defaultValue(),
                "parameter-default island requires a default expression"
        );
        var callableScope = island.callableScope();
        var environment = new FrontendTypedLexicalEnvironment(
                callableScope,
                analysisData,
                null,
                interfaceSurface.typedLexicalBaseline()
        );
        // Island context: explicit PARAMETER_DEFAULT domain, enclosing function stays
        // the callable owner, instance/static restriction inherited, no property-initializer
        // context (that one would wrongly seal `self`/instance members) and no nested-lambda
        // resolver (lambdas inside defaults stay fail-closed).
        var context = new FrontendSuiteContext(
                sourcePathFor(interfaceSurface, functionDeclaration, analysisData),
                functionDeclaration,
                null,
                callableScope,
                null,
                functionDeclaration.isStatic()
                        ? ResolveRestriction.staticContext()
                        : ResolveRestriction.instanceContext(),
                functionDeclaration.isStatic(),
                null,
                interfaceSurface,
                environment,
                analysisData,
                diagnosticManager,
                classRegistry,
                null,
                null,
                null,
                FrontendVisibleValueDomain.PARAMETER_DEFAULT
        );
        var errorsBefore = errorCount(diagnosticManager);
        statementResolver.resolveParameterDefault(context, parameter, expectedTypeFor(interfaceSurface, callableScope, parameter));
        // Islands are independent roots and never join a callable export batch (same export shape
        // as property initializers).
        context.typedEnvironment().exportPatchTransaction().applyTo(analysisData);
        analysisData.updateDiagnostics(diagnosticManager.snapshot());

        if (isAccepted(analysisData.expressionTypes().get(defaultValue))) {
            return;
        }
        reclaimDefaultMetadata(island);
        if (errorCount(diagnosticManager) == errorsBefore) {
            // Visibility violations stay silent inside the island; anchor the single owner
            // diagnostic at the default root. Roots that already produced their own error
            // (unknown names, failed calls, unrecorded lambdas) keep that upstream diagnostic.
            reportUnsupportedDefaultExpression(functionDeclaration, parameter, interfaceSurface, analysisData, diagnosticManager);
        }
    }

    private static boolean isAccepted(@Nullable FrontendExpressionType rootType) {
        return rootType != null
                && (rootType.status() == FrontendExpressionTypeStatus.RESOLVED
                || rootType.status() == FrontendExpressionTypeStatus.DYNAMIC);
    }

    private static void reclaimDefaultMetadata(@NotNull PendingDefaultIsland island) {
        var functionDef = island.functionDef();
        var index = island.parameterIndex();
        var existing = functionDef.getParameter(index);
        if (existing == null || !existing.name().equals(island.parameter().name().trim())) {
            throw new IllegalStateException(
                    "Parameter skeleton drifted while reclaiming default metadata for '"
                            + functionDef.getName() + "' at index " + index
            );
        }
        functionDef.removeParameter(index);
        functionDef.addParameter(index, new LirParameterDef(
                existing.name(),
                existing.type(),
                null,
                functionDef
        ));
    }

    private static void reportUnsupportedDefaultExpression(
            @NotNull FunctionDeclaration functionDeclaration,
            @NotNull Parameter parameter,
            @NotNull FrontendInterfaceSurface interfaceSurface,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull DiagnosticManager diagnosticManager
    ) {
        var defaultValue = Objects.requireNonNull(
                parameter.defaultValue(),
                "parameter default diagnostic requires a default expression"
        );
        var allowedForms = functionDeclaration.isStatic()
                ? "a static function's default expression may only use literals, constants, enums, types, "
                  + "singletons, builtin constructors, and utility/global/static calls; parameters, locals, "
                  + "captures, 'self', instance members, 'await', and get-node expressions are not visible"
                : "a default expression may only use literals, constants, enums, types, singletons, builtin "
                  + "constructors, utility/global/static calls, 'self', and instance members; parameters, "
                  + "locals, captures, 'await', and get-node expressions are not visible";
        diagnosticManager.error(
                UNSUPPORTED_PARAMETER_DEFAULT_EXPRESSION_CATEGORY,
                "Parameter default value for '" + parameter.name().trim() + "' is not supported: " + allowedForms,
                sourcePathFor(interfaceSurface, functionDeclaration, analysisData),
                FrontendRange.fromAstRange(defaultValue.range())
        );
    }

    /// The parameter slot type feeds the island's expected type so typed container literals keep
    /// their declared element types; the interface baseline is authoritative, with the callable
    /// scope binding as fallback. `Variant`/untyped slots are dropped downstream.
    private static @Nullable GdType expectedTypeFor(
            @NotNull FrontendInterfaceSurface interfaceSurface,
            @NotNull CallableScope callableScope,
            @NotNull Parameter parameter
    ) {
        var baselineType = interfaceSurface.typedLexicalBaseline().typeFor(parameter);
        if (baselineType != null) {
            return baselineType;
        }
        var slot = callableScope.resolveValueHere(parameter.name().trim());
        return slot != null
                && slot.kind() == ScopeValueKind.PARAMETER
                && slot.declaration() == parameter
                ? slot.type()
                : null;
    }

    private static @Nullable LirFunctionDef functionSkeletonOrNull(
            @NotNull ClassDef owningClassDef,
            @NotNull FunctionDeclaration functionDeclaration
    ) {
        var functionName = functionDeclaration.name().trim();
        var staticFunction = functionDeclaration.isStatic();
        var parameterCount = functionDeclaration.parameters().size();
        return owningClassDef.getFunctions().stream()
                .filter(function -> function instanceof LirFunctionDef)
                .map(LirFunctionDef.class::cast)
                .filter(function -> function.getName().equals(functionName))
                .filter(function -> function.isStatic() == staticFunction)
                .filter(function -> function.getParameterCount() == parameterCount)
                .findFirst()
                .orElse(null);
    }

    private static @NotNull String syntheticDefaultFunctionName(
            boolean staticFunction,
            @NotNull String functionName,
            @NotNull Parameter parameter
    ) {
        var prefix = staticFunction
                ? STATIC_PREFIX
                : FrontendSyntheticPropertyHelperSupport.PARAMETER_DEFAULT_PREFIX;
        return prefix + functionName + "$" + parameter.name().trim();
    }

    private static int errorCount(@NotNull DiagnosticManager diagnosticManager) {
        return (int) diagnosticManager.snapshot().asList().stream()
                .filter(diagnostic -> diagnostic.severity() == FrontendDiagnosticSeverity.ERROR)
                .count();
    }

    private static @NotNull Path sourcePathFor(
            @NotNull FrontendInterfaceSurface interfaceSurface,
            @NotNull Node entryRoot,
            @NotNull FrontendAnalysisData analysisData
    ) {
        return FrontendSuiteResolver.sourcePathFor(interfaceSurface, entryRoot, analysisData);
    }

    private record PendingDefaultIsland(
            @NotNull FunctionDeclaration functionDeclaration,
            @NotNull Parameter parameter,
            int parameterIndex,
            @NotNull LirFunctionDef functionDef,
            @NotNull CallableScope callableScope
    ) {
    }
}
