package gd.script.gdcc.backend.c.gen.binding;

import gd.script.gdcc.backend.c.gen.binding.usage.GodotBindingUsageSession;
import gd.script.gdcc.lir.LirClassDef;
import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.lir.LirPropertyDef;
import gd.script.gdcc.type.GdStringType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
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
                (_, _) -> "",
                (_, _) -> "",
                (_) -> "",
                (_) -> "",
                (_) -> "",
                templateUsageBuffer
        );

        facade.recordModuleLocalGodotBinding(ModuleLocalGodotBinding.classConstant("Probe", "READY", "13"));

        assertTrue(session.moduleLocalBindings().isEmpty());
        session.commit(templateUsageBuffer);
        assertEquals(List.of("godot_Probe_READY"), session.moduleLocalBindings().stream()
                .map(binding -> binding.symbol().cFunctionName())
                .toList());
    }

    @Test
    void propertyApplyMethodsShouldDispatchToTheirOwnCallbacks() {
        var classDef = new LirClassDef("Worker", "RefCounted");
        var property = new LirPropertyDef("title", GdStringType.STRING,
                false, null, null, null, Map.of());
        var func = new LirFunctionDef("work");
        var facade = new GenerateRenderFacade(
                (c, f) -> "func:" + f.getName(),
                (c, p) -> "init:" + p.getName(),
                (c, p) -> "default:" + p.getName(),
                (c, p) -> "onready:" + p.getName(),
                (c) -> "staticDefaults:" + c.getName(),
                (c) -> "staticInitializers:" + c.getName(),
                (c) -> "staticDeinitialize:" + c.getName()
        );

        assertEquals("func:work", facade.generateFuncBody(classDef, func));
        assertEquals("init:title", facade.generatePropertyInitApplyBody(classDef, property));
        assertEquals("default:title", facade.generatePropertyDefaultApplyBody(classDef, property));
        assertEquals("onready:title", facade.generateOnreadyApplyBody(classDef, property));
        assertEquals("staticDefaults:Worker", facade.generateStaticDefaultsBody(classDef));
        assertEquals("staticInitializers:Worker", facade.generateStaticInitializersBody(classDef));
        assertEquals("staticDeinitialize:Worker", facade.generateStaticDeinitializeBody(classDef));
    }
}
