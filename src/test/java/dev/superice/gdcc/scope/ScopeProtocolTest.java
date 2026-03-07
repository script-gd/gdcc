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
import static org.junit.jupiter.api.Assertions.assertNull;
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

        var rootFunctions = List.of(new TestFunctionDef("foo", "root"));
        var middleFunctions = List.of(new TestFunctionDef("foo", "middle"));
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

    private static final class TestScope implements Scope {
        private final Map<String, ScopeValue> valuesByName = new HashMap<>();
        private final Map<String, List<? extends FunctionDef>> functionsByName = new HashMap<>();
        private final Map<String, ScopeTypeMeta> typeMetasByName = new HashMap<>();
        private @Nullable Scope parentScope;

        void defineValue(@NotNull ScopeValue value) {
            valuesByName.put(value.name(), value);
        }

        void defineFunctions(@NotNull String name, @NotNull List<? extends FunctionDef> functions) {
            functionsByName.put(name, List.copyOf(functions));
        }

        void defineTypeMeta(@NotNull ScopeTypeMeta typeMeta) {
            typeMetasByName.put(typeMeta.name(), typeMeta);
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
        public @Nullable ScopeValue resolveValueHere(@NotNull String name) {
            return valuesByName.get(name);
        }

        @Override
        public @NotNull List<? extends FunctionDef> resolveFunctionsHere(@NotNull String name) {
            return functionsByName.getOrDefault(name, List.of());
        }

        @Override
        public @Nullable ScopeTypeMeta resolveTypeMetaHere(@NotNull String name) {
            return typeMetasByName.get(name);
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
