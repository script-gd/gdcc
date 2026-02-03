package dev.superice.gdcc.lir;

import dev.superice.gdcc.type.GdType;
import org.jetbrains.annotations.NotNull;

public record LirVariable(
        @NotNull String id,
        @NotNull GdType type,
        boolean ref,
        @NotNull LirFunctionDef definedInFunction) {
    public LirVariable(
            @NotNull String id,
            @NotNull GdType type,
            @NotNull LirFunctionDef definedInFunction
    ) {
        this(id, type, false, definedInFunction);
    }
}
