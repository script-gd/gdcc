package dev.superice.gdcc.frontend.scope;

import dev.superice.gdcc.gdextension.ExtensionAPI;
import dev.superice.gdcc.gdextension.ExtensionEnumValue;
import dev.superice.gdcc.gdextension.ExtensionGdClass;
import dev.superice.gdcc.gdextension.ExtensionGlobalEnum;
import dev.superice.gdcc.gdextension.ExtensionHeader;
import dev.superice.gdcc.gdextension.ExtensionSingleton;
import dev.superice.gdcc.gdextension.ExtensionUtilityFunction;
import dev.superice.gdcc.lir.LirCaptureDef;
import dev.superice.gdcc.lir.LirClassDef;
import dev.superice.gdcc.lir.LirFunctionDef;
import dev.superice.gdcc.lir.LirParameterDef;
import dev.superice.gdcc.lir.LirPropertyDef;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.scope.ScopeTypeMeta;
import dev.superice.gdcc.scope.ScopeTypeMetaKind;
import dev.superice.gdcc.type.GdType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

final class FrontendScopeTestSupport {
    private FrontendScopeTestSupport() {
    }

    static @NotNull ClassRegistry createRegistry() {
        return new ClassRegistry(new ExtensionAPI(
                new ExtensionHeader(4, 4, 0, "stable", "test", "test", "single"),
                List.of(),
                List.of(),
                List.of(new ExtensionGlobalEnum("GlobalFlags", false, List.of(new ExtensionEnumValue("READY", 1)))),
                List.of(new ExtensionUtilityFunction("global_tick", "String", "debug", false, 1, List.of())),
                List.of(),
                List.of(
                        new ExtensionGdClass("Object", false, true, "", "core", List.of(), List.of(), List.of(), List.of(), List.of()),
                        new ExtensionGdClass("Node", false, true, "Object", "core", List.of(), List.of(), List.of(), List.of(), List.of())
                ),
                List.of(new ExtensionSingleton("GlobalPlayer", "Node")),
                List.of()
        ));
    }

    static @NotNull LirClassDef createClass(
            @NotNull String name,
            @NotNull String superName,
            @NotNull List<LirPropertyDef> properties,
            @NotNull List<LirFunctionDef> functions
    ) {
        var classDef = new LirClassDef(name, superName);
        for (var property : properties) {
            classDef.addProperty(property);
        }
        for (var function : functions) {
            classDef.addFunction(function);
        }
        return classDef;
    }

    static @NotNull LirPropertyDef createProperty(@NotNull String name, @NotNull GdType type) {
        return new LirPropertyDef(name, type);
    }

    static @NotNull LirFunctionDef createFunction(@NotNull String name, @NotNull GdType returnType) {
        var function = new LirFunctionDef(name);
        function.setReturnType(returnType);
        return function;
    }

    static @NotNull LirParameterDef createParameter(
            @NotNull String name,
            @NotNull GdType type,
            @NotNull LirFunctionDef owner
    ) {
        return new LirParameterDef(name, type, null, owner);
    }

    static @NotNull LirCaptureDef createCapture(
            @NotNull String name,
            @NotNull GdType type,
            @NotNull LirFunctionDef owner
    ) {
        return new LirCaptureDef(name, type, owner);
    }

    static @NotNull ScopeTypeMeta createTypeMeta(
            @NotNull String name,
            @NotNull GdType instanceType,
            @NotNull ScopeTypeMetaKind kind,
            @Nullable Object declaration,
            boolean pseudoType
    ) {
        return new ScopeTypeMeta(name, instanceType, kind, declaration, pseudoType);
    }
}
