package gd.script.gdcc.backend.c.gen;

import gd.script.gdcc.backend.CodegenContext;
import gd.script.gdcc.backend.ProjectInfo;
import gd.script.gdcc.enums.GodotVersion;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.lir.LirBasicBlock;
import gd.script.gdcc.lir.LirClassDef;
import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.lir.LirModule;
import gd.script.gdcc.lir.LirParameterDef;
import gd.script.gdcc.lir.LirPropertyDef;
import gd.script.gdcc.lir.insn.LiteralIntInsn;
import gd.script.gdcc.lir.insn.ReturnInsn;
import gd.script.gdcc.lir.parser.DomLirParser;
import gd.script.gdcc.lir.parser.DomLirSerializer;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdObjectType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

        // A second session over the same parsed module sees identical facts and synthesizes
        // nothing twice.
        var second = newPreparedCodegen(parsedModule);
        assertEquals(plan, second.onreadyPlan().classPlan(parsedHost.getName()));
        assertFalse(second.generate().isEmpty());
        assertEquals(functionCount, parsedHost.getFunctions().size());
        assertSame(synthesizedInit, functionNamed(parsedHost, "_field_init_onready_missing"));
    }

    @Test
    @DisplayName("onreadyPlan accessor should fail before prepare")
    void onreadyPlanAccessorShouldFailBeforePrepare() {
        var codegen = new CCodegen();
        assertThrows(IllegalStateException.class, codegen::onreadyPlan);
    }
}
