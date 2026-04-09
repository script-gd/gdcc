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
import dev.superice.gdcc.lir.LirPropertyDef;
import dev.superice.gdcc.lir.insn.CallMethodInsn;
import dev.superice.gdcc.lir.insn.ConstructArrayInsn;
import dev.superice.gdcc.lir.insn.ConstructBuiltinInsn;
import dev.superice.gdcc.lir.insn.ConstructDictionaryInsn;
import dev.superice.gdcc.lir.insn.ConstructObjectInsn;
import dev.superice.gdcc.lir.insn.LiteralFloatInsn;
import dev.superice.gdcc.lir.insn.LiteralIntInsn;
import dev.superice.gdcc.lir.insn.LiteralStringInsn;
import dev.superice.gdcc.lir.insn.ReturnInsn;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.type.GdArrayType;
import dev.superice.gdcc.type.GdDictionaryType;
import dev.superice.gdcc.type.GdFloatType;
import dev.superice.gdcc.type.GdIntType;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdPackedNumericArrayType;
import dev.superice.gdcc.type.GdPackedStringArrayType;
import dev.superice.gdcc.type.GdPackedVectorArrayType;
import dev.superice.gdcc.type.GdStringType;
import dev.superice.gdcc.type.GdStringNameType;
import dev.superice.gdcc.type.GdTransform2DType;
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

class CConstructInsnGenEngineTest {
    @Test
    @DisplayName("construct insns should generate runnable typed container and builtin constructors in real engine")
    void constructInsnsShouldRunInRealGodot() throws IOException, InterruptedException {
        if (!hasZig()) {
            Assumptions.abort("Zig not found; skipping integration test");
            return;
        }

        var tempDir = Path.of("tmp/test/construct_engine");
        Files.createDirectories(tempDir);

        var projectInfo = new CProjectInfo(
                "construct_engine",
                GodotVersion.V451,
                tempDir,
                COptimizationLevel.DEBUG,
                TargetPlatform.getNativePlatform()
        );
        var builder = new CProjectBuilder();
        builder.initProject(projectInfo);

        var constructClass = newConstructEngineClass();
        var module = new LirModule("construct_engine_module", List.of(constructClass));
        var api = ExtensionApiLoader.loadVersion(GodotVersion.V451);
        var codegen = new CCodegen();
        codegen.prepare(new CodegenContext(projectInfo, new ClassRegistry(api)), module);

        var buildResult = builder.buildProject(projectInfo, codegen);
        assertTrue(buildResult.success(), "Compilation should succeed. Build log:\n" + buildResult.buildLog());
        assertFalse(buildResult.artifacts().isEmpty(), "Compilation should produce extension artifacts.");
        var entrySource = Files.readString(tempDir.resolve("entry.c"));
        assertTrue(entrySource.contains("make_typed_array_explicit"), "Typed array method should be emitted into C source.");
        assertTrue(entrySource.contains("make_typed_dictionary_explicit"), "Typed dictionary method should be emitted into C source.");
        assertTrue(
                entrySource.contains("godot_new_Array_with_Array_int_StringName_Variant"),
                "Typed array constructor call should be generated in C source."
        );
        assertTrue(
                entrySource.contains("godot_new_Dictionary_with_Dictionary_int_StringName_Variant_int_StringName_Variant"),
                "Typed dictionary constructor call should be generated in C source."
        );

        var runner = new GodotGdextensionTestRunner(Path.of("test_project"));
        runner.prepareProject(new GodotGdextensionTestRunner.ProjectSetup(
                buildResult.artifacts(),
                List.of(new GodotGdextensionTestRunner.SceneNodeSpec(
                        "ConstructNode",
                        constructClass.getName(),
                        ".",
                        Map.of()
                )),
                new GodotGdextensionTestRunner.TestScriptSpec(testScript())
        ));

        var runResult = runner.run(true);
        var combinedOutput = runResult.combinedOutput();

        assertTrue(runResult.stopSignalSeen(), "Godot run should emit stop signal.\nOutput:\n" + combinedOutput);
        assertTrue(combinedOutput.contains("explicit transform check passed."), "explicit transform should pass.\nOutput:\n" + combinedOutput);
        assertTrue(combinedOutput.contains("prepare transform check passed."), "prepare transform should pass.\nOutput:\n" + combinedOutput);
        assertTrue(combinedOutput.contains("explicit generic array check passed."), "explicit generic array should pass.\nOutput:\n" + combinedOutput);
        assertTrue(combinedOutput.contains("prepare generic array check passed."), "prepare generic array should pass.\nOutput:\n" + combinedOutput);
        assertTrue(combinedOutput.contains("explicit generic dictionary check passed."), "explicit generic dictionary should pass.\nOutput:\n" + combinedOutput);
        assertTrue(combinedOutput.contains("prepare generic dictionary check passed."), "prepare generic dictionary should pass.\nOutput:\n" + combinedOutput);
        assertFalse(combinedOutput.contains("check failed"), "No check should fail.\nOutput:\n" + combinedOutput);
    }

    @Test
    @DisplayName("construct_array packed insns should generate runnable Packed*Array constructors across all supported packed types")
    void constructPackedArrayInsnsShouldRunInRealGodot() throws IOException, InterruptedException {
        if (!hasZig()) {
            Assumptions.abort("Zig not found; skipping integration test");
            return;
        }

        var tempDir = Path.of("tmp/test/construct_engine_packed_all");
        Files.createDirectories(tempDir);

        var projectInfo = new CProjectInfo(
                "construct_engine_packed_all",
                GodotVersion.V451,
                tempDir,
                COptimizationLevel.DEBUG,
                TargetPlatform.getNativePlatform()
        );
        var builder = new CProjectBuilder();
        builder.initProject(projectInfo);

        var constructClass = newPackedConstructEngineClass();
        var module = new LirModule("construct_engine_packed_all_module", List.of(constructClass));
        var api = ExtensionApiLoader.loadVersion(GodotVersion.V451);
        var codegen = new CCodegen();
        codegen.prepare(new CodegenContext(projectInfo, new ClassRegistry(api)), module);

        var buildResult = builder.buildProject(projectInfo, codegen);
        assertTrue(buildResult.success(), "Compilation should succeed. Build log:\n" + buildResult.buildLog());
        assertFalse(buildResult.artifacts().isEmpty(), "Compilation should produce extension artifacts.");
        var entrySource = Files.readString(tempDir.resolve("entry.c"));
        for (var packedCase : packedEngineCases()) {
            assertTrue(
                    entrySource.contains(packedCase.explicitMethodName()),
                    () -> "Explicit method should be emitted for " + packedCase.builtinName() + "."
            );
            assertTrue(
                    entrySource.contains(packedCase.prepareMethodName()),
                    () -> "Prepare method should be emitted for " + packedCase.builtinName() + "."
            );
            assertTrue(
                    entrySource.contains(packedCase.constructorCall()),
                    () -> packedCase.builtinName() + " constructor call should be generated in C source."
            );
        }

        var runner = new GodotGdextensionTestRunner(Path.of("test_project"));
        runner.prepareProject(new GodotGdextensionTestRunner.ProjectSetup(
                buildResult.artifacts(),
                List.of(new GodotGdextensionTestRunner.SceneNodeSpec(
                        "ConstructNode",
                        constructClass.getName(),
                        ".",
                        Map.of()
                )),
                new GodotGdextensionTestRunner.TestScriptSpec(packedTestScript())
        ));

        var runResult = runner.run(true);
        var combinedOutput = runResult.combinedOutput();

        assertTrue(runResult.stopSignalSeen(), "Godot run should emit stop signal.\nOutput:\n" + combinedOutput);
        for (var packedCase : packedEngineCases()) {
            assertTrue(
                    combinedOutput.contains("explicit " + packedCase.label() + " check passed."),
                    () -> "explicit packed check should pass for " + packedCase.builtinName() + ".\nOutput:\n" + combinedOutput
            );
            assertTrue(
                    combinedOutput.contains("prepare " + packedCase.label() + " check passed."),
                    () -> "prepare packed check should pass for " + packedCase.builtinName() + ".\nOutput:\n" + combinedOutput
            );
        }
        assertFalse(combinedOutput.contains("check failed"), "No check should fail.\nOutput:\n" + combinedOutput);
    }

    @Test
    @DisplayName("construct_object should support engine and gdcc object lifecycles across refcounted and non-refcounted routes")
    void constructObjectInsnsShouldRunInRealGodot() throws IOException, InterruptedException {
        if (!hasZig()) {
            Assumptions.abort("Zig not found; skipping integration test");
            return;
        }

        var tempDir = Path.of("tmp/test/construct_engine_object");
        Files.createDirectories(tempDir);

        var projectInfo = new CProjectInfo(
                "construct_engine_object",
                GodotVersion.V451,
                tempDir,
                COptimizationLevel.DEBUG,
                TargetPlatform.getNativePlatform()
        );
        var builder = new CProjectBuilder();
        builder.initProject(projectInfo);

        var moduleClasses = newObjectConstructEngineClasses();
        var constructNodeClass = moduleClasses.getFirst();
        var module = new LirModule("construct_engine_object_module", moduleClasses);
        var api = ExtensionApiLoader.loadVersion(GodotVersion.V451);
        var codegen = new CCodegen();
        codegen.prepare(new CodegenContext(projectInfo, new ClassRegistry(api)), module);

        var buildResult = builder.buildProject(projectInfo, codegen);
        assertTrue(buildResult.success(), "Compilation should succeed. Build log:\n" + buildResult.buildLog());
        assertFalse(buildResult.artifacts().isEmpty(), "Compilation should produce extension artifacts.");
        var entrySource = Files.readString(tempDir.resolve("entry.c"));
        assertTrue(
                entrySource.contains("godot_new_Node()"),
                "Engine non-refcounted object constructor call should be generated."
        );
        assertTrue(
                entrySource.contains("godot_new_RefCounted()"),
                "Engine RefCounted constructor call should be generated."
        );
        assertTrue(
                entrySource.contains(
                        "gdcc_object_from_godot_object_ptr(GDConstructRuntimePlainObject_class_create_instance(NULL, true))"
                ),
                "GDCC non-refcounted object constructor call should be converted from Godot pointer into gdcc wrapper."
        );
        assertTrue(
                entrySource.contains(
                        "gdcc_object_from_godot_object_ptr(gdcc_ref_counted_init_raw(GDConstructRuntimeCountedWorker_class_create_instance(NULL, false), true))"
                ),
                "GDCC RefCounted object constructor call should be externally wrapped with RefCounted init before gdcc wrapper conversion."
        );
        var plainCreateInstanceBody = resolveCreateInstanceBody(entrySource, "GDConstructRuntimePlainObject");
        var countedCreateInstanceBody = resolveCreateInstanceBody(entrySource, "GDConstructRuntimeCountedWorker");
        assertFalse(
                plainCreateInstanceBody.contains("gdcc_ref_counted_init_raw("),
                "Non-refcounted GDCC create_instance should not initialize RefCounted state.\n" + plainCreateInstanceBody
        );
        assertFalse(
                countedCreateInstanceBody.contains("gdcc_ref_counted_init_raw("),
                "RefCounted GDCC create_instance itself should remain raw; external C callers own init.\n" + countedCreateInstanceBody
        );
        assertFalse(
                resolveFunctionBody(entrySource, "GDConstructObjectEngineNode_make_engine_node_class_name")
                        .contains("try_destroy_object($node);"),
                "Engine non-ref local should not be auto-destroyed."
        );
        assertTrue(
                resolveFunctionBody(entrySource, "GDConstructObjectEngineNode_measure_engine_ref_counted_reference_count")
                        .contains("release_object($ref_counted);"),
                "Engine RefCounted local release path should be emitted."
        );
        assertFalse(
                resolveFunctionBody(entrySource, "GDConstructObjectEngineNode_make_gdcc_plain_object_label")
                        .contains("try_destroy_object(gdcc_object_to_godot_object_ptr($plain_object, GDConstructRuntimePlainObject_object_ptr));"),
                "GDCC plain-object local should not be auto-destroyed."
        );
        assertTrue(
                resolveFunctionBody(entrySource, "GDConstructObjectEngineNode_measure_gdcc_counted_worker_reference_count")
                        .contains("release_object(gdcc_object_to_godot_object_ptr($worker, GDConstructRuntimeCountedWorker_object_ptr));"),
                "GDCC RefCounted local release path should be emitted."
        );

        var runner = new GodotGdextensionTestRunner(Path.of("test_project"));
        runner.prepareProject(new GodotGdextensionTestRunner.ProjectSetup(
                buildResult.artifacts(),
                List.of(new GodotGdextensionTestRunner.SceneNodeSpec(
                        "ConstructObjectNode",
                        constructNodeClass.getName(),
                        ".",
                        Map.of()
                )),
                new GodotGdextensionTestRunner.TestScriptSpec(objectConstructTestScript())
        ));

        var runResult = runner.run(true);
        var combinedOutput = runResult.combinedOutput();

        assertTrue(runResult.stopSignalSeen(), "Godot run should emit stop signal.\nOutput:\n" + combinedOutput);
        assertTrue(
                combinedOutput.contains("engine non-ref object construct check passed."),
                "Engine non-ref object construct check should pass.\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("engine refcounted construct check passed."),
                "Engine refcounted construct check should pass.\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("gdcc non-ref object construct check passed."),
                "GDCC non-ref object construct check should pass.\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("gdcc refcounted ping check passed."),
                "GDCC refcounted ping check should pass.\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("gdcc refcounted reference count check passed."),
                "GDCC refcounted reference count check should pass.\nOutput:\n" + combinedOutput
        );
        assertFalse(combinedOutput.contains("check failed"), "No check should fail.\nOutput:\n" + combinedOutput);
    }

    @Test
    @DisplayName("default property init path should generate and run Packed*Array field init functions in real engine")
    void defaultPackedPropertyInitShouldRunInRealGodot() throws IOException, InterruptedException {
        if (!hasZig()) {
            Assumptions.abort("Zig not found; skipping integration test");
            return;
        }

        var tempDir = Path.of("tmp/test/construct_engine_packed_property_init");
        Files.createDirectories(tempDir);

        var projectInfo = new CProjectInfo(
                "construct_engine_packed_property_init",
                GodotVersion.V451,
                tempDir,
                COptimizationLevel.DEBUG,
                TargetPlatform.getNativePlatform()
        );
        var builder = new CProjectBuilder();
        builder.initProject(projectInfo);

        var constructClass = newPackedPropertyInitEngineClass();
        var module = new LirModule("construct_engine_packed_property_init_module", List.of(constructClass));
        var api = ExtensionApiLoader.loadVersion(GodotVersion.V451);
        var codegen = new CCodegen();
        codegen.prepare(new CodegenContext(projectInfo, new ClassRegistry(api)), module);

        var buildResult = builder.buildProject(projectInfo, codegen);
        assertTrue(buildResult.success(), "Compilation should succeed. Build log:\n" + buildResult.buildLog());
        assertFalse(buildResult.artifacts().isEmpty(), "Compilation should produce extension artifacts.");
        var entrySource = Files.readString(tempDir.resolve("entry.c"));
        assertTrue(
                entrySource.contains("GDConstructPackedPropertyInitNode_class_constructor"),
                "Class constructor should be emitted for packed property init class."
        );
        for (var packedCase : packedEngineCases()) {
            assertTrue(
                    entrySource.contains("_field_init_" + packedCase.propertyName()),
                    () -> "Default init function should be generated for property " + packedCase.propertyName()
            );
            assertTrue(
                    entrySource.contains("self->" + packedCase.propertyName() + " = "),
                    () -> "Class constructor should assign property " + packedCase.propertyName() + " from init function."
            );
            assertTrue(
                    entrySource.contains(packedCase.constructorCall()),
                    () -> packedCase.builtinName() + " constructor call should appear in field init path."
            );
        }

        var runner = new GodotGdextensionTestRunner(Path.of("test_project"));
        runner.prepareProject(new GodotGdextensionTestRunner.ProjectSetup(
                buildResult.artifacts(),
                List.of(new GodotGdextensionTestRunner.SceneNodeSpec(
                        "ConstructNode",
                        constructClass.getName(),
                        ".",
                        Map.of()
                )),
                new GodotGdextensionTestRunner.TestScriptSpec(packedPropertyInitTestScript())
        ));

        var runResult = runner.run(true);
        var combinedOutput = runResult.combinedOutput();

        assertTrue(runResult.stopSignalSeen(), "Godot run should emit stop signal.\nOutput:\n" + combinedOutput);
        for (var packedCase : packedEngineCases()) {
            assertTrue(
                    combinedOutput.contains("default " + packedCase.label() + " property init check passed."),
                    () -> "default property init check should pass for " + packedCase.builtinName() + ".\nOutput:\n" + combinedOutput
            );
        }
        assertFalse(combinedOutput.contains("check failed"), "No check should fail.\nOutput:\n" + combinedOutput);
    }

    private static boolean hasZig() {
        return ZigUtil.findZig() != null;
    }

    private static LirClassDef newConstructEngineClass() {
        var clazz = new LirClassDef("GDConstructEngineNode", "Node");
        clazz.setSourceFile("construct_engine.gd");

        var selfType = new GdObjectType(clazz.getName());
        clazz.addFunction(newExplicitTransformFunction(selfType));
        clazz.addFunction(newPrepareTransformFunction(selfType));
        clazz.addFunction(newExplicitGenericArrayFunction(selfType));
        clazz.addFunction(newPrepareGenericArrayFunction(selfType));
        clazz.addFunction(newExplicitGenericDictionaryFunction(selfType));
        clazz.addFunction(newPrepareGenericDictionaryFunction(selfType));
        clazz.addFunction(newExplicitTypedArrayFunction(selfType));
        clazz.addFunction(newPrepareTypedArrayFunction(selfType));
        clazz.addFunction(newExplicitTypedDictionaryFunction(selfType));
        clazz.addFunction(newPrepareTypedDictionaryFunction(selfType));
        return clazz;
    }

    private static LirClassDef newPackedConstructEngineClass() {
        var clazz = new LirClassDef("GDConstructPackedEngineNode", "Node");
        clazz.setSourceFile("construct_engine_packed_extra.gd");

        var selfType = new GdObjectType(clazz.getName());
        for (var packedCase : packedEngineCases()) {
            clazz.addFunction(newExplicitPackedArrayFunction(packedCase.explicitMethodName(), packedCase.type(), selfType));
            clazz.addFunction(newPreparePackedArrayFunction(packedCase.prepareMethodName(), packedCase.type(), selfType));
        }
        return clazz;
    }

    private static List<LirClassDef> newObjectConstructEngineClasses() {
        var constructNode = new LirClassDef("GDConstructObjectEngineNode", "Node");
        constructNode.setSourceFile("construct_engine_object.gd");
        var constructNodeType = new GdObjectType(constructNode.getName());
        constructNode.addFunction(newConstructEngineNodeFunction(constructNodeType));
        constructNode.addFunction(newMeasureEngineRefCountedReferenceCountFunction(constructNodeType));
        constructNode.addFunction(newConstructGdccPlainObjectLabelFunction(constructNodeType));
        constructNode.addFunction(newConstructGdccCountedWorkerPingFunction(constructNodeType));
        constructNode.addFunction(newMeasureGdccCountedWorkerReferenceCountFunction(constructNodeType));

        var plainObjectClass = new LirClassDef("GDConstructRuntimePlainObject", "Object");
        plainObjectClass.setSourceFile("construct_engine_object_plain.gd");
        plainObjectClass.addFunction(newPlainObjectLabelFunction(new GdObjectType(plainObjectClass.getName())));

        var countedWorkerClass = new LirClassDef("GDConstructRuntimeCountedWorker", "RefCounted");
        countedWorkerClass.setSourceFile("construct_engine_object_worker.gd");
        countedWorkerClass.addFunction(newWorkerPingFunction(new GdObjectType(countedWorkerClass.getName())));
        return List.of(constructNode, plainObjectClass, countedWorkerClass);
    }

    private static LirClassDef newPackedPropertyInitEngineClass() {
        var clazz = new LirClassDef("GDConstructPackedPropertyInitNode", "Node");
        clazz.setSourceFile("construct_engine_packed_property_init.gd");
        for (var packedCase : packedEngineCases()) {
            clazz.addProperty(new LirPropertyDef(packedCase.propertyName(), packedCase.type()));
        }
        return clazz;
    }

    private static LirFunctionDef newExplicitTransformFunction(GdObjectType selfType) {
        var func = newMethod("make_transform2d_explicit", GdTransform2DType.TRANSFORM2D, selfType);
        func.createAndAddVariable("xx", GdFloatType.FLOAT);
        func.createAndAddVariable("xy", GdFloatType.FLOAT);
        func.createAndAddVariable("yx", GdFloatType.FLOAT);
        func.createAndAddVariable("yy", GdFloatType.FLOAT);
        func.createAndAddVariable("tx", GdFloatType.FLOAT);
        func.createAndAddVariable("ty", GdFloatType.FLOAT);
        func.createAndAddVariable("t", GdTransform2DType.TRANSFORM2D);

        var block = entry(func);
        block.appendInstruction(new LiteralFloatInsn("xx", 1.0));
        block.appendInstruction(new LiteralFloatInsn("xy", 0.0));
        block.appendInstruction(new LiteralFloatInsn("yx", 0.0));
        block.appendInstruction(new LiteralFloatInsn("yy", 1.0));
        block.appendInstruction(new LiteralFloatInsn("tx", 2.0));
        block.appendInstruction(new LiteralFloatInsn("ty", 3.0));
        block.appendInstruction(new ConstructBuiltinInsn(
                "t",
                List.of(
                        varRef("xx"),
                        varRef("xy"),
                        varRef("yx"),
                        varRef("yy"),
                        varRef("tx"),
                        varRef("ty")
                )
        ));
        block.appendInstruction(new ReturnInsn("t"));
        return func;
    }

    private static LirFunctionDef newPrepareTransformFunction(GdObjectType selfType) {
        var func = newMethod("make_transform2d_prepare", GdTransform2DType.TRANSFORM2D, selfType);
        func.createAndAddVariable("t", GdTransform2DType.TRANSFORM2D);
        entry(func).appendInstruction(new ReturnInsn("t"));
        return func;
    }

    private static LirFunctionDef newExplicitGenericArrayFunction(GdObjectType selfType) {
        var arrayType = new GdArrayType(GdVariantType.VARIANT);
        var func = newMethod("make_generic_array_explicit", arrayType, selfType);
        func.createAndAddVariable("arr", arrayType);
        entry(func).appendInstruction(new ConstructArrayInsn("arr", null));
        entry(func).appendInstruction(new ReturnInsn("arr"));
        return func;
    }

    private static LirFunctionDef newPrepareGenericArrayFunction(GdObjectType selfType) {
        var arrayType = new GdArrayType(GdVariantType.VARIANT);
        var func = newMethod("make_generic_array_prepare", arrayType, selfType);
        func.createAndAddVariable("arr", arrayType);
        entry(func).appendInstruction(new ReturnInsn("arr"));
        return func;
    }

    private static LirFunctionDef newExplicitGenericDictionaryFunction(GdObjectType selfType) {
        var dictionaryType = new GdDictionaryType(GdVariantType.VARIANT, GdVariantType.VARIANT);
        var func = newMethod("make_generic_dictionary_explicit", dictionaryType, selfType);
        func.createAndAddVariable("dict", dictionaryType);
        entry(func).appendInstruction(new ConstructDictionaryInsn("dict", null, null));
        entry(func).appendInstruction(new ReturnInsn("dict"));
        return func;
    }

    private static LirFunctionDef newPrepareGenericDictionaryFunction(GdObjectType selfType) {
        var dictionaryType = new GdDictionaryType(GdVariantType.VARIANT, GdVariantType.VARIANT);
        var func = newMethod("make_generic_dictionary_prepare", dictionaryType, selfType);
        func.createAndAddVariable("dict", dictionaryType);
        entry(func).appendInstruction(new ReturnInsn("dict"));
        return func;
    }

    private static LirFunctionDef newExplicitTypedArrayFunction(GdObjectType selfType) {
        var arrayType = new GdArrayType(new GdObjectType("Node"));
        var func = newMethodWithMarker("make_typed_array_explicit", arrayType, selfType);
        func.createAndAddVariable("arr", arrayType);
        entry(func).appendInstruction(new ConstructArrayInsn("arr", "Node"));
        entry(func).appendInstruction(new ReturnInsn("arr"));
        return func;
    }

    private static LirFunctionDef newPrepareTypedArrayFunction(GdObjectType selfType) {
        var arrayType = new GdArrayType(new GdObjectType("Node"));
        var func = newMethodWithMarker("make_typed_array_prepare", arrayType, selfType);
        func.createAndAddVariable("arr", arrayType);
        entry(func).appendInstruction(new ReturnInsn("arr"));
        return func;
    }

    private static LirFunctionDef newExplicitTypedDictionaryFunction(GdObjectType selfType) {
        var dictionaryType = new GdDictionaryType(GdStringNameType.STRING_NAME, new GdObjectType("Node"));
        var func = newMethodWithMarker("make_typed_dictionary_explicit", dictionaryType, selfType);
        func.createAndAddVariable("dict", dictionaryType);
        entry(func).appendInstruction(new ConstructDictionaryInsn("dict", "StringName", "Node"));
        entry(func).appendInstruction(new ReturnInsn("dict"));
        return func;
    }

    private static LirFunctionDef newPrepareTypedDictionaryFunction(GdObjectType selfType) {
        var dictionaryType = new GdDictionaryType(GdStringNameType.STRING_NAME, new GdObjectType("Node"));
        var func = newMethodWithMarker("make_typed_dictionary_prepare", dictionaryType, selfType);
        func.createAndAddVariable("dict", dictionaryType);
        entry(func).appendInstruction(new ReturnInsn("dict"));
        return func;
    }

    private static LirFunctionDef newConstructEngineNodeFunction(GdObjectType selfType) {
        var func = newMethod("make_engine_node_class_name", GdStringType.STRING, selfType);
        func.createAndAddVariable("node", new GdObjectType("Node"));
        func.createAndAddVariable("class_name", GdStringType.STRING);
        entry(func).appendInstruction(new ConstructObjectInsn("node", "Node"));
        entry(func).appendInstruction(new CallMethodInsn("class_name", "get_class", "node", List.of()));
        entry(func).appendInstruction(new ReturnInsn("class_name"));
        return func;
    }

    private static LirFunctionDef newMeasureEngineRefCountedReferenceCountFunction(GdObjectType selfType) {
        var refCountedType = new GdObjectType("RefCounted");
        var func = newMethod("measure_engine_ref_counted_reference_count", GdIntType.INT, selfType);
        func.createAndAddVariable("ref_counted", refCountedType);
        func.createAndAddVariable("count", GdIntType.INT);
        entry(func).appendInstruction(new ConstructObjectInsn("ref_counted", "RefCounted"));
        entry(func).appendInstruction(new CallMethodInsn("count", "get_reference_count", "ref_counted", List.of()));
        entry(func).appendInstruction(new ReturnInsn("count"));
        return func;
    }

    private static LirFunctionDef newConstructGdccPlainObjectLabelFunction(GdObjectType selfType) {
        var plainObjectType = new GdObjectType("GDConstructRuntimePlainObject");
        var func = newMethod("make_gdcc_plain_object_label", GdStringType.STRING, selfType);
        func.createAndAddVariable("plain_object", plainObjectType);
        func.createAndAddVariable("label", GdStringType.STRING);
        entry(func).appendInstruction(new ConstructObjectInsn("plain_object", "GDConstructRuntimePlainObject"));
        entry(func).appendInstruction(new CallMethodInsn("label", "label", "plain_object", List.of()));
        entry(func).appendInstruction(new ReturnInsn("label"));
        return func;
    }

    private static LirFunctionDef newConstructGdccCountedWorkerPingFunction(GdObjectType selfType) {
        var workerType = new GdObjectType("GDConstructRuntimeCountedWorker");
        var func = newMethod("make_gdcc_counted_worker_ping", GdIntType.INT, selfType);
        func.createAndAddVariable("worker", workerType);
        func.createAndAddVariable("ping", GdIntType.INT);
        entry(func).appendInstruction(new ConstructObjectInsn("worker", "GDConstructRuntimeCountedWorker"));
        entry(func).appendInstruction(new CallMethodInsn("ping", "ping", "worker", List.of()));
        entry(func).appendInstruction(new ReturnInsn("ping"));
        return func;
    }

    private static LirFunctionDef newMeasureGdccCountedWorkerReferenceCountFunction(GdObjectType selfType) {
        var workerType = new GdObjectType("GDConstructRuntimeCountedWorker");
        var func = newMethod("measure_gdcc_counted_worker_reference_count", GdIntType.INT, selfType);
        func.createAndAddVariable("worker", workerType);
        func.createAndAddVariable("count", GdIntType.INT);
        entry(func).appendInstruction(new ConstructObjectInsn("worker", "GDConstructRuntimeCountedWorker"));
        entry(func).appendInstruction(new CallMethodInsn("count", "get_reference_count", "worker", List.of()));
        entry(func).appendInstruction(new ReturnInsn("count"));
        return func;
    }

    private static LirFunctionDef newPlainObjectLabelFunction(GdObjectType selfType) {
        var func = newMethod("label", GdStringType.STRING, selfType);
        func.createAndAddVariable("label", GdStringType.STRING);
        entry(func).appendInstruction(new LiteralStringInsn("label", "plain-worker"));
        entry(func).appendInstruction(new ReturnInsn("label"));
        return func;
    }

    private static LirFunctionDef newWorkerPingFunction(GdObjectType selfType) {
        var func = newMethod("ping", GdIntType.INT, selfType);
        func.createAndAddVariable("result", GdIntType.INT);
        entry(func).appendInstruction(new LiteralIntInsn("result", 7));
        entry(func).appendInstruction(new ReturnInsn("result"));
        return func;
    }

    private static LirFunctionDef newExplicitPackedArrayFunction(String methodName, GdType packedType, GdObjectType selfType) {
        var func = newMethod(methodName, packedType, selfType);
        func.createAndAddVariable("packed", packedType);
        entry(func).appendInstruction(new ConstructArrayInsn("packed", null));
        entry(func).appendInstruction(new ReturnInsn("packed"));
        return func;
    }

    private static LirFunctionDef newPreparePackedArrayFunction(String methodName, GdType packedType, GdObjectType selfType) {
        var func = newMethod(methodName, packedType, selfType);
        func.createAndAddVariable("packed", packedType);
        entry(func).appendInstruction(new ReturnInsn("packed"));
        return func;
    }

    private static List<PackedEngineCase> packedEngineCases() {
        return List.of(
                new PackedEngineCase(
                        "packed byte array",
                        "PackedByteArray",
                        "TYPE_PACKED_BYTE_ARRAY",
                        "PackedByteArray",
                        GdPackedNumericArrayType.PACKED_BYTE_ARRAY,
                        "packed.append(7)",
                        "packed[0] == 7"
                ),
                new PackedEngineCase(
                        "packed int32 array",
                        "PackedInt32Array",
                        "TYPE_PACKED_INT32_ARRAY",
                        "PackedInt32Array",
                        GdPackedNumericArrayType.PACKED_INT32_ARRAY,
                        "packed.append(123)",
                        "packed[0] == 123"
                ),
                new PackedEngineCase(
                        "packed int64 array",
                        "PackedInt64Array",
                        "TYPE_PACKED_INT64_ARRAY",
                        "PackedInt64Array",
                        GdPackedNumericArrayType.PACKED_INT64_ARRAY,
                        "packed.append(9876543210)",
                        "packed[0] == 9876543210"
                ),
                new PackedEngineCase(
                        "packed float32 array",
                        "PackedFloat32Array",
                        "TYPE_PACKED_FLOAT32_ARRAY",
                        "PackedFloat32Array",
                        GdPackedNumericArrayType.PACKED_FLOAT32_ARRAY,
                        "packed.append(1.5)",
                        "is_equal_approx(packed[0], 1.5)"
                ),
                new PackedEngineCase(
                        "packed float64 array",
                        "PackedFloat64Array",
                        "TYPE_PACKED_FLOAT64_ARRAY",
                        "PackedFloat64Array",
                        GdPackedNumericArrayType.PACKED_FLOAT64_ARRAY,
                        "packed.append(2.75)",
                        "is_equal_approx(packed[0], 2.75)"
                ),
                new PackedEngineCase(
                        "packed string array",
                        "PackedStringArray",
                        "TYPE_PACKED_STRING_ARRAY",
                        "PackedStringArray",
                        GdPackedStringArrayType.PACKED_STRING_ARRAY,
                        "packed.append(\"gdcc\")",
                        "packed[0] == \"gdcc\""
                ),
                new PackedEngineCase(
                        "packed vector2 array",
                        "PackedVector2Array",
                        "TYPE_PACKED_VECTOR2_ARRAY",
                        "PackedVector2Array",
                        GdPackedVectorArrayType.PACKED_VECTOR2_ARRAY,
                        "packed.append(Vector2(1.25, -2.5))",
                        "packed[0].is_equal_approx(Vector2(1.25, -2.5))"
                ),
                new PackedEngineCase(
                        "packed vector3 array",
                        "PackedVector3Array",
                        "TYPE_PACKED_VECTOR3_ARRAY",
                        "PackedVector3Array",
                        GdPackedVectorArrayType.PACKED_VECTOR3_ARRAY,
                        "packed.append(Vector3(1.0, -2.0, 3.5))",
                        "packed[0].is_equal_approx(Vector3(1.0, -2.0, 3.5))"
                ),
                new PackedEngineCase(
                        "packed vector4 array",
                        "PackedVector4Array",
                        "TYPE_PACKED_VECTOR4_ARRAY",
                        "PackedVector4Array",
                        GdPackedVectorArrayType.PACKED_VECTOR4_ARRAY,
                        "packed.append(Vector4(1.0, 2.0, -3.0, 4.5))",
                        "packed[0].is_equal_approx(Vector4(1.0, 2.0, -3.0, 4.5))"
                )
        );
    }

    private static LirFunctionDef newMethod(String name, dev.superice.gdcc.type.GdType returnType, GdObjectType selfType) {
        var func = new LirFunctionDef(name);
        func.setReturnType(returnType);
        func.addParameter(new LirParameterDef("self", selfType, null, func));
        func.addBasicBlock(new LirBasicBlock("entry"));
        func.setEntryBlockId("entry");
        return func;
    }

    private static LirFunctionDef newMethodWithMarker(String name, dev.superice.gdcc.type.GdType returnType, GdObjectType selfType) {
        var func = newMethod(name, returnType, selfType);
        func.addParameter(new LirParameterDef("marker", GdIntType.INT, null, func));
        return func;
    }

    private static LirBasicBlock entry(LirFunctionDef functionDef) {
        return functionDef.getBasicBlock("entry");
    }

    private static LirInstruction.VariableOperand varRef(String id) {
        return new LirInstruction.VariableOperand(id);
    }

    private static String resolveCreateInstanceBody(String cCode, String className) {
        var functionPrefix = "GDExtensionObjectPtr\\s+" + java.util.regex.Pattern.quote(className) + "_class_create_instance";
        var pattern = java.util.regex.Pattern.compile(functionPrefix + "\\([^)]*\\)\\s*\\{(.*?)return obj;\\s*}", java.util.regex.Pattern.DOTALL);
        var matcher = pattern.matcher(cCode);
        assertTrue(matcher.find(), "Missing create_instance body for class " + className);
        return matcher.group(1);
    }

    private static String resolveFunctionBody(String cCode, String functionName) {
        var pattern = java.util.regex.Pattern.compile(
                java.util.regex.Pattern.quote(functionName) + "\\s*\\([^)]*\\)\\s*\\{(.*?)return _return_val;\\s*}",
                java.util.regex.Pattern.DOTALL
        );
        var matcher = pattern.matcher(cCode);
        assertTrue(matcher.find(), "Missing function body for " + functionName);
        return matcher.group(1);
    }

    private static String testScript() {
        return """
                extends Node
                
                const TARGET_NODE_NAME = "ConstructNode"
                const EPSILON = 0.001
                
                func _ready() -> void:
                    var target = get_parent().get_node_or_null(TARGET_NODE_NAME)
                    if target == null:
                        push_error("Target node missing.")
                        return
                
                    var transform_explicit = target.call("make_transform2d_explicit")
                    if _check_transform2d(transform_explicit, 2.0, 3.0):
                        print("explicit transform check passed.")
                    else:
                        push_error("explicit transform check failed.")
                
                    var transform_prepare = target.call("make_transform2d_prepare")
                    if _check_transform2d(transform_prepare, 0.0, 0.0):
                        print("prepare transform check passed.")
                    else:
                        push_error("prepare transform check failed.")
                
                    var arr_explicit = target.call("make_generic_array_explicit")
                    if _check_generic_array(arr_explicit):
                        print("explicit generic array check passed.")
                    else:
                        push_error("explicit generic array check failed.")
                
                    var arr_prepare = target.call("make_generic_array_prepare")
                    if _check_generic_array(arr_prepare):
                        print("prepare generic array check passed.")
                    else:
                        push_error("prepare generic array check failed.")
                
                    var dict_explicit = target.call("make_generic_dictionary_explicit")
                    if _check_generic_dictionary(dict_explicit):
                        print("explicit generic dictionary check passed.")
                    else:
                        push_error("explicit generic dictionary check failed.")
                
                    var dict_prepare = target.call("make_generic_dictionary_prepare")
                    if _check_generic_dictionary(dict_prepare):
                        print("prepare generic dictionary check passed.")
                    else:
                        push_error("prepare generic dictionary check failed.")
                
                func _check_transform2d(value: Variant, tx: float, ty: float) -> bool:
                    if typeof(value) != TYPE_TRANSFORM2D:
                        return false
                    var t: Transform2D = value
                    return absf(t.x.x - 1.0) <= EPSILON \
                            and absf(t.x.y - 0.0) <= EPSILON \
                            and absf(t.y.x - 0.0) <= EPSILON \
                            and absf(t.y.y - 1.0) <= EPSILON \
                            and absf(t.origin.x - tx) <= EPSILON \
                            and absf(t.origin.y - ty) <= EPSILON
                
                func _check_generic_array(value: Variant) -> bool:
                    if typeof(value) != TYPE_ARRAY:
                        return false
                    var arr: Array = value
                    return not arr.is_typed() and arr.size() == 0
                
                func _check_generic_dictionary(value: Variant) -> bool:
                    if typeof(value) != TYPE_DICTIONARY:
                        return false
                    var dict: Dictionary = value
                    return not dict.is_typed() and dict.is_empty()
                """;
    }

    private static String packedTestScript() {
        var script = new StringBuilder();
        script.append("extends Node\n\n");
        script.append("const TARGET_NODE_NAME = \"ConstructNode\"\n\n");
        script.append("func _ready() -> void:\n");
        script.append("    var target = get_parent().get_node_or_null(TARGET_NODE_NAME)\n");
        script.append("    if target == null:\n");
        script.append("        push_error(\"Target node missing.\")\n");
        script.append("        return\n\n");

        for (var packedCase : packedEngineCases()) {
            var explicitValueVar = packedCase.valueVar("explicit");
            var prepareValueVar = packedCase.valueVar("prepare");
            script.append("    var ").append(explicitValueVar).append(" = target.call(\"").append(packedCase.explicitMethodName()).append("\")\n");
            script.append("    if ").append(packedCase.checkMethodName()).append("(").append(explicitValueVar).append("):\n");
            script.append("        print(\"explicit ").append(packedCase.label()).append(" check passed.\")\n");
            script.append("    else:\n");
            script.append("        push_error(\"explicit ").append(packedCase.label()).append(" check failed.\")\n\n");

            script.append("    var ").append(prepareValueVar).append(" = target.call(\"").append(packedCase.prepareMethodName()).append("\")\n");
            script.append("    if ").append(packedCase.checkMethodName()).append("(").append(prepareValueVar).append("):\n");
            script.append("        print(\"prepare ").append(packedCase.label()).append(" check passed.\")\n");
            script.append("    else:\n");
            script.append("        push_error(\"prepare ").append(packedCase.label()).append(" check failed.\")\n\n");
        }

        for (var packedCase : packedEngineCases()) {
            script.append("func ").append(packedCase.checkMethodName()).append("(value: Variant) -> bool:\n");
            script.append("    if typeof(value) != ").append(packedCase.gdscriptTypeConstant()).append(":\n");
            script.append("        return false\n");
            script.append("    var packed: ").append(packedCase.gdscriptTypeName()).append(" = value\n");
            script.append("    if packed.size() != 0:\n");
            script.append("        return false\n");
            script.append("    ").append(packedCase.appendExpression()).append("\n");
            script.append("    return packed.size() == 1 and ").append(packedCase.firstElementAssertion()).append("\n\n");
        }
        return script.toString();
    }

    private static String objectConstructTestScript() {
        return """
                extends Node
                
                const TARGET_NODE_NAME = "ConstructObjectNode"
                
                func _ready() -> void:
                    var target = get_parent().get_node_or_null(TARGET_NODE_NAME)
                    if target == null:
                        push_error("Target node missing.")
                        return
                
                    var engine_node_class = String(target.call("make_engine_node_class_name"))
                    if engine_node_class == "Node":
                        print("engine non-ref object construct check passed.")
                    else:
                        push_error("engine non-ref object construct check failed.")
                
                    var engine_ref_count = int(target.call("measure_engine_ref_counted_reference_count"))
                    if engine_ref_count >= 1:
                        print("engine refcounted construct check passed.")
                    else:
                        push_error("engine refcounted construct check failed.")
                
                    var plain_label = String(target.call("make_gdcc_plain_object_label"))
                    if plain_label == "plain-worker":
                        print("gdcc non-ref object construct check passed.")
                    else:
                        push_error("gdcc non-ref object construct check failed.")
                
                    var counted_ping = int(target.call("make_gdcc_counted_worker_ping"))
                    if counted_ping == 7:
                        print("gdcc refcounted ping check passed.")
                    else:
                        push_error("gdcc refcounted ping check failed.")
                
                    var counted_ref_count = int(target.call("measure_gdcc_counted_worker_reference_count"))
                    if counted_ref_count >= 1:
                        print("gdcc refcounted reference count check passed.")
                    else:
                        push_error("gdcc refcounted reference count check failed.")
                """;
    }

    private static String packedPropertyInitTestScript() {
        var script = new StringBuilder();
        script.append("extends Node\n\n");
        script.append("const TARGET_NODE_NAME = \"ConstructNode\"\n\n");
        script.append("func _ready() -> void:\n");
        script.append("    var target = get_parent().get_node_or_null(TARGET_NODE_NAME)\n");
        script.append("    if target == null:\n");
        script.append("        push_error(\"Target node missing.\")\n");
        script.append("        return\n\n");

        for (var packedCase : packedEngineCases()) {
            var valueVar = packedCase.valueVar("property");
            script.append("    var ").append(valueVar).append(" = target.get(\"").append(packedCase.propertyName()).append("\")\n");
            script.append("    if ").append(packedCase.checkMethodName()).append("(").append(valueVar).append("):\n");
            script.append("        print(\"default ").append(packedCase.label()).append(" property init check passed.\")\n");
            script.append("    else:\n");
            script.append("        push_error(\"default ").append(packedCase.label()).append(" property init check failed.\")\n\n");
        }

        for (var packedCase : packedEngineCases()) {
            script.append("func ").append(packedCase.checkMethodName()).append("(value: Variant) -> bool:\n");
            script.append("    if typeof(value) != ").append(packedCase.gdscriptTypeConstant()).append(":\n");
            script.append("        return false\n");
            script.append("    var packed: ").append(packedCase.gdscriptTypeName()).append(" = value\n");
            script.append("    if packed.size() != 0:\n");
            script.append("        return false\n");
            script.append("    ").append(packedCase.appendExpression()).append("\n");
            script.append("    return packed.size() == 1 and ").append(packedCase.firstElementAssertion()).append("\n\n");
        }
        return script.toString();
    }

    private record PackedEngineCase(
            String label,
            String builtinName,
            String gdscriptTypeConstant,
            String gdscriptTypeName,
            GdType type,
            String appendExpression,
            String firstElementAssertion
    ) {
        private String explicitMethodName() {
            return "make_" + label.replace(' ', '_') + "_explicit";
        }

        private String prepareMethodName() {
            return "make_" + label.replace(' ', '_') + "_prepare";
        }

        private String constructorCall() {
            return "godot_new_" + builtinName + "()";
        }

        private String checkMethodName() {
            return "_check_" + label.replace(' ', '_');
        }

        private String valueVar(String suffix) {
            return label.replace(' ', '_') + "_" + suffix;
        }

        private String propertyName() {
            return label.replace(' ', '_') + "_prop";
        }
    }
}
