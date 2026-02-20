package dev.superice.gdcc.backend.c.gen;

import dev.superice.gdcc.backend.CodegenContext;
import dev.superice.gdcc.backend.ProjectInfo;
import dev.superice.gdcc.enums.GodotVersion;
import dev.superice.gdcc.gdextension.ExtensionAPI;
import dev.superice.gdcc.gdextension.ExtensionEnumValue;
import dev.superice.gdcc.gdextension.ExtensionFunctionArgument;
import dev.superice.gdcc.gdextension.ExtensionGlobalEnum;
import dev.superice.gdcc.gdextension.ExtensionGdClass;
import dev.superice.gdcc.gdextension.ExtensionUtilityFunction;
import dev.superice.gdcc.lir.LirBasicBlock;
import dev.superice.gdcc.lir.LirClassDef;
import dev.superice.gdcc.lir.LirFunctionDef;
import dev.superice.gdcc.lir.LirVariable;
import dev.superice.gdcc.lir.insn.ReturnInsn;
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
                List.of(
                        new ExtensionUtilityFunction(
                                "utility_sum",
                                "int",
                                "core",
                                false,
                                0,
                                List.of(
                                        new ExtensionFunctionArgument("a", "int", null, null),
                                        new ExtensionFunctionArgument("b", "int", null, null)
                                )
                        ),
                        new ExtensionUtilityFunction(
                                "utility_with_default",
                                "void",
                                "core",
                                false,
                                0,
                                List.of(
                                        new ExtensionFunctionArgument("required", "int", null, null),
                                        new ExtensionFunctionArgument("optional", "int", "7", null)
                                )
                        )
                ),
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
    @DisplayName("CallVoid Signature Validation Tests")
    class CallVoidSignatureValidationTests {
        @Test
        @DisplayName("callVoid should skip vararg tail only when varargs is null")
        void testCallVoidVarargTailContract() {
            builder.callVoid("utility_sum", List.of(
                    builder.valueOfExpr("1", GdIntType.INT),
                    builder.valueOfExpr("2", GdIntType.INT)
            ), null);
            assertEquals("utility_sum(1, 2);\n", builder.build());

            builder = new CBodyBuilder(builder.helper(), builder.clazz(), lirFunctionDef);
            builder.callVoid("utility_sum", List.of(
                    builder.valueOfExpr("1", GdIntType.INT),
                    builder.valueOfExpr("2", GdIntType.INT)
            ), List.of());
            assertEquals("utility_sum(1, 2, NULL, (godot_int)0);\n", builder.build());
        }
    }

    @Nested
    @DisplayName("jumpIf Tests")
    class JumpIfTests {

        @Test
        @DisplayName("jumpIf should accept bool expression")
        void testJumpIfBoolExpr() {
            builder.jumpIf(builder.valueOfExpr("flag_expr", GdBoolType.BOOL), "bb_true", "bb_false");
            assertEquals("if (flag_expr) goto bb_true;\nelse goto bb_false;\n", builder.build());
        }

        @Test
        @DisplayName("jumpIf should reject non-bool expression")
        void testJumpIfNonBoolExpr() {
            assertThrows(RuntimeException.class, () ->
                    builder.jumpIf(builder.valueOfExpr("123", GdIntType.INT), "bb_true", "bb_false")
            );
        }
    }

    @Nested
    @DisplayName("assignGlobalConst Failure Tests")
    class AssignGlobalConstFailureTests {

        @Test
        @DisplayName("assignGlobalConst should fail for missing enum")
        void testAssignGlobalConstMissingEnum() {
            var target = new LirVariable("x", GdIntType.INT, lirFunctionDef);
            var targetRef = builder.targetOfVar(target);
            assertThrows(RuntimeException.class, () ->
                    builder.assignGlobalConst(targetRef, "MissingEnum", "VALUE_A")
            );
        }

        @Test
        @DisplayName("assignGlobalConst should fail for missing enum value")
        void testAssignGlobalConstMissingEnumValue() {
            var target = new LirVariable("x", GdIntType.INT, lirFunctionDef);
            var targetRef = builder.targetOfVar(target);
            assertThrows(RuntimeException.class, () ->
                    builder.assignGlobalConst(targetRef, "TestEnum", "MISSING")
            );
        }
    }

    @Nested
    @DisplayName("valueOfVar by Name Tests")
    class ValueOfVarByNameTests {

        @Test
        @DisplayName("valueOfVar(name) should resolve existing variable")
        void testValueOfVarByNameSuccess() {
            lirFunctionDef.createAndAddVariable("namedVar", GdIntType.INT);
            var value = builder.valueOfVar("namedVar");
            assertInstanceOf(CBodyBuilder.VarValue.class, value);
            assertEquals("$namedVar", value.generateCode());
        }

        @Test
        @DisplayName("valueOfVar(name) should fail for missing variable")
        void testValueOfVarByNameMissingVariable() {
            assertThrows(RuntimeException.class, () -> builder.valueOfVar("missingVar"));
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

        /// Helper to set up __finally__ block context for return tests.
        private void setFinallyBlockContext(CBodyBuilder bodyBuilder) {
            var finallyBlock = new LirBasicBlock("__finally__");
            bodyBuilder.setCurrentPosition(finallyBlock, 0, new ReturnInsn(null));
        }

        private CBodyBuilder createBuilderWithReturnType(GdType returnType) {
            var funcDef = new LirFunctionDef("returnTestFunc", false, false, false, false, false,
                    Collections.emptyMap(), Collections.emptyList(), Collections.emptyMap(),
                    returnType, Collections.emptyMap(), new LinkedHashMap<>());
            return new CBodyBuilder(builder.helper(), builder.clazz(), funcDef);
        }

        @Test
        @DisplayName("Returning primitive should be direct")
        void testReturnPrimitive() {
            var intBuilder = createBuilderWithReturnType(GdIntType.INT);
            setFinallyBlockContext(intBuilder);
            var intVar = new LirVariable("i", GdIntType.INT, intBuilder.func());
            var value = intBuilder.valueOfVar(intVar);

            intBuilder.returnValue(value);

            assertEquals("return $i;\n", intBuilder.build());
        }

        @Test
        @DisplayName("Returning String should copy")
        void testReturnString() {
            var stringBuilder = createBuilderWithReturnType(GdStringType.STRING);
            setFinallyBlockContext(stringBuilder);
            var strVar = new LirVariable("s", GdStringType.STRING, stringBuilder.func());
            var value = stringBuilder.valueOfVar(strVar);

            stringBuilder.returnValue(value);

            var result = stringBuilder.build();
            assertTrue(result.contains("godot_new_String_with_String(&$s)"), "Should copy String on return");
        }

        @Test
        @DisplayName("Returning object should be direct (pointer)")
        void testReturnObject() {
            var objectBuilder = createBuilderWithReturnType(new GdObjectType("Node"));
            setFinallyBlockContext(objectBuilder);
            var objVar = new LirVariable("obj", new GdObjectType("Node"), objectBuilder.func());
            var value = objectBuilder.valueOfVar(objVar);

            objectBuilder.returnValue(value);

            assertEquals("return $obj;\n", objectBuilder.build());
        }

        @Test
        @DisplayName("Returning String expression should destroy temp after copy")
        void testReturnStringExprTempOrder() {
            var stringBuilder = createBuilderWithReturnType(GdStringType.STRING);
            setFinallyBlockContext(stringBuilder);
            var value = stringBuilder.valueOfExpr("some_string_expr", GdStringType.STRING);

            stringBuilder.returnValue(value);

            var result = stringBuilder.build();
            var tempDecl = "godot_String __gdcc_tmp_string_0 = some_string_expr;";
            var retTempDecl = "godot_String __gdcc_tmp_ret_1;";
            var retTempAssign = "__gdcc_tmp_ret_1 = godot_new_String_with_String(&__gdcc_tmp_string_0);";
            var destroyTemp = "godot_String_destroy(&__gdcc_tmp_string_0);";
            var retLine = "return __gdcc_tmp_ret_1;";

            var tempIndex = result.indexOf(tempDecl);
            var retTempIndex = result.indexOf(retTempDecl);
            var retTempAssignIndex = result.indexOf(retTempAssign);
            var destroyTempIndex = result.indexOf(destroyTemp);
            var retIndex = result.indexOf(retLine);

            assertTrue(tempIndex >= 0, "Should materialize expression temp");
            assertTrue(retTempIndex >= 0, "Should declare return temp");
            assertTrue(retTempAssignIndex >= 0, "Should copy into return temp");
            assertTrue(destroyTempIndex >= 0, "Should destroy expression temp");
            assertTrue(retIndex >= 0, "Should return temp");
            assertTrue(tempIndex < retTempIndex, "Should create expression temp before copy");
            assertTrue(retTempIndex < retTempAssignIndex, "Should assign return temp after declaration");
            assertTrue(retTempAssignIndex < destroyTempIndex, "Should destroy expression temp after copy");
            assertTrue(destroyTempIndex < retIndex, "Should destroy expression temp before return");
        }

        @Test
        @DisplayName("Returning value from void function should fail")
        void testReturnValueFromVoidFunctionFails() {
            var value = builder.valueOfExpr("1", GdIntType.INT);
            assertThrows(RuntimeException.class, () -> builder.returnValue(value));
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
        @DisplayName("callAssign should support discarding non-void return")
        void testCallAssignDiscardReturn() {
            builder.callAssign(builder.discardRef(), "some_func", GdIntType.INT, List.of());
            assertEquals("some_func();\n", builder.build());
        }

        @Test
        @DisplayName("callAssign discard should reject void return type")
        void testCallAssignDiscardVoidReturnType() {
            assertThrows(RuntimeException.class, () ->
                    builder.callAssign(builder.discardRef(), "some_func", GdVoidType.VOID, List.of())
            );
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

    @Nested
    @DisplayName("PtrKind Resolution Tests")
    class PtrKindResolutionTests {

        @Test
        @DisplayName("GDCC object variable should have GDCC_PTR kind")
        void testGdccObjectVarPtrKind() {
            var gdccVar = new LirVariable("myObj", new GdObjectType("MyGdccClass"), lirFunctionDef);
            var value = builder.valueOfVar(gdccVar);
            assertEquals(CBodyBuilder.PtrKind.GDCC_PTR, value.ptrKind());
        }

        @Test
        @DisplayName("Engine object variable should have GODOT_PTR kind")
        void testEngineObjectVarPtrKind() {
            var nodeVar = new LirVariable("node", new GdObjectType("Node"), lirFunctionDef);
            var value = builder.valueOfVar(nodeVar);
            assertEquals(CBodyBuilder.PtrKind.GODOT_PTR, value.ptrKind());
        }

        @Test
        @DisplayName("Primitive variable should have NON_OBJECT kind")
        void testPrimitiveVarPtrKind() {
            var intVar = new LirVariable("i", GdIntType.INT, lirFunctionDef);
            var value = builder.valueOfVar(intVar);
            assertEquals(CBodyBuilder.PtrKind.NON_OBJECT, value.ptrKind());
        }

        @Test
        @DisplayName("Expression with explicit PtrKind should use provided kind")
        void testExprExplicitPtrKind() {
            var value = builder.valueOfExpr("some_ptr", new GdObjectType("MyGdccClass"), CBodyBuilder.PtrKind.GODOT_PTR);
            assertEquals(CBodyBuilder.PtrKind.GODOT_PTR, value.ptrKind());
        }

        @Test
        @DisplayName("Expression PtrKind should be auto-resolved from type by default")
        void testExprAutoResolvedPtrKind() {
            var value = builder.valueOfExpr("some_ptr", new GdObjectType("MyGdccClass"));
            assertEquals(CBodyBuilder.PtrKind.GDCC_PTR, value.ptrKind());
        }
    }

    @Nested
    @DisplayName("GDCC Object Argument Conversion Tests")
    class GdccObjectArgConversionTests {

        @Test
        @DisplayName("GDCC object arg should be converted to ->_object when calling godot_ function")
        void testGdccObjectArgConvertedForGodotFunc() {
            var gdccVar = new LirVariable("myObj", new GdObjectType("MyGdccClass"), lirFunctionDef);
            var value = builder.valueOfVar(gdccVar);

            builder.callVoid("godot_some_func", List.of(value));

            assertEquals("godot_some_func($myObj->_object);\n", builder.build());
        }

        @Test
        @DisplayName("Engine object arg should NOT be converted when calling godot_ function")
        void testEngineObjectArgNotConvertedForGodotFunc() {
            var nodeVar = new LirVariable("node", new GdObjectType("Node"), lirFunctionDef);
            var value = builder.valueOfVar(nodeVar);

            builder.callVoid("godot_Node_do_thing", List.of(value));

            assertEquals("godot_Node_do_thing($node);\n", builder.build());
        }

        @Test
        @DisplayName("GDCC object arg should NOT be converted when calling non-godot function")
        void testGdccObjectArgNotConvertedForNonGodotFunc() {
            var gdccVar = new LirVariable("myObj", new GdObjectType("MyGdccClass"), lirFunctionDef);
            var value = builder.valueOfVar(gdccVar);

            builder.callVoid("my_custom_func", List.of(value));

            assertEquals("my_custom_func($myObj);\n", builder.build());
        }

        @Test
        @DisplayName("GDCC object arg should be converted for own_object function")
        void testGdccObjectArgConvertedForOwnObject() {
            var gdccVar = new LirVariable("myObj", new GdObjectType("MyGdccClass"), lirFunctionDef);
            var value = builder.valueOfVar(gdccVar);

            builder.callVoid("try_own_object", List.of(value));

            assertEquals("try_own_object($myObj->_object);\n", builder.build());
        }

        @Test
        @DisplayName("Mixed args: GDCC object and String in godot_ call")
        void testMixedArgsGdccAndString() {
            var gdccVar = new LirVariable("myObj", new GdObjectType("MyGdccClass"), lirFunctionDef);
            var strVar = new LirVariable("name", GdStringType.STRING, lirFunctionDef);

            builder.callVoid("godot_Object_set", List.of(
                    builder.valueOfVar(gdccVar),
                    builder.valueOfVar(strVar)
            ));

            assertEquals("godot_Object_set($myObj->_object, &$name);\n", builder.build());
        }

        @Test
        @DisplayName("GDCC object arg should stay GDCC ptr for godot_new_Variant_with_gdcc_Object")
        void testGdccObjectArgNotConvertedForGdccVariantPackFunc() {
            var gdccVar = new LirVariable("myObj", new GdObjectType("MyGdccClass"), lirFunctionDef);

            builder.callVoid("godot_new_Variant_with_gdcc_Object", List.of(builder.valueOfVar(gdccVar)));

            assertEquals("godot_new_Variant_with_gdcc_Object($myObj);\n", builder.build());
        }
    }

    @Nested
    @DisplayName("GDCC Object Return Conversion Tests")
    class GdccObjectReturnConversionTests {

        @Test
        @DisplayName("callAssign should wrap godot_ return with fromGodotObjectPtr for GDCC target")
        void testCallAssignGdccTargetFromGodotFunc() {
            var target = new LirVariable("myObj", new GdObjectType("MyGdccClass"), lirFunctionDef);
            var targetRef = builder.targetOfVar(target);

            builder.callAssign(targetRef, "godot_get_something", new GdObjectType("MyGdccClass"), List.of());

            var result = builder.build();
            assertTrue(result.contains("(MyGdccClass*)gdcc_object_from_godot_object_ptr(godot_get_something())"),
                    "Should wrap godot_ return with fromGodotObjectPtr for GDCC target. Actual:\n" + result);
        }

        @Test
        @DisplayName("callAssign should NOT wrap for engine target from godot_ function")
        void testCallAssignEngineTargetFromGodotFuncNoWrap() {
            var target = new LirVariable("node", new GdObjectType("Node"), lirFunctionDef);
            var targetRef = builder.targetOfVar(target);

            builder.callAssign(targetRef, "godot_get_node", new GdObjectType("Node"), List.of());

            var result = builder.build();
            assertFalse(result.contains("gdcc_object_from_godot_object_ptr"),
                    "Should NOT wrap for engine target. Actual:\n" + result);
            assertTrue(result.contains("$node = godot_get_node()"),
                    "Should assign directly. Actual:\n" + result);
        }

        @Test
        @DisplayName("callAssign should NOT wrap for non-godot function returning GDCC object")
        void testCallAssignNonGodotFuncNoWrap() {
            var target = new LirVariable("myObj", new GdObjectType("MyGdccClass"), lirFunctionDef);
            var targetRef = builder.targetOfVar(target);

            builder.callAssign(targetRef, "my_create_func", new GdObjectType("MyGdccClass"), List.of());

            var result = builder.build();
            assertFalse(result.contains("gdcc_object_from_godot_object_ptr"),
                    "Should NOT wrap for non-godot function. Actual:\n" + result);
            assertTrue(result.contains("$myObj = my_create_func()"),
                    "Should assign directly. Actual:\n" + result);
        }

        @Test
        @DisplayName("callAssign with GDCC target from godot_ func should still do own/release")
        void testCallAssignGdccTargetOwnRelease() {
            var target = new LirVariable("myObj", new GdObjectType("MyGdccClass"), lirFunctionDef);
            var targetRef = builder.targetOfVar(target);

            builder.callAssign(targetRef, "godot_get_something", new GdObjectType("MyGdccClass"), List.of());

            var result = builder.build();
            // MyGdccClass extends RefCounted, should use release_object and own_object
            assertTrue(result.contains("release_object($myObj->_object)"),
                    "Should release old GDCC object. Actual:\n" + result);
            assertTrue(result.contains("own_object($myObj->_object)"),
                    "Should own new GDCC object. Actual:\n" + result);
        }

        @Test
        @DisplayName("callAssign with GDCC args and GDCC return from godot_ function")
        void testCallAssignFullGdccConversion() {
            var target = new LirVariable("result", new GdObjectType("MyGdccClass"), lirFunctionDef);
            var targetRef = builder.targetOfVar(target);
            var arg = new LirVariable("input", new GdObjectType("MyGdccClass"), lirFunctionDef);

            builder.callAssign(targetRef, "godot_transform", new GdObjectType("MyGdccClass"),
                    List.of(builder.valueOfVar(arg)));

            var result = builder.build();
            // Arg should be converted
            assertTrue(result.contains("$input->_object"),
                    "Should convert GDCC arg to godot ptr. Actual:\n" + result);
            // Return should be wrapped
            assertTrue(result.contains("gdcc_object_from_godot_object_ptr"),
                    "Should wrap return with fromGodotObjectPtr. Actual:\n" + result);
        }
    }

    @Nested
    @DisplayName("AssignVar Pointer Conversion Tests")
    class AssignVarPtrConversionTests {

        @Test
        @DisplayName("GODOT_PTR value assigned to GDCC target should wrap with fromGodotObjectPtr")
        void testGodotPtrValueToGdccTarget() {
            var target = new LirVariable("myObj", new GdObjectType("MyGdccClass"), lirFunctionDef);
            var targetRef = builder.targetOfVar(target);
            // Expression with explicit GODOT_PTR: simulates a GDExtension API return value
            var value = builder.valueOfExpr("some_godot_api_result", new GdObjectType("MyGdccClass"), CBodyBuilder.PtrKind.GODOT_PTR);

            builder.assignVar(targetRef, value);

            var result = builder.build();
            assertTrue(result.contains("(MyGdccClass*)gdcc_object_from_godot_object_ptr(some_godot_api_result)"),
                    "Should convert GODOT_PTR to GDCC_PTR via fromGodotObjectPtr. Actual:\n" + result);
        }

        @Test
        @DisplayName("GDCC_PTR value assigned to engine (GODOT_PTR) target should use ->_object")
        void testGdccPtrValueToEngineTarget() {
            // Target is Object (engine base type), so its PtrKind is GODOT_PTR
            // MyGdccClass extends RefCounted extends Object, so assignment is valid
            var target = new LirVariable("obj", GdObjectType.OBJECT, lirFunctionDef);
            var targetRef = builder.targetOfVar(target);
            // Source is a GDCC variable, PtrKind is GDCC_PTR
            var source = new LirVariable("myObj", new GdObjectType("MyGdccClass"), lirFunctionDef);
            var value = builder.valueOfVar(source);

            builder.assignVar(targetRef, value);

            var result = builder.build();
            assertTrue(result.contains("$obj = $myObj->_object"),
                    "Should convert GDCC_PTR to GODOT_PTR via ->_object. Actual:\n" + result);
        }

        @Test
        @DisplayName("Same PtrKind (GDCC to GDCC) should NOT convert")
        void testSamePtrKindGdccNoConversion() {
            var target = new LirVariable("target", new GdObjectType("MyGdccClass"), lirFunctionDef);
            var source = new LirVariable("source", new GdObjectType("MyGdccClass"), lirFunctionDef);
            var targetRef = builder.targetOfVar(target);
            var value = builder.valueOfVar(source);

            builder.assignVar(targetRef, value);

            var result = builder.build();
            assertTrue(result.contains("$target = $source;"),
                    "Should assign directly without conversion. Actual:\n" + result);
            assertFalse(result.contains("gdcc_object_from_godot_object_ptr"),
                    "Should NOT wrap with fromGodotObjectPtr. Actual:\n" + result);
            assertFalse(result.contains("$source->_object;"),
                    "Should NOT use ->_object on RHS. Actual:\n" + result);
        }

        @Test
        @DisplayName("Same PtrKind (engine to engine) should NOT convert")
        void testSamePtrKindEngineNoConversion() {
            var target = new LirVariable("target", new GdObjectType("Node"), lirFunctionDef);
            var source = new LirVariable("source", new GdObjectType("Node"), lirFunctionDef);
            var targetRef = builder.targetOfVar(target);
            var value = builder.valueOfVar(source);

            builder.assignVar(targetRef, value);

            var result = builder.build();
            assertTrue(result.contains("$target = $source;"),
                    "Should assign directly without conversion. Actual:\n" + result);
            assertFalse(result.contains("->_object"),
                    "Should NOT use ->_object. Actual:\n" + result);
        }

        @Test
        @DisplayName("GODOT_PTR to GDCC target should still do own/release with ->_object")
        void testGodotPtrToGdccTargetOwnRelease() {
            var target = new LirVariable("myObj", new GdObjectType("MyGdccClass"), lirFunctionDef);
            var targetRef = builder.targetOfVar(target);
            var value = builder.valueOfExpr("some_godot_result", new GdObjectType("MyGdccClass"), CBodyBuilder.PtrKind.GODOT_PTR);

            builder.assignVar(targetRef, value);

            var result = builder.build();
            // MyGdccClass extends RefCounted, should have own/release with ->_object
            assertTrue(result.contains("release_object($myObj->_object)"),
                    "Should release old GDCC object via ->_object. Actual:\n" + result);
            assertTrue(result.contains("own_object($myObj->_object)"),
                    "Should own new GDCC object via ->_object. Actual:\n" + result);
            // Should also convert the assignment value
            assertTrue(result.contains("gdcc_object_from_godot_object_ptr(some_godot_result)"),
                    "Should convert GODOT_PTR to GDCC_PTR. Actual:\n" + result);
        }

        @Test
        @DisplayName("assignExpr with explicit PtrKind should convert GODOT_PTR to GDCC")
        void testAssignExprWithPtrKindConversion() {
            var target = new LirVariable("myObj", new GdObjectType("MyGdccClass"), lirFunctionDef);
            var targetRef = builder.targetOfVar(target);

            builder.assignExpr(targetRef, "godot_api_call()", new GdObjectType("MyGdccClass"), CBodyBuilder.PtrKind.GODOT_PTR);

            var result = builder.build();
            assertTrue(result.contains("(MyGdccClass*)gdcc_object_from_godot_object_ptr(godot_api_call())"),
                    "assignExpr with GODOT_PTR should convert to GDCC_PTR. Actual:\n" + result);
        }

        @Test
        @DisplayName("assignExpr without PtrKind should auto-resolve and NOT convert (GDCC type → GDCC_PTR)")
        void testAssignExprAutoResolvedNoPtrConversion() {
            var target = new LirVariable("myObj", new GdObjectType("MyGdccClass"), lirFunctionDef);
            var targetRef = builder.targetOfVar(target);

            // Without explicit PtrKind, auto-resolved from GdObjectType("MyGdccClass") → GDCC_PTR
            builder.assignExpr(targetRef, "some_gdcc_ptr", new GdObjectType("MyGdccClass"));

            var result = builder.build();
            assertTrue(result.contains("$myObj = some_gdcc_ptr;"),
                    "Should assign directly when PtrKinds match. Actual:\n" + result);
            assertFalse(result.contains("gdcc_object_from_godot_object_ptr"),
                    "Should NOT convert when PtrKinds match. Actual:\n" + result);
        }

        @Test
        @DisplayName("GDCC_PTR to RefCounted (engine base class) target should use ->_object")
        void testGdccPtrToRefCountedTarget() {
            // RefCounted is an engine type (GODOT_PTR)
            var target = new LirVariable("rc", new GdObjectType("RefCounted"), lirFunctionDef);
            var targetRef = builder.targetOfVar(target);
            // MyGdccClass extends RefCounted, so assignment is valid
            var source = new LirVariable("myObj", new GdObjectType("MyGdccClass"), lirFunctionDef);
            var value = builder.valueOfVar(source);

            builder.assignVar(targetRef, value);

            var result = builder.build();
            assertTrue(result.contains("$rc = $myObj->_object"),
                    "Should convert GDCC_PTR to GODOT_PTR via ->_object. Actual:\n" + result);
        }

        @Test
        @DisplayName("GODOT_PTR to GDCC target full ordering: release → assign with conversion → own")
        void testGodotPtrToGdccTargetFullOrdering() {
            var target = new LirVariable("myObj", new GdObjectType("MyGdccClass"), lirFunctionDef);
            var targetRef = builder.targetOfVar(target);
            var value = builder.valueOfExpr("godot_result", new GdObjectType("MyGdccClass"), CBodyBuilder.PtrKind.GODOT_PTR);

            builder.assignVar(targetRef, value);

            var result = builder.build();
            var releaseIndex = result.indexOf("release_object($myObj->_object)");
            var assignIndex = result.indexOf("$myObj = (MyGdccClass*)gdcc_object_from_godot_object_ptr(godot_result);");
            var ownIndex = result.indexOf("own_object($myObj->_object)");

            assertTrue(releaseIndex >= 0, "Should have release. Actual:\n" + result);
            assertTrue(assignIndex >= 0, "Should have converted assignment. Actual:\n" + result);
            assertTrue(ownIndex >= 0, "Should have own. Actual:\n" + result);
            assertTrue(releaseIndex < assignIndex, "Release should come before assignment");
            assertTrue(assignIndex < ownIndex, "Assignment should come before own");
        }
    }
}
