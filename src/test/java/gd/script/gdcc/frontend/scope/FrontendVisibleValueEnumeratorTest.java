package gd.script.gdcc.frontend.scope;

import dev.superice.gdparser.frontend.ast.DeclarationKind;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.Parameter;
import dev.superice.gdparser.frontend.ast.PassStatement;
import dev.superice.gdparser.frontend.ast.Point;
import dev.superice.gdparser.frontend.ast.Range;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import gd.script.gdcc.frontend.sema.resolver.FrontendVisibleValueEnumerator;
import gd.script.gdcc.scope.Scope;
import gd.script.gdcc.scope.ScopeValue;
import gd.script.gdcc.scope.ScopeValueKind;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdStringType;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Contract tests for `Scope.valuesHere()` / `Scope.collectVisibleValues()` and the
/// declaration-after-use byte-order filtering of `FrontendVisibleValueEnumerator`
/// (LSP foundation plan §2.5 identifier-prefix enumeration).
class FrontendVisibleValueEnumeratorTest {
    @Test
    void blockScopeEnumeratesLocalsInDeclarationOrder() {
        var block = new BlockScope(new CallableScope(FrontendScopeTestSupport.createRegistry(), CallableScopeKind.FUNCTION_DECLARATION), BlockScopeKind.FUNCTION_BODY);
        block.defineLocal("a", GdIntType.INT, localVar("a", 10, 20));
        block.defineLocal("b", GdIntType.INT, localVar("b", 21, 30));

        var names = FrontendVisibleValueEnumerator.enumerateVisibleValues(block, useSite(100)).stream()
                .map(ScopeValue::name)
                .toList();
        assertEquals(List.of("a", "b"), names);
    }

    @Test
    void declarationAfterUseIsFilteredButEarlierLocalSurvives() {
        var block = new BlockScope(new CallableScope(FrontendScopeTestSupport.createRegistry(), CallableScopeKind.FUNCTION_DECLARATION), BlockScopeKind.FUNCTION_BODY);
        block.defineLocal("before", GdIntType.INT, localVar("before", 10, 20));
        block.defineLocal("after", GdIntType.INT, localVar("after", 500, 510));

        var names = FrontendVisibleValueEnumerator.enumerateVisibleValues(block, useSite(100)).stream()
                .map(ScopeValue::name)
                .toList();
        assertEquals(List.of("before"), names, "locals declared after the use site must be filtered");
    }

    @Test
    void capturesFollowOuterDeclarationOrderLikeLocals() {
        var callable = new CallableScope(FrontendScopeTestSupport.createRegistry(), CallableScopeKind.FUNCTION_DECLARATION);
        callable.defineCapture("early", GdIntType.INT, localVar("early", 10, 20));
        callable.defineCapture("late", GdIntType.INT, localVar("late", 500, 510));

        var names = FrontendVisibleValueEnumerator.enumerateVisibleValues(callable, useSite(100)).stream()
                .map(ScopeValue::name)
                .toList();
        assertEquals(
                List.of("early"),
                names,
                "captures must follow the same declaration-after-use filter as locals (resolver parity)"
        );
    }

    @Test
    void parametersStayVisibleRegardlessOfSourceOrder() {
        var callable = new CallableScope(FrontendScopeTestSupport.createRegistry(), CallableScopeKind.FUNCTION_DECLARATION);
        // A parameter "declared" after the use site is not order-sensitive: production parameters
        // carry a `Parameter` node, which the byte-order filter exempts (resolver parity).
        callable.defineParameter("param", GdIntType.INT, parameter("param", 500, 510));
        var body = new BlockScope(callable, BlockScopeKind.FUNCTION_BODY);
        body.defineLocal("local", GdIntType.INT, localVar("local", 10, 20));

        var names = FrontendVisibleValueEnumerator.enumerateVisibleValues(body, useSite(100)).stream()
                .map(ScopeValue::name)
                .toList();
        assertEquals(List.of("local", "param"), names);
    }

    @Test
    void filteredInnerLocalDoesNotShadowVisibleOuterBinding() {
        var registry = FrontendScopeTestSupport.createRegistry();
        var callable = new CallableScope(registry, CallableScopeKind.FUNCTION_DECLARATION);
        var outer = new BlockScope(callable, BlockScopeKind.FUNCTION_BODY);
        var outerX = localVar("x", 10, 20);
        outer.defineLocal("x", GdIntType.INT, outerX);
        var inner = new BlockScope(outer, BlockScopeKind.IF_BODY);
        inner.defineLocal("x", GdStringType.STRING, localVar("x", 500, 510));

        var values = FrontendVisibleValueEnumerator.enumerateVisibleValues(inner, useSite(100));
        assertEquals(1, values.size());
        // The inner `x` is declaration-after-use and filtered; the outer `x` stays visible instead
        // of being shadowed by an invisible binding.
        assertSame(outerX, values.getFirst().declaration());
    }

    @Test
    void classScopeEnumeratesDirectAndInheritedMembersWithNearestWinning() {
        var registry = FrontendScopeTestSupport.createRegistry();
        var parentProperty = FrontendScopeTestSupport.createProperty("speed", GdStringType.STRING);
        var parent = FrontendScopeTestSupport.createClass(
                "BaseHero",
                "Object",
                List.of(parentProperty),
                List.of()
        );
        var childProperty = FrontendScopeTestSupport.createProperty("speed", GdIntType.INT);
        var child = FrontendScopeTestSupport.createClass(
                "Hero",
                "BaseHero",
                List.of(childProperty, FrontendScopeTestSupport.createProperty("hp", GdIntType.INT)),
                List.of()
        );
        registry.addGdccClass(parent);
        registry.addGdccClass(child);

        var values = new ClassScope(registry, registry, child).valuesHere().stream()
                .map(ScopeValue::name)
                .toList();
        assertTrue(values.containsAll(List.of("hp", "speed")), "direct members must be enumerated");
        var speedValue = new ClassScope(registry, registry, child).valuesHere().stream()
                .filter(value -> value.name().equals("speed"))
                .findFirst()
                .orElseThrow();
        assertSame(childProperty, speedValue.declaration(), "the direct member shadows the inherited one");
        // Inherited-only names stay visible: add one to the parent and re-check.
        var parentWithExtra = FrontendScopeTestSupport.createClass(
                "BaseHero2",
                "Object",
                List.of(FrontendScopeTestSupport.createProperty("base_only", GdIntType.INT)),
                List.of()
        );
        registry.addGdccClass(parentWithExtra);
        var grandChild = FrontendScopeTestSupport.createClass("Hero2", "BaseHero2", List.of(), List.of());
        registry.addGdccClass(grandChild);
        var grandChildNames = new ClassScope(registry, registry, grandChild).valuesHere().stream()
                .map(ScopeValue::name)
                .toList();
        assertEquals(List.of("base_only"), grandChildNames);
    }

    @Test
    void innerClassEnumerationSkipsOuterClassScopeValues() {
        var registry = FrontendScopeTestSupport.createRegistry();
        var outerClass = FrontendScopeTestSupport.createClass(
                "Outer",
                "Object",
                List.of(FrontendScopeTestSupport.createProperty("outer_prop", GdIntType.INT)),
                List.of()
        );
        var innerClass = FrontendScopeTestSupport.createClass(
                "Outer.Inner",
                "Object",
                List.of(FrontendScopeTestSupport.createProperty("inner_prop", GdIntType.INT)),
                List.of()
        );
        registry.addGdccClass(outerClass);
        registry.addGdccClass(innerClass);
        var outerScope = new ClassScope(registry, registry, outerClass);
        var innerScope = new ClassScope(outerScope, registry, innerClass);

        // Mirrors `resolveValue`: inner-class value enumeration must not leak outer class members.
        var names = innerScope.collectVisibleValues().stream().map(ScopeValue::name).toList();
        assertEquals(List.of("inner_prop"), names);
    }

    @Test
    void emptyChainYieldsNoValues() {
        var registry = FrontendScopeTestSupport.createRegistry();
        var empty = new BlockScope(registry, BlockScopeKind.BLOCK_STATEMENT);
        assertTrue(FrontendVisibleValueEnumerator.enumerateVisibleValues(empty, useSite(0)).isEmpty());
    }

    private static @NotNull VariableDeclaration localVar(@NotNull String name, int startByte, int endByte) {
        return new VariableDeclaration(
                DeclarationKind.VAR,
                name,
                null,
                null,
                false,
                null,
                new Range(startByte, endByte, new Point(0, startByte), new Point(0, endByte))
        );
    }

    private static @NotNull Node useSite(int startByte) {
        return new PassStatement(new Range(startByte, startByte, new Point(0, startByte), new Point(0, startByte)));
    }

    private static @NotNull Parameter parameter(@NotNull String name, int startByte, int endByte) {
        return new Parameter(name, null, null, false, new Range(startByte, endByte, new Point(0, startByte), new Point(0, endByte)));
    }
}
