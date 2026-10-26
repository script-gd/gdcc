package gd.script.gdcc.backend.c.build;

import freemarker.template.TemplateException;
import gd.script.gdcc.backend.CodegenContext;
import gd.script.gdcc.backend.ProjectInfo;
import gd.script.gdcc.backend.TemplateLoader;
import gd.script.gdcc.backend.c.gen.CGenHelper;
import gd.script.gdcc.enums.GodotVersion;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.lir.LirClassDef;
import gd.script.gdcc.lir.LirPropertyDef;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdIntType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Runtime smoke for the production `template_451/onready_lookup.ftl` fragment: the same
/// lookup code that lands in `entry.c` is rendered through the real `CGenHelper` model,
/// compiled with the real `gdcc_string_name.h` `GD_STATIC_SN` machinery (never a test
/// imitation of the macro), and executed against a counting fake engine. The probe
/// exercises the flag gates, exact-row dispatch, DONE latching, diagnostics, and the
/// same-image `destroy_all` key rebuild — see `src/test/resources/c/onready_lookup_probe.c`
/// for the scenario contract.
class GdccOnreadyLookupRuntimeSmokeTest {
    private static final Path PROBE_RESOURCE = Path.of("src/test/resources/c/onready_lookup_probe.c");

    @TempDir
    private static Path sharedDir;

    private static RuntimeProbeToolchain zig;

    @BeforeAll
    static void setUp() {
        zig = RuntimeProbeToolchain.zig();
        Assumptions.assumeTrue(zig != null, "Zig executable is required for the onready lookup runtime probe");
    }

    @Test
    void generatedLookupShouldDispatchByExactClassAcrossAllGatePaths() throws Exception {
        var lookupFragment = renderLookupFragment();
        var source = GdccStaticStringRuntimeSmokeTest.FAKE_ENGINE
                + Files.readString(PROBE_RESOURCE).replace("/*__GENERATED_LOOKUP__*/", lookupFragment);
        var run = zig.compileLinkAndRun(
                sharedDir,
                "onready_lookup_probe",
                source,
                List.of(RuntimeProbeToolchain.GODOT_INCLUDE_DIR.resolve("godot_binding.c")),
                List.of());
        assertEquals(0, run.exitCode(), run::diagnostic);
        assertFalse(run.output().contains("FAIL"), run::diagnostic);
        assertTrue(run.output().contains("OK onready lookup"), run::diagnostic);
    }

    /// LkLeaf -> LkMid -> LkRoot gives the first physical root a three-row table
    /// (including the inherited-only middle); LkRoot2 is the second root; LkClean
    /// produces no lookup machinery at all.
    private static String renderLookupFragment() throws IOException, TemplateException {
        var projectInfo = new ProjectInfo("OnreadyLookupProbe", GodotVersion.V451, Path.of(".")) {
        };
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var root = new LirClassDef("LkRoot", "Node");
        root.addProperty(onreadyInt("r"));
        var mid = new LirClassDef("LkMid", "LkRoot");
        var leaf = new LirClassDef("LkLeaf", "LkMid");
        leaf.addProperty(onreadyInt("l"));
        var root2 = new LirClassDef("LkRoot2", "Node");
        root2.addProperty(onreadyInt("o"));
        var clean = new LirClassDef("LkClean", "Node");
        var classes = List.of(leaf, mid, root, root2, clean);
        for (var classDef : classes) {
            registry.addGdccClass(classDef);
        }
        var helper = new CGenHelper(new CodegenContext(projectInfo, registry), classes);
        return TemplateLoader.renderFromClasspath("template_451/onready_lookup.ftl", Map.of("helper", helper));
    }

    private static LirPropertyDef onreadyInt(String name) {
        return new LirPropertyDef(
                name, GdIntType.INT, false, "_field_init_" + name, null, null, Map.of("onready", ""));
    }
}
