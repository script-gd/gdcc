package gd.script.gdcc.backend.c.gen.fatptr;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/// Immutable spec for one generated fat pointer upcast helper.
/// The helper preserves the source instance ID and rebuilds the target typed pointer
/// from the validated live raw pointer; dead sources produce a target with NULL pointer.
public record ObjectFatPtrUpcastSpec(
        @NotNull ObjectFatPtrSpec source,
        @NotNull ObjectFatPtrSpec target,
        @NotNull String helperName
) {
    public ObjectFatPtrUpcastSpec {
        Objects.requireNonNull(source);
        Objects.requireNonNull(target);
        Objects.requireNonNull(helperName);
        if (source.fatPtrTypeName().equals(target.fatPtrTypeName())) {
            throw new IllegalArgumentException("Upcast spec requires distinct source and target fat pointer types");
        }
    }

    public static @NotNull ObjectFatPtrUpcastSpec forPair(@NotNull ObjectFatPtrSpec source, @NotNull ObjectFatPtrSpec target) {
        return new ObjectFatPtrUpcastSpec(
                source,
                target,
                "gdcc_" + source.cIdentifier() + "_fat_ptr_upcast_to_" + target.cIdentifier()
        );
    }
}
