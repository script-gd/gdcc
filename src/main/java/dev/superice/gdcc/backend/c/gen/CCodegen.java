package dev.superice.gdcc.backend.c.gen;

import dev.superice.gdcc.backend.Codegen;
import dev.superice.gdcc.backend.CodegenContext;
import dev.superice.gdcc.backend.GeneratedFile;
import dev.superice.gdcc.backend.TemplateLoader;
import dev.superice.gdcc.lir.LirBasicBlock;
import dev.superice.gdcc.lir.LirFunctionDef;
import dev.superice.gdcc.lir.LirModule;
import dev.superice.gdcc.lir.LirParameterDef;
import dev.superice.gdcc.lir.insn.LoadPropertyInsn;
import dev.superice.gdcc.lir.insn.ReturnInsn;
import dev.superice.gdcc.type.GdObjectType;
import freemarker.template.TemplateException;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public class CCodegen implements Codegen {
    public CodegenContext ctx;
    public LirModule module;

    private void generateDefaultGetterSetter() {
        if (ctx == null || module == null) {
            throw new IllegalStateException("CCodegen not prepared. Call prepare() before generateDefaultGetterSetter().");
        }
        for (var classDef : module.getClassDefs()) {
            var selfType = new GdObjectType(classDef.getName());
            for (var propertyDef : classDef.getProperties()) {
                if (propertyDef.isStatic()) {
                    throw new IllegalStateException("Static properties are not supported in GdExtension.");
                }
                if (propertyDef.getGetterFunc() == null) {
                    var getterName = "_field_getter_" + propertyDef.getName();
                    propertyDef.setGetterFunc(getterName);
                    var func = new LirFunctionDef(getterName);
                    func.setReturnType(propertyDef.getType());
                    func.addParameter(new LirParameterDef("self", selfType, null, func));
                    var tmpVar = func.createAndAddTmpVariable(propertyDef.getType());
                    var bb = new LirBasicBlock("entry");
                    func.addBasicBlock(bb);
                    bb.instructions().add(new LoadPropertyInsn(tmpVar.id(), propertyDef.getName(), "self"));
                    bb.instructions().add(new ReturnInsn(tmpVar.id()));
                    // func.setEntryBlockId("entry");
                    classDef.addFunction(func);
                }
                if (propertyDef.getSetterFunc() == null) {
                    var setterName = "_field_setter_" + propertyDef.getName();
                    propertyDef.setSetterFunc(setterName);
                    // TODO: implement default setter
                }
            }
        }
    }

    @Override
    public List<GeneratedFile> generate() {
        if (ctx == null || module == null) {
            throw new IllegalStateException("CCodegen not prepared. Call prepare() before generate().");
        }
        try {
            var tplCtx = Map.of(
                    "module", module,
                    "helper", new CGenHelper(ctx, module.getClassDefs())
            );
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
