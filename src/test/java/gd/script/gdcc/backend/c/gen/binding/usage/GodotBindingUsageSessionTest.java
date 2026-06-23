package gd.script.gdcc.backend.c.gen.binding.usage;

import gd.script.gdcc.backend.c.gen.binding.GodotBindingSymbol;
import gd.script.gdcc.backend.c.gen.binding.ModuleLocalGodotBinding;
import gd.script.gdcc.enums.GodotVersion;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.scope.ClassRegistry;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GodotBindingUsageSessionTest {
    @Test
    void providedWrappersShouldBeAcceptedButNotCommitted() {
        var providedName = "godot_Probe_Known";
        var session = new GodotBindingUsageSession(Set.of(providedName));
        var buffer = session.newFunctionBuffer();

        buffer.recordGodotCall(providedName);
        buffer.recordModuleLocalGodotBinding(ModuleLocalGodotBinding.classConstant("Probe", "Known", "1"));
        session.commit(buffer);

        assertTrue(session.moduleLocalBindings().isEmpty());
        assertTrue(session.moduleLocalCFunctionNames().isEmpty());
    }

    @Test
    void gdccHelperWrappersShouldBeRuntimeProvided() throws IOException {
        var registry = new ClassRegistry(ExtensionApiLoader.loadVersion(GodotVersion.V451));
        var session = GodotBindingUsageSession.forRegistry(registry);
        var buffer = session.newFunctionBuffer();

        for (var functionName : List.of(
                "godot_new_Nil",
                "godot_new_Nil_with_Variant",
                "godot_new_bool",
                "godot_new_bool_with_bool",
                "godot_new_bool_with_int",
                "godot_new_bool_with_float",
                "godot_new_int",
                "godot_new_int_with_int",
                "godot_new_int_with_float",
                "godot_new_int_with_bool",
                "godot_new_float",
                "godot_new_float_with_float",
                "godot_new_float_with_int",
                "godot_new_float_with_bool",
                "godot_new_gdcc_Object_with_Variant",
                "godot_new_Transform2D_with_float_float_float_float_float_float",
                "godot_new_Transform3D_with_float_float_float_float_float_float_float_float_float_float_float_float",
                "godot_new_Basis_with_float_float_float_float_float_float_float_float_float",
                "godot_new_Projection_with_float_float_float_float_float_float_float_float_float_float_float_float_float_float_float_float",
                "godot_Variant_call"
        )) {
            buffer.recordGodotCall(functionName);
        }
        session.commit(buffer);

        assertTrue(session.moduleLocalBindings().isEmpty());
        assertTrue(session.moduleLocalCFunctionNames().isEmpty());
    }

    @Test
    void moduleLocalBindingsShouldKeepFirstUseOrderAndMergeCompatibleConstants() {
        var session = new GodotBindingUsageSession(Set.of());
        var first = ModuleLocalGodotBinding.singleton("SceneTree");
        var sameConstant = ModuleLocalGodotBinding.classConstant("Probe", "READY", "13");
        var sameConstantAgain = ModuleLocalGodotBinding.classConstant("Probe", "READY", "13");
        var buffer = session.newFunctionBuffer();

        buffer.recordModuleLocalGodotBinding(first);
        buffer.recordGodotCall("godot_SceneTree_singleton");
        buffer.recordModuleLocalGodotBinding(sameConstant);
        buffer.recordModuleLocalGodotBinding(sameConstantAgain);
        buffer.recordGodotCall("godot_Probe_READY");
        session.commit(buffer);

        var snapshot = session.moduleLocalBindings();
        assertEquals(List.of("godot_SceneTree_singleton", "godot_Probe_READY"), snapshot.stream()
                .map(binding -> binding.symbol().cFunctionName())
                .toList());
        assertEquals(Set.of("godot_SceneTree_singleton", "godot_Probe_READY"), session.moduleLocalCFunctionNames());
    }

    @Test
    void failedFunctionBufferShouldNotLeakIntoSessionWhenItIsNotCommitted() {
        var session = new GodotBindingUsageSession(Set.of());
        var failedBuffer = session.newFunctionBuffer();
        failedBuffer.recordModuleLocalGodotBinding(ModuleLocalGodotBinding.classConstant("Probe", "FAILED", "1"));

        var successfulBuffer = session.newFunctionBuffer();
        successfulBuffer.recordModuleLocalGodotBinding(ModuleLocalGodotBinding.classConstant("Probe", "OK", "2"));
        session.commit(successfulBuffer);

        assertEquals(List.of("godot_Probe_OK"), session.moduleLocalBindings().stream()
                .map(binding -> binding.symbol().cFunctionName())
                .toList());
    }

    @Test
    void nonProvidedCallWithoutExplicitBindingShouldFailFast() {
        var buffer = new GodotBindingUsageSession(Set.of()).newFunctionBuffer();

        var failure = assertThrows(IllegalStateException.class, () -> buffer.recordGodotCall("godot_Probe_missing"));

        assertTrue(failure.getMessage().contains("was not explicitly registered as module-local"));
    }

    @Test
    void sameCNameForDifferentBindingsShouldFailFast() {
        var buffer = new GodotBindingUsageSession(Set.of()).newFunctionBuffer();

        buffer.recordModuleLocalGodotBinding(ModuleLocalGodotBinding.classConstant("Probe", "READY", "13"));
        var failure = assertThrows(
                IllegalStateException.class,
                () -> buffer.recordModuleLocalGodotBinding(singletonWithCName("SceneTree", "godot_Probe_READY"))
        );

        assertTrue(failure.getMessage().contains("Godot binding C name conflict for 'godot_Probe_READY'"));
    }

    @Test
    void sameCanonicalConstantWithDifferentValueShouldFailFast() {
        var buffer = new GodotBindingUsageSession(Set.of()).newFunctionBuffer();

        buffer.recordModuleLocalGodotBinding(ModuleLocalGodotBinding.classConstant("Probe", "READY", "13"));
        var failure = assertThrows(
                IllegalStateException.class,
                () -> buffer.recordModuleLocalGodotBinding(ModuleLocalGodotBinding.classConstant("Probe", "READY", "14"))
        );

        assertTrue(failure.getMessage().contains("Incompatible module-local Godot binding metadata for 'godot_Probe_READY'"));
    }

    @Test
    void cNameConflictAcrossCommittedBuffersShouldFailFast() {
        var session = new GodotBindingUsageSession(Set.of());
        var firstBuffer = session.newFunctionBuffer();
        firstBuffer.recordModuleLocalGodotBinding(ModuleLocalGodotBinding.classConstant("Probe", "READY", "13"));
        session.commit(firstBuffer);

        var secondBuffer = session.newFunctionBuffer();
        secondBuffer.recordModuleLocalGodotBinding(singletonWithCName("SceneTree", "godot_Probe_READY"));
        var failure = assertThrows(IllegalStateException.class, () -> session.commit(secondBuffer));

        assertTrue(failure.getMessage().contains("Godot binding C name conflict for 'godot_Probe_READY'"));
    }

    private static @NotNull ModuleLocalGodotBinding singletonWithCName(
            @NotNull String className,
            @NotNull String cFunctionName
    ) {
        return new ModuleLocalGodotBinding.Singleton(
                new GodotBindingSymbol(
                        GodotBindingSymbol.Family.SINGLETON,
                        className,
                        "singleton",
                        cFunctionName,
                        "godot_" + className + " *",
                        List.of(),
                        false,
                        null,
                        List.of()
                ),
                className
        );
    }
}
