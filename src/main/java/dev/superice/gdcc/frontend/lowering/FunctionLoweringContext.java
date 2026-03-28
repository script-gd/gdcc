package dev.superice.gdcc.frontend.lowering;

import dev.superice.gdcc.frontend.sema.FrontendAnalysisData;
import dev.superice.gdcc.frontend.sema.FrontendAstSideTable;
import dev.superice.gdcc.frontend.sema.FrontendSourceClassRelation;
import dev.superice.gdcc.lir.LirBasicBlock;
import dev.superice.gdcc.lir.LirClassDef;
import dev.superice.gdcc.lir.LirFunctionDef;
import dev.superice.gdparser.frontend.ast.Node;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;

/// Lowering-local description of one function-shaped unit that later passes will lower.
///
/// The unit can be:
/// - an executable callable body already published in the class skeleton
/// - a synthetic property initializer function shell
/// - a future synthetic parameter-default initializer function shell
///
/// The carrier keeps direct AST identity references because frontend side tables are keyed by the
/// original parser nodes. Later CFG/body passes therefore use the same AST identities instead of
/// rebuilding separate synthetic lookup keys.
public final class FunctionLoweringContext {
    /// Category of the lowering unit.
    ///
    /// This determines how later passes should interpret the relationship between `sourceOwner`
    /// and `loweringRoot`. The preparation pass currently publishes `EXECUTABLE_BODY` and
    /// `PROPERTY_INIT`, while `PARAMETER_DEFAULT_INIT` remains a reserved extension slot.
    private final @NotNull Kind kind;

    /// Source file path that owns this lowering unit.
    ///
    /// It must point at the same parsed source unit as `sourceClassRelation` and `sourceOwner` so
    /// diagnostics, runtime-name mapping, and future lowering failures can still anchor back to
    /// the original file.
    private final @NotNull Path sourcePath;

    /// Source-to-class relation published by the frontend skeleton.
    ///
    /// This relation is the stable bridge from source AST ownership to the runtime-mapped
    /// `LirClassDef`, including top-level and inner-class ownership facts already frozen by the
    /// skeleton phase.
    private final @NotNull FrontendSourceClassRelation sourceClassRelation;

    /// LIR class that owns the target function shell.
    ///
    /// For executable bodies this is the class that already contains the callable skeleton. For
    /// property/default initializer units this is the class that receives the hidden synthetic
    /// helper function.
    private final @NotNull LirClassDef owningClass;

    /// Target function whose body will be populated by lowering.
    ///
    /// During preparation the only requirement is that this function shell already exists on the
    /// owning class. Later CFG/body passes are responsible for filling blocks, entry metadata, and
    /// instructions into this shell when the architecture allows materialization.
    private final @NotNull LirFunctionDef targetFunction;

    /// Original declaration-level AST owner used by shared frontend side tables.
    ///
    /// This intentionally stays at the declaration node instead of collapsing to `loweringRoot`:
    /// callable lowering uses the declaration, property initialization uses the property
    /// declaration, and future parameter-default lowering uses the parameter/default declaration.
    private final @NotNull Node sourceOwner;

    /// AST root actually traversed and transformed by this lowering unit.
    ///
    /// It can be identical to `sourceOwner` or a narrower subtree below it. For example, property
    /// initialization lowers only the initializer expression, while executable lowering uses the
    /// callable body `Block`.
    private final @NotNull Node loweringRoot;

    /// Compile-ready frontend analysis snapshot reused by all later lowering passes.
    ///
    /// CFG/body lowering must read scopes, bindings, resolved members/calls, and expression types
    /// from this snapshot instead of re-running semantic analysis or rebuilding ad-hoc side
    /// tables.
    private final @NotNull FrontendAnalysisData analysisData;

    /// Per-function CFG skeleton bundles keyed by original AST node identity.
    ///
    /// CFG-oriented passes publish into this side table after the function shells already exist.
    /// Keeping the mapping local to the lowering context lets later passes recover block roles by
    /// AST identity without mutating shared semantic state or prematurely materializing the blocks
    /// into the target `LirFunctionDef`.
    private final @NotNull FrontendAstSideTable<CfgNodeBlocks> cfgNodeBlocks = new FrontendAstSideTable<>();

    public FunctionLoweringContext(
            @NotNull Kind kind,
            @NotNull Path sourcePath,
            @NotNull FrontendSourceClassRelation sourceClassRelation,
            @NotNull LirClassDef owningClass,
            @NotNull LirFunctionDef targetFunction,
            @NotNull Node sourceOwner,
            @NotNull Node loweringRoot,
            @NotNull FrontendAnalysisData analysisData
    ) {
        this.kind = Objects.requireNonNull(kind, "kind must not be null");
        this.sourcePath = Objects.requireNonNull(sourcePath, "sourcePath must not be null");
        this.sourceClassRelation = Objects.requireNonNull(
                sourceClassRelation,
                "sourceClassRelation must not be null"
        );
        this.owningClass = Objects.requireNonNull(owningClass, "owningClass must not be null");
        this.targetFunction = Objects.requireNonNull(targetFunction, "targetFunction must not be null");
        this.sourceOwner = Objects.requireNonNull(sourceOwner, "sourceOwner must not be null");
        this.loweringRoot = Objects.requireNonNull(loweringRoot, "loweringRoot must not be null");
        this.analysisData = Objects.requireNonNull(analysisData, "analysisData must not be null");
    }

    public @NotNull Kind kind() {
        return kind;
    }

    public @NotNull Path sourcePath() {
        return sourcePath;
    }

    public @NotNull FrontendSourceClassRelation sourceClassRelation() {
        return sourceClassRelation;
    }

    public @NotNull LirClassDef owningClass() {
        return owningClass;
    }

    public @NotNull LirFunctionDef targetFunction() {
        return targetFunction;
    }

    public @NotNull Node sourceOwner() {
        return sourceOwner;
    }

    public @NotNull Node loweringRoot() {
        return loweringRoot;
    }

    public @NotNull FrontendAnalysisData analysisData() {
        return analysisData;
    }

    /// Publishes one AST-keyed CFG block bundle for this lowering unit.
    ///
    /// Duplicate publication for the same AST node is a protocol violation because later passes
    /// must be able to rely on a one-to-one mapping from node identity to block-role bundle.
    public void publishCfgNodeBlocks(@NotNull Node astNode, @NotNull CfgNodeBlocks blocks) {
        Objects.requireNonNull(astNode, "astNode must not be null");
        Objects.requireNonNull(blocks, "blocks must not be null");
        if (cfgNodeBlocks.containsKey(astNode)) {
            throw new IllegalStateException(
                    "CFG node blocks have already been published for " + describeCfgAstNode(astNode)
            );
        }
        cfgNodeBlocks.put(astNode, blocks);
    }

    public @Nullable CfgNodeBlocks cfgNodeBlocksOrNull(@NotNull Node astNode) {
        return cfgNodeBlocks.get(Objects.requireNonNull(astNode, "astNode must not be null"));
    }

    public @NotNull CfgNodeBlocks requireCfgNodeBlocks(@NotNull Node astNode) {
        var blocks = cfgNodeBlocksOrNull(astNode);
        if (blocks == null) {
            throw new IllegalStateException(
                    "CFG node blocks have not been published for " + describeCfgAstNode(astNode)
            );
        }
        return blocks;
    }

    public boolean hasCfgNodeBlocks(@NotNull Node astNode) {
        return cfgNodeBlocks.containsKey(Objects.requireNonNull(astNode, "astNode must not be null"));
    }

    public enum Kind {
        EXECUTABLE_BODY,
        PROPERTY_INIT,
        PARAMETER_DEFAULT_INIT
    }

    /// Role-bearing CFG skeleton blocks published for one AST node.
    ///
    /// The bundle keeps lexical block order so later lowering/materialization work can emit the
    /// same deterministic block order without re-deriving the shape from source traversal.
    public sealed interface CfgNodeBlocks
            permits BlockCfgNodeBlocks, IfCfgNodeBlocks, ElifCfgNodeBlocks, WhileCfgNodeBlocks {
        @NotNull List<LirBasicBlock> blocks();
    }

    /// CFG bundle for the executable-body root block.
    public record BlockCfgNodeBlocks(@NotNull LirBasicBlock entry) implements CfgNodeBlocks {
        public BlockCfgNodeBlocks {
            Objects.requireNonNull(entry, "entry must not be null");
        }

        @Override
        public @NotNull List<LirBasicBlock> blocks() {
            return List.of(entry);
        }
    }

    /// CFG bundle for one `if` statement.
    ///
    /// `elseOrNextClauseEntry` may alias `merge` when the source statement has neither `else` nor
    /// `elif`, because the false path then falls through directly into the merge block.
    public record IfCfgNodeBlocks(
            @NotNull LirBasicBlock thenEntry,
            @NotNull LirBasicBlock elseOrNextClauseEntry,
            @NotNull LirBasicBlock merge
    ) implements CfgNodeBlocks {
        public IfCfgNodeBlocks {
            Objects.requireNonNull(thenEntry, "thenEntry must not be null");
            Objects.requireNonNull(elseOrNextClauseEntry, "elseOrNextClauseEntry must not be null");
            Objects.requireNonNull(merge, "merge must not be null");
        }

        @Override
        public @NotNull List<LirBasicBlock> blocks() {
            return distinctBlocks(thenEntry, elseOrNextClauseEntry, merge);
        }
    }

    /// CFG bundle for one `elif` clause.
    ///
    /// `nextClauseOrMerge` may alias the owning `if` merge block when this clause is the final
    /// false-continuation point in the chain.
    public record ElifCfgNodeBlocks(
            @NotNull LirBasicBlock bodyEntry,
            @NotNull LirBasicBlock nextClauseOrMerge
    ) implements CfgNodeBlocks {
        public ElifCfgNodeBlocks {
            Objects.requireNonNull(bodyEntry, "bodyEntry must not be null");
            Objects.requireNonNull(nextClauseOrMerge, "nextClauseOrMerge must not be null");
        }

        @Override
        public @NotNull List<LirBasicBlock> blocks() {
            return distinctBlocks(bodyEntry, nextClauseOrMerge);
        }
    }

    /// CFG bundle for one `while` statement.
    public record WhileCfgNodeBlocks(
            @NotNull LirBasicBlock conditionEntry,
            @NotNull LirBasicBlock bodyEntry,
            @NotNull LirBasicBlock exit
    ) implements CfgNodeBlocks {
        public WhileCfgNodeBlocks {
            Objects.requireNonNull(conditionEntry, "conditionEntry must not be null");
            Objects.requireNonNull(bodyEntry, "bodyEntry must not be null");
            Objects.requireNonNull(exit, "exit must not be null");
        }

        @Override
        public @NotNull List<LirBasicBlock> blocks() {
            return distinctBlocks(conditionEntry, bodyEntry, exit);
        }
    }

    private static @NotNull List<LirBasicBlock> distinctBlocks(@NotNull LirBasicBlock... blocks) {
        var seen = new IdentityHashMap<LirBasicBlock, Boolean>();
        var distinct = new ArrayList<LirBasicBlock>(blocks.length);
        for (var block : blocks) {
            if (seen.put(block, true) == null) {
                distinct.add(block);
            }
        }
        return List.copyOf(distinct);
    }

    private static @NotNull String describeCfgAstNode(@NotNull Node astNode) {
        return astNode.getClass().getSimpleName() + "@" + System.identityHashCode(astNode);
    }
}
