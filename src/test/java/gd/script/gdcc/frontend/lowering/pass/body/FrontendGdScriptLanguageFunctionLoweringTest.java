package gd.script.gdcc.frontend.lowering.pass.body;

import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.lowering.FrontendLoweringContext;
import gd.script.gdcc.frontend.lowering.FunctionLoweringContext;
import gd.script.gdcc.frontend.lowering.pass.FrontendLoweringBodyInsnPass;
import gd.script.gdcc.frontend.lowering.pass.FrontendLoweringBuildCfgPass;
import gd.script.gdcc.frontend.lowering.pass.FrontendLoweringClassSkeletonPass;
import gd.script.gdcc.frontend.lowering.pass.FrontendLoweringFunctionPreparationPass;
import gd.script.gdcc.frontend.parse.FrontendModule;
import gd.script.gdcc.frontend.parse.GdScriptParserService;
import gd.script.gdcc.frontend.sema.analyzer.FrontendSemanticAnalyzer;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.lir.LirInstruction;
import gd.script.gdcc.lir.insn.CallGlobalInsn;
import gd.script.gdcc.lir.insn.CallMethodInsn;
import gd.script.gdcc.lir.insn.CallStaticMethodInsn;
import gd.script.gdcc.lir.insn.IsInstanceOfInsn;
import gd.script.gdcc.lir.insn.LiteralIntInsn;
import gd.script.gdcc.lir.insn.LiteralStringInsn;
import gd.script.gdcc.lir.insn.LoadStaticInsn;
import gd.script.gdcc.lir.insn.PackVariantInsn;
import gd.script.gdcc.scope.ClassRegistry;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Body-lowering contract for the synthetic GDScript language functions and `preload`:
/// `len`/`char`/`ord`/`range`/`is_instance_of` lower to `call_global` with the bare function name
/// (backend routing to `gdcc_*` is a backend concern), while `load`/`preload` rewrite to the
/// `load_static "@GlobalScope" "ResourceLoader"` + `call_method "load"` pair.
/// Arguments whose static type is not Variant-assignable are packed through `pack_variant`
/// before the call.
class FrontendGdScriptLanguageFunctionLoweringTest {
    @Test
    void lenCallPacksStringArgumentIntoVariant() throws Exception {
        var lowered = lowerProbe(
                """
                        class_name LanguageFunctionLenPack
                        extends RefCounted
                        
                        func probe() -> int:
                            return len("abc")
                        """
        );

        var call = requireOnly(lowered, CallGlobalInsn.class);
        var pack = requireOnly(lowered, PackVariantInsn.class);
        assertAll(
                () -> assertEquals("len", call.functionName()),
                () -> assertEquals(1, call.args().size()),
                // The call must consume the packed Variant slot, not the raw String literal.
                () -> assertEquals(pack.resultId(), operandId(call.args().getFirst())),
                () -> assertTrue(
                        indexOf(lowered, pack) < indexOf(lowered, call),
                        "pack_variant must precede the call_global"
                )
        );
    }

    @Test
    void charAndOrdCallsLowerToCallGlobalWithoutPacking() throws Exception {
        var lowered = lowerProbe(
                """
                        class_name LanguageFunctionCharOrd
                        extends RefCounted
                        
                        func probe() -> int:
                            var s: String = char(65)
                            return ord(s)
                        """
        );

        var calls = allInstructions(lowered).stream()
                .filter(CallGlobalInsn.class::isInstance)
                .map(CallGlobalInsn.class::cast)
                .toList();
        assertEquals(2, calls.size(), () -> "expected char + ord call_global pair in " + allInstructions(lowered));
        // Both parameter types (`int`/`String`) are directly assignable, so no pack_variant may
        // appear in the function.
        assertEquals(0, count(lowered, PackVariantInsn.class));
        assertAll(
                () -> assertEquals("char", calls.get(0).functionName()),
                () -> assertEquals("ord", calls.get(1).functionName())
        );
    }

    @Test
    void rangeCallPacksEveryIntArgumentIntoVariantVarargTail() throws Exception {
        var lowered = lowerProbe(
                """
                        class_name LanguageFunctionRangePack
                        extends RefCounted
                        
                        func probe() -> Array:
                            return range(1, 10, 2)
                        """
        );

        var call = requireOnly(lowered, CallGlobalInsn.class);
        var packs = allInstructions(lowered).stream()
                .filter(PackVariantInsn.class::isInstance)
                .map(PackVariantInsn.class::cast)
                .toList();
        // `range` has zero fixed parameters: every argument travels through the Variant vararg
        // tail, so each int literal must be packed and the call must consume the packed slots in
        // source order.
        assertAll(
                () -> assertEquals("range", call.functionName()),
                () -> assertEquals(3, call.args().size()),
                () -> assertEquals(3, packs.size(), () -> "expected one pack per argument in " + allInstructions(lowered)),
                () -> assertEquals(packs.get(0).resultId(), operandId(call.args().get(0))),
                () -> assertEquals(packs.get(1).resultId(), operandId(call.args().get(1))),
                () -> assertEquals(packs.get(2).resultId(), operandId(call.args().get(2))),
                () -> assertTrue(
                        packs.stream().allMatch(pack -> indexOf(lowered, pack) < indexOf(lowered, call)),
                        "all pack_variant instructions must precede the call_global"
                ),
                () -> assertEquals(
                        "Array",
                        lowered.getVariableById(call.resultId()).type().getTypeName(),
                        "range result slot must be typed as Array"
                )
        );
    }

    @Test
    void isInstanceOfCallLowersToCallGlobalDistinctFromTypeTestInsn() throws Exception {
        var lowered = lowerProbe(
                """
                        class_name LanguageFunctionIsInstanceOfLowering
                        extends RefCounted
                        
                        func probe(x) -> bool:
                            return is_instance_of(x, TYPE_INT)
                        """
        );

        var call = requireOnly(lowered, CallGlobalInsn.class);
        // Hard boundary: the global function must never lower to the `x is T` instruction.
        assertEquals(
                0,
                count(lowered, IsInstanceOfInsn.class),
                () -> "global is_instance_of must not produce IsInstanceOfInsn in " + allInstructions(lowered)
        );
        assertAll(
                () -> assertEquals("is_instance_of", call.functionName()),
                () -> assertEquals(2, call.args().size()),
                // `x` is Variant already; the TYPE_INT enum constant materializes as an int
                // literal that is packed into the Variant parameter slot.
                () -> assertEquals(
                        2,
                        assertInstanceOf(
                                LiteralIntInsn.class,
                                allInstructions(lowered).stream()
                                        .filter(LiteralIntInsn.class::isInstance)
                                        .filter(insn -> {
                                            var pack = allInstructions(lowered).stream()
                                                    .filter(PackVariantInsn.class::isInstance)
                                                    .map(PackVariantInsn.class::cast)
                                                    .filter(candidate -> candidate.resultId().equals(operandId(call.args().get(1))))
                                                    .findFirst()
                                                    .orElseThrow(() -> new AssertionError("TYPE_INT literal must be packed"));
                                            return pack.valueId().equals(insn.resultId());
                                        })
                                        .findFirst()
                                        .orElseThrow(() -> new AssertionError("TYPE_INT must materialize as literal_int"))
                        ).value(),
                        "TYPE_INT must keep the engine Variant.Type enum value"
                ),
                () -> assertEquals(
                        "bool",
                        lowered.getVariableById(call.resultId()).type().getTypeName(),
                        "is_instance_of result slot must be typed as bool"
                )
        );
    }

    @Test
    void loadCallRewritesToResourceLoaderSingletonCallPair() throws Exception {
        var lowered = lowerProbe(
                """
                        class_name LanguageFunctionLoadRewrite
                        extends RefCounted
                        
                        func probe() -> Resource:
                            return load("res://icon.svg")
                        """
        );

        // Synthetic `load` never reaches the backend as `call_global`; it rewrites to
        // `load_static "@GlobalScope" "ResourceLoader"` + `call_method "load"` so the ordinary
        // engine instance dispatch (with default-argument completion) handles it.
        assertEquals(
                0,
                count(lowered, CallGlobalInsn.class),
                () -> "synthetic load must not remain a call_global in " + allInstructions(lowered)
        );
        var loadStatic = requireOnly(lowered, LoadStaticInsn.class);
        var call = requireOnly(lowered, CallMethodInsn.class);
        assertAll(
                () -> assertEquals("@GlobalScope", loadStatic.className()),
                () -> assertEquals("ResourceLoader", loadStatic.staticName()),
                () -> assertEquals(
                        "ResourceLoader",
                        lowered.getVariableById(loadStatic.resultId()).type().getTypeName(),
                        "singleton receiver temp must be typed as ResourceLoader"
                ),
                () -> assertEquals("load", call.methodName()),
                () -> assertEquals(loadStatic.resultId(), call.objectId()),
                () -> assertEquals(1, call.args().size()),
                () -> assertTrue(
                        indexOf(lowered, loadStatic) < indexOf(lowered, call),
                        "load_static must precede the call_method"
                ),
                () -> assertEquals(
                        "Resource",
                        lowered.getVariableById(call.resultId()).type().getTypeName(),
                        "load result slot must be typed as Resource"
                )
        );
    }

    @Test
    void preloadLiteralLowersToSameSingletonCallPair() throws Exception {
        var lowered = lowerProbe(
                """
                        class_name PreloadLiteralLowering
                        extends RefCounted
                        
                        func probe() -> Resource:
                            return preload("res://icon.svg")
                        """
        );

        assertEquals(
                0,
                count(lowered, CallGlobalInsn.class),
                () -> "preload must not produce any call_global in " + allInstructions(lowered)
        );
        var loadStatic = requireOnly(lowered, LoadStaticInsn.class);
        var call = requireOnly(lowered, CallMethodInsn.class);
        var pathLiteral = requireOnly(lowered, LiteralStringInsn.class);
        assertAll(
                // The literal path is decoded verbatim (no compile-time normalization).
                () -> assertEquals("res://icon.svg", pathLiteral.value()),
                () -> assertEquals("@GlobalScope", loadStatic.className()),
                () -> assertEquals("ResourceLoader", loadStatic.staticName()),
                () -> assertEquals("load", call.methodName()),
                () -> assertEquals(loadStatic.resultId(), call.objectId()),
                () -> assertEquals(List.of(pathLiteral.resultId()), call.args().stream()
                        .map(FrontendGdScriptLanguageFunctionLoweringTest::operandId)
                        .toList()),
                () -> assertTrue(
                        indexOf(lowered, pathLiteral) < indexOf(lowered, call)
                                && indexOf(lowered, loadStatic) < indexOf(lowered, call),
                        "literal path and singleton load must precede the call_method"
                ),
                () -> assertEquals(
                        "Resource",
                        lowered.getVariableById(call.resultId()).type().getTypeName(),
                        "preload result slot must be typed as Resource"
                )
        );
    }

    @Test
    void userDefinedLoadShadowKeepsOrdinaryInstanceCallRoute() throws Exception {
        var lowered = lowerProbe(
                """
                        class_name LanguageFunctionLoadShadowLowering
                        extends RefCounted
                        
                        func load(path) -> String:
                            return path
                        
                        func probe() -> String:
                            return load("res://icon.svg")
                        """
        );

        // The shadow binds to the user method (declaration site is a GDCC function, not the
        // synthetic utility record), so the ResourceLoader rewrite must not trigger.
        assertEquals(
                0,
                count(lowered, LoadStaticInsn.class),
                () -> "shadowed load must not emit the singleton load_static in " + allInstructions(lowered)
        );
        var call = requireOnly(lowered, CallMethodInsn.class);
        assertAll(
                () -> assertEquals("load", call.methodName()),
                () -> assertEquals("self", call.objectId()),
                () -> assertEquals(
                        "String",
                        lowered.getVariableById(call.resultId()).type().getTypeName(),
                        "shadowed load result slot must keep the user-declared String type"
                )
        );
    }

    @Test
    void userDefinedStaticLoadShadowKeepsStaticCallRoute() throws Exception {
        var lowered = lowerProbe(
                """
                        class_name LanguageFunctionLoadStaticShadow
                        extends RefCounted
                        
                        static func load(path) -> String:
                            return path
                        
                        func probe() -> String:
                            return load("res://icon.svg")
                        """
        );

        // A user static `load` resolves with a non-null receiver type (the enclosing class), so it
        // must keep the `call_static_method` route; the rewrite only fires for the null-receiver
        // utility route carrying the synthetic declaration record.
        assertEquals(
                0,
                count(lowered, LoadStaticInsn.class),
                () -> "static shadow must not emit the singleton load_static in " + allInstructions(lowered)
        );
        assertEquals(0, count(lowered, CallGlobalInsn.class));
        var call = requireOnly(lowered, CallStaticMethodInsn.class);
        assertAll(
                () -> assertEquals("load", call.methodName()),
                () -> assertEquals(
                        "String",
                        lowered.getVariableById(call.resultId()).type().getTypeName(),
                        "static shadow result slot must keep the user-declared String type"
                )
        );
    }

    @Test
    void chainedLoadCallOnReceiverStaysOrdinaryMethodCall() throws Exception {
        var lowered = lowerProbe(
                """
                        class_name LanguageFunctionChainedLoad
                        extends RefCounted
                        
                        func probe(obj):
                            return obj.load("res://icon.svg")
                        """
        );

        // Attribute calls never reach the bare-utility rewrite branch: the chain stays a plain
        // (dynamic) `call_method` on the receiver and must not materialize the singleton.
        assertEquals(
                0,
                count(lowered, LoadStaticInsn.class),
                () -> "chained load must not emit the singleton load_static in " + allInstructions(lowered)
        );
        assertEquals(0, count(lowered, CallGlobalInsn.class));
        var call = requireOnly(lowered, CallMethodInsn.class);
        assertAll(
                () -> assertEquals("load", call.methodName()),
                () -> assertEquals("obj", call.objectId())
        );
    }

    @Test
    void sourceVariableNamedLikeRewriteTempDoesNotCollide() throws Exception {
        // The rewrite temp prefix is a legal GDScript identifier; a source local occupying the
        // first generated name must push the singleton temp to the next free id instead of being
        // overwritten or tripping the type check.
        var lowered = lowerProbe(
                """
                        class_name LanguageFunctionTempCollision
                        extends RefCounted
                        
                        func probe() -> Resource:
                            var cfg_lang_fn_resource_loader_0 = "occupied"
                            return load("res://icon.svg")
                        """
        );

        var loadStatic = requireOnly(lowered, LoadStaticInsn.class);
        assertAll(
                () -> assertFalse(
                        "cfg_lang_fn_resource_loader_0".equals(loadStatic.resultId()),
                        "rewrite temp must skip the user-occupied slot id"
                ),
                () -> assertEquals(
                        "cfg_lang_fn_resource_loader_1",
                        loadStatic.resultId(),
                        "rewrite temp must take the next free id"
                ),
                () -> assertEquals(
                        "Variant",
                        lowered.getVariableById("cfg_lang_fn_resource_loader_0").type().getTypeName(),
                        "the user variable must keep its own declared slot type (untyped var stays "
                                + "Variant); a rewrite into ResourceLoader would prove clobbering"
                )
        );
    }

    private static @NotNull LirFunctionDef lowerProbe(@NotNull String source) throws Exception {
        var diagnostics = new DiagnosticManager();
        var unit = new GdScriptParserService().parseUnit(
                Path.of("tmp", "language_function_lowering.gd"),
                source,
                diagnostics
        );
        assertTrue(diagnostics.isEmpty(), () -> "Unexpected parse diagnostics: " + diagnostics.snapshot());
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var module = new FrontendModule("test_module", List.of(unit), Map.of());
        // Shared semantic path: body lowering tests only need expression facts, not the compile gate.
        var analysisData = new FrontendSemanticAnalyzer().analyze(module, classRegistry, diagnostics);
        assertFalse(
                diagnostics.hasErrors(),
                () -> "Unexpected semantic errors before body lowering: " + diagnostics.snapshot()
        );

        var context = new FrontendLoweringContext(module, classRegistry, diagnostics);
        context.publishAnalysisData(analysisData);
        new FrontendLoweringClassSkeletonPass().run(context);
        new FrontendLoweringFunctionPreparationPass().run(context);
        new FrontendLoweringBuildCfgPass().run(context);
        new FrontendLoweringBodyInsnPass().run(context);

        return context.requireFunctionLoweringContexts().stream()
                .filter(candidate -> candidate.kind() == FunctionLoweringContext.Kind.EXECUTABLE_BODY)
                .filter(candidate -> candidate.targetFunction().getName().equals("probe"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing executable body context for probe"))
                .targetFunction();
    }

    private static @NotNull String operandId(@NotNull LirInstruction.Operand operand) {
        return assertInstanceOf(LirInstruction.VariableOperand.class, operand).id();
    }

    private static int indexOf(@NotNull LirFunctionDef function, @NotNull LirInstruction instruction) {
        return allInstructions(function).indexOf(instruction);
    }

    private static <T extends LirInstruction> @NotNull T requireOnly(
            @NotNull LirFunctionDef function,
            @NotNull Class<T> type
    ) {
        var matches = allInstructions(function).stream().filter(type::isInstance).map(type::cast).toList();
        assertEquals(1, matches.size(), () -> "Expected exactly one " + type.getSimpleName()
                + " in " + allInstructions(function));
        return matches.getFirst();
    }

    private static int count(@NotNull LirFunctionDef function, @NotNull Class<? extends LirInstruction> type) {
        return (int) allInstructions(function).stream().filter(type::isInstance).count();
    }

    private static @NotNull List<LirInstruction> allInstructions(@NotNull LirFunctionDef function) {
        var instructions = new ArrayList<LirInstruction>();
        for (var block : function) {
            instructions.addAll(block.getInstructions());
        }
        return List.copyOf(instructions);
    }
}
