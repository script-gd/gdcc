package gd.script.gdcc.scope;

import gd.script.gdcc.gdextension.ExtensionAPI;
import gd.script.gdcc.gdextension.ExtensionBuiltinClass;
import gd.script.gdcc.gdextension.ExtensionGdClass;
import gd.script.gdcc.lir.LirClassDef;
import gd.script.gdcc.lir.LirParameterDef;
import gd.script.gdcc.lir.LirSignalDef;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdStringType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class ClassRegistryEngineSignalLookupTest {
    @Test
    @DisplayName("engine signal lookup should match a direct engine class signal")
    void findEngineSignalInHierarchyShouldMatchDirectEngineSignal() {
        var ready = createEngineSignal("ready");
        var node = createEngineClass("Node", "Object", List.of(ready));
        var registry = newRegistry(apiWith(List.of(node)), List.of());

        var lookup = registry.findEngineSignalInHierarchy("Node", "ready");

        assertNotNull(lookup);
        assertEquals("Node", lookup.ownerClass().getName());
        assertSame(ready, lookup.signal());
    }

    @Test
    @DisplayName("engine signal lookup should walk past a GDCC parent to a native ancestor")
    void findEngineSignalInHierarchyShouldWalkPastGdccParentToNativeAncestor() {
        var ready = createEngineSignal("ready");
        var node = createEngineClass("Node", "Object", List.of(ready));
        var parent = new LirClassDef("ParentClass", "Node", false, false, Map.of(), List.of(), List.of(), List.of());
        var child = new LirClassDef("ChildClass", "ParentClass", false, false, Map.of(), List.of(), List.of(), List.of());
        var registry = newRegistry(apiWith(List.of(node)), List.of(parent, child));

        var lookup = registry.findEngineSignalInHierarchy("ChildClass", "ready");

        assertNotNull(lookup);
        assertEquals("Node", lookup.ownerClass().getName());
        assertSame(ready, lookup.signal());
    }

    @Test
    @DisplayName("engine signal lookup should ignore a same-name GDCC parent signal")
    void findEngineSignalInHierarchyShouldIgnoreSameNameGdccParentSignal() {
        var pinged = createSignal("pinged", GdIntType.INT);
        var parent = new LirClassDef("ParentClass", "RefCounted", false, false, Map.of(), List.of(pinged), List.of(), List.of());
        var child = new LirClassDef("ChildClass", "ParentClass", false, false, Map.of(), List.of(), List.of(), List.of());
        var registry = newRegistry(emptyApi(), List.of(parent, child));

        assertNull(registry.findEngineSignalInHierarchy("ChildClass", "pinged"));
        assertNull(registry.findEngineSignalInHierarchy("ParentClass", "pinged"));
    }

    @Test
    @DisplayName("engine signal lookup should still find a native ancestor when a GDCC parent reuses the same name")
    void findEngineSignalInHierarchyShouldFindNativeAncestorDespiteSameNameGdccParentSignal() {
        var nativeReady = createEngineSignal("ready");
        var node = createEngineClass("Node", "Object", List.of(nativeReady));
        var parentReady = createSignal("ready", GdStringType.STRING);
        var parent = new LirClassDef("ParentClass", "Node", false, false, Map.of(), List.of(parentReady), List.of(), List.of());
        var child = new LirClassDef("ChildClass", "ParentClass", false, false, Map.of(), List.of(), List.of(), List.of());
        var registry = newRegistry(apiWith(List.of(node)), List.of(parent, child));

        var lookup = registry.findEngineSignalInHierarchy("ChildClass", "ready");

        assertNotNull(lookup);
        assertEquals("Node", lookup.ownerClass().getName());
        assertSame(nativeReady, lookup.signal());
    }

    @Test
    @DisplayName("engine signal lookup should not confuse a GDCC parent signal with a different native signal")
    void findEngineSignalInHierarchyShouldNotTreatGdccParentSignalAsEngineEvenWhenNativeAncestorHasOtherSignals() {
        var ready = createEngineSignal("ready");
        var node = createEngineClass("Node", "Object", List.of(ready));
        var parentPinged = createSignal("pinged", GdStringType.STRING);
        var parent = new LirClassDef("ParentClass", "Node", false, false, Map.of(), List.of(parentPinged), List.of(), List.of());
        var child = new LirClassDef("ChildClass", "ParentClass", false, false, Map.of(), List.of(), List.of(), List.of());
        var registry = newRegistry(apiWith(List.of(node)), List.of(parent, child));

        assertNull(registry.findEngineSignalInHierarchy("ChildClass", "pinged"));
        var readyLookup = registry.findEngineSignalInHierarchy("ChildClass", "ready");
        assertNotNull(readyLookup);
        assertEquals("Node", readyLookup.ownerClass().getName());
        assertSame(ready, readyLookup.signal());
    }

    @Test
    @DisplayName("engine signal lookup should prefer the nearest native owner")
    void findEngineSignalInHierarchyShouldPreferNearestNativeOwner() {
        var objectReady = createEngineSignal("ready");
        var nodeReady = createEngineSignal("ready");
        var objectClass = createEngineClass("Object", "", List.of(objectReady));
        var nodeClass = createEngineClass("Node", "Object", List.of(nodeReady));
        var registry = newRegistry(apiWith(List.of(objectClass, nodeClass)), List.of());

        var lookup = registry.findEngineSignalInHierarchy("Node", "ready");

        assertNotNull(lookup);
        assertEquals("Node", lookup.ownerClass().getName());
        assertSame(nodeReady, lookup.signal());
    }

    @Test
    @DisplayName("engine signal lookup should return null for an unknown class or missing signal")
    void findEngineSignalInHierarchyShouldReturnNullForUnknownClassOrMissingSignal() {
        var ready = createEngineSignal("ready");
        var node = createEngineClass("Node", "Object", List.of(ready));
        var registry = newRegistry(apiWith(List.of(node)), List.of());

        assertNull(registry.findEngineSignalInHierarchy("UnknownClass", "ready"));
        assertNull(registry.findEngineSignalInHierarchy("Node", "missing"));
    }

    private static LirSignalDef createSignal(String name, gd.script.gdcc.type.GdType... parameterTypes) {
        var signal = new LirSignalDef(name);
        for (var index = 0; index < parameterTypes.length; index++) {
            signal.addParameter(new LirParameterDef("arg" + index, parameterTypes[index], null, signal));
        }
        return signal;
    }

    private static ExtensionGdClass.SignalInfo createEngineSignal(String name) {
        return new ExtensionGdClass.SignalInfo(name, new ArrayList<>());
    }

    private static ExtensionGdClass createEngineClass(
            String name,
            String superName,
            List<ExtensionGdClass.SignalInfo> signals
    ) {
        return new ExtensionGdClass(
                name,
                false,
                true,
                superName,
                "core",
                List.of(),
                List.of(),
                signals,
                List.of(),
                List.of()
        );
    }

    private static ClassRegistry newRegistry(ExtensionAPI api, List<LirClassDef> gdccClasses) {
        var registry = new ClassRegistry(api);
        for (var gdccClass : gdccClasses) {
            registry.addGdccClass(gdccClass);
        }
        return registry;
    }

    private static ExtensionAPI apiWith(List<ExtensionGdClass> gdClasses) {
        return new ExtensionAPI(
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.<ExtensionBuiltinClass>of(),
                gdClasses,
                List.of(),
                List.of()
        );
    }

    private static ExtensionAPI emptyApi() {
        return apiWith(List.of());
    }
}
