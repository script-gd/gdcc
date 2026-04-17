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
import dev.superice.gdcc.lir.insn.LineNumberInsn;
import dev.superice.gdcc.lir.insn.ReturnInsn;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.type.GdArrayType;
import dev.superice.gdcc.type.GdFloatType;
import dev.superice.gdcc.type.GdFloatVectorType;
import dev.superice.gdcc.type.GdIntType;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdPackedNumericArrayType;
import dev.superice.gdcc.type.GdPackedVectorArrayType;
import dev.superice.gdcc.type.GdStringNameType;
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

        entry(func).appendInstruction(new CallMethodInsn(
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

        entry(func).appendInstruction(new CallMethodInsn(
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
    @DisplayName("CALL_METHOD should convert GDCC self pointer when static dispatch resolves to engine owner")
    void callMethodGdccSelfToEngineOwnerShouldConvertReceiverPointer() {
        var clazz = newClass("GDMyNode", "Node");
        var func = newFunction("dispose_self");
        func.addParameter(new LirParameterDef("self", new GdObjectType("GDMyNode"), null, func));

        entry(func).appendInstruction(new CallMethodInsn(
                null,
                "queue_free",
                "self",
                List.of()
        ));
        clazz.addFunction(func);

        var body = generateBody(clazz, func, newApi(List.of(), List.of(nodeClassWithQueueFree())), List.of(clazz));
        assertTrue(body.contains("godot_Node_queue_free((godot_Node*)gdcc_object_to_godot_object_ptr($self, GDMyNode_object_ptr));"), body);
        assertFalse(body.contains("godot_Node_queue_free((godot_Node*)$self);"), body);
    }

    @Test
    @DisplayName("CALL_METHOD should emit GDCC static dispatch between known GDCC types")
    void callMethodGdccShouldEmitStaticDispatch() {
        var workerClass = newClass("Worker");
        var pingFunc = newFunction("ping");
        pingFunc.addParameter(new LirParameterDef("self", new GdObjectType("Worker"), null, pingFunc));
        entry(pingFunc).appendInstruction(new dev.superice.gdcc.lir.insn.ReturnInsn(null));
        workerClass.addFunction(pingFunc);

        var hostClass = newClass("Host");
        var caller = newFunction("run");
        caller.createAndAddVariable("worker", new GdObjectType("Worker"));
        entry(caller).appendInstruction(new CallMethodInsn(
                null,
                "ping",
                "worker",
                List.of()
        ));
        hostClass.addFunction(caller);

        var body = generateBody(hostClass, caller, newApi(List.of(), List.of()), List.of(hostClass, workerClass));
        assertTrue(body.contains("Worker_ping($worker);"), body);
        assertFalse(body.contains("godot_Object_call("), body);
        assertFalse(body.contains("godot_Variant_call("), body);
    }

    @Test
    @DisplayName("CALL_METHOD should upcast GDCC receiver to parent owner via _super chain without bare cast")
    void callMethodGdccReceiverToParentOwnerShouldUseSuperChainUpcast() {
        var baseClass = newClass("BaseWorker");
        var basePing = newFunction("base_ping");
        basePing.addParameter(new LirParameterDef("self", new GdObjectType("BaseWorker"), null, basePing));
        entry(basePing).appendInstruction(new ReturnInsn(null));
        baseClass.addFunction(basePing);

        var childClass = newClass("ChildWorker", "BaseWorker");
        var childOnly = newFunction("child_only");
        childOnly.addParameter(new LirParameterDef("self", new GdObjectType("ChildWorker"), null, childOnly));
        entry(childOnly).appendInstruction(new ReturnInsn(null));
        childClass.addFunction(childOnly);

        var hostClass = newClass("Host");
        var caller = newFunction("run");
        caller.createAndAddVariable("child", new GdObjectType("ChildWorker"));
        entry(caller).appendInstruction(new CallMethodInsn(
                null,
                "base_ping",
                "child",
                List.of()
        ));
        hostClass.addFunction(caller);

        var body = generateBody(hostClass, caller, newApi(List.of(), List.of()), List.of(hostClass, childClass, baseClass));
        assertTrue(body.contains("BaseWorker_base_ping(&($child->_super));"), body);
        assertFalse(body.contains("BaseWorker_base_ping((BaseWorker*)$child);"), body);
        assertFalse(body.contains("godot_Object_call("), body);
    }

    @Test
    @DisplayName("CALL_METHOD should fallback to OBJECT_DYNAMIC for known engine type with unknown method")
    void callMethodKnownEngineTypeUnknownMethodShouldFallbackToObjectDynamic() {
        var clazz = newClass("Worker");
        var func = newFunction("call_unknown");
        func.createAndAddVariable("node", new GdObjectType("Node"));
        entry(func).appendInstruction(new CallMethodInsn(null, "missing_method", "node", List.of()));
        clazz.addFunction(func);

        var body = generateBody(clazz, func, newApi(List.of(), List.of(nodeClassWithQueueFree())), List.of(clazz));
        assertTrue(body.contains("godot_Object_call($node, GD_STATIC_SN(u8\"missing_method\")"), body);
        assertFalse(body.contains("godot_Node_missing_method("), body);
    }

    @Test
    @DisplayName("CALL_METHOD should fallback to OBJECT_DYNAMIC for known GDCC type with unknown method")
    void callMethodKnownGdccTypeUnknownMethodShouldFallbackToObjectDynamic() {
        var workerClass = newClass("Worker");
        var pingFunc = newFunction("ping");
        pingFunc.addParameter(new LirParameterDef("self", new GdObjectType("Worker"), null, pingFunc));
        entry(pingFunc).appendInstruction(new ReturnInsn(null));
        workerClass.addFunction(pingFunc);

        var hostClass = newClass("Host");
        var caller = newFunction("run");
        caller.createAndAddVariable("worker", new GdObjectType("Worker"));
        entry(caller).appendInstruction(new CallMethodInsn(
                null,
                "missing_method",
                "worker",
                List.of()
        ));
        hostClass.addFunction(caller);

        var body = generateBody(hostClass, caller, newApi(List.of(), List.of()), List.of(hostClass, workerClass));
        assertTrue(body.contains("godot_Object_call(gdcc_object_to_godot_object_ptr($worker, Worker_object_ptr), GD_STATIC_SN(u8\"missing_method\")"), body);
        assertFalse(body.contains("Worker_missing_method("), body);
    }

    @Test
    @DisplayName("CALL_METHOD should emit OBJECT_DYNAMIC for GDCC base-typed receiver with pointer conversion")
    void callMethodGdccBaseTypedReceiverShouldFallbackToObjectDynamicWithPointerConversion() {
        var baseClass = newClass("BaseWorker");
        var basePing = newFunction("ping");
        basePing.addParameter(new LirParameterDef("self", new GdObjectType("BaseWorker"), null, basePing));
        entry(basePing).appendInstruction(new ReturnInsn(null));
        baseClass.addFunction(basePing);

        var childClass = newClass("ChildWorker", "BaseWorker");
        var childOnly = newFunction("child_only_echo");
        childOnly.setReturnType(GdIntType.INT);
        childOnly.addParameter(new LirParameterDef("self", new GdObjectType("ChildWorker"), null, childOnly));
        childOnly.addParameter(new LirParameterDef("value", GdIntType.INT, null, childOnly));
        entry(childOnly).appendInstruction(new ReturnInsn("value"));
        childClass.addFunction(childOnly);

        var hostClass = newClass("Host");
        var caller = newFunction("run");
        caller.createAndAddVariable("baseRef", new GdObjectType("BaseWorker"));
        caller.createAndAddVariable("value", GdIntType.INT);
        caller.createAndAddVariable("ret", GdIntType.INT);
        entry(caller).appendInstruction(new CallMethodInsn(
                "ret",
                "child_only_echo",
                "baseRef",
                List.of(new LirInstruction.VariableOperand("value"))
        ));
        hostClass.addFunction(caller);

        var body = generateBody(hostClass, caller, newApi(List.of(), List.of()), List.of(hostClass, baseClass, childClass));
        assertTrue(body.contains("godot_Object_call(gdcc_object_to_godot_object_ptr($baseRef, BaseWorker_object_ptr), GD_STATIC_SN(u8\"child_only_echo\")"), body);
        assertFalse(body.contains("ChildWorker_child_only_echo("), body);
        assertFalse(body.contains("godot_Variant_call("), body);
    }

    @Test
    @DisplayName("CALL_METHOD OBJECT_DYNAMIC should pack GDCC object args with helper and keep pointer conversion on receiver")
    void callMethodObjectDynamicShouldPackGdccArgsWithHelperAndPointerConversion() {
        var baseClass = newClass("BaseWorker");
        var basePing = newFunction("ping");
        basePing.addParameter(new LirParameterDef("self", new GdObjectType("BaseWorker"), null, basePing));
        entry(basePing).appendInstruction(new ReturnInsn(null));
        baseClass.addFunction(basePing);

        var childClass = newClass("ChildWorker", "BaseWorker");
        var childOnly = newFunction("child_only_consume_peer");
        childOnly.addParameter(new LirParameterDef("self", new GdObjectType("ChildWorker"), null, childOnly));
        childOnly.addParameter(new LirParameterDef("peer", new GdObjectType("PeerWorker"), null, childOnly));
        entry(childOnly).appendInstruction(new ReturnInsn(null));
        childClass.addFunction(childOnly);

        var peerClass = newClass("PeerWorker");
        var peerPing = newFunction("ping");
        peerPing.addParameter(new LirParameterDef("self", new GdObjectType("PeerWorker"), null, peerPing));
        entry(peerPing).appendInstruction(new ReturnInsn(null));
        peerClass.addFunction(peerPing);

        var hostClass = newClass("Host");
        var caller = newFunction("run");
        caller.createAndAddVariable("baseRef", new GdObjectType("BaseWorker"));
        caller.createAndAddVariable("peer", new GdObjectType("PeerWorker"));
        entry(caller).appendInstruction(new CallMethodInsn(
                null,
                "child_only_consume_peer",
                "baseRef",
                List.of(new LirInstruction.VariableOperand("peer"))
        ));
        hostClass.addFunction(caller);

        var body = generateBody(
                hostClass,
                caller,
                newApi(List.of(), List.of()),
                List.of(hostClass, baseClass, childClass, peerClass)
        );
        assertTrue(body.contains("godot_Object_call(gdcc_object_to_godot_object_ptr($baseRef, BaseWorker_object_ptr), GD_STATIC_SN(u8\"child_only_consume_peer\")"), body);
        assertTrue(body.contains("gdcc_new_Variant_with_gdcc_Object($peer)"), body);
        assertFalse(body.contains("godot_new_Variant_with_Object("), body);
        assertFalse(body.contains("godot_Variant_call("), body);
    }

    @Test
    @DisplayName("CALL_METHOD should allow static method call and print warning")
    void callMethodStaticShouldEmitWarningAndGenerateCall() {
        var clazz = newClass("Worker");
        var func = newFunction("call_static");
        func.createAndAddVariable("node", new GdObjectType("Node"));
        func.createAndAddVariable("ret", new GdObjectType("Node"));
        entry(func).appendInstruction(new CallMethodInsn("ret", "make", "node", List.of()));
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
        entry(basePing).appendInstruction(new ReturnInsn(null));
        baseClass.addFunction(basePing);

        var subClass = newClass("Sub", "Base");
        var subPing = newFunction("ping");
        subPing.addParameter(new LirParameterDef("self", new GdObjectType("Sub"), null, subPing));
        entry(subPing).appendInstruction(new ReturnInsn(null));
        subClass.addFunction(subPing);

        var hostClass = newClass("Host");
        var caller = newFunction("run");
        caller.createAndAddVariable("target", new GdObjectType("Sub"));
        entry(caller).appendInstruction(new CallMethodInsn(
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
        entry(fixed).appendInstruction(new ReturnInsn(null));
        workerClass.addFunction(fixed);

        var vararg = newFunction("echo");
        vararg.addParameter(new LirParameterDef("self", new GdObjectType("Worker"), null, vararg));
        vararg.addParameter(new LirParameterDef("text", GdStringType.STRING, null, vararg));
        vararg.setVararg(true);
        entry(vararg).appendInstruction(new ReturnInsn(null));
        workerClass.addFunction(vararg);

        var hostClass = newClass("Host");
        var caller = newFunction("run");
        caller.createAndAddVariable("worker", new GdObjectType("Worker"));
        caller.createAndAddVariable("text", GdStringType.STRING);
        caller.createAndAddVariable("count", GdIntType.INT);
        entry(caller).appendInstruction(new CallMethodInsn(
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
    @DisplayName("CALL_METHOD should fallback to OBJECT_DYNAMIC when overload resolution is ambiguous")
    void callMethodShouldFallbackToObjectDynamicOnAmbiguousOverload() {
        var clazz = newClass("Worker");
        var func = newFunction("call_ambiguous");
        func.createAndAddVariable("node", new GdObjectType("Node"));
        func.createAndAddVariable("arg", new GdObjectType("Node"));
        entry(func).appendInstruction(new CallMethodInsn(
                null,
                "mix",
                "node",
                List.of(new LirInstruction.VariableOperand("arg"))
        ));
        clazz.addFunction(func);

        var body = generateBody(clazz, func, newApi(List.of(), List.of(nodeClassWithAmbiguousOverloads())), List.of(clazz));
        assertTrue(body.contains("godot_Object_call($node, GD_STATIC_SN(u8\"mix\")"), body);
        assertFalse(body.contains("godot_Node_mix("), body);
    }

    @Test
    @DisplayName("CALL_METHOD should resolve typed Array receiver to Array builtin metadata")
    void callMethodTypedArrayReceiverShouldResolveBuiltinMetadata() {
        var clazz = newClass("Worker");
        var func = newFunction("call_typed_array_size");
        func.createAndAddVariable("values", new GdArrayType(GdStringType.STRING));
        func.createAndAddVariable("ret", GdIntType.INT);
        entry(func).appendInstruction(new CallMethodInsn(
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
        entry(func).appendInstruction(new CallMethodInsn(
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
        entry(func).appendInstruction(new CallMethodInsn("ret", "queue_free", "node", List.of()));
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
        entry(func).appendInstruction(new CallMethodInsn(
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

    @Test
    @DisplayName("CALL_METHOD should materialize extension literal defaults")
    void callMethodShouldMaterializeExtensionLiteralDefault() {
        var clazz = newClass("Worker");
        var func = newFunction("call_substr_default");
        func.createAndAddVariable("text", GdStringType.STRING);
        func.createAndAddVariable("from", GdIntType.INT);
        func.createAndAddVariable("ret", GdStringType.STRING);
        entry(func).appendInstruction(new CallMethodInsn(
                "ret",
                "substr",
                "text",
                List.of(new LirInstruction.VariableOperand("from"))
        ));
        clazz.addFunction(func);

        var body = generateBody(clazz, func, newApi(List.of(stringBuiltinWithSubstrDefault()), List.of()), List.of(clazz));
        assertTrue(body.contains("godot_String_substr(&$text, $from, __gdcc_tmp_default_arg_2_"), body);
        assertTrue(body.contains("__gdcc_tmp_default_arg_2_"), body);
    }

    @Test
    @DisplayName("CALL_METHOD should pass provided bitfield arguments through wrapper-compatible pointer casts")
    void callMethodShouldPassProvidedBitfieldArgByPointerCast() {
        var clazz = newClass("Worker");
        var func = newFunction("call_set_process_thread_messages");
        func.createAndAddVariable("node", new GdObjectType("Node"));
        func.createAndAddVariable("flags", GdIntType.INT);
        entry(func).appendInstruction(new CallMethodInsn(
                null,
                "set_process_thread_messages",
                "node",
                List.of(new LirInstruction.VariableOperand("flags"))
        ));
        clazz.addFunction(func);

        var body = generateBody(clazz, func, newApi(List.of(), List.of(nodeClassWithBitfieldDefaultParam())), List.of(clazz));
        assertTrue(body.contains("__gdcc_tmp_bitfield_arg_1_"), body);
        assertTrue(body.contains("= $flags;"), body);
        assertTrue(
                body.contains("godot_Node_set_process_thread_messages($node, (const godot_Node_ProcessThreadMessages *)&__gdcc_tmp_bitfield_arg_1_"),
                body
        );
        assertFalse(body.contains("godot_Node_set_process_thread_messages($node, $flags)"), body);
    }

    @Test
    @DisplayName("CALL_METHOD should materialize bitfield defaults through wrapper-compatible pointer casts")
    void callMethodShouldMaterializeBitfieldDefaultByPointerCast() {
        var clazz = newClass("Worker");
        var func = newFunction("call_set_process_thread_messages_default");
        func.createAndAddVariable("node", new GdObjectType("Node"));
        entry(func).appendInstruction(new CallMethodInsn(
                null,
                "set_process_thread_messages",
                "node",
                List.of()
        ));
        clazz.addFunction(func);

        var body = generateBody(clazz, func, newApi(List.of(), List.of(nodeClassWithBitfieldDefaultParam())), List.of(clazz));
        assertTrue(body.contains("godot_int __gdcc_tmp_default_arg_1_"), body);
        assertTrue(
                body.contains("godot_Node_set_process_thread_messages($node, (const godot_Node_ProcessThreadMessages *)&__gdcc_tmp_default_arg_1_"),
                body
        );
        assertFalse(body.contains("godot_Node_set_process_thread_messages($node, __gdcc_tmp_default_arg_1_"), body);
    }

    @Test
    @DisplayName("CALL_METHOD should materialize GDCC instance default_value_func")
    void callMethodShouldMaterializeGdccInstanceDefaultFunction() {
        var workerClass = newClass("Worker");
        var defaultCount = newFunction("default_count");
        defaultCount.setReturnType(GdIntType.INT);
        defaultCount.addParameter(new LirParameterDef("self", new GdObjectType("Worker"), null, defaultCount));
        workerClass.addFunction(defaultCount);

        var ping = newFunction("ping");
        ping.addParameter(new LirParameterDef("self", new GdObjectType("Worker"), null, ping));
        ping.addParameter(new LirParameterDef("count", GdIntType.INT, "default_count", ping));
        ping.setReturnType(GdVoidType.VOID);
        entry(ping).appendInstruction(new ReturnInsn(null));
        workerClass.addFunction(ping);

        var hostClass = newClass("Host");
        var caller = newFunction("run");
        caller.createAndAddVariable("worker", new GdObjectType("Worker"));
        entry(caller).appendInstruction(new CallMethodInsn(
                null,
                "ping",
                "worker",
                List.of()
        ));
        hostClass.addFunction(caller);

        var body = generateBody(hostClass, caller, newApi(List.of(), List.of()), List.of(hostClass, workerClass));
        assertTrue(body.contains("Worker_default_count($worker)"), body);
        assertTrue(body.contains("Worker_ping($worker, __gdcc_tmp_default_arg_1_"), body);
    }

    @Test
    @DisplayName("CALL_METHOD should materialize GDCC static default_value_func without receiver")
    void callMethodShouldMaterializeGdccStaticDefaultFunction() {
        var workerClass = newClass("Worker");
        var defaultCount = newFunction("default_count_static");
        defaultCount.setStatic(true);
        defaultCount.setReturnType(GdIntType.INT);
        workerClass.addFunction(defaultCount);

        var ping = newFunction("ping");
        ping.addParameter(new LirParameterDef("self", new GdObjectType("Worker"), null, ping));
        ping.addParameter(new LirParameterDef("count", GdIntType.INT, "default_count_static", ping));
        ping.setReturnType(GdVoidType.VOID);
        entry(ping).appendInstruction(new ReturnInsn(null));
        workerClass.addFunction(ping);

        var hostClass = newClass("Host");
        var caller = newFunction("run");
        caller.createAndAddVariable("worker", new GdObjectType("Worker"));
        entry(caller).appendInstruction(new CallMethodInsn(
                null,
                "ping",
                "worker",
                List.of()
        ));
        hostClass.addFunction(caller);

        var body = generateBody(hostClass, caller, newApi(List.of(), List.of()), List.of(hostClass, workerClass));
        assertTrue(body.contains("Worker_default_count_static()"), body);
        assertFalse(body.contains("Worker_default_count_static($worker)"), body);
        assertTrue(body.contains("Worker_ping($worker, __gdcc_tmp_default_arg_1_"), body);
    }

    @Test
    @DisplayName("CALL_METHOD should reject non-Variant vararg arguments")
    void callMethodShouldRejectNonVariantVarargArgument() {
        var clazz = newClass("Worker");
        var func = newFunction("call_vararg_bad_extra");
        func.createAndAddVariable("node", new GdObjectType("Node"));
        func.createAndAddVariable("name", GdStringNameType.STRING_NAME);
        func.createAndAddVariable("extra", GdIntType.INT);
        entry(func).appendInstruction(new CallMethodInsn(
                null,
                "spread",
                "node",
                List.of(
                        new LirInstruction.VariableOperand("name"),
                        new LirInstruction.VariableOperand("extra")
                )
        ));
        clazz.addFunction(func);

        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(clazz, func, newApi(List.of(), List.of(nodeClassWithVarargSpread())), List.of(clazz))
        );
        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("Vararg argument #2"), ex.getMessage());
        assertTrue(ex.getMessage().contains("must be Variant"), ex.getMessage());
    }

    @Test
    @DisplayName("CALL_METHOD should normalize typedarray PackedByteArray parameter to packed array type")
    void callMethodShouldNormalizeTypedarrayPackedByteArrayParameter() {
        var clazz = newClass("Worker");
        var func = newFunction("call_typedarray_packed_param");
        func.createAndAddVariable("array", new GdArrayType(GdStringType.STRING));
        func.createAndAddVariable("bytes", GdPackedNumericArrayType.PACKED_BYTE_ARRAY);
        func.createAndAddVariable("ret", GdIntType.INT);
        entry(func).appendInstruction(new CallMethodInsn(
                "ret",
                "accept_packed",
                "array",
                List.of(new LirInstruction.VariableOperand("bytes"))
        ));
        clazz.addFunction(func);

        var body = generateBody(clazz, func, newApi(List.of(arrayBuiltinWithTypedarrayPackedByteArrayParam()), List.of()), List.of(clazz));
        assertTrue(body.contains("godot_Array_accept_packed(&$array, &$bytes)"), body);
    }

    @Test
    @DisplayName("CALL_METHOD should normalize typedarray StringName parameter to Array[StringName]")
    void callMethodShouldNormalizeTypedarrayStringNameParameter() {
        var clazz = newClass("Worker");
        var func = newFunction("call_typedarray_string_name_param");
        func.createAndAddVariable("array", new GdArrayType(GdStringType.STRING));
        func.createAndAddVariable("names", new GdArrayType(GdStringNameType.STRING_NAME));
        func.createAndAddVariable("ret", GdIntType.INT);
        entry(func).appendInstruction(new CallMethodInsn(
                "ret",
                "accept_names",
                "array",
                List.of(new LirInstruction.VariableOperand("names"))
        ));
        clazz.addFunction(func);

        var body = generateBody(clazz, func, newApi(List.of(arrayBuiltinWithTypedarrayStringNameParam()), List.of()), List.of(clazz));
        assertTrue(body.contains("godot_Array_accept_names(&$array, &$names)"), body);
    }

    @Test
    @DisplayName("CALL_METHOD should normalize typedarray PackedVector3Array return type")
    void callMethodShouldNormalizeTypedarrayPackedVector3ArrayReturn() {
        var clazz = newClass("Worker");
        var func = newFunction("call_typedarray_packed_return");
        func.createAndAddVariable("array", new GdArrayType(GdStringType.STRING));
        func.createAndAddVariable("ret", GdPackedVectorArrayType.PACKED_VECTOR3_ARRAY);
        entry(func).appendInstruction(new CallMethodInsn(
                "ret",
                "fetch_packed_vectors",
                "array",
                List.of()
        ));
        clazz.addFunction(func);

        var body = generateBody(clazz, func, newApi(List.of(arrayBuiltinWithTypedarrayPackedVector3ArrayReturn()), List.of()), List.of(clazz));
        assertTrue(body.contains("godot_Array_fetch_packed_vectors(&$array)"), body);
    }

    @Test
    @DisplayName("CALL_METHOD should emit OBJECT_DYNAMIC dispatch with argument pack and return unpack")
    void callMethodObjectDynamicShouldPackAndUnpack() {
        var clazz = newClass("Worker");
        var func = newFunction("call_object_dynamic");
        func.createAndAddVariable("obj", new GdObjectType("MysteryObject"));
        func.createAndAddVariable("value", GdIntType.INT);
        func.createAndAddVariable("ret", GdIntType.INT);
        entry(func).appendInstruction(new CallMethodInsn(
                "ret",
                "compute",
                "obj",
                List.of(new LirInstruction.VariableOperand("value"))
        ));
        clazz.addFunction(func);

        var body = generateBody(clazz, func, newApi(List.of(), List.of()), List.of(clazz));
        assertTrue(body.contains("godot_Object_call($obj, GD_STATIC_SN(u8\"compute\")"), body);
        assertTrue(body.contains("godot_new_Variant_with_int($value)"), body);
        assertTrue(body.contains("godot_new_int_with_Variant("), body);
        assertFalse(body.contains("godot_Variant_call("), body);
    }

    @Test
    @DisplayName("CALL_METHOD should emit VARIANT_DYNAMIC dispatch and keep Variant result")
    void callMethodVariantDynamicShouldEmitCall() {
        var clazz = newClass("Worker");
        clazz.setSourceFile("worker_dynamic.gd");
        var func = newFunction("call_variant_dynamic");
        func.createAndAddVariable("recv", GdVariantType.VARIANT);
        func.createAndAddVariable("arg", GdIntType.INT);
        func.createAndAddVariable("ret", GdVariantType.VARIANT);
        entry(func).appendInstruction(new LineNumberInsn(11));
        entry(func).appendInstruction(new LineNumberInsn(22));
        entry(func).appendInstruction(new CallMethodInsn(
                "ret",
                "mystery",
                "recv",
                List.of(new LirInstruction.VariableOperand("arg"))
        ));
        entry(func).appendInstruction(new LineNumberInsn(99));
        clazz.addFunction(func);

        var body = generateBody(clazz, func, newApi(List.of(), List.of()), List.of(clazz));
        assertTrue(body.contains("godot_Variant_call(&$recv, GD_STATIC_SN(u8\"mystery\")"), body);
        assertTrue(body.contains("\"worker_dynamic.gd\", 22"), body);
        assertFalse(body.contains("\"worker_dynamic.gd\", 99"), body);
        assertTrue(body.contains("godot_new_Variant_with_int($arg)"), body);
        assertTrue(body.contains("const godot_Variant* __gdcc_tmp_argv_"), body);
        assertFalse(body.contains("variant_call_argv"), body);
        assertFalse(body.contains("godot_Object_call("), body);
    }

    @Test
    @DisplayName("CALL_METHOD should unpack VARIANT_DYNAMIC return into non-Variant target with assemble fallback location")
    void callMethodVariantDynamicShouldUnpackReturn() {
        var clazz = newClass("Worker");
        clazz.setSourceFile("worker_dynamic.gd");
        var func = newFunction("call_variant_dynamic_unpack");
        func.createAndAddVariable("recv", GdVariantType.VARIANT);
        func.createAndAddVariable("arg", GdIntType.INT);
        func.createAndAddVariable("ret", GdIntType.INT);
        entry(func).appendInstruction(new CallMethodInsn(
                "ret",
                "mystery",
                "recv",
                List.of(new LirInstruction.VariableOperand("arg"))
        ));
        clazz.addFunction(func);

        var body = generateBody(clazz, func, newApi(List.of(), List.of()), List.of(clazz));
        assertTrue(body.contains("godot_Variant_call(&$recv, GD_STATIC_SN(u8\"mystery\")"), body);
        assertTrue(body.contains("\"worker_dynamic.gd(assemble)\", 0"), body);
        assertTrue(body.contains("godot_new_int_with_Variant("), body);
        assertTrue(body.contains("const godot_Variant* __gdcc_tmp_argv_"), body);
    }

    @Test
    @DisplayName("CALL_METHOD should fallback to class name when sourceFile is missing in VARIANT_DYNAMIC location")
    void callMethodVariantDynamicShouldUseClassNameWhenSourceFileMissing() {
        var clazz = newClass("WorkerNoSource");
        var func = newFunction("call_variant_dynamic_no_source_file");
        func.createAndAddVariable("recv", GdVariantType.VARIANT);
        func.createAndAddVariable("arg", GdIntType.INT);
        func.createAndAddVariable("ret", GdVariantType.VARIANT);
        entry(func).appendInstruction(new CallMethodInsn(
                "ret",
                "mystery",
                "recv",
                List.of(new LirInstruction.VariableOperand("arg"))
        ));
        clazz.addFunction(func);

        var body = generateBody(clazz, func, newApi(List.of(), List.of()), List.of(clazz));
        assertTrue(body.contains("godot_Variant_call(&$recv, GD_STATIC_SN(u8\"mystery\")"), body);
        assertTrue(body.contains("\"WorkerNoSource(assemble)\", 0"), body);
    }

    @Test
    @DisplayName("CALL_METHOD dynamic paths should reject ref result variable")
    void callMethodDynamicShouldRejectRefResult() {
        var clazz = newClass("Worker");
        var func = newFunction("call_object_dynamic_ref_result");
        func.createAndAddVariable("obj", new GdObjectType("MysteryObject"));
        func.createAndAddRefVariable("ret", GdVariantType.VARIANT);
        entry(func).appendInstruction(new CallMethodInsn(
                "ret",
                "compute",
                "obj",
                List.of()
        ));
        clazz.addFunction(func);

        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(clazz, func, newApi(List.of(), List.of()), List.of(clazz))
        );
        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("cannot be a reference"), ex.getMessage());
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

    private ExtensionGdClass nodeClassWithBitfieldDefaultParam() {
        var setProcessThreadMessages = new ExtensionGdClass.ClassMethod(
                "set_process_thread_messages",
                false,
                false,
                false,
                false,
                0L,
                List.of(),
                new ExtensionGdClass.ClassMethod.ClassMethodReturn("void"),
                List.of(new ExtensionFunctionArgument("flags", "bitfield::Node.ProcessThreadMessages", "0", null))
        );
        return new ExtensionGdClass(
                "Node",
                false,
                true,
                "Object",
                "core",
                List.of(),
                List.of(setProcessThreadMessages),
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

    private ExtensionBuiltinClass stringBuiltinWithSubstrDefault() {
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

    private ExtensionGdClass nodeClassWithVarargSpread() {
        var spread = new ExtensionGdClass.ClassMethod(
                "spread",
                false,
                true,
                false,
                false,
                0L,
                List.of(),
                new ExtensionGdClass.ClassMethod.ClassMethodReturn("void"),
                List.of(new ExtensionFunctionArgument("name", "StringName", null, null))
        );
        return new ExtensionGdClass(
                "Node",
                false,
                true,
                "Object",
                "core",
                List.of(),
                List.of(spread),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private ExtensionBuiltinClass arrayBuiltinWithTypedarrayPackedByteArrayParam() {
        var method = new ExtensionBuiltinClass.ClassMethod(
                "accept_packed",
                "int",
                false,
                true,
                false,
                false,
                0L,
                List.of(new ExtensionFunctionArgument("bytes", "typedarray::PackedByteArray", null, null)),
                List.of(),
                new ExtensionBuiltinClass.ClassMethod.ReturnValue("int")
        );
        return new ExtensionBuiltinClass(
                "Array",
                false,
                List.of(),
                List.of(method),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private ExtensionBuiltinClass arrayBuiltinWithTypedarrayStringNameParam() {
        var method = new ExtensionBuiltinClass.ClassMethod(
                "accept_names",
                "int",
                false,
                true,
                false,
                false,
                0L,
                List.of(new ExtensionFunctionArgument("names", "typedarray::StringName", null, null)),
                List.of(),
                new ExtensionBuiltinClass.ClassMethod.ReturnValue("int")
        );
        return new ExtensionBuiltinClass(
                "Array",
                false,
                List.of(),
                List.of(method),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private ExtensionBuiltinClass arrayBuiltinWithTypedarrayPackedVector3ArrayReturn() {
        var method = new ExtensionBuiltinClass.ClassMethod(
                "fetch_packed_vectors",
                "typedarray::PackedVector3Array",
                false,
                true,
                false,
                false,
                0L,
                List.of(),
                List.of(),
                new ExtensionBuiltinClass.ClassMethod.ReturnValue("typedarray::PackedVector3Array")
        );
        return new ExtensionBuiltinClass(
                "Array",
                false,
                List.of(),
                List.of(method),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
    }
}
