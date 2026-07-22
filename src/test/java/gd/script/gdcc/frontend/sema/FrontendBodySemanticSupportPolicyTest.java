package gd.script.gdcc.frontend.sema;

import gd.script.gdcc.frontend.scope.BlockScopeKind;
import gd.script.gdcc.frontend.scope.CallableScopeKind;
import gd.script.gdcc.frontend.sema.resolver.FrontendVisibleValueDomain;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class FrontendBodySemanticSupportPolicyTest {
    @Test
    void executableBlockKindsPublishInventoryAndEnterSuiteResolver() {
        var supportedKinds = EnumSet.of(
                BlockScopeKind.BLOCK_STATEMENT,
                BlockScopeKind.FUNCTION_BODY,
                BlockScopeKind.CONSTRUCTOR_BODY,
                BlockScopeKind.IF_BODY,
                BlockScopeKind.ELIF_BODY,
                BlockScopeKind.ELSE_BODY,
                BlockScopeKind.WHILE_BODY,
                BlockScopeKind.FOR_BODY
        );

        for (var kind : BlockScopeKind.values()) {
            var policy = FrontendBodySemanticSupportPolicy.forBlockScopeKind(kind);
            assertEquals(supportedKinds.contains(kind), policy.publishesLexicalInventory(), kind.name());
            assertEquals(supportedKinds.contains(kind), policy.entersSuiteResolver(), kind.name());
            if (supportedKinds.contains(kind)) {
                assertEquals(FrontendVisibleValueDomain.EXECUTABLE_BODY, policy.visibleValueDomain(), kind.name());
            }
        }
    }

    @Test
    void unsupportedScopesKeepPreciseStructuralDomains() {
        assertEquals(
                FrontendBodySemanticSupportPolicy.LAMBDA_SUBTREE,
                FrontendBodySemanticSupportPolicy.forBlockScopeKind(BlockScopeKind.LAMBDA_BODY)
        );
        assertEquals(
                FrontendBodySemanticSupportPolicy.MATCH_SUBTREE,
                FrontendBodySemanticSupportPolicy.forBlockScopeKind(BlockScopeKind.MATCH_SECTION_BODY)
        );
        assertEquals(
                FrontendBodySemanticSupportPolicy.LAMBDA_SUBTREE,
                FrontendBodySemanticSupportPolicy.forCallableScopeKind(CallableScopeKind.LAMBDA_EXPRESSION)
        );

        var deferredPolicies = Set.of(
                FrontendBodySemanticSupportPolicy.LAMBDA_SUBTREE,
                FrontendBodySemanticSupportPolicy.MATCH_SUBTREE,
                FrontendBodySemanticSupportPolicy.BLOCK_LOCAL_CONST_SUBTREE,
                FrontendBodySemanticSupportPolicy.PARAMETER_DEFAULT,
                FrontendBodySemanticSupportPolicy.UNKNOWN_OR_SKIPPED_SUBTREE
        );
        for (var policy : deferredPolicies) {
            assertFalse(policy.publishesLexicalInventory(), policy.name());
            assertFalse(policy.entersSuiteResolver(), policy.name());
            assertNotSame(FrontendVisibleValueDomain.EXECUTABLE_BODY, policy.visibleValueDomain(), policy.name());
        }
    }

    @Test
    void forHeaderUsesOuterExecutableDomainWithoutOwningBodyInventory() {
        var policy = FrontendBodySemanticSupportPolicy.FOR_HEADER;

        assertFalse(policy.publishesLexicalInventory());
        assertFalse(policy.entersSuiteResolver());
        assertEquals(FrontendVisibleValueDomain.EXECUTABLE_BODY, policy.visibleValueDomain());
    }

    @Test
    void bodyEntrySemanticEntryAgreesWithFirstCertificateGateForAllBlockScopeKinds() {
        var supportedKinds = EnumSet.of(
                BlockScopeKind.BLOCK_STATEMENT,
                BlockScopeKind.FUNCTION_BODY,
                BlockScopeKind.CONSTRUCTOR_BODY,
                BlockScopeKind.IF_BODY,
                BlockScopeKind.ELIF_BODY,
                BlockScopeKind.ELSE_BODY,
                BlockScopeKind.WHILE_BODY,
                BlockScopeKind.FOR_BODY
        );

        for (var kind : BlockScopeKind.values()) {
            var policy = FrontendBodySemanticSupportPolicy.forBlockScopeKind(kind);
            assertEquals(supportedKinds.contains(kind), policy.isSupportedSuiteBodyRoot(), kind.name());
        }
    }

    @Test
    void bodyEntrySemanticEntryAgreesWithFirstCertificateGateForAllCallableScopeKinds() {
        var supportedCallableKinds = EnumSet.of(
                CallableScopeKind.FUNCTION_DECLARATION,
                CallableScopeKind.CONSTRUCTOR_DECLARATION
        );

        for (var kind : CallableScopeKind.values()) {
            var policy = FrontendBodySemanticSupportPolicy.forCallableScopeKind(kind);
            assertEquals(supportedCallableKinds.contains(kind), policy.isSupportedSuiteBodyRoot(), kind.name());
        }
    }

    @Test
    void forHeaderIsNotASupportedSuiteBodyRootEvenThoughItUsesExecutableVisibleValueDomain() {
        // FOR_HEADER shares the EXECUTABLE_BODY visible-value domain but must NOT be a body root:
        // this is the canonical case showing body-entry and inventory-publication are distinct questions.
        var policy = FrontendBodySemanticSupportPolicy.FOR_HEADER;

        assertFalse(policy.isSupportedSuiteBodyRoot());
        assertEquals(FrontendVisibleValueDomain.EXECUTABLE_BODY, policy.visibleValueDomain());
    }

    @Test
    void deferredPoliciesAreNotSupportedSuiteBodyRoots() {
        var deferredPolicies = Set.of(
                FrontendBodySemanticSupportPolicy.LAMBDA_SUBTREE,
                FrontendBodySemanticSupportPolicy.MATCH_SUBTREE,
                FrontendBodySemanticSupportPolicy.BLOCK_LOCAL_CONST_SUBTREE,
                FrontendBodySemanticSupportPolicy.PARAMETER_DEFAULT,
                FrontendBodySemanticSupportPolicy.UNKNOWN_OR_SKIPPED_SUBTREE
        );

        for (var policy : deferredPolicies) {
            assertFalse(policy.isSupportedSuiteBodyRoot(), policy.name());
        }
    }

    @Test
    void executableInventorySupportBodyEntryBridgeAgreesWithPolicyForAllBlockScopeKinds() {
        // The bridge consumed by FrontendInterfacePhase.enterSupportedBlock must route through the same
        // body-entry semantic entry as FrontendBodyStructuralCompleteness's first certificate gate, so the
        // two consumers can never diverge even if publishesLexicalInventory and entersSuiteResolver diverge
        // for a future feature.
        for (var kind : BlockScopeKind.values()) {
            var policy = FrontendBodySemanticSupportPolicy.forBlockScopeKind(kind);
            assertEquals(
                    policy.isSupportedSuiteBodyRoot(),
                    FrontendExecutableInventorySupport.isSupportedSuiteBodyRoot(kind),
                    kind.name()
            );
        }
    }
}
