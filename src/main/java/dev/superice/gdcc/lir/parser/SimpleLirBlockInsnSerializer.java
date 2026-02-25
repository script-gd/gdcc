package dev.superice.gdcc.lir.parser;

import dev.superice.gdcc.enums.LifecycleProvenance;
import dev.superice.gdcc.exception.LirInsnSerializationException;
import dev.superice.gdcc.lir.LirInstruction;
import dev.superice.gdcc.lir.LirInstruction.*;
import dev.superice.gdcc.lir.insn.LifecycleInstruction;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.Writer;
import java.util.List;

/// Simple text-based serializer for LIR basic-block instruction lists.
public final class SimpleLirBlockInsnSerializer implements LirBlockInsnSerializer {
    @Override
    public void serialize(@NotNull List<LirInstruction> insnList, @NotNull Writer writer) throws IOException {
        for (int i = 0; i < insnList.size(); i++) {
            var insn = insnList.get(i);
            try {
                var sb = new StringBuilder();

                // optional result
                if (insn.resultId() != null) {
                    sb.append('$').append(insn.resultId()).append(" = ");
                }

                // opcode (textual form from enum)
                sb.append(insn.opcode().opcode());

                // operands
                for (var op : insn.operands()) {
                    sb.append(' ');
                    appendOperand(sb, op);
                }
                appendLifecycleProvenance(sb, insn);

                sb.append(';');
                writer.write(sb.toString());
                writer.write('\n');
            } catch (LirInsnSerializationException e) {
                throw e; // rethrow
            } catch (Exception e) {
                throw new LirInsnSerializationException(i, insn, e.getMessage() == null ? e.toString() : e.getMessage());
            }
        }
    }

    private static void appendOperand(StringBuilder sb, Operand op) {
        switch (op) {
            case VariableOperand v -> sb.append('$').append(v.id());
            case BasicBlockOperand b -> sb.append(b.bbId());
            case StringOperand s -> sb.append('"').append(escapeString(s.value())).append('"');
            case IntOperand it -> sb.append(it.value());
            case FloatOperand ft -> sb.append(ft.value());
            case BooleanOperand bo -> sb.append(bo.value());
            case GdOperatorOperand opn -> sb.append('"').append(opn.operator().name()).append('"');
            case VarargOperand va -> {
                var first = true;
                for (var inner : va.values()) {
                    if (!first) sb.append(' ');
                    appendOperand(sb, inner);
                    first = false;
                }
            }
            default -> throw new IllegalArgumentException("Unsupported operand kind: " + op.getClass());
        }
    }

    private static void appendLifecycleProvenance(@NotNull StringBuilder sb, @NotNull LirInstruction insn) {
        if (!(insn instanceof LifecycleInstruction lifecycleInstruction)) {
            return;
        }
        var provenance = lifecycleInstruction.getProvenance();
        if (provenance == LifecycleProvenance.UNKNOWN) {
            return;
        }
        sb.append(' ').append('"').append(provenance.name()).append('"');
    }

    private static String escapeString(String s) {
        var sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
