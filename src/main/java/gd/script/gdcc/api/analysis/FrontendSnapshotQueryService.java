package gd.script.gdcc.api.analysis;

import dev.superice.gdparser.frontend.ast.Node;
import gd.script.gdcc.frontend.sema.FrontendBinding;
import gd.script.gdcc.frontend.sema.FrontendCallResolutionStatus;
import gd.script.gdcc.frontend.sema.FrontendMemberResolutionStatus;
import gd.script.gdcc.frontend.sema.FrontendResolvedCall;
import gd.script.gdcc.frontend.sema.FrontendResolvedMember;
import gd.script.gdcc.gdextension.ExtensionBuiltinClass;
import gd.script.gdcc.gdextension.ExtensionEnumValue;
import gd.script.gdcc.gdextension.ExtensionGdClass;
import gd.script.gdcc.gdextension.ExtensionGlobalConstant;
import gd.script.gdcc.gdextension.ExtensionGlobalEnum;
import gd.script.gdcc.gdextension.ExtensionUtilityFunction;
import gd.script.gdcc.scope.ClassDef;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.scope.FunctionDef;
import gd.script.gdcc.scope.GdScriptClassConstant;
import gd.script.gdcc.scope.GdScriptEnumConstant;
import gd.script.gdcc.scope.GdScriptEnumGroup;
import gd.script.gdcc.scope.GdScriptLanguageConstant;
import gd.script.gdcc.scope.PropertyDef;
import gd.script.gdcc.scope.Scope;
import gd.script.gdcc.scope.ScopeLookupStatus;
import gd.script.gdcc.scope.ScopeOwnerKind;
import gd.script.gdcc.scope.SignalDef;
import gd.script.gdcc.type.GdType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/// In-process cursor query service over a published `ModuleAnalysisSnapshot`
/// (`frontend_lsp_foundation_implementation.md` §2.4). All methods are static, take the snapshot
/// as first parameter, and are pure reads.
/// Fact-projection queries (`definitionAt`/`usagesAt`/`typeAt`/`documentationAt`) return DTOs
/// (ranges, names, enums); `nodeAt` exposes the underlying AST node READ-ONLY — gdparser AST
/// graphs are deeply immutable (child collections frozen at construction), and the side tables
/// that would make node identity keys queryable stay package-private, so external callers can
/// inspect the node but cannot resolve it against any snapshot generation.
///
/// Read-only model access (`frontend_lsp_foundation_implementation.md` §2.1): model objects
/// reachable inside the frozen
/// containers (`Scope`, `ClassDef` and friends) are only touched through mutator-free views
/// here — no mutator is ever called; the structural freeze installed at snapshot construction
/// stays as the second line of defense underneath.
///
/// Coordinate contract: inputs are a byte offset or a (0-based row, 0-based byte column) pair
/// (UTF-8 semantics, matching gdparser ranges); outputs use display paths with 1-based
/// line/column spans plus raw byte spans (`QuerySourceRange`).
///
/// Absent-result contract: unknown display paths, `parse.internal` units, offsets without node
/// coverage, unbound identifiers and fact-free nodes all return empty results — never throw and
/// never resolve against another snapshot generation.
public final class FrontendSnapshotQueryService {
    private FrontendSnapshotQueryService() {
    }

    // ------------------------------------------------------------------
    // nodeAt
    // ------------------------------------------------------------------

    /// Deepest node covering `byteOffset` under the half-open rule `[startByte, endByte)`;
    /// zero-width ranges never cover. `null` for unknown paths, failed units and uncovered
    /// offsets. The returned node belongs to THIS snapshot's AST generation: callers must not
    /// mix it with another snapshot's nodes (identity keys are generation-scoped).
    public static @Nullable Node nodeAt(
            @NotNull ModuleAnalysisSnapshot snapshot,
            @NotNull String displayPath,
            int byteOffset
    ) {
        var unitIndex = requireUnitIndex(snapshot, displayPath);
        return unitIndex == null ? null : unitIndex.nodeAt(byteOffset);
    }

    /// (0-based row, 0-based UTF-8 byte column) variant of `nodeAt`; an out-of-range coordinate
    /// converts to `null` rather than leaking into a neighbouring line.
    public static @Nullable Node nodeAt(
            @NotNull ModuleAnalysisSnapshot snapshot,
            @NotNull String displayPath,
            int row0,
            int column0
    ) {
        var unitIndex = requireUnitIndex(snapshot, displayPath);
        if (unitIndex == null) {
            return null;
        }
        var byteOffset = unitIndex.byteOffsetAt(row0, column0);
        return byteOffset == null ? null : unitIndex.nodeAt(byteOffset);
    }

    // ------------------------------------------------------------------
    // definitionAt
    // ------------------------------------------------------------------

    /// Normalizes the declaration provenance of the symbol at the cursor. See
    /// `DeclarationLookupResult` for the four result kinds; normalization never picks one element of
    /// a collection-shaped provenance and never drops un-localizable elements.
    public static @NotNull DeclarationLookupResult definitionAt(
            @NotNull ModuleAnalysisSnapshot snapshot,
            @NotNull String displayPath,
            int byteOffset
    ) {
        var fact = factAt(snapshot, displayPath, byteOffset, false);
        if (fact == null || fact.declarationSite() == null) {
            return DeclarationLookupResult.none();
        }
        var normalized = DeclarationNormalizer.normalize(fact.declarationSite(), snapshot);
        return switch (normalized.outcome()) {
            case NO_SITE -> DeclarationLookupResult.none();
            case ALL_EXTERNAL -> DeclarationLookupResult.external();
            case NORMALIZED -> toLookup(normalized, snapshot);
        };
    }

    /// (0-based row, 0-based byte column) overload of `definitionAt`.
    public static @NotNull DeclarationLookupResult definitionAt(
            @NotNull ModuleAnalysisSnapshot snapshot,
            @NotNull String displayPath,
            int row0,
            int column0
    ) {
        var unitIndex = requireUnitIndex(snapshot, displayPath);
        if (unitIndex == null) {
            return DeclarationLookupResult.none();
        }
        var byteOffset = unitIndex.byteOffsetAt(row0, column0);
        return byteOffset == null
                ? DeclarationLookupResult.none()
                : definitionAt(snapshot, displayPath, byteOffset);
    }

    private static @NotNull DeclarationLookupResult toLookup(
            DeclarationNormalizer.@NotNull Result normalized,
            @NotNull ModuleAnalysisSnapshot snapshot
    ) {
        // Candidates preserve provenance order: a failed element keeps its slot as an explicit
        // null-location marker instead of being dropped or appended after the located ones.
        var candidates = new ArrayList<DeclarationLookupResult.Candidate>(normalized.elements().size());
        var locatedCount = 0;
        for (var declarationNode : normalized.elements()) {
            var location = declarationNode == null
                    ? null
                    : DeclarationNormalizer.locate(declarationNode, snapshot);
            if (location != null) {
                locatedCount++;
            }
            candidates.add(new DeclarationLookupResult.Candidate(location));
        }
        if (locatedCount == 0) {
            return DeclarationLookupResult.external();
        }
        // A single located declaration reports SINGLE_SOURCE even when it arrived as a
        // one-element collection (method references publish as List[1]); MULTIPLE_CANDIDATES is
        // reserved for genuinely ambiguous provenance (multi-element collections or any
        // un-localizable element).
        if (normalized.elements().size() == 1 && locatedCount == 1) {
            return DeclarationLookupResult.single(Objects.requireNonNull(candidates.getFirst().location()));
        }
        return DeclarationLookupResult.multiple(candidates);
    }

    // ------------------------------------------------------------------
    // usagesAt
    // ------------------------------------------------------------------

    /// All use sites grouped under the same normalized source declaration identity as the
    /// symbol at the cursor, sorted by (displayPath, startByte). Collection-shaped provenance
    /// unions the groups of every normalizable element. Sites whose provenance fails to
    /// normalize never appear (documented absent sets: unbound identifiers,
    /// `DEFERRED`/`UNSUPPORTED` sites, type-position references).
    public static @NotNull List<QuerySourceRange> usagesAt(
            @NotNull ModuleAnalysisSnapshot snapshot,
            @NotNull String displayPath,
            int byteOffset
    ) {
        var fact = factAt(snapshot, displayPath, byteOffset, false);
        if (fact == null || fact.declarationSite() == null) {
            return List.of();
        }
        var normalized = DeclarationNormalizer.normalize(fact.declarationSite(), snapshot);
        if (normalized.outcome() != DeclarationNormalizer.Outcome.NORMALIZED) {
            return List.of();
        }
        var reverseIndex = UsagesReverseIndex.require(snapshot);
        var deduped = new LinkedHashSet<QuerySourceRange>();
        for (var declarationNode : normalized.sourceNodes()) {
            var sites = reverseIndex.get(declarationNode);
            if (sites != null) {
                deduped.addAll(sites);
            }
        }
        return deduped.stream().sorted(UsagesReverseIndex.SITE_ORDER).toList();
    }

    /// (0-based row, 0-based byte column) overload of `usagesAt`.
    public static @NotNull List<QuerySourceRange> usagesAt(
            @NotNull ModuleAnalysisSnapshot snapshot,
            @NotNull String displayPath,
            int row0,
            int column0
    ) {
        var unitIndex = requireUnitIndex(snapshot, displayPath);
        if (unitIndex == null) {
            return List.of();
        }
        var byteOffset = unitIndex.byteOffsetAt(row0, column0);
        return byteOffset == null ? List.of() : usagesAt(snapshot, displayPath, byteOffset);
    }

    // ------------------------------------------------------------------
    // typeAt
    // ------------------------------------------------------------------

    /// Display name of the published type at the cursor. Consults `expressionTypes()` first
    /// (its key space includes attribute step nodes, so `obj.x|`, `obj.f()|` and `obj[i]|`
    /// steps all answer), then `slotTypes()` for variable slots. Facts whose status carries no
    /// published type (`DEFERRED`/`FAILED`/`UNSUPPORTED`) and fact-free nodes return empty.
    public static @NotNull Optional<String> typeAt(
            @NotNull ModuleAnalysisSnapshot snapshot,
            @NotNull String displayPath,
            int byteOffset
    ) {
        var node = nodeAt(snapshot, displayPath, byteOffset);
        if (node == null) {
            return Optional.empty();
        }
        var analysisData = snapshot.analysisData();
        var expressionType = analysisData.expressionTypes().get(node);
        if (expressionType != null && expressionType.publishedType() != null) {
            return Optional.of(expressionType.publishedType().getTypeName());
        }
        var slotType = analysisData.slotTypes().get(node);
        return slotType == null ? Optional.empty() : Optional.of(slotType.getTypeName());
    }

    /// (0-based row, 0-based byte column) overload of `typeAt`.
    public static @NotNull Optional<String> typeAt(
            @NotNull ModuleAnalysisSnapshot snapshot,
            @NotNull String displayPath,
            int row0,
            int column0
    ) {
        var unitIndex = requireUnitIndex(snapshot, displayPath);
        if (unitIndex == null) {
            return Optional.empty();
        }
        var byteOffset = unitIndex.byteOffsetAt(row0, column0);
        return byteOffset == null ? Optional.empty() : typeAt(snapshot, displayPath, byteOffset);
    }

    // ------------------------------------------------------------------
    // documentationAt
    // ------------------------------------------------------------------

    /// Projects the symbol at the cursor into a `SymbolDocDescriptor`. Only `RESOLVED`
    /// member/call facts classify — `FAILED`/`DEFERRED`/`UNSUPPORTED`/`DYNAMIC`/`BLOCKED` sites
    /// and unbound identifiers return empty. External classification is driven by
    /// the declaration model type and registry provenance, never by a value's runtime type.
    public static @NotNull Optional<SymbolDocDescriptor> documentationAt(
            @NotNull ModuleAnalysisSnapshot snapshot,
            @NotNull String displayPath,
            int byteOffset
    ) {
        var fact = factAt(snapshot, displayPath, byteOffset, true);
        if (fact == null || fact.declarationSite() == null) {
            return Optional.empty();
        }
        var declarationSite = Objects.requireNonNull(fact.declarationSite());

        // GDCC source classification first: anything normalizable to a source declaration is a
        // user symbol and carries every normalized position.
        var normalized = DeclarationNormalizer.normalize(declarationSite, snapshot);
        if (normalized.outcome() == DeclarationNormalizer.Outcome.NORMALIZED) {
            return gdccDescriptor(snapshot, fact, declarationSite, normalized);
        }
        return externalDescriptor(snapshot, fact, declarationSite);
    }

    /// (0-based row, 0-based byte column) overload of `documentationAt`.
    public static @NotNull Optional<SymbolDocDescriptor> documentationAt(
            @NotNull ModuleAnalysisSnapshot snapshot,
            @NotNull String displayPath,
            int row0,
            int column0
    ) {
        var unitIndex = requireUnitIndex(snapshot, displayPath);
        if (unitIndex == null) {
            return Optional.empty();
        }
        var byteOffset = unitIndex.byteOffsetAt(row0, column0);
        return byteOffset == null ? Optional.empty() : documentationAt(snapshot, displayPath, byteOffset);
    }

    // ------------------------------------------------------------------
    // documentationAt internals
    // ------------------------------------------------------------------

    private static @NotNull Optional<SymbolDocDescriptor> gdccDescriptor(
            @NotNull ModuleAnalysisSnapshot snapshot,
            @NotNull CursorFact fact,
            @NotNull Object declarationSite,
            DeclarationNormalizer.@NotNull Result normalized
    ) {
        // Element-order candidates: un-normalizable elements and foreign-generation nodes keep
        // an explicit null-location slot (§2.4 GDCC rule: carry ALL candidates, pick nothing).
        var candidates = new ArrayList<SymbolDocDescriptor.SourceCandidate>(normalized.elements().size());
        Node firstDeclarationNode = null;
        for (var declarationNode : normalized.elements()) {
            var location = declarationNode == null
                    ? null
                    : DeclarationNormalizer.locate(declarationNode, snapshot);
            if (location != null && firstDeclarationNode == null) {
                firstDeclarationNode = declarationNode;
            }
            candidates.add(new SymbolDocDescriptor.SourceCandidate(location));
        }
        if (firstDeclarationNode == null) {
            return Optional.empty();
        }
        var kind = classifyKind(declarationSite, fact.binding());
        // Locals and parameters have no class owner; member kinds recover the declaring class
        // from the scope recorded at the FIRST LOCATED declaration (provenance order =
        // resolution order, i.e. the nearest declaration wins).
        String ownerName = switch (kind) {
            case PROPERTY, METHOD, SIGNAL, CONSTANT, ENUM_GROUP, ENUM_VALUE, TYPE ->
                    declaringClassName(snapshot, firstDeclarationNode);
            case LOCAL_VARIABLE, PARAMETER, UTILITY_FUNCTION -> null;
        };
        return Optional.of(new SymbolDocDescriptor(kind, DocNamespace.GDCC, ownerName, fact.memberName(), candidates));
    }

    private static @NotNull Optional<SymbolDocDescriptor> externalDescriptor(
            @NotNull ModuleAnalysisSnapshot snapshot,
            @NotNull CursorFact fact,
            @NotNull Object declarationSite
    ) {
        var registry = snapshot.classRegistry();
        var memberName = fact.memberName();

        // Utility functions: dump-sourced go to @GlobalScope, registry-synthesized language
        // functions to @GDScript — classified by registry provenance, not by ownerKind.
        switch (declarationSite) {
            case ExtensionUtilityFunction utilityFunction -> {
                var namespace = registry.isGdScriptLanguageFunction(utilityFunction.name())
                        ? DocNamespace.GDSCRIPT
                        : DocNamespace.GLOBAL_SCOPE;
                return Optional.of(descriptor(DocSymbolKind.UTILITY_FUNCTION, namespace, null, utilityFunction.name()));
            }
            case GdScriptLanguageConstant languageConstant -> {
                return Optional.of(descriptor(DocSymbolKind.CONSTANT, DocNamespace.GDSCRIPT, null, languageConstant.name()));
            }
            case ExtensionGlobalConstant globalConstant -> {
                return Optional.of(descriptor(DocSymbolKind.CONSTANT, DocNamespace.GLOBAL_SCOPE, null, globalConstant.name()));
            }
            case ExtensionGlobalEnum globalEnum -> {
                return Optional.of(descriptor(DocSymbolKind.ENUM_GROUP, DocNamespace.GLOBAL_SCOPE, null, globalEnum.name()));
            }
            case ExtensionEnumValue enumValue -> {
                return enumValueDescriptor(snapshot, fact, enumValue);
            }
            case ExtensionGdClass.ConstantInfo _ -> {
                var startClass = requireEngineStartClass(snapshot, fact);
                if (startClass == null) {
                    return Optional.empty();
                }
                var lookup = registry.findEngineClassConstantInHierarchy(startClass.getName(), memberName);
                return lookup == null
                        ? Optional.empty()
                        : Optional.of(descriptor(DocSymbolKind.CONSTANT, DocNamespace.ENGINE, lookup.ownerClass().getName(), memberName));
            }
            case ExtensionBuiltinClass.ConstantInfo _ -> {
                var receiverName = receiverTypeName(fact);
                if (receiverName == null) {
                    return Optional.empty();
                }
                var lookup = registry.findBuiltinClassConstantInHierarchy(receiverName, memberName);
                return lookup == null
                        ? Optional.empty()
                        : Optional.of(descriptor(DocSymbolKind.CONSTANT, DocNamespace.BUILTIN, lookup.ownerClass().getName(), memberName));
            }
            case PropertyDef property -> {
                return propertyDescriptor(snapshot, fact, property);
            }
            case SignalDef _ -> {
                var startClass = requireEngineStartClass(snapshot, fact);
                if (startClass == null) {
                    return Optional.empty();
                }
                var lookup = registry.findEngineSignalInHierarchy(startClass.getName(), memberName);
                return lookup == null
                        ? Optional.empty()
                        : Optional.of(descriptor(DocSymbolKind.SIGNAL, DocNamespace.ENGINE, lookup.ownerClass().getName(), memberName));
            }
            case FunctionDef function -> {
                return methodDescriptor(snapshot, fact, List.of(function), false);
            }
            case Collection<?> collection -> {
                // Bare utility-function bindings publish as overload List<N> of
                // ExtensionUtilityFunction — classify them exactly like the single-function case
                // (provenance split via the registry, not ownerKind).
                ExtensionUtilityFunction utilityFunction = null;
                var allUtility = !collection.isEmpty();
                for (var element : collection) {
                    if (element instanceof ExtensionUtilityFunction candidate) {
                        utilityFunction = utilityFunction == null ? candidate : utilityFunction;
                    } else {
                        allUtility = false;
                        break;
                    }
                }
                if (allUtility) {
                    var namespace = registry.isGdScriptLanguageFunction(utilityFunction.name())
                            ? DocNamespace.GDSCRIPT
                            : DocNamespace.GLOBAL_SCOPE;
                    return Optional.of(descriptor(DocSymbolKind.UTILITY_FUNCTION, namespace, null, utilityFunction.name()));
                }
                // All-external collection provenance: engine/builtin overload sets. Owners are
                // recovered per element by selected-FunctionDef identity; the nearest (first)
                // distinct owner wins, matching most-derived shadowing.
                var functions = new ArrayList<FunctionDef>(collection.size());
                for (var element : collection) {
                    if (element instanceof FunctionDef functionDef) {
                        functions.add(functionDef);
                    }
                }
                if (functions.isEmpty()) {
                    return Optional.empty();
                }
                return methodDescriptor(snapshot, fact, functions, true);
            }
            case ClassDef classDef -> {
                var namespace = classDef instanceof ExtensionBuiltinClass ? DocNamespace.BUILTIN : DocNamespace.ENGINE;
                return Optional.of(descriptor(DocSymbolKind.TYPE, namespace, null, classDef.getName()));
            }
            default -> {
            }
        }
        // Everything else (SELF/LITERAL/SUPER shapes, unrecognized synthetics) has no
        // documentation descriptor.
        return Optional.empty();
    }

    private static @NotNull Optional<SymbolDocDescriptor> enumValueDescriptor(
            @NotNull ModuleAnalysisSnapshot snapshot,
            @NotNull CursorFact fact,
            @NotNull ExtensionEnumValue enumValue
    ) {
        var registry = snapshot.classRegistry();
        // Registry provenance decides the namespace: the same ExtensionEnumValue type backs
        // global enums (@GlobalScope) and class enums, so identity against the global bare-name
        // index is the only reliable split.
        if (registry.findGlobalEnumValueByBareName(enumValue.name()) == enumValue) {
            return Optional.of(descriptor(DocSymbolKind.ENUM_VALUE, DocNamespace.GLOBAL_SCOPE, null, enumValue.name()));
        }
        if (fact.ownerKind() == ScopeOwnerKind.BUILTIN) {
            var receiverName = receiverTypeName(fact);
            var lookup = receiverName == null
                    ? null
                    : registry.findBuiltinClassEnumValueInHierarchy(receiverName, enumValue.name());
            return lookup == null
                    ? Optional.empty()
                    : Optional.of(descriptor(DocSymbolKind.ENUM_VALUE, DocNamespace.BUILTIN, lookup.ownerClass().getName(), enumValue.name()));
        }
        var startClass = requireEngineStartClass(snapshot, fact);
        if (startClass == null) {
            return Optional.empty();
        }
        var lookup = registry.findEngineClassEnumValueInHierarchy(startClass.getName(), enumValue.name());
        return lookup == null
                ? Optional.empty()
                : Optional.of(descriptor(DocSymbolKind.ENUM_VALUE, DocNamespace.ENGINE, lookup.ownerClass().getName(), enumValue.name()));
    }

    private static @NotNull Optional<SymbolDocDescriptor> propertyDescriptor(
            @NotNull ModuleAnalysisSnapshot snapshot,
            @NotNull CursorFact fact,
            @NotNull PropertyDef property
    ) {
        var registry = snapshot.classRegistry();
        // Builtin member PropertyInfo objects are synthesized per lookup (no cross-call
        // identity): the receiver's builtin class is the owner directly.
        if (fact.ownerKind() == ScopeOwnerKind.BUILTIN) {
            var receiverName = receiverTypeName(fact);
            return receiverName == null
                    ? Optional.empty()
                    : Optional.of(descriptor(DocSymbolKind.PROPERTY, DocNamespace.BUILTIN, receiverName, property.getName()));
        }
        var startClassName = receiverTypeName(fact);
        if (startClassName == null) {
            var enclosing = enclosingClass(snapshot, fact);
            startClassName = enclosing == null ? null : enclosing.getName();
        }
        if (startClassName == null) {
            return Optional.empty();
        }
        var lookup = registry.findPropertyInHierarchy(startClassName, property.getName());
        if (lookup == null) {
            return Optional.empty();
        }
        var namespace = lookup.ownerClass() instanceof ExtensionBuiltinClass ? DocNamespace.BUILTIN : DocNamespace.ENGINE;
        return Optional.of(descriptor(DocSymbolKind.PROPERTY, namespace, lookup.ownerClass().getName(), property.getName()));
    }

    private static @NotNull Optional<SymbolDocDescriptor> methodDescriptor(
            @NotNull ModuleAnalysisSnapshot snapshot,
            @NotNull CursorFact fact,
            @NotNull List<FunctionDef> functions,
            boolean overloadSet
    ) {
        // The owner is the class whose function table holds the ALREADY-SELECTED FunctionDef by
        // identity — overload selection must not be re-run and nearest-name heuristics are
        // forbidden.
        var startClassName = receiverTypeName(fact);
        if (startClassName == null) {
            var enclosing = enclosingClass(snapshot, fact);
            startClassName = enclosing == null ? null : enclosing.getName();
        }
        if (startClassName == null) {
            return Optional.empty();
        }
        String ownerName = null;
        ClassDef ownerClass = null;
        for (var function : functions) {
            var found = findMethodOwner(snapshot.classRegistry(), startClassName, function);
            if (found != null) {
                ownerName = found.getName();
                ownerClass = found;
                break;
            }
        }
        if (ownerName == null) {
            return Optional.empty();
        }
        var namespace = ownerClass instanceof ExtensionBuiltinClass ? DocNamespace.BUILTIN : DocNamespace.ENGINE;
        return Optional.of(descriptor(DocSymbolKind.METHOD, namespace, ownerName, fact.memberName()));
    }

    /// Walks the superclass chain from `startClassName` and returns the class whose function
    /// table contains `selected` BY OBJECT IDENTITY, or `null` when the chain does not declare
    /// it (e.g. the start class was not the resolution receiver).
    private static @Nullable ClassDef findMethodOwner(
            @NotNull ClassRegistry registry,
            @NotNull String startClassName,
            @NotNull FunctionDef selected
    ) {
        var classDef = registry.resolveClassDefByName(startClassName);
        while (classDef != null) {
            for (var candidate : classDef.getFunctions()) {
                if (candidate == selected) {
                    return classDef;
                }
            }
            classDef = registry.resolveSuperclass(classDef);
        }
        return null;
    }

    // ------------------------------------------------------------------
    // shared helpers
    // ------------------------------------------------------------------

    /// The fact published for the cursor node. Lookup order is binding → member → call; the
    /// three tables key disjoint node shapes (identifier, property step, call node/step), so at
    /// most one carries a meaningful entry per node. `resolvedOnly` applies the
    /// documentationAt status filter.
    private static @Nullable CursorFact factAt(
            @NotNull ModuleAnalysisSnapshot snapshot,
            @NotNull String displayPath,
            int byteOffset,
            boolean resolvedOnly
    ) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        Objects.requireNonNull(displayPath, "displayPath must not be null");
        var node = nodeAt(snapshot, displayPath, byteOffset);
        if (node == null) {
            return null;
        }
        var analysisData = snapshot.analysisData();
        var binding = analysisData.symbolBindings().get(node);
        var member = analysisData.resolvedMembers().get(node);
        var call = analysisData.resolvedCalls().get(node);
        if (resolvedOnly) {
            if (member != null && member.status() != FrontendMemberResolutionStatus.RESOLVED) {
                member = null;
            }
            if (call != null && call.status() != FrontendCallResolutionStatus.RESOLVED) {
                call = null;
            }
            // Value bindings carry no resolution status field of their own; a FOUND_BLOCKED
            // binding (declaration-before-use, parameter-default islands) is the binding-side
            // BLOCKED shape and must produce no descriptor (acceptance 7).
            if (binding != null && binding.valueAccessStatus() == ScopeLookupStatus.FOUND_BLOCKED) {
                binding = null;
            }
        }
        if (binding == null && member == null && call == null) {
            return null;
        }
        return new CursorFact(snapshot, node, binding, member, call);
    }

    private static @Nullable AstUnitIndex requireUnitIndex(
            @NotNull ModuleAnalysisSnapshot snapshot,
            @NotNull String displayPath
    ) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        Objects.requireNonNull(displayPath, "displayPath must not be null");
        return snapshot.astIndex().forDisplayPath(displayPath);
    }

    private static @NotNull SymbolDocDescriptor descriptor(
            @NotNull DocSymbolKind kind,
            @NotNull DocNamespace namespace,
            @Nullable String ownerName,
            @NotNull String memberName
    ) {
        return new SymbolDocDescriptor(kind, namespace, ownerName, memberName, List.of());
    }

    private static @NotNull DocSymbolKind classifyKind(@NotNull Object declarationSite, @Nullable FrontendBinding binding) {
        switch (declarationSite) {
            case PropertyDef _ -> {
                return DocSymbolKind.PROPERTY;
            }
            case SignalDef _ -> {
                return DocSymbolKind.SIGNAL;
            }
            case GdScriptEnumConstant _ -> {
                return DocSymbolKind.ENUM_VALUE;
            }
            case GdScriptEnumGroup _ -> {
                return DocSymbolKind.ENUM_GROUP;
            }
            default -> {
            }
        }
        if (declarationSite instanceof FunctionDef || declarationSite instanceof Collection<?>) {
            return DocSymbolKind.METHOD;
        }
        if (declarationSite instanceof GdScriptClassConstant classConstant) {
            // The wrapper's own category is meaningless: classify by the enum declaration it
            // carries (ENUM_VALUE / ENUM_GROUP), matching the unwrapped normalization.
            return classifyKind(classConstant.declaration(), binding);
        }
        if (declarationSite instanceof ClassDef) {
            return DocSymbolKind.TYPE;
        }
        // AST-node provenance: classify by the binding kind.
        if (binding != null) {
            return switch (binding.kind()) {
                case PARAMETER -> DocSymbolKind.PARAMETER;
                case LOCAL_VAR, CAPTURE -> DocSymbolKind.LOCAL_VARIABLE;
                case PROPERTY -> DocSymbolKind.PROPERTY;
                case SIGNAL -> DocSymbolKind.SIGNAL;
                case METHOD, STATIC_METHOD -> DocSymbolKind.METHOD;
                case CONSTANT, GLOBAL_ENUM -> DocSymbolKind.CONSTANT;
                case UTILITY_FUNCTION -> DocSymbolKind.UTILITY_FUNCTION;
                case TYPE_META, SINGLETON -> DocSymbolKind.TYPE;
                case SELF, SUPER, LITERAL, UNKNOWN -> DocSymbolKind.LOCAL_VARIABLE;
            };
        }
        return DocSymbolKind.LOCAL_VARIABLE;
    }

    /// Receiver class name from a member/call fact's published receiver type, or `null`.
    private static @Nullable String receiverTypeName(@NotNull CursorFact fact) {
        GdType receiverType = fact.member() != null
                ? fact.member().receiverType()
                : fact.call() != null ? fact.call().receiverType() : null;
        return receiverType == null ? null : receiverType.getTypeName();
    }

    /// Lexically enclosing class of the cursor node (via the nearest recorded scope). Used for
    /// implicit-`this` bare bindings that carry no receiver type.
    private static @Nullable ClassDef enclosingClass(@NotNull ModuleAnalysisSnapshot snapshot, @NotNull CursorFact fact) {
        var scope = nearestScope(snapshot, fact.unitIndex(), fact.node());
        return scope == null ? null : scope.owningClassOrNull();
    }

    /// Declaring class of a normalized GDCC declaration node: the scope recorded at (or above)
    /// the declaration site owns the lexical class chain.
    private static @Nullable String declaringClassName(@NotNull ModuleAnalysisSnapshot snapshot, @NotNull Node declarationNode) {
        var unitIndex = snapshot.astIndex().unitOf(declarationNode);
        if (unitIndex == null) {
            return null;
        }
        var scope = nearestScope(snapshot, unitIndex, declarationNode);
        var owner = scope == null ? null : scope.owningClassOrNull();
        return owner == null ? null : owner.getName();
    }

    /// Innermost scope recorded at or above `node` (parent-index fallback for scope-less
    /// leaves). Package-private: shared with the completion service inside this package.
    static @Nullable Scope nearestScope(
            @NotNull ModuleAnalysisSnapshot snapshot,
            @Nullable AstUnitIndex unitIndex,
            @NotNull Node node
    ) {
        var current = node;
        while (current != null) {
            var scope = snapshot.analysisData().scopesByAst().get(current);
            if (scope != null) {
                // Container-held models are only reachable through mutator-free views.
                return ReadOnlyScope.wrap(scope);
            }
            current = unitIndex == null ? null : unitIndex.parentOf(current);
        }
        return null;
    }

    /// First engine ancestor class for engine-only hierarchy lookups (constants / enum values /
    /// signals): bare bindings resolve through the lexically enclosing class, which may itself
    /// be GDCC — the engine lookups must start from the first native ancestor. Returns `null`
    /// when no engine class is reachable.
    private static @Nullable ExtensionGdClass requireEngineStartClass(
            @NotNull ModuleAnalysisSnapshot snapshot,
            @NotNull CursorFact fact
    ) {
        ClassDef start = null;
        var receiverName = receiverTypeName(fact);
        if (receiverName != null) {
            start = snapshot.classRegistry().resolveClassDefByName(receiverName);
        }
        if (start == null) {
            start = enclosingClass(snapshot, fact);
        }
        while (start != null && !(start instanceof ExtensionGdClass)) {
            start = snapshot.classRegistry().resolveSuperclass(start);
        }
        return (ExtensionGdClass) start;
    }

    /// The published fact at one cursor node plus the snapshot/unit context owner-recovery
    /// helpers need. `declarationSite()`/`memberName()` prefer the bare binding, then the
    /// member, then the call — matching the disjoint key spaces.
    private record CursorFact(
            @NotNull ModuleAnalysisSnapshot snapshot,
            @NotNull Node node,
            @Nullable FrontendBinding binding,
            @Nullable FrontendResolvedMember member,
            @Nullable FrontendResolvedCall call
    ) {
        private @Nullable AstUnitIndex unitIndex() {
            return snapshot.astIndex().unitOf(node);
        }

        private @Nullable Object declarationSite() {
            if (binding != null && binding.declarationSite() != null) {
                return binding.declarationSite();
            }
            if (member != null && member.declarationSite() != null) {
                return member.declarationSite();
            }
            return call == null ? null : call.declarationSite();
        }

        private @Nullable ScopeOwnerKind ownerKind() {
            if (member != null) {
                return member.ownerKind();
            }
            return call == null ? null : call.ownerKind();
        }

        private @NotNull String memberName() {
            if (binding != null) {
                return binding.symbolName();
            }
            if (member != null) {
                return member.memberName();
            }
            return call == null ? "" : call.callableName();
        }
    }
}
