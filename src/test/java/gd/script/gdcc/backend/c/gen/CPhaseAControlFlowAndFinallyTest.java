package gd.script.gdcc.backend.c.gen;

import gd.script.gdcc.backend.CodegenContext;
import gd.script.gdcc.backend.ProjectInfo;
import gd.script.gdcc.enums.GodotVersion;
import gd.script.gdcc.enums.LifecycleProvenance;
import gd.script.gdcc.exception.InvalidControlFlowGraphException;
import gd.script.gdcc.exception.InvalidInsnException;
import gd.script.gdcc.gdextension.ExtensionAPI;
import gd.script.gdcc.lir.LirBasicBlock;
import gd.script.gdcc.lir.LirClassDef;
import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.lir.LirModule;
import gd.script.gdcc.lir.LirParameterDef;
import gd.script.gdcc.lir.insn.DestructInsn;
import gd.script.gdcc.lir.insn.GotoInsn;
import gd.script.gdcc.lir.insn.LiteralStringInsn;
import gd.script.gdcc.lir.insn.NopInsn;
import gd.script.gdcc.lir.insn.ReturnInsn;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdStringType;
import gd.script.gdcc.type.GdVoidType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class CPhaseAControlFlowAndFinallyTest {
    @Test
    @DisplayName("void return in non-finally block should jump to __finally__")
    void voidReturnOutsideFinallyShouldJumpToFinally() {
        var clazz = new LirClassDef("TestClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("void_func");
        func.setReturnType(GdVoidType.VOID);

        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new ReturnInsn(null));
        func.addBasicBlock(entry);

        var finallyBlock = new LirBasicBlock("__finally__");
        finallyBlock.appendInstruction(new ReturnInsn(null));
        func.addBasicBlock(finallyBlock);
        func.setEntryBlockId("entry");
        clazz.addFunction(func);

        var codegen = newCodegen(new LirModule("test_module", List.of(clazz)), List.of(clazz));
        var body = codegen.generateFuncBody(clazz, func);

        assertTrue(body.contains("goto __finally__;"));
        assertTrue(body.contains("__finally__: // __finally__"));
        assertTrue(body.contains("return;"));
    }

    @Test
    @DisplayName("non-void return in non-finally block should write _return_val then jump")
    void nonVoidReturnOutsideFinallyShouldUseReturnSlot() {
        var clazz = new LirClassDef("TestClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("int_func");
        func.setReturnType(GdIntType.INT);
        func.createAndAddVariable("result", GdIntType.INT);

        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new ReturnInsn("result"));
        func.addBasicBlock(entry);

        var finallyBlock = new LirBasicBlock("__finally__");
        finallyBlock.appendInstruction(new ReturnInsn("_return_val"));
        func.addBasicBlock(finallyBlock);
        func.setEntryBlockId("entry");
        clazz.addFunction(func);

        var codegen = newCodegen(new LirModule("test_module", List.of(clazz)), List.of(clazz));
        var body = codegen.generateFuncBody(clazz, func);

        assertTrue(body.contains("_return_val = $result;"));
        assertTrue(body.contains("goto __finally__;"));
        assertTrue(body.contains("return _return_val;"));
    }

    @Test
    @DisplayName("ReturnInsn(_return_val) in __finally__ should not depend on LIR variable table")
    void returnSlotSentinelShouldNotNeedVariableDefinition() {
        var clazz = new LirClassDef("TestClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("int_func");
        func.setReturnType(GdIntType.INT);

        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new GotoInsn("__finally__"));
        func.addBasicBlock(entry);

        var finallyBlock = new LirBasicBlock("__finally__");
        finallyBlock.appendInstruction(new ReturnInsn("_return_val"));
        func.addBasicBlock(finallyBlock);
        func.setEntryBlockId("entry");
        clazz.addFunction(func);

        var codegen = newCodegen(new LirModule("test_module", List.of(clazz)), List.of(clazz));
        var body = codegen.generateFuncBody(clazz, func);

        assertTrue(body.contains("return _return_val;"));
        assertFalse(body.contains("Return value variable ID _return_val does not exist"));
    }

    @Test
    @DisplayName("non-void __finally__ must return _return_val sentinel")
    void nonVoidFinallyMustReturnReturnSlotSentinel() {
        var clazz = new LirClassDef("TestClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("int_func");
        func.setReturnType(GdIntType.INT);
        func.createAndAddVariable("result", GdIntType.INT);

        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new GotoInsn("__finally__"));
        func.addBasicBlock(entry);

        var finallyBlock = new LirBasicBlock("__finally__");
        finallyBlock.appendInstruction(new ReturnInsn("result"));
        func.addBasicBlock(finallyBlock);
        func.setEntryBlockId("entry");
        clazz.addFunction(func);

        var codegen = newCodegen(new LirModule("test_module", List.of(clazz)), List.of(clazz));
        var ex = assertThrows(InvalidControlFlowGraphException.class, () -> codegen.generateFuncBody(clazz, func));

        assertTrue(ex.getMessage().contains("_return_val"), ex.getMessage());
    }

    @Test
    @DisplayName("moved local object return should clear source before finally cleanup and never release _return_val")
    void movedLocalObjectReturnShouldClearSourceBeforeFinallyCleanup() {
        var clazz = new LirClassDef("TestClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("resource_func");
        func.setReturnType(new GdObjectType("RefCounted"));
        func.createAndAddVariable("obj", new GdObjectType("RefCounted"));

        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new ReturnInsn("obj"));
        func.addBasicBlock(entry);

        var finallyBlock = new LirBasicBlock("__finally__");
        finallyBlock.appendInstruction(new DestructInsn("obj", LifecycleProvenance.AUTO_GENERATED));
        finallyBlock.appendInstruction(new ReturnInsn("_return_val"));
        func.addBasicBlock(finallyBlock);
        func.setEntryBlockId("entry");
        clazz.addFunction(func);

        var codegen = newCodegen(new LirModule("test_module", List.of(clazz)), List.of(clazz));
        var body = codegen.generateFuncBody(clazz, func);

        assertTrue(body.contains("_return_val = $obj;"), body);
        assertTrue(body.contains("$obj = (gdcc_RefCounted_fat_ptr){ 0 };"), body);
        assertTrue(body.contains("release_object(gdcc_RefCounted_fat_ptr_live_object($obj));"), body);
        assertFalse(body.contains("release_object(_return_val);"), body);
        assertTrue(body.contains("return _return_val;"), body);
        assertTrue(
                body.indexOf("$obj = (gdcc_RefCounted_fat_ptr){ 0 };")
                        < body.indexOf("release_object(gdcc_RefCounted_fat_ptr_live_object($obj));"),
                "Move-return source must be cleared before auto cleanup. Actual:\n" + body
        );
    }

    @Test
    @DisplayName("void function returning value should throw InvalidInsnException")
    void voidFunctionReturningValueShouldThrow() {
        var clazz = new LirClassDef("TestClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("void_func");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("v", GdIntType.INT);

        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new ReturnInsn("v"));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        clazz.addFunction(func);

        var codegen = newCodegen(new LirModule("test_module", List.of(clazz)), List.of(clazz));
        var ex = assertThrows(InvalidInsnException.class, () -> codegen.generateFuncBody(clazz, func));
        assertInstanceOf(InvalidInsnException.class, ex);
    }

    @Test
    @DisplayName("generate should inject default __finally__ with destruct + ReturnInsn(_return_val)")
    void generateShouldInjectDefaultFinallyForNonVoidFunction() {
        var clazz = new LirClassDef("TestClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("int_func");
        func.setReturnType(GdIntType.INT);
        func.addParameter(new LirParameterDef("p", GdIntType.INT, null, func));
        func.createAndAddVariable("str", GdStringType.STRING);
        func.createAndAddVariable("i", GdIntType.INT);
        func.createAndAddRefVariable("refStr", GdStringType.STRING);

        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new ReturnInsn("i"));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        clazz.addFunction(func);

        var module = new LirModule("test_module", List.of(clazz));
        var codegen = newCodegen(module, List.of(clazz));
        codegen.generate();

        var finallyBlock = func.getBasicBlock("__finally__");
        assertNotNull(finallyBlock);
        var instructions = finallyBlock.getInstructions();
        assertEquals(2, instructions.size());

        var destructInsn = assertInstanceOf(DestructInsn.class, instructions.getFirst());
        assertEquals("str", destructInsn.variableId());
        assertEquals(LifecycleProvenance.AUTO_GENERATED, destructInsn.getProvenance());

        var returnInsn = assertInstanceOf(ReturnInsn.class, instructions.getLast());
        assertEquals("_return_val", returnInsn.returnValueId());
    }

    @Test
    @DisplayName("generate should skip AUTO_GENERATED destruct for non-RefCounted object locals")
    void generateShouldSkipAutoGeneratedDestructForNonRefCountedObjectLocals() {
        var countedWorker = new LirClassDef("CountedWorker", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var plainWorker = new LirClassDef("PlainWorker", "Node", false, false, Map.of(), List.of(), List.of(), List.of());
        var clazz = new LirClassDef("TestClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("mixed_cleanup");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("label", GdStringType.STRING);
        func.createAndAddVariable("counted", new GdObjectType("CountedWorker"));
        func.createAndAddVariable("plain", new GdObjectType("PlainWorker"));

        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new ReturnInsn(null));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        clazz.addFunction(func);

        var module = new LirModule("test_module", List.of(clazz, countedWorker, plainWorker));
        var codegen = newCodegen(module, List.of(clazz, countedWorker, plainWorker));
        codegen.generate();

        var finallyBlock = func.getBasicBlock("__finally__");
        assertNotNull(finallyBlock);
        var destructIds = finallyBlock.getInstructions().stream()
                .filter(DestructInsn.class::isInstance)
                .map(DestructInsn.class::cast)
                .map(DestructInsn::variableId)
                .toList();
        assertTrue(destructIds.contains("label"), "Destroyable value-semantic locals should still be auto-destructed.");
        assertTrue(destructIds.contains("counted"), "RefCounted object locals should still be auto-released.");
        assertFalse(destructIds.contains("plain"), "Non-RefCounted object locals must not be auto-destroyed.");
        assertInstanceOf(ReturnInsn.class, finallyBlock.getInstructions().getLast());
    }

    @Test
    @DisplayName("generate should auto-cleanup RefCounted object locals but never inject cleanup for _return_val")
    void generateShouldAutoCleanupObjectLocalsButExcludeReturnSlot() {
        var clazz = new LirClassDef("TestClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("object_func");
        // Unknown object types fail fast during fat-pointer type collection, so the managed-cleanup
        // contract is anchored with a known GDCC RefCounted type instead.
        var managedType = new GdObjectType("TestClass");
        func.setReturnType(managedType);
        func.addParameter(new LirParameterDef("param", managedType, null, func));
        func.createAndAddVariable("managedLocal", managedType);

        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new ReturnInsn("param"));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        clazz.addFunction(func);

        var module = new LirModule("test_module", List.of(clazz));
        var codegen = newCodegen(module, List.of(clazz));
        codegen.generate();

        var finallyBlock = func.getBasicBlock("__finally__");
        assertNotNull(finallyBlock);
        var destructIds = finallyBlock.getInstructions().stream()
                .filter(DestructInsn.class::isInstance)
                .map(DestructInsn.class::cast)
                .map(DestructInsn::variableId)
                .toList();
        assertTrue(destructIds.contains("managedLocal"), "RefCounted object locals should stay in managed auto-cleanup set.");
        assertFalse(destructIds.contains("_return_val"), "The hidden return-publish slot must never be auto-cleaned as a local variable.");
        assertEquals("_return_val", assertInstanceOf(ReturnInsn.class, finallyBlock.getInstructions().getLast()).returnValueId());

        var body = codegen.generateFuncBody(clazz, func);
        assertTrue(body.contains(
                "release_object(gdcc_TestClass_fat_ptr_live_object($managedLocal));"
        ), body);
        assertFalse(body.contains("release_object(_return_val);"), body);
        assertFalse(body.contains("try_release_object(_return_val);"), body);
    }

    @Test
    @DisplayName("generate should keep existing __finally__ instructions and append missing ones")
    void generateShouldKeepExistingFinallyForVoidFunction() {
        var clazz = new LirClassDef("TestClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("void_func");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("str", GdStringType.STRING);

        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new ReturnInsn(null));
        func.addBasicBlock(entry);

        var existingFinally = new LirBasicBlock("__finally__");
        existingFinally.appendInstruction(new NopInsn());
        func.addBasicBlock(existingFinally);
        func.setEntryBlockId("entry");
        clazz.addFunction(func);

        var module = new LirModule("test_module", List.of(clazz));
        var codegen = newCodegen(module, List.of(clazz));
        codegen.generate();

        var finallyBlock = func.getBasicBlock("__finally__");
        assertNotNull(finallyBlock);
        assertEquals(3, finallyBlock.getInstructionCount());

        assertInstanceOf(NopInsn.class, finallyBlock.getInstructions().getFirst());

        var destructInsn = assertInstanceOf(DestructInsn.class, finallyBlock.getInstruction(1));
        assertEquals("str", destructInsn.variableId());
        assertEquals(LifecycleProvenance.AUTO_GENERATED, destructInsn.getProvenance());

        var returnInsn = assertInstanceOf(ReturnInsn.class, finallyBlock.getInstructions().getLast());
        assertNull(returnInsn.returnValueId());
    }

    @Test
    @DisplayName("__prepare__ should skip ref variables during initialization")
    void prepareShouldSkipRefVariables() {
        var clazz = new LirClassDef("TestClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("void_func");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("plainStr", GdStringType.STRING);
        func.createAndAddRefVariable("refStr", GdStringType.STRING);

        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new ReturnInsn(null));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        clazz.addFunction(func);

        var module = new LirModule("test_module", List.of(clazz));
        var codegen = newCodegen(module, List.of(clazz));
        codegen.generate();

        var prepareBlock = func.getBasicBlock("__prepare__");
        assertNotNull(prepareBlock);
        var hasPlainStrInit = prepareBlock.getInstructions().stream()
                .filter(LiteralStringInsn.class::isInstance)
                .map(LiteralStringInsn.class::cast)
                .anyMatch(insn -> "plainStr".equals(insn.resultId()));
        var hasRefStrInit = prepareBlock.getInstructions().stream()
                .filter(LiteralStringInsn.class::isInstance)
                .map(LiteralStringInsn.class::cast)
                .anyMatch(insn -> "refStr".equals(insn.resultId()));
        assertTrue(hasPlainStrInit);
        assertFalse(hasRefStrInit);
    }

    @Test
    @DisplayName("__prepare__ should skip void variables but keep neighboring non-void initialization")
    void prepareShouldSkipVoidVariablesButKeepOtherInitializers() {
        var clazz = new LirClassDef("TestClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("void_slot_func");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("plainStr", GdStringType.STRING);
        func.createAndAddVariable("voidTmp", GdVoidType.VOID);

        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new ReturnInsn(null));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        clazz.addFunction(func);

        var module = new LirModule("test_module", List.of(clazz));
        var codegen = newCodegen(module, List.of(clazz));
        codegen.generate();

        var prepareBlock = func.getBasicBlock("__prepare__");
        assertNotNull(prepareBlock);
        var hasPlainStrInit = prepareBlock.getInstructions().stream()
                .filter(LiteralStringInsn.class::isInstance)
                .map(LiteralStringInsn.class::cast)
                .anyMatch(insn -> "plainStr".equals(insn.resultId()));
        var hasVoidInit = prepareBlock.getInstructions().stream()
                .anyMatch(insn -> "voidTmp".equals(insn.resultId()));
        assertTrue(hasPlainStrInit);
        assertFalse(hasVoidInit);
        assertEquals(2, prepareBlock.getInstructionCount());
        assertInstanceOf(GotoInsn.class, prepareBlock.getInstructions().getLast());
    }

    @Test
    @DisplayName("existing duplicated __prepare__ instructions should not be appended again and must warn")
    void duplicatePrepareInstructionsShouldWarnAndNotAppend() {
        var clazz = new LirClassDef("TestClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("void_func");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("plainStr", GdStringType.STRING);

        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new ReturnInsn(null));
        func.addBasicBlock(entry);

        var existingPrepare = new LirBasicBlock("__prepare__");
        existingPrepare.appendInstruction(new LiteralStringInsn("plainStr", ""));
        existingPrepare.appendInstruction(new GotoInsn("entry"));
        func.addBasicBlock(existingPrepare);
        func.setEntryBlockId("entry");
        clazz.addFunction(func);

        var module = new LirModule("test_module", List.of(clazz));
        var codegen = newCodegen(module, List.of(clazz));
        var outputBuffer = new ByteArrayOutputStream();
        var capture = new PrintStream(outputBuffer, true, StandardCharsets.UTF_8);
        var originalOut = System.out;
        try {
            System.setOut(capture);
            codegen.generate();
        } finally {
            System.setOut(originalOut);
            capture.close();
        }

        var prepareBlock = func.getBasicBlock("__prepare__");
        assertNotNull(prepareBlock);
        assertEquals(2, prepareBlock.getInstructionCount());
        assertTrue(outputBuffer.toString(StandardCharsets.UTF_8).contains("already contains instruction"));
    }

    @Test
    @DisplayName("existing duplicated __finally__ instructions should not be appended again and must warn")
    void duplicateFinallyInstructionsShouldWarnAndNotAppend() {
        var clazz = new LirClassDef("TestClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("void_func");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("str", GdStringType.STRING);

        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new ReturnInsn(null));
        func.addBasicBlock(entry);

        var existingFinally = new LirBasicBlock("__finally__");
        existingFinally.appendInstruction(new DestructInsn("str"));
        existingFinally.appendInstruction(new ReturnInsn(null));
        func.addBasicBlock(existingFinally);
        func.setEntryBlockId("entry");
        clazz.addFunction(func);

        var module = new LirModule("test_module", List.of(clazz));
        var codegen = newCodegen(module, List.of(clazz));
        var outputBuffer = new ByteArrayOutputStream();
        var capture = new PrintStream(outputBuffer, true, StandardCharsets.UTF_8);
        var originalOut = System.out;
        try {
            System.setOut(capture);
            codegen.generate();
        } finally {
            System.setOut(originalOut);
            capture.close();
        }

        var finallyBlock = func.getBasicBlock("__finally__");
        assertNotNull(finallyBlock);
        assertEquals(2, finallyBlock.getInstructionCount());
        assertInstanceOf(DestructInsn.class, finallyBlock.getInstructions().getFirst());
        var returnInsn = assertInstanceOf(ReturnInsn.class, finallyBlock.getInstructions().getLast());
        assertNull(returnInsn.returnValueId());
        assertTrue(outputBuffer.toString(StandardCharsets.UTF_8).contains("already contains instruction"));
    }

    private CCodegen newCodegen(LirModule module, List<LirClassDef> gdccClasses) {
        // Engine base types must be registered so fat-pointer specs resolve for superclasses / returns.
        var objectClass = new gd.script.gdcc.gdextension.ExtensionGdClass(
                "Object", false, true, null, "core", List.of(), List.of(), List.of(), List.of(), List.of()
        );
        var refCountedClass = new gd.script.gdcc.gdextension.ExtensionGdClass(
                "RefCounted", true, true, "Object", "core", List.of(), List.of(), List.of(), List.of(), List.of()
        );
        var classRegistry = new ClassRegistry(new ExtensionAPI(
                null, List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(objectClass, refCountedClass), List.of(), List.of()
        ));
        for (var gdccClass : gdccClasses) {
            classRegistry.addGdccClass(gdccClass);
        }
        ProjectInfo projectInfo = new ProjectInfo("TestProject", GodotVersion.V451, Path.of(".")) {
        };
        var ctx = new CodegenContext(projectInfo, classRegistry);
        var codegen = new CCodegen();
        codegen.prepare(ctx, module);
        return codegen;
    }
}
