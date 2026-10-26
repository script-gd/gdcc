package gd.script.gdcc.util;

import com.github.abrarsyed.jastyle.ASFormatter;
import com.github.abrarsyed.jastyle.constants.EnumFormatStyle;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;

public final class CCodeFormatter {
    private CCodeFormatter() {
    }

    /// Formats a C code snippet with basic spacing and brace indentation.
    public static @NotNull String format(@NotNull String code) {
        var formatter = new ASFormatter();
        formatter.setFormattingStyle(EnumFormatStyle.LINUX);
        formatter.setTabSpaceConversionMode(true);
        var outputWriter = new StringWriter((int) (code.length() * 1.1));
        try {
            formatter.format(new StringReader(code), outputWriter);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        // jAstyle terminates each line via PrintWriter.println, which uses the platform
        // line separator; normalize to LF so generated sources are identical everywhere.
        return outputWriter.toString().replace("\r\n", "\n");
    }
}

