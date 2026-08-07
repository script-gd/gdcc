package gd.script.gdcc.backend.c.gen;

import gd.script.gdcc.backend.CodegenContext;
import gd.script.gdcc.backend.ProjectInfo;
import gd.script.gdcc.enums.GodotVersion;
import gd.script.gdcc.exception.InvalidInsnException;
import gd.script.gdcc.gdextension.ExtensionAPI;
import gd.script.gdcc.gdextension.ExtensionGdClass;
import gd.script.gdcc.lir.LirBasicBlock;
import gd.script.gdcc.lir.LirClassDef;
import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.lir.LirModule;
import gd.script.gdcc.lir.insn.ObjectCastInsn;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdNilType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.type.GdVariantType;
import gd.script.gdcc.type.GdVoidType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Positive/negative codegen coverage for backend `object_cast`.
class ObjectCastInsnGenTest {
    @Test
    @DisplayName("Node as Node2D uses raw+id cast helper and preserves ID via from_raw")
    void objectDowncastUsesHelper() {
        var body = generateWithEngine("obj", new GdObjectType("Node"), "Node2D", new GdObjectType("Node2D"));
        assertTrue(body.contains("gdcc_object_cast_raw_and_id"), body);
        assertTrue(body.contains("GD_STATIC_SN(u8\"Node2D\")"), body);
        assertTrue(body.contains("_from_raw"), body);
        assertFalse(body.contains("godot_object_cast_to"), body);
        assertFalse(body.contains("gdcc_check_variant_type_object"), body);
        assertFalse(body.contains("_from_variant"), body);
    }

    @Test
    @DisplayName("Variant as Node uses variant cast helper")
    void variantAsObjectUsesVariantHelper() {
        var body = generateWithEngine("value", GdVariantType.VARIANT, "Node", new GdObjectType("Node"));
        assertTrue(body.contains("gdcc_object_cast_variant"), body);
        assertTrue(body.contains("GD_STATIC_SN(u8\"Node\")"), body);
        assertTrue(body.contains("_from_raw"), body);
        assertFalse(body.contains("gdcc_check_variant_type_object"), body);
        assertFalse(body.contains("godot_object_cast_to"), body);
    }

    @Test
    @DisplayName("Nil as Node uses variant cast helper and canonical-null failure branch")
    void nilAsObjectEmitsCastPath() {
        var body = generateWithEngine("n", GdNilType.NIL, "Node", new GdObjectType("Node"));
        assertTrue(body.contains("gdcc_object_cast_variant"), body);
        assertFalse(body.contains("gdcc_object_cast_raw_and_id"), body);
        assertTrue(body.contains("!= NULL"), body);
        assertTrue(body.contains("{ 0 }"), body);
        assertTrue(body.contains("_from_raw"), body);
    }

    @Test
    @DisplayName("Variant ref parameter is not double-addressed for object_cast")
    void variantRefParamIsNotDoubleAddressed() {
        var workerClass = new LirClassDef("Worker", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("ref_ocast");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddRefVariable("value", GdVariantType.VARIANT);
        func.createAndAddVariable("result", new GdObjectType("Node"));
        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new ObjectCastInsn("result", "Node", "value"));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        workerClass.addFunction(func);

        var codegen = new CCodegen();
        codegen.prepare(newContext(engineApi(), List.of(workerClass)),
                new LirModule("test_module", List.of(workerClass)));
        var body = codegen.generateFuncBody(workerClass, func);
        assertTrue(body.contains("gdcc_object_cast_variant"), body);
        assertFalse(body.contains("&&$value"), body);
        assertTrue(body.contains("gdcc_object_cast_variant($value,")
                        || body.contains("gdcc_object_cast_variant( $value,"),
                body);
    }

    @Test
    @DisplayName("static upcast hand-written object_cast still emits runtime helper")
    void handWrittenUpcastStillEmitsHelper() {
        var body = generateWithEngine("obj", new GdObjectType("Node2D"), "Node", new GdObjectType("Node"));
        assertTrue(body.contains("gdcc_object_cast_raw_and_id"), body);
        assertTrue(body.contains("GD_STATIC_SN(u8\"Node\")"), body);
    }

    @Test
    @DisplayName("null resultId is validated no-op")
    void nullResultIsNoOp() {
        var body = generateWithEngine("obj", new GdObjectType("Node"), "Node2D", null);
        assertFalse(body.contains("gdcc_object_cast_"), body);
        assertFalse(body.contains("_from_raw"), body);
    }

    @Test
    @DisplayName("int as Node fails closed")
    void nonObjectSourceFailsClosed() {
        var ex = assertThrows(InvalidInsnException.class,
                () -> generateWithEngine("value", GdIntType.INT, "Node", new GdObjectType("Node")));
        assertTrue(ex.getMessage().contains("Object/Variant/Nil") || ex.getMessage().contains("invalid"),
                ex.getMessage());
    }

    @Test
    @DisplayName("unrelated object classes fail closed")
    void unrelatedClassesFailClosed() {
        var ex = assertThrows(InvalidInsnException.class, () -> {
            var body = generate(
                    engineApiWithResource(),
                    "obj",
                    new GdObjectType("Node"),
                    "Resource",
                    new GdObjectType("Resource")
            );
            assertFalse(body.isEmpty());
        });
        assertTrue(ex.getMessage().contains("invalid") || ex.getMessage().contains("INVALID"),
                ex.getMessage());
    }

    @Test
    @DisplayName("unknown class name fails closed (no unresolved runtime fallback)")
    void unknownClassFailsClosed() {
        var ex = assertThrows(InvalidInsnException.class,
                () -> generateWithEngine("obj", new GdObjectType("Node"), "FutureEnemy",
                        new GdObjectType("FutureEnemy")));
        assertTrue(ex.getMessage().contains("registry-proven") || ex.getMessage().contains("cannot"),
                ex.getMessage());
    }

    @Test
    @DisplayName("result type mismatch vs class_name fails closed")
    void resultClassMismatchFailsClosed() {
        var ex = assertThrows(InvalidInsnException.class,
                () -> generateWithEngine("obj", new GdObjectType("Node"), "Node2D", new GdObjectType("Node")));
        assertTrue(ex.getMessage().contains("does not match"), ex.getMessage());
    }

    @Test
    @DisplayName("empty class name fails closed")
    void emptyClassNameFailsClosed() {
        var ex = assertThrows(InvalidInsnException.class,
                () -> generateWithEngine("obj", new GdObjectType("Node"), "  ", new GdObjectType("Node")));
        assertTrue(ex.getMessage().contains("empty"), ex.getMessage());
    }

    @Test
    @DisplayName("never uses forbidden unpack/class-tag helpers")
    void neverUsesForbiddenHelpers() {
        var body = generateWithEngine("value", GdVariantType.VARIANT, "Node", new GdObjectType("Node"));
        assertFalse(body.contains("godot_object_cast_to"), body);
        assertFalse(body.contains("gdcc_check_variant_type_object"), body);
        assertFalse(body.contains("valueOfCastedVar"), body);
        assertTrue(body.contains("gdcc_object_cast_variant"), body);
    }

    private static @NotNull String generateWithEngine(
            @NotNull String valueId,
            @NotNull GdType valueType,
            @NotNull String className,
            @Nullable GdType resultType
    ) {
        return generate(engineApi(), valueId, valueType, className, resultType);
    }

    private static @NotNull String generate(
            @NotNull ExtensionAPI api,
            @NotNull String valueId,
            @NotNull GdType valueType,
            @NotNull String className,
            @Nullable GdType resultType
    ) {
        var workerClass = new LirClassDef("Worker", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("object_cast_test");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable(valueId, valueType);
        String resultId = null;
        if (resultType != null) {
            func.createAndAddVariable("result", resultType);
            resultId = "result";
        }
        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new ObjectCastInsn(resultId, className, valueId));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        workerClass.addFunction(func);

        var codegen = new CCodegen();
        codegen.prepare(newContext(api, List.of(workerClass)),
                new LirModule("test_module", List.of(workerClass)));
        return codegen.generateFuncBody(workerClass, func);
    }

    private static @NotNull ExtensionAPI engineApi() {
        return engineApiWithClasses(
                new ExtensionGdClass("Object", false, false, "", "core",
                        List.of(), List.of(), List.of(), List.of(), List.of()),
                new ExtensionGdClass("Node", false, false, "Object", "core",
                        List.of(), List.of(), List.of(), List.of(), List.of()),
                new ExtensionGdClass("Node2D", false, false, "Node", "core",
                        List.of(), List.of(), List.of(), List.of(), List.of())
        );
    }

    private static @NotNull ExtensionAPI engineApiWithResource() {
        return engineApiWithClasses(
                new ExtensionGdClass("Object", false, false, "", "core",
                        List.of(), List.of(), List.of(), List.of(), List.of()),
                new ExtensionGdClass("Node", false, false, "Object", "core",
                        List.of(), List.of(), List.of(), List.of(), List.of()),
                new ExtensionGdClass("Resource", false, false, "Object", "core",
                        List.of(), List.of(), List.of(), List.of(), List.of())
        );
    }

    private static @NotNull ExtensionAPI engineApiWithClasses(ExtensionGdClass... classes) {
        return new ExtensionAPI(
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(classes),
                List.of(),
                List.of()
        );
    }

    private static @NotNull CodegenContext newContext(
            @NotNull ExtensionAPI api,
            @NotNull List<LirClassDef> gdccClasses
    ) {
        var classRegistry = new ClassRegistry(api);
        for (var gdccClass : gdccClasses) {
            classRegistry.addGdccClass(gdccClass);
        }
        ProjectInfo projectInfo = new ProjectInfo("TestProject", GodotVersion.V451, Path.of(".")) {
        };
        return new CodegenContext(projectInfo, classRegistry, true);
    }
}
