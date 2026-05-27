package gd.script.gdcc.backend.c.gen.binding;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GodotBindingGeneratedCScannerTest {
    @Test
    void scanShouldAcceptProvidedModuleLocalLocalDefinitionsAndTypeNames() {
        var missing = GodotBindingGeneratedCScanner.scan(
                Map.of(
                        "entry.h", """
                                #include <godot_binding.h>
                                static inline godot_Node *godot_new_Node(void) {
                                    return NULL;
                                }
                                """,
                        "entry.c", """
                                void use_wrappers(void) {
                                    godot_Window_Flags flags = 0;
                                    godot_Probe_Mode mode = 0;
                                    godot_print(NULL, NULL, 0);
                                    godot_Probe_count(NULL);
                                    godot_new_Node();
                                    godot_Variant_call(NULL, NULL, "source.gd", 7, NULL, 0);
                                    (void)flags;
                                    (void)mode;
                                }
                                """
                ),
                Set.of("godot_print"),
                Set.of("godot_Probe_count")
        );

        assertTrue(missing.isEmpty(), missing.toString());
    }

    @Test
    void checkShouldRejectUnknownWrapperCalls() {
        var failure = assertThrows(
                IllegalStateException.class,
                () -> GodotBindingGeneratedCScanner.check(
                        Map.of("entry.c", "void bad(void) { godot_missing_wrapper(); }"),
                        Set.of(),
                        Set.of()
                )
        );

        assertAll(
                () -> assertTrue(failure.getMessage().contains("Generated C references unknown Godot binding wrappers")),
                () -> assertTrue(failure.getMessage().contains("godot_missing_wrapper"))
        );
    }

    @Test
    void localFunctionDetectionShouldHandlePointerReturnDefinitions() {
        var missing = GodotBindingGeneratedCScanner.scan(
                Map.of("engine_method_binds.h", """
                        static inline godot_Node *godot_new_Node(void) {
                            return NULL;
                        }
                        
                        static inline void use_constructor(void) {
                            godot_new_Node();
                        }
                        """),
                Set.of(),
                Set.of()
        );

        assertTrue(missing.isEmpty(), missing.toString());
    }
}
