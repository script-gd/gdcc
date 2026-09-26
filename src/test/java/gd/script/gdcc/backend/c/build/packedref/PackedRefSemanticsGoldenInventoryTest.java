package gd.script.gdcc.backend.c.build.packedref;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Godot-independent anchor for the Packed*Array reference-semantics case inventory.
///
/// The golden file owns case PAYLOADS, but its inventory (which cases exist, in which order)
/// is the contract this suite exists to lock: a probe call and its golden line removed
/// together would silently shrink coverage, and the golden-only dual-run comparison
/// structurally cannot detect that. This check lives outside the dual-run test class on
/// purpose — that class skips entirely when `GODOT_BIN` is missing, while this resource-only
/// assertion runs in every environment.
class PackedRefSemanticsGoldenInventoryTest {

    /// The reference-semantics behavior inventory (semantics matrix scenarios plus the dynamic
    /// Variant receiver case, the mixed scenario, and the supplementary cases), in
    /// `PackedRefProbes.run_all` emission order.
    private static final List<String> MATRIX_CASE_NAMES = List.of(
            "LOCAL_ALIAS",                      // local alias sharing
            "PARAM_VISIBILITY",                 // parameter mutation visible to the caller
            "SCRIPT_PROPERTY",                  // script property mutation persists
            "TYPED_ARRAY_ELEMENT",              // typed Array element mutation persists
            "DICT_VALUE",                       // Dictionary value mutation persists
            "BUILTIN_PROPERTY_MUTATION",        // builtin property getter mutation does not persist
            "BUILTIN_PROPERTY_REASSIGN",        // builtin property reassignment persists
            "BUILTIN_PROPERTY_SUBSCRIPT_WRITE", // builtin property subscript write persists
            "PLUS_EQUALS_REBIND",               // += produces a new array and rebinds
            "DUPLICATE",                        // duplicate() is an independent copy
            "SIGNAL_ARG",                       // signal argument mutation visible to the emitter
            "FOR_ITER",                         // live iteration visits appended elements
            "APPEND_ARRAY_ALIAS",               // append_array shared through aliases
            "RESIZE_ALIAS",                     // resize shared through aliases
            "INDEX_WRITE_ALIAS",                // subscript write shared through aliases
            "VARIANT_IDENTITY",                 // Variant round-trip preserves sharing
            "PARAM_DEFAULT_SHARED",             // parameter default materializes a fresh array per call
            "ELEMENT_REBIND",                   // element slot rebinding keeps the old array alive
            "STRING_ITER_ELEMENTS",             // iteration elements are String copies
            "IN_MEMBERSHIP",                    // `in` matches by content
            "EQUALITY",                         // ==/!= compare by content
            "DICT_KEY_HASH",                    // Dictionary keys hash by content
            "AS_SAME_FAMILY",                   // same-family `as` is a COW copy (fresh identity)
            "CORO_AWAIT",                       // coroutine mutations visible across await
            "SIGNAL_MULTI",                     // multi-argument typed signal sharing
            "DYNAMIC_VARIANT_MUTATION",         // dynamic Variant receiver route
            "STATIC_VAR",                       // static variable mutation persists
            "LAMBDA_CAPTURE",                   // lambda capture sharing
            "MIXED_COMBINATION",                // mixed scenario: property + signal + lambda + coroutine + live iteration
            "CONTROL_FLOW_BRANCHES",            // complex if/elif/match branch mutation
            "RETURN_VALUE_SHARING",             // packed return-value identity forms
            "ENGINE_METHOD_PACKED_ARG",         // packed argument/return at engine methods
            "STRING_ARRAY_MUTATION"             // PackedStringArray mutation/equality
    );

    /// The golden must contain exactly the locked case inventory, in emission order — no
    /// case silently dropped, no unregistered case added. Payload truth still lives only in
    /// the golden file; this anchors coverage.
    @Test
    void goldenCoversExactlyTheLockedMatrixInventory() throws IOException {
        assertEquals(MATRIX_CASE_NAMES, PackedRefSemanticsDualRunHarness.loadGolden().caseNames());
    }
}
