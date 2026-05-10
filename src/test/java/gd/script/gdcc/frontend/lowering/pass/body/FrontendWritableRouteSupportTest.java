package gd.script.gdcc.frontend.lowering.pass.body;

import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.lowering.FrontendLoweringContext;
import gd.script.gdcc.frontend.lowering.FunctionLoweringContext;
import gd.script.gdcc.frontend.lowering.cfg.FrontendCfgGraph;
import gd.script.gdcc.frontend.lowering.cfg.item.CallItem;
import gd.script.gdcc.frontend.lowering.cfg.item.FrontendWritableRoutePayload;
import gd.script.gdcc.frontend.lowering.pass.FrontendLoweringAnalysisPass;
import gd.script.gdcc.frontend.lowering.pass.FrontendLoweringBuildCfgPass;
import gd.script.gdcc.frontend.lowering.pass.FrontendLoweringClassSkeletonPass;
import gd.script.gdcc.frontend.lowering.pass.FrontendLoweringFunctionPreparationPass;
import gd.script.gdcc.frontend.parse.FrontendModule;
import gd.script.gdcc.frontend.parse.GdScriptParserService;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.lir.LirBasicBlock;
import gd.script.gdcc.lir.LirInstruction;
import gd.script.gdcc.lir.insn.CallGlobalInsn;
import gd.script.gdcc.lir.insn.CallIntrinsicInsn;
import gd.script.gdcc.lir.insn.GoIfInsn;
import gd.script.gdcc.lir.insn.GotoInsn;
import gd.script.gdcc.lir.insn.LiteralStringNameInsn;
import gd.script.gdcc.lir.insn.LoadPropertyInsn;
import gd.script.gdcc.lir.insn.StorePropertyInsn;
import gd.script.gdcc.lir.insn.VariantGetKeyedInsn;
import gd.script.gdcc.lir.insn.VariantGetNamedInsn;
import gd.script.gdcc.lir.insn.VariantSetIndexedInsn;
import gd.script.gdcc.lir.insn.VariantSetKeyedInsn;
import gd.script.gdcc.lir.insn.VariantSetNamedInsn;
import gd.script.gdcc.type.GdBoolType;
import gd.script.gdcc.type.GdArrayType;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdDictionaryType;
import gd.script.gdcc.type.GdFloatType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdPackedNumericArrayType;
import gd.script.gdcc.type.GdVariantType;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.Point;
import dev.superice.gdparser.frontend.ast.Range;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
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
    void materializeCallReceiverLeafRejectsPayloadBackedCallWithoutDedicatedReceiverSlot() throws Exception {
        var fixture = prepareMutatingCallFixture();
        var block = new LirBasicBlock("entry");
        var invalidCallItem = new CallItem(
                fixture.callItem().anchor(),
                fixture.callItem().callableName(),
                null,
                fixture.callItem().argumentValueIds(),
                fixture.callItem().resultValueId(),
                fixture.callItem().writableRoutePayloadOrNull()
        );

        var exception = assertThrows(
                IllegalStateException.class,
                () -> fixture.session().materializeCallReceiverLeaf(block, invalidCallItem)
        );

        assertAll(
                () -> assertTrue(exception.getMessage().contains("receiverValueIdOrNull")),
                () -> assertTrue(exception.getMessage().contains("push_back")),
                () -> assertTrue(block.getNonTerminatorInstructions().isEmpty())
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
                        GdVariantType.VARIANT,
                        "payloads",
                        "key_slot",
                        GdIntType.INT,
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
    void reverseCommitReusesSubscriptKeyMaterializedDuringLeafRead() throws Exception {
        var session = prepareSession();
        var block = new LirBasicBlock("entry");
        session.ensureVariable("box_slot", new GdDictionaryType(GdFloatType.FLOAT, GdVariantType.VARIANT));
        session.ensureVariable("key_slot", GdIntType.INT);
        session.ensureVariable("rhs_slot", GdVariantType.VARIANT);
        session.ensureVariable("leaf_result", GdVariantType.VARIANT);
        var chain = new FrontendWritableRouteSupport.FrontendWritableAccessChain(
                identifier("box"),
                new FrontendWritableRouteSupport.FrontendWritableRoot(
                        "dictionary receiver",
                        "box_slot",
                        new GdDictionaryType(GdFloatType.FLOAT, GdVariantType.VARIANT)
                ),
                new FrontendWritableRouteSupport.SubscriptLeaf(
                        "box_slot",
                        new GdDictionaryType(GdFloatType.FLOAT, GdVariantType.VARIANT),
                        null,
                        "key_slot",
                        GdIntType.INT,
                        GdVariantType.VARIANT
                ),
                List.of(new FrontendWritableRouteSupport.SubscriptCommitStep(
                        "box_slot",
                        new GdDictionaryType(GdFloatType.FLOAT, GdVariantType.VARIANT),
                        null,
                        "key_slot",
                        GdIntType.INT
                ))
        );

        var leafSlotId = FrontendWritableRouteSupport.materializeLeafReadInto(session, block, chain, "leaf_result");
        FrontendWritableRouteSupport.reverseCommit(
                session,
                block,
                chain,
                leafSlotId,
                FrontendWritableRouteSupport.ALWAYS_APPLY
        );

        var instructions = block.getNonTerminatorInstructions();
        var castInsn = assertInstanceOf(CallIntrinsicInsn.class, instructions.getFirst());
        var getKeyedInsn = assertInstanceOf(VariantGetKeyedInsn.class, instructions.get(1));
        var setKeyedInsn = assertInstanceOf(VariantSetKeyedInsn.class, instructions.get(2));

        assertAll(
                () -> assertEquals(3, instructions.size()),
                () -> assertEquals("c_int_to_float", castInsn.intrinsicName()),
                () -> assertEquals("key_slot", onlyVariableOperandId(castInsn.args())),
                () -> assertEquals("leaf_result", leafSlotId),
                () -> assertEquals(castInsn.resultId(), getKeyedInsn.keyId()),
                () -> assertEquals(castInsn.resultId(), setKeyedInsn.keyId()),
                () -> assertEquals("box_slot", getKeyedInsn.keyedVariantId()),
                () -> assertEquals("box_slot", setKeyedInsn.keyedVariantId()),
                () -> assertEquals("leaf_result", setKeyedInsn.valueId())
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
                        GdVariantType.VARIANT,
                        null,
                        "key_slot",
                        GdIntType.INT,
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
                (_, _) -> false
        );
        assertTrue(gatedBlock.getNonTerminatorInstructions().isEmpty());
    }

    @Test
    void reverseCommitThreadsCarrierAcrossMultipleSteps() throws Exception {
        var session = prepareSession();
        var block = new LirBasicBlock("entry");
        session.ensureVariable("element_slot", GdVariantType.VARIANT);
        session.ensureVariable("items_slot", GdVariantType.VARIANT);
        session.ensureVariable("key_slot", GdIntType.INT);
        session.ensureVariable("rhs_slot", GdIntType.INT);
        var chain = new FrontendWritableRouteSupport.FrontendWritableAccessChain(
                identifier("x"),
                new FrontendWritableRouteSupport.FrontendWritableRoot("nested owner route", "self", GdObjectType.OBJECT),
                new FrontendWritableRouteSupport.InstancePropertyLeaf("element_slot", "x", GdIntType.INT),
                List.of(
                        new FrontendWritableRouteSupport.InstancePropertyCommitStep("self", "items"),
                        new FrontendWritableRouteSupport.SubscriptCommitStep(
                                "items_slot",
                                GdVariantType.VARIANT,
                                null,
                                "key_slot",
                                GdIntType.INT
                        )
                )
        );

        var carrierSlotId = FrontendWritableRouteSupport.writeLeaf(session, block, chain, "rhs_slot");
        FrontendWritableRouteSupport.reverseCommit(
                session,
                block,
                chain,
                carrierSlotId,
                FrontendWritableRouteSupport.ALWAYS_APPLY
        );
        var instructions = block.getNonTerminatorInstructions();
        var leafStore = assertInstanceOf(StorePropertyInsn.class, instructions.get(0));
        var indexedStore = assertInstanceOf(VariantSetIndexedInsn.class, instructions.get(1));
        var outerStore = assertInstanceOf(StorePropertyInsn.class, instructions.get(2));

        assertAll(
                () -> assertEquals("element_slot", carrierSlotId),
                () -> assertEquals(3, instructions.size()),
                () -> assertEquals("x", leafStore.propertyName()),
                () -> assertEquals("element_slot", leafStore.objectId()),
                () -> assertEquals("rhs_slot", leafStore.valueId()),
                () -> assertEquals("items_slot", indexedStore.variantId()),
                () -> assertEquals("key_slot", indexedStore.indexId()),
                () -> assertEquals("element_slot", indexedStore.valueId()),
                () -> assertEquals("items", outerStore.propertyName()),
                () -> assertEquals("self", outerStore.objectId()),
                () -> assertEquals("items_slot", outerStore.valueId())
        );
    }

    @Test
    void reverseCommitUsesPromotedCarrierWhenGateRejectsInnerButAllowsOuter() throws Exception {
        var session = prepareSession();
        var block = new LirBasicBlock("entry");
        session.ensureVariable("element_slot", GdVariantType.VARIANT);
        session.ensureVariable("items_slot", GdVariantType.VARIANT);
        session.ensureVariable("key_slot", GdIntType.INT);
        session.ensureVariable("rhs_slot", GdIntType.INT);
        var chain = new FrontendWritableRouteSupport.FrontendWritableAccessChain(
                identifier("x"),
                new FrontendWritableRouteSupport.FrontendWritableRoot("nested owner route", "self", GdObjectType.OBJECT),
                new FrontendWritableRouteSupport.InstancePropertyLeaf("element_slot", "x", GdIntType.INT),
                List.of(
                        new FrontendWritableRouteSupport.InstancePropertyCommitStep("self", "items"),
                        new FrontendWritableRouteSupport.SubscriptCommitStep(
                                "items_slot",
                                GdVariantType.VARIANT,
                                null,
                                "key_slot",
                                GdIntType.INT
                        )
                )
        );

        var carrierSlotId = FrontendWritableRouteSupport.writeLeaf(session, block, chain, "rhs_slot");
        var observedCarriers = new ArrayList<String>();
        var gateDecisions = new ArrayList<Boolean>();
        FrontendWritableRouteSupport.reverseCommit(
                session,
                block,
                chain,
                carrierSlotId,
                (_, currentCarrierSlotId) -> {
                    observedCarriers.add(currentCarrierSlotId);
                    var allow = observedCarriers.size() == 2;
                    gateDecisions.add(allow);
                    return allow;
                }
        );
        var instructions = block.getNonTerminatorInstructions();
        var leafStore = assertInstanceOf(StorePropertyInsn.class, instructions.get(0));
        var outerStore = assertInstanceOf(StorePropertyInsn.class, instructions.get(1));

        assertAll(
                () -> assertEquals(List.of("element_slot", "items_slot"), observedCarriers),
                () -> assertEquals(List.of(false, true), gateDecisions),
                () -> assertEquals(2, instructions.size()),
                () -> assertFalse(instructions.stream().anyMatch(VariantSetIndexedInsn.class::isInstance)),
                () -> assertEquals("x", leafStore.propertyName()),
                () -> assertEquals("element_slot", leafStore.objectId()),
                () -> assertEquals("rhs_slot", leafStore.valueId()),
                () -> assertEquals("items", outerStore.propertyName()),
                () -> assertEquals("self", outerStore.objectId()),
                () -> assertEquals("items_slot", outerStore.valueId())
        );
    }

    @Test
    void reverseCommitWithRuntimeGateUsesStaticFastPathForConcreteCarrier() throws Exception {
        var session = prepareSession();
        var block = new LirBasicBlock("entry");
        session.targetFunction().addBasicBlock(block);
        session.ensureVariable("packed_slot", GdPackedNumericArrayType.PACKED_INT32_ARRAY);
        var chain = new FrontendWritableRouteSupport.FrontendWritableAccessChain(
                identifier("values"),
                new FrontendWritableRouteSupport.FrontendWritableRoot(
                        "packed array route",
                        "packed_slot",
                        GdPackedNumericArrayType.PACKED_INT32_ARRAY
                ),
                new FrontendWritableRouteSupport.DirectSlotLeaf(
                        "packed_slot",
                        GdPackedNumericArrayType.PACKED_INT32_ARRAY
                ),
                List.of(new FrontendWritableRouteSupport.InstancePropertyCommitStep("self", "values"))
        );

        var emitterCalls = new ArrayList<String>();
        var continuationBlock = FrontendWritableRouteSupport.reverseCommitWithRuntimeGate(
                session,
                block,
                chain,
                "packed_slot",
                (_, _, _, currentCarrierSlotId) -> {
                    emitterCalls.add(currentCarrierSlotId);
                    return "unused_gate";
                }
        );
        var instructions = block.getNonTerminatorInstructions();
        var storeInsn = assertInstanceOf(StorePropertyInsn.class, instructions.getFirst());

        assertAll(
                () -> assertSame(block, continuationBlock),
                () -> assertEquals(List.of(), emitterCalls),
                () -> assertFalse(block.hasTerminator()),
                () -> assertEquals(1, instructions.size()),
                () -> assertEquals("self", storeInsn.objectId()),
                () -> assertEquals("values", storeInsn.propertyName()),
                () -> assertEquals("packed_slot", storeInsn.valueId())
        );
    }

    @Test
    void reverseCommitWithRuntimeGateSkipsSharedCarrierWithoutCallingEmitter() throws Exception {
        var session = prepareSession();
        var block = new LirBasicBlock("entry");
        session.targetFunction().addBasicBlock(block);
        session.ensureVariable("shared_slot", new GdArrayType(GdVariantType.VARIANT));
        var chain = new FrontendWritableRouteSupport.FrontendWritableAccessChain(
                identifier("values"),
                new FrontendWritableRouteSupport.FrontendWritableRoot(
                        "shared array route",
                        "shared_slot",
                        new GdArrayType(GdVariantType.VARIANT)
                ),
                new FrontendWritableRouteSupport.DirectSlotLeaf(
                        "shared_slot",
                        new GdArrayType(GdVariantType.VARIANT)
                ),
                List.of(new FrontendWritableRouteSupport.InstancePropertyCommitStep("self", "values"))
        );

        var emitterCalls = new ArrayList<String>();
        var continuationBlock = FrontendWritableRouteSupport.reverseCommitWithRuntimeGate(
                session,
                block,
                chain,
                "shared_slot",
                (_, _, _, currentCarrierSlotId) -> {
                    emitterCalls.add(currentCarrierSlotId);
                    return "unused_gate";
                }
        );

        assertAll(
                () -> assertSame(block, continuationBlock),
                () -> assertEquals(List.of(), emitterCalls),
                () -> assertTrue(block.getNonTerminatorInstructions().isEmpty()),
                () -> assertFalse(block.hasTerminator())
        );
    }

    @Test
    void reverseCommitWithRuntimeGateBranchesForVariantCarrierAndContinuesWithPromotedCarrier() throws Exception {
        var session = prepareSession();
        var block = new LirBasicBlock("entry");
        session.targetFunction().addBasicBlock(block);
        session.ensureVariable("element_slot", GdVariantType.VARIANT);
        session.ensureVariable("items_slot", GdPackedNumericArrayType.PACKED_INT32_ARRAY);
        session.ensureVariable("key_slot", GdIntType.INT);
        var chain = new FrontendWritableRouteSupport.FrontendWritableAccessChain(
                identifier("x"),
                new FrontendWritableRouteSupport.FrontendWritableRoot("nested owner route", "self", GdObjectType.OBJECT),
                new FrontendWritableRouteSupport.DirectSlotLeaf("element_slot", GdVariantType.VARIANT),
                List.of(
                        new FrontendWritableRouteSupport.InstancePropertyCommitStep("self", "items"),
                        new FrontendWritableRouteSupport.SubscriptCommitStep(
                                "items_slot",
                                GdPackedNumericArrayType.PACKED_INT32_ARRAY,
                                null,
                                "key_slot",
                                GdIntType.INT
                        )
                )
        );
        var blocksBefore = session.targetFunction().getBasicBlockCount();
        var emittedRuntimeGateCarriers = new ArrayList<String>();
        var continuationBlock = FrontendWritableRouteSupport.reverseCommitWithRuntimeGate(
                session,
                block,
                chain,
                "element_slot",
                (innerSession, innerBlock, _, currentCarrierSlotId) -> {
                    emittedRuntimeGateCarriers.add(currentCarrierSlotId);
                    var gateSlotId = innerSession.allocateWritableRouteTemp("variant_requires_writeback", GdBoolType.BOOL);
                    innerBlock.appendNonTerminatorInstruction(new CallGlobalInsn(
                            gateSlotId,
                            "gdcc_variant_requires_writeback",
                            List.of(new LirInstruction.VariableOperand(currentCarrierSlotId))
                    ));
                    return gateSlotId;
                }
        );
        var entryInstructions = block.getNonTerminatorInstructions();
        var gateCallInsn = assertInstanceOf(CallGlobalInsn.class, entryInstructions.getFirst());
        var entryTerminator = assertInstanceOf(GoIfInsn.class, block.getTerminator());
        var applyBlock = session.requireBlock(entryTerminator.trueBbId());
        var skipBlock = session.requireBlock(entryTerminator.falseBbId());
        var continueBlock = session.requireBlock(assertInstanceOf(GotoInsn.class, applyBlock.getTerminator()).targetBbId());
        var applyInstructions = applyBlock.getNonTerminatorInstructions();
        var indexedStoreInsn = assertInstanceOf(VariantSetIndexedInsn.class, applyInstructions.getFirst());
        var skipTerminator = assertInstanceOf(GotoInsn.class, skipBlock.getTerminator());
        var outerInstructions = continueBlock.getNonTerminatorInstructions();
        var outerStoreInsn = assertInstanceOf(StorePropertyInsn.class, outerInstructions.getFirst());

        assertAll(
                () -> assertSame(continueBlock, continuationBlock),
                () -> assertEquals(List.of("element_slot"), emittedRuntimeGateCarriers),
                () -> assertEquals(blocksBefore + 3, session.targetFunction().getBasicBlockCount()),
                () -> assertEquals("gdcc_variant_requires_writeback", gateCallInsn.functionName()),
                () -> assertEquals("element_slot", ((LirInstruction.VariableOperand) gateCallInsn.args().getFirst()).id()),
                () -> assertEquals(gateCallInsn.resultId(), entryTerminator.conditionVarId()),
                () -> assertEquals(1, applyInstructions.size()),
                () -> assertEquals("items_slot", indexedStoreInsn.variantId()),
                () -> assertEquals("key_slot", indexedStoreInsn.indexId()),
                () -> assertEquals("element_slot", indexedStoreInsn.valueId()),
                () -> assertEquals(assertInstanceOf(GotoInsn.class, applyBlock.getTerminator()).targetBbId(), skipTerminator.targetBbId()),
                () -> assertEquals(1, outerInstructions.size()),
                () -> assertEquals("self", outerStoreInsn.objectId()),
                () -> assertEquals("items", outerStoreInsn.propertyName()),
                () -> assertEquals("items_slot", outerStoreInsn.valueId())
        );
    }

    @Test
    void reverseCommitUpdatesCarrierBeforeGatingOuterStep() throws Exception {
        var session = prepareSession();
        var block = new LirBasicBlock("entry");
        session.ensureVariable("element_slot", GdVariantType.VARIANT);
        session.ensureVariable("items_slot", GdVariantType.VARIANT);
        session.ensureVariable("key_slot", GdIntType.INT);
        session.ensureVariable("rhs_slot", GdIntType.INT);
        var chain = new FrontendWritableRouteSupport.FrontendWritableAccessChain(
                identifier("x"),
                new FrontendWritableRouteSupport.FrontendWritableRoot("nested owner route", "self", GdObjectType.OBJECT),
                new FrontendWritableRouteSupport.InstancePropertyLeaf("element_slot", "x", GdIntType.INT),
                List.of(
                        new FrontendWritableRouteSupport.InstancePropertyCommitStep("self", "items"),
                        new FrontendWritableRouteSupport.SubscriptCommitStep(
                                "items_slot",
                                GdVariantType.VARIANT,
                                null,
                                "key_slot",
                                GdIntType.INT
                        )
                )
        );

        var carrierSlotId = FrontendWritableRouteSupport.writeLeaf(session, block, chain, "rhs_slot");
        var observedCarriers = new ArrayList<String>();
        FrontendWritableRouteSupport.reverseCommit(
                session,
                block,
                chain,
                carrierSlotId,
                (_, currentCarrierSlotId) -> {
                    observedCarriers.add(currentCarrierSlotId);
                    return observedCarriers.size() == 1;
                }
        );
        var instructions = block.getNonTerminatorInstructions();
        var propertyStores = instructions.stream()
                .filter(StorePropertyInsn.class::isInstance)
                .map(StorePropertyInsn.class::cast)
                .toList();
        var indexedStores = instructions.stream()
                .filter(VariantSetIndexedInsn.class::isInstance)
                .map(VariantSetIndexedInsn.class::cast)
                .toList();

        assertAll(
                () -> assertEquals(List.of("element_slot", "items_slot"), observedCarriers),
                () -> assertEquals(2, instructions.size()),
                () -> assertEquals(1, propertyStores.size()),
                () -> assertEquals("x", propertyStores.getFirst().propertyName()),
                () -> assertEquals("element_slot", propertyStores.getFirst().objectId()),
                () -> assertEquals("rhs_slot", propertyStores.getFirst().valueId()),
                () -> assertEquals(1, indexedStores.size()),
                () -> assertEquals("items_slot", indexedStores.getFirst().variantId()),
                () -> assertEquals("key_slot", indexedStores.getFirst().indexId()),
                () -> assertEquals("element_slot", indexedStores.getFirst().valueId())
        );
    }

    @Test
    void reverseCommitFailsFastWhenStaticPropertyCommitIsNotTerminal() throws Exception {
        var session = prepareSession();
        var block = new LirBasicBlock("entry");
        session.ensureVariable("value_slot", GdVariantType.VARIANT);
        var chain = new FrontendWritableRouteSupport.FrontendWritableAccessChain(
                identifier("value"),
                new FrontendWritableRouteSupport.FrontendWritableRoot("malformed route", "value_slot", GdVariantType.VARIANT),
                new FrontendWritableRouteSupport.DirectSlotLeaf("value_slot", GdVariantType.VARIANT),
                List.of(
                        new FrontendWritableRouteSupport.InstancePropertyCommitStep("self", "payloads"),
                        new FrontendWritableRouteSupport.StaticPropertyCommitStep("RuntimeWritableRouteHelper", "payload")
                )
        );

        var exception = assertThrows(
                IllegalStateException.class,
                () -> FrontendWritableRouteSupport.reverseCommit(
                        session,
                        block,
                        chain,
                        "value_slot",
                        FrontendWritableRouteSupport.ALWAYS_APPLY
                )
        );

        assertAll(
                () -> assertTrue(exception.getMessage().contains("StaticPropertyCommitStep")),
                () -> assertTrue(block.getNonTerminatorInstructions().isEmpty())
        );
    }

    @Test
    void constructorsFailFastForMalformedRoutes() {
        var blankKey = assertThrows(
                IllegalArgumentException.class,
                () -> new FrontendWritableRouteSupport.SubscriptLeaf(
                        "receiver_slot",
                        GdVariantType.VARIANT,
                        null,
                        " ",
                        GdIntType.INT,
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

    private static @NotNull MutatingCallFixture prepareMutatingCallFixture() throws Exception {
        var diagnostics = new DiagnosticManager();
        var module = parseModule(
                List.of(new SourceFixture(
                        "writable_route_call_helper.gd",
                        """
                                class_name WritableRouteCallHelper
                                extends RefCounted
                                
                                var payloads: PackedInt32Array
                                
                                func mutate(seed: int) -> void:
                                    payloads.push_back(seed)
                                """
                )),
                Map.of("WritableRouteCallHelper", "RuntimeWritableRouteCallHelper")
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
        var functionContext = context.requireFunctionLoweringContexts().stream()
                .filter(candidate -> candidate.kind() == FunctionLoweringContext.Kind.EXECUTABLE_BODY)
                .filter(candidate -> candidate.owningClass().getName().equals("RuntimeWritableRouteCallHelper"))
                .filter(candidate -> candidate.targetFunction().getName().equals("mutate"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Missing executable body context for RuntimeWritableRouteCallHelper.mutate"
                ));
        var session = new FrontendBodyLoweringSession(functionContext, context.classRegistry());
        var graph = functionContext.requireFrontendCfgGraph();
        var entryNode = assertInstanceOf(FrontendCfgGraph.SequenceNode.class, graph.requireNode("seq_0"));
        var callItem = entryNode.items().stream()
                .filter(CallItem.class::isInstance)
                .map(CallItem.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing published mutating call item"));
        assertInstanceOf(FrontendWritableRoutePayload.class, callItem.writableRoutePayloadOrNull());
        return new MutatingCallFixture(session, callItem);
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

    private static @NotNull String onlyVariableOperandId(@NotNull List<LirInstruction.Operand> operands) {
        assertEquals(1, operands.size(), "Expected exactly one variable operand");
        return assertInstanceOf(LirInstruction.VariableOperand.class, operands.getFirst()).id();
    }

    private record SourceFixture(
            @NotNull String fileName,
            @NotNull String source
    ) {
    }

    private record MutatingCallFixture(
            @NotNull FrontendBodyLoweringSession session,
            @NotNull CallItem callItem
    ) {
    }
}
