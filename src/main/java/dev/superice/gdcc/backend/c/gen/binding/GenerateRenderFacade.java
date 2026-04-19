package dev.superice.gdcc.backend.c.gen.binding;

import dev.superice.gdcc.lir.LirClassDef;
import dev.superice.gdcc.lir.LirFunctionDef;
import dev.superice.gdcc.lir.LirPropertyDef;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.function.BiFunction;

/// Template-visible renderer facade.
/// `CCodegen.generate()` keeps session ownership local and only exposes explicit render callbacks to templates.
public final class GenerateRenderFacade {
    private final @NotNull BiFunction<LirClassDef, LirFunctionDef, String> funcBodyRenderer;
    private final @NotNull BiFunction<LirClassDef, LirPropertyDef, String> propertyInitApplyBodyRenderer;

    public GenerateRenderFacade(
            @NotNull BiFunction<LirClassDef, LirFunctionDef, String> funcBodyRenderer,
            @NotNull BiFunction<LirClassDef, LirPropertyDef, String> propertyInitApplyBodyRenderer
    ) {
        this.funcBodyRenderer = Objects.requireNonNull(funcBodyRenderer);
        this.propertyInitApplyBodyRenderer = Objects.requireNonNull(propertyInitApplyBodyRenderer);
    }

    public @NotNull String generateFuncBody(@NotNull LirClassDef classDef, @NotNull LirFunctionDef func) {
        return funcBodyRenderer.apply(classDef, func);
    }

    public @NotNull String generatePropertyInitApplyBody(@NotNull LirClassDef classDef,
                                                         @NotNull LirPropertyDef property) {
        return propertyInitApplyBodyRenderer.apply(classDef, property);
    }
}
