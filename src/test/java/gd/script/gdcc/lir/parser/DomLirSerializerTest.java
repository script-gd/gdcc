package gd.script.gdcc.lir.parser;

import java.io.StringReader;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

import gd.script.gdcc.backend.CodegenContext;
import gd.script.gdcc.backend.ProjectInfo;
import gd.script.gdcc.backend.c.gen.CGenHelper;
import gd.script.gdcc.enums.GodotVersion;
import gd.script.gdcc.enums.LifecycleProvenance;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.lir.LirBasicBlock;
import gd.script.gdcc.lir.LirCaptureDef;
import gd.script.gdcc.lir.LirClassDef;
import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.lir.LirInstruction;
import gd.script.gdcc.lir.LirLambdaMeta;
import gd.script.gdcc.lir.LirModule;
import gd.script.gdcc.lir.LirParameterDef;
import gd.script.gdcc.lir.LirPropertyDef;
import gd.script.gdcc.lir.insn.AwaitInsn;
import gd.script.gdcc.lir.insn.CallGlobalInsn;
import gd.script.gdcc.lir.insn.DestructInsn;
import gd.script.gdcc.lir.insn.LineNumberInsn;
import gd.script.gdcc.lir.insn.LiteralStringInsn;
import gd.script.gdcc.lir.insn.PackVariantInsn;
import gd.script.gdcc.lir.insn.ReturnInsn;
import gd.script.gdcc.lir.insn.TryReleaseObjectInsn;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdFloatType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdVariantType;
import gd.script.gdcc.type.GdccForArrayIterType;
import gd.script.gdcc.type.GdccForDictionaryIterType;
import gd.script.gdcc.type.GdccForFloatIterType;
import gd.script.gdcc.type.GdccForPackedArrayIterType;
import gd.script.gdcc.type.GdccForRangeIterType;
import gd.script.gdcc.type.GdccForStringIterType;
import gd.script.gdcc.type.GdccForVariantIterType;

public class DomLirSerializerTest {
    /// `is_tool` and class/property annotations survive an XML roundtrip; in particular the parsed
    /// class annotation map must not be polluted by member annotations (direct-children read).
    @Test
    public void serialize_classToolAndAnnotationsRoundtrip() throws Exception {
        var property = new LirPropertyDef(
                "hp",
                new GdIntType(),
                false,
                null,
                null,
                null,
                Map.of("export", "", "export_range", "0,20,0.5")
        );
        var cls = new LirClassDef(
                "Tooly",
                "Node",
                false,
                true,
                Map.of("tool", ""),
                List.of(),
                List.of(property),
                List.of()
        );
        var module = new LirModule("m", List.of(cls));

        var xml = new DomLirSerializer().serializeToString(module);
        assertTrue(xml.contains("is_tool=\"true\""));
        assertTrue(xml.contains("<annotation key=\"tool\" value=\"\""));

        var parsed = new DomLirParser(new ClassRegistry(ExtensionApiLoader.loadDefault()))
                .parse(new StringReader(xml));
        var parsedClass = parsed.getClassDefs().getFirst();
        assertTrue(parsedClass.isTool());
        assertEquals(Map.of("tool", ""), parsedClass.getAnnotations());
        var parsedProperty = parsedClass.getProperties().getFirst();
        assertEquals("", parsedProperty.getAnnotations().get("export"));
        assertEquals("0,20,0.5", parsedProperty.getAnnotations().get("export_range"));
    }

    @Test
    public void serialize_module_includesBasicBlockInstructions() throws Exception {
        var fn = new LirFunctionDef("_init", "entry");
        fn.addParameter(new LirParameterDef("self", new GdObjectType("RotatingCamera"), null, fn));

        // add variable entries by creating variables via createAndAddTmpVariable or createAndAddVariable
        fn.createAndAddVariable("0", new GdFloatType());

        var bb = new LirBasicBlock("entry", List.of(
                new LineNumberInsn(9),
                new LiteralStringInsn("0", "Camera init"),
                new PackVariantInsn("1", "0"),
                new CallGlobalInsn(null, "print", List.of(new LirInstruction.VariableOperand("1"))),
                new DestructInsn("1"),
                new DestructInsn("0"),
                new ReturnInsn(null)
        ));
        fn.addBasicBlock(bb);

        var cls = new LirClassDef("RotatingCamera", "Camera3D", false, false, Map.of(), List.of(), List.of(), List.of(fn));
        var module = new LirModule("m", List.of(cls));

        var serializer = new DomLirSerializer();
        var xml = serializer.serializeToString(module);
        System.out.println(xml);

        assertTrue(xml.contains("<basic_block id=\"entry\""));
        // basic block text should include the literal string instruction
        assertTrue(xml.contains("$0 = literal_string \"Camera init\";"));
        assertTrue(xml.contains("call_global \"print\" $1;"));
    }

    @Test
    public void serialize_module_lifecycleInstructionsIncludeProvenance() throws Exception {
        var fn = new LirFunctionDef("_cleanup", "entry");
        fn.addParameter(new LirParameterDef("self", new GdObjectType("RotatingCamera"), null, fn));
        fn.createAndAddVariable("tmp", new GdObjectType("Node"));

        var bb = new LirBasicBlock("entry", List.of(
                new TryReleaseObjectInsn("tmp", LifecycleProvenance.USER_EXPLICIT),
                new ReturnInsn(null)
        ));
        fn.addBasicBlock(bb);

        var cls = new LirClassDef("RotatingCamera", "Camera3D", false, false, Map.of(), List.of(), List.of(), List.of(fn));
        var module = new LirModule("m", List.of(cls));
        var serializer = new DomLirSerializer();

        var xml = serializer.serializeToString(module);
        assertTrue(xml.contains("try_release_object $tmp \"USER_EXPLICIT\";"));
    }

    @Test
    public void serialize_module_preservesCanonicalSuperclassAttribute() throws Exception {
        // DOM serializer must preserve canonical class identity verbatim, regardless of separator spelling.
        var cls = new LirClassDef("Outer__sub__Leaf", "Outer__sub__Shared", false, false, Map.of(), List.of(), List.of(), List.of());
        var module = new LirModule("m", List.of(cls));
        var serializer = new DomLirSerializer();

        var xml = serializer.serializeToString(module);

        assertTrue(xml.contains("name=\"Outer__sub__Leaf\""), xml);
        assertTrue(xml.contains("super=\"Outer__sub__Shared\""), xml);
    }

    @Test
    public void serialize_module_preservesMappedTopLevelCanonicalClassIdentity() throws Exception {
        var cls = new LirClassDef("RuntimeOuter", "RuntimeBase", false, false, Map.of(), List.of(), List.of(), List.of());
        var module = new LirModule("m", List.of(cls));
        var serializer = new DomLirSerializer();

        var xml = serializer.serializeToString(module);

        assertTrue(xml.contains("name=\"RuntimeOuter\""), xml);
        assertTrue(xml.contains("super=\"RuntimeBase\""), xml);
        assertFalse(xml.contains("MappedOuter"), xml);
        assertFalse(xml.contains("BaseBySource"), xml);
    }

    @Test
    public void serialize_module_usesCompilerOnlyGrammarForFunctionVariables() throws Exception {
        var fn = new LirFunctionDef("_init", "entry");
        fn.createAndAddVariable("range_iter", GdccForRangeIterType.FOR_RANGE_ITER);
        fn.createAndAddVariable("string_iter", GdccForStringIterType.FOR_STRING_ITER);
        fn.createAndAddVariable("array_iter", GdccForArrayIterType.FOR_ARRAY_ITER);
        fn.createAndAddVariable("dictionary_iter", GdccForDictionaryIterType.FOR_DICTIONARY_ITER);
        fn.createAndAddVariable("variant_iter", GdccForVariantIterType.FOR_VARIANT_ITER);
        fn.createAndAddVariable("packed_array_iter", GdccForPackedArrayIterType.FOR_PACKED_INT32_ARRAY_ITER);
        fn.createAndAddVariable("float_iter", GdccForFloatIterType.FOR_FLOAT_ITER);
        fn.addBasicBlock(new LirBasicBlock("entry", List.of(new ReturnInsn(null))));

        var cls = new LirClassDef("RotatingCamera", "Camera3D", false, false, Map.of(), List.of(), List.of(), List.of(fn));
        var module = new LirModule("m", List.of(cls));
        var serializer = new DomLirSerializer();

        var xml = serializer.serializeToString(module);

        assertTrue(xml.contains("type=\"compiler::GdccForRangeIter\""), xml);
        assertTrue(xml.contains("type=\"compiler::GdccForStringIter\""), xml);
        assertTrue(xml.contains("type=\"compiler::GdccForArrayIter\""), xml);
        assertTrue(xml.contains("type=\"compiler::GdccForDictionaryIter\""), xml);
        assertTrue(xml.contains("type=\"compiler::GdccForVariantIter\""), xml);
        assertTrue(xml.contains("type=\"compiler::GdccForPackedInt32ArrayIter\""), xml);
        assertTrue(xml.contains("type=\"compiler::GdccForFloatIter\""), xml);
        assertFalse(xml.contains("type=\"GdccForRangeIter\""), xml);
        assertFalse(xml.contains("type=\"GdccForStringIter\""), xml);
        assertFalse(xml.contains("type=\"GdccForArrayIter\""), xml);
        assertFalse(xml.contains("type=\"GdccForDictionaryIter\""), xml);
        assertFalse(xml.contains("type=\"GdccForVariantIter\""), xml);
        assertFalse(xml.contains("type=\"GdccForPackedInt32ArrayIter\""), xml);
        assertFalse(xml.contains("type=\"GdccForFloatIter\""), xml);
    }

    @Test
    public void serialize_module_rejectsCompilerOnlyTypeOnFunctionReturnSurface() {
        var fn = new LirFunctionDef("helper", "entry");
        fn.setReturnType(GdccForRangeIterType.FOR_RANGE_ITER);
        fn.addBasicBlock(new LirBasicBlock("entry", List.of(new ReturnInsn(null))));

        var cls = new LirClassDef("RotatingCamera", "Camera3D", false, false, Map.of(), List.of(), List.of(), List.of(fn));
        var module = new LirModule("m", List.of(cls));
        var serializer = new DomLirSerializer();

        var exception = assertThrows(IllegalArgumentException.class, () -> serializer.serializeToString(module));

        assertTrue(exception.getMessage().contains("compiler-only type leaked into function return"), exception.getMessage());
    }

    @Test
    public void serialize_module_writesOrderedLambdaCapturesAndRoundTrips() throws Exception {
        var fn = new LirFunctionDef("_lambda_0", "entry");
        fn.setLambda(true);
        fn.setHidden(true);
        fn.setStatic(true);
        fn.setReturnType(new GdFloatType());
        fn.addCapture(new LirCaptureDef("seed", new GdFloatType(), fn));
        fn.addCapture(new LirCaptureDef("offset", new GdObjectType("Node"), fn));
        fn.addBasicBlock(new LirBasicBlock("entry", List.of(new ReturnInsn(null))));

        var cls = new LirClassDef("Hero", "Node", false, false, Map.of(), List.of(), List.of(), List.of(fn));
        var module = new LirModule("m", List.of(cls));
        var xml = new DomLirSerializer().serializeToString(module);

        assertTrue(xml.contains("is_lambda=\"true\""), xml);
        assertTrue(xml.contains("<capture name=\"seed\" type=\"float\"/>")
                || xml.contains("<capture name=\"seed\" type=\"float\" />"), xml);
        assertTrue(xml.contains("<capture name=\"offset\" type=\"Node\"/>")
                || xml.contains("<capture name=\"offset\" type=\"Node\" />"), xml);
        assertTrue(xml.indexOf("name=\"seed\"") < xml.indexOf("name=\"offset\""), xml);

        var parsed = new DomLirParser(new gd.script.gdcc.scope.ClassRegistry(
                gd.script.gdcc.gdextension.ExtensionApiLoader.loadDefault()
        )).parse(xml);
        var parsedFn = parsed.getClassDefs().getFirst().getFunctions().getFirst();
        assertEquals(2, parsedFn.getCaptureCount());
        var captures = parsedFn.getCaptureList();
        assertEquals("seed", captures.getFirst().getName());
        assertEquals("float", captures.getFirst().getType().getTypeName());
        assertEquals("offset", captures.getLast().getName());
        assertEquals("Node", captures.getLast().getType().getTypeName());
    }

    @Test
    public void serialize_module_rejectsCompilerOnlyLambdaCapture() {
        var fn = new LirFunctionDef("_lambda_0", "entry");
        fn.setLambda(true);
        fn.addCapture(new LirCaptureDef("iter", GdccForRangeIterType.FOR_RANGE_ITER, fn));
        fn.addBasicBlock(new LirBasicBlock("entry", List.of(new ReturnInsn(null))));

        var cls = new LirClassDef("Hero", "Node", false, false, Map.of(), List.of(), List.of(), List.of(fn));
        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> new DomLirSerializer().serializeToString(new LirModule("m", List.of(cls)))
        );
        assertTrue(exception.getMessage().contains("compiler-only type leaked into function capture"), exception.getMessage());
    }

    @Test
    public void addCaptureOnNonLambdaStillThrows() {
        var fn = new LirFunctionDef("run");
        assertThrows(
                IllegalStateException.class,
                () -> fn.addCapture(new LirCaptureDef("seed", new GdFloatType(), fn))
        );
    }

    @Test
    public void serialize_module_writesLambdaMetaElementAndRoundTrips() throws Exception {
        // The lambda-only identity rides a nested
        // `<meta call_site_context="..." source_identity_key="..."/>` element which must survive
        // the XML round-trip (the backend rebind table consumes the parsed values); a plain
        // function never carries the element at all.
        var fn = new LirFunctionDef("_lambda_0", "entry");
        fn.setLambda(true);
        fn.setHidden(true);
        fn.setStatic(true);
        fn.setLambdaMeta(new LirLambdaMeta("Hero::run#0", "call(base=self.sig_a, method=connect, arg=0)"));
        fn.addBasicBlock(new LirBasicBlock("entry", List.of(new ReturnInsn(null))));
        var plainFn = new LirFunctionDef("run", "entry");
        plainFn.addBasicBlock(new LirBasicBlock("entry", List.of(new ReturnInsn(null))));

        var cls = new LirClassDef("Hero", "Node", false, false, Map.of(), List.of(), List.of(), List.of(fn, plainFn));
        var module = new LirModule("m", List.of(cls));
        var xml = new DomLirSerializer().serializeToString(module);

        assertTrue(xml.contains("<meta"), xml);
        assertTrue(xml.contains("source_identity_key=\"Hero::run#0\""), xml);
        assertTrue(
                xml.contains("call_site_context=\"call(base=self.sig_a, method=connect, arg=0)\""),
                xml
        );
        // The plain function element must not carry a meta element; scope the assertion to its
        // own element body so the lambda's meta cannot mask a regression. (The Transformer sorts
        // attributes alphabetically, so locate the element by its name attribute, not a tag
        // prefix.)
        var plainStart = xml.indexOf("name=\"run\"");
        var plainFunctionElement = xml.substring(plainStart, xml.indexOf("</function>", plainStart));
        assertFalse(plainFunctionElement.contains("<meta"));
        assertFalse(plainFunctionElement.contains("source_identity_key"));

        var parsed = new DomLirParser(new gd.script.gdcc.scope.ClassRegistry(
                gd.script.gdcc.gdextension.ExtensionApiLoader.loadDefault()
        )).parse(xml);
        var parsedLambda = parsed.getClassDefs().getFirst().getFunctions().stream()
                .filter(f -> f.getName().equals("_lambda_0"))
                .findFirst()
                .orElseThrow();
        assertEquals(
                new LirLambdaMeta("Hero::run#0", "call(base=self.sig_a, method=connect, arg=0)"),
                parsedLambda.getLambdaMeta()
        );
        var parsedPlain = parsed.getClassDefs().getFirst().getFunctions().stream()
                .filter(f -> f.getName().equals("run"))
                .findFirst()
                .orElseThrow();
        assertNull(parsedPlain.getLambdaMeta());
    }

    @Test
    public void serialize_module_lambdaMetaRoundTripsXmlSpecialCharacters() throws Exception {
        // The context descriptor embeds normalized source slices, so quotes, `<`, `&` and `'`
        // are all reachable characters; the DOM attribute escaping must round-trip them intact.
        var fn = new LirFunctionDef("_lambda_0", "entry");
        fn.setLambda(true);
        var meta = new LirLambdaMeta("Hero::run#0", "call(base=foo(\"a<b&c'>\"), method=connect, arg=0)");
        fn.setLambdaMeta(meta);
        fn.addBasicBlock(new LirBasicBlock("entry", List.of(new ReturnInsn(null))));

        var cls = new LirClassDef("Hero", "Node", false, false, Map.of(), List.of(), List.of(), List.of(fn));
        var xml = new DomLirSerializer().serializeToString(new LirModule("m", List.of(cls)));

        var parsed = new DomLirParser(new gd.script.gdcc.scope.ClassRegistry(
                gd.script.gdcc.gdextension.ExtensionApiLoader.loadDefault()
        )).parse(xml);
        assertEquals(meta, parsed.getClassDefs().getFirst().getFunctions().getFirst().getLambdaMeta());
    }

    @Test
    public void serialize_module_writesCoroutineAttributeAndAwaitInsnRoundTrip() throws Exception {
        // Coroutine function whose body awaits a Variant (dynamic dispatch path); the XML
        // round-trip must preserve both the `is_coroutine` function attribute and the await insn.
        var fn = new LirFunctionDef("wait_dynamic", "entry");
        fn.setCoroutine(true);
        fn.createAndAddVariable("target", GdVariantType.VARIANT);
        fn.createAndAddVariable("0", GdVariantType.VARIANT);
        fn.addBasicBlock(new LirBasicBlock("entry", List.of(
                new AwaitInsn("0", "target"),
                new ReturnInsn(null)
        )));

        var cls = new LirClassDef("Hero", "Node", false, false, Map.of(), List.of(), List.of(), List.of(fn));
        var module = new LirModule("m", List.of(cls));
        var xml = new DomLirSerializer().serializeToString(module);

        assertTrue(xml.contains("is_coroutine=\"true\""), xml);
        assertTrue(xml.contains("$0 = await $target;"), xml);

        var parsed = new DomLirParser(new gd.script.gdcc.scope.ClassRegistry(
                gd.script.gdcc.gdextension.ExtensionApiLoader.loadDefault()
        )).parse(xml);
        var parsedFn = parsed.getClassDefs().getFirst().getFunctions().getFirst();
        assertTrue(parsedFn.isCoroutine());
        var parsedBlock = parsedFn.getBasicBlock("entry");
        assertNotNull(parsedBlock);
        var awaitInsn = assertInstanceOf(AwaitInsn.class, parsedBlock.getNonTerminatorInstructions().getFirst());
        assertAll(
                () -> assertEquals("0", awaitInsn.resultId()),
                () -> assertEquals("target", awaitInsn.operandId())
        );
    }

    @Test
    public void serialize_module_writesLambdaCoroutineCombinationAndRoundTrips() throws Exception {
        // Lambda+coroutine combination: `is_lambda="true"` and `is_coroutine="true"` are orthogonal
        // markers that coexist on one synthesized shell — alongside ordered <captures> and an
        // await body. Each marker had independent coverage; the combination was uncovered.
        var fn = new LirFunctionDef("_lambda_0", "entry");
        fn.setLambda(true);
        fn.setHidden(true);
        fn.setStatic(true);
        fn.setCoroutine(true);
        fn.setReturnType(GdVariantType.VARIANT);
        fn.addCapture(new LirCaptureDef("seed", new GdFloatType(), fn));
        fn.createAndAddVariable("target", GdVariantType.VARIANT);
        fn.createAndAddVariable("0", GdVariantType.VARIANT);
        fn.addBasicBlock(new LirBasicBlock("entry", List.of(
                new AwaitInsn("0", "target"),
                new ReturnInsn(null)
        )));

        var cls = new LirClassDef("Hero", "Node", false, false, Map.of(), List.of(), List.of(), List.of(fn));
        var module = new LirModule("m", List.of(cls));
        var xml = new DomLirSerializer().serializeToString(module);

        assertTrue(xml.contains("is_lambda=\"true\""), xml);
        assertTrue(xml.contains("is_coroutine=\"true\""), xml);
        assertTrue(xml.contains("<capture name=\"seed\" type=\"float\"/>")
                || xml.contains("<capture name=\"seed\" type=\"float\" />"), xml);
        assertTrue(xml.contains("$0 = await $target;"), xml);

        var parsed = new DomLirParser(new gd.script.gdcc.scope.ClassRegistry(
                gd.script.gdcc.gdextension.ExtensionApiLoader.loadDefault()
        )).parse(xml);
        var parsedFn = parsed.getClassDefs().getFirst().getFunctions().getFirst();
        var parsedAwait = assertInstanceOf(
                AwaitInsn.class,
                parsedFn.getBasicBlock("entry").getNonTerminatorInstructions().getFirst()
        );
        assertAll(
                () -> assertTrue(parsedFn.isLambda()),
                () -> assertTrue(parsedFn.isCoroutine()),
                () -> assertEquals(1, parsedFn.getCaptureCount()),
                () -> assertEquals("seed", parsedFn.getCaptureList().getFirst().getName()),
                () -> assertEquals("0", parsedAwait.resultId()),
                () -> assertEquals("target", parsedAwait.operandId())
        );
    }

    @Test
    public void serialize_module_coroutineDefaultsToFalseAndRoundTrips() throws Exception {
        // Plain sync function: attribute serializes as false and parses back false.
        var fn = new LirFunctionDef("sync_fn", "entry");
        fn.addBasicBlock(new LirBasicBlock("entry", List.of(new ReturnInsn(null))));

        var cls = new LirClassDef("Hero", "Node", false, false, Map.of(), List.of(), List.of(), List.of(fn));
        var module = new LirModule("m", List.of(cls));
        var xml = new DomLirSerializer().serializeToString(module);

        assertTrue(xml.contains("is_coroutine=\"false\""), xml);

        var parsed = new DomLirParser(new gd.script.gdcc.scope.ClassRegistry(
                gd.script.gdcc.gdextension.ExtensionApiLoader.loadDefault()
        )).parse(xml);
        var parsedFn = parsed.getClassDefs().getFirst().getFunctions().getFirst();
        assertFalse(parsedFn.isCoroutine());
    }

    @Test
    public void serialize_module_coroStateVariableRoundTripsThroughXml() throws Exception {
        // A coroutine function holding a `compiler::GdccCoroState` local (the static-path await
        // operand shape): the variable must serialize with the compiler-only type text and parse
        // back to the same singleton type.
        var fn = new LirFunctionDef("wait_step", "entry");
        fn.setCoroutine(true);
        fn.createAndAddVariable("state", gd.script.gdcc.type.GdccCoroStateType.CORO_STATE);
        fn.createAndAddVariable("0", GdVariantType.VARIANT);
        fn.addBasicBlock(new LirBasicBlock("entry", List.of(
                new AwaitInsn("0", "state"),
                new ReturnInsn(null)
        )));

        var cls = new LirClassDef("Hero", "Node", false, false, Map.of(), List.of(), List.of(), List.of(fn));
        var module = new LirModule("m", List.of(cls));
        var xml = new DomLirSerializer().serializeToString(module);

        assertTrue(xml.contains("type=\"compiler::GdccCoroState\""), xml);

        var parsed = new DomLirParser(new gd.script.gdcc.scope.ClassRegistry(
                gd.script.gdcc.gdextension.ExtensionApiLoader.loadDefault()
        )).parse(xml);
        var parsedFn = parsed.getClassDefs().getFirst().getFunctions().getFirst();
        assertAll(
                () -> assertTrue(parsedFn.isCoroutine()),
                () -> assertEquals(gd.script.gdcc.type.GdccCoroStateType.CORO_STATE,
                        java.util.Objects.requireNonNull(parsedFn.getVariableById("state")).type()),
                () -> assertInstanceOf(AwaitInsn.class,
                        parsedFn.getBasicBlock("entry").getNonTerminatorInstructions().getFirst())
        );
    }

    @Test
    public void serialize_module_onreadyPropertyRoundTripKeepsPlanClassification() throws Exception {
        // Frontend-shaped input: an onready property with a source initializer, a plain
        // property with one, and an onready property without one.
        var readyProp = new LirPropertyDef(
                "ready_count", new GdIntType(), false, "_field_init_ready_count",
                null, null, Map.of("onready", ""));
        var plainProp = new LirPropertyDef(
                "plain_count", new GdIntType(), false, "_field_init_plain_count",
                null, null, Map.of());
        var noInitProp = new LirPropertyDef(
                "pending", new GdIntType(), false, null,
                null, null, Map.of("onready", ""));
        var cls = new LirClassDef(
                "Hero", "Node", false, false, Map.of(), List.of(),
                List.of(readyProp, plainProp, noInitProp), List.of());
        var module = new LirModule("m", List.of(cls));

        var xml = new DomLirSerializer().serializeToString(module);
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var parsed = new DomLirParser(registry).parse(new StringReader(xml));
        var parsedClass = parsed.getClassDefs().getFirst();
        var parsedReady = parsedClass.getProperties().get(0);
        var parsedPlain = parsedClass.getProperties().get(1);
        var parsedNoInit = parsedClass.getProperties().get(2);
        assertEquals(Map.of("onready", ""), parsedReady.getAnnotations());
        assertEquals("_field_init_ready_count", parsedReady.getInitFunc());
        assertEquals("_field_init_plain_count", parsedPlain.getInitFunc());
        assertNull(parsedNoInit.getInitFunc());
        assertFalse(parsedReady.isInitializerSynthesizedByBackend());

        // The backend session plan built on the parsed LIR classifies the properties
        // exactly as the pre-serialization semantics would.
        registry.addGdccClass(parsedClass);
        var projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var helper = new CGenHelper(
                new CodegenContext(projectInfo, registry), parsed.getClassDefs());
        var classPlan = helper.onreadyPlan().classPlan(parsedClass.getName());
        assertEquals(List.of(parsedReady), classPlan.onreadyInitializers());
        assertTrue(classPlan.chainHasOnreadyInitializer());
        assertTrue(helper.checkOnreadyProperty(parsedReady));
        assertFalse(helper.checkOnreadyProperty(parsedPlain));
        assertTrue(helper.checkOnreadyProperty(parsedNoInit));
    }

    @Test
    public void serialize_module_backendDefaultInitMarkerDoesNotSurviveRoundTrip() throws Exception {
        // The backend-default provenance marker is transient state: the init function name
        // is ordinary serialized data, but the marker itself is not part of the LIR schema.
        var prop = new LirPropertyDef(
                "hp", new GdIntType(), false, null,
                null, null, Map.of("onready", ""));
        prop.setBackendSynthesizedInitFunc("_field_init_hp");
        var cls = new LirClassDef(
                "Hero", "Node", false, false, Map.of(), List.of(), List.of(prop), List.of());
        var module = new LirModule("m", List.of(cls));

        var xml = new DomLirSerializer().serializeToString(module);
        var parsed = new DomLirParser(new ClassRegistry(ExtensionApiLoader.loadDefault()))
                .parse(new StringReader(xml));
        var parsedProp = parsed.getClassDefs().getFirst().getProperties().getFirst();
        assertEquals("_field_init_hp", parsedProp.getInitFunc());
        assertFalse(parsedProp.isInitializerSynthesizedByBackend());
    }
}
