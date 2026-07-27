package gd.script.gdcc.type;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
    @org.junit.jupiter.api.DisplayName("All compiler-only types are permitted subtypes of GdType")
    void compilerTypesArePermittedByGdType() {
        for (var type : compilerTypes()) {
            // GdCompilerType extends GdType, so every compiler-only type is also a GdType.
            assertInstanceOf(GdType.class, type);
            assertInstanceOf(GdCompilerType.class, type);
        }
    }

    @Test
    @org.junit.jupiter.api.DisplayName("GdCompilerType sealed permits match all iterator state types")
    void sealedPermitsMatchKnownIteratorStateTypes() {
        assertEquals(
                Set.of(
                        GdccForRangeIterType.class,
                        GdccForVariantIterType.class,
                        GdccForStringIterType.class,
                        GdccForArrayIterType.class,
                        GdccForDictionaryIterType.class,
                        GdccForPackedArrayIterType.class,
                        GdccForFloatIterType.class
                ),
                Arrays.stream(GdCompilerType.class.getPermittedSubclasses())
                        .collect(Collectors.toUnmodifiableSet())
        );
    }

    @Test
    @org.junit.jupiter.api.DisplayName("compiler-only type shared defaults: non-nullable, no metadata, destroyable")
    void sharedDefaultsHold() {
        for (var type : compilerTypes()) {
            // These are inherited from GdCompilerType default methods, not re-declared per concrete type.
            assertFalse(type.isNullable(), "compiler-only types are value-passed and non-nullable by design");
            assertNull(type.getGdExtensionType(), "compiler-only types carry no GDExtension metadata");
            assertTrue(type.isDestroyable(), "compiler-only storage types are destroyable non-object values");
        }
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
    @org.junit.jupiter.api.DisplayName("refcounted iterator states require gdcc deep-copy helpers")
    void refcountedIteratorStatesRequireDeepCopyHelpers() {
        assertDeepCopyContract(GdccForVariantIterType.FOR_VARIANT_ITER, "gdcc_for_variant_iter_copy");
        assertDeepCopyContract(GdccForStringIterType.FOR_STRING_ITER, "gdcc_for_string_iter_copy");
        assertDeepCopyContract(GdccForArrayIterType.FOR_ARRAY_ITER, "gdcc_for_array_iter_copy");
        assertDeepCopyContract(GdccForDictionaryIterType.FOR_DICTIONARY_ITER, "gdcc_for_dictionary_iter_copy");
        for (var family : GdccForPackedArrayIterType.all()) {
            assertDeepCopyContract(family, family.getCCopyHelperName());
        }
    }

    @Test
    @org.junit.jupiter.api.DisplayName("float iterator state uses direct struct assignment")
    void floatIteratorStateUsesDirectStructAssignment() {
        var type = GdccForFloatIterType.FOR_FLOAT_ITER;
        assertTrue(type.isDirectStructAssignmentSafe());
        assertEquals("", type.getCCopyHelperName());
        assertDoesNotThrow(type::validateCStorageContract);
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
        for (var type : compilerTypes()) {
            for (var family : userFacingFamilies) {
                assertFalse(family.isAssignableFrom(type.getClass()),
                        family.getSimpleName() + " must not be assignable from compiler-only type");
            }
        }
    }

    @Test
    @org.junit.jupiter.api.DisplayName("compiler-only type must not produce godot_* default helper names")
    void mustNotProduceGodotDefaultHelpers() {
        for (var type : compilerTypes()) {
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

    private static List<GdCompilerType> compilerTypes() {
        var all = new ArrayList<GdCompilerType>();
        all.add(GdccForRangeIterType.FOR_RANGE_ITER);
        all.add(GdccForVariantIterType.FOR_VARIANT_ITER);
        all.add(GdccForStringIterType.FOR_STRING_ITER);
        all.add(GdccForArrayIterType.FOR_ARRAY_ITER);
        all.add(GdccForDictionaryIterType.FOR_DICTIONARY_ITER);
        all.addAll(GdccForPackedArrayIterType.all());
        all.add(GdccForFloatIterType.FOR_FLOAT_ITER);
        return List.copyOf(all);
    }

    private static void assertDeepCopyContract(GdCompilerType type, String expectedCopyHelper) {
        assertFalse(type.isDirectStructAssignmentSafe());
        assertEquals(expectedCopyHelper, type.getCCopyHelperName());
        assertTrue(type.getCCopyHelperName().startsWith("gdcc_"));
        assertDoesNotThrow(type::validateCStorageContract);
    }
}
