package gd.script.gdcc.backend.c.gen;

import gd.script.gdcc.backend.CodegenContext;
import gd.script.gdcc.backend.GeneratedFile;
import gd.script.gdcc.backend.ProjectInfo;
import gd.script.gdcc.backend.c.gen.binding.usage.GodotBindingUsageSession;
import gd.script.gdcc.enums.GodotVersion;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.lir.LirBasicBlock;
import gd.script.gdcc.lir.LirCaptureDef;
import gd.script.gdcc.lir.LirClassDef;
import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.lir.LirInstruction;
import gd.script.gdcc.lir.LirModule;
import gd.script.gdcc.lir.LirParameterDef;
import gd.script.gdcc.lir.LirPropertyDef;
import gd.script.gdcc.lir.insn.ConstructArrayInsn;
import gd.script.gdcc.lir.insn.ConstructBuiltinInsn;
import gd.script.gdcc.lir.insn.ConstructDictionaryInsn;
import gd.script.gdcc.lir.insn.LiteralBoolInsn;
import gd.script.gdcc.lir.insn.LiteralFloatInsn;
import gd.script.gdcc.lir.insn.LiteralIntInsn;
import gd.script.gdcc.lir.insn.LiteralNilInsn;
import gd.script.gdcc.lir.insn.LiteralNullInsn;
import gd.script.gdcc.lir.insn.LiteralStringInsn;
import gd.script.gdcc.lir.insn.LiteralStringNameInsn;
import gd.script.gdcc.lir.insn.ReturnInsn;
import gd.script.gdcc.lir.parser.DomLirParser;
import gd.script.gdcc.lir.parser.DomLirSerializer;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdArrayType;
import gd.script.gdcc.type.GdBoolType;
import gd.script.gdcc.type.GdColorType;
import gd.script.gdcc.type.GdDictionaryType;
import gd.script.gdcc.type.GdFloatType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdPackedNumericArrayType;
import gd.script.gdcc.type.GdStringNameType;
import gd.script.gdcc.type.GdStringType;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.type.GdVariantType;
import gd.script.gdcc.type.GdVoidType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Prepare/generate integration for the frozen `@onready` session plan across codegen
/// sessions sharing one module.
class CCodegenOnreadyTest {
    /// `Node`-derived class covering each initializer form the plan distinguishes.
    private static LirClassDef newOnreadyHostClass() {
        var host = new LirClassDef("OnreadyHost", "Node");
        host.addFunction(newHiddenIntInit("OnreadyHost", "_field_init_onready_source", 7));
        host.addFunction(newHiddenIntInit("OnreadyHost", "_field_init_plain_source", 3));
        host.addProperty(new LirPropertyDef(
                "onready_source", GdIntType.INT, false, "_field_init_onready_source",
                null, null, Map.of("onready", "")));
        host.addProperty(new LirPropertyDef(
                "onready_missing", GdIntType.INT, false, null,
                null, null, Map.of("onready", "")));
        host.addProperty(new LirPropertyDef(
                "plain_source", GdIntType.INT, false, "_field_init_plain_source",
                null, null, Map.of()));
        host.addProperty(new LirPropertyDef(
                "plain_missing", GdIntType.INT, false, null,
                null, null, Map.of()));
        host.addProperty(new LirPropertyDef(
                "static_missing", GdIntType.INT, true, null,
                null, null, Map.of()));
        return host;
    }

    /// A valid source init helper: hidden, one owning-class `self` param, returns the
    /// property type - the shape the property-init contract requires.
    private static LirFunctionDef newHiddenIntInit(String ownerClassName, String name, long value) {
        var func = new LirFunctionDef(name);
        func.setHidden(true);
        func.setReturnType(GdIntType.INT);
        func.addParameter(new LirParameterDef("self", new GdObjectType(ownerClassName), null, func));
        var tmpVar = func.createAndAddTmpVariable(GdIntType.INT);
        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new LiteralIntInsn(tmpVar.id(), value));
        entry.setTerminator(new ReturnInsn(tmpVar.id()));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        return func;
    }

    private static LirPropertyDef propertyOf(LirClassDef classDef, String name) {
        return classDef.getProperties().stream()
                .filter(property -> property.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static LirFunctionDef functionNamed(LirClassDef classDef, String name) {
        return classDef.getFunctions().stream()
                .filter(function -> function.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static CodegenContext newContext() throws IOException {
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var projectInfo = new ProjectInfo("TestProject", GodotVersion.V451, Path.of(".")) {
        };
        return new CodegenContext(projectInfo, registry);
    }

    private static CCodegen newPreparedCodegen(LirModule module) throws IOException {
        var codegen = new CCodegen();
        codegen.prepare(newContext(), module);
        return codegen;
    }

    @Test
    @DisplayName("prepare should freeze the onready plan before default init synthesis")
    void prepareShouldFreezeOnreadyPlanBeforeDefaultSynthesis() throws IOException {
        var host = newOnreadyHostClass();
        var module = new LirModule("onready_plan_module", List.of(host));
        var codegen = newPreparedCodegen(module);

        var plan = codegen.onreadyPlan();
        var classPlan = plan.classPlan("OnreadyHost");
        var onreadySource = propertyOf(host, "onready_source");
        var onreadyMissing = propertyOf(host, "onready_missing");
        assertEquals(List.of(onreadySource), classPlan.onreadyInitializers());
        assertTrue(classPlan.chainHasOnreadyInitializer());
        assertTrue(classPlan.classOrDescendantHasOnreadyInitializer());
        assertNull(classPlan.userReadyOwnerClassName());
        assertNull(classPlan.userReadyFunction());
        // Synthesis has not run yet: source-less properties still carry a null initFunc.
        assertNull(onreadyMissing.getInitFunc());
        assertFalse(onreadyMissing.isInitializerSynthesizedByBackend());

        var files = codegen.generate();
        assertFalse(files.isEmpty());
        for (var file : files) {
            assertTrue(file.contentWriter().length > 0, file.filePath());
        }

        // The plan is a frozen snapshot: generate-time backfill must not change it.
        assertSame(plan, codegen.onreadyPlan());
        assertEquals(List.of(onreadySource),
                codegen.onreadyPlan().classPlan("OnreadyHost").onreadyInitializers());
        assertEquals("_field_init_onready_missing", onreadyMissing.getInitFunc());
        assertTrue(onreadyMissing.isInitializerSynthesizedByBackend());
        assertTrue(propertyOf(host, "plain_missing").isInitializerSynthesizedByBackend());
        assertFalse(onreadySource.isInitializerSynthesizedByBackend());
        assertFalse(propertyOf(host, "plain_source").isInitializerSynthesizedByBackend());
        // Static properties never receive a synthesized default init helper.
        assertNull(propertyOf(host, "static_missing").getInitFunc());
        assertFalse(propertyOf(host, "static_missing").isInitializerSynthesizedByBackend());
    }

    @Test
    @DisplayName("two codegen sessions over the same module should keep identical onready facts")
    void twoCodegenSessionsOverSameModuleShouldKeepIdenticalOnreadyFacts() throws IOException {
        var host = newOnreadyHostClass();
        var module = new LirModule("onready_double_session_module", List.of(host));
        var first = newPreparedCodegen(module);
        var firstPlan = first.onreadyPlan().classPlan("OnreadyHost");
        var firstFiles = first.generate();
        var functionCountAfterFirst = host.getFunctions().size();
        var firstSynthesizedInit = functionNamed(host, "_field_init_onready_missing");
        var firstSynthesizedGetter = functionNamed(host, "_field_getter_onready_source");

        var second = newPreparedCodegen(module);
        var secondPlan = second.onreadyPlan().classPlan("OnreadyHost");
        // Both sessions reference the same LIR objects, so the frozen facts compare equal.
        assertEquals(firstPlan, secondPlan);
        var onreadySource = propertyOf(host, "onready_source");
        var onreadyMissing = propertyOf(host, "onready_missing");
        // The marker set by the first session keeps the backfill classified as non-source.
        assertEquals(List.of(onreadySource), secondPlan.onreadyInitializers());
        assertTrue(onreadyMissing.isInitializerSynthesizedByBackend());

        var secondFiles = second.generate();
        assertFalse(firstFiles.isEmpty());
        assertFalse(secondFiles.isEmpty());
        // No synthesized helper may be duplicated by the second session.
        assertEquals(functionCountAfterFirst, host.getFunctions().size());
        assertSame(firstSynthesizedInit, functionNamed(host, "_field_init_onready_missing"));
        assertSame(firstSynthesizedGetter, functionNamed(host, "_field_getter_onready_source"));
        assertEquals(1, host.getFunctions().stream()
                .filter(f -> f.getName().equals("_field_setter_onready_source")).count());

        // Re-preparing an already-used codegen rebuilds helper and plan with the same facts.
        first.prepare(newContext(), module);
        assertEquals(firstPlan, first.onreadyPlan().classPlan("OnreadyHost"));
    }

    @Test
    @DisplayName("setInitFunc should clear the backend-default marker for later sessions")
    void setInitFuncShouldClearBackendDefaultMarkerForLaterSessions() throws IOException {
        var host = newOnreadyHostClass();
        var module = new LirModule("onready_repoint_module", List.of(host));
        var first = newPreparedCodegen(module);
        first.generate();
        var onreadyMissing = propertyOf(host, "onready_missing");
        assertTrue(onreadyMissing.isInitializerSynthesizedByBackend());

        // A null assignment through the regular setter drops the name and the marker.
        onreadyMissing.setInitFunc(null);
        assertNull(onreadyMissing.getInitFunc());
        assertFalse(onreadyMissing.isInitializerSynthesizedByBackend());

        // An explicit re-point at a real helper is a source assignment; the marker clears.
        onreadyMissing.setInitFunc("_field_init_replacement");
        assertFalse(onreadyMissing.isInitializerSynthesizedByBackend());
        host.addFunction(newHiddenIntInit("OnreadyHost", "_field_init_replacement", 9));

        var second = newPreparedCodegen(module);
        var plan = second.onreadyPlan().classPlan("OnreadyHost");
        assertEquals(List.of(propertyOf(host, "onready_source"), onreadyMissing),
                plan.onreadyInitializers());
        var files = second.generate();
        assertFalse(files.isEmpty());
    }

    @Test
    @DisplayName("serialized module should parse back into identical plan facts and generate")
    void serializedFrontendModuleShouldSurviveParseIntoPrepareAndGenerate() throws Exception {
        var host = newOnreadyHostClass();
        var module = new LirModule("onready_xml_module", List.of(host));
        var xml = new DomLirSerializer().serializeToString(module);
        var parseRegistry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var parsedModule = new DomLirParser(parseRegistry).parse(new StringReader(xml));

        var parsedHost = parsedModule.getClassDefs().getFirst();
        var first = newPreparedCodegen(parsedModule);
        var plan = first.onreadyPlan().classPlan(parsedHost.getName());
        var onreadySource = propertyOf(parsedHost, "onready_source");
        var onreadyMissing = propertyOf(parsedHost, "onready_missing");
        assertEquals(List.of(onreadySource), plan.onreadyInitializers());
        assertFalse(onreadySource.isInitializerSynthesizedByBackend());
        assertNull(onreadyMissing.getInitFunc());

        var firstFiles = first.generate();
        assertFalse(firstFiles.isEmpty());
        assertTrue(onreadyMissing.isInitializerSynthesizedByBackend());
        assertFalse(onreadySource.isInitializerSynthesizedByBackend());
        var functionCount = parsedHost.getFunctions().size();
        var synthesizedInit = functionNamed(parsedHost, "_field_init_onready_missing");
        var synthesizedDefault = functionNamed(parsedHost, "_onready_field_default_onready_source");

        // The C routing survives the XML roundtrip: construction applies the type default,
        // the real source initializer is only wired to the ready apply.
        var cCode = generatedEntryC(firstFiles);
        var constructorBody = functionBodyByPrefix(cCode, "void OnreadyHost_class_constructor");
        assertTrue(constructorBody.contains("OnreadyHost_class_apply_property_default_onready_source(self);"), constructorBody);
        assertFalse(cCode.contains("OnreadyHost_class_apply_property_init_onready_source"), cCode);
        assertTrue(cCode.contains("OnreadyHost_class_apply_onready_init_onready_source"), cCode);

        // A second session over the same parsed module sees identical facts and synthesizes
        // nothing twice.
        var second = newPreparedCodegen(parsedModule);
        assertEquals(plan, second.onreadyPlan().classPlan(parsedHost.getName()));
        assertFalse(second.generate().isEmpty());
        assertEquals(functionCount, parsedHost.getFunctions().size());
        assertSame(synthesizedInit, functionNamed(parsedHost, "_field_init_onready_missing"));
        assertSame(synthesizedDefault, functionNamed(parsedHost, "_onready_field_default_onready_source"));
    }

    @Test
    @DisplayName("onreadyPlan accessor should fail before prepare")
    void onreadyPlanAccessorShouldFailBeforePrepare() {
        var codegen = new CCodegen();
        assertThrows(IllegalStateException.class, codegen::onreadyPlan);
    }

    // ==== construction-time default routing and ready-time apply ====

    private static String generatedEntryC(List<GeneratedFile> files) {
        return files.stream()
                .filter(file -> file.filePath().equals("entry.c"))
                .findFirst()
                .map(file -> new String(file.contentWriter()))
                .orElseThrow(() -> new AssertionError("Missing generated entry.c"));
    }

    /// Extracts one generated function body by brace matching from its signature prefix.
    private static String functionBodyByPrefix(String code, String signaturePrefix) {
        var signatureIndex = code.indexOf(signaturePrefix);
        assertTrue(signatureIndex >= 0, "Missing function prefix: " + signaturePrefix);
        var openBraceIndex = code.indexOf('{', signatureIndex);
        assertTrue(openBraceIndex >= 0, "Missing opening brace for " + signaturePrefix);
        var depth = 0;
        for (var i = openBraceIndex; i < code.length(); i++) {
            var ch = code.charAt(i);
            if (ch == '{') {
                depth++;
            } else if (ch == '}' && --depth == 0) {
                return code.substring(openBraceIndex + 1, i);
            }
        }
        throw new AssertionError("Missing closing brace for " + signaturePrefix);
    }

    private static int countOccurrences(String text, String needle) {
        var count = 0;
        var index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    /// A valid source init helper of arbitrary return type: hidden, one owning-class `self`
    /// param, one tmp produced by `insnFactory` (a factory so the result id can be bound).
    private static LirFunctionDef newSourceInit(
            String ownerClassName,
            String name,
            GdType returnType,
            Function<String, LirInstruction> insnFactory
    ) {
        var func = new LirFunctionDef(name);
        func.setHidden(true);
        func.setReturnType(returnType);
        func.addParameter(new LirParameterDef("self", new GdObjectType(ownerClassName), null, func));
        var tmpVar = func.createAndAddTmpVariable(returnType);
        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(insnFactory.apply(tmpVar.id()));
        entry.setTerminator(new ReturnInsn(tmpVar.id()));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        return func;
    }

    /// A contract-valid occupant of the reserved `_onready_field_default_` slot — the shape
    /// reused across sessions — which the negative cases mutate into malformed variants.
    private static LirFunctionDef newValidDefaultOccupant(String ownerClassName) {
        return newHiddenIntInit(ownerClassName, "_onready_field_default_onready_source", 1);
    }

    private static LirInstruction firstInstructionOfEntry(LirFunctionDef func) {
        return func.getBasicBlock("entry").getInstructions().getFirst();
    }

    @Test
    @DisplayName("constructor and init_fields should route onready source-init properties through the default apply")
    void generateShouldRouteOnreadySourceInitThroughDefaultApplyAtConstruction() throws IOException {
        var host = newOnreadyHostClass();
        var module = new LirModule("onready_default_route_module", List.of(host));
        var codegen = newPreparedCodegen(module);
        var cCode = generatedEntryC(codegen.generate());

        for (var body : List.of(
                functionBodyByPrefix(cCode, "void OnreadyHost_class_constructor"),
                functionBodyByPrefix(cCode, "void OnreadyHost_class_init_fields"))) {
            assertTrue(body.contains("OnreadyHost_class_apply_property_default_onready_source(self);"), body);
            assertTrue(body.contains("OnreadyHost_class_apply_property_init_onready_missing(self);"), body);
            assertTrue(body.contains("OnreadyHost_class_apply_property_init_plain_source(self);"), body);
            assertTrue(body.contains("OnreadyHost_class_apply_property_init_plain_missing(self);"), body);
            assertFalse(body.contains("apply_property_init_onready_source"), body);
            assertFalse(body.contains("apply_onready_init_"), body);
        }

        // The default apply first-writes the reserved hidden helper's type default; the
        // real source init helper is emitted normally and only the ready apply calls it.
        var defaultApplyBody = functionBodyByPrefix(
                cCode, "static inline void OnreadyHost_class_apply_property_default_onready_source");
        assertTrue(defaultApplyBody.contains(
                "self->onready_source = OnreadyHost__onready_field_default_onready_source("
                        + "gdcc_OnreadyHost_fat_ptr_from_raw(OnreadyHost_object_ptr(self))"), defaultApplyBody);
        var readyApplyBody = functionBodyByPrefix(
                cCode, "static inline void OnreadyHost_class_apply_onready_init_onready_source");
        assertTrue(readyApplyBody.contains(
                "self->onready_source = OnreadyHost__field_init_onready_source("
                        + "gdcc_OnreadyHost_fat_ptr_from_raw(OnreadyHost_object_ptr(self))"), readyApplyBody);
        // Scalar overwrite is a bare assignment: no carrier temp, no destroy.
        assertFalse(readyApplyBody.contains("__gdcc_tmp_"), readyApplyBody);
        assertFalse(readyApplyBody.contains("_destroy("), readyApplyBody);
        assertTrue(cCode.contains("OnreadyHost__field_init_onready_source("), cCode);
        assertFalse(cCode.contains("OnreadyHost_class_apply_property_init_onready_source"), cCode);

        // No ready/default machinery for the ineligible forms; statics stay fully untouched.
        for (var name : List.of("onready_missing", "plain_source", "plain_missing")) {
            assertFalse(cCode.contains("OnreadyHost_class_apply_onready_init_" + name), cCode);
            assertFalse(cCode.contains("OnreadyHost_class_apply_property_default_" + name), cCode);
            assertFalse(cCode.contains("_onready_field_default_" + name), cCode);
        }
        assertFalse(cCode.contains("OnreadyHost_class_apply_onready_init_static_missing"), cCode);
        assertFalse(cCode.contains("_onready_field_default_static_missing"), cCode);

        // The reserved helper is a hidden instance helper shaped like `_field_init_`; the
        // source initFunc and its provenance stay untouched.
        var defaultHelper = functionNamed(host, "_onready_field_default_onready_source");
        assertTrue(defaultHelper.isHidden());
        assertFalse(defaultHelper.isStatic());
        assertEquals(GdIntType.INT, defaultHelper.getReturnType());
        assertEquals(1, defaultHelper.getParameterCount());
        var onreadySource = propertyOf(host, "onready_source");
        assertEquals("_field_init_onready_source", onreadySource.getInitFunc());
        assertFalse(onreadySource.isInitializerSynthesizedByBackend());
    }

    @Test
    @DisplayName("construction routing should stay frozen when annotations mutate after prepare")
    void constructionRoutingShouldStayFrozenAcrossPostPrepareAnnotationMutation() throws IOException {
        var host = newOnreadyHostClass();
        var module = new LirModule("onready_frozen_route_module", List.of(host));
        var codegen = newPreparedCodegen(module);

        // Live annotation edits after the plan was captured must not re-route: removing
        // `onready` from the eligible property and tagging a plain one changes nothing.
        propertyOf(host, "onready_source").getAnnotations().remove("onready");
        propertyOf(host, "plain_source").getAnnotations().put("onready", "");

        var cCode = generatedEntryC(codegen.generate());
        var constructorBody = functionBodyByPrefix(cCode, "void OnreadyHost_class_constructor");
        assertTrue(constructorBody.contains("OnreadyHost_class_apply_property_default_onready_source(self);"), constructorBody);
        assertTrue(constructorBody.contains("OnreadyHost_class_apply_property_init_plain_source(self);"), constructorBody);
        assertFalse(constructorBody.contains("apply_property_init_onready_source"), constructorBody);
        assertFalse(constructorBody.contains("apply_property_default_plain_source"), constructorBody);
    }

    @Test
    @DisplayName("repeated sessions should reuse the onready default helper and emit identical apply bodies")
    void repeatedSessionsShouldReuseOnreadyDefaultHelperAndEmitIdenticalApplyBodies() throws IOException {
        var host = newOnreadyHostClass();
        var module = new LirModule("onready_helper_reuse_module", List.of(host));

        var first = newPreparedCodegen(module);
        var firstC = generatedEntryC(first.generate());
        var defaultHelper = functionNamed(host, "_onready_field_default_onready_source");

        var second = newPreparedCodegen(module);
        var secondC = generatedEntryC(second.generate());
        // The valid occupant of the reserved slot is reused, never duplicated.
        assertEquals(1, host.getFunctions().stream()
                .filter(f -> f.getName().equals("_onready_field_default_onready_source")).count());
        assertSame(defaultHelper, functionNamed(host, "_onready_field_default_onready_source"));
        // Constructor, init_fields, both apply wrappers and the hidden default helper body
        // all stay byte-identical across sessions.
        for (var prefix : List.of(
                "void OnreadyHost_class_constructor",
                "void OnreadyHost_class_init_fields",
                "static inline void OnreadyHost_class_apply_property_default_onready_source",
                "static inline void OnreadyHost_class_apply_onready_init_onready_source",
                "godot_int OnreadyHost__onready_field_default_onready_source(")) {
            assertEquals(functionBodyByPrefix(firstC, prefix), functionBodyByPrefix(secondC, prefix), prefix);
        }
        // The ineligible sibling keeps the ordinary route in the second session too.
        assertTrue(secondC.contains("OnreadyHost_class_apply_property_init_onready_missing(self);"), secondC);
        assertFalse(secondC.contains("OnreadyHost_class_apply_property_default_onready_missing"), secondC);
        assertFalse(secondC.contains("OnreadyHost_class_apply_onready_init_onready_missing"), secondC);
        assertFalse(secondC.contains("_onready_field_default_onready_missing"), secondC);
        var onreadySource = propertyOf(host, "onready_source");
        assertEquals("_field_init_onready_source", onreadySource.getInitFunc());
        assertFalse(onreadySource.isInitializerSynthesizedByBackend());

        // Re-preparing an already-used codegen keeps the same helper and routing stable.
        second.prepare(newContext(), module);
        second.generate();
        assertEquals(1, host.getFunctions().stream()
                .filter(f -> f.getName().equals("_onready_field_default_onready_source")).count());
        assertSame(defaultHelper, functionNamed(host, "_onready_field_default_onready_source"));
    }

    private record OnreadyDefaultCase(
            GdType propertyType,
            Function<String, LirInstruction> sourceInsn,
            Class<? extends LirInstruction> expectedInsnType,
            Consumer<LirInstruction> metadataCheck
    ) {
    }

    /// Every default-table arm must produce identical instructions through both the ordinary
    /// `_field_init_` backfill and the `_onready_field_default_` slot — one shared table, no
    /// untyped substitution for typed containers.
    private static List<OnreadyDefaultCase> onreadyDefaultCases() {
        return List.of(
                new OnreadyDefaultCase(GdIntType.INT,
                        id -> new LiteralIntInsn(id, 9), LiteralIntInsn.class,
                        insn -> assertEquals(0L, ((LiteralIntInsn) insn).value())),
                new OnreadyDefaultCase(GdBoolType.BOOL,
                        id -> new LiteralBoolInsn(id, true), LiteralBoolInsn.class,
                        insn -> assertFalse(((LiteralBoolInsn) insn).value())),
                new OnreadyDefaultCase(GdFloatType.FLOAT,
                        id -> new LiteralFloatInsn(id, 2.5), LiteralFloatInsn.class,
                        insn -> assertEquals(0.0, ((LiteralFloatInsn) insn).value())),
                new OnreadyDefaultCase(GdStringType.STRING,
                        id -> new LiteralStringInsn(id, "x"), LiteralStringInsn.class,
                        insn -> assertEquals("", ((LiteralStringInsn) insn).value())),
                new OnreadyDefaultCase(GdStringNameType.STRING_NAME,
                        id -> new LiteralStringNameInsn(id, "x"), LiteralStringNameInsn.class,
                        insn -> assertEquals("", ((LiteralStringNameInsn) insn).value())),
                new OnreadyDefaultCase(GdVariantType.VARIANT,
                        LiteralNilInsn::new, LiteralNilInsn.class, _ -> {
                }),
                new OnreadyDefaultCase(new GdObjectType("RefCounted"),
                        LiteralNullInsn::new, LiteralNullInsn.class, _ -> {
                }),
                new OnreadyDefaultCase(new GdArrayType(GdIntType.INT),
                        id -> new ConstructArrayInsn(id, "int"), ConstructArrayInsn.class,
                        insn -> assertEquals("int", ((ConstructArrayInsn) insn).className())),
                new OnreadyDefaultCase(new GdDictionaryType(GdStringType.STRING, GdIntType.INT),
                        id -> new ConstructDictionaryInsn(id, "String", "int"), ConstructDictionaryInsn.class,
                        insn -> {
                            var dict = (ConstructDictionaryInsn) insn;
                            assertEquals("String", dict.keyClassName());
                            assertEquals("int", dict.valueClassName());
                        }),
                new OnreadyDefaultCase(GdPackedNumericArrayType.PACKED_INT32_ARRAY,
                        id -> new ConstructArrayInsn(id, null), ConstructArrayInsn.class,
                        insn -> assertNull(((ConstructArrayInsn) insn).className())),
                new OnreadyDefaultCase(GdColorType.COLOR,
                        id -> new ConstructBuiltinInsn(id, List.of()), ConstructBuiltinInsn.class,
                        insn -> assertTrue(((ConstructBuiltinInsn) insn).args().isEmpty()))
        );
    }

    @Test
    @DisplayName("onready default helpers should share the exact type default table with the ordinary backfill")
    void onreadyDefaultHelpersShouldShareTheExactTypeDefaultTable() throws IOException {
        var cases = onreadyDefaultCases();
        var host = new LirClassDef("OnreadyDefaults", "Node");
        for (var i = 0; i < cases.size(); i++) {
            var c = cases.get(i);
            host.addFunction(newSourceInit("OnreadyDefaults", "_field_init_p" + i, c.propertyType(), c.sourceInsn()));
            host.addProperty(new LirPropertyDef(
                    "p" + i, c.propertyType(), false, "_field_init_p" + i, null, null, Map.of("onready", "")));
            host.addProperty(new LirPropertyDef(
                    "plain" + i, c.propertyType(), false, null, null, null, Map.of()));
        }
        var codegen = newPreparedCodegen(new LirModule("onready_defaults_module", List.of(host)));
        codegen.generate();

        for (var i = 0; i < cases.size(); i++) {
            var c = cases.get(i);
            for (var helperName : List.of("_onready_field_default_p" + i, "_field_init_plain" + i)) {
                var insn = assertInstanceOf(c.expectedInsnType(),
                        firstInstructionOfEntry(functionNamed(host, helperName)), helperName);
                c.metadataCheck().accept(insn);
            }
        }
    }

    /// One onready property of the given type whose source init produces `sourceInsn`.
    private static void addOnreadyProperty(
            LirClassDef host,
            String name,
            GdType type,
            Function<String, LirInstruction> sourceInsn
    ) {
        host.addFunction(newSourceInit(host.getName(), "_field_init_" + name, type, sourceInsn));
        host.addProperty(new LirPropertyDef(name, type, false, "_field_init_" + name, null, null, Map.of("onready", "")));
    }

    @Test
    @DisplayName("ready apply should destroy-then-move for destroyable value types while default apply first-writes")
    void readyApplyShouldDestroyThenMoveForDestroyableValueTypes() throws IOException {
        var host = new LirClassDef("OnreadyVals", "Node");
        addOnreadyProperty(host, "s", GdStringType.STRING, id -> new LiteralStringInsn(id, "init"));
        addOnreadyProperty(host, "arr", new GdArrayType(GdIntType.INT), id -> new ConstructArrayInsn(id, "int"));
        addOnreadyProperty(host, "dict", new GdDictionaryType(GdStringType.STRING, GdIntType.INT),
                id -> new ConstructDictionaryInsn(id, "String", "int"));
        addOnreadyProperty(host, "v", GdVariantType.VARIANT, LiteralNilInsn::new);
        addOnreadyProperty(host, "pk", GdPackedNumericArrayType.PACKED_INT32_ARRAY,
                id -> new ConstructArrayInsn(id, null));
        var cCode = generatedEntryC(newPreparedCodegen(
                new LirModule("onready_vals_module", List.of(host))).generate());

        var movePattern = Pattern.compile("self->(\\w+) = __gdcc_tmp_owned_move_\\d+;");
        for (var prop : List.of("s", "arr", "dict", "v", "pk")) {
            var readyBody = functionBodyByPrefix(
                    cCode, "static inline void OnreadyVals_class_apply_onready_init_" + prop);
            // Call result materialized first -> exactly one old-value destroy -> carrier moved
            // in by plain assignment; no copy constructor and the carrier is never destroyed.
            var callIndex = readyBody.indexOf("= OnreadyVals__field_init_" + prop + "(");
            var destroyIndex = readyBody.indexOf("_destroy(&self->" + prop + ")");
            var matcher = movePattern.matcher(readyBody);
            assertTrue(callIndex >= 0, readyBody);
            assertTrue(destroyIndex > callIndex, readyBody);
            assertTrue(matcher.find(destroyIndex), readyBody);
            assertEquals(1, countOccurrences(readyBody, "_destroy(&self->" + prop + ")"), readyBody);
            assertFalse(readyBody.contains("godot_new_"), readyBody);
            assertFalse(readyBody.contains("_destroy(&__gdcc_tmp"), readyBody);

            var defaultBody = functionBodyByPrefix(
                    cCode, "static inline void OnreadyVals_class_apply_property_default_" + prop);
            assertTrue(defaultBody.contains(
                    "self->" + prop + " = OnreadyVals__onready_field_default_" + prop + "("), defaultBody);
            assertFalse(defaultBody.contains("_destroy("), defaultBody);
            assertFalse(defaultBody.contains("release_object("), defaultBody);
        }
    }

    @Test
    @DisplayName("ready apply should capture-assign-release object fields per refcount status")
    void readyApplyShouldCaptureAssignReleaseObjectFieldsPerRefcountStatus() throws IOException {
        var host = new LirClassDef("OnreadyObjs", "Node");
        addOnreadyProperty(host, "rc", new GdObjectType("RefCounted"), LiteralNullInsn::new);
        addOnreadyProperty(host, "obj", new GdObjectType("Object"), LiteralNullInsn::new);
        addOnreadyProperty(host, "node", new GdObjectType("Node"), LiteralNullInsn::new);
        var cCode = generatedEntryC(newPreparedCodegen(
                new LirModule("onready_objs_module", List.of(host))).generate());

        for (var prop : List.of("rc", "obj", "node")) {
            var readyBody = functionBodyByPrefix(
                    cCode, "static inline void OnreadyObjs_class_apply_onready_init_" + prop);
            // Lifecycle invariant: materialize call -> capture old -> move carrier -> release
            // the OLD temp only; the fresh result is never re-owned or released.
            var callIndex = readyBody.indexOf("= OnreadyObjs__field_init_" + prop + "(");
            var captureIndex = readyBody.indexOf("= self->" + prop + ";");
            assertTrue(callIndex >= 0, readyBody);
            assertTrue(captureIndex > callIndex, readyBody);
            var carrierName = extractTemp(readyBody, "__gdcc_tmp_owned_move_");
            var oldName = extractTemp(readyBody, "__gdcc_tmp_old_obj_");
            var moveIndex = readyBody.indexOf("self->" + prop + " = " + carrierName + ";");
            assertTrue(moveIndex > captureIndex, readyBody);
            assertFalse(readyBody.contains("own_object("), readyBody);
            assertFalse(readyBody.contains("try_own_object("), readyBody);
            var afterMove = readyBody.substring(moveIndex);
            if (!prop.equals("node")) {
                assertTrue(afterMove.contains("release_object("), afterMove);
                // Exactly one release, and it targets the captured old temp, not the carrier.
                var releaseIndex = afterMove.indexOf("release_object(");
                var releaseCall = afterMove.substring(releaseIndex, afterMove.indexOf(";", releaseIndex));
                assertTrue(releaseCall.contains(oldName), releaseCall);
                assertFalse(releaseCall.contains(carrierName), releaseCall);
                assertFalse(afterMove.contains("_destroy("), afterMove);
            } else {
                assertFalse(afterMove.contains("release_object("), afterMove);
                assertFalse(afterMove.contains("try_release_object("), afterMove);
                assertFalse(afterMove.contains("_destroy("), afterMove);
            }
            assertFalse(afterMove.contains(carrierName + ".instance_id"), afterMove);
        }
        var rcBody = functionBodyByPrefix(cCode, "static inline void OnreadyObjs_class_apply_onready_init_rc");
        var objBody = functionBodyByPrefix(cCode, "static inline void OnreadyObjs_class_apply_onready_init_obj");
        var nodeBody = functionBodyByPrefix(cCode, "static inline void OnreadyObjs_class_apply_onready_init_node");
        assertEquals(1, countOccurrences(rcBody, "\nrelease_object("), rcBody);
        assertEquals(0, countOccurrences(rcBody, "try_release_object("), rcBody);
        assertEquals(1, countOccurrences(objBody, "try_release_object("), objBody);
        assertEquals(0, countOccurrences(objBody, "\nrelease_object("), objBody);
        assertFalse(nodeBody.contains("release_object("), nodeBody);
        assertFalse(nodeBody.contains("try_release_object("), nodeBody);

        // Object default applies are pure first-write: no old capture, no own, no release.
        for (var prop : List.of("rc", "obj", "node")) {
            var defaultBody = functionBodyByPrefix(
                    cCode, "static inline void OnreadyObjs_class_apply_property_default_" + prop);
            assertTrue(defaultBody.contains(
                    "self->" + prop + " = OnreadyObjs__onready_field_default_" + prop + "("), defaultBody);
            assertFalse(defaultBody.contains("__gdcc_tmp_"), defaultBody);
            assertFalse(defaultBody.contains("own_object("), defaultBody);
            assertFalse(defaultBody.contains("release_object("), defaultBody);
            assertFalse(defaultBody.contains("_destroy("), defaultBody);
        }
    }

    /// Extracts the first `__gdcc_tmp_<prefix><N>` temp name from a generated body.
    private static String extractTemp(String body, String prefix) {
        var matcher = Pattern.compile("(" + Pattern.quote(prefix) + "\\d+)").matcher(body);
        assertTrue(matcher.find(), body);
        return matcher.group(1);
    }

    @Test
    @DisplayName("onready default and ready applies should write the field directly, never the custom setter")
    void onreadyApplyHelpersShouldBypassCustomSetter() throws IOException {
        var host = new LirClassDef("OnreadySetter", "Node");
        host.addFunction(newHiddenIntInit("OnreadySetter", "_field_init_flag", 1));
        var setter = new LirFunctionDef("custom_flag_setter");
        setter.setReturnType(GdVoidType.VOID);
        setter.addParameter(new LirParameterDef("self", new GdObjectType("OnreadySetter"), null, setter));
        setter.addParameter(new LirParameterDef("value", GdIntType.INT, null, setter));
        var setterEntry = new LirBasicBlock("entry");
        setterEntry.setTerminator(new ReturnInsn(null));
        setter.addBasicBlock(setterEntry);
        setter.setEntryBlockId("entry");
        host.addFunction(setter);
        host.addProperty(new LirPropertyDef(
                "flag", GdIntType.INT, false, "_field_init_flag", null, "custom_flag_setter", Map.of("onready", "")));

        var cCode = generatedEntryC(newPreparedCodegen(
                new LirModule("onready_setter_module", List.of(host))).generate());
        for (var body : List.of(
                functionBodyByPrefix(cCode, "static inline void OnreadySetter_class_apply_property_default_flag"),
                functionBodyByPrefix(cCode, "static inline void OnreadySetter_class_apply_onready_init_flag"))) {
            assertTrue(body.contains("self->flag ="), body);
            assertFalse(body.contains("custom_flag_setter"), body);
        }
    }

    /// A contract-valid occupant of the `_onready_field_default_` slot whose `self` parameter
    /// may be mutated into each malformed signature variant.
    private static LirFunctionDef newDefaultOccupantWithSelfParam(String selfName, GdObjectType selfType) {
        var func = new LirFunctionDef("_onready_field_default_onready_source");
        func.setHidden(true);
        func.setReturnType(GdIntType.INT);
        func.addParameter(new LirParameterDef(selfName, selfType, null, func));
        var tmpVar = func.createAndAddTmpVariable(GdIntType.INT);
        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new LiteralIntInsn(tmpVar.id(), 1));
        entry.setTerminator(new ReturnInsn(tmpVar.id()));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        return func;
    }

    /// Cases mutating the host with one malformed `_onready_field_default_` occupant each;
    /// every variant must fail generation before control-flow grafting, never repaired.
    /// The third argument asserts case-specific post-failure invariants.
    private static Stream<Arguments> malformedOnreadyDefaultOccupants() {
        var noPostCheck = (Consumer<LirClassDef>) host -> {
        };
        Consumer<LirClassDef> nonHidden = host -> {
            var occupant = newValidDefaultOccupant("OnreadyHost");
            occupant.setHidden(false);
            host.addFunction(occupant);
        };
        Consumer<LirClassDef> shellOnly = host -> {
            var shell = new LirFunctionDef("_onready_field_default_onready_source");
            shell.setHidden(true);
            shell.setReturnType(GdIntType.INT);
            shell.addParameter(new LirParameterDef("self", new GdObjectType("OnreadyHost"), null, shell));
            host.addFunction(shell);
        };
        Consumer<LirClassDef> wrongReturn = host -> {
            var occupant = newValidDefaultOccupant("OnreadyHost");
            occupant.setReturnType(GdFloatType.FLOAT);
            host.addFunction(occupant);
        };
        Consumer<LirClassDef> invalidEntry = host -> {
            var occupant = newValidDefaultOccupant("OnreadyHost");
            occupant.setEntryBlockId("bogus");
            host.addFunction(occupant);
        };
        Consumer<LirClassDef> zeroParams = host -> {
            var occupant = new LirFunctionDef("_onready_field_default_onready_source");
            occupant.setHidden(true);
            occupant.setReturnType(GdIntType.INT);
            var tmpVar = occupant.createAndAddTmpVariable(GdIntType.INT);
            var entry = new LirBasicBlock("entry");
            entry.appendInstruction(new LiteralIntInsn(tmpVar.id(), 1));
            entry.setTerminator(new ReturnInsn(tmpVar.id()));
            occupant.addBasicBlock(entry);
            occupant.setEntryBlockId("entry");
            host.addFunction(occupant);
        };
        Consumer<LirClassDef> extraParam = host -> {
            var occupant = newValidDefaultOccupant("OnreadyHost");
            occupant.addParameter(new LirParameterDef("extra", GdIntType.INT, null, occupant));
            host.addFunction(occupant);
        };
        Consumer<LirClassDef> wrongSelfName = host ->
                host.addFunction(newDefaultOccupantWithSelfParam("other", new GdObjectType("OnreadyHost")));
        Consumer<LirClassDef> wrongSelfType = host ->
                host.addFunction(newDefaultOccupantWithSelfParam("self", new GdObjectType("Node")));
        Consumer<LirClassDef> staticFlag = host -> {
            var occupant = newValidDefaultOccupant("OnreadyHost");
            occupant.setStatic(true);
            host.addFunction(occupant);
        };
        Consumer<LirFunctionDef> coroutine = f -> f.setCoroutine(true);
        Consumer<LirFunctionDef> lambdaFlag = f -> f.setLambda(true);
        Consumer<LirFunctionDef> abstractFlag = f -> f.setAbstract(true);
        Consumer<LirFunctionDef> vararg = f -> f.setVararg(true);
        Consumer<LirFunctionDef> capturesWithoutLambda = f -> {
            f.setLambda(true);
            f.addCapture(new LirCaptureDef("cap", GdIntType.INT, f));
            f.setLambda(false);
        };
        Consumer<LirClassDef> duplicate = host -> {
            host.addFunction(newValidDefaultOccupant("OnreadyHost"));
            host.addFunction(newValidDefaultOccupant("OnreadyHost"));
        };
        return Stream.of(
                Arguments.of("non_hidden", nonHidden, noPostCheck),
                Arguments.of("shell_only", shellOnly,
                        (Consumer<LirClassDef>) host -> assertEquals(0,
                                functionNamed(host, "_onready_field_default_onready_source").getBasicBlockCount())),
                Arguments.of("wrong_return", wrongReturn, noPostCheck),
                Arguments.of("invalid_entry", invalidEntry, noPostCheck),
                Arguments.of("zero_params", zeroParams, noPostCheck),
                Arguments.of("extra_param", extraParam, noPostCheck),
                Arguments.of("wrong_self_name", wrongSelfName, noPostCheck),
                Arguments.of("wrong_self_type", wrongSelfType, noPostCheck),
                Arguments.of("static", staticFlag, noPostCheck),
                Arguments.of("coroutine", (Consumer<LirClassDef>) host -> coroutine.accept(newValidDefaultOccupantAdded(host)), noPostCheck),
                Arguments.of("lambda", (Consumer<LirClassDef>) host -> lambdaFlag.accept(newValidDefaultOccupantAdded(host)), noPostCheck),
                Arguments.of("abstract", (Consumer<LirClassDef>) host -> abstractFlag.accept(newValidDefaultOccupantAdded(host)), noPostCheck),
                Arguments.of("vararg", (Consumer<LirClassDef>) host -> vararg.accept(newValidDefaultOccupantAdded(host)), noPostCheck),
                Arguments.of("captures_without_lambda",
                        (Consumer<LirClassDef>) host -> capturesWithoutLambda.accept(newValidDefaultOccupantAdded(host)), noPostCheck),
                Arguments.of("duplicate", duplicate, noPostCheck)
        );
    }

    private static LirFunctionDef newValidDefaultOccupantAdded(LirClassDef host) {
        var occupant = newValidDefaultOccupant("OnreadyHost");
        host.addFunction(occupant);
        return occupant;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedOnreadyDefaultOccupants")
    @DisplayName("malformed onready default slot occupants should fail generation")
    void generateShouldRejectMalformedOnreadyDefaultOccupant(
            String caseName,
            Consumer<LirClassDef> occupantSetup,
            Consumer<LirClassDef> postCheck
    ) throws IOException {
        var host = newOnreadyHostClass();
        occupantSetup.accept(host);
        var module = new LirModule("onready_default_malformed_" + caseName, List.of(host));
        var codegen = newPreparedCodegen(module);

        var exception = assertThrows(IllegalStateException.class, codegen::generate);
        assertTrue(exception.getMessage().contains("_onready_field_default_onready_source"), exception.getMessage());
        // Reserved-slot validation fails before any control-flow grafting.
        for (var function : host.getFunctions()) {
            assertNull(function.getBasicBlock("__prepare__"), function.getName());
            assertNull(function.getBasicBlock("__finally__"), function.getName());
        }
        postCheck.accept(host);
    }

    @Test
    @DisplayName("generated apply symbols should conflict with same-named user functions")
    void generatedApplySymbolsShouldConflictWithSameNamedUserFunctions() throws IOException {
        var clashingDefault = newOnreadyHostClass();
        clashingDefault.addFunction(newHiddenIntInit("OnreadyHost", "class_apply_property_default_onready_source", 1));
        var defaultModule = new LirModule("onready_symbol_clash_default", List.of(clashingDefault));
        var defaultException = assertThrows(IllegalStateException.class,
                () -> newPreparedCodegen(defaultModule).generate());
        assertTrue(defaultException.getMessage().contains("C file-scope symbol conflict"), defaultException.getMessage());

        var clashingReady = newOnreadyHostClass();
        clashingReady.addFunction(newHiddenIntInit("OnreadyHost", "class_apply_onready_init_onready_source", 1));
        var readyModule = new LirModule("onready_symbol_clash_ready", List.of(clashingReady));
        var readyException = assertThrows(IllegalStateException.class,
                () -> newPreparedCodegen(readyModule).generate());
        assertTrue(readyException.getMessage().contains("C file-scope symbol conflict"), readyException.getMessage());
    }

    @Test
    @DisplayName("onready apply renderers should reject ineligible properties")
    void onreadyApplyRenderersShouldRejectIneligibleProperties() throws IOException {
        var host = newOnreadyHostClass();
        var module = new LirModule("onready_ineligible_module", List.of(host));
        var codegen = newPreparedCodegen(module);
        var session = GodotBindingUsageSession.forRegistry(codegen.ctx.classRegistry());

        for (var name : List.of("plain_source", "plain_missing", "onready_missing", "static_missing")) {
            var property = propertyOf(host, name);
            assertThrows(IllegalStateException.class,
                    () -> codegen.generatePropertyDefaultApplyBody(host, property, session));
            assertThrows(IllegalStateException.class,
                    () -> codegen.generateOnreadyApplyBody(host, property, session));
        }
        // The ordinary init-apply entry must not be used as a bypass for eligible properties.
        var onreadySource = propertyOf(host, "onready_source");
        assertThrows(IllegalStateException.class,
                () -> codegen.generatePropertyInitApplyBody(host, onreadySource, session));
    }

    /// Mutations applying each special-function contract violation to a resolved init helper;
    /// the shared signature guard rejects them on every init route (source, ordinary, static).
    private static Stream<Arguments> specialInitFlagCases() {
        Consumer<LirFunctionDef> coroutine = f -> f.setCoroutine(true);
        Consumer<LirFunctionDef> lambda = f -> f.setLambda(true);
        Consumer<LirFunctionDef> abstractFlag = f -> f.setAbstract(true);
        Consumer<LirFunctionDef> vararg = f -> f.setVararg(true);
        Consumer<LirFunctionDef> capturesWithoutLambda = f -> {
            f.setLambda(true);
            f.addCapture(new LirCaptureDef("cap", GdIntType.INT, f));
            f.setLambda(false);
        };
        return Stream.of(
                Arguments.of("coroutine", coroutine),
                Arguments.of("lambda", lambda),
                Arguments.of("abstract", abstractFlag),
                Arguments.of("vararg", vararg),
                Arguments.of("captures_without_lambda", capturesWithoutLambda)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("specialInitFlagCases")
    @DisplayName("source onready init helpers with special function flags or captures should fail generation")
    void generateShouldRejectSpecialFlagsOnSourceOnreadyInit(String caseName, Consumer<LirFunctionDef> mutation)
            throws IOException {
        var host = newOnreadyHostClass();
        mutation.accept(functionNamed(host, "_field_init_onready_source"));
        var module = new LirModule("onready_source_flag_" + caseName, List.of(host));
        var codegen = newPreparedCodegen(module);

        var exception = assertThrows(IllegalStateException.class, codegen::generate);
        assertTrue(exception.getMessage().contains("_field_init_onready_source"), exception.getMessage());
        // The eligible source helper is validated during synthesis, before any grafting.
        for (var function : host.getFunctions()) {
            assertNull(function.getBasicBlock("__prepare__"), function.getName());
            assertNull(function.getBasicBlock("__finally__"), function.getName());
        }
    }

    @Test
    @DisplayName("ordinary init helpers with special function flags should fail generation")
    void generateShouldRejectSpecialFlagsOnOrdinaryInitHelper() throws IOException {
        var host = newOnreadyHostClass();
        functionNamed(host, "_field_init_plain_source").setCoroutine(true);
        var module = new LirModule("ordinary_init_flag_module", List.of(host));
        var codegen = newPreparedCodegen(module);

        var exception = assertThrows(IllegalStateException.class, codegen::generate);
        assertTrue(exception.getMessage().contains("_field_init_plain_source"), exception.getMessage());
        assertTrue(exception.getMessage().contains("special function flags"), exception.getMessage());
    }

    @Test
    @DisplayName("static source init helpers with special function flags should fail generation")
    void generateShouldRejectSpecialFlagsOnStaticSourceInit() throws IOException {
        var host = new LirClassDef("StaticFlagHost", "Node");
        var init = new LirFunctionDef("_field_init_sflag");
        init.setHidden(true);
        init.setStatic(true);
        init.setVararg(true);
        init.setReturnType(GdIntType.INT);
        var tmpVar = init.createAndAddTmpVariable(GdIntType.INT);
        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new LiteralIntInsn(tmpVar.id(), 1));
        entry.setTerminator(new ReturnInsn(tmpVar.id()));
        init.addBasicBlock(entry);
        init.setEntryBlockId("entry");
        host.addFunction(init);
        host.addProperty(new LirPropertyDef(
                "sflag", GdIntType.INT, true, "_field_init_sflag", null, null, Map.of()));
        var codegen = newPreparedCodegen(new LirModule("static_init_flag_module", List.of(host)));

        var exception = assertThrows(IllegalStateException.class, codegen::generate);
        assertTrue(exception.getMessage().contains("_field_init_sflag"), exception.getMessage());
        assertTrue(exception.getMessage().contains("special function flags"), exception.getMessage());
    }

    private static void assertGenerateRejectsLostSourceProvenance(
            LirClassDef host,
            String moduleName
    ) throws IOException {
        var module = new LirModule(moduleName, List.of(host));
        var codegen = newPreparedCodegen(module);
        var onreadySource = propertyOf(host, "onready_source");
        onreadySource.setInitFunc(null);

        var exception = assertThrows(IllegalStateException.class, codegen::generate);
        assertTrue(exception.getMessage().contains("onready_source"), exception.getMessage());
        // A lost source reference must not be backfilled or replaced: initFunc stays null,
        // the marker stays false, no default slot was synthesized, nothing was grafted.
        assertNull(onreadySource.getInitFunc());
        assertFalse(onreadySource.isInitializerSynthesizedByBackend());
        assertTrue(host.getFunctions().stream()
                .noneMatch(f -> f.getName().equals("_onready_field_default_onready_source")));
        for (var function : host.getFunctions()) {
            assertNull(function.getBasicBlock("__prepare__"), function.getName());
            assertNull(function.getBasicBlock("__finally__"), function.getName());
        }
    }

    @Test
    @DisplayName("frozen eligible property that loses its source initFunc should fail generation")
    void generateShouldRejectEligiblePropertyWithClearedSourceInit() throws IOException {
        assertGenerateRejectsLostSourceProvenance(newOnreadyHostClass(),
                "onready_init_cleared_module");
    }

    @Test
    @DisplayName("frozen eligible property whose initFunc and old helper are both removed should fail generation")
    void generateShouldRejectEligiblePropertyWithClearedInitAndRemovedHelper() throws IOException {
        var host = newOnreadyHostClass();
        var module = new LirModule("onready_init_cleared_removed_module", List.of(host));
        var codegen = newPreparedCodegen(module);
        var onreadySource = propertyOf(host, "onready_source");
        onreadySource.setInitFunc(null);
        host.removeFunction(functionNamed(host, "_field_init_onready_source"));

        var exception = assertThrows(IllegalStateException.class, codegen::generate);
        assertTrue(exception.getMessage().contains("onready_source"), exception.getMessage());
        assertNull(onreadySource.getInitFunc());
        assertFalse(onreadySource.isInitializerSynthesizedByBackend());
        assertTrue(host.getFunctions().stream()
                .noneMatch(f -> f.getName().equals("_onready_field_default_onready_source")));
    }

    @Test
    @DisplayName("eligible property whose source init name gets the backend marker should fail generation and direct render")
    void generateShouldRejectBackendMarkedSourceProvenance() throws IOException {
        var host = newOnreadyHostClass();
        var module = new LirModule("onready_marker_poisoned_module", List.of(host));
        var codegen = newPreparedCodegen(module);
        var onreadySource = propertyOf(host, "onready_source");
        onreadySource.setBackendSynthesizedInitFunc("_field_init_onready_source");

        var exception = assertThrows(IllegalStateException.class, codegen::generate);
        assertTrue(exception.getMessage().contains("onready_source"), exception.getMessage());
        assertTrue(onreadySource.isInitializerSynthesizedByBackend());
        assertTrue(host.getFunctions().stream()
                .noneMatch(f -> f.getName().equals("_onready_field_default_onready_source")));

        var session = GodotBindingUsageSession.forRegistry(codegen.ctx.classRegistry());
        assertThrows(IllegalStateException.class,
                () -> codegen.generateOnreadyApplyBody(host, onreadySource, session));
    }

    @Test
    @DisplayName("a property initFunc referencing the reserved onready default name should fail generation")
    void generateShouldRejectInitFuncAliasingReservedDefaultName() throws IOException {
        // Self-alias: the eligible property's own initFunc spells the reserved slot name and
        // a valid hidden helper by that name exists — the alias scan must still reject it.
        var selfAlias = newOnreadyHostClass();
        var selfOccupant = newValidDefaultOccupant("OnreadyHost");
        selfAlias.addFunction(selfOccupant);
        propertyOf(selfAlias, "onready_source").setInitFunc("_onready_field_default_onready_source");
        var selfModule = new LirModule("onready_alias_self_module", List.of(selfAlias));
        var selfException = assertThrows(IllegalStateException.class,
                () -> newPreparedCodegen(selfModule).generate());
        assertTrue(selfException.getMessage().contains("_onready_field_default_onready_source"),
                selfException.getMessage());
        assertEquals("_onready_field_default_onready_source",
                propertyOf(selfAlias, "onready_source").getInitFunc());
        assertNull(selfOccupant.getBasicBlock("__prepare__"));

        // Sibling-alias: another property's initFunc points at the reserved slot name.
        var siblingAlias = newOnreadyHostClass();
        var siblingOccupant = newValidDefaultOccupant("OnreadyHost");
        siblingAlias.addFunction(siblingOccupant);
        propertyOf(siblingAlias, "plain_source").setInitFunc("_onready_field_default_onready_source");
        var siblingModule = new LirModule("onready_alias_sibling_module", List.of(siblingAlias));
        var siblingException = assertThrows(IllegalStateException.class,
                () -> newPreparedCodegen(siblingModule).generate());
        assertTrue(siblingException.getMessage().contains("_onready_field_default_onready_source"),
                siblingException.getMessage());
        assertEquals("_onready_field_default_onready_source",
                propertyOf(siblingAlias, "plain_source").getInitFunc());
        assertNull(siblingOccupant.getBasicBlock("__prepare__"));
    }

    @Test
    @DisplayName("ordinary instance init helper with a static signature should be rejected")
    void generateShouldRejectStaticOrdinaryInitHelper() throws IOException {
        var host = new LirClassDef("StaticInitHost", "Node");
        var init = newHiddenIntInit("StaticInitHost", "_field_init_flag", 1);
        init.setStatic(true);
        host.addFunction(init);
        host.addProperty(new LirPropertyDef(
                "flag", GdIntType.INT, false, "_field_init_flag", null, null, Map.of()));
        var codegen = newPreparedCodegen(new LirModule("static_init_helper_module", List.of(host)));

        var exception = assertThrows(IllegalStateException.class, codegen::generate);
        assertTrue(exception.getMessage().contains("_field_init_flag"), exception.getMessage());
        assertTrue(exception.getMessage().contains("non-static"), exception.getMessage());
    }

    @Test
    @DisplayName("onready source init shell should fail codegen before control-flow grafting")
    void generateShouldRejectShellOnlyOnreadySourceInit() throws IOException {
        var host = newOnreadyHostClass();
        host.removeFunction(functionNamed(host, "_field_init_onready_source"));
        var shell = new LirFunctionDef("_field_init_onready_source");
        shell.setHidden(true);
        shell.setReturnType(GdIntType.INT);
        shell.addParameter(new LirParameterDef("self", new GdObjectType("OnreadyHost"), null, shell));
        host.addFunction(shell);
        var module = new LirModule("onready_source_shell_module", List.of(host));
        var codegen = newPreparedCodegen(module);

        var exception = assertThrows(IllegalStateException.class, codegen::generate);
        assertTrue(exception.getMessage().contains("shell-only"), exception.getMessage());
    }
}
