package gd.script.gdcc.type;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/// Shared abstraction for all compiler-only storage types.
///
/// These types represent backend/runtime internal C storage and are never part of the
/// GDScript source-facing type system. They must only appear on LIR local variables and
/// backend-owned intrinsic operands/results, never on public ABI surfaces.
///
/// Each concrete type provides its own LIR-only text, C storage type name, and C init/destroy
/// helper names. The shared defaults encode the design invariants: no GDExtension metadata,
/// destroyable non-object lifecycle, and non-nullable value semantics.
public sealed interface GdCompilerType extends GdType
        permits GdccForRangeIterType, GdccForVariantIterType,
        GdccForStringIterType, GdccForArrayIterType, GdccForDictionaryIterType,
        GdccForPackedArrayIterType, GdccForFloatIterType, GdccCoroStateType {
    // GdccForPackedArrayIterType is one sealed permit covering all 10 Packed* families.

    /// LIR-only text grammar: `compiler::<Name>`, recognized solely by the LIR parser/serializer.
    @NotNull String getLirTypeText();

    /// C storage type name used in generated C declarations (e.g. `gdcc_for_range_iter`).
    @NotNull String getCStorageTypeName();

    /// C init helper function name for prepare-block initialization (e.g. `gdcc_for_range_iter_init`).
    @NotNull String getCInitHelperName();

    /// C destroy helper function name for lifecycle cleanup (e.g. `gdcc_for_range_iter_destroy`).
    @NotNull String getCDestroyHelperName();

    /// Compiler-only storage is passed to C helpers by address unless a concrete type explicitly
    /// opts into value passing. This keeps the current `&slot` ABI for internal helpers stable.
    default boolean isPassedByPointerInC() {
        return true;
    }

    /// Compiler-only assignment defaults to direct struct assignment.
    /// Concrete types that need deep-copy semantics must override this with a non-empty `gdcc_*`
    /// helper and return `false` from `isDirectStructAssignmentSafe()`.
    default @NotNull String getCCopyHelperName() {
        return "";
    }

    /// Compiler-only types are copyable by default. A move-only type (single-consumer ownership,
    /// e.g. `GdccCoroStateType`) overrides this with `false`: no copy operation exists at all, so
    /// neither direct struct assignment nor a copy helper is permitted, and any copy attempt must
    /// fail fast at the codegen boundary instead of silently duplicating ownership.
    default boolean isCopyable() {
        return true;
    }

    /// Direct struct assignment is the default compiler-only copy contract.
    /// Consumers read this semantic first instead of inferring behavior from an empty helper name.
    default boolean isDirectStructAssignmentSafe() {
        return getCCopyHelperName().isEmpty();
    }

    /// Defensive validation so future compiler-only types fail fast instead of silently falling back
    /// to the wrong `godot_*` copy path.
    default void validateCStorageContract() {
        var copyHelperName = getCCopyHelperName();
        if (!isCopyable()) {
            // Move-only types must not expose any copy channel: a direct assignment would
            // duplicate ownership bitwise, and a published copy helper would imply copyability.
            if (isDirectStructAssignmentSafe()) {
                throw new IllegalStateException(
                        "Move-only compiler-only type '" + getTypeName()
                                + "' must not enable direct struct assignment"
                );
            }
            if (!copyHelperName.isBlank()) {
                throw new IllegalStateException(
                        "Move-only compiler-only type '" + getTypeName()
                                + "' must not publish a copy helper: " + copyHelperName
                );
            }
            return;
        }
        if (isDirectStructAssignmentSafe() && !copyHelperName.isBlank()) {
            throw new IllegalStateException(
                    "Compiler-only type '" + getTypeName()
                            + "' must not publish a copy helper when direct struct assignment is enabled: "
                            + copyHelperName
            );
        }
        if (!isDirectStructAssignmentSafe() && copyHelperName.isBlank()) {
            throw new IllegalStateException(
                    "Compiler-only type '" + getTypeName()
                            + "' requires a non-empty copy helper when direct struct assignment is unsafe"
            );
        }
        if (copyHelperName.startsWith("godot_")) {
            throw new IllegalStateException(
                    "Compiler-only type '" + getTypeName()
                            + "' must not use godot_* copy helpers: " + copyHelperName
            );
        }
    }

    /// Compiler-only types carry no GDExtension metadata.
    @Override
    default @Nullable GdExtensionTypeEnum getGdExtensionType() {
        return null;
    }

    /// Compiler-only storage types are destroyable non-object values.
    @Override
    default boolean isDestroyable() {
        return true;
    }

    /// Compiler-only types are value-passed and non-nullable by design.
    @Override
    default boolean isNullable() {
        return false;
    }
}
