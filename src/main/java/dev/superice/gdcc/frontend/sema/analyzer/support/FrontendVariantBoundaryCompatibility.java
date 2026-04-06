package dev.superice.gdcc.frontend.sema.analyzer.support;

import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.type.GdNilType;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdType;
import dev.superice.gdcc.type.GdVariantType;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/// Shared frontend-only compatibility rule for ordinary typed frontend boundaries.
///
/// The helper is intentionally narrow:
/// - it decides only whether a source/target pair is accepted at the frontend semantic boundary
/// - it distinguishes direct flow from explicit pack/unpack/null-object edges so lowering can reuse the same rule later
/// - it does not emit diagnostics and does not weaken backend/global `ClassRegistry.checkAssignable(...)`
/// - the exact allowed matrix is owned by `doc/module_impl/frontend/frontend_implicit_conversion_matrix.md`;
///   this helper must stay mechanically aligned with that document instead of evolving its own rule list
/// - the consumer/lowering contract for these decisions is documented in
///   `doc/module_impl/frontend/frontend_lowering_(un)pack_implementation.md`
public final class FrontendVariantBoundaryCompatibility {
    public enum Decision {
        ALLOW_DIRECT,
        ALLOW_WITH_PACK,
        ALLOW_WITH_UNPACK,
        ALLOW_WITH_LITERAL_NULL,
        REJECT;

        public boolean allows() {
            return this != REJECT;
        }
    }

    private FrontendVariantBoundaryCompatibility() {
    }

    /// Frontend semantic boundary rule for one already-typed source/target pair.
    /// The authoritative compatibility matrix lives in
    /// `doc/module_impl/frontend/frontend_implicit_conversion_matrix.md`, while the downstream
    /// consumer/materialization contract lives in
    /// `doc/module_impl/frontend/frontend_lowering_(un)pack_implementation.md`.
    /// This method only encodes that matrix as a lowering-friendly decision:
    /// - `ALLOW_WITH_PACK`
    /// - `ALLOW_WITH_UNPACK`
    /// - `ALLOW_WITH_LITERAL_NULL`
    /// - `ALLOW_DIRECT`
    /// - `REJECT`
    public static @NotNull Decision determineFrontendBoundaryDecision(
            @NotNull ClassRegistry classRegistry,
            @NotNull GdType sourceType,
            @NotNull GdType targetType
    ) {
        var registry = Objects.requireNonNull(classRegistry, "classRegistry must not be null");
        var source = Objects.requireNonNull(sourceType, "sourceType must not be null");
        var target = Objects.requireNonNull(targetType, "targetType must not be null");
        if (target instanceof GdVariantType) {
            return source instanceof GdVariantType ? Decision.ALLOW_DIRECT : Decision.ALLOW_WITH_PACK;
        }
        if (source instanceof GdVariantType) {
            return Decision.ALLOW_WITH_UNPACK;
        }
        if (source instanceof GdNilType && target instanceof GdObjectType) {
            return Decision.ALLOW_WITH_LITERAL_NULL;
        }
        return registry.checkAssignable(source, target) ? Decision.ALLOW_DIRECT : Decision.REJECT;
    }

    public static boolean isFrontendBoundaryCompatible(
            @NotNull ClassRegistry classRegistry,
            @NotNull GdType sourceType,
            @NotNull GdType targetType
    ) {
        return determineFrontendBoundaryDecision(classRegistry, sourceType, targetType).allows();
    }
}
