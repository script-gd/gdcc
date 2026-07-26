package gd.script.gdcc.type;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GdccForVariantIterTypeTest {
    @Test
    void exposesOnlyStableCompilerInternalProtocol() {
        var type = GdccForVariantIterType.FOR_VARIANT_ITER;

        assertInstanceOf(GdCompilerType.class, type);
        assertEquals("GdccForVariantIter", type.getTypeName());
        assertEquals("compiler::GdccForVariantIter", type.getLirTypeText());
        assertEquals("gdcc_for_variant_iter", type.getCStorageTypeName());
        assertEquals("gdcc_for_variant_iter_init", type.getCInitHelperName());
        assertEquals("gdcc_for_variant_iter_destroy", type.getCDestroyHelperName());
        assertEquals("gdcc_for_variant_iter_copy", type.getCCopyHelperName());
        assertFalse(type.isNullable());
        assertNull(type.getGdExtensionType());
        assertTrue(type.isDestroyable());
    }

    @Test
    void requiresDeepCopyAndRejectsDirectStructAssignment() {
        var type = GdccForVariantIterType.FOR_VARIANT_ITER;

        assertFalse(type.isDirectStructAssignmentSafe());
        assertFalse(type.getCCopyHelperName().isBlank());
        assertFalse(type.getCCopyHelperName().startsWith("godot_"));
    }

    @Test
    void validateCStorageContractPasses() {
        assertDoesNotThrow(GdccForVariantIterType.FOR_VARIANT_ITER::validateCStorageContract);
    }

    @Test
    void staysOutOfUserFacingTypeFamilies() {
        var type = GdccForVariantIterType.FOR_VARIANT_ITER;
        assertInstanceOf(GdType.class, type);
        var userFacingFamilies = List.<Class<?>>of(
                GdPrimitiveType.class,
                GdObjectType.class,
                GdVariantType.class,
                GdMetaType.class
        );
        for (var family : userFacingFamilies) {
            assertFalse(family.isAssignableFrom(type.getClass()), family.getSimpleName());
        }
    }
}
