package gd.script.gdcc.backend.c.gen.fatptr;

import gd.script.gdcc.backend.c.gen.binding.GodotBindingSupport;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.scope.RefCountedStatus;
import gd.script.gdcc.type.GdObjectType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// Immutable declaration spec for one static object type's fat pointer.
/// The backend uses this as the internal storage/parameter/return representation for object values.
public record ObjectFatPtrSpec(
        @NotNull GdObjectType objectType,
        @NotNull String canonicalClassName,
        @NotNull String cIdentifier,
        @NotNull String fatPtrTypeName,
        @NotNull String pointerCType,
        @NotNull Kind kind,
        @NotNull RefCountedStatus refCountedStatus,
        @Nullable String objectPtrHelperName
) {
    public enum Kind {
        ENGINE,
        GDCC
    }

    public ObjectFatPtrSpec {
        Objects.requireNonNull(objectType);
        Objects.requireNonNull(canonicalClassName);
        Objects.requireNonNull(cIdentifier);
        Objects.requireNonNull(fatPtrTypeName);
        Objects.requireNonNull(pointerCType);
        Objects.requireNonNull(kind);
        Objects.requireNonNull(refCountedStatus);
        if (kind == Kind.GDCC && objectPtrHelperName == null) {
            throw new IllegalArgumentException("GDCC object fat pointer spec requires objectPtrHelperName");
        }
        if (kind == Kind.ENGINE && objectPtrHelperName != null) {
            throw new IllegalArgumentException("Engine object fat pointer spec must not have objectPtrHelperName");
        }
        // The object fat pointer template concatenates this field directly with the `ptr` member name.
        if (!pointerCType.endsWith(" *")) {
            throw new IllegalArgumentException("pointerCType must end with ' *' for template concatenation: " + pointerCType);
        }
    }

    /// Builds a spec for a known engine or GDCC object type.
    /// Unknown object types fail fast with the surface that exposed them; they must not fall back to a raw ABI pointer.
    public static @NotNull ObjectFatPtrSpec forObjectType(
            @NotNull ClassRegistry classRegistry,
            @NotNull GdObjectType objectType,
            @NotNull String surface
    ) {
        var className = objectType.getTypeName();
        var cIdentifier = GodotBindingSupport.cIdentifier(className);
        var fatPtrTypeName = "gdcc_" + cIdentifier + "_fat_ptr";
        var refCountedStatus = classRegistry.getRefCountedStatus(objectType);
        if (objectType.checkEngineType(classRegistry)) {
            return new ObjectFatPtrSpec(
                    objectType,
                    className,
                    cIdentifier,
                    fatPtrTypeName,
                    "godot_" + className + " *",
                    Kind.ENGINE,
                    refCountedStatus,
                    null
            );
        }
        if (objectType.checkGdccType(classRegistry)) {
            return new ObjectFatPtrSpec(
                    objectType,
                    className,
                    cIdentifier,
                    fatPtrTypeName,
                    className + " *",
                    Kind.GDCC,
                    refCountedStatus,
                    className + "_object_ptr"
            );
        }
        throw new IllegalStateException(
                "Unknown object type '" + className + "' at " + surface +
                        "; object fat pointer declaration requires a registered engine or GDCC class"
        );
    }
}
