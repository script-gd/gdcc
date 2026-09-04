package gd.script.gdcc.frontend.sema.analyzer;

import dev.superice.gdparser.frontend.ast.ArrayExpression;
import dev.superice.gdparser.frontend.ast.CallExpression;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.GetNodeExpression;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.SelfExpression;
import dev.superice.gdparser.frontend.ast.SourceFile;
import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.diagnostic.DiagnosticSnapshot;
import gd.script.gdcc.frontend.diagnostic.FrontendDiagnostic;
import gd.script.gdcc.frontend.diagnostic.FrontendDiagnosticSeverity;
import gd.script.gdcc.frontend.diagnostic.FrontendRange;
import gd.script.gdcc.frontend.parse.FrontendModule;
import gd.script.gdcc.frontend.parse.FrontendSourceUnit;
import gd.script.gdcc.frontend.parse.GdScriptParserService;
import gd.script.gdcc.frontend.sema.FrontendAnalysisData;
import gd.script.gdcc.frontend.sema.FrontendBindingKind;
import gd.script.gdcc.frontend.sema.FrontendCallResolutionStatus;
import gd.script.gdcc.frontend.sema.FrontendExpressionTypeStatus;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.lir.LirClassDef;
import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.lir.LirParameterDef;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.scope.ScopeLookupStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/// Anchors the parameter-default metadata owner sweep and the `PARAMETER_DEFAULT` visible-value
/// island: accepted forms publish full facts plus `defaultValueFunc` metadata, rejected forms
/// produce exactly one anchored diagnostic and reclaim the metadata, and the reclaimed state is
/// what body arity checks observe.
class FrontendParameterDefaultMetadataOwnerTest {
    private static final @NotNull String ORDER_CATEGORY = "sema.invalid_parameter_default_order";
    private static final @NotNull String UNSUPPORTED_DEFAULT_CATEGORY =
            "sema.unsupported_parameter_default_expression";

    @Test
    void literalDefaultPublishesFactsAndMetadata() throws Exception {
        var input = analyze("literal_default.gd", """
                class_name LiteralDefault
                extends RefCounted
                
                func ping(count, limit = 5):
                    return limit
                """);

        assertTrue(input.diagnostics().snapshot().isEmpty(), input.diagnostics().snapshot()::toString);
        var ping = findFunction(input.unit().ast(), "ping");
        var defaultRoot = ping.parameters().getLast().defaultValue();
        // The default expression publishes into the same AST-identity side tables as body facts.
        var rootType = input.analysisData().expressionTypes().get(defaultRoot);
        assertNotNull(rootType);
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, rootType.status());
        assertEquals("int", Objects.requireNonNull(rootType.publishedType()).getTypeName());
        assertEquals("_default_ping$limit", requireParameterDef(input, "LiteralDefault", "ping", "limit")
                .getDefaultValueFunc());
    }

    @Test
    void builtinConstructorAndStaticCallDefaultsAreAccepted() throws Exception {
        var input = analyze("builtin_and_static_default.gd", """
                class_name BuiltinAndStaticDefault
                extends RefCounted
                
                static func make_default() -> int:
                    return 1
                
                func ping(offset = Vector2(1, 2), seed = make_default()):
                    return seed
                """);

        assertTrue(input.diagnostics().snapshot().isEmpty(), input.diagnostics().snapshot()::toString);
        var ping = findFunction(input.unit().ast(), "ping");
        var constructorDefault = ping.parameters().get(0).defaultValue();
        var constructorType = input.analysisData().expressionTypes().get(constructorDefault);
        assertNotNull(constructorType);
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, constructorType.status());
        assertEquals("Vector2", Objects.requireNonNull(constructorType.publishedType()).getTypeName());

        var callDefault = findNode(
                ping.parameters().get(1).defaultValue(),
                CallExpression.class,
                candidate -> true
        );
        var resolvedCall = input.analysisData().resolvedCalls().get(callDefault);
        assertNotNull(resolvedCall);
        assertEquals(FrontendCallResolutionStatus.RESOLVED, resolvedCall.status());
        assertEquals("make_default", resolvedCall.callableName());

        assertEquals("_default_ping$offset", requireParameterDef(input, "BuiltinAndStaticDefault", "ping", "offset")
                .getDefaultValueFunc());
        assertEquals("_default_ping$seed", requireParameterDef(input, "BuiltinAndStaticDefault", "ping", "seed")
                .getDefaultValueFunc());
    }

    @Test
    void crossDefaultCallReadsPlaceholderMetadata() throws Exception {
        var input = analyze("cross_default_call.gd", """
                class_name CrossDefaultCall
                extends RefCounted
                
                func f(a = 1):
                    return a
                
                func g(x = f()):
                    return x
                """);

        // `f()` omits the only parameter: the call resolves because f's placeholder metadata is
        // already written when g's island runs (phase 2 completes module-wide first).
        assertTrue(input.diagnostics().snapshot().isEmpty(), input.diagnostics().snapshot()::toString);
        assertEquals("_default_f$a", requireParameterDef(input, "CrossDefaultCall", "f", "a")
                .getDefaultValueFunc());
        assertEquals("_default_g$x", requireParameterDef(input, "CrossDefaultCall", "g", "x")
                .getDefaultValueFunc());
        var g = findFunction(input.unit().ast(), "g");
        var call = findNode(g.parameters().getLast().defaultValue(), CallExpression.class, candidate -> true);
        var resolvedCall = input.analysisData().resolvedCalls().get(call);
        assertNotNull(resolvedCall);
        assertEquals(FrontendCallResolutionStatus.RESOLVED, resolvedCall.status());
    }

    @Test
    void instanceMethodDefaultsMayUseSelfAndInstanceMembers() throws Exception {
        var input = analyze("instance_self_default.gd", """
                class_name InstanceSelfDefault
                extends Node
                
                var hp: int = 10
                
                func read_hp() -> int:
                    return hp
                
                func ping(copy = self.hp, receiver = self, computed = read_hp()):
                    return copy
                """);

        assertTrue(input.diagnostics().snapshot().isEmpty(), input.diagnostics().snapshot()::toString);
        var ping = findFunction(input.unit().ast(), "ping");
        // `self` binds normally inside an instance method's default island.
        var selfUseSite = findNode(
                ping.parameters().get(1).defaultValue(),
                SelfExpression.class,
                candidate -> true
        );
        var selfBinding = input.analysisData().symbolBindings().get(selfUseSite);
        assertNotNull(selfBinding);
        assertEquals(FrontendBindingKind.SELF, selfBinding.kind());
        assertEquals(
                FrontendExpressionTypeStatus.RESOLVED,
                Objects.requireNonNull(input.analysisData().expressionTypes().get(ping.parameters().get(1).defaultValue()))
                        .status()
        );
        var memberCall = findNode(
                ping.parameters().get(2).defaultValue(),
                CallExpression.class,
                candidate -> true
        );
        assertEquals(
                FrontendCallResolutionStatus.RESOLVED,
                Objects.requireNonNull(input.analysisData().resolvedCalls().get(memberCall)).status()
        );
        assertEquals("_default_ping$copy", requireParameterDef(input, "InstanceSelfDefault", "ping", "copy")
                .getDefaultValueFunc());
        assertEquals("_default_ping$receiver", requireParameterDef(input, "InstanceSelfDefault", "ping", "receiver")
                .getDefaultValueFunc());
        assertEquals("_default_ping$computed", requireParameterDef(input, "InstanceSelfDefault", "ping", "computed")
                .getDefaultValueFunc());
    }

    @Test
    void typedContainerDefaultReceivesParameterSlotAsExpectedType() throws Exception {
        var input = analyze("typed_container_default.gd", """
                class_name TypedContainerDefault
                extends RefCounted
                
                func ping(values: Array[int] = [1, 2]):
                    return values
                """);

        assertTrue(input.diagnostics().snapshot().isEmpty(), input.diagnostics().snapshot()::toString);
        var ping = findFunction(input.unit().ast(), "ping");
        var arrayLiteral = assertInstanceOf(ArrayExpression.class, ping.parameters().getLast().defaultValue());
        // The parameter slot type must reach the island as expected type, otherwise the literal
        // would degenerate to `Array[Variant]`.
        var plan = input.analysisData().containerLiteralPlans().get(arrayLiteral);
        assertNotNull(plan);
        assertEquals("Array[int]", plan.resultType().getTypeName());
        var rootType = input.analysisData().expressionTypes().get(arrayLiteral);
        assertNotNull(rootType);
        assertEquals("Array[int]", Objects.requireNonNull(rootType.publishedType()).getTypeName());
        assertEquals("_default_ping$values", requireParameterDef(input, "TypedContainerDefault", "ping", "values")
                .getDefaultValueFunc());
    }

    @Test
    void staticFunctionLiteralDefaultIsAccepted() throws Exception {
        var input = analyze("static_literal_default.gd", """
                class_name StaticLiteralDefault
                extends RefCounted
                
                static func ping(count = 5):
                    return count
                """);

        assertTrue(input.diagnostics().snapshot().isEmpty(), input.diagnostics().snapshot()::toString);
        // Static functions always synthesize under the `_default_s_` prefix.
        assertEquals("_default_s_ping$count", requireParameterDef(input, "StaticLiteralDefault", "ping", "count")
                .getDefaultValueFunc());
    }

    @Test
    void parameterReferenceDefaultStopsAtLayerAndIsRejected() throws Exception {
        var input = analyze("parameter_reference_default.gd", """
                class_name ParameterReferenceDefault
                extends RefCounted
                
                static var shadowed = 1
                
                func ping(shadowed, alias = shadowed):
                    return alias
                """);

        var ping = findFunction(input.unit().ast(), "ping");
        var defaultRoot = ping.parameters().getLast().defaultValue();
        // Exactly one owner diagnostic anchored at the default root; the resolver must not fall
        // through to the outer static property shadowed by the parameter.
        var ownerDiagnostics = diagnosticsByCategory(input.diagnostics().snapshot(), UNSUPPORTED_DEFAULT_CATEGORY);
        assertEquals(1, ownerDiagnostics.size(), ownerDiagnostics::toString);
        assertEquals(FrontendRange.fromAstRange(defaultRoot.range()), ownerDiagnostics.getFirst().range());
        assertTrue(diagnosticsByCategory(input.diagnostics().snapshot(), "sema.binding").isEmpty());

        var useSite = findNode(defaultRoot, IdentifierExpression.class, id -> id.name().equals("shadowed"));
        var binding = input.analysisData().symbolBindings().get(useSite);
        assertNotNull(binding);
        assertEquals(FrontendBindingKind.PARAMETER, binding.kind());
        assertEquals(ScopeLookupStatus.FOUND_BLOCKED, binding.valueAccessStatus());
        assertSame(ping.parameters().getFirst(), binding.declarationSite());
        assertNull(requireParameterDef(input, "ParameterReferenceDefault", "ping", "alias").getDefaultValueFunc());
    }

    @Test
    void localReferenceDefaultIsRejected() throws Exception {
        var input = analyze("local_reference_default.gd", """
                class_name LocalReferenceDefault
                extends RefCounted
                
                func ping(alias = body_local):
                    var body_local = 1
                    return alias
                """);

        var ping = findFunction(input.unit().ast(), "ping");
        var ownerDiagnostics = diagnosticsByCategory(input.diagnostics().snapshot(), UNSUPPORTED_DEFAULT_CATEGORY);
        assertEquals(1, ownerDiagnostics.size(), ownerDiagnostics::toString);
        assertTrue(diagnosticsByCategory(input.diagnostics().snapshot(), "sema.binding").isEmpty());

        var defaultRoot = ping.parameters().getLast().defaultValue();
        var useSite = findNode(defaultRoot, IdentifierExpression.class, id -> id.name().equals("body_local"));
        var binding = input.analysisData().symbolBindings().get(useSite);
        assertNotNull(binding);
        assertEquals(FrontendBindingKind.LOCAL_VAR, binding.kind());
        assertEquals(ScopeLookupStatus.FOUND_BLOCKED, binding.valueAccessStatus());
        assertNull(requireParameterDef(input, "LocalReferenceDefault", "ping", "alias").getDefaultValueFunc());
    }

    @Test
    void awaitDefaultIsRejected() throws Exception {
        var input = analyze("await_default.gd", """
                class_name AwaitDefault
                extends Node
                
                signal done()
                
                func ping(value = await done):
                    return value
                """);

        var ownerDiagnostics = diagnosticsByCategory(input.diagnostics().snapshot(), UNSUPPORTED_DEFAULT_CATEGORY);
        assertEquals(1, ownerDiagnostics.size(), ownerDiagnostics::toString);
        // The await boundary stays silent inside the island (no unsupported-expression/route
        // diagnostic, no coroutine classification).
        assertTrue(diagnosticsByCategory(input.diagnostics().snapshot(), "sema.unsupported_expression_route").isEmpty());
        assertTrue(input.analysisData().coroutineFunctions().isEmpty());
        assertTrue(input.analysisData().coroutineLambdaOwners().isEmpty());
        assertNull(requireParameterDef(input, "AwaitDefault", "ping", "value").getDefaultValueFunc());
    }

    @Test
    void getNodeDefaultIsRejected() throws Exception {
        var input = analyze("get_node_default.gd", """
                class_name GetNodeDefault
                extends Node
                
                func ping(child = $Camera3D):
                    return child
                """);

        var ping = findFunction(input.unit().ast(), "ping");
        var ownerDiagnostics = diagnosticsByCategory(input.diagnostics().snapshot(), UNSUPPORTED_DEFAULT_CATEGORY);
        assertEquals(1, ownerDiagnostics.size(), ownerDiagnostics::toString);
        assertTrue(diagnosticsByCategory(input.diagnostics().snapshot(), "sema.deferred_expression_resolution").isEmpty());
        var getNode = findNode(
                ping.parameters().getLast().defaultValue(),
                GetNodeExpression.class,
                candidate -> true
        );
        var getNodeType = input.analysisData().expressionTypes().get(getNode);
        assertNotNull(getNodeType);
        assertEquals(FrontendExpressionTypeStatus.BLOCKED, getNodeType.status());
        assertNull(requireParameterDef(input, "GetNodeDefault", "ping", "child").getDefaultValueFunc());
    }

    @Test
    void staticMethodSelfMemberDefaultIsRejected() throws Exception {
        var input = analyze("static_self_default.gd", """
                class_name StaticSelfDefault
                extends RefCounted
                
                var hp: int = 10
                
                static func ping(copy = self.hp):
                    return copy
                """);

        var ping = findFunction(input.unit().ast(), "ping");
        var ownerDiagnostics = diagnosticsByCategory(input.diagnostics().snapshot(), UNSUPPORTED_DEFAULT_CATEGORY);
        assertEquals(1, ownerDiagnostics.size(), input.diagnostics().snapshot()::toString);
        // Neither `bindSelf` nor the chain route may pre-emit inside the island.
        assertTrue(diagnosticsByCategory(input.diagnostics().snapshot(), "sema.binding").isEmpty());
        var defaultRoot = ping.parameters().getLast().defaultValue();
        var rootType = input.analysisData().expressionTypes().get(defaultRoot);
        assertNotNull(rootType);
        assertEquals(FrontendExpressionTypeStatus.BLOCKED, rootType.status());
        assertNull(requireParameterDef(input, "StaticSelfDefault", "ping", "copy").getDefaultValueFunc());
    }

    @Test
    void mandatoryParameterAfterOptionalIsRejected() throws Exception {
        var input = analyze("mandatory_after_optional.gd", """
                class_name MandatoryAfterOptional
                extends RefCounted
                
                func ping(a = 1, b):
                    return b
                """);

        var ping = findFunction(input.unit().ast(), "ping");
        var orderDiagnostics = diagnosticsByCategory(input.diagnostics().snapshot(), ORDER_CATEGORY);
        assertEquals(1, orderDiagnostics.size(), orderDiagnostics::toString);
        assertEquals(FrontendDiagnosticSeverity.ERROR, orderDiagnostics.getFirst().severity());
        // Anchored at the violating mandatory parameter.
        assertEquals(
                FrontendRange.fromAstRange(ping.parameters().get(1).range()),
                orderDiagnostics.getFirst().range()
        );
        // The violating parameter never receives metadata; the legal defaulted sibling still does.
        var pingDef = requireFunctionDef(input, "MandatoryAfterOptional", "ping");
        assertNull(pingDef.getParameter("b").getDefaultValueFunc());
        assertEquals("_default_ping$a", pingDef.getParameter("a").getDefaultValueFunc());
    }

    @Test
    void variadicParameterCannotHaveDefault() throws Exception {
        var input = analyze("variadic_default.gd", """
                class_name VariadicDefault
                extends RefCounted
                
                func ping(a = 1, ...rest = [2]):
                    pass
                """);

        var ping = findFunction(input.unit().ast(), "ping");
        var orderDiagnostics = diagnosticsByCategory(input.diagnostics().snapshot(), ORDER_CATEGORY);
        assertEquals(1, orderDiagnostics.size(), input.diagnostics().snapshot()::toString);
        // Anchored at the violating variadic parameter, which never receives metadata; the legal
        // defaulted sibling keeps its placeholder.
        assertEquals(
                FrontendRange.fromAstRange(ping.parameters().get(1).range()),
                orderDiagnostics.getFirst().range()
        );
        var pingDef = requireFunctionDef(input, "VariadicDefault", "ping");
        assertNull(pingDef.getParameter("rest").getDefaultValueFunc());
        assertEquals("_default_ping$a", pingDef.getParameter("a").getDefaultValueFunc());
    }

    @Test
    void omittedArgumentCallsPassArityWhenDefaultsAccepted() throws Exception {
        var input = analyze("omitted_argument_calls.gd", """
                class_name OmittedArgumentCalls
                extends RefCounted
                
                func ping(a, b = 5, c = "s"):
                    return b
                
                func caller():
                    ping(1)
                    ping(1, 2)
                    ping(1, 2, "x")
                """);

        assertFalse(input.diagnostics().snapshot().hasErrors(), input.diagnostics().snapshot()::toString);
        var caller = findFunction(input.unit().ast(), "caller");
        var calls = new ArrayList<CallExpression>();
        collectMatchingNodes(caller.body(), CallExpression.class, candidate -> true, calls);
        assertEquals(3, calls.size());
        for (var call : calls) {
            var resolvedCall = input.analysisData().resolvedCalls().get(call);
            assertNotNull(resolvedCall);
            assertEquals(FrontendCallResolutionStatus.RESOLVED, resolvedCall.status());
        }
    }

    @Test
    void missingRequiredArgumentStillReportsTooFew() throws Exception {
        var input = analyze("missing_required_argument.gd", """
                class_name MissingRequiredArgument
                extends RefCounted
                
                func ping(a, b = 5):
                    return b
                
                func caller():
                    ping()
                """);

        // Defaults are accepted and kept; omitting past the required prefix stays an error.
        assertTrue(input.diagnostics().snapshot().asList().stream().anyMatch(diagnostic ->
                diagnostic.severity() == FrontendDiagnosticSeverity.ERROR
                        && diagnostic.message().contains("missing required parameter #1 ('a')")
        ), input.diagnostics().snapshot()::toString);
        assertTrue(diagnosticsByCategory(input.diagnostics().snapshot(), UNSUPPORTED_DEFAULT_CATEGORY).isEmpty());
        assertEquals("_default_ping$b", requireParameterDef(input, "MissingRequiredArgument", "ping", "b")
                .getDefaultValueFunc());
    }

    @Test
    void sweepSkipsInitAndKeepsExistingRejectionPath() throws Exception {
        var input = analyze("init_default_skipped.gd", """
                class_name InitDefaultSkipped
                extends RefCounted
                
                func _init(seed = 1):
                    pass
                """);

        // The sweep never touches `_init`: no owner diagnostics, no metadata. The existing
        // fail-closed paths (parameterized `_init` type-check rejection + binding/chain subtree
        // diagnostics) keep reporting.
        assertTrue(diagnosticsByCategory(input.diagnostics().snapshot(), ORDER_CATEGORY).isEmpty());
        assertTrue(diagnosticsByCategory(input.diagnostics().snapshot(), UNSUPPORTED_DEFAULT_CATEGORY).isEmpty());
        assertFalse(diagnosticsByCategory(input.diagnostics().snapshot(), "sema.type_check").isEmpty());
        assertFalse(diagnosticsByCategory(input.diagnostics().snapshot(), "sema.unsupported_binding_subtree").isEmpty());
        assertNull(requireParameterDef(input, "InitDefaultSkipped", "_init", "seed").getDefaultValueFunc());
    }

    @Test
    void lambdaParameterDefaultStaysFailClosed() throws Exception {
        var input = analyze("lambda_default_stays_closed.gd", """
                class_name LambdaDefaultStaysClosed
                extends RefCounted
                
                func ping():
                    var cb = func(item = 1):
                        return item
                """);

        // The variable analyzer keeps its lambda diagnostic; the sweep never sees lambda
        // parameters, so neither owner category may fire.
        assertEquals(
                1,
                diagnosticsByCategory(input.diagnostics().snapshot(), "sema.unsupported_parameter_default_value").size()
        );
        assertTrue(diagnosticsByCategory(input.diagnostics().snapshot(), ORDER_CATEGORY).isEmpty());
        assertTrue(diagnosticsByCategory(input.diagnostics().snapshot(), UNSUPPORTED_DEFAULT_CATEGORY).isEmpty());
    }

    @Test
    void reclaimedDefaultMakesOmittingCallReportTooFewRegardlessOfSourceOrder() throws Exception {
        var input = analyze("reclaimed_default_too_few.gd", """
                class_name ReclaimedDefaultTooFew
                extends RefCounted
                
                func caller():
                    ping(1)
                
                func ping(a, b = a):
                    return b
                """);

        // The call site precedes the callee in source order, but the sweep finalizes metadata
        // before any body resolves: `b` is reclaimed to required, so `ping(1)` reports too-few.
        assertEquals(
                1,
                diagnosticsByCategory(input.diagnostics().snapshot(), UNSUPPORTED_DEFAULT_CATEGORY).size()
        );
        assertTrue(input.diagnostics().snapshot().asList().stream().anyMatch(diagnostic ->
                diagnostic.severity() == FrontendDiagnosticSeverity.ERROR
                        && diagnostic.message().contains("missing required parameter #2 ('b')")
        ), input.diagnostics().snapshot()::toString);
        assertNull(requireParameterDef(input, "ReclaimedDefaultTooFew", "ping", "b").getDefaultValueFunc());
    }

    @Test
    void crossDefaultReclaimCornerKeepsCallerFactsWithoutRetroactiveInvalidation() throws Exception {
        var input = analyze("cross_default_reclaim_corner.gd", """
                class_name CrossDefaultReclaimCorner
                extends RefCounted
                
                func user(x = bad(1)):
                    return x
                
                func bad(a, b = a):
                    return b
                """);

        // `user` runs its island while `bad`'s placeholder is still published, so its omitting
        // call resolves and its own metadata stays; `bad` is then rejected and reclaimed. The
        // resolved call fact is not retroactively invalidated — the module fails closed through
        // `bad`'s diagnostic.
        var ownerDiagnostics = diagnosticsByCategory(input.diagnostics().snapshot(), UNSUPPORTED_DEFAULT_CATEGORY);
        assertEquals(1, ownerDiagnostics.size(), ownerDiagnostics::toString);
        assertEquals("_default_user$x", requireParameterDef(input, "CrossDefaultReclaimCorner", "user", "x")
                .getDefaultValueFunc());
        assertNull(requireParameterDef(input, "CrossDefaultReclaimCorner", "bad", "b").getDefaultValueFunc());
        var user = findFunction(input.unit().ast(), "user");
        var call = findNode(user.parameters().getLast().defaultValue(), CallExpression.class, candidate -> true);
        assertEquals(
                FrontendCallResolutionStatus.RESOLVED,
                Objects.requireNonNull(input.analysisData().resolvedCalls().get(call)).status()
        );
        assertTrue(input.diagnostics().snapshot().hasErrors());
    }

    @Test
    void incompatibleDefaultTypeKeepsMetadataAndReportsTypeCheck() throws Exception {
        var input = analyze("incompatible_default_type.gd", """
                class_name IncompatibleDefaultType
                extends RefCounted
                
                func ping(count: int = "x"):
                    return count
                """);

        var ping = findFunction(input.unit().ast(), "ping");
        var defaultRoot = ping.parameters().getLast().defaultValue();
        // Exactly one assignment-compatibility diagnostic anchored at the default expression; the
        // metadata is kept (arity still allows omission) and the owner stays silent.
        var typeCheckDiagnostics = diagnosticsByCategory(input.diagnostics().snapshot(), "sema.type_check");
        assertEquals(1, typeCheckDiagnostics.size(), typeCheckDiagnostics::toString);
        assertEquals(FrontendRange.fromAstRange(defaultRoot.range()), typeCheckDiagnostics.getFirst().range());
        assertTrue(typeCheckDiagnostics.getFirst().message().contains("String"));
        assertTrue(typeCheckDiagnostics.getFirst().message().contains("int"));
        assertTrue(diagnosticsByCategory(input.diagnostics().snapshot(), UNSUPPORTED_DEFAULT_CATEGORY).isEmpty());
        assertEquals("_default_ping$count", requireParameterDef(input, "IncompatibleDefaultType", "ping", "count")
                .getDefaultValueFunc());
    }

    @Test
    void sameNameStaticInstancePairDefaultsAreRejected() throws Exception {
        var input = analyze("static_instance_collision.gd", """
                class_name StaticInstanceCollision
                extends RefCounted
                
                static func ping(count = 1):
                    return count
                
                func ping(count = 2):
                    return count
                """);

        // Godot GDScript forbids same-name functions in one class; gdcc keeps parameter defaults
        // fail-closed under any same-name sibling. Each defaulted parameter earns exactly one
        // diagnostic anchored at its default root, and neither function receives metadata.
        var pingFunctions = findFunctions(input.unit().ast(), "ping");
        assertEquals(2, pingFunctions.size());
        var ownerDiagnostics = diagnosticsByCategory(input.diagnostics().snapshot(), UNSUPPORTED_DEFAULT_CATEGORY);
        assertEquals(2, ownerDiagnostics.size(), input.diagnostics().snapshot()::toString);
        for (var ping : pingFunctions) {
            var defaultRoot = ping.parameters().getLast().defaultValue();
            assertNotNull(defaultRoot);
            assertTrue(ownerDiagnostics.stream().anyMatch(diagnostic ->
                            diagnostic.range().equals(FrontendRange.fromAstRange(defaultRoot.range()))),
                    ownerDiagnostics::toString);
        }
        var classDef = requireClassDef(input, "StaticInstanceCollision");
        for (var function : classDef.getFunctions()) {
            if (function.getName().equals("ping")) {
                assertNull(function.getParameter("count").getDefaultValueFunc());
            }
        }
    }

    @Test
    void sameStaticnessOverloadsWithDefaultsAreRejected() throws Exception {
        var input = analyze("same_staticness_overload.gd", """
                class_name SameStaticnessOverload
                extends RefCounted
                
                func ping(value = 1):
                    return value
                
                func ping(a, value = 2):
                    return value
                """);

        // Same-staticness overloads are also fail-closed even though the lowering triple
        // (name, static, arity) could tell them apart: without rejection both would synthesize
        // `_default_ping$value`.
        assertRejectedDefaultsWithoutIsland(input, "SameStaticnessOverload", "ping", 2);
    }

    @Test
    void sameArityOverloadsWithDifferentParameterNamesAreRejected() throws Exception {
        var input = analyze("same_arity_overload.gd", """
                class_name SameArityOverload
                extends RefCounted
                
                func ping(a = 1):
                    return a
                
                func ping(b = 2):
                    return b
                """);

        // The original ambiguity crash path: identical (name, static, arity) makes the skeleton
        // match pick a sibling whose parameter names drift. The fail-closed sibling check runs
        // before any parameter access, so both functions are rejected without an exception.
        assertRejectedDefaultsWithoutIsland(input, "SameArityOverload", "ping", 2);
    }

    @Test
    void sameNameSiblingWithoutDefaultStillRejectsDefaultedFunction() throws Exception {
        var input = analyze("one_sided_default_pair.gd", """
                class_name OneSidedDefaultPair
                extends RefCounted
                
                static func ping(a):
                    return a
                
                func ping(count = 2):
                    return count
                """);

        // The conflict is name-only: the sibling carries no default, yet the defaulted function
        // is still rejected so the synthetic-name contract never depends on sibling content.
        assertRejectedDefaultsWithoutIsland(input, "OneSidedDefaultPair", "ping", 1);
    }

    /// Asserts the same-name fail-closed contract for every `ping` overload: exactly
    /// `expectedDiagnosticCount` diagnostics anchored at the respective default roots, no metadata
    /// on any defaulted parameter, and no published expression facts (the island never ran).
    private static void assertRejectedDefaultsWithoutIsland(
            @NotNull AnalyzedInput input,
            @NotNull String className,
            @NotNull String functionName,
            int expectedDiagnosticCount
    ) {
        var ownerDiagnostics = diagnosticsByCategory(input.diagnostics().snapshot(), UNSUPPORTED_DEFAULT_CATEGORY);
        assertEquals(expectedDiagnosticCount, ownerDiagnostics.size(), input.diagnostics().snapshot()::toString);
        var defaultedRoots = new ArrayList<Node>();
        for (var function : findFunctions(input.unit().ast(), functionName)) {
            for (var parameter : function.parameters()) {
                if (parameter.defaultValue() != null) {
                    defaultedRoots.add(parameter.defaultValue());
                }
            }
        }
        assertEquals(expectedDiagnosticCount, defaultedRoots.size());
        for (var defaultRoot : defaultedRoots) {
            assertTrue(ownerDiagnostics.stream().anyMatch(diagnostic ->
                            diagnostic.range().equals(FrontendRange.fromAstRange(defaultRoot.range()))),
                    ownerDiagnostics::toString);
            assertNull(input.analysisData().expressionTypes().get(defaultRoot));
        }
        var classDef = requireClassDef(input, className);
        for (var function : classDef.getFunctions()) {
            if (!function.getName().equals(functionName)) {
                continue;
            }
            for (var index = 0; index < function.getParameterCount(); index++) {
                assertNull(function.getParameter(index).getDefaultValueFunc());
            }
        }
    }

    @Test
    void failedMemberAccessRetainsResolutionDiagnosticAndReclaims() throws Exception {
        var input = analyze("failed_member_default.gd", """
                class_name FailedMemberDefault
                extends RefCounted
                
                func ping(copy = self.missing_member):
                    return copy
                """);

        // FAILED traces keep reporting inside the island: the genuine resolution failure is the
        // single diagnostic, the owner stays silent, and the metadata is reclaimed because the
        // root is FAILED rather than RESOLVED/DYNAMIC.
        var memberDiagnostics = diagnosticsByCategory(input.diagnostics().snapshot(), "sema.member_resolution");
        assertEquals(1, memberDiagnostics.size(), input.diagnostics().snapshot()::toString);
        assertTrue(diagnosticsByCategory(input.diagnostics().snapshot(), UNSUPPORTED_DEFAULT_CATEGORY).isEmpty());
        assertTrue(diagnosticsByCategory(input.diagnostics().snapshot(), "sema.binding").isEmpty());

        var ping = findFunction(input.unit().ast(), "ping");
        var defaultRoot = ping.parameters().getLast().defaultValue();
        var rootType = input.analysisData().expressionTypes().get(defaultRoot);
        assertNotNull(rootType);
        assertEquals(FrontendExpressionTypeStatus.FAILED, rootType.status());
        assertNull(requireParameterDef(input, "FailedMemberDefault", "ping", "copy").getDefaultValueFunc());
    }

    @Test
    void unrecordedLambdaDefaultWithMatchStaysFailClosedAndReclaims() throws Exception {
        var input = analyze("lambda_match_default.gd", """
                class_name LambdaMatchDefault
                extends Node
                
                func ping(cb = func():
                    var value = 1
                    match value:
                        1:
                            return 1
                ):
                    return cb
                """);

        // The unrecorded lambda keeps the existing fail-closed diagnostic anchored at the lambda
        // (the default root); the nested match is never scanned, the owner adds nothing, and the
        // metadata is reclaimed.
        var unsupportedSubtreeDiagnostics = diagnosticsByCategory(
                input.diagnostics().snapshot(),
                "sema.unsupported_binding_subtree"
        );
        assertEquals(1, unsupportedSubtreeDiagnostics.size(), input.diagnostics().snapshot()::toString);
        assertTrue(diagnosticsByCategory(input.diagnostics().snapshot(), UNSUPPORTED_DEFAULT_CATEGORY).isEmpty());
        assertNull(requireParameterDef(input, "LambdaMatchDefault", "ping", "cb").getDefaultValueFunc());
    }

    private static @NotNull AnalyzedInput analyze(
            @NotNull String fileName,
            @NotNull String source
    ) throws Exception {
        var parserService = new GdScriptParserService();
        var diagnostics = new DiagnosticManager();
        var unit = parserService.parseUnit(Path.of("tmp", fileName), source, diagnostics);
        assertTrue(diagnostics.isEmpty(), () -> "Unexpected parse diagnostics: " + diagnostics.snapshot());

        var analysisData = new FrontendSemanticAnalyzer().analyze(
                new FrontendModule("test_module", List.of(unit)),
                new ClassRegistry(ExtensionApiLoader.loadDefault()),
                diagnostics
        );
        return new AnalyzedInput(unit, analysisData, diagnostics);
    }

    private static @NotNull LirClassDef requireClassDef(
            @NotNull AnalyzedInput input,
            @NotNull String className
    ) {
        return input.analysisData().moduleSkeleton().allClassDefs().stream()
                .filter(classDef -> classDef.getName().equals(className))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Class not found: " + className));
    }

    private static @NotNull LirFunctionDef requireFunctionDef(
            @NotNull AnalyzedInput input,
            @NotNull String className,
            @NotNull String functionName
    ) {
        return requireClassDef(input, className).getFunctions().stream()
                .filter(function -> function.getName().equals(functionName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Function not found: " + functionName));
    }

    private static @NotNull LirParameterDef requireParameterDef(
            @NotNull AnalyzedInput input,
            @NotNull String className,
            @NotNull String functionName,
            @NotNull String parameterName
    ) {
        var parameterDef = requireFunctionDef(input, className, functionName).getParameter(parameterName);
        if (parameterDef == null) {
            throw new AssertionError("Parameter not found: " + parameterName);
        }
        return parameterDef;
    }

    private static @NotNull List<FrontendDiagnostic> diagnosticsByCategory(
            @NotNull DiagnosticSnapshot diagnostics,
            @NotNull String category
    ) {
        return diagnostics.asList().stream()
                .filter(diagnostic -> diagnostic.category().equals(category))
                .toList();
    }

    private static @NotNull FunctionDeclaration findFunction(
            @NotNull SourceFile sourceFile,
            @NotNull String name
    ) {
        return findNode(
                sourceFile,
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals(name)
        );
    }

    private static @NotNull List<FunctionDeclaration> findFunctions(
            @NotNull SourceFile sourceFile,
            @NotNull String name
    ) {
        var matches = new ArrayList<FunctionDeclaration>();
        collectMatchingNodes(
                sourceFile,
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals(name),
                matches
        );
        return matches;
    }

    private static <T extends Node> @NotNull T findNode(
            @NotNull Node root,
            @NotNull Class<T> nodeType,
            @NotNull Predicate<T> predicate
    ) {
        var matches = new ArrayList<T>();
        collectMatchingNodes(root, nodeType, predicate, matches);
        return matches.stream()
                .findFirst()
                .orElseThrow(() -> new AssertionError("Node not found: " + nodeType.getSimpleName()));
    }

    private static <T extends Node> void collectMatchingNodes(
            @NotNull Node node,
            @NotNull Class<T> nodeType,
            @NotNull Predicate<T> predicate,
            @NotNull List<T> matches
    ) {
        Objects.requireNonNull(node, "node must not be null");
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

    private record AnalyzedInput(
            @NotNull FrontendSourceUnit unit,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull DiagnosticManager diagnostics
    ) {
    }
}
