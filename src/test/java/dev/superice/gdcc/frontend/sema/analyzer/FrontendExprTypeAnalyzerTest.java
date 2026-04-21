package dev.superice.gdcc.frontend.sema.analyzer;

import dev.superice.gdcc.frontend.diagnostic.DiagnosticManager;
import dev.superice.gdcc.frontend.diagnostic.FrontendDiagnosticSeverity;
import dev.superice.gdcc.frontend.parse.FrontendModule;
import dev.superice.gdcc.frontend.parse.GdScriptParserService;
import dev.superice.gdcc.frontend.scope.BlockScope;
import dev.superice.gdcc.frontend.sema.FrontendBindingKind;
import dev.superice.gdcc.frontend.sema.FrontendExpressionType;
import dev.superice.gdcc.frontend.sema.FrontendExpressionTypeStatus;
import dev.superice.gdcc.gdextension.ExtensionAPI;
import dev.superice.gdcc.gdextension.ExtensionApiLoader;
import dev.superice.gdcc.gdextension.ExtensionBuiltinClass;
import dev.superice.gdcc.gdextension.ExtensionGdClass;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdparser.frontend.ast.AttributeCallStep;
import dev.superice.gdparser.frontend.ast.AttributeExpression;
import dev.superice.gdparser.frontend.ast.AttributePropertyStep;
import dev.superice.gdparser.frontend.ast.AttributeSubscriptStep;
import dev.superice.gdparser.frontend.ast.AssignmentExpression;
import dev.superice.gdparser.frontend.ast.CallExpression;
import dev.superice.gdparser.frontend.ast.ExpressionStatement;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.LiteralExpression;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.SelfExpression;
import dev.superice.gdparser.frontend.ast.SubscriptExpression;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import dev.superice.gdcc.type.GdCallableType;
import dev.superice.gdcc.type.GdVoidType;
import dev.superice.gdcc.type.GdVariantType;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    void analyzePublishesMappedTopLevelStaticRouteTypesViaCallerSideRemap() throws Exception {
        var analyzed = analyze(
                "expr_type_mapped_static_route.gd",
                """
                        class_name MappedWorker
                        extends RefCounted
                        
                        static func build(seed) -> String:
                            return ""
                        
                        func ping(seed):
                            MappedWorker.build(seed)
                        """,
                new ClassRegistry(ExtensionApiLoader.loadDefault()),
                Map.of("MappedWorker", "RuntimeWorker")
        );

        var pingFunction = findFunction(analyzed.ast(), "ping");
        var buildStatement = assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().getFirst());
        var buildExpression = assertInstanceOf(AttributeExpression.class, buildStatement.expression());
        var buildType = analyzed.analysisData().expressionTypes().get(buildExpression);

        assertNotNull(buildType);
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, buildType.status());
        assertEquals("String", buildType.publishedType().getTypeName());
        assertTrue(diagnosticsByCategory(analyzed, "sema.expression_resolution").isEmpty());
    }

    @Test
    void analyzePublishesSupportedPropertyInitializerExpressionTypesWithoutOpeningClassConstInitializers() throws Exception {
        var analyzed = analyze(
                "expr_type_property_initializers.gd",
                """
                        class_name ExprTypePropertyInitializers
                        extends RefCounted
                        
                        var payload: int = 1
                        
                        class Handle:
                            func read() -> int:
                                return 1
                        
                        class Worker:
                            var handle: Handle
                        
                            static func build() -> Worker:
                                return null
                        
                        var ready_value := Worker.build().handle.read()
                        const Alias = Worker.build()
                        """
        );

        var payloadDeclaration = findNode(
                analyzed.ast(),
                VariableDeclaration.class,
                declaration -> declaration.name().equals("payload")
        );
        var readyValueDeclaration = findNode(
                analyzed.ast(),
                VariableDeclaration.class,
                declaration -> declaration.name().equals("ready_value")
        );
        var aliasDeclaration = findNode(
                analyzed.ast(),
                VariableDeclaration.class,
                declaration -> declaration.name().equals("Alias")
        );
        var readyInitializer = assertInstanceOf(AttributeExpression.class, readyValueDeclaration.value());
        var workerHead = findNode(readyInitializer, IdentifierExpression.class, identifier -> identifier.name().equals("Worker"));

        var payloadInitializerType = analyzed.analysisData().expressionTypes().get(payloadDeclaration.value());
        assertNotNull(payloadInitializerType);
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, payloadInitializerType.status());
        assertEquals("int", payloadInitializerType.publishedType().getTypeName());

        var readyValueType = analyzed.analysisData().expressionTypes().get(readyInitializer);
        assertNotNull(readyValueType);
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, readyValueType.status());
        assertEquals("int", readyValueType.publishedType().getTypeName());

        assertNull(analyzed.analysisData().expressionTypes().get(workerHead));
        assertNull(analyzed.analysisData().expressionTypes().get(aliasDeclaration.value()));
        assertTrue(diagnosticsByCategory(analyzed, "sema.expression_resolution").isEmpty());
        assertTrue(diagnosticsByCategory(analyzed, "sema.unsupported_expression_route").isEmpty());
        assertTrue(diagnosticsByCategory(analyzed, "sema.discarded_expression").isEmpty());
    }

    @Test
    void analyzePropagatesBlockedPropertyInitializerHeadsWithoutExprOwnedDuplicateErrors() throws Exception {
        var analyzed = analyze(
                "expr_type_blocked_property_initializer.gd",
                """
                        class_name ExprTypeBlockedPropertyInitializer
                        extends RefCounted
                        
                        signal changed
                        var payload: int = 1
                        
                        func read() -> int:
                            return 1
                        
                        var blocked_value := payload
                        var blocked_signal := changed
                        var blocked_call := read()
                        static var blocked_chain := self.read()
                        """
        );

        var blockedValueDeclaration = findNode(
                analyzed.ast(),
                VariableDeclaration.class,
                declaration -> declaration.name().equals("blocked_value")
        );
        var blockedSignalDeclaration = findNode(
                analyzed.ast(),
                VariableDeclaration.class,
                declaration -> declaration.name().equals("blocked_signal")
        );
        var blockedCallDeclaration = findNode(
                analyzed.ast(),
                VariableDeclaration.class,
                declaration -> declaration.name().equals("blocked_call")
        );
        var blockedChainDeclaration = findNode(
                analyzed.ast(),
                VariableDeclaration.class,
                declaration -> declaration.name().equals("blocked_chain")
        );
        var blockedValueIdentifier = assertInstanceOf(IdentifierExpression.class, blockedValueDeclaration.value());
        var blockedSignalIdentifier = assertInstanceOf(IdentifierExpression.class, blockedSignalDeclaration.value());
        var blockedCall = assertInstanceOf(CallExpression.class, blockedCallDeclaration.value());
        var blockedChain = assertInstanceOf(AttributeExpression.class, blockedChainDeclaration.value());

        var blockedValueType = analyzed.analysisData().expressionTypes().get(blockedValueIdentifier);
        assertNotNull(blockedValueType);
        assertEquals(FrontendExpressionTypeStatus.BLOCKED, blockedValueType.status());
        assertTrue(blockedValueType.detailReason().contains("payload"));

        var blockedSignalType = analyzed.analysisData().expressionTypes().get(blockedSignalIdentifier);
        assertNotNull(blockedSignalType);
        assertEquals(FrontendExpressionTypeStatus.BLOCKED, blockedSignalType.status());
        assertTrue(blockedSignalType.detailReason().contains("changed"));

        var blockedCallType = analyzed.analysisData().expressionTypes().get(blockedCall);
        assertNotNull(blockedCallType);
        assertEquals(FrontendExpressionTypeStatus.BLOCKED, blockedCallType.status());
        assertTrue(blockedCallType.detailReason().contains("read"));

        var blockedChainType = analyzed.analysisData().expressionTypes().get(blockedChain);
        assertNotNull(blockedChainType);
        assertEquals(FrontendExpressionTypeStatus.BLOCKED, blockedChainType.status());
        assertNotNull(blockedChainType.detailReason());
        assertTrue(blockedChainType.detailReason().contains("self"));

        assertEquals(4, diagnosticsByCategory(analyzed, "sema.unsupported_binding_subtree").size());
        assertTrue(diagnosticsByCategory(analyzed, "sema.binding").isEmpty());
        assertTrue(diagnosticsByCategory(analyzed, "sema.expression_resolution").isEmpty());
        assertTrue(diagnosticsByCategory(analyzed, "sema.unsupported_expression_route").isEmpty());
    }

    @Test
    void analyzePropagatesUnsupportedSameClassTypeMetaPropertyInitializerRoutesWithoutExprOwnedDuplicateErrors()
            throws Exception {
        var analyzed = analyze(
                "expr_type_same_class_type_meta_property_initializer.gd",
                """
                        class_name ExprTypeSameClassTypeMetaPropertyInitializer
                        extends RefCounted
                        
                        signal changed
                        var payload: int = 1
                        
                        func read() -> int:
                            return 1
                        
                        static var blocked_value := ExprTypeSameClassTypeMetaPropertyInitializer.payload
                        static var blocked_signal := ExprTypeSameClassTypeMetaPropertyInitializer.changed
                        static var blocked_call := ExprTypeSameClassTypeMetaPropertyInitializer.read()
                        """
        );

        var blockedValueDeclaration = findNode(
                analyzed.ast(),
                VariableDeclaration.class,
                declaration -> declaration.name().equals("blocked_value")
        );
        var blockedSignalDeclaration = findNode(
                analyzed.ast(),
                VariableDeclaration.class,
                declaration -> declaration.name().equals("blocked_signal")
        );
        var blockedCallDeclaration = findNode(
                analyzed.ast(),
                VariableDeclaration.class,
                declaration -> declaration.name().equals("blocked_call")
        );
        var blockedValue = assertInstanceOf(AttributeExpression.class, blockedValueDeclaration.value());
        var blockedSignal = assertInstanceOf(AttributeExpression.class, blockedSignalDeclaration.value());
        var blockedCall = assertInstanceOf(AttributeExpression.class, blockedCallDeclaration.value());

        var blockedValueType = analyzed.analysisData().expressionTypes().get(blockedValue);
        assertNotNull(blockedValueType);
        assertEquals(FrontendExpressionTypeStatus.UNSUPPORTED, blockedValueType.status());
        assertTrue(blockedValueType.detailReason().contains("ExprTypeSameClassTypeMetaPropertyInitializer.payload"));

        var blockedSignalType = analyzed.analysisData().expressionTypes().get(blockedSignal);
        assertNotNull(blockedSignalType);
        assertEquals(FrontendExpressionTypeStatus.UNSUPPORTED, blockedSignalType.status());
        assertTrue(blockedSignalType.detailReason().contains("ExprTypeSameClassTypeMetaPropertyInitializer.changed"));

        var blockedCallType = analyzed.analysisData().expressionTypes().get(blockedCall);
        assertNotNull(blockedCallType);
        assertEquals(FrontendExpressionTypeStatus.UNSUPPORTED, blockedCallType.status());
        assertTrue(blockedCallType.detailReason().contains("ExprTypeSameClassTypeMetaPropertyInitializer.read"));

        assertEquals(3, diagnosticsByCategory(analyzed, "sema.unsupported_chain_route").size());
        assertTrue(diagnosticsByCategory(analyzed, "sema.expression_resolution").isEmpty());
        assertTrue(diagnosticsByCategory(analyzed, "sema.unsupported_expression_route").isEmpty());
    }

    @Test
    void analyzePropagatesUnsupportedInheritedTypeMetaPropertyInitializerRoutesWithoutExprOwnedDuplicateErrors()
            throws Exception {
        var analyzed = analyze(
                "expr_type_inherited_type_meta_property_initializer.gd",
                """
                        class_name ExprTypeInheritedTypeMetaPropertyInitializer
                        extends PropertyInitializerBase
                        
                        static var blocked_value := PropertyInitializerBase.payload
                        static var blocked_signal := PropertyInitializerBase.changed
                        static var blocked_call := PropertyInitializerBase.read()
                        static var allowed_helper := PropertyInitializerBase.helper()
                        """,
                FrontendAnalyzerTestRegistrySupport.registryWithInheritedPropertyInitializerBase()
        );

        var blockedValueDeclaration = findNode(
                analyzed.ast(),
                VariableDeclaration.class,
                declaration -> declaration.name().equals("blocked_value")
        );
        var blockedSignalDeclaration = findNode(
                analyzed.ast(),
                VariableDeclaration.class,
                declaration -> declaration.name().equals("blocked_signal")
        );
        var blockedCallDeclaration = findNode(
                analyzed.ast(),
                VariableDeclaration.class,
                declaration -> declaration.name().equals("blocked_call")
        );
        var allowedHelperDeclaration = findNode(
                analyzed.ast(),
                VariableDeclaration.class,
                declaration -> declaration.name().equals("allowed_helper")
        );
        var blockedValue = assertInstanceOf(AttributeExpression.class, blockedValueDeclaration.value());
        var blockedSignal = assertInstanceOf(AttributeExpression.class, blockedSignalDeclaration.value());
        var blockedCall = assertInstanceOf(AttributeExpression.class, blockedCallDeclaration.value());
        var allowedHelper = assertInstanceOf(AttributeExpression.class, allowedHelperDeclaration.value());

        var blockedValueType = analyzed.analysisData().expressionTypes().get(blockedValue);
        assertNotNull(blockedValueType);
        assertEquals(FrontendExpressionTypeStatus.UNSUPPORTED, blockedValueType.status());
        assertTrue(blockedValueType.detailReason().contains("PropertyInitializerBase.payload"));

        var blockedSignalType = analyzed.analysisData().expressionTypes().get(blockedSignal);
        assertNotNull(blockedSignalType);
        assertEquals(FrontendExpressionTypeStatus.UNSUPPORTED, blockedSignalType.status());
        assertTrue(blockedSignalType.detailReason().contains("PropertyInitializerBase.changed"));

        var blockedCallType = analyzed.analysisData().expressionTypes().get(blockedCall);
        assertNotNull(blockedCallType);
        assertEquals(FrontendExpressionTypeStatus.UNSUPPORTED, blockedCallType.status());
        assertTrue(blockedCallType.detailReason().contains("PropertyInitializerBase.read"));

        var allowedHelperType = analyzed.analysisData().expressionTypes().get(allowedHelper);
        assertNotNull(allowedHelperType);
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, allowedHelperType.status());
        assertEquals("int", allowedHelperType.publishedType().getTypeName());

        assertEquals(3, diagnosticsByCategory(analyzed, "sema.unsupported_chain_route").size());
        assertTrue(diagnosticsByCategory(analyzed, "sema.expression_resolution").isEmpty());
        assertTrue(diagnosticsByCategory(analyzed, "sema.unsupported_expression_route").isEmpty());
    }

    @Test
    void analyzePropagatesUnsupportedSameClassSuffixPropertyInitializerRoutesWithoutExprOwnedDuplicateErrors()
            throws Exception {
        var analyzed = analyze(
                "expr_type_same_class_suffix_property_initializer.gd",
                """
                        class_name ExprTypeSameClassSuffixPropertyInitializer
                        extends RefCounted
                        
                        signal changed
                        var payload: int = 1
                        
                        func read() -> int:
                            return 1
                        
                        static func build() -> ExprTypeSameClassSuffixPropertyInitializer:
                            return null
                        
                        var blocked_value := build().payload
                        var blocked_call := build().read()
                        var blocked_signal := build().changed
                        """
        );

        var blockedValueDeclaration = findNode(
                analyzed.ast(),
                VariableDeclaration.class,
                declaration -> declaration.name().equals("blocked_value")
        );
        var blockedCallDeclaration = findNode(
                analyzed.ast(),
                VariableDeclaration.class,
                declaration -> declaration.name().equals("blocked_call")
        );
        var blockedSignalDeclaration = findNode(
                analyzed.ast(),
                VariableDeclaration.class,
                declaration -> declaration.name().equals("blocked_signal")
        );
        var blockedValue = assertInstanceOf(AttributeExpression.class, blockedValueDeclaration.value());
        var blockedCall = assertInstanceOf(AttributeExpression.class, blockedCallDeclaration.value());
        var blockedSignal = assertInstanceOf(AttributeExpression.class, blockedSignalDeclaration.value());

        var blockedValueType = analyzed.analysisData().expressionTypes().get(blockedValue);
        assertNotNull(blockedValueType);
        assertEquals(FrontendExpressionTypeStatus.UNSUPPORTED, blockedValueType.status());
        assertTrue(blockedValueType.detailReason().contains("payload"));

        var blockedCallType = analyzed.analysisData().expressionTypes().get(blockedCall);
        assertNotNull(blockedCallType);
        assertEquals(FrontendExpressionTypeStatus.UNSUPPORTED, blockedCallType.status());
        assertTrue(blockedCallType.detailReason().contains("read"));

        var blockedSignalType = analyzed.analysisData().expressionTypes().get(blockedSignal);
        assertNotNull(blockedSignalType);
        assertEquals(FrontendExpressionTypeStatus.UNSUPPORTED, blockedSignalType.status());
        assertTrue(blockedSignalType.detailReason().contains("changed"));

        assertEquals(3, diagnosticsByCategory(analyzed, "sema.unsupported_chain_route").size());
        assertTrue(diagnosticsByCategory(analyzed, "sema.member_resolution").isEmpty());
        assertTrue(diagnosticsByCategory(analyzed, "sema.call_resolution").isEmpty());
        assertTrue(diagnosticsByCategory(analyzed, "sema.expression_resolution").isEmpty());
        assertTrue(diagnosticsByCategory(analyzed, "sema.unsupported_expression_route").isEmpty());
    }

    @Test
    void analyzePropagatesUnsupportedInheritedSuffixPropertyInitializerRoutesWithoutExprOwnedDuplicateErrors()
            throws Exception {
        var analyzed = analyze(
                "expr_type_inherited_suffix_property_initializer.gd",
                """
                        class_name ExprTypeInheritedSuffixPropertyInitializer
                        extends PropertyInitializerBase
                        
                        static func build_base() -> PropertyInitializerBase:
                            return null
                        
                        var blocked_value := build_base().payload
                        var blocked_call := build_base().read()
                        var blocked_signal := build_base().changed
                        var allowed_helper := PropertyInitializerBase.helper()
                        """,
                FrontendAnalyzerTestRegistrySupport.registryWithInheritedPropertyInitializerBase()
        );

        var blockedValueDeclaration = findNode(
                analyzed.ast(),
                VariableDeclaration.class,
                declaration -> declaration.name().equals("blocked_value")
        );
        var blockedCallDeclaration = findNode(
                analyzed.ast(),
                VariableDeclaration.class,
                declaration -> declaration.name().equals("blocked_call")
        );
        var blockedSignalDeclaration = findNode(
                analyzed.ast(),
                VariableDeclaration.class,
                declaration -> declaration.name().equals("blocked_signal")
        );
        var allowedHelperDeclaration = findNode(
                analyzed.ast(),
                VariableDeclaration.class,
                declaration -> declaration.name().equals("allowed_helper")
        );
        var blockedValue = assertInstanceOf(AttributeExpression.class, blockedValueDeclaration.value());
        var blockedCall = assertInstanceOf(AttributeExpression.class, blockedCallDeclaration.value());
        var blockedSignal = assertInstanceOf(AttributeExpression.class, blockedSignalDeclaration.value());
        var allowedHelper = assertInstanceOf(AttributeExpression.class, allowedHelperDeclaration.value());

        var blockedValueType = analyzed.analysisData().expressionTypes().get(blockedValue);
        assertNotNull(blockedValueType);
        assertEquals(FrontendExpressionTypeStatus.UNSUPPORTED, blockedValueType.status());
        assertTrue(blockedValueType.detailReason().contains("payload"));

        var blockedCallType = analyzed.analysisData().expressionTypes().get(blockedCall);
        assertNotNull(blockedCallType);
        assertEquals(FrontendExpressionTypeStatus.UNSUPPORTED, blockedCallType.status());
        assertTrue(blockedCallType.detailReason().contains("read"));

        var blockedSignalType = analyzed.analysisData().expressionTypes().get(blockedSignal);
        assertNotNull(blockedSignalType);
        assertEquals(FrontendExpressionTypeStatus.UNSUPPORTED, blockedSignalType.status());
        assertTrue(blockedSignalType.detailReason().contains("changed"));

        var allowedHelperType = analyzed.analysisData().expressionTypes().get(allowedHelper);
        assertNotNull(allowedHelperType);
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, allowedHelperType.status());
        assertEquals("int", allowedHelperType.publishedType().getTypeName());

        assertEquals(3, diagnosticsByCategory(analyzed, "sema.unsupported_chain_route").size());
        assertTrue(diagnosticsByCategory(analyzed, "sema.member_resolution").isEmpty());
        assertTrue(diagnosticsByCategory(analyzed, "sema.call_resolution").isEmpty());
        assertTrue(diagnosticsByCategory(analyzed, "sema.expression_resolution").isEmpty());
        assertTrue(diagnosticsByCategory(analyzed, "sema.unsupported_expression_route").isEmpty());
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
        assertEquals("ExprTypeStaticRouteHeads__sub__Worker", constructorType.publishedType().getTypeName());

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
    void analyzePublishesBuiltinInstancePropertyTypesAndKeepsMissingMemberAsFailure() throws Exception {
        var analyzed = analyze(
                "expr_type_builtin_instance_properties.gd",
                """
                        class_name ExprTypeBuiltinInstanceProperties
                        extends RefCounted
                        
                        func ping(vector: Vector3):
                            vector.x
                            Color(1.0, 0.5, 0.25, 1.0).r
                            Basis.IDENTITY.x
                            vector.missing
                        """
        );

        var pingFunction = findFunction(analyzed.ast(), "ping");
        var vectorStatement = assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().getFirst());
        var colorStatement = assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().get(1));
        var basisStatement = assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().get(2));
        var missingStatement = assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().get(3));

        var vectorExpression = assertInstanceOf(AttributeExpression.class, vectorStatement.expression());
        var colorExpression = assertInstanceOf(AttributeExpression.class, colorStatement.expression());
        var basisExpression = assertInstanceOf(AttributeExpression.class, basisStatement.expression());
        var missingExpression = assertInstanceOf(AttributeExpression.class, missingStatement.expression());

        var vectorType = analyzed.analysisData().expressionTypes().get(vectorExpression);
        assertNotNull(vectorType);
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, vectorType.status());
        assertNotNull(vectorType.publishedType());
        assertEquals("float", vectorType.publishedType().getTypeName());

        var colorType = analyzed.analysisData().expressionTypes().get(colorExpression);
        assertNotNull(colorType);
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, colorType.status());
        assertNotNull(colorType.publishedType());
        assertEquals("float", colorType.publishedType().getTypeName());

        var basisType = analyzed.analysisData().expressionTypes().get(basisExpression);
        assertNotNull(basisType);
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, basisType.status());
        assertNotNull(basisType.publishedType());
        assertEquals("Vector3", basisType.publishedType().getTypeName());

        var missingType = analyzed.analysisData().expressionTypes().get(missingExpression);
        assertNotNull(missingType);
        assertEquals(FrontendExpressionTypeStatus.FAILED, missingType.status());
        assertTrue(missingType.detailReason().contains("missing"));
        assertTrue(missingType.detailReason().contains("Vector3"));

        assertEquals(1, diagnosticsByCategory(analyzed, "sema.member_resolution").size());
        assertTrue(diagnosticsByCategory(analyzed, "sema.unsupported_chain_route").isEmpty());
        assertTrue(diagnosticsByCategory(analyzed, "sema.expression_resolution").isEmpty());
    }

    @Test
    void analyzePublishesBuiltinMemberPropertyAssignmentTypesAndKeepsFailuresStrict() throws Exception {
        var analyzed = analyze(
                "expr_type_builtin_member_property_assignment.gd",
                """
                        class_name ExprTypeBuiltinMemberPropertyAssignment
                        extends RefCounted
                        
                        func ping(vector: Vector3, color: Color):
                            vector.x = 1.0
                            color.a = 0.5
                            vector.x = ""
                            vector.missing = 1.0
                        """
        );

        var assignments = findNodes(findFunction(analyzed.ast(), "ping"), AssignmentExpression.class, _ -> true);
        for (var successIndex : List.of(0, 1)) {
            var assignmentType = analyzed.analysisData().expressionTypes().get(assignments.get(successIndex));
            assertNotNull(assignmentType);
            assertEquals(FrontendExpressionTypeStatus.RESOLVED, assignmentType.status());
            assertEquals(GdVoidType.VOID, assignmentType.publishedType());
        }

        var typeMismatch = analyzed.analysisData().expressionTypes().get(assignments.get(2));
        assertNotNull(typeMismatch);
        assertEquals(FrontendExpressionTypeStatus.FAILED, typeMismatch.status());
        assertTrue(typeMismatch.detailReason().contains("not assignable"));
        assertTrue(typeMismatch.detailReason().contains("float"));

        var missingMember = analyzed.analysisData().expressionTypes().get(assignments.get(3));
        assertNotNull(missingMember);
        assertEquals(FrontendExpressionTypeStatus.FAILED, missingMember.status());
        assertTrue(missingMember.detailReason().contains("missing"));
        assertTrue(missingMember.detailReason().contains("Vector3"));

        assertEquals(1, diagnosticsByCategory(analyzed, "sema.expression_resolution").size());
        assertEquals(1, diagnosticsByCategory(analyzed, "sema.member_resolution").size());
        assertTrue(diagnosticsByCategory(analyzed, "sema.deferred_expression_resolution").isEmpty());
        assertTrue(diagnosticsByCategory(analyzed, "sema.unsupported_expression_route").isEmpty());
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
    void analyzeResolvesCallableBindAndSubscriptRoutesAndRejectsDirectCallableInvocationVariants() throws Exception {
        var analyzed = analyze(
                "expr_type_callable_bind_routes.gd",
                """
                        class_name ExprTypeCallableBindRoutes
                        extends RefCounted
                        
                        class Worker:
                            static func build(value: int) -> int:
                                return value
                        
                        func helper(value: int) -> int:
                            return value
                        
                        func consume(cb: Callable) -> Callable:
                            return cb
                        
                        func ping(items: Array[Callable], dict: Dictionary[String, Callable]):
                            helper.bind(1)
                            self.helper.bind(1)
                            Worker.build.bind(1)
                            items[0].bind(1)
                            dict["cb"].call()
                            consume(items[0])
                            helper.bind(1)()
                            self.helper.bind(1)()
                        """
        );

        var pingFunction = findFunction(analyzed.ast(), "ping");
        var statements = pingFunction.body().statements();
        var bareBind = assertInstanceOf(AttributeExpression.class, assertInstanceOf(ExpressionStatement.class, statements.get(0)).expression());
        var selfBind = assertInstanceOf(AttributeExpression.class, assertInstanceOf(ExpressionStatement.class, statements.get(1)).expression());
        var staticBind = assertInstanceOf(AttributeExpression.class, assertInstanceOf(ExpressionStatement.class, statements.get(2)).expression());
        var subscriptBind = assertInstanceOf(AttributeExpression.class, assertInstanceOf(ExpressionStatement.class, statements.get(3)).expression());
        var callableCall = assertInstanceOf(AttributeExpression.class, assertInstanceOf(ExpressionStatement.class, statements.get(4)).expression());
        var consumeCall = assertInstanceOf(CallExpression.class, assertInstanceOf(ExpressionStatement.class, statements.get(5)).expression());
        var bareBindInvoke = assertInstanceOf(CallExpression.class, assertInstanceOf(ExpressionStatement.class, statements.get(6)).expression());
        var selfBindInvoke = assertInstanceOf(CallExpression.class, assertInstanceOf(ExpressionStatement.class, statements.get(7)).expression());

        for (var callableExpression : List.of(bareBind, selfBind, staticBind, subscriptBind, consumeCall)) {
            var expressionType = analyzed.analysisData().expressionTypes().get(callableExpression);
            assertNotNull(expressionType);
            assertEquals(FrontendExpressionTypeStatus.RESOLVED, expressionType.status());
            assertInstanceOf(GdCallableType.class, expressionType.publishedType());
        }

        var callableCallType = analyzed.analysisData().expressionTypes().get(callableCall);
        assertNotNull(callableCallType);
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, callableCallType.status());
        assertEquals(GdVariantType.VARIANT, callableCallType.publishedType());

        for (var unsupportedInvoke : List.of(bareBindInvoke, selfBindInvoke)) {
            var expressionType = analyzed.analysisData().expressionTypes().get(unsupportedInvoke);
            assertNotNull(expressionType);
            assertEquals(FrontendExpressionTypeStatus.UNSUPPORTED, expressionType.status());
            assertTrue(expressionType.detailReason().contains("Direct invocation of callable values"));
        }

        var unsupportedDiagnostics = diagnosticsByCategory(analyzed, "sema.unsupported_expression_route");
        assertEquals(2, unsupportedDiagnostics.size());
        assertTrue(unsupportedDiagnostics.stream().allMatch(diagnostic ->
                diagnostic.severity() == FrontendDiagnosticSeverity.ERROR
        ));
        assertTrue(unsupportedDiagnostics.stream().allMatch(
                diagnostic -> diagnostic.message().contains("Direct invocation of callable values")
        ));
        assertTrue(diagnosticsByCategory(analyzed, "sema.deferred_expression_resolution").isEmpty());
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
    void analyzeResolvesUtilityCallWithoutReturnMetadataAsVoid() throws Exception {
        var analyzed = analyze(
                "expr_type_void_utility_call.gd",
                """
                        class_name ExprTypeVoidUtilityCall
                        extends RefCounted
                        
                        func ping(value):
                            print(value)
                        """
        );

        var pingFunction = findFunction(analyzed.ast(), "ping");
        var printCall = assertInstanceOf(
                CallExpression.class,
                assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().getFirst()).expression()
        );

        var printCallType = analyzed.analysisData().expressionTypes().get(printCall);
        assertNotNull(printCallType);
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, printCallType.status());
        assertEquals(GdVoidType.VOID, printCallType.publishedType());

        assertTrue(diagnosticsByCategory(analyzed, "sema.expression_resolution").isEmpty());
        assertTrue(diagnosticsByCategory(analyzed, "sema.call_resolution").isEmpty());
        assertTrue(diagnosticsByCategory(analyzed, "sema.discarded_expression").isEmpty());
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
    void analyzeReportsExprOwnedDeferredDiagnosticsForRemainingGenericMvpGaps() throws Exception {
        var analyzed = analyze(
                "expr_type_deferred_gaps.gd",
                """
                        class_name ExprTypeDeferredGaps
                        extends RefCounted
                        
                        func ping(flag):
                            1 if flag else 2
                            [1, 2]
                        """
        );

        var pingFunction = findFunction(analyzed.ast(), "ping");
        var deferredRoots = List.of(
                assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().get(0)).expression(),
                assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().get(1)).expression()
        );
        for (var deferredRoot : deferredRoots) {
            assertEquals(
                    FrontendExpressionTypeStatus.DEFERRED,
                    analyzed.analysisData().expressionTypes().get(deferredRoot).status()
            );
        }

        var deferredDiagnostics = diagnosticsByCategory(analyzed, "sema.deferred_expression_resolution");
        assertEquals(2, deferredDiagnostics.size());
        assertTrue(deferredDiagnostics.stream().anyMatch(diagnostic -> diagnostic.message().contains(
                "Conditional expression typing is deferred"
        )));
        assertTrue(deferredDiagnostics.stream().anyMatch(diagnostic -> diagnostic.message().contains(
                "Array literal typing is deferred"
        )));
        assertTrue(deferredDiagnostics.stream().noneMatch(diagnostic -> diagnostic.message().contains("milestone-G")));
    }

    @Test
    void analyzePublishesUnaryExpressionTypesWithoutDeferredDiagnostics() throws Exception {
        var analyzed = analyze(
                "expr_type_unary_semantics.gd",
                """
                        class_name ExprTypeUnarySemantics
                        extends RefCounted
                        
                        func ping(items: Array[int], dynamic_value, typed_variant: Variant):
                            var neg: int = -1
                            var pos: int = +1
                            var bit: int = ~1
                            var logic_true: bool = !true
                            var logic_array: bool = not items
                            var dynamic_result := -dynamic_value
                            var dynamic_variant_result := not typed_variant
                            var invalid: int = ~"hello"
                        """
        );

        var pingFunction = findFunction(analyzed.ast(), "ping");
        var statements = pingFunction.body().statements();
        assertEquals(
                FrontendExpressionTypeStatus.RESOLVED,
                analyzed.analysisData().expressionTypes().get(findVariable(statements, "neg").value()).status()
        );
        assertEquals(
                FrontendExpressionTypeStatus.RESOLVED,
                analyzed.analysisData().expressionTypes().get(findVariable(statements, "pos").value()).status()
        );
        assertEquals(
                FrontendExpressionTypeStatus.RESOLVED,
                analyzed.analysisData().expressionTypes().get(findVariable(statements, "bit").value()).status()
        );
        assertEquals(
                FrontendExpressionTypeStatus.RESOLVED,
                analyzed.analysisData().expressionTypes().get(findVariable(statements, "logic_true").value()).status()
        );
        var typedArrayNot = analyzed.analysisData().expressionTypes().get(findVariable(statements, "logic_array").value());
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, typedArrayNot.status());
        assertEquals("bool", typedArrayNot.publishedType().getTypeName());

        var dynamicResult = analyzed.analysisData().expressionTypes().get(findVariable(statements, "dynamic_result").value());
        assertEquals(FrontendExpressionTypeStatus.DYNAMIC, dynamicResult.status());
        assertEquals(GdVariantType.VARIANT, dynamicResult.publishedType());

        var dynamicVariantResult = analyzed.analysisData().expressionTypes().get(
                findVariable(statements, "dynamic_variant_result").value()
        );
        assertEquals(FrontendExpressionTypeStatus.DYNAMIC, dynamicVariantResult.status());
        assertEquals(GdVariantType.VARIANT, dynamicVariantResult.publishedType());

        var invalidResult = analyzed.analysisData().expressionTypes().get(findVariable(statements, "invalid").value());
        assertEquals(FrontendExpressionTypeStatus.FAILED, invalidResult.status());

        var expressionDiagnostics = diagnosticsByCategory(analyzed, "sema.expression_resolution");
        assertEquals(1, expressionDiagnostics.size());
        assertTrue(expressionDiagnostics.getFirst().message().contains(
                "Unary operator '~' is not defined for operand type 'String'"
        ));
        assertTrue(diagnosticsByCategory(analyzed, "sema.deferred_expression_resolution").isEmpty());
        assertTrue(diagnosticsByCategory(analyzed, "sema.unsupported_expression_route").isEmpty());
        assertTrue(diagnosticsByCategory(analyzed, "sema.discarded_expression").isEmpty());
    }

    @Test
    void analyzePublishesBinaryExpressionTypesWithoutDeferredDiagnostics() throws Exception {
        var analyzed = analyze(
                "expr_type_binary_semantics.gd",
                """
                        class_name ExprTypeBinarySemantics
                        extends RefCounted
                        
                        func ping(
                            ints_a: Array[int],
                            ints_b: Array[int],
                            names: Array[String],
                            raw_array: Array,
                            dynamic_value,
                            typed_variant: Variant
                        ):
                            var add: int = 1 + 2
                            var compare: bool = 1 == 2
                            var membership: bool = 1 in ints_a
                            var dynamic_sum := dynamic_value + 1
                            var dynamic_variant_sum := typed_variant + 1
                            var logic_and: bool = dynamic_value and 0
                            var logic_or: bool = typed_variant or 0
                            var preserved := ints_a + ints_b
                            var widened := ints_a + names
                            var raw_widened := ints_a + raw_array
                            var invalid: int = "hello" & 1
                            var unsupported = 1 not in ints_a
                        """
        );

        var statements = findFunction(analyzed.ast(), "ping").body().statements();
        assertEquals(
                FrontendExpressionTypeStatus.RESOLVED,
                analyzed.analysisData().expressionTypes().get(findVariable(statements, "add").value()).status()
        );
        assertEquals(
                FrontendExpressionTypeStatus.RESOLVED,
                analyzed.analysisData().expressionTypes().get(findVariable(statements, "compare").value()).status()
        );
        assertEquals(
                FrontendExpressionTypeStatus.RESOLVED,
                analyzed.analysisData().expressionTypes().get(findVariable(statements, "membership").value()).status()
        );

        var dynamicSum = analyzed.analysisData().expressionTypes().get(findVariable(statements, "dynamic_sum").value());
        assertEquals(FrontendExpressionTypeStatus.DYNAMIC, dynamicSum.status());
        assertEquals(GdVariantType.VARIANT, dynamicSum.publishedType());

        var dynamicVariantSum = analyzed.analysisData().expressionTypes().get(
                findVariable(statements, "dynamic_variant_sum").value()
        );
        assertEquals(FrontendExpressionTypeStatus.DYNAMIC, dynamicVariantSum.status());
        assertEquals(GdVariantType.VARIANT, dynamicVariantSum.publishedType());

        var logicAnd = analyzed.analysisData().expressionTypes().get(findVariable(statements, "logic_and").value());
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, logicAnd.status());
        assertEquals("bool", logicAnd.publishedType().getTypeName());

        var logicOr = analyzed.analysisData().expressionTypes().get(findVariable(statements, "logic_or").value());
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, logicOr.status());
        assertEquals("bool", logicOr.publishedType().getTypeName());

        var preserved = analyzed.analysisData().expressionTypes().get(findVariable(statements, "preserved").value());
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, preserved.status());
        assertEquals("Array[int]", preserved.publishedType().getTypeName());

        var widened = analyzed.analysisData().expressionTypes().get(findVariable(statements, "widened").value());
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, widened.status());
        assertEquals("Array", widened.publishedType().getTypeName());

        var rawWidened = analyzed.analysisData().expressionTypes().get(findVariable(statements, "raw_widened").value());
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, rawWidened.status());
        assertEquals("Array", rawWidened.publishedType().getTypeName());

        var invalid = analyzed.analysisData().expressionTypes().get(findVariable(statements, "invalid").value());
        assertEquals(FrontendExpressionTypeStatus.FAILED, invalid.status());

        var unsupported = analyzed.analysisData().expressionTypes().get(findVariable(statements, "unsupported").value());
        assertEquals(FrontendExpressionTypeStatus.UNSUPPORTED, unsupported.status());
        assertTrue(unsupported.detailReason().contains("must not be silently normalized to 'in'"));

        var expressionDiagnostics = diagnosticsByCategory(analyzed, "sema.expression_resolution");
        assertEquals(1, expressionDiagnostics.size());
        assertTrue(expressionDiagnostics.getFirst().message().contains(
                "Binary operator '&' is not defined for operand types 'String' and 'int'"
        ));

        var unsupportedDiagnostics = diagnosticsByCategory(analyzed, "sema.unsupported_expression_route");
        assertEquals(1, unsupportedDiagnostics.size());
        assertTrue(unsupportedDiagnostics.getFirst().message().contains("not in"));

        assertTrue(diagnosticsByCategory(analyzed, "sema.deferred_expression_resolution").isEmpty());
        assertTrue(diagnosticsByCategory(analyzed, "sema.discarded_expression").isEmpty());
    }

    @Test
    void analyzePublishesResolvedVoidForStatementAssignmentsWithoutDiscardedWarnings() throws Exception {
        var analyzed = analyze(
                "expr_type_assignment_success.gd",
                """
                        class_name ExprTypeAssignmentSuccess
                        extends RefCounted
                        
                        var hp: int = 0
                        
                        class Holder:
                            var values: Array[int]
                        
                        func ping(values: Array[int], holder: Holder):
                            hp = 1
                            self.hp = 2
                            values[0] = 3
                            holder.values[0] = 4
                        """
        );

        var pingFunction = findFunction(analyzed.ast(), "ping");
        var assignments = List.of(
                assertInstanceOf(
                        AssignmentExpression.class,
                        assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().get(0)).expression()
                ),
                assertInstanceOf(
                        AssignmentExpression.class,
                        assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().get(1)).expression()
                ),
                assertInstanceOf(
                        AssignmentExpression.class,
                        assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().get(2)).expression()
                ),
                assertInstanceOf(
                        AssignmentExpression.class,
                        assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().get(3)).expression()
                )
        );

        for (var assignment : assignments) {
            var assignmentType = analyzed.analysisData().expressionTypes().get(assignment);
            assertNotNull(assignmentType);
            assertEquals(FrontendExpressionTypeStatus.RESOLVED, assignmentType.status());
            assertEquals(GdVoidType.VOID, assignmentType.publishedType());
        }

        assertTrue(diagnosticsByCategory(analyzed, "sema.expression_resolution").isEmpty());
        assertTrue(diagnosticsByCategory(analyzed, "sema.deferred_expression_resolution").isEmpty());
        assertTrue(diagnosticsByCategory(analyzed, "sema.unsupported_expression_route").isEmpty());
        assertTrue(diagnosticsByCategory(analyzed, "sema.discarded_expression").isEmpty());
    }

    @Test
    void analyzeRejectsIllegalTargetsAndAssignabilityFailures() throws Exception {
        var analyzed = analyze(
                "expr_type_assignment_failures.gd",
                """
                        class_name ExprTypeAssignmentFailures
                        extends RefCounted
                        
                        signal pinged
                        
                        class Worker:
                            static func build() -> int:
                                return 1
                        
                        func helper() -> int:
                            return 1
                        
                        func ping(values: Array[int]):
                            self.pinged = 1
                            Worker = 1
                            helper = 1
                            values[0] = ""
                        """
        );

        var pingFunction = findFunction(analyzed.ast(), "ping");
        var signalAssignment = assertInstanceOf(
                AssignmentExpression.class,
                assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().get(0)).expression()
        );
        var typeMetaAssignment = assertInstanceOf(
                AssignmentExpression.class,
                assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().get(1)).expression()
        );
        var callableAssignment = assertInstanceOf(
                AssignmentExpression.class,
                assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().get(2)).expression()
        );
        var assignabilityFailure = assertInstanceOf(
                AssignmentExpression.class,
                assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().get(3)).expression()
        );

        var signalAssignmentType = analyzed.analysisData().expressionTypes().get(signalAssignment);
        assertNotNull(signalAssignmentType);
        assertEquals(FrontendExpressionTypeStatus.FAILED, signalAssignmentType.status());
        assertTrue(signalAssignmentType.detailReason().contains("Signal"));

        var typeMetaAssignmentType = analyzed.analysisData().expressionTypes().get(typeMetaAssignment);
        assertNotNull(typeMetaAssignmentType);
        assertEquals(FrontendExpressionTypeStatus.FAILED, typeMetaAssignmentType.status());
        assertTrue(typeMetaAssignmentType.detailReason().contains("Type-meta"));

        var callableAssignmentType = analyzed.analysisData().expressionTypes().get(callableAssignment);
        assertNotNull(callableAssignmentType);
        assertEquals(FrontendExpressionTypeStatus.FAILED, callableAssignmentType.status());
        assertTrue(callableAssignmentType.detailReason().contains("cannot be assigned"));

        var assignabilityFailureType = analyzed.analysisData().expressionTypes().get(assignabilityFailure);
        assertNotNull(assignabilityFailureType);
        assertEquals(FrontendExpressionTypeStatus.FAILED, assignabilityFailureType.status());
        assertTrue(assignabilityFailureType.detailReason().contains("not assignable"));

        var expressionDiagnostics = diagnosticsByCategory(analyzed, "sema.expression_resolution");
        assertEquals(4, expressionDiagnostics.size());
        assertTrue(diagnosticsByCategory(analyzed, "sema.deferred_expression_resolution").isEmpty());
        assertTrue(diagnosticsByCategory(analyzed, "sema.unsupported_expression_route").isEmpty());
        assertTrue(diagnosticsByCategory(analyzed, "sema.discarded_expression").isEmpty());
    }

    @Test
    void analyzeRejectsReadonlyPropertyWritesThroughBareAndAttributeRoutes() throws Exception {
        var analyzed = analyze(
                "expr_type_assignment_readonly_property.gd",
                """
                        class_name ExprTypeAssignmentReadonlyProperty
                        extends ReadonlyBase
                        
                        func ping():
                            locked = 1
                            self.locked = 1
                        """,
                registryWithReadonlyEngineBase()
        );

        var pingFunction = findFunction(analyzed.ast(), "ping");
        var bareAssignment = assertInstanceOf(
                AssignmentExpression.class,
                assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().get(0)).expression()
        );
        var attributeAssignment = assertInstanceOf(
                AssignmentExpression.class,
                assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().get(1)).expression()
        );

        var bareAssignmentType = analyzed.analysisData().expressionTypes().get(bareAssignment);
        assertNotNull(bareAssignmentType);
        assertEquals(FrontendExpressionTypeStatus.FAILED, bareAssignmentType.status());
        assertEquals("Property 'locked' is not writable", bareAssignmentType.detailReason());

        var attributeAssignmentType = analyzed.analysisData().expressionTypes().get(attributeAssignment);
        assertNotNull(attributeAssignmentType);
        assertEquals(FrontendExpressionTypeStatus.FAILED, attributeAssignmentType.status());
        assertEquals("Property 'locked' is not writable", attributeAssignmentType.detailReason());

        var expressionDiagnostics = diagnosticsByCategory(analyzed, "sema.expression_resolution");
        assertEquals(2, expressionDiagnostics.size());
        assertTrue(expressionDiagnostics.stream().allMatch(diagnostic -> diagnostic.message().contains("not writable")));
        assertTrue(diagnosticsByCategory(analyzed, "sema.deferred_expression_resolution").isEmpty());
        assertTrue(diagnosticsByCategory(analyzed, "sema.unsupported_expression_route").isEmpty());
        assertTrue(diagnosticsByCategory(analyzed, "sema.discarded_expression").isEmpty());
    }

    @Test
    void analyzeAcceptsVariantAndDynamicAssignmentTargetsButKeepsImplicitConversionsStrict() throws Exception {
        var analyzed = analyze(
                "expr_type_assignment_variant_dynamic.gd",
                """
                        class_name ExprTypeAssignmentVariantDynamic
                        extends RefCounted
                        
                        var field
                        
                        func ping(dynamic_value):
                            var payload
                            var ratio: float = 0.0
                            payload = 1
                            field = 2
                            self.field = 3
                            dynamic_value[0] = 4
                            ratio = 1
                        """
        );

        var assignments = findNodes(findFunction(analyzed.ast(), "ping"), AssignmentExpression.class, _ -> true);
        for (var successIndex : List.of(0, 1, 2, 3)) {
            var assignmentType = analyzed.analysisData().expressionTypes().get(assignments.get(successIndex));
            assertNotNull(assignmentType);
            assertEquals(FrontendExpressionTypeStatus.RESOLVED, assignmentType.status());
            assertEquals(GdVoidType.VOID, assignmentType.publishedType());
        }

        var strictImplicitConversionFailure = analyzed.analysisData().expressionTypes().get(assignments.get(4));
        assertNotNull(strictImplicitConversionFailure);
        assertEquals(FrontendExpressionTypeStatus.FAILED, strictImplicitConversionFailure.status());
        assertTrue(strictImplicitConversionFailure.detailReason().contains("not assignable"));
        assertTrue(strictImplicitConversionFailure.detailReason().contains("float"));

        var expressionDiagnostics = diagnosticsByCategory(analyzed, "sema.expression_resolution");
        assertEquals(1, expressionDiagnostics.size());
        assertTrue(expressionDiagnostics.getFirst().message().contains("not assignable"));
        assertTrue(diagnosticsByCategory(analyzed, "sema.deferred_expression_resolution").isEmpty());
        assertTrue(diagnosticsByCategory(analyzed, "sema.unsupported_expression_route").isEmpty());
        assertTrue(diagnosticsByCategory(analyzed, "sema.discarded_expression").isEmpty());
    }

    @Test
    void analyzePublishesResolvedVoidForSupportedCompoundAssignmentStatementsWithoutDiscardedWarnings()
            throws Exception {
        var analyzed = analyze(
                "expr_type_assignment_compound.gd",
                """
                        class_name ExprTypeAssignmentCompound
                        extends RefCounted
                        
                        var hp: int = 0
                        var payload
                        
                        func ping():
                            hp += 1
                            payload += 1
                        """
        );

        var pingFunction = findFunction(analyzed.ast(), "ping");
        var assignments = findNodes(pingFunction, AssignmentExpression.class, _ -> true);
        for (var compoundAssignment : assignments) {
            var compoundAssignmentType = analyzed.analysisData().expressionTypes().get(compoundAssignment);
            assertNotNull(compoundAssignmentType);
            assertEquals(FrontendExpressionTypeStatus.RESOLVED, compoundAssignmentType.status());
            assertEquals(GdVoidType.VOID, compoundAssignmentType.publishedType());
        }

        assertTrue(diagnosticsByCategory(analyzed, "sema.unsupported_expression_route").isEmpty());
        assertTrue(diagnosticsByCategory(analyzed, "sema.expression_resolution").isEmpty());
        assertTrue(diagnosticsByCategory(analyzed, "sema.deferred_expression_resolution").isEmpty());
        assertTrue(diagnosticsByCategory(analyzed, "sema.discarded_expression").isEmpty());
    }

    @Test
    void analyzeReportsExpressionResolutionForCompoundAssignmentWritebackMismatch() throws Exception {
        var analyzed = analyze(
                "expr_type_assignment_compound_writeback_mismatch.gd",
                """
                        class_name ExprTypeAssignmentCompoundWritebackMismatch
                        extends RefCounted
                        
                        func ping(values: Array[int], raw_array: Array):
                            values += raw_array
                        """
        );

        var pingFunction = findFunction(analyzed.ast(), "ping");
        var compoundAssignment = assertInstanceOf(
                AssignmentExpression.class,
                assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().getFirst()).expression()
        );

        var compoundAssignmentType = analyzed.analysisData().expressionTypes().get(compoundAssignment);
        assertNotNull(compoundAssignmentType);
        assertEquals(FrontendExpressionTypeStatus.FAILED, compoundAssignmentType.status());
        assertTrue(compoundAssignmentType.detailReason().contains("not assignable"));
        assertTrue(compoundAssignmentType.detailReason().contains("Array[int]"));

        var expressionDiagnostics = diagnosticsByCategory(analyzed, "sema.expression_resolution");
        assertEquals(1, expressionDiagnostics.size());
        assertEquals(FrontendDiagnosticSeverity.ERROR, expressionDiagnostics.getFirst().severity());
        assertTrue(expressionDiagnostics.getFirst().message().contains("not assignable"));
        assertTrue(diagnosticsByCategory(analyzed, "sema.unsupported_expression_route").isEmpty());
        assertTrue(diagnosticsByCategory(analyzed, "sema.deferred_expression_resolution").isEmpty());
        assertTrue(diagnosticsByCategory(analyzed, "sema.discarded_expression").isEmpty());
    }

    @Test
    void analyzePublishesAttributeStepTypesForCallPropertyAndSubscriptChains() throws Exception {
        var analyzed = analyze(
                "expr_type_attribute_step_publication.gd",
                """
                        class_name ExprTypeAttributeStepPublication
                        extends RefCounted
                        
                        var payloads: Dictionary[int, ExprTypeAttributeStepPublication]
                        var text: String
                        var value: int
                        
                        func fetch(seed: int) -> ExprTypeAttributeStepPublication:
                            return self
                        
                        func ping(seed: int, dynamic_host):
                            self.fetch(seed).value
                            self.payloads[seed].value
                            dynamic_host.payloads[seed]
                            self.payloads["bad"]
                            self.text[0]
                        """,
                registryWithKeyedStringBuiltin()
        );

        var pingFunction = findFunction(analyzed.ast(), "ping");
        var statements = pingFunction.body().statements();
        var callChain = assertInstanceOf(
                AttributeExpression.class,
                assertInstanceOf(ExpressionStatement.class, statements.get(0)).expression()
        );
        var resolvedSubscriptChain = assertInstanceOf(
                AttributeExpression.class,
                assertInstanceOf(ExpressionStatement.class, statements.get(1)).expression()
        );
        var dynamicSubscriptChain = assertInstanceOf(
                AttributeExpression.class,
                assertInstanceOf(ExpressionStatement.class, statements.get(2)).expression()
        );
        var failedSubscriptChain = assertInstanceOf(
                AttributeExpression.class,
                assertInstanceOf(ExpressionStatement.class, statements.get(3)).expression()
        );
        var unsupportedSubscriptChain = assertInstanceOf(
                AttributeExpression.class,
                assertInstanceOf(ExpressionStatement.class, statements.get(4)).expression()
        );

        var fetchStep = assertInstanceOf(AttributeCallStep.class, callChain.steps().getFirst());
        var fetchedValueStep = assertInstanceOf(AttributePropertyStep.class, callChain.steps().get(1));
        var resolvedSubscriptStep = assertInstanceOf(AttributeSubscriptStep.class, resolvedSubscriptChain.steps().getFirst());
        var resolvedValueStep = assertInstanceOf(AttributePropertyStep.class, resolvedSubscriptChain.steps().get(1));
        var dynamicSubscriptStep = assertInstanceOf(AttributeSubscriptStep.class, dynamicSubscriptChain.steps().getFirst());
        var failedSubscriptStep = assertInstanceOf(AttributeSubscriptStep.class, failedSubscriptChain.steps().getFirst());
        var unsupportedSubscriptStep = assertInstanceOf(
                AttributeSubscriptStep.class,
                unsupportedSubscriptChain.steps().getFirst()
        );

        var fetchStepType = analyzed.analysisData().expressionTypes().get(fetchStep);
        assertNotNull(fetchStepType);
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, fetchStepType.status());
        assertEquals("ExprTypeAttributeStepPublication", fetchStepType.publishedType().getTypeName());

        var fetchedValueStepType = analyzed.analysisData().expressionTypes().get(fetchedValueStep);
        assertNotNull(fetchedValueStepType);
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, fetchedValueStepType.status());
        assertEquals("int", fetchedValueStepType.publishedType().getTypeName());

        var resolvedSubscriptStepType = analyzed.analysisData().expressionTypes().get(resolvedSubscriptStep);
        assertNotNull(resolvedSubscriptStepType);
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, resolvedSubscriptStepType.status());
        assertEquals("ExprTypeAttributeStepPublication", resolvedSubscriptStepType.publishedType().getTypeName());

        var resolvedValueStepType = analyzed.analysisData().expressionTypes().get(resolvedValueStep);
        assertNotNull(resolvedValueStepType);
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, resolvedValueStepType.status());
        assertEquals("int", resolvedValueStepType.publishedType().getTypeName());

        var dynamicSubscriptStepType = analyzed.analysisData().expressionTypes().get(dynamicSubscriptStep);
        assertNotNull(dynamicSubscriptStepType);
        assertEquals(FrontendExpressionTypeStatus.DYNAMIC, dynamicSubscriptStepType.status());
        assertEquals(GdVariantType.VARIANT, dynamicSubscriptStepType.publishedType());

        var failedSubscriptStepType = analyzed.analysisData().expressionTypes().get(failedSubscriptStep);
        assertNotNull(failedSubscriptStepType);
        assertEquals(FrontendExpressionTypeStatus.FAILED, failedSubscriptStepType.status());
        assertTrue(failedSubscriptStepType.detailReason().contains("not assignable"));

        var unsupportedSubscriptStepType = analyzed.analysisData().expressionTypes().get(unsupportedSubscriptStep);
        assertNotNull(unsupportedSubscriptStepType);
        assertEquals(FrontendExpressionTypeStatus.UNSUPPORTED, unsupportedSubscriptStepType.status());
        assertTrue(unsupportedSubscriptStepType.detailReason().contains("keyed access metadata"));
    }

    @Test
    void analyzePublishesTypedSubscriptRoutesAndExprOwnedDiagnostics() throws Exception {
        var analyzed = analyze(
                "expr_type_subscript_routes.gd",
                """
                        class_name ExprTypeSubscriptRoutes
                        extends RefCounted
                        
                        func ping(
                                items: Array[int],
                                lookup: Dictionary[String, int],
                                packed: PackedInt32Array,
                                text: String,
                                value: int,
                                dynamic_value
                        ):
                            items[0]
                            lookup["hp"]
                            packed[0]
                            dynamic_value[0]
                            items["bad"]
                            value[0]
                            text[0]
                        """,
                registryWithKeyedStringBuiltin()
        );

        var pingFunction = findFunction(analyzed.ast(), "ping");
        var statements = pingFunction.body().statements();
        var itemsSubscript = assertInstanceOf(
                SubscriptExpression.class,
                assertInstanceOf(ExpressionStatement.class, statements.get(0)).expression()
        );
        var lookupSubscript = assertInstanceOf(
                SubscriptExpression.class,
                assertInstanceOf(ExpressionStatement.class, statements.get(1)).expression()
        );
        var packedSubscript = assertInstanceOf(
                SubscriptExpression.class,
                assertInstanceOf(ExpressionStatement.class, statements.get(2)).expression()
        );
        var dynamicSubscript = assertInstanceOf(
                SubscriptExpression.class,
                assertInstanceOf(ExpressionStatement.class, statements.get(3)).expression()
        );
        var badKeySubscript = assertInstanceOf(
                SubscriptExpression.class,
                assertInstanceOf(ExpressionStatement.class, statements.get(4)).expression()
        );
        var badReceiverSubscript = assertInstanceOf(
                SubscriptExpression.class,
                assertInstanceOf(ExpressionStatement.class, statements.get(5)).expression()
        );
        var unsupportedKeyedSubscript = assertInstanceOf(
                SubscriptExpression.class,
                assertInstanceOf(ExpressionStatement.class, statements.get(6)).expression()
        );

        var itemsType = analyzed.analysisData().expressionTypes().get(itemsSubscript);
        assertNotNull(itemsType);
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, itemsType.status());
        assertEquals("int", itemsType.publishedType().getTypeName());

        var lookupType = analyzed.analysisData().expressionTypes().get(lookupSubscript);
        assertNotNull(lookupType);
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, lookupType.status());
        assertEquals("int", lookupType.publishedType().getTypeName());

        var packedType = analyzed.analysisData().expressionTypes().get(packedSubscript);
        assertNotNull(packedType);
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, packedType.status());
        assertEquals("int", packedType.publishedType().getTypeName());

        var dynamicType = analyzed.analysisData().expressionTypes().get(dynamicSubscript);
        assertNotNull(dynamicType);
        assertEquals(FrontendExpressionTypeStatus.DYNAMIC, dynamicType.status());
        assertEquals(GdVariantType.VARIANT, dynamicType.publishedType());

        var badKeyType = analyzed.analysisData().expressionTypes().get(badKeySubscript);
        assertNotNull(badKeyType);
        assertEquals(FrontendExpressionTypeStatus.FAILED, badKeyType.status());
        assertTrue(badKeyType.detailReason().contains("not assignable"));

        var badReceiverType = analyzed.analysisData().expressionTypes().get(badReceiverSubscript);
        assertNotNull(badReceiverType);
        assertEquals(FrontendExpressionTypeStatus.FAILED, badReceiverType.status());
        assertTrue(badReceiverType.detailReason().contains("does not support"));

        var unsupportedKeyedType = analyzed.analysisData().expressionTypes().get(unsupportedKeyedSubscript);
        assertNotNull(unsupportedKeyedType);
        assertEquals(FrontendExpressionTypeStatus.UNSUPPORTED, unsupportedKeyedType.status());
        assertTrue(unsupportedKeyedType.detailReason().contains("keyed access metadata"));

        var expressionDiagnostics = diagnosticsByCategory(analyzed, "sema.expression_resolution");
        assertEquals(2, expressionDiagnostics.size());
        assertTrue(expressionDiagnostics.stream().anyMatch(diagnostic -> diagnostic.message().contains("not assignable")));
        assertTrue(expressionDiagnostics.stream().anyMatch(diagnostic -> diagnostic.message().contains("does not support")));

        var unsupportedDiagnostics = diagnosticsByCategory(analyzed, "sema.unsupported_expression_route");
        assertEquals(1, unsupportedDiagnostics.size());
        assertEquals(FrontendDiagnosticSeverity.ERROR, unsupportedDiagnostics.getFirst().severity());
        assertTrue(unsupportedDiagnostics.getFirst().message().contains("keyed access metadata"));

        assertTrue(diagnosticsByCategory(analyzed, "sema.deferred_expression_resolution").isEmpty());
        assertEquals(3, diagnosticsByCategory(analyzed, "sema.discarded_expression").size());
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
        assertEquals(FrontendDiagnosticSeverity.ERROR, unsupportedDiagnostics.getFirst().severity());
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
        assertEquals("int", blockedReadType.publishedType().getTypeName());

        var blockedCallType = analyzed.analysisData().expressionTypes().get(blockedCall);
        assertNotNull(blockedCallType);
        assertEquals(FrontendExpressionTypeStatus.BLOCKED, blockedCallType.status());
        assertNull(blockedCallType.publishedType());
        assertNotNull(blockedCallType.detailReason());
    }

    @Test
    void analyzePublishesBlockedBareCallWithResolvedReturnType() throws Exception {
        var analyzed = analyze(
                "expr_type_blocked_bare_call.gd",
                """
                        class_name ExprTypeBlockedBareCall
                        extends RefCounted
                        
                        func helper(value: int) -> int:
                            return value
                        
                        static func ping_static(value: int):
                            helper(value)
                        """
        );

        var pingStaticFunction = findFunction(analyzed.ast(), "ping_static");
        var blockedCall = assertInstanceOf(
                CallExpression.class,
                assertInstanceOf(ExpressionStatement.class, pingStaticFunction.body().statements().getFirst()).expression()
        );

        var blockedCallType = analyzed.analysisData().expressionTypes().get(blockedCall);
        assertNotNull(blockedCallType);
        assertEquals(FrontendExpressionTypeStatus.BLOCKED, blockedCallType.status());
        assertNotNull(blockedCallType.publishedType());
        assertEquals("int", blockedCallType.publishedType().getTypeName());
        assertTrue(blockedCallType.detailReason().contains("not accessible"));
        assertTrue(diagnosticsByCategory(analyzed, "sema.discarded_expression").isEmpty());
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
        assertEquals(FrontendExpressionTypeStatus.UNSUPPORTED, deferredInitializerType.status());

        var unsupportedInitializerType = analyzed.analysisData().expressionTypes().get(unsupportedDeclaration.value());
        assertNotNull(unsupportedInitializerType);
        assertEquals(FrontendExpressionTypeStatus.UNSUPPORTED, unsupportedInitializerType.status());
        assertNull(analyzed.analysisData().expressionTypes().get(unsupportedHead));

        var blockedInitializerType = analyzed.analysisData().expressionTypes().get(blockedDeclaration.value());
        assertNotNull(blockedInitializerType);
        assertEquals(FrontendExpressionTypeStatus.BLOCKED, blockedInitializerType.status());
        assertEquals("int", blockedInitializerType.publishedType().getTypeName());
        assertSame(
                deferredInitializerType,
                assertInitializerProvenanceReachableFromLocalUse(
                        analyzed,
                        deferredUse,
                        deferredDeclaration,
                        FrontendExpressionTypeStatus.UNSUPPORTED
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
        return analyze(fileName, source, new ClassRegistry(ExtensionApiLoader.loadDefault()));
    }

    private static @NotNull AnalyzedScript analyze(
            @NotNull String fileName,
            @NotNull String source,
            @NotNull ClassRegistry registry
    ) throws Exception {
        return analyze(fileName, source, registry, Map.of());
    }

    private static @NotNull AnalyzedScript analyze(
            @NotNull String fileName,
            @NotNull String source,
            @NotNull ClassRegistry registry,
            @NotNull Map<String, String> topLevelCanonicalNameMap
    ) throws Exception {
        var diagnostics = new DiagnosticManager();
        var parserService = new GdScriptParserService();
        var unit = parserService.parseUnit(Path.of("tmp", fileName), source, diagnostics);
        var analysisData = new FrontendSemanticAnalyzer().analyze(
                new FrontendModule("test_module", List.of(unit), topLevelCanonicalNameMap),
                registry,
                diagnostics
        );
        return new AnalyzedScript(unit.ast(), analysisData);
    }

    private static @NotNull ClassRegistry registryWithKeyedStringBuiltin() throws Exception {
        var api = ExtensionApiLoader.loadDefault();
        var patchedBuiltins = api.builtinClasses().stream()
                .map(FrontendExprTypeAnalyzerTest::withKeyedStringBuiltin)
                .toList();
        return new ClassRegistry(new ExtensionAPI(
                api.header(),
                api.builtinClassSizes(),
                api.builtinClassMemberOffsets(),
                api.globalEnums(),
                api.utilityFunctions(),
                patchedBuiltins,
                api.classes(),
                api.singletons(),
                api.nativeStructures()
        ));
    }

    private static @NotNull ClassRegistry registryWithReadonlyEngineBase() throws Exception {
        var api = ExtensionApiLoader.loadDefault();
        var classes = new ArrayList<>(api.classes());
        classes.add(new ExtensionGdClass(
                "ReadonlyBase",
                false,
                true,
                "RefCounted",
                "core",
                List.of(),
                List.of(),
                List.of(),
                List.of(new ExtensionGdClass.PropertyInfo("locked", "int", true, false, "0")),
                List.of()
        ));
        return new ClassRegistry(new ExtensionAPI(
                api.header(),
                api.builtinClassSizes(),
                api.builtinClassMemberOffsets(),
                api.globalEnums(),
                api.utilityFunctions(),
                api.builtinClasses(),
                List.copyOf(classes),
                api.singletons(),
                api.nativeStructures()
        ));
    }

    private static @NotNull ExtensionBuiltinClass withKeyedStringBuiltin(@NotNull ExtensionBuiltinClass builtinClass) {
        if (!builtinClass.name().equals("String")) {
            return builtinClass;
        }
        return new ExtensionBuiltinClass(
                builtinClass.name(),
                true,
                builtinClass.operators(),
                builtinClass.methods(),
                builtinClass.enums(),
                builtinClass.constructors(),
                builtinClass.members(),
                builtinClass.constants()
        );
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
