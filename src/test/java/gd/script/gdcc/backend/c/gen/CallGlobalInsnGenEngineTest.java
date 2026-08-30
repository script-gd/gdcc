package gd.script.gdcc.backend.c.gen;

import gd.script.gdcc.backend.CodegenContext;
import gd.script.gdcc.backend.c.build.COptimizationLevel;
import gd.script.gdcc.backend.c.build.CProjectBuilder;
import gd.script.gdcc.backend.c.build.CProjectInfo;
import gd.script.gdcc.backend.c.build.GodotGdextensionTestRunner;
import gd.script.gdcc.backend.c.build.TargetPlatform;
import gd.script.gdcc.backend.c.build.ZigUtil;
import gd.script.gdcc.enums.GodotVersion;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.lir.LirBasicBlock;
import gd.script.gdcc.lir.LirClassDef;
import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.lir.LirInstruction;
import gd.script.gdcc.lir.LirModule;
import gd.script.gdcc.lir.LirParameterDef;
import gd.script.gdcc.lir.insn.CallGlobalInsn;
import gd.script.gdcc.lir.insn.LiteralStringInsn;
import gd.script.gdcc.lir.insn.PackVariantInsn;
import gd.script.gdcc.lir.insn.ReturnInsn;
import gd.script.gdcc.lir.insn.UnpackVariantInsn;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdArrayType;
import gd.script.gdcc.type.GdBoolType;
import gd.script.gdcc.type.GdDictionaryType;
import gd.script.gdcc.type.GdFloatType;
import gd.script.gdcc.type.GdFloatVectorType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdIntVectorType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdPackedNumericArrayType;
import gd.script.gdcc.type.GdStringType;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.type.GdVariantType;
import gd.script.gdcc.type.GdVoidType;
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

class CallGlobalInsnGenEngineTest {

    @Test
    @DisplayName("CALL_GLOBAL should call tan/fposmod/lerp/max/print in real engine")
    void callGlobalUtilitiesShouldRunInRealGodot() throws IOException, InterruptedException {
        if (!hasZig()) {
            Assumptions.abort("Zig not found; skipping integration test");
            return;
        }

        var tempDir = Path.of("tmp/test/call_global_engine");
        Files.createDirectories(tempDir);

        var projectInfo = new CProjectInfo(
                "call_global_engine",
                GodotVersion.V451,
                tempDir,
                COptimizationLevel.DEBUG,
                TargetPlatform.getNativePlatform()
        );
        var builder = new CProjectBuilder();
        builder.initProject(projectInfo);

        var callGlobalClass = newCallGlobalEngineClass();
        var module = new LirModule("call_global_engine_module", List.of(callGlobalClass));
        var api = ExtensionApiLoader.loadVersion(GodotVersion.V451);
        var codegen = new CCodegen();
        codegen.prepare(new CodegenContext(projectInfo, new ClassRegistry(api)), module);

        var buildResult = builder.buildProject(projectInfo, codegen);
        assertTrue(buildResult.success(), "Compilation should succeed. Build log:\n" + buildResult.buildLog());
        assertFalse(buildResult.artifacts().isEmpty(), "Compilation should produce extension artifacts.");

        var runner = new GodotGdextensionTestRunner(Path.of("test_project"));
        runner.prepareProject(new GodotGdextensionTestRunner.ProjectSetup(
                buildResult.artifacts(),
                List.of(new GodotGdextensionTestRunner.SceneNodeSpec(
                        "CallGlobalNode",
                        callGlobalClass.getName(),
                        ".",
                        Map.of()
                )),
                new GodotGdextensionTestRunner.TestScriptSpec(testScript())
        ));

        var runResult = runner.run(true);
        var combinedOutput = runResult.combinedOutput();

        assertTrue(runResult.stopSignalSeen(), "Godot run should emit stop signal.\nOutput:\n" + combinedOutput);
        assertTrue(combinedOutput.contains("tan check passed."), "tan should pass.\nOutput:\n" + combinedOutput);
        assertTrue(combinedOutput.contains("fposmod check passed."), "fposmod should pass.\nOutput:\n" + combinedOutput);
        assertTrue(combinedOutput.contains("lerp check passed."), "lerp should pass.\nOutput:\n" + combinedOutput);
        assertTrue(combinedOutput.contains("max check passed."), "max should pass.\nOutput:\n" + combinedOutput);
        assertTrue(combinedOutput.contains("[engine] call_print from extension"), "print should be emitted by extension call.\nOutput:\n" + combinedOutput);
        assertFalse(combinedOutput.contains("check failed"), "No check should fail.\nOutput:\n" + combinedOutput);
    }

    @Test
    @DisplayName("CALL_GLOBAL should execute Variant writeback helper family matrix in real engine")
    void callGlobalVariantWritebackHelperShouldMatchRuntimeFamilyMatrix() throws IOException, InterruptedException {
        if (!hasZig()) {
            Assumptions.abort("Zig not found; skipping integration test");
            return;
        }

        var tempDir = Path.of("tmp/test/call_global_variant_writeback_helper_engine");
        Files.createDirectories(tempDir);

        var projectInfo = new CProjectInfo(
                "call_global_variant_writeback_helper_engine",
                GodotVersion.V451,
                tempDir,
                COptimizationLevel.DEBUG,
                TargetPlatform.getNativePlatform()
        );
        var builder = new CProjectBuilder();
        builder.initProject(projectInfo);

        var probeClass = newVariantWritebackHelperProbeClass();
        var module = new LirModule("call_global_variant_writeback_helper_module", List.of(probeClass));
        var api = ExtensionApiLoader.loadVersion(GodotVersion.V451);
        var codegen = new CCodegen();
        codegen.prepare(new CodegenContext(projectInfo, new ClassRegistry(api)), module);

        var buildResult = builder.buildProject(projectInfo, codegen);
        assertTrue(buildResult.success(), "Compilation should succeed. Build log:\n" + buildResult.buildLog());
        assertFalse(buildResult.artifacts().isEmpty(), "Compilation should produce extension artifacts.");

        var runner = new GodotGdextensionTestRunner(Path.of("test_project"));
        runner.prepareProject(new GodotGdextensionTestRunner.ProjectSetup(
                buildResult.artifacts(),
                List.of(new GodotGdextensionTestRunner.SceneNodeSpec(
                        "CallGlobalWritebackHelperNode",
                        probeClass.getName(),
                        ".",
                        Map.of()
                )),
                new GodotGdextensionTestRunner.TestScriptSpec(variantWritebackHelperTestScript())
        ));

        var runResult = runner.run(true);
        var combinedOutput = runResult.combinedOutput();

        assertTrue(runResult.stopSignalSeen(), "Godot run should emit stop signal.\nOutput:\n" + combinedOutput);
        assertTrue(combinedOutput.contains("helper string true check passed."), "String should require writeback.\nOutput:\n" + combinedOutput);
        assertTrue(combinedOutput.contains("helper vector2 true check passed."), "Vector2 should require writeback.\nOutput:\n" + combinedOutput);
        assertTrue(combinedOutput.contains("helper vector3i true check passed."), "Vector3i should require writeback.\nOutput:\n" + combinedOutput);
        assertTrue(combinedOutput.contains("helper packed array true check passed."), "PackedInt32Array should require writeback.\nOutput:\n" + combinedOutput);
        assertTrue(combinedOutput.contains("helper array false check passed."), "Array should skip writeback.\nOutput:\n" + combinedOutput);
        assertTrue(combinedOutput.contains("helper dictionary false check passed."), "Dictionary should skip writeback.\nOutput:\n" + combinedOutput);
        assertTrue(combinedOutput.contains("helper object false check passed."), "Object should skip writeback.\nOutput:\n" + combinedOutput);
        assertFalse(combinedOutput.contains("check failed"), "No helper-matrix check should fail.\nOutput:\n" + combinedOutput);
    }

    private static boolean hasZig() {
        return ZigUtil.findZig() != null;
    }

    @Test
    @DisplayName("CALL_GLOBAL should execute gdcc_len/gdcc_char/gdcc_ord semantics in real engine")
    void callGlobalGdScriptLanguageFunctionsShouldRunInRealGodot() throws IOException, InterruptedException {
        if (!hasZig()) {
            Assumptions.abort("Zig not found; skipping integration test");
            return;
        }

        var tempDir = Path.of("tmp/test/call_global_language_functions_engine");
        Files.createDirectories(tempDir);

        var projectInfo = new CProjectInfo(
                "call_global_language_functions_engine",
                GodotVersion.V451,
                tempDir,
                COptimizationLevel.DEBUG,
                TargetPlatform.getNativePlatform()
        );
        var builder = new CProjectBuilder();
        builder.initProject(projectInfo);

        var probeClass = newLanguageFunctionProbeClass();
        var module = new LirModule("call_global_language_functions_engine_module", List.of(probeClass));
        var api = ExtensionApiLoader.loadVersion(GodotVersion.V451);
        var codegen = new CCodegen();
        codegen.prepare(new CodegenContext(projectInfo, new ClassRegistry(api)), module);

        var buildResult = builder.buildProject(projectInfo, codegen);
        assertTrue(buildResult.success(), "Compilation should succeed. Build log:\n" + buildResult.buildLog());
        assertFalse(buildResult.artifacts().isEmpty(), "Compilation should produce extension artifacts.");

        var runner = new GodotGdextensionTestRunner(Path.of("test_project"));
        runner.prepareProject(new GodotGdextensionTestRunner.ProjectSetup(
                buildResult.artifacts(),
                List.of(new GodotGdextensionTestRunner.SceneNodeSpec(
                        "LanguageFunctionsNode",
                        probeClass.getName(),
                        ".",
                        Map.of()
                )),
                new GodotGdextensionTestRunner.TestScriptSpec(languageFunctionTestScript())
        ));

        var runResult = runner.run(true);
        var combinedOutput = runResult.combinedOutput();

        // The script uses the engine's own len/char/ord as the semantic oracle for every probe.
        assertTrue(runResult.stopSignalSeen(), "Godot run should emit stop signal.\nOutput:\n" + combinedOutput);
        assertTrue(combinedOutput.contains("len checks passed."), "len semantics should match.\nOutput:\n" + combinedOutput);
        assertTrue(combinedOutput.contains("char checks passed."), "char semantics should match.\nOutput:\n" + combinedOutput);
        assertTrue(combinedOutput.contains("ord checks passed."), "ord semantics should match.\nOutput:\n" + combinedOutput);
        assertFalse(combinedOutput.contains("check failed"), "No language-function check should fail.\nOutput:\n" + combinedOutput);
    }

    /// Probe class exposing the three synthetic language functions as engine-callable methods:
    /// `probe_len(Variant) -> int`, `probe_char(int) -> String`, `probe_ord(String) -> int`.
    private static LirClassDef newLanguageFunctionProbeClass() {
        var clazz = new LirClassDef("GDLanguageFunctionsNode", "Node");
        clazz.setSourceFile("call_global_language_functions_engine.gd");
        var selfType = new GdObjectType(clazz.getName());

        var lenFunc = newMethod("probe_len", GdIntType.INT, selfType);
        lenFunc.addParameter(new LirParameterDef("value", GdVariantType.VARIANT, null, lenFunc));
        lenFunc.createAndAddVariable("result", GdIntType.INT);
        entry(lenFunc).appendInstruction(new CallGlobalInsn("result", "len", List.of(varRef("value"))));
        entry(lenFunc).appendInstruction(new ReturnInsn("result"));
        clazz.addFunction(lenFunc);

        var charFunc = newMethod("probe_char", GdStringType.STRING, selfType);
        charFunc.addParameter(new LirParameterDef("code", GdIntType.INT, null, charFunc));
        charFunc.createAndAddVariable("result", GdStringType.STRING);
        entry(charFunc).appendInstruction(new CallGlobalInsn("result", "char", List.of(varRef("code"))));
        entry(charFunc).appendInstruction(new ReturnInsn("result"));
        clazz.addFunction(charFunc);

        var ordFunc = newMethod("probe_ord", GdIntType.INT, selfType);
        ordFunc.addParameter(new LirParameterDef("text", GdStringType.STRING, null, ordFunc));
        ordFunc.createAndAddVariable("result", GdIntType.INT);
        entry(ordFunc).appendInstruction(new CallGlobalInsn("result", "ord", List.of(varRef("text"))));
        entry(ordFunc).appendInstruction(new ReturnInsn("result"));
        clazz.addFunction(ordFunc);

        return clazz;
    }

    private static String languageFunctionTestScript() {
        // Note: `char(0xD800)`/`char(0x110000)` exercise the Godot 4.5 contract where surrogates
        // and out-of-range code points are replaced with U+FFFD by String.chr without a CallError;
        // both sides must agree. `probe_len(42)`/`probe_char(-1)`/`probe_ord("ab")` hit the
        // gdcc_* error paths, which print a runtime error and return the type default.
        return """
                extends Node
                
                const TARGET_NODE_NAME = "LanguageFunctionsNode"
                
                func _ready() -> void:
                    var target = get_parent().get_node_or_null(TARGET_NODE_NAME)
                    if target == null:
                        push_error("Target node missing.")
                        return
                
                    var len_cases = [
                        "hello",
                        &"abc",
                        [1, 2, 3],
                        {"a": 1, "b": 2},
                        PackedByteArray([1, 2]),
                        PackedInt32Array([1, 2, 3]),
                        PackedInt64Array([1]),
                        PackedFloat32Array([0.5, 1.5]),
                        PackedFloat64Array([0.5]),
                        PackedStringArray(["a", "b", "c"]),
                        PackedVector2Array([Vector2.ZERO]),
                        PackedVector3Array([Vector3.ZERO]),
                        PackedColorArray([Color.WHITE]),
                        PackedVector4Array([Vector4.ZERO]),
                    ]
                    var len_ok = true
                    for value in len_cases:
                        if int(target.call("probe_len", value)) != len(value):
                            push_error("len check failed for value of type " + type_string(typeof(value)))
                            len_ok = false
                    if int(target.call("probe_len", 42)) != 0:
                        push_error("len unsupported-type check failed.")
                        len_ok = false
                    if len_ok:
                        print("len checks passed.")
                
                    var char_ok = true
                    for code in [0, 65, 0xD800, 0x110000, 4294967295]:
                        if str(target.call("probe_char", code)) != char(code):
                            push_error("char check failed for code: " + str(code))
                            char_ok = false
                    if str(target.call("probe_char", -1)) != "":
                        push_error("char negative check failed.")
                        char_ok = false
                    if char_ok:
                        print("char checks passed.")
                
                    var ord_ok = true
                    for text in ["A", char(0xE9), char(0x1F4AF)]:
                        if int(target.call("probe_ord", text)) != ord(text):
                            push_error("ord check failed for code point: " + str(ord(text)))
                            ord_ok = false
                    if int(target.call("probe_ord", "ab")) != 0:
                        push_error("ord multi-char check failed.")
                        ord_ok = false
                    if int(target.call("probe_ord", "")) != 0:
                        push_error("ord empty check failed.")
                        ord_ok = false
                    if ord_ok:
                        print("ord checks passed.")
                """;
    }

    private static LirClassDef newCallGlobalEngineClass() {
        var clazz = new LirClassDef("GDCallGlobalEngineNode", "Node");
        clazz.setSourceFile("call_global_engine.gd");

        var selfType = new GdObjectType(clazz.getName());
        clazz.addFunction(newTanFunction(selfType));
        clazz.addFunction(newFposmodFunction(selfType));
        clazz.addFunction(newLerpFunction(selfType));
        clazz.addFunction(newMaxFunction(selfType));
        clazz.addFunction(newPrintFunction(selfType));
        return clazz;
    }

    private static LirClassDef newVariantWritebackHelperProbeClass() {
        var clazz = new LirClassDef("GDCallGlobalWritebackHelperNode", "Node");
        clazz.setSourceFile("call_global_variant_writeback_helper.gd");

        var selfType = new GdObjectType(clazz.getName());
        clazz.addFunction(newVariantWritebackProbeFunction("probe_string", GdStringType.STRING, selfType));
        clazz.addFunction(newVariantWritebackProbeFunction("probe_vector2", GdFloatVectorType.VECTOR2, selfType));
        clazz.addFunction(newVariantWritebackProbeFunction("probe_vector3i", GdIntVectorType.VECTOR3I, selfType));
        clazz.addFunction(newVariantWritebackProbeFunction(
                "probe_packed_int32_array",
                GdPackedNumericArrayType.PACKED_INT32_ARRAY,
                selfType
        ));
        clazz.addFunction(newVariantWritebackProbeFunction(
                "probe_array",
                new GdArrayType(GdVariantType.VARIANT),
                selfType
        ));
        clazz.addFunction(newVariantWritebackProbeFunction(
                "probe_dictionary",
                new GdDictionaryType(GdVariantType.VARIANT, GdVariantType.VARIANT),
                selfType
        ));
        clazz.addFunction(newVariantWritebackProbeFunction(
                "probe_node_object",
                new GdObjectType("Node"),
                selfType
        ));
        return clazz;
    }

    private static LirFunctionDef newTanFunction(GdObjectType selfType) {
        var func = newMethod("call_tan", GdFloatType.FLOAT, selfType);
        func.addParameter(new LirParameterDef("angleRad", GdFloatType.FLOAT, null, func));
        func.createAndAddVariable("result", GdFloatType.FLOAT);

        entry(func).appendInstruction(new CallGlobalInsn(
                "result",
                "tan",
                List.of(varRef("angleRad"))
        ));
        entry(func).appendInstruction(new ReturnInsn("result"));
        return func;
    }

    private static LirFunctionDef newFposmodFunction(GdObjectType selfType) {
        var func = newMethod("call_fposmod", GdFloatType.FLOAT, selfType);
        func.addParameter(new LirParameterDef("x", GdFloatType.FLOAT, null, func));
        func.addParameter(new LirParameterDef("y", GdFloatType.FLOAT, null, func));
        func.createAndAddVariable("result", GdFloatType.FLOAT);

        entry(func).appendInstruction(new CallGlobalInsn(
                "result",
                "fposmod",
                List.of(varRef("x"), varRef("y"))
        ));
        entry(func).appendInstruction(new ReturnInsn("result"));
        return func;
    }

    private static LirFunctionDef newLerpFunction(GdObjectType selfType) {
        var func = newMethod("call_lerp", GdFloatType.FLOAT, selfType);
        func.addParameter(new LirParameterDef("from", GdFloatType.FLOAT, null, func));
        func.addParameter(new LirParameterDef("to", GdFloatType.FLOAT, null, func));
        func.addParameter(new LirParameterDef("weight", GdFloatType.FLOAT, null, func));

        func.createAndAddVariable("fromVariant", GdVariantType.VARIANT);
        func.createAndAddVariable("toVariant", GdVariantType.VARIANT);
        func.createAndAddVariable("weightVariant", GdVariantType.VARIANT);
        func.createAndAddVariable("resultVariant", GdVariantType.VARIANT);
        func.createAndAddVariable("result", GdFloatType.FLOAT);

        entry(func).appendInstruction(new PackVariantInsn("fromVariant", "from"));
        entry(func).appendInstruction(new PackVariantInsn("toVariant", "to"));
        entry(func).appendInstruction(new PackVariantInsn("weightVariant", "weight"));
        entry(func).appendInstruction(new CallGlobalInsn(
                "resultVariant",
                "lerp",
                List.of(varRef("fromVariant"), varRef("toVariant"), varRef("weightVariant"))
        ));
        entry(func).appendInstruction(new UnpackVariantInsn("result", "resultVariant"));
        entry(func).appendInstruction(new ReturnInsn("result"));
        return func;
    }

    private static LirFunctionDef newMaxFunction(GdObjectType selfType) {
        var func = newMethod("call_max", GdFloatType.FLOAT, selfType);
        func.addParameter(new LirParameterDef("a", GdFloatType.FLOAT, null, func));
        func.addParameter(new LirParameterDef("b", GdFloatType.FLOAT, null, func));
        func.addParameter(new LirParameterDef("c", GdFloatType.FLOAT, null, func));

        func.createAndAddVariable("aVariant", GdVariantType.VARIANT);
        func.createAndAddVariable("bVariant", GdVariantType.VARIANT);
        func.createAndAddVariable("cVariant", GdVariantType.VARIANT);
        func.createAndAddVariable("resultVariant", GdVariantType.VARIANT);
        func.createAndAddVariable("result", GdFloatType.FLOAT);

        entry(func).appendInstruction(new PackVariantInsn("aVariant", "a"));
        entry(func).appendInstruction(new PackVariantInsn("bVariant", "b"));
        entry(func).appendInstruction(new PackVariantInsn("cVariant", "c"));
        entry(func).appendInstruction(new CallGlobalInsn(
                "resultVariant",
                "max",
                List.of(varRef("aVariant"), varRef("bVariant"), varRef("cVariant"))
        ));
        entry(func).appendInstruction(new UnpackVariantInsn("result", "resultVariant"));
        entry(func).appendInstruction(new ReturnInsn("result"));
        return func;
    }

    private static LirFunctionDef newPrintFunction(GdObjectType selfType) {
        var func = newMethod("call_print", GdVoidType.VOID, selfType);
        func.createAndAddVariable("messageText", GdStringType.STRING);
        func.createAndAddVariable("messageVariant", GdVariantType.VARIANT);

        entry(func).appendInstruction(new LiteralStringInsn("messageText", "[engine] call_print from extension"));
        entry(func).appendInstruction(new PackVariantInsn("messageVariant", "messageText"));
        entry(func).appendInstruction(new CallGlobalInsn(
                null,
                "print",
                List.of(varRef("messageVariant"))
        ));
        entry(func).appendInstruction(new ReturnInsn(null));
        return func;
    }

    private static LirFunctionDef newVariantWritebackProbeFunction(
            String name,
            GdType probeType,
            GdObjectType selfType
    ) {
        var func = newMethod(name, GdBoolType.BOOL, selfType);
        func.addParameter(new LirParameterDef("value", probeType, null, func));
        func.createAndAddVariable("carrier", GdVariantType.VARIANT);
        func.createAndAddVariable("requiresWriteback", GdBoolType.BOOL);

        entry(func).appendInstruction(new PackVariantInsn("carrier", "value"));
        entry(func).appendInstruction(new CallGlobalInsn(
                "requiresWriteback",
                "gdcc_variant_requires_writeback",
                List.of(varRef("carrier"))
        ));
        entry(func).appendInstruction(new ReturnInsn("requiresWriteback"));
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
                
                const TARGET_NODE_NAME = "CallGlobalNode"
                const EPSILON = 0.001
                
                func _ready() -> void:
                    var target = get_parent().get_node_or_null(TARGET_NODE_NAME)
                    if target == null:
                        push_error("Target node missing.")
                        return
                
                    var tan_value = float(target.call("call_tan", 0.5))
                    if absf(tan_value - tan(0.5)) <= EPSILON:
                        print("tan check passed.")
                    else:
                        push_error("tan check failed.")
                
                    var fposmod_value = float(target.call("call_fposmod", -1.5, 1.0))
                    if absf(fposmod_value - fposmod(-1.5, 1.0)) <= EPSILON:
                        print("fposmod check passed.")
                    else:
                        push_error("fposmod check failed.")
                
                    var lerp_value = float(target.call("call_lerp", 10.0, 20.0, 0.25))
                    if absf(lerp_value - lerp(10.0, 20.0, 0.25)) <= EPSILON:
                        print("lerp check passed.")
                    else:
                        push_error("lerp check failed.")
                
                    var max_value = float(target.call("call_max", 1.25, 4.5, 2.75))
                    if absf(max_value - max(1.25, 4.5, 2.75)) <= EPSILON:
                        print("max check passed.")
                    else:
                        push_error("max check failed.")
                
                    target.call("call_print")
                """;
    }

    private static String variantWritebackHelperTestScript() {
        return """
                extends Node
                
                const TARGET_NODE_NAME = "CallGlobalWritebackHelperNode"
                
                func _ready() -> void:
                    var target = get_parent().get_node_or_null(TARGET_NODE_NAME)
                    if target == null:
                        push_error("Target node missing.")
                        return
                
                    if bool(target.call("probe_string", "alpha")):
                        print("helper string true check passed.")
                    else:
                        push_error("helper string true check failed.")
                
                    if bool(target.call("probe_vector2", Vector2(1.0, 2.0))):
                        print("helper vector2 true check passed.")
                    else:
                        push_error("helper vector2 true check failed.")
                
                    if bool(target.call("probe_vector3i", Vector3i(1, 2, 3))):
                        print("helper vector3i true check passed.")
                    else:
                        push_error("helper vector3i true check failed.")
                
                    if bool(target.call("probe_packed_int32_array", PackedInt32Array([1, 2]))):
                        print("helper packed array true check passed.")
                    else:
                        push_error("helper packed array true check failed.")
                
                    if not bool(target.call("probe_array", [1, 2])):
                        print("helper array false check passed.")
                    else:
                        push_error("helper array false check failed.")
                
                    if not bool(target.call("probe_dictionary", {"alpha": 1})):
                        print("helper dictionary false check passed.")
                    else:
                        push_error("helper dictionary false check failed.")
                
                    if not bool(target.call("probe_node_object", Node.new())):
                        print("helper object false check passed.")
                    else:
                        push_error("helper object false check failed.")
                """;
    }
}
