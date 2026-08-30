package gd.script.gdcc.frontend.sema.analyzer;

import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.PreloadExpression;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.diagnostic.FrontendDiagnostic;
import gd.script.gdcc.frontend.diagnostic.FrontendDiagnosticSeverity;
import gd.script.gdcc.frontend.parse.FrontendModule;
import gd.script.gdcc.frontend.parse.GdScriptParserService;
import gd.script.gdcc.frontend.sema.FrontendAnalysisData;
import gd.script.gdcc.frontend.sema.FrontendExpressionTypeStatus;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.scope.ClassRegistry;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Sema contract for the synthetic GDScript language functions
/// (`len`/`char`/`ord`/`range`/`is_instance_of`/`load`) and the `preload` expression: direct bare
/// calls resolve through the shared utility pipeline with Godot 4.5 signatures, user-defined
/// same-name functions shadow the globals, and first-class value references such as `var f = len`
/// are rejected with a `sema.expression_resolution` diagnostic. `preload` requires a string
/// literal path and publishes `RESOLVED(Resource)` without entering the resolved-call table.
class FrontendGdScriptLanguageFunctionSemaTest {
    @Test
    void directCallsResolveWithGodotSignatures() throws Exception {
        var analyzed = analyze(
                "language_function_calls.gd",
                """
                        class_name LanguageFunctionCalls
                        extends Node
                        
                        func run():
                            var n = len("abc")
                            var s = char(65)
                            var o = ord("A")
                        """
        );

        assertExpressionType(analyzed, "run", "n", "int");
        assertExpressionType(analyzed, "run", "s", "String");
        assertExpressionType(analyzed, "run", "o", "int");
        assertEquals(
                List.of(),
                errorDiagnostics(analyzed),
                "direct language-function calls must not produce error diagnostics"
        );
    }

    @Test
    void argumentTypeMismatchProducesExpressionResolutionDiagnostic() throws Exception {
        var analyzed = analyze(
                "language_function_bad_arg.gd",
                """
                        class_name LanguageFunctionBadArg
                        extends Node
                        
                        func run():
                            var bad = char("s")
                        """
        );

        var diagnostics = expressionResolutionDiagnostics(analyzed);
        assertEquals(1, diagnostics.size(), () -> "unexpected diagnostics: " + describe(analyzed));
    }

    @Test
    void userDefinedFunctionShadowsLanguageFunction() throws Exception {
        var analyzed = analyze(
                "language_function_shadow.gd",
                """
                        class_name LanguageFunctionShadow
                        extends Node
                        
                        func len(a) -> String:
                            return a
                        
                        func run():
                            var n = len("abc")
                        """
        );

        // The class-scope method wins over the global synthetic entry, matching Godot's scoping;
        // the String return type (vs the synthetic `int`) proves which overload bound.
        assertExpressionType(analyzed, "run", "n", "String");
        assertEquals(
                List.of(),
                errorDiagnostics(analyzed),
                "shadowed language-function call must not produce error diagnostics"
        );
    }

    @Test
    void firstClassReferenceToLanguageFunctionIsRejected() throws Exception {
        var analyzed = analyze(
                "language_function_first_class.gd",
                """
                        class_name LanguageFunctionFirstClass
                        extends Node
                        
                        func run():
                            var f = len
                        """
        );

        var diagnostics = expressionResolutionDiagnostics(analyzed);
        assertEquals(1, diagnostics.size(), () -> "unexpected diagnostics: " + describe(analyzed));
        assertTrue(diagnostics.getFirst().message().contains("'len'"));
        assertTrue(diagnostics.getFirst().message().contains("first-class value"));
    }

    @Test
    void firstClassReferenceToRegularUtilityStillResolvesToCallable() throws Exception {
        // Regression guard: the ban targets synthetic language functions only; regular extension
        // utilities keep publishing `Callable` for value positions.
        var analyzed = analyze(
                "utility_first_class.gd",
                """
                        class_name UtilityFirstClass
                        extends Node
                        
                        func run():
                            var f = print
                        """
        );

        assertExpressionType(analyzed, "run", "f", "Callable");
        assertEquals(List.of(), expressionResolutionDiagnostics(analyzed));
    }

    @Test
    void rangeCallsResolveToGenericArray() throws Exception {
        var analyzed = analyze(
                "language_function_range.gd",
                """
                        class_name LanguageFunctionRange
                        extends Node
                        
                        func run():
                            var a = range(3)
                            var b = range(1, 5)
                            var c = range(1, 10, 2)
                        """
        );

        // All arities publish the unparameterized `Array` return (Godot MethodInfo alignment).
        assertExpressionType(analyzed, "run", "a", "Array");
        assertExpressionType(analyzed, "run", "b", "Array");
        assertExpressionType(analyzed, "run", "c", "Array");
        assertEquals(
                List.of(),
                errorDiagnostics(analyzed),
                "valid range calls must not produce error diagnostics"
        );
    }

    @Test
    void rangeArityOutsideOneToThreeIsRejected() throws Exception {
        // `range` is registered as vararg with zero fixed parameters (Godot MethodInfo), so
        // generic vararg matching alone would accept any count; the frontend must gate arity.
        var zeroArgs = analyze(
                "language_function_range_zero.gd",
                """
                        class_name LanguageFunctionRangeZero
                        extends Node
                        
                        func run():
                            var bad = range()
                        """
        );
        var fourArgs = analyze(
                "language_function_range_four.gd",
                """
                        class_name LanguageFunctionRangeFour
                        extends Node
                        
                        func run():
                            var bad = range(1, 2, 3, 4)
                        """
        );

        var zeroDiagnostics = expressionResolutionDiagnostics(zeroArgs);
        assertEquals(1, zeroDiagnostics.size(), () -> "unexpected diagnostics: " + describe(zeroArgs));
        assertTrue(zeroDiagnostics.getFirst().message().contains("'range'"));
        assertTrue(zeroDiagnostics.getFirst().message().contains("1 to 3"));
        var fourDiagnostics = expressionResolutionDiagnostics(fourArgs);
        assertEquals(1, fourDiagnostics.size(), () -> "unexpected diagnostics: " + describe(fourArgs));
        assertTrue(fourDiagnostics.getFirst().message().contains("'range'"));
        assertTrue(fourDiagnostics.getFirst().message().contains("1 to 3"));
    }

    @Test
    void userDefinedRangeShadowingBypassesSyntheticArityGate() throws Exception {
        // The arity gate targets the synthetic `range` only; a user-defined shadow keeps its own
        // signature (here four parameters), matching Godot scoping.
        var analyzed = analyze(
                "language_function_range_shadow.gd",
                """
                        class_name LanguageFunctionRangeShadow
                        extends Node
                        
                        func range(a, b, c, d) -> String:
                            return a
                        
                        func run():
                            var n = range(1, 2, 3, 4)
                        """
        );

        assertExpressionType(analyzed, "run", "n", "String");
        assertEquals(List.of(), expressionResolutionDiagnostics(analyzed));
    }

    @Test
    void forHeaderRangeRouteStaysSpecialCased() throws Exception {
        // Regression guard: `for i in range(...)` keeps the RANGE_CALL route and must not pick up
        // the synthetic global function resolution (nor its diagnostics).
        var analyzed = analyze(
                "language_function_for_range.gd",
                """
                        class_name LanguageFunctionForRange
                        extends Node
                        
                        func run():
                            for i in range(3):
                                pass
                        """
        );

        assertEquals(
                List.of(),
                errorDiagnostics(analyzed),
                "for-range must stay diagnostic-free: " + describe(analyzed)
        );
    }

    @Test
    void isInstanceOfResolvesToBool() throws Exception {
        var analyzed = analyze(
                "language_function_is_instance_of.gd",
                """
                        class_name LanguageFunctionIsInstanceOf
                        extends Node
                        
                        func run(x):
                            var ok = is_instance_of(x, TYPE_INT)
                        """
        );

        assertExpressionType(analyzed, "run", "ok", "bool");
        assertEquals(
                List.of(),
                errorDiagnostics(analyzed),
                "is_instance_of call must not produce error diagnostics"
        );
    }

    @Test
    void firstClassReferenceToRangeAndIsInstanceOfIsRejected() throws Exception {
        for (var entry : List.of(
                Map.entry("range", "language_function_range_first_class.gd"),
                Map.entry("is_instance_of", "language_function_is_instance_of_first_class.gd"),
                Map.entry("load", "language_function_load_first_class.gd")
        )) {
            var analyzed = analyze(
                    entry.getValue(),
                    """
                            class_name LanguageFunctionFirstClass
                            extends Node
                            
                            func run():
                                var f = %s
                            """.formatted(entry.getKey())
            );

            var diagnostics = expressionResolutionDiagnostics(analyzed);
            assertEquals(1, diagnostics.size(), () -> "unexpected diagnostics: " + describe(analyzed));
            assertTrue(diagnostics.getFirst().message().contains("'" + entry.getKey() + "'"));
            assertTrue(diagnostics.getFirst().message().contains("first-class value"));
        }
    }

    @Test
    void loadCallResolvesToResource() throws Exception {
        var analyzed = analyze(
                "language_function_load.gd",
                """
                        class_name LanguageFunctionLoad
                        extends Node
                        
                        func run():
                            var r = load("res://icon.svg")
                        """
        );

        assertExpressionType(analyzed, "run", "r", "Resource");
        assertEquals(
                List.of(),
                errorDiagnostics(analyzed),
                "load call must not produce error diagnostics: " + describe(analyzed)
        );
    }

    @Test
    void loadArgumentTypeMismatchProducesExpressionResolutionDiagnostic() throws Exception {
        var analyzed = analyze(
                "language_function_load_bad_arg.gd",
                """
                        class_name LanguageFunctionLoadBadArg
                        extends Node
                        
                        func run():
                            var bad = load(42)
                        """
        );

        var diagnostics = expressionResolutionDiagnostics(analyzed);
        assertEquals(1, diagnostics.size(), () -> "unexpected diagnostics: " + describe(analyzed));
    }

    @Test
    void userDefinedFunctionShadowsLoad() throws Exception {
        // The class-scope method wins over the global synthetic entry (Godot scoping); the String
        // return type (vs the synthetic `Resource`) proves which overload bound.
        var analyzed = analyze(
                "language_function_load_shadow.gd",
                """
                        class_name LanguageFunctionLoadShadow
                        extends Node
                        
                        func load(path) -> String:
                            return path
                        
                        func run():
                            var r = load("res://icon.svg")
                        """
        );

        assertExpressionType(analyzed, "run", "r", "String");
        assertEquals(List.of(), errorDiagnostics(analyzed));
    }

    @Test
    void preloadLiteralResolvesToResourceWithoutResolvedCall() throws Exception {
        var analyzed = analyze(
                "preload_literal.gd",
                """
                        class_name PreloadLiteral
                        extends Node
                        
                        var icon = preload("res://icon.svg")
                        """
        );

        assertPropertyType(analyzed, "icon", "Resource");
        assertEquals(
                List.of(),
                errorDiagnostics(analyzed),
                "literal preload must not produce error diagnostics: " + describe(analyzed)
        );
        // preload publishes no FrontendResolvedCall; the resolved-call key space stays frozen to
        // CallExpression/AttributeCallStep.
        var preloadCallKeys = analyzed.analysisData().resolvedCalls().keySet().stream()
                .filter(PreloadExpression.class::isInstance)
                .toList();
        assertEquals(List.of(), preloadCallKeys, "preload must not publish into resolvedCalls");
    }

    @Test
    void preloadNonLiteralPathIsRejected() throws Exception {
        var variablePath = analyze(
                "preload_variable_path.gd",
                """
                        class_name PreloadVariablePath
                        extends Node
                        
                        func run(path):
                            var bad = preload(path)
                        """
        );
        var concatenatedPath = analyze(
                "preload_concat_path.gd",
                """
                        class_name PreloadConcatPath
                        extends Node
                        
                        func run():
                            var bad = preload("res://" + "icon.svg")
                        """
        );
        // A StringName literal is not a string literal: Godot's preload takes a String path, so
        // the dedicated literal check must reject it rather than silently accepting the wrong
        // payload family.
        var stringNamePath = analyze(
                "preload_stringname_path.gd",
                """
                        class_name PreloadStringNamePath
                        extends Node
                        
                        func run():
                            var bad = preload(&"res://icon.svg")
                        """
        );

        for (var analyzed : List.of(variablePath, concatenatedPath, stringNamePath)) {
            var diagnostics = expressionResolutionDiagnostics(analyzed);
            assertEquals(1, diagnostics.size(), () -> "unexpected diagnostics: " + describe(analyzed));
            assertTrue(diagnostics.getFirst().message().contains("preload"), diagnostics.getFirst().message());
            assertTrue(
                    diagnostics.getFirst().message().contains("string literal"),
                    diagnostics.getFirst().message()
            );
        }
    }

    @Test
    void preloadEmptyStringLiteralIsAcceptedAndPassedThrough() throws Exception {
        // The compiler does not judge path validity: an empty string literal is a legal literal
        // and passes through verbatim; ResourceLoader owns the runtime error on failure.
        var analyzed = analyze(
                "preload_empty_path.gd",
                """
                        class_name PreloadEmptyPath
                        extends Node
                        
                        var empty = preload("")
                        """
        );

        assertPropertyType(analyzed, "empty", "Resource");
        assertEquals(
                List.of(),
                errorDiagnostics(analyzed),
                "empty string literal preload must not produce error diagnostics: " + describe(analyzed)
        );
    }

    private static void assertExpressionType(
            @NotNull AnalyzedScript analyzed,
            @NotNull String functionName,
            @NotNull String variableName,
            @NotNull String expectedTypeName
    ) {
        var function = findNode(analyzed.ast(), FunctionDeclaration.class, f -> f.name().equals(functionName));
        var declaration = findNode(function, VariableDeclaration.class, v -> v.name().equals(variableName));
        var initializer = declaration.value();
        assertNotNull(initializer, "variable '" + variableName + "' must have an initializer");
        var type = analyzed.analysisData().expressionTypes().get(initializer);
        assertNotNull(type, "no published expression type for '" + variableName + "' initializer");
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, type.status());
        assertNotNull(type.publishedType());
        assertEquals(expectedTypeName, type.publishedType().getTypeName());
    }

    /// Class-level property counterpart of [assertExpressionType]: anchors on the top-level
    /// `VariableDeclaration` initializer instead of a function-local one.
    private static void assertPropertyType(
            @NotNull AnalyzedScript analyzed,
            @NotNull String propertyName,
            @NotNull String expectedTypeName
    ) {
        var declaration = findNode(analyzed.ast(), VariableDeclaration.class, v -> v.name().equals(propertyName));
        var initializer = declaration.value();
        assertNotNull(initializer, "property '" + propertyName + "' must have an initializer");
        var type = analyzed.analysisData().expressionTypes().get(initializer);
        assertNotNull(type, "no published expression type for '" + propertyName + "' initializer");
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, type.status());
        assertNotNull(type.publishedType());
        assertEquals(expectedTypeName, type.publishedType().getTypeName());
    }

    private static @NotNull List<FrontendDiagnostic> expressionResolutionDiagnostics(@NotNull AnalyzedScript analyzed) {
        return analyzed.analysisData().diagnostics().asList().stream()
                .filter(diagnostic -> diagnostic.category().equals("sema.expression_resolution"))
                .toList();
    }

    private static @NotNull List<FrontendDiagnostic> errorDiagnostics(@NotNull AnalyzedScript analyzed) {
        return analyzed.analysisData().diagnostics().asList().stream()
                .filter(diagnostic -> diagnostic.severity() == FrontendDiagnosticSeverity.ERROR)
                .toList();
    }

    private static @NotNull String describe(@NotNull AnalyzedScript analyzed) {
        return analyzed.analysisData().diagnostics().asList().stream()
                .map(diagnostic -> diagnostic.category() + ": " + diagnostic.message())
                .toList()
                .toString();
    }

    private static @NotNull AnalyzedScript analyze(
            @NotNull String fileName,
            @NotNull String source
    ) throws Exception {
        var diagnostics = new DiagnosticManager();
        var parserService = new GdScriptParserService();
        var unit = parserService.parseUnit(Path.of("tmp", fileName), source, diagnostics);
        var analysisData = new FrontendSemanticAnalyzer().analyze(
                new FrontendModule("test_module", List.of(unit), Map.of()),
                new ClassRegistry(ExtensionApiLoader.loadDefault()),
                diagnostics
        );
        return new AnalyzedScript(unit.ast(), analysisData);
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
            @NotNull FrontendAnalysisData analysisData
    ) {
    }
}
