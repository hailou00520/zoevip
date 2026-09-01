#include <jni.h>
#include <android/log.h>
#include <inttypes.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/mman.h>
#include <strings.h>
#include <unistd.h>

#define LOG_TAG "ZoeVIP:NativePatch"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static const char *kLibName = "libapp.so";

static int line_has_libapp(const char *line) {
    const char *hit = strstr(line, kLibName);
    if (hit == NULL) {
        return 0;
    }
    char next = hit[strlen(kLibName)];
    return next == '\0' || next == '\n' || next == ' ';
}

static int line_is_rx(const char *line) {
    return strstr(line, " r-xp ") != NULL || strstr(line, " r-x ") != NULL;
}

static FILE *open_proc_maps(void) {
    int fd = open("/proc/self/maps", O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        return NULL;
    }
    FILE *maps = fdopen(fd, "r");
    if (maps == NULL) {
        close(fd);
    }
    return maps;
}

static int find_runtime_addr_in_maps(uint32_t container_off, uint32_t file_offset,
                                     int (*line_match)(const char *),
                                     uintptr_t *addr_out) {
    FILE *maps = open_proc_maps();
    if (maps == NULL) {
        return -1;
    }
    const uint32_t target_off = container_off + file_offset;
    char line[1024];
    while (fgets(line, sizeof(line), maps) != NULL) {
        if (line_match != NULL && !line_match(line)) {
            continue;
        }
        if (!line_is_rx(line)) {
            continue;
        }
        uintptr_t start = 0;
        uintptr_t end = 0;
        uintptr_t map_off = 0;
        if (sscanf(line, "%" SCNxPTR "-%" SCNxPTR " %*s %" SCNxPTR, &start, &end, &map_off) != 3) {
            continue;
        }
        if (target_off < map_off) {
            continue;
        }
        uintptr_t delta = (uintptr_t) target_off - map_off;
        if (delta >= end - start) {
            continue;
        }
        *addr_out = start + delta;
        fclose(maps);
        return 0;
    }
    fclose(maps);
    return -1;
}

typedef struct {
    uint32_t file_offset;
    uint32_t expected;
    uint32_t patched;
    const char *label;
} PatchEntry;

typedef struct {
    const char *build_id;
    const PatchEntry *entries;
    size_t count;
    const char *label;
} PatchProfile;

// Expected values are little-endian uint32 (in-memory byte order on arm64).
static const PatchEntry kCapyPatches[] = {
        {0x993278U, 0x372000a0U, 0x1f2003d5U, "PaywallGuard.ensureEntitled"},
        {0xb6a1b4U, 0x362000c0U, 0x1f2003d5U, "SettingsScreen subscription card"},
};

static const char kCapyBuildId[] = "b718850999f58c66f90d3cfa50e7b621";

/* Hills 1.8.0: Zot copyWith + Dart entitlement / Pro UI gates (build-id 4592ec97…). */
static const PatchEntry kHillsPatches[] = {
        {0x36F360U, 0xF85E03A1U, 0x910082C1U, "Hills VXa.copyWith(isPro)"},
        {0x315E08U, 0xF85E03A1U, 0x910082C1U, "Hills PlayerConfig copyWith isPro"},
        {0x315E0CU, 0x37200101U, 0x1F2003D5U, "Hills PlayerConfig isPro tbnz"},
        {0x315F78U, 0x37200080U, 0x1F2003D5U, "Hills PlayerConfig isPro tbnz 2"},
        {0x351624U, 0x37200140U, 0x1F2003D5U, "Hills AccountEntitlement check"},
        {0x351D80U, 0x37200700U, 0x1F2003D5U, "Hills AccountEntitlement gate"},
        {0x351BBCU, 0x37200060U, 0x1F2003D5U, "Hills entitlement branch"},
        {0x3550A0U, 0x372000A0U, 0x1F2003D5U, "Hills pro UI gate 1"},
        {0x355138U, 0x372000A0U, 0x1F2003D5U, "Hills pro UI gate 2"},
        {0x355170U, 0x372000A0U, 0x1F2003D5U, "Hills pro UI gate 3"},
        {0x357E04U, 0x372000A0U, 0x1F2003D5U, "Hills pro UI gate 4"},
};

static const char kHillsBuildId[] = "4592ec978eb0ea0b1bd4a9662e69fdef";

/* Hills 1.7.2 (Zot-equivalent): single VXa.copyWith(isPro) site only. */
static const PatchEntry kHills172Patches[] = {
        {0x353F44U, 0xF85E03A1U, 0x910082C1U, "Hills 1.7.2 VXa.copyWith(isPro)"},
};

static const char kHills172BuildId[] = "b7188509d62d9d8d1ab806421701fb0e";

/* Zot nw.a: anchor scan + patch at +48 (ldur -> add for Dart isPro=true). */
static const uint8_t kHills172Anchor[] = {
        0x1f, 0x00, 0x16, 0x6b, 0x61, 0x00, 0x00, 0x54, 0x20, 0xf0, 0x41, 0xb8,
        0x00, 0x80, 0x1c, 0x8b, 0xa0, 0x83, 0x1f, 0xf8, 0x05, 0x36, 0x15, 0x94,
        0xa1, 0x83, 0x5c, 0xf8, 0x01, 0x70, 0x00, 0xb8, 0xa1, 0x03, 0x5d, 0xf8,
        0x01, 0xb0, 0x00, 0xb8, 0xa1, 0x83, 0x5d, 0xf8, 0x01, 0xf0, 0x00, 0xb8,
        0xa1, 0x03, 0x5e, 0xf8, 0x01, 0x30, 0x01, 0xb8, 0xa1, 0x83, 0x5e, 0xf8,
        0x01, 0x70, 0x01, 0xb8, 0xa1, 0x03, 0x5f, 0xf8, 0x01, 0xb0, 0x01, 0xb8,
        0xa1, 0x83, 0x5f, 0xf8, 0x01, 0xf0, 0x01, 0xb8, 0xef, 0x03, 0x1d, 0xaa,
        0xfd, 0x79, 0xc1, 0xa8, 0xc0, 0x03, 0x5f, 0xd6,
};
static const size_t kHills172AnchorLen = sizeof(kHills172Anchor);
static const uint32_t kHills172AnchorPatchOff = 48U;

static int patch_u32(uintptr_t addr, uint32_t expected, uint32_t patched, const char *label);

static int apply_hills_anchored_patch(const char *build_id) {
    if (strcmp(build_id, kHills172BuildId) != 0) {
        return 0;
    }
    FILE *maps = open_proc_maps();
    if (maps == NULL) {
        return 0;
    }
    char line[1024];
    int applied = 0;
    while (fgets(line, sizeof(line), maps) != NULL) {
        if (!line_has_libapp(line) || !line_is_rx(line)) {
            continue;
        }
        uintptr_t start = 0;
        uintptr_t end = 0;
        if (sscanf(line, "%" SCNxPTR "-%" SCNxPTR, &start, &end) != 2) {
            continue;
        }
        if (end <= start) {
            continue;
        }
        const uint8_t *region = (const uint8_t *) start;
        size_t region_len = (size_t) (end - start);
        if (region_len < kHills172AnchorLen + kHills172AnchorPatchOff + 4U) {
            continue;
        }
        for (size_t off = 0; off + kHills172AnchorLen <= region_len; off++) {
            if (memcmp(region + off, kHills172Anchor, kHills172AnchorLen) != 0) {
                continue;
            }
            uintptr_t addr = start + off + kHills172AnchorPatchOff;
            if (patch_u32(addr, 0xF85E03A1U, 0x910082C1U,
                          "Hills 1.7.2 anchored copyWith(isPro)") == 0) {
                applied++;
            }
            break;
        }
        if (applied > 0) {
            break;
        }
    }
    fclose(maps);
    if (applied > 0) {
        LOGI("Hills: anchored patch applied");
    }
    return applied;
}

static int encode_build_id(const uint8_t *desc, uint32_t descsz, char *out, size_t out_len) {
    static const char hex[] = "0123456789abcdef";
    size_t pos = 0;
    for (uint32_t b = 0; b < descsz && pos + 1 < out_len; b++) {
        out[pos++] = hex[(desc[b] >> 4) & 0xf];
        out[pos++] = hex[desc[b] & 0xf];
    }
    out[pos] = '\0';
    return (descsz > 0 && pos > 0) ? 0 : -1;
}

static int read_build_id_from_base(uintptr_t base, char *out, size_t out_len) {
    const uint8_t *note = (const uint8_t *) (base + 0x1c8U);
    uint32_t namesz = *(const uint32_t *) note;
    uint32_t descsz = *(const uint32_t *) (note + 4);
    uint32_t note_type = *(const uint32_t *) (note + 8);
    if (namesz != 4U || descsz != 16U || note_type != 3U) {
        return -1;
    }
    if (memcmp(note + 12, "GNU", 3) != 0) {
        return -1;
    }
    return encode_build_id(note + 16, descsz, out, out_len);
}

static int read_build_id_from_path(const char *path, char *out, size_t out_len) {
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        return -1;
    }
    uint8_t note[32];
    if (lseek(fd, 0x1c8, SEEK_SET) < 0 || read(fd, note, sizeof(note)) != (ssize_t) sizeof(note)) {
        close(fd);
        return -1;
    }
    close(fd);
    uint32_t namesz = *(uint32_t *) note;
    uint32_t descsz = *(uint32_t *) (note + 4);
    uint32_t note_type = *(uint32_t *) (note + 8);
    if (namesz != 4U || descsz != 16U || note_type != 3U) {
        return -1;
    }
    if (memcmp(note + 12, "GNU", 3) != 0) {
        return -1;
    }
    return encode_build_id(note + 16, descsz, out, out_len);
}

static int find_libapp_base(uintptr_t *base_out) {
    FILE *maps = open_proc_maps();
    if (maps == NULL) {
        return -1;
    }
    char line[1024];
    uintptr_t min_base = 0;
    int found = 0;
    while (fgets(line, sizeof(line), maps) != NULL) {
        if (!line_has_libapp(line)) {
            continue;
        }
        uintptr_t start = 0;
        if (sscanf(line, "%" SCNxPTR "-", &start) != 1) {
            continue;
        }
        if (!found || start < min_base) {
            min_base = start;
            found = 1;
        }
    }
    fclose(maps);
    if (!found) {
        return -1;
    }
    *base_out = min_base;
    return 0;
}

static int find_libapp_runtime_addr(uint32_t file_offset, uintptr_t *addr_out) {
    if (find_runtime_addr_in_maps(0U, file_offset, line_has_libapp, addr_out) == 0) {
        return 0;
    }
    /* Fallback: some loaders split libapp segments oddly in /proc/maps. */
    uintptr_t base = 0;
    if (find_libapp_base(&base) == 0) {
        *addr_out = base + (uintptr_t) file_offset;
        return 0;
    }
    return -1;
}

static int find_libapp_path(char *path, size_t path_len) {
    FILE *maps = open_proc_maps();
    if (maps == NULL) {
        return -1;
    }
    char line[1024];
    while (fgets(line, sizeof(line), maps) != NULL) {
        if (!line_has_libapp(line)) {
            continue;
        }
        char *slash = strchr(line, '/');
        if (slash == NULL) {
            continue;
        }
        size_t len = strcspn(slash, "\n");
        if (len >= path_len) {
            continue;
        }
        memcpy(path, slash, len);
        path[len] = '\0';
        fclose(maps);
        return 0;
    }
    fclose(maps);
    return -1;
}

static int patch_u32(uintptr_t addr, uint32_t expected, uint32_t patched, const char *label) {
    uintptr_t page = addr & ~(uintptr_t) (sysconf(_SC_PAGESIZE) - 1);
    if (mprotect((void *) page, (size_t) sysconf(_SC_PAGESIZE) * 2U, PROT_READ | PROT_WRITE | PROT_EXEC) != 0) {
        LOGE("%s mprotect failed: %s", label, strerror(errno));
        return -1;
    }
    volatile uint32_t *slot = (volatile uint32_t *) addr;
    uint32_t current = *slot;
    if (current == patched) {
        LOGI("%s already patched at %p", label, (void *) addr);
        return 0;
    }
    if (current != expected) {
        LOGE("%s expected 0x%08x got 0x%08x at %p", label, expected, current, (void *) addr);
        return -1;
    }
    *slot = patched;
    __builtin___clear_cache((char *) addr, (char *) addr + 4);
    LOGI("%s patched 0x%08x -> 0x%08x at %p", label, expected, patched, (void *) addr);
    return 0;
}

static int apply_profile(const PatchProfile *profile) {
    char build_id[64];
    int id_ok = -1;
    char lib_path[512];
    uintptr_t base = 0;

    if (find_libapp_path(lib_path, sizeof(lib_path)) == 0) {
        id_ok = read_build_id_from_path(lib_path, build_id, sizeof(build_id));
    }
    if (id_ok != 0 && find_libapp_base(&base) == 0) {
        id_ok = read_build_id_from_base(base, build_id, sizeof(build_id));
    }
    if (id_ok != 0) {
        return 0;
    }
    if (strcmp(build_id, profile->build_id) != 0) {
        LOGI("%s: build-id mismatch (%s)", profile->label, build_id);
        return 0;
    }
    LOGI("%s: libapp build-id=%s", profile->label, build_id);

    int anchored = apply_hills_anchored_patch(build_id);
    if (anchored > 0) {
        return anchored;
    }

    int applied = 0;
    for (size_t i = 0; i < profile->count; i++) {
        const PatchEntry *entry = &profile->entries[i];
        uintptr_t addr = 0;
        if (find_libapp_runtime_addr(entry->file_offset, &addr) != 0) {
            LOGE("%s: addr not mapped for %s @0x%x", profile->label, entry->label,
                 entry->file_offset);
            continue;
        }
        if (patch_u32(addr, entry->expected, entry->patched, entry->label) == 0) {
            applied++;
        }
    }
    LOGI("%s: applied %d/%zu code patch(es)", profile->label, applied, profile->count);
    return applied;
}

static int apply_capy_patches(void) {
    static const PatchProfile profile = {
            kCapyBuildId,
            kCapyPatches,
            sizeof(kCapyPatches) / sizeof(kCapyPatches[0]),
            "CapyPlayer",
    };
    return apply_profile(&profile);
}

static int g_hills_patched = 0;
static pthread_t g_hills_patch_thread;

static int hills_patches_complete(int applied, size_t total) {
    if (applied <= 0) {
        return 0;
    }
    if (total <= 1U) {
        return 1;
    }
    size_t need = total >= 6U ? 6U : total;
    return (size_t) applied >= need;
}

static int apply_hills_libapp_patches(void) {
    if (g_hills_patched) {
        return (int) (sizeof(kHillsPatches) / sizeof(kHillsPatches[0]));
    }
    static const PatchProfile profiles[] = {
            {
                    kHillsBuildId,
                    kHillsPatches,
                    sizeof(kHillsPatches) / sizeof(kHillsPatches[0]),
                    "Hills",
            },
            {
                    kHills172BuildId,
                    kHills172Patches,
                    sizeof(kHills172Patches) / sizeof(kHills172Patches[0]),
                    "Hills",
            },
    };
    int best = 0;
    for (size_t i = 0; i < sizeof(profiles) / sizeof(profiles[0]); i++) {
        int applied = apply_profile(&profiles[i]);
        if (applied > best) {
            best = applied;
        }
        if (hills_patches_complete(applied, profiles[i].count)) {
            g_hills_patched = 1;
            break;
        }
    }
    return best;
}

static void *hills_patch_poll_thread(void *arg) {
    (void) arg;
    for (int i = 0; i < 1200 && !g_hills_patched; i++) {
        apply_hills_libapp_patches();
        usleep(50000);
    }
    return NULL;
}

__attribute__((constructor))
static void hills_patch_auto_start(void) {
    if (pthread_create(&g_hills_patch_thread, NULL, hills_patch_poll_thread, NULL) == 0) {
        pthread_detach(g_hills_patch_thread);
    }
}

JNIEXPORT jboolean JNICALL
Java_com_afusekt_lsp_hook_CapyPlayerNativePatch_nativeApplyLibAppPatches(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    return apply_capy_patches() > 0 ? JNI_TRUE : JNI_FALSE;
}

static int libapp_is_mapped(void) {
    uintptr_t base = 0;
    return find_libapp_base(&base) == 0;
}

JNIEXPORT jboolean JNICALL
Java_com_afusekt_lsp_hook_CapyPlayerNativePatch_nativeWaitAndApply(JNIEnv *env, jclass clazz, jint timeout_ms) {
    (void) env;
    (void) clazz;
    int waited = 0;
    const int step = 50;
    while (waited <= timeout_ms) {
        if (apply_capy_patches() > 0) {
            return JNI_TRUE;
        }
        if (!libapp_is_mapped()) {
            usleep((useconds_t) step * 1000U);
            waited += step;
            continue;
        }
        usleep((useconds_t) step * 1000U);
        waited += step;
    }
    return JNI_FALSE;
}

typedef void (*exit_fn)(int);

static exit_fn g_real_exit = NULL;
static uint32_t g_saved_exit[4];
static void *g_exit_trampoline = NULL;
static int g_exit_hook_installed = 0;

static exit_fn g_real_libc_exit = NULL;
static uint32_t g_saved_libc_exit[4];
static void *g_libc_exit_trampoline = NULL;
static int g_libc_exit_hook_installed = 0;

typedef struct {
    const char *libname;   /* substring matched in /proc/self/maps */
    int blocked_status;    /* exit code blocked for this library */
    uintptr_t start;
    uintptr_t end;
    int range_ready;
} ExitGuardTarget;

/* [0] VidHub libnesec: block _exit(28); [1] Afusekt libnative-lib: block _exit(0). */
static ExitGuardTarget g_targets[2] = {
        {"libnesec.so", 28, 0, 0, 0},
        {"libnative-lib.so", 0, 0, 0, 0},
};

static int refresh_target_range(ExitGuardTarget *t) {
    /* Use open/read instead of fopen: fopen is inline-hooked by our own maps
     * filter and its prologue is not relocatable on some bionic builds. */
    int fd = open("/proc/self/maps", O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        return 0;
    }
    char buf[8192];
    char line[512];
    size_t li = 0;
    uintptr_t start = 0;
    uintptr_t end = 0;
    int found = 0;
    ssize_t n;
    while ((n = read(fd, buf, sizeof(buf))) > 0) {
        for (ssize_t i = 0; i < n; i++) {
            char c = buf[i];
            if (c == '\n') {
                line[li] = '\0';
                li = 0;
                if (strstr(line, t->libname) != NULL) {
                    uintptr_t rs = 0;
                    uintptr_t re = 0;
                    if (sscanf(line, "%" SCNxPTR "-%" SCNxPTR, &rs, &re) == 2) {
                        if (!found || rs < start) {
                            start = rs;
                        }
                        if (re > end) {
                            end = re;
                        }
                        found = 1;
                    }
                }
            } else if (li < sizeof(line) - 1U) {
                line[li++] = c;
            }
        }
    }
    close(fd);
    if (!found) {
        return 0;
    }
    t->start = start;
    t->end = end;
    t->range_ready = 1;
    return 1;
}

static int caller_in_target(void *caller, ExitGuardTarget *t) {
    uintptr_t pc = (uintptr_t) caller;
    return t->range_ready && pc >= t->start && pc < t->end;
}

static void exit_guard_hook(int status) {
    void *caller = __builtin_return_address(0);
    for (int i = 0; i < (int) (sizeof(g_targets) / sizeof(g_targets[0])); i++) {
        ExitGuardTarget *t = &g_targets[i];
        if (status == t->blocked_status && caller_in_target(caller, t)) {
            LOGI("blocked _exit(%d) from %s (%p)", status, t->libname, caller);
            return;
        }
    }
    g_real_exit(status);
}

static void libc_exit_guard_hook(int status) {
    void *caller = __builtin_return_address(0);
    for (int i = 0; i < (int) (sizeof(g_targets) / sizeof(g_targets[0])); i++) {
        ExitGuardTarget *t = &g_targets[i];
        if (status == t->blocked_status && caller_in_target(caller, t)) {
            LOGI("blocked exit(%d) from %s (%p)", status, t->libname, caller);
            return;
        }
    }
    g_real_libc_exit(status);
}

static int patch_page_rw(void *addr) {
    long page = sysconf(_SC_PAGESIZE);
    uintptr_t start = (uintptr_t) addr & ~((uintptr_t) page - 1U);
    return mprotect((void *) start, (size_t) page * 2U, PROT_READ | PROT_WRITE | PROT_EXEC);
}

static int install_exit_guard_once(void) {
    if (g_exit_hook_installed) {
        return 1;
    }
    for (int i = 0; i < (int) (sizeof(g_targets) / sizeof(g_targets[0])); i++) {
        refresh_target_range(&g_targets[i]);
    }
    void *target = dlsym(RTLD_DEFAULT, "_exit");
    if (target == NULL) {
        LOGE("exit guard: _exit not found");
        return 0;
    }

    long page = sysconf(_SC_PAGESIZE);
    g_exit_trampoline = mmap(NULL, (size_t) page, PROT_READ | PROT_WRITE | PROT_EXEC,
            MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (g_exit_trampoline == MAP_FAILED) {
        LOGE("exit guard: trampoline mmap failed");
        return 0;
    }

    uint32_t *tramp = (uint32_t *) g_exit_trampoline;
    memcpy(g_saved_exit, target, sizeof(g_saved_exit));
    tramp[0] = g_saved_exit[0];
    tramp[1] = g_saved_exit[1];
    tramp[2] = g_saved_exit[2];
    tramp[3] = g_saved_exit[3];
    intptr_t back_offset = ((intptr_t) target + 16 - (intptr_t) &tramp[4]) / 4;
    tramp[4] = (uint32_t) (0x14000000U | (back_offset & 0x03FFFFFFU));
    __builtin___clear_cache((char *) g_exit_trampoline, (char *) g_exit_trampoline + 20);

    g_real_exit = (exit_fn) g_exit_trampoline;
    if (patch_page_rw(target) != 0) {
        LOGE("exit guard: mprotect failed: %s", strerror(errno));
        return 0;
    }

    intptr_t hook_offset = ((intptr_t) exit_guard_hook - (intptr_t) target) / 4;
    uint32_t *slot = (uint32_t *) target;
    slot[0] = (uint32_t) (0x14000000U | (hook_offset & 0x03FFFFFFU));
    slot[1] = 0xD503201FU;
    slot[2] = 0xD503201FU;
    slot[3] = 0xD503201FU;
    __builtin___clear_cache((char *) target, (char *) target + 16);

    g_exit_hook_installed = 1;
    LOGI("generic _exit guard installed at %p (targets: %s+%s)",
            target, g_targets[0].libname, g_targets[1].libname);

    /* Also guard libc exit(): the watchdog may call exit(0) instead of _exit(0). */
    if (!g_libc_exit_hook_installed) {
        void *exit_target = dlsym(RTLD_DEFAULT, "exit");
        if (exit_target != NULL) {
            void *exit_tramp = mmap(NULL, (size_t) page, PROT_READ | PROT_WRITE | PROT_EXEC,
                    MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
            if (exit_tramp != MAP_FAILED) {
                uint32_t *et = (uint32_t *) exit_tramp;
                memcpy(g_saved_libc_exit, exit_target, sizeof(g_saved_libc_exit));
                et[0] = g_saved_libc_exit[0];
                et[1] = g_saved_libc_exit[1];
                et[2] = g_saved_libc_exit[2];
                et[3] = g_saved_libc_exit[3];
                intptr_t eback = ((intptr_t) exit_target + 16 - (intptr_t) &et[4]) / 4;
                et[4] = (uint32_t) (0x14000000U | (eback & 0x03FFFFFFU));
                __builtin___clear_cache((char *) exit_tramp, (char *) exit_tramp + 20);

                g_libc_exit_trampoline = exit_tramp;
                g_real_libc_exit = (exit_fn) exit_tramp;
                if (patch_page_rw(exit_target) == 0) {
                    intptr_t eoff = ((intptr_t) libc_exit_guard_hook - (intptr_t) exit_target) / 4;
                    uint32_t *eslot = (uint32_t *) exit_target;
                    eslot[0] = (uint32_t) (0x14000000U | (eoff & 0x03FFFFFFU));
                    eslot[1] = 0xD503201FU;
                    eslot[2] = 0xD503201FU;
                    eslot[3] = 0xD503201FU;
                    __builtin___clear_cache((char *) exit_target, (char *) exit_target + 16);
                    g_libc_exit_hook_installed = 1;
                    LOGI("generic exit guard installed at %p", exit_target);
                } else {
                    LOGE("exit guard: mprotect failed for exit: %s", strerror(errno));
                }
            }
        } else {
            LOGE("exit guard: exit not found");
        }
    }
    return 1;
}

static void *exit_guard_thread(void *arg) {
    intptr_t target_index = (intptr_t) arg;
    for (int i = 0; i < 400; i++) {
        if (g_exit_hook_installed
                || (g_targets[target_index].range_ready && install_exit_guard_once())) {
            return NULL;
        }
        if (!g_targets[target_index].range_ready) {
            refresh_target_range(&g_targets[target_index]);
        }
        usleep(50000);
    }
    LOGE("exit guard: timed out waiting for %s", g_targets[target_index].libname);
    return NULL;
}

static int start_exit_guard_thread(int target_index) {
    if (g_targets[target_index].range_ready && install_exit_guard_once()) {
        return 1;
    }
    pthread_t thread;
    if (pthread_create(&thread, NULL, exit_guard_thread, (void *) (intptr_t) target_index) == 0) {
        pthread_detach(thread);
        LOGI("exit guard thread started for %s", g_targets[target_index].libname);
        return 1;
    }
    LOGE("exit guard: thread create failed for %s", g_targets[target_index].libname);
    return 0;
}

typedef FILE *(*fopen_fn)(const char *, const char *);

static fopen_fn g_real_fopen = NULL;
static uint32_t g_saved_fopen[4];
static void *g_fopen_trampoline = NULL;
static int g_maps_filter_installed = 0;

static int maps_line_should_hide(const char *line) {
    static const char *keywords[] = {
            "xposed", "lsposed", "edxposed", "libxposed",
            "zoevip", "obsidian.zot", "/zot/", "lspd", "riru", NULL
    };
    if (line == NULL) {
        return 0;
    }
    char lower[1024];
    size_t len = strlen(line);
    if (len >= sizeof(lower)) {
        len = sizeof(lower) - 1U;
    }
    for (size_t i = 0; i < len; i++) {
        char c = line[i];
        lower[i] = (char) ((c >= 'A' && c <= 'Z') ? (c + 32) : c);
    }
    lower[len] = '\0';
    for (int i = 0; keywords[i] != NULL; i++) {
        if (strstr(lower, keywords[i]) != NULL) {
            return 1;
        }
    }
    return 0;
}

static FILE *maps_fopen_hook(const char *path, const char *mode) {
    if (path == NULL || strstr(path, "/maps") == NULL) {
        return g_real_fopen(path, mode);
    }
    FILE *source = g_real_fopen(path, mode);
    if (source == NULL) {
        return NULL;
    }
    FILE *sink = tmpfile();
    if (sink == NULL) {
        return source;
    }
    char line[1024];
    while (fgets(line, sizeof(line), source) != NULL) {
        if (!maps_line_should_hide(line)) {
            fputs(line, sink);
        }
    }
    fclose(source);
    rewind(sink);
    return sink;
}

static int install_maps_fopen_filter(void) {
    if (g_maps_filter_installed) {
        return 1;
    }
    void *target = dlsym(RTLD_DEFAULT, "fopen");
    if (target == NULL) {
        LOGE("maps filter: fopen not found");
        return 0;
    }

    long page = sysconf(_SC_PAGESIZE);
    g_fopen_trampoline = mmap(NULL, (size_t) page, PROT_READ | PROT_WRITE | PROT_EXEC,
            MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (g_fopen_trampoline == MAP_FAILED) {
        LOGE("maps filter: trampoline mmap failed");
        return 0;
    }

    uint32_t *tramp = (uint32_t *) g_fopen_trampoline;
    memcpy(g_saved_fopen, target, sizeof(g_saved_fopen));
    tramp[0] = g_saved_fopen[0];
    tramp[1] = g_saved_fopen[1];
    tramp[2] = g_saved_fopen[2];
    tramp[3] = g_saved_fopen[3];
    intptr_t back_offset = ((intptr_t) target + 16 - (intptr_t) &tramp[4]) / 4;
    tramp[4] = (uint32_t) (0x14000000U | (back_offset & 0x03FFFFFFU));
    __builtin___clear_cache((char *) g_fopen_trampoline, (char *) g_fopen_trampoline + 20);

    g_real_fopen = (fopen_fn) g_fopen_trampoline;
    if (patch_page_rw(target) != 0) {
        LOGE("maps filter: mprotect failed: %s", strerror(errno));
        return 0;
    }

    intptr_t hook_offset = ((intptr_t) maps_fopen_hook - (intptr_t) target) / 4;
    uint32_t *slot = (uint32_t *) target;
    slot[0] = (uint32_t) (0x14000000U | (hook_offset & 0x03FFFFFFU));
    slot[1] = 0xD503201FU;
    slot[2] = 0xD503201FU;
    slot[3] = 0xD503201FU;
    __builtin___clear_cache((char *) target, (char *) target + 16);

    g_maps_filter_installed = 1;
    LOGI("native /proc/maps fopen filter installed at %p", target);
    return 1;
}

JNIEXPORT jboolean JNICALL
Java_com_afusekt_lsp_libxposed_LibVidHubNative_nativeInstallMapsFilter(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    return install_maps_fopen_filter() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_afusekt_lsp_hook_VidHubNativeGuard_nativeInstallMapsFilter(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    return install_maps_fopen_filter() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_afusekt_lsp_libxposed_LibVidHubNative_nativeStartNesecExitGuard(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    if (start_exit_guard_thread(0)) {
        LOGI("exit guard thread started (libnesec)");
    } else {
        LOGE("exit guard thread create failed (libnesec)");
    }
}

JNIEXPORT void JNICALL
Java_com_afusekt_lsp_libxposed_LibAfusektShield_nativeStartExitGuard(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    if (start_exit_guard_thread(1)) {
        LOGI("exit guard thread started (libnative-lib)");
    } else {
        LOGE("exit guard thread create failed (libnative-lib)");
    }
}

JNIEXPORT jboolean JNICALL
Java_com_afusekt_lsp_libxposed_LibHillsNative_nativeInstallMapsFilter(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    return install_maps_fopen_filter() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_afusekt_lsp_libxposed_LibHillsNative_nativeApplyLibAppPatches(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    apply_hills_libapp_patches();
    return g_hills_patched ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_afusekt_lsp_libxposed_LibHillsNative_nativeWaitAndApplyLibAppPatches(JNIEnv *env, jclass clazz,
                                                                              jint timeout_ms) {
    (void) env;
    (void) clazz;
    if (g_hills_patched) {
        return JNI_TRUE;
    }
    int waited = 0;
    const int step = 50;
    while (timeout_ms <= 0 || waited <= timeout_ms) {
        if (apply_hills_libapp_patches() > 0 && g_hills_patched) {
            return JNI_TRUE;
        }
        if (!libapp_is_mapped()) {
            if (timeout_ms <= 0) {
                return JNI_FALSE;
            }
            usleep((useconds_t) step * 1000U);
            waited += step;
            continue;
        }
        if (timeout_ms <= 0) {
            return JNI_FALSE;
        }
        usleep((useconds_t) step * 1000U);
        waited += step;
    }
    return g_hills_patched ? JNI_TRUE : JNI_FALSE;
}
