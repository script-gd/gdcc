package gd.script.gdcc.backend.c.gen;

import gd.script.gdcc.backend.CodegenContext;
import gd.script.gdcc.backend.ProjectInfo;
import gd.script.gdcc.enums.GodotVersion;
import gd.script.gdcc.enums.LifecycleProvenance;
import gd.script.gdcc.exception.InvalidInsnException;
import gd.script.gdcc.gdextension.ExtensionAPI;
import gd.script.gdcc.gdextension.ExtensionBuiltinClass;
import gd.script.gdcc.gdextension.ExtensionFunctionArgument;
import gd.script.gdcc.gdextension.ExtensionGdClass;
import gd.script.gdcc.lir.LirBasicBlock;
import gd.script.gdcc.lir.LirClassDef;
import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.lir.LirInstruction;
import gd.script.gdcc.lir.LirModule;
import gd.script.gdcc.lir.LirParameterDef;
import gd.script.gdcc.lir.insn.CallStaticMethodInsn;
import gd.script.gdcc.lir.insn.DestructInsn;
import gd.script.gdcc.lir.insn.ReturnInsn;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdColorType;
import gd.script.gdcc.type.GdFloatType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdStringType;
import gd.script.gdcc.type.GdVariantType;
import gd.script.gdcc.type.GdVoidType;
import gd.script.gdcc.type.GdccCoroStateType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// `CALL_STATIC_METHOD` backend contract (`frontend_await_implementation.md`): receiver-free
/// exact dispatch for GDCC/engine/builtin static methods, the static coroutine start-thunk ABI
/// (state slot, no receiver), and fail-fast invariants (no dynamic fallback, no instance default
/// functions, fixed-parameter start thunk).
class CallStaticMethodInsnGenTest {
    @Test
    @DisplayName("CALL_STATIC_METHOD on a GDCC static method should emit a direct C call without receiver")
    void gdccStaticCallShouldEmitDirectCCall() {
        var workerClass = newClass("Worker");
        var build = newFunction("build");
        build.setStatic(true);
        build.setReturnType(GdIntType.INT);
        build.addParameter(new LirParameterDef("count", GdIntType.INT, null, build));
        entry(build).appendInstruction(new ReturnInsn("count"));
        workerClass.addFunction(build);

        var hostClass = newClass("Host");
        var caller = newFunction("run");
        caller.createAndAddVariable("count", GdIntType.INT);
        caller.createAndAddVariable("result", GdIntType.INT);
        entry(caller).appendInstruction(new CallStaticMethodInsn(
                "result",
                "Worker",
                "build",
                List.of(new LirInstruction.VariableOperand("count"))
        ));
        hostClass.addFunction(caller);

        var body = generateBody(hostClass, caller, newApi(List.of(), List.of()), List.of(hostClass, workerClass));
        assertTrue(body.contains("$result = Worker_build($count);"), body);
    }

    @Test
    @DisplayName("CALL_STATIC_METHOD on a void GDCC static method should discard the result")
    void gdccStaticVoidCallShouldDiscardResult() {
        var workerClass = newClass("Worker");
        var reset = newFunction("reset");
        reset.setStatic(true);
        entry(reset).appendInstruction(new ReturnInsn(null));
        workerClass.addFunction(reset);

        var hostClass = newClass("Host");
        var caller = newFunction("run");
        entry(caller).appendInstruction(new CallStaticMethodInsn(null, "Worker", "reset", List.of()));
        hostClass.addFunction(caller);

        var body = generateBody(hostClass, caller, newApi(List.of(), List.of()), List.of(hostClass, workerClass));
        assertTrue(body.contains("Worker_reset();"), body);
    }

    @Test
    @DisplayName("CALL_STATIC_METHOD on a void GDCC static method with a resultId should fail fast")
    void gdccStaticVoidCallWithResultShouldFailFast() {
        var workerClass = newClass("Worker");
        var reset = newFunction("reset");
        reset.setStatic(true);
        entry(reset).appendInstruction(new ReturnInsn(null));
        workerClass.addFunction(reset);

        var hostClass = newClass("Host");
        var caller = newFunction("run");
        caller.createAndAddVariable("result", GdVariantType.VARIANT);
        entry(caller).appendInstruction(new CallStaticMethodInsn("result", "Worker", "reset", List.of()));
        hostClass.addFunction(caller);

        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(hostClass, caller, newApi(List.of(), List.of()), List.of(hostClass, workerClass))
        );
        assertTrue(ex.getMessage().contains("has no return value but resultId is provided"), ex.getMessage());
    }

    @Test
    @DisplayName("CALL_STATIC_METHOD on a GDCC static coroutine should call the start thunk without receiver")
    void gdccStaticCoroutineShouldEmitStartThunkWithoutReceiver() {
        var workerClass = newClass("Worker");
        var sumTo = newFunction("sum_to");
        sumTo.setStatic(true);
        sumTo.setCoroutine(true);
        sumTo.setReturnType(GdIntType.INT);
        sumTo.addParameter(new LirParameterDef("count", GdIntType.INT, null, sumTo));
        entry(sumTo).appendInstruction(new ReturnInsn("count"));
        workerClass.addFunction(sumTo);

        var hostClass = newClass("Host");
        var caller = newFunction("run");
        caller.createAndAddVariable("count", GdIntType.INT);
        caller.createAndAddVariable("state", GdccCoroStateType.CORO_STATE);
        entry(caller).appendInstruction(new CallStaticMethodInsn(
                "state",
                "Worker",
                "sum_to",
                List.of(new LirInstruction.VariableOperand("count"))
        ));
        hostClass.addFunction(caller);

        var body = generateBody(hostClass, caller, newApi(List.of(), List.of()), List.of(hostClass, workerClass));
        // Overwrite discipline: the previously held state reference (or NULL) is released first.
        assertTrue(body.contains("gdcc_coro_state_slot_destroy(&$state);"), body);
        assertTrue(body.contains("$state = Worker_sum_to__coro_start($count);"), body);
        assertFalse(body.contains("Worker_sum_to($"), body);
        assertOrdered(body, "gdcc_coro_state_slot_destroy(&$state);", "$state = Worker_sum_to__coro_start($count);");
    }

    @Test
    @DisplayName("CALL_STATIC_METHOD statement-position static coroutine call should detach via INTERNAL destruct")
    void gdccStaticCoroutineStatementShouldDetachViaInternalDestruct() {
        var workerClass = newClass("Worker");
        var fire = newFunction("fire");
        fire.setStatic(true);
        fire.setCoroutine(true);
        entry(fire).appendInstruction(new ReturnInsn(null));
        workerClass.addFunction(fire);

        var hostClass = newClass("Host");
        var caller = newFunction("run");
        caller.createAndAddVariable("__coro_state_7", GdccCoroStateType.CORO_STATE);
        entry(caller).appendInstruction(new CallStaticMethodInsn(
                "__coro_state_7",
                "Worker",
                "fire",
                List.of()
        ));
        entry(caller).appendInstruction(new DestructInsn("__coro_state_7", LifecycleProvenance.INTERNAL));
        hostClass.addFunction(caller);

        var body = generateBody(hostClass, caller, newApi(List.of(), List.of()), List.of(hostClass, workerClass));
        assertTrue(body.contains("$__coro_state_7 = Worker_fire__coro_start();"), body);
        assertTrue(body.contains("gdcc_coro_state_slot_destroy(&$__coro_state_7);"), body);
        assertOrdered(
                body,
                "$__coro_state_7 = Worker_fire__coro_start();",
                "gdcc_coro_state_slot_destroy(&$__coro_state_7);"
        );
    }

    @Test
    @DisplayName("CALL_STATIC_METHOD on a GDCC static coroutine without a result should fail fast")
    void gdccStaticCoroutineWithoutResultShouldFailFast() {
        var workerClass = newClass("Worker");
        var fire = newFunction("fire");
        fire.setStatic(true);
        fire.setCoroutine(true);
        entry(fire).appendInstruction(new ReturnInsn(null));
        workerClass.addFunction(fire);

        var hostClass = newClass("Host");
        var caller = newFunction("run");
        entry(caller).appendInstruction(new CallStaticMethodInsn(null, "Worker", "fire", List.of()));
        hostClass.addFunction(caller);

        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(hostClass, caller, newApi(List.of(), List.of()), List.of(hostClass, workerClass))
        );
        assertTrue(ex.getMessage().contains("must declare a compiler::GdccCoroState result variable"), ex.getMessage());
    }

    @Test
    @DisplayName("CALL_STATIC_METHOD on a GDCC static coroutine with a non-GdccCoroState result should fail fast")
    void gdccStaticCoroutineWithWrongResultTypeShouldFailFast() {
        var workerClass = newClass("Worker");
        var fire = newFunction("fire");
        fire.setStatic(true);
        fire.setCoroutine(true);
        entry(fire).appendInstruction(new ReturnInsn(null));
        workerClass.addFunction(fire);

        var hostClass = newClass("Host");
        var caller = newFunction("run");
        caller.createAndAddVariable("state", GdVariantType.VARIANT);
        entry(caller).appendInstruction(new CallStaticMethodInsn("state", "Worker", "fire", List.of()));
        hostClass.addFunction(caller);

        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(hostClass, caller, newApi(List.of(), List.of()), List.of(hostClass, workerClass))
        );
        assertTrue(ex.getMessage().contains("must be declared compiler::GdccCoroState"), ex.getMessage());
    }

    @Test
    @DisplayName("CALL_STATIC_METHOD on a vararg GDCC static coroutine should fail fast (fixed-parameter start thunk)")
    void gdccStaticVarargCoroutineShouldFailFast() {
        var workerClass = newClass("Worker");
        var fire = newFunction("fire");
        fire.setStatic(true);
        fire.setCoroutine(true);
        fire.setVararg(true);
        entry(fire).appendInstruction(new ReturnInsn(null));
        workerClass.addFunction(fire);

        var hostClass = newClass("Host");
        var caller = newFunction("run");
        caller.createAndAddVariable("state", GdccCoroStateType.CORO_STATE);
        entry(caller).appendInstruction(new CallStaticMethodInsn("state", "Worker", "fire", List.of()));
        hostClass.addFunction(caller);

        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(hostClass, caller, newApi(List.of(), List.of()), List.of(hostClass, workerClass))
        );
        assertTrue(ex.getMessage().contains("vararg"), ex.getMessage());
    }

    @Test
    @DisplayName("CALL_STATIC_METHOD on an inherited static coroutine should name the declaring owner class")
    void gdccInheritedStaticCoroutineShouldUseDeclaringClassSymbol() {
        var baseClass = newClass("BaseWorker");
        var baseCoro = newFunction("base_coro");
        baseCoro.setStatic(true);
        baseCoro.setCoroutine(true);
        entry(baseCoro).appendInstruction(new ReturnInsn(null));
        baseClass.addFunction(baseCoro);

        var childClass = newClass("ChildWorker", "BaseWorker");

        var hostClass = newClass("Host");
        var caller = newFunction("run");
        caller.createAndAddVariable("state", GdccCoroStateType.CORO_STATE);
        entry(caller).appendInstruction(new CallStaticMethodInsn(
                "state",
                "ChildWorker",
                "base_coro",
                List.of()
        ));
        hostClass.addFunction(caller);

        var body = generateBody(hostClass, caller, newApi(List.of(), List.of()), List.of(hostClass, childClass, baseClass));
        // The start thunk is emitted on the declaring class, not on the receiver spelling.
        assertTrue(body.contains("$state = BaseWorker_base_coro__coro_start();"), body);
        assertFalse(body.contains("ChildWorker_base_coro"), body);
    }

    @Test
    @DisplayName("CALL_STATIC_METHOD on an engine static method should emit the static helper call")
    void engineStaticCallShouldEmitStaticHelper() {
        var hostClass = newClass("Host");
        var caller = newFunction("run");
        caller.createAndAddVariable("text", GdStringType.STRING);
        caller.createAndAddVariable("result", GdVariantType.VARIANT);
        entry(caller).appendInstruction(new CallStaticMethodInsn(
                "result",
                "JSON",
                "parse_string",
                List.of(new LirInstruction.VariableOperand("text"))
        ));
        hostClass.addFunction(caller);

        var body = generateBody(hostClass, caller, newApi(List.of(), List.of(jsonClassWithParseString())), List.of(hostClass));
        // Engine helper names lowercase the owner (`gdcc_engine_call_<static_><owner>_...`).
        assertTrue(body.contains("gdcc_engine_call_static_json_parse_string"), body);
        assertTrue(body.contains("$result = gdcc_engine_call_static_json_parse_string"), body);
    }

    @Test
    @DisplayName("CALL_STATIC_METHOD on a builtin static method should call the self-less wrapper")
    void builtinStaticCallShouldEmitWrapperWithoutSelf() {
        var hostClass = newClass("Host");
        var caller = newFunction("run");
        caller.createAndAddVariable("h", GdFloatType.FLOAT);
        caller.createAndAddVariable("s", GdFloatType.FLOAT);
        caller.createAndAddVariable("v", GdFloatType.FLOAT);
        caller.createAndAddVariable("alpha", GdFloatType.FLOAT);
        caller.createAndAddVariable("result", GdColorType.COLOR);
        entry(caller).appendInstruction(new CallStaticMethodInsn(
                "result",
                "Color",
                "from_hsv",
                List.of(
                        new LirInstruction.VariableOperand("h"),
                        new LirInstruction.VariableOperand("s"),
                        new LirInstruction.VariableOperand("v"),
                        new LirInstruction.VariableOperand("alpha")
                )
        ));
        hostClass.addFunction(caller);

        var body = generateBody(hostClass, caller, newApi(List.of(colorBuiltinWithFromHsv()), List.of()), List.of(hostClass));
        assertTrue(body.contains("$result = godot_Color_from_hsv($h, $s, $v, $alpha);"), body);
        assertFalse(body.contains("godot_Color_from_hsv(NULL"), body);
    }

    @Test
    @DisplayName("CALL_STATIC_METHOD should materialize GDCC static default_value_func without receiver")
    void gdccStaticCallShouldMaterializeStaticDefaultFunction() {
        var workerClass = newClass("Worker");
        var defaultCount = newFunction("default_count_static");
        defaultCount.setStatic(true);
        defaultCount.setReturnType(GdIntType.INT);
        workerClass.addFunction(defaultCount);

        var ping = newFunction("ping");
        ping.setStatic(true);
        ping.addParameter(new LirParameterDef("count", GdIntType.INT, "default_count_static", ping));
        entry(ping).appendInstruction(new ReturnInsn(null));
        workerClass.addFunction(ping);

        var hostClass = newClass("Host");
        var caller = newFunction("run");
        entry(caller).appendInstruction(new CallStaticMethodInsn(null, "Worker", "ping", List.of()));
        hostClass.addFunction(caller);

        var body = generateBody(hostClass, caller, newApi(List.of(), List.of()), List.of(hostClass, workerClass));
        assertTrue(body.contains("Worker_default_count_static()"), body);
        assertTrue(body.contains("Worker_ping(__gdcc_tmp_default_arg_1_"), body);
        assertFalse(body.contains("Worker_ping($"), body);
    }

    @Test
    @DisplayName("CALL_STATIC_METHOD should materialize multiple static source defaults in declaration order")
    void gdccStaticCallShouldMaterializeMultipleStaticDefaultsInDeclarationOrder() {
        var workerClass = newClass("Worker");

        var defaultCount = newFunction("_default_s_ping$count");
        defaultCount.setStatic(true);
        defaultCount.setReturnType(GdIntType.INT);
        workerClass.addFunction(defaultCount);

        var defaultLabel = newFunction("_default_s_ping$label");
        defaultLabel.setStatic(true);
        defaultLabel.setReturnType(GdStringType.STRING);
        workerClass.addFunction(defaultLabel);

        var ping = newFunction("ping");
        ping.setStatic(true);
        ping.addParameter(new LirParameterDef("a", GdIntType.INT, null, ping));
        ping.addParameter(new LirParameterDef("count", GdIntType.INT, "_default_s_ping$count", ping));
        ping.addParameter(new LirParameterDef("label", GdStringType.STRING, "_default_s_ping$label", ping));
        entry(ping).appendInstruction(new ReturnInsn(null));
        workerClass.addFunction(ping);

        var hostClass = newClass("Host");
        var caller = newFunction("run");
        caller.createAndAddVariable("a", GdIntType.INT);
        entry(caller).appendInstruction(new CallStaticMethodInsn(
                null,
                "Worker",
                "ping",
                List.of(new LirInstruction.VariableOperand("a"))
        ));
        hostClass.addFunction(caller);

        var body = generateBody(hostClass, caller, newApi(List.of(), List.of()), List.of(hostClass, workerClass));
        // Static shells are receiver-less and evaluated per call in declaration order.
        assertOrdered(
                body,
                "Worker__default_s_ping$count()",
                "Worker__default_s_ping$label()",
                "Worker_ping($a, __gdcc_tmp_default_arg_2_"
        );
        assertTrue(body.contains("__gdcc_tmp_default_arg_3_"), body);
        assertFalse(body.contains("Worker__default_s_ping$count($"), body);
    }

    @Test
    @DisplayName("CALL_STATIC_METHOD with an instance default_value_func should fail fast (no receiver)")
    void gdccStaticCallWithInstanceDefaultFunctionShouldFailFast() {
        var workerClass = newClass("Worker");
        var defaultCount = newFunction("default_count");
        defaultCount.setReturnType(GdIntType.INT);
        defaultCount.addParameter(new LirParameterDef("self", new GdObjectType("Worker"), null, defaultCount));
        workerClass.addFunction(defaultCount);

        var ping = newFunction("ping");
        ping.setStatic(true);
        ping.addParameter(new LirParameterDef("count", GdIntType.INT, "default_count", ping));
        entry(ping).appendInstruction(new ReturnInsn(null));
        workerClass.addFunction(ping);

        var hostClass = newClass("Host");
        var caller = newFunction("run");
        entry(caller).appendInstruction(new CallStaticMethodInsn(null, "Worker", "ping", List.of()));
        hostClass.addFunction(caller);

        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(hostClass, caller, newApi(List.of(), List.of()), List.of(hostClass, workerClass))
        );
        assertTrue(ex.getMessage().contains("is an instance function but the call has no receiver"), ex.getMessage());
    }

    @Test
    @DisplayName("CALL_STATIC_METHOD on an unknown static method should fail fast")
    void unknownStaticMethodShouldFailFast() {
        var workerClass = newClass("Worker");
        var hostClass = newClass("Host");
        var caller = newFunction("run");
        entry(caller).appendInstruction(new CallStaticMethodInsn(null, "Worker", "missing", List.of()));
        hostClass.addFunction(caller);

        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(hostClass, caller, newApi(List.of(), List.of()), List.of(hostClass, workerClass))
        );
        assertTrue(ex.getMessage().contains("missing"), ex.getMessage());
    }

    @Test
    @DisplayName("CALL_STATIC_METHOD on an unknown owner type should fail fast")
    void unknownOwnerTypeShouldFailFast() {
        var hostClass = newClass("Host");
        var caller = newFunction("run");
        entry(caller).appendInstruction(new CallStaticMethodInsn(null, "Missing", "build", List.of()));
        hostClass.addFunction(caller);

        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(hostClass, caller, newApi(List.of(), List.of()), List.of(hostClass))
        );
        assertTrue(ex.getMessage().contains("Static method owner type 'Missing' was not found"), ex.getMessage());
    }

    @Test
    @DisplayName("CALL_STATIC_METHOD on an instance-only method should fail fast (no static dynamic fallback)")
    void instanceOnlyMethodViaStaticCallShouldFailFast() {
        var workerClass = newClass("Worker");
        var ping = newFunction("ping");
        ping.addParameter(new LirParameterDef("self", new GdObjectType("Worker"), null, ping));
        entry(ping).appendInstruction(new ReturnInsn(null));
        workerClass.addFunction(ping);

        var hostClass = newClass("Host");
        var caller = newFunction("run");
        entry(caller).appendInstruction(new CallStaticMethodInsn(null, "Worker", "ping", List.of()));
        hostClass.addFunction(caller);

        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(hostClass, caller, newApi(List.of(), List.of()), List.of(hostClass, workerClass))
        );
        assertTrue(ex.getMessage().contains("ping"), ex.getMessage());
    }

    /// Asserts each fragment exists and appears strictly after the previous one (same ordering
    /// discipline as `CallMethodInsnGenTest`): lifecycle contracts are orderings, not mere presence.
    private static void assertOrdered(String text, String... fragmentsInOrder) {
        var searchFromIndex = 0;
        for (var fragment : fragmentsInOrder) {
            var index = text.indexOf(fragment, searchFromIndex);
            assertTrue(index >= 0, () -> "Missing fragment: " + fragment + "\n" + text);
            searchFromIndex = index + fragment.length();
        }
    }

    private LirClassDef newClass(String name) {
        return newClass(name, "RefCounted");
    }

    private LirClassDef newClass(String name, String superName) {
        return new LirClassDef(name, superName, false, false, Map.of(), List.of(), List.of(), List.of());
    }

    private LirFunctionDef newFunction(String name) {
        var func = new LirFunctionDef(name);
        func.setReturnType(GdVoidType.VOID);
        var entry = new LirBasicBlock("entry");
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        return func;
    }

    private LirBasicBlock entry(LirFunctionDef functionDef) {
        return functionDef.getBasicBlock("entry");
    }

    private String generateBody(LirClassDef clazz,
                                LirFunctionDef func,
                                ExtensionAPI api,
                                List<LirClassDef> gdccClasses) {
        var module = new LirModule("test_module", gdccClasses);
        var codegen = newCodegen(module, api, gdccClasses);
        return codegen.generateFuncBody(clazz, func);
    }

    private CCodegen newCodegen(LirModule module, ExtensionAPI api, List<LirClassDef> gdccClasses) {
        var classRegistry = new ClassRegistry(api);
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

    private ExtensionAPI newApi(List<ExtensionBuiltinClass> builtinClasses, List<ExtensionGdClass> gdClasses) {
        return new ExtensionAPI(
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                builtinClasses,
                gdClasses,
                List.of(),
                List.of()
        );
    }

    private ExtensionGdClass jsonClassWithParseString() {
        var parseString = new ExtensionGdClass.ClassMethod(
                "parse_string",
                false,
                false,
                true,
                false,
                1241577513L,
                List.of(),
                new ExtensionGdClass.ClassMethod.ClassMethodReturn("Variant"),
                List.of(new ExtensionFunctionArgument("json_string", "String", null, null))
        );
        return new ExtensionGdClass(
                "JSON",
                false,
                true,
                "RefCounted",
                "core",
                List.of(),
                List.of(parseString),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private ExtensionBuiltinClass colorBuiltinWithFromHsv() {
        var fromHsv = new ExtensionBuiltinClass.ClassMethod(
                "from_hsv",
                "Color",
                false,
                false,
                true,
                false,
                1573799446L,
                List.of(
                        new ExtensionFunctionArgument("h", "float", null, null),
                        new ExtensionFunctionArgument("s", "float", null, null),
                        new ExtensionFunctionArgument("v", "float", null, null),
                        new ExtensionFunctionArgument("alpha", "float", null, null)
                ),
                List.of(),
                new ExtensionBuiltinClass.ClassMethod.ReturnValue("Color")
        );
        return new ExtensionBuiltinClass(
                "Color",
                false,
                List.of(),
                List.of(fromHsv),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
    }
}
