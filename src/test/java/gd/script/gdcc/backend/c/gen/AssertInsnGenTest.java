package gd.script.gdcc.backend.c.gen;

import gd.script.gdcc.backend.CodegenContext;
import gd.script.gdcc.backend.ProjectInfo;
import gd.script.gdcc.enums.GodotVersion;
import gd.script.gdcc.exception.InvalidInsnException;
import gd.script.gdcc.gdextension.ExtensionAPI;
import gd.script.gdcc.lir.LirBasicBlock;
import gd.script.gdcc.lir.LirClassDef;
import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.lir.LirInstruction;
import gd.script.gdcc.lir.LirModule;
import gd.script.gdcc.lir.insn.AssertInsn;
import gd.script.gdcc.lir.insn.GotoInsn;
import gd.script.gdcc.lir.insn.ReturnInsn;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdBoolType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdStringType;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.type.GdVariantType;
import gd.script.gdcc.type.GdVoidType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Backend coverage for the user-level `assert` guard:
/// the `gdcc_assert_failed` + default-return failure shape, and the IR fail-fast contracts
/// (condition must be bool, message must be String-assignable, no `__finally__`).
class AssertInsnGenTest {
    @Test
    @DisplayName("assert without message should fail via gdcc_assert_failed(NULL, ...) and default return")
    void assertWithoutMessageEmitsGuardAndDefaultReturn() {
        var func = newFunction("assert_plain", GdVoidType.VOID);
        func.createAndAddVariable("cond", GdBoolType.BOOL);
        appendEntryBlock(func, new AssertInsn("cond", null));

        var body = generateBody(func);

        assertTrue(body.contains("if (!($cond)) {"), body);
        assertTrue(body.contains("gdcc_assert_failed(NULL, __func__, __FILE__, __LINE__);"), body);
        assertTrue(body.contains("goto __finally__;"), body);
    }

    @Test
    @DisplayName("assert with message should pass the String slot by address")
    void assertWithMessagePassesStringPointer() {
        var func = newFunction("assert_message", GdVoidType.VOID);
        func.createAndAddVariable("cond", GdBoolType.BOOL);
        func.createAndAddVariable("msg", GdStringType.STRING);
        appendEntryBlock(func, new AssertInsn("cond", "msg"));

        var body = generateBody(func);

        assertTrue(body.contains("if (!($cond)) {"), body);
        assertTrue(body.contains("gdcc_assert_failed(&$msg, __func__, __FILE__, __LINE__);"), body);
        assertTrue(body.contains("goto __finally__;"), body);
    }

    @Test
    @DisplayName("non-void function should publish the default return value on the assert-fail edge")
    void nonVoidReturnPublishesDefaultOnAssertFail() {
        var func = newFunction("assert_non_void", GdIntType.INT);
        func.createAndAddVariable("cond", GdBoolType.BOOL);
        appendEntryBlock(func, new AssertInsn("cond", null));

        var body = generateBody(func);

        assertTrue(body.contains("gdcc_assert_failed(NULL, __func__, __FILE__, __LINE__);"), body);
        assertTrue(body.contains("_return_val = 0;"), body);
        assertTrue(body.contains("goto __finally__;"), body);
    }

    @Test
    @DisplayName("non-bool assert condition should fail fast")
    void nonBoolConditionFailsFast() {
        var func = newFunction("assert_int_condition", GdVoidType.VOID);
        func.createAndAddVariable("cond", GdIntType.INT);
        appendEntryBlock(func, new AssertInsn("cond", null));

        var ex = assertThrows(InvalidInsnException.class, () -> generateBody(func));
        assertTrue(ex.getMessage().contains("must be bool"), ex.getMessage());
    }

    @Test
    @DisplayName("missing assert condition variable should fail fast")
    void missingConditionVariableFailsFast() {
        var func = newFunction("assert_missing_condition", GdVoidType.VOID);
        appendEntryBlock(func, new AssertInsn("missing", null));

        var ex = assertThrows(InvalidInsnException.class, () -> generateBody(func));
        assertTrue(ex.getMessage().contains("condition variable not found"), ex.getMessage());
    }

    @Test
    @DisplayName("non-String assert message should fail fast")
    void nonStringMessageFailsFast() {
        var func = newFunction("assert_int_message", GdVoidType.VOID);
        func.createAndAddVariable("cond", GdBoolType.BOOL);
        func.createAndAddVariable("msg", GdIntType.INT);
        appendEntryBlock(func, new AssertInsn("cond", "msg"));

        var ex = assertThrows(InvalidInsnException.class, () -> generateBody(func));
        assertTrue(ex.getMessage().contains("must be assignable to String"), ex.getMessage());
    }

    @Test
    @DisplayName("Variant assert message is outside the current contract and should fail fast")
    void variantMessageFailsFast() {
        // The frontend boundary matrix would allow a Variant->String unpack route, but the assert
        // message slot is consumed without conversion, so backend validation remains strict and
        // preserves the LIR contract.
        var func = newFunction("assert_variant_message", GdVoidType.VOID);
        func.createAndAddVariable("cond", GdBoolType.BOOL);
        func.createAndAddVariable("msg", GdVariantType.VARIANT);
        appendEntryBlock(func, new AssertInsn("cond", "msg"));

        var ex = assertThrows(InvalidInsnException.class, () -> generateBody(func));
        assertTrue(ex.getMessage().contains("must be assignable to String"), ex.getMessage());
    }

    @Test
    @DisplayName("missing assert message variable should fail fast")
    void missingMessageVariableFailsFast() {
        var func = newFunction("assert_missing_message", GdVoidType.VOID);
        func.createAndAddVariable("cond", GdBoolType.BOOL);
        appendEntryBlock(func, new AssertInsn("cond", "missing"));

        var ex = assertThrows(InvalidInsnException.class, () -> generateBody(func));
        assertTrue(ex.getMessage().contains("message variable not found"), ex.getMessage());
    }

    @Test
    @DisplayName("assert inside __finally__ should fail fast instead of self-jumping")
    void assertInsideFinallyFailsFast() {
        var func = newFunction("assert_in_finally", GdVoidType.VOID);
        func.createAndAddVariable("cond", GdBoolType.BOOL);

        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new GotoInsn("__finally__"));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");

        var finallyBlock = new LirBasicBlock("__finally__");
        finallyBlock.appendInstruction(new AssertInsn("cond", null));
        finallyBlock.appendInstruction(new ReturnInsn(null));
        func.addBasicBlock(finallyBlock);

        var ex = assertThrows(InvalidInsnException.class, () -> generateBody(func));
        assertTrue(ex.getMessage().contains("__finally__"), ex.getMessage());
    }

    private static LirFunctionDef newFunction(String name, GdType returnType) {
        var func = new LirFunctionDef(name);
        func.setReturnType(returnType);
        return func;
    }

    private static void appendEntryBlock(LirFunctionDef func, LirInstruction instruction) {
        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(instruction);
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
    }

    private static String generateBody(LirFunctionDef func) {
        var workerClass = new LirClassDef("Worker", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        workerClass.addFunction(func);

        var api = new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        var codegen = new CCodegen();
        codegen.prepare(newContext(api, List.of(workerClass)), new LirModule("test_module", List.of(workerClass)));
        return codegen.generateFuncBody(workerClass, func);
    }

    private static CodegenContext newContext(ExtensionAPI api, List<LirClassDef> gdccClasses) {
        var classRegistry = new ClassRegistry(api);
        for (var gdccClass : gdccClasses) {
            classRegistry.addGdccClass(gdccClass);
        }
        ProjectInfo projectInfo = new ProjectInfo("TestProject", GodotVersion.V451, Path.of(".")) {
        };
        return new CodegenContext(projectInfo, classRegistry, true);
    }
}
