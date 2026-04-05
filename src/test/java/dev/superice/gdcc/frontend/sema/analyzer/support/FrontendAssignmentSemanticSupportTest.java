package dev.superice.gdcc.frontend.sema.analyzer.support;

import dev.superice.gdcc.frontend.diagnostic.DiagnosticManager;
import dev.superice.gdcc.frontend.parse.FrontendModule;
import dev.superice.gdcc.frontend.parse.GdScriptParserService;
import dev.superice.gdcc.frontend.sema.FrontendAnalysisData;
import dev.superice.gdcc.frontend.sema.FrontendExpressionTypeStatus;
import dev.superice.gdcc.frontend.sema.analyzer.FrontendSemanticAnalyzer;
import dev.superice.gdcc.gdextension.ExtensionAPI;
import dev.superice.gdcc.gdextension.ExtensionApiLoader;
import dev.superice.gdcc.gdextension.ExtensionGdClass;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.scope.ResolveRestriction;
import dev.superice.gdcc.type.GdFloatType;
import dev.superice.gdcc.type.GdIntType;
import dev.superice.gdcc.type.GdNilType;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdVariantType;
import dev.superice.gdcc.type.GdVoidType;
import dev.superice.gdparser.frontend.ast.AssignmentExpression;
import dev.superice.gdparser.frontend.ast.ExpressionStatement;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.Node;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontendAssignmentSemanticSupportTest {
    @Test
    void resolveAssignmentExpressionTypePublishesResolvedVoidForSupportedTargets() throws Exception {
        var analyzed = analyze(
                "assignment_semantic_support_positive.gd",
                """
                        class_name AssignmentSemanticSupportPositive
                        extends RefCounted
                        
                        var hp: int = 0
                        
                        class Holder:
                            var values: Array[int] = []
                        
                        func ping(values: Array[int], holder: Holder):
                            hp = 1
                            self.hp = 2
                            values[0] = 3
                            holder.values[0] = 4
                        """
        );

        var support = createSupport(analyzed, ResolveRestriction.instanceContext(), false);
        var publishedResolver = publishedExpressionResolver(analyzed);
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
            var result = support.resolveAssignmentExpressionType(
                    assignment,
                    FrontendAssignmentSemanticSupport.AssignmentUsage.STATEMENT_ROOT,
                    publishedResolver,
                    false
            );
            assertEquals(FrontendExpressionTypeStatus.RESOLVED, result.expressionType().status());
            assertEquals(GdVoidType.VOID, result.expressionType().publishedType());
        }
    }

    @Test
    void resolveAssignmentExpressionTypeRejectsIllegalTargetsAndValueRequiredUsage() throws Exception {
        var analyzed = analyze(
                "assignment_semantic_support_negative.gd",
                """
                        class_name AssignmentSemanticSupportNegative
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
                            values[0] = 1
                        """
        );

        var support = createSupport(analyzed, ResolveRestriction.instanceContext(), false);
        var publishedResolver = publishedExpressionResolver(analyzed);
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
        var valueRequiredAssignment = assertInstanceOf(
                AssignmentExpression.class,
                assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().get(4)).expression()
        );

        var signalResult = support.resolveAssignmentExpressionType(
                signalAssignment,
                FrontendAssignmentSemanticSupport.AssignmentUsage.STATEMENT_ROOT,
                publishedResolver,
                false
        );
        assertTrue(signalResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.FAILED, signalResult.expressionType().status());
        assertTrue(signalResult.expressionType().detailReason().contains("Signal"));

        var typeMetaResult = support.resolveAssignmentExpressionType(
                typeMetaAssignment,
                FrontendAssignmentSemanticSupport.AssignmentUsage.STATEMENT_ROOT,
                publishedResolver,
                false
        );
        assertTrue(typeMetaResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.FAILED, typeMetaResult.expressionType().status());
        assertTrue(typeMetaResult.expressionType().detailReason().contains("Type-meta"));

        var callableResult = support.resolveAssignmentExpressionType(
                callableAssignment,
                FrontendAssignmentSemanticSupport.AssignmentUsage.STATEMENT_ROOT,
                publishedResolver,
                false
        );
        assertTrue(callableResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.FAILED, callableResult.expressionType().status());
        assertTrue(callableResult.expressionType().detailReason().contains("cannot be assigned"));

        var assignabilityResult = support.resolveAssignmentExpressionType(
                assignabilityFailure,
                FrontendAssignmentSemanticSupport.AssignmentUsage.STATEMENT_ROOT,
                publishedResolver,
                false
        );
        assertTrue(assignabilityResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.FAILED, assignabilityResult.expressionType().status());
        assertTrue(assignabilityResult.expressionType().detailReason().contains("not assignable"));

        var valueRequiredResult = support.resolveAssignmentExpressionType(
                valueRequiredAssignment,
                FrontendAssignmentSemanticSupport.AssignmentUsage.VALUE_REQUIRED,
                publishedResolver,
                false
        );
        assertTrue(valueRequiredResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.FAILED, valueRequiredResult.expressionType().status());
        assertTrue(valueRequiredResult.expressionType().detailReason().contains("ordinary value"));
    }

    @Test
    void resolveAssignmentExpressionTypeSupportsClosedCompoundOperatorSetButKeepsValueAndWritebackChecks()
            throws Exception {
        var analyzed = analyze(
                "assignment_semantic_support_compound.gd",
                """
                        class_name AssignmentSemanticSupportCompound
                        extends RefCounted
                        
                        var hp: int = 0
                        var payload
                        
                        func ping(values: Array[int], raw_array: Array):
                            hp += 1
                            self.hp -= 1
                            values[0] |= 1
                            payload += 1
                            values += raw_array
                        """
        );

        var support = createSupport(analyzed, ResolveRestriction.instanceContext(), false);
        var publishedResolver = publishedExpressionResolver(analyzed);
        var pingFunction = findFunction(analyzed.ast(), "ping");
        var assignments = findNodes(pingFunction, AssignmentExpression.class, _ -> true);

        for (var successfulAssignment : assignments.subList(0, 4)) {
            var result = support.resolveAssignmentExpressionType(
                    successfulAssignment,
                    FrontendAssignmentSemanticSupport.AssignmentUsage.STATEMENT_ROOT,
                    publishedResolver,
                    false
            );
            assertEquals(FrontendExpressionTypeStatus.RESOLVED, result.expressionType().status());
            assertEquals(GdVoidType.VOID, result.expressionType().publishedType());
        }

        var assignabilityFailure = support.resolveAssignmentExpressionType(
                assignments.get(4),
                FrontendAssignmentSemanticSupport.AssignmentUsage.STATEMENT_ROOT,
                publishedResolver,
                false
        );
        assertTrue(assignabilityFailure.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.FAILED, assignabilityFailure.expressionType().status());
        assertTrue(assignabilityFailure.expressionType().detailReason().contains("not assignable"));
        assertTrue(assignabilityFailure.expressionType().detailReason().contains("Array[int]"));

        var valueRequiredResult = support.resolveAssignmentExpressionType(
                assignments.getFirst(),
                FrontendAssignmentSemanticSupport.AssignmentUsage.VALUE_REQUIRED,
                publishedResolver,
                false
        );
        assertTrue(valueRequiredResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.FAILED, valueRequiredResult.expressionType().status());
        assertTrue(valueRequiredResult.expressionType().detailReason().contains("ordinary value"));
    }

    @Test
    void resolveAssignmentExpressionTypeKeepsUnknownCompoundOperatorsFailClosed() throws Exception {
        var analyzed = analyze(
                "assignment_semantic_support_unknown_compound.gd",
                """
                        class_name AssignmentSemanticSupportUnknownCompound
                        extends RefCounted
                        
                        var hp: int = 0
                        
                        func ping():
                            hp += 1
                        """
        );

        var support = createSupport(analyzed, ResolveRestriction.instanceContext(), false);
        var publishedResolver = publishedExpressionResolver(analyzed);
        var compoundAssignment = findNode(analyzed.ast(), AssignmentExpression.class, assignment -> "+=".equals(assignment.operator()));
        var syntheticUnknownOperator = new AssignmentExpression(
                "??=",
                compoundAssignment.left(),
                compoundAssignment.right(),
                compoundAssignment.range()
        );

        var result = support.resolveAssignmentExpressionType(
                syntheticUnknownOperator,
                FrontendAssignmentSemanticSupport.AssignmentUsage.STATEMENT_ROOT,
                publishedResolver,
                false
        );

        assertTrue(result.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.UNSUPPORTED, result.expressionType().status());
        assertTrue(result.expressionType().detailReason().contains("Compound assignment operator '??='"));
    }

    @Test
    void resolveAssignmentExpressionTypeUsesSharedReadonlyPropertyContractForBareAndAttributeRoutes() throws Exception {
        var analyzed = analyze(
                "assignment_semantic_support_readonly_property.gd",
                """
                        class_name AssignmentSemanticSupportReadonlyProperty
                        extends ReadonlyBase
                        
                        func ping():
                            locked = 1
                            self.locked = 1
                        """,
                registryWithReadonlyEngineBase()
        );

        var support = createSupport(analyzed, ResolveRestriction.instanceContext(), false);
        var publishedResolver = publishedExpressionResolver(analyzed);
        var pingFunction = findFunction(analyzed.ast(), "ping");
        var bareAssignment = assertInstanceOf(
                AssignmentExpression.class,
                assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().get(0)).expression()
        );
        var attributeAssignment = assertInstanceOf(
                AssignmentExpression.class,
                assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().get(1)).expression()
        );

        var bareResult = support.resolveAssignmentExpressionType(
                bareAssignment,
                FrontendAssignmentSemanticSupport.AssignmentUsage.STATEMENT_ROOT,
                publishedResolver,
                false
        );
        var attributeResult = support.resolveAssignmentExpressionType(
                attributeAssignment,
                FrontendAssignmentSemanticSupport.AssignmentUsage.STATEMENT_ROOT,
                publishedResolver,
                false
        );

        assertTrue(bareResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.FAILED, bareResult.expressionType().status());
        assertEquals("Property 'locked' is not writable", bareResult.expressionType().detailReason());

        assertTrue(attributeResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.FAILED, attributeResult.expressionType().status());
        assertEquals("Property 'locked' is not writable", attributeResult.expressionType().detailReason());
    }

    @Test
    void checkAssignmentCompatiblePublishesFrontendWidePublicContract() throws Exception {
        var support = createSupport(
                analyze(
                        "assignment_semantic_support_public_gate.gd",
                        """
                                class_name AssignmentSemanticSupportPublicGate
                                extends RefCounted
                                
                                func ping():
                                    pass
                                """
                ),
                ResolveRestriction.instanceContext(),
                false
        );

        assertTrue(support.checkAssignmentCompatible(
                GdVariantType.VARIANT,
                GdIntType.INT
        ));
        assertTrue(support.checkAssignmentCompatible(
                GdIntType.INT,
                GdVariantType.VARIANT
        ));
        assertTrue(support.checkAssignmentCompatible(
                GdObjectType.OBJECT,
                GdNilType.NIL
        ));
        assertTrue(!support.checkAssignmentCompatible(
                GdFloatType.FLOAT,
                GdIntType.INT
        ));
    }

    @Test
    void resolveAssignmentExpressionTypeAcceptsVariantAndDynamicTargetsButKeepsImplicitConversionsStrict()
            throws Exception {
        var analyzed = analyze(
                "assignment_semantic_support_variant_dynamic.gd",
                """
                        class_name AssignmentSemanticSupportVariantDynamic
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

        var support = createSupport(analyzed, ResolveRestriction.instanceContext(), false);
        var publishedResolver = publishedExpressionResolver(analyzed);
        var assignments = findNodes(findFunction(analyzed.ast(), "ping"), AssignmentExpression.class, _ -> true);

        var localVariantResult = support.resolveAssignmentExpressionType(
                assignments.get(0),
                FrontendAssignmentSemanticSupport.AssignmentUsage.STATEMENT_ROOT,
                publishedResolver,
                false
        );
        var bareVariantPropertyResult = support.resolveAssignmentExpressionType(
                assignments.get(1),
                FrontendAssignmentSemanticSupport.AssignmentUsage.STATEMENT_ROOT,
                publishedResolver,
                false
        );
        var attributeVariantPropertyResult = support.resolveAssignmentExpressionType(
                assignments.get(2),
                FrontendAssignmentSemanticSupport.AssignmentUsage.STATEMENT_ROOT,
                publishedResolver,
                false
        );
        var dynamicTargetResult = support.resolveAssignmentExpressionType(
                assignments.get(3),
                FrontendAssignmentSemanticSupport.AssignmentUsage.STATEMENT_ROOT,
                publishedResolver,
                false
        );
        var strictImplicitConversionFailure = support.resolveAssignmentExpressionType(
                assignments.get(4),
                FrontendAssignmentSemanticSupport.AssignmentUsage.STATEMENT_ROOT,
                publishedResolver,
                false
        );

        for (var successfulResult : List.of(
                localVariantResult,
                bareVariantPropertyResult,
                attributeVariantPropertyResult,
                dynamicTargetResult
        )) {
            assertEquals(FrontendExpressionTypeStatus.RESOLVED, successfulResult.expressionType().status());
            assertEquals(GdVoidType.VOID, successfulResult.expressionType().publishedType());
        }

        assertTrue(strictImplicitConversionFailure.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.FAILED, strictImplicitConversionFailure.expressionType().status());
        assertTrue(strictImplicitConversionFailure.expressionType().detailReason().contains("not assignable"));
        assertTrue(strictImplicitConversionFailure.expressionType().detailReason().contains("float"));
    }

    @Test
    void resolveAssignmentExpressionTypeAcceptsStableVariantSourcesForConcreteTargets() throws Exception {
        var analyzed = analyze(
                "assignment_semantic_support_variant_source.gd",
                """
                        class_name AssignmentSemanticSupportVariantSource
                        extends RefCounted
                        
                        func ping(any_value: Variant, dynamic_value):
                            var int_slot: int = 0
                            int_slot = any_value
                            int_slot = dynamic_value.ping().length
                            int_slot = "x"
                        """
        );

        var support = createSupport(analyzed, ResolveRestriction.instanceContext(), false);
        var publishedResolver = publishedExpressionResolver(analyzed);
        var assignments = findNodes(findFunction(analyzed.ast(), "ping"), AssignmentExpression.class, _ -> true);

        var exactVariantSource = support.resolveAssignmentExpressionType(
                assignments.get(0),
                FrontendAssignmentSemanticSupport.AssignmentUsage.STATEMENT_ROOT,
                publishedResolver,
                false
        );
        var dynamicVariantSource = support.resolveAssignmentExpressionType(
                assignments.get(1),
                FrontendAssignmentSemanticSupport.AssignmentUsage.STATEMENT_ROOT,
                publishedResolver,
                false
        );
        var strictMismatch = support.resolveAssignmentExpressionType(
                assignments.get(2),
                FrontendAssignmentSemanticSupport.AssignmentUsage.STATEMENT_ROOT,
                publishedResolver,
                false
        );

        for (var successfulResult : List.of(exactVariantSource, dynamicVariantSource)) {
            assertEquals(FrontendExpressionTypeStatus.RESOLVED, successfulResult.expressionType().status());
            assertEquals(GdVoidType.VOID, successfulResult.expressionType().publishedType());
        }

        assertTrue(strictMismatch.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.FAILED, strictMismatch.expressionType().status());
        assertTrue(strictMismatch.expressionType().detailReason().contains("String"));
        assertTrue(strictMismatch.expressionType().detailReason().contains("int"));
    }

    @Test
    void resolveAssignmentExpressionTypeAcceptsNullSourcesForObjectTargetsButKeepsNilToScalarRejected()
            throws Exception {
        var analyzed = analyze(
                "assignment_semantic_support_null_object_source.gd",
                """
                        class_name AssignmentSemanticSupportNullObjectSource
                        extends RefCounted
                        
                        var payload_obj: Object
                        var payload_i: int = 0
                        
                        func ping() -> void:
                            payload_obj = null
                            payload_i = null
                        """
        );

        var support = createSupport(analyzed, ResolveRestriction.instanceContext(), false);
        var publishedResolver = publishedExpressionResolver(analyzed);
        var assignments = findNodes(findFunction(analyzed.ast(), "ping"), AssignmentExpression.class, _ -> true);

        var objectAssignment = support.resolveAssignmentExpressionType(
                assignments.get(0),
                FrontendAssignmentSemanticSupport.AssignmentUsage.STATEMENT_ROOT,
                publishedResolver,
                false
        );
        var scalarMismatch = support.resolveAssignmentExpressionType(
                assignments.get(1),
                FrontendAssignmentSemanticSupport.AssignmentUsage.STATEMENT_ROOT,
                publishedResolver,
                false
        );

        assertEquals(FrontendExpressionTypeStatus.RESOLVED, objectAssignment.expressionType().status());
        assertEquals(GdVoidType.VOID, objectAssignment.expressionType().publishedType());
        assertTrue(scalarMismatch.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.FAILED, scalarMismatch.expressionType().status());
        assertTrue(scalarMismatch.expressionType().detailReason().contains("Nil"));
        assertTrue(scalarMismatch.expressionType().detailReason().contains("int"));
    }

    private @NotNull FrontendAssignmentSemanticSupport createSupport(
            @NotNull AnalyzedScript analyzed,
            @NotNull ResolveRestriction restriction,
            boolean staticContext
    ) {
        var chainReduction = new FrontendChainReductionFacade(
                analyzed.analysisData(),
                analyzed.analysisData().scopesByAst(),
                () -> restriction,
                () -> staticContext,
                () -> null,
                analyzed.classRegistry(),
                (expression, finalizeWindow) -> FrontendChainReductionHelper.ExpressionTypeResult.fromPublished(
                        publishedExpressionResolver(analyzed).resolve(expression, finalizeWindow)
                )
        );
        return new FrontendAssignmentSemanticSupport(
                analyzed.analysisData().symbolBindings(),
                analyzed.analysisData().scopesByAst(),
                analyzed.analysisData().moduleSkeleton(),
                () -> restriction,
                analyzed.classRegistry(),
                chainReduction
        );
    }

    private static @NotNull FrontendExpressionSemanticSupport.NestedExpressionResolver publishedExpressionResolver(
            @NotNull AnalyzedScript analyzed
    ) {
        return (expression, finalizeWindow) -> Objects.requireNonNull(
                analyzed.analysisData().expressionTypes().get(expression),
                "Expected published expression type for " + expression.getClass().getSimpleName()
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
        var diagnostics = new DiagnosticManager();
        var parserService = new GdScriptParserService();
        var unit = parserService.parseUnit(Path.of("tmp", fileName), source, diagnostics);
        var analysisData = new FrontendSemanticAnalyzer().analyze(
                new FrontendModule("test_module", List.of(unit)),
                registry,
                diagnostics
        );
        return new AnalyzedScript(unit.ast(), analysisData, registry);
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

    private static @NotNull FunctionDeclaration findFunction(@NotNull Node root, @NotNull String name) {
        return findNode(root, FunctionDeclaration.class, function -> function.name().equals(name));
    }

    private static <T extends Node> @NotNull T findNode(
            @NotNull Node root,
            @NotNull Class<T> nodeType,
            @NotNull Predicate<T> predicate
    ) {
        return findNodes(root, nodeType, predicate).getFirst();
    }

    private static <T extends Node> @NotNull List<T> findNodes(
            @NotNull Node root,
            @NotNull Class<T> nodeType,
            @NotNull Predicate<T> predicate
    ) {
        var matches = new ArrayList<T>();
        collectNodes(root, nodeType, predicate, matches);
        if (matches.isEmpty()) {
            throw new AssertionError("No node of type " + nodeType.getSimpleName() + " matched the predicate");
        }
        return List.copyOf(matches);
    }

    private static <T extends Node> void collectNodes(
            @NotNull Node node,
            @NotNull Class<T> nodeType,
            @NotNull Predicate<T> predicate,
            @NotNull List<T> matches
    ) {
        if (nodeType.isInstance(node)) {
            var typedNode = nodeType.cast(node);
            if (predicate.test(typedNode)) {
                matches.add(typedNode);
            }
        }
        for (var child : node.getChildren()) {
            collectNodes(child, nodeType, predicate, matches);
        }
    }

    private record AnalyzedScript(
            @NotNull Node ast,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull ClassRegistry classRegistry
    ) {
    }
}
