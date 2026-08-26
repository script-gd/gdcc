package gd.script.gdcc.backend.c.gen;

import gd.script.gdcc.backend.CodegenContext;
import gd.script.gdcc.backend.GeneratedFile;
import gd.script.gdcc.backend.ProjectInfo;
import gd.script.gdcc.backend.c.gen.binding.usage.GodotBindingUsageBuffer;
import gd.script.gdcc.enums.GodotVersion;
import gd.script.gdcc.exception.InvalidControlFlowGraphException;
import gd.script.gdcc.gdextension.ExtensionAPI;
import gd.script.gdcc.gdextension.ExtensionBuiltinClass;
import gd.script.gdcc.lir.LirBasicBlock;
import gd.script.gdcc.lir.LirCaptureDef;
import gd.script.gdcc.lir.LirClassDef;
import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.lir.LirInstruction;
import gd.script.gdcc.lir.LirModule;
import gd.script.gdcc.lir.LirParameterDef;
import gd.script.gdcc.lir.insn.ConstructLambdaInsn;
import gd.script.gdcc.lir.insn.ReturnInsn;
import gd.script.gdcc.lir.insn.VariantSetInsn;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdCallableType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdStringType;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.type.GdVariantType;
import gd.script.gdcc.type.GdVoidType;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Coroutine state-class codegen anchors (`frontend_await_implementation.md` §5): handwritten
/// LIR with `is_coroutine="true"` generates the hidden state class (registration, identity binding,
/// `completed` signal, desc callbacks), the minicoro body function (frame-mapped parameters, no
/// parameter C slots, `_return_val` consumed into the typed return slot), the coroutine-start
/// thunk (OOM path included) and the engine entry three-branch dispatch - while synchronous
/// functions keep their byte-level codegen shape.
class CCoroutineStateClassCodegenTest {

    @Test
    void coroutineModuleShouldGenerateStateClassBodyThunkAndEngineEntries() {
        var workerClass = newWorkerModule();
        var files = generate(workerClass);
        var cCode = generatedFileText(files, "entry.c");
        var hCode = generatedFileText(files, "entry.h");

        // ---- entry.h: conditional runtime include + wrapper struct layout ----
        assertTrue(hCode.contains("#include <gdcc_coroutine.h>"), hCode);
        assertContainsAll(
                hCode,
                "GDExtensionObjectPtr _object;",
                "gdcc_coro_state_header _coro_header;",
                "_coro_param_self;",
                "godot_int _coro_param_count;",
                "godot_String _coro_param_label;",
                "godot_int _coro_ret;",
                "godot_bool _coro_ret_initialized;",
                "void Worker_sum_to__coro_body(mco_coro",
                "Worker_sum_to__coro_start("
        );

        // ---- ClassDB registration: hidden runtime-only RefCounted subclass ----
        assertContainsAll(
                cCode,
                "creation_info.is_runtime = true;",
                "creation_info.is_exposed = false;",
                "GD_STATIC_SN(u8\"_gdcc_coro_state_Worker__coro__sum_to\")",
                "GD_STATIC_SN(u8\"RefCounted\")",
                "godot_classdb_register_extension_class5("
        );
        // `completed(result)` signal with the engine-internal NIL_IS_VARIANT usage.
        assertContainsAll(
                cCode,
                "GD_STATIC_SN(u8\"completed\")",
                "godot_PROPERTY_USAGE_NIL_IS_VARIANT",
                "godot_classdb_register_extension_class_signal("
        );

        // ---- create_instance2: one slot-zero binding under the module-private coroutine token ----
        assertContainsAll(
                cCode,
                "godot_object_set_instance_binding(obj, gdcc_coro_binding_token()",
                "&self->_coro_header, &_gdcc_coro_state_Worker__coro__sum_to_class_binding_callbacks)"
        );
        var createBody = resolveFunctionBodyByPrefix(cCode,
                "GDExtensionObjectPtr _gdcc_coro_state_Worker__coro__sum_to_class_create_instance(");
        assertEquals(1, countOccurrences(createBody, "godot_object_set_instance_binding("), createBody);
        assertFalse(createBody.contains("godot_object_get_instance_binding("), createBody);
        assertFalse(hCode.contains("class_coro_binding_create"), hCode);

        // ---- notification: POSTINITIALIZE header init, PREDELETE cancel-resume only ----
        var notificationBody = resolveFunctionBodyByPrefix(cCode, "void _gdcc_coro_state_Worker__coro__sum_to_class_notification(");
        assertContainsAll(
                notificationBody,
                "gdcc_coro_state_header_init(&self->_coro_header, &_gdcc_coro_state_Worker__coro__sum_to_desc, self->_object);",
                "self->_coro_ret_initialized = false;",
                "godot_Object_NOTIFICATION_PREDELETE()",
                "gdcc_coro_cancel(&self->_coro_header);"
        );
        assertFalse(notificationBody.contains("_coro_param_label ="), notificationBody);

        // ---- free_instance: parameter fields freed, ret slot destroyed once, generic teardown ----
        var freeBody = resolveFunctionBodyByPrefix(cCode, "void _gdcc_coro_state_Worker__coro__sum_to_class_free_instance(");
        assertContainsAll(
                freeBody,
                "godot_String_destroy(&(self->_coro_param_label));",
                "_gdcc_coro_state_Worker__coro__sum_to_destroy_ret_slot(&self->_coro_header);",
                "gdcc_coro_state_free(&self->_coro_header);",
                "godot_mem_free(self);"
        );

        // ---- desc callbacks: pack copies and preserves the slot; typed copy_ret_slot ----
        var packBody = resolveFunctionBodyByPrefix(cCode, "void _gdcc_coro_state_Worker__coro__sum_to_pack_result(");
        assertContainsAll(
                packBody,
                "godot_variant_destroy(&coro_header->result_cache);",
                "coro_header->result_cache = coro_packed;"
        );
        assertFalse(packBody.contains("_coro_ret_initialized = false"), packBody);
        var copyRetBody = resolveFunctionBodyByPrefix(cCode, "void _gdcc_coro_state_Worker__coro__sum_to_copy_ret_slot(");
        assertTrue(copyRetBody.contains("out_typed = self->_coro_ret;"), copyRetBody);
        assertContainsAll(
                cCode,
                ".pack_result = _gdcc_coro_state_Worker__coro__sum_to_pack_result",
                ".copy_ret_slot = _gdcc_coro_state_Worker__coro__sum_to_copy_ret_slot",
                ".destroy_ret_slot = _gdcc_coro_state_Worker__coro__sum_to_destroy_ret_slot",
                ".emit_completed = _gdcc_coro_state_Worker__coro__sum_to_emit_completed"
        );

        // ---- body function: signature, frame prologue, parameter mapping, slot consumption ----
        var body = resolveFunctionBodyByPrefix(cCode, "void Worker_sum_to__coro_body(");
        assertContainsAll(
                body,
                "mco_get_user_data(_co)",
                "_return_val = _coro_state->_coro_param_count;",
                "_coro_state->_coro_ret = _return_val;",
                "_coro_state->_coro_ret_initialized = true;",
                "goto __prepare__;",
                "goto __finally__;"
        );
        assertEquals(1, countOccurrences(body, "__prepare__:"), body);
        assertEquals(1, countOccurrences(body, "__finally__:"), body);
        assertEquals(1, countOccurrences(body, "return;"), body);
        assertFalse(body.contains("return _return_val;"), body);
        // No parameter C slots and no parameter copies: parameters live only in frame fields.
        assertFalse(body.contains("$count"), body);
        assertFalse(body.contains("$label"), body);
        assertFalse(body.contains("$self"), body);

        // ---- start thunk: create -> fill frame -> mco_create -> resume -> DEAD finalize ----
        var thunkBody = resolveFunctionBodyByPrefix(cCode, "godot_Object* Worker_sum_to__coro_start(");
        assertContainsAll(
                thunkBody,
                "_gdcc_coro_state_Worker__coro__sum_to_class_create_instance(NULL, false)",
                "if (coro_state_obj == NULL)",
                "gdcc_ref_counted_init_raw(coro_state_obj, true)",
                "gdcc_coro_state_header *coro_header = gdcc_coro_state_identify(coro_state_obj)",
                "if (coro_header == NULL)",
                "release_object(coro_state_obj);",
                "offsetof(_gdcc_coro_state_Worker__coro__sum_to, _coro_header)",
                "coro_state->_coro_param_count = $count;",
                "coro_state->_coro_param_label = godot_new_String_with_String($label);",
                "coro_state->_coro_param_self = $self;",
                "own_object(",
                "mco_desc_init(Worker_sum_to__coro_body, GDCC_CORO_STACK_SIZE)",
                "mco_create(&coro_state->_coro_header.co, &coro_desc) != MCO_SUCCESS",
                "GDCC_PRINT_RUNTIME_ERROR(",
                "coro_state->_coro_ret = 0;",
                "coro_state->_coro_ret_initialized = true;",
                "_gdcc_coro_state_Worker__coro__sum_to_pack_result(&coro_state->_coro_header);",
                "coro_state->_coro_header.done = true;",
                "mco_resume(coro_state->_coro_header.co);",
                "mco_status(coro_state->_coro_header.co) == MCO_DEAD",
                "gdcc_coro_finalize(&coro_state->_coro_header);",
                "return (godot_Object*)coro_state_obj;"
        );
        // The borrowed self is stored first and only then retained (first-write + retain).
        var selfFillIndex = thunkBody.indexOf("coro_state->_coro_param_self = $self;");
        assertTrue(selfFillIndex >= 0, thunkBody);
        assertTrue(thunkBody.indexOf("own_object(", selfFillIndex) > selfFillIndex, thunkBody);

        // ---- engine entry (typed non-Variant): done fast path + detach/default/error branch ----
        var engineEntryBody = resolveFunctionBodyByPrefix(cCode, "godot_int Worker_sum_to(");
        assertContainsAll(
                engineEntryBody,
                "Worker_sum_to__coro_start(",
                "if (coro_state_obj == NULL)",
                "gdcc_coro_state_identify(coro_state_obj)",
                "if (coro_header == NULL)",
                "coroutine start returned an invalid state object",
                "if (coro_header->done)",
                "_gdcc_coro_state_Worker__coro__sum_to__move_result(coro_header)",
                "release_object(coro_state_obj);",
                "return r;",
                "suspended at the engine boundary",
                "return 0;"
        );
        // Same ordering contract as the Variant branch: move the typed result out first.
        var typedMoveIndex = engineEntryBody.indexOf(
                "_gdcc_coro_state_Worker__coro__sum_to__move_result(coro_header)");
        assertTrue(typedMoveIndex >= 0, engineEntryBody);
        assertTrue(engineEntryBody.indexOf("release_object(coro_state_obj);", typedMoveIndex) > typedMoveIndex,
                engineEntryBody);

        // ---- engine entry (Variant): suspended hands the state object out as a Variant ----
        var variantEngineBody = resolveFunctionBodyByPrefix(cCode, "godot_Variant Worker_fetch(");
        assertContainsAll(
                variantEngineBody,
                "if (coro_header->done)",
                "godot_new_Variant_with_Object(coro_state_obj)",
                "release_object(coro_state_obj);",
                "return r;"
        );
        // The done fast path moves the typed result out before releasing the state reference.
        var variantMoveIndex = variantEngineBody.indexOf(
                "_gdcc_coro_state_Worker__coro__fetch__move_result(coro_header)");
        assertTrue(variantMoveIndex >= 0, variantEngineBody);
        assertTrue(variantEngineBody.indexOf("release_object(coro_state_obj);", variantMoveIndex) > variantMoveIndex,
                variantEngineBody);

        // ---- engine entry (String): value-semantic copy channel destroys the old value first ----
        var stringCopyRetBody = resolveFunctionBodyByPrefix(cCode, "void _gdcc_coro_state_Worker__coro__display_name_copy_ret_slot(");
        assertTrue(
                stringCopyRetBody.indexOf("godot_String_destroy((godot_String *)out_typed);")
                        < stringCopyRetBody.indexOf("godot_new_String_with_String(&(self->_coro_ret));"),
                stringCopyRetBody
        );

        // ---- engine entry (object): alias-safe slot-write order in copy_ret_slot ----
        var objectCopyRetBody = resolveFunctionBodyByPrefix(cCode, "void _gdcc_coro_state_Worker__coro__spawn_peer_copy_ret_slot(");
        assertContainsAll(
                objectCopyRetBody,
                "coro_out_old = ",
                " = self->_coro_ret;"
        );
        var assignIndex = objectCopyRetBody.indexOf(" = self->_coro_ret;");
        assertTrue(assignIndex > objectCopyRetBody.indexOf("coro_out_old = "), objectCopyRetBody);
        // Alias-safe discipline: retain the new value strictly before releasing the old one.
        var ownIndex = objectCopyRetBody.indexOf("own_object(", assignIndex);
        var releaseIndex = objectCopyRetBody.indexOf("release_object(", assignIndex);
        assertTrue(ownIndex > assignIndex, objectCopyRetBody);
        assertTrue(releaseIndex > ownIndex, objectCopyRetBody);

        // ---- engine entry (void): always detach, no identify / no move_result ----
        var voidEngineBody = resolveFunctionBodyByPrefix(cCode, "void Worker_wait_done(");
        assertTrue(voidEngineBody.contains("Worker_wait_done__coro_start("), voidEngineBody);
        assertTrue(voidEngineBody.contains("release_object(coro_state_obj);"), voidEngineBody);
        assertFalse(voidEngineBody.contains("gdcc_coro_state_identify"), voidEngineBody);
        assertFalse(voidEngineBody.contains("move_result"), voidEngineBody);

        // ---- void coroutine desc specializations: nil copy channel, no ret slot ----
        var voidCopyRetBody = resolveFunctionBodyByPrefix(cCode, "void _gdcc_coro_state_Worker__coro__wait_done_copy_ret_slot(");
        assertTrue(voidCopyRetBody.contains("*(godot_Variant*)out_typed = godot_new_Variant_nil();"), voidCopyRetBody);
        var voidDestroyRetBody = resolveFunctionBodyByPrefix(cCode, "void _gdcc_coro_state_Worker__coro__wait_done_destroy_ret_slot(");
        assertFalse(voidDestroyRetBody.contains("_coro_ret"), voidDestroyRetBody);

        // ---- synchronous functions in the same module keep their plain codegen shape ----
        var syncBody = resolveFunctionBodyByPrefix(cCode, "godot_int Worker_sync_work(");
        assertTrue(syncBody.contains("return _return_val;"), syncBody);
        assertTrue(syncBody.contains("_return_val = $x;"), syncBody);
        assertFalse(syncBody.contains("_coro_state"), syncBody);
        assertFalse(syncBody.contains("mco_"), syncBody);
    }

    @Test
    void syncOnlyModuleShouldNotSeeAnyCoroutineSurface() {
        var workerClass = new LirClassDef("PlainWorker", "RefCounted");
        var func = newFunction("plain", GdIntType.INT);
        func.addParameter(new LirParameterDef("self", new GdObjectType("PlainWorker"), null, func));
        func.addParameter(new LirParameterDef("x", GdIntType.INT, null, func));
        entry(func).setTerminator(new ReturnInsn("x"));
        workerClass.addFunction(func);

        var files = generate(workerClass);
        var cCode = generatedFileText(files, "entry.c");
        var hCode = generatedFileText(files, "entry.h");
        assertFalse(hCode.contains("gdcc_coroutine.h"), hCode);
        assertFalse(cCode.contains("_gdcc_coro_state_"), cCode);
        assertFalse(hCode.contains("_gdcc_coro_state_"), hCode);
        assertFalse(cCode.contains("mco_"), cCode);
    }

    @Test
    void coroutineLambdaShouldGenerateStateClassSurface() {
        // The `isCoroutine && isLambda` combination is positive codegen:
        // a capturing coroutine lambda gets its hidden state class with typed capture frame
        // fields, a start thunk carrying the `_capture` tail parameter, and no plain
        // function/engine-entry surface (the Callable ABI enters through the thunk).
        var workerClass = new LirClassDef("Worker", "RefCounted");
        var lambda = newFunction("_lambda_0", GdIntType.INT);
        lambda.setLambda(true);
        lambda.setHidden(true);
        lambda.setStatic(true);
        lambda.setCoroutine(true);
        lambda.addCapture(new LirCaptureDef("seed", GdIntType.INT, lambda));
        entry(lambda).setTerminator(new ReturnInsn("seed"));
        workerClass.addFunction(lambda);

        var files = generate(workerClass);
        var cCode = generatedFileText(files, "entry.c");
        var hCode = generatedFileText(files, "entry.h");

        assertContainsAll(
                hCode,
                "typedef struct Worker_Capture__lambda_0",
                "godot_int _coro_capture_seed;",
                "godot_Object* Worker__lambda_0__coro_start(",
                "Worker_Capture__lambda_0* _capture"
        );
        // The state class is registered like any other coroutine's, lambda or not.
        assertTrue(cCode.contains("GD_STATIC_SN(u8\"_gdcc_coro_state_Worker__coro___lambda_0\")"), cCode);
        // No plain function declaration or engine entry for the coroutine lambda.
        assertFalse(hCode.contains("godot_int Worker__lambda_0("), hCode);
        assertFalse(cCode.contains("godot_int Worker__lambda_0("), cCode);
    }

    @Test
    void lambdaCoroutineFrameAndCallFuncShouldManageCaptureLifecycle() {
        // Full capture-lifecycle anchors for a coroutine lambda: typed capture
        // frame fields in capture-plan order (`self` first), thunk-side per-call copies with
        // per-type discipline (primitive assign / value copy-construct from the field address /
        // object assign + retain), exactly-once destroys in free_instance after the parameter
        // sweep, frame-field body mapping without `_capture` or `$`-slot leakage, and the
        // call_func done/suspend dispatch.
        var workerClass = new LirClassDef("Worker", "RefCounted");
        var lambda = newFunction("_lambda_0", GdIntType.INT);
        lambda.setLambda(true);
        lambda.setHidden(true);
        lambda.setStatic(true);
        lambda.setCoroutine(true);
        lambda.addCapture(new LirCaptureDef("self", new GdObjectType("Worker"), lambda));
        lambda.addCapture(new LirCaptureDef("seed", GdIntType.INT, lambda));
        lambda.addCapture(new LirCaptureDef("label", GdStringType.STRING, lambda));
        entry(lambda).setTerminator(new ReturnInsn("seed"));
        workerClass.addFunction(lambda);

        var files = generate(workerClass);
        var cCode = generatedFileText(files, "entry.c");
        var hCode = generatedFileText(files, "entry.h");

        // ---- State struct: capture frame fields in capture order, ahead of the return slot ----
        var structBody = resolveFunctionBodyByPrefix(hCode, "struct _gdcc_coro_state_Worker__coro___lambda_0 {");
        var selfIndex = structBody.indexOf("_coro_capture_self;");
        var seedIndex = structBody.indexOf("_coro_capture_seed;");
        var labelIndex = structBody.indexOf("_coro_capture_label;");
        var retIndex = structBody.indexOf("_coro_ret;");
        assertAll(
                () -> assertTrue(selfIndex >= 0, structBody),
                () -> assertTrue(selfIndex < seedIndex && seedIndex < labelIndex, structBody),
                () -> assertTrue(labelIndex < retIndex, structBody)
        );

        // ---- Forward declarations precede call_func (C declaration-before-use) ----
        var thunkDeclIndex = hCode.indexOf("godot_Object* Worker__lambda_0__coro_start(");
        var moveResultDeclIndex = hCode.indexOf("_gdcc_coro_state_Worker__coro___lambda_0__move_result(gdcc_coro_state_header *coro_header);");
        var callFuncIndex = hCode.indexOf("static void Worker__lambda_0_call(");
        assertAll(
                () -> assertTrue(thunkDeclIndex >= 0 && thunkDeclIndex < callFuncIndex, hCode),
                () -> assertTrue(moveResultDeclIndex >= 0 && moveResultDeclIndex < callFuncIndex, hCode)
        );

        // ---- Start thunk: capture block tail parameter + per-call frame fill before mco_create ----
        var thunkBody = resolveFunctionBodyByPrefix(cCode, "godot_Object* Worker__lambda_0__coro_start(");
        assertContainsAll(
                thunkBody,
                "Worker_Capture__lambda_0* _capture",
                "coro_state->_coro_capture_self = _capture->self;",
                "own_object(",
                "coro_state->_coro_capture_seed = _capture->seed;",
                "coro_state->_coro_capture_label = godot_new_String_with_String(&(_capture->label));"
        );
        // Object fill assigns first and retains the fresh frame field copy afterwards.
        var selfFillIndex = thunkBody.indexOf("coro_state->_coro_capture_self = _capture->self;");
        assertTrue(thunkBody.indexOf("own_object(", selfFillIndex) > selfFillIndex, thunkBody);
        // All capture fills happen before the minicoro is created (OOM path sweeps them via
        // free_instance, same discipline as parameter fields).
        var createIndex = thunkBody.indexOf("mco_create(");
        assertAll(
                () -> assertTrue(selfFillIndex >= 0 && selfFillIndex < createIndex, thunkBody),
                () -> assertTrue(thunkBody.indexOf("coro_state->_coro_capture_label =") < createIndex, thunkBody)
        );

        // ---- free_instance: capture destroys after the parameter sweep, before the ret slot ----
        var freeBody = resolveFunctionBodyByPrefix(cCode, "void _gdcc_coro_state_Worker__coro___lambda_0_class_free_instance(");
        assertContainsAll(
                freeBody,
                "release_object(",
                "_live_object(self->_coro_capture_self)",
                "godot_String_destroy(&(self->_coro_capture_label));"
        );
        var captureFreeIndex = freeBody.indexOf("godot_String_destroy(&(self->_coro_capture_label));");
        var retDestroyIndex = freeBody.indexOf("_destroy_ret_slot(&self->_coro_header);");
        assertAll(
                () -> assertTrue(captureFreeIndex >= 0, freeBody),
                () -> assertTrue(captureFreeIndex < retDestroyIndex, freeBody)
        );

        // ---- Body: captures map to frame fields; no `_capture` block, no `$`-slot copies ----
        var body = resolveFunctionBodyByPrefix(cCode, "void Worker__lambda_0__coro_body(");
        assertTrue(body.contains("_return_val = _coro_state->_coro_capture_seed;"), body);
        assertFalse(body.contains("$seed"), body);
        assertFalse(body.contains("_capture->"), body);

        // ---- call_func: start thunk dispatch with captures as the tail argument ----
        var callFuncBody = resolveFunctionBodyByPrefix(hCode, "static void Worker__lambda_0_call(");
        assertContainsAll(
                callFuncBody,
                "godot_Object* coro_state_obj = Worker__lambda_0__coro_start(captures);",
                "if (coro_state_obj == NULL)",
                "gdcc_coro_state_header* coro_header = gdcc_coro_state_identify(coro_state_obj);",
                "coro_header->done",
                "godot_int r = _gdcc_coro_state_Worker__coro___lambda_0__move_result(coro_header);",
                "godot_new_Variant_with_int(r)",
                "godot_new_Variant_with_Object(coro_state_obj)",
                "godot_variant_new_copy(r_return, &coro_state_variant);",
                "release_object(coro_state_obj);"
        );
        // The plain synchronous impl call is gone from the coroutine dispatch.
        assertFalse(callFuncBody.contains("Worker__lambda_0(captures)"), callFuncBody);
    }

    @Test
    void shellCoroutineFunctionShouldReuseExistingFailFast() {
        // No entry block / no basic blocks: the backend must not silently synthesize a body
        // for coroutine functions; the ordinary control-flow validation fires.
        var workerClass = new LirClassDef("Worker", "RefCounted");
        var shell = new LirFunctionDef("shell_coro");
        shell.setReturnType(GdVoidType.VOID);
        shell.setCoroutine(true);
        workerClass.addFunction(shell);

        assertThrows(InvalidControlFlowGraphException.class, () -> generate(workerClass));
    }

    @Test
    void coroutineBodyBuilderShouldMapParametersToFrameFields() {
        // Focused CBodyBuilder-level anchor of the frame mapping contract: reads, writes,
        // address-of and argument passing all treat parameters as non-ref owning storage.
        var workerClass = new LirClassDef("Worker", "RefCounted");
        var func = newFunction("map_params", GdIntType.INT);
        func.setCoroutine(true);
        func.addParameter(new LirParameterDef("self", new GdObjectType("Worker"), null, func));
        func.addParameter(new LirParameterDef("label", GdStringType.STRING, null, func));

        var module = new LirModule("coroutine_state_class_module", List.of(workerClass));
        var classRegistry = new ClassRegistry(new ExtensionAPI(
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        ));
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var helper = new CGenHelper(new CodegenContext(projectInfo, classRegistry), module.getClassDefs());
        var context = new CCoroutineFrameContext("_gdcc_coro_state_Worker__coro__map_params");
        var builder = new CBodyBuilder(helper, workerClass, func, GodotBindingUsageBuffer.noOp(), context);

        var labelVariable = func.getVariableById("label");
        assertEquals(
                "_coro_state->_coro_param_label",
                builder.valueOfVar(labelVariable).generateCode()
        );
        // Parameters are writable owning storage in a coroutine body (no ref rejection).
        var target = builder.targetOfVar(labelVariable);
        assertEquals("_coro_state->_coro_param_label", target.generateCode());
        assertFalse(target.isRef());
        // Value-semantic parameters pass by address of the frame field, not as borrowed pointers.
        assertEquals(
                "&_coro_state->_coro_param_label",
                builder.renderArgument(builder.valueOfVar(labelVariable), false).code()
        );
    }

    @Test
    void coroutineFrameParametersShouldDriveWritebackAndLambdaCaptures() {
        var workerClass = new LirClassDef("Worker", "RefCounted");

        // variant_set on a String frame parameter: the borrowed-ref rejection must not fire
        // (frame fields are writable owning storage); pack/writeback address the frame field.
        var touchLabel = newCoroutine("touch_label", GdVoidType.VOID);
        touchLabel.addParameter(new LirParameterDef("self", new GdObjectType("Worker"), null, touchLabel));
        touchLabel.addParameter(new LirParameterDef("label", GdStringType.STRING, null, touchLabel));
        touchLabel.createAndAddVariable("k", GdVariantType.VARIANT);
        touchLabel.createAndAddVariable("v", GdVariantType.VARIANT);
        entry(touchLabel).appendInstruction(new VariantSetInsn("label", "k", "v"));
        entry(touchLabel).setTerminator(new ReturnInsn(null));
        workerClass.addFunction(touchLabel);

        // Method-lambda contract: captures start with self; every capture source and the
        // cached object_id must read frame fields (no `$param` C slots exist in the body).
        var lambda = new LirFunctionDef("_lambda_0", "entry");
        lambda.setLambda(true);
        lambda.setHidden(true);
        lambda.setStatic(true);
        lambda.setReturnType(GdVoidType.VOID);
        lambda.addCapture(new LirCaptureDef("self", new GdObjectType("Worker"), lambda));
        lambda.addCapture(new LirCaptureDef("label", GdStringType.STRING, lambda));
        var lambdaEntry = new LirBasicBlock("entry");
        lambdaEntry.appendInstruction(new ReturnInsn(null));
        lambda.addBasicBlock(lambdaEntry);
        workerClass.addFunction(lambda);

        var schedule = newCoroutine("schedule", GdVoidType.VOID);
        schedule.addParameter(new LirParameterDef("self", new GdObjectType("Worker"), null, schedule));
        schedule.addParameter(new LirParameterDef("label", GdStringType.STRING, null, schedule));
        schedule.createAndAddVariable("cb", new GdCallableType());
        entry(schedule).appendInstruction(new ConstructLambdaInsn(
                "cb",
                "_lambda_0",
                List.of(new LirInstruction.VariableOperand("self"), new LirInstruction.VariableOperand("label"))
        ));
        entry(schedule).setTerminator(new ReturnInsn(null));
        workerClass.addFunction(schedule);

        var files = generate(workerClass);
        var cCode = generatedFileText(files, "entry.c");

        var touchBody = resolveFunctionBodyByPrefix(cCode, "void Worker_touch_label__coro_body(");
        assertContainsAll(
                touchBody,
                "godot_variant_set(",
                "godot_new_Variant_with_String(",
                "_coro_state->_coro_param_label",
                "godot_new_String_with_Variant("
        );
        // No `$label` C slot operations (the runtime-error message mentioning `$label`
        // is a pre-existing diagnostic string, not a storage reference).
        assertFalse(touchBody.contains("$label ="), touchBody);
        assertFalse(touchBody.contains("&$label"), touchBody);

        var scheduleBody = resolveFunctionBodyByPrefix(cCode, "void Worker_schedule__coro_body(");
        assertContainsAll(
                scheduleBody,
                "->self = _coro_state->_coro_param_self;",
                "_coro_state->_coro_param_self.instance_id",
                "godot_new_String_with_String(&(_coro_state->_coro_param_label))"
        );
        assertFalse(scheduleBody.contains("$self.instance_id"), scheduleBody);
        assertFalse(scheduleBody.contains("->self = $self;"), scheduleBody);
        assertFalse(scheduleBody.contains("(&($label))"), scheduleBody);
    }

    private static @NotNull LirClassDef newWorkerModule() {
        var workerClass = new LirClassDef("Worker", "RefCounted");

        var sumTo = newCoroutine("sum_to", GdIntType.INT);
        sumTo.addParameter(new LirParameterDef("self", new GdObjectType("Worker"), null, sumTo));
        sumTo.addParameter(new LirParameterDef("count", GdIntType.INT, null, sumTo));
        sumTo.addParameter(new LirParameterDef("label", GdStringType.STRING, null, sumTo));
        entry(sumTo).setTerminator(new ReturnInsn("count"));
        workerClass.addFunction(sumTo);

        var waitDone = newCoroutine("wait_done", GdVoidType.VOID);
        waitDone.addParameter(new LirParameterDef("self", new GdObjectType("Worker"), null, waitDone));
        entry(waitDone).setTerminator(new ReturnInsn(null));
        workerClass.addFunction(waitDone);

        var fetch = newCoroutine("fetch", GdVariantType.VARIANT);
        fetch.addParameter(new LirParameterDef("self", new GdObjectType("Worker"), null, fetch));
        fetch.createAndAddVariable("v", GdVariantType.VARIANT);
        entry(fetch).setTerminator(new ReturnInsn("v"));
        workerClass.addFunction(fetch);

        var displayName = newCoroutine("display_name", GdStringType.STRING);
        displayName.addParameter(new LirParameterDef("self", new GdObjectType("Worker"), null, displayName));
        displayName.addParameter(new LirParameterDef("label", GdStringType.STRING, null, displayName));
        entry(displayName).setTerminator(new ReturnInsn("label"));
        workerClass.addFunction(displayName);

        var spawnPeer = newCoroutine("spawn_peer", new GdObjectType("Worker"));
        spawnPeer.addParameter(new LirParameterDef("self", new GdObjectType("Worker"), null, spawnPeer));
        spawnPeer.addParameter(new LirParameterDef("peer", new GdObjectType("Worker"), null, spawnPeer));
        entry(spawnPeer).setTerminator(new ReturnInsn("peer"));
        workerClass.addFunction(spawnPeer);

        var syncWork = newFunction("sync_work", GdIntType.INT);
        syncWork.addParameter(new LirParameterDef("self", new GdObjectType("Worker"), null, syncWork));
        syncWork.addParameter(new LirParameterDef("x", GdIntType.INT, null, syncWork));
        entry(syncWork).setTerminator(new ReturnInsn("x"));
        workerClass.addFunction(syncWork);

        return workerClass;
    }

    private static @NotNull LirFunctionDef newCoroutine(String name, GdType returnType) {
        var func = newFunction(name, returnType);
        func.setCoroutine(true);
        return func;
    }

    private static @NotNull LirFunctionDef newFunction(String name, GdType returnType) {
        var func = new LirFunctionDef(name);
        func.setReturnType(returnType);
        func.addBasicBlock(new LirBasicBlock("entry"));
        func.setEntryBlockId("entry");
        return func;
    }

    private static @NotNull LirBasicBlock entry(LirFunctionDef func) {
        return func.getBasicBlock("entry");
    }

    private static @NotNull CCodegen preparedCodegen(LirClassDef workerClass) {
        var module = new LirModule("coroutine_state_class_module", List.of(workerClass));
        // Callable locals materialize their default via `construct_builtin Callable()` in
        // `__prepare__`, which validates against the builtin-class metadata of the fixture.
        var callableBuiltin = new ExtensionBuiltinClass(
                "Callable",
                false,
                List.of(),
                List.of(),
                List.of(),
                List.of(new ExtensionBuiltinClass.ConstructorInfo("Callable", 0, List.of())),
                List.of(),
                List.of()
        );
        var classRegistry = new ClassRegistry(new ExtensionAPI(
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(callableBuiltin),
                List.of(),
                List.of(),
                List.of()
        ));
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var ctx = new CodegenContext(projectInfo, classRegistry);
        var codegen = new CCodegen();
        codegen.prepare(ctx, module);
        return codegen;
    }

    private static @NotNull List<GeneratedFile> generate(LirClassDef workerClass) {
        return preparedCodegen(workerClass).generate();
    }

    private static @NotNull String generatedFileText(List<GeneratedFile> files, String filePath) {
        return files.stream()
                .filter(file -> file.filePath().equals(filePath))
                .findFirst()
                .map(file -> new String(file.contentWriter(), java.nio.charset.StandardCharsets.UTF_8))
                .orElseThrow(() -> new AssertionError("Generated file not found: " + filePath));
    }

    private static @NotNull String resolveFunctionBodyByPrefix(String source, String functionPrefix) {
        var start = source.indexOf(functionPrefix);
        assertTrue(start >= 0, () -> "Function not found: " + functionPrefix + "\n" + source);
        var braceStart = source.indexOf('{', start);
        assertTrue(braceStart >= 0, () -> "Function body not found: " + functionPrefix);
        var depth = 0;
        for (var index = braceStart; index < source.length(); index++) {
            var current = source.charAt(index);
            if (current == '{') {
                depth++;
            } else if (current == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(start, index + 1);
                }
            }
        }
        throw new AssertionError("Unbalanced braces after: " + functionPrefix);
    }

    private static int countOccurrences(String text, String needle) {
        var count = 0;
        var fromIndex = 0;
        while (true) {
            var index = text.indexOf(needle, fromIndex);
            if (index < 0) {
                return count;
            }
            count++;
            fromIndex = index + needle.length();
        }
    }

    private static void assertContainsAll(String text, String... fragments) {
        for (var fragment : fragments) {
            assertTrue(text.contains(fragment), () -> "Missing fragment: " + fragment + "\n" + text);
        }
    }
}
