package dev.superice.gdcc.enums;

import java.util.List;

/// Enum describing Low IR instruction kinds and their metadata.
/// Each enum constant records the textual opcode, whether the instruction
/// produces a result (NONE / OPTIONAL / REQUIRED), the expected operand
/// kinds (a pattern: fixed operands followed by VARARGS if present), and
/// the minimum/maximum operand counts.
public enum GdInstruction {
    // New Data
    LITERAL_BOOL("literal_bool", ReturnKind.REQUIRED, List.of(OperandKind.BOOL), 1, 1),
    LITERAL_INT("literal_int", ReturnKind.REQUIRED, List.of(OperandKind.INT), 1, 1),
    LITERAL_FLOAT("literal_float", ReturnKind.REQUIRED, List.of(OperandKind.FLOAT), 1, 1),
    LITERAL_STRING("literal_string", ReturnKind.REQUIRED, List.of(OperandKind.STRING), 1, 1),
    LITERAL_STRING_NAME("literal_string_name", ReturnKind.REQUIRED, List.of(OperandKind.STRING), 1, 1),
    LITERAL_NULL("literal_null", ReturnKind.REQUIRED, List.of()),
    LITERAL_NIL("literal_nil", ReturnKind.REQUIRED, List.of()),

    // Construction & Destruction
    CONSTRUCT_BUILTIN("construct_builtin", ReturnKind.REQUIRED, List.of(OperandKind.VARIABLE, OperandKind.VARARGS), 0, Integer.MAX_VALUE),
    CONSTRUCT_ARRAY("construct_array", ReturnKind.REQUIRED, List.of(OperandKind.STRING), 0, 1),
    CONSTRUCT_DICTIONARY("construct_dictionary", ReturnKind.REQUIRED, List.of(OperandKind.STRING, OperandKind.STRING), 0, 2),
    CONSTRUCT_OBJECT("construct_object", ReturnKind.REQUIRED, List.of(OperandKind.STRING), 1, 1),
    CONSTRUCT_CALLABLE("construct_callable", ReturnKind.REQUIRED, List.of(OperandKind.STRING), 1, 1),
    CONSTRUCT_LAMBDA("construct_lambda", ReturnKind.REQUIRED, List.of(OperandKind.STRING, OperandKind.VARARGS), 1, Integer.MAX_VALUE),
    DESTRUCT("destruct", ReturnKind.NONE, List.of(OperandKind.VARIABLE, OperandKind.STRING), 1, 2),
    TRY_OWN_OBJECT("try_own_object", ReturnKind.NONE, List.of(OperandKind.VARIABLE, OperandKind.STRING), 1, 2),
    TRY_RELEASE_OBJECT("try_release_object", ReturnKind.NONE, List.of(OperandKind.VARIABLE, OperandKind.STRING), 1, 2),
    UNARY_OP("unary_op", ReturnKind.REQUIRED, List.of(OperandKind.OPERATOR, OperandKind.VARIABLE), 2, 2),
    BINARY_OP("binary_op", ReturnKind.REQUIRED, List.of(OperandKind.OPERATOR, OperandKind.VARIABLE, OperandKind.VARIABLE), 3, 3),

    // Indexing
    VARIANT_GET("variant_get", ReturnKind.REQUIRED, List.of(OperandKind.VARIABLE, OperandKind.VARIABLE), 2, 2),
    VARIANT_GET_KEYED("variant_get_keyed", ReturnKind.REQUIRED, List.of(OperandKind.VARIABLE, OperandKind.VARIABLE), 2, 2),
    VARIANT_GET_NAMED("variant_get_named", ReturnKind.REQUIRED, List.of(OperandKind.VARIABLE, OperandKind.STRING), 2, 2),
    VARIANT_GET_INDEXED("variant_get_indexed", ReturnKind.REQUIRED, List.of(OperandKind.VARIABLE, OperandKind.INT), 2, 2),
    VARIANT_SET("variant_set", ReturnKind.NONE, List.of(OperandKind.VARIABLE, OperandKind.VARIABLE, OperandKind.VARIABLE), 3, 3),
    VARIANT_SET_KEYED("variant_set_keyed", ReturnKind.NONE, List.of(OperandKind.VARIABLE, OperandKind.VARIABLE, OperandKind.VARIABLE), 3, 3),
    VARIANT_SET_NAMED("variant_set_named", ReturnKind.NONE, List.of(OperandKind.VARIABLE, OperandKind.STRING, OperandKind.VARIABLE), 3, 3),
    VARIANT_SET_INDEXED("variant_set_indexed", ReturnKind.NONE, List.of(OperandKind.VARIABLE, OperandKind.INT, OperandKind.VARIABLE), 3, 3),

    // Type Instructions
    GET_VARIANT_TYPE("get_variant_type", ReturnKind.REQUIRED, List.of(OperandKind.VARIABLE), 1, 1),
    GET_CLASS_NAME("get_class_name", ReturnKind.REQUIRED, List.of(OperandKind.VARIABLE), 1, 1),
    OBJECT_CAST("object_cast", ReturnKind.OPTIONAL, List.of(OperandKind.STRING, OperandKind.VARIABLE), 2, 2),
    IS_INSTANCE_OF("is_instance_of", ReturnKind.REQUIRED, List.of(OperandKind.STRING, OperandKind.VARIABLE), 2, 2),
    PACK_VARIANT("pack_variant", ReturnKind.REQUIRED, List.of(OperandKind.VARIABLE), 1, 1),
    UNPACK_VARIANT("unpack_variant", ReturnKind.REQUIRED, List.of(OperandKind.VARIABLE), 1, 1),
    VARIANT_IS_NIL("variant_is_nil", ReturnKind.REQUIRED, List.of(OperandKind.VARIABLE), 1, 1),
    OBJECT_IS_NULL("object_is_null", ReturnKind.REQUIRED, List.of(OperandKind.VARIABLE), 1, 1),

    // Control Flow
    GOTO("goto", ReturnKind.NONE, List.of(OperandKind.LABEL), 1, 1),
    GO_IF("go_if", ReturnKind.NONE, List.of(OperandKind.VARIABLE, OperandKind.LABEL, OperandKind.LABEL), 3, 3),
    RETURN("return", ReturnKind.NONE, List.of(OperandKind.VARIABLE), 0, 1),

    // Call Instructions
    CALL_GLOBAL("call_global", ReturnKind.OPTIONAL, List.of(OperandKind.STRING, OperandKind.VARARGS), 1, Integer.MAX_VALUE),
    CALL_METHOD("call_method", ReturnKind.OPTIONAL, List.of(OperandKind.STRING, OperandKind.VARIABLE, OperandKind.VARARGS), 2, Integer.MAX_VALUE),
    CALL_SUPER_METHOD("call_super_method", ReturnKind.OPTIONAL, List.of(OperandKind.STRING, OperandKind.VARIABLE, OperandKind.VARARGS), 2, Integer.MAX_VALUE),
    CALL_STATIC_METHOD("call_static_method", ReturnKind.OPTIONAL, List.of(OperandKind.STRING, OperandKind.STRING, OperandKind.VARARGS), 2, Integer.MAX_VALUE),
    CALL_INTRINSIC("call_intrinsic", ReturnKind.OPTIONAL, List.of(OperandKind.STRING, OperandKind.VARARGS), 1, Integer.MAX_VALUE),

    // Load/Store
    LOAD_PROPERTY("load_property", ReturnKind.REQUIRED, List.of(OperandKind.STRING, OperandKind.VARIABLE), 2, 2),
    STORE_PROPERTY("store_property", ReturnKind.NONE, List.of(OperandKind.STRING, OperandKind.VARIABLE, OperandKind.VARIABLE), 3, 3),
    LOAD_STATIC("load_static", ReturnKind.REQUIRED, List.of(OperandKind.STRING, OperandKind.STRING), 2, 2),
    STORE_STATIC("store_static", ReturnKind.NONE, List.of(OperandKind.STRING, OperandKind.STRING, OperandKind.VARIABLE), 3, 3),

    // Misc
    NOP("nop", ReturnKind.NONE, List.of(), 0, 0),
    LINE_NUMBER("line_number", ReturnKind.NONE, List.of(OperandKind.INT), 1, 1),
    ;

    private final String opcode;
    private final ReturnKind returnKind;
    private final List<OperandKind> operandKinds; // pattern: fixed kinds followed by VARARGS if present
    private final int minOperands;
    private final int maxOperands;

    GdInstruction(String opcode, ReturnKind returnKind, List<OperandKind> operandKinds, int minOperands, int maxOperands) {
        this.opcode = opcode;
        this.returnKind = returnKind;
        this.operandKinds = operandKinds;
        this.minOperands = minOperands;
        this.maxOperands = maxOperands;
    }

    // convenience ctor for zero-operand items
    GdInstruction(String opcode, ReturnKind returnKind, List<OperandKind> operandKinds) {
        this(opcode, returnKind, operandKinds, operandKinds.size(), operandKinds.size());
    }

    public String opcode() { return opcode; }
    public ReturnKind returnKind() { return returnKind; }
    public List<OperandKind> operandKinds() { return operandKinds; }
    public int minOperands() { return minOperands; }
    public int maxOperands() { return maxOperands; }

    public enum ReturnKind {
        NONE,
        OPTIONAL,
        REQUIRED
    }

    public enum OperandKind {
        VARIABLE,
        STRING,
        INT,
        FLOAT,
        OPERATOR,
        LABEL,
        BOOL,
        VARARGS,
        GENERIC
    }
}
