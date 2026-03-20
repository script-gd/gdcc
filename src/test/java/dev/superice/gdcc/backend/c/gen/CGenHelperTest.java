package dev.superice.gdcc.backend.c.gen;

import dev.superice.gdcc.backend.CodegenContext;
import dev.superice.gdcc.backend.ProjectInfo;
import dev.superice.gdcc.enums.GodotVersion;
import dev.superice.gdcc.exception.InvalidInsnException;
import dev.superice.gdcc.gdextension.ExtensionApiLoader;
import dev.superice.gdcc.lir.LirClassDef;
import dev.superice.gdcc.lir.LirFunctionDef;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.type.GdArrayType;
import dev.superice.gdcc.type.GdIntType;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdPackedNumericArrayType;
import dev.superice.gdcc.type.GdStringNameType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CGenHelperTest {
    private CGenHelper helper;
    private LirFunctionDef function;

    @BeforeEach
    void setUp() throws IOException {
        var projectInfo = new ProjectInfo("TestProject", GodotVersion.V451, Path.of(".")) {
        };
        var extensionApi = ExtensionApiLoader.loadDefault();
        var classRegistry = new ClassRegistry(extensionApi);

        var gdccBase = new LirClassDef("MyBase", "RefCounted");
        var gdccChild = new LirClassDef("MyChild", "MyBase");
        classRegistry.addGdccClass(gdccBase);
        classRegistry.addGdccClass(gdccChild);

        var context = new CodegenContext(projectInfo, classRegistry);
        helper = new CGenHelper(context, List.of(gdccBase, gdccChild));

        function = new LirFunctionDef("test");
    }

    @Test
    @DisplayName("renderVarAssignWithGodotReturn should convert Godot object ptr to GDCC ptr for GDCC target")
    void renderVarAssignWithGodotReturnShouldConvertToGdccPtr() {
        function.createAndAddVariable("target", new GdObjectType("MyBase"));

        var statement = helper.renderVarAssignWithGodotReturn(
                function,
                "target",
                new GdObjectType("MyChild"),
                "godot_api_result()"
        );

        assertEquals("$target = (MyBase*)gdcc_object_from_godot_object_ptr(godot_api_result());", statement);
    }

    @Test
    @DisplayName("renderVarAssignWithGodotReturn should convert GDCC source ptr via helper before engine cast")
    void renderVarAssignWithGodotReturnShouldConvertGdccSourceBeforeEngineCast() {
        function.createAndAddVariable("target", new GdObjectType("RefCounted"));

        var statement = helper.renderVarAssignWithGodotReturn(
                function,
                "target",
                new GdObjectType("MyChild"),
                "$child_expr"
        );

        assertEquals("$target = (godot_RefCounted*)(gdcc_object_to_godot_object_ptr($child_expr, MyChild_object_ptr));", statement);
    }

    @Test
    @DisplayName("renderVarAssignWithGodotReturn should keep same-type engine pointer assignment direct")
    void renderVarAssignWithGodotReturnShouldKeepDirectAssignmentForSameEngineType() {
        function.createAndAddVariable("target", new GdObjectType("Node"));

        var statement = helper.renderVarAssignWithGodotReturn(
                function,
                "target",
                new GdObjectType("Node"),
                "godot_make_node()"
        );

        assertEquals("$target = godot_make_node();", statement);
    }

    @Test
    @DisplayName("renderVarAssignWithGodotReturn should reject readonly ref variable")
    void renderVarAssignWithGodotReturnShouldRejectReadonlyRefVariable() {
        function.createAndAddRefVariable("target", new GdObjectType("Node"));

        var ex = assertThrows(InvalidInsnException.class, () ->
                helper.renderVarAssignWithGodotReturn(
                        function,
                        "target",
                        new GdObjectType("Node"),
                        "godot_make_node()"
                )
        );

        assertInstanceOf(InvalidInsnException.class, ex);
    }

    @Test
    @DisplayName("renderVarAssignWithGodotReturn should reject incompatible assignment")
    void renderVarAssignWithGodotReturnShouldRejectIncompatibleAssignment() {
        function.createAndAddVariable("target", GdIntType.INT);

        var ex = assertThrows(InvalidInsnException.class, () ->
                helper.renderVarAssignWithGodotReturn(
                        function,
                        "target",
                        new GdObjectType("Node"),
                        "godot_make_node()"
                )
        );

        assertInstanceOf(InvalidInsnException.class, ex);
    }

    @Test
    @DisplayName("parseExtensionType should normalize typedarray PackedByteArray to packed type")
    void parseExtensionTypeShouldNormalizeTypedarrayPackedByteArray() {
        var parsed = helper.parseExtensionType(
                "typedarray::PackedByteArray",
                "test typedarray packed parameter"
        );

        assertEquals(GdPackedNumericArrayType.PACKED_BYTE_ARRAY, parsed);
    }

    @Test
    @DisplayName("parseExtensionType should normalize typedarray StringName to Array[StringName]")
    void parseExtensionTypeShouldNormalizeTypedarrayStringName() {
        var parsed = helper.parseExtensionType(
                "typedarray::StringName",
                "test typedarray parameter"
        );

        assertEquals(new GdArrayType(GdStringNameType.STRING_NAME), parsed);
    }

    @Test
    @DisplayName("parseExtensionType should resolve typedarray engine class element through registry")
    void parseExtensionTypeShouldResolveTypedarrayEngineClassElementThroughRegistry() {
        var parsed = helper.parseExtensionType(
                "typedarray::RDPipelineSpecializationConstant",
                "test typedarray engine class parameter"
        );

        assertEquals(new GdArrayType(new GdObjectType("RDPipelineSpecializationConstant")), parsed);
    }

    @Test
    @DisplayName("parseExtensionType should normalize enum and bitfield metadata to int")
    void parseExtensionTypeShouldNormalizeEnumAndBitfield() {
        var enumType = helper.parseExtensionType("enum::Variant.Type", "test enum return type");
        var bitfieldType = helper.parseExtensionType("bitfield::MethodFlags", "test bitfield parameter");

        assertEquals(GdIntType.INT, enumType);
        assertEquals(GdIntType.INT, bitfieldType);
    }

    @Test
    @DisplayName("parseExtensionType should reject malformed typedarray metadata")
    void parseExtensionTypeShouldRejectMalformedTypedarrayMetadata() {
        var ex = assertThrows(
                IllegalArgumentException.class,
                () -> helper.parseExtensionType("typedarray::   ", "test malformed typedarray")
        );

        assertTrue(ex.getMessage().contains("malformed typedarray metadata"), ex.getMessage());
    }

    @Test
    @DisplayName("parseExtensionType should reject unsupported metadata type")
    void parseExtensionTypeShouldRejectUnsupportedMetadataType() {
        var ex = assertThrows(
                IllegalArgumentException.class,
                () -> helper.parseExtensionType("typedarray::Array[]", "test unsupported typedarray")
        );

        assertTrue(ex.getMessage().contains("unsupported type metadata"), ex.getMessage());
    }
}
