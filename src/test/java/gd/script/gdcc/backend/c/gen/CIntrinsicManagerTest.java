package gd.script.gdcc.backend.c.gen;

import gd.script.gdcc.backend.CodegenContext;
import gd.script.gdcc.backend.ProjectInfo;
import gd.script.gdcc.backend.c.gen.intrinsic.CIntToFloatIntrinsic;
import gd.script.gdcc.enums.GodotVersion;
import gd.script.gdcc.gdextension.ExtensionAPI;
import gd.script.gdcc.lir.LirClassDef;
import gd.script.gdcc.scope.ClassRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

class CIntrinsicManagerTest {
    @Test
    @DisplayName("manager should expose only registered backend intrinsics")
    void managerShouldExposeOnlyRegisteredBackendIntrinsics() {
        var manager = new CIntrinsicManager();

        assertInstanceOf(CIntToFloatIntrinsic.class, manager.find(CIntToFloatIntrinsic.NAME));
        assertNull(manager.find("unknown"));
    }

    @Test
    @DisplayName("CGenHelper should expose intrinsic manager")
    void cGenHelperShouldExposeIntrinsicManager() {
        var workerClass = new LirClassDef("Worker", "RefCounted", false, false,
                Map.of(), List.of(), List.of(), List.of());
        var api = new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of());
        var classRegistry = new ClassRegistry(api);
        classRegistry.addGdccClass(workerClass);
        ProjectInfo projectInfo = new ProjectInfo("TestProject", GodotVersion.V451, Path.of(".")) {
        };
        var helper = new CGenHelper(new CodegenContext(projectInfo, classRegistry), List.of(workerClass));

        assertInstanceOf(CIntToFloatIntrinsic.class, helper.intrinsicManager().find(CIntToFloatIntrinsic.NAME));
    }
}
