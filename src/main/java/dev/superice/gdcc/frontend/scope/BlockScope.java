package dev.superice.gdcc.frontend.scope;

import dev.superice.gdcc.scope.FunctionDef;
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

/// Frontend lexical scope for block-local bindings.
///
/// This layer is used for any AST node that introduces a nested local region, such as:
/// - ordinary statement blocks
/// - `if` / `while` branches
/// - loop iterators
/// - pattern bindings in future `match` support
///
/// In Phase 4 the block scope is intentionally small:
/// - value lookup only covers bindings defined in the current block
/// - function lookup always falls through to outer scopes
/// - type/meta lookup defaults to the parent chain unless the caller explicitly adds a local type
///
/// The follow-up restriction work simply forwards the restriction through this layer. Block-owned
/// locals/constants are always legal once found here because class-member static/instance rules do
/// not apply to block-local bindings.
public final class BlockScope extends AbstractFrontendScope {
    private final Map<String, ScopeValue> valuesByName = new LinkedHashMap<>();

    public BlockScope(@NotNull Scope parentScope) {
        super(Objects.requireNonNull(parentScope, "parentScope"));
    }

    /// Registers a mutable local binding owned by the current block.
    public void defineLocal(
            @NotNull String name,
            @NotNull GdType type,
            @Nullable Object declaration
    ) {
        defineValue(name, type, declaration, ScopeValueKind.LOCAL, false);
    }

    /// Registers a block-local constant.
    public void defineConstant(
            @NotNull String name,
            @NotNull GdType type,
            @Nullable Object declaration
    ) {
        defineValue(name, type, declaration, ScopeValueKind.CONSTANT, true);
    }

    @Override
    public @NotNull ScopeLookupResult<ScopeValue> resolveValueHere(
            @NotNull String name,
            @NotNull ResolveRestriction restriction
    ) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(restriction, "restriction");
        var value = valuesByName.get(name);
        return value != null ? ScopeLookupResult.foundAllowed(value) : ScopeLookupResult.notFound();
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

    private void defineValue(
            @NotNull String name,
            @NotNull GdType type,
            @Nullable Object declaration,
            @NotNull ScopeValueKind kind,
            boolean constant
    ) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(kind, "kind");
        var previous = valuesByName.putIfAbsent(name, new ScopeValue(name, type, kind, declaration, constant, false));
        if (previous != null) {
            throw duplicateNamespaceBinding("block value", name);
        }
    }
}
