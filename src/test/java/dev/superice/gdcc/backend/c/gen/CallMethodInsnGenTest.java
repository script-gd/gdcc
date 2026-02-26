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
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.type.GdFloatType;
import dev.superice.gdcc.type.GdFloatVectorType;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdStringType;
import dev.superice.gdcc.type.GdVariantType;
import dev.superice.gdcc.type.GdVoidType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
    @DisplayName("CALL_METHOD should reject static method call")
    void callMethodShouldRejectStaticMethod() {
        var clazz = newClass("Worker");
        var func = newFunction("call_static");
        func.createAndAddVariable("node", new GdObjectType("Node"));
        entry(func).instructions().add(new CallMethodInsn(null, "make", "node", List.of()));
        clazz.addFunction(func);

        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(clazz, func, newApi(List.of(), List.of(nodeClassWithStaticFactory())), List.of(clazz))
        );
        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("is static and cannot be called"), ex.getMessage());
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
        return new LirClassDef(name, "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
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
}
