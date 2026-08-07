package gd.script.gdcc.backend.c.gen;

import gd.script.gdcc.backend.CodegenContext;
import gd.script.gdcc.backend.ProjectInfo;
import gd.script.gdcc.enums.GodotVersion;
import gd.script.gdcc.exception.InvalidInsnException;
import gd.script.gdcc.gdextension.ExtensionAPI;
import gd.script.gdcc.lir.LirBasicBlock;
import gd.script.gdcc.lir.LirClassDef;
import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.lir.LirModule;
import gd.script.gdcc.lir.insn.BuiltinCastInsn;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdArrayType;
import gd.script.gdcc.type.GdBoolType;
import gd.script.gdcc.type.GdDictionaryType;
import gd.script.gdcc.type.GdFloatType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdStringType;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.type.GdVariantType;
import gd.script.gdcc.type.GdVoidType;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Positive/negative codegen coverage for backend `builtin_cast`.
class BuiltinCastInsnGenTest {
    @Test
    @DisplayName("int as float packs once and constructs with FLOAT enum")
    void intAsFloatEmitsConstruct() {
        var body = generate(GdIntType.INT, "float", GdFloatType.FLOAT);
        assertTrue(body.contains("godot_variant_construct"), body);
        assertTrue(body.contains("GDEXTENSION_VARIANT_TYPE_FLOAT"), body);
        assertTrue(body.contains("GDEXTENSION_CALL_OK"), body);
        assertTrue(body.contains("godot_new_Variant_with_int") || body.contains("godot_new_Variant_with_Int"), body);
        assertTrue(body.contains("godot_new_float_with_Variant")
                || body.contains("godot_new_Float_with_Variant")
                || body.contains("godot_new_float64_with_Variant")
                || body.matches("(?s).*godot_new_.*[Ff]loat.*_with_Variant.*"), body);
        assertFalse(body.contains("construct_builtin"), body);
    }

    @Test
    @DisplayName("Variant as int does not pack source twice")
    void variantAsIntDoesNotRepack() {
        var body = generate(GdVariantType.VARIANT, "int", GdIntType.INT);
        assertTrue(body.contains("godot_variant_construct"), body);
        assertTrue(body.contains("GDEXTENSION_VARIANT_TYPE_INT"), body);
        assertFalse(body.contains("godot_new_Variant_with_Variant"), body);
        assertFalse(body.contains("godot_new_Variant_nil"), body);
    }

    @Test
    @DisplayName("Array[int] target uses base ARRAY enum and no typed metadata guard")
    void parameterizedArrayUsesBaseEnumOnly() {
        var target = new GdArrayType(GdIntType.INT);
        var body = generate(GdVariantType.VARIANT, "Array[int]", target);
        assertTrue(body.contains("GDEXTENSION_VARIANT_TYPE_ARRAY"), body);
        assertFalse(body.contains("gdcc_is_instance_of_typed_array"), body);
        assertFalse(body.contains("gdcc_check_variant_type_object"), body);
        assertFalse(body.contains("set_typed"), body);
    }

    @Test
    @DisplayName("failure path prints runtime error and returns default")
    void constructFailureEmitsRuntimeErrorAndDefaultReturn() {
        var body = generate(GdIntType.INT, "String", GdStringType.STRING);
        assertTrue(body.contains("GDCC_PRINT_RUNTIME_ERROR"), body);
        assertTrue(body.contains("goto __finally__") || body.contains("return "), body);
    }

    @Test
    @DisplayName("exact same type identity LIR fails closed")
    void identityFailsClosed() {
        var ex = assertThrows(InvalidInsnException.class,
                () -> generate(GdIntType.INT, "int", GdIntType.INT));
        assertTrue(ex.getMessage().contains("IDENTITY") || ex.getMessage().contains("does not accept"),
                ex.getMessage());
    }

    @Test
    @DisplayName("Variant target LIR fails closed")
    void variantTargetFailsClosed() {
        var ex = assertThrows(InvalidInsnException.class,
                () -> generate(GdIntType.INT, "Variant", GdVariantType.VARIANT));
        assertTrue(ex.getMessage().contains("Variant") || ex.getMessage().contains("does not accept")
                        || ex.getMessage().contains("non-Object"),
                ex.getMessage());
    }

    @Test
    @DisplayName("Object target LIR fails closed")
    void objectTargetFailsClosed() {
        var ex = assertThrows(InvalidInsnException.class, () -> {
            var workerClass = new LirClassDef("Worker", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
            var func = new LirFunctionDef("bad_obj");
            func.setReturnType(GdVoidType.VOID);
            func.createAndAddVariable("value", GdVariantType.VARIANT);
            func.createAndAddVariable("result", new GdObjectType("Node"));
            var entry = new LirBasicBlock("entry");
            entry.appendInstruction(new BuiltinCastInsn("result", "Node", "value"));
            func.addBasicBlock(entry);
            func.setEntryBlockId("entry");
            workerClass.addFunction(func);
            var codegen = new CCodegen();
            codegen.prepare(newContext(emptyApi(), List.of(workerClass)),
                    new LirModule("test_module", List.of(workerClass)));
            codegen.generateFuncBody(workerClass, func);
        });
        assertTrue(ex.getMessage().contains("Object") || ex.getMessage().contains("cannot be resolved")
                        || ex.getMessage().contains("non-Object"),
                ex.getMessage());
    }

    @Test
    @DisplayName("result type mismatch fails closed")
    void resultTypeMismatchFailsClosed() {
        var ex = assertThrows(InvalidInsnException.class,
                () -> generate(GdIntType.INT, "float", GdIntType.INT));
        assertTrue(ex.getMessage().contains("does not match") || ex.getMessage().contains("result"),
                ex.getMessage());
    }

    @Test
    @DisplayName("empty target type name fails closed")
    void emptyTargetFailsClosed() {
        var ex = assertThrows(InvalidInsnException.class,
                () -> generate(GdIntType.INT, "  ", GdFloatType.FLOAT));
        assertTrue(ex.getMessage().contains("empty"), ex.getMessage());
    }

    @Test
    @DisplayName("bool as String is allowed construct path")
    void boolAsStringAllowed() {
        var body = generate(GdBoolType.BOOL, "String", GdStringType.STRING);
        assertTrue(body.contains("godot_variant_construct"), body);
        assertTrue(body.contains("GDEXTENSION_VARIANT_TYPE_STRING"), body);
    }

    @Test
    @DisplayName("Dictionary[String, int] target uses base DICTIONARY enum only")
    void parameterizedDictionaryUsesBaseEnumOnly() {
        var target = new GdDictionaryType(GdStringType.STRING, GdIntType.INT);
        var body = generate(GdVariantType.VARIANT, "Dictionary[String, int]", target);
        assertTrue(body.contains("GDEXTENSION_VARIANT_TYPE_DICTIONARY"), body);
        assertFalse(body.contains("gdcc_is_instance_of_typed_dictionary"), body);
        assertFalse(body.contains("set_typed"), body);
    }

    @Test
    @DisplayName("Variant ref parameter is not double-addressed")
    void variantRefParamIsNotDoubleAddressed() {
        var workerClass = new LirClassDef("Worker", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("ref_cast");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddRefVariable("value", GdVariantType.VARIANT);
        func.createAndAddVariable("result", GdIntType.INT);
        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new BuiltinCastInsn("result", "int", "value"));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        workerClass.addFunction(func);

        var codegen = new CCodegen();
        codegen.prepare(newContext(emptyApi(), List.of(workerClass)),
                new LirModule("test_module", List.of(workerClass)));
        var body = codegen.generateFuncBody(workerClass, func);
        assertTrue(body.contains("godot_variant_construct"), body);
        // ref Variant is already a pointer; must not emit &&$value.
        assertFalse(body.contains("&&$value"), body);
        assertTrue(body.contains("{ $value }") || body.contains("{($value)}")
                        || body.matches("(?s).*\\{\\s*\\$value\\s*}.*"),
                body);
    }

    @Test
    @DisplayName("Array[int] target with bare Array result fails closed")
    void containerCovarianceResultFailsClosed() {
        var ex = assertThrows(InvalidInsnException.class,
                () -> generate(GdVariantType.VARIANT, "Array[int]", new GdArrayType(GdVariantType.VARIANT)));
        assertTrue(ex.getMessage().contains("does not match"), ex.getMessage());
    }

    private static @NotNull String generate(
            @NotNull GdType valueType,
            @NotNull String targetTypeName,
            @NotNull GdType resultType
    ) {
        var workerClass = new LirClassDef("Worker", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("builtin_cast_test");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("value", valueType);
        func.createAndAddVariable("result", resultType);
        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new BuiltinCastInsn("result", targetTypeName, "value"));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        workerClass.addFunction(func);

        var codegen = new CCodegen();
        codegen.prepare(newContext(emptyApi(), List.of(workerClass)),
                new LirModule("test_module", List.of(workerClass)));
        return codegen.generateFuncBody(workerClass, func);
    }

    private static @NotNull ExtensionAPI emptyApi() {
        return new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
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
