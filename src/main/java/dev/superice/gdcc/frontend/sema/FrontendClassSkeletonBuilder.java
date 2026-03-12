package dev.superice.gdcc.frontend.sema;

import dev.superice.gdparser.frontend.ast.*;
import dev.superice.gdcc.frontend.diagnostic.DiagnosticManager;
import dev.superice.gdcc.frontend.diagnostic.FrontendRange;
import dev.superice.gdcc.frontend.parse.FrontendSourceUnit;
import dev.superice.gdcc.lir.LirClassDef;
import dev.superice.gdcc.lir.LirFunctionDef;
import dev.superice.gdcc.lir.LirParameterDef;
import dev.superice.gdcc.lir.LirPropertyDef;
import dev.superice.gdcc.lir.LirSignalDef;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.type.GdType;
import dev.superice.gdcc.type.GdVariantType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.*;

/// Build class skeletons from parsed AST units and inject top-level ones into ClassRegistry.
public final class FrontendClassSkeletonBuilder {
    /// Matches upstream Godot GDScript behavior: scripts/classes without an explicit `extends`
    /// default to `RefCounted`.
    private static final String DEFAULT_SUPER_CLASS_NAME = "RefCounted";

    /// Builds the module skeleton while appending all newly discovered skeleton diagnostics
    /// into the shared pipeline manager.
    ///
    /// The manager is expected to already own any parse-phase diagnostics collected from the same
    /// `FrontendSourceUnit`s. Units themselves no longer store parse diagnostics, so this builder
    /// must treat the shared manager as the only parse diagnostic source of truth.
    public @NotNull FrontendModuleSkeleton build(
            @NotNull String moduleName,
            @NotNull List<FrontendSourceUnit> units,
            @NotNull ClassRegistry classRegistry,
            @NotNull DiagnosticManager diagnosticManager,
            @NotNull FrontendAnalysisData analysisData
    ) {
        Objects.requireNonNull(moduleName, "moduleName must not be null");
        Objects.requireNonNull(units, "units must not be null");
        Objects.requireNonNull(classRegistry, "classRegistry must not be null");
        Objects.requireNonNull(diagnosticManager, "diagnosticManager must not be null");
        Objects.requireNonNull(analysisData, "analysisData must not be null");

        var classByName = new LinkedHashMap<String, ClassSkeletonCandidate>();
        var topLevelClassOrder = new ArrayList<ClassSkeletonCandidate>();
        var acceptedSourceRelationsByName = new LinkedHashMap<String, FrontendSourceClassRelation>();
        var annotationCollector = new FrontendAnnotationCollector();

        for (var unit : units) {
            // Reuse the shared analysis data side table so later phases see one stable
            // annotation ownership map instead of per-phase copies.
            analysisData.annotationsByAst().putAll(annotationCollector.collect(unit));
            var context = new SkeletonBuildContext(
                    classRegistry,
                    diagnosticManager,
                    unit.path(),
                    analysisData
            );
            var relation = buildSourceClassRelation(unit, context);

            var topLevelCandidate = new ClassSkeletonCandidate(
                    unit.path(),
                    FrontendRange.fromAstRange(topLevelClassRange(unit)),
                    relation.topLevelClassDef()
            );
            var existing = classByName.putIfAbsent(topLevelCandidate.classDef().getName(), topLevelCandidate);
            if (existing != null) {
                reportDuplicateTopLevelClass(existing, topLevelCandidate, diagnosticManager);
                continue;
            }
            acceptedSourceRelationsByName.put(topLevelCandidate.classDef().getName(), relation);
            topLevelClassOrder.add(topLevelCandidate);
        }

        var rejectedClassNames = detectRejectedTopLevelClasses(classByName, diagnosticManager);
        var sourceClassRelations = new ArrayList<FrontendSourceClassRelation>();

        for (var classCandidate : topLevelClassOrder) {
            if (rejectedClassNames.contains(classCandidate.classDef().getName())) {
                continue;
            }
            classRegistry.addGdccClass(classCandidate.classDef());
            sourceClassRelations.add(acceptedSourceRelationsByName.get(classCandidate.classDef().getName()));
        }

        return new FrontendModuleSkeleton(moduleName, sourceClassRelations, diagnosticManager.snapshot());
    }

    /// Builds one source-owned skeleton relation:
    /// - exactly one top-level script class
    /// - zero or more nested `ClassDeclaration -> LirClassDef` ownership pairs discovered under
    ///   `ClassDeclaration` subtrees
    ///
    /// The relation is stable even when later phases need more than one class skeleton from the
    /// same source file; callers no longer have to recover that ownership through list indexes.
    private @NotNull FrontendSourceClassRelation buildSourceClassRelation(
            @NotNull FrontendSourceUnit unit,
            @NotNull SkeletonBuildContext context
    ) {
        var classNameStatement = firstClassNameStatement(unit.ast().statements());
        var className = resolveClassName(unit.path(), classNameStatement);
        var superClassName = resolveSuperClassName(unit.ast().statements(), classNameStatement);

        var topLevelClassDef = buildClassSkeleton(
                className,
                superClassName,
                unit.ast().statements(),
                context
        );
        var innerClassRelations = collectInnerClassRelations(unit.ast().statements(), context);
        return new FrontendSourceClassRelation(unit, topLevelClassDef, innerClassRelations);
    }

    private @Nullable ClassNameStatement firstClassNameStatement(@NotNull List<Statement> statements) {
        for (var statement : statements) {
            if (statement instanceof ClassNameStatement classNameStatement) {
                return classNameStatement;
            }
        }
        return null;
    }

    private @NotNull String resolveClassName(@NotNull Path sourcePath, @Nullable ClassNameStatement classNameStatement) {
        if (classNameStatement != null && !classNameStatement.name().isBlank()) {
            return classNameStatement.name().trim();
        }
        return deriveClassNameFromFileName(sourcePath);
    }

    private @NotNull String resolveSuperClassName(
            @NotNull List<Statement> statements,
            @Nullable ClassNameStatement classNameStatement
    ) {
        if (classNameStatement != null && classNameStatement.extendsTarget() != null && !classNameStatement.extendsTarget().isBlank()) {
            return classNameStatement.extendsTarget().trim();
        }
        for (var statement : statements) {
            if (statement instanceof ExtendsStatement extendsStatement && !extendsStatement.target().isBlank()) {
                return extendsStatement.target().trim();
            }
        }
        return DEFAULT_SUPER_CLASS_NAME;
    }

    private @NotNull Range topLevelClassRange(@NotNull FrontendSourceUnit unit) {
        var classNameStatement = firstClassNameStatement(unit.ast().statements());
        return classNameStatement != null ? classNameStatement.range() : unit.ast().range();
    }

    /// Builds one `LirClassDef` from a statement list without recursively embedding child classes
    /// into the parent object. Nested classes are collected separately into
    /// `FrontendSourceClassRelation`, which keeps ownership explicit without overloading
    /// `LirClassDef` itself with new hierarchy semantics.
    private @NotNull LirClassDef buildClassSkeleton(
            @NotNull String className,
            @NotNull String superClassName,
            @NotNull List<Statement> statements,
            @NotNull SkeletonBuildContext context
    ) {
        var classDef = new LirClassDef(className, superClassName);
        classDef.setSourceFile(context.sourcePath().toString().replace('\\', '/'));

        for (var statement : statements) {
            switch (statement) {
                case SignalStatement signalStatement -> classDef.addSignal(toLirSignal(signalStatement, context));
                case VariableDeclaration variableDeclaration -> {
                    if (variableDeclaration.kind() == DeclarationKind.VAR) {
                        classDef.addProperty(toLirProperty(variableDeclaration, context));
                    }
                }
                case FunctionDeclaration functionDeclaration ->
                        classDef.addFunction(toLirFunction(functionDeclaration, context));
                default -> {
                }
            }
        }
        return classDef;
    }

    /// Recursively collects every non-top-level class declared inside the current statement list.
    ///
    /// The returned list is source-local metadata only for now:
    /// - it is preserved on `FrontendModuleSkeleton`
    /// - it is not injected into `ClassRegistry` yet, because global registration semantics for
    ///   nested classes remain a separate design question
    /// - malformed nested classes are diagnosed and skipped together with their own subtrees
    /// - each entry keeps both the parsed `ClassDeclaration` owner and its built skeleton so later
    ///   phases can materialize inner-class scope/binding state without guessing
    private @NotNull List<FrontendInnerClassRelation> collectInnerClassRelations(
            @NotNull List<Statement> statements,
            @NotNull SkeletonBuildContext context
    ) {
        var innerClassRelations = new ArrayList<FrontendInnerClassRelation>();
        for (var statement : statements) {
            if (!(statement instanceof ClassDeclaration classDeclaration)) {
                continue;
            }

            var innerClassName = resolveInnerClassName(classDeclaration, context);
            if (innerClassName == null) {
                continue;
            }
            var innerClassDef = buildClassSkeleton(
                    innerClassName,
                    resolveInnerClassSuperName(classDeclaration),
                    classDeclaration.body().statements(),
                    context
            );
            innerClassRelations.add(new FrontendInnerClassRelation(classDeclaration, innerClassDef));
            innerClassRelations.addAll(collectInnerClassRelations(classDeclaration.body().statements(), context));
        }
        return List.copyOf(innerClassRelations);
    }

    private @NotNull String resolveInnerClassSuperName(@NotNull ClassDeclaration classDeclaration) {
        var extendsTarget = classDeclaration.extendsTarget();
        if (extendsTarget == null || extendsTarget.isBlank()) {
            return DEFAULT_SUPER_CLASS_NAME;
        }
        return extendsTarget.trim();
    }

    private @Nullable String resolveInnerClassName(
            @NotNull ClassDeclaration classDeclaration,
            @NotNull SkeletonBuildContext context
    ) {
        var className = classDeclaration.name().trim();
        if (className.isEmpty()) {
            context.diagnostics().error(
                    "sema.class_skeleton",
                    "Inner class declaration is missing a class name and will be skipped",
                    context.sourcePath(),
                    FrontendRange.fromAstRange(classDeclaration.range())
            );
            return null;
        }
        return className;
    }

    private @NotNull LirSignalDef toLirSignal(
            @NotNull SignalStatement signalStatement,
            @NotNull SkeletonBuildContext context
    ) {
        var signalDef = new LirSignalDef(signalStatement.name().trim());
        for (var parameter : signalStatement.parameters()) {
            var parameterType = resolveTypeOrVariant(parameter.type(), context);
            signalDef.addParameter(new LirParameterDef(
                    parameter.name().trim(),
                    parameterType,
                    null,
                    signalDef
            ));
        }
        return signalDef;
    }

    private @NotNull LirPropertyDef toLirProperty(
            @NotNull VariableDeclaration variableDeclaration,
            @NotNull SkeletonBuildContext context
    ) {
        var propertyType = resolveTypeOrVariant(variableDeclaration.type(), context);
        var propertyDef = new LirPropertyDef(variableDeclaration.name().trim(), propertyType);
        propertyDef.setStatic(variableDeclaration.isStatic());
        applyPropertyAnnotations(variableDeclaration, propertyDef, context);
        return propertyDef;
    }

    private void applyPropertyAnnotations(
            @NotNull VariableDeclaration variableDeclaration,
            @NotNull LirPropertyDef propertyDef,
            @NotNull SkeletonBuildContext context
    ) {
        for (var annotation : context.analysisData().annotationsByAst().getOrDefault(variableDeclaration, List.of())) {
            switch (annotation.name()) {
                case "export" -> propertyDef.getAnnotations().put("export", "");
                case "onready" -> propertyDef.getAnnotations().put("onready", "");
                default -> reportUnsupportedPropertyAnnotation(annotation, variableDeclaration, context);
            }
        }
    }

    /// Skeleton build only understands the small property annotation subset that already maps to
    /// stable LIR metadata. Other parsed annotations are preserved in the shared side tables, but
    /// diagnosed here so later phases do not silently lose that unsupported semantic input.
    private void reportUnsupportedPropertyAnnotation(
            @NotNull FrontendGdAnnotation annotation,
            @NotNull VariableDeclaration variableDeclaration,
            @NotNull SkeletonBuildContext context
    ) {
        context.diagnostics().warning(
                "sema.unsupported_annotation",
                "Property annotation '@" + annotation.name()
                        + "' on '" + variableDeclaration.name()
                        + "' is not supported during skeleton build",
                context.sourcePath(),
                annotation.range()
        );
    }

    private @NotNull LirFunctionDef toLirFunction(
            @NotNull FunctionDeclaration functionDeclaration,
            @NotNull SkeletonBuildContext context
    ) {
        var functionDef = new LirFunctionDef(functionDeclaration.name().trim());
        functionDef.setReturnType(resolveTypeOrVariant(functionDeclaration.returnType(), context));

        for (var parameter : functionDeclaration.parameters()) {
            var parameterType = resolveTypeOrVariant(parameter.type(), context);
            functionDef.addParameter(new LirParameterDef(
                    parameter.name().trim(),
                    parameterType,
                    null,
                    functionDef
            ));
            if (parameter.variadic()) {
                functionDef.setVararg(true);
            }
        }
        return functionDef;
    }

    private @NotNull GdType resolveTypeOrVariant(
            @Nullable TypeRef typeRef,
            @NotNull SkeletonBuildContext context
    ) {
        if (typeRef == null || typeRef.sourceText().isBlank()) {
            return GdVariantType.VARIANT;
        }

        try {
            var resolvedType = context.classRegistry().findType(typeRef.sourceText().trim());
            if (resolvedType != null) {
                return resolvedType;
            }
        } catch (RuntimeException ignored) {
            // Preserve tolerant frontend behavior: downgrade to Variant with warning.
        }

        context.diagnostics().warning(
                "sema.type_resolution",
                "Unknown type '" + typeRef.sourceText() + "', fallback to Variant",
                context.sourcePath(),
                FrontendRange.fromAstRange(typeRef.range())
        );
        return GdVariantType.VARIANT;
    }

    private @NotNull Set<String> detectRejectedTopLevelClasses(
            @NotNull Map<String, ClassSkeletonCandidate> classByName,
            @NotNull DiagnosticManager diagnosticManager
    ) {
        var states = new HashMap<String, VisitState>();
        var visitStack = new ArrayList<String>();
        var rejectedClassNames = new LinkedHashSet<String>();
        for (var className : classByName.keySet()) {
            detectInheritanceProblemsDfs(
                    className,
                    classByName,
                    states,
                    visitStack,
                    rejectedClassNames,
                    diagnosticManager
            );
        }
        rejectClassesDependingOnRejectedSupers(classByName, rejectedClassNames, diagnosticManager);
        return Set.copyOf(rejectedClassNames);
    }

    private void detectInheritanceProblemsDfs(
            @NotNull String className,
            @NotNull Map<String, ClassSkeletonCandidate> classByName,
            @NotNull Map<String, VisitState> states,
            @NotNull List<String> visitStack,
            @NotNull Set<String> rejectedClassNames,
            @NotNull DiagnosticManager diagnosticManager
    ) {
        var state = states.getOrDefault(className, VisitState.UNVISITED);
        if (state == VisitState.VISITED) {
            return;
        }
        if (state == VisitState.VISITING) {
            reportInheritanceCycle(className, classByName, visitStack, rejectedClassNames, diagnosticManager);
            return;
        }

        states.put(className, VisitState.VISITING);
        visitStack.add(className);

        var superName = classByName.get(className).classDef().getSuperName();
        if (classByName.containsKey(superName)) {
            detectInheritanceProblemsDfs(
                    superName,
                    classByName,
                    states,
                    visitStack,
                    rejectedClassNames,
                    diagnosticManager
            );
        }

        visitStack.removeLast();
        states.put(className, VisitState.VISITED);
    }

    private void reportInheritanceCycle(
            @NotNull String reenteredClassName,
            @NotNull Map<String, ClassSkeletonCandidate> classByName,
            @NotNull List<String> visitStack,
            @NotNull Set<String> rejectedClassNames,
            @NotNull DiagnosticManager diagnosticManager
    ) {
        var cycleStart = visitStack.indexOf(reenteredClassName);
        var cyclePath = new ArrayList<>(visitStack.subList(cycleStart, visitStack.size()));
        cyclePath.add(reenteredClassName);
        var chainText = String.join(" -> ", cyclePath);

        for (var className : new LinkedHashSet<>(cyclePath)) {
            var classCandidate = classByName.get(className);
            if (classCandidate == null || !rejectedClassNames.add(className)) {
                continue;
            }
            diagnosticManager.error(
                    "sema.inheritance_cycle",
                    "Class '" + className + "' participates in inheritance cycle: "
                            + chainText
                            + "; its skeleton subtree will be skipped",
                    classCandidate.sourcePath(),
                    classCandidate.range()
            );
        }
    }

    private void rejectClassesDependingOnRejectedSupers(
            @NotNull Map<String, ClassSkeletonCandidate> classByName,
            @NotNull Set<String> rejectedClassNames,
            @NotNull DiagnosticManager diagnosticManager
    ) {
        boolean changed;
        do {
            changed = false;
            for (var entry : classByName.entrySet()) {
                var className = entry.getKey();
                if (rejectedClassNames.contains(className)) {
                    continue;
                }

                var classCandidate = entry.getValue();
                var superName = classCandidate.classDef().getSuperName();
                if (!rejectedClassNames.contains(superName)) {
                    continue;
                }

                diagnosticManager.error(
                        "sema.class_skeleton",
                        "Class '" + className + "' will be skipped because its super class '"
                                + superName
                                + "' was rejected by earlier inheritance diagnostics",
                        classCandidate.sourcePath(),
                        classCandidate.range()
                );
                rejectedClassNames.add(className);
                changed = true;
            }
        } while (changed);
    }

    private void reportDuplicateTopLevelClass(
            @NotNull ClassSkeletonCandidate existing,
            @NotNull ClassSkeletonCandidate duplicate,
            @NotNull DiagnosticManager diagnosticManager
    ) {
        var className = duplicate.classDef().getName();
        var message = "Duplicate class name '" + className + "' found in "
                + existing.sourcePath() + " and " + duplicate.sourcePath()
                + "; duplicate skeleton subtree will be skipped";
        diagnosticManager.error(
                "sema.class_skeleton",
                message,
                duplicate.sourcePath(),
                duplicate.range()
        );
    }

    private @NotNull String deriveClassNameFromFileName(@NotNull Path sourcePath) {
        var fileName = sourcePath.getFileName() != null ? sourcePath.getFileName().toString() : "script";
        var extensionIndex = fileName.lastIndexOf('.');
        var baseName = extensionIndex > 0 ? fileName.substring(0, extensionIndex) : fileName;
        var tokens = baseName.split("[^A-Za-z0-9]+");

        var classNameBuilder = new StringBuilder();
        for (var token : tokens) {
            if (token.isBlank()) {
                continue;
            }
            classNameBuilder.append(Character.toUpperCase(token.charAt(0)));
            if (token.length() > 1) {
                classNameBuilder.append(token.substring(1));
            }
        }

        if (classNameBuilder.isEmpty()) {
            classNameBuilder.append("Script");
        }
        if (!Character.isJavaIdentifierStart(classNameBuilder.charAt(0))) {
            classNameBuilder.insert(0, "Gd");
        }
        for (var index = 1; index < classNameBuilder.length(); index++) {
            var currentChar = classNameBuilder.charAt(index);
            if (!Character.isJavaIdentifierPart(currentChar)) {
                classNameBuilder.setCharAt(index, '_');
            }
        }
        return classNameBuilder.toString();
    }

    private enum VisitState {
        UNVISITED,
        VISITING,
        VISITED
    }

    private record ClassSkeletonCandidate(
            @NotNull Path sourcePath,
            @Nullable FrontendRange range,
            @NotNull LirClassDef classDef
    ) {
    }

    /// Stable per-unit skeleton build environment shared by private helpers.
    ///
    /// Keeping these inputs bundled together avoids reintroducing the old anti-pattern where
    /// diagnostics, registry access, source path, and side tables were all threaded through
    /// helper signatures separately.
    private record SkeletonBuildContext(
            @NotNull ClassRegistry classRegistry,
            @NotNull DiagnosticManager diagnostics,
            @NotNull Path sourcePath,
            @NotNull FrontendAnalysisData analysisData
    ) {
    }
}
