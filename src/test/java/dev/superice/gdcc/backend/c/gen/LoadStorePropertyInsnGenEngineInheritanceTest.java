package dev.superice.gdcc.backend.c.gen;

import dev.superice.gdcc.backend.CodegenContext;
import dev.superice.gdcc.backend.c.build.CBuildResult;
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
import dev.superice.gdcc.lir.LirModule;
import dev.superice.gdcc.lir.LirParameterDef;
import dev.superice.gdcc.lir.insn.LoadPropertyInsn;
import dev.superice.gdcc.lir.insn.ReturnInsn;
import dev.superice.gdcc.lir.insn.StorePropertyInsn;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.type.GdIntType;
import dev.superice.gdcc.type.GdType;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdVoidType;
import org.jetbrains.annotations.NotNull;
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

class LoadStorePropertyInsnGenEngineInheritanceTest {

    @Test
    @DisplayName("LOAD_PROPERTY should run ENGINE child receiver -> ENGINE parent property path in real engine")
    void loadPropertyEngineChildReceiverShouldRunInRealGodot() throws IOException, InterruptedException {
        if (!hasZig()) {
            Assumptions.abort("Zig not found; skipping integration test");
            return;
        }

        var tempDir = Path.of("tmp/test/load_property_engine_inheritance_child");
        Files.createDirectories(tempDir);

        var hostClass = newEngineChildLoadHostClass();
        var module = new LirModule("load_property_engine_inheritance_child_module", List.of(hostClass));
        var buildResult = buildProject(tempDir, "load_property_engine_inheritance_child", module);

        var entrySource = Files.readString(tempDir.resolve("entry.c"));
        assertTrue(
                entrySource.contains("godot_Node_get_process_mode((godot_Node*)$control);"),
                "ENGINE child receiver should be cast to ENGINE parent owner type."
        );
        assertFalse(
                entrySource.contains("godot_Control_get_process_mode("),
                "Getter owner symbol should be resolved to nearest parent owner."
        );

        var runResult = runWithSceneAndScript(
                buildResult.artifacts(),
                "LoadPropertyEngineChildNode",
                hostClass.getName(),
                engineChildLoadScript()
        );
        var output = runResult.combinedOutput();
        assertTrue(runResult.stopSignalSeen(), "Godot run should emit stop signal.\nOutput:\n" + output);
        assertTrue(output.contains("engine child load property check passed."), "Load path should pass.\nOutput:\n" + output);
        assertFalse(output.contains("check failed"), "No check should fail.\nOutput:\n" + output);
    }

    @Test
    @DisplayName("STORE_PROPERTY should run ENGINE child receiver -> ENGINE parent property path in real engine")
    void storePropertyEngineChildReceiverShouldRunInRealGodot() throws IOException, InterruptedException {
        if (!hasZig()) {
            Assumptions.abort("Zig not found; skipping integration test");
            return;
        }

        var tempDir = Path.of("tmp/test/store_property_engine_inheritance_child");
        Files.createDirectories(tempDir);

        var hostClass = newEngineChildStoreHostClass();
        var module = new LirModule("store_property_engine_inheritance_child_module", List.of(hostClass));
        var buildResult = buildProject(tempDir, "store_property_engine_inheritance_child", module);

        var entrySource = Files.readString(tempDir.resolve("entry.c"));
        assertTrue(
                entrySource.contains("godot_Node_set_process_mode((godot_Node*)$control, $value);"),
                "ENGINE child receiver should be cast to ENGINE parent owner type."
        );
        assertFalse(
                entrySource.contains("godot_Control_set_process_mode("),
                "Setter owner symbol should be resolved to nearest parent owner."
        );

        var runResult = runWithSceneAndScript(
                buildResult.artifacts(),
                "StorePropertyEngineChildNode",
                hostClass.getName(),
                engineChildStoreScript()
        );
        var output = runResult.combinedOutput();
        assertTrue(runResult.stopSignalSeen(), "Godot run should emit stop signal.\nOutput:\n" + output);
        assertTrue(output.contains("engine child store property check passed."), "Store path should pass.\nOutput:\n" + output);
        assertFalse(output.contains("check failed"), "No check should fail.\nOutput:\n" + output);
    }

    @Test
    @DisplayName("LOAD_PROPERTY should run GDCC receiver -> ENGINE owner property path in real engine")
    void loadPropertyGdccReceiverToEngineOwnerShouldRunInRealGodot() throws IOException, InterruptedException {
        if (!hasZig()) {
            Assumptions.abort("Zig not found; skipping integration test");
            return;
        }

        var tempDir = Path.of("tmp/test/load_property_gdcc_to_engine_owner");
        Files.createDirectories(tempDir);

        var hostClass = newGdccBridgeLoadHostClass();
        var childClass = newGdccBridgeChildClass();
        var module = new LirModule("load_property_gdcc_to_engine_owner_module", List.of(hostClass, childClass));
        var buildResult = buildProject(tempDir, "load_property_gdcc_to_engine_owner", module);

        var entrySource = Files.readString(tempDir.resolve("entry.c"));
        assertTrue(
                entrySource.contains("godot_Node_get_process_mode((godot_Node*)gdcc_object_to_godot_object_ptr($obj, GDGdccEnginePropertyBridgeChild_object_ptr));"),
                "GDCC receiver should be converted with helper before ENGINE owner cast."
        );
        assertFalse(
                entrySource.contains("godot_Node_get_process_mode((godot_Node*)$obj);"),
                "GDCC wrapper pointer must not be cast directly to engine owner type."
        );

        var runResult = runWithSceneAndScript(
                buildResult.artifacts(),
                "LoadPropertyGdccBridgeNode",
                hostClass.getName(),
                gdccBridgeLoadScript()
        );
        var output = runResult.combinedOutput();
        assertTrue(runResult.stopSignalSeen(), "Godot run should emit stop signal.\nOutput:\n" + output);
        assertTrue(output.contains("gdcc bridge load property check passed."), "Bridge load path should pass.\nOutput:\n" + output);
        assertFalse(output.contains("check failed"), "No check should fail.\nOutput:\n" + output);
    }

    @Test
    @DisplayName("STORE_PROPERTY should run GDCC receiver -> ENGINE owner property path in real engine")
    void storePropertyGdccReceiverToEngineOwnerShouldRunInRealGodot() throws IOException, InterruptedException {
        if (!hasZig()) {
            Assumptions.abort("Zig not found; skipping integration test");
            return;
        }

        var tempDir = Path.of("tmp/test/store_property_gdcc_to_engine_owner");
        Files.createDirectories(tempDir);

        var hostClass = newGdccBridgeStoreHostClass();
        var childClass = newGdccBridgeChildClass();
        var module = new LirModule("store_property_gdcc_to_engine_owner_module", List.of(hostClass, childClass));
        var buildResult = buildProject(tempDir, "store_property_gdcc_to_engine_owner", module);

        var entrySource = Files.readString(tempDir.resolve("entry.c"));
        assertTrue(
                entrySource.contains("godot_Node_set_process_mode((godot_Node*)gdcc_object_to_godot_object_ptr($obj, GDGdccEnginePropertyBridgeChild_object_ptr), $value);"),
                "GDCC receiver should be converted with helper before ENGINE owner cast."
        );
        assertFalse(
                entrySource.contains("godot_Node_set_process_mode((godot_Node*)$obj, $value);"),
                "GDCC wrapper pointer must not be cast directly to engine owner type."
        );

        var runResult = runWithSceneAndScript(
                buildResult.artifacts(),
                "StorePropertyGdccBridgeNode",
                hostClass.getName(),
                gdccBridgeStoreScript()
        );
        var output = runResult.combinedOutput();
        assertTrue(runResult.stopSignalSeen(), "Godot run should emit stop signal.\nOutput:\n" + output);
        assertTrue(output.contains("gdcc bridge store property check passed."), "Bridge store path should pass.\nOutput:\n" + output);
        assertFalse(output.contains("check failed"), "No check should fail.\nOutput:\n" + output);
    }

    private static boolean hasZig() {
        return ZigUtil.findZig() != null;
    }

    private static CBuildResult buildProject(@NotNull Path tempDir,
                                             @NotNull String projectName,
                                             @NotNull LirModule module) throws IOException {
        var projectInfo = new CProjectInfo(
                projectName,
                GodotVersion.V451,
                tempDir,
                COptimizationLevel.DEBUG,
                TargetPlatform.getNativePlatform()
        );
        var builder = new CProjectBuilder();
        builder.initProject(projectInfo);

        var api = ExtensionApiLoader.loadVersion(GodotVersion.V451);
        var codegen = new CCodegen();
        codegen.prepare(new CodegenContext(projectInfo, new ClassRegistry(api)), module);

        var buildResult = builder.buildProject(projectInfo, codegen);
        assertTrue(buildResult.success(), "Compilation should succeed. Build log:\n" + buildResult.buildLog());
        assertFalse(buildResult.artifacts().isEmpty(), "Compilation should produce extension artifacts.");
        return buildResult;
    }

    private static GodotGdextensionTestRunner.GodotRunResult runWithSceneAndScript(@NotNull List<Path> artifacts,
                                                                                    @NotNull String sceneName,
                                                                                    @NotNull String className,
                                                                                    @NotNull String script) throws IOException, InterruptedException {
        var runner = new GodotGdextensionTestRunner(Path.of("test_project"));
        runner.prepareProject(new GodotGdextensionTestRunner.ProjectSetup(
                artifacts,
                List.of(new GodotGdextensionTestRunner.SceneNodeSpec(sceneName, className, ".", Map.of())),
                new GodotGdextensionTestRunner.TestScriptSpec(script)
        ));
        return runner.run(true);
    }

    private static LirClassDef newEngineChildLoadHostClass() {
        var clazz = new LirClassDef("GDLoadPropertyEngineChildNode", "Node");
        clazz.setSourceFile("load_property_engine_child.gd");

        var func = newLirMethod(clazz, "load_engine_parent_process_mode", GdIntType.INT);
        func.addParameter(new LirParameterDef("control", new GdObjectType("Control"), null, func));
        func.createAndAddVariable("mode", GdIntType.INT);
        var entry = func.getBasicBlock("entry");
        entry.instructions().add(new LoadPropertyInsn("mode", "process_mode", "control"));
        entry.instructions().add(new ReturnInsn("mode"));
        clazz.addFunction(func);
        return clazz;
    }

    private static LirClassDef newEngineChildStoreHostClass() {
        var clazz = new LirClassDef("GDStorePropertyEngineChildNode", "Node");
        clazz.setSourceFile("store_property_engine_child.gd");

        var func = newLirMethod(clazz, "store_engine_parent_process_mode", GdVoidType.VOID);
        func.addParameter(new LirParameterDef("control", new GdObjectType("Control"), null, func));
        func.addParameter(new LirParameterDef("value", GdIntType.INT, null, func));
        var entry = func.getBasicBlock("entry");
        entry.instructions().add(new StorePropertyInsn("process_mode", "control", "value"));
        entry.instructions().add(new ReturnInsn(null));
        clazz.addFunction(func);
        return clazz;
    }

    private static LirClassDef newGdccBridgeLoadHostClass() {
        var clazz = new LirClassDef("GDLoadPropertyGdccBridgeNode", "Node");
        clazz.setSourceFile("load_property_gdcc_bridge.gd");

        var func = newLirMethod(clazz, "load_engine_process_mode_from_gdcc", GdIntType.INT);
        func.addParameter(new LirParameterDef("obj", new GdObjectType("GDGdccEnginePropertyBridgeChild"), null, func));
        func.createAndAddVariable("mode", GdIntType.INT);
        var entry = func.getBasicBlock("entry");
        entry.instructions().add(new LoadPropertyInsn("mode", "process_mode", "obj"));
        entry.instructions().add(new ReturnInsn("mode"));
        clazz.addFunction(func);
        return clazz;
    }

    private static LirClassDef newGdccBridgeStoreHostClass() {
        var clazz = new LirClassDef("GDStorePropertyGdccBridgeNode", "Node");
        clazz.setSourceFile("store_property_gdcc_bridge.gd");

        var func = newLirMethod(clazz, "store_engine_process_mode_to_gdcc", GdVoidType.VOID);
        func.addParameter(new LirParameterDef("obj", new GdObjectType("GDGdccEnginePropertyBridgeChild"), null, func));
        func.addParameter(new LirParameterDef("value", GdIntType.INT, null, func));
        var entry = func.getBasicBlock("entry");
        entry.instructions().add(new StorePropertyInsn("process_mode", "obj", "value"));
        entry.instructions().add(new ReturnInsn(null));
        clazz.addFunction(func);
        return clazz;
    }

    private static LirClassDef newGdccBridgeChildClass() {
        var clazz = new LirClassDef("GDGdccEnginePropertyBridgeChild", "Node");
        clazz.setSourceFile("property_gdcc_engine_bridge_child.gd");
        return clazz;
    }

    private static LirFunctionDef newLirMethod(@NotNull LirClassDef owner,
                                               @NotNull String name,
                                               @NotNull GdType returnType) {
        var func = new LirFunctionDef(name);
        func.setReturnType(returnType);
        func.addParameter(new LirParameterDef("self", new GdObjectType(owner.getName()), null, func));
        func.addBasicBlock(new LirBasicBlock("entry"));
        func.setEntryBlockId("entry");
        return func;
    }

    private static String engineChildLoadScript() {
        return """
                extends Node
                
                const TARGET_NODE_NAME = "LoadPropertyEngineChildNode"
                
                func _ready() -> void:
                    var target = get_parent().get_node_or_null(TARGET_NODE_NAME)
                    if target == null:
                        push_error("Target node missing.")
                        return

                    var control = Control.new()
                    control.process_mode = Node.PROCESS_MODE_ALWAYS
                    var result = int(target.call("load_engine_parent_process_mode", control))
                    if result == Node.PROCESS_MODE_ALWAYS:
                        print("engine child load property check passed.")
                    else:
                        push_error("engine child load property check failed.")
                """;
    }

    private static String engineChildStoreScript() {
        return """
                extends Node
                
                const TARGET_NODE_NAME = "StorePropertyEngineChildNode"
                
                func _ready() -> void:
                    var target = get_parent().get_node_or_null(TARGET_NODE_NAME)
                    if target == null:
                        push_error("Target node missing.")
                        return

                    var control = Control.new()
                    control.process_mode = Node.PROCESS_MODE_INHERIT
                    target.call("store_engine_parent_process_mode", control, Node.PROCESS_MODE_DISABLED)
                    if control.process_mode == Node.PROCESS_MODE_DISABLED:
                        print("engine child store property check passed.")
                    else:
                        push_error("engine child store property check failed.")
                """;
    }

    private static String gdccBridgeLoadScript() {
        return """
                extends Node
                
                const TARGET_NODE_NAME = "LoadPropertyGdccBridgeNode"
                
                func _ready() -> void:
                    var target = get_parent().get_node_or_null(TARGET_NODE_NAME)
                    if target == null:
                        push_error("Target node missing.")
                        return

                    var obj = GDGdccEnginePropertyBridgeChild.new()
                    obj.process_mode = Node.PROCESS_MODE_ALWAYS
                    var result = int(target.call("load_engine_process_mode_from_gdcc", obj))
                    if result == Node.PROCESS_MODE_ALWAYS:
                        print("gdcc bridge load property check passed.")
                    else:
                        push_error("gdcc bridge load property check failed.")
                """;
    }

    private static String gdccBridgeStoreScript() {
        return """
                extends Node
                
                const TARGET_NODE_NAME = "StorePropertyGdccBridgeNode"
                
                func _ready() -> void:
                    var target = get_parent().get_node_or_null(TARGET_NODE_NAME)
                    if target == null:
                        push_error("Target node missing.")
                        return

                    var obj = GDGdccEnginePropertyBridgeChild.new()
                    obj.process_mode = Node.PROCESS_MODE_INHERIT
                    target.call("store_engine_process_mode_to_gdcc", obj, Node.PROCESS_MODE_PAUSABLE)
                    if obj.process_mode == Node.PROCESS_MODE_PAUSABLE:
                        print("gdcc bridge store property check passed.")
                    else:
                        push_error("gdcc bridge store property check failed.")
                """;
    }
}
