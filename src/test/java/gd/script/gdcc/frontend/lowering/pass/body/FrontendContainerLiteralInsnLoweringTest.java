package gd.script.gdcc.frontend.lowering.pass.body;

import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.lowering.FrontendBodyLoweringSupport;
import gd.script.gdcc.frontend.lowering.FrontendLoweringContext;
import gd.script.gdcc.frontend.lowering.FunctionLoweringContext;
import gd.script.gdcc.frontend.lowering.cfg.FrontendCfgGraph;
import gd.script.gdcc.frontend.lowering.cfg.item.ContainerLiteralItem;
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
import gd.script.gdcc.lir.insn.CallIntrinsicInsn;
import gd.script.gdcc.lir.insn.ConstructBuiltinInsn;
import gd.script.gdcc.lir.insn.ConstructContainerLiteralInsn;
import gd.script.gdcc.lir.insn.PackVariantInsn;
import gd.script.gdcc.lir.insn.UnpackVariantInsn;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdArrayType;
import gd.script.gdcc.type.GdDictionaryType;
import gd.script.gdcc.type.GdFloatType;
import gd.script.gdcc.type.GdStringNameType;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.type.GdVariantType;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/// Body-lowering contract for array/dictionary literals.
///
/// Uses `analyzeForCompile(...)` so compile-gate release is part of the lowering readiness path.
class FrontendContainerLiteralInsnLoweringTest {

    @Test
    void lowersGenericArrayLiteralPreservingElementOrder() throws Exception {
        var lowered = lowerProbe(
                """
                        class_name ContainerLiteralArrayOrder
                        extends RefCounted
                        
                        func probe() -> Array:
                            return [1, 2, 3]
                        """
        );

        var wiring = requireContainerWiring(lowered);
        var construct = requireOnlyConstruct(lowered.function());
        assertAll(
                () -> assertEquals(wiring.resultSlotId(), construct.resultId()),
                () -> assertEquals(3, construct.operands().size()),
                () -> assertEquals(
                        new GdArrayType(GdVariantType.VARIANT),
                        requireVariableType(lowered.function(), wiring.resultSlotId())
                ),
                () -> assertTrue(
                        instructionIndex(lowered.function(), construct)
                                > instructionIndexOfLastOperandProducer(lowered.function(), construct),
                        "construct must follow operand producers"
                )
        );
    }

    @Test
    void lowersEmptyArrayAndDictionaryLiterals() throws Exception {
        var emptyArray = lowerProbe(
                """
                        class_name ContainerLiteralEmptyArray
                        extends RefCounted
                        
                        func probe() -> Array:
                            return []
                        """
        );
        var emptyDict = lowerProbe(
                """
                        class_name ContainerLiteralEmptyDict
                        extends RefCounted
                        
                        func probe() -> Dictionary:
                            return {}
                        """
        );

        var arrayConstruct = requireOnlyConstruct(emptyArray.function());
        var dictConstruct = requireOnlyConstruct(emptyDict.function());
        assertAll(
                () -> assertTrue(arrayConstruct.operands().isEmpty()),
                () -> assertTrue(dictConstruct.operands().isEmpty()),
                () -> assertEquals(
                        new GdArrayType(GdVariantType.VARIANT),
                        requireVariableType(emptyArray.function(), arrayConstruct.resultId())
                ),
                () -> assertEquals(
                        new GdDictionaryType(GdVariantType.VARIANT, GdVariantType.VARIANT),
                        requireVariableType(emptyDict.function(), dictConstruct.resultId())
                )
        );
    }

    @Test
    void lowersDictionaryLiteralAsKeyValuePairsInSourceOrder() throws Exception {
        var lowered = lowerProbe(
                """
                        class_name ContainerLiteralDictOrder
                        extends RefCounted
                        
                        func probe() -> Dictionary:
                            return {1: 2, 3: 4}
                        """
        );

        var construct = requireOnlyConstruct(lowered.function());
        assertEquals(4, construct.operands().size());
        // Four distinct operand slots (key0,value0,key1,value1); order is structural.
        var ids = construct.operands().stream()
                .map(op -> ((LirInstruction.VariableOperand) op).id())
                .toList();
        assertEquals(4, ids.stream().distinct().count());
    }

    @Test
    void materializesIntToFloatBeforeTypedArrayConstruct() throws Exception {
        var lowered = lowerProbe(
                """
                        class_name ContainerLiteralIntToFloat
                        extends RefCounted
                        
                        func probe() -> Array[float]:
                            return [1, 2]
                        """
        );

        var construct = requireOnlyConstruct(lowered.function());
        var instructions = allInstructions(lowered.function());
        var intrinsicCasts = instructions.stream()
                .filter(CallIntrinsicInsn.class::isInstance)
                .map(CallIntrinsicInsn.class::cast)
                .filter(insn -> "c_int_to_float".equals(insn.intrinsicName()))
                .toList();
        assertAll(
                () -> assertEquals(2, construct.operands().size()),
                () -> assertEquals(2, intrinsicCasts.size(), () -> "Expected two c_int_to_float, got " + instructions),
                () -> assertTrue(
                        instructionIndex(instructions, intrinsicCasts.getFirst())
                                < instructionIndex(instructions, construct)
                ),
                () -> assertTrue(
                        instructionIndex(instructions, intrinsicCasts.get(1))
                                < instructionIndex(instructions, construct)
                ),
                () -> assertEquals(
                        List.of(
                                intrinsicCasts.getFirst().resultId(),
                                intrinsicCasts.get(1).resultId()
                        ),
                        construct.operands().stream()
                                .map(op -> ((LirInstruction.VariableOperand) op).id())
                                .toList()
                ),
                () -> assertEquals(
                        new GdArrayType(GdFloatType.FLOAT),
                        requireVariableType(lowered.function(), construct.resultId())
                )
        );
    }

    @Test
    void materializesStringToStringNameBeforeTypedDictionaryKey() throws Exception {
        var lowered = lowerProbe(
                """
                        class_name ContainerLiteralStringKey
                        extends RefCounted
                        
                        func probe() -> Dictionary[StringName, int]:
                            return {"a": 1}
                        """
        );

        var construct = requireOnlyConstruct(lowered.function());
        var instructions = allInstructions(lowered.function());
        var constructors = instructions.stream()
                .filter(ConstructBuiltinInsn.class::isInstance)
                .map(ConstructBuiltinInsn.class::cast)
                .toList();
        assertFalse(constructors.isEmpty(), () -> "Expected String->StringName construct_builtin: " + instructions);
        var keyConstructor = constructors.getFirst();
        assertAll(
                () -> assertEquals(2, construct.operands().size()),
                () -> assertEquals(
                        keyConstructor.resultId(),
                        ((LirInstruction.VariableOperand) construct.operands().getFirst()).id()
                ),
                () -> assertTrue(
                        instructionIndex(instructions, keyConstructor)
                                < instructionIndex(instructions, construct)
                ),
                () -> assertEquals(
                        GdStringNameType.STRING_NAME,
                        requireVariableType(lowered.function(), keyConstructor.resultId())
                )
        );
    }

    @Test
    void materializesVariantUnpackBeforeTypedArrayElement() throws Exception {
        var lowered = lowerProbe(
                """
                        class_name ContainerLiteralVariantUnpack
                        extends RefCounted
                        
                        func probe(v: Variant) -> Array[int]:
                            return [v]
                        """
        );

        var construct = requireOnlyConstruct(lowered.function());
        var instructions = allInstructions(lowered.function());
        var unpacks = instructions.stream()
                .filter(UnpackVariantInsn.class::isInstance)
                .map(UnpackVariantInsn.class::cast)
                .toList();
        assertEquals(1, unpacks.size(), () -> "Expected one unpack_variant: " + instructions);
        var unpack = unpacks.getFirst();
        assertAll(
                () -> assertEquals(1, construct.operands().size()),
                () -> assertEquals(
                        unpack.resultId(),
                        ((LirInstruction.VariableOperand) construct.operands().getFirst()).id()
                ),
                () -> assertTrue(
                        instructionIndex(instructions, unpack) < instructionIndex(instructions, construct)
                )
        );
    }

    @Test
    void packsConcreteElementsForGenericArrayLiteral() throws Exception {
        // Generic Array element slots are Variant; concrete int must pack before construct.
        var lowered = lowerProbe(
                """
                        class_name ContainerLiteralGenericPack
                        extends RefCounted
                        
                        func probe() -> Array:
                            return [1]
                        """
        );

        var construct = requireOnlyConstruct(lowered.function());
        var instructions = allInstructions(lowered.function());
        var packInsns = instructions.stream()
                .filter(PackVariantInsn.class::isInstance)
                .map(PackVariantInsn.class::cast)
                .toList();
        assertEquals(1, packInsns.size(), () -> "Expected one pack_variant for generic Array[int element]: " + instructions);
        var pack = packInsns.getFirst();
        assertAll(
                () -> assertEquals(1, construct.operands().size()),
                () -> assertEquals(
                        new GdArrayType(GdVariantType.VARIANT),
                        requireVariableType(lowered.function(), construct.resultId())
                ),
                () -> assertTrue(instructionIndex(instructions, pack) < instructionIndex(instructions, construct)),
                () -> assertEquals(
                        pack.resultId(),
                        ((LirInstruction.VariableOperand) construct.operands().getFirst()).id()
                ),
                () -> assertEquals(GdVariantType.VARIANT, requireVariableType(lowered.function(), pack.resultId()))
        );
    }

    @Test
    void nestedArrayLiteralEmitsInnerConstructBeforeOuter() throws Exception {
        var lowered = lowerProbe(
                """
                        class_name ContainerLiteralNested
                        extends RefCounted
                        
                        func probe() -> Array:
                            return [[1], [2]]
                        """
        );

        var instructions = allInstructions(lowered.function());
        var constructs = instructions.stream()
                .filter(ConstructContainerLiteralInsn.class::isInstance)
                .map(ConstructContainerLiteralInsn.class::cast)
                .toList();
        assertEquals(3, constructs.size(), () -> "Expected 2 inner + 1 outer constructs: " + instructions);
        var outer = constructs.getLast();
        assertEquals(2, outer.operands().size());
        // Outer generic Array packs each nested Array into Variant before construct.
        var packs = instructions.stream()
                .filter(PackVariantInsn.class::isInstance)
                .map(PackVariantInsn.class::cast)
                .toList();
        assertTrue(packs.size() >= 2, () -> "Expected packs of nested arrays: " + instructions);
        var outerOpIds = outer.operands().stream()
                .map(op -> ((LirInstruction.VariableOperand) op).id())
                .toList();
        assertEquals(
                packs.subList(packs.size() - 2, packs.size()).stream().map(PackVariantInsn::resultId).toList(),
                outerOpIds
        );
        assertTrue(
                instructionIndex(instructions, constructs.get(0))
                        < instructionIndex(instructions, outer)
        );
        assertTrue(
                instructionIndex(instructions, constructs.get(1))
                        < instructionIndex(instructions, outer)
        );
        // Each outer pack source is an inner construct result.
        var innerResultIds = constructs.subList(0, 2).stream()
                .map(ConstructContainerLiteralInsn::resultId)
                .toList();
        var outerPackSources = packs.subList(packs.size() - 2, packs.size()).stream()
                .map(PackVariantInsn::valueId)
                .toList();
        assertEquals(innerResultIds, outerPackSources);
    }

    // --- helpers ---

    private static @NotNull ConstructContainerLiteralInsn requireOnlyConstruct(@NotNull LirFunctionDef function) {
        var constructs = allInstructions(function).stream()
                .filter(ConstructContainerLiteralInsn.class::isInstance)
                .map(ConstructContainerLiteralInsn.class::cast)
                .toList();
        assertEquals(1, constructs.size(), () -> "Expected exactly one construct_container_literal: " + allInstructions(function));
        return constructs.getFirst();
    }

    private static @NotNull ContainerWiring requireContainerWiring(@NotNull LoweredProbe lowered) {
        var items = collectContainerItems(lowered.functionContext().requireFrontendCfgGraph());
        assertFalse(items.isEmpty(), "CFG must publish ContainerLiteralItem");
        // Prefer outermost / last published item as the function result producer when nested.
        var item = items.getLast();
        return new ContainerWiring(
                FrontendBodyLoweringSupport.cfgTempSlotId(item.resultValueId()),
                item
        );
    }

    private static @NotNull List<ContainerLiteralItem> collectContainerItems(@NotNull FrontendCfgGraph graph) {
        var items = new ArrayList<ContainerLiteralItem>();
        for (var nodeId : graph.nodeIds()) {
            if (!(graph.requireNode(nodeId) instanceof FrontendCfgGraph.SequenceNode(_, var sequenceItems, _))) {
                continue;
            }
            for (var item : sequenceItems) {
                if (item instanceof ContainerLiteralItem containerLiteralItem) {
                    items.add(containerLiteralItem);
                }
            }
        }
        return items;
    }

    private static @NotNull LoweredProbe lowerProbe(@NotNull String source) throws Exception {
        var diagnostics = new DiagnosticManager();
        var unit = new GdScriptParserService().parseUnit(
                Path.of("tmp", "container_literal_body_lowering.gd"),
                source,
                diagnostics
        );
        assertTrue(diagnostics.isEmpty(), () -> "Unexpected parse diagnostics: " + diagnostics.snapshot());
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var className = source.lines()
                .filter(line -> line.startsWith("class_name "))
                .map(line -> line.substring("class_name ".length()).trim())
                .findFirst()
                .orElse("ContainerLiteralBody");
        var module = new FrontendModule(
                "test_module",
                List.of(unit),
                Map.of(className, "Runtime" + className)
        );
        var analysisData = new FrontendSemanticAnalyzer().analyzeForCompile(module, classRegistry, diagnostics);
        assertFalse(
                diagnostics.hasErrors(),
                () -> "Unexpected compile-ready errors before body lowering: " + diagnostics.snapshot()
        );

        var context = new FrontendLoweringContext(module, classRegistry, diagnostics);
        context.publishAnalysisData(analysisData);
        new FrontendLoweringClassSkeletonPass().run(context);
        new FrontendLoweringFunctionPreparationPass().run(context);
        new FrontendLoweringBuildCfgPass().run(context);
        new FrontendLoweringBodyInsnPass().run(context);

        var functionContext = context.requireFunctionLoweringContexts().stream()
                .filter(candidate -> candidate.kind() == FunctionLoweringContext.Kind.EXECUTABLE_BODY)
                .filter(candidate -> candidate.targetFunction().getName().equals("probe"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing executable body context for probe"));
        return new LoweredProbe(functionContext, functionContext.targetFunction());
    }

    private static @NotNull List<LirInstruction> allInstructions(@NotNull LirFunctionDef function) {
        var instructions = new ArrayList<LirInstruction>();
        for (var block : function) {
            instructions.addAll(block.getInstructions());
        }
        return List.copyOf(instructions);
    }

    private static int instructionIndex(
            @NotNull LirFunctionDef function,
            @NotNull LirInstruction target
    ) {
        return instructionIndex(allInstructions(function), target);
    }

    private static int instructionIndex(
            @NotNull List<LirInstruction> instructions,
            @NotNull LirInstruction target
    ) {
        for (var index = 0; index < instructions.size(); index++) {
            if (instructions.get(index) == target) {
                return index;
            }
        }
        fail("Instruction not found: " + target);
        return -1;
    }

    private static int instructionIndexOfLastOperandProducer(
            @NotNull LirFunctionDef function,
            @NotNull ConstructContainerLiteralInsn construct
    ) {
        var instructions = allInstructions(function);
        var constructIndex = instructionIndex(instructions, construct);
        var last = -1;
        for (var index = 0; index < constructIndex; index++) {
            var insn = instructions.get(index);
            if (construct.operands().stream().anyMatch(op ->
                    op instanceof LirInstruction.VariableOperand(var id)
                            && id.equals(insn.resultId()))) {
                last = index;
            }
        }
        return last;
    }

    private static @NotNull GdType requireVariableType(
            @NotNull LirFunctionDef function,
            @NotNull String variableId
    ) {
        var variable = function.getVariableById(variableId);
        assertNotNull(variable, () -> "Missing LIR variable " + variableId + " in " + function.getVariables());
        return variable.type();
    }

    private record LoweredProbe(
            @NotNull FunctionLoweringContext functionContext,
            @NotNull LirFunctionDef function
    ) {
    }

    private record ContainerWiring(
            @NotNull String resultSlotId,
            @NotNull ContainerLiteralItem item
    ) {
    }
}
