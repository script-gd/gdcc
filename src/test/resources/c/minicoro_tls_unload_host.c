// Host leg of the TLS unload-reclaim gate (MinicoroTlsUnloadReclaimTest):
// dlopen -> probe round -> dlclose in a loop within one process. The round count must exceed
// the platform's pthread-key ceiling (glibc 1024, Darwin 512): if the image's destructor ever
// stops deleting its TLS key, pthread_key_create exhausts and aborts the process before the
// final round, so completing the loop is itself the leak proof.
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>

// Occupy executable TLS so a mis-linked local-exec DSO cannot get lucky.
static __thread volatile long sentinel = 0x12345678;

int main(int argc, char** argv) {
    if (argc != 4) return 2;
    const char* image = argv[1];
    int rounds = atoi(argv[2]);
    if (rounds < 1) return 2;
    // Side channel for the image's unload witness (see the DSO leg).
    if (setenv("GDCC_TLS_UNLOAD_LOG", argv[3], 1) != 0) return 2;
    printf("HOST_TLS %lx\n", (unsigned long)sentinel);
    for (int i = 0; i < rounds; i++) {
        void* library = dlopen(image, RTLD_NOW | RTLD_LOCAL);
        int (*tls_path)(void);
        int (*round)(void);
        int result;
        if (library == NULL) { fprintf(stderr, "dlopen: %s\n", dlerror()); return 3; }
        tls_path = (int (*)(void))dlsym(library, "gdcc_tls_unload_probe_tls_path");
        round = (int (*)(void))dlsym(library, "gdcc_tls_unload_probe_round");
        if (tls_path == NULL || round == NULL) { fprintf(stderr, "dlsym: %s\n", dlerror()); return 4; }
        if (i == 0) printf("TLS_PATH %s\n", tls_path() ? "pthread" : "native");
        result = round();
        if (sentinel != 0x12345678) return 5;
        if (result != 0) return result; // 11-14 come from the DSO leg
        if (dlclose(library) != 0) return 6;
        // Unload must be real, not just refcount bookkeeping: a still-resident image would
        // answer RTLD_NOLOAD with a handle.
        if (dlopen(image, RTLD_NOW | RTLD_LOCAL | RTLD_NOLOAD) != NULL) return 8;
    }
    printf("DLOPEN_ROUNDS_PASS %d\n", rounds);
    return 0;
}
