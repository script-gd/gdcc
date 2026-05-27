package gd.script.gdcc.backend.c.gen.binding;

import gd.script.gdcc.backend.c.gen.binding.usage.GodotBindingUsageBuffer;
import gd.script.gdcc.lir.LirClassDef;
import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.lir.LirPropertyDef;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.function.BiFunction;

/// Template-visible renderer facade.
/// `CCodegen.generate()` keeps session ownership local and exposes only buffer-backed callbacks to templates.
public final class GenerateRenderFacade {
    private final @NotNull BiFunction<LirClassDef, LirFunctionDef, String> funcBodyRenderer;
    private final @NotNull BiFunction<LirClassDef, LirPropertyDef, String> propertyInitApplyBodyRenderer;
    private final @NotNull GodotBindingUsageBuffer templateUsageBuffer;

    public GenerateRenderFacade(
            @NotNull BiFunction<LirClassDef, LirFunctionDef, String> funcBodyRenderer,
            @NotNull BiFunction<LirClassDef, LirPropertyDef, String> propertyInitApplyBodyRenderer
    ) {
        this(funcBodyRenderer, propertyInitApplyBodyRenderer, GodotBindingUsageBuffer.noOp());
    }

    public GenerateRenderFacade(
            @NotNull BiFunction<LirClassDef, LirFunctionDef, String> funcBodyRenderer,
            @NotNull BiFunction<LirClassDef, LirPropertyDef, String> propertyInitApplyBodyRenderer,
            @NotNull GodotBindingUsageBuffer templateUsageBuffer
    ) {
        this.funcBodyRenderer = Objects.requireNonNull(funcBodyRenderer);
        this.propertyInitApplyBodyRenderer = Objects.requireNonNull(propertyInitApplyBodyRenderer);
        this.templateUsageBuffer = Objects.requireNonNull(templateUsageBuffer);
    }

    public @NotNull String generateFuncBody(@NotNull LirClassDef classDef, @NotNull LirFunctionDef func) {
        return funcBodyRenderer.apply(classDef, func);
    }

    public @NotNull String generatePropertyInitApplyBody(@NotNull LirClassDef classDef,
                                                         @NotNull LirPropertyDef property) {
        return propertyInitApplyBodyRenderer.apply(classDef, property);
    }

    public void recordModuleLocalGodotBinding(@NotNull ModuleLocalGodotBinding binding) {
        templateUsageBuffer.recordModuleLocalGodotBinding(binding);
    }
}
