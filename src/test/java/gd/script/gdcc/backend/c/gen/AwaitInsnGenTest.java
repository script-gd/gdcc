package gd.script.gdcc.backend.c.gen;

import gd.script.gdcc.backend.CodegenContext;
import gd.script.gdcc.backend.ProjectInfo;
import gd.script.gdcc.enums.GodotVersion;
import gd.script.gdcc.exception.InvalidInsnException;
import gd.script.gdcc.gdextension.ExtensionAPI;
import gd.script.gdcc.lir.LirBasicBlock;
import gd.script.gdcc.lir.LirClassDef;
import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.lir.LirModule;
import gd.script.gdcc.lir.LirParameterDef;
import gd.script.gdcc.lir.insn.AwaitInsn;
import gd.script.gdcc.lir.insn.ReturnInsn;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdSignalType;
import gd.script.gdcc.type.GdVariantType;
import gd.script.gdcc.type.GdVoidType;
import gd.script.gdcc.type.GdccCoroStateType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// String-anchor tests for `AwaitInsnGen` (contract: `gdcc_low_ir.md` §Coroutine Instructions,
/// `frontend_await_implementation.md` §7): signal / static-state / dynamic dispatch paths,
/// the moved-from NULL source slot, the typed `out_typed` channel, and the cancel check after
/// every resume point. Behavioral anchors (not text-only): the cancel check must sit between
/// the helper call and any result-slot read, because a cancel-resume leaves the result channel
/// unwritten.
class AwaitInsnGenTest {
    @Test
    @DisplayName("AWAIT on Signal should render one-shot wait, cancel check, then Variant slot-write")
    void awaitSignalShouldRenderSignalPath() {
        var func = newCoroutine("run");
        func.createAndAddVariable("sig", new GdSignalType());
        func.createAndAddVariable("res", GdVariantType.VARIANT);
        entry(func).appendInstruction(new AwaitInsn("res", "sig"));
        entry(func).setTerminator(new ReturnInsn(null));

        var body = generateBody(func);
        assertContainsAll(
                body,
                "gdcc_coro_await_signal(&$sig, &__gdcc_tmp_await_signal_out_0, _co, &_coro_state->_coro_header);",
                "if (_coro_state->_coro_header.cancel) {",
                "goto __finally__;",
                "$res = godot_new_Variant_with_Variant(&__gdcc_tmp_await_signal_out_0);",
                "godot_Variant_destroy(&__gdcc_tmp_await_signal_out_0);"
        );
        // The cancel check must precede any read of the resume-value temp: a cancel-resume
        // leaves it unwritten.
        assertOrdered(
                body,
                "gdcc_coro_await_signal(",
                "_coro_state->_coro_header.cancel",
                "$res = godot_new_Variant_with_Variant("
        );
        // Slot-write discipline on overwrite: the previous Variant in the result slot is
        // destroyed before the new resume value is assigned (loop-reawait safe).
        assertOrdered(body, "godot_Variant_destroy(&$res);", "$res = godot_new_Variant_with_Variant(");
    }

    @Test
    @DisplayName("AWAIT on Signal with typed result should unpack through the Variant boundary")
    void awaitSignalTypedResultShouldUnpack() {
        var func = newCoroutine("run");
        func.createAndAddVariable("sig", new GdSignalType());
        func.createAndAddVariable("count", GdIntType.INT);
        entry(func).appendInstruction(new AwaitInsn("count", "sig"));
        entry(func).setTerminator(new ReturnInsn(null));

        var body = generateBody(func);
        assertContainsAll(
                body,
                "gdcc_coro_await_signal(&$sig, ",
                "$count = godot_new_int_with_Variant(&__gdcc_tmp_await_signal_out_0);"
        );
        assertOrdered(body, "_coro_state->_coro_header.cancel", "$count = godot_new_int_with_Variant(");
    }

    @Test
    @DisplayName("AWAIT on compiler::GdccCoroState should move-from only after successful identification")
    void awaitStateShouldConsumeOwnedReference() {
        var func = newCoroutine("run");
        func.createAndAddVariable("state", GdccCoroStateType.CORO_STATE);
        func.createAndAddVariable("res", GdIntType.INT);
        entry(func).appendInstruction(new AwaitInsn("res", "state"));
        entry(func).setTerminator(new ReturnInsn(null));

        var body = generateBody(func);
        assertContainsAll(
                body,
                "gdcc_coro_state_header *__gdcc_await_callee_entry_0 = gdcc_coro_state_identify($state);",
                "if (__gdcc_await_callee_entry_0 != NULL) {",
                "$state = NULL;",
                "gdcc_coro_await_state(__gdcc_await_callee_entry_0, &$res, _co, &_coro_state->_coro_header);",
                "if (_coro_state->_coro_header.cancel) {"
        );
        // Ownership transfer order: a recognized slot is reset before the helper consumes the
        // reference. An unrecognized slot stays owned for __finally__; the cancel check follows
        // the helper call because a cancel-resume returns without writing out.
        assertOrdered(
                body,
                "gdcc_coro_state_identify($state)",
                "if (__gdcc_await_callee_entry_0 != NULL)",
                "$state = NULL;",
                "gdcc_coro_await_state(",
                "_coro_state->_coro_header.cancel"
        );
    }

    @Test
    @DisplayName("AWAIT on coroutine state should address coroutine frame parameters as the typed result slot")
    void awaitStateShouldTargetFrameParameterResult() {
        var func = newCoroutine("run");
        func.addParameter(new LirParameterDef("count", GdIntType.INT, null, func));
        func.createAndAddVariable("state", GdccCoroStateType.CORO_STATE);
        entry(func).appendInstruction(new AwaitInsn("count", "state"));
        entry(func).setTerminator(new ReturnInsn(null));

        var body = generateBody(func);
        assertTrue(
                body.contains("gdcc_coro_await_state(__gdcc_await_callee_entry_0, &_coro_state->_coro_param_count, _co, &_coro_state->_coro_header);"),
                body
        );
    }

    @Test
    @DisplayName("AWAIT on Variant should render runtime dispatch with staged Variant result")
    void awaitDynamicShouldRenderDynamicPath() {
        var func = newCoroutine("run");
        func.createAndAddVariable("target", GdVariantType.VARIANT);
        func.createAndAddVariable("res", GdVariantType.VARIANT);
        entry(func).appendInstruction(new AwaitInsn("res", "target"));
        entry(func).setTerminator(new ReturnInsn(null));

        var body = generateBody(func);
        assertContainsAll(
                body,
                "gdcc_coro_await_dynamic(&$target, &__gdcc_tmp_await_dynamic_out_0, _co, &_coro_state->_coro_header);",
                "if (_coro_state->_coro_header.cancel) {",
                "$res = godot_new_Variant_with_Variant(&__gdcc_tmp_await_dynamic_out_0);"
        );
        assertOrdered(body, "gdcc_coro_await_dynamic(", "_coro_state->_coro_header.cancel", "$res = godot_new_Variant_with_Variant(");
        // Same overwrite discipline as the signal path: old result value destroyed first.
        assertOrdered(body, "godot_Variant_destroy(&$res);", "$res = godot_new_Variant_with_Variant(");
    }

    @Test
    @DisplayName("AWAIT in a non-coroutine function should fail fast")
    void awaitInNonCoroutineShouldFailFast() {
        var func = newSyncFunction("run");
        func.createAndAddVariable("sig", new GdSignalType());
        func.createAndAddVariable("res", GdVariantType.VARIANT);
        entry(func).appendInstruction(new AwaitInsn("res", "sig"));
        entry(func).setTerminator(new ReturnInsn(null));

        var ex = assertThrows(InvalidInsnException.class, () -> generateBody(func));
        assertTrue(ex.getMessage().contains("await is only valid inside an is_coroutine function"), ex.getMessage());
    }

    @Test
    @DisplayName("AWAIT on a non Signal/Variant/GdccCoroState operand should fail fast")
    void awaitNonAwaitableOperandShouldFailFast() {
        var func = newCoroutine("run");
        func.createAndAddVariable("count", GdIntType.INT);
        func.createAndAddVariable("res", GdVariantType.VARIANT);
        entry(func).appendInstruction(new AwaitInsn("res", "count"));
        entry(func).setTerminator(new ReturnInsn(null));

        var ex = assertThrows(InvalidInsnException.class, () -> generateBody(func));
        assertTrue(ex.getMessage().contains("await operand must be Signal, Variant or compiler::GdccCoroState"), ex.getMessage());
    }

    @Test
    @DisplayName("AWAIT must not publish a compiler-only result")
    void awaitCompilerOnlyResultShouldFailFast() {
        var func = newCoroutine("run");
        func.createAndAddVariable("target", GdVariantType.VARIANT);
        func.createAndAddVariable("res", GdccCoroStateType.CORO_STATE);
        entry(func).appendInstruction(new AwaitInsn("res", "target"));
        entry(func).setTerminator(new ReturnInsn(null));

        var ex = assertThrows(InvalidInsnException.class, () -> generateBody(func));
        assertTrue(ex.getMessage().contains("compiler-only type leaked into await result"), ex.getMessage());
    }

    @Test
    @DisplayName("AWAIT on Variant with a non-Variant result should fail fast")
    void awaitDynamicTypedResultShouldFailFast() {
        var func = newCoroutine("run");
        func.createAndAddVariable("target", GdVariantType.VARIANT);
        func.createAndAddVariable("res", GdIntType.INT);
        entry(func).appendInstruction(new AwaitInsn("res", "target"));
        entry(func).setTerminator(new ReturnInsn(null));

        var ex = assertThrows(InvalidInsnException.class, () -> generateBody(func));
        assertTrue(ex.getMessage().contains("always publishes a Variant result"), ex.getMessage());
    }

    @Test
    @DisplayName("AWAIT dynamic result aliasing the operand should fail fast")
    void awaitDynamicAliasedResultShouldFailFast() {
        var func = newCoroutine("run");
        func.createAndAddVariable("target", GdVariantType.VARIANT);
        entry(func).appendInstruction(new AwaitInsn("target", "target"));
        entry(func).setTerminator(new ReturnInsn(null));

        var ex = assertThrows(InvalidInsnException.class, () -> generateBody(func));
        assertTrue(ex.getMessage().contains("must not alias its operand"), ex.getMessage());
    }

    @Test
    @DisplayName("AWAIT on a reference coroutine-state operand should fail fast")
    void awaitStateRefOperandShouldFailFast() {
        var func = newCoroutine("run");
        func.createAndAddRefVariable("state", GdccCoroStateType.CORO_STATE);
        func.createAndAddVariable("res", GdIntType.INT);
        entry(func).appendInstruction(new AwaitInsn("res", "state"));
        entry(func).setTerminator(new ReturnInsn(null));

        var ex = assertThrows(InvalidInsnException.class, () -> generateBody(func));
        assertTrue(ex.getMessage().contains("must not be a reference"), ex.getMessage());
    }

    @Test
    @DisplayName("AWAIT on a reference Variant operand should fail fast (helper resets the operand slot)")
    void awaitDynamicRefOperandShouldFailFast() {
        var func = newCoroutine("run");
        func.createAndAddRefVariable("target", GdVariantType.VARIANT);
        func.createAndAddVariable("res", GdVariantType.VARIANT);
        entry(func).appendInstruction(new AwaitInsn("res", "target"));
        entry(func).setTerminator(new ReturnInsn(null));

        var ex = assertThrows(InvalidInsnException.class, () -> generateBody(func));
        assertTrue(ex.getMessage().contains("must not be a reference"), ex.getMessage());
    }

    @Test
    @DisplayName("AWAIT into a reference result variable should fail fast (resume channel needs owning storage)")
    void awaitRefResultShouldFailFast() {
        var func = newCoroutine("run");
        func.createAndAddVariable("state", GdccCoroStateType.CORO_STATE);
        func.createAndAddRefVariable("res", GdIntType.INT);
        entry(func).appendInstruction(new AwaitInsn("res", "state"));
        entry(func).setTerminator(new ReturnInsn(null));

        var ex = assertThrows(InvalidInsnException.class, () -> generateBody(func));
        assertTrue(ex.getMessage().contains("must not be a reference"), ex.getMessage());
    }

    @Test
    @DisplayName("AWAIT with an unknown operand variable id should fail fast")
    void awaitUnknownOperandShouldFailFast() {
        var func = newCoroutine("run");
        func.createAndAddVariable("sig", new GdSignalType());
        func.createAndAddVariable("res", GdVariantType.VARIANT);
        entry(func).appendInstruction(new AwaitInsn("res", "missing_sig"));
        entry(func).setTerminator(new ReturnInsn(null));

        var ex = assertThrows(InvalidInsnException.class, () -> generateBody(func));
        assertTrue(ex.getMessage().contains("await operand variable ID 'missing_sig' not found"), ex.getMessage());
    }

    @Test
    @DisplayName("AWAIT with an unknown result variable id should fail fast")
    void awaitUnknownResultShouldFailFast() {
        var func = newCoroutine("run");
        func.createAndAddVariable("sig", new GdSignalType());
        entry(func).appendInstruction(new AwaitInsn("missing_res", "sig"));
        entry(func).setTerminator(new ReturnInsn(null));

        var ex = assertThrows(InvalidInsnException.class, () -> generateBody(func));
        assertTrue(ex.getMessage().contains("await result variable ID 'missing_res' not found"), ex.getMessage());
    }

    @Test
    @DisplayName("AWAIT in a coroutine body must not emit synchronous-function-only shapes")
    void awaitShouldNotLeakSyncOnlyShapes() {
        var func = newCoroutine("run");
        func.createAndAddVariable("sig", new GdSignalType());
        func.createAndAddVariable("res", GdVariantType.VARIANT);
        entry(func).appendInstruction(new AwaitInsn("res", "sig"));
        entry(func).setTerminator(new ReturnInsn(null));

        var body = generateBody(func);
        // The whole result hand-off goes through gdcc_coro_* helpers; the engine Signal API is
        // only touched inside the runtime TU (connect flags live there, not in generated code).
        assertFalse(body.contains("godot_Signal_connect("), body);
        assertFalse(body.contains("mco_yield("), body);
    }

    private static LirFunctionDef newCoroutine(String name) {
        var func = newFunction(name);
        func.setCoroutine(true);
        return func;
    }

    private static LirFunctionDef newSyncFunction(String name) {
        return newFunction(name);
    }

    private static LirFunctionDef newFunction(String name) {
        var func = new LirFunctionDef(name);
        func.setReturnType(GdVoidType.VOID);
        func.addParameter(new LirParameterDef("self", new GdObjectType("Worker"), null, func));
        func.addBasicBlock(new LirBasicBlock("entry"));
        func.setEntryBlockId("entry");
        return func;
    }

    private static LirBasicBlock entry(LirFunctionDef func) {
        return func.getBasicBlock("entry");
    }

    private static String generateBody(LirFunctionDef func) {
        var workerClass = new LirClassDef("Worker", "RefCounted");
        workerClass.addFunction(func);
        var module = new LirModule("await_insn_gen_module", List.of(workerClass));
        var classRegistry = new ClassRegistry(new ExtensionAPI(
                null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()
        ));
        classRegistry.addGdccClass(workerClass);
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var codegen = new CCodegen();
        codegen.prepare(new CodegenContext(projectInfo, classRegistry), module);
        return codegen.generateFuncBody(workerClass, func);
    }

    private static void assertContainsAll(String text, String... fragments) {
        for (var fragment : fragments) {
            assertTrue(text.contains(fragment), () -> "Missing fragment: " + fragment + "\n" + text);
        }
    }

    /// Strictly sequential occurrence matching: each fragment is searched after the previous
    /// match, so repeated fragments are matched as distinct occurrences in order.
    private static void assertOrdered(String text, String... fragmentsInOrder) {
        var searchFromIndex = 0;
        for (var fragment : fragmentsInOrder) {
            var index = text.indexOf(fragment, searchFromIndex);
            assertTrue(index >= 0, () -> "Missing fragment: " + fragment + "\n" + text);
            searchFromIndex = index + fragment.length();
        }
    }
}
