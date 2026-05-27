package gd.script.gdcc.backend.c.build;

import gd.script.gdcc.backend.c.gen.CCodegen;
import gd.script.gdcc.backend.c.gen.ModuleLocalGodotBindingFixtureCodegen;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

/// Test project builder that keeps the real C project build path while forcing generated C to carry
/// a non-empty module-local binding section.
public final class ModuleLocalGodotBindingFixtureProjectBuilder extends CProjectBuilder {
    public ModuleLocalGodotBindingFixtureProjectBuilder(@NotNull CCompiler cCompiler) {
        super(cCompiler);
    }

    @Override
    public CBuildResult buildProject(@NotNull CProjectInfo projectInfo, @NotNull CCodegen codegen) throws IOException {
        var fixtureCodegen = new ModuleLocalGodotBindingFixtureCodegen();
        fixtureCodegen.prepare(codegen.ctx, codegen.module);
        return super.buildProject(projectInfo, fixtureCodegen);
    }
}
