package dev.superice.gdcc.lir.parser;

import dev.superice.gdcc.lir.LirModule;
import org.junit.jupiter.api.Test;

import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.*;

public class DomLirParserTest {
    @Test
    public void parse_basicBlockInstructionsFromXml() throws Exception {
        var xml = """
                <ir>
                  <class_def name="C" super="Object" is_abstract="false" is_tool="false">
                    <functions>
                      <function name="_init" is_static="false" is_abstract="false" is_lambda="false" is_vararg="false" is_hidden="false">
                        <parameters/>
                        <captures/>
                        <return_type type="void"/>
                        <variables>
                          <variable id="0" type="int"/>
                        </variables>
                        <basic_blocks entry="entry">
                          <basic_block id="entry">
                            $0 = literal_string "Hello";
                            call_global "print" $0;
                          </basic_block>
                        </basic_blocks>
                      </function>
                    </functions>
                  </class_def>
                </ir>
                """;

        var parser = new DomLirParser();
        LirModule mod = parser.parse(new StringReader(xml));
        assertNotNull(mod);
        assertEquals(1, mod.getClassDefs().size());
        var cls = mod.getClassDefs().getFirst();
        assertEquals(1, cls.getFunctions().size());
        var fn = cls.getFunctions().getFirst();
        assertEquals(1, fn.getBasicBlockCount());
        var bb = fn.getBasicBlock("entry");
        System.out.println(bb);
        assertNotNull(bb);
        assertEquals(2, bb.instructions().size());
    }
}
