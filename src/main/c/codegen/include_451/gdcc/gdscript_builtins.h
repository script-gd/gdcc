#ifndef GDSCRIPT_BUILTINS_H
#define GDSCRIPT_BUILTINS_H

#include <godot_binding.h>
#include <stdio.h>

/// GDScript language-level builtins.
///
/// These helpers back GDScript's own language functions/statements (`assert`, `len`, ...), which
/// are registered by the GDScript module rather than the GDExtension API, so no generated
/// `godot_*` wrapper exists for them. All entry points use the `gdcc_` prefix and the
/// "print runtime error + return type default value" failure style shared with the rest of the
/// gdcc runtime.
///
/// Include contract: this header is pulled in by `gdcc_helper.h` right after the
/// `GDCC_PRINT_RUNTIME_ERROR` macro definition (the helpers below depend on it). It must not
/// include `gdcc_helper.h` itself, otherwise the include graph would cycle.

/// Reports a failed user-level `assert` through the shared runtime-error channel.
///
/// `message_or_null` is the optional user message (a `NULL` pointer means the call had no
/// message argument). The caller emits the default-return edge immediately after this call, so
/// this helper only reports and never alters control flow.
static inline void gdcc_assert_failed(
    const godot_String *message_or_null,
    const char *func,
    const char *file,
    int line
) {
    if (message_or_null == NULL) {
        GDCC_PRINT_RUNTIME_ERROR("Assertion failed.", func, file, line);
        return;
    }
    // godot_string_to_utf8_chars returns the FULL UTF-8 byte length of the string, not the
    // number of bytes actually written (it copies at most max_write_length bytes and never
    // NUL-terminates). Clamp to the buffer capacity before terminating.
    char message_utf8[448];
    GDExtensionInt written = godot_string_to_utf8_chars(message_or_null, message_utf8, sizeof(message_utf8) - 1);
    if (written < 0) {
        written = 0;
    }
    if (written > (GDExtensionInt)sizeof(message_utf8) - 1) {
        written = (GDExtensionInt)sizeof(message_utf8) - 1;
    }
    message_utf8[written] = '\0';
    char desc[512];
    snprintf(desc, sizeof(desc), "Assertion failed: %s", message_utf8);
    GDCC_PRINT_RUNTIME_ERROR(desc, func, file, line);
}

#endif //GDSCRIPT_BUILTINS_H
