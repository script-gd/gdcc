package dev.superice.gdcc.frontend.sema.analyzer;

import dev.superice.gdcc.frontend.diagnostic.DiagnosticManager;
import dev.superice.gdcc.frontend.diagnostic.FrontendDiagnostic;
import dev.superice.gdcc.frontend.diagnostic.FrontendDiagnosticSeverity;
import dev.superice.gdcc.frontend.parse.FrontendModule;
import dev.superice.gdcc.frontend.parse.FrontendSourceUnit;
import dev.superice.gdcc.frontend.parse.GdScriptParserService;
import dev.superice.gdcc.frontend.sema.FrontendAnalysisData;
import dev.superice.gdcc.frontend.sema.FrontendBindingKind;
import dev.superice.gdcc.frontend.sema.FrontendCallResolutionKind;
import dev.superice.gdcc.frontend.sema.FrontendCallResolutionStatus;
import dev.superice.gdcc.frontend.sema.FrontendExpressionTypeStatus;
import dev.superice.gdcc.frontend.sema.FrontendMemberResolutionStatus;
import dev.superice.gdcc.frontend.sema.FrontendReceiverKind;
import dev.superice.gdcc.gdextension.ExtensionAPI;
import dev.superice.gdcc.gdextension.ExtensionApiLoader;
import dev.superice.gdcc.gdextension.ExtensionBuiltinClass;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.type.GdCallableType;
import dev.superice.gdcc.type.GdVariantType;
import dev.superice.gdparser.frontend.ast.AttributeExpression;
import dev.superice.gdparser.frontend.ast.AttributeCallStep;
import dev.superice.gdparser.frontend.ast.AttributePropertyStep;
import dev.superice.gdparser.frontend.ast.AttributeSubscriptStep;
import dev.superice.gdparser.frontend.ast.CallExpression;
import dev.superice.gdparser.frontend.ast.ExpressionStatement;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontendChainBindingAnalyzerTest {
    @Test
    void analyzePublishesResolvedMemberAndStaticCallFactsForSupportedRoutes() throws Exception {
        var analyzed = analyze(
                "resolved_routes.gd",
                """
                        class_name ResolvedRoutes
                        extends Node
                        
                        var payload: int = 1
                        
                        class Worker:
                            static func build(seed):
                                return ""
                        
                        func ping(seed):
                            self.payload
                            Worker.build(seed)
                        """
        );

        var pingFunction = findFunction(analyzed.unit().ast(), "ping");
        var propertyStatement = assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().getFirst());
        var callStatement = assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().get(1));
        var payloadStep = findNode(propertyStatement, AttributePropertyStep.class, step -> step.name().equals("payload"));
        var buildStep = findNode(callStatement, AttributeCallStep.class, step -> step.name().equals("build"));

        var resolvedPayload = analyzed.analysisData().resolvedMembers().get(payloadStep);
        assertNotNull(resolvedPayload);
        assertEquals(FrontendMemberResolutionStatus.RESOLVED, resolvedPayload.status());
        assertEquals(FrontendBindingKind.PROPERTY, resolvedPayload.bindingKind());
        assertEquals(FrontendReceiverKind.INSTANCE, resolvedPayload.receiverKind());
        var resolvedPayloadType = resolvedPayload.resultType();
        assertNotNull(resolvedPayloadType);
        assertEquals("int", resolvedPayloadType.getTypeName());

        var resolvedBuild = analyzed.analysisData().resolvedCalls().get(buildStep);
        assertNotNull(resolvedBuild);
        assertEquals(FrontendCallResolutionStatus.RESOLVED, resolvedBuild.status());
        assertEquals(FrontendCallResolutionKind.STATIC_METHOD, resolvedBuild.callKind());
        assertEquals(FrontendReceiverKind.TYPE_META, resolvedBuild.receiverKind());
        assertEquals(1, analyzed.analysisData().resolvedMembers().size());
        assertEquals(1, analyzed.analysisData().resolvedCalls().size());
        assertTrue(diagnosticsByCategory(analyzed.analysisData(), "sema.call_resolution").isEmpty());
    }

    @Test
    void analyzePublishesPropertyInitializerChainFactsWithoutOpeningClassConstInitializers() throws Exception {
        var analyzed = analyze(
                "property_initializer_routes.gd",
                """
                        class_name PropertyInitializerRoutes
                        extends RefCounted
                        
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

        var readyValue = findNode(
                analyzed.unit().ast(),
                VariableDeclaration.class,
                declaration -> declaration.name().equals("ready_value")
        );
        var alias = findNode(
                analyzed.unit().ast(),
                VariableDeclaration.class,
                declaration -> declaration.name().equals("Alias")
        );
        var readyInitializer = assertInstanceOf(AttributeExpression.class, readyValue.value());
        var buildStep = findNode(readyInitializer, AttributeCallStep.class, step -> step.name().equals("build"));
        var handleStep = findNode(readyInitializer, AttributePropertyStep.class, step -> step.name().equals("handle"));
        var readStep = findNode(readyInitializer, AttributeCallStep.class, step -> step.name().equals("read"));
        var aliasBuildStep = findNode(alias.value(), AttributeCallStep.class, step -> step.name().equals("build"));

        var resolvedBuild = analyzed.analysisData().resolvedCalls().get(buildStep);
        assertNotNull(resolvedBuild);
        assertEquals(FrontendCallResolutionStatus.RESOLVED, resolvedBuild.status());
        assertEquals(FrontendCallResolutionKind.STATIC_METHOD, resolvedBuild.callKind());
        assertEquals(FrontendReceiverKind.TYPE_META, resolvedBuild.receiverKind());

        var resolvedHandle = analyzed.analysisData().resolvedMembers().get(handleStep);
        assertNotNull(resolvedHandle);
        assertEquals(FrontendMemberResolutionStatus.RESOLVED, resolvedHandle.status());
        assertEquals(FrontendBindingKind.PROPERTY, resolvedHandle.bindingKind());
        assertEquals(FrontendReceiverKind.INSTANCE, resolvedHandle.receiverKind());

        var resolvedRead = analyzed.analysisData().resolvedCalls().get(readStep);
        assertNotNull(resolvedRead);
        assertEquals(FrontendCallResolutionStatus.RESOLVED, resolvedRead.status());
        assertEquals(FrontendCallResolutionKind.INSTANCE_METHOD, resolvedRead.callKind());
        assertEquals(FrontendReceiverKind.INSTANCE, resolvedRead.receiverKind());

        assertTrue(analyzed.analysisData().resolvedCalls().get(aliasBuildStep) == null);
        assertTrue(diagnosticsByCategory(analyzed.analysisData(), "sema.member_resolution").isEmpty());
        assertTrue(diagnosticsByCategory(analyzed.analysisData(), "sema.call_resolution").isEmpty());
        assertTrue(diagnosticsByCategory(analyzed.analysisData(), "sema.unsupported_chain_route").isEmpty());
    }

    @Test
    void analyzePublishesPropertyInitializerCallFailuresWithoutOpeningWholeClassBody() throws Exception {
        var analyzed = analyze(
                "property_initializer_failed_call.gd",
                """
                        class_name PropertyInitializerFailedCall
                        extends RefCounted
                        
                        class Worker:
                            func read() -> int:
                                return 1
                        
                        static var failed_call := Worker.read()
                        """
        );

        var failedCallDeclaration = findNode(
                analyzed.unit().ast(),
                VariableDeclaration.class,
                declaration -> declaration.name().equals("failed_call")
        );
        var readStep = findNode(failedCallDeclaration.value(), AttributeCallStep.class, step -> step.name().equals("read"));

        var failedRead = analyzed.analysisData().resolvedCalls().get(readStep);
        assertNotNull(failedRead);
        assertEquals(FrontendCallResolutionStatus.FAILED, failedRead.status());
        assertEquals(FrontendCallResolutionKind.STATIC_METHOD, failedRead.callKind());
        assertEquals(FrontendReceiverKind.TYPE_META, failedRead.receiverKind());

        var callDiagnostics = diagnosticsByCategory(analyzed.analysisData(), "sema.call_resolution");
        assertEquals(1, callDiagnostics.size());
        assertTrue(callDiagnostics.getFirst().message().contains("Static method lookup for 'read'"));
    }

    @Test
    void analyzeSuppressesDuplicateChainDiagnosticWhenPropertyInitializerHeadIsSealedUpstream() throws Exception {
        var analyzed = analyze(
                "property_initializer_head_owned_by_top_binding.gd",
                """
                        class_name PropertyInitializerHeadOwnedByTopBinding
                        extends RefCounted
                        
                        var payload: int = 1
                        var copy := self.payload
                        """
        );

        var copyDeclaration = findNode(
                analyzed.unit().ast(),
                VariableDeclaration.class,
                declaration -> declaration.name().equals("copy")
        );
        var payloadStep = findNode(copyDeclaration.value(), AttributePropertyStep.class, step -> step.name().equals("payload"));

        assertTrue(analyzed.analysisData().resolvedMembers().get(payloadStep) == null);
        assertEquals(1, diagnosticsByCategory(analyzed.analysisData(), "sema.unsupported_binding_subtree").size());
        assertTrue(diagnosticsByCategory(analyzed.analysisData(), "sema.unsupported_binding_subtree")
                .getFirst()
                .message()
                .contains("self"));
        assertTrue(diagnosticsByCategory(analyzed.analysisData(), "sema.unsupported_chain_route").isEmpty());
        assertTrue(diagnosticsByCategory(analyzed.analysisData(), "sema.member_resolution").isEmpty());
        assertTrue(diagnosticsByCategory(analyzed.analysisData(), "sema.call_resolution").isEmpty());
    }

    @Test
    void analyzeSealsSameClassTypeMetaValueAndMethodRoutesInPropertyInitializerAsUnsupportedRoute() throws Exception {
        var analyzed = analyze(
                "property_initializer_same_class_type_meta_call.gd",
                """
                        class_name PropertyInitializerSameClassTypeMetaCall
                        extends RefCounted
                        
                        signal changed
                        var payload: int = 1
                        
                        func read() -> int:
                            return 1
                        
                        static var blocked_value := PropertyInitializerSameClassTypeMetaCall.payload
                        static var blocked_signal := PropertyInitializerSameClassTypeMetaCall.changed
                        static var blocked_call := PropertyInitializerSameClassTypeMetaCall.read()
                        """
        );

        var blockedValueDeclaration = findNode(
                analyzed.unit().ast(),
                VariableDeclaration.class,
                declaration -> declaration.name().equals("blocked_value")
        );
        var blockedSignalDeclaration = findNode(
                analyzed.unit().ast(),
                VariableDeclaration.class,
                declaration -> declaration.name().equals("blocked_signal")
        );
        var blockedCallDeclaration = findNode(
                analyzed.unit().ast(),
                VariableDeclaration.class,
                declaration -> declaration.name().equals("blocked_call")
        );
        var payloadStep = findNode(blockedValueDeclaration.value(), AttributePropertyStep.class, step -> step.name().equals("payload"));
        var changedStep = findNode(blockedSignalDeclaration.value(), AttributePropertyStep.class, step -> step.name().equals("changed"));
        var readStep = findNode(blockedCallDeclaration.value(), AttributeCallStep.class, step -> step.name().equals("read"));

        var unsupportedValue = analyzed.analysisData().resolvedMembers().get(payloadStep);
        assertNotNull(unsupportedValue);
        assertEquals(FrontendMemberResolutionStatus.UNSUPPORTED, unsupportedValue.status());
        assertEquals(FrontendBindingKind.PROPERTY, unsupportedValue.bindingKind());
        assertEquals(FrontendReceiverKind.TYPE_META, unsupportedValue.receiverKind());

        var unsupportedSignal = analyzed.analysisData().resolvedMembers().get(changedStep);
        assertNotNull(unsupportedSignal);
        assertEquals(FrontendMemberResolutionStatus.UNSUPPORTED, unsupportedSignal.status());
        assertEquals(FrontendBindingKind.SIGNAL, unsupportedSignal.bindingKind());
        assertEquals(FrontendReceiverKind.TYPE_META, unsupportedSignal.receiverKind());

        var unsupportedRead = analyzed.analysisData().resolvedCalls().get(readStep);
        assertNotNull(unsupportedRead);
        assertEquals(FrontendCallResolutionStatus.UNSUPPORTED, unsupportedRead.status());
        assertEquals(FrontendCallResolutionKind.STATIC_METHOD, unsupportedRead.callKind());
        assertEquals(FrontendReceiverKind.TYPE_META, unsupportedRead.receiverKind());

        var unsupportedDiagnostics = diagnosticsByCategory(analyzed.analysisData(), "sema.unsupported_chain_route");
        assertEquals(3, unsupportedDiagnostics.size());
        assertTrue(unsupportedDiagnostics.stream().allMatch(diagnostic ->
                diagnostic.severity() == FrontendDiagnosticSeverity.ERROR
        ));
        assertTrue(unsupportedDiagnostics.stream().anyMatch(diagnostic ->
                diagnostic.message().contains("PropertyInitializerSameClassTypeMetaCall.payload")
        ));
        assertTrue(unsupportedDiagnostics.stream().anyMatch(diagnostic ->
                diagnostic.message().contains("PropertyInitializerSameClassTypeMetaCall.changed")
        ));
        assertTrue(unsupportedDiagnostics.stream().anyMatch(diagnostic ->
                diagnostic.message().contains("PropertyInitializerSameClassTypeMetaCall.read")
        ));
        assertTrue(diagnosticsByCategory(analyzed.analysisData(), "sema.member_resolution").isEmpty());
        assertTrue(diagnosticsByCategory(analyzed.analysisData(), "sema.call_resolution").isEmpty());
    }

    @Test
    void analyzeSealsInheritedTypeMetaValueAndMethodRoutesInPropertyInitializerAsUnsupportedRoute() throws Exception {
        var analyzed = analyze(
                "property_initializer_inherited_type_meta_call.gd",
                """
                        class_name PropertyInitializerInheritedTypeMetaCall
                        extends PropertyInitializerBase
                        
                        static var blocked_value := PropertyInitializerBase.payload
                        static var blocked_signal := PropertyInitializerBase.changed
                        static var blocked_call := PropertyInitializerBase.read()
                        static var allowed_helper := PropertyInitializerBase.helper()
                        """,
                FrontendAnalyzerTestRegistrySupport.registryWithInheritedPropertyInitializerBase()
        );

        var blockedValueDeclaration = findNode(
                analyzed.unit().ast(),
                VariableDeclaration.class,
                declaration -> declaration.name().equals("blocked_value")
        );
        var blockedSignalDeclaration = findNode(
                analyzed.unit().ast(),
                VariableDeclaration.class,
                declaration -> declaration.name().equals("blocked_signal")
        );
        var blockedCallDeclaration = findNode(
                analyzed.unit().ast(),
                VariableDeclaration.class,
                declaration -> declaration.name().equals("blocked_call")
        );
        var allowedHelperDeclaration = findNode(
                analyzed.unit().ast(),
                VariableDeclaration.class,
                declaration -> declaration.name().equals("allowed_helper")
        );
        var payloadStep = findNode(blockedValueDeclaration.value(), AttributePropertyStep.class, step -> step.name().equals("payload"));
        var changedStep = findNode(blockedSignalDeclaration.value(), AttributePropertyStep.class, step -> step.name().equals("changed"));
        var readStep = findNode(blockedCallDeclaration.value(), AttributeCallStep.class, step -> step.name().equals("read"));
        var helperStep = findNode(allowedHelperDeclaration.value(), AttributeCallStep.class, step -> step.name().equals("helper"));

        var unsupportedValue = analyzed.analysisData().resolvedMembers().get(payloadStep);
        assertNotNull(unsupportedValue);
        assertEquals(FrontendMemberResolutionStatus.UNSUPPORTED, unsupportedValue.status());
        assertEquals(FrontendBindingKind.PROPERTY, unsupportedValue.bindingKind());
        assertEquals(FrontendReceiverKind.TYPE_META, unsupportedValue.receiverKind());

        var unsupportedSignal = analyzed.analysisData().resolvedMembers().get(changedStep);
        assertNotNull(unsupportedSignal);
        assertEquals(FrontendMemberResolutionStatus.UNSUPPORTED, unsupportedSignal.status());
        assertEquals(FrontendBindingKind.SIGNAL, unsupportedSignal.bindingKind());
        assertEquals(FrontendReceiverKind.TYPE_META, unsupportedSignal.receiverKind());

        var unsupportedRead = analyzed.analysisData().resolvedCalls().get(readStep);
        assertNotNull(unsupportedRead);
        assertEquals(FrontendCallResolutionStatus.UNSUPPORTED, unsupportedRead.status());
        assertEquals(FrontendCallResolutionKind.STATIC_METHOD, unsupportedRead.callKind());
        assertEquals(FrontendReceiverKind.TYPE_META, unsupportedRead.receiverKind());

        var resolvedHelper = analyzed.analysisData().resolvedCalls().get(helperStep);
        assertNotNull(resolvedHelper);
        assertEquals(FrontendCallResolutionStatus.RESOLVED, resolvedHelper.status());
        assertEquals(FrontendCallResolutionKind.STATIC_METHOD, resolvedHelper.callKind());
        assertEquals(FrontendReceiverKind.TYPE_META, resolvedHelper.receiverKind());

        var unsupportedDiagnostics = diagnosticsByCategory(analyzed.analysisData(), "sema.unsupported_chain_route");
        assertEquals(3, unsupportedDiagnostics.size());
        assertTrue(unsupportedDiagnostics.stream().anyMatch(diagnostic ->
                diagnostic.message().contains("PropertyInitializerBase.payload")
        ));
        assertTrue(unsupportedDiagnostics.stream().anyMatch(diagnostic ->
                diagnostic.message().contains("PropertyInitializerBase.changed")
        ));
        assertTrue(unsupportedDiagnostics.stream().anyMatch(diagnostic ->
                diagnostic.message().contains("PropertyInitializerBase.read")
        ));
        assertFalse(unsupportedDiagnostics.stream().anyMatch(diagnostic -> diagnostic.message().contains("helper")));
        assertTrue(diagnosticsByCategory(analyzed.analysisData(), "sema.member_resolution").isEmpty());
        assertTrue(diagnosticsByCategory(analyzed.analysisData(), "sema.call_resolution").isEmpty());
    }

    @Test
    void analyzeSealsSameClassInstanceSuffixRoutesInPropertyInitializerAsUnsupported() throws Exception {
        var analyzed = analyze(
                "property_initializer_same_class_suffix_routes.gd",
                """
                        class_name PropertyInitializerSameClassSuffixRoutes
                        extends RefCounted
                        
                        signal changed
                        var payload: int = 1
                        
                        func read() -> int:
                            return 1
                        
                        static func build() -> PropertyInitializerSameClassSuffixRoutes:
                            return null
                        
                        var blocked_value := build().payload
                        var blocked_call := build().read()
                        var blocked_signal := build().changed
                        """
        );

        var blockedValueDeclaration = findNode(
                analyzed.unit().ast(),
                VariableDeclaration.class,
                declaration -> declaration.name().equals("blocked_value")
        );
        var blockedCallDeclaration = findNode(
                analyzed.unit().ast(),
                VariableDeclaration.class,
                declaration -> declaration.name().equals("blocked_call")
        );
        var blockedSignalDeclaration = findNode(
                analyzed.unit().ast(),
                VariableDeclaration.class,
                declaration -> declaration.name().equals("blocked_signal")
        );
        var payloadStep = findNode(blockedValueDeclaration.value(), AttributePropertyStep.class, step -> step.name().equals("payload"));
        var readStep = findNode(blockedCallDeclaration.value(), AttributeCallStep.class, step -> step.name().equals("read"));
        var changedStep = findNode(blockedSignalDeclaration.value(), AttributePropertyStep.class, step -> step.name().equals("changed"));

        var unsupportedValue = analyzed.analysisData().resolvedMembers().get(payloadStep);
        assertNotNull(unsupportedValue);
        assertEquals(FrontendMemberResolutionStatus.UNSUPPORTED, unsupportedValue.status());
        assertEquals(FrontendBindingKind.PROPERTY, unsupportedValue.bindingKind());
        assertEquals(FrontendReceiverKind.INSTANCE, unsupportedValue.receiverKind());

        var unsupportedSignal = analyzed.analysisData().resolvedMembers().get(changedStep);
        assertNotNull(unsupportedSignal);
        assertEquals(FrontendMemberResolutionStatus.UNSUPPORTED, unsupportedSignal.status());
        assertEquals(FrontendBindingKind.SIGNAL, unsupportedSignal.bindingKind());
        assertEquals(FrontendReceiverKind.INSTANCE, unsupportedSignal.receiverKind());

        var unsupportedRead = analyzed.analysisData().resolvedCalls().get(readStep);
        assertNotNull(unsupportedRead);
        assertEquals(FrontendCallResolutionStatus.UNSUPPORTED, unsupportedRead.status());
        assertEquals(FrontendCallResolutionKind.INSTANCE_METHOD, unsupportedRead.callKind());
        assertEquals(FrontendReceiverKind.INSTANCE, unsupportedRead.receiverKind());

        var unsupportedDiagnostics = diagnosticsByCategory(analyzed.analysisData(), "sema.unsupported_chain_route");
        assertEquals(3, unsupportedDiagnostics.size());
        assertTrue(unsupportedDiagnostics.stream().anyMatch(diagnostic -> diagnostic.message().contains("payload")));
        assertTrue(unsupportedDiagnostics.stream().anyMatch(diagnostic -> diagnostic.message().contains("changed")));
        assertTrue(unsupportedDiagnostics.stream().anyMatch(diagnostic -> diagnostic.message().contains("read")));
        assertTrue(diagnosticsByCategory(analyzed.analysisData(), "sema.member_resolution").isEmpty());
        assertTrue(diagnosticsByCategory(analyzed.analysisData(), "sema.call_resolution").isEmpty());
    }

    @Test
    void analyzeSealsInheritedInstanceSuffixRoutesInPropertyInitializerAsUnsupported() throws Exception {
        var analyzed = analyze(
                "property_initializer_inherited_suffix_routes.gd",
                """
                        class_name PropertyInitializerInheritedSuffixRoutes
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
                analyzed.unit().ast(),
                VariableDeclaration.class,
                declaration -> declaration.name().equals("blocked_value")
        );
        var blockedCallDeclaration = findNode(
                analyzed.unit().ast(),
                VariableDeclaration.class,
                declaration -> declaration.name().equals("blocked_call")
        );
        var blockedSignalDeclaration = findNode(
                analyzed.unit().ast(),
                VariableDeclaration.class,
                declaration -> declaration.name().equals("blocked_signal")
        );
        var allowedHelperDeclaration = findNode(
                analyzed.unit().ast(),
                VariableDeclaration.class,
                declaration -> declaration.name().equals("allowed_helper")
        );
        var payloadStep = findNode(blockedValueDeclaration.value(), AttributePropertyStep.class, step -> step.name().equals("payload"));
        var readStep = findNode(blockedCallDeclaration.value(), AttributeCallStep.class, step -> step.name().equals("read"));
        var changedStep = findNode(blockedSignalDeclaration.value(), AttributePropertyStep.class, step -> step.name().equals("changed"));
        var helperStep = findNode(allowedHelperDeclaration.value(), AttributeCallStep.class, step -> step.name().equals("helper"));

        var unsupportedValue = analyzed.analysisData().resolvedMembers().get(payloadStep);
        assertNotNull(unsupportedValue);
        assertEquals(FrontendMemberResolutionStatus.UNSUPPORTED, unsupportedValue.status());
        assertEquals(FrontendBindingKind.PROPERTY, unsupportedValue.bindingKind());
        assertEquals(FrontendReceiverKind.INSTANCE, unsupportedValue.receiverKind());

        var unsupportedSignal = analyzed.analysisData().resolvedMembers().get(changedStep);
        assertNotNull(unsupportedSignal);
        assertEquals(FrontendMemberResolutionStatus.UNSUPPORTED, unsupportedSignal.status());
        assertEquals(FrontendBindingKind.SIGNAL, unsupportedSignal.bindingKind());
        assertEquals(FrontendReceiverKind.INSTANCE, unsupportedSignal.receiverKind());

        var unsupportedRead = analyzed.analysisData().resolvedCalls().get(readStep);
        assertNotNull(unsupportedRead);
        assertEquals(FrontendCallResolutionStatus.UNSUPPORTED, unsupportedRead.status());
        assertEquals(FrontendCallResolutionKind.INSTANCE_METHOD, unsupportedRead.callKind());
        assertEquals(FrontendReceiverKind.INSTANCE, unsupportedRead.receiverKind());

        var resolvedHelper = analyzed.analysisData().resolvedCalls().get(helperStep);
        assertNotNull(resolvedHelper);
        assertEquals(FrontendCallResolutionStatus.RESOLVED, resolvedHelper.status());
        assertEquals(FrontendCallResolutionKind.STATIC_METHOD, resolvedHelper.callKind());
        assertEquals(FrontendReceiverKind.TYPE_META, resolvedHelper.receiverKind());

        var unsupportedDiagnostics = diagnosticsByCategory(analyzed.analysisData(), "sema.unsupported_chain_route");
        assertEquals(3, unsupportedDiagnostics.size());
        assertTrue(unsupportedDiagnostics.stream().anyMatch(diagnostic -> diagnostic.message().contains("payload")));
        assertTrue(unsupportedDiagnostics.stream().anyMatch(diagnostic -> diagnostic.message().contains("changed")));
        assertTrue(unsupportedDiagnostics.stream().anyMatch(diagnostic -> diagnostic.message().contains("read")));
        assertFalse(unsupportedDiagnostics.stream().anyMatch(diagnostic -> diagnostic.message().contains("helper")));
        assertTrue(diagnosticsByCategory(analyzed.analysisData(), "sema.member_resolution").isEmpty());
        assertTrue(diagnosticsByCategory(analyzed.analysisData(), "sema.call_resolution").isEmpty());
    }

    @Test
    void analyzePublishesEachNonHeadStepForResolvedStaticRouteChain() throws Exception {
        var analyzed = analyze(
                "resolved_static_route_chain.gd",
                """
                        class_name ResolvedStaticRouteChain
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

        var pingFunction = findFunction(analyzed.unit().ast(), "ping");
        var chainStatement = assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().getFirst());
        var buildStep = findNode(chainStatement, AttributeCallStep.class, step -> step.name().equals("build"));
        var handleStep = findNode(chainStatement, AttributePropertyStep.class, step -> step.name().equals("handle"));
        var startStep = findNode(chainStatement, AttributeCallStep.class, step -> step.name().equals("start"));

        var resolvedBuild = analyzed.analysisData().resolvedCalls().get(buildStep);
        assertNotNull(resolvedBuild);
        assertEquals(FrontendCallResolutionStatus.RESOLVED, resolvedBuild.status());
        assertEquals(FrontendCallResolutionKind.STATIC_METHOD, resolvedBuild.callKind());
        assertEquals(FrontendReceiverKind.TYPE_META, resolvedBuild.receiverKind());
        var resolvedBuildType = resolvedBuild.returnType();
        assertNotNull(resolvedBuildType);
        assertEquals("ResolvedStaticRouteChain$Worker", resolvedBuildType.getTypeName());

        var resolvedHandle = analyzed.analysisData().resolvedMembers().get(handleStep);
        assertNotNull(resolvedHandle);
        assertEquals(FrontendMemberResolutionStatus.RESOLVED, resolvedHandle.status());
        assertEquals(FrontendBindingKind.PROPERTY, resolvedHandle.bindingKind());
        assertEquals(FrontendReceiverKind.INSTANCE, resolvedHandle.receiverKind());
        var resolvedHandleType = resolvedHandle.resultType();
        assertNotNull(resolvedHandleType);
        assertEquals("ResolvedStaticRouteChain$Handle", resolvedHandleType.getTypeName());

        var resolvedStart = analyzed.analysisData().resolvedCalls().get(startStep);
        assertNotNull(resolvedStart);
        assertEquals(FrontendCallResolutionStatus.RESOLVED, resolvedStart.status());
        assertEquals(FrontendCallResolutionKind.INSTANCE_METHOD, resolvedStart.callKind());
        assertEquals(FrontendReceiverKind.INSTANCE, resolvedStart.receiverKind());
        var resolvedStartType = resolvedStart.returnType();
        assertNotNull(resolvedStartType);
        assertEquals("int", resolvedStartType.getTypeName());

        assertTrue(diagnosticsByCategory(analyzed.analysisData(), "sema.member_resolution").isEmpty());
        assertTrue(diagnosticsByCategory(analyzed.analysisData(), "sema.call_resolution").isEmpty());
    }

    @Test
    void analyzePublishesConstructorThenWarnsWhenInstanceSyntaxHitsStaticMethod() throws Exception {
        var analyzed = analyze(
                "instance_static_method.gd",
                """
                        class_name InstanceStaticMethod
                        extends RefCounted
                        
                        class Worker:
                            static func build():
                                return 1
                        
                        func ping():
                            Worker.new().build()
                        """
        );

        var pingFunction = findFunction(analyzed.unit().ast(), "ping");
        var chainStatement = assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().getFirst());
        var constructorStep = findNode(chainStatement, AttributeCallStep.class, step -> step.name().equals("new"));
        var buildStep = findNode(chainStatement, AttributeCallStep.class, step -> step.name().equals("build"));

        var constructorCall = analyzed.analysisData().resolvedCalls().get(constructorStep);
        assertNotNull(constructorCall);
        assertEquals(FrontendCallResolutionStatus.RESOLVED, constructorCall.status());
        assertEquals(FrontendCallResolutionKind.CONSTRUCTOR, constructorCall.callKind());
        assertEquals(FrontendReceiverKind.TYPE_META, constructorCall.receiverKind());

        var resolvedBuild = analyzed.analysisData().resolvedCalls().get(buildStep);
        assertNotNull(resolvedBuild);
        assertEquals(FrontendCallResolutionStatus.RESOLVED, resolvedBuild.status());
        assertEquals(FrontendCallResolutionKind.STATIC_METHOD, resolvedBuild.callKind());
        assertEquals(FrontendReceiverKind.INSTANCE, resolvedBuild.receiverKind());

        var callDiagnostics = diagnosticsByCategory(analyzed.analysisData(), "sema.call_resolution");
        assertEquals(1, callDiagnostics.size());
        assertTrue(callDiagnostics.getFirst().message().contains("Instance-style syntax resolved to static method"));
    }

    @Test
    void analyzePublishesBinaryArgumentFactsWithoutCascadingSuffixMisses() throws Exception {
        var analyzed = analyze(
                "deferred_suffix.gd",
                """
                        class_name DeferredSuffix
                        extends RefCounted
                        
                        func build(value: int) -> String:
                            return ""
                        
                        func ping():
                            self.build(1 + 2).length
                        """
        );

        var pingFunction = findFunction(analyzed.unit().ast(), "ping");
        var chainStatement = assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().getFirst());
        var buildStep = findNode(chainStatement, AttributeCallStep.class, step -> step.name().equals("build"));
        var lengthStep = findNode(chainStatement, AttributePropertyStep.class, step -> step.name().equals("length"));

        var resolvedCall = analyzed.analysisData().resolvedCalls().get(buildStep);
        assertNotNull(resolvedCall);
        assertEquals(FrontendCallResolutionStatus.RESOLVED, resolvedCall.status());
        assertEquals(FrontendCallResolutionKind.INSTANCE_METHOD, resolvedCall.callKind());
        var resolvedLength = analyzed.analysisData().resolvedMembers().get(lengthStep);
        assertNotNull(resolvedLength);
        assertEquals(FrontendMemberResolutionStatus.RESOLVED, resolvedLength.status());
        assertEquals(FrontendBindingKind.METHOD, resolvedLength.bindingKind());
        assertEquals(1, analyzed.analysisData().resolvedMembers().size());
        assertEquals(1, analyzed.analysisData().resolvedCalls().size());
        assertTrue(diagnosticsByCategory(analyzed.analysisData(), "sema.deferred_chain_resolution").isEmpty());
        assertTrue(diagnosticsByCategory(analyzed.analysisData(), "sema.member_resolution").isEmpty());
        assertTrue(diagnosticsByCategory(analyzed.analysisData(), "sema.call_resolution").isEmpty());
    }

    @Test
    void analyzeKeepsDeferredArgumentBoundaryForRemainingUnsupportedExpressionKinds() throws Exception {
        var analyzed = analyze(
                "deferred_suffix_remaining_gap.gd",
                """
                        class_name DeferredSuffixRemainingGap
                        extends RefCounted
                        
                        func build(value: int) -> String:
                            return ""
                        
                        func ping(flag):
                            self.build(1 if flag else 2).length
                        """
        );

        var pingFunction = findFunction(analyzed.unit().ast(), "ping");
        var chainStatement = assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().getFirst());
        var buildStep = findNode(chainStatement, AttributeCallStep.class, step -> step.name().equals("build"));
        var lengthStep = findNode(chainStatement, AttributePropertyStep.class, step -> step.name().equals("length"));

        var deferredCall = analyzed.analysisData().resolvedCalls().get(buildStep);
        assertNotNull(deferredCall);
        assertEquals(FrontendCallResolutionStatus.DEFERRED, deferredCall.status());
        assertEquals(FrontendCallResolutionKind.INSTANCE_METHOD, deferredCall.callKind());
        assertEquals(0, analyzed.analysisData().resolvedMembers().size());
        assertEquals(1, analyzed.analysisData().resolvedCalls().size());
        assertTrue(analyzed.analysisData().resolvedMembers().get(lengthStep) == null);

        var deferredDiagnostics = diagnosticsByCategory(analyzed.analysisData(), "sema.deferred_chain_resolution");
        assertEquals(1, deferredDiagnostics.size());
        assertTrue(deferredDiagnostics.getFirst().message().contains("Argument #1 type is still deferred"));
        assertTrue(deferredDiagnostics.getFirst().message().contains("Conditional expression typing is deferred"));
        assertTrue(diagnosticsByCategory(analyzed.analysisData(), "sema.member_resolution").isEmpty());
        assertTrue(diagnosticsByCategory(analyzed.analysisData(), "sema.call_resolution").isEmpty());
    }

    @Test
    void analyzeUsesDynamicArgumentVariantToKeepOuterCallResolvable() throws Exception {
        var analyzed = analyze(
                "dynamic_argument_route.gd",
                """
                        class_name DynamicArgumentRoute
                        extends RefCounted
                        
                        func consume(value) -> int:
                            return 1
                        
                        func ping(worker):
                            self.consume(worker.ping())
                        """
        );

        var pingFunction = findFunction(analyzed.unit().ast(), "ping");
        var callStatement = assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().getFirst());
        var consumeStep = findNode(callStatement, AttributeCallStep.class, step -> step.name().equals("consume"));
        var pingStep = findNode(callStatement, AttributeCallStep.class, step -> step.name().equals("ping"));

        var innerDynamicCall = analyzed.analysisData().resolvedCalls().get(pingStep);
        assertNotNull(innerDynamicCall);
        assertEquals(FrontendCallResolutionStatus.DYNAMIC, innerDynamicCall.status());

        var outerResolvedCall = analyzed.analysisData().resolvedCalls().get(consumeStep);
        assertNotNull(outerResolvedCall);
        assertEquals(
                FrontendCallResolutionStatus.RESOLVED,
                outerResolvedCall.status(),
                String.valueOf(outerResolvedCall.detailReason())
        );
        assertEquals(FrontendCallResolutionKind.INSTANCE_METHOD, outerResolvedCall.callKind());
        assertTrue(diagnosticsByCategory(analyzed.analysisData(), "sema.call_resolution").isEmpty());
    }

    @Test
    void analyzeUsesTypedPlainSubscriptArgumentsToKeepOuterCallExact() throws Exception {
        var analyzed = analyze(
                "subscript_argument_route.gd",
                """
                        class_name SubscriptArgumentRoute
                        extends RefCounted
                        
                        func consume(value: int) -> int:
                            return value
                        
                        func ping(items: Array[int]):
                            self.consume(items[0])
                        """
        );

        var pingFunction = findFunction(analyzed.unit().ast(), "ping");
        var callStatement = assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().getFirst());
        var consumeStep = findNode(callStatement, AttributeCallStep.class, step -> step.name().equals("consume"));

        var resolvedCall = analyzed.analysisData().resolvedCalls().get(consumeStep);
        assertNotNull(resolvedCall);
        assertEquals(FrontendCallResolutionStatus.RESOLVED, resolvedCall.status());
        assertEquals(FrontendCallResolutionKind.INSTANCE_METHOD, resolvedCall.callKind());
        assertTrue(diagnosticsByCategory(analyzed.analysisData(), "sema.deferred_chain_resolution").isEmpty());
        assertTrue(diagnosticsByCategory(analyzed.analysisData(), "sema.call_resolution").isEmpty());
    }

    @Test
    void analyzeKeepsValueRequiredNestedAssignmentFailClosedForDynamicTargets() throws Exception {
        var analyzed = analyze(
                "dynamic_assignment_argument_route.gd",
                """
                        class_name DynamicAssignmentArgumentRoute
                        extends RefCounted
                        
                        func consume(value: int) -> int:
                            return value
                        
                        func ping(dynamic_value):
                            self.consume(dynamic_value[0] = 1)
                        """
        );

        var pingFunction = findFunction(analyzed.unit().ast(), "ping");
        var callStatement = assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().getFirst());
        var consumeStep = findNode(callStatement, AttributeCallStep.class, step -> step.name().equals("consume"));

        var deferredCall = analyzed.analysisData().resolvedCalls().get(consumeStep);
        assertNotNull(deferredCall);
        assertEquals(FrontendCallResolutionStatus.UNSUPPORTED, deferredCall.status());
        assertEquals(FrontendCallResolutionKind.INSTANCE_METHOD, deferredCall.callKind());
        assertNotNull(deferredCall.detailReason());
        assertTrue(!deferredCall.detailReason().isBlank());

        var unsupportedDiagnostics = diagnosticsByCategory(analyzed.analysisData(), "sema.unsupported_chain_route");
        assertEquals(1, unsupportedDiagnostics.size());
        assertEquals(FrontendDiagnosticSeverity.ERROR, unsupportedDiagnostics.getFirst().severity());
        assertTrue(!unsupportedDiagnostics.getFirst().message().isBlank());
        assertTrue(diagnosticsByCategory(analyzed.analysisData(), "sema.call_resolution").isEmpty());
        assertTrue(diagnosticsByCategory(analyzed.analysisData(), "sema.deferred_chain_resolution").isEmpty());
    }

    void analyzeKeepsExactSuffixAfterAttributeSubscriptStep() throws Exception {
        var analyzed = analyze(
                "attribute_subscript_suffix.gd",
                """
                        class_name AttributeSubscriptSuffix
                        extends RefCounted
                        
                        class Item:
                            var payload: int = 1
                        
                        class Holder:
                            var items: Array[Item] = []
                        
                        func ping(holder: Holder):
                            holder.items[0].payload
                        """
        );

        var pingFunction = findFunction(analyzed.unit().ast(), "ping");
        var chainStatement = assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().getFirst());
        var itemsStep = findNode(chainStatement, AttributeSubscriptStep.class, step -> step.name().equals("items"));
        var payloadStep = findNode(chainStatement, AttributePropertyStep.class, step -> step.name().equals("payload"));

        assertTrue(analyzed.analysisData().resolvedMembers().get(itemsStep) == null);

        var resolvedPayload = analyzed.analysisData().resolvedMembers().get(payloadStep);
        assertNotNull(resolvedPayload);
        assertEquals(FrontendMemberResolutionStatus.RESOLVED, resolvedPayload.status());
        assertEquals(FrontendBindingKind.PROPERTY, resolvedPayload.bindingKind());
        var resolvedPayloadType = resolvedPayload.resultType();
        assertNotNull(resolvedPayloadType);
        assertEquals("int", resolvedPayloadType.getTypeName());
        assertTrue(diagnosticsByCategory(analyzed.analysisData(), "sema.unsupported_chain_route").isEmpty());
    }

    @Test
    void analyzeReportsUnsupportedBoundaryForAttributeSubscriptKeyedBuiltinRoute() throws Exception {
        var analyzed = analyze(
                "attribute_subscript_keyed_unsupported.gd",
                """
                        class_name AttributeSubscriptKeyedUnsupported
                        extends RefCounted
                        
                        class Holder:
                            var text: String = ""
                        
                        func ping(holder: Holder):
                            holder.text[0].length
                        """,
                registryWithKeyedStringBuiltin()
        );

        var pingFunction = findFunction(analyzed.unit().ast(), "ping");
        var chainStatement = assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().getFirst());
        var lengthStep = findNode(chainStatement, AttributePropertyStep.class, step -> step.name().equals("length"));

        assertTrue(analyzed.analysisData().resolvedMembers().get(lengthStep) == null);

        var unsupportedDiagnostics = diagnosticsByCategory(analyzed.analysisData(), "sema.unsupported_chain_route");
        assertEquals(1, unsupportedDiagnostics.size());
        assertEquals(FrontendDiagnosticSeverity.ERROR, unsupportedDiagnostics.getFirst().severity());
        assertTrue(unsupportedDiagnostics.getFirst().message().contains("keyed access metadata"));
    }

    @Test
    void analyzePublishesMethodReferenceMembersAsCallableValues() throws Exception {
        var analyzed = analyze(
                "method_reference_members.gd",
                """
                        class_name MethodReferenceMembers
                        extends RefCounted
                        
                        class Worker:
                            static func build() -> int:
                                return 1
                        
                        func helper() -> int:
                            return 1
                        
                        func ping():
                            self.helper
                            Worker.build
                        """
        );

        var pingFunction = findFunction(analyzed.unit().ast(), "ping");
        var instanceReferenceStep = findNode(
                assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().getFirst()),
                AttributePropertyStep.class,
                step -> step.name().equals("helper")
        );
        var staticReferenceStep = findNode(
                assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().get(1)),
                AttributePropertyStep.class,
                step -> step.name().equals("build")
        );

        var instanceReference = analyzed.analysisData().resolvedMembers().get(instanceReferenceStep);
        assertNotNull(instanceReference);
        assertEquals(FrontendMemberResolutionStatus.RESOLVED, instanceReference.status());
        assertEquals(FrontendBindingKind.METHOD, instanceReference.bindingKind());
        assertEquals(FrontendReceiverKind.INSTANCE, instanceReference.receiverKind());
        assertInstanceOf(GdCallableType.class, instanceReference.resultType());

        var staticReference = analyzed.analysisData().resolvedMembers().get(staticReferenceStep);
        assertNotNull(staticReference);
        assertEquals(FrontendMemberResolutionStatus.RESOLVED, staticReference.status());
        assertEquals(FrontendBindingKind.STATIC_METHOD, staticReference.bindingKind());
        assertEquals(FrontendReceiverKind.TYPE_META, staticReference.receiverKind());
        assertInstanceOf(GdCallableType.class, staticReference.resultType());

        assertTrue(diagnosticsByCategory(analyzed.analysisData(), "sema.member_resolution").isEmpty());
        assertTrue(diagnosticsByCategory(analyzed.analysisData(), "sema.unsupported_chain_route").isEmpty());
    }

    @Test
    void analyzeUsesCallableReceiverForBareFunctionHeadChains() throws Exception {
        var analyzed = analyze(
                "callable_head_chain.gd",
                """
                        class_name CallableHeadChain
                        extends RefCounted
                        
                        func helper() -> int:
                            return 1
                        
                        func ping():
                            helper.call()
                        """
        );

        var pingFunction = findFunction(analyzed.unit().ast(), "ping");
        var callStep = findNode(
                assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().getFirst()),
                AttributeCallStep.class,
                step -> step.name().equals("call")
        );

        var resolvedCall = analyzed.analysisData().resolvedCalls().get(callStep);
        assertNotNull(resolvedCall);
        assertEquals(FrontendCallResolutionStatus.RESOLVED, resolvedCall.status());
        assertEquals(FrontendCallResolutionKind.INSTANCE_METHOD, resolvedCall.callKind());
        assertEquals(FrontendReceiverKind.INSTANCE, resolvedCall.receiverKind());
        assertTrue(diagnosticsByCategory(analyzed.analysisData(), "sema.call_resolution").isEmpty());
    }

    @Test
    void analyzeResolvesCallableBindAndCallableHeadVariants() throws Exception {
        var analyzed = analyze(
                "callable_bind_and_head_variants.gd",
                """
                        class_name CallableBindAndHeadVariants
                        extends RefCounted
                        
                        class Worker:
                            static func build(value: int) -> int:
                                return value
                        
                        func helper(value: int) -> int:
                            return value
                        
                        func ping(items: Array[Callable], dict: Dictionary[String, Callable]):
                            helper.bind(1)
                            self.helper.bind(1)
                            Worker.build.bind(1)
                            self.helper.call()
                            Worker.build.call()
                            items[0].bind(1)
                            dict["cb"].call()
                        """
        );

        var pingFunction = findFunction(analyzed.unit().ast(), "ping");
        var statements = pingFunction.body().statements();
        var bareBindStep = findNode(
                assertInstanceOf(ExpressionStatement.class, statements.get(0)),
                AttributeCallStep.class,
                step -> step.name().equals("bind")
        );
        var selfBindStep = findNode(
                assertInstanceOf(ExpressionStatement.class, statements.get(1)),
                AttributeCallStep.class,
                step -> step.name().equals("bind")
        );
        var staticBindStep = findNode(
                assertInstanceOf(ExpressionStatement.class, statements.get(2)),
                AttributeCallStep.class,
                step -> step.name().equals("bind")
        );
        var selfCallStep = findNode(
                assertInstanceOf(ExpressionStatement.class, statements.get(3)),
                AttributeCallStep.class,
                step -> step.name().equals("call")
        );
        var staticCallStep = findNode(
                assertInstanceOf(ExpressionStatement.class, statements.get(4)),
                AttributeCallStep.class,
                step -> step.name().equals("call")
        );
        var subscriptBindStep = findNode(
                assertInstanceOf(ExpressionStatement.class, statements.get(5)),
                AttributeCallStep.class,
                step -> step.name().equals("bind")
        );
        var dictCallStep = findNode(
                assertInstanceOf(ExpressionStatement.class, statements.get(6)),
                AttributeCallStep.class,
                step -> step.name().equals("call")
        );

        for (var bindStep : List.of(bareBindStep, selfBindStep, staticBindStep, subscriptBindStep)) {
            var resolvedBind = analyzed.analysisData().resolvedCalls().get(bindStep);
            assertNotNull(resolvedBind);
            assertEquals(FrontendCallResolutionStatus.RESOLVED, resolvedBind.status());
            assertEquals(FrontendCallResolutionKind.INSTANCE_METHOD, resolvedBind.callKind());
            assertEquals(FrontendReceiverKind.INSTANCE, resolvedBind.receiverKind());
            assertEquals("Callable", resolvedBind.returnType().getTypeName());
        }

        for (var callStep : List.of(selfCallStep, staticCallStep, dictCallStep)) {
            var resolvedCall = analyzed.analysisData().resolvedCalls().get(callStep);
            assertNotNull(resolvedCall);
            assertEquals(FrontendCallResolutionStatus.RESOLVED, resolvedCall.status());
            assertEquals(FrontendCallResolutionKind.INSTANCE_METHOD, resolvedCall.callKind());
            assertEquals(FrontendReceiverKind.INSTANCE, resolvedCall.receiverKind());
            assertEquals(GdVariantType.VARIANT, resolvedCall.returnType());
        }

        assertTrue(diagnosticsByCategory(analyzed.analysisData(), "sema.call_resolution").isEmpty());
        assertTrue(diagnosticsByCategory(analyzed.analysisData(), "sema.deferred_chain_resolution").isEmpty());
        assertTrue(diagnosticsByCategory(analyzed.analysisData(), "sema.unsupported_chain_route").isEmpty());
    }

    @Test
    void analyzePropagatesBlockedBareCallArgumentsThroughChainPath() throws Exception {
        var analyzed = analyze(
                "blocked_bare_call_chain_path.gd",
                """
                        class_name BlockedBareCallChainPath
                        extends RefCounted
                        
                        class Target:
                            func consume(value: int) -> int:
                                return value
                        
                        func helper(value: int) -> int:
                            return value
                        
                        static func ping_static(target: Target, value: int):
                            target.consume(helper(value))
                        """
        );

        var pingStaticFunction = findFunction(analyzed.unit().ast(), "ping_static");
        var chainExpression = assertInstanceOf(
                AttributeExpression.class,
                assertInstanceOf(ExpressionStatement.class, pingStaticFunction.body().statements().getFirst()).expression()
        );
        var consumeStep = findNode(chainExpression, AttributeCallStep.class, step -> step.name().equals("consume"));
        var blockedBareCall = findNode(
                chainExpression,
                CallExpression.class,
                candidate -> candidate.callee() instanceof IdentifierExpression identifier
                        && identifier.name().equals("helper")
        );

        var blockedBareCallType = analyzed.analysisData().expressionTypes().get(blockedBareCall);
        assertNotNull(blockedBareCallType);
        assertEquals(FrontendExpressionTypeStatus.BLOCKED, blockedBareCallType.status());
        assertNotNull(blockedBareCallType.publishedType());
        assertEquals("int", blockedBareCallType.publishedType().getTypeName());

        var outerChainType = analyzed.analysisData().expressionTypes().get(chainExpression);
        assertNotNull(outerChainType);
        assertEquals(FrontendExpressionTypeStatus.BLOCKED, outerChainType.status());
        assertTrue(outerChainType.publishedType() == null);
        assertTrue(outerChainType.detailReason().contains("Argument #1 is blocked"));

        assertTrue(analyzed.analysisData().resolvedCalls().get(consumeStep) == null);
        assertTrue(diagnosticsByCategory(analyzed.analysisData(), "sema.call_resolution").isEmpty());
        assertTrue(diagnosticsByCategory(analyzed.analysisData(), "sema.deferred_chain_resolution").isEmpty());
        assertTrue(diagnosticsByCategory(analyzed.analysisData(), "sema.unsupported_chain_route").isEmpty());
    }

    @Test
    void analyzeResolvesBareCallArgumentDependenciesInsideChainCalls() throws Exception {
        var analyzed = analyze(
                "bare_call_chain_argument.gd",
                """
                        class_name BareCallChainArgument
                        extends RefCounted
                        
                        func helper() -> int:
                            return 1
                        
                        func consume(value: int) -> int:
                            return value
                        
                        func ping():
                            self.consume(helper())
                        """
        );

        var pingFunction = findFunction(analyzed.unit().ast(), "ping");
        var consumeStep = findNode(
                assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().getFirst()),
                AttributeCallStep.class,
                step -> step.name().equals("consume")
        );

        var resolvedConsume = analyzed.analysisData().resolvedCalls().get(consumeStep);
        assertNotNull(resolvedConsume);
        assertEquals(FrontendCallResolutionStatus.RESOLVED, resolvedConsume.status());
        assertEquals(FrontendCallResolutionKind.INSTANCE_METHOD, resolvedConsume.callKind());
        assertTrue(diagnosticsByCategory(analyzed.analysisData(), "sema.deferred_chain_resolution").isEmpty());
        assertTrue(diagnosticsByCategory(analyzed.analysisData(), "sema.call_resolution").isEmpty());
    }

    @Test
    void analyzePublishesStaticLoadFactsForGlobalEnumBuiltinAndEngineConstants() throws Exception {
        var analyzed = analyze(
                "static_load_routes.gd",
                """
                        class_name StaticLoadRoutes
                        extends Node
                        
                        func ping():
                            Side.SIDE_LEFT
                            Vector3.BACK
                            Node.NOTIFICATION_ENTER_TREE
                        """
        );

        var pingFunction = findFunction(analyzed.unit().ast(), "ping");
        var enumStatement = assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().getFirst());
        var builtinStatement = assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().get(1));
        var engineStatement = assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().get(2));

        var enumLoad = analyzed.analysisData().resolvedMembers().get(
                findNode(enumStatement, AttributePropertyStep.class, step -> step.name().equals("SIDE_LEFT"))
        );
        assertNotNull(enumLoad);
        assertEquals(FrontendMemberResolutionStatus.RESOLVED, enumLoad.status());
        assertEquals(FrontendBindingKind.CONSTANT, enumLoad.bindingKind());
        assertEquals(FrontendReceiverKind.TYPE_META, enumLoad.receiverKind());
        var enumResultType = enumLoad.resultType();
        assertNotNull(enumResultType);
        assertEquals("int", enumResultType.getTypeName());

        var builtinLoad = analyzed.analysisData().resolvedMembers().get(
                findNode(builtinStatement, AttributePropertyStep.class, step -> step.name().equals("BACK"))
        );
        assertNotNull(builtinLoad);
        assertEquals(FrontendMemberResolutionStatus.RESOLVED, builtinLoad.status());
        var builtinResultType = builtinLoad.resultType();
        assertNotNull(builtinResultType);
        assertEquals("Vector3", builtinResultType.getTypeName());

        var engineLoad = analyzed.analysisData().resolvedMembers().get(
                findNode(engineStatement, AttributePropertyStep.class, step -> step.name().equals("NOTIFICATION_ENTER_TREE"))
        );
        assertNotNull(engineLoad);
        assertEquals(FrontendMemberResolutionStatus.RESOLVED, engineLoad.status());
        var engineResultType = engineLoad.resultType();
        assertNotNull(engineResultType);
        assertEquals("int", engineResultType.getTypeName());

        assertTrue(diagnosticsByCategory(analyzed.analysisData(), "sema.member_resolution").isEmpty());
        assertTrue(diagnosticsByCategory(analyzed.analysisData(), "sema.unsupported_chain_route").isEmpty());
    }

    @Test
    void analyzeFailsTypeMetaCallToInstanceMethod() throws Exception {
        var analyzed = analyze(
                "failed_static_call.gd",
                """
                        class_name FailedStaticCall
                        extends RefCounted
                        
                        class Worker:
                            func speak():
                                return 1
                        
                        func ping():
                            Worker.speak()
                        """
        );

        var pingFunction = findFunction(analyzed.unit().ast(), "ping");
        var chainStatement = assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().getFirst());
        var speakStep = findNode(chainStatement, AttributeCallStep.class, step -> step.name().equals("speak"));

        var failedCall = analyzed.analysisData().resolvedCalls().get(speakStep);
        assertNotNull(failedCall);
        assertEquals(FrontendCallResolutionStatus.FAILED, failedCall.status());
        assertEquals(FrontendCallResolutionKind.STATIC_METHOD, failedCall.callKind());
        assertEquals(FrontendReceiverKind.TYPE_META, failedCall.receiverKind());

        var callDiagnostics = diagnosticsByCategory(analyzed.analysisData(), "sema.call_resolution");
        assertEquals(1, callDiagnostics.size());
        assertTrue(callDiagnostics.getFirst().message().contains("Static method lookup for 'speak'"));
    }

    @Test
    void analyzeSealsUnsupportedGdccStaticLoadAtBoundary() throws Exception {
        var analyzed = analyze(
                "unsupported_static_load.gd",
                """
                        class_name UnsupportedStaticLoad
                        extends RefCounted
                        
                        class Worker:
                            pass
                        
                        func ping():
                            Worker.VALUE
                        """
        );

        var pingFunction = findFunction(analyzed.unit().ast(), "ping");
        var chainStatement = assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().getFirst());
        var valueStep = findNode(chainStatement, AttributePropertyStep.class, step -> step.name().equals("VALUE"));

        var unsupportedMember = analyzed.analysisData().resolvedMembers().get(valueStep);
        assertNotNull(unsupportedMember);
        assertEquals(FrontendMemberResolutionStatus.UNSUPPORTED, unsupportedMember.status());
        assertEquals(FrontendBindingKind.CONSTANT, unsupportedMember.bindingKind());
        assertEquals(FrontendReceiverKind.TYPE_META, unsupportedMember.receiverKind());

        var unsupportedDiagnostics = diagnosticsByCategory(analyzed.analysisData(), "sema.unsupported_chain_route");
        assertEquals(1, unsupportedDiagnostics.size());
        assertEquals(FrontendDiagnosticSeverity.ERROR, unsupportedDiagnostics.getFirst().severity());
        assertTrue(unsupportedDiagnostics.getFirst().message().contains("Static load route on GDCC class"));
        assertTrue(diagnosticsByCategory(analyzed.analysisData(), "sema.member_resolution").isEmpty());
    }

    @Test
    void analyzePublishesFailedHeadFailureRoutesAndDiagnostics() throws Exception {
        var analyzed = analyze(
                "head_failure_routes.gd",
                """
                        class_name HeadFailureRoutes
                        extends RefCounted
                        
                        func ping():
                            missing.payload
                            missing_call.speak()
                        """
        );

        var pingFunction = findFunction(analyzed.unit().ast(), "ping");
        var failedMemberStatement = assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().getFirst());
        var failedCallStatement = assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().get(1));
        var payloadStep = findNode(failedMemberStatement, AttributePropertyStep.class, step -> step.name().equals("payload"));
        var speakStep = findNode(failedCallStatement, AttributeCallStep.class, step -> step.name().equals("speak"));

        var failedMember = analyzed.analysisData().resolvedMembers().get(payloadStep);
        assertNotNull(failedMember);
        assertEquals(FrontendMemberResolutionStatus.FAILED, failedMember.status());
        assertEquals(FrontendBindingKind.UNKNOWN, failedMember.bindingKind());
        assertEquals(FrontendReceiverKind.UNKNOWN, failedMember.receiverKind());
        assertTrue(failedMember.detailReason().contains("does not resolve to a published value or type-meta receiver"));

        var failedCall = analyzed.analysisData().resolvedCalls().get(speakStep);
        assertNotNull(failedCall);
        assertEquals(FrontendCallResolutionStatus.FAILED, failedCall.status());
        assertEquals(FrontendCallResolutionKind.UNKNOWN, failedCall.callKind());
        assertEquals(FrontendReceiverKind.UNKNOWN, failedCall.receiverKind());
        assertTrue(failedCall.detailReason().contains("does not resolve to a published value or type-meta receiver"));

        assertEquals(2, diagnosticsByCategory(analyzed.analysisData(), "sema.binding").size());
        assertEquals(1, diagnosticsByCategory(analyzed.analysisData(), "sema.member_resolution").size());
        assertEquals(1, diagnosticsByCategory(analyzed.analysisData(), "sema.call_resolution").size());
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
        var diagnostics = new DiagnosticManager();
        var parserService = new GdScriptParserService();
        var unit = parserService.parseUnit(Path.of("tmp", fileName), source, diagnostics);
        var analysisData = new FrontendSemanticAnalyzer().analyze(
                new FrontendModule("test_module", List.of(unit)),
                registry,
                diagnostics
        );
        return new AnalyzedScript(unit, analysisData);
    }

    private static @NotNull ClassRegistry registryWithKeyedStringBuiltin() throws Exception {
        var api = ExtensionApiLoader.loadDefault();
        var patchedBuiltins = api.builtinClasses().stream()
                .map(FrontendChainBindingAnalyzerTest::withKeyedStringBuiltin)
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
                builtinClass.properties(),
                builtinClass.constants()
        );
    }

    private static @NotNull FunctionDeclaration findFunction(@NotNull Node root, @NotNull String name) {
        return findNode(root, FunctionDeclaration.class, function -> function.name().equals(name));
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
