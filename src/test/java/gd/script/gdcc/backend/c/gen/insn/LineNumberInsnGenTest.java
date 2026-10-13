package gd.script.gdcc.backend.c.gen.insn;

import gd.script.gdcc.lir.LirBasicBlock;
import gd.script.gdcc.lir.LirClassDef;
import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.lir.insn.LineNumberInsn;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// `#line` operand emission: the file name must go through the directive-specific escaper so
/// non-ASCII names stay raw UTF-8 bytes (tcc decodes no escapes in `#line`), while structural
/// characters keep their escapes for clang/zig.
class LineNumberInsnGenTest {
    private final LineNumberInsnGen gen = new LineNumberInsnGen();

    @Test
    void nonAsciiSourceFileNameStaysRawUtf8() {
        var clazz = new LirClassDef("GDFoo", "RefCounted");
        clazz.setSourceFile("café中文.gd");
        var code = gen.generateCCode(null, clazz, new LirFunctionDef("f", "entry"),
                new LirBasicBlock("entry"), 0, new LineNumberInsn(12));
        assertEquals("#line 12 \"café中文.gd\"", code);
    }

    @Test
    void tabInFileNamePassesThroughRaw() {
        var clazz = new LirClassDef("GDFoo", "RefCounted");
        clazz.setSourceFile("a\tb.gd");
        var code = gen.generateCCode(null, clazz, new LirFunctionDef("f", "entry"),
                new LirBasicBlock("entry"), 0, new LineNumberInsn(4));
        assertEquals("#line 4 \"a\tb.gd\"", code);
    }

    @Test
    void fallsBackToClassNameAndEscapesStructuralChars() {
        var clazz = new LirClassDef("GD\"Quoted\"", "RefCounted");
        var code = gen.generateCCode(null, clazz, new LirFunctionDef("f", "entry"),
                new LirBasicBlock("entry"), 0, new LineNumberInsn(3));
        assertEquals("#line 3 \"GD\\\"Quoted\\\"\"", code);
    }
}
