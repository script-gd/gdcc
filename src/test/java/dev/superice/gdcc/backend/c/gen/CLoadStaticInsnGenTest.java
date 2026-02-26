package dev.superice.gdcc.backend.c.gen;

import dev.superice.gdcc.backend.CodegenContext;
import dev.superice.gdcc.backend.ProjectInfo;
import dev.superice.gdcc.enums.GodotVersion;
import dev.superice.gdcc.exception.InvalidInsnException;
import dev.superice.gdcc.gdextension.ExtensionAPI;
import dev.superice.gdcc.gdextension.ExtensionApiLoader;
import dev.superice.gdcc.gdextension.ExtensionEnumValue;
import dev.superice.gdcc.gdextension.ExtensionGdClass;
import dev.superice.gdcc.gdextension.ExtensionGlobalEnum;
import dev.superice.gdcc.lir.LirBasicBlock;
import dev.superice.gdcc.lir.LirClassDef;
import dev.superice.gdcc.lir.LirFunctionDef;
import dev.superice.gdcc.lir.LirModule;
import dev.superice.gdcc.lir.insn.LoadStaticInsn;
import dev.superice.gdcc.lir.insn.ReturnInsn;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.type.GdFloatVectorType;
import dev.superice.gdcc.type.GdIntType;
import dev.superice.gdcc.type.GdType;
import dev.superice.gdcc.type.GdVoidType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
    @DisplayName("load_static should reject incompatible target type")
    void shouldRejectIncompatibleBuiltinTargetType() throws IOException {
        var api = ExtensionApiLoader.loadDefault();
        var ex = assertThrows(InvalidInsnException.class, () -> generateBody(api,
                setupLoadStaticFunction(GdIntType.INT, new LoadStaticInsn("out", "Vector3", "BACK"))));
        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("not assignable"));
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
        entry.instructions().add(new LoadStaticInsn("out", "Side", "SIDE_LEFT"));
        entry.instructions().add(new ReturnInsn(null));
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
        entry.instructions().add(instruction);
        entry.instructions().add(new ReturnInsn(null));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        return func;
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
