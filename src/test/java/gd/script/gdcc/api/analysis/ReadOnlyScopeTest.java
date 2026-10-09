package gd.script.gdcc.api.analysis;

import gd.script.gdcc.api.API;
import gd.script.gdcc.scope.ResolveRestriction;
import gd.script.gdcc.scope.Scope;
import gd.script.gdcc.scope.ScopeLookupStatus;
import org.junit.jupiter.api.Test;

import static gd.script.gdcc.api.analysis.QueryTestSupport.file;
import static gd.script.gdcc.api.analysis.QueryTestSupport.files;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Regression anchors for the `ReadOnlyScope` facade: the wrapper must preserve the
/// delegate's OWN chain-walking semantics — in particular `ClassScope` skipping consecutive
/// outer class scopes — instead of falling back to the interface default protocol, and it must
/// close the parent-chain mutator.
class ReadOnlyScopeTest {
    private static final String SOURCE = """
            class_name ScopeOuter
            extends RefCounted
            
            var outer_var: int = 1
            
            func outer_func() -> void:
                pass
            
            class Inner:
                var inner_var: int = 2
            
                func use() -> void:
                    pass
            """;

    @Test
    void facadePreservesClassScopeChainSkippingAndClosesMutators() {
        var api = new API();
        try {
            var snapshot = QueryTestSupport.analyze(api, "scope_facade", files(file("/src/outer.gd", SOURCE)));
            Scope innerClassScope = null;
            for (var scope : snapshot.analysisData().scopesByAst().values()) {
                var currentClass = scope.currentClassOrNull();
                if (currentClass != null && currentClass.getName().contains("Inner")) {
                    innerClassScope = scope;
                    break;
                }
            }
            assertNotNull(innerClassScope, "fixture must publish the inner class scope");
            var facade = ReadOnlyScope.wrap(innerClassScope);

            // Chain-walking lookup must run on the delegate: inner classes do NOT see outer
            // class members (ClassScope skips consecutive outer class scopes). The interface
            // default protocol would wrongly find them through the wrapped parent chain.
            assertEquals(
                    ScopeLookupStatus.NOT_FOUND,
                    facade.resolveValue("outer_var", ResolveRestriction.unrestricted()).status()
            );
            assertEquals(
                    ScopeLookupStatus.NOT_FOUND,
                    facade.resolveFunctions("outer_func", ResolveRestriction.unrestricted()).status()
            );
            // ...while the inner class's own members stay reachable through the same chain.
            assertEquals(
                    ScopeLookupStatus.FOUND_ALLOWED,
                    facade.resolveValue("inner_var", ResolveRestriction.unrestricted()).status()
            );

            // The parent chain is exposed only as recursively wrapped read-only views, and the
            // mutator is closed everywhere.
            assertThrows(UnsupportedOperationException.class, () -> facade.setParentScope(null));
            var parent = facade.getParentScope();
            assertNotNull(parent);
            assertInstanceOf(ReadOnlyScope.class, parent);
            assertThrows(UnsupportedOperationException.class, () -> parent.setParentScope(null));
        } finally {
            api.close();
        }
    }
}
