package gd.script.gdcc.frontend.sema.analyzer.support;

import dev.superice.gdparser.frontend.ast.Expression;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import gd.script.gdcc.frontend.constant.Evaluation;
import gd.script.gdcc.frontend.constant.GdConstantValue;
import gd.script.gdcc.frontend.constant.GdScriptEvaluatedConstant;
import gd.script.gdcc.frontend.scope.BlockScope;
import gd.script.gdcc.frontend.scope.CallableScope;
import gd.script.gdcc.frontend.scope.FrontendClassConstantLookup;
import gd.script.gdcc.frontend.sema.FrontendAnalysisData;
import gd.script.gdcc.frontend.sema.FrontendTypedLexicalEnvironment;
import gd.script.gdcc.frontend.sema.analyzer.FrontendSuiteContext;
import gd.script.gdcc.gdextension.ExtensionEnumValue;
import gd.script.gdcc.gdextension.ExtensionGlobalConstant;
import gd.script.gdcc.scope.ClassDef;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.scope.GdScriptClassConstant;
import gd.script.gdcc.scope.GdScriptEnumConstant;
import gd.script.gdcc.scope.GdScriptEnumGroup;
import gd.script.gdcc.scope.GdScriptLanguageConstant;
import gd.script.gdcc.scope.ResolveRestriction;
import gd.script.gdcc.scope.Scope;
import gd.script.gdcc.scope.ScopeValue;
import gd.script.gdcc.scope.ScopeValueKind;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.util.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// Scope-facing `LookupContext` for one block-local `const` initializer.
///
/// Unlike the class-level pre-pass context — which freezes "earlier same-class constants only" —
/// the local domain resolves through the ordinary lexical chain: earlier local constants (read
/// through the typed environment's overlay-aware fact view), class constants including their
/// failed-const mask, globals/language constants, and type-meta namespaces (`State.IDLE`,
/// `Other.Inner.CONST`, `Vector2.ZERO`, `Control.PRESET_CENTER`).
///
/// Two lexical rules are enforced here rather than by the scope graph itself:
/// - declaration-order visibility: a `const`/`var` whose declaration ends after the initializer's
///   start byte is filtered out of its layer, so self references and forward references fall
///   through to outer bindings exactly like `var` rules do
/// - constant facts, not scope slots, carry frozen values: a binding whose declaration is a local
///   `const` is answered by the environment's `constantFact` overlay — `Resolved` produces the
///   value, `Rejected` is terminal `Blocked`, and a missing fact is `Blocked` rather than a
///   silent fall-through.
public final class FrontendLocalConstLookupContext implements LookupContext {
    private final @NotNull VariableDeclaration declaration;
    private final @NotNull Expression initializer;
    private final @NotNull Scope declarationScope;
    private final @NotNull FrontendTypedLexicalEnvironment typedEnvironment;
    private final @NotNull FrontendAnalysisData analysisData;
    private final @NotNull ClassRegistry classRegistry;
    private final @NotNull ResolveRestriction restriction;

    public FrontendLocalConstLookupContext(
            @NotNull VariableDeclaration declaration,
            @NotNull Scope declarationScope,
            @NotNull FrontendSuiteContext context
    ) {
        this.declaration = Objects.requireNonNull(declaration, "declaration must not be null");
        this.initializer = Objects.requireNonNull(declaration.value(), "const declaration must have an initializer");
        this.declarationScope = Objects.requireNonNull(declarationScope, "declarationScope must not be null");
        this.typedEnvironment = Objects.requireNonNull(context.typedEnvironment(), "typedEnvironment");
        this.analysisData = Objects.requireNonNull(context.analysisData(), "analysisData");
        this.classRegistry = Objects.requireNonNull(context.classRegistry(), "classRegistry");
        this.restriction = Objects.requireNonNull(context.restriction(), "restriction");
    }

    @Override
    public @NotNull LookupResult resolve(@NotNull String name) {
        // Declaration-order filtered walk over block/callable layers: a hit whose declaration
        // ends after the initializer begins is not yet visible, so the layer is skipped and the
        // walk continues outward instead of answering the hidden binding.
        var scope = declarationScope;
        while (scope instanceof BlockScope || scope instanceof CallableScope) {
            var hit = scope.resolveValueHere(name, restriction);
            if (hit.isBlocked()) {
                return new LookupResult.Blocked(
                        "'" + name + "' is not accessible in the current context");
            }
            if (hit.isAllowed()) {
                var value = hit.requireValue();
                if (value.declaration() instanceof VariableDeclaration variableDeclaration
                        && !isVisibleAtInitializer(variableDeclaration)) {
                    scope = scope.getParentScope();
                    continue;
                }
                return toOutcome(name, value);
            }
            scope = scope.getParentScope();
            if (scope == null) {
                return new LookupResult.Unknown();
            }
        }
        // Outer layers (class scope, module, globals) resolve through the unrestricted chain so
        // class-constant masks and failed-const poison apply exactly as they do for ordinary
        // body reads.
        if (scope != null) {
            var outer = scope.resolveValue(name, restriction);
            if (outer.isBlocked()) {
                return new LookupResult.Blocked(
                        "'" + name + "' is claimed by an inaccessible or failed constant");
            }
            if (outer.isAllowed()) {
                return toOutcome(name, outer.requireValue());
            }
        }
        return resolveGlobalOrNamespace(name, scope);
    }

    /// One member descent inside a namespace token produced by `resolve`. Script enum groups
    /// expose members as frozen ints; script classes probe the shared single-layer constant
    /// lookup (mask first, usable table second) so a failed class const poisons qualified access
    /// the same way it poisons bare reads; engine classes decode metadata constants/enums; and
    /// builtin `GdType` identities fall through to the shared metadata decoder.
    @Override
    public @NotNull LookupResult resolveMember(@NotNull Object namespaceIdentity, @NotNull String memberName) {
        if (namespaceIdentity instanceof GdScriptEnumGroup group) {
            var member = group.findMember(memberName);
            return member != null
                    ? new LookupResult.ResolvedConstant(new GdConstantValue.IntValue(member.value()))
                    : new LookupResult.Unknown();
        }
        if (namespaceIdentity instanceof GdObjectType objectType) {
            return resolveEngineClassMember(classRegistry, objectType.className(), memberName);
        }
        if (namespaceIdentity instanceof ClassDef classDef) {
            // Qualified constants follow the same nearest-layer inheritance walk ordinary
            // chains use: `Child.C` may legally name a constant declared on `Base`.
            return switch (FrontendClassConstantLookup.probeInHierarchy(
                    classRegistry,
                    classDef,
                    memberName,
                    analysisData.rejectedClassConstants()
            )) {
                case FrontendClassConstantLookup.ProbeResult.Usable usable ->
                        toClassConstantOutcome(memberName, usable.constant());
                case FrontendClassConstantLookup.ProbeResult.TerminalBlocked blocked ->
                        new LookupResult.Blocked(
                                "'" + memberName + "' is a class constant of '" + classDef.getName()
                                        + "' whose initializer failed to evaluate");
                case FrontendClassConstantLookup.ProbeResult.Miss _ -> new LookupResult.Unknown();
            };
        }
        return LookupContext.super.resolveMember(namespaceIdentity, memberName);
    }

    @Override
    public @NotNull FrontendConstCallableRegistry callableRegistry() {
        // The local domain shares the same production registry the class pre-pass consumes:
        // currently the immutable empty table — unregistered pure-looking calls stay rejected —
        // and any future populated table is consumed identically here.
        return FrontendConstCallableRegistry.empty();
    }

    @Override
    public @NotNull ClassRegistry classRegistry() {
        return classRegistry;
    }

    /// Maps a found scope value to the const domain's four-state answer. Local constants answer
    /// through the overlay-aware fact view (never their slot type), model declarations produced
    /// by the skeleton map straight to frozen values, and every mutable/unknown shape reports a
    /// non-constant reason the evaluator can surface honestly.
    private @NotNull LookupResult toOutcome(@NotNull String name, @NotNull ScopeValue value) {
        if (value.kind() == ScopeValueKind.CONSTANT) {
            return switch (value.declaration()) {
                case VariableDeclaration variableDeclaration -> {
                    var fact = typedEnvironment.constantFact(variableDeclaration);
                    if (fact instanceof Evaluation.Resolved resolved) {
                        yield new LookupResult.ResolvedConstant(resolved.value());
                    }
                    if (fact instanceof Evaluation.Rejected rejected) {
                        yield new LookupResult.Blocked(
                                "'" + name + "' failed to evaluate at its declaration; "
                                        + "the reference depends on an invalid constant");
                    }
                    yield new LookupResult.Blocked(
                            "'" + name + "' is a constant that has not been evaluated");
                }
                case GdScriptEvaluatedConstant evaluated ->
                        new LookupResult.ResolvedConstant(evaluated.value());
                case GdScriptEnumConstant enumConstant ->
                        new LookupResult.ResolvedConstant(new GdConstantValue.IntValue(enumConstant.value()));
                case GdScriptEnumGroup enumGroup -> new LookupResult.ResolvedNamespace(enumGroup);
                case GdScriptLanguageConstant languageConstant ->
                        new LookupResult.ResolvedConstant(new GdConstantValue.FloatValue(languageConstant.value()));
                case ExtensionGlobalConstant globalConstant ->
                        new LookupResult.ResolvedConstant(new GdConstantValue.IntValue(globalConstant.value()));
                case ExtensionEnumValue enumValue ->
                        new LookupResult.ResolvedConstant(new GdConstantValue.IntValue(enumValue.value()));
                case null, default -> new LookupResult.NonConstant(
                        "'" + name + "' is not a usable compile-time constant");
            };
        }
        return switch (value.kind()) {
            case LOCAL -> new LookupResult.NonConstant(
                    "'" + name + "' is a mutable local variable, not a compile-time constant");
            case PARAMETER -> new LookupResult.NonConstant(
                    "'" + name + "' is a function parameter, not a compile-time constant");
            case CAPTURE -> new LookupResult.NonConstant(
                    "'" + name + "' is a lambda capture, not a compile-time constant");
            default -> new LookupResult.NonConstant(
                    "'" + name + "' is not a compile-time constant");
        };
    }

    /// Whether `variableDeclaration` ends strictly before the local const initializer begins —
    /// the same declaration-order visibility rule ordinary `var` lookup applies, reused here so
    /// a const initializer can never read itself or a later same-name local.
    private boolean isVisibleAtInitializer(@NotNull VariableDeclaration variableDeclaration) {
        return variableDeclaration.range().endByte() <= initializer.range().startByte();
    }

    /// Global fallback plus the type-meta namespace: bare engine enum values, JSON global
    /// constants, and language constants come first so `Vector2` et al. can only be claimed by
    /// the namespace branch afterwards.
    private @NotNull LookupResult resolveGlobalOrNamespace(@NotNull String name, @Nullable Scope scope) {
        var globalEnumValue = classRegistry.findGlobalEnumValueByBareName(name);
        if (globalEnumValue != null) {
            return new LookupResult.ResolvedConstant(new GdConstantValue.IntValue(globalEnumValue.value()));
        }
        var globalConstant = classRegistry.findGlobalConstant(name);
        if (globalConstant != null) {
            return new LookupResult.ResolvedConstant(new GdConstantValue.IntValue(globalConstant.value()));
        }
        var languageConstant = classRegistry.findGdScriptLanguageConstant(name);
        if (languageConstant != null) {
            return new LookupResult.ResolvedConstant(new GdConstantValue.FloatValue(languageConstant.value()));
        }
        if (scope != null) {
            var typeMeta = analysisData.moduleSkeleton()
                    .resolveSourceFacingTypeMeta(scope, name, restriction)
                    .allowedValueOrNull();
            if (typeMeta != null) {
                // Only genuine namespaces may descend or construct: the same `ScopeTypeMetaKind`
                // gate the class pre-pass uses — builtins/engine classes become namespaces while
                // global enums, script classes, and named enum groups answer as honest
                // non-constants/namespaces so `Error(1)` cannot sneak through as an `int`
                // constructor.
                return switch (typeMeta.kind()) {
                    case BUILTIN, ENGINE_CLASS ->
                            new LookupResult.ResolvedNamespace(typeMeta.instanceType());
                    case GDCC_CLASS -> typeMeta.declaration() instanceof ClassDef classDef
                            ? new LookupResult.ResolvedNamespace(classDef)
                            : new LookupResult.NonConstant(
                            "'" + name + "' is a script class, not a constant value");
                    case GDCC_ENUM -> typeMeta.declaration() instanceof GdScriptEnumGroup group
                            ? new LookupResult.ResolvedNamespace(group)
                            : new LookupResult.NonConstant(
                            "'" + name + "' is a named enum group, not a constant value");
                    case GLOBAL_ENUM -> new LookupResult.NonConstant(
                            "'" + name + "' is a global enum, not a constant value");
                };
            }
        }
        return new LookupResult.Unknown();
    }

    /// Maps one published class-constant row to the const domain's answer; declaration shapes
    /// outside the three the skeleton produces are ignored defensively so an unexpected row can
    /// never be reinterpreted as a usable constant.
    private static @NotNull LookupResult toClassConstantOutcome(
            @NotNull String memberName,
            @NotNull GdScriptClassConstant constant
    ) {
        return switch (constant.declaration()) {
            case GdScriptEvaluatedConstant evaluated ->
                    new LookupResult.ResolvedConstant(evaluated.value());
            case GdScriptEnumGroup enumGroup -> new LookupResult.ResolvedNamespace(enumGroup);
            case GdScriptEnumConstant enumConstant ->
                    new LookupResult.ResolvedConstant(new GdConstantValue.IntValue(enumConstant.value()));
            default -> new LookupResult.NonConstant(
                    "'" + memberName + "' is declared on the class but is not a usable constant");
        };
    }

    /// Engine classes expose integer metadata constants and enum values qualified by class name
    /// (`Control.PRESET_CENTER`); anything else — including GDCC class names, which never reach
    /// the engine hierarchy finders — stays `Unknown` and is rejected by the caller domain.
    /// Shared with `FrontendClassConstPrepassLookup` so both contexts decode one surface.
    static @NotNull LookupResult resolveEngineClassMember(
            @NotNull ClassRegistry classRegistry,
            @NotNull String engineClassName,
            @NotNull String memberName
    ) {
        var constant = classRegistry.findEngineClassConstantInHierarchy(engineClassName, memberName);
        if (constant != null) {
            var parsed = parseEngineConstantInt(constant.constant().value());
            return parsed != null
                    ? new LookupResult.ResolvedConstant(parsed)
                    : new LookupResult.Unknown();
        }
        var enumValue = classRegistry.findEngineClassEnumValueInHierarchy(engineClassName, memberName);
        return enumValue != null
                ? new LookupResult.ResolvedConstant(new GdConstantValue.IntValue(enumValue.enumValue().value()))
                : new LookupResult.Unknown();
    }

    /// Engine constant metadata carries the source literal as text; only plain signed-decimal
    /// ints are decodable here, anything richer stays `Unknown` rather than guessed.
    private static @Nullable GdConstantValue.IntValue parseEngineConstantInt(@NotNull String valueText) {
        var text = valueText.trim();
        var negative = text.startsWith("-");
        var magnitude = negative ? text.substring(1) : text;
        var parsed = StringUtil.parseGdUnsignedIntegerLexeme(magnitude);
        return parsed == null ? null : new GdConstantValue.IntValue(negative ? -parsed : parsed);
    }
}
