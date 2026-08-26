package gd.script.gdcc.backend.c.build;

import gd.script.gdcc.backend.CodegenContext;
import gd.script.gdcc.backend.ProjectInfo;
import gd.script.gdcc.enums.GodotVersion;
import gd.script.gdcc.enums.LifecycleProvenance;
import gd.script.gdcc.gdextension.ExtensionAPI;
import gd.script.gdcc.gdextension.ExtensionBuiltinClass;
import gd.script.gdcc.lir.LirBasicBlock;
import gd.script.gdcc.lir.LirCaptureDef;
import gd.script.gdcc.lir.LirClassDef;
import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.lir.LirInstruction;
import gd.script.gdcc.lir.LirModule;
import gd.script.gdcc.lir.LirParameterDef;
import gd.script.gdcc.lir.insn.AwaitInsn;
import gd.script.gdcc.lir.insn.CallMethodInsn;
import gd.script.gdcc.lir.insn.CallStaticMethodInsn;
import gd.script.gdcc.lir.insn.ConstructLambdaInsn;
import gd.script.gdcc.lir.insn.DestructInsn;
import gd.script.gdcc.lir.insn.ReturnInsn;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdCallableType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdSignalType;
import gd.script.gdcc.type.GdStringType;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.type.GdVariantType;
import gd.script.gdcc.type.GdVoidType;
import gd.script.gdcc.type.GdccCoroStateType;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Zig-gated syntax probe for the generated coroutine C surface: a module covering every
/// engine-entry/desc-callback branch (int / String / object / Variant / void) is generated
/// and its `entry.c` must compile to an object file. String-anchor tests cannot see C
/// declaration-order or type errors; this probe catches them. Skipped when no zig exists.
/// Note: `zig cc -fsyntax-only` mis-handles quoted includes on zig 0.16 (spurious
/// `FileNotFound` at the first `#include "..."`), so the probe compiles with `-c` instead.
class CCoroutineGeneratedCSyntaxSmokeTest {
    private static final Path GODOT_INCLUDE_DIR = Path.of("src/main/c/codegen/include_451/godot").toAbsolutePath().normalize();
    private static final Path GDCC_INCLUDE_DIR = Path.of("src/main/c/codegen/include_451/gdcc").toAbsolutePath().normalize();

    @TempDir
    private Path generatedDir;

    @Test
    void generatedCoroutineModuleShouldPassCSyntaxCheck() throws IOException, InterruptedException {
        var zig = ZigUtil.findZig();
        Assumptions.assumeTrue(zig != null, "Zig executable is required for the generated-C syntax probe");

        var workerClass = new LirClassDef("SyntaxWorker", "RefCounted");
        workerClass.addFunction(coroutine("sum_to", GdIntType.INT,
                List.of(param("count", GdIntType.INT), param("label", GdStringType.STRING)),
                "count"));
        workerClass.addFunction(coroutine("display_name", GdStringType.STRING,
                List.of(param("label", GdStringType.STRING)),
                "label"));
        workerClass.addFunction(coroutine("spawn_peer", new GdObjectType("SyntaxWorker"),
                List.of(param("peer", new GdObjectType("SyntaxWorker"))),
                "peer"));
        workerClass.addFunction(coroutine("fetch", GdVariantType.VARIANT, List.of(), null));
        workerClass.addFunction(coroutine("wait_done", GdVoidType.VOID, List.of(), null));

        // Named static coroutines: no `self` parameter, so the frame/state object,
        // start thunk and ClassDB wrapper compile-verify the receiver-free shape.
        workerClass.addFunction(staticCoroutine("static_sum", GdIntType.INT,
                List.of(param("count", GdIntType.INT)),
                "count"));
        workerClass.addFunction(staticCoroutine("static_fetch", GdVariantType.VARIANT, List.of(), null));

        // Lambda capturing self + a String frame parameter: compile-verifies the frame-aware
        // capture copy and the `_coro_param_self.instance_id` object_id channel.
        var lambda = new LirFunctionDef("_lambda_0", "entry");
        lambda.setLambda(true);
        lambda.setHidden(true);
        lambda.setStatic(true);
        lambda.setReturnType(GdVoidType.VOID);
        lambda.addCapture(new LirCaptureDef("self", new GdObjectType("SyntaxWorker"), lambda));
        lambda.addCapture(new LirCaptureDef("label", GdStringType.STRING, lambda));
        var lambdaEntry = new LirBasicBlock("entry");
        lambdaEntry.appendInstruction(new ReturnInsn(null));
        lambda.addBasicBlock(lambdaEntry);
        workerClass.addFunction(lambda);

        var schedule = new LirFunctionDef("schedule");
        schedule.setReturnType(GdVoidType.VOID);
        schedule.setCoroutine(true);
        schedule.addParameter(new LirParameterDef("self", new GdObjectType("SyntaxWorker"), null, schedule));
        schedule.addParameter(new LirParameterDef("label", new GdStringType(), null, schedule));
        schedule.addParameter(new LirParameterDef("seed", GdIntType.INT, null, schedule));
        schedule.createAndAddVariable("cb", new GdCallableType());
        var scheduleEntry = new LirBasicBlock("entry");
        scheduleEntry.appendInstruction(new ConstructLambdaInsn(
                "cb",
                "_lambda_0",
                List.of(new LirInstruction.VariableOperand("self"), new LirInstruction.VariableOperand("label"))
        ));

        // Coroutine lambda: captures + an in-body await compile-verify the
        // `_coro_capture_*` frame fields, the `_capture` start-thunk tail parameter, the
        // forward-declaration ordering ahead of `call_func`, and the done/suspend dispatch —
        // string anchors alone cannot see C declaration-order or type errors here.
        var coroLambda = new LirFunctionDef("_lambda_1", "entry");
        coroLambda.setLambda(true);
        coroLambda.setHidden(true);
        coroLambda.setStatic(true);
        coroLambda.setCoroutine(true);
        coroLambda.setReturnType(GdIntType.INT);
        coroLambda.addCapture(new LirCaptureDef("self", new GdObjectType("SyntaxWorker"), coroLambda));
        coroLambda.addCapture(new LirCaptureDef("seed", GdIntType.INT, coroLambda));
        coroLambda.addCapture(new LirCaptureDef("label", GdStringType.STRING, coroLambda));
        coroLambda.createAndAddVariable("dyn", GdVariantType.VARIANT);
        coroLambda.createAndAddVariable("await_res", GdVariantType.VARIANT);
        var coroLambdaEntry = new LirBasicBlock("entry");
        coroLambdaEntry.appendInstruction(new AwaitInsn("await_res", "dyn"));
        coroLambdaEntry.setTerminator(new ReturnInsn("seed"));
        coroLambda.addBasicBlock(coroLambdaEntry);
        workerClass.addFunction(coroLambda);

        // Construct the coroutine lambda from the coroutine frame parameters (capture sources
        // render as frame fields through the frame-aware copy path).
        schedule.createAndAddVariable("cb2", new GdCallableType());
        scheduleEntry.appendInstruction(new ConstructLambdaInsn(
                "cb2",
                "_lambda_1",
                List.of(
                        new LirInstruction.VariableOperand("self"),
                        new LirInstruction.VariableOperand("seed"),
                        new LirInstruction.VariableOperand("label")
                )
        ));
        scheduleEntry.setTerminator(new ReturnInsn(null));
        schedule.addBasicBlock(scheduleEntry);
        schedule.setEntryBlockId("entry");
        workerClass.addFunction(schedule);

        // Await surface: all three dispatch paths plus statement-position detach inside one
        // coroutine body, so the generated await C is compile-verified (frame-parameter
        // addressing, staged Variant temps, typed out_typed slots, moved-from NULL reset).
        var runAll = new LirFunctionDef("run_all");
        runAll.setReturnType(GdVoidType.VOID);
        runAll.setCoroutine(true);
        runAll.addParameter(new LirParameterDef("self", new GdObjectType("SyntaxWorker"), null, runAll));
        runAll.addParameter(new LirParameterDef("sig", new GdSignalType(), null, runAll));
        runAll.addParameter(new LirParameterDef("dyn", GdVariantType.VARIANT, null, runAll));
        runAll.createAndAddVariable("count", GdIntType.INT);
        runAll.createAndAddVariable("label", GdStringType.STRING);
        runAll.createAndAddVariable("res_v", GdVariantType.VARIANT);
        runAll.createAndAddVariable("res_i", GdIntType.INT);
        runAll.createAndAddVariable("state_fetch", GdccCoroStateType.CORO_STATE);
        runAll.createAndAddVariable("state_sum", GdccCoroStateType.CORO_STATE);
        runAll.createAndAddVariable("state_static_sum", GdccCoroStateType.CORO_STATE);
        runAll.createAndAddVariable("__coro_state_9", GdccCoroStateType.CORO_STATE);
        runAll.createAndAddVariable("__coro_state_10", GdccCoroStateType.CORO_STATE);
        var runAllEntry = new LirBasicBlock("entry");
        runAllEntry.appendInstruction(new AwaitInsn("res_v", "sig"));
        runAllEntry.appendInstruction(new CallMethodInsn("state_fetch", "fetch", "self", List.of()));
        runAllEntry.appendInstruction(new AwaitInsn("res_v", "state_fetch"));
        runAllEntry.appendInstruction(new CallMethodInsn(
                "state_sum",
                "sum_to",
                "self",
                List.of(new LirInstruction.VariableOperand("count"), new LirInstruction.VariableOperand("label"))
        ));
        runAllEntry.appendInstruction(new AwaitInsn("res_i", "state_sum"));
        // Static coroutine call surface: awaited typed result and statement-position
        // fire-and-forget detach, both receiver-free.
        runAllEntry.appendInstruction(new CallStaticMethodInsn(
                "state_static_sum",
                "SyntaxWorker",
                "static_sum",
                List.of(new LirInstruction.VariableOperand("count"))
        ));
        runAllEntry.appendInstruction(new AwaitInsn("res_i", "state_static_sum"));
        runAllEntry.appendInstruction(new AwaitInsn("res_v", "dyn"));
        runAllEntry.appendInstruction(new CallMethodInsn("__coro_state_9", "fetch", "self", List.of()));
        runAllEntry.appendInstruction(new DestructInsn("__coro_state_9", LifecycleProvenance.INTERNAL));
        runAllEntry.appendInstruction(new CallStaticMethodInsn("__coro_state_10", "SyntaxWorker", "static_fetch", List.of()));
        runAllEntry.appendInstruction(new DestructInsn("__coro_state_10", LifecycleProvenance.INTERNAL));
        runAllEntry.setTerminator(new ReturnInsn(null));
        runAll.addBasicBlock(runAllEntry);
        runAll.setEntryBlockId("entry");
        workerClass.addFunction(runAll);

        var module = new LirModule("coroutine_syntax_module", List.of(workerClass));
        // Callable default materialization in `__prepare__` validates against this metadata.
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
        // GDCC-dispatch resolution of the coroutine start-thunk calls in `run_all`.
        classRegistry.addGdccClass(workerClass);
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var codegen = new gd.script.gdcc.backend.c.gen.CCodegen();
        codegen.prepare(new CodegenContext(projectInfo, classRegistry), module);
        var files = codegen.generate();
        for (var file : files) {
            Files.writeString(
                    generatedDir.resolve(file.filePath()),
                    new String(file.contentWriter(), StandardCharsets.UTF_8),
                    StandardCharsets.UTF_8
            );
        }

        var entryC = generatedDir.resolve("entry.c");
        var command = new ArrayList<String>();
        command.add(zig.toString());
        command.add("cc");
        command.add("-std=c23");
        command.add("-c");
        command.add("-I" + GODOT_INCLUDE_DIR);
        command.add("-I" + GDCC_INCLUDE_DIR);
        command.add("-I" + generatedDir);
        command.add(entryC.toString());
        command.add("-o");
        command.add(generatedDir.resolve("entry.o").toString());
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        var exitCode = process.waitFor();
        assertEquals(0, exitCode, () -> String.join(" ", command) + "\n" + output);
    }

    private record ParamSpec(String name, GdType type) {
    }

    private static @NotNull ParamSpec param(String name, GdType type) {
        return new ParamSpec(name, type);
    }

    /// Static coroutines take no `self` parameter; the returned-variable shape is
    /// otherwise identical to the instance `coroutine(...)` fixture.
    private static @NotNull LirFunctionDef staticCoroutine(String name,
                                                           GdType returnType,
                                                           List<ParamSpec> extraParams,
                                                           String returnedVariableId) {
        var func = new LirFunctionDef(name);
        func.setStatic(true);
        func.setReturnType(returnType);
        func.setCoroutine(true);
        for (var parameter : extraParams) {
            func.addParameter(new LirParameterDef(parameter.name(), parameter.type(), null, func));
        }
        var entry = new LirBasicBlock("entry");
        if (returnType instanceof GdVoidType) {
            entry.setTerminator(new ReturnInsn(null));
        } else if (returnedVariableId != null) {
            entry.setTerminator(new ReturnInsn(returnedVariableId));
        } else {
            func.createAndAddVariable("v", GdVariantType.VARIANT);
            entry.setTerminator(new ReturnInsn("v"));
        }
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        return func;
    }

    private static @NotNull LirFunctionDef coroutine(String name,
                                                     GdType returnType,
                                                     List<ParamSpec> extraParams,
                                                     String returnedVariableId) {
        var func = new LirFunctionDef(name);
        func.setReturnType(returnType);
        func.setCoroutine(true);
        func.addParameter(new LirParameterDef("self", new GdObjectType("SyntaxWorker"), null, func));
        for (var parameter : extraParams) {
            func.addParameter(new LirParameterDef(parameter.name(), parameter.type(), null, func));
        }
        var entry = new LirBasicBlock("entry");
        if (returnType instanceof GdVoidType) {
            entry.setTerminator(new ReturnInsn(null));
        } else if (returnedVariableId != null) {
            entry.setTerminator(new ReturnInsn(returnedVariableId));
        } else {
            func.createAndAddVariable("v", GdVariantType.VARIANT);
            entry.setTerminator(new ReturnInsn("v"));
        }
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        return func;
    }
}
