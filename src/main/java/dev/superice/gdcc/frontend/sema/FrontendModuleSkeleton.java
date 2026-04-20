package dev.superice.gdcc.frontend.sema;

import dev.superice.gdcc.frontend.FrontendClassNameContract;
import dev.superice.gdcc.frontend.diagnostic.DiagnosticSnapshot;
import dev.superice.gdcc.lir.LirClassDef;
import dev.superice.gdcc.scope.ResolveRestriction;
import dev.superice.gdcc.scope.Scope;
import dev.superice.gdcc.scope.ScopeLookupResult;
import dev.superice.gdcc.scope.ScopeTypeMeta;
import dev.superice.gdcc.scope.resolver.ScopeTypeResolver;
import dev.superice.gdcc.type.GdType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/// Class skeleton extraction result for one frontend module.
public record FrontendModuleSkeleton(
        @NotNull String moduleName,
        @NotNull List<FrontendSourceClassRelation> sourceClassRelations,
        @NotNull Map<String, String> topLevelCanonicalNameMap,
        @NotNull DiagnosticSnapshot diagnostics
) {
    public FrontendModuleSkeleton {
        Objects.requireNonNull(moduleName, "moduleName must not be null");
        sourceClassRelations = List.copyOf(Objects.requireNonNull(sourceClassRelations, "sourceClassRelations must not be null"));
        topLevelCanonicalNameMap = FrontendClassNameContract.freezeTopLevelCanonicalNameMap(Objects.requireNonNull(
                topLevelCanonicalNameMap,
                "topLevelCanonicalNameMap must not be null"
        ));
        Objects.requireNonNull(diagnostics, "diagnostics must not be null");
    }

    /// Returns every class skeleton contributed by the module, including nested classes.
    public @NotNull List<LirClassDef> allClassDefs() {
        var classDefs = new ArrayList<LirClassDef>();
        for (var relation : sourceClassRelations) {
            classDefs.addAll(relation.allClassDefs());
        }
        return List.copyOf(classDefs);
    }

    public @Nullable String findMappedTopLevelCanonicalName(@NotNull String sourceName) {
        return findMappedTopLevelCanonicalName(sourceName, topLevelCanonicalNameMap);
    }

    public @NotNull String remapTopLevelCanonicalName(@NotNull String sourceName) {
        return remapTopLevelCanonicalName(sourceName, topLevelCanonicalNameMap);
    }

    /// Frontend source-facing type lookup must preserve lexical wins. We therefore retry the
    /// mapped top-level canonical name only after the original source spelling misses.
    public @NotNull ScopeLookupResult<ScopeTypeMeta> resolveSourceFacingTypeMeta(
            @NotNull Scope scope,
            @NotNull String sourceName,
            @NotNull ResolveRestriction restriction
    ) {
        return resolveSourceFacingTypeMeta(scope, sourceName, restriction, topLevelCanonicalNameMap);
    }

    /// Strict declared-type positions share the same rule as type-meta lookup:
    /// lexical/source lookup first, mapped top-level canonical retry second.
    public @Nullable GdType tryResolveSourceFacingDeclaredType(
            @NotNull Scope scope,
            @NotNull String typeText
    ) {
        return tryResolveSourceFacingDeclaredType(scope, typeText, topLevelCanonicalNameMap);
    }

    public static @Nullable String findMappedTopLevelCanonicalName(
            @NotNull String sourceName,
            @NotNull Map<String, String> topLevelCanonicalNameMap
    ) {
        Objects.requireNonNull(sourceName, "sourceName must not be null");
        Objects.requireNonNull(topLevelCanonicalNameMap, "topLevelCanonicalNameMap must not be null");
        return topLevelCanonicalNameMap.get(sourceName);
    }

    public static @NotNull String remapTopLevelCanonicalName(
            @NotNull String sourceName,
            @NotNull Map<String, String> topLevelCanonicalNameMap
    ) {
        Objects.requireNonNull(sourceName, "sourceName must not be null");
        Objects.requireNonNull(topLevelCanonicalNameMap, "topLevelCanonicalNameMap must not be null");
        return topLevelCanonicalNameMap.getOrDefault(sourceName, sourceName);
    }

    public static @NotNull ScopeLookupResult<ScopeTypeMeta> resolveSourceFacingTypeMeta(
            @NotNull Scope scope,
            @NotNull String sourceName,
            @NotNull ResolveRestriction restriction,
            @NotNull Map<String, String> topLevelCanonicalNameMap
    ) {
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(sourceName, "sourceName must not be null");
        Objects.requireNonNull(restriction, "restriction must not be null");
        Objects.requireNonNull(topLevelCanonicalNameMap, "topLevelCanonicalNameMap must not be null");

        var lexicalResult = scope.resolveTypeMeta(sourceName, restriction);
        if (lexicalResult.isFound()) {
            return lexicalResult;
        }
        var mappedCanonicalName = findMappedTopLevelCanonicalName(sourceName, topLevelCanonicalNameMap);
        if (mappedCanonicalName == null) {
            return lexicalResult;
        }
        return scope.resolveTypeMeta(mappedCanonicalName, restriction);
    }

    public static @Nullable GdType tryResolveSourceFacingDeclaredType(
            @NotNull Scope scope,
            @NotNull String typeText,
            @NotNull Map<String, String> topLevelCanonicalNameMap
    ) {
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(typeText, "typeText must not be null");
        Objects.requireNonNull(topLevelCanonicalNameMap, "topLevelCanonicalNameMap must not be null");
        return ScopeTypeResolver.tryResolveDeclaredType(
                scope,
                typeText,
                (lookupScope, unresolvedTypeText) -> {
                    var mappedCanonicalName = findMappedTopLevelCanonicalName(
                            unresolvedTypeText,
                            topLevelCanonicalNameMap
                    );
                    if (mappedCanonicalName == null) {
                        return null;
                    }
                    var mappedTypeMeta = lookupScope.resolveTypeMeta(
                            mappedCanonicalName,
                            ResolveRestriction.unrestricted()
                    ).allowedValueOrNull();
                    return mappedTypeMeta != null ? mappedTypeMeta.instanceType() : null;
                }
        );
    }

}
