package gd.script.gdcc.backend.c.gen;

import gd.script.gdcc.backend.CodegenContext;
import gd.script.gdcc.backend.ProjectInfo;
import gd.script.gdcc.enums.GodotVersion;
import gd.script.gdcc.exception.InvalidInsnException;
import gd.script.gdcc.gdextension.ExtensionAPI;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.gdextension.ExtensionBuiltinClass;
import gd.script.gdcc.gdextension.ExtensionFunctionArgument;
import gd.script.gdcc.gdextension.ExtensionGdClass;
import gd.script.gdcc.lir.LirBasicBlock;
import gd.script.gdcc.lir.LirClassDef;
import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.lir.LirModule;
import gd.script.gdcc.lir.LirParameterDef;
import gd.script.gdcc.lir.LirPropertyDef;
import gd.script.gdcc.lir.insn.LoadPropertyInsn;
import gd.script.gdcc.lir.insn.ReturnInsn;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdArrayType;
import gd.script.gdcc.type.GdBoolType;
import gd.script.gdcc.type.GdColorType;
import gd.script.gdcc.type.GdccForRangeIterType;
import gd.script.gdcc.type.GdFloatType;
import gd.script.gdcc.type.GdFloatVectorType;
import gd.script.gdcc.type.GdNilType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdStringNameType;
import gd.script.gdcc.type.GdStringType;
import gd.script.gdcc.type.GdVariantType;
import gd.script.gdcc.type.GdVoidType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CLoadPropertyInsnGenTest {
    @Test
    @DisplayName("GDCC getter should stage a stable carrier from backing field address when overwriting target inside getter")
    void gdccGetterUsesFieldAccessInsideGetter() {
        var gdccClass = new LirClassDef("MyClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var propertyDef = new LirPropertyDef("value", GdStringType.STRING, false, null, "_field_getter_value", null, Map.of());
        gdccClass.addProperty(propertyDef);

        var func = new LirFunctionDef("_field_getter_value");
        func.setReturnType(GdStringType.STRING);
        func.addParameter(new LirParameterDef("self", new GdObjectType("MyClass"), null, func));
        func.createAndAddVariable("tmp", GdStringType.STRING);

        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new LoadPropertyInsn("tmp", "value", "self"));
        entry.appendInstruction(new ReturnInsn("tmp"));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        gdccClass.addFunction(func);

        var module = new LirModule("test_module", List.of(gdccClass));
        var ctx = newContext(new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()),
                List.of(gdccClass));

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);

        var body = codegen.generateFuncBody(gdccClass, func);
        assertTrue(body.contains("godot_String __gdcc_tmp_string_0 = godot_new_String_with_String(&($self->value));"), body);
        assertTrue(body.contains("godot_String_destroy(&$tmp);"), body);
        assertTrue(body.contains("$tmp = __gdcc_tmp_string_0;"), body);
        assertFalse(body.contains("__gdcc_tmp_string_0 = $self->value;"), body);
        assertFalse(body.contains("godot_String_destroy(&__gdcc_tmp_string_0);"), body);
        assertFalse(body.contains("MyClass__field_getter_value("));
    }

    @Test
    @DisplayName("GDCC Variant getter should stage a stable carrier from backing field address instead of shallow temp materialization")
    void gdccVariantGetterCopiesBackingFieldByAddress() {
        var gdccClass = new LirClassDef("MyClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var propertyDef = new LirPropertyDef("payload", GdVariantType.VARIANT, false, null, "_field_getter_payload", null, Map.of());
        gdccClass.addProperty(propertyDef);

        var func = new LirFunctionDef("_field_getter_payload");
        func.setReturnType(GdVariantType.VARIANT);
        func.addParameter(new LirParameterDef("self", new GdObjectType("MyClass"), null, func));
        func.createAndAddVariable("tmp", GdVariantType.VARIANT);

        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new LoadPropertyInsn("tmp", "payload", "self"));
        entry.appendInstruction(new ReturnInsn("tmp"));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        gdccClass.addFunction(func);

        var module = new LirModule("test_module", List.of(gdccClass));
        var ctx = newContext(new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()),
                List.of(gdccClass));

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);

        var body = codegen.generateFuncBody(gdccClass, func);
        assertTrue(body.contains("godot_Variant __gdcc_tmp_variant_0 = godot_new_Variant_with_Variant(&($self->payload));"), body);
        assertTrue(body.contains("godot_Variant_destroy(&$tmp);"), body);
        assertTrue(body.contains("$tmp = __gdcc_tmp_variant_0;"), body);
        assertFalse(body.contains("__gdcc_tmp_variant_0 = $self->payload;"), body);
        assertFalse(body.contains("godot_Variant_destroy(&__gdcc_tmp_variant_0);"), body);
        assertFalse(body.contains("MyClass__field_getter_payload("), body);
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
        entry.appendInstruction(new LoadPropertyInsn("tmp", "value", "obj"));
        entry.appendInstruction(new ReturnInsn("tmp"));
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
                List.of(), List.of(engineMethod("get_name", 101L, "String", List.of())), List.of(),
                List.of(new ExtensionGdClass.PropertyInfo("name", "String", true, true, "", "get_name", "set_name", null)),
                List.of()
        );
        var api = new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(nodeClass), List.of(), List.of());

        var gdccClass = new LirClassDef("TestClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("use_node");
        func.setReturnType(GdStringType.STRING);
        func.addParameter(new LirParameterDef("node", new GdObjectType("Node"), null, func));
        func.createAndAddVariable("tmp", GdStringType.STRING);

        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new LoadPropertyInsn("tmp", "name", "node"));
        entry.appendInstruction(new ReturnInsn("tmp"));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        gdccClass.addFunction(func);

        var module = new LirModule("test_module", List.of(gdccClass));
        var ctx = newContext(api, List.of(gdccClass));

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);

        var body = codegen.generateFuncBody(gdccClass, func);
        assertTrue(body.contains("gdcc_engine_call_node_get_name_P_RT($node)"), body);
        assertFalse(body.contains("godot_Node_get_name("), body);
    }

    @Test
    @DisplayName("Engine property getter should follow raw accessor name instead of property name")
    void enginePropertyGetterShouldUseRawAccessorName() {
        var windowClass = new ExtensionGdClass(
                "Window", false, true, "Object", "core",
                List.of(), List.of(engineMethod("get_title_override", 201L, "String", List.of())), List.of(),
                List.of(new ExtensionGdClass.PropertyInfo("window_title", "String", true, true, "", "get_title_override", "set_title_override", null)),
                List.of()
        );
        var api = new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(windowClass), List.of(), List.of());

        var gdccClass = new LirClassDef("TestClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("use_window");
        func.setReturnType(GdStringType.STRING);
        func.addParameter(new LirParameterDef("window", new GdObjectType("Window"), null, func));
        func.createAndAddVariable("tmp", GdStringType.STRING);

        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new LoadPropertyInsn("tmp", "window_title", "window"));
        entry.appendInstruction(new ReturnInsn("tmp"));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        gdccClass.addFunction(func);

        var module = new LirModule("test_module", List.of(gdccClass));
        var ctx = newContext(api, List.of(gdccClass));

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);

        var body = codegen.generateFuncBody(gdccClass, func);
        assertTrue(body.contains("gdcc_engine_call_window_get_title_override_P_RT($window)"), body);
        assertFalse(body.contains("get_window_title"), body);
        assertFalse(body.contains("godot_Window_get_window_title("), body);
    }

    @Test
    @DisplayName("Indexed engine property getter should pass fixed index 0")
    void indexedEnginePropertyGetterShouldPassFixedIndexZero() {
        var windowClass = new ExtensionGdClass(
                "Window", false, true, "Object", "core",
                List.of(), List.of(engineMethod(
                "get_flag",
                301L,
                "bool",
                List.of(arg("flag", "enum::Window.Flags"))
        )), List.of(),
                List.of(new ExtensionGdClass.PropertyInfo("unresizable", "bool", true, true, "", "get_flag", "set_flag", 0)),
                List.of()
        );
        var api = new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(windowClass), List.of(), List.of());

        var gdccClass = new LirClassDef("TestClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("use_window_flag");
        func.setReturnType(GdBoolType.BOOL);
        func.addParameter(new LirParameterDef("window", new GdObjectType("Window"), null, func));
        func.createAndAddVariable("tmp", GdBoolType.BOOL);

        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new LoadPropertyInsn("tmp", "unresizable", "window"));
        entry.appendInstruction(new ReturnInsn("tmp"));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        gdccClass.addFunction(func);

        var module = new LirModule("test_module", List.of(gdccClass));
        var ctx = newContext(api, List.of(gdccClass));

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);

        var body = codegen.generateFuncBody(gdccClass, func);
        assertTrue(body.contains("gdcc_engine_call_window_get_flag_PI_RZ($window, 0)"), body);
        assertFalse(body.contains("get_unresizable"), body);
    }

    @Test
    @DisplayName("Readable engine property should fail-fast when raw getter method metadata is missing")
    void enginePropertyGetterShouldFailWhenRawMethodMetadataMissing() {
        var nodeClass = new ExtensionGdClass(
                "Node", false, true, "Object", "core",
                List.of(), List.of(), List.of(),
                List.of(new ExtensionGdClass.PropertyInfo("name", "String", true, true, "", "get_name", "set_name", null)),
                List.of()
        );
        var api = new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(nodeClass), List.of(), List.of());

        var gdccClass = new LirClassDef("TestClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("use_node");
        func.setReturnType(GdStringType.STRING);
        func.addParameter(new LirParameterDef("node", new GdObjectType("Node"), null, func));
        func.createAndAddVariable("tmp", GdStringType.STRING);

        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new LoadPropertyInsn("tmp", "name", "node"));
        entry.appendInstruction(new ReturnInsn("tmp"));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        gdccClass.addFunction(func);

        var module = new LirModule("test_module", List.of(gdccClass));
        var ctx = newContext(api, List.of(gdccClass));

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);

        var ex = assertThrows(InvalidInsnException.class, () -> codegen.generateFuncBody(gdccClass, func));
        assertTrue(ex.getMessage().contains("get_name"), ex.getMessage());
        assertTrue(ex.getMessage().contains("METHOD_MISSING"), ex.getMessage());
    }

    @Test
    @DisplayName("load_property should reject compiler-only result target")
    void loadPropertyShouldRejectCompilerOnlyResultTarget() {
        var gdccClass = new LirClassDef("MyClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        gdccClass.addProperty(new LirPropertyDef("value", GdStringType.STRING, false, null, null, null, Map.of()));

        var func = new LirFunctionDef("use_value");
        func.setReturnType(GdVoidType.VOID);
        func.addParameter(new LirParameterDef("obj", new GdObjectType("MyClass"), null, func));
        func.createAndAddVariable("tmp", GdccForRangeIterType.FOR_RANGE_ITER);

        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new LoadPropertyInsn("tmp", "value", "obj"));
        entry.appendInstruction(new ReturnInsn(null));
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
        assertTrue(ex.getMessage().contains("compiler-only type leaked into property load result target variable 'tmp'"), ex.getMessage());
    }

    @Test
    @DisplayName("Unreadable engine property should throw")
    void unreadableEnginePropertyShouldThrow() {
        var nodeClass = new ExtensionGdClass(
                "Node", false, true, "Object", "core",
                List.of(), List.of(), List.of(),
                List.of(new ExtensionGdClass.PropertyInfo("name", "String", false, true, "", "get_name", "set_name", null)),
                List.of()
        );
        var api = new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(nodeClass), List.of(), List.of());

        var gdccClass = new LirClassDef("TestClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("use_node");
        func.setReturnType(GdStringType.STRING);
        func.addParameter(new LirParameterDef("node", new GdObjectType("Node"), null, func));
        func.createAndAddVariable("tmp", GdStringType.STRING);

        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new LoadPropertyInsn("tmp", "name", "node"));
        entry.appendInstruction(new ReturnInsn("tmp"));
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
        entry.appendInstruction(new LoadPropertyInsn("tmp", "name", "obj"));
        entry.appendInstruction(new ReturnInsn("tmp"));
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
        assertFalse(body.contains("__gdcc_tmp_idx_valid_"), body);
        assertFalse(body.contains("GDCC_PRINT_RUNTIME_ERROR"), body);
        assertFalse(body.contains("godot_variant_get_named"), body);
        assertFalse(body.contains("godot_variant_set_named"), body);
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
        entry.appendInstruction(new LoadPropertyInsn("tmp", "child", "obj"));
        entry.appendInstruction(new ReturnInsn(null));
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
        entry.appendInstruction(new LoadPropertyInsn("tmp", "target", "obj"));
        entry.appendInstruction(new ReturnInsn(null));
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
        entry.appendInstruction(new LoadPropertyInsn("tmp", "items", "obj"));
        entry.appendInstruction(new ReturnInsn(null));
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
                List.of(new ExtensionBuiltinClass.MemberInfo("x", "float")),
                List.of()
        );
        var api = new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), List.of(vector2Class), List.of(), List.of(), List.of());

        var gdccClass = new LirClassDef("TestClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("use_vec");
        func.setReturnType(GdFloatType.FLOAT);
        func.createAndAddVariable("vec", GdFloatVectorType.VECTOR2);
        func.createAndAddVariable("tmp", GdFloatType.FLOAT);

        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new LoadPropertyInsn("tmp", "x", "vec"));
        entry.appendInstruction(new ReturnInsn("tmp"));
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
        entry.appendInstruction(new LoadPropertyInsn("tmp", "value", "obj"));
        entry.appendInstruction(new ReturnInsn("tmp"));
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
    @DisplayName("LOAD_PROPERTY should reject missing object variable before resolver fallback")
    void loadPropertyShouldRejectMissingObjectVariable() {
        var gdccClass = new LirClassDef("TestClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("load_missing_object");
        func.setReturnType(GdStringType.STRING);
        func.createAndAddVariable("tmp", GdStringType.STRING);

        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new LoadPropertyInsn("tmp", "name", "missing_obj"));
        entry.appendInstruction(new ReturnInsn("tmp"));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        gdccClass.addFunction(func);

        var module = new LirModule("test_module", List.of(gdccClass));
        var ctx = newContext(new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()),
                List.of(gdccClass));

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);

        var ex = assertThrows(InvalidInsnException.class, () -> codegen.generateFuncBody(gdccClass, func));
        assertTrue(ex.getMessage().contains("Object variable ID missing_obj does not exist"), ex.getMessage());
    }

    @Test
    @DisplayName("LOAD_PROPERTY should reject missing result variable before property lookup")
    void loadPropertyShouldRejectMissingResultVariable() {
        var gdccClass = new LirClassDef("TestClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("load_missing_result");
        func.setReturnType(GdStringType.STRING);
        func.addParameter(new LirParameterDef("obj", new GdObjectType("UnknownType"), null, func));

        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new LoadPropertyInsn("missing_tmp", "name", "obj"));
        entry.appendInstruction(new ReturnInsn(null));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        gdccClass.addFunction(func);

        var module = new LirModule("test_module", List.of(gdccClass));
        var ctx = newContext(new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()),
                List.of(gdccClass));

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);

        var ex = assertThrows(InvalidInsnException.class, () -> codegen.generateFuncBody(gdccClass, func));
        assertTrue(ex.getMessage().contains("Result variable ID missing_tmp does not exist"), ex.getMessage());
    }

    @Test
    @DisplayName("LOAD_PROPERTY should reject Nil receiver instead of runtime object fallback")
    void loadPropertyShouldRejectNilReceiver() {
        var gdccClass = new LirClassDef("TestClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("load_nil_receiver");
        func.setReturnType(GdStringType.STRING);
        func.createAndAddVariable("obj", GdNilType.NIL);
        func.createAndAddVariable("tmp", GdStringType.STRING);

        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new LoadPropertyInsn("tmp", "name", "obj"));
        entry.appendInstruction(new ReturnInsn("tmp"));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        gdccClass.addFunction(func);

        var module = new LirModule("test_module", List.of(gdccClass));
        var ctx = newContext(new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()),
                List.of(gdccClass));

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);

        var ex = assertThrows(InvalidInsnException.class, () -> codegen.generateFuncBody(gdccClass, func));
        assertTrue(ex.getMessage().contains("not a valid property target type"), ex.getMessage());
        assertTrue(ex.getMessage().contains("Nil"), ex.getMessage());
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
        entry.appendInstruction(new LoadPropertyInsn("tmp", "value", "obj"));
        entry.appendInstruction(new ReturnInsn("tmp"));
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
                List.of(new ExtensionBuiltinClass.MemberInfo("x", "float")),
                List.of()
        );
        var api = new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), List.of(vector2Class), List.of(), List.of(), List.of());

        var gdccClass = new LirClassDef("TestClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("use_vec_ref");
        func.setReturnType(GdFloatType.FLOAT);
        func.createAndAddRefVariable("vec", GdFloatVectorType.VECTOR2);
        func.createAndAddVariable("tmp", GdFloatType.FLOAT);

        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new LoadPropertyInsn("tmp", "x", "vec"));
        entry.appendInstruction(new ReturnInsn("tmp"));
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
    @DisplayName("Default API builtin member-backed properties should use builtin getter names")
    void defaultApiBuiltinMemberBackedPropertiesShouldUseBuiltinGetterNames() throws IOException {
        var api = ExtensionApiLoader.loadDefault();

        var gdccClass = new LirClassDef("TestClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());

        var vectorFunc = new LirFunctionDef("axis_x");
        vectorFunc.setReturnType(GdFloatType.FLOAT);
        vectorFunc.addParameter(new LirParameterDef("vector", GdFloatVectorType.VECTOR3, null, vectorFunc));
        vectorFunc.createAndAddVariable("axis", GdFloatType.FLOAT);
        var vectorEntry = new LirBasicBlock("entry");
        vectorEntry.appendInstruction(new LoadPropertyInsn("axis", "x", "vector"));
        vectorEntry.appendInstruction(new ReturnInsn("axis"));
        vectorFunc.addBasicBlock(vectorEntry);
        vectorFunc.setEntryBlockId("entry");
        gdccClass.addFunction(vectorFunc);

        var colorFunc = new LirFunctionDef("red");
        colorFunc.setReturnType(GdFloatType.FLOAT);
        colorFunc.addParameter(new LirParameterDef("color", GdColorType.COLOR, null, colorFunc));
        colorFunc.createAndAddVariable("channel", GdFloatType.FLOAT);
        var colorEntry = new LirBasicBlock("entry");
        colorEntry.appendInstruction(new LoadPropertyInsn("channel", "r", "color"));
        colorEntry.appendInstruction(new ReturnInsn("channel"));
        colorFunc.addBasicBlock(colorEntry);
        colorFunc.setEntryBlockId("entry");
        gdccClass.addFunction(colorFunc);

        var module = new LirModule("test_module", List.of(gdccClass));
        var ctx = newContext(api, List.of(gdccClass));

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);

        var vectorBody = codegen.generateFuncBody(gdccClass, vectorFunc);
        // Unit-level body generation sees the builtin parameter as an already-usable ref slot.
        // The end-to-end ABI contract is covered separately by the integration test, where the
        // generated method wrapper passes a builtin-value pointer into the user method.
        assertTrue(vectorBody.contains("godot_Vector3_get_x($vector)"), vectorBody);
        assertFalse(vectorBody.contains("godot_Object_get"), vectorBody);

        var colorBody = codegen.generateFuncBody(gdccClass, colorFunc);
        assertTrue(colorBody.contains("godot_Color_get_r($color)"), colorBody);
        assertFalse(colorBody.contains("godot_Object_get"), colorBody);
    }

    @Test
    @DisplayName("Default API missing builtin member should still fail-fast on load")
    void defaultApiMissingBuiltinMemberShouldStillFailFastOnLoad() throws IOException {
        var api = ExtensionApiLoader.loadDefault();

        var gdccClass = new LirClassDef("TestClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("missing_axis");
        func.setReturnType(GdFloatType.FLOAT);
        func.addParameter(new LirParameterDef("vector", GdFloatVectorType.VECTOR3, null, func));
        func.createAndAddVariable("axis", GdFloatType.FLOAT);

        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new LoadPropertyInsn("axis", "missing_axis", "vector"));
        entry.appendInstruction(new ReturnInsn("axis"));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        gdccClass.addFunction(func);

        var module = new LirModule("test_module", List.of(gdccClass));
        var ctx = newContext(api, List.of(gdccClass));

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);

        var ex = assertThrows(InvalidInsnException.class, () -> codegen.generateFuncBody(gdccClass, func));
        assertTrue(ex.getMessage().contains("missing_axis"), ex.getMessage());
        assertTrue(ex.getMessage().contains("Vector3"), ex.getMessage());
    }

    @Test
    @DisplayName("Missing builtin property should throw")
    void missingBuiltinPropertyShouldThrow() {
        var vector2Class = new ExtensionBuiltinClass(
                "Vector2", false,
                List.of(), List.of(), List.of(),
                List.of(),
                List.of(new ExtensionBuiltinClass.MemberInfo("y", "float")),
                List.of()
        );
        var api = new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), List.of(vector2Class), List.of(), List.of(), List.of());

        var gdccClass = new LirClassDef("TestClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("use_vec");
        func.setReturnType(GdFloatType.FLOAT);
        func.createAndAddVariable("vec", GdFloatVectorType.VECTOR2);
        func.createAndAddVariable("tmp", GdFloatType.FLOAT);

        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new LoadPropertyInsn("tmp", "x", "vec"));
        entry.appendInstruction(new ReturnInsn("tmp"));
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
        entry.appendInstruction(new LoadPropertyInsn("tmp", "value", "obj"));
        entry.appendInstruction(new ReturnInsn("tmp"));
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
        entry.appendInstruction(new LoadPropertyInsn("tmp", "value", "child"));
        entry.appendInstruction(new ReturnInsn("tmp"));
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
        entry.appendInstruction(new LoadPropertyInsn("tmp", "value", "grand"));
        entry.appendInstruction(new ReturnInsn("tmp"));
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
        entry.appendInstruction(new LoadPropertyInsn("tmp", "value", "grand"));
        entry.appendInstruction(new ReturnInsn("tmp"));
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
                List.of(), List.of(engineMethod("get_name", 801L, "String", List.of())), List.of(),
                List.of(new ExtensionGdClass.PropertyInfo("name", "String", true, true, "", "get_name", "set_name", null)),
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
        entry.appendInstruction(new LoadPropertyInsn("tmp", "name", "obj"));
        entry.appendInstruction(new ReturnInsn("tmp"));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        hostClass.addFunction(func);

        var module = new LirModule("test_module", List.of(hostClass, userClass));
        var ctx = newContext(api, List.of(hostClass, userClass));

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);

        var body = codegen.generateFuncBody(hostClass, func);
        assertTrue(body.contains("gdcc_engine_call_node_get_name_P_RT((godot_Node*)gdcc_object_to_godot_object_ptr($obj, MyClass_object_ptr));"), body);
        assertFalse(body.contains("gdcc_engine_call_node_get_name_P_RT((godot_Node*)$obj);"), body);
    }

    @Test
    @DisplayName("ENGINE child receiver should call ENGINE parent getter with owner cast")
    void engineChildReceiverShouldCallEngineParentGetterWithOwnerCast() {
        var nodeClass = new ExtensionGdClass(
                "Node", false, true, "Object", "core",
                List.of(), List.of(engineMethod("get_name", 901L, "String", List.of())), List.of(),
                List.of(new ExtensionGdClass.PropertyInfo("name", "String", true, true, "", "get_name", "set_name", null)),
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
        entry.appendInstruction(new LoadPropertyInsn("tmp", "name", "control"));
        entry.appendInstruction(new ReturnInsn("tmp"));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        hostClass.addFunction(func);

        var module = new LirModule("test_module", List.of(hostClass));
        var ctx = newContext(api, List.of(hostClass));

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);

        var body = codegen.generateFuncBody(hostClass, func);
        assertTrue(body.contains("gdcc_engine_call_node_get_name_P_RT((godot_Node*)$control);"), body);
        assertFalse(body.contains("gdcc_engine_call_control_get_name_"), body);
    }

    @Test
    @DisplayName("Three-level GDCC->GDCC->ENGINE chain should resolve ENGINE owner getter")
    void threeLevelGdccToEngineChainShouldResolveEngineOwnerGetter() {
        var nodeClass = new ExtensionGdClass(
                "Node", false, true, "Object", "core",
                List.of(), List.of(engineMethod("get_name", 1001L, "String", List.of())), List.of(),
                List.of(new ExtensionGdClass.PropertyInfo("name", "String", true, true, "", "get_name", "set_name", null)),
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
        entry.appendInstruction(new LoadPropertyInsn("tmp", "name", "grand"));
        entry.appendInstruction(new ReturnInsn("tmp"));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        hostClass.addFunction(func);

        var module = new LirModule("test_module", List.of(hostClass, childClass, grandChildClass));
        var ctx = newContext(api, List.of(hostClass, childClass, grandChildClass));

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);

        var body = codegen.generateFuncBody(hostClass, func);
        assertTrue(body.contains("gdcc_engine_call_node_get_name_P_RT((godot_Node*)gdcc_object_to_godot_object_ptr($grand, GrandChildClass_object_ptr));"), body);
        assertFalse(body.contains("gdcc_engine_call_node_get_name_P_RT((godot_Node*)$grand);"), body);
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
        entry.appendInstruction(new LoadPropertyInsn("tmp", "missing_prop", "child"));
        entry.appendInstruction(new ReturnInsn("tmp"));
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
        entry.appendInstruction(new LoadPropertyInsn("tmp", "value", "obj"));
        entry.appendInstruction(new ReturnInsn("tmp"));
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

    private static ExtensionGdClass.ClassMethod engineMethod(String name,
                                                             long hash,
                                                             String returnType,
                                                             List<ExtensionFunctionArgument> arguments) {
        return new ExtensionGdClass.ClassMethod(
                name,
                false,
                false,
                false,
                false,
                hash,
                List.of(),
                new ExtensionGdClass.ClassMethod.ClassMethodReturn(returnType),
                arguments
        );
    }

    private static ExtensionFunctionArgument arg(String name, String type) {
        return new ExtensionFunctionArgument(name, type, null, null);
    }
}
