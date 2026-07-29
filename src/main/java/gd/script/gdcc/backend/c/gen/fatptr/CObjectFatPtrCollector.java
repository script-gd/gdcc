package gd.script.gdcc.backend.c.gen.fatptr;

import gd.script.gdcc.backend.c.gen.binding.ModuleLocalGodotBinding;
import gd.script.gdcc.backend.c.gen.binding.usage.EngineConstructorUsage;
import gd.script.gdcc.backend.c.gen.insn.BackendMethodCallResolver;
import gd.script.gdcc.lir.LirModule;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdArrayType;
import gd.script.gdcc.type.GdDictionaryType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdType;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/// Deterministic module-level collector for object fat pointer typedef declarations.
/// Collects every static object surface that needs a `gdcc_<Type>_fat_ptr` typedef and helpers.
public final class CObjectFatPtrCollector {
    private final @NotNull ClassRegistry classRegistry;
    private final @NotNull TreeMap<String, ObjectFatPtrSpec> specsByFatPtrTypeName = new TreeMap<>();

    private CObjectFatPtrCollector(@NotNull ClassRegistry classRegistry) {
        this.classRegistry = classRegistry;
    }

    public static @NotNull List<ObjectFatPtrSpec> collect(
            @NotNull LirModule module,
            @NotNull ClassRegistry classRegistry
    ) {
        return collect(module, classRegistry, List.of(), List.of(), List.of());
    }

    /// Also walks engine method/constructor/module-local binding surfaces so exact-engine helpers
    /// and generated wrappers share the same per-type fat pointer declarations.
    public static @NotNull List<ObjectFatPtrSpec> collect(
            @NotNull LirModule module,
            @NotNull ClassRegistry classRegistry,
            @NotNull List<BackendMethodCallResolver.ResolvedMethodCall> usedEngineMethods,
            @NotNull List<EngineConstructorUsage> usedEngineConstructors,
            @NotNull List<ModuleLocalGodotBinding> usedModuleLocalBindings
    ) {
        var collector = new CObjectFatPtrCollector(classRegistry);
        collector.collectModuleSurfaces(module);
        collector.collectEngineBindingSurfaces(usedEngineMethods, usedEngineConstructors, usedModuleLocalBindings);
        return List.copyOf(collector.specsByFatPtrTypeName.values());
    }

    private void collectModuleSurfaces(@NotNull LirModule module) {
        for (var classDef : module.getClassDefs()) {
            var className = classDef.getName();
            addType(new GdObjectType(className), "class '" + className + "' self type");
            for (var property : classDef.getProperties()) {
                addType(property.getType(), "property '" + className + "." + property.getName() + "'");
            }
            for (var function : classDef.getFunctions()) {
                var surfacePrefix = "function '" + className + "." + function.getName() + "'";
                addType(function.getReturnType(), surfacePrefix + " return");
                for (var parameter : function.getParameters()) {
                    addType(parameter.getType(), surfacePrefix + " parameter '" + parameter.getName() + "'");
                }
                for (var capture : sortedByName(function.getCaptures())) {
                    addType(capture.getValue().type(), surfacePrefix + " capture '" + capture.getKey() + "'");
                }
                for (var variable : sortedByName(function.getVariables())) {
                    addType(variable.getValue().type(), surfacePrefix + " variable '" + variable.getKey() + "'");
                }
            }
        }
    }

    private void collectEngineBindingSurfaces(
            @NotNull List<BackendMethodCallResolver.ResolvedMethodCall> usedEngineMethods,
            @NotNull List<EngineConstructorUsage> usedEngineConstructors,
            @NotNull List<ModuleLocalGodotBinding> usedModuleLocalBindings
    ) {
        for (var resolved : usedEngineMethods) {
            var surfacePrefix = "engine method '" + resolved.ownerClassName() + "." + resolved.methodName() + "'";
            addType(resolved.ownerType(), surfacePrefix + " owner");
            addType(resolved.returnType(), surfacePrefix + " return");
            for (var parameter : resolved.parameters()) {
                addType(parameter.type(), surfacePrefix + " parameter '" + parameter.name() + "'");
            }
        }
        for (var constructor : usedEngineConstructors) {
            addType(new GdObjectType(constructor.className()), "engine constructor '" + constructor.className() + "'");
        }
        for (var binding : usedModuleLocalBindings) {
            if (binding instanceof ModuleLocalGodotBinding.Singleton singleton) {
                addType(
                        new GdObjectType(singleton.returnTypeName()),
                        "singleton '" + singleton.lookupName() + "' return"
                );
            }
        }
    }

    private void addType(@NotNull GdType type, @NotNull String surface) {
        switch (type) {
            case GdObjectType objectType -> addSpec(ObjectFatPtrSpec.forObjectType(classRegistry, objectType, surface));
            case GdArrayType arrayType -> addType(arrayType.getValueType(), surface + " array element");
            case GdDictionaryType dictionaryType -> {
                addType(dictionaryType.getKeyType(), surface + " dictionary key");
                addType(dictionaryType.getValueType(), surface + " dictionary value");
            }
            default -> {
            }
        }
    }

    private void addSpec(@NotNull ObjectFatPtrSpec spec) {
        var existing = specsByFatPtrTypeName.get(spec.fatPtrTypeName());
        if (existing == null) {
            specsByFatPtrTypeName.put(spec.fatPtrTypeName(), spec);
            return;
        }
        if (!existing.canonicalClassName().equals(spec.canonicalClassName())) {
            throw new IllegalStateException(
                    "Object fat pointer type name '" + spec.fatPtrTypeName() + "' collides between '" +
                            existing.canonicalClassName() + "' and '" + spec.canonicalClassName() + "'"
            );
        }
    }

    private static <V> @NotNull List<Map.Entry<String, V>> sortedByName(@NotNull Map<String, V> map) {
        return map.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .toList();
    }
}
