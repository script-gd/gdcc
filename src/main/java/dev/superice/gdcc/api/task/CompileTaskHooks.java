package dev.superice.gdcc.api.task;

import dev.superice.gdcc.api.CompileTaskSnapshot;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.function.Consumer;

/// Optional hook points around compile-task snapshot transitions.
public final class CompileTaskHooks {
    private static final @NotNull CompileTaskHooks NONE = new CompileTaskHooks(null);

    private final @Nullable Consumer<CompileTaskSnapshot> afterSnapshotUpdate;

    private CompileTaskHooks(@Nullable Consumer<CompileTaskSnapshot> afterSnapshotUpdate) {
        this.afterSnapshotUpdate = afterSnapshotUpdate;
    }

    public static @NotNull CompileTaskHooks none() {
        return NONE;
    }

    public static @NotNull CompileTaskHooks afterSnapshotUpdate(
            @NotNull Consumer<CompileTaskSnapshot> afterSnapshotUpdate
    ) {
        return new CompileTaskHooks(Objects.requireNonNull(
                afterSnapshotUpdate,
                "afterSnapshotUpdate must not be null"
        ));
    }

    void afterSnapshotUpdate(@NotNull CompileTaskSnapshot snapshot) {
        if (afterSnapshotUpdate != null) {
            afterSnapshotUpdate.accept(snapshot);
        }
    }
}
