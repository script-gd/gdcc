package gd.script.gdcc.backend.c.build;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Behavior of the real [TinyCcLibrary] FFM binding, gated on a resolvable tinycc bundle
/// (`GDCC_TINYCC_HOME` or the built `build/tinycc-bundle/<platformKey>/`): symbol binding
/// completeness (a missing export fails the load), the diagnostic upcall's copy semantics (the
/// native message buffer is freed by libtcc when the callback returns, so only a full copy can
/// survive), and the process-lifetime cache contract (failures are never cached, a success is
/// published exactly once).
///
/// Every state gets an explicit `tcc_set_lib_path(bundleRoot)`: the Windows bundle is built
/// with predefs disabled, so libtcc reads `tccdefs.h` from `{B}/include` at runtime, and the
/// default `{B}` resolves to the libtcc.dll directory (`bin/`) — not the bundle root. The Linux
/// bundle embeds its predefs, which would silently mask a missing lib path there.
class TinyCcLibraryTest {

    /// `TCC_OUTPUT_MEMORY` from libtcc.h: the lightest output type for compile-only probes.
    /// libtcc only allocates its sections (including the symbol table) inside
    /// `tcc_set_output_type` — compiling without it dereferences a NULL symtab, so every probe
    /// here sets an output type first (the compiler rounds use TCC_OUTPUT_DLL the same way).
    private static final int TCC_OUTPUT_MEMORY = 1;

    private static Path bundleRoot;
    private static Path libraryPath;

    @BeforeAll
    static void requireBundle() {
        bundleRoot = TinyCcCliTestSupport.findTinyCcBundleRoot();
        Assumptions.assumeTrue(bundleRoot != null,
                "no tinycc bundle available (GDCC_TINYCC_HOME unset, no build/tinycc-bundle); skipping FFM binding tests");
        libraryPath = TinyCcBundle.libraryPath(bundleRoot, TargetPlatform.getNativePlatform());
    }

    @BeforeEach
    void resetCache() {
        // Every test sees a defined cache state; a previously loaded library stays mapped but
        // unreferenced, which is harmless for these assertions.
        TinyCcLibrary.clearCachedLibraryForTesting();
    }

    @Test
    void bindsEverySymbolAndServesTheBasicCalls() throws IOException {
        var library = TinyCcLibrary.require(libraryPath);
        try (var arena = Arena.ofConfined()) {
            var state = library.newState();
            assertNotNull(state, "tcc_new must return a usable state");
            library.setLibPath(state, arena.allocateFrom(bundleRoot.toString()));
            // -std=c11 is the fixed dialect of every gdcc tinycc round.
            assertEquals(0, library.setOptions(state, arena.allocateFrom("-std=c11")));
            assertEquals(0, library.setOutputType(state, TinyCcLibrary.TCC_OUTPUT_DLL));
            assertEquals(0, library.addIncludePath(state, arena.allocateFrom(".")));
            assertEquals(0, library.compileString(state, arena.allocateFrom("int gdcc_binding_probe(void) { return 0; }")),
                    "a valid translation unit must compile cleanly");
            library.delete(state);
        }
    }

    @Test
    void diagnosticCallbackReceivesFullyCopiedMessages() throws IOException {
        var library = TinyCcLibrary.require(libraryPath);
        var messages = new ArrayList<String>();
        var failure = new AtomicReference<Throwable>();
        try (var arena = Arena.ofConfined()) {
            var state = library.newState();
            assertNotNull(state);
            library.setLibPath(state, arena.allocateFrom(bundleRoot.toString()));
            assertEquals(0, library.setOutputType(state, TCC_OUTPUT_MEMORY));
            library.setErrorFunc(state, library.newErrorFuncStub(arena, messages::add, failure));
            var status = library.compileString(state, arena.allocateFrom("int broken("));
            assertTrue(status != 0, "an invalid translation unit must fail");
            library.delete(state);
        }
        assertNull(failure.get(), "the upcall machinery itself must not fail");
        assertFalse(messages.isEmpty(), "a failed compile must emit at least one diagnostic");
        // The assertion that matters: the message is a complete Java String copy of the native
        // diagnostic (libtcc frees the native buffer when the callback returns), so it still
        // carries the full text here, after the downcall has completed.
        assertTrue(messages.getFirst().contains("error"), () -> "unexpected diagnostic text: " + messages);
    }

    @Test
    void diagnosticCallbackCopiesLongMessagesWithoutTruncation() throws IOException {
        var library = TinyCcLibrary.require(libraryPath);
        var longIdentifier = "gdcc_undeclared_" + "x".repeat(4000);
        var messages = new ArrayList<String>();
        var failure = new AtomicReference<Throwable>();
        try (var arena = Arena.ofConfined()) {
            var state = library.newState();
            assertNotNull(state);
            library.setLibPath(state, arena.allocateFrom(bundleRoot.toString()));
            assertEquals(0, library.setOutputType(state, TCC_OUTPUT_MEMORY));
            library.setErrorFunc(state, library.newErrorFuncStub(arena, messages::add, failure));
            var status = library.compileString(state, arena.allocateFrom("int f(void) { return " + longIdentifier + "; }"));
            assertTrue(status != 0);
            library.delete(state);
        }
        assertNull(failure.get());
        assertFalse(messages.isEmpty());
        assertTrue(messages.stream().anyMatch(message -> message.contains(longIdentifier)),
                () -> "a 4k-character diagnostic must survive the native→Java copy intact, got: " + messages);
    }

    @Test
    void diagnosticCallbackFiresPerStateAndPerFailure() throws IOException {
        var library = TinyCcLibrary.require(libraryPath);
        var messages = new ArrayList<String>();
        var failure = new AtomicReference<Throwable>();
        try (var arena = Arena.ofConfined()) {
            for (var broken : List.of("int broken_a(", "int broken_b(")) {
                var state = library.newState();
                assertNotNull(state);
                library.setLibPath(state, arena.allocateFrom(bundleRoot.toString()));
                assertEquals(0, library.setOutputType(state, TCC_OUTPUT_MEMORY));
                library.setErrorFunc(state, library.newErrorFuncStub(arena, messages::add, failure));
                assertTrue(library.compileString(state, arena.allocateFrom(broken)) != 0);
                library.delete(state);
            }
        }
        assertNull(failure.get());
        assertEquals(2, messages.size(), () -> "each failed compile fires its own diagnostic: " + messages);
    }

    @Test
    void handlerFailureIsRecordedAndNeverCrossesTheNativeFrame() throws IOException {
        var library = TinyCcLibrary.require(libraryPath);
        var failure = new AtomicReference<Throwable>();
        var handler = new TinyCcNative.ErrorHandler() {
            @Override
            public void onError(@NotNull String message) {
                throw new RuntimeException("handler exploded on: " + message);
            }
        };
        int status;
        try (var arena = Arena.ofConfined()) {
            var state = library.newState();
            assertNotNull(state);
            library.setLibPath(state, arena.allocateFrom(bundleRoot.toString()));
            assertEquals(0, library.setOutputType(state, TCC_OUTPUT_MEMORY));
            library.setErrorFunc(state, library.newErrorFuncStub(arena, handler, failure));
            // The throwing handler must not make the downcall itself throw.
            status = library.compileString(state, arena.allocateFrom("int broken("));
            library.delete(state);
        }
        assertTrue(status != 0);
        var recorded = failure.get();
        assertNotNull(recorded, "a throwing handler must surface through the failure sink");
        assertTrue(String.valueOf(recorded.getMessage()).contains("handler exploded on:"),
                () -> "the recorded failure should carry the handler's exception: " + recorded);
    }

    @Test
    void loadFailureIsNotCachedAndSuccessIsPublishedOnce() throws IOException {
        var missing = Path.of("does-not-exist").resolve("libtcc-missing.so");
        assertThrows(IOException.class, () -> TinyCcLibrary.require(missing),
                "loading a nonexistent library must fail");
        var first = TinyCcLibrary.require(libraryPath);
        var second = TinyCcLibrary.require(libraryPath);
        assertSame(first, second, "a successful load is published exactly once and reused");
    }
}
