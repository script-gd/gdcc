package gd.script.gdcc.frontend.sema;

import dev.superice.gdparser.frontend.ast.Node;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/// Interface-layer index of ordinary locals that already exist in baseline `BlockScope` inventory.
///
/// The index is intentionally a view over published inventory, not a second declaration publisher.
/// Unsupported typed-dependent bodies therefore have no entries until their gate-owned inventory is
/// explicitly published by a later body phase.
public final class FrontendBodyDeclarationIndex {
    private final @NotNull Map<Node, List<FrontendBodyLocalDeclaration>> declarationsByBodyRoot;

    public FrontendBodyDeclarationIndex(
            @NotNull Map<Node, List<FrontendBodyLocalDeclaration>> declarationsByBodyRoot
    ) {
        Objects.requireNonNull(declarationsByBodyRoot, "declarationsByBodyRoot");
        var copiedDeclarations = new IdentityHashMap<Node, List<FrontendBodyLocalDeclaration>>();
        for (var entry : declarationsByBodyRoot.entrySet()) {
            copiedDeclarations.put(
                    Objects.requireNonNull(entry.getKey(), "bodyRoot"),
                    List.copyOf(Objects.requireNonNull(entry.getValue(), "declarations"))
            );
        }
        this.declarationsByBodyRoot = Collections.unmodifiableMap(copiedDeclarations);
    }

    public @NotNull List<FrontendBodyLocalDeclaration> declarationsFor(@NotNull Node bodyRoot) {
        return declarationsByBodyRoot.getOrDefault(Objects.requireNonNull(bodyRoot, "bodyRoot"), List.of());
    }

    public boolean containsBodyRoot(@NotNull Node bodyRoot) {
        return declarationsByBodyRoot.containsKey(Objects.requireNonNull(bodyRoot, "bodyRoot"));
    }

    public @NotNull Map<Node, List<FrontendBodyLocalDeclaration>> declarationsByBodyRoot() {
        return declarationsByBodyRoot;
    }
}
