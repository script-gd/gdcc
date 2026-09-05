package gd.script.gdcc.backend.c.gen;

import gd.script.gdcc.backend.CodegenContext;
import gd.script.gdcc.backend.ProjectInfo;
import gd.script.gdcc.backend.c.gen.binding.BindingData;
import gd.script.gdcc.enums.GodotVersion;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.lir.LirClassDef;
import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.lir.LirParameterDef;
import gd.script.gdcc.lir.LirPropertyDef;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdArrayType;
import gd.script.gdcc.type.GdBoolType;
import gd.script.gdcc.type.GdDictionaryType;
import gd.script.gdcc.type.GdccForRangeIterType;
import gd.script.gdcc.type.GdFloatVectorType;
import gd.script.gdcc.type.GdFloatType;
import gd.script.gdcc.type.GdIntVectorType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdNodePathType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdPackedNumericArrayType;
import gd.script.gdcc.type.GdRect2Type;
import gd.script.gdcc.type.GdStringType;
import gd.script.gdcc.type.GdStringNameType;
import gd.script.gdcc.type.GdVariantType;
import gd.script.gdcc.type.GdVoidType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CGenHelperTest {
    private CGenHelper helper;
    private ClassRegistry classRegistry;

    @BeforeEach
    void setUp() throws IOException {
        var projectInfo = new ProjectInfo("TestProject", GodotVersion.V451, Path.of(".")) {
        };
        var extensionApi = ExtensionApiLoader.loadDefault();
        classRegistry = new ClassRegistry(extensionApi);

        var gdccBase = new LirClassDef("MyBase", "RefCounted");
        var gdccChild = new LirClassDef("MyChild", "MyBase");
        var gdccInner = new LirClassDef("RuntimeOuter__sub__Worker", "MyBase");
        classRegistry.addGdccClass(gdccBase);
        classRegistry.addGdccClass(gdccChild);
        classRegistry.addGdccClass(gdccInner);

        var context = new CodegenContext(projectInfo, classRegistry);
        helper = new CGenHelper(context, List.of(gdccBase, gdccChild, gdccInner));
    }

    @Test
    @DisplayName("compiler-only range iterator should use gdcc storage helpers and reject Variant helpers")
    void compilerOnlyRangeIteratorShouldUseGdccHelpersAndRejectVariantHelpers() {
        var type = GdccForRangeIterType.FOR_RANGE_ITER;

        assertEquals("gdcc_for_range_iter", helper.renderGdTypeInC(type));
        assertEquals("gdcc_for_range_iter*", helper.renderGdTypeRefInC(type));
        assertEquals("GdccForRangeIter", helper.renderGdTypeName(type));
        assertEquals("gdcc_for_range_iter_init", helper.renderCompilerOnlyInitFunctionName(type));
        assertEquals("", helper.renderCopyAssignFunctionName(type));
        assertEquals("gdcc_for_range_iter_destroy", helper.renderDestroyFunctionName(type));
        assertFalse(helper.renderGdTypeInC(type).contains("godot_"));
        assertFalse(helper.renderDestroyFunctionName(type).contains("godot_"));
        assertThrows(IllegalArgumentException.class, () -> helper.renderPackFunctionName(type));
        assertThrows(IllegalArgumentException.class, () -> helper.renderUnpackFunctionName(type));
    }

    @Test
    @DisplayName("compiler-only helper rendering should keep explicit direct-assignment contract")
    void compilerOnlyHelperRenderingShouldKeepExplicitDirectAssignmentContract() {
        var type = GdccForRangeIterType.FOR_RANGE_ITER;
        assertTrue(type.isDirectStructAssignmentSafe(), "existing compiler-only type should stay on direct assignment path");
        assertEquals("", helper.renderCopyAssignFunctionName(type), "consumer should still observe the explicit direct-assignment contract");
    }

    @Test
    @DisplayName("checkVirtualMethod should accept exact engine virtual signatures")
    void checkVirtualMethodShouldAcceptExactEngineVirtualSignatures() {
        var processWorker = new LirClassDef("ProcessWorker", "Node");
        var process = new LirFunctionDef("_process");
        process.setReturnType(GdVoidType.VOID);
        process.addParameter(new LirParameterDef("delta", GdFloatType.FLOAT, null, process));
        processWorker.addFunction(process);
        classRegistry.addGdccClass(processWorker);

        assertTrue(helper.checkVirtualMethod(processWorker, process));
    }

    @Test
    @DisplayName("checkVirtualMethod should ignore backend synthetic self parameter when matching engine virtuals")
    void checkVirtualMethodShouldIgnoreBackendSyntheticSelfParameterWhenMatchingEngineVirtuals() {
        var readyWorker = new LirClassDef("ReadyWorker", "Node");
        var ready = new LirFunctionDef("_ready");
        ready.setReturnType(GdVoidType.VOID);
        ready.addParameter(new LirParameterDef("self", new GdObjectType("ReadyWorker"), null, ready));
        readyWorker.addFunction(ready);
        classRegistry.addGdccClass(readyWorker);

        assertTrue(helper.checkVirtualMethod(readyWorker, ready));
    }

    @Test
    @DisplayName("checkVirtualMethod should reject wrong engine virtual signatures even when the name matches")
    void checkVirtualMethodShouldRejectWrongEngineVirtualSignaturesEvenWhenTheNameMatches() {
        var processWorker = new LirClassDef("InvalidProcessWorker", "Node");
        var process = new LirFunctionDef("_process");
        process.setReturnType(GdVoidType.VOID);
        processWorker.addFunction(process);
        classRegistry.addGdccClass(processWorker);

        assertFalse(helper.checkVirtualMethod(processWorker, process));
    }

    @Test
    @DisplayName("checkVirtualMethod should still find the engine contract behind gdcc abstract shadow declarations")
    void checkVirtualMethodShouldStillFindTheEngineContractBehindGdccAbstractShadowDeclarations() {
        var shadowBase = new LirClassDef("ShadowBase", "Node");
        var abstractProcess = new LirFunctionDef("_process");
        abstractProcess.setAbstract(true);
        abstractProcess.setReturnType(GdVoidType.VOID);
        abstractProcess.addParameter(new LirParameterDef("delta", GdVariantType.VARIANT, null, abstractProcess));
        shadowBase.addFunction(abstractProcess);
        classRegistry.addGdccClass(shadowBase);

        var shadowChild = new LirClassDef("ShadowChild", "ShadowBase");
        var concreteProcess = new LirFunctionDef("_process");
        concreteProcess.setReturnType(GdVoidType.VOID);
        concreteProcess.addParameter(new LirParameterDef("delta", GdFloatType.FLOAT, null, concreteProcess));
        shadowChild.addFunction(concreteProcess);
        classRegistry.addGdccClass(shadowChild);

        assertTrue(helper.checkVirtualMethod(shadowChild, concreteProcess));
        assertFalse(helper.checkVirtualMethod(shadowBase, abstractProcess));
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
    @DisplayName("renderBoundMetadata should reject compiler-only metadata slots")
    void renderBoundMetadataShouldRejectCompilerOnlySlot() {
        var ex = assertThrows(
                IllegalArgumentException.class,
                () -> helper.renderBoundMetadata(GdccForRangeIterType.FOR_RANGE_ITER, "godot_PROPERTY_USAGE_DEFAULT")
        );

        assertEquals(
                "compiler-only type leaked into bound slot metadata: GdccForRangeIter",
                ex.getMessage()
        );
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
    @DisplayName("renderPropertyMetadata should keep export property visible while marking Variant")
    void renderPropertyMetadataShouldKeepExportVariantVisible() {
        var property = new LirPropertyDef("payload", GdVariantType.VARIANT, false, null, null, null, Map.of("export", ""));

        assertEquals(
                "godot_PROPERTY_USAGE_DEFAULT | godot_PROPERTY_USAGE_NIL_IS_VARIANT",
                helper.renderPropertyMetadata(property).usageExpr()
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
    @DisplayName("renderPropertyMetadata should keep non-export Variant property hidden in editor")
    void renderPropertyMetadataShouldKeepNonExportVariantHidden() {
        var property = new LirPropertyDef("payload", GdVariantType.VARIANT, false, null, null, null, Map.of());

        assertEquals(
                "godot_PROPERTY_USAGE_NO_EDITOR | godot_PROPERTY_USAGE_NIL_IS_VARIANT",
                helper.renderPropertyMetadata(property).usageExpr()
        );
    }

    @Test
    @DisplayName("renderPropertyMetadata should preserve export property usage for non-Variant types")
    void renderPropertyMetadataShouldPreserveExportNonVariantUsage() {
        var property = new LirPropertyDef("score", GdIntType.INT, false, null, null, null, Map.of("export", ""));

        assertEquals("godot_PROPERTY_USAGE_DEFAULT", helper.renderPropertyMetadata(property).usageExpr());
    }

    @Test
    @DisplayName("renderPropertyMetadata should preserve non-export property usage for non-Variant types")
    void renderPropertyMetadataShouldPreserveNonExportNonVariantUsage() {
        var property = new LirPropertyDef("score", GdIntType.INT, false, null, null, null, Map.of());

        assertEquals("godot_PROPERTY_USAGE_NO_EDITOR", helper.renderPropertyMetadata(property).usageExpr());
    }

    @Test
    @DisplayName("renderPropertyMetadata should render every export variant hint from the annotation value")
    void renderPropertyMetadataShouldRenderEveryExportVariantHint() {
        // Key order mirrors the fixed backend selection order (export_range first); every variant
        // drives hint/hint_string from its annotation value and forces PROPERTY_USAGE_DEFAULT.
        var expectations = new LinkedHashMap<String, String>();
        expectations.put("export_range", "godot_PROPERTY_HINT_RANGE");
        expectations.put("export_enum", "godot_PROPERTY_HINT_ENUM");
        expectations.put("export_flags", "godot_PROPERTY_HINT_FLAGS");
        expectations.put("export_flags_2d_render", "godot_PROPERTY_HINT_LAYERS_2D_RENDER");
        expectations.put("export_flags_2d_physics", "godot_PROPERTY_HINT_LAYERS_2D_PHYSICS");
        expectations.put("export_flags_2d_navigation", "godot_PROPERTY_HINT_LAYERS_2D_NAVIGATION");
        expectations.put("export_flags_3d_render", "godot_PROPERTY_HINT_LAYERS_3D_RENDER");
        expectations.put("export_flags_3d_physics", "godot_PROPERTY_HINT_LAYERS_3D_PHYSICS");
        expectations.put("export_flags_3d_navigation", "godot_PROPERTY_HINT_LAYERS_3D_NAVIGATION");
        expectations.put("export_flags_avoidance", "godot_PROPERTY_HINT_LAYERS_AVOIDANCE");
        expectations.put("export_file", "godot_PROPERTY_HINT_FILE");
        expectations.put("export_file_path", "godot_PROPERTY_HINT_FILE_PATH");
        expectations.put("export_dir", "godot_PROPERTY_HINT_DIR");
        expectations.put("export_global_file", "godot_PROPERTY_HINT_GLOBAL_FILE");
        expectations.put("export_global_dir", "godot_PROPERTY_HINT_GLOBAL_DIR");
        expectations.put("export_multiline", "godot_PROPERTY_HINT_MULTILINE_TEXT");
        expectations.put("export_placeholder", "godot_PROPERTY_HINT_PLACEHOLDER_TEXT");
        expectations.put("export_exp_easing", "godot_PROPERTY_HINT_EXP_EASING");
        expectations.put("export_color_no_alpha", "godot_PROPERTY_HINT_COLOR_NO_ALPHA");
        expectations.put("export_node_path", "godot_PROPERTY_HINT_NODE_PATH_VALID_TYPES");

        for (var expectation : expectations.entrySet()) {
            var property = new LirPropertyDef(
                    "value",
                    GdIntType.INT,
                    false,
                    null,
                    null,
                    null,
                    Map.of(expectation.getKey(), "1,2")
            );
            var metadata = helper.renderPropertyMetadata(property);
            assertEquals(expectation.getValue(), metadata.hintEnumLiteral(), expectation.getKey());
            assertEquals("GD_STATIC_S(u8\"1,2\")", metadata.hintStringExpr(), expectation.getKey());
            assertEquals("godot_PROPERTY_USAGE_DEFAULT", metadata.usageExpr(), expectation.getKey());
            assertEquals("GD_STATIC_SN(u8\"\")", metadata.classNameExpr(), expectation.getKey());
        }
    }

    @Test
    @DisplayName("renderPropertyMetadata should escape variant hint_string through the string literal channel")
    void renderPropertyMetadataShouldEscapeVariantHintString() {
        // `export_placeholder` carries arbitrary prose: quotes/backslashes must survive the
        // escapeStringLiteral channel so the emitted C string literal stays valid.
        var property = new LirPropertyDef(
                "prompt",
                GdStringType.STRING,
                false,
                null,
                null,
                null,
                Map.of("export_placeholder", "say \"hi\"\nhere")
        );

        var metadata = helper.renderPropertyMetadata(property);

        assertEquals("godot_PROPERTY_HINT_PLACEHOLDER_TEXT", metadata.hintEnumLiteral());
        assertEquals("GD_STATIC_S(u8\"say \\\"hi\\\"\\nhere\")", metadata.hintStringExpr());
    }

    @Test
    @DisplayName("renderPropertyMetadata should render undetermined Variant property with variant key")
    void renderPropertyMetadataShouldRenderVariantTypedPropertyWithVariantKey() {
        var property = new LirPropertyDef(
                "value",
                GdVariantType.VARIANT,
                false,
                null,
                null,
                null,
                Map.of("export_range", "0,1")
        );

        var metadata = helper.renderPropertyMetadata(property);

        assertEquals("GDEXTENSION_VARIANT_TYPE_NIL", metadata.typeEnumLiteral());
        assertEquals("godot_PROPERTY_USAGE_DEFAULT | godot_PROPERTY_USAGE_NIL_IS_VARIANT", metadata.usageExpr());
        assertEquals("godot_PROPERTY_HINT_RANGE", metadata.hintEnumLiteral());
        assertEquals("GD_STATIC_S(u8\"0,1\")", metadata.hintStringExpr());
    }

    @Test
    @DisplayName("renderPropertyMetadata should prefer export variant over bare export")
    void renderPropertyMetadataShouldPreferVariantKeyOverBareExport() {
        var property = new LirPropertyDef(
                "value",
                GdIntType.INT,
                false,
                null,
                null,
                null,
                Map.of("export", "", "export_range", "0,100")
        );

        var metadata = helper.renderPropertyMetadata(property);

        assertEquals("godot_PROPERTY_HINT_RANGE", metadata.hintEnumLiteral());
        assertEquals("GD_STATIC_S(u8\"0,100\")", metadata.hintStringExpr());
        assertEquals("godot_PROPERTY_USAGE_DEFAULT", metadata.usageExpr());
    }

    @Test
    @DisplayName("renderPropertyMetadata should follow the fixed variant order regardless of map iteration order")
    void renderPropertyMetadataShouldFollowFixedVariantOrderRegardlessOfMapOrder() {
        // `LirPropertyDef.annotations` is a HashMap: selection must follow the fixed priority
        // order (export_range before export_enum), never the map's iteration order.
        var property = new LirPropertyDef(
                "value",
                GdIntType.INT,
                false,
                null,
                null,
                null,
                Map.of("export_enum", "A,B", "export_range", "0,100")
        );

        var metadata = helper.renderPropertyMetadata(property);

        assertEquals("godot_PROPERTY_HINT_RANGE", metadata.hintEnumLiteral());
        assertEquals("GD_STATIC_S(u8\"0,100\")", metadata.hintStringExpr());
    }

    @Test
    @DisplayName("renderPropertyMetadata should keep typed array hint for bare export")
    void renderPropertyMetadataShouldKeepTypedArrayHintForBareExport() {
        var property = new LirPropertyDef(
                "values",
                new GdArrayType(GdIntType.INT),
                false,
                null,
                null,
                null,
                Map.of("export", "")
        );

        var metadata = helper.renderPropertyMetadata(property);

        assertEquals("godot_PROPERTY_HINT_ARRAY_TYPE", metadata.hintEnumLiteral());
        assertEquals("GD_STATIC_S(u8\"int\")", metadata.hintStringExpr());
        assertEquals("godot_PROPERTY_USAGE_DEFAULT", metadata.usageExpr());
    }

    @Test
    @DisplayName("renderPropertyMetadata should render resource hint for bare export Resource-derived object")
    void renderPropertyMetadataShouldRenderResourceHintForBareExportObject() {
        var property = new LirPropertyDef(
                "texture",
                new GdObjectType("Texture2D"),
                false,
                null,
                null,
                null,
                Map.of("export", "")
        );

        var metadata = helper.renderPropertyMetadata(property);

        assertEquals("GDEXTENSION_VARIANT_TYPE_OBJECT", metadata.typeEnumLiteral());
        assertEquals("godot_PROPERTY_HINT_RESOURCE_TYPE", metadata.hintEnumLiteral());
        assertEquals("GD_STATIC_S(u8\"Texture2D\")", metadata.hintStringExpr());
        assertEquals("GD_STATIC_SN(u8\"Texture2D\")", metadata.classNameExpr());
        assertEquals("godot_PROPERTY_USAGE_DEFAULT", metadata.usageExpr());
    }

    @Test
    @DisplayName("renderPropertyMetadata should render node hint for bare export Node-derived object")
    void renderPropertyMetadataShouldRenderNodeHintForBareExportObject() {
        var property = new LirPropertyDef(
                "target",
                new GdObjectType("Node2D"),
                false,
                null,
                null,
                null,
                Map.of("export", "")
        );

        var metadata = helper.renderPropertyMetadata(property);

        assertEquals("GDEXTENSION_VARIANT_TYPE_OBJECT", metadata.typeEnumLiteral());
        assertEquals("godot_PROPERTY_HINT_NODE_TYPE", metadata.hintEnumLiteral());
        assertEquals("GD_STATIC_S(u8\"Node2D\")", metadata.hintStringExpr());
        assertEquals("GD_STATIC_SN(u8\"Node2D\")", metadata.classNameExpr());
        assertEquals("godot_PROPERTY_USAGE_DEFAULT", metadata.usageExpr());
    }

    @Test
    @DisplayName("renderPropertyMetadata should keep non-export Object property on default metadata")
    void renderPropertyMetadataShouldKeepNonExportObjectOnDefaults() {
        var property = new LirPropertyDef(
                "texture",
                new GdObjectType("Texture2D"),
                false,
                null,
                null,
                null,
                Map.of()
        );

        var metadata = helper.renderPropertyMetadata(property);

        assertEquals("godot_PROPERTY_USAGE_NO_EDITOR", metadata.usageExpr());
        assertEquals("godot_PROPERTY_HINT_NONE", metadata.hintEnumLiteral());
        assertEquals("GD_STATIC_SN(u8\"\")", metadata.classNameExpr());
    }

    @Test
    @DisplayName("renderPropertyMetadata should stay silent for non-exportable Object family rejected upstream")
    void renderPropertyMetadataShouldStaySilentForNonExportableObjectFamily() {
        // The frontend export validation rejects bare `RefCounted` exports; backend deliberately
        // does not re-diagnose and falls back to the plain type-derived surface.
        var property = new LirPropertyDef(
                "obj",
                new GdObjectType("RefCounted"),
                false,
                null,
                null,
                null,
                Map.of("export", "")
        );

        var metadata = helper.renderPropertyMetadata(property);

        assertEquals("godot_PROPERTY_USAGE_DEFAULT", metadata.usageExpr());
        assertEquals("godot_PROPERTY_HINT_NONE", metadata.hintEnumLiteral());
        assertEquals("GD_STATIC_SN(u8\"\")", metadata.classNameExpr());
    }

    @Test
    @DisplayName("renderSignalParameterMetadata should use method-arg usage rather than property usage")
    void renderSignalParameterMetadataShouldUseMethodArgUsage() {
        var intMetadata = helper.renderSignalParameterMetadata(GdIntType.INT);
        var variantMetadata = helper.renderSignalParameterMetadata(GdVariantType.VARIANT);

        assertEquals("godot_PROPERTY_USAGE_DEFAULT", intMetadata.usageExpr());
        assertEquals(
                "godot_PROPERTY_USAGE_DEFAULT | godot_PROPERTY_USAGE_NIL_IS_VARIANT",
                variantMetadata.usageExpr()
        );
        assertNotEquals(
                helper.renderPropertyMetadata(new LirPropertyDef(
                        "payload",
                        GdIntType.INT,
                        false,
                        null,
                        null,
                        null,
                        Map.of()
                )).usageExpr(),
                intMetadata.usageExpr()
        );
    }

    @Test
    @DisplayName("renderSignalParameterMetadata should keep Object class_name on the empty default")
    void renderSignalParameterMetadataShouldKeepEmptyObjectClassName() {
        var metadata = helper.renderSignalParameterMetadata(new GdObjectType("Node"));

        assertEquals("GDEXTENSION_VARIANT_TYPE_OBJECT", metadata.typeEnumLiteral());
        assertEquals("GD_STATIC_SN(u8\"\")", metadata.classNameExpr());
        assertEquals("godot_PROPERTY_HINT_NONE", metadata.hintEnumLiteral());
        assertEquals("godot_PROPERTY_USAGE_DEFAULT", metadata.usageExpr());
    }

    @Test
    @DisplayName("renderSignalParameterMetadata should emit typed container hints")
    void renderSignalParameterMetadataShouldEmitTypedContainerHints() {
        var arrayMetadata = helper.renderSignalParameterMetadata(new GdArrayType(GdStringNameType.STRING_NAME));
        var dictionaryMetadata = helper.renderSignalParameterMetadata(
                new GdDictionaryType(GdStringNameType.STRING_NAME, new GdObjectType("Node"))
        );

        assertEquals("godot_PROPERTY_HINT_ARRAY_TYPE", arrayMetadata.hintEnumLiteral());
        assertEquals("GD_STATIC_S(u8\"StringName\")", arrayMetadata.hintStringExpr());
        assertEquals("godot_PROPERTY_HINT_DICTIONARY_TYPE", dictionaryMetadata.hintEnumLiteral());
        assertEquals("GD_STATIC_S(u8\"StringName;Node\")", dictionaryMetadata.hintStringExpr());
        assertEquals("GD_STATIC_SN(u8\"\")", arrayMetadata.classNameExpr());
        assertEquals("GD_STATIC_SN(u8\"\")", dictionaryMetadata.classNameExpr());
    }

    @Test
    @DisplayName("renderSignalParameterMetadata should reject compiler-only types")
    void renderSignalParameterMetadataShouldRejectCompilerOnlyType() {
        var ex = assertThrows(
                IllegalArgumentException.class,
                () -> helper.renderSignalParameterMetadata(GdccForRangeIterType.FOR_RANGE_ITER)
        );

        assertEquals(
                "compiler-only type leaked into signal parameter metadata: GdccForRangeIter",
                ex.getMessage()
        );
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
        assertEquals("", helper.renderCallWrapperDestroyStmt(new GdObjectType("RefCounted"), "value"));
        assertEquals("", helper.renderCallWrapperDestroyStmt(GdIntType.INT, "value"));
    }

    @Test
    @DisplayName("renderCallWrapperOwnedObjectReturnConsumeStmt should release RefCounted and try-release Object")
    void renderCallWrapperOwnedObjectReturnConsumeStmtShouldReleaseRefCountedReturnsOnly() {
        assertEquals(
                "release_object(gdcc_RefCounted_fat_ptr_live_object(value));",
                helper.renderCallWrapperOwnedObjectReturnConsumeStmt(new GdObjectType("RefCounted"), "value")
        );
        assertEquals(
                "release_object(gdcc_MyChild_fat_ptr_live_object(value));",
                helper.renderCallWrapperOwnedObjectReturnConsumeStmt(new GdObjectType("MyChild"), "value")
        );
        // Exact Object is UNKNOWN: may hold RC instances, so consume uses try_release with the
        // cached instance_id driving the runtime reference-bit check.
        assertEquals(
                "try_release_object(gdcc_Object_fat_ptr_live_object(value), value.instance_id);",
                helper.renderCallWrapperOwnedObjectReturnConsumeStmt(new GdObjectType("Object"), "value")
        );
        assertEquals("", helper.renderCallWrapperOwnedObjectReturnConsumeStmt(new GdObjectType("Node"), "value"));
        assertEquals("", helper.renderCallWrapperOwnedObjectReturnConsumeStmt(GdStringType.STRING, "value"));
    }

    @Test
    @DisplayName("renderEngineMethodHelperVarargObjectReturnOwnStmt should own RefCounted and try-own Object")
    void renderEngineMethodHelperVarargObjectReturnOwnStmtShouldOwnRefCountedReturnsOnly() {
        assertEquals(
                "own_object(gdcc_RefCounted_fat_ptr_live_object(result));",
                helper.renderEngineMethodHelperVarargObjectReturnOwnStmt(new GdObjectType("RefCounted"), "result")
        );
        // Exact Object is UNKNOWN: may hold RC instances, so the vararg return own uses try_own with
        // the cached instance_id driving the runtime reference-bit check.
        assertEquals(
                "try_own_object(gdcc_Object_fat_ptr_live_object(result), result.instance_id);",
                helper.renderEngineMethodHelperVarargObjectReturnOwnStmt(new GdObjectType("Object"), "result")
        );
        assertEquals("", helper.renderEngineMethodHelperVarargObjectReturnOwnStmt(new GdObjectType("Node"), "result"));
        assertEquals("", helper.renderEngineMethodHelperVarargObjectReturnOwnStmt(GdStringType.STRING, "result"));
    }

    @Test
    @DisplayName("call wrapper helpers should reject compiler-only types")
    void callWrapperHelpersShouldRejectCompilerOnlyTypes() {
        var typeGateEx = assertThrows(
                IllegalArgumentException.class,
                () -> helper.renderCallWrapperVariantTypeGate(GdccForRangeIterType.FOR_RANGE_ITER, "type")
        );
        assertEquals(
                "compiler-only type leaked into call wrapper type gate: GdccForRangeIter",
                typeGateEx.getMessage()
        );

        var unpackEx = assertThrows(
                IllegalArgumentException.class,
                () -> helper.renderCallWrapperUnpackExpr(GdccForRangeIterType.FOR_RANGE_ITER, "value_ptr", "value_type")
        );
        assertEquals(
                "compiler-only type leaked into call wrapper unpack expression: GdccForRangeIter",
                unpackEx.getMessage()
        );

        var destroyEx = assertThrows(
                IllegalArgumentException.class,
                () -> helper.renderCallWrapperDestroyStmt(GdccForRangeIterType.FOR_RANGE_ITER, "value")
        );
        assertEquals(
                "compiler-only type leaked into call wrapper destroy stmt: GdccForRangeIter",
                destroyEx.getMessage()
        );
    }

    @Test
    @DisplayName("call wrapper type gate should keep narrow primitive widening rules")
    void renderCallWrapperVariantTypeGateShouldKeepNarrowPrimitiveWideningRules() {
        assertEquals(
                "(type == GDEXTENSION_VARIANT_TYPE_FLOAT || type == GDEXTENSION_VARIANT_TYPE_INT)",
                helper.renderCallWrapperVariantTypeGate(GdFloatType.FLOAT, "type")
        );
        assertEquals(
                "(type == GDEXTENSION_VARIANT_TYPE_INT)",
                helper.renderCallWrapperVariantTypeGate(GdIntType.INT, "type")
        );
        assertFalse(helper.renderCallWrapperVariantTypeGate(GdFloatType.FLOAT, "type").contains("BOOL"));
    }

    @Test
    @DisplayName("call wrapper type gate should accept matching vectori payloads only for vector params")
    void renderCallWrapperVariantTypeGateShouldAcceptMatchingVectorIPayloadsOnlyForVectorParams() {
        assertEquals(
                "(type == GDEXTENSION_VARIANT_TYPE_VECTOR2 || type == GDEXTENSION_VARIANT_TYPE_VECTOR2I)",
                helper.renderCallWrapperVariantTypeGate(GdFloatVectorType.VECTOR2, "type")
        );
        assertEquals(
                "(type == GDEXTENSION_VARIANT_TYPE_VECTOR3 || type == GDEXTENSION_VARIANT_TYPE_VECTOR3I)",
                helper.renderCallWrapperVariantTypeGate(GdFloatVectorType.VECTOR3, "type")
        );
        assertEquals(
                "(type == GDEXTENSION_VARIANT_TYPE_VECTOR4 || type == GDEXTENSION_VARIANT_TYPE_VECTOR4I)",
                helper.renderCallWrapperVariantTypeGate(GdFloatVectorType.VECTOR4, "type")
        );

        var vector3iGate = helper.renderCallWrapperVariantTypeGate(GdIntVectorType.VECTOR3I, "type");
        assertEquals("(type == GDEXTENSION_VARIANT_TYPE_VECTOR3I)", vector3iGate);
        assertFalse(vector3iGate.contains("VECTOR3 ||"), vector3iGate);

        var rect2Gate = helper.renderCallWrapperVariantTypeGate(GdRect2Type.RECT2, "type");
        assertEquals("(type == GDEXTENSION_VARIANT_TYPE_RECT2)", rect2Gate);
        assertFalse(rect2Gate.contains("RECT2I"), rect2Gate);
    }

    @Test
    @DisplayName("call wrapper type gate should accept string-family payloads only for string targets")
    void renderCallWrapperVariantTypeGateShouldAcceptStringFamilyPayloadsOnlyForStringTargets() {
        assertEquals(
                "(type == GDEXTENSION_VARIANT_TYPE_STRING_NAME || type == GDEXTENSION_VARIANT_TYPE_STRING)",
                helper.renderCallWrapperVariantTypeGate(GdStringNameType.STRING_NAME, "type")
        );
        assertEquals(
                "(type == GDEXTENSION_VARIANT_TYPE_STRING || type == GDEXTENSION_VARIANT_TYPE_STRING_NAME)",
                helper.renderCallWrapperVariantTypeGate(GdStringType.STRING, "type")
        );

        var nodePathGate = helper.renderCallWrapperVariantTypeGate(GdNodePathType.NODE_PATH, "type");
        assertEquals("(type == GDEXTENSION_VARIANT_TYPE_NODE_PATH)", nodePathGate);
        assertFalse(nodePathGate.contains("STRING"), nodePathGate);
    }

    @Test
    @DisplayName("call wrapper type gate should accept NIL payloads for object params")
    void renderCallWrapperVariantTypeGateShouldAcceptNilForObjectParams() {
        var objectType = new GdObjectType("Node");
        var objectGate = helper.renderCallWrapperVariantTypeGate(objectType, "type");
        assertEquals(
                "(type == GDEXTENSION_VARIANT_TYPE_OBJECT || type == GDEXTENSION_VARIANT_TYPE_NIL)",
                objectGate
        );
        assertFalse(objectGate.contains("DICTIONARY"), objectGate);
    }

    @Test
    @DisplayName("call wrapper unpack should emit fat_ptr_from_variant for object params")
    void renderCallWrapperUnpackExprShouldEmitFatPtrFromVariantForObjectParams() {
        var objectType = new GdObjectType("Node");
        var unpack = helper.renderCallWrapperUnpackExpr(objectType, "p_args[0]", "arg0_type");
        assertTrue(unpack.contains("_fat_ptr_from_variant"), unpack);
        assertTrue(unpack.contains("p_args[0]"), unpack);
    }

    @Test
    @DisplayName("call wrapper vector widening should reject non builtin vector dimensions")
    void renderCallWrapperVectorWideningShouldRejectNonBuiltinVectorDimensions() {
        var invalidVector = new GdFloatVectorType(5);

        assertThrows(IllegalStateException.class,
                () -> helper.renderCallWrapperVariantTypeGate(invalidVector, "type"));
        assertThrows(IllegalStateException.class,
                () -> helper.renderCallWrapperUnpackExpr(invalidVector, "value_ptr", "value_type"));
    }

    @Test
    @DisplayName("call wrapper unpack should cast int payloads only for float params")
    void renderCallWrapperUnpackExprShouldCastIntPayloadsOnlyForFloatParams() {
        assertEquals(
                "value_type == GDEXTENSION_VARIANT_TYPE_INT"
                        + " ? (godot_float)godot_new_int_with_Variant(value_ptr)"
                        + " : godot_new_float_with_Variant(value_ptr)",
                helper.renderCallWrapperUnpackExpr(GdFloatType.FLOAT, "value_ptr", "value_type")
        );
        assertEquals(
                "godot_variant_get_type(value_ptr) == GDEXTENSION_VARIANT_TYPE_INT"
                        + " ? (godot_float)godot_new_int_with_Variant(value_ptr)"
                        + " : godot_new_float_with_Variant(value_ptr)",
                helper.renderCallWrapperUnpackExpr(GdFloatType.FLOAT, "value_ptr", null)
        );
        assertEquals(
                "godot_new_int_with_Variant(value_ptr)",
                helper.renderCallWrapperUnpackExpr(GdIntType.INT, "value_ptr", null)
        );
    }

    @Test
    @DisplayName("call wrapper unpack should route vector params through vector widening helper only")
    void renderCallWrapperUnpackExprShouldRouteVectorParamsThroughVectorWideningHelperOnly() {
        assertEquals(
                "gdcc_new_Vector2_from_call_arg_variant(value_ptr, value_type)",
                helper.renderCallWrapperUnpackExpr(GdFloatVectorType.VECTOR2, "value_ptr", "value_type")
        );
        assertEquals(
                "gdcc_new_Vector3_from_call_arg_variant(value_ptr, value_type)",
                helper.renderCallWrapperUnpackExpr(GdFloatVectorType.VECTOR3, "value_ptr", "value_type")
        );
        assertEquals(
                "gdcc_new_Vector4_from_call_arg_variant(value_ptr, value_type)",
                helper.renderCallWrapperUnpackExpr(GdFloatVectorType.VECTOR4, "value_ptr", "value_type")
        );
        assertEquals(
                "gdcc_new_Vector3_from_call_arg_variant(value_ptr, godot_variant_get_type(value_ptr))",
                helper.renderCallWrapperUnpackExpr(GdFloatVectorType.VECTOR3, "value_ptr", null)
        );
        assertEquals(
                "godot_new_Vector3i_with_Variant(value_ptr)",
                helper.renderCallWrapperUnpackExpr(GdIntVectorType.VECTOR3I, "value_ptr", "value_type")
        );
        assertEquals(
                "godot_new_Rect2_with_Variant(value_ptr)",
                helper.renderCallWrapperUnpackExpr(GdRect2Type.RECT2, "value_ptr", "value_type")
        );
    }

    @Test
    @DisplayName("call wrapper unpack should route string-family params through string materializers only")
    void renderCallWrapperUnpackExprShouldRouteStringFamilyParamsThroughStringMaterializersOnly() {
        assertEquals(
                "gdcc_new_StringName_from_call_arg_variant(value_ptr, value_type)",
                helper.renderCallWrapperUnpackExpr(GdStringNameType.STRING_NAME, "value_ptr", "value_type")
        );
        assertEquals(
                "gdcc_new_String_from_call_arg_variant(value_ptr, value_type)",
                helper.renderCallWrapperUnpackExpr(GdStringType.STRING, "value_ptr", "value_type")
        );
        assertEquals(
                "gdcc_new_StringName_from_call_arg_variant(value_ptr, godot_variant_get_type(value_ptr))",
                helper.renderCallWrapperUnpackExpr(GdStringNameType.STRING_NAME, "value_ptr", null)
        );

        var nodePathUnpack = helper.renderCallWrapperUnpackExpr(GdNodePathType.NODE_PATH, "value_ptr", "value_type");
        assertEquals("godot_new_NodePath_with_Variant(value_ptr)", nodePathUnpack);
        assertFalse(nodePathUnpack.contains("from_call_arg_variant"), nodePathUnpack);
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

    @Test
    @DisplayName("operator evaluator helper types use fat pointers for object operands")
    void operatorEvaluatorHelperTypesUseFatPointersForObjects() {
        var engineObject = new GdObjectType("Node");
        var gdccObject = new GdObjectType("MyChild");

        assertEquals("gdcc_Node_fat_ptr", helper.renderOperatorEvaluatorHelperTypeInC(engineObject));
        assertEquals("gdcc_MyChild_fat_ptr", helper.renderOperatorEvaluatorHelperTypeInC(gdccObject));
        assertEquals("gdcc_Node_fat_ptr", helper.renderOperatorEvaluatorHelperReturnTypeInC(engineObject));
        assertEquals("godot_int", helper.renderOperatorEvaluatorHelperTypeInC(GdIntType.INT));
        assertEquals("godot_String*", helper.renderOperatorEvaluatorHelperTypeInC(GdStringType.STRING));
    }

    @Test
    @DisplayName("operator evaluator lowers fat object operands to temporary raw slots")
    void operatorEvaluatorLowersObjectOperandsToRawSlots() {
        var object = new GdObjectType("Node");

        assertEquals(
                "GDExtensionObjectPtr left_raw = gdcc_Node_fat_ptr_live_object(left);\n",
                helper.renderOperatorEvaluatorObjectRawSlotDecl(object, "left")
        );
        assertEquals("&left_raw", helper.renderOperatorEvaluatorArgExpr(object, "left"));
        assertEquals("", helper.renderOperatorEvaluatorObjectRawSlotDecl(GdIntType.INT, "left"));
        assertEquals("&left", helper.renderOperatorEvaluatorArgExpr(GdIntType.INT, "left"));
        assertEquals("left", helper.renderOperatorEvaluatorArgExpr(GdStringType.STRING, "left"));
    }

    @Test
    @DisplayName("operator evaluator object returns capture raw carrier into fat pointer (defensive)")
    void operatorEvaluatorObjectReturnsCaptureRawIntoFat() {
        // Defensive: current extension_api has no Object-returning operators; still anchor the ABI shape.
        var engineObject = new GdObjectType("Node");
        var gdccObject = new GdObjectType("MyChild");

        assertEquals("GDExtensionObjectPtr", helper.renderOperatorEvaluatorResultCarrierTypeInC(engineObject));
        assertEquals("GDExtensionObjectPtr", helper.renderOperatorEvaluatorResultCarrierTypeInC(gdccObject));
        assertNotEquals(
                helper.renderOperatorEvaluatorHelperReturnTypeInC(engineObject),
                helper.renderOperatorEvaluatorResultCarrierTypeInC(engineObject),
                "carrier must stay raw while the helper return type is fat"
        );
        assertEquals(
                "gdcc_Node_fat_ptr_from_raw((GDExtensionObjectPtr)(result))",
                helper.renderOperatorEvaluatorReturnExpr(engineObject, "result")
        );
        assertEquals(
                "gdcc_MyChild_fat_ptr_from_raw((GDExtensionObjectPtr)(result))",
                helper.renderOperatorEvaluatorReturnExpr(gdccObject, "result")
        );
        // Non-object return: no from_raw wrap.
        assertEquals("godot_bool", helper.renderOperatorEvaluatorResultCarrierTypeInC(GdBoolType.BOOL));
        assertEquals("result", helper.renderOperatorEvaluatorReturnExpr(GdBoolType.BOOL, "result"));
        assertEquals("godot_String", helper.renderOperatorEvaluatorResultCarrierTypeInC(GdStringType.STRING));
        assertEquals("result", helper.renderOperatorEvaluatorReturnExpr(GdStringType.STRING, "result"));
    }

    @Test
    @DisplayName("operator evaluator live path: object operand + bool return (no return from_raw)")
    void operatorEvaluatorObjectOperandBoolReturnDoesNotFromRawReturn() {
        // Production-reachable shape: e.g. String in Object / bool and Object → bool.
        var object = new GdObjectType("Object");

        assertEquals("gdcc_Object_fat_ptr", helper.renderOperatorEvaluatorHelperTypeInC(object));
        assertEquals(
                "GDExtensionObjectPtr right_raw = gdcc_Object_fat_ptr_live_object(right);\n",
                helper.renderOperatorEvaluatorObjectRawSlotDecl(object, "right")
        );
        assertEquals("&right_raw", helper.renderOperatorEvaluatorArgExpr(object, "right"));
        assertEquals("godot_bool", helper.renderOperatorEvaluatorHelperReturnTypeInC(GdBoolType.BOOL));
        assertEquals("godot_bool", helper.renderOperatorEvaluatorResultCarrierTypeInC(GdBoolType.BOOL));
        assertEquals("result", helper.renderOperatorEvaluatorReturnExpr(GdBoolType.BOOL, "result"));
        assertFalse(helper.renderOperatorEvaluatorReturnExpr(GdBoolType.BOOL, "result").contains("_from_raw"));
    }

    @Test
    @DisplayName("unknown object still fails fast in operator evaluator renderers")
    void operatorEvaluatorUnknownObjectFailsFast() {
        var unknown = new GdObjectType("NotARegisteredType");

        var typeEx = assertThrows(IllegalStateException.class,
                () -> helper.renderOperatorEvaluatorHelperTypeInC(unknown));
        assertTrue(typeEx.getMessage().contains("NotARegisteredType")
                || typeEx.getMessage().toLowerCase().contains("unknown"), typeEx.getMessage());

        var slotEx = assertThrows(IllegalStateException.class,
                () -> helper.renderOperatorEvaluatorObjectRawSlotDecl(unknown, "left"));
        assertTrue(slotEx.getMessage().contains("NotARegisteredType")
                || slotEx.getMessage().toLowerCase().contains("unknown"), slotEx.getMessage());

        var returnEx = assertThrows(IllegalStateException.class,
                () -> helper.renderOperatorEvaluatorReturnExpr(unknown, "result"));
        assertTrue(returnEx.getMessage().contains("NotARegisteredType")
                || returnEx.getMessage().toLowerCase().contains("unknown"), returnEx.getMessage());
    }

    @Test
    @DisplayName("source default_value_func never enters the bind name or defaultVariables channel")
    void sourceDefaultValueFuncIsIsolatedFromBindChannel() {
        var worker = new LirClassDef("Worker", "RefCounted");

        var ping = new LirFunctionDef("ping");
        ping.setReturnType(GdVoidType.VOID);
        ping.addParameter(new LirParameterDef("self", new GdObjectType("Worker"), null, ping));
        ping.addParameter(new LirParameterDef("count", GdIntType.INT, "_default_ping$count", ping));
        ping.addParameter(new LirParameterDef("label", GdStringType.STRING, "_default_ping$label", ping));
        worker.addFunction(ping);

        var staticPing = new LirFunctionDef("static_ping");
        staticPing.setStatic(true);
        staticPing.setReturnType(GdVoidType.VOID);
        staticPing.addParameter(new LirParameterDef("count", GdIntType.INT, "_default_s_static_ping$count", staticPing));
        worker.addFunction(staticPing);

        var plain = new LirFunctionDef("plain");
        plain.setReturnType(GdVoidType.VOID);
        plain.addParameter(new LirParameterDef("self", new GdObjectType("Worker"), null, plain));
        plain.addParameter(new LirParameterDef("count", GdIntType.INT, null, plain));
        plain.addParameter(new LirParameterDef("label", GdStringType.STRING, null, plain));
        worker.addFunction(plain);

        // The bind-time Variant channel stays empty, but the default-slot count feeds the
        // wrapper shape: same-shape methods with different default counts never share.
        var pingBindName = helper.renderFuncBindName(worker, ping);
        var plainBindName = helper.renderFuncBindName(worker, plain);
        var staticPingBindName = helper.renderFuncBindName(staticPing);
        assertNotEquals(plainBindName, pingBindName);
        assertTrue(pingBindName.endsWith("_2_defslot"), pingBindName);
        assertTrue(staticPingBindName.endsWith("_1_defslot_static"), staticPingBindName);
        assertFalse(plainBindName.contains("defslot"), plainBindName);
        // The legacy `_N_default_` channel is never produced.
        assertFalse(pingBindName.contains("_default_"), pingBindName);
        assertFalse(staticPingBindName.contains("_default_"), staticPingBindName);

        assertEquals(2, helper.countDefaultSlots(ping));
        assertEquals(1, helper.countDefaultSlots(staticPing));
        assertEquals(0, helper.countDefaultSlots(plain));

        var context = new CodegenContext(
                new ProjectInfo("TestProject", GodotVersion.V451, Path.of(".")) {
                },
                classRegistry
        );
        var localHelper = new CGenHelper(context, List.of(worker));
        // ping (2 default slots), plain (same ABI shape, 0 slots) and static_ping never dedupe
        // into one another: three distinct wrapper shapes.
        assertEquals(3, localHelper.getBindingDataList().size());
        for (var data : localHelper.getBindingDataList()) {
            assertTrue(data.defaultVariables().isEmpty(),
                    "defaultVariables must stay empty for source defaults: " + data);
            assertFalse(localHelper.renderFuncBindName(data).contains("_default_"));
        }
    }

    @Test
    @DisplayName("BindingData rejects out-of-range defaultSlotCount")
    void bindingDataRejectsOutOfRangeDefaultSlotCount() {
        // More default slots than parameters.
        assertThrows(IllegalArgumentException.class, () -> new BindingData(
                "Worker", List.of(GdIntType.INT), GdVoidType.VOID, List.of(), false, 2));
        // Negative count.
        assertThrows(IllegalArgumentException.class, () -> new BindingData(
                null, List.of(GdIntType.INT), GdVoidType.VOID, List.of(), true, -1));
    }

    @Test
    @DisplayName("BindingData rejects any non-empty defaultVariables (bind Variant channel closed)")
    void bindingDataRejectsNonEmptyDefaultVariables() {
        assertThrows(IllegalArgumentException.class, () -> new BindingData(
                "Worker", List.of(GdIntType.INT), GdVoidType.VOID, List.of(GdIntType.INT), false, 1));
    }
}
