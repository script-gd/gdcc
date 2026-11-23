package gd.script.gdcc.frontend.sema.analyzer.support;

import dev.superice.gdparser.frontend.ast.Expression;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import dev.superice.gdparser.frontend.gdscript.GdScriptParserFacade;
import gd.script.gdcc.frontend.constant.Evaluation;
import gd.script.gdcc.frontend.constant.GdConstantValue;
import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.scope.AbstractFrontendScope;
import gd.script.gdcc.frontend.scope.BlockScope;
import gd.script.gdcc.frontend.scope.BlockScopeKind;
import gd.script.gdcc.frontend.sema.FrontendAnalysisData;
import gd.script.gdcc.frontend.sema.FrontendBodyDeclarationIndex;
import gd.script.gdcc.frontend.sema.FrontendInterfaceSurface;
import gd.script.gdcc.frontend.sema.FrontendModuleSkeleton;
import gd.script.gdcc.frontend.sema.FrontendSuiteEntryRoots;
import gd.script.gdcc.frontend.sema.FrontendTypedLexicalBaseline;
import gd.script.gdcc.frontend.sema.FrontendTypedLexicalEnvironment;
import gd.script.gdcc.frontend.sema.analyzer.FrontendSuiteContext;
import gd.script.gdcc.frontend.sema.resolver.FrontendVisibleValueDomain;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.scope.FunctionDef;
import gd.script.gdcc.scope.ResolveRestriction;
import gd.script.gdcc.scope.Scope;
import gd.script.gdcc.scope.ScopeLookupResult;
import gd.script.gdcc.scope.ScopeValue;
import gd.script.gdcc.type.GdFloatVectorType;
import gd.script.gdcc.type.GdIntType;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontendLocalConstLookupContextTest {
    @Test
    void matchKeyContextFoldsQualifiedEnumValuesWithoutWideningOrdinaryConstLookup() throws Exception {
        var declarations = parseDeclarations("""
                const GLOBAL = Side.SIDE_LEFT + 1
                const ERROR_CODE = Error.OK
                const AXIS = Vector2.AXIS_X
                const ZERO = Vector2.ZERO
                """);
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var scope = new BlockScope(registry, BlockScopeKind.MATCH_SECTION_BODY);
        var suite = suiteContext(registry, registry, declarations.getFirst());

        var global = declarations.get(0);
        var errorCode = declarations.get(1);
        var axis = declarations.get(2);
        var zero = declarations.get(3);
        assertEquals(new GdConstantValue.IntValue(1L), resolvedMatchKey(global, scope, suite));
        assertEquals(new GdConstantValue.IntValue(0L), resolvedMatchKey(errorCode, scope, suite));
        assertEquals(new GdConstantValue.IntValue(0L), resolvedMatchKey(axis, scope, suite));
        var zeroValue = GdConstantValue.BuiltinValue.ofFloats(GdFloatVectorType.VECTOR2, 0.0, 0.0);
        assertEquals(zeroValue, resolvedMatchKey(zero, scope, suite));

        for (var declaration : List.of(global, errorCode, axis)) {
            var ordinary = new FrontendLocalConstLookupContext(declaration, scope, suite);
            assertInstanceOf(Evaluation.Rejected.class,
                    FrontendConstantEvaluator.evaluate(initializer(declaration), ordinary, EvaluationPolicy.general()));
        }
        var ordinaryZero = new FrontendLocalConstLookupContext(zero, scope, suite);
        assertEquals(new Evaluation.Resolved(zeroValue),
                FrontendConstantEvaluator.evaluate(initializer(zero), ordinaryZero, EvaluationPolicy.general()));
    }

    @Test
    void matchKeyContextUsesTypeMetaFallbackOnlyForQualifiedEnumValues() throws Exception {
        var declarations = parseDeclarations("""
                const MEMBER = Side.SIDE_LEFT
                const BARE = Side
                const MISSING = Side.NO_SUCH_MEMBER
                const CONSTRUCTOR = Error(1)
                """);
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var restriction = ResolveRestriction.instanceContext();
        var typeOnlyScope = new AbstractFrontendScope(null) {
            @Override
            public @NotNull ScopeLookupResult<ScopeValue> resolveValueHere(
                    @NotNull String name, @NotNull ResolveRestriction restriction
            ) {
                return ScopeLookupResult.notFound();
            }

            @Override
            public @NotNull ScopeLookupResult<List<FunctionDef>> resolveFunctionsHere(
                    @NotNull String name, @NotNull ResolveRestriction restriction
            ) {
                return ScopeLookupResult.notFound();
            }
        };
        typeOnlyScope.defineTypeMeta(registry.resolveTypeMetaHere("Side", restriction).requireValue());
        typeOnlyScope.defineTypeMeta(registry.resolveTypeMetaHere("Error", restriction).requireValue());
        var suite = suiteContext(typeOnlyScope, registry, declarations.getFirst());
        var member = declarations.getFirst();
        var keyScope = FrontendLocalConstLookupContext.forMatchDictionaryKey(
                initializer(member), typeOnlyScope, suite);

        var namespace = assertInstanceOf(LookupContext.LookupResult.ResolvedNamespace.class, keyScope.resolve("Side"));
        assertSame(registry.findGlobalEnum("Side"), namespace.namespaceIdentity());
        assertEquals(new GdConstantValue.IntValue(0L), resolvedMatchKey(member, typeOnlyScope, suite));
        assertInstanceOf(LookupContext.LookupResult.Unknown.class,
                keyScope.resolveMember(namespace.namespaceIdentity(), "NO_SUCH_MEMBER"));

        var ordinary = new FrontendLocalConstLookupContext(member, typeOnlyScope, suite);
        assertInstanceOf(LookupContext.LookupResult.NonConstant.class, ordinary.resolve("Side"));
        assertRejectedMatchKey(declarations.get(1), typeOnlyScope, suite, "namespace, not a value");
        assertRejectedMatchKey(declarations.get(2), typeOnlyScope, suite, "NO_SUCH_MEMBER");
        assertRejectedMatchKey(declarations.get(3), typeOnlyScope, suite, "namespace that is not a builtin type");
    }

    @Test
    void matchKeyAnchorSkipsLaterBindingsButRespectsVisibleShadowsAndBlockedConstants() throws Exception {
        var declarations = parseDeclarations("""
                const BEFORE = Side.SIDE_LEFT
                var Side = 5
                const AFTER = Side.SIDE_LEFT
                """);
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var before = declarations.getFirst();
        var shadow = declarations.get(1);
        var after = declarations.getLast();
        var suite = suiteContext(registry, registry, before);
        var localScope = new BlockScope(registry, BlockScopeKind.MATCH_SECTION_BODY);
        localScope.defineLocal("Side", GdIntType.INT, shadow);

        assertEquals(new GdConstantValue.IntValue(0L), resolvedMatchKey(before, localScope, suite));
        assertRejectedMatchKey(after, localScope, suite, "mutable local variable");

        var blockedScope = new BlockScope(registry, BlockScopeKind.MATCH_SECTION_BODY);
        blockedScope.defineConstant("Side", GdIntType.INT, shadow);
        var lookup = FrontendLocalConstLookupContext.forMatchDictionaryKey(initializer(after), blockedScope, suite);
        assertInstanceOf(LookupContext.LookupResult.Blocked.class, lookup.resolve("Side"));
    }

    private static @NotNull List<VariableDeclaration> parseDeclarations(@NotNull String source) {
        var parsed = new GdScriptParserFacade().parse(source);
        assertTrue(parsed.diagnostics().isEmpty(), () -> parsed.diagnostics().toString());
        return parsed.ast().statements().stream()
                .map(statement -> assertInstanceOf(VariableDeclaration.class, statement))
                .toList();
    }

    private static @NotNull Expression initializer(@NotNull VariableDeclaration declaration) {
        var expression = declaration.value();
        assertNotNull(expression);
        return expression;
    }

    private static @NotNull GdConstantValue resolvedMatchKey(
            @NotNull VariableDeclaration declaration, @NotNull Scope keyScope, @NotNull FrontendSuiteContext suite
    ) {
        return assertInstanceOf(Evaluation.Resolved.class, evaluateMatchKey(declaration, keyScope, suite)).value();
    }

    private static void assertRejectedMatchKey(
            @NotNull VariableDeclaration declaration,
            @NotNull Scope keyScope,
            @NotNull FrontendSuiteContext suite,
            @NotNull String reasonFragment
    ) {
        var rejection = assertInstanceOf(Evaluation.Rejected.class, evaluateMatchKey(declaration, keyScope, suite));
        assertTrue(rejection.reason().contains(reasonFragment), rejection.reason());
    }

    private static @NotNull Evaluation evaluateMatchKey(
            @NotNull VariableDeclaration declaration, @NotNull Scope keyScope, @NotNull FrontendSuiteContext suite
    ) {
        var key = initializer(declaration);
        var lookup = FrontendLocalConstLookupContext.forMatchDictionaryKey(key, keyScope, suite);
        return FrontendConstantEvaluator.evaluate(key, lookup, EvaluationPolicy.matchConstant());
    }

    private static @NotNull FrontendSuiteContext suiteContext(
            @NotNull Scope scope, @NotNull ClassRegistry registry, @NotNull Node anchor
    ) {
        var diagnostics = new DiagnosticManager();
        var data = FrontendAnalysisData.bootstrap();
        data.updateModuleSkeleton(new FrontendModuleSkeleton("lookup_test", List.of(), Map.of(), diagnostics.snapshot()));
        var surface = new FrontendInterfaceSurface(
                new FrontendBodyDeclarationIndex(Map.of()),
                FrontendTypedLexicalBaseline.builder().build(),
                new FrontendSuiteEntryRoots(List.of(), List.of(), List.of())
        );
        return new FrontendSuiteContext(
                Path.of("lookup_test.gd"), anchor, null, scope, null, ResolveRestriction.instanceContext(),
                false, null, surface, new FrontendTypedLexicalEnvironment(scope, data),
                data, diagnostics, registry, null, null, null, FrontendVisibleValueDomain.EXECUTABLE_BODY
        );
    }
}
