package gd.script.gdcc.frontend.sema;

import dev.superice.gdparser.frontend.ast.Block;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/// Body-entry roots produced by the Interface phase.
///
/// Only structurally supported roots listed here are legal for `FrontendSuiteResolver` to enter.
public record FrontendSuiteEntryRoots(
        @NotNull List<Node> callableOwners,
        @NotNull List<VariableDeclaration> propertyInitializers,
        @NotNull List<Block> supportedBlocks,
        @NotNull Map<Node, Path> sourcePathsByEntryRoot
) {
    public FrontendSuiteEntryRoots(
            @NotNull List<Node> callableOwners,
            @NotNull List<VariableDeclaration> propertyInitializers,
            @NotNull List<Block> supportedBlocks
    ) {
        this(callableOwners, propertyInitializers, supportedBlocks, Map.of());
    }

    public FrontendSuiteEntryRoots {
        callableOwners = List.copyOf(Objects.requireNonNull(callableOwners, "callableOwners"));
        propertyInitializers = List.copyOf(Objects.requireNonNull(propertyInitializers, "propertyInitializers"));
        supportedBlocks = List.copyOf(Objects.requireNonNull(supportedBlocks, "supportedBlocks"));
        sourcePathsByEntryRoot = copyIdentityPathMap(sourcePathsByEntryRoot);
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

    public @Nullable Path sourcePathFor(@NotNull Node entryRoot) {
        return sourcePathsByEntryRoot.get(Objects.requireNonNull(entryRoot, "entryRoot"));
    }

    private static <T> @NotNull Set<T> identitySet(@NotNull List<T> values) {
        var set = java.util.Collections.newSetFromMap(new IdentityHashMap<T, Boolean>());
        set.addAll(values);
        return set;
    }

    private static @NotNull Map<Node, Path> copyIdentityPathMap(@NotNull Map<Node, Path> sourcePathsByEntryRoot) {
        var copiedPaths = new IdentityHashMap<Node, Path>();
        for (var entry : Objects.requireNonNull(sourcePathsByEntryRoot, "sourcePathsByEntryRoot").entrySet()) {
            copiedPaths.put(
                    Objects.requireNonNull(entry.getKey(), "entryRoot"),
                    Objects.requireNonNull(entry.getValue(), "sourcePath")
            );
        }
        return Collections.unmodifiableMap(copiedPaths);
    }
}
