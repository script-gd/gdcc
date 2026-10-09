package gd.script.gdcc.api.analysis;

import dev.superice.gdparser.frontend.ast.AttributeCallStep;
import dev.superice.gdparser.frontend.ast.AttributePropertyStep;
import dev.superice.gdparser.frontend.ast.CallExpression;
import dev.superice.gdparser.frontend.ast.ClassDeclaration;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.Range;
import dev.superice.gdparser.infra.treesitter.CompletionContext;
import dev.superice.gdparser.infra.treesitter.GdParserFacade;
import gd.script.gdcc.frontend.sema.FrontendBinding;
import gd.script.gdcc.frontend.sema.FrontendBindingKind;
import gd.script.gdcc.frontend.sema.FrontendMemberResolutionStatus;
import gd.script.gdcc.frontend.sema.resolver.FrontendVisibleValueEnumerator;
import gd.script.gdcc.gdextension.ExtensionBuiltinClass;
import gd.script.gdcc.gdextension.ExtensionGdClass;
import gd.script.gdcc.gdextension.ExtensionGlobalEnum;
import gd.script.gdcc.scope.ClassDef;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.scope.FunctionDef;
import gd.script.gdcc.scope.GdScriptClassConstant;
import gd.script.gdcc.scope.GdScriptEnumConstant;
import gd.script.gdcc.scope.GdScriptEnumGroup;
import gd.script.gdcc.scope.ScopeValue;
import gd.script.gdcc.scope.ScopeValueKind;
import gd.script.gdcc.type.GdArrayType;
import gd.script.gdcc.type.GdDictionaryType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/// In-process completion candidate service over a published `ModuleAnalysisSnapshot`
/// (`frontend_lsp_foundation_implementation.md` §2.5). Static, pure-read counterpart of
/// `FrontendSnapshotQueryService`: same snapshot-first entry rule, same absent-result contract
/// (unknown paths, `parse.internal` units, skipped/error subtrees and fact-free receivers all
/// yield empty candidate lists — never throw, never resolve against another generation).
///
/// Generation-isolation contract: the gdparser completion parse runs over the SNAPSHOT'S OWN
/// stored source text (a stale snapshot therefore answers self-consistently from its own text)
/// and produces a NEW CST generation. Only the context `kind`, `replaceableRange` and
/// `receiverRange` are consumed; no completion-CST node ever touches snapshot side tables.
///
/// Minimal V1 ruleset: member access after `.` (instance surface for typed
/// receivers, static surface + constants/enums for class-name receivers), bare-identifier
/// prefix (scope-chain values with declaration-after-use filtering, enclosing-class methods,
/// global constants/enums, utility and language functions) and type positions (builtin/engine
/// type names, project global class names, file inner classes). `CALL_ARGUMENT` is classified
/// but yields no candidates. Prefix filtering and presentation ordering stay in the LSP layer.
public final class FrontendSnapshotCompletionService {
    /// One shared facade is safe: gdparser keeps no parse state on the instance (a fresh
    /// TSParser is created per parse call), so concurrent completion requests do not race.
    private static final GdParserFacade COMPLETION_FACADE = GdParserFacade.withDefaultLanguage();

    private FrontendSnapshotCompletionService() {
    }

    /// Completion candidates for the cursor at `byteOffset` (UTF-8 byte into the unit source).
    /// Argument policy: a negative offset is a programming error and throws
    /// `IllegalArgumentException`; an offset past the source end is an absent result (empty
    /// `UNKNOWN`) mirroring the row/column overload's out-of-range handling.
    public static @NotNull CompletionLookupResult completionCandidatesAt(
            @NotNull ModuleAnalysisSnapshot snapshot,
            @NotNull String displayPath,
            int byteOffset
    ) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        Objects.requireNonNull(displayPath, "displayPath must not be null");
        if (byteOffset < 0) {
            throw new IllegalArgumentException("byteOffset must be >= 0, got " + byteOffset);
        }
        var view = sourceView(snapshot, displayPath);
        var unitIndex = snapshot.astIndex().forDisplayPath(displayPath);
        if (view == null || unitIndex == null || byteOffset > unitIndex.totalBytes()) {
            return new CompletionLookupResult(CompletionContextKind.UNKNOWN, null, List.of());
        }
        // New CST generation: kind + ranges only, never node identity (see class contract).
        var context = COMPLETION_FACADE.parseCompletionContext(view.source(), byteOffset);
        var contextKind = mapKind(context.kind());
        var replaceableRange = sanitizedReplaceableRange(unitIndex, context, byteOffset);
        if (view.parseFailed()) {
            // Failed units publish no facts; still report the context range so the LSP layer
            // can position its (empty) completion request correctly.
            return new CompletionLookupResult(contextKind, replaceableRange, List.of());
        }
        var cursorNode = locateCursorNode(unitIndex, byteOffset, context);
        if (cursorNode != null && isInsideSkippedSubtree(snapshot, unitIndex, cursorNode)) {
            // Skipped/error subtrees carry no facts. Unfinished member-access partial chains
            // are deliberately never marked skipped, so the dot path is unaffected.
            return new CompletionLookupResult(contextKind, replaceableRange, List.of());
        }
        var candidates = switch (contextKind) {
            case MEMBER_ACCESS -> memberAccessCandidates(snapshot, unitIndex, context, view.source());
            case IDENTIFIER -> identifierCandidates(snapshot, unitIndex, context, byteOffset, cursorNode);
            case TYPE_POSITION -> typePositionCandidates(snapshot, unitIndex, cursorNode);
            case CALL_ARGUMENT, UNKNOWN -> List.<CompletionCandidate>of();
        };
        return new CompletionLookupResult(contextKind, replaceableRange, candidates);
    }

    /// (0-based row, 0-based UTF-8 byte column) overload; an out-of-range coordinate yields the
    /// empty `UNKNOWN` result rather than leaking into a neighbouring line.
    public static @NotNull CompletionLookupResult completionCandidatesAt(
            @NotNull ModuleAnalysisSnapshot snapshot,
            @NotNull String displayPath,
            int row0,
            int column0
    ) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        Objects.requireNonNull(displayPath, "displayPath must not be null");
        var unitIndex = snapshot.astIndex().forDisplayPath(displayPath);
        if (unitIndex == null) {
            return new CompletionLookupResult(CompletionContextKind.UNKNOWN, null, List.of());
        }
        var byteOffset = unitIndex.byteOffsetAt(row0, column0);
        return byteOffset == null
                ? new CompletionLookupResult(CompletionContextKind.UNKNOWN, null, List.of())
                : completionCandidatesAt(snapshot, displayPath, byteOffset);
    }

    // ------------------------------------------------------------------
    // Cursor location and skipped-subtree gate
    // ------------------------------------------------------------------

    private static @Nullable ModuleAnalysisSnapshot.SourceView sourceView(
            @NotNull ModuleAnalysisSnapshot snapshot,
            @NotNull String displayPath
    ) {
        for (var view : snapshot.sourceViews()) {
            if (view.displayPath().equals(displayPath)) {
                return view;
            }
        }
        return null;
    }

    /// Node anchoring the cursor. A non-zero replaceable range that CONTAINS the cursor means
    /// the cursor sits on an identifier: anchor at its START byte because half-open coverage
    /// makes the cursor's own byte unreliable when it rests exactly at the identifier end.
    /// Falls back to the cursor byte and then the preceding byte for zero-width positions.
    private static @Nullable Node locateCursorNode(
            @NotNull AstUnitIndex unitIndex,
            int byteOffset,
            @NotNull CompletionContext context
    ) {
        Node node = null;
        var replaceable = context.replaceableRange();
        if (replaceable.startByte() != replaceable.endByte() && containsOffset(replaceable, byteOffset)) {
            node = unitIndex.nodeAt(replaceable.startByte());
        }
        if (node == null) {
            node = unitIndex.nodeAt(byteOffset);
        }
        if (node == null && byteOffset > 0) {
            node = unitIndex.nodeAt(byteOffset - 1);
        }
        return node;
    }

    /// The context's replaceable range, clamped to a zero-width range at the cursor when the
    /// reported range does not CONTAIN the cursor: gdparser's error recovery may attach the
    /// token after the cursor instead (e.g. the next statement's keyword when a line ends with
    /// `receiver.`), and such a range can never be the token being typed.
    private static @NotNull QuerySourceRange sanitizedReplaceableRange(
            @NotNull AstUnitIndex unitIndex,
            @NotNull CompletionContext context,
            int byteOffset
    ) {
        var replaceable = context.replaceableRange();
        return containsOffset(replaceable, byteOffset)
                ? unitIndex.toQueryRange(replaceable)
                : unitIndex.queryRangeAt(byteOffset, byteOffset);
    }

    /// Closed-interval containment: a cursor resting exactly at either range edge still counts
    /// as typing inside the token.
    private static boolean containsOffset(@NotNull Range range, int byteOffset) {
        return range.startByte() <= byteOffset && byteOffset <= range.endByte();
    }

    /// Whether `node` or any ancestor is a recorded skipped/error subtree root.
    private static boolean isInsideSkippedSubtree(
            @NotNull ModuleAnalysisSnapshot snapshot,
            @NotNull AstUnitIndex unitIndex,
            @NotNull Node node
    ) {
        var current = node;
        while (current != null) {
            if (snapshot.analysisData().skippedSubtreeRoots().containsKey(current)) {
                return true;
            }
            current = unitIndex.parentOf(current);
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Member access: receiver resolution + member enumeration
    // ------------------------------------------------------------------

    /// Resolves the receiverRange against the SNAPSHOT AST (same generation, same text): among
    /// the nodes ending exactly at the receiver end, the deepest one carrying a usable fact
    /// wins — a `TYPE_META`/`SINGLETON` binding selects the static/class surface, a `SUPER`
    /// binding selects the lexical superclass's method surface, an enum-group declaration (via
    /// binding or resolved-member fact) selects the group's values, and a published expression
    /// type selects the instance surface. No fact anywhere means the receiver was swallowed by
    /// an error structure or is dynamically typed: empty candidates (acceptance 2).
    private static @NotNull List<CompletionCandidate> memberAccessCandidates(
            @NotNull ModuleAnalysisSnapshot snapshot,
            @NotNull AstUnitIndex unitIndex,
            @NotNull CompletionContext context,
            @NotNull String source
    ) {
        var receiverRange = context.receiverRange();
        if (receiverRange == null || receiverRange.startByte() == receiverRange.endByte()) {
            return List.of();
        }
        // gdparser's AST drops parentheses, so a parenthesized/cast receiver's inner expression
        // ends BEFORE the trailing `)` characters. Peel ONE wrapping paren at a time and retry
        // the exact-end match: call parentheses are preserved (the call node matches first),
        // and nested wraps resolve layer by layer instead of stripping into call arguments.
        var sourceBytes = source.getBytes(StandardCharsets.UTF_8);
        var end = receiverRange.endByte();
        while (end > receiverRange.startByte()) {
            var resolved = resolveReceiverAtEnd(snapshot, unitIndex, receiverRange, end);
            if (resolved != null) {
                return resolved;
            }
            var scan = skipWhitespaceBack(sourceBytes, receiverRange.startByte(), end);
            if (scan <= receiverRange.startByte() || sourceBytes[scan - 1] != ')') {
                return List.of();
            }
            end = skipWhitespaceBack(sourceBytes, receiverRange.startByte(), scan - 1);
        }
        return List.of();
    }

    /// Largest index <= `end` such that all bytes in `[index, end)` are ASCII whitespace.
    private static int skipWhitespaceBack(byte[] sourceBytes, int lowerBound, int end) {
        var scan = end;
        while (scan > lowerBound && isAsciiWhitespace(sourceBytes[scan - 1])) {
            scan--;
        }
        return scan;
    }

    private static boolean isAsciiWhitespace(byte value) {
        return value == ' ' || value == '\t' || value == '\n' || value == '\r';
    }

    /// The deepest-fact walk for one candidate receiver end: `null` when no node ends exactly
    /// at `receiverEnd` inside the receiver range, otherwise the matched surface's candidates.
    private static @Nullable List<CompletionCandidate> resolveReceiverAtEnd(
            @NotNull ModuleAnalysisSnapshot snapshot,
            @NotNull AstUnitIndex unitIndex,
            @NotNull Range receiverRange,
            int receiverEnd
    ) {
        var analysisData = snapshot.analysisData();
        var node = unitIndex.nodeAt(receiverEnd - 1);
        while (node != null) {
            var range = node.range();
            if (range.endByte() == receiverEnd && range.startByte() >= receiverRange.startByte()) {
                var binding = analysisData.symbolBindings().get(node);
                // The SUPER branch accepts the keyword-position binding or the bare `super`
                // identifier shape (gdparser represents `super` as an IdentifierExpression).
                if (isSuperReceiver(node, binding)) {
                    return superMethodCandidates(snapshot, unitIndex, node);
                }
                if (binding != null) {
                    if (binding.kind() == FrontendBindingKind.TYPE_META) {
                        return staticReceiverCandidates(snapshot, binding.declarationSite());
                    }
                    if (binding.kind() == FrontendBindingKind.SINGLETON
                            && binding.declarationSite() instanceof ClassDef singletonClass) {
                        return instanceMembersOfClass(snapshot, singletonClass);
                    }
                    // Bare enum-group references bind as CONSTANT/GLOBAL_ENUM values whose
                    // declaration (possibly script-constant wrapped) is the group itself:
                    // complete the group's values, not the pseudo-type's member surface.
                    var declaration = unwrapDeclaration(binding.declarationSite());
                    if (declaration instanceof ExtensionGlobalEnum || declaration instanceof GdScriptEnumGroup) {
                        return staticReceiverCandidates(snapshot, declaration);
                    }
                }
                // Qualified enum-group receivers (`CompStaticUser.State`) carry the group
                // declaration on the property step's RESOLVED member fact. Subscript steps are
                // excluded: `State["IDLE"]` carries the group only as container provenance and
                // must complete its subscript result type instead.
                if (node instanceof AttributePropertyStep) {
                    var memberFact = analysisData.resolvedMembers().get(node);
                    if (memberFact != null && memberFact.status() == FrontendMemberResolutionStatus.RESOLVED) {
                        var memberDeclaration = unwrapDeclaration(memberFact.declarationSite());
                        if (memberDeclaration instanceof ExtensionGlobalEnum || memberDeclaration instanceof GdScriptEnumGroup) {
                            return staticReceiverCandidates(snapshot, memberDeclaration);
                        }
                    }
                }
                var expressionType = analysisData.expressionTypes().get(node);
                if (expressionType != null && expressionType.publishedType() != null) {
                    return instanceMembersOfType(snapshot, expressionType.publishedType());
                }
                // A matched call node without a usable result type is TERMINAL: the receiver
                // is syntactically identified (failed/untyped call), so peeling further would
                // wrongly resolve into a call argument instead of answering empty (acceptance
                // 2's fact-free receiver form).
                if (node instanceof CallExpression || node instanceof AttributeCallStep) {
                    return List.of();
                }
            }
            node = unitIndex.parentOf(node);
        }
        return null;
    }

    private static boolean isSuperReceiver(@NotNull Node node, @Nullable FrontendBinding binding) {
        if (binding != null && binding.kind() == FrontendBindingKind.SUPER) {
            return true;
        }
        return node instanceof IdentifierExpression identifier && identifier.name().equals("super");
    }

    /// `super.` method surface: the super contract supports method calls starting at the
    /// lexical superclass and rejects property/subscript access, so only superclass-hierarchy
    /// methods are offered (the published `super` expression type deliberately denotes the
    /// CURRENT class for lowering and must not drive member enumeration).
    private static @NotNull List<CompletionCandidate> superMethodCandidates(
            @NotNull ModuleAnalysisSnapshot snapshot,
            @NotNull AstUnitIndex unitIndex,
            @NotNull Node superNode
    ) {
        var scope = FrontendSnapshotQueryService.nearestScope(snapshot, unitIndex, superNode);
        var owningClass = scope == null ? null : scope.owningClassOrNull();
        var superClass = owningClass == null ? null : snapshot.classRegistry().resolveSuperclass(owningClass);
        if (superClass == null) {
            return List.of();
        }
        var out = new LinkedHashMap<String, CompletionCandidate>();
        var visited = new HashSet<String>();
        var claimedNames = new HashSet<String>();
        var current = superClass;
        while (current != null && visited.add(current.getName())) {
            // Nearest declaring owner wins per method name (ScopeMethodResolver parity); all
            // permitted overloads of the winning level survive.
            for (var function : current.getFunctions()) {
                if (!function.isStatic() && !claimedNames.contains(function.getName())) {
                    putIfAbsent(out, methodKey(function), new CompletionCandidate(
                            function.getName(), CompletionCandidateKind.METHOD, null, renderSignature(function)));
                }
            }
            for (var function : current.getFunctions()) {
                claimedNames.add(function.getName());
            }
            current = snapshot.classRegistry().resolveSuperclass(current);
        }
        return List.copyOf(out.values());
    }

    /// Static surface of a class-name receiver: static properties/functions only (members
    /// without the static modifier are never listed) plus the constant surface
    /// (script constants, engine/builtin class constants and enum groups with their values).
    /// Enum-group receivers (global enums, GDCC named enums) list their values.
    private static @NotNull List<CompletionCandidate> staticReceiverCandidates(
            @NotNull ModuleAnalysisSnapshot snapshot,
            @Nullable Object declarationSite
    ) {
        switch (declarationSite) {
            case ClassDef classDef -> {
                var out = new LinkedHashMap<String, CompletionCandidate>();
                if (classDef instanceof ExtensionBuiltinClass builtin) {
                    collectOwnMembers(builtin, true, out);
                    collectConstantSurface(builtin, out, Set.of());
                    return List.copyOf(out.values());
                }
                // Terminal shadowing mirrors the static resolver: a subclass member claims its
                // name regardless of the static modifier, so an ancestor's same-named static
                // member must not leak through a subclass's instance declaration.
                var shadowedNames = new HashSet<String>();
                var visited = new HashSet<String>();
                var current = classDef;
                while (current != null && visited.add(current.getName())) {
                    collectOwnMembers(current, true, out, shadowedNames);
                    collectConstantSurface(current, out, shadowedNames);
                    claimMemberNames(current, shadowedNames);
                    current = snapshot.classRegistry().resolveSuperclass(current);
                }
                return List.copyOf(out.values());
            }
            case ExtensionGlobalEnum globalEnum -> {
                var out = new ArrayList<CompletionCandidate>();
                for (var value : globalEnum.values()) {
                    out.add(new CompletionCandidate(value.name(), CompletionCandidateKind.VALUE, "int", null));
                }
                return List.copyOf(out);
            }
            case GdScriptEnumGroup enumGroup -> {
                var out = new ArrayList<CompletionCandidate>();
                for (var member : enumGroup.members()) {
                    out.add(new CompletionCandidate(member.memberName(), CompletionCandidateKind.VALUE, "int", null));
                }
                return List.copyOf(out);
            }
            case null, default -> {
                // Synthesized metas (e.g. `Array[String]`) carry no class declaration.
                return List.of();
            }
        }
    }

    private static @NotNull List<CompletionCandidate> instanceMembersOfType(
            @NotNull ModuleAnalysisSnapshot snapshot,
            @NotNull GdType type
    ) {
        if (type instanceof GdObjectType objectType) {
            var classDef = snapshot.classRegistry().getClassDef(objectType);
            if (classDef == null) {
                classDef = snapshot.classRegistry().resolveClassDefByName(objectType.className());
            }
            return classDef == null ? List.of() : instanceMembersOfClass(snapshot, classDef);
        }
        // Container display names are parameterized (`Array[int]`); the registry keys builtin
        // classes by their raw family name instead (mirroring the method resolver).
        var builtinName = switch (type) {
            case GdArrayType _ -> "Array";
            case GdDictionaryType _ -> "Dictionary";
            default -> type.getTypeName();
        };
        var builtin = snapshot.classRegistry().findBuiltinClass(builtinName);
        return builtin == null ? List.of() : instanceMembersOfClass(snapshot, builtin);
    }

    /// Instance surface: non-static properties/methods plus signals, walking the superclass
    /// chain (subclass members shadow same-key ancestors; builtin classes have no hierarchy).
    /// Constants stay on the static surface — they are class-scope names, not instance members.
    private static @NotNull List<CompletionCandidate> instanceMembersOfClass(
            @NotNull ModuleAnalysisSnapshot snapshot,
            @NotNull ClassDef start
    ) {
        var out = new LinkedHashMap<String, CompletionCandidate>();
        if (start instanceof ExtensionBuiltinClass builtin) {
            collectOwnMembers(builtin, false, out);
            return List.copyOf(out.values());
        }
        var visited = new HashSet<String>();
        var current = start;
        while (current != null && visited.add(current.getName())) {
            collectOwnMembers(current, false, out);
            current = snapshot.classRegistry().resolveSuperclass(current);
        }
        return List.copyOf(out.values());
    }

    /// One class's own property/method (+signal) candidates for the INSTANCE surface.
    /// Overload sets survive via the signature key while exact overrides are shadowed by the
    /// subclass through the insertion-order `putIfAbsent`.
    private static void collectOwnMembers(
            @NotNull ClassDef classDef,
            boolean staticMembers,
            @NotNull Map<String, CompletionCandidate> out
    ) {
        collectOwnMembers(classDef, staticMembers, out, Set.of());
    }

    /// Static-path variant skipping names already claimed by a subclass (terminal shadowing).
    private static void collectOwnMembers(
            @NotNull ClassDef classDef,
            boolean staticMembers,
            @NotNull Map<String, CompletionCandidate> out,
            @NotNull Set<String> shadowedNames
    ) {
        for (var property : classDef.getProperties()) {
            if (property.isStatic() == staticMembers && !shadowedNames.contains(property.getName())) {
                putIfAbsent(out, "P:" + property.getName(), new CompletionCandidate(
                        property.getName(), CompletionCandidateKind.PROPERTY,
                        property.getType().getTypeName(), null));
            }
        }
        for (var function : classDef.getFunctions()) {
            if (function.isStatic() == staticMembers && !shadowedNames.contains(function.getName())) {
                putIfAbsent(out, methodKey(function), new CompletionCandidate(
                        function.getName(), CompletionCandidateKind.METHOD, null, renderSignature(function)));
            }
        }
        if (!staticMembers) {
            for (var signal : classDef.getSignals()) {
                if (!shadowedNames.contains(signal.getName())) {
                    putIfAbsent(out, "V:" + signal.getName(), new CompletionCandidate(
                            signal.getName(), CompletionCandidateKind.VALUE, null, null));
                }
            }
        }
    }

    /// Claims every property/function/script-constant name of one hierarchy level so ancestor
    /// members of the same name stay hidden, regardless of the static modifier.
    private static void claimMemberNames(@NotNull ClassDef classDef, @NotNull Set<String> shadowedNames) {
        for (var property : classDef.getProperties()) {
            shadowedNames.add(property.getName());
        }
        for (var function : classDef.getFunctions()) {
            shadowedNames.add(function.getName());
        }
        for (var scriptConstant : classDef.getScriptConstants()) {
            shadowedNames.add(scriptConstant.name());
        }
    }

    /// Constant surface for class-name receivers: GDCC script constants (enum values/groups),
    /// engine class constants/enums and builtin class constants/enums. Enum values are always
    /// `int`; metadata constants carry no reliable type text and stay untyped. Names claimed by
    /// a subclass stay hidden on the hierarchy walk.
    private static void collectConstantSurface(
            @NotNull ClassDef classDef,
            @NotNull Map<String, CompletionCandidate> out,
            @NotNull Set<String> shadowedNames
    ) {
        for (var scriptConstant : classDef.getScriptConstants()) {
            if (shadowedNames.contains(scriptConstant.name())) {
                continue;
            }
            switch (scriptConstant.declaration()) {
                case GdScriptEnumConstant _ -> putIfAbsent(out, "V:" + scriptConstant.name(),
                        new CompletionCandidate(scriptConstant.name(), CompletionCandidateKind.VALUE, "int", null));
                case GdScriptEnumGroup group -> {
                    putIfAbsent(out, "V:" + group.name(),
                            new CompletionCandidate(group.name(), CompletionCandidateKind.VALUE, null, null));
                    for (var member : group.members()) {
                        putIfAbsent(out, "V:" + member.memberName(), new CompletionCandidate(
                                member.memberName(), CompletionCandidateKind.VALUE, "int", null));
                    }
                }
                default -> putIfAbsent(out, "V:" + scriptConstant.name(), new CompletionCandidate(
                        scriptConstant.name(), CompletionCandidateKind.VALUE,
                        scriptConstant.type().getTypeName(), null));
            }
        }
        if (classDef instanceof ExtensionGdClass engineClass) {
            for (var constant : engineClass.constants()) {
                putConstantCandidate(out, constant.name(), shadowedNames);
            }
            for (var classEnum : engineClass.enums()) {
                putEnumGroupCandidate(out, classEnum.name(), shadowedNames);
                for (var value : classEnum.values()) {
                    putEnumValueCandidate(out, value.name(), shadowedNames);
                }
            }
        }
        if (classDef instanceof ExtensionBuiltinClass builtinClass) {
            for (var constant : builtinClass.constants()) {
                putConstantCandidate(out, constant.name(), shadowedNames);
            }
            for (var classEnum : builtinClass.enums()) {
                putEnumGroupCandidate(out, classEnum.name(), shadowedNames);
                for (var value : classEnum.values()) {
                    putEnumValueCandidate(out, value.name(), shadowedNames);
                }
            }
        }
    }

    private static void putConstantCandidate(
            @NotNull Map<String, CompletionCandidate> out,
            @NotNull String name,
            @NotNull Set<String> shadowedNames
    ) {
        if (!shadowedNames.contains(name)) {
            putIfAbsent(out, "V:" + name, new CompletionCandidate(name, CompletionCandidateKind.VALUE, null, null));
        }
    }

    private static void putEnumGroupCandidate(
            @NotNull Map<String, CompletionCandidate> out,
            @NotNull String name,
            @NotNull Set<String> shadowedNames
    ) {
        if (!shadowedNames.contains(name)) {
            putIfAbsent(out, "V:" + name, new CompletionCandidate(name, CompletionCandidateKind.VALUE, null, null));
        }
    }

    private static void putEnumValueCandidate(
            @NotNull Map<String, CompletionCandidate> out,
            @NotNull String name,
            @NotNull Set<String> shadowedNames
    ) {
        if (!shadowedNames.contains(name)) {
            putIfAbsent(out, "V:" + name, new CompletionCandidate(name, CompletionCandidateKind.VALUE, "int", null));
        }
    }

    // ------------------------------------------------------------------
    // Identifier prefix: scope-chain values + class methods + globals
    // ------------------------------------------------------------------

    /// Bare-identifier candidates: visible scope values (nearest first, declaration-after-use
    /// already applied by the enumerator), the enclosing class hierarchy's methods, then the
    /// global namespace (constants, enums and their values, language constants, utility and
    /// language functions). Name claiming mirrors scope shadowing: a visible value or class
    /// method blocks the same-named global candidate, and overloads survive only within the
    /// winning function source.
    private static @NotNull List<CompletionCandidate> identifierCandidates(
            @NotNull ModuleAnalysisSnapshot snapshot,
            @NotNull AstUnitIndex unitIndex,
            @NotNull CompletionContext context,
            int byteOffset,
            @Nullable Node cursorNode
    ) {
        var out = new LinkedHashMap<String, CompletionCandidate>();
        var claimedValueNames = new HashSet<String>();
        var claimedMethodNames = new HashSet<String>();
        if (cursorNode != null) {
            var scope = FrontendSnapshotQueryService.nearestScope(snapshot, unitIndex, cursorNode);
            if (scope != null) {
                // The declaration-after-use pivot is the cursor byte itself: on an identifier
                // the prefix start (declarations ending inside the prefix cannot exist), else
                // the raw cursor offset.
                var replaceable = context.replaceableRange();
                var useSiteOffset = replaceable.startByte() != replaceable.endByte()
                        && containsOffset(replaceable, byteOffset)
                        ? replaceable.startByte()
                        : byteOffset;
                for (var value : FrontendVisibleValueEnumerator.enumerateVisibleValues(scope, useSiteOffset)) {
                    var candidate = valueCandidate(value);
                    putIfAbsent(out, valueKey(value), candidate);
                    claimedValueNames.add(value.name());
                }
                var owningClass = scope.owningClassOrNull();
                if (owningClass != null) {
                    collectClassMethods(snapshot, owningClass, out, claimedMethodNames);
                }
            }
        }
        collectGlobalNamespace(snapshot.classRegistry(), out, claimedValueNames, claimedMethodNames);
        return List.copyOf(out.values());
    }

    /// All methods of the enclosing class hierarchy as identifier candidates (bare method
    /// references/calls resolve without a receiver). Every encountered name is claimed — bare
    /// function lookup stops at the first scope contributing a name, so a global function with
    /// a claimed name is never a fallback overload. Static-context filtering is deliberately
    /// not modelled in V1.
    private static void collectClassMethods(
            @NotNull ModuleAnalysisSnapshot snapshot,
            @NotNull ClassDef start,
            @NotNull Map<String, CompletionCandidate> out,
            @NotNull Set<String> claimedMethodNames
    ) {
        var visited = new HashSet<String>();
        var current = start;
        while (current != null && visited.add(current.getName())) {
            // Nearest declaring owner wins per method name (ClassScope.resolveFunctionsHere
            // parity); all permitted overloads of the winning level survive, and the level's
            // names are claimed before advancing so ancestors contribute no hidden overloads.
            for (var function : current.getFunctions()) {
                if (!claimedMethodNames.contains(function.getName())) {
                    putIfAbsent(out, methodKey(function), new CompletionCandidate(
                            function.getName(), CompletionCandidateKind.METHOD, null, renderSignature(function)));
                }
            }
            for (var function : current.getFunctions()) {
                claimedMethodNames.add(function.getName());
            }
            current = snapshot.classRegistry().resolveSuperclass(current);
        }
    }

    private static void collectGlobalNamespace(
            @NotNull ClassRegistry registry,
            @NotNull Map<String, CompletionCandidate> out,
            @NotNull Set<String> claimedValueNames,
            @NotNull Set<String> claimedMethodNames
    ) {
        for (var constant : registry.getGlobalConstantList()) {
            if (claimedValueNames.add(constant.name())) {
                putIfAbsent(out, "V:" + constant.name(),
                        new CompletionCandidate(constant.name(), CompletionCandidateKind.VALUE, "int", null));
            }
        }
        for (var globalEnum : registry.getGlobalEnumList()) {
            if (claimedValueNames.add(globalEnum.name())) {
                putIfAbsent(out, "V:" + globalEnum.name(),
                        new CompletionCandidate(globalEnum.name(), CompletionCandidateKind.VALUE, null, null));
            }
            for (var value : globalEnum.values()) {
                if (claimedValueNames.add(value.name())) {
                    putIfAbsent(out, "V:" + value.name(),
                            new CompletionCandidate(value.name(), CompletionCandidateKind.VALUE, "int", null));
                }
            }
        }
        for (var languageConstant : registry.getGdScriptLanguageConstantList()) {
            if (claimedValueNames.add(languageConstant.name())) {
                putIfAbsent(out, "V:" + languageConstant.name(),
                        new CompletionCandidate(languageConstant.name(), CompletionCandidateKind.VALUE, "float", null));
            }
        }
        for (var utility : registry.getExtensionUtilityFunctionList()) {
            if (claimedMethodNames.add(utility.getName())) {
                putIfAbsent(out, methodKey(utility), new CompletionCandidate(
                        utility.getName(), CompletionCandidateKind.METHOD, null, renderSignature(utility)));
            }
        }
        for (var languageFunction : registry.getGdScriptLanguageFunctionList()) {
            if (claimedMethodNames.add(languageFunction.getName())) {
                putIfAbsent(out, methodKey(languageFunction), new CompletionCandidate(
                        languageFunction.getName(), CompletionCandidateKind.METHOD, null,
                        renderSignature(languageFunction)));
            }
        }
    }

    // ------------------------------------------------------------------
    // Type position: builtin/engine type names + project classes + inner classes
    // ------------------------------------------------------------------

    /// Type-position candidates: builtin and engine class names from the registry, the project
    /// global class-name mapping (source-facing names), and the current file's inner classes
    /// (direct file-level inners plus the direct inners of any lexically enclosing inner class,
    /// matching GDScript's lexical visibility).
    private static @NotNull List<CompletionCandidate> typePositionCandidates(
            @NotNull ModuleAnalysisSnapshot snapshot,
            @NotNull AstUnitIndex unitIndex,
            @Nullable Node cursorNode
    ) {
        var out = new LinkedHashMap<String, CompletionCandidate>();
        var registry = snapshot.classRegistry();
        for (var builtin : registry.getExtensionBuiltinClassList()) {
            putIfAbsent(out, "T:" + builtin.getName(),
                    new CompletionCandidate(builtin.getName(), CompletionCandidateKind.TYPE, null, null));
        }
        for (var engineClass : registry.getExtensionGdClassList()) {
            putIfAbsent(out, "T:" + engineClass.getName(),
                    new CompletionCandidate(engineClass.getName(), CompletionCandidateKind.TYPE, null, null));
        }
        for (var sourceName : snapshot.topLevelCanonicalNameMap().keySet()) {
            putIfAbsent(out, "T:" + sourceName,
                    new CompletionCandidate(sourceName, CompletionCandidateKind.TYPE, null, null));
        }
        var preorder = unitIndex.preorderNodes();
        if (!preorder.isEmpty()) {
            collectDirectInnerClasses(preorder.getFirst(), out);
            var current = cursorNode;
            while (current != null) {
                if (current instanceof ClassDeclaration) {
                    collectDirectInnerClasses(current, out);
                }
                current = unitIndex.parentOf(current);
            }
        }
        return List.copyOf(out.values());
    }

    /// Inner classes lexically contained in `container`: a file root lists its direct
    /// statements, an inner class lists the statements of its body block.
    private static void collectDirectInnerClasses(
            @NotNull Node container,
            @NotNull Map<String, CompletionCandidate> out
    ) {
        var statements = container instanceof ClassDeclaration classDeclaration
                ? classDeclaration.body().getChildren()
                : container.getChildren();
        for (var statement : statements) {
            if (statement instanceof ClassDeclaration innerClass) {
                putIfAbsent(out, "T:" + innerClass.name(), new CompletionCandidate(
                        innerClass.name(), CompletionCandidateKind.TYPE, null, null));
            }
        }
    }

    // ------------------------------------------------------------------
    // Candidate rendering helpers
    // ------------------------------------------------------------------

    private static @NotNull CompletionContextKind mapKind(CompletionContext.@NotNull Kind kind) {
        return switch (kind) {
            case MEMBER_ACCESS -> CompletionContextKind.MEMBER_ACCESS;
            case IDENTIFIER -> CompletionContextKind.IDENTIFIER;
            case TYPE_POSITION -> CompletionContextKind.TYPE_POSITION;
            case CALL_ARGUMENT -> CompletionContextKind.CALL_ARGUMENT;
            case UNKNOWN -> CompletionContextKind.UNKNOWN;
        };
    }

    /// Unwraps the script-constant table wrapper so enum declarations classify by the enum
    /// fact they carry (mirroring the declaration normalizer's treatment).
    private static @Nullable Object unwrapDeclaration(@Nullable Object declarationSite) {
        return declarationSite instanceof GdScriptClassConstant scriptConstant
                ? scriptConstant.declaration()
                : declarationSite;
    }

    private static @NotNull String valueKey(@NotNull ScopeValue value) {
        return (value.kind() == ScopeValueKind.PROPERTY ? "P:" : "V:") + value.name();
    }

    private static @NotNull CompletionCandidate valueCandidate(@NotNull ScopeValue value) {
        var kind = switch (value.kind()) {
            case PROPERTY -> CompletionCandidateKind.PROPERTY;
            case TYPE_META -> CompletionCandidateKind.TYPE;
            default -> CompletionCandidateKind.VALUE;
        };
        return new CompletionCandidate(value.name(), kind, value.type().getTypeName(), null);
    }

    /// Dedup key keeping overloads apart (name + parameter type list + vararg flag) so exact
    /// overrides shadow while distinct signatures coexist.
    private static @NotNull String methodKey(@NotNull FunctionDef function) {
        var key = new StringBuilder("M:").append(function.getName()).append('(');
        for (var parameter : function.getParameters()) {
            key.append(parameter.getType().getTypeName()).append(',');
        }
        return key.append(')').append(function.isVararg() ? ":vararg" : ":fixed").toString();
    }

    /// `name(param: Type, ...) -> Return` display text; parameter default rendering is not
    /// modelled (the metadata default-provider name is not display text).
    private static @NotNull String renderSignature(@NotNull FunctionDef function) {
        var signature = new StringBuilder(function.getName()).append('(');
        var parameters = function.getParameters();
        for (var i = 0; i < parameters.size(); i++) {
            if (i > 0) {
                signature.append(", ");
            }
            signature.append(parameters.get(i).getName())
                    .append(": ")
                    .append(parameters.get(i).getType().getTypeName());
        }
        if (function.isVararg()) {
            signature.append(parameters.isEmpty() ? "..." : ", ...");
        }
        signature.append(')');
        signature.append(" -> ").append(function.getReturnType().getTypeName());
        return signature.toString();
    }

    private static void putIfAbsent(
            @NotNull Map<String, CompletionCandidate> out,
            @NotNull String key,
            @NotNull CompletionCandidate candidate
    ) {
        out.putIfAbsent(key, candidate);
    }
}
