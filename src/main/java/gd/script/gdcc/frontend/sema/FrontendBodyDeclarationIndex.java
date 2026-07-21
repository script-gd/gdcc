package gd.script.gdcc.frontend.sema;

import dev.superice.gdparser.frontend.ast.Node;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/// Interface-layer index of ordinary locals and for-iterators that already exist in baseline
/// `BlockScope` inventory.
///
/// The index is intentionally a view over published inventory, not a second declaration publisher.
/// `FrontendBodyStructuralCompleteness` requires the view to cover every published `LOCAL` binding in
/// the body scope; production body lookup uses declaration identity to verify that a scope local
/// belongs to this published inventory; source-range filtering still determines declaration-order
/// visibility.
///
/// For each `FOR_BODY` root the published list must contain exactly one iterator entry at position 0
/// with `sourceOrder == 0`; ordinary body locals follow at contiguous `sourceOrder >= 1`.
public final class FrontendBodyDeclarationIndex {
    private final @NotNull Map<Node, List<FrontendBodyLocalDeclaration>> declarationsByBodyRoot;
    private final @NotNull Map<Node, FrontendBodyLocalDeclaration> declarationsByDeclaration;

    public FrontendBodyDeclarationIndex(
            @NotNull Map<Node, List<FrontendBodyLocalDeclaration>> declarationsByBodyRoot
    ) {
        Objects.requireNonNull(declarationsByBodyRoot, "declarationsByBodyRoot");
        var copiedDeclarations = new IdentityHashMap<Node, List<FrontendBodyLocalDeclaration>>();
        var copiedDeclarationsByDeclaration = new IdentityHashMap<Node, FrontendBodyLocalDeclaration>();
        for (var entry : declarationsByBodyRoot.entrySet()) {
            var bodyRoot = Objects.requireNonNull(entry.getKey(), "bodyRoot");
            var declarations = List.copyOf(Objects.requireNonNull(entry.getValue(), "declarations"));
            copiedDeclarations.put(
                    bodyRoot,
                    declarations
            );
            for (var declaration : declarations) {
                var previous = copiedDeclarationsByDeclaration.put(
                        declaration.declaration(),
                        declaration
                );
                if (previous != null) {
                    throw new IllegalArgumentException("ordinary local declaration belongs to multiple body roots");
                }
            }
        }
        this.declarationsByBodyRoot = Collections.unmodifiableMap(copiedDeclarations);
        declarationsByDeclaration = Collections.unmodifiableMap(copiedDeclarationsByDeclaration);
    }

    public @NotNull List<FrontendBodyLocalDeclaration> declarationsFor(@NotNull Node bodyRoot) {
        return declarationsByBodyRoot.getOrDefault(Objects.requireNonNull(bodyRoot, "bodyRoot"), List.of());
    }

    public boolean containsBodyRoot(@NotNull Node bodyRoot) {
        return declarationsByBodyRoot.containsKey(Objects.requireNonNull(bodyRoot, "bodyRoot"));
    }

    /// Returns the published inventory entry for one source local or iterator declaration, if any.
    public @Nullable FrontendBodyLocalDeclaration declarationFor(@NotNull Node declaration) {
        return declarationsByDeclaration.get(Objects.requireNonNull(declaration, "declaration"));
    }

    public @NotNull Map<Node, List<FrontendBodyLocalDeclaration>> declarationsByBodyRoot() {
        return declarationsByBodyRoot;
    }
}
