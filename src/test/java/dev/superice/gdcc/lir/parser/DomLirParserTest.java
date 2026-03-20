package dev.superice.gdcc.lir.parser;

import dev.superice.gdcc.gdextension.ExtensionApiLoader;
import dev.superice.gdcc.type.GdArrayType;
import dev.superice.gdcc.type.GdDictionaryType;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdStringType;
import dev.superice.gdcc.lir.LirModule;
import dev.superice.gdcc.scope.ClassRegistry;
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

        var parser = new DomLirParser(new ClassRegistry(ExtensionApiLoader.loadDefault()));
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

    @Test
    public void parse_preservesContainerShapeForUnknownLeafObjectTypes() throws Exception {
        var xml = """
                <ir>
                  <class_def name="C" super="Object" is_abstract="false" is_tool="false">
                    <functions>
                      <function name="_init" is_static="false" is_abstract="false" is_lambda="false" is_vararg="false" is_hidden="false">
                        <parameters/>
                        <captures/>
                        <return_type type="void"/>
                        <variables>
                          <variable id="0" type="Array[FutureItem]"/>
                          <variable id="1" type="Dictionary[String, FutureItem]"/>
                        </variables>
                        <basic_blocks entry="entry">
                          <basic_block id="entry">
                          </basic_block>
                        </basic_blocks>
                      </function>
                    </functions>
                  </class_def>
                </ir>
                """;

        var parser = new DomLirParser(new ClassRegistry(ExtensionApiLoader.loadDefault()));
        var mod = parser.parse(new StringReader(xml));
        var fn = mod.getClassDefs().getFirst().getFunctions().getFirst();

        var arrayType = assertInstanceOf(GdArrayType.class, fn.getVariableById("0").type());
        assertEquals(new GdObjectType("FutureItem"), arrayType.getValueType());

        var dictionaryType = assertInstanceOf(GdDictionaryType.class, fn.getVariableById("1").type());
        assertEquals(GdStringType.STRING, dictionaryType.getKeyType());
        assertEquals(new GdObjectType("FutureItem"), dictionaryType.getValueType());
    }

    @Test
    public void parse_preservesBareUnknownObjectTypesThroughRegistryCompatibilityLookup() throws Exception {
        var xml = """
                <ir>
                  <class_def name="C" super="Object" is_abstract="false" is_tool="false">
                    <functions>
                      <function name="_init" is_static="false" is_abstract="false" is_lambda="false" is_vararg="false" is_hidden="false">
                        <parameters/>
                        <captures/>
                        <return_type type="void"/>
                        <variables>
                          <variable id="0" type="FutureEnemy"/>
                        </variables>
                        <basic_blocks entry="entry">
                          <basic_block id="entry">
                          </basic_block>
                        </basic_blocks>
                      </function>
                    </functions>
                  </class_def>
                </ir>
                """;

        var parser = new DomLirParser(new ClassRegistry(ExtensionApiLoader.loadDefault()));
        var mod = parser.parse(new StringReader(xml));
        var fn = mod.getClassDefs().getFirst().getFunctions().getFirst();

        assertEquals(new GdObjectType("FutureEnemy"), fn.getVariableById("0").type());
    }

    @Test
    public void parse_rejectsInvalidContainerLeafIdentifierInsteadOfGuessingWholeType() throws Exception {
        var xml = """
                <ir>
                  <class_def name="C" super="Object" is_abstract="false" is_tool="false">
                    <functions>
                      <function name="_init" is_static="false" is_abstract="false" is_lambda="false" is_vararg="false" is_hidden="false">
                        <parameters/>
                        <captures/>
                        <return_type type="void"/>
                        <variables>
                          <variable id="0" type="Array[bad-name]"/>
                        </variables>
                        <basic_blocks entry="entry">
                          <basic_block id="entry">
                          </basic_block>
                        </basic_blocks>
                      </function>
                    </functions>
                  </class_def>
                </ir>
                """;

        var parser = new DomLirParser(new ClassRegistry(ExtensionApiLoader.loadDefault()));
        var ex = assertThrows(IllegalArgumentException.class, () -> parser.parse(new StringReader(xml)));

        assertTrue(ex.getMessage().contains("Array[bad-name]"), ex.getMessage());
    }

    @Test
    public void parse_preservesCanonicalSuperclassAttribute() throws Exception {
        var xml = """
                <ir>
                  <class_def name="Outer$Leaf" super="Outer$Shared" is_abstract="false" is_tool="false">
                    <functions/>
                  </class_def>
                </ir>
                """;

        var parser = new DomLirParser(new ClassRegistry(ExtensionApiLoader.loadDefault()));
        var mod = parser.parse(new StringReader(xml));
        var cls = mod.getClassDefs().getFirst();

        assertEquals("Outer$Leaf", cls.getName());
        assertEquals("Outer$Shared", cls.getSuperName());
    }
}
