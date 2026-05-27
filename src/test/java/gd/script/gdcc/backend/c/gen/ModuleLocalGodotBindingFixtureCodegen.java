package gd.script.gdcc.backend.c.gen;

import gd.script.gdcc.backend.c.gen.binding.ModuleLocalGodotBinding;
import gd.script.gdcc.backend.c.gen.binding.usage.GodotBindingUsageBuffer;
import gd.script.gdcc.lir.LirClassDef;
import gd.script.gdcc.lir.LirFunctionDef;
import org.jetbrains.annotations.NotNull;

/// Test fixture codegen that forces a non-empty module-local Godot binding snapshot while still
/// using the production `CCodegen.generate()` session, template, and file emission path.
public final class ModuleLocalGodotBindingFixtureCodegen extends CCodegen {
    @Override
    @NotNull String generateFuncBody(@NotNull LirClassDef clazz,
                                     @NotNull LirFunctionDef func,
                                     @NotNull GodotBindingUsageBuffer usageBuffer) {
        usageBuffer.recordModuleLocalGodotBinding(ModuleLocalGodotBinding.classConstant("Probe", "READY", "13"));
        return super.generateFuncBody(clazz, func, usageBuffer);
    }
}
