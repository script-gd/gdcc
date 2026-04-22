package dev.superice.gdcc.frontend.sema.analyzer;

import dev.superice.gdcc.frontend.diagnostic.DiagnosticManager;
import dev.superice.gdcc.frontend.diagnostic.FrontendRange;
import dev.superice.gdcc.frontend.sema.FrontendAnalysisData;
import dev.superice.gdcc.frontend.sema.FrontendAstSideTable;
import dev.superice.gdcc.frontend.sema.FrontendOwnedClassRelation;
import dev.superice.gdcc.frontend.sema.FrontendSourceClassRelation;
import dev.superice.gdcc.lir.LirClassDef;
import dev.superice.gdcc.lir.LirFunctionDef;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.scope.FunctionDef;
import dev.superice.gdcc.scope.Scope;
import dev.superice.gdparser.frontend.ast.ClassDeclaration;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.SourceFile;
import dev.superice.gdparser.frontend.ast.Statement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;

/// Diagnostics-only validator for source methods that match inherited engine virtual names.
///
/// Skeleton publication intentionally stays permissive here: even a bad override header still keeps
/// its published function metadata so later body analyzers can continue producing facts for
/// inspection/LSP consumers. This phase only reports signature errors and never skips the subtree.
public class FrontendVirtualOverrideAnalyzer {
    private static final @NotNull String VIRTUAL_OVERRIDE_CATEGORY = "sema.virtual_override";

    private @Nullable Path sourcePath;
    private @Nullable FrontendSourceClassRelation sourceClassRelation;
    private @Nullable ClassRegistry classRegistry;
    private @Nullable FrontendAstSideTable<Scope> scopesByAst;
    private @Nullable DiagnosticManager diagnosticManager;

    public void analyze(
            @NotNull ClassRegistry classRegistry,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull DiagnosticManager diagnosticManager
    ) {
        Objects.requireNonNull(classRegistry, "classRegistry must not be null");
        Objects.requireNonNull(analysisData, "analysisData must not be null");
        Objects.requireNonNull(diagnosticManager, "diagnosticManager must not be null");

        clearAnalyzeState();
        var moduleSkeleton = analysisData.moduleSkeleton();
        analysisData.diagnostics();

        var publishedScopes = analysisData.scopesByAst();
        for (var relation : moduleSkeleton.sourceClassRelations()) {
            var sourceFile = relation.unit().ast();
            if (!publishedScopes.containsKey(sourceFile)) {
                throw new IllegalStateException(
                        "Scope graph has not been published for source file: " + relation.unit().path()
                );
            }
        }

        try {
            this.classRegistry = classRegistry;
            this.scopesByAst = publishedScopes;
            this.diagnosticManager = diagnosticManager;
            for (var relation : moduleSkeleton.sourceClassRelations()) {
                this.sourcePath = relation.unit().path();
                this.sourceClassRelation = relation;
                walkSourceFile(relation.unit().ast());
            }
        } finally {
            clearAnalyzeState();
        }
    }

    /// The framework may reuse one analyzer instance across tests or tooling entrypoints, so every
    /// analyze run starts from a clean per-run traversal context.
    private void clearAnalyzeState() {
        sourcePath = null;
        sourceClassRelation = null;
        classRegistry = null;
        scopesByAst = null;
        diagnosticManager = null;
    }

    private void walkSourceFile(@NotNull SourceFile sourceFile) {
        walkClassContainer(sourceFile, sourceFile.statements());
    }

    private void walkClassContainer(@NotNull Node classOwner, @NotNull List<Statement> statements) {
        var owningClass = requireClassRelation(classOwner);
        for (var statement : statements) {
            if (statement instanceof FunctionDeclaration functionDeclaration) {
                validateFunctionOverride(owningClass, functionDeclaration);
                continue;
            }
            if (statement instanceof ClassDeclaration classDeclaration) {
                if (!requireScopesByAst().containsKey(classDeclaration)) {
                    continue;
                }
                walkClassContainer(classDeclaration, classDeclaration.body().statements());
            }
        }
    }

    private void validateFunctionOverride(
            @NotNull FrontendOwnedClassRelation owningClass,
            @NotNull FunctionDeclaration functionDeclaration
    ) {
        if (!requireScopesByAst().containsKey(functionDeclaration)) {
            return;
        }

        var publishedFunction = findPublishedFunction(owningClass.classDef(), functionDeclaration.name());
        if (publishedFunction == null) {
            return;
        }
        var engineVirtual = findEngineVirtual(owningClass.classDef(), publishedFunction.getName());
        if (engineVirtual == null) {
            return;
        }

        if (publishedFunction.isStatic()) {
            report(
                    FrontendRange.fromAstRange(functionDeclaration.range()),
                    "Function '" + publishedFunction.getName()
                            + "' in class '" + owningClass.displayName()
                            + "' matches engine virtual '" + formatSignature(engineVirtual)
                            + "' but is declared static; engine virtual overrides must remain instance methods"
            );
            return;
        }
        if (publishedFunction.isVararg() != engineVirtual.function().isVararg()) {
            report(
                    FrontendRange.fromAstRange(functionDeclaration.range()),
                    "Function '" + publishedFunction.getName()
                            + "' in class '" + owningClass.displayName()
                            + "' matches engine virtual '" + formatSignature(engineVirtual)
                            + "' but its variadic contract does not match"
            );
            return;
        }
        if (publishedFunction.getParameterCount() != engineVirtual.function().getParameterCount()) {
            report(
                    FrontendRange.fromAstRange(functionDeclaration.range()),
                    "Function '" + publishedFunction.getName()
                            + "' in class '" + owningClass.displayName()
                            + "' matches engine virtual '" + formatSignature(engineVirtual)
                            + "' but declares " + publishedFunction.getParameterCount()
                            + " parameter(s); expected " + engineVirtual.function().getParameterCount()
            );
            return;
        }

        for (var parameterIndex = 0; parameterIndex < publishedFunction.getParameterCount(); parameterIndex++) {
            var publishedParameter = requirePublishedParameter(publishedFunction, parameterIndex);
            var expectedParameter = requireVirtualParameter(engineVirtual.function(), parameterIndex);
            if (publishedParameter.getType().equals(expectedParameter.getType())) {
                continue;
            }
            var sourceParameter = functionDeclaration.parameters().get(parameterIndex);
            report(
                    FrontendRange.fromAstRange(
                            sourceParameter.type() != null ? sourceParameter.type().range() : sourceParameter.range()
                    ),
                    "Function '" + publishedFunction.getName()
                            + "' in class '" + owningClass.displayName()
                            + "' matches engine virtual '" + formatSignature(engineVirtual)
                            + "' but parameter #" + (parameterIndex + 1)
                            + " '" + sourceParameter.name() + "' has type '"
                            + publishedParameter.getType().getTypeName() + "'; expected '"
                            + expectedParameter.getType().getTypeName() + "'"
            );
            return;
        }

        if (publishedFunction.getReturnType().equals(engineVirtual.function().getReturnType())) {
            return;
        }
        report(
                FrontendRange.fromAstRange(
                        functionDeclaration.returnType() != null
                                ? functionDeclaration.returnType().range()
                                : functionDeclaration.range()
                ),
                "Function '" + publishedFunction.getName()
                        + "' in class '" + owningClass.displayName()
                        + "' matches engine virtual '" + formatSignature(engineVirtual)
                        + "' but returns '" + publishedFunction.getReturnType().getTypeName()
                        + "'; expected '" + engineVirtual.function().getReturnType().getTypeName() + "'"
        );
    }

    private @Nullable LirFunctionDef findPublishedFunction(
            @NotNull LirClassDef classDef,
            @NotNull String functionName
    ) {
        return classDef.getFunctions().stream()
                .filter(function -> function.getName().equals(functionName))
                .findFirst()
                .orElse(null);
    }

    private @Nullable EngineVirtualMatch findEngineVirtual(
            @NotNull LirClassDef currentClass,
            @NotNull String functionName
    ) {
        var engineVirtual = requireClassRegistry().findEngineVirtualMethod(currentClass.getName(), functionName);
        if (engineVirtual == null) {
            return null;
        }
        return new EngineVirtualMatch(engineVirtual.ownerClassName(), engineVirtual.function());
    }

    private @NotNull FrontendOwnedClassRelation requireClassRelation(@NotNull Node classOwner) {
        var relation = requireSourceClassRelation().findRelation(classOwner);
        if (relation != null) {
            return relation;
        }
        throw new IllegalStateException(
                "Class relation has not been published for node: " + classOwner.getClass().getSimpleName()
        );
    }

    private @NotNull ClassRegistry requireClassRegistry() {
        return Objects.requireNonNull(classRegistry, "classRegistry must not be null");
    }

    private @NotNull FrontendSourceClassRelation requireSourceClassRelation() {
        return Objects.requireNonNull(sourceClassRelation, "sourceClassRelation must not be null");
    }

    private @NotNull FrontendAstSideTable<Scope> requireScopesByAst() {
        return Objects.requireNonNull(scopesByAst, "scopesByAst must not be null");
    }

    private @NotNull DiagnosticManager requireDiagnosticManager() {
        return Objects.requireNonNull(diagnosticManager, "diagnosticManager must not be null");
    }

    private @NotNull Path requireSourcePath() {
        return Objects.requireNonNull(sourcePath, "sourcePath must not be null");
    }

    private @NotNull dev.superice.gdcc.scope.ParameterDef requirePublishedParameter(
            @NotNull LirFunctionDef function,
            int index
    ) {
        return Objects.requireNonNull(
                function.getParameter(index),
                "Published function parameter must not be null"
        );
    }

    private @NotNull dev.superice.gdcc.scope.ParameterDef requireVirtualParameter(
            @NotNull FunctionDef function,
            int index
    ) {
        return Objects.requireNonNull(
                function.getParameter(index),
                "Engine virtual parameter must not be null"
        );
    }

    private void report(
            @NotNull FrontendRange range,
            @NotNull String message
    ) {
        requireDiagnosticManager().error(
                VIRTUAL_OVERRIDE_CATEGORY,
                message,
                requireSourcePath(),
                range
        );
    }

    private record EngineVirtualMatch(
            @NotNull String ownerClassName,
            @NotNull FunctionDef function
    ) {
        private EngineVirtualMatch {
            Objects.requireNonNull(ownerClassName, "ownerClassName must not be null");
            Objects.requireNonNull(function, "function must not be null");
        }
    }

    private static @NotNull String formatSignature(@NotNull EngineVirtualMatch engineVirtual) {
        var parameters = new StringJoiner(", ");
        for (var parameter : engineVirtual.function().getParameters()) {
            parameters.add(parameter.getName() + ": " + parameter.getType().getTypeName());
        }
        return engineVirtual.ownerClassName()
                + "." + engineVirtual.function().getName()
                + "(" + parameters + ") -> " + engineVirtual.function().getReturnType().getTypeName();
    }
}
