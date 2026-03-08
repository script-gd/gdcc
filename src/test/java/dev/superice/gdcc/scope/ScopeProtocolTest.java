package dev.superice.gdcc.scope;

import dev.superice.gdcc.type.GdIntType;
import dev.superice.gdcc.type.GdStringType;
import dev.superice.gdcc.type.GdType;
import dev.superice.gdcc.type.GdVariantType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnmodifiableView;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ScopeProtocolTest {
    @Test
    void resolveValueRecursesAndPrefersNearestHit() {
        var rootScope = new TestScope();
        var middleScope = new TestScope();
        var leafScope = new TestScope();
        middleScope.setParentScope(rootScope);
        leafScope.setParentScope(middleScope);

        var rootValue = new ScopeValue("global_only", GdVariantType.VARIANT, ScopeValueKind.CONSTANT, "root", true, false);
        var middleValue = new ScopeValue("value", GdIntType.INT, ScopeValueKind.LOCAL, "middle", false, false);
        rootScope.defineValue(rootValue);
        middleScope.defineValue(middleValue);

        assertEquals(middleValue, leafScope.resolveValue("value"));
        assertEquals(rootValue, leafScope.resolveValue("global_only"));
    }

    @Test
    void resolveFunctionsRecursesUntilNearestNonEmptyLayer() {
        var rootScope = new TestScope();
        var middleScope = new TestScope();
        var leafScope = new TestScope();
        middleScope.setParentScope(rootScope);
        leafScope.setParentScope(middleScope);

        var rootFunctions = List.<FunctionDef>of(new TestFunctionDef("foo", "root"));
        var middleFunctions = List.<FunctionDef>of(new TestFunctionDef("foo", "middle"));
        rootScope.defineFunctions("foo", rootFunctions);
        middleScope.defineFunctions("foo", middleFunctions);

        assertEquals(middleFunctions, leafScope.resolveFunctions("foo"));

        var fallbackRootScope = new TestScope();
        var fallbackMiddleScope = new TestScope();
        var fallbackLeafScope = new TestScope();
        fallbackMiddleScope.setParentScope(fallbackRootScope);
        fallbackLeafScope.setParentScope(fallbackMiddleScope);
        fallbackRootScope.defineFunctions("bar", rootFunctions);

        assertEquals(rootFunctions, fallbackLeafScope.resolveFunctions("bar"));
    }

    @Test
    void resolveMissingNameStopsAtRoot() {
        var rootScope = new TestScope();

        assertNull(rootScope.getParentScope());
        assertNull(rootScope.resolveValue("missing"));
        assertTrue(rootScope.resolveFunctions("missing").isEmpty());
        assertNull(rootScope.resolveTypeMeta("missing"));
    }

    @Test
    void resolveTypeMetaRecursesAndStaysStrict() {
        var rootScope = new TestScope();
        var middleScope = new TestScope();
        var leafScope = new TestScope();
        middleScope.setParentScope(rootScope);
        leafScope.setParentScope(middleScope);

        var rootTypeMeta = new ScopeTypeMeta(
                "GlobalType",
                GdStringType.STRING,
                ScopeTypeMetaKind.BUILTIN,
                "root",
                false
        );
        var middleTypeMeta = new ScopeTypeMeta(
                "ScopedType",
                GdIntType.INT,
                ScopeTypeMetaKind.GLOBAL_ENUM,
                "middle",
                true
        );
        rootScope.defineTypeMeta(rootTypeMeta);
        middleScope.defineTypeMeta(middleTypeMeta);

        assertEquals(middleTypeMeta, leafScope.resolveTypeMeta("ScopedType"));
        assertEquals(rootTypeMeta, leafScope.resolveTypeMeta("GlobalType"));
        assertNull(leafScope.resolveTypeMeta("UnknownType"));
    }

    @Test
    void blockedValueStillShadowsOuterValue() {
        var rootScope = new TestScope();
        var middleScope = new TestScope();
        var leafScope = new TestScope();
        middleScope.setParentScope(rootScope);
        leafScope.setParentScope(middleScope);

        var blockedValue = new ScopeValue("player", GdIntType.INT, ScopeValueKind.PROPERTY, "blocked", false, false);
        var outerValue = new ScopeValue("player", GdStringType.STRING, ScopeValueKind.SINGLETON, "outer", true, false);
        middleScope.defineBlockedValue(blockedValue);
        rootScope.defineValue(outerValue);

        var result = leafScope.resolveValue("player", ResolveRestriction.staticContext());
        assertTrue(result.isBlocked());
        assertSame(blockedValue, result.requireValue());
        assertNull(result.allowedValueOrNull());
    }

    @Test
    void blockedFunctionSetStillShadowsOuterFunctions() {
        var rootScope = new TestScope();
        var middleScope = new TestScope();
        var leafScope = new TestScope();
        middleScope.setParentScope(rootScope);
        leafScope.setParentScope(middleScope);

        var blockedFunctions = List.<FunctionDef>of(new TestFunctionDef("tick", "blocked"));
        var outerFunctions = List.<FunctionDef>of(new TestFunctionDef("tick", "outer"));
        middleScope.defineBlockedFunctions("tick", blockedFunctions);
        rootScope.defineFunctions("tick", outerFunctions);

        var result = leafScope.resolveFunctions("tick", ResolveRestriction.staticContext());
        assertTrue(result.isBlocked());
        assertEquals(blockedFunctions, result.requireValue());
    }

    @Test
    void typeMetaLookupStaysRestrictionInvariantAndNeverBlocked() {
        var rootScope = new TestScope();
        var childScope = new TestScope();
        childScope.setParentScope(rootScope);

        var typeMeta = new ScopeTypeMeta("ScopedType", GdIntType.INT, ScopeTypeMetaKind.GLOBAL_ENUM, "enum", true);
        rootScope.defineTypeMeta(typeMeta);

        var unrestrictedResult = childScope.resolveTypeMeta("ScopedType", ResolveRestriction.unrestricted());
        var staticResult = childScope.resolveTypeMeta("ScopedType", ResolveRestriction.staticContext());
        var instanceResult = childScope.resolveTypeMeta("ScopedType", ResolveRestriction.instanceContext());

        assertTrue(unrestrictedResult.isAllowed());
        assertTrue(staticResult.isAllowed());
        assertTrue(instanceResult.isAllowed());
        assertFalse(unrestrictedResult.isBlocked());
        assertFalse(staticResult.isBlocked());
        assertFalse(instanceResult.isBlocked());
        assertEquals(typeMeta, unrestrictedResult.requireValue());
        assertEquals(typeMeta, staticResult.requireValue());
        assertEquals(typeMeta, instanceResult.requireValue());
    }

    private static final class TestScope implements Scope {
        private final Map<String, ScopeLookupResult<ScopeValue>> valuesByName = new HashMap<>();
        private final Map<String, ScopeLookupResult<List<FunctionDef>>> functionsByName = new HashMap<>();
        private final Map<String, ScopeLookupResult<ScopeTypeMeta>> typeMetasByName = new HashMap<>();
        private @Nullable Scope parentScope;

        void defineValue(@NotNull ScopeValue value) {
            valuesByName.put(value.name(), ScopeLookupResult.foundAllowed(value));
        }

        void defineBlockedValue(@NotNull ScopeValue value) {
            valuesByName.put(value.name(), ScopeLookupResult.foundBlocked(value));
        }

        void defineFunctions(@NotNull String name, @NotNull List<FunctionDef> functions) {
            functionsByName.put(name, ScopeLookupResult.foundAllowed(List.copyOf(functions)));
        }

        void defineBlockedFunctions(@NotNull String name, @NotNull List<FunctionDef> functions) {
            functionsByName.put(name, ScopeLookupResult.foundBlocked(List.copyOf(functions)));
        }

        void defineTypeMeta(@NotNull ScopeTypeMeta typeMeta) {
            typeMetasByName.put(typeMeta.name(), ScopeLookupResult.foundAllowed(typeMeta));
        }

        @Override
        public @Nullable Scope getParentScope() {
            return parentScope;
        }

        @Override
        public void setParentScope(@Nullable Scope parentScope) {
            this.parentScope = parentScope;
        }

        @Override
        public @NotNull ScopeLookupResult<ScopeValue> resolveValueHere(
                @NotNull String name,
                @NotNull ResolveRestriction restriction
        ) {
            return valuesByName.getOrDefault(name, ScopeLookupResult.notFound());
        }

        @Override
        public @NotNull ScopeLookupResult<List<FunctionDef>> resolveFunctionsHere(
                @NotNull String name,
                @NotNull ResolveRestriction restriction
        ) {
            return functionsByName.getOrDefault(name, ScopeLookupResult.notFound());
        }

        @Override
        public @NotNull ScopeLookupResult<ScopeTypeMeta> resolveTypeMetaHere(
                @NotNull String name,
                @NotNull ResolveRestriction restriction
        ) {
            return typeMetasByName.getOrDefault(name, ScopeLookupResult.notFound());
        }
    }

    private record TestFunctionDef(
            @NotNull String name,
            @NotNull String ownerTag
    ) implements FunctionDef {
        @Override
        public @NotNull String getName() {
            return name;
        }

        @Override
        public boolean isStatic() {
            return false;
        }

        @Override
        public boolean isAbstract() {
            return false;
        }

        @Override
        public boolean isLambda() {
            return false;
        }

        @Override
        public boolean isVararg() {
            return false;
        }

        @Override
        public boolean isHidden() {
            return false;
        }

        @Override
        public @NotNull @UnmodifiableView Map<String, String> getAnnotations() {
            return Map.of();
        }

        @Override
        public @Nullable ParameterDef getParameter(int index) {
            return null;
        }

        @Override
        public @Nullable ParameterDef getParameter(@NotNull String name) {
            return null;
        }

        @Override
        public int getParameterCount() {
            return 0;
        }

        @Override
        public @NotNull @UnmodifiableView List<? extends ParameterDef> getParameters() {
            return List.of();
        }

        @Override
        public @Nullable CaptureDef getCapture(@NotNull String name) {
            return null;
        }

        @Override
        public int getCaptureCount() {
            return 0;
        }

        @Override
        public @UnmodifiableView Map<String, ? extends CaptureDef> getCaptures() {
            return Map.of();
        }

        @Override
        public @NotNull GdType getReturnType() {
            return GdVariantType.VARIANT;
        }
    }
}
