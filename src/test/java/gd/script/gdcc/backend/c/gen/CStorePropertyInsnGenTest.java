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
import gd.script.gdcc.lir.insn.StorePropertyInsn;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdBoolType;
import gd.script.gdcc.type.GdColorType;
import gd.script.gdcc.type.GdDictionaryType;
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

public class CStorePropertyInsnGenTest {
    @Test
    @DisplayName("GDCC setter should stage a stable carrier for ref-parameter aliases when storing field inside the setter itself")
    void gdccSetterStoresFieldDirectlyInsideSetter() {
        var gdccClass = new LirClassDef("MyClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        gdccClass.addProperty(new LirPropertyDef("value", GdStringType.STRING, false, null, null, "_field_setter_value", Map.of()));

        var func = new LirFunctionDef("_field_setter_value");
        func.setReturnType(GdVoidType.VOID);
        func.addParameter(new LirParameterDef("self", new GdObjectType("MyClass"), null, func));
        func.addParameter(new LirParameterDef("value", GdStringType.STRING, null, func));
        addEntryStoreAndReturn(func, new StorePropertyInsn("value", "self", "value"));
        gdccClass.addFunction(func);

        var module = new LirModule("test_module", List.of(gdccClass));
        var ctx = newContext(emptyApi(), List.of(gdccClass));

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);

        var body = codegen.generateFuncBody(gdccClass, func);
        assertTrue(body.contains("godot_String __gdcc_tmp_string_0 = godot_new_String_with_String($value);"), body);
        assertTrue(body.contains("godot_String_destroy(&$self->value);"), body);
        assertTrue(body.contains("$self->value = __gdcc_tmp_string_0;"), body);
        assertFalse(body.contains("godot_String_destroy(&__gdcc_tmp_string_0);"), body);
        assertFalse(body.contains("MyClass__field_setter_value("));
    }

    @Test
    @DisplayName("GDCC Variant setter should stage a stable carrier for ref-parameter aliases without temp lifetime leakage")
    void gdccVariantSetterCopiesDirectlyIntoBackingField() {
        var gdccClass = new LirClassDef("MyClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        gdccClass.addProperty(new LirPropertyDef("payload", GdVariantType.VARIANT, false, null, null, "_field_setter_payload", Map.of()));

        var func = new LirFunctionDef("_field_setter_payload");
        func.setReturnType(GdVoidType.VOID);
        func.addParameter(new LirParameterDef("self", new GdObjectType("MyClass"), null, func));
        func.addParameter(new LirParameterDef("value", GdVariantType.VARIANT, null, func));
        addEntryStoreAndReturn(func, new StorePropertyInsn("payload", "self", "value"));
        gdccClass.addFunction(func);

        var module = new LirModule("test_module", List.of(gdccClass));
        var ctx = newContext(emptyApi(), List.of(gdccClass));

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);

        var body = codegen.generateFuncBody(gdccClass, func);
        assertTrue(body.contains("godot_Variant __gdcc_tmp_variant_0 = godot_new_Variant_with_Variant($value);"), body);
        assertTrue(body.contains("godot_Variant_destroy(&$self->payload);"), body);
        assertTrue(body.contains("$self->payload = __gdcc_tmp_variant_0;"), body);
        assertFalse(body.contains("godot_Variant_destroy(&__gdcc_tmp_variant_0);"), body);
        assertFalse(body.contains("MyClass__field_setter_payload("), body);
    }

    @Test
    @DisplayName("GDCC object setter should rely on store_property lifecycle path without extra own/release instructions")
    void gdccObjectSetterUsesUnifiedLifecyclePath() {
        var nodeClass = new ExtensionGdClass(
                "Node", false, true, "Object", "core",
                List.of(), List.of(), List.of(), List.of(), List.of()
        );
        var api = new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(nodeClass), List.of(), List.of());

        var gdccClass = new LirClassDef("MyClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        gdccClass.addProperty(new LirPropertyDef("target", new GdObjectType("Object"), false, null, null, "_field_setter_target", Map.of()));

        var func = new LirFunctionDef("_field_setter_target");
        func.setReturnType(GdVoidType.VOID);
        func.addParameter(new LirParameterDef("self", new GdObjectType("MyClass"), null, func));
        func.addParameter(new LirParameterDef("value", new GdObjectType("Node"), null, func));
        addEntryStoreAndReturn(func, new StorePropertyInsn("target", "self", "value"));
        gdccClass.addFunction(func);

        var module = new LirModule("test_module", List.of(gdccClass));
        var ctx = newContext(api, List.of(gdccClass));

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);

        var body = codegen.generateFuncBody(gdccClass, func);
        assertTrue(body.contains("__gdcc_tmp_old_obj_"), "Should capture old target object in temp.");
        assertTrue(body.contains(" = $self->target;"), "Captured old temp should be initialized from target slot.");
        assertTrue(body.contains("try_release_object(__gdcc_tmp_old_obj_"), "Should release captured old target object.");
        assertTrue(body.contains("$self->target = $value;"));
        assertTrue(body.contains("try_own_object($self->target);"));
        assertFalse(body.contains("try_own_object($value);"));
    }

    @Test
    @DisplayName("GDCC setter self.obj = self.obj should keep RefCounted lifecycle ordering on backing field writes")
    void gdccSetterSelfPropertyReassignForRefCountedKeepsFieldLifecycleOrdering() {
        var refCountedClass = new ExtensionGdClass(
                "RefCounted", true, true, "Object", "core",
                List.of(), List.of(), List.of(), List.of(), List.of()
        );
        var api = new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(refCountedClass), List.of(), List.of());

        var gdccClass = new LirClassDef("MyClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        gdccClass.addProperty(new LirPropertyDef(
                "obj",
                new GdObjectType("RefCounted"),
                false,
                null,
                "_field_getter_obj",
                "_field_setter_obj",
                Map.of()
        ));

        var setter = new LirFunctionDef("_field_setter_obj");
        setter.setReturnType(GdVoidType.VOID);
        setter.addParameter(new LirParameterDef("self", new GdObjectType("MyClass"), null, setter));
        setter.addParameter(new LirParameterDef("value", new GdObjectType("RefCounted"), null, setter));
        setter.createAndAddVariable("rhs", new GdObjectType("RefCounted"));

        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new LoadPropertyInsn("rhs", "obj", "self"));
        entry.appendInstruction(new StorePropertyInsn("obj", "self", "rhs"));
        entry.appendInstruction(new ReturnInsn(null));
        setter.addBasicBlock(entry);
        setter.setEntryBlockId("entry");
        gdccClass.addFunction(setter);

        var module = new LirModule("test_module", List.of(gdccClass));
        var ctx = newContext(api, List.of(gdccClass));

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);

        var body = codegen.generateFuncBody(gdccClass, setter);
        assertTrue(body.contains("MyClass__field_getter_obj($self);"));
        assertFalse(body.contains("MyClass__field_setter_obj($self, $rhs);"));
        assertTrue(body.contains("__gdcc_tmp_old_obj_"), "Setter-self field write should capture old value temp.");
        assertTrue(body.contains(" = $self->obj;"), "Captured temp should copy field old value.");

        var assignIndex = body.indexOf("$self->obj = $rhs;");
        var ownIndex = body.indexOf("own_object($self->obj);");
        assertTrue(assignIndex >= 0, "Setter-self field write should assign RHS value.");
        assertTrue(ownIndex >= 0, "Setter-self field write should own BORROWED RHS value.");
        assertTrue(body.substring(0, assignIndex).contains(" = $self->obj;"),
                "Setter-self field write should capture old value before assignment.");
        assertTrue(assignIndex < ownIndex, "Assignment should happen before own.");
        var releaseOldIndex = body.indexOf("release_object(__gdcc_tmp_old_obj_", ownIndex);
        assertTrue(releaseOldIndex >= 0, "Setter-self field write should release captured old value after own.");
        assertTrue(ownIndex < releaseOldIndex, "Release of captured old value should happen last.");
    }

    @Test
    @DisplayName("GDCC setter should be called when storing outside the setter")
    void gdccSetterCalledOutsideSetter() {
        var gdccClass = new LirClassDef("MyClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        gdccClass.addProperty(new LirPropertyDef("value", GdStringType.STRING, false, null, null, "_field_setter_value", Map.of()));

        var func = new LirFunctionDef("set_value");
        func.setReturnType(GdVoidType.VOID);
        func.addParameter(new LirParameterDef("obj", new GdObjectType("MyClass"), null, func));
        func.addParameter(new LirParameterDef("value", GdStringType.STRING, null, func));
        addEntryStoreAndReturn(func, new StorePropertyInsn("value", "obj", "value"));
        gdccClass.addFunction(func);

        var module = new LirModule("test_module", List.of(gdccClass));
        var ctx = newContext(emptyApi(), List.of(gdccClass));

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);

        var body = codegen.generateFuncBody(gdccClass, func);
        assertTrue(body.contains("MyClass__field_setter_value($obj, $value);"));
    }

    @Test
    @DisplayName("Engine property should use engine setter")
    void enginePropertyUsesEngineSetter() {
        var nodeClass = new ExtensionGdClass(
                "Node", false, true, "Object", "core",
                List.of(), List.of(engineMethod("set_name", 102L, "void", List.of(arg("value", "String")))), List.of(),
                List.of(new ExtensionGdClass.PropertyInfo("name", "String", true, true, "", "get_name", "set_name", null)),
                List.of()
        );
        var api = new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(nodeClass), List.of(), List.of());

        var gdccClass = new LirClassDef("TestClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("set_node_name");
        func.setReturnType(GdVoidType.VOID);
        func.addParameter(new LirParameterDef("node", new GdObjectType("Node"), null, func));
        func.addParameter(new LirParameterDef("value", GdStringType.STRING, null, func));
        addEntryStoreAndReturn(func, new StorePropertyInsn("name", "node", "value"));
        gdccClass.addFunction(func);

        var module = new LirModule("test_module", List.of(gdccClass));
        var ctx = newContext(api, List.of(gdccClass));

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);

        var body = codegen.generateFuncBody(gdccClass, func);
        assertTrue(body.contains("gdcc_engine_call_node_set_name_PT_RV($node, $value);"), body);
        assertFalse(body.contains("godot_Node_set_name("), body);
    }

    @Test
    @DisplayName("Engine property setter should follow raw accessor name instead of property name")
    void enginePropertySetterShouldUseRawAccessorName() {
        var windowClass = new ExtensionGdClass(
                "Window", false, true, "Object", "core",
                List.of(), List.of(engineMethod("set_title_override", 202L, "void", List.of(arg("title", "String")))), List.of(),
                List.of(new ExtensionGdClass.PropertyInfo("window_title", "String", true, true, "", "get_title_override", "set_title_override", null)),
                List.of()
        );
        var api = new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(windowClass), List.of(), List.of());

        var gdccClass = new LirClassDef("TestClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("set_window");
        func.setReturnType(GdVoidType.VOID);
        func.addParameter(new LirParameterDef("window", new GdObjectType("Window"), null, func));
        func.addParameter(new LirParameterDef("value", GdStringType.STRING, null, func));
        addEntryStoreAndReturn(func, new StorePropertyInsn("window_title", "window", "value"));
        gdccClass.addFunction(func);

        var module = new LirModule("test_module", List.of(gdccClass));
        var ctx = newContext(api, List.of(gdccClass));

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);

        var body = codegen.generateFuncBody(gdccClass, func);
        assertTrue(body.contains("gdcc_engine_call_window_set_title_override_PT_RV($window, $value);"), body);
        assertFalse(body.contains("set_window_title"), body);
        assertFalse(body.contains("godot_Window_set_window_title("), body);
    }

    @Test
    @DisplayName("Indexed engine property setter should pass fixed index 0 before value")
    void indexedEnginePropertySetterShouldPassFixedIndexZero() {
        var windowClass = new ExtensionGdClass(
                "Window", false, true, "Object", "core",
                List.of(), List.of(engineMethod(
                "set_flag",
                302L,
                "void",
                List.of(arg("flag", "enum::Window.Flags"), arg("enabled", "bool"))
        )), List.of(),
                List.of(new ExtensionGdClass.PropertyInfo("unresizable", "bool", true, true, "", "get_flag", "set_flag", 0)),
                List.of()
        );
        var api = new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(windowClass), List.of(), List.of());

        var gdccClass = new LirClassDef("TestClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("set_window_flag");
        func.setReturnType(GdVoidType.VOID);
        func.addParameter(new LirParameterDef("window", new GdObjectType("Window"), null, func));
        func.addParameter(new LirParameterDef("value", GdBoolType.BOOL, null, func));
        addEntryStoreAndReturn(func, new StorePropertyInsn("unresizable", "window", "value"));
        gdccClass.addFunction(func);

        var module = new LirModule("test_module", List.of(gdccClass));
        var ctx = newContext(api, List.of(gdccClass));

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);

        var body = codegen.generateFuncBody(gdccClass, func);
        assertTrue(body.contains("gdcc_engine_call_window_set_flag_PIZ_RV($window, 0, $value);"), body);
        assertFalse(body.contains("set_unresizable"), body);
    }

    @Test
    @DisplayName("Writable engine property should fail-fast when raw setter method metadata is missing")
    void enginePropertySetterShouldFailWhenRawMethodMetadataMissing() {
        var nodeClass = new ExtensionGdClass(
                "Node", false, true, "Object", "core",
                List.of(), List.of(), List.of(),
                List.of(new ExtensionGdClass.PropertyInfo("name", "String", true, true, "", "get_name", "set_name", null)),
                List.of()
        );
        var api = new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(nodeClass), List.of(), List.of());

        var gdccClass = new LirClassDef("TestClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("set_node_name");
        func.setReturnType(GdVoidType.VOID);
        func.addParameter(new LirParameterDef("node", new GdObjectType("Node"), null, func));
        func.addParameter(new LirParameterDef("value", GdStringType.STRING, null, func));
        addEntryStoreAndReturn(func, new StorePropertyInsn("name", "node", "value"));
        gdccClass.addFunction(func);

        var module = new LirModule("test_module", List.of(gdccClass));
        var ctx = newContext(api, List.of(gdccClass));

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);

        var ex = assertThrows(InvalidInsnException.class, () -> codegen.generateFuncBody(gdccClass, func));
        assertTrue(ex.getMessage().contains("set_name"), ex.getMessage());
        assertTrue(ex.getMessage().contains("METHOD_MISSING"), ex.getMessage());
    }

    @Test
    @DisplayName("Unknown object type should fallback to godot_Object_set")
    void unknownObjectTypeShouldFallbackToGodotObjectSet() {
        var gdccClass = new LirClassDef("TestClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("set_unknown_prop");
        func.setReturnType(GdVoidType.VOID);
        func.addParameter(new LirParameterDef("obj", new GdObjectType("UnknownType"), null, func));
        func.addParameter(new LirParameterDef("value", GdStringType.STRING, null, func));
        addEntryStoreAndReturn(func, new StorePropertyInsn("name", "obj", "value"));
        gdccClass.addFunction(func);

        var module = new LirModule("test_module", List.of(gdccClass));
        var ctx = newContext(emptyApi(), List.of(gdccClass));

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);

        var body = codegen.generateFuncBody(gdccClass, func);
        assertTrue(body.contains("__gdcc_tmp_variant_0 = godot_new_Variant_with_String("));
        assertTrue(body.contains("godot_Object_set($obj, GD_STATIC_SN(u8\"name\"), &__gdcc_tmp_variant_0);"));
        assertFalse(body.contains("__gdcc_tmp_idx_valid_"), body);
        assertFalse(body.contains("GDCC_PRINT_RUNTIME_ERROR"), body);
        assertFalse(body.contains("godot_variant_get_named"), body);
        assertFalse(body.contains("godot_variant_set_named"), body);
        assertFalse(body.contains("godot_UnknownType_set_name("));
    }

    @Test
    @DisplayName("STORE_PROPERTY should reject missing object variable before runtime fallback")
    void storePropertyShouldRejectMissingObjectVariable() {
        var gdccClass = new LirClassDef("TestClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("store_missing_object");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("value", GdStringType.STRING);
        addEntryStoreAndReturn(func, new StorePropertyInsn("name", "missing_obj", "value"));
        gdccClass.addFunction(func);

        var module = new LirModule("test_module", List.of(gdccClass));
        var ctx = newContext(emptyApi(), List.of(gdccClass));

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);

        var ex = assertThrows(InvalidInsnException.class, () -> codegen.generateFuncBody(gdccClass, func));
        assertTrue(ex.getMessage().contains("Object variable ID missing_obj does not exist"), ex.getMessage());
    }

    @Test
    @DisplayName("STORE_PROPERTY should reject missing value variable before property lookup")
    void storePropertyShouldRejectMissingValueVariable() {
        var gdccClass = new LirClassDef("TestClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("store_missing_value");
        func.setReturnType(GdVoidType.VOID);
        func.addParameter(new LirParameterDef("obj", new GdObjectType("UnknownType"), null, func));
        addEntryStoreAndReturn(func, new StorePropertyInsn("name", "obj", "missing_value"));
        gdccClass.addFunction(func);

        var module = new LirModule("test_module", List.of(gdccClass));
        var ctx = newContext(emptyApi(), List.of(gdccClass));

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);

        var ex = assertThrows(InvalidInsnException.class, () -> codegen.generateFuncBody(gdccClass, func));
        assertTrue(ex.getMessage().contains("Value variable ID missing_value does not exist"), ex.getMessage());
    }

    @Test
    @DisplayName("STORE_PROPERTY should reject Nil receiver instead of runtime object fallback")
    void storePropertyShouldRejectNilReceiver() {
        var gdccClass = new LirClassDef("TestClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("store_nil_receiver");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("obj", GdNilType.NIL);
        func.createAndAddVariable("value", GdStringType.STRING);
        addEntryStoreAndReturn(func, new StorePropertyInsn("name", "obj", "value"));
        gdccClass.addFunction(func);

        var module = new LirModule("test_module", List.of(gdccClass));
        var ctx = newContext(emptyApi(), List.of(gdccClass));

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);

        var ex = assertThrows(InvalidInsnException.class, () -> codegen.generateFuncBody(gdccClass, func));
        assertTrue(ex.getMessage().contains("not a valid property target type"), ex.getMessage());
        assertTrue(ex.getMessage().contains("Nil"), ex.getMessage());
    }

    @Test
    @DisplayName("Explicitly non-writable engine property should throw even when raw setter exists")
    void nonWritableEnginePropertyWithRawSetterShouldThrow() {
        var nodeClass = new ExtensionGdClass(
                "Node", false, true, "Object", "core",
                List.of(), List.of(), List.of(),
                List.of(new ExtensionGdClass.PropertyInfo("name", "String", true, false, "", "get_name", "set_name", null)),
                List.of()
        );
        var api = new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(nodeClass), List.of(), List.of());

        var gdccClass = new LirClassDef("TestClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("set_node_name");
        func.setReturnType(GdVoidType.VOID);
        func.addParameter(new LirParameterDef("node", new GdObjectType("Node"), null, func));
        func.addParameter(new LirParameterDef("value", GdStringType.STRING, null, func));
        addEntryStoreAndReturn(func, new StorePropertyInsn("name", "node", "value"));
        gdccClass.addFunction(func);

        var module = new LirModule("test_module", List.of(gdccClass));
        var ctx = newContext(api, List.of(gdccClass));

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);

        var ex = assertThrows(InvalidInsnException.class, () -> codegen.generateFuncBody(gdccClass, func));
        assertTrue(ex.getMessage().contains("is not writable"), ex.getMessage());
    }

    @Test
    @DisplayName("Unknown object type should pack typed Dictionary using normalized symbol name")
    void unknownObjectTypeShouldPackTypedDictionaryWithNormalizedSymbol() {
        var gdccClass = new LirClassDef("TestClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("set_unknown_typed_dict");
        func.setReturnType(GdVoidType.VOID);
        func.addParameter(new LirParameterDef("obj", new GdObjectType("UnknownType"), null, func));
        func.addParameter(
                new LirParameterDef("value", new GdDictionaryType(GdStringNameType.STRING_NAME, GdVariantType.VARIANT), null, func)
        );
        addEntryStoreAndReturn(func, new StorePropertyInsn("meta", "obj", "value"));
        gdccClass.addFunction(func);

        var module = new LirModule("test_module", List.of(gdccClass));
        var ctx = newContext(emptyApi(), List.of(gdccClass));

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);

        var body = codegen.generateFuncBody(gdccClass, func);
        assertTrue(body.contains("__gdcc_tmp_variant_0 = godot_new_Variant_with_Dictionary("));
        assertFalse(body.contains("godot_new_Variant_with_Dictionary["));
    }

    @Test
    @DisplayName("Builtin property should pass non-ref receiver with address-of")
    void builtinPropertyUsesAddressOfForReceiverVariable() {
        var vector2Class = vector2Builtin();
        var api = new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), List.of(vector2Class), List.of(), List.of(), List.of());

        var gdccClass = new LirClassDef("TestClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("set_vec_x");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("vec", GdFloatVectorType.VECTOR2);
        func.createAndAddVariable("value", GdFloatType.FLOAT);
        addEntryStoreAndReturn(func, new StorePropertyInsn("x", "vec", "value"));
        gdccClass.addFunction(func);

        var module = new LirModule("test_module", List.of(gdccClass));
        var ctx = newContext(api, List.of(gdccClass));

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);

        var body = codegen.generateFuncBody(gdccClass, func);
        assertTrue(body.contains("godot_Vector2_set_x(&$vec, $value);"));
    }

    @Test
    @DisplayName("Builtin ref receiver should not add extra address-of")
    void builtinRefReceiverDoesNotUseExtraAddressOf() {
        var vector2Class = vector2Builtin();
        var api = new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), List.of(vector2Class), List.of(), List.of(), List.of());

        var gdccClass = new LirClassDef("TestClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("set_vec_x_ref");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddRefVariable("vec", GdFloatVectorType.VECTOR2);
        func.createAndAddVariable("value", GdFloatType.FLOAT);
        addEntryStoreAndReturn(func, new StorePropertyInsn("x", "vec", "value"));
        gdccClass.addFunction(func);

        var module = new LirModule("test_module", List.of(gdccClass));
        var ctx = newContext(api, List.of(gdccClass));

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);

        var body = codegen.generateFuncBody(gdccClass, func);
        assertTrue(body.contains("godot_Vector2_set_x($vec, $value);"));
        assertFalse(body.contains("godot_Vector2_set_x(&$vec, $value);"));
    }

    @Test
    @DisplayName("Default API builtin member-backed properties should use builtin setter names")
    void defaultApiBuiltinMemberBackedPropertiesShouldUseBuiltinSetterNames() throws IOException {
        var api = ExtensionApiLoader.loadDefault();

        var gdccClass = new LirClassDef("TestClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());

        var vectorFunc = new LirFunctionDef("set_axis_x");
        vectorFunc.setReturnType(GdVoidType.VOID);
        vectorFunc.addParameter(new LirParameterDef("vector", GdFloatVectorType.VECTOR3, null, vectorFunc));
        vectorFunc.addParameter(new LirParameterDef("value", GdFloatType.FLOAT, null, vectorFunc));
        addEntryStoreAndReturn(vectorFunc, new StorePropertyInsn("x", "vector", "value"));
        gdccClass.addFunction(vectorFunc);

        var colorFunc = new LirFunctionDef("set_alpha");
        colorFunc.setReturnType(GdVoidType.VOID);
        colorFunc.addParameter(new LirParameterDef("color", GdColorType.COLOR, null, colorFunc));
        colorFunc.addParameter(new LirParameterDef("alpha", GdFloatType.FLOAT, null, colorFunc));
        addEntryStoreAndReturn(colorFunc, new StorePropertyInsn("a", "color", "alpha"));
        gdccClass.addFunction(colorFunc);

        var module = new LirModule("test_module", List.of(gdccClass));
        var ctx = newContext(api, List.of(gdccClass));

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);

        var vectorBody = codegen.generateFuncBody(gdccClass, vectorFunc);
        assertTrue(vectorBody.contains("godot_Vector3_set_x($vector, $value);"), vectorBody);
        assertFalse(vectorBody.contains("godot_Object_set"), vectorBody);

        var colorBody = codegen.generateFuncBody(gdccClass, colorFunc);
        assertTrue(colorBody.contains("godot_Color_set_a($color, $alpha);"), colorBody);
        assertFalse(colorBody.contains("godot_Object_set"), colorBody);
    }

    @Test
    @DisplayName("Default API missing builtin member should still fail-fast on store")
    void defaultApiMissingBuiltinMemberShouldStillFailFastOnStore() throws IOException {
        var api = ExtensionApiLoader.loadDefault();

        var gdccClass = new LirClassDef("TestClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("set_missing_axis");
        func.setReturnType(GdVoidType.VOID);
        func.addParameter(new LirParameterDef("vector", GdFloatVectorType.VECTOR3, null, func));
        func.addParameter(new LirParameterDef("value", GdFloatType.FLOAT, null, func));
        addEntryStoreAndReturn(func, new StorePropertyInsn("missing_axis", "vector", "value"));
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
        var vector2Class = vector2Builtin();
        var api = new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), List.of(vector2Class), List.of(), List.of(), List.of());

        var gdccClass = new LirClassDef("TestClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("set_vec_x");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("vec", GdFloatVectorType.VECTOR2);
        func.createAndAddVariable("value", GdFloatType.FLOAT);
        addEntryStoreAndReturn(func, new StorePropertyInsn("y", "vec", "value"));
        gdccClass.addFunction(func);

        var module = new LirModule("test_module", List.of(gdccClass));
        var ctx = newContext(api, List.of(gdccClass));

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);

        var ex = assertThrows(InvalidInsnException.class, () -> codegen.generateFuncBody(gdccClass, func));
        assertInstanceOf(InvalidInsnException.class, ex);
    }

    @Test
    @DisplayName("Subtype value should be assignable to supertype property")
    void subtypeValueAssignableToSupertypeProperty() {
        var nodeClass = new ExtensionGdClass(
                "Node", false, true, "Object", "core",
                List.of(), List.of(), List.of(),
                List.of(), List.of()
        );
        var api = new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(nodeClass), List.of(), List.of());

        var gdccClass = new LirClassDef("MyClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        gdccClass.addProperty(new LirPropertyDef("target", new GdObjectType("Object"), false, null, null, "_field_setter_target", Map.of()));

        var func = new LirFunctionDef("set_target");
        func.setReturnType(GdVoidType.VOID);
        func.addParameter(new LirParameterDef("obj", new GdObjectType("MyClass"), null, func));
        func.addParameter(new LirParameterDef("value", new GdObjectType("Node"), null, func));
        addEntryStoreAndReturn(func, new StorePropertyInsn("target", "obj", "value"));
        gdccClass.addFunction(func);

        var module = new LirModule("test_module", List.of(gdccClass));
        var ctx = newContext(api, List.of(gdccClass));

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);

        var body = codegen.generateFuncBody(gdccClass, func);
        assertTrue(body.contains("MyClass__field_setter_target($obj, $value);"));
    }

    @Test
    @DisplayName("GDCC property without setter should throw when storing outside setter")
    void gdccPropertyWithoutSetterShouldThrow() {
        var gdccClass = new LirClassDef("MyClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        gdccClass.addProperty(new LirPropertyDef("value", GdStringType.STRING, false, null, null, null, Map.of()));

        var func = new LirFunctionDef("set_value");
        func.setReturnType(GdVoidType.VOID);
        func.addParameter(new LirParameterDef("obj", new GdObjectType("MyClass"), null, func));
        func.addParameter(new LirParameterDef("value", GdStringType.STRING, null, func));
        addEntryStoreAndReturn(func, new StorePropertyInsn("value", "obj", "value"));
        gdccClass.addFunction(func);

        var module = new LirModule("test_module", List.of(gdccClass));
        var ctx = newContext(emptyApi(), List.of(gdccClass));

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);

        var ex = assertThrows(InvalidInsnException.class, () -> codegen.generateFuncBody(gdccClass, func));
        assertInstanceOf(InvalidInsnException.class, ex);
    }

    @Test
    @DisplayName("GDCC child receiver should call parent GDCC setter via _super upcast")
    void gdccChildReceiverShouldCallParentGdccSetterViaSuperUpcast() {
        var parentClass = new LirClassDef("ParentClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        parentClass.addProperty(new LirPropertyDef("value", GdStringType.STRING, false, null, null, "_field_setter_value", Map.of()));

        var childClass = new LirClassDef("ChildClass", "ParentClass", false, false, Map.of(), List.of(), List.of(), List.of());
        var hostClass = new LirClassDef("HostClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("store_parent_value");
        func.setReturnType(GdVoidType.VOID);
        func.addParameter(new LirParameterDef("child", new GdObjectType("ChildClass"), null, func));
        func.addParameter(new LirParameterDef("value", GdStringType.STRING, null, func));
        addEntryStoreAndReturn(func, new StorePropertyInsn("value", "child", "value"));
        hostClass.addFunction(func);

        var module = new LirModule("test_module", List.of(hostClass, childClass, parentClass));
        var ctx = newContext(
                new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()),
                List.of(hostClass, childClass, parentClass)
        );

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);

        var body = codegen.generateFuncBody(hostClass, func);
        assertTrue(body.contains("ParentClass__field_setter_value(&($child->_super), $value);"), body);
        assertFalse(body.contains("ParentClass__field_setter_value((ParentClass*)$child, $value);"), body);
    }

    @Test
    @DisplayName("Three-level GDCC chain should call top parent setter via _super._super upcast")
    void threeLevelGdccChainShouldCallTopParentSetterViaDoubleSuperUpcast() {
        var parentClass = new LirClassDef("ParentClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        parentClass.addProperty(new LirPropertyDef("value", GdStringType.STRING, false, null, null, "_field_setter_value", Map.of()));

        var childClass = new LirClassDef("ChildClass", "ParentClass", false, false, Map.of(), List.of(), List.of(), List.of());
        var grandChildClass = new LirClassDef("GrandChildClass", "ChildClass", false, false, Map.of(), List.of(), List.of(), List.of());
        var hostClass = new LirClassDef("HostClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());

        var func = new LirFunctionDef("store_top_parent_value");
        func.setReturnType(GdVoidType.VOID);
        func.addParameter(new LirParameterDef("grand", new GdObjectType("GrandChildClass"), null, func));
        func.addParameter(new LirParameterDef("value", GdStringType.STRING, null, func));
        addEntryStoreAndReturn(func, new StorePropertyInsn("value", "grand", "value"));
        hostClass.addFunction(func);

        var module = new LirModule("test_module", List.of(hostClass, grandChildClass, childClass, parentClass));
        var ctx = newContext(
                new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()),
                List.of(hostClass, grandChildClass, childClass, parentClass)
        );

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);

        var body = codegen.generateFuncBody(hostClass, func);
        assertTrue(body.contains("ParentClass__field_setter_value(&($grand->_super._super), $value);"), body);
        assertFalse(body.contains("ParentClass__field_setter_value((ParentClass*)$grand, $value);"), body);
    }

    @Test
    @DisplayName("Shadowed property should resolve nearest owner setter on inheritance chain")
    void shadowedPropertyShouldResolveNearestOwnerSetterOnInheritanceChain() {
        var parentClass = new LirClassDef("ParentClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        parentClass.addProperty(new LirPropertyDef("value", GdStringType.STRING, false, null, null, "_field_setter_parent_value", Map.of()));

        var childClass = new LirClassDef("ChildClass", "ParentClass", false, false, Map.of(), List.of(), List.of(), List.of());
        childClass.addProperty(new LirPropertyDef("value", GdStringType.STRING, false, null, null, "_field_setter_child_value", Map.of()));

        var grandChildClass = new LirClassDef("GrandChildClass", "ChildClass", false, false, Map.of(), List.of(), List.of(), List.of());
        var hostClass = new LirClassDef("HostClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());

        var func = new LirFunctionDef("store_shadowed_value");
        func.setReturnType(GdVoidType.VOID);
        func.addParameter(new LirParameterDef("grand", new GdObjectType("GrandChildClass"), null, func));
        func.addParameter(new LirParameterDef("value", GdStringType.STRING, null, func));
        addEntryStoreAndReturn(func, new StorePropertyInsn("value", "grand", "value"));
        hostClass.addFunction(func);

        var module = new LirModule("test_module", List.of(hostClass, grandChildClass, childClass, parentClass));
        var ctx = newContext(
                new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()),
                List.of(hostClass, grandChildClass, childClass, parentClass)
        );

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);

        var body = codegen.generateFuncBody(hostClass, func);
        assertTrue(body.contains("ChildClass__field_setter_child_value(&($grand->_super), $value);"), body);
        assertFalse(body.contains("ParentClass__field_setter_parent_value("), body);
    }

    @Test
    @DisplayName("GDCC receiver should call ENGINE owner setter with GDCC->Godot conversion")
    void gdccReceiverShouldCallEngineOwnerSetterWithConversion() {
        var nodeClass = new ExtensionGdClass(
                "Node", false, true, "Object", "core",
                List.of(), List.of(engineMethod("set_name", 902L, "void", List.of(arg("value", "String")))), List.of(),
                List.of(new ExtensionGdClass.PropertyInfo("name", "String", true, true, "", "get_name", "set_name", null)),
                List.of()
        );
        var api = new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(nodeClass), List.of(), List.of());

        var userClass = new LirClassDef("MyClass", "Node", false, false, Map.of(), List.of(), List.of(), List.of());
        var hostClass = new LirClassDef("HostClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("store_engine_parent_prop");
        func.setReturnType(GdVoidType.VOID);
        func.addParameter(new LirParameterDef("obj", new GdObjectType("MyClass"), null, func));
        func.addParameter(new LirParameterDef("value", GdStringType.STRING, null, func));
        addEntryStoreAndReturn(func, new StorePropertyInsn("name", "obj", "value"));
        hostClass.addFunction(func);

        var module = new LirModule("test_module", List.of(hostClass, userClass));
        var ctx = newContext(api, List.of(hostClass, userClass));

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);

        var body = codegen.generateFuncBody(hostClass, func);
        assertTrue(body.contains("gdcc_engine_call_node_set_name_PT_RV((godot_Node*)gdcc_object_to_godot_object_ptr($obj, MyClass_object_ptr), $value);"), body);
        assertFalse(body.contains("gdcc_engine_call_node_set_name_PT_RV((godot_Node*)$obj, $value);"), body);
    }

    @Test
    @DisplayName("ENGINE child receiver should call ENGINE parent setter with owner cast")
    void engineChildReceiverShouldCallEngineParentSetterWithOwnerCast() {
        var nodeClass = new ExtensionGdClass(
                "Node", false, true, "Object", "core",
                List.of(), List.of(engineMethod("set_name", 1002L, "void", List.of(arg("value", "String")))), List.of(),
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
        var func = new LirFunctionDef("store_engine_parent_prop");
        func.setReturnType(GdVoidType.VOID);
        func.addParameter(new LirParameterDef("control", new GdObjectType("Control"), null, func));
        func.addParameter(new LirParameterDef("value", GdStringType.STRING, null, func));
        addEntryStoreAndReturn(func, new StorePropertyInsn("name", "control", "value"));
        hostClass.addFunction(func);

        var module = new LirModule("test_module", List.of(hostClass));
        var ctx = newContext(api, List.of(hostClass));

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);

        var body = codegen.generateFuncBody(hostClass, func);
        assertTrue(body.contains("gdcc_engine_call_node_set_name_PT_RV((godot_Node*)$control, $value);"), body);
        assertFalse(body.contains("gdcc_engine_call_control_set_name_"), body);
    }

    @Test
    @DisplayName("Three-level GDCC->GDCC->ENGINE chain should resolve ENGINE owner setter")
    void threeLevelGdccToEngineChainShouldResolveEngineOwnerSetter() {
        var nodeClass = new ExtensionGdClass(
                "Node", false, true, "Object", "core",
                List.of(), List.of(engineMethod("set_name", 1102L, "void", List.of(arg("value", "String")))), List.of(),
                List.of(new ExtensionGdClass.PropertyInfo("name", "String", true, true, "", "get_name", "set_name", null)),
                List.of()
        );
        var api = new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(nodeClass), List.of(), List.of());

        var childClass = new LirClassDef("ChildClass", "Node", false, false, Map.of(), List.of(), List.of(), List.of());
        var grandChildClass = new LirClassDef("GrandChildClass", "ChildClass", false, false, Map.of(), List.of(), List.of(), List.of());
        var hostClass = new LirClassDef("HostClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("store_chain_prop");
        func.setReturnType(GdVoidType.VOID);
        func.addParameter(new LirParameterDef("grand", new GdObjectType("GrandChildClass"), null, func));
        func.addParameter(new LirParameterDef("value", GdStringType.STRING, null, func));
        addEntryStoreAndReturn(func, new StorePropertyInsn("name", "grand", "value"));
        hostClass.addFunction(func);

        var module = new LirModule("test_module", List.of(hostClass, childClass, grandChildClass));
        var ctx = newContext(api, List.of(hostClass, childClass, grandChildClass));

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);

        var body = codegen.generateFuncBody(hostClass, func);
        assertTrue(body.contains("gdcc_engine_call_node_set_name_PT_RV((godot_Node*)gdcc_object_to_godot_object_ptr($grand, GrandChildClass_object_ptr), $value);"), body);
        assertFalse(body.contains("gdcc_engine_call_node_set_name_PT_RV((godot_Node*)$grand, $value);"), body);
    }

    @Test
    @DisplayName("Known object receiver should fail-fast when property is absent in hierarchy")
    void knownObjectReceiverShouldFailFastWhenPropertyAbsentInHierarchy() {
        var parentClass = new LirClassDef("ParentClass", "", false, false, Map.of(), List.of(), List.of(), List.of());
        var childClass = new LirClassDef("ChildClass", "ParentClass", false, false, Map.of(), List.of(), List.of(), List.of());
        var hostClass = new LirClassDef("HostClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("store_missing_prop");
        func.setReturnType(GdVoidType.VOID);
        func.addParameter(new LirParameterDef("child", new GdObjectType("ChildClass"), null, func));
        func.addParameter(new LirParameterDef("value", GdStringType.STRING, null, func));
        addEntryStoreAndReturn(func, new StorePropertyInsn("missing_prop", "child", "value"));
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
    @DisplayName("Store property should fail-fast when value type is not assignable to property type")
    void storePropertyShouldFailWhenValueTypeNotAssignableToPropertyType() {
        var gdccClass = new LirClassDef("MyClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        gdccClass.addProperty(new LirPropertyDef("value", GdStringType.STRING, false, null, null, "_field_setter_value", Map.of()));

        var hostClass = new LirClassDef("HostClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("store_type_mismatch");
        func.setReturnType(GdVoidType.VOID);
        func.addParameter(new LirParameterDef("obj", new GdObjectType("MyClass"), null, func));
        func.addParameter(new LirParameterDef("value", GdFloatType.FLOAT, null, func));
        addEntryStoreAndReturn(func, new StorePropertyInsn("value", "obj", "value"));
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
        assertTrue(ex.getMessage().contains("not assignable to property"), ex.getMessage());
    }

    @Test
    @DisplayName("Setter-self fast path should not trigger when owner is parent class")
    void setterSelfFastPathShouldNotTriggerWhenOwnerIsParentClass() {
        var parentClass = new LirClassDef("ParentClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        parentClass.addProperty(new LirPropertyDef("value", GdStringType.STRING, false, null, null, "_field_setter_value", Map.of()));

        var childClass = new LirClassDef("ChildClass", "ParentClass", false, false, Map.of(), List.of(), List.of(), List.of());
        var childSetter = new LirFunctionDef("_field_setter_value");
        childSetter.setReturnType(GdVoidType.VOID);
        childSetter.addParameter(new LirParameterDef("self", new GdObjectType("ChildClass"), null, childSetter));
        childSetter.addParameter(new LirParameterDef("value", GdStringType.STRING, null, childSetter));
        addEntryStoreAndReturn(childSetter, new StorePropertyInsn("value", "self", "value"));
        childClass.addFunction(childSetter);

        var module = new LirModule("test_module", List.of(childClass, parentClass));
        var ctx = newContext(
                new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()),
                List.of(childClass, parentClass)
        );

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);

        var body = codegen.generateFuncBody(childClass, childSetter);
        assertTrue(body.contains("ParentClass__field_setter_value(&($self->_super), $value);"), body);
        assertFalse(body.contains("$self->value ="), body);
    }

    private void addEntryStoreAndReturn(LirFunctionDef func, StorePropertyInsn storeInsn) {
        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(storeInsn);
        entry.appendInstruction(new ReturnInsn(null));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
    }

    private ExtensionBuiltinClass vector2Builtin() {
        return new ExtensionBuiltinClass(
                "Vector2", false,
                List.of(), List.of(), List.of(),
                List.of(),
                List.of(new ExtensionBuiltinClass.MemberInfo("x", "float")),
                List.of()
        );
    }

    private ExtensionAPI emptyApi() {
        return new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
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
