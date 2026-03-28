package dev.superice.gdcc.frontend.lowering;

import dev.superice.gdcc.frontend.sema.FrontendAnalysisData;
import dev.superice.gdcc.frontend.sema.FrontendSourceClassRelation;
import dev.superice.gdcc.lir.LirClassDef;
import dev.superice.gdcc.lir.LirFunctionDef;
import dev.superice.gdparser.frontend.ast.Node;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.Objects;

/// Lowering-local description of one function-shaped unit that later passes will lower.
///
/// The unit can be:
/// - an executable callable body already published in the class skeleton
/// - a synthetic property initializer function shell
/// - a future synthetic parameter-default initializer function shell
///
/// The record deliberately keeps direct AST identity references because frontend side tables are
/// keyed by the original parser nodes. `sourceOwner` therefore remains the original declaration
/// node, including future parameter/default owners whose lowering root is only the default-value
/// expression.
///
/// @param kind Category of the lowering unit. This determines how later passes should interpret
///             the relationship between `sourceOwner` and `loweringRoot`. The preparation pass
///             currently publishes `EXECUTABLE_BODY` and `PROPERTY_INIT`, but the model must keep
///             `PARAMETER_DEFAULT_INIT` as a reserved extension slot for future lowering work.
/// @param sourcePath Source file path that owns this lowering unit. It must match the same parser
///                   unit as `sourceClassRelation` and `sourceOwner`, so diagnostics and lowering
///                   results can be anchored back to the original source file.
/// @param sourceClassRelation Source-to-class relation published by the frontend skeleton. It
///                            provides the stable mapping from the AST class owner to the target
///                            `LirClassDef`, which later lowering passes use to recover the source
///                            class that owns this function-shaped unit.
/// @param owningClass LIR class that owns the target function. For an executable body this is the
///                    class that already contains the callable published by the skeleton. For a
///                    property initializer or future parameter-default initializer this is the
///                    owning class that receives the hidden synthetic function shell.
/// @param targetFunction Target function whose body will be populated by lowering. During
///                       preparation, the only requirement is that the function shell already
///                       exists on `owningClass`; later body-lowering passes are responsible for
///                       writing blocks, entry metadata, and instructions into it.
/// @param sourceOwner Original AST owner node used by frontend side tables. This must stay at the
///                    declaration-level owner instead of collapsing to `loweringRoot`: callable
///                    lowering uses the declaration node, property initialization uses the property
///                    declaration, and future parameter-default lowering uses the
///                    parameter/default declaration node.
/// @param loweringRoot AST root actually traversed and transformed by this lowering unit. It can
///                     be identical to `sourceOwner` or a narrower subtree below it. For example,
///                     property initialization lowers only the initializer expression, and future
///                     parameter-default lowering lowers only the default-value expression.
/// @param analysisData Frontend analysis snapshot already published when this context is created.
///                     Later lowering passes read scopes, types, module skeleton facts, and other
///                     side tables from it instead of re-running semantic analysis.
public record FunctionLoweringContext(
        @NotNull Kind kind,
        @NotNull Path sourcePath,
        @NotNull FrontendSourceClassRelation sourceClassRelation,
        @NotNull LirClassDef owningClass,
        @NotNull LirFunctionDef targetFunction,
        @NotNull Node sourceOwner,
        @NotNull Node loweringRoot,
        @NotNull FrontendAnalysisData analysisData
) {
    public FunctionLoweringContext {
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(sourcePath, "sourcePath must not be null");
        Objects.requireNonNull(sourceClassRelation, "sourceClassRelation must not be null");
        Objects.requireNonNull(owningClass, "owningClass must not be null");
        Objects.requireNonNull(targetFunction, "targetFunction must not be null");
        Objects.requireNonNull(sourceOwner, "sourceOwner must not be null");
        Objects.requireNonNull(loweringRoot, "loweringRoot must not be null");
        Objects.requireNonNull(analysisData, "analysisData must not be null");
    }

    public enum Kind {
        EXECUTABLE_BODY,
        PROPERTY_INIT,
        PARAMETER_DEFAULT_INIT
    }
}
