package gd.script.gdcc.frontend.sema;

import dev.superice.gdparser.frontend.ast.Node;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.Objects;

/// Source provenance of one declaration model object created by the class skeleton.
///
/// `symbolBindings()` / `resolvedMembers()` / `resolvedCalls()` declaration sites are not
/// guaranteed to be AST nodes: they can be `PropertyDef`, `FunctionDef` overload sets, enum
/// constant/group models, or engine metadata without any source position. The skeleton records one
/// entry per source-created model object (keyed by object identity in
/// `FrontendAnalysisData.declarationOrigins()`), and snapshot query services normalize those model
/// objects back to this source position. Model objects without an entry (engine/builtin metadata,
/// synthetic constructors) simply have no source position.
///
/// @param declarationNode the AST declaration node the model object was created from; its `range()`
///                        supplies the 1-based line/column position exposed to query callers
/// @param sourcePath      the frontend-visible source path of the owning unit (the unit's logical path;
///                        API-layer callers remap it to the display path through the snapshot source views)
public record FrontendDeclarationOrigin(
        @NotNull Node declarationNode,
        @NotNull Path sourcePath
) {
    public FrontendDeclarationOrigin {
        Objects.requireNonNull(declarationNode, "declarationNode must not be null");
        Objects.requireNonNull(sourcePath, "sourcePath must not be null");
    }
}
