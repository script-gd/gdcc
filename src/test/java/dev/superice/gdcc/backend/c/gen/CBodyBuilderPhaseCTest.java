package dev.superice.gdcc.backend.c.gen;

import dev.superice.gdcc.backend.CodegenContext;
import dev.superice.gdcc.backend.ProjectInfo;
import dev.superice.gdcc.enums.GodotVersion;
import dev.superice.gdcc.gdextension.ExtensionAPI;
import dev.superice.gdcc.gdextension.ExtensionEnumValue;
import dev.superice.gdcc.gdextension.ExtensionGlobalEnum;
import dev.superice.gdcc.gdextension.ExtensionGdClass;
import dev.superice.gdcc.lir.LirBasicBlock;
import dev.superice.gdcc.lir.LirClassDef;
import dev.superice.gdcc.lir.LirFunctionDef;
import dev.superice.gdcc.lir.LirVariable;
import dev.superice.gdcc.lir.insn.DestructInsn;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.type.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/// Tests for CBodyBuilder Phase C: Semantic Core Implementation.
///
/// Phase C focuses on:
/// - Argument rendering with '&' decision
/// - Assignability check
/// - RHS copy and conversion
/// - Non-object destruction
/// - Object own/release
public class CBodyBuilderPhaseCTest {
    private CBodyBuilder builder;
    private LirFunctionDef lirFunctionDef;

    @BeforeEach
    void setUp() {
        var projectInfo = new ProjectInfo("TestProject", GodotVersion.V451, Path.of(".")) {
            // Anonymous subclass to bypass abstract
        };

        // Create ExtensionAPI with some engine classes for testing
        var refCountedClass = new ExtensionGdClass(
                "RefCounted", true, true, "Object", "core",
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList()
        );
        var nodeClass = new ExtensionGdClass(
                "Node", false, true, "Object", "core",
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList()
        );
        var extensionAPI = new ExtensionAPI(
                null,
                Collections.emptyList(),
                Collections.emptyList(),
                List.of(new ExtensionGlobalEnum(
                        "TestEnum",
                        false,
                        List.of(new ExtensionEnumValue("VALUE_A", 42))
                )),
                Collections.emptyList(),
                Collections.emptyList(),
                List.of(refCountedClass, nodeClass),
                Collections.emptyList(),
                Collections.emptyList()
        );
        var classRegistry = new ClassRegistry(extensionAPI);

        // Add a GDCC class for testing
        var gdccClass = new LirClassDef("MyGdccClass", "RefCounted", false, false,
                Collections.emptyMap(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        classRegistry.addGdccClass(gdccClass);

        var ctx = new CodegenContext(projectInfo, classRegistry);
        var lirClassDef = new LirClassDef("TestClass", "RefCounted", false, false,
                Collections.emptyMap(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList());

        lirFunctionDef = new LirFunctionDef("testFunc", false, false, false, false, false,
                Collections.emptyMap(), Collections.emptyList(), Collections.emptyMap(),
                GdVoidType.VOID, Collections.emptyMap(), new LinkedHashMap<>());

        var helper = new CGenHelper(ctx, List.of(lirClassDef));
        builder = new CBodyBuilder(helper, lirClassDef, lirFunctionDef);
    }

    @Nested
    @DisplayName("Argument Rendering Tests")
    class ArgumentRenderingTests {

        @Test
        @DisplayName("Primitive types should be passed by value (no &)")
        void testPrimitiveArgumentNoAddressOf() {
            var intVar = new LirVariable("i", GdIntType.INT, lirFunctionDef);
            var value = builder.valueOfVar(intVar);

            builder.callVoid("func", List.of(value));

            assertEquals("func($i);\n", builder.build());
        }

        @Test
        @DisplayName("Object types should be passed by value (pointer)")
        void testObjectArgumentNoAddressOf() {
            var objVar = new LirVariable("obj", new GdObjectType("Node"), lirFunctionDef);
            var value = builder.valueOfVar(objVar);

            builder.callVoid("func", List.of(value));

            assertEquals("func($obj);\n", builder.build());
        }

        @Test
        @DisplayName("String type should be passed by reference (&)")
        void testStringArgumentWithAddressOf() {
            var strVar = new LirVariable("s", GdStringType.STRING, lirFunctionDef);
            var value = builder.valueOfVar(strVar);

            builder.callVoid("func", List.of(value));

            assertEquals("func(&$s);\n", builder.build());
        }

        @Test
        @DisplayName("Variant type should be passed by reference (&)")
        void testVariantArgumentWithAddressOf() {
            var variantVar = new LirVariable("v", GdVariantType.VARIANT, lirFunctionDef);
            var value = builder.valueOfVar(variantVar);

            builder.callVoid("func", List.of(value));

            assertEquals("func(&$v);\n", builder.build());
        }

        @Test
        @DisplayName("Array type should be passed by reference (&)")
        void testArrayArgumentWithAddressOf() {
            var arrVar = new LirVariable("arr", new GdArrayType(GdIntType.INT), lirFunctionDef);
            var value = builder.valueOfVar(arrVar);

            builder.callVoid("func", List.of(value));

            assertEquals("func(&$arr);\n", builder.build());
        }

        @Test
        @DisplayName("Ref variable should not add extra &")
        void testRefVariableNoExtraAddressOf() {
            var refVar = new LirVariable("ref", GdStringType.STRING, true, lirFunctionDef);
            var value = builder.valueOfVar(refVar);

            builder.callVoid("func", List.of(value));

            // ref variables are already pointers, should not add &
            assertEquals("func($ref);\n", builder.build());
        }

        @Test
        @DisplayName("Expression with String type should add &")
        void testExpressionWithAddressOf() {
            var value = builder.valueOfExpr("some_string_expr", GdStringType.STRING);

            builder.callVoid("func", List.of(value));

            assertEquals("""
                    godot_String __gdcc_tmp_string_0 = some_string_expr;
                    func(&__gdcc_tmp_string_0);
                    godot_String_destroy(&__gdcc_tmp_string_0);
                    """, builder.build());
        }
    }

    @Nested
    @DisplayName("Assignment Semantics Tests")
    class AssignmentSemanticsTests {

        @Test
        @DisplayName("Primitive assignment should be direct")
        void testPrimitiveAssignment() {
            var target = new LirVariable("x", GdIntType.INT, lirFunctionDef);
            var targetRef = builder.targetOfVar(target);
            var value = builder.valueOfExpr("42", GdIntType.INT);

            builder.assignVar(targetRef, value);

            assertEquals("$x = 42;\n", builder.build());
        }

        @Test
        @DisplayName("assignExpr should use assignment semantics")
        void testAssignExpr() {
            var target = new LirVariable("x", GdIntType.INT, lirFunctionDef);
            var targetRef = builder.targetOfVar(target);

            builder.assignExpr(targetRef, "123", GdIntType.INT);

            assertEquals("$x = 123;\n", builder.build());
        }

        @Test
        @DisplayName("assignGlobalConst should resolve global enum value")
        void testAssignGlobalConst() {
            var target = new LirVariable("x", GdIntType.INT, lirFunctionDef);
            var targetRef = builder.targetOfVar(target);

            builder.assignGlobalConst(targetRef, "TestEnum", "VALUE_A");

            assertEquals("$x = 42;\n", builder.build());
        }

        @Test
        @DisplayName("String assignment should destroy old and copy new")
        void testStringAssignment() {
            var target = new LirVariable("s", GdStringType.STRING, lirFunctionDef);
            var source = new LirVariable("src", GdStringType.STRING, lirFunctionDef);
            var targetRef = builder.targetOfVar(target);
            var value = builder.valueOfVar(source);

            builder.assignVar(targetRef, value);

            var result = builder.build();
            // Should contain destroy for old value
            assertTrue(result.contains("godot_String_destroy(&$s)"), "Should destroy old String");
            // Should contain copy for new value
            assertTrue(result.contains("godot_new_String_with_String"), "Should copy new String");
        }

        @Test
        @DisplayName("Variant assignment should destroy old and copy new")
        void testVariantAssignment() {
            var target = new LirVariable("v", GdVariantType.VARIANT, lirFunctionDef);
            var source = new LirVariable("src", GdVariantType.VARIANT, lirFunctionDef);
            var targetRef = builder.targetOfVar(target);
            var value = builder.valueOfVar(source);

            builder.assignVar(targetRef, value);

            var result = builder.build();
            assertTrue(result.contains("godot_Variant_destroy"), "Should destroy old Variant");
            assertTrue(result.contains("godot_new_Variant_with_Variant"), "Should copy new Variant");
        }

        @Test
        @DisplayName("RefCounted object assignment should release old and own new")
        void testRefCountedObjectAssignment() {
            var target = new LirVariable("obj", new GdObjectType("RefCounted"), lirFunctionDef);
            var source = new LirVariable("src", new GdObjectType("RefCounted"), lirFunctionDef);
            var targetRef = builder.targetOfVar(target);
            var value = builder.valueOfVar(source);

            builder.assignVar(targetRef, value);

            var result = builder.build();
            // RefCounted: should use release_object and own_object (not try_ versions)
            assertTrue(result.contains("release_object($obj)"), "Should release old RefCounted object");
            assertTrue(result.contains("own_object($obj)"), "Should own new RefCounted object");
        }

        @Test
        @DisplayName("Node (non-RefCounted) object assignment should not have own/release")
        void testNonRefCountedObjectAssignment() {
            var target = new LirVariable("node", new GdObjectType("Node"), lirFunctionDef);
            var source = new LirVariable("src", new GdObjectType("Node"), lirFunctionDef);
            var targetRef = builder.targetOfVar(target);
            var value = builder.valueOfVar(source);

            builder.assignVar(targetRef, value);

            var result = builder.build();
            // Node is not RefCounted, should not have own/release
            assertFalse(result.contains("release_object"), "Should not release non-RefCounted object");
            assertFalse(result.contains("own_object"), "Should not own non-RefCounted object");
            assertFalse(result.contains("try_release_object"), "Should not try_release non-RefCounted object");
            assertFalse(result.contains("try_own_object"), "Should not try_own non-RefCounted object");
        }

        @Test
        @DisplayName("GDCC object assignment should use ->_object for own/release")
        void testGdccObjectAssignment() {
            var target = new LirVariable("myObj", new GdObjectType("MyGdccClass"), lirFunctionDef);
            var source = new LirVariable("src", new GdObjectType("MyGdccClass"), lirFunctionDef);
            var targetRef = builder.targetOfVar(target);
            var value = builder.valueOfVar(source);

            builder.assignVar(targetRef, value);

            var result = builder.build();
            // GDCC class inherits RefCounted, should use ->_object
            assertTrue(result.contains("$myObj->_object"), "Should use ->_object for GDCC object");
        }

        @Test
        @DisplayName("Self String assignment should copy before destroy and destroy temp after")
        void testSelfStringAssignmentOrder() {
            var target = new LirVariable("s", GdStringType.STRING, lirFunctionDef);
            var targetRef = builder.targetOfVar(target);
            var value = builder.valueOfVar(target);

            builder.assignVar(targetRef, value);

            var result = builder.build();
            var tempDecl = "godot_String __gdcc_tmp_string_0 = godot_new_String_with_String(&$s);";
            var destroyOld = "godot_String_destroy(&$s);";
            var assign = "$s = __gdcc_tmp_string_0;";
            var destroyTemp = "godot_String_destroy(&__gdcc_tmp_string_0);";

            var tempIndex = result.indexOf(tempDecl);
            var destroyOldIndex = result.indexOf(destroyOld);
            var assignIndex = result.indexOf(assign);
            var destroyTempIndex = result.indexOf(destroyTemp);

            assertTrue(tempIndex >= 0, "Should materialize copy temp");
            assertTrue(destroyOldIndex >= 0, "Should destroy old value");
            assertTrue(assignIndex >= 0, "Should assign new value");
            assertTrue(destroyTempIndex >= 0, "Should destroy temp");
            assertTrue(tempIndex < destroyOldIndex, "Should copy before destroying old value");
            assertTrue(destroyOldIndex < assignIndex, "Should destroy old value before assignment");
            assertTrue(assignIndex < destroyTempIndex, "Should destroy temp after assignment");
        }
    }

    @Nested
    @DisplayName("Return Value Semantics Tests")
    class ReturnValueSemanticsTests {

        @Test
        @DisplayName("Returning primitive should be direct")
        void testReturnPrimitive() {
            var intVar = new LirVariable("i", GdIntType.INT, lirFunctionDef);
            var value = builder.valueOfVar(intVar);

            builder.returnValue(value);

            assertEquals("return $i;\n", builder.build());
        }

        @Test
        @DisplayName("Returning String should copy")
        void testReturnString() {
            var strVar = new LirVariable("s", GdStringType.STRING, lirFunctionDef);
            var value = builder.valueOfVar(strVar);

            builder.returnValue(value);

            var result = builder.build();
            assertTrue(result.contains("godot_new_String_with_String(&$s)"), "Should copy String on return");
        }

        @Test
        @DisplayName("Returning object should be direct (pointer)")
        void testReturnObject() {
            var objVar = new LirVariable("obj", new GdObjectType("Node"), lirFunctionDef);
            var value = builder.valueOfVar(objVar);

            builder.returnValue(value);

            assertEquals("return $obj;\n", builder.build());
        }

        @Test
        @DisplayName("Returning String expression should destroy temp after copy")
        void testReturnStringExprTempOrder() {
            var value = builder.valueOfExpr("some_string_expr", GdStringType.STRING);

            builder.returnValue(value);

            var result = builder.build();
            var tempDecl = "godot_String __gdcc_tmp_string_0 = some_string_expr;";
            var retTempDecl = "godot_String __gdcc_tmp_ret_1 = godot_new_String_with_String(&__gdcc_tmp_string_0);";
            var destroyTemp = "godot_String_destroy(&__gdcc_tmp_string_0);";
            var retLine = "return __gdcc_tmp_ret_1;";

            var tempIndex = result.indexOf(tempDecl);
            var retTempIndex = result.indexOf(retTempDecl);
            var destroyTempIndex = result.indexOf(destroyTemp);
            var retIndex = result.indexOf(retLine);

            assertTrue(tempIndex >= 0, "Should materialize expression temp");
            assertTrue(retTempIndex >= 0, "Should copy into return temp");
            assertTrue(destroyTempIndex >= 0, "Should destroy expression temp");
            assertTrue(retIndex >= 0, "Should return temp");
            assertTrue(tempIndex < retTempIndex, "Should create expression temp before copy");
            assertTrue(retTempIndex < destroyTempIndex, "Should destroy expression temp after copy");
            assertTrue(destroyTempIndex < retIndex, "Should destroy expression temp before return");
        }
    }

    @Nested
    @DisplayName("Assignability Check Tests")
    class AssignabilityCheckTests {

        @Test
        @DisplayName("Incompatible types should throw exception")
        void testIncompatibleTypeAssignment() {
            var target = new LirVariable("s", GdStringType.STRING, lirFunctionDef);
            var targetRef = builder.targetOfVar(target);
            var value = builder.valueOfExpr("42", GdIntType.INT);

            assertThrows(RuntimeException.class, () -> builder.assignVar(targetRef, value));
        }

        @Test
        @DisplayName("Compatible object types should work")
        void testCompatibleObjectTypeAssignment() {
            // RefCounted is assignable to Object
            var target = new LirVariable("obj", GdObjectType.OBJECT, lirFunctionDef);
            var targetRef = builder.targetOfVar(target);
            var value = builder.valueOfExpr("refCountedPtr", new GdObjectType("RefCounted"));

            assertDoesNotThrow(() -> builder.assignVar(targetRef, value));
        }
    }

    @Nested
    @DisplayName("Call with Assignment Tests")
    class CallAssignTests {

        @Test
        @DisplayName("callAssign with String target should destroy old")
        void testCallAssignStringTarget() {
            var target = new LirVariable("s", GdStringType.STRING, lirFunctionDef);
            var targetRef = builder.targetOfVar(target);

            builder.callAssign(targetRef, "get_string", GdStringType.STRING, List.of());

            var result = builder.build();
            assertTrue(result.contains("godot_String_destroy(&$s)"), "Should destroy old String before assignment");
            assertTrue(result.contains("$s = get_string()"), "Should assign result");
        }

        @Test
        @DisplayName("callAssign with RefCounted target should release old and own new")
        void testCallAssignRefCountedTarget() {
            var target = new LirVariable("obj", new GdObjectType("RefCounted"), lirFunctionDef);
            var targetRef = builder.targetOfVar(target);

            builder.callAssign(targetRef, "create_object", new GdObjectType("RefCounted"), List.of());

            var result = builder.build();
            assertTrue(result.contains("release_object($obj)"), "Should release old object before assignment");
            assertTrue(result.contains("own_object($obj)"), "Should own new object after assignment");
        }

        @Test
        @DisplayName("callAssign should destroy temp after assignment")
        void testCallAssignTempDestroyOrder() {
            var target = new LirVariable("s", GdStringType.STRING, lirFunctionDef);
            var targetRef = builder.targetOfVar(target);
            var arg = builder.valueOfExpr("some_string_expr", GdStringType.STRING);

            builder.callAssign(targetRef, "get_string", GdStringType.STRING, List.of(arg));

            var result = builder.build();
            var tempDecl = "godot_String __gdcc_tmp_string_0 = some_string_expr;";
            var destroyOld = "godot_String_destroy(&$s);";
            var assign = "$s = get_string(&__gdcc_tmp_string_0);";
            var destroyTemp = "godot_String_destroy(&__gdcc_tmp_string_0);";

            var tempIndex = result.indexOf(tempDecl);
            var destroyOldIndex = result.indexOf(destroyOld);
            var assignIndex = result.indexOf(assign);
            var destroyTempIndex = result.indexOf(destroyTemp);

            assertTrue(tempIndex >= 0, "Should materialize argument temp");
            assertTrue(destroyOldIndex >= 0, "Should destroy old value");
            assertTrue(assignIndex >= 0, "Should assign result");
            assertTrue(destroyTempIndex >= 0, "Should destroy argument temp");
            assertTrue(tempIndex < destroyOldIndex, "Should materialize temp before destroying old value");
            assertTrue(destroyOldIndex < assignIndex, "Should destroy old value before assignment");
            assertTrue(assignIndex < destroyTempIndex, "Should destroy temp after assignment");
        }
    }

    @Nested
    @DisplayName("CallAssign Overload Tests")
    class CallAssignOverloadTests {

        @Test
        @DisplayName("callAssign with explicit void return type should fail")
        void testCallAssignVoidReturnType() {
            var target = new LirVariable("x", GdIntType.INT, lirFunctionDef);
            var targetRef = builder.targetOfVar(target);

            assertThrows(RuntimeException.class, () ->
                    builder.callAssign(targetRef, "some_func", GdVoidType.VOID, List.of())
            );
        }

        @Test
        @DisplayName("callAssign without return type for non-utility should fail")
        void testCallAssignMissingReturnType() {
            var target = new LirVariable("x", GdIntType.INT, lirFunctionDef);
            var targetRef = builder.targetOfVar(target);

            assertThrows(RuntimeException.class, () -> builder.callAssign(targetRef, "some_func", List.of()));
        }
    }

    @Nested
    @DisplayName("Unknown Object Assignment Tests")
    class UnknownObjectAssignmentTests {

        @Test
        @DisplayName("Unknown object assignment should use try_own/try_release")
        void testUnknownObjectAssignmentUsesTry() {
            var target = new LirVariable("obj", new GdObjectType("UnknownType"), lirFunctionDef);
            var source = new LirVariable("src", new GdObjectType("UnknownType"), lirFunctionDef);
            var targetRef = builder.targetOfVar(target);
            var value = builder.valueOfVar(source);

            builder.assignVar(targetRef, value);

            var result = builder.build();
            assertTrue(result.contains("try_release_object($obj)"), "Should try_release unknown object");
            assertTrue(result.contains("try_own_object($obj)"), "Should try_own unknown object");
        }
    }
}
