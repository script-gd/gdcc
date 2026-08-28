package gd.script.gdcc.frontend.lowering;

import gd.script.gdcc.frontend.sema.FrontendBindingKind;
import gd.script.gdcc.frontend.sema.FrontendMemberResolutionStatus;
import gd.script.gdcc.frontend.sema.FrontendReceiverKind;
import gd.script.gdcc.frontend.sema.FrontendResolvedMember;
import gd.script.gdcc.scope.PropertyDef;
import gd.script.gdcc.scope.ScopeOwnerKind;
import gd.script.gdcc.type.GdArrayType;
import gd.script.gdcc.type.GdDictionaryType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdPackedArrayType;
import gd.script.gdcc.type.GdStringNameType;
import gd.script.gdcc.type.GdStringType;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.type.GdVariantType;
import gd.script.gdcc.type.GdVectorType;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/// Shared truth source for frontend subscript access families.
///
/// CFG publication, writable-route payload freezing, and body-lowering instruction emission must all
/// agree on whether one subscript route is `INDEXED`, `NAMED`, `KEYED`, or fully generic. Keeping
/// that choice here prevents the builder and body pass from slowly drifting into separate heuristics.
public final class FrontendSubscriptAccessSupport {
    private FrontendSubscriptAccessSupport() {
    }

    public static @NotNull AccessKind determineAccessKind(
            @NotNull GdType receiverType,
            @NotNull GdType keyType
    ) {
        Objects.requireNonNull(receiverType, "receiverType must not be null");
        Objects.requireNonNull(keyType, "keyType must not be null");
        if (keyType instanceof GdIntType && supportsIndexedSubscript(receiverType)) {
            return AccessKind.INDEXED;
        }
        if (keyType instanceof GdStringNameType && supportsNamedSubscript(receiverType)) {
            return AccessKind.NAMED;
        }
        if (!(keyType instanceof GdVariantType) && supportsKeyedSubscript(receiverType)) {
            return AccessKind.KEYED;
        }
        return AccessKind.GENERIC;
    }

    private static boolean supportsKeyedSubscript(@NotNull GdType receiverType) {
        return receiverType instanceof GdVariantType
                || receiverType instanceof GdDictionaryType
                || receiverType instanceof GdObjectType;
    }

    private static boolean supportsNamedSubscript(@NotNull GdType receiverType) {
        return receiverType instanceof GdVariantType
                || receiverType instanceof GdDictionaryType
                || receiverType instanceof GdObjectType
                || receiverType instanceof GdStringType
                || receiverType instanceof GdVectorType;
    }

    private static boolean supportsIndexedSubscript(@NotNull GdType receiverType) {
        return receiverType instanceof GdVariantType
                || receiverType instanceof GdArrayType
                || receiverType instanceof GdDictionaryType
                || receiverType instanceof GdStringType
                || receiverType instanceof GdVectorType
                || receiverType instanceof GdPackedArrayType;
    }

    /// Whether an attribute-subscript container member (`receiver.member[key]`) is a resolved,
    /// non-static GDCC instance property with a concrete declared type, so body lowering may load
    /// the named base through `LoadPropertyInsn` and subscript it with the published container
    /// type instead of rebuilding the Variant named route.
    ///
    /// Fail-closed by construction: dynamic members, engine-owned properties (including
    /// `ExtensionGdClass.PropertyInfo`, which implements `PropertyDef` but is not GDCC storage),
    /// static properties, and untyped (`Variant`) properties all keep the Variant named route.
    /// CFG access-kind freezing and body lowering must both consult this single predicate so the
    /// frozen route contract cannot drift from the emitted instructions.
    public static boolean isResolvedTypedInstanceContainerMember(@NotNull FrontendResolvedMember containerMember) {
        Objects.requireNonNull(containerMember, "containerMember must not be null");
        return containerMember.status() == FrontendMemberResolutionStatus.RESOLVED
                && containerMember.bindingKind() == FrontendBindingKind.PROPERTY
                && containerMember.receiverKind() == FrontendReceiverKind.INSTANCE
                && containerMember.ownerKind() == ScopeOwnerKind.GDCC
                && containerMember.declarationSite() instanceof PropertyDef propertyDef
                && !propertyDef.isStatic()
                && containerMember.resultType() != null
                && !(containerMember.resultType() instanceof GdVariantType);
    }

    public enum AccessKind {
        GENERIC,
        KEYED,
        NAMED,
        INDEXED
    }
}
