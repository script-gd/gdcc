package dev.superice.gdcc.backend.c.gen;

import dev.superice.gdcc.backend.CodegenContext;
import dev.superice.gdcc.backend.ProjectInfo;
import dev.superice.gdcc.enums.GodotVersion;
import dev.superice.gdcc.exception.InvalidInsnException;
import dev.superice.gdcc.gdextension.ExtensionAPI;
import dev.superice.gdcc.gdextension.ExtensionEnumValue;
import dev.superice.gdcc.gdextension.ExtensionFunctionArgument;
import dev.superice.gdcc.gdextension.ExtensionGlobalEnum;
import dev.superice.gdcc.gdextension.ExtensionGdClass;
import dev.superice.gdcc.gdextension.ExtensionUtilityFunction;
import dev.superice.gdcc.lir.LirBasicBlock;
import dev.superice.gdcc.lir.LirClassDef;
import dev.superice.gdcc.lir.LirFunctionDef;
import dev.superice.gdcc.lir.LirParameterDef;
import dev.superice.gdcc.lir.LirVariable;
import dev.superice.gdcc.lir.insn.NopInsn;
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
        @DisplayName("RefCounted object assignment should capture old, own new, then release captured old")
        void testRefCountedObjectAssignment() {
            var target = new LirVariable("obj", new GdObjectType("RefCounted"), lirFunctionDef);
            var source = new LirVariable("src", new GdObjectType("RefCounted"), lirFunctionDef);
            var targetRef = builder.targetOfVar(target);
            var value = builder.valueOfVar(source);

            builder.assignVar(targetRef, value);

            var result = builder.build();
            // RefCounted: should use release_object and own_object (not try_ versions)
            assertTrue(result.contains("godot_RefCounted* __gdcc_tmp_old_obj_0 = $obj;"),
                    "Should capture old RefCounted object");
            assertTrue(result.contains("release_object(__gdcc_tmp_old_obj_0);"),
                    "Should release captured old RefCounted object");
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
        @DisplayName("GDCC object assignment should use helper conversion for own/release")
        void testGdccObjectAssignment() {
            var target = new LirVariable("myObj", new GdObjectType("MyGdccClass"), lirFunctionDef);
            var source = new LirVariable("src", new GdObjectType("MyGdccClass"), lirFunctionDef);
            var targetRef = builder.targetOfVar(target);
            var value = builder.valueOfVar(source);

            builder.assignVar(targetRef, value);

            var result = builder.build();
            // GDCC class inherits RefCounted, should use helper conversion
            assertTrue(result.contains("gdcc_object_to_godot_object_ptr($myObj, MyGdccClass_object_ptr)"),
                    "Should use class-specific GDCC object pointer helper conversion");
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
            assertTrue(result.contains("godot_RefCounted* _return_val = NULL;"), "Prepare block should init return slot");
            assertTrue(result.contains("release_object(__gdcc_tmp_old_obj_0);"), "Writing return slot should release captured old value");
            assertTrue(result.contains("_return_val = $obj;"), "Should write returned object into slot");
            assertTrue(result.contains("$obj = NULL;"), "Moved local object should be cleared so __finally__ does not release it again");
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
                    CBodyBuilder.PtrKind.GODOT_PTR
            );

            objectBuilder.returnValue(ownedValue);

            var result = objectBuilder.build();
            assertTrue(result.contains("release_object(__gdcc_tmp_old_obj_0);"), "Should still release previous return slot value");
            assertTrue(result.contains("_return_val = create_object();"), "Should assign owned return expression");
            assertFalse(result.contains("own_object(_return_val);"), "Owned return value must not be owned again");
            assertTrue(result.contains("goto __finally__;"), "Non-finally return should jump to __finally__");
        }

        @Test
        @DisplayName("Returning owned GODOT_PTR for GDCC return type should convert representation without own")
        void testReturnOwnedGodotPtrToGdccReturnTypeDoesNotOwnAgain() {
            var objectBuilder = createBuilderWithReturnType(new GdObjectType("MyGdccClass"));
            objectBuilder.beginBasicBlock("__prepare__");
            var ownedValue = objectBuilder.valueOfOwnedExpr(
                    "godot_make_worker()",
                    new GdObjectType("MyGdccClass"),
                    CBodyBuilder.PtrKind.GODOT_PTR
            );

            objectBuilder.returnValue(ownedValue);

            var result = objectBuilder.build();
            assertTrue(
                    result.contains("_return_val = (MyGdccClass*)gdcc_object_from_godot_object_ptr(godot_make_worker());"),
                    "OWNED Godot ptr return should be converted before publishing into GDCC return slot. Actual:\n" + result
            );
            assertFalse(
                    result.contains("own_object(gdcc_object_to_godot_object_ptr(_return_val, MyGdccClass_object_ptr));"),
                    "Representation conversion must not add an extra retain at the publish boundary. Actual:\n" + result
            );
            assertFalse(
                    result.contains("try_own_object(gdcc_object_to_godot_object_ptr(_return_val, MyGdccClass_object_ptr));"),
                    "Representation conversion must stay ownership-neutral for unknown refcount status too. Actual:\n" + result
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
            assertTrue(result.contains("own_object(_return_val);"), "Borrowed parameter should be retained for the caller");
            assertFalse(result.contains("$obj = NULL;"), "Borrowed parameter must not be cleared by move logic");
        }

        @Test
        @DisplayName("Returning borrowed object expression outside finally should retain return slot without move")
        void testReturnBorrowedObjectExpressionOutsideFinallyUsesOwn() {
            var objectBuilder = createBuilderWithReturnType(new GdObjectType("RefCounted"));
            objectBuilder.beginBasicBlock("__prepare__");
            var borrowedValue = objectBuilder.valueOfExpr(
                    "self->cached_resource",
                    new GdObjectType("RefCounted"),
                    CBodyBuilder.PtrKind.GODOT_PTR
            );

            objectBuilder.returnValue(borrowedValue);

            var result = objectBuilder.build();
            assertTrue(result.contains("_return_val = self->cached_resource;"),
                    "Borrowed field/property style return should still publish through the return slot");
            assertTrue(result.contains("own_object(_return_val);"),
                    "Borrowed field/property style return should retain at the publish boundary");
            assertFalse(result.contains("cached_resource = NULL;"),
                    "Borrowed expression return must not trigger move-return source clearing");
            assertTrue(result.contains("goto __finally__;"), "Non-finally return should jump to __finally__");
        }

        @Test
        @DisplayName("Returning borrowed GODOT_PTR for GDCC return type should retain only at publish boundary")
        void testReturnBorrowedGodotPtrToGdccReturnTypeOwnsAtPublishBoundary() {
            var objectBuilder = createBuilderWithReturnType(new GdObjectType("MyGdccClass"));
            objectBuilder.beginBasicBlock("__prepare__");
            var borrowedValue = objectBuilder.valueOfExpr(
                    "self->cached_worker",
                    new GdObjectType("MyGdccClass"),
                    CBodyBuilder.PtrKind.GODOT_PTR
            );

            objectBuilder.returnValue(borrowedValue);

            var result = objectBuilder.build();
            assertTrue(
                    result.contains("_return_val = (MyGdccClass*)gdcc_object_from_godot_object_ptr(self->cached_worker);"),
                    "Borrowed Godot ptr should still be converted before publishing into GDCC return slot. Actual:\n" + result
            );
            assertTrue(
                    result.contains("own_object(gdcc_object_to_godot_object_ptr(_return_val, MyGdccClass_object_ptr));"),
                    "Borrowed return should retain exactly at the publish boundary after conversion. Actual:\n" + result
            );
            assertFalse(
                    result.contains("try_own_object(gdcc_object_to_godot_object_ptr(_return_val, MyGdccClass_object_ptr));"),
                    "Known RefCounted GDCC return type should use the precise own path. Actual:\n" + result
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
        @DisplayName("callAssign with RefCounted target should release captured old and consume owned return")
        void testCallAssignRefCountedTarget() {
            var target = new LirVariable("obj", new GdObjectType("RefCounted"), lirFunctionDef);
            var targetRef = builder.targetOfVar(target);

            builder.callAssign(targetRef, "create_object", new GdObjectType("RefCounted"), List.of());

            var result = builder.build();
            assertTrue(result.contains("release_object(__gdcc_tmp_old_obj_0);"), "Should release captured old object");
            assertFalse(result.contains("own_object($obj)"), "Owned call result should not be owned again");
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
            assertTrue(result.contains("godot_RefCounted* __gdcc_tmp_discard_0 = create_object();"),
                    "Should materialize object return into discard temp");
            assertTrue(result.contains("release_object(__gdcc_tmp_discard_0);"),
                    "Should release discarded RefCounted object");
            assertFalse(result.contains("own_object(__gdcc_tmp_discard_0)"),
                    "Discard path must consume owned return without own");
        }

        @Test
        @DisplayName("callAssign discard of unknown object return should try_release temporary result")
        void testCallAssignDiscardUnknownObjectReturn() {
            builder.callAssign(builder.discardRef(), "fetch_unknown", new GdObjectType("UnknownType"), List.of());

            var result = builder.build();
            assertTrue(result.contains("GDExtensionObjectPtr __gdcc_tmp_discard_0 = fetch_unknown();"),
                    "Should materialize unknown object return into discard temp");
            assertTrue(result.contains("try_release_object(__gdcc_tmp_discard_0);"),
                    "Should try_release discarded unknown object");
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
        @DisplayName("Unknown object assignment should use try_own/try_release")
        void testUnknownObjectAssignmentUsesTry() {
            var target = new LirVariable("obj", new GdObjectType("UnknownType"), lirFunctionDef);
            var source = new LirVariable("src", new GdObjectType("UnknownType"), lirFunctionDef);
            var targetRef = builder.targetOfVar(target);
            var value = builder.valueOfVar(source);

            builder.assignVar(targetRef, value);

            var result = builder.build();
            assertTrue(result.contains("GDExtensionObjectPtr __gdcc_tmp_old_obj_0 = $obj;"),
                    "Should capture old unknown object");
            assertTrue(result.contains("try_release_object(__gdcc_tmp_old_obj_0)"), "Should try_release unknown object");
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

        @Test
        @DisplayName("Variable reads should default to BORROWED ownership")
        void testVarReadDefaultsToBorrowedOwnership() {
            var value = builder.valueOfVar(new LirVariable("obj", new GdObjectType("MyGdccClass"), lirFunctionDef));

            assertEquals(CBodyBuilder.OwnershipKind.BORROWED, value.ownership());
        }

        @Test
        @DisplayName("Raw object expressions should stay BORROWED until a fresh producer marks them OWNED")
        void testExprDefaultsToBorrowedOwnership() {
            var value = builder.valueOfExpr("make_obj()", new GdObjectType("MyGdccClass"), CBodyBuilder.PtrKind.GODOT_PTR);

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
            assertEquals(CBodyBuilder.PtrKind.GODOT_PTR, value.ptrKind());
            assertTrue(
                    value.generateCode().contains("gdcc_object_to_godot_object_ptr($obj, MyGdccClass_object_ptr)"),
                    "Borrowed casted value should still render the ptr conversion helper. Actual:\n" + value.generateCode()
            );
        }

        @Test
        @DisplayName("Owned expression should expose OWNED ownership kind")
        void testOwnedExprOwnershipKind() {
            var value = builder.valueOfOwnedExpr(
                    "owned_ptr",
                    new GdObjectType("MyGdccClass"),
                    CBodyBuilder.PtrKind.GDCC_PTR
            );
            assertEquals(CBodyBuilder.OwnershipKind.OWNED, value.ownership());
        }
    }

    @Nested
    @DisplayName("GDCC Object Argument Conversion Tests")
    class GdccObjectArgConversionTests {

        @Test
        @DisplayName("GDCC object arg should be converted via helper when calling godot_ function")
        void testGdccObjectArgConvertedForGodotFunc() {
            var gdccVar = new LirVariable("myObj", new GdObjectType("MyGdccClass"), lirFunctionDef);
            var value = builder.valueOfVar(gdccVar);

            builder.callVoid("godot_some_func", List.of(value));

            assertEquals("godot_some_func(gdcc_object_to_godot_object_ptr($myObj, MyGdccClass_object_ptr));\n", builder.build());
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
        @DisplayName("valueOfCastedVar should convert GDCC receiver to Godot raw ptr before engine cast")
        void testCastedGdccReceiverToEngineOwnerShouldConvertBeforeCast() {
            var gdccVar = new LirVariable("self", new GdObjectType("MyGdccClass"), lirFunctionDef);
            var casted = builder.valueOfCastedVar(gdccVar, new GdObjectType("Node"));

            builder.callVoid("godot_Node_queue_free", List.of(casted));

            assertEquals("godot_Node_queue_free((godot_Node*)gdcc_object_to_godot_object_ptr($self, MyGdccClass_object_ptr));\n", builder.build());
        }

        @Test
        @DisplayName("valueOfCastedVar should convert Godot raw ptr when casting engine receiver to GDCC type")
        void testCastedEngineReceiverToGdccTypeShouldConvertFromGodotPtr() {
            var engineVar = new LirVariable("node", new GdObjectType("Node"), lirFunctionDef);
            var casted = builder.valueOfCastedVar(engineVar, new GdObjectType("MyGdccClass"));

            builder.callVoid("my_custom_func", List.of(casted));

            assertEquals("my_custom_func((MyGdccClass*)gdcc_object_from_godot_object_ptr($node));\n", builder.build());
        }

        @Test
        @DisplayName("valueOfCastedVar should render GDCC upcast via _super chain")
        void testCastedGdccChildToAncestorShouldUseSuperChain() {
            builder.classRegistry().addGdccClass(new LirClassDef("GdccGrandParent", "RefCounted"));
            builder.classRegistry().addGdccClass(new LirClassDef("GdccParent", "GdccGrandParent"));
            builder.classRegistry().addGdccClass(new LirClassDef("GdccChild", "GdccParent"));
            var childVar = new LirVariable("child", new GdObjectType("GdccChild"), lirFunctionDef);
            var casted = builder.valueOfCastedVar(childVar, new GdObjectType("GdccGrandParent"));

            builder.callVoid("my_custom_func", List.of(casted));

            assertEquals("my_custom_func(&($child->_super._super));\n", builder.build());
        }

        @Test
        @DisplayName("valueOfCastedVar should render canonical inner-class GDCC upcast via _super chain")
        void testCastedCanonicalInnerGdccChildToAncestorShouldUseSuperChain() {
            builder.classRegistry().addGdccClass(new LirClassDef("Outer$GrandParent", "RefCounted"), "GrandParent");
            builder.classRegistry().addGdccClass(new LirClassDef("Outer$Parent", "Outer$GrandParent"), "Parent");
            builder.classRegistry().addGdccClass(new LirClassDef("Outer$Child", "Outer$Parent"), "Child");
            var childVar = new LirVariable("child", new GdObjectType("Outer$Child"), lirFunctionDef);
            var casted = builder.valueOfCastedVar(childVar, new GdObjectType("Outer$GrandParent"));

            builder.callVoid("my_custom_func", List.of(casted));

            assertEquals("my_custom_func(&($child->_super._super));\n", builder.build());
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
            assertTrue(ex.getMessage().contains("safe upcast"), ex.getMessage());
        }

        @Test
        @DisplayName("valueOfCastedVar should reject source-styled inner superclass metadata")
        void testCastedInnerGdccChildWithSourceStyledSuperShouldFailFast() {
            builder.classRegistry().addGdccClass(new LirClassDef("Outer$Parent", "RefCounted"), "Parent");
            builder.classRegistry().addGdccClass(new LirClassDef("Outer$Child", "Parent"), "Child");
            var childVar = new LirVariable("child", new GdObjectType("Outer$Child"), lirFunctionDef);

            var ex = assertThrows(InvalidInsnException.class, () ->
                    builder.valueOfCastedVar(childVar, new GdObjectType("Outer$Parent"))
            );
            assertInstanceOf(InvalidInsnException.class, ex);
            assertTrue(ex.getMessage().contains("safe upcast"), ex.getMessage());
        }

        @Test
        @DisplayName("renderArgument should fail-fast on inconsistent GDCC_PTR and non-GDCC object type")
        void testRenderArgumentShouldRejectInconsistentGdccPtrKind() {
            var mismatched = builder.valueOfExpr("(godot_Node*)$self", new GdObjectType("Node"), CBodyBuilder.PtrKind.GDCC_PTR);
            var ex = assertThrows(InvalidInsnException.class, () -> builder.callVoid("godot_Node_queue_free", List.of(mismatched)));
            assertInstanceOf(InvalidInsnException.class, ex);
            assertTrue(ex.getMessage().contains("ptr kind/type mismatch"), ex.getMessage());
        }

        @Test
        @DisplayName("GDCC object arg should be converted for own_object function")
        void testGdccObjectArgConvertedForOwnObject() {
            var gdccVar = new LirVariable("myObj", new GdObjectType("MyGdccClass"), lirFunctionDef);
            var value = builder.valueOfVar(gdccVar);

            builder.callVoid("try_own_object", List.of(value));

            assertEquals("try_own_object(gdcc_object_to_godot_object_ptr($myObj, MyGdccClass_object_ptr));\n", builder.build());
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

            assertEquals("godot_Object_set(gdcc_object_to_godot_object_ptr($myObj, MyGdccClass_object_ptr), &$name);\n", builder.build());
        }

        @Test
        @DisplayName("GDCC object arg should stay GDCC ptr for gdcc_new_Variant_with_gdcc_Object")
        void testGdccObjectArgNotConvertedForGdccVariantPackFunc() {
            var gdccVar = new LirVariable("myObj", new GdObjectType("MyGdccClass"), lirFunctionDef);

            builder.callVoid("gdcc_new_Variant_with_gdcc_Object", List.of(builder.valueOfVar(gdccVar)));

            assertEquals("gdcc_new_Variant_with_gdcc_Object($myObj);\n", builder.build());
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
        @DisplayName("callAssign with GDCC target from godot_ func should release captured old and consume owned return")
        void testCallAssignGdccTargetOwnRelease() {
            var target = new LirVariable("myObj", new GdObjectType("MyGdccClass"), lirFunctionDef);
            var targetRef = builder.targetOfVar(target);

            builder.callAssign(targetRef, "godot_get_something", new GdObjectType("MyGdccClass"), List.of());

            var result = builder.build();
            // MyGdccClass extends RefCounted; owned call results should not be owned again.
            assertTrue(result.contains("release_object(gdcc_object_to_godot_object_ptr(__gdcc_tmp_old_obj_0, MyGdccClass_object_ptr))"),
                    "Should release captured old GDCC object. Actual:\n" + result);
            assertFalse(result.contains("own_object(gdcc_object_to_godot_object_ptr($myObj, MyGdccClass_object_ptr))"),
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
            assertTrue(result.contains("gdcc_object_to_godot_object_ptr($input, MyGdccClass_object_ptr)"),
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
        @DisplayName("GDCC_PTR value assigned to engine (GODOT_PTR) target should use helper conversion")
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
            assertTrue(result.contains("$obj = gdcc_object_to_godot_object_ptr($myObj, MyGdccClass_object_ptr)"),
                    "Should convert GDCC_PTR to GODOT_PTR via helper conversion. Actual:\n" + result);
        }

        @Test
        @DisplayName("OWNED GDCC_PTR assigned to engine target should convert representation without extra own")
        void testOwnedGdccPtrValueToEngineTargetDoesNotOwnAgain() {
            var target = new LirVariable("rc", new GdObjectType("RefCounted"), lirFunctionDef);
            var targetRef = builder.targetOfVar(target);
            var ownedValue = builder.valueOfOwnedExpr(
                    "fresh_worker()",
                    new GdObjectType("MyGdccClass"),
                    CBodyBuilder.PtrKind.GDCC_PTR
            );

            builder.assignVar(targetRef, ownedValue);

            var result = builder.build();
            assertTrue(
                    result.contains("$rc = gdcc_object_to_godot_object_ptr(fresh_worker(), MyGdccClass_object_ptr);"),
                    "OWNED GDCC_PTR should still convert before storing into engine slot. Actual:\n" + result
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
                    "Should NOT wrap with fromGodotObjectPtr. Actual:\n" + result);
            assertFalse(result.contains("gdcc_object_to_godot_object_ptr($source, MyGdccClass_object_ptr);"),
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
        @DisplayName("GODOT_PTR to GDCC target should still do own/release with helper conversion using old-temp flow")
        void testGodotPtrToGdccTargetOwnRelease() {
            var target = new LirVariable("myObj", new GdObjectType("MyGdccClass"), lirFunctionDef);
            var targetRef = builder.targetOfVar(target);
            var value = builder.valueOfExpr("some_godot_result", new GdObjectType("MyGdccClass"), CBodyBuilder.PtrKind.GODOT_PTR);

            builder.assignVar(targetRef, value);

            var result = builder.build();
            // MyGdccClass extends RefCounted, should have own/release with helper conversion
            assertTrue(result.contains("release_object(gdcc_object_to_godot_object_ptr(__gdcc_tmp_old_obj_0, MyGdccClass_object_ptr))"),
                    "Should release captured old GDCC object via helper conversion. Actual:\n" + result);
            assertTrue(result.contains("own_object(gdcc_object_to_godot_object_ptr($myObj, MyGdccClass_object_ptr))"),
                    "Should own new GDCC object via helper conversion. Actual:\n" + result);
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
        @DisplayName("GDCC_PTR to RefCounted (engine base class) target should use helper conversion")
        void testGdccPtrToRefCountedTarget() {
            // RefCounted is an engine type (GODOT_PTR)
            var target = new LirVariable("rc", new GdObjectType("RefCounted"), lirFunctionDef);
            var targetRef = builder.targetOfVar(target);
            // MyGdccClass extends RefCounted, so assignment is valid
            var source = new LirVariable("myObj", new GdObjectType("MyGdccClass"), lirFunctionDef);
            var value = builder.valueOfVar(source);

            builder.assignVar(targetRef, value);

            var result = builder.build();
            assertTrue(result.contains("$rc = gdcc_object_to_godot_object_ptr($myObj, MyGdccClass_object_ptr)"),
                    "Should convert GDCC_PTR to GODOT_PTR via helper conversion. Actual:\n" + result);
        }

        @Test
        @DisplayName("GODOT_PTR to GDCC target full ordering: capture old → assign with conversion → own → release old")
        void testGodotPtrToGdccTargetFullOrdering() {
            var target = new LirVariable("myObj", new GdObjectType("MyGdccClass"), lirFunctionDef);
            var targetRef = builder.targetOfVar(target);
            var value = builder.valueOfExpr("godot_result", new GdObjectType("MyGdccClass"), CBodyBuilder.PtrKind.GODOT_PTR);

            builder.assignVar(targetRef, value);

            var result = builder.build();
            var captureIndex = result.indexOf("MyGdccClass* __gdcc_tmp_old_obj_0 = $myObj;");
            var assignIndex = result.indexOf("$myObj = (MyGdccClass*)gdcc_object_from_godot_object_ptr(godot_result);");
            var ownIndex = result.indexOf("own_object(gdcc_object_to_godot_object_ptr($myObj, MyGdccClass_object_ptr))");
            var releaseOldIndex = result.indexOf("release_object(gdcc_object_to_godot_object_ptr(__gdcc_tmp_old_obj_0, MyGdccClass_object_ptr));");

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
        @DisplayName("Object-valued property init first-write should convert GODOT_PTR and consume OWNED without own/release")
        void testObjectPropertyInitFirstWriteConsumesOwnedReturn() {
            builder.applyPropertyInitializerFirstWrite(
                    "self->worker",
                    new GdObjectType("MyGdccClass"),
                    "godot_make_worker()",
                    new GdObjectType("MyGdccClass"),
                    CBodyBuilder.PtrKind.GODOT_PTR,
                    CBodyBuilder.OwnershipKind.OWNED
            );

            var result = builder.build();
            assertTrue(
                    result.contains("self->worker = (MyGdccClass*)gdcc_object_from_godot_object_ptr(godot_make_worker());"),
                    "First-write should convert GODOT_PTR helper result before storing. Actual:\n" + result
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
                    CBodyBuilder.PtrKind.GODOT_PTR,
                    CBodyBuilder.OwnershipKind.BORROWED
            );

            var result = builder.build();
            assertTrue(result.contains("self->node_ref = borrowed_ref();"), "Borrowed object result should still assign directly. Actual:\n" + result);
            assertTrue(result.contains("own_object(self->node_ref);"), "Borrowed object result should be retained by the field. Actual:\n" + result);
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
