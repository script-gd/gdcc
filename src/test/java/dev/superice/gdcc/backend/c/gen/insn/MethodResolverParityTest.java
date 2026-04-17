package dev.superice.gdcc.backend.c.gen.insn;

import dev.superice.gdcc.backend.CodegenContext;
import dev.superice.gdcc.backend.ProjectInfo;
import dev.superice.gdcc.backend.c.gen.CBodyBuilder;
import dev.superice.gdcc.backend.c.gen.CGenHelper;
import dev.superice.gdcc.enums.GodotVersion;
import dev.superice.gdcc.exception.InvalidInsnException;
import dev.superice.gdcc.gdextension.ExtensionAPI;
import dev.superice.gdcc.gdextension.ExtensionBuiltinClass;
import dev.superice.gdcc.gdextension.ExtensionFunctionArgument;
import dev.superice.gdcc.gdextension.ExtensionGdClass;
import dev.superice.gdcc.lir.LirBasicBlock;
import dev.superice.gdcc.lir.LirClassDef;
import dev.superice.gdcc.lir.LirFunctionDef;
import dev.superice.gdcc.lir.LirParameterDef;
import dev.superice.gdcc.lir.LirVariable;
import dev.superice.gdcc.lir.insn.ReturnInsn;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.scope.resolver.ScopeMethodResolver;
import dev.superice.gdcc.type.GdArrayType;
import dev.superice.gdcc.type.GdIntType;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdStringType;
import dev.superice.gdcc.type.GdVariantType;
import dev.superice.gdcc.type.GdVoidType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MethodResolverParityTest {
    @Test
    @DisplayName("backend method adapter should match shared resolved GDCC method")
    void backendMethodAdapterShouldMatchSharedResolvedGdccMethod() {
        var workerClass = newClass("Worker", "RefCounted");
        var ping = newFunction("ping");
        ping.addParameter(new LirParameterDef("self", new GdObjectType("Worker"), null, ping));
        entry(ping).appendInstruction(new ReturnInsn(null));
        workerClass.addFunction(ping);

        var bodyBuilder = newBodyBuilder(emptyApi(), List.of(workerClass));
        var receiverVar = new LirVariable("worker", new GdObjectType("Worker"), bodyBuilder.func());

        var shared = ScopeMethodResolver.resolveInstanceMethod(
                bodyBuilder.classRegistry(),
                receiverVar.type(),
                "ping",
                List.of()
        );
        var sharedResolved = assertInstanceOf(ScopeMethodResolver.Resolved.class, shared);

        var backendResolved = BackendMethodCallResolver.resolve(bodyBuilder, receiverVar, "ping", List.of());
        assertEquals(sharedResolved.method().ownerClass().getName(), backendResolved.ownerClassName());
        assertEquals(sharedResolved.method().methodName(), backendResolved.methodName());
        assertEquals(BackendMethodCallResolver.DispatchMode.GDCC, backendResolved.mode());
    }

    @Test
    @DisplayName("backend method adapter should preserve object dynamic fallback for unknown method")
    void backendMethodAdapterShouldPreserveObjectDynamicFallback() {
        var bodyBuilder = newBodyBuilder(apiWith(List.of(), List.of(nodeClassWithQueueFree())), List.of());
        var receiverVar = new LirVariable("node", new GdObjectType("Node"), bodyBuilder.func());

        var shared = ScopeMethodResolver.resolveInstanceMethod(
                bodyBuilder.classRegistry(),
                receiverVar.type(),
                "missing_method",
                List.of()
        );
        var fallback = assertInstanceOf(ScopeMethodResolver.DynamicFallback.class, shared);
        assertEquals(ScopeMethodResolver.DynamicKind.OBJECT_DYNAMIC, fallback.dynamicKind());
        assertEquals(ScopeMethodResolver.DynamicFallbackReason.METHOD_MISSING, fallback.reason());

        var backendResolved = BackendMethodCallResolver.resolve(bodyBuilder, receiverVar, "missing_method", List.of());
        assertEquals(BackendMethodCallResolver.DispatchMode.OBJECT_DYNAMIC, backendResolved.mode());
    }

    @Test
    @DisplayName("backend method adapter should preserve variant dynamic fallback")
    void backendMethodAdapterShouldPreserveVariantDynamicFallback() {
        var bodyBuilder = newBodyBuilder(emptyApi(), List.of());
        var receiverVar = new LirVariable("value", GdVariantType.VARIANT, bodyBuilder.func());

        var shared = ScopeMethodResolver.resolveInstanceMethod(
                bodyBuilder.classRegistry(),
                receiverVar.type(),
                "callv",
                List.of()
        );
        var fallback = assertInstanceOf(ScopeMethodResolver.DynamicFallback.class, shared);
        assertEquals(ScopeMethodResolver.DynamicKind.VARIANT_DYNAMIC, fallback.dynamicKind());

        var backendResolved = BackendMethodCallResolver.resolve(bodyBuilder, receiverVar, "callv", List.of());
        assertEquals(BackendMethodCallResolver.DispatchMode.VARIANT_DYNAMIC, backendResolved.mode());
    }

    @Test
    @DisplayName("backend method adapter should preserve builtin receiver normalization")
    void backendMethodAdapterShouldPreserveBuiltinReceiverNormalization() {
        var bodyBuilder = newBodyBuilder(apiWith(List.of(arrayBuiltinWithSize()), List.of()), List.of());
        var receiverVar = new LirVariable("array", new GdArrayType(GdStringType.STRING), bodyBuilder.func());

        var shared = ScopeMethodResolver.resolveInstanceMethod(
                bodyBuilder.classRegistry(),
                receiverVar.type(),
                "size",
                List.of()
        );
        var sharedResolved = assertInstanceOf(ScopeMethodResolver.Resolved.class, shared);

        var backendResolved = BackendMethodCallResolver.resolve(bodyBuilder, receiverVar, "size", List.of());
        assertEquals(sharedResolved.method().ownerClass().getName(), backendResolved.ownerClassName());
        assertEquals(BackendMethodCallResolver.DispatchMode.BUILTIN, backendResolved.mode());
    }

    @Test
    @DisplayName("backend method adapter should publish bitfield ABI data as extra parameter metadata")
    void backendMethodAdapterShouldPublishBitfieldAbiDataAsExtraParameterMetadata() {
        var bodyBuilder = newBodyBuilder(apiWith(List.of(), List.of(nodeClassWithBitfieldParam())), List.of());
        var receiverVar = new LirVariable("node", new GdObjectType("Node"), bodyBuilder.func());
        var argVar = new LirVariable("flags", GdIntType.INT, bodyBuilder.func());

        var backendResolved = BackendMethodCallResolver.resolve(bodyBuilder, receiverVar, "set_process_thread_messages", List.of(argVar));
        assertEquals(1, backendResolved.parameters().size());
        var extraData = assertInstanceOf(
                BackendMethodCallResolver.BitfieldPassByRefExtraParamSpecData.class,
                backendResolved.parameters().getFirst().extraParamSpecData()
        );
        assertEquals("godot_Node_ProcessThreadMessages", extraData.cType());
    }

    @Test
    @DisplayName("backend method adapter should preserve hard failure for incompatible arguments")
    void backendMethodAdapterShouldPreserveHardFailureForIncompatibleArguments() {
        var bodyBuilder = newBodyBuilder(apiWith(List.of(), List.of(nodeClassWithAcceptCount())), List.of());
        var receiverVar = new LirVariable("node", new GdObjectType("Node"), bodyBuilder.func());
        var argVar = new LirVariable("text", GdStringType.STRING, bodyBuilder.func());

        var shared = ScopeMethodResolver.resolveInstanceMethod(
                bodyBuilder.classRegistry(),
                receiverVar.type(),
                "accept_count",
                List.of(argVar.type())
        );
        var failed = assertInstanceOf(ScopeMethodResolver.Failed.class, shared);
        assertEquals(ScopeMethodResolver.FailureKind.NO_APPLICABLE_OVERLOAD, failed.kind());

        var ex = assertThrows(
                InvalidInsnException.class,
                () -> BackendMethodCallResolver.resolve(bodyBuilder, receiverVar, "accept_count", List.of(argVar))
        );
        assertTrue(ex.getMessage().contains("No applicable overload"), ex.getMessage());
    }

    @Test
    @DisplayName("backend method adapter should preserve mapped canonical GDCC owner names")
    void backendMethodAdapterShouldPreserveMappedCanonicalGdccOwnerNames() {
        var parentClass = newClass("RuntimeOuter$Shared", "RefCounted");
        var ping = newFunction("ping");
        ping.addParameter(new LirParameterDef("self", new GdObjectType("RuntimeOuter$Shared"), null, ping));
        entry(ping).appendInstruction(new ReturnInsn(null));
        parentClass.addFunction(ping);

        var childClass = newClass("RuntimeOuter$Leaf", "RuntimeOuter$Shared");
        var bodyBuilder = newBodyBuilder(
                emptyApi(),
                List.of(parentClass, childClass),
                Map.of(
                        "RuntimeOuter$Shared", "Shared",
                        "RuntimeOuter$Leaf", "Leaf"
                )
        );
        var receiverVar = new LirVariable("leaf", new GdObjectType("RuntimeOuter$Leaf"), bodyBuilder.func());

        var shared = ScopeMethodResolver.resolveInstanceMethod(
                bodyBuilder.classRegistry(),
                receiverVar.type(),
                "ping",
                List.of()
        );
        var sharedResolved = assertInstanceOf(ScopeMethodResolver.Resolved.class, shared);

        var backendResolved = BackendMethodCallResolver.resolve(bodyBuilder, receiverVar, "ping", List.of());
        assertEquals("RuntimeOuter$Shared", sharedResolved.method().ownerClass().getName());
        assertEquals(sharedResolved.method().ownerClass().getName(), backendResolved.ownerClassName());
        assertEquals(BackendMethodCallResolver.DispatchMode.GDCC, backendResolved.mode());
    }

    @Test
    @DisplayName("backend method adapter should reject _init because constructor routes are not ordinary method calls")
    void backendMethodAdapterShouldRejectInitConstructorRoute() {
        var workerClass = newClass("Worker", "RefCounted");
        var init = newFunction("_init");
        init.addParameter(new LirParameterDef("self", new GdObjectType("Worker"), null, init));
        init.addParameter(new LirParameterDef("value", GdIntType.INT, null, init));
        entry(init).appendInstruction(new ReturnInsn(null));
        workerClass.addFunction(init);

        var bodyBuilder = newBodyBuilder(emptyApi(), List.of(workerClass));
        var receiverVar = new LirVariable("worker", new GdObjectType("Worker"), bodyBuilder.func());
        var argVar = new LirVariable("value", GdIntType.INT, bodyBuilder.func());

        var ex = assertThrows(
                InvalidInsnException.class,
                () -> BackendMethodCallResolver.resolve(bodyBuilder, receiverVar, "_init", List.of(argVar))
        );
        assertTrue(ex.getMessage().contains("_init"), ex.getMessage());
    }

    private static CBodyBuilder newBodyBuilder(ExtensionAPI api, List<LirClassDef> gdccClasses) {
        return newBodyBuilder(api, gdccClasses, Map.of());
    }

    private static CBodyBuilder newBodyBuilder(
            ExtensionAPI api,
            List<LirClassDef> gdccClasses,
            Map<String, String> sourceNameOverrides
    ) {
        var classRegistry = new ClassRegistry(api);
        for (var gdccClass : gdccClasses) {
            classRegistry.addGdccClass(gdccClass, sourceNameOverrides.get(gdccClass.getName()));
        }

        ProjectInfo projectInfo = new ProjectInfo("TestProject", GodotVersion.V451, Path.of(".")) {
        };
        var context = new CodegenContext(projectInfo, classRegistry);
        var helper = new CGenHelper(context, gdccClasses);

        var ownerClass = gdccClasses.isEmpty()
                ? new LirClassDef("HostClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of())
                : gdccClasses.getFirst();

        var func = new LirFunctionDef("test_func");
        func.setReturnType(GdVoidType.VOID);
        return new CBodyBuilder(helper, ownerClass, func);
    }

    private static LirClassDef newClass(String name, String superName) {
        return new LirClassDef(name, superName, false, false, Map.of(), List.of(), List.of(), List.of());
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

    private static ExtensionAPI apiWith(List<ExtensionBuiltinClass> builtinClasses,
                                        List<ExtensionGdClass> gdClasses) {
        return new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), builtinClasses, gdClasses, List.of(), List.of());
    }

    private static ExtensionAPI emptyApi() {
        return apiWith(List.of(), List.of());
    }

    private static ExtensionBuiltinClass arrayBuiltinWithSize() {
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

    private static ExtensionGdClass nodeClassWithQueueFree() {
        var queueFree = new ExtensionGdClass.ClassMethod(
                "queue_free",
                false,
                false,
                false,
                false,
                0L,
                List.of(),
                new ExtensionGdClass.ClassMethod.ClassMethodReturn("void"),
                List.of()
        );
        return new ExtensionGdClass(
                "Node",
                false,
                true,
                "Object",
                "core",
                List.of(),
                List.of(queueFree),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private static ExtensionGdClass nodeClassWithAcceptCount() {
        var method = new ExtensionGdClass.ClassMethod(
                "accept_count",
                false,
                false,
                false,
                false,
                0L,
                List.of(),
                new ExtensionGdClass.ClassMethod.ClassMethodReturn("void"),
                List.of(new ExtensionFunctionArgument("count", "int", null, null))
        );
        return new ExtensionGdClass(
                "Node",
                false,
                true,
                "Object",
                "core",
                List.of(),
                List.of(method),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private static ExtensionGdClass nodeClassWithBitfieldParam() {
        var method = new ExtensionGdClass.ClassMethod(
                "set_process_thread_messages",
                false,
                false,
                false,
                false,
                0L,
                List.of(),
                new ExtensionGdClass.ClassMethod.ClassMethodReturn("void"),
                List.of(new ExtensionFunctionArgument("flags", "bitfield::Node.ProcessThreadMessages", null, null))
        );
        return new ExtensionGdClass(
                "Node",
                false,
                true,
                "Object",
                "core",
                List.of(),
                List.of(method),
                List.of(),
                List.of(),
                List.of()
        );
    }
}
