package gd.script.gdcc.backend.c.build;

import gd.script.gdcc.backend.CodegenContext;
import gd.script.gdcc.backend.c.gen.CCodegen;
import gd.script.gdcc.enums.GodotVersion;
import gd.script.gdcc.frontend.constant.GdScriptEvaluatedConstant;
import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.diagnostic.FrontendDiagnostic;
import gd.script.gdcc.frontend.lowering.FrontendLoweringPassManager;
import gd.script.gdcc.frontend.parse.FrontendModule;
import gd.script.gdcc.frontend.parse.FrontendSourceUnit;
import gd.script.gdcc.frontend.parse.GdScriptParserService;
import gd.script.gdcc.frontend.scope.ClassScope;
import gd.script.gdcc.frontend.sema.FrontendAnalysisData;
import gd.script.gdcc.frontend.sema.FrontendClassSkeletonBuilder;
import gd.script.gdcc.frontend.sema.FrontendModuleSkeleton;
import gd.script.gdcc.frontend.sema.analyzer.FrontendScopeAnalyzer;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.lir.LirBasicBlock;
import gd.script.gdcc.lir.LirClassDef;
import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.lir.LirInstruction;
import gd.script.gdcc.lir.LirModule;
import gd.script.gdcc.lir.LirPropertyDef;
import gd.script.gdcc.lir.insn.AwaitInsn;
import gd.script.gdcc.lir.insn.BinaryOpInsn;
import gd.script.gdcc.lir.insn.BuiltinCastInsn;
import gd.script.gdcc.lir.insn.CallGlobalInsn;
import gd.script.gdcc.lir.insn.CallIntrinsicInsn;
import gd.script.gdcc.lir.insn.CallMethodInsn;
import gd.script.gdcc.lir.insn.CallStaticMethodInsn;
import gd.script.gdcc.lir.insn.CallSuperMethodInsn;
import gd.script.gdcc.lir.insn.ConstructArrayInsn;
import gd.script.gdcc.lir.insn.ConstructBuiltinInsn;
import gd.script.gdcc.lir.insn.ConstructCallableInsn;
import gd.script.gdcc.lir.insn.ConstructContainerLiteralInsn;
import gd.script.gdcc.lir.insn.ConstructDictionaryInsn;
import gd.script.gdcc.lir.insn.ConstructLambdaInsn;
import gd.script.gdcc.lir.insn.ConstructObjectInsn;
import gd.script.gdcc.lir.insn.ConstructSignalInsn;
import gd.script.gdcc.lir.insn.ConstructStandaloneCallableInsn;
import gd.script.gdcc.lir.insn.GetClassNameInsn;
import gd.script.gdcc.lir.insn.GetVariantTypeInsn;
import gd.script.gdcc.lir.insn.GoIfInsn;
import gd.script.gdcc.lir.insn.IsInstanceOfInsn;
import gd.script.gdcc.lir.insn.LiteralBoolInsn;
import gd.script.gdcc.lir.insn.LiteralFloatInsn;
import gd.script.gdcc.lir.insn.LiteralIntInsn;
import gd.script.gdcc.lir.insn.LiteralStringInsn;
import gd.script.gdcc.lir.insn.LoadPropertyInsn;
import gd.script.gdcc.lir.insn.LoadStaticInsn;
import gd.script.gdcc.lir.insn.ObjectCastInsn;
import gd.script.gdcc.lir.insn.ReturnInsn;
import gd.script.gdcc.lir.insn.StorePropertyInsn;
import gd.script.gdcc.lir.insn.StoreStaticInsn;
import gd.script.gdcc.lir.insn.UnaryOpInsn;
import gd.script.gdcc.lir.insn.VariantGetIndexedInsn;
import gd.script.gdcc.lir.insn.VariantGetInsn;
import gd.script.gdcc.lir.insn.VariantGetKeyedInsn;
import gd.script.gdcc.lir.insn.VariantGetNamedInsn;
import gd.script.gdcc.lir.insn.VariantSetIndexedInsn;
import gd.script.gdcc.lir.insn.VariantSetInsn;
import gd.script.gdcc.lir.insn.VariantSetKeyedInsn;
import gd.script.gdcc.lir.insn.VariantSetNamedInsn;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.scope.GdScriptClassConstant;
import gd.script.gdcc.scope.ResolveRestriction;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Targeted AOT smoke for `const` support: runs a multi-class module through the real
/// frontend → lowering → C codegen → zig → Godot chain inside an isolated `tmp/` project,
/// and re-anchors the compile-level rules that cannot live in the positive-only test suite:
///
/// - Lowered-region purity: const use sites emit only the frozen payload (literal chains and
///   `construct_builtin` over variable operands) plus ordinary boundary instructions; source
///   operators, member reads, static/property storage, and call replay never reappear.
/// - No runtime state: a `const` declaration produces no LIR property, no synthesized
///   field-init/static-init helper, no ClassDB registration, and no mutable local slot.
/// - Class-initializer dependency rules (frozen earlier-only semantics): forward, self,
///   inherited, and cross-class references are rejected at skeleton time with masked names.
/// - Failed-const name mask: a rejected class const terminally blocks its own name on every
///   lookup layer instead of falling back to ancestors or globals.
///
/// Godot-facing checks only read values through `target.call`/`ClassDB.class_call_static`;
/// nothing reaches for an AOT "constant field", which does not exist by design.
public class GdccConstRuntimeSmokeTest {
    private static final String PASS_MARKER = "GDCC_CONST_SMOKE_PASS";
    private static final String FAIL_MARKER = "GDCC_CONST_SMOKE_FAIL";

    /// Instructions that would replay source-level semantics instead of materializing a frozen
    /// payload. Their absence — not an allowlist — is asserted so new boundary instructions
    /// (packing, ownership, lifecycle) cannot silently turn a const read into a runtime replay.
    private static final Set<Class<? extends LirInstruction>> REPLAY_INSTRUCTIONS = Set.of(
            UnaryOpInsn.class,
            BinaryOpInsn.class,
            CallGlobalInsn.class,
            CallIntrinsicInsn.class,
            CallMethodInsn.class,
            CallStaticMethodInsn.class,
            CallSuperMethodInsn.class,
            LoadStaticInsn.class,
            StoreStaticInsn.class,
            LoadPropertyInsn.class,
            StorePropertyInsn.class,
            VariantGetInsn.class,
            VariantGetIndexedInsn.class,
            VariantGetKeyedInsn.class,
            VariantGetNamedInsn.class,
            VariantSetInsn.class,
            VariantSetIndexedInsn.class,
            VariantSetKeyedInsn.class,
            VariantSetNamedInsn.class,
            ConstructObjectInsn.class,
            ConstructLambdaInsn.class,
            ConstructCallableInsn.class,
            ConstructStandaloneCallableInsn.class,
            ConstructArrayInsn.class,
            ConstructDictionaryInsn.class,
            ConstructContainerLiteralInsn.class,
            ConstructSignalInsn.class,
            IsInstanceOfInsn.class,
            GetVariantTypeInsn.class,
            GetClassNameInsn.class,
            ObjectCastInsn.class,
            BuiltinCastInsn.class,
            AwaitInsn.class,
            GoIfInsn.class
    );

    // ----------------------------------------------------------------------
    // Runtime end-to-end: compile → native build → isolated Godot run
    // ----------------------------------------------------------------------

    @Test
    void constsMaterializeAndRunInIsolatedGodotProject() throws Exception {
        if (ZigUtil.findZig() == null || GodotGdextensionTestRunner.findGodotBinaryFromEnv() == null) {
            Assumptions.abort("Zig or GODOT_BIN not found; skipping const runtime smoke test");
            return;
        }

        var tempParent = Path.of("tmp/test");
        Files.createDirectories(tempParent);
        var tempDir = Files.createTempDirectory(tempParent, "const_runtime_");
        var projectDir = tempDir.resolve("project");
        Files.createDirectories(projectDir);
        var runtimeDir = Files.createTempDirectory(tempParent, "const_runtime_project_");
        Files.copy(Path.of("test_project/project.godot"), runtimeDir.resolve("project.godot"));
        Files.copy(Path.of("test_project/root.gd"), runtimeDir.resolve("root.gd"));

        var module = parseModule(
                "const_runtime_module",
                List.of(
                        new SourceSpec(tempDir.resolve("const_helper.gd"), constHelperSource()),
                        new SourceSpec(tempDir.resolve("const_base.gd"), constBaseSource()),
                        new SourceSpec(tempDir.resolve("const_child.gd"), constChildSource()),
                        new SourceSpec(tempDir.resolve("const_main.gd"), constMainSource())
                ),
                Map.of(
                        "ConstSmokeHelper", "RuntimeConstSmokeHelper",
                        "ConstSmokeBase", "RuntimeConstSmokeBase",
                        "ConstSmokeChild", "RuntimeConstSmokeChild",
                        "ConstSmokeMain", "RuntimeConstSmokeMain"
                )
        );
        var diagnostics = new DiagnosticManager();
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadVersion(GodotVersion.V451));
        var lowered = new FrontendLoweringPassManager().lower(module, classRegistry, diagnostics);
        assertNotNull(lowered, () -> "Lowering returned null with diagnostics: " + diagnostics.snapshot());
        assertFalse(diagnostics.hasErrors(), () -> "Unexpected frontend diagnostics: " + diagnostics.snapshot());

        // The class table may keep frozen payloads for use sites, but no runtime state may
        // materialize: no property rows, no synthesized init helpers for consts.
        assertNoConstRuntimeState(
                requireClass(lowered, "RuntimeConstSmokeMain"),
                List.of("LIMIT", "DERIVED", "START", "GREETING", "UP_Y", "B", "T", "VI4")
        );
        assertNoConstRuntimeState(
                requireClass(lowered, "RuntimeConstSmokeHelper"),
                List.of("HC", "HV")
        );

        var projectInfo = new CProjectInfo(
                "const_runtime_smoke",
                GodotVersion.V451,
                projectDir,
                COptimizationLevel.DEBUG,
                TargetPlatform.getNativePlatform()
        );
        var codegen = new CCodegen();
        codegen.prepare(new CodegenContext(projectInfo, classRegistry), lowered);

        var buildResult = new CProjectBuilder().buildProject(projectInfo, codegen);
        assertTrue(buildResult.success(), () -> "Native build should succeed. Build log:\n" + buildResult.buildLog());

        GodotGdextensionTestRunner.GodotRunResult runResult;
        try (var runner = new GodotGdextensionTestRunner(runtimeDir)) {
            runner.prepareProject(new GodotGdextensionTestRunner.ProjectSetup(
                    buildResult.artifacts(),
                    List.of(
                            new GodotGdextensionTestRunner.SceneNodeSpec(
                                    "ConstTarget", "RuntimeConstSmokeMain", ".", Map.of()),
                            new GodotGdextensionTestRunner.SceneNodeSpec(
                                    "ConstChildNode", "RuntimeConstSmokeChild", ".", Map.of())
                    ),
                    new GodotGdextensionTestRunner.TestScriptSpec(constValidationScript())
            ));
            runResult = runner.run(true);
        }
        var combinedOutput = runResult.combinedOutput();
        var godotLog = tempDir.resolve("godot.log");
        Files.writeString(godotLog, combinedOutput);
        IO.println("Godot combined output: " + godotLog.toAbsolutePath());

        assertTrue(
                runResult.stopSignalSeen(),
                () -> "Godot run should emit \"" + GodotGdextensionTestRunner.TEST_STOP_SIGNAL + "\".\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains(PASS_MARKER),
                () -> "Godot output should confirm all const checks.\nOutput:\n" + combinedOutput
        );
        assertFalse(
                combinedOutput.contains(FAIL_MARKER),
                () -> "Godot output should not report a const failure.\nOutput:\n" + combinedOutput
        );
        assertFalse(
                combinedOutput.contains("SCRIPT ERROR"),
                () -> "Godot output should not contain script errors.\nOutput:\n" + combinedOutput
        );
        assertFalse(
                combinedOutput.contains("ERROR"),
                () -> "Godot output should not contain errors.\nOutput:\n" + combinedOutput
        );
    }

    // ----------------------------------------------------------------------
    // Lowered-region purity: payload materialization only, no AST replay
    // ----------------------------------------------------------------------

    @Test
    void constReadsLowerToPayloadMaterializationOnly() throws Exception {
        var lowered = lowerModule(
                List.of(new SourceSpec(Path.of("tmp/const_purity.gd"), constPuritySource())),
                Map.of("ConstPurity", "RuntimeConstPurity")
        );
        var classDef = requireClass(lowered, "RuntimeConstPurity");

        // Every function whose body only returns/read a const must stay replay-free.
        for (var functionName : List.of(
                "pure_int", "pure_float", "pure_bool", "pure_string",
                "pure_vec", "pure_transform", "local_const_only")) {
            assertNoReplayInstructions(requireFunction(classDef, functionName));
        }

        var pureInt = requireFunction(classDef, "pure_int");
        var intLiteral = requireSingleInstruction(pureInt, LiteralIntInsn.class);
        var intReturn = requireSingleReturn(pureInt);
        assertAll(
                () -> assertEquals(42L, intLiteral.value()),
                () -> assertEquals(intLiteral.resultId(), intReturn.returnValueId())
        );

        var pureFloat = requireFunction(classDef, "pure_float");
        assertEquals(1.5, requireSingleInstruction(pureFloat, LiteralFloatInsn.class).value(), 1e-9);

        var pureBool = requireFunction(classDef, "pure_bool");
        assertTrue(requireSingleInstruction(pureBool, LiteralBoolInsn.class).value());

        var pureString = requireFunction(classDef, "pure_string");
        assertEquals("pure", requireSingleInstruction(pureString, LiteralStringInsn.class).value());

        // Geometry payloads flatten to exact-typed leaf literals; every `construct_builtin`
        // operand must be a variable — never a source AST fragment or immediate argument.
        for (var functionName : List.of("pure_vec", "pure_transform")) {
            var function = requireFunction(classDef, functionName);
            var constructs = instructionsOfType(function, ConstructBuiltinInsn.class);
            assertAll(
                    () -> assertFalse(constructs.isEmpty(), "expected construct_builtin in " + functionName),
                    () -> assertTrue(
                            constructs.stream()
                                    .flatMap(construct -> construct.args().stream())
                                    .allMatch(LirInstruction.VariableOperand.class::isInstance),
                            "construct_builtin operands must be variable operands in " + functionName
                    ),
                    () -> assertFalse(
                            instructionsOfType(function, LiteralFloatInsn.class).isEmpty(),
                            "expected leaf literal_float materialization in " + functionName
                    )
            );
        }

        // A local const allocates no mutable slot: its name never enters the variable table
        // and nothing assigns into such a slot.
        var localConstOnly = requireFunction(classDef, "local_const_only");
        var localLiteral = requireSingleInstruction(localConstOnly, LiteralIntInsn.class);
        assertAll(
                () -> assertFalse(
                        localConstOnly.getVariables().containsKey("K"),
                        "local const must not allocate a named local slot"
                ),
                () -> assertEquals(40L, localLiteral.value())
        );
    }

    @Test
    void constDeclarationsLeaveNoRuntimeState() throws Exception {
        var lowered = lowerModule(
                List.of(new SourceSpec(Path.of("tmp/const_state.gd"), constPuritySource())),
                Map.of("ConstPurity", "RuntimeConstPurity")
        );
        var classDef = requireClass(lowered, "RuntimeConstPurity");
        var functionNames = classDef.getFunctions().stream().map(LirFunctionDef::getName).toList();
        var constantNames = List.of("CI", "CF", "CB", "CS", "CV", "CT");

        assertAll(
                // Compile-time table keeps the frozen payloads; nothing else may reference them.
                () -> assertEquals(constantNames.size(), classDef.getScriptConstants().size()),
                () -> assertTrue(
                        classDef.getScriptConstants().stream()
                                .map(GdScriptClassConstant::declaration)
                                .allMatch(GdScriptEvaluatedConstant.class::isInstance),
                        "class consts must be evaluated-constant declarations"
                ),
                () -> assertTrue(
                        classDef.getProperties().isEmpty(),
                        "const declarations must not produce LIR properties: "
                                + classDef.getProperties().stream().map(LirPropertyDef::getName).toList()
                ),
                // No synthesized helpers may carry const names or init channels.
                () -> assertTrue(
                        Collections.disjoint(functionNames, constantNames),
                        "const names must not appear as synthesized functions: " + functionNames
                ),
                () -> assertTrue(
                        functionNames.stream().noneMatch(name -> name.startsWith("_field_init_")),
                        "const declarations must not create property-initializer helpers: " + functionNames
                )
        );
    }

    // ----------------------------------------------------------------------
    // Compile-level regression: frozen class-initializer dependency rules
    // ----------------------------------------------------------------------

    @Test
    void classConstInitializerRejectsFrozenDependencyMatrix() throws IOException {
        // Forward reference: only earlier same-class constants are visible.
        var forward = buildSkeleton(List.of(new SourceSpec("const_fwd.gd", """
                class_name ConstFwd
                extends RefCounted

                const B = A + 1
                const A = 5
                """)));
        var forwardErrors = skeletonErrors(forward);
        assertAll(
                () -> assertEquals(1, forwardErrors.size()),
                () -> assertTrue(forwardErrors.getFirst().message().contains("declared later"),
                        "Expected forward-reference rejection but was: " + forwardErrors.getFirst().message()),
                () -> assertNotNull(findConstant(forward, "ConstFwd", "A"),
                        "the later declaration itself still evaluates"),
                () -> assertNull(findConstant(forward, "ConstFwd", "B")),
                () -> assertTrue(forward.analysisData().rejectedClassConstants()
                        .get("ConstFwd").containsKey("B"))
        );

        // Self reference is diagnosed on its own wording, not as a forward reference.
        var selfRef = buildSkeleton(List.of(new SourceSpec("const_self.gd", """
                class_name ConstSelf
                extends RefCounted

                const X = X + 1
                """)));
        var selfErrors = skeletonErrors(selfRef);
        assertAll(
                () -> assertEquals(1, selfErrors.size()),
                () -> assertTrue(selfErrors.getFirst().message().contains("own declaration"),
                        "Expected self-reference rejection but was: " + selfErrors.getFirst().message())
        );

        // A failed earlier const poisons dependents without re-reporting the root cause twice.
        var dependent = buildSkeleton(List.of(new SourceSpec("const_dep.gd", """
                class_name ConstDep
                extends RefCounted

                const BAD = MISSING + 1
                const DEP = BAD + 1
                const GOOD = 7
                """)));
        var dependentErrors = skeletonErrors(dependent);
        assertAll(
                () -> assertEquals(2, dependentErrors.size()),
                () -> assertTrue(dependentErrors.get(1).message().contains("initializer failed to evaluate"),
                        "Expected dependency-invalid rejection but was: " + dependentErrors.get(1).message()),
                () -> assertNotNull(findConstant(dependent, "ConstDep", "GOOD"),
                        "a failed const must not take legal siblings down"),
                () -> assertEquals(2, dependent.analysisData().rejectedClassConstants()
                        .get("ConstDep").size())
        );

        // Inherited constants cannot feed an initializer even though ordinary reads may.
        var inherited = buildSkeleton(List.of(
                new SourceSpec("const_matrix_base.gd", """
                        class_name ConstMatrixBase
                        extends RefCounted

                        const PC = 9
                        """),
                new SourceSpec("const_matrix_child.gd", """
                        class_name ConstMatrixChild
                        extends ConstMatrixBase

                        const FROM_BASE = PC
                        """)
        ));
        var inheritedErrors = skeletonErrors(inherited);
        assertAll(
                () -> assertEquals(1, inheritedErrors.size()),
                () -> assertTrue(inheritedErrors.getFirst().message().contains("inherited member"),
                        "Expected inherited-member rejection but was: " + inheritedErrors.getFirst().message())
        );

        // Qualified access into another class is a normal-read feature only; inside an
        // initializer the class name is a type-meta namespace, not a constant value. Both
        // shapes are anchored: an inner class reports as a declared member, a same-module
        // top-level sibling reports as a script class — neither yields a constant.
        var crossInner = buildSkeleton(List.of(new SourceSpec("const_cross_inner.gd", """
                class_name ConstCrossInner
                extends RefCounted

                class Helper:
                    const V = 6

                const X = Helper.V
                """)));
        var crossInnerErrors = skeletonErrors(crossInner);
        assertAll(
                () -> assertEquals(1, crossInnerErrors.size()),
                () -> assertTrue(crossInnerErrors.getFirst().message().contains("declared class member"),
                        "Expected inner-class rejection but was: " + crossInnerErrors.getFirst().message())
        );

        var crossTop = buildSkeleton(List.of(
                new SourceSpec("const_cross_top_other.gd", """
                        class_name ConstCrossOther
                        extends RefCounted

                        const C = 1
                        """),
                new SourceSpec("const_cross_top.gd", """
                        class_name ConstCrossTop
                        extends RefCounted

                        const X = ConstCrossOther.C
                        """)
        ));
        var crossTopErrors = skeletonErrors(crossTop);
        assertAll(
                () -> assertEquals(1, crossTopErrors.size()),
                () -> assertTrue(crossTopErrors.getFirst().message().contains("script class"),
                        "Expected cross-class rejection but was: " + crossTopErrors.getFirst().message())
        );
    }

    @Test
    void failedConstNameMaskBlocksAncestorAndGlobalFallback() throws IOException {
        // Child's failed `C` must hide the parent's usable `C` on bare and inherited lookup;
        // the same mechanism keeps a failed `PI` from leaking the global language constant.
        var fixture = analyzeScopes(List.of(
                new SourceSpec("const_mask_parent.gd", """
                        class_name ConstMaskParent
                        extends RefCounted

                        const C = 7
                        """),
                new SourceSpec("const_mask_child.gd", """
                        class_name ConstMaskChild
                        extends ConstMaskParent

                        const C = MISSING
                        const PI = MISSING
                        """)
        ));

        var childScope = assertInstanceOf(
                ClassScope.class,
                fixture.analysisData().scopesByAst().get(fixture.units().get(1).ast())
        );
        assertAll(
                () -> assertTrue(
                        childScope.resolveValue("C", ResolveRestriction.unrestricted()).isBlocked(),
                        "failed const must terminally block the inherited usable name"
                ),
                () -> assertTrue(
                        childScope.resolveValue("PI", ResolveRestriction.unrestricted()).isBlocked(),
                        "failed const must not fall back to the global constant"
                )
        );
    }

    // ----------------------------------------------------------------------
    // Sources
    // ----------------------------------------------------------------------

    /// Companion class read through ordinary qualified access — allowed in bodies.
    private static @NotNull String constHelperSource() {
        return """
                class_name ConstSmokeHelper
                extends Node

                const HC = 11
                const HV = Vector3(1.0, 2.0, 3.0)
                """;
    }

    private static @NotNull String constBaseSource() {
        return """
                class_name ConstSmokeBase
                extends Node

                const BC = 5
                """;
    }

    /// Bare and qualified reads of an inherited class const from a function body.
    private static @NotNull String constChildSource() {
        return """
                class_name ConstSmokeChild
                extends ConstSmokeBase

                func inherited_const() -> int:
                    return BC

                func qualified_self_const() -> int:
                    return ConstSmokeChild.BC

                func qualified_base_const() -> int:
                    return ConstSmokeBase.BC
                """;
    }

    /// Covers the supported const surface end-to-end: scalar payloads and source-order
    /// derivations, enum-member and builtin-constant inputs, geometry payloads, inner-class
    /// qualified reads (body and local-const initializer), match keys, parameter defaults,
    /// lambda reads without capture, and static-method reads.
    private static @NotNull String constMainSource() {
        return """
                class_name ConstSmokeMain
                extends Node

                enum State { IDLE, RUN = 4, JUMP }

                const LIMIT = 20
                const DERIVED = LIMIT * 2 + 1
                const START = State.RUN * 2
                const GREETING = "gd" + "cc"
                const UP_Y = Vector3.UP.y
                const B = Basis(Vector3(1, 2, 3), Vector3(4, 5, 6), Vector3(7, 8, 10))
                const T = Transform3D(B, Vector3(9, 8, 7))
                const VI4 = Vector4i(1, -2, 3, -4)

                class Helper:
                    const V = 6

                func scalar_sum() -> int:
                    return LIMIT + DERIVED + START

                func greet() -> String:
                    return GREETING + "!"

                func up_y() -> float:
                    return UP_Y

                func get_transform() -> Transform3D:
                    return T

                func get_vi4() -> Vector4i:
                    return VI4

                func inner_qualified() -> int:
                    return Helper.V

                func local_qualified_consts() -> int:
                    const X = ConstSmokeHelper.HC
                    const Y = Helper.V
                    return X + Y

                func cross_class() -> int:
                    return ConstSmokeHelper.HC

                func inherited_qualified() -> int:
                    return ConstSmokeChild.BC

                func match_key(v: int) -> int:
                    match v:
                        START:
                            return 1
                        _:
                            return -1

                func with_default(a: int = START) -> int:
                    return a

                func lambda_reader() -> int:
                    const K = 40
                    var add = func() -> int:
                        return K + 2
                    return add.call()

                static func static_limit() -> int:
                    return LIMIT + 1
                """;
    }

    /// Godot-side checks: values only ever leave the compiled classes through method calls —
    /// no AOT "const field" is touched, and the property list must stay const-free. The
    /// non-symmetric Transform3D data would expose any logical-column/physical-row mix-up.
    private static @NotNull String constValidationScript() {
        return """
                extends Node

                func _ready() -> void:
                    var target = get_parent().get_node_or_null("ConstTarget")
                    var child = get_parent().get_node_or_null("ConstChildNode")
                    if target == null or child == null:
                        push_error("GDCC_CONST_SMOKE_FAIL target nodes missing")
                        return

                    var ok := true
                    if int(target.call("scalar_sum")) != 69:
                        push_error("scalar_sum failed")
                        ok = false
                    if str(target.call("greet")) != "gdcc!":
                        push_error("greet failed")
                        ok = false
                    var up_y := float(target.call("up_y"))
                    if not is_finite(up_y) or not is_equal_approx(up_y, 1.0):
                        push_error("up_y failed")
                        ok = false
                    if int(target.call("inner_qualified")) != 6:
                        push_error("inner_qualified failed")
                        ok = false
                    if int(target.call("local_qualified_consts")) != 17:
                        push_error("local_qualified_consts failed")
                        ok = false
                    if int(target.call("cross_class")) != 11:
                        push_error("cross_class failed")
                        ok = false
                    if int(target.call("inherited_qualified")) != 5:
                        push_error("inherited_qualified failed")
                        ok = false
                    if int(target.call("match_key", 8)) != 1:
                        push_error("match_key hit failed")
                        ok = false
                    if int(target.call("match_key", 1)) != -1:
                        push_error("match_key miss failed")
                        ok = false
                    if int(target.call("with_default")) != 8:
                        push_error("with_default omitted failed")
                        ok = false
                    if int(target.call("with_default", 3)) != 3:
                        push_error("with_default explicit failed")
                        ok = false
                    if int(target.call("lambda_reader")) != 42:
                        push_error("lambda_reader failed")
                        ok = false
                    if target.call("get_vi4") != Vector4i(1, -2, 3, -4):
                        push_error("get_vi4 failed")
                        ok = false

                    var t: Transform3D = target.call("get_transform")
                    if t.basis.x != Vector3(1, 2, 3):
                        push_error("transform basis.x mismatch: %s" % t.basis.x)
                        ok = false
                    if t.basis.y != Vector3(4, 5, 6):
                        push_error("transform basis.y mismatch: %s" % t.basis.y)
                        ok = false
                    if t.basis.z != Vector3(7, 8, 10):
                        push_error("transform basis.z mismatch: %s" % t.basis.z)
                        ok = false
                    if t.origin != Vector3(9, 8, 7):
                        push_error("transform origin mismatch: %s" % t.origin)
                        ok = false

                    if int(child.call("inherited_const")) != 5:
                        push_error("inherited_const failed")
                        ok = false
                    if int(child.call("qualified_self_const")) != 5:
                        push_error("qualified_self_const failed")
                        ok = false
                    if int(child.call("qualified_base_const")) != 5:
                        push_error("qualified_base_const failed")
                        ok = false
                    if int(ClassDB.class_call_static("RuntimeConstSmokeMain", "static_limit")) != 21:
                        push_error("static_limit failed")
                        ok = false

                    for property in target.get_property_list():
                        if String(property.name) in ["LIMIT", "DERIVED", "START", "GREETING", "UP_Y", "B", "T", "VI4"]:
                            push_error("const leaked into property list: %s" % property.name)
                            ok = false
                    for property in child.get_property_list():
                        if String(property.name) == "BC":
                            push_error("inherited const leaked into property list")
                            ok = false

                    # Compile-time consts register nothing on the engine: property lists only
                    # cover properties, so integer-constant registration needs its own probe.
                    for cls in ["RuntimeConstSmokeMain", "RuntimeConstSmokeHelper", "RuntimeConstSmokeBase", "RuntimeConstSmokeChild"]:
                        var registered := ClassDB.class_get_integer_constant_list(cls, true)
                        for const_name in ["LIMIT", "DERIVED", "START", "GREETING", "UP_Y", "B", "T", "VI4", "HC", "HV", "BC"]:
                            if const_name in registered:
                                push_error("const leaked into ClassDB constants: %s.%s" % [cls, const_name])
                                ok = false

                    if ok:
                        print("GDCC_CONST_SMOKE_PASS")
                    else:
                        push_error("GDCC_CONST_SMOKE_FAIL one or more checks failed")
                """;
    }

    /// One const per supported flavor so the purity assertions see every materialization
    /// family: scalar literals, a builtin component chain, and a nested geometry recipe.
    /// Initializers deliberately use operator/conditional shapes — replaying them would emit
    /// forbidden instructions, so only genuine folding keeps these functions clean.
    private static @NotNull String constPuritySource() {
        return """
                class_name ConstPurity
                extends RefCounted

                const CI = 6 * 7
                const CF = 0.75 * 2.0
                const CB = true if true else false
                const CS = "pu" + "re"
                const CV = Vector2(1.5, 2.0) * 2.0
                const CT = Transform3D(
                        Basis(Vector3(1, 2, 3), Vector3(4, 5, 6), Vector3(7, 8, 10)),
                        Vector3(9, 8, 7))

                func pure_int() -> int:
                    return CI

                func pure_float() -> float:
                    return CF

                func pure_bool() -> bool:
                    return CB

                func pure_string() -> String:
                    return CS

                func pure_vec() -> Vector2:
                    return CV

                func pure_transform() -> Transform3D:
                    return CT

                func local_const_only() -> int:
                    const K = 6 * 7 - 2
                    return K
                """;
    }

    // ----------------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------------

    /// Parse → full frontend analyze → lowering; returns the emitted module.
    private static @NotNull LirModule lowerModule(
            @NotNull List<SourceSpec> sources,
            @NotNull Map<String, String> topLevelCanonicalNameMap
    ) throws IOException {
        var module = parseModule("const_smoke_module", sources, topLevelCanonicalNameMap);
        var diagnostics = new DiagnosticManager();
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadVersion(GodotVersion.V451));
        var lowered = new FrontendLoweringPassManager().lower(module, classRegistry, diagnostics);
        assertNotNull(lowered, () -> "Lowering returned null with diagnostics: " + diagnostics.snapshot());
        assertFalse(diagnostics.hasErrors(), () -> "Unexpected frontend diagnostics: " + diagnostics.snapshot());
        return lowered;
    }

    private static @NotNull FrontendModule parseModule(
            @NotNull String moduleName,
            @NotNull List<SourceSpec> sources,
            @NotNull Map<String, String> topLevelCanonicalNameMap
    ) {
        var parser = new GdScriptParserService();
        var parseDiagnostics = new DiagnosticManager();
        var units = sources.stream()
                .map(spec -> parser.parseUnit(spec.sourcePath(), spec.source(), parseDiagnostics))
                .toList();
        assertTrue(parseDiagnostics.isEmpty(), () -> "Unexpected parse diagnostics: " + parseDiagnostics.snapshot());
        return new FrontendModule(moduleName, units, topLevelCanonicalNameMap);
    }

    /// Skeleton-only fixture: class const evaluation and the rejected-name mask are owned by
    /// the joint const/enum pre-pass, so initializer dependency rules are asserted here.
    private static @NotNull SkeletonFixture buildSkeleton(@NotNull List<SourceSpec> sources) throws IOException {
        var parserService = new GdScriptParserService();
        var diagnostics = new DiagnosticManager();
        var analysisData = FrontendAnalysisData.bootstrap();
        var units = sources.stream()
                .map(spec -> parserService.parseUnit(spec.sourcePath(), spec.source(), diagnostics))
                .toList();
        var result = new FrontendClassSkeletonBuilder().build(
                new FrontendModule("const_smoke_module", units),
                new ClassRegistry(ExtensionApiLoader.loadVersion(GodotVersion.V451)),
                diagnostics,
                analysisData
        );
        return new SkeletonFixture(result, analysisData, diagnostics, units);
    }

    /// Skeleton + scope publication so scope-level mask probes (`resolveValue`) are assertable.
    private static @NotNull ScopeFixture analyzeScopes(@NotNull List<SourceSpec> sources) throws IOException {
        var parserService = new GdScriptParserService();
        var registry = new ClassRegistry(ExtensionApiLoader.loadVersion(GodotVersion.V451));
        var diagnostics = new DiagnosticManager();
        var analysisData = FrontendAnalysisData.bootstrap();
        var units = sources.stream()
                .map(spec -> parserService.parseUnit(spec.sourcePath(), spec.source(), diagnostics))
                .toList();
        var result = new FrontendClassSkeletonBuilder().build(
                new FrontendModule("const_smoke_module", units),
                registry,
                diagnostics,
                analysisData
        );
        analysisData.updateModuleSkeleton(result);
        analysisData.updateDiagnostics(diagnostics.snapshot());
        new FrontendScopeAnalyzer().analyze(registry, analysisData, diagnostics);
        return new ScopeFixture(analysisData, diagnostics, units);
    }

    private static @NotNull List<FrontendDiagnostic> skeletonErrors(@NotNull SkeletonFixture fixture) {
        return fixture.diagnostics().snapshot().asList().stream()
                .filter(diagnostic -> diagnostic.category().equals("sema.class_skeleton"))
                .toList();
    }

    private static @NotNull LirClassDef requireClass(@NotNull LirModule module, @NotNull String className) {
        return module.getClassDefs().stream()
                .filter(classDef -> classDef.getName().equals(className))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Class not found: " + className));
    }

    private static @NotNull LirFunctionDef requireFunction(@NotNull LirClassDef classDef, @NotNull String name) {
        return classDef.getFunctions().stream()
                .filter(function -> function.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Function not found: " + classDef.getName() + "." + name));
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

    private static <T extends LirInstruction> @NotNull List<T> instructionsOfType(
            @NotNull LirFunctionDef function,
            @NotNull Class<T> instructionType
    ) {
        return allInstructions(function).stream()
                .filter(instructionType::isInstance)
                .map(instructionType::cast)
                .toList();
    }

    private static <T extends LirInstruction> @NotNull T requireSingleInstruction(
            @NotNull LirFunctionDef function,
            @NotNull Class<T> instructionType
    ) {
        var matches = instructionsOfType(function, instructionType);
        assertEquals(1, matches.size(), () -> "Expected exactly one " + instructionType.getSimpleName()
                + " in " + function.getName());
        return matches.getFirst();
    }

    private static @NotNull ReturnInsn requireSingleReturn(@NotNull LirFunctionDef function) {
        var matches = new ArrayList<ReturnInsn>();
        for (LirBasicBlock block : function) {
            if (block.getTerminator() instanceof ReturnInsn returnInsn) {
                matches.add(returnInsn);
            }
        }
        assertEquals(1, matches.size(), () -> "Expected exactly one ReturnInsn in " + function.getName());
        return matches.getFirst();
    }

    /// No source-semantics instruction may appear inside a function that only reads constants.
    private static void assertNoReplayInstructions(@NotNull LirFunctionDef function) {
        var replayed = allInstructions(function).stream()
                .filter(instruction -> REPLAY_INSTRUCTIONS.contains(instruction.getClass()))
                .map(instruction -> instruction.getClass().getSimpleName())
                .toList();
        assertTrue(
                replayed.isEmpty(),
                () -> "const reads must not replay source operators/calls in "
                        + function.getName() + ": " + new LinkedHashSet<>(replayed)
        );
    }

    /// A `const` is a compile-time fact: no property row, and no init helper may be
    /// synthesized for it or carry its name.
    private static void assertNoConstRuntimeState(@NotNull LirClassDef classDef, @NotNull List<String> constNames) {
        var propertyNames = classDef.getProperties().stream().map(LirPropertyDef::getName).toList();
        var functionNames = classDef.getFunctions().stream().map(LirFunctionDef::getName).toList();
        assertAll(
                () -> assertTrue(
                        Collections.disjoint(propertyNames, constNames),
                        "consts must not become LIR properties in " + classDef.getName() + ": " + propertyNames
                ),
                () -> assertTrue(
                        Collections.disjoint(functionNames, constNames),
                        "consts must not synthesize runtime functions in " + classDef.getName() + ": " + functionNames
                ),
                () -> assertTrue(
                        functionNames.stream().noneMatch(name -> name.startsWith("_field_init_")),
                        "consts must not create property-initializer helpers in " + classDef.getName() + ": " + functionNames
                )
        );
    }

    private static Object findConstant(
            @NotNull SkeletonFixture fixture,
            @NotNull String className,
            @NotNull String constantName
    ) {
        return fixture.result().sourceClassRelations().stream()
                .flatMap(relation -> relation.allClassDefs().stream())
                .filter(classDef -> classDef.getName().equals(className))
                .flatMap(classDef -> classDef.getScriptConstants().stream())
                .filter(constant -> constant.name().equals(constantName))
                .findFirst()
                .map(GdScriptClassConstant::declaration)
                .orElse(null);
    }

    private record SourceSpec(@NotNull Path sourcePath, @NotNull String source) {
        private SourceSpec(@NotNull String fileName, @NotNull String source) {
            this(Path.of("tmp", fileName), source);
        }
    }

    private record SkeletonFixture(
            @NotNull FrontendModuleSkeleton result,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull DiagnosticManager diagnostics,
            @NotNull List<FrontendSourceUnit> units
    ) {
    }

    private record ScopeFixture(
            @NotNull FrontendAnalysisData analysisData,
            @NotNull DiagnosticManager diagnostics,
            @NotNull List<FrontendSourceUnit> units
    ) {
    }
}
