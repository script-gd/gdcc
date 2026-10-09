package gd.script.gdcc.gdextension;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/// Computes the comma-joined union property type metadata mappings for one loaded
/// `ExtensionAPI` (see `ScopeTypeParsers.registerUnionTypeMetadataMappings`).
///
/// Godot's dump declares some engine properties with editor resource-picker hint strings in
/// their `type` field instead of a single runtime class. Two spelling families exist in the
/// wild (33 occurrences in the bundled 4.5.1 dump, all on `classes[].properties[].type`):
///
/// - plain unions `A,B[,C...]` (e.g. `CanvasItemMaterial,ShaderMaterial`): the runtime Variant
///   type is the nearest common ancestor (NCA) of all members — the picker hint is narrower
///   than runtime acceptance, so NCA widening is faithful to Godot behavior;
/// - base-with-exclusions `A,-B,-C...` (e.g. `Texture2D,-AnimatedTexture,...`): the excluded
///   subclasses only filter the editor picker; the first element is already the runtime type.
///
/// The computation needs only the dump's own `inherits` chains — no `ClassRegistry` — so it
/// can run at load time before any registry exists. Strings whose members cannot ALL be
/// resolved against the dump hierarchy are skipped (fail-closed): parsing them later keeps the
/// pre-existing strict failure, preserving observability of dump version drift.
public final class ExtensionUnionTypeMappings {
    private ExtensionUnionTypeMappings() {
    }

    /// Scans all engine and builtin property type metadata of `api` and returns the
    /// raw-union-string -> resolving-class-name mapping (insertion order: engine classes
    /// first, then builtin classes, each in dump order).
    public static @NotNull Map<String, String> compute(@NotNull ExtensionAPI api) {
        Objects.requireNonNull(api, "api must not be null");
        var parentByName = new HashMap<String, String>();
        for (var engineClass : api.classes()) {
            if (engineClass.name() != null) {
                parentByName.put(engineClass.name(), engineClass.inherits());
            }
        }
        var mappings = new LinkedHashMap<String, String>();
        for (var engineClass : api.classes()) {
            for (var property : engineClass.properties()) {
                collectMapping(property.type(), parentByName, Set.of(), mappings);
            }
        }
        var builtinNames = new HashSet<String>();
        for (var builtinClass : api.builtinClasses()) {
            if (builtinClass.name() != null) {
                builtinNames.add(builtinClass.name());
            }
        }
        for (var builtinClass : api.builtinClasses()) {
            for (var member : builtinClass.members()) {
                collectMapping(member.type(), parentByName, builtinNames, mappings);
            }
        }
        return mappings;
    }

    private static void collectMapping(
            @Nullable String rawType,
            @NotNull Map<String, String> parentByName,
            @NotNull Set<String> builtinNames,
            @NotNull Map<String, String> mappings
    ) {
        if (rawType == null || !rawType.contains(",") || mappings.containsKey(rawType.trim())) {
            return;
        }
        var resolvedName = resolveUnionType(rawType.trim(), parentByName, builtinNames);
        if (resolvedName != null) {
            mappings.put(rawType.trim(), resolvedName);
        }
    }

    /// Resolves one comma-joined union spelling to its runtime class name, or `null` when the
    /// union cannot be fully resolved against the dump hierarchy (fail-closed skip).
    private static @Nullable String resolveUnionType(
            @NotNull String unionType,
            @NotNull Map<String, String> parentByName,
            @NotNull Set<String> builtinNames
    ) {
        // Trailing/leading empty fragments must be kept (`split` with a negative limit) so
        // malformed metadata like `LeafB,` is detected instead of silently dropping the empty
        // fragment and resolving as a single-member union.
        var parts = unionType.split(",", -1);
        var members = new ArrayList<String>();
        var hasExclusions = false;
        for (var part : parts) {
            var member = part.trim();
            if (member.isEmpty()) {
                // Malformed metadata (empty fragment): fail-closed, keep the strict failure.
                return null;
            }
            if (member.startsWith("-")) {
                hasExclusions = true;
            } else {
                members.add(member);
            }
        }
        if (members.isEmpty()) {
            return null;
        }
        if (hasExclusions) {
            // Base-with-exclusions: the first element is the runtime type; excluded subclasses
            // only filter the editor picker. The base must itself be resolvable.
            var base = members.getFirst();
            return parentByName.containsKey(base) || builtinNames.contains(base) ? base : null;
        }
        return nearestCommonAncestor(members, parentByName);
    }

    /// Deepest class that is an ancestor of every member, walking the dump's `inherits`
    /// chains. Engine classes are single-inheritance with `Object` at the root, so a common
    /// ancestor always exists for known members; any unknown member fails the whole union.
    private static @Nullable String nearestCommonAncestor(
            @NotNull List<String> members,
            @NotNull Map<String, String> parentByName
    ) {
        var chains = new ArrayList<List<String>>();
        for (var member : members) {
            if (!parentByName.containsKey(member)) {
                // Unknown or non-engine (builtin/primitive) member: no hierarchy to walk.
                return null;
            }
            var chain = new ArrayList<String>();
            var seen = new HashSet<String>();
            for (var current = member; current != null && !current.isBlank(); current = parentByName.get(current)) {
                if (!seen.add(current)) {
                    // Inheritance cycle in malformed metadata: fail-closed.
                    return null;
                }
                chain.add(current);
            }
            chains.add(chain);
        }
        for (var candidate : chains.getFirst()) {
            if (chains.stream().allMatch(chain -> chain.contains(candidate))) {
                return candidate;
            }
        }
        return null;
    }
}
