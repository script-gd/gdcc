package gd.script.gdcc.type;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GdccForRangeIterTypeTest {
    @Test
    void exposesOnlyStableCompilerInternalProtocol() {
        var type = GdccForRangeIterType.FOR_RANGE_ITER;

        // The concrete type must implement the GdCompilerType abstraction layer.
        assertInstanceOf(GdCompilerType.class, type);

        assertEquals("GdccForRangeIter", type.getTypeName());
        assertEquals("compiler::GdccForRangeIter", type.getLirTypeText());
        assertEquals("gdcc_for_range_iter", type.getCStorageTypeName());
        assertEquals("gdcc_for_range_iter_init", type.getCInitHelperName());
        assertEquals("gdcc_for_range_iter_destroy", type.getCDestroyHelperName());
        // isNullable, getGdExtensionType, and isDestroyable are inherited from GdCompilerType defaults.
        assertFalse(type.isNullable());
        assertNull(type.getGdExtensionType());
        assertTrue(type.isDestroyable());
    }

    @Test
    void staysOutOfUserFacingTypeFamilies() {
        assertNotUserFacingTypeFamily(GdccForRangeIterType.FOR_RANGE_ITER);
    }

    private static void assertNotUserFacingTypeFamily(GdType type) {
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
