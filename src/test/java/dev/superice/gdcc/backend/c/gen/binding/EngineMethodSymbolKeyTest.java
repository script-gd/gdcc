package dev.superice.gdcc.backend.c.gen.binding;

import dev.superice.gdcc.backend.c.gen.insn.BackendMethodCallResolver;
import dev.superice.gdcc.type.GdIntType;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdStringNameType;
import dev.superice.gdcc.type.GdType;
import dev.superice.gdcc.type.GdVariantType;
import dev.superice.gdcc.type.GdVoidType;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class EngineMethodSymbolKeyTest {
    @Test
    @DisplayName("symbol key should stay stable when only bind hashes change")
    void symbolKeyShouldStayStableWhenOnlyBindHashesChange() {
        var oldResolved = exactResolved(
                "Object",
                "call",
                GdVariantType.VARIANT,
                List.of(GdStringNameType.STRING_NAME),
                true,
                false,
                93L,
                List.of(931L)
        );
        var newResolved = exactResolved(
                "Object",
                "call",
                GdVariantType.VARIANT,
                List.of(GdStringNameType.STRING_NAME),
                true,
                false,
                193L,
                List.of(1931L, 1932L)
        );

        var oldKey = EngineMethodSymbolKey.from(oldResolved);
        var newKey = EngineMethodSymbolKey.from(newResolved);
        assertNotNull(oldKey);
        assertNotNull(newKey);

        assertEquals(oldKey, newKey);
        assertEquals("PS_RR_Xv", oldKey.symbolId());
        assertEquals("gdcc_engine_callv_object_call_PS_RR_Xv", oldKey.renderCallHelperName());
        assertEquals("gdcc_engine_method_bind_object_call_PS_RR_Xv", oldKey.renderBindAccessorName());
    }

    @Test
    @DisplayName("symbol key should differentiate static and ABI changes")
    void symbolKeyShouldDifferentiateStaticAndAbiChanges() {
        var instanceKey = EngineMethodSymbolKey.from(exactResolved(
                "Node",
                "queue_free",
                GdVoidType.VOID,
                List.of(),
                false,
                false,
                77L,
                List.of()
        ));
        var staticKey = EngineMethodSymbolKey.from(exactResolved(
                "Node",
                "queue_free",
                GdVoidType.VOID,
                List.of(),
                false,
                true,
                177L,
                List.of()
        ));
        var differentAbiKey = EngineMethodSymbolKey.from(exactResolved(
                "Node",
                "queue_free",
                GdVoidType.VOID,
                List.of(GdIntType.INT),
                false,
                false,
                277L,
                List.of()
        ));
        assertNotNull(instanceKey);
        assertNotNull(staticKey);
        assertNotNull(differentAbiKey);

        assertNotEquals(instanceKey, staticKey);
        assertNotEquals(instanceKey.renderCallHelperName(), staticKey.renderCallHelperName());
        assertNotEquals(instanceKey, differentAbiKey);
        assertNotEquals(instanceKey.renderBindAccessorName(), differentAbiKey.renderBindAccessorName());
    }

    private static @NotNull BackendMethodCallResolver.ResolvedMethodCall exactResolved(
            @NotNull String ownerClassName,
            @NotNull String methodName,
            @NotNull GdType returnType,
            @NotNull List<GdType> parameterTypes,
            boolean isVararg,
            boolean isStatic,
            long hash,
            @NotNull List<Long> hashCompatibility
    ) {
        return new BackendMethodCallResolver.ResolvedMethodCall(
                BackendMethodCallResolver.DispatchMode.ENGINE,
                methodName,
                ownerClassName,
                new GdObjectType(ownerClassName),
                "",
                returnType,
                parameterTypes.stream()
                        .map(type -> new BackendMethodCallResolver.MethodParamSpec(
                                "arg",
                                type,
                                BackendMethodCallResolver.DefaultArgKind.NONE,
                                null,
                                null,
                                null
                        ))
                        .toList(),
                new BackendMethodCallResolver.EngineMethodBindSpec(hash, hashCompatibility),
                isVararg,
                isStatic
        );
    }
}
