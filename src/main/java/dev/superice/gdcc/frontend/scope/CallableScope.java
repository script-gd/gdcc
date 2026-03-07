package dev.superice.gdcc.frontend.scope;

import dev.superice.gdcc.scope.CaptureDef;
import dev.superice.gdcc.scope.FunctionDef;
import dev.superice.gdcc.scope.ParameterDef;
import dev.superice.gdcc.scope.Scope;
import dev.superice.gdcc.scope.ScopeValue;
import dev.superice.gdcc.scope.ScopeValueKind;
import dev.superice.gdcc.type.GdType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/// Frontend lexical scope for functions, constructors, and lambdas.
///
/// The callable layer models bindings that belong to the callable boundary itself:
/// - parameters
/// - captures
/// - future callable-local type aliases through the inherited `defineTypeMeta(...)` API
///
/// `self` is intentionally not modeled as an implicit value binding in Phase 4.
/// The binder still owns the policy decision of whether `self` should be explicit, forbidden in
/// static contexts, or represented by a dedicated binding kind.
public final class CallableScope extends AbstractFrontendScope {
    private final Map<String, ScopeValue> parametersByName = new LinkedHashMap<>();
    private final Map<String, ScopeValue> capturesByName = new LinkedHashMap<>();

    public CallableScope(@NotNull Scope parentScope) {
        super(Objects.requireNonNull(parentScope, "parentScope"));
    }

    /// Registers a parameter binding owned by the current callable.
    public void defineParameter(@NotNull ParameterDef parameter) {
        Objects.requireNonNull(parameter, "parameter");
        defineParameter(parameter.getName(), parameter.getType(), parameter);
    }

    /// Registers a parameter binding owned by the current callable.
    public void defineParameter(
            @NotNull String name,
            @NotNull GdType type,
            @Nullable Object declaration
    ) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        ensureCallableValueSlotAvailable(name);
        parametersByName.put(name, new ScopeValue(name, type, ScopeValueKind.PARAMETER, declaration, false, false));
    }

    /// Registers a capture imported from an outer callable.
    public void defineCapture(@NotNull CaptureDef capture) {
        Objects.requireNonNull(capture, "capture");
        defineCapture(capture.getName(), capture.getType(), capture);
    }

    /// Registers a capture imported from an outer callable.
    public void defineCapture(
            @NotNull String name,
            @NotNull GdType type,
            @Nullable Object declaration
    ) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        ensureCallableValueSlotAvailable(name);
        capturesByName.put(name, new ScopeValue(name, type, ScopeValueKind.CAPTURE, declaration, false, false));
    }

    @Override
    public @Nullable ScopeValue resolveValueHere(@NotNull String name) {
        Objects.requireNonNull(name, "name");
        // Parameters outrank captures in the callable layer.
        //
        // In real GDScript this shape appears when an inner callable would otherwise capture a name
        // from an outer scope, but the current callable already declares the same parameter.
        var parameter = parametersByName.get(name);
        if (parameter != null) {
            return parameter;
        }
        return capturesByName.get(name);
    }

    @Override
    public @NotNull List<? extends FunctionDef> resolveFunctionsHere(@NotNull String name) {
        Objects.requireNonNull(name, "name");
        return List.of();
    }

    private void ensureCallableValueSlotAvailable(@NotNull String name) {
        if (parametersByName.containsKey(name) || capturesByName.containsKey(name)) {
            throw duplicateNamespaceBinding("callable value", name);
        }
    }
}
