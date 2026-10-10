package gd.script.gdcc.backend.c.build;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies transparent u8 prefix support with a CLI rebuilt from the vendored TinyCC sources.
class TinyCcU8StringTest {
    @TempDir
    private Path tempDir;

    @ParameterizedTest
    @ValueSource(strings = {"", "ASCII", "\u00E9\u4E2D\u6587\uD83D\uDE00", "\\u00E9\\U0001F600",
            "\\303\\2517\\377\\x80", "a\\0b\\n\\t\\\"\\\\"})
    void prefixedLiteralShouldMatchOrdinaryLiteral(String body) throws IOException, InterruptedException {
        var source = """
                #include <string.h>
                _Static_assert(_Generic(u8"", char *: 1, default: 0), "ordinary char array");
                _Static_assert(sizeof(L"wide") == 5 * sizeof(L""[0]), "wide strings unchanged");
                int main(void) {
                    const char plain[] = "%s";
                    const char prefixed[] = u8"%s";
                    const char joined[] = "left" u8"middle" u8"" "right";
                    return sizeof(plain) != sizeof(prefixed)
                        || memcmp(plain, prefixed, sizeof(plain))
                        || strcmp(joined, "leftmiddleright");
                }
                """.formatted(body, body);
        compileAndRun(source);
    }

    @Test
    void preprocessingShouldPreservePrefixAndOrdinaryIdentifiers() throws IOException, InterruptedException {
        var source = """
                #include <string.h>
                #define STRINGIFY(x) #x
                #define PREFIX(x) u8 ## x
                #define u8 "macro"
                const char *direct = u8"keep";
                const char *spelling = STRINGIFY(u8"keep");
                const char *pasted = PREFIX("paste");
                const char *spaced = u8 "tail";
                const char *commented = u8/**/"tail";
                #undef u8
                int main(void) {
                    int u8 = 7, u8_name = 5, U8 = 3;
                    return strcmp(direct, "keep")
                        || strcmp(spelling, "u8\\"keep\\"")
                        || strcmp(pasted, "paste")
                        || strcmp(spaced, "macrotail")
                        || strcmp(commented, "macrotail")
                        || u8 + u8_name + U8 != 15;
                }
                """;
        var preprocessed = runChecked(tccCommand(source, List.of("-E")));
        assertTrue(preprocessed.contains("u8\"keep\""), preprocessed);
        assertTrue(preprocessed.contains("u8\"paste\""), preprocessed);
        assertTrue(preprocessed.contains("\"u8\\\"keep\\\"\""), preprocessed);
        compileAndRun(source);
    }

    @ParameterizedTest
    @ValueSource(strings = {"u\\\n8", "u8\\\n", "u\\\r\n8\\\r\n"})
    void prefixShouldAllowLineSplicing(String prefix) throws IOException, InterruptedException {
        var source = """
                #include <string.h>
                int main(void) {
                    const char text[] = %s"splice";
                    return sizeof(text) != sizeof("splice") || strcmp(text, "splice");
                }
                """.formatted(prefix);
        compileAndRun(source);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void prefixShouldCrossInputBufferBoundary(int prefixBytesInFirstBuffer) throws IOException, InterruptedException {
        var leading = "int main(void) { const char text[] = ";
        // TinyCC reads source files in 8192-byte buffers; split each position of u8" across one.
        var source = leading + " ".repeat(8192 - leading.length() - prefixBytesInFirstBuffer)
                + "u8\"boundary\"; return sizeof(text) != 9 || text[0] != 'b' || text[8] != 0; }\n";
        compileAndRun(source);
    }

    @Test
    void characterConstantShouldNotGainU8PrefixSupport() throws IOException, InterruptedException {
        var command = tccCommand("int main(void) { int value = u8'a'; return value != 'a'; }\n",
                List.of("-c", "-o", tempDir.resolve("unsupported.o").toString()));
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertNotEquals(0, process.waitFor(), output);
        assertTrue(output.contains("'u8' undeclared"), output);
    }

    private void compileAndRun(String source) throws IOException, InterruptedException {
        var executable = tempDir.resolve("u8-literal.exe");
        assertTrue(runChecked(tccCommand(source, List.of("-o", executable.toString()))).isBlank());
        assertTrue(runChecked(List.of(executable.toString())).isBlank());
    }

    private static String runChecked(List<String> command) throws IOException, InterruptedException {
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), () -> String.join(" ", command) + "\n" + output);
        return output;
    }

    private List<String> tccCommand(String source, List<String> options) throws IOException {
        var tcc = TinyCcCliTestSupport.findTinyCcCli();
        Assumptions.assumeTrue(tcc != null, "a tinycc CLI rebuilt from the vendored sources is required");
        var runtimeRoot = TinyCcCliTestSupport.resolveRuntimeRoot(tcc);
        var sourceFile = Files.createTempFile(tempDir, "u8-literal-", ".c");
        Files.writeString(sourceFile, source, StandardCharsets.UTF_8);
        var command = new ArrayList<String>();
        command.add(tcc.toString());
        if (runtimeRoot != null) {
            command.addAll(List.of("-B", runtimeRoot.toString()));
        }
        command.add("-std=c11");
        command.addAll(options);
        command.add(sourceFile.toString());
        return command;
    }
}
