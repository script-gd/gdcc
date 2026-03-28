package dev.superice.gdcc.frontend.lowering;

import dev.superice.gdcc.frontend.diagnostic.DiagnosticManager;
import dev.superice.gdcc.frontend.lowering.pass.FrontendLoweringAnalysisPass;
import dev.superice.gdcc.frontend.lowering.pass.FrontendLoweringClassSkeletonPass;
import dev.superice.gdcc.frontend.lowering.pass.FrontendLoweringFunctionPreparationPass;
import dev.superice.gdcc.frontend.parse.FrontendModule;
import dev.superice.gdcc.frontend.parse.GdScriptParserService;
import dev.superice.gdcc.frontend.sema.FrontendAnalysisData;
import dev.superice.gdcc.frontend.sema.analyzer.FrontendSemanticAnalyzer;
import dev.superice.gdcc.gdextension.ExtensionApiLoader;
import dev.superice.gdcc.lir.LirBasicBlock;
import dev.superice.gdcc.lir.LirClassDef;
import dev.superice.gdcc.lir.LirFunctionDef;
import dev.superice.gdcc.lir.LirModule;
import dev.superice.gdcc.lir.LirParameterDef;
import dev.superice.gdcc.lir.LirPropertyDef;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.type.GdIntType;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdStringType;
import dev.superice.gdparser.frontend.ast.AttributeCallStep;
import dev.superice.gdparser.frontend.ast.AttributeExpression;
import dev.superice.gdparser.frontend.ast.AttributePropertyStep;
import dev.superice.gdparser.frontend.ast.Block;
import dev.superice.gdparser.frontend.ast.ClassDeclaration;
import dev.superice.gdparser.frontend.ast.ConstructorDeclaration;
import dev.superice.gdparser.frontend.ast.Expression;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.Statement;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontendLoweringFunctionPreparationPassTest {
    @Test
    void runPublishesExecutableAndPropertyInitContextsWhileKeepingSkeletonOnlyLir() throws Exception {
        var prepared = prepareCompileReadyContext();
        var preparationPass = new FrontendLoweringFunctionPreparationPass();

        preparationPass.run(prepared.context());

        var lirModule = prepared.context().requireLirModule();
        var contexts = prepared.context().requireFunctionLoweringContexts();
        assertEquals(
                EnumSet.of(
                        FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                        FunctionLoweringContext.Kind.PROPERTY_INIT,
                        FunctionLoweringContext.Kind.PARAMETER_DEFAULT_INIT
                ),
                EnumSet.allOf(FunctionLoweringContext.Kind.class)
        );
        assertEquals(6, contexts.size());
        assertEquals(4, contexts.stream().filter(context -> context.kind() == FunctionLoweringContext.Kind.EXECUTABLE_BODY).count());
        assertEquals(2, contexts.stream().filter(context -> context.kind() == FunctionLoweringContext.Kind.PROPERTY_INIT).count());
        assertEquals(0, contexts.stream().filter(context -> context.kind() == FunctionLoweringContext.Kind.PARAMETER_DEFAULT_INIT).count());

        var outerClass = requireClass(lirModule, "RuntimePreparationOuter");
        var innerClass = requireClass(lirModule, "RuntimePreparationOuter$Inner");
        var outerSourceFile = prepared.module().units().getFirst().ast();
        var innerDeclaration = requireStatement(outerSourceFile.statements(), ClassDeclaration.class, ignored -> true);
        var outerConstructor = requireStatement(outerSourceFile.statements(), ConstructorDeclaration.class, ignored -> true);
        var outerStaticFunction = requireStatement(
                outerSourceFile.statements(),
                FunctionDeclaration.class,
                function -> function.name().equals("helper")
        );
        var outerFunction = requireStatement(
                outerSourceFile.statements(),
                FunctionDeclaration.class,
                function -> function.name().equals("ping")
        );
        var outerProperty = requireStatement(
                outerSourceFile.statements(),
                VariableDeclaration.class,
                property -> property.name().equals("count")
        );
        var innerProperty = requireStatement(
                innerDeclaration.body().statements(),
                VariableDeclaration.class,
                property -> property.name().equals("label")
        );
        var innerFunction = requireStatement(
                innerDeclaration.body().statements(),
                FunctionDeclaration.class,
                function -> function.name().equals("pong")
        );

        var initContext = requireContext(
                contexts,
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimePreparationOuter",
                "_init"
        );
        assertSame(outerConstructor, initContext.sourceOwner());
        assertSame(outerConstructor.body(), initContext.loweringRoot());
        assertInstanceOf(Block.class, initContext.loweringRoot());
        assertSame(requireFunction(outerClass, "_init"), initContext.targetFunction());

        var helperContext = requireContext(
                contexts,
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimePreparationOuter",
                "helper"
        );
        assertSame(outerStaticFunction, helperContext.sourceOwner());
        assertSame(outerStaticFunction.body(), helperContext.loweringRoot());
        assertInstanceOf(Block.class, helperContext.loweringRoot());
        assertTrue(helperContext.targetFunction().isStatic());
        assertSame(requireFunction(outerClass, "helper"), helperContext.targetFunction());

        var pingContext = requireContext(
                contexts,
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimePreparationOuter",
                "ping"
        );
        assertSame(outerFunction, pingContext.sourceOwner());
        assertSame(outerFunction.body(), pingContext.loweringRoot());
        assertInstanceOf(Block.class, pingContext.loweringRoot());
        assertSame(requireFunction(outerClass, "ping"), pingContext.targetFunction());

        var pongContext = requireContext(
                contexts,
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimePreparationOuter$Inner",
                "pong"
        );
        assertSame(innerFunction, pongContext.sourceOwner());
        assertSame(innerFunction.body(), pongContext.loweringRoot());
        assertInstanceOf(Block.class, pongContext.loweringRoot());
        assertSame(requireFunction(innerClass, "pong"), pongContext.targetFunction());

        var outerPropertyContext = requireContext(
                contexts,
                FunctionLoweringContext.Kind.PROPERTY_INIT,
                "RuntimePreparationOuter",
                "_field_init_count"
        );
        assertSame(outerProperty, outerPropertyContext.sourceOwner());
        assertSame(outerProperty.value(), outerPropertyContext.loweringRoot());
        assertInstanceOf(Expression.class, outerPropertyContext.loweringRoot());
        assertTrue(outerPropertyContext.targetFunction().isHidden());
        assertEquals("int", outerPropertyContext.targetFunction().getReturnType().getTypeName());
        assertEquals(1, outerPropertyContext.targetFunction().getParameterCount());
        var outerSelfParam = outerPropertyContext.targetFunction().getParameter(0);
        assertNotNull(outerSelfParam);
        assertEquals("self", outerSelfParam.name());

        var innerPropertyContext = requireContext(
                contexts,
                FunctionLoweringContext.Kind.PROPERTY_INIT,
                "RuntimePreparationOuter$Inner",
                "_field_init_label"
        );
        assertSame(innerProperty, innerPropertyContext.sourceOwner());
        assertSame(innerProperty.value(), innerPropertyContext.loweringRoot());
        assertInstanceOf(Expression.class, innerPropertyContext.loweringRoot());
        assertTrue(innerPropertyContext.targetFunction().isHidden());
        assertEquals("String", innerPropertyContext.targetFunction().getReturnType().getTypeName());
        assertEquals(1, innerPropertyContext.targetFunction().getParameterCount());
        var innerSelfParam = innerPropertyContext.targetFunction().getParameter(0);
        assertNotNull(innerSelfParam);
        assertEquals("self", innerSelfParam.name());

        var outerInitProperty = requireProperty(outerClass, "count");
        var innerInitProperty = requireProperty(innerClass, "label");
        assertEquals("_field_init_count", outerInitProperty.getInitFunc());
        assertEquals("_field_init_label", innerInitProperty.getInitFunc());
        assertSame(requireFunction(outerClass, "_field_init_count"), outerPropertyContext.targetFunction());
        assertSame(requireFunction(innerClass, "_field_init_label"), innerPropertyContext.targetFunction());

        for (var classDef : lirModule.getClassDefs()) {
            for (var function : classDef.getFunctions()) {
                assertEquals(0, function.getBasicBlockCount(), classDef.getName() + "::" + function.getName());
                assertTrue(function.getEntryBlockId().isEmpty(), classDef.getName() + "::" + function.getName());
            }
        }
    }

    @Test
    void requireFunctionLoweringContextsFailsFastBeforePublication() throws Exception {
        var context = new FrontendLoweringContext(
                new FrontendModule("test_module", List.of()),
                new ClassRegistry(ExtensionApiLoader.loadDefault()),
                new DiagnosticManager()
        );
        assertNull(context.functionLoweringContextsOrNull());

        var exception = assertThrows(IllegalStateException.class, context::requireFunctionLoweringContexts);

        assertEquals("functionLoweringContexts have not been published yet", exception.getMessage());
    }

    @Test
    void runSkipsPropertyWithoutInitializerAndKeepsInitFuncUnset() throws Exception {
        var prepared = prepareNoInitializerCompileReadyContext();

        new FrontendLoweringFunctionPreparationPass().run(prepared.context());

        var lirModule = prepared.context().requireLirModule();
        var contexts = prepared.context().requireFunctionLoweringContexts();
        var outerClass = requireClass(lirModule, "RuntimePreparationNoInit");
        var property = requireProperty(outerClass, "count");

        assertEquals(1, contexts.size());
        assertEquals(
                1,
                contexts.stream().filter(context -> context.kind() == FunctionLoweringContext.Kind.EXECUTABLE_BODY).count()
        );
        assertEquals(
                0,
                contexts.stream().filter(context -> context.kind() == FunctionLoweringContext.Kind.PROPERTY_INIT).count()
        );
        assertNull(property.getInitFunc());
        assertEquals(
                1,
                outerClass.getFunctions().stream()
                        .filter(function -> function.getName().equals("ping"))
                        .count()
        );
        assertFalse(
                outerClass.getFunctions().stream().anyMatch(function -> function.getName().equals("_field_init_count"))
        );
    }

    @Test
    void functionLoweringContextCanRepresentFutureParameterDefaultInitWithoutShapeChanges() throws Exception {
        var analyzed = analyzeSharedModule(
                List.of(new SourceFixture(
                        "parameter_default_shape.gd",
                        """
                                class_name ParameterDefaultShape
                                extends RefCounted
                                
                                func ping(value: int, alias = value) -> int:
                                    return alias
                                """
                )),
                Map.of("ParameterDefaultShape", "RuntimeParameterDefaultShape")
        );
        var sourceFile = analyzed.module().units().getFirst().ast();
        var pingFunction = requireStatement(
                sourceFile.statements(),
                FunctionDeclaration.class,
                function -> function.name().equals("ping")
        );
        var defaultedParameter = pingFunction.parameters().getLast();
        var owningClass = analyzed.analysisData().moduleSkeleton().allClassDefs().getFirst();
        var targetFunction = new LirFunctionDef("_default_ping$alias");
        targetFunction.setHidden(true);
        targetFunction.setReturnType(GdIntType.INT);
        targetFunction.addParameter(new LirParameterDef("self", outerClassAsType(owningClass), null, targetFunction));
        owningClass.addFunction(targetFunction);
        var sourceRelation = analyzed.analysisData().moduleSkeleton().sourceClassRelations().getFirst();
        var defaultValueExpression = java.util.Objects.requireNonNull(
                defaultedParameter.defaultValue(),
                "parameter default value must exist"
        );

        var parameterDefaultContext = new FunctionLoweringContext(
                FunctionLoweringContext.Kind.PARAMETER_DEFAULT_INIT,
                analyzed.module().units().getFirst().path(),
                sourceRelation,
                owningClass,
                targetFunction,
                defaultedParameter,
                defaultValueExpression,
                analyzed.analysisData()
        );
        var loweringContext = new FrontendLoweringContext(
                analyzed.module(),
                new ClassRegistry(ExtensionApiLoader.loadDefault()),
                new DiagnosticManager()
        );

        loweringContext.publishFunctionLoweringContexts(List.of(parameterDefaultContext));

        var publishedContexts = loweringContext.requireFunctionLoweringContexts();
        assertEquals(1, publishedContexts.size());
        assertSame(parameterDefaultContext, publishedContexts.getFirst());
        assertSame(defaultedParameter, parameterDefaultContext.sourceOwner());
        assertSame(defaultValueExpression, parameterDefaultContext.loweringRoot());
        assertEquals(FunctionLoweringContext.Kind.PARAMETER_DEFAULT_INIT, parameterDefaultContext.kind());
        assertTrue(parameterDefaultContext.targetFunction().isHidden());
    }

    @Test
    void runFailsFastWhenLirModuleHasNotBeenPublishedYet() throws Exception {
        var module = parseModule(
                List.of(new SourceFixture(
                        "missing_lir_module.gd",
                        """
                                class_name MissingLirModule
                                extends RefCounted
                                
                                func ping() -> void:
                                    pass
                                """
                )),
                Map.of()
        );
        var context = new FrontendLoweringContext(
                module,
                new ClassRegistry(ExtensionApiLoader.loadDefault()),
                new DiagnosticManager()
        );
        new FrontendLoweringAnalysisPass().run(context);

        var exception = assertThrows(
                IllegalStateException.class,
                () -> new FrontendLoweringFunctionPreparationPass().run(context)
        );

        assertEquals("lirModule has not been published yet", exception.getMessage());
    }

    @Test
    void runFailsFastWhenAnalysisDataHasNotBeenPublishedYet() throws Exception {
        var context = new FrontendLoweringContext(
                new FrontendModule("test_module", List.of()),
                new ClassRegistry(ExtensionApiLoader.loadDefault()),
                new DiagnosticManager()
        );

        var exception = assertThrows(
                IllegalStateException.class,
                () -> new FrontendLoweringFunctionPreparationPass().run(context)
        );

        assertEquals("analysisData has not been published yet", exception.getMessage());
    }

    @Test
    void runFailsFastWhenCallableBodyScopeIsMissing() throws Exception {
        var prepared = prepareCompileReadyContext();
        var outerSourceFile = prepared.module().units().getFirst().ast();
        var outerFunction = requireStatement(
                outerSourceFile.statements(),
                FunctionDeclaration.class,
                function -> function.name().equals("ping")
        );
        prepared.context().requireAnalysisData().scopesByAst().remove(outerFunction.body());

        var exception = assertThrows(
                IllegalStateException.class,
                () -> new FrontendLoweringFunctionPreparationPass().run(prepared.context())
        );

        assertTrue(exception.getMessage().contains("callable body scope has not been published"));
    }

    @Test
    void runFailsFastWhenCallableOwnerScopeIsMissing() throws Exception {
        var prepared = prepareCompileReadyContext();
        var outerSourceFile = prepared.module().units().getFirst().ast();
        var outerFunction = requireStatement(
                outerSourceFile.statements(),
                FunctionDeclaration.class,
                function -> function.name().equals("ping")
        );
        prepared.context().requireAnalysisData().scopesByAst().remove(outerFunction);

        var exception = assertThrows(
                IllegalStateException.class,
                () -> new FrontendLoweringFunctionPreparationPass().run(prepared.context())
        );

        assertTrue(exception.getMessage().contains("callable owner scope has not been published"));
    }

    @Test
    void runFailsFastWhenFunctionSkeletonIsMissing() throws Exception {
        var prepared = prepareCompileReadyContext();
        var outerClass = requireClass(prepared.context().requireLirModule(), "RuntimePreparationOuter");
        assertTrue(outerClass.removeFunction(requireFunction(outerClass, "ping")));

        var exception = assertThrows(
                IllegalStateException.class,
                () -> new FrontendLoweringFunctionPreparationPass().run(prepared.context())
        );

        assertTrue(exception.getMessage().contains("Expected exactly one function skeleton for RuntimePreparationOuter.ping"));
    }

    @Test
    void runFailsFastWhenExecutableFunctionSkeletonAlreadyHasBodyShape() throws Exception {
        var prepared = prepareCompileReadyContext();
        var outerClass = requireClass(prepared.context().requireLirModule(), "RuntimePreparationOuter");
        var pingFunction = requireFunction(outerClass, "ping");
        pingFunction.addBasicBlock(new LirBasicBlock("entry"));
        pingFunction.setEntryBlockId("entry");

        var exception = assertThrows(
                IllegalStateException.class,
                () -> new FrontendLoweringFunctionPreparationPass().run(prepared.context())
        );

        assertTrue(exception.getMessage().contains("Executable function 'RuntimePreparationOuter.ping' must remain shell-only during preparation"));
    }

    @Test
    void runReusesPreassignedHiddenPropertyInitShell() throws Exception {
        var prepared = prepareCompileReadyContext();
        var lirModule = prepared.context().requireLirModule();
        var outerClass = requireClass(lirModule, "RuntimePreparationOuter");
        var property = requireProperty(outerClass, "count");
        property.setInitFunc("_field_init_count_preassigned");
        var existingShell = new LirFunctionDef("_field_init_count_preassigned");
        existingShell.setStatic(false);
        existingShell.setHidden(true);
        existingShell.setReturnType(property.getType());
        existingShell.addParameter(new LirParameterDef("self", outerClassAsType(outerClass), null, existingShell));
        outerClass.addFunction(existingShell);

        new FrontendLoweringFunctionPreparationPass().run(prepared.context());

        var contexts = prepared.context().requireFunctionLoweringContexts();
        var propertyContext = requireContext(
                contexts,
                FunctionLoweringContext.Kind.PROPERTY_INIT,
                "RuntimePreparationOuter",
                "_field_init_count_preassigned"
        );
        assertSame(existingShell, propertyContext.targetFunction());
        assertEquals("_field_init_count_preassigned", property.getInitFunc());
        assertEquals(
                1,
                outerClass.getFunctions().stream()
                        .filter(function -> function.getName().equals("_field_init_count_preassigned"))
                        .count()
        );
    }

    @Test
    void runFailsFastWhenIndexedClassSkeletonIsMissingFromPublishedLirModule() throws Exception {
        var prepared = prepareCompileReadyContext();
        var lirModule = prepared.context().requireLirModule();
        var innerClass = requireClass(lirModule, "RuntimePreparationOuter$Inner");
        assertTrue(lirModule.getClassDefs().remove(innerClass));

        var exception = assertThrows(
                IllegalStateException.class,
                () -> new FrontendLoweringFunctionPreparationPass().run(prepared.context())
        );

        assertTrue(exception.getMessage().contains("RuntimePreparationOuter$Inner"));
        assertTrue(exception.getMessage().contains("is not part of the published LIR module"));
    }

    @Test
    void runFailsFastWhenPropertyDeclarationScopeIsMissing() throws Exception {
        var prepared = prepareCompileReadyContext();
        var outerSourceFile = prepared.module().units().getFirst().ast();
        var property = requireStatement(
                outerSourceFile.statements(),
                VariableDeclaration.class,
                declaration -> declaration.name().equals("count")
        );
        prepared.context().requireAnalysisData().scopesByAst().remove(property);

        var exception = assertThrows(
                IllegalStateException.class,
                () -> new FrontendLoweringFunctionPreparationPass().run(prepared.context())
        );

        assertTrue(exception.getMessage().contains("property declaration scope has not been published"));
    }

    @Test
    void runFailsFastWhenPropertyInitializerExpressionScopeIsMissing() throws Exception {
        var prepared = prepareCompileReadyContext();
        var outerSourceFile = prepared.module().units().getFirst().ast();
        var property = requireStatement(
                outerSourceFile.statements(),
                VariableDeclaration.class,
                declaration -> declaration.name().equals("count")
        );
        prepared.context().requireAnalysisData().scopesByAst().remove(property.value());

        var exception = assertThrows(
                IllegalStateException.class,
                () -> new FrontendLoweringFunctionPreparationPass().run(prepared.context())
        );

        assertTrue(exception.getMessage().contains("property initializer expression scope has not been published"));
    }

    @Test
    void runFailsFastWhenPropertyMetadataIsMissing() throws Exception {
        var prepared = prepareCompileReadyContext();
        var outerClass = requireClass(prepared.context().requireLirModule(), "RuntimePreparationOuter");
        var property = requireProperty(outerClass, "count");
        assertTrue(outerClass.removeProperty(property));

        var exception = assertThrows(
                IllegalStateException.class,
                () -> new FrontendLoweringFunctionPreparationPass().run(prepared.context())
        );

        assertTrue(exception.getMessage().contains("Expected exactly one property skeleton for RuntimePreparationOuter.count"));
    }

    @Test
    void runFailsFastWhenExistingPropertyInitShellIsNotHidden() throws Exception {
        var prepared = prepareCompileReadyContext();
        var outerClass = requireClass(prepared.context().requireLirModule(), "RuntimePreparationOuter");
        var property = requireProperty(outerClass, "count");
        property.setInitFunc("_field_init_count_existing");
        var existingShell = new LirFunctionDef("_field_init_count_existing");
        existingShell.setStatic(false);
        existingShell.setHidden(false);
        existingShell.setReturnType(property.getType());
        existingShell.addParameter(new LirParameterDef("self", outerClassAsType(outerClass), null, existingShell));
        outerClass.addFunction(existingShell);

        var exception = assertThrows(
                IllegalStateException.class,
                () -> new FrontendLoweringFunctionPreparationPass().run(prepared.context())
        );

        assertTrue(exception.getMessage().contains("must be hidden"));
    }

    @Test
    void runFailsFastWhenExistingPropertyInitShellHasIncompatibleContract() throws Exception {
        var prepared = prepareCompileReadyContext();
        var outerClass = requireClass(prepared.context().requireLirModule(), "RuntimePreparationOuter");
        var property = requireProperty(outerClass, "count");
        property.setInitFunc("_field_init_count_existing");
        var existingShell = new LirFunctionDef("_field_init_count_existing");
        existingShell.setStatic(false);
        existingShell.setHidden(true);
        existingShell.setReturnType(GdStringType.STRING);
        existingShell.addParameter(new LirParameterDef("self", outerClassAsType(outerClass), null, existingShell));
        outerClass.addFunction(existingShell);

        var exception = assertThrows(
                IllegalStateException.class,
                () -> new FrontendLoweringFunctionPreparationPass().run(prepared.context())
        );

        assertTrue(exception.getMessage().contains("return type does not match property"));
    }

    @Test
    void runFailsFastWhenExistingPropertyInitShellUsesWrongSelfParameterContract() throws Exception {
        var prepared = prepareCompileReadyContext();
        var outerClass = requireClass(prepared.context().requireLirModule(), "RuntimePreparationOuter");
        var property = requireProperty(outerClass, "count");
        property.setInitFunc("_field_init_count_existing");
        var existingShell = new LirFunctionDef("_field_init_count_existing");
        existingShell.setStatic(false);
        existingShell.setHidden(true);
        existingShell.setReturnType(property.getType());
        existingShell.addParameter(new LirParameterDef("owner", outerClassAsType(outerClass), null, existingShell));
        outerClass.addFunction(existingShell);

        var exception = assertThrows(
                IllegalStateException.class,
                () -> new FrontendLoweringFunctionPreparationPass().run(prepared.context())
        );

        assertTrue(exception.getMessage().contains("must declare the self parameter as 'self'"));
    }

    @Test
    void runPublishesAnalysisFactsIntoFunctionLoweringContextsForLaterPasses() throws Exception {
        var prepared = prepareFactRichCompileReadyContext();

        new FrontendLoweringFunctionPreparationPass().run(prepared.context());

        var publishedAnalysisData = prepared.context().requireAnalysisData();
        var contexts = prepared.context().requireFunctionLoweringContexts();
        var propertyContext = requireContext(
                contexts,
                FunctionLoweringContext.Kind.PROPERTY_INIT,
                "RuntimePreparationFacts",
                "_field_init_ready_value"
        );
        assertSame(publishedAnalysisData, propertyContext.analysisData());
        assertNotNull(propertyContext.analysisData().scopesByAst().get(propertyContext.sourceOwner()));
        assertNotNull(propertyContext.analysisData().scopesByAst().get(propertyContext.loweringRoot()));
        var readyInitializer = assertInstanceOf(AttributeExpression.class, propertyContext.loweringRoot());
        var workerHead = findNode(
                readyInitializer,
                IdentifierExpression.class,
                identifierExpression -> identifierExpression.name().equals("Worker")
        );
        var handleStep = findNode(
                readyInitializer,
                AttributePropertyStep.class,
                step -> step.name().equals("handle")
        );
        var readStep = findNode(
                readyInitializer,
                AttributeCallStep.class,
                step -> step.name().equals("read")
        );
        assertNotNull(propertyContext.analysisData().symbolBindings().get(workerHead));
        assertNotNull(propertyContext.analysisData().resolvedMembers().get(handleStep));
        assertNotNull(propertyContext.analysisData().resolvedCalls().get(readStep));
        assertNotNull(propertyContext.analysisData().expressionTypes().get(readyInitializer));

        var pingContext = requireContext(
                contexts,
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimePreparationFacts",
                "ping"
        );
        assertSame(publishedAnalysisData, pingContext.analysisData());
        assertNotNull(pingContext.analysisData().scopesByAst().get(pingContext.sourceOwner()));
        assertNotNull(pingContext.analysisData().scopesByAst().get(pingContext.loweringRoot()));
        var valueUseSite = findNode(
                pingContext.loweringRoot(),
                IdentifierExpression.class,
                identifierExpression -> identifierExpression.name().equals("value")
        );
        assertNotNull(pingContext.analysisData().symbolBindings().get(valueUseSite));
    }

    @Test
    void lowerCompileBlockedModuleStopsBeforePreparationPass() throws Exception {
        var continuationRan = new AtomicBoolean();
        var diagnostics = new DiagnosticManager();
        var lowered = new FrontendLoweringPassManager(List.of(
                new FrontendLoweringAnalysisPass(),
                new FrontendLoweringClassSkeletonPass(),
                new FrontendLoweringFunctionPreparationPass(),
                _ -> continuationRan.set(true)
        )).lower(
                parseModule(
                        List.of(new SourceFixture(
                                "preparation_blocked_assert.gd",
                                """
                                        class_name PreparationBlockedAssert
                                        extends RefCounted
                                        
                                        func ping(value):
                                            assert(value, "blocked in compile mode")
                                        """
                        )),
                        Map.of()
                ),
                new ClassRegistry(ExtensionApiLoader.loadDefault()),
                diagnostics
        );

        assertFalse(continuationRan.get());
        assertTrue(diagnostics.hasErrors());
        assertNull(lowered);
    }

    @Test
    void lowerParameterDefaultModuleStopsBeforePreparationPublicationAndKeepsUnsupportedDiagnostic() throws Exception {
        var continuationRan = new AtomicBoolean();
        var diagnostics = new DiagnosticManager();
        var lowered = new FrontendLoweringPassManager(List.of(
                new FrontendLoweringAnalysisPass(),
                new FrontendLoweringClassSkeletonPass(),
                new FrontendLoweringFunctionPreparationPass(),
                _ -> continuationRan.set(true)
        )).lower(
                parseModule(
                        List.of(new SourceFixture(
                                "preparation_blocked_parameter_default.gd",
                                """
                                        class_name PreparationBlockedParameterDefault
                                        extends RefCounted
                                        
                                        func ping(seed = 1):
                                            return seed
                                        """
                        )),
                        Map.of()
                ),
                new ClassRegistry(ExtensionApiLoader.loadDefault()),
                diagnostics
        );

        assertFalse(continuationRan.get());
        assertTrue(diagnostics.hasErrors());
        assertNull(lowered);
        assertTrue(diagnostics.snapshot().asList().stream().anyMatch(diagnostic ->
                diagnostic.category().equals("sema.unsupported_parameter_default_value")
                        && diagnostic.message().contains("Parameter default value")
        ));
    }

    @Test
    void lowerCompileBlockedModuleLeavesFunctionLoweringContextsUnpublished() throws Exception {
        var module = parseModule(
                List.of(new SourceFixture(
                        "preparation_blocked_assert_contexts.gd",
                        """
                                class_name PreparationBlockedAssertContexts
                                extends RefCounted
                                
                                func ping(value):
                                    assert(value, "blocked in compile mode")
                                """
                )),
                Map.of()
        );
        var context = new FrontendLoweringContext(
                module,
                new ClassRegistry(ExtensionApiLoader.loadDefault()),
                new DiagnosticManager()
        );

        new FrontendLoweringAnalysisPass().run(context);

        assertTrue(context.isStopRequested());
        assertNull(context.functionLoweringContextsOrNull());
    }

    private static @NotNull PreparedContext prepareCompileReadyContext() throws Exception {
        var diagnostics = new DiagnosticManager();
        var module = parseModule(
                List.of(new SourceFixture(
                        "preparation_ready.gd",
                        """
                                class_name PreparationOuter
                                extends RefCounted
                                
                                var count: int = 1
                                
                                func _init(seed: int):
                                    pass
                                
                                static func helper(flag: bool) -> void:
                                    pass
                                
                                func ping(value: int) -> int:
                                    return value
                                
                                class Inner:
                                    extends RefCounted
                                
                                    var label: String = "inner"
                                
                                    func pong() -> void:
                                        pass
                                """
                )),
                Map.of("PreparationOuter", "RuntimePreparationOuter")
        );
        var context = new FrontendLoweringContext(
                module,
                new ClassRegistry(ExtensionApiLoader.loadDefault()),
                diagnostics
        );
        new FrontendLoweringAnalysisPass().run(context);
        new FrontendLoweringClassSkeletonPass().run(context);
        return new PreparedContext(context, diagnostics, module);
    }

    private static @NotNull PreparedContext prepareNoInitializerCompileReadyContext() throws Exception {
        var diagnostics = new DiagnosticManager();
        var module = parseModule(
                List.of(new SourceFixture(
                        "preparation_no_initializer.gd",
                        """
                                class_name PreparationNoInit
                                extends RefCounted
                                
                                var count: int
                                
                                func ping() -> void:
                                    pass
                                """
                )),
                Map.of("PreparationNoInit", "RuntimePreparationNoInit")
        );
        var context = new FrontendLoweringContext(
                module,
                new ClassRegistry(ExtensionApiLoader.loadDefault()),
                diagnostics
        );
        new FrontendLoweringAnalysisPass().run(context);
        new FrontendLoweringClassSkeletonPass().run(context);
        return new PreparedContext(context, diagnostics, module);
    }

    private static @NotNull PreparedContext prepareFactRichCompileReadyContext() throws Exception {
        var diagnostics = new DiagnosticManager();
        var module = parseModule(
                List.of(new SourceFixture(
                        "preparation_fact_rich.gd",
                        """
                                class_name PreparationFacts
                                extends RefCounted
                                
                                class Handle:
                                    func read() -> int:
                                        return 1
                                
                                class Worker:
                                    var handle: Handle = Handle.new()
                                
                                    static func build() -> Worker:
                                        return Worker.new()
                                
                                var ready_value: int = Worker.build().handle.read()
                                
                                func ping(value: int) -> int:
                                    return value
                                """
                )),
                Map.of("PreparationFacts", "RuntimePreparationFacts")
        );
        var context = new FrontendLoweringContext(
                module,
                new ClassRegistry(ExtensionApiLoader.loadDefault()),
                diagnostics
        );
        new FrontendLoweringAnalysisPass().run(context);
        new FrontendLoweringClassSkeletonPass().run(context);
        return new PreparedContext(context, diagnostics, module);
    }

    private static @NotNull SharedAnalyzedModule analyzeSharedModule(
            @NotNull List<SourceFixture> fixtures,
            @NotNull Map<String, String> topLevelCanonicalNameMap
    ) throws Exception {
        var module = parseModule(fixtures, topLevelCanonicalNameMap);
        var diagnostics = new DiagnosticManager();
        var analysisData = new FrontendSemanticAnalyzer().analyze(
                module,
                new ClassRegistry(ExtensionApiLoader.loadDefault()),
                diagnostics
        );
        return new SharedAnalyzedModule(module, analysisData, diagnostics);
    }

    private static @NotNull FrontendModule parseModule(
            @NotNull List<SourceFixture> fixtures,
            @NotNull Map<String, String> topLevelCanonicalNameMap
    ) {
        var parserService = new GdScriptParserService();
        var parseDiagnostics = new DiagnosticManager();
        var units = fixtures.stream()
                .map(fixture -> parserService.parseUnit(Path.of("tmp", fixture.fileName()), fixture.source(), parseDiagnostics))
                .toList();
        assertTrue(parseDiagnostics.isEmpty(), () -> "Unexpected parse diagnostics: " + parseDiagnostics.snapshot());
        return new FrontendModule("test_module", units, topLevelCanonicalNameMap);
    }

    private static @NotNull LirClassDef requireClass(@NotNull LirModule lirModule, @NotNull String className) {
        return lirModule.getClassDefs().stream()
                .filter(classDef -> classDef.getName().equals(className))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing class " + className));
    }

    private static @NotNull LirFunctionDef requireFunction(@NotNull LirClassDef classDef, @NotNull String functionName) {
        return classDef.getFunctions().stream()
                .filter(function -> function.getName().equals(functionName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing function " + classDef.getName() + "." + functionName));
    }

    private static @NotNull LirPropertyDef requireProperty(@NotNull LirClassDef classDef, @NotNull String propertyName) {
        return classDef.getProperties().stream()
                .filter(property -> property.getName().equals(propertyName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing property " + classDef.getName() + "." + propertyName));
    }

    private static @NotNull GdObjectType outerClassAsType(@NotNull LirClassDef classDef) {
        return new GdObjectType(classDef.getName());
    }

    private static @NotNull FunctionLoweringContext requireContext(
            @NotNull List<FunctionLoweringContext> contexts,
            @NotNull FunctionLoweringContext.Kind kind,
            @NotNull String owningClassName,
            @NotNull String functionName
    ) {
        return contexts.stream()
                .filter(context -> context.kind() == kind)
                .filter(context -> context.owningClass().getName().equals(owningClassName))
                .filter(context -> context.targetFunction().getName().equals(functionName))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Missing context " + kind + " " + owningClassName + "." + functionName
                ));
    }

    private static <T extends Statement> @NotNull T requireStatement(
            @NotNull List<Statement> statements,
            @NotNull Class<T> statementType,
            @NotNull Predicate<T> predicate
    ) {
        return statements.stream()
                .filter(statementType::isInstance)
                .map(statementType::cast)
                .filter(predicate)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing statement " + statementType.getSimpleName()));
    }

    private static <T extends Node> @NotNull T findNode(
            @NotNull Node root,
            @NotNull Class<T> nodeType,
            @NotNull Predicate<T> predicate
    ) {
        if (nodeType.isInstance(root)) {
            var candidate = nodeType.cast(root);
            if (predicate.test(candidate)) {
                return candidate;
            }
        }
        for (var child : root.getChildren()) {
            try {
                return findNode(child, nodeType, predicate);
            } catch (AssertionError ignored) {
            }
        }
        throw new AssertionError("Node not found: " + nodeType.getSimpleName());
    }

    private record PreparedContext(
            @NotNull FrontendLoweringContext context,
            @NotNull DiagnosticManager diagnostics,
            @NotNull FrontendModule module
    ) {
    }

    private record SourceFixture(
            @NotNull String fileName,
            @NotNull String source
    ) {
    }

    private record SharedAnalyzedModule(
            @NotNull FrontendModule module,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull DiagnosticManager diagnostics
    ) {
    }
}
