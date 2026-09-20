package gd.script.gdcc.frontend.sema.analyzer;

import dev.superice.gdparser.frontend.ast.AssignmentExpression;
import dev.superice.gdparser.frontend.ast.AttributeCallStep;
import dev.superice.gdparser.frontend.ast.AttributeExpression;
import dev.superice.gdparser.frontend.ast.AttributePropertyStep;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.MatchStatement;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.Statement;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.diagnostic.DiagnosticSnapshot;
import gd.script.gdcc.frontend.diagnostic.FrontendDiagnostic;
import gd.script.gdcc.frontend.parse.FrontendModule;
import gd.script.gdcc.frontend.parse.FrontendSourceUnit;
import gd.script.gdcc.frontend.parse.GdScriptParserService;
import gd.script.gdcc.frontend.sema.FrontendAnalysisData;
import gd.script.gdcc.frontend.sema.FrontendBindingKind;
import gd.script.gdcc.frontend.sema.FrontendCallResolutionStatus;
import gd.script.gdcc.frontend.sema.FrontendExpressionTypeStatus;
import gd.script.gdcc.frontend.sema.FrontendMatchSupport;
import gd.script.gdcc.frontend.sema.FrontendMemberResolutionStatus;
import gd.script.gdcc.frontend.sema.FrontendReceiverKind;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.scope.GdScriptEnumConstant;
import gd.script.gdcc.scope.GdScriptEnumGroup;
import gd.script.gdcc.type.GdIntType;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Step 5 acceptance for `frontend_enum_plan.md`: enum members resolve through chain binding on
/// three routes (in-class value route, inner-class type-meta static load, cross-class qualified
/// static load plus group continuation), member misses and Dictionary-shaped suffixes keep their
/// pre-existing resolution contracts, and the unsupported boundary for unknown names, class
/// `const`, and nested-class qualifiers stays sealed.
class FrontendEnumChainBindingTest {
    @Test
    void namedEnumMemberAccessPublishesConstantMemberFact() throws Exception {
        var analyzed = analyze("enum_chain_value_route.gd", """
                class_name EnumChainValueRoute
                extends RefCounted

                enum State { IDLE, JUMP = 5 }

                func ping() -> int:
                    var inferred := State.IDLE
                    return State.JUMP
                """);

        assertTrue(analyzed.diagnostics().isEmpty(), () -> "Unexpected diagnostics: " + analyzed.diagnostics());
        var ping = findFunction(analyzed.unit().ast(), "ping");
        var idleStep = findNode(ping, AttributePropertyStep.class, step -> step.name().equals("IDLE"));
        var jumpStep = findNode(ping, AttributePropertyStep.class, step -> step.name().equals("JUMP"));

        var idleMember = analyzed.analysisData().resolvedMembers().get(idleStep);
        assertNotNull(idleMember);
        assertAll(
                () -> assertEquals(FrontendMemberResolutionStatus.RESOLVED, idleMember.status()),
                () -> assertEquals(FrontendBindingKind.CONSTANT, idleMember.bindingKind()),
                () -> assertEquals(GdIntType.INT, idleMember.resultType()),
                () -> {
                    var member = assertInstanceOf(GdScriptEnumConstant.class, idleMember.declarationSite());
                    assertEquals("IDLE", member.memberName());
                    assertEquals("State", member.groupName());
                },
                // `var x := State.IDLE` infers int through the chain member fact.
                () -> {
                    var inferred = findVariable(ping.body().statements(), "inferred");
                    assertEquals(GdIntType.INT, analyzed.analysisData().slotTypes().get(inferred));
                }
        );

        var jumpMember = analyzed.analysisData().resolvedMembers().get(jumpStep);
        assertNotNull(jumpMember);
        assertEquals(FrontendMemberResolutionStatus.RESOLVED, jumpMember.status());
        var jumpChain = findNode(ping, AttributeExpression.class, chain -> chain.steps().contains(jumpStep));
        var jumpType = analyzed.analysisData().expressionTypes().get(jumpChain);
        assertNotNull(jumpType);
        assertAll(
                () -> assertEquals(FrontendExpressionTypeStatus.RESOLVED, jumpType.status()),
                () -> assertEquals(GdIntType.INT, jumpType.publishedType())
        );
    }

    @Test
    void namedEnumGroupKeepsDictionaryMethodRouteAndMethodReference() throws Exception {
        var analyzed = analyze("enum_chain_dictionary_route.gd", """
                class_name EnumChainDictionaryRoute
                extends RefCounted

                enum State { IDLE, JUMP }

                func ping():
                    var names = State.keys()
                    var methodRef = State.keys
                """);

        assertTrue(analyzed.diagnostics().isEmpty(), () -> "Unexpected diagnostics: " + analyzed.diagnostics());
        var ping = findFunction(analyzed.unit().ast(), "ping");

        // Call steps are never intercepted by the enum branch: `State.keys()` keeps the
        // Dictionary method route.
        var keysCall = findNode(ping, AttributeCallStep.class, step -> step.name().equals("keys"));
        var resolvedCall = analyzed.analysisData().resolvedCalls().get(keysCall);
        assertNotNull(resolvedCall);
        assertAll(
                () -> assertEquals(FrontendCallResolutionStatus.RESOLVED, resolvedCall.status()),
                () -> assertEquals(FrontendReceiverKind.INSTANCE, resolvedCall.receiverKind())
        );

        // A member miss on the enum branch falls through to the builtin path, so a bare
        // `State.keys` property read still publishes the Dictionary method reference.
        var keysRead = findNode(ping, AttributePropertyStep.class, step -> step.name().equals("keys"));
        var methodReference = analyzed.analysisData().resolvedMembers().get(keysRead);
        assertNotNull(methodReference);
        assertAll(
                () -> assertEquals(FrontendMemberResolutionStatus.RESOLVED, methodReference.status()),
                () -> assertEquals(FrontendBindingKind.METHOD, methodReference.bindingKind()),
                () -> assertEquals("Callable", methodReference.resultType().getTypeName())
        );
    }

    @Test
    void enumMemberShadowingDictionaryMethodWinsOnPropertyReadOnly() throws Exception {
        var analyzed = analyze("enum_chain_member_shadows_dict_method.gd", """
                class_name EnumChainMemberShadowsDictMethod
                extends RefCounted

                enum State { keys }

                func ping():
                \tvar member = State.keys
                \tvar names = State.keys()
                """);

        assertTrue(analyzed.diagnostics().isEmpty(), () -> "Unexpected diagnostics: " + analyzed.diagnostics());
        var ping = findFunction(analyzed.unit().ast(), "ping");

        // Property read hits the enum member (the enum branch runs before the builtin fallback).
        var keysRead = findNode(ping, AttributePropertyStep.class, step -> step.name().equals("keys"));
        var memberFact = analyzed.analysisData().resolvedMembers().get(keysRead);
        assertNotNull(memberFact);
        assertAll(
                () -> assertEquals(FrontendMemberResolutionStatus.RESOLVED, memberFact.status()),
                () -> assertEquals(FrontendBindingKind.CONSTANT, memberFact.bindingKind()),
                () -> assertEquals(GdIntType.INT, memberFact.resultType()),
                () -> assertInstanceOf(GdScriptEnumConstant.class, memberFact.declarationSite())
        );

        // The call step is untouched by the enum branch and keeps the Dictionary method route.
        var keysCall = findNode(ping, AttributeCallStep.class, step -> step.name().equals("keys"));
        var resolvedCall = analyzed.analysisData().resolvedCalls().get(keysCall);
        assertNotNull(resolvedCall);
        assertEquals(FrontendCallResolutionStatus.RESOLVED, resolvedCall.status());
    }

    @Test
    void namedEnumMemberMissKeepsMemberResolutionDiagnostic() throws Exception {
        var analyzed = analyze("enum_chain_member_miss.gd", """
                class_name EnumChainMemberMiss
                extends RefCounted

                enum State { IDLE }

                func ping():
                    var value = State.MISSING
                """);

        var missStep = findNode(
                findFunction(analyzed.unit().ast(), "ping"),
                AttributePropertyStep.class,
                step -> step.name().equals("MISSING")
        );
        var missMember = analyzed.analysisData().resolvedMembers().get(missStep);
        assertNotNull(missMember);
        assertEquals(FrontendMemberResolutionStatus.FAILED, missMember.status());
        // The miss surfaces through the chain-owned member-resolution category, not the
        // unsupported-route boundary.
        assertEquals(1, diagnosticsByCategory(analyzed.diagnostics(), "sema.member_resolution").size());
        assertTrue(diagnosticsByCategory(analyzed.diagnostics(), "sema.unsupported_chain_route").isEmpty());
    }

    @Test
    void enumMemberSuffixContinuesOnIntReceiver() throws Exception {
        var analyzed = analyze("enum_chain_suffix_int_receiver.gd", """
                class_name EnumChainSuffixIntReceiver
                extends RefCounted

                enum State { IDLE, JUMP }

                func ping():
                    var value = State.IDLE.JUMP
                """);

        var ping = findFunction(analyzed.unit().ast(), "ping");
        var idleStep = findNode(ping, AttributePropertyStep.class, step -> step.name().equals("IDLE"));
        var jumpStep = findNode(ping, AttributePropertyStep.class, step -> step.name().equals("JUMP"));

        var idleMember = analyzed.analysisData().resolvedMembers().get(idleStep);
        assertNotNull(idleMember);
        assertEquals(FrontendMemberResolutionStatus.RESOLVED, idleMember.status());

        // `.JUMP` continues on the int receiver and fails the ordinary member lookup instead of
        // resolving against the enum group again.
        var jumpMember = analyzed.analysisData().resolvedMembers().get(jumpStep);
        assertNotNull(jumpMember);
        assertEquals(FrontendMemberResolutionStatus.FAILED, jumpMember.status());
        assertNotNull(jumpMember.detailReason());
        assertTrue(jumpMember.detailReason().contains("'int'"), jumpMember.detailReason());
        assertEquals(1, diagnosticsByCategory(analyzed.diagnostics(), "sema.member_resolution").size());
    }

    @Test
    void innerClassAccessesOuterEnumMemberViaTypeMetaRoute() throws Exception {
        var analyzed = analyze("enum_chain_inner_class.gd", """
                class_name EnumChainInnerClass
                extends RefCounted

                enum State { IDLE, JUMP = 5 }

                class Inner:
                    func ping() -> int:
                        return State.JUMP
                """);

        assertTrue(analyzed.diagnostics().isEmpty(), () -> "Unexpected diagnostics: " + analyzed.diagnostics());
        var ping = findFunction(analyzed.unit().ast(), "ping");
        var jumpStep = findNode(ping, AttributePropertyStep.class, step -> step.name().equals("JUMP"));
        var member = analyzed.analysisData().resolvedMembers().get(jumpStep);
        assertNotNull(member);
        assertAll(
                () -> assertEquals(FrontendMemberResolutionStatus.RESOLVED, member.status()),
                () -> assertEquals(FrontendBindingKind.CONSTANT, member.bindingKind()),
                // The type-meta static-load route keeps the TYPE_META receiver kind.
                () -> assertEquals(FrontendReceiverKind.TYPE_META, member.receiverKind()),
                () -> assertEquals(GdIntType.INT, member.resultType()),
                () -> {
                    var constant = assertInstanceOf(GdScriptEnumConstant.class, member.declarationSite());
                    assertEquals(5L, constant.value());
                }
        );
    }

    @Test
    void innerClassKeepsBareEnumGroupValueIsolated() throws Exception {
        var analyzed = analyze("enum_chain_inner_isolation.gd", """
                class_name EnumChainInnerIsolation
                extends RefCounted

                enum State { IDLE }

                class Inner:
                    func ping():
                        var copy = State
                """);
        var ping = findFunction(analyzed.unit().ast(), "ping");

        // Bare `State` stays value-isolated: no CONSTANT binding leaks into the inner class and
        // the type-meta misuse surfaces as an expression-level error.
        var stateIdentifier = findNode(
                assertInstanceOf(
                        VariableDeclaration.class,
                        ping.body().statements().getFirst()
                ),
                IdentifierExpression.class,
                identifier -> identifier.name().equals("State")
        );
        var binding = analyzed.analysisData().symbolBindings().get(stateIdentifier);
        assertNotNull(binding);
        assertEquals(FrontendBindingKind.TYPE_META, binding.kind());
        assertEquals(1, diagnosticsByCategory(analyzed.diagnostics(), "sema.expression_resolution").size());
    }

    @Test
    void innerClassEnumGroupCallKeepsPseudoTypeStaticReceiverBoundary() throws Exception {
        var analyzed = analyze("enum_chain_inner_call_boundary.gd", """
                class_name EnumChainInnerCallBoundary
                extends RefCounted

                enum State { IDLE }

                class Inner:
                    func ping():
                        State.keys()
                """);
        var ping = findFunction(analyzed.unit().ast(), "ping");

        // `State.keys()` hits the pseudo-type static receiver boundary owned by call resolution
        // (the pre-existing `UNSUPPORTED_STATIC_RECEIVER` mapping).
        var keysCall = findNode(ping, AttributeCallStep.class, step -> step.name().equals("keys"));
        var resolvedCall = analyzed.analysisData().resolvedCalls().get(keysCall);
        assertNotNull(resolvedCall);
        assertEquals(FrontendCallResolutionStatus.FAILED, resolvedCall.status());
        assertEquals(1, diagnosticsByCategory(analyzed.diagnostics(), "sema.call_resolution").size());
    }

    @Test
    void enumConstantMemberRejectsAssignmentTarget() throws Exception {
        var analyzed = analyze("enum_chain_assignment_rejected.gd", """
                class_name EnumChainAssignmentRejected
                extends RefCounted

                enum State { IDLE }

                func ping():
                    State.IDLE = 5
                """);

        var ping = findFunction(analyzed.unit().ast(), "ping");
        var idleStep = findNode(ping, AttributePropertyStep.class, step -> step.name().equals("IDLE"));
        // The member fact still publishes the compile-time constant shape; the writable-target
        // classification rejects the CONSTANT kind.
        var member = analyzed.analysisData().resolvedMembers().get(idleStep);
        assertNotNull(member);
        assertEquals(FrontendBindingKind.CONSTANT, member.bindingKind());

        var assignment = findNode(ping, AssignmentExpression.class, _ -> true);
        var assignmentType = analyzed.analysisData().expressionTypes().get(assignment);
        assertNotNull(assignmentType);
        assertAll(
                () -> assertEquals(FrontendExpressionTypeStatus.FAILED, assignmentType.status()),
                () -> assertNotNull(assignmentType.detailReason()),
                () -> assertTrue(
                        assignmentType.detailReason().contains("not an assignable"),
                        assignmentType.detailReason()
                )
        );
    }

    @Test
    void crossClassEnumMemberAndGroupResolveViaQualifiedAccess() throws Exception {
        var analyzed = analyze("enum_chain_cross_class.gd", """
                class_name EnumChainCrossClass
                extends RefCounted

                class Base:
                    enum { PARENT_IDLE = 3 }

                class Other extends Base:
                    enum State { IDLE, JUMP = 5 }
                    enum { GROUP_ONLY }

                func ping() -> int:
                    var group = Other.State
                    var member = Other.State.IDLE
                    var anonymous = Other.GROUP_ONLY
                    return Other.PARENT_IDLE
                """);

        assertTrue(analyzed.diagnostics().isEmpty(), () -> "Unexpected diagnostics: " + analyzed.diagnostics());
        var ping = findFunction(analyzed.unit().ast(), "ping");

        var groupStep = findNode(ping, AttributePropertyStep.class, step -> step.name().equals("State"));
        var groupFact = analyzed.analysisData().resolvedMembers().get(groupStep);
        assertNotNull(groupFact);
        assertAll(
                () -> assertEquals(FrontendMemberResolutionStatus.RESOLVED, groupFact.status()),
                () -> assertEquals(FrontendBindingKind.CONSTANT, groupFact.bindingKind()),
                () -> assertEquals("Dictionary", groupFact.resultType().getTypeName()),
                () -> assertInstanceOf(GdScriptEnumGroup.class, groupFact.declarationSite())
        );

        var idleStep = findNode(ping, AttributePropertyStep.class, step -> step.name().equals("IDLE"));
        var idleFact = analyzed.analysisData().resolvedMembers().get(idleStep);
        assertNotNull(idleFact);
        assertAll(
                () -> assertEquals(FrontendMemberResolutionStatus.RESOLVED, idleFact.status()),
                () -> assertEquals(FrontendBindingKind.CONSTANT, idleFact.bindingKind()),
                () -> assertEquals(GdIntType.INT, idleFact.resultType()),
                () -> assertInstanceOf(GdScriptEnumConstant.class, idleFact.declarationSite())
        );

        var anonymousStep = findNode(ping, AttributePropertyStep.class, step -> step.name().equals("GROUP_ONLY"));
        var anonymousFact = analyzed.analysisData().resolvedMembers().get(anonymousStep);
        assertNotNull(anonymousFact);
        assertAll(
                () -> assertEquals(FrontendBindingKind.CONSTANT, anonymousFact.bindingKind()),
                () -> assertEquals(GdIntType.INT, anonymousFact.resultType())
        );

        // Inherited enum members resolve through the same qualified route.
        var parentStep = findNode(ping, AttributePropertyStep.class, step -> step.name().equals("PARENT_IDLE"));
        var parentFact = analyzed.analysisData().resolvedMembers().get(parentStep);
        assertNotNull(parentFact);
        assertAll(
                () -> assertEquals(FrontendBindingKind.CONSTANT, parentFact.bindingKind()),
                () -> {
                    var constant = assertInstanceOf(GdScriptEnumConstant.class, parentFact.declarationSite());
                    assertEquals(3L, constant.value());
                    // Inner-class owner canonical names embed the outer class prefix.
                    assertEquals("EnumChainCrossClass__sub__Base", constant.ownerClassCanonicalName());
                }
        );
    }

    @Test
    void crossModuleEnumAccessResolvesAcrossSourceUnits() throws Exception {
        var analyzed = analyzeAll(List.of(
                new SourceSpec("enum_chain_module_other.gd", """
                        class_name EnumChainModuleOther
                        extends RefCounted

                        enum State { IDLE, JUMP = 5 }
                        enum { GROUP_ONLY }
                        """),
                new SourceSpec("enum_chain_module_consumer.gd", """
                        class_name EnumChainModuleConsumer
                        extends RefCounted

                        func ping() -> int:
                            var member = EnumChainModuleOther.State.JUMP
                            return EnumChainModuleOther.GROUP_ONLY
                        """)
        ));

        assertTrue(analyzed.diagnostics().isEmpty(), () -> "Unexpected diagnostics: " + analyzed.diagnostics());
        var ping = findFunction(analyzed.unit().ast(), "ping");
        var jumpStep = findNode(ping, AttributePropertyStep.class, step -> step.name().equals("JUMP"));
        var jumpFact = analyzed.analysisData().resolvedMembers().get(jumpStep);
        assertNotNull(jumpFact);
        assertAll(
                () -> assertEquals(FrontendMemberResolutionStatus.RESOLVED, jumpFact.status()),
                () -> assertEquals(FrontendBindingKind.CONSTANT, jumpFact.bindingKind()),
                () -> {
                    var constant = assertInstanceOf(GdScriptEnumConstant.class, jumpFact.declarationSite());
                    assertEquals(5L, constant.value());
                }
        );
        var groupOnlyStep = findNode(ping, AttributePropertyStep.class, step -> step.name().equals("GROUP_ONLY"));
        var groupOnlyFact = analyzed.analysisData().resolvedMembers().get(groupOnlyStep);
        assertNotNull(groupOnlyFact);
        assertEquals(FrontendBindingKind.CONSTANT, groupOnlyFact.bindingKind());
    }

    @Test
    void crossClassGroupKeepsDictionaryRoute() throws Exception {
        var analyzed = analyze("enum_chain_cross_class_dict_route.gd", """
                class_name EnumChainCrossClassDictRoute
                extends RefCounted

                class Other:
                    enum State { keys }

                func ping():
                \tvar names = Other.State.keys()
                \tvar member = Other.State.keys
                """);

        assertTrue(analyzed.diagnostics().isEmpty(), () -> "Unexpected diagnostics: " + analyzed.diagnostics());
        var ping = findFunction(analyzed.unit().ast(), "ping");

        // Call steps are not intercepted: the group Dictionary keeps its method route.
        var keysCall = findNode(ping, AttributeCallStep.class, step -> step.name().equals("keys"));
        var resolvedCall = analyzed.analysisData().resolvedCalls().get(keysCall);
        assertNotNull(resolvedCall);
        assertEquals(FrontendCallResolutionStatus.RESOLVED, resolvedCall.status());

        // Property read on the armed group continuation hits the enum member named like the
        // Dictionary method, mirroring the in-class edge rule.
        var keysRead = findNode(ping, AttributePropertyStep.class, step -> step.name().equals("keys"));
        var memberFact = analyzed.analysisData().resolvedMembers().get(keysRead);
        assertNotNull(memberFact);
        assertAll(
                () -> assertEquals(FrontendMemberResolutionStatus.RESOLVED, memberFact.status()),
                () -> assertEquals(FrontendBindingKind.CONSTANT, memberFact.bindingKind()),
                () -> assertInstanceOf(GdScriptEnumConstant.class, memberFact.declarationSite())
        );
    }

    @Test
    void crossClassMemberMissKeepsMemberResolutionDiagnostic() throws Exception {
        var analyzed = analyze("enum_chain_cross_class_miss.gd", """
                class_name EnumChainCrossClassMiss
                extends RefCounted

                class Other:
                    enum State { IDLE }

                func ping():
                    var value = Other.State.MISSING
                """);

        var ping = findFunction(analyzed.unit().ast(), "ping");
        var missStep = findNode(ping, AttributePropertyStep.class, step -> step.name().equals("MISSING"));
        var missFact = analyzed.analysisData().resolvedMembers().get(missStep);
        assertNotNull(missFact);
        assertEquals(FrontendMemberResolutionStatus.FAILED, missFact.status());
        assertEquals(1, diagnosticsByCategory(analyzed.diagnostics(), "sema.member_resolution").size());
        assertTrue(diagnosticsByCategory(analyzed.diagnostics(), "sema.unsupported_chain_route").isEmpty());
    }

    @Test
    void crossClassMemberSuffixFailsOnIntReceiverAfterInterception() throws Exception {
        var analyzed = analyze("enum_chain_cross_class_suffix.gd", """
                class_name EnumChainCrossClassSuffix
                extends RefCounted

                class Other:
                    enum State { IDLE }

                func ping():
                \tvar ok := Other.State.IDLE + 1
                \tvar bad = Other.State.IDLE.JUMP
                """);

        var ping = findFunction(analyzed.unit().ast(), "ping");
        var idleSteps = findNodes(ping, AttributePropertyStep.class, step -> step.name().equals("IDLE"));
        assertEquals(2, idleSteps.size());
        for (var idleStep : idleSteps) {
            var fact = analyzed.analysisData().resolvedMembers().get(idleStep);
            assertNotNull(fact);
            assertEquals(FrontendBindingKind.CONSTANT, fact.bindingKind());
        }
        // The binary-expression suffix consumes the int member without any diagnostic.
        var okVariable = findVariable(ping.body().statements(), "ok");
        assertEquals(GdIntType.INT, analyzed.analysisData().slotTypes().get(okVariable));

        // `.JUMP` after the intercepted member must fail on the int receiver, proving the group
        // continuation state cleared itself after the single interception.
        var jumpStep = findNode(ping, AttributePropertyStep.class, step -> step.name().equals("JUMP"));
        var jumpFact = analyzed.analysisData().resolvedMembers().get(jumpStep);
        assertNotNull(jumpFact);
        assertEquals(FrontendMemberResolutionStatus.FAILED, jumpFact.status());
        assertNotNull(jumpFact.detailReason());
        assertTrue(jumpFact.detailReason().contains("'int'"), jumpFact.detailReason());
        assertEquals(1, diagnosticsByCategory(analyzed.diagnostics(), "sema.member_resolution").size());
    }

    @Test
    void crossCategoryShadowingResolvesNearestLayerWins() throws Exception {
        var analyzed = analyze("enum_chain_cross_category_shadow.gd", """
                class_name EnumChainCrossCategoryShadow
                extends RefCounted

                class Base:
                    static var SHARED_NAME: int = 1

                class Other extends Base:
                    enum { SHARED_NAME = 7 }

                func ping() -> int:
                    return Other.SHARED_NAME
                """);

        assertTrue(analyzed.diagnostics().isEmpty(), () -> "Unexpected diagnostics: " + analyzed.diagnostics());
        var ping = findFunction(analyzed.unit().ast(), "ping");
        var shadowStep = findNode(ping, AttributePropertyStep.class, step -> step.name().equals("SHARED_NAME"));
        var fact = analyzed.analysisData().resolvedMembers().get(shadowStep);
        assertNotNull(fact);
        assertAll(
                () -> assertEquals(FrontendMemberResolutionStatus.RESOLVED, fact.status()),
                // The subclass enum constant shadows the superclass static property.
                () -> assertEquals(FrontendBindingKind.CONSTANT, fact.bindingKind()),
                () -> assertEquals(GdIntType.INT, fact.resultType()),
                () -> {
                    var constant = assertInstanceOf(GdScriptEnumConstant.class, fact.declarationSite());
                    assertEquals(7L, constant.value());
                    assertTrue(constant.ownerClassCanonicalName().endsWith("Other"), constant.ownerClassCanonicalName());
                }
        );
    }

    @Test
    void crossClassUnknownAndNestedQualifierKeepUnsupportedRoute() throws Exception {
        var analyzed = analyze("enum_chain_cross_class_unsupported.gd", """
                class_name EnumChainCrossClassUnsupported
                extends RefCounted

                class Outer:
                    class Inner:
                        enum State { IDLE }

                class Other:
                    enum State { IDLE }

                func ping():
                    var unknown = Other.MISSING
                    var nested = Outer.Inner.State.IDLE
                """);

        var ping = findFunction(analyzed.unit().ast(), "ping");
        var unknownStep = findNode(ping, AttributePropertyStep.class, step -> step.name().equals("MISSING"));
        var unknownFact = analyzed.analysisData().resolvedMembers().get(unknownStep);
        assertNotNull(unknownFact);
        assertAll(
                () -> assertEquals(FrontendMemberResolutionStatus.UNSUPPORTED, unknownFact.status()),
                () -> assertEquals(FrontendBindingKind.CONSTANT, unknownFact.bindingKind()),
                () -> assertEquals(FrontendReceiverKind.TYPE_META, unknownFact.receiverKind())
        );

        // `.Inner` has no static-member branch on the GDCC static-load route, so nested
        // qualification stays deferred at the same boundary.
        var innerStep = findNode(ping, AttributePropertyStep.class, step -> step.name().equals("Inner"));
        var innerFact = analyzed.analysisData().resolvedMembers().get(innerStep);
        assertNotNull(innerFact);
        assertEquals(FrontendMemberResolutionStatus.UNSUPPORTED, innerFact.status());

        // One unsupported-route error per rejected chain head step; no member-resolution noise.
        assertEquals(2, diagnosticsByCategory(analyzed.diagnostics(), "sema.unsupported_chain_route").size());
        assertTrue(diagnosticsByCategory(analyzed.diagnostics(), "sema.member_resolution").isEmpty());
    }

    @Test
    void groupContinuationStateDoesNotLeakAcrossChains() throws Exception {
        var analyzed = analyze("enum_chain_continuation_isolation.gd", """
                class_name EnumChainContinuationIsolation
                extends RefCounted

                class Other:
                    enum State { GROUP_ONLY }

                func ping():
                    var group := Other.State
                    var leaked = Other.GROUP_ONLY
                """);

        var ping = findFunction(analyzed.unit().ast(), "ping");

        // The first chain ends on the group fact (Dictionary), arming no later chain.
        var groupVariable = findVariable(ping.body().statements(), "group");
        assertEquals("Dictionary", analyzed.analysisData().slotTypes().get(groupVariable).getTypeName());

        // `GROUP_ONLY` is a group member but not a class-level constant: the second chain must
        // keep the UNSUPPORTED boundary instead of being intercepted by leftover group state.
        var leakedStep = findNode(ping, AttributePropertyStep.class, step -> step.name().equals("GROUP_ONLY"));
        var leakedFact = analyzed.analysisData().resolvedMembers().get(leakedStep);
        assertNotNull(leakedFact);
        assertEquals(FrontendMemberResolutionStatus.UNSUPPORTED, leakedFact.status());
        assertEquals(1, diagnosticsByCategory(analyzed.diagnostics(), "sema.unsupported_chain_route").size());
    }

    @Test
    void crossClassInstanceMemberTerminallyShadowsInheritedEnumConstant() throws Exception {
        var analyzed = analyze("enum_chain_terminal_shadow.gd", """
                class_name EnumChainTerminalShadow
                extends RefCounted

                class Grand:
                    enum { VALUE = 9 }

                class Other extends Grand:
                    var VALUE: int = 1

                func ping():
                    var leaked = Other.VALUE
                    var direct = Grand.VALUE
                """);

        var ping = findFunction(analyzed.unit().ast(), "ping");
        var leakedVariable = findVariable(ping.body().statements(), "leaked");
        var directVariable = findVariable(ping.body().statements(), "direct");

        // The instance property on the nearer layer is a terminal shadowing hit: `Other.VALUE`
        // keeps the pre-existing UNSUPPORTED boundary instead of leaking to the ancestor enum
        // constant.
        var leakedStep = findNode(leakedVariable, AttributePropertyStep.class, step -> step.name().equals("VALUE"));
        var leakedFact = analyzed.analysisData().resolvedMembers().get(leakedStep);
        assertNotNull(leakedFact);
        assertEquals(FrontendMemberResolutionStatus.UNSUPPORTED, leakedFact.status());

        // Direct access on the declaring class still resolves the enum constant.
        var directStep = findNode(directVariable, AttributePropertyStep.class, step -> step.name().equals("VALUE"));
        var directFact = analyzed.analysisData().resolvedMembers().get(directStep);
        assertNotNull(directFact);
        assertEquals(FrontendBindingKind.CONSTANT, directFact.bindingKind());

        assertEquals(1, diagnosticsByCategory(analyzed.diagnostics(), "sema.unsupported_chain_route").size());
    }

    @Test
    void crossClassGroupSubscriptKeepsDictionaryRoute() throws Exception {
        var analyzed = analyze("enum_chain_group_subscript.gd", """
                class_name EnumChainGroupSubscript
                extends RefCounted

                class Other:
                    enum State { IDLE, JUMP }

                func ping():
                    var value = Other.State["IDLE"]
                """);

        assertTrue(analyzed.diagnostics().isEmpty(), () -> "Unexpected diagnostics: " + analyzed.diagnostics());
        var ping = findFunction(analyzed.unit().ast(), "ping");
        var chain = findNode(ping, AttributeExpression.class, expression -> true);
        var type = analyzed.analysisData().expressionTypes().get(chain);
        assertNotNull(type);
        assertAll(
                // The subscript keeps Dictionary semantics (generic Dictionary value type) and is
                // never folded into an enum constant.
                () -> assertEquals(FrontendExpressionTypeStatus.RESOLVED, type.status()),
                () -> assertEquals("Variant", type.publishedType().getTypeName())
        );
        var constantFacts = findNodes(chain, AttributePropertyStep.class, _ -> true).stream()
                .map(step -> analyzed.analysisData().resolvedMembers().get(step))
                .filter(fact -> fact != null && fact.declarationSite() instanceof GdScriptEnumConstant)
                .toList();
        assertTrue(constantFacts.isEmpty(), () -> "Unexpected constant folding: " + constantFacts);
    }

    @Test
    void propertyInitializerConsumesCrossClassEnumConstant() throws Exception {
        var analyzed = analyze("enum_chain_property_initializer.gd", """
                class_name EnumChainPropertyInitializer
                extends RefCounted

                class Other:
                    enum State { IDLE, JUMP = 5 }

                var initial: int = Other.State.IDLE
                """);

        assertTrue(analyzed.diagnostics().isEmpty(), () -> "Unexpected diagnostics: " + analyzed.diagnostics());
        var initializer = findNode(analyzed.unit().ast(), AttributeExpression.class, chain -> true);
        var type = analyzed.analysisData().expressionTypes().get(initializer);
        assertNotNull(type);
        assertAll(
                () -> assertEquals(FrontendExpressionTypeStatus.RESOLVED, type.status()),
                () -> assertEquals(GdIntType.INT, type.publishedType())
        );
        var idleStep = findNode(initializer, AttributePropertyStep.class, step -> step.name().equals("IDLE"));
        var fact = analyzed.analysisData().resolvedMembers().get(idleStep);
        assertNotNull(fact);
        assertEquals(FrontendBindingKind.CONSTANT, fact.bindingKind());
    }

    @Test
    void parameterDefaultConsumesCrossClassEnumConstant() throws Exception {
        var analyzed = analyze("enum_chain_parameter_default.gd", """
                class_name EnumChainParameterDefault
                extends RefCounted

                class Other:
                    enum { GROUP_ONLY = 4 }

                func ping(count = Other.GROUP_ONLY) -> int:
                    return count
                """);

        assertTrue(analyzed.diagnostics().isEmpty(), () -> "Unexpected diagnostics: " + analyzed.diagnostics());
        var ping = findFunction(analyzed.unit().ast(), "ping");
        var defaultChain = findNode(ping, AttributeExpression.class, chain -> true);
        var type = analyzed.analysisData().expressionTypes().get(defaultChain);
        assertNotNull(type);
        assertAll(
                () -> assertEquals(FrontendExpressionTypeStatus.RESOLVED, type.status()),
                () -> assertEquals(GdIntType.INT, type.publishedType())
        );
        var step = findNode(defaultChain, AttributePropertyStep.class, candidate -> candidate.name().equals("GROUP_ONLY"));
        var fact = analyzed.analysisData().resolvedMembers().get(step);
        assertNotNull(fact);
        assertEquals(FrontendBindingKind.CONSTANT, fact.bindingKind());
    }

    @Test
    void matchPatternRecognizesCrossClassEnumConstantOperand() throws Exception {
        var analyzed = analyze("enum_chain_match_pattern.gd", """
                class_name EnumChainMatchPattern
                extends RefCounted

                class Other:
                    enum State { IDLE, JUMP }

                func ping(state):
                    match state:
                        Other.State.IDLE:
                            pass
                """);

        assertTrue(analyzed.diagnostics().isEmpty(), () -> "Unexpected diagnostics: " + analyzed.diagnostics());
        var ping = findFunction(analyzed.unit().ast(), "ping");
        var matchStatement = findNode(ping.body(), MatchStatement.class, _ -> true);
        var pattern = assertInstanceOf(
                AttributeExpression.class,
                matchStatement.sections().getFirst().patterns().getFirst()
        );
        // The last step fact carries `bindingKind == CONSTANT`, so match support classifies the
        // pattern as a constant operand instead of a runtime expression pattern.
        assertTrue(FrontendMatchSupport.isConstantPatternOperand(analyzed.analysisData(), pattern));
        var idleStep = findNode(pattern, AttributePropertyStep.class, step -> step.name().equals("IDLE"));
        var fact = analyzed.analysisData().resolvedMembers().get(idleStep);
        assertNotNull(fact);
        assertEquals(FrontendBindingKind.CONSTANT, fact.bindingKind());
    }

    private static @NotNull AnalyzedInput analyze(
            @NotNull String fileName,
            @NotNull String source
    ) throws Exception {
        return analyzeAll(List.of(new SourceSpec(fileName, source)));
    }

    private static @NotNull AnalyzedInput analyzeAll(@NotNull List<SourceSpec> sources) throws Exception {
        var parserService = new GdScriptParserService();
        var diagnostics = new DiagnosticManager();
        var units = new ArrayList<FrontendSourceUnit>();
        for (var source : sources) {
            units.add(parserService.parseUnit(Path.of("tmp", source.fileName()), source.source(), diagnostics));
        }
        assertTrue(diagnostics.isEmpty(), () -> "Unexpected parse diagnostics: " + diagnostics.snapshot());
        var analysisData = new FrontendSemanticAnalyzer().analyze(
                new FrontendModule("test_module", List.copyOf(units)),
                new ClassRegistry(ExtensionApiLoader.loadDefault()),
                diagnostics
        );
        return new AnalyzedInput(units.getLast(), analysisData, diagnostics.snapshot());
    }

    private static @NotNull FunctionDeclaration findFunction(@NotNull Node root, @NotNull String name) {
        return findNode(root, FunctionDeclaration.class, declaration -> declaration.name().equals(name));
    }

    private static @NotNull VariableDeclaration findVariable(
            @NotNull List<Statement> statements,
            @NotNull String name
    ) {
        return statements.stream()
                .filter(VariableDeclaration.class::isInstance)
                .map(VariableDeclaration.class::cast)
                .filter(declaration -> declaration.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Variable not found: " + name));
    }

    private static @NotNull List<FrontendDiagnostic> diagnosticsByCategory(
            @NotNull DiagnosticSnapshot diagnostics,
            @NotNull String category
    ) {
        return diagnostics.asList().stream()
                .filter(diagnostic -> diagnostic.category().equals(category))
                .toList();
    }

    private static <T extends Node> @NotNull T findNode(
            @NotNull Node root,
            @NotNull Class<T> nodeType,
            @NotNull Predicate<T> predicate
    ) {
        var matches = findNodes(root, nodeType, predicate);
        return matches.stream()
                .findFirst()
                .orElseThrow(() -> new AssertionError("Node not found: " + nodeType.getSimpleName()));
    }

    private static <T extends Node> @NotNull List<T> findNodes(
            @NotNull Node root,
            @NotNull Class<T> nodeType,
            @NotNull Predicate<T> predicate
    ) {
        var matches = new ArrayList<T>();
        collectMatchingNodes(root, nodeType, predicate, matches);
        return matches;
    }

    private static <T extends Node> void collectMatchingNodes(
            @NotNull Node node,
            @NotNull Class<T> nodeType,
            @NotNull Predicate<T> predicate,
            @NotNull List<T> matches
    ) {
        if (nodeType.isInstance(node) && predicate.test(nodeType.cast(node))) {
            matches.add(nodeType.cast(node));
        }
        for (var child : node.getChildren()) {
            collectMatchingNodes(child, nodeType, predicate, matches);
        }
    }

    private record SourceSpec(@NotNull String fileName, @NotNull String source) {
    }

    private record AnalyzedInput(
            @NotNull FrontendSourceUnit unit,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull DiagnosticSnapshot diagnostics
    ) {
    }
}
