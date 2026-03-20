package dev.superice.gdcc.frontend.sema.analyzer;

import dev.superice.gdcc.gdextension.ExtensionAPI;
import dev.superice.gdcc.gdextension.ExtensionApiLoader;
import dev.superice.gdcc.gdextension.ExtensionGdClass;
import dev.superice.gdcc.scope.ClassRegistry;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

final class FrontendAnalyzerTestRegistrySupport {
    private FrontendAnalyzerTestRegistrySupport() {
    }

    static @NotNull ClassRegistry registryWithInheritedPropertyInitializerBase() throws Exception {
        var api = ExtensionApiLoader.loadDefault();
        var classes = new ArrayList<>(api.classes());
        classes.add(new ExtensionGdClass(
                "PropertyInitializerBase",
                false,
                true,
                "RefCounted",
                "core",
                List.of(),
                List.of(
                        new ExtensionGdClass.ClassMethod(
                                "read",
                                false,
                                false,
                                false,
                                false,
                                0,
                                List.of(),
                                new ExtensionGdClass.ClassMethod.ClassMethodReturn("int"),
                                List.of()
                        ),
                        new ExtensionGdClass.ClassMethod(
                                "helper",
                                false,
                                false,
                                true,
                                false,
                                0,
                                List.of(),
                                new ExtensionGdClass.ClassMethod.ClassMethodReturn("int"),
                                List.of()
                        )
                ),
                List.of(new ExtensionGdClass.SignalInfo("changed", List.of())),
                List.of(new ExtensionGdClass.PropertyInfo("payload", "int", true, true, "0")),
                List.of()
        ));
        return new ClassRegistry(new ExtensionAPI(
                api.header(),
                api.builtinClassSizes(),
                api.builtinClassMemberOffsets(),
                api.globalEnums(),
                api.utilityFunctions(),
                api.builtinClasses(),
                List.copyOf(classes),
                api.singletons(),
                api.nativeStructures()
        ));
    }
}
