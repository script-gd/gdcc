package gd.script.gdcc.backend.c.build;

import gd.script.gdcc.backend.CodegenContext;
import gd.script.gdcc.backend.c.gen.CCodegen;
import gd.script.gdcc.enums.GodotVersion;
import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.lowering.FrontendLoweringPassManager;
import gd.script.gdcc.frontend.parse.FrontendModule;
import gd.script.gdcc.frontend.parse.GdScriptParserService;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.lir.LirModule;
import gd.script.gdcc.scope.ClassRegistry;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Lowering -> codegen chain anchors for the fixed-call-argument object upcast shape.
/// GDScript is lowered by the real frontend and emitted as C text via `CCodegen.generate()`
/// (no Zig / Godot required). Assertions are scoped per generated C function body so the
/// same-named per-function boundary temps can never satisfy another function's anchor:
/// the upcast temp's ownership emission is pinned for all three RefCountedStatus states of
/// the parameter target type (assign-time own, paired release inside `__finally__`), and the
/// engine-method route is pinned as a same-type passthrough that consumes the temp directly.
public class CallArgumentObjectUpcastCodegenTest {
    private static final Pattern OBJECT_UPCAST_TEMP = Pattern.compile(
            "gdcc_Object_fat_ptr \\$(cfg_boundary_call_fixed_0_upcast_\\d+)"
    );
    private static final Pattern NODE_UPCAST_TEMP = Pattern.compile(
            "gdcc_Node_fat_ptr \\$(cfg_boundary_call_fixed_0_upcast_\\d+)"
    );
    private static final Pattern RESOURCE_UPCAST_TEMP = Pattern.compile(
            "gdcc_Resource_fat_ptr \\$(cfg_boundary_call_fixed_0_upcast_\\d+)"
    );
    private static final String FINALLY_MARKER = "__finally__:";

    @Test
    void fixedCallArgumentUpcastTempOwnershipMatchesTargetRefCountedStatus() throws Exception {
        var lowered = lowerModule(
                "call_argument_object_upcast_chain",
                Path.of("tmp/test/call_argument_object_upcast_chain/upcast_chain_probe.gd"),
                """
                        class_name UpcastChainProbe
                        extends Node
                        
                        class Token extends RefCounted:
                            var count: int = 0
                        
                            func bump() -> int:
                                count += 1
                                return count
                        
                        class CustomRes extends Resource:
                            var tag: int = 0
                        
                        func take_res(res: Resource) -> void:
                            pass
                        
                        func probe_callable(token: Token) -> Callable:
                            return Callable(token, &"bump")
                        
                        func probe_signal(sprite: Sprite2D) -> Signal:
                            return Signal(sprite, &"renamed")
                        
                        func probe_node(sprite: Sprite2D) -> void:
                            add_child(sprite)
                        
                        func probe_resource(res: CustomRes) -> void:
                            take_res(res)
                        """,
                Map.of("UpcastChainProbe", "RuntimeUpcastChainProbe")
        );

        var projectDir = Path.of("tmp/test/call_argument_object_upcast_chain/project");
        Files.createDirectories(projectDir);
        var projectInfo = new CProjectInfo(
                "call_argument_object_upcast_chain",
                GodotVersion.V451,
                projectDir,
                COptimizationLevel.DEBUG,
                TargetPlatform.getNativePlatform()
        );
        var codegen = new CCodegen();
        codegen.prepare(new CodegenContext(projectInfo, lowered.classRegistry()), lowered.module());
        var entrySource = generateEntryC(codegen);

        var callableBody = requireFunctionCBody(entrySource, "RuntimeUpcastChainProbe_probe_callable");
        var signalBody = requireFunctionCBody(entrySource, "RuntimeUpcastChainProbe_probe_signal");
        var nodeBody = requireFunctionCBody(entrySource, "RuntimeUpcastChainProbe_probe_node");
        var resourceBody = requireFunctionCBody(entrySource, "RuntimeUpcastChainProbe_probe_resource");

        var callableTemp = requireSingleMatch(OBJECT_UPCAST_TEMP, callableBody, "Object-typed upcast temp in probe_callable");
        var signalTemp = requireSingleMatch(OBJECT_UPCAST_TEMP, signalBody, "Object-typed upcast temp in probe_signal");
        var nodeTemp = requireSingleMatch(NODE_UPCAST_TEMP, nodeBody, "Node-typed upcast temp in probe_node");
        var resourceTemp = requireSingleMatch(RESOURCE_UPCAST_TEMP, resourceBody, "Resource-typed upcast temp in probe_resource");

        assertAll(
                // UNKNOWN (exact Object target): two-arg try_own at the slot write, exactly one
                // paired try_release inside __finally__; the constructor consumes the temp as
                // a live pointer. The explicit Signal constructor shares the same entry.
                () -> assertTrue(
                        callableBody.contains(
                                "try_own_object(gdcc_Object_fat_ptr_live_object($" + callableTemp
                                        + "), $" + callableTemp + ".instance_id);"
                        ),
                        callableBody
                ),
                () -> assertEquals(
                        1,
                        countOccurrencesAfter(
                                callableBody,
                                "try_release_object(gdcc_Object_fat_ptr_live_object($" + callableTemp
                                        + "), $" + callableTemp + ".instance_id);",
                                FINALLY_MARKER
                        ),
                        callableBody
                ),
                () -> assertTrue(
                        callableBody.contains(
                                "godot_new_Callable_with_Object_StringName(gdcc_Object_fat_ptr_live_object($"
                                        + callableTemp + "), &$"
                        ),
                        callableBody
                ),
                () -> assertTrue(
                        signalBody.contains(
                                "godot_new_Signal_with_Object_StringName(gdcc_Object_fat_ptr_live_object($"
                                        + signalTemp + "), &$"
                        ),
                        signalBody
                ),
                // NO (Node target): the engine method route consumes the temp in the add_child
                // call as a bare fat pointer (same-type passthrough, no cast/live-ptr wrapper)
                // with no ownership operation around it.
                () -> assertTrue(
                        requireConsumingCallLine(nodeBody, "add_child", nodeTemp).contains(", $" + nodeTemp + ","),
                        nodeBody
                ),
                () -> assertFalse(
                        ownershipCallMentions(nodeBody, "gdcc_Node_fat_ptr_live_object", nodeTemp),
                        nodeBody
                ),
                // YES (Resource target): exact one-arg own at the slot write, exactly one paired
                // release inside __finally__.
                () -> assertTrue(
                        resourceBody.contains("own_object(gdcc_Resource_fat_ptr_live_object($" + resourceTemp + "));"),
                        resourceBody
                ),
                () -> assertEquals(
                        1,
                        countOccurrencesAfter(
                                resourceBody,
                                "release_object(gdcc_Resource_fat_ptr_live_object($" + resourceTemp + "));",
                                FINALLY_MARKER
                        ),
                        resourceBody
                )
        );
    }

    private static int countOccurrencesAfter(@NotNull String body, @NotNull String needle, @NotNull String afterMarker) {
        var markerIndex = body.indexOf(afterMarker);
        assertTrue(markerIndex >= 0, () -> "Marker " + afterMarker + " not found in:\n" + body);
        var scope = body.substring(markerIndex);
        var count = 0;
        for (var index = scope.indexOf(needle); index >= 0; index = scope.indexOf(needle, index + needle.length())) {
            count++;
        }
        return count;
    }

    private static @NotNull String requireConsumingCallLine(@NotNull String body, @NotNull String calleeMarker, @NotNull String temp) {
        var lines = body.lines()
                .filter(line -> line.contains(calleeMarker) && line.contains("$" + temp))
                .toList();
        assertFalse(lines.isEmpty(), () -> "No call to " + calleeMarker + " consumes $" + temp + " in:\n" + body);
        return lines.getFirst();
    }

    private static boolean ownershipCallMentions(@NotNull String body, @NotNull String fatPtrWrapper, @NotNull String temp) {
        return body.lines().anyMatch(line ->
                (line.contains("own_object(") || line.contains("release_object("))
                        && line.contains(fatPtrWrapper + "($" + temp + ")")
        );
    }

    private static @NotNull String requireSingleMatch(
            @NotNull Pattern pattern,
            @NotNull String body,
            @NotNull String description
    ) {
        var matches = pattern.matcher(body).results().map(result -> result.group(1)).toList();
        assertEquals(1, matches.size(), () -> "Expected exactly one " + description + " in:\n" + body);
        return matches.getFirst();
    }

    /// Extracts one generated C function body by name via brace matching, so anchors stay
    /// scoped to that function even when boundary temp names repeat across functions.
    private static @NotNull String requireFunctionCBody(@NotNull String entrySource, @NotNull String cFunctionName) {
        var start = entrySource.indexOf(cFunctionName + "(");
        assertTrue(start >= 0, () -> "C function not found: " + cFunctionName + " in:\n" + entrySource);
        var openBrace = entrySource.indexOf('{', start);
        assertTrue(openBrace >= 0, () -> "No body for C function: " + cFunctionName);
        var depth = 0;
        for (var index = openBrace; index < entrySource.length(); index++) {
            var current = entrySource.charAt(index);
            if (current == '{') {
                depth++;
            } else if (current == '}' && --depth == 0) {
                return entrySource.substring(start, index + 1);
            }
        }
        throw new AssertionError("Unbalanced braces after C function: " + cFunctionName);
    }

    private static @NotNull String generateEntryC(@NotNull CCodegen codegen) {
        return codegen.generate().stream()
                .filter(file -> file.filePath().endsWith("entry.c"))
                .findFirst()
                .map(file -> new String(file.contentWriter(), StandardCharsets.UTF_8))
                .orElseThrow(() -> new AssertionError("entry.c not found in generated files"));
    }

    private static @NotNull LoweredFixture lowerModule(
            @NotNull String moduleName,
            @NotNull Path sourcePath,
            @NotNull String source,
            @NotNull Map<String, String> topLevelCanonicalNameMap
    ) throws IOException {
        var parser = new GdScriptParserService();
        var parseDiagnostics = new DiagnosticManager();
        var unit = parser.parseUnit(sourcePath, source, parseDiagnostics);
        assertTrue(parseDiagnostics.isEmpty(), () -> "Unexpected parse diagnostics: " + parseDiagnostics.snapshot());

        var diagnostics = new DiagnosticManager();
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadVersion(GodotVersion.V451));
        var module = new FrontendModule(moduleName, List.of(unit), topLevelCanonicalNameMap);
        var lowered = new FrontendLoweringPassManager().lower(module, classRegistry, diagnostics);

        assertNotNull(lowered, () -> "Lowering returned null with diagnostics: " + diagnostics.snapshot());
        assertFalse(diagnostics.hasErrors(), () -> "Unexpected frontend diagnostics: " + diagnostics.snapshot());
        return new LoweredFixture(lowered, classRegistry);
    }

    private record LoweredFixture(
            @NotNull LirModule module,
            @NotNull ClassRegistry classRegistry
    ) {
    }
}
