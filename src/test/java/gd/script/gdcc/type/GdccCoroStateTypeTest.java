package gd.script.gdcc.type;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Contract test for {@link GdccCoroStateType} — the erased marker type of an OWNED coroutine
/// state object reference (contract: `gdcc_low_ir.md` §Coroutine Instructions,
/// `gdcc_ownership_lifecycle_spec.md` §3.10). Anchors the singleton protocol, the
/// `godot_Object*` storage exception, and the move-only single-consumer contract.
class GdccCoroStateTypeTest {

    @Test
    void exposesStableCompilerInternalProtocol() {
        var type = GdccCoroStateType.CORO_STATE;

        assertInstanceOf(GdCompilerType.class, type);
        assertEquals("GdccCoroState", type.getTypeName());
        assertEquals("compiler::GdccCoroState", type.getLirTypeText());

        // The sole sanctioned non-gdcc_* storage: the value genuinely wraps an engine object
        // reference. Init/destroy helpers stay in the gdcc_* namespace.
        assertEquals("godot_Object*", type.getCStorageTypeName());
        assertEquals("gdcc_coro_state_slot_init", type.getCInitHelperName());
        assertEquals("gdcc_coro_state_slot_destroy", type.getCDestroyHelperName());

        // Shared GdCompilerType defaults still hold.
        assertFalse(type.isNullable());
        assertNull(type.getGdExtensionType());
        assertTrue(type.isDestroyable());
        assertTrue(type.isPassedByPointerInC(), "destroy helper takes the slot address");
    }

    @Test
    void moveOnlySingleConsumerContractHolds() {
        var type = GdccCoroStateType.CORO_STATE;

        // No copy channel exists at all: neither direct struct assignment nor a copy helper.
        assertFalse(type.isCopyable());
        assertFalse(type.isDirectStructAssignmentSafe());
        assertEquals("", type.getCCopyHelperName());
        // A move-only type in this exact configuration is a valid contract state.
        assertDoesNotThrow(type::validateCStorageContract);
    }

    @Test
    void staysOutOfUserFacingTypeFamilies() {
        var type = GdccCoroStateType.CORO_STATE;
        assertInstanceOf(GdType.class, type);
        var userFacingFamilies = List.<Class<?>>of(
                GdPrimitiveType.class,
                GdObjectType.class,
                GdVariantType.class,
                GdMetaType.class,
                GdContainerType.class
        );
        for (var family : userFacingFamilies) {
            assertFalse(family.isAssignableFrom(type.getClass()), family.getSimpleName());
        }
    }
}
