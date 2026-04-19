package dev.superice.gdcc.backend.c.gen.binding;

import dev.superice.gdcc.backend.c.gen.insn.BackendMethodCallResolver;
import dev.superice.gdcc.type.GdType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// Generated exact-engine helper parameter after backend surface normalization.
public record EngineMethodHelperParam(
        @NotNull String name,
        @NotNull GdType type,
        @NotNull String cType,
        @NotNull BackendMethodCallResolver.EngineHelperSlotMode slotMode,
        @Nullable String slotCType
) {
    public EngineMethodHelperParam {
        Objects.requireNonNull(name);
        Objects.requireNonNull(type);
        Objects.requireNonNull(cType);
        Objects.requireNonNull(slotMode);
        if (slotMode == BackendMethodCallResolver.EngineHelperSlotMode.LOCAL_VALUE_SLOT_ADDRESS) {
            if (slotCType == null || slotCType.isBlank()) {
                throw new IllegalArgumentException("slotCType must be present for LOCAL_VALUE_SLOT_ADDRESS");
            }
        }
    }

    public boolean requiresLocalValueSlot() {
        return slotMode == BackendMethodCallResolver.EngineHelperSlotMode.LOCAL_VALUE_SLOT_ADDRESS;
    }
}
