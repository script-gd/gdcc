package gd.script.gdcc.backend.c.gen.binding;

import gd.script.gdcc.backend.c.gen.binding.usage.GodotBindingUsageBuffer;
import gd.script.gdcc.lir.LirClassDef;
import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.lir.LirPropertyDef;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;

/// Template-visible renderer facade.
/// `CCodegen.generate()` keeps session ownership local and exposes only buffer-backed callbacks to templates.
public final class GenerateRenderFacade {
    private final @NotNull BiFunction<LirClassDef, LirFunctionDef, String> funcBodyRenderer;
    private final @NotNull BiFunction<LirClassDef, LirPropertyDef, String> propertyInitApplyBodyRenderer;
    private final @NotNull Function<LirClassDef, String> staticDefaultsBodyRenderer;
    private final @NotNull Function<LirClassDef, String> staticInitializersBodyRenderer;
    private final @NotNull Function<LirClassDef, String> staticDeinitializeBodyRenderer;
    private final @NotNull GodotBindingUsageBuffer templateUsageBuffer;

    public GenerateRenderFacade(
            @NotNull BiFunction<LirClassDef, LirFunctionDef, String> funcBodyRenderer,
            @NotNull BiFunction<LirClassDef, LirPropertyDef, String> propertyInitApplyBodyRenderer,
            @NotNull Function<LirClassDef, String> staticDefaultsBodyRenderer,
            @NotNull Function<LirClassDef, String> staticInitializersBodyRenderer,
            @NotNull Function<LirClassDef, String> staticDeinitializeBodyRenderer
    ) {
        this(funcBodyRenderer, propertyInitApplyBodyRenderer, staticDefaultsBodyRenderer,
                staticInitializersBodyRenderer, staticDeinitializeBodyRenderer, GodotBindingUsageBuffer.noOp());
    }

    public GenerateRenderFacade(
            @NotNull BiFunction<LirClassDef, LirFunctionDef, String> funcBodyRenderer,
            @NotNull BiFunction<LirClassDef, LirPropertyDef, String> propertyInitApplyBodyRenderer,
            @NotNull Function<LirClassDef, String> staticDefaultsBodyRenderer,
            @NotNull Function<LirClassDef, String> staticInitializersBodyRenderer,
            @NotNull Function<LirClassDef, String> staticDeinitializeBodyRenderer,
            @NotNull GodotBindingUsageBuffer templateUsageBuffer
    ) {
        this.funcBodyRenderer = Objects.requireNonNull(funcBodyRenderer);
        this.propertyInitApplyBodyRenderer = Objects.requireNonNull(propertyInitApplyBodyRenderer);
        this.staticDefaultsBodyRenderer = Objects.requireNonNull(staticDefaultsBodyRenderer);
        this.staticInitializersBodyRenderer = Objects.requireNonNull(staticInitializersBodyRenderer);
        this.staticDeinitializeBodyRenderer = Objects.requireNonNull(staticDeinitializeBodyRenderer);
        this.templateUsageBuffer = Objects.requireNonNull(templateUsageBuffer);
    }

    public @NotNull String generateFuncBody(@NotNull LirClassDef classDef, @NotNull LirFunctionDef func) {
        return funcBodyRenderer.apply(classDef, func);
    }

    public @NotNull String generatePropertyInitApplyBody(@NotNull LirClassDef classDef,
                                                         @NotNull LirPropertyDef property) {
        return propertyInitApplyBodyRenderer.apply(classDef, property);
    }

    /// Per-class static defaults section body (static var module lifecycle).
    public @NotNull String generateStaticDefaultsBody(@NotNull LirClassDef classDef) {
        return staticDefaultsBodyRenderer.apply(classDef);
    }

    /// Per-class static initializers section body (source initializer application).
    public @NotNull String generateStaticInitializersBody(@NotNull LirClassDef classDef) {
        return staticInitializersBodyRenderer.apply(classDef);
    }

    /// `deinitialize()` cleanup statements for one class's static backing variables.
    public @NotNull String generateStaticDeinitializeBody(@NotNull LirClassDef classDef) {
        return staticDeinitializeBodyRenderer.apply(classDef);
    }

    public void recordModuleLocalGodotBinding(@NotNull ModuleLocalGodotBinding binding) {
        templateUsageBuffer.recordModuleLocalGodotBinding(binding);
    }
}
