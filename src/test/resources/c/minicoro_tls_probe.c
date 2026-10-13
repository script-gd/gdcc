// Single-TU probe: the implementation is pulled in through minicoro.c (which applies the
// production MCO_USE_ASM/allocator configuration) so the compile-time anchors below see the
// TLS selection chain's result — its macros live inside MINICORO_IMPL and are invisible to a
// declarations-only TU.
#include <minicoro.c>
#include <stdint.h>
#include <stdio.h>

// Compile-time anchor: the gdcc minicoro patch forbids the thread-unsafe fallback;
// this TU must not compile at all if the fallback ever engages under either compiler.
#if defined(MCO_NO_MULTITHREAD)
#error "the TLS probe requires a thread-local mco_running(); MCO_NO_MULTITHREAD must stay undefined"
#endif

// Compile-time anchor: under tcc on aarch64 the pthread-key TLS path must be selected — tcc's
// native __thread is unsafe for a DSO there (local-exec aliases host TLS slots on ELF; Mach-O
// has no TLV support), so a regression in the selection chain must fail the compile.
#if defined(__TINYC__) && defined(__aarch64__) && !defined(_WIN32) && !defined(MCO_USE_PTHREAD_TLS)
#error "tcc aarch64 must select the MCO_USE_PTHREAD_TLS path"
#endif

#if defined(_WIN32)
#include <windows.h>
#else
#include <pthread.h>
#endif

#define THREAD_COUNT 4
#define ROUNDS 250

// A mutex-protected one-shot barrier is the only cross-thread rendezvous: volatile
// flag spinning would be an ISO C data race, and C11 atomics are not portable across
// every tcc target configuration. The Windows leg uses CRITICAL_SECTION + a
// manual-reset event because the vendored winapi subset carries no
// CONDITION_VARIABLE. Everything else per-thread is joined (happens-before) before
// the main thread reads it.
typedef struct ProbeBarrier {
#if defined(_WIN32)
    CRITICAL_SECTION mu;
    HANDLE event; // manual-reset
#else
    pthread_mutex_t mu;
    pthread_cond_t cv;
#endif
    int arrived;
    int expected;
} ProbeBarrier;

static int barrier_init(ProbeBarrier *b, int expected) {
#if defined(_WIN32)
    InitializeCriticalSection(&b->mu);
    b->event = CreateEvent(NULL, TRUE, FALSE, NULL);
    if (b->event == NULL) return -1;
#else
    if (pthread_mutex_init(&b->mu, NULL) != 0) return -1;
    if (pthread_cond_init(&b->cv, NULL) != 0) return -1;
#endif
    b->arrived = 0;
    b->expected = expected;
    return 0;
}

static int barrier_wait(ProbeBarrier *b) {
#if defined(_WIN32)
    int last;
    EnterCriticalSection(&b->mu);
    b->arrived++;
    last = (b->arrived >= b->expected);
    LeaveCriticalSection(&b->mu);
    // Manual-reset event: once signaled it stays signaled, so a thread arriving late
    // still passes; the signal is outside the lock so no waiter can miss it.
    if (last && !SetEvent(b->event)) return -1;
    return WaitForSingleObject(b->event, INFINITE) == WAIT_OBJECT_0 ? 0 : -1;
#else
    pthread_mutex_lock(&b->mu);
    b->arrived++;
    if (b->arrived >= b->expected) {
        pthread_cond_broadcast(&b->cv);
    } else {
        while (b->arrived < b->expected) {
            pthread_cond_wait(&b->cv, &b->mu);
        }
    }
    pthread_mutex_unlock(&b->mu);
    return 0;
#endif
}

// One failure slot per thread — written only by the owning thread, read by main after
// the join. No shared counter, so no increment can be torn or lost.
static int g_failures[THREAD_COUNT];
// Two one-shot barrier PAIRS: entering/identity-sampled. The second barrier of each
// pair closes the sequential-scheduling escape hatch — without it, a shared
// mco_current_co could be restored along the resume chain one thread at a time and
// every identity check would fake-pass.
static ProbeBarrier g_main_enter, g_main_checked, g_cancel_enter, g_cancel_checked;

typedef struct CoroCtx {
    int thread_index;
    int step;
    int is_cancel_target;
} CoroCtx;

static void worker_body(mco_coro *co) {
    CoroCtx *ctx = (CoroCtx *)mco_get_user_data(co);
    int *failures = &g_failures[ctx->thread_index];
    if (ctx->is_cancel_target) {
        // Same two-phase discipline as the main coroutines: every cancel target is
        // in flight while every identity check runs.
        if (barrier_wait(&g_cancel_enter) != 0) { (*failures)++; return; }
        if (mco_running() != co) (*failures)++;
        if (barrier_wait(&g_cancel_checked) != 0) { (*failures)++; return; }
        mco_yield(co);
        for (;;) mco_yield(co);
    }
    for (int i = 0; i < ROUNDS; i++) {
        if (i == 0) {
            if (barrier_wait(&g_main_enter) != 0) { (*failures)++; return; }
            if (mco_running() != co) (*failures)++;
            if (barrier_wait(&g_main_checked) != 0) { (*failures)++; return; }
        }
        if (mco_running() != co) (*failures)++;
        ctx->step++;
        mco_yield(co);
    }
}

static void thread_main(int index) {
    int *failures = &g_failures[index];
    CoroCtx main_ctx = { index, 0, 0 };
    CoroCtx cancel_ctx = { index, 0, 1 };
    mco_desc desc = mco_desc_init(worker_body, 0);
    desc.user_data = &main_ctx;
    mco_coro *main_co = NULL;
    if (mco_create(&main_co, &desc) != MCO_SUCCESS) { (*failures)++; return; }
    desc = mco_desc_init(worker_body, 0);
    desc.user_data = &cancel_ctx;
    mco_coro *cancel_co = NULL;
    if (mco_create(&cancel_co, &desc) != MCO_SUCCESS) { (*failures)++; return; }

    // The main coroutine's step 0 blocks at the barrier until every thread is inside
    // its own body; the remaining ROUNDS-1 steps run overlapped.
    if (mco_resume(main_co) != MCO_SUCCESS) (*failures)++;
    if (mco_resume(cancel_co) != MCO_SUCCESS) (*failures)++;

    // Cancellation: the suspended secondary coroutine is destroyed mid-flight; the
    // primary coroutine on the same thread must continue completely unaffected.
    if (mco_status(cancel_co) != MCO_SUSPENDED) (*failures)++;
    if (mco_destroy(cancel_co) != MCO_SUCCESS) (*failures)++;

    while (mco_status(main_co) == MCO_SUSPENDED) {
        if (mco_resume(main_co) != MCO_SUCCESS) { (*failures)++; break; }
    }
    if (mco_status(main_co) != MCO_DEAD) (*failures)++;
    if (main_ctx.step != ROUNDS) (*failures)++;
    if (mco_destroy(main_co) != MCO_SUCCESS) (*failures)++;
    printf("EV t%d done steps=%d\n", index, main_ctx.step);
    fflush(stdout);
}

#if defined(_WIN32)
static DWORD WINAPI thread_shim(LPVOID p) {
    thread_main((int)(uintptr_t)p);
    return 0;
}
#else
static void *thread_shim(void *p) {
    thread_main((int)(uintptr_t)p);
    return NULL;
}
#endif

int main(void) {
    if (barrier_init(&g_main_enter, THREAD_COUNT) != 0
            || barrier_init(&g_main_checked, THREAD_COUNT) != 0
            || barrier_init(&g_cancel_enter, THREAD_COUNT) != 0
            || barrier_init(&g_cancel_checked, THREAD_COUNT) != 0) {
        return 10;
    }
#if defined(_WIN32)
    HANDLE threads[THREAD_COUNT];
    for (int i = 0; i < THREAD_COUNT; i++) {
        threads[i] = CreateThread(NULL, 0, thread_shim, (LPVOID)(uintptr_t)i, 0, NULL);
        if (threads[i] == NULL) return 10;
    }
    WaitForMultipleObjects(THREAD_COUNT, threads, TRUE, INFINITE);
    for (int i = 0; i < THREAD_COUNT; i++) CloseHandle(threads[i]);
#else
    pthread_t threads[THREAD_COUNT];
    for (int i = 0; i < THREAD_COUNT; i++) {
        if (pthread_create(&threads[i], NULL, thread_shim, (void *)(uintptr_t)i) != 0) return 10;
    }
    for (int i = 0; i < THREAD_COUNT; i++) pthread_join(threads[i], NULL);
#endif
    // join gives the happens-before edge; the per-thread slots need no atomics here.
    int failures = g_failures[0] + g_failures[1] + g_failures[2] + g_failures[3];
    if (failures != 0) {
        printf("EV failures=%d\n", failures);
        fflush(stdout);
        return 1;
    }
    printf("OK tls_isolation\n");
    return 0;
}
