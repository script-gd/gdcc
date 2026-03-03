package dev.superice.gdcc.backend.c.gen;

import dev.superice.gdcc.backend.CodegenContext;
import dev.superice.gdcc.backend.c.build.CBuildResult;
import dev.superice.gdcc.backend.c.build.COptimizationLevel;
import dev.superice.gdcc.backend.c.build.CProjectBuilder;
import dev.superice.gdcc.backend.c.build.CProjectInfo;
import dev.superice.gdcc.backend.c.build.GodotGdextensionTestRunner;
import dev.superice.gdcc.backend.c.build.TargetPlatform;
import dev.superice.gdcc.backend.c.build.ZigUtil;
import dev.superice.gdcc.enums.GodotOperator;
import dev.superice.gdcc.enums.GodotVersion;
import dev.superice.gdcc.exception.InvalidInsnException;
import dev.superice.gdcc.gdextension.ExtensionApiLoader;
import dev.superice.gdcc.lir.LirBasicBlock;
import dev.superice.gdcc.lir.LirClassDef;
import dev.superice.gdcc.lir.LirFunctionDef;
import dev.superice.gdcc.lir.LirModule;
import dev.superice.gdcc.lir.LirParameterDef;
import dev.superice.gdcc.lir.insn.BinaryOpInsn;
import dev.superice.gdcc.lir.insn.PackVariantInsn;
import dev.superice.gdcc.lir.insn.ReturnInsn;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.type.GdArrayType;
import dev.superice.gdcc.type.GdBoolType;
import dev.superice.gdcc.type.GdFloatType;
import dev.superice.gdcc.type.GdFloatVectorType;
import dev.superice.gdcc.type.GdIntType;
import dev.superice.gdcc.type.GdNilType;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdStringType;
import dev.superice.gdcc.type.GdTransform2DType;
import dev.superice.gdcc.type.GdType;
import dev.superice.gdcc.type.GdVariantType;
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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class COperatorInsnGenEngineTest {
    @Test
    @DisplayName("operator insns should run E1/E2/E4/E5/E6/E7 and string/vector/matrix in real engine")
    void operatorInsnsShouldRunCoreEngineScenariosInRealGodot() throws IOException, InterruptedException {
        if (!hasZig()) {
            Assumptions.abort("Zig not found; skipping integration test");
            return;
        }

        var tempDir = Path.of("tmp/test/operator_engine_runtime");
        Files.createDirectories(tempDir);
        var operatorClass = newRuntimeOperatorEngineClass();
        var buildResult = buildProject(tempDir, "operator_engine_runtime", operatorClass);
        assertTrue(buildResult.success(), "Compilation should succeed. Build log:\n" + buildResult.buildLog());
        assertFalse(buildResult.artifacts().isEmpty(), "Compilation should produce extension artifacts.");

        var entrySource = Files.readString(tempDir.resolve("entry.c"));
        assertTrue(entrySource.contains("pow("), entrySource);
        assertTrue(entrySource.contains("pow_int("), entrySource);
        assertTrue(entrySource.contains("godot_variant_evaluate(GDEXTENSION_VARIANT_OP_ADD"), entrySource);
        assertTrue(entrySource.contains("godot_variant_evaluate(GDEXTENSION_VARIANT_OP_DIVIDE"), entrySource);
        assertTrue(entrySource.contains("gdcc_eval_binary_in_int_array_to_bool"), entrySource);
        assertTrue(entrySource.contains("GDEXTENSION_VARIANT_TYPE_INT"), entrySource);
        assertTrue(entrySource.contains("GDEXTENSION_VARIANT_TYPE_STRING"), entrySource);
        assertTrue(entrySource.contains("GDEXTENSION_VARIANT_TYPE_VECTOR2"), entrySource);
        assertTrue(entrySource.contains("godot_new_int_with_Variant"), entrySource);
        assertTrue(entrySource.contains("godot_new_String_with_Variant"), entrySource);
        assertTrue(entrySource.contains("godot_new_Vector2_with_Variant"), entrySource);
        assertFalse(entrySource.contains("gdcc_eval_binary_add_variant"), entrySource);

        var runner = new GodotGdextensionTestRunner(Path.of("test_project"));
        runner.prepareProject(new GodotGdextensionTestRunner.ProjectSetup(
                buildResult.artifacts(),
                List.of(new GodotGdextensionTestRunner.SceneNodeSpec(
                        "OperatorNode",
                        operatorClass.getName(),
                        ".",
                        Map.of()
                )),
                new GodotGdextensionTestRunner.TestScriptSpec(runtimeScript())
        ));

        var runResult = runner.run(true);
        var combinedOutput = runResult.combinedOutput();

        assertTrue(runResult.stopSignalSeen(), "Godot run should emit stop signal.\nOutput:\n" + combinedOutput);
        for (var marker : List.of(
                "E1 power check passed.",
                "E2 object/nil compare check passed.",
                "E4 IN original-order check passed.",
                "E5 variant mixed check passed.",
                "E5 variant string->variant check passed.",
                "E5 variant->bool unpack check passed.",
                "E5 variant->concrete unpack check passed.",
                "E6 runtime fail-path check passed.",
                "E7 lifecycle stress check passed.",
                "string concat check passed.",
                "string compare check passed.",
                "vector add check passed.",
                "matrix multiply check passed."
        )) {
            assertTrue(combinedOutput.contains(marker), "Missing marker '" + marker + "'.\nOutput:\n" + combinedOutput);
        }
        assertTrue(
                combinedOutput.contains("godot_variant_evaluate failed for operator 'DIVIDE'"),
                "Runtime fail-path should print evaluator failure.\nOutput:\n" + combinedOutput
        );
        assertFalse(combinedOutput.contains("check failed"), "No check should fail.\nOutput:\n" + combinedOutput);
    }

    @Test
    @DisplayName("E3 should fail-fast when original-order metadata is missing")
    void shouldFailFastWhenOriginalOrderMetadataIsMissing() throws IOException {
        var tempDir = Path.of("tmp/test/operator_engine_e3");
        Files.createDirectories(tempDir);

        var operatorClass = newCompileFailClass(
                "GDOperatorEngineE3Node",
                "string_gt_int",
                GdBoolType.BOOL,
                "left",
                GdStringType.STRING,
                "right",
                GdIntType.INT,
                GodotOperator.GREATER
        );
        var ex = assertThrows(
                RuntimeException.class,
                () -> buildProjectWithCompileGuard(tempDir, "operator_engine_e3", operatorClass)
        );
        var invalidInsn = extractInvalidInsnCause(ex);
        assertInstanceOf(InvalidInsnException.class, invalidInsn);
        assertTrue(
                invalidInsn.getMessage().contains("Binary operator metadata is missing for signature (String, GREATER, int)"),
                invalidInsn.getMessage()
        );
    }

    @Test
    @DisplayName("E4 reverse IN should fail-fast and not use swapped metadata")
    void reverseInShouldFailFastWithoutSwapFallback() throws IOException {
        var tempDir = Path.of("tmp/test/operator_engine_e4");
        Files.createDirectories(tempDir);

        var operatorClass = newCompileFailClass(
                "GDOperatorEngineE4Node",
                "array_in_int",
                GdBoolType.BOOL,
                "left",
                new GdArrayType(GdVariantType.VARIANT),
                "right",
                GdIntType.INT,
                GodotOperator.IN
        );
        var ex = assertThrows(
                RuntimeException.class,
                () -> buildProjectWithCompileGuard(tempDir, "operator_engine_e4", operatorClass)
        );
        var invalidInsn = extractInvalidInsnCause(ex);
        assertInstanceOf(InvalidInsnException.class, invalidInsn);
        assertTrue(
                invalidInsn.getMessage().contains("Binary operator metadata is missing for signature (Array, IN, int)"),
                invalidInsn.getMessage()
        );
    }

    @Test
    @DisplayName("variant_evaluate should compile non-Variant result and emit runtime unpack type check")
    void variantEvaluateShouldCompileNonVariantResultWithRuntimeUnpackCheck() throws IOException {
        if (!hasZig()) {
            Assumptions.abort("Zig not found; skipping integration test");
            return;
        }

        var tempDir = Path.of("tmp/test/operator_engine_variant_type_guard");
        Files.createDirectories(tempDir);

        var operatorClass = newVariantToBoolClass();
        var buildResult = buildProject(tempDir, "operator_engine_variant_type_guard", operatorClass);
        assertTrue(buildResult.success(), "Compilation should succeed. Build log:\n" + buildResult.buildLog());

        var entrySource = Files.readString(tempDir.resolve("entry.c"));
        assertTrue(entrySource.contains("godot_variant_evaluate(GDEXTENSION_VARIANT_OP_EQUAL"), entrySource);
        assertTrue(entrySource.contains("gdcc_check_variant_type_builtin"), entrySource);
        assertTrue(entrySource.contains("GDEXTENSION_VARIANT_TYPE_BOOL"), entrySource);
        assertTrue(entrySource.contains("godot_new_bool_with_Variant"), entrySource);
    }

    private static boolean hasZig() {
        return ZigUtil.findZig() != null;
    }

    private static @NotNull CBuildResult buildProject(@NotNull Path tempDir,
                                                      @NotNull String projectName,
                                                      @NotNull LirClassDef operatorClass) throws IOException {
        var projectInfo = new CProjectInfo(
                projectName,
                GodotVersion.V451,
                tempDir,
                COptimizationLevel.DEBUG,
                TargetPlatform.getNativePlatform()
        );
        var builder = new CProjectBuilder();
        builder.initProject(projectInfo);

        var module = new LirModule(projectName + "_module", List.of(operatorClass));
        var api = ExtensionApiLoader.loadVersion(GodotVersion.V451);
        var codegen = new CCodegen();
        codegen.prepare(new CodegenContext(projectInfo, new ClassRegistry(api)), module);

        return builder.buildProject(projectInfo, codegen);
    }

    private static @NotNull CBuildResult buildProjectWithCompileGuard(@NotNull Path tempDir,
                                                                      @NotNull String projectName,
                                                                      @NotNull LirClassDef operatorClass) throws IOException {
        var projectInfo = new CProjectInfo(
                projectName,
                GodotVersion.V451,
                tempDir,
                COptimizationLevel.DEBUG,
                TargetPlatform.getNativePlatform()
        );
        var builder = new CProjectBuilder((_, _, _, _, _, _) -> {
            throw new AssertionError("Compiler should not be invoked for codegen fail-fast scenario");
        });
        builder.initProject(projectInfo);

        var module = new LirModule(projectName + "_module", List.of(operatorClass));
        var api = ExtensionApiLoader.loadVersion(GodotVersion.V451);
        var codegen = new CCodegen();
        codegen.prepare(new CodegenContext(projectInfo, new ClassRegistry(api)), module);

        return builder.buildProject(projectInfo, codegen);
    }

    private static @NotNull LirClassDef newRuntimeOperatorEngineClass() {
        var clazz = new LirClassDef("GDOperatorEngineNode", "Node");
        clazz.setSourceFile("operator_engine.gd");

        var selfType = new GdObjectType(clazz.getName());
        clazz.addFunction(newBinaryMethod(
                "pow_float_int",
                GdFloatType.FLOAT,
                selfType,
                "left",
                GdFloatType.FLOAT,
                "right",
                GdIntType.INT,
                GodotOperator.POWER
        ));
        clazz.addFunction(newBinaryMethod(
                "pow_int_int",
                GdIntType.INT,
                selfType,
                "left",
                GdIntType.INT,
                "right",
                GdIntType.INT,
                GodotOperator.POWER
        ));
        clazz.addFunction(newBinaryMethod(
                "obj_eq",
                GdBoolType.BOOL,
                selfType,
                "left",
                GdObjectType.OBJECT,
                "right",
                GdObjectType.OBJECT,
                GodotOperator.EQUAL
        ));
        clazz.addFunction(newObjectNullCompareMethod("obj_eq_local_null", selfType, GodotOperator.EQUAL));
        clazz.addFunction(newObjectParamVsLocalNullMethod("obj_eq_param_with_local_null", selfType, GodotOperator.EQUAL));
        clazz.addFunction(newBinaryMethod(
                "obj_ne",
                GdBoolType.BOOL,
                selfType,
                "left",
                GdObjectType.OBJECT,
                "right",
                GdObjectType.OBJECT,
                GodotOperator.NOT_EQUAL
        ));
        clazz.addFunction(newNilToNilMethod("nil_eq_nil", selfType, GodotOperator.EQUAL));
        clazz.addFunction(newNilToNilMethod("nil_ne_nil", selfType, GodotOperator.NOT_EQUAL));
        clazz.addFunction(newNilVsLocalObjectNullMethod("nil_eq_local_obj_null", selfType, GodotOperator.EQUAL));
        clazz.addFunction(newNilLocalCompareMethod(
                "nil_eq_obj",
                selfType,
                "right",
                GdObjectType.OBJECT,
                GodotOperator.EQUAL
        ));
        clazz.addFunction(newNilLocalCompareMethod(
                "nil_eq_int",
                selfType,
                "right",
                GdIntType.INT,
                GodotOperator.EQUAL
        ));
        clazz.addFunction(newBinaryMethod(
                "contains_int_in_array",
                GdBoolType.BOOL,
                selfType,
                "left",
                GdIntType.INT,
                "right",
                new GdArrayType(GdVariantType.VARIANT),
                GodotOperator.IN
        ));
        clazz.addFunction(newPackedVariantBinaryMethod(
                "variant_add_to_variant",
                GdVariantType.VARIANT,
                selfType,
                "left",
                GdIntType.INT,
                "right",
                GdIntType.INT,
                GodotOperator.ADD,
                true,
                false
        ));
        clazz.addFunction(newPackedVariantBinaryMethod(
                "variant_equal_to_bool",
                GdBoolType.BOOL,
                selfType,
                "left",
                GdIntType.INT,
                "right",
                GdIntType.INT,
                GodotOperator.EQUAL,
                true,
                true
        ));
        clazz.addFunction(newPackedVariantBinaryMethod(
                "variant_add_to_int",
                GdIntType.INT,
                selfType,
                "left",
                GdIntType.INT,
                "right",
                GdIntType.INT,
                GodotOperator.ADD,
                true,
                false
        ));
        clazz.addFunction(newPackedVariantBinaryMethod(
                "variant_concat_to_string_unpack",
                GdStringType.STRING,
                selfType,
                "left",
                GdStringType.STRING,
                "right",
                GdStringType.STRING,
                GodotOperator.ADD,
                true,
                false
        ));
        clazz.addFunction(newPackedVariantBinaryMethod(
                "variant_add_vec2_to_vec2",
                GdFloatVectorType.VECTOR2,
                selfType,
                "left",
                GdFloatVectorType.VECTOR2,
                "right",
                GdFloatVectorType.VECTOR2,
                GodotOperator.ADD,
                true,
                false
        ));
        clazz.addFunction(newPackedVariantBinaryMethod(
                "variant_divide_to_variant",
                GdVariantType.VARIANT,
                selfType,
                "left",
                GdStringType.STRING,
                "right",
                GdStringType.STRING,
                GodotOperator.DIVIDE,
                true,
                true
        ));
        clazz.addFunction(newPackedVariantBinaryMethod(
                "variant_concat_to_variant",
                GdVariantType.VARIANT,
                selfType,
                "left",
                GdStringType.STRING,
                "right",
                GdStringType.STRING,
                GodotOperator.ADD,
                true,
                true
        ));
        clazz.addFunction(newBinaryMethod(
                "concat_text",
                GdStringType.STRING,
                selfType,
                "left",
                GdStringType.STRING,
                "right",
                GdStringType.STRING,
                GodotOperator.ADD
        ));
        clazz.addFunction(newBinaryMethod(
                "equals_text",
                GdBoolType.BOOL,
                selfType,
                "left",
                GdStringType.STRING,
                "right",
                GdStringType.STRING,
                GodotOperator.EQUAL
        ));
        clazz.addFunction(newBinaryMethod(
                "add_vec2",
                GdFloatVectorType.VECTOR2,
                selfType,
                "left",
                GdFloatVectorType.VECTOR2,
                "right",
                GdFloatVectorType.VECTOR2,
                GodotOperator.ADD
        ));
        clazz.addFunction(newBinaryMethod(
                "mul_transform2d",
                GdTransform2DType.TRANSFORM2D,
                selfType,
                "left",
                GdTransform2DType.TRANSFORM2D,
                "right",
                GdTransform2DType.TRANSFORM2D,
                GodotOperator.MULTIPLY
        ));
        return clazz;
    }

    private static @NotNull LirClassDef newCompileFailClass(@NotNull String className,
                                                            @NotNull String methodName,
                                                            @NotNull GdType returnType,
                                                            @NotNull String leftName,
                                                            @NotNull GdType leftType,
                                                            @NotNull String rightName,
                                                            @NotNull GdType rightType,
                                                            @NotNull GodotOperator operator) {
        var clazz = new LirClassDef(className, "Node");
        clazz.setSourceFile("operator_engine_fail.gd");
        var selfType = new GdObjectType(clazz.getName());
        clazz.addFunction(newBinaryMethod(
                methodName,
                returnType,
                selfType,
                leftName,
                leftType,
                rightName,
                rightType,
                operator
        ));
        return clazz;
    }

    private static @NotNull LirClassDef newVariantToBoolClass() {
        var clazz = new LirClassDef("GDOperatorEngineVariantTypeGuardNode", "Node");
        clazz.setSourceFile("operator_engine_variant_type_guard.gd");
        var selfType = new GdObjectType(clazz.getName());
        clazz.addFunction(newPackedVariantBinaryMethod(
                "variant_equal_to_bool",
                GdBoolType.BOOL,
                selfType,
                "left",
                GdIntType.INT,
                "right",
                GdIntType.INT,
                GodotOperator.EQUAL,
                true,
                true
        ));
        return clazz;
    }

    private static @NotNull LirFunctionDef newBinaryMethod(@NotNull String name,
                                                           @NotNull GdType returnType,
                                                           @NotNull GdObjectType selfType,
                                                           @NotNull String leftName,
                                                           @NotNull GdType leftType,
                                                           @NotNull String rightName,
                                                           @NotNull GdType rightType,
                                                           @NotNull GodotOperator operator) {
        var func = newMethod(name, returnType, selfType);
        func.addParameter(new LirParameterDef(leftName, leftType, null, func));
        func.addParameter(new LirParameterDef(rightName, rightType, null, func));
        func.createAndAddVariable("result", returnType);
        entry(func).instructions().add(new BinaryOpInsn("result", operator, leftName, rightName));
        entry(func).instructions().add(new ReturnInsn("result"));
        return func;
    }

    private static @NotNull LirFunctionDef newNilToNilMethod(@NotNull String name,
                                                             @NotNull GdObjectType selfType,
                                                             @NotNull GodotOperator operator) {
        var func = newMethod(name, GdBoolType.BOOL, selfType);
        func.createAndAddVariable("left_nil", GdNilType.NIL);
        func.createAndAddVariable("right_nil", GdNilType.NIL);
        func.createAndAddVariable("result", GdBoolType.BOOL);
        entry(func).instructions().add(new BinaryOpInsn("result", operator, "left_nil", "right_nil"));
        entry(func).instructions().add(new ReturnInsn("result"));
        return func;
    }

    private static @NotNull LirFunctionDef newNilLocalCompareMethod(@NotNull String name,
                                                                    @NotNull GdObjectType selfType,
                                                                    @NotNull String rightName,
                                                                    @NotNull GdType rightType,
                                                                    @NotNull GodotOperator operator) {
        var func = newMethod(name, GdBoolType.BOOL, selfType);
        func.addParameter(new LirParameterDef(rightName, rightType, null, func));
        func.createAndAddVariable("left_nil", GdNilType.NIL);
        func.createAndAddVariable("result", GdBoolType.BOOL);
        entry(func).instructions().add(new BinaryOpInsn("result", operator, "left_nil", rightName));
        entry(func).instructions().add(new ReturnInsn("result"));
        return func;
    }

    private static @NotNull LirFunctionDef newNilVsLocalObjectNullMethod(@NotNull String name,
                                                                         @NotNull GdObjectType selfType,
                                                                         @NotNull GodotOperator operator) {
        var func = newMethod(name, GdBoolType.BOOL, selfType);
        func.createAndAddVariable("left_nil", GdNilType.NIL);
        func.createAndAddVariable("right_obj", GdObjectType.OBJECT);
        func.createAndAddVariable("result", GdBoolType.BOOL);
        entry(func).instructions().add(new BinaryOpInsn("result", operator, "left_nil", "right_obj"));
        entry(func).instructions().add(new ReturnInsn("result"));
        return func;
    }

    private static @NotNull LirFunctionDef newObjectNullCompareMethod(@NotNull String name,
                                                                      @NotNull GdObjectType selfType,
                                                                      @NotNull GodotOperator operator) {
        var func = newMethod(name, GdBoolType.BOOL, selfType);
        func.createAndAddVariable("left_obj", GdObjectType.OBJECT);
        func.createAndAddVariable("right_obj", GdObjectType.OBJECT);
        func.createAndAddVariable("result", GdBoolType.BOOL);
        entry(func).instructions().add(new BinaryOpInsn("result", operator, "left_obj", "right_obj"));
        entry(func).instructions().add(new ReturnInsn("result"));
        return func;
    }

    private static @NotNull LirFunctionDef newObjectParamVsLocalNullMethod(@NotNull String name,
                                                                           @NotNull GdObjectType selfType,
                                                                           @NotNull GodotOperator operator) {
        var func = newMethod(name, GdBoolType.BOOL, selfType);
        func.addParameter(new LirParameterDef("obj", GdObjectType.OBJECT, null, func));
        func.createAndAddVariable("null_obj", GdObjectType.OBJECT);
        func.createAndAddVariable("result", GdBoolType.BOOL);
        entry(func).instructions().add(new BinaryOpInsn("result", operator, "null_obj", "obj"));
        entry(func).instructions().add(new ReturnInsn("result"));
        return func;
    }

    private static @NotNull LirFunctionDef newPackedVariantBinaryMethod(@NotNull String name,
                                                                        @NotNull GdType returnType,
                                                                        @NotNull GdObjectType selfType,
                                                                        @NotNull String leftName,
                                                                        @NotNull GdType leftType,
                                                                        @NotNull String rightName,
                                                                        @NotNull GdType rightType,
                                                                        @NotNull GodotOperator operator,
                                                                        boolean packLeft,
                                                                        boolean packRight) {
        var func = newMethod(name, returnType, selfType);
        func.addParameter(new LirParameterDef(leftName, leftType, null, func));
        func.addParameter(new LirParameterDef(rightName, rightType, null, func));
        var leftOperandId = leftName;
        var rightOperandId = rightName;
        if (packLeft) {
            func.createAndAddVariable("left_variant", GdVariantType.VARIANT);
            leftOperandId = "left_variant";
        }
        if (packRight) {
            func.createAndAddVariable("right_variant", GdVariantType.VARIANT);
            rightOperandId = "right_variant";
        }
        func.createAndAddVariable("result", returnType);

        if (packLeft) {
            entry(func).instructions().add(new PackVariantInsn("left_variant", leftName));
        }
        if (packRight) {
            entry(func).instructions().add(new PackVariantInsn("right_variant", rightName));
        }
        entry(func).instructions().add(new BinaryOpInsn("result", operator, leftOperandId, rightOperandId));
        entry(func).instructions().add(new ReturnInsn("result"));
        return func;
    }

    private static @NotNull LirFunctionDef newMethod(@NotNull String name,
                                                     @NotNull GdType returnType,
                                                     @NotNull GdObjectType selfType) {
        var func = new LirFunctionDef(name);
        func.setReturnType(returnType);
        func.addParameter(new LirParameterDef("self", selfType, null, func));
        func.addBasicBlock(new LirBasicBlock("entry"));
        func.setEntryBlockId("entry");
        return func;
    }

    private static @NotNull LirBasicBlock entry(@NotNull LirFunctionDef functionDef) {
        return functionDef.getBasicBlock("entry");
    }

    private static @NotNull InvalidInsnException extractInvalidInsnCause(@NotNull Throwable throwable) {
        for (var current = throwable; current != null; current = current.getCause()) {
            if (current instanceof InvalidInsnException invalidInsn) {
                return invalidInsn;
            }
        }
        throw new AssertionError("InvalidInsnException cause not found", throwable);
    }

    private static @NotNull String runtimeScript() {
        return """
                extends Node
                
                const TARGET_NODE_NAME = "OperatorNode"
                const EPSILON = 0.0001
                
                func _ready() -> void:
                    var target = get_parent().get_node_or_null(TARGET_NODE_NAME)
                    if target == null:
                        push_error("Target node missing.")
                        return
                
                    _check_e1_power(target)
                    _check_e2_object_nil_compare(target)
                    _check_e4_in_original_order(target)
                    _check_e5_variant_mixed(target)
                    _check_e5_variant_string_to_variant(target)
                    _check_e5_variant_to_bool_unpack(target)
                    _check_e5_variant_to_concrete_unpack(target)
                    _check_e6_runtime_fail_path(target)
                    _check_e7_lifecycle_stress(target)
                    _check_string_ops(target)
                    _check_vector_ops(target)
                    _check_matrix_ops(target)
                
                func _check_e1_power(target: Node) -> void:
                    var float_result = float(target.call("pow_float_int", 2.5, 3))
                    var int_result = int(target.call("pow_int_int", 3, 4))
                    var int_negative_exp_result = int(target.call("pow_int_int", 2, -3))
                    if _scalar_close(float_result, 15.625) and int_result == 81 and int_negative_exp_result == 0:
                        print("E1 power check passed.")
                    else:
                        push_error("E1 power check failed.")
                
                func _check_e2_object_nil_compare(target: Node) -> void:
                    var a = Node.new()
                    var b = Node.new()
                    var cond = true
                    cond = cond and bool(target.call("obj_eq_local_null"))
                    cond = cond and not bool(target.call("obj_eq_param_with_local_null", a))
                    cond = cond and bool(target.call("obj_eq", a, a))
                    cond = cond and bool(target.call("obj_ne", a, b))
                    cond = cond and bool(target.call("nil_eq_nil"))
                    cond = cond and not bool(target.call("nil_ne_nil"))
                    cond = cond and bool(target.call("nil_eq_local_obj_null"))
                    cond = cond and not bool(target.call("nil_eq_obj", a))
                    cond = cond and not bool(target.call("nil_eq_int", 42))
                    if cond:
                        print("E2 object/nil compare check passed.")
                    else:
                        push_error("E2 object/nil compare check failed.")
                
                func _check_e4_in_original_order(target: Node) -> void:
                    var arr = [1, 2, 3]
                    var in_true = bool(target.call("contains_int_in_array", 2, arr))
                    var in_false = bool(target.call("contains_int_in_array", 9, arr))
                    if in_true and not in_false:
                        print("E4 IN original-order check passed.")
                    else:
                        push_error("E4 IN original-order check failed.")
                
                func _check_e5_variant_mixed(target: Node) -> void:
                    var result_variant = target.call("variant_add_to_variant", 2, 3)
                    if int(result_variant) == 5:
                        print("E5 variant mixed check passed.")
                    else:
                        push_error("E5 variant mixed check failed.")
                
                func _check_e5_variant_string_to_variant(target: Node) -> void:
                    var result_variant = target.call("variant_concat_to_variant", "a", "b")
                    if typeof(result_variant) == TYPE_STRING and str(result_variant) == "ab":
                        print("E5 variant string->variant check passed.")
                    else:
                        push_error("E5 variant string->variant check failed.")
                
                func _check_e5_variant_to_bool_unpack(target: Node) -> void:
                    var equal_true = bool(target.call("variant_equal_to_bool", 7, 7))
                    var equal_false = bool(target.call("variant_equal_to_bool", 7, 9))
                    if equal_true and not equal_false:
                        print("E5 variant->bool unpack check passed.")
                    else:
                        push_error("E5 variant->bool unpack check failed.")
                
                func _check_e5_variant_to_concrete_unpack(target: Node) -> void:
                    var int_result = int(target.call("variant_add_to_int", 2, 3))
                    var string_result = str(target.call("variant_concat_to_string_unpack", "a", "b"))
                    var vec_result = target.call("variant_add_vec2_to_vec2", Vector2(1.0, 2.0), Vector2(3.0, 4.0))
                    var ok = int_result == 5
                    ok = ok and string_result == "ab"
                    ok = ok and typeof(vec_result) == TYPE_VECTOR2 and _vec2_close(vec_result, Vector2(4.0, 6.0))
                    if ok:
                        print("E5 variant->concrete unpack check passed.")
                    else:
                        push_error("E5 variant->concrete unpack check failed.")
                
                func _check_e6_runtime_fail_path(target: Node) -> void:
                    var fallback_value = target.call("variant_divide_to_variant", "a", "b")
                    if fallback_value == null:
                        print("E6 runtime fail-path check passed.")
                    else:
                        push_error("E6 runtime fail-path check failed.")
                
                func _check_e7_lifecycle_stress(target: Node) -> void:
                    var ok = true
                    for _i in range(2000):
                        var s = str(target.call("variant_concat_to_variant", "a", "b"))
                        if s != "ab":
                            ok = false
                            break
                    if ok:
                        print("E7 lifecycle stress check passed.")
                    else:
                        push_error("E7 lifecycle stress check failed.")
                
                func _check_string_ops(target: Node) -> void:
                    var concat_result = str(target.call("concat_text", "Hello, ", "GDCC"))
                    var equal_true = bool(target.call("equals_text", "abc", "abc"))
                    var equal_false = bool(target.call("equals_text", "abc", "xyz"))
                    if concat_result == "Hello, GDCC":
                        print("string concat check passed.")
                    else:
                        push_error("string concat check failed.")
                    if equal_true and not equal_false:
                        print("string compare check passed.")
                    else:
                        push_error("string compare check failed.")
                
                func _check_vector_ops(target: Node) -> void:
                    var vec_a = Vector2(1.5, -2.0)
                    var vec_b = Vector2(0.5, 3.0)
                    var vec_result = target.call("add_vec2", vec_a, vec_b)
                    if typeof(vec_result) == TYPE_VECTOR2 and _vec2_close(vec_result, vec_a + vec_b):
                        print("vector add check passed.")
                    else:
                        push_error("vector add check failed.")
                
                func _check_matrix_ops(target: Node) -> void:
                    var left_t = Transform2D(Vector2(1.0, 0.0), Vector2(0.0, 1.0), Vector2(2.0, 3.0))
                    var right_t = Transform2D(Vector2(0.0, -1.0), Vector2(1.0, 0.0), Vector2(4.0, -2.0))
                    var matrix_result = target.call("mul_transform2d", left_t, right_t)
                    var expected_t = left_t * right_t
                    if typeof(matrix_result) == TYPE_TRANSFORM2D and _transform2d_close(matrix_result, expected_t):
                        print("matrix multiply check passed.")
                    else:
                        push_error("matrix multiply check failed.")
                
                func _scalar_close(a: float, b: float) -> bool:
                    return absf(a - b) <= EPSILON
                
                func _vec2_close(a: Vector2, b: Vector2) -> bool:
                    return _scalar_close(a.x, b.x) and _scalar_close(a.y, b.y)
                
                func _transform2d_close(a: Transform2D, b: Transform2D) -> bool:
                    return _vec2_close(a.x, b.x) and _vec2_close(a.y, b.y) and _vec2_close(a.origin, b.origin)
                """;
    }
}
