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
    @DisplayName("Unreadable engine property should throw")
    void unreadableEnginePropertyShouldThrow() {
        var nodeClass = new ExtensionGdClass(
                "Node", false, true, "Object", "core",
                List.of(), List.of(), List.of(),
                List.of(new ExtensionGdClass.PropertyInfo("name", "String", false, true, "")),
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

        var ex = assertThrows(InvalidInsnException.class, () -> codegen.generateFuncBody(gdccClass, func));
        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("is not readable"), ex.getMessage());
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

    @Test
    @DisplayName("GDCC child receiver should call parent GDCC getter via _super upcast")
    void gdccChildReceiverShouldCallParentGdccGetterViaSuperUpcast() {
        var parentClass = new LirClassDef("ParentClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        parentClass.addProperty(new LirPropertyDef("value", GdStringType.STRING, false, null, "_field_getter_value", null, Map.of()));

        var childClass = new LirClassDef("ChildClass", "ParentClass", false, false, Map.of(), List.of(), List.of(), List.of());
        var hostClass = new LirClassDef("HostClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("load_parent_value");
        func.setReturnType(GdStringType.STRING);
        func.addParameter(new LirParameterDef("child", new GdObjectType("ChildClass"), null, func));
        func.createAndAddVariable("tmp", GdStringType.STRING);

        var entry = new LirBasicBlock("entry");
        entry.instructions().add(new LoadPropertyInsn("tmp", "value", "child"));
        entry.instructions().add(new ReturnInsn("tmp"));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        hostClass.addFunction(func);

        var module = new LirModule("test_module", List.of(hostClass, childClass, parentClass));
        var ctx = newContext(
                new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()),
                List.of(hostClass, childClass, parentClass)
        );

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);

        var body = codegen.generateFuncBody(hostClass, func);
        assertTrue(body.contains("ParentClass__field_getter_value(&($child->_super));"), body);
        assertFalse(body.contains("ParentClass__field_getter_value((ParentClass*)$child);"), body);
        assertFalse(body.contains("ChildClass__field_getter_value("), body);
    }

    @Test
    @DisplayName("Three-level GDCC chain should call top parent getter via _super._super upcast")
    void threeLevelGdccChainShouldCallTopParentGetterViaDoubleSuperUpcast() {
        var parentClass = new LirClassDef("ParentClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        parentClass.addProperty(new LirPropertyDef("value", GdStringType.STRING, false, null, "_field_getter_value", null, Map.of()));

        var childClass = new LirClassDef("ChildClass", "ParentClass", false, false, Map.of(), List.of(), List.of(), List.of());
        var grandChildClass = new LirClassDef("GrandChildClass", "ChildClass", false, false, Map.of(), List.of(), List.of(), List.of());
        var hostClass = new LirClassDef("HostClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());

        var func = new LirFunctionDef("load_top_parent_value");
        func.setReturnType(GdStringType.STRING);
        func.addParameter(new LirParameterDef("grand", new GdObjectType("GrandChildClass"), null, func));
        func.createAndAddVariable("tmp", GdStringType.STRING);

        var entry = new LirBasicBlock("entry");
        entry.instructions().add(new LoadPropertyInsn("tmp", "value", "grand"));
        entry.instructions().add(new ReturnInsn("tmp"));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        hostClass.addFunction(func);

        var module = new LirModule("test_module", List.of(hostClass, grandChildClass, childClass, parentClass));
        var ctx = newContext(
                new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()),
                List.of(hostClass, grandChildClass, childClass, parentClass)
        );

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);

        var body = codegen.generateFuncBody(hostClass, func);
        assertTrue(body.contains("ParentClass__field_getter_value(&($grand->_super._super));"), body);
        assertFalse(body.contains("ParentClass__field_getter_value((ParentClass*)$grand);"), body);
    }

    @Test
    @DisplayName("Shadowed property should resolve nearest owner getter on inheritance chain")
    void shadowedPropertyShouldResolveNearestOwnerGetterOnInheritanceChain() {
        var parentClass = new LirClassDef("ParentClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        parentClass.addProperty(new LirPropertyDef("value", GdStringType.STRING, false, null, "_field_getter_parent_value", null, Map.of()));

        var childClass = new LirClassDef("ChildClass", "ParentClass", false, false, Map.of(), List.of(), List.of(), List.of());
        childClass.addProperty(new LirPropertyDef("value", GdStringType.STRING, false, null, "_field_getter_child_value", null, Map.of()));

        var grandChildClass = new LirClassDef("GrandChildClass", "ChildClass", false, false, Map.of(), List.of(), List.of(), List.of());
        var hostClass = new LirClassDef("HostClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());

        var func = new LirFunctionDef("load_shadowed_value");
        func.setReturnType(GdStringType.STRING);
        func.addParameter(new LirParameterDef("grand", new GdObjectType("GrandChildClass"), null, func));
        func.createAndAddVariable("tmp", GdStringType.STRING);

        var entry = new LirBasicBlock("entry");
        entry.instructions().add(new LoadPropertyInsn("tmp", "value", "grand"));
        entry.instructions().add(new ReturnInsn("tmp"));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        hostClass.addFunction(func);

        var module = new LirModule("test_module", List.of(hostClass, grandChildClass, childClass, parentClass));
        var ctx = newContext(
                new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()),
                List.of(hostClass, grandChildClass, childClass, parentClass)
        );

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);

        var body = codegen.generateFuncBody(hostClass, func);
        assertTrue(body.contains("ChildClass__field_getter_child_value(&($grand->_super));"), body);
        assertFalse(body.contains("ParentClass__field_getter_parent_value("), body);
    }

    @Test
    @DisplayName("GDCC receiver should call ENGINE owner getter with GDCC->Godot conversion")
    void gdccReceiverShouldCallEngineOwnerGetterWithConversion() {
        var nodeClass = new ExtensionGdClass(
                "Node", false, true, "Object", "core",
                List.of(), List.of(), List.of(),
                List.of(new ExtensionGdClass.PropertyInfo("name", "String", true, true, "")),
                List.of()
        );
        var api = new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(nodeClass), List.of(), List.of());

        var userClass = new LirClassDef("MyClass", "Node", false, false, Map.of(), List.of(), List.of(), List.of());
        var hostClass = new LirClassDef("HostClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("load_engine_parent_prop");
        func.setReturnType(GdStringType.STRING);
        func.addParameter(new LirParameterDef("obj", new GdObjectType("MyClass"), null, func));
        func.createAndAddVariable("tmp", GdStringType.STRING);

        var entry = new LirBasicBlock("entry");
        entry.instructions().add(new LoadPropertyInsn("tmp", "name", "obj"));
        entry.instructions().add(new ReturnInsn("tmp"));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        hostClass.addFunction(func);

        var module = new LirModule("test_module", List.of(hostClass, userClass));
        var ctx = newContext(api, List.of(hostClass, userClass));

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);

        var body = codegen.generateFuncBody(hostClass, func);
        assertTrue(body.contains("godot_Node_get_name((godot_Node*)gdcc_object_to_godot_object_ptr($obj, MyClass_object_ptr));"), body);
        assertFalse(body.contains("godot_Node_get_name((godot_Node*)$obj);"), body);
    }

    @Test
    @DisplayName("ENGINE child receiver should call ENGINE parent getter with owner cast")
    void engineChildReceiverShouldCallEngineParentGetterWithOwnerCast() {
        var nodeClass = new ExtensionGdClass(
                "Node", false, true, "Object", "core",
                List.of(), List.of(), List.of(),
                List.of(new ExtensionGdClass.PropertyInfo("name", "String", true, true, "")),
                List.of()
        );
        var controlClass = new ExtensionGdClass(
                "Control", false, true, "Node", "core",
                List.of(), List.of(), List.of(),
                List.of(),
                List.of()
        );
        var api = new ExtensionAPI(
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(nodeClass, controlClass),
                List.of(),
                List.of()
        );

        var hostClass = new LirClassDef("HostClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("load_engine_parent_prop");
        func.setReturnType(GdStringType.STRING);
        func.addParameter(new LirParameterDef("control", new GdObjectType("Control"), null, func));
        func.createAndAddVariable("tmp", GdStringType.STRING);

        var entry = new LirBasicBlock("entry");
        entry.instructions().add(new LoadPropertyInsn("tmp", "name", "control"));
        entry.instructions().add(new ReturnInsn("tmp"));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        hostClass.addFunction(func);

        var module = new LirModule("test_module", List.of(hostClass));
        var ctx = newContext(api, List.of(hostClass));

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);

        var body = codegen.generateFuncBody(hostClass, func);
        assertTrue(body.contains("godot_Node_get_name((godot_Node*)$control);"), body);
        assertFalse(body.contains("godot_Control_get_name("), body);
    }

    @Test
    @DisplayName("Three-level GDCC->GDCC->ENGINE chain should resolve ENGINE owner getter")
    void threeLevelGdccToEngineChainShouldResolveEngineOwnerGetter() {
        var nodeClass = new ExtensionGdClass(
                "Node", false, true, "Object", "core",
                List.of(), List.of(), List.of(),
                List.of(new ExtensionGdClass.PropertyInfo("name", "String", true, true, "")),
                List.of()
        );
        var api = new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(nodeClass), List.of(), List.of());

        var childClass = new LirClassDef("ChildClass", "Node", false, false, Map.of(), List.of(), List.of(), List.of());
        var grandChildClass = new LirClassDef("GrandChildClass", "ChildClass", false, false, Map.of(), List.of(), List.of(), List.of());
        var hostClass = new LirClassDef("HostClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("load_chain_prop");
        func.setReturnType(GdStringType.STRING);
        func.addParameter(new LirParameterDef("grand", new GdObjectType("GrandChildClass"), null, func));
        func.createAndAddVariable("tmp", GdStringType.STRING);

        var entry = new LirBasicBlock("entry");
        entry.instructions().add(new LoadPropertyInsn("tmp", "name", "grand"));
        entry.instructions().add(new ReturnInsn("tmp"));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        hostClass.addFunction(func);

        var module = new LirModule("test_module", List.of(hostClass, childClass, grandChildClass));
        var ctx = newContext(api, List.of(hostClass, childClass, grandChildClass));

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);

        var body = codegen.generateFuncBody(hostClass, func);
        assertTrue(body.contains("godot_Node_get_name((godot_Node*)gdcc_object_to_godot_object_ptr($grand, GrandChildClass_object_ptr));"), body);
        assertFalse(body.contains("godot_Node_get_name((godot_Node*)$grand);"), body);
    }

    @Test
    @DisplayName("Known object receiver should fail-fast when property is absent in hierarchy")
    void knownObjectReceiverShouldFailFastWhenPropertyAbsentInHierarchy() {
        var parentClass = new LirClassDef("ParentClass", "", false, false, Map.of(), List.of(), List.of(), List.of());
        var childClass = new LirClassDef("ChildClass", "ParentClass", false, false, Map.of(), List.of(), List.of(), List.of());
        var hostClass = new LirClassDef("HostClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("load_missing_prop");
        func.setReturnType(GdStringType.STRING);
        func.addParameter(new LirParameterDef("child", new GdObjectType("ChildClass"), null, func));
        func.createAndAddVariable("tmp", GdStringType.STRING);

        var entry = new LirBasicBlock("entry");
        entry.instructions().add(new LoadPropertyInsn("tmp", "missing_prop", "child"));
        entry.instructions().add(new ReturnInsn("tmp"));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        hostClass.addFunction(func);

        var module = new LirModule("test_module", List.of(hostClass, childClass, parentClass));
        var ctx = newContext(
                new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()),
                List.of(hostClass, childClass, parentClass)
        );

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);

        var ex = assertThrows(InvalidInsnException.class, () -> codegen.generateFuncBody(hostClass, func));
        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("class hierarchy"), ex.getMessage());
        assertTrue(ex.getMessage().contains("ChildClass"), ex.getMessage());
    }

    @Test
    @DisplayName("Load property should fail-fast when result type is not assignable from property type")
    void loadPropertyShouldFailWhenResultTypeNotAssignableFromPropertyType() {
        var gdccClass = new LirClassDef("MyClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        gdccClass.addProperty(new LirPropertyDef("value", GdStringType.STRING, false, null, "_field_getter_value", null, Map.of()));

        var hostClass = new LirClassDef("HostClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("load_type_mismatch");
        func.setReturnType(GdFloatType.FLOAT);
        func.addParameter(new LirParameterDef("obj", new GdObjectType("MyClass"), null, func));
        func.createAndAddVariable("tmp", GdFloatType.FLOAT);

        var entry = new LirBasicBlock("entry");
        entry.instructions().add(new LoadPropertyInsn("tmp", "value", "obj"));
        entry.instructions().add(new ReturnInsn("tmp"));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        hostClass.addFunction(func);

        var module = new LirModule("test_module", List.of(hostClass, gdccClass));
        var ctx = newContext(
                new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()),
                List.of(hostClass, gdccClass)
        );

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);

        var ex = assertThrows(InvalidInsnException.class, () -> codegen.generateFuncBody(hostClass, func));
        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("not assignable from property"), ex.getMessage());
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

