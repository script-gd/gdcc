// DSO leg of the TLS unload-reclaim gate (MinicoroTlsUnloadReclaimTest): every dlopen round
// creates, enters and destroys one coroutine, so the image's TLS path (pthread key on
// tcc/aarch64, native TLS elsewhere) is created, exercised and torn down again per round.
//
// Single-TU DSO: the implementation is pulled in through minicoro.c (production
// MCO_USE_ASM/allocator configuration included) so the anchor and the path report below see
// the TLS selection chain's result — its macros live inside MINICORO_IMPL and are invisible
// to a declarations-only TU.
#include <minicoro.c>
#include <stdio.h>
#include <stdlib.h>

// Compile-time anchor: under tcc on aarch64 the pthread-key TLS path must be selected — tcc's
// native __thread is unsafe for a DSO there (local-exec aliases host TLS slots on ELF; Mach-O
// has no TLV support), so a selection-chain regression must fail this compile.
#if defined(__TINYC__) && defined(__aarch64__) && !defined(_WIN32) && !defined(MCO_USE_PTHREAD_TLS)
#error "tcc aarch64 must select the MCO_USE_PTHREAD_TLS path"
#endif

// Which TLS storage this image was built with: 1 = pthread key, 0 = native TLS. The host
// asserts the reported value against the platform contract.
int gdcc_tls_unload_probe_tls_path(void) {
#ifdef MCO_USE_PTHREAD_TLS
    return 1;
#else
    return 0;
#endif
}

typedef struct RoundState {
    int entered;
    int identity_ok;
} RoundState;

static void round_body(mco_coro* co) {
    RoundState* state = (RoundState*)mco_get_user_data(co);
    state->entered = 1;
    // mco_running() must identify THIS coroutine through the image's TLS path.
    state->identity_ok = (mco_running() == co) ? 1 : 0;
    mco_yield(co);
}

// One round: create, enter once (TLS write + read on the coroutine path), destroy before
// returning — the host unloads the image right after, and no coroutine may dangle into
// unloaded code.
int gdcc_tls_unload_probe_round(void) {
    RoundState state;
    mco_coro* co;
    mco_desc desc;
    state.entered = 0;
    state.identity_ok = 0;
    desc = mco_desc_init(round_body, 0);
    desc.user_data = &state;
    if (mco_create(&co, &desc) != MCO_SUCCESS) return 11;
    if (mco_resume(co) != MCO_SUCCESS) { mco_destroy(co); return 12; }
    if (!state.entered || !state.identity_ok) { mco_destroy(co); return 13; }
    if (mco_destroy(co) != MCO_SUCCESS) return 14;
    return 0;
}

// Unload witness: proves image destructors (minicoro's pthread-key cleanup among them) really
// run at every dlclose. Spelled __attribute((destructor)) for the same reason as the minicoro
// patch — glibc headers may macro-erase the __attribute__ identifier when the compiler (tcc)
// does not advertise __GNUC__. The host counts the appended lines after all rounds.
__attribute((destructor)) static void gdcc_tls_unload_probe_unloaded(void) {
    const char* path = getenv("GDCC_TLS_UNLOAD_LOG");
    FILE* file;
    if (path == NULL) return;
    file = fopen(path, "a");
    if (file == NULL) return;
    fputs("D\n", file);
    fclose(file);
}
