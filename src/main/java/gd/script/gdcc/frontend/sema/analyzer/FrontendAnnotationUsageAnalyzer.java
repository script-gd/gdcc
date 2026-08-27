package gd.script.gdcc.frontend.sema.analyzer;

import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.scope.ClassScope;
import gd.script.gdcc.frontend.sema.FrontendAstSideTable;
import gd.script.gdcc.frontend.sema.FrontendAnalysisData;
import gd.script.gdcc.frontend.sema.FrontendGdAnnotation;
import gd.script.gdcc.scope.ClassDef;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.scope.Scope;
import gd.script.gdcc.type.GdObjectType;
import dev.superice.gdparser.frontend.ast.ClassDeclaration;
import dev.superice.gdparser.frontend.ast.DeclarationKind;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.SourceFile;
import dev.superice.gdparser.frontend.ast.Statement;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/// Diagnostics-only annotation placement validator for the currently supported subset.
///
/// Current `@onready` contract stays intentionally small: skeleton retains the annotation, then
/// this analyzer validates owner-class and staticness placement without introducing runtime
/// `_ready()` timing semantics.
///
/// `@export` is retained by the skeleton for ClassDB usage metadata, but Godot rejects every
/// export annotation on a static variable (export registers per-instance storage, which a static
/// property never has). gdcc mirrors that rejection here because there is no parser-level
/// annotation validation layer.
public class FrontendAnnotationUsageAnalyzer {
    private static final @NotNull String ANNOTATION_USAGE_CATEGORY = "sema.annotation_usage";

    public void analyze(
            @NotNull ClassRegistry classRegistry,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull DiagnosticManager diagnosticManager
    ) {
        Objects.requireNonNull(classRegistry, "classRegistry must not be null");
        Objects.requireNonNull(analysisData, "analysisData must not be null");
        Objects.requireNonNull(diagnosticManager, "diagnosticManager must not be null");

        var moduleSkeleton = analysisData.moduleSkeleton();
        analysisData.diagnostics();

        var scopesByAst = analysisData.scopesByAst();
        for (var sourceClassRelation : moduleSkeleton.sourceClassRelations()) {
            var sourceFile = sourceClassRelation.unit().ast();
            if (!scopesByAst.containsKey(sourceFile)) {
                throw new IllegalStateException(
                        "Scope graph has not been published for source file: " + sourceClassRelation.unit().path()
                );
            }
        }

        for (var sourceClassRelation : moduleSkeleton.sourceClassRelations()) {
            new AnnotationUsageVisitor(
                    sourceClassRelation.unit().path(),
                    classRegistry,
                    analysisData,
                    scopesByAst,
                    diagnosticManager
            ).walkSourceFile(sourceClassRelation.unit().ast());
        }
    }

    private static final class AnnotationUsageVisitor {
        private final @NotNull Path sourcePath;
        private final @NotNull ClassRegistry classRegistry;
        private final @NotNull FrontendAnalysisData analysisData;
        private final @NotNull FrontendAstSideTable<Scope> scopesByAst;
        private final @NotNull DiagnosticManager diagnosticManager;

        private AnnotationUsageVisitor(
                @NotNull Path sourcePath,
                @NotNull ClassRegistry classRegistry,
                @NotNull FrontendAnalysisData analysisData,
                @NotNull FrontendAstSideTable<Scope> scopesByAst,
                @NotNull DiagnosticManager diagnosticManager
        ) {
            this.sourcePath = Objects.requireNonNull(sourcePath, "sourcePath must not be null");
            this.classRegistry = Objects.requireNonNull(classRegistry, "classRegistry must not be null");
            this.analysisData = Objects.requireNonNull(analysisData, "analysisData must not be null");
            this.scopesByAst = Objects.requireNonNull(scopesByAst, "scopesByAst must not be null");
            this.diagnosticManager = Objects.requireNonNull(diagnosticManager, "diagnosticManager must not be null");
        }

        private void walkSourceFile(@NotNull SourceFile sourceFile) {
            walkClassContainer(sourceFile, sourceFile.statements());
        }

        private void walkClassContainer(@NotNull Node classOwner, @NotNull List<Statement> statements) {
            // Annotation usage no longer needs to cache per-container class context because each
            // supported property declaration already carries its owning `ClassScope`. This barrier
            // remains to fail fast if a published class container somehow lost its class scope.
            assertPublishedClassScope(classOwner);
            for (var statement : statements) {
                walkNode(statement);
            }
        }

        private void walkNode(@Nullable Node node) {
            if (node == null) {
                return;
            }
            validateOnreadyUsage(node);
            validateExportUsage(node);
            if (node instanceof ClassDeclaration classDeclaration) {
                if (!scopesByAst.containsKey(classDeclaration)) {
                    return;
                }
                walkClassContainer(classDeclaration, classDeclaration.body().statements());
                return;
            }
            for (var child : node.getChildren()) {
                walkNode(child);
            }
        }

        private void validateOnreadyUsage(@NotNull Node annotatedNode) {
            var onreadyAnnotation = findAnnotationByName(annotatedNode, "onready");
            if (onreadyAnnotation == null) {
                return;
            }

            if (!(annotatedNode instanceof VariableDeclaration variableDeclaration)
                    || variableDeclaration.kind() != DeclarationKind.VAR
                    || !(scopesByAst.get(variableDeclaration) instanceof ClassScope propertyScope)) {
                reportInvalidUsage(
                        onreadyAnnotation,
                        "@onready can only be used on class properties declared with 'var'"
                );
                return;
            }
            if (variableDeclaration.isStatic()) {
                reportInvalidUsage(
                        onreadyAnnotation,
                        "@onready cannot be used on static property '" + variableDeclaration.name() + "'"
                );
                return;
            }
            if (!isNodeDerived(propertyScope.getCurrentClass())) {
                reportInvalidUsage(
                        onreadyAnnotation,
                        "@onready property '" + variableDeclaration.name()
                                + "' requires an owner class that inherits from Node, but current class '"
                                + propertyScope.getCurrentClass().getName() + "' does not"
                );
            }
        }

        /// `@export` on a static property is rejected (Godot parity); unlike `@onready` there is
        /// no additional owner-class requirement, so non-static usage stays silent here.
        private void validateExportUsage(@NotNull Node annotatedNode) {
            var exportAnnotation = findAnnotationByName(annotatedNode, "export");
            if (exportAnnotation == null) {
                return;
            }
            if (annotatedNode instanceof VariableDeclaration variableDeclaration
                    && variableDeclaration.kind() == DeclarationKind.VAR
                    && variableDeclaration.isStatic()
                    && scopesByAst.get(variableDeclaration) instanceof ClassScope) {
                reportInvalidUsage(
                        exportAnnotation,
                        "@export cannot be used on static property '" + variableDeclaration.name() + "'"
                );
            }
        }

        private boolean isNodeDerived(@NotNull ClassDef classDef) {
            Objects.requireNonNull(classDef, "classDef must not be null");
            return classRegistry.checkAssignable(
                    new GdObjectType(classDef.getName()),
                    new GdObjectType("Node")
            );
        }

        private void reportInvalidUsage(
                @NotNull FrontendGdAnnotation annotation,
                @NotNull String message
        ) {
            Objects.requireNonNull(annotation, "annotation must not be null");
            Objects.requireNonNull(message, "message must not be null");
            diagnosticManager.error(
                    ANNOTATION_USAGE_CATEGORY,
                    message,
                    sourcePath,
                    annotation.range()
            );
        }

        private @Nullable FrontendGdAnnotation findAnnotationByName(@NotNull Node annotatedNode, @NotNull String name) {
            return analysisData.annotationsByAst().getOrDefault(annotatedNode, List.of()).stream()
                    .filter(annotation -> annotation.name().equals(name))
                    .findFirst()
                    .orElse(null);
        }

        private void assertPublishedClassScope(@NotNull Node classOwner) {
            var publishedScope = scopesByAst.get(Objects.requireNonNull(classOwner, "classOwner must not be null"));
            if (publishedScope instanceof ClassScope) {
                return;
            }
            throw new IllegalStateException("Class scope has not been published for node: " + classOwner.getClass().getSimpleName());
        }
    }
}
