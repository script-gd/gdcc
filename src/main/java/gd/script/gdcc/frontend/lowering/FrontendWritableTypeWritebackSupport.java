package gd.script.gdcc.frontend.lowering;

import gd.script.gdcc.type.GdArrayType;
import gd.script.gdcc.type.GdDictionaryType;
import gd.script.gdcc.type.GdCompilerType;
import gd.script.gdcc.type.GdObjectType;
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
public final class FrontendWritableTypeWritebackSupport {
    private FrontendWritableTypeWritebackSupport() {
    }

    public static boolean requiresReverseCommitForCarrierType(@NotNull GdType carrierType) {
        return switch (Objects.requireNonNull(carrierType, "carrierType must not be null")) {
            case GdCompilerType _ -> throw new IllegalArgumentException(
                    "compiler-only type leaked into frontend writeback analysis: " + carrierType.getTypeName()
            );
            case GdPrimitiveType _, GdObjectType _, GdArrayType _, GdDictionaryType _ -> false;
            default -> true;
        };
    }
}
