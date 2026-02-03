package dev.superice.gdcc.lir.insn;

import dev.superice.gdcc.lir.LirInstruction;

public interface NewDataInstruction extends LirInstruction {
    default LiteralBoolInsn getAsLiteralBoolInsn() {
        return (LiteralBoolInsn) this;
    }

    default LiteralIntInsn getAsLiteralIntInsn() {
        return (LiteralIntInsn) this;
    }

    default LiteralStringInsn getAsLiteralStringInsn() {
        return (LiteralStringInsn) this;
    }

    default LiteralFloatInsn getAsLiteralFloatInsn() {
        return (LiteralFloatInsn) this;
    }

    default LiteralStringNameInsn getAsLiteralStringNameInsn() {
        return (LiteralStringNameInsn) this;
    }

    default LiteralNilInsn getAsLiteralNilInsn() {
        return (LiteralNilInsn) this;
    }

    default LiteralNullInsn getAsLiteralNullInsn() {
        return (LiteralNullInsn) this;
    }
}
