package dev.superice.gdcc.frontend.lowering.pass.body;

import dev.superice.gdcc.enums.GodotOperator;
import dev.superice.gdcc.lir.LirBasicBlock;
import dev.superice.gdcc.lir.insn.AssignInsn;
import dev.superice.gdcc.lir.insn.BinaryOpInsn;
import dev.superice.gdcc.lir.insn.LiteralBoolInsn;
import dev.superice.gdcc.lir.insn.LiteralFloatInsn;
import dev.superice.gdcc.lir.insn.LiteralIntInsn;
import dev.superice.gdcc.lir.insn.LiteralNilInsn;
import dev.superice.gdcc.lir.insn.LiteralStringInsn;
import dev.superice.gdcc.lir.insn.LiteralStringNameInsn;
import dev.superice.gdcc.lir.insn.LoadPropertyInsn;
import dev.superice.gdcc.lir.insn.LoadStaticInsn;
import dev.superice.gdcc.lir.insn.UnaryOpInsn;
import dev.superice.gdparser.frontend.ast.BinaryExpression;
import dev.superice.gdparser.frontend.ast.Expression;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.LiteralExpression;
import dev.superice.gdparser.frontend.ast.SelfExpression;
import dev.superice.gdparser.frontend.ast.UnaryExpression;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

final class FrontendOpaqueExprInsnLoweringProcessors {
    private FrontendOpaqueExprInsnLoweringProcessors() {
    }

    static @NotNull FrontendInsnLoweringProcessorRegistry<Expression, FrontendBodyLoweringSession.OpaqueExprLoweringContext>
    createRegistry() {
        return FrontendInsnLoweringProcessorRegistry.of(
                "opaque expression",
                new FrontendIdentifierOpaqueExprInsnLoweringProcessor(),
                new FrontendLiteralOpaqueExprInsnLoweringProcessor(),
                new FrontendSelfOpaqueExprInsnLoweringProcessor(),
                new FrontendUnaryOpaqueExprInsnLoweringProcessor(),
                new FrontendBinaryOpaqueExprInsnLoweringProcessor()
        );
    }

    /// Resolves a bare identifier leaf through the already-published binding table.
    ///
    /// This processor is allowed to choose only among binding-backed runtime load routes
    /// (local/parameter/capture/property/self); it must not re-run any scope lookup or member
    /// inference.
    private static final class FrontendIdentifierOpaqueExprInsnLoweringProcessor
            implements FrontendInsnLoweringProcessor<IdentifierExpression, FrontendBodyLoweringSession.OpaqueExprLoweringContext> {
        @Override
        public @NotNull Class<IdentifierExpression> nodeType() {
            return IdentifierExpression.class;
        }

        @Override
        public void lower(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull IdentifierExpression node,
                @Nullable FrontendBodyLoweringSession.OpaqueExprLoweringContext context
        ) {
            var item = requireContext(context);
            var binding = session.requireBinding(node);
            var resultSlotId = session.resultSlotId(item);
            switch (binding.kind()) {
                case SELF -> block.appendNonTerminatorInstruction(new AssignInsn(resultSlotId, "self"));
                case LOCAL_VAR, PARAMETER, CAPTURE ->
                        block.appendNonTerminatorInstruction(new AssignInsn(resultSlotId, binding.symbolName()));
                case PROPERTY -> {
                    if (session.isStaticPropertyBinding(binding)) {
                        block.appendNonTerminatorInstruction(new LoadStaticInsn(
                                resultSlotId,
                                session.currentClassName(),
                                binding.symbolName()
                        ));
                        return;
                    }
                    session.requireSelfSlot();
                    block.appendNonTerminatorInstruction(new LoadPropertyInsn(resultSlotId, binding.symbolName(), "self"));
                }
                default -> throw session.unsupportedSequenceItem(
                        item,
                        "identifier binding kind is not supported by executable body lowering: " + binding.kind()
                );
            }
        }
    }

    /// Emits literal materialization instructions directly from the parser literal kind/source text.
    ///
    /// The processor stays intentionally dumb: all type acceptance already happened upstream, so it
    /// only translates the published literal surface into the matching concrete LIR instruction.
    private static final class FrontendLiteralOpaqueExprInsnLoweringProcessor
            implements FrontendInsnLoweringProcessor<LiteralExpression, FrontendBodyLoweringSession.OpaqueExprLoweringContext> {
        @Override
        public @NotNull Class<LiteralExpression> nodeType() {
            return LiteralExpression.class;
        }

        @Override
        public void lower(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull LiteralExpression node,
                @Nullable FrontendBodyLoweringSession.OpaqueExprLoweringContext context
        ) {
            var item = requireContext(context);
            var resultSlotId = session.resultSlotId(item);
            switch (node.kind()) {
                case "integer" -> block.appendNonTerminatorInstruction(new LiteralIntInsn(
                        resultSlotId,
                        Integer.parseInt(node.sourceText())
                ));
                case "number" -> {
                    if (node.sourceText().contains(".")) {
                        block.appendNonTerminatorInstruction(new LiteralFloatInsn(
                                resultSlotId,
                                Double.parseDouble(node.sourceText())
                        ));
                        return;
                    }
                    block.appendNonTerminatorInstruction(new LiteralIntInsn(
                            resultSlotId,
                            Integer.parseInt(node.sourceText())
                    ));
                }
                case "float" -> block.appendNonTerminatorInstruction(new LiteralFloatInsn(
                        resultSlotId,
                        Double.parseDouble(node.sourceText())
                ));
                case "string" -> block.appendNonTerminatorInstruction(new LiteralStringInsn(
                        resultSlotId,
                        node.sourceText()
                ));
                case "string_name" -> block.appendNonTerminatorInstruction(new LiteralStringNameInsn(
                        resultSlotId,
                        node.sourceText()
                ));
                case "true" -> block.appendNonTerminatorInstruction(new LiteralBoolInsn(resultSlotId, true));
                case "false" -> block.appendNonTerminatorInstruction(new LiteralBoolInsn(resultSlotId, false));
                case "null" -> block.appendNonTerminatorInstruction(new LiteralNilInsn(resultSlotId));
                default -> throw session.unsupportedSequenceItem(
                        item,
                        "literal kind is not supported by executable body lowering: " + node.kind()
                );
            }
        }
    }

    /// Reuses the implicit `self` slot instead of allocating any extra receiver reconstruction path.
    ///
    /// `SelfExpression` has no child operands and no semantic branching once compile gate has
    /// accepted the function shape, so the processor only copies the canonical slot id.
    private static final class FrontendSelfOpaqueExprInsnLoweringProcessor
            implements FrontendInsnLoweringProcessor<SelfExpression, FrontendBodyLoweringSession.OpaqueExprLoweringContext> {
        @Override
        public @NotNull Class<SelfExpression> nodeType() {
            return SelfExpression.class;
        }

        @Override
        public void lower(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull SelfExpression node,
                @Nullable FrontendBodyLoweringSession.OpaqueExprLoweringContext context
        ) {
            var item = requireContext(context);
            block.appendNonTerminatorInstruction(new AssignInsn(session.resultSlotId(item), "self"));
        }
    }

    /// Finalizes unary opaque expressions from their already-materialized operand slot.
    ///
    /// Child evaluation order is frozen by CFG build, so the processor only validates the operand
    /// count and chooses the unary opcode mapped from the source operator lexeme.
    private static final class FrontendUnaryOpaqueExprInsnLoweringProcessor
            implements FrontendInsnLoweringProcessor<UnaryExpression, FrontendBodyLoweringSession.OpaqueExprLoweringContext> {
        @Override
        public @NotNull Class<UnaryExpression> nodeType() {
            return UnaryExpression.class;
        }

        @Override
        public void lower(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull UnaryExpression node,
                @Nullable FrontendBodyLoweringSession.OpaqueExprLoweringContext context
        ) {
            var item = requireContext(context);
            session.requireOpaqueOperandCount(item, 1);
            block.appendNonTerminatorInstruction(new UnaryOpInsn(
                    session.resultSlotId(item),
                    GodotOperator.fromSourceLexeme(node.operator(), GodotOperator.OperatorArity.UNARY),
                    session.slotIdForValue(item.operandValueIds().getFirst())
            ));
        }
    }

    /// Finalizes eager binary expressions from the two operand slots already published by CFG build.
    ///
    /// Short-circuit `and/or` never reaches this processor; if they do, that means the opaque-item
    /// classifier or compile gate has been bypassed.
    private static final class FrontendBinaryOpaqueExprInsnLoweringProcessor
            implements FrontendInsnLoweringProcessor<BinaryExpression, FrontendBodyLoweringSession.OpaqueExprLoweringContext> {
        @Override
        public @NotNull Class<BinaryExpression> nodeType() {
            return BinaryExpression.class;
        }

        @Override
        public void lower(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull BinaryExpression node,
                @Nullable FrontendBodyLoweringSession.OpaqueExprLoweringContext context
        ) {
            var item = requireContext(context);
            session.requireOpaqueOperandCount(item, 2);
            block.appendNonTerminatorInstruction(new BinaryOpInsn(
                    session.resultSlotId(item),
                    GodotOperator.fromSourceLexeme(node.operator(), GodotOperator.OperatorArity.BINARY),
                    session.slotIdForValue(item.operandValueIds().getFirst()),
                    session.slotIdForValue(item.operandValueIds().getLast())
            ));
        }
    }

    private static @NotNull dev.superice.gdcc.frontend.lowering.cfg.item.OpaqueExprValueItem requireContext(
            @Nullable FrontendBodyLoweringSession.OpaqueExprLoweringContext context
    ) {
        return Objects.requireNonNull(context, "context must not be null").item();
    }
}
