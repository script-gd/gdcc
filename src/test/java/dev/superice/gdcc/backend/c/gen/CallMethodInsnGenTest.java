package dev.superice.gdcc.backend.c.gen;

import dev.superice.gdcc.backend.CodegenContext;
import dev.superice.gdcc.backend.ProjectInfo;
import dev.superice.gdcc.enums.GodotVersion;
import dev.superice.gdcc.exception.InvalidInsnException;
import dev.superice.gdcc.gdextension.ExtensionAPI;
import dev.superice.gdcc.gdextension.ExtensionBuiltinClass;
import dev.superice.gdcc.gdextension.ExtensionFunctionArgument;
import dev.superice.gdcc.gdextension.ExtensionGdClass;
import dev.superice.gdcc.lir.LirBasicBlock;
import dev.superice.gdcc.lir.LirClassDef;
import dev.superice.gdcc.lir.LirFunctionDef;
import dev.superice.gdcc.lir.LirInstruction;
import dev.superice.gdcc.lir.LirModule;
import dev.superice.gdcc.lir.LirParameterDef;
import dev.superice.gdcc.lir.insn.CallMethodInsn;
import dev.superice.gdcc.lir.insn.ReturnInsn;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.type.GdArrayType;
import dev.superice.gdcc.type.GdFloatType;
import dev.superice.gdcc.type.GdFloatVectorType;
import dev.superice.gdcc.type.GdIntType;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdStringType;
import dev.superice.gdcc.type.GdVariantType;
import dev.superice.gdcc.type.GdVoidType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CallMethodInsnGenTest {
    @Test
    @DisplayName("CALL_METHOD should emit builtin static dispatch for Vector3.rotated")
    void callMethodBuiltinShouldEmitStaticDispatch() {
        var clazz = newClass("Worker");
        var func = newFunction("call_rotated");
        func.createAndAddVariable("vector", GdFloatVectorType.VECTOR3);
        func.createAndAddVariable("axis", GdFloatVectorType.VECTOR3);
        func.createAndAddVariable("angle", GdFloatType.FLOAT);
        func.createAndAddVariable("ret", GdFloatVectorType.VECTOR3);

        entry(func).instructions().add(new CallMethodInsn(
                "ret",
                "rotated",
                "vector",
                List.of(
                        new LirInstruction.VariableOperand("axis"),
                        new LirInstruction.VariableOperand("angle")
                )
        ));
        clazz.addFunction(func);

        var body = generateBody(clazz, func, newApi(List.of(vector3Builtin()), List.of()), List.of(clazz));
        assertTrue(body.contains("$ret = godot_Vector3_rotated(&$vector, &$axis, $angle);"), body);
    }

    @Test
    @DisplayName("CALL_METHOD should emit engine static dispatch for Node.queue_free")
    void callMethodEngineShouldEmitStaticDispatch() {
        var clazz = newClass("Worker");
        var func = newFunction("call_queue_free");
        func.createAndAddVariable("node", new GdObjectType("Node"));

        entry(func).instructions().add(new CallMethodInsn(
                null,
                "queue_free",
                "node",
                List.of()
        ));
        clazz.addFunction(func);

        var body = generateBody(clazz, func, newApi(List.of(), List.of(nodeClassWithQueueFree())), List.of(clazz));
        assertTrue(body.contains("godot_Node_queue_free($node);"), body);
    }

    @Test
    @DisplayName("CALL_METHOD should emit GDCC static dispatch between known GDCC types")
    void callMethodGdccShouldEmitStaticDispatch() {
        var workerClass = newClass("Worker");
        var pingFunc = newFunction("ping");
        pingFunc.addParameter(new LirParameterDef("self", new GdObjectType("Worker"), null, pingFunc));
        entry(pingFunc).instructions().add(new dev.superice.gdcc.lir.insn.ReturnInsn(null));
        workerClass.addFunction(pingFunc);

        var hostClass = newClass("Host");
        var caller = newFunction("run");
        caller.createAndAddVariable("worker", new GdObjectType("Worker"));
        entry(caller).instructions().add(new CallMethodInsn(
                null,
                "ping",
                "worker",
                List.of()
        ));
        hostClass.addFunction(caller);

        var body = generateBody(hostClass, caller, newApi(List.of(), List.of()), List.of(hostClass, workerClass));
        assertTrue(body.contains("Worker_ping($worker);"), body);
    }

    @Test
    @DisplayName("CALL_METHOD should reject known object type with unknown method")
    void callMethodKnownTypeUnknownMethodShouldFailFast() {
        var clazz = newClass("Worker");
        var func = newFunction("call_unknown");
        func.createAndAddVariable("node", new GdObjectType("Node"));
        entry(func).instructions().add(new CallMethodInsn(null, "missing_method", "node", List.of()));
        clazz.addFunction(func);

        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(clazz, func, newApi(List.of(), List.of(nodeClassWithQueueFree())), List.of(clazz))
        );
        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("Method 'missing_method' not found"), ex.getMessage());
    }

    @Test
    @DisplayName("CALL_METHOD should allow static method call and print warning")
    void callMethodStaticShouldEmitWarningAndGenerateCall() {
        var clazz = newClass("Worker");
        var func = newFunction("call_static");
        func.createAndAddVariable("node", new GdObjectType("Node"));
        func.createAndAddVariable("ret", new GdObjectType("Node"));
        entry(func).instructions().add(new CallMethodInsn("ret", "make", "node", List.of()));
        clazz.addFunction(func);

        var outputBuffer = new ByteArrayOutputStream();
        var capture = new PrintStream(outputBuffer, true, StandardCharsets.UTF_8);
        var originalOut = System.out;
        String body;
        try {
            System.setOut(capture);
            body = generateBody(clazz, func, newApi(List.of(), List.of(nodeClassWithStaticFactory())), List.of(clazz));
        } finally {
            System.setOut(originalOut);
            capture.close();
        }

        var output = outputBuffer.toString(StandardCharsets.UTF_8);
        assertTrue(body.contains("godot_Node_make()"), body);
        assertTrue(output.contains("call_method on receiver"), output);
        assertTrue(output.contains("resolved static method 'Node.make'"), output);
    }

    @Test
    @DisplayName("CALL_METHOD should pick nearest owner overload on inheritance chain")
    void callMethodShouldPickNearestOwnerOverload() {
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

        var hostClass = newClass("Host");
        var caller = newFunction("run");
        caller.createAndAddVariable("target", new GdObjectType("Sub"));
        entry(caller).instructions().add(new CallMethodInsn(
                null,
                "ping",
                "target",
                List.of()
        ));
        hostClass.addFunction(caller);

        var body = generateBody(hostClass, caller, newApi(List.of(), List.of()), List.of(hostClass, subClass, baseClass));
        assertTrue(body.contains("Sub_ping($target);"), body);
        assertFalse(body.contains("Base_ping($target);"), body);
    }

    @Test
    @DisplayName("CALL_METHOD should prefer fixed overload over vararg overload")
    void callMethodShouldPreferFixedOverVararg() {
        var workerClass = newClass("Worker");

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

        var hostClass = newClass("Host");
        var caller = newFunction("run");
        caller.createAndAddVariable("worker", new GdObjectType("Worker"));
        caller.createAndAddVariable("text", GdStringType.STRING);
        caller.createAndAddVariable("count", GdIntType.INT);
        entry(caller).instructions().add(new CallMethodInsn(
                null,
                "echo",
                "worker",
                List.of(
                        new LirInstruction.VariableOperand("text"),
                        new LirInstruction.VariableOperand("count")
                )
        ));
        hostClass.addFunction(caller);

        var body = generateBody(hostClass, caller, newApi(List.of(), List.of()), List.of(hostClass, workerClass));
        assertTrue(body.contains("Worker_echo($worker, &$text, $count);"), body);
    }

    @Test
    @DisplayName("CALL_METHOD should report ambiguous overload when candidates are equally specific")
    void callMethodShouldReportAmbiguousOverload() {
        var clazz = newClass("Worker");
        var func = newFunction("call_ambiguous");
        func.createAndAddVariable("node", new GdObjectType("Node"));
        func.createAndAddVariable("arg", new GdObjectType("Node"));
        entry(func).instructions().add(new CallMethodInsn(
                null,
                "mix",
                "node",
                List.of(new LirInstruction.VariableOperand("arg"))
        ));
        clazz.addFunction(func);

        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(clazz, func, newApi(List.of(), List.of(nodeClassWithAmbiguousOverloads())), List.of(clazz))
        );
        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("Ambiguous overload"), ex.getMessage());
    }

    @Test
    @DisplayName("CALL_METHOD should resolve typed Array receiver to Array builtin metadata")
    void callMethodTypedArrayReceiverShouldResolveBuiltinMetadata() {
        var clazz = newClass("Worker");
        var func = newFunction("call_typed_array_size");
        func.createAndAddVariable("values", new GdArrayType(GdStringType.STRING));
        func.createAndAddVariable("ret", GdIntType.INT);
        entry(func).instructions().add(new CallMethodInsn(
                "ret",
                "size",
                "values",
                List.of()
        ));
        clazz.addFunction(func);

        var body = generateBody(clazz, func, newApi(List.of(arrayBuiltinWithSize()), List.of()), List.of(clazz));
        assertTrue(body.contains("godot_Array_size(&$values)"), body);
    }

    @Test
    @DisplayName("CALL_METHOD should reject incompatible fixed argument type")
    void callMethodShouldRejectArgumentTypeMismatch() {
        var clazz = newClass("Worker");
        var func = newFunction("call_rotated_bad_arg");
        func.createAndAddVariable("vector", GdFloatVectorType.VECTOR3);
        func.createAndAddVariable("axis", GdFloatVectorType.VECTOR3);
        func.createAndAddVariable("angle", GdStringType.STRING);
        func.createAndAddVariable("ret", GdFloatVectorType.VECTOR3);
        entry(func).instructions().add(new CallMethodInsn(
                "ret",
                "rotated",
                "vector",
                List.of(
                        new LirInstruction.VariableOperand("axis"),
                        new LirInstruction.VariableOperand("angle")
                )
        ));
        clazz.addFunction(func);

        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(clazz, func, newApi(List.of(vector3Builtin()), List.of()), List.of(clazz))
        );
        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("Cannot assign value of type 'String'"), ex.getMessage());
    }

    @Test
    @DisplayName("CALL_METHOD should reject resultId for void method")
    void callMethodShouldRejectResultForVoidMethod() {
        var clazz = newClass("Worker");
        var func = newFunction("call_queue_free_with_result");
        func.createAndAddVariable("node", new GdObjectType("Node"));
        func.createAndAddVariable("ret", GdVariantType.VARIANT);
        entry(func).instructions().add(new CallMethodInsn("ret", "queue_free", "node", List.of()));
        clazz.addFunction(func);

        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(clazz, func, newApi(List.of(), List.of(nodeClassWithQueueFree())), List.of(clazz))
        );
        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("has no return value"), ex.getMessage());
    }

    @Test
    @DisplayName("CALL_METHOD should reject missing argument variable")
    void callMethodShouldRejectMissingArgumentVariable() {
        var clazz = newClass("Worker");
        var func = newFunction("call_rotated_missing_arg");
        func.createAndAddVariable("vector", GdFloatVectorType.VECTOR3);
        func.createAndAddVariable("ret", GdFloatVectorType.VECTOR3);
        entry(func).instructions().add(new CallMethodInsn(
                "ret",
                "rotated",
                "vector",
                List.of(
                        new LirInstruction.VariableOperand("axis"),
                        new LirInstruction.VariableOperand("angle")
                )
        ));
        clazz.addFunction(func);

        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(clazz, func, newApi(List.of(vector3Builtin()), List.of()), List.of(clazz))
        );
        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("Argument variable ID 'axis' not found"), ex.getMessage());
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

    private ExtensionBuiltinClass vector3Builtin() {
        var rotated = new ExtensionBuiltinClass.ClassMethod(
                "rotated",
                "Vector3",
                false,
                true,
                false,
                false,
                0L,
                List.of(
                        new ExtensionFunctionArgument("axis", "Vector3", null, null),
                        new ExtensionFunctionArgument("angle", "float", null, null)
                ),
                List.of(),
                new ExtensionBuiltinClass.ClassMethod.ReturnValue("Vector3")
        );
        return new ExtensionBuiltinClass(
                "Vector3",
                false,
                List.of(),
                List.of(rotated),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private ExtensionGdClass nodeClassWithQueueFree() {
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

    private ExtensionGdClass nodeClassWithStaticFactory() {
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

    private ExtensionGdClass nodeClassWithAmbiguousOverloads() {
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

    private ExtensionBuiltinClass arrayBuiltinWithSize() {
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
}
