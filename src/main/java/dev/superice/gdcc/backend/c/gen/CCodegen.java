package dev.superice.gdcc.backend.c.gen;

import dev.superice.gdcc.backend.Codegen;
import dev.superice.gdcc.backend.CodegenContext;
import dev.superice.gdcc.backend.GeneratedFile;
import dev.superice.gdcc.backend.TemplateLoader;
import dev.superice.gdcc.backend.c.gen.insn.*;
import dev.superice.gdcc.enums.GdInstruction;
import dev.superice.gdcc.enums.LifecycleProvenance;
import dev.superice.gdcc.lir.*;
import dev.superice.gdcc.lir.insn.*;
import dev.superice.gdcc.lir.validation.LifecycleInstructionRestrictionValidator;
import dev.superice.gdcc.scope.ParameterDef;
import dev.superice.gdcc.type.*;
import dev.superice.gdcc.util.CCodeFormatter;
import freemarker.template.TemplateException;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

public class CCodegen implements Codegen {
    private static final Logger LOGGER = LoggerFactory.getLogger(CCodegen.class);
    private static final EnumMap<GdInstruction, CInsnGen<? extends LirInstruction>> INSN_GENS = new EnumMap<>(GdInstruction.class);

    static {
        registerInsnGen(new NopInsnGen());
        registerInsnGen(new LineNumberInsnGen());
        registerInsnGen(new ControlFlowInsnGen());
        registerInsnGen(new NewDataInsnGen());
        registerInsnGen(new LoadPropertyInsnGen());
        registerInsnGen(new StorePropertyInsnGen());
        registerInsnGen(new OwnReleaseObjectInsnGen());
        registerInsnGen(new DestructInsnGen());
        registerInsnGen(new PackUnpackVariantInsnGen());
        registerInsnGen(new CallGlobalInsnGen());
        registerInsnGen(new CallMethodInsnGen());
        registerInsnGen(new ConstructInsnGen());
        registerInsnGen(new OperatorInsnGen());
        registerInsnGen(new LoadStaticInsnGen());
        registerInsnGen(new StoreStaticInsnGen());
    }

    public CodegenContext ctx;
    public LirModule module;
    private CGenHelper helper;
    /// Validator for lifecycle instruction usage restrictions.
    private final LifecycleInstructionRestrictionValidator lifecycleValidator = new LifecycleInstructionRestrictionValidator();

    private static void registerInsnGen(@NotNull CInsnGen<? extends LirInstruction> insnGen) {
        for (var opcode : insnGen.getInsnOpcodes()) {
            INSN_GENS.put(opcode, insnGen);
        }
    }

    private static boolean containsInstruction(@NotNull LirBasicBlock block, @NotNull LirInstruction instruction) {
        for (var existingInsn : block.instructions()) {
            if (existingInsn.checkEquals(instruction)) {
                return true;
            }
        }
        return false;
    }

    private void appendInsnIfAbsent(@NotNull LirFunctionDef func,
                                    @NotNull LirBasicBlock block,
                                    @NotNull LirInstruction instruction) {
        if (containsInstruction(block, instruction)) {
            LOGGER.warn("Function {} block {} already contains instruction {}, skip append.",
                    func.getName(),
                    block.id(),
                    instruction);
            return;
        }
        block.instructions().add(instruction);
    }

    private @NotNull String resolvePrepareEntryTarget(@NotNull LirFunctionDef func, @NotNull LirBasicBlock prepareBB) {
        for (var instruction : prepareBB.instructions()) {
            if (instruction instanceof GotoInsn(var targetBbId) && !"__prepare__".equals(targetBbId)) {
                return targetBbId;
            }
        }
        LOGGER.warn("Function {} already enters __prepare__ without a non-self goto target, keep __prepare__ as goto target.",
                func.getName());
        return "__prepare__";
    }

    private void generateDefaultGetterSetterInitialization() {
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
                    func.setEntryBlockId("entry");
                    classDef.addFunction(func);
                }
                if (propertyDef.getSetterFunc() == null) {
                    var setterName = "_field_setter_" + propertyDef.getName();
                    propertyDef.setSetterFunc(setterName);
                    var func = new LirFunctionDef(setterName);
                    func.setReturnType(GdVoidType.VOID);
                    func.addParameter(new LirParameterDef("self", selfType, null, func));
                    func.addParameter(new LirParameterDef("value", propertyDef.getType(), null, func));

                    var bb = new LirBasicBlock("entry");
                    func.addBasicBlock(bb);
                    bb.instructions().add(new StorePropertyInsn(propertyDef.getName(), "self", "value"));
                    bb.instructions().add(new ReturnInsn(null));
                    func.setEntryBlockId("entry");
                    classDef.addFunction(func);
                }
                if (propertyDef.getInitFunc() == null) {
                    var initName = "_field_init_" + propertyDef.getName();
                    propertyDef.setInitFunc(initName);
                    var func = new LirFunctionDef(initName);
                    func.setReturnType(propertyDef.getType());
                    func.addParameter(new LirParameterDef("self", selfType, null, func));
                    var tmpVar = func.createAndAddTmpVariable(propertyDef.getType());
                    var bb = new LirBasicBlock("entry");
                    func.addBasicBlock(bb);
                    switch (propertyDef.getType()) {
                        case GdObjectType _ -> bb.instructions().add(new LiteralNullInsn(tmpVar.id()));
                        case GdVariantType _, GdNilType _ -> bb.instructions().add(new LiteralNilInsn(tmpVar.id()));
                        case GdBoolType _ -> bb.instructions().add(new LiteralBoolInsn(tmpVar.id(), false));
                        case GdIntType _ -> bb.instructions().add(new LiteralIntInsn(tmpVar.id(), 0));
                        case GdFloatType _ -> bb.instructions().add(new LiteralFloatInsn(tmpVar.id(), 0.0));
                        case GdStringType _ -> bb.instructions().add(new LiteralStringInsn(tmpVar.id(), ""));
                        case GdStringNameType _ -> bb.instructions().add(new LiteralStringNameInsn(tmpVar.id(), ""));
                        case GdArrayType gdArrayType ->
                                bb.instructions().add(new ConstructArrayInsn(tmpVar.id(), gdArrayType.getValueType().getTypeName()));
                        case GdPackedArrayType _ -> bb.instructions().add(new ConstructArrayInsn(tmpVar.id(), null));
                        case GdDictionaryType gdDictionaryType ->
                                bb.instructions().add(new ConstructDictionaryInsn(tmpVar.id(), gdDictionaryType.getKeyType().getTypeName(), gdDictionaryType.getValueType().getTypeName()));
                        default -> bb.instructions().add(new ConstructBuiltinInsn(tmpVar.id(), List.of()));
                    }
                    bb.instructions().add(new ReturnInsn(tmpVar.id()));
                    func.setEntryBlockId("entry");
                    classDef.addFunction(func);
                }
            }
        }
    }

    private void generateFunctionPrepareBlock() {
        for (var classDef : module.getClassDefs()) {
            for (var func : classDef.getFunctions()) {
                var prepareBB = func.getBasicBlock("__prepare__");
                if (prepareBB == null) {
                    prepareBB = new LirBasicBlock("__prepare__");
                    func.addBasicBlock(prepareBB);
                }
                // initialize variables
                var parameterNames = func.getParameters().stream().map(ParameterDef::getName).collect(HashSet::new, HashSet::add, HashSet::addAll);
                for (var variable : func.getVariables().values()) {
                    if (parameterNames.contains(variable.id())) {
                        continue;
                    }
                    if (variable.ref()) {
                        continue;
                    }
                    var initInsn = switch (variable.type()) {
                        case GdObjectType _ -> new LiteralNullInsn(variable.id());
                        case GdVariantType _, GdNilType _ -> new LiteralNilInsn(variable.id());
                        case GdBoolType _ -> new LiteralBoolInsn(variable.id(), false);
                        case GdIntType _ -> new LiteralIntInsn(variable.id(), 0);
                        case GdFloatType _ -> new LiteralFloatInsn(variable.id(), 0.0);
                        case GdStringType _ -> new LiteralStringInsn(variable.id(), "");
                        case GdStringNameType _ -> new LiteralStringNameInsn(variable.id(), "");
                        case GdArrayType gdArrayType ->
                                new ConstructArrayInsn(variable.id(), gdArrayType.getValueType().getTypeName());
                        case GdPackedArrayType _ -> new ConstructArrayInsn(variable.id(), null);
                        case GdDictionaryType gdDictionaryType ->
                                new ConstructDictionaryInsn(variable.id(), gdDictionaryType.getKeyType().getTypeName(), gdDictionaryType.getValueType().getTypeName());
                        default -> new ConstructBuiltinInsn(variable.id(), List.of());
                    };
                    appendInsnIfAbsent(func, prepareBB, initInsn);
                }
                var funcEntry = func.getEntryBlockId();
                var targetEntry = "__prepare__".equals(funcEntry)
                        ? resolvePrepareEntryTarget(func, prepareBB)
                        : funcEntry;
                appendInsnIfAbsent(func, prepareBB, new GotoInsn(targetEntry));
                func.setEntryBlockId("__prepare__");
            }
        }
    }

    private void ensureFunctionFinallyBlock() {
        for (var classDef : module.getClassDefs()) {
            for (var func : classDef.getFunctions()) {
                var finallyBB = func.getBasicBlock("__finally__");
                if (finallyBB == null) {
                    finallyBB = new LirBasicBlock("__finally__");
                    func.addBasicBlock(finallyBB);
                }

                var parameterNames = func.getParameters().stream()
                        .map(ParameterDef::getName)
                        .collect(HashSet::new, HashSet::add, HashSet::addAll);
                for (var variable : func.getVariables().values()) {
                    if (parameterNames.contains(variable.id())) {
                        continue;
                    }
                    if (variable.ref()) {
                        continue;
                    }
                    if (!variable.type().isDestroyable()) {
                        continue;
                    }
                    appendInsnIfAbsent(func, finallyBB,
                            new DestructInsn(variable.id(), LifecycleProvenance.AUTO_GENERATED));
                }
                if (func.getReturnType() instanceof GdVoidType) {
                    appendInsnIfAbsent(func, finallyBB, new ReturnInsn(null));
                } else {
                    appendInsnIfAbsent(func, finallyBB, new ReturnInsn("_return_val"));
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    public @NotNull String generateFuncBody(@NotNull LirClassDef clazz,
                                            @NotNull LirFunctionDef func) {
        if (ctx == null || module == null) {
            throw new IllegalStateException("CCodegen not prepared. Call prepare() before generateBlock().");
        }
        lifecycleValidator.validateFunction(ctx, func);
        // Check if the entry block is valid
        if (!func.hasBasicBlock(func.getEntryBlockId())) {
            throw new IllegalArgumentException("Function " + func.getName() + " has invalid entry block ID: " + func.getEntryBlockId());
        }
        var bodyBuilder = new CBodyBuilder(helper, clazz, func);
        // generate blocks
        bodyBuilder.appendRaw("goto " + func.getEntryBlockId() + ";\n");
        for (var bb : func) {
            bodyBuilder.beginBasicBlock(bb.id());
            for (int i = 0; i < bb.instructions().size(); i++) {
                var insn = bb.instructions().get(i);
                CInsnGen<LirInstruction> insnGen = (CInsnGen<LirInstruction>) INSN_GENS.get(insn.opcode());
                if (insnGen == null) {
                    throw new UnsupportedOperationException("Unsupported instruction opcode: " + insn.opcode().opcode());
                }
                bodyBuilder.setCurrentPosition(bb, i, insn);
                insnGen.generateCCode(bodyBuilder);
            }
        }
        return bodyBuilder.build();
    }


    @Override
    public List<GeneratedFile> generate() {
        if (ctx == null || module == null) {
            throw new IllegalStateException("CCodegen not prepared. Call prepare() before generate().");
        }
        this.generateDefaultGetterSetterInitialization();
        this.generateFunctionPrepareBlock();
        this.ensureFunctionFinallyBlock();
        for (var classDef : module.getClassDefs()) {
            for (var function : classDef.getFunctions()) {
                lifecycleValidator.validateFunction(ctx, function);
            }
        }
        try {
            var tplCtx = Map.of(
                    "module", module,
                    "helper", helper,
                    "gen", this
            );
            var cSrc = TemplateLoader.renderFromClasspath("template_451/entry.c.ftl", tplCtx);
            var hSrc = TemplateLoader.renderFromClasspath("template_451/entry.h.ftl", tplCtx);
            cSrc = CCodeFormatter.format(cSrc);
            hSrc = CCodeFormatter.format(hSrc);

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
        var registry = ctx.classRegistry();
        for (var classDef : module.getClassDefs()) {
            registry.addGdccClass(classDef);
        }
        this.helper = new CGenHelper(ctx, module.getClassDefs());
    }
}
