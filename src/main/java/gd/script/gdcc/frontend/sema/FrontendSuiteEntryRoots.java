package gd.script.gdcc.frontend.sema;

import dev.superice.gdparser.frontend.ast.Block;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import org.jetbrains.annotations.NotNull;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/// Body-entry roots produced by the Interface phase.
///
/// Only roots listed here are legal for the future `SuiteResolver` to enter. Typed-dependent bodies
/// are deliberately excluded while their `FrontendInventoryGate` remains unpublished.
public record FrontendSuiteEntryRoots(
        @NotNull List<Node> callableOwners,
        @NotNull List<VariableDeclaration> propertyInitializers,
        @NotNull List<Block> supportedBlocks
) {
    public FrontendSuiteEntryRoots {
        callableOwners = List.copyOf(Objects.requireNonNull(callableOwners, "callableOwners"));
        propertyInitializers = List.copyOf(Objects.requireNonNull(propertyInitializers, "propertyInitializers"));
        supportedBlocks = List.copyOf(Objects.requireNonNull(supportedBlocks, "supportedBlocks"));
    }

    public boolean containsSupportedBlock(@NotNull Block block) {
        return identitySet(supportedBlocks).contains(Objects.requireNonNull(block, "block"));
    }

    public boolean containsCallableOwner(@NotNull Node callableOwner) {
        return identitySet(callableOwners).contains(Objects.requireNonNull(callableOwner, "callableOwner"));
    }

    public boolean containsPropertyInitializer(@NotNull VariableDeclaration property) {
        return identitySet(propertyInitializers).contains(Objects.requireNonNull(property, "property"));
    }

    private static <T> @NotNull Set<T> identitySet(@NotNull List<T> values) {
        var set = java.util.Collections.newSetFromMap(new IdentityHashMap<T, Boolean>());
        set.addAll(values);
        return set;
    }
}
