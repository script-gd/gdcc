package gd.script.gdcc.frontend.sema.analyzer;

import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.diagnostic.FrontendDiagnostic;
import gd.script.gdcc.frontend.diagnostic.FrontendDiagnosticSeverity;
import gd.script.gdcc.frontend.parse.FrontendModule;
import gd.script.gdcc.frontend.parse.FrontendSourceUnit;
import gd.script.gdcc.frontend.parse.GdScriptParserService;
import gd.script.gdcc.frontend.sema.FrontendAnalysisData;
import gd.script.gdcc.frontend.sema.FrontendBindingKind;
import gd.script.gdcc.frontend.sema.FrontendCallResolutionKind;
import gd.script.gdcc.frontend.sema.FrontendCallResolutionStatus;
import gd.script.gdcc.frontend.sema.FrontendExpressionTypeStatus;
import gd.script.gdcc.frontend.sema.FrontendMemberResolutionStatus;
import gd.script.gdcc.frontend.sema.FrontendReceiverKind;
import gd.script.gdcc.gdextension.ExtensionAPI;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.gdextension.ExtensionBuiltinClass;
import gd.script.gdcc.gdextension.ExtensionEnumValue;
import gd.script.gdcc.gdextension.ExtensionFunctionArgument;
import gd.script.gdcc.gdextension.ExtensionGdClass;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdCallableType;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.type.GdVariantType;
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
import java.util.Map;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontendBodyOwnerProceduresChainBindingTest {
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
    void analyzeKeepsSingletonCallReceiverStableAgainstLaterLocalShadowing() throws Exception {
        var analyzed = analyze(
                "singleton_call_later_local_shadow.gd",
                """
                        class_name SingletonCallLaterLocalShadow
                        extends RefCounted
                        
                        func ping():
                            Engine.get_frames_drawn()
                            var Engine: String = ""
                        """
        );

        var pingFunction = findFunction(analyzed.unit().ast(), "ping");
        var engineHead = findNode(pingFunction, IdentifierExpression.class, identifier -> identifier.name().equals("Engine"));
        var framesStep = findNode(pingFunction, AttributeCallStep.class, step -> step.name().equals("get_frames_drawn"));
        var binding = analyzed.analysisData().symbolBindings().get(engineHead);
        var resolvedCall = analyzed.analysisData().resolvedCalls().get(framesStep);

        assertAll(
                () -> assertNotNull(binding),
                () -> assertEquals(FrontendBindingKind.SINGLETON, binding.kind()),
                () -> assertNotNull(binding.resolvedValue()),
                () -> assertEquals("Engine", binding.resolvedValue().type().getTypeName()),
                () -> assertNotNull(resolvedCall),
                () -> assertEquals(FrontendCallResolutionStatus.RESOLVED, resolvedCall.status()),
                () -> assertEquals(FrontendCallResolutionKind.INSTANCE_METHOD, resolvedCall.callKind()),
                () -> assertEquals(FrontendReceiverKind.INSTANCE, resolvedCall.receiverKind()),
                () -> assertNotNull(resolvedCall.receiverType()),
                () -> assertEquals("Engine", resolvedCall.receiverType().getTypeName()),
                () -> assertNotNull(resolvedCall.returnType()),
                () -> assertEquals("int", resolvedCall.returnType().getTypeName())
        );
    }

    // ------------------------------------------------------------------
    // Dual-role chain-head route bias downstream behavior
    // ------------------------------------------------------------------

    /// `Engine.get_frames_drawn()` must produce an INSTANCE-method resolved call with
    /// SINGLETON head binding, confirming the dual-role bias does not steal instance calls.
    @Test
    void analyzeDualRoleSingletonInstanceCallStaysInstanceRoute() throws Exception {
        var analyzed = analyze(
                "dual_role_singleton_instance_route.gd",
                """
                        class_name DualRoleSingletonInstanceRoute
                        extends RefCounted
                        
                        func ping():
                            Engine.get_frames_drawn()
                        """
        );

        var pingFunction = findFunction(analyzed.unit().ast(), "ping");
        var engineHead = findNode(pingFunction, IdentifierExpression.class, identifier -> identifier.name().equals("Engine"));
        var framesStep = findNode(pingFunction, AttributeCallStep.class, step -> step.name().equals("get_frames_drawn"));
        var binding = analyzed.analysisData().symbolBindings().get(engineHead);
        var resolvedCall = analyzed.analysisData().resolvedCalls().get(framesStep);

        assertAll(
                () -> assertNotNull(binding),
                () -> assertEquals(FrontendBindingKind.SINGLETON, binding.kind()),
                () -> assertNotNull(resolvedCall),
                () -> assertEquals(FrontendCallResolutionStatus.RESOLVED, resolvedCall.status()),
                () -> assertEquals(FrontendCallResolutionKind.INSTANCE_METHOD, resolvedCall.callKind()),
                () -> assertEquals(FrontendReceiverKind.INSTANCE, resolvedCall.receiverKind())
        );
    }

    /// `IP.RESOLVER_MAX_QUERIES` must produce a TYPE_META head binding and a resolved static
    /// load member, confirming the dual-role bias routes engine class constants through the
    /// static-load path.
    @Test
    void analyzeDualRoleEngineClassConstantRoutesToTypeMetaStaticLoad() throws Exception {
        var analyzed = analyze(
                "dual_role_engine_constant_route.gd",
                """
                        class_name DualRoleEngineConstantRoute
                        extends RefCounted
                        
                        func ping():
                            IP.RESOLVER_MAX_QUERIES
                        """
        );

        var pingFunction = findFunction(analyzed.unit().ast(), "ping");
        var ipHead = findNode(pingFunction, IdentifierExpression.class, identifier -> identifier.name().equals("IP"));
        var constantStep = findNode(pingFunction, AttributePropertyStep.class, step -> step.name().equals("RESOLVER_MAX_QUERIES"));
        var binding = analyzed.analysisData().symbolBindings().get(ipHead);
        var resolvedMember = analyzed.analysisData().resolvedMembers().get(constantStep);

        assertAll(
                () -> assertNotNull(binding),
                () -> assertEquals(FrontendBindingKind.TYPE_META, binding.kind()),
                () -> assertNotNull(resolvedMember),
                () -> assertEquals(FrontendMemberResolutionStatus.RESOLVED, resolvedMember.status()),
                () -> assertEquals(FrontendReceiverKind.TYPE_META, resolvedMember.receiverKind())
        );
    }

    /// `ResourceUID.path_to_uid("res://foo.gd")` must produce a TYPE_META head binding and a
    /// resolved static method call, confirming the dual-role bias routes static methods through
    /// the type-meta static-method path.
    @Test
    void analyzeDualRoleStaticMethodRoutesToTypeMetaStaticCall() throws Exception {
        var analyzed = analyze(
                "dual_role_static_method_route.gd",
                """
                        class_name DualRoleStaticMethodRoute
                        extends RefCounted
                        
                        func ping():
                            ResourceUID.path_to_uid("res://foo.gd")
                        """
        );

        var pingFunction = findFunction(analyzed.unit().ast(), "ping");
        var uidHead = findNode(pingFunction, IdentifierExpression.class, identifier -> identifier.name().equals("ResourceUID"));
        var callStep = findNode(pingFunction, AttributeCallStep.class, step -> step.name().equals("path_to_uid"));
        var binding = analyzed.analysisData().symbolBindings().get(uidHead);
        var resolvedCall = analyzed.analysisData().resolvedCalls().get(callStep);

        assertAll(
                () -> assertNotNull(binding),
                () -> assertEquals(FrontendBindingKind.TYPE_META, binding.kind()),
                () -> assertNotNull(resolvedCall),
                () -> assertEquals(FrontendCallResolutionStatus.RESOLVED, resolvedCall.status()),
                () -> assertEquals(FrontendCallResolutionKind.STATIC_METHOD, resolvedCall.callKind()),
                () -> assertEquals(FrontendReceiverKind.TYPE_META, resolvedCall.receiverKind())
        );
    }

    /// `Input.MOUSE_MODE_VISIBLE` must produce a TYPE_META head binding, confirming the
    /// dual-role bias routes class enum values through the static-load path. Full enum-value
    /// member resolution is a downstream chain-reduction concern; here we only assert that the
    /// head binding switches to TYPE_META and does not materialize a singleton receiver.
    @Test
    void analyzeDualRoleClassEnumValueRoutesToTypeMetaStaticLoad() throws Exception {
        var analyzed = analyze(
                "dual_role_class_enum_route.gd",
                """
                        class_name DualRoleClassEnumRoute
                        extends RefCounted
                        
                        func ping():
                            Input.MOUSE_MODE_VISIBLE
                        """
        );

        var pingFunction = findFunction(analyzed.unit().ast(), "ping");
        var inputHead = findNode(pingFunction, IdentifierExpression.class, identifier -> identifier.name().equals("Input"));
        var binding = analyzed.analysisData().symbolBindings().get(inputHead);

        assertAll(
                () -> assertNotNull(binding),
                () -> assertEquals(FrontendBindingKind.TYPE_META, binding.kind())
        );
    }

    @Test
    void analyzeAcceptsStableVariantSourcesForInstanceAndStaticCallsAfterZeroArgCustomConstruction() throws Exception {
        var analyzed = analyze(
                "variant_call_routes.gd",
                """
                        class_name VariantCallRoutes
                        extends RefCounted
                        
                        class Worker:
                            func _init():
                                pass
                        
                            static func build_count(value: int):
                                return 1
                        
                            func consume(value: int):
                                return 1
                        
                        func ping(seed):
                            Worker.new().consume(seed.anything())
                            Worker.build_count(seed.anything())
                        """
        );

        var pingFunction = findFunction(analyzed.unit().ast(), "ping");
        var instanceStatement = assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().getFirst());
        var staticStatement = assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().get(1));
        var consumeStep = findNode(instanceStatement, AttributeCallStep.class, step -> step.name().equals("consume"));
        var workerNewStep = findNode(instanceStatement, AttributeCallStep.class, step -> step.name().equals("new"));
        var buildStep = findNode(staticStatement, AttributeCallStep.class, step -> step.name().equals("build_count"));

        var resolvedConsume = analyzed.analysisData().resolvedCalls().get(consumeStep);
        assertNotNull(resolvedConsume);
        assertEquals(FrontendCallResolutionStatus.RESOLVED, resolvedConsume.status());
        assertEquals(FrontendCallResolutionKind.INSTANCE_METHOD, resolvedConsume.callKind());

        var resolvedWorkerConstructor = analyzed.analysisData().resolvedCalls().get(workerNewStep);
        assertNotNull(resolvedWorkerConstructor);
        assertEquals(FrontendCallResolutionStatus.RESOLVED, resolvedWorkerConstructor.status());
        assertEquals(FrontendCallResolutionKind.CONSTRUCTOR, resolvedWorkerConstructor.callKind());

        var resolvedBuild = analyzed.analysisData().resolvedCalls().get(buildStep);
        assertNotNull(resolvedBuild);
        assertEquals(FrontendCallResolutionStatus.RESOLVED, resolvedBuild.status());
        assertEquals(FrontendCallResolutionKind.STATIC_METHOD, resolvedBuild.callKind());

        assertTrue(diagnosticsByCategory(analyzed.analysisData(), "sema.call_resolution").isEmpty());
    }

    @Test
    void analyzeResolvesMappedTopLevelStaticCallAcrossSourceUnitsViaCallerSideRemap() throws Exception {
        var diagnostics = new DiagnosticManager();
        var parserService = new GdScriptParserService();
        var workerUnit = parserService.parseUnit(Path.of("tmp", "mapped_worker_chain.gd"), """
                class_name MappedWorker
                extends RefCounted
                
                static func build(seed) -> String:
                    return ""
                """, diagnostics);
        var consumerUnit = parserService.parseUnit(Path.of("tmp", "mapped_consumer_chain.gd"), """
                class_name Consumer
                extends RefCounted
                
                func ping(seed):
                    MappedWorker.build(seed)
                """, diagnostics);
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var analysisData = new FrontendSemanticAnalyzer().analyze(
                new FrontendModule(
                        "test_module",
                        List.of(workerUnit, consumerUnit),
                        Map.of("MappedWorker", "RuntimeWorker")
                ),
                registry,
                diagnostics
        );

        var pingFunction = findFunction(consumerUnit.ast(), "ping");
        var callStatement = assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().getFirst());
        var buildStep = findNode(callStatement, AttributeCallStep.class, step -> step.name().equals("build"));
        var resolvedBuild = analysisData.resolvedCalls().get(buildStep);

        assertNotNull(resolvedBuild);
        assertEquals(FrontendCallResolutionStatus.RESOLVED, resolvedBuild.status());
        assertEquals(FrontendCallResolutionKind.STATIC_METHOD, resolvedBuild.callKind());
        assertEquals(FrontendReceiverKind.TYPE_META, resolvedBuild.receiverKind());
        assertTrue(diagnosticsByCategory(analysisData, "sema.call_resolution").isEmpty());
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

        assertNull(analyzed.analysisData().resolvedCalls().get(aliasBuildStep));
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
    void analyzeResolvesTypedLocalPropertyWritePathAfterStabilizationPhase() throws Exception {
        var analyzed = analyze(
                "typed_local_property_write_path_baseline.gd",
                """
                        class_name TypedLocalPropertyWritePathBaseline
                        extends RefCounted
                        
                        class Point:
                            var next: Point = null
                            var marker: int = -1
                        
                        func make_point() -> Point:
                            return Point.new()
                        
                        func write_path(point: Point) -> void:
                            var tail := make_point()
                            tail.next = point
                        """
        );

        var writePath = findFunction(analyzed.unit().ast(), "write_path");
        var tailDeclaration = findNode(
                writePath.body(),
                VariableDeclaration.class,
                declaration -> declaration.name().equals("tail")
        );
        var assignment = assertInstanceOf(
                dev.superice.gdparser.frontend.ast.AssignmentExpression.class,
                assertInstanceOf(ExpressionStatement.class, writePath.body().statements().get(1)).expression()
        );
        var nextStep = findNode(assignment, AttributePropertyStep.class, step -> step.name().equals("next"));

        var initializerType = analyzed.analysisData().expressionTypes().get(tailDeclaration.value());
        assertNotNull(initializerType);
        var resolvedMember = analyzed.analysisData().resolvedMembers().get(nextStep);
        assertNotNull(resolvedMember);

        assertAll(
                () -> assertEquals(FrontendExpressionTypeStatus.RESOLVED, initializerType.status()),
                () -> assertTrue(initializerType.publishedType().getTypeName().endsWith("Point")),
                () -> assertEquals(FrontendMemberResolutionStatus.RESOLVED, resolvedMember.status()),
                () -> assertEquals(FrontendReceiverKind.INSTANCE, resolvedMember.receiverKind()),
                () -> assertTrue(resolvedMember.receiverType().getTypeName().endsWith("Point")),
                () -> assertTrue(resolvedMember.resultType().getTypeName().endsWith("Point")),
                () -> assertTrue(diagnosticsByCategory(analyzed.analysisData(), "sema.member_resolution").isEmpty())
        );
    }

    @Test
    void analyzeResolvesTypedLocalPropertyReadPathAfterStabilizationPhase() throws Exception {
        var analyzed = analyze(
                "typed_local_property_read_path_baseline.gd",
                """
                        class_name TypedLocalPropertyReadPathBaseline
                        extends RefCounted
                        
                        class Point:
                            var next: Point = null
                            var marker: int = -1
                        
                        func make_point() -> Point:
                            return Point.new()
                        
                        func read_path() -> bool:
                            var point := make_point()
                            return point.marker != -1
                        """
        );

        var readPath = findFunction(analyzed.unit().ast(), "read_path");
        var pointDeclaration = findNode(
                readPath.body(),
                VariableDeclaration.class,
                declaration -> declaration.name().equals("point")
        );
        var markerStep = findNode(readPath.body(), AttributePropertyStep.class, step -> step.name().equals("marker"));

        var initializerType = analyzed.analysisData().expressionTypes().get(pointDeclaration.value());
        assertNotNull(initializerType);
        var resolvedMember = analyzed.analysisData().resolvedMembers().get(markerStep);
        assertNotNull(resolvedMember);

        assertAll(
                () -> assertEquals(FrontendExpressionTypeStatus.RESOLVED, initializerType.status()),
                () -> assertTrue(initializerType.publishedType().getTypeName().endsWith("Point")),
                () -> assertEquals(FrontendMemberResolutionStatus.RESOLVED, resolvedMember.status()),
                () -> assertTrue(resolvedMember.receiverType().getTypeName().endsWith("Point")),
                () -> assertEquals("int", resolvedMember.resultType().getTypeName()),
                () -> assertTrue(diagnosticsByCategory(analyzed.analysisData(), "sema.member_resolution").isEmpty())
        );
    }

    @Test
    void analyzeResolvesTypedLocalAliasChainAfterStabilizationPhase() throws Exception {
        var analyzed = analyze(
                "typed_local_alias_chain_baseline.gd",
                """
                        class_name TypedLocalAliasChainBaseline
                        extends RefCounted
                        
                        class Point:
                            var next: Point = null
                            var marker: int = -1
                        
                        func make_point() -> Point:
                            return Point.new()
                        
                        func read_path() -> bool:
                            var a := make_point()
                            var b := a
                            var c := b
                            return c.marker != -1
                        """
        );

        var readPath = findFunction(analyzed.unit().ast(), "read_path");
        var aDeclaration = findNode(readPath.body(), VariableDeclaration.class, declaration -> declaration.name().equals("a"));
        var bDeclaration = findNode(readPath.body(), VariableDeclaration.class, declaration -> declaration.name().equals("b"));
        var cDeclaration = findNode(readPath.body(), VariableDeclaration.class, declaration -> declaration.name().equals("c"));
        var markerStep = findNode(readPath.body(), AttributePropertyStep.class, step -> step.name().equals("marker"));

        var aInitializerType = analyzed.analysisData().expressionTypes().get(aDeclaration.value());
        var bInitializerType = analyzed.analysisData().expressionTypes().get(bDeclaration.value());
        var cInitializerType = analyzed.analysisData().expressionTypes().get(cDeclaration.value());
        assertNotNull(aInitializerType);
        assertNotNull(bInitializerType);
        assertNotNull(cInitializerType);
        var resolvedMember = analyzed.analysisData().resolvedMembers().get(markerStep);
        assertNotNull(resolvedMember);

        // The source-order stabilization pass updates each alias slot before chain binding consumes
        // the next alias or the final member receiver.
        assertAll(
                () -> assertTrue(aInitializerType.publishedType().getTypeName().endsWith("Point")),
                () -> assertTrue(bInitializerType.publishedType().getTypeName().endsWith("Point")),
                () -> assertTrue(cInitializerType.publishedType().getTypeName().endsWith("Point")),
                () -> assertEquals(FrontendMemberResolutionStatus.RESOLVED, resolvedMember.status()),
                () -> assertTrue(resolvedMember.receiverType().getTypeName().endsWith("Point")),
                () -> assertEquals("int", resolvedMember.resultType().getTypeName()),
                () -> assertTrue(diagnosticsByCategory(analyzed.analysisData(), "sema.member_resolution").isEmpty())
        );
    }

    @Test
    void analyzeResolvesComplexInitializerAliasAfterStabilizationPhase() throws Exception {
        var analyzed = analyze(
                "typed_local_complex_initializer_baseline.gd",
                """
                        class_name TypedLocalComplexInitializerBaseline
                        extends RefCounted
                        
                        class Point:
                            var next: Point = null
                            var marker: int = -1
                        
                        class Box:
                            var next: Point = Point.new()
                        
                        class Factory:
                            func make_point(seed: int) -> Box:
                                return Box.new()
                        
                        func read_path(factory: Factory, seed: int) -> bool:
                            var p := factory.make_point(seed).next
                            var q := p
                            return q.marker != -1
                        """
        );

        var readPath = findFunction(analyzed.unit().ast(), "read_path");
        var pDeclaration = findNode(readPath.body(), VariableDeclaration.class, declaration -> declaration.name().equals("p"));
        var qDeclaration = findNode(readPath.body(), VariableDeclaration.class, declaration -> declaration.name().equals("q"));
        var markerStep = findNode(readPath.body(), AttributePropertyStep.class, step -> step.name().equals("marker"));

        var pInitializerType = analyzed.analysisData().expressionTypes().get(pDeclaration.value());
        var qInitializerType = analyzed.analysisData().expressionTypes().get(qDeclaration.value());
        assertNotNull(pInitializerType);
        assertNotNull(qInitializerType);
        var resolvedMember = analyzed.analysisData().resolvedMembers().get(markerStep);
        assertNotNull(resolvedMember);

        // The complex chain initializer is reduced silently before chain binding, so the alias can
        // publish an exact member fact instead of keeping the old dynamic fallback.
        assertAll(
                () -> assertEquals(FrontendExpressionTypeStatus.RESOLVED, pInitializerType.status()),
                () -> assertTrue(pInitializerType.publishedType().getTypeName().endsWith("Point")),
                () -> assertTrue(qInitializerType.publishedType().getTypeName().endsWith("Point")),
                () -> assertEquals(FrontendMemberResolutionStatus.RESOLVED, resolvedMember.status()),
                () -> assertTrue(resolvedMember.receiverType().getTypeName().endsWith("Point")),
                () -> assertEquals("int", resolvedMember.resultType().getTypeName()),
                () -> assertTrue(diagnosticsByCategory(analyzed.analysisData(), "sema.member_resolution").isEmpty())
        );
    }

    @Test
    void analyzeKeepsTrueDynamicLocalAliasOpenAfterStabilizationPhase() throws Exception {
        var analyzed = analyze(
                "typed_local_dynamic_fail_closed_pipeline.gd",
                """
                        class_name TypedLocalDynamicFailClosedPipeline
                        extends RefCounted
                        
                        func ping(dynamic_host):
                            var point := dynamic_host.next
                            return point.member
                        """
        );

        var pingFunction = findFunction(analyzed.unit().ast(), "ping");
        var pointDeclaration = findNode(
                pingFunction.body(),
                VariableDeclaration.class,
                declaration -> declaration.name().equals("point")
        );
        var pointInitializer = pointDeclaration.value();
        assertNotNull(pointInitializer);
        var nextStep = findNode(pointInitializer, AttributePropertyStep.class, step -> step.name().equals("next"));
        var memberStep = findNode(pingFunction.body(), AttributePropertyStep.class, step -> step.name().equals("member"));

        var initializerType = analyzed.analysisData().expressionTypes().get(pointInitializer);
        assertNotNull(initializerType);
        var nextMember = analyzed.analysisData().resolvedMembers().get(nextStep);
        assertNotNull(nextMember);
        var memberMember = analyzed.analysisData().resolvedMembers().get(memberStep);
        assertNotNull(memberMember);

        // True dynamic receivers are runtime-open. Stabilization may preserve the Variant slot, but
        // must not guess an exact receiver type for either the initializer or the later alias read.
        assertAll(
                () -> assertEquals(FrontendExpressionTypeStatus.DYNAMIC, initializerType.status()),
                () -> assertEquals(GdVariantType.VARIANT, initializerType.publishedType()),
                () -> assertEquals(GdVariantType.VARIANT, analyzed.analysisData().slotTypes().get(pointDeclaration)),
                () -> assertEquals(FrontendMemberResolutionStatus.DYNAMIC, nextMember.status()),
                () -> assertEquals(GdVariantType.VARIANT, nextMember.receiverType()),
                () -> assertEquals(FrontendMemberResolutionStatus.DYNAMIC, memberMember.status()),
                () -> assertEquals(GdVariantType.VARIANT, memberMember.receiverType()),
                () -> assertTrue(diagnosticsByCategory(analyzed.analysisData(), "sema.member_resolution").isEmpty()),
                () -> assertTrue(diagnosticsByCategory(analyzed.analysisData(), "sema.unsupported_chain_route").isEmpty())
        );
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

        assertNull(analyzed.analysisData().resolvedMembers().get(payloadStep));
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
        assertEquals("ResolvedStaticRouteChain__sub__Worker", resolvedBuildType.getTypeName());

        var resolvedHandle = analyzed.analysisData().resolvedMembers().get(handleStep);
        assertNotNull(resolvedHandle);
        assertEquals(FrontendMemberResolutionStatus.RESOLVED, resolvedHandle.status());
        assertEquals(FrontendBindingKind.PROPERTY, resolvedHandle.bindingKind());
        assertEquals(FrontendReceiverKind.INSTANCE, resolvedHandle.receiverKind());
        var resolvedHandleType = resolvedHandle.resultType();
        assertNotNull(resolvedHandleType);
        assertEquals("ResolvedStaticRouteChain__sub__Handle", resolvedHandleType.getTypeName());

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
    void analyzePublishesBareBuiltinAndObjectNewConstructorsOnSharedCallSurface() throws Exception {
        var analyzed = analyze(
                "constructor_surface_routes.gd",
                """
                        class_name ConstructorSurfaceRoutes
                        extends RefCounted
                        
                        class Worker:
                            func _init():
                                pass
                        
                        func ping():
                            Array()
                            Vector3i(1, 2, 3)
                            Node.new()
                            Worker.new()
                        """
        );

        var pingFunction = findFunction(analyzed.unit().ast(), "ping");
        var arrayCall = findNode(
                pingFunction,
                CallExpression.class,
                candidate -> candidate.callee() instanceof IdentifierExpression identifier
                        && identifier.name().equals("Array")
        );
        var vectorCall = findNode(
                pingFunction,
                CallExpression.class,
                candidate -> candidate.callee() instanceof IdentifierExpression identifier
                        && identifier.name().equals("Vector3i")
        );
        var newSteps = findNodes(pingFunction, AttributeCallStep.class, step -> step.name().equals("new"));
        assertEquals(2, newSteps.size());
        var nodeNewStep = newSteps.getFirst();
        var workerNewStep = newSteps.getLast();

        var resolvedArray = analyzed.analysisData().resolvedCalls().get(arrayCall);
        var resolvedVector = analyzed.analysisData().resolvedCalls().get(vectorCall);
        var resolvedNode = analyzed.analysisData().resolvedCalls().get(nodeNewStep);
        var resolvedWorker = analyzed.analysisData().resolvedCalls().get(workerNewStep);

        assertAll(
                () -> assertNotNull(resolvedArray),
                () -> assertEquals(FrontendCallResolutionStatus.RESOLVED, resolvedArray.status()),
                () -> assertEquals(FrontendCallResolutionKind.CONSTRUCTOR, resolvedArray.callKind()),
                () -> assertEquals(FrontendReceiverKind.TYPE_META, resolvedArray.receiverKind()),
                () -> assertEquals(List.of(), resolvedArray.argumentTypes()),
                () -> assertNotNull(resolvedVector),
                () -> assertEquals(FrontendCallResolutionStatus.RESOLVED, resolvedVector.status()),
                () -> assertEquals(FrontendCallResolutionKind.CONSTRUCTOR, resolvedVector.callKind()),
                () -> assertEquals(FrontendReceiverKind.TYPE_META, resolvedVector.receiverKind()),
                () -> assertEquals(
                        List.of("int", "int", "int"),
                        resolvedVector.argumentTypes().stream().map(GdType::getTypeName).toList()
                ),
                () -> assertNotNull(resolvedNode),
                () -> assertEquals(FrontendCallResolutionStatus.RESOLVED, resolvedNode.status()),
                () -> assertEquals(FrontendCallResolutionKind.CONSTRUCTOR, resolvedNode.callKind()),
                () -> assertEquals(FrontendReceiverKind.TYPE_META, resolvedNode.receiverKind()),
                () -> assertEquals(List.of(), resolvedNode.argumentTypes()),
                () -> assertNotNull(resolvedWorker),
                () -> assertEquals(FrontendCallResolutionStatus.RESOLVED, resolvedWorker.status()),
                () -> assertEquals(FrontendCallResolutionKind.CONSTRUCTOR, resolvedWorker.callKind()),
                () -> assertEquals(FrontendReceiverKind.TYPE_META, resolvedWorker.receiverKind()),
                () -> assertEquals(List.of(), resolvedWorker.argumentTypes())
        );
        assertTrue(diagnosticsByCategory(analyzed.analysisData(), "sema.call_resolution").isEmpty());
    }

    @Test
    void analyzeKeepsParameterizedGdccConstructorsFailClosed() throws Exception {
        var analyzed = analyze(
                "parameterized_gdcc_constructor_route.gd",
                """
                        class_name ParameterizedGdccConstructorRoute
                        extends RefCounted
                        
                        class Worker:
                            func _init(value: int):
                                pass
                        
                        func ping(seed):
                            Worker.new(seed)
                        """
        );

        var pingFunction = findFunction(analyzed.unit().ast(), "ping");
        var workerNewStep = findNode(pingFunction, AttributeCallStep.class, step -> step.name().equals("new"));
        var failedCall = analyzed.analysisData().resolvedCalls().get(workerNewStep);

        assertAll(
                () -> assertNotNull(failedCall),
                () -> assertEquals(FrontendCallResolutionStatus.FAILED, failedCall.status()),
                () -> assertEquals(FrontendCallResolutionKind.CONSTRUCTOR, failedCall.callKind()),
                () -> assertEquals(FrontendReceiverKind.TYPE_META, failedCall.receiverKind()),
                () -> assertTrue(failedCall.detailReason().contains("does not support arguments"), failedCall.detailReason())
        );
    }

    @Test
    void analyzeTargetsSingleArgVariantBuiltinConstructorsWithoutRelaxingBareObjectRoutes() throws Exception {
        var analyzed = analyze(
                "variant_builtin_constructor_route.gd",
                """
                        class_name VariantBuiltinConstructorRoute
                        extends RefCounted
                        
                        func ping(plain: Array, seed: Variant):
                            int(plain[0])
                            String(plain[0])
                            Array(seed)
                            Dictionary(seed)
                            Node(seed)
                        """
        );

        var pingFunction = findFunction(analyzed.unit().ast(), "ping");
        var intCall = findNode(
                pingFunction,
                CallExpression.class,
                candidate -> candidate.callee() instanceof IdentifierExpression identifier
                        && identifier.name().equals("int")
        );
        var stringCall = findNode(
                pingFunction,
                CallExpression.class,
                candidate -> candidate.callee() instanceof IdentifierExpression identifier
                        && identifier.name().equals("String")
        );
        var arrayCall = findNode(
                pingFunction,
                CallExpression.class,
                candidate -> candidate.callee() instanceof IdentifierExpression identifier
                        && identifier.name().equals("Array")
        );
        var dictionaryCall = findNode(
                pingFunction,
                CallExpression.class,
                candidate -> candidate.callee() instanceof IdentifierExpression identifier
                        && identifier.name().equals("Dictionary")
        );
        var nodeCall = findNode(
                pingFunction,
                CallExpression.class,
                candidate -> candidate.callee() instanceof IdentifierExpression identifier
                        && identifier.name().equals("Node")
        );

        var intType = analyzed.analysisData().expressionTypes().get(intCall);
        var stringType = analyzed.analysisData().expressionTypes().get(stringCall);
        var arrayType = analyzed.analysisData().expressionTypes().get(arrayCall);
        var dictionaryType = analyzed.analysisData().expressionTypes().get(dictionaryCall);
        var nodeType = analyzed.analysisData().expressionTypes().get(nodeCall);
        var resolvedInt = analyzed.analysisData().resolvedCalls().get(intCall);
        var resolvedString = analyzed.analysisData().resolvedCalls().get(stringCall);
        var resolvedArray = analyzed.analysisData().resolvedCalls().get(arrayCall);
        var resolvedDictionary = analyzed.analysisData().resolvedCalls().get(dictionaryCall);
        var failedNode = analyzed.analysisData().resolvedCalls().get(nodeCall);
        var unsafeDiagnostics = diagnosticsByCategory(analyzed.analysisData(), "sema.unsafe_call_argument");

        assertAll(
                () -> {
                    var publishedIntType = intType;
                    assertNotNull(publishedIntType);
                    assertEquals(FrontendExpressionTypeStatus.RESOLVED, publishedIntType.status());
                    var publishedIntCall = resolvedInt;
                    assertNotNull(publishedIntCall);
                    assertEquals(FrontendCallResolutionStatus.RESOLVED, publishedIntCall.status());
                    assertEquals(FrontendCallResolutionKind.CONSTRUCTOR, publishedIntCall.callKind());
                    assertEquals(FrontendReceiverKind.TYPE_META, publishedIntCall.receiverKind());
                    assertEquals(List.of(GdVariantType.VARIANT), publishedIntCall.argumentTypes());
                },
                () -> {
                    var publishedStringType = stringType;
                    assertNotNull(publishedStringType);
                    assertEquals(FrontendExpressionTypeStatus.RESOLVED, publishedStringType.status());
                    var publishedStringCall = resolvedString;
                    assertNotNull(publishedStringCall);
                    assertEquals(FrontendCallResolutionStatus.RESOLVED, publishedStringCall.status());
                    assertEquals(FrontendCallResolutionKind.CONSTRUCTOR, publishedStringCall.callKind());
                    assertEquals(FrontendReceiverKind.TYPE_META, publishedStringCall.receiverKind());
                    assertEquals(List.of(GdVariantType.VARIANT), publishedStringCall.argumentTypes());
                },
                () -> {
                    var publishedArrayType = arrayType;
                    assertNotNull(publishedArrayType);
                    assertEquals(FrontendExpressionTypeStatus.RESOLVED, publishedArrayType.status());
                    var publishedArrayCall = resolvedArray;
                    assertNotNull(publishedArrayCall);
                    assertEquals(FrontendCallResolutionStatus.RESOLVED, publishedArrayCall.status());
                    assertEquals(FrontendCallResolutionKind.CONSTRUCTOR, publishedArrayCall.callKind());
                    assertEquals(FrontendReceiverKind.TYPE_META, publishedArrayCall.receiverKind());
                    assertEquals(List.of(GdVariantType.VARIANT), publishedArrayCall.argumentTypes());
                },
                () -> {
                    var publishedDictionaryType = dictionaryType;
                    assertNotNull(publishedDictionaryType);
                    assertEquals(FrontendExpressionTypeStatus.RESOLVED, publishedDictionaryType.status());
                    var publishedDictionaryCall = resolvedDictionary;
                    assertNotNull(publishedDictionaryCall);
                    assertEquals(FrontendCallResolutionStatus.RESOLVED, publishedDictionaryCall.status());
                    assertEquals(FrontendCallResolutionKind.CONSTRUCTOR, publishedDictionaryCall.callKind());
                    assertEquals(FrontendReceiverKind.TYPE_META, publishedDictionaryCall.receiverKind());
                    assertEquals(List.of(GdVariantType.VARIANT), publishedDictionaryCall.argumentTypes());
                },
                () -> {
                    var publishedNodeType = nodeType;
                    assertNotNull(publishedNodeType);
                    assertEquals(FrontendExpressionTypeStatus.FAILED, publishedNodeType.status());
                    var detailReason = publishedNodeType.detailReason();
                    assertNotNull(detailReason);
                    assertTrue(detailReason.contains("Node.new(...)"));
                    assertNull(failedNode);
                },
                () -> assertEquals(4, unsafeDiagnostics.size()),
                () -> assertEquals(
                        4,
                        unsafeDiagnostics.stream()
                                .filter(diagnostic -> diagnostic.severity() == FrontendDiagnosticSeverity.WARNING)
                                .count()
                ),
                () -> assertTrue(unsafeDiagnostics.stream().allMatch(diagnostic -> diagnostic.message().contains("Variant"))),
                () -> assertTrue(unsafeDiagnostics.stream().anyMatch(diagnostic -> diagnostic.message().contains("int(...)"))),
                () -> assertTrue(unsafeDiagnostics.stream().anyMatch(diagnostic -> diagnostic.message().contains("String(...)"))),
                () -> assertTrue(unsafeDiagnostics.stream().anyMatch(diagnostic -> diagnostic.message().contains("Array(...)"))),
                () -> assertTrue(unsafeDiagnostics.stream().anyMatch(diagnostic -> diagnostic.message().contains("Dictionary(...)"))),
                () -> assertTrue(unsafeDiagnostics.stream().noneMatch(diagnostic -> diagnostic.message().contains("Node(...)")))
        );
    }

    @Test
    void analyzeKeepsGenericBuiltinConstructorRankingFailClosedForMultiArgumentVariantAmbiguity() throws Exception {
        var analyzed = analyze(
                "ambiguous_multi_arg_builtin_constructor_route.gd",
                """
                        class_name AmbiguousMultiArgBuiltinConstructorRoute
                        extends RefCounted
                        
                        func ping(first: Variant, second: Variant):
                            String(first, second)
                        """,
                registryWithAmbiguousStringPairConstructors()
        );

        var pingFunction = findFunction(analyzed.unit().ast(), "ping");
        var stringCall = findNode(
                pingFunction,
                CallExpression.class,
                candidate -> candidate.callee() instanceof IdentifierExpression identifier
                        && identifier.name().equals("String")
        );

        var failedType = analyzed.analysisData().expressionTypes().get(stringCall);
        var failedCall = analyzed.analysisData().resolvedCalls().get(stringCall);

        assertAll(
                () -> assertNotNull(failedType),
                () -> assertEquals(FrontendExpressionTypeStatus.FAILED, failedType.status()),
                () -> {
                    var detailReason = failedType.detailReason();
                    assertNotNull(detailReason);
                    assertTrue(detailReason.contains("Ambiguous constructor overload"));
                },
                () -> assertNotNull(failedCall),
                () -> assertEquals(FrontendCallResolutionStatus.FAILED, failedCall.status()),
                () -> assertEquals(FrontendCallResolutionKind.CONSTRUCTOR, failedCall.callKind()),
                () -> assertEquals(FrontendReceiverKind.TYPE_META, failedCall.receiverKind()),
                () -> {
                    var detailReason = failedCall.detailReason();
                    assertNotNull(detailReason);
                    assertTrue(detailReason.contains("Ambiguous constructor overload"));
                }
        );
    }

    @Test
    void analyzePrefersMoreSpecificBareBuiltinConstructorOverVariantFallback() throws Exception {
        var analyzed = analyze(
                "specific_builtin_constructor_route.gd",
                """
                        class_name SpecificBuiltinConstructorRoute
                        extends RefCounted
                        
                        func ping():
                            String("seed")
                        """,
                registryWithSpecificStringConstructors()
        );

        var pingFunction = findFunction(analyzed.unit().ast(), "ping");
        var stringCall = findNode(
                pingFunction,
                CallExpression.class,
                candidate -> candidate.callee() instanceof IdentifierExpression identifier
                        && identifier.name().equals("String")
        );

        var resolvedType = analyzed.analysisData().expressionTypes().get(stringCall);
        var resolvedCall = analyzed.analysisData().resolvedCalls().get(stringCall);

        assertAll(
                () -> assertNotNull(resolvedType),
                () -> assertEquals(FrontendExpressionTypeStatus.RESOLVED, resolvedType.status()),
                () -> assertNotNull(resolvedCall),
                () -> assertEquals(FrontendCallResolutionStatus.RESOLVED, resolvedCall.status()),
                () -> assertEquals(FrontendCallResolutionKind.CONSTRUCTOR, resolvedCall.callKind()),
                () -> assertEquals(FrontendReceiverKind.TYPE_META, resolvedCall.receiverKind()),
                () -> assertInstanceOf(ExtensionBuiltinClass.ConstructorInfo.class, resolvedCall.declarationSite()),
                () -> assertEquals(
                        "String",
                        assertInstanceOf(
                                ExtensionBuiltinClass.ConstructorInfo.class,
                                resolvedCall.declarationSite()
                        ).arguments().getFirst().type()
                ),
                () -> assertTrue(diagnosticsByCategory(analyzed.analysisData(), "sema.unsafe_call_argument").isEmpty())
        );
    }

    @Test
    void analyzeKeepsNonInstantiableEngineConstructorFailureReasonPrecise() throws Exception {
        var analyzed = analyze(
                "non_instantiable_engine_constructor_route.gd",
                """
                        class_name NonInstantiableEngineConstructorRoute
                        extends RefCounted
                        
                        func ping():
                            Node.new()
                        """,
                registryWithNonInstantiableNode()
        );

        var pingFunction = findFunction(analyzed.unit().ast(), "ping");
        var nodeNewStep = findNode(
                pingFunction,
                AttributeCallStep.class,
                step -> step.name().equals("new")
        );

        var failedCall = analyzed.analysisData().resolvedCalls().get(nodeNewStep);

        assertAll(
                () -> assertNotNull(failedCall),
                () -> assertEquals(FrontendCallResolutionStatus.FAILED, failedCall.status()),
                () -> assertEquals(FrontendCallResolutionKind.CONSTRUCTOR, failedCall.callKind()),
                () -> assertEquals(FrontendReceiverKind.TYPE_META, failedCall.receiverKind()),
                () -> assertTrue(failedCall.detailReason().contains("Node")),
                () -> assertTrue(failedCall.detailReason().contains("is not instantiable"))
        );
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
        // The binary argument is finalized during the bounded retry window, so the suffix member is
        // resolved once instead of cascading into a second dynamic or missing-member diagnostic.
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
                            self.build(preload("res://icon.svg")).length
                        """
        );

        var pingFunction = findFunction(analyzed.unit().ast(), "ping");
        var chainStatement = assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().getFirst());
        var buildStep = findNode(chainStatement, AttributeCallStep.class, step -> step.name().equals("build"));
        var lengthStep = findNode(chainStatement, AttributePropertyStep.class, step -> step.name().equals("length"));

        var deferredCall = analyzed.analysisData().resolvedCalls().get(buildStep);
        assertNotNull(deferredCall);
        // Preload expressions remain outside the current retry surface; after the one retry
        // window, the chain stays deferred and the suffix is not speculatively opened.
        assertEquals(FrontendCallResolutionStatus.DEFERRED, deferredCall.status());
        assertEquals(FrontendCallResolutionKind.INSTANCE_METHOD, deferredCall.callKind());
        assertEquals(0, analyzed.analysisData().resolvedMembers().size());
        assertEquals(1, analyzed.analysisData().resolvedCalls().size());
        assertTrue(analyzed.analysisData().resolvedMembers().get(lengthStep) == null);

        var deferredDiagnostics = diagnosticsByCategory(analyzed.analysisData(), "sema.deferred_chain_resolution");
        assertEquals(1, deferredDiagnostics.size());
        assertTrue(deferredDiagnostics.getFirst().message().contains("Argument #1 type is still deferred"));
        assertTrue(deferredDiagnostics.getFirst().message().contains("Preload expression typing is deferred"));
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
        var publishedBlockedBareCall = analyzed.analysisData().resolvedCalls().get(blockedBareCall);
        assertNotNull(publishedBlockedBareCall);
        assertEquals(FrontendCallResolutionStatus.BLOCKED, publishedBlockedBareCall.status());
        assertEquals(FrontendCallResolutionKind.INSTANCE_METHOD, publishedBlockedBareCall.callKind());
        assertEquals(FrontendReceiverKind.INSTANCE, publishedBlockedBareCall.receiverKind());
        assertEquals(List.of("int"), publishedBlockedBareCall.argumentTypes().stream().map(GdType::getTypeName).toList());
        assertEquals("int", publishedBlockedBareCall.returnType().getTypeName());

        var outerChainType = analyzed.analysisData().expressionTypes().get(chainExpression);
        assertNotNull(outerChainType);
        assertEquals(FrontendExpressionTypeStatus.BLOCKED, outerChainType.status());
        assertNull(outerChainType.publishedType());
        assertTrue(outerChainType.detailReason().contains("Argument #1 is blocked"));

        assertNull(analyzed.analysisData().resolvedCalls().get(consumeStep));
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
        var bareCall = findNode(
                assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().getFirst()),
                CallExpression.class,
                candidate -> candidate.callee() instanceof IdentifierExpression identifier
                        && identifier.name().equals("helper")
        );
        var consumeStep = findNode(
                assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().getFirst()),
                AttributeCallStep.class,
                step -> step.name().equals("consume")
        );
        var resolvedBareCall = analyzed.analysisData().resolvedCalls().get(bareCall);
        assertNotNull(resolvedBareCall);
        assertEquals(FrontendCallResolutionStatus.RESOLVED, resolvedBareCall.status());
        assertEquals(FrontendCallResolutionKind.INSTANCE_METHOD, resolvedBareCall.callKind());
        assertEquals(FrontendReceiverKind.INSTANCE, resolvedBareCall.receiverKind());
        assertEquals(List.of(), resolvedBareCall.argumentTypes());
        assertEquals("int", resolvedBareCall.returnType().getTypeName());

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
    void analyzePublishesInheritedEngineStaticLoadFacts() throws Exception {
        var analyzed = analyze(
                "inherited_engine_static_load_routes.gd",
                """
                        class_name InheritedEngineStaticLoadRoutes
                        extends RefCounted
                        
                        func ping():
                            ChildInput.PARENT_LIMIT
                            ChildInput.PARENT_MOUSE_MODE
                        """,
                new ClassRegistry(inheritedEngineStaticFixtureApi())
        );

        var pingFunction = findFunction(analyzed.unit().ast(), "ping");
        var constantStatement = assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().getFirst());
        var enumStatement = assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().get(1));
        var constantLoad = analyzed.analysisData().resolvedMembers().get(
                findNode(constantStatement, AttributePropertyStep.class, step -> step.name().equals("PARENT_LIMIT"))
        );
        var enumLoad = analyzed.analysisData().resolvedMembers().get(
                findNode(enumStatement, AttributePropertyStep.class, step -> step.name().equals("PARENT_MOUSE_MODE"))
        );

        assertAll(
                () -> assertNotNull(constantLoad),
                () -> assertEquals(FrontendMemberResolutionStatus.RESOLVED, constantLoad.status()),
                () -> assertEquals(FrontendBindingKind.CONSTANT, constantLoad.bindingKind()),
                () -> assertEquals(FrontendReceiverKind.TYPE_META, constantLoad.receiverKind()),
                () -> assertNotNull(constantLoad.resultType()),
                () -> assertEquals("int", constantLoad.resultType().getTypeName()),
                () -> assertNotNull(enumLoad),
                () -> assertEquals(FrontendMemberResolutionStatus.RESOLVED, enumLoad.status()),
                () -> assertEquals(FrontendBindingKind.CONSTANT, enumLoad.bindingKind()),
                () -> assertEquals(FrontendReceiverKind.TYPE_META, enumLoad.receiverKind()),
                () -> assertNotNull(enumLoad.resultType()),
                () -> assertEquals("int", enumLoad.resultType().getTypeName())
        );
        assertTrue(diagnosticsByCategory(analyzed.analysisData(), "sema.member_resolution").isEmpty());
        assertTrue(diagnosticsByCategory(analyzed.analysisData(), "sema.unsupported_chain_route").isEmpty());
    }

    @Test
    void analyzePublishesBuiltinInstancePropertyFactsAndKeepsMissingMemberAsFailure() throws Exception {
        var analyzed = analyze(
                "builtin_instance_property_routes.gd",
                """
                        class_name BuiltinInstancePropertyRoutes
                        extends RefCounted
                        
                        func ping(vector: Vector3):
                            vector.x
                            Basis.IDENTITY.x
                            vector.missing
                        """
        );

        var pingFunction = findFunction(analyzed.unit().ast(), "ping");
        var vectorStatement = assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().getFirst());
        var basisStatement = assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().get(1));
        var missingStatement = assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().get(2));

        var vectorX = analyzed.analysisData().resolvedMembers().get(
                findNode(vectorStatement, AttributePropertyStep.class, step -> step.name().equals("x"))
        );
        assertNotNull(vectorX);
        assertEquals(FrontendMemberResolutionStatus.RESOLVED, vectorX.status());
        assertEquals(FrontendBindingKind.PROPERTY, vectorX.bindingKind());
        assertEquals(FrontendReceiverKind.INSTANCE, vectorX.receiverKind());
        assertNotNull(vectorX.resultType());
        assertEquals("float", vectorX.resultType().getTypeName());

        var basisX = analyzed.analysisData().resolvedMembers().get(
                findNode(basisStatement, AttributePropertyStep.class, step -> step.name().equals("x"))
        );
        assertNotNull(basisX);
        assertEquals(FrontendMemberResolutionStatus.RESOLVED, basisX.status());
        assertEquals(FrontendBindingKind.PROPERTY, basisX.bindingKind());
        assertEquals(FrontendReceiverKind.INSTANCE, basisX.receiverKind());
        assertNotNull(basisX.resultType());
        assertEquals("Vector3", basisX.resultType().getTypeName());

        var missingMember = analyzed.analysisData().resolvedMembers().get(
                findNode(missingStatement, AttributePropertyStep.class, step -> step.name().equals("missing"))
        );
        assertNotNull(missingMember);
        assertEquals(FrontendMemberResolutionStatus.FAILED, missingMember.status());
        assertEquals(FrontendBindingKind.UNKNOWN, missingMember.bindingKind());
        assertEquals(FrontendReceiverKind.INSTANCE, missingMember.receiverKind());
        assertTrue(missingMember.detailReason().contains("missing"));
        assertTrue(missingMember.detailReason().contains("Vector3"));

        assertEquals(1, diagnosticsByCategory(analyzed.analysisData(), "sema.member_resolution").size());
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

    @Test
    void analyzeSelectsChainOverloadByContainerLiteralElementBoundary() throws Exception {
        var analyzed = analyze(
                "chain_container_literal_overload.gd",
                """
                        class_name ChainContainerLiteralOverload
                        extends RefCounted
                        
                        func take(values: Array[int]) -> int:
                            return 1
                        
                        func take(values: Array[String]) -> int:
                            return 2
                        
                        func ping():
                            self.take([1])
                            self.take(["x"])
                        """
        );

        var pingFunction = findFunction(analyzed.unit().ast(), "ping");
        var firstStatement = assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().getFirst());
        var secondStatement = assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().get(1));
        var firstStep = findNode(firstStatement, AttributeCallStep.class, step -> step.name().equals("take"));
        var secondStep = findNode(secondStatement, AttributeCallStep.class, step -> step.name().equals("take"));

        var firstCall = analyzed.analysisData().resolvedCalls().get(firstStep);
        var secondCall = analyzed.analysisData().resolvedCalls().get(secondStep);
        assertNotNull(firstCall);
        assertNotNull(secondCall);
        assertEquals(FrontendCallResolutionStatus.RESOLVED, firstCall.status());
        assertEquals(FrontendCallResolutionStatus.RESOLVED, secondCall.status());
        assertEquals("Array[int]", firstCall.argumentTypes().getFirst().getTypeName());
        assertEquals("Array[String]", secondCall.argumentTypes().getFirst().getTypeName());
        assertNotNull(firstCall.exactCallableBoundary());
        assertEquals("Array[int]", firstCall.exactCallableBoundary().fixedParameterTypes().getFirst().getTypeName());
        assertNotNull(secondCall.exactCallableBoundary());
        assertEquals("Array[String]", secondCall.exactCallableBoundary().fixedParameterTypes().getFirst().getTypeName());
        assertTrue(diagnosticsByCategory(analyzed.analysisData(), "sema.call_resolution").isEmpty());
    }

    @Test
    void analyzeKeepsSameRankTypedContainerChainOverloadAmbiguous() throws Exception {
        var analyzed = analyze(
                "chain_container_literal_same_rank_ambiguous.gd",
                """
                        class_name ChainContainerLiteralSameRankAmbiguous
                        extends RefCounted
                        
                        func take(values: Array[int]) -> int:
                            return 1
                        
                        func take(values: Array[String]) -> int:
                            return 2
                        
                        func ping(payload):
                            self.take(payload)
                        """
        );

        var pingFunction = findFunction(analyzed.unit().ast(), "ping");
        var statement = assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().getFirst());
        var takeStep = findNode(statement, AttributeCallStep.class, step -> step.name().equals("take"));
        var resolvedCall = analyzed.analysisData().resolvedCalls().get(takeStep);
        assertNotNull(resolvedCall);
        // Variant payload ranks equally against Array[int] and Array[String]; must not pick by declaration order.
        assertEquals(FrontendCallResolutionStatus.DYNAMIC, resolvedCall.status());
    }

    @Test
    void analyzePublishesContextualArgumentTypesForStaticMethodChainLiteral() throws Exception {
        var analyzed = analyze(
                "static_chain_container_literal.gd",
                """
                        class_name StaticChainContainerLiteral
                        extends RefCounted
                        
                        static func take(values: Array[int]) -> int:
                            return 1
                        
                        static func take(values: Array[String]) -> int:
                            return 2
                        
                        func ping():
                            StaticChainContainerLiteral.take([1])
                        """
        );

        var pingFunction = findFunction(analyzed.unit().ast(), "ping");
        var statement = assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().getFirst());
        var takeStep = findNode(statement, AttributeCallStep.class, step -> step.name().equals("take"));
        var resolvedCall = analyzed.analysisData().resolvedCalls().get(takeStep);
        assertNotNull(resolvedCall);
        assertEquals(FrontendCallResolutionStatus.RESOLVED, resolvedCall.status());
        assertEquals(FrontendCallResolutionKind.STATIC_METHOD, resolvedCall.callKind());
        assertEquals("Array[int]", resolvedCall.argumentTypes().getFirst().getTypeName());
        assertNotNull(resolvedCall.exactCallableBoundary());
        assertEquals("Array[int]", resolvedCall.exactCallableBoundary().fixedParameterTypes().getFirst().getTypeName());
    }

    @Test
    void analyzeAcceptsEmptyContainerLiteralOnTypedChainParameter() throws Exception {
        var analyzed = analyze(
                "chain_empty_container_literal.gd",
                """
                        class_name ChainEmptyContainerLiteral
                        extends RefCounted
                        
                        func take(values: Array[int]) -> int:
                            return 1
                        
                        func ping():
                            self.take([])
                        """
        );

        var pingFunction = findFunction(analyzed.unit().ast(), "ping");
        var statement = assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().getFirst());
        var takeStep = findNode(statement, AttributeCallStep.class, step -> step.name().equals("take"));
        var resolvedCall = analyzed.analysisData().resolvedCalls().get(takeStep);
        assertNotNull(resolvedCall);
        assertEquals(FrontendCallResolutionStatus.RESOLVED, resolvedCall.status(), String.valueOf(resolvedCall.detailReason()));
        assertEquals("Array[int]", resolvedCall.argumentTypes().getFirst().getTypeName());
    }

    @Test
    void analyzePrefersTypedEmptyLiteralOverVariantWhenSecondArgumentPresent() throws Exception {
        // Empty literal is vacuously best against Array[int]; Variant overload loses under shared
        // literalAggregateRank (same path as bare/ctor). Also exercises multi-arg aggregation.
        var analyzed = analyze(
                "chain_empty_literal_plus_second_arg.gd",
                """
                        class_name ChainEmptyLiteralPlusSecondArg
                        extends RefCounted
                        
                        func take(values: Array[int], flag: int) -> int:
                            return 1
                        
                        func take(values, flag: int) -> int:
                            return 2
                        
                        func ping():
                            self.take([], 1)
                        """
        );

        var pingFunction = findFunction(analyzed.unit().ast(), "ping");
        var statement = assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().getFirst());
        var takeStep = findNode(statement, AttributeCallStep.class, step -> step.name().equals("take"));
        var resolvedCall = analyzed.analysisData().resolvedCalls().get(takeStep);
        assertNotNull(resolvedCall);
        assertEquals(FrontendCallResolutionStatus.RESOLVED, resolvedCall.status(), String.valueOf(resolvedCall.detailReason()));
        assertEquals("Array[int]", resolvedCall.argumentTypes().getFirst().getTypeName());
        assertNotNull(resolvedCall.exactCallableBoundary());
        assertEquals("Array[int]", resolvedCall.exactCallableBoundary().fixedParameterTypes().getFirst().getTypeName());
    }

    @Test
    void analyzeKeepsStaticSameRankTypedContainerAmbiguousAsFailed() throws Exception {
        var analyzed = analyze(
                "static_chain_container_literal_same_rank.gd",
                """
                        class_name StaticChainContainerLiteralSameRank
                        extends RefCounted
                        
                        static func take(values: Array[int]) -> int:
                            return 1
                        
                        static func take(values: Array[String]) -> int:
                            return 2
                        
                        func ping(payload):
                            StaticChainContainerLiteralSameRank.take(payload)
                        """
        );

        var pingFunction = findFunction(analyzed.unit().ast(), "ping");
        var statement = assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().getFirst());
        var takeStep = findNode(statement, AttributeCallStep.class, step -> step.name().equals("take"));
        var resolvedCall = analyzed.analysisData().resolvedCalls().get(takeStep);
        assertNotNull(resolvedCall);
        // Static/builtin same-rank typed-container overloads stay fail-closed (not DYNAMIC, not
        // declaration-order pick). Exact message text may include either "Ambiguous overload" or
        // the outer method-lookup wrapper.
        assertEquals(FrontendCallResolutionStatus.FAILED, resolvedCall.status());
        assertNotNull(resolvedCall.detailReason());
        assertFalse(resolvedCall.detailReason().isBlank());
    }

    @Test
    void analyzePrefersFewerOmittedDefaultsOnChainWhenLiteralAggregatesTie() throws Exception {
        // After literalAggregateRank ties, chain fallback must match bare: fewer omitted trailing
        // defaults wins — unary take(Array[int]) omits 0 vs binary omits 1 when calling take([1]).
        var analyzed = analyze(
                "chain_literal_omitted_defaults.gd",
                """
                        class_name ChainLiteralOmittedDefaults
                        extends RefCounted
                        
                        func take(values: Array[int]) -> int:
                            return 1
                        
                        func take(values: Array[int], flag: int = 0) -> int:
                            return 2
                        
                        func ping():
                            self.take([1])
                        """
        );

        var pingFunction = findFunction(analyzed.unit().ast(), "ping");
        var statement = assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().getFirst());
        var takeStep = findNode(statement, AttributeCallStep.class, step -> step.name().equals("take"));
        var resolvedCall = analyzed.analysisData().resolvedCalls().get(takeStep);
        assertNotNull(resolvedCall);
        assertEquals(FrontendCallResolutionStatus.RESOLVED, resolvedCall.status(), String.valueOf(resolvedCall.detailReason()));
        assertNotNull(resolvedCall.exactCallableBoundary());
        assertEquals(1, resolvedCall.exactCallableBoundary().fixedParameterTypes().size());
        assertEquals("Array[int]", resolvedCall.exactCallableBoundary().fixedParameterTypes().getFirst().getTypeName());
    }

    @Test
    void analyzeResolvesMixedLiteralAndScalarChainViaSharedAggregateThenFallback() throws Exception {
        // Multi-arg mixed literal/scalar:
        //   take(Array[float], int) vs take(Array[int], float) with take([1], 1)
        // literalAggregateRank ties on (worst, total); step-4 classic matrix fallback then prefers
        // int→int over int→float, selecting Array[float]+int. Chain must use the same aggregate
        // path as bare/ctor (not packed-int min alone).
        var analyzed = analyze(
                "chain_mixed_literal_scalar_aggregate.gd",
                """
                        class_name ChainMixedLiteralScalarAggregate
                        extends RefCounted
                        
                        func take(values: Array[float], flag: int) -> int:
                            return 1
                        
                        func take(values: Array[int], flag: float) -> int:
                            return 2
                        
                        func ping():
                            self.take([1], 1)
                        """
        );

        var pingFunction = findFunction(analyzed.unit().ast(), "ping");
        var statement = assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().getFirst());
        var takeStep = findNode(statement, AttributeCallStep.class, step -> step.name().equals("take"));
        var resolvedCall = analyzed.analysisData().resolvedCalls().get(takeStep);
        assertNotNull(resolvedCall);
        assertEquals(FrontendCallResolutionStatus.RESOLVED, resolvedCall.status(), String.valueOf(resolvedCall.detailReason()));
        assertEquals("Array[float]", resolvedCall.argumentTypes().getFirst().getTypeName());
        assertNotNull(resolvedCall.exactCallableBoundary());
        assertEquals("Array[float]", resolvedCall.exactCallableBoundary().fixedParameterTypes().getFirst().getTypeName());
        assertEquals("int", resolvedCall.exactCallableBoundary().fixedParameterTypes().get(1).getTypeName());
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
    ) {
        var diagnostics = new DiagnosticManager();
        var parserService = new GdScriptParserService();
        var unit = parserService.parseUnit(Path.of("tmp", fileName), source, diagnostics);
        var analysisData = new FrontendSemanticAnalyzer().analyze(
                new FrontendModule("test_module", List.of(unit), topLevelCanonicalNameMap),
                registry,
                diagnostics
        );
        return new AnalyzedScript(unit, analysisData);
    }

    private static @NotNull ClassRegistry registryWithKeyedStringBuiltin() throws Exception {
        var api = ExtensionApiLoader.loadDefault();
        var patchedBuiltins = api.builtinClasses().stream()
                .map(FrontendBodyOwnerProceduresChainBindingTest::withKeyedStringBuiltin)
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

    private static @NotNull ClassRegistry registryWithAmbiguousStringPairConstructors() throws Exception {
        var api = ExtensionApiLoader.loadDefault();
        var patchedBuiltins = api.builtinClasses().stream()
                .map(FrontendBodyOwnerProceduresChainBindingTest::withAmbiguousStringPairConstructors)
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

    private static @NotNull ClassRegistry registryWithSpecificStringConstructors() throws Exception {
        var api = ExtensionApiLoader.loadDefault();
        var patchedBuiltins = api.builtinClasses().stream()
                .map(FrontendBodyOwnerProceduresChainBindingTest::withSpecificStringConstructors)
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

    private static @NotNull ClassRegistry registryWithNonInstantiableNode() throws Exception {
        var api = ExtensionApiLoader.loadDefault();
        var patchedClasses = api.classes().stream()
                .map(FrontendBodyOwnerProceduresChainBindingTest::withNonInstantiableNode)
                .toList();
        return new ClassRegistry(new ExtensionAPI(
                api.header(),
                api.builtinClassSizes(),
                api.builtinClassMemberOffsets(),
                api.globalEnums(),
                api.utilityFunctions(),
                api.builtinClasses(),
                patchedClasses,
                api.singletons(),
                api.nativeStructures()
        ));
    }

    private static @NotNull ExtensionAPI inheritedEngineStaticFixtureApi() throws Exception {
        var api = ExtensionApiLoader.loadDefault();
        var parentClass = new ExtensionGdClass(
                "BaseInput",
                false,
                true,
                "Object",
                "core",
                List.of(new ExtensionGdClass.ClassEnum(
                        "MouseMode", false, List.of(new ExtensionEnumValue("PARENT_MOUSE_MODE", 7))
                )),
                List.of(),
                List.of(),
                List.of(),
                List.of(new ExtensionGdClass.ConstantInfo("PARENT_LIMIT", "42"))
        );
        var childClass = new ExtensionGdClass(
                "ChildInput",
                false,
                true,
                "BaseInput",
                "core",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
        var patchedClasses = new ArrayList<>(api.classes());
        patchedClasses.add(parentClass);
        patchedClasses.add(childClass);
        return new ExtensionAPI(
                api.header(),
                api.builtinClassSizes(),
                api.builtinClassMemberOffsets(),
                api.globalEnums(),
                api.utilityFunctions(),
                api.builtinClasses(),
                patchedClasses,
                api.singletons(),
                api.nativeStructures()
        );
    }

    /// Keep one synthetic two-arg ambiguity alive so the single-arg Variant constructor shortcut
    /// stays tightly scoped and does not erase generic constructor-ranking failures.
    private static @NotNull ExtensionBuiltinClass withAmbiguousStringPairConstructors(
            @NotNull ExtensionBuiltinClass builtinClass
    ) {
        if (!builtinClass.name().equals("String")) {
            return builtinClass;
        }
        return new ExtensionBuiltinClass(
                builtinClass.name(),
                builtinClass.isKeyed(),
                builtinClass.operators(),
                builtinClass.methods(),
                builtinClass.enums(),
                List.of(
                        new ExtensionBuiltinClass.ConstructorInfo(
                                "String",
                                0,
                                List.of(
                                        new ExtensionFunctionArgument("first", "int", null, null),
                                        new ExtensionFunctionArgument("second", "String", null, null)
                                )
                        ),
                        new ExtensionBuiltinClass.ConstructorInfo(
                                "String",
                                1,
                                List.of(
                                        new ExtensionFunctionArgument("first", "String", null, null),
                                        new ExtensionFunctionArgument("second", "int", null, null)
                                )
                        )
                ),
                builtinClass.members(),
                builtinClass.constants()
        );
    }

    private static @NotNull ExtensionBuiltinClass withSpecificStringConstructors(@NotNull ExtensionBuiltinClass builtinClass) {
        if (!builtinClass.name().equals("String")) {
            return builtinClass;
        }
        return new ExtensionBuiltinClass(
                builtinClass.name(),
                builtinClass.isKeyed(),
                builtinClass.operators(),
                builtinClass.methods(),
                builtinClass.enums(),
                List.of(
                        new ExtensionBuiltinClass.ConstructorInfo(
                                "String",
                                0,
                                List.of(new ExtensionFunctionArgument("value", "Variant", null, null))
                        ),
                        new ExtensionBuiltinClass.ConstructorInfo(
                                "String",
                                1,
                                List.of(new ExtensionFunctionArgument("value", "String", null, null))
                        )
                ),
                builtinClass.members(),
                builtinClass.constants()
        );
    }

    private static @NotNull ExtensionGdClass withNonInstantiableNode(@NotNull ExtensionGdClass gdClass) {
        if (!gdClass.name().equals("Node")) {
            return gdClass;
        }
        return new ExtensionGdClass(
                gdClass.name(),
                gdClass.isRefcounted(),
                false,
                gdClass.inherits(),
                gdClass.apiType(),
                gdClass.enums(),
                gdClass.methods(),
                gdClass.signals(),
                gdClass.properties(),
                gdClass.constants()
        );
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
