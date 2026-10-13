package gd.script.gdcc.backend.c.gen.insn;

import gd.script.gdcc.backend.c.gen.CGenHelper;
import gd.script.gdcc.backend.c.gen.CInsnGen;
import gd.script.gdcc.enums.GdInstruction;
import gd.script.gdcc.exception.InvalidInsnException;
import gd.script.gdcc.lir.LirBasicBlock;
import gd.script.gdcc.lir.LirClassDef;
import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.lir.insn.LineNumberInsn;
import gd.script.gdcc.util.StringUtil;
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
            sb.append("\"").append(StringUtil.escapeLineDirectiveFileName(clazz.getSourceFile())).append("\"");
        } else {
            sb.append("\"").append(StringUtil.escapeLineDirectiveFileName(clazz.getName())).append("\"");
        }
        return sb.toString();
    }
}
