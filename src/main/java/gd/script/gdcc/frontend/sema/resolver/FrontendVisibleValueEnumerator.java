package gd.script.gdcc.frontend.sema.resolver;

import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.Parameter;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import gd.script.gdcc.scope.Scope;
import gd.script.gdcc.scope.ScopeValue;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/// Enumerates the value names visible at a use site along the lexical scope chain.
///
/// This is the enumeration counterpart of `FrontendVisibleValueResolver`'s single-name lookup,
/// built for completion-style tooling (`frontend_lsp_foundation_implementation.md` §2.5). The
/// declaration-after-use
/// byte-order filter runs per layer before nearest-layer shadowing, so an invisible inner local
/// never shadows a visible outer binding of the same name: a local `VariableDeclaration` is
/// visible only when its declaration range ends at or before the use-site start
/// (`endByte <= startByte`, the same comparison as
/// `FrontendVisibleValueResolver.isVisibleLocal`), which also covers self-reference inside the
/// declaration's own initializer. `Parameter`-backed values stay visible regardless of source
/// order, while every `VariableDeclaration`-backed value — locals and lambda captures alike — is
/// order-sensitive because capture legality follows the outer declaration order (resolver parity).
/// Class members and constants stay visible regardless of source order, matching Godot's member
/// visibility.
public final class FrontendVisibleValueEnumerator {
    /// This class is a stateless enumeration utility and is not instantiated.
    private FrontendVisibleValueEnumerator() {
    }

    /// Enumerates the values visible at `useSite` from `scope`, applying the
    /// declaration-after-use byte-order filter per layer before nearest-layer shadowing.
    public static @NotNull List<ScopeValue> enumerateVisibleValues(@NotNull Scope scope, @NotNull Node useSite) {
        Objects.requireNonNull(useSite, "useSite must not be null");
        return enumerateVisibleValues(scope, useSite.range().startByte());
    }

    /// Byte-offset variant for use sites that do not map onto a single AST node (e.g. a
    /// zero-width completion cursor): a local declaration is visible exactly when its range
    /// ends at or before `useSiteByteOffset`.
    public static @NotNull List<ScopeValue> enumerateVisibleValues(@NotNull Scope scope, int useSiteByteOffset) {
        Objects.requireNonNull(scope, "scope must not be null");
        var visibleByName = new LinkedHashMap<String, ScopeValue>();
        Scope current = scope;
        while (current != null) {
            for (var value : current.valuesHere()) {
                if (isNotYetDeclared(value, useSiteByteOffset)) {
                    continue;
                }
                visibleByName.putIfAbsent(value.name(), value);
            }
            current = current.enumerationParentScope();
        }
        return List.copyOf(visibleByName.values());
    }

    /// The shared declaration-after-use byte-order rule (same comparison as
    /// `FrontendVisibleValueResolver.isVisibleLocal`): `Parameter`-backed values are exempt, and
    /// every `VariableDeclaration`-backed value — locals and lambda captures alike — is
    /// order-sensitive because capture legality follows the outer declaration order.
    private static boolean isNotYetDeclared(@NotNull ScopeValue value, int useSiteByteOffset) {
        return !(value.declaration() instanceof Parameter)
                && value.declaration() instanceof VariableDeclaration declaration
                && declaration.range().endByte() > useSiteByteOffset;
    }
}
