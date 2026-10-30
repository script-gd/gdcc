package gd.script.gdcc.frontend.lowering;

import dev.superice.gdparser.frontend.ast.Node;
import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.lowering.pass.FrontendLoweringAnalysisPass;
import gd.script.gdcc.frontend.lowering.pass.FrontendLoweringBodyInsnPass;
import gd.script.gdcc.frontend.lowering.pass.FrontendLoweringBuildCfgPass;
import gd.script.gdcc.frontend.lowering.pass.FrontendLoweringClassSkeletonPass;
import gd.script.gdcc.frontend.lowering.pass.FrontendLoweringFunctionPreparationPass;
import gd.script.gdcc.frontend.lowering.pass.body.FrontendBodyLoweringSession;
import gd.script.gdcc.frontend.parse.FrontendModule;
import gd.script.gdcc.frontend.parse.GdScriptParserService;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.lir.LirBasicBlock;
import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.lir.LirInstruction;
import gd.script.gdcc.lir.insn.BinaryOpInsn;
import gd.script.gdcc.lir.insn.ConstructBuiltinInsn;
import gd.script.gdcc.lir.insn.LiteralBoolInsn;
import gd.script.gdcc.lir.insn.LiteralFloatInsn;
import gd.script.gdcc.lir.insn.LiteralIntInsn;
import gd.script.gdcc.lir.insn.LiteralStringInsn;
import gd.script.gdcc.lir.insn.LoadStaticInsn;
import gd.script.gdcc.lir.insn.ReturnInsn;
import gd.script.gdcc.scope.ClassRegistry;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/// Pipeline-level acceptance tests for evaluated class `const` support: bare and qualified
/// references must fold to literal/component-construction instructions at the use site, and a
/// failed `const` must poison its name with a dependency-invalid diagnostic at the use site
/// instead of falling back to globals or reporting an accessibility failure.
class FrontendConstLoweringTest {
    /// A bare class `const` reference materializes the frozen int at the use site — never a
    /// runtime static load.
    @Test
    void bareConstReferenceLowersToIntLiteral() throws Exception {
        var prepared = prepareContext(
                "body_insn_const_bare.gd",
                """
                        class_name BodyInsnConstBare
                        extends RefCounted
                        
                        const C = 42
                        
                        func get() -> int:
                            return C
                        """,
                Map.of("BodyInsnConstBare", "RuntimeBodyInsnConstBare")
        );
        var getContext = requireContext(prepared, "RuntimeBodyInsnConstBare", "get");

        new FrontendLoweringBodyInsnPass().run(prepared.context());

        var literalInsn = requireOnlyInstruction(getContext.targetFunction(), LiteralIntInsn.class);
        var returnInsn = requireOnlyReturnInsn(getContext.targetFunction());
        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals(42L, literalInsn.value()),
                () -> assertEquals(literalInsn.resultId(), returnInsn.returnValueId()),
                () -> assertTrue(
                        allInstructions(getContext.targetFunction()).stream()
                                .noneMatch(LoadStaticInsn.class::isInstance),
                        "class const must not read runtime static storage"
                )
        );
    }

    /// Scalar frozen values materialize through the matching literal instruction family.
    @Test
    void scalarConstsMaterializeMatchingLiteralInstructions() throws Exception {
        var prepared = prepareContext(
                "body_insn_const_scalars.gd",
                """
                        class_name BodyInsnConstScalars
                        extends RefCounted
                        
                        const F = 1.5
                        const B = true
                        const S = "hi"
                        
                        func get_f() -> float:
                            return F
                        
                        func get_b() -> bool:
                            return B
                        
                        func get_s() -> String:
                            return S
                        """,
                Map.of("BodyInsnConstScalars", "RuntimeBodyInsnConstScalars")
        );

        new FrontendLoweringBodyInsnPass().run(prepared.context());

        var floatInsn = requireOnlyInstruction(
                requireContext(prepared, "RuntimeBodyInsnConstScalars", "get_f").targetFunction(),
                LiteralFloatInsn.class
        );
        var boolInsn = requireOnlyInstruction(
                requireContext(prepared, "RuntimeBodyInsnConstScalars", "get_b").targetFunction(),
                LiteralBoolInsn.class
        );
        var stringInsn = requireOnlyInstruction(
                requireContext(prepared, "RuntimeBodyInsnConstScalars", "get_s").targetFunction(),
                LiteralStringInsn.class
        );
        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals(1.5, floatInsn.value(), 1e-9),
                () -> assertTrue(boolInsn.value()),
                () -> assertEquals("hi", stringInsn.value())
        );
    }

    /// A builtin-typed const materializes as exact-typed leaf literals plus the fixed canonical
    /// `construct_builtin` recipe — no runtime load, no replayed source constructors.
    @Test
    void builtinConstMaterializesComponentConstruction() throws Exception {
        var prepared = prepareContext(
                "body_insn_const_builtin.gd",
                """
                        class_name BodyInsnConstBuiltin
                        extends RefCounted
                        
                        const V = Vector2(1.0, 2.0)
                        
                        func get() -> Vector2:
                            return V
                        """,
                Map.of("BodyInsnConstBuiltin", "RuntimeBodyInsnConstBuiltin")
        );
        var getContext = requireContext(prepared, "RuntimeBodyInsnConstBuiltin", "get");

        new FrontendLoweringBodyInsnPass().run(prepared.context());

        var function = getContext.targetFunction();
        var floatLiterals = allInstructions(function).stream()
                .filter(LiteralFloatInsn.class::isInstance)
                .map(LiteralFloatInsn.class::cast)
                .toList();
        var construct = requireOnlyInstruction(function, ConstructBuiltinInsn.class);
        var returnInsn = requireOnlyReturnInsn(function);
        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals(List.of(1.0, 2.0),
                        floatLiterals.stream().map(LiteralFloatInsn::value).toList()),
                () -> assertEquals(2, construct.args().size()),
                () -> assertEquals(construct.resultId(), returnInsn.returnValueId()),
                () -> assertTrue(
                        allInstructions(function).stream()
                                .noneMatch(LoadStaticInsn.class::isInstance),
                        "builtin const must not read runtime static storage"
                )
        );
    }

    /// `Other.C` lowers through the receiverless `MemberLoadItem` route and folds to the same
    /// literal as the bare reference — the qualified access never becomes a static load.
    @Test
    void qualifiedInnerClassConstFoldsToLiteral() throws Exception {
        var prepared = prepareContext(
                "body_insn_const_qualified.gd",
                """
                        class_name BodyInsnConstQualified
                        extends RefCounted
                        
                        class Other:
                            const C = 7
                        
                        func get() -> int:
                            return Other.C
                        """,
                Map.of("BodyInsnConstQualified", "RuntimeBodyInsnConstQualified")
        );
        var getContext = requireContext(prepared, "RuntimeBodyInsnConstQualified", "get");

        new FrontendLoweringBodyInsnPass().run(prepared.context());

        var literalInsn = requireOnlyInstruction(getContext.targetFunction(), LiteralIntInsn.class);
        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals(7L, literalInsn.value()),
                () -> assertTrue(
                        allInstructions(getContext.targetFunction()).stream()
                                .noneMatch(LoadStaticInsn.class::isInstance),
                        "qualified class const must not read runtime static storage"
                )
        );
    }

    /// An inherited class `const` resolves through the inheritance walk and folds identically.
    @Test
    void inheritedConstReferenceFoldsToLiteral() throws Exception {
        var prepared = prepareContext(
                List.of(
                        new SourceFixture(
                                "body_insn_const_parent.gd",
                                """
                                        class_name ConstParent
                                        extends RefCounted
                                        
                                        const PC = 9
                                        """
                        ),
                        new SourceFixture(
                                "body_insn_const_child.gd",
                                """
                                        class_name BodyInsnConstChild
                                        extends ConstParent
                                        
                                        func get() -> int:
                                            return PC
                                        """
                        )
                ),
                Map.of(
                        "ConstParent", "RuntimeConstParent",
                        "BodyInsnConstChild", "RuntimeBodyInsnConstChild"
                )
        );
        var getContext = requireContext(prepared, "RuntimeBodyInsnConstChild", "get");

        new FrontendLoweringBodyInsnPass().run(prepared.context());

        var literalInsn = requireOnlyInstruction(getContext.targetFunction(), LiteralIntInsn.class);
        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals(9L, literalInsn.value())
        );
    }

    /// A class `const` stays readable inside static methods — the CONSTANT binding carries no
    /// instance dependency, so static restrictions must not block it.
    @Test
    void staticMethodBodyReadsClassConst() throws Exception {
        var prepared = prepareContext(
                "body_insn_const_static.gd",
                """
                        class_name BodyInsnConstStatic
                        extends RefCounted
                        
                        const LIMIT = 64
                        
                        static func limit() -> int:
                            return LIMIT
                        """,
                Map.of("BodyInsnConstStatic", "RuntimeBodyInsnConstStatic")
        );
        var limitContext = requireContext(prepared, "RuntimeBodyInsnConstStatic", "limit");

        new FrontendLoweringBodyInsnPass().run(prepared.context());

        var literalInsn = requireOnlyInstruction(limitContext.targetFunction(), LiteralIntInsn.class);
        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals(64L, literalInsn.value())
        );
    }

    /// Const references inside larger expressions materialize the frozen value in place; the
    /// surrounding runtime expression is not itself folded (use-site materialization only).
    @Test
    void constReferenceInsideExpressionMaterializesValueOnly() throws Exception {
        var prepared = prepareContext(
                "body_insn_const_expr.gd",
                """
                        class_name BodyInsnConstExpr
                        extends RefCounted
                        
                        const C = 42
                        
                        func get() -> int:
                            return C + 1
                        """,
                Map.of("BodyInsnConstExpr", "RuntimeBodyInsnConstExpr")
        );
        var getContext = requireContext(prepared, "RuntimeBodyInsnConstExpr", "get");

        new FrontendLoweringBodyInsnPass().run(prepared.context());

        var function = getContext.targetFunction();
        var intLiterals = allInstructions(function).stream()
                .filter(LiteralIntInsn.class::isInstance)
                .map(LiteralIntInsn.class::cast)
                .toList();
        var binaryOp = requireOnlyInstruction(function, BinaryOpInsn.class);
        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals(List.of(42L, 1L),
                        intLiterals.stream().map(LiteralIntInsn::value).toList()),
                // 42 materializes for C, then the runtime add consumes both operand slots.
                () -> assertEquals(intLiterals.get(0).resultId(), binaryOp.leftId()),
                () -> assertEquals(intLiterals.get(1).resultId(), binaryOp.rightId())
        );
    }

    /// A failed `const` poisons its name: the use site reports one dependency-invalid
    /// diagnostic — not an accessibility failure, not an unknown identifier, and not a global
    /// fallback hit.
    @Test
    void failedConstUseSiteReportsDependencyInvalid() throws Exception {
        var prepared = prepareAnalysisOnly(
                "body_insn_const_poison.gd",
                """
                        class_name BodyInsnConstPoison
                        extends RefCounted
                        
                        const BAD = MISSING + 1
                        const GLOBAL_SHADOW = 1
                        
                        func get() -> int:
                            return BAD
                        """,
                Map.of("BodyInsnConstPoison", "RuntimeBodyInsnConstPoison")
        );

        var errors = prepared.diagnostics().snapshot().asList().stream()
                .filter(diagnostic -> diagnostic.message() != null)
                .toList();
        assertAll(
                // One declaration diagnostic (eval failure) + one use-site diagnostic (poison).
                () -> assertEquals(2, errors.size()),
                () -> assertTrue(errors.getFirst().message().contains("no supported constant value"),
                        "Expected declaration eval failure but was: " + errors.getFirst().message()),
                () -> assertTrue(errors.get(1).message().contains("depends on an invalid constant"),
                        "Expected dependency-invalid diagnostic but was: " + errors.get(1).message()),
                () -> assertFalse(errors.get(1).message().contains("not accessible"),
                        "poison must not report an accessibility failure"),
                () -> assertFalse(errors.get(1).message().contains("Unable to resolve"),
                        "poisoned names must not fall through to unknown-identifier reporting")
        );
    }

    /// A qualified access on a failed `const` produces the same dependency-invalid diagnostic
    /// through the chain-resolution BLOCKED trace.
    @Test
    void qualifiedFailedConstReportsDependencyInvalid() throws Exception {
        var prepared = prepareAnalysisOnly(
                "body_insn_const_qualified_poison.gd",
                """
                        class_name BodyInsnConstQualifiedPoison
                        extends RefCounted
                        
                        class Other:
                            const BAD = MISSING + 1
                        
                        func get() -> int:
                            return Other.BAD
                        """,
                Map.of("BodyInsnConstQualifiedPoison", "RuntimeBodyInsnConstQualifiedPoison")
        );

        var errors = prepared.diagnostics().snapshot().asList().stream()
                .filter(diagnostic -> diagnostic.message() != null)
                .toList();
        assertAll(
                () -> assertEquals(2, errors.size()),
                () -> assertTrue(errors.getFirst().message().contains("no supported constant value"),
                        "Expected declaration eval failure but was: " + errors.getFirst().message()),
                () -> assertTrue(errors.get(1).message().contains("invalid constant"),
                        "Expected dependency-invalid diagnostic but was: " + errors.get(1).message())
        );
    }

    /// A failed `const` claims its name for calls too: `Child.target()` must not fall through
    /// to the parent's same-named static method — the name is terminally blocked.
    @Test
    void qualifiedCallCannotEscapeFailedConstClaim() throws Exception {
        var prepared = prepareAnalysisOnly(
                List.of(
                        new SourceFixture(
                                "body_insn_const_call_parent.gd",
                                """
                                        class_name ConstCallParent
                                        extends RefCounted
                                        
                                        static func target() -> int:
                                            return 1
                                        """
                        ),
                        new SourceFixture(
                                "body_insn_const_call_child.gd",
                                """
                                        class_name BodyInsnConstCallChild
                                        extends ConstCallParent
                                        
                                        const target = MISSING + 1
                                        
                                        func get() -> int:
                                            return BodyInsnConstCallChild.target()
                                        """
                        )
                ),
                Map.of(
                        "ConstCallParent", "RuntimeConstCallParent",
                        "BodyInsnConstCallChild", "RuntimeBodyInsnConstCallChild"
                )
        );

        var errors = prepared.diagnostics().snapshot().asList().stream()
                .filter(diagnostic -> diagnostic.message() != null)
                .toList();
        assertAll(
                () -> assertEquals(2, errors.size()),
                () -> assertTrue(errors.getFirst().message().contains("no supported constant value"),
                        "Expected declaration eval failure but was: " + errors.getFirst().message()),
                () -> assertTrue(errors.get(1).message().contains("invalid constant"),
                        "Expected dependency-invalid call diagnostic but was: " + errors.get(1).message())
        );
    }

    /// A usable `const` equally owns its name: `Child.helper()` fails as "not a method" instead
    /// of resolving the parent's same-named static function through the constant's shadow.
    @Test
    void qualifiedCallCannotCallUsableConst() throws Exception {
        var prepared = prepareAnalysisOnly(
                List.of(
                        new SourceFixture(
                                "body_insn_const_callable_parent.gd",
                                """
                                        class_name ConstCallableParent
                                        extends RefCounted
                                        
                                        static func helper() -> int:
                                            return 1
                                        """
                        ),
                        new SourceFixture(
                                "body_insn_const_callable_child.gd",
                                """
                                        class_name BodyInsnConstCallableChild
                                        extends ConstCallableParent
                                        
                                        const helper = 5
                                        
                                        func get() -> int:
                                            return BodyInsnConstCallableChild.helper()
                                        """
                        )
                ),
                Map.of(
                        "ConstCallableParent", "RuntimeConstCallableParent",
                        "BodyInsnConstCallableChild", "RuntimeBodyInsnConstCallableChild"
                )
        );

        var errors = prepared.diagnostics().snapshot().asList().stream()
                .filter(diagnostic -> diagnostic.message() != null)
                .toList();
        assertAll(
                // The failed call fans out into the usual call+expression diagnostic pair —
                // the same fan-out every failed static call produces.
                () -> assertEquals(2, errors.size(),
                        () -> errors.stream()
                                .map(d -> d.category() + ": " + d.message())
                                .collect(java.util.stream.Collectors.joining("\n"))),
                () -> assertTrue(errors.stream().allMatch(d -> d.message().contains("not a method")),
                        "Expected not-a-method failures but was: " + errors)
        );
    }

    /// Const initializers may not touch runtime state: a var reference inside an initializer is
    /// a declaration diagnostic, and the initializer's expression never becomes a property
    /// initializer fact.
    @Test
    void constInitializerCannotReferenceRuntimeMembers() throws Exception {
        var prepared = prepareContext(
                "body_insn_const_runtime_ref.gd",
                """
                        class_name BodyInsnConstRuntimeRef
                        extends RefCounted
                        
                        var member: int = 3
                        const USES_VAR = member
                        
                        func get() -> int:
                            return member
                        """,
                Map.of("BodyInsnConstRuntimeRef", "RuntimeBodyInsnConstRuntimeRef")
        );

        var errors = prepared.diagnostics().snapshot().asList().stream()
                .filter(diagnostic -> diagnostic.message() != null)
                .toList();
        assertAll(
                () -> assertEquals(1, errors.size(),
                        () -> errors.stream()
                                .map(d -> d.category() + ": " + d.message())
                                .collect(java.util.stream.Collectors.joining("\n"))),
                () -> assertTrue(errors.getFirst().message().contains("not a compile-time constant")
                                || errors.getFirst().message().contains("no supported constant value"),
                        "Expected non-constant initializer rejection but was: "
                                + errors.getFirst().message()),
                // The var stays a normal property — the failed const never disturbed it.
                () -> assertFalse(prepared.context().requireAnalysisData()
                        .rejectedClassConstants().isEmpty())
        );
    }

    // ------------------------------------------------------------------
    // Harness
    // ------------------------------------------------------------------

    private static @NotNull PreparedContext prepareContext(
            @NotNull String fileName,
            @NotNull String source,
            @NotNull Map<String, String> topLevelCanonicalNameMap
    ) throws Exception {
        return prepareContext(List.of(new SourceFixture(fileName, source)), topLevelCanonicalNameMap, true);
    }

    /// Analysis-stage harness for negative cases: poisoned expressions carry BLOCKED facts that
    /// are deliberately not lowering-ready, so the CFG pass must not run on them at all.
    private static @NotNull PreparedContext prepareAnalysisOnly(
            @NotNull String fileName,
            @NotNull String source,
            @NotNull Map<String, String> topLevelCanonicalNameMap
    ) throws Exception {
        return prepareContext(List.of(new SourceFixture(fileName, source)), topLevelCanonicalNameMap, false);
    }

    private static @NotNull PreparedContext prepareAnalysisOnly(
            @NotNull List<SourceFixture> fixtures,
            @NotNull Map<String, String> topLevelCanonicalNameMap
    ) throws Exception {
        return prepareContext(fixtures, topLevelCanonicalNameMap, false);
    }

    private static @NotNull PreparedContext prepareContext(
            @NotNull List<SourceFixture> fixtures,
            @NotNull Map<String, String> topLevelCanonicalNameMap
    ) throws Exception {
        return prepareContext(fixtures, topLevelCanonicalNameMap, true);
    }

    private static @NotNull PreparedContext prepareContext(
            @NotNull List<SourceFixture> fixtures,
            @NotNull Map<String, String> topLevelCanonicalNameMap,
            boolean buildCfg
    ) throws Exception {
        var diagnostics = new DiagnosticManager();
        var parserService = new GdScriptParserService();
        var parseDiagnostics = new DiagnosticManager();
        var units = fixtures.stream()
                .map(fixture -> parserService.parseUnit(
                        Path.of("tmp", fixture.fileName()),
                        fixture.source(),
                        parseDiagnostics
                ))
                .toList();
        assertTrue(parseDiagnostics.isEmpty(),
                () -> "Unexpected parse diagnostics: " + parseDiagnostics.snapshot());
        var context = new FrontendLoweringContext(
                new FrontendModule("test_module", units, topLevelCanonicalNameMap),
                new ClassRegistry(ExtensionApiLoader.loadDefault()),
                diagnostics
        );
        new FrontendLoweringAnalysisPass().run(context);
        new FrontendLoweringClassSkeletonPass().run(context);
        new FrontendLoweringFunctionPreparationPass().run(context);
        if (buildCfg) {
            new FrontendLoweringBuildCfgPass().run(context);
        }
        return new PreparedContext(context, diagnostics);
    }

    private static @NotNull FunctionLoweringContext requireContext(
            @NotNull PreparedContext prepared,
            @NotNull String owningClassName,
            @NotNull String functionName
    ) {
        return prepared.context().requireFunctionLoweringContexts().stream()
                .filter(context -> context.kind() == FunctionLoweringContext.Kind.EXECUTABLE_BODY)
                .filter(context -> context.owningClass().getName().equals(owningClassName))
                .filter(context -> context.targetFunction().getName().equals(functionName))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Missing executable context " + owningClassName + "." + functionName
                ));
    }

    private static @NotNull List<LirInstruction> allInstructions(@NotNull LirFunctionDef function) {
        var instructions = new ArrayList<LirInstruction>();
        for (LirBasicBlock block : function) {
            instructions.addAll(block.getNonTerminatorInstructions());
            if (block.getTerminator() != null) {
                instructions.add(block.getTerminator());
            }
        }
        return instructions;
    }

    private static <T extends LirInstruction> @NotNull T requireOnlyInstruction(
            @NotNull LirFunctionDef function,
            @NotNull Class<T> instructionType
    ) {
        var matches = allInstructions(function).stream()
                .filter(instructionType::isInstance)
                .map(instructionType::cast)
                .toList();
        assertEquals(1, matches.size(), () -> "Expected exactly one " + instructionType.getSimpleName());
        return matches.getFirst();
    }

    private static @NotNull ReturnInsn requireOnlyReturnInsn(@NotNull LirFunctionDef function) {
        var matches = new ArrayList<ReturnInsn>();
        for (LirBasicBlock block : function) {
            if (block.getTerminator() instanceof ReturnInsn returnInsn) {
                matches.add(returnInsn);
            }
        }
        assertEquals(1, matches.size(), "Expected exactly one ReturnInsn terminator");
        return matches.getFirst();
    }

    private record PreparedContext(
            @NotNull FrontendLoweringContext context,
            @NotNull DiagnosticManager diagnostics
    ) {
    }

    private record SourceFixture(@NotNull String fileName, @NotNull String source) {
    }
}
