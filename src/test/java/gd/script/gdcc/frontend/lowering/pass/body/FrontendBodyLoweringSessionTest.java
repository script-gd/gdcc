package gd.script.gdcc.frontend.lowering.pass.body;

import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.lowering.FrontendLoweringContext;
import gd.script.gdcc.frontend.lowering.FrontendSubscriptAccessSupport;
import gd.script.gdcc.frontend.lowering.FunctionLoweringContext;
import gd.script.gdcc.frontend.lowering.pass.FrontendLoweringAnalysisPass;
import gd.script.gdcc.frontend.lowering.pass.FrontendLoweringBuildCfgPass;
import gd.script.gdcc.frontend.lowering.pass.FrontendLoweringClassSkeletonPass;
import gd.script.gdcc.frontend.lowering.pass.FrontendLoweringFunctionPreparationPass;
import gd.script.gdcc.frontend.parse.FrontendModule;
import gd.script.gdcc.frontend.parse.GdScriptParserService;
import gd.script.gdcc.frontend.sema.FrontendBindingKind;
import gd.script.gdcc.frontend.sema.FrontendMemberResolutionStatus;
import gd.script.gdcc.frontend.sema.FrontendReceiverKind;
import gd.script.gdcc.frontend.sema.FrontendResolvedMember;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.lir.LirBasicBlock;
import gd.script.gdcc.lir.LirInstruction;
import gd.script.gdcc.lir.insn.CallIntrinsicInsn;
import gd.script.gdcc.lir.insn.ConstructBuiltinInsn;
import gd.script.gdcc.lir.insn.LiteralNullInsn;
import gd.script.gdcc.lir.insn.PackVariantInsn;
import gd.script.gdcc.lir.insn.UnpackVariantInsn;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.scope.ScopeOwnerKind;
import gd.script.gdcc.type.GdDictionaryType;
import gd.script.gdcc.type.GdccForRangeIterType;
import gd.script.gdcc.type.GdFloatType;
import gd.script.gdcc.type.GdFloatVectorType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdIntVectorType;
import gd.script.gdcc.type.GdNilType;
import gd.script.gdcc.type.GdNodePathType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdStringNameType;
import gd.script.gdcc.type.GdStringType;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.type.GdVariantType;
import gd.script.gdcc.type.GdVoidType;
import dev.superice.gdparser.frontend.ast.AttributePropertyStep;
import dev.superice.gdparser.frontend.ast.Point;
import dev.superice.gdparser.frontend.ast.Range;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontendBodyLoweringSessionTest {
    private static final Range SYNTHETIC_RANGE = new Range(0, 1, new Point(0, 0), new Point(0, 1));

    @Test
    void requireResolvedMemberAcceptsDynamicPublishedFacts() throws Exception {
        var fixture = prepareSessionFixture();
        var anchor = property("marker");
        var publishedMember = FrontendResolvedMember.dynamic(
                "marker",
                FrontendBindingKind.PROPERTY,
                FrontendReceiverKind.INSTANCE,
                ScopeOwnerKind.BUILTIN,
                GdVariantType.VARIANT,
                "Variant.marker",
                "runtime-dynamic property access"
        );
        fixture.context().analysisData().resolvedMembers().put(anchor, publishedMember);

        var actualMember = fixture.session().requireResolvedMember(anchor);

        assertAll(
                () -> assertSame(publishedMember, actualMember),
                () -> assertEquals(FrontendMemberResolutionStatus.DYNAMIC, actualMember.status()),
                () -> assertEquals(GdVariantType.VARIANT, actualMember.receiverType()),
                () -> assertEquals("runtime-dynamic property access", actualMember.detailReason())
        );
    }

    @Test
    void requireResolvedMemberRejectsNonLoweringReadyMemberStatuses() throws Exception {
        var fixture = prepareSessionFixture();
        var cases = List.of(
                new MemberStatusCase(
                        "blocked",
                        FrontendResolvedMember.blocked(
                                "blocked",
                                FrontendBindingKind.PROPERTY,
                                FrontendReceiverKind.INSTANCE,
                                ScopeOwnerKind.GDCC,
                                new GdObjectType("Player"),
                                GdIntType.INT,
                                "Player.blocked",
                                "instance member is blocked in static context"
                        )
                ),
                new MemberStatusCase(
                        "deferred",
                        FrontendResolvedMember.deferred(
                                "deferred",
                                FrontendBindingKind.PROPERTY,
                                FrontendReceiverKind.INSTANCE,
                                ScopeOwnerKind.GDCC,
                                new GdObjectType("Player"),
                                "Player.deferred",
                                "receiver typing is not ready"
                        )
                ),
                new MemberStatusCase(
                        "failed",
                        FrontendResolvedMember.failed(
                                "failed",
                                FrontendBindingKind.PROPERTY,
                                FrontendReceiverKind.INSTANCE,
                                ScopeOwnerKind.GDCC,
                                new GdObjectType("Player"),
                                "Player.failed",
                                "member does not exist"
                        )
                ),
                new MemberStatusCase(
                        "unsupported",
                        FrontendResolvedMember.unsupported(
                                "unsupported",
                                FrontendBindingKind.PROPERTY,
                                FrontendReceiverKind.TYPE_META,
                                ScopeOwnerKind.GDCC,
                                new GdObjectType("Player"),
                                "Player.unsupported",
                                "static dynamic member route is unsupported"
                        )
                )
        );

        for (var testCase : cases) {
            var anchor = property(testCase.member().memberName());
            fixture.context().analysisData().resolvedMembers().put(anchor, testCase.member());

            var exception = assertThrows(
                    IllegalStateException.class,
                    () -> fixture.session().requireResolvedMember(anchor)
            );

            assertAll(
                    testCase.label(),
                    () -> assertTrue(
                            exception.getMessage().contains("Member anchor AttributePropertyStep is not lowering-ready"),
                            exception.getMessage()
                    ),
                    () -> assertTrue(
                            exception.getMessage().contains(testCase.member().status().name()),
                            exception.getMessage()
                    )
            );
        }
    }

    @Test
    void requireResolvedMemberFailsFastWhenMemberFactIsMissing() throws Exception {
        var fixture = prepareSessionFixture();
        var exception = assertThrows(
                IllegalStateException.class,
                () -> fixture.session().requireResolvedMember(property("missing"))
        );

        assertTrue(
                exception.getMessage().contains("Missing published resolved member for AttributePropertyStep"),
                exception.getMessage()
        );
    }

    @Test
    void materializeFrontendBoundaryValuePacksConcreteSourcesForVariantTargets() throws Exception {
        var session = prepareSession();
        var block = new LirBasicBlock("entry");
        session.ensureVariable("source_value", GdIntType.INT);

        var materializedSlotId = session.materializeFrontendBoundaryValue(
                block,
                "source_value",
                GdIntType.INT,
                GdVariantType.VARIANT,
                "call_arg"
        );

        var instructions = block.getNonTerminatorInstructions();
        var packedInsn = assertInstanceOf(PackVariantInsn.class, instructions.getFirst());
        var packedVariable = session.targetFunction().getVariableById(materializedSlotId);
        assertNotNull(packedVariable);

        assertAll(
                () -> assertEquals(1, instructions.size()),
                () -> assertNotEquals("source_value", materializedSlotId),
                () -> assertEquals(materializedSlotId, packedInsn.resultId()),
                () -> assertEquals("source_value", packedInsn.valueId()),
                () -> assertEquals(GdVariantType.VARIANT, packedVariable.type())
        );
    }

    @Test
    void materializeFrontendBoundaryValueUnpacksStableVariantSourcesForConcreteTargets() throws Exception {
        var session = prepareSession();
        var block = new LirBasicBlock("entry");
        session.ensureVariable("source_variant", GdVariantType.VARIANT);

        var materializedSlotId = session.materializeFrontendBoundaryValue(
                block,
                "source_variant",
                GdVariantType.VARIANT,
                GdIntType.INT,
                "return_value"
        );

        var instructions = block.getNonTerminatorInstructions();
        var unpackedInsn = assertInstanceOf(UnpackVariantInsn.class, instructions.getFirst());
        var unpackedVariable = session.targetFunction().getVariableById(materializedSlotId);
        assertNotNull(unpackedVariable);

        assertAll(
                () -> assertEquals(1, instructions.size()),
                () -> assertNotEquals("source_variant", materializedSlotId),
                () -> assertEquals(materializedSlotId, unpackedInsn.resultId()),
                () -> assertEquals("source_variant", unpackedInsn.variantId()),
                () -> assertEquals(GdIntType.INT, unpackedVariable.type())
        );
    }

    @Test
    void materializeFrontendBoundaryValueCastsIntSourcesForFloatTargetsThroughIntrinsic() throws Exception {
        var session = prepareSession();
        var block = new LirBasicBlock("entry");
        session.ensureVariable("source_int", GdIntType.INT);

        var materializedSlotId = session.materializeFrontendBoundaryValue(
                block,
                "source_int",
                GdIntType.INT,
                GdFloatType.FLOAT,
                "return_value"
        );

        var instructions = block.getNonTerminatorInstructions();
        var castInsn = assertInstanceOf(CallIntrinsicInsn.class, instructions.getFirst());
        var castedVariable = session.targetFunction().getVariableById(materializedSlotId);
        var castArgument = assertInstanceOf(LirInstruction.VariableOperand.class, castInsn.args().getFirst());
        assertNotNull(castedVariable);

        assertAll(
                () -> assertEquals(1, instructions.size()),
                () -> assertNotEquals("source_int", materializedSlotId),
                () -> assertEquals(materializedSlotId, castInsn.resultId()),
                () -> assertEquals("c_int_to_float", castInsn.intrinsicName()),
                () -> assertEquals(1, castInsn.args().size()),
                () -> assertEquals("source_int", castArgument.id()),
                () -> assertEquals(GdFloatType.FLOAT, castedVariable.type())
        );
    }

    @Test
    void materializeFrontendBoundaryValueCastsVectoriSourcesForVectorTargetsThroughIntrinsic() throws Exception {
        var cases = List.of(
                new IntrinsicCastCase(
                        "source_vector2i",
                        GdIntVectorType.VECTOR2I,
                        GdFloatVectorType.VECTOR2,
                        "c_vector2i_to_vector2"
                ),
                new IntrinsicCastCase(
                        "source_vector3i",
                        GdIntVectorType.VECTOR3I,
                        GdFloatVectorType.VECTOR3,
                        "c_vector3i_to_vector3"
                ),
                new IntrinsicCastCase(
                        "source_vector4i",
                        GdIntVectorType.VECTOR4I,
                        GdFloatVectorType.VECTOR4,
                        "c_vector4i_to_vector4"
                )
        );

        for (var testCase : cases) {
            var session = prepareSession();
            var block = new LirBasicBlock("entry_" + testCase.sourceSlotId());
            session.ensureVariable(testCase.sourceSlotId(), testCase.sourceType());

            var materializedSlotId = session.materializeFrontendBoundaryValue(
                    block,
                    testCase.sourceSlotId(),
                    testCase.sourceType(),
                    testCase.targetType(),
                    "vector_boundary"
            );

            var instructions = block.getNonTerminatorInstructions();
            var castInsn = assertInstanceOf(CallIntrinsicInsn.class, instructions.getFirst());
            var castedVariable = session.targetFunction().getVariableById(materializedSlotId);
            var castArgument = assertInstanceOf(LirInstruction.VariableOperand.class, castInsn.args().getFirst());
            assertNotNull(castedVariable);

            assertAll(
                    testCase.intrinsicName(),
                    () -> assertEquals(1, instructions.size()),
                    () -> assertNotEquals(testCase.sourceSlotId(), materializedSlotId),
                    () -> assertEquals(materializedSlotId, castInsn.resultId()),
                    () -> assertEquals(testCase.intrinsicName(), castInsn.intrinsicName()),
                    () -> assertEquals(1, castInsn.args().size()),
                    () -> assertEquals(testCase.sourceSlotId(), castArgument.id()),
                    () -> assertEquals(testCase.targetType(), castedVariable.type())
            );
        }
    }

    @Test
    void materializeFrontendBoundaryValueConstructsStringFamilySourcesThroughBuiltinConstructor() throws Exception {
        var cases = List.of(
                new BuiltinConstructorBoundaryCase(
                        "source_text",
                        GdStringType.STRING,
                        GdStringNameType.STRING_NAME
                ),
                new BuiltinConstructorBoundaryCase(
                        "source_name",
                        GdStringNameType.STRING_NAME,
                        GdStringType.STRING
                )
        );

        for (var testCase : cases) {
            var session = prepareSession();
            var block = new LirBasicBlock("entry_" + testCase.sourceSlotId());
            session.ensureVariable(testCase.sourceSlotId(), testCase.sourceType());

            var materializedSlotId = session.materializeFrontendBoundaryValue(
                    block,
                    testCase.sourceSlotId(),
                    testCase.sourceType(),
                    testCase.targetType(),
                    "string_family_boundary"
            );

            var instructions = block.getNonTerminatorInstructions();
            var constructInsn = assertInstanceOf(ConstructBuiltinInsn.class, instructions.getFirst());
            var constructedVariable = session.targetFunction().getVariableById(materializedSlotId);
            var constructorArgument = assertInstanceOf(
                    LirInstruction.VariableOperand.class,
                    constructInsn.args().getFirst()
            );
            assertNotNull(constructedVariable);

            assertAll(
                    testCase.sourceType().getTypeName() + " -> " + testCase.targetType().getTypeName(),
                    () -> assertEquals(1, instructions.size()),
                    () -> assertNotEquals(testCase.sourceSlotId(), materializedSlotId),
                    () -> assertEquals(materializedSlotId, constructInsn.resultId()),
                    () -> assertEquals(1, constructInsn.args().size()),
                    () -> assertEquals(testCase.sourceSlotId(), constructorArgument.id()),
                    () -> assertEquals(testCase.targetType(), constructedVariable.type())
            );
        }
    }

    @Test
    void materializeSubscriptKeySelectsAccessKindFromStringFamilyContainerKeyType() throws Exception {
        var session = prepareSession();
        var nameBlock = new LirBasicBlock("string_key_to_string_name");
        var textBlock = new LirBasicBlock("string_name_key_to_string");
        var nameDictionary = new GdDictionaryType(GdStringNameType.STRING_NAME, GdVariantType.VARIANT);
        var textDictionary = new GdDictionaryType(GdStringType.STRING, GdVariantType.VARIANT);
        session.ensureVariable("source_text", GdStringType.STRING);
        session.ensureVariable("source_name", GdStringNameType.STRING_NAME);

        var nameKey = session.materializeSubscriptKey(
                nameBlock,
                "source_text",
                GdStringType.STRING,
                nameDictionary,
                null,
                "string_key_for_string_name_dictionary"
        );
        var textKey = session.materializeSubscriptKey(
                textBlock,
                "source_name",
                GdStringNameType.STRING_NAME,
                textDictionary,
                null,
                "string_name_key_for_string_dictionary"
        );

        var nameConstruct = assertInstanceOf(
                ConstructBuiltinInsn.class,
                nameBlock.getNonTerminatorInstructions().getFirst()
        );
        var textConstruct = assertInstanceOf(
                ConstructBuiltinInsn.class,
                textBlock.getNonTerminatorInstructions().getFirst()
        );
        var nameArgument = assertInstanceOf(LirInstruction.VariableOperand.class, nameConstruct.args().getFirst());
        var textArgument = assertInstanceOf(LirInstruction.VariableOperand.class, textConstruct.args().getFirst());
        var nameVariable = session.targetFunction().getVariableById(nameKey.slotId());
        var textVariable = session.targetFunction().getVariableById(textKey.slotId());
        assertNotNull(nameVariable);
        assertNotNull(textVariable);

        assertAll(
                () -> assertEquals(1, nameBlock.getNonTerminatorInstructions().size()),
                () -> assertEquals(1, textBlock.getNonTerminatorInstructions().size()),
                () -> assertEquals("source_text", nameArgument.id()),
                () -> assertEquals("source_name", textArgument.id()),
                () -> assertEquals(nameConstruct.resultId(), nameKey.slotId()),
                () -> assertEquals(textConstruct.resultId(), textKey.slotId()),
                () -> assertEquals(GdStringNameType.STRING_NAME, nameKey.type()),
                () -> assertEquals(GdStringType.STRING, textKey.type()),
                () -> assertEquals(GdStringNameType.STRING_NAME, nameVariable.type()),
                () -> assertEquals(GdStringType.STRING, textVariable.type()),
                () -> assertEquals(FrontendSubscriptAccessSupport.AccessKind.NAMED, nameKey.accessKind()),
                () -> assertEquals(FrontendSubscriptAccessSupport.AccessKind.KEYED, textKey.accessKind()),
                // These raw-source checks pin the route drift that would happen if a caller ignored
                // the bundled `MaterializedSubscriptKey` result and recalculated from the source key.
                () -> assertEquals(
                        FrontendSubscriptAccessSupport.AccessKind.KEYED,
                        FrontendSubscriptAccessSupport.determineAccessKind(nameDictionary, GdStringType.STRING)
                ),
                () -> assertEquals(
                        FrontendSubscriptAccessSupport.AccessKind.NAMED,
                        FrontendSubscriptAccessSupport.determineAccessKind(textDictionary, GdStringNameType.STRING_NAME)
                )
        );
    }

    @Test
    void materializeFrontendBoundaryValueRejectsStringFamilyNeighborBoundaries() throws Exception {
        var session = prepareSession();
        var nodePathBlock = new LirBasicBlock("string_to_node_path");
        var intBlock = new LirBasicBlock("string_name_to_int");
        session.ensureVariable("source_text", GdStringType.STRING);
        session.ensureVariable("source_name", GdStringNameType.STRING_NAME);

        var nodePathException = assertThrows(
                IllegalStateException.class,
                () -> session.materializeFrontendBoundaryValue(
                        nodePathBlock,
                        "source_text",
                        GdStringType.STRING,
                        GdNodePathType.NODE_PATH,
                        "string_to_node_path_boundary"
                )
        );
        var intException = assertThrows(
                IllegalStateException.class,
                () -> session.materializeFrontendBoundaryValue(
                        intBlock,
                        "source_name",
                        GdStringNameType.STRING_NAME,
                        GdIntType.INT,
                        "string_name_to_int_boundary"
                )
        );

        assertAll(
                () -> assertTrue(nodePathException.getMessage().contains("string_to_node_path_boundary"), nodePathException.getMessage()),
                () -> assertTrue(nodePathException.getMessage().contains("rejects source type 'String'"), nodePathException.getMessage()),
                () -> assertTrue(intException.getMessage().contains("string_name_to_int_boundary"), intException.getMessage()),
                () -> assertTrue(intException.getMessage().contains("rejects source type 'StringName'"), intException.getMessage()),
                () -> assertTrue(nodePathBlock.getNonTerminatorInstructions().isEmpty()),
                () -> assertTrue(intBlock.getNonTerminatorInstructions().isEmpty())
        );
    }

    @Test
    void materializeFrontendBoundaryValueRejectsUnsupportedVectorIntrinsicCastBoundaries() throws Exception {
        var session = prepareSession();
        var reverseBlock = new LirBasicBlock("reverse_vector");
        var wrongDimensionBlock = new LirBasicBlock("wrong_dimension_vector");
        session.ensureVariable("source_vector3", GdFloatVectorType.VECTOR3);
        session.ensureVariable("source_vector2i", GdIntVectorType.VECTOR2I);

        var reverseException = assertThrows(
                IllegalStateException.class,
                () -> session.materializeFrontendBoundaryValue(
                        reverseBlock,
                        "source_vector3",
                        GdFloatVectorType.VECTOR3,
                        GdIntVectorType.VECTOR3I,
                        "reverse_vector_boundary"
                )
        );
        var wrongDimensionException = assertThrows(
                IllegalStateException.class,
                () -> session.materializeFrontendBoundaryValue(
                        wrongDimensionBlock,
                        "source_vector2i",
                        GdIntVectorType.VECTOR2I,
                        GdFloatVectorType.VECTOR3,
                        "wrong_dimension_vector_boundary"
                )
        );

        assertAll(
                () -> assertTrue(reverseException.getMessage().contains("reverse_vector_boundary"), reverseException.getMessage()),
                () -> assertTrue(reverseException.getMessage().contains("rejects source type 'Vector3'"), reverseException.getMessage()),
                () -> assertTrue(wrongDimensionException.getMessage().contains("wrong_dimension_vector_boundary"), wrongDimensionException.getMessage()),
                () -> assertTrue(wrongDimensionException.getMessage().contains("rejects source type 'Vector2i'"), wrongDimensionException.getMessage()),
                () -> assertTrue(reverseBlock.getNonTerminatorInstructions().isEmpty()),
                () -> assertTrue(wrongDimensionBlock.getNonTerminatorInstructions().isEmpty())
        );
    }

    @Test
    void materializeFrontendBoundaryValueMaterializesObjectNullForNilSources() throws Exception {
        var session = prepareSession();
        var block = new LirBasicBlock("entry");
        session.ensureVariable("source_nil", GdNilType.NIL);

        var materializedSlotId = session.materializeFrontendBoundaryValue(
                block,
                "source_nil",
                GdNilType.NIL,
                GdObjectType.OBJECT,
                "object_return"
        );

        var instructions = block.getNonTerminatorInstructions();
        var literalNullInsn = assertInstanceOf(LiteralNullInsn.class, instructions.getFirst());
        var nullVariable = session.targetFunction().getVariableById(materializedSlotId);
        assertNotNull(nullVariable);

        assertAll(
                () -> assertEquals(1, instructions.size()),
                () -> assertNotEquals("source_nil", materializedSlotId),
                () -> assertEquals(materializedSlotId, literalNullInsn.resultId()),
                () -> assertEquals(GdObjectType.OBJECT, nullVariable.type())
        );
    }

    @Test
    void materializeFrontendBoundaryValueKeepsDirectRoutesInstructionFree() throws Exception {
        var session = prepareSession();
        var concreteBlock = new LirBasicBlock("concrete_direct");
        var variantBlock = new LirBasicBlock("variant_direct");
        var floatBlock = new LirBasicBlock("float_direct");
        session.ensureVariable("source_value", GdIntType.INT);
        session.ensureVariable("source_variant", GdVariantType.VARIANT);
        session.ensureVariable("source_float", GdFloatType.FLOAT);

        var directConcreteSlotId = session.materializeFrontendBoundaryValue(
                concreteBlock,
                "source_value",
                GdIntType.INT,
                GdIntType.INT,
                "local_init"
        );
        var directVariantSlotId = session.materializeFrontendBoundaryValue(
                variantBlock,
                "source_variant",
                GdVariantType.VARIANT,
                GdVariantType.VARIANT,
                "local_init"
        );
        var directFloatSlotId = session.materializeFrontendBoundaryValue(
                floatBlock,
                "source_float",
                GdFloatType.FLOAT,
                GdFloatType.FLOAT,
                "local_init"
        );

        assertAll(
                () -> assertEquals("source_value", directConcreteSlotId),
                () -> assertEquals("source_variant", directVariantSlotId),
                () -> assertEquals("source_float", directFloatSlotId),
                () -> assertTrue(concreteBlock.getNonTerminatorInstructions().isEmpty()),
                () -> assertTrue(variantBlock.getNonTerminatorInstructions().isEmpty()),
                () -> assertTrue(floatBlock.getNonTerminatorInstructions().isEmpty())
        );
    }

    @Test
    void materializeFrontendBoundaryValueFailsFastForVoidSourceOrTarget() throws Exception {
        var session = prepareSession();
        var sourceVoidBlock = new LirBasicBlock("source_void");
        var targetVoidBlock = new LirBasicBlock("target_void");
        session.ensureVariable("source_void", GdVoidType.VOID);
        session.ensureVariable("source_value", GdIntType.INT);

        var sourceException = assertThrows(
                IllegalStateException.class,
                () -> session.materializeFrontendBoundaryValue(
                        sourceVoidBlock,
                        "source_void",
                        GdVoidType.VOID,
                        GdIntType.INT,
                        "return_value"
                )
        );
        var targetException = assertThrows(
                IllegalStateException.class,
                () -> session.materializeFrontendBoundaryValue(
                        targetVoidBlock,
                        "source_value",
                        GdIntType.INT,
                        GdVoidType.VOID,
                        "call_arg"
                )
        );

        assertAll(
                () -> assertTrue(sourceException.getMessage().contains("return_value"), sourceException.getMessage()),
                () -> assertTrue(sourceException.getMessage().contains("source type void"), sourceException.getMessage()),
                () -> assertTrue(sourceException.getMessage().contains("result slots"), sourceException.getMessage()),
                () -> assertTrue(targetException.getMessage().contains("call_arg"), targetException.getMessage()),
                () -> assertTrue(targetException.getMessage().contains("target type void"), targetException.getMessage()),
                () -> assertTrue(sourceVoidBlock.getNonTerminatorInstructions().isEmpty()),
                () -> assertTrue(targetVoidBlock.getNonTerminatorInstructions().isEmpty())
        );
    }

    @Test
    void materializeFrontendBoundaryValueFailsFastWhenCompilerOnlyTypeLeaksIntoBoundary() throws Exception {
        var session = prepareSession();
        var sourceLeakBlock = new LirBasicBlock("compiler_only_source");
        var targetLeakBlock = new LirBasicBlock("compiler_only_target");
        session.ensureVariable("iter_slot", GdccForRangeIterType.FOR_RANGE_ITER);
        session.ensureVariable("value_slot", GdIntType.INT);

        var sourceFailure = assertThrows(
                IllegalStateException.class,
                () -> session.materializeFrontendBoundaryValue(
                        sourceLeakBlock,
                        "iter_slot",
                        GdccForRangeIterType.FOR_RANGE_ITER,
                        GdVariantType.VARIANT,
                        "compiler_only_source_boundary"
                )
        );
        var targetFailure = assertThrows(
                IllegalStateException.class,
                () -> session.materializeFrontendBoundaryValue(
                        targetLeakBlock,
                        "value_slot",
                        GdVariantType.VARIANT,
                        GdccForRangeIterType.FOR_RANGE_ITER,
                        "compiler_only_target_boundary"
                )
        );

        assertAll(
                () -> assertTrue(sourceFailure.getMessage().contains("compiler-only type leaked into frontend boundary source")),
                () -> assertTrue(sourceFailure.getMessage().contains("compiler_only_source_boundary")),
                () -> assertTrue(targetFailure.getMessage().contains("compiler-only type leaked into frontend boundary target")),
                () -> assertTrue(targetFailure.getMessage().contains("compiler_only_target_boundary")),
                () -> assertTrue(sourceLeakBlock.getNonTerminatorInstructions().isEmpty()),
                () -> assertTrue(targetLeakBlock.getNonTerminatorInstructions().isEmpty())
        );
    }

    @Test
    void loweringProcessorRegistryReturnsContinuationBlockChosenByProcessor() throws Exception {
        var session = prepareSession();
        var entryBlock = new LirBasicBlock("entry");
        var continuationBlock = new LirBasicBlock("continuation");
        var registry = FrontendInsnLoweringProcessorRegistry.of(
                "test node",
                new FrontendInsnLoweringProcessor<TestNode, Void>() {
                    @Override
                    public @NotNull Class<TestNode> nodeType() {
                        return TestNode.class;
                    }

                    @Override
                    public @NotNull LirBasicBlock lower(
                            @NotNull FrontendBodyLoweringSession innerSession,
                            @NotNull LirBasicBlock block,
                            @NotNull TestNode node,
                            @Nullable Void context
                    ) {
                        assertSame(session, innerSession);
                        assertSame(entryBlock, block);
                        return continuationBlock;
                    }
                }
        );

        var actualBlock = registry.lower(session, entryBlock, new TestNode(), null);

        assertSame(continuationBlock, actualBlock);
    }

    @Test
    void loweringProcessorRegistryRequiresExactNodeTypeMatch() throws Exception {
        var session = prepareSession();
        var entryBlock = new LirBasicBlock("entry");
        var registry = FrontendInsnLoweringProcessorRegistry.of(
                "test node",
                new FrontendInsnLoweringProcessor<TestNode, Void>() {
                    @Override
                    public @NotNull Class<TestNode> nodeType() {
                        return TestNode.class;
                    }

                    @Override
                    public @NotNull LirBasicBlock lower(
                            @NotNull FrontendBodyLoweringSession innerSession,
                            @NotNull LirBasicBlock block,
                            @NotNull TestNode node,
                            @Nullable Void context
                    ) {
                        throw new AssertionError("Exact-type registry must not route subclasses to a parent processor");
                    }
                }
        );

        var exception = assertThrows(
                IllegalStateException.class,
                () -> registry.lower(session, entryBlock, new DerivedTestNode(), null)
        );

        assertTrue(exception.getMessage().contains(DerivedTestNode.class.getName()), exception.getMessage());
    }

    private static @NotNull FrontendBodyLoweringSession prepareSession() throws Exception {
        return prepareSessionFixture().session();
    }

    private static @NotNull SessionFixture prepareSessionFixture() throws Exception {
        var diagnostics = new DiagnosticManager();
        var module = parseModule(
                List.of(new SourceFixture(
                        "body_lowering_session_helper.gd",
                        """
                                class_name BodyLoweringSessionHelper
                                extends RefCounted
                                
                                func ping(seed: int) -> int:
                                    return seed
                                """
                )),
                Map.of("BodyLoweringSessionHelper", "RuntimeBodyLoweringSessionHelper")
        );
        var context = new FrontendLoweringContext(
                module,
                new ClassRegistry(ExtensionApiLoader.loadDefault()),
                diagnostics
        );
        new FrontendLoweringAnalysisPass().run(context);
        new FrontendLoweringClassSkeletonPass().run(context);
        new FrontendLoweringFunctionPreparationPass().run(context);
        new FrontendLoweringBuildCfgPass().run(context);
        assertFalse(diagnostics.hasErrors(), () -> "Unexpected lowering diagnostics: " + diagnostics.snapshot());
        var functionContext = requireContext(context.requireFunctionLoweringContexts());
        return new SessionFixture(
                new FrontendBodyLoweringSession(
                        functionContext,
                        context.classRegistry()
                ),
                functionContext
        );
    }

    private static @NotNull AttributePropertyStep property(@NotNull String name) {
        return new AttributePropertyStep(name, SYNTHETIC_RANGE);
    }

    private record SessionFixture(
            @NotNull FrontendBodyLoweringSession session,
            @NotNull FunctionLoweringContext context
    ) {
    }

    private record MemberStatusCase(
            @NotNull String label,
            @NotNull FrontendResolvedMember member
    ) {
    }

    private static @NotNull FrontendModule parseModule(
            @NotNull List<SourceFixture> fixtures,
            @NotNull Map<String, String> topLevelCanonicalNameMap
    ) {
        var parserService = new GdScriptParserService();
        var parseDiagnostics = new DiagnosticManager();
        var units = fixtures.stream()
                .map(fixture -> parserService.parseUnit(Path.of("tmp", fixture.fileName()), fixture.source(), parseDiagnostics))
                .toList();
        assertTrue(parseDiagnostics.isEmpty(), () -> "Unexpected parse diagnostics: " + parseDiagnostics.snapshot());
        return new FrontendModule("test_module", units, topLevelCanonicalNameMap);
    }

    private static @NotNull FunctionLoweringContext requireContext(@NotNull List<FunctionLoweringContext> contexts) {
        return contexts.stream()
                .filter(context -> context.kind() == FunctionLoweringContext.Kind.EXECUTABLE_BODY)
                .filter(context -> context.owningClass().getName().equals("RuntimeBodyLoweringSessionHelper"))
                .filter(context -> context.targetFunction().getName().equals("ping"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing executable body context for RuntimeBodyLoweringSessionHelper.ping"));
    }

    private record SourceFixture(
            @NotNull String fileName,
            @NotNull String source
    ) {
    }

    private record IntrinsicCastCase(
            @NotNull String sourceSlotId,
            @NotNull GdType sourceType,
            @NotNull GdType targetType,
            @NotNull String intrinsicName
    ) {
    }

    private record BuiltinConstructorBoundaryCase(
            @NotNull String sourceSlotId,
            @NotNull GdType sourceType,
            @NotNull GdType targetType
    ) {
    }

    private static class TestNode {
    }

    private static final class DerivedTestNode extends TestNode {
    }
}
