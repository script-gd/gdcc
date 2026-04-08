package dev.superice.gdcc.frontend.lowering.pass.body;

import dev.superice.gdcc.frontend.diagnostic.DiagnosticManager;
import dev.superice.gdcc.frontend.lowering.FrontendLoweringContext;
import dev.superice.gdcc.frontend.lowering.FunctionLoweringContext;
import dev.superice.gdcc.frontend.lowering.pass.FrontendLoweringAnalysisPass;
import dev.superice.gdcc.frontend.lowering.pass.FrontendLoweringBuildCfgPass;
import dev.superice.gdcc.frontend.lowering.pass.FrontendLoweringClassSkeletonPass;
import dev.superice.gdcc.frontend.lowering.pass.FrontendLoweringFunctionPreparationPass;
import dev.superice.gdcc.frontend.parse.FrontendModule;
import dev.superice.gdcc.frontend.parse.GdScriptParserService;
import dev.superice.gdcc.gdextension.ExtensionApiLoader;
import dev.superice.gdcc.lir.LirBasicBlock;
import dev.superice.gdcc.lir.insn.LiteralStringNameInsn;
import dev.superice.gdcc.lir.insn.LoadPropertyInsn;
import dev.superice.gdcc.lir.insn.StorePropertyInsn;
import dev.superice.gdcc.lir.insn.VariantGetNamedInsn;
import dev.superice.gdcc.lir.insn.VariantSetIndexedInsn;
import dev.superice.gdcc.lir.insn.VariantSetNamedInsn;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.type.GdIntType;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdVariantType;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.Point;
import dev.superice.gdparser.frontend.ast.Range;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontendWritableRouteSupportTest {
    private static final Range SYNTHETIC_RANGE = new Range(0, 1, new Point(0, 0), new Point(0, 1));

    @Test
    void materializeLeafReadKeepsDirectSlotRoutesInstructionFree() throws Exception {
        var session = prepareSession();
        var block = new LirBasicBlock("entry");
        session.ensureVariable("receiver_slot", GdObjectType.OBJECT);
        var chain = new FrontendWritableRouteSupport.FrontendWritableAccessChain(
                identifier("receiver"),
                new FrontendWritableRouteSupport.FrontendWritableRoot(
                        "direct receiver root",
                        "receiver_slot",
                        GdObjectType.OBJECT
                ),
                new FrontendWritableRouteSupport.DirectSlotLeaf("receiver_slot", GdObjectType.OBJECT),
                List.of()
        );

        var slotId = FrontendWritableRouteSupport.materializeLeafRead(session, block, chain, "call_receiver");

        assertAll(
                () -> assertEquals("receiver_slot", slotId),
                () -> assertTrue(block.getNonTerminatorInstructions().isEmpty())
        );
    }

    @Test
    void materializeLeafReadLoadsPropertyIntoRequestedResultSlot() throws Exception {
        var session = prepareSession();
        var block = new LirBasicBlock("entry");
        session.ensureVariable("leaf_result", GdIntType.INT);
        var chain = new FrontendWritableRouteSupport.FrontendWritableAccessChain(
                identifier("payload"),
                new FrontendWritableRouteSupport.FrontendWritableRoot("self root", "self", GdObjectType.OBJECT),
                new FrontendWritableRouteSupport.InstancePropertyLeaf("self", "payload", GdIntType.INT),
                List.of()
        );

        var slotId = FrontendWritableRouteSupport.materializeLeafReadInto(session, block, chain, "leaf_result");
        var instructions = block.getNonTerminatorInstructions();
        var loadInsn = assertInstanceOf(LoadPropertyInsn.class, instructions.getFirst());

        assertAll(
                () -> assertEquals("leaf_result", slotId),
                () -> assertEquals(1, instructions.size()),
                () -> assertEquals("leaf_result", loadInsn.resultId()),
                () -> assertEquals("payload", loadInsn.propertyName()),
                () -> assertEquals("self", loadInsn.objectId())
        );
    }

    @Test
    void writeLeafCommitsNamedSubscriptThroughSharedNamedBaseRoute() throws Exception {
        var session = prepareSession();
        var block = new LirBasicBlock("entry");
        session.ensureVariable("receiver_slot", GdVariantType.VARIANT);
        session.ensureVariable("key_slot", GdIntType.INT);
        session.ensureVariable("rhs_slot", GdIntType.INT);
        var chain = new FrontendWritableRouteSupport.FrontendWritableAccessChain(
                identifier("payloads"),
                new FrontendWritableRouteSupport.FrontendWritableRoot(
                        "attribute-subscript receiver",
                        "receiver_slot",
                        GdVariantType.VARIANT
                ),
                new FrontendWritableRouteSupport.SubscriptLeaf(
                        "receiver_slot",
                        "payloads",
                        "key_slot",
                        FrontendSubscriptInsnSupport.SubscriptAccessKind.INDEXED,
                        GdVariantType.VARIANT
                ),
                List.of()
        );

        var carrierSlotId = FrontendWritableRouteSupport.writeLeaf(session, block, chain, "rhs_slot");
        var instructions = block.getNonTerminatorInstructions();
        var nameInsn = assertInstanceOf(LiteralStringNameInsn.class, instructions.get(0));
        var getNamedInsn = assertInstanceOf(VariantGetNamedInsn.class, instructions.get(1));
        var setIndexedInsn = assertInstanceOf(VariantSetIndexedInsn.class, instructions.get(2));
        var setNamedInsn = assertInstanceOf(VariantSetNamedInsn.class, instructions.get(3));

        assertAll(
                () -> assertEquals("receiver_slot", carrierSlotId),
                () -> assertEquals(4, instructions.size()),
                () -> assertEquals("payloads", nameInsn.value()),
                () -> assertEquals("receiver_slot", getNamedInsn.namedVariantId()),
                () -> assertEquals("key_slot", setIndexedInsn.indexId()),
                () -> assertEquals("rhs_slot", setIndexedInsn.valueId()),
                () -> assertEquals("receiver_slot", setNamedInsn.namedVariantId()),
                () -> assertEquals(nameInsn.resultId(), setNamedInsn.nameId()),
                () -> assertEquals(getNamedInsn.resultId(), setNamedInsn.valueId())
        );
    }

    @Test
    void reverseCommitWritesBackPropertyBaseAndHonorsGateHook() throws Exception {
        var session = prepareSession();
        var block = new LirBasicBlock("entry");
        session.ensureVariable("payload_slot", GdVariantType.VARIANT);
        session.ensureVariable("key_slot", GdIntType.INT);
        session.ensureVariable("rhs_slot", GdIntType.INT);
        var chain = new FrontendWritableRouteSupport.FrontendWritableAccessChain(
                identifier("payloads"),
                new FrontendWritableRouteSupport.FrontendWritableRoot(
                        "property-backed base",
                        "payload_slot",
                        GdVariantType.VARIANT
                ),
                new FrontendWritableRouteSupport.SubscriptLeaf(
                        "payload_slot",
                        null,
                        "key_slot",
                        FrontendSubscriptInsnSupport.SubscriptAccessKind.INDEXED,
                        GdVariantType.VARIANT
                ),
                List.of(new FrontendWritableRouteSupport.InstancePropertyCommitStep("self", "payloads"))
        );

        var carrierSlotId = FrontendWritableRouteSupport.writeLeaf(session, block, chain, "rhs_slot");
        FrontendWritableRouteSupport.reverseCommit(
                session,
                block,
                chain,
                carrierSlotId,
                FrontendWritableRouteSupport.ALWAYS_APPLY
        );
        var appliedInstructions = block.getNonTerminatorInstructions();
        var storeInsn = assertInstanceOf(StorePropertyInsn.class, appliedInstructions.getLast());

        assertAll(
                () -> assertEquals("payload_slot", carrierSlotId),
                () -> assertEquals(2, appliedInstructions.size()),
                () -> assertEquals("payloads", storeInsn.propertyName()),
                () -> assertEquals("self", storeInsn.objectId()),
                () -> assertEquals("payload_slot", storeInsn.valueId())
        );

        var gatedBlock = new LirBasicBlock("gated");
        FrontendWritableRouteSupport.reverseCommit(
                session,
                gatedBlock,
                chain,
                carrierSlotId,
                (unusedStep, unusedWrittenBackValueSlotId) -> false
        );
        assertTrue(gatedBlock.getNonTerminatorInstructions().isEmpty());
    }

    @Test
    void constructorsFailFastForMalformedRoutes() {
        var blankKey = assertThrows(
                IllegalArgumentException.class,
                () -> new FrontendWritableRouteSupport.SubscriptLeaf(
                        "receiver_slot",
                        null,
                        " ",
                        FrontendSubscriptInsnSupport.SubscriptAccessKind.INDEXED,
                        GdVariantType.VARIANT
                )
        );
        var blankProperty = assertThrows(
                IllegalArgumentException.class,
                () -> new FrontendWritableRouteSupport.InstancePropertyCommitStep("self", " ")
        );

        assertAll(
                () -> assertTrue(blankKey.getMessage().contains("keySlotId")),
                () -> assertTrue(blankProperty.getMessage().contains("propertyName"))
        );
    }

    private static @NotNull FrontendBodyLoweringSession prepareSession() throws Exception {
        var diagnostics = new DiagnosticManager();
        var module = parseModule(
                List.of(new SourceFixture(
                        "writable_route_helper.gd",
                        """
                                class_name WritableRouteHelper
                                extends RefCounted
                                
                                var payload: int
                                var payloads: Variant
                                
                                func ping(seed: int) -> int:
                                    return seed
                                """
                )),
                Map.of("WritableRouteHelper", "RuntimeWritableRouteHelper")
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
                .filter(context -> context.owningClass().getName().equals("RuntimeWritableRouteHelper"))
                .filter(context -> context.targetFunction().getName().equals("ping"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing executable body context for RuntimeWritableRouteHelper.ping"));
    }

    private static @NotNull IdentifierExpression identifier(String name) {
        return new IdentifierExpression(name, SYNTHETIC_RANGE);
    }

    private record SourceFixture(
            @NotNull String fileName,
            @NotNull String source
    ) {
    }
}
