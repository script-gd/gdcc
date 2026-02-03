package dev.superice.gdcc.exception;

public class InvalidInsnException extends GdccException {
    public InvalidInsnException(String message) {
        super(message);
    }

    public InvalidInsnException(String func, String block, int index, String insn, String reason) {
        super("Invalid instruction in function '" + func + "', block '" + block + "', index " + index +
              " (" + insn + "): " + reason);
    }
}
