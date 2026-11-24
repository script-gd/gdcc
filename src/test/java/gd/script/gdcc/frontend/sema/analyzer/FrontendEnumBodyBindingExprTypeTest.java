package gd.script.gdcc.frontend.sema.analyzer;

import dev.superice.gdparser.frontend.ast.Block;
import dev.superice.gdparser.frontend.ast.EnumDeclaration;
import dev.superice.gdparser.frontend.ast.EnumMember;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.LiteralExpression;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.Point;
import dev.superice.gdparser.frontend.ast.Range;
import dev.superice.gdparser.frontend.ast.SourceFile;
import dev.superice.gdparser.frontend.ast.Statement;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import dev.superice.gdparser.frontend.gdscript.DiagnosticCodes;
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
import org.jetbrains.annotations.Nullable;
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

/// Script enum body binding (`frontend_enum_implementation.md`): bare enum constants flow through
/// top binding /
/// visible value resolver / expression typing with zero changes (`CONSTANT` kind, enum metadata
/// provenance, int/Dictionary published types), property-initializer and parameter-default
/// islands consume them. A function-body `enum` is rejected by the parser; if one reaches the
/// body resolver as a hand-built AST, the semantic boundary still rejects it without publishing facts.
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
    void functionBodyNamedEnumKeepsSiblingsInSourceAndAtSemanticBoundary() throws Exception {
        var source = analyzeRejectedSourceEnum("enum_body_named_rejected.gd", """
                class_name EnumBodyNamedRejected
                extends RefCounted

                func ping():
                    var before := 1
                    enum Inside { A = 1 }
                    var after := 2
                """);
        assertEquals(1, source.diagnostics().size(), () -> "Unexpected diagnostics: " + source.diagnostics());
        assertSiblingSlots(source.analysisData(), findFunction(source.unit().ast(), "ping"));

        var manual = analyzeManualLocalEnum(source.unit(), "Inside", "A", "1");
        assertEquals(1, manual.diagnostics().size(), () -> "Unexpected diagnostics: " + manual.diagnostics());
        assertSiblingSlots(manual.analysisData(), findFunction(manual.unit().ast(), "ping"));
    }

    @Test
    void functionBodyAnonymousEnumKeepsSiblingsInSourceAndAtSemanticBoundary() throws Exception {
        var source = analyzeRejectedSourceEnum("enum_body_anonymous_rejected.gd", """
                class_name EnumBodyAnonymousRejected
                extends RefCounted

                func ping():
                    var before := 1
                    enum { B = 2 }
                    var after := 3
                """);
        assertEquals(1, source.diagnostics().size(), () -> "Unexpected diagnostics: " + source.diagnostics());
        assertSiblingSlots(source.analysisData(), findFunction(source.unit().ast(), "ping"));

        var manual = analyzeManualLocalEnum(source.unit(), null, "B", "2");
        assertEquals(1, manual.diagnostics().size(), () -> "Unexpected diagnostics: " + manual.diagnostics());
        assertSiblingSlots(manual.analysisData(), findFunction(manual.unit().ast(), "ping"));
    }

    @Test
    void functionBodyEnumDoesNotLeakNamesIntoScope() throws Exception {
        var source = analyzeRejectedSourceEnum("enum_body_no_scope_leak.gd", """
                class_name EnumBodyNoScopeLeak
                extends RefCounted

                func ping():
                    enum Inside { LEAKED }
                    return LEAKED
                """);
        var manual = analyzeManualLocalEnum(source.unit(), "Inside", "LEAKED", null);
        for (var analyzed : List.of(source, manual)) {
            var ping = findFunction(analyzed.unit().ast(), "ping");
            var leaked = findNode(ping, IdentifierExpression.class, identifier -> identifier.name().equals("LEAKED"));
            var leakedBinding = analyzed.analysisData().symbolBindings().get(leaked);
            assertNotNull(leakedBinding);
            assertEquals(FrontendBindingKind.UNKNOWN, leakedBinding.kind());
            assertEquals(FrontendExpressionTypeStatus.FAILED, analyzed.analysisData().expressionTypes().get(leaked).status());
            var errors = diagnosticsByCategory(analyzed.diagnostics(), "sema.binding");
            assertEquals(1, errors.size());
            assertEquals(FrontendRange.fromAstRange(leaked.range()), errors.getFirst().range());
        }
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
        return analyze(unit, diagnostics);
    }

    private static AnalyzedInput analyzeRejectedSourceEnum(String fileName, String source) throws Exception {
        var diagnostics = new DiagnosticManager();
        var unit = new GdScriptParserService().parseUnit(Path.of("tmp", fileName), source, diagnostics);
        var function = findFunction(unit.ast(), "ping");
        assertEquals(1, diagnostics.snapshot().size());
        assertEquals(1, unit.parseRecoveries().size());
        var diagnostic = diagnostics.snapshot().getFirst();
        var recovery = unit.parseRecoveries().getFirst();
        assertEquals(DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN, diagnostic.category());
        assertEquals(FrontendDiagnosticSeverity.ERROR, diagnostic.severity());
        assertEquals("tmp/" + fileName, diagnostic.sourcePath());
        assertEquals("enum", source.substring(diagnostic.range().startByte(), diagnostic.range().endByte()));
        assertEquals(diagnostic.category(), recovery.code());
        assertEquals(diagnostic.range(), recovery.range());
        assertSame(function, recovery.owner());
        assertEquals("suite.body", recovery.facet());
        assertTrue(function.body().statements().stream().noneMatch(EnumDeclaration.class::isInstance));

        var analyzed = analyze(unit, diagnostics);
        assertEquals(List.of(diagnostic), diagnosticsByCategory(analyzed.diagnostics(), diagnostic.category()));
        assertTrue(diagnosticsByCategory(analyzed.diagnostics(), "sema.unsupported_binding_subtree").isEmpty());
        assertFalse(analyzed.analysisData().skippedSubtreeRoots().containsKey(function));
        assertTrue(analyzed.analysisData().scopesByAst().containsKey(function.body()));
        return analyzed;
    }

    private static AnalyzedInput analyzeManualLocalEnum(
            FrontendSourceUnit rejected, @Nullable String group, String memberName, @Nullable String integer
    ) throws Exception {
        var parserRange = rejected.parseRecoveries().getFirst().range();
        var source = rejected.source();
        var start = parserRange.startByte();
        var end = source.indexOf('}', start) + 1;
        var memberStart = source.indexOf(memberName, start + "enum".length());
        var valueStart = integer == null ? -1 : source.indexOf(integer, memberStart + memberName.length());
        var base = new Point(parserRange.start().line() - 1, parserRange.start().column() - 1);
        var value = integer == null ? null : new LiteralExpression("integer", integer,
                enumLineRange(start, base, valueStart, valueStart + integer.length()));
        var member = new EnumMember(memberName, value, enumLineRange(start, base, memberStart,
                value != null ? value.range().endByte() : memberStart + memberName.length()));
        var declaration = new EnumDeclaration(group, List.of(member), enumLineRange(start, base, start, end));

        // Insert the current parser's rejected shape without any attached recovery so the same
        // frontend entry can exercise its independent semantic guard.
        var function = findFunction(rejected.ast(), "ping");
        var body = new ArrayList<>(function.body().statements());
        var insertion = 0;
        while (insertion < body.size() && body.get(insertion).range().startByte() < start) {
            insertion++;
        }
        body.add(insertion, declaration);
        var manualFunction = new FunctionDeclaration(function.name(), function.parameters(), function.returnType(),
                function.isStatic(), new Block(body, function.body().range()), function.range());
        var statements = rejected.ast().statements().stream()
                .map(statement -> statement == function ? manualFunction : statement)
                .toList();
        var unit = new FrontendSourceUnit(rejected.path(), source, new SourceFile(statements, rejected.ast().range()));
        assertTrue(unit.parseRecoveries().isEmpty());
        var analyzed = analyze(unit, new DiagnosticManager());
        assertTrue(analyzed.analysisData().parseRecoveriesByOwner().isEmpty());
        var errors = diagnosticsByCategory(analyzed.diagnostics(), "sema.unsupported_binding_subtree");
        assertEquals(1, errors.size());
        assertEquals(FrontendDiagnosticSeverity.ERROR, errors.getFirst().severity());
        assertEquals(FrontendRange.fromAstRange(declaration.range()), errors.getFirst().range());
        assertNull(analyzed.analysisData().symbolBindings().get(declaration));
        assertFalse(analyzed.analysisData().skippedSubtreeRoots().containsKey(declaration));
        if (value != null) {
            assertNull(analyzed.analysisData().expressionTypes().get(value));
            assertNull(analyzed.analysisData().symbolBindings().get(value));
        }
        return analyzed;
    }

    private static Range enumLineRange(int lineStart, Point base, int start, int end) {
        return new Range(start, end,
                new Point(base.row(), base.column() + start - lineStart),
                new Point(base.row(), base.column() + end - lineStart));
    }

    private static AnalyzedInput analyze(FrontendSourceUnit unit, DiagnosticManager diagnostics) throws Exception {
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
