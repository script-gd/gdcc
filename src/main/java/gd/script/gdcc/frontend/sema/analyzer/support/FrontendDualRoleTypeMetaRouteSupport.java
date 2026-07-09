package gd.script.gdcc.frontend.sema.analyzer.support;

import dev.superice.gdparser.frontend.ast.AttributeCallStep;
import dev.superice.gdparser.frontend.ast.AttributeExpression;
import dev.superice.gdparser.frontend.ast.AttributePropertyStep;
import dev.superice.gdparser.frontend.ast.AttributeStep;
import dev.superice.gdparser.frontend.ast.AttributeSubscriptStep;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import gd.script.gdcc.frontend.sema.FrontendModuleSkeleton;
import gd.script.gdcc.frontend.sema.resolver.FrontendVisibleValueResolution;
import gd.script.gdcc.frontend.sema.resolver.FrontendVisibleValueStatus;
import gd.script.gdcc.gdextension.ExtensionGdClass;
import gd.script.gdcc.scope.ClassDef;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.scope.ResolveRestriction;
import gd.script.gdcc.scope.Scope;
import gd.script.gdcc.scope.ScopeLookupResult;
import gd.script.gdcc.scope.ScopeTypeMeta;
import gd.script.gdcc.scope.ScopeTypeMetaKind;
import gd.script.gdcc.scope.ScopeValueKind;
import gd.script.gdcc.type.GdObjectType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Objects;

/// Shared route bias for names that are both autoload singletons and type-meta receivers.
public final class FrontendDualRoleTypeMetaRouteSupport {
    private FrontendDualRoleTypeMetaRouteSupport() {
    }

    public static @Nullable ScopeTypeMeta resolveBiasedTypeMeta(
            @NotNull AttributeExpression attributeExpression,
            @NotNull FrontendVisibleValueResolution valueResolution,
            @NotNull Scope currentScope,
            @NotNull ResolveRestriction restriction,
            @NotNull FrontendModuleSkeleton moduleSkeleton,
            @NotNull ClassRegistry classRegistry
    ) {
        Objects.requireNonNull(attributeExpression, "attributeExpression must not be null");
        Objects.requireNonNull(valueResolution, "valueResolution must not be null");
        Objects.requireNonNull(currentScope, "currentScope must not be null");
        Objects.requireNonNull(restriction, "restriction must not be null");
        Objects.requireNonNull(moduleSkeleton, "moduleSkeleton must not be null");
        Objects.requireNonNull(classRegistry, "classRegistry must not be null");

        if (!(attributeExpression.base() instanceof IdentifierExpression identifierExpression)) {
            return null;
        }
        if (attributeExpression.steps().isEmpty()) {
            return null;
        }
        if (valueResolution.status() != FrontendVisibleValueStatus.FOUND_ALLOWED) {
            return null;
        }
        var visibleValue = valueResolution.visibleValue();
        if (visibleValue == null || visibleValue.kind() != ScopeValueKind.SINGLETON) {
            return null;
        }

        var name = identifierExpression.name();
        var singletonType = classRegistry.findSingletonType(name);
        if (singletonType == null) {
            return null;
        }
        var typeMetaResult = moduleSkeleton.resolveSourceFacingTypeMeta(currentScope, name, restriction);
        if (!typeMetaResult.isAllowed()) {
            return null;
        }
        var typeMeta = typeMetaResult.requireValue();
        if (typeMeta.kind() != ScopeTypeMetaKind.ENGINE_CLASS
                && typeMeta.kind() != ScopeTypeMetaKind.GDCC_CLASS) {
            return null;
        }
        if (!supportsTopLevelTypeMeta(typeMeta)) {
            return null;
        }

        var firstStep = attributeExpression.steps().getFirst();
        var stepName = extractStepName(firstStep);
        if (stepName == null) {
            return null;
        }
        if (firstStep instanceof AttributeCallStep && stepName.equals("new")) {
            return resolvesInSingletonInstanceNamespace(classRegistry, singletonType, stepName) ? null : typeMeta;
        }

        var inTypeMetaStatic = resolvesInTypeMetaStaticNamespace(classRegistry, typeMeta, stepName);
        var inSingletonInstance = resolvesInSingletonInstanceNamespace(classRegistry, singletonType, stepName);
        return inTypeMetaStatic && !inSingletonInstance ? typeMeta : null;
    }

    public static boolean supportsTopLevelTypeMeta(@NotNull ScopeTypeMeta typeMeta) {
        return switch (Objects.requireNonNull(typeMeta, "typeMeta must not be null").kind()) {
            case GDCC_CLASS, ENGINE_CLASS, BUILTIN -> !typeMeta.pseudoType();
            case GLOBAL_ENUM -> typeMeta.declaration() != null;
        };
    }

    public static boolean shouldPreferGlobalEnumTypeMeta(
            @NotNull FrontendVisibleValueResolution valueResolution,
            @NotNull ScopeLookupResult<ScopeTypeMeta> typeMetaResult
    ) {
        Objects.requireNonNull(valueResolution, "valueResolution must not be null");
        Objects.requireNonNull(typeMetaResult, "typeMetaResult must not be null");
        if (valueResolution.status() != FrontendVisibleValueStatus.FOUND_ALLOWED) {
            return false;
        }
        var visibleValue = valueResolution.visibleValue();
        if (visibleValue == null || visibleValue.kind() != ScopeValueKind.GLOBAL_ENUM) {
            return false;
        }
        return typeMetaResult.isAllowed()
                && typeMetaResult.requireValue().kind() == ScopeTypeMetaKind.GLOBAL_ENUM
                && supportsTopLevelTypeMeta(typeMetaResult.requireValue());
    }

    private static @Nullable String extractStepName(@NotNull AttributeStep step) {
        return switch (step) {
            case AttributePropertyStep propertyStep -> propertyStep.name();
            case AttributeCallStep callStep -> callStep.name();
            case AttributeSubscriptStep subscriptStep -> subscriptStep.name();
            default -> null;
        };
    }

    private static boolean resolvesInTypeMetaStaticNamespace(
            @NotNull ClassRegistry classRegistry,
            @NotNull ScopeTypeMeta typeMeta,
            @NotNull String stepName
    ) {
        if (typeMeta.declaration() instanceof ExtensionGdClass engineClass) {
            if (classRegistry.findEngineClassConstantInHierarchy(engineClass.getName(), stepName) != null) {
                return true;
            }
            if (classRegistry.findEngineClassEnumValueInHierarchy(engineClass.getName(), stepName) != null) {
                return true;
            }
        } else if (classRegistry.getClassDef(
                typeMeta.instanceType() instanceof GdObjectType objectType
                        ? objectType
                        : new GdObjectType(typeMeta.canonicalName())
        ) instanceof ExtensionGdClass engineClass) {
            if (classRegistry.findEngineClassConstantInHierarchy(engineClass.getName(), stepName) != null) {
                return true;
            }
            if (classRegistry.findEngineClassEnumValueInHierarchy(engineClass.getName(), stepName) != null) {
                return true;
            }
        }
        return hasStaticMethodInHierarchy(classRegistry, typeMeta, stepName);
    }

    private static boolean resolvesInSingletonInstanceNamespace(
            @NotNull ClassRegistry classRegistry,
            @NotNull GdObjectType singletonType,
            @NotNull String stepName
    ) {
        return hasInstanceMethodInHierarchy(classRegistry, singletonType, stepName)
                || hasInstancePropertyInHierarchy(classRegistry, singletonType, stepName)
                || hasSignalInHierarchy(classRegistry, singletonType, stepName);
    }

    private static boolean hasStaticMethodInHierarchy(
            @NotNull ClassRegistry classRegistry,
            @NotNull ScopeTypeMeta typeMeta,
            @NotNull String stepName
    ) {
        ClassDef current = classRegistry.resolveClassDefFromTypeMeta(typeMeta);
        var visited = new HashSet<String>();
        while (current != null && visited.add(current.getName())) {
            var found = current.getFunctions().stream()
                    .anyMatch(fn -> fn.getName().equals(stepName) && fn.isStatic());
            if (found) {
                return true;
            }
            current = classRegistry.resolveSuperclass(current);
        }
        return false;
    }

    private static boolean hasInstanceMethodInHierarchy(
            @NotNull ClassRegistry classRegistry,
            @NotNull GdObjectType singletonType,
            @NotNull String stepName
    ) {
        ClassDef current = classRegistry.getClassDef(singletonType);
        var visited = new HashSet<String>();
        while (current != null && visited.add(current.getName())) {
            var found = current.getFunctions().stream()
                    .anyMatch(fn -> fn.getName().equals(stepName) && !fn.isStatic());
            if (found) {
                return true;
            }
            current = classRegistry.resolveSuperclass(current);
        }
        return false;
    }

    private static boolean hasInstancePropertyInHierarchy(
            @NotNull ClassRegistry classRegistry,
            @NotNull GdObjectType singletonType,
            @NotNull String stepName
    ) {
        ClassDef current = classRegistry.getClassDef(singletonType);
        var visited = new HashSet<String>();
        while (current != null && visited.add(current.getName())) {
            var found = current.getProperties().stream()
                    .anyMatch(prop -> prop.getName().equals(stepName) && !prop.isStatic());
            if (found) {
                return true;
            }
            current = classRegistry.resolveSuperclass(current);
        }
        return false;
    }

    private static boolean hasSignalInHierarchy(
            @NotNull ClassRegistry classRegistry,
            @NotNull GdObjectType singletonType,
            @NotNull String stepName
    ) {
        ClassDef current = classRegistry.getClassDef(singletonType);
        var visited = new HashSet<String>();
        while (current != null && visited.add(current.getName())) {
            var found = current.getSignals().stream()
                    .anyMatch(signal -> signal.getName().equals(stepName));
            if (found) {
                return true;
            }
            current = classRegistry.resolveSuperclass(current);
        }
        return false;
    }
}
