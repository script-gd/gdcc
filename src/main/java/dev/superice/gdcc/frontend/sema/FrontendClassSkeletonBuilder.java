package dev.superice.gdcc.frontend.sema;

import dev.superice.gdparser.frontend.ast.*;
import dev.superice.gdcc.exception.FrontendSemanticException;
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

    public @NotNull FrontendModuleSkeleton build(
            @NotNull String moduleName,
            @NotNull List<FrontendSourceUnit> units,
            @NotNull ClassRegistry classRegistry
    ) {
        Objects.requireNonNull(moduleName, "moduleName must not be null");
        Objects.requireNonNull(units, "units must not be null");
        Objects.requireNonNull(classRegistry, "classRegistry must not be null");

        var diagnostics = new ArrayList<FrontendDiagnostic>();
        var classByName = new LinkedHashMap<String, ClassSkeletonCandidate>();
        var classOrder = new ArrayList<ClassSkeletonCandidate>();

        for (var unit : units) {
            diagnostics.addAll(unit.parseDiagnostics());
            var classCandidate = buildClassCandidate(unit, classRegistry, diagnostics);
            var existing = classByName.putIfAbsent(classCandidate.classDef().getName(), classCandidate);
            if (existing != null) {
                throw duplicateClassException(existing, classCandidate, diagnostics);
            }
            classOrder.add(classCandidate);
        }

        detectInheritanceCycles(classByName, diagnostics);

        for (var classCandidate : classOrder) {
            classRegistry.addGdccClass(classCandidate.classDef());
        }

        var classDefs = classOrder.stream()
                .map(ClassSkeletonCandidate::classDef)
                .toList();
        return new FrontendModuleSkeleton(moduleName, units, classDefs, diagnostics);
    }

    private @NotNull ClassSkeletonCandidate buildClassCandidate(
            @NotNull FrontendSourceUnit unit,
            @NotNull ClassRegistry classRegistry,
            @NotNull List<FrontendDiagnostic> diagnostics
    ) {
        var classNameStatement = firstClassNameStatement(unit.ast().statements());
        var className = resolveClassName(unit.path(), classNameStatement);
        var superClassName = resolveSuperClassName(unit.ast().statements(), classNameStatement);

        var classDef = new LirClassDef(className, superClassName);
        classDef.setSourceFile(unit.path().toString().replace('\\', '/'));

        for (var statement : unit.ast().statements()) {
            switch (statement) {
                case SignalStatement signalStatement -> classDef.addSignal(
                        toLirSignal(signalStatement, classRegistry, unit.path(), diagnostics)
                );
                case VariableDeclaration variableDeclaration -> {
                    if (variableDeclaration.kind() == DeclarationKind.VAR) {
                        classDef.addProperty(toLirProperty(variableDeclaration, classRegistry, unit.path(), diagnostics));
                    }
                }
                case FunctionDeclaration functionDeclaration -> classDef.addFunction(
                        toLirFunction(functionDeclaration, classRegistry, unit.path(), diagnostics)
                );
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
            @NotNull ClassRegistry classRegistry,
            @NotNull Path sourcePath,
            @NotNull List<FrontendDiagnostic> diagnostics
    ) {
        var signalDef = new LirSignalDef(signalStatement.name().trim());
        for (var parameter : signalStatement.parameters()) {
            var parameterType = resolveTypeOrVariant(
                    parameter.type(),
                    classRegistry,
                    sourcePath,
                    diagnostics
            );
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
            @NotNull ClassRegistry classRegistry,
            @NotNull Path sourcePath,
            @NotNull List<FrontendDiagnostic> diagnostics
    ) {
        var propertyType = resolveTypeOrVariant(
                variableDeclaration.type(),
                classRegistry,
                sourcePath,
                diagnostics
        );
        var propertyDef = new LirPropertyDef(variableDeclaration.name().trim(), propertyType);
        propertyDef.setStatic(variableDeclaration.isStatic());
        switch (variableDeclaration.sourceNodeType()) {
            case "export_variable_statement" -> propertyDef.getAnnotations().put("export", "true");
            case "onready_variable_statement" -> propertyDef.getAnnotations().put("onready", "true");
            default -> {
            }
        }
        return propertyDef;
    }

    private @NotNull LirFunctionDef toLirFunction(
            @NotNull FunctionDeclaration functionDeclaration,
            @NotNull ClassRegistry classRegistry,
            @NotNull Path sourcePath,
            @NotNull List<FrontendDiagnostic> diagnostics
    ) {
        var functionDef = new LirFunctionDef(functionDeclaration.name().trim());
        functionDef.setReturnType(resolveTypeOrVariant(
                functionDeclaration.returnType(),
                classRegistry,
                sourcePath,
                diagnostics
        ));

        for (var parameter : functionDeclaration.parameters()) {
            var parameterType = resolveTypeOrVariant(
                    parameter.type(),
                    classRegistry,
                    sourcePath,
                    diagnostics
            );
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
            @NotNull ClassRegistry classRegistry,
            @NotNull Path sourcePath,
            @NotNull List<FrontendDiagnostic> diagnostics
    ) {
        if (typeRef == null || typeRef.sourceText().isBlank()) {
            return GdVariantType.VARIANT;
        }

        try {
            var resolvedType = classRegistry.findType(typeRef.sourceText().trim());
            if (resolvedType != null) {
                return resolvedType;
            }
        } catch (RuntimeException ignored) {
            // Preserve tolerant frontend behavior: downgrade to Variant with warning.
        }

        diagnostics.add(FrontendDiagnostic.warning(
                "sema.type_resolution",
                "Unknown type '" + typeRef.sourceText() + "', fallback to Variant",
                sourcePath,
                FrontendRange.fromAstRange(typeRef.range())
        ));
        return GdVariantType.VARIANT;
    }

    private void detectInheritanceCycles(
            @NotNull Map<String, ClassSkeletonCandidate> classByName,
            @NotNull List<FrontendDiagnostic> diagnostics
    ) {
        var states = new HashMap<String, VisitState>();
        var visitStack = new ArrayList<String>();
        for (var className : classByName.keySet()) {
            detectInheritanceCyclesDfs(className, classByName, states, visitStack, diagnostics);
        }
    }

    private void detectInheritanceCyclesDfs(
            @NotNull String className,
            @NotNull Map<String, ClassSkeletonCandidate> classByName,
            @NotNull Map<String, VisitState> states,
            @NotNull List<String> visitStack,
            @NotNull List<FrontendDiagnostic> diagnostics
    ) {
        var state = states.getOrDefault(className, VisitState.UNVISITED);
        if (state == VisitState.VISITED) {
            return;
        }
        if (state == VisitState.VISITING) {
            throw buildInheritanceCycleException(className, classByName, visitStack, diagnostics);
        }

        states.put(className, VisitState.VISITING);
        visitStack.add(className);

        var superName = classByName.get(className).classDef().getSuperName();
        if (classByName.containsKey(superName)) {
            detectInheritanceCyclesDfs(superName, classByName, states, visitStack, diagnostics);
        }

        visitStack.removeLast();
        states.put(className, VisitState.VISITED);
    }

    private @NotNull FrontendSemanticException buildInheritanceCycleException(
            @NotNull String reenteredClassName,
            @NotNull Map<String, ClassSkeletonCandidate> classByName,
            @NotNull List<String> visitStack,
            @NotNull List<FrontendDiagnostic> diagnostics
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
        diagnostics.addAll(cycleDiagnostics);
        return new FrontendSemanticException("Detected inheritance cycle: " + chainText, diagnostics);
    }

    private @NotNull FrontendSemanticException duplicateClassException(
            @NotNull ClassSkeletonCandidate existing,
            @NotNull ClassSkeletonCandidate duplicate,
            @NotNull List<FrontendDiagnostic> diagnostics
    ) {
        var className = duplicate.classDef().getName();
        var message = "Duplicate class name '" + className + "' found in "
                + existing.sourcePath() + " and " + duplicate.sourcePath();
        diagnostics.add(FrontendDiagnostic.error(
                "sema.class_skeleton",
                message,
                duplicate.sourcePath(),
                duplicate.range()
        ));
        return new FrontendSemanticException(message, diagnostics);
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
}
