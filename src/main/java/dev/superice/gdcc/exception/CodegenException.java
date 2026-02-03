package dev.superice.gdcc.exception;

public class CodegenException extends GdccException {
    public CodegenException(String message) {
        super(message);
    }

    public CodegenException(String message, Throwable cause) {
        super(message, cause);
    }
}
