package dev.superice.gdcc.lir;

import dev.superice.gdcc.enums.GdInstruction;
import dev.superice.gdcc.enums.GdInstruction.OperandKind;
import dev.superice.gdcc.enums.GodotOperator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/// Base type for all Low IR instructions.
/// -----
/// Per requirement, we only model an abstract instruction type here.
/// Concrete instructions can be added later.
public interface LirInstruction {

    /// Optional result id without `$` (e.g. "0", "self").
    @Nullable String resultId();

    /// Instruction mnemonic.
    GdInstruction opcode();

    @NotNull List<Operand> operands();

    default boolean checkEquals(@NotNull LirInstruction another) {
        return this.opcode() == another.opcode()
                && Objects.equals(this.resultId(), another.resultId())
                && Objects.equals(this.operands(), another.operands());
    }

    sealed interface Operand permits BasicBlockOperand, BooleanOperand, FloatOperand, GdOperatorOperand, IntOperand, StringOperand, VarargOperand, VariableOperand {
        /// Returns the expected operand kind for this operand instance.
        OperandKind operandKind();
    }

    record VariableOperand(@NotNull String id) implements Operand {
        @Override
        public OperandKind operandKind() {
            return OperandKind.VARIABLE;
        }
    }

    record BasicBlockOperand(@NotNull String bbId) implements Operand {
        @Override
        public OperandKind operandKind() {
            return OperandKind.LABEL;
        }
    }

    record StringOperand(@NotNull String value) implements Operand {
        @Override
        public OperandKind operandKind() {
            return OperandKind.STRING;
        }
    }

    record IntOperand(int value) implements Operand {
        @Override
        public OperandKind operandKind() {
            return OperandKind.INT;
        }
    }

    record FloatOperand(double value) implements Operand {
        @Override
        public OperandKind operandKind() {
            return OperandKind.FLOAT;
        }
    }

    record BooleanOperand(boolean value) implements Operand {
        @Override
        public OperandKind operandKind() {
            return OperandKind.BOOL;
        }
    }

    record GdOperatorOperand(@NotNull GodotOperator operator) implements Operand {
        @Override
        public OperandKind operandKind() {
            return OperandKind.OPERATOR;
        }
    }

    record VarargOperand(@NotNull List<Operand> values) implements Operand {
        @Override
        public OperandKind operandKind() {
            return OperandKind.VARARGS;
        }
    }
}
