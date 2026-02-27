package dev.superice.gdcc.backend.c.gen;

import dev.superice.gdcc.backend.CodegenContext;
import dev.superice.gdcc.backend.GeneratedFile;
import dev.superice.gdcc.backend.ProjectInfo;
import dev.superice.gdcc.enums.GodotVersion;
import dev.superice.gdcc.gdextension.ExtensionApiLoader;
import dev.superice.gdcc.lir.LirClassDef;
import dev.superice.gdcc.lir.LirModule;
import dev.superice.gdcc.lir.LirPropertyDef;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.type.GdFloatType;
import dev.superice.gdcc.type.GdObjectType;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CCodegenTest {
    @Test
    public void generatesEntryFiles() throws Exception {
        // build a simple LirModule
        var rotatingCameraClass = new LirClassDef("GDRotatingCamera3D", "Camera3D");
        rotatingCameraClass.addProperty(new LirPropertyDef("pitch_degree",
                GdFloatType.FLOAT,
                false,
                "_field_init_pitch_degree",
                "_field_getter_pitch_degree",
                "_field_setter_pitch_degree",
                Map.of())
        );
        var module = new LirModule("my_module", List.of(rotatingCameraClass));

        // load extension API and class registry
        var api = ExtensionApiLoader.loadDefault();
        var classRegistry = new ClassRegistry(api);

        // tiny ProjectInfo implementation for test
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var ctx = new CodegenContext(projectInfo, classRegistry);

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);
        List<GeneratedFile> files = codegen.generate();

        assertEquals(2, files.size(), "Should produce two files");

        var cFile = files.get(0);
        var hFile = files.get(1);
        var cCode = new String(cFile.contentWriter());
        var hCode = new String(hFile.contentWriter());
        System.out.println(hCode);
        System.out.println(cCode);
        assertTrue(cCode.contains("Loading my_module"));
        assertTrue(hCode.contains("GDEXTENSION_MY_MODULE_ENTRY_H"));
    }

    @Test
    public void generatesExplicitGdccInheritanceLayoutAndObjectPtrHelpers() throws Exception {
        var parentClass = new LirClassDef("GDParentNode", "Node");
        parentClass.addProperty(new LirPropertyDef("speed", GdFloatType.FLOAT));

        var childClass = new LirClassDef("GDChildNode", "GDParentNode");
        childClass.addProperty(new LirPropertyDef("peer", new GdObjectType("GDParentNode")));

        var module = new LirModule("inheritance_layout_module", List.of(parentClass, childClass));

        var api = ExtensionApiLoader.loadDefault();
        var classRegistry = new ClassRegistry(api);
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var ctx = new CodegenContext(projectInfo, classRegistry);

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);
        List<GeneratedFile> files = codegen.generate();

        var cCode = new String(files.get(0).contentWriter());
        var hCode = new String(files.get(1).contentWriter());

        assertTrue(hCode.contains("struct GDParentNode {"));
        assertTrue(hCode.contains("GDExtensionObjectPtr _object;"));
        assertTrue(hCode.contains("struct GDChildNode {"));
        assertTrue(hCode.contains("GDParentNode _super;"));
        assertTrue(hCode.contains("static inline GDExtensionObjectPtr GDParentNode_object_ptr(GDParentNode* self);"));
        assertTrue(hCode.contains("static inline GDExtensionObjectPtr GDChildNode_object_ptr(GDChildNode* self);"));
        assertTrue(hCode.contains("static inline void GDChildNode_set_object_ptr(GDChildNode* self, GDExtensionObjectPtr obj);"));

        assertTrue(cCode.contains("static inline GDExtensionObjectPtr GDChildNode_object_ptr(GDChildNode* self)"));
        assertTrue(cCode.contains("return GDParentNode_object_ptr(&self->_super);"));
        assertTrue(cCode.contains("GDChildNode_set_object_ptr(self, obj);"));
        assertTrue(cCode.contains("GDParentNode_class_constructor(&self->_super);"));
        assertTrue(cCode.contains("try_release_object(GDParentNode_object_ptr(self->peer));"));
        assertTrue(cCode.contains("GDParentNode_class_destructor(&self->_super);"));
        assertFalse(cCode.contains("godot_object_from_gdcc_object_ptr(self->peer)"));
    }
}
