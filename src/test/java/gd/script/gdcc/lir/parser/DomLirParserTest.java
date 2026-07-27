package gd.script.gdcc.lir.parser;

import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.type.GdArrayType;
import gd.script.gdcc.type.GdDictionaryType;
import gd.script.gdcc.type.GdccForArrayIterType;
import gd.script.gdcc.type.GdccForDictionaryIterType;
import gd.script.gdcc.type.GdccForFloatIterType;
import gd.script.gdcc.type.GdccForPackedArrayIterType;
import gd.script.gdcc.type.GdccForRangeIterType;
import gd.script.gdcc.type.GdccForStringIterType;
import gd.script.gdcc.type.GdccForVariantIterType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdStringType;
import gd.script.gdcc.lir.LirModule;
import gd.script.gdcc.scope.ClassRegistry;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.List;
import java.util.Objects;

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
        assertEquals(2, bb.getInstructionCount());
    }

    @Test
    public void parse_rejectsInstructionAfterTerminatorWithinBasicBlock() throws Exception {
        var xml = """
                <ir>
                  <class_def name="C" super="Object" is_abstract="false" is_tool="false">
                    <functions>
                      <function name="_init" is_static="false" is_abstract="false" is_lambda="false" is_vararg="false" is_hidden="false">
                        <parameters/>
                        <captures/>
                        <return_type type="void"/>
                        <variables>
                          <variable id="0" type="String"/>
                        </variables>
                        <basic_blocks entry="entry">
                          <basic_block id="entry">
                            return;
                            $0 = literal_string "unexpected";
                          </basic_block>
                        </basic_blocks>
                      </function>
                    </functions>
                  </class_def>
                </ir>
                """;

        var parser = new DomLirParser(new ClassRegistry(ExtensionApiLoader.loadDefault()));
        var exception = assertThrows(IllegalStateException.class, () -> parser.parse(new StringReader(xml)));

        assertTrue(exception.getMessage().contains("terminator"), exception.getMessage());
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
                  <class_def name="Outer__sub__Leaf" super="Outer__sub__Shared" is_abstract="false" is_tool="false">
                    <functions/>
                  </class_def>
                </ir>
                """;

        var parser = new DomLirParser(new ClassRegistry(ExtensionApiLoader.loadDefault()));
        var mod = parser.parse(new StringReader(xml));
        var cls = mod.getClassDefs().getFirst();

        // Parser should round-trip canonical inner names as opaque class identity, not normalize them back to legacy '$'.
        assertEquals("Outer__sub__Leaf", cls.getName());
        assertEquals("Outer__sub__Shared", cls.getSuperName());
    }

    @Test
    public void parse_preservesMappedTopLevelCanonicalClassIdentity() throws Exception {
        var xml = """
                <ir>
                  <class_def name="RuntimeOuter" super="RuntimeBase" is_abstract="false" is_tool="false">
                    <functions/>
                  </class_def>
                </ir>
                """;

        var parser = new DomLirParser(new ClassRegistry(ExtensionApiLoader.loadDefault()));
        var mod = parser.parse(new StringReader(xml));
        var cls = mod.getClassDefs().getFirst();

        assertEquals("RuntimeOuter", cls.getName());
        assertEquals("RuntimeBase", cls.getSuperName());
        assertNotEquals("MappedOuter", cls.getName());
        assertNotEquals("BaseBySource", cls.getSuperName());
    }

    @Test
    public void parse_allowsCompilerOnlyTypeOnlyOnFunctionVariables() throws Exception {
        var xml = """
                <ir>
                  <class_def name="C" super="Object" is_abstract="false" is_tool="false">
                    <functions>
                      <function name="_init" is_static="false" is_abstract="false" is_lambda="false" is_vararg="false" is_hidden="false">
                        <parameters/>
                        <captures/>
                        <return_type type="void"/>
                          <variables>
                            <variable id="range_iter" type="compiler::GdccForRangeIter"/>
                            <variable id="string_iter" type="compiler::GdccForStringIter"/>
                            <variable id="array_iter" type="compiler::GdccForArrayIter"/>
                            <variable id="dictionary_iter" type="compiler::GdccForDictionaryIter"/>
                            <variable id="variant_iter" type="compiler::GdccForVariantIter"/>
                            <variable id="packed_array_iter" type="compiler::GdccForPackedInt32ArrayIter"/>
                            <variable id="float_iter" type="compiler::GdccForFloatIter"/>
                          </variables>
                        <basic_blocks entry="entry">
                          <basic_block id="entry">
                            return;
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

        assertAll(
                () -> assertEquals(GdccForRangeIterType.FOR_RANGE_ITER,
                        Objects.requireNonNull(fn.getVariableById("range_iter")).type()),
                () -> assertEquals(GdccForStringIterType.FOR_STRING_ITER,
                        Objects.requireNonNull(fn.getVariableById("string_iter")).type()),
                () -> assertEquals(GdccForArrayIterType.FOR_ARRAY_ITER,
                        Objects.requireNonNull(fn.getVariableById("array_iter")).type()),
                () -> assertEquals(GdccForDictionaryIterType.FOR_DICTIONARY_ITER,
                        Objects.requireNonNull(fn.getVariableById("dictionary_iter")).type()),
                () -> assertEquals(GdccForVariantIterType.FOR_VARIANT_ITER,
                        Objects.requireNonNull(fn.getVariableById("variant_iter")).type()),
                () -> assertEquals(GdccForPackedArrayIterType.FOR_PACKED_INT32_ARRAY_ITER,
                        Objects.requireNonNull(fn.getVariableById("packed_array_iter")).type()),
                () -> assertEquals(GdccForFloatIterType.FOR_FLOAT_ITER,
                        Objects.requireNonNull(fn.getVariableById("float_iter")).type())
        );
    }

    @Test
    public void parse_rejectsCompilerOnlyTypeOnPublicAbiAndLambdaCaptureSurfaces() throws Exception {
        var surfaces = java.util.Map.of(
                "signal parameter", """
                        <ir>
                          <class_def name="C" super="Object" is_abstract="false" is_tool="false">
                            <signals>
                              <signal name="changed">
                                <parameter name="iter" type="compiler::GdccForRangeIter"/>
                              </signal>
                            </signals>
                            <functions/>
                          </class_def>
                        </ir>
                        """,
                "property", """
                        <ir>
                          <class_def name="C" super="Object" is_abstract="false" is_tool="false">
                            <properties>
                              <property name="iter" type="compiler::GdccForRangeIter" is_static="false"/>
                            </properties>
                            <functions/>
                          </class_def>
                        </ir>
                        """,
                "function parameter", """
                        <ir>
                          <class_def name="C" super="Object" is_abstract="false" is_tool="false">
                            <functions>
                              <function name="step" is_static="false" is_abstract="false" is_lambda="false" is_vararg="false" is_hidden="false">
                                <parameters>
                                  <parameter name="iter" type="compiler::GdccForRangeIter"/>
                                </parameters>
                                <captures/>
                                <return_type type="void"/>
                                <variables/>
                                <basic_blocks entry="entry"><basic_block id="entry">return;</basic_block></basic_blocks>
                              </function>
                            </functions>
                          </class_def>
                        </ir>
                        """,
                "function return", """
                        <ir>
                          <class_def name="C" super="Object" is_abstract="false" is_tool="false">
                            <functions>
                              <function name="step" is_static="false" is_abstract="false" is_lambda="false" is_vararg="false" is_hidden="true">
                                <parameters/>
                                <captures/>
                                <return_type type="compiler::GdccForRangeIter"/>
                                <variables/>
                                <basic_blocks entry="entry"><basic_block id="entry">return;</basic_block></basic_blocks>
                              </function>
                            </functions>
                          </class_def>
                        </ir>
                        """,
                "function capture", """
                        <ir>
                          <class_def name="C" super="Object" is_abstract="false" is_tool="false">
                            <functions>
                              <function name="lambda0" is_static="false" is_abstract="false" is_lambda="true" is_vararg="false" is_hidden="false">
                                <parameters/>
                                <captures>
                                  <capture name="iter" type="compiler::GdccForRangeIter"/>
                                </captures>
                                <return_type type="void"/>
                                <variables/>
                                <basic_blocks entry="entry"><basic_block id="entry">return;</basic_block></basic_blocks>
                              </function>
                            </functions>
                          </class_def>
                        </ir>
                        """
        );

        for (var entry : surfaces.entrySet()) {
            var parser = new DomLirParser(new ClassRegistry(ExtensionApiLoader.loadDefault()));
            var exception = assertThrows(IllegalArgumentException.class, () -> parser.parse(new StringReader(entry.getValue())), entry.getKey());

            assertTrue(exception.getMessage().contains("compiler-only type leaked into " + entry.getKey()), exception.getMessage());
        }
    }

    @Test
    public void parse_rejectsUnknownCompilerOnlyGrammarWithoutGuessingObjectType() throws Exception {
        var xml = """
                <ir>
                  <class_def name="C" super="Object" is_abstract="false" is_tool="false">
                    <functions>
                      <function name="_init" is_static="false" is_abstract="false" is_lambda="false" is_vararg="false" is_hidden="false">
                        <parameters/>
                        <captures/>
                        <return_type type="void"/>
                        <variables>
                          <variable id="iter" type="compiler::UnknownIter"/>
                        </variables>
                        <basic_blocks entry="entry"><basic_block id="entry">return;</basic_block></basic_blocks>
                      </function>
                    </functions>
                  </class_def>
                </ir>
                """;

        var parser = new DomLirParser(new ClassRegistry(ExtensionApiLoader.loadDefault()));
        var exception = assertThrows(IllegalArgumentException.class, () -> parser.parse(new StringReader(xml)));

        assertTrue(exception.getMessage().contains("Unknown compiler-only type text: compiler::UnknownIter"), exception.getMessage());
        assertFalse(exception.getMessage().contains("GdObjectType"), exception.getMessage());
    }

    @Test
    public void parse_rejectsMalformedCompilerOnlyGrammarWithoutGuessingObjectType() throws Exception {
        var malformedTypeTexts = List.of("compiler::", "compiler:OnlyOneColon", "compiler ::GdccForRangeIter");

        for (var typeText : malformedTypeTexts) {
            var xml = """
                    <ir>
                      <class_def name="C" super="Object" is_abstract="false" is_tool="false">
                        <functions>
                          <function name="_init" is_static="false" is_abstract="false" is_lambda="false" is_vararg="false" is_hidden="false">
                            <parameters/>
                            <captures/>
                            <return_type type="void"/>
                            <variables>
                              <variable id="iter" type="%s"/>
                            </variables>
                            <basic_blocks entry="entry"><basic_block id="entry">return;</basic_block></basic_blocks>
                          </function>
                        </functions>
                      </class_def>
                    </ir>
                    """.formatted(typeText);

            var parser = new DomLirParser(new ClassRegistry(ExtensionApiLoader.loadDefault()));
            var exception = assertThrows(IllegalArgumentException.class, () -> parser.parse(new StringReader(xml)), typeText);

            assertFalse(exception.getMessage().contains("GdObjectType"), exception.getMessage());
            assertTrue(
                    exception.getMessage().contains("compiler") || exception.getMessage().contains("Cannot parse type"),
                    exception.getMessage()
            );
        }
    }
}
