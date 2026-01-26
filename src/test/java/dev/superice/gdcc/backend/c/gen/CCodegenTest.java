package dev.superice.gdcc.backend.c.gen;

import dev.superice.gdcc.backend.CodegenContext;
import dev.superice.gdcc.backend.GeneratedFile;
import dev.superice.gdcc.backend.ProjectInfo;
import dev.superice.gdcc.enums.GodotVersion;
import dev.superice.gdcc.gdextension.ExtensionApiLoader;
import dev.superice.gdcc.lir.LirModule;
import dev.superice.gdcc.scope.ClassRegistry;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CCodegenTest {
    @Test
    public void generatesEntryFiles() throws Exception {
        // build a simple LirModule
        var module = new LirModule("my_module", List.of());

        // load extension API and class registry
        var api = ExtensionApiLoader.loadDefault();
        var classRegistry = new ClassRegistry(api);

        // tiny ProjectInfo implementation for test
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {};
        var ctx = new CodegenContext(projectInfo, classRegistry);

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);
        List<GeneratedFile> files = codegen.generate();

        assertEquals(2, files.size(), "Should produce two files");

        var cFile = files.get(0);
        var hFile = files.get(1);

        assertTrue(new String(cFile.contentWriter()).contains("Loading my_module"));
        assertTrue(new String(hFile.contentWriter()).contains("GDEXTENSION_MY_MODULE_ENTRY_H"));
    }
}
