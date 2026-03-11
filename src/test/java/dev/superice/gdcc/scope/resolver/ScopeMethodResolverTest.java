package dev.superice.gdcc.scope.resolver;

import dev.superice.gdcc.gdextension.ExtensionAPI;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.scope.ScopeOwnerKind;
import dev.superice.gdcc.gdextension.ExtensionBuiltinClass;
import dev.superice.gdcc.gdextension.ExtensionFunctionArgument;
import dev.superice.gdcc.gdextension.ExtensionGdClass;
import dev.superice.gdcc.lir.LirBasicBlock;
import dev.superice.gdcc.lir.LirClassDef;
import dev.superice.gdcc.lir.LirFunctionDef;
import dev.superice.gdcc.lir.LirParameterDef;
import dev.superice.gdcc.lir.insn.ReturnInsn;
import dev.superice.gdcc.type.GdArrayType;
import dev.superice.gdcc.type.GdIntType;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdStringType;
import dev.superice.gdcc.type.GdVariantType;
import dev.superice.gdcc.type.GdVoidType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScopeMethodResolverTest {
    @Test
    @DisplayName("shared method resolver should pick nearest owner on inheritance chain")
    void resolveInstanceMethodShouldPickNearestOwner() {
        var baseClass = newClass("Base", "RefCounted");
        var basePing = newFunction("ping");
        basePing.addParameter(new LirParameterDef("self", new GdObjectType("Base"), null, basePing));
        entry(basePing).instructions().add(new ReturnInsn(null));
        baseClass.addFunction(basePing);

        var subClass = newClass("Sub", "Base");
        var subPing = newFunction("ping");
        subPing.addParameter(new LirParameterDef("self", new GdObjectType("Sub"), null, subPing));
        entry(subPing).instructions().add(new ReturnInsn(null));
        subClass.addFunction(subPing);

        var registry = newRegistry(emptyApi(), List.of(baseClass, subClass));
        var result = ScopeMethodResolver.resolveInstanceMethod(
                registry,
                new GdObjectType("Sub"),
                "ping",
                List.of()
        );

        var resolved = assertInstanceOf(ScopeMethodResolver.Resolved.class, result);
        assertEquals("Sub", resolved.method().ownerClass().getName());
        assertEquals(0, resolved.method().ownerDistance());
    }

    @Test
    @DisplayName("shared method resolver should accept default arguments")
    void resolveInstanceMethodShouldAcceptDefaultArguments() {
        var registry = newRegistry(apiWith(List.of(stringBuiltinWithSubstrDefault()), List.of()), List.of());

        var result = ScopeMethodResolver.resolveInstanceMethod(
                registry,
                GdStringType.STRING,
                "substr",
                List.of(GdIntType.INT)
        );

        var resolved = assertInstanceOf(ScopeMethodResolver.Resolved.class, result);
        assertEquals(ScopeOwnerKind.BUILTIN, resolved.method().ownerKind());
        assertEquals(2, resolved.method().parameters().size());
        assertTrue(resolved.method().parameters().get(1).hasDefaultValue());
    }

    @Test
    @DisplayName("shared method resolver should prefer fixed overload over vararg")
    void resolveInstanceMethodShouldPreferFixedOverVararg() {
        var workerClass = newClass("Worker", "RefCounted");

        var fixed = newFunction("echo");
        fixed.addParameter(new LirParameterDef("self", new GdObjectType("Worker"), null, fixed));
        fixed.addParameter(new LirParameterDef("text", GdStringType.STRING, null, fixed));
        fixed.addParameter(new LirParameterDef("count", GdIntType.INT, null, fixed));
        entry(fixed).instructions().add(new ReturnInsn(null));
        workerClass.addFunction(fixed);

        var vararg = newFunction("echo");
        vararg.addParameter(new LirParameterDef("self", new GdObjectType("Worker"), null, vararg));
        vararg.addParameter(new LirParameterDef("text", GdStringType.STRING, null, vararg));
        vararg.setVararg(true);
        entry(vararg).instructions().add(new ReturnInsn(null));
        workerClass.addFunction(vararg);

        var registry = newRegistry(emptyApi(), List.of(workerClass));
        var result = ScopeMethodResolver.resolveInstanceMethod(
                registry,
                new GdObjectType("Worker"),
                "echo",
                List.of(GdStringType.STRING, GdIntType.INT)
        );

        var resolved = assertInstanceOf(ScopeMethodResolver.Resolved.class, result);
        assertEquals("Worker", resolved.method().ownerClass().getName());
        assertEquals(2, resolved.method().parameters().size());
        assertEquals(false, resolved.method().isVararg());
    }

    @Test
    @DisplayName("shared method resolver should fallback to object dynamic on ambiguous object overload")
    void resolveInstanceMethodShouldFallbackToObjectDynamicOnAmbiguousObjectOverload() {
        var registry = newRegistry(apiWith(List.of(), List.of(nodeClassWithAmbiguousOverloads())), List.of());

        var result = ScopeMethodResolver.resolveInstanceMethod(
                registry,
                new GdObjectType("Node"),
                "mix",
                List.of(new GdObjectType("Node"))
        );

        var fallback = assertInstanceOf(ScopeMethodResolver.DynamicFallback.class, result);
        assertEquals(ScopeMethodResolver.DynamicKind.OBJECT_DYNAMIC, fallback.dynamicKind());
        assertEquals(ScopeMethodResolver.DynamicFallbackReason.AMBIGUOUS_OVERLOAD, fallback.reason());
    }

    @Test
    @DisplayName("shared method resolver should fail hard on ambiguous builtin overload")
    void resolveInstanceMethodShouldFailHardOnAmbiguousBuiltinOverload() {
        var registry = newRegistry(apiWith(List.of(arrayBuiltinWithAmbiguousOverloads()), List.of(simpleNodeClass())), List.of());

        var result = ScopeMethodResolver.resolveInstanceMethod(
                registry,
                new GdArrayType(GdVariantType.VARIANT),
                "mix",
                List.of(new GdObjectType("Node"))
        );

        var failed = assertInstanceOf(ScopeMethodResolver.Failed.class, result);
        assertEquals(ScopeMethodResolver.FailureKind.AMBIGUOUS_OVERLOAD, failed.kind());
        assertTrue(failed.message().contains("Ambiguous overload"), failed.message());
    }

    @Test
    @DisplayName("shared method resolver should normalize typed Array receiver to Array builtin metadata")
    void resolveInstanceMethodShouldNormalizeTypedArrayReceiver() {
        var registry = newRegistry(apiWith(List.of(arrayBuiltinWithSize()), List.of()), List.of());

        var result = ScopeMethodResolver.resolveInstanceMethod(
                registry,
                new GdArrayType(GdStringType.STRING),
                "size",
                List.of()
        );

        var resolved = assertInstanceOf(ScopeMethodResolver.Resolved.class, result);
        assertEquals(ScopeOwnerKind.BUILTIN, resolved.method().ownerKind());
        assertEquals("Array", resolved.method().ownerClass().getName());
    }

    @Test
    @DisplayName("shared method resolver should reject incompatible fixed arguments")
    void resolveInstanceMethodShouldRejectIncompatibleFixedArguments() {
        var registry = newRegistry(apiWith(List.of(), List.of(nodeClassWithAcceptCount())), List.of());

        var result = ScopeMethodResolver.resolveInstanceMethod(
                registry,
                new GdObjectType("Node"),
                "accept_count",
                List.of(GdStringType.STRING)
        );

        var failed = assertInstanceOf(ScopeMethodResolver.Failed.class, result);
        assertEquals(ScopeMethodResolver.FailureKind.NO_APPLICABLE_OVERLOAD, failed.kind());
        assertTrue(failed.message().contains("Cannot assign value of type 'String'"), failed.message());
    }

    @Test
    @DisplayName("shared method resolver should fallback to variant dynamic for Variant receiver")
    void resolveInstanceMethodShouldFallbackToVariantDynamicForVariantReceiver() {
        var registry = newRegistry(emptyApi(), List.of());

        var result = ScopeMethodResolver.resolveInstanceMethod(
                registry,
                GdVariantType.VARIANT,
                "callv",
                List.of()
        );

        var fallback = assertInstanceOf(ScopeMethodResolver.DynamicFallback.class, result);
        assertEquals(ScopeMethodResolver.DynamicKind.VARIANT_DYNAMIC, fallback.dynamicKind());
        assertEquals(ScopeMethodResolver.DynamicFallbackReason.VARIANT_RECEIVER, fallback.reason());
    }

    @Test
    @DisplayName("shared method resolver should resolve static type-meta receiver")
    void resolveStaticMethodShouldResolveStaticTypeMetaReceiver() {
        var registry = newRegistry(apiWith(List.of(), List.of(nodeClassWithStaticFactory())), List.of());
        var nodeTypeMeta = registry.resolveTypeMeta("Node");

        var result = ScopeMethodResolver.resolveStaticMethod(registry, nodeTypeMeta, "make", List.of());
        var resolved = assertInstanceOf(ScopeMethodResolver.Resolved.class, result);

        assertEquals(ScopeOwnerKind.ENGINE, resolved.method().ownerKind());
        assertEquals("Node", resolved.method().ownerClass().getName());
        assertTrue(resolved.method().isStatic());
    }

    @Test
    @DisplayName("shared method resolver should keep constructor route out of static method lookup")
    void resolveStaticMethodShouldRejectConstructorRoute() {
        var registry = newRegistry(apiWith(List.of(), List.of(nodeClassWithStaticFactory())), List.of());
        var nodeTypeMeta = registry.resolveTypeMeta("Node");

        var result = ScopeMethodResolver.resolveStaticMethod(registry, nodeTypeMeta, "new", List.of());
        var failed = assertInstanceOf(ScopeMethodResolver.Failed.class, result);

        assertEquals(ScopeMethodResolver.FailureKind.CONSTRUCTOR_ROUTE_UNSUPPORTED, failed.kind());
        assertTrue(failed.message().contains("constructor resolution"), failed.message());
    }

    @Test
    @DisplayName("shared method resolver should resolve canonical container metadata against registry")
    void resolveInstanceMethodShouldResolveCanonicalContainerMetadataAgainstRegistry() {
        var registry = newRegistry(
                apiWith(
                        List.of(),
                        List.of(
                                simpleEngineClass("PipelineConstant", "RefCounted"),
                                engineClassWithCanonicalContainerMetadata(
                                        "PipelineOwner",
                                        "accept_constants",
                                        "Array[PipelineConstant]",
                                        "Array[PipelineConstant]"
                                )
                        )
                ),
                List.of()
        );

        var result = ScopeMethodResolver.resolveInstanceMethod(
                registry,
                new GdObjectType("PipelineOwner"),
                "accept_constants",
                List.of(new GdArrayType(new GdObjectType("PipelineConstant")))
        );

        var resolved = assertInstanceOf(ScopeMethodResolver.Resolved.class, result);
        assertEquals(
                new GdArrayType(new GdObjectType("PipelineConstant")),
                resolved.method().parameters().getFirst().type()
        );
        assertEquals(
                new GdArrayType(new GdObjectType("PipelineConstant")),
                resolved.method().returnType()
        );
    }

    @Test
    @DisplayName("shared method resolver should reject unknown canonical container parameter metadata")
    void resolveInstanceMethodShouldRejectUnknownCanonicalContainerParameterMetadata() {
        var registry = newRegistry(
                apiWith(
                        List.of(),
                        List.of(
                                engineClassWithCanonicalContainerMetadata(
                                        "PipelineOwner",
                                        "accept_constants",
                                        "void",
                                        "Array[MissingConstant]"
                                )
                        )
                ),
                List.of()
        );

        var result = ScopeMethodResolver.resolveInstanceMethod(
                registry,
                new GdObjectType("PipelineOwner"),
                "accept_constants",
                List.of(new GdArrayType(new GdObjectType("MissingConstant")))
        );

        var failed = assertInstanceOf(ScopeMethodResolver.Failed.class, result);
        assertEquals(ScopeMethodResolver.FailureKind.MALFORMED_METADATA, failed.kind());
        assertTrue(failed.message().contains("Array[MissingConstant]"), failed.message());
        assertTrue(failed.message().contains("method parameter #1"), failed.message());
    }

    @Test
    @DisplayName("shared method resolver should reject unknown canonical container return metadata")
    void resolveInstanceMethodShouldRejectUnknownCanonicalContainerReturnMetadata() {
        var registry = newRegistry(
                apiWith(
                        List.of(),
                        List.of(
                                engineClassWithCanonicalContainerMetadata(
                                        "PipelineOwner",
                                        "load_constants",
                                        "Array[MissingConstant]",
                                        null
                                )
                        )
                ),
                List.of()
        );

        var result = ScopeMethodResolver.resolveInstanceMethod(
                registry,
                new GdObjectType("PipelineOwner"),
                "load_constants",
                List.of()
        );

        var failed = assertInstanceOf(ScopeMethodResolver.Failed.class, result);
        assertEquals(ScopeMethodResolver.FailureKind.MALFORMED_METADATA, failed.kind());
        assertTrue(failed.message().contains("Array[MissingConstant]"), failed.message());
        assertTrue(failed.message().contains("return type"), failed.message());
    }

    private static ClassRegistry newRegistry(ExtensionAPI api, List<LirClassDef> gdccClasses) {
        var registry = new ClassRegistry(api);
        for (var gdccClass : gdccClasses) {
            registry.addGdccClass(gdccClass);
        }
        return registry;
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

    private static ExtensionBuiltinClass stringBuiltinWithSubstrDefault() {
        var substr = new ExtensionBuiltinClass.ClassMethod(
                "substr",
                "String",
                false,
                true,
                false,
                false,
                0L,
                List.of(
                        new ExtensionFunctionArgument("from", "int", null, null),
                        new ExtensionFunctionArgument("len", "int", "-1", null)
                ),
                List.of(),
                new ExtensionBuiltinClass.ClassMethod.ReturnValue("String")
        );
        return new ExtensionBuiltinClass(
                "String",
                false,
                List.of(),
                List.of(substr),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
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

    private static ExtensionBuiltinClass arrayBuiltinWithAmbiguousOverloads() {
        var mixNode = new ExtensionBuiltinClass.ClassMethod(
                "mix",
                "void",
                false,
                true,
                false,
                false,
                0L,
                List.of(new ExtensionFunctionArgument("value", "Node", null, null)),
                List.of(),
                new ExtensionBuiltinClass.ClassMethod.ReturnValue("void")
        );
        var mixObject = new ExtensionBuiltinClass.ClassMethod(
                "mix",
                "void",
                false,
                true,
                false,
                false,
                0L,
                List.of(new ExtensionFunctionArgument("value", "Object", null, null)),
                List.of(),
                new ExtensionBuiltinClass.ClassMethod.ReturnValue("void")
        );
        return new ExtensionBuiltinClass(
                "Array",
                false,
                List.of(),
                List.of(mixNode, mixObject),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private static ExtensionGdClass nodeClassWithAmbiguousOverloads() {
        var mixNode = new ExtensionGdClass.ClassMethod(
                "mix",
                false,
                false,
                false,
                false,
                0L,
                List.of(),
                new ExtensionGdClass.ClassMethod.ClassMethodReturn("void"),
                List.of(new ExtensionFunctionArgument("value", "Node", null, null))
        );
        var mixObject = new ExtensionGdClass.ClassMethod(
                "mix",
                false,
                false,
                false,
                false,
                0L,
                List.of(),
                new ExtensionGdClass.ClassMethod.ClassMethodReturn("void"),
                List.of(new ExtensionFunctionArgument("value", "Object", null, null))
        );
        return new ExtensionGdClass(
                "Node",
                false,
                true,
                "Object",
                "core",
                List.of(),
                List.of(mixNode, mixObject),
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

    private static ExtensionGdClass simpleNodeClass() {
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

    private static ExtensionGdClass nodeClassWithStaticFactory() {
        var make = new ExtensionGdClass.ClassMethod(
                "make",
                false,
                false,
                true,
                false,
                0L,
                List.of(),
                new ExtensionGdClass.ClassMethod.ClassMethodReturn("Node"),
                List.of()
        );
        return new ExtensionGdClass(
                "Node",
                false,
                true,
                "Object",
                "core",
                List.of(),
                List.of(make),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private static ExtensionGdClass engineClassWithCanonicalContainerMetadata(String className,
                                                                              String methodName,
                                                                              String returnType,
                                                                              String parameterType) {
        var args = parameterType == null
                ? List.<ExtensionFunctionArgument>of()
                : List.of(new ExtensionFunctionArgument("values", parameterType, null, null));
        var method = new ExtensionGdClass.ClassMethod(
                methodName,
                false,
                false,
                false,
                false,
                0L,
                List.of(),
                new ExtensionGdClass.ClassMethod.ClassMethodReturn(returnType),
                args
        );
        return new ExtensionGdClass(
                className,
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

    private static ExtensionGdClass simpleEngineClass(String name, String superName) {
        return new ExtensionGdClass(
                name,
                false,
                true,
                superName,
                "core",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
    }
}
