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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CGenHelperTest {
    private CGenHelper helper;
    private ClassRegistry classRegistry;
    private CodegenContext context;

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

        context = new CodegenContext(projectInfo, classRegistry);
        helper = new CGenHelper(context, List.of(gdccBase, gdccChild, gdccInner));
    }

    /// Registers all classes and builds a helper whose module list is exactly these classes.
    private CGenHelper newModuleHelper(List<LirClassDef> classes) {
        for (var classDef : classes) {
            classRegistry.addGdccClass(classDef);
        }
        return new CGenHelper(context, classes);
    }

    private static LirPropertyDef newProperty(
            String name,
            boolean isStatic,
            String initFunc,
            Map<String, String> annotations
    ) {
        return new LirPropertyDef(name, GdIntType.INT, isStatic, initFunc, null, null, annotations);
    }

    private static LirFunctionDef newReadyFunction(String ownerClassName) {
        var ready = new LirFunctionDef("_ready");
        ready.setReturnType(GdVoidType.VOID);
        ready.addParameter(new LirParameterDef("self", new GdObjectType(ownerClassName), null, ready));
        return ready;
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
    @DisplayName("renderBoundMetadata should publish the concrete object class in class_name")
    void renderBoundMetadataShouldPublishObjectClassName() {
        var engineMetadata = helper.renderBoundMetadata(new GdObjectType("HTTPRequest"), "godot_PROPERTY_USAGE_DEFAULT");
        var gdccMetadata = helper.renderBoundMetadata(new GdObjectType("PendingRequest"), "godot_PROPERTY_USAGE_NO_EDITOR");
        var innerMetadata = helper.renderBoundMetadata(new GdObjectType("Outer__sub__Inner"), "godot_PROPERTY_USAGE_DEFAULT");
        var genericMetadata = helper.renderBoundMetadata(GdObjectType.OBJECT, "godot_PROPERTY_USAGE_DEFAULT");

        assertEquals("GDEXTENSION_VARIANT_TYPE_OBJECT", engineMetadata.typeEnumLiteral());
        assertEquals("godot_PROPERTY_HINT_NONE", engineMetadata.hintEnumLiteral());
        assertEquals("GD_STATIC_S(u8\"\")", engineMetadata.hintStringExpr());
        assertEquals("GD_STATIC_SN(u8\"HTTPRequest\")", engineMetadata.classNameExpr());
        assertEquals("GD_STATIC_SN(u8\"PendingRequest\")", gdccMetadata.classNameExpr());
        assertEquals("godot_PROPERTY_USAGE_NO_EDITOR", gdccMetadata.usageExpr());
        assertEquals("GD_STATIC_SN(u8\"Outer__sub__Inner\")", innerMetadata.classNameExpr());
        assertEquals("GD_STATIC_SN(u8\"Object\")", genericMetadata.classNameExpr());
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
    @DisplayName("renderBoundMetadata should keep inner canonical object leaves verbatim while leaving top-level class slot empty")
    void renderBoundMetadataShouldKeepInnerCanonicalObjectLeavesVerbatimWhileLeavingTopLevelClassSlotEmpty() {
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
    @DisplayName("renderPropertyMetadata should render enum hint for bare export int with generated hint_string")
    void renderPropertyMetadataShouldRenderEnumHintForBareExportIntWithHintString() {
        // Script-enum exports: the frontend stores the generated `Name:value` hint_string under
        // the bare "export" key; the int-typed property maps to PROPERTY_HINT_ENUM.
        var property = new LirPropertyDef(
                "current",
                GdIntType.INT,
                false,
                null,
                null,
                null,
                Map.of("export", "Idle:0,Jump:5")
        );

        var metadata = helper.renderPropertyMetadata(property);

        assertEquals("GDEXTENSION_VARIANT_TYPE_INT", metadata.typeEnumLiteral());
        assertEquals("godot_PROPERTY_HINT_ENUM", metadata.hintEnumLiteral());
        assertEquals("GD_STATIC_S(u8\"Idle:0,Jump:5\")", metadata.hintStringExpr());
        assertEquals("GD_STATIC_SN(u8\"\")", metadata.classNameExpr());
        assertEquals("godot_PROPERTY_USAGE_DEFAULT", metadata.usageExpr());
    }

    @Test
    @DisplayName("renderPropertyMetadata should keep type-derived mapping for bare export int with empty value")
    void renderPropertyMetadataShouldKeepTypeDerivedMappingForBareExportIntWithEmptyValue() {
        // Plain bare `@export` encodes an empty value: no enum hint, the int type-derived
        // metadata stays exactly as before the script-enum rule existed.
        var property = new LirPropertyDef("score", GdIntType.INT, false, null, null, null, Map.of("export", ""));

        var metadata = helper.renderPropertyMetadata(property);

        assertEquals("GDEXTENSION_VARIANT_TYPE_INT", metadata.typeEnumLiteral());
        assertEquals("godot_PROPERTY_HINT_NONE", metadata.hintEnumLiteral());
        assertEquals("GD_STATIC_S(u8\"\")", metadata.hintStringExpr());
        assertEquals("godot_PROPERTY_USAGE_DEFAULT", metadata.usageExpr());
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
    @DisplayName("renderPropertyMetadata should publish class name for non-export Object property")
    void renderPropertyMetadataShouldPublishClassNameForNonExportObject() {
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
        assertEquals("GD_STATIC_SN(u8\"Texture2D\")", metadata.classNameExpr());
    }

    @Test
    @DisplayName("renderPropertyMetadata should stay silent for non-exportable Object family rejected upstream")
    void renderPropertyMetadataShouldStaySilentForNonExportableObjectFamily() {
        // The frontend export validation rejects bare `RefCounted` exports; backend deliberately
        // does not re-diagnose and falls back to the plain type-derived surface (which now still
        // publishes the object class name like any other Object slot).
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
        assertEquals("GD_STATIC_SN(u8\"RefCounted\")", metadata.classNameExpr());
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
    @DisplayName("renderSignalParameterMetadata should publish the Object class name")
    void renderSignalParameterMetadataShouldPublishObjectClassName() {
        var metadata = helper.renderSignalParameterMetadata(new GdObjectType("Node"));

        assertEquals("GDEXTENSION_VARIANT_TYPE_OBJECT", metadata.typeEnumLiteral());
        assertEquals("GD_STATIC_SN(u8\"Node\")", metadata.classNameExpr());
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
    @DisplayName("typed container leaves feed the bind name so same-shape wrappers never collide")
    void typedContainerLeavesFeedTheBindName() {
        // Regression: BindingData equality distinguishes typed containers, so the bind-name
        // encoding must too — `Array[Dictionary]` and `Array[StringName]` returns previously
        // both rendered as `ret_Array` and emitted duplicate wrapper symbols (probe P0-B).
        var worker = new LirClassDef("Worker", "RefCounted");

        var dictList = new LirFunctionDef("dict_list");
        dictList.setReturnType(new GdArrayType(new GdDictionaryType(GdVariantType.VARIANT, GdVariantType.VARIANT)));
        dictList.addParameter(new LirParameterDef("self", new GdObjectType("Worker"), null, dictList));
        worker.addFunction(dictList);

        var nameList = new LirFunctionDef("name_list");
        nameList.setReturnType(new GdArrayType(GdStringNameType.STRING_NAME));
        nameList.addParameter(new LirParameterDef("self", new GdObjectType("Worker"), null, nameList));
        worker.addFunction(nameList);

        var plainList = new LirFunctionDef("plain_list");
        plainList.setReturnType(new GdArrayType(GdVariantType.VARIANT));
        plainList.addParameter(new LirParameterDef("self", new GdObjectType("Worker"), null, plainList));
        worker.addFunction(plainList);

        var dictListBindName = helper.renderFuncBindName(worker, dictList);
        var nameListBindName = helper.renderFuncBindName(worker, nameList);
        var plainListBindName = helper.renderFuncBindName(worker, plainList);
        assertNotEquals(dictListBindName, nameListBindName);
        assertNotEquals(dictListBindName, plainListBindName);
        assertNotEquals(nameListBindName, plainListBindName);
        // Generic containers keep the engine-facing plain spelling; typed containers embed
        // length-prefixed element segments.
        assertTrue(plainListBindName.endsWith("ret_Array"), plainListBindName);
        assertTrue(dictListBindName.endsWith("ret_Array_10_Dictionary"), dictListBindName);
        assertTrue(nameListBindName.endsWith("ret_Array_10_StringName"), nameListBindName);

        // Typed parameters participate the same way (Dictionary[String, int] vs plain).
        var typedParam = new LirFunctionDef("typed_param");
        typedParam.setReturnType(GdVoidType.VOID);
        typedParam.addParameter(new LirParameterDef("self", new GdObjectType("Worker"), null, typedParam));
        typedParam.addParameter(new LirParameterDef("table",
                new GdDictionaryType(GdStringType.STRING, GdIntType.INT), null, typedParam));
        worker.addFunction(typedParam);

        var plainParam = new LirFunctionDef("plain_param");
        plainParam.setReturnType(GdVoidType.VOID);
        plainParam.addParameter(new LirParameterDef("self", new GdObjectType("Worker"), null, plainParam));
        plainParam.addParameter(new LirParameterDef("table",
                new GdDictionaryType(GdVariantType.VARIANT, GdVariantType.VARIANT), null, plainParam));
        worker.addFunction(plainParam);

        assertNotEquals(helper.renderFuncBindName(worker, typedParam),
                helper.renderFuncBindName(worker, plainParam));
    }

    @Test
    @DisplayName("bind name container segments are injective across underscore-carrying type names")
    void bindNameContainerSegmentsAreInjective() {
        // Type names may contain `_`, so a bare `_` join would still collide; length-prefixed
        // container segments must keep these pairs distinct.
        var worker = new LirClassDef("Worker", "RefCounted");

        var fooBarBaz = new LirFunctionDef("foo_bar_baz");
        fooBarBaz.setReturnType(new GdDictionaryType(new GdObjectType("Foo"), new GdObjectType("Bar_Baz")));
        fooBarBaz.addParameter(new LirParameterDef("self", new GdObjectType("Worker"), null, fooBarBaz));
        worker.addFunction(fooBarBaz);

        var fooBarBazSwapped = new LirFunctionDef("foo_bar_baz_swapped");
        fooBarBazSwapped.setReturnType(new GdDictionaryType(new GdObjectType("Foo_Bar"), new GdObjectType("Baz")));
        fooBarBazSwapped.addParameter(new LirParameterDef("self", new GdObjectType("Worker"), null, fooBarBazSwapped));
        worker.addFunction(fooBarBazSwapped);

        assertNotEquals(helper.renderFuncBindName(worker, fooBarBaz),
                helper.renderFuncBindName(worker, fooBarBazSwapped));

        var nested = new LirFunctionDef("nested");
        nested.setReturnType(new GdArrayType(new GdArrayType(GdStringType.STRING)));
        nested.addParameter(new LirParameterDef("self", new GdObjectType("Worker"), null, nested));
        worker.addFunction(nested);

        var flatUnderscore = new LirFunctionDef("flat_underscore");
        flatUnderscore.setReturnType(new GdArrayType(new GdObjectType("Array_String")));
        flatUnderscore.addParameter(new LirParameterDef("self", new GdObjectType("Worker"), null, flatUnderscore));
        worker.addFunction(flatUnderscore);

        assertNotEquals(helper.renderFuncBindName(worker, nested),
                helper.renderFuncBindName(worker, flatUnderscore));
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

    // ==== onready session plan ====

    @Test
    @DisplayName("checkOnreadyProperty should be a raw annotation-key tag check")
    void checkOnreadyPropertyShouldBeRawAnnotationKeyCheck() {
        var tagged = newProperty("a", false, null, Map.of("onready", ""));
        var arbitraryValue = newProperty("b", false, null, Map.of("onready", "anything"));
        var untagged = newProperty("c", false, null, Map.of());
        var otherAnnotation = newProperty("d", false, null, Map.of("export", ""));

        assertTrue(helper.checkOnreadyProperty(tagged));
        assertTrue(helper.checkOnreadyProperty(arbitraryValue));
        assertFalse(helper.checkOnreadyProperty(untagged));
        assertFalse(helper.checkOnreadyProperty(otherAnnotation));
    }

    @Test
    @DisplayName("onready plan should count only non-static onready properties with source initializers")
    void onreadyPlanShouldCountOnlySourceInitializerInstanceProperties() {
        var host = new LirClassDef("PlanHost", "Node");
        var onreadySource = newProperty("onready_source", false, "_field_init_onready_source", Map.of("onready", ""));
        var onreadyNoSource = newProperty("onready_no_source", false, null, Map.of("onready", ""));
        var plainSource = newProperty("plain_source", false, "_field_init_plain_source", Map.of());
        var staticOnready = newProperty("static_onready", true, "_field_init_static_onready", Map.of("onready", ""));
        host.addProperty(onreadySource);
        host.addProperty(onreadyNoSource);
        host.addProperty(plainSource);
        host.addProperty(staticOnready);
        var moduleHelper = newModuleHelper(List.of(host));

        var plan = moduleHelper.onreadyPlan().classPlan("PlanHost");

        assertEquals(List.of(onreadySource), plan.onreadyInitializers());
        assertTrue(plan.chainHasOnreadyInitializer());
        assertTrue(plan.classOrDescendantHasOnreadyInitializer());
        assertThrows(UnsupportedOperationException.class,
                () -> plan.onreadyInitializers().add(plainSource));
    }

    @Test
    @DisplayName("onready plan should exclude backend-default-marked initializers")
    void onreadyPlanShouldExcludeBackendDefaultMarkedInitializers() {
        var host = new LirClassDef("BackfillHost", "Node");
        var onreadyProp = newProperty("onready_prop", false, null, Map.of("onready", ""));
        host.addProperty(onreadyProp);

        var firstHelper = newModuleHelper(List.of(host));
        assertTrue(firstHelper.onreadyPlan().classPlan("BackfillHost").onreadyInitializers().isEmpty());
        assertFalse(firstHelper.checkClassChainHasOnreadyInitializer(host));

        // Simulates the backend default synthesis that backfills initFunc on the shared
        // module objects: a second session must still see "no source initializer".
        onreadyProp.setBackendSynthesizedInitFunc("_field_init_onready_prop");
        var secondHelper = newModuleHelper(List.of(host));
        var plan = secondHelper.onreadyPlan().classPlan("BackfillHost");
        assertTrue(plan.onreadyInitializers().isEmpty());
        assertFalse(plan.chainHasOnreadyInitializer());

        // Clearing initFunc through the regular setter also clears the marker.
        onreadyProp.setInitFunc(null);
        assertFalse(onreadyProp.isInitializerSynthesizedByBackend());
        assertNull(onreadyProp.getInitFunc());
    }

    @Test
    @DisplayName("onready plan should keep the per-class list in property declaration order")
    void onreadyPlanShouldKeepDeclarationOrder() {
        var host = new LirClassDef("OrderHost", "Node");
        var first = newProperty("first", false, "_field_init_first", Map.of("onready", ""));
        var skippedPlain = newProperty("plain", false, "_field_init_plain", Map.of());
        var skippedNoInit = newProperty("no_init", false, null, Map.of("onready", ""));
        var second = newProperty("second", false, "_field_init_second", Map.of("onready", ""));
        host.addProperty(first);
        host.addProperty(skippedPlain);
        host.addProperty(skippedNoInit);
        host.addProperty(second);
        var moduleHelper = newModuleHelper(List.of(host));

        assertEquals(List.of(first, second),
                moduleHelper.onreadyPlan().classPlan("OrderHost").onreadyInitializers());
    }

    @Test
    @DisplayName("onready chain fact should propagate up through same-module ancestors")
    void onreadyChainFactShouldPropagateThroughSameModuleAncestors() {
        // parent-only: child inherits the chain fact without own initializers
        var base = new LirClassDef("ChainBase", "Node");
        base.addProperty(newProperty("base_prop", false, "_field_init_base_prop", Map.of("onready", "")));
        var child = new LirClassDef("ChainChild", "ChainBase");
        // local-only: parent stays clean
        var plainBase = new LirClassDef("PlainBase", "Node");
        var localChild = new LirClassDef("LocalChild", "PlainBase");
        localChild.addProperty(newProperty("own_prop", false, "_field_init_own_prop", Map.of("onready", "")));
        // middle-only three-level chain: root <- mid <- leaf
        var root = new LirClassDef("ChainRoot", "Node");
        var mid = new LirClassDef("ChainMid", "ChainRoot");
        mid.addProperty(newProperty("mid_prop", false, "_field_init_mid_prop", Map.of("onready", "")));
        var leaf = new LirClassDef("ChainLeaf", "ChainMid");
        var moduleHelper = newModuleHelper(List.of(base, child, plainBase, localChild, root, mid, leaf));

        assertTrue(moduleHelper.checkClassChainHasOnreadyInitializer(base));
        assertTrue(moduleHelper.checkClassChainHasOnreadyInitializer(child));
        assertFalse(moduleHelper.checkClassChainHasOnreadyInitializer(plainBase));
        assertTrue(moduleHelper.checkClassChainHasOnreadyInitializer(localChild));
        assertFalse(moduleHelper.checkClassChainHasOnreadyInitializer(root));
        assertTrue(moduleHelper.checkClassChainHasOnreadyInitializer(mid));
        assertTrue(moduleHelper.checkClassChainHasOnreadyInitializer(leaf));
    }

    @Test
    @DisplayName("onready chain should stop at engine, unknown, blank and outside-module parents")
    void onreadyChainShouldStopAtModuleBoundaries() {
        var engineRooted = new LirClassDef("EngineRooted", "Node");
        engineRooted.addProperty(newProperty("p", false, "_field_init_er", Map.of("onready", "")));
        var orphan = new LirClassDef("Orphan", "NoSuchRegisteredClass");
        orphan.addProperty(newProperty("p", false, "_field_init_or", Map.of("onready", "")));
        var rootless = new LirClassDef("Rootless", "");
        rootless.addProperty(newProperty("p", false, "_field_init_rl", Map.of("onready", "")));
        // Registered outside the module: its onready facts must not leak into module chains.
        var external = new LirClassDef("ExternalBase", "Node");
        external.addProperty(newProperty("ext_prop", false, "_field_init_ext", Map.of("onready", "")));
        var internalChild = new LirClassDef("InternalChild", "ExternalBase");
        // Listed in the module but not registered: the parent edge must not be honored.
        var unregisteredParent = new LirClassDef("UnregisteredParent", "Node");
        unregisteredParent.addProperty(newProperty("up_prop", false, "_field_init_up", Map.of("onready", "")));
        var childOfUnregistered = new LirClassDef("ChildOfUnregistered", "UnregisteredParent");

        classRegistry.addGdccClass(engineRooted);
        classRegistry.addGdccClass(orphan);
        classRegistry.addGdccClass(rootless);
        classRegistry.addGdccClass(external);
        classRegistry.addGdccClass(internalChild);
        classRegistry.addGdccClass(childOfUnregistered);
        var moduleHelper = new CGenHelper(context, List.of(
                engineRooted, orphan, rootless, internalChild, unregisteredParent, childOfUnregistered));

        assertTrue(moduleHelper.checkClassChainHasOnreadyInitializer(engineRooted));
        assertTrue(moduleHelper.checkClassChainHasOnreadyInitializer(orphan));
        assertTrue(moduleHelper.checkClassChainHasOnreadyInitializer(rootless));
        assertFalse(moduleHelper.checkClassChainHasOnreadyInitializer(internalChild));
        assertTrue(moduleHelper.checkClassChainHasOnreadyInitializer(unregisteredParent));
        assertFalse(moduleHelper.checkClassChainHasOnreadyInitializer(childOfUnregistered));
    }

    @Test
    @DisplayName("onready plan should resolve chains when the module lists derived classes first")
    void onreadyPlanShouldResolveDerivedFirstModuleOrder() {
        var base = new LirClassDef("LateBase", "Node");
        base.addProperty(newProperty("p", false, "_field_init_p", Map.of("onready", "")));
        var derived = new LirClassDef("EarlyDerived", "LateBase");
        var moduleHelper = newModuleHelper(List.of(derived, base));

        assertTrue(moduleHelper.checkClassChainHasOnreadyInitializer(derived));
        assertTrue(moduleHelper.checkClassChainHasOnreadyInitializer(base));
    }

    @Test
    @DisplayName("onready descendant fact should cover own or same-module descendant chain facts")
    void onreadyDescendantFactShouldCoverOwnAndSameModuleDescendants() {
        var anc = new LirClassDef("Anc", "Node");
        var der = new LirClassDef("Der", "Anc");
        der.addProperty(newProperty("p", false, "_field_init_der", Map.of("onready", "")));
        var lone = new LirClassDef("Lone", "Node");
        lone.addProperty(newProperty("p", false, "_field_init_lo", Map.of("onready", "")));
        var quietRoot = new LirClassDef("QuietRoot", "Node");
        var quietChild = new LirClassDef("QuietChild", "QuietRoot");
        var sibRoot = new LirClassDef("SibRoot", "Node");
        var sibA = new LirClassDef("SibA", "SibRoot");
        sibA.addProperty(newProperty("p", false, "_field_init_sa", Map.of("onready", "")));
        var sibB = new LirClassDef("SibB", "SibRoot");
        var moduleHelper = newModuleHelper(List.of(anc, der, lone, quietRoot, quietChild, sibRoot, sibA, sibB));

        // derived-only: ancestor sees the descendant fact without an own chain fact
        assertFalse(moduleHelper.checkClassChainHasOnreadyInitializer(anc));
        assertTrue(moduleHelper.checkClassOrDescendantHasOnreadyInitializer(anc));
        assertTrue(moduleHelper.checkClassOrDescendantHasOnreadyInitializer(der));
        // local-only
        assertTrue(moduleHelper.checkClassOrDescendantHasOnreadyInitializer(lone));
        // all-none
        assertFalse(moduleHelper.checkClassOrDescendantHasOnreadyInitializer(quietRoot));
        assertFalse(moduleHelper.checkClassOrDescendantHasOnreadyInitializer(quietChild));
        // a sibling's onready chain must not leak across the hierarchy
        assertFalse(moduleHelper.checkClassOrDescendantHasOnreadyInitializer(sibB));
        assertTrue(moduleHelper.checkClassOrDescendantHasOnreadyInitializer(sibRoot));
    }

    @Test
    @DisplayName("resolveChainUserReadyFunction should return the most-derived valid declaration and its owner")
    void resolveChainUserReadyFunctionShouldReturnMostDerivedValidDeclaration() {
        var base = new LirClassDef("ReadyBase", "Node");
        var baseReady = newReadyFunction("ReadyBase");
        base.addFunction(baseReady);
        var derived = new LirClassDef("ReadyDerived", "ReadyBase");
        var derivedReady = newReadyFunction("ReadyDerived");
        derived.addFunction(derivedReady);
        var inheritedOnly = new LirClassDef("ReadyInherited", "ReadyBase");
        var empty = new LirClassDef("ReadyEmpty", "Node");
        var moduleHelper = newModuleHelper(List.of(base, derived, inheritedOnly, empty));

        assertSame(derivedReady, moduleHelper.resolveChainUserReadyFunctionOrNull(derived));
        assertEquals("ReadyDerived",
                moduleHelper.onreadyPlan().classPlan("ReadyDerived").userReadyOwnerClassName());

        assertSame(baseReady, moduleHelper.resolveChainUserReadyFunctionOrNull(inheritedOnly));
        assertEquals("ReadyBase",
                moduleHelper.onreadyPlan().classPlan("ReadyInherited").userReadyOwnerClassName());

        assertNull(moduleHelper.resolveChainUserReadyFunctionOrNull(empty));
    }

    @Test
    @DisplayName("resolveChainUserReadyFunction should skip declarations failing the engine signature check")
    void resolveChainUserReadyFunctionShouldSkipInvalidDeclarations() {
        var badReturn = new LirClassDef("BadReturn", "Node");
        var badReturnReady = newReadyFunction("BadReturn");
        badReturnReady.setReturnType(GdIntType.INT);
        badReturn.addFunction(badReturnReady);
        var badArity = new LirClassDef("BadArity", "Node");
        var badArityReady = newReadyFunction("BadArity");
        badArityReady.addParameter(new LirParameterDef("extra", GdIntType.INT, null, badArityReady));
        badArity.addFunction(badArityReady);
        // A static _ready is excluded from virtual dispatch: the valid parent declaration
        // must still win instead of being shadowed by the invalid child declaration.
        var validBase = new LirClassDef("ValidBase", "Node");
        var validReady = newReadyFunction("ValidBase");
        validBase.addFunction(validReady);
        var staticChild = new LirClassDef("StaticReadyChild", "ValidBase");
        var staticReady = newReadyFunction("StaticReadyChild");
        staticReady.setStatic(true);
        staticChild.addFunction(staticReady);
        // Not Node-derived at all: the engine exposes no `_ready` on this ancestry.
        var refCountedHost = new LirClassDef("RefCountedHost", "RefCounted");
        refCountedHost.addFunction(newReadyFunction("RefCountedHost"));
        var moduleHelper = newModuleHelper(List.of(badReturn, badArity, validBase, staticChild, refCountedHost));

        assertNull(moduleHelper.resolveChainUserReadyFunctionOrNull(badReturn));
        assertNull(moduleHelper.resolveChainUserReadyFunctionOrNull(badArity));
        assertSame(validReady, moduleHelper.resolveChainUserReadyFunctionOrNull(staticChild));
        assertEquals("ValidBase",
                moduleHelper.onreadyPlan().classPlan("StaticReadyChild").userReadyOwnerClassName());
        assertNull(moduleHelper.resolveChainUserReadyFunctionOrNull(refCountedHost));
    }

    @Test
    @DisplayName("resolveChainUserReadyFunction should keep the coroutine function reference intact")
    void resolveChainUserReadyFunctionShouldKeepCoroutineFunctionReference() {
        var host = new LirClassDef("CoroReady", "Node");
        var coroReady = newReadyFunction("CoroReady");
        coroReady.setCoroutine(true);
        host.addFunction(coroReady);
        var moduleHelper = newModuleHelper(List.of(host));

        var resolution = moduleHelper.resolveChainUserReadyFunctionOrNull(host);
        assertSame(coroReady, resolution);
        assertEquals("CoroReady",
                moduleHelper.onreadyPlan().classPlan("CoroReady").userReadyOwnerClassName());
        assertTrue(resolution.isCoroutine());
    }

    @Test
    @DisplayName("onready plan should answer unknown classes with an empty plan")
    void onreadyPlanShouldAnswerUnknownClassesWithEmptyPlan() {
        var host = new LirClassDef("KnownHost", "Node");
        var moduleHelper = newModuleHelper(List.of(host));

        var unknown = moduleHelper.onreadyPlan().classPlan("Nobody");
        assertTrue(unknown.onreadyInitializers().isEmpty());
        assertFalse(unknown.chainHasOnreadyInitializer());
        assertFalse(unknown.classOrDescendantHasOnreadyInitializer());
        assertNull(unknown.userReadyOwnerClassName());
        assertNull(unknown.userReadyFunction());
        var stray = new LirClassDef("Stray", "Node");
        assertFalse(moduleHelper.checkClassChainHasOnreadyInitializer(stray));
        assertFalse(moduleHelper.checkClassOrDescendantHasOnreadyInitializer(stray));
        assertNull(moduleHelper.resolveChainUserReadyFunctionOrNull(stray));
    }

    @Test
    @DisplayName("onready plan snapshot should stay stable under later LIR mutation")
    void onreadyPlanSnapshotShouldStayStableUnderLaterLirMutation() {
        var base = new LirClassDef("SnapBase", "Node");
        var baseReady = newReadyFunction("SnapBase");
        base.addFunction(baseReady);
        var baseProp = newProperty("base_prop", false, "_field_init_base_prop", Map.of("onready", ""));
        base.addProperty(baseProp);
        var leaf = new LirClassDef("SnapLeaf", "SnapBase");
        var leafProp = newProperty("leaf_prop", false, "_field_init_leaf_prop", Map.of("onready", ""));
        leaf.addProperty(leafProp);
        // The helper borrows the authoritative list rather than copying it, so the honest
        // fixture is an externally held mutable list.
        var moduleClasses = new ArrayList<LirClassDef>(List.of(base, leaf));
        classRegistry.addGdccClass(base);
        classRegistry.addGdccClass(leaf);
        var moduleHelper = new CGenHelper(context, moduleClasses);

        var leafPlanBefore = moduleHelper.onreadyPlan().classPlan("SnapLeaf");
        var basePlanBefore = moduleHelper.onreadyPlan().classPlan("SnapBase");

        // Mutate everything the snapshot consumed: initFunc, annotations, super name, and
        // the class's function list. The frozen facts must not move.
        leafProp.setInitFunc(null);
        baseProp.getAnnotations().remove("onready");
        leaf.setSuperName("Node");
        leaf.addFunction(newReadyFunction("SnapLeaf"));
        // The captured plan is closed: clearing/replacing the borrowed source list afterwards
        // cannot add or remove members.
        moduleClasses.clear();
        var late = new LirClassDef("SnapLate", "Node");
        late.addProperty(newProperty("late_prop", false, "_field_init_late", Map.of("onready", "")));
        moduleClasses.add(late);

        var leafPlanAfter = moduleHelper.onreadyPlan().classPlan("SnapLeaf");
        assertSame(leafPlanBefore, leafPlanAfter);
        assertEquals(List.of(leafProp), leafPlanAfter.onreadyInitializers());
        assertTrue(leafPlanAfter.chainHasOnreadyInitializer());
        assertTrue(leafPlanAfter.classOrDescendantHasOnreadyInitializer());
        assertEquals("SnapBase", leafPlanAfter.userReadyOwnerClassName());
        assertSame(baseReady, leafPlanAfter.userReadyFunction());
        assertSame(basePlanBefore, moduleHelper.onreadyPlan().classPlan("SnapBase"));
        assertTrue(moduleHelper.onreadyPlan().classPlan("SnapLate").onreadyInitializers().isEmpty());
        assertFalse(moduleHelper.checkClassChainHasOnreadyInitializer(late));
        // The raw tag predicate intentionally reads live annotations, not the snapshot.
        assertFalse(moduleHelper.checkOnreadyProperty(baseProp));
    }

    @Test
    @DisplayName("onready plan should read the authoritative class list at first query")
    void onreadyPlanShouldUseAuthoritativeClassListAtFirstQuery() {
        var base = new LirClassDef("BorrowBase", "Node");
        var moduleClasses = new ArrayList<LirClassDef>(List.of(base));
        classRegistry.addGdccClass(base);
        var moduleHelper = new CGenHelper(context, moduleClasses);

        // No onready query has run yet: a class added to the authoritative list before the
        // first query still participates in the captured plan.
        var late = new LirClassDef("BorrowLate", "Node");
        var lateProp = newProperty("late_prop", false, "_field_init_late", Map.of("onready", ""));
        late.addProperty(lateProp);
        classRegistry.addGdccClass(late);
        moduleClasses.add(late);

        var plan = moduleHelper.onreadyPlan();
        assertEquals(List.of(lateProp), plan.classPlan("BorrowLate").onreadyInitializers());
        assertTrue(moduleHelper.checkClassChainHasOnreadyInitializer(late));
        assertTrue(moduleHelper.checkClassOrDescendantHasOnreadyInitializer(late));
    }

    @Test
    @DisplayName("ClassPlan should defensively copy its initializer membership")
    void classPlanShouldDefensivelyCopyInitializerMembership() {
        var prop = newProperty("p", false, "_field_init_p", Map.of("onready", ""));
        var callerList = new ArrayList<LirPropertyDef>(List.of(prop));
        var plan = new COnreadyPlan.ClassPlan(callerList, true, true, null, null);

        callerList.clear();
        callerList.add(newProperty("other", false, "_field_init_other", Map.of("onready", "")));

        assertEquals(List.of(prop), plan.onreadyInitializers());
        assertThrows(UnsupportedOperationException.class,
                () -> plan.onreadyInitializers().add(prop));
    }

    @Test
    @DisplayName("ClassPlan should require ready owner and function to be both present or both absent")
    void classPlanShouldRequireReadyOwnerAndFunctionPairInvariant() {
        var bothNull = new COnreadyPlan.ClassPlan(List.of(), false, false, null, null);
        assertNull(bothNull.userReadyOwnerClassName());
        assertNull(bothNull.userReadyFunction());

        var ready = newReadyFunction("Host");
        var bothPresent = new COnreadyPlan.ClassPlan(List.of(), false, false, "Host", ready);
        assertEquals("Host", bothPresent.userReadyOwnerClassName());
        assertSame(ready, bothPresent.userReadyFunction());

        assertThrows(IllegalArgumentException.class,
                () -> new COnreadyPlan.ClassPlan(List.of(), false, false, "Host", null));
        assertThrows(IllegalArgumentException.class,
                () -> new COnreadyPlan.ClassPlan(List.of(), false, false, null, ready));
    }

    @Test
    @DisplayName("cyclic super names should fail fast at planner construction and at plan build")
    void onreadyPlanningShouldFailFastOnCyclicInput() {
        // Cycle already present at construction: the vtable planner rejects it first.
        var cycA = new LirClassDef("CycA", "CycB");
        var cycB = new LirClassDef("CycB", "CycA");
        classRegistry.addGdccClass(cycA);
        classRegistry.addGdccClass(cycB);
        assertThrows(IllegalStateException.class,
                () -> new CGenHelper(context, List.of(cycA, cycB)));

        // Cycle introduced after helper construction but before the first onready query:
        // the plan's own visited walk must refuse it instead of looping.
        var lateA = new LirClassDef("LateCycA", "Node");
        lateA.addFunction(newReadyFunction("LateCycA"));
        var lateB = new LirClassDef("LateCycB", "Node");
        classRegistry.addGdccClass(lateA);
        classRegistry.addGdccClass(lateB);
        var lateHelper = new CGenHelper(context, List.of(lateA, lateB));
        lateA.setSuperName("LateCycB");
        lateB.setSuperName("LateCycA");
        assertThrows(IllegalStateException.class, lateHelper::onreadyPlan);

        // Self-cycle introduced the same way.
        var selfCyc = new LirClassDef("SelfCyc", "Node");
        classRegistry.addGdccClass(selfCyc);
        var selfHelper = new CGenHelper(context, List.of(selfCyc));
        selfCyc.setSuperName("SelfCyc");
        assertThrows(IllegalStateException.class, selfHelper::onreadyPlan);
    }

    @Test
    @DisplayName("onready plan should fail fast on a cyclic registry ancestry outside the module")
    void onreadyPlanShouldPreflightRegistryAncestryBeforeVirtualCheck() {
        // The module itself is acyclic, but the host's parent lives outside the module and
        // forms a registered cycle; resolving `_ready` must not recurse into it.
        var host = new LirClassDef("RegistryCycHost", "ExternalA");
        host.addFunction(newReadyFunction("RegistryCycHost"));
        var externalA = new LirClassDef("ExternalA", "ExternalB");
        var externalB = new LirClassDef("ExternalB", "ExternalA");
        classRegistry.addGdccClass(host);
        classRegistry.addGdccClass(externalA);
        classRegistry.addGdccClass(externalB);
        var hostHelper = new CGenHelper(context, List.of(host));

        assertThrows(IllegalStateException.class, hostHelper::onreadyPlan);
    }

    @Test
    @DisplayName("onready apply naming and chooser should follow frozen plan membership, not live annotations")
    void onreadyApplyNamingAndChooserShouldFollowFrozenPlanMembership() {
        var host = new LirClassDef("ApplyHost", "Node");
        var onreadySource = newProperty("onready_source", false, "_field_init_onready_source", Map.of("onready", ""));
        var plainSource = newProperty("plain_source", false, "_field_init_plain_source", Map.of());
        var onreadyMissing = newProperty("onready_missing", false, null, Map.of("onready", ""));
        var staticOnready = newProperty("static_onready", true, "_field_init_static", Map.of("onready", ""));
        host.addProperty(onreadySource);
        host.addProperty(plainSource);
        host.addProperty(onreadyMissing);
        host.addProperty(staticOnready);
        var moduleHelper = newModuleHelper(List.of(host));

        assertTrue(moduleHelper.checkOnreadyInitializer(host, onreadySource));
        assertFalse(moduleHelper.checkOnreadyInitializer(host, plainSource));
        // Missing initializers and statics are never frozen-eligible, annotation or not.
        assertFalse(moduleHelper.checkOnreadyInitializer(host, onreadyMissing));
        assertFalse(moduleHelper.checkOnreadyInitializer(host, staticOnready));

        assertEquals("_onready_field_default_onready_source",
                moduleHelper.renderOnreadyFieldDefaultFunctionName(onreadySource));
        assertEquals("ApplyHost_class_apply_property_default_onready_source",
                moduleHelper.renderPropertyDefaultApplyHelperName(host, onreadySource));
        assertEquals("ApplyHost_class_apply_onready_init_onready_source",
                moduleHelper.renderOnreadyApplyHelperName(host, onreadySource));

        // The construction chooser picks the default route only for frozen members.
        assertEquals("ApplyHost_class_apply_property_default_onready_source",
                moduleHelper.renderPropertyConstructionApplyHelperName(host, onreadySource));
        assertEquals("ApplyHost_class_apply_property_init_plain_source",
                moduleHelper.renderPropertyConstructionApplyHelperName(host, plainSource));
        assertEquals("ApplyHost_class_apply_property_init_onready_missing",
                moduleHelper.renderPropertyConstructionApplyHelperName(host, onreadyMissing));

        // Annotation edits after the plan was captured cannot flip membership.
        onreadySource.getAnnotations().remove("onready");
        plainSource.getAnnotations().put("onready", "");
        assertTrue(moduleHelper.checkOnreadyInitializer(host, onreadySource));
        assertFalse(moduleHelper.checkOnreadyInitializer(host, plainSource));
        assertEquals("ApplyHost_class_apply_property_default_onready_source",
                moduleHelper.renderPropertyConstructionApplyHelperName(host, onreadySource));
        assertEquals("ApplyHost_class_apply_property_init_plain_source",
                moduleHelper.renderPropertyConstructionApplyHelperName(host, plainSource));
    }

    @Test
    @DisplayName("onready chain/adapter/ready helper names should render the exact generated symbols")
    void onreadyChainNamingShouldRenderExactSymbols() {
        assertEquals("Host_class_apply_onready_inits", helper.renderOnreadyChainApplyHelperName("Host"));
        assertEquals("Host__gdcc_onready_apply_root", helper.renderOnreadyRootApplyHelperName("Host"));
        assertEquals("Host__gdcc_onready_ready", helper.renderOnreadyReadyHelperName("Host"));
    }

    @Test
    @DisplayName("GDCC root resolution should follow the registered wrapper chain, not module order")
    void resolveGdccRootClassNameShouldFollowRegisteredWrapperChain() {
        // The shared fixture: MyChild -> MyBase -> RefCounted (engine); the inner class also
        // hangs off MyBase. Root is the outermost GDCC wrapper ancestor.
        assertEquals("MyBase", helper.resolveGdccRootClassName("MyChild"));
        assertEquals("MyBase", helper.resolveGdccRootClassName("MyBase"));
        assertEquals("MyBase", helper.resolveGdccRootClassName("RuntimeOuter__sub__Worker"));

        var root = new LirClassDef("ChainRoot", "Node");
        var mid = new LirClassDef("ChainMid", "ChainRoot");
        var leaf = new LirClassDef("ChainLeaf", "ChainMid");
        // Derived-first module order must not matter: resolution walks the registry.
        var moduleHelper = newModuleHelper(List.of(leaf, mid, root));
        assertEquals("ChainRoot", moduleHelper.resolveGdccRootClassName("ChainLeaf"));
        assertEquals("ChainRoot", moduleHelper.resolveGdccRootClassName("ChainMid"));
        assertEquals("ChainRoot", moduleHelper.resolveGdccRootClassName("ChainRoot"));

        // A registered GDCC ancestor outside the module list is still the physical prefix
        // owner, so the walk follows it regardless of module membership.
        var externalRoot = new LirClassDef("ExternalRoot", "Node");
        classRegistry.addGdccClass(externalRoot);
        var underExternal = new LirClassDef("UnderExternal", "ExternalRoot");
        var externalHelper = newModuleHelper(List.of(underExternal));
        assertEquals("ExternalRoot", externalHelper.resolveGdccRootClassName("UnderExternal"));

        // Unknown or blank supers terminate the walk at the current class.
        var unknownParented = new LirClassDef("UnknownParented", "NotRegistered");
        var blankParented = new LirClassDef("BlankParented", "");
        var oddHelper = newModuleHelper(List.of(unknownParented, blankParented));
        assertEquals("UnknownParented", oddHelper.resolveGdccRootClassName("UnknownParented"));
        assertEquals("BlankParented", oddHelper.resolveGdccRootClassName("BlankParented"));

        // An unregistered starting class is an argument error, not a state error.
        assertThrows(IllegalArgumentException.class, () -> helper.resolveGdccRootClassName("Missing"));
    }

    @Test
    @DisplayName("GDCC root resolution should detect cycles and never cache the root")
    void resolveGdccRootClassNameShouldDetectCyclesAndAvoidCaching() {
        var a = new LirClassDef("CycA", "CycB");
        var b = new LirClassDef("CycB", "Node");
        var cycleHelper = newModuleHelper(List.of(a, b));
        // Constructed acyclic; mutate into a two-node cycle afterwards.
        b.setSuperName("CycA");
        assertThrows(IllegalStateException.class, () -> cycleHelper.resolveGdccRootClassName("CycA"));

        var self = new LirClassDef("SelfCyc", "Node");
        var selfHelper = newModuleHelper(List.of(self));
        self.setSuperName("SelfCyc");
        assertThrows(IllegalStateException.class, () -> selfHelper.resolveGdccRootClassName("SelfCyc"));

        // No cached root: mutating the superclass changes the next call's result.
        var oldRoot = new LirClassDef("OldRoot", "Node");
        var newRoot = new LirClassDef("NewRoot", "Node");
        var movable = new LirClassDef("Movable", "OldRoot");
        var moveHelper = newModuleHelper(List.of(oldRoot, newRoot, movable));
        assertEquals("OldRoot", moveHelper.resolveGdccRootClassName("Movable"));
        movable.setSuperName("NewRoot");
        assertEquals("NewRoot", moveHelper.resolveGdccRootClassName("Movable"));
    }
}
