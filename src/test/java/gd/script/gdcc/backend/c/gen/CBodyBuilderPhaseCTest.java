package gd.script.gdcc.backend.c.gen;

import gd.script.gdcc.backend.CodegenContext;
import gd.script.gdcc.backend.ProjectInfo;
import gd.script.gdcc.enums.GodotVersion;
import gd.script.gdcc.exception.InvalidInsnException;
import gd.script.gdcc.gdextension.ExtensionAPI;
import gd.script.gdcc.gdextension.ExtensionEnumValue;
import gd.script.gdcc.gdextension.ExtensionFunctionArgument;
import gd.script.gdcc.gdextension.ExtensionGlobalConstant;
import gd.script.gdcc.gdextension.ExtensionGlobalEnum;
import gd.script.gdcc.gdextension.ExtensionGdClass;
import gd.script.gdcc.gdextension.ExtensionUtilityFunction;
import gd.script.gdcc.lir.LirBasicBlock;
import gd.script.gdcc.lir.LirClassDef;
import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.lir.LirParameterDef;
import gd.script.gdcc.lir.LirVariable;
import gd.script.gdcc.lir.insn.NopInsn;
import gd.script.gdcc.lir.insn.ReturnInsn;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.*;
import gd.script.gdcc.type.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/// Tests for CBodyBuilder semantic core behavior.
///
/// Coverage focuses on:
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
                List.of(new ExtensionGlobalConstant("GDCC_TEST_BIG_FLAG", 4_294_967_296L, true)),
                List.of(new ExtensionGlobalEnum(
                        "TestEnum",
                        false,
                        List.of(
                                new ExtensionEnumValue("VALUE_A", 42),
                                new ExtensionEnumValue("VALUE_WIDE", 34_359_738_368L)
                        )
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
        @DisplayName("Compiler-only iterator storage should be passed by reference (&)")
        void testCompilerOnlyArgumentWithAddressOf() {
            var iterVar = new LirVariable("iter", GdccForRangeIterType.FOR_RANGE_ITER, lirFunctionDef);
            var value = builder.valueOfVar(iterVar);

            builder.callVoid("func", List.of(value));

            assertEquals("func(&$iter);\n", builder.build());
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

        @Test
        @DisplayName("assignGlobalConstant should fail for missing constant")
        void testAssignGlobalConstantMissingValue() {
            var target = new LirVariable("x", GdIntType.INT, lirFunctionDef);
            var targetRef = builder.targetOfVar(target);
            assertThrows(RuntimeException.class, () ->
                    builder.assignGlobalConstant(targetRef, "MISSING")
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
        @DisplayName("assignGlobalConst should preserve int64 enum literals")
        void testAssignGlobalConstWithInt64Value() {
            var target = new LirVariable("x", GdIntType.INT, lirFunctionDef);
            var targetRef = builder.targetOfVar(target);

            builder.assignGlobalConst(targetRef, "TestEnum", "VALUE_WIDE");

            assertEquals("$x = 34359738368;\n", builder.build());
        }

        @Test
        @DisplayName("assignGlobalConstant should resolve top-level global constant")
        void testAssignGlobalConstant() {
            var target = new LirVariable("x", GdIntType.INT, lirFunctionDef);
            var targetRef = builder.targetOfVar(target);

            builder.assignGlobalConstant(targetRef, "GDCC_TEST_BIG_FLAG");

            assertEquals("$x = 4294967296;\n", builder.build());
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
        @DisplayName("Typed Array assignment should use normalized copy symbol and safe temp prefix")
        void testTypedArrayAssignmentUsesNormalizedSymbolAndSafeTempPrefix() {
            var arrayType = new GdArrayType(GdStringNameType.STRING_NAME);
            var target = new LirVariable("arr", arrayType, lirFunctionDef);
            var source = new LirVariable("src", arrayType, lirFunctionDef);

            builder.assignVar(builder.targetOfVar(target), builder.valueOfVar(source));

            var result = builder.build();
            assertTrue(result.contains("godot_new_Array_with_Array(&$src);"),
                    "Typed Array copy should use normalized Array constructor symbol");
            assertTrue(result.contains("$arr = godot_new_Array_with_Array(&$src);"),
                    "Typed Array assignment should write the copied rhs directly into the target slot");
            assertFalse(result.contains("__gdcc_tmp_array_"),
                    "Typed Array assignment should no longer materialize a copy temp");
            assertFalse(result.contains("__gdcc_tmp_array["),
                    "Temp variable name must not contain generic suffix characters");
        }

        @Test
        @DisplayName("__prepare__ non-object assignment should not destroy old value")
        void testPrepareBlockNonObjectAssignSkipsDestroy() {
            var prepareBlock = new LirBasicBlock("__prepare__");
            builder.beginBasicBlock("__prepare__");
            builder.setCurrentPosition(prepareBlock, 0, new NopInsn());
            var target = new LirVariable("s", GdStringType.STRING, lirFunctionDef);
            var source = new LirVariable("src", GdStringType.STRING, lirFunctionDef);

            builder.assignVar(builder.targetOfVar(target), builder.valueOfVar(source));

            var result = builder.build();
            assertFalse(result.contains("godot_String_destroy(&$s)"),
                    "__prepare__ first-write semantics should skip old value destroy");
            assertTrue(result.contains("$s = godot_new_String_with_String(&$src);"),
                    "Should still assign copied rhs value directly into the target slot");
            assertFalse(result.contains("__gdcc_tmp_string_"),
                    "Prepare-block assignment should not materialize a copy temp");
        }

        @Test
        @DisplayName("__prepare__ compiler-only assignment should keep first-write direct assignment without destroy")
        void testPrepareBlockCompilerOnlyAssignSkipsDestroyAndCopyHelper() {
            var prepareBlock = new LirBasicBlock("__prepare__");
            builder.beginBasicBlock("__prepare__");
            builder.setCurrentPosition(prepareBlock, 0, new NopInsn());
            var target = new LirVariable("iter", GdccForRangeIterType.FOR_RANGE_ITER, lirFunctionDef);
            var source = new LirVariable("src", GdccForRangeIterType.FOR_RANGE_ITER, lirFunctionDef);

            builder.assignVar(builder.targetOfVar(target), builder.valueOfVar(source));

            var result = builder.build();
            assertTrue(result.contains("$iter = $src;"), result);
            assertFalse(result.contains("gdcc_for_range_iter_destroy(&$iter);"), result);
            assertFalse(result.contains("godot_new_GdccForRangeIter_with_GdccForRangeIter"), result);
            assertFalse(result.contains("__gdcc_tmp_"), result);
        }

        @Test
        @DisplayName("self compiler-only assignment should stay on direct assignment path without stable carrier")
        void testSelfCompilerOnlyAssignmentStaysDirect() {
            var target = new LirVariable("iter", GdccForRangeIterType.FOR_RANGE_ITER, lirFunctionDef);

            builder.assignVar(builder.targetOfVar(target), builder.valueOfVar(target));

            var result = builder.build();
            assertTrue(result.contains("gdcc_for_range_iter_destroy(&$iter);"), result);
            assertTrue(result.contains("$iter = $iter;"), result);
            assertFalse(result.contains("__gdcc_tmp_gdccforrangeiter"), result);
            assertFalse(result.contains("godot_new_GdccForRangeIter_with_GdccForRangeIter"), result);
        }

        @Test
        @DisplayName("RefCounted object assignment should capture old fat ptr, own new, then release captured old")
        void testRefCountedObjectAssignment() {
            var target = new LirVariable("obj", new GdObjectType("RefCounted"), lirFunctionDef);
            var source = new LirVariable("src", new GdObjectType("RefCounted"), lirFunctionDef);
            var targetRef = builder.targetOfVar(target);
            var value = builder.valueOfVar(source);

            builder.assignVar(targetRef, value);

            var result = builder.build();
            // Fat pointer storage: capture old fat ptr, struct-assign same type, retain/release via live_object.
            assertTrue(result.contains("gdcc_RefCounted_fat_ptr __gdcc_tmp_old_obj_0 = $obj;"),
                    "Should capture old RefCounted fat pointer. Actual:\n" + result);
            assertTrue(result.contains("$obj = $src;"), "Same-type FAT_PTR storage should struct-assign directly. Actual:\n" + result);
            assertTrue(result.contains("own_object(gdcc_RefCounted_fat_ptr_live_object($obj));"),
                    "Should own new RefCounted object via live_object. Actual:\n" + result);
            assertTrue(result.contains("release_object(gdcc_RefCounted_fat_ptr_live_object(__gdcc_tmp_old_obj_0));"),
                    "Should release captured old RefCounted object via live_object. Actual:\n" + result);
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
        @DisplayName("GDCC object assignment should struct-assign same-type fat ptr and own/release via live_object")
        void testGdccObjectAssignment() {
            var target = new LirVariable("myObj", new GdObjectType("MyGdccClass"), lirFunctionDef);
            var source = new LirVariable("src", new GdObjectType("MyGdccClass"), lirFunctionDef);
            var targetRef = builder.targetOfVar(target);
            var value = builder.valueOfVar(source);

            builder.assignVar(targetRef, value);

            var result = builder.build();
            // Same-type FAT_PTR storage uses plain struct assign with live_object for own/release.
            assertTrue(result.contains("gdcc_MyGdccClass_fat_ptr __gdcc_tmp_old_obj_0 = $myObj;"),
                    "Should capture old GDCC fat pointer. Actual:\n" + result);
            assertTrue(result.contains("$myObj = $src;"),
                    "Same-type GDCC FAT_PTR should struct-assign directly. Actual:\n" + result);
            assertTrue(result.contains("own_object(gdcc_MyGdccClass_fat_ptr_live_object($myObj));"),
                    "Should own GDCC object via live_object. Actual:\n" + result);
            assertTrue(result.contains("release_object(gdcc_MyGdccClass_fat_ptr_live_object(__gdcc_tmp_old_obj_0));"),
                    "Should release captured old GDCC object via live_object. Actual:\n" + result);
        }

        @Test
        @DisplayName("Self String assignment should stage stable carrier before destroy and consume it into target")
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
            assertEquals(-1, destroyTempIndex, "Consumed stable carrier must not be destroyed afterwards");
            assertTrue(tempIndex < destroyOldIndex, "Should copy before destroying old value");
            assertTrue(destroyOldIndex < assignIndex, "Should destroy old value before assignment");
        }

        @Test
        @DisplayName("Self Variant assignment should stage stable carrier before destroy and consume it into target")
        void testSelfVariantAssignmentOrder() {
            var target = new LirVariable("payload", GdVariantType.VARIANT, lirFunctionDef);
            var targetRef = builder.targetOfVar(target);
            var value = builder.valueOfVar(target);

            builder.assignVar(targetRef, value);

            var result = builder.build();
            var tempDecl = "godot_Variant __gdcc_tmp_variant_0 = godot_new_Variant_with_Variant(&$payload);";
            var destroyOld = "godot_Variant_destroy(&$payload);";
            var assign = "$payload = __gdcc_tmp_variant_0;";
            var destroyTemp = "godot_Variant_destroy(&__gdcc_tmp_variant_0);";

            var tempIndex = result.indexOf(tempDecl);
            var destroyOldIndex = result.indexOf(destroyOld);
            var assignIndex = result.indexOf(assign);
            var destroyTempIndex = result.indexOf(destroyTemp);

            assertTrue(tempIndex >= 0, "Should materialize copy temp");
            assertTrue(destroyOldIndex >= 0, "Should destroy old value");
            assertTrue(assignIndex >= 0, "Should assign new value");
            assertEquals(-1, destroyTempIndex, "Consumed stable carrier must not be destroyed afterwards");
            assertTrue(tempIndex < destroyOldIndex, "Should copy before destroying old value");
            assertTrue(destroyOldIndex < assignIndex, "Should destroy old value before assignment");
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
        @DisplayName("Returning typed Dictionary expression should use normalized copy symbol and safe temp prefix")
        void testReturnTypedDictionaryExprUsesNormalizedSymbolAndSafeTempPrefix() {
            var dictionaryType = new GdDictionaryType(GdStringNameType.STRING_NAME, GdVariantType.VARIANT);
            var dictionaryBuilder = createBuilderWithReturnType(dictionaryType);
            setFinallyBlockContext(dictionaryBuilder);
            var value = dictionaryBuilder.valueOfExpr("some_dict_expr", dictionaryType);

            dictionaryBuilder.returnValue(value);

            var result = dictionaryBuilder.build();
            assertTrue(result.contains("godot_new_Dictionary_with_Dictionary(&__gdcc_tmp_dictionary_0);"),
                    "Typed Dictionary return copy should use normalized Dictionary symbol");
            assertTrue(result.contains("__gdcc_tmp_dictionary_0"),
                    "Typed Dictionary expression temp should use safe normalized prefix");
            assertFalse(result.contains("__gdcc_tmp_dictionary["),
                    "Expression temp name must not contain generic suffix characters");
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
        @DisplayName("Returning compiler-only value should stay on explicit direct assignment contract")
        void testReturnCompilerOnlyDirect() {
            var iterBuilder = createBuilderWithReturnType(GdccForRangeIterType.FOR_RANGE_ITER);
            setFinallyBlockContext(iterBuilder);
            var iterVar = new LirVariable("iter", GdccForRangeIterType.FOR_RANGE_ITER, iterBuilder.func());

            iterBuilder.returnValue(iterBuilder.valueOfVar(iterVar));

            var result = iterBuilder.build();
            assertEquals("return $iter;\n", result);
            assertFalse(result.contains("godot_new_GdccForRangeIter_with_GdccForRangeIter"), result);
            assertFalse(result.contains("__gdcc_tmp_"), result);
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
        @DisplayName("Returning owning local RefCounted outside finally should move into return slot without extra own")
        void testReturnLocalObjectOutsideFinallyMovesIntoReturnSlot() {
            var objectBuilder = createBuilderWithReturnType(new GdObjectType("RefCounted"));
            objectBuilder.beginBasicBlock("__prepare__");
            var objVar = new LirVariable("obj", new GdObjectType("RefCounted"), objectBuilder.func());

            objectBuilder.returnValue(objectBuilder.valueOfVar(objVar));

            var result = objectBuilder.build();
            assertTrue(result.contains("gdcc_RefCounted_fat_ptr _return_val = (gdcc_RefCounted_fat_ptr){ 0 };"),
                    "Prepare block should init return slot with fat pointer zero literal. Actual:\n" + result);
            assertTrue(result.contains("release_object(gdcc_RefCounted_fat_ptr_live_object(__gdcc_tmp_old_obj_0));"),
                    "Writing return slot should release captured old fat pointer via live_object. Actual:\n" + result);
            assertTrue(result.contains("_return_val = $obj;"), "Should write returned object into slot. Actual:\n" + result);
            assertTrue(result.contains("$obj = (gdcc_RefCounted_fat_ptr){ 0 };"),
                    "Moved local object slot should be zeroed so __finally__ does not release it again. Actual:\n" + result);
            assertFalse(result.contains("own_object(_return_val);"), "Owning local object should move into return slot without extra own");
            assertTrue(result.contains("goto __finally__;"), "Non-finally return should jump to __finally__");
        }

        @Test
        @DisplayName("Returning owned object outside finally should consume ownership without own")
        void testReturnOwnedObjectOutsideFinallyConsumesOwnership() {
            var objectBuilder = createBuilderWithReturnType(new GdObjectType("RefCounted"));
            objectBuilder.beginBasicBlock("__prepare__");
            var ownedValue = objectBuilder.valueOfOwnedExpr(
                    "create_object()",
                    new GdObjectType("RefCounted"),
                    CBodyBuilder.PtrKind.RAW_PRODUCER
            );

            objectBuilder.returnValue(ownedValue);

            var result = objectBuilder.build();
            assertTrue(result.contains("release_object(gdcc_RefCounted_fat_ptr_live_object(__gdcc_tmp_old_obj_0));"),
                    "Should still release previous return slot value via live_object. Actual:\n" + result);
            assertTrue(result.contains("_return_val = gdcc_RefCounted_fat_ptr_from_raw((GDExtensionObjectPtr)(create_object()));"),
                    "Should convert owned raw producer before publishing into return slot. Actual:\n" + result);
            assertFalse(result.contains("own_object(_return_val);"), "Owned return value must not be owned again");
            assertTrue(result.contains("goto __finally__;"), "Non-finally return should jump to __finally__");
        }

        @Test
        @DisplayName("Returning owned RAW_PRODUCER for GDCC return type should convert representation without own")
        void testReturnOwnedGodotPtrToGdccReturnTypeDoesNotOwnAgain() {
            var objectBuilder = createBuilderWithReturnType(new GdObjectType("MyGdccClass"));
            objectBuilder.beginBasicBlock("__prepare__");
            var ownedValue = objectBuilder.valueOfOwnedExpr(
                    "godot_make_worker()",
                    new GdObjectType("MyGdccClass"),
                    CBodyBuilder.PtrKind.RAW_PRODUCER
            );

            objectBuilder.returnValue(ownedValue);

            var result = objectBuilder.build();
            assertTrue(
                    result.contains("_return_val = gdcc_MyGdccClass_fat_ptr_from_raw((GDExtensionObjectPtr)(godot_make_worker()));"),
                    "OWNED Godot raw ptr return should be captured into GDCC return slot via fat_ptr_from_raw. Actual:\n" + result
            );
            assertFalse(
                    result.contains("own_object(gdcc_MyGdccClass_fat_ptr_live_object(_return_val));"),
                    "OWNED raw producer must not be retained again at the publish boundary. Actual:\n" + result
            );
            assertTrue(
                    result.contains("release_object(gdcc_MyGdccClass_fat_ptr_live_object(__gdcc_tmp_old_obj_0));"),
                    "Should release previous return slot value via live_object. Actual:\n" + result
            );
        }

        @Test
        @DisplayName("Returning borrowed object parameter outside finally should retain return slot and keep source untouched")
        void testReturnBorrowedParameterOutsideFinallyUsesOwn() {
            var objectBuilder = createBuilderWithReturnType(new GdObjectType("RefCounted"));
            objectBuilder.func().addParameter(new LirParameterDef("obj", new GdObjectType("RefCounted"), null, objectBuilder.func()));
            objectBuilder.beginBasicBlock("__prepare__");
            var parameterVar = new LirVariable("obj", new GdObjectType("RefCounted"), objectBuilder.func());

            objectBuilder.returnValue(objectBuilder.valueOfVar(parameterVar));

            var result = objectBuilder.build();
            assertTrue(result.contains("_return_val = $obj;"), "Borrowed parameter should still write into return slot");
            assertTrue(result.contains("own_object(gdcc_RefCounted_fat_ptr_live_object(_return_val));"),
                    "Borrowed parameter should be retained for the caller via live_object. Actual:\n" + result);
            assertFalse(result.contains("$obj = NULL;"), "Borrowed parameter must not be cleared by move logic");
            assertFalse(result.contains("$obj = (gdcc_RefCounted_fat_ptr){ 0 };"),
                    "Borrowed parameter must not be cleared by move logic. Actual:\n" + result);
        }

        @Test
        @DisplayName("Returning borrowed object expression outside finally should retain return slot without move")
        void testReturnBorrowedObjectExpressionOutsideFinallyUsesOwn() {
            var objectBuilder = createBuilderWithReturnType(new GdObjectType("RefCounted"));
            objectBuilder.beginBasicBlock("__prepare__");
            var borrowedValue = objectBuilder.valueOfExpr(
                    "self->cached_resource",
                    new GdObjectType("RefCounted"),
                    CBodyBuilder.PtrKind.RAW_PRODUCER
            );

            objectBuilder.returnValue(borrowedValue);

            var result = objectBuilder.build();
            assertTrue(result.contains("_return_val = gdcc_RefCounted_fat_ptr_from_raw((GDExtensionObjectPtr)(self->cached_resource));"),
                    "Borrowed field/property style return should publish through the return slot by capturing the raw ptr into a fat pointer. Actual:\n" + result);
            assertTrue(result.contains("own_object(gdcc_RefCounted_fat_ptr_live_object(_return_val));"),
                    "Borrowed field/property style return should retain at the publish boundary via live_object. Actual:\n" + result);
            assertFalse(result.contains("cached_resource = NULL;"),
                    "Borrowed expression return must not trigger move-return source clearing");
            assertFalse(result.contains("cached_resource = (gdcc_RefCounted_fat_ptr){ 0 };"),
                    "Borrowed expression return must not trigger move-return source clearing. Actual:\n" + result);
            assertTrue(result.contains("goto __finally__;"), "Non-finally return should jump to __finally__");
        }

        @Test
        @DisplayName("Returning borrowed RAW_PRODUCER for GDCC return type should retain only at publish boundary")
        void testReturnBorrowedGodotPtrToGdccReturnTypeOwnsAtPublishBoundary() {
            var objectBuilder = createBuilderWithReturnType(new GdObjectType("MyGdccClass"));
            objectBuilder.beginBasicBlock("__prepare__");
            var borrowedValue = objectBuilder.valueOfExpr(
                    "self->cached_worker",
                    new GdObjectType("MyGdccClass"),
                    CBodyBuilder.PtrKind.RAW_PRODUCER
            );

            objectBuilder.returnValue(borrowedValue);

            var result = objectBuilder.build();
            assertTrue(
                    result.contains("_return_val = gdcc_MyGdccClass_fat_ptr_from_raw((GDExtensionObjectPtr)(self->cached_worker));"),
                    "Borrowed Godot raw ptr should still be captured into GDCC return slot via fat_ptr_from_raw. Actual:\n" + result
            );
            assertTrue(
                    result.contains("own_object(gdcc_MyGdccClass_fat_ptr_live_object(_return_val));"),
                    "Borrowed return should retain exactly at the publish boundary via live_object. Actual:\n" + result
            );
            assertFalse(
                    result.contains("try_own_object(gdcc_MyGdccClass_fat_ptr_live_object(_return_val));"),
                    "Known RefCounted GDCC return type should use the precise own path. Actual:\n" + result
            );
            assertTrue(
                    result.contains("release_object(gdcc_MyGdccClass_fat_ptr_live_object(__gdcc_tmp_old_obj_0));"),
                    "Should release previous return slot value via live_object. Actual:\n" + result
            );
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
        @DisplayName("Unregistered object target type should fail-fast during fat pointer storage resolution")
        void testUnknownTargetObjectAssignmentFailsFast() {
            // The test registry only registers RefCounted and Node; the bare "Object" engine type is
            // unknown to the fat-pointer spec resolver and must fail-fast.
            var target = new LirVariable("obj", GdObjectType.OBJECT, lirFunctionDef);
            var targetRef = builder.targetOfVar(target);
            var value = builder.valueOfExpr("refCountedPtr", new GdObjectType("RefCounted"));

            var ex = assertThrows(IllegalStateException.class, () -> builder.assignVar(targetRef, value));
            assertTrue(ex.getMessage().contains("Unknown object type 'Object'"),
                    "Should fail-fast on unregistered object target. Actual:\n" + ex.getMessage());
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
        @DisplayName("callAssign with RefCounted target should release captured old and consume owned return")
        void testCallAssignRefCountedTarget() {
            var target = new LirVariable("obj", new GdObjectType("RefCounted"), lirFunctionDef);
            var targetRef = builder.targetOfVar(target);

            builder.callAssign(targetRef, "create_object", new GdObjectType("RefCounted"), List.of());

            var result = builder.build();
            assertTrue(result.contains("gdcc_RefCounted_fat_ptr __gdcc_tmp_old_obj_0 = $obj;"),
                    "Should capture captured old object as fat pointer. Actual:\n" + result);
            assertTrue(result.contains("release_object(gdcc_RefCounted_fat_ptr_live_object(__gdcc_tmp_old_obj_0));"),
                    "Should release captured old object via live_object. Actual:\n" + result);
            assertFalse(result.contains("own_object(gdcc_RefCounted_fat_ptr_live_object($obj));"),
                    "Owned call result should not be owned again");
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
        @DisplayName("callAssign discard of String return should destroy temporary result")
        void testCallAssignDiscardStringReturn() {
            builder.callAssign(builder.discardRef(), "make_string", GdStringType.STRING, List.of());

            var result = builder.build();
            assertTrue(result.contains("godot_String __gdcc_tmp_discard_0 = make_string();"),
                    "Should materialize String return into discard temp");
            assertTrue(result.contains("godot_String_destroy(&__gdcc_tmp_discard_0);"),
                    "Should destroy discarded String return");
        }

        @Test
        @DisplayName("callAssign discard of RefCounted return should release temporary result")
        void testCallAssignDiscardRefCountedReturn() {
            builder.callAssign(builder.discardRef(), "create_object", new GdObjectType("RefCounted"), List.of());

            var result = builder.build();
            assertTrue(result.contains("gdcc_RefCounted_fat_ptr __gdcc_tmp_discard_0 = create_object();"),
                    "Should materialize object return into fat pointer discard temp. Actual:\n" + result);
            assertTrue(result.contains("release_object(gdcc_RefCounted_fat_ptr_live_object(__gdcc_tmp_discard_0));"),
                    "Should release discarded RefCounted object via live_object. Actual:\n" + result);
            assertFalse(result.contains("own_object(gdcc_RefCounted_fat_ptr_live_object(__gdcc_tmp_discard_0));"),
                    "Discard path must consume owned return without own");
        }

        @Test
        @DisplayName("callAssign discard of unknown object return should fail-fast on unregistered type")
        void testCallAssignDiscardUnknownObjectReturn() {
            // Fail-fast: unknown object types cannot materialize a fat pointer discard temp.
            var ex = assertThrows(IllegalStateException.class, () ->
                    builder.callAssign(builder.discardRef(), "fetch_unknown", new GdObjectType("UnknownType"), List.of())
            );
            assertTrue(ex.getMessage().contains("Unknown object type 'UnknownType'"),
                    "Discard of an unregistered object type must fail-fast. Actual:\n" + ex.getMessage());
        }

        @Test
        @DisplayName("callAssign object target should reject non-object return type")
        void testCallAssignObjectTargetRejectsNonObjectReturnType() {
            var target = new LirVariable("obj", new GdObjectType("RefCounted"), lirFunctionDef);
            var targetRef = builder.targetOfVar(target);

            var ex = assertThrows(RuntimeException.class, () ->
                    builder.callAssign(targetRef, "some_func", GdIntType.INT, List.of())
            );
            assertTrue(ex.getMessage().contains("requires object return type"),
                    "Should report object target/non-object return mismatch");
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
        @DisplayName("Unknown object assignment should fail-fast on unregistered type")
        void testUnknownObjectAssignmentFailsFast() {
            // Fail-fast: unknown object types cannot materialize a fat pointer slot storage.
            var target = new LirVariable("obj", new GdObjectType("UnknownType"), lirFunctionDef);
            var source = new LirVariable("src", new GdObjectType("UnknownType"), lirFunctionDef);
            var targetRef = builder.targetOfVar(target);
            var value = builder.valueOfVar(source);

            var ex = assertThrows(IllegalStateException.class, () -> builder.assignVar(targetRef, value));
            assertTrue(ex.getMessage().contains("Unknown object type 'UnknownType'"),
                    "Should fail-fast before emitting storage for unregistered object types. Actual:\n" + ex.getMessage());
        }
    }

    @Nested
    @DisplayName("PtrKind Resolution Tests")
    class PtrKindResolutionTests {

        @Test
        @DisplayName("GDCC object variable should have FAT_PTR kind")
        void testGdccObjectVarPtrKind() {
            var gdccVar = new LirVariable("myObj", new GdObjectType("MyGdccClass"), lirFunctionDef);
            var value = builder.valueOfVar(gdccVar);
            assertEquals(CBodyBuilder.PtrKind.FAT_PTR, value.ptrKind());
        }

        @Test
        @DisplayName("Engine object variable should have FAT_PTR kind")
        void testEngineObjectVarPtrKind() {
            var nodeVar = new LirVariable("node", new GdObjectType("Node"), lirFunctionDef);
            var value = builder.valueOfVar(nodeVar);
            assertEquals(CBodyBuilder.PtrKind.FAT_PTR, value.ptrKind());
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
            var value = builder.valueOfExpr("some_ptr", new GdObjectType("MyGdccClass"), CBodyBuilder.PtrKind.RAW_PRODUCER);
            assertEquals(CBodyBuilder.PtrKind.RAW_PRODUCER, value.ptrKind());
        }

        @Test
        @DisplayName("Expression PtrKind should be auto-resolved from type by default")
        void testExprAutoResolvedPtrKind() {
            var value = builder.valueOfExpr("some_ptr", new GdObjectType("MyGdccClass"));
            assertEquals(CBodyBuilder.PtrKind.FAT_PTR, value.ptrKind());
        }

        @Test
        @DisplayName("Variable reads should default to BORROWED ownership")
        void testVarReadDefaultsToBorrowedOwnership() {
            var value = builder.valueOfVar(new LirVariable("obj", new GdObjectType("MyGdccClass"), lirFunctionDef));

            assertEquals(CBodyBuilder.OwnershipKind.BORROWED, value.ownership());
        }

        @Test
        @DisplayName("Raw object expressions should stay BORROWED until a fresh producer marks them OWNED")
        void testExprDefaultsToBorrowedOwnership() {
            var value = builder.valueOfExpr("make_obj()", new GdObjectType("MyGdccClass"), CBodyBuilder.PtrKind.RAW_PRODUCER);

            assertEquals(CBodyBuilder.OwnershipKind.BORROWED, value.ownership());
        }

        @Test
        @DisplayName("valueOfCastedVar should keep BORROWED ownership across ptr conversion")
        void testCastedVarKeepsBorrowedOwnership() {
            var value = builder.valueOfCastedVar(
                    new LirVariable("obj", new GdObjectType("MyGdccClass"), lirFunctionDef),
                    new GdObjectType("RefCounted")
            );

            assertEquals(CBodyBuilder.OwnershipKind.BORROWED, value.ownership());
            assertEquals(CBodyBuilder.PtrKind.FAT_PTR, value.ptrKind());
            assertTrue(
                    value.generateCode().contains("gdcc_MyGdccClass_fat_ptr_upcast_to_RefCounted($obj)"),
                    "Borrowed casted value should render the fat pointer upcast helper. Actual:\n" + value.generateCode()
            );
        }

        @Test
        @DisplayName("Owned expression should expose OWNED ownership kind")
        void testOwnedExprOwnershipKind() {
            var value = builder.valueOfOwnedExpr(
                    "owned_ptr",
                    new GdObjectType("MyGdccClass"),
                    CBodyBuilder.PtrKind.FAT_PTR
            );
            assertEquals(CBodyBuilder.OwnershipKind.OWNED, value.ownership());
        }
    }

    @Nested
    @DisplayName("GDCC Object Argument Conversion Tests")
    class GdccObjectArgConversionTests {

        @Test
        @DisplayName("GDCC object arg should expand to validated live Godot raw ptr when calling godot_ function")
        void testGdccObjectArgConvertedForGodotFunc() {
            var gdccVar = new LirVariable("myObj", new GdObjectType("MyGdccClass"), lirFunctionDef);
            var value = builder.valueOfVar(gdccVar);

            builder.callVoid("godot_some_func", List.of(value));

            assertEquals("godot_some_func(gdcc_MyGdccClass_fat_ptr_live_object($myObj));\n", builder.build());
        }

        @Test
        @DisplayName("GDCC object arg should pass fat pointer to gdcc_engine_call_ helper")
        void testGdccObjectArgConvertedForEngineHelperFunc() {
            var gdccVar = new LirVariable("myObj", new GdObjectType("MyGdccClass"), lirFunctionDef);
            var value = builder.valueOfVar(gdccVar);

            builder.callVoid("gdcc_engine_call_MyGdccClass_attach_P_RV", List.of(value));

            assertEquals("gdcc_engine_call_MyGdccClass_attach_P_RV($myObj);\n", builder.build());
        }

        @Test
        @DisplayName("GDCC object arg should pass fat pointer to gdcc_engine_callv_ helper")
        void testGdccObjectArgConvertedForEngineVarargHelperFunc() {
            var gdccVar = new LirVariable("myObj", new GdObjectType("MyGdccClass"), lirFunctionDef);
            var value = builder.valueOfVar(gdccVar);

            builder.callVoid("gdcc_engine_callv_MyGdccClass_attach_P_RV_Xv", List.of(value));

            assertEquals("gdcc_engine_callv_MyGdccClass_attach_P_RV_Xv($myObj);\n", builder.build());
        }

        @Test
        @DisplayName("Engine object arg should expand to validated live Godot raw ptr via live_object when calling godot_ function")
        void testEngineObjectArgNotConvertedForGodotFunc() {
            var nodeVar = new LirVariable("node", new GdObjectType("Node"), lirFunctionDef);
            var value = builder.valueOfVar(nodeVar);

            builder.callVoid("godot_Node_do_thing", List.of(value));

            assertEquals("godot_Node_do_thing(gdcc_Node_fat_ptr_live_object($node));\n", builder.build());
        }

        @Test
        @DisplayName("Engine object arg should pass fat pointer to gdcc_engine_call_ helper")
        void testEngineObjectArgNotConvertedForEngineHelperFunc() {
            var nodeVar = new LirVariable("node", new GdObjectType("Node"), lirFunctionDef);
            var value = builder.valueOfVar(nodeVar);

            builder.callVoid("gdcc_engine_call_Node_do_thing_P_RV", List.of(value));

            assertEquals("gdcc_engine_call_Node_do_thing_P_RV($node);\n", builder.build());
        }

        @Test
        @DisplayName("Engine object arg should pass fat pointer to gdcc_engine_callv_ helper")
        void testEngineObjectArgNotConvertedForEngineVarargHelperFunc() {
            var nodeVar = new LirVariable("node", new GdObjectType("Node"), lirFunctionDef);
            var value = builder.valueOfVar(nodeVar);

            builder.callVoid("gdcc_engine_callv_Node_do_thing_P_RV_Xv", List.of(value));

            assertEquals("gdcc_engine_callv_Node_do_thing_P_RV_Xv($node);\n", builder.build());
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
        @DisplayName("valueOfCastedVar should fail-fast when static GDCC->engine cast lacks an ancestor relation")
        void testCastedGdccReceiverToEngineOwnerShouldConvertBeforeCast() {
            var gdccVar = new LirVariable("self", new GdObjectType("MyGdccClass"), lirFunctionDef);
            var ex = assertThrows(InvalidInsnException.class, () ->
                    builder.valueOfCastedVar(gdccVar, new GdObjectType("Node"))
            );
            assertTrue(ex.getMessage().contains("Cannot upcast object type 'MyGdccClass' to 'Node'"),
                    "Cross-hierarchy casts must fail-fast. Actual:\n" + ex.getMessage());
        }

        @Test
        @DisplayName("valueOfCastedVar should fail-fast when casting engine receiver to an unrelated GDCC type")
        void testCastedEngineReceiverToGdccTypeShouldConvertFromGodotPtr() {
            var engineVar = new LirVariable("node", new GdObjectType("Node"), lirFunctionDef);
            var ex = assertThrows(InvalidInsnException.class, () ->
                    builder.valueOfCastedVar(engineVar, new GdObjectType("MyGdccClass"))
            );
            assertTrue(ex.getMessage().contains("Cannot upcast object type 'Node' to 'MyGdccClass'"),
                    "Cross-hierarchy casts must fail-fast. Actual:\n" + ex.getMessage());
        }

        @Test
        @DisplayName("valueOfCastedVar should render GDCC upcast via fat pointer upcast helper")
        void testCastedGdccChildToAncestorShouldUseSuperChain() {
            builder.classRegistry().addGdccClass(new LirClassDef("GdccGrandParent", "RefCounted"));
            builder.classRegistry().addGdccClass(new LirClassDef("GdccParent", "GdccGrandParent"));
            builder.classRegistry().addGdccClass(new LirClassDef("GdccChild", "GdccParent"));
            var childVar = new LirVariable("child", new GdObjectType("GdccChild"), lirFunctionDef);
            var casted = builder.valueOfCastedVar(childVar, new GdObjectType("GdccGrandParent"));

            builder.callVoid("my_custom_func", List.of(casted));

            assertEquals("my_custom_func(gdcc_GdccChild_fat_ptr_upcast_to_GdccGrandParent($child));\n", builder.build());
        }

        @Test
        @DisplayName("valueOfCastedVar should render canonical inner-class GDCC upcast via fat pointer upcast helper")
        void testCastedCanonicalInnerGdccChildToAncestorShouldUseSuperChain() {
            // The backend cast path works on canonical runtime identity even when source-facing local names remain available.
            builder.classRegistry().addGdccClass(new LirClassDef("Outer__sub__GrandParent", "RefCounted"), "GrandParent");
            builder.classRegistry().addGdccClass(new LirClassDef("Outer__sub__Parent", "Outer__sub__GrandParent"), "Parent");
            builder.classRegistry().addGdccClass(new LirClassDef("Outer__sub__Child", "Outer__sub__Parent"), "Child");
            var childVar = new LirVariable("child", new GdObjectType("Outer__sub__Child"), lirFunctionDef);
            var casted = builder.valueOfCastedVar(childVar, new GdObjectType("Outer__sub__GrandParent"));

            builder.callVoid("my_custom_func", List.of(casted));

            assertEquals(
                    "my_custom_func(gdcc_Outer_sub_Child_fat_ptr_upcast_to_Outer_sub_GrandParent($child));\n",
                    builder.build()
            );
        }

        @Test
        @DisplayName("valueOfCastedVar should reject GDCC non-upcast conversion")
        void testCastedGdccParentToChildShouldFailFast() {
            builder.classRegistry().addGdccClass(new LirClassDef("GdccParent", "RefCounted"));
            builder.classRegistry().addGdccClass(new LirClassDef("GdccChild", "GdccParent"));
            var parentVar = new LirVariable("parent", new GdObjectType("GdccParent"), lirFunctionDef);

            var ex = assertThrows(InvalidInsnException.class, () ->
                    builder.valueOfCastedVar(parentVar, new GdObjectType("GdccChild"))
            );
            assertInstanceOf(InvalidInsnException.class, ex);
            assertTrue(ex.getMessage().contains("Cannot upcast object type 'GdccParent' to 'GdccChild'"),
                    ex.getMessage());
        }

        @Test
        @DisplayName("valueOfCastedVar should reject source-styled inner superclass metadata")
        void testCastedInnerGdccChildWithSourceStyledSuperShouldFailFast() {
            builder.classRegistry().addGdccClass(new LirClassDef("Outer__sub__Parent", "RefCounted"), "Parent");
            builder.classRegistry().addGdccClass(new LirClassDef("Outer__sub__Child", "Parent"), "Child");
            var childVar = new LirVariable("child", new GdObjectType("Outer__sub__Child"), lirFunctionDef);

            var ex = assertThrows(InvalidInsnException.class, () ->
                    builder.valueOfCastedVar(childVar, new GdObjectType("Outer__sub__Parent"))
            );
            assertInstanceOf(InvalidInsnException.class, ex);
            assertTrue(ex.getMessage().contains("Cannot upcast object type 'Outer__sub__Child' to 'Outer__sub__Parent'"),
                    ex.getMessage());
        }

        @Test
        @DisplayName("renderArgument should fail-fast on inconsistent NON_OBJECT ptr kind and object type")
        void testRenderArgumentShouldRejectInconsistentGdccPtrKind() {
            // The backend collapses engine and GDCC object storage into FAT_PTR, so the only remaining
            // mismatch for an object argument is a NON_OBJECT ptr kind paired with an object type.
            var mismatched = builder.valueOfExpr("(godot_Node*)$self", new GdObjectType("Node"), CBodyBuilder.PtrKind.NON_OBJECT);
            var ex = assertThrows(InvalidInsnException.class, () -> builder.callVoid("godot_Node_queue_free", List.of(mismatched)));
            assertInstanceOf(InvalidInsnException.class, ex);
            assertTrue(ex.getMessage().contains("ptr kind/type mismatch") && ex.getMessage().contains("NON_OBJECT"),
                    ex.getMessage());
        }

        @Test
        @DisplayName("object lifecycle call should pass validated live raw ptr plus cached instance_id")
        void testGdccObjectArgConvertedForOwnObject() {
            var gdccVar = new LirVariable("myObj", new GdObjectType("MyGdccClass"), lirFunctionDef);

            builder.emitObjectLifecycleCall("try_own_object", builder.valueOfVar(gdccVar));

            assertEquals("try_own_object(gdcc_MyGdccClass_fat_ptr_live_object($myObj), $myObj.instance_id);\n",
                    builder.build());
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

            assertEquals("godot_Object_set(gdcc_MyGdccClass_fat_ptr_live_object($myObj), &$name);\n", builder.build());
        }

        @Test
        @DisplayName("gdcc_eval_* helpers accept fat pointers (not raw ABI)")
        void testGdccEvalHelperKeepsFatPointerArgument() {
            var gdccVar = new LirVariable("myObj", new GdObjectType("MyGdccClass"), lirFunctionDef);

            builder.callVoid("gdcc_eval_binary_in_object_array_to_bool", List.of(builder.valueOfVar(gdccVar)));

            var result = builder.build();
            assertEquals("gdcc_eval_binary_in_object_array_to_bool($myObj);\n", result);
            assertFalse(result.contains("_live_object("));
        }

        @Test
        @DisplayName("explicit raw-ABI helper still converts fat pointer to live raw")
        void testExplicitRawAbiHelperConvertsToLiveObject() {
            var gdccVar = new LirVariable("myObj", new GdObjectType("MyGdccClass"), lirFunctionDef);

            builder.callVoid("gdcc_object_from_godot_object_ptr", List.of(builder.valueOfVar(gdccVar)));

            assertEquals("gdcc_object_from_godot_object_ptr(gdcc_MyGdccClass_fat_ptr_live_object($myObj));\n",
                    builder.build());
        }

        @Test
        @DisplayName("gdcc_eval_* object return is already fat (no from_raw capture)")
        void testGdccEvalObjectReturnIsFatNotRawProducer() {
            var target = new LirVariable("myObj", new GdObjectType("MyGdccClass"), lirFunctionDef);

            builder.callAssign(
                    builder.targetOfVar(target),
                    "gdcc_eval_binary_add_something",
                    new GdObjectType("MyGdccClass"),
                    List.of()
            );

            var result = builder.build();
            assertTrue(result.contains("$myObj = gdcc_eval_binary_add_something();"), result);
            assertFalse(result.contains("_from_raw("), result);
        }

        @Test
        @DisplayName("valueOfCastedVar should reject object and non-object casts")
        void testValueOfCastedVarShouldRejectObjectNonObjectCast() {
            var gdccVar = new LirVariable("myObj", new GdObjectType("MyGdccClass"), lirFunctionDef);
            var ex = assertThrows(InvalidInsnException.class, () -> builder.valueOfCastedVar(gdccVar, GdIntType.INT));
            assertInstanceOf(InvalidInsnException.class, ex);
            assertTrue(ex.getMessage().contains("Cannot cast between object and non-object types"), ex.getMessage());
        }
    }

    @Nested
    @DisplayName("GDCC Object Return Conversion Tests")
    class GdccObjectReturnConversionTests {

        @Test
        @DisplayName("callAssign should wrap godot_ return with from_raw fat capture for GDCC target")
        void testCallAssignGdccTargetFromGodotFunc() {
            var target = new LirVariable("myObj", new GdObjectType("MyGdccClass"), lirFunctionDef);
            var targetRef = builder.targetOfVar(target);

            builder.callAssign(targetRef, "godot_get_something", new GdObjectType("MyGdccClass"), List.of());

            var result = builder.build();
            assertTrue(result.contains("gdcc_MyGdccClass_fat_ptr_from_raw((GDExtensionObjectPtr)(godot_get_something()))"),
                    "Should wrap godot_ return with from_raw fat capture for GDCC target. Actual:\n" + result);
        }

        @Test
        @DisplayName("callAssign should treat gdcc_engine_call_ return as fat pointer for GDCC target")
        void testCallAssignGdccTargetFromEngineHelperFunc() {
            var target = new LirVariable("myObj", new GdObjectType("MyGdccClass"), lirFunctionDef);
            var targetRef = builder.targetOfVar(target);

            builder.callAssign(targetRef, "gdcc_engine_call_Node_spawn_P_RL4Node_", new GdObjectType("MyGdccClass"), List.of());

            var result = builder.build();
            assertFalse(result.contains("gdcc_MyGdccClass_fat_ptr_from_raw((GDExtensionObjectPtr)(gdcc_engine_call_Node_spawn_P_RL4Node_()))"),
                    "Engine helper already returns fat; caller must not re-capture via from_raw. Actual:\n" + result);
            assertTrue(result.contains("gdcc_engine_call_Node_spawn_P_RL4Node_()"), result);
        }

        @Test
        @DisplayName("callAssign should treat gdcc_engine_callv_ return as fat pointer for GDCC target")
        void testCallAssignGdccTargetFromEngineVarargHelperFunc() {
            var target = new LirVariable("myObj", new GdObjectType("MyGdccClass"), lirFunctionDef);
            var targetRef = builder.targetOfVar(target);

            builder.callAssign(targetRef, "gdcc_engine_callv_Node_spawn_P_RL4Node__Xv", new GdObjectType("MyGdccClass"), List.of());

            var result = builder.build();
            assertFalse(result.contains("gdcc_MyGdccClass_fat_ptr_from_raw((GDExtensionObjectPtr)(gdcc_engine_callv_Node_spawn_P_RL4Node__Xv()))"),
                    "Vararg engine helper already returns fat; caller must not re-capture via from_raw. Actual:\n" + result);
            assertTrue(result.contains("gdcc_engine_callv_Node_spawn_P_RL4Node__Xv()"), result);
        }

        @Test
        @DisplayName("callAssign should capture godot_ return into fat pointer for engine target")
        void testCallAssignEngineTargetFromGodotFuncCapturesFatPtr() {
            var target = new LirVariable("node", new GdObjectType("Node"), lirFunctionDef);
            var targetRef = builder.targetOfVar(target);

            builder.callAssign(targetRef, "godot_get_node", new GdObjectType("Node"), List.of());

            var result = builder.build();
            assertTrue(result.contains("gdcc_Node_fat_ptr_from_raw((GDExtensionObjectPtr)(godot_get_node()))"),
                    "Engine raw producer must capture into fat pointer storage. Actual:\n" + result);
        }

        @Test
        @DisplayName("callAssign should assign gdcc_engine_call_ fat return for engine target")
        void testCallAssignEngineTargetFromEngineHelperFuncCapturesFatPtr() {
            var target = new LirVariable("node", new GdObjectType("Node"), lirFunctionDef);
            var targetRef = builder.targetOfVar(target);

            builder.callAssign(targetRef, "gdcc_engine_call_Node_spawn_P_RL4Node_", new GdObjectType("Node"), List.of());

            var result = builder.build();
            assertFalse(result.contains("gdcc_Node_fat_ptr_from_raw((GDExtensionObjectPtr)(gdcc_engine_call_Node_spawn_P_RL4Node_()))"),
                    "Engine helper already returns fat; no caller-side from_raw. Actual:\n" + result);
            assertTrue(result.contains("gdcc_engine_call_Node_spawn_P_RL4Node_()"), result);
        }

        @Test
        @DisplayName("callAssign should assign gdcc_engine_callv_ fat return for engine target")
        void testCallAssignEngineTargetFromEngineVarargHelperFuncCapturesFatPtr() {
            var target = new LirVariable("node", new GdObjectType("Node"), lirFunctionDef);
            var targetRef = builder.targetOfVar(target);

            builder.callAssign(targetRef, "gdcc_engine_callv_Node_spawn_P_RL4Node__Xv", new GdObjectType("Node"), List.of());

            var result = builder.build();
            assertFalse(result.contains("gdcc_Node_fat_ptr_from_raw((GDExtensionObjectPtr)(gdcc_engine_callv_Node_spawn_P_RL4Node__Xv()))"),
                    "Vararg engine helper already returns fat; no caller-side from_raw. Actual:\n" + result);
            assertTrue(result.contains("gdcc_engine_callv_Node_spawn_P_RL4Node__Xv()"), result);
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
        @DisplayName("callAssign with GDCC target from godot_ func should release captured old and consume owned return")
        void testCallAssignGdccTargetOwnRelease() {
            var target = new LirVariable("myObj", new GdObjectType("MyGdccClass"), lirFunctionDef);
            var targetRef = builder.targetOfVar(target);

            builder.callAssign(targetRef, "godot_get_something", new GdObjectType("MyGdccClass"), List.of());

            var result = builder.build();
            // MyGdccClass extends RefCounted; owned call results should not be owned again.
            assertTrue(result.contains("release_object(gdcc_MyGdccClass_fat_ptr_live_object(__gdcc_tmp_old_obj_0))"),
                    "Should release captured old GDCC object. Actual:\n" + result);
            assertFalse(result.contains("own_object(gdcc_MyGdccClass_fat_ptr_live_object($myObj))"),
                    "Should consume owned call result without own. Actual:\n" + result);
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
            assertTrue(result.contains("gdcc_MyGdccClass_fat_ptr_live_object($input)"),
                    "Should convert GDCC arg to godot ptr. Actual:\n" + result);
            // Return should be wrapped
            assertTrue(result.contains("gdcc_MyGdccClass_fat_ptr_from_raw((GDExtensionObjectPtr)(godot_transform("),
                    "Should wrap return with from_raw fat capture. Actual:\n" + result);
        }
    }

    @Nested
    @DisplayName("AssignVar Pointer Conversion Tests")
    class AssignVarPtrConversionTests {

        @Test
        @DisplayName("RAW_PRODUCER value assigned to GDCC target should wrap with from_raw fat capture")
        void testGodotPtrValueToGdccTarget() {
            var target = new LirVariable("myObj", new GdObjectType("MyGdccClass"), lirFunctionDef);
            var targetRef = builder.targetOfVar(target);
            // Expression with explicit RAW_PRODUCER: simulates a GDExtension API return value
            var value = builder.valueOfExpr("some_godot_api_result", new GdObjectType("MyGdccClass"), CBodyBuilder.PtrKind.RAW_PRODUCER);

            builder.assignVar(targetRef, value);

            var result = builder.build();
            assertTrue(result.contains("gdcc_MyGdccClass_fat_ptr_from_raw((GDExtensionObjectPtr)(some_godot_api_result))"),
                    "Should convert RAW_PRODUCER to FAT_PTR via from_raw fat capture. Actual:\n" + result);
        }

        @Test
        @DisplayName("FAT_PTR value assigned to engine parent target should use fat pointer upcast")
        void testGdccPtrValueToEngineTarget() {
            // MyGdccClass extends RefCounted, so assignment into RefCounted storage is valid.
            var target = new LirVariable("obj", new GdObjectType("RefCounted"), lirFunctionDef);
            var targetRef = builder.targetOfVar(target);
            var source = new LirVariable("myObj", new GdObjectType("MyGdccClass"), lirFunctionDef);
            var value = builder.valueOfVar(source);

            builder.assignVar(targetRef, value);

            var result = builder.build();
            assertTrue(result.contains("$obj = gdcc_MyGdccClass_fat_ptr_upcast_to_RefCounted($myObj)"),
                    "Should upcast GDCC fat pointer into engine parent fat pointer. Actual:\n" + result);
        }

        @Test
        @DisplayName("OWNED FAT_PTR assigned to engine target should convert representation without extra own")
        void testOwnedGdccPtrValueToEngineTargetDoesNotOwnAgain() {
            var target = new LirVariable("rc", new GdObjectType("RefCounted"), lirFunctionDef);
            var targetRef = builder.targetOfVar(target);
            var ownedValue = builder.valueOfOwnedExpr(
                    "fresh_worker()",
                    new GdObjectType("MyGdccClass"),
                    CBodyBuilder.PtrKind.FAT_PTR
            );

            builder.assignVar(targetRef, ownedValue);

            var result = builder.build();
            assertTrue(
                    result.contains("$rc = gdcc_MyGdccClass_fat_ptr_upcast_to_RefCounted(fresh_worker());"),
                    "OWNED FAT_PTR should still convert before storing into engine slot. Actual:\n" + result
            );
            assertFalse(
                    result.contains("own_object($rc);"),
                    "Pointer conversion must not silently re-own an already owned value. Actual:\n" + result
            );
            assertFalse(
                    result.contains("try_own_object($rc);"),
                    "Pointer conversion must stay ownership-neutral for try-own paths too. Actual:\n" + result
            );
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
                    "Should NOT wrap with from_raw fat capture. Actual:\n" + result);
            assertFalse(result.contains("gdcc_MyGdccClass_fat_ptr_live_object($source);"),
                    "Should NOT use helper conversion on RHS. Actual:\n" + result);
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
            assertFalse(result.contains("gdcc_object_to_godot_object_ptr"),
                    "Should NOT use helper conversion. Actual:\n" + result);
        }

        @Test
        @DisplayName("RAW_PRODUCER to GDCC target should still do own/release with helper conversion using old-temp flow")
        void testGodotPtrToGdccTargetOwnRelease() {
            var target = new LirVariable("myObj", new GdObjectType("MyGdccClass"), lirFunctionDef);
            var targetRef = builder.targetOfVar(target);
            var value = builder.valueOfExpr("some_godot_result", new GdObjectType("MyGdccClass"), CBodyBuilder.PtrKind.RAW_PRODUCER);

            builder.assignVar(targetRef, value);

            var result = builder.build();
            // MyGdccClass extends RefCounted, should have own/release with helper conversion
            assertTrue(result.contains("release_object(gdcc_MyGdccClass_fat_ptr_live_object(__gdcc_tmp_old_obj_0))"),
                    "Should release captured old GDCC object via helper conversion. Actual:\n" + result);
            assertTrue(result.contains("own_object(gdcc_MyGdccClass_fat_ptr_live_object($myObj))"),
                    "Should own new GDCC object via helper conversion. Actual:\n" + result);
            // Should also convert the assignment value
            assertTrue(result.contains("gdcc_MyGdccClass_fat_ptr_from_raw((GDExtensionObjectPtr)(some_godot_result))"),
                    "Should convert RAW_PRODUCER to FAT_PTR. Actual:\n" + result);
        }

        @Test
        @DisplayName("assignExpr with explicit PtrKind should convert RAW_PRODUCER to GDCC")
        void testAssignExprWithPtrKindConversion() {
            var target = new LirVariable("myObj", new GdObjectType("MyGdccClass"), lirFunctionDef);
            var targetRef = builder.targetOfVar(target);

            builder.assignExpr(targetRef, "godot_api_call()", new GdObjectType("MyGdccClass"), CBodyBuilder.PtrKind.RAW_PRODUCER);

            var result = builder.build();
            assertTrue(result.contains("gdcc_MyGdccClass_fat_ptr_from_raw((GDExtensionObjectPtr)(godot_api_call()))"),
                    "assignExpr with RAW_PRODUCER should convert to FAT_PTR. Actual:\n" + result);
        }

        @Test
        @DisplayName("assignExpr without PtrKind should auto-resolve and NOT convert (GDCC type → FAT_PTR)")
        void testAssignExprAutoResolvedNoPtrConversion() {
            var target = new LirVariable("myObj", new GdObjectType("MyGdccClass"), lirFunctionDef);
            var targetRef = builder.targetOfVar(target);

            // Without explicit PtrKind, auto-resolved from GdObjectType("MyGdccClass") → FAT_PTR
            builder.assignExpr(targetRef, "some_gdcc_ptr", new GdObjectType("MyGdccClass"));

            var result = builder.build();
            assertTrue(result.contains("$myObj = some_gdcc_ptr;"),
                    "Should assign directly when PtrKinds match. Actual:\n" + result);
            assertFalse(result.contains("gdcc_object_from_godot_object_ptr"),
                    "Should NOT convert when PtrKinds match. Actual:\n" + result);
        }

        @Test
        @DisplayName("FAT_PTR to RefCounted (engine base class) target should use helper conversion")
        void testGdccPtrToRefCountedTarget() {
            // RefCounted is an engine type (RAW_PRODUCER)
            var target = new LirVariable("rc", new GdObjectType("RefCounted"), lirFunctionDef);
            var targetRef = builder.targetOfVar(target);
            // MyGdccClass extends RefCounted, so assignment is valid
            var source = new LirVariable("myObj", new GdObjectType("MyGdccClass"), lirFunctionDef);
            var value = builder.valueOfVar(source);

            builder.assignVar(targetRef, value);

            var result = builder.build();
            assertTrue(result.contains("$rc = gdcc_MyGdccClass_fat_ptr_upcast_to_RefCounted($myObj)"),
                    "Should convert FAT_PTR to RAW_PRODUCER via helper conversion. Actual:\n" + result);
        }

        @Test
        @DisplayName("RAW_PRODUCER to GDCC target full ordering: capture old → assign with conversion → own → release old")
        void testGodotPtrToGdccTargetFullOrdering() {
            var target = new LirVariable("myObj", new GdObjectType("MyGdccClass"), lirFunctionDef);
            var targetRef = builder.targetOfVar(target);
            var value = builder.valueOfExpr("godot_result", new GdObjectType("MyGdccClass"), CBodyBuilder.PtrKind.RAW_PRODUCER);

            builder.assignVar(targetRef, value);

            var result = builder.build();
            var captureIndex = result.indexOf("gdcc_MyGdccClass_fat_ptr __gdcc_tmp_old_obj_0 = $myObj;");
            var assignIndex = result.indexOf("$myObj = gdcc_MyGdccClass_fat_ptr_from_raw((GDExtensionObjectPtr)(godot_result));");
            var ownIndex = result.indexOf("own_object(gdcc_MyGdccClass_fat_ptr_live_object($myObj))");
            var releaseOldIndex = result.indexOf("release_object(gdcc_MyGdccClass_fat_ptr_live_object(__gdcc_tmp_old_obj_0));");

            assertTrue(captureIndex >= 0, "Should capture old slot value. Actual:\n" + result);
            assertTrue(assignIndex >= 0, "Should have converted assignment. Actual:\n" + result);
            assertTrue(ownIndex >= 0, "Should have own. Actual:\n" + result);
            assertTrue(releaseOldIndex >= 0, "Should release captured old value. Actual:\n" + result);
            assertTrue(captureIndex < assignIndex, "Old capture should come before assignment");
            assertTrue(assignIndex < ownIndex, "Assignment should come before own");
            assertTrue(ownIndex < releaseOldIndex, "Release of captured old value should happen last");
        }
    }

    @Nested
    @DisplayName("Property Initializer First-Write Tests")
    class PropertyInitializerFirstWriteTests {

        @Test
        @DisplayName("Object-valued property init first-write should convert RAW_PRODUCER and consume OWNED without own/release")
        void testObjectPropertyInitFirstWriteConsumesOwnedReturn() {
            builder.applyPropertyInitializerFirstWrite(
                    "self->worker",
                    new GdObjectType("MyGdccClass"),
                    "godot_make_worker()",
                    new GdObjectType("MyGdccClass"),
                    CBodyBuilder.PtrKind.RAW_PRODUCER,
                    CBodyBuilder.OwnershipKind.OWNED
            );

            var result = builder.build();
            assertTrue(
                    result.contains("self->worker = gdcc_MyGdccClass_fat_ptr_from_raw((GDExtensionObjectPtr)(godot_make_worker()));"),
                    "First-write should convert RAW_PRODUCER helper result before storing. Actual:\n" + result
            );
            assertFalse(result.contains("__gdcc_tmp_old_obj_"), "First-write should not capture an old field value. Actual:\n" + result);
            assertFalse(result.contains("own_object("), "OWNED helper result should be consumed without extra own. Actual:\n" + result);
            assertFalse(result.contains("release_object("), "First-write should not release an old field value. Actual:\n" + result);
        }

        @Test
        @DisplayName("Borrowed object property init first-write should retain new field value without release-old flow")
        void testBorrowedObjectPropertyInitFirstWriteOwnsWithoutReleaseOld() {
            builder.applyPropertyInitializerFirstWrite(
                    "self->node_ref",
                    new GdObjectType("RefCounted"),
                    "borrowed_ref()",
                    new GdObjectType("RefCounted"),
                    CBodyBuilder.PtrKind.RAW_PRODUCER,
                    CBodyBuilder.OwnershipKind.BORROWED
            );

            var result = builder.build();
            assertTrue(result.contains("self->node_ref = gdcc_RefCounted_fat_ptr_from_raw((GDExtensionObjectPtr)(borrowed_ref()));"),
                    "Borrowed RAW_PRODUCER should capture into fat pointer storage. Actual:\n" + result);
            assertTrue(result.contains("own_object(gdcc_RefCounted_fat_ptr_live_object(self->node_ref));"),
                    "Borrowed object result should be retained via live_object. Actual:\n" + result);
            assertFalse(result.contains("__gdcc_tmp_old_obj_"), "First-write should not capture old value even for borrowed rhs. Actual:\n" + result);
            assertFalse(result.contains("release_object("), "First-write should not release old value. Actual:\n" + result);
        }

        @Test
        @DisplayName("Destroyable non-object property init first-write should not destroy an old value")
        void testDestroyableNonObjectPropertyInitFirstWriteSkipsDestroyOld() {
            builder.applyPropertyInitializerFirstWrite(
                    "self->label",
                    GdStringType.STRING,
                    "make_label()",
                    GdStringType.STRING,
                    CBodyBuilder.PtrKind.NON_OBJECT,
                    CBodyBuilder.OwnershipKind.OWNED
            );

            var result = builder.build();
            assertEquals("self->label = make_label();\n", result);
            assertFalse(result.contains("godot_String_destroy"), "First-write should not destroy an old String field value. Actual:\n" + result);
        }
    }
}
