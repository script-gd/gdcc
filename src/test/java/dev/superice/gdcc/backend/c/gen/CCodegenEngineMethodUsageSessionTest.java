package dev.superice.gdcc.backend.c.gen;

import dev.superice.gdcc.backend.CodegenContext;
import dev.superice.gdcc.backend.ProjectInfo;
import dev.superice.gdcc.backend.c.gen.binding.EngineMethodUsageSession;
import dev.superice.gdcc.enums.GodotVersion;
import dev.superice.gdcc.exception.InvalidInsnException;
import dev.superice.gdcc.gdextension.ExtensionAPI;
import dev.superice.gdcc.gdextension.ExtensionBuiltinClass;
import dev.superice.gdcc.gdextension.ExtensionFunctionArgument;
import dev.superice.gdcc.gdextension.ExtensionGdClass;
import dev.superice.gdcc.lir.LirBasicBlock;
import dev.superice.gdcc.lir.LirClassDef;
import dev.superice.gdcc.lir.LirFunctionDef;
import dev.superice.gdcc.lir.LirModule;
import dev.superice.gdcc.lir.insn.CallMethodInsn;
import dev.superice.gdcc.lir.insn.ReturnInsn;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.type.GdArrayType;
import dev.superice.gdcc.type.GdIntType;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdStringType;
import dev.superice.gdcc.type.GdVariantType;
import dev.superice.gdcc.type.GdVoidType;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CCodegenEngineMethodUsageSessionTest {
    @Test
    @DisplayName("module session should record exact engine methods once in first-hit order and ignore non-engine routes")
    void moduleSessionShouldRecordExactEngineMethodsOnceInFirstHitOrderAndIgnoreNonEngineRoutes() {
        var hostClass = newClass("Worker", "RefCounted");
        var gdccPing = newVoidFunction("ping");
        gdccPing.createAndAddVariable("self", new GdObjectType("Worker"));
        entry(gdccPing).setTerminator(new ReturnInsn(null));
        hostClass.addFunction(gdccPing);

        var instanceTwice = newVoidFunction("call_instance_twice");
        instanceTwice.createAndAddVariable("probe", new GdObjectType("Probe"));
        entry(instanceTwice).appendInstruction(new CallMethodInsn(null, "touch", "probe", List.of()));
        entry(instanceTwice).appendInstruction(new CallMethodInsn(null, "touch", "probe", List.of()));
        entry(instanceTwice).setTerminator(new ReturnInsn(null));
        hostClass.addFunction(instanceTwice);

        var builtinCall = newVoidFunction("call_builtin");
        builtinCall.createAndAddVariable("array", new GdArrayType(GdVariantType.VARIANT));
        entry(builtinCall).appendInstruction(new CallMethodInsn(null, "size", "array", List.of()));
        entry(builtinCall).setTerminator(new ReturnInsn(null));
        hostClass.addFunction(builtinCall);

        var dynamicCall = newVoidFunction("call_dynamic");
        dynamicCall.createAndAddVariable("value", GdVariantType.VARIANT);
        entry(dynamicCall).appendInstruction(new CallMethodInsn(null, "callv", "value", List.of()));
        entry(dynamicCall).setTerminator(new ReturnInsn(null));
        hostClass.addFunction(dynamicCall);

        var gdccCall = newVoidFunction("call_gdcc");
        gdccCall.createAndAddVariable("worker", new GdObjectType("Worker"));
        entry(gdccCall).appendInstruction(new CallMethodInsn(null, "ping", "worker", List.of()));
        entry(gdccCall).setTerminator(new ReturnInsn(null));
        hostClass.addFunction(gdccCall);

        var staticCall = newVoidFunction("call_static");
        staticCall.createAndAddVariable("probe", new GdObjectType("Probe"));
        staticCall.createAndAddVariable("label", GdStringType.STRING);
        entry(staticCall).appendInstruction(new CallMethodInsn(
                null,
                "touch",
                "probe",
                List.of(new dev.superice.gdcc.lir.LirInstruction.VariableOperand("label"))
        ));
        entry(staticCall).setTerminator(new ReturnInsn(null));
        hostClass.addFunction(staticCall);

        var varargCall = newVoidFunction("call_vararg");
        varargCall.createAndAddVariable("probe", new GdObjectType("Probe"));
        varargCall.createAndAddVariable("head", GdIntType.INT);
        varargCall.createAndAddVariable("tail", GdVariantType.VARIANT);
        entry(varargCall).appendInstruction(new CallMethodInsn(
                null,
                "touch",
                "probe",
                List.of(
                        new dev.superice.gdcc.lir.LirInstruction.VariableOperand("head"),
                        new dev.superice.gdcc.lir.LirInstruction.VariableOperand("tail")
                )
        ));
        entry(varargCall).setTerminator(new ReturnInsn(null));
        hostClass.addFunction(varargCall);

        var module = new LirModule("engine_usage_module", List.of(hostClass));
        var codegen = newCodegen(module, apiWith(List.of(arrayBuiltinWithSize()), List.of(probeClassWithOverloadedTouch())), List.of(hostClass));
        var session = new EngineMethodUsageSession();

        codegen.generateFuncBody(hostClass, instanceTwice, session);
        assertSnapshot(session, List.of(spec("Probe", "touch", 55L, false, false)));

        codegen.generateFuncBody(hostClass, builtinCall, session);
        codegen.generateFuncBody(hostClass, dynamicCall, session);
        codegen.generateFuncBody(hostClass, gdccCall, session);
        assertSnapshot(session, List.of(spec("Probe", "touch", 55L, false, false)));

        codegen.generateFuncBody(hostClass, staticCall, session);
        codegen.generateFuncBody(hostClass, varargCall, session);
        assertSnapshot(session, List.of(
                spec("Probe", "touch", 55L, false, false),
                spec("Probe", "touch", 55L, true, false),
                spec("Probe", "touch", 55L, false, true)
        ));
    }

    @Test
    @DisplayName("failed render should not leak exact engine usage into later successful renders")
    void failedRenderShouldNotLeakExactEngineUsageIntoLaterSuccessfulRenders() {
        var hostClass = newClass("Worker", "RefCounted");

        var failVoidWithResult = newVoidFunction("fail_void_with_result");
        failVoidWithResult.createAndAddVariable("probe", new GdObjectType("Probe"));
        failVoidWithResult.createAndAddVariable("ret", GdIntType.INT);
        entry(failVoidWithResult).appendInstruction(new CallMethodInsn("ret", "touch", "probe", List.of()));
        entry(failVoidWithResult).setTerminator(new ReturnInsn(null));
        hostClass.addFunction(failVoidWithResult);

        var failResultTarget = newVoidFunction("fail_result_target");
        failResultTarget.createAndAddVariable("probe", new GdObjectType("Probe"));
        failResultTarget.createAndAddVariable("text", GdStringType.STRING);
        entry(failResultTarget).appendInstruction(new CallMethodInsn("text", "count", "probe", List.of()));
        entry(failResultTarget).setTerminator(new ReturnInsn(null));
        hostClass.addFunction(failResultTarget);

        var failArgType = newVoidFunction("fail_arg_type");
        failArgType.createAndAddVariable("probe", new GdObjectType("Probe"));
        failArgType.createAndAddVariable("text", GdStringType.STRING);
        entry(failArgType).appendInstruction(new CallMethodInsn(
                null,
                "accept_count",
                "probe",
                List.of(new dev.superice.gdcc.lir.LirInstruction.VariableOperand("text"))
        ));
        entry(failArgType).setTerminator(new ReturnInsn(null));
        hostClass.addFunction(failArgType);

        var valid = newVoidFunction("valid_count");
        valid.createAndAddVariable("probe", new GdObjectType("Probe"));
        valid.createAndAddVariable("count", GdIntType.INT);
        entry(valid).appendInstruction(new CallMethodInsn("count", "count", "probe", List.of()));
        entry(valid).setTerminator(new ReturnInsn(null));
        hostClass.addFunction(valid);

        var module = new LirModule("engine_usage_failure_module", List.of(hostClass));
        var codegen = newCodegen(module, apiWith(List.of(), List.of(probeClassWithFailureAnchors())), List.of(hostClass));
        var session = new EngineMethodUsageSession();

        assertThrows(InvalidInsnException.class, () -> codegen.generateFuncBody(hostClass, failVoidWithResult, session));
        assertTrue(session.snapshot().isEmpty());

        assertThrows(InvalidInsnException.class, () -> codegen.generateFuncBody(hostClass, failResultTarget, session));
        assertTrue(session.snapshot().isEmpty());

        assertThrows(InvalidInsnException.class, () -> codegen.generateFuncBody(hostClass, failArgType, session));
        assertTrue(session.snapshot().isEmpty());

        codegen.generateFuncBody(hostClass, valid, session);
        assertSnapshot(session, List.of(spec("Probe", "count", 72L, false, false)));
    }

    @Test
    @DisplayName("public generateFuncBody should stay deterministic and side-effect free")
    void publicGenerateFuncBodyShouldStayDeterministicAndSideEffectFree() {
        var hostClass = newClass("Worker", "RefCounted");

        var publicRender = newVoidFunction("public_render");
        publicRender.createAndAddVariable("probe", new GdObjectType("Probe"));
        entry(publicRender).appendInstruction(new CallMethodInsn(null, "touch", "probe", List.of()));
        entry(publicRender).setTerminator(new ReturnInsn(null));
        hostClass.addFunction(publicRender);

        var sessionRender = newVoidFunction("session_render");
        sessionRender.createAndAddVariable("probe", new GdObjectType("Probe"));
        sessionRender.createAndAddVariable("label", GdStringType.STRING);
        entry(sessionRender).appendInstruction(new CallMethodInsn(
                null,
                "touch",
                "probe",
                List.of(new dev.superice.gdcc.lir.LirInstruction.VariableOperand("label"))
        ));
        entry(sessionRender).setTerminator(new ReturnInsn(null));
        hostClass.addFunction(sessionRender);

        var module = new LirModule("engine_usage_public_body_module", List.of(hostClass));
        var codegen = newCodegen(module, apiWith(List.of(), List.of(probeClassWithOverloadedTouch())), List.of(hostClass));

        var firstBody = codegen.generateFuncBody(hostClass, publicRender);
        var secondBody = codegen.generateFuncBody(hostClass, publicRender);
        assertEquals(firstBody, secondBody);

        var session = new EngineMethodUsageSession();
        codegen.generateFuncBody(hostClass, sessionRender, session);
        assertSnapshot(session, List.of(spec("Probe", "touch", 55L, true, false)));
    }

    private record SnapshotSpec(
            @NotNull String ownerClassName,
            @NotNull String methodName,
            long hash,
            boolean isStatic,
            boolean isVararg
    ) {
    }

    private static @NotNull SnapshotSpec spec(
            @NotNull String ownerClassName,
            @NotNull String methodName,
            long hash,
            boolean isStatic,
            boolean isVararg
    ) {
        return new SnapshotSpec(ownerClassName, methodName, hash, isStatic, isVararg);
    }

    private static void assertSnapshot(
            @NotNull EngineMethodUsageSession session,
            @NotNull List<SnapshotSpec> expected
    ) {
        var actual = session.snapshot().stream()
                .map(resolved -> {
                    var bindSpec = resolved.engineMethodBindSpec();
                    assertNotNull(bindSpec, "session snapshot should only contain exact engine methods");
                    return spec(
                            resolved.ownerClassName(),
                            resolved.methodName(),
                            bindSpec.hash(),
                            resolved.isStatic(),
                            resolved.isVararg()
                    );
                })
                .toList();
        assertIterableEquals(expected, actual);
    }

    private static @NotNull CCodegen newCodegen(
            @NotNull LirModule module,
            @NotNull ExtensionAPI api,
            @NotNull List<LirClassDef> gdccClasses
    ) {
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

    private static @NotNull ExtensionAPI apiWith(
            @NotNull List<ExtensionBuiltinClass> builtinClasses,
            @NotNull List<ExtensionGdClass> gdClasses
    ) {
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

    private static @NotNull LirClassDef newClass(@NotNull String name, @NotNull String superName) {
        return new LirClassDef(name, superName, false, false, Map.of(), List.of(), List.of(), List.of());
    }

    private static @NotNull LirFunctionDef newVoidFunction(@NotNull String name) {
        var func = new LirFunctionDef(name);
        func.setReturnType(GdVoidType.VOID);
        var entry = new LirBasicBlock("entry");
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        return func;
    }

    private static @NotNull LirBasicBlock entry(@NotNull LirFunctionDef functionDef) {
        return Objects.requireNonNull(functionDef.getBasicBlock("entry"));
    }

    private static @NotNull ExtensionBuiltinClass arrayBuiltinWithSize() {
        var size = new ExtensionBuiltinClass.ClassMethod(
                "size",
                "int",
                false,
                true,
                false,
                false,
                0L,
                List.of(),
                List.of(),
                new ExtensionBuiltinClass.ClassMethod.ReturnValue("int")
        );
        return new ExtensionBuiltinClass(
                "Array",
                false,
                List.of(),
                List.of(size),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private static @NotNull ExtensionGdClass probeClassWithOverloadedTouch() {
        var instanceTouch = new ExtensionGdClass.ClassMethod(
                "touch",
                false,
                false,
                false,
                false,
                55L,
                List.of(551L),
                new ExtensionGdClass.ClassMethod.ClassMethodReturn("void"),
                List.of()
        );
        var staticTouch = new ExtensionGdClass.ClassMethod(
                "touch",
                false,
                false,
                true,
                false,
                55L,
                List.of(552L),
                new ExtensionGdClass.ClassMethod.ClassMethodReturn("void"),
                List.of(new ExtensionFunctionArgument("label", "String", null, null))
        );
        var varargTouch = new ExtensionGdClass.ClassMethod(
                "touch",
                false,
                true,
                false,
                false,
                55L,
                List.of(553L),
                new ExtensionGdClass.ClassMethod.ClassMethodReturn("void"),
                List.of(new ExtensionFunctionArgument("head", "int", null, null))
        );
        return new ExtensionGdClass(
                "Probe",
                false,
                true,
                "Object",
                "core",
                List.of(),
                List.of(instanceTouch, staticTouch, varargTouch),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private static @NotNull ExtensionGdClass probeClassWithFailureAnchors() {
        var touch = new ExtensionGdClass.ClassMethod(
                "touch",
                false,
                false,
                false,
                false,
                71L,
                List.of(),
                new ExtensionGdClass.ClassMethod.ClassMethodReturn("void"),
                List.of()
        );
        var count = new ExtensionGdClass.ClassMethod(
                "count",
                false,
                false,
                false,
                false,
                72L,
                List.of(),
                new ExtensionGdClass.ClassMethod.ClassMethodReturn("int"),
                List.of()
        );
        var acceptCount = new ExtensionGdClass.ClassMethod(
                "accept_count",
                false,
                false,
                false,
                false,
                73L,
                List.of(),
                new ExtensionGdClass.ClassMethod.ClassMethodReturn("void"),
                List.of(new ExtensionFunctionArgument("count", "int", null, null))
        );
        return new ExtensionGdClass(
                "Probe",
                false,
                true,
                "Object",
                "core",
                List.of(),
                List.of(touch, count, acceptCount),
                List.of(),
                List.of(),
                List.of()
        );
    }
}
