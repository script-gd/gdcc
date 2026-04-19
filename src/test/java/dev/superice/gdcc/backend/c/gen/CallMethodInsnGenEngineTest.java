package dev.superice.gdcc.backend.c.gen;

import dev.superice.gdcc.backend.CodegenContext;
import dev.superice.gdcc.backend.c.build.COptimizationLevel;
import dev.superice.gdcc.backend.c.build.CProjectBuilder;
import dev.superice.gdcc.backend.c.build.CProjectInfo;
import dev.superice.gdcc.backend.c.build.GodotGdextensionTestRunner;
import dev.superice.gdcc.backend.c.build.TargetPlatform;
import dev.superice.gdcc.backend.c.build.ZigUtil;
import dev.superice.gdcc.enums.GodotOperator;
import dev.superice.gdcc.enums.GodotVersion;
import dev.superice.gdcc.gdextension.ExtensionApiLoader;
import dev.superice.gdcc.lir.LirBasicBlock;
import dev.superice.gdcc.lir.LirClassDef;
import dev.superice.gdcc.lir.LirFunctionDef;
import dev.superice.gdcc.lir.LirInstruction;
import dev.superice.gdcc.lir.LirModule;
import dev.superice.gdcc.lir.LirParameterDef;
import dev.superice.gdcc.lir.insn.BinaryOpInsn;
import dev.superice.gdcc.lir.insn.CallMethodInsn;
import dev.superice.gdcc.lir.insn.LiteralBoolInsn;
import dev.superice.gdcc.lir.insn.LiteralIntInsn;
import dev.superice.gdcc.lir.insn.LiteralStringInsn;
import dev.superice.gdcc.lir.insn.LiteralStringNameInsn;
import dev.superice.gdcc.lir.insn.PackVariantInsn;
import dev.superice.gdcc.lir.insn.ReturnInsn;
import dev.superice.gdcc.lir.insn.UnpackVariantInsn;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.type.GdArrayType;
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
    @DisplayName("CALL_METHOD should keep exact engine vararg success, discard and error paths stable in real engine")
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
                TargetPlatform.getNativePlatform()
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
                entrySource.contains("gdcc_engine_callv_object_call_"),
                "Engine vararg dispatch should route through generated callv helper."
        );
        assertTrue(
                entrySource.contains("gdcc_engine_callv_scenetree_call_group_flags_"),
                "Broader engine vararg families should also route through generated callv helpers."
        );
        assertTrue(entrySource.contains("__gdcc_tmp_argv_"), "Engine vararg dispatch should still emit caller-owned argv/argc.");
        assertFalse(entrySource.contains("godot_Object_call((godot_Object*)$node, &$dispatchMethod, __gdcc_tmp_argv_"), entrySource);

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
        assertTrue(
                combinedOutput.contains("engine vararg call_group_flags check passed."),
                "A second stock engine vararg family should also pass through the generated helper route.\nOutput:\n" + combinedOutput
        );
        assertTrue(combinedOutput.contains("engine vararg discard call_method check passed."), "Discard path should pass.\nOutput:\n" + combinedOutput);
        assertTrue(combinedOutput.contains("engine vararg error-path check passed."), "Error path should continue after helper cleanup.\nOutput:\n" + combinedOutput);
        assertTrue(
                combinedOutput.contains("engine method call failed: Object.call: invalid target method 'missing_method'"),
                "Helper should emit a stable runtime error with concrete call details for call-error paths.\nOutput:\n" + combinedOutput
        );
        assertFalse(
                combinedOutput.contains("engine method call failed: SceneTree.call_group_flags"),
                "The broader SceneTree vararg anchor should stay on the success path.\nOutput:\n" + combinedOutput
        );
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
                TargetPlatform.getNativePlatform()
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
                TargetPlatform.getNativePlatform()
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
                entrySource.contains("godot_Object_call(gdcc_object_to_godot_object_ptr($baseRef, GDBaseDynamicWorker_object_ptr), GD_STATIC_SN(u8\"child_only_echo\")"),
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
    @DisplayName("CALL_METHOD should run GDCC receiver to engine owner static dispatch with safe pointer conversion")
    void callMethodGdccReceiverToEngineOwnerShouldRunWithSafePointerConversion() throws IOException, InterruptedException {
        if (!hasZig()) {
            Assumptions.abort("Zig not found; skipping integration test");
            return;
        }

        var tempDir = Path.of("tmp/test/call_method_engine_owner_bridge");
        Files.createDirectories(tempDir);

        var projectInfo = new CProjectInfo(
                "call_method_engine_owner_bridge",
                GodotVersion.V451,
                tempDir,
                COptimizationLevel.DEBUG,
                TargetPlatform.getNativePlatform()
        );
        var builder = new CProjectBuilder();
        builder.initProject(projectInfo);

        var bridgeClass = newGdccEngineOwnerBridgeClass();
        var module = new LirModule("call_method_engine_owner_bridge_module", List.of(bridgeClass));
        var api = ExtensionApiLoader.loadVersion(GodotVersion.V451);
        var codegen = new CCodegen();
        codegen.prepare(new CodegenContext(projectInfo, new ClassRegistry(api)), module);

        var buildResult = builder.buildProject(projectInfo, codegen);
        assertTrue(buildResult.success(), "Compilation should succeed. Build log:\n" + buildResult.buildLog());
        assertFalse(buildResult.artifacts().isEmpty(), "Compilation should produce extension artifacts.");

        var entrySource = Files.readString(tempDir.resolve("entry.c"));
        assertTrue(entrySource.contains("gdcc_engine_call_node_get_child_count_"), "Engine dispatch should use the phase-5 helper route.");
        assertTrue(
                entrySource.contains("gdcc_object_to_godot_object_ptr($self, GDGdccEngineOwnerBridgeNode_object_ptr)"),
                "Engine dispatch on GDCC receiver should still cast after helper conversion."
        );
        assertFalse(entrySource.contains("godot_Node_get_child_count("), "Phase-5 route should stop calling the legacy wrapper.");

        var runner = new GodotGdextensionTestRunner(Path.of("test_project"));
        runner.prepareProject(new GodotGdextensionTestRunner.ProjectSetup(
                buildResult.artifacts(),
                List.of(new GodotGdextensionTestRunner.SceneNodeSpec(
                        "GdccEngineOwnerBridgeNode",
                        bridgeClass.getName(),
                        ".",
                        Map.of()
                )),
                new GodotGdextensionTestRunner.TestScriptSpec(gdccEngineOwnerBridgeTestScript())
        ));

        var runResult = runner.run(true);
        var combinedOutput = runResult.combinedOutput();

        assertTrue(runResult.stopSignalSeen(), "Godot run should emit stop signal.\nOutput:\n" + combinedOutput);
        assertTrue(combinedOutput.contains("gdcc receiver engine owner call_method check passed."), "Bridge path should pass.\nOutput:\n" + combinedOutput);
        assertFalse(combinedOutput.contains("check failed"), "No check should fail.\nOutput:\n" + combinedOutput);
    }

    @Test
    @DisplayName("CALL_METHOD should run builtin/exact-engine/object-dynamic/variant-dynamic routes in real engine")
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
                TargetPlatform.getNativePlatform()
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
        var bindHeader = Files.readString(tempDir.resolve("engine_method_binds.h"));
        assertTrue(entrySource.contains("godot_String_substr"), "Builtin dispatch should be generated.");
        assertTrue(entrySource.contains("gdcc_engine_call_node_get_child_count_"), "Exact engine getter dispatch should use helper route.");
        assertTrue(entrySource.contains("gdcc_engine_call_node_add_child_"), "Exact engine object-parameter dispatch should use helper route.");
        assertTrue(
                entrySource.contains("gdcc_engine_call_arraymesh_add_surface_from_arrays_"),
                "Bitfield-compatible exact engine helper route should be generated."
        );
        assertFalse(entrySource.contains("(const godot_Mesh_ArrayFormat *)&"), "Caller-side helper route should no longer emit wrapper-compatible bitfield casts.");
        assertTrue(
                bindHeader.contains("godot_Mesh_PrimitiveType arg0_slot = (godot_Mesh_PrimitiveType)arg0;"),
                "ArrayMesh helper should materialize the enum slot inside the helper body."
        );
        assertTrue(
                bindHeader.contains("_slot = (godot_Mesh_ArrayFormat)arg"),
                "ArrayMesh helper should materialize the bitfield slot inside the helper body."
        );
        assertFalse(bindHeader.contains("(const godot_Mesh_ArrayFormat *)&"), "Helper body should no longer rely on wrapper-compatible bitfield casts.");
        assertTrue(entrySource.contains("godot_Object_call("), "OBJECT_DYNAMIC dispatch should be generated.");
        assertTrue(entrySource.contains("godot_Variant_call("), "VARIANT_DYNAMIC dispatch should be generated.");
        assertTrue(entrySource.contains("\"call_method_engine.gd(assemble)\""), "VARIANT_DYNAMIC fallback source location should be generated.");

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
        assertTrue(
                combinedOutput.contains("engine exact object-parameter call_method check passed."),
                "Exact object-parameter helper path should pass.\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("engine exact explicit add_child full-args call_method check passed."),
                "Typed receiver add_child should stay stable when bool/enum parameters are passed explicitly.\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("engine bitfield helper call_method check passed."),
                "Bitfield-compatible helper path should pass.\nOutput:\n" + combinedOutput
        );
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
        clazz.addFunction(newEngineAddChildExactFunction(selfType));
        clazz.addFunction(newEngineAddChildExplicitInternalFunction(selfType));
        clazz.addFunction(newEngineAddSurfaceFromArraysFunction(selfType));
        clazz.addFunction(newObjectDynamicGetInstanceIdFunction(selfType));
        clazz.addFunction(newVariantDynamicToIntFunction(selfType));
        return clazz;
    }

    private static LirClassDef newEngineVarargTestClass() {
        var clazz = new LirClassDef("GDCallMethodVarargNode", "Node");
        clazz.setSourceFile("call_method_vararg_engine.gd");

        var selfType = new GdObjectType(clazz.getName());
        clazz.addFunction(newEngineVarargCallFunction(selfType));
        clazz.addFunction(newEngineVarargGroupFlagsFunction(selfType));
        clazz.addFunction(newEngineVarargDiscardCallFunction(selfType));
        clazz.addFunction(newEngineVarargMissingMethodFunction(selfType));
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

    private static LirClassDef newGdccEngineOwnerBridgeClass() {
        var clazz = new LirClassDef("GDGdccEngineOwnerBridgeNode", "Node");
        clazz.setSourceFile("call_method_gdcc_engine_owner_bridge.gd");

        var selfType = new GdObjectType(clazz.getName());
        clazz.addFunction(newGdccEngineOwnerBridgeFunction(selfType));
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

        entry(func).appendInstruction(new CallMethodInsn(
                "result",
                "substr",
                "text",
                List.of(varRef("from"), varRef("len"))
        ));
        entry(func).appendInstruction(new ReturnInsn("result"));
        return func;
    }

    private static LirFunctionDef newEngineGetChildCountFunction(GdObjectType selfType) {
        var func = newMethod("call_engine_get_child_count", GdIntType.INT, selfType);
        func.addParameter(new LirParameterDef("node", new GdObjectType("Node"), null, func));
        func.createAndAddVariable("result", GdIntType.INT);

        entry(func).appendInstruction(new CallMethodInsn(
                "result",
                "get_child_count",
                "node",
                List.of()
        ));
        entry(func).appendInstruction(new ReturnInsn("result"));
        return func;
    }

    private static LirFunctionDef newEngineAddChildExactFunction(GdObjectType selfType) {
        var func = newMethod("call_engine_add_child_exact", GdIntType.INT, selfType);
        func.addParameter(new LirParameterDef("holder", new GdObjectType("Node"), null, func));
        func.addParameter(new LirParameterDef("child", new GdObjectType("Node"), null, func));
        func.createAndAddVariable("result", GdIntType.INT);

        entry(func).appendInstruction(new CallMethodInsn(
                null,
                "add_child",
                "holder",
                List.of(varRef("child"))
        ));
        entry(func).appendInstruction(new CallMethodInsn(
                "result",
                "get_child_count",
                "holder",
                List.of()
        ));
        entry(func).appendInstruction(new ReturnInsn("result"));
        return func;
    }

    private static LirFunctionDef newEngineAddChildExplicitInternalFunction(GdObjectType selfType) {
        var func = newMethod("call_engine_add_child_exact_full_args", GdIntType.INT, selfType);
        func.addParameter(new LirParameterDef("holder", new GdObjectType("Node"), null, func));
        func.addParameter(new LirParameterDef("child", new GdObjectType("Node"), null, func));
        func.createAndAddVariable("forceReadableName", GdBoolType.BOOL);
        func.createAndAddVariable("includeInternal", GdBoolType.BOOL);
        func.createAndAddVariable("internalMode", GdIntType.INT);
        func.createAndAddVariable("visibleCount", GdIntType.INT);
        func.createAndAddVariable("totalCount", GdIntType.INT);
        func.createAndAddVariable("result", GdIntType.INT);

        entry(func).appendInstruction(new LiteralBoolInsn("forceReadableName", false));
        entry(func).appendInstruction(new LiteralBoolInsn("includeInternal", true));
        entry(func).appendInstruction(new LiteralIntInsn("internalMode", 1));
        entry(func).appendInstruction(new CallMethodInsn(
                null,
                "add_child",
                "holder",
                List.of(varRef("child"), varRef("forceReadableName"), varRef("internalMode"))
        ));
        entry(func).appendInstruction(new CallMethodInsn(
                "visibleCount",
                "get_child_count",
                "holder",
                List.of()
        ));
        entry(func).appendInstruction(new CallMethodInsn(
                "totalCount",
                "get_child_count",
                "holder",
                List.of(varRef("includeInternal"))
        ));
        entry(func).appendInstruction(new BinaryOpInsn("result", GodotOperator.ADD, "visibleCount", "totalCount"));
        entry(func).appendInstruction(new ReturnInsn("result"));
        return func;
    }

    private static LirFunctionDef newEngineAddSurfaceFromArraysFunction(GdObjectType selfType) {
        var func = newMethod("call_array_mesh_add_surface", GdIntType.INT, selfType);
        func.addParameter(new LirParameterDef("mesh", new GdObjectType("ArrayMesh"), null, func));
        func.addParameter(new LirParameterDef("primitive", GdIntType.INT, null, func));
        func.addParameter(new LirParameterDef("arrays", new GdArrayType(GdVariantType.VARIANT), null, func));
        func.createAndAddVariable("result", GdIntType.INT);

        entry(func).appendInstruction(new CallMethodInsn(
                null,
                "add_surface_from_arrays",
                "mesh",
                List.of(varRef("primitive"), varRef("arrays"))
        ));
        entry(func).appendInstruction(new CallMethodInsn(
                "result",
                "get_surface_count",
                "mesh",
                List.of()
        ));
        entry(func).appendInstruction(new ReturnInsn("result"));
        return func;
    }

    private static LirFunctionDef newObjectDynamicGetInstanceIdFunction(GdObjectType selfType) {
        var func = newMethod("hidden_object_dynamic_get_instance_id", GdIntType.INT, selfType);
        func.setHidden(true);
        func.addParameter(new LirParameterDef("obj", new GdObjectType("MysteryObject"), null, func));
        func.createAndAddVariable("result", GdIntType.INT);

        entry(func).appendInstruction(new CallMethodInsn(
                "result",
                "get_instance_id",
                "obj",
                List.of()
        ));
        entry(func).appendInstruction(new ReturnInsn("result"));
        return func;
    }

    private static LirFunctionDef newVariantDynamicToIntFunction(GdObjectType selfType) {
        var func = newMethod("call_variant_to_int", GdIntType.INT, selfType);
        func.createAndAddVariable("source", GdStringType.STRING);
        func.createAndAddVariable("value", GdVariantType.VARIANT);
        func.createAndAddVariable("result", GdIntType.INT);

        entry(func).appendInstruction(new LiteralStringInsn("source", "42"));
        entry(func).appendInstruction(new PackVariantInsn("value", "source"));
        entry(func).appendInstruction(new CallMethodInsn(
                "result",
                "to_int",
                "value",
                List.of()
        ));
        entry(func).appendInstruction(new ReturnInsn("result"));
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

        entry(func).appendInstruction(new LiteralStringNameInsn("dispatchMethod", "has_method"));
        entry(func).appendInstruction(new LiteralStringNameInsn("argMethodName", "queue_free"));
        entry(func).appendInstruction(new PackVariantInsn("argMethodNameVariant", "argMethodName"));
        entry(func).appendInstruction(new CallMethodInsn(
                "resultVariant",
                "call",
                "node",
                List.of(varRef("dispatchMethod"), varRef("argMethodNameVariant"))
        ));
        entry(func).appendInstruction(new UnpackVariantInsn("resultBool", "resultVariant"));
        entry(func).appendInstruction(new ReturnInsn("resultBool"));
        return func;
    }

    private static LirFunctionDef newEngineVarargGroupFlagsFunction(GdObjectType selfType) {
        var func = newMethod("call_engine_vararg_group_flags_internal_add_child", GdIntType.INT, selfType);
        func.addParameter(new LirParameterDef("child", new GdObjectType("Node"), null, func));
        func.createAndAddVariable("tree", new GdObjectType("SceneTree"));
        func.createAndAddVariable("flags", GdIntType.INT);
        func.createAndAddVariable("groupName", GdStringNameType.STRING_NAME);
        func.createAndAddVariable("dispatchMethod", GdStringNameType.STRING_NAME);
        func.createAndAddVariable("forceReadableName", GdBoolType.BOOL);
        func.createAndAddVariable("includeInternal", GdBoolType.BOOL);
        func.createAndAddVariable("internalMode", GdIntType.INT);
        func.createAndAddVariable("childVariant", GdVariantType.VARIANT);
        func.createAndAddVariable("forceReadableNameVariant", GdVariantType.VARIANT);
        func.createAndAddVariable("internalModeVariant", GdVariantType.VARIANT);
        func.createAndAddVariable("visibleCount", GdIntType.INT);
        func.createAndAddVariable("totalCount", GdIntType.INT);
        func.createAndAddVariable("result", GdIntType.INT);

        entry(func).appendInstruction(new CallMethodInsn(
                "tree",
                "get_tree",
                "self",
                List.of()
        ));
        entry(func).appendInstruction(new LiteralIntInsn("flags", 0));
        entry(func).appendInstruction(new LiteralStringNameInsn("groupName", "gdcc_exact_group_flags_smoke"));
        entry(func).appendInstruction(new LiteralStringNameInsn("dispatchMethod", "add_child"));
        entry(func).appendInstruction(new CallMethodInsn(
                null,
                "add_to_group",
                "self",
                List.of(varRef("groupName"))
        ));
        entry(func).appendInstruction(new LiteralBoolInsn("forceReadableName", false));
        entry(func).appendInstruction(new LiteralBoolInsn("includeInternal", true));
        entry(func).appendInstruction(new LiteralIntInsn("internalMode", 1));
        entry(func).appendInstruction(new PackVariantInsn("childVariant", "child"));
        entry(func).appendInstruction(new PackVariantInsn("forceReadableNameVariant", "forceReadableName"));
        entry(func).appendInstruction(new PackVariantInsn("internalModeVariant", "internalMode"));
        entry(func).appendInstruction(new CallMethodInsn(
                null,
                "call_group_flags",
                "tree",
                List.of(
                        varRef("flags"),
                        varRef("groupName"),
                        varRef("dispatchMethod"),
                        varRef("childVariant"),
                        varRef("forceReadableNameVariant"),
                        varRef("internalModeVariant")
                )
        ));
        entry(func).appendInstruction(new CallMethodInsn(
                "visibleCount",
                "get_child_count",
                "self",
                List.of()
        ));
        entry(func).appendInstruction(new CallMethodInsn(
                "totalCount",
                "get_child_count",
                "self",
                List.of(varRef("includeInternal"))
        ));
        entry(func).appendInstruction(new BinaryOpInsn("result", GodotOperator.ADD, "visibleCount", "totalCount"));
        entry(func).appendInstruction(new ReturnInsn("result"));
        return func;
    }

    private static LirFunctionDef newEngineVarargDiscardCallFunction(GdObjectType selfType) {
        var func = newMethod("call_engine_vararg_discard_has_method", GdIntType.INT, selfType);
        func.addParameter(new LirParameterDef("node", new GdObjectType("Node"), null, func));
        func.createAndAddVariable("dispatchMethod", GdStringNameType.STRING_NAME);
        func.createAndAddVariable("argMethodName", GdStringNameType.STRING_NAME);
        func.createAndAddVariable("argMethodNameVariant", GdVariantType.VARIANT);
        func.createAndAddVariable("result", GdIntType.INT);

        entry(func).appendInstruction(new LiteralStringNameInsn("dispatchMethod", "has_method"));
        entry(func).appendInstruction(new LiteralStringNameInsn("argMethodName", "queue_free"));
        entry(func).appendInstruction(new PackVariantInsn("argMethodNameVariant", "argMethodName"));
        entry(func).appendInstruction(new CallMethodInsn(
                null,
                "call",
                "node",
                List.of(varRef("dispatchMethod"), varRef("argMethodNameVariant"))
        ));
        entry(func).appendInstruction(new CallMethodInsn(
                "result",
                "get_child_count",
                "node",
                List.of()
        ));
        entry(func).appendInstruction(new ReturnInsn("result"));
        return func;
    }

    private static LirFunctionDef newEngineVarargMissingMethodFunction(GdObjectType selfType) {
        var func = newMethod("call_engine_vararg_missing_method_and_continue", GdIntType.INT, selfType);
        func.addParameter(new LirParameterDef("node", new GdObjectType("Node"), null, func));
        func.createAndAddVariable("dispatchMethod", GdStringNameType.STRING_NAME);
        func.createAndAddVariable("ignoredResult", GdVariantType.VARIANT);
        func.createAndAddVariable("result", GdIntType.INT);

        entry(func).appendInstruction(new LiteralStringNameInsn("dispatchMethod", "missing_method"));
        entry(func).appendInstruction(new CallMethodInsn(
                "ignoredResult",
                "call",
                "node",
                List.of(varRef("dispatchMethod"))
        ));
        entry(func).appendInstruction(new CallMethodInsn(
                "result",
                "get_child_count",
                "node",
                List.of()
        ));
        entry(func).appendInstruction(new ReturnInsn("result"));
        return func;
    }

    private static LirFunctionDef newGdccCrossCallFunction(GdObjectType selfType) {
        var func = newMethod("call_peer_echo", GdIntType.INT, selfType);
        func.addParameter(new LirParameterDef("peer", new GdObjectType("GDPeerWorker"), null, func));
        func.addParameter(new LirParameterDef("value", GdIntType.INT, null, func));
        func.createAndAddVariable("result", GdIntType.INT);

        entry(func).appendInstruction(new CallMethodInsn(
                "result",
                "echo_value",
                "peer",
                List.of(varRef("value"))
        ));
        entry(func).appendInstruction(new ReturnInsn("result"));
        return func;
    }

    private static LirFunctionDef newGdccPeerEchoFunction(GdObjectType selfType) {
        var func = newMethod("echo_value", GdIntType.INT, selfType);
        func.addParameter(new LirParameterDef("value", GdIntType.INT, null, func));
        entry(func).appendInstruction(new ReturnInsn("value"));
        return func;
    }

    private static LirFunctionDef newGdccParentDynamicDispatchFunction(GdObjectType selfType) {
        var func = newMethod("call_child_only_from_base", GdIntType.INT, selfType);
        func.addParameter(new LirParameterDef("baseRef", new GdObjectType("GDBaseDynamicWorker"), null, func));
        func.addParameter(new LirParameterDef("value", GdIntType.INT, null, func));
        func.createAndAddVariable("result", GdIntType.INT);

        entry(func).appendInstruction(new CallMethodInsn(
                "result",
                "child_only_echo",
                "baseRef",
                List.of(varRef("value"))
        ));
        entry(func).appendInstruction(new ReturnInsn("result"));
        return func;
    }

    private static LirFunctionDef newGdccDynamicBasePingFunction(GdObjectType selfType) {
        var func = newMethod("ping", GdIntType.INT, selfType);
        func.addParameter(new LirParameterDef("value", GdIntType.INT, null, func));
        entry(func).appendInstruction(new ReturnInsn("value"));
        return func;
    }

    private static LirFunctionDef newGdccDynamicChildOnlyEchoFunction(GdObjectType selfType) {
        var func = newMethod("child_only_echo", GdIntType.INT, selfType);
        func.addParameter(new LirParameterDef("value", GdIntType.INT, null, func));
        entry(func).appendInstruction(new ReturnInsn("value"));
        return func;
    }

    private static LirFunctionDef newGdccEngineOwnerBridgeFunction(GdObjectType selfType) {
        var func = newMethod("call_self_get_child_count", GdIntType.INT, selfType);
        func.createAndAddVariable("result", GdIntType.INT);

        entry(func).appendInstruction(new CallMethodInsn(
                "result",
                "get_child_count",
                "self",
                List.of()
        ));
        entry(func).appendInstruction(new ReturnInsn("result"));
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
                
                    var holder: Node = Node.new()
                    var child: Node = Node.new()
                    var exact_count = int(target.call("call_engine_add_child_exact", holder, child))
                    if exact_count == 1:
                        print("engine exact object-parameter call_method check passed.")
                    else:
                        push_error("engine exact object-parameter call_method check failed.")
                
                    var internal_holder: Node = Node.new()
                    var internal_child: Node = Node.new()
                    var internal_count = int(target.call("call_engine_add_child_exact_full_args", internal_holder, internal_child))
                    if internal_count == 1:
                        print("engine exact explicit add_child full-args call_method check passed.")
                    else:
                        push_error("engine exact explicit add_child full-args call_method check failed.")
                
                    var mesh: ArrayMesh = ArrayMesh.new()
                    var arrays := []
                    arrays.resize(Mesh.ARRAY_MAX)
                    arrays[Mesh.ARRAY_VERTEX] = PackedVector3Array([
                        Vector3(0, 0, 0),
                        Vector3(1, 0, 0),
                        Vector3(0, 1, 0),
                    ])
                    var surface_count = int(target.call("call_array_mesh_add_surface", mesh, Mesh.PRIMITIVE_TRIANGLES, arrays))
                    if surface_count == 1:
                        print("engine bitfield helper call_method check passed.")
                    else:
                        push_error("engine bitfield helper call_method check failed.")
                
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
                
                    var group_child = Node.new()
                    var group_count = int(target.call("call_engine_vararg_group_flags_internal_add_child", group_child))
                    if group_count == 1:
                        print("engine vararg call_group_flags check passed.")
                    else:
                        push_error("engine vararg call_group_flags check failed.")
                
                    var discard_count = int(target.call("call_engine_vararg_discard_has_method", probe))
                    if discard_count == 0:
                        print("engine vararg discard call_method check passed.")
                    else:
                        push_error("engine vararg discard call_method check failed.")
                
                    var error_count = int(target.call("call_engine_vararg_missing_method_and_continue", probe))
                    if error_count == 0:
                        print("engine vararg error-path check passed.")
                    else:
                        push_error("engine vararg error-path check failed.")
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

    private static String gdccEngineOwnerBridgeTestScript() {
        return """
                extends Node
                
                const TARGET_NODE_NAME = "GdccEngineOwnerBridgeNode"
                
                func _ready() -> void:
                    var target = get_parent().get_node_or_null(TARGET_NODE_NAME)
                    if target == null:
                        push_error("Target node missing.")
                        return
                
                    var count = int(target.call("call_self_get_child_count"))
                    if count == 0:
                        print("gdcc receiver engine owner call_method check passed.")
                    else:
                        push_error("gdcc receiver engine owner call_method check failed.")
                """;
    }
}
