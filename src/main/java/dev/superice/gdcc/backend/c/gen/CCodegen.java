package dev.superice.gdcc.backend.c.gen;

import dev.superice.gdcc.backend.Codegen;
import dev.superice.gdcc.backend.CodegenContext;
import dev.superice.gdcc.backend.GeneratedFile;
import dev.superice.gdcc.backend.TemplateLoader;
import dev.superice.gdcc.backend.c.gen.binding.EngineMethodUsageBuffer;
import dev.superice.gdcc.backend.c.gen.binding.EngineMethodUsageSession;
import dev.superice.gdcc.backend.c.gen.binding.GenerateRenderFacade;
import dev.superice.gdcc.backend.c.gen.insn.*;
import dev.superice.gdcc.enums.GdInstruction;
import dev.superice.gdcc.enums.LifecycleProvenance;
import dev.superice.gdcc.lir.*;
import dev.superice.gdcc.lir.insn.*;
import dev.superice.gdcc.lir.validation.ControlFlowIntegrityValidator;
import dev.superice.gdcc.lir.validation.LifecycleInstructionRestrictionValidator;
import dev.superice.gdcc.scope.ParameterDef;
import dev.superice.gdcc.scope.RefCountedStatus;
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
import java.util.Set;

public class CCodegen implements Codegen {
    private static final Logger LOGGER = LoggerFactory.getLogger(CCodegen.class);
    private static final EnumMap<GdInstruction, CInsnGen<? extends LirInstruction>> INSN_GENS = new EnumMap<>(GdInstruction.class);

    static {
        registerInsnGen(new NopInsnGen());
        registerInsnGen(new LineNumberInsnGen());
        registerInsnGen(new ControlFlowInsnGen());
        registerInsnGen(new NewDataInsnGen());
        registerInsnGen(new AssignInsnGen());
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
        registerInsnGen(new IndexLoadInsnGen());
        registerInsnGen(new IndexStoreInsnGen());
    }

    public CodegenContext ctx;
    public LirModule module;
    private CGenHelper helper;
    /// Validator for block layout and successor integrity.
    private final ControlFlowIntegrityValidator controlFlowValidator = new ControlFlowIntegrityValidator();
    /// Validator for lifecycle instruction usage restrictions.
    private final LifecycleInstructionRestrictionValidator lifecycleValidator = new LifecycleInstructionRestrictionValidator();

    private static void registerInsnGen(@NotNull CInsnGen<? extends LirInstruction> insnGen) {
        for (var opcode : insnGen.getInsnOpcodes()) {
            INSN_GENS.put(opcode, insnGen);
        }
    }

    private static boolean containsInstruction(@NotNull LirBasicBlock block, @NotNull LirInstruction instruction) {
        for (var existingInsn : block.getInstructions()) {
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
        if (instruction instanceof ControlFlowInstruction controlFlowInstruction) {
            block.setTerminator(controlFlowInstruction);
            return;
        }
        block.appendNonTerminatorInstruction(instruction);
    }

    private @NotNull String resolvePrepareEntryTarget(@NotNull LirFunctionDef func, @NotNull LirBasicBlock prepareBB) {
        var terminator = prepareBB.getTerminator();
        if (terminator instanceof GotoInsn(var targetBbId) && !"__prepare__".equals(targetBbId)) {
            return targetBbId;
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
                    bb.appendNonTerminatorInstruction(new LoadPropertyInsn(tmpVar.id(), propertyDef.getName(), "self"));
                    bb.setTerminator(new ReturnInsn(tmpVar.id()));
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
                    bb.appendNonTerminatorInstruction(new StorePropertyInsn(propertyDef.getName(), "self", "value"));
                    bb.setTerminator(new ReturnInsn(null));
                    func.setEntryBlockId("entry");
                    classDef.addFunction(func);
                }
                if (propertyDef.getInitFunc() == null) {
                    var initName = "_field_init_" + propertyDef.getName();
                    propertyDef.setInitFunc(initName);
                    var func = new LirFunctionDef(initName);
                    func.setHidden(true);
                    func.setReturnType(propertyDef.getType());
                    func.addParameter(new LirParameterDef("self", selfType, null, func));
                    var tmpVar = func.createAndAddTmpVariable(propertyDef.getType());
                    var bb = new LirBasicBlock("entry");
                    func.addBasicBlock(bb);
                    switch (propertyDef.getType()) {
                        case GdObjectType _ -> bb.appendNonTerminatorInstruction(new LiteralNullInsn(tmpVar.id()));
                        case GdVariantType _, GdNilType _ ->
                                bb.appendNonTerminatorInstruction(new LiteralNilInsn(tmpVar.id()));
                        case GdBoolType _ -> bb.appendNonTerminatorInstruction(new LiteralBoolInsn(tmpVar.id(), false));
                        case GdIntType _ -> bb.appendNonTerminatorInstruction(new LiteralIntInsn(tmpVar.id(), 0));
                        case GdFloatType _ -> bb.appendNonTerminatorInstruction(new LiteralFloatInsn(tmpVar.id(), 0.0));
                        case GdStringType _ ->
                                bb.appendNonTerminatorInstruction(new LiteralStringInsn(tmpVar.id(), ""));
                        case GdStringNameType _ ->
                                bb.appendNonTerminatorInstruction(new LiteralStringNameInsn(tmpVar.id(), ""));
                        case GdArrayType gdArrayType ->
                                bb.appendNonTerminatorInstruction(new ConstructArrayInsn(tmpVar.id(), gdArrayType.getValueType().getTypeName()));
                        case GdPackedArrayType _ ->
                                bb.appendNonTerminatorInstruction(new ConstructArrayInsn(tmpVar.id(), null));
                        case GdDictionaryType gdDictionaryType ->
                                bb.appendNonTerminatorInstruction(new ConstructDictionaryInsn(tmpVar.id(), gdDictionaryType.getKeyType().getTypeName(), gdDictionaryType.getValueType().getTypeName()));
                        default -> bb.appendNonTerminatorInstruction(new ConstructBuiltinInsn(tmpVar.id(), List.of()));
                    }
                    bb.setTerminator(new ReturnInsn(tmpVar.id()));
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
                var parameterNames = func.getParameters().stream()
                        .map(ParameterDef::getName)
                        .collect(HashSet<String>::new, HashSet::add, HashSet::addAll);
                for (var variable : func.getVariables().values()) {
                    if (parameterNames.contains(variable.id())) {
                        continue;
                    }
                    if (variable.ref()) {
                        continue;
                    }
                    // Discarded void-return calls no longer publish result slots, but backend still
                    // skips any stray void variables so invalid IR fails at the real opcode/value
                    // contract boundary instead of drifting into a fake constructor path.
                    if (variable.type() instanceof GdVoidType) {
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

    /// `initFunc == null` means backend still owns default-value helper synthesis. Once a property
    /// points at a named init function, backend only accepts an already materialized executable body.
    private void validatePropertyInitFunctionsReadyForCodegen() {
        for (var classDef : module.getClassDefs()) {
            for (var propertyDef : classDef.getProperties()) {
                validatePropertyInitFunctionReadyForCodegen(classDef, propertyDef);
            }
        }
    }

    private void validatePropertyInitFunctionReadyForCodegen(
            @NotNull LirClassDef classDef,
            @NotNull LirPropertyDef propertyDef
    ) {
        var function = resolvePropertyInitFunction(classDef, propertyDef);
        validatePropertyInitFunctionSignature(classDef, propertyDef, function);
        if (function.getBasicBlockCount() == 0 || function.getEntryBlockId().isEmpty()) {
            throw new IllegalStateException(
                    "Property '"
                            + classDef.getName()
                            + "."
                            + propertyDef.getName()
                            + "' references shell-only init function '"
                            + function.getName()
                            + "'; property init must be fully lowered before backend codegen"
            );
        }
        if (!function.hasBasicBlock(function.getEntryBlockId())) {
            throw new IllegalStateException(
                    "Property '"
                            + classDef.getName()
                            + "."
                            + propertyDef.getName()
                            + "' references init function '"
                            + function.getName()
                            + "' with invalid entry block ID: "
                            + function.getEntryBlockId()
            );
        }
    }

    private @NotNull LirFunctionDef resolvePropertyInitFunction(
            @NotNull LirClassDef classDef,
            @NotNull LirPropertyDef propertyDef
    ) {
        var initFuncName = propertyDef.getInitFunc();
        if (initFuncName == null) {
            throw new IllegalStateException(
                    "Property '" + classDef.getName() + "." + propertyDef.getName() + "' does not define initFunc"
            );
        }
        var matches = classDef.getFunctions().stream()
                .filter(function -> function.getName().equals(initFuncName))
                .toList();
        if (matches.isEmpty()) {
            throw new IllegalStateException(
                    "Property init function '"
                            + classDef.getName()
                            + "."
                            + initFuncName
                            + "' referenced by property '"
                            + propertyDef.getName()
                            + "' does not exist"
            );
        }
        if (matches.size() != 1) {
            throw new IllegalStateException(
                    "Expected exactly one property init function '"
                            + classDef.getName()
                            + "."
                            + initFuncName
                            + "' for property '"
                            + propertyDef.getName()
                            + "', but found "
                            + matches.size()
            );
        }
        return matches.getFirst();
    }

    /// Property-init helpers are always internal single-return helpers with the owning-class `self`
    /// parameter. Backend keeps this contract explicit so template rendering never has to guess
    /// whether a named `initFunc` still needs repair.
    private void validatePropertyInitFunctionSignature(
            @NotNull LirClassDef classDef,
            @NotNull LirPropertyDef propertyDef,
            @NotNull LirFunctionDef function
    ) {
        if (!function.isHidden()) {
            throw new IllegalStateException(
                    "Property '"
                            + classDef.getName()
                            + "."
                            + propertyDef.getName()
                            + "' references non-hidden init function '"
                            + function.getName()
                            + "'; property init helpers must stay internal to backend/template wiring"
            );
        }
        if (!function.getReturnType().equals(propertyDef.getType())) {
            throw new IllegalStateException(
                    "Property '"
                            + classDef.getName()
                            + "."
                            + propertyDef.getName()
                            + "' references init function '"
                            + function.getName()
                            + "' with mismatched return type "
                            + function.getReturnType().getTypeName()
                            + "; expected "
                            + propertyDef.getType().getTypeName()
            );
        }
        if (function.getParameterCount() != 1) {
            throw new IllegalStateException(
                    "Property '"
                            + classDef.getName()
                            + "."
                            + propertyDef.getName()
                            + "' references init function '"
                            + function.getName()
                            + "' with "
                            + function.getParameterCount()
                            + " parameters; expected exactly one owning-class self parameter"
            );
        }

        var selfParameter = function.getParameter(0);
        if (selfParameter == null
                || !selfParameter.getName().equals("self")
                || !selfParameter.getType().equals(new GdObjectType(classDef.getName()))) {
            throw new IllegalStateException(
                    "Property '"
                            + classDef.getName()
                            + "."
                            + propertyDef.getName()
                            + "' references init function '"
                            + function.getName()
                            + "' with invalid self parameter; expected `self: "
                            + classDef.getName()
                            + "`"
            );
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
                        .collect(HashSet<String>::new, HashSet::add, HashSet::addAll);
                for (var variable : func.getVariables().values()) {
                    if (!shouldInsertAutoGeneratedFinallyDestruct(variable, parameterNames)) {
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

    /// `__finally__` auto-cleanup is slot-based: it only targets managed local slots still owned by the
    /// current function. `_return_val` stays outside this set because it is the hidden return-publish
    /// boundary declared by `CBodyBuilder`, not a normal LIR variable slot.
    private boolean shouldInsertAutoGeneratedFinallyDestruct(@NotNull LirVariable variable,
                                                             @NotNull Set<String> parameterNames) {
        if ("_return_val".equals(variable.id())) {
            return false;
        }
        if (parameterNames.contains(variable.id()) || variable.ref()) {
            return false;
        }
        if (!variable.type().isDestroyable()) {
            return false;
        }
        if (variable.type() instanceof GdObjectType objectType) {
            var refCountedStatus = ctx.classRegistry().getRefCountedStatus(objectType);
            // Godot does not auto-free non-RefCounted objects at local scope exit.
            // They stay under explicit user-managed lifetime (`free`, `queue_free`, etc.).
            return refCountedStatus != RefCountedStatus.NO;
        }
        return true;
    }

    public @NotNull String generateFuncBody(@NotNull LirClassDef clazz,
                                            @NotNull LirFunctionDef func) {
        return generateFuncBody(clazz, func, EngineMethodUsageBuffer.noOp());
    }

    @SuppressWarnings("unchecked")
    @NotNull String generateFuncBody(@NotNull LirClassDef clazz,
                                     @NotNull LirFunctionDef func,
                                     @NotNull EngineMethodUsageBuffer usageBuffer) {
        if (ctx == null || module == null) {
            throw new IllegalStateException("CCodegen not prepared. Call prepare() before generateBlock().");
        }
        controlFlowValidator.validateFunction(func);
        lifecycleValidator.validateFunction(ctx, func);
        // Check if the entry block is valid
        if (!func.hasBasicBlock(func.getEntryBlockId())) {
            throw new IllegalArgumentException("Function " + func.getName() + " has invalid entry block ID: " + func.getEntryBlockId());
        }
        var bodyBuilder = new CBodyBuilder(helper, clazz, func, usageBuffer);
        // generate blocks
        bodyBuilder.appendRaw("goto " + func.getEntryBlockId() + ";\n");
        for (var bb : func) {
            bodyBuilder.beginBasicBlock(bb.id());
            for (int i = 0; i < bb.getInstructionCount(); i++) {
                var insn = bb.getInstruction(i);
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

    @NotNull String generateFuncBody(@NotNull LirClassDef clazz,
                                     @NotNull LirFunctionDef func,
                                     @NotNull EngineMethodUsageSession usageSession) {
        var usageBuffer = usageSession.newFunctionBuffer();
        var body = generateFuncBody(clazz, func, usageBuffer);
        usageSession.commit(usageBuffer);
        return body;
    }

    /// Renders the constructor-time property initializer apply body.
    /// The init helper still only produces a value; this method owns the direct backing-field first-write
    /// route so property initialization keeps unified slot-write semantics without becoming a setter call.
    public @NotNull String generatePropertyInitApplyBody(@NotNull LirClassDef clazz,
                                                         @NotNull LirPropertyDef property) {
        if (ctx == null || module == null) {
            throw new IllegalStateException("CCodegen not prepared. Call prepare() before generating property init apply code.");
        }
        var initFunction = resolvePropertyInitFunction(clazz, property);
        var bodyBuilder = new CBodyBuilder(helper, clazz, initFunction);
        bodyBuilder.applyPropertyInitializerFirstWrite(
                "self->" + property.getName(),
                property.getType(),
                clazz.getName() + "_" + initFunction.getName() + "(self)",
                initFunction.getReturnType(),
                initFunction.getReturnType() instanceof GdObjectType objectType
                        ? (objectType.checkGdccType(ctx.classRegistry()) ? CBodyBuilder.PtrKind.GDCC_PTR : CBodyBuilder.PtrKind.GODOT_PTR)
                        : CBodyBuilder.PtrKind.NON_OBJECT,
                // Property-init helpers are a dedicated fresh-producer entry: the apply helper must
                // consume the returned object directly instead of re-owning the field write.
                CBodyBuilder.OwnershipKind.OWNED
        );
        return bodyBuilder.build();
    }


    @Override
    public List<GeneratedFile> generate() {
        if (ctx == null || module == null) {
            throw new IllegalStateException("CCodegen not prepared. Call prepare() before generate().");
        }
        this.generateDefaultGetterSetterInitialization();
        this.validatePropertyInitFunctionsReadyForCodegen();
        this.generateFunctionPrepareBlock();
        this.ensureFunctionFinallyBlock();
        for (var classDef : module.getClassDefs()) {
            for (var function : classDef.getFunctions()) {
                controlFlowValidator.validateFunction(function);
                lifecycleValidator.validateFunction(ctx, function);
            }
        }
        try {
            var usageSession = new EngineMethodUsageSession();
            var bodyRender = new GenerateRenderFacade(
                    (classDef, func) -> generateFuncBody(classDef, func, usageSession),
                    this::generatePropertyInitApplyBody
            );
            var cTplCtx = Map.of(
                    "module", module,
                    "helper", helper,
                    "bodyRender", bodyRender
            );
            var cSrc = TemplateLoader.renderFromClasspath("template_451/entry.c.ftl", cTplCtx);
            var usedEngineMethods = usageSession.snapshot();
            var bindTplCtx = Map.of(
                    "module", module,
                    "helper", helper,
                    "usedEngineMethods", usedEngineMethods
            );
            var engineMethodBindsSrc = TemplateLoader.renderFromClasspath(
                    "template_451/engine_method_binds.h.ftl",
                    bindTplCtx
            );
            var hTplCtx = Map.of(
                    "module", module,
                    "helper", helper
            );
            var hSrc = TemplateLoader.renderFromClasspath("template_451/entry.h.ftl", hTplCtx);
            cSrc = CCodeFormatter.format(cSrc);
            engineMethodBindsSrc = CCodeFormatter.format(engineMethodBindsSrc);
            hSrc = CCodeFormatter.format(hSrc);

            var cBytes = cSrc.getBytes(StandardCharsets.UTF_8);
            var engineMethodBindsBytes = engineMethodBindsSrc.getBytes(StandardCharsets.UTF_8);
            var hBytes = hSrc.getBytes(StandardCharsets.UTF_8);

            var cFile = new GeneratedFile(cBytes, "entry.c");
            var engineMethodBindsFile = new GeneratedFile(engineMethodBindsBytes, "engine_method_binds.h");
            var hFile = new GeneratedFile(hBytes, "entry.h");
            return List.of(cFile, engineMethodBindsFile, hFile);
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
