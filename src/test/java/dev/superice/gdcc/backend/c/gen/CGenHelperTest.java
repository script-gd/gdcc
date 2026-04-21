package dev.superice.gdcc.backend.c.gen;

import dev.superice.gdcc.backend.CodegenContext;
import dev.superice.gdcc.backend.ProjectInfo;
import dev.superice.gdcc.enums.GodotVersion;
import dev.superice.gdcc.exception.InvalidInsnException;
import dev.superice.gdcc.gdextension.ExtensionApiLoader;
import dev.superice.gdcc.lir.LirClassDef;
import dev.superice.gdcc.lir.LirFunctionDef;
import dev.superice.gdcc.lir.LirPropertyDef;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.type.GdArrayType;
import dev.superice.gdcc.type.GdDictionaryType;
import dev.superice.gdcc.type.GdIntType;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdPackedNumericArrayType;
import dev.superice.gdcc.type.GdStringType;
import dev.superice.gdcc.type.GdStringNameType;
import dev.superice.gdcc.type.GdVariantType;
import dev.superice.gdcc.type.GdVoidType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CGenHelperTest {
    private CGenHelper helper;
    private LirFunctionDef function;

    @BeforeEach
    void setUp() throws IOException {
        var projectInfo = new ProjectInfo("TestProject", GodotVersion.V451, Path.of(".")) {
        };
        var extensionApi = ExtensionApiLoader.loadDefault();
        var classRegistry = new ClassRegistry(extensionApi);

        var gdccBase = new LirClassDef("MyBase", "RefCounted");
        var gdccChild = new LirClassDef("MyChild", "MyBase");
        var gdccInner = new LirClassDef("RuntimeOuter__sub__Worker", "MyBase");
        classRegistry.addGdccClass(gdccBase);
        classRegistry.addGdccClass(gdccChild);
        classRegistry.addGdccClass(gdccInner);

        var context = new CodegenContext(projectInfo, classRegistry);
        helper = new CGenHelper(context, List.of(gdccBase, gdccChild, gdccInner));

        function = new LirFunctionDef("test");
    }

    @Test
    @DisplayName("renderVarAssignWithGodotReturn should convert Godot object ptr to GDCC ptr for GDCC target")
    void renderVarAssignWithGodotReturnShouldConvertToGdccPtr() {
        function.createAndAddVariable("target", new GdObjectType("MyBase"));

        var statement = helper.renderVarAssignWithGodotReturn(
                function,
                "target",
                new GdObjectType("MyChild"),
                "godot_api_result()"
        );

        assertEquals("$target = (MyBase*)gdcc_object_from_godot_object_ptr(godot_api_result());", statement);
    }

    @Test
    @DisplayName("renderVarAssignWithGodotReturn should convert GDCC source ptr via helper before engine cast")
    void renderVarAssignWithGodotReturnShouldConvertGdccSourceBeforeEngineCast() {
        function.createAndAddVariable("target", new GdObjectType("RefCounted"));

        var statement = helper.renderVarAssignWithGodotReturn(
                function,
                "target",
                new GdObjectType("MyChild"),
                "$child_expr"
        );

        assertEquals("$target = (godot_RefCounted*)(gdcc_object_to_godot_object_ptr($child_expr, MyChild_object_ptr));", statement);
    }

    @Test
    @DisplayName("renderVarAssignWithGodotReturn should keep same-type engine pointer assignment direct")
    void renderVarAssignWithGodotReturnShouldKeepDirectAssignmentForSameEngineType() {
        function.createAndAddVariable("target", new GdObjectType("Node"));

        var statement = helper.renderVarAssignWithGodotReturn(
                function,
                "target",
                new GdObjectType("Node"),
                "godot_make_node()"
        );

        assertEquals("$target = godot_make_node();", statement);
    }

    @Test
    @DisplayName("renderVarAssignWithGodotReturn should reject readonly ref variable")
    void renderVarAssignWithGodotReturnShouldRejectReadonlyRefVariable() {
        function.createAndAddRefVariable("target", new GdObjectType("Node"));

        var ex = assertThrows(InvalidInsnException.class, () ->
                helper.renderVarAssignWithGodotReturn(
                        function,
                        "target",
                        new GdObjectType("Node"),
                        "godot_make_node()"
                )
        );

        assertInstanceOf(InvalidInsnException.class, ex);
    }

    @Test
    @DisplayName("renderVarAssignWithGodotReturn should reject incompatible assignment")
    void renderVarAssignWithGodotReturnShouldRejectIncompatibleAssignment() {
        function.createAndAddVariable("target", GdIntType.INT);

        var ex = assertThrows(InvalidInsnException.class, () ->
                helper.renderVarAssignWithGodotReturn(
                        function,
                        "target",
                        new GdObjectType("Node"),
                        "godot_make_node()"
                )
        );

        assertInstanceOf(InvalidInsnException.class, ex);
    }

    @Test
    @DisplayName("parseExtensionType should normalize typedarray PackedByteArray to packed type")
    void parseExtensionTypeShouldNormalizeTypedarrayPackedByteArray() {
        var parsed = helper.parseExtensionType(
                "typedarray::PackedByteArray",
                "test typedarray packed parameter"
        );

        assertEquals(GdPackedNumericArrayType.PACKED_BYTE_ARRAY, parsed);
    }

    @Test
    @DisplayName("parseExtensionType should normalize typedarray StringName to Array[StringName]")
    void parseExtensionTypeShouldNormalizeTypedarrayStringName() {
        var parsed = helper.parseExtensionType(
                "typedarray::StringName",
                "test typedarray parameter"
        );

        assertEquals(new GdArrayType(GdStringNameType.STRING_NAME), parsed);
    }

    @Test
    @DisplayName("parseExtensionType should resolve typedarray engine class element through registry")
    void parseExtensionTypeShouldResolveTypedarrayEngineClassElementThroughRegistry() {
        var parsed = helper.parseExtensionType(
                "typedarray::RDPipelineSpecializationConstant",
                "test typedarray engine class parameter"
        );

        assertEquals(new GdArrayType(new GdObjectType("RDPipelineSpecializationConstant")), parsed);
    }

    @Test
    @DisplayName("parseExtensionType should normalize enum and bitfield metadata to int")
    void parseExtensionTypeShouldNormalizeEnumAndBitfield() {
        var enumType = helper.parseExtensionType("enum::Variant.Type", "test enum return type");
        var bitfieldType = helper.parseExtensionType("bitfield::MethodFlags", "test bitfield parameter");

        assertEquals(GdIntType.INT, enumType);
        assertEquals(GdIntType.INT, bitfieldType);
    }

    @Test
    @DisplayName("parseExtensionType should reject malformed typedarray metadata")
    void parseExtensionTypeShouldRejectMalformedTypedarrayMetadata() {
        var ex = assertThrows(
                IllegalArgumentException.class,
                () -> helper.parseExtensionType("typedarray::   ", "test malformed typedarray")
        );

        assertTrue(ex.getMessage().contains("malformed typedarray metadata"), ex.getMessage());
    }

    @Test
    @DisplayName("parseExtensionType should reject unsupported metadata type")
    void parseExtensionTypeShouldRejectUnsupportedMetadataType() {
        var ex = assertThrows(
                IllegalArgumentException.class,
                () -> helper.parseExtensionType("typedarray::Array[]", "test unsupported typedarray")
        );

        assertTrue(ex.getMessage().contains("unsupported type metadata"), ex.getMessage());
    }

    @Test
    @DisplayName("renderBoundMetadata should encode Variant outward slot as NIL")
    void renderBoundMetadataShouldEncodeVariantAsNil() {
        var metadata = helper.renderBoundMetadata(GdVariantType.VARIANT, "godot_PROPERTY_USAGE_DEFAULT");

        assertEquals("GDEXTENSION_VARIANT_TYPE_NIL", metadata.typeEnumLiteral());
    }

    @Test
    @DisplayName("renderBoundMetadata should keep non-Variant outward enum unchanged")
    void renderBoundMetadataShouldKeepNonVariantEnum() {
        var metadata = helper.renderBoundMetadata(GdIntType.INT, "godot_PROPERTY_USAGE_NO_EDITOR");

        assertEquals("GDEXTENSION_VARIANT_TYPE_INT", metadata.typeEnumLiteral());
    }

    @Test
    @DisplayName("renderBoundMetadata should reject void metadata slots")
    void renderBoundMetadataShouldRejectVoidSlot() {
        var ex = assertThrows(
                IllegalArgumentException.class,
                () -> helper.renderBoundMetadata(GdVoidType.VOID, "godot_PROPERTY_USAGE_DEFAULT")
        );

        assertTrue(ex.getMessage().contains("does not have outward GDExtension metadata"), ex.getMessage());
    }

    @Test
    @DisplayName("renderBoundMetadata should add Variant usage flag without rewriting base usage")
    void renderBoundMetadataShouldAddVariantFlag() {
        var metadata = helper.renderBoundMetadata(GdVariantType.VARIANT, "godot_PROPERTY_USAGE_DEFAULT");

        assertEquals("godot_PROPERTY_USAGE_DEFAULT | godot_PROPERTY_USAGE_NIL_IS_VARIANT", metadata.usageExpr());
    }

    @Test
    @DisplayName("renderBoundMetadata should keep non-Variant usage unchanged")
    void renderBoundMetadataShouldKeepNonVariantUsage() {
        var metadata = helper.renderBoundMetadata(GdIntType.INT, "godot_PROPERTY_USAGE_NO_EDITOR");

        assertEquals("godot_PROPERTY_USAGE_NO_EDITOR", metadata.usageExpr());
    }

    @Test
    @DisplayName("renderBoundMetadata should keep default hint metadata for non-typed-dictionary slots")
    void renderBoundMetadataShouldKeepDefaultHintMetadataForNonTypedDictionarySlots() {
        var variantMetadata = helper.renderBoundMetadata(GdVariantType.VARIANT, "godot_PROPERTY_USAGE_DEFAULT");
        var intMetadata = helper.renderBoundMetadata(GdIntType.INT, "godot_PROPERTY_USAGE_DEFAULT");

        assertEquals("godot_PROPERTY_HINT_NONE", variantMetadata.hintEnumLiteral());
        assertEquals("GD_STATIC_S(u8\"\")", variantMetadata.hintStringExpr());
        assertEquals("GD_STATIC_SN(u8\"\")", variantMetadata.classNameExpr());
        assertEquals("godot_PROPERTY_HINT_NONE", intMetadata.hintEnumLiteral());
    }

    @Test
    @DisplayName("renderBoundMetadata should emit typed dictionary hint for object leaf")
    void renderBoundMetadataShouldEmitTypedDictionaryHintForObjectLeaf() {
        var metadata = helper.renderBoundMetadata(
                new GdDictionaryType(GdStringNameType.STRING_NAME, new GdObjectType("Node")),
                "godot_PROPERTY_USAGE_DEFAULT"
        );

        assertEquals("GDEXTENSION_VARIANT_TYPE_DICTIONARY", metadata.typeEnumLiteral());
        assertEquals("godot_PROPERTY_HINT_DICTIONARY_TYPE", metadata.hintEnumLiteral());
        assertEquals("GD_STATIC_S(u8\"StringName;Node\")", metadata.hintStringExpr());
        assertEquals("godot_PROPERTY_USAGE_DEFAULT", metadata.usageExpr());
    }

    @Test
    @DisplayName("renderBoundMetadata should keep Variant atom inside typed dictionary hint")
    void renderBoundMetadataShouldKeepVariantAtomInsideTypedDictionaryHint() {
        var metadata = helper.renderBoundMetadata(
                new GdDictionaryType(GdStringNameType.STRING_NAME, GdVariantType.VARIANT),
                "godot_PROPERTY_USAGE_NO_EDITOR"
        );

        assertEquals("godot_PROPERTY_HINT_DICTIONARY_TYPE", metadata.hintEnumLiteral());
        assertEquals("GD_STATIC_S(u8\"StringName;Variant\")", metadata.hintStringExpr());
        assertEquals("godot_PROPERTY_USAGE_NO_EDITOR", metadata.usageExpr());
    }

    @Test
    @DisplayName("renderBoundMetadata should emit packed array atom inside typed dictionary hint")
    void renderBoundMetadataShouldEmitPackedArrayAtomInsideTypedDictionaryHint() {
        var metadata = helper.renderBoundMetadata(
                new GdDictionaryType(GdVariantType.VARIANT, GdPackedNumericArrayType.PACKED_INT32_ARRAY),
                "godot_PROPERTY_USAGE_DEFAULT"
        );

        assertEquals("godot_PROPERTY_HINT_DICTIONARY_TYPE", metadata.hintEnumLiteral());
        assertEquals("GD_STATIC_S(u8\"Variant;PackedInt32Array\")", metadata.hintStringExpr());
    }

    @Test
    @DisplayName("renderBoundMetadata should keep generic dictionary metadata untyped")
    void renderBoundMetadataShouldKeepGenericDictionaryMetadataUntyped() {
        var metadata = helper.renderBoundMetadata(
                new GdDictionaryType(GdVariantType.VARIANT, GdVariantType.VARIANT),
                "godot_PROPERTY_USAGE_DEFAULT"
        );

        assertEquals("GDEXTENSION_VARIANT_TYPE_DICTIONARY", metadata.typeEnumLiteral());
        assertEquals("godot_PROPERTY_HINT_NONE", metadata.hintEnumLiteral());
        assertEquals("GD_STATIC_S(u8\"\")", metadata.hintStringExpr());
    }

    @Test
    @DisplayName("renderBoundMetadata should emit typed array hint for builtin leaf")
    void renderBoundMetadataShouldEmitTypedArrayHintForBuiltinLeaf() {
        var metadata = helper.renderBoundMetadata(
                new GdArrayType(GdStringNameType.STRING_NAME),
                "godot_PROPERTY_USAGE_DEFAULT",
                "method arg"
        );

        assertEquals("GDEXTENSION_VARIANT_TYPE_ARRAY", metadata.typeEnumLiteral());
        assertEquals("godot_PROPERTY_HINT_ARRAY_TYPE", metadata.hintEnumLiteral());
        assertEquals("GD_STATIC_S(u8\"StringName\")", metadata.hintStringExpr());
        assertEquals("godot_PROPERTY_USAGE_DEFAULT", metadata.usageExpr());
    }

    @Test
    @DisplayName("renderBoundMetadata should emit typed array hint for engine and GDCC object leaf")
    void renderBoundMetadataShouldEmitTypedArrayHintForEngineAndGdccObjectLeaf() {
        var engineMetadata = helper.renderBoundMetadata(
                new GdArrayType(new GdObjectType("Node")),
                "godot_PROPERTY_USAGE_DEFAULT",
                "method return"
        );
        var gdccMetadata = helper.renderBoundMetadata(
                new GdArrayType(new GdObjectType("MyChild")),
                "godot_PROPERTY_USAGE_NO_EDITOR",
                "property"
        );

        assertEquals("godot_PROPERTY_HINT_ARRAY_TYPE", engineMetadata.hintEnumLiteral());
        assertEquals("GD_STATIC_S(u8\"Node\")", engineMetadata.hintStringExpr());
        assertEquals("godot_PROPERTY_HINT_ARRAY_TYPE", gdccMetadata.hintEnumLiteral());
        assertEquals("GD_STATIC_S(u8\"MyChild\")", gdccMetadata.hintStringExpr());
        assertEquals("godot_PROPERTY_USAGE_NO_EDITOR", gdccMetadata.usageExpr());
    }

    @Test
    @DisplayName("renderBoundMetadata should keep inner canonical object leaves verbatim while leaving dormant class slot empty")
    void renderBoundMetadataShouldKeepInnerCanonicalObjectLeavesVerbatimWhileLeavingDormantClassSlotEmpty() {
        var typedArrayMetadata = helper.renderBoundMetadata(
                new GdArrayType(new GdObjectType("RuntimeOuter__sub__Worker")),
                "godot_PROPERTY_USAGE_DEFAULT",
                "property"
        );
        var typedDictionaryMetadata = helper.renderBoundMetadata(
                new GdDictionaryType(new GdObjectType("RuntimeOuter__sub__Worker"), GdVariantType.VARIANT),
                "godot_PROPERTY_USAGE_NO_EDITOR",
                "property"
        );

        assertEquals("godot_PROPERTY_HINT_ARRAY_TYPE", typedArrayMetadata.hintEnumLiteral());
        assertEquals("GD_STATIC_S(u8\"RuntimeOuter__sub__Worker\")", typedArrayMetadata.hintStringExpr());
        assertEquals("GD_STATIC_SN(u8\"\")", typedArrayMetadata.classNameExpr());
        assertEquals("godot_PROPERTY_HINT_DICTIONARY_TYPE", typedDictionaryMetadata.hintEnumLiteral());
        assertEquals("GD_STATIC_S(u8\"RuntimeOuter__sub__Worker;Variant\")", typedDictionaryMetadata.hintStringExpr());
        assertEquals("GD_STATIC_SN(u8\"\")", typedDictionaryMetadata.classNameExpr());
    }

    @Test
    @DisplayName("renderBoundMetadata should emit plain container atoms inside typed array hint")
    void renderBoundMetadataShouldEmitPlainContainerAtomsInsideTypedArrayHint() {
        var arrayMetadata = helper.renderBoundMetadata(
                new GdArrayType(new GdArrayType(GdVariantType.VARIANT)),
                "godot_PROPERTY_USAGE_DEFAULT",
                "method arg"
        );
        var dictionaryMetadata = helper.renderBoundMetadata(
                new GdArrayType(new GdDictionaryType(GdVariantType.VARIANT, GdVariantType.VARIANT)),
                "godot_PROPERTY_USAGE_DEFAULT",
                "method return"
        );

        assertEquals("godot_PROPERTY_HINT_ARRAY_TYPE", arrayMetadata.hintEnumLiteral());
        assertEquals("GD_STATIC_S(u8\"Array\")", arrayMetadata.hintStringExpr());
        assertEquals("godot_PROPERTY_HINT_ARRAY_TYPE", dictionaryMetadata.hintEnumLiteral());
        assertEquals("GD_STATIC_S(u8\"Dictionary\")", dictionaryMetadata.hintStringExpr());
    }

    @Test
    @DisplayName("renderBoundMetadata should keep generic array metadata untyped")
    void renderBoundMetadataShouldKeepGenericArrayMetadataUntyped() {
        var metadata = helper.renderBoundMetadata(
                new GdArrayType(GdVariantType.VARIANT),
                "godot_PROPERTY_USAGE_DEFAULT",
                "method arg"
        );

        assertEquals("GDEXTENSION_VARIANT_TYPE_ARRAY", metadata.typeEnumLiteral());
        assertEquals("godot_PROPERTY_HINT_NONE", metadata.hintEnumLiteral());
        assertEquals("GD_STATIC_S(u8\"\")", metadata.hintStringExpr());
    }

    @Test
    @DisplayName("typed-array guard helpers should only apply to non-generic array slots")
    void typedArrayGuardHelpersShouldOnlyApplyToNonGenericArraySlots() {
        assertTrue(helper.needsTypedArrayCallGuard(new GdArrayType(GdStringNameType.STRING_NAME)));
        assertFalse(helper.needsTypedArrayCallGuard(new GdArrayType(GdVariantType.VARIANT)));
        assertFalse(helper.needsTypedArrayCallGuard(new GdDictionaryType(GdVariantType.VARIANT, GdVariantType.VARIANT)));
    }

    @Test
    @DisplayName("typed-array guard helpers should expose object leaf metadata without backend registry revalidation")
    void typedArrayGuardHelpersShouldExposeObjectLeafMetadata() {
        var typedObjectArray = new GdArrayType(new GdObjectType("Node"));

        assertEquals(
                "(godot_int)GDEXTENSION_VARIANT_TYPE_OBJECT",
                helper.renderTypedArrayGuardBuiltinTypeLiteral(typedObjectArray)
        );
        assertTrue(helper.isTypedArrayGuardObjectLeaf(typedObjectArray));
        assertEquals(
                "GD_STATIC_SN(u8\"Node\")",
                helper.renderTypedArrayGuardClassNameExpr(typedObjectArray)
        );
    }

    @Test
    @DisplayName("typed-array guard helpers should reject generic array slots")
    void typedArrayGuardHelpersShouldRejectGenericArraySlots() {
        var ex = assertThrows(
                IllegalArgumentException.class,
                () -> helper.renderTypedArrayGuardBuiltinTypeLiteral(new GdArrayType(GdVariantType.VARIANT))
        );

        assertEquals(
                "Typed-array guard metadata requested for non-typed Array slot 'Array'",
                ex.getMessage()
        );
    }

    @Test
    @DisplayName("typed-array guard helpers should reject nested typed leaves and missing runtime metadata")
    void typedArrayGuardHelpersShouldRejectUnsupportedLeaves() {
        var nestedArrayEx = assertThrows(
                IllegalArgumentException.class,
                () -> helper.renderTypedArrayGuardBuiltinTypeLiteral(new GdArrayType(new GdArrayType(GdIntType.INT)))
        );
        assertEquals(
                "Unsupported typed-array runtime leaf 'Array[int]' at element leaf: nested typed Array leaf is not supported",
                nestedArrayEx.getMessage()
        );

        var missingMetadataEx = assertThrows(
                IllegalArgumentException.class,
                () -> helper.renderTypedArrayGuardBuiltinTypeLiteral(new GdArrayType(GdVoidType.VOID))
        );
        assertEquals(
                "Unsupported typed-array runtime leaf 'void' at element leaf: missing runtime GDExtension metadata",
                missingMetadataEx.getMessage()
        );
    }

    @Test
    @DisplayName("renderBoundMetadata should reject typed nested array leaf in typed array hint")
    void renderBoundMetadataShouldRejectTypedNestedArrayLeafInTypedArrayHint() {
        var ex = assertThrows(
                IllegalArgumentException.class,
                () -> helper.renderBoundMetadata(
                        new GdArrayType(new GdArrayType(GdIntType.INT)),
                        "godot_PROPERTY_USAGE_DEFAULT",
                        "property"
                )
        );

        assertEquals(
                "Unsupported typed-array outward hint leaf 'Array[int]' at property: nested typed Array leaf is not supported",
                ex.getMessage()
        );
    }

    @Test
    @DisplayName("renderBoundMetadata should reject typed nested dictionary leaf in typed array hint")
    void renderBoundMetadataShouldRejectTypedNestedDictionaryLeafInTypedArrayHint() {
        var ex = assertThrows(
                IllegalArgumentException.class,
                () -> helper.renderBoundMetadata(
                        new GdArrayType(new GdDictionaryType(GdIntType.INT, GdStringType.STRING)),
                        "godot_PROPERTY_USAGE_DEFAULT",
                        "method return"
                )
        );

        assertEquals(
                "Unsupported typed-array outward hint leaf 'Dictionary[int, String]' at method return: nested typed Dictionary leaf is not supported",
                ex.getMessage()
        );
    }

    @Test
    @DisplayName("renderBoundMetadata should reject missing metadata leaf in typed array hint")
    void renderBoundMetadataShouldRejectMissingMetadataLeafInTypedArrayHint() {
        var ex = assertThrows(
                IllegalArgumentException.class,
                () -> helper.renderBoundMetadata(
                        new GdArrayType(GdVoidType.VOID),
                        "godot_PROPERTY_USAGE_DEFAULT",
                        "method return"
                )
        );

        assertEquals(
                "Unsupported typed-array outward hint leaf 'void' at method return: missing outward GDExtension metadata",
                ex.getMessage()
        );
    }

    @Test
    @DisplayName("renderBoundMetadata should reject typed nested array leaf in typed dictionary hint")
    void renderBoundMetadataShouldRejectTypedNestedArrayLeafInTypedDictionaryHint() {
        var ex = assertThrows(
                IllegalArgumentException.class,
                () -> helper.renderBoundMetadata(
                        new GdDictionaryType(GdStringType.STRING, new GdArrayType(GdIntType.INT)),
                        "godot_PROPERTY_USAGE_DEFAULT"
                )
        );

        assertEquals(
                "Unsupported typed-dictionary outward hint leaf 'Array[int]' at value leaf: nested typed Array leaf is not supported",
                ex.getMessage()
        );
    }

    @Test
    @DisplayName("renderBoundMetadata should reject typed nested dictionary leaf in typed dictionary hint")
    void renderBoundMetadataShouldRejectTypedNestedDictionaryLeafInTypedDictionaryHint() {
        var ex = assertThrows(
                IllegalArgumentException.class,
                () -> helper.renderBoundMetadata(
                        new GdDictionaryType(
                                GdStringType.STRING,
                                new GdDictionaryType(GdIntType.INT, GdStringType.STRING)
                        ),
                        "godot_PROPERTY_USAGE_DEFAULT"
                )
        );

        assertEquals(
                "Unsupported typed-dictionary outward hint leaf 'Dictionary[int, String]' at value leaf: nested typed Dictionary leaf is not supported",
                ex.getMessage()
        );
    }

    @Test
    @DisplayName("renderBoundMetadata should reject missing metadata leaf in typed dictionary hint")
    void renderBoundMetadataShouldRejectMissingMetadataLeafInTypedDictionaryHint() {
        var ex = assertThrows(
                IllegalArgumentException.class,
                () -> helper.renderBoundMetadata(
                        new GdDictionaryType(GdVoidType.VOID, GdIntType.INT),
                        "godot_PROPERTY_USAGE_DEFAULT"
                )
        );

        assertEquals(
                "Unsupported typed-dictionary outward hint leaf 'void' at key leaf: missing outward GDExtension metadata",
                ex.getMessage()
        );
    }

    @Test
    @DisplayName("renderPropertyUsageEnum should keep export property visible while marking Variant")
    void renderPropertyUsageEnumShouldKeepExportVariantVisible() {
        var property = new LirPropertyDef("payload", GdVariantType.VARIANT, false, null, null, null, Map.of("export", ""));

        assertEquals(
                "godot_PROPERTY_USAGE_DEFAULT | godot_PROPERTY_USAGE_NIL_IS_VARIANT",
                helper.renderPropertyUsageEnum(property)
        );
    }

    @Test
    @DisplayName("renderPropertyMetadata should encode Variant property as outward NIL")
    void renderPropertyMetadataShouldEncodeVariantPropertyAsNil() {
        var property = new LirPropertyDef("payload", GdVariantType.VARIANT, false, null, null, null, Map.of());

        var metadata = helper.renderPropertyMetadata(property);

        assertEquals("GDEXTENSION_VARIANT_TYPE_NIL", metadata.typeEnumLiteral());
        assertEquals("godot_PROPERTY_USAGE_NO_EDITOR | godot_PROPERTY_USAGE_NIL_IS_VARIANT", metadata.usageExpr());
    }

    @Test
    @DisplayName("renderPropertyUsageEnum should keep non-export Variant property hidden in editor")
    void renderPropertyUsageEnumShouldKeepNonExportVariantHidden() {
        var property = new LirPropertyDef("payload", GdVariantType.VARIANT, false, null, null, null, Map.of());

        assertEquals(
                "godot_PROPERTY_USAGE_NO_EDITOR | godot_PROPERTY_USAGE_NIL_IS_VARIANT",
                helper.renderPropertyUsageEnum(property)
        );
    }

    @Test
    @DisplayName("renderPropertyUsageEnum should preserve export property usage for non-Variant types")
    void renderPropertyUsageEnumShouldPreserveExportNonVariantUsage() {
        var property = new LirPropertyDef("score", GdIntType.INT, false, null, null, null, Map.of("export", ""));

        assertEquals("godot_PROPERTY_USAGE_DEFAULT", helper.renderPropertyUsageEnum(property));
    }

    @Test
    @DisplayName("renderPropertyUsageEnum should preserve non-export property usage for non-Variant types")
    void renderPropertyUsageEnumShouldPreserveNonExportNonVariantUsage() {
        var property = new LirPropertyDef("score", GdIntType.INT, false, null, null, null, Map.of());

        assertEquals("godot_PROPERTY_USAGE_NO_EDITOR", helper.renderPropertyUsageEnum(property));
    }

    @Test
    @DisplayName("renderCallWrapperDestroyStmt should destroy wrapper-owned String locals")
    void renderCallWrapperDestroyStmtShouldDestroyStringLocal() {
        assertEquals(
                "godot_String_destroy(&value);",
                helper.renderCallWrapperDestroyStmt(GdStringType.STRING, "value")
        );
    }

    @Test
    @DisplayName("renderCallWrapperDestroyStmt should destroy wrapper-owned Variant locals")
    void renderCallWrapperDestroyStmtShouldDestroyVariantLocal() {
        assertEquals(
                "godot_Variant_destroy(&value);",
                helper.renderCallWrapperDestroyStmt(GdVariantType.VARIANT, "value")
        );
    }

    @Test
    @DisplayName("renderCallWrapperDestroyStmt should skip object and primitive locals")
    void renderCallWrapperDestroyStmtShouldSkipObjectAndPrimitiveLocals() {
        assertEquals("", helper.renderCallWrapperDestroyStmt(new GdObjectType("Node"), "value"));
        assertEquals("", helper.renderCallWrapperDestroyStmt(GdIntType.INT, "value"));
    }

    @Test
    @DisplayName("typed-dictionary guard helpers should describe object leaf metadata without rendering the whole block")
    void typedDictionaryGuardHelpersShouldDescribeObjectLeafMetadata() {
        var type = new GdDictionaryType(GdStringNameType.STRING_NAME, new GdObjectType("Node"));

        assertTrue(helper.needsTypedDictionaryCallGuard(type));
        assertEquals(
                "(godot_int)GDEXTENSION_VARIANT_TYPE_STRING_NAME",
                helper.renderTypedDictionaryGuardBuiltinTypeLiteral(type, "key")
        );
        assertEquals(
                "(godot_int)GDEXTENSION_VARIANT_TYPE_OBJECT",
                helper.renderTypedDictionaryGuardBuiltinTypeLiteral(type, "value")
        );
        assertFalse(helper.isTypedDictionaryGuardObjectLeaf(type, "key"));
        assertTrue(helper.isTypedDictionaryGuardObjectLeaf(type, "value"));
        assertEquals(
                "GD_STATIC_SN(u8\"Node\")",
                helper.renderTypedDictionaryGuardClassNameExpr(type, "value")
        );
    }

    @Test
    @DisplayName("typed-dictionary guard helpers should skip generic Dictionary slots")
    void typedDictionaryGuardHelpersShouldSkipGenericDictionary() {
        assertFalse(helper.needsTypedDictionaryCallGuard(new GdDictionaryType(GdVariantType.VARIANT, GdVariantType.VARIANT)));
    }

    @Test
    @DisplayName("typed-dictionary guard helpers should reject rendering metadata for generic Dictionary slots")
    void typedDictionaryGuardHelpersShouldRejectGenericDictionaryMetadataRequest() {
        var generic = new GdDictionaryType(GdVariantType.VARIANT, GdVariantType.VARIANT);

        var ex = assertThrows(
                IllegalArgumentException.class,
                () -> helper.renderTypedDictionaryGuardBuiltinTypeLiteral(generic, "key")
        );

        assertTrue(ex.getMessage().contains("non-typed Dictionary slot"), ex.getMessage());
    }

    @Test
    @DisplayName("typed-dictionary guard helpers should reject unknown side names")
    void typedDictionaryGuardHelpersShouldRejectUnknownSideName() {
        var typed = new GdDictionaryType(GdStringNameType.STRING_NAME, new GdObjectType("Node"));

        var ex = assertThrows(
                IllegalArgumentException.class,
                () -> helper.renderTypedDictionaryGuardClassNameExpr(typed, "item")
        );

        assertTrue(ex.getMessage().contains("Unknown typed-dictionary guard side"), ex.getMessage());
    }

    @Test
    @DisplayName("typed container guard helpers should expose inner canonical class names verbatim")
    void typedContainerGuardHelpersShouldExposeInnerCanonicalClassNamesVerbatim() {
        var typedArray = new GdArrayType(new GdObjectType("RuntimeOuter__sub__Worker"));
        var typedDictionary = new GdDictionaryType(GdStringNameType.STRING_NAME, new GdObjectType("RuntimeOuter__sub__Worker"));

        assertTrue(helper.isTypedArrayGuardObjectLeaf(typedArray));
        assertEquals(
                "GD_STATIC_SN(u8\"RuntimeOuter__sub__Worker\")",
                helper.renderTypedArrayGuardClassNameExpr(typedArray)
        );
        assertTrue(helper.isTypedDictionaryGuardObjectLeaf(typedDictionary, "value"));
        assertEquals(
                "GD_STATIC_SN(u8\"RuntimeOuter__sub__Worker\")",
                helper.renderTypedDictionaryGuardClassNameExpr(typedDictionary, "value")
        );
    }
}
