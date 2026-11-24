package gd.script.gdcc.frontend.sema.analyzer;

import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.diagnostic.FrontendDiagnostic;
import gd.script.gdcc.frontend.diagnostic.FrontendDiagnosticSeverity;
import gd.script.gdcc.frontend.diagnostic.FrontendRange;
import gd.script.gdcc.frontend.parse.FrontendModule;
import gd.script.gdcc.frontend.parse.FrontendSourceUnit;
import gd.script.gdcc.frontend.parse.GdScriptParserService;
import gd.script.gdcc.frontend.sema.FrontendAnalysisData;
import gd.script.gdcc.frontend.sema.FrontendCallResolutionKind;
import gd.script.gdcc.frontend.sema.FrontendCallResolutionStatus;
import gd.script.gdcc.frontend.sema.FrontendExpressionTypeStatus;
import gd.script.gdcc.frontend.sema.FrontendMemberResolutionStatus;
import gd.script.gdcc.frontend.sema.FrontendReceiverKind;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.scope.ScopeOwnerKind;
import dev.superice.gdparser.frontend.ast.AssignmentExpression;
import dev.superice.gdparser.frontend.ast.AttributeCallStep;
import dev.superice.gdparser.frontend.ast.AttributeExpression;
import dev.superice.gdparser.frontend.ast.AttributePropertyStep;
import dev.superice.gdparser.frontend.ast.BinaryExpression;
import dev.superice.gdparser.frontend.ast.CallExpression;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.LambdaExpression;
import dev.superice.gdparser.frontend.ast.MissingAttributeStep;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.ReturnStatement;
import dev.superice.gdparser.frontend.ast.SubscriptExpression;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Semantic-level coverage for the `super` call route
/// (`doc/module_impl/frontend/frontend_super_call_implementation.md`).
class FrontendSuperCallSemanticsTest {
    @Test
    void superChainCallResolvesToParentImplementation() throws Exception {
        var analyzed = analyze(
                "super_chain.gd",
                """
                        class_name SuperChain
                        extends Node

                        class Base:
                            func greet() -> int:
                                return 1

                        class Child extends Base:
                            func greet() -> int:
                                return 2
                            func probe() -> int:
                                return super.greet()
                        """
        );

        var probe = findFunction(analyzed.unit().ast(), "probe");
        var superStep = findNode(probe, AttributeCallStep.class, step -> step.name().equals("greet"));
        var resolvedCall = analyzed.analysisData().resolvedCalls().get(superStep);
        assertNotNull(resolvedCall);
        var receiverType = resolvedCall.receiverType();
        org.junit.jupiter.api.Assertions.assertAll(
                () -> assertEquals(FrontendCallResolutionStatus.RESOLVED, resolvedCall.status(),
                        String.valueOf(resolvedCall.detailReason())),
                () -> assertEquals(FrontendCallResolutionKind.SUPER_METHOD, resolvedCall.callKind()),
                () -> assertEquals(FrontendReceiverKind.INSTANCE, resolvedCall.receiverKind()),
                () -> assertEquals(ScopeOwnerKind.GDCC, resolvedCall.ownerKind()),
                () -> assertEquals("greet", resolvedCall.callableName()),
                () -> assertEquals("int", resolvedCall.returnType() == null ? null : resolvedCall.returnType().getTypeName()),
                // The receiver stays the lexical current class so the backend `CALL_SUPER_METHOD`
                // self invariant (`receiver type == bodyBuilder.clazz()`) holds.
                () -> assertEquals("SuperChain__sub__Child", receiverType == null ? null : receiverType.getTypeName()),
                () -> assertNotNull(resolvedCall.exactCallableBoundary()),
                () -> assertTrue(diagnosticsByCategory(analyzed.analysisData(), "sema.call_resolution").isEmpty()),
                () -> assertTrue(diagnosticsByCategory(analyzed.analysisData(), "sema.binding").isEmpty())
        );
    }

    @Test
    void bareSuperCallUsesEnclosingFunctionName() throws Exception {
        var analyzed = analyze(
                "super_bare.gd",
                """
                        class_name SuperBare
                        extends Node

                        class Base:
                            func greet(name: String) -> int:
                                return 1

                        class Child extends Base:
                            func greet(name: String) -> int:
                                return super(name)
                        """
        );

        var superCall = findNode(analyzed.unit().ast(), CallExpression.class,
                call -> call.callee() instanceof IdentifierExpression callee && callee.name().equals("super"));
        var resolvedCall = analyzed.analysisData().resolvedCalls().get(superCall);
        assertNotNull(resolvedCall);
        org.junit.jupiter.api.Assertions.assertAll(
                () -> assertEquals(FrontendCallResolutionStatus.RESOLVED, resolvedCall.status(),
                        String.valueOf(resolvedCall.detailReason())),
                () -> assertEquals(FrontendCallResolutionKind.SUPER_METHOD, resolvedCall.callKind()),
                () -> assertEquals("greet", resolvedCall.callableName()),
                () -> assertEquals("int", resolvedCall.returnType() == null ? null : resolvedCall.returnType().getTypeName()),
                () -> assertNotNull(resolvedCall.exactCallableBoundary()),
                () -> assertEquals(1, resolvedCall.exactCallableBoundary().fixedParameterTypes().size()),
                () -> assertTrue(diagnosticsByCategory(analyzed.analysisData(), "sema.call_resolution").isEmpty())
        );
    }

    @Test
    void superCallResolvesNearestAncestorBeyondDirectParent() throws Exception {
        var analyzed = analyze(
                "super_grandparent.gd",
                """
                        class_name SuperGrandparent
                        extends Node

                        class GrandBase:
                            func deep() -> int:
                                return 7

                        class Base extends GrandBase:
                            func other():
                                pass

                        class Child extends Base:
                            func probe() -> int:
                                return super.deep()
                        """
        );

        var probe = findFunction(analyzed.unit().ast(), "probe");
        var superStep = findNode(probe, AttributeCallStep.class, step -> step.name().equals("deep"));
        var resolvedCall = analyzed.analysisData().resolvedCalls().get(superStep);
        assertNotNull(resolvedCall);
        org.junit.jupiter.api.Assertions.assertAll(
                () -> assertEquals(FrontendCallResolutionStatus.RESOLVED, resolvedCall.status(),
                        String.valueOf(resolvedCall.detailReason())),
                () -> assertEquals(FrontendCallResolutionKind.SUPER_METHOD, resolvedCall.callKind()),
                () -> assertEquals("int", resolvedCall.returnType() == null ? null : resolvedCall.returnType().getTypeName()),
                () -> assertTrue(diagnosticsByCategory(analyzed.analysisData(), "sema.call_resolution").isEmpty())
        );
    }

    @Test
    void superCallFailsWhenParentChainLacksMethod() throws Exception {
        var analyzed = analyze(
                "super_missing.gd",
                """
                        class_name SuperMissing
                        extends Node

                        class Base:
                            func known():
                                pass

                        class Child extends Base:
                            func probe():
                                super.missing()
                        """
        );

        var probe = findFunction(analyzed.unit().ast(), "probe");
        var superStep = findNode(probe, AttributeCallStep.class, step -> step.name().equals("missing"));
        var resolvedCall = analyzed.analysisData().resolvedCalls().get(superStep);
        assertNotNull(resolvedCall);
        org.junit.jupiter.api.Assertions.assertAll(
                () -> assertEquals(FrontendCallResolutionStatus.FAILED, resolvedCall.status()),
                () -> assertEquals(FrontendCallResolutionKind.SUPER_METHOD, resolvedCall.callKind()),
                () -> assertFalse(diagnosticsByCategory(analyzed.analysisData(), "sema.call_resolution").isEmpty())
        );
    }

    @Test
    void superPropertyAccessFails() throws Exception {
        var analyzed = analyze(
                "super_property.gd",
                """
                        class_name SuperProperty
                        extends Node

                        class Base:
                            var payload: int = 1

                        class Child extends Base:
                            func probe() -> int:
                                return super.payload
                        """
        );

        var probe = findFunction(analyzed.unit().ast(), "probe");
        var superStep = findNode(probe, AttributePropertyStep.class, step -> step.name().equals("payload"));
        var resolvedMember = analyzed.analysisData().resolvedMembers().get(superStep);
        assertNotNull(resolvedMember);
        assertEquals(FrontendMemberResolutionStatus.FAILED, resolvedMember.status());
        assertSinglePrimaryFailure(analyzed, superStep, "parse.super.missing_call", "parse.super.missing_call");
    }

    @Test
    void bareSuperValuePublishesOneFailureAtTheKeyword() throws Exception {
        var analyzed = analyze(
                "super_value.gd",
                """
                        class_name SuperValue
                        extends Node

                        class Base:
                            func greet():
                                pass

                        class Child extends Base:
                            func probe():
                                var x = super
                                return x
                        """
        );

        assertBareSuperFailure(analyzed);
    }

    @Test
    void superCallInStaticFunctionFails() throws Exception {
        var analyzed = analyze(
                "super_static.gd",
                """
                        class_name SuperStatic
                        extends Node

                        class Base:
                            func greet():
                                pass

                        class Child extends Base:
                            static func probe():
                                super.greet()
                        """
        );

        var bindingDiagnostics = diagnosticsByCategory(analyzed.analysisData(), "sema.binding");
        assertTrue(
                bindingDiagnostics.stream().anyMatch(diagnostic -> diagnostic.message().contains("'super' is not available in static context")),
                () -> "Expected a static-context binding diagnostic, got: " + bindingDiagnostics
        );
    }

    @Test
    void superInitCallIsRejected() throws Exception {
        var analyzed = analyze(
                "super_init.gd",
                """
                        class_name SuperInit
                        extends Node

                        class Base:
                            func _init():
                                pass

                        class Child extends Base:
                            func _init():
                                super()
                        """
        );

        var superCall = findNode(analyzed.unit().ast(), CallExpression.class,
                call -> call.callee() instanceof IdentifierExpression callee && callee.name().equals("super"));
        var resolvedCall = analyzed.analysisData().resolvedCalls().get(superCall);
        assertNotNull(resolvedCall);
        org.junit.jupiter.api.Assertions.assertAll(
                () -> assertEquals(FrontendCallResolutionStatus.FAILED, resolvedCall.status()),
                () -> assertEquals(FrontendCallResolutionKind.SUPER_METHOD, resolvedCall.callKind()),
                () -> assertTrue(String.valueOf(resolvedCall.detailReason()).contains("automatically"),
                        String.valueOf(resolvedCall.detailReason()))
        );
    }

    @Test
    void bareSuperCallInsideLambdaFailsClosed() throws Exception {
        var analyzed = analyze(
                "super_lambda_bare.gd",
                """
                        class_name SuperLambdaBare
                        extends Node

                        class Base:
                            func greet():
                                pass

                        class Child extends Base:
                            func greet():
                                var callback = func():
                                    super()
                                return callback
                        """
        );

        var superCall = findNode(analyzed.unit().ast(), CallExpression.class,
                call -> call.callee() instanceof IdentifierExpression callee && callee.name().equals("super"));
        var resolvedCall = analyzed.analysisData().resolvedCalls().get(superCall);
        assertNotNull(resolvedCall);
        org.junit.jupiter.api.Assertions.assertAll(
                () -> assertEquals(FrontendCallResolutionStatus.FAILED, resolvedCall.status()),
                () -> assertTrue(String.valueOf(resolvedCall.detailReason()).contains("enclosing named function"),
                        String.valueOf(resolvedCall.detailReason()))
        );
    }

    @Test
    void superCallInsideLambdaCapturesSelf() throws Exception {
        var analyzed = analyze(
                "super_lambda_capture.gd",
                """
                        class_name SuperLambdaCapture
                        extends Node

                        class Base:
                            func greet() -> int:
                                return 1

                        class Child extends Base:
                            func probe():
                                var callback = func():
                                    return super.greet()
                                return callback.call()
                        """
        );

        var probe = findFunction(analyzed.unit().ast(), "probe");
        var lambda = findNode(probe, LambdaExpression.class, _ -> true);
        var plan = analyzed.analysisData().lambdaPlans().get(lambda);
        assertNotNull(plan);
        assertTrue(plan.capturesSelf(), "super call inside a lambda must capture the enclosing self");
        var superStep = findNode(lambda, AttributeCallStep.class, step -> step.name().equals("greet"));
        var resolvedCall = analyzed.analysisData().resolvedCalls().get(superStep);
        assertNotNull(resolvedCall);
        assertEquals(FrontendCallResolutionStatus.RESOLVED, resolvedCall.status(),
                String.valueOf(resolvedCall.detailReason()));
    }

    @Test
    void superCallTargetsEngineSuperclassMethod() throws Exception {
        var analyzed = analyze(
                "super_engine.gd",
                """
                        class_name SuperEngine
                        extends Node

                        func _ready():
                            super._ready()
                        """
        );

        var ready = findFunction(analyzed.unit().ast(), "_ready");
        var superStep = findNode(ready, AttributeCallStep.class, step -> step.name().equals("_ready"));
        var resolvedCall = analyzed.analysisData().resolvedCalls().get(superStep);
        assertNotNull(resolvedCall);
        org.junit.jupiter.api.Assertions.assertAll(
                () -> assertEquals(FrontendCallResolutionStatus.RESOLVED, resolvedCall.status(),
                        String.valueOf(resolvedCall.detailReason())),
                () -> assertEquals(FrontendCallResolutionKind.SUPER_METHOD, resolvedCall.callKind()),
                () -> assertEquals(ScopeOwnerKind.ENGINE, resolvedCall.ownerKind())
        );
    }

    @Test
    void superCallResultContinuesAsOrdinaryChain() throws Exception {
        var analyzed = analyze(
                "super_chain_suffix.gd",
                """
                        class_name SuperChainSuffix
                        extends Node

                        class Base:
                            func make() -> Base:
                                return self
                            func greet() -> int:
                                return 1

                        class Child extends Base:
                            func probe() -> int:
                                return super.make().greet()
                        """
        );

        // Step 0 (`super.make()`) is the super route; step 1 (`greet`) reduces ordinarily against
        // the parent method's return type (`Base`), not the lexical current class.
        var superStep = findNode(analyzed.unit().ast(), AttributeCallStep.class, step -> step.name().equals("make"));
        var suffixStep = findNode(analyzed.unit().ast(), AttributeCallStep.class, step -> step.name().equals("greet"));
        var superCall = analyzed.analysisData().resolvedCalls().get(superStep);
        var suffixCall = analyzed.analysisData().resolvedCalls().get(suffixStep);
        assertNotNull(superCall);
        assertNotNull(suffixCall);
        org.junit.jupiter.api.Assertions.assertAll(
                () -> assertEquals(FrontendCallResolutionStatus.RESOLVED, superCall.status(),
                        String.valueOf(superCall.detailReason())),
                () -> assertEquals(FrontendCallResolutionKind.SUPER_METHOD, superCall.callKind()),
                () -> assertEquals(FrontendCallResolutionStatus.RESOLVED, suffixCall.status(),
                        String.valueOf(suffixCall.detailReason())),
                () -> assertEquals(FrontendCallResolutionKind.INSTANCE_METHOD, suffixCall.callKind()),
                () -> assertEquals(
                        "SuperChainSuffix__sub__Base",
                        suffixCall.receiverType() == null ? null : suffixCall.receiverType().getTypeName()
                ),
                () -> assertTrue(diagnosticsByCategory(analyzed.analysisData(), "sema.call_resolution").isEmpty())
        );
    }

    @Test
    void superAttributeSubscriptStepFails() throws Exception {
        var analyzed = analyze(
                "super_subscript_step.gd",
                """
                        class_name SuperSubscriptStep
                        extends Node

                        class Base:
                            var payload: Array[int] = [1]

                        class Child extends Base:
                            func probe() -> int:
                                return super.payload[0]
                        """
        );

        var subscript = findNode(analyzed.unit().ast(), SubscriptExpression.class, _ -> true);
        var base = assertInstanceOf(AttributeExpression.class, subscript.base());
        var step = assertInstanceOf(AttributePropertyStep.class, base.steps().getFirst());
        assertEquals("payload", step.name());
        assertSinglePrimaryFailure(analyzed, step, "parse.super.missing_call", "parse.super.missing_call");
        var data = analyzed.analysisData();
        assertEquals(FrontendMemberResolutionStatus.FAILED, data.resolvedMembers().get(step).status());
        assertEquals(FrontendExpressionTypeStatus.FAILED, data.expressionTypes().get(subscript).status());
        assertSame(step, data.propagatedFailureOrigins().get(subscript).primaryAnchor());
    }

    @Test
    void bareSuperSubscriptFailsAtTheKeywordWithoutASubscriptEcho() throws Exception {
        var analyzed = analyze(
                "super_bare_subscript.gd",
                """
                        class_name SuperBareSubscript
                        extends Node

                        class Base:
                            var payload: Array[int] = [1]

                        class Child extends Base:
                            func probe() -> int:
                                return super[0]
                        """
        );

        assertBareSuperFailure(analyzed);
        var subscript = findNode(analyzed.unit().ast(), SubscriptExpression.class, _ -> true);
        assertSame(subscript.base(), analyzed.analysisData().propagatedFailureOrigins().get(subscript).primaryAnchor());
    }

    @Test
    void superAsCallArgumentFailsAtTheKeywordWithoutACallEcho() throws Exception {
        var analyzed = analyze(
                "super_argument.gd",
                """
                        class_name SuperArgument
                        extends Node

                        class Base:
                            func greet():
                                pass

                        class Child extends Base:
                            func take(value):
                                pass
                            func probe():
                                take(super)
                        """
        );

        assertBareSuperFailure(analyzed);
        var call = findNode(analyzed.unit().ast(), CallExpression.class, _ -> true);
        assertSame(call.arguments().getFirst(), analyzed.analysisData().propagatedFailureOrigins().get(call).primaryAnchor());
    }

    @Test
    void bareSuperSubscriptWriteKeepsOneFailureAtTheKeywordInBothModes() throws Exception {
        for (var compileMode : List.of(false, true)) {
            for (var activeParse : List.of(false, true)) {
                var analyzed = analyze("bare_super_subscript_write.gd", """
                        extends Node
                        func probe() -> void:
                            super[0] = 1
                        """, compileMode, activeParse);
                var assignment = findNode(analyzed.unit().ast(), AssignmentExpression.class, _ -> true);
                var left = assertInstanceOf(SubscriptExpression.class, assignment.left());
                var keyword = assertInstanceOf(IdentifierExpression.class, left.base());
                assertEquals("super", keyword.name());
                assertSinglePrimaryFailure(analyzed, keyword, "parse.super.missing_access",
                        activeParse ? "parse.super.missing_access" : "sema.expression_resolution");
                var data = analyzed.analysisData();
                assertEquals(FrontendExpressionTypeStatus.FAILED, data.expressionTypes().get(keyword).status());
                assertEquals(FrontendExpressionTypeStatus.FAILED, data.expressionTypes().get(assignment).status());
                assertNull(data.expressionTypes().get(left));
                var origins = data.propagatedFailureOrigins();
                var origin = origins.get(keyword);
                assertSame(keyword, origins.get(left).primaryAnchor());
                assertSame(keyword, origins.get(assignment).primaryAnchor());
                assertSame(origin.originRecovery(), origins.get(left).originRecovery());
                assertSame(origin.originRecovery(), origins.get(assignment).originRecovery());
            }
        }
    }

    @Test
    void superCallReceiversRemainResolvedForPropertyAndSubscriptWrites() throws Exception {
        var source = """
                class_name SuperWriteChains
                extends Node

                class Target:
                    var hp: int = 0

                class Base:
                    var target: Target
                    var items: Array[int] = [0]
                    func get_target() -> Target:
                        return target
                    func get_items() -> Array[int]:
                        return items

                class Child extends Base:
                    func probe() -> void:
                        items[0] = 1
                        super.get_target().hp = 1
                        super.get_items()[0] = 1
                """;
        for (var compileMode : List.of(false, true)) {
            var analyzed = analyze("super_write_chains.gd", source, compileMode, true);
            var data = analyzed.analysisData();
            assertTrue(errors(analyzed).isEmpty(), () -> data.diagnostics().asList().toString());
            var probe = findFunction(analyzed.unit().ast(), "probe");
            var assignments = findNodes(probe, AssignmentExpression.class, _ -> true);
            assertEquals(3, assignments.size());
            for (var assignment : assignments) {
                assertEquals(FrontendExpressionTypeStatus.RESOLVED, data.expressionTypes().get(assignment).status());
                assertNull(data.propagatedFailureOrigins().get(assignment));
            }
            var heads = findNodes(probe, IdentifierExpression.class, id -> id.name().equals("super"));
            assertEquals(2, heads.size());
            for (var head : heads) {
                var type = data.expressionTypes().get(head);
                assertNotNull(type);
                assertEquals(FrontendExpressionTypeStatus.RESOLVED, type.status());
                assertEquals("SuperWriteChains__sub__Child", type.publishedType().getTypeName());
            }
        }
    }

    @Test
    void outerSuperSuffixAndCallInheritTheInnerAnchorBeforeReporting() throws Exception {
        for (var compileMode : List.of(false, true)) {
            for (var activeParse : List.of(false, true)) {
                for (var statement : List.of("return super.payload.hp", "return self.take(super.payload)")) {
                    var analyzed = analyze("super_nested_recovery.gd", """
                            extends Node
                            func take(value):
                                return value
                            func probe():
                                %s
                            """.formatted(statement), compileMode, activeParse);
                    var probe = findFunction(analyzed.unit().ast(), "probe");
                    var owner = findNode(probe, AttributePropertyStep.class, step -> step.name().equals("payload"));
                    assertSinglePrimaryFailure(analyzed, owner, "parse.super.missing_call",
                            activeParse ? "parse.super.missing_call" : "sema.member_resolution");
                    var root = assertInstanceOf(AttributeExpression.class,
                            assertInstanceOf(ReturnStatement.class, probe.body().statements().getFirst()).value());
                    var outerStep = root.steps().getLast();
                    var data = analyzed.analysisData();
                    assertEquals(FrontendExpressionTypeStatus.FAILED, data.expressionTypes().get(root).status());
                    assertEquals(FrontendExpressionTypeStatus.FAILED, data.expressionTypes().get(outerStep).status());
                    assertSame(owner, data.propagatedFailureOrigins().get(root).primaryAnchor());
                    assertSame(owner, data.propagatedFailureOrigins().get(outerStep).primaryAnchor());
                }
            }
        }
    }

    @Test
    void missingSuperAndSelfMemberNamesKeepReceiverFactsInReadAndWritePositions() throws Exception {
        for (var statement : List.of("return super.", "return self.", "self. = 2")) {
            for (var activeParse : List.of(false, true)) {
                var analyzed = analyze("missing_member_recovery.gd", """
                        extends Node
                        func probe():
                            %s
                        """.formatted(statement), true, activeParse);
                var chain = findNode(analyzed.unit().ast(), AttributeExpression.class, _ -> true);
                var missing = assertInstanceOf(MissingAttributeStep.class, chain.steps().getLast());
                var parserCode = statement.contains("super") ? "parse.super.missing_name" : "parse.attribute.missing_name";
                assertSinglePrimaryFailure(analyzed, missing, parserCode, activeParse ? parserCode : "sema.member_resolution");
                var data = analyzed.analysisData();
                assertEquals(FrontendExpressionTypeStatus.RESOLVED, data.expressionTypes().get(chain.base()).status());
                assertEquals(FrontendExpressionTypeStatus.FAILED, data.expressionTypes().get(missing).status());
            }
        }
    }

    @Test
    void superChainRecoveryKeepsTheIndependentLaterBinaryOrArrayError() throws Exception {
        for (var expression : List.of("super.payload + (1 + \"a\")", "[super.payload, 1 + \"a\"]")) {
            var analyzed = analyze("super_independent_error.gd", """
                    extends Node
                    func probe():
                        return %s
                    """.formatted(expression));
            var probe = findFunction(analyzed.unit().ast(), "probe");
            var main = findNode(probe, AttributePropertyStep.class, step -> step.name().equals("payload"));
            var sibling = findNodes(probe, BinaryExpression.class, _ -> true).getLast();
            var errors = errors(analyzed);
            assertEquals(2, errors.size(), errors::toString);
            assertTrue(errors.stream().anyMatch(error -> error.category().equals("parse.super.missing_call")
                    && FrontendRange.fromAstRange(main.range()).equals(error.range())), errors::toString);
            assertTrue(errors.stream().anyMatch(error -> error.category().equals("sema.expression_resolution")
                    && FrontendRange.fromAstRange(sibling.range()).equals(error.range())), errors::toString);
        }
    }

    @Test
    void twoMissingNamesInTheSameRecoveryOwnerKeepDistinctClaimsAndAnIndependentError() throws Exception {
        var analyzed = analyze("missing_name_siblings.gd", """
                extends Node
                func probe():
                    return [self., self., 1 + "a"]
                """, true, false);
        var probe = findFunction(analyzed.unit().ast(), "probe");
        var missing = findNodes(probe, MissingAttributeStep.class, _ -> true);
        var sibling = findNode(probe, BinaryExpression.class, _ -> true);
        var errors = errors(analyzed);
        assertEquals(2, missing.size());
        assertEquals(3, errors.size(), errors::toString);
        for (var step : missing) {
            var origin = analyzed.analysisData().propagatedFailureOrigins().get(step);
            assertSame(step, origin.primaryAnchor());
            var recovery = origin.originRecovery();
            assertNotNull(recovery);
            assertEquals("parse.attribute.missing_name", recovery.code());
            assertEquals(step.range().startByte(), recovery.range().endByte());
            var claim = analyzed.analysisData().claimedParseRecoveries().get(recovery);
            assertNotNull(claim);
            assertEquals(recovery.range(), claim.range());
            assertEquals("sema.member_resolution", claim.category());
            assertTrue(errors.contains(claim), errors::toString);
        }
        assertTrue(errors.stream().anyMatch(error -> error.category().equals("sema.expression_resolution")
                && FrontendRange.fromAstRange(sibling.range()).equals(error.range())), errors::toString);
    }

    @Test
    void recoveryLookupKeepsSameRangeClaimsInSeparateSourceFiles() throws Exception {
        var source = """
                extends Node
                func probe():
                    return self.
                """;
        for (var compileMode : List.of(false, true)) {
            var manager = new DiagnosticManager();
            var parser = new GdScriptParserService();
            var active = parser.parseUnit(Path.of("tmp", "active_recovery.gd"), source, manager);
            var inactive = parser.parseUnit(Path.of("tmp", "inactive_recovery.gd"), source, new DiagnosticManager());
            var activeRecovery = active.parseRecoveries().getFirst();
            var inactiveRecovery = inactive.parseRecoveries().getFirst();
            assertEquals(activeRecovery.range(), inactiveRecovery.range());
            assertEquals(activeRecovery.code(), inactiveRecovery.code());
            var module = new FrontendModule("test_module", List.of(active, inactive));
            var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
            var analyzer = new FrontendSemanticAnalyzer();
            var data = compileMode
                    ? analyzer.analyzeForCompile(module, registry, manager)
                    : analyzer.analyze(module, registry, manager);
            var reported = manager.snapshot().asList().stream()
                    .filter(diagnostic -> diagnostic.severity() == FrontendDiagnosticSeverity.ERROR)
                    .toList();
            assertEquals(2, reported.size(), reported::toString);
            for (var unit : List.of(active, inactive)) {
                var missing = findNode(unit.ast(), MissingAttributeStep.class, _ -> true);
                var origin = data.propagatedFailureOrigins().get(missing);
                assertNotNull(origin);
                assertSame(missing, origin.primaryAnchor());
                var recovery = unit.parseRecoveries().getFirst();
                assertSame(recovery, origin.originRecovery());
                var claim = data.claimedParseRecoveries().get(recovery);
                assertNotNull(claim);
                assertEquals(FrontendDiagnostic.sourcePathText(unit.path()), claim.sourcePath());
                assertEquals(unit == active ? recovery.code() : "sema.member_resolution", claim.category());
                assertEquals(recovery.range(), claim.range());
                assertTrue(reported.contains(claim), reported::toString);
            }
        }
    }

    @Test
    void superCallToStaticTargetFails() throws Exception {
        var analyzed = analyze(
                "super_static_target.gd",
                """
                        class_name SuperStaticTarget
                        extends Node

                        class Base:
                            static func make() -> int:
                                return 1

                        class Child extends Base:
                            func probe() -> int:
                                return super.make()
                        """
        );

        var superStep = findNode(analyzed.unit().ast(), AttributeCallStep.class, step -> step.name().equals("make"));
        var resolvedCall = analyzed.analysisData().resolvedCalls().get(superStep);
        assertNotNull(resolvedCall);
        org.junit.jupiter.api.Assertions.assertAll(
                () -> assertEquals(FrontendCallResolutionStatus.FAILED, resolvedCall.status()),
                () -> assertTrue(String.valueOf(resolvedCall.detailReason()).contains("instance-only"),
                        String.valueOf(resolvedCall.detailReason()))
        );
    }

    @Test
    void superCallWithArgumentMismatchCarriesReadableDetail() throws Exception {
        var analyzed = analyze(
                "super_arity.gd",
                """
                        class_name SuperArity
                        extends Node

                        class Base:
                            func greet(name: String) -> int:
                                return 1

                        class Child extends Base:
                            func probe() -> int:
                                return super.greet()
                        """
        );

        var superStep = findNode(analyzed.unit().ast(), AttributeCallStep.class, step -> step.name().equals("greet"));
        var resolvedCall = analyzed.analysisData().resolvedCalls().get(superStep);
        assertNotNull(resolvedCall);
        org.junit.jupiter.api.Assertions.assertAll(
                () -> assertEquals(FrontendCallResolutionStatus.FAILED, resolvedCall.status()),
                () -> assertTrue(String.valueOf(resolvedCall.detailReason()).contains("No applicable overload"),
                        String.valueOf(resolvedCall.detailReason())),
                () -> assertFalse(String.valueOf(resolvedCall.detailReason()).endsWith("NO_APPLICABLE_OVERLOAD"),
                        "failure must carry the resolver detail message, not the raw enum name")
        );
    }

    @Test
    void superCallInPropertyInitializerIsRejected() throws Exception {
        var analyzed = analyze(
                "super_property_init.gd",
                """
                        class_name SuperPropertyInit
                        extends Node

                        class Base:
                            func greet() -> int:
                                return 1

                        class Child extends Base:
                            var payload: int = super.greet()
                        """
        );

        var unsupported = diagnosticsByCategory(analyzed.analysisData(), "sema.unsupported_binding_subtree");
        assertTrue(
                unsupported.stream().anyMatch(diagnostic -> diagnostic.message().contains("'super'")),
                () -> "Expected a property-initializer boundary diagnostic, got: "
                        + analyzed.analysisData().diagnostics().asList()
        );
    }

    @Test
    void superCallReportsReadableMessageWhenMethodMissing() throws Exception {
        var analyzed = analyze(
                "super_missing_message.gd",
                """
                        class_name SuperMissingMessage
                        extends Node

                        class Base:
                            func known():
                                pass

                        class Child extends Base:
                            func probe():
                                super.missing()
                        """
        );

        var callDiagnostics = diagnosticsByCategory(analyzed.analysisData(), "sema.call_resolution");
        assertTrue(
                callDiagnostics.stream().anyMatch(diagnostic ->
                        diagnostic.message().contains("'missing' was not found on the superclass chain of")),
                () -> "Expected a readable missing-method message, got: " + callDiagnostics
        );
    }

    private static @NotNull AnalyzedScript analyze(
            @NotNull String fileName,
            @NotNull String source
    ) throws Exception {
        return analyze(fileName, source, true, true);
    }

    private static @NotNull AnalyzedScript analyze(
            @NotNull String fileName, @NotNull String source, boolean compileMode, boolean activeParse
    ) throws Exception {
        var parseDiagnostics = new DiagnosticManager();
        var unit = new GdScriptParserService().parseUnit(Path.of("tmp", fileName), source, parseDiagnostics);
        var diagnostics = activeParse ? parseDiagnostics : new DiagnosticManager();
        var module = new FrontendModule("test_module", List.of(unit), Map.of());
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var analyzer = new FrontendSemanticAnalyzer();
        var analysisData = compileMode
                ? analyzer.analyzeForCompile(module, registry, diagnostics)
                : analyzer.analyze(module, registry, diagnostics);
        return new AnalyzedScript(unit, analysisData);
    }

    private static void assertBareSuperFailure(@NotNull AnalyzedScript analyzed) {
        var keyword = findNode(analyzed.unit().ast(), IdentifierExpression.class, id -> id.name().equals("super"));
        assertSinglePrimaryFailure(analyzed, keyword, "parse.super.missing_access", "parse.super.missing_access");
        assertEquals(FrontendExpressionTypeStatus.FAILED, analyzed.analysisData().expressionTypes().get(keyword).status());
    }

    private static void assertSinglePrimaryFailure(
            @NotNull AnalyzedScript analyzed, @NotNull Node anchor, @NotNull String recoveryCode, @NotNull String category
    ) {
        var errors = errors(analyzed);
        assertEquals(1, errors.size(), errors::toString);
        var data = analyzed.analysisData();
        var origin = data.propagatedFailureOrigins().get(anchor);
        assertNotNull(origin);
        assertSame(anchor, origin.primaryAnchor());
        var recovery = origin.originRecovery();
        assertNotNull(recovery);
        assertEquals(recoveryCode, recovery.code());
        assertEquals(category, errors.getFirst().category());
        assertEquals(recovery.range(), errors.getFirst().range());
        if (!(anchor instanceof MissingAttributeStep)) {
            assertEquals(FrontendRange.fromAstRange(anchor.range()), errors.getFirst().range());
        }
        assertEquals(errors.getFirst(), data.claimedParseRecoveries().get(recovery));
    }

    private static @NotNull List<FrontendDiagnostic> errors(@NotNull AnalyzedScript analyzed) {
        return analyzed.analysisData().diagnostics().asList().stream()
                .filter(diagnostic -> diagnostic.severity() == FrontendDiagnosticSeverity.ERROR)
                .toList();
    }

    private static @NotNull FunctionDeclaration findFunction(@NotNull Node root, @NotNull String name) {
        return findNodes(root, FunctionDeclaration.class, function -> function.name().equals(name))
                .stream()
                .findFirst()
                .orElseThrow(() -> new AssertionError("Function not found: " + name));
    }

    private static <T extends Node> @NotNull T findNode(
            @NotNull Node root,
            @NotNull Class<T> nodeType,
            @NotNull Predicate<T> predicate
    ) {
        return findNodes(root, nodeType, predicate).stream()
                .findFirst()
                .orElseThrow(() -> new AssertionError("Node not found: " + nodeType.getSimpleName()));
    }

    private static <T extends Node> @NotNull List<T> findNodes(
            @NotNull Node root,
            @NotNull Class<T> nodeType,
            @NotNull Predicate<T> predicate
    ) {
        var matches = new ArrayList<T>();
        collectMatchingNodes(root, nodeType, predicate, matches);
        return List.copyOf(matches);
    }

    private static <T extends Node> void collectMatchingNodes(
            @NotNull Node node,
            @NotNull Class<T> nodeType,
            @NotNull Predicate<T> predicate,
            @NotNull List<T> matches
    ) {
        if (nodeType.isInstance(node)) {
            var candidate = nodeType.cast(node);
            if (predicate.test(candidate)) {
                matches.add(candidate);
            }
        }
        for (var child : node.getChildren()) {
            collectMatchingNodes(child, nodeType, predicate, matches);
        }
    }

    private static @NotNull List<FrontendDiagnostic> diagnosticsByCategory(
            @NotNull FrontendAnalysisData analysisData,
            @NotNull String category
    ) {
        return analysisData.diagnostics().asList().stream()
                .filter(diagnostic -> diagnostic.category().equals(category))
                .toList();
    }

    private record AnalyzedScript(
            @NotNull FrontendSourceUnit unit,
            @NotNull FrontendAnalysisData analysisData
    ) {
    }
}
