package gd.script.gdcc.frontend.lowering;

import gd.script.gdcc.type.GdArrayType;
import gd.script.gdcc.type.GdDictionaryType;
import gd.script.gdcc.type.GdCompilerType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdPackedArrayType;
import gd.script.gdcc.type.GdPrimitiveType;
import gd.script.gdcc.type.GdType;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/// Shared truth source for which statically known carrier families still need reverse writeback.
///
/// The contract comes from `doc/gdcc_type_system.md` and is consumed by assignment lowering,
/// mutating-receiver writeback, and runtime-gated routes:
/// - shared/reference carriers (`Array`, `Dictionary`, `Object`, primitive slots) do not write back
/// - value-semantic builtin carriers do write back
/// - `Variant` currently returns `true` here because the static shortcut only skips families already
///   proven shared; the runtime helper refines the unknown branch later
///
/// Packed*Array is Variant-backed (holder copies share the engine-side array identity), so its
/// answer additionally depends on the route provenance: routes whose writeback would persist a
/// detached getter copy or re-store the same identity are exempt, while routes whose writeback is
/// a redundant-but-harmless same-identity store keep the legacy answer to minimize migration risk.
public final class FrontendWritableTypeWritebackSupport {
    /// Which writable route is asking for a reverse-commit decision. The family matrix alone cannot
    /// answer for Packed*Array anymore, so every call site must declare its route.
    public enum WritebackRouteProvenance {
        /// Terminal snapshot commit for a bare direct-slot receiver (local/parameter/capture root).
        /// Packed snapshot temps are Variant holder copies sharing identity with the source slot, so
        /// the post-call self-assign would be redundant.
        DIRECT_SLOT,
        /// Bare static property route: whether the terminal leaf must be promoted into a commit
        /// step. Packed static storage shares identity with the loaded value, so no promotion is
        /// needed (and promoting would trip the static-terminal contract).
        STATIC_PROPERTY,
        /// Builtin engine property commit reached through a mutating receiver *call*. The engine
        /// getter returns a detached copy and the interpreter does not persist the mutation, so
        /// writing the carrier back would wrongly persist it. Assignment routes on the same
        /// properties are a different surface (the interpreter persists subscript assignment
        /// through read-modify-write) and must use {@link #GENERIC} instead.
        ENGINE_PROPERTY_CALL,
        /// GDCC script instance property commit. Packed writeback is a redundant same-identity
        /// store; kept deliberately (harmless, lower migration risk).
        SCRIPT_PROPERTY,
        /// Array/Dictionary element commit. Kept for the same reason as {@link #SCRIPT_PROPERTY}.
        CONTAINER_ELEMENT,
        /// Assignment-driven and runtime-open routes keep the legacy family answer for packed.
        GENERIC
    }

    private FrontendWritableTypeWritebackSupport() {
    }

    public static boolean requiresReverseCommitForCarrierType(
            @NotNull GdType carrierType,
            @NotNull WritebackRouteProvenance provenance
    ) {
        Objects.requireNonNull(provenance, "provenance must not be null");
        return switch (Objects.requireNonNull(carrierType, "carrierType must not be null")) {
            case GdCompilerType _ -> throw new IllegalArgumentException(
                    "compiler-only type leaked into frontend writeback analysis: " + carrierType.getTypeName()
            );
            // Packed*Array must be answered before the shared-family rule: it is Variant-backed
            // (identity-shared) but keeps legacy writeback on the retained redundant routes.
            case GdPackedArrayType _ -> switch (provenance) {
                case DIRECT_SLOT, STATIC_PROPERTY, ENGINE_PROPERTY_CALL -> false;
                case SCRIPT_PROPERTY, CONTAINER_ELEMENT, GENERIC -> true;
            };
            case GdPrimitiveType _, GdObjectType _, GdArrayType _, GdDictionaryType _ -> false;
            default -> true;
        };
    }

    /// Whether a mutating call on a bare direct-slot receiver that stayed on the temp-snapshot
    /// surface still needs the terminal `DIRECT_SLOT` commit step. This publishing decision is
    /// deliberately narrower than the family matrix: Packed*Array is the sole exemption because
    /// its snapshot shares identity with the source slot, while every other family keeps the
    /// historical unconditional step (a redundant self-assign for shared carriers).
    public static boolean requiresDirectSlotSnapshotCommit(@NotNull GdType carrierType) {
        return switch (Objects.requireNonNull(carrierType, "carrierType must not be null")) {
            case GdCompilerType _ -> throw new IllegalArgumentException(
                    "compiler-only type leaked into frontend writeback analysis: " + carrierType.getTypeName()
            );
            case GdPackedArrayType _ -> false;
            default -> true;
        };
    }
}
