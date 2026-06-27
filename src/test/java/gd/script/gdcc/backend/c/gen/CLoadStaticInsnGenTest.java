package gd.script.gdcc.backend.c.gen;

import gd.script.gdcc.backend.CodegenContext;
import gd.script.gdcc.backend.ProjectInfo;
import gd.script.gdcc.enums.GodotVersion;
import gd.script.gdcc.exception.InvalidInsnException;
import gd.script.gdcc.gdextension.ExtensionAPI;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.gdextension.ExtensionEnumValue;
import gd.script.gdcc.gdextension.ExtensionGdClass;
import gd.script.gdcc.gdextension.ExtensionGlobalConstant;
import gd.script.gdcc.gdextension.ExtensionGlobalEnum;
import gd.script.gdcc.gdextension.ExtensionSingleton;
import gd.script.gdcc.lir.LirBasicBlock;
import gd.script.gdcc.lir.LirClassDef;
import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.lir.LirModule;
import gd.script.gdcc.lir.insn.LoadStaticInsn;
import gd.script.gdcc.lir.insn.ReturnInsn;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdFloatVectorType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdStringType;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.type.GdVoidType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CLoadStaticInsnGenTest {
    @Test
    @DisplayName("load_static should load global enum value")
    void shouldLoadGlobalEnumValue() {
        var api = new ExtensionAPI(
                null,
                List.of(),
                List.of(),
                List.of(new ExtensionGlobalEnum("Side", false, List.of(new ExtensionEnumValue("SIDE_LEFT", 0)))),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
        var body = generateBody(api, setupLoadStaticFunction(GdIntType.INT, new LoadStaticInsn("out", "Side", "SIDE_LEFT")));
        assertTrue(body.contains("$out = 0;"));
    }

    @Test
    @DisplayName("load_static should preserve int64 global enum values")
    void shouldLoadInt64GlobalEnumValue() {
        var api = new ExtensionAPI(
                null,
                List.of(),
                List.of(),
                List.of(new ExtensionGlobalEnum(
                        "WideFlags",
                        true,
                        List.of(new ExtensionEnumValue("WIDE_FLAG", 34_359_738_368L))
                )),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
        var body = generateBody(api, setupLoadStaticFunction(GdIntType.INT, new LoadStaticInsn("out", "WideFlags", "WIDE_FLAG")));
        assertTrue(body.contains("$out = 34359738368;"));
        assertFalse(body.contains("$out = 0;"));
    }

    @Test
    @DisplayName("load_static should load @GlobalScope global constant values")
    void shouldLoadGlobalScopeGlobalConstantValue() {
        var api = new ExtensionAPI(
                null,
                List.of(),
                List.of(),
                List.of(new ExtensionGlobalConstant("GDCC_TEST_BIG_FLAG", 4_294_967_296L, true)),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
        var body = generateBody(
                api,
                setupLoadStaticFunction(GdIntType.INT, new LoadStaticInsn("out", "@GlobalScope", "GDCC_TEST_BIG_FLAG"))
        );
        assertTrue(body.contains("$out = 4294967296;"));
        assertFalse(body.contains("$out = 0;"));
    }

    @Test
    @DisplayName("load_static should load @GlobalScope singleton property as borrowed object receiver")
    void shouldLoadGlobalScopeSingletonPropertyValue() {
        var body = generateBody(
                singletonFixtureApi(),
                setupLoadStaticFunction(
                        new GdObjectType("Node"),
                        new LoadStaticInsn("out", "@GlobalScope", "GameSingleton")
                )
        );

        assertTrue(body.contains("godot_GameSingleton_singleton()"));
        assertFalse(body.contains("godot_Node_singleton()"));
    }

    @Test
    @DisplayName("load_static should materialize builtin Vector3 constant")
    void shouldLoadBuiltinVector3Constant() throws IOException {
        var api = ExtensionApiLoader.loadDefault();
        var body = generateBody(api, setupLoadStaticFunction(GdFloatVectorType.VECTOR3, new LoadStaticInsn("out", "Vector3", "BACK")));
        assertTrue(body.contains("godot_new_Vector3_with_float_float_float(0, 0, 1)"));
    }

    @Test
    @DisplayName("load_static should map INF literals to godot_inf")
    void shouldMapBuiltinInfLiteralToGodotInf() throws IOException {
        var api = ExtensionApiLoader.loadDefault();
        var body = generateBody(api, setupLoadStaticFunction(GdFloatVectorType.VECTOR3, new LoadStaticInsn("out", "Vector3", "INF")));
        assertTrue(body.contains("godot_inf"));
    }

    @Test
    @DisplayName("load_static should load engine class integer constants")
    void shouldLoadEngineClassIntegerConstant() throws IOException {
        var api = ExtensionApiLoader.loadDefault();
        var nodeClass = api.classes().stream()
                .filter(clazz -> "Node".equals(clazz.name()))
                .findFirst()
                .orElseThrow();
        var constant = nodeClass.constants().stream()
                .filter(entry -> "NOTIFICATION_ENTER_TREE".equals(entry.name()))
                .findFirst()
                .orElseThrow();

        var body = generateBody(api, setupLoadStaticFunction(GdIntType.INT,
                new LoadStaticInsn("out", "Node", "NOTIFICATION_ENTER_TREE")));
        assertTrue(body.contains("$out = " + constant.value().trim() + ";"));
    }

    @Test
    @DisplayName("load_static should load engine class enum values as integer literals")
    void shouldLoadEngineClassEnumValue() throws IOException {
        var api = ExtensionApiLoader.loadDefault();
        var inputClass = api.classes().stream()
                .filter(clazz -> "Input".equals(clazz.name()))
                .findFirst()
                .orElseThrow();
        var enumValue = inputClass.enums().stream()
                .flatMap(e -> e.values().stream())
                .filter(value -> "MOUSE_MODE_VISIBLE".equals(value.name()))
                .findFirst()
                .orElseThrow();

        var body = generateBody(api, setupLoadStaticFunction(GdIntType.INT,
                new LoadStaticInsn("out", "Input", "MOUSE_MODE_VISIBLE")));
        assertTrue(body.contains("$out = " + enumValue.value() + ";"));
    }

    @Test
    @DisplayName("load_static should load inherited engine class integer constants")
    void shouldLoadInheritedEngineClassIntegerConstant() {
        var api = inheritedEngineStaticFixtureApi();
        var body = generateBody(api, setupLoadStaticFunction(GdIntType.INT,
                new LoadStaticInsn("out", "ChildInput", "PARENT_LIMIT")));
        assertTrue(body.contains("$out = 42;"));
        assertFalse(body.contains("$out = 0;"));
    }

    @Test
    @DisplayName("load_static should load inherited engine class enum values")
    void shouldLoadInheritedEngineClassEnumValue() {
        var api = inheritedEngineStaticFixtureApi();
        var body = generateBody(api, setupLoadStaticFunction(GdIntType.INT,
                new LoadStaticInsn("out", "ChildInput", "PARENT_MOUSE_MODE")));
        assertTrue(body.contains("$out = 7;"));
        assertFalse(body.contains("$out = 0;"));
    }

    @Test
    @DisplayName("load_static should load builtin class enum values as integer literals")
    void shouldLoadBuiltinClassEnumValue() throws IOException {
        var api = ExtensionApiLoader.loadDefault();
        var vector3Class = api.builtinClasses().stream()
                .filter(clazz -> "Vector3".equals(clazz.name()))
                .findFirst()
                .orElseThrow();
        var enumValue = vector3Class.enums().stream()
                .flatMap(e -> e.values().stream())
                .filter(value -> "AXIS_X".equals(value.name()))
                .findFirst()
                .orElseThrow();

        var body = generateBody(api, setupLoadStaticFunction(GdIntType.INT,
                new LoadStaticInsn("out", "Vector3", "AXIS_X")));
        assertTrue(body.contains("$out = " + enumValue.value() + ";"));
    }

    @Test
    @DisplayName("load_static should reject missing engine class enum value")
    void shouldRejectMissingEngineClassEnumValue() throws IOException {
        var api = ExtensionApiLoader.loadDefault();
        var ex = assertThrows(InvalidInsnException.class, () -> generateBody(api,
                setupLoadStaticFunction(GdIntType.INT, new LoadStaticInsn("out", "Input", "MOUSE_MODE_MISSING"))));
        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("not found"));
        assertTrue(ex.getMessage().contains("Input"));
    }

    @Test
    @DisplayName("load_static should reject missing builtin class enum value")
    void shouldRejectMissingBuiltinClassEnumValue() throws IOException {
        var api = ExtensionApiLoader.loadDefault();
        var ex = assertThrows(InvalidInsnException.class, () -> generateBody(api,
                setupLoadStaticFunction(GdIntType.INT, new LoadStaticInsn("out", "Vector3", "AXIS_MISSING"))));
        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("not found"));
        assertTrue(ex.getMessage().contains("Vector3"));
    }

    @Test
    @DisplayName("load_static should reject engine class enum value with incompatible target type")
    void shouldRejectEngineClassEnumValueIncompatibleTargetType() throws IOException {
        var api = ExtensionApiLoader.loadDefault();
        var ex = assertThrows(InvalidInsnException.class, () -> generateBody(api,
                setupLoadStaticFunction(GdStringType.STRING, new LoadStaticInsn("out", "Input", "MOUSE_MODE_VISIBLE"))));
        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("not assignable"));
    }

    @Test
    @DisplayName("load_static should reject missing inherited engine static member")
    void shouldRejectMissingInheritedEngineStaticMember() {
        var api = inheritedEngineStaticFixtureApi();
        var ex = assertThrows(InvalidInsnException.class, () -> generateBody(api,
                setupLoadStaticFunction(GdIntType.INT, new LoadStaticInsn("out", "ChildInput", "MISSING_STATIC"))));
        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("not found"));
        assertTrue(ex.getMessage().contains("ChildInput"));
    }

    @Test
    @DisplayName("load_static should reject inherited engine class enum value with incompatible target type")
    void shouldRejectInheritedEngineClassEnumValueIncompatibleTargetType() {
        var api = inheritedEngineStaticFixtureApi();
        var ex = assertThrows(InvalidInsnException.class, () -> generateBody(api,
                setupLoadStaticFunction(GdStringType.STRING, new LoadStaticInsn("out", "ChildInput", "PARENT_MOUSE_MODE"))));
        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("not assignable"));
    }

    @Test
    @DisplayName("load_static should preserve int64 engine class enum values")
    void shouldLoadInt64EngineClassEnumValue() {
        var engineClass = new ExtensionGdClass(
                "WideFlags",
                false,
                true,
                "Object",
                "core",
                List.of(new ExtensionGdClass.ClassEnum(
                        "WideFlag", true, List.of(new ExtensionEnumValue("WIDE_FLAG", 3_435_973_836_8L))
                )),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
        var api = new ExtensionAPI(
                null, List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(engineClass), List.of(), List.of()
        );
        var body = generateBody(api, setupLoadStaticFunction(GdIntType.INT,
                new LoadStaticInsn("out", "WideFlags", "WIDE_FLAG")));
        assertTrue(body.contains("$out = 34359738368;"));
        assertFalse(body.contains("$out = 0;"));
    }

    @Test
    @DisplayName("load_static should render negative engine class enum values")
    void shouldLoadNegativeEngineClassEnumValue() {
        var engineClass = new ExtensionGdClass(
                "ResourceUID",
                false,
                true,
                "Object",
                "core",
                List.of(new ExtensionGdClass.ClassEnum(
                        "InvalidId", false, List.of(new ExtensionEnumValue("INVALID_ID", -1L))
                )),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
        var api = new ExtensionAPI(
                null, List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(engineClass), List.of(), List.of()
        );
        var body = generateBody(api, setupLoadStaticFunction(GdIntType.INT,
                new LoadStaticInsn("out", "ResourceUID", "INVALID_ID")));
        assertTrue(body.contains("$out = -1;"));
    }

    @Test
    @DisplayName("load_static should reject non-integer engine class constants")
    void shouldRejectNonIntegerEngineClassConstant() {
        var api = new ExtensionAPI(
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(new ExtensionGdClass(
                        "Node",
                        false,
                        true,
                        "Object",
                        "core",
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(new ExtensionGdClass.ConstantInfo("NOT_INT", "3.14"))
                )),
                List.of(),
                List.of()
        );

        var ex = assertThrows(InvalidInsnException.class, () -> generateBody(api,
                setupLoadStaticFunction(GdIntType.INT, new LoadStaticInsn("out", "Node", "NOT_INT"))));
        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("not an integer literal"));
    }

    @Test
    @DisplayName("load_static should reject inherited non-integer engine class constants")
    void shouldRejectInheritedNonIntegerEngineClassConstant() {
        var parentClass = new ExtensionGdClass(
                "BadStaticParent",
                false,
                true,
                "Object",
                "core",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(new ExtensionGdClass.ConstantInfo("NOT_INT", "3.14"))
        );
        var childClass = new ExtensionGdClass(
                "BadStaticChild",
                false,
                true,
                "BadStaticParent",
                "core",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
        var api = new ExtensionAPI(
                null, List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(parentClass, childClass), List.of(), List.of()
        );

        var ex = assertThrows(InvalidInsnException.class, () -> generateBody(api,
                setupLoadStaticFunction(GdIntType.INT, new LoadStaticInsn("out", "BadStaticChild", "NOT_INT"))));
        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("not an integer literal"));
        assertTrue(ex.getMessage().contains("BadStaticParent"));
    }

    @Test
    @DisplayName("load_static should reject incompatible target type")
    void shouldRejectIncompatibleBuiltinTargetType() throws IOException {
        var api = ExtensionApiLoader.loadDefault();
        var ex = assertThrows(InvalidInsnException.class, () -> generateBody(api,
                setupLoadStaticFunction(GdIntType.INT, new LoadStaticInsn("out", "Vector3", "BACK"))));
        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("not assignable"));
    }

    @Test
    @DisplayName("load_static should reject singleton property with incompatible target type")
    void shouldRejectIncompatibleSingletonTargetType() {
        var ex = assertThrows(InvalidInsnException.class, () -> generateBody(
                singletonFixtureApi(),
                setupLoadStaticFunction(GdIntType.INT, new LoadStaticInsn("out", "@GlobalScope", "GameSingleton"))
        ));

        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("not assignable from singleton type 'Node'"));
    }

    @Test
    @DisplayName("load_static should reject missing @GlobalScope global constant")
    void shouldRejectMissingGlobalScopeGlobalConstant() {
        var api = new ExtensionAPI(
                null,
                List.of(),
                List.of(),
                List.of(new ExtensionGlobalConstant("GDCC_TEST_BIG_FLAG", 4_294_967_296L, true)),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
        var ex = assertThrows(InvalidInsnException.class, () -> generateBody(api,
                setupLoadStaticFunction(GdIntType.INT, new LoadStaticInsn("out", "@GlobalScope", "MISSING"))));
        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("Global constant 'MISSING' not found"));
    }

    @Test
    @DisplayName("load_static should reject reference result variable")
    void shouldRejectReferenceResultVariable() {
        var api = new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        var clazz = new LirClassDef("Worker", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("load_ref");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddRefVariable("out", GdIntType.INT);

        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new LoadStaticInsn("out", "Side", "SIDE_LEFT"));
        entry.appendInstruction(new ReturnInsn(null));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        clazz.addFunction(func);

        var module = new LirModule("test_module", List.of(clazz));
        var codegen = new CCodegen();
        codegen.prepare(newContext(api), module);

        var ex = assertThrows(InvalidInsnException.class, () -> codegen.generateFuncBody(clazz, func));
        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("cannot be a reference"));
    }

    private LirFunctionDef setupLoadStaticFunction(GdType resultType, LoadStaticInsn instruction) {
        var func = new LirFunctionDef("load_static_test");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("out", resultType);

        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(instruction);
        entry.appendInstruction(new ReturnInsn(null));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        return func;
    }

    private ExtensionAPI singletonFixtureApi() {
        return new ExtensionAPI(
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(new ExtensionGdClass(
                        "Node",
                        false,
                        true,
                        "Object",
                        "core",
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of()
                )),
                List.of(new ExtensionSingleton("GameSingleton", "Node")),
                List.of()
        );
    }

    private ExtensionAPI inheritedEngineStaticFixtureApi() {
        var parentClass = new ExtensionGdClass(
                "BaseInput",
                false,
                true,
                "Object",
                "core",
                List.of(new ExtensionGdClass.ClassEnum(
                        "MouseMode", false, List.of(new ExtensionEnumValue("PARENT_MOUSE_MODE", 7))
                )),
                List.of(),
                List.of(),
                List.of(),
                List.of(new ExtensionGdClass.ConstantInfo("PARENT_LIMIT", "42"))
        );
        var childClass = new ExtensionGdClass(
                "ChildInput",
                false,
                true,
                "BaseInput",
                "core",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
        return new ExtensionAPI(
                null, List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(parentClass, childClass), List.of(), List.of()
        );
    }

    private String generateBody(ExtensionAPI api, LirFunctionDef func) {
        var clazz = new LirClassDef("Worker", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        clazz.addFunction(func);
        var module = new LirModule("test_module", List.of(clazz));
        var codegen = new CCodegen();
        codegen.prepare(newContext(api), module);
        return codegen.generateFuncBody(clazz, func);
    }

    private CodegenContext newContext(ExtensionAPI api) {
        var projectInfo = new ProjectInfo("load_static_test", GodotVersion.V451, Path.of(".")) {
        };
        return new CodegenContext(projectInfo, new ClassRegistry(api));
    }
}
