package gd.script.gdcc.backend;

import gd.script.gdcc.util.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/// Caller-facing facts about one compiled source file, consumed by backend codegen for the
/// Phase 6 class metadata (`_gdcc_get_metadata`): `displayPath` becomes `source_res_path` when
/// it is `res://`-shaped, and the optional `absolutePath` becomes `source_path`.
///
/// Instances are keyed in `CodegenContext.sourceFileFacts` by the normalized logical-path text
/// (`path.toString()` with `/` separators) — the same normalization the frontend stamps onto
/// `LirClassDef.sourceFile`, so the backend can join without re-deriving paths.
public record SourceFileFacts(@NotNull String displayPath, @Nullable String absolutePath) {
    public SourceFileFacts {
        displayPath = StringUtil.requireTrimmedNonBlank(displayPath, "displayPath");
        absolutePath = absolutePath == null ? null : StringUtil.requireTrimmedNonBlank(absolutePath, "absolutePath");
    }
}
