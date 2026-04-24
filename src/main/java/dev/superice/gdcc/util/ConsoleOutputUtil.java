package dev.superice.gdcc.util;

import org.jetbrains.annotations.NotNull;

import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.util.Objects;

public final class ConsoleOutputUtil {
    private ConsoleOutputUtil() {
    }

    public static @NotNull PrintWriter stdoutWriter() {
        return writerFor(System.out);
    }

    public static @NotNull PrintWriter stderrWriter() {
        return writerFor(System.err);
    }

    public static @NotNull PrintWriter writerFor(@NotNull PrintStream stream) {
        return new PrintWriter(new EncodingSafeWriter(stream), true);
    }

    public static void println(@NotNull PrintStream stream, @NotNull String line) {
        stream.println(encodeFor(stream.charset(), line));
        stream.flush();
    }

    static @NotNull String encodeFor(@NotNull Charset charset, @NotNull String text) {
        var encoder = Objects.requireNonNull(charset, "charset must not be null").newEncoder();
        var out = new StringBuilder(text.length());
        for (var i = 0; i < text.length(); ) {
            var codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);
            var token = new String(Character.toChars(codePoint));
            if (encoder.canEncode(token)) {
                out.append(token);
            } else if (codePoint <= 0xFFFF) {
                out.append("\\u").append(String.format("%04X", codePoint));
            } else {
                out.append("\\U").append(String.format("%08X", codePoint));
            }
        }
        return out.toString();
    }

    private static final class EncodingSafeWriter extends Writer {
        private final @NotNull PrintStream stream;
        private final @NotNull Charset charset;

        private EncodingSafeWriter(@NotNull PrintStream stream) {
            this.stream = Objects.requireNonNull(stream, "stream must not be null");
            charset = stream.charset();
        }

        @Override
        public void write(char @NotNull [] cbuf, int off, int len) {
            writeEncoded(new String(cbuf, off, len));
        }

        @Override
        public void write(@NotNull String str, int off, int len) {
            writeEncoded(str.substring(off, off + len));
        }

        @Override
        public void write(int c) {
            writeEncoded(String.valueOf((char) c));
        }

        private void writeEncoded(@NotNull String text) {
            stream.print(encodeFor(charset, text));
        }

        @Override
        public void flush() {
            stream.flush();
        }

        @Override
        public void close() {
            flush();
        }
    }
}
