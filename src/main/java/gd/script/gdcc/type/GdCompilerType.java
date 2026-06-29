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
        permits GdccForRangeIterType {

    /// LIR-only text grammar: `compiler::<Name>`, recognized solely by the LIR parser/serializer.
    @NotNull String getLirTypeText();

    /// C storage type name used in generated C declarations (e.g. `gdcc_for_range_iter`).
    @NotNull String getCStorageTypeName();

    /// C init helper function name for prepare-block initialization (e.g. `gdcc_for_range_iter_init`).
    @NotNull String getCInitHelperName();

    /// C destroy helper function name for lifecycle cleanup (e.g. `gdcc_for_range_iter_destroy`).
    @NotNull String getCDestroyHelperName();

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
