package dev.superice.gdcc.frontend.lowering.pass.body;

import dev.superice.gdcc.frontend.diagnostic.DiagnosticManager;
import dev.superice.gdcc.frontend.lowering.FrontendLoweringContext;
import dev.superice.gdcc.frontend.lowering.FunctionLoweringContext;
import dev.superice.gdcc.frontend.lowering.pass.FrontendLoweringAnalysisPass;
import dev.superice.gdcc.frontend.lowering.pass.FrontendLoweringBuildCfgPass;
import dev.superice.gdcc.frontend.lowering.pass.FrontendLoweringClassSkeletonPass;
import dev.superice.gdcc.frontend.lowering.pass.FrontendLoweringFunctionPreparationPass;
import dev.superice.gdcc.frontend.parse.FrontendModule;
import dev.superice.gdcc.frontend.parse.GdScriptParserService;
import dev.superice.gdcc.gdextension.ExtensionApiLoader;
import dev.superice.gdcc.lir.LirBasicBlock;
import dev.superice.gdcc.lir.insn.LiteralNullInsn;
import dev.superice.gdcc.lir.insn.PackVariantInsn;
import dev.superice.gdcc.lir.insn.UnpackVariantInsn;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.type.GdIntType;
import dev.superice.gdcc.type.GdNilType;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdVariantType;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontendBodyLoweringSessionTest {
    @Test
    void materializeFrontendBoundaryValuePacksConcreteSourcesForVariantTargets() throws Exception {
        var session = prepareSession();
        var block = new LirBasicBlock("entry");
        session.ensureVariable("source_value", GdIntType.INT);

        var materializedSlotId = session.materializeFrontendBoundaryValue(
                block,
                "source_value",
                GdIntType.INT,
                GdVariantType.VARIANT,
                "call_arg"
        );

        var instructions = block.getNonTerminatorInstructions();
        var packedInsn = assertInstanceOf(PackVariantInsn.class, instructions.getFirst());
        var packedVariable = session.targetFunction().getVariableById(materializedSlotId);
        assertNotNull(packedVariable);

        assertAll(
                () -> assertEquals(1, instructions.size()),
                () -> assertNotEquals("source_value", materializedSlotId),
                () -> assertEquals(materializedSlotId, packedInsn.resultId()),
                () -> assertEquals("source_value", packedInsn.valueId()),
                () -> assertEquals(GdVariantType.VARIANT, packedVariable.type())
        );
    }

    @Test
    void materializeFrontendBoundaryValueUnpacksStableVariantSourcesForConcreteTargets() throws Exception {
        var session = prepareSession();
        var block = new LirBasicBlock("entry");
        session.ensureVariable("source_variant", GdVariantType.VARIANT);

        var materializedSlotId = session.materializeFrontendBoundaryValue(
                block,
                "source_variant",
                GdVariantType.VARIANT,
                GdIntType.INT,
                "return_value"
        );

        var instructions = block.getNonTerminatorInstructions();
        var unpackedInsn = assertInstanceOf(UnpackVariantInsn.class, instructions.getFirst());
        var unpackedVariable = session.targetFunction().getVariableById(materializedSlotId);
        assertNotNull(unpackedVariable);

        assertAll(
                () -> assertEquals(1, instructions.size()),
                () -> assertNotEquals("source_variant", materializedSlotId),
                () -> assertEquals(materializedSlotId, unpackedInsn.resultId()),
                () -> assertEquals("source_variant", unpackedInsn.variantId()),
                () -> assertEquals(GdIntType.INT, unpackedVariable.type())
        );
    }

    @Test
    void materializeFrontendBoundaryValueMaterializesObjectNullForNilSources() throws Exception {
        var session = prepareSession();
        var block = new LirBasicBlock("entry");
        session.ensureVariable("source_nil", GdNilType.NIL);

        var materializedSlotId = session.materializeFrontendBoundaryValue(
                block,
                "source_nil",
                GdNilType.NIL,
                GdObjectType.OBJECT,
                "object_return"
        );

        var instructions = block.getNonTerminatorInstructions();
        var literalNullInsn = assertInstanceOf(LiteralNullInsn.class, instructions.getFirst());
        var nullVariable = session.targetFunction().getVariableById(materializedSlotId);
        assertNotNull(nullVariable);

        assertAll(
                () -> assertEquals(1, instructions.size()),
                () -> assertNotEquals("source_nil", materializedSlotId),
                () -> assertEquals(materializedSlotId, literalNullInsn.resultId()),
                () -> assertEquals(GdObjectType.OBJECT, nullVariable.type())
        );
    }

    @Test
    void materializeFrontendBoundaryValueKeepsDirectRoutesInstructionFree() throws Exception {
        var session = prepareSession();
        var concreteBlock = new LirBasicBlock("concrete_direct");
        var variantBlock = new LirBasicBlock("variant_direct");
        session.ensureVariable("source_value", GdIntType.INT);
        session.ensureVariable("source_variant", GdVariantType.VARIANT);

        var directConcreteSlotId = session.materializeFrontendBoundaryValue(
                concreteBlock,
                "source_value",
                GdIntType.INT,
                GdIntType.INT,
                "local_init"
        );
        var directVariantSlotId = session.materializeFrontendBoundaryValue(
                variantBlock,
                "source_variant",
                GdVariantType.VARIANT,
                GdVariantType.VARIANT,
                "local_init"
        );

        assertAll(
                () -> assertEquals("source_value", directConcreteSlotId),
                () -> assertEquals("source_variant", directVariantSlotId),
                () -> assertTrue(concreteBlock.getNonTerminatorInstructions().isEmpty()),
                () -> assertTrue(variantBlock.getNonTerminatorInstructions().isEmpty())
        );
    }

    private static @NotNull FrontendBodyLoweringSession prepareSession() throws Exception {
        var diagnostics = new DiagnosticManager();
        var module = parseModule(
                List.of(new SourceFixture(
                        "body_lowering_session_helper.gd",
                        """
                                class_name BodyLoweringSessionHelper
                                extends RefCounted
                                
                                func ping(seed: int) -> int:
                                    return seed
                                """
                )),
                Map.of("BodyLoweringSessionHelper", "RuntimeBodyLoweringSessionHelper")
        );
        var context = new FrontendLoweringContext(
                module,
                new ClassRegistry(ExtensionApiLoader.loadDefault()),
                diagnostics
        );
        new FrontendLoweringAnalysisPass().run(context);
        new FrontendLoweringClassSkeletonPass().run(context);
        new FrontendLoweringFunctionPreparationPass().run(context);
        new FrontendLoweringBuildCfgPass().run(context);
        assertFalse(diagnostics.hasErrors(), () -> "Unexpected lowering diagnostics: " + diagnostics.snapshot());
        return new FrontendBodyLoweringSession(requireContext(context.requireFunctionLoweringContexts()));
    }

    private static @NotNull FrontendModule parseModule(
            @NotNull List<SourceFixture> fixtures,
            @NotNull Map<String, String> topLevelCanonicalNameMap
    ) {
        var parserService = new GdScriptParserService();
        var parseDiagnostics = new DiagnosticManager();
        var units = fixtures.stream()
                .map(fixture -> parserService.parseUnit(Path.of("tmp", fixture.fileName()), fixture.source(), parseDiagnostics))
                .toList();
        assertTrue(parseDiagnostics.isEmpty(), () -> "Unexpected parse diagnostics: " + parseDiagnostics.snapshot());
        return new FrontendModule("test_module", units, topLevelCanonicalNameMap);
    }

    private static @NotNull FunctionLoweringContext requireContext(@NotNull List<FunctionLoweringContext> contexts) {
        return contexts.stream()
                .filter(context -> context.kind() == FunctionLoweringContext.Kind.EXECUTABLE_BODY)
                .filter(context -> context.owningClass().getName().equals("RuntimeBodyLoweringSessionHelper"))
                .filter(context -> context.targetFunction().getName().equals("ping"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing executable body context for RuntimeBodyLoweringSessionHelper.ping"));
    }

    private record SourceFixture(
            @NotNull String fileName,
            @NotNull String source
    ) {
    }
}
