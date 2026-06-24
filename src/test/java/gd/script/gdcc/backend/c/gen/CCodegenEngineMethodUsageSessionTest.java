package gd.script.gdcc.backend.c.gen;

import gd.script.gdcc.backend.GeneratedFile;
import gd.script.gdcc.backend.CodegenContext;
import gd.script.gdcc.backend.ProjectInfo;
import gd.script.gdcc.backend.c.gen.binding.EngineMethodSymbolKey;
import gd.script.gdcc.backend.c.gen.binding.usage.GodotBindingUsageSession;
import gd.script.gdcc.enums.GodotVersion;
import gd.script.gdcc.enums.GodotOperator;
import gd.script.gdcc.exception.InvalidInsnException;
import gd.script.gdcc.gdextension.ExtensionAPI;
import gd.script.gdcc.gdextension.ExtensionBuiltinClass;
import gd.script.gdcc.gdextension.ExtensionFunctionArgument;
import gd.script.gdcc.gdextension.ExtensionGdClass;
import gd.script.gdcc.gdextension.ExtensionSingleton;
import gd.script.gdcc.lir.LirBasicBlock;
import gd.script.gdcc.lir.LirClassDef;
import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.lir.LirModule;
import gd.script.gdcc.lir.LirInstruction;
import gd.script.gdcc.lir.insn.CallMethodInsn;
import gd.script.gdcc.lir.insn.BinaryOpInsn;
import gd.script.gdcc.lir.insn.ConstructObjectInsn;
import gd.script.gdcc.lir.insn.LoadPropertyInsn;
import gd.script.gdcc.lir.insn.LoadStaticInsn;
import gd.script.gdcc.lir.insn.ReturnInsn;
import gd.script.gdcc.lir.insn.StorePropertyInsn;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdArrayType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdStringType;
import gd.script.gdcc.type.GdVariantType;
import gd.script.gdcc.type.GdVoidType;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
                List.of(new LirInstruction.VariableOperand("label"))
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
                        new LirInstruction.VariableOperand("head"),
                        new LirInstruction.VariableOperand("tail")
                )
        ));
        entry(varargCall).setTerminator(new ReturnInsn(null));
        hostClass.addFunction(varargCall);

        var module = new LirModule("engine_usage_module", List.of(hostClass));
        var codegen = newCodegen(module, apiWith(List.of(arrayBuiltinWithSize()), List.of(probeClassWithOverloadedTouch())), List.of(hostClass));
        var session = GodotBindingUsageSession.forRegistry(codegen.ctx.classRegistry());

        codegen.generateFuncBody(hostClass, instanceTwice, session);
        assertSnapshot(session, List.of(spec("Probe", "touch", false, "P_RV")));

        codegen.generateFuncBody(hostClass, builtinCall, session);
        codegen.generateFuncBody(hostClass, dynamicCall, session);
        codegen.generateFuncBody(hostClass, gdccCall, session);
        assertSnapshot(session, List.of(spec("Probe", "touch", false, "P_RV")));

        codegen.generateFuncBody(hostClass, staticCall, session);
        codegen.generateFuncBody(hostClass, varargCall, session);
        assertSnapshot(session, List.of(
                spec("Probe", "touch", false, "P_RV"),
                spec("Probe", "touch", true, "PT_RV"),
                spec("Probe", "touch", false, "PI_RV_Xv")
        ));
    }

    @Test
    @DisplayName("module session should record exact engine property accessors once")
    void moduleSessionShouldRecordExactEnginePropertyAccessorsOnce() {
        var hostClass = newClass("Worker", "RefCounted");

        var propertyAccess = newVoidFunction("access_property");
        propertyAccess.createAndAddVariable("window", new GdObjectType("Window"));
        propertyAccess.createAndAddVariable("tmp", GdStringType.STRING);
        propertyAccess.createAndAddVariable("value", GdStringType.STRING);
        entry(propertyAccess).appendInstruction(new LoadPropertyInsn("tmp", "window_title", "window"));
        entry(propertyAccess).appendInstruction(new LoadPropertyInsn("tmp", "window_title", "window"));
        entry(propertyAccess).appendInstruction(new StorePropertyInsn("window_title", "window", "value"));
        entry(propertyAccess).appendInstruction(new StorePropertyInsn("window_title", "window", "value"));
        entry(propertyAccess).setTerminator(new ReturnInsn(null));
        hostClass.addFunction(propertyAccess);

        var module = new LirModule("engine_property_usage_module", List.of(hostClass));
        var codegen = newCodegen(
                module,
                apiWith(List.of(), List.of(windowClassWithRawPropertyAccessors())),
                List.of(hostClass)
        );
        var session = GodotBindingUsageSession.forRegistry(codegen.ctx.classRegistry());

        codegen.generateFuncBody(hostClass, propertyAccess, session);

        assertSnapshot(session, List.of(
                spec("Window", "get_title_override", false, "P_RT"),
                spec("Window", "set_title_override", false, "PT_RV")
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
                List.of(new LirInstruction.VariableOperand("text"))
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
        var session = GodotBindingUsageSession.forRegistry(codegen.ctx.classRegistry());

        assertThrows(InvalidInsnException.class, () -> codegen.generateFuncBody(hostClass, failVoidWithResult, session));
        assertTrue(session.engineMethods().isEmpty());

        assertThrows(InvalidInsnException.class, () -> codegen.generateFuncBody(hostClass, failResultTarget, session));
        assertTrue(session.engineMethods().isEmpty());

        assertThrows(InvalidInsnException.class, () -> codegen.generateFuncBody(hostClass, failArgType, session));
        assertTrue(session.engineMethods().isEmpty());

        codegen.generateFuncBody(hostClass, valid, session);
        assertSnapshot(session, List.of(spec("Probe", "count", false, "P_RI")));
    }

    @Test
    @DisplayName("failed render should not leak engine constructor usage into later successful renders")
    void failedRenderShouldNotLeakEngineConstructorUsageIntoLaterSuccessfulRenders() {
        var hostClass = newClass("Worker", "RefCounted");

        var invalid = newVoidFunction("construct_then_fail");
        invalid.createAndAddVariable("node", new GdObjectType("Node"));
        invalid.createAndAddVariable("left", GdIntType.INT);
        invalid.createAndAddVariable("right", GdIntType.INT);
        invalid.createAndAddVariable("sum", GdIntType.INT);
        entry(invalid).appendInstruction(new ConstructObjectInsn("node", "Node"));
        entry(invalid).appendInstruction(new BinaryOpInsn("sum", GodotOperator.ADD, "left", "right"));
        entry(invalid).setTerminator(new ReturnInsn(null));
        hostClass.addFunction(invalid);

        var valid = newVoidFunction("construct_node");
        valid.createAndAddVariable("node", new GdObjectType("Node"));
        entry(valid).appendInstruction(new ConstructObjectInsn("node", "Node"));
        entry(valid).setTerminator(new ReturnInsn(null));
        hostClass.addFunction(valid);

        var module = new LirModule("engine_constructor_usage_failure_module", List.of(hostClass));
        var codegen = newCodegen(module, apiWith(List.of(), List.of(nodeClass())), List.of(hostClass));
        var session = GodotBindingUsageSession.forRegistry(codegen.ctx.classRegistry());

        assertThrows(
                InvalidInsnException.class,
                () -> codegen.generateFuncBody(hostClass, invalid, session)
        );
        assertTrue(session.engineMethods().isEmpty());
        assertTrue(session.engineConstructors().isEmpty(), "Failed function renders must not commit constructor usage.");

        var validBody = codegen.generateFuncBody(hostClass, valid, session);
        assertTrue(validBody.contains("godot_new_Node()"), validBody);
        assertTrue(session.engineMethods().isEmpty());
        assertEquals(1, session.engineConstructors().size(), session.engineConstructors().toString());
        assertEquals("Node", session.engineConstructors().getFirst().className());
    }

    @Test
    @DisplayName("generate should filter fixed singleton wrappers and render only non-provided singleton wrappers")
    void generateShouldFilterFixedSingletonWrappersAndRenderOnlyNonProvidedSingletonWrappers() {
        var hostClass = newClass("SingletonUsageWorker", "RefCounted");

        var loadEngine = newVoidFunction("load_engine");
        loadEngine.createAndAddVariable("engine", new GdObjectType("Engine"));
        entry(loadEngine).appendInstruction(new LoadStaticInsn("engine", "@GlobalScope", "Engine"));
        entry(loadEngine).setTerminator(new ReturnInsn(null));
        hostClass.addFunction(loadEngine);

        var loadClassDb = newVoidFunction("load_class_db");
        loadClassDb.createAndAddVariable("class_db", new GdObjectType("ClassDB"));
        entry(loadClassDb).appendInstruction(new LoadStaticInsn("class_db", "@GlobalScope", "ClassDB"));
        entry(loadClassDb).setTerminator(new ReturnInsn(null));
        hostClass.addFunction(loadClassDb);

        var loadGameSingleton = newVoidFunction("load_game_singleton");
        loadGameSingleton.createAndAddVariable("game", new GdObjectType("Node"));
        entry(loadGameSingleton).appendInstruction(new LoadStaticInsn("game", "@GlobalScope", "GameSingleton"));
        entry(loadGameSingleton).setTerminator(new ReturnInsn(null));
        hostClass.addFunction(loadGameSingleton);

        var module = new LirModule("singleton_usage_module", List.of(hostClass));
        var codegen = newCodegen(module, singletonUsageApi(), List.of(hostClass));

        var files = codegen.generate();
        var entrySource = generatedFileText(files, "entry.c");
        var bindHeader = generatedFileText(files, "engine_method_binds.h");

        assertTrue(entrySource.contains("godot_Engine_singleton()"), entrySource);
        assertTrue(entrySource.contains("godot_ClassDB_singleton()"), entrySource);
        assertTrue(entrySource.contains("godot_GameSingleton_singleton()"), entrySource);
        assertFalse(bindHeader.contains("static inline godot_Engine * godot_Engine_singleton(void)"), bindHeader);
        assertFalse(bindHeader.contains("static inline godot_ClassDB * godot_ClassDB_singleton(void)"), bindHeader);
        assertTrue(bindHeader.contains("static inline godot_Node * godot_GameSingleton_singleton(void)"), bindHeader);
        assertTrue(bindHeader.contains("godot_global_get_singleton(GD_STATIC_SN(u8\"GameSingleton\"))"), bindHeader);
        assertTrue(bindHeader.contains("context.lookup_name = \"GameSingleton\";"), bindHeader);
        assertTrue(bindHeader.contains("context.owner = \"@GlobalScope\";"), bindHeader);
        assertTrue(bindHeader.contains("context.type = \"Node\";"), bindHeader);
        assertFalse(bindHeader.contains("godot_Node_singleton"), bindHeader);
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
                List.of(new LirInstruction.VariableOperand("label"))
        ));
        entry(sessionRender).setTerminator(new ReturnInsn(null));
        hostClass.addFunction(sessionRender);

        var module = new LirModule("engine_usage_public_body_module", List.of(hostClass));
        var codegen = newCodegen(module, apiWith(List.of(), List.of(probeClassWithOverloadedTouch())), List.of(hostClass));

        var firstBody = codegen.generateFuncBody(hostClass, publicRender);
        var secondBody = codegen.generateFuncBody(hostClass, publicRender);
        assertEquals(firstBody, secondBody);

        var session = GodotBindingUsageSession.forRegistry(codegen.ctx.classRegistry());
        codegen.generateFuncBody(hostClass, sessionRender, session);
        assertSnapshot(session, List.of(spec("Probe", "touch", true, "PT_RV")));
    }

    private record SnapshotSpec(
            @NotNull String ownerClassName,
            @NotNull String methodName,
            boolean isStatic,
            @NotNull String symbolId
    ) {
    }

    private static @NotNull SnapshotSpec spec(
            @NotNull String ownerClassName,
            @NotNull String methodName,
            boolean isStatic,
            @NotNull String symbolId
    ) {
        return new SnapshotSpec(ownerClassName, methodName, isStatic, symbolId);
    }

    private static void assertSnapshot(
            @NotNull GodotBindingUsageSession session,
            @NotNull List<SnapshotSpec> expected
    ) {
        var actual = session.engineMethods().stream()
                .map(resolved -> {
                    var key = EngineMethodSymbolKey.from(resolved);
                    assertNotNull(key, "session snapshot should only contain exact engine methods");
                    return spec(
                            resolved.ownerClassName(),
                            resolved.methodName(),
                            resolved.isStatic(),
                            key.symbolId()
                    );
                })
                .toList();
        assertIterableEquals(expected, actual);
    }

    private static @NotNull String generatedFileText(
            @NotNull List<GeneratedFile> files,
            @NotNull String filePath
    ) {
        return files.stream()
                .filter(file -> file.filePath().equals(filePath))
                .findFirst()
                .map(file -> new String(file.contentWriter()))
                .orElseThrow(() -> new AssertionError("Missing generated file " + filePath));
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

    private static @NotNull ExtensionAPI singletonUsageApi() {
        return new ExtensionAPI(
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(
                        singletonClass("Engine"),
                        singletonClass("ClassDB"),
                        nodeClass()
                ),
                List.of(
                        new ExtensionSingleton("Engine", "Engine"),
                        new ExtensionSingleton("ClassDB", "ClassDB"),
                        new ExtensionSingleton("GameSingleton", "Node")
                ),
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

    private static @NotNull ExtensionGdClass nodeClass() {
        return new ExtensionGdClass(
                "Node",
                false,
                true,
                "Object",
                "core",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private static @NotNull ExtensionGdClass singletonClass(@NotNull String name) {
        return new ExtensionGdClass(
                name,
                false,
                true,
                "Object",
                "core",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private static @NotNull ExtensionGdClass windowClassWithRawPropertyAccessors() {
        var getTitle = new ExtensionGdClass.ClassMethod(
                "get_title_override",
                false,
                false,
                false,
                false,
                81L,
                List.of(),
                new ExtensionGdClass.ClassMethod.ClassMethodReturn("String"),
                List.of()
        );
        var setTitle = new ExtensionGdClass.ClassMethod(
                "set_title_override",
                false,
                false,
                false,
                false,
                82L,
                List.of(),
                new ExtensionGdClass.ClassMethod.ClassMethodReturn("void"),
                List.of(new ExtensionFunctionArgument("title", "String", null, null))
        );
        return new ExtensionGdClass(
                "Window",
                false,
                true,
                "Object",
                "core",
                List.of(),
                List.of(getTitle, setTitle),
                List.of(),
                List.of(new ExtensionGdClass.PropertyInfo(
                        "window_title",
                        "String",
                        true,
                        true,
                        "",
                        "get_title_override",
                        "set_title_override",
                        null
                )),
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
