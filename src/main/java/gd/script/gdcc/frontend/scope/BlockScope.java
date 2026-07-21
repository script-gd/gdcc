package gd.script.gdcc.frontend.scope;

import gd.script.gdcc.scope.FunctionDef;
import gd.script.gdcc.scope.ResolveRestriction;
import gd.script.gdcc.scope.Scope;
import gd.script.gdcc.scope.ScopeLookupResult;
import gd.script.gdcc.scope.ScopeValue;
import gd.script.gdcc.scope.ScopeValueKind;
import gd.script.gdcc.type.GdType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnmodifiableView;

import java.util.Collection;
import java.util.Collections;
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
/// The block scope is intentionally small:
/// - value lookup only covers bindings defined in the current block
/// - function lookup always falls through to outer scopes
/// - type/meta lookup defaults to the parent chain unless the caller explicitly adds a local type
///
/// Restriction-aware lookup simply forwards the restriction through this layer. Block-owned
/// locals/constants are always legal once found here because class-member static/instance rules do
/// not apply to block-local bindings.
public final class BlockScope extends AbstractFrontendScope {
    private final @NotNull BlockScopeKind kind;
    private final Map<String, ScopeValue> valuesByName = new LinkedHashMap<>();

    public BlockScope(@NotNull Scope parentScope, @NotNull BlockScopeKind kind) {
        super(Objects.requireNonNull(parentScope, "parentScope"));
        this.kind = Objects.requireNonNull(kind, "kind");
    }

    /// Returns the semantic source that created this lexical block boundary.
    public @NotNull BlockScopeKind kind() {
        return kind;
    }

    /// Returns an unmodifiable view of all value bindings owned by this block.
    ///
    /// Insertion order matches the order in which locals and constants were published. Callers that need
    /// the Interface-phase body inventory should filter to [ScopeValueKind#LOCAL]; block-local constants
    /// may appear here once supported, but are not part of the current body declaration index.
    public @NotNull @UnmodifiableView Collection<ScopeValue> localValues() {
        return Collections.unmodifiableCollection(valuesByName.values());
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

    /// Rewrites the type of an already-published mutable local binding.
    ///
    /// - only an existing block-local `LOCAL` binding with the same declaration identity can be rewritten
    /// - missing, mismatched, or non-local names remain a quiet no-op because earlier phases may
    ///   already have rejected the declaration with a source diagnostic, and the expression-typing
    ///   phase must stay fail-closed instead of mutating some other surviving binding
    /// - nested same-name shadow locals stay isolated because child blocks own a different
    ///   `BlockScope`; same-callable shadows that earlier phases reject therefore leave no current-layer
    ///   binding to rewrite, and even within one block declaration-identity matching prevents mutating a
    ///   sibling binding that happens to share the same source name
    /// - preserving the original declaration object keeps later use-site bindings pointing at the
    ///   same `VariableDeclaration`, so initializer provenance remains recoverable after backfill
    public void resetLocalType(
            @NotNull String name,
            @NotNull Object declaration,
            @NotNull GdType type
    ) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(declaration, "declaration");
        Objects.requireNonNull(type, "type");
        var existing = valuesByName.get(name);
        if (existing == null
                || existing.kind() != ScopeValueKind.LOCAL
                || existing.declaration() != declaration) {
            return;
        }
        valuesByName.put(
                name,
                new ScopeValue(
                        existing.name(),
                        type,
                        existing.kind(),
                        existing.declaration(),
                        existing.constant(),
                        existing.writable(),
                        existing.staticMember()
                )
        );
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
        var previous = valuesByName.putIfAbsent(
                name,
                new ScopeValue(name, type, kind, declaration, constant, !constant, false)
        );
        if (previous != null) {
            throw duplicateNamespaceBinding("block value", name);
        }
    }
}
