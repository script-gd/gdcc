package dev.superice.gdcc.scope.resolver;

import dev.superice.gdcc.gdextension.ExtensionAPI;
import dev.superice.gdcc.gdextension.ExtensionBuiltinClass;
import dev.superice.gdcc.gdextension.ExtensionGdClass;
import dev.superice.gdcc.lir.LirClassDef;
import dev.superice.gdcc.lir.LirParameterDef;
import dev.superice.gdcc.lir.LirSignalDef;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.scope.ScopeOwnerKind;
import dev.superice.gdcc.type.GdIntType;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdStringType;
import dev.superice.gdcc.type.GdType;
import dev.superice.gdcc.type.GdVariantType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScopeSignalResolverTest {
    @Test
    @DisplayName("shared object signal resolver should report metadata unknown for unknown receiver")
    void resolveObjectSignalShouldReportMetadataUnknownForUnknownReceiver() {
        var registry = newRegistry(emptyApi(), List.of());

        var result = ScopeSignalResolver.resolveObjectSignal(
                registry,
                new GdObjectType("UnknownType"),
                "changed"
        );

        var metadataUnknown = assertInstanceOf(ScopeSignalResolver.MetadataUnknown.class, result);
        assertEquals("UnknownType", metadataUnknown.receiverType().getTypeName());
        assertEquals("changed", metadataUnknown.signalName());
    }

    @Test
    @DisplayName("shared object signal resolver should pick nearest GDCC owner")
    void resolveObjectSignalShouldPickNearestGdccOwner() {
        var parentSignal = createSignal("changed", GdStringType.STRING);
        var childSignal = createSignal("changed", GdIntType.INT);

        var parentClass = new LirClassDef("ParentClass", "RefCounted", false, false, Map.of(), List.of(parentSignal), List.of(), List.of());
        var childClass = new LirClassDef("ChildClass", "ParentClass", false, false, Map.of(), List.of(childSignal), List.of(), List.of());

        var registry = newRegistry(emptyApi(), List.of(parentClass, childClass));
        var result = ScopeSignalResolver.resolveObjectSignal(registry, new GdObjectType("ChildClass"), "changed");

        var resolved = assertInstanceOf(ScopeSignalResolver.Resolved.class, result);
        assertEquals(ScopeOwnerKind.GDCC, resolved.signal().ownerKind());
        assertEquals("ChildClass", resolved.signal().ownerClass().getName());
        assertSame(childSignal, resolved.signal().signal());
        assertEquals(List.of("int"), resolved.signal().signalType().parameterTypes().stream().map(GdType::getTypeName).toList());
    }

    @Test
    @DisplayName("shared object signal resolver should follow canonical inner-class superclass names")
    void resolveObjectSignalShouldFollowCanonicalInnerSuperclassNames() {
        var parentSignal = createSignal("changed", GdStringType.STRING);
        var parentClass = new LirClassDef("Outer__sub__Shared", "RefCounted", false, false, Map.of(), List.of(parentSignal), List.of(), List.of());
        var childClass = new LirClassDef("Outer__sub__Leaf", "Outer__sub__Shared", false, false, Map.of(), List.of(), List.of(), List.of());

        var registry = newRegistry(emptyApi(), List.of(parentClass, childClass));
        var result = ScopeSignalResolver.resolveObjectSignal(registry, new GdObjectType("Outer__sub__Leaf"), "changed");

        var resolved = assertInstanceOf(ScopeSignalResolver.Resolved.class, result);
        assertEquals("Outer__sub__Shared", resolved.signal().ownerClass().getName());
        assertSame(parentSignal, resolved.signal().signal());
    }

    @Test
    @DisplayName("shared object signal resolver should classify inherited engine owner")
    void resolveObjectSignalShouldClassifyInheritedEngineOwner() {
        var nodeClass = createEngineClass("Node", "Object", List.of(createEngineSignal("ready", GdStringType.STRING)));
        var userClass = new LirClassDef("MyClass", "Node", false, false, Map.of(), List.of(), List.of(), List.of());
        var registry = newRegistry(apiWith(List.of(), List.of(nodeClass)), List.of(userClass));

        var result = ScopeSignalResolver.resolveObjectSignal(registry, new GdObjectType("MyClass"), "ready");
        var resolved = assertInstanceOf(ScopeSignalResolver.Resolved.class, result);

        assertEquals(ScopeOwnerKind.ENGINE, resolved.signal().ownerKind());
        assertEquals("Node", resolved.signal().ownerClass().getName());
        assertEquals("ready", resolved.signal().signal().getName());
        assertEquals(List.of("String"), resolved.signal().signalType().parameterTypes().stream().map(GdType::getTypeName).toList());
    }

    @Test
    @DisplayName("shared instance signal resolver should reject builtin receiver directly")
    void resolveInstanceSignalShouldRejectBuiltinReceiverDirectly() {
        var stringBuiltin = new ExtensionBuiltinClass(
                "String",
                false,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
        var registry = newRegistry(apiWith(List.of(stringBuiltin), List.of()), List.of());

        var result = ScopeSignalResolver.resolveInstanceSignal(registry, GdStringType.STRING, "changed");
        var failed = assertInstanceOf(ScopeSignalResolver.Failed.class, result);

        assertEquals(ScopeSignalResolver.FailureKind.UNSUPPORTED_RECEIVER_KIND, failed.kind());
        assertEquals("String", failed.receiverType().getTypeName());
        assertEquals(List.of(), failed.hierarchy());
    }

    @Test
    @DisplayName("shared object signal resolver should report missing signal in known hierarchy")
    void resolveObjectSignalShouldReportMissingSignal() {
        var parentClass = new LirClassDef("ParentClass", "", false, false, Map.of(), List.of(), List.of(), List.of());
        var childClass = new LirClassDef("ChildClass", "ParentClass", false, false, Map.of(), List.of(), List.of(), List.of());
        var registry = newRegistry(emptyApi(), List.of(parentClass, childClass));

        var result = ScopeSignalResolver.resolveObjectSignal(registry, new GdObjectType("ChildClass"), "missing_signal");
        var failed = assertInstanceOf(ScopeSignalResolver.Failed.class, result);

        assertEquals(ScopeSignalResolver.FailureKind.SIGNAL_MISSING, failed.kind());
        assertEquals(List.of("ChildClass", "ParentClass"), failed.hierarchy());
    }

    @Test
    @DisplayName("shared object signal resolver should report missing super metadata")
    void resolveObjectSignalShouldReportMissingSuperMetadata() {
        var classA = new LirClassDef("ClassA", "MissingBase", false, false, Map.of(), List.of(), List.of(), List.of());
        var registry = newRegistry(emptyApi(), List.of(classA));

        var result = ScopeSignalResolver.resolveObjectSignal(registry, new GdObjectType("ClassA"), "changed");
        var failed = assertInstanceOf(ScopeSignalResolver.Failed.class, result);

        assertEquals(ScopeSignalResolver.FailureKind.MISSING_SUPER_METADATA, failed.kind());
        assertEquals("MissingBase", failed.relatedClassName());
    }

    @Test
    @DisplayName("shared object signal resolver should reject stale source-styled inner superclass names")
    void resolveObjectSignalShouldRejectSourceStyledInnerSuperclassNames() {
        var parentSignal = createSignal("changed", GdStringType.STRING);
        var parentClass = new LirClassDef("Outer__sub__Shared", "RefCounted", false, false, Map.of(), List.of(parentSignal), List.of(), List.of());
        var childClass = new LirClassDef("Outer__sub__Leaf", "Shared", false, false, Map.of(), List.of(), List.of(), List.of());

        var registry = newRegistry(emptyApi(), List.of(parentClass, childClass));
        var result = ScopeSignalResolver.resolveObjectSignal(registry, new GdObjectType("Outer__sub__Leaf"), "changed");

        var failed = assertInstanceOf(ScopeSignalResolver.Failed.class, result);
        assertEquals(ScopeSignalResolver.FailureKind.MISSING_SUPER_METADATA, failed.kind());
        assertEquals("Shared", failed.relatedClassName());
    }

    @Test
    @DisplayName("shared object signal resolver should report inheritance cycle")
    void resolveObjectSignalShouldReportInheritanceCycle() {
        var classA = new LirClassDef("ClassA", "ClassB", false, false, Map.of(), List.of(), List.of(), List.of());
        var classB = new LirClassDef("ClassB", "ClassA", false, false, Map.of(), List.of(), List.of(), List.of());
        var registry = newRegistry(emptyApi(), List.of(classA, classB));

        var result = ScopeSignalResolver.resolveObjectSignal(registry, new GdObjectType("ClassA"), "changed");
        var failed = assertInstanceOf(ScopeSignalResolver.Failed.class, result);

        assertEquals(ScopeSignalResolver.FailureKind.INHERITANCE_CYCLE, failed.kind());
        assertTrue(failed.hierarchy().contains("ClassA"));
        assertTrue(failed.hierarchy().contains("ClassB"));
    }

    @Test
    @DisplayName("shared instance signal resolver should reject variant receiver instead of guessing dynamic signal")
    void resolveInstanceSignalShouldRejectVariantReceiver() {
        var registry = newRegistry(emptyApi(), List.of());

        var result = ScopeSignalResolver.resolveInstanceSignal(registry, GdVariantType.VARIANT, "changed");
        var failed = assertInstanceOf(ScopeSignalResolver.Failed.class, result);

        assertEquals(ScopeSignalResolver.FailureKind.UNSUPPORTED_RECEIVER_KIND, failed.kind());
        assertEquals("Variant", failed.receiverType().getTypeName());
    }

    private static LirSignalDef createSignal(String name, GdType... parameterTypes) {
        var signal = new LirSignalDef(name);
        for (var index = 0; index < parameterTypes.length; index++) {
            signal.addParameter(new LirParameterDef("arg" + index, parameterTypes[index], null, signal));
        }
        return signal;
    }

    private static ExtensionGdClass.SignalInfo createEngineSignal(String name, GdType... parameterTypes) {
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

    private static ExtensionGdClass createEngineClass(String name,
                                                      String superName,
                                                      List<ExtensionGdClass.SignalInfo> signals) {
        return new ExtensionGdClass(name, false, true, superName, "core", List.of(), List.of(), signals, List.of(), List.of());
    }

    private static ClassRegistry newRegistry(ExtensionAPI api, List<LirClassDef> gdccClasses) {
        var registry = new ClassRegistry(api);
        for (var gdccClass : gdccClasses) {
            registry.addGdccClass(gdccClass);
        }
        return registry;
    }

    private static ExtensionAPI apiWith(List<ExtensionBuiltinClass> builtinClasses,
                                        List<ExtensionGdClass> gdClasses) {
        return new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), builtinClasses, gdClasses, List.of(), List.of());
    }

    private static ExtensionAPI emptyApi() {
        return apiWith(List.of(), List.of());
    }
}
