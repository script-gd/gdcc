package gd.script.gdcc.frontend.sema.analyzer;

import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.scope.ClassScope;
import gd.script.gdcc.frontend.sema.FrontendAstSideTable;
import gd.script.gdcc.frontend.sema.FrontendAnalysisData;
import gd.script.gdcc.frontend.sema.FrontendDeclaredTypeSupport;
import gd.script.gdcc.frontend.sema.FrontendGdAnnotation;
import gd.script.gdcc.frontend.sema.analyzer.support.FrontendExportAnnotationSupport;
import gd.script.gdcc.scope.ClassDef;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.scope.PropertyDef;
import gd.script.gdcc.scope.Scope;
import gd.script.gdcc.type.GdArrayType;
import gd.script.gdcc.type.GdColorType;
import gd.script.gdcc.type.GdDictionaryType;
import gd.script.gdcc.type.GdFloatType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdNodePathType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdPackedArrayType;
import gd.script.gdcc.type.GdStringType;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.type.GdVariantType;
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
            // The `SourceFile` container itself never passes through `walkNode`, so script-level
            // annotations attached to it (the only legal `@tool` placement) are validated here.
            validateToolUsage(sourceFile);
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
            validateToolUsage(node);
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

        /// Export-family validation: placement (class `var` property only), staticness, argument
        /// structure (via the shared export helper), and property-type compatibility. Each
        /// annotation is checked independently and the first violated rule wins, so one bad
        /// annotation never cascades; multiple export annotations on one property deliberately
        /// coexist without a diagnostic (a documented difference from Godot's hard error).
        private void validateExportUsage(@NotNull Node annotatedNode) {
            for (var annotation : analysisData.annotationsByAst().getOrDefault(annotatedNode, List.of())) {
                if (FrontendExportAnnotationSupport.isExportFamilyAnnotation(annotation.name())) {
                    validateSingleExportAnnotation(annotatedNode, annotation);
                }
            }
        }

        private void validateSingleExportAnnotation(@NotNull Node annotatedNode, @NotNull FrontendGdAnnotation annotation) {
            var name = annotation.name();
            if (!(annotatedNode instanceof VariableDeclaration variableDeclaration)
                    || variableDeclaration.kind() != DeclarationKind.VAR
                    || !(scopesByAst.get(variableDeclaration) instanceof ClassScope propertyScope)) {
                reportInvalidUsage(annotation, "@" + name + " can only be used on class properties declared with 'var'");
                return;
            }
            // Godot rejects every export annotation on a static variable (export registers
            // per-instance storage, which a static property never has).
            if (variableDeclaration.isStatic()) {
                reportInvalidUsage(annotation, "@" + name + " cannot be used on static property '" + variableDeclaration.name() + "'");
                return;
            }
            if (FrontendExportAnnotationSupport.evaluate(annotation)
                    instanceof FrontendExportAnnotationSupport.Evaluation.Malformed malformed) {
                reportInvalidUsage(annotation, malformed.reason());
                return;
            }
            var propertyDef = findClassProperty(propertyScope.getCurrentClass(), variableDeclaration.name().trim());
            if (propertyDef == null) {
                // The skeleton rejected this property subtree; the upstream diagnostic owns it.
                return;
            }
            switch (resolveEffectiveExportType(variableDeclaration, propertyDef)) {
                case EffectiveExportType.UpstreamBlocked upstreamBlocked -> {
                    // The initializer subtree already carries an upstream blocking diagnostic;
                    // stacking a determinability error on the same root cause would double-report.
                }
                case EffectiveExportType.VariantTyped variantTyped -> {
                    // A legitimately Variant-typed property is exportable as Variant; Godot's
                    // default type check skips Variant, only the custom multiline check rejects it.
                    if (name.equals("export_multiline")) {
                        reportExportTypeMismatch(annotation, variableDeclaration.name(), GdVariantType.VARIANT, "String");
                    }
                }
                case EffectiveExportType.Undetermined undetermined -> {
                    // Godot rejects bare `@export` only when neither a type specifier nor an
                    // initializer exists ("type can't be inferred"); the default type check skips
                    // a still-undetermined Variant, only multiline's custom check fails here.
                    if (name.equals("export")) {
                        reportInvalidUsage(
                                annotation,
                                "@export requires a determinable property type, but '" + variableDeclaration.name()
                                        + "' has neither a type annotation nor an inferable initializer"
                        );
                    } else if (name.equals("export_multiline")) {
                        reportExportTypeMismatch(annotation, variableDeclaration.name(), GdVariantType.VARIANT, "String");
                    }
                }
                case EffectiveExportType.Determined determined ->
                        checkDeterminedExportType(annotation, variableDeclaration, propertyScope, determined.type());
            }
        }

        /// Type-compatibility rules per variant. Container-typed properties are deliberately
        /// rejected for every variant (Godot's element-peel encoding is out of scope), while
        /// bare `export` stays unrestricted. The default-check family (range/flags/layer
        /// flags/exp_easing) accepts `int` and `float` interchangeably, matching Godot.
        private void checkDeterminedExportType(
                @NotNull FrontendGdAnnotation annotation,
                @NotNull VariableDeclaration variableDeclaration,
                @NotNull ClassScope propertyScope,
                @NotNull GdType type
        ) {
            var name = annotation.name();
            var propertyName = variableDeclaration.name();
            var container = type instanceof GdArrayType
                    || type instanceof GdDictionaryType
                    || type instanceof GdPackedArrayType;
            if (container && !name.equals("export")) {
                reportExportTypeMismatch(annotation, propertyName, type, allowedExportTypesText(name));
                return;
            }
            switch (name) {
                case "export" -> validateBareExportType(annotation, propertyName, propertyScope, type);
                case "export_range" -> requireExportType(
                        annotation, propertyName, type,
                        type instanceof GdIntType || type instanceof GdFloatType
                );
                case "export_enum" -> requireExportType(
                        annotation, propertyName, type,
                        type instanceof GdIntType || type instanceof GdStringType
                );
                case "export_flags", "export_flags_2d_render", "export_flags_2d_physics",
                     "export_flags_2d_navigation", "export_flags_3d_render", "export_flags_3d_physics",
                     "export_flags_3d_navigation", "export_flags_avoidance" -> requireExportType(
                        annotation, propertyName, type,
                        type instanceof GdIntType || type instanceof GdFloatType
                );
                case "export_file", "export_file_path", "export_dir", "export_global_file",
                     "export_global_dir", "export_multiline", "export_placeholder" -> requireExportType(
                        annotation, propertyName, type,
                        type instanceof GdStringType
                );
                case "export_exp_easing" -> requireExportType(
                        annotation, propertyName, type,
                        type instanceof GdFloatType || type instanceof GdIntType
                );
                case "export_color_no_alpha" -> requireExportType(
                        annotation, propertyName, type,
                        type instanceof GdColorType
                );
                case "export_node_path" -> requireExportType(
                        annotation, propertyName, type,
                        type instanceof GdNodePathType
                );
                default -> {
                }
            }
        }

        /// Bare `export` Object-family rules: only Resource- or Node-derived objects are
        /// exportable, and exporting a Node type additionally requires a Node-derived owner class
        /// (Godot parity — the editor would otherwise attach an unplaceable node reference).
        private void validateBareExportType(
                @NotNull FrontendGdAnnotation annotation,
                @NotNull String propertyName,
                @NotNull ClassScope propertyScope,
                @NotNull GdType type
        ) {
            if (!(type instanceof GdObjectType objectType)) {
                return;
            }
            var nodeDerived = classRegistry.checkAssignable(objectType, new GdObjectType("Node"));
            var resourceDerived = classRegistry.checkAssignable(objectType, new GdObjectType("Resource"));
            if (!nodeDerived && !resourceDerived) {
                reportInvalidUsage(
                        annotation,
                        "@export can only export built-in, Resource, Node, or enum types, but '"
                                + propertyName + "' is " + type.getTypeName()
                );
                return;
            }
            if (nodeDerived && !isNodeDerived(propertyScope.getCurrentClass())) {
                reportInvalidUsage(
                        annotation,
                        "@export of Node type is only supported in Node-derived classes, but '"
                                + propertyName + "' is declared in '" + propertyScope.getCurrentClass().getName() + "'"
                );
            }
        }

        private void requireExportType(
                @NotNull FrontendGdAnnotation annotation,
                @NotNull String propertyName,
                @NotNull GdType type,
                boolean compatible
        ) {
            if (!compatible) {
                reportExportTypeMismatch(annotation, propertyName, type, allowedExportTypesText(annotation.name()));
            }
        }

        private void reportExportTypeMismatch(
                @NotNull FrontendGdAnnotation annotation,
                @NotNull String propertyName,
                @NotNull GdType type,
                @NotNull String allowedTypesText
        ) {
            reportInvalidUsage(
                    annotation,
                    "@" + annotation.name() + " can only be used on properties of type " + allowedTypesText
                            + ", but '" + propertyName + "' is " + type.getTypeName()
            );
        }

        /// The allowed-type text shown in mismatch diagnostics, phrased after Godot's accepted
        /// sets (the int/float interchangeability of the default-check family is not repeated in
        /// the wording).
        private @NotNull String allowedExportTypesText(@NotNull String name) {
            return switch (name) {
                case "export_range" -> "int or float";
                case "export_enum" -> "int, String, or Variant";
                case "export_flags", "export_flags_2d_render", "export_flags_2d_physics",
                     "export_flags_2d_navigation", "export_flags_3d_render", "export_flags_3d_physics",
                     "export_flags_3d_navigation", "export_flags_avoidance" -> "int";
                case "export_exp_easing" -> "float";
                case "export_color_no_alpha" -> "Color";
                case "export_node_path" -> "NodePath";
                default -> "String";
            };
        }

        /// Effective export type after applying the type-fact source rules: an explicitly
        /// declared non-Variant type wins; otherwise the initializer's published expression type
        /// provides the fallback (this analyzer runs after expression typing).
        private @NotNull EffectiveExportType resolveEffectiveExportType(
                @NotNull VariableDeclaration variableDeclaration,
                @NotNull PropertyDef propertyDef
        ) {
            var declaredType = propertyDef.getType();
            if (!(declaredType instanceof GdVariantType)) {
                return new EffectiveExportType.Determined(declaredType);
            }
            var initializer = variableDeclaration.value();
            if (initializer == null) {
                // An explicit `: Variant` annotation is itself a determinate (Variant) export
                // type; only a declaration without any type information fails determinability.
                var typeRef = variableDeclaration.type();
                return typeRef != null && !FrontendDeclaredTypeSupport.isInferredTypeRef(typeRef)
                        ? new EffectiveExportType.VariantTyped()
                        : new EffectiveExportType.Undetermined();
            }
            var fact = Objects.requireNonNull(
                    analysisData.expressionTypes().get(initializer),
                    "Property initializer expression type has not been published yet"
            );
            return switch (fact.status()) {
                case RESOLVED -> fact.publishedType() instanceof GdVariantType
                        ? new EffectiveExportType.VariantTyped()
                        : new EffectiveExportType.Determined(fact.publishedType());
                case DYNAMIC -> new EffectiveExportType.VariantTyped();
                case BLOCKED, DEFERRED, FAILED, UNSUPPORTED -> new EffectiveExportType.UpstreamBlocked();
            };
        }

        private @Nullable PropertyDef findClassProperty(@NotNull ClassDef classDef, @NotNull String propertyName) {
            return classDef.getProperties().stream()
                    .filter(property -> property.getName().equals(propertyName))
                    .findFirst()
                    .orElse(null);
        }

        private sealed interface EffectiveExportType {
            /// A concrete non-Variant type; full per-variant compatibility checks apply.
            record Determined(@NotNull GdType type) implements EffectiveExportType {
            }

            /// Legitimately Variant-typed (explicit `: Variant` annotation or an initializer
            /// that only infers Variant): exportable as Variant; the default type check and
            /// `export_enum` skip Variant, only the custom multiline check rejects it.
            record VariantTyped() implements EffectiveExportType {
            }

            /// Nothing determines the type (no type annotation, no `:=`, no initializer): bare
            /// export and multiline fail; other variants skip (Godot default-check parity).
            record Undetermined() implements EffectiveExportType {
            }

            /// The initializer subtree already carries an upstream blocking diagnostic;
            /// determinability diagnostics are suppressed to keep single-owner reporting.
            record UpstreamBlocked() implements EffectiveExportType {
            }
        }

        /// `@tool` is script-level in Godot (`AnnotationInfo::SCRIPT`): only the zero-argument
        /// form attached to the top-level `SourceFile` is legal. Inner classes, members, and
        /// trailing anchors are rejected to match Godot, which reports `@tool` outside the script
        /// top as an error instead of a per-class marker. When one annotation violates both rules
        /// the placement error takes precedence and arity is not double-reported.
        private void validateToolUsage(@NotNull Node annotatedNode) {
            for (var toolAnnotation : findAnnotationsByName(annotatedNode, "tool")) {
                if (!(annotatedNode instanceof SourceFile)) {
                    reportInvalidUsage(
                            toolAnnotation,
                            "@tool can only be used at the top of the script, before \"extends\" and \"class_name\""
                    );
                    continue;
                }
                if (!toolAnnotation.arguments().isEmpty()) {
                    reportInvalidUsage(toolAnnotation, "@tool does not accept any arguments");
                }
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

        private @NotNull List<FrontendGdAnnotation> findAnnotationsByName(@NotNull Node annotatedNode, @NotNull String name) {
            return analysisData.annotationsByAst().getOrDefault(annotatedNode, List.of()).stream()
                    .filter(annotation -> annotation.name().equals(name))
                    .toList();
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
