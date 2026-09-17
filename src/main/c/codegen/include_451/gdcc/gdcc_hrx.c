/// Hot-reload exchange (HRX) runtime: heap-resident thunk machinery for custom Callables.
/// Contract: doc/module_impl/backend/hot_reload_implementation_plan.md §5.
///
/// Cross-generation rules honored here (§2.3 of the plan):
///  - everything that must outlive dlclose lives on the Godot heap (godot_mem_alloc) and is
///    explicitly initialized (the allocator does not zero);
///  - thunk code lives in OS executable pages owned by no library image;
///  - old-library code is never needed after deinitialize: the sweeper is detached and every
///    spec's function pointers are NULLed there, so only thunks (table lookup + marking +
///    tail call) remain reachable from Godot-held Callables.

#include <godot_binding.h>

// `class_library` is referenced by static helpers pulled in through gdcc_helper.h and by the
// Callable `token` field below; it is (re)assigned by every generation's initialize call.
static GDExtensionClassLibraryPtr class_library = NULL;

#include <gdcc_helper.h>

#include "gdcc_hrx.h"

#include <string.h>

#ifdef _WIN32
#include <windows.h>
#else
#include <sys/mman.h>
// getpagesize() is POSIX and hidden from strict -std=c23 headers; its ABI is stable on every
// targeted libc (glibc / musl / macOS), so declare it directly instead of relaxing the standard.
int getpagesize(void);
#endif

// ---------------------------------------------------------------------------
// ABI offset assertions: machine-code thunks read these fields at fixed offsets.
// ---------------------------------------------------------------------------
_Static_assert(offsetof(gdcc_hrx_spec, impl_ptr) == 0, "thunk ABI: spec.impl_ptr must be at 0");
_Static_assert(offsetof(gdcc_hrx_spec, is_valid_fn) == 16, "thunk ABI: spec.is_valid_fn must be at 16");
_Static_assert(offsetof(gdcc_hrx_spec, captures) == 24, "thunk ABI: spec.captures must be at 24");
_Static_assert(offsetof(gdcc_hrx_spec, dead) == 32, "thunk ABI: spec.dead must be at 32");
_Static_assert(offsetof(gdcc_hrx_spec, refcount) == 36, "thunk ABI: spec.refcount must be at 36");
_Static_assert(offsetof(gdcc_hrx_spec, argument_count) == 40, "thunk ABI: spec.argument_count must be at 40");
_Static_assert(offsetof(gdcc_hrx_spec, hub) == 48, "thunk ABI: spec.hub must be at 48");
_Static_assert(offsetof(gdcc_hrx_hub, sweeper) == 40, "thunk ABI: hub.sweeper must be at 40");
// §5.11 v2 append-only pin: the callsite_context tail must start exactly where the v1 layout
// ended, so v1 blocks never overlap the new field.
_Static_assert(offsetof(gdcc_hrx_spec, callsite_context) == 136,
        "ABI v2: spec.callsite_context must append at offset 136 (v1 sizeof)");
// ABI v2 append-only pin: the v1 block ends at 136 and v2 appends exactly one pointer there.
// If this breaks, the field was NOT appended at the tail and the abi_version guard cannot
// protect old-generation blocks from an out-of-bounds read.
_Static_assert(offsetof(gdcc_hrx_spec, callsite_context) == 136, "ABI v2: spec.callsite_context must append at 136");

// ---------------------------------------------------------------------------
// Thunk templates (hand-assembled, position-independent, ABI-frozen, ZERO runtime patching).
// The spec arrives in each callback's first argument (Godot always passes
// `callable_userdata`); the free thunk reaches the hub through `spec->hub` (offset 48) and
// `hub->sweeper` (offset 40). All three architecture variants are compiled as data on every
// host so tests can pin their bytes; only the host variant is ever executed.
// GDEXTENSION_CALL_ERROR_INVALID_METHOD=1.
// ---------------------------------------------------------------------------

// x86_64 System V AMD64 (Linux, macOS Intel): rdi/rsi/rdx/rcx/r8, 16B stack alignment.
// is_valid: 44 bytes. The nested is_valid_fn call keeps a full frame: push rbx aligns rsp
// to 16 for the inner `call`.
static const uint8_t gdcc_hrx_tpl_sysv_is_valid[] = {
    0x53,                                           // push rbx
    0x48, 0x89, 0xFB,                               // mov rbx, rdi (spec)
    0x48, 0x83, 0x3B, 0x00,                         // cmp qword [rbx], 0
    0x74, 0x1E,                                     // je .Lfalse
    0x83, 0x7B, 0x20, 0x00,                         // cmp dword [rbx+32], 0
    0x75, 0x18,                                     // jne .Lfalse
    0x48, 0x8B, 0x43, 0x10,                         // mov rax, [rbx+16]
    0x48, 0x85, 0xC0,                               // test rax, rax
    0x74, 0x08,                                     // je .Ltrue
    0x48, 0x8B, 0x7B, 0x18,                         // mov rdi, [rbx+24]
    0xFF, 0xD0,                                     // call rax
    0x5B,                                           // pop rbx
    0xC3,                                           // ret
    0xB8, 0x01, 0x00, 0x00, 0x00,                   // .Ltrue: mov eax, 1
    0x5B, 0xC3,                                     // pop rbx; ret
    0x31, 0xC0,                                     // .Lfalse: xor eax, eax
    0x5B, 0xC3                                      // pop rbx; ret
};
// free: 48 bytes. Order: load -> decrement -> store -> only on zero set dead -> load
// spec->hub -> hub->sweeper -> tail-jump sweeper (rdi=spec).
static const uint8_t gdcc_hrx_tpl_sysv_free[] = {
    0x49, 0x89, 0xFA,                               // mov r10, rdi (spec)
    0x41, 0x8B, 0x4A, 0x24,                         // mov ecx, [r10+36]
    0x85, 0xC9,                                     // test ecx, ecx
    0x74, 0x24,                                     // je .Lret (underflow guard)
    0xFF, 0xC9,                                     // dec ecx
    0x41, 0x89, 0x4A, 0x24,                         // mov [r10+36], ecx
    0x85, 0xC9,                                     // test ecx, ecx
    0x75, 0x1A,                                     // jne .Lret (still shared)
    0x41, 0xC7, 0x42, 0x20, 0x01, 0x00, 0x00, 0x00, // mov dword [r10+32], 1 (dead)
    0x49, 0x8B, 0x42, 0x30,                         // mov rax, [r10+48] (spec->hub)
    0x48, 0x8B, 0x40, 0x28,                         // mov rax, [rax+40] (hub->sweeper)
    0x48, 0x85, 0xC0,                               // test rax, rax
    0x74, 0x05,                                     // je .Lret (no sweeper: mark only)
    0x4C, 0x89, 0xD7,                               // mov rdi, r10
    0xFF, 0xE0,                                     // jmp rax (tail)
    0xC3                                            // .Lret: ret
};
// call: 54 bytes. Tail-call swaps arg0 to captures and leaves the remaining registers
// untouched; the invalid path writes the full GDExtensionCallError.
static const uint8_t gdcc_hrx_tpl_sysv_call[] = {
    0x49, 0x89, 0xFA,                               // mov r10, rdi (spec)
    0x49, 0x83, 0x3A, 0x00,                         // cmp qword [r10], 0
    0x74, 0x10,                                     // je .Linvalid
    0x41, 0x83, 0x7A, 0x20, 0x00,                   // cmp dword [r10+32], 0
    0x75, 0x09,                                     // jne .Linvalid
    0x49, 0x8B, 0x02,                               // mov rax, [r10]
    0x49, 0x8B, 0x7A, 0x18,                         // mov rdi, [r10+24]
    0xFF, 0xE0,                                     // jmp rax (tail)
    0x4D, 0x85, 0xC0,                               // .Linvalid: test r8, r8
    0x74, 0x17,                                     // je .Lret
    0x41, 0xC7, 0x00, 0x01, 0x00, 0x00, 0x00,       // mov dword [r8], 1
    0x41, 0xC7, 0x40, 0x04, 0x00, 0x00, 0x00, 0x00, // mov dword [r8+4], 0
    0x41, 0xC7, 0x40, 0x08, 0x00, 0x00, 0x00, 0x00, // mov dword [r8+8], 0
    0xC3                                            // .Lret: ret
};
// get_argument_count: 34 bytes. Two-parameter ABI (userdata, r_is_valid).
static const uint8_t gdcc_hrx_tpl_sysv_get_argument_count[] = {
    0x49, 0x89, 0xFA,                               // mov r10, rdi (spec)
    0x48, 0x85, 0xF6,                               // test rsi, rsi
    0x74, 0x15,                                     // je .Lcount
    0x49, 0x83, 0x3A, 0x00,                         // cmp qword [r10], 0
    0x74, 0x0C,                                     // je .Linvalid
    0x41, 0x83, 0x7A, 0x20, 0x00,                   // cmp dword [r10+32], 0
    0x75, 0x05,                                     // jne .Linvalid
    0xC6, 0x06, 0x01,                               // mov byte [rsi], 1
    0xEB, 0x03,                                     // jmp .Lcount
    0xC6, 0x06, 0x00,                               // .Linvalid: mov byte [rsi], 0
    0x49, 0x63, 0x42, 0x28,                         // .Lcount: movsxd rax, dword [r10+40]
    0xC3                                            // ret
};

// x86_64 Win64: rcx/rdx/r8/r9 + stack 5th, 32B shadow space for the nested call, no red zone.
// is_valid: 52 bytes.
static const uint8_t gdcc_hrx_tpl_win64_is_valid[] = {
    0x53,                                           // push rbx
    0x48, 0x89, 0xCB,                               // mov rbx, rcx (spec)
    0x48, 0x83, 0x3B, 0x00,                         // cmp qword [rbx], 0
    0x74, 0x26,                                     // je .Lfalse
    0x83, 0x7B, 0x20, 0x00,                         // cmp dword [rbx+32], 0
    0x75, 0x20,                                     // jne .Lfalse
    0x48, 0x8B, 0x43, 0x10,                         // mov rax, [rbx+16]
    0x48, 0x85, 0xC0,                               // test rax, rax
    0x74, 0x10,                                     // je .Ltrue
    0x48, 0x8B, 0x4B, 0x18,                         // mov rcx, [rbx+24]
    0x48, 0x83, 0xEC, 0x20,                         // sub rsp, 0x20 (shadow space)
    0xFF, 0xD0,                                     // call rax
    0x48, 0x83, 0xC4, 0x20,                         // add rsp, 0x20
    0x5B, 0xC3,                                     // pop rbx; ret
    0xB8, 0x01, 0x00, 0x00, 0x00,                   // .Ltrue: mov eax, 1
    0x5B, 0xC3,                                     // pop rbx; ret
    0x31, 0xC0,                                     // .Lfalse: xor eax, eax
    0x5B, 0xC3                                      // pop rbx; ret
};
// free: 48 bytes; sweeper tail-jump passes spec in rcx.
static const uint8_t gdcc_hrx_tpl_win64_free[] = {
    0x49, 0x89, 0xCA,                               // mov r10, rcx (spec)
    0x41, 0x8B, 0x4A, 0x24,                         // mov ecx, [r10+36]
    0x85, 0xC9,                                     // test ecx, ecx
    0x74, 0x24,                                     // je .Lret
    0xFF, 0xC9,                                     // dec ecx
    0x41, 0x89, 0x4A, 0x24,                         // mov [r10+36], ecx
    0x85, 0xC9,                                     // test ecx, ecx
    0x75, 0x1A,                                     // jne .Lret
    0x41, 0xC7, 0x42, 0x20, 0x01, 0x00, 0x00, 0x00, // mov dword [r10+32], 1
    0x49, 0x8B, 0x42, 0x30,                         // mov rax, [r10+48] (spec->hub)
    0x48, 0x8B, 0x40, 0x28,                         // mov rax, [rax+40] (hub->sweeper)
    0x48, 0x85, 0xC0,                               // test rax, rax
    0x74, 0x05,                                     // je .Lret
    0x4C, 0x89, 0xD1,                               // mov rcx, r10
    0xFF, 0xE0,                                     // jmp rax (tail)
    0xC3                                            // .Lret: ret
};
// call: 59 bytes. The 5th argument (r_error) sits at [rsp+0x28] on entry; the tail jump
// never touches the stack, so it forwards unchanged.
static const uint8_t gdcc_hrx_tpl_win64_call[] = {
    0x49, 0x89, 0xCA,                               // mov r10, rcx (spec)
    0x49, 0x83, 0x3A, 0x00,                         // cmp qword [r10], 0
    0x74, 0x10,                                     // je .Linvalid
    0x41, 0x83, 0x7A, 0x20, 0x00,                   // cmp dword [r10+32], 0
    0x75, 0x09,                                     // jne .Linvalid
    0x49, 0x8B, 0x02,                               // mov rax, [r10]
    0x49, 0x8B, 0x4A, 0x18,                         // mov rcx, [r10+24]
    0xFF, 0xE0,                                     // jmp rax (tail)
    0x4C, 0x8B, 0x5C, 0x24, 0x28,                   // .Linvalid: mov r11, [rsp+0x28]
    0x4D, 0x85, 0xDB,                               // test r11, r11
    0x74, 0x17,                                     // je .Lret
    0x41, 0xC7, 0x03, 0x01, 0x00, 0x00, 0x00,       // mov dword [r11], 1
    0x41, 0xC7, 0x43, 0x04, 0x00, 0x00, 0x00, 0x00, // mov dword [r11+4], 0
    0x41, 0xC7, 0x43, 0x08, 0x00, 0x00, 0x00, 0x00, // mov dword [r11+8], 0
    0xC3                                            // .Lret: ret
};
// get_argument_count: 34 bytes (rcx=userdata, rdx=r_is_valid).
static const uint8_t gdcc_hrx_tpl_win64_get_argument_count[] = {
    0x49, 0x89, 0xCA,                               // mov r10, rcx (spec)
    0x48, 0x85, 0xD2,                               // test rdx, rdx
    0x74, 0x15,                                     // je .Lcount
    0x49, 0x83, 0x3A, 0x00,                         // cmp qword [r10], 0
    0x74, 0x0C,                                     // je .Linvalid
    0x41, 0x83, 0x7A, 0x20, 0x00,                   // cmp dword [r10+32], 0
    0x75, 0x05,                                     // jne .Linvalid
    0xC6, 0x02, 0x01,                               // mov byte [rdx], 1
    0xEB, 0x03,                                     // jmp .Lcount
    0xC6, 0x02, 0x00,                               // .Linvalid: mov byte [rdx], 0
    0x49, 0x63, 0x42, 0x28,                         // .Lcount: movsxd rax, dword [r10+40]
    0xC3                                            // ret
};

// aarch64 (Linux + macOS arm64): x0..x4 arguments, spec arrives in x0 (zero literals).
// The icache is flushed after the thunk-page write.
// is_valid: 64 bytes; the nested call saves x29/x30.
static const uint8_t gdcc_hrx_tpl_arm64_is_valid[] = {
    0xE9, 0x03, 0x00, 0xAA, // mov x9, x0 (spec)
    0x2A, 0x01, 0x40, 0xF9, // ldr x10, [x9, #0]
    0x8A, 0x01, 0x00, 0xB4, // cbz x10, .Lfalse
    0x2B, 0x21, 0x40, 0xB9, // ldr w11, [x9, #32]
    0x4B, 0x01, 0x00, 0x35, // cbnz w11, .Lfalse
    0x2A, 0x09, 0x40, 0xF9, // ldr x10, [x9, #16]
    0xCA, 0x00, 0x00, 0xB4, // cbz x10, .Ltrue
    0xFD, 0x7B, 0xBF, 0xA9, // stp x29, x30, [sp, #-16]!
    0x20, 0x0D, 0x40, 0xF9, // ldr x0, [x9, #24]
    0x40, 0x01, 0x3F, 0xD6, // blr x10
    0xFD, 0x7B, 0xC1, 0xA8, // ldp x29, x30, [sp], #16
    0xC0, 0x03, 0x5F, 0xD6, // ret
    0x20, 0x00, 0x80, 0x52, // .Ltrue: mov w0, #1
    0xC0, 0x03, 0x5F, 0xD6, // ret
    0xE0, 0x03, 0x1F, 0x2A, // .Lfalse: mov w0, wzr
    0xC0, 0x03, 0x5F, 0xD6  // ret
};
// free: 56 bytes.
static const uint8_t gdcc_hrx_tpl_arm64_free[] = {
    0xE9, 0x03, 0x00, 0xAA, // mov x9, x0 (spec)
    0x2A, 0x25, 0x40, 0xB9, // ldr w10, [x9, #36]
    0x6A, 0x01, 0x00, 0x34, // cbz w10, .Lret
    0x4A, 0x05, 0x00, 0x51, // sub w10, w10, #1
    0x2A, 0x25, 0x00, 0xB9, // str w10, [x9, #36]
    0x0A, 0x01, 0x00, 0x35, // cbnz w10, .Lret
    0x2B, 0x00, 0x80, 0x52, // mov w11, #1
    0x2B, 0x21, 0x00, 0xB9, // str w11, [x9, #32]
    0x2A, 0x19, 0x40, 0xF9, // ldr x10, [x9, #48] (spec->hub)
    0x4A, 0x15, 0x40, 0xF9, // ldr x10, [x10, #40] (hub->sweeper)
    0x6A, 0x00, 0x00, 0xB4, // cbz x10, .Lret
    0xE0, 0x03, 0x09, 0xAA, // mov x0, x9
    0x40, 0x01, 0x1F, 0xD6, // br x10 (tail)
    0xC0, 0x03, 0x5F, 0xD6  // .Lret: ret
};
// call: 56 bytes (13 instructions + trailing alignment nop).
static const uint8_t gdcc_hrx_tpl_arm64_call[] = {
    0xE9, 0x03, 0x00, 0xAA, // mov x9, x0 (spec)
    0x2A, 0x01, 0x40, 0xF9, // ldr x10, [x9, #0]
    0xAA, 0x00, 0x00, 0xB4, // cbz x10, .Linvalid
    0x2B, 0x21, 0x40, 0xB9, // ldr w11, [x9, #32]
    0x6B, 0x00, 0x00, 0x35, // cbnz w11, .Linvalid
    0x20, 0x0D, 0x40, 0xF9, // ldr x0, [x9, #24]
    0x40, 0x01, 0x1F, 0xD6, // br x10 (tail)
    0xA4, 0x00, 0x00, 0xB4, // .Linvalid: cbz x4, .Lret
    0x2C, 0x00, 0x80, 0x52, // mov w12, #1
    0x8C, 0x00, 0x00, 0xB9, // str w12, [x4, #0]
    0x9F, 0x04, 0x00, 0xB9, // str wzr, [x4, #4]
    0x9F, 0x08, 0x00, 0xB9, // str wzr, [x4, #8]
    0xC0, 0x03, 0x5F, 0xD6, // .Lret: ret
    0x1F, 0x20, 0x03, 0xD5  // nop (bundle alignment pad)
};
// get_argument_count: 48 bytes; x0=userdata, x1=r_is_valid.
static const uint8_t gdcc_hrx_tpl_arm64_get_argument_count[] = {
    0xE9, 0x03, 0x00, 0xAA, // mov x9, x0 (spec)
    0x21, 0x01, 0x00, 0xB4, // cbz x1, .Lcount
    0x2A, 0x01, 0x40, 0xF9, // ldr x10, [x9, #0]
    0xCA, 0x00, 0x00, 0xB4, // cbz x10, .Linvalid
    0x2B, 0x21, 0x40, 0xB9, // ldr w11, [x9, #32]
    0x8B, 0x00, 0x00, 0x35, // cbnz w11, .Linvalid
    0x2C, 0x00, 0x80, 0x52, // mov w12, #1
    0x2C, 0x00, 0x00, 0x39, // strb w12, [x1]
    0x02, 0x00, 0x00, 0x14, // b .Lcount
    0x3F, 0x00, 0x00, 0x39, // .Linvalid: strb wzr, [x1]
    0x20, 0x29, 0x80, 0xB9, // .Lcount: ldrsw x0, [x9, #40]
    0xC0, 0x03, 0x5F, 0xD6  // ret
};

_Static_assert(sizeof(gdcc_hrx_tpl_sysv_is_valid) == 44, "sysv is_valid template size drifted");
_Static_assert(sizeof(gdcc_hrx_tpl_sysv_free) == 48, "sysv free template size drifted");
_Static_assert(sizeof(gdcc_hrx_tpl_sysv_call) == 54, "sysv call template size drifted");
_Static_assert(sizeof(gdcc_hrx_tpl_sysv_get_argument_count) == 34, "sysv argc template size drifted");
_Static_assert(sizeof(gdcc_hrx_tpl_win64_is_valid) == 52, "win64 is_valid template size drifted");
_Static_assert(sizeof(gdcc_hrx_tpl_win64_free) == 48, "win64 free template size drifted");
_Static_assert(sizeof(gdcc_hrx_tpl_win64_call) == 59, "win64 call template size drifted");
_Static_assert(sizeof(gdcc_hrx_tpl_win64_get_argument_count) == 34, "win64 argc template size drifted");
_Static_assert(sizeof(gdcc_hrx_tpl_arm64_is_valid) == 64, "arm64 is_valid template size drifted");
_Static_assert(sizeof(gdcc_hrx_tpl_arm64_free) == 56, "arm64 free template size drifted");
_Static_assert(sizeof(gdcc_hrx_tpl_arm64_call) == 56, "arm64 call template size drifted");
_Static_assert(sizeof(gdcc_hrx_tpl_arm64_get_argument_count) == 48, "arm64 argc template size drifted");

// Bundle offsets are cumulative template sizes: pin the sums so a resized template cannot
// silently overlap the next role thunk on a non-host ABI (offsets themselves are frozen in
// gdcc_hrx_bundle_offsets below and byte-pinned by the smoke tests).
_Static_assert(sizeof(gdcc_hrx_tpl_sysv_is_valid) == 44, "sysv bundle offset 1 drifted");
_Static_assert(sizeof(gdcc_hrx_tpl_sysv_is_valid) + sizeof(gdcc_hrx_tpl_sysv_free) == 92, "sysv bundle offset 2 drifted");
_Static_assert(sizeof(gdcc_hrx_tpl_sysv_is_valid) + sizeof(gdcc_hrx_tpl_sysv_free)
        + sizeof(gdcc_hrx_tpl_sysv_call) == 146, "sysv bundle offset 3 drifted");
_Static_assert(sizeof(gdcc_hrx_tpl_sysv_is_valid) + sizeof(gdcc_hrx_tpl_sysv_free)
        + sizeof(gdcc_hrx_tpl_sysv_call) + sizeof(gdcc_hrx_tpl_sysv_get_argument_count) == 180, "sysv bundle end drifted");
_Static_assert(sizeof(gdcc_hrx_tpl_win64_is_valid) == 52, "win64 bundle offset 1 drifted");
_Static_assert(sizeof(gdcc_hrx_tpl_win64_is_valid) + sizeof(gdcc_hrx_tpl_win64_free) == 100, "win64 bundle offset 2 drifted");
_Static_assert(sizeof(gdcc_hrx_tpl_win64_is_valid) + sizeof(gdcc_hrx_tpl_win64_free)
        + sizeof(gdcc_hrx_tpl_win64_call) == 159, "win64 bundle offset 3 drifted");
_Static_assert(sizeof(gdcc_hrx_tpl_win64_is_valid) + sizeof(gdcc_hrx_tpl_win64_free)
        + sizeof(gdcc_hrx_tpl_win64_call) + sizeof(gdcc_hrx_tpl_win64_get_argument_count) == 193, "win64 bundle end drifted");
_Static_assert(sizeof(gdcc_hrx_tpl_arm64_is_valid) == 64, "arm64 bundle offset 1 drifted");
_Static_assert(sizeof(gdcc_hrx_tpl_arm64_is_valid) + sizeof(gdcc_hrx_tpl_arm64_free) == 120, "arm64 bundle offset 2 drifted");
_Static_assert(sizeof(gdcc_hrx_tpl_arm64_is_valid) + sizeof(gdcc_hrx_tpl_arm64_free)
        + sizeof(gdcc_hrx_tpl_arm64_call) == 176, "arm64 bundle offset 3 drifted");
_Static_assert(sizeof(gdcc_hrx_tpl_arm64_is_valid) + sizeof(gdcc_hrx_tpl_arm64_free)
        + sizeof(gdcc_hrx_tpl_arm64_call) + sizeof(gdcc_hrx_tpl_arm64_get_argument_count) == 224, "arm64 bundle end drifted");

static const gdcc_hrx_thunk_template gdcc_hrx_templates[GDCC_HRX_ARCH_COUNT][GDCC_HRX_THUNK_KIND_COUNT] = {
    [GDCC_HRX_ARCH_X86_64_SYSV] = {
        [GDCC_HRX_THUNK_IS_VALID] = { gdcc_hrx_tpl_sysv_is_valid, sizeof(gdcc_hrx_tpl_sysv_is_valid) },
        [GDCC_HRX_THUNK_FREE] = { gdcc_hrx_tpl_sysv_free, sizeof(gdcc_hrx_tpl_sysv_free) },
        [GDCC_HRX_THUNK_CALL] = { gdcc_hrx_tpl_sysv_call, sizeof(gdcc_hrx_tpl_sysv_call) },
        [GDCC_HRX_THUNK_GET_ARGUMENT_COUNT] = { gdcc_hrx_tpl_sysv_get_argument_count, sizeof(gdcc_hrx_tpl_sysv_get_argument_count) },
    },
    [GDCC_HRX_ARCH_X86_64_WIN64] = {
        [GDCC_HRX_THUNK_IS_VALID] = { gdcc_hrx_tpl_win64_is_valid, sizeof(gdcc_hrx_tpl_win64_is_valid) },
        [GDCC_HRX_THUNK_FREE] = { gdcc_hrx_tpl_win64_free, sizeof(gdcc_hrx_tpl_win64_free) },
        [GDCC_HRX_THUNK_CALL] = { gdcc_hrx_tpl_win64_call, sizeof(gdcc_hrx_tpl_win64_call) },
        [GDCC_HRX_THUNK_GET_ARGUMENT_COUNT] = { gdcc_hrx_tpl_win64_get_argument_count, sizeof(gdcc_hrx_tpl_win64_get_argument_count) },
    },
    [GDCC_HRX_ARCH_AARCH64] = {
        [GDCC_HRX_THUNK_IS_VALID] = { gdcc_hrx_tpl_arm64_is_valid, sizeof(gdcc_hrx_tpl_arm64_is_valid) },
        [GDCC_HRX_THUNK_FREE] = { gdcc_hrx_tpl_arm64_free, sizeof(gdcc_hrx_tpl_arm64_free) },
        [GDCC_HRX_THUNK_CALL] = { gdcc_hrx_tpl_arm64_call, sizeof(gdcc_hrx_tpl_arm64_call) },
        [GDCC_HRX_THUNK_GET_ARGUMENT_COUNT] = { gdcc_hrx_tpl_arm64_get_argument_count, sizeof(gdcc_hrx_tpl_arm64_get_argument_count) },
    },
};

/// Byte offset of each role thunk inside the hub's shared thunk page
/// (layout: is_valid, free, call, argc; arm64 bundle starts stay 8-aligned).
static const uint32_t gdcc_hrx_bundle_offsets[GDCC_HRX_ARCH_COUNT][GDCC_HRX_THUNK_KIND_COUNT + 1] = {
    [GDCC_HRX_ARCH_X86_64_SYSV] = { 0, 44, 92, 146, 180 },
    [GDCC_HRX_ARCH_X86_64_WIN64] = { 0, 52, 100, 159, 193 },
    [GDCC_HRX_ARCH_AARCH64] = { 0, 64, 120, 176, 224 },
};

#if defined(__x86_64__) || defined(_M_X64)
#if defined(_WIN32)
#define GDCC_HRX_HOST_ARCH GDCC_HRX_ARCH_X86_64_WIN64
#else
#define GDCC_HRX_HOST_ARCH GDCC_HRX_ARCH_X86_64_SYSV
#endif
#define GDCC_HRX_HAS_THUNK_TEMPLATES 1
#elif defined(__aarch64__)
#define GDCC_HRX_HOST_ARCH GDCC_HRX_ARCH_AARCH64
#define GDCC_HRX_HAS_THUNK_TEMPLATES 1
#else
/// Unsupported ISA (e.g. riscv64): the module must still compile so export builds for these
/// targets keep working. The execmem probe fails closed, so an editor process freezes into
/// HRX_UNAVAILABLE and a non-editor process keeps the direct path; no thunk is ever written.
#define GDCC_HRX_HOST_ARCH GDCC_HRX_ARCH_COUNT
#define GDCC_HRX_HAS_THUNK_TEMPLATES 0
#endif

const gdcc_hrx_thunk_template *gdcc_hrx_thunk_template_get_for_arch(gdcc_hrx_thunk_arch arch, gdcc_hrx_thunk_kind kind) {
    if (arch < 0 || arch >= GDCC_HRX_ARCH_COUNT || kind < 0 || kind >= GDCC_HRX_THUNK_KIND_COUNT) {
        return NULL;
    }
    return &gdcc_hrx_templates[arch][kind];
}

uint32_t gdcc_hrx_thunk_bundle_offset_for_arch(gdcc_hrx_thunk_arch arch, gdcc_hrx_thunk_kind kind) {
    if (arch < 0 || arch >= GDCC_HRX_ARCH_COUNT || kind < 0 || kind >= GDCC_HRX_THUNK_KIND_COUNT) {
        return 0;
    }
    return gdcc_hrx_bundle_offsets[arch][kind];
}

const gdcc_hrx_thunk_template *gdcc_hrx_thunk_template_get(gdcc_hrx_thunk_kind kind) {
    return gdcc_hrx_thunk_template_get_for_arch(GDCC_HRX_HOST_ARCH, kind);
}

uint32_t gdcc_hrx_thunk_bundle_offset(gdcc_hrx_thunk_kind kind) {
    return gdcc_hrx_thunk_bundle_offset_for_arch(GDCC_HRX_HOST_ARCH, kind);
}

// ---------------------------------------------------------------------------
// Process state (image-local: every library generation re-resolves it).
// ---------------------------------------------------------------------------
static gdcc_hrx_mode g_hrx_mode = GDCC_HRX_MODE_UNINITIALIZED;
static gdcc_hrx_hub *g_hrx_hub = NULL;
static godot_bool g_hrx_unavailable_reported = false;
static uint64_t g_hrx_orphaned_hub_count = 0;

gdcc_hrx_mode gdcc_hrx_get_mode(void) {
    return g_hrx_mode;
}

#ifdef GDCC_HRX_TEST_HOOKS
/// Test-only seam (never compiled into production builds): resets the image-local state so a
/// probe process can simulate a NEW library generation — the next initialize_core re-enters
/// the UNINITIALIZED decision branch (tombstone sweep, anchor query, hub takeover) instead
/// of short-circuiting on the previous generation's frozen mode.
void gdcc_hrx_test_reset_image_state(void) {
    g_hrx_mode = GDCC_HRX_MODE_UNINITIALIZED;
    g_hrx_hub = NULL;
    g_hrx_unavailable_reported = false;
}
#endif

gdcc_hrx_hub *gdcc_hrx_current_hub(void) {
    return g_hrx_hub;
}

godot_Callable gdcc_hrx_invalid_callable(void) {
    godot_Callable result;
    memset(&result, 0, sizeof(result));
    return result;
}

void gdcc_hrx_report_unavailable(void) {
    if (g_hrx_unavailable_reported) {
        return;
    }
    g_hrx_unavailable_reported = true;
    GDCC_PRINT_RUNTIME_ERROR(
            "gdcc: executable heap memory is unavailable in this editor process; "
            "custom Callables (lambdas/standalone) are disabled (fail-closed hot reload policy)",
            "gdcc_hrx_report_unavailable", NULL, 0
    );
}

// ---------------------------------------------------------------------------
// OS executable memory (process-level, survives library unload by design).
// ---------------------------------------------------------------------------
static void *gdcc_hrx_os_page_alloc(size_t size) {
#ifdef _WIN32
    return VirtualAlloc(NULL, size, MEM_COMMIT | MEM_RESERVE, PAGE_READWRITE);
#else
    void *page = mmap(NULL, size, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    return (page == MAP_FAILED) ? NULL : page;
#endif
}

static godot_bool gdcc_hrx_os_page_make_rx(void *page, size_t size) {
#ifdef _WIN32
    DWORD old_protect = 0;
    return VirtualProtect(page, size, PAGE_EXECUTE_READ, &old_protect) != 0;
#else
    return mprotect(page, size, PROT_READ | PROT_EXEC) == 0;
#endif
}

static void gdcc_hrx_os_page_free(void *page, size_t size) {
#ifdef _WIN32
    (void)size;
    VirtualFree(page, 0, MEM_RELEASE);
#else
    munmap(page, size);
#endif
}

static size_t gdcc_hrx_os_page_size(void) {
#ifdef _WIN32
    SYSTEM_INFO info;
    GetSystemInfo(&info);
    return (size_t)info.dwPageSize;
#else
    return (size_t)getpagesize();
#endif
}

static void gdcc_hrx_flush_icache(void *addr, size_t size) {
#if defined(__aarch64__)
    __builtin___clear_cache((char *)addr, (char *)addr + size);
#else
    (void)addr;
    (void)size;
#endif
}

bool gdcc_hrx_execmem_probe(void) {
#if !GDCC_HRX_HAS_THUNK_TEMPLATES
    // No thunk template set for this ISA: fail closed so the editor lands in HRX_UNAVAILABLE.
    return false;
#else
    // Full RW -> write -> RX -> execute -> unmap round trip, mirroring the thunk-page flow
    // exactly (the shared page is written once and never demoted afterwards).
    const size_t page_size = gdcc_hrx_os_page_size();
    uint8_t *page = (uint8_t *)gdcc_hrx_os_page_alloc(page_size);
    if (page == NULL) {
        return false;
    }
#if defined(__aarch64__)
    const uint32_t ret_insn = 0xD65F03C0u;
    memcpy(page, &ret_insn, sizeof(ret_insn));
#else
    page[0] = 0xC3; // ret
#endif
    if (!gdcc_hrx_os_page_make_rx(page, page_size)) {
        gdcc_hrx_os_page_free(page, page_size);
        return false;
    }
    gdcc_hrx_flush_icache(page, 16);
    void (*nop_thunk)(void) = (void (*)(void))page;
    nop_thunk();
    gdcc_hrx_os_page_free(page, page_size);
    return true;
#endif
}

// ---------------------------------------------------------------------------
// Shared thunk page: all four role thunks are copied into ONE OS page at hub creation —
// before any Callable exists — and the page is never written again, so no protection demote
// of a page holding published code can ever occur.
// ---------------------------------------------------------------------------
static void *gdcc_hrx_thunk_page_publish(void) {
    const size_t page_size = gdcc_hrx_os_page_size();
    uint8_t *page = (uint8_t *)gdcc_hrx_os_page_alloc(page_size);
    if (page == NULL) {
        return NULL;
    }
    for (gdcc_hrx_thunk_kind kind = 0; kind < GDCC_HRX_THUNK_KIND_COUNT; kind++) {
        const gdcc_hrx_thunk_template *tpl = gdcc_hrx_thunk_template_get(kind);
        if (tpl == NULL || tpl->bytes == NULL) {
            gdcc_hrx_os_page_free(page, page_size);
            return NULL; // no thunk template set for this ISA (unreachable post-probe)
        }
        memcpy(page + gdcc_hrx_thunk_bundle_offset(kind), tpl->bytes, tpl->size);
    }
    if (!gdcc_hrx_os_page_make_rx(page, page_size)) {
        gdcc_hrx_os_page_free(page, page_size);
        return NULL;
    }
    gdcc_hrx_flush_icache(page, page_size);
    return page;
}

// ---------------------------------------------------------------------------
// Heap helpers (Godot allocator only: CRT heap does not survive library unload).
// ---------------------------------------------------------------------------
static char *gdcc_hrx_heap_strdup(const char *text) {
    if (text == NULL) {
        return NULL;
    }
    const size_t len = strlen(text) + 1;
    char *copy = (char *)godot_mem_alloc(len);
    if (copy != NULL) {
        memcpy(copy, text, len);
    }
    return copy;
}

static void gdcc_hrx_standalone_payload_free(gdcc_hrx_standalone_payload *payload) {
    if (payload == NULL) {
        return;
    }
    godot_mem_free((void *)payload->kind);
    godot_mem_free((void *)payload->owner);
    godot_mem_free((void *)payload->name);
    godot_mem_free(payload);
}

// ---------------------------------------------------------------------------
// Registry / interning table / pending queue.
// ---------------------------------------------------------------------------
#define GDCC_HRX_INTERN_BUCKETS 64u

static void gdcc_hrx_registry_push(gdcc_hrx_hub *hub, gdcc_hrx_spec *spec) {
    spec->prev = NULL;
    spec->next = hub->registry;
    if (hub->registry != NULL) {
        hub->registry->prev = spec;
    }
    hub->registry = spec;
}

static void gdcc_hrx_dll_remove(gdcc_hrx_hub *hub, gdcc_hrx_spec *spec) {
    if (spec->prev != NULL) {
        spec->prev->next = spec->next;
    } else {
        hub->registry = spec->next;
    }
    if (spec->next != NULL) {
        spec->next->prev = spec->prev;
    }
    spec->prev = NULL;
    spec->next = NULL;
}

static uint32_t gdcc_hrx_key_hash(const char *key) {
    uint32_t hash = 2166136261u;
    for (const unsigned char *p = (const unsigned char *)key; *p != 0; p++) {
        hash ^= (uint32_t)(*p);
        hash *= 16777619u;
    }
    return hash;
}

static gdcc_hrx_spec *gdcc_hrx_intern_find(gdcc_hrx_hub *hub, const char *key) {
    const uint32_t bucket = gdcc_hrx_key_hash(key) % hub->intern_table_size;
    for (gdcc_hrx_spec *spec = hub->intern_table[bucket]; spec != NULL; spec = spec->intern_next) {
        if (spec->impl_key != NULL && strcmp(spec->impl_key, key) == 0) {
            return spec;
        }
    }
    return NULL;
}

static void gdcc_hrx_intern_insert(gdcc_hrx_hub *hub, gdcc_hrx_spec *spec) {
    const uint32_t bucket = gdcc_hrx_key_hash(spec->impl_key) % hub->intern_table_size;
    spec->intern_next = hub->intern_table[bucket];
    hub->intern_table[bucket] = spec;
    hub->intern_count++;
}

static void gdcc_hrx_intern_remove(gdcc_hrx_hub *hub, gdcc_hrx_spec *spec) {
    // Membership is decided by the explicit `interned` flag, never by `intern_next == NULL`
    // (a single-node bucket chain also has NULL there).
    if (!spec->interned || spec->impl_key == NULL) {
        return;
    }
    const uint32_t bucket = gdcc_hrx_key_hash(spec->impl_key) % hub->intern_table_size;
    gdcc_hrx_spec **link = &hub->intern_table[bucket];
    while (*link != NULL && *link != spec) {
        link = &(*link)->intern_next;
    }
    if (*link == spec) {
        *link = spec->intern_next;
        hub->intern_count--;
    }
    spec->intern_next = NULL;
}

static void gdcc_hrx_pending_push(gdcc_hrx_hub *hub, gdcc_hrx_spec *spec) {
    spec->pending_next = hub->sweep_pending;
    hub->sweep_pending = spec;
}

static godot_bool gdcc_hrx_desc_matches(const gdcc_hrx_spec *spec, const gdcc_hrx_identity *identity);

// ---------------------------------------------------------------------------
// Spec lifecycle.
// ---------------------------------------------------------------------------
static gdcc_hrx_spec *gdcc_hrx_spec_new(
        gdcc_hrx_hub *hub,
        void *captures,
        gdcc_hrx_impl impl,
        gdcc_hrx_is_valid_fn is_valid,
        gdcc_hrx_destroy_fn destroy,
        int32_t argument_count,
        const gdcc_hrx_identity *identity,
        uint32_t interned
) {
    // The identity is heap-copied: generated `.rodata` dies with the library image while the
    // spec must stay comparable for later generations.
    char *key_copy = NULL;
    unsigned char *desc_copy = NULL;
    char *context_copy = NULL;
    const uint32_t desc_len = (identity != NULL) ? identity->schema_desc_len : 0u;
    if (identity != NULL && identity->impl_key != NULL) {
        key_copy = gdcc_hrx_heap_strdup(identity->impl_key);
        desc_copy = (unsigned char *)godot_mem_alloc(desc_len == 0 ? 1 : desc_len);
        // callsite_context may legitimately be NULL (standalone); heap_strdup passes NULL
        // through, so an OOM failure is only distinguishable when the source is non-NULL.
        context_copy = gdcc_hrx_heap_strdup(identity->callsite_context);
        if (key_copy == NULL || desc_copy == NULL
                || (identity->callsite_context != NULL && context_copy == NULL)) {
            godot_mem_free(key_copy);
            godot_mem_free(desc_copy);
            godot_mem_free(context_copy);
            return NULL;
        }
        if (desc_len > 0) {
            memcpy(desc_copy, identity->schema_desc, desc_len);
        }
    }
    gdcc_hrx_spec *spec = (gdcc_hrx_spec *)godot_mem_alloc(sizeof(gdcc_hrx_spec));
    if (spec == NULL) {
        godot_mem_free(key_copy);
        godot_mem_free(desc_copy);
        godot_mem_free(context_copy);
        return NULL;
    }
    spec->impl_ptr = impl;
    spec->destroy_fn = destroy;
    spec->is_valid_fn = is_valid;
    spec->captures = captures;
    spec->dead = 0;
    spec->refcount = 0; // published as 1 by the caller only after the thunk is ready
    spec->argument_count = argument_count;
    spec->abi_version = GDCC_HRX_ABI_VERSION;
    spec->binding_state = GDCC_HRX_BOUND_COMPATIBLE;
    spec->interned = interned;
    spec->impl_key = key_copy;
    spec->schema_desc = desc_copy;
    spec->schema_desc_len = desc_len;
    spec->reserved0 = 0;
    spec->callsite_context = context_copy;
    if (identity != NULL && identity->impl_key != NULL) {
        memcpy(spec->schema_fingerprint, identity->schema_fingerprint, sizeof(spec->schema_fingerprint));
    } else {
        memset(spec->schema_fingerprint, 0, sizeof(spec->schema_fingerprint));
    }
    spec->prev = NULL;
    spec->next = NULL;
    spec->intern_next = NULL;
    spec->pending_next = NULL;
    spec->callsite_context = context_copy;
    spec->hub = hub;
    return spec;
}

/// Frees everything except the capture block (whose layout is only known to a compatible
/// generation): identity copies, standalone payload, spec shell. Shared by the
/// live-generation sweeper and the next-generation two-phase sweep. The thunk code page is
/// hub-owned (shared by every spec), so a spec carries no per-spec code resource.
static void gdcc_hrx_shell_free(gdcc_hrx_hub *hub, gdcc_hrx_spec *spec) {
    (void)hub;
    godot_mem_free((void *)spec->impl_key);
    godot_mem_free((void *)spec->schema_desc);
    // v1 blocks predate the appended field: never read/free it through them.
    if (spec->abi_version >= 2u) {
        godot_mem_free((void *)spec->callsite_context);
    }
    if (spec->interned) {
        gdcc_hrx_standalone_payload_free((gdcc_hrx_standalone_payload *)spec->captures);
    }
    godot_mem_free(spec);
}

static godot_Callable gdcc_hrx_callable_from_spec(gdcc_hrx_spec *spec, GDObjectInstanceID object_id) {
    godot_Callable result = gdcc_hrx_invalid_callable();
    // All function pointers reference the hub's shared thunk page: written once at hub
    // creation and constant across generations (a rebound Callable keeps its pointers).
    const uint8_t *base = (const uint8_t *)spec->hub->thunk_page;
    GDExtensionCallableCustomInfo2 info = {
        .callable_userdata = spec,
        .token = class_library,
        .object_id = object_id,
        .call_func = (GDExtensionCallableCustomCall)(base + gdcc_hrx_thunk_bundle_offset(GDCC_HRX_THUNK_CALL)),
        .is_valid_func = (GDExtensionCallableCustomIsValid)(base + gdcc_hrx_thunk_bundle_offset(GDCC_HRX_THUNK_IS_VALID)),
        .free_func = (GDExtensionCallableCustomFree)(base + gdcc_hrx_thunk_bundle_offset(GDCC_HRX_THUNK_FREE)),
        .hash_func = NULL,
        .equal_func = NULL,
        .less_than_func = NULL,
        .to_string_func = NULL,
        .get_argument_count_func = (GDExtensionCallableCustomGetArgumentCount)(base + gdcc_hrx_thunk_bundle_offset(GDCC_HRX_THUNK_GET_ARGUMENT_COUNT)),
    };
    godot_callable_custom_create2((GDExtensionUninitializedTypePtr)&result, &info);
    return result;
}

godot_Callable gdcc_hrx_create_lambda(
        void *captures,
        GDObjectInstanceID object_id,
        gdcc_hrx_impl impl,
        gdcc_hrx_is_valid_fn is_valid,
        gdcc_hrx_destroy_fn destroy,
        int32_t argument_count,
        const gdcc_hrx_identity *identity,
        gdcc_hrx_spec **out_spec
) {
    if (out_spec != NULL) {
        *out_spec = NULL;
    }
    gdcc_hrx_hub *hub = g_hrx_hub;
    if (g_hrx_mode != GDCC_HRX_MODE_ACTIVE || hub == NULL) {
        return gdcc_hrx_invalid_callable();
    }
    gdcc_hrx_spec *spec = gdcc_hrx_spec_new(hub, captures, impl, is_valid, destroy, argument_count, identity, 0);
    if (spec == NULL) {
        // Half-built state must never reach Godot: the caller-built capture block is released
        // with this generation's destroy_fn (the only code that knows its layout).
        if (destroy != NULL) {
            destroy(captures);
        }
        GDCC_PRINT_RUNTIME_ERROR("gdcc: failed to create hot-reload thunk for a lambda Callable",
                "gdcc_hrx_create_lambda", NULL, 0);
        return gdcc_hrx_invalid_callable();
    }
    // Registration strictly precedes publication so deinitialize can never miss a live spec.
    gdcc_hrx_registry_push(hub, spec);
    spec->refcount = 1;
    godot_Callable result = gdcc_hrx_callable_from_spec(spec, object_id);
    if (out_spec != NULL) {
        *out_spec = spec;
    }
    return result;
}

godot_Callable gdcc_hrx_create_standalone(
        const char *kind,
        const char *owner,
        const char *name,
        godot_int utility_hash,
        int argument_count,
        godot_bool is_vararg,
        godot_bool returns_value,
        gdcc_hrx_impl call_impl,
        gdcc_hrx_is_valid_fn is_valid_impl,
        const gdcc_hrx_identity *identity
) {
    gdcc_hrx_hub *hub = g_hrx_hub;
    if (g_hrx_mode != GDCC_HRX_MODE_ACTIVE || hub == NULL || identity == NULL || identity->impl_key == NULL) {
        return gdcc_hrx_invalid_callable();
    }
    // Interning hit: share `(thunk, spec)` so Godot's default `(call_func, userdata)` identity
    // keeps two independently-built Callables for the same standalone equal. A hit is reusable
    // only while it is alive AND bound to a schema-compatible implementation: a zombie left
    // over from a schema-changing reload (impl_ptr NULLed) must never father new Callables.
    gdcc_hrx_spec *hit = gdcc_hrx_intern_find(hub, identity->impl_key);
    if (hit != NULL && !hit->dead
            && hit->binding_state == GDCC_HRX_BOUND_COMPATIBLE
            && gdcc_hrx_desc_matches(hit, identity)) {
        hit->refcount++;
        return gdcc_hrx_callable_from_spec(hit, 0);
    }
    if (hit != NULL) {
        // Corpse or incompatible zombie: detach it from the intern table so the fresh spec
        // below owns the key. The old shell stays registered for the sweep, keeping the
        // fail-closed invalidation of Callables that still reference it.
        gdcc_hrx_intern_remove(hub, hit);
    }
    gdcc_hrx_standalone_payload *payload = (gdcc_hrx_standalone_payload *)godot_mem_alloc(sizeof(gdcc_hrx_standalone_payload));
    if (payload == NULL) {
        GDCC_PRINT_RUNTIME_ERROR("gdcc: out of memory cloning a standalone Callable payload",
                "gdcc_hrx_create_standalone", NULL, 0);
        return gdcc_hrx_invalid_callable();
    }
    payload->kind = gdcc_hrx_heap_strdup(kind);
    payload->owner = gdcc_hrx_heap_strdup(owner);
    payload->name = gdcc_hrx_heap_strdup(name);
    payload->utility_hash = utility_hash;
    payload->argument_count = argument_count;
    payload->is_vararg = is_vararg;
    payload->returns_value = returns_value;
    if (payload->kind == NULL || payload->owner == NULL || payload->name == NULL) {
        gdcc_hrx_standalone_payload_free(payload);
        GDCC_PRINT_RUNTIME_ERROR("gdcc: out of memory cloning a standalone Callable payload",
                "gdcc_hrx_create_standalone", NULL, 0);
        return gdcc_hrx_invalid_callable();
    }
    // destroy_fn is permanently NULL for standalones: the payload is fixed-ABI shell metadata
    // freed unconditionally by the sweep paths; schema matching only protects lambda captures.
    gdcc_hrx_spec *spec = gdcc_hrx_spec_new(hub, payload, call_impl, is_valid_impl, NULL,
            (int32_t)argument_count, identity, 1);
    if (spec == NULL) {
        gdcc_hrx_standalone_payload_free(payload);
        GDCC_PRINT_RUNTIME_ERROR("gdcc: failed to create hot-reload thunk for a standalone Callable",
                "gdcc_hrx_create_standalone", NULL, 0);
        return gdcc_hrx_invalid_callable();
    }
    gdcc_hrx_registry_push(hub, spec);
    spec->refcount = 1;
    gdcc_hrx_intern_insert(hub, spec);
    return gdcc_hrx_callable_from_spec(spec, 0);
}

godot_Callable gdcc_hrx_callable_retain(gdcc_hrx_spec *spec) {
    if (spec == NULL || spec->dead) {
        return gdcc_hrx_invalid_callable();
    }
    spec->refcount++;
    return gdcc_hrx_callable_from_spec(spec, 0);
}

// ---------------------------------------------------------------------------
// Sweeper (live generation) + pending drain.
// ---------------------------------------------------------------------------
void gdcc_hrx_sweep(gdcc_hrx_spec *spec) {
    gdcc_hrx_hub *hub = g_hrx_hub;
    if (hub == NULL || spec == NULL) {
        return;
    }
    if (hub->sweep_depth > 0) {
        // Reentrant free (a destroy_fn released another Callable): defer to the drain so the
        // in-flight sweep never chases pointers across its own destruction.
        gdcc_hrx_pending_push(hub, spec);
        return;
    }
    gdcc_hrx_intern_remove(hub, spec);   // index first: a same-identity recreate must not hit a corpse
    gdcc_hrx_dll_remove(hub, spec);
    hub->sweep_depth++;
    if (spec->binding_state == GDCC_HRX_BOUND_COMPATIBLE && spec->destroy_fn != NULL) {
        spec->destroy_fn(spec->captures); // this generation's code: layout is known
    } else if (spec->captures != NULL && !spec->interned) {
        // Unknown capture layout: leak the block rather than risk a wrong-layout destruction.
        hub->leaked_capture_count++;
    }
    gdcc_hrx_shell_free(hub, spec);
    hub->sweep_depth--;
    if (hub->sweep_depth == 0) {
        while (hub->sweep_pending != NULL) {
            gdcc_hrx_spec *pending = hub->sweep_pending;
            hub->sweep_pending = pending->pending_next;
            pending->pending_next = NULL;
            gdcc_hrx_sweep(pending);
        }
    }
}

// ---------------------------------------------------------------------------
// Rebind (new generation) + two-phase sweep.
// ---------------------------------------------------------------------------
static const gdcc_hrx_rebind_entry *gdcc_hrx_find_rebind_entry(
        const gdcc_hrx_rebind_entry *entries,
        uint32_t entry_count,
        const char *impl_key
) {
    if (impl_key == NULL) {
        return NULL;
    }
    for (uint32_t i = 0; i < entry_count; i++) {
        if (entries[i].identity != NULL && entries[i].identity->impl_key != NULL
                && strcmp(entries[i].identity->impl_key, impl_key) == 0) {
            return &entries[i];
        }
    }
    return NULL;
}

static godot_bool gdcc_hrx_desc_matches(const gdcc_hrx_spec *spec, const gdcc_hrx_identity *identity) {
    return identity != NULL
            && spec->schema_desc_len == identity->schema_desc_len
            && (spec->schema_desc_len == 0
                || memcmp(spec->schema_desc, identity->schema_desc, spec->schema_desc_len) == 0);
}

/// Third rebind gate (§5.11): NULL-safe callsite_context equality. Standalone identities carry
/// NULL on both sides (match); exactly one NULL means a context-carrying lambda met a
/// context-less entry (mismatch). Callers must have passed the abi_version guard first — the
/// field is only read on current-version specs.
static godot_bool gdcc_hrx_context_matches(const gdcc_hrx_spec *spec, const gdcc_hrx_identity *identity) {
    if (spec->callsite_context == NULL || identity->callsite_context == NULL) {
        return spec->callsite_context == identity->callsite_context;
    }
    return strcmp(spec->callsite_context, identity->callsite_context) == 0;
}

static godot_bool gdcc_hrx_fingerprint_matches(const gdcc_hrx_spec *spec, const gdcc_hrx_identity *identity) {
    return identity != NULL
            && memcmp(spec->schema_fingerprint, identity->schema_fingerprint, sizeof(spec->schema_fingerprint)) == 0;
}

static void gdcc_hrx_rebind_and_sweep(gdcc_hrx_hub *hub, const gdcc_hrx_rebind_entry *entries, uint32_t entry_count) {
    // Phase 1 (single pass, NO destruction): detach the fully-dead into a private worklist and
    // rebind every survivor in place. Destruction is deferred to phase 2 because it may free
    // sibling Callables whose specs must already hold the new generation's destroy_fn.
    gdcc_hrx_spec *worklist = NULL;
    gdcc_hrx_spec *spec = hub->registry;
    while (spec != NULL) {
        gdcc_hrx_spec *next = spec->next;
        if (spec->dead && spec->refcount == 0) {
            gdcc_hrx_intern_remove(hub, spec);
            gdcc_hrx_dll_remove(hub, spec);
            spec->pending_next = worklist;
            worklist = spec;
        } else if (!spec->dead) {
            const gdcc_hrx_rebind_entry *entry = gdcc_hrx_find_rebind_entry(entries, entry_count, spec->impl_key);
            // Version guard FIRST (§5.11): a v1 spec block predates callsite_context, so the
            // field may only be read when the versions match (short-circuit order is load-bearing).
            if (entry != NULL && spec->abi_version == GDCC_HRX_ABI_VERSION
                    && gdcc_hrx_desc_matches(spec, entry->identity)
                    && gdcc_hrx_context_matches(spec, entry->identity)) {
                // In-place upgrade to the new code; captures stay untouched.
                spec->impl_ptr = entry->impl;
                spec->destroy_fn = entry->destroy;
                spec->is_valid_fn = entry->is_valid;
                spec->argument_count = entry->identity->argument_count;
                spec->binding_state = GDCC_HRX_BOUND_COMPATIBLE;
            } else {
                spec->binding_state = GDCC_HRX_UNBOUND_INCOMPATIBLE;
            }
        }
        spec = next;
    }
    // Phase 2 (execute): destroy the dead worklist. The destroy_fn comes from the REBIND TABLE
    // (never from the spec, whose pointers deinitialize NULLed); a fingerprint+descriptor match
    // is required before any destruction, otherwise the capture block is intentionally leaked.
    while (worklist != NULL) {
        gdcc_hrx_spec *item = worklist;
        worklist = item->pending_next;
        item->pending_next = NULL;
        hub->sweep_depth++;
        const gdcc_hrx_rebind_entry *entry = gdcc_hrx_find_rebind_entry(entries, entry_count, item->impl_key);
        if (entry != NULL && entry->destroy != NULL
                && gdcc_hrx_fingerprint_matches(item, entry->identity)
                && gdcc_hrx_desc_matches(item, entry->identity)) {
            entry->destroy(item->captures);
        } else if (item->captures != NULL && !item->interned) {
            hub->leaked_capture_count++;
        }
        gdcc_hrx_shell_free(hub, item);
        hub->sweep_depth--;
        if (hub->sweep_depth == 0) {
            while (hub->sweep_pending != NULL) {
                gdcc_hrx_spec *pending = hub->sweep_pending;
                hub->sweep_pending = pending->pending_next;
                pending->pending_next = NULL;
                gdcc_hrx_sweep(pending);
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Anchor (Engine singleton instance binding) + lifecycle.
// ---------------------------------------------------------------------------
#define GDCC_HRX_INTERN_BUCKETS_INIT 64u

/// Upper bound of NULL-tombstone slots swept before hub lookup (§5.5): each
/// `free_instance_binding` removes the FIRST token match, so stacked tombstones left by
/// repeated failed creations (or an unfixed runtime build) are cleared one per iteration
/// until a real hub surfaces or none remain. `object_has_instance_binding` is not exposed
/// through the GDExtension interface, so an exhausted table is indistinguishable from a
/// tombstone — the loop simply stops at the cap (a no-op `free_instance_binding` is
/// harmless); more stacked tombstones than the cap counts as foreign tampering and the
/// fresh hub may stay shadowed (documented limitation).
#define GDCC_HRX_TOMBSTONE_SWEEP_MAX 8u

static void *gdcc_hrx_anchor_create(void *p_token, void *p_instance) {
    (void)p_token;
    (void)p_instance;
    gdcc_hrx_hub *hub = (gdcc_hrx_hub *)godot_mem_alloc(sizeof(gdcc_hrx_hub));
    if (hub == NULL) {
        return NULL;
    }
    gdcc_hrx_spec **table = (gdcc_hrx_spec **)godot_mem_alloc(GDCC_HRX_INTERN_BUCKETS_INIT * sizeof(gdcc_hrx_spec *));
    if (table == NULL) {
        godot_mem_free(hub);
        return NULL;
    }
    // The shared thunk page is published BEFORE the hub becomes visible: no Callable exists
    // yet, so the one-shot RW->write->RX transition can never strand published code.
    void *thunk_page = gdcc_hrx_thunk_page_publish();
    if (thunk_page == NULL) {
        godot_mem_free(table);
        godot_mem_free(hub);
        return NULL;
    }
    for (uint32_t i = 0; i < GDCC_HRX_INTERN_BUCKETS_INIT; i++) {
        table[i] = NULL;
    }
    hub->magic = GDCC_HRX_HUB_MAGIC;
    hub->version = GDCC_HRX_HUB_VERSION;
    hub->sweep_depth = 0;
    hub->registry = NULL;
    hub->intern_table = table;
    hub->intern_table_size = GDCC_HRX_INTERN_BUCKETS_INIT;
    hub->intern_count = 0;
    hub->sweeper = NULL;
    hub->sweep_pending = NULL;
    hub->thunk_page = thunk_page;
    hub->leaked_capture_count = 0;
    return hub;
}

/// The engine copies the free/reference pointers into the binding slot (object.h): both MUST
/// stay NULL so no library address outlives its image. `create_callback` is not stored by the
/// engine (it runs during the lookup call only), so it may live in library code.
static const GDExtensionInstanceBindingCallbacks gdcc_hrx_anchor_callbacks = {
    .create_callback = gdcc_hrx_anchor_create,
    .free_callback = NULL,
    .reference_callback = NULL,
};

static gdcc_hrx_hub *gdcc_hrx_hub_create_and_attach(GDExtensionObjectPtr engine_object, uint64_t anchor_token) {
    void *binding = godot_object_get_instance_binding(engine_object, (void *)anchor_token, &gdcc_hrx_anchor_callbacks);
    if (binding == NULL) {
        // The engine keeps the freshly appended slot even when create_callback returned NULL
        // (object.cpp:2116-2147): such a NULL tombstone shadows every later lookup of this
        // token (first match wins), so it must be removed or no hub could ever take over.
        godot_object_free_instance_binding(engine_object, (void *)anchor_token);
        return NULL;
    }
    gdcc_hrx_hub *hub = (gdcc_hrx_hub *)binding;
    if (hub->magic != GDCC_HRX_HUB_MAGIC || hub->version != GDCC_HRX_HUB_VERSION) {
        return NULL;
    }
    return hub;
}

gdcc_hrx_mode gdcc_hrx_initialize_core(
        GDExtensionClassLibraryPtr library,
        GDExtensionObjectPtr engine_object,
        bool is_editor,
        bool execmem_probe_ok,
        uint64_t anchor_token,
        const gdcc_hrx_rebind_entry *entries,
        uint32_t entry_count
) {
    class_library = library;
    if (g_hrx_mode == GDCC_HRX_MODE_UNINITIALIZED) {
        if (!is_editor) {
            // Non-editor process: no hub, no anchor, no executable memory; callables stay direct.
            g_hrx_mode = GDCC_HRX_MODE_DIRECT_NON_RELOAD;
            return g_hrx_mode;
        }
        // The hub is consulted BEFORE the probe: if an older generation already published one,
        // its specs/thunks exist and only the HRX path can redeem them (never fall back to direct).
        // First sweep any NULL tombstones shadowing the token (each free removes the first
        // match): a valid hub buried behind them must surface and be taken over.
        for (uint32_t sweep = 0; sweep < GDCC_HRX_TOMBSTONE_SWEEP_MAX; sweep++) {
            if (godot_object_get_instance_binding(engine_object, (void *)anchor_token, NULL) != NULL) {
                break;
            }
            godot_object_free_instance_binding(engine_object, (void *)anchor_token);
        }
        gdcc_hrx_hub *hub = (gdcc_hrx_hub *)godot_object_get_instance_binding(engine_object, (void *)anchor_token, NULL);
        if (hub == NULL) {
            if (!execmem_probe_ok) {
                g_hrx_mode = GDCC_HRX_MODE_UNAVAILABLE;
                gdcc_hrx_report_unavailable();
                return g_hrx_mode;
            }
            hub = gdcc_hrx_hub_create_and_attach(engine_object, anchor_token);
            if (hub == NULL) {
                g_hrx_mode = GDCC_HRX_MODE_UNAVAILABLE;
                gdcc_hrx_report_unavailable();
                return g_hrx_mode;
            }
        } else if (hub->magic != GDCC_HRX_HUB_MAGIC || hub->version != GDCC_HRX_HUB_VERSION) {
            // Foreign/corrupt anchor: orphan it untouched (leak-on-purpose, never traverse),
            // drop the binding and mount a fresh hub. A corrupt anchor is NOT proof of a
            // working thunk-page environment, so the probe still gates the fresh hub.
            g_hrx_orphaned_hub_count++;
            godot_object_free_instance_binding(engine_object, (void *)anchor_token);
            if (!execmem_probe_ok) {
                g_hrx_mode = GDCC_HRX_MODE_UNAVAILABLE;
                gdcc_hrx_report_unavailable();
                return g_hrx_mode;
            }
            hub = gdcc_hrx_hub_create_and_attach(engine_object, anchor_token);
            if (hub == NULL) {
                g_hrx_mode = GDCC_HRX_MODE_UNAVAILABLE;
                gdcc_hrx_report_unavailable();
                return g_hrx_mode;
            }
        }
        g_hrx_hub = hub;
        g_hrx_mode = GDCC_HRX_MODE_ACTIVE;
    }
    if (g_hrx_mode != GDCC_HRX_MODE_ACTIVE || g_hrx_hub == NULL) {
        return g_hrx_mode;
    }
    // (Re)start a generation on the hub: register this image's sweeper first, then rebind
    // survivors and sweep the dead (free thunks can fire as soon as the sweeper is visible).
    g_hrx_hub->sweeper = gdcc_hrx_sweep;
    gdcc_hrx_rebind_and_sweep(g_hrx_hub, entries, entry_count);
    return g_hrx_mode;
}

gdcc_hrx_mode gdcc_hrx_initialize(
        GDExtensionClassLibraryPtr library,
        uint64_t anchor_token,
        const gdcc_hrx_rebind_entry *entries,
        uint32_t entry_count
) {
    gdcc_init();
    const bool editor = gdcc_is_editor_hint();
    const godot_bool probe_ok = editor ? gdcc_hrx_execmem_probe() : false;
    return gdcc_hrx_initialize_core(
            library,
            (GDExtensionObjectPtr)godot_Engine_singleton(),
            editor,
            probe_ok,
            anchor_token,
            entries,
            entry_count
    );
}

void gdcc_hrx_deinitialize(void) {
    if (g_hrx_mode != GDCC_HRX_MODE_ACTIVE || g_hrx_hub == NULL) {
        return;
    }
    // D8 tail: detach the sweeper first (free thunks from now on only mark dead), then NULL
    // every spec's code pointers. dead/refcount stay untouched for the next generation's sweep.
    gdcc_hrx_hub *hub = g_hrx_hub;
    hub->sweeper = NULL;
    for (gdcc_hrx_spec *spec = hub->registry; spec != NULL; spec = spec->next) {
        spec->impl_ptr = NULL;
        spec->destroy_fn = NULL;
        spec->is_valid_fn = NULL;
        spec->binding_state = GDCC_HRX_UNBOUND_INCOMPATIBLE;
    }
}
