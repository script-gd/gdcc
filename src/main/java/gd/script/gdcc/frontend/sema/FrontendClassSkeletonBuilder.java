package gd.script.gdcc.frontend.sema;

import gd.script.gdcc.frontend.FrontendClassNameContract;
import gd.script.gdcc.frontend.sema.analyzer.FrontendAnnotationCollector;
import gd.script.gdcc.frontend.sema.analyzer.support.FrontendEnumConstantEvaluator;
import gd.script.gdcc.frontend.sema.analyzer.support.FrontendExportAnnotationSupport;
import gd.script.gdcc.frontend.scope.ClassScope;
import dev.superice.gdparser.frontend.ast.*;
import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.diagnostic.FrontendRange;
import gd.script.gdcc.frontend.parse.FrontendModule;
import gd.script.gdcc.frontend.parse.FrontendSourceUnit;
import gd.script.gdcc.lir.LirClassDef;
import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.lir.LirParameterDef;
import gd.script.gdcc.lir.LirPropertyDef;
import gd.script.gdcc.lir.LirSignalDef;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.scope.GdScriptClassConstant;
import gd.script.gdcc.scope.GdScriptEnumConstant;
import gd.script.gdcc.scope.GdScriptEnumGroup;
import gd.script.gdcc.scope.ResolveRestriction;
import gd.script.gdcc.scope.Scope;
import gd.script.gdcc.scope.ScopeTypeMeta;
import gd.script.gdcc.scope.ScopeTypeMetaKind;
import gd.script.gdcc.type.GdDictionaryType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdVariantType;
import gd.script.gdcc.util.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.*;

/// Build class skeletons from parsed AST units via a module-level header discovery pass, then
/// publish accepted class shells into `ClassRegistry` before member filling starts.
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
            @NotNull FrontendModule module,
            @NotNull ClassRegistry classRegistry,
            @NotNull DiagnosticManager diagnosticManager,
            @NotNull FrontendAnalysisData analysisData
    ) {
        Objects.requireNonNull(module, "module must not be null");
        Objects.requireNonNull(classRegistry, "classRegistry must not be null");
        Objects.requireNonNull(diagnosticManager, "diagnosticManager must not be null");
        Objects.requireNonNull(analysisData, "analysisData must not be null");

        var units = module.units();
        var annotationCollector = new FrontendAnnotationCollector();

        for (var unit : units) {
            // Reuse the shared analysis data side table so the semantic pipeline sees one stable
            // annotation ownership map instead of local copies.
            analysisData.annotationsByAst().putAll(annotationCollector.collect(unit));
        }
        var headerDiscovery = discoverModuleClassHeaders(module, classRegistry, diagnosticManager);
        markSkippedSubtreeRoots(headerDiscovery.rejectedSubtreeRoots(), analysisData);
        var sourceClassRelations = new ArrayList<FrontendSourceClassRelation>();

        for (var sourceUnitGraph : headerDiscovery.sourceUnitGraphs()) {
            if (sourceUnitGraph.topLevelHeader() == null) {
                continue;
            }
            var context = new SkeletonBuildContext(
                    classRegistry,
                    diagnosticManager,
                    sourceUnitGraph.unit().path(),
                    analysisData,
                    module.topLevelCanonicalNameMap()
            );
            var sourceClassRelation = buildSourceClassRelationShell(sourceUnitGraph, context);
            applyScriptToolAnnotation(sourceClassRelation, context);
            sourceClassRelations.add(sourceClassRelation);
        }
        publishClassShells(sourceClassRelations, classRegistry);
        // Module-wide canonical index for the enum pre-pass ancestor blocker: the blocker scans
        // ancestor class bodies structurally, so it needs every accepted GDCC relation regardless
        // of which source unit is currently being filled.
        var relationsByCanonicalName = new HashMap<String, FrontendOwnedClassRelation>();
        for (var sourceClassRelation : sourceClassRelations) {
            relationsByCanonicalName.put(sourceClassRelation.canonicalName(), sourceClassRelation);
            for (var innerClassRelation : sourceClassRelation.innerClassRelations()) {
                relationsByCanonicalName.put(innerClassRelation.canonicalName(), innerClassRelation);
            }
        }
        // Static-property conflict checks are deferred until every class shell has been filled:
        // member filling follows source order, not inheritance order, so an ancestor's properties
        // are only guaranteed to exist in the registry after this loop completes.
        var pendingStaticPropertyChecks = new ArrayList<PendingStaticPropertyConflictCheck>();
        for (var sourceClassRelation : sourceClassRelations) {
            var context = new SkeletonBuildContext(
                    classRegistry,
                    diagnosticManager,
                    sourceClassRelation.unit().path(),
                    analysisData,
                    module.topLevelCanonicalNameMap()
            );
            fillSourceClassRelationMembers(
                    sourceClassRelation,
                    context,
                    pendingStaticPropertyChecks,
                    relationsByCanonicalName
            );
        }
        validateStaticPropertyHierarchyConflicts(pendingStaticPropertyChecks, classRegistry, diagnosticManager, analysisData);

        return new FrontendModuleSkeleton(
                module.moduleName(),
                sourceClassRelations,
                module.topLevelCanonicalNameMap(),
                diagnosticManager.snapshot()
        );
    }

    /// Builds one accepted source-owned skeleton relation from the validated header graph:
    /// - exactly one accepted top-level script class
    /// - zero or more accepted nested `ClassDeclaration -> LirClassDef` ownership pairs
    /// - each accepted class carries the frontend-only superclass source/canonical pair that
    ///   downstream frontend consumers must read instead of reconstructing from `ClassDef`
    ///
    /// Rejected roots and their descendants never reach relation construction. The relation
    /// therefore mirrors the accepted header graph instead of rediscovering validity while filling
    /// member skeletons.
    private @NotNull FrontendSourceClassRelation buildSourceClassRelationShell(
            @NotNull SourceUnitClassHeaderGraph sourceUnitGraph,
            @NotNull SkeletonBuildContext context
    ) {
        var topLevelHeader = Objects.requireNonNull(
                sourceUnitGraph.topLevelHeader(),
                "Accepted source unit graph must have a top-level header"
        );
        var topLevelClassDef = createClassShell(
                topLevelHeader.canonicalName(),
                topLevelHeader.superClassRef().canonicalName(),
                context
        );
        var innerClassRelations = collectAcceptedInnerClassRelations(
                topLevelHeader.immediateInnerHeaders(),
                context
        );
        return new FrontendSourceClassRelation(
                sourceUnitGraph.unit(),
                topLevelHeader.sourceName(),
                topLevelHeader.canonicalName(),
                topLevelHeader.superClassRef(),
                topLevelClassDef,
                innerClassRelations
        );
    }

    /// Publishes every accepted shell before member signatures are filled so the current module can
    /// already resolve self and same-module gdcc types through the registry during skeleton filling.
    private void publishClassShells(
            @NotNull List<FrontendSourceClassRelation> sourceClassRelations,
            @NotNull ClassRegistry classRegistry
    ) {
        for (var sourceClassRelation : sourceClassRelations) {
            classRegistry.addGdccClass(
                    sourceClassRelation.topLevelClassDef(),
                    sourceClassRelation.sourceName()
            );
            for (var innerClassRelation : sourceClassRelation.innerClassRelations()) {
                classRegistry.addGdccClass(
                        innerClassRelation.classDef(),
                        innerClassRelation.sourceName()
                );
            }
        }
    }

    private void fillSourceClassRelationMembers(
            @NotNull FrontendSourceClassRelation sourceClassRelation,
            @NotNull SkeletonBuildContext context,
            @NotNull List<PendingStaticPropertyConflictCheck> pendingStaticPropertyChecks,
            @NotNull Map<String, FrontendOwnedClassRelation> relationsByCanonicalName
    ) {
        var declaredTypeScopes = buildDeclaredTypeScopes(sourceClassRelation, context.classRegistry());
        fillClassMembers(
                sourceClassRelation.topLevelClassDef(),
                sourceClassRelation.unit().ast().statements(),
                requireDeclaredTypeScope(declaredTypeScopes, sourceClassRelation.astOwner()),
                context,
                pendingStaticPropertyChecks,
                relationsByCanonicalName
        );
        for (var innerClassRelation : sourceClassRelation.innerClassRelations()) {
            fillClassMembers(
                    innerClassRelation.classDef(),
                    innerClassRelation.declaration().body().statements(),
                    requireDeclaredTypeScope(declaredTypeScopes, innerClassRelation.astOwner()),
                    context,
                    pendingStaticPropertyChecks,
                    relationsByCanonicalName
            );
        }
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
        var className = StringUtil.trimToNull(classNameStatement != null ? classNameStatement.name() : null);
        if (className != null) {
            return className;
        }
        return FrontendClassNameContract.deriveDefaultTopLevelSourceName(sourcePath);
    }

    /// Top-level scripts may spell inheritance either on `class_name Name extends Base` or on a
    /// standalone `extends Base` statement. Header discovery preserves only the trimmed raw text
    /// here; this path is still separate from shared declared-type resolution.
    private @Nullable String resolveTopLevelRawExtendsText(
            @NotNull List<Statement> statements,
            @Nullable ClassNameStatement classNameStatement
    ) {
        var classNameExtends = StringUtil.trimToNull(
                classNameStatement != null ? classNameStatement.extendsTarget() : null
        );
        if (classNameExtends != null) {
            return classNameExtends;
        }
        for (var statement : statements) {
            if (statement instanceof ExtendsStatement extendsStatement) {
                var extendsTarget = StringUtil.trimToNull(extendsStatement.target());
                if (extendsTarget != null) {
                    return extendsTarget;
                }
            }
        }
        return null;
    }

    private @NotNull Range topLevelClassRange(@NotNull FrontendSourceUnit unit) {
        var classNameStatement = firstClassNameStatement(unit.ast().statements());
        return classNameStatement != null ? classNameStatement.range() : unit.ast().range();
    }

    /// Creates the minimal class shell that is published before member filling begins.
    ///
    /// Member signatures are filled later against the already-published registry state. This keeps
    /// all accepted canonical class identities queryable before any property/function/signal type is
    /// resolved.
    private @NotNull LirClassDef createClassShell(
            @NotNull String className,
            @NotNull String superClassName,
            @NotNull SkeletonBuildContext context
    ) {
        var classDef = new LirClassDef(className, superClassName);
        classDef.setSourceFile(context.sourcePath().toString().replace('\\', '/'));
        return classDef;
    }

    /// Applies the script-level `@tool` contract once every class shell of one source file exists.
    ///
    /// Only a zero-argument `tool` annotation attached to the top-level `SourceFile` is consumed.
    /// Godot treats `@tool` as script-level state and `_prepare_compilation` assigns it to every
    /// class compiled from the script, so the flag propagates to the top-level shell and all inner
    /// shells; the top-level class additionally records the `tool` class annotation so LIR XML
    /// roundtrips keep the source-level fact. `@tool` attached anywhere else (inner class, member,
    /// trailing anchor) or carrying arguments is retention-only here — placement/arity diagnostics
    /// belong to `FrontendAnnotationUsageAnalyzer` as the single owner.
    private void applyScriptToolAnnotation(
            @NotNull FrontendSourceClassRelation sourceClassRelation,
            @NotNull SkeletonBuildContext context
    ) {
        var sourceFile = sourceClassRelation.unit().ast();
        var hasScriptTool = context.analysisData().annotationsByAst()
                .getOrDefault(sourceFile, List.of())
                .stream()
                .anyMatch(annotation -> annotation.name().equals("tool") && annotation.arguments().isEmpty());
        if (!hasScriptTool) {
            return;
        }
        sourceClassRelation.topLevelClassDef().setTool(true);
        sourceClassRelation.topLevelClassDef().setAnnotation("tool", "");
        for (var innerClassRelation : sourceClassRelation.innerClassRelations()) {
            innerClassRelation.classDef().setTool(true);
        }
    }

    /// Fills member skeletons into an already-published class shell.
    ///
    /// Inner classes are still excluded here because they already own their own shell and relation
    /// entry. The parent class only receives signals/properties/functions/constructors declared
    /// directly in its own statement list. Constructors are lowered into the special `_init`
    /// function slot on `ClassDef` so downstream code can keep using one shared member surface.
    /// Enum declarations are fully handled by the enum pre-pass running before the main loop.
    private void fillClassMembers(
            @NotNull LirClassDef classDef,
            @NotNull List<Statement> statements,
            @NotNull ClassScope declaredTypeScope,
            @NotNull SkeletonBuildContext context,
            @NotNull List<PendingStaticPropertyConflictCheck> pendingStaticPropertyChecks,
            @NotNull Map<String, FrontendOwnedClassRelation> relationsByCanonicalName
    ) {
        runEnumConstantPrePass(classDef, statements, declaredTypeScope, context, relationsByCanonicalName);
        for (var statement : statements) {
            switch (statement) {
                case SignalStatement signalStatement -> {
                    if (rejectReservedSyntheticPropertyHelperMember(
                            signalStatement,
                            "Signal",
                            signalStatement.name(),
                            context
                    ) || rejectEngineNativeSignalShadow(
                            classDef,
                            signalStatement,
                            context
                    )) {
                        continue;
                    }
                    classDef.addSignal(toLirSignal(signalStatement, declaredTypeScope, context));
                }
                case VariableDeclaration variableDeclaration -> {
                    if (variableDeclaration.kind() == DeclarationKind.VAR) {
                        if (rejectReservedSyntheticPropertyHelperMember(
                                variableDeclaration,
                                "Property",
                                variableDeclaration.name(),
                                context
                        )) {
                            continue;
                        }
                        var propertyDef = toLirProperty(variableDeclaration, declaredTypeScope, context);
                        classDef.addProperty(propertyDef);
                        if (variableDeclaration.isStatic()) {
                            pendingStaticPropertyChecks.add(new PendingStaticPropertyConflictCheck(
                                    context.sourcePath(),
                                    classDef,
                                    propertyDef,
                                    variableDeclaration
                            ));
                        }
                    }
                }
                case FunctionDeclaration functionDeclaration -> {
                    if (rejectReservedSyntheticPropertyHelperMember(
                            functionDeclaration,
                            "Function",
                            functionDeclaration.name(),
                            context
                    )) {
                        continue;
                    }
                    addFunctionMember(
                            classDef,
                            functionDeclaration.name().trim().equals("_init")
                                    ? toLirInitFunction(
                                    functionDeclaration.parameters(),
                                    declaredTypeScope,
                                    context
                            )
                                    : toLirFunction(functionDeclaration, declaredTypeScope, context),
                            functionDeclaration,
                            context
                    );
                }
                case ConstructorDeclaration constructorDeclaration -> addFunctionMember(
                        classDef,
                        toLirInitFunction(
                                constructorDeclaration.parameters(),
                                declaredTypeScope,
                                context
                        ),
                        constructorDeclaration,
                        context
                );
                case EnumDeclaration _ -> {
                    // Handled by the enum pre-pass before this loop; nothing left to do here.
                }
                default -> {
                }
            }
        }
    }

    /// Validates and evaluates every `EnumDeclaration` of one class body before regular member
    /// filling starts.
    ///
    /// Running ahead of the main loop gives two guarantees:
    /// - constant facts land on the `ClassDef` constant table before any member type resolution
    ///   runs, so declaration order between enums and properties does not matter;
    /// - named enum groups publish their `GDCC_ENUM` type-meta onto the declared-type scaffold
    ///   before `var x: State` style annotations are resolved.
    ///
    /// Enums succeed or fail independently: a rejected enum produces exactly one
    /// `sema.class_skeleton` diagnostic, skips its own subtree, and leaves sibling enums/members
    /// untouched. Nothing is published for a failed enum — no constant rows and no type-meta — so
    /// downstream phases never observe half-evaluated enum facts.
    private void runEnumConstantPrePass(
            @NotNull LirClassDef classDef,
            @NotNull List<Statement> statements,
            @NotNull ClassScope declaredTypeScope,
            @NotNull SkeletonBuildContext context,
            @NotNull Map<String, FrontendOwnedClassRelation> relationsByCanonicalName
    ) {
        var enumDeclarations = new ArrayList<EnumDeclaration>();
        for (var statement : statements) {
            if (statement instanceof EnumDeclaration enumDeclaration) {
                enumDeclarations.add(enumDeclaration);
            }
        }
        if (enumDeclarations.isEmpty()) {
            return;
        }
        // The main loop has not filled the class shell yet, so the conflict table is collected
        // from the AST directly: property/signal/function names plus direct inner-class source
        // names. Named enum members are intentionally absent — they never enter the class-level
        // namespace, so `enum State {IDLE}` may coexist with a same-named class member `IDLE`.
        var reservedNames = getReserveNames(statements);
        var ancestorEnumNames = collectAncestorEnumNames(classDef.getSuperName(), relationsByCanonicalName);
        for (var enumDeclaration : enumDeclarations) {
            processEnumDeclaration(
                    classDef,
                    enumDeclaration,
                    reservedNames,
                    declaredTypeScope,
                    context,
                    ancestorEnumNames
            );
        }
    }

    private static @NotNull HashSet<String> getReserveNames(@NotNull List<Statement> statements) {
        var reservedNames = new HashSet<String>();
        for (var statement : statements) {
            switch (statement) {
                case SignalStatement signalStatement -> reservedNames.add(signalStatement.name().trim());
                case VariableDeclaration variableDeclaration -> {
                    if (variableDeclaration.kind() == DeclarationKind.VAR) {
                        reservedNames.add(variableDeclaration.name().trim());
                    }
                }
                case FunctionDeclaration functionDeclaration ->
                        reservedNames.add(functionDeclaration.name().trim());
                // Constructors share the `_init` slot with same-named functions.
                case ConstructorDeclaration _ -> reservedNames.add("_init");
                case ClassDeclaration classDeclaration -> reservedNames.add(classDeclaration.name().trim());
                default -> {
                }
            }
        }
        return reservedNames;
    }

    /// Validates and evaluates one enum declaration, publishing its facts only when every member
    /// resolves. Anonymous enums publish one constant row per member; named enums publish one
    /// Dictionary-typed group row plus the `GDCC_ENUM` type-meta on the declared-type scaffold.
    /// Successfully published names join the class-level conflict table for later enums.
    private void processEnumDeclaration(
            @NotNull LirClassDef classDef,
            @NotNull EnumDeclaration enumDeclaration,
            @NotNull Set<String> reservedNames,
            @NotNull ClassScope declaredTypeScope,
            @NotNull SkeletonBuildContext context,
            @NotNull Set<String> ancestorEnumNames
    ) {
        var groupName = StringUtil.trimToNull(enumDeclaration.name());
        var members = enumDeclaration.members();
        var enumLabel = groupName != null ? "Enum '" + groupName + "'" : "Anonymous enum";
        // The parser may have already rejected something inside this enum (its recovery drops a
        // broken initializer, leaving a `value == null` member that looks like an auto-increment
        // candidate). Publishing constants from such a subtree would fabricate values, so the enum
        // is skipped without publication — and without a second diagnostic on the same root cause
        // (single-owner recovery rule: parse owns the initializer error).
        if (hasParserDiagnosticInside(enumDeclaration, context)) {
            markSkippedSubtreeRoots(List.of(enumDeclaration), context.analysisData());
            return;
        }
        // gdparser error recovery maps an empty enum body to one phantom member with an empty
        // name while keeping parse diagnostics empty, so both shapes collapse into the same
        // "at least one member" rule here.
        if (members.isEmpty() || members.stream().anyMatch(member -> member.name().isBlank())) {
            rejectEnumDeclaration(
                    enumDeclaration,
                    enumDeclaration,
                    enumLabel + " on class '" + classDef.getName() + "' must declare at least one member",
                    context
            );
            return;
        }
        // Duplicate members inside a single enum are rejected regardless of naming form.
        var seenMemberNames = new HashSet<String>();
        for (var member : members) {
            var memberName = member.name().trim();
            if (!seenMemberNames.add(memberName)) {
                rejectEnumDeclaration(
                        enumDeclaration,
                        enumDeclaration,
                        enumLabel + " on class '" + classDef.getName()
                                + "' declares duplicate member '" + memberName + "'",
                        context
                );
                return;
            }
        }
        // Class-level conflict check: anonymous enums occupy one name per member, named enums
        // occupy only the group name (their members stay inside the Dictionary value).
        if (groupName != null) {
            if (reservedNames.contains(groupName)) {
                rejectEnumDeclaration(
                        enumDeclaration,
                        enumDeclaration,
                        enumLabel + " on class '" + classDef.getName()
                                + "' conflicts with an existing class member named '" + groupName + "'",
                        context
                );
                return;
            }
        } else {
            for (var member : members) {
                var memberName = member.name().trim();
                if (reservedNames.contains(memberName)) {
                    rejectEnumDeclaration(
                            enumDeclaration,
                            enumDeclaration,
                            enumLabel + " on class '" + classDef.getName() + "' declares member '"
                                    + memberName + "' which conflicts with an existing class member",
                            context
                    );
                    return;
                }
            }
        }
        // Evaluate member values in source order without publishing anything: a failed enum must
        // leave neither constant rows nor type-meta behind.
        var evaluatedMembers = new ArrayList<GdScriptEnumConstant>();
        var earlierMemberValues = new LinkedHashMap<String, Long>();
        long nextAutoValue = 0;
        for (var member : members) {
            var memberName = member.name().trim();
            long value;
            if (member.value() != null) {
                var evaluation = FrontendEnumConstantEvaluator.evaluate(
                        member.value(),
                        earlierMemberValues,
                        classDef.getScriptConstants(),
                        ancestorEnumNames,
                        context.classRegistry()
                );
                switch (evaluation) {
                    case FrontendEnumConstantEvaluator.Evaluation.Rejected rejected -> {
                        rejectEnumDeclaration(
                                enumDeclaration,
                                member,
                                enumLabel + " on class '" + classDef.getName() + "' member '" + memberName
                                        + "' has no supported int constant value: " + rejected.reason(),
                                context
                        );
                        return;
                    }
                    case FrontendEnumConstantEvaluator.Evaluation.Resolved resolved -> value = resolved.value();
                }
            } else {
                // Auto-increment follows Godot: first member defaults to 0, later members continue
                // from the previous member's value.
                value = nextAutoValue;
            }
            earlierMemberValues.put(memberName, value);
            evaluatedMembers.add(new GdScriptEnumConstant(memberName, value, groupName, classDef.getName()));
            nextAutoValue = value + 1;
        }
        if (groupName != null) {
            var enumGroup = new GdScriptEnumGroup(groupName, List.copyOf(evaluatedMembers), classDef.getName());
            classDef.addScriptConstant(new GdScriptClassConstant(
                    groupName,
                    new GdDictionaryType(GdVariantType.VARIANT, GdVariantType.VARIANT),
                    enumGroup
            ));
            declaredTypeScope.defineTypeMeta(new ScopeTypeMeta(
                    classDef.getName() + "." + groupName,
                    groupName,
                    GdIntType.INT,
                    ScopeTypeMetaKind.GDCC_ENUM,
                    enumGroup,
                    true
            ));
            reservedNames.add(groupName);
        } else {
            for (var member : evaluatedMembers) {
                classDef.addScriptConstant(new GdScriptClassConstant(member.memberName(), GdIntType.INT, member));
                reservedNames.add(member.memberName());
            }
        }
    }

    /// Emits the single `sema.class_skeleton` diagnostic for one rejected enum and skips its
    /// whole subtree. `diagnosticAnchor` stays as precise as the failure allows (member node for
    /// value evaluation errors, the enum declaration itself for structural errors).
    private void rejectEnumDeclaration(
            @NotNull EnumDeclaration enumDeclaration,
            @NotNull Node diagnosticAnchor,
            @NotNull String message,
            @NotNull SkeletonBuildContext context
    ) {
        context.diagnostics().error(
                "sema.class_skeleton",
                message,
                context.sourcePath(),
                FrontendRange.fromAstRange(diagnosticAnchor.range())
        );
        markSkippedSubtreeRoots(List.of(enumDeclaration), context.analysisData());
    }

    /// Checks whether the parser already reported an error inside this enum's source range. Parse
    /// diagnostics live in the same shared manager (parse runs before skeleton), and enum bodies
    /// contain only members, so any `parse.*` diagnostic whose range is contained in the enum
    /// declaration range marks the whole enum as parse-broken.
    private boolean hasParserDiagnosticInside(
            @NotNull EnumDeclaration enumDeclaration,
            @NotNull SkeletonBuildContext context
    ) {
        var enumRange = FrontendRange.fromAstRange(enumDeclaration.range());
        var sourcePath = context.sourcePath().toString().replace('\\', '/');
        for (var diagnostic : context.diagnostics().snapshot().asList()) {
            if (!diagnostic.category().startsWith("parse.")) {
                continue;
            }
            var range = diagnostic.range();
            if (range == null || diagnostic.sourcePath() == null
                    || !diagnostic.sourcePath().equals(sourcePath)) {
                continue;
            }
            if (range.startByte() >= enumRange.startByte() && range.endByte() <= enumRange.endByte()) {
                return true;
            }
        }
        return false;
    }

    /// Collects the enum names declared by the GDCC ancestor chain of one class: named enum group
    /// names plus anonymous enum member names.
    ///
    /// The scan is purely structural, so it never depends on ancestor fill order. The walk stops
    /// at the first non-module (engine/native) ancestor: those classes expose no bare-visible
    /// script enums, and no GDCC class can sit above them on the chain.
    private @NotNull Set<String> collectAncestorEnumNames(
            @NotNull String superClassCanonicalName,
            @NotNull Map<String, FrontendOwnedClassRelation> relationsByCanonicalName
    ) {
        var names = new HashSet<String>();
        var visited = new HashSet<String>();
        var currentSuperName = superClassCanonicalName;
        while (!currentSuperName.isBlank() && visited.add(currentSuperName)) {
            var relation = relationsByCanonicalName.get(currentSuperName);
            if (relation == null) {
                break;
            }
            for (var statement : classBodyStatementsOf(relation)) {
                if (statement instanceof EnumDeclaration enumDeclaration) {
                    if (enumDeclaration.name() != null) {
                        names.add(enumDeclaration.name().trim());
                    } else {
                        for (var member : enumDeclaration.members()) {
                            names.add(member.name().trim());
                        }
                    }
                }
            }
            currentSuperName = relation.superClassRef().canonicalName();
        }
        return names;
    }

    private @NotNull List<Statement> classBodyStatementsOf(@NotNull FrontendOwnedClassRelation relation) {
        return switch (relation.astOwner()) {
            case SourceFile sourceFile -> sourceFile.statements();
            case ClassDeclaration classDeclaration -> classDeclaration.body().statements();
            default -> List.of();
        };
    }

    /// Builds accepted inner class relations in pre-order so downstream consumers keep seeing the
    /// stable source traversal order established by discovery.
    private @NotNull List<FrontendInnerClassRelation> collectAcceptedInnerClassRelations(
            @NotNull List<AcceptedClassHeader> acceptedInnerHeaders,
            @NotNull SkeletonBuildContext context
    ) {
        var innerClassRelations = new ArrayList<FrontendInnerClassRelation>();
        for (var acceptedInnerHeader : acceptedInnerHeaders) {
            var classDeclaration = requireInnerClassDeclaration(acceptedInnerHeader);
            var innerClassDef = createClassShell(
                    acceptedInnerHeader.canonicalName(),
                    acceptedInnerHeader.superClassRef().canonicalName(),
                    context
            );
            innerClassRelations.add(new FrontendInnerClassRelation(
                    acceptedInnerHeader.lexicalOwner(),
                    classDeclaration,
                    acceptedInnerHeader.sourceName(),
                    acceptedInnerHeader.canonicalName(),
                    acceptedInnerHeader.superClassRef(),
                    innerClassDef
            ));
            innerClassRelations.addAll(collectAcceptedInnerClassRelations(
                    acceptedInnerHeader.immediateInnerHeaders(),
                    context
            ));
        }
        return List.copyOf(innerClassRelations);
    }

    private @NotNull ClassDeclaration requireInnerClassDeclaration(
            @NotNull AcceptedClassHeader acceptedInnerHeader
    ) {
        if (acceptedInnerHeader.astOwner() instanceof ClassDeclaration classDeclaration) {
            return classDeclaration;
        }
        throw new IllegalStateException("Accepted inner class header must point to ClassDeclaration");
    }

    private @NotNull LirSignalDef toLirSignal(
            @NotNull SignalStatement signalStatement,
            @NotNull Scope declaredTypeScope,
            @NotNull SkeletonBuildContext context
    ) {
        var signalDef = new LirSignalDef(signalStatement.name().trim());
        for (var parameter : signalStatement.parameters()) {
            var parameterType = FrontendDeclaredTypeSupport.resolveTypeOrVariant(
                    parameter.type(),
                    declaredTypeScope,
                    context.moduleTopLevelCanonicalNameMap(),
                    context.sourcePath(),
                    context.diagnostics()
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
            @NotNull Scope declaredTypeScope,
            @NotNull SkeletonBuildContext context
    ) {
        var propertyType = FrontendDeclaredTypeSupport.resolveTypeOrVariant(
                variableDeclaration.type(),
                declaredTypeScope,
                context.moduleTopLevelCanonicalNameMap(),
                context.sourcePath(),
                context.diagnostics()
        );
        var propertyDef = new LirPropertyDef(variableDeclaration.name().trim(), propertyType);
        propertyDef.setStatic(variableDeclaration.isStatic());
        applyPropertyAnnotations(variableDeclaration, propertyDef, context);
        applyScriptEnumExportHint(variableDeclaration, declaredTypeScope, propertyDef, context);
        return propertyDef;
    }

    private void applyPropertyAnnotations(
            @NotNull VariableDeclaration variableDeclaration,
            @NotNull LirPropertyDef propertyDef,
            @NotNull SkeletonBuildContext context
    ) {
        for (var annotation : context.analysisData().annotationsByAst().getOrDefault(variableDeclaration, List.of())) {
            // The shared export helper owns argument evaluation: well-formed export annotations
            // map to their Godot hint_string metadata, while malformed ones stay retention-only
            // in the side table (the usage analyzer owns the argument diagnostics). Same-name
            // duplicates keep last-wins Map semantics.
            switch (FrontendExportAnnotationSupport.evaluate(annotation)) {
                case FrontendExportAnnotationSupport.Evaluation.Supported supported ->
                        propertyDef.getAnnotations().put(supported.hintKey(), supported.hintStringValue());
                case FrontendExportAnnotationSupport.Evaluation.Malformed _ -> {
                }
                case FrontendExportAnnotationSupport.Evaluation.NotExportFamily _ -> {
                    switch (annotation.name()) {
                        case "onready" -> propertyDef.getAnnotations().put("onready", "");
                        // Class-level annotations mis-attached to a member stay retention-only in
                        // the side table; their placement diagnostics belong to the usage analyzer.
                        case "tool", "icon" -> {
                        }
                        default -> reportUnsupportedPropertyAnnotation(annotation, variableDeclaration, context);
                    }
                }
            }
        }
    }

    /// Bare `@export` on a property whose declared type resolves to a successfully evaluated
    /// script enum earns a generated `Name:value` hint_string (Godot `export_annotations` ENUM
    /// branch parity), stored under the honest `"export"` key so the backend maps it to
    /// `PROPERTY_HINT_ENUM`. Runs at skeleton time because the enum pre-pass has already
    /// published `GDCC_ENUM` type-meta while lowering would only see the erased `int` type.
    ///
    /// Skipped shapes keep their previous behavior:
    /// - explicit export variants (`@export_range` etc.) own their metadata, even stacked with
    ///   a bare `@export`;
    /// - failed enums publish no type-meta, so their Variant fallback carries no hint;
    /// - non-enum declared types keep the empty bare-export encoding.
    private void applyScriptEnumExportHint(
            @NotNull VariableDeclaration variableDeclaration,
            @NotNull Scope declaredTypeScope,
            @NotNull LirPropertyDef propertyDef,
            @NotNull SkeletonBuildContext context
    ) {
        var annotations = propertyDef.getAnnotations();
        if (!annotations.containsKey("export") || hasExplicitExportVariant(annotations)) {
            return;
        }
        var typeRef = variableDeclaration.type();
        if (typeRef == null) {
            return;
        }
        // Look the declared type text up as a type-meta name through the same source-facing
        // scope protocol the declared-type resolver uses (lexical names first, top-level class
        // remap as fallback). This intentionally is not the full declared-type resolver:
        // qualified `Other.State` type annotations and container texts like `Array[State]`
        // never name a GDCC_ENUM type-meta and stay hint-free.
        var typeMeta = FrontendModuleSkeleton.resolveSourceFacingTypeMeta(
                declaredTypeScope,
                typeRef.sourceText().trim(),
                ResolveRestriction.unrestricted(),
                context.moduleTopLevelCanonicalNameMap()
        ).allowedValueOrNull();
        if (typeMeta == null
                || typeMeta.kind() != ScopeTypeMetaKind.GDCC_ENUM
                || !(typeMeta.declaration() instanceof GdScriptEnumGroup enumGroup)) {
            return;
        }
        var hintString = new StringBuilder();
        for (var member : enumGroup.members()) {
            if (!hintString.isEmpty()) {
                hintString.append(',');
            }
            hintString.append(StringUtil.capitalize(member.memberName())).append(':').append(member.value());
        }
        annotations.put("export", hintString.toString());
    }

    private static boolean hasExplicitExportVariant(@NotNull Map<String, String> annotations) {
        for (var key : annotations.keySet()) {
            if (key.startsWith("export_")) {
                return true;
            }
        }
        return false;
    }

    /// Skeleton build only understands the small property annotation subset that already maps to
    /// stable LIR metadata. Other parsed annotations are preserved in the shared side tables, but
    /// diagnosed here so downstream analysis does not silently lose that unsupported semantic input.
    private void reportUnsupportedPropertyAnnotation(
            @NotNull FrontendGdAnnotation annotation,
            @NotNull VariableDeclaration variableDeclaration,
            @NotNull SkeletonBuildContext context
    ) {
        context.diagnostics().error(
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
            @NotNull Scope declaredTypeScope,
            @NotNull SkeletonBuildContext context
    ) {
        var functionDef = new LirFunctionDef(functionDeclaration.name().trim());
        // Bare-callee binding and later call analysis both depend on skeleton metadata preserving
        // whether a declared function is static or instance-owned.
        functionDef.setStatic(functionDeclaration.isStatic());
        functionDef.setReturnType(FrontendDeclaredTypeSupport.resolveTypeOrVariant(
                functionDeclaration.returnType(),
                declaredTypeScope,
                context.moduleTopLevelCanonicalNameMap(),
                context.sourcePath(),
                context.diagnostics()
        ));
        fillFunctionParameters(
                functionDef,
                functionDeclaration.parameters(),
                declaredTypeScope,
                context
        );
        return functionDef;
    }

    /// The shared gdcc class surface models constructors as the special `_init` function entry used
    /// by Godot, regardless of whether the parser surfaced them as `ConstructorDeclaration` or as a
    /// function declaration literally named `_init`.
    ///
    /// Constructors are always modeled as `void _init(...)` at the skeleton layer. We therefore do
    /// not forward any parser-side return annotation placeholder into the shared `ClassDef`
    /// function surface.
    private @NotNull LirFunctionDef toLirInitFunction(
            @NotNull List<Parameter> parameters,
            @NotNull Scope declaredTypeScope,
            @NotNull SkeletonBuildContext context
    ) {
        var functionDef = new LirFunctionDef("_init");
        fillFunctionParameters(functionDef, parameters, declaredTypeScope, context);
        return functionDef;
    }

    private void fillFunctionParameters(
            @NotNull LirFunctionDef functionDef,
            @NotNull List<Parameter> parameters,
            @NotNull Scope declaredTypeScope,
            @NotNull SkeletonBuildContext context
    ) {
        for (var parameter : parameters) {
            var parameterType = FrontendDeclaredTypeSupport.resolveTypeOrVariant(
                    parameter.type(),
                    declaredTypeScope,
                    context.moduleTopLevelCanonicalNameMap(),
                    context.sourcePath(),
                    context.diagnostics()
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
    }

    /// `_init` is the only constructor slot on a gdcc class.
    ///
    /// Godot exposes constructors through the special `_init` member name, so a class must never
    /// publish more than one `_init` function into its skeleton. Tolerant frontend behavior here is
    /// still diagnostic + skip-duplicate instead of a hard failure.
    private void addFunctionMember(
            @NotNull LirClassDef classDef,
            @NotNull LirFunctionDef functionDef,
            @NotNull Node sourceNode,
            @NotNull SkeletonBuildContext context
    ) {
        if (functionDef.getName().equals("_init") && classDef.hasFunction("_init")) {
            context.diagnostics().error(
                    "sema.class_skeleton",
                    "Class '" + classDef.getName()
                            + "' declares more than one '_init' constructor/function; duplicate declaration will be skipped",
                    context.sourcePath(),
                    FrontendRange.fromAstRange(sourceNode.range())
            );
            return;
        }
        classDef.addFunction(functionDef);
    }

    private void markSkippedSubtreeRoots(
            @NotNull List<? extends Node> skippedRoots,
            @NotNull FrontendAnalysisData analysisData
    ) {
        for (var skippedRoot : skippedRoots) {
            analysisData.skippedSubtreeRoots().put(
                    Objects.requireNonNull(skippedRoot, "skippedRoot must not be null"),
                    Boolean.TRUE
            );
        }
    }

    /// Compiler-owned synthetic property helpers are materialized later under `_field_init_*`,
    /// `_field_getter_*`, and `_field_setter_*`. Rejecting source members that reuse those prefixes
    /// keeps later lowering/backend phases from colliding with user-defined names.
    private boolean rejectReservedSyntheticPropertyHelperMember(
            @NotNull Node sourceNode,
            @NotNull String memberKind,
            @NotNull String memberName,
            @NotNull SkeletonBuildContext context
    ) {
        var matchedPrefix = FrontendSyntheticPropertyHelperSupport.reservedPrefixOrNull(memberName);
        if (matchedPrefix == null) {
            return false;
        }
        context.diagnostics().error(
                "sema.class_skeleton",
                FrontendSyntheticPropertyHelperSupport.reservedPrefixDiagnosticMessage(
                        memberKind,
                        memberName,
                        matchedPrefix
                ),
                context.sourcePath(),
                FrontendRange.fromAstRange(sourceNode.range())
        );
        markSkippedSubtreeRoots(List.of(sourceNode), context.analysisData());
        return true;
    }

    /// One static property declaration whose hierarchy conflict check is deferred until every
    /// class shell in the module has been filled. `source` is kept so a conflict can still anchor
    /// the diagnostic and skip the exact AST subtree after the property is removed again.
    private record PendingStaticPropertyConflictCheck(
            @NotNull Path sourcePath,
            @NotNull LirClassDef ownerClass,
            @NotNull LirPropertyDef property,
            @NotNull VariableDeclaration source
    ) {
    }

    /// Rejects a source `static var` whose name collides with any property on the inheritance
    /// chain (static or instance, GDCC or engine). Godot treats such redeclaration as an error,
    /// and silently keeping it would let the subclass binding shadow the shared static storage
    /// owner. Only the new static declaration surface is constrained; the existing shadowing
    /// behavior of non-static properties stays untouched.
    ///
    /// Recovery mirrors the engine-signal shadow rule: diagnostic + remove the property from the
    /// class skeleton + skip the source subtree, so scope publication never sees the member.
    private void validateStaticPropertyHierarchyConflicts(
            @NotNull List<PendingStaticPropertyConflictCheck> pendingChecks,
            @NotNull ClassRegistry classRegistry,
            @NotNull DiagnosticManager diagnosticManager,
            @NotNull FrontendAnalysisData analysisData
    ) {
        for (var pending : pendingChecks) {
            var superName = pending.ownerClass().getSuperName();
            if (superName.isBlank()) {
                continue;
            }
            var inherited = classRegistry.findPropertyInHierarchy(superName, pending.property().getName());
            if (inherited == null) {
                continue;
            }
            diagnosticManager.error(
                    "sema.class_skeleton",
                    "Static property '" + pending.property().getName() + "' on class '"
                            + pending.ownerClass().getName() + "' conflicts with inherited member '"
                            + inherited.ownerClass().getName() + "." + inherited.property().getName() + "'",
                    pending.sourcePath(),
                    FrontendRange.fromAstRange(pending.source().range())
            );
            pending.ownerClass().removeProperty(pending.property());
            markSkippedSubtreeRoots(List.of(pending.source()), analysisData);
        }
    }

    /// GDCC may shadow an inherited GDCC signal, but it must not redeclare an inherited
    /// engine/native signal. The lookup walks past GDCC parents and only matches `ExtensionGdClass`
    /// metadata, so a same-name parent GDCC signal never triggers this reject.
    private boolean rejectEngineNativeSignalShadow(
            @NotNull LirClassDef classDef,
            @NotNull SignalStatement signalStatement,
            @NotNull SkeletonBuildContext context
    ) {
        var signalName = signalStatement.name().trim();
        var inheritedEngineSignal = context.classRegistry().findEngineSignalInHierarchy(
                classDef.getSuperName(),
                signalName
        );
        if (inheritedEngineSignal == null) {
            return false;
        }
        context.diagnostics().error(
                "sema.class_skeleton",
                "Signal '" + signalName + "' on class '" + classDef.getName()
                        + "' conflicts with inherited engine/native signal '"
                        + inheritedEngineSignal.ownerClass().getName() + "." + signalName + "'",
                context.sourcePath(),
                FrontendRange.fromAstRange(signalStatement.range())
        );
        markSkippedSubtreeRoots(List.of(signalStatement), context.analysisData());
        return true;
    }

    /// Skeleton member filling still runs before the real scope phase, so it materializes a minimal
    /// class-scope chain dedicated to type resolution:
    /// - `ClassRegistry` remains the root
    /// - each accepted class gets one `ClassScope`
    /// - every class scope publishes only its direct inner classes through the shared helper
    ///
    /// No value/function/parameter bindings are prefilled here; this scaffold exists solely so
    /// shared strict declared type resolution can already follow the eventual lexical type namespace.
    private @NotNull IdentityHashMap<Node, ClassScope> buildDeclaredTypeScopes(
            @NotNull FrontendSourceClassRelation sourceClassRelation,
            @NotNull ClassRegistry classRegistry
    ) {
        var scopesByAstOwner = new IdentityHashMap<Node, ClassScope>();
        var topLevelScope = new ClassScope(
                Objects.requireNonNull(classRegistry, "classRegistry"),
                classRegistry,
                sourceClassRelation.topLevelClassDef()
        );
        scopesByAstOwner.put(sourceClassRelation.astOwner(), topLevelScope);
        for (var innerRelation : sourceClassRelation.findImmediateInnerRelations(sourceClassRelation.astOwner())) {
            topLevelScope.defineTypeMeta(innerRelation.toTypeMeta());
        }
        buildDeclaredTypeScopes(
                sourceClassRelation,
                sourceClassRelation.astOwner(),
                topLevelScope,
                scopesByAstOwner,
                classRegistry
        );
        return scopesByAstOwner;
    }

    private void buildDeclaredTypeScopes(
            @NotNull FrontendSourceClassRelation sourceClassRelation,
            @NotNull Node lexicalOwner,
            @NotNull ClassScope parentScope,
            @NotNull IdentityHashMap<Node, ClassScope> scopesByAstOwner,
            @NotNull ClassRegistry classRegistry
    ) {
        for (var innerClassRelation : sourceClassRelation.findImmediateInnerRelations(lexicalOwner)) {
            var innerScope = new ClassScope(parentScope, classRegistry, innerClassRelation.classDef());
            scopesByAstOwner.put(innerClassRelation.astOwner(), innerScope);
            for (var childInnerRelation : sourceClassRelation.findImmediateInnerRelations(innerClassRelation.astOwner())) {
                innerScope.defineTypeMeta(childInnerRelation.toTypeMeta());
            }
            buildDeclaredTypeScopes(
                    sourceClassRelation,
                    innerClassRelation.astOwner(),
                    innerScope,
                    scopesByAstOwner,
                    classRegistry
            );
        }
    }

    private @NotNull ClassScope requireDeclaredTypeScope(
            @NotNull IdentityHashMap<Node, ClassScope> scopesByAstOwner,
            @NotNull Node astOwner
    ) {
        var scope = scopesByAstOwner.get(astOwner);
        if (scope == null) {
            throw new IllegalStateException("Missing declared type scope for accepted class owner");
        }
        return scope;
    }

    /// Module class-header discovery pass:
    /// - discovers a complete module-local class header graph before member filling starts
    /// - validates duplicate/canonical/unsupported-super/cycle issues against that graph
    /// - records rejected subtree roots explicitly so downstream processing can skip only those
    ///   regions
    private @NotNull ModuleClassHeaderDiscovery discoverModuleClassHeaders(
            @NotNull FrontendModule module,
            @NotNull ClassRegistry classRegistry,
            @NotNull DiagnosticManager diagnosticManager
    ) {
        Objects.requireNonNull(module, "module must not be null");
        // Header discovery receives the frozen module carrier directly so the same module-level
        // mapping remains available while deriving canonical identities and validating headers.
        var units = module.units();
        var sourceUnitHeaders = new ArrayList<MutableSourceUnitHeaders>(units.size());
        var discoveredHeadersInOrder = new ArrayList<MutableClassHeader>();
        var rejectedCandidates = new ArrayList<RejectedClassHeader>();
        var rejectedSubtreeRoots = new ArrayList<Node>();

        for (var unit : units) {
            var topLevelHeader = discoverTopLevelHeader(
                    unit,
                    module.topLevelCanonicalNameMap(),
                    discoveredHeadersInOrder,
                    rejectedCandidates,
                    rejectedSubtreeRoots,
                    diagnosticManager
            );
            sourceUnitHeaders.add(new MutableSourceUnitHeaders(unit, topLevelHeader));
        }

        rejectDuplicateTopLevelHeaders(
                sourceUnitHeaders,
                rejectedCandidates,
                rejectedSubtreeRoots,
                diagnosticManager
        );
        rejectDuplicateInnerHeadersByLexicalOwner(
                discoveredHeadersInOrder,
                rejectedCandidates,
                rejectedSubtreeRoots,
                diagnosticManager
        );
        rejectCanonicalHeaderConflicts(
                discoveredHeadersInOrder,
                rejectedCandidates,
                rejectedSubtreeRoots,
                diagnosticManager
        );

        var validationIndex = buildHeaderValidationIndex(discoveredHeadersInOrder);
        rejectHeadersWithUnsupportedSuperclassSources(
                discoveredHeadersInOrder,
                validationIndex,
                classRegistry,
                rejectedCandidates,
                rejectedSubtreeRoots,
                diagnosticManager
        );
        rejectHeadersWithInheritanceCycles(
                discoveredHeadersInOrder,
                validationIndex,
                classRegistry,
                rejectedCandidates,
                rejectedSubtreeRoots,
                diagnosticManager
        );
        rejectHeadersDependingOnRejectedSupers(
                discoveredHeadersInOrder,
                validationIndex,
                classRegistry,
                rejectedCandidates,
                rejectedSubtreeRoots,
                diagnosticManager
        );
        return freezeModuleClassHeaderDiscovery(
                sourceUnitHeaders,
                classRegistry,
                validationIndex,
                rejectedCandidates,
                rejectedSubtreeRoots
        );
    }

    /// Every source unit always contributes exactly one synthetic top-level script class header,
    /// even when later validation rejects it. This keeps module-wide conflict validation operating
    /// on one complete header graph before any skeleton object is published.
    private @NotNull MutableClassHeader discoverTopLevelHeader(
            @NotNull FrontendSourceUnit unit,
            @NotNull Map<String, String> topLevelCanonicalNameMap,
            @NotNull List<MutableClassHeader> discoveredHeadersInOrder,
            @NotNull List<RejectedClassHeader> rejectedCandidates,
            @NotNull List<Node> rejectedSubtreeRoots,
            @NotNull DiagnosticManager diagnosticManager
    ) {
        var classNameStatement = firstClassNameStatement(unit.ast().statements());
        var topLevelSourceName = resolveClassName(unit.path(), classNameStatement);
        var topLevelCanonicalName = resolveTopLevelCanonicalName(topLevelSourceName, topLevelCanonicalNameMap);
        var topLevelHeader = new MutableClassHeader(
                unit,
                null,
                unit.ast(),
                unit.ast(),
                topLevelSourceName,
                topLevelCanonicalName,
                resolveTopLevelRawExtendsText(unit.ast().statements(), classNameStatement),
                FrontendRange.fromAstRange(topLevelClassRange(unit)),
                true
        );
        discoveredHeadersInOrder.add(topLevelHeader);
        // Reject compiler-owned class-level identities at the source boundary: the coroutine
        // state-class prefix (contract §1.3 rule 1) and the reserved sequences (`__sub__` /
        // `__coro__`), so source-space names stay disjoint from generated canonical identities.
        if (FrontendClassNameContract.startsWithCoroStateClassPrefix(topLevelSourceName)) {
            diagnosticManager.error(
                    "sema.class_skeleton",
                    reservedClassPrefixDiagnostic("Top-level", topLevelSourceName),
                    unit.path(),
                    topLevelHeader.range()
            );
            rejectDiscoveredSubtree(topLevelHeader, rejectedCandidates, rejectedSubtreeRoots);
            return topLevelHeader;
        }
        if (FrontendClassNameContract.containsReservedSequence(topLevelSourceName)) {
            diagnosticManager.error(
                    "sema.class_skeleton",
                    reservedClassNameDiagnostic("Top-level", topLevelSourceName),
                    unit.path(),
                    topLevelHeader.range()
            );
            rejectDiscoveredSubtree(topLevelHeader, rejectedCandidates, rejectedSubtreeRoots);
            return topLevelHeader;
        }
        discoverInnerClassHeaders(
                unit,
                unit.ast().statements(),
                topLevelHeader,
                unit.ast(),
                topLevelHeader.canonicalName(),
                discoveredHeadersInOrder,
                rejectedCandidates,
                rejectedSubtreeRoots,
                diagnosticManager
        );
        return topLevelHeader;
    }

    /// Discovery is intentionally tolerant: malformed inner declarations publish diagnostics and
    /// become rejected subtree roots immediately, while valid siblings continue to be discovered in
    /// the same source unit.
    private void discoverInnerClassHeaders(
            @NotNull FrontendSourceUnit unit,
            @NotNull List<Statement> statements,
            @NotNull MutableClassHeader parentHeader,
            @NotNull Node lexicalOwner,
            @NotNull String parentCanonicalName,
            @NotNull List<MutableClassHeader> discoveredHeadersInOrder,
            @NotNull List<RejectedClassHeader> rejectedCandidates,
            @NotNull List<Node> rejectedSubtreeRoots,
            @NotNull DiagnosticManager diagnosticManager
    ) {
        for (var statement : statements) {
            if (!(statement instanceof ClassDeclaration classDeclaration)) {
                continue;
            }

            var innerClassName = StringUtil.trimToNull(classDeclaration.name());
            if (innerClassName == null) {
                diagnosticManager.error(
                        "sema.class_skeleton",
                        "Inner class declaration is missing a class name and will be skipped",
                        unit.path(),
                        FrontendRange.fromAstRange(classDeclaration.range())
                );
                rejectedCandidates.add(new RejectedClassHeader(
                        unit,
                        lexicalOwner,
                        classDeclaration,
                        null,
                        null,
                        StringUtil.trimToNull(classDeclaration.extendsTarget()),
                        FrontendRange.fromAstRange(classDeclaration.range())
                ));
                rejectedSubtreeRoots.add(classDeclaration);
                continue;
            }
            if (FrontendClassNameContract.startsWithCoroStateClassPrefix(innerClassName)) {
                diagnosticManager.error(
                        "sema.class_skeleton",
                        reservedClassPrefixDiagnostic("Inner", innerClassName),
                        unit.path(),
                        FrontendRange.fromAstRange(classDeclaration.range())
                );
                rejectedCandidates.add(new RejectedClassHeader(
                        unit,
                        lexicalOwner,
                        classDeclaration,
                        innerClassName,
                        null,
                        StringUtil.trimToNull(classDeclaration.extendsTarget()),
                        FrontendRange.fromAstRange(classDeclaration.range())
                ));
                rejectedSubtreeRoots.add(classDeclaration);
                continue;
            }
            if (FrontendClassNameContract.containsReservedSequence(innerClassName)) {
                diagnosticManager.error(
                        "sema.class_skeleton",
                        reservedClassNameDiagnostic("Inner", innerClassName),
                        unit.path(),
                        FrontendRange.fromAstRange(classDeclaration.range())
                );
                rejectedCandidates.add(new RejectedClassHeader(
                        unit,
                        lexicalOwner,
                        classDeclaration,
                        innerClassName,
                        null,
                        StringUtil.trimToNull(classDeclaration.extendsTarget()),
                        FrontendRange.fromAstRange(classDeclaration.range())
                ));
                rejectedSubtreeRoots.add(classDeclaration);
                continue;
            }

            var innerHeader = new MutableClassHeader(
                    unit,
                    parentHeader,
                    lexicalOwner,
                    classDeclaration,
                    innerClassName,
                    // Inner canonical names remain a pure frontend-derived identity: once the top-level
                    // canonical is frozen, every nested layer appends the reserved separator and the
                    // source-facing local name exactly once at header discovery time.
                    parentCanonicalName
                            + FrontendClassNameContract.INNER_CLASS_CANONICAL_SEPARATOR
                            + innerClassName,
                    StringUtil.trimToNull(classDeclaration.extendsTarget()),
                    FrontendRange.fromAstRange(classDeclaration.range()),
                    false
            );
            parentHeader.immediateInnerHeaders().add(innerHeader);
            discoveredHeadersInOrder.add(innerHeader);
            discoverInnerClassHeaders(
                    unit,
                    classDeclaration.body().statements(),
                    innerHeader,
                    classDeclaration,
                    innerHeader.canonicalName(),
                    discoveredHeadersInOrder,
                    rejectedCandidates,
                    rejectedSubtreeRoots,
                    diagnosticManager
            );
        }
    }

    private void rejectDuplicateTopLevelHeaders(
            @NotNull List<MutableSourceUnitHeaders> sourceUnitHeaders,
            @NotNull List<RejectedClassHeader> rejectedCandidates,
            @NotNull List<Node> rejectedSubtreeRoots,
            @NotNull DiagnosticManager diagnosticManager
    ) {
        var firstByName = new LinkedHashMap<String, MutableClassHeader>();
        for (var sourceUnitHeader : sourceUnitHeaders) {
            var topLevelHeader = sourceUnitHeader.topLevelHeader();
            if (!topLevelHeader.isActive()) {
                continue;
            }
            var existing = firstByName.putIfAbsent(topLevelHeader.sourceName(), topLevelHeader);
            if (existing != null) {
                reportDuplicateTopLevelClass(existing, topLevelHeader, diagnosticManager);
                rejectDiscoveredSubtree(topLevelHeader, rejectedCandidates, rejectedSubtreeRoots);
            }
        }
    }

    /// Inner class source names are only required to be unique under the same immediate lexical
    /// owner. Different branches may reuse the same source name because their canonical names still
    /// diverge through the owner chain.
    private void rejectDuplicateInnerHeadersByLexicalOwner(
            @NotNull List<MutableClassHeader> discoveredHeadersInOrder,
            @NotNull List<RejectedClassHeader> rejectedCandidates,
            @NotNull List<Node> rejectedSubtreeRoots,
            @NotNull DiagnosticManager diagnosticManager
    ) {
        var firstByOwnerAndName = new IdentityHashMap<Node, Map<String, MutableClassHeader>>();
        for (var discoveredHeader : discoveredHeadersInOrder) {
            if (discoveredHeader.isTopLevel() || !discoveredHeader.isActive()) {
                continue;
            }

            var headersByName = firstByOwnerAndName.computeIfAbsent(
                    discoveredHeader.lexicalOwner(),
                    _ -> new LinkedHashMap<>()
            );
            var existing = headersByName.putIfAbsent(
                    discoveredHeader.sourceName(),
                    discoveredHeader
            );
            if (existing == null) {
                continue;
            }

            diagnosticManager.error(
                    "sema.class_skeleton",
                    "Duplicate inner class name '" + discoveredHeader.sourceName()
                            + "' under lexical owner '" + describeLexicalOwner(discoveredHeader)
                            + "'; duplicate skeleton subtree will be skipped",
                    discoveredHeader.unit().path(),
                    discoveredHeader.range()
            );
            rejectDiscoveredSubtree(
                    discoveredHeader,
                    rejectedCandidates,
                    rejectedSubtreeRoots
            );
        }
    }

    private void rejectCanonicalHeaderConflicts(
            @NotNull List<MutableClassHeader> discoveredHeadersInOrder,
            @NotNull List<RejectedClassHeader> rejectedCandidates,
            @NotNull List<Node> rejectedSubtreeRoots,
            @NotNull DiagnosticManager diagnosticManager
    ) {
        var firstByCanonicalName = new LinkedHashMap<String, MutableClassHeader>();
        for (var discoveredHeader : discoveredHeadersInOrder) {
            if (!discoveredHeader.isActive()) {
                continue;
            }

            var existing = firstByCanonicalName.putIfAbsent(
                    discoveredHeader.canonicalName(),
                    discoveredHeader
            );
            if (existing == null) {
                continue;
            }

            diagnosticManager.error(
                    "sema.class_skeleton",
                    "Canonical class name conflict '" + discoveredHeader.canonicalName()
                            + "' between "
                            + describeClassHeaderOrigin(existing)
                            + " and "
                            + describeClassHeaderOrigin(discoveredHeader)
                            + "; conflicting skeleton subtree will be skipped",
                    discoveredHeader.unit().path(),
                    discoveredHeader.range()
            );
            rejectDiscoveredSubtree(
                    discoveredHeader,
                    rejectedCandidates,
                    rejectedSubtreeRoots
            );
        }
    }

    private void reportDuplicateTopLevelClass(
            @NotNull MutableClassHeader existing,
            @NotNull MutableClassHeader duplicate,
            @NotNull DiagnosticManager diagnosticManager
    ) {
        var className = duplicate.sourceName();
        var message = "Duplicate top-level class source name '" + className + "' found in "
                + existing.unit().path() + " and " + duplicate.unit().path()
                + "; duplicate skeleton subtree will be skipped";
        diagnosticManager.error(
                "sema.class_skeleton",
                message,
                duplicate.unit().path(),
                duplicate.range()
        );
    }

    /// Builds the minimal lookup tables needed by class-header validation:
    /// - canonical-name hits for conflict validation and precise diagnostics
    /// - per-owner inner source-name hits for lexical lookup
    /// - AST owner recovery so inner headers can walk back to enclosing lexical owners
    private @NotNull HeaderValidationIndex buildHeaderValidationIndex(
            @NotNull List<MutableClassHeader> discoveredHeadersInOrder
    ) {
        var firstTopLevelByName = new LinkedHashMap<String, MutableClassHeader>();
        var firstByCanonicalName = new LinkedHashMap<String, MutableClassHeader>();
        var firstInnerByLexicalOwner = new IdentityHashMap<Node, Map<String, MutableClassHeader>>();
        var headersByAstOwner = new IdentityHashMap<Node, MutableClassHeader>();

        for (var discoveredHeader : discoveredHeadersInOrder) {
            firstByCanonicalName.putIfAbsent(
                    discoveredHeader.canonicalName(),
                    discoveredHeader
            );
            var previousByOwner = headersByAstOwner.putIfAbsent(
                    discoveredHeader.astOwner(),
                    discoveredHeader
            );
            if (previousByOwner != null) {
                throw new IllegalStateException(
                        "Duplicate AST owner encountered while building class header index"
                );
            }
            if (discoveredHeader.isTopLevel()) {
                firstTopLevelByName.putIfAbsent(
                        discoveredHeader.sourceName(),
                        discoveredHeader
                );
                continue;
            }
            firstInnerByLexicalOwner.computeIfAbsent(
                    discoveredHeader.lexicalOwner(),
                    _ -> new LinkedHashMap<>()
            ).putIfAbsent(
                    discoveredHeader.sourceName(),
                    discoveredHeader
            );
        }
        return new HeaderValidationIndex(
                firstTopLevelByName,
                firstByCanonicalName,
                firstInnerByLexicalOwner,
                headersByAstOwner
        );
    }

    private void rejectHeadersWithInheritanceCycles(
            @NotNull List<MutableClassHeader> discoveredHeadersInOrder,
            @NotNull HeaderValidationIndex validationIndex,
            @NotNull ClassRegistry classRegistry,
            @NotNull List<RejectedClassHeader> rejectedCandidates,
            @NotNull List<Node> rejectedSubtreeRoots,
            @NotNull DiagnosticManager diagnosticManager
    ) {
        var states = new IdentityHashMap<MutableClassHeader, VisitState>();
        var visitStack = new ArrayList<MutableClassHeader>();
        for (var discoveredHeader : discoveredHeadersInOrder) {
            if (!discoveredHeader.isActive()) {
                continue;
            }
            detectInheritanceProblemsDfs(
                    discoveredHeader,
                    validationIndex,
                    classRegistry,
                    states,
                    visitStack,
                    rejectedCandidates,
                    rejectedSubtreeRoots,
                    diagnosticManager
            );
        }
    }

    /// Header `extends` is a source-facing frontend protocol, not a raw canonical-name lookup:
    /// - the current compilation's only gdcc module contributes inner/top-level source-visible names
    /// - engine/native classes may still bind when `sourceName == canonicalName`
    /// - all other raw texts are rejected explicitly before accepted headers freeze superclass facts
    private void rejectHeadersWithUnsupportedSuperclassSources(
            @NotNull List<MutableClassHeader> discoveredHeadersInOrder,
            @NotNull HeaderValidationIndex validationIndex,
            @NotNull ClassRegistry classRegistry,
            @NotNull List<RejectedClassHeader> rejectedCandidates,
            @NotNull List<Node> rejectedSubtreeRoots,
            @NotNull DiagnosticManager diagnosticManager
    ) {
        for (var discoveredHeader : discoveredHeadersInOrder) {
            if (!discoveredHeader.isActive()) {
                continue;
            }

            var bindingDecision = resolveHeaderSuperBindingDecision(
                    discoveredHeader,
                    validationIndex,
                    classRegistry
            );
            if (bindingDecision.accepted()) {
                continue;
            }

            var rawExtendsText = Objects.requireNonNull(
                    bindingDecision.sourceText(),
                    "Unsupported superclass rejection requires raw extends text"
            );
            diagnosticManager.error(
                    "sema.class_skeleton",
                    "Class '" + discoveredHeader.canonicalName()
                            + "' declares unsupported superclass '" + rawExtendsText
                            + "': " + Objects.requireNonNull(bindingDecision.rejectionReason(), "rejectionReason")
                            + "; its skeleton subtree will be skipped",
                    discoveredHeader.unit().path(),
                    discoveredHeader.range()
            );
            rejectDiscoveredSubtree(
                    discoveredHeader,
                    rejectedCandidates,
                    rejectedSubtreeRoots
            );
        }
    }

    private void detectInheritanceProblemsDfs(
            @NotNull MutableClassHeader discoveredHeader,
            @NotNull HeaderValidationIndex validationIndex,
            @NotNull ClassRegistry classRegistry,
            @NotNull IdentityHashMap<MutableClassHeader, VisitState> states,
            @NotNull List<MutableClassHeader> visitStack,
            @NotNull List<RejectedClassHeader> rejectedCandidates,
            @NotNull List<Node> rejectedSubtreeRoots,
            @NotNull DiagnosticManager diagnosticManager
    ) {
        var state = states.getOrDefault(discoveredHeader, VisitState.UNVISITED);
        if (state == VisitState.VISITED) {
            return;
        }
        if (state == VisitState.VISITING) {
            reportInheritanceCycle(
                    discoveredHeader,
                    visitStack,
                    rejectedCandidates,
                    rejectedSubtreeRoots,
                    diagnosticManager
            );
            return;
        }

        states.put(discoveredHeader, VisitState.VISITING);
        visitStack.add(discoveredHeader);

        var superHeader = resolveHeaderSuperBindingDecision(
                discoveredHeader,
                validationIndex,
                classRegistry
        ).referencedHeader();
        if (superHeader != null && superHeader.isActive()) {
            detectInheritanceProblemsDfs(
                    superHeader,
                    validationIndex,
                    classRegistry,
                    states,
                    visitStack,
                    rejectedCandidates,
                    rejectedSubtreeRoots,
                    diagnosticManager
            );
        }

        visitStack.removeLast();
        states.put(discoveredHeader, VisitState.VISITED);
    }

    private void reportInheritanceCycle(
            @NotNull MutableClassHeader reenteredHeader,
            @NotNull List<MutableClassHeader> visitStack,
            @NotNull List<RejectedClassHeader> rejectedCandidates,
            @NotNull List<Node> rejectedSubtreeRoots,
            @NotNull DiagnosticManager diagnosticManager
    ) {
        var cycleStart = visitStack.indexOf(reenteredHeader);
        var cyclePath = new ArrayList<>(visitStack.subList(cycleStart, visitStack.size()));
        cyclePath.add(reenteredHeader);
        var chainText = cyclePath.stream()
                .map(MutableClassHeader::canonicalName)
                .reduce((left, right) -> left + " -> " + right)
                .orElse(reenteredHeader.canonicalName());

        for (var cycleHeader : new LinkedHashSet<>(cyclePath)) {
            if (!cycleHeader.isActive()) {
                continue;
            }
            diagnosticManager.error(
                    "sema.inheritance_cycle",
                    "Class '" + cycleHeader.canonicalName()
                            + "' participates in inheritance cycle: "
                            + chainText
                            + "; its skeleton subtree will be skipped",
                    cycleHeader.unit().path(),
                    cycleHeader.range()
            );
            rejectDiscoveredSubtree(
                    cycleHeader,
                    rejectedCandidates,
                    rejectedSubtreeRoots
            );
        }
    }

    private void rejectHeadersDependingOnRejectedSupers(
            @NotNull List<MutableClassHeader> discoveredHeadersInOrder,
            @NotNull HeaderValidationIndex validationIndex,
            @NotNull ClassRegistry classRegistry,
            @NotNull List<RejectedClassHeader> rejectedCandidates,
            @NotNull List<Node> rejectedSubtreeRoots,
            @NotNull DiagnosticManager diagnosticManager
    ) {
        boolean changed;
        do {
            changed = false;
            for (var discoveredHeader : discoveredHeadersInOrder) {
                if (!discoveredHeader.isActive()) {
                    continue;
                }

                var superHeader = resolveHeaderSuperBindingDecision(
                        discoveredHeader,
                        validationIndex,
                        classRegistry
                ).referencedHeader();
                if (superHeader == null || superHeader.isActive()) {
                    continue;
                }

                diagnosticManager.error(
                        "sema.class_skeleton",
                        "Class '" + discoveredHeader.canonicalName()
                                + "' will be skipped because its super class '"
                                + superHeader.canonicalName()
                                + "' was rejected by earlier inheritance diagnostics",
                        discoveredHeader.unit().path(),
                        discoveredHeader.range()
                );
                rejectDiscoveredSubtree(
                        discoveredHeader,
                        rejectedCandidates,
                        rejectedSubtreeRoots
                );
                changed = true;
            }
        } while (changed);
    }

    /// Resolves the header-layer superclass candidate inside the current compilation's only gdcc
    /// module using source-facing names only:
    /// - walk lexical owner chain for visible inner source names
    /// - finally fall back to same-module top-level source names
    private @Nullable MutableClassHeader resolveCurrentModuleHeaderSuperCandidate(
            @NotNull MutableClassHeader discoveredHeader,
            @NotNull HeaderValidationIndex validationIndex
    ) {
        var rawExtendsText = discoveredHeader.rawExtendsText();
        if (rawExtendsText == null) {
            return null;
        }

        Node lexicalOwner = discoveredHeader.isTopLevel()
                ? null
                : discoveredHeader.lexicalOwner();
        while (lexicalOwner != null) {
            var innerHeadersByName = validationIndex.firstInnerByLexicalOwner().get(
                    lexicalOwner
            );
            if (innerHeadersByName != null) {
                var innerHit = innerHeadersByName.get(rawExtendsText);
                if (innerHit != null) {
                    return innerHit;
                }
            }
            lexicalOwner = nextLexicalOwner(lexicalOwner, validationIndex);
        }
        return validationIndex.firstTopLevelByName().get(rawExtendsText);
    }

    private @Nullable Node nextLexicalOwner(
            @NotNull Node lexicalOwner,
            @NotNull HeaderValidationIndex validationIndex
    ) {
        if (lexicalOwner instanceof SourceFile) {
            return null;
        }
        var ownerHeader = validationIndex.headersByAstOwner().get(lexicalOwner);
        if (ownerHeader == null) {
            throw new IllegalStateException(
                    "Missing owner header while resolving lexical class header chain"
            );
        }
        return ownerHeader.lexicalOwner();
    }

    /// Marks one discovered header as the rejected root and then propagates a lighter
    /// `REJECTED_DESCENDANT` state into all nested children. Only the root is recorded as a
    /// rejected candidate/rejected subtree output item; descendants are skipped implicitly with the
    /// subtree.
    private void rejectDiscoveredSubtree(
            @NotNull MutableClassHeader rejectedRoot,
            @NotNull List<RejectedClassHeader> rejectedCandidates,
            @NotNull List<Node> rejectedSubtreeRoots
    ) {
        if (!rejectedRoot.isActive()) {
            return;
        }
        rejectedRoot.setState(HeaderCandidateState.REJECTED_CANDIDATE);
        rejectedCandidates.add(toRejectedClassHeader(rejectedRoot));
        rejectedSubtreeRoots.add(rejectedRoot.astOwner());
        markRejectedDescendants(rejectedRoot);
    }

    private void markRejectedDescendants(@NotNull MutableClassHeader rejectedRoot) {
        for (var childHeader : rejectedRoot.immediateInnerHeaders()) {
            if (childHeader.state() == HeaderCandidateState.ACTIVE) {
                childHeader.setState(HeaderCandidateState.REJECTED_DESCENDANT);
            }
            markRejectedDescendants(childHeader);
        }
    }

    private @NotNull RejectedClassHeader toRejectedClassHeader(
            @NotNull MutableClassHeader rejectedHeader
    ) {
        return new RejectedClassHeader(
                rejectedHeader.unit(),
                rejectedHeader.lexicalOwner(),
                rejectedHeader.astOwner(),
                rejectedHeader.sourceName(),
                rejectedHeader.canonicalName(),
                rejectedHeader.rawExtendsText(),
                rejectedHeader.range()
        );
    }

    private @NotNull ModuleClassHeaderDiscovery freezeModuleClassHeaderDiscovery(
            @NotNull List<MutableSourceUnitHeaders> sourceUnitHeaders,
            @NotNull ClassRegistry classRegistry,
            @NotNull HeaderValidationIndex validationIndex,
            @NotNull List<RejectedClassHeader> rejectedCandidates,
            @NotNull List<Node> rejectedSubtreeRoots
    ) {
        var sourceUnitGraphs = sourceUnitHeaders.stream()
                .map(sourceUnitHeader -> freezeSourceUnitHeaderGraph(
                        sourceUnitHeader,
                        classRegistry,
                        validationIndex
                ))
                .toList();
        return new ModuleClassHeaderDiscovery(
                sourceUnitGraphs,
                List.copyOf(rejectedCandidates),
                List.copyOf(rejectedSubtreeRoots)
        );
    }

    private @NotNull SourceUnitClassHeaderGraph freezeSourceUnitHeaderGraph(
            @NotNull MutableSourceUnitHeaders sourceUnitHeader,
            @NotNull ClassRegistry classRegistry,
            @NotNull HeaderValidationIndex validationIndex
    ) {
        return new SourceUnitClassHeaderGraph(
                sourceUnitHeader.unit(),
                sourceUnitHeader.topLevelHeader().isActive()
                        ? freezeAcceptedHeader(
                        sourceUnitHeader.topLevelHeader(),
                        classRegistry,
                        validationIndex
                )
                        : null
        );
    }

    private @NotNull AcceptedClassHeader freezeAcceptedHeader(
            @NotNull MutableClassHeader discoveredHeader,
            @NotNull ClassRegistry classRegistry,
            @NotNull HeaderValidationIndex validationIndex
    ) {
        var acceptedInnerHeaders = discoveredHeader.immediateInnerHeaders().stream()
                .filter(MutableClassHeader::isActive)
                .map(innerHeader -> freezeAcceptedHeader(
                        innerHeader,
                        classRegistry,
                        validationIndex
                ))
                .toList();
        return new AcceptedClassHeader(
                discoveredHeader.unit(),
                discoveredHeader.lexicalOwner(),
                discoveredHeader.astOwner(),
                discoveredHeader.sourceName(),
                discoveredHeader.canonicalName(),
                resolveHeaderSuperBindingDecision(
                        discoveredHeader,
                        validationIndex,
                        classRegistry
                ).requireAcceptedSuperClassRef(),
                discoveredHeader.rawExtendsText(),
                discoveredHeader.range(),
                acceptedInnerHeaders
        );
    }

    /// Computes the structured header-super binding fact consumed by accepted freeze,
    /// unsupported-source diagnostics, and same-module inheritance validation.
    private @NotNull HeaderSuperBindingDecision resolveHeaderSuperBindingDecision(
            @NotNull MutableClassHeader discoveredHeader,
            @NotNull HeaderValidationIndex validationIndex,
            @NotNull ClassRegistry classRegistry
    ) {
        var rawExtendsText = discoveredHeader.rawExtendsText();
        if (rawExtendsText == null) {
            return HeaderSuperBindingDecision.accepted(
                    HeaderSuperBindingKind.IMPLICIT_DEFAULT,
                    null,
                    null,
                    null,
                    new FrontendSuperClassRef(
                            DEFAULT_SUPER_CLASS_NAME,
                            DEFAULT_SUPER_CLASS_NAME
                    )
            );
        }

        var superHeader = resolveCurrentModuleHeaderSuperCandidate(discoveredHeader, validationIndex);
        if (superHeader != null) {
            return HeaderSuperBindingDecision.accepted(
                    HeaderSuperBindingKind.CURRENT_MODULE_HEADER,
                    rawExtendsText,
                    superHeader,
                    null,
                    new FrontendSuperClassRef(
                            superHeader.sourceName(),
                            superHeader.canonicalName()
                    )
            );
        }
        if (looksLikePathBasedSuperclassTarget(rawExtendsText)) {
            return HeaderSuperBindingDecision.rejected(
                    HeaderSuperBindingKind.REJECTED_PATH_BASED,
                    rawExtendsText,
                    null,
                    null,
                    "path-based extends targets are not supported in frontend superclass binding"
            );
        }
        if (classRegistry.isSingleton(rawExtendsText)) {
            return HeaderSuperBindingDecision.rejected(
                    HeaderSuperBindingKind.REJECTED_SINGLETON,
                    rawExtendsText,
                    null,
                    null,
                    "autoload/singleton superclasses are not supported in frontend superclass binding"
            );
        }
        if (rawExtendsText.contains(FrontendClassNameContract.INNER_CLASS_CANONICAL_SEPARATOR)) {
            var canonicalHit = validationIndex.firstByCanonicalName().get(rawExtendsText);
            if (canonicalHit != null) {
                return HeaderSuperBindingDecision.rejected(
                        HeaderSuperBindingKind.REJECTED_CANONICAL_TEXT,
                        rawExtendsText,
                        canonicalHit,
                        null,
                        "canonical '"
                                + FrontendClassNameContract.INNER_CLASS_CANONICAL_SEPARATOR
                                + "' spelling is not part of supported frontend extends syntax; use source-facing name '"
                                + canonicalHit.sourceName() + "' instead"
                );
            }
            return HeaderSuperBindingDecision.rejected(
                    HeaderSuperBindingKind.REJECTED_CANONICAL_TEXT,
                    rawExtendsText,
                    null,
                    null,
                    "canonical '" + FrontendClassNameContract.INNER_CLASS_CANONICAL_SEPARATOR
                            + "' spelling is not part of frontend extends syntax"
            );
        }

        var typeMeta = classRegistry.resolveTypeMetaHere(rawExtendsText);
        if (typeMeta != null) {
            return switch (typeMeta.kind()) {
                case ENGINE_CLASS -> HeaderSuperBindingDecision.accepted(
                        HeaderSuperBindingKind.ENGINE_CLASS,
                        rawExtendsText,
                        null,
                        typeMeta,
                        new FrontendSuperClassRef(
                                typeMeta.sourceName(),
                                typeMeta.canonicalName()
                        )
                );
                case GDCC_CLASS -> HeaderSuperBindingDecision.rejected(
                        HeaderSuperBindingKind.REJECTED_UNSUPPORTED_GDCC_SOURCE,
                        rawExtendsText,
                        null,
                        typeMeta,
                        "Currently frontend superclass binding does not support this gdcc superclass source; only source-facing names from the current module are accepted"
                );
                case BUILTIN -> HeaderSuperBindingDecision.rejected(
                        HeaderSuperBindingKind.REJECTED_BUILTIN,
                        rawExtendsText,
                        null,
                        typeMeta,
                        "builtin types cannot be used as superclasses in frontend superclass binding"
                );
                case GLOBAL_ENUM, GDCC_ENUM -> HeaderSuperBindingDecision.rejected(
                        HeaderSuperBindingKind.REJECTED_ENUM,
                        rawExtendsText,
                        null,
                        typeMeta,
                        "enum names cannot be used as superclasses in frontend superclass binding"
                );
            };
        }
        return HeaderSuperBindingDecision.rejected(
                HeaderSuperBindingKind.REJECTED_UNRESOLVED,
                rawExtendsText,
                null,
                null,
                "only source-facing names from the current module and engine/native class names are supported"
        );
    }

    private boolean looksLikePathBasedSuperclassTarget(@NotNull String rawExtendsText) {
        var trimmed = rawExtendsText.trim();
        return trimmed.startsWith("\"")
                || trimmed.startsWith("'")
                || trimmed.startsWith("preload(")
                || trimmed.startsWith("load(")
                || trimmed.contains("://")
                || trimmed.contains("/")
                || trimmed.contains("\\");
    }

    /// Top-level canonical-name mapping is frozen once at the module boundary and applied during
    /// header discovery so duplicate/canonical/conflict validation all see the final identity.
    private @NotNull String resolveTopLevelCanonicalName(
            @NotNull String sourceName,
            @NotNull Map<String, String> topLevelCanonicalNameMap
    ) {
        return topLevelCanonicalNameMap.getOrDefault(sourceName, sourceName);
    }

    private @NotNull String reservedClassNameDiagnostic(@NotNull String classKind, @NotNull String className) {
        return classKind + " class name '" + className + "' contains reserved gdcc class-name sequence '"
                + FrontendClassNameContract.reservedSequenceOrNull(className)
                + "'; this spelling is reserved for compiler-generated canonical names, so the skeleton subtree will be skipped";
    }

    private @NotNull String reservedClassPrefixDiagnostic(@NotNull String classKind, @NotNull String className) {
        return classKind + " class name '" + className + "' uses reserved gdcc class-name prefix '"
                + FrontendClassNameContract.CORO_STATE_CLASS_PREFIX
                + "'; this prefix is reserved for compiler-generated coroutine state classes,"
                + " so the skeleton subtree will be skipped";
    }

    private @NotNull String describeLexicalOwner(@NotNull MutableClassHeader discoveredHeader) {
        var parentHeader = discoveredHeader.parentHeader();
        if (parentHeader != null) {
            return parentHeader.canonicalName();
        }
        return discoveredHeader.unit().path().toString();
    }

    private @NotNull String describeClassHeaderOrigin(@NotNull MutableClassHeader discoveredHeader) {
        var classKind = discoveredHeader.isTopLevel()
                ? discoveredHeader.sourceName().equals(discoveredHeader.canonicalName())
                  ? "top-level class '" + discoveredHeader.sourceName() + "'"
                  : "top-level class source '" + discoveredHeader.sourceName()
                    + "' (canonical '" + discoveredHeader.canonicalName() + "')"
                : "inner class '" + discoveredHeader.sourceName()
                  + "' (canonical '" + discoveredHeader.canonicalName() + "')";
        return discoveredHeader.unit().path() + " (" + classKind + ")";
    }

    private enum VisitState {
        UNVISITED,
        VISITING,
        VISITED
    }

    private enum HeaderCandidateState {
        ACTIVE,
        REJECTED_CANDIDATE,
        REJECTED_DESCENDANT
    }

    private enum HeaderSuperBindingKind {
        IMPLICIT_DEFAULT,
        CURRENT_MODULE_HEADER,
        ENGINE_CLASS,
        REJECTED_PATH_BASED,
        REJECTED_SINGLETON,
        REJECTED_CANONICAL_TEXT,
        REJECTED_UNSUPPORTED_GDCC_SOURCE,
        REJECTED_BUILTIN,
        REJECTED_ENUM,
        REJECTED_UNRESOLVED
    }

    /// Immutable accepted/rejected output of the header discovery pass.
    private record ModuleClassHeaderDiscovery(
            @NotNull List<SourceUnitClassHeaderGraph> sourceUnitGraphs,
            @NotNull List<RejectedClassHeader> rejectedCandidates,
            @NotNull List<Node> rejectedSubtreeRoots
    ) {
    }

    private record SourceUnitClassHeaderGraph(
            @NotNull FrontendSourceUnit unit,
            @Nullable AcceptedClassHeader topLevelHeader
    ) {
    }

    private record AcceptedClassHeader(
            @NotNull FrontendSourceUnit unit,
            @NotNull Node lexicalOwner,
            @NotNull Node astOwner,
            @NotNull String sourceName,
            @NotNull String canonicalName,
            @NotNull FrontendSuperClassRef superClassRef,
            @Nullable String rawExtendsText,
            @Nullable FrontendRange range,
            @NotNull List<AcceptedClassHeader> immediateInnerHeaders
    ) {
        private AcceptedClassHeader {
            Objects.requireNonNull(superClassRef, "superClassRef");
        }
    }

    private record RejectedClassHeader(
            @NotNull FrontendSourceUnit unit,
            @NotNull Node lexicalOwner,
            @NotNull Node astOwner,
            @Nullable String sourceName,
            @Nullable String canonicalName,
            @Nullable String rawExtendsText,
            @Nullable FrontendRange range
    ) {
    }

    private record MutableSourceUnitHeaders(
            @NotNull FrontendSourceUnit unit,
            @NotNull MutableClassHeader topLevelHeader
    ) {
    }

    private record HeaderValidationIndex(
            @NotNull Map<String, MutableClassHeader> firstTopLevelByName,
            @NotNull Map<String, MutableClassHeader> firstByCanonicalName,
            @NotNull IdentityHashMap<Node, Map<String, MutableClassHeader>> firstInnerByLexicalOwner,
            @NotNull IdentityHashMap<Node, MutableClassHeader> headersByAstOwner
    ) {
    }

    private record HeaderSuperBindingDecision(
            @NotNull HeaderSuperBindingKind kind,
            @Nullable String sourceText,
            @Nullable MutableClassHeader referencedHeader,
            @Nullable ScopeTypeMeta resolvedMeta,
            @Nullable FrontendSuperClassRef acceptedSuperClassRef,
            @Nullable String rejectionReason
    ) {
        private HeaderSuperBindingDecision {
            Objects.requireNonNull(kind, "kind");
        }

        private static @NotNull HeaderSuperBindingDecision accepted(
                @NotNull HeaderSuperBindingKind kind,
                @Nullable String sourceText,
                @Nullable MutableClassHeader referencedHeader,
                @Nullable ScopeTypeMeta resolvedMeta,
                @NotNull FrontendSuperClassRef acceptedSuperClassRef
        ) {
            return new HeaderSuperBindingDecision(
                    kind,
                    sourceText,
                    referencedHeader,
                    resolvedMeta,
                    acceptedSuperClassRef,
                    null
            );
        }

        private static @NotNull HeaderSuperBindingDecision rejected(
                @NotNull HeaderSuperBindingKind kind,
                @Nullable String sourceText,
                @Nullable MutableClassHeader referencedHeader,
                @Nullable ScopeTypeMeta resolvedMeta,
                @NotNull String rejectionReason
        ) {
            return new HeaderSuperBindingDecision(
                    kind,
                    sourceText,
                    referencedHeader,
                    resolvedMeta,
                    null,
                    rejectionReason
            );
        }

        private boolean accepted() {
            return acceptedSuperClassRef != null;
        }

        private @NotNull FrontendSuperClassRef requireAcceptedSuperClassRef() {
            if (acceptedSuperClassRef == null) {
                throw new IllegalStateException(
                        "Header super binding kind '" + kind + "' does not carry an accepted superclass ref"
                );
            }
            return acceptedSuperClassRef;
        }
    }

    /// Mutable discovery node used only while class-header discovery and validation assemble the
    /// final immutable accepted and rejected header views.
    private static final class MutableClassHeader {
        private final @NotNull FrontendSourceUnit unit;
        private final @Nullable MutableClassHeader parentHeader;
        private final @NotNull Node lexicalOwner;
        private final @NotNull Node astOwner;
        private final @NotNull String sourceName;
        private final @NotNull String canonicalName;
        private final @Nullable String rawExtendsText;
        private final @Nullable FrontendRange range;
        private final boolean topLevel;
        private final @NotNull List<MutableClassHeader> immediateInnerHeaders = new ArrayList<>();
        private @NotNull HeaderCandidateState state = HeaderCandidateState.ACTIVE;

        private MutableClassHeader(
                @NotNull FrontendSourceUnit unit,
                @Nullable MutableClassHeader parentHeader,
                @NotNull Node lexicalOwner,
                @NotNull Node astOwner,
                @NotNull String sourceName,
                @NotNull String canonicalName,
                @Nullable String rawExtendsText,
                @Nullable FrontendRange range,
                boolean topLevel
        ) {
            this.unit = Objects.requireNonNull(unit, "unit");
            this.parentHeader = parentHeader;
            this.lexicalOwner = Objects.requireNonNull(lexicalOwner, "lexicalOwner");
            this.astOwner = Objects.requireNonNull(astOwner, "astOwner");
            this.sourceName = Objects.requireNonNull(sourceName, "sourceName");
            this.canonicalName = Objects.requireNonNull(canonicalName, "canonicalName");
            this.rawExtendsText = rawExtendsText;
            this.range = range;
            this.topLevel = topLevel;
        }

        private @NotNull FrontendSourceUnit unit() {
            return unit;
        }

        private @Nullable MutableClassHeader parentHeader() {
            return parentHeader;
        }

        private @NotNull Node lexicalOwner() {
            return lexicalOwner;
        }

        private @NotNull Node astOwner() {
            return astOwner;
        }

        private @NotNull String sourceName() {
            return sourceName;
        }

        private @NotNull String canonicalName() {
            return canonicalName;
        }

        private @Nullable String rawExtendsText() {
            return rawExtendsText;
        }

        private @Nullable FrontendRange range() {
            return range;
        }

        private boolean isTopLevel() {
            return topLevel;
        }

        private @NotNull List<MutableClassHeader> immediateInnerHeaders() {
            return immediateInnerHeaders;
        }

        private @NotNull HeaderCandidateState state() {
            return state;
        }

        private void setState(@NotNull HeaderCandidateState state) {
            this.state = Objects.requireNonNull(state, "state");
        }

        private boolean isActive() {
            return state == HeaderCandidateState.ACTIVE;
        }
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
            @NotNull FrontendAnalysisData analysisData,
            @NotNull Map<String, String> moduleTopLevelCanonicalNameMap
    ) {
    }
}
