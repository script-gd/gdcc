package gd.script.gdcc.frontend.sema.analyzer;

import dev.superice.gdparser.frontend.ast.EnumDeclaration;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.LiteralExpression;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.Statement;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
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
import gd.script.gdcc.frontend.sema.FrontendExpressionTypeStatus;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.lir.LirParameterDef;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.scope.GdScriptEnumConstant;
import gd.script.gdcc.scope.GdScriptEnumGroup;
import gd.script.gdcc.scope.ScopeLookupStatus;
import gd.script.gdcc.type.GdDictionaryType;
import gd.script.gdcc.type.GdIntType;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Step 4 acceptance for `frontend_enum_plan.md`: bare enum constants flow through top binding /
/// visible value resolver / expression typing with zero changes (`CONSTANT` kind, enum metadata
/// provenance, int/Dictionary published types), property-initializer and parameter-default
/// islands consume them, and a function-body `enum` statement is closed by one
/// `sema.unsupported_binding_subtree` error anchored at the declaration root while the rejected
/// subtree publishes no facts and sibling statements survive.
class FrontendEnumBodyBindingExprTypeTest {
    @Test
    void bareAnonymousEnumMemberPublishesConstantBindingAndIntType() throws Exception {
        var analyzed = analyze("enum_body_bare_member.gd", """
                class_name EnumBodyBareMember
                extends RefCounted

                enum { IDLE, RUNNING }

                func ping() -> int:
                    return IDLE
                """);
        var ping = findFunction(analyzed.unit().ast(), "ping");
        var idle = findNode(ping, IdentifierExpression.class, identifier -> identifier.name().equals("IDLE"));

        assertTrue(analyzed.diagnostics().isEmpty(), () -> "Unexpected diagnostics: " + analyzed.diagnostics());
        var binding = analyzed.analysisData().symbolBindings().get(idle);
        assertNotNull(binding);
        assertAll(
                () -> assertEquals(FrontendBindingKind.CONSTANT, binding.kind()),
                () -> assertEquals(ScopeLookupStatus.FOUND_ALLOWED, binding.valueAccessStatus()),
                () -> {
                    var constant = assertInstanceOf(GdScriptEnumConstant.class, binding.declarationSite());
                    assertEquals("IDLE", constant.memberName());
                    assertEquals(0L, constant.value());
                    assertNull(constant.groupName());
                },
                () -> {
                    var type = analyzed.analysisData().expressionTypes().get(idle);
                    assertNotNull(type);
                    assertEquals(FrontendExpressionTypeStatus.RESOLVED, type.status());
                    assertSame(GdIntType.INT, type.publishedType());
                }
        );
    }

    @Test
    void bareNamedEnumGroupPublishesConstantBindingAndDictionaryType() throws Exception {
        var analyzed = analyze("enum_body_bare_group.gd", """
                class_name EnumBodyBareGroup
                extends RefCounted

                enum State { IDLE, JUMP }

                func ping():
                    var copy = State
                """);
        var ping = findFunction(analyzed.unit().ast(), "ping");
        var state = findNode(ping, IdentifierExpression.class, identifier -> identifier.name().equals("State"));

        assertTrue(analyzed.diagnostics().isEmpty(), () -> "Unexpected diagnostics: " + analyzed.diagnostics());
        var binding = analyzed.analysisData().symbolBindings().get(state);
        assertNotNull(binding);
        assertAll(
                () -> assertEquals(FrontendBindingKind.CONSTANT, binding.kind()),
                () -> assertEquals(ScopeLookupStatus.FOUND_ALLOWED, binding.valueAccessStatus()),
                () -> {
                    var group = assertInstanceOf(GdScriptEnumGroup.class, binding.declarationSite());
                    assertEquals("State", group.name());
                    assertEquals(2, group.members().size());
                },
                () -> {
                    var type = analyzed.analysisData().expressionTypes().get(state);
                    assertNotNull(type);
                    assertEquals(FrontendExpressionTypeStatus.RESOLVED, type.status());
                    assertInstanceOf(GdDictionaryType.class, type.publishedType());
                }
        );
    }

    @Test
    void localVarShadowsEnumConstantBinding() throws Exception {
        var analyzed = analyze("enum_body_local_shadowing.gd", """
                class_name EnumBodyLocalShadowing
                extends RefCounted

                enum { IDLE }

                func ping():
                    var IDLE = 5
                    return IDLE
                """);
        var ping = findFunction(analyzed.unit().ast(), "ping");
        // `var IDLE` stores its name as a plain string, so the only IdentifierExpression is the
        // return-site use, which must resolve to the shadowing local instead of the class constant.
        var idle = findNode(ping, IdentifierExpression.class, identifier -> identifier.name().equals("IDLE"));

        assertTrue(analyzed.diagnostics().isEmpty(), () -> "Unexpected diagnostics: " + analyzed.diagnostics());
        var binding = analyzed.analysisData().symbolBindings().get(idle);
        assertNotNull(binding);
        assertAll(
                () -> assertEquals(FrontendBindingKind.LOCAL_VAR, binding.kind()),
                // The use site must carry the shadowing local's own declaration, not the
                // class-layer enum constant metadata.
                () -> assertSame(
                        findVariable(ping.body().statements(), "IDLE"),
                        binding.declarationSite()
                )
        );
    }

    @Test
    void unknownIdentifierAlongsideEnumKeepsUnknownBindingPath() throws Exception {
        var analyzed = analyze("enum_body_unknown_beside_enum.gd", """
                class_name EnumBodyUnknownBesideEnum
                extends RefCounted

                enum { IDLE }

                func ping():
                    var ok_value = IDLE
                    return MISSING_NAME
                """);
        var ping = findFunction(analyzed.unit().ast(), "ping");
        var idle = findNode(ping, IdentifierExpression.class, identifier -> identifier.name().equals("IDLE"));
        var missing = findNode(ping, IdentifierExpression.class, identifier -> identifier.name().equals("MISSING_NAME"));

        var missingBinding = analyzed.analysisData().symbolBindings().get(missing);
        assertNotNull(missingBinding);
        assertEquals(FrontendBindingKind.UNKNOWN, missingBinding.kind());
        var bindingErrors = diagnosticsByCategory(analyzed.diagnostics(), "sema.binding");
        assertEquals(1, bindingErrors.size(), () -> "Unexpected diagnostics: " + analyzed.diagnostics());
        assertTrue(bindingErrors.getFirst().message().contains("MISSING_NAME"));
        // The enum constant sibling fact is unaffected by the unknown-name fallout.
        var idleBinding = analyzed.analysisData().symbolBindings().get(idle);
        assertNotNull(idleBinding);
        assertEquals(FrontendBindingKind.CONSTANT, idleBinding.kind());
    }

    @Test
    void functionBodyNamedEnumEmitsSingleBoundaryDiagnosticAndSkipsSubtree() throws Exception {
        var analyzed = analyze("enum_body_named_rejected.gd", """
                class_name EnumBodyNamedRejected
                extends RefCounted

                func ping():
                    var before := 1
                    enum Inside { A = 1 }
                    var after := 2
                """);
        var ping = findFunction(analyzed.unit().ast(), "ping");
        var enumDeclaration = findNode(ping, EnumDeclaration.class, declaration -> "Inside".equals(declaration.name()));
        var memberValue = findNode(enumDeclaration, LiteralExpression.class, literal -> true);

        var diagnostics = analyzed.diagnostics().asList();
        assertEquals(1, diagnostics.size(), () -> "Unexpected diagnostics: " + diagnostics);
        var diagnostic = diagnostics.getFirst();
        assertAll(
                () -> assertEquals(FrontendDiagnosticSeverity.ERROR, diagnostic.severity()),
                () -> assertEquals("sema.unsupported_binding_subtree", diagnostic.category()),
                () -> assertEquals(FrontendRange.fromAstRange(enumDeclaration.range()), diagnostic.range()),
                // The resolver never descends into the rejected root: no facts for nodes inside.
                () -> assertNull(analyzed.analysisData().symbolBindings().get(enumDeclaration)),
                () -> assertNull(analyzed.analysisData().expressionTypes().get(memberValue)),
                () -> assertNull(analyzed.analysisData().symbolBindings().get(memberValue)),
                // The skeleton→scope skip side table stays untouched: body phase has no consumer.
                () -> assertFalse(analyzed.analysisData().skippedSubtreeRoots().containsKey(enumDeclaration))
        );
        // Sibling statements around the rejected enum keep publishing facts.
        assertSiblingSlots(analyzed.analysisData(), ping);
    }

    @Test
    void functionBodyAnonymousEnumEmitsBoundaryDiagnosticAndKeepsSiblings() throws Exception {
        var analyzed = analyze("enum_body_anonymous_rejected.gd", """
                class_name EnumBodyAnonymousRejected
                extends RefCounted

                func ping():
                    var before := 1
                    enum { B = 2 }
                    var after := 3
                """);
        var ping = findFunction(analyzed.unit().ast(), "ping");
        var enumDeclaration = findNode(ping, EnumDeclaration.class, declaration -> declaration.name() == null);
        var memberValue = findNode(enumDeclaration, LiteralExpression.class, literal -> true);

        var diagnostics = analyzed.diagnostics().asList();
        assertEquals(1, diagnostics.size(), () -> "Unexpected diagnostics: " + diagnostics);
        var diagnostic = diagnostics.getFirst();
        assertAll(
                () -> assertEquals(FrontendDiagnosticSeverity.ERROR, diagnostic.severity()),
                () -> assertEquals("sema.unsupported_binding_subtree", diagnostic.category()),
                () -> assertEquals(FrontendRange.fromAstRange(enumDeclaration.range()), diagnostic.range()),
                // Same structural-stop canary as the named-enum case: the member value
                // expression publishes no facts, and the skip side table stays untouched.
                () -> assertNull(analyzed.analysisData().expressionTypes().get(memberValue)),
                () -> assertFalse(analyzed.analysisData().skippedSubtreeRoots().containsKey(enumDeclaration))
        );
        assertSiblingSlots(analyzed.analysisData(), ping);
    }

    @Test
    void functionBodyEnumDoesNotLeakNamesIntoScope() throws Exception {
        var analyzed = analyze("enum_body_no_scope_leak.gd", """
                class_name EnumBodyNoScopeLeak
                extends RefCounted

                func ping():
                    enum Inside { LEAKED }
                    return LEAKED
                """);
        var ping = findFunction(analyzed.unit().ast(), "ping");
        var leaked = findNode(ping, IdentifierExpression.class, identifier -> identifier.name().equals("LEAKED"));

        // The rejected enum never reaches skeleton/scope, so its members stay unresolvable.
        var leakedBinding = analyzed.analysisData().symbolBindings().get(leaked);
        assertNotNull(leakedBinding);
        assertEquals(FrontendBindingKind.UNKNOWN, leakedBinding.kind());
        assertEquals(1, diagnosticsByCategory(analyzed.diagnostics(), "sema.unsupported_binding_subtree").size());
        assertEquals(1, diagnosticsByCategory(analyzed.diagnostics(), "sema.binding").size());
    }

    @Test
    void propertyInitializerConsumesBareEnumConstant() throws Exception {
        var analyzed = analyze("enum_property_initializer.gd", """
                class_name EnumPropertyInitializer
                extends RefCounted

                enum { IDLE, RUNNING }

                var speed: int = RUNNING
                """);
        var speed = findNode(
                analyzed.unit().ast(),
                VariableDeclaration.class,
                declaration -> declaration.name().equals("speed")
        );
        var running = assertInstanceOf(IdentifierExpression.class, speed.value());

        assertTrue(analyzed.diagnostics().isEmpty(), () -> "Unexpected diagnostics: " + analyzed.diagnostics());
        var binding = analyzed.analysisData().symbolBindings().get(running);
        assertNotNull(binding);
        assertAll(
                () -> assertEquals(FrontendBindingKind.CONSTANT, binding.kind()),
                () -> assertEquals(
                        1L,
                        assertInstanceOf(GdScriptEnumConstant.class, binding.declarationSite()).value()
                ),
                () -> {
                    var type = analyzed.analysisData().expressionTypes().get(running);
                    assertNotNull(type);
                    assertEquals(FrontendExpressionTypeStatus.RESOLVED, type.status());
                    assertSame(GdIntType.INT, type.publishedType());
                }
        );
    }

    @Test
    void parameterDefaultConsumesBareEnumConstant() throws Exception {
        var analyzed = analyze("enum_parameter_default.gd", """
                class_name EnumParameterDefault
                extends RefCounted

                enum { IDLE }

                func ping(count = IDLE):
                    return count

                static func pong(count = IDLE):
                    return count
                """);

        assertTrue(analyzed.diagnostics().isEmpty(), () -> "Unexpected diagnostics: " + analyzed.diagnostics());
        // Static and instance callables share the same class-constant allowance
        // (`ResolveRestriction.allowClassConstants`), so both defaults resolve identically;
        // static owners keep the `_default_s_` metadata naming infix.
        assertAll(
                () -> assertEnumConstantDefault(analyzed, "ping", "_default_ping$count"),
                () -> assertEnumConstantDefault(analyzed, "pong", "_default_s_pong$count")
        );
    }

    /// Asserts one parameter default shaped as `= IDLE`: the default root binds the enum constant
    /// with int type, and the accepted island records its `_default_<func>$<param>` metadata.
    private static void assertEnumConstantDefault(
            @NotNull AnalyzedInput analyzed,
            @NotNull String functionName,
            @NotNull String expectedDefaultValueFunc
    ) {
        var function = findFunction(analyzed.unit().ast(), functionName);
        var defaultRoot = assertInstanceOf(
                IdentifierExpression.class,
                function.parameters().getFirst().defaultValue()
        );
        var binding = analyzed.analysisData().symbolBindings().get(defaultRoot);
        assertNotNull(binding);
        assertEquals(FrontendBindingKind.CONSTANT, binding.kind());
        assertInstanceOf(GdScriptEnumConstant.class, binding.declarationSite());
        var type = analyzed.analysisData().expressionTypes().get(defaultRoot);
        assertNotNull(type);
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, type.status());
        assertSame(GdIntType.INT, type.publishedType());
        assertEquals(expectedDefaultValueFunc, requireParameterDef(analyzed, functionName, "count").getDefaultValueFunc());
    }

    private static void assertSiblingSlots(@NotNull FrontendAnalysisData analysisData, @NotNull FunctionDeclaration owner) {
        var before = findVariable(owner.body().statements(), "before");
        var after = findVariable(owner.body().statements(), "after");
        assertEquals(GdIntType.INT, analysisData.slotTypes().get(before));
        assertEquals(GdIntType.INT, analysisData.slotTypes().get(after));
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
        return new AnalyzedInput(unit, analysisData, diagnostics.snapshot());
    }

    private static @NotNull LirParameterDef requireParameterDef(
            @NotNull AnalyzedInput analyzed,
            @NotNull String functionName,
            @NotNull String parameterName
    ) {
        var functionDef = analyzed.analysisData().moduleSkeleton().allClassDefs().stream()
                .flatMap(classDef -> classDef.getFunctions().stream())
                .filter(candidate -> candidate.getName().equals(functionName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Function not found: " + functionName));
        var parameterDef = functionDef.getParameter(parameterName);
        if (parameterDef == null) {
            throw new AssertionError("Parameter not found: " + parameterName);
        }
        return parameterDef;
    }

    private static @NotNull FunctionDeclaration findFunction(@NotNull Node root, @NotNull String name) {
        return findNode(root, FunctionDeclaration.class, declaration -> declaration.name().equals(name));
    }

    private static @NotNull VariableDeclaration findVariable(
            @NotNull List<? extends Statement> statements,
            @NotNull String name
    ) {
        return statements.stream()
                .filter(VariableDeclaration.class::isInstance)
                .map(VariableDeclaration.class::cast)
                .filter(declaration -> declaration.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Variable not found: " + name));
    }

    private static @NotNull List<FrontendDiagnostic> diagnosticsByCategory(
            @NotNull DiagnosticSnapshot diagnostics,
            @NotNull String category
    ) {
        return diagnostics.asList().stream()
                .filter(diagnostic -> diagnostic.category().equals(category))
                .toList();
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
        if (nodeType.isInstance(node) && predicate.test(nodeType.cast(node))) {
            matches.add(nodeType.cast(node));
        }
        for (var child : node.getChildren()) {
            collectMatchingNodes(child, nodeType, predicate, matches);
        }
    }

    private record AnalyzedInput(
            @NotNull FrontendSourceUnit unit,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull DiagnosticSnapshot diagnostics
    ) {
    }
}
