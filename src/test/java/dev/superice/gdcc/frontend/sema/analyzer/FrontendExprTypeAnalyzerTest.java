package dev.superice.gdcc.frontend.sema.analyzer;

import dev.superice.gdcc.frontend.diagnostic.DiagnosticManager;
import dev.superice.gdcc.frontend.parse.GdScriptParserService;
import dev.superice.gdcc.frontend.scope.BlockScope;
import dev.superice.gdcc.frontend.sema.FrontendBindingKind;
import dev.superice.gdcc.frontend.sema.FrontendExpressionType;
import dev.superice.gdcc.frontend.sema.FrontendExpressionTypeStatus;
import dev.superice.gdcc.gdextension.ExtensionApiLoader;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdparser.frontend.ast.AttributeExpression;
import dev.superice.gdparser.frontend.ast.CallExpression;
import dev.superice.gdparser.frontend.ast.ExpressionStatement;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.LiteralExpression;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.SelfExpression;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import dev.superice.gdcc.type.GdCallableType;
import dev.superice.gdcc.type.GdVariantType;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontendExprTypeAnalyzerTest {
    @Test
    void analyzePublishesResolvedAtomicAndChainExpressionTypes() throws Exception {
        var analyzed = analyze(
                "expr_type_resolved.gd",
                """
                        class_name ExprTypeResolved
                        extends Node
                        
                        var payload: int = 1
                        
                        class Worker:
                            static func build(seed) -> String:
                                return ""
                        
                        func ping(seed):
                            1
                            self
                            seed
                            self.payload
                            Worker.build(seed)
                        """
        );

        var pingFunction = findFunction(analyzed.ast(), "ping");
        var literalStatement = assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().getFirst());
        var selfStatement = assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().get(1));
        var seedStatement = assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().get(2));
        var payloadStatement = assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().get(3));
        var buildStatement = assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().get(4));

        var literal = assertInstanceOf(LiteralExpression.class, literalStatement.expression());
        var selfExpression = assertInstanceOf(SelfExpression.class, selfStatement.expression());
        var seedIdentifier = assertInstanceOf(IdentifierExpression.class, seedStatement.expression());
        var payloadExpression = assertInstanceOf(AttributeExpression.class, payloadStatement.expression());
        var buildExpression = assertInstanceOf(AttributeExpression.class, buildStatement.expression());

        var literalType = analyzed.analysisData().expressionTypes().get(literal);
        assertNotNull(literalType);
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, literalType.status());
        assertEquals("int", literalType.publishedType().getTypeName());

        var selfType = analyzed.analysisData().expressionTypes().get(selfExpression);
        assertNotNull(selfType);
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, selfType.status());
        assertEquals("ExprTypeResolved", selfType.publishedType().getTypeName());

        var seedType = analyzed.analysisData().expressionTypes().get(seedIdentifier);
        assertNotNull(seedType);
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, seedType.status());
        assertEquals("Variant", seedType.publishedType().getTypeName());

        var payloadType = analyzed.analysisData().expressionTypes().get(payloadExpression);
        assertNotNull(payloadType);
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, payloadType.status());
        assertEquals("int", payloadType.publishedType().getTypeName());

        var buildType = analyzed.analysisData().expressionTypes().get(buildExpression);
        assertNotNull(buildType);
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, buildType.status());
        assertEquals("String", buildType.publishedType().getTypeName());
    }

    @Test
    void analyzePublishesArgumentAndFinalTypeForResolvedStaticRouteChain() throws Exception {
        var analyzed = analyze(
                "expr_type_static_route_chain.gd",
                """
                        class_name ExprTypeStaticRouteChain
                        extends RefCounted
                        
                        class Handle:
                            func start() -> int:
                                return 1
                        
                        class Worker:
                            var handle: Handle = Handle.new()
                        
                            static func build(seed) -> Worker:
                                return Worker.new()
                        
                        func ping(seed):
                            Worker.build(seed).handle.start()
                        """
        );

        var pingFunction = findFunction(analyzed.ast(), "ping");
        var chainStatement = assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().getFirst());
        var chainExpression = assertInstanceOf(AttributeExpression.class, chainStatement.expression());
        var workerHead = findNode(chainExpression, IdentifierExpression.class, identifier -> identifier.name().equals("Worker"));
        var seedIdentifier = findNode(
                chainExpression,
                IdentifierExpression.class,
                identifier -> identifier.name().equals("seed")
        );

        assertNull(analyzed.analysisData().expressionTypes().get(workerHead));

        var seedType = analyzed.analysisData().expressionTypes().get(seedIdentifier);
        assertNotNull(seedType);
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, seedType.status());
        assertEquals(GdVariantType.VARIANT, seedType.publishedType());

        var chainType = analyzed.analysisData().expressionTypes().get(chainExpression);
        assertNotNull(chainType);
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, chainType.status());
        assertEquals("int", chainType.publishedType().getTypeName());
    }

    @Test
    void analyzeDistinguishesExactVariantFromDynamicVariantDegradation() throws Exception {
        var analyzed = analyze(
                "expr_type_dynamic.gd",
                """
                        class_name DynamicRoute
                        extends RefCounted
                        
                        func ping(worker):
                            worker
                            worker.ping().length
                        """
        );

        var pingFunction = findFunction(analyzed.ast(), "ping");
        var variantStatement = assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().getFirst());
        var dynamicStatement = assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().get(1));

        var workerIdentifier = assertInstanceOf(IdentifierExpression.class, variantStatement.expression());
        var dynamicExpression = assertInstanceOf(AttributeExpression.class, dynamicStatement.expression());

        var exactVariantType = analyzed.analysisData().expressionTypes().get(workerIdentifier);
        assertNotNull(exactVariantType);
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, exactVariantType.status());
        assertEquals(GdVariantType.VARIANT, exactVariantType.publishedType());

        var dynamicType = analyzed.analysisData().expressionTypes().get(dynamicExpression);
        assertNotNull(dynamicType);
        assertEquals(FrontendExpressionTypeStatus.DYNAMIC, dynamicType.status());
        assertEquals(GdVariantType.VARIANT, dynamicType.publishedType());
        assertNotNull(dynamicType.detailReason());
    }

    @Test
    void analyzeUsesDynamicArgumentVariantToKeepOuterCallResolvable() throws Exception {
        var analyzed = analyze(
                "expr_type_dynamic_arg.gd",
                """
                        class_name DynamicArgumentRoute
                        extends RefCounted
                        
                        func consume(value) -> int:
                            return 1
                        
                        func ping(worker):
                            self.consume(worker.ping())
                        """
        );

        var pingFunction = findFunction(analyzed.ast(), "ping");
        var consumeStatement = assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().getFirst());
        var consumeExpression = assertInstanceOf(AttributeExpression.class, consumeStatement.expression());
        var innerDynamic = findNode(
                consumeExpression,
                AttributeExpression.class,
                candidate -> candidate != consumeExpression
        );

        var innerType = analyzed.analysisData().expressionTypes().get(innerDynamic);
        assertNotNull(innerType);
        assertEquals(FrontendExpressionTypeStatus.DYNAMIC, innerType.status());
        assertEquals(GdVariantType.VARIANT, innerType.publishedType());

        var outerType = analyzed.analysisData().expressionTypes().get(consumeExpression);
        assertNotNull(outerType);
        assertEquals(
                FrontendExpressionTypeStatus.RESOLVED,
                outerType.status(),
                outerType.detailReason()
        );
        assertEquals("int", outerType.publishedType().getTypeName());
    }

    @Test
    void analyzeKeepsStaticRouteTypeMetaHeadsOutOfOrdinaryExpressionTypes() throws Exception {
        var analyzed = analyze(
                "expr_type_static_route_heads.gd",
                """
                        class_name ExprTypeStaticRouteHeads
                        extends Node
                        
                        class Worker:
                            static func build(seed) -> String:
                                return ""
                        
                        func ping(seed):
                            Worker.build(seed)
                            Worker.new()
                            Vector3.BACK
                            Node.NOTIFICATION_ENTER_TREE
                        """
        );

        var pingFunction = findFunction(analyzed.ast(), "ping");
        var staticCallStatement = assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().getFirst());
        var constructorStatement = assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().get(1));
        var builtinLoadStatement = assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().get(2));
        var engineLoadStatement = assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().get(3));

        var staticCall = assertInstanceOf(AttributeExpression.class, staticCallStatement.expression());
        var constructorCall = assertInstanceOf(AttributeExpression.class, constructorStatement.expression());
        var builtinLoad = assertInstanceOf(AttributeExpression.class, builtinLoadStatement.expression());
        var engineLoad = assertInstanceOf(AttributeExpression.class, engineLoadStatement.expression());

        var staticCallHead = findNode(staticCall, IdentifierExpression.class, identifier -> identifier.name().equals("Worker"));
        var constructorHead = findNode(constructorCall, IdentifierExpression.class, identifier -> identifier.name().equals("Worker"));
        var builtinLoadHead = findNode(builtinLoad, IdentifierExpression.class, identifier -> identifier.name().equals("Vector3"));
        var engineLoadHead = findNode(engineLoad, IdentifierExpression.class, identifier -> identifier.name().equals("Node"));

        assertNull(analyzed.analysisData().expressionTypes().get(staticCallHead));
        assertNull(analyzed.analysisData().expressionTypes().get(constructorHead));
        assertNull(analyzed.analysisData().expressionTypes().get(builtinLoadHead));
        assertNull(analyzed.analysisData().expressionTypes().get(engineLoadHead));

        var staticCallType = analyzed.analysisData().expressionTypes().get(staticCall);
        assertNotNull(staticCallType);
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, staticCallType.status());
        assertEquals("String", staticCallType.publishedType().getTypeName());

        var constructorType = analyzed.analysisData().expressionTypes().get(constructorCall);
        assertNotNull(constructorType);
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, constructorType.status());
        assertEquals("ExprTypeStaticRouteHeads$Worker", constructorType.publishedType().getTypeName());

        var builtinLoadType = analyzed.analysisData().expressionTypes().get(builtinLoad);
        assertNotNull(builtinLoadType);
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, builtinLoadType.status());
        assertEquals("Vector3", builtinLoadType.publishedType().getTypeName());

        var engineLoadType = analyzed.analysisData().expressionTypes().get(engineLoad);
        assertNotNull(engineLoadType);
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, engineLoadType.status());
        assertEquals("int", engineLoadType.publishedType().getTypeName());
    }

    @Test
    void analyzePublishesCallableTypesForBareCallableValuesAndMethodReferences() throws Exception {
        var analyzed = analyze(
                "expr_type_callable_values.gd",
                """
                        class_name ExprTypeCallableValues
                        extends RefCounted
                        
                        class Worker:
                            static func build() -> int:
                                return 1
                        
                        func helper() -> int:
                            return 1
                        
                        func ping():
                            helper
                            self.helper
                            Worker.build
                        """
        );

        var pingFunction = findFunction(analyzed.ast(), "ping");
        var bareHelper = assertInstanceOf(
                IdentifierExpression.class,
                assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().getFirst()).expression()
        );
        var instanceMethodReference = assertInstanceOf(
                AttributeExpression.class,
                assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().get(1)).expression()
        );
        var staticMethodReference = assertInstanceOf(
                AttributeExpression.class,
                assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().get(2)).expression()
        );
        var staticHead = findNode(
                staticMethodReference,
                IdentifierExpression.class,
                identifier -> identifier.name().equals("Worker")
        );

        var bareHelperType = analyzed.analysisData().expressionTypes().get(bareHelper);
        assertNotNull(bareHelperType);
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, bareHelperType.status());
        assertInstanceOf(GdCallableType.class, bareHelperType.publishedType());

        var instanceMethodReferenceType = analyzed.analysisData().expressionTypes().get(instanceMethodReference);
        assertNotNull(instanceMethodReferenceType);
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, instanceMethodReferenceType.status());
        assertInstanceOf(GdCallableType.class, instanceMethodReferenceType.publishedType());

        var staticMethodReferenceType = analyzed.analysisData().expressionTypes().get(staticMethodReference);
        assertNotNull(staticMethodReferenceType);
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, staticMethodReferenceType.status());
        assertInstanceOf(GdCallableType.class, staticMethodReferenceType.publishedType());
        assertNull(analyzed.analysisData().expressionTypes().get(staticHead));

        var discardedDiagnostics = diagnosticsByCategory(analyzed, "sema.discarded_expression");
        assertEquals(3, discardedDiagnostics.size());
    }

    @Test
    void analyzeWarnsForDiscardedNonVoidBareCallButNotVoidCall() throws Exception {
        var analyzed = analyze(
                "expr_type_discarded_calls.gd",
                """
                        class_name ExprTypeDiscardedCalls
                        extends RefCounted
                        
                        func helper() -> int:
                            return 1
                        
                        func noop() -> void:
                            pass
                        
                        func ping():
                            helper()
                            noop()
                        """
        );

        var pingFunction = findFunction(analyzed.ast(), "ping");
        var helperCall = assertInstanceOf(
                CallExpression.class,
                assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().getFirst()).expression()
        );
        var noopCall = assertInstanceOf(
                CallExpression.class,
                assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().get(1)).expression()
        );

        var helperCallType = analyzed.analysisData().expressionTypes().get(helperCall);
        assertNotNull(helperCallType);
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, helperCallType.status());
        assertEquals("int", helperCallType.publishedType().getTypeName());

        var noopCallType = analyzed.analysisData().expressionTypes().get(noopCall);
        assertNotNull(noopCallType);
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, noopCallType.status());
        assertEquals("void", noopCallType.publishedType().getTypeName());

        var discardedDiagnostics = diagnosticsByCategory(analyzed, "sema.discarded_expression");
        assertEquals(1, discardedDiagnostics.size());
        assertTrue(discardedDiagnostics.getFirst().message().contains("int"));
    }

    @Test
    void analyzePreservesBareTypeMetaMisuseAsFailedWithoutDuplicateExprError() throws Exception {
        var analyzed = analyze(
                "expr_type_type_meta_misuse.gd",
                """
                        class_name ExprTypeTypeMetaMisuse
                        extends RefCounted
                        
                        class Worker:
                            static func build() -> int:
                                return 1
                        
                        func consume(value) -> void:
                            pass
                        
                        func ping():
                            Worker
                            consume(Worker)
                            var bad := Worker
                        """
        );

        var pingFunction = findFunction(analyzed.ast(), "ping");
        var bareWorker = assertInstanceOf(
                IdentifierExpression.class,
                assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().getFirst()).expression()
        );
        var consumeCall = assertInstanceOf(
                CallExpression.class,
                assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().get(1)).expression()
        );
        var badDeclaration = findVariable(pingFunction.body().statements(), "bad");
        var workerArguments = findNodes(
                consumeCall,
                IdentifierExpression.class,
                identifier -> identifier.name().equals("Worker")
        );

        var bareWorkerType = analyzed.analysisData().expressionTypes().get(bareWorker);
        assertNotNull(bareWorkerType);
        assertEquals(FrontendExpressionTypeStatus.FAILED, bareWorkerType.status());

        assertEquals(1, workerArguments.size());
        var argumentWorkerType = analyzed.analysisData().expressionTypes().get(workerArguments.getFirst());
        assertNotNull(argumentWorkerType);
        assertEquals(FrontendExpressionTypeStatus.FAILED, argumentWorkerType.status());

        var badInitializerType = analyzed.analysisData().expressionTypes().get(badDeclaration.value());
        assertNotNull(badInitializerType);
        assertEquals(FrontendExpressionTypeStatus.FAILED, badInitializerType.status());

        var bodyScope = assertInstanceOf(BlockScope.class, analyzed.analysisData().scopesByAst().get(pingFunction.body()));
        assertEquals(GdVariantType.VARIANT, bodyScope.resolveValue("bad").type());

        assertEquals(3, diagnosticsByCategory(analyzed, "sema.binding").size());
        assertTrue(diagnosticsByCategory(analyzed, "sema.expression_resolution").isEmpty());
        assertTrue(diagnosticsByCategory(analyzed, "sema.discarded_expression").isEmpty());
    }

    @Test
    void analyzeReportsExprOwnedDeferredDiagnosticsForCurrentMvpGaps() throws Exception {
        var analyzed = analyze(
                "expr_type_deferred_gaps.gd",
                """
                        class_name ExprTypeDeferredGaps
                        extends RefCounted
                        
                        func ping(items):
                            1 + 2
                            items[0]
                            items = 1
                        """
        );

        var pingFunction = findFunction(analyzed.ast(), "ping");
        var genericDeferred = assertInstanceOf(
                ExpressionStatement.class,
                pingFunction.body().statements().getFirst()
        ).expression();
        var subscript = assertInstanceOf(
                ExpressionStatement.class,
                pingFunction.body().statements().get(1)
        ).expression();
        var assignment = assertInstanceOf(
                ExpressionStatement.class,
                pingFunction.body().statements().get(2)
        ).expression();

        assertEquals(
                FrontendExpressionTypeStatus.DEFERRED,
                analyzed.analysisData().expressionTypes().get(genericDeferred).status()
        );
        assertEquals(
                FrontendExpressionTypeStatus.DEFERRED,
                analyzed.analysisData().expressionTypes().get(subscript).status()
        );
        assertEquals(
                FrontendExpressionTypeStatus.DEFERRED,
                analyzed.analysisData().expressionTypes().get(assignment).status()
        );

        var deferredDiagnostics = diagnosticsByCategory(analyzed, "sema.deferred_expression_resolution");
        assertEquals(3, deferredDiagnostics.size());
    }

    @Test
    void analyzeReportsExprOwnedErrorAndUnsupportedDiagnostics() throws Exception {
        var analyzed = analyze(
                "expr_type_expr_owned_errors.gd",
                """
                        class_name ExprTypeExprOwnedErrors
                        extends RefCounted
                        
                        func helper() -> int:
                            return 1
                        
                        func make_cb() -> Callable:
                            return helper
                        
                        func takes_text(value: String) -> int:
                            return 1
                        
                        func ping():
                            takes_text(1)
                            self.make_cb()()
                        """
        );

        var pingFunction = findFunction(analyzed.ast(), "ping");
        var mismatchedBareCall = assertInstanceOf(
                CallExpression.class,
                assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().getFirst()).expression()
        );
        var unsupportedDirectCall = assertInstanceOf(
                CallExpression.class,
                assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().get(1)).expression()
        );

        var mismatchedBareCallType = analyzed.analysisData().expressionTypes().get(mismatchedBareCall);
        assertNotNull(mismatchedBareCallType);
        assertEquals(FrontendExpressionTypeStatus.FAILED, mismatchedBareCallType.status());

        var unsupportedDirectCallType = analyzed.analysisData().expressionTypes().get(unsupportedDirectCall);
        assertNotNull(unsupportedDirectCallType);
        assertEquals(FrontendExpressionTypeStatus.UNSUPPORTED, unsupportedDirectCallType.status());

        var expressionDiagnostics = diagnosticsByCategory(analyzed, "sema.expression_resolution");
        assertEquals(1, expressionDiagnostics.size());
        assertTrue(expressionDiagnostics.getFirst().message().contains("No applicable overload"));

        var unsupportedDiagnostics = diagnosticsByCategory(analyzed, "sema.unsupported_expression_route");
        assertEquals(1, unsupportedDiagnostics.size());
        assertTrue(unsupportedDiagnostics.getFirst().message().contains("Direct invocation of callable values"));
    }

    @Test
    void analyzePreservesFailedHeadFailureAcrossNestedArgumentDependency() throws Exception {
        var analyzed = analyze(
                "expr_type_failed_head_arg.gd",
                """
                        class_name ExprTypeFailedHeadArgument
                        extends RefCounted
                        
                        func consume(value) -> int:
                            return 1
                        
                        func ping():
                            self.consume(missing.payload)
                        """
        );

        var pingFunction = findFunction(analyzed.ast(), "ping");
        var consumeStatement = assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().getFirst());
        var consumeExpression = assertInstanceOf(AttributeExpression.class, consumeStatement.expression());
        var failedInner = findNode(
                consumeExpression,
                AttributeExpression.class,
                candidate -> candidate != consumeExpression
        );

        var innerType = analyzed.analysisData().expressionTypes().get(failedInner);
        assertNotNull(innerType);
        assertEquals(FrontendExpressionTypeStatus.FAILED, innerType.status());
        assertTrue(innerType.detailReason().contains("chain head"));

        var outerType = analyzed.analysisData().expressionTypes().get(consumeExpression);
        assertNotNull(outerType);
        assertEquals(FrontendExpressionTypeStatus.FAILED, outerType.status());
        assertTrue(outerType.detailReason().contains("chain head"));

        assertEquals(
                1,
                analyzed.analysisData().diagnostics().asList().stream()
                        .filter(diagnostic -> diagnostic.category().equals("sema.member_resolution"))
                        .count()
        );
        assertEquals(
                1,
                analyzed.analysisData().diagnostics().asList().stream()
                        .filter(diagnostic -> diagnostic.category().equals("sema.call_resolution"))
                        .count()
        );
    }

    @Test
    void analyzePreservesBlockedStatusAcrossNestedArgumentDependency() throws Exception {
        var analyzed = analyze(
                "expr_type_blocked_arg.gd",
                """
                        class_name BlockedArgumentRoute
                        extends RefCounted
                        
                        class Target:
                            func consume(value) -> int:
                                return 1
                        
                        var payload: int = 1
                        
                        static func ping(target: Target):
                            self.payload
                            target.consume(self.payload)
                        """
        );

        var pingFunction = findFunction(analyzed.ast(), "ping");
        var blockedReadStatement = assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().getFirst());
        var blockedCallStatement = assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().get(1));

        var blockedRead = assertInstanceOf(AttributeExpression.class, blockedReadStatement.expression());
        var blockedCall = assertInstanceOf(AttributeExpression.class, blockedCallStatement.expression());

        var blockedReadType = analyzed.analysisData().expressionTypes().get(blockedRead);
        assertNotNull(blockedReadType);
        assertEquals(FrontendExpressionTypeStatus.BLOCKED, blockedReadType.status());
        assertNull(blockedReadType.publishedType());

        var blockedCallType = analyzed.analysisData().expressionTypes().get(blockedCall);
        assertNotNull(blockedCallType);
        assertEquals(FrontendExpressionTypeStatus.BLOCKED, blockedCallType.status());
        assertNull(blockedCallType.publishedType());
        assertNotNull(blockedCallType.detailReason());
    }

    @Test
    void analyzeKeepsFailedInitializerProvenanceWithoutBackfillingInferredLocal() throws Exception {
        var analyzed = analyze(
                "expr_type_failed_initializer.gd",
                """
                        class_name ExprTypeFailedInitializer
                        extends RefCounted
                        
                        func ping():
                            var broken := missing.payload
                            broken
                        """
        );

        var pingFunction = findFunction(analyzed.ast(), "ping");
        var bodyScope = assertInstanceOf(BlockScope.class, analyzed.analysisData().scopesByAst().get(pingFunction.body()));
        var brokenDeclaration = findVariable(pingFunction.body().statements(), "broken");
        var brokenUse = assertInstanceOf(
                IdentifierExpression.class,
                assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().get(1)).expression()
        );

        var initializerType = analyzed.analysisData().expressionTypes().get(brokenDeclaration.value());
        assertNotNull(initializerType);
        assertEquals(FrontendExpressionTypeStatus.FAILED, initializerType.status());
        assertTrue(initializerType.detailReason().contains("chain head"));
        assertSame(
                initializerType,
                assertInitializerProvenanceReachableFromLocalUse(
                        analyzed,
                        brokenUse,
                        brokenDeclaration,
                        FrontendExpressionTypeStatus.FAILED
                )
        );

        assertEquals(GdVariantType.VARIANT, bodyScope.resolveValue("broken").type());

        var brokenUseType = analyzed.analysisData().expressionTypes().get(brokenUse);
        assertNotNull(brokenUseType);
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, brokenUseType.status());
        assertEquals(GdVariantType.VARIANT, brokenUseType.publishedType());

        assertEquals(
                1,
                analyzed.analysisData().diagnostics().asList().stream()
                        .filter(diagnostic -> diagnostic.category().equals("sema.member_resolution"))
                        .count()
        );
    }

    @Test
    void analyzeBackfillsInferredLocalsFromResolvedAndDynamicInitializerTypes() throws Exception {
        var analyzed = analyze(
                "expr_type_inferred_backfill.gd",
                """
                        class_name ExprTypeInferredBackfill
                        extends RefCounted
                        
                        func ping(worker):
                            var resolved := 1
                            var dynamic_value := worker.ping().length
                            resolved
                            dynamic_value
                        """
        );

        var pingFunction = findFunction(analyzed.ast(), "ping");
        var bodyScope = assertInstanceOf(BlockScope.class, analyzed.analysisData().scopesByAst().get(pingFunction.body()));
        var resolvedDeclaration = findVariable(pingFunction.body().statements(), "resolved");
        var dynamicDeclaration = findVariable(pingFunction.body().statements(), "dynamic_value");
        var resolvedUse = assertInstanceOf(
                IdentifierExpression.class,
                assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().get(2)).expression()
        );
        var dynamicUse = assertInstanceOf(
                IdentifierExpression.class,
                assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().get(3)).expression()
        );

        var resolvedInitializerType = analyzed.analysisData().expressionTypes().get(resolvedDeclaration.value());
        assertNotNull(resolvedInitializerType);
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, resolvedInitializerType.status());
        assertEquals("int", resolvedInitializerType.publishedType().getTypeName());

        var dynamicInitializerType = analyzed.analysisData().expressionTypes().get(dynamicDeclaration.value());
        assertNotNull(dynamicInitializerType);
        assertEquals(FrontendExpressionTypeStatus.DYNAMIC, dynamicInitializerType.status());
        assertEquals(GdVariantType.VARIANT, dynamicInitializerType.publishedType());
        assertSame(
                resolvedInitializerType,
                assertInitializerProvenanceReachableFromLocalUse(
                        analyzed,
                        resolvedUse,
                        resolvedDeclaration,
                        FrontendExpressionTypeStatus.RESOLVED
                )
        );
        assertSame(
                dynamicInitializerType,
                assertInitializerProvenanceReachableFromLocalUse(
                        analyzed,
                        dynamicUse,
                        dynamicDeclaration,
                        FrontendExpressionTypeStatus.DYNAMIC
                )
        );

        assertEquals("int", bodyScope.resolveValue("resolved").type().getTypeName());
        assertEquals(GdVariantType.VARIANT, bodyScope.resolveValue("dynamic_value").type());

        var resolvedUseType = analyzed.analysisData().expressionTypes().get(resolvedUse);
        assertNotNull(resolvedUseType);
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, resolvedUseType.status());
        assertEquals("int", resolvedUseType.publishedType().getTypeName());

        var dynamicUseType = analyzed.analysisData().expressionTypes().get(dynamicUse);
        assertNotNull(dynamicUseType);
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, dynamicUseType.status());
        assertEquals(GdVariantType.VARIANT, dynamicUseType.publishedType());
    }

    @Test
    void analyzeLeavesInferredLocalsAsVariantWhenInitializerCannotPublishStableType() throws Exception {
        var analyzed = analyze(
                "expr_type_inferred_no_backfill.gd",
                """
                        class_name ExprTypeInferredNoBackfill
                        extends RefCounted
                        
                        class Worker:
                            pass
                        
                        var payload: int = 1
                        
                        static func ping():
                            var deferred_value := func(offset: int):
                                return offset
                            var unsupported_value := Worker.VALUE
                            var blocked_value := self.payload
                            deferred_value
                            unsupported_value
                            blocked_value
                        """
        );

        var pingFunction = findFunction(analyzed.ast(), "ping");
        var bodyScope = assertInstanceOf(BlockScope.class, analyzed.analysisData().scopesByAst().get(pingFunction.body()));
        var deferredDeclaration = findVariable(pingFunction.body().statements(), "deferred_value");
        var unsupportedDeclaration = findVariable(pingFunction.body().statements(), "unsupported_value");
        var blockedDeclaration = findVariable(pingFunction.body().statements(), "blocked_value");
        var unsupportedHead = findNode(
                unsupportedDeclaration.value(),
                IdentifierExpression.class,
                identifier -> identifier.name().equals("Worker")
        );
        var deferredUse = assertInstanceOf(
                IdentifierExpression.class,
                assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().get(3)).expression()
        );
        var unsupportedUse = assertInstanceOf(
                IdentifierExpression.class,
                assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().get(4)).expression()
        );
        var blockedUse = assertInstanceOf(
                IdentifierExpression.class,
                assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().get(5)).expression()
        );

        var deferredInitializerType = analyzed.analysisData().expressionTypes().get(deferredDeclaration.value());
        assertNotNull(deferredInitializerType);
        assertEquals(FrontendExpressionTypeStatus.DEFERRED, deferredInitializerType.status());

        var unsupportedInitializerType = analyzed.analysisData().expressionTypes().get(unsupportedDeclaration.value());
        assertNotNull(unsupportedInitializerType);
        assertEquals(FrontendExpressionTypeStatus.UNSUPPORTED, unsupportedInitializerType.status());
        assertNull(analyzed.analysisData().expressionTypes().get(unsupportedHead));

        var blockedInitializerType = analyzed.analysisData().expressionTypes().get(blockedDeclaration.value());
        assertNotNull(blockedInitializerType);
        assertEquals(FrontendExpressionTypeStatus.BLOCKED, blockedInitializerType.status());
        assertNull(blockedInitializerType.publishedType());
        assertSame(
                deferredInitializerType,
                assertInitializerProvenanceReachableFromLocalUse(
                        analyzed,
                        deferredUse,
                        deferredDeclaration,
                        FrontendExpressionTypeStatus.DEFERRED
                )
        );
        assertSame(
                unsupportedInitializerType,
                assertInitializerProvenanceReachableFromLocalUse(
                        analyzed,
                        unsupportedUse,
                        unsupportedDeclaration,
                        FrontendExpressionTypeStatus.UNSUPPORTED
                )
        );
        assertSame(
                blockedInitializerType,
                assertInitializerProvenanceReachableFromLocalUse(
                        analyzed,
                        blockedUse,
                        blockedDeclaration,
                        FrontendExpressionTypeStatus.BLOCKED
                )
        );

        assertEquals(GdVariantType.VARIANT, bodyScope.resolveValue("deferred_value").type());
        assertEquals(GdVariantType.VARIANT, bodyScope.resolveValue("unsupported_value").type());
        assertEquals(GdVariantType.VARIANT, bodyScope.resolveValue("blocked_value").type());

        var deferredUseType = analyzed.analysisData().expressionTypes().get(deferredUse);
        assertNotNull(deferredUseType);
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, deferredUseType.status());
        assertEquals(GdVariantType.VARIANT, deferredUseType.publishedType());

        var unsupportedUseType = analyzed.analysisData().expressionTypes().get(unsupportedUse);
        assertNotNull(unsupportedUseType);
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, unsupportedUseType.status());
        assertEquals(GdVariantType.VARIANT, unsupportedUseType.publishedType());

        var blockedUseType = analyzed.analysisData().expressionTypes().get(blockedUse);
        assertNotNull(blockedUseType);
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, blockedUseType.status());
        assertEquals(GdVariantType.VARIANT, blockedUseType.publishedType());
    }

    @Test
    void analyzeDoesNotRetypeEarlierLocalWhenDuplicateInferredDeclarationWasRejected() throws Exception {
        var analyzed = analyze(
                "expr_type_duplicate_inferred_local.gd",
                """
                        class_name ExprTypeDuplicateInferredLocal
                        extends RefCounted
                        
                        func ping():
                            var value := 1
                            var value := "oops"
                            value
                        """
        );

        var pingFunction = findFunction(analyzed.ast(), "ping");
        var bodyScope = assertInstanceOf(BlockScope.class, analyzed.analysisData().scopesByAst().get(pingFunction.body()));
        var firstDeclaration = assertInstanceOf(VariableDeclaration.class, pingFunction.body().statements().getFirst());
        var duplicateDeclaration = assertInstanceOf(VariableDeclaration.class, pingFunction.body().statements().get(1));
        var valueUse = assertInstanceOf(
                IdentifierExpression.class,
                assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().get(2)).expression()
        );

        var firstInitializerType = analyzed.analysisData().expressionTypes().get(firstDeclaration.value());
        assertNotNull(firstInitializerType);
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, firstInitializerType.status());
        assertEquals("int", firstInitializerType.publishedType().getTypeName());

        var duplicateInitializerType = analyzed.analysisData().expressionTypes().get(duplicateDeclaration.value());
        assertNotNull(duplicateInitializerType);
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, duplicateInitializerType.status());
        assertEquals("String", duplicateInitializerType.publishedType().getTypeName());

        assertEquals("int", bodyScope.resolveValue("value").type().getTypeName());

        var valueUseType = analyzed.analysisData().expressionTypes().get(valueUse);
        assertNotNull(valueUseType);
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, valueUseType.status());
        assertEquals("int", valueUseType.publishedType().getTypeName());

        assertTrue(
                analyzed.analysisData().diagnostics().asList().stream()
                        .anyMatch(diagnostic -> diagnostic.category().equals("sema.variable_binding")
                                && diagnostic.message().contains("Duplicate local variable 'value'"))
        );
    }

    @Test
    void analyzeKeepsOuterLocalTypeWhenConflictingInnerInferredDeclarationWasRejected() throws Exception {
        var analyzed = analyze(
                "expr_type_conflicting_inferred_local.gd",
                """
                        class_name ExprTypeConflictingInferredLocal
                        extends RefCounted
                        
                        func ping():
                            var value := 1
                            if value > 0:
                                var value := 2
                            value
                        """
        );

        var pingFunction = findFunction(analyzed.ast(), "ping");
        var bodyScope = assertInstanceOf(BlockScope.class, analyzed.analysisData().scopesByAst().get(pingFunction.body()));
        var outerDeclaration = assertInstanceOf(VariableDeclaration.class, pingFunction.body().statements().getFirst());
        var valueUse = assertInstanceOf(
                IdentifierExpression.class,
                assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().get(2)).expression()
        );

        var outerInitializerType = analyzed.analysisData().expressionTypes().get(outerDeclaration.value());
        assertNotNull(outerInitializerType);
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, outerInitializerType.status());
        assertEquals("int", outerInitializerType.publishedType().getTypeName());

        assertEquals("int", bodyScope.resolveValue("value").type().getTypeName());

        var valueUseType = analyzed.analysisData().expressionTypes().get(valueUse);
        assertNotNull(valueUseType);
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, valueUseType.status());
        assertEquals("int", valueUseType.publishedType().getTypeName());

        assertTrue(
                analyzed.analysisData().diagnostics().asList().stream()
                        .anyMatch(diagnostic -> diagnostic.category().equals("sema.variable_binding")
                                && diagnostic.message().contains("shadows outer local 'value'"))
        );
    }

    private static @NotNull AnalyzedScript analyze(
            @NotNull String fileName,
            @NotNull String source
    ) throws Exception {
        var diagnostics = new DiagnosticManager();
        var parserService = new GdScriptParserService();
        var unit = parserService.parseUnit(Path.of("tmp", fileName), source, diagnostics);
        var analysisData = new FrontendSemanticAnalyzer().analyze(
                "test_module",
                List.of(unit),
                new ClassRegistry(ExtensionApiLoader.loadDefault()),
                diagnostics
        );
        return new AnalyzedScript(unit.ast(), analysisData);
    }

    private static @NotNull FunctionDeclaration findFunction(@NotNull Node root, @NotNull String name) {
        return findNode(root, FunctionDeclaration.class, function -> function.name().equals(name));
    }

    private static @NotNull VariableDeclaration findVariable(@NotNull List<?> statements, @NotNull String name) {
        return statements.stream()
                .filter(VariableDeclaration.class::isInstance)
                .map(VariableDeclaration.class::cast)
                .filter(variable -> variable.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Variable not found: " + name));
    }

    private static @NotNull FrontendExpressionType assertInitializerProvenanceReachableFromLocalUse(
            @NotNull AnalyzedScript analyzed,
            @NotNull IdentifierExpression localUse,
            @NotNull VariableDeclaration expectedDeclaration,
            @NotNull FrontendExpressionTypeStatus expectedInitializerStatus
    ) {
        var localBinding = analyzed.analysisData().symbolBindings().get(localUse);
        assertNotNull(localBinding);
        assertEquals(FrontendBindingKind.LOCAL_VAR, localBinding.kind());
        var declarationSite = assertInstanceOf(VariableDeclaration.class, localBinding.declarationSite());
        assertSame(expectedDeclaration, declarationSite);
        var initializer = declarationSite.value();
        assertNotNull(initializer);
        var initializerType = analyzed.analysisData().expressionTypes().get(initializer);
        assertNotNull(initializerType);
        assertEquals(expectedInitializerStatus, initializerType.status());
        return initializerType;
    }

    private static @NotNull List<dev.superice.gdcc.frontend.diagnostic.FrontendDiagnostic> diagnosticsByCategory(
            @NotNull AnalyzedScript analyzed,
            @NotNull String category
    ) {
        return analyzed.analysisData().diagnostics().asList().stream()
                .filter(diagnostic -> diagnostic.category().equals(category))
                .toList();
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

    private record AnalyzedScript(
            @NotNull Node ast,
            @NotNull dev.superice.gdcc.frontend.sema.FrontendAnalysisData analysisData
    ) {
    }
}
