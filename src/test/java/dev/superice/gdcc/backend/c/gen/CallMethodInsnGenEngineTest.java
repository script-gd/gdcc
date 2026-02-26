package dev.superice.gdcc.backend.c.gen;

import dev.superice.gdcc.backend.CodegenContext;
import dev.superice.gdcc.backend.c.build.COptimizationLevel;
import dev.superice.gdcc.backend.c.build.CProjectBuilder;
import dev.superice.gdcc.backend.c.build.CProjectInfo;
import dev.superice.gdcc.backend.c.build.GodotGdextensionTestRunner;
import dev.superice.gdcc.backend.c.build.TargetPlatform;
import dev.superice.gdcc.backend.c.build.ZigUtil;
import dev.superice.gdcc.enums.GodotVersion;
import dev.superice.gdcc.gdextension.ExtensionApiLoader;
import dev.superice.gdcc.lir.LirBasicBlock;
import dev.superice.gdcc.lir.LirClassDef;
import dev.superice.gdcc.lir.LirFunctionDef;
import dev.superice.gdcc.lir.LirInstruction;
import dev.superice.gdcc.lir.LirModule;
import dev.superice.gdcc.lir.LirParameterDef;
import dev.superice.gdcc.lir.insn.CallMethodInsn;
import dev.superice.gdcc.lir.insn.LiteralStringInsn;
import dev.superice.gdcc.lir.insn.LiteralStringNameInsn;
import dev.superice.gdcc.lir.insn.PackVariantInsn;
import dev.superice.gdcc.lir.insn.ReturnInsn;
import dev.superice.gdcc.lir.insn.UnpackVariantInsn;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.type.GdBoolType;
import dev.superice.gdcc.type.GdIntType;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdStringNameType;
import dev.superice.gdcc.type.GdStringType;
import dev.superice.gdcc.type.GdType;
import dev.superice.gdcc.type.GdVariantType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CallMethodInsnGenEngineTest {

    @Test
    @DisplayName("CALL_METHOD should call engine vararg method correctly in real engine")
    void callMethodEngineVarargShouldRunInRealGodot() throws IOException, InterruptedException {
        if (!hasZig()) {
            Assumptions.abort("Zig not found; skipping integration test");
            return;
        }

        var tempDir = Path.of("tmp/test/call_method_engine_vararg");
        Files.createDirectories(tempDir);

        var projectInfo = new CProjectInfo(
                "call_method_engine_vararg",
                GodotVersion.V451,
                tempDir,
                COptimizationLevel.DEBUG,
                TargetPlatform.WINDOWS_X86_64
        );
        var builder = new CProjectBuilder();
        builder.initProject(projectInfo);

        var varargClass = newEngineVarargTestClass();
        var module = new LirModule("call_method_engine_vararg_module", List.of(varargClass));
        var api = ExtensionApiLoader.loadVersion(GodotVersion.V451);
        var codegen = new CCodegen();
        codegen.prepare(new CodegenContext(projectInfo, new ClassRegistry(api)), module);

        var buildResult = builder.buildProject(projectInfo, codegen);
        assertTrue(buildResult.success(), "Compilation should succeed. Build log:\n" + buildResult.buildLog());
        assertFalse(buildResult.artifacts().isEmpty(), "Compilation should produce extension artifacts.");

        var entrySource = Files.readString(tempDir.resolve("entry.c"));
        assertTrue(
                entrySource.contains("godot_Object_call((godot_Object*)$node, &$dispatchMethod, __gdcc_tmp_argv_"),
                "Engine vararg dispatch should be generated via argv/argc emission."
        );

        var runner = new GodotGdextensionTestRunner(Path.of("test_project"));
        runner.prepareProject(new GodotGdextensionTestRunner.ProjectSetup(
                buildResult.artifacts(),
                List.of(new GodotGdextensionTestRunner.SceneNodeSpec(
                        "CallMethodVarargNode",
                        varargClass.getName(),
                        ".",
                        Map.of()
                )),
                new GodotGdextensionTestRunner.TestScriptSpec(varargTestScript())
        ));

        var runResult = runner.run(true);
        var combinedOutput = runResult.combinedOutput();

        assertTrue(runResult.stopSignalSeen(), "Godot run should emit stop signal.\nOutput:\n" + combinedOutput);
        assertTrue(combinedOutput.contains("engine vararg call_method check passed."), "Vararg path should pass.\nOutput:\n" + combinedOutput);
        assertFalse(combinedOutput.contains("check failed"), "No check should fail.\nOutput:\n" + combinedOutput);
    }

    @Test
    @DisplayName("CALL_METHOD should support static GDCC dispatch across different GDCC classes in real engine")
    void callMethodBetweenDifferentGdccClassesShouldRunInRealGodot() throws IOException, InterruptedException {
        if (!hasZig()) {
            Assumptions.abort("Zig not found; skipping integration test");
            return;
        }

        var tempDir = Path.of("tmp/test/call_method_engine_gdcc_cross");
        Files.createDirectories(tempDir);

        var projectInfo = new CProjectInfo(
                "call_method_engine_gdcc_cross",
                GodotVersion.V451,
                tempDir,
                COptimizationLevel.DEBUG,
                TargetPlatform.WINDOWS_X86_64
        );
        var builder = new CProjectBuilder();
        builder.initProject(projectInfo);

        var hostClass = newGdccCrossCallHostClass();
        var peerClass = newGdccPeerClass();
        var module = new LirModule("call_method_engine_gdcc_cross_module", List.of(hostClass, peerClass));
        var api = ExtensionApiLoader.loadVersion(GodotVersion.V451);
        var codegen = new CCodegen();
        codegen.prepare(new CodegenContext(projectInfo, new ClassRegistry(api)), module);

        var buildResult = builder.buildProject(projectInfo, codegen);
        assertTrue(buildResult.success(), "Compilation should succeed. Build log:\n" + buildResult.buildLog());
        assertFalse(buildResult.artifacts().isEmpty(), "Compilation should produce extension artifacts.");

        var entrySource = Files.readString(tempDir.resolve("entry.c"));
        assertTrue(entrySource.contains("GDPeerWorker_echo_value("), "GDCC static dispatch should be generated.");
        assertFalse(entrySource.contains("godot_Object_call("), "GDCC cross-class call should not fallback to OBJECT_DYNAMIC.");
        assertFalse(entrySource.contains("godot_Variant_call("), "GDCC cross-class call should not fallback to VARIANT_DYNAMIC.");

        var runner = new GodotGdextensionTestRunner(Path.of("test_project"));
        runner.prepareProject(new GodotGdextensionTestRunner.ProjectSetup(
                buildResult.artifacts(),
                List.of(new GodotGdextensionTestRunner.SceneNodeSpec(
                        "GdccCrossCallNode",
                        hostClass.getName(),
                        ".",
                        Map.of()
                )),
                new GodotGdextensionTestRunner.TestScriptSpec(gdccCrossCallTestScript())
        ));

        var runResult = runner.run(true);
        var combinedOutput = runResult.combinedOutput();

        assertTrue(runResult.stopSignalSeen(), "Godot run should emit stop signal.\nOutput:\n" + combinedOutput);
        assertTrue(combinedOutput.contains("gdcc cross-class call_method check passed."), "GDCC cross-class path should pass.\nOutput:\n" + combinedOutput);
        assertFalse(combinedOutput.contains("check failed"), "No check should fail.\nOutput:\n" + combinedOutput);
    }

    @Test
    @DisplayName("CALL_METHOD should emit OBJECT_DYNAMIC for parent-typed GDCC receiver and run in real engine")
    void callMethodParentTypedGdccReceiverShouldUseObjectDynamicAndRunInRealGodot() throws IOException, InterruptedException {
        if (!hasZig()) {
            Assumptions.abort("Zig not found; skipping integration test");
            return;
        }

        var tempDir = Path.of("tmp/test/call_method_engine_parent_dynamic");
        Files.createDirectories(tempDir);

        var projectInfo = new CProjectInfo(
                "call_method_engine_parent_dynamic",
                GodotVersion.V451,
                tempDir,
                COptimizationLevel.DEBUG,
                TargetPlatform.WINDOWS_X86_64
        );
        var builder = new CProjectBuilder();
        builder.initProject(projectInfo);

        var hostClass = newGdccParentDynamicHostClass();
        var baseClass = newGdccDynamicBaseClass();
        var childClass = newGdccDynamicChildClass();
        var module = new LirModule("call_method_engine_parent_dynamic_module", List.of(hostClass, baseClass, childClass));
        var api = ExtensionApiLoader.loadVersion(GodotVersion.V451);
        var codegen = new CCodegen();
        codegen.prepare(new CodegenContext(projectInfo, new ClassRegistry(api)), module);

        var buildResult = builder.buildProject(projectInfo, codegen);
        assertTrue(buildResult.success(), "Compilation should succeed. Build log:\n" + buildResult.buildLog());
        assertFalse(buildResult.artifacts().isEmpty(), "Compilation should produce extension artifacts.");

        var entrySource = Files.readString(tempDir.resolve("entry.c"));
        assertTrue(
                entrySource.contains("godot_Object_call(godot_object_from_gdcc_object_ptr($baseRef), GD_STATIC_SN(u8\"child_only_echo\")"),
                "Parent-typed GDCC receiver should use OBJECT_DYNAMIC dispatch with GDCC pointer conversion."
        );
        assertFalse(
                entrySource.contains("GDChildDynamicWorker_child_only_echo($baseRef"),
                "Parent-typed receiver should not use static GDCC dispatch at call-site."
        );
        assertFalse(entrySource.contains("godot_Variant_call("), "Parent-typed receiver should use OBJECT_DYNAMIC, not VARIANT_DYNAMIC.");

        var runner = new GodotGdextensionTestRunner(Path.of("test_project"));
        runner.prepareProject(new GodotGdextensionTestRunner.ProjectSetup(
                buildResult.artifacts(),
                List.of(new GodotGdextensionTestRunner.SceneNodeSpec(
                        "GdccParentDynamicNode",
                        hostClass.getName(),
                        ".",
                        Map.of()
                )),
                new GodotGdextensionTestRunner.TestScriptSpec(gdccParentDynamicTestScript())
        ));

        var runResult = runner.run(true);
        var combinedOutput = runResult.combinedOutput();

        assertTrue(runResult.stopSignalSeen(), "Godot run should emit stop signal.\nOutput:\n" + combinedOutput);
        assertTrue(combinedOutput.contains("gdcc parent-typed dynamic call_method check passed."), "Parent-typed dynamic path should pass.\nOutput:\n" + combinedOutput);
        assertFalse(combinedOutput.contains("check failed"), "No check should fail.\nOutput:\n" + combinedOutput);
    }

    @Test
    @DisplayName("CALL_METHOD should run builtin/engine/object_dynamic/variant_dynamic paths in real engine")
    void callMethodPathsShouldRunInRealGodot() throws IOException, InterruptedException {
        if (!hasZig()) {
            Assumptions.abort("Zig not found; skipping integration test");
            return;
        }

        var tempDir = Path.of("tmp/test/call_method_engine");
        Files.createDirectories(tempDir);

        var projectInfo = new CProjectInfo(
                "call_method_engine",
                GodotVersion.V451,
                tempDir,
                COptimizationLevel.DEBUG,
                TargetPlatform.WINDOWS_X86_64
        );
        var builder = new CProjectBuilder();
        builder.initProject(projectInfo);

        var callMethodClass = newCallMethodEngineClass();
        var module = new LirModule("call_method_engine_module", List.of(callMethodClass));
        var api = ExtensionApiLoader.loadVersion(GodotVersion.V451);
        var codegen = new CCodegen();
        codegen.prepare(new CodegenContext(projectInfo, new ClassRegistry(api)), module);

        var buildResult = builder.buildProject(projectInfo, codegen);
        assertTrue(buildResult.success(), "Compilation should succeed. Build log:\n" + buildResult.buildLog());
        assertFalse(buildResult.artifacts().isEmpty(), "Compilation should produce extension artifacts.");

        var entrySource = Files.readString(tempDir.resolve("entry.c"));
        assertTrue(entrySource.contains("godot_String_substr"), "Builtin dispatch should be generated.");
        assertTrue(entrySource.contains("godot_Node_get_child_count"), "Engine dispatch should be generated.");
        assertTrue(entrySource.contains("godot_Object_call("), "OBJECT_DYNAMIC dispatch should be generated.");
        assertTrue(entrySource.contains("godot_Variant_call("), "VARIANT_DYNAMIC dispatch should be generated.");

        var runner = new GodotGdextensionTestRunner(Path.of("test_project"));
        runner.prepareProject(new GodotGdextensionTestRunner.ProjectSetup(
                buildResult.artifacts(),
                List.of(new GodotGdextensionTestRunner.SceneNodeSpec(
                        "CallMethodNode",
                        callMethodClass.getName(),
                        ".",
                        Map.of()
                )),
                new GodotGdextensionTestRunner.TestScriptSpec(testScript())
        ));

        var runResult = runner.run(true);
        var combinedOutput = runResult.combinedOutput();

        assertTrue(runResult.stopSignalSeen(), "Godot run should emit stop signal.\nOutput:\n" + combinedOutput);
        assertTrue(combinedOutput.contains("builtin call_method check passed."), "Builtin path should pass.\nOutput:\n" + combinedOutput);
        assertTrue(combinedOutput.contains("engine call_method check passed."), "Engine path should pass.\nOutput:\n" + combinedOutput);
        assertTrue(combinedOutput.contains("variant dynamic call_method check passed."), "VARIANT_DYNAMIC path should pass.\nOutput:\n" + combinedOutput);
        assertFalse(combinedOutput.contains("check failed"), "No check should fail.\nOutput:\n" + combinedOutput);
    }

    private static boolean hasZig() {
        return ZigUtil.findZig() != null;
    }

    private static LirClassDef newCallMethodEngineClass() {
        var clazz = new LirClassDef("GDCallMethodEngineNode", "Node");
        clazz.setSourceFile("call_method_engine.gd");

        var selfType = new GdObjectType(clazz.getName());
        clazz.addFunction(newBuiltinSubstrFunction(selfType));
        clazz.addFunction(newEngineGetChildCountFunction(selfType));
        clazz.addFunction(newObjectDynamicGetInstanceIdFunction(selfType));
        clazz.addFunction(newVariantDynamicToIntFunction(selfType));
        return clazz;
    }

    private static LirClassDef newEngineVarargTestClass() {
        var clazz = new LirClassDef("GDCallMethodVarargNode", "Node");
        clazz.setSourceFile("call_method_vararg_engine.gd");

        var selfType = new GdObjectType(clazz.getName());
        clazz.addFunction(newEngineVarargCallFunction(selfType));
        return clazz;
    }

    private static LirClassDef newGdccCrossCallHostClass() {
        var clazz = new LirClassDef("GDGdccCrossCallNode", "Node");
        clazz.setSourceFile("call_method_gdcc_cross_host.gd");

        var selfType = new GdObjectType(clazz.getName());
        clazz.addFunction(newGdccCrossCallFunction(selfType));
        return clazz;
    }

    private static LirClassDef newGdccPeerClass() {
        var clazz = new LirClassDef("GDPeerWorker", "RefCounted");
        clazz.setSourceFile("call_method_gdcc_cross_peer.gd");

        var selfType = new GdObjectType(clazz.getName());
        clazz.addFunction(newGdccPeerEchoFunction(selfType));
        return clazz;
    }

    private static LirClassDef newGdccParentDynamicHostClass() {
        var clazz = new LirClassDef("GDGdccParentDynamicNode", "Node");
        clazz.setSourceFile("call_method_gdcc_parent_dynamic_host.gd");

        var selfType = new GdObjectType(clazz.getName());
        clazz.addFunction(newGdccParentDynamicDispatchFunction(selfType));
        return clazz;
    }

    private static LirClassDef newGdccDynamicBaseClass() {
        var clazz = new LirClassDef("GDBaseDynamicWorker", "RefCounted");
        clazz.setSourceFile("call_method_gdcc_parent_dynamic_base.gd");

        var selfType = new GdObjectType(clazz.getName());
        clazz.addFunction(newGdccDynamicBasePingFunction(selfType));
        return clazz;
    }

    private static LirClassDef newGdccDynamicChildClass() {
        var clazz = new LirClassDef("GDChildDynamicWorker", "GDBaseDynamicWorker");
        clazz.setSourceFile("call_method_gdcc_parent_dynamic_child.gd");

        var selfType = new GdObjectType(clazz.getName());
        clazz.addFunction(newGdccDynamicChildOnlyEchoFunction(selfType));
        return clazz;
    }

    private static LirFunctionDef newBuiltinSubstrFunction(GdObjectType selfType) {
        var func = newMethod("call_builtin_substr", GdStringType.STRING, selfType);
        func.addParameter(new LirParameterDef("text", GdStringType.STRING, null, func));
        func.addParameter(new LirParameterDef("from", GdIntType.INT, null, func));
        func.addParameter(new LirParameterDef("len", GdIntType.INT, null, func));
        func.createAndAddVariable("result", GdStringType.STRING);

        entry(func).instructions().add(new CallMethodInsn(
                "result",
                "substr",
                "text",
                List.of(varRef("from"), varRef("len"))
        ));
        entry(func).instructions().add(new ReturnInsn("result"));
        return func;
    }

    private static LirFunctionDef newEngineGetChildCountFunction(GdObjectType selfType) {
        var func = newMethod("call_engine_get_child_count", GdIntType.INT, selfType);
        func.addParameter(new LirParameterDef("node", new GdObjectType("Node"), null, func));
        func.createAndAddVariable("result", GdIntType.INT);

        entry(func).instructions().add(new CallMethodInsn(
                "result",
                "get_child_count",
                "node",
                List.of()
        ));
        entry(func).instructions().add(new ReturnInsn("result"));
        return func;
    }

    private static LirFunctionDef newObjectDynamicGetInstanceIdFunction(GdObjectType selfType) {
        var func = newMethod("hidden_object_dynamic_get_instance_id", GdIntType.INT, selfType);
        func.setHidden(true);
        func.addParameter(new LirParameterDef("obj", new GdObjectType("MysteryObject"), null, func));
        func.createAndAddVariable("result", GdIntType.INT);

        entry(func).instructions().add(new CallMethodInsn(
                "result",
                "get_instance_id",
                "obj",
                List.of()
        ));
        entry(func).instructions().add(new ReturnInsn("result"));
        return func;
    }

    private static LirFunctionDef newVariantDynamicToIntFunction(GdObjectType selfType) {
        var func = newMethod("call_variant_to_int", GdIntType.INT, selfType);
        func.createAndAddVariable("source", GdStringType.STRING);
        func.createAndAddVariable("value", GdVariantType.VARIANT);
        func.createAndAddVariable("result", GdIntType.INT);

        entry(func).instructions().add(new LiteralStringInsn("source", "42"));
        entry(func).instructions().add(new PackVariantInsn("value", "source"));
        entry(func).instructions().add(new CallMethodInsn(
                "result",
                "to_int",
                "value",
                List.of()
        ));
        entry(func).instructions().add(new ReturnInsn("result"));
        return func;
    }

    private static LirFunctionDef newEngineVarargCallFunction(GdObjectType selfType) {
        var func = newMethod("call_engine_vararg_has_method", GdBoolType.BOOL, selfType);
        func.addParameter(new LirParameterDef("node", new GdObjectType("Node"), null, func));
        func.createAndAddVariable("dispatchMethod", GdStringNameType.STRING_NAME);
        func.createAndAddVariable("argMethodName", GdStringNameType.STRING_NAME);
        func.createAndAddVariable("argMethodNameVariant", GdVariantType.VARIANT);
        func.createAndAddVariable("resultVariant", GdVariantType.VARIANT);
        func.createAndAddVariable("resultBool", GdBoolType.BOOL);

        entry(func).instructions().add(new LiteralStringNameInsn("dispatchMethod", "has_method"));
        entry(func).instructions().add(new LiteralStringNameInsn("argMethodName", "queue_free"));
        entry(func).instructions().add(new PackVariantInsn("argMethodNameVariant", "argMethodName"));
        entry(func).instructions().add(new CallMethodInsn(
                "resultVariant",
                "call",
                "node",
                List.of(varRef("dispatchMethod"), varRef("argMethodNameVariant"))
        ));
        entry(func).instructions().add(new UnpackVariantInsn("resultBool", "resultVariant"));
        entry(func).instructions().add(new ReturnInsn("resultBool"));
        return func;
    }

    private static LirFunctionDef newGdccCrossCallFunction(GdObjectType selfType) {
        var func = newMethod("call_peer_echo", GdIntType.INT, selfType);
        func.addParameter(new LirParameterDef("peer", new GdObjectType("GDPeerWorker"), null, func));
        func.addParameter(new LirParameterDef("value", GdIntType.INT, null, func));
        func.createAndAddVariable("result", GdIntType.INT);

        entry(func).instructions().add(new CallMethodInsn(
                "result",
                "echo_value",
                "peer",
                List.of(varRef("value"))
        ));
        entry(func).instructions().add(new ReturnInsn("result"));
        return func;
    }

    private static LirFunctionDef newGdccPeerEchoFunction(GdObjectType selfType) {
        var func = newMethod("echo_value", GdIntType.INT, selfType);
        func.addParameter(new LirParameterDef("value", GdIntType.INT, null, func));
        entry(func).instructions().add(new ReturnInsn("value"));
        return func;
    }

    private static LirFunctionDef newGdccParentDynamicDispatchFunction(GdObjectType selfType) {
        var func = newMethod("call_child_only_from_base", GdIntType.INT, selfType);
        func.addParameter(new LirParameterDef("baseRef", new GdObjectType("GDBaseDynamicWorker"), null, func));
        func.addParameter(new LirParameterDef("value", GdIntType.INT, null, func));
        func.createAndAddVariable("result", GdIntType.INT);

        entry(func).instructions().add(new CallMethodInsn(
                "result",
                "child_only_echo",
                "baseRef",
                List.of(varRef("value"))
        ));
        entry(func).instructions().add(new ReturnInsn("result"));
        return func;
    }

    private static LirFunctionDef newGdccDynamicBasePingFunction(GdObjectType selfType) {
        var func = newMethod("ping", GdIntType.INT, selfType);
        func.addParameter(new LirParameterDef("value", GdIntType.INT, null, func));
        entry(func).instructions().add(new ReturnInsn("value"));
        return func;
    }

    private static LirFunctionDef newGdccDynamicChildOnlyEchoFunction(GdObjectType selfType) {
        var func = newMethod("child_only_echo", GdIntType.INT, selfType);
        func.addParameter(new LirParameterDef("value", GdIntType.INT, null, func));
        entry(func).instructions().add(new ReturnInsn("value"));
        return func;
    }

    private static LirFunctionDef newMethod(String name, GdType returnType, GdObjectType selfType) {
        var func = new LirFunctionDef(name);
        func.setReturnType(returnType);
        func.addParameter(new LirParameterDef("self", selfType, null, func));
        func.addBasicBlock(new LirBasicBlock("entry"));
        func.setEntryBlockId("entry");
        return func;
    }

    private static LirBasicBlock entry(LirFunctionDef functionDef) {
        return functionDef.getBasicBlock("entry");
    }

    private static LirInstruction.VariableOperand varRef(String id) {
        return new LirInstruction.VariableOperand(id);
    }

    private static String testScript() {
        return """
                extends Node
                
                const TARGET_NODE_NAME = "CallMethodNode"
                
                func _ready() -> void:
                    var target = get_parent().get_node_or_null(TARGET_NODE_NAME)
                    if target == null:
                        push_error("Target node missing.")
                        return
                
                    var builtin_value = str(target.call("call_builtin_substr", "abcdef", 2, 3))
                    if builtin_value == "cde":
                        print("builtin call_method check passed.")
                    else:
                        push_error("builtin call_method check failed.")
                
                    var probe = Node.new()
                    probe.add_child(Node.new())
                    var child_count = int(target.call("call_engine_get_child_count", probe))
                    if child_count == 1:
                        print("engine call_method check passed.")
                    else:
                        push_error("engine call_method check failed.")
                
                    var variant_int = int(target.call("call_variant_to_int"))
                    if variant_int == 42:
                        print("variant dynamic call_method check passed.")
                    else:
                        push_error("variant dynamic call_method check failed.")
                """;
    }

    private static String varargTestScript() {
        return """
                extends Node
                
                const TARGET_NODE_NAME = "CallMethodVarargNode"
                
                func _ready() -> void:
                    var target = get_parent().get_node_or_null(TARGET_NODE_NAME)
                    if target == null:
                        push_error("Target node missing.")
                        return
                
                    var probe = Node.new()
                    var ok = bool(target.call("call_engine_vararg_has_method", probe))
                    if ok:
                        print("engine vararg call_method check passed.")
                    else:
                        push_error("engine vararg call_method check failed.")
                """;
    }

    private static String gdccCrossCallTestScript() {
        return """
                extends Node
                
                const TARGET_NODE_NAME = "GdccCrossCallNode"
                
                func _ready() -> void:
                    var target = get_parent().get_node_or_null(TARGET_NODE_NAME)
                    if target == null:
                        push_error("Target node missing.")
                        return
                
                    var peer = GDPeerWorker.new()
                    var result = int(target.call("call_peer_echo", peer, 77))
                    if result == 77:
                        print("gdcc cross-class call_method check passed.")
                    else:
                        push_error("gdcc cross-class call_method check failed.")
                """;
    }

    private static String gdccParentDynamicTestScript() {
        return """
                extends Node
                
                const TARGET_NODE_NAME = "GdccParentDynamicNode"
                
                func _ready() -> void:
                    var target = get_parent().get_node_or_null(TARGET_NODE_NAME)
                    if target == null:
                        push_error("Target node missing.")
                        return
                
                    var child = GDChildDynamicWorker.new()
                    var result = int(target.call("call_child_only_from_base", child, 93))
                    if result == 93:
                        print("gdcc parent-typed dynamic call_method check passed.")
                    else:
                        push_error("gdcc parent-typed dynamic call_method check failed.")
                """;
    }
}
