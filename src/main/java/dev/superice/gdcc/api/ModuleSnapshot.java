package dev.superice.gdcc.api;

import dev.superice.gdcc.util.StringUtil;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/// Frozen module view returned to RPC adapters and tests.
///
/// Step 1 only exposes `rootEntryCount` instead of a full VFS tree so lifecycle behavior can be
/// anchored without freezing the later VFS API surface too early.
public record ModuleSnapshot(
        @NotNull String moduleId,
        @NotNull String moduleName,
        @NotNull CompileOptions compileOptions,
        @NotNull Map<String, String> topLevelCanonicalNameMap,
        boolean hasLastCompileResult,
        int rootEntryCount
) {
    public ModuleSnapshot {
        moduleId = StringUtil.requireTrimmedNonBlank(moduleId, "moduleId");
        moduleName = StringUtil.requireTrimmedNonBlank(moduleName, "moduleName");
        Objects.requireNonNull(compileOptions, "compileOptions must not be null");
        topLevelCanonicalNameMap = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(
                topLevelCanonicalNameMap,
                "topLevelCanonicalNameMap must not be null"
        )));
        if (rootEntryCount < 0) {
            throw new IllegalArgumentException("rootEntryCount must not be negative");
        }
    }
}
