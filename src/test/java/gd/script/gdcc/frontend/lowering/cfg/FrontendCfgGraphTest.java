package gd.script.gdcc.frontend.lowering.cfg;

import gd.script.gdcc.frontend.lowering.FrontendSubscriptAccessSupport;
import gd.script.gdcc.frontend.lowering.cfg.item.AssignmentItem;
import gd.script.gdcc.frontend.lowering.cfg.item.BoolConstantItem;
import gd.script.gdcc.frontend.lowering.cfg.item.CallItem;
import gd.script.gdcc.frontend.lowering.cfg.item.CompoundAssignmentBinaryOpItem;
import gd.script.gdcc.frontend.lowering.cfg.item.ContainerLiteralItem;
import gd.script.gdcc.frontend.lowering.cfg.item.DirectSlotAliasValueItem;
import gd.script.gdcc.frontend.lowering.cfg.item.FrontendWritableRoutePayload;
import gd.script.gdcc.frontend.lowering.cfg.item.LocalDeclarationItem;
import gd.script.gdcc.frontend.lowering.cfg.item.MemberLoadItem;
import gd.script.gdcc.frontend.lowering.cfg.item.MergeValueItem;
import gd.script.gdcc.frontend.lowering.cfg.item.OpaqueExprValueItem;
import gd.script.gdcc.frontend.lowering.cfg.item.SequenceItem;
import gd.script.gdcc.frontend.lowering.cfg.item.SourceAnchorItem;
import gd.script.gdcc.frontend.lowering.cfg.item.SubscriptLoadItem;
import dev.superice.gdparser.frontend.ast.ArrayExpression;
import dev.superice.gdparser.frontend.ast.AssignmentExpression;
import dev.superice.gdparser.frontend.ast.AttributePropertyStep;
import dev.superice.gdparser.frontend.ast.CallExpression;
import dev.superice.gdparser.frontend.ast.DeclarationKind;
import dev.superice.gdparser.frontend.ast.DictionaryExpression;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.PassStatement;
import dev.superice.gdparser.frontend.ast.Point;
import dev.superice.gdparser.frontend.ast.Range;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontendCfgGraphTest {
    private static final Range SYNTHETIC_RANGE = new Range(0, 1, new Point(0, 0), new Point(0, 1));

    @Test
    void constructorCopiesNodeTopologyAndSupportsTypedLookup() {
        var sequenceItems = new ArrayList<SequenceItem>();
        sequenceItems.add(new SourceAnchorItem(new PassStatement(SYNTHETIC_RANGE)));
        sequenceItems.add(new OpaqueExprValueItem(identifier("flag"), "v0"));

        var nodes = new LinkedHashMap<String, FrontendCfgGraph.NodeDef>();
        nodes.put("entry", new FrontendCfgGraph.SequenceNode("entry", sequenceItems, "branch"));
        nodes.put("branch", new FrontendCfgGraph.BranchNode("branch", identifier("flag"), "v0", "then", "else"));
        nodes.put("then", new FrontendCfgGraph.StopNode("then", FrontendCfgGraph.StopKind.RETURN, "ret0"));
        nodes.put("else", new FrontendCfgGraph.StopNode("else", FrontendCfgGraph.StopKind.RETURN, null));

        var graph = new FrontendCfgGraph("entry", nodes);
        sequenceItems.clear();
        nodes.clear();

        var entryNode = assertInstanceOf(FrontendCfgGraph.SequenceNode.class, graph.requireNode("entry"));
        var branchNode = assertInstanceOf(FrontendCfgGraph.BranchNode.class, graph.requireNode("branch"));
        var thenNode = assertInstanceOf(FrontendCfgGraph.StopNode.class, graph.requireNode("then"));

        assertAll(
                () -> assertEquals(List.of("entry", "branch", "then", "else"), graph.nodeIds()),
                () -> assertEquals(4, graph.nodes().size()),
                () -> assertTrue(graph.hasNode("entry")),
                () -> assertEquals(2, entryNode.items().size()),
                () -> assertEquals("branch", entryNode.nextId()),
                () -> assertEquals("v0", branchNode.conditionValueId()),
                () -> assertEquals(List.of("then", "else"), List.of(branchNode.trueTargetId(), branchNode.falseTargetId())),
                () -> assertEquals(FrontendCfgGraph.StopKind.RETURN, thenNode.kind()),
                () -> assertEquals("ret0", thenNode.returnValueIdOrNull()),
                () -> assertNull(graph.nodeOrNull("missing")),
                () -> assertThrows(
                        UnsupportedOperationException.class,
                        () -> graph.nodes().put(
                                "extra",
                                new FrontendCfgGraph.StopNode("extra", FrontendCfgGraph.StopKind.RETURN, null)
                        )
                )
        );
    }

    @Test
    void constructorRejectsBrokenEntryAndEdgeContracts() {
        var missingEntryNodes = new LinkedHashMap<String, FrontendCfgGraph.NodeDef>();
        missingEntryNodes.put("stop", new FrontendCfgGraph.StopNode("stop", FrontendCfgGraph.StopKind.RETURN, null));

        var keyMismatchNodes = new LinkedHashMap<String, FrontendCfgGraph.NodeDef>();
        keyMismatchNodes.put("entry", new FrontendCfgGraph.StopNode("other", FrontendCfgGraph.StopKind.RETURN, null));

        var brokenTargetNodes = new LinkedHashMap<String, FrontendCfgGraph.NodeDef>();
        brokenTargetNodes.put("entry", new FrontendCfgGraph.SequenceNode("entry", List.of(), "branch"));
        brokenTargetNodes.put("branch", new FrontendCfgGraph.BranchNode("branch", identifier("flag"), "v0", "then", "missing"));
        brokenTargetNodes.put("then", new FrontendCfgGraph.StopNode("then", FrontendCfgGraph.StopKind.RETURN, null));

        var missingEntry = assertThrows(
                IllegalArgumentException.class,
                () -> new FrontendCfgGraph("missing", missingEntryNodes)
        );
        var keyMismatch = assertThrows(
                IllegalArgumentException.class,
                () -> new FrontendCfgGraph("entry", keyMismatchNodes)
        );
        var brokenTarget = assertThrows(
                IllegalArgumentException.class,
                () -> new FrontendCfgGraph("entry", brokenTargetNodes)
        );

        assertAll(
                () -> assertTrue(missingEntry.getMessage().contains("entry node")),
                () -> assertTrue(keyMismatch.getMessage().contains("node id mismatch")),
                () -> assertTrue(brokenTarget.getMessage().contains("missing falseTargetId"))
        );
    }

    @Test
    void directSlotAliasValueItemRejectsNonDirectSlotExpressionRoots() {
        var callExpression = new CallExpression(identifier("callee"), List.of(), SYNTHETIC_RANGE);
        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> new DirectSlotAliasValueItem(callExpression, "v0")
        );

        assertTrue(exception.getMessage().contains("IdentifierExpression or SelfExpression"));
    }

    @Test
    void constructorAllowsMergeOnlyMultiProducerResultSlots() {
        var mergedNodes = new LinkedHashMap<String, FrontendCfgGraph.NodeDef>();
        mergedNodes.put(
                "entry",
                new FrontendCfgGraph.SequenceNode(
                        "entry",
                        List.of(
                                new BoolConstantItem(identifier("left"), true, "left0"),
                                new MergeValueItem(identifier("left_merge"), "left0", "merged0"),
                                new BoolConstantItem(identifier("right"), false, "right0"),
                                new MergeValueItem(identifier("right_merge"), "right0", "merged0")
                        ),
                        "stop"
                )
        );
        mergedNodes.put("stop", new FrontendCfgGraph.StopNode("stop", FrontendCfgGraph.StopKind.RETURN, "merged0"));

        var graph = new FrontendCfgGraph("entry", mergedNodes);
        var entryNode = assertInstanceOf(FrontendCfgGraph.SequenceNode.class, graph.requireNode("entry"));

        assertAll(
                () -> assertEquals("merged0", assertInstanceOf(
                        MergeValueItem.class,
                        entryNode.items().get(1)
                ).resultValueId()),
                () -> assertEquals("merged0", assertInstanceOf(
                        MergeValueItem.class,
                        entryNode.items().get(3)
                ).resultValueId())
        );
    }

    @Test
    void constructorRejectsMixedMergeAndOrdinaryProducersForSameValueId() {
        var invalidNodes = new LinkedHashMap<String, FrontendCfgGraph.NodeDef>();
        invalidNodes.put(
                "entry",
                new FrontendCfgGraph.SequenceNode(
                        "entry",
                        List.of(
                                new BoolConstantItem(identifier("source"), true, "src0"),
                                new OpaqueExprValueItem(identifier("flag"), "v0"),
                                new MergeValueItem(identifier("merged"), "src0", "v0")
                        ),
                        "stop"
                )
        );
        invalidNodes.put("stop", new FrontendCfgGraph.StopNode("stop", FrontendCfgGraph.StopKind.RETURN, "v0"));

        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> new FrontendCfgGraph("entry", invalidNodes)
        );

        assertAll(
                () -> assertTrue(exception.getMessage().contains("multiple producers")),
                () -> assertTrue(exception.getMessage().contains("v0")),
                () -> assertTrue(exception.getMessage().contains("OpaqueExprValueItem")),
                () -> assertTrue(exception.getMessage().contains("MergeValueItem"))
        );
    }

    @Test
    void valueOpItemsExposeStableAnchorOperandAndResultContracts() {
        var passStatement = new PassStatement(SYNTHETIC_RANGE);
        var expression = identifier("seed");
        var sourceAnchor = new SourceAnchorItem(passStatement);
        var propertyStep = new AttributePropertyStep("payload", SYNTHETIC_RANGE);
        var assignmentExpression = new AssignmentExpression("=", identifier("target"), expression, SYNTHETIC_RANGE);
        var callPayload = directSlotPayload(expression);
        var assignmentPayload = new FrontendWritableRoutePayload(
                assignmentExpression,
                new FrontendWritableRoutePayload.RootDescriptor(
                        FrontendWritableRoutePayload.RootKind.DIRECT_SLOT,
                        identifier("target"),
                        null
                ),
                new FrontendWritableRoutePayload.LeafDescriptor(
                        FrontendWritableRoutePayload.LeafKind.SUBSCRIPT,
                        assignmentExpression.left(),
                        null,
                        List.of("index0"),
                        null,
                        FrontendSubscriptAccessSupport.AccessKind.INDEXED
                ),
                List.of()
        );
        var opaqueValue = new OpaqueExprValueItem(expression, List.of("lhs0", "rhs0"), "v0");
        var memberItem = new MemberLoadItem(propertyStep, "payload", "recv0", "v1");
        var staticMemberItem = new MemberLoadItem(propertyStep, "ZERO", null, "v7");
        var subscriptItem = new SubscriptLoadItem(expression, "items", "recv1", List.of("arg0"), "v2");
        var callItem = new CallItem(expression, "build", "recv2", List.of("arg1", "arg2"), "v3", callPayload);
        var discardedVoidCallItem = new CallItem(expression, "print", null, List.of("arg3"), null);
        var boolItem = new BoolConstantItem(expression, true, "v4");
        var mergeItem = new MergeValueItem(expression, "v4", "v5");
        var aliasItem = new DirectSlotAliasValueItem(expression, "v8");
        var localDeclarationItem = new LocalDeclarationItem(
                new VariableDeclaration(DeclarationKind.VAR, "local", null, null, false, "var_stmt", SYNTHETIC_RANGE),
                "init0"
        );
        var compoundItem = new CompoundAssignmentBinaryOpItem(
                new AssignmentExpression("+=", identifier("target"), expression, SYNTHETIC_RANGE),
                "+",
                "current0",
                "rhs3",
                "v6"
        );
        var assignmentItem = new AssignmentItem(
                assignmentExpression,
                List.of("slot0", "index0"),
                "rhs3",
                null,
                assignmentPayload
        );
        var assignmentValueItem = new AssignmentItem(
                assignmentExpression,
                List.of("slot0", "index0"),
                "rhs3",
                "v9",
                assignmentPayload
        );

        assertAll(
                () -> assertSame(passStatement, sourceAnchor.statement()),
                () -> assertSame(passStatement, sourceAnchor.anchor()),
                () -> assertSame(expression, opaqueValue.expression()),
                () -> assertSame(expression, opaqueValue.anchor()),
                () -> assertEquals(List.of("lhs0", "rhs0"), opaqueValue.operandValueIds()),
                () -> assertEquals("v0", opaqueValue.resultValueIdOrNull()),
                () -> assertSame(propertyStep, memberItem.anchor()),
                () -> assertEquals("payload", memberItem.memberName()),
                () -> assertEquals(List.of("recv0"), memberItem.operandValueIds()),
                () -> assertEquals("ZERO", staticMemberItem.memberName()),
                () -> assertEquals(List.of(), staticMemberItem.operandValueIds()),
                () -> assertSame(expression, subscriptItem.anchor()),
                () -> assertEquals("items", subscriptItem.memberNameOrNull()),
                () -> assertEquals(List.of("recv1", "arg0"), subscriptItem.operandValueIds()),
                () -> assertSame(expression, callItem.anchor()),
                () -> assertEquals("build", callItem.callableName()),
                () -> assertEquals("v3", callItem.resultValueIdOrNull()),
                () -> assertEquals(List.of("recv2", "arg1", "arg2"), callItem.operandValueIds()),
                () -> assertSame(callPayload, callItem.writableRoutePayloadOrNull()),
                () -> assertEquals("print", discardedVoidCallItem.callableName()),
                () -> assertNull(discardedVoidCallItem.resultValueIdOrNull()),
                () -> assertEquals(List.of("arg3"), discardedVoidCallItem.operandValueIds()),
                () -> assertSame(expression, boolItem.anchor()),
                () -> assertEquals(List.of(), boolItem.operandValueIds()),
                () -> assertEquals("v4", boolItem.resultValueIdOrNull()),
                () -> assertSame(expression, mergeItem.anchor()),
                () -> assertEquals(List.of("v4"), mergeItem.operandValueIds()),
                () -> assertEquals("v5", mergeItem.resultValueIdOrNull()),
                () -> assertSame(expression, aliasItem.anchor()),
                () -> assertEquals(List.of(), aliasItem.operandValueIds()),
                () -> assertEquals("v8", aliasItem.resultValueIdOrNull()),
                () -> assertSame(localDeclarationItem.declaration(), localDeclarationItem.anchor()),
                () -> assertEquals(List.of("init0"), localDeclarationItem.operandValueIds()),
                () -> assertEquals(List.of("current0", "rhs3"), compoundItem.operandValueIds()),
                () -> assertEquals("v6", compoundItem.resultValueIdOrNull()),
                () -> assertSame(assignmentExpression, assignmentItem.anchor()),
                () -> assertEquals(List.of("slot0", "index0", "rhs3"), assignmentItem.operandValueIds()),
                () -> assertSame(assignmentPayload, assignmentItem.writableRoutePayload()),
                () -> assertNull(assignmentItem.resultValueIdOrNull()),
                () -> assertEquals("v9", assignmentValueItem.resultValueIdOrNull()),
                () -> assertTrue(opaqueValue.hasStandaloneMaterializationSlot()),
                () -> assertTrue(callItem.hasStandaloneMaterializationSlot()),
                () -> assertFalse(discardedVoidCallItem.hasStandaloneMaterializationSlot()),
                () -> assertTrue(boolItem.hasStandaloneMaterializationSlot()),
                () -> assertTrue(mergeItem.hasStandaloneMaterializationSlot()),
                () -> assertTrue(compoundItem.hasStandaloneMaterializationSlot()),
                () -> assertTrue(assignmentValueItem.hasStandaloneMaterializationSlot()),
                () -> assertFalse(aliasItem.hasStandaloneMaterializationSlot()),
                () -> assertTrue(localDeclarationItem.hasStandaloneMaterializationSlot()),
                () -> assertFalse(assignmentItem.hasStandaloneMaterializationSlot())
        );
    }

    @Test
    void assignmentItemRejectsMissingWritableRoutePayload() {
        var assignmentExpression = new AssignmentExpression("=", identifier("target"), identifier("rhs"), SYNTHETIC_RANGE);

        var exception = assertThrows(
                NullPointerException.class,
                () -> new AssignmentItem(
                        assignmentExpression,
                        List.of("slot0"),
                        "rhs0",
                        null,
                        null
                )
        );

        assertTrue(exception.getMessage().contains("writableRoutePayload"));
    }

    @Test
    void stopNodeRejectsReturnValueOnSyntheticTerminalMerge() {
        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> new FrontendCfgGraph.StopNode("stop", FrontendCfgGraph.StopKind.TERMINAL_MERGE, "ret0")
        );

        assertTrue(exception.getMessage().contains("Terminal-merge stop node"));
    }

    @Test
    void constructorRejectsTerminalMergeAsEntryOrExecutableTarget() {
        var terminalEntryNodes = new LinkedHashMap<String, FrontendCfgGraph.NodeDef>();
        terminalEntryNodes.put(
                "merge",
                new FrontendCfgGraph.StopNode("merge", FrontendCfgGraph.StopKind.TERMINAL_MERGE, null)
        );
        var terminalTargetNodes = new LinkedHashMap<String, FrontendCfgGraph.NodeDef>();
        terminalTargetNodes.put("entry", new FrontendCfgGraph.SequenceNode("entry", List.of(), "merge"));
        terminalTargetNodes.put(
                "merge",
                new FrontendCfgGraph.StopNode("merge", FrontendCfgGraph.StopKind.TERMINAL_MERGE, null)
        );

        var terminalEntry = assertThrows(
                IllegalArgumentException.class,
                () -> new FrontendCfgGraph("merge", terminalEntryNodes)
        );
        var terminalTarget = assertThrows(
                IllegalArgumentException.class,
                () -> new FrontendCfgGraph("entry", terminalTargetNodes)
        );

        assertAll(
                () -> assertTrue(terminalEntry.getMessage().contains("must not be a synthetic terminal-merge stop")),
                () -> assertTrue(terminalTarget.getMessage().contains("must not target synthetic terminal-merge stop"))
        );
    }

    @Test
    void constructorRejectsMergeSourceWithoutEarlierProducerInSameSequence() {
        var laterProducerNodes = new LinkedHashMap<String, FrontendCfgGraph.NodeDef>();
        laterProducerNodes.put(
                "entry",
                new FrontendCfgGraph.SequenceNode(
                        "entry",
                        List.of(
                                new MergeValueItem(identifier("merge"), "src0", "merged0"),
                                new BoolConstantItem(identifier("source"), true, "src0")
                        ),
                        "stop"
                )
        );
        laterProducerNodes.put("stop", new FrontendCfgGraph.StopNode("stop", FrontendCfgGraph.StopKind.RETURN, "merged0"));

        var crossSequenceNodes = new LinkedHashMap<String, FrontendCfgGraph.NodeDef>();
        crossSequenceNodes.put(
                "entry",
                new FrontendCfgGraph.SequenceNode(
                        "entry",
                        List.of(new OpaqueExprValueItem(identifier("flag"), "v0")),
                        "branch"
                )
        );
        crossSequenceNodes.put("branch", new FrontendCfgGraph.BranchNode("branch", identifier("flag"), "v0", "then", "else"));
        crossSequenceNodes.put(
                "then",
                new FrontendCfgGraph.SequenceNode(
                        "then",
                        List.of(new MergeValueItem(identifier("merge"), "src0", "merged0")),
                        "stop"
                )
        );
        crossSequenceNodes.put(
                "else",
                new FrontendCfgGraph.SequenceNode(
                        "else",
                        List.of(new BoolConstantItem(identifier("source"), true, "src0")),
                        "stop"
                )
        );
        crossSequenceNodes.put("stop", new FrontendCfgGraph.StopNode("stop", FrontendCfgGraph.StopKind.RETURN, "merged0"));

        var laterProducer = assertThrows(
                IllegalArgumentException.class,
                () -> new FrontendCfgGraph("entry", laterProducerNodes)
        );
        var crossSequence = assertThrows(
                IllegalArgumentException.class,
                () -> new FrontendCfgGraph("entry", crossSequenceNodes)
        );

        assertAll(
                () -> assertTrue(laterProducer.getMessage().contains("produced earlier in the same sequence")),
                () -> assertTrue(laterProducer.getMessage().contains("src0")),
                () -> assertTrue(crossSequence.getMessage().contains("produced earlier in the same sequence")),
                () -> assertTrue(crossSequence.getMessage().contains("then"))
        );
    }

    @Test
    void constructorRejectsPayloadBackedCallWithoutDedicatedReceiverValueSlot() {
        var routeAnchor = identifier("call");
        var invalidPayload = new FrontendWritableRoutePayload(
                routeAnchor,
                new FrontendWritableRoutePayload.RootDescriptor(
                        FrontendWritableRoutePayload.RootKind.VALUE_ID,
                        routeAnchor,
                        "late0"
                ),
                new FrontendWritableRoutePayload.LeafDescriptor(
                        FrontendWritableRoutePayload.LeafKind.DIRECT_SLOT,
                        routeAnchor,
                        null,
                        List.of(),
                        null,
                        null
                ),
                List.of()
        );
        var nodes = new LinkedHashMap<String, FrontendCfgGraph.NodeDef>();
        nodes.put(
                "entry",
                new FrontendCfgGraph.SequenceNode(
                        "entry",
                        List.of(
                                new CallItem(routeAnchor, "build", null, List.of(), "v0", invalidPayload),
                                new BoolConstantItem(identifier("late"), true, "late0")
                        ),
                        "stop"
                )
        );
        nodes.put("stop", new FrontendCfgGraph.StopNode("stop", FrontendCfgGraph.StopKind.RETURN, "v0"));

        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> new FrontendCfgGraph("entry", nodes)
        );

        assertAll(
                () -> assertTrue(exception.getMessage().contains("writable call")),
                () -> assertTrue(exception.getMessage().contains("receiverValueIdOrNull")),
                () -> assertTrue(exception.getMessage().contains("entry"))
        );
    }

    @Test
    void constructorRejectsWritableRoutePayloadReferencingLaterLocalValue() {
        var assignment = new AssignmentExpression("=", identifier("target"), identifier("rhs"), SYNTHETIC_RANGE);
        var invalidPayload = new FrontendWritableRoutePayload(
                assignment,
                new FrontendWritableRoutePayload.RootDescriptor(
                        FrontendWritableRoutePayload.RootKind.VALUE_ID,
                        assignment,
                        "late0"
                ),
                new FrontendWritableRoutePayload.LeafDescriptor(
                        FrontendWritableRoutePayload.LeafKind.PROPERTY,
                        assignment,
                        "late0",
                        List.of(),
                        "payload",
                        null
                ),
                List.of()
        );
        var nodes = new LinkedHashMap<String, FrontendCfgGraph.NodeDef>();
        nodes.put(
                "entry",
                new FrontendCfgGraph.SequenceNode(
                        "entry",
                        List.of(
                                new BoolConstantItem(identifier("seed"), true, "v0"),
                                new AssignmentItem(
                                        assignment,
                                        List.of(),
                                        "v0",
                                        null,
                                        invalidPayload
                                ),
                                new BoolConstantItem(identifier("late"), true, "late0")
                        ),
                        "stop"
                )
        );
        nodes.put("stop", new FrontendCfgGraph.StopNode("stop", FrontendCfgGraph.StopKind.RETURN, "v0"));

        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> new FrontendCfgGraph("entry", nodes)
        );

        assertAll(
                () -> assertTrue(exception.getMessage().contains("writable route")),
                () -> assertTrue(exception.getMessage().contains("late0")),
                () -> assertTrue(exception.getMessage().contains("entry"))
        );
    }

    @Test
    void constructorRejectsWritableCallPayloadWithoutDedicatedReceiverValue() {
        var routeAnchor = identifier("call");
        var payload = new FrontendWritableRoutePayload(
                routeAnchor,
                new FrontendWritableRoutePayload.RootDescriptor(
                        FrontendWritableRoutePayload.RootKind.SELF_CONTEXT,
                        routeAnchor,
                        null
                ),
                new FrontendWritableRoutePayload.LeafDescriptor(
                        FrontendWritableRoutePayload.LeafKind.PROPERTY,
                        identifier("payload"),
                        null,
                        List.of(),
                        "payload",
                        null
                ),
                List.of()
        );
        var nodes = new LinkedHashMap<String, FrontendCfgGraph.NodeDef>();
        nodes.put(
                "entry",
                new FrontendCfgGraph.SequenceNode(
                        "entry",
                        List.of(new CallItem(routeAnchor, "push_back", null, List.of(), "v0", payload)),
                        "stop"
                )
        );
        nodes.put("stop", new FrontendCfgGraph.StopNode("stop", FrontendCfgGraph.StopKind.RETURN, "v0"));

        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> new FrontendCfgGraph("entry", nodes)
        );

        assertAll(
                () -> assertTrue(exception.getMessage().contains("writable call")),
                () -> assertTrue(exception.getMessage().contains("receiverValueIdOrNull")),
                () -> assertTrue(exception.getMessage().contains("entry"))
        );
    }

    @Test
    void constructorRejectsNonTerminalStaticWritablePropertyStep() {
        var assignmentExpression = new AssignmentExpression("=", identifier("target"), identifier("rhs"), SYNTHETIC_RANGE);
        var invalidNodes = new LinkedHashMap<String, FrontendCfgGraph.NodeDef>();
        invalidNodes.put(
                "entry",
                new FrontendCfgGraph.SequenceNode(
                        "entry",
                        List.of(
                                new OpaqueExprValueItem(identifier("container"), "v0"),
                                new OpaqueExprValueItem(identifier("key"), "v1"),
                                new AssignmentItem(
                                        assignmentExpression,
                                        List.of("v0", "v1"),
                                        "v0",
                                        null,
                                        new FrontendWritableRoutePayload(
                                                assignmentExpression,
                                                new FrontendWritableRoutePayload.RootDescriptor(
                                                        FrontendWritableRoutePayload.RootKind.STATIC_CONTEXT,
                                                        identifier("Holder"),
                                                        null
                                                ),
                                                new FrontendWritableRoutePayload.LeafDescriptor(
                                                        FrontendWritableRoutePayload.LeafKind.SUBSCRIPT,
                                                        assignmentExpression.left(),
                                                        "v0",
                                                        List.of("v1"),
                                                        null,
                                                        FrontendSubscriptAccessSupport.AccessKind.INDEXED
                                                ),
                                                List.of(
                                                        new FrontendWritableRoutePayload.StepDescriptor(
                                                                FrontendWritableRoutePayload.StepKind.SUBSCRIPT,
                                                                assignmentExpression.left(),
                                                                "v0",
                                                                List.of("v1"),
                                                                null,
                                                                FrontendSubscriptAccessSupport.AccessKind.INDEXED
                                                        ),
                                                        new FrontendWritableRoutePayload.StepDescriptor(
                                                                FrontendWritableRoutePayload.StepKind.PROPERTY,
                                                                identifier("items"),
                                                                null,
                                                                List.of(),
                                                                "items",
                                                                null
                                                        )
                                                )
                                        )
                                )
                        ),
                        "stop"
                )
        );
        invalidNodes.put("stop", new FrontendCfgGraph.StopNode("stop", FrontendCfgGraph.StopKind.RETURN, null));

        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> new FrontendCfgGraph("entry", invalidNodes)
        );

        assertTrue(exception.getMessage().contains("non-terminal static property commit step"), exception.getMessage());
    }

    @Test
    void valueOpItemsRejectBlankValueIds() {
        var blankOpaque = assertThrows(
                IllegalArgumentException.class,
                () -> new OpaqueExprValueItem(identifier("seed"), " ")
        );
        var blankCallOperand = assertThrows(
                IllegalArgumentException.class,
                () -> new CallItem(identifier("seed"), "build", "recv0", List.of("arg0", " "), "v1")
        );
        var blankMemberName = assertThrows(
                IllegalArgumentException.class,
                () -> new MemberLoadItem(identifier("seed"), " ", "recv0", "v2")
        );
        var blankContainerOperand = assertThrows(
                IllegalArgumentException.class,
                () -> new ContainerLiteralItem(
                        new ArrayExpression(List.of(), false, SYNTHETIC_RANGE),
                        List.of("elem0", " "),
                        "v3"
                )
        );

        assertAll(
                () -> assertTrue(blankOpaque.getMessage().contains("resultValueId")),
                () -> assertTrue(blankCallOperand.getMessage().contains("argumentValueIds[1]")),
                () -> assertTrue(blankMemberName.getMessage().contains("memberName")),
                () -> assertTrue(blankContainerOperand.getMessage().contains("operandValueIds[1]"))
        );
    }

    @Test
    void containerLiteralItemExposesStableAnchorOperandsAndRejectsNonLiteralRoots() {
        var arrayExpression = new ArrayExpression(List.of(identifier("a"), identifier("b")), false, SYNTHETIC_RANGE);
        var dictionaryExpression = new DictionaryExpression(List.of(), false, SYNTHETIC_RANGE);
        var arrayItem = new ContainerLiteralItem(arrayExpression, List.of("e0", "e1"), "v10");
        var dictionaryItem = new ContainerLiteralItem(dictionaryExpression, List.of(), "v11");

        assertAll(
                () -> assertSame(arrayExpression, arrayItem.expression()),
                () -> assertSame(arrayExpression, arrayItem.anchor()),
                () -> assertEquals(List.of("e0", "e1"), arrayItem.operandValueIds()),
                () -> assertEquals("v10", arrayItem.resultValueIdOrNull()),
                () -> assertTrue(arrayItem.hasStandaloneMaterializationSlot()),
                () -> assertSame(dictionaryExpression, dictionaryItem.expression()),
                () -> assertEquals(List.of(), dictionaryItem.operandValueIds()),
                () -> assertEquals("v11", dictionaryItem.resultValueIdOrNull())
        );

        var rejected = assertThrows(
                IllegalArgumentException.class,
                () -> new ContainerLiteralItem(identifier("not_a_literal"), List.of(), "v12")
        );
        assertTrue(rejected.getMessage().contains("ArrayExpression"), rejected.getMessage());
    }

    private static IdentifierExpression identifier(String name) {
        return new IdentifierExpression(name, SYNTHETIC_RANGE);
    }

    private static FrontendWritableRoutePayload directSlotPayload(IdentifierExpression anchor) {
        return new FrontendWritableRoutePayload(
                anchor,
                new FrontendWritableRoutePayload.RootDescriptor(
                        FrontendWritableRoutePayload.RootKind.DIRECT_SLOT,
                        anchor,
                        null
                ),
                new FrontendWritableRoutePayload.LeafDescriptor(
                        FrontendWritableRoutePayload.LeafKind.DIRECT_SLOT,
                        anchor,
                        null,
                        List.of(),
                        null,
                        null
                ),
                List.of()
        );
    }
}
