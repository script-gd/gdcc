package gd.script.gdcc.frontend.sema.analyzer.support;

import dev.superice.gdparser.frontend.ast.DeclarationKind;
import dev.superice.gdparser.frontend.ast.EnumDeclaration;
import dev.superice.gdparser.frontend.ast.EnumMember;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import gd.script.gdcc.frontend.constant.GdConstantValue;
import gd.script.gdcc.frontend.constant.GdScriptEvaluatedConstant;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.scope.GdScriptClassConstant;
import gd.script.gdcc.scope.GdScriptEnumConstant;
import gd.script.gdcc.scope.GdScriptEnumGroup;
import gd.script.gdcc.scope.ScopeTypeMetaKind;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.util.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/// Scope-facing `LookupContext` for class-level constant initializers.
///
/// The ordering rules are shared verbatim by the two declaration domains that evaluate during
/// the joint pre-pass — class `const` initializers and enum member initializers — because both
/// are frozen by the same principle: only earlier, already-published same-class constants and
/// namespaces may be referenced; every other declared name (self, forward, failed, non-constant
/// member, inherited member) must terminate as `Blocked` instead of leaking to globals.
/// What the two domains disagree on is kept in [Mode]: enum evaluation only admits int payloads
/// and no namespaces, while class consts take the full frozen-value surface including builtin
/// namespaces (`Vector2.ZERO`) and named enum groups (`State.IDLE`).
public final class FrontendClassConstPrepassLookup implements LookupContext {
    /// Which declaration domain is being evaluated.
    public enum Mode {
        /// Class `const` initializer: full frozen-value whitelist plus namespace descent.
        CLASS_CONST_INITIALIZER,
        /// Enum member initializer: int-only payloads, no namespaces, no member descent.
        ENUM_PREP_PASS
    }

    private final @NotNull Mode mode;
    /// Values of earlier members of the enum currently being evaluated (enum mode only).
    private final @NotNull Map<String, Long> earlierEnumMembers;
    private final @NotNull FrontendClassConstPrepassContext classContext;
    /// The declaration node currently being evaluated, so a name that resolves back to itself
    /// can be diagnosed as a self-reference instead of a generic forward reference.
    private final @Nullable Node selfDeclaration;
    private final @NotNull ClassRegistry classRegistry;

    public FrontendClassConstPrepassLookup(
            @NotNull Mode mode,
            @NotNull Map<String, Long> earlierEnumMembers,
            @NotNull FrontendClassConstPrepassContext classContext,
            @Nullable Node selfDeclaration,
            @NotNull ClassRegistry classRegistry
    ) {
        this.mode = Objects.requireNonNull(mode, "mode must not be null");
        // The map is only read for the synchronous duration of one initializer evaluation and
        // mutated by the caller strictly between evaluations, so a read-only view is enough —
        // copying here would cost Θ(n²) for enums with many explicit initializers.
        this.earlierEnumMembers = Collections.unmodifiableMap(
                Objects.requireNonNull(earlierEnumMembers, "earlierEnumMembers must not be null")
        );
        this.classContext = Objects.requireNonNull(classContext, "classContext must not be null");
        this.selfDeclaration = selfDeclaration;
        this.classRegistry = Objects.requireNonNull(classRegistry, "classRegistry must not be null");
    }

    public static @NotNull FrontendClassConstPrepassLookup forClassConst(
            @NotNull FrontendClassConstPrepassContext classContext,
            @NotNull Node selfDeclaration,
            @NotNull ClassRegistry classRegistry
    ) {
        return new FrontendClassConstPrepassLookup(
                Mode.CLASS_CONST_INITIALIZER,
                Map.of(),
                classContext,
                Objects.requireNonNull(selfDeclaration, "selfDeclaration must not be null"),
                classRegistry
        );
    }

    /// @param selfDeclaration the enum member being evaluated, or `null` for callers that have no
    ///        self identity to exempt (legacy convenience entry points)
    public static @NotNull FrontendClassConstPrepassLookup forEnumPrePass(
            @NotNull FrontendClassConstPrepassContext classContext,
            @NotNull Map<String, Long> earlierEnumMembers,
            @Nullable Node selfDeclaration,
            @NotNull ClassRegistry classRegistry
    ) {
        return new FrontendClassConstPrepassLookup(
                Mode.ENUM_PREP_PASS,
                earlierEnumMembers,
                classContext,
                selfDeclaration,
                classRegistry
        );
    }

    @Override
    public @NotNull LookupResult resolve(@NotNull String name) {
        var earlierEnumValue = earlierEnumMembers.get(name);
        if (earlierEnumValue != null) {
            return new LookupResult.ResolvedConstant(new GdConstantValue.IntValue(earlierEnumValue));
        }
        // Usable published constants come first: a name that already owns a successful row is
        // always resolved before any structural mask can claim it.
        var usable = findUsableConstant(name);
        if (usable != null) {
            return usable;
        }
        if (classContext.rejectedConstNames().contains(name)) {
            return new LookupResult.Blocked(
                    "'" + name + "' is an earlier class constant whose initializer failed to evaluate"
            );
        }
        var declared = classContext.declaredMemberNames().get(name);
        if (declared != null) {
            return new LookupResult.Blocked(declaredMemberReason(name, declared));
        }
        return switch (classContext.ancestorMemberBlocker().check(name)) {
            case SCRIPT_ENUM -> new LookupResult.Blocked(
                    "'" + name + "' is an inherited enum constant; referencing it requires "
                            + "inheritance-order evaluation, which is not supported"
            );
            case SCRIPT_MEMBER -> new LookupResult.Blocked(
                    "'" + name + "' is an inherited member; constant initializers cannot "
                            + "reference inherited declarations"
            );
            case ENGINE_MEMBER -> new LookupResult.Blocked(
                    "'" + name + "' is an inherited engine/native member; constant initializers "
                            + "cannot reference inherited declarations"
            );
            case NONE -> resolveGlobalOrNamespace(name);
        };
    }

    /// Descends one member inside a namespace token produced by `resolve`. Named enum groups
    /// expose their members as frozen ints; engine class names expose metadata constants and
    /// enum values; builtin classes fall through to the shared metadata decoder.
    @Override
    public @NotNull LookupResult resolveMember(@NotNull Object namespaceIdentity, @NotNull String memberName) {
        if (namespaceIdentity instanceof GdScriptEnumGroup group) {
            var member = group.findMember(memberName);
            return member != null
                    ? new LookupResult.ResolvedConstant(new GdConstantValue.IntValue(member.value()))
                    : new LookupResult.Unknown();
        }
        if (namespaceIdentity instanceof GdObjectType objectType) {
            return resolveEngineClassMember(objectType.className(), memberName);
        }
        return LookupContext.super.resolveMember(namespaceIdentity, memberName);
    }

    @Override
    public @NotNull FrontendConstCallableRegistry callableRegistry() {
        // The prepass context shares the production registry: today it is the immutable empty
        // table, and any future populated table is consumed identically here.
        return FrontendConstCallableRegistry.empty();
    }

    @Override
    public @NotNull ClassRegistry classRegistry() {
        return classRegistry;
    }

    /// Maps one published constant row to this domain's answer. Declaration shapes outside the
    /// three produced by the skeleton are ignored defensively so an unexpected row can never be
    /// reinterpreted as a usable constant.
    private @Nullable LookupResult findUsableConstant(@NotNull String name) {
        for (var constant : classContext.usableConstants()) {
            if (!constant.name().equals(name)) {
                continue;
            }
            return switch (constant.declaration()) {
                case GdScriptEnumConstant enumConstant ->
                        new LookupResult.ResolvedConstant(new GdConstantValue.IntValue(enumConstant.value()));
                case GdScriptEnumGroup enumGroup -> mode == Mode.ENUM_PREP_PASS
                        ? new LookupResult.NonConstant(
                        "'" + name + "' is a named enum group (Dictionary), not an int constant")
                        : new LookupResult.ResolvedNamespace(enumGroup);
                case GdScriptEvaluatedConstant evaluated -> mode == Mode.ENUM_PREP_PASS
                        ? evaluatedIntOrNonConstant(name, evaluated)
                        : new LookupResult.ResolvedConstant(evaluated.value());
                default -> new LookupResult.NonConstant(
                        "'" + name + "' is declared on this class but is not a usable constant");
            };
        }
        return null;
    }

    /// Enum member initializers may only consume int payloads: an evaluated class const of
    /// another supported type exists but does not satisfy the int-only contract.
    private static @NotNull LookupResult evaluatedIntOrNonConstant(
            @NotNull String name,
            @NotNull GdScriptEvaluatedConstant evaluated
    ) {
        if (evaluated.value() instanceof GdConstantValue.IntValue intValue) {
            return new LookupResult.ResolvedConstant(intValue);
        }
        return new LookupResult.NonConstant(
                "'" + name + "' is a class constant of type '"
                        + evaluated.value().type().getTypeName() + "', not an int constant");
    }

    /// Reason phrases for declared-name hits, split by the kind of declaration so diagnostics
    /// stay honest about *why* the name cannot serve as a constant here.
    private @NotNull String declaredMemberReason(@NotNull String name, @NotNull Node declared) {
        if (declared == selfDeclaration) {
            return "'" + name + "' cannot reference its own declaration";
        }
        if (declared instanceof VariableDeclaration variable
                && variable.kind() == DeclarationKind.CONST) {
            return "'" + name + "' is declared later in this class; constant initializers can "
                    + "only reference earlier constants";
        }
        if (declared instanceof EnumMember || declared instanceof EnumDeclaration) {
            return "'" + name + "' is an enum member name declared in this class that is not "
                    + "usable here";
        }
        return "'" + name + "' is a declared class member, not a compile-time constant";
    }

    /// Global fallback: bare engine enum values, JSON global constants, and synthesized language
    /// constants are the only non-class names visible to initializers. Class-const mode then
    /// reopens the type-meta namespace so builtin/engine class names can descend via
    /// `resolveMember`; enum mode intentionally leaves them `Unknown`.
    private @NotNull LookupResult resolveGlobalOrNamespace(@NotNull String name) {
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
            return mode == Mode.ENUM_PREP_PASS
                    ? new LookupResult.NonConstant(
                    "'" + name + "' is a float language constant, not an int constant")
                    : new LookupResult.ResolvedConstant(
                    new GdConstantValue.FloatValue(languageConstant.value()));
        }
        if (mode == Mode.CLASS_CONST_INITIALIZER) {
            var typeMeta = classRegistry.resolveTypeMeta(name);
            if (typeMeta != null) {
                // Only genuine namespaces may descend or construct: a global enum's instance
                // type is `int`, so returning it as a namespace would let `Error(1)` sneak
                // through as an `int` constructor outside the whitelist. Class names and
                // named groups are honest non-constant answers instead of unknown identifiers.
                return switch (typeMeta.kind()) {
                    case BUILTIN, ENGINE_CLASS ->
                            new LookupResult.ResolvedNamespace(typeMeta.instanceType());
                    case GLOBAL_ENUM -> new LookupResult.NonConstant(
                            "'" + name + "' is a global enum, not a constant value");
                    case GDCC_CLASS -> new LookupResult.NonConstant(
                            "'" + name + "' is a script class, not a constant value");
                    case GDCC_ENUM -> new LookupResult.NonConstant(
                            "'" + name + "' is a named enum group (Dictionary), not a constant value");
                };
            }
        }
        return new LookupResult.Unknown();
    }

    /// Engine classes expose integer metadata constants and enum values qualified by class name
    /// (`Control.PRESET_CENTER`); anything else — including GDCC class names, which never reach
    /// the engine hierarchy finders — stays `Unknown` and is rejected by the caller domain.
    private @NotNull LookupResult resolveEngineClassMember(
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
