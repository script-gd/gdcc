package gd.script.gdcc.frontend.lowering.pass.body;

import gd.script.gdcc.frontend.constant.GdConstantValue;
import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.lowering.FrontendLoweringContext;
import gd.script.gdcc.frontend.lowering.FunctionLoweringContext;
import gd.script.gdcc.frontend.lowering.pass.FrontendLoweringAnalysisPass;
import gd.script.gdcc.frontend.lowering.pass.FrontendLoweringBuildCfgPass;
import gd.script.gdcc.frontend.lowering.pass.FrontendLoweringClassSkeletonPass;
import gd.script.gdcc.frontend.lowering.pass.FrontendLoweringFunctionPreparationPass;
import gd.script.gdcc.frontend.parse.FrontendModule;
import gd.script.gdcc.frontend.parse.GdScriptParserService;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.lir.LirBasicBlock;
import gd.script.gdcc.lir.LirClassDef;
import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.lir.LirInstruction;
import gd.script.gdcc.lir.LirModule;
import gd.script.gdcc.lir.insn.AssignInsn;
import gd.script.gdcc.lir.insn.ConstructBuiltinInsn;
import gd.script.gdcc.lir.insn.LiteralBoolInsn;
import gd.script.gdcc.lir.insn.LiteralFloatInsn;
import gd.script.gdcc.lir.insn.LiteralIntInsn;
import gd.script.gdcc.lir.insn.LiteralStringInsn;
import gd.script.gdcc.lir.insn.PackVariantInsn;
import gd.script.gdcc.lir.parser.DomLirParser;
import gd.script.gdcc.lir.parser.DomLirSerializer;
import gd.script.gdcc.lir.parser.SimpleLirBlockInsnParser;
import gd.script.gdcc.lir.parser.SimpleLirBlockInsnSerializer;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdAABBType;
import gd.script.gdcc.type.GdBasisType;
import gd.script.gdcc.type.GdColorType;
import gd.script.gdcc.type.GdFloatType;
import gd.script.gdcc.type.GdFloatVectorType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdIntVectorType;
import gd.script.gdcc.type.GdPlaneType;
import gd.script.gdcc.type.GdProjectionType;
import gd.script.gdcc.type.GdQuaternionType;
import gd.script.gdcc.type.GdRect2Type;
import gd.script.gdcc.type.GdRect2iType;
import gd.script.gdcc.type.GdTransform2DType;
import gd.script.gdcc.type.GdTransform3DType;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.type.GdVariantType;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Anchors the §8.1 canonical materialization contract of
/// `FrontendBodyLoweringSession.materializeConstantValue`: scalar literals, the fixed
/// literal+construct recipe chain per controlled builtin type, variable-only construct
/// operands, non-ref exact-typed scratch slots, and simple/DOM serializer roundtrips — in both
/// directions (wrong slot types and ref slots must fail fast, never silently coerce).
class FrontendConstantMaterializationTest {

    // --- scalar materialization ---

    @Test
    void materializeScalarConstantsEmitMatchingLiteralInsns() throws Exception {
        var session = prepareSession();
        var block = new LirBasicBlock("entry");

        session.materializeConstantValue(block, new GdConstantValue.BoolValue(true), "out_bool");
        session.materializeConstantValue(block, new GdConstantValue.IntValue(-42L), "out_int");
        session.materializeConstantValue(block, new GdConstantValue.FloatValue(0.30000000000000004), "out_float");
        session.materializeConstantValue(block, new GdConstantValue.StringValue("payload"), "out_string");

        var insns = block.getNonTerminatorInstructions();
        var boolInsn = assertInstanceOf(LiteralBoolInsn.class, insns.get(0));
        var intInsn = assertInstanceOf(LiteralIntInsn.class, insns.get(1));
        var floatInsn = assertInstanceOf(LiteralFloatInsn.class, insns.get(2));
        var stringInsn = assertInstanceOf(LiteralStringInsn.class, insns.get(3));

        assertAll(
                () -> assertEquals(4, insns.size()),
                () -> assertEquals("out_bool", boolInsn.resultId()),
                () -> assertTrue(boolInsn.value()),
                () -> assertEquals("out_int", intInsn.resultId()),
                () -> assertEquals(-42L, intInsn.value()),
                () -> assertEquals("out_float", floatInsn.resultId()),
                // Full double precision survives into the literal — no float32 narrowing on
                // scalar float payloads.
                () -> assertEquals(0.30000000000000004, floatInsn.value()),
                () -> assertEquals("out_string", stringInsn.resultId()),
                () -> assertEquals("payload", stringInsn.value())
        );
    }

    @Test
    void materializeConstantDeclaresMissingResultSlotWithValueType() throws Exception {
        var session = prepareSession();
        var block = new LirBasicBlock("entry");

        session.materializeConstantValue(block, new GdConstantValue.IntValue(7L), "declared_out");

        var variable = session.targetFunction().getVariableById("declared_out");
        assertNotNull(variable);
        assertAll(
                () -> assertEquals(GdIntType.INT, variable.type()),
                () -> assertFalse(variable.ref())
        );
    }

    @Test
    void materializeConstantRejectsMismatchedAndRefResultSlots() throws Exception {
        var session = prepareSession();
        session.ensureVariable("variant_out", GdVariantType.VARIANT);
        session.ensureVariable("vector3_out", GdFloatVectorType.VECTOR3);
        session.targetFunction().createAndAddRefVariable("ref_int_out", GdIntType.INT);
        session.targetFunction().createAndAddRefVariable("ref_vector2_out", GdFloatVectorType.VECTOR2);

        var variantException = assertThrows(
                IllegalStateException.class,
                () -> session.materializeConstantValue(
                        new LirBasicBlock("b1"),
                        new GdConstantValue.IntValue(1L),
                        "variant_out"
                )
        );
        var vectorException = assertThrows(
                IllegalStateException.class,
                () -> session.materializeConstantValue(
                        new LirBasicBlock("b2"),
                        GdConstantValue.BuiltinValue.ofFloats(GdFloatVectorType.VECTOR2, 1.0, 2.0),
                        "vector3_out"
                )
        );
        var refScalarException = assertThrows(
                IllegalStateException.class,
                () -> session.materializeConstantValue(
                        new LirBasicBlock("b3"),
                        new GdConstantValue.IntValue(1L),
                        "ref_int_out"
                )
        );
        var refBuiltinException = assertThrows(
                IllegalStateException.class,
                () -> session.materializeConstantValue(
                        new LirBasicBlock("b4"),
                        GdConstantValue.BuiltinValue.ofFloats(GdFloatVectorType.VECTOR2, 1.0, 2.0),
                        "ref_vector2_out"
                )
        );

        assertAll(
                () -> assertTrue(refScalarException.getMessage().contains("non-ref result slot"),
                        refScalarException.getMessage()),
                () -> assertTrue(refBuiltinException.getMessage().contains("non-ref result slot"),
                        refBuiltinException.getMessage()),
                () -> assertTrue(variantException.getMessage().contains("variant_out"),
                        variantException.getMessage()),
                () -> assertTrue(vectorException.getMessage().contains("vector3_out"),
                        vectorException.getMessage())
        );
    }

    // --- flat vector recipes ---

    @Test
    void materializeFloatVectorsEmitLeafLiteralsThenVariableOnlyConstruct() throws Exception {
        var cases = List.of(
                new VectorCase(GdFloatVectorType.VECTOR2, new double[]{1.0, -0.5}),
                new VectorCase(GdFloatVectorType.VECTOR3, new double[]{1.0, 2.0, 3.5}),
                new VectorCase(GdFloatVectorType.VECTOR4, new double[]{1.0, 2.0, 3.0, 4.25})
        );

        for (var vectorCase : cases) {
            var session = prepareSession();
            var block = new LirBasicBlock("entry");
            var resultSlot = "out_" + vectorCase.type().getTypeName();
            session.materializeConstantValue(
                    block,
                    GdConstantValue.BuiltinValue.ofFloats(vectorCase.type(), vectorCase.leaves()),
                    resultSlot
            );

            var insns = block.getNonTerminatorInstructions();
            var leafCount = vectorCase.leaves().length;
            assertEquals(leafCount + 1, insns.size(), vectorCase.type().getTypeName());
            for (var i = 0; i < leafCount; i++) {
                var leafInsn = assertInstanceOf(LiteralFloatInsn.class, insns.get(i));
                var leafVariable = session.targetFunction().getVariableById(leafInsn.resultId());
                assertNotNull(leafVariable);
                var leafIndex = i;
                assertAll(
                        vectorCase.type().getTypeName() + " leaf[" + i + "]",
                        () -> assertEquals((double) (float) vectorCase.leaves()[leafIndex], leafInsn.value()),
                        () -> assertEquals(GdFloatType.FLOAT, leafVariable.type()),
                        () -> assertFalse(leafVariable.ref())
                );
            }

            var constructInsn = assertInstanceOf(ConstructBuiltinInsn.class, insns.get(leafCount));
            assertAll(
                    vectorCase.type().getTypeName() + " construct",
                    () -> assertEquals(resultSlot, constructInsn.resultId()),
                    () -> assertEquals(leafCount, constructInsn.args().size()),
                    () -> assertTrue(constructInsn.args().stream()
                            .allMatch(LirInstruction.VariableOperand.class::isInstance)),
                    () -> {
                        for (var i = 0; i < leafCount; i++) {
                            var operand = (LirInstruction.VariableOperand) constructInsn.args().get(i);
                            var literal = (LiteralFloatInsn) insns.get(i);
                            assertEquals(literal.resultId(), operand.id(), "operand[" + i + "]");
                        }
                    },
                    () -> assertEquals(vectorCase.type(),
                            session.targetFunction().getVariableById(resultSlot).type())
            );
        }
    }

    @Test
    void materializeIntVectorLeavesEmitIntLiteralsOnInt64ScalarTemps() throws Exception {
        var session = prepareSession();
        var block = new LirBasicBlock("entry");

        session.materializeConstantValue(
                block,
                GdConstantValue.BuiltinValue.ofInts(GdIntVectorType.VECTOR3I, -1, 0, Integer.MAX_VALUE),
                "out_vector3i"
        );

        var insns = block.getNonTerminatorInstructions();
        assertEquals(4, insns.size());
        var expectedLeaves = List.of(-1L, 0L, (long) Integer.MAX_VALUE);
        for (var i = 0; i < 3; i++) {
            var leafInsn = assertInstanceOf(LiteralIntInsn.class, insns.get(i));
            var leafVariable = session.targetFunction().getVariableById(leafInsn.resultId());
            assertNotNull(leafVariable);
            var index = i;
            assertAll(
                    "leaf[" + i + "]",
                    () -> assertEquals(expectedLeaves.get(index), leafInsn.value()),
                    // The int32 component sits on a normal int64 int scalar temp — the int32
                    // guard ran at evaluation, not on the slot type.
                    () -> assertEquals(GdIntType.INT, leafVariable.type())
            );
        }
        var constructInsn = assertInstanceOf(ConstructBuiltinInsn.class, insns.get(3));
        assertEquals("out_vector3i", constructInsn.resultId());
        assertTrue(constructInsn.args().stream()
                .allMatch(LirInstruction.VariableOperand.class::isInstance));
    }

    // --- composite recipes ---

    @Test
    void materializeRect2BuildsPositionThenSizeVectors() throws Exception {
        var session = prepareSession();
        var block = new LirBasicBlock("entry");

        session.materializeConstantValue(
                block,
                GdConstantValue.BuiltinValue.ofFloats(GdRect2Type.RECT2, 1.0, 2.0, 3.0, 4.0),
                "out_rect2"
        );

        // Depth-first emission: each component's leaf literals immediately precede the
        // construct consuming them — lit,lit,Vector2, lit,lit,Vector2, Rect2.
        var insns = block.getNonTerminatorInstructions();
        assertEquals(7, insns.size());
        var positionConstruct = assertInstanceOf(ConstructBuiltinInsn.class, insns.get(2));
        var sizeConstruct = assertInstanceOf(ConstructBuiltinInsn.class, insns.get(5));
        var rectConstruct = assertInstanceOf(ConstructBuiltinInsn.class, insns.get(6));

        var positionArgs = variableOperandIds(positionConstruct);
        var sizeArgs = variableOperandIds(sizeConstruct);
        var rectArgs = variableOperandIds(rectConstruct);
        var positionVariable = session.targetFunction().getVariableById(positionConstruct.resultId());
        var sizeVariable = session.targetFunction().getVariableById(sizeConstruct.resultId());
        assertNotNull(positionVariable);
        assertNotNull(sizeVariable);

        assertAll(
                () -> assertEquals(List.of(literalResultId(insns.get(0)), literalResultId(insns.get(1))),
                        positionArgs),
                () -> assertEquals(List.of(literalResultId(insns.get(3)), literalResultId(insns.get(4))),
                        sizeArgs),
                () -> assertEquals(List.of(positionConstruct.resultId(), sizeConstruct.resultId()),
                        rectArgs),
                () -> assertEquals(GdFloatVectorType.VECTOR2, positionVariable.type()),
                () -> assertEquals(GdFloatVectorType.VECTOR2, sizeVariable.type()),
                () -> assertEquals("out_rect2", rectConstruct.resultId())
        );
    }

    @Test
    void materializeBasisKeepsLogicalColumnVectorOrder() throws Exception {
        var session = prepareSession();
        var block = new LirBasicBlock("entry");

        // Distinct leaf values pin the canonical x/y/z column-vector split — a row-major or
        // transposed recipe would wire the wrong leaves into the wrong Vector3.
        session.materializeConstantValue(
                block,
                GdConstantValue.BuiltinValue.ofFloats(
                        GdBasisType.BASIS,
                        1, 2, 3, 4, 5, 6, 7, 8, 9
                ),
                "out_basis"
        );

        var insns = block.getNonTerminatorInstructions();
        // Depth-first per column vector: lit,lit,lit,Vector3 x3, then Basis(x, y, z).
        assertEquals(13, insns.size());
        var xConstruct = assertInstanceOf(ConstructBuiltinInsn.class, insns.get(3));
        var yConstruct = assertInstanceOf(ConstructBuiltinInsn.class, insns.get(7));
        var zConstruct = assertInstanceOf(ConstructBuiltinInsn.class, insns.get(11));
        var basisConstruct = assertInstanceOf(ConstructBuiltinInsn.class, insns.get(12));

        // The emitted literal payloads themselves must keep the flat leaf order — a swapped
        // column slice could not produce these literal values in this sequence.
        assertAll(
                () -> assertEquals(
                        List.of(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0),
                        insns.stream()
                                .filter(LiteralFloatInsn.class::isInstance)
                                .map(LiteralFloatInsn.class::cast)
                                .map(LiteralFloatInsn::value)
                                .toList()),
                () -> assertEquals(
                        List.of(literalResultId(insns.get(0)), literalResultId(insns.get(1)), literalResultId(insns.get(2))),
                        variableOperandIds(xConstruct)),
                () -> assertEquals(
                        List.of(literalResultId(insns.get(4)), literalResultId(insns.get(5)), literalResultId(insns.get(6))),
                        variableOperandIds(yConstruct)),
                () -> assertEquals(
                        List.of(literalResultId(insns.get(8)), literalResultId(insns.get(9)), literalResultId(insns.get(10))),
                        variableOperandIds(zConstruct)),
                () -> assertEquals(
                        List.of(xConstruct.resultId(), yConstruct.resultId(), zConstruct.resultId()),
                        variableOperandIds(basisConstruct)),
                () -> assertEquals("out_basis", basisConstruct.resultId())
        );
    }

    @Test
    void materializeTransform3DNestsBasisThenOrigin() throws Exception {
        var session = prepareSession();
        var block = new LirBasicBlock("entry");

        session.materializeConstantValue(
                block,
                GdConstantValue.BuiltinValue.ofFloats(
                        GdTransform3DType.TRANSFORM3D,
                        1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12
                ),
                "out_transform3d"
        );

        // Depth-first emission: the Basis part first (lit,lit,lit,Vector3 x3, Basis), then the
        // origin part (lit,lit,lit,Vector3), then the Transform3D parent consuming
        // [basis, origin].
        var insns = block.getNonTerminatorInstructions();
        assertEquals(18, insns.size());
        var basisConstruct = assertInstanceOf(ConstructBuiltinInsn.class, insns.get(12));
        var originConstruct = assertInstanceOf(ConstructBuiltinInsn.class, insns.get(16));
        var transformConstruct = assertInstanceOf(ConstructBuiltinInsn.class, insns.get(17));

        var basisVariable = session.targetFunction().getVariableById(basisConstruct.resultId());
        var originVariable = session.targetFunction().getVariableById(originConstruct.resultId());
        assertNotNull(basisVariable);
        assertNotNull(originVariable);

        // The literal payloads keep the flat leaf order — basis leaves 0..8 (non-symmetric,
        // so a transposed recipe would produce different values here) then origin 9..11.
        assertAll(
                () -> assertEquals(
                        List.of(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0, 11.0, 12.0),
                        insns.stream()
                                .filter(LiteralFloatInsn.class::isInstance)
                                .map(LiteralFloatInsn.class::cast)
                                .map(LiteralFloatInsn::value)
                                .toList()),
                () -> assertEquals(GdBasisType.BASIS, basisVariable.type()),
                () -> assertEquals(GdFloatVectorType.VECTOR3, originVariable.type()),
                () -> assertEquals(
                        List.of(literalResultId(insns.get(0)), literalResultId(insns.get(1)), literalResultId(insns.get(2))),
                        variableOperandIds(
                                assertInstanceOf(ConstructBuiltinInsn.class, insns.get(3)))),
                () -> assertEquals(
                        List.of(literalResultId(insns.get(13)), literalResultId(insns.get(14)), literalResultId(insns.get(15))),
                        variableOperandIds(originConstruct)),
                () -> assertEquals(
                        List.of(basisConstruct.resultId(), originConstruct.resultId()),
                        variableOperandIds(transformConstruct)),
                () -> assertEquals("out_transform3d", transformConstruct.resultId())
        );
    }

    @Test
    void materializeProjectionBuildsFourVector4Columns() throws Exception {
        var session = prepareSession();
        var block = new LirBasicBlock("entry");
        var leaves = new double[16];
        for (var i = 0; i < 16; i++) {
            leaves[i] = i + 0.25;
        }

        session.materializeConstantValue(
                block,
                GdConstantValue.BuiltinValue.ofFloats(GdProjectionType.PROJECTION, leaves),
                "out_projection"
        );

        var insns = block.getNonTerminatorInstructions();
        // Depth-first per column: lit,lit,lit,lit,Vector4 x4, then Projection(c0, c1, c2, c3).
        assertEquals(21, insns.size());
        var columnResults = new ArrayList<String>(4);
        for (var column = 0; column < 4; column++) {
            var columnConstruct = assertInstanceOf(ConstructBuiltinInsn.class, insns.get(column * 5 + 4));
            columnResults.add(columnConstruct.resultId());
            var expectedLeafIds = new ArrayList<String>(4);
            for (var leaf = 0; leaf < 4; leaf++) {
                expectedLeafIds.add(literalResultId(insns.get(column * 5 + leaf)));
            }
            var expected = expectedLeafIds;
            assertEquals(expected, variableOperandIds(columnConstruct), "column " + column);
        }
        var projectionConstruct = assertInstanceOf(ConstructBuiltinInsn.class, insns.get(20));
        // Literal payloads keep the flat leaf order — a permuted column recipe would still
        // satisfy the wiring checks but not this value sequence.
        assertAll(
                () -> assertEquals(
                        IntStream.range(0, 16)
                                .mapToObj(index -> index + 0.25)
                                .toList(),
                        insns.stream()
                                .filter(LiteralFloatInsn.class::isInstance)
                                .map(LiteralFloatInsn.class::cast)
                                .map(LiteralFloatInsn::value)
                                .toList()),
                () -> assertEquals(columnResults, variableOperandIds(projectionConstruct)),
                () -> assertEquals("out_projection", projectionConstruct.resultId())
        );
    }

    @Test
    void materializeRect2iBuildsVector2iParts() throws Exception {
        var session = prepareSession();
        var block = new LirBasicBlock("entry");

        session.materializeConstantValue(
                block,
                GdConstantValue.BuiltinValue.ofInts(GdRect2iType.RECT2I, 1, 2, 3, 4),
                "out_rect2i"
        );

        // Depth-first emission: lit,lit,Vector2i, lit,lit,Vector2i, Rect2i.
        var insns = block.getNonTerminatorInstructions();
        assertEquals(7, insns.size());
        for (var i : new int[]{0, 1, 3, 4}) {
            assertInstanceOf(LiteralIntInsn.class, insns.get(i));
        }
        var positionConstruct = assertInstanceOf(ConstructBuiltinInsn.class, insns.get(2));
        var rectConstruct = assertInstanceOf(ConstructBuiltinInsn.class, insns.get(6));
        var positionVariable = session.targetFunction().getVariableById(positionConstruct.resultId());
        assertNotNull(positionVariable);
        assertAll(
                () -> assertEquals(GdIntVectorType.VECTOR2I, positionVariable.type()),
                () -> assertEquals("out_rect2i", rectConstruct.resultId())
        );
    }

    @Test
    void materializeFlatScalarPartsCoverPlaneQuaternionColorAabbTransform2d() throws Exception {
        record RecipeCase(GdType type, int literalCount, int totalInsnCount) {
        }

        var cases = List.of(
                new RecipeCase(GdPlaneType.PLANE, 4, 5),
                new RecipeCase(GdQuaternionType.QUATERNION, 4, 5),
                new RecipeCase(GdColorType.COLOR, 4, 5),
                // Composite recipes interleave depth-first: each sub-component's leaf literals
                // sit right before the construct consuming them.
                new RecipeCase(GdAABBType.AABB, 6, 9),          // 2 x (3 lit + Vector3) + parent
                new RecipeCase(GdTransform2DType.TRANSFORM2D, 6, 10) // 3 x (2 lit + Vector2) + parent
        );
        for (var recipeCase : cases) {
            var session = prepareSession();
            var block = new LirBasicBlock("entry");
            var leaves = new double[recipeCase.literalCount()];
            for (var i = 0; i < leaves.length; i++) {
                leaves[i] = i + 1.0;
            }
            session.materializeConstantValue(
                    block,
                    GdConstantValue.BuiltinValue.ofFloats(recipeCase.type(), leaves),
                    "out_" + recipeCase.type().getTypeName()
            );

            var insns = block.getNonTerminatorInstructions();
            var literalInsnCount = insns.stream()
                    .filter(LiteralFloatInsn.class::isInstance)
                    .count();
            var parentConstruct = assertInstanceOf(ConstructBuiltinInsn.class, insns.getLast());
            assertAll(
                    recipeCase.type().getTypeName(),
                    () -> assertEquals(recipeCase.totalInsnCount(), insns.size()),
                    () -> assertEquals(recipeCase.literalCount(), literalInsnCount),
                    () -> assertTrue(parentConstruct.args().stream()
                            .allMatch(LirInstruction.VariableOperand.class::isInstance))
            );
        }
    }

    @Test
    void materializedValueFeedsExistingVariantBoundaryPath() throws Exception {
        var session = prepareSession();
        var block = new LirBasicBlock("entry");
        session.ensureVariable("variant_slot", GdVariantType.VARIANT);

        session.materializeConstantValue(
                block,
                GdConstantValue.BuiltinValue.ofFloats(GdFloatVectorType.VECTOR2, 1.0, 2.0),
                "scratch_vector"
        );
        block.appendNonTerminatorInstruction(new PackVariantInsn("variant_slot", "scratch_vector"));

        var packInsn = assertInstanceOf(PackVariantInsn.class, block.getNonTerminatorInstructions().getLast());
        assertAll(
                () -> assertEquals("variant_slot", packInsn.resultId()),
                () -> assertEquals("scratch_vector", packInsn.valueId()),
                () -> assertFalse(session.targetFunction().getVariableById("scratch_vector").ref())
        );
    }

    // --- roundtrips ---

    @Test
    void materializedConstantChainRoundTripsThroughSimpleSerializer() throws Exception {
        var session = prepareSession();
        var block = new LirBasicBlock("entry");
        session.materializeConstantValue(
                block,
                GdConstantValue.BuiltinValue.ofFloats(
                        GdFloatVectorType.VECTOR2,
                        Double.NaN,
                        Double.NEGATIVE_INFINITY
                ),
                "out_vector2"
        );
        session.materializeConstantValue(block, new GdConstantValue.FloatValue(-0.0), "out_neg_zero");
        session.materializeConstantValue(block, new GdConstantValue.IntValue(Long.MIN_VALUE), "out_min_int");
        block.appendNonTerminatorInstruction(new AssignInsn("alias_out", "out_vector2"));

        var reparsed = simpleRoundTrip(block.getNonTerminatorInstructions());
        assertEquals(block.getNonTerminatorInstructions().size(), reparsed.size());
        for (var i = 0; i < reparsed.size(); i++) {
            var expected = block.getNonTerminatorInstructions().get(i);
            var index = i;
            assertTrue(expected.checkEquals(reparsed.get(i)),
                    "instruction " + index + ": " + expected);
        }
    }

    @Test
    void materializedConstantChainRoundTripsThroughDomSerializer() throws Exception {
        var session = prepareSession();
        var block = new LirBasicBlock("entry");
        session.materializeConstantValue(block, new GdConstantValue.FloatValue(0.30000000000000004), "precise");
        session.materializeConstantValue(block, new GdConstantValue.StringValue("plain"), "text");
        session.materializeConstantValue(
                block,
                GdConstantValue.BuiltinValue.ofFloats(GdBasisType.BASIS,
                        1, 0, 0, 0, 1, 0, 0, 0, 1),
                "rotation"
        );

        var reparsed = domRoundTrip(block.getNonTerminatorInstructions(), session);
        assertEquals(block.getNonTerminatorInstructions().size(), reparsed.size());
        for (var i = 0; i < reparsed.size(); i++) {
            var expected = block.getNonTerminatorInstructions().get(i);
            var index = i;
            assertTrue(expected.checkEquals(reparsed.get(i)),
                    "instruction " + index + ": " + expected);
        }
    }

    // --- helpers ---

    private static @NotNull List<String> variableOperandIds(@NotNull ConstructBuiltinInsn insn) {
        var ids = new ArrayList<String>(insn.args().size());
        for (var operand : insn.args()) {
            ids.add(assertInstanceOf(LirInstruction.VariableOperand.class, operand).id());
        }
        return ids;
    }

    private static @NotNull String literalResultId(@NotNull LirInstruction insn) {
        var resultId = insn.resultId();
        assertNotNull(resultId);
        return resultId;
    }

    private static @NotNull List<LirInstruction> simpleRoundTrip(
            @NotNull List<LirInstruction> insns
    ) throws Exception {
        var out = new StringWriter();
        new SimpleLirBlockInsnSerializer().serialize(insns, out);
        return new SimpleLirBlockInsnParser().parse(new StringReader(out.toString()));
    }

    /// Serializes the emitted instructions inside a well-formed module — every referenced slot
    /// is re-declared on a fresh function — then parses the XML back through the DOM parser.
    private static @NotNull List<LirInstruction> domRoundTrip(
            @NotNull List<LirInstruction> insns,
            @NotNull FrontendBodyLoweringSession session
    ) throws Exception {
        var function = new LirFunctionDef("const_sink", "entry");
        for (var insn : insns) {
            for (var slotId : referencedVariableIds(insn)) {
                var variable = session.targetFunction().getVariableById(slotId);
                assertNotNull(variable, "undeclared slot referenced by materialized chain: " + slotId);
                function.createAndAddVariable(slotId, variable.type());
            }
        }
        function.addBasicBlock(new LirBasicBlock("entry", insns));

        var clazz = new LirClassDef(
                "ConstSink", "RefCounted", false, false,
                Map.of(), List.of(), List.of(), List.of(function)
        );
        var xml = new DomLirSerializer().serializeToString(new LirModule("m", List.of(clazz)));
        var parsed = new DomLirParser(new ClassRegistry(ExtensionApiLoader.loadDefault()))
                .parse(new StringReader(xml));
        return parsed.getClassDefs().getFirst().getFunctions().getFirst()
                .getBasicBlock("entry").getNonTerminatorInstructions();
    }

    private static @NotNull List<String> referencedVariableIds(@NotNull LirInstruction insn) {
        var ids = new ArrayList<String>();
        if (insn.resultId() != null) {
            ids.add(insn.resultId());
        }
        for (var operand : insn.operands()) {
            if (operand instanceof LirInstruction.VariableOperand variableOperand) {
                ids.add(variableOperand.id());
            }
        }
        return ids;
    }

    private static @NotNull FrontendBodyLoweringSession prepareSession() throws Exception {
        var diagnostics = new DiagnosticManager();
        var parseDiagnostics = new DiagnosticManager();
        var parserService = new GdScriptParserService();
        var unit = parserService.parseUnit(
                Path.of("tmp", "const_materialization_helper.gd"),
                """
                        class_name ConstMaterializationHelper
                        extends RefCounted

                        func ping(seed: int) -> int:
                            return seed
                        """,
                parseDiagnostics
        );
        assertTrue(parseDiagnostics.isEmpty(),
                () -> "Unexpected parse diagnostics: " + parseDiagnostics.snapshot());
        var module = new FrontendModule(
                "test_module",
                List.of(unit),
                Map.of("ConstMaterializationHelper", "RuntimeConstMaterializationHelper")
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
        assertFalse(diagnostics.hasErrors(),
                () -> "Unexpected lowering diagnostics: " + diagnostics.snapshot());
        var functionContext = context.requireFunctionLoweringContexts().stream()
                .filter(candidate -> candidate.kind() == FunctionLoweringContext.Kind.EXECUTABLE_BODY)
                .filter(candidate -> candidate.owningClass().getName().equals("RuntimeConstMaterializationHelper"))
                .filter(candidate -> candidate.targetFunction().getName().equals("ping"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing executable body context"));
        return new FrontendBodyLoweringSession(functionContext, context.classRegistry());
    }

    private record VectorCase(@NotNull GdType type, double @NotNull [] leaves) {
    }
}
