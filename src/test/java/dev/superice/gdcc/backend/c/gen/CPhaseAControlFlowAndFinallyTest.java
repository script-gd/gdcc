package dev.superice.gdcc.backend.c.gen;

import dev.superice.gdcc.backend.CodegenContext;
import dev.superice.gdcc.backend.ProjectInfo;
import dev.superice.gdcc.enums.GodotVersion;
import dev.superice.gdcc.enums.LifecycleProvenance;
import dev.superice.gdcc.exception.InvalidInsnException;
import dev.superice.gdcc.gdextension.ExtensionAPI;
import dev.superice.gdcc.lir.LirBasicBlock;
import dev.superice.gdcc.lir.LirClassDef;
import dev.superice.gdcc.lir.LirFunctionDef;
import dev.superice.gdcc.lir.LirModule;
import dev.superice.gdcc.lir.LirParameterDef;
import dev.superice.gdcc.lir.insn.DestructInsn;
import dev.superice.gdcc.lir.insn.GotoInsn;
import dev.superice.gdcc.lir.insn.LiteralStringInsn;
import dev.superice.gdcc.lir.insn.NopInsn;
import dev.superice.gdcc.lir.insn.ReturnInsn;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.type.GdIntType;
import dev.superice.gdcc.type.GdStringType;
import dev.superice.gdcc.type.GdVoidType;
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
        entry.instructions().add(new ReturnInsn(null));
        func.addBasicBlock(entry);

        var finallyBlock = new LirBasicBlock("__finally__");
        finallyBlock.instructions().add(new ReturnInsn(null));
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
        entry.instructions().add(new ReturnInsn("result"));
        func.addBasicBlock(entry);

        var finallyBlock = new LirBasicBlock("__finally__");
        finallyBlock.instructions().add(new ReturnInsn("_return_val"));
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
        entry.instructions().add(new GotoInsn("__finally__"));
        func.addBasicBlock(entry);

        var finallyBlock = new LirBasicBlock("__finally__");
        finallyBlock.instructions().add(new ReturnInsn("_return_val"));
        func.addBasicBlock(finallyBlock);
        func.setEntryBlockId("entry");
        clazz.addFunction(func);

        var codegen = newCodegen(new LirModule("test_module", List.of(clazz)), List.of(clazz));
        var body = codegen.generateFuncBody(clazz, func);

        assertTrue(body.contains("return _return_val;"));
        assertFalse(body.contains("Return value variable ID _return_val does not exist"));
    }

    @Test
    @DisplayName("void function returning value should throw InvalidInsnException")
    void voidFunctionReturningValueShouldThrow() {
        var clazz = new LirClassDef("TestClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("void_func");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("v", GdIntType.INT);

        var entry = new LirBasicBlock("entry");
        entry.instructions().add(new ReturnInsn("v"));
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
        entry.instructions().add(new ReturnInsn("i"));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        clazz.addFunction(func);

        var module = new LirModule("test_module", List.of(clazz));
        var codegen = newCodegen(module, List.of(clazz));
        codegen.generate();

        var finallyBlock = func.getBasicBlock("__finally__");
        assertNotNull(finallyBlock);
        var instructions = finallyBlock.instructions();
        assertEquals(2, instructions.size());

        var destructInsn = assertInstanceOf(DestructInsn.class, instructions.getFirst());
        assertEquals("str", destructInsn.variableId());
        assertEquals(LifecycleProvenance.AUTO_GENERATED, destructInsn.getProvenance());

        var returnInsn = assertInstanceOf(ReturnInsn.class, instructions.getLast());
        assertEquals("_return_val", returnInsn.returnValueId());
    }

    @Test
    @DisplayName("generate should keep existing __finally__ instructions and append missing ones")
    void generateShouldKeepExistingFinallyForVoidFunction() {
        var clazz = new LirClassDef("TestClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("void_func");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("str", GdStringType.STRING);

        var entry = new LirBasicBlock("entry");
        entry.instructions().add(new ReturnInsn(null));
        func.addBasicBlock(entry);

        var existingFinally = new LirBasicBlock("__finally__");
        existingFinally.instructions().add(new NopInsn());
        func.addBasicBlock(existingFinally);
        func.setEntryBlockId("entry");
        clazz.addFunction(func);

        var module = new LirModule("test_module", List.of(clazz));
        var codegen = newCodegen(module, List.of(clazz));
        codegen.generate();

        var finallyBlock = func.getBasicBlock("__finally__");
        assertNotNull(finallyBlock);
        assertEquals(3, finallyBlock.instructions().size());

        assertInstanceOf(NopInsn.class, finallyBlock.instructions().getFirst());

        var destructInsn = assertInstanceOf(DestructInsn.class, finallyBlock.instructions().get(1));
        assertEquals("str", destructInsn.variableId());
        assertEquals(LifecycleProvenance.AUTO_GENERATED, destructInsn.getProvenance());

        var returnInsn = assertInstanceOf(ReturnInsn.class, finallyBlock.instructions().getLast());
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
        entry.instructions().add(new ReturnInsn(null));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        clazz.addFunction(func);

        var module = new LirModule("test_module", List.of(clazz));
        var codegen = newCodegen(module, List.of(clazz));
        codegen.generate();

        var prepareBlock = func.getBasicBlock("__prepare__");
        assertNotNull(prepareBlock);
        var hasPlainStrInit = prepareBlock.instructions().stream()
                .filter(LiteralStringInsn.class::isInstance)
                .map(LiteralStringInsn.class::cast)
                .anyMatch(insn -> insn.resultId().equals("plainStr"));
        var hasRefStrInit = prepareBlock.instructions().stream()
                .filter(LiteralStringInsn.class::isInstance)
                .map(LiteralStringInsn.class::cast)
                .anyMatch(insn -> insn.resultId().equals("refStr"));
        assertTrue(hasPlainStrInit);
        assertFalse(hasRefStrInit);
    }

    @Test
    @DisplayName("existing duplicated __prepare__ instructions should not be appended again and must warn")
    void duplicatePrepareInstructionsShouldWarnAndNotAppend() {
        var clazz = new LirClassDef("TestClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("void_func");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("plainStr", GdStringType.STRING);

        var entry = new LirBasicBlock("entry");
        entry.instructions().add(new ReturnInsn(null));
        func.addBasicBlock(entry);

        var existingPrepare = new LirBasicBlock("__prepare__");
        existingPrepare.instructions().add(new LiteralStringInsn("plainStr", ""));
        existingPrepare.instructions().add(new GotoInsn("entry"));
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
        assertEquals(2, prepareBlock.instructions().size());
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
        entry.instructions().add(new ReturnInsn(null));
        func.addBasicBlock(entry);

        var existingFinally = new LirBasicBlock("__finally__");
        existingFinally.instructions().add(new DestructInsn("str"));
        existingFinally.instructions().add(new ReturnInsn(null));
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
        assertEquals(2, finallyBlock.instructions().size());
        assertInstanceOf(DestructInsn.class, finallyBlock.instructions().getFirst());
        var returnInsn = assertInstanceOf(ReturnInsn.class, finallyBlock.instructions().getLast());
        assertNull(returnInsn.returnValueId());
        assertTrue(outputBuffer.toString(StandardCharsets.UTF_8).contains("already contains instruction"));
    }

    private CCodegen newCodegen(LirModule module, List<LirClassDef> gdccClasses) {
        var classRegistry = new ClassRegistry(new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()));
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
