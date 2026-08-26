package gd.script.gdcc.backend.c.gen;

import gd.script.gdcc.backend.CodegenContext;
import gd.script.gdcc.backend.GeneratedFile;
import gd.script.gdcc.backend.ProjectInfo;
import gd.script.gdcc.backend.c.gen.insn.ConstructInsnGen;
import gd.script.gdcc.enums.GdInstruction;
import gd.script.gdcc.enums.GodotVersion;
import gd.script.gdcc.exception.InvalidInsnException;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.lir.LirBasicBlock;
import gd.script.gdcc.lir.LirCaptureDef;
import gd.script.gdcc.lir.LirClassDef;
import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.lir.LirInstruction;
import gd.script.gdcc.lir.LirModule;
import gd.script.gdcc.lir.LirParameterDef;
import gd.script.gdcc.lir.insn.ConstructLambdaInsn;
import gd.script.gdcc.lir.insn.LiteralIntInsn;
import gd.script.gdcc.lir.insn.LiteralStringInsn;
import gd.script.gdcc.lir.insn.ReturnInsn;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdCallableType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdStringType;
import gd.script.gdcc.type.GdVoidType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// `construct_lambda` emits `gdcc_new_lambda_callable` plus a capture heap block, and
/// codegen fail-fasts when the lambda shell and operand list diverge.
final class ConstructLambdaInsnGenTest {
    @Test
    @DisplayName("construct_lambda opcode is registered on ConstructInsnGen")
    void constructLambdaOpcodeIsRegisteredForDispatch() {
        assertTrue(new ConstructInsnGen().getInsnOpcodes().contains(GdInstruction.CONSTRUCT_LAMBDA));
    }

    @Test
    @DisplayName("captureless construct_lambda uses NULL userdata and object_id 0")
    void capturelessConstructLambdaEmitsNullUserdataAndZeroObjectId() throws Exception {
        var clazz = newTestClass();
        addCapturelessLambda(clazz);
        var func = newFunction("make");
        func.createAndAddVariable("cb", new GdCallableType());
        entry(func).appendInstruction(new ConstructLambdaInsn("cb", "_lambda_0", List.of()));
        clazz.addFunction(func);

        var body = generateBody(clazz, func);
        assertTrue(body.contains("gdcc_new_lambda_callable("), body);
        assertTrue(body.contains("NULL"), body);
        assertTrue(body.contains("Worker__lambda_0_call"), body);
        assertTrue(body.contains("Worker__lambda_0_free"), body);
        assertTrue(body.contains("Worker__lambda_0_is_valid"), body);
        assertTrue(body.contains("Worker__lambda_0_get_argument_count"), body);
        assertFalse(body.contains("godot_mem_alloc"), body);
        assertFalse(body.contains("$self.instance_id"), body);
    }

    @Test
    @DisplayName("local int capture is copied by value into the heap capture block")
    void localIntCaptureCopiesByValueIntoHeapBlock() throws Exception {
        var clazz = newTestClass();
        addIntCaptureLambda(clazz);
        var func = newFunction("make");
        func.createAndAddVariable("seed", GdIntType.INT);
        func.createAndAddVariable("cb", new GdCallableType());
        entry(func).appendInstruction(new ConstructLambdaInsn(
                "cb",
                "_lambda_0",
                List.of(new LirInstruction.VariableOperand("seed"))
        ));
        clazz.addFunction(func);

        var body = generateBody(clazz, func);
        assertTrue(body.contains("Worker_Capture__lambda_0 *"), body);
        assertTrue(body.contains("godot_mem_alloc(sizeof(Worker_Capture__lambda_0))"), body);
        assertTrue(body.contains("->seed = $seed;"), body);
        assertTrue(body.contains("gdcc_new_lambda_callable("), body);
        assertFalse(body.contains("$self.instance_id"), body);
    }

    @Test
    @DisplayName("self capture writes cached instance_id into object_id")
    void selfCaptureWritesCachedInstanceId() throws Exception {
        var clazz = newTestClass();
        addSelfCaptureLambda(clazz);
        var func = newFunction("make");
        func.addParameter(new LirParameterDef("self", new GdObjectType(clazz.getName()), null, func));
        func.createAndAddVariable("cb", new GdCallableType());
        entry(func).appendInstruction(new ConstructLambdaInsn(
                "cb",
                "_lambda_0",
                List.of(new LirInstruction.VariableOperand("self"))
        ));
        clazz.addFunction(func);

        var body = generateBody(clazz, func);
        assertTrue(body.contains("gdcc_new_lambda_callable("), body);
        assertTrue(body.contains("$self.instance_id"), body);
        assertTrue(body.contains("->self = $self;"), body);
        assertTrue(body.contains("own_object("), body);
    }

    @Test
    @DisplayName("unknown lambda name fail-fasts before generating a callable")
    void unknownLambdaNameFailsFast() throws Exception {
        var clazz = newTestClass();
        var func = newFunction("make");
        func.createAndAddVariable("cb", new GdCallableType());
        entry(func).appendInstruction(new ConstructLambdaInsn("cb", "_lambda_missing", List.of()));
        clazz.addFunction(func);

        var ex = assertThrows(InvalidInsnException.class, () -> generateBody(clazz, func));
        assertTrue(ex.getMessage().contains("is not defined on this class"), ex.getMessage());
    }

    @Test
    @DisplayName("capture count mismatch fail-fasts")
    void captureCountMismatchFailsFast() throws Exception {
        var clazz = newTestClass();
        addIntCaptureLambda(clazz);
        var func = newFunction("make");
        func.createAndAddVariable("cb", new GdCallableType());
        entry(func).appendInstruction(new ConstructLambdaInsn("cb", "_lambda_0", List.of()));
        clazz.addFunction(func);

        var ex = assertThrows(InvalidInsnException.class, () -> generateBody(clazz, func));
        assertTrue(ex.getMessage().contains("capture count"), ex.getMessage());
    }

    @Test
    @DisplayName("capture name order mismatch fail-fasts")
    void captureNameOrderMismatchFailsFast() throws Exception {
        var clazz = newTestClass();
        addIntCaptureLambda(clazz);
        var func = newFunction("make");
        func.createAndAddVariable("offset", GdIntType.INT);
        func.createAndAddVariable("cb", new GdCallableType());
        entry(func).appendInstruction(new ConstructLambdaInsn(
                "cb",
                "_lambda_0",
                List.of(new LirInstruction.VariableOperand("offset"))
        ));
        clazz.addFunction(func);

        var ex = assertThrows(InvalidInsnException.class, () -> generateBody(clazz, func));
        assertTrue(ex.getMessage().contains("expected 'seed'"), ex.getMessage());
    }

    @Test
    @DisplayName("generate() excludes capture locals from __prepare__ and copies them in the prologue")
    void generateExcludesCaptureLocalsFromPrepareAndCopiesInPrologue() throws Exception {
        var clazz = newTestClass();
        var lambda = new LirFunctionDef("_lambda_0", "entry");
        lambda.setLambda(true);
        lambda.setHidden(true);
        lambda.setStatic(true);
        lambda.setReturnType(GdStringType.STRING);
        lambda.addCapture(new LirCaptureDef("label", GdStringType.STRING, lambda));
        lambda.createAndAddVariable("result", GdStringType.STRING);
        var lambdaEntry = new LirBasicBlock("entry");
        lambdaEntry.appendInstruction(new LiteralStringInsn("result", "ok"));
        lambdaEntry.appendInstruction(new ReturnInsn("result"));
        lambda.addBasicBlock(lambdaEntry);
        clazz.addFunction(lambda);

        var maker = newFunction("make");
        maker.createAndAddVariable("label", GdStringType.STRING);
        maker.createAndAddVariable("cb", new GdCallableType());
        entry(maker).appendInstruction(new LiteralStringInsn("label", "seed"));
        entry(maker).appendInstruction(new ConstructLambdaInsn(
                "cb",
                "_lambda_0",
                List.of(new LirInstruction.VariableOperand("label"))
        ));
        entry(maker).appendInstruction(new ReturnInsn(null));
        clazz.addFunction(maker);

        var files = generateFiles(clazz);
        var entryC = generatedFileText(files, "entry.c");
        var entryH = generatedFileText(files, "entry.h");

        assertTrue(entryH.contains("typedef struct Worker_Capture__lambda_0"), entryH);
        assertTrue(entryH.contains("godot_String label;"), entryH);
        assertTrue(entryH.contains("godot_String_destroy(&(captures->label));"), entryH);
        assertTrue(entryC.contains("$label = godot_new_String_with_String(&(_capture->label));"), entryC);
        assertFalse(entryC.contains("__prepare__:\n    $label = godot_new_String"), entryC);
        assertTrue(entryC.contains("gdcc_new_lambda_callable("), entryC);
        assertTrue(entryC.contains("godot_new_String_with_String(&($label))"), entryC);
    }

    @Test
    @DisplayName("coroutine lambda keeps construct_lambda capture surface and hands the block to the start thunk")
    void coroutineLambdaCaptureShouldPassCaptureToStartThunk() throws Exception {
        // `ConstructInsnGen` is deliberately coroutine-agnostic — the outer body still
        // allocates and fills the capture block exactly like a synchronous lambda. The coroutine
        // difference shows up only at the Callable invocation boundary: `call_func` forwards the
        // block as the start thunk's `_capture` tail argument, and the body reads typed frame
        // fields instead of running a `_capture->` prologue copy.
        var clazz = newTestClass();
        var lambda = new LirFunctionDef("_lambda_0", "entry");
        lambda.setLambda(true);
        lambda.setHidden(true);
        lambda.setStatic(true);
        lambda.setCoroutine(true);
        lambda.setReturnType(GdStringType.STRING);
        lambda.addCapture(new LirCaptureDef("label", GdStringType.STRING, lambda));
        lambda.addCapture(new LirCaptureDef("seed", GdIntType.INT, lambda));
        var lambdaEntry = new LirBasicBlock("entry");
        lambdaEntry.appendInstruction(new ReturnInsn("label"));
        lambda.addBasicBlock(lambdaEntry);
        clazz.addFunction(lambda);

        var maker = newFunction("make");
        maker.createAndAddVariable("label", GdStringType.STRING);
        maker.createAndAddVariable("seed", GdIntType.INT);
        maker.createAndAddVariable("cb", new GdCallableType());
        entry(maker).appendInstruction(new LiteralStringInsn("label", "seed"));
        entry(maker).appendInstruction(new LiteralIntInsn("seed", 7));
        entry(maker).appendInstruction(new ConstructLambdaInsn(
                "cb",
                "_lambda_0",
                List.of(new LirInstruction.VariableOperand("label"), new LirInstruction.VariableOperand("seed"))
        ));
        entry(maker).appendInstruction(new ReturnInsn(null));
        clazz.addFunction(maker);

        var files = generateFiles(clazz);
        var entryC = generatedFileText(files, "entry.c");
        var entryH = generatedFileText(files, "entry.h");

        // Outer-body construction surface is identical to the synchronous shape.
        assertTrue(entryC.contains("gdcc_new_lambda_callable("), entryC);
        assertTrue(entryC.contains("godot_mem_alloc(sizeof(Worker_Capture__lambda_0))"), entryC);
        assertTrue(entryC.contains("godot_new_String_with_String(&($label))"), entryC);
        // call_func enters the start thunk with the capture block as the tail argument.
        assertTrue(entryH.contains("Worker__lambda_0__coro_start(captures)"), entryH);
        // The coroutine body reads the typed capture frame field...
        assertTrue(entryC.contains("_coro_state->_coro_capture_label"), entryC);
        // ...and never runs the synchronous `_capture->` prologue first-write into a local slot.
        assertFalse(entryC.contains("$label = godot_new_String_with_String(&(_capture->label));"), entryC);
    }

    @Test
    @DisplayName("non-self-capturing lambda wrappers keep object_id 0 and no ObjectDB check")
    void nonSelfCapturingLambdaWrappersKeepStandaloneValidity() throws Exception {
        var clazz = newTestClass();
        addIntCaptureLambda(clazz);
        var files = generateFiles(clazz);
        var entryH = generatedFileText(files, "entry.h");

        assertTrue(entryH.contains("Worker__lambda_0_is_valid"), entryH);
        assertFalse(entryH.contains("godot_object_get_instance_from_id"), entryH);
        assertTrue(entryH.contains("return true;"), entryH);
    }

    @Test
    @DisplayName("self-capturing lambda wrappers consult ObjectDB by cached instance_id")
    void selfCapturingLambdaWrappersConsultObjectDb() throws Exception {
        var clazz = newTestClass();
        addSelfCaptureLambda(clazz);
        var files = generateFiles(clazz);
        var entryH = generatedFileText(files, "entry.h");

        assertTrue(entryH.contains("godot_object_get_instance_from_id(captures->self.instance_id)"), entryH);
        assertTrue(entryH.contains("release_object("), entryH);
    }

    private static void addCapturelessLambda(LirClassDef clazz) {
        var lambda = new LirFunctionDef("_lambda_0", "entry");
        lambda.setLambda(true);
        lambda.setHidden(true);
        lambda.setStatic(true);
        lambda.setReturnType(GdIntType.INT);
        lambda.createAndAddVariable("result", GdIntType.INT);
        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new LiteralIntInsn("result", 7));
        entry.appendInstruction(new ReturnInsn("result"));
        lambda.addBasicBlock(entry);
        clazz.addFunction(lambda);
    }

    private static void addIntCaptureLambda(LirClassDef clazz) {
        var lambda = new LirFunctionDef("_lambda_0", "entry");
        lambda.setLambda(true);
        lambda.setHidden(true);
        lambda.setStatic(true);
        lambda.setReturnType(GdIntType.INT);
        lambda.addCapture(new LirCaptureDef("seed", GdIntType.INT, lambda));
        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new ReturnInsn("seed"));
        lambda.addBasicBlock(entry);
        clazz.addFunction(lambda);
    }

    private static void addSelfCaptureLambda(LirClassDef clazz) {
        var lambda = new LirFunctionDef("_lambda_0", "entry");
        lambda.setLambda(true);
        lambda.setHidden(true);
        lambda.setStatic(true);
        lambda.setReturnType(GdVoidType.VOID);
        lambda.addCapture(new LirCaptureDef("self", new GdObjectType(clazz.getName()), lambda));
        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new ReturnInsn(null));
        lambda.addBasicBlock(entry);
        clazz.addFunction(lambda);
    }

    private static LirClassDef newTestClass() {
        return new LirClassDef("Worker", "RefCounted");
    }

    private static LirFunctionDef newFunction(String name) {
        var func = new LirFunctionDef(name);
        func.setReturnType(GdVoidType.VOID);
        var entry = new LirBasicBlock("entry");
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        return func;
    }

    private static LirBasicBlock entry(LirFunctionDef functionDef) {
        return functionDef.getBasicBlock("entry");
    }

    private static String generateBody(LirClassDef clazz, LirFunctionDef func) throws Exception {
        var module = new LirModule("test_module", List.of(clazz));
        var codegen = newCodegen(module);
        return codegen.generateFuncBody(clazz, func);
    }

    private static List<GeneratedFile> generateFiles(LirClassDef clazz) throws Exception {
        var module = new LirModule("test_module", List.of(clazz));
        var codegen = newCodegen(module);
        return codegen.generate();
    }

    private static CCodegen newCodegen(LirModule module) throws Exception {
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        classRegistry.addGdccClass(module.getClassDefs().getFirst());
        ProjectInfo projectInfo = new ProjectInfo("TestProject", GodotVersion.V451, Path.of(".")) {
        };
        var ctx = new CodegenContext(projectInfo, classRegistry);
        var codegen = new CCodegen();
        codegen.prepare(ctx, module);
        return codegen;
    }

    private static String generatedFileText(List<GeneratedFile> files, String filePath) {
        for (var file : files) {
            if (file.filePath().equals(filePath)) {
                return new String(file.contentWriter());
            }
        }
        throw new AssertionError("Missing generated file: " + filePath);
    }
}
