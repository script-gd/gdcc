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
import dev.superice.gdcc.lir.LirSignalDef;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.scope.ScopeTypeMeta;
import dev.superice.gdcc.scope.ScopeTypeMetaKind;
import dev.superice.gdcc.type.GdType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

final class FrontendScopeTestSupport {
    private FrontendScopeTestSupport() {
    }

    static @NotNull ClassRegistry createRegistry() {
        return createRegistry(List.of());
    }

    static @NotNull ClassRegistry createRegistry(@NotNull List<ExtensionGdClass> extraEngineClasses) {
        var engineClasses = new ArrayList<ExtensionGdClass>();
        engineClasses.add(new ExtensionGdClass("Object", false, true, "", "core", List.of(), List.of(), List.of(), List.of(), List.of()));
        engineClasses.add(new ExtensionGdClass("Node", false, true, "Object", "core", List.of(), List.of(), List.of(), List.of(), List.of()));
        engineClasses.addAll(extraEngineClasses);
        return new ClassRegistry(new ExtensionAPI(
                new ExtensionHeader(4, 4, 0, "stable", "test", "test", "single"),
                List.of(),
                List.of(),
                List.of(new ExtensionGlobalEnum("GlobalFlags", false, List.of(new ExtensionEnumValue("READY", 1)))),
                List.of(new ExtensionUtilityFunction("global_tick", "String", "debug", false, 1, List.of())),
                List.of(),
                List.copyOf(engineClasses),
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
        return createClass(name, superName, List.of(), properties, functions);
    }

    /// Creates a GDCC class fixture with first-class signal metadata so scope tests can exercise
    /// direct and inherited signal lookup without rebuilding low-level objects in every test.
    static @NotNull LirClassDef createClass(
            @NotNull String name,
            @NotNull String superName,
            @NotNull List<LirSignalDef> signals,
            @NotNull List<LirPropertyDef> properties,
            @NotNull List<LirFunctionDef> functions
    ) {
        var classDef = new LirClassDef(name, superName);
        for (var signal : signals) {
            classDef.addSignal(signal);
        }
        for (var property : properties) {
            classDef.addProperty(property);
        }
        for (var function : functions) {
            classDef.addFunction(function);
        }
        return classDef;
    }

    static @NotNull LirPropertyDef createProperty(@NotNull String name, @NotNull GdType type) {
        return createProperty(name, type, false);
    }

    static @NotNull LirPropertyDef createProperty(
            @NotNull String name,
            @NotNull GdType type,
            boolean isStatic
    ) {
        var property = new LirPropertyDef(name, type);
        property.setStatic(isStatic);
        return property;
    }

    static @NotNull LirFunctionDef createFunction(@NotNull String name, @NotNull GdType returnType) {
        return createFunction(name, returnType, false);
    }

    static @NotNull LirFunctionDef createFunction(
            @NotNull String name,
            @NotNull GdType returnType,
            boolean isStatic
    ) {
        var function = new LirFunctionDef(name);
        function.setReturnType(returnType);
        function.setStatic(isStatic);
        return function;
    }

    /// Creates a GDCC signal fixture with a stable parameter signature.
    static @NotNull LirSignalDef createSignal(@NotNull String name, @NotNull GdType... parameterTypes) {
        var signal = new LirSignalDef(name);
        for (var index = 0; index < parameterTypes.length; index++) {
            signal.addParameter(new LirParameterDef("arg" + index, parameterTypes[index], null, signal));
        }
        return signal;
    }

    /// Creates an engine-class signal fixture without forcing each test to assemble extension API
    /// records manually.
    static @NotNull ExtensionGdClass.SignalInfo createEngineSignal(
            @NotNull String name,
            @NotNull GdType... parameterTypes
    ) {
        var arguments = new ArrayList<ExtensionGdClass.SignalInfo.SignalArgument>();
        var signal = new ExtensionGdClass.SignalInfo(name, arguments);
        for (var index = 0; index < parameterTypes.length; index++) {
            arguments.add(new ExtensionGdClass.SignalInfo.SignalArgument(
                    "arg" + index,
                    parameterTypes[index].getTypeName(),
                    signal
            ));
        }
        return signal;
    }

    /// Creates a minimal engine class fixture that contributes only signal metadata.
    static @NotNull ExtensionGdClass createEngineClass(
            @NotNull String name,
            @NotNull String superName,
            @NotNull List<ExtensionGdClass.SignalInfo> signals
    ) {
        return new ExtensionGdClass(name, false, true, superName, "core", List.of(), List.of(), signals, List.of(), List.of());
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
        return createTypeMeta(name, name, instanceType, kind, declaration, pseudoType);
    }

    static @NotNull ScopeTypeMeta createTypeMeta(
            @NotNull String canonicalName,
            @NotNull String sourceName,
            @NotNull GdType instanceType,
            @NotNull ScopeTypeMetaKind kind,
            @Nullable Object declaration,
            boolean pseudoType
    ) {
        return new ScopeTypeMeta(canonicalName, sourceName, instanceType, kind, declaration, pseudoType);
    }
}
