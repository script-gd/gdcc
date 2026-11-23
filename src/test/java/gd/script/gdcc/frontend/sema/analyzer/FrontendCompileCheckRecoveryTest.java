package gd.script.gdcc.frontend.sema.analyzer;

import dev.superice.gdparser.frontend.ast.*;
import gd.script.gdcc.frontend.constant.Evaluation;
import gd.script.gdcc.frontend.diagnostic.*;
import gd.script.gdcc.frontend.parse.FrontendModule;
import gd.script.gdcc.frontend.parse.FrontendParseRecovery;
import gd.script.gdcc.frontend.parse.FrontendSourceUnit;
import gd.script.gdcc.frontend.parse.GdScriptParserService;
import gd.script.gdcc.frontend.sema.*;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.scope.ClassRegistry;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

class FrontendCompileCheckRecoveryTest {
    @Test
    void replaysClaimWithPriorSnapshotOrOriginalAfterClearingItAndNeverDuplicatesOnReturn() throws Exception {
        for (var publishClaim : List.of(false, true)) {
            var input = analyze("claims", "extends RefCounted\nfunc probe(): pass\n", false);
            var owner = find(input.unit().ast(), FunctionDeclaration.class, _ -> true);
            var recovery = recovery("parse.test.type", "damaged type", owner, "function.return_type", owner);
            var claimed = error("sema.type_resolution", "cannot resolve damaged type", input, owner);
            var original = error(recovery.code(), recovery.message(), input, owner);
            setRecoveries(input, recovery);
            input.data().claimParseRecovery(recovery, claimed);
            input.diagnostics().report(claimed);
            input.data().updateDiagnostics(publishClaim ? input.diagnostics().snapshot() : new DiagnosticSnapshot(List.of()));

            var secondManager = new DiagnosticManager();
            var gate = new FrontendCompileCheckAnalyzer();
            gate.analyze(input.data(), secondManager);
            assertEquals(List.of(publishClaim ? claimed : original), secondManager.snapshot().asList());
            assertEquals(claimed, input.data().claimedParseRecoveries().get(recovery));

            input.data().updateDiagnostics(secondManager.snapshot());
            gate.analyze(input.data(), input.diagnostics());
            assertEquals(List.of(claimed), input.diagnostics().snapshot().asList());
            assertEquals(claimed, input.data().claimedParseRecoveries().get(recovery));

            input.data().updateDiagnostics(input.diagnostics().snapshot());
            var alreadyHasOriginal = new DiagnosticManager();
            alreadyHasOriginal.report(original);
            gate.analyze(input.data(), alreadyHasOriginal);
            assertEquals(List.of(original), alreadyHasOriginal.snapshot().asList());
        }
    }

    @Test
    void exactReplayKeepsDistinctMessagesAndOnlyBlockingSemanticsAndRestoresFailedUnits() throws Exception {
        var input = analyze("distinct", "extends RefCounted\nfunc probe(): pass\n", false);
        var anchor = find(input.unit().ast(), FunctionDeclaration.class, _ -> true);
        var firstRecovery = recovery("parse.test", "first parser message", null, null, anchor);
        var secondRecovery = recovery("parse.test", "second parser message", null, null, anchor);
        var failedUnit = new FrontendSourceUnit(Path.of("failure.gd"), "", input.unit().ast(), true);
        var otherFailedUnit = new FrontendSourceUnit(Path.of("failure_without_prior.gd"), "", input.unit().ast(), true);
        input.data().updateSourceUnits(List.of(withRecoveries(input, firstRecovery, secondRecovery), failedUnit, otherFailedUnit));

        var first = error("sema.loop_control_flow", "break outside loop", input, anchor);
        var second = error("sema.loop_control_flow", "continue outside loop", input, anchor);
        var blockingWarning = FrontendDiagnostic.warning(
                FrontendBodyOwnerProcedures.VARIABLE_SLOT_PUBLICATION_CATEGORY, "missing slot",
                sourcePath(input), first.range()
        );
        var ignoredWarning = FrontendDiagnostic.warning("sema.advice", "ordinary warning", sourcePath(input), first.range());
        var staleCompile = error("sema.compile_check", "stale compile-only blocker", input, anchor);
        var orphanParser = error("parse.test.orphan", "not an original recovery", input, anchor);
        var failed = FrontendDiagnostic.error("parse.internal", "Unexpected parser failure: preserved cause",
                failedUnit.path().toString(), null);
        var failedFallback = FrontendDiagnostic.error("parse.internal", "Unexpected parser failure.",
                otherFailedUnit.path().toString(), null);
        input.data().updateDiagnostics(new DiagnosticSnapshot(
                List.of(first, second, blockingWarning, ignoredWarning, staleCompile, orphanParser, failed)
        ));

        var manager = new DiagnosticManager();
        manager.report(second);
        var gate = new FrontendCompileCheckAnalyzer();
        gate.analyze(input.data(), manager);
        var expected = List.of(second, first, blockingWarning,
                error(firstRecovery.code(), firstRecovery.message(), input, anchor),
                error(secondRecovery.code(), secondRecovery.message(), input, anchor),
                failed, failedFallback);
        assertEquals(expected, manager.snapshot().asList());
        gate.analyze(input.data(), manager);
        assertEquals(expected, manager.snapshot().asList());
    }

    @Test
    void parameterizedConstructorGateRegeneratesItsOwnErrorOnceAndKeepsUpstreamCallError() throws Exception {
        var input = analyze("constructor_retry", """
                class_name CompileCheckConstructorRetry
                extends RefCounted
                class Worker:
                    func _init(value: int):
                        pass
                func build(seed):
                    return Worker.new(seed)
                """, true);
        var initial = input.diagnostics().snapshot().asList();
        assertEquals(1, initial.stream().filter(diagnostic -> diagnostic.category().equals("sema.compile_check")).count());
        assertEquals(1, initial.stream().filter(diagnostic -> diagnostic.category().equals("sema.call_resolution")).count());

        var gate = new FrontendCompileCheckAnalyzer();
        gate.analyze(input.data(), input.diagnostics());
        assertEquals(initial, input.diagnostics().snapshot().asList());
        var second = new DiagnosticManager();
        gate.analyze(input.data(), second);
        assertEquals(initial, second.snapshot().asList());
        gate.analyze(input.data(), second);
        assertEquals(initial, second.snapshot().asList());
    }

    @Test
    void constructorGuardStaysInItsFileAndSkipsPrunedAncestorsButStillChecksRejectedDefaults() throws Exception {
        var parser = new GdScriptParserService();
        var diagnostics = new DiagnosticManager();
        var other = parser.parseUnit(Path.of("tmp", "constructor_other.gd"), """
                class_name ConstructorOther
                extends RefCounted
                func stable(): pass
                """, diagnostics);
        var owner = parser.parseUnit(Path.of("tmp", "constructor_owner.gd"), """
                class_name ConstructorOwner
                extends RefCounted
                class Worker:
                    func _init(value: int):
                        pass
                func probe(seed):
                    if true:
                        pass
                    elif false:
                        return Worker.new(seed)
                    return Worker.new(seed)
                func build(worker = Worker.new(1)):
                    pass
                func healthy(value = 2):
                    pass
                """, diagnostics);
        assertTrue(diagnostics.isEmpty(), () -> "Unexpected parse diagnostics: " + diagnostics.snapshot());
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var data = new FrontendSemanticAnalyzer().analyze(
                new FrontendModule("constructor_files", List.of(other, owner)), registry, diagnostics
        );
        var probe = find(owner.ast(), FunctionDeclaration.class, function -> function.name().equals("probe"));
        var branch = find(probe, ElifClause.class, _ -> true);
        var prunedCall = find(branch, AttributeCallStep.class, call -> call.name().equals("new"));
        var activeCall = find(probe.body().statements().getLast(), AttributeCallStep.class, call -> call.name().equals("new"));
        var build = find(owner.ast(), FunctionDeclaration.class, function -> function.name().equals("build"));
        var rejectedDefault = build.parameters().getFirst().defaultValue();
        var defaultCall = find(rejectedDefault, AttributeCallStep.class, call -> call.name().equals("new"));
        var healthy = find(owner.ast(), FunctionDeclaration.class, function -> function.name().equals("healthy"));
        var healthyDefault = healthy.parameters().getFirst().defaultValue();
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, data.expressionTypes().get(healthyDefault).status());
        for (var call : List.of(prunedCall, activeCall, defaultCall)) {
            assertEquals(FrontendCallResolutionKind.CONSTRUCTOR, data.resolvedCalls().get(call).callKind());
        }
        assertTrue(diagnostics.snapshot().asList().stream().anyMatch(diagnostic ->
                diagnostic.category().equals("sema.call_resolution")
                        && Objects.equals(FrontendRange.fromAstRange(defaultCall.range()), diagnostic.range())));
        data.skippedSubtreeRoots().put(branch, true);
        assertFalse(data.skippedSubtreeRoots().containsKey(prunedCall));

        var manager = new DiagnosticManager();
        new FrontendCompileCheckAnalyzer().analyze(data, manager);
        assertCompileRanges(manager, Set.of(activeCall, defaultCall));
        var compileDiagnostics = manager.snapshot().asList().stream()
                .filter(diagnostic -> diagnostic.category().equals("sema.compile_check"))
                .toList();
        for (var diagnostic : compileDiagnostics) {
            assertEquals(FrontendDiagnostic.sourcePathText(owner.path()), diagnostic.sourcePath());
            assertTrue(diagnostic.message().contains("supports only zero-argument custom object construction"));
        }
    }

    @Test
    void fivePropagatedScansYieldToActiveOrCompileSurfacePrimaryButKeepUnrelatedConstBlock() throws Exception {
        var input = analyze("propagated", """
                extends RefCounted
                func echo(value):
                    return value
                func probe(seed):
                    const BAD = [1]
                    const PROPAGATED = 2
                    var primary = 3
                    var container = [4]
                    var field = self.echo(seed).tail
                    return seed
                """, false);
        var primary = variable(input, "primary").value();
        var container = variable(input, "container").value();
        var constant = variable(input, "PROPAGATED");
        var unchangedConstant = variable(input, "BAD");
        var attribute = assertInstanceOf(AttributeExpression.class, variable(input, "field").value());
        var call = assertInstanceOf(AttributeCallStep.class, attribute.steps().getFirst());
        var member = assertInstanceOf(AttributePropertyStep.class, attribute.steps().getLast());
        var probe = find(input.unit().ast(), FunctionDeclaration.class, function -> function.name().equals("probe"));
        var value = assertInstanceOf(IdentifierExpression.class,
                assertInstanceOf(ReturnStatement.class, probe.body().statements().getLast()).value());
        var failure = FrontendExpressionType.failed("synthetic dependency failure");
        input.data().expressionTypes().put(primary, failure);
        input.data().expressionTypes().put(container, failure);
        input.data().resolvedCalls().put(call, FrontendResolvedCall.failed(call.name(),
                FrontendCallResolutionKind.INSTANCE_METHOD, FrontendReceiverKind.INSTANCE, null, null,
                List.of(), null, "call dependency failure"));
        input.data().resolvedMembers().put(member, FrontendResolvedMember.failed(member.name(),
                FrontendBindingKind.PROPERTY, FrontendReceiverKind.INSTANCE, null, null,
                null, "member dependency failure"));
        input.data().symbolBindings().put(value, new FrontendBinding(value.name(), FrontendBindingKind.SUPER, null));
        input.data().constantFacts().put(constant, new Evaluation.Rejected("constant dependency failed", constant.value()));
        for (var dependent : List.of(container, call, member, value, constant.value())) {
            input.data().recordPropagatedFailureOrigin(dependent, primary, null);
        }

        var gate = new FrontendCompileCheckAnalyzer();
        var withPrimaryFact = new DiagnosticManager();
        gate.analyze(input.data(), withPrimaryFact);
        assertCompileRanges(withPrimaryFact, Set.of(primary, unchangedConstant));

        var withPublishedPrimary = new DiagnosticManager();
        var primaryDiagnostic = error("sema.expression_resolution", "primary dependency failed", input, primary);
        withPublishedPrimary.report(primaryDiagnostic);
        gate.analyze(input.data(), withPublishedPrimary);
        assertCompileRanges(withPublishedPrimary, Set.of(unchangedConstant));
        assertEquals(primaryDiagnostic, withPublishedPrimary.snapshot().asList().getFirst());
    }

    @Test
    void prunesDamagedStatementsDefaultsAndBranchesButScansTheirHealthySiblings() throws Exception {
        var input = analyze("pruning", """
                extends RefCounted
                var bad_property: int = 0
                func probe(arg: int = 1):
                    if true:
                        pass
                    elif false:
                        var bad_elif = 2
                    else:
                        var healthy_elif = 3
                    match arg:
                        1:
                            var bad_match = 4
                        _:
                            var healthy_match = 5
                    var bad_statement = 6
                    var healthy_statement = 7
                """, true);
        assertFalse(input.diagnostics().hasErrors());
        var function = find(input.unit().ast(), FunctionDeclaration.class, _ -> true);
        var parameter = function.parameters().getFirst();
        var defaultRoot = Objects.requireNonNull(parameter.defaultValue());
        var elifClause = find(function, ElifClause.class, _ -> true);
        var match = find(function, MatchStatement.class, _ -> true);
        var firstSection = match.sections().getFirst();
        var badProperty = variable(input, "bad_property");
        var badStatement = variable(input, "bad_statement");
        for (var skipped : List.of(defaultRoot, elifClause, firstSection, badProperty, badStatement)) {
            input.data().skippedSubtreeRoots().put(skipped, true);
        }
        input.data().expressionTypes().remove(defaultRoot);
        for (var name : List.of("bad_elif", "bad_match", "bad_property", "bad_statement",
                "healthy_elif", "healthy_match", "healthy_statement")) {
            input.data().expressionTypes().put(variable(input, name).value(), FrontendExpressionType.failed(name));
        }
        var gate = new FrontendCompileCheckAnalyzer();
        var missingCoverage = new DiagnosticManager();
        gate.analyze(input.data(), missingCoverage);
        var healthy = Set.of(variable(input, "healthy_elif").value(), variable(input, "healthy_match").value(),
                variable(input, "healthy_statement").value());
        var expected = new HashSet<Node>(healthy);
        expected.addAll(List.of(defaultRoot, elifClause, firstSection, badProperty, badStatement));
        assertCompileRanges(missingCoverage, expected);

        var defaultRecovery = recovery("parse.test.default", "bad default", parameter, "parameter.default", function);
        var sectionRecovery = recovery("parse.test.section", "bad match branch", firstSection, "match.section", function);
        setRecoveries(input, defaultRecovery, sectionRecovery);
        var covered = new DiagnosticManager();
        gate.analyze(input.data(), covered);
        expected.removeAll(List.of(defaultRoot, firstSection));
        assertCompileRanges(covered, expected);
        assertEquals(2, covered.snapshot().asList().stream()
                .filter(diagnostic -> diagnostic.category().startsWith("parse.")).count());
    }

    @Test
    void missingAttributeFailureHasOneTerminalAnchorWithOrWithoutActiveParserCoverage() throws Exception {
        var parseManager = new DiagnosticManager();
        var unit = new GdScriptParserService().parseUnit(Path.of("tmp", "missing_attribute.gd"), """
                extends RefCounted
                func probe(value):
                    return value.
                """, parseManager);
        assertEquals(1, unit.parseRecoveries().size());
        var recovery = unit.parseRecoveries().getFirst();
        assertEquals("parse.attribute.missing_name", recovery.code());
        var input = analyze(unit, parseManager, false);
        var attribute = find(unit.ast(), AttributeExpression.class, _ -> true);
        var step = assertInstanceOf(MissingAttributeStep.class, attribute.steps().getLast());
        input.data().expressionTypes().put(attribute, FrontendExpressionType.failed("missing attribute"));
        input.data().expressionTypes().put(step, FrontendExpressionType.failed("missing attribute step"));
        input.data().recordPropagatedFailureOrigin(attribute, step, recovery);
        input.data().recordPropagatedFailureOrigin(step, step, recovery);
        input.data().updateDiagnostics(new DiagnosticSnapshot(List.of()));

        var gate = new FrontendCompileCheckAnalyzer();
        var withCoverage = new DiagnosticManager();
        gate.analyze(input.data(), withCoverage);
        assertEquals(List.of(FrontendDiagnostic.error(recovery.code(), recovery.message(),
                sourcePath(input), recovery.range())), withCoverage.snapshot().asList());

        input.data().updateSourceUnits(List.of(new FrontendSourceUnit(unit.path(), unit.source(), unit.ast())));
        input.data().recordPropagatedFailureOrigin(attribute, step, null);
        input.data().recordPropagatedFailureOrigin(step, step, null);
        var withoutCoverage = new DiagnosticManager();
        gate.analyze(input.data(), withoutCoverage);
        assertCompileRanges(withoutCoverage, Set.of(step));
    }

    @Test
    void awaitPrunesItsDamagedOperandBeforeScanningTheWrapperAndStillChecksSiblings() throws Exception {
        var input = analyze("await_recovery", """
                extends RefCounted
                signal event
                func probe():
                    await event
                    var sibling = 1
                """, true);
        assertFalse(input.diagnostics().hasErrors());
        var await = find(input.unit().ast(), AwaitExpression.class, _ -> true);
        var sibling = variable(input, "sibling").value();
        input.data().skippedSubtreeRoots().put(await.value(), true);
        for (var node : List.of(await, await.value(), sibling)) {
            input.data().expressionTypes().put(node, FrontendExpressionType.failed("test compile failure"));
        }
        var manager = new DiagnosticManager();
        new FrontendCompileCheckAnalyzer().analyze(input.data(), manager);
        assertCompileRanges(manager, Set.of(await.value(), sibling));
    }

    @Test
    void dictionaryPatternWithNoValueStillPlacesItsKeyOnTheCompileSurface() throws Exception {
        var input = analyze("key_only_match", """
                extends RefCounted
                func probe(arg):
                    match arg:
                        {"first", "second": var value}:
                            return value
                        _:
                            return null
                """, false);
        var dictionary = find(input.unit().ast(), DictionaryExpression.class, _ -> true);
        var keyOnly = dictionary.entries().getFirst();
        assertNull(keyOnly.value());
        input.data().expressionTypes().put(keyOnly.key(), FrontendExpressionType.failed("test key blocker"));
        input.data().updateDiagnostics(new DiagnosticSnapshot(List.of()));
        var manager = new DiagnosticManager();
        new FrontendCompileCheckAnalyzer().analyze(input.data(), manager);
        assertCompileRanges(manager, Set.of(keyOnly.key()));
    }

    private static void assertCompileRanges(DiagnosticManager manager, Set<? extends Node> anchors) {
        var diagnostics = manager.snapshot().asList().stream()
                .filter(diagnostic -> diagnostic.category().equals("sema.compile_check"))
                .toList();
        assertEquals(anchors.size(), diagnostics.size(), () -> "Unexpected compile diagnostics: " + diagnostics);
        assertEquals(new HashSet<>(anchors.stream().map(node -> FrontendRange.fromAstRange(node.range())).toList()),
                new HashSet<>(diagnostics.stream().map(FrontendDiagnostic::range).toList()));
    }

    private static FrontendParseRecovery recovery(String code, String message, Node owner, String facet, Node anchor) {
        return new FrontendParseRecovery(code, FrontendDiagnosticSeverity.ERROR, message,
                FrontendRange.fromAstRange(anchor.range()), owner, facet);
    }

    private static FrontendDiagnostic error(String category, String message, Input input, Node anchor) {
        return FrontendDiagnostic.error(category, message, sourcePath(input), FrontendRange.fromAstRange(anchor.range()));
    }

    private static String sourcePath(Input input) {
        return FrontendDiagnostic.sourcePathText(input.unit().path());
    }

    private static FrontendSourceUnit withRecoveries(Input input, FrontendParseRecovery... recoveries) {
        var unit = input.unit();
        return new FrontendSourceUnit(unit.path(), unit.source(), unit.ast(), false, List.of(recoveries));
    }

    private static void setRecoveries(Input input, FrontendParseRecovery... recoveries) {
        input.data().updateSourceUnits(List.of(withRecoveries(input, recoveries)));
        for (var recovery : recoveries) {
            if (recovery.owner() != null) {
                input.data().parseRecoveriesByOwner().put(recovery.owner(), List.of(recovery));
            }
        }
    }

    private static Input analyze(String name, String source, boolean forCompile) throws Exception {
        var diagnostics = new DiagnosticManager();
        var unit = new GdScriptParserService().parseUnit(Path.of("tmp", name + ".gd"), source, diagnostics);
        assertTrue(diagnostics.isEmpty(), () -> "Unexpected parse diagnostics: " + diagnostics.snapshot());
        return analyze(unit, diagnostics, forCompile);
    }

    private static Input analyze(FrontendSourceUnit unit, DiagnosticManager diagnostics, boolean forCompile) throws Exception {
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var module = new FrontendModule("compile_recovery", List.of(unit));
        var analyzer = new FrontendSemanticAnalyzer();
        var data = forCompile ? analyzer.analyzeForCompile(module, registry, diagnostics)
                : analyzer.analyze(module, registry, diagnostics);
        return new Input(unit, data, diagnostics);
    }

    private static VariableDeclaration variable(Input input, String name) {
        return find(input.unit().ast(), VariableDeclaration.class, variable -> variable.name().equals(name));
    }

    private static <T extends Node> T find(Node root, Class<T> type, Predicate<T> predicate) {
        var result = findOrNull(root, type, predicate);
        return Objects.requireNonNull(result, () -> type.getSimpleName() + " not found");
    }

    private static <T extends Node> T findOrNull(Node root, Class<T> type, Predicate<T> predicate) {
        if (type.isInstance(root)) {
            var matched = type.cast(root);
            if (predicate.test(matched)) {
                return matched;
            }
        }
        for (var child : root.getChildren()) {
            var result = findOrNull(child, type, predicate);
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    private record Input(FrontendSourceUnit unit, FrontendAnalysisData data, DiagnosticManager diagnostics) {
    }
}
