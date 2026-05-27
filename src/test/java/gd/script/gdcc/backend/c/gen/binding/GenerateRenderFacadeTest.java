package gd.script.gdcc.backend.c.gen.binding;

import gd.script.gdcc.backend.c.gen.binding.usage.GodotBindingUsageSession;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerateRenderFacadeTest {
    @Test
    void moduleLocalBindingRecordsShouldStayBufferedUntilTemplateRenderCommits() {
        var session = new GodotBindingUsageSession(Set.of());
        var templateUsageBuffer = session.newFunctionBuffer();
        var facade = new GenerateRenderFacade(
                (_, _) -> "",
                (_, _) -> "",
                templateUsageBuffer
        );

        facade.recordModuleLocalGodotBinding(ModuleLocalGodotBinding.classConstant("Probe", "READY", "13"));

        assertTrue(session.moduleLocalBindings().isEmpty());
        session.commit(templateUsageBuffer);
        assertEquals(List.of("godot_Probe_READY"), session.moduleLocalBindings().stream()
                .map(binding -> binding.symbol().cFunctionName())
                .toList());
    }
}
