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
import java.util.ArrayList;
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
        return generatedFileText(files, "entry.c");
    }

    private static String generatedFileText(List<GeneratedFile> files, String filePath) {
        return files.stream()
                .filter(file -> file.filePath().equals(filePath))
                .findFirst()
                .map(file -> new String(file.contentWriter()))
                .orElseThrow(() -> new AssertionError("Missing generated " + filePath));
    }

    /// Extracts a generated function definition body by signature prefix, skipping prototypes.
    private static String functionBodyByPrefix(String code, String signaturePrefix) {
        var fromIndex = 0;
        var signatureIndex = -1;
        while ((signatureIndex = code.indexOf(signaturePrefix, fromIndex)) >= 0) {
            var openBraceIndex = code.indexOf('{', signatureIndex);
            var semicolonIndex = code.indexOf(';', signatureIndex);
            if (semicolonIndex < 0 || semicolonIndex > openBraceIndex) {
                break;
            }
            fromIndex = semicolonIndex + 1;
        }
        assertTrue(signatureIndex >= 0, "Missing function definition: " + signaturePrefix);
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

    private static void assertContainsAll(String text, String... fragments) {
        for (var fragment : fragments) {
            assertTrue(text.contains(fragment), () -> "Missing fragment: " + fragment + "\n" + text);
        }
    }

    /// Every fragment must appear strictly after the previous one (ordered containment).
    private static void assertOrderedIn(String text, String... fragmentsInOrder) {
        var searchFromIndex = 0;
        for (var fragment : fragmentsInOrder) {
            var index = text.indexOf(fragment, searchFromIndex);
            assertTrue(index >= 0, () -> "Missing fragment: " + fragment + "\n" + text);
            searchFromIndex = index + fragment.length();
        }
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
        // The chain body keeps the frozen member even though the live annotation was removed.
        var chainBody = functionBodyByPrefix(cCode, "static void OnreadyHost_class_apply_onready_inits");
        assertTrue(chainBody.contains("OnreadyHost_class_apply_onready_init_onready_source(self);"), chainBody);
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
        // Constructor, init_fields, both apply wrappers, the hidden default helper body and
        // the chain/adapter definitions all stay byte-identical across sessions.
        for (var prefix : List.of(
                "void OnreadyHost_class_constructor",
                "void OnreadyHost_class_init_fields",
                "static inline void OnreadyHost_class_apply_property_default_onready_source",
                "static inline void OnreadyHost_class_apply_onready_init_onready_source",
                "godot_int OnreadyHost__onready_field_default_onready_source(",
                "static void OnreadyHost_class_apply_onready_inits",
                "static void OnreadyHost__gdcc_onready_apply_root")) {
            assertEquals(functionBodyByPrefix(firstC, prefix), functionBodyByPrefix(secondC, prefix), prefix);
        }
        // The ineligible sibling keeps the ordinary route in the second session too.
        assertTrue(secondC.contains("OnreadyHost_class_apply_property_init_onready_missing(self);"), secondC);
        assertFalse(secondC.contains("OnreadyHost_class_apply_property_default_onready_missing"), secondC);
        assertFalse(secondC.contains("OnreadyHost_class_apply_onready_init_onready_missing"), secondC);
        assertFalse(secondC.contains("_onready_field_default_onready_missing"), secondC);
        // Prototypes stay unique in the second session as well.
        for (var proto : List.of(
                "static void OnreadyHost_class_apply_onready_inits(OnreadyHost* self);",
                "static inline void OnreadyHost_class_apply_onready_init_onready_source(OnreadyHost* self);",
                "static void OnreadyHost__gdcc_onready_apply_root(OnreadyHost* root);",
                "static void OnreadyHost__gdcc_onready_ready(OnreadyHost* self);")) {
            assertEquals(1, countOccurrences(secondC, proto), proto);
        }
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

    /// A -> B -> C chain fixture in derived-first module order: StepB is a pass-through
    /// middle class; clean-parent leaf and standalone covers the no-chain classes.
    private static LirModule newOnreadyChainModule() {
        var a = new LirClassDef("StepA", "Node");
        addEligibleIntProperty(a, "z_first", 1);
        a.addProperty(new LirPropertyDef("plain", GdIntType.INT, false, null, null, null, Map.of()));
        addEligibleIntProperty(a, "a_second", 2);
        a.addProperty(new LirPropertyDef("onready_missing", GdIntType.INT, false, null, null, null, Map.of("onready", "")));
        var b = new LirClassDef("StepB", "StepA");
        b.addProperty(new LirPropertyDef("plain_b", GdIntType.INT, false, null, null, null, Map.of()));
        var c = new LirClassDef("StepC", "StepB");
        addEligibleIntProperty(c, "c_first", 3);
        addEligibleIntProperty(c, "c_second", 4);
        var cleanParent = new LirClassDef("CleanParent", "Node");
        cleanParent.addProperty(new LirPropertyDef("cp_plain", GdIntType.INT, false, null, null, null, Map.of()));
        var leafUnderClean = new LirClassDef("LeafUnderClean", "CleanParent");
        addEligibleIntProperty(leafUnderClean, "luc", 5);
        var standalone = new LirClassDef("StepStandalone", "Node");
        standalone.addProperty(new LirPropertyDef("sflag", GdIntType.INT, true, null, null, null, Map.of()));
        standalone.addProperty(new LirPropertyDef("sd", GdIntType.INT, false, null, null, null, Map.of()));
        standalone.addProperty(new LirPropertyDef("sd_no", GdIntType.INT, false, null, null, null, Map.of("onready", "")));
        return new LirModule("onready_chain_module",
                List.of(c, b, a, leafUnderClean, cleanParent, standalone));
    }

    /// Adds `name` as a source-initializer onready INT property plus its hidden init helper.
    private static void addEligibleIntProperty(LirClassDef owner, String name, int value) {
        owner.addFunction(newHiddenIntInit(owner.getName(), "_field_init_" + name, value));
        owner.addProperty(new LirPropertyDef(
                name, GdIntType.INT, false, "_field_init_" + name, null, null, Map.of("onready", "")));
    }

    private static LirClassDef classNamed(LirModule module, String name) {
        return module.getClassDefs().stream()
                .filter(classDef -> classDef.getName().equals(name)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("onready chain prototypes should be emitted once in module order before every definition")
    void generateShouldEmitOnreadyChainPrototypesBeforeDefinitions() throws IOException {
        var module = newOnreadyChainModule();
        var codegen = newPreparedCodegen(module);
        var cCode = generatedEntryC(codegen.generate());

        var firstDefinition = cCode.indexOf("GDE_EXPORT");
        assertTrue(firstDefinition > 0);
        // Module order [StepC, StepB, StepA, LeafUnderClean]: the prototype pass follows it.
        var expectedProtos = List.of(
                "static void StepC_class_apply_onready_inits(StepC* self);",
                "static inline void StepC_class_apply_onready_init_c_first(StepC* self);",
                "static inline void StepC_class_apply_onready_init_c_second(StepC* self);",
                "static void StepC__gdcc_onready_apply_root(StepA* root);",
                "static void StepC__gdcc_onready_ready(StepC* self);",
                "static void StepB_class_apply_onready_inits(StepB* self);",
                "static void StepB__gdcc_onready_apply_root(StepA* root);",
                "static void StepB__gdcc_onready_ready(StepB* self);",
                "static void StepA_class_apply_onready_inits(StepA* self);",
                "static inline void StepA_class_apply_onready_init_z_first(StepA* self);",
                "static inline void StepA_class_apply_onready_init_a_second(StepA* self);",
                "static void StepA__gdcc_onready_apply_root(StepA* root);",
                "static void StepA__gdcc_onready_ready(StepA* self);",
                "static void LeafUnderClean_class_apply_onready_inits(LeafUnderClean* self);",
                "static inline void LeafUnderClean_class_apply_onready_init_luc(LeafUnderClean* self);",
                "static void LeafUnderClean__gdcc_onready_apply_root(CleanParent* root);",
                "static void LeafUnderClean__gdcc_onready_ready(LeafUnderClean* self);");
        var previousIndex = -1;
        for (var proto : expectedProtos) {
            assertEquals(1, countOccurrences(cCode, proto), proto);
            var index = cCode.indexOf(proto);
            assertTrue(index >= 0 && index < firstDefinition, proto);
            assertTrue(index > previousIndex, "module order violated at " + proto);
            previousIndex = index;
            // The signature without `;` occurs once in the prototype and once in the
            // definition.
            var signature = proto.substring(0, proto.length() - 1);
            assertEquals(2, countOccurrences(cCode, signature), signature);
        }
        // No prototypes for ineligible properties or classes without frozen chain members.
        for (var absent : List.of(
                "StepA_class_apply_onready_init_onready_missing",
                "StepA_class_apply_onready_init_plain",
                "StepB_class_apply_onready_init_",
                "CleanParent_class_apply_onready_inits",
                "CleanParent__gdcc_onready_apply_root",
                "CleanParent__gdcc_onready_ready",
                "CleanParent__gdcc_onready_class_name",
                "StepStandalone_class_apply_onready_inits",
                "StepStandalone__gdcc_onready")) {
            assertFalse(cCode.contains(absent), absent);
        }
    }

    @Test
    @DisplayName("onready chain functions should apply GDCC ancestors first then own frozen list")
    void generateShouldEmitOnreadyChainFunctionsInParentBeforeOwnOrder() throws IOException {
        var module = newOnreadyChainModule();
        var codegen = newPreparedCodegen(module);
        var cCode = generatedEntryC(codegen.generate());

        // Full statement equality pins each chain body to exactly its expected calls —
        // parent-first ordering, declaration order, no flattened calls, nothing extra.
        assertEquals(List.of(
                        "StepA_class_apply_onready_init_z_first(self);",
                        "StepA_class_apply_onready_init_a_second(self);"),
                bodyStatements(functionBodyByPrefix(cCode, "static void StepA_class_apply_onready_inits")));
        assertEquals(List.of(
                        "StepA_class_apply_onready_inits(&self->_super);"),
                bodyStatements(functionBodyByPrefix(cCode, "static void StepB_class_apply_onready_inits")));
        assertEquals(List.of(
                        "StepB_class_apply_onready_inits(&self->_super);",
                        "StepC_class_apply_onready_init_c_first(self);",
                        "StepC_class_apply_onready_init_c_second(self);"),
                bodyStatements(functionBodyByPrefix(cCode, "static void StepC_class_apply_onready_inits")));
        assertEquals(List.of(
                        "LeafUnderClean_class_apply_onready_init_luc(self);"),
                bodyStatements(functionBodyByPrefix(cCode,
                        "static void LeafUnderClean_class_apply_onready_inits")));
    }

    /// Non-blank trimmed lines of an extracted function body, for exact statement equality.
    private static List<String> bodyStatements(String body) {
        return body.lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
    }

    @Test
    @DisplayName("onready root adapters should take the physical root type and offset-cast to the chain class")
    void generateShouldEmitOnreadyRootAdapters() throws IOException {
        var module = newOnreadyChainModule();
        var codegen = newPreparedCodegen(module);
        var cCode = generatedEntryC(codegen.generate());

        for (var cls : List.of("StepA", "StepB", "StepC")) {
            assertEquals(1,
                    countOccurrences(cCode, cls + "__gdcc_onready_apply_root(StepA* root)\n{"), cls);
            var body = functionBodyByPrefix(cCode, "static void " + cls + "__gdcc_onready_apply_root(StepA* root)");
            assertTrue(body.contains(cls + "_class_apply_onready_inits((" + cls + "*)root);"), body);
            assertFalse(body.contains("(*)("), body);
        }
        // The physical root under a clean GDCC parent is the parent itself, not the chain owner.
        var leafBody = functionBodyByPrefix(cCode,
                "static void LeafUnderClean__gdcc_onready_apply_root(CleanParent* root)");
        assertTrue(leafBody.contains(
                "LeafUnderClean_class_apply_onready_inits((LeafUnderClean*)root);"), leafBody);
    }

    @Test
    @DisplayName("root wrapper carries only the flags byte; create/recreate/notification wire the lifecycle")
    void generateShouldEmitRootLifecycleLayoutAndWrites() throws IOException {
        // A polymorphic override forces `_vtable` into the root segment so the frozen
        // field order after `_object` is pinned with the slot field present as well.
        var vtBase = new LirClassDef("FlagsVtBase", "Node");
        addEligibleIntProperty(vtBase, "v", 9);
        vtBase.addFunction(newVoidMethod("FlagsVtBase", "probe"));
        var vtLeaf = new LirClassDef("FlagsVtLeaf", "FlagsVtBase");
        vtLeaf.addFunction(newVoidMethod("FlagsVtLeaf", "probe"));
        var vtFiles = newPreparedCodegen(
                new LirModule("onready_flags_layout_module", List.of(vtLeaf, vtBase))).generate();
        var vtHeader = generatedFileText(vtFiles, "entry.h");

        var vtRootStruct = functionBodyByPrefix(vtHeader, "struct FlagsVtBase {");
        assertOrderedIn(vtRootStruct,
                "GDExtensionObjectPtr _object;",
                "const void* _vtable;",
                "uint8_t _gdcc_flags;");
        assertFalse(vtRootStruct.contains("_gdcc_onready_apply"), vtRootStruct);

        var files = newPreparedCodegen(newOnreadyChainModule()).generate();
        var hCode = generatedFileText(files, "entry.h");
        var cCode = generatedEntryC(files);

        assertContainsAll(hCode,
                "enum { GDCC_FLAG_ONREADY_DONE = 2, GDCC_FLAG_CTOR_COMPLETE = 4, GDCC_FLAG_HAS_ONREADY = 8 };");

        // Chain-free root: no `_vtable`, just the shared flags byte — the instance
        // apply pointer is gone entirely; class dispatch lives in the lookup table.
        var cleanStruct = functionBodyByPrefix(hCode, "struct CleanParent {");
        assertOrderedIn(cleanStruct,
                "GDExtensionObjectPtr _object;",
                "uint8_t _gdcc_flags;");
        assertFalse(cleanStruct.contains("_vtable"), cleanStruct);
        var stepBStruct = functionBodyByPrefix(hCode, "struct StepB {");
        assertFalse(stepBStruct.contains("_gdcc_flags"), stepBStruct);
        // Field-access spelling only — the `__gdcc_onready_apply_root` adapter
        // symbols keep their longer name on purpose.
        assertFalse(hCode.contains("(*_gdcc_onready_apply)"), hCode);
        assertFalse(cCode.contains("._gdcc_onready_apply"), cCode);

        // create: HAS_ONREADY only when THIS class's frozen chain fact is true,
        // before attach/binding/POSTINITIALIZE.
        var stepCCreate = functionBodyByPrefix(cCode, "GDExtensionObjectPtr StepC_class_create_instance(");
        assertOrderedIn(stepCCreate,
                "self->_super._super._gdcc_flags = GDCC_FLAG_HAS_ONREADY;",
                "godot_object_set_instance(",
                "godot_object_set_instance_binding(",
                "godot_Object_notification(obj, godot_Object_NOTIFICATION_POSTINITIALIZE()");
        assertFalse(stepCCreate.contains("StepC_class_apply_onready_inits"), stepCCreate);

        // The chain-fact middle class (no own initializers) still sets HAS — its row
        // in the root's table applies the full inherited chain.
        var stepBCreate = functionBodyByPrefix(cCode, "GDExtensionObjectPtr StepB_class_create_instance(");
        assertOrderedIn(stepBCreate,
                "self->_super._gdcc_flags = GDCC_FLAG_HAS_ONREADY;");
        var cleanCreate = functionBodyByPrefix(cCode, "GDExtensionObjectPtr CleanParent_class_create_instance(");
        assertContainsAll(cleanCreate,
                "self->_gdcc_flags = 0;");

        // recreate: fresh wrapper storage — construction counts as complete and the
        // "ready already happened" fact carries over; HAS is recomputed from this
        // generation's chain fact before init_fields replays.
        var stepCRecreate = functionBodyByPrefix(cCode,
                "GDExtensionClassInstancePtr StepC_class_recreate_instance(");
        assertOrderedIn(stepCRecreate,
                "self->_super._super._gdcc_flags = GDCC_FLAG_CTOR_COMPLETE | GDCC_FLAG_ONREADY_DONE | GDCC_FLAG_HAS_ONREADY;",
                "godot_object_set_instance_binding(",
                "StepC_class_init_fields(self);");
        var cleanRecreate = functionBodyByPrefix(cCode,
                "GDExtensionClassInstancePtr CleanParent_class_recreate_instance(");
        assertOrderedIn(cleanRecreate,
                "self->_gdcc_flags = GDCC_FLAG_CTOR_COMPLETE | GDCC_FLAG_ONREADY_DONE;");

        // notification: CTOR_COMPLETE flips only AFTER the whole constructor chain ran.
        var stepCNotification = functionBodyByPrefix(cCode, "void StepC_class_notification(");
        assertOrderedIn(stepCNotification,
                "StepC_class_constructor(self);",
                "self->_super._super._gdcc_flags |= GDCC_FLAG_CTOR_COMPLETE;");
    }

    /// Minimal instance method for fixtures that need a polymorphic override pair.
    private static LirFunctionDef newVoidMethod(String ownerClassName, String name) {
        var func = new LirFunctionDef(name);
        func.setReturnType(GdVoidType.VOID);
        func.addParameter(new LirParameterDef("self", new GdObjectType(ownerClassName), null, func));
        var entry = new LirBasicBlock("entry");
        entry.setTerminator(new ReturnInsn(null));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        return func;
    }

    @Test
    @DisplayName("composite ready impl should run guard/apply/forward in order and dispatch by address")
    void generateShouldEmitCompositeReadyImplAndDispatch() throws IOException {
        var module = newOnreadyChainModule();
        // A user `_ready` on StepA: the whole three-level chain forwards to the owner
        // symbol through the offset-0 owner cast.
        classNamed(module, "StepA").addFunction(newVoidMethod("StepA", "_ready"));
        var codegen = newPreparedCodegen(module);
        var cCode = generatedEntryC(codegen.generate());

        for (var cls : List.of("StepA", "StepB", "StepC", "LeafUnderClean")) {
            assertEquals(1,
                    countOccurrences(cCode, "static void " + cls + "__gdcc_onready_ready(" + cls + "* self);"), cls);
            assertEquals(1,
                    countOccurrences(cCode, "static void " + cls + "__gdcc_onready_ready(" + cls + "* self)\n{"), cls);
        }

        // `_ready` resolution stays chain-based: StepC's owner is StepA, and no chain body
        // invokes `_ready` itself (init chain / user ready stay separate).
        assertEquals("StepA", codegen.onreadyPlan().classPlan("StepC").userReadyOwnerClassName());
        var cChainBody = functionBodyByPrefix(cCode, "static void StepC_class_apply_onready_inits");
        assertFalse(cChainBody.contains("_ready("), cChainBody);
        assertNull(codegen.onreadyPlan().classPlan("CleanParent").userReadyFunction());

        // Three-level owner cast: StepC's impl forwards to `StepA__ready` through the
        // offset-0 `(StepA*)self` conversion, in both the guard path and the tail.
        var stepCImpl = functionBodyByPrefix(cCode, "static void StepC__gdcc_onready_ready(StepC* self)");
        assertOrderedIn(stepCImpl,
                "(self->_super._super._gdcc_flags & GDCC_FLAG_CTOR_COMPLETE) == 0",
                "StepA__ready(gdcc_StepA_fat_ptr_from_raw(StepA_object_ptr((StepA*)self)));",
                "return;",
                "self->_super._super._gdcc_flags |= GDCC_FLAG_ONREADY_DONE;",
                "StepC_class_apply_onready_inits(self);",
                "StepA__ready(gdcc_StepA_fat_ptr_from_raw(StepA_object_ptr((StepA*)self)));");

        // Own-most-derived owner: StepA's impl forwards without a cast.
        var stepAImpl = functionBodyByPrefix(cCode, "static void StepA__gdcc_onready_ready(StepA* self)");
        assertOrderedIn(stepAImpl,
                "(self->_gdcc_flags & GDCC_FLAG_CTOR_COMPLETE) == 0",
                "StepA__ready(gdcc_StepA_fat_ptr_from_raw(StepA_object_ptr(self)));",
                "return;",
                "self->_gdcc_flags |= GDCC_FLAG_ONREADY_DONE;",
                "StepA_class_apply_onready_inits(self);",
                "StepA__ready(gdcc_StepA_fat_ptr_from_raw(StepA_object_ptr(self)));");
        assertFalse(stepAImpl.contains("(StepA*)self"), stepAImpl);

        // The chain call itself never reads DONE (request_ready replay must overwrite).
        assertEquals(1, countOccurrences(stepCImpl, "GDCC_FLAG_ONREADY_DONE"), stepCImpl);
        assertEquals(1, countOccurrences(stepCImpl, "GDCC_FLAG_CTOR_COMPLETE"), stepCImpl);
    }

    @Test
    @DisplayName("composite impl should forward to the class's own most-derived `_ready`")
    void generateShouldForwardCompositeReadyToMostDerivedUserReady() throws IOException {
        var module = newOnreadyChainModule();
        classNamed(module, "StepA").addFunction(newVoidMethod("StepA", "_ready"));
        classNamed(module, "StepC").addFunction(newVoidMethod("StepC", "_ready"));
        var codegen = newPreparedCodegen(module);
        var cCode = generatedEntryC(codegen.generate());

        // StepC overrides `_ready`: its composite impl forwards to StepC's own symbol
        // without a cast; declarationless StepB still forwards to chain ancestor StepA.
        assertEquals("StepC", codegen.onreadyPlan().classPlan("StepC").userReadyOwnerClassName());
        assertEquals("StepA", codegen.onreadyPlan().classPlan("StepB").userReadyOwnerClassName());
        var stepCImpl = functionBodyByPrefix(cCode, "static void StepC__gdcc_onready_ready(StepC* self)");
        assertTrue(stepCImpl.contains("StepC__ready(gdcc_StepC_fat_ptr_from_raw(StepC_object_ptr(self)));"), stepCImpl);
        assertFalse(stepCImpl.contains("StepA__ready("), stepCImpl);
        var stepBImpl = functionBodyByPrefix(cCode, "static void StepB__gdcc_onready_ready(StepB* self)");
        assertTrue(stepBImpl.contains("StepA__ready(gdcc_StepA_fat_ptr_from_raw(StepA_object_ptr((StepA*)self)));"), stepBImpl);
    }

    @Test
    @DisplayName("composite ready without a user `_ready` should still apply the chain")
    void generateShouldEmitCompositeReadyImplWithoutUserReady() throws IOException {
        var cCode = generatedEntryC(newPreparedCodegen(newOnreadyChainModule()).generate());

        var stepCImpl = functionBodyByPrefix(cCode, "static void StepC__gdcc_onready_ready(StepC* self)");
        assertOrderedIn(stepCImpl,
                "(self->_super._super._gdcc_flags & GDCC_FLAG_CTOR_COMPLETE) == 0",
                "return;",
                "self->_super._super._gdcc_flags |= GDCC_FLAG_ONREADY_DONE;",
                "StepC_class_apply_onready_inits(self);");
        // No user `_ready` anywhere on the chain: neither branch forwards.
        assertFalse(stepCImpl.contains("__ready("), stepCImpl);
    }

    @Test
    @DisplayName("chain classes should take over `_ready` dispatch; clean classes stay untouched")
    void generateShouldEmitCompositeReadyVirtualDispatch() throws IOException {
        var module = newOnreadyChainModule();
        classNamed(module, "StepA").addFunction(newVoidMethod("StepA", "_ready"));
        var cCode = generatedEntryC(newPreparedCodegen(module).generate());

        // get_virtual: the `_ready` special case returns the composite impl's real
        // address BEFORE any own-function match or the parent fallthrough.
        var stepAGetVirtual = functionBodyByPrefix(cCode, "void* StepA_class_get_virtual_with_data(");
        assertOrderedIn(stepAGetVirtual,
                "GD_STATIC_SN(u8\"_ready\")",
                "return (void*)&StepA__gdcc_onready_ready;",
                "return (void*)StepA__ready;");
        var stepCGetVirtual = functionBodyByPrefix(cCode, "void* StepC_class_get_virtual_with_data(");
        assertOrderedIn(stepCGetVirtual,
                "return (void*)&StepC__gdcc_onready_ready;",
                "return StepB_class_get_virtual_with_data(p_class_userdata, p_name, p_hash);");

        // call_virtual: the composite address is matched and dispatched directly —
        // before the own loop and the parent fallthrough, never through ptrcall.
        var stepCCallVirtual = functionBodyByPrefix(cCode, "void StepC_class_call_virtual_with_data(");
        assertOrderedIn(stepCCallVirtual,
                "p_virtual_call_userdata == &StepC__gdcc_onready_ready",
                "StepC__gdcc_onready_ready((StepC*)p_instance);",
                "(void)p_args;",
                "(void)r_ret;",
                "return;",
                "StepB_class_call_virtual_with_data(");
        var stepACallVirtual = functionBodyByPrefix(cCode, "void StepA_class_call_virtual_with_data(");
        assertOrderedIn(stepACallVirtual,
                "p_virtual_call_userdata == &StepA__gdcc_onready_ready",
                "StepA__gdcc_onready_ready((StepA*)p_instance);",
                "return;",
                "p_virtual_call_userdata == &StepA__ready");

        // Chain-free classes: no `_ready` special case and no composite reference —
        // the inherited normal fallback stays the only exit.
        for (var cls : List.of("CleanParent", "StepStandalone")) {
            var getVirtual = functionBodyByPrefix(cCode, "void* " + cls + "_class_get_virtual_with_data(");
            assertFalse(getVirtual.contains("__gdcc_onready_ready"), getVirtual);
            assertTrue(getVirtual.contains("return NULL;"), getVirtual);
            var callVirtual = functionBodyByPrefix(cCode, "void " + cls + "_class_call_virtual_with_data(");
            assertFalse(callVirtual.contains("__gdcc_onready_ready"), callVirtual);
        }
        // A non-`_ready` virtual lookup on a chain class still falls through to the
        // parent chain unchanged.
        assertTrue(stepCGetVirtual.contains("StepB_class_get_virtual_with_data"), stepCGetVirtual);
    }

    @Test
    @DisplayName("composite ready should forward a coroutine `_ready` to the engine shell, not the thunk")
    void generateShouldForwardCoroutineReadyToShell() throws IOException {
        var module = newOnreadyChainModule();
        var coroReady = newVoidMethod("StepA", "_ready");
        coroReady.setCoroutine(true);
        classNamed(module, "StepA").addFunction(coroReady);
        var cCode = generatedEntryC(newPreparedCodegen(module).generate());

        // The composite impl calls the synchronous engine entry `StepA__ready` (the shell
        // detaches/releases the coroutine state internally) — never the start thunk or body.
        var stepCImpl = functionBodyByPrefix(cCode, "static void StepC__gdcc_onready_ready(StepC* self)");
        assertTrue(stepCImpl.contains("StepA__ready(gdcc_StepA_fat_ptr_from_raw(StepA_object_ptr((StepA*)self)));"),
                stepCImpl);
        assertFalse(stepCImpl.contains("__coro_start"), stepCImpl);
        assertFalse(stepCImpl.contains("__coro_body"), stepCImpl);
    }

    /// `_ready` declared on the base while only the descendant carries onready —
    /// the `_ready` entry-initialization "descendant fact" scenario.
    private static LirModule newDescendantOnreadyReadyModule() {
        var base = new LirClassDef("ReadyBase", "Node");
        base.addFunction(newVoidMethod("ReadyBase", "_ready"));
        var leaf = new LirClassDef("ReadyLeaf", "ReadyBase");
        addEligibleIntProperty(leaf, "l", 7);
        return new LirModule("onready_descendant_ready_module", List.of(leaf, base));
    }

    @Test
    @DisplayName("sync `_ready` should run guarded onready entry initialization before `goto entry`")
    void generateShouldInitializeOnreadyAtSyncReadyEntry() throws IOException {
        var module = newOnreadyChainModule();
        classNamed(module, "StepA").addFunction(newVoidMethod("StepA", "_ready"));
        var cCode = generatedEntryC(newPreparedCodegen(module).generate());

        var readyBody = functionBodyByPrefix(cCode, "void StepA__ready(");
        // The entry initialization is a scoped block materializing the physical root
        // wrapper and delegating to the per-root initialize helper — flag gates and
        // the class-name lookup live inside it, before the `goto entry` user-code jump.
        assertOrderedIn(readyBody,
                "{\n",
                "StepA* __gdcc_onready_root = (StepA*)$self.ptr;",
                "StepA__gdcc_onready_initialize_if_needed(__gdcc_onready_root);",
                "goto entry;");
        assertEquals(1, countOccurrences(readyBody, "StepA* __gdcc_onready_root = "), readyBody);
    }

    @Test
    @DisplayName("descendant-chain fact should inject entry initialization into an inherited base `_ready`")
    void generateShouldInitializeOnreadyAtInheritedReadyEntry() throws IOException {
        var cCode = generatedEntryC(newPreparedCodegen(newDescendantOnreadyReadyModule()).generate());

        // ReadyBase declares `_ready` and has no onready itself — the descendant fact
        // (ReadyLeaf's chain) still injects the entry initialization, dispatching by instance type.
        var readyBody = functionBodyByPrefix(cCode, "void ReadyBase__ready(");
        assertOrderedIn(readyBody,
                "ReadyBase* __gdcc_onready_root = (ReadyBase*)$self.ptr;",
                "ReadyBase__gdcc_onready_initialize_if_needed(__gdcc_onready_root);",
                "goto entry;");
    }

    @Test
    @DisplayName("an abstract base `_ready` reachable via super should still run entry initialization")
    void generateShouldInitializeOnreadyAtAbstractBaseReadyEntry() throws IOException {
        var absBase = new LirClassDef("AbsBase", "Node");
        absBase.setAbstract(true);
        absBase.addFunction(newVoidMethod("AbsBase", "_ready"));
        var absLeaf = new LirClassDef("AbsLeaf", "AbsBase");
        addEligibleIntProperty(absLeaf, "al", 8);
        var cCode = generatedEntryC(newPreparedCodegen(
                new LirModule("onready_abstract_ready_module", List.of(absLeaf, absBase))).generate());

        var readyBody = functionBodyByPrefix(cCode, "void AbsBase__ready(");
        assertTrue(readyBody.contains("AbsBase* __gdcc_onready_root = (AbsBase*)$self.ptr;"), readyBody);
    }

    @Test
    @DisplayName("chain-free or malformed `_ready` functions should get no entry initialization")
    void generateShouldSkipOnreadyEntryInitializationWhenIneligible() throws IOException {
        var cleanModule = newOnreadyChainModule();
        classNamed(cleanModule, "StepStandalone").addFunction(newVoidMethod("StepStandalone", "_ready"));
        var cleanCode = generatedEntryC(newPreparedCodegen(cleanModule).generate());
        var cleanReady = functionBodyByPrefix(cleanCode, "void StepStandalone__ready(");
        assertFalse(cleanReady.contains("__gdcc_onready_root"), cleanReady);

        // Malformed variants — each on its own chain class (all carry own onready):
        // static / hidden / wrong signature must never receive the entry initialization, and an
        // ordinary method on a chain class is untouched.
        var excStatic = new LirClassDef("ExcStatic", "Node");
        addEligibleIntProperty(excStatic, "e", 1);
        var staticReady = newVoidMethod("ExcStatic", "_ready");
        staticReady.setStatic(true);
        excStatic.addFunction(staticReady);
        var excHidden = new LirClassDef("ExcHidden", "Node");
        addEligibleIntProperty(excHidden, "e", 1);
        var hiddenReady = newVoidMethod("ExcHidden", "_ready");
        hiddenReady.setHidden(true);
        excHidden.addFunction(hiddenReady);
        var excSig = new LirClassDef("ExcSig", "Node");
        addEligibleIntProperty(excSig, "e", 1);
        var wrongSigReady = newVoidMethod("ExcSig", "_ready");
        wrongSigReady.addParameter(new LirParameterDef("extra", GdIntType.INT, null, wrongSigReady));
        excSig.addFunction(wrongSigReady);
        var excName = new LirClassDef("ExcName", "Node");
        addEligibleIntProperty(excName, "e", 1);
        excName.addFunction(newVoidMethod("ExcName", "probe"));
        var excCode = generatedEntryC(newPreparedCodegen(new LirModule(
                "onready_excluded_ready_module",
                List.of(excStatic, excHidden, excSig, excName))).generate());

        assertFalse(functionBodyByPrefix(excCode, "void ExcStatic__ready(")
                .contains("__gdcc_onready_root"), excCode);
        assertFalse(functionBodyByPrefix(excCode, "void ExcHidden__ready(")
                .contains("__gdcc_onready_root"), excCode);
        assertFalse(functionBodyByPrefix(excCode, "void ExcSig__ready(")
                .contains("__gdcc_onready_root"), excCode);
        assertFalse(functionBodyByPrefix(excCode, "void ExcName_probe(")
                .contains("__gdcc_onready_root"), excCode);
    }

    @Test
    @DisplayName("coroutine `_ready` should run entry initialization only in its start thunk")
    void generateShouldInitializeOnreadyAtCoroutineStartThunkEntry() throws IOException {
        var module = newOnreadyChainModule();
        var coroReady = newVoidMethod("StepA", "_ready");
        coroReady.setCoroutine(true);
        classNamed(module, "StepA").addFunction(coroReady);
        var probe = newVoidMethod("StepA", "probe");
        probe.setCoroutine(true);
        classNamed(module, "StepA").addFunction(probe);
        var cCode = generatedEntryC(newPreparedCodegen(module).generate());

        // The thunk holds exactly one entry initialization, before the state allocation and any
        // resume — every internal entry (direct/vtable/super) lands here.
        var thunk = functionBodyByPrefix(cCode, "godot_Object* StepA__ready__coro_start(");
        assertEquals(1, countOccurrences(thunk,
                "StepA__gdcc_onready_initialize_if_needed(__gdcc_onready_root);"), thunk);
        assertOrderedIn(thunk,
                "StepA* __gdcc_onready_root = (StepA*)$self.ptr;",
                "StepA__gdcc_onready_initialize_if_needed(__gdcc_onready_root);",
                "_class_create_instance(",
                "mco_resume(");

        // Neither the coroutine body nor the sync shell may repeat it.
        var coroBody = functionBodyByPrefix(cCode, "void StepA__ready__coro_body(");
        assertFalse(coroBody.contains("__gdcc_onready_root"), coroBody);
        var shell = functionBodyByPrefix(cCode, "void StepA__ready(");
        assertFalse(shell.contains("__gdcc_onready_root"), shell);

        // An ordinary coroutine on the same class gets no entry initialization at all.
        for (var body : List.of(
                functionBodyByPrefix(cCode, "godot_Object* StepA_probe__coro_start("),
                functionBodyByPrefix(cCode, "void StepA_probe__coro_body("))) {
            assertFalse(body.contains("__gdcc_onready_root"), body);
            assertFalse(body.contains("StepA__gdcc_onready_initialize_if_needed("), body);
        }
    }

    @Test
    @DisplayName("non-empty roots should emit typed lookup tables with fixed class-name keys")
    void generateShouldEmitOnreadyLookupTablesPerRoot() throws IOException {
        var cCode = generatedEntryC(newPreparedCodegen(newOnreadyChainModule()).generate());

        // Root StepA: typedef, one fixed-literal getter per row, then the compile-time
        // table mapping each key getter to that class's existing root adapter.
        assertContainsAll(cCode,
                "typedef struct gdcc_StepA_onready_lookup_entry {",
                "const godot_StringName* (*class_name)(void);",
                "void (*apply)(StepA* root);",
                "} gdcc_StepA_onready_lookup_entry;",
                "static const godot_StringName* StepC__gdcc_onready_class_name(void)",
                "return GD_STATIC_SN(u8\"StepC\");",
                "static const godot_StringName* StepB__gdcc_onready_class_name(void)",
                "return GD_STATIC_SN(u8\"StepB\");",
                "static const godot_StringName* StepA__gdcc_onready_class_name(void)",
                "return GD_STATIC_SN(u8\"StepA\");",
                "static const gdcc_StepA_onready_lookup_entry gdcc_StepA_onready_lookup_table[] = {",
                "{ &StepC__gdcc_onready_class_name, &StepC__gdcc_onready_apply_root },",
                "{ &StepB__gdcc_onready_class_name, &StepB__gdcc_onready_apply_root },",
                "{ &StepA__gdcc_onready_class_name, &StepA__gdcc_onready_apply_root },",
                "static void StepA__gdcc_onready_initialize_if_needed(StepA* root)");

        // Second physical root: CleanParent's table holds only its onready leaf row.
        assertContainsAll(cCode,
                "void (*apply)(CleanParent* root);",
                "static const godot_StringName* LeafUnderClean__gdcc_onready_class_name(void)",
                "return GD_STATIC_SN(u8\"LeafUnderClean\");",
                "static const gdcc_CleanParent_onready_lookup_entry gdcc_CleanParent_onready_lookup_table[] = {",
                "{ &LeafUnderClean__gdcc_onready_class_name, &LeafUnderClean__gdcc_onready_apply_root },",
                "static void CleanParent__gdcc_onready_initialize_if_needed(CleanParent* root)");

        // Chain-free classes never emit lookup machinery: no getter, row, or helper.
        for (var absent : List.of(
                "CleanParent__gdcc_onready_class_name",
                "StepStandalone__gdcc_onready_class_name",
                "gdcc_StepStandalone_onready_lookup",
                "StepStandalone__gdcc_onready_initialize_if_needed")) {
            assertFalse(cCode.contains(absent), absent);
        }
        // Two plans total: exactly two typedefs, two tables, two helpers.
        assertEquals(2, countOccurrences(cCode, "_onready_lookup_entry gdcc_"), cCode);
        assertEquals(2, countOccurrences(cCode, "__gdcc_onready_initialize_if_needed("), cCode);
    }

    @Test
    @DisplayName("the root initialize helper should gate, query once, destroy the name, then apply")
    void generateShouldEmitOnreadyInitializeHelperFlow() throws IOException {
        var cCode = generatedEntryC(newPreparedCodegen(newOnreadyChainModule()).generate());
        var body = functionBodyByPrefix(cCode,
                "static void StepA__gdcc_onready_initialize_if_needed(StepA* root)");

        assertOrderedIn(body,
                "(root->_gdcc_flags & GDCC_FLAG_ONREADY_DONE) != 0",
                "(root->_gdcc_flags & GDCC_FLAG_CTOR_COMPLETE) == 0",
                "(root->_gdcc_flags & GDCC_FLAG_HAS_ONREADY) == 0",
                "root->_gdcc_flags |= GDCC_FLAG_ONREADY_DONE;",
                "GDExtensionObjectPtr raw = StepA_object_ptr(root);",
                "raw == NULL",
                "godot_object_get_class_name(raw, class_library, &actual_name)",
                "godot_StringName_op_equal_StringName(&actual_name, gdcc_StepA_onready_lookup_table[i].class_name())",
                "godot_StringName_destroy(&actual_name);",
                "apply(root);");
        // One query per attempt; the temporary is destroyed before apply can reach
        // user-adjacent code.
        assertEquals(1, countOccurrences(body, "godot_object_get_class_name"), body);
        assertEquals(1, countOccurrences(body, "godot_StringName_destroy(&actual_name);"), body);
    }

    @Test
    @DisplayName("engine composite impls should never consult the class lookup")
    void generateShouldKeepEngineCompositeLookupFree() throws IOException {
        var cCode = generatedEntryC(newPreparedCodegen(newOnreadyChainModule()).generate());
        for (var cls : List.of("StepA", "StepB", "StepC", "LeafUnderClean")) {
            var impl = functionBodyByPrefix(cCode,
                    "static void " + cls + "__gdcc_onready_ready(" + cls + "* self)");
            assertFalse(impl.contains("lookup_table"), impl);
            assertFalse(impl.contains("initialize_if_needed"), impl);
            assertFalse(impl.contains("get_class_name"), impl);
            var chain = functionBodyByPrefix(cCode,
                    "static void " + cls + "_class_apply_onready_inits");
            assertFalse(chain.contains("lookup_table"), chain);
            assertFalse(chain.contains("get_class_name"), chain);
        }
    }

    @Test
    @DisplayName("user functions colliding with gdcc_-prefixed lookup symbols should fail validation")
    void generateShouldRejectLookupTableSymbolCollisions() throws IOException {
        // A class literally named `gdcc` makes user functions spell the `gdcc_<R>_*`
        // lookup type/table symbols.
        for (var funcName : List.of("StepA_onready_lookup_table", "StepA_onready_lookup_entry")) {
            var classes = new ArrayList<>(newOnreadyChainModule().getClassDefs());
            var gdccClass = new LirClassDef("gdcc", "Node");
            gdccClass.addFunction(newHiddenIntInit("gdcc", funcName, 9));
            classes.add(gdccClass);
            var codegen = newPreparedCodegen(new LirModule("onready_lookup_collision_module", classes));

            var exception = assertThrows(IllegalStateException.class, codegen::generate);
            assertTrue(exception.getMessage().contains("file-scope symbol conflict"), exception.getMessage());
            assertTrue(exception.getMessage().contains("gdcc_" + funcName), exception.getMessage());
        }
    }

    /// Class canonical names equal to generated lookup symbols: the class's own wrapper
    /// typedef is an ordinary file-scope identifier and must collide with the emitted
    /// lookup typedef/table/getter/helper instead of reaching the C compiler.
    private static Stream<Arguments> lookupSymbolClassNameCollisions() {
        return Stream.of(
                Arguments.of("gdcc_Foo_onready_lookup_entry"),
                Arguments.of("gdcc_Foo_onready_lookup_table"),
                Arguments.of("Foo__gdcc_onready_class_name"),
                Arguments.of("Foo__gdcc_onready_initialize_if_needed")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("lookupSymbolClassNameCollisions")
    @DisplayName("a class named like a generated lookup symbol should collide with its wrapper typedef")
    void generateShouldRejectClassNamedLikeLookupSymbol(String className) throws IOException {
        var foo = new LirClassDef("Foo", "Node");
        foo.addFunction(newHiddenIntInit("Foo", "_field_init_v", 1));
        foo.addProperty(new LirPropertyDef(
                "v", GdIntType.INT, false, "_field_init_v", null, null, Map.of("onready", "")));
        var clash = new LirClassDef(className, "Node");
        var codegen = newPreparedCodegen(new LirModule("lookup_name_collision_module", List.of(foo, clash)));

        var exception = assertThrows(IllegalStateException.class, codegen::generate);
        assertTrue(exception.getMessage().contains("file-scope symbol conflict"), exception.getMessage());
        assertTrue(exception.getMessage().contains(className), exception.getMessage());
        // Both emission sources must be named in the diagnostic.
        assertTrue(exception.getMessage().contains("wrapper typedef of '" + className + "'"), exception.getMessage());
        assertTrue(exception.getMessage().contains("onready"), exception.getMessage());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("lookupSymbolClassNameCollisions")
    @DisplayName("lookup symbol names stay available to user classes when the root has no onready chain")
    void generateShouldAllowClassNamedLikeLookupSymbolOnCleanRoot(String className) throws IOException {
        var foo = new LirClassDef("Foo", "Node");
        var clash = new LirClassDef(className, "Node");
        var codegen = newPreparedCodegen(new LirModule("lookup_name_clean_module", List.of(foo, clash)));

        // No chain fact -> no lookup machinery -> the names remain available.
        var files = codegen.generate();
        assertFalse(files.isEmpty());
    }

    private static Stream<Arguments> lifecycleFlagConstantNames() {
        return Stream.of(
                Arguments.of("ONREADY_DONE"),
                Arguments.of("CTOR_COMPLETE"),
                Arguments.of("HAS_ONREADY")
        );
    }

    @ParameterizedTest(name = "GDCC_FLAG_{0}")
    @MethodSource("lifecycleFlagConstantNames")
    @DisplayName("user functions colliding with lifecycle flag constants should fail file-scope validation")
    void generateShouldRejectFunctionCollidingWithLifecycleFlagConstant(String methodName) throws IOException {
        var flag = new LirClassDef("GDCC_FLAG", "Node");
        flag.addFunction(newHiddenIntInit("GDCC_FLAG", methodName, 9));
        var codegen = newPreparedCodegen(new LirModule("flag_collision_module", List.of(flag)));

        var exception = assertThrows(IllegalStateException.class, codegen::generate);
        assertTrue(exception.getMessage().contains("file-scope symbol conflict"), exception.getMessage());
        assertTrue(exception.getMessage().contains("GDCC_FLAG_" + methodName), exception.getMessage());
        assertTrue(exception.getMessage().contains("lifecycle flag constant"), exception.getMessage());
        assertTrue(exception.getMessage().contains("function 'GDCC_FLAG." + methodName + "'"), exception.getMessage());
    }

    @Test
    @DisplayName("a class named like a lifecycle flag constant should collide with its wrapper typedef")
    void generateShouldRejectClassNamedLikeLifecycleFlagConstant() throws IOException {
        var clash = new LirClassDef("GDCC_FLAG_HAS_ONREADY", "Node");
        var codegen = newPreparedCodegen(new LirModule("flag_name_collision_module", List.of(clash)));

        var exception = assertThrows(IllegalStateException.class, codegen::generate);
        assertTrue(exception.getMessage().contains("GDCC_FLAG_HAS_ONREADY"), exception.getMessage());
        assertTrue(exception.getMessage().contains("wrapper typedef of 'GDCC_FLAG_HAS_ONREADY'"), exception.getMessage());
        assertTrue(exception.getMessage().contains("lifecycle flag constant"), exception.getMessage());
    }

    @Test
    @DisplayName("flag-named instance properties stay legal: enum constants cannot rewrite member names")
    void generateShouldAllowLifecycleFlagNamedInstanceProperties() throws IOException {
        var host = new LirClassDef("FlagProps", "Node");
        for (var name : List.of("GDCC_FLAG_ONREADY_DONE", "GDCC_FLAG_CTOR_COMPLETE", "GDCC_FLAG_HAS_ONREADY")) {
            host.addProperty(new LirPropertyDef(name, GdIntType.INT, false, null, null, null, Map.of()));
        }
        var codegen = newPreparedCodegen(new LirModule("flag_props_module", List.of(host)));

        var files = codegen.generate();
        var hCode = generatedFileText(files, "entry.h");
        assertContainsAll(hCode,
                "enum { GDCC_FLAG_ONREADY_DONE = 2, GDCC_FLAG_CTOR_COMPLETE = 4, GDCC_FLAG_HAS_ONREADY = 8 };",
                "godot_int GDCC_FLAG_ONREADY_DONE;",
                "godot_int GDCC_FLAG_CTOR_COMPLETE;",
                "godot_int GDCC_FLAG_HAS_ONREADY;");
    }

    private static Stream<Arguments> onreadyChainCollidingFunctionNames() {
        return Stream.of(
                Arguments.of("class_apply_onready_inits"),
                Arguments.of("_gdcc_onready_apply_root"),
                Arguments.of("_gdcc_onready_ready"),
                Arguments.of("_gdcc_onready_class_name"),
                Arguments.of("_gdcc_onready_initialize_if_needed")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("onreadyChainCollidingFunctionNames")
    @DisplayName("user functions colliding with onready chain symbols should fail file-scope validation")
    void generateShouldRejectUserFunctionCollidingWithOnreadyChainSymbol(String funcName) throws IOException {
        var module = newOnreadyChainModule();
        classNamed(module, "StepA").addFunction(newHiddenIntInit("StepA", funcName, 9));
        var codegen = newPreparedCodegen(module);

        var exception = assertThrows(IllegalStateException.class, codegen::generate);
        assertTrue(exception.getMessage().contains("file-scope symbol conflict"), exception.getMessage());
        assertTrue(exception.getMessage().contains("StepA_" + funcName), exception.getMessage());
        if (funcName.equals("_gdcc_onready_ready")) {
            assertTrue(exception.getMessage().contains("onready composite ready helper of 'StepA'"), exception.getMessage());
            assertTrue(exception.getMessage().contains("function 'StepA._gdcc_onready_ready'"), exception.getMessage());
        }
    }

    @Test
    @DisplayName("onready chain symbol names stay available to user functions on classes without a chain")
    void generateShouldAllowOnreadyChainNamesOnCleanClasses() throws IOException {
        var host = new LirClassDef("CleanHost", "Node");
        host.addFunction(newHiddenIntInit("CleanHost", "_gdcc_onready_ready", 7));
        host.addFunction(newHiddenIntInit("CleanHost", "_gdcc_onready_apply_root", 8));
        host.addFunction(newHiddenIntInit("CleanHost", "class_apply_onready_inits", 9));
        host.addProperty(new LirPropertyDef("p", GdIntType.INT, false, null, null, null, Map.of()));
        var codegen = newPreparedCodegen(new LirModule("clean_chain_names_module", List.of(host)));

        var cCode = generatedEntryC(codegen.generate());
        assertTrue(cCode.contains("CleanHost__gdcc_onready_ready("), cCode);
        assertTrue(cCode.contains("CleanHost__gdcc_onready_apply_root("), cCode);
        assertTrue(cCode.contains("CleanHost_class_apply_onready_inits("), cCode);
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
