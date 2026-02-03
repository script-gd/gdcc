package dev.superice.gdcc.backend.c.gen.insn;

import dev.superice.gdcc.backend.c.gen.CGenHelper;
import dev.superice.gdcc.backend.c.gen.CInsnGen;
import dev.superice.gdcc.enums.GdInstruction;
import dev.superice.gdcc.exception.InvalidInsnException;
import dev.superice.gdcc.lir.LirBasicBlock;
import dev.superice.gdcc.lir.LirClassDef;
import dev.superice.gdcc.lir.LirFunctionDef;
import dev.superice.gdcc.lir.insn.LineNumberInsn;
import dev.superice.gdcc.util.StringUtil;
import org.jetbrains.annotations.NotNull;

import java.util.EnumSet;

public final class LineNumberInsnGen implements CInsnGen<LineNumberInsn> {
    @Override
    public @NotNull EnumSet<GdInstruction> getInsnOpcodes() {
        return EnumSet.of(GdInstruction.LINE_NUMBER);
    }

    @Override
    public @NotNull String generateCCode(@NotNull CGenHelper helper,
                                         @NotNull LirClassDef clazz,
                                         @NotNull LirFunctionDef func,
                                         @NotNull LirBasicBlock block,
                                         int insnIndex,
                                         @NotNull LineNumberInsn instruction) {
        if (instruction.lineNumber() <= 0) {
            throw new InvalidInsnException(func.getName(), block.id(), insnIndex, instruction.toString(),
                    "Line number must be positive, got " + instruction.lineNumber());
        }
        var sb = new StringBuilder("#line ");
        sb.append(instruction.lineNumber());
        sb.append(" ");
        if (clazz.getSourceFile() != null) {
            sb.append("\"").append(StringUtil.escapeStringLiteral(clazz.getSourceFile())).append("\"");
        } else {
            sb.append("\"").append(StringUtil.escapeStringLiteral(clazz.getName())).append("\"");
        }
        return sb.toString();
    }
}
