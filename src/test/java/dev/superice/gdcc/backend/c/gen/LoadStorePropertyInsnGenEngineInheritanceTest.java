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
import dev.superice.gdcc.lir.LirPropertyDef;
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

    @Test
    @DisplayName("getter-self on engine-inherited class should run in real engine with correct object lifecycle order")
    void getterSelfOnEngineInheritedClassShouldRunWithObjectLifecycleOrder() throws IOException, InterruptedException {
        if (!hasZig()) {
            Assumptions.abort("Zig not found; skipping integration test");
            return;
        }

        var tempDir = Path.of("tmp/test/getter_self_engine_inheritance");
        Files.createDirectories(tempDir);

        var hostClass = newSelfAccessorEngineInheritanceHostClass();
        var module = new LirModule("getter_self_engine_inheritance_module", List.of(hostClass));
        var buildResult = buildProject(tempDir, "getter_self_engine_inheritance", module);

        var entrySource = Files.readString(tempDir.resolve("entry.c"));
        var getterSource = extractFunctionSource(
                entrySource,
                "GDSelfAccessorEngineInheritanceNode__field_getter_obj"
        );
        assertTrue(getterSource.contains("$self->obj"), "Getter-self should read backing field directly.");
        assertFalse(
                getterSource.contains("GDSelfAccessorEngineInheritanceNode__field_getter_obj($self)"),
                "Getter-self path should not recurse into getter call."
        );
        assertTrue(
                getterSource.contains("__gdcc_tmp_old_obj_"),
                "Second getter-self write should capture old object value."
        );
        var firstAssignIndex = getterSource.indexOf("$tmp = $self->obj;");
        var secondAssignIndex = getterSource.indexOf("$tmp = $self->obj;", firstAssignIndex + 1);
        assertTrue(firstAssignIndex >= 0, "Getter-self should assign target from backing field.");
        assertTrue(secondAssignIndex > firstAssignIndex, "Getter-self test should execute field load twice.");
        var captureOldIndex = getterSource.lastIndexOf(" = $tmp;", secondAssignIndex);
        assertTrue(captureOldIndex >= 0, "Second getter-self write should capture old target before overwrite.");
        assertTrue(captureOldIndex < secondAssignIndex, "Old target capture should happen before second assignment.");
        var ownIndex = getterSource.indexOf("own_object($tmp);", secondAssignIndex);
        assertTrue(ownIndex > secondAssignIndex, "BORROWED field read should be owned after assignment.");
        var releaseOldIndex = getterSource.indexOf("release_object(__gdcc_tmp_old_obj_", ownIndex);
        assertTrue(releaseOldIndex > ownIndex, "Captured old value should be released after owning new value.");

        var runResult = runWithSceneAndScript(
                buildResult.artifacts(),
                "GetterSelfEngineInheritanceNode",
                hostClass.getName(),
                getterSelfEngineInheritanceScript()
        );
        var output = runResult.combinedOutput();
        assertTrue(runResult.stopSignalSeen(), "Godot run should emit stop signal.\nOutput:\n" + output);
        assertTrue(
                output.contains("engine inheritance getter-self check passed."),
                "Getter-self runtime behavior should pass.\nOutput:\n" + output
        );
        assertFalse(output.contains("check failed"), "No check should fail.\nOutput:\n" + output);
    }

    @Test
    @DisplayName("setter-self on engine-inherited class should run in real engine with correct object lifecycle order")
    void setterSelfOnEngineInheritedClassShouldRunWithObjectLifecycleOrder() throws IOException, InterruptedException {
        if (!hasZig()) {
            Assumptions.abort("Zig not found; skipping integration test");
            return;
        }

        var tempDir = Path.of("tmp/test/setter_self_engine_inheritance");
        Files.createDirectories(tempDir);

        var hostClass = newSelfAccessorEngineInheritanceHostClass();
        var module = new LirModule("setter_self_engine_inheritance_module", List.of(hostClass));
        var buildResult = buildProject(tempDir, "setter_self_engine_inheritance", module);

        var entrySource = Files.readString(tempDir.resolve("entry.c"));
        var setterSource = extractFunctionSource(
                entrySource,
                "GDSelfAccessorEngineInheritanceNode__field_setter_obj"
        );
        assertFalse(
                setterSource.contains("GDSelfAccessorEngineInheritanceNode__field_setter_obj($self, $value)"),
                "Setter-self path should not recurse into setter call."
        );
        assertTrue(setterSource.contains("__gdcc_tmp_old_obj_"), "Setter-self should capture old slot value.");
        var assignIndex = setterSource.indexOf("$self->obj = $value;");
        assertTrue(assignIndex >= 0, "Setter-self should write directly to backing field.");
        var captureOldIndex = setterSource.lastIndexOf(" = $self->obj;", assignIndex);
        assertTrue(captureOldIndex >= 0, "Setter-self should capture old value before overwrite.");
        assertTrue(captureOldIndex < assignIndex, "Old value capture must happen before assignment.");
        var ownIndex = setterSource.indexOf("own_object($self->obj);", assignIndex);
        assertTrue(ownIndex > assignIndex, "Setter-self should own BORROWED value after assignment.");
        var releaseOldIndex = setterSource.indexOf("release_object(__gdcc_tmp_old_obj_", ownIndex);
        assertTrue(releaseOldIndex > ownIndex, "Setter-self should release captured old value last.");

        var runResult = runWithSceneAndScript(
                buildResult.artifacts(),
                "SetterSelfEngineInheritanceNode",
                hostClass.getName(),
                setterSelfEngineInheritanceScript()
        );
        var output = runResult.combinedOutput();
        assertTrue(runResult.stopSignalSeen(), "Godot run should emit stop signal.\nOutput:\n" + output);
        assertTrue(
                output.contains("engine inheritance setter-self check passed."),
                "Setter-self runtime behavior should pass.\nOutput:\n" + output
        );
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
        entry.appendInstruction(new LoadPropertyInsn("mode", "process_mode", "control"));
        entry.appendInstruction(new ReturnInsn("mode"));
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
        entry.appendInstruction(new StorePropertyInsn("process_mode", "control", "value"));
        entry.appendInstruction(new ReturnInsn(null));
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
        entry.appendInstruction(new LoadPropertyInsn("mode", "process_mode", "obj"));
        entry.appendInstruction(new ReturnInsn("mode"));
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
        entry.appendInstruction(new StorePropertyInsn("process_mode", "obj", "value"));
        entry.appendInstruction(new ReturnInsn(null));
        clazz.addFunction(func);
        return clazz;
    }

    private static LirClassDef newGdccBridgeChildClass() {
        var clazz = new LirClassDef("GDGdccEnginePropertyBridgeChild", "Node");
        clazz.setSourceFile("property_gdcc_engine_bridge_child.gd");
        return clazz;
    }

    private static LirClassDef newSelfAccessorEngineInheritanceHostClass() {
        var clazz = new LirClassDef("GDSelfAccessorEngineInheritanceNode", "Node");
        clazz.setSourceFile("self_accessor_engine_inheritance.gd");
        clazz.addProperty(new LirPropertyDef(
                "obj",
                new GdObjectType("RefCounted"),
                false,
                null,
                "_field_getter_obj",
                "_field_setter_obj",
                Map.of()
        ));
        clazz.addFunction(newSelfAccessorGetterFunction(clazz));
        clazz.addFunction(newSelfAccessorSetterFunction(clazz));
        return clazz;
    }

    private static LirFunctionDef newSelfAccessorGetterFunction(@NotNull LirClassDef owner) {
        var func = newLirMethod(owner, "_field_getter_obj", new GdObjectType("RefCounted"));
        func.createAndAddVariable("tmp", new GdObjectType("RefCounted"));
        var entry = func.getBasicBlock("entry");
        entry.appendInstruction(new LoadPropertyInsn("tmp", "obj", "self"));
        entry.appendInstruction(new LoadPropertyInsn("tmp", "obj", "self"));
        entry.appendInstruction(new ReturnInsn("tmp"));
        return func;
    }

    private static LirFunctionDef newSelfAccessorSetterFunction(@NotNull LirClassDef owner) {
        var func = newLirMethod(owner, "_field_setter_obj", GdVoidType.VOID);
        func.addParameter(new LirParameterDef("value", new GdObjectType("RefCounted"), null, func));
        var entry = func.getBasicBlock("entry");
        entry.appendInstruction(new StorePropertyInsn("obj", "self", "value"));
        entry.appendInstruction(new ReturnInsn(null));
        return func;
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

    private static @NotNull String extractFunctionSource(@NotNull String entrySource,
                                                         @NotNull String functionName) {
        var signature = functionName + "(";
        var startIndex = entrySource.indexOf(signature);
        assertTrue(startIndex >= 0, "Generated function not found: " + functionName);
        var braceStart = entrySource.indexOf('{', startIndex);
        assertTrue(braceStart >= 0, "Cannot locate function body start: " + functionName);

        var braceDepth = 0;
        for (var i = braceStart; i < entrySource.length(); i++) {
            var ch = entrySource.charAt(i);
            if (ch == '{') {
                braceDepth++;
                continue;
            }
            if (ch == '}') {
                braceDepth--;
                if (braceDepth == 0) {
                    return entrySource.substring(startIndex, i + 1);
                }
            }
        }
        throw new IllegalStateException("Cannot locate function body end: " + functionName);
    }

    private static String getterSelfEngineInheritanceScript() {
        return """
                extends Node
                
                const TARGET_NODE_NAME = "GetterSelfEngineInheritanceNode"
                
                func _ready() -> void:
                    var target = get_parent().get_node_or_null(TARGET_NODE_NAME)
                    if target == null:
                        push_error("Target node missing.")
                        return
                
                    var marker = RefCounted.new()
                    target.obj = marker
                    var got = target.obj
                    if got == null:
                        push_error("engine inheritance getter-self check failed.")
                        return
                    if got != marker:
                        push_error("engine inheritance getter-self check failed.")
                        return
                    marker = null
                    if got == null:
                        push_error("engine inheritance getter-self check failed.")
                        return
                
                    print("engine inheritance getter-self check passed.")
                """;
    }

    private static String setterSelfEngineInheritanceScript() {
        return """
                extends Node
                
                const TARGET_NODE_NAME = "SetterSelfEngineInheritanceNode"
                
                func _ready() -> void:
                    var target = get_parent().get_node_or_null(TARGET_NODE_NAME)
                    if target == null:
                        push_error("Target node missing.")
                        return
                
                    var first = RefCounted.new()
                    var second = RefCounted.new()
                    target.obj = first
                    target.obj = second
                    first = null
                
                    var read_back = target.obj
                    if read_back != second:
                        push_error("engine inheritance setter-self check failed.")
                        return
                    second = null
                    if read_back == null:
                        push_error("engine inheritance setter-self check failed.")
                        return
                
                    print("engine inheritance setter-self check passed.")
                """;
    }
}
