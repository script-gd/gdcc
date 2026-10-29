package gd.script.gdcc.frontend.sema.analyzer.support;

import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/// Metadata-validating `FrontendConstCallableRegistry.Builder` (const plan §5.5).
///
/// Registration is a trusted built-in operation guarded against mispatching rather than a
/// source-facing feature: a key only lands when ordinary signature selection reproduces it
/// exactly and the metadata method is marked `is_const`. Violations throw programmer guards;
/// frozen registries are `Map`-backed and immutable.
final class FrontendConstCallableRegistryBuilder implements FrontendConstCallableRegistry.Builder {

    private final @NotNull ClassRegistry classRegistry;
    private final @NotNull Map<ConstCallableKey, FrontendConstCallableRegistry.Entry> entries;
    private boolean frozen;

    FrontendConstCallableRegistryBuilder(@NotNull ClassRegistry classRegistry) {
        this.classRegistry = Objects.requireNonNull(classRegistry, "classRegistry must not be null");
        this.entries = new LinkedHashMap<>();
    }

    @Override
    public @NotNull FrontendConstCallableRegistry.Builder register(
            @NotNull ConstCallableKey key,
            @NotNull GdType returnType,
            @NotNull ConstMemberEvaluator evaluator
    ) {
        if (frozen) {
            throw new IllegalStateException("const callable registry is already frozen");
        }
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(returnType, "returnType must not be null");
        Objects.requireNonNull(evaluator, "evaluator must not be null");
        if (key.invocationKind() != ConstCallableKey.InvocationKind.INSTANCE_METHOD) {
            throw new IllegalArgumentException(
                    "const callable kind '" + key.invocationKind() + "' is not admitted"
            );
        }
        if (entries.containsKey(key)) {
            throw new IllegalArgumentException(
                    "duplicate const callable signature " + describeKey(key)
            );
        }
        // The registered signature must be exactly what ordinary selection picks for the
        // declared parameter types — anything else is an invalid metadata claim, not a source
        // error, so it is rejected as a programmer guard.
        var selection = FrontendConstCallSelectionSupport.select(
                classRegistry, key.ownerType(), key.methodName(), key.parameterTypes());
        if (!(selection instanceof LookupContext.SelectionResult.Selected selected)
                || !selected.key().equals(key)) {
            throw new IllegalArgumentException(
                    "no selectable builtin method signature matches " + describeKey(key)
            );
        }
        if (!FrontendConstCallSelectionSupport.sameExactType(selected.returnType(), returnType)) {
            throw new IllegalArgumentException(
                    "registered return type '" + returnType.getTypeName()
                            + "' does not match the selected signature's return type '"
                            + selected.returnType().getTypeName() + "' for " + describeKey(key)
            );
        }
        if (!isMarkedConst(key)) {
            throw new IllegalArgumentException(
                    "method '" + key.methodName() + "' on '" + key.ownerType().getTypeName()
                            + "' is not marked is_const in extension metadata"
            );
        }
        entries.put(key, new FrontendConstCallableRegistry.Entry(key, returnType, evaluator));
        return this;
    }

    @Override
    public @NotNull FrontendConstCallableRegistry build() {
        frozen = true;
        return new FrozenRegistry(entries);
    }

    /// `is_const` is checked against the exact metadata overload the key claims — matching name,
    /// non-static, non-vararg, and parameter types identical to the selected declared types.
    private boolean isMarkedConst(@NotNull ConstCallableKey key) {
        var builtinClass = classRegistry.findBuiltinClass(key.ownerType().getTypeName());
        if (builtinClass == null) {
            return false;
        }
        for (var method : builtinClass.methods()) {
            if (method == null || method.isStatic() || method.isVararg()
                    || !method.name().equals(key.methodName())) {
                continue;
            }
            var parameters = method.getParameters();
            if (parameters.size() != key.parameterTypes().size()) {
                continue;
            }
            var matches = true;
            for (var index = 0; index < parameters.size(); index++) {
                if (!FrontendConstCallSelectionSupport.sameExactType(
                        parameters.get(index).getType(), key.parameterTypes().get(index))) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return method.isConst();
            }
        }
        return false;
    }

    private static @NotNull String describeKey(@NotNull ConstCallableKey key) {
        var signature = new StringBuilder(key.methodName()).append('(');
        for (var index = 0; index < key.parameterTypes().size(); index++) {
            if (index > 0) {
                signature.append(", ");
            }
            signature.append(key.parameterTypes().get(index).getTypeName());
        }
        return key.ownerType().getTypeName() + "." + signature.append(')');
    }

    /// Immutable `Map`-backed registry produced by `build()`.
    private record FrozenRegistry(
            @NotNull Map<ConstCallableKey, FrontendConstCallableRegistry.Entry> entries
    ) implements FrontendConstCallableRegistry {
        private FrozenRegistry {
            entries = Map.copyOf(entries);
        }

        @Override
        public @Nullable Entry find(@NotNull ConstCallableKey key) {
            return entries.get(key);
        }
    }
}
