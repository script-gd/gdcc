package dev.superice.gdcc.lir.parser;

import dev.superice.gdcc.enums.LifecycleProvenance;
import dev.superice.gdcc.lir.*;
import dev.superice.gdcc.lir.insn.*;
import dev.superice.gdcc.type.GdFloatType;
import dev.superice.gdcc.type.GdObjectType;
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
}
