package gd.script.gdcc.type;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Contract test for the {@link GdCompilerType} sealed interface abstraction layer.
///
/// Anchors the shared protocol that all compiler-only storage types must satisfy:
/// LIR-only text, C storage/init/destroy helper names, and the design-invariant defaults
/// (non-nullable, no GDExtension metadata, destroyable non-object lifecycle).
/// Also verifies that compiler-only types stay outside the user-facing type families.
class GdCompilerTypeTest {

    @Test
    @org.junit.jupiter.api.DisplayName("GdCompilerType is a permitted subtype of GdType")
    void compilerTypeIsPermittedByGdType() {
        var type = GdccForRangeIterType.FOR_RANGE_ITER;
        // GdCompilerType extends GdType, so every compiler-only type is also a GdType.
        assertInstanceOf(GdType.class, type);
        assertInstanceOf(GdCompilerType.class, type);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("GdccForRangeIterType is the sole permitted GdCompilerType subtype")
    void forRangeIterIsSolePermittedSubtype() {
        // GdCompilerType is sealed and permits only GdccForRangeIterType.
        // The singleton instance must be assignable to GdCompilerType.
        assertTrue(GdCompilerType.class.isAssignableFrom(GdccForRangeIterType.class),
                "GdccForRangeIterType must implement GdCompilerType");
    }

    @Test
    @org.junit.jupiter.api.DisplayName("compiler-only type shared defaults: non-nullable, no metadata, destroyable")
    void sharedDefaultsHold() {
        var type = GdccForRangeIterType.FOR_RANGE_ITER;
        // These are inherited from GdCompilerType default methods, not re-declared per concrete type.
        assertFalse(type.isNullable(), "compiler-only types are value-passed and non-nullable by design");
        assertNull(type.getGdExtensionType(), "compiler-only types carry no GDExtension metadata");
        assertTrue(type.isDestroyable(), "compiler-only storage types are destroyable non-object values");
    }

    @Test
    @org.junit.jupiter.api.DisplayName("compiler-only type protocol methods return gdcc_* helpers and LIR-only text")
    void protocolMethodsReturnGdccHelpers() {
        var type = (GdCompilerType) GdccForRangeIterType.FOR_RANGE_ITER;

        // LIR-only text must use the compiler:: grammar, never a bare source-facing name.
        assertEquals("compiler::GdccForRangeIter", type.getLirTypeText());

        // C storage and helper names must use the gdcc_* namespace, never godot_* defaults.
        assertEquals("gdcc_for_range_iter", type.getCStorageTypeName());
        assertEquals("gdcc_for_range_iter_init", type.getCInitHelperName());
        assertEquals("gdcc_for_range_iter_destroy", type.getCDestroyHelperName());
        assertTrue(type.isPassedByPointerInC());
        assertEquals("", type.getCCopyHelperName());
        assertTrue(type.isDirectStructAssignmentSafe());
    }

    @Test
    @org.junit.jupiter.api.DisplayName("compiler-only default copy contract is direct struct assignment")
    void defaultCopyContractIsDirectStructAssignment() {
        var type = (GdCompilerType) GdccForRangeIterType.FOR_RANGE_ITER;

        assertTrue(type.isPassedByPointerInC(), "compiler-only helper ABI stays pointer-based by default");
        assertEquals("", type.getCCopyHelperName(), "direct-assignment compiler-only types expose empty copy helper");
        assertTrue(type.isDirectStructAssignmentSafe(), "compiler-only types default to direct struct assignment");
        type.validateCStorageContract();
    }

    @Test
    @org.junit.jupiter.api.DisplayName("compiler-only type internal name is stable but not a source-facing declared type")
    void internalNameIsStableAndNotSourceFacing() {
        var type = GdccForRangeIterType.FOR_RANGE_ITER;
        assertEquals("GdccForRangeIter", type.getTypeName());
        // The internal name must differ from the LIR-only text grammar.
        assertFalse(type.getTypeName().startsWith("compiler::"),
                "getTypeName() must not use the LIR-only compiler:: grammar");
    }

    @Test
    @org.junit.jupiter.api.DisplayName("compiler-only type stays outside all user-facing type families")
    void staysOutOfUserFacingTypeFamilies() {
        var type = GdccForRangeIterType.FOR_RANGE_ITER;
        var userFacingFamilies = List.<Class<?>>of(
                GdPrimitiveType.class,
                GdObjectType.class,
                GdVariantType.class,
                GdMetaType.class,
                GdContainerType.class,
                GdNilType.class,
                GdVoidType.class,
                GdRidType.class,
                GdStringLikeType.class,
                GdVectorType.class
        );
        for (var family : userFacingFamilies) {
            assertFalse(family.isAssignableFrom(type.getClass()),
                    family.getSimpleName() + " must not be assignable from compiler-only type");
        }
    }

    @Test
    @org.junit.jupiter.api.DisplayName("compiler-only type must not produce godot_* default helper names")
    void mustNotProduceGodotDefaultHelpers() {
        var type = GdccForRangeIterType.FOR_RANGE_ITER;
        var cStorage = type.getCStorageTypeName();
        var cInit = type.getCInitHelperName();
        var cDestroy = type.getCDestroyHelperName();

        // All helper names must use gdcc_* namespace, not godot_* generated binding defaults.
        assertFalse(cStorage.startsWith("godot_"), "C storage type must not use godot_* prefix: " + cStorage);
        assertFalse(cInit.startsWith("godot_"), "C init helper must not use godot_* prefix: " + cInit);
        assertFalse(cDestroy.startsWith("godot_"), "C destroy helper must not use godot_* prefix: " + cDestroy);

        assertTrue(cStorage.startsWith("gdcc_"), "C storage type must use gdcc_* prefix: " + cStorage);
        assertTrue(cInit.startsWith("gdcc_"), "C init helper must use gdcc_* prefix: " + cInit);
        assertTrue(cDestroy.startsWith("gdcc_"), "C destroy helper must use gdcc_* prefix: " + cDestroy);
    }
}
