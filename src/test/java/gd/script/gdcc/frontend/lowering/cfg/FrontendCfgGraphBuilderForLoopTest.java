package gd.script.gdcc.frontend.lowering.cfg;

import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.lowering.ForIterationOperationDescriptor;
import gd.script.gdcc.frontend.lowering.cfg.item.ForLoopGetItem;
import gd.script.gdcc.frontend.lowering.cfg.item.ForLoopInitItem;
import gd.script.gdcc.frontend.lowering.cfg.item.ForLoopNextItem;
import gd.script.gdcc.frontend.lowering.cfg.item.ForLoopShouldContinueItem;
import gd.script.gdcc.frontend.lowering.cfg.item.SequenceItem;
import gd.script.gdcc.frontend.lowering.cfg.item.ValueOpItem;
import gd.script.gdcc.frontend.lowering.cfg.region.FrontendForRegion;
import gd.script.gdcc.frontend.lowering.cfg.region.FrontendIfRegion;
import gd.script.gdcc.frontend.parse.FrontendModule;
import gd.script.gdcc.frontend.parse.GdScriptParserService;
import gd.script.gdcc.frontend.sema.FrontendAnalysisData;
import gd.script.gdcc.frontend.sema.FrontendAstSideTable;
import gd.script.gdcc.frontend.sema.analyzer.FrontendSemanticAnalyzer;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdBoolType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdccForRangeIterType;
import dev.superice.gdparser.frontend.ast.ForStatement;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.IfStatement;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Focused tests for `for-in` frontend CFG construction.
///
/// Every test runs the real production pipeline (`analyzeForCompile`) so the consumed iteration plan,
/// source-facing slot type and compile-gate decisions are genuine published facts, never hand-injected
/// side tables. Assertions anchor structural CFG behaviour (region shape, item placement, slot
/// registries, break/continue edges) rather than scanning generated text.
class FrontendCfgGraphBuilderForLoopTest {
    @Test
    void buildsRangeCallForRegionWithSlotsAndItems() throws Exception {
        var analyzed = analyzeFunction(
                "cfg_for_range_call.gd",
                """
                        class_name CfgForRangeCall
                        extends RefCounted
                        
                        func ping() -> int:
                            var total := 0
                            for i in range(3):
                                total = total + i
                            return total
                        """
        );

        var rootBlock = analyzed.function().body();
        var forStatement = assertInstanceOf(ForStatement.class, rootBlock.statements().get(1));
        var build = new FrontendCfgGraphBuilder().buildExecutableBody(rootBlock, analyzed.analysisData());
        var graph = build.graph();

        var region = assertInstanceOf(FrontendForRegion.class, Objects.requireNonNull(build.regions().get(forStatement)));
        var sourceSlot = Objects.requireNonNull(build.forSourceIteratorSlots().get(forStatement));
        var stateSlot = Objects.requireNonNull(build.forIteratorStateSlots().get(forStatement));
        var items = collectForItems(graph, forStatement);

        var initSequence = assertInstanceOf(FrontendCfgGraph.SequenceNode.class, graph.requireNode(region.initEntryId()));
        var conditionSequence = assertInstanceOf(
                FrontendCfgGraph.SequenceNode.class,
                graph.requireNode(region.conditionEntryId())
        );
        var conditionBranch = assertInstanceOf(
                FrontendCfgGraph.BranchNode.class,
                graph.requireNode(conditionSequence.nextId())
        );
        var bodyGetSequence = assertInstanceOf(FrontendCfgGraph.SequenceNode.class, graph.requireNode(region.bodyEntryId()));
        var updateSequence = assertInstanceOf(FrontendCfgGraph.SequenceNode.class, graph.requireNode(region.updateEntryId()));

        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors()),
                // region exposes the five anchors plus the two distinct slot ids
                () -> assertEquals(region.initEntryId(), region.entryId()),
                () -> assertEquals("i", region.sourceIteratorSlotId()),
                () -> assertEquals("cfg_for_iter_0", region.iteratorStateSlotId()),
                () -> assertNotEquals(region.sourceIteratorSlotId(), region.iteratorStateSlotId()),
                // source slot mirrors the final published slot type (int) and the iterator name
                () -> assertNotNull(sourceSlot),
                () -> assertSame(forStatement, sourceSlot.statement()),
                () -> assertEquals("i", sourceSlot.sourceIteratorSlotId()),
                () -> assertEquals(GdIntType.INT.getTypeName(), sourceSlot.exposedType().getTypeName()),
                // hidden state slot uses the range compiler-only state type and distinct temp id
                () -> assertNotNull(stateSlot),
                () -> assertSame(forStatement, stateSlot.statement()),
                () -> assertEquals("cfg_for_iter_0", stateSlot.slotId()),
                () -> assertEquals("cfg_for_iter_next_0", stateSlot.nextTempSlotId()),
                () -> assertSame(GdccForRangeIterType.FOR_RANGE_ITER, stateSlot.stateType()),
                // init consumes the single range argument and publishes no ordinary result
                () -> assertEquals(1, items.init().operandValueIds().size()),
                () -> assertNull(items.init().resultValueIdOrNull()),
                () -> assertFalse(items.init().hasStandaloneMaterializationSlot()),
                () -> assertEquals(region.iteratorStateSlotId(), items.init().iteratorStateSlotId()),
                // should-continue publishes one ordinary bool result consumed by the condition branch
                () -> assertNotNull(items.shouldContinue().resultValueIdOrNull()),
                () -> assertEquals(GdBoolType.BOOL.getTypeName(), items.shouldContinue().shouldContinueOperation().resultType().getTypeName()),
                () -> assertEquals(items.shouldContinue().resultValueId(), conditionBranch.conditionValueId()),
                () -> assertSame(forStatement.iterable(), conditionBranch.conditionRoot()),
                () -> assertEquals(region.bodyEntryId(), conditionBranch.trueTargetId()),
                () -> assertEquals(region.exitId(), conditionBranch.falseTargetId()),
                // get commits the source-facing iterator local and publishes a distinct raw element value
                () -> assertEquals(region.sourceIteratorSlotId(), items.get().sourceIteratorSlotId()),
                () -> assertEquals(region.iteratorStateSlotId(), items.get().iteratorStateSlotId()),
                () -> assertNotEquals(items.get().resultValueId(), items.get().sourceIteratorSlotId()),
                // next advances state through a distinct temp and publishes no ordinary result
                () -> assertNull(items.next().resultValueIdOrNull()),
                () -> assertFalse(items.next().hasStandaloneMaterializationSlot()),
                () -> assertEquals(stateSlot.nextTempSlotId(), items.next().nextTempSlotId()),
                () -> assertEquals(region.iteratorStateSlotId(), items.next().iteratorStateSlotId()),
                // wiring: init -> condition, body get -> body statements, update -> condition
                () -> assertEquals(region.conditionEntryId(), lastSequenceOf(graph, region.initEntryId()).nextId()),
                () -> assertEquals(items.init().iteratorStateSlotId(), region.iteratorStateSlotId()),
                () -> assertEquals(region.conditionEntryId(), updateSequence.nextId()),
                // the four items live in the expected entries
                () -> assertSame(items.init(), firstForItem(initSequence.items(), ForLoopInitItem.class)),
                () -> assertSame(items.shouldContinue(), firstForItem(conditionSequence.items(), ForLoopShouldContinueItem.class)),
                () -> assertSame(items.get(), firstForItem(bodyGetSequence.items(), ForLoopGetItem.class)),
                () -> assertSame(items.next(), firstForItem(updateSequence.items(), ForLoopNextItem.class))
        );
    }

    @Test
    void buildsIntShorthandForRegionReusingRangeContract() throws Exception {
        var analyzed = analyzeFunction(
                "cfg_for_int_shorthand.gd",
                """
                        class_name CfgForIntShorthand
                        extends RefCounted
                        
                        func ping(limit: int) -> int:
                            var total := 0
                            for i in limit:
                                total = total + i
                            return total
                        """
        );

        var rootBlock = analyzed.function().body();
        var forStatement = assertInstanceOf(ForStatement.class, rootBlock.statements().get(1));
        var build = new FrontendCfgGraphBuilder().buildExecutableBody(rootBlock, analyzed.analysisData());
        var graph = build.graph();

        var region = assertInstanceOf(FrontendForRegion.class, Objects.requireNonNull(build.regions().get(forStatement)));
        var sourceSlot = Objects.requireNonNull(build.forSourceIteratorSlots().get(forStatement));
        var stateSlot = Objects.requireNonNull(build.forIteratorStateSlots().get(forStatement));
        var items = collectForItems(graph, forStatement);

        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors()),
                // INT_SHORTHAND keeps the single stop operand verbatim, no fabricated range AST
                () -> assertEquals(1, items.init().operandValueIds().size()),
                () -> assertEquals("i", region.sourceIteratorSlotId()),
                () -> assertEquals(GdIntType.INT.getTypeName(), sourceSlot.exposedType().getTypeName()),
                () -> assertSame(GdccForRangeIterType.FOR_RANGE_ITER, stateSlot.stateType()),
                // the route still lowers through the range intrinsic contract
                () -> assertEquals(
                        "gdcc.for_range_iter.init",
                        items.init().initOperation().intrinsicName()
                ),
                () -> assertEquals(
                        "gdcc.for_range_iter.should_continue",
                        items.shouldContinue().shouldContinueOperation().intrinsicName()
                ),
                () -> assertEquals("gdcc.for_range_iter.get", items.get().getOperation().intrinsicName()),
                () -> assertEquals("gdcc.for_range_iter.next", items.next().nextOperation().intrinsicName())
        );
    }

    @Test
    void publishesRangeArgumentsInSourceOrderBeforeInit() throws Exception {
        var analyzed = analyzeFunction(
                "cfg_for_range_args.gd",
                """
                        class_name CfgForRangeArgs
                        extends RefCounted
                        
                        func ping(start: int, end: int, step: int) -> int:
                            var total := 0
                            for i in range(start, end, step):
                                total = total + i
                            return total
                        """
        );

        var rootBlock = analyzed.function().body();
        var forStatement = assertInstanceOf(ForStatement.class, rootBlock.statements().get(1));
        var build = new FrontendCfgGraphBuilder().buildExecutableBody(rootBlock, analyzed.analysisData());
        var graph = build.graph();
        var region = assertInstanceOf(FrontendForRegion.class, Objects.requireNonNull(build.regions().get(forStatement)));
        var items = collectForItems(graph, forStatement);

        var initSequence = assertInstanceOf(FrontendCfgGraph.SequenceNode.class, graph.requireNode(region.initEntryId()));
        var operandProducers = initSequence.items().stream()
                .filter(ValueOpItem.class::isInstance)
                .map(ValueOpItem.class::cast)
                .filter(item -> !(item instanceof ForLoopInitItem))
                .toList();

        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors()),
                // three range arguments materialize as three operand value ids in source order
                () -> assertEquals(3, items.init().operandValueIds().size()),
                () -> assertEquals(3, operandProducers.size()),
                () -> assertEquals(
                        operandProducers.stream().map(ValueOpItem::resultValueIdOrNull).toList(),
                        items.init().operandValueIds()
                ),
                // the init item is the last item of the init entry, after all operand producers
                () -> assertSame(initSequence.items().getLast(), items.init())
        );
    }

    @Test
    void connectsBreakToExitAndContinueToUpdateEntry() throws Exception {
        var analyzed = analyzeFunction(
                "cfg_for_break_continue.gd",
                """
                        class_name CfgForBreakContinue
                        extends RefCounted
                        
                        func ping(stop_now: bool, skip_now: bool) -> int:
                            var total := 0
                            for i in range(5):
                                if stop_now:
                                    break
                                if skip_now:
                                    continue
                                total = total + i
                            return total
                        """
        );

        var rootBlock = analyzed.function().body();
        var forStatement = assertInstanceOf(ForStatement.class, rootBlock.statements().get(1));
        var breakIf = assertInstanceOf(IfStatement.class, forStatement.body().statements().get(0));
        var continueIf = assertInstanceOf(IfStatement.class, forStatement.body().statements().get(1));
        var build = new FrontendCfgGraphBuilder().buildExecutableBody(rootBlock, analyzed.analysisData());
        var graph = build.graph();

        var region = assertInstanceOf(FrontendForRegion.class, Objects.requireNonNull(build.regions().get(forStatement)));
        var breakIfRegion = assertInstanceOf(FrontendIfRegion.class, Objects.requireNonNull(build.regions().get(breakIf)));
        var continueIfRegion = assertInstanceOf(FrontendIfRegion.class, Objects.requireNonNull(build.regions().get(continueIf)));
        var breakThen = assertInstanceOf(
                FrontendCfgGraph.SequenceNode.class,
                graph.requireNode(breakIfRegion.thenEntryId())
        );
        var continueThen = assertInstanceOf(
                FrontendCfgGraph.SequenceNode.class,
                graph.requireNode(continueIfRegion.thenEntryId())
        );

        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors()),
                // break jumps to the loop exit, continue jumps to the update entry (not the condition)
                () -> assertEquals(region.exitId(), breakThen.nextId()),
                () -> assertEquals(region.updateEntryId(), continueThen.nextId()),
                () -> assertNotEquals(region.conditionEntryId(), continueThen.nextId())
        );
    }

    @Test
    void connectsNestedForBreakAndContinueToInnermostRegionTargets() throws Exception {
        var analyzed = analyzeFunction(
                "cfg_for_nested_break_continue.gd",
                """
                        class_name CfgForNestedBreakContinue
                        extends RefCounted
                        
                        func ping(stop_inner: bool, skip_inner: bool, stop_outer: bool) -> int:
                            var total := 0
                            for i in range(3):
                                for j in range(2):
                                    if stop_inner:
                                        break
                                    if skip_inner:
                                        continue
                                    total = total + j
                                if stop_outer:
                                    break
                            return total
                        """
        );

        var rootBlock = analyzed.function().body();
        var outerFor = assertInstanceOf(ForStatement.class, rootBlock.statements().get(1));
        var innerFor = assertInstanceOf(ForStatement.class, outerFor.body().statements().getFirst());
        var innerBreakIf = assertInstanceOf(IfStatement.class, innerFor.body().statements().get(0));
        var innerContinueIf = assertInstanceOf(IfStatement.class, innerFor.body().statements().get(1));
        var outerBreakIf = assertInstanceOf(IfStatement.class, outerFor.body().statements().get(1));
        var build = new FrontendCfgGraphBuilder().buildExecutableBody(rootBlock, analyzed.analysisData());
        var graph = build.graph();

        var outerRegion = assertInstanceOf(FrontendForRegion.class, Objects.requireNonNull(build.regions().get(outerFor)));
        var innerRegion = assertInstanceOf(FrontendForRegion.class, Objects.requireNonNull(build.regions().get(innerFor)));
        var innerBreakIfRegion = assertInstanceOf(
                FrontendIfRegion.class,
                Objects.requireNonNull(build.regions().get(innerBreakIf))
        );
        var innerContinueIfRegion = assertInstanceOf(
                FrontendIfRegion.class,
                Objects.requireNonNull(build.regions().get(innerContinueIf))
        );
        var outerBreakIfRegion = assertInstanceOf(
                FrontendIfRegion.class,
                Objects.requireNonNull(build.regions().get(outerBreakIf))
        );
        var innerBreakThen = assertInstanceOf(
                FrontendCfgGraph.SequenceNode.class,
                graph.requireNode(innerBreakIfRegion.thenEntryId())
        );
        var innerContinueThen = assertInstanceOf(
                FrontendCfgGraph.SequenceNode.class,
                graph.requireNode(innerContinueIfRegion.thenEntryId())
        );
        var outerBreakThen = assertInstanceOf(
                FrontendCfgGraph.SequenceNode.class,
                graph.requireNode(outerBreakIfRegion.thenEntryId())
        );

        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors()),
                () -> assertEquals(innerRegion.exitId(), innerBreakThen.nextId()),
                () -> assertEquals(innerRegion.updateEntryId(), innerContinueThen.nextId()),
                () -> assertNotEquals(innerRegion.conditionEntryId(), innerContinueThen.nextId()),
                () -> assertNotEquals(outerRegion.exitId(), innerBreakThen.nextId()),
                () -> assertNotEquals(outerRegion.updateEntryId(), innerContinueThen.nextId()),
                () -> assertEquals(outerRegion.exitId(), outerBreakThen.nextId()),
                () -> assertNotEquals(innerRegion.exitId(), outerBreakThen.nextId())
        );
    }

    @Test
    void assignsDistinctHiddenSlotsToNestedAndSiblingLoops() throws Exception {
        var analyzed = analyzeFunction(
                "cfg_for_nested_sibling.gd",
                """
                        class_name CfgForNestedSibling
                        extends RefCounted
                        
                        func ping() -> int:
                            var total := 0
                            for i in range(3):
                                for j in range(i):
                                    total = total + j
                            for k in range(2):
                                total = total + k
                            return total
                        """
        );

        var rootBlock = analyzed.function().body();
        var outerFor = assertInstanceOf(ForStatement.class, rootBlock.statements().get(1));
        var innerFor = assertInstanceOf(ForStatement.class, outerFor.body().statements().getFirst());
        var siblingFor = assertInstanceOf(ForStatement.class, rootBlock.statements().get(2));
        var build = new FrontendCfgGraphBuilder().buildExecutableBody(rootBlock, analyzed.analysisData());

        var outerState = Objects.requireNonNull(build.forIteratorStateSlots().get(outerFor));
        var innerState = Objects.requireNonNull(build.forIteratorStateSlots().get(innerFor));
        var siblingState = Objects.requireNonNull(build.forIteratorStateSlots().get(siblingFor));
        var outerSource = Objects.requireNonNull(build.forSourceIteratorSlots().get(outerFor));
        var innerSource = Objects.requireNonNull(build.forSourceIteratorSlots().get(innerFor));
        var siblingSource = Objects.requireNonNull(build.forSourceIteratorSlots().get(siblingFor));

        var allHiddenIds = List.of(
                outerState.slotId(), outerState.nextTempSlotId(),
                innerState.slotId(), innerState.nextTempSlotId(),
                siblingState.slotId(), siblingState.nextTempSlotId()
        );

        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors()),
                // every loop owns a unique hidden state slot and next temp
                () -> assertEquals(6, allHiddenIds.stream().distinct().count()),
                // source iterator slots keep their distinct source names and ForStatement identities
                () -> assertEquals("i", outerSource.sourceIteratorSlotId()),
                () -> assertEquals("j", innerSource.sourceIteratorSlotId()),
                () -> assertEquals("k", siblingSource.sourceIteratorSlotId()),
                () -> assertSame(outerFor, outerSource.statement()),
                () -> assertSame(innerFor, innerSource.statement()),
                () -> assertSame(siblingFor, siblingSource.statement()),
                // source slot ids never collide with hidden slot ids
                () -> assertFalse(allHiddenIds.contains(outerSource.sourceIteratorSlotId())),
                () -> assertFalse(allHiddenIds.contains(innerSource.sourceIteratorSlotId()))
        );
    }

    @Test
    void keepsHiddenSlotIdsOutOfOrdinaryValueSurface() throws Exception {
        var analyzed = analyzeFunction(
                "cfg_for_hidden_surface.gd",
                """
                        class_name CfgForHiddenSurface
                        extends RefCounted
                        
                        func ping() -> int:
                            var total := 0
                            for i in range(3):
                                total = total + i
                            return total
                        """
        );

        var rootBlock = analyzed.function().body();
        var forStatement = assertInstanceOf(ForStatement.class, rootBlock.statements().get(1));
        var build = new FrontendCfgGraphBuilder().buildExecutableBody(rootBlock, analyzed.analysisData());
        var graph = build.graph();
        var stateSlot = Objects.requireNonNull(build.forIteratorStateSlots().get(forStatement));
        var hiddenIds = java.util.Set.of(stateSlot.slotId(), stateSlot.nextTempSlotId());

        var leaked = new ArrayList<String>();
        for (var nodeId : graph.nodeIds()) {
            if (!(graph.requireNode(nodeId) instanceof FrontendCfgGraph.SequenceNode(_, var items, _))) {
                continue;
            }
            for (var item : items) {
                if (!(item instanceof ValueOpItem valueOpItem)) {
                    continue;
                }
                var resultValueId = valueOpItem.resultValueIdOrNull();
                if (resultValueId != null && hiddenIds.contains(resultValueId)) {
                    leaked.add(resultValueId);
                }
                for (var operandValueId : valueOpItem.operandValueIds()) {
                    if (hiddenIds.contains(operandValueId)) {
                        leaked.add(operandValueId);
                    }
                }
            }
        }

        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors()),
                () -> assertTrue(leaked.isEmpty(), () -> "hidden slot ids leaked into value surface: " + leaked)
        );
    }

    @Test
    void buildsExplicitIteratorTypeForLoopWithExposedType() throws Exception {
        var analyzed = analyzeFunction(
                "cfg_for_explicit_type.gd",
                """
                        class_name CfgForExplicitType
                        extends RefCounted
                        
                        func ping() -> void:
                            for i: float in range(3):
                                print(i)
                        """
        );

        var rootBlock = analyzed.function().body();
        var forStatement = assertInstanceOf(ForStatement.class, rootBlock.statements().getFirst());
        var build = new FrontendCfgGraphBuilder().buildExecutableBody(rootBlock, analyzed.analysisData());

        var region = assertInstanceOf(FrontendForRegion.class, Objects.requireNonNull(build.regions().get(forStatement)));
        var sourceSlot = Objects.requireNonNull(build.forSourceIteratorSlots().get(forStatement));

        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors()),
                () -> assertEquals("i", region.sourceIteratorSlotId()),
                // explicit declared type drives the source-facing slot type, not the int element type
                () -> assertEquals("float", sourceSlot.exposedType().getTypeName())
        );
    }

    @Test
    void buildsForLoopWhoseBodyReturns() throws Exception {
        var analyzed = analyzeFunction(
                "cfg_for_return_body.gd",
                """
                        class_name CfgForReturnBody
                        extends RefCounted
                        
                        func ping() -> int:
                            for i in range(3):
                                return i
                            return 0
                        """
        );

        var rootBlock = analyzed.function().body();
        var forStatement = assertInstanceOf(ForStatement.class, rootBlock.statements().getFirst());
        var build = new FrontendCfgGraphBuilder().buildExecutableBody(rootBlock, analyzed.analysisData());
        var graph = build.graph();

        var region = assertInstanceOf(FrontendForRegion.class, Objects.requireNonNull(build.regions().get(forStatement)));
        var items = collectForItems(graph, forStatement);
        var bodyGetSequence = assertInstanceOf(
                FrontendCfgGraph.SequenceNode.class,
                graph.requireNode(region.bodyEntryId())
        );
        var updateSequence = assertInstanceOf(
                FrontendCfgGraph.SequenceNode.class,
                graph.requireNode(region.updateEntryId())
        );

        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors()),
                // get still anchors the body entry even though the body returns instead of falling through
                () -> assertSame(items.get(), firstForItem(bodyGetSequence.items(), ForLoopGetItem.class)),
                // next still anchors the update entry, which jumps back to the condition
                () -> assertSame(items.next(), firstForItem(updateSequence.items(), ForLoopNextItem.class)),
                () -> assertEquals(region.conditionEntryId(), updateSequence.nextId()),
                // the statement after the loop remains reachable through the loop exit
                () -> assertInstanceOf(FrontendCfgGraph.SequenceNode.class, graph.requireNode(region.exitId()))
        );
    }

    @Test
    void buildsForLoopWhoseBodyOnlyBreaks() throws Exception {
        var analyzed = analyzeFunction(
                "cfg_for_break_body.gd",
                """
                        class_name CfgForBreakBody
                        extends RefCounted
                        
                        func ping() -> int:
                            var total := 0
                            for i in range(3):
                                break
                            return total
                        """
        );

        var rootBlock = analyzed.function().body();
        var forStatement = assertInstanceOf(ForStatement.class, rootBlock.statements().get(1));
        var build = new FrontendCfgGraphBuilder().buildExecutableBody(rootBlock, analyzed.analysisData());
        var graph = build.graph();

        var region = assertInstanceOf(FrontendForRegion.class, Objects.requireNonNull(build.regions().get(forStatement)));
        var items = collectForItems(graph, forStatement);
        // The body entry's get sequence flows into the body block whose only statement breaks to exit.
        var bodyGetSequence = assertInstanceOf(
                FrontendCfgGraph.SequenceNode.class,
                graph.requireNode(region.bodyEntryId())
        );
        var bodyBlockEntry = assertInstanceOf(
                FrontendCfgGraph.SequenceNode.class,
                graph.requireNode(bodyGetSequence.nextId())
        );
        var updateSequence = assertInstanceOf(
                FrontendCfgGraph.SequenceNode.class,
                graph.requireNode(region.updateEntryId())
        );

        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors()),
                // the get entry precedes the body block that breaks straight to the exit
                () -> assertSame(items.get(), firstForItem(bodyGetSequence.items(), ForLoopGetItem.class)),
                () -> assertEquals(region.exitId(), bodyBlockEntry.nextId()),
                // the next item still anchors the update entry
                () -> assertSame(items.next(), firstForItem(updateSequence.items(), ForLoopNextItem.class)),
                () -> assertEquals(region.sourceIteratorSlotId(), items.get().sourceIteratorSlotId())
        );
    }

    @Test
    void buildsForLoopWithPassBody() throws Exception {
        var analyzed = analyzeFunction(
                "cfg_for_pass_body.gd",
                """
                        class_name CfgForPassBody
                        extends RefCounted
                        
                        func ping() -> void:
                            for i in range(3):
                                pass
                        """
        );

        var rootBlock = analyzed.function().body();
        var forStatement = assertInstanceOf(ForStatement.class, rootBlock.statements().getFirst());
        var build = new FrontendCfgGraphBuilder().buildExecutableBody(rootBlock, analyzed.analysisData());
        var graph = build.graph();

        var region = assertInstanceOf(FrontendForRegion.class, Objects.requireNonNull(build.regions().get(forStatement)));
        var items = collectForItems(graph, forStatement);
        var bodyGetSequence = assertInstanceOf(
                FrontendCfgGraph.SequenceNode.class,
                graph.requireNode(region.bodyEntryId())
        );
        var bodyBlockEntry = assertInstanceOf(
                FrontendCfgGraph.SequenceNode.class,
                graph.requireNode(bodyGetSequence.nextId())
        );
        var updateSequence = assertInstanceOf(
                FrontendCfgGraph.SequenceNode.class,
                graph.requireNode(region.updateEntryId())
        );

        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors()),
                () -> assertEquals("i", region.sourceIteratorSlotId()),
                // get anchors the body entry
                () -> assertSame(items.get(), firstForItem(bodyGetSequence.items(), ForLoopGetItem.class)),
                // an empty (pass) body falls through to the update entry...
                () -> assertEquals(region.updateEntryId(), bodyBlockEntry.nextId()),
                // ...and the update entry jumps back to the condition
                () -> assertEquals(region.conditionEntryId(), updateSequence.nextId())
        );
    }

    // ---------------------------------------------------------------------------------------------
    // Build-artifact negative tests: the cross-table validation must fail fast on corrupted artifacts
    // ---------------------------------------------------------------------------------------------

    @Test
    void rejectsStateSlotReusingNextTempId() throws Exception {
        var analyzed = analyzeSimpleRangeLoop();
        var forStatement = analyzed.forStatement();

        // A hidden state slot must keep its slot id and next temp id distinct.
        assertThrowsValidation(() -> new FrontendForIteratorStateSlot(
                forStatement,
                "cfg_for_iter_0",
                "cfg_for_iter_0",
                GdccForRangeIterType.FOR_RANGE_ITER
        ));
    }

    @Test
    void rejectsSourceSlotUsingCompilerOnlyType() throws Exception {
        var analyzed = analyzeSimpleRangeLoop();
        var forStatement = analyzed.forStatement();

        assertThrowsValidation(() -> new FrontendForSourceIteratorSlot(
                forStatement,
                "i",
                GdccForRangeIterType.FOR_RANGE_ITER
        ));
    }

    @Test
    void rejectsRegionSharingSourceAndHiddenSlotId() {
        assertThrowsValidation(() -> new FrontendForRegion(
                "seq_0",
                "seq_1",
                "seq_2",
                "seq_3",
                "seq_4",
                "shared_slot",
                "shared_slot"
        ));
    }

    @Test
    void rejectsGetItemSharingSourceAndHiddenSlotId() throws Exception {
        var analyzed = analyzeSimpleRangeLoop();
        var forStatement = analyzed.forStatement();
        var contract = gd.script.gdcc.frontend.lowering.ForLoweringContractRegistry.get(
                gd.script.gdcc.frontend.sema.FrontendForIterationRoute.RANGE_CALL
        );
        assertNotNull(contract);

        assertThrowsValidation(() -> new ForLoopGetItem(
                forStatement,
                contract.get(),
                "shared_slot",
                "v0",
                "shared_slot"
        ));
    }

    @Test
    void rejectsGetItemWithCompilerOnlyResultType() throws Exception {
        var analyzed = analyzeSimpleRangeLoop();
        var forStatement = analyzed.forStatement();
        // A get operation must produce an ordinary element type, never compiler-only iterator state.
        var compilerOnlyGet = new ForIterationOperationDescriptor(
                "gdcc.for_range_iter.get",
                GdccForRangeIterType.FOR_RANGE_ITER,
                List.of(GdccForRangeIterType.FOR_RANGE_ITER)
        );

        assertThrowsValidation(() -> new ForLoopGetItem(
                forStatement,
                compilerOnlyGet,
                "cfg_for_iter_0",
                "v0",
                "i"
        ));
    }

    @Test
    void rejectsShouldContinueItemWithNonBoolResultType() throws Exception {
        var analyzed = analyzeSimpleRangeLoop();
        var forStatement = analyzed.forStatement();
        // A should-continue operation must produce bool, never an int result.
        var nonBoolShouldContinue = new ForIterationOperationDescriptor(
                "gdcc.for_range_iter.should_continue",
                GdIntType.INT,
                List.of(GdccForRangeIterType.FOR_RANGE_ITER)
        );

        assertThrowsValidation(() -> new ForLoopShouldContinueItem(
                forStatement,
                nonBoolShouldContinue,
                "cfg_for_iter_0",
                "v0"
        ));
    }

    @Test
    void rejectsBuildArtifactMissingSourceSlotMetadata() throws Exception {
        var analyzed = analyzeSimpleRangeLoop();
        var build = new FrontendCfgGraphBuilder().buildExecutableBody(
                analyzed.function().body(),
                analyzed.analysisData()
        );

        // Drop the source-slot metadata while keeping the region: cross-validation must fail fast.
        var emptySourceSlots = new FrontendAstSideTable<FrontendForSourceIteratorSlot>();
        var message = assertThrowsValidationMessage(() -> new FrontendCfgGraphBuilder.ExecutableBodyBuild(
                build.graph(),
                build.regions(),
                emptySourceSlots,
                build.forIteratorStateSlots(),
                build.matchBindSlots(),
                build.foldedMatchBindDeclarations()
        ));
        // Anchor the missing-source-slot fail-fast path specifically, not just any validation error.
        assertTrue(message.contains("source-facing for-in iterator slot"), message);
    }

    @Test
    void rejectsBuildArtifactWithMismatchedStateSlotId() throws Exception {
        var analyzed = analyzeSimpleRangeLoop();
        var forStatement = analyzed.forStatement();
        var build = new FrontendCfgGraphBuilder().buildExecutableBody(
                analyzed.function().body(),
                analyzed.analysisData()
        );

        // Replace the state slot metadata with one whose id disagrees with the region/items.
        var tamperedStateSlots = new FrontendAstSideTable<FrontendForIteratorStateSlot>();
        tamperedStateSlots.put(forStatement, new FrontendForIteratorStateSlot(
                forStatement,
                "cfg_for_iter_99",
                "cfg_for_iter_next_99",
                GdccForRangeIterType.FOR_RANGE_ITER
        ));
        assertThrowsValidation(() -> new FrontendCfgGraphBuilder.ExecutableBodyBuild(
                build.graph(),
                build.regions(),
                build.forSourceIteratorSlots(),
                tamperedStateSlots,
                build.matchBindSlots(),
                build.foldedMatchBindDeclarations()
        ));
    }

    @Test
    void rejectsBuildArtifactWithDuplicateHiddenStateSlotId() throws Exception {
        var analyzed = analyzeFunction(
                "cfg_for_duplicate_slot.gd",
                """
                        class_name CfgForDuplicateSlot
                        extends RefCounted
                        
                        func ping() -> int:
                            var total := 0
                            for i in range(3):
                                for j in range(i):
                                    total = total + j
                            return total
                        """
        );

        var rootBlock = analyzed.function().body();
        var outerFor = assertInstanceOf(ForStatement.class, rootBlock.statements().get(1));
        var innerFor = assertInstanceOf(ForStatement.class, outerFor.body().statements().getFirst());
        var build = new FrontendCfgGraphBuilder().buildExecutableBody(rootBlock, analyzed.analysisData());

        var outerState = Objects.requireNonNull(build.forIteratorStateSlots().get(outerFor));
        // Tamper the inner loop's state slot to reuse the outer loop's slot id: the artifact-level
        // duplicate detection must fail fast even though each record is individually well-formed.
        var tamperedStateSlots = new FrontendAstSideTable<FrontendForIteratorStateSlot>();
        tamperedStateSlots.put(outerFor, outerState);
        tamperedStateSlots.put(innerFor, new FrontendForIteratorStateSlot(
                innerFor,
                outerState.slotId(),
                "cfg_for_iter_next_inner",
                GdccForRangeIterType.FOR_RANGE_ITER
        ));

        var message = assertThrowsValidationMessage(() -> new FrontendCfgGraphBuilder.ExecutableBodyBuild(
                build.graph(),
                build.regions(),
                build.forSourceIteratorSlots(),
                tamperedStateSlots,
                build.matchBindSlots(),
                build.foldedMatchBindDeclarations()
        ));
        // Anchor the duplicate-slot fail-fast path specifically, not just any validation error.
        assertTrue(message.contains("Duplicate"), message);
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private record ForItems(
            @NotNull ForLoopInitItem init,
            @NotNull ForLoopShouldContinueItem shouldContinue,
            @NotNull ForLoopGetItem get,
            @NotNull ForLoopNextItem next
    ) {
    }

    private static @NotNull ForItems collectForItems(
            @NotNull FrontendCfgGraph graph,
            @NotNull ForStatement forStatement
    ) {
        ForLoopInitItem init = null;
        ForLoopShouldContinueItem shouldContinue = null;
        ForLoopGetItem get = null;
        ForLoopNextItem next = null;
        for (var nodeId : graph.nodeIds()) {
            if (!(graph.requireNode(nodeId) instanceof FrontendCfgGraph.SequenceNode(_, var items, _))) {
                continue;
            }
            for (var item : items) {
                if (item instanceof ForLoopInitItem i && i.statement() == forStatement) {
                    init = i;
                } else if (item instanceof ForLoopShouldContinueItem s && s.statement() == forStatement) {
                    shouldContinue = s;
                } else if (item instanceof ForLoopGetItem g && g.statement() == forStatement) {
                    get = g;
                } else if (item instanceof ForLoopNextItem n && n.statement() == forStatement) {
                    next = n;
                }
            }
        }
        assertNotNull(init, "missing ForLoopInitItem");
        assertNotNull(shouldContinue, "missing ForLoopShouldContinueItem");
        assertNotNull(get, "missing ForLoopGetItem");
        assertNotNull(next, "missing ForLoopNextItem");
        return new ForItems(init, shouldContinue, get, next);
    }

    private static @NotNull FrontendCfgGraph.SequenceNode lastSequenceOf(
            @NotNull FrontendCfgGraph graph,
            @NotNull String entryId
    ) {
        // The init entry is a single sequence in these tests; follow it directly.
        return assertInstanceOf(FrontendCfgGraph.SequenceNode.class, graph.requireNode(entryId));
    }

    private static @NotNull SequenceItem firstForItem(
            @NotNull List<SequenceItem> items,
            @NotNull Class<? extends SequenceItem> type
    ) {
        return items.stream()
                .filter(type::isInstance)
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing item of type " + type.getSimpleName()));
    }

    private static void assertThrowsValidation(@NotNull Runnable action) {
        var thrown = false;
        try {
            action.run();
        } catch (IllegalArgumentException | IllegalStateException expected) {
            thrown = true;
        }
        assertTrue(thrown, "expected graph-construction validation to fail fast");
    }

    private static @NotNull String assertThrowsValidationMessage(@NotNull Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException | IllegalStateException expected) {
            return String.valueOf(expected.getMessage());
        }
        throw new AssertionError("expected graph-construction validation to fail fast");
    }

    private static @NotNull AnalyzedForFunction analyzeSimpleRangeLoop() throws Exception {
        var analyzed = analyzeFunction(
                "cfg_for_simple.gd",
                """
                        class_name CfgForSimple
                        extends RefCounted
                        
                        func ping() -> int:
                            var total := 0
                            for i in range(3):
                                total = total + i
                            return total
                        """
        );
        var forStatement = assertInstanceOf(ForStatement.class, analyzed.function().body().statements().get(1));
        return new AnalyzedForFunction(analyzed.function(), analyzed.analysisData(), forStatement);
    }

    private record AnalyzedForFunction(
            @NotNull FunctionDeclaration function,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull ForStatement forStatement
    ) {
    }

    private static @NotNull AnalyzedFunction analyzeFunction(
            @NotNull String fileName,
            @NotNull String source
    ) throws Exception {
        var functionName = "ping";
        var parserService = new GdScriptParserService();
        var parseDiagnostics = new DiagnosticManager();
        var unit = parserService.parseUnit(Path.of("tmp", fileName), source, parseDiagnostics);
        assertTrue(parseDiagnostics.isEmpty(), () -> "Unexpected parse diagnostics: " + parseDiagnostics.snapshot());
        var module = new FrontendModule(
                "test_module",
                List.of(unit),
                Map.of(classNameOf(source), "Runtime" + classNameOf(source))
        );
        var diagnostics = new DiagnosticManager();
        var analysisData = new FrontendSemanticAnalyzer().analyzeForCompile(
                module,
                new ClassRegistry(ExtensionApiLoader.loadDefault()),
                diagnostics
        );
        var function = module.units().getFirst().ast().statements().stream()
                .filter(FunctionDeclaration.class::isInstance)
                .map(FunctionDeclaration.class::cast)
                .filter(candidate -> candidate.name().equals(functionName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing function declaration " + functionName));
        return new AnalyzedFunction(analysisData, diagnostics, function);
    }

    private static @NotNull String classNameOf(@NotNull String source) {
        for (var line : source.split("\n")) {
            var trimmed = line.trim();
            if (trimmed.startsWith("class_name ")) {
                return trimmed.substring("class_name ".length()).trim();
            }
        }
        throw new AssertionError("Missing class_name in source");
    }

    private record AnalyzedFunction(
            @NotNull FrontendAnalysisData analysisData,
            @NotNull DiagnosticManager diagnostics,
            @NotNull FunctionDeclaration function
    ) {
    }
}
