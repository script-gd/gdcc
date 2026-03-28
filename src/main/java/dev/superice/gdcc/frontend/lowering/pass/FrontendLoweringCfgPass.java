package dev.superice.gdcc.frontend.lowering.pass;

import dev.superice.gdcc.frontend.lowering.FrontendLoweringContext;
import dev.superice.gdcc.frontend.lowering.FrontendLoweringPass;
import dev.superice.gdcc.frontend.lowering.FunctionLoweringContext;
import dev.superice.gdcc.lir.LirBasicBlock;
import dev.superice.gdcc.lir.LirModule;
import dev.superice.gdparser.frontend.ast.Block;
import dev.superice.gdparser.frontend.ast.DeclarationKind;
import dev.superice.gdparser.frontend.ast.ElifClause;
import dev.superice.gdparser.frontend.ast.ExpressionStatement;
import dev.superice.gdparser.frontend.ast.IfStatement;
import dev.superice.gdparser.frontend.ast.PassStatement;
import dev.superice.gdparser.frontend.ast.ReturnStatement;
import dev.superice.gdparser.frontend.ast.Statement;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import dev.superice.gdparser.frontend.ast.WhileStatement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/// Freezes the first CFG-pass contract without materializing any block skeleton yet.
///
/// This step only validates that later CFG work will see compile-ready executable lowering units
/// that already crossed the compile gate and therefore satisfy the current compile surface.
public final class FrontendLoweringCfgPass implements FrontendLoweringPass {
    private int nextIfIndex;
    private int nextElifIndex;
    private int nextWhileIndex;

    @Override
    public void run(@NotNull FrontendLoweringContext context) {
        var analysisData = context.requireAnalysisData();
        var lirModule = context.requireLirModule();
        var functionLoweringContexts = context.requireFunctionLoweringContexts();

        for (var functionContext : functionLoweringContexts) {
            if (functionContext.analysisData() != analysisData) {
                throw new IllegalStateException("Function lowering context must reuse the published analysis snapshot");
            }
            validateTargetFunctionMembership(functionContext, lirModule);
            switch (functionContext.kind()) {
                case EXECUTABLE_BODY -> publishExecutableCfgSkeleton(functionContext);
                case PROPERTY_INIT -> validatePropertyInitContext(functionContext);
                case PARAMETER_DEFAULT_INIT -> throw new IllegalStateException(
                        "CFG pass does not support parameter default initializer contexts yet"
                );
            }
        }
    }

    private void publishExecutableCfgSkeleton(@NotNull FunctionLoweringContext functionContext) {
        if (!(functionContext.sourceOwner() instanceof dev.superice.gdparser.frontend.ast.FunctionDeclaration)
                && !(functionContext.sourceOwner() instanceof dev.superice.gdparser.frontend.ast.ConstructorDeclaration)) {
            throw new IllegalStateException(describeContext(functionContext) + " must keep a callable declaration as sourceOwner");
        }
        if (!(functionContext.loweringRoot() instanceof Block rootBlock)) {
            throw new IllegalStateException(describeContext(functionContext) + " must expose a Block loweringRoot");
        }
        validateShellOnlyTarget(functionContext);
        resetBlockCounters();
        var entry = new LirBasicBlock("entry");
        functionContext.publishCfgNodeBlocks(rootBlock, new FunctionLoweringContext.BlockCfgNodeBlocks(entry));
        walkBlock(functionContext, rootBlock, entry);
    }

    private void validatePropertyInitContext(@NotNull FunctionLoweringContext functionContext) {
        validateShellOnlyTarget(functionContext);
    }

    /// Walks one lexical block and returns the block that later lexical statements should continue
    /// from. `null` means control flow has definitely terminated inside this block, so any later
    /// sibling statements must not publish CFG skeleton metadata.
    private @Nullable LirBasicBlock walkBlock(
            @NotNull FunctionLoweringContext functionContext,
            @NotNull Block block,
            @Nullable LirBasicBlock currentBlock
    ) {
        var activeBlock = currentBlock;
        for (var statement : block.statements()) {
            if (activeBlock == null) {
                break;
            }
            activeBlock = walkStatement(functionContext, statement, activeBlock);
        }
        return activeBlock;
    }

    /// Classifies the small MVP statement surface that is allowed to reach CFG lowering.
    ///
    /// The returned block models lexical fallthrough:
    /// - same block: statement does not force a split and control continues normally
    /// - new block: structured statement creates successor skeleton blocks and yields its merge/exit
    /// - `null`: statement terminates the lexical path
    private @Nullable LirBasicBlock walkStatement(
            @NotNull FunctionLoweringContext functionContext,
            @NotNull Statement statement,
            @NotNull LirBasicBlock currentBlock
    ) {
        return switch (statement) {
            case PassStatement _ -> currentBlock;
            case ReturnStatement _ -> null;
            case ExpressionStatement _ -> currentBlock;
            case VariableDeclaration variableDeclaration when variableDeclaration.kind() == DeclarationKind.VAR ->
                    currentBlock;
            case IfStatement ifStatement -> walkIfStatement(functionContext, ifStatement);
            case WhileStatement whileStatement -> walkWhileStatement(functionContext, whileStatement);
            default -> throw new IllegalStateException(
                    describeContext(functionContext)
                            + " reached an unsupported executable statement in CFG walk: "
                            + statement.getClass().getSimpleName()
            );
        };
    }

    /// Publishes the CFG role bundle for one `if` and then walks each lexical branch body.
    ///
    /// This method only freezes the skeleton shape:
    /// - `thenEntry` is the true branch entry
    /// - `elseOrNextClauseEntry` is the false continuation into `else` or the first `elif`
    /// - `merge` is the lexical continuation after the whole chain
    ///
    /// If every branch terminates, we return `null` so later sibling statements are suppressed.
    private @Nullable LirBasicBlock walkIfStatement(
            @NotNull FunctionLoweringContext functionContext,
            @NotNull IfStatement ifStatement
    ) {
        var needsFalseEntry = !ifStatement.elifClauses().isEmpty() || ifStatement.elseBody() != null;
        var ifBlocks = newIfBlocks(needsFalseEntry);
        functionContext.publishCfgNodeBlocks(ifStatement, ifBlocks);

        // The `then` branch starts at its dedicated entry block.
        var fallsThrough = walkBlock(functionContext, ifStatement.body(), ifBlocks.thenEntry()) != null;
        if (!ifStatement.elifClauses().isEmpty()) {
            // The false edge first enters the `elif` chain, which may eventually continue into
            // `else` or alias directly to the owning merge block.
            fallsThrough |= walkElifChain(
                    functionContext,
                    ifStatement.elifClauses(),
                    ifBlocks.elseOrNextClauseEntry(),
                    ifBlocks.merge(),
                    ifStatement.elseBody()
            );
        } else if (ifStatement.elseBody() != null) {
            // Without `elif`, the false edge enters the `else` body directly.
            fallsThrough |= walkBlock(functionContext, ifStatement.elseBody(), ifBlocks.elseOrNextClauseEntry()) != null;
        } else {
            // Without `elif` or `else`, the false path always falls through to the merge.
            fallsThrough = true;
        }
        return fallsThrough ? ifBlocks.merge() : null;
    }

    /// Walks an `elif` chain as a sequence of false-continuation checkpoints.
    ///
    /// Each clause publishes its own bundle:
    /// - `bodyEntry` is the clause body entered when that clause condition is true
    /// - `nextClauseOrMerge` is the false continuation into the next clause or the owning `if`
    ///   merge when the chain is exhausted
    ///
    /// The boolean result answers whether any path from the chain can continue past the owning
    /// `if` statement.
    private boolean walkElifChain(
            @NotNull FunctionLoweringContext functionContext,
            @NotNull List<ElifClause> elifClauses,
            @NotNull LirBasicBlock firstClauseEntry,
            @NotNull LirBasicBlock mergeBlock,
            @Nullable Block elseBody
    ) {
        var fallsThrough = false;
        var clauseEntry = firstClauseEntry;
        for (var clauseIndex = 0; clauseIndex < elifClauses.size(); clauseIndex++) {
            var elifClause = elifClauses.get(clauseIndex);
            var hasNextClause = clauseIndex + 1 < elifClauses.size();
            var needsFalseEntry = hasNextClause || elseBody != null;
            var elifBlocks = newElifBlocks(needsFalseEntry, mergeBlock);
            functionContext.publishCfgNodeBlocks(elifClause, elifBlocks);
            // `clauseEntry` represents where the previous false edge lands. The clause itself then
            // publishes its own body entry plus the next false continuation point.
            if (walkBlock(functionContext, elifClause.body(), elifBlocks.bodyEntry()) != null) {
                fallsThrough = true;
            }
            clauseEntry = elifBlocks.nextClauseOrMerge();
        }
        if (elseBody != null) {
            // The final false continuation enters the `else` body if it exists.
            return fallsThrough || walkBlock(functionContext, elseBody, clauseEntry) != null;
        }
        // Without `else`, the final false continuation reaches the owning merge by construction.
        return true;
    }

    /// Publishes the fixed three-block skeleton for `while`.
    ///
    /// The loop always contributes:
    /// - a condition entry
    /// - a body entry
    /// - an exit block that represents lexical continuation after the loop
    ///
    /// We still walk the body so nested control-flow nodes publish their own metadata, but the
    /// body result does not change the loop exit contract: later lexical statements continue from
    /// `exit`.
    private @NotNull LirBasicBlock walkWhileStatement(
            @NotNull FunctionLoweringContext functionContext,
            @NotNull WhileStatement whileStatement
    ) {
        var whileBlocks = newWhileBlocks();
        functionContext.publishCfgNodeBlocks(whileStatement, whileBlocks);
        walkBlock(functionContext, whileStatement.body(), whileBlocks.bodyEntry());
        return whileBlocks.exit();
    }

    private void validateTargetFunctionMembership(
            @NotNull FunctionLoweringContext functionContext,
            @NotNull LirModule lirModule
    ) {
        if (lirModule.getClassDefs().stream().noneMatch(classDef -> classDef == functionContext.owningClass())) {
            throw new IllegalStateException(
                    describeContext(functionContext) + " references an owningClass outside the published LIR module"
            );
        }
        if (functionContext.owningClass().getFunctions().stream().noneMatch(function -> function == functionContext.targetFunction())) {
            throw new IllegalStateException(
                    describeContext(functionContext) + " references a targetFunction outside the owning LIR class"
            );
        }
    }

    private void validateShellOnlyTarget(@NotNull FunctionLoweringContext functionContext) {
        if (functionContext.targetFunction().getBasicBlockCount() != 0
                || !functionContext.targetFunction().getEntryBlockId().isEmpty()) {
            throw new IllegalStateException(
                    describeContext(functionContext) + " must remain shell-only before CFG skeleton materialization"
            );
        }
    }

    private static @NotNull String describeContext(@NotNull FunctionLoweringContext functionContext) {
        return "Function lowering context "
                + functionContext.kind()
                + " "
                + functionContext.owningClass().getName()
                + "."
                + functionContext.targetFunction().getName();
    }

    /// CFG block ids are scoped per executable lowering unit so regression tests can rely on
    /// lexical visit order without leaking counters across functions.
    private void resetBlockCounters() {
        nextIfIndex = 0;
        nextElifIndex = 0;
        nextWhileIndex = 0;
    }

    /// Allocates one deterministic `if` bundle.
    ///
    /// When there is no false-side body, the false continuation aliases `merge` so later passes can
    /// observe that the source shape falls through directly instead of inventing an extra block.
    private @NotNull FunctionLoweringContext.IfCfgNodeBlocks newIfBlocks(boolean needsFalseEntry) {
        var index = nextIfIndex++;
        var merge = new LirBasicBlock("if_merge_" + index);
        var falseEntry = needsFalseEntry ? new LirBasicBlock("if_false_" + index) : merge;
        return new FunctionLoweringContext.IfCfgNodeBlocks(
                new LirBasicBlock("if_then_" + index),
                falseEntry,
                merge
        );
    }

    /// Allocates one deterministic `elif` bundle.
    ///
    /// The final clause in a chain may alias its false continuation to the owning `if` merge block
    /// so the metadata preserves the exact lexical CFG shape without creating an unnecessary node.
    private @NotNull FunctionLoweringContext.ElifCfgNodeBlocks newElifBlocks(
            boolean needsFalseEntry,
            @NotNull LirBasicBlock mergeBlock
    ) {
        var index = nextElifIndex++;
        return new FunctionLoweringContext.ElifCfgNodeBlocks(
                new LirBasicBlock("elif_body_" + index),
                needsFalseEntry ? new LirBasicBlock("elif_false_" + index) : mergeBlock
        );
    }

    /// Allocates one deterministic `while` bundle with separate condition, body, and exit roles.
    private @NotNull FunctionLoweringContext.WhileCfgNodeBlocks newWhileBlocks() {
        var index = nextWhileIndex++;
        return new FunctionLoweringContext.WhileCfgNodeBlocks(
                new LirBasicBlock("while_cond_" + index),
                new LirBasicBlock("while_body_" + index),
                new LirBasicBlock("while_exit_" + index)
        );
    }
}
