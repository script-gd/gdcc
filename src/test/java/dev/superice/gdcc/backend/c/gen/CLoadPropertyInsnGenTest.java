package dev.superice.gdcc.backend.c.gen;

import dev.superice.gdcc.backend.CodegenContext;
import dev.superice.gdcc.backend.ProjectInfo;
import dev.superice.gdcc.enums.GodotVersion;
import dev.superice.gdcc.exception.InvalidInsnException;
import dev.superice.gdcc.gdextension.ExtensionAPI;
import dev.superice.gdcc.gdextension.ExtensionBuiltinClass;
import dev.superice.gdcc.gdextension.ExtensionGdClass;
import dev.superice.gdcc.lir.LirBasicBlock;
import dev.superice.gdcc.lir.LirClassDef;
import dev.superice.gdcc.lir.LirFunctionDef;
import dev.superice.gdcc.lir.LirModule;
import dev.superice.gdcc.lir.LirParameterDef;
import dev.superice.gdcc.lir.LirPropertyDef;
import dev.superice.gdcc.lir.insn.LoadPropertyInsn;
import dev.superice.gdcc.lir.insn.ReturnInsn;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.type.GdArrayType;
import dev.superice.gdcc.type.GdFloatType;
import dev.superice.gdcc.type.GdFloatVectorType;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdStringNameType;
import dev.superice.gdcc.type.GdStringType;
import dev.superice.gdcc.type.GdVoidType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CLoadPropertyInsnGenTest {
    @Test
    @DisplayName("GDCC getter should load field directly when inside getter")
    void gdccGetterUsesFieldAccessInsideGetter() {
        var gdccClass = new LirClassDef("MyClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var propertyDef = new LirPropertyDef("value", GdStringType.STRING, false, null, "_field_getter_value", null, Map.of());
        gdccClass.addProperty(propertyDef);

        var func = new LirFunctionDef("_field_getter_value");
        func.setReturnType(GdStringType.STRING);
        func.addParameter(new LirParameterDef("self", new GdObjectType("MyClass"), null, func));
        func.createAndAddVariable("tmp", GdStringType.STRING);

        var entry = new LirBasicBlock("entry");
        entry.instructions().add(new LoadPropertyInsn("tmp", "value", "self"));
        entry.instructions().add(new ReturnInsn("tmp"));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        gdccClass.addFunction(func);

        var module = new LirModule("test_module", List.of(gdccClass));
        var ctx = newContext(new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()),
                List.of(gdccClass));

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);

        var body = codegen.generateFuncBody(gdccClass, func);
        assertTrue(body.contains("$self->value"));
        assertFalse(body.contains("MyClass__field_getter_value("));
    }

    @Test
    @DisplayName("GDCC getter should be called outside getter")
    void gdccGetterUsesGetterOutside() {
        var gdccClass = new LirClassDef("MyClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var propertyDef = new LirPropertyDef("value", GdStringType.STRING, false, null, "_field_getter_value", null, Map.of());
        gdccClass.addProperty(propertyDef);

        var func = new LirFunctionDef("use_value");
        func.setReturnType(GdStringType.STRING);
        func.addParameter(new LirParameterDef("obj", new GdObjectType("MyClass"), null, func));
        func.createAndAddVariable("tmp", GdStringType.STRING);

        var entry = new LirBasicBlock("entry");
        entry.instructions().add(new LoadPropertyInsn("tmp", "value", "obj"));
        entry.instructions().add(new ReturnInsn("tmp"));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        gdccClass.addFunction(func);

        var module = new LirModule("test_module", List.of(gdccClass));
        var ctx = newContext(new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()),
                List.of(gdccClass));

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);

        var body = codegen.generateFuncBody(gdccClass, func);
        assertTrue(body.contains("MyClass__field_getter_value($obj)"));
    }

    @Test
    @DisplayName("Engine property should use engine getter")
    void enginePropertyUsesEngineGetter() {
        var nodeClass = new ExtensionGdClass(
                "Node", false, true, "Object", "core",
                List.of(), List.of(), List.of(),
                List.of(new ExtensionGdClass.PropertyInfo("name", "String", true, true, "")),
                List.of()
        );
        var api = new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(nodeClass), List.of(), List.of());

        var gdccClass = new LirClassDef("TestClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("use_node");
        func.setReturnType(GdStringType.STRING);
        func.addParameter(new LirParameterDef("node", new GdObjectType("Node"), null, func));
        func.createAndAddVariable("tmp", GdStringType.STRING);

        var entry = new LirBasicBlock("entry");
        entry.instructions().add(new LoadPropertyInsn("tmp", "name", "node"));
        entry.instructions().add(new ReturnInsn("tmp"));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        gdccClass.addFunction(func);

        var module = new LirModule("test_module", List.of(gdccClass));
        var ctx = newContext(api, List.of(gdccClass));

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);

        var body = codegen.generateFuncBody(gdccClass, func);
        assertTrue(body.contains("godot_Node_get_name($node)"));
    }

    @Test
    @DisplayName("Unknown object type should fallback to godot_Object_get")
    void unknownObjectTypeShouldFallbackToGodotObjectGet() {
        var gdccClass = new LirClassDef("TestClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("get_unknown_prop");
        func.setReturnType(GdStringType.STRING);
        func.addParameter(new LirParameterDef("obj", new GdObjectType("UnknownType"), null, func));
        func.createAndAddVariable("tmp", GdStringType.STRING);

        var entry = new LirBasicBlock("entry");
        entry.instructions().add(new LoadPropertyInsn("tmp", "name", "obj"));
        entry.instructions().add(new ReturnInsn("tmp"));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        gdccClass.addFunction(func);

        var module = new LirModule("test_module", List.of(gdccClass));
        var ctx = newContext(new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()),
                List.of(gdccClass));

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);

        var body = codegen.generateFuncBody(gdccClass, func);
        assertTrue(body.contains("__gdcc_tmp_variant_0 = godot_Object_get($obj, GD_STATIC_SN(u8\"name\"));"));
        assertTrue(body.contains("$tmp = godot_new_String_with_Variant(&__gdcc_tmp_variant_0);"));
        assertFalse(body.contains("godot_UnknownType_get_name("));
    }

    @Test
    @DisplayName("Unknown object type should unpack engine object from variant")
    void unknownObjectTypeShouldUnpackEngineObjectFromVariant() {
        var nodeClass = new ExtensionGdClass(
                "Node", false, true, "Object", "core",
                List.of(), List.of(), List.of(), List.of(), List.of()
        );
        var api = new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(nodeClass), List.of(), List.of());

        var gdccClass = new LirClassDef("TestClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("get_unknown_node_prop");
        func.setReturnType(GdVoidType.VOID);
        func.addParameter(new LirParameterDef("obj", new GdObjectType("UnknownType"), null, func));
        func.createAndAddVariable("tmp", new GdObjectType("Node"));

        var entry = new LirBasicBlock("entry");
        entry.instructions().add(new LoadPropertyInsn("tmp", "child", "obj"));
        entry.instructions().add(new ReturnInsn(null));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        gdccClass.addFunction(func);

        var module = new LirModule("test_module", List.of(gdccClass));
        var ctx = newContext(api, List.of(gdccClass));

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);

        var body = codegen.generateFuncBody(gdccClass, func);
        assertTrue(body.contains("__gdcc_tmp_variant_0 = godot_Object_get($obj, GD_STATIC_SN(u8\"child\"));"));
        assertTrue(body.contains("$tmp = (godot_Node*)godot_new_Object_with_Variant(&__gdcc_tmp_variant_0);"));
        assertFalse(body.contains("godot_UnknownType_get_child("));
    }

    @Test
    @DisplayName("Unknown object type should unpack GDCC object from variant")
    void unknownObjectTypeShouldUnpackGdccObjectFromVariant() {
        var targetClass = new LirClassDef("TargetClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var gdccClass = new LirClassDef("TestClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("get_unknown_gdcc_prop");
        func.setReturnType(GdVoidType.VOID);
        func.addParameter(new LirParameterDef("obj", new GdObjectType("UnknownType"), null, func));
        func.createAndAddVariable("tmp", new GdObjectType("TargetClass"));

        var entry = new LirBasicBlock("entry");
        entry.instructions().add(new LoadPropertyInsn("tmp", "target", "obj"));
        entry.instructions().add(new ReturnInsn(null));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        gdccClass.addFunction(func);

        var module = new LirModule("test_module", List.of(gdccClass, targetClass));
        var ctx = newContext(new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()),
                List.of(gdccClass, targetClass));

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);

        var body = codegen.generateFuncBody(gdccClass, func);
        assertTrue(body.contains("__gdcc_tmp_variant_0 = godot_Object_get($obj, GD_STATIC_SN(u8\"target\"));"));
        assertTrue(body.contains("$tmp = (TargetClass*)godot_new_gdcc_Object_with_Variant(&__gdcc_tmp_variant_0);"));
        assertFalse(body.contains("godot_UnknownType_get_target("));
    }

    @Test
    @DisplayName("Unknown object type should unpack typed Array using normalized symbol name")
    void unknownObjectTypeShouldUnpackTypedArrayWithNormalizedSymbol() {
        var gdccClass = new LirClassDef("TestClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("get_unknown_array_prop");
        func.setReturnType(GdVoidType.VOID);
        func.addParameter(new LirParameterDef("obj", new GdObjectType("UnknownType"), null, func));
        func.createAndAddVariable("tmp", new GdArrayType(GdStringNameType.STRING_NAME));

        var entry = new LirBasicBlock("entry");
        entry.instructions().add(new LoadPropertyInsn("tmp", "items", "obj"));
        entry.instructions().add(new ReturnInsn(null));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        gdccClass.addFunction(func);

        var module = new LirModule("test_module", List.of(gdccClass));
        var ctx = newContext(new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()),
                List.of(gdccClass));

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);

        var body = codegen.generateFuncBody(gdccClass, func);
        assertTrue(body.contains("$tmp = godot_new_Array_with_Variant(&__gdcc_tmp_variant_0);"));
        assertFalse(body.contains("godot_new_Array["));
    }

    @Test
    @DisplayName("Builtin property should use builtin getter")
    void builtinPropertyUsesBuiltinGetter() {
        var vector2Class = new ExtensionBuiltinClass(
                "Vector2", false,
                List.of(), List.of(), List.of(),
                List.of(),
                List.of(new ExtensionBuiltinClass.PropertyInfo("x", "float", true, false, "0")),
                List.of()
        );
        var api = new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), List.of(vector2Class), List.of(), List.of(), List.of());

        var gdccClass = new LirClassDef("TestClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("use_vec");
        func.setReturnType(GdFloatType.FLOAT);
        func.createAndAddVariable("vec", GdFloatVectorType.VECTOR2);
        func.createAndAddVariable("tmp", GdFloatType.FLOAT);

        var entry = new LirBasicBlock("entry");
        entry.instructions().add(new LoadPropertyInsn("tmp", "x", "vec"));
        entry.instructions().add(new ReturnInsn("tmp"));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        gdccClass.addFunction(func);

        var module = new LirModule("test_module", List.of(gdccClass));
        var ctx = newContext(api, List.of(gdccClass));

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);

        var body = codegen.generateFuncBody(gdccClass, func);
        assertTrue(body.contains("godot_Vector2_get_x(&$vec)"));
    }

    @Test
    @DisplayName("Ref result variable should be rejected")
    void refResultVarShouldThrow() {
        var gdccClass = new LirClassDef("MyClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var propertyDef = new LirPropertyDef("value", GdStringType.STRING, false, null, "_field_getter_value", null, Map.of());
        gdccClass.addProperty(propertyDef);

        var func = new LirFunctionDef("use_value");
        func.setReturnType(GdStringType.STRING);
        func.addParameter(new LirParameterDef("obj", new GdObjectType("MyClass"), null, func));
        func.createAndAddRefVariable("tmp", GdStringType.STRING);

        var entry = new LirBasicBlock("entry");
        entry.instructions().add(new LoadPropertyInsn("tmp", "value", "obj"));
        entry.instructions().add(new ReturnInsn("tmp"));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        gdccClass.addFunction(func);

        var module = new LirModule("test_module", List.of(gdccClass));
        var ctx = newContext(new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()),
                List.of(gdccClass));

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);

        var ex = assertThrows(InvalidInsnException.class, () -> codegen.generateFuncBody(gdccClass, func));
        assertInstanceOf(InvalidInsnException.class, ex);
    }

    @Test
    @DisplayName("GDCC getter call should apply assignment semantics for destroyable targets")
    void gdccGetterUsesAssignmentSemantics() {
        var gdccClass = new LirClassDef("MyClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var propertyDef = new LirPropertyDef("value", GdStringType.STRING, false, null, "_field_getter_value", null, Map.of());
        gdccClass.addProperty(propertyDef);

        var func = new LirFunctionDef("use_value");
        func.setReturnType(GdStringType.STRING);
        func.addParameter(new LirParameterDef("obj", new GdObjectType("MyClass"), null, func));
        func.createAndAddVariable("tmp", GdStringType.STRING);

        var entry = new LirBasicBlock("entry");
        entry.instructions().add(new LoadPropertyInsn("tmp", "value", "obj"));
        entry.instructions().add(new ReturnInsn("tmp"));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        gdccClass.addFunction(func);

        var module = new LirModule("test_module", List.of(gdccClass));
        var ctx = newContext(new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()),
                List.of(gdccClass));

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);

        var body = codegen.generateFuncBody(gdccClass, func);
        assertTrue(body.contains("godot_String_destroy(&$tmp)"));
        assertTrue(body.contains("MyClass__field_getter_value($obj)"));
    }

    @Test
    @DisplayName("Builtin ref variable should be passed without extra address-of")
    void builtinPropertyUsesRefVariableWithoutAddressOf() {
        var vector2Class = new ExtensionBuiltinClass(
                "Vector2", false,
                List.of(), List.of(), List.of(),
                List.of(),
                List.of(new ExtensionBuiltinClass.PropertyInfo("x", "float", true, false, "0")),
                List.of()
        );
        var api = new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), List.of(vector2Class), List.of(), List.of(), List.of());

        var gdccClass = new LirClassDef("TestClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("use_vec_ref");
        func.setReturnType(GdFloatType.FLOAT);
        func.createAndAddRefVariable("vec", GdFloatVectorType.VECTOR2);
        func.createAndAddVariable("tmp", GdFloatType.FLOAT);

        var entry = new LirBasicBlock("entry");
        entry.instructions().add(new LoadPropertyInsn("tmp", "x", "vec"));
        entry.instructions().add(new ReturnInsn("tmp"));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        gdccClass.addFunction(func);

        var module = new LirModule("test_module", List.of(gdccClass));
        var ctx = newContext(api, List.of(gdccClass));

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);

        var body = codegen.generateFuncBody(gdccClass, func);
        assertTrue(body.contains("godot_Vector2_get_x($vec)"));
        assertFalse(body.contains("godot_Vector2_get_x(&$vec)"));
    }

    @Test
    @DisplayName("Builtin unreadable property should throw")
    void builtinUnreadablePropertyShouldThrow() {
        var vector2Class = new ExtensionBuiltinClass(
                "Vector2", false,
                List.of(), List.of(), List.of(),
                List.of(),
                List.of(new ExtensionBuiltinClass.PropertyInfo("x", "float", false, false, "0")),
                List.of()
        );
        var api = new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), List.of(vector2Class), List.of(), List.of(), List.of());

        var gdccClass = new LirClassDef("TestClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("use_vec");
        func.setReturnType(GdFloatType.FLOAT);
        func.createAndAddVariable("vec", GdFloatVectorType.VECTOR2);
        func.createAndAddVariable("tmp", GdFloatType.FLOAT);

        var entry = new LirBasicBlock("entry");
        entry.instructions().add(new LoadPropertyInsn("tmp", "x", "vec"));
        entry.instructions().add(new ReturnInsn("tmp"));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        gdccClass.addFunction(func);

        var module = new LirModule("test_module", List.of(gdccClass));
        var ctx = newContext(api, List.of(gdccClass));

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);

        var ex = assertThrows(InvalidInsnException.class, () -> codegen.generateFuncBody(gdccClass, func));
        assertInstanceOf(InvalidInsnException.class, ex);
    }

    @Test
    @DisplayName("GDCC property without getter should throw")
    void gdccPropertyMissingGetterShouldThrow() {
        var gdccClass = new LirClassDef("MyClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var propertyDef = new LirPropertyDef("value", GdStringType.STRING, false, null, null, null, Map.of());
        gdccClass.addProperty(propertyDef);

        var func = new LirFunctionDef("use_value");
        func.setReturnType(GdStringType.STRING);
        func.addParameter(new LirParameterDef("obj", new GdObjectType("MyClass"), null, func));
        func.createAndAddVariable("tmp", GdStringType.STRING);

        var entry = new LirBasicBlock("entry");
        entry.instructions().add(new LoadPropertyInsn("tmp", "value", "obj"));
        entry.instructions().add(new ReturnInsn("tmp"));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        gdccClass.addFunction(func);

        var module = new LirModule("test_module", List.of(gdccClass));
        var ctx = newContext(new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()),
                List.of(gdccClass));

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);

        var ex = assertThrows(InvalidInsnException.class, () -> codegen.generateFuncBody(gdccClass, func));
        assertInstanceOf(InvalidInsnException.class, ex);
    }

    private CodegenContext newContext(ExtensionAPI api, List<LirClassDef> gdccClasses) {
        var classRegistry = new ClassRegistry(api);
        for (var gdccClass : gdccClasses) {
            classRegistry.addGdccClass(gdccClass);
        }
        ProjectInfo projectInfo = new ProjectInfo("TestProject", GodotVersion.V451, Path.of(".")) {
        };
        return new CodegenContext(projectInfo, classRegistry);
    }
}

