package gd.script.gdcc.frontend.lowering.pass.body;

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
import gd.script.gdcc.lir.LirInstruction;
import gd.script.gdcc.lir.insn.CallIntrinsicInsn;
import gd.script.gdcc.lir.insn.LiteralNullInsn;
import gd.script.gdcc.lir.insn.PackVariantInsn;
import gd.script.gdcc.lir.insn.UnpackVariantInsn;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdFloatType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdNilType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdVariantType;
import gd.script.gdcc.type.GdVoidType;
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
        return new FrontendBodyLoweringSession(
                requireContext(context.requireFunctionLoweringContexts()),
                context.classRegistry()
        );
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

    private static class TestNode {
    }

    private static final class DerivedTestNode extends TestNode {
    }
}
