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
import dev.superice.gdcc.lir.insn.StorePropertyInsn;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.type.GdFloatType;
import dev.superice.gdcc.type.GdFloatVectorType;
import dev.superice.gdcc.type.GdObjectType;
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

public class CStorePropertyInsnGenTest {
    @Test
    @DisplayName("GDCC setter should store field directly when inside the setter itself")
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
        assertTrue(body.contains("godot_String_destroy(&$self->value);"));
        assertTrue(body.contains("__gdcc_tmp_string_0 = godot_new_String_with_String($value);"));
        assertTrue(body.contains("$self->value = __gdcc_tmp_string_0;"));
        assertTrue(body.contains("godot_String_destroy(&__gdcc_tmp_string_0);"));
        assertFalse(body.contains("MyClass__field_setter_value("));
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
        entry.instructions().add(new LoadPropertyInsn("rhs", "obj", "self"));
        entry.instructions().add(new StorePropertyInsn("obj", "self", "rhs"));
        entry.instructions().add(new ReturnInsn(null));
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
                List.of(), List.of(), List.of(),
                List.of(new ExtensionGdClass.PropertyInfo("name", "String", true, true, "")),
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
        assertTrue(body.contains("godot_Node_set_name($node, $value);"));
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
        assertFalse(body.contains("godot_UnknownType_set_name("));
    }

    @Test
    @DisplayName("Builtin property should pass non-ref receiver with address-of")
    void builtinPropertyUsesAddressOfForReceiverVariable() {
        var vector2Class = vector2Builtin(true);
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
        var vector2Class = vector2Builtin(true);
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
    @DisplayName("Builtin non-writable property should throw")
    void builtinNonWritablePropertyShouldThrow() {
        var vector2Class = vector2Builtin(false);
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

    private void addEntryStoreAndReturn(LirFunctionDef func, StorePropertyInsn storeInsn) {
        var entry = new LirBasicBlock("entry");
        entry.instructions().add(storeInsn);
        entry.instructions().add(new ReturnInsn(null));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
    }

    private ExtensionBuiltinClass vector2Builtin(boolean writable) {
        return new ExtensionBuiltinClass(
                "Vector2", false,
                List.of(), List.of(), List.of(),
                List.of(),
                List.of(new ExtensionBuiltinClass.PropertyInfo("x", "float", true, writable, "0")),
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
}
