#include <jni.h>
#include <android/log.h>
#include <inttypes.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <netdb.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <strings.h>
#include <unistd.h>
#include <time.h>

#define LOG_TAG "ZoeVIP:NativePatch"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static const char *kLibName = "libapp.so";

static int line_has_libflutter(const char *line) {
    return strstr(line, "libflutter.so") != NULL;
}

static int line_has_libapp(const char *line) {
    const char *hit = strstr(line, kLibName);
    if (hit == NULL) {
        return 0;
    }
    char next = hit[strlen(kLibName)];
    return next == '\0' || next == '\n' || next == ' ';
}

static int line_is_executable(const char *line) {
    /* Flutter libapp.so places Dart AOT code in rwxp as well as r-xp segments. */
    return strstr(line, " r-xp ") != NULL
            || strstr(line, " r-x ") != NULL
            || strstr(line, " rwxp ") != NULL;
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

static int find_runtime_addr_in_maps_ex(uint32_t container_off, uint32_t file_offset,
        int (*line_match)(const char *), int executable_only, uintptr_t *addr_out) {
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
        if (executable_only && !line_is_executable(line)) {
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

static int find_runtime_addr_in_maps(uint32_t container_off, uint32_t file_offset,
                                     int (*line_match)(const char *),
                                     uintptr_t *addr_out) {
    return find_runtime_addr_in_maps_ex(container_off, file_offset, line_match, 1, addr_out);
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

static const PatchEntry kCapy115Patches[] = {
        // Settings card left unpatched: dual settings/banner patches caused image flicker.
        {0x659358U, 0x372000a0U, 0xd503201fU, "PaywallGuard.ensureEntitled"},
        {0x65935cU, 0x9100c2c0U, 0x910082c0U, "PaywallGuard.ensureEntitled true"},
        {0x659528U, 0x372000a0U, 0xd503201fU, "PaywallGuard.ensureEntitledAsync"},
        {0x659878U, 0x372000a0U, 0xd503201fU, "PaywallGuard.ensureProFeature"},
        /* Force settings entitlement probe to Pro (skip paywall branch). */
        {0x82d294U, 0x37200107U, 0x1400000aU, "SettingsCard.entitled"},
        /* Precise entitlement field gates (ldur+decompress+tbnz→false/store-false). */
        {0x6E2D60U, 0x37200A20U, 0xD503201FU, "entGate nop"},
        {0x6E2D64U, 0x9100C2C0U, 0x910082C0U, "entGate true"},
        {0x7DAE2CU, 0x372000A2U, 0xD503201FU, "entGate nop"},
        {0x7DAE30U, 0x9100C2C0U, 0x910082C0U, "entGate true"},
        {0x7DBC38U, 0x372000A1U, 0xD503201FU, "entGate nop"},
        {0x7DBC3CU, 0x9100C2C0U, 0x910082C0U, "entGate true"},
        {0x92C5F4U, 0x372000A3U, 0xD503201FU, "entGate nop"},
        {0x92C5F8U, 0x9100C2C0U, 0x910082C0U, "entGate true"},
        {0x9955D8U, 0x372001A0U, 0xD503201FU, "entGate nop"},
        {0x9955DCU, 0x9100C2C0U, 0x910082C0U, "entGate true"},
        {0xABE08CU, 0x372000A1U, 0xD503201FU, "entGate nop"},
        {0xABE090U, 0x9100C2C0U, 0x910082C0U, "entGate true"},
        {0xAC9CC0U, 0x372000E0U, 0xD503201FU, "entGate nop"},
        {0xAC9CC4U, 0x9100C2C0U, 0x910082C0U, "entGate true"},
        {0xACE4CCU, 0x37200080U, 0xD503201FU, "entGate nop"},
        {0xACE4D0U, 0x9100C2C0U, 0x910082C0U, "entGate true"},
        {0xAE0824U, 0x372000A2U, 0xD503201FU, "entGate nop"},
        {0xAE0828U, 0x9100C2C0U, 0x910082C0U, "entGate true"},
};

static int patch_u32(uintptr_t addr, uint32_t expected, uint32_t patched, const char *label);

/* Same early-return-false epilogue as PaywallGuard — force true for Pro/entitlement gates. */
static int apply_capy115_bool_gate_scan(void) {
    FILE *maps = open_proc_maps();
    if (maps == NULL) {
        return 0;
    }
    int applied = 0;
    char line[1024];
    while (fgets(line, sizeof(line), maps) != NULL) {
        if (!line_has_libapp(line) || !line_is_executable(line)) {
            continue;
        }
        uintptr_t start = 0;
        uintptr_t end = 0;
        if (sscanf(line, "%" SCNxPTR "-%" SCNxPTR, &start, &end) != 2) {
            continue;
        }
        if (end < start + 20U) {
            continue;
        }
        for (uintptr_t addr = start; addr + 20U <= end; addr += 4U) {
            volatile uint32_t *slot = (volatile uint32_t *) addr;
            uint32_t w0 = slot[0];
            uint32_t w1 = slot[1];
            uint32_t w2 = slot[2];
            uint32_t w3 = slot[3];
            uint32_t w4 = slot[4];
            if ((w0 & 0xFF80001FU) != 0x37000000U || ((w0 >> 19) & 0x1FU) != 4U) {
                continue;
            }
            if ((w1 & 0xFFC00000U) != 0x91000000U || (w1 & 0x1FU) != 0U
                    || ((w1 >> 10) & 0xFFFU) != 0x30U) {
                continue;
            }
            if (w2 != 0xAA1D03EFU || w3 != 0xA8C179FDU || w4 != 0xD65F03C0U) {
                continue;
            }
            uint32_t true_insn = (w1 & ~0x3FFC00U) | (0x20U << 10);
            if (patch_u32(addr, w0, 0xD503201FU, "boolGate nop") == 0) {
                applied++;
            }
            if (patch_u32(addr + 4U, w1, true_insn, "boolGate true") == 0) {
                applied++;
            }
        }
    }
    fclose(maps);
    if (applied > 0) {
        LOGI("CapyPlayer 1.1.5: boolGate scan applied %d insn(s)", applied);
    }
    return applied;
}

static const char kCapy115BuildId[] = "7af013633a213bf5b934d1427a205d8a";

/* CapyPlayer 1.1.6
 * WebDAV: allow cross-origin redirect WITHOUT forcing _sameOrigin true.
 * Forcing sameOrigin forwards Authorization onto CDN and can loop huge PUTs.
 *
 * Divert path (webdav_client_plus-style):
 *   bl _canRedirectTo-wrapper @ 0x82eb48
 *   tbz w0,#4 → non-follow   @ 0x82eb4c   ← must NOP (fall through = follow)
 *   follow via 0x10594e4
 * Wrapper @ 0x82f1ec: tbnz → false; NOP so redirect statuses always allow.
 * Do NOT patch Location isEmpty (0x82eab8): that early-exit is correct. */
static const PatchEntry kCapy116Patches[] = {
        {0x679648U, 0x372000a0U, 0xd503201fU, "PaywallGuard.ensureEntitled"},
        {0x67964cU, 0x9100c2c0U, 0x910082c0U, "PaywallGuard.ensureEntitled true"},
        {0x679818U, 0x372000a0U, 0xd503201fU, "PaywallGuard.ensureEntitledAsync"},
        {0x679b68U, 0x372000a0U, 0xd503201fU, "PaywallGuard.ensureProFeature"},
        /* Redirect status → always allow (_canRedirectTo wrapper returns true). */
        {0x82f1ecU, 0x372000a0U, 0xd503201fU, "WebDAV _canRedirectTo allow cross-origin"},
        /* Fall through to follow path (was tbz-to-non-follow; old b#non-follow was inverted). */
        {0x82eb4cU, 0x36200060U, 0xd503201fU, "WebDAV req follow redirect"},
};
static const char kCapy116BuildId[] = "2d9bfe2e51bde16bc58c214ebb13ce5c";
static const uint32_t kCapy116HostOff = 0xa496cU;
static const uint32_t kCapy115HostOff = 0xa17adU;

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
        if (!line_has_libapp(line) || !line_is_executable(line)) {
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
    /* Match any permission: Paywall sites are RX, host strings are R-- rodata.
     * executable_only=1 misses rodata and the old base+file_offset fallback
     * pointed at the wrong page, so host divert never rewrote the real URI. */
    if (find_runtime_addr_in_maps_ex(0U, file_offset, line_has_libapp, 0, addr_out) == 0) {
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

static int patch_u32(uintptr_t addr, uint32_t expected, uint32_t patched, const char *label);

static int is_entitlement_branch(uint32_t insn) {
    if ((insn & 0x1FU) != 0U) {
        return 0;
    }
    if (((insn >> 19) & 0x1FU) != 4U) {
        return 0;
    }
    uint32_t hi = insn & 0x7F800000U;
    return hi == 0x36000000U || hi == 0x37000000U;
}

static int is_bl_to(uint32_t insn, uintptr_t from, uintptr_t target) {
    if ((insn & 0xFC000000U) != 0x94000000U) {
        return 0;
    }
    int32_t imm = (int32_t) (insn & 0x03FFFFFFU);
    if (imm & 0x02000000) {
        imm |= (int32_t) ~0x03FFFFFFU;
    }
    uintptr_t dest = from + (uintptr_t) ((int64_t) imm << 2);
    return dest == target;
}

static int apply_capy115_entitlement_scan(void) {
    char build_id[64];
    char lib_path[512];
    uintptr_t base = 0;
    int id_ok = -1;

    if (find_libapp_path(lib_path, sizeof(lib_path)) == 0) {
        id_ok = read_build_id_from_path(lib_path, build_id, sizeof(build_id));
    }
    if (id_ok != 0 && find_libapp_base(&base) == 0) {
        id_ok = read_build_id_from_base(base, build_id, sizeof(build_id));
    }
    if (id_ok != 0 || strcmp(build_id, kCapy115BuildId) != 0) {
        return 0;
    }

    uintptr_t check_fn = 0;
    if (find_libapp_runtime_addr(0x66a0e0U, &check_fn) != 0) {
        LOGE("CapyPlayer 1.1.5: entitlement helper not mapped");
        return 0;
    }

    FILE *maps = open_proc_maps();
    if (maps == NULL) {
        return 0;
    }

    int applied = 0;
    char line[1024];
    while (fgets(line, sizeof(line), maps) != NULL) {
        if (!line_has_libapp(line) || !line_is_executable(line)) {
            continue;
        }
        uintptr_t start = 0;
        uintptr_t end = 0;
        if (sscanf(line, "%" SCNxPTR "-%" SCNxPTR, &start, &end) != 2) {
            continue;
        }
        if (end <= start + 8U) {
            continue;
        }
        for (uintptr_t addr = start; addr + 8U <= end; addr += 4U) {
            volatile uint32_t *slot = (volatile uint32_t *) addr;
            uint32_t insn0 = slot[0];
            uint32_t insn1 = slot[1];
            if (!is_bl_to(insn0, addr, check_fn) || !is_entitlement_branch(insn1)) {
                continue;
            }
            if (patch_u32(addr + 4U, insn1, 0x1f2003d5U, "CapyPlayer entitlement branch") == 0) {
                applied++;
            }
        }
    }
    fclose(maps);
    if (applied > 0) {
        LOGI("CapyPlayer 1.1.5: entitlement scan applied %d branch(es)", applied);
    }
    return applied;
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

static int g_banner_scan_started = 0;
static pthread_t g_banner_scan_thread;

static void *banner_xref_thread(void *unused) {
    (void) unused;
    sleep(4);
    const char needle[] = "CAPY PRO";
    FILE *maps = open_proc_maps();
    if (maps == NULL) {
        return NULL;
    }
    uintptr_t heap_str = 0;
    char line[1024];
    while (heap_str == 0 && fgets(line, sizeof(line), maps) != NULL) {
        if (strstr(line, " rw-p ") == NULL || strstr(line, ".so") != NULL
                || strstr(line, "stack_and_tls") != NULL) {
            continue;
        }
        uintptr_t start = 0;
        uintptr_t end = 0;
        if (sscanf(line, "%" SCNxPTR "-%" SCNxPTR, &start, &end) != 2 || end <= start + 8U) {
            continue;
        }
        size_t span = (size_t) (end - start);
        if (span > (32U << 20)) {
            continue;
        }
        const char *found = memmem((const void *) start, span, needle, sizeof(needle) - 1U);
        if (found != NULL) {
            heap_str = (uintptr_t) found;
        }
    }
    fclose(maps);
    if (heap_str < 16U) {
        LOGI("banner heap string missing");
        return NULL;
    }
    uintptr_t object = heap_str - 16U;
    uintptr_t heap_base = object & ~((uintptr_t) 0xFFFFFFFFU);
    uint32_t compressed = (uint32_t) (object - heap_base);
    LOGI("banner object %p compressed 0x%x", (void *) object, compressed);

    maps = open_proc_maps();
    if (maps == NULL) {
        return NULL;
    }
    int refs = 0;
    while (refs < 12 && fgets(line, sizeof(line), maps) != NULL) {
        if (strstr(line, " rw-p ") == NULL || strstr(line, "stack_and_tls") != NULL) {
            continue;
        }
        uintptr_t start = 0;
        uintptr_t end = 0;
        if (sscanf(line, "%" SCNxPTR "-%" SCNxPTR, &start, &end) != 2 || end <= start + 8U) {
            continue;
        }
        size_t span = (size_t) (end - start);
        if (span < (8U << 20) || span > (1024U << 20)) {
            continue;
        }
        LOGI("banner scan %p-%p", (void *) start, (void *) end);
        for (uintptr_t cursor = start; cursor + 8U <= end && refs < 12; cursor += 4U) {
            uint32_t word = *(volatile uint32_t *) cursor;
            if (word != compressed) {
                continue;
            }
            if (cursor >= object && cursor < object + 48U) {
                continue;
            }
            uint32_t next = *(volatile uint32_t *) (cursor + 4U);
            LOGI("banner cref %p next %08x", (void *) cursor, next);
            refs++;
        }
    }
    fclose(maps);
    LOGI("banner refs %d", refs);
    return NULL;
}

static void start_banner_xref_scan(void) {
    if (g_banner_scan_started) {
        return;
    }
    g_banner_scan_started = 1;
    if (pthread_create(&g_banner_scan_thread, NULL, banner_xref_thread, NULL) == 0) {
        pthread_detach(g_banner_scan_thread);
    }
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
    if (applied > 0 && strcmp(profile->build_id, kCapy115BuildId) == 0) {
        /* Banner heap scan disabled: multi-GB maps scan caused multi-second white screen. */
    }
    return applied;
}

static int g_capy115_scan_done = 0;
static int g_last_capy_applied = 0;

static int capy_patches_satisfied(int applied, size_t capy115_total, size_t capy_total) {
    size_t capy116_total = sizeof(kCapy116Patches) / sizeof(kCapy116Patches[0]);
    if (applied >= (int) capy116_total) {
        return 1;
    }
    if (applied >= (int) capy115_total) {
        return 1;
    }
    if (applied >= (int) capy_total) {
        return 1;
    }
    return 0;
}

static int g_capy115_host_diverted = 0;

/* Break subscription API DNS/TLS by rewriting the embedded API host in libapp.
 * Same length so Dart OneByteString / URI constants stay valid. */
static int rewrite_host_at(uintptr_t addr, const char *from, const char *to, size_t n) {
    char *p = (char *) addr;
    if (memcmp(p, to, n) == 0) {
        return 1;
    }
    if (memcmp(p, from, n) != 0) {
        return 0;
    }
    long page = sysconf(_SC_PAGESIZE);
    if (page <= 0) {
        page = 4096;
    }
    uintptr_t page_base = addr & ~(uintptr_t) (page - 1);
    /* rodata is usually R--; request RW only (not EXEC) so Xiaomi W^X accepts it. */
    if (mprotect((void *) page_base, (size_t) page * 2U, PROT_READ | PROT_WRITE) != 0) {
        if (mprotect((void *) page_base, (size_t) page * 2U,
                PROT_READ | PROT_WRITE | PROT_EXEC) != 0) {
            LOGE("CapyPlayer: host divert mprotect failed: %s", strerror(errno));
            return 0;
        }
    }
    memcpy(p, to, n);
    __builtin___clear_cache(p, p + n);
    return 1;
}

/* Rewrite ASCII host; also UTF-16LE copies that Dart/ICU sometimes keep on the heap. */
static int rewrite_host_utf16_at(uintptr_t addr, const char *from, const char *to, size_t n) {
    uint16_t *p = (uint16_t *) addr;
    for (size_t i = 0; i < n; i++) {
        if (p[i] != (uint16_t) (unsigned char) from[i]) {
            return 0;
        }
    }
    long page = sysconf(_SC_PAGESIZE);
    if (page <= 0) {
        page = 4096;
    }
    uintptr_t page_base = addr & ~(uintptr_t) (page - 1);
    size_t bytes = n * 2U;
    if (mprotect((void *) page_base, (size_t) page * 2U, PROT_READ | PROT_WRITE) != 0) {
        if (mprotect((void *) page_base, (size_t) page * 2U,
                PROT_READ | PROT_WRITE | PROT_EXEC) != 0) {
            return 0;
        }
    }
    for (size_t i = 0; i < n; i++) {
        p[i] = (uint16_t) (unsigned char) to[i];
    }
    __builtin___clear_cache((char *) p, (char *) p + bytes);
    return 1;
}

/* Avoid SIGSEGV on sparse/guard pages (backup JSON alloc creates many holes). */
static int page_is_resident(uintptr_t addr, size_t page) {
    unsigned char vec = 0;
    uintptr_t base = addr & ~(uintptr_t) (page - 1);
    if (mincore((void *) base, page, &vec) != 0) {
        return 0;
    }
    return (vec & 1U) != 0;
}

static int scrub_subscription_host_region(uintptr_t start, uintptr_t end,
        const char *from, const char *to, size_t n) {
    if (end <= start + n) {
        return 0;
    }
    long page_l = sysconf(_SC_PAGESIZE);
    size_t page = page_l > 0 ? (size_t) page_l : 4096U;
    int rewritten = 0;
    uintptr_t p = start & ~(uintptr_t) (page - 1);
    for (; p < end; p += page) {
        if (!page_is_resident(p, page)) {
            continue;
        }
        uintptr_t seg_start = p < start ? start : p;
        uintptr_t seg_end = p + page;
        if (seg_end > end) {
            seg_end = end;
        }
        if (n > 1U && p >= page && page_is_resident(p - page, page)) {
            uintptr_t overlap = seg_start > (n - 1U) ? (seg_start - (n - 1U)) : start;
            if (overlap >= start) {
                seg_start = overlap;
            }
        }
        if (seg_end <= seg_start + n) {
            continue;
        }
        size_t span = (size_t) (seg_end - seg_start);
        for (size_t off = 0; off + n <= span; ) {
            char *hit = memmem((void *) (seg_start + off), span - off, from, n);
            if (hit == NULL) {
                break;
            }
            if (rewrite_host_at((uintptr_t) hit, from, to, n)) {
                rewritten++;
            }
            off = (size_t) ((uintptr_t) hit - seg_start) + n;
        }
    }
    /* UTF-16LE — only tiny regions, page-safe. */
    size_t span_all = (size_t) (end - start);
    if (span_all <= (4U << 20) && span_all > n * 2U) {
        uint8_t from16[128];
        if (n * 2U <= sizeof(from16)) {
            for (size_t i = 0; i < n; i++) {
                from16[i * 2U] = (uint8_t) from[i];
                from16[i * 2U + 1U] = 0;
            }
            size_t n16 = n * 2U;
            for (p = start & ~(uintptr_t) (page - 1); p < end; p += page) {
                if (!page_is_resident(p, page)) {
                    continue;
                }
                uintptr_t seg_start = p < start ? start : p;
                uintptr_t seg_end = p + page;
                if (seg_end > end) {
                    seg_end = end;
                }
                if (seg_end <= seg_start + n16) {
                    continue;
                }
                size_t span = (size_t) (seg_end - seg_start);
                for (size_t off = 0; off + n16 <= span; ) {
                    char *hit = memmem((void *) (seg_start + off), span - off, from16, n16);
                    if (hit == NULL) {
                        break;
                    }
                    if (rewrite_host_utf16_at((uintptr_t) hit, from, to, n)) {
                        rewritten++;
                    }
                    off = (size_t) ((uintptr_t) hit - seg_start) + n16;
                }
            }
        }
    }
    return rewritten;
}

/* libapp-only host rewrite. NEVER scan anonymous Dart heaps — that ANR/SEGVs
 * during WebDAV backup (RssHwm~2GB). Heap Uri copies are blocked via
 * libflutter getaddrinfo GOT filter instead. */
static int scrub_subscription_host_libapp_only(void) {
    static const char kFrom[] = "api-capyplayer.feifeiduck.cn";
    static const char kTo[] = "blocked.subscription.invalid";
    const size_t n = sizeof(kFrom) - 1U;
    if (sizeof(kFrom) != sizeof(kTo)) {
        return 0;
    }
    FILE *maps = open_proc_maps();
    if (maps == NULL) {
        return 0;
    }
    int rewritten = 0;
    char line[1024];
    while (fgets(line, sizeof(line), maps) != NULL) {
        char perms[8] = {0};
        uintptr_t start = 0;
        uintptr_t end = 0;
        if (sscanf(line, "%" SCNxPTR "-%" SCNxPTR " %7s", &start, &end, perms) != 3) {
            continue;
        }
        if (!line_has_libapp(line) || strchr(perms, 'r') == NULL || end <= start + n) {
            continue;
        }
        size_t span = (size_t) (end - start);
        if (span > (64U << 20)) {
            continue;
        }
        rewritten += scrub_subscription_host_region(start, end, kFrom, kTo, n);
    }
    fclose(maps);
    if (rewritten > 0) {
        LOGI("CapyPlayer: scrubbed host in libapp (%d site(s))", rewritten);
    }
    return rewritten;
}

static int divert_capy_subscription_host(uint32_t host_off) {
    static const char kFrom[] = "api-capyplayer.feifeiduck.cn";
    static const char kTo[] = "blocked.subscription.invalid";
    if (sizeof(kFrom) != sizeof(kTo)) {
        return 0;
    }
    const size_t n = sizeof(kFrom) - 1U;
    uintptr_t addr = 0;
    if (g_capy115_host_diverted) {
        return 1;
    }
    int ok = 0;
    if (find_libapp_runtime_addr(host_off, &addr) == 0) {
        if (rewrite_host_at(addr, kFrom, kTo, n)) {
            ok = 1;
            LOGI("CapyPlayer: diverted subscription API host at %p", (void *) addr);
        } else {
            LOGE("CapyPlayer: subscription host mismatch at %p", (void *) addr);
        }
    } else {
        LOGI("CapyPlayer: subscription host string not mapped yet (@0x%x)", host_off);
    }
    (void) scrub_subscription_host_libapp_only();
    if (ok) {
        g_capy115_host_diverted = 1;
    }
    return ok;
}

static int apply_capy_patches(void) {
    static const PatchProfile profiles[] = {
            {
                    kCapy116BuildId,
                    kCapy116Patches,
                    sizeof(kCapy116Patches) / sizeof(kCapy116Patches[0]),
                    "CapyPlayer 1.1.6",
            },
            {
                    kCapy115BuildId,
                    kCapy115Patches,
                    sizeof(kCapy115Patches) / sizeof(kCapy115Patches[0]),
                    "CapyPlayer 1.1.5",
            },
            {
                    kCapyBuildId,
                    kCapyPatches,
                    sizeof(kCapyPatches) / sizeof(kCapyPatches[0]),
                    "CapyPlayer",
            },
    };
    int best = 0;
    for (size_t i = 0; i < sizeof(profiles) / sizeof(profiles[0]); i++) {
        int applied = apply_profile(&profiles[i]);
        if (applied > best) {
            best = applied;
        }
        if ((size_t) applied >= profiles[i].count) {
            if (strcmp(profiles[i].build_id, kCapy116BuildId) == 0) {
                divert_capy_subscription_host(kCapy116HostOff);
            } else if (strcmp(profiles[i].build_id, kCapy115BuildId) == 0) {
                divert_capy_subscription_host(kCapy115HostOff);
            }
            (void) apply_capy115_bool_gate_scan;
            (void) g_capy115_scan_done;
            break;
        }
    }
    /* Patches may already be applied (applied==0 on retry) — still divert host. */
    if (!g_capy115_host_diverted) {
        divert_capy_subscription_host(kCapy116HostOff);
        if (!g_capy115_host_diverted) {
            divert_capy_subscription_host(kCapy115HostOff);
        }
    }
    g_last_capy_applied = best;
    return best;
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

static int process_is_hills(void) {
    char cmdline[256];
    memset(cmdline, 0, sizeof(cmdline));
    int fd = open("/proc/self/cmdline", O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        return 0;
    }
    ssize_t n = read(fd, cmdline, sizeof(cmdline) - 1U);
    close(fd);
    if (n <= 0) {
        return 0;
    }
    return strstr(cmdline, "mountains.hills") != NULL;
}

__attribute__((constructor))
static void hills_patch_auto_start(void) {
    if (!process_is_hills()) {
        return;
    }
    if (pthread_create(&g_hills_patch_thread, NULL, hills_patch_poll_thread, NULL) == 0) {
        pthread_detach(g_hills_patch_thread);
    }
}

static int patch_page_rw(void *addr);

typedef struct {
    const char *from;
    const char *to;
} SubscriptionTextPatch;

static const SubscriptionTextPatch kSubscriptionTextPatches[] = {
        {"\"hasSubscription\":false", "\"hasSubscription\":true"},
        {"\"has_subscription\":false", "\"has_subscription\":true"},
        {"\"has_active_subscription\":false", "\"has_active_subscription\":true"},
        {"\"isActive\":false", "\"isActive\":true"},
        {"\"isPro\":false", "\"isPro\":true"},
        {"\"lifetime\":false", "\"lifetime\":true"},
        {"\"lifetimeMember\":false", "\"lifetimeMember\":true"},
        {"\"tier\":\"free\"", "\"tier\":\"lifetime\""},
        {"\"status\":\"inactive\"", "\"status\":\"active\""},
        {"\"status\":\"expired\"", "\"status\":\"active\""},
        {"\"plan\":\"free\"", "\"plan\":\"lifetime\""},
        {"\"planType\":\"free\"", "\"planType\":\"lifetime\""},
};

static int is_socket_fd(int fd) {
    int type = 0;
    socklen_t type_len = (socklen_t) sizeof(type);
    return getsockopt(fd, SOL_SOCKET, SO_TYPE, &type, &type_len) == 0;
}

static int looks_like_subscription_payload(const char *buf, size_t len) {
    if (buf == NULL || len < 16U || len > 65536U) {
        return 0;
    }
    if (memmem(buf, len, "{", 1) == NULL) {
        return 0;
    }
    if (memmem(buf, len, "hasSubscription", 15) != NULL) {
        return 1;
    }
    if (memmem(buf, len, "has_subscription", 16) != NULL) {
        return 1;
    }
    if (memmem(buf, len, "lifetimeMember", 14) != NULL) {
        return 1;
    }
    if (memmem(buf, len, "subscriptionActive", 18) != NULL) {
        return 1;
    }
    if (memmem(buf, len, "subscriptions/status", 20) != NULL) {
        return 1;
    }
    if (memmem(buf, len, "subscriptions/verify", 20) != NULL) {
        return 1;
    }
    if (memmem(buf, len, "subscriptions/sync", 18) != NULL) {
        return 1;
    }
    if (memmem(buf, len, "SubscriptionStatus", 18) != NULL) {
        return 1;
    }
    if (memmem(buf, len, "google_product_id", 17) != NULL) {
        return 1;
    }
    /* Local persistence blobs after login sync often only carry tier/status. */
    if (memmem(buf, len, "\"tier\":\"free\"", 13) != NULL) {
        return 1;
    }
    if (memmem(buf, len, "lastVerifiedAt", 14) != NULL
            && memmem(buf, len, "productId", 9) != NULL) {
        return 1;
    }
    return 0;
}

static size_t rewrite_rejected_receipt(char *buf, size_t len) {
    static const char kNeedle[] = "\"rejectedReceipt\":true";
    static const char kRep[] = "\"rejectedReceipt\":null";
    const size_t nlen = sizeof(kNeedle) - 1U;
    if (len < nlen || strlen(kRep) != nlen) {
        return len;
    }
    char *p = (char *) memmem(buf, len, kNeedle, nlen);
    while (p != NULL) {
        memcpy(p, kRep, nlen);
        size_t left = (size_t) ((buf + len) - (p + nlen));
        p = left >= nlen ? (char *) memmem(p + nlen, left, kNeedle, nlen) : NULL;
    }
    return len;
}

static size_t patch_subscription_buffer(char *buf, size_t len) {
    len = rewrite_rejected_receipt(buf, len);
    if (!looks_like_subscription_payload(buf, len)) {
        return len;
    }
    for (size_t i = 0; i < sizeof(kSubscriptionTextPatches) / sizeof(kSubscriptionTextPatches[0]); i++) {
        const char *from = kSubscriptionTextPatches[i].from;
        const char *to = kSubscriptionTextPatches[i].to;
        size_t from_len = strlen(from);
        size_t to_len = strlen(to);
        char *cursor = buf;
        char *end = buf + len;
        while (cursor + from_len <= end) {
            char *hit = memmem(cursor, (size_t) (end - cursor), from, from_len);
            if (hit == NULL) {
                break;
            }
            memmove(hit + to_len, hit + from_len, (size_t) (end - (hit + from_len)));
            memcpy(hit, to, to_len);
            if (to_len < from_len) {
                end -= (from_len - to_len);
            } else if (to_len > from_len) {
                end += (to_len - from_len);
            }
            cursor = hit + to_len;
        }
        len = (size_t) (end - buf);
    }
    return len;
}

typedef ssize_t (*recv_fn)(int, void *, size_t, int);
typedef int (*ssl_read_fn)(void *, void *, int);

static recv_fn g_real_recv = NULL;
static ssl_read_fn g_real_ssl_read = NULL;
static uint32_t g_saved_recv[4];
static uint32_t g_saved_ssl_read[4];
static void *g_recv_trampoline = NULL;
static void *g_ssl_read_trampoline = NULL;
static int g_recv_hook_installed = 0;
static int g_ssl_read_hook_installed = 0;

static ssize_t recv_subscription_hook(int fd, void *buf, size_t len, int flags) {
    ssize_t n = g_real_recv(fd, buf, len, flags);
    if (n > 0 && buf != NULL) {
        size_t patched = patch_subscription_buffer((char *) buf, (size_t) n);
        return (ssize_t) patched;
    }
    return n;
}

static int ssl_read_subscription_hook(void *ssl, void *buf, int num);

void capy_patch_subscription_inplace(char *buf, size_t len);

static int ssl_read_subscription_hook(void *ssl, void *buf, int num) {
    int n = g_real_ssl_read(ssl, buf, num);
    if (n > 0 && n <= 65536 && buf != NULL) {
        /* Same-length inplace only — never change returned byte count. */
        capy_patch_subscription_inplace((char *) buf, (size_t) n);
    }
    return n;
}

static void *map_veneer_near(uintptr_t site);

static int install_arm64_trampoline_hook(void *target, void *hook, void **trampoline_out,
        uint32_t *saved_out, void **real_fn_out) {
    long page = sysconf(_SC_PAGESIZE);
    void *trampoline = mmap(NULL, (size_t) page, PROT_READ | PROT_WRITE | PROT_EXEC,
            MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (trampoline == MAP_FAILED) {
        return 0;
    }
    /* Absolute veneer near target — relative B to hook can be >±128MB and SIGSEGV. */
    void *veneer = map_veneer_near((uintptr_t) target);
    if (veneer == NULL) {
        munmap(trampoline, (size_t) page);
        return 0;
    }
    uint32_t *tramp = (uint32_t *) trampoline;
    memcpy(saved_out, target, 16);
    tramp[0] = saved_out[0];
    tramp[1] = saved_out[1];
    tramp[2] = saved_out[2];
    tramp[3] = saved_out[3];
    intptr_t back_offset = ((intptr_t) target + 16 - (intptr_t) &tramp[4]) / 4;
    tramp[4] = (uint32_t) (0x14000000U | (back_offset & 0x03FFFFFFU));
    __builtin___clear_cache((char *) trampoline, (char *) trampoline + 20);
    *trampoline_out = trampoline;
    *real_fn_out = trampoline;

    uint32_t *vcode = (uint32_t *) veneer;
    vcode[0] = 0x58000050U; /* ldr x16, #8 */
    vcode[1] = 0xD61F0200U; /* br x16 */
    *(uint64_t *) (vcode + 2) = (uint64_t) (uintptr_t) hook;
    __builtin___clear_cache((char *) veneer, (char *) veneer + 16);

    if (patch_page_rw(target) != 0) {
        return 0;
    }
    intptr_t to_veneer = ((intptr_t) veneer - (intptr_t) target) / 4;
    uint32_t *slot = (uint32_t *) target;
    slot[0] = (uint32_t) (0x14000000U | (to_veneer & 0x03FFFFFFU));
    slot[1] = 0xD503201FU;
    slot[2] = 0xD503201FU;
    slot[3] = 0xD503201FU;
    __builtin___clear_cache((char *) target, (char *) target + 16);
    return 1;
}

static int install_recv_hook_once(void) {
    if (g_recv_hook_installed) {
        return 1;
    }
    void *target = dlsym(RTLD_DEFAULT, "recv");
    if (target == NULL) {
        LOGE("CapyPlayer: recv not found");
        return 0;
    }
    if (!install_arm64_trampoline_hook(target, (void *) recv_subscription_hook, &g_recv_trampoline,
            g_saved_recv, (void **) &g_real_recv)) {
        LOGE("CapyPlayer: recv hook install failed");
        return 0;
    }
    g_recv_hook_installed = 1;
    LOGI("CapyPlayer: recv hook installed at %p", target);
    return 1;
}

static int line_has_shared_object(const char *line) {
    const char *dot = strrchr(line, '.');
    if (dot == NULL) {
        return 0;
    }
    return strcmp(dot, ".so") == 0 || strstr(dot, ".so ") != NULL;
}

static void *resolve_ssl_read_from_path(const char *path) {
    if (path == NULL || path[0] == '\0') {
        return NULL;
    }
    void *handle = dlopen(path, RTLD_NOW | RTLD_NOLOAD);
    if (handle == NULL) {
        handle = dlopen(path, RTLD_NOW);
    }
    if (handle == NULL) {
        return NULL;
    }
    void *target = dlsym(handle, "SSL_read");
    if (target != NULL) {
        LOGI("CapyPlayer: SSL_read resolved in %s", path);
    }
    return target;
}

static void *resolve_ssl_read(void) {
    static const char *libs[] = {
            "libflutter.so",
            "libapp.so",
            "libssl.so",
            "libcrypto.so",
            "libssl.so.3",
            "libcrypto.so.3",
            NULL,
    };
    for (size_t i = 0; libs[i] != NULL; i++) {
        void *target = resolve_ssl_read_from_path(libs[i]);
        if (target != NULL) {
            return target;
        }
    }

    FILE *maps = open_proc_maps();
    if (maps != NULL) {
        char line[1024];
        while (fgets(line, sizeof(line), maps) != NULL) {
            if (!line_is_executable(line) || !line_has_shared_object(line)) {
                continue;
            }
            char *slash = strchr(line, '/');
            if (slash == NULL) {
                continue;
            }
            size_t len = strcspn(slash, "\n");
            if (len == 0 || len >= sizeof(line)) {
                continue;
            }
            char path[512];
            memcpy(path, slash, len);
            path[len] = '\0';
            if (strstr(path, "libzoevippatch.so") != NULL) {
                continue;
            }
            void *target = resolve_ssl_read_from_path(path);
            if (target != NULL) {
                fclose(maps);
                return target;
            }
        }
        fclose(maps);
    }

    return dlsym(RTLD_DEFAULT, "SSL_read");
}

static int install_ssl_read_hook_once(void) {
    if (g_ssl_read_hook_installed) {
        return 1;
    }
    void *target = resolve_ssl_read();
    if (target == NULL) {
        LOGI("CapyPlayer: SSL_read not resolved yet");
        return 0;
    }
    if (!install_arm64_trampoline_hook(target, (void *) ssl_read_subscription_hook,
            &g_ssl_read_trampoline, g_saved_ssl_read, (void **) &g_real_ssl_read)) {
        LOGE("CapyPlayer: SSL_read hook install failed at %p", target);
        return 0;
    }
    g_ssl_read_hook_installed = 1;
    LOGI("CapyPlayer: SSL_read hook installed at %p", target);
    return 1;
}

/* libflutter read loop: mov x0,x21; blr x8; cmp w0,#0. Return value is the
   byte count written at x1. Same-length edits only — the caller trusts that count. */
static const uint32_t kFlutterReadSite = 0x8cb8d4U;
static const uint32_t kBlrX8 = 0xD63F0100U;
static int g_flutter_read_hook_installed = 0;

/* Extra libflutter read@plt sites in the TLS/socket region (file offsets). */
static const uint32_t kFlutterReadPltSites[] = {
        0x8bada8U,
        0x8baf2cU,
        0x8c05ccU,
        0x8dc3b0U,
        0x8f3cf0U,
};
static const uint32_t kFlutterWritePltSite = 0x8c2300U;
static int g_flutter_io_hooks_installed = 0;
static void *g_libflutter_read_plt = NULL;
static void *g_libflutter_write_plt = NULL;

static const char *kFalseFlags[] = {
        "\"hasSubscription\":false",
        "\"has_subscription\":false",
        "\"has_active_subscription\":false",
        "\"subscriptionActive\":false",
        "\"subscription_active\":false",
        "\"isActive\":false",
        "\"isPro\":false",
        "\"lifetime\":false",
        "\"lifetimeMember\":false",
        "\"annualMember\":false",
        "\"nonRecurring\":false",
};

static const SubscriptionTextPatch kSameLengthPatches[] = {
        {"\"status\":\"inactive\"", "\"status\":\"active\"  "},
        {"\"status\":\"expired\"", "\"status\":\"active\" "},
        /* "life" is same length as "free"; sync treats non-free + active flags as entitled. */
        {"\"tier\":\"free\"", "\"tier\":\"life\""},
        {"\"plan\":\"free\"", "\"plan\":\"life\""},
};

__attribute__((noinline, used))
static int looks_like_webdav_or_binary(const char *buf, size_t len) {
    if (buf == NULL || len < 4U) {
        return 0;
    }
    /* ZIP / backup archives — never rewrite. */
    if (buf[0] == 'P' && buf[1] == 'K') {
        return 1;
    }
    if (memmem(buf, len, "webdav", 6) != NULL
            || memmem(buf, len, "WebDAV", 6) != NULL
            || memmem(buf, len, "123pan", 6) != NULL
            || memmem(buf, len, "PROPFIND", 8) != NULL
            || memmem(buf, len, "<?xml", 5) != NULL
            || memmem(buf, len, "DAV:", 4) != NULL) {
        return 1;
    }
    return 0;
}

void capy_patch_subscription_inplace(char *buf, size_t len) {
    if (buf == NULL || len < 8U || len > 65536U) {
        return;
    }
    if (looks_like_webdav_or_binary(buf, len)) {
        return;
    }
    rewrite_rejected_receipt(buf, len);
    if (!looks_like_subscription_payload(buf, len)) {
        return;
    }
    int changed = 0;
    for (size_t i = 0; i < sizeof(kFalseFlags) / sizeof(kFalseFlags[0]); i++) {
        const char *key = kFalseFlags[i];
        size_t key_len = strlen(key);
        if (key_len < 5U || len < key_len) {
            continue;
        }
        char *cursor = buf;
        char *end = buf + len;
        while (cursor + key_len <= end) {
            char *hit = memmem(cursor, (size_t) (end - cursor), key, key_len);
            if (hit == NULL) {
                break;
            }
            memcpy(hit + key_len - 5U, "true ", 5U);
            changed = 1;
            cursor = hit + key_len;
        }
    }
    for (size_t i = 0; i < sizeof(kSameLengthPatches) / sizeof(kSameLengthPatches[0]); i++) {
        const char *from = kSameLengthPatches[i].from;
        const char *to = kSameLengthPatches[i].to;
        size_t n = strlen(from);
        if (n != strlen(to) || len < n) {
            continue;
        }
        char *cursor = buf;
        char *end = buf + len;
        while (cursor + n <= end) {
            char *hit = memmem(cursor, (size_t) (end - cursor), from, n);
            if (hit == NULL) {
                break;
            }
            memcpy(hit, to, n);
            changed = 1;
            cursor = hit + n;
        }
    }
    if (changed) {
        LOGI("CapyPlayer: patched plaintext subscription (%zu bytes)", len);
    }
}

/* Only touch Accept-Encoding on subscription API traffic — never WebDAV/media. */
__attribute__((noinline, used))
void capy_patch_http_request_inplace(char *buf, size_t len) {
    if (buf == NULL || len < 16U) {
        return;
    }
    if (looks_like_webdav_or_binary(buf, len)) {
        return;
    }
    if (memmem(buf, len, "api-capyplayer", 13) == NULL
            && memmem(buf, len, "/subscriptions/", 15) == NULL) {
        return;
    }
    char *cursor = buf;
    char *end = buf + len;
    while (cursor + 4U <= end) {
        char *hit = memmem(cursor, (size_t) (end - cursor), "gzip", 4);
        if (hit == NULL) {
            break;
        }
        memcpy(hit, "iden", 4);
        cursor = hit + 4;
    }
}

__attribute__((naked))
static void flutter_stream_read_hook(void) {
    __asm__ volatile(
            "stp x29, x30, [sp, #-48]!\n"
            "stp x19, x20, [sp, #16]\n"
            "mov x19, x1\n"
            "blr x8\n"
            "mov w20, w0\n"
            "cmp w20, #0\n"
            "b.le 1f\n"
            "mov x0, x19\n"
            "uxtw x1, w20\n"
            "bl capy_patch_subscription_inplace\n"
            "mov w0, w20\n"
            "1:\n"
            "ldp x19, x20, [sp, #16]\n"
            "ldp x29, x30, [sp], #48\n"
            "ret\n");
}

static void *map_veneer_near(uintptr_t site) {
    long page = sysconf(_SC_PAGESIZE);
    if (page <= 0) {
        page = 4096;
    }
    /* MAP_FIXED_NOREPLACE: fail instead of clobbering an existing mapping. */
    const int fixed_noreplace = 0x100000;
    uintptr_t base = site & ~((uintptr_t) page - 1U);
    for (uintptr_t step = (uintptr_t) page; step < (64U * 1024U * 1024U); step += (uintptr_t) page * 32U) {
        uintptr_t hints[2] = {base - step, base + step};
        for (int i = 0; i < 2; i++) {
            if (hints[i] < (uintptr_t) page) {
                continue;
            }
            void *mem = mmap((void *) hints[i], (size_t) page, PROT_READ | PROT_WRITE | PROT_EXEC,
                    MAP_PRIVATE | MAP_ANONYMOUS | fixed_noreplace, -1, 0);
            if (mem == (void *) hints[i]) {
                return mem;
            }
            if (mem != MAP_FAILED) {
                munmap(mem, (size_t) page);
            }
        }
    }
    return NULL;
}

static int install_flutter_read_hook_once(void) {
    if (g_flutter_read_hook_installed) {
        return 1;
    }
    uintptr_t site = 0;
    if (find_runtime_addr_in_maps(0U, kFlutterReadSite, line_has_libflutter, &site) != 0) {
        return 0;
    }
    uint32_t current = *(volatile uint32_t *) site;
    if (current != kBlrX8) {
        LOGE("CapyPlayer: flutter read site mismatch %08x", current);
        return 0;
    }
    void *veneer = map_veneer_near(site);
    if (veneer == NULL) {
        LOGE("CapyPlayer: flutter read veneer out of range");
        return 0;
    }
    uint32_t *code = (uint32_t *) veneer;
    /* ldr x16, #8 ; br x16 ; .quad hook */
    code[0] = 0x58000050U;
    code[1] = 0xD61F0200U;
    *(uint64_t *) (code + 2) = (uint64_t) (uintptr_t) flutter_stream_read_hook;
    __builtin___clear_cache((char *) veneer, (char *) veneer + 16);
    intptr_t bl_off = ((intptr_t) veneer - (intptr_t) site) / 4;
    if (patch_page_rw((void *) site) != 0) {
        LOGE("CapyPlayer: flutter read mprotect failed");
        return 0;
    }
    *(volatile uint32_t *) site = 0x94000000U | ((uint32_t) bl_off & 0x03FFFFFFU);
    __builtin___clear_cache((char *) site, (char *) site + 4);
    g_flutter_read_hook_installed = 1;
    LOGI("CapyPlayer: flutter plaintext read hook at %p", (void *) site);
    return 1;
}

typedef ssize_t (*libc_read_fn)(int, void *, size_t);
typedef ssize_t (*libc_write_fn)(int, const void *, size_t);

static ssize_t flutter_libc_read_hook_c(int fd, void *buf, size_t count) {
    libc_read_fn real = (libc_read_fn) g_libflutter_read_plt;
    if (real == NULL) {
        return -1;
    }
    ssize_t n = real(fd, buf, count);
    /* Only small socket reads — never scan multi-MB WebDAV backup chunks. */
    if (n > 0 && n <= 65536 && buf != NULL && is_socket_fd(fd)) {
        capy_patch_subscription_inplace((char *) buf, (size_t) n);
    }
    return n;
}

static ssize_t flutter_libc_write_hook_c(int fd, const void *buf, size_t count) {
    /* Do not rewrite outbound bodies — WebDAV PUT backup must pass untouched.
       Subscription free→lifetime is handled on the read path only. */
    (void) buf;
    (void) count;
    libc_write_fn real = (libc_write_fn) g_libflutter_write_plt;
    if (real == NULL) {
        return -1;
    }
    return real(fd, buf, count);
}

static int install_bl_hook_to(uintptr_t site, void *hook_fn, const char *label) {
    uint32_t current = *(volatile uint32_t *) site;
    if ((current & 0xFC000000U) != 0x94000000U) {
        LOGE("CapyPlayer: %s site not bl (%08x)", label, current);
        return 0;
    }
    void *veneer = map_veneer_near(site);
    if (veneer == NULL) {
        LOGE("CapyPlayer: %s veneer out of range", label);
        return 0;
    }
    uint32_t *code = (uint32_t *) veneer;
    code[0] = 0x58000050U; /* ldr x16, #8 */
    code[1] = 0xD61F0200U; /* br x16 */
    *(uint64_t *) (code + 2) = (uint64_t) (uintptr_t) hook_fn;
    __builtin___clear_cache((char *) veneer, (char *) veneer + 16);
    intptr_t bl_off = ((intptr_t) veneer - (intptr_t) site) / 4;
    if (patch_page_rw((void *) site) != 0) {
        LOGE("CapyPlayer: %s mprotect failed", label);
        return 0;
    }
    *(volatile uint32_t *) site = 0x94000000U | ((uint32_t) bl_off & 0x03FFFFFFU);
    __builtin___clear_cache((char *) site, (char *) site + 4);
    LOGI("CapyPlayer: %s hooked at %p", label, (void *) site);
    return 1;
}

static int resolve_libflutter_plt(uint32_t bl_site_off, void **plt_out) {
    uintptr_t site = 0;
    if (find_runtime_addr_in_maps(0U, bl_site_off, line_has_libflutter, &site) != 0) {
        return 0;
    }
    uint32_t insn = *(volatile uint32_t *) site;
    if ((insn & 0xFC000000U) != 0x94000000U) {
        return 0;
    }
    int32_t imm = (int32_t) (insn & 0x03FFFFFFU);
    if (imm & 0x02000000) {
        imm |= (int32_t) ~0x03FFFFFFU;
    }
    *plt_out = (void *) (site + (uintptr_t) ((int64_t) imm << 2));
    return 1;
}

static int install_flutter_io_hooks_once(void) {
    if (g_flutter_io_hooks_installed) {
        return 1;
    }
    if (!resolve_libflutter_plt(kFlutterReadPltSites[0], &g_libflutter_read_plt)) {
        LOGI("CapyPlayer: libflutter read@plt not resolved yet");
        return 0;
    }
    if (!resolve_libflutter_plt(kFlutterWritePltSite, &g_libflutter_write_plt)) {
        LOGI("CapyPlayer: libflutter write@plt not resolved yet");
        return 0;
    }
    int ok = 0;
    for (size_t i = 0; i < sizeof(kFlutterReadPltSites) / sizeof(kFlutterReadPltSites[0]); i++) {
        uintptr_t site = 0;
        if (find_runtime_addr_in_maps(0U, kFlutterReadPltSites[i], line_has_libflutter, &site) != 0) {
            continue;
        }
        if (install_bl_hook_to(site, (void *) flutter_libc_read_hook_c, "flutter read@plt")) {
            ok++;
        }
    }
    {
        uintptr_t site = 0;
        if (find_runtime_addr_in_maps(0U, kFlutterWritePltSite, line_has_libflutter, &site) == 0) {
            if (install_bl_hook_to(site, (void *) flutter_libc_write_hook_c, "flutter write@plt")) {
                ok++;
            }
        }
    }
    if (ok <= 0) {
        return 0;
    }
    g_flutter_io_hooks_installed = 1;
    LOGI("CapyPlayer: flutter io hooks installed (%d)", ok);
    return 1;
}

/* Prefer rewriting subscription API responses over path diversion:
 * diverting /verify to 404 made the app set rejectedReceipt and force free. */
static int patch_subscription_paths_once(void) {
    return 0;
}

static int g_getaddrinfo_hook_installed = 0;
typedef int (*getaddrinfo_fn)(const char *, const char *, const struct addrinfo *,
        struct addrinfo **);
static getaddrinfo_fn g_real_getaddrinfo = NULL;

/* libflutter .got.plt JUMP_SLOT for getaddrinfo (Capy 1.1.6 libflutter). */
static const uint32_t kFlutterGetaddrinfoGotOff = 0xb41c88U;

static int should_block_subscription_host(const char *node) {
    if (node == NULL || node[0] == '\0') {
        return 0;
    }
    return strstr(node, "api-capyplayer.feifeiduck.cn") != NULL
            || strstr(node, "blocked.subscription.invalid") != NULL;
}

static int getaddrinfo_subscription_block(const char *node, const char *service,
        const struct addrinfo *hints, struct addrinfo **res) {
    if (should_block_subscription_host(node)) {
        LOGI("CapyPlayer: blocked subscription DNS %s", node);
        if (res != NULL) {
            *res = NULL;
        }
        return EAI_NONAME;
    }
    if (g_real_getaddrinfo == NULL) {
        return EAI_FAIL;
    }
    return g_real_getaddrinfo(node, service, hints, res);
}

static int find_libflutter_base(uintptr_t *base_out) {
    FILE *maps = open_proc_maps();
    if (maps == NULL) {
        return -1;
    }
    char line[1024];
    uintptr_t min_base = 0;
    int found = 0;
    while (fgets(line, sizeof(line), maps) != NULL) {
        if (!line_has_libflutter(line)) {
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

/* Patch libflutter GOT only — does not rewrite libc (WebDAV DNS stays intact). */
static int install_flutter_getaddrinfo_got_once(void) {
    if (g_getaddrinfo_hook_installed) {
        return 1;
    }
    uintptr_t base = 0;
    if (find_libflutter_base(&base) != 0) {
        return 0;
    }
    uintptr_t got = base + (uintptr_t) kFlutterGetaddrinfoGotOff;
    long page = sysconf(_SC_PAGESIZE);
    if (page <= 0) {
        page = 4096;
    }
    uintptr_t page_base = got & ~(uintptr_t) (page - 1);
    if (mprotect((void *) page_base, (size_t) page * 2U, PROT_READ | PROT_WRITE) != 0) {
        LOGE("CapyPlayer: getaddrinfo GOT mprotect failed: %s", strerror(errno));
        return 0;
    }
    getaddrinfo_fn *slot = (getaddrinfo_fn *) got;
    getaddrinfo_fn current = *slot;
    if (current == NULL || current == (getaddrinfo_fn) getaddrinfo_subscription_block) {
        current = (getaddrinfo_fn) dlsym(RTLD_DEFAULT, "getaddrinfo");
    }
    if (current == NULL) {
        LOGE("CapyPlayer: real getaddrinfo unresolved");
        mprotect((void *) page_base, (size_t) page * 2U, PROT_READ);
        return 0;
    }
    g_real_getaddrinfo = current;
    *slot = getaddrinfo_subscription_block;
    __builtin___clear_cache((char *) slot, (char *) slot + sizeof(*slot));
    mprotect((void *) page_base, (size_t) page * 2U, PROT_READ);
    g_getaddrinfo_hook_installed = 1;
    LOGI("CapyPlayer: libflutter getaddrinfo GOT filtered at %p (real %p)",
            (void *) got, (void *) current);
    return 1;
}

static int install_capy_network_hooks(void) {
    /* Pro without heap scrub / without per-chunk body scans:
     *  - libflutter getaddrinfo GOT blocks only subscription host (WebDAV OK)
     *  - Paywall + libapp host divert elsewhere
     * Flutter read@plt / stream hooks disabled: they memmem every WebDAV chunk
     * and ANR during multi-GB backup. */
    int installed = 0;
    if (install_flutter_getaddrinfo_got_once()) {
        installed++;
    }
    (void) install_ssl_read_hook_once;
    (void) install_flutter_read_hook_once;
    (void) install_flutter_io_hooks_once;
    (void) patch_subscription_paths_once;
    (void) capy_patch_http_request_inplace;
    if (installed > 0) {
        LOGI("CapyPlayer: DNS filter ready (%d) — Pro+WebDAV-safe", installed);
    } else {
        LOGI("CapyPlayer: DNS filter pending (libflutter not mapped)");
    }
    return installed > 0 ? 1 : 0;
}

JNIEXPORT void JNICALL
Java_com_afusekt_lsp_hook_CapyPlayerNativePatch_nativeInstallNetworkHooks(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    install_capy_network_hooks();
}

JNIEXPORT void JNICALL
Java_com_afusekt_lsp_libxposed_LibCapyPlayerNative_nativeInstallNetworkHooks(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    install_capy_network_hooks();
}

static jboolean capy_patches_complete_jboolean(void) {
    size_t capy115_total = sizeof(kCapy115Patches) / sizeof(kCapy115Patches[0]);
    size_t capy_total = sizeof(kCapyPatches) / sizeof(kCapyPatches[0]);
    return capy_patches_satisfied(g_last_capy_applied, capy115_total, capy_total) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_afusekt_lsp_hook_CapyPlayerNativePatch_nativeApplyLibAppPatches(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    apply_capy_patches();
    return capy_patches_complete_jboolean();
}

JNIEXPORT jboolean JNICALL
Java_com_afusekt_lsp_libxposed_LibCapyPlayerNative_nativeApplyLibAppPatches(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    apply_capy_patches();
    return capy_patches_complete_jboolean();
}

JNIEXPORT jint JNICALL
Java_com_afusekt_lsp_hook_CapyPlayerNativePatch_nativeApplyLibAppPatchCount(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    apply_capy_patches();
    return (jint) g_last_capy_applied;
}

JNIEXPORT jint JNICALL
Java_com_afusekt_lsp_libxposed_LibCapyPlayerNative_nativeApplyLibAppPatchCount(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    apply_capy_patches();
    return (jint) g_last_capy_applied;
}

static int libapp_is_mapped(void) {
    uintptr_t base = 0;
    return find_libapp_base(&base) == 0;
}

static jboolean wait_and_apply_capy_patches(jint timeout_ms) {
    int waited = 0;
    const int step = 50;
    while (waited <= timeout_ms) {
        apply_capy_patches();
        if (capy_patches_complete_jboolean()) {
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

JNIEXPORT jboolean JNICALL
Java_com_afusekt_lsp_hook_CapyPlayerNativePatch_nativeWaitAndApply(JNIEnv *env, jclass clazz, jint timeout_ms) {
    (void) env;
    (void) clazz;
    return wait_and_apply_capy_patches(timeout_ms);
}

JNIEXPORT jboolean JNICALL
Java_com_afusekt_lsp_libxposed_LibCapyPlayerNative_nativeWaitAndApply(JNIEnv *env, jclass clazz, jint timeout_ms) {
    (void) env;
    (void) clazz;
    return wait_and_apply_capy_patches(timeout_ms);
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
