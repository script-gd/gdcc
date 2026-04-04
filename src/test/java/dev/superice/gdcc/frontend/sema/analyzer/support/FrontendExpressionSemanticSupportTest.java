package dev.superice.gdcc.frontend.sema.analyzer.support;

import dev.superice.gdcc.frontend.diagnostic.DiagnosticManager;
import dev.superice.gdcc.frontend.parse.FrontendModule;
import dev.superice.gdcc.frontend.parse.GdScriptParserService;
import dev.superice.gdcc.frontend.sema.FrontendAnalysisData;
import dev.superice.gdcc.frontend.sema.FrontendCallResolutionKind;
import dev.superice.gdcc.frontend.sema.FrontendCallResolutionStatus;
import dev.superice.gdcc.frontend.sema.FrontendExpressionType;
import dev.superice.gdcc.frontend.sema.FrontendExpressionTypeStatus;
import dev.superice.gdcc.frontend.sema.FrontendReceiverKind;
import dev.superice.gdcc.frontend.sema.analyzer.FrontendSemanticAnalyzer;
import dev.superice.gdcc.gdextension.ExtensionApiLoader;
import dev.superice.gdcc.lir.LirFunctionDef;
import dev.superice.gdcc.lir.LirParameterDef;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.scope.ResolveRestriction;
import dev.superice.gdcc.type.GdCallableType;
import dev.superice.gdcc.type.GdIntType;
import dev.superice.gdcc.type.GdNilType;
import dev.superice.gdcc.type.GdStringType;
import dev.superice.gdcc.type.GdVariantType;
import dev.superice.gdparser.frontend.ast.ArrayExpression;
import dev.superice.gdparser.frontend.ast.AwaitExpression;
import dev.superice.gdparser.frontend.ast.BinaryExpression;
import dev.superice.gdparser.frontend.ast.CallExpression;
import dev.superice.gdparser.frontend.ast.CastExpression;
import dev.superice.gdparser.frontend.ast.ConditionalExpression;
import dev.superice.gdparser.frontend.ast.DictEntry;
import dev.superice.gdparser.frontend.ast.DictionaryExpression;
import dev.superice.gdparser.frontend.ast.Expression;
import dev.superice.gdparser.frontend.ast.ExpressionStatement;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.GetNodeExpression;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.PatternBindingExpression;
import dev.superice.gdparser.frontend.ast.Point;
import dev.superice.gdparser.frontend.ast.PreloadExpression;
import dev.superice.gdparser.frontend.ast.Range;
import dev.superice.gdparser.frontend.ast.SubscriptExpression;
import dev.superice.gdparser.frontend.ast.TypeRef;
import dev.superice.gdparser.frontend.ast.TypeTestExpression;
import dev.superice.gdparser.frontend.ast.UnaryExpression;
import dev.superice.gdparser.frontend.ast.UnknownExpression;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontendExpressionSemanticSupportTest {
    private static final @NotNull Range TINY = new Range(0, 1, new Point(0, 0), new Point(0, 1));

    @Test
    void resolveIdentifierExpressionTypeMaterializesCallableValuesAndRejectsBareTypeMeta() throws Exception {
        var analyzed = analyze(
                "expression_semantic_support_identifiers.gd",
                """
                        class_name ExpressionSemanticSupportIdentifiers
                        extends RefCounted
                        
                        class Worker:
                            static func build() -> int:
                                return 1
                        
                        func helper() -> int:
                            return 1
                        
                        func ping(seed):
                            seed
                            helper
                            Worker
                        """
        );

        var support = createSupport(analyzed, ResolveRestriction.instanceContext(), false);
        var pingFunction = findFunction(analyzed.ast(), "ping");
        var seed = assertInstanceOf(
                IdentifierExpression.class,
                assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().getFirst()).expression()
        );
        var helper = assertInstanceOf(
                IdentifierExpression.class,
                assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().get(1)).expression()
        );
        var worker = assertInstanceOf(
                IdentifierExpression.class,
                assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().get(2)).expression()
        );

        var seedResult = support.resolveIdentifierExpressionType(seed);
        assertFalse(seedResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, seedResult.expressionType().status());
        assertEquals(GdVariantType.VARIANT, seedResult.expressionType().publishedType());

        var helperResult = support.resolveIdentifierExpressionType(helper);
        assertFalse(helperResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, helperResult.expressionType().status());
        assertInstanceOf(GdCallableType.class, helperResult.expressionType().publishedType());

        var workerResult = support.resolveIdentifierExpressionType(worker);
        assertFalse(workerResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.FAILED, workerResult.expressionType().status());
        assertTrue(workerResult.expressionType().detailReason().contains("static route"));
    }

    @Test
    void resolveCallExpressionTypeDistinguishesResolvedBlockedAndUnsupportedCalls() throws Exception {
        var analyzed = analyze(
                "expression_semantic_support_calls.gd",
                """
                        class_name ExpressionSemanticSupportCalls
                        extends RefCounted
                        
                        func helper(value: int) -> int:
                            return value
                        
                        func make_cb() -> Callable:
                            return helper
                        
                        static func ping_static(value: int):
                            helper(value)
                        
                        func ping():
                            helper(1)
                            self.make_cb()()
                        """
        );

        var pingFunction = findFunction(analyzed.ast(), "ping");
        var pingStaticFunction = findFunction(analyzed.ast(), "ping_static");
        var resolvedBareCall = assertInstanceOf(
                CallExpression.class,
                assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().getFirst()).expression()
        );
        var unsupportedDirectCall = assertInstanceOf(
                CallExpression.class,
                assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().get(1)).expression()
        );
        var blockedBareCall = assertInstanceOf(
                CallExpression.class,
                assertInstanceOf(ExpressionStatement.class, pingStaticFunction.body().statements().getFirst()).expression()
        );

        var unrestrictedSupport = createSupport(analyzed, ResolveRestriction.instanceContext(), false);
        var staticSupport = createSupport(analyzed, ResolveRestriction.staticContext(), true);
        var publishedResolver = publishedExpressionResolver(analyzed);

        var resolvedResult = unrestrictedSupport.resolveCallExpressionType(
                resolvedBareCall,
                publishedResolver,
                true,
                false
        );
        var resolvedCall = resolvedResult.publishedCallOrNull();
        assertAll(
                () -> assertTrue(resolvedResult.rootOwnsOutcome()),
                () -> assertEquals(FrontendExpressionTypeStatus.RESOLVED, resolvedResult.expressionType().status()),
                () -> assertEquals("int", resolvedResult.expressionType().publishedType().getTypeName()),
                () -> assertNotNull(resolvedCall),
                () -> assertEquals(FrontendCallResolutionStatus.RESOLVED, resolvedCall.status()),
                () -> assertEquals(FrontendCallResolutionKind.INSTANCE_METHOD, resolvedCall.callKind()),
                () -> assertEquals(FrontendReceiverKind.INSTANCE, resolvedCall.receiverKind()),
                () -> assertEquals(List.of("int"), resolvedCall.argumentTypes().stream().map(type -> type.getTypeName()).toList()),
                () -> assertEquals("int", resolvedCall.returnType().getTypeName()),
                () -> assertNotNull(resolvedCall.declarationSite())
        );

        var blockedResult = staticSupport.resolveCallExpressionType(
                blockedBareCall,
                publishedResolver,
                true,
                false
        );
        var blockedCall = blockedResult.publishedCallOrNull();
        assertAll(
                () -> assertTrue(blockedResult.rootOwnsOutcome()),
                () -> assertEquals(FrontendExpressionTypeStatus.BLOCKED, blockedResult.expressionType().status()),
                () -> assertNotNull(blockedResult.expressionType().publishedType()),
                () -> assertEquals("int", blockedResult.expressionType().publishedType().getTypeName()),
                () -> assertNotNull(blockedCall),
                () -> assertEquals(FrontendCallResolutionStatus.BLOCKED, blockedCall.status()),
                () -> assertEquals(FrontendCallResolutionKind.INSTANCE_METHOD, blockedCall.callKind()),
                () -> assertEquals(FrontendReceiverKind.INSTANCE, blockedCall.receiverKind()),
                () -> assertEquals(List.of("int"), blockedCall.argumentTypes().stream().map(type -> type.getTypeName()).toList()),
                () -> assertEquals("int", blockedCall.returnType().getTypeName()),
                () -> assertNotNull(blockedCall.declarationSite())
        );

        var unsupportedResult = unrestrictedSupport.resolveCallExpressionType(
                unsupportedDirectCall,
                publishedResolver,
                true,
                false
        );
        assertAll(
                () -> assertTrue(unsupportedResult.rootOwnsOutcome()),
                () -> assertEquals(FrontendExpressionTypeStatus.UNSUPPORTED, unsupportedResult.expressionType().status()),
                () -> assertTrue(unsupportedResult.expressionType().detailReason().contains("Direct invocation of callable values")),
                () -> assertNull(unsupportedResult.publishedCallOrNull())
        );
    }

    @Test
    void resolveCallExpressionTypeAcceptsStableVariantSourcesAtFixedParameterBoundaries() throws Exception {
        var analyzed = analyze(
                "expression_semantic_support_variant_calls.gd",
                """
                        class_name ExpressionSemanticSupportVariantCalls
                        extends RefCounted
                        
                        func take_i(value: int) -> int:
                            return value
                        
                        func take_any(value) -> int:
                            return 1
                        
                        func ping(any_value: Variant, worker):
                            take_i(any_value)
                            take_i(worker.ping().length)
                            take_any(1)
                        """
        );

        var support = createSupport(analyzed, ResolveRestriction.instanceContext(), false);
        var publishedResolver = publishedExpressionResolver(analyzed);
        var calls = findNodes(findFunction(analyzed.ast(), "ping"), CallExpression.class, _ -> true);

        var exactVariantCall = support.resolveCallExpressionType(calls.get(0), publishedResolver, true, false);
        var dynamicVariantCall = support.resolveCallExpressionType(calls.get(1), publishedResolver, true, false);
        var packToVariantCall = support.resolveCallExpressionType(calls.get(2), publishedResolver, true, false);

        for (var result : List.of(exactVariantCall, dynamicVariantCall, packToVariantCall)) {
            assertTrue(result.rootOwnsOutcome());
            assertEquals(FrontendExpressionTypeStatus.RESOLVED, result.expressionType().status());
            assertNotNull(result.publishedCallOrNull());
        }

        assertEquals(List.of(GdVariantType.VARIANT), exactVariantCall.publishedCallOrNull().argumentTypes());
        assertEquals(List.of(GdVariantType.VARIANT), dynamicVariantCall.publishedCallOrNull().argumentTypes());
        assertEquals(List.of(GdIntType.INT), packToVariantCall.publishedCallOrNull().argumentTypes());
    }

    @Test
    void resolveCallExpressionTypeAcceptsNullSourcesAtObjectParameterBoundariesButRejectsScalarTargets()
            throws Exception {
        var analyzed = analyze(
                "expression_semantic_support_null_object_calls.gd",
                """
                        class_name ExpressionSemanticSupportNullObjectCalls
                        extends RefCounted
                        
                        func take_obj(value: Object) -> int:
                            return 1
                        
                        func take_i(value: int) -> int:
                            return value
                        
                        func ping() -> void:
                            take_obj(null)
                            take_i(null)
                        """
        );

        var support = createSupport(analyzed, ResolveRestriction.instanceContext(), false);
        var publishedResolver = publishedExpressionResolver(analyzed);
        var calls = findNodes(findFunction(analyzed.ast(), "ping"), CallExpression.class, _ -> true);

        var objectCall = support.resolveCallExpressionType(calls.get(0), publishedResolver, true, false);
        var scalarCall = support.resolveCallExpressionType(calls.get(1), publishedResolver, true, false);

        assertTrue(objectCall.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, objectCall.expressionType().status());
        assertNotNull(objectCall.publishedCallOrNull());
        assertEquals(List.of(GdNilType.NIL), objectCall.publishedCallOrNull().argumentTypes());

        assertTrue(scalarCall.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.FAILED, scalarCall.expressionType().status());
        assertTrue(scalarCall.expressionType().detailReason().contains("Nil"));
        assertTrue(scalarCall.expressionType().detailReason().contains("int"));
    }

    @Test
    void resolveSubscriptExpressionTypePublishesResolvedAndDynamicOutcomes() throws Exception {
        var analyzed = analyze(
                "expression_semantic_support_subscript.gd",
                """
                        class_name ExpressionSemanticSupportSubscript
                        extends RefCounted
                        
                        func ping(items: Array[int], lookup: Dictionary[String, int], packed: PackedInt32Array, dynamic_value):
                            items[0]
                            lookup["hp"]
                            packed[0]
                            dynamic_value[0]
                        """
        );

        var support = createSupport(analyzed, ResolveRestriction.instanceContext(), false);
        var publishedResolver = publishedExpressionResolver(analyzed);
        var pingFunction = findFunction(analyzed.ast(), "ping");
        var itemsSubscript = assertInstanceOf(
                SubscriptExpression.class,
                assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().getFirst()).expression()
        );
        var lookupSubscript = assertInstanceOf(
                SubscriptExpression.class,
                assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().get(1)).expression()
        );
        var packedSubscript = assertInstanceOf(
                SubscriptExpression.class,
                assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().get(2)).expression()
        );
        var dynamicSubscript = assertInstanceOf(
                SubscriptExpression.class,
                assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().get(3)).expression()
        );

        var itemsResult = support.resolveSubscriptExpressionType(itemsSubscript, publishedResolver, false);
        assertTrue(itemsResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, itemsResult.expressionType().status());
        assertEquals("int", itemsResult.expressionType().publishedType().getTypeName());

        var lookupResult = support.resolveSubscriptExpressionType(lookupSubscript, publishedResolver, false);
        assertTrue(lookupResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, lookupResult.expressionType().status());
        assertEquals("int", lookupResult.expressionType().publishedType().getTypeName());

        var packedResult = support.resolveSubscriptExpressionType(packedSubscript, publishedResolver, false);
        assertTrue(packedResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, packedResult.expressionType().status());
        assertEquals("int", packedResult.expressionType().publishedType().getTypeName());

        var dynamicResult = support.resolveSubscriptExpressionType(dynamicSubscript, publishedResolver, false);
        assertTrue(dynamicResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.DYNAMIC, dynamicResult.expressionType().status());
        assertEquals(GdVariantType.VARIANT, dynamicResult.expressionType().publishedType());
    }

    @Test
    void resolveUnaryExpressionTypePublishesResolvedDynamicAndFailedOutcomes() throws Exception {
        var analyzed = analyze(
                "expression_semantic_support_unary.gd",
                """
                        class_name ExpressionSemanticSupportUnary
                        extends RefCounted
                        
                        func ping(items: Array[int], dynamic_value, typed_variant: Variant):
                            -1
                            +1
                            ~1
                            !true
                            not true
                            not items
                            -dynamic_value
                            not typed_variant
                            ~"hello"
                        """
        );

        var support = createSupport(analyzed, ResolveRestriction.instanceContext(), false);
        var publishedResolver = publishedExpressionResolver(analyzed);
        var pingFunction = findFunction(analyzed.ast(), "ping");
        var expressions = pingFunction.body().statements().stream()
                .map(ExpressionStatement.class::cast)
                .map(ExpressionStatement::expression)
                .map(UnaryExpression.class::cast)
                .toList();

        var negateResult = support.resolveUnaryExpressionType(expressions.get(0), publishedResolver, false);
        assertTrue(negateResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, negateResult.expressionType().status());
        assertEquals("int", negateResult.expressionType().publishedType().getTypeName());

        var positiveResult = support.resolveUnaryExpressionType(expressions.get(1), publishedResolver, false);
        assertTrue(positiveResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, positiveResult.expressionType().status());
        assertEquals("int", positiveResult.expressionType().publishedType().getTypeName());

        var bitNotResult = support.resolveUnaryExpressionType(expressions.get(2), publishedResolver, false);
        assertTrue(bitNotResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, bitNotResult.expressionType().status());
        assertEquals("int", bitNotResult.expressionType().publishedType().getTypeName());

        var bangResult = support.resolveUnaryExpressionType(expressions.get(3), publishedResolver, false);
        assertTrue(bangResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, bangResult.expressionType().status());
        assertEquals("bool", bangResult.expressionType().publishedType().getTypeName());

        var notResult = support.resolveUnaryExpressionType(expressions.get(4), publishedResolver, false);
        assertTrue(notResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, notResult.expressionType().status());
        assertEquals("bool", notResult.expressionType().publishedType().getTypeName());

        var typedArrayNotResult = support.resolveUnaryExpressionType(expressions.get(5), publishedResolver, false);
        assertTrue(typedArrayNotResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, typedArrayNotResult.expressionType().status());
        assertEquals("bool", typedArrayNotResult.expressionType().publishedType().getTypeName());

        var dynamicResult = support.resolveUnaryExpressionType(expressions.get(6), publishedResolver, false);
        assertTrue(dynamicResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.DYNAMIC, dynamicResult.expressionType().status());
        assertEquals(GdVariantType.VARIANT, dynamicResult.expressionType().publishedType());

        var resolvedVariantResult = support.resolveUnaryExpressionType(expressions.get(7), publishedResolver, false);
        assertTrue(resolvedVariantResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.DYNAMIC, resolvedVariantResult.expressionType().status());
        assertEquals(GdVariantType.VARIANT, resolvedVariantResult.expressionType().publishedType());

        var invalidResult = support.resolveUnaryExpressionType(expressions.get(8), publishedResolver, false);
        assertTrue(invalidResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.FAILED, invalidResult.expressionType().status());
        assertTrue(invalidResult.expressionType().detailReason().contains("not defined for operand type 'String'"));
    }

    @Test
    void resolveUnaryExpressionTypeRejectsUnknownOperatorsAndPropagatesDependencyFailures() throws Exception {
        var support = newBareSupport();
        var unknownOperatorResult = support.resolveUnaryExpressionType(
                new UnaryExpression("??", integerLiteral("1"), TINY),
                (expression, finalizeWindow) -> FrontendExpressionType.resolved(GdIntType.INT),
                false
        );
        assertTrue(unknownOperatorResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.FAILED, unknownOperatorResult.expressionType().status());
        assertTrue(unknownOperatorResult.expressionType().detailReason().contains("Unknown unary source operator"));

        var analyzed = analyze(
                "expression_semantic_support_unary_dependency.gd",
                """
                        class_name ExpressionSemanticSupportUnaryDependency
                        extends RefCounted
                        
                        func ping():
                            -missing.payload
                        """
        );
        var dependencySupport = createSupport(analyzed, ResolveRestriction.instanceContext(), false);
        var publishedResolver = publishedExpressionResolver(analyzed);
        var unaryExpression = assertInstanceOf(
                UnaryExpression.class,
                assertInstanceOf(
                        ExpressionStatement.class,
                        findFunction(analyzed.ast(), "ping").body().statements().getFirst()
                ).expression()
        );

        var propagatedResult = dependencySupport.resolveUnaryExpressionType(unaryExpression, publishedResolver, false);
        assertFalse(propagatedResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.FAILED, propagatedResult.expressionType().status());
        assertTrue(propagatedResult.expressionType().detailReason().contains("chain head"));
    }

    @Test
    void resolveBinaryExpressionTypePublishesMetadataDynamicSpecialAndUnsupportedOutcomes() throws Exception {
        var analyzed = analyze(
                "expression_semantic_support_binary.gd",
                """
                        class_name ExpressionSemanticSupportBinary
                        extends RefCounted
                        
                        func ping(
                            ints_a: Array[int],
                            ints_b: Array[int],
                            names: Array[String],
                            raw_array: Array,
                            dynamic_value,
                            typed_variant: Variant
                        ):
                            1 + 2
                            1 - 2
                            1 * 2
                            1 == 2
                            1 < 2
                            1 & 2
                            1 in ints_a
                            dynamic_value + 1
                            typed_variant + 1
                            1 and 2
                            dynamic_value or 0
                            ints_a + ints_b
                            ints_a + names
                            ints_a + raw_array
                            1 not in ints_a
                            "hello" & 1
                        """
        );

        var support = createSupport(analyzed, ResolveRestriction.instanceContext(), false);
        var publishedResolver = publishedExpressionResolver(analyzed);
        var expressions = findFunction(analyzed.ast(), "ping").body().statements().stream()
                .map(ExpressionStatement.class::cast)
                .map(ExpressionStatement::expression)
                .map(BinaryExpression.class::cast)
                .toList();

        for (var index : List.of(0, 1, 2)) {
            var result = support.resolveBinaryExpressionType(expressions.get(index), publishedResolver, false);
            assertTrue(result.rootOwnsOutcome());
            assertEquals(FrontendExpressionTypeStatus.RESOLVED, result.expressionType().status());
            assertEquals("int", result.expressionType().publishedType().getTypeName());
        }
        var bitAndResult = support.resolveBinaryExpressionType(expressions.get(5), publishedResolver, false);
        assertTrue(bitAndResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, bitAndResult.expressionType().status());
        assertEquals("int", bitAndResult.expressionType().publishedType().getTypeName());

        for (var index : List.of(3, 4, 6, 9, 10)) {
            var result = support.resolveBinaryExpressionType(expressions.get(index), publishedResolver, false);
            assertTrue(result.rootOwnsOutcome());
            assertEquals(FrontendExpressionTypeStatus.RESOLVED, result.expressionType().status());
            assertEquals("bool", result.expressionType().publishedType().getTypeName());
        }

        var dynamicAddResult = support.resolveBinaryExpressionType(expressions.get(7), publishedResolver, false);
        assertTrue(dynamicAddResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.DYNAMIC, dynamicAddResult.expressionType().status());
        assertEquals(GdVariantType.VARIANT, dynamicAddResult.expressionType().publishedType());

        var variantAddResult = support.resolveBinaryExpressionType(expressions.get(8), publishedResolver, false);
        assertTrue(variantAddResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.DYNAMIC, variantAddResult.expressionType().status());
        assertEquals(GdVariantType.VARIANT, variantAddResult.expressionType().publishedType());

        var typedArrayPreserveResult = support.resolveBinaryExpressionType(expressions.get(11), publishedResolver, false);
        assertTrue(typedArrayPreserveResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, typedArrayPreserveResult.expressionType().status());
        assertEquals("Array[int]", typedArrayPreserveResult.expressionType().publishedType().getTypeName());

        var mismatchedTypedArrayResult = support.resolveBinaryExpressionType(expressions.get(12), publishedResolver, false);
        assertTrue(mismatchedTypedArrayResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, mismatchedTypedArrayResult.expressionType().status());
        assertEquals("Array", mismatchedTypedArrayResult.expressionType().publishedType().getTypeName());

        var typedUntypedArrayResult = support.resolveBinaryExpressionType(expressions.get(13), publishedResolver, false);
        assertTrue(typedUntypedArrayResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, typedUntypedArrayResult.expressionType().status());
        assertEquals("Array", typedUntypedArrayResult.expressionType().publishedType().getTypeName());

        var notInResult = support.resolveBinaryExpressionType(expressions.get(14), publishedResolver, false);
        assertTrue(notInResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.UNSUPPORTED, notInResult.expressionType().status());
        assertTrue(notInResult.expressionType().detailReason().contains("must not be silently normalized to 'in'"));

        var invalidResult = support.resolveBinaryExpressionType(expressions.get(15), publishedResolver, false);
        assertTrue(invalidResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.FAILED, invalidResult.expressionType().status());
        assertTrue(invalidResult.expressionType().detailReason().contains("not defined for operand types 'String' and 'int'"));
    }

    @Test
    void resolveBinaryExpressionTypePreservesOperandOrderAndDependencyProvenance() throws Exception {
        var analyzed = analyze(
                "expression_semantic_support_binary_order.gd",
                """
                        class_name ExpressionSemanticSupportBinaryOrder
                        extends RefCounted
                        
                        func ping(items: Array[int]):
                            1 in items
                            items in 1
                            1 + missing.payload
                        """
        );

        var support = createSupport(analyzed, ResolveRestriction.instanceContext(), false);
        var publishedResolver = publishedExpressionResolver(analyzed);
        var expressions = findFunction(analyzed.ast(), "ping").body().statements().stream()
                .map(ExpressionStatement.class::cast)
                .map(ExpressionStatement::expression)
                .map(BinaryExpression.class::cast)
                .toList();

        var resolvedInResult = support.resolveBinaryExpressionType(expressions.get(0), publishedResolver, false);
        assertTrue(resolvedInResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, resolvedInResult.expressionType().status());
        assertEquals("bool", resolvedInResult.expressionType().publishedType().getTypeName());

        var reversedInResult = support.resolveBinaryExpressionType(expressions.get(1), publishedResolver, false);
        assertTrue(reversedInResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.FAILED, reversedInResult.expressionType().status());
        assertTrue(reversedInResult.expressionType().detailReason().contains("operand types 'Array[int]' and 'int'"));

        var propagatedResult = support.resolveBinaryExpressionType(expressions.get(2), publishedResolver, false);
        assertFalse(propagatedResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.FAILED, propagatedResult.expressionType().status());
        assertTrue(propagatedResult.expressionType().detailReason().contains("chain head"));

        var unknownOperatorResult = newBareSupport().resolveBinaryExpressionType(
                new BinaryExpression("??", integerLiteral("1"), integerLiteral("2"), TINY),
                (expression, finalizeWindow) -> FrontendExpressionType.resolved(GdIntType.INT),
                false
        );
        assertTrue(unknownOperatorResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.FAILED, unknownOperatorResult.expressionType().status());
        assertTrue(unknownOperatorResult.expressionType().detailReason().contains("Unknown binary source operator"));
    }

    @Test
    void resolveBinaryExpressionTypeAcceptsLogicalSourceAliases() throws Exception {
        var support = newBareSupport();

        var logicalAndResult = support.resolveBinaryExpressionType(
                new BinaryExpression("&&", integerLiteral("1"), integerLiteral("2"), TINY),
                (expression, finalizeWindow) -> FrontendExpressionType.resolved(GdIntType.INT),
                false
        );
        assertTrue(logicalAndResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, logicalAndResult.expressionType().status());
        assertEquals("bool", logicalAndResult.expressionType().publishedType().getTypeName());

        var logicalOrResult = support.resolveBinaryExpressionType(
                new BinaryExpression("||", identifier("payload"), integerLiteral("0"), TINY),
                (expression, finalizeWindow) -> expression instanceof IdentifierExpression
                        ? FrontendExpressionType.dynamic("synthetic runtime-open payload")
                        : FrontendExpressionType.resolved(GdIntType.INT),
                false
        );
        assertTrue(logicalOrResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, logicalOrResult.expressionType().status());
        assertEquals("bool", logicalOrResult.expressionType().publishedType().getTypeName());
    }

    @Test
    void resolveRemainingExplicitExpressionRoutesEnumerateRemainingDeferredKindsAndRejectParserRecoveryNodes()
            throws Exception {
        var support = newBareSupport();
        var nestedResolver = resolvedVariantResolver();
        var typeRef = new TypeRef("String", TINY);
        var literal = integerLiteral("1");
        var cases = List.of(
                new RemainingExpressionCase(
                        new ConditionalExpression(identifier("flag"), integerLiteral("1"), integerLiteral("2"), TINY),
                        FrontendExpressionTypeStatus.DEFERRED,
                        "Conditional expression typing is deferred"
                ),
                new RemainingExpressionCase(
                        new ArrayExpression(List.of(integerLiteral("1")), false, TINY),
                        FrontendExpressionTypeStatus.DEFERRED,
                        "Array literal typing is deferred"
                ),
                new RemainingExpressionCase(
                        new DictionaryExpression(
                                List.of(new DictEntry(stringLiteral("\"hp\""), integerLiteral("1"), TINY)),
                                false,
                                TINY
                        ),
                        FrontendExpressionTypeStatus.DEFERRED,
                        "Dictionary literal typing is deferred"
                ),
                new RemainingExpressionCase(
                        new AwaitExpression(identifier("signal_name"), TINY),
                        FrontendExpressionTypeStatus.DEFERRED,
                        "Await expression typing is deferred"
                ),
                new RemainingExpressionCase(
                        new PreloadExpression(stringLiteral("\"res://icon.svg\""), TINY),
                        FrontendExpressionTypeStatus.DEFERRED,
                        "Preload expression typing is deferred"
                ),
                new RemainingExpressionCase(
                        new GetNodeExpression("$Camera3D", TINY),
                        FrontendExpressionTypeStatus.DEFERRED,
                        "Get-node expression typing is deferred"
                ),
                new RemainingExpressionCase(
                        new CastExpression(identifier("value"), typeRef, TINY),
                        FrontendExpressionTypeStatus.DEFERRED,
                        "Cast expression typing is deferred"
                ),
                new RemainingExpressionCase(
                        new TypeTestExpression(identifier("value"), typeRef, false, TINY),
                        FrontendExpressionTypeStatus.DEFERRED,
                        "Type-test expression typing is deferred"
                ),
                new RemainingExpressionCase(
                        new PatternBindingExpression("captured", TINY),
                        FrontendExpressionTypeStatus.DEFERRED,
                        "Pattern binding expression typing is deferred"
                ),
                new RemainingExpressionCase(
                        new UnknownExpression("recovery_node", "??", TINY),
                        FrontendExpressionTypeStatus.UNSUPPORTED,
                        "Parser recovery expression 'recovery_node'"
                )
        );

        for (var testCase : cases) {
            var result = support.resolveRemainingExplicitExpressionType(
                    testCase.expression(),
                    nestedResolver,
                    true,
                    false
            );
            assertTrue(result.rootOwnsOutcome(), () -> "expected root-owned outcome for " + testCase.expression());
            assertEquals(testCase.status(), result.expressionType().status());
            assertTrue(
                    result.expressionType().detailReason().contains(testCase.reasonFragment()),
                    () -> "expected detail reason to contain '" + testCase.reasonFragment() + "' but got '"
                            + result.expressionType().detailReason() + "'"
            );
        }
    }

    @Test
    void resolveDeferredRoutesKeepDependencyProvenance() throws Exception {
        var analyzed = analyze(
                "expression_semantic_support_deferred.gd",
                """
                        class_name ExpressionSemanticSupportDeferred
                        extends RefCounted
                        
                        func ping(items):
                            items = 1
                            1 + missing.payload
                        """
        );

        var support = createSupport(analyzed, ResolveRestriction.instanceContext(), false);
        var publishedResolver = publishedExpressionResolver(analyzed);
        var pingFunction = findFunction(analyzed.ast(), "ping");
        var genericDeferred = assertInstanceOf(
                Expression.class,
                assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().getLast()).expression()
        );

        var genericResult = support.resolveBinaryExpressionType(
                assertInstanceOf(BinaryExpression.class, genericDeferred),
                publishedResolver,
                false
        );
        assertFalse(genericResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.FAILED, genericResult.expressionType().status());
        assertTrue(genericResult.expressionType().detailReason().contains("chain head"));
    }

    @Test
    void selectCallableOverloadReportsAmbiguousAndEmptyOverloadSets() throws Exception {
        var support = newBareSupport();
        var ambiguous = List.of(
                newCallable("helper", GdIntType.INT, GdIntType.INT),
                newCallable("helper", GdStringType.STRING, GdIntType.INT)
        );

        var ambiguousSelection = support.selectCallableOverload(ambiguous, List.of(GdIntType.INT));
        assertTrue(ambiguousSelection.selected() == null);
        assertTrue(ambiguousSelection.detailReason().contains("Ambiguous bare call overload"));

        var variantAmbiguous = List.of(
                newCallable("helper", GdIntType.INT, GdIntType.INT),
                newCallable("helper", GdIntType.INT, GdStringType.STRING)
        );
        var variantAmbiguousSelection = support.selectCallableOverload(
                variantAmbiguous,
                List.of(GdVariantType.VARIANT)
        );
        assertTrue(variantAmbiguousSelection.selected() == null);
        assertTrue(variantAmbiguousSelection.detailReason().contains("Ambiguous bare call overload"));

        var emptySelection = support.selectCallableOverload(List.of(), List.of(GdIntType.INT));
        assertTrue(emptySelection.selected() == null);
        assertEquals("Bare call resolves to an empty overload set", emptySelection.detailReason());
    }

    private @NotNull FrontendExpressionSemanticSupport createSupport(
            @NotNull AnalyzedScript analyzed,
            @NotNull ResolveRestriction restriction,
            boolean staticContext
    ) {
        return new FrontendExpressionSemanticSupport(
                analyzed.analysisData().symbolBindings(),
                analyzed.analysisData().scopesByAst(),
                () -> restriction,
                () -> null,
                analyzed.classRegistry(),
                () -> new FrontendChainHeadReceiverSupport(
                        analyzed.analysisData(),
                        analyzed.analysisData().scopesByAst(),
                        restriction,
                        staticContext,
                        null,
                        _ -> null,
                        _ -> null
                )
        );
    }

    private static @NotNull FrontendExpressionSemanticSupport newBareSupport() throws Exception {
        var analysisData = FrontendAnalysisData.bootstrap();
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        return new FrontendExpressionSemanticSupport(
                analysisData.symbolBindings(),
                analysisData.scopesByAst(),
                ResolveRestriction::instanceContext,
                () -> null,
                classRegistry,
                () -> new FrontendChainHeadReceiverSupport(
                        analysisData,
                        analysisData.scopesByAst(),
                        ResolveRestriction.instanceContext(),
                        false,
                        null,
                        _ -> null,
                        _ -> null
                )
        );
    }

    private static @NotNull FrontendExpressionSemanticSupport.NestedExpressionResolver publishedExpressionResolver(
            @NotNull AnalyzedScript analyzed
    ) {
        return (expression, finalizeWindow) -> {
            var published = analyzed.analysisData().expressionTypes().get(expression);
            return Objects.requireNonNull(
                    published,
                    "Expected published expression type for " + expression.getClass().getSimpleName()
            );
        };
    }

    private static @NotNull FrontendExpressionSemanticSupport.NestedExpressionResolver resolvedVariantResolver() {
        return (expression, finalizeWindow) -> FrontendExpressionType.resolved(GdVariantType.VARIANT);
    }

    private static @NotNull AnalyzedScript analyze(
            @NotNull String fileName,
            @NotNull String source
    ) throws Exception {
        var diagnostics = new DiagnosticManager();
        var parserService = new GdScriptParserService();
        var unit = parserService.parseUnit(Path.of("tmp", fileName), source, diagnostics);
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var analysisData = new FrontendSemanticAnalyzer().analyze(
                new FrontendModule("test_module", List.of(unit)),
                classRegistry,
                diagnostics
        );
        return new AnalyzedScript(unit.ast(), analysisData, classRegistry);
    }

    private static @NotNull IdentifierExpression identifier(@NotNull String name) {
        return new IdentifierExpression(name, TINY);
    }

    private static @NotNull dev.superice.gdparser.frontend.ast.LiteralExpression integerLiteral(
            @NotNull String sourceText
    ) {
        return new dev.superice.gdparser.frontend.ast.LiteralExpression("integer", sourceText, TINY);
    }

    private static @NotNull dev.superice.gdparser.frontend.ast.LiteralExpression stringLiteral(
            @NotNull String sourceText
    ) {
        return new dev.superice.gdparser.frontend.ast.LiteralExpression("string", sourceText, TINY);
    }

    private static @NotNull LirFunctionDef newCallable(
            @NotNull String name,
            @NotNull dev.superice.gdcc.type.GdType returnType,
            @NotNull dev.superice.gdcc.type.GdType... parameterTypes
    ) {
        var function = new LirFunctionDef(name);
        function.setReturnType(returnType);
        for (var index = 0; index < parameterTypes.length; index++) {
            function.addParameter(new LirParameterDef("arg" + index, parameterTypes[index], null, function));
        }
        return function;
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
            @NotNull FrontendAnalysisData analysisData,
            @NotNull ClassRegistry classRegistry
    ) {
    }

    private record RemainingExpressionCase(
            @NotNull Expression expression,
            @NotNull FrontendExpressionTypeStatus status,
            @NotNull String reasonFragment
    ) {
    }
}
