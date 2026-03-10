package dev.superice.gdcc.frontend.sema;

import dev.superice.gdparser.frontend.ast.*;
import dev.superice.gdcc.exception.FrontendSemanticException;
import dev.superice.gdcc.frontend.diagnostic.DiagnosticManager;
import dev.superice.gdcc.frontend.diagnostic.FrontendDiagnostic;
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

/// Build class skeletons from parsed AST units and inject them into ClassRegistry.
public final class FrontendClassSkeletonBuilder {
    private static final String DEFAULT_SUPER_CLASS_NAME = "Object";

    /// Builds the module skeleton while appending all newly discovered skeleton diagnostics
    /// into the shared pipeline manager.
    ///
    /// The manager is expected to already own any parse-phase diagnostics collected from the
    /// same `FrontendSourceUnit`s. This builder therefore must not re-import
    /// `FrontendSourceUnit.parseDiagnostics()` or it would duplicate diagnostics that the parse
    /// phase already published earlier in the pipeline.
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
        var classOrder = new ArrayList<ClassSkeletonCandidate>();
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
            var classCandidate = buildClassCandidate(unit, context);
            var existing = classByName.putIfAbsent(classCandidate.classDef().getName(), classCandidate);
            if (existing != null) {
                throw duplicateClassException(existing, classCandidate, diagnosticManager);
            }
            classOrder.add(classCandidate);
        }

        detectInheritanceCycles(classByName, diagnosticManager);

        for (var classCandidate : classOrder) {
            classRegistry.addGdccClass(classCandidate.classDef());
        }

        var classDefs = classOrder.stream()
                .map(ClassSkeletonCandidate::classDef)
                .toList();
        return new FrontendModuleSkeleton(moduleName, units, classDefs, diagnosticManager.snapshot());
    }

    /// Builds one class candidate using a per-unit context that bundles stable skeleton
    /// construction inputs together. This keeps helper signatures small and prevents the
    /// builder from slipping back to the old “pass diagnostics list through every helper” shape.
    private @NotNull ClassSkeletonCandidate buildClassCandidate(
            @NotNull FrontendSourceUnit unit,
            @NotNull SkeletonBuildContext context
    ) {
        var classNameStatement = firstClassNameStatement(unit.ast().statements());
        var className = resolveClassName(unit.path(), classNameStatement);
        var superClassName = resolveSuperClassName(unit.ast().statements(), classNameStatement);

        var classDef = new LirClassDef(className, superClassName);
        classDef.setSourceFile(unit.path().toString().replace('\\', '/'));

        for (var statement : unit.ast().statements()) {
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

        var classRange = classNameStatement != null ? classNameStatement.range() : unit.ast().range();
        return new ClassSkeletonCandidate(
                unit.path(),
                FrontendRange.fromAstRange(classRange),
                classDef
        );
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

    private void detectInheritanceCycles(
            @NotNull Map<String, ClassSkeletonCandidate> classByName,
            @NotNull DiagnosticManager diagnosticManager
    ) {
        var states = new HashMap<String, VisitState>();
        var visitStack = new ArrayList<String>();
        for (var className : classByName.keySet()) {
            detectInheritanceCyclesDfs(className, classByName, states, visitStack, diagnosticManager);
        }
    }

    private void detectInheritanceCyclesDfs(
            @NotNull String className,
            @NotNull Map<String, ClassSkeletonCandidate> classByName,
            @NotNull Map<String, VisitState> states,
            @NotNull List<String> visitStack,
            @NotNull DiagnosticManager diagnosticManager
    ) {
        var state = states.getOrDefault(className, VisitState.UNVISITED);
        if (state == VisitState.VISITED) {
            return;
        }
        if (state == VisitState.VISITING) {
            throw buildInheritanceCycleException(className, classByName, visitStack, diagnosticManager);
        }

        states.put(className, VisitState.VISITING);
        visitStack.add(className);

        var superName = classByName.get(className).classDef().getSuperName();
        if (classByName.containsKey(superName)) {
            detectInheritanceCyclesDfs(superName, classByName, states, visitStack, diagnosticManager);
        }

        visitStack.removeLast();
        states.put(className, VisitState.VISITED);
    }

    private @NotNull FrontendSemanticException buildInheritanceCycleException(
            @NotNull String reenteredClassName,
            @NotNull Map<String, ClassSkeletonCandidate> classByName,
            @NotNull List<String> visitStack,
            @NotNull DiagnosticManager diagnosticManager
    ) {
        var cycleStart = visitStack.indexOf(reenteredClassName);
        var cyclePath = new ArrayList<>(visitStack.subList(cycleStart, visitStack.size()));
        cyclePath.add(reenteredClassName);
        var chainText = String.join(" -> ", cyclePath);

        var cycleDiagnostics = new ArrayList<FrontendDiagnostic>();
        for (var className : new LinkedHashSet<>(cyclePath)) {
            var classCandidate = classByName.get(className);
            if (classCandidate == null) {
                continue;
            }
            cycleDiagnostics.add(FrontendDiagnostic.error(
                    "sema.inheritance_cycle",
                    "Class '" + className + "' participates in inheritance cycle: " + chainText,
                    classCandidate.sourcePath(),
                    classCandidate.range()
            ));
        }
        diagnosticManager.reportAll(cycleDiagnostics);
        return new FrontendSemanticException("Detected inheritance cycle: " + chainText, diagnosticManager.snapshot());
    }

    private @NotNull FrontendSemanticException duplicateClassException(
            @NotNull ClassSkeletonCandidate existing,
            @NotNull ClassSkeletonCandidate duplicate,
            @NotNull DiagnosticManager diagnosticManager
    ) {
        var className = duplicate.classDef().getName();
        var message = "Duplicate class name '" + className + "' found in "
                + existing.sourcePath() + " and " + duplicate.sourcePath();
        diagnosticManager.error(
                "sema.class_skeleton",
                message,
                duplicate.sourcePath(),
                duplicate.range()
        );
        return new FrontendSemanticException(message, diagnosticManager.snapshot());
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
