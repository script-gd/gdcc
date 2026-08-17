package gd.script.gdcc.lir.parser;

import gd.script.gdcc.enums.LifecycleProvenance;
import gd.script.gdcc.lir.*;
import gd.script.gdcc.lir.insn.*;
import gd.script.gdcc.type.GdFloatType;
import gd.script.gdcc.type.GdccForArrayIterType;
import gd.script.gdcc.type.GdccForDictionaryIterType;
import gd.script.gdcc.type.GdccForFloatIterType;
import gd.script.gdcc.type.GdccForPackedArrayIterType;
import gd.script.gdcc.type.GdccForRangeIterType;
import gd.script.gdcc.type.GdccForStringIterType;
import gd.script.gdcc.type.GdccForVariantIterType;
import gd.script.gdcc.type.GdObjectType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class DomLirSerializerTest {
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
}
