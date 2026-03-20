package dev.superice.gdcc.frontend.scope;

import dev.superice.gdcc.scope.CaptureDef;
import dev.superice.gdcc.scope.FunctionDef;
import dev.superice.gdcc.scope.ParameterDef;
import dev.superice.gdcc.scope.ResolveRestriction;
import dev.superice.gdcc.scope.Scope;
import dev.superice.gdcc.scope.ScopeLookupResult;
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
/// `self` is intentionally not modeled as an implicit value binding here.
///
/// This scope now participates in restriction-aware lookup, but parameters/captures themselves are
/// not filtered by class-member static/instance restrictions. The frontend binder still owns the
/// policy decision of whether `self` should be explicit or represented by a dedicated binding kind.
public final class CallableScope extends AbstractFrontendScope {
    private final @NotNull CallableScopeKind kind;
    private final Map<String, ScopeValue> parametersByName = new LinkedHashMap<>();
    private final Map<String, ScopeValue> capturesByName = new LinkedHashMap<>();

    public CallableScope(@NotNull Scope parentScope, @NotNull CallableScopeKind kind) {
        super(Objects.requireNonNull(parentScope, "parentScope"));
        this.kind = Objects.requireNonNull(kind, "kind");
    }

    /// Returns the semantic source that created this callable boundary.
    public @NotNull CallableScopeKind kind() {
        return kind;
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
        parametersByName.put(name, new ScopeValue(name, type, ScopeValueKind.PARAMETER, declaration, false, true, false));
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
        capturesByName.put(name, new ScopeValue(name, type, ScopeValueKind.CAPTURE, declaration, false, true, false));
    }

    @Override
    public @NotNull ScopeLookupResult<ScopeValue> resolveValueHere(
            @NotNull String name,
            @NotNull ResolveRestriction restriction
    ) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(restriction, "restriction");

        // Parameters outrank captures in the callable layer.
        //
        // In real GDScript this shape appears when an inner callable would otherwise capture a name
        // from an outer scope, but the current callable already declares the same parameter.
        var parameter = parametersByName.get(name);
        if (parameter != null) {
            return ScopeLookupResult.foundAllowed(parameter);
        }
        var capture = capturesByName.get(name);
        return capture != null ? ScopeLookupResult.foundAllowed(capture) : ScopeLookupResult.notFound();
    }

    @Override
    public @NotNull ScopeLookupResult<List<FunctionDef>> resolveFunctionsHere(
            @NotNull String name,
            @NotNull ResolveRestriction restriction
    ) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(restriction, "restriction");
        return ScopeLookupResult.notFound();
    }

    private void ensureCallableValueSlotAvailable(@NotNull String name) {
        if (parametersByName.containsKey(name) || capturesByName.containsKey(name)) {
            throw duplicateNamespaceBinding("callable value", name);
        }
    }
}
