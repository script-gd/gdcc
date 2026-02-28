package dev.superice.gdcc.backend.c.gen;

import dev.superice.gdcc.backend.CodegenContext;
import dev.superice.gdcc.backend.ProjectInfo;
import dev.superice.gdcc.enums.GodotVersion;
import dev.superice.gdcc.exception.InvalidInsnException;
import dev.superice.gdcc.gdextension.ExtensionApiLoader;
import dev.superice.gdcc.lir.LirClassDef;
import dev.superice.gdcc.lir.LirFunctionDef;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.type.GdIntType;
import dev.superice.gdcc.type.GdObjectType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
}
