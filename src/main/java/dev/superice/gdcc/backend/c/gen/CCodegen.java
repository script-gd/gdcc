package dev.superice.gdcc.backend.c.gen;

import dev.superice.gdcc.backend.Codegen;
import dev.superice.gdcc.backend.CodegenContext;
import dev.superice.gdcc.backend.GeneratedFile;
import dev.superice.gdcc.backend.TemplateLoader;
import dev.superice.gdcc.lir.LirModule;
import freemarker.template.TemplateException;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public class CCodegen implements Codegen {
    public CodegenContext ctx;
    public LirModule module;

    @Override
    public List<GeneratedFile> generate() {
        if (ctx == null || module == null) {
            throw new IllegalStateException("CCodegen not prepared. Call prepare() before generate().");
        }
        try {
            var tplCtx = Map.<String, Object>of("module", module);
            var cSrc = TemplateLoader.renderFromClasspath("template_451/entry.c.ftl", tplCtx);
            var hSrc = TemplateLoader.renderFromClasspath("template_451/entry.h.ftl", tplCtx);

            var cBytes = cSrc.getBytes(StandardCharsets.UTF_8);
            var hBytes = hSrc.getBytes(StandardCharsets.UTF_8);

            var cFile = new GeneratedFile(cBytes, "entry.c");
            var hFile = new GeneratedFile(hBytes, "entry.h");
            return List.of(cFile, hFile);
        } catch (IOException | TemplateException e) {
            throw new RuntimeException("Failed to generate C code: " + e.getMessage(), e);
        }
    }

    @Override
    public void prepare(@NotNull CodegenContext ctx, @NotNull LirModule module) {
        this.ctx = ctx;
        this.module = module;
    }
}
