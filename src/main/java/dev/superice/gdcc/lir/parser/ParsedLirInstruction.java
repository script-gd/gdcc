package dev.superice.gdcc.lir.parser;

import dev.superice.gdcc.enums.GdInstruction;
import dev.superice.gdcc.exception.LirInsnParsingException;
import dev.superice.gdcc.lir.LirInstruction;
import dev.superice.gdcc.lir.LirInstruction.*;
import dev.superice.gdcc.lir.insn.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/// Parsed representation of a LIR instruction and a converter to concrete instruction types.
public record ParsedLirInstruction(
        @Nullable String resultId,
        @NotNull GdInstruction opcode,
        @NotNull List<Operand> operands,
        int lineNumber,
        int columnNumber,
        @NotNull String lirLine
) {

    public @NotNull LirInstruction toConcrete() {
        try {
            return switch (opcode) {
                case LITERAL_BOOL -> {
                    var v = (BooleanOperand) operands.get(0);
                    yield new LiteralBoolInsn(resultId, v.value());
                }
                case LITERAL_INT -> {
                    var v = (IntOperand) operands.get(0);
                    yield new LiteralIntInsn(resultId, v.value());
                }
                case LITERAL_FLOAT -> {
                    var v = (FloatOperand) operands.get(0);
                    yield new LiteralFloatInsn(resultId, v.value());
                }
                case LITERAL_STRING -> {
                    var v = (StringOperand) operands.get(0);
                    yield new LiteralStringInsn(resultId, v.value());
                }
                case LITERAL_STRING_NAME -> {
                    var v = (StringOperand) operands.get(0);
                    yield new LiteralStringNameInsn(resultId, v.value());
                }
                case LITERAL_NULL -> new LiteralNullInsn(resultId);

                case CONSTRUCT_BUILTIN -> new ConstructBuiltinInsn(resultId, List.copyOf(operands));
                case CONSTRUCT_ARRAY -> {
                    String cls = null;
                    if (!operands.isEmpty()) cls = ((StringOperand) operands.get(0)).value();
                    yield new ConstructArrayInsn(resultId, cls);
                }
                case CONSTRUCT_DICTIONARY -> {
                    String k = null, v = null;
                    if (!operands.isEmpty()) k = ((StringOperand) operands.get(0)).value();
                    if (operands.size() > 1) v = ((StringOperand) operands.get(1)).value();
                    yield new ConstructDictionaryInsn(resultId, k, v);
                }
                case CONSTRUCT_OBJECT -> {
                    var cls = ((StringOperand) operands.get(0)).value();
                    yield new ConstructObjectInsn(resultId, cls);
                }
                case CONSTRUCT_CALLABLE -> {
                    var fn = ((StringOperand) operands.get(0)).value();
                    yield new ConstructCallableInsn(resultId, fn);
                }
                case CONSTRUCT_LAMBDA -> {
                    var lambdaName = ((StringOperand) operands.get(0)).value();
                    var captures = new ArrayList<Operand>();
                    for (int i = 1; i < operands.size(); i++) captures.add(operands.get(i));
                    yield new ConstructLambdaInsn(resultId, lambdaName, captures);
                }

                case DESTRUCT -> {
                    var id = ((VariableOperand) operands.getFirst()).id();
                    yield new DestructInsn(id);
                }
                case UNARY_OP -> {
                    var op = ((GdOperatorOperand) operands.get(0)).operator();
                    var id = ((VariableOperand) operands.get(1)).id();
                    yield new UnaryOpInsn(resultId, op, id);
                }
                case BINARY_OP -> {
                    var op = ((GdOperatorOperand) operands.get(0)).operator();
                    var left = ((VariableOperand) operands.get(1)).id();
                    var right = ((VariableOperand) operands.get(2)).id();
                    yield new BinaryOpInsn(resultId, op, left, right);
                }

                case VARIANT_GET -> new VariantGetInsn(resultId, ((VariableOperand) operands.get(0)).id(), ((VariableOperand) operands.get(1)).id());
                case VARIANT_GET_KEYED -> new VariantGetKeyedInsn(resultId, ((VariableOperand) operands.get(0)).id(), ((VariableOperand) operands.get(1)).id());
                case VARIANT_GET_NAMED -> new VariantGetNamedInsn(resultId, ((VariableOperand) operands.get(0)).id(), ((StringOperand) operands.get(1)).value());
                case VARIANT_GET_INDEXED -> new VariantGetIndexedInsn(resultId, ((VariableOperand) operands.get(0)).id(), ((IntOperand) operands.get(1)).value());
                case VARIANT_SET -> new VariantSetInsn(((VariableOperand) operands.get(0)).id(), ((VariableOperand) operands.get(1)).id(), ((VariableOperand) operands.get(2)).id());
                case VARIANT_SET_KEYED -> new VariantSetKeyedInsn(((VariableOperand) operands.get(0)).id(), ((VariableOperand) operands.get(1)).id(), ((VariableOperand) operands.get(2)).id());
                case VARIANT_SET_NAMED -> new VariantSetNamedInsn(((VariableOperand) operands.get(0)).id(), ((StringOperand) operands.get(1)).value(), ((VariableOperand) operands.get(2)).id());
                case VARIANT_SET_INDEXED -> new VariantSetIndexedInsn(((VariableOperand) operands.get(0)).id(), ((IntOperand) operands.get(1)).value(), ((VariableOperand) operands.get(2)).id());

                case GET_VARIANT_TYPE -> new GetVariantTypeInsn(resultId, ((VariableOperand) operands.get(0)).id());
                case GET_CLASS_NAME -> new GetClassNameInsn(resultId, ((VariableOperand) operands.get(0)).id());
                case OBJECT_CAST -> new ObjectCastInsn(resultId, ((StringOperand) operands.get(0)).value(), ((VariableOperand) operands.get(1)).id());
                case IS_INSTANCE_OF -> new IsInstanceOfInsn(resultId, ((StringOperand) operands.get(0)).value(), ((VariableOperand) operands.get(1)).id());
                case PACK_VARIANT -> new PackVariantInsn(resultId, ((VariableOperand) operands.get(0)).id());
                case UNPACK_VARIANT -> new UnpackVariantInsn(resultId, ((VariableOperand) operands.get(0)).id());
                case VARIANT_IS_NULL -> new VariantIsNullInsn(resultId, ((VariableOperand) operands.get(0)).id());
                case OBJECT_IS_NULL -> new ObjectIsNullInsn(resultId, ((VariableOperand) operands.get(0)).id());

                case GOTO -> new GotoInsn(((BasicBlockOperand) operands.get(0)).bbId());
                case GO_IF -> new GoIfInsn(((VariableOperand) operands.get(0)).id(), ((BasicBlockOperand) operands.get(1)).bbId(), ((BasicBlockOperand) operands.get(2)).bbId());
                case RETURN -> {
                    if (operands.isEmpty()) yield new ReturnInsn(null);
                    yield new ReturnInsn(((VariableOperand) operands.get(0)).id());
                }

                case CALL_GLOBAL -> {
                    var fname = ((StringOperand) operands.get(0)).value();
                    var args = new ArrayList<Operand>();
                    for (int i = 1; i < operands.size(); i++) args.add(operands.get(i));
                    yield new CallGlobalInsn(resultId, fname, args);
                }
                case CALL_METHOD -> {
                    var mname = ((StringOperand) operands.get(0)).value();
                    var obj = ((VariableOperand) operands.get(1)).id();
                    var args = new ArrayList<Operand>();
                    for (int i = 2; i < operands.size(); i++) args.add(operands.get(i));
                    yield new CallMethodInsn(resultId, mname, obj, args);
                }
                case CALL_SUPER_METHOD -> {
                    var mname = ((StringOperand) operands.get(0)).value();
                    var obj = ((VariableOperand) operands.get(1)).id();
                    var args = new ArrayList<Operand>();
                    for (int i = 2; i < operands.size(); i++) args.add(operands.get(i));
                    yield new CallSuperMethodInsn(resultId, mname, obj, args);
                }
                case CALL_STATIC_METHOD -> {
                    var cname = ((StringOperand) operands.get(0)).value();
                    var mname = ((StringOperand) operands.get(1)).value();
                    var args = new ArrayList<Operand>();
                    for (int i = 2; i < operands.size(); i++) args.add(operands.get(i));
                    yield new CallStaticMethodInsn(resultId, cname, mname, args);
                }
                case CALL_INTRINSIC -> {
                    var iname = ((StringOperand) operands.get(0)).value();
                    var args = new ArrayList<Operand>();
                    for (int i = 1; i < operands.size(); i++) args.add(operands.get(i));
                    yield new CallIntrinsicInsn(resultId, iname, args);
                }

                case LOAD_PROPERTY -> new LoadPropertyInsn(resultId, ((StringOperand) operands.get(0)).value(), ((VariableOperand) operands.get(1)).id());
                case STORE_PROPERTY -> new StorePropertyInsn(((StringOperand) operands.get(0)).value(), ((VariableOperand) operands.get(1)).id(), ((VariableOperand) operands.get(2)).id());
                case LOAD_STATIC -> new LoadStaticInsn(resultId, ((StringOperand) operands.get(0)).value(), ((StringOperand) operands.get(1)).value());
                case STORE_STATIC -> new StoreStaticInsn(((StringOperand) operands.get(0)).value(), ((StringOperand) operands.get(1)).value(), ((VariableOperand) operands.get(2)).id());

                case NOP -> new NopInsn();
                case LINE_NUMBER -> new LineNumberInsn(((IntOperand) operands.get(0)).value());
            };
        } catch (IndexOutOfBoundsException | ClassCastException e) {
            throw new LirInsnParsingException(lineNumber, columnNumber, lirLine, "Error converting parsed instruction to concrete type: " + e.getMessage());
        }
    }
}
