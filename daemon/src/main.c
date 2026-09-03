#define _GNU_SOURCE

/* qcom-efsd -- root helper that exposes the modem's EFS2 filesystem over an
 * abstract unix socket, one JSON object per line.
 *
 * Started by the app as:
 *   su -c "setsid '<path>/qcom-efsd' -uid <appUid> [-verbose] &"
 *
 * Only the uid passed with -uid may connect; the daemon refuses to start
 * without it so that a stray instance can never be driven by another app.
 */
#include "diag.h"
#include "efs2.h"
#include "util.h"

#include <errno.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stddef.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#define QEFSD_VERSION   "1.0.1"
#define DEFAULT_SOCKET  "qcom_efsd"
#define MAX_LINE        (4u * 1024 * 1024)
#define MAX_INLINE_READ (512u * 1024)

static diag_t g_diag;
static efs_t  g_efs;
static int    g_open = 0;
static int    g_readonly = 1;

/* ---- small helpers ---------------------------------------------------- */

static const char *type_of(int32_t mode, int32_t entry_type)
{
    switch (mode & 0xF000) {
    case 0x4000: return "dir";
    case 0x8000: return "file";
    case 0xA000: return "link";
    case 0xE000: return "item";
    }
    /* Fallback for a modem that reports no mode bits.  The numbering is the
     * one observed on SM8350: 0 = file, 1 = directory, 15 = item file. */
    switch (entry_type) {
    case 0:  return "file";
    case 1:  return "dir";
    case 2:  return "link";
    case 15: return "item";
    }
    return "unknown";
}

static void fail(sbuf *o, const char *fmt, ...)
{
    char msg[512];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(msg, sizeof msg, fmt, ap);
    va_end(ap);

    sb_reset(o);
    sb_str(o, "{\"ok\":false,\"error\":");
    sb_json_str(o, msg);
    sb_str(o, "}");
}

static void fail_efs(sbuf *o, efs_t *e)
{
    sb_reset(o);
    sb_str(o, "{\"ok\":false,\"error\":");
    sb_json_str(o, e->last_error[0] ? e->last_error : "EFS operation failed");
    sb_fmt(o, ",\"efs_errno\":%d}", e->last_errno);
}

static int mkdirp_local(const char *path)
{
    char buf[1024];
    size_t len = strlen(path);
    if (len >= sizeof buf) return -1;
    memcpy(buf, path, len + 1);

    for (char *p = buf + 1; *p; p++) {
        if (*p != '/') continue;
        *p = 0;
        if (mkdir(buf, 0777) < 0 && errno != EEXIST) return -1;
        *p = '/';
    }
    return (mkdir(buf, 0777) < 0 && errno != EEXIST) ? -1 : 0;
}

static int write_local(const char *path, const uint8_t *data, size_t len)
{
    int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0666);
    if (fd >= 0) fchmod(fd, 0666);   /* the app runs as a different uid */
    if (fd < 0) return -1;
    size_t off = 0;
    while (off < len) {
        ssize_t w = write(fd, data + off, len - off);
        if (w <= 0) { close(fd); return -1; }
        off += (size_t)w;
    }
    close(fd);
    return 0;
}

static uint8_t *read_local(const char *path, size_t *len_out)
{
    int fd = open(path, O_RDONLY);
    if (fd < 0) return NULL;

    struct stat st;
    if (fstat(fd, &st) < 0 || st.st_size < 0) { close(fd); return NULL; }

    size_t len = (size_t)st.st_size;
    uint8_t *buf = malloc(len ? len : 1);
    if (!buf) { close(fd); return NULL; }

    size_t off = 0;
    while (off < len) {
        ssize_t r = read(fd, buf + off, len - off);
        if (r <= 0) break;
        off += (size_t)r;
    }
    close(fd);
    *len_out = off;
    return buf;
}

static void join_path(char *dst, size_t n, const char *dir, const char *name)
{
    if (strcmp(dir, "/") == 0) snprintf(dst, n, "/%s", name);
    else snprintf(dst, n, "%s/%s", dir, name);
}

/* ---- directory listing ------------------------------------------------ */

static int list_dir(sbuf *o, const char *path)
{
    int32_t dirp = efs_opendir(&g_efs, path);
    if (dirp < 0) return -1;

    sb_str(o, "{\"ok\":true,\"path\":");
    sb_json_str(o, path);
    sb_str(o, ",\"entries\":[");

    efs_dirent_t ent;
    uint32_t seq = 1;
    int first = 1;

    for (;;) {
        int r = efs_readdir(&g_efs, dirp, seq, &ent);
        if (r < 0) break;      /* treat as end of listing */
        if (r > 0) break;

        if (!first) sb_str(o, ",");
        first = 0;

        sb_str(o, "{\"name\":");
        sb_json_str(o, ent.name);
        sb_fmt(o, ",\"type\":\"%s\",\"mode\":%d,\"size\":%d,"
                  "\"atime\":%d,\"mtime\":%d,\"ctime\":%d,\"entry_type\":%d}",
               type_of(ent.mode, ent.entry_type), ent.mode, ent.size,
               ent.atime, ent.mtime, ent.ctime, ent.entry_type);
        seq++;
    }

    efs_closedir(&g_efs, dirp);
    sb_str(o, "]}");
    return 0;
}

/* ---- recursive pull --------------------------------------------------- */

typedef struct {
    unsigned files, dirs, links, errors;
    unsigned long long bytes;
    size_t max_file;
    sbuf *errlist;
    int erridx;
} pull_stats;

static void note_error(pull_stats *ps, const char *path, const char *why)
{
    if (ps->erridx < 64) {
        if (ps->erridx) sb_str(ps->errlist, ",");
        sb_str(ps->errlist, "{\"path\":");
        sb_json_str(ps->errlist, path);
        sb_str(ps->errlist, ",\"error\":");
        sb_json_str(ps->errlist, why);
        sb_str(ps->errlist, "}");
        ps->erridx++;
    }
    ps->errors++;
}

static void pull_tree(const char *efs_path, const char *local_dir,
                      int depth, pull_stats *ps)
{
    if (depth > 24) return;

    if (mkdirp_local(local_dir) < 0) {
        note_error(ps, local_dir, "cannot create local directory");
        return;
    }
    ps->dirs++;

    int32_t dirp = efs_opendir(&g_efs, efs_path);
    if (dirp < 0) { note_error(ps, efs_path, g_efs.last_error); return; }

    /* Collect the listing first: the modem cannot serve a nested opendir
     * while we are iterating this one. */
    efs_dirent_t *ents = NULL;
    size_t nent = 0, cap = 0;
    efs_dirent_t ent;

    for (uint32_t seq = 1; ; seq++) {
        int r = efs_readdir(&g_efs, dirp, seq, &ent);
        if (r != 0) break;
        if (nent == cap) {
            cap = cap ? cap * 2 : 32;
            efs_dirent_t *ne = realloc(ents, cap * sizeof *ne);
            if (!ne) break;
            ents = ne;
        }
        ents[nent++] = ent;
    }
    efs_closedir(&g_efs, dirp);

    for (size_t i = 0; i < nent; i++) {
        char child[EFS_PATH_MAX], local[1024];
        join_path(child, sizeof child, efs_path, ents[i].name);
        snprintf(local, sizeof local, "%s/%s", local_dir, ents[i].name);

        const char *t = type_of(ents[i].mode, ents[i].entry_type);

        if (strcmp(t, "dir") == 0) {
            pull_tree(child, local, depth + 1, ps);
        } else if (strcmp(t, "link") == 0) {
            char target[EFS_PATH_MAX];
            if (efs_readlink(&g_efs, child, target, sizeof target) == 0) {
                char meta[1024 + 16];
                snprintf(meta, sizeof meta, "%s.symlink", local);
                write_local(meta, (const uint8_t *)target, strlen(target));
                ps->links++;
            } else {
                note_error(ps, child, g_efs.last_error);
            }
        } else {
            if (ps->max_file && ents[i].size > 0 && (size_t)ents[i].size > ps->max_file) {
                note_error(ps, child, "skipped: larger than the size limit");
                continue;
            }
            uint8_t *data = NULL;
            size_t len = 0;
            if (efs_read_file(&g_efs, child, &data, &len) == 0) {
                if (write_local(local, data, len) == 0) {
                    ps->files++;
                    ps->bytes += len;
                } else {
                    note_error(ps, child, "cannot write the local copy");
                }
                free(data);
            } else {
                note_error(ps, child, g_efs.last_error);
            }
        }
    }
    free(ents);
}

/* ---- recursive delete ------------------------------------------------- */

static int rm_tree(const char *path, int depth)
{
    if (depth > 24) return -1;

    int32_t dirp = efs_opendir(&g_efs, path);
    if (dirp < 0) return efs_unlink(&g_efs, path);

    efs_dirent_t *ents = NULL;
    size_t nent = 0, cap = 0;
    efs_dirent_t ent;

    for (uint32_t seq = 1; ; seq++) {
        int r = efs_readdir(&g_efs, dirp, seq, &ent);
        if (r != 0) break;
        if (nent == cap) {
            cap = cap ? cap * 2 : 32;
            efs_dirent_t *ne = realloc(ents, cap * sizeof *ne);
            if (!ne) break;
            ents = ne;
        }
        ents[nent++] = ent;
    }
    efs_closedir(&g_efs, dirp);

    int rc = 0;
    for (size_t i = 0; i < nent; i++) {
        char child[EFS_PATH_MAX];
        join_path(child, sizeof child, path, ents[i].name);
        if (strcmp(type_of(ents[i].mode, ents[i].entry_type), "dir") == 0) {
            if (rm_tree(child, depth + 1) < 0) rc = -1;
        } else {
            if (efs_unlink(&g_efs, child) < 0) rc = -1;
        }
    }
    free(ents);

    if (efs_rmdir(&g_efs, path) < 0) rc = -1;
    return rc;
}

/* ---- command dispatch ------------------------------------------------- */

static int require_open(sbuf *o)
{
    if (g_open) return 0;
    fail(o, "not connected to the modem; send {\"cmd\":\"open\"} first");
    return -1;
}

static int require_write(sbuf *o)
{
    if (!g_readonly) return 0;
    fail(o, "the daemon is in read-only mode; send {\"cmd\":\"readonly\",\"on\":false} first");
    return -1;
}

static int get_path(const char *req, sbuf *o, char *path, size_t n)
{
    if (json_get_str(req, "path", path, n) < 0) {
        fail(o, "missing 'path' string field");
        return -1;
    }
    if (path[0] != '/') { fail(o, "path must be absolute"); return -1; }
    return 0;
}

static void cmd_open(sbuf *o)
{
    if (g_open) { diag_close(&g_diag); g_open = 0; }

    if (diag_open(&g_diag) < 0) {
        fail(o, "%s", diag_error(&g_diag));
        diag_close(&g_diag);
        return;
    }

    efs_init(&g_efs, &g_diag);

    if (efs_detect(&g_efs) < 0) {
        fail(o, "%s", g_efs.last_error);
        diag_close(&g_diag);
        return;
    }
    g_open = 1;

    sb_fmt(o, "{\"ok\":true,\"transport\":\"%s\",\"subsys\":%d,\"readonly\":%s}",
           diag_transport_desc(&g_diag), g_efs.method,
           g_readonly ? "true" : "false");
}

static void cmd_read(const char *req, sbuf *o)
{
    char path[EFS_PATH_MAX], out[1024];
    if (get_path(req, o, path, sizeof path) < 0) return;

    uint8_t *data = NULL;
    size_t len = 0;
    if (efs_read_file(&g_efs, path, &data, &len) < 0) { fail_efs(o, &g_efs); return; }

    if (json_get_str(req, "out", out, sizeof out) > 0) {
        int rc = write_local(out, data, len);
        free(data);
        if (rc < 0) { fail(o, "cannot write %s: %s", out, strerror(errno)); return; }
        sb_str(o, "{\"ok\":true,\"path\":");
        sb_json_str(o, path);
        sb_str(o, ",\"out\":");
        sb_json_str(o, out);
        sb_fmt(o, ",\"size\":%zu}", len);
        return;
    }

    if (len > MAX_INLINE_READ) {
        free(data);
        fail(o, "file is %zu bytes; pass an 'out' path for anything over %u", len, MAX_INLINE_READ);
        return;
    }
    char *b64 = malloc(4 * ((len + 2) / 3) + 8);
    if (!b64) { free(data); fail(o, "out of memory"); return; }
    b64_encode(data, len, b64);
    free(data);

    sb_str(o, "{\"ok\":true,\"path\":");
    sb_json_str(o, path);
    sb_fmt(o, ",\"size\":%zu,\"data\":\"%s\"}", len, b64);
    free(b64);
}

static void cmd_write(const char *req, sbuf *o)
{
    char path[EFS_PATH_MAX], src[1024];
    long long mode = 0644;
    if (get_path(req, o, path, sizeof path) < 0) return;
    json_get_i64(req, "mode", &mode);

    /* "item": true forces an EFS item file, false forces an ordinary file.
     * Left out, the path decides -- /nv/item_files/... and friends. */
    int want_item = 0;
    int force_item = (json_get_bool(req, "item", &want_item) == 0) ? want_item : -1;

    uint8_t *data = NULL;
    size_t len = 0;

    if (json_get_str(req, "src", src, sizeof src) > 0) {
        data = read_local(src, &len);
        if (!data) { fail(o, "cannot read %s: %s", src, strerror(errno)); return; }
    } else {
        const char *b64 = NULL;
        size_t blen = 0;
        /* the base64 blob can be large, so decode it in place from the raw line */
        char *tmp = malloc(MAX_INLINE_READ * 2);
        if (!tmp) { fail(o, "out of memory"); return; }
        int n = json_get_str(req, "data", tmp, MAX_INLINE_READ * 2);
        if (n < 0) { free(tmp); fail(o, "missing 'src' or 'data' field"); return; }
        b64 = tmp;
        blen = (size_t)n;
        data = malloc(blen + 3);
        if (!data) { free(tmp); fail(o, "out of memory"); return; }
        long dl = b64_decode(b64, data, blen + 3);
        free(tmp);
        if (dl < 0) { free(data); fail(o, "'data' is not valid base64"); return; }
        len = (size_t)dl;
    }

    int rc = efs_write_file(&g_efs, path, data, len, (int16_t)mode, force_item);
    free(data);

    if (rc < 0) { fail_efs(o, &g_efs); return; }

    /* Report the type actually written, so a caller never has to guess. */
    int wrote_item = (force_item < 0) ? efs_is_item_path(path) : (force_item != 0);
    sb_str(o, "{\"ok\":true,\"path\":");
    sb_json_str(o, path);
    sb_fmt(o, ",\"size\":%zu,\"item\":%s}", len, wrote_item ? "true" : "false");
}

static void cmd_stat(const char *req, sbuf *o)
{
    char path[EFS_PATH_MAX];
    if (get_path(req, o, path, sizeof path) < 0) return;

    /* Default to lstat so a symlink is described as a symlink; EFS2's stat
     * follows links just like POSIX stat().  "follow":true asks for the
     * target's attributes instead. */
    int follow = 0;
    json_get_bool(req, "follow", &follow);

    efs_stat_t st;
    char target[EFS_PATH_MAX];
    int have_target = 0;
    const char *used = follow ? "stat" : "lstat";
    int rc = follow ? efs_stat(&g_efs, path, &st) : efs_lstat(&g_efs, path, &st);
    if (rc < 0 && !follow) {
        used = "stat (lstat unavailable)";
        rc = efs_stat(&g_efs, path, &st);
    }
    if (rc < 0) { fail_efs(o, &g_efs); return; }

    /* Where there is no lstat, stat has already followed the link and says
     * "file".  readlink is the only way left to tell: it fails on anything
     * that is not a symlink, so a success settles it. */
    if (!follow && (st.mode & 0xF000) != 0x4000 &&
        efs_readlink(&g_efs, path, target, sizeof target) == 0)
        have_target = 1;

    sb_str(o, "{\"ok\":true,\"path\":");
    sb_json_str(o, path);
    sb_fmt(o, ",\"type\":\"%s\",\"mode\":%d,\"size\":%d,\"nlink\":%d,"
              "\"atime\":%d,\"mtime\":%d,\"ctime\":%d,\"stat_call\":\"%s\"",
           have_target ? "link" : type_of(st.mode, 0), st.mode, st.size, st.nlink,
           st.atime, st.mtime, st.ctime, used);
    if (have_target) {
        sb_str(o, ",\"target\":");
        sb_json_str(o, target);
    }

    sb_str(o, "}");
}

static void cmd_statfs(const char *req, sbuf *o)
{
    char path[EFS_PATH_MAX];
    if (get_path(req, o, path, sizeof path) < 0) return;

    uint8_t raw[256];
    int rawlen = 0;
    if (efs_statfs(&g_efs, path, raw, sizeof raw, &rawlen) < 0) { fail_efs(o, &g_efs); return; }

    char hex[520];
    hex_encode(raw, (size_t)rawlen < 256 ? (size_t)rawlen : 256, hex);

    sb_str(o, "{\"ok\":true,\"path\":");
    sb_json_str(o, path);
    sb_fmt(o, ",\"raw\":\"%s\"", hex);

    /* The field order differs between modem generations, so rather than trust
     * a fixed offset we look for the first plausible (block_size, total) pair:
     * a power-of-two block size followed by a non-zero block count.  The raw
     * blob is always included so a caller can decode it differently. */
    int words = rawlen / 4;
    uint32_t v[16];
    if (words > 16) words = 16;
    for (int i = 0; i < words; i++)
        v[i] = (uint32_t)raw[i * 4] | ((uint32_t)raw[i * 4 + 1] << 8) |
               ((uint32_t)raw[i * 4 + 2] << 16) | ((uint32_t)raw[i * 4 + 3] << 24);

    for (int i = 0; i + 2 < words; i++) {
        uint32_t bs = v[i], total = v[i + 1], freeb = v[i + 2];
        if (bs < 256 || bs > 65536 || (bs & (bs - 1))) continue;   /* power of two */
        if (!total || total > (1u << 24) || freeb > total) continue;

        sb_fmt(o, ",\"block_size\":%u,\"total_blocks\":%u,\"free_blocks\":%u,"
                  "\"total_bytes\":%llu,\"free_bytes\":%llu",
               bs, total, freeb,
               (unsigned long long)bs * total, (unsigned long long)bs * freeb);
        break;
    }
    sb_str(o, "}");
}

static void cmd_pull_tree(const char *req, sbuf *o)
{
    char path[EFS_PATH_MAX], out[1024];
    long long maxf = 8u * 1024 * 1024;

    if (get_path(req, o, path, sizeof path) < 0) return;
    if (json_get_str(req, "out", out, sizeof out) < 0) { fail(o, "missing 'out' field"); return; }
    json_get_i64(req, "max_file", &maxf);

    sbuf errs;
    sb_init(&errs);

    pull_stats ps;
    memset(&ps, 0, sizeof ps);
    ps.max_file = (size_t)maxf;
    ps.errlist = &errs;

    pull_tree(path, out, 0, &ps);

    sb_str(o, "{\"ok\":true,\"path\":");
    sb_json_str(o, path);
    sb_str(o, ",\"out\":");
    sb_json_str(o, out);
    sb_fmt(o, ",\"dirs\":%u,\"files\":%u,\"links\":%u,\"bytes\":%llu,\"errors\":%u,\"error_list\":[%s]}",
           ps.dirs, ps.files, ps.links, ps.bytes, ps.errors, errs.p ? errs.p : "");
    sb_free(&errs);
}

static void cmd_nv_read(const char *req, sbuf *o)
{
    long long item = -1, index = -1;
    if (json_get_i64(req, "item", &item) < 0 || item < 0 || item > 0xFFFF) {
        fail(o, "missing or invalid 'item' field (0-65535)");
        return;
    }
    json_get_i64(req, "index", &index);

    uint8_t data[NV_ITEM_DATA_SIZE];
    uint16_t status = 0;
    int rc = (index >= 0)
        ? nv_read_sub(&g_diag, (uint16_t)item, (uint16_t)index, data, &status, 4000)
        : nv_read(&g_diag, (uint16_t)item, data, &status, 4000);

    if (rc < 0) {
        /* Either the target rejected the request outright (a DIAG-level error
         * rather than an NV status) or the answer was malformed. */
        fail(o, "NV item %lld is not readable on this target: %s", item, diag_error(&g_diag));
        return;
    }

    char hex[NV_ITEM_DATA_SIZE * 2 + 2];
    hex_encode(data, sizeof data, hex);
    sb_fmt(o, "{\"ok\":true,\"item\":%lld,\"status\":%u,\"status_text\":\"%s\",\"data\":\"%s\"}",
           item, status, nv_status_str(status), hex);
}

static void cmd_nv_write(const char *req, sbuf *o)
{
    long long item = -1, index = -1;
    char hex[NV_ITEM_DATA_SIZE * 2 + 4];

    if (json_get_i64(req, "item", &item) < 0 || item < 0 || item > 0xFFFF) {
        fail(o, "missing or invalid 'item' field (0-65535)");
        return;
    }
    if (json_get_str(req, "data", hex, sizeof hex) < 0) { fail(o, "missing 'data' hex field"); return; }
    json_get_i64(req, "index", &index);

    uint8_t data[NV_ITEM_DATA_SIZE];
    memset(data, 0, sizeof data);
    long n = hex_decode(hex, data, sizeof data);
    if (n < 0) { fail(o, "'data' is not valid hex"); return; }

    uint16_t status = 0;
    int rc = (index >= 0)
        ? nv_write_sub(&g_diag, (uint16_t)item, (uint16_t)index, data, sizeof data, &status, 4000)
        : nv_write(&g_diag, (uint16_t)item, data, sizeof data, &status, 4000);

    if (rc < 0) {
        fail(o, "NV write for item %lld failed: %s", item, diag_error(&g_diag));
        return;
    }
    if (status != 0) {
        sb_fmt(o, "{\"ok\":false,\"error\":\"NV write rejected: %s\",\"status\":%u}",
               nv_status_str(status), status);
        return;
    }
    sb_fmt(o, "{\"ok\":true,\"item\":%lld,\"status\":0}", item);
}

static void cmd_spc(const char *req, sbuf *o)
{
    char spc[32];
    if (json_get_str(req, "spc", spc, sizeof spc) < 0) {
        fail(o, "missing 'spc' string field (six digits)");
        return;
    }
    if (strlen(spc) != 6) { fail(o, "the SPC must be exactly six digits"); return; }
    for (int i = 0; i < 6; i++)
        if (spc[i] < '0' || spc[i] > '9') { fail(o, "the SPC must be six digits"); return; }

    int ok = 0;
    if (diag_spc(&g_diag, spc, &ok, 4000) < 0) {
        fail(o, "SPC exchange failed: %s", diag_error(&g_diag));
        return;
    }
    sb_fmt(o, "{\"ok\":true,\"unlocked\":%s}", ok ? "true" : "false");
}

static void cmd_raw(const char *req, sbuf *o)
{
    char hex[8192];
    long long timeout = 4000;

    if (json_get_str(req, "hex", hex, sizeof hex) < 0) { fail(o, "missing 'hex' field"); return; }
    json_get_i64(req, "timeout", &timeout);

    /* By default the answer has to match the request, the way every other
     * command needs it to.  "match":false returns the first frame that
     * arrives instead -- which is how you find out what a target answers
     * when it answers with something unexpected. */
    int match = 1;
    json_get_bool(req, "match", &match);

    uint8_t pkt[DIAG_MAX_PKT];
    long n = hex_decode(hex, pkt, sizeof pkt);
    if (n <= 0) { fail(o, "'hex' is not a valid packet"); return; }

    uint8_t resp[DIAG_MAX_PKT];
    int r;
    if (match) {
        r = diag_xfer(&g_diag, pkt, (size_t)n, resp, sizeof resp, (int)timeout);
    } else if (diag_send(&g_diag, pkt, (size_t)n) < 0) {
        r = -1;
    } else {
        r = diag_recv(&g_diag, resp, sizeof resp, (int)timeout, -1, -1, -1);
    }
    if (r < 0) { fail(o, "%s", diag_error(&g_diag)); return; }

    char *rhex = malloc((size_t)r * 2 + 2);
    if (!rhex) { fail(o, "out of memory"); return; }
    hex_encode(resp, (size_t)r, rhex);
    sb_fmt(o, "{\"ok\":true,\"length\":%d,\"response\":\"%s\"}", r, rhex);
    free(rhex);
}

static void dispatch(const char *req, sbuf *o)
{
    char cmd[64];

    if (json_get_str(req, "cmd", cmd, sizeof cmd) < 0) {
        fail(o, "missing 'cmd' string field");
        return;
    }

    if (!strcmp(cmd, "version")) {
        sb_fmt(o, "{\"ok\":true,\"version\":\"%s\",\"connected\":%s,\"readonly\":%s}",
               QEFSD_VERSION, g_open ? "true" : "false", g_readonly ? "true" : "false");
        return;
    }
    if (!strcmp(cmd, "readonly")) {
        int on = 1;
        if (json_get_bool(req, "on", &on) < 0) { fail(o, "missing 'on' boolean field"); return; }
        g_readonly = on;
        sb_fmt(o, "{\"ok\":true,\"readonly\":%s}", g_readonly ? "true" : "false");
        return;
    }
    if (!strcmp(cmd, "open"))  { cmd_open(o); return; }
    if (!strcmp(cmd, "close")) {
        if (g_open) { diag_close(&g_diag); g_open = 0; }
        sb_str(o, "{\"ok\":true}");
        return;
    }
    if (!strcmp(cmd, "stats")) {
        sb_fmt(o, "{\"ok\":true,\"rx_frames\":%lu,\"rx_dropped\":%lu,"
                  "\"transport\":\"%s\",\"subsys\":%d}",
               g_diag.rx_frames, g_diag.rx_dropped,
               diag_transport_desc(&g_diag), g_efs.method);
        return;
    }
    if (!strcmp(cmd, "shutdown")) {
        if (g_open) { diag_close(&g_diag); g_open = 0; }
        sb_str(o, "{\"ok\":true,\"bye\":true}");
        return;
    }

    if (require_open(o) < 0) return;

    if (!strcmp(cmd, "ls")) {
        char path[EFS_PATH_MAX];
        if (get_path(req, o, path, sizeof path) < 0) return;
        if (list_dir(o, path) < 0) fail_efs(o, &g_efs);
        return;
    }
    if (!strcmp(cmd, "stat"))   { cmd_stat(req, o); return; }
    if (!strcmp(cmd, "statfs")) { cmd_statfs(req, o); return; }
    if (!strcmp(cmd, "read"))   { cmd_read(req, o); return; }
    if (!strcmp(cmd, "readlink")) {
        char path[EFS_PATH_MAX], target[EFS_PATH_MAX];
        if (get_path(req, o, path, sizeof path) < 0) return;
        if (efs_readlink(&g_efs, path, target, sizeof target) < 0) { fail_efs(o, &g_efs); return; }
        sb_str(o, "{\"ok\":true,\"target\":");
        sb_json_str(o, target);
        sb_str(o, "}");
        return;
    }
    if (!strcmp(cmd, "pull_tree")) { cmd_pull_tree(req, o); return; }
    if (!strcmp(cmd, "image")) {
        char path[EFS_PATH_MAX], out[1024];
        uint64_t bytes = 0;
        if (get_path(req, o, path, sizeof path) < 0) return;
        if (json_get_str(req, "out", out, sizeof out) < 0) { fail(o, "missing 'out' field"); return; }
        if (efs_image_dump(&g_efs, path, out, &bytes) < 0) { fail_efs(o, &g_efs); return; }
        sb_str(o, "{\"ok\":true,\"out\":");
        sb_json_str(o, out);
        sb_fmt(o, ",\"bytes\":%llu}", (unsigned long long)bytes);
        return;
    }
    if (!strcmp(cmd, "nv_read")) { cmd_nv_read(req, o); return; }

    /* Everything below can change the modem -- "raw" included, since an
     * arbitrary packet may well be a write. */
    if (require_write(o) < 0) return;

    if (!strcmp(cmd, "raw"))      { cmd_raw(req, o); return; }
    if (!strcmp(cmd, "write"))    { cmd_write(req, o); return; }
    if (!strcmp(cmd, "nv_write")) { cmd_nv_write(req, o); return; }
    if (!strcmp(cmd, "spc"))      { cmd_spc(req, o); return; }

    if (!strcmp(cmd, "mkdir") || !strcmp(cmd, "rmdir") || !strcmp(cmd, "unlink") ||
        !strcmp(cmd, "rmtree") || !strcmp(cmd, "chmod")) {
        char path[EFS_PATH_MAX];
        long long mode = 0777;
        if (get_path(req, o, path, sizeof path) < 0) return;
        json_get_i64(req, "mode", &mode);

        int rc;
        if (!strcmp(cmd, "mkdir"))       rc = efs_mkdir(&g_efs, path, (int16_t)mode);
        else if (!strcmp(cmd, "rmdir"))  rc = efs_rmdir(&g_efs, path);
        else if (!strcmp(cmd, "unlink")) rc = efs_unlink(&g_efs, path);
        else if (!strcmp(cmd, "chmod"))  rc = efs_chmod(&g_efs, path, (int16_t)mode);
        else                             rc = rm_tree(path, 0);

        if (rc < 0) { fail_efs(o, &g_efs); return; }
        sb_str(o, "{\"ok\":true}");
        return;
    }
    if (!strcmp(cmd, "rename")) {
        char from[EFS_PATH_MAX], to[EFS_PATH_MAX];
        if (json_get_str(req, "from", from, sizeof from) < 0 ||
            json_get_str(req, "to", to, sizeof to) < 0) {
            fail(o, "rename needs 'from' and 'to'");
            return;
        }
        if (efs_rename(&g_efs, from, to) < 0) { fail_efs(o, &g_efs); return; }
        sb_str(o, "{\"ok\":true}");
        return;
    }
    if (!strcmp(cmd, "symlink")) {
        char target[EFS_PATH_MAX], link[EFS_PATH_MAX];
        if (json_get_str(req, "target", target, sizeof target) < 0 ||
            json_get_str(req, "link", link, sizeof link) < 0) {
            fail(o, "symlink needs 'target' and 'link'");
            return;
        }
        if (efs_symlink(&g_efs, target, link) < 0) { fail_efs(o, &g_efs); return; }
        sb_str(o, "{\"ok\":true}");
        return;
    }
    if (!strcmp(cmd, "sync")) {
        if (efs_sync(&g_efs) < 0) { fail_efs(o, &g_efs); return; }
        sb_str(o, "{\"ok\":true}");
        return;
    }

    fail(o, "unknown command '%s'", cmd);
}

/* ---- socket server ---------------------------------------------------- */

static int listen_abstract(const char *name)
{
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) return -1;

    struct sockaddr_un sa;
    memset(&sa, 0, sizeof sa);
    sa.sun_family = AF_UNIX;
    sa.sun_path[0] = 0;                       /* abstract namespace */
    size_t nlen = strlen(name);
    if (nlen > sizeof sa.sun_path - 2) { close(fd); return -1; }
    memcpy(sa.sun_path + 1, name, nlen);

    socklen_t alen = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + nlen);
    if (bind(fd, (struct sockaddr *)&sa, alen) < 0) { close(fd); return -1; }
    if (listen(fd, 4) < 0) { close(fd); return -1; }
    return fd;
}

/* Declared locally: bionic only exposes struct ucred behind _GNU_SOURCE and
 * the layout is fixed by the kernel ABI. */
struct qefs_ucred { pid_t pid; uid_t uid; gid_t gid; };

static int peer_uid_ok(int fd, uid_t want)
{
    struct qefs_ucred cred;
    socklen_t len = sizeof cred;
    if (getsockopt(fd, SOL_SOCKET, SO_PEERCRED, &cred, &len) < 0) return 0;
    return cred.uid == want;
}

/* Returns 1 when the client asked the daemon to shut down. */
static int serve(int cfd)
{
    sbuf line, out;
    int bye = 0;
    sb_init(&line);
    sb_init(&out);

    char buf[8192];
    for (;;) {
        ssize_t n = read(cfd, buf, sizeof buf);
        if (n <= 0) break;

        for (ssize_t i = 0; i < n; i++) {
            if (buf[i] != '\n') {
                if (line.len < MAX_LINE) sb_raw(&line, &buf[i], 1);
                continue;
            }

            sb_reset(&out);
            dispatch(line.p ? line.p : "", &out);
            sb_raw(&out, "\n", 1);

            size_t off = 0;
            while (off < out.len) {
                ssize_t w = write(cfd, out.p + off, out.len - off);
                if (w <= 0) goto done;
                off += (size_t)w;
            }
            bye = strstr(out.p, "\"bye\":true") != NULL;
            sb_reset(&line);
            if (bye) goto done;
        }
    }
done:
    sb_free(&line);
    sb_free(&out);
    return bye;
}

int main(int argc, char **argv)
{
    const char *sockname = DEFAULT_SOCKET;
    long peer_uid = -1;

    for (int i = 1; i < argc; i++) {
        if (!strcmp(argv[i], "-uid") && i + 1 < argc)          peer_uid = strtol(argv[++i], NULL, 10);
        else if (!strcmp(argv[i], "-socket") && i + 1 < argc)  sockname = argv[++i];
        else if (!strcmp(argv[i], "-verbose"))                 g_verbose = 1;
        else if (!strcmp(argv[i], "-rw"))                      g_readonly = 0;
        else if (!strcmp(argv[i], "-mock") && i + 1 < argc)    diag_set_mock(argv[++i]);
        else if (!strcmp(argv[i], "-qrtr") && i + 1 < argc) {
            unsigned long node = 0, port = 0;
            if (sscanf(argv[++i], "%lu:%lu", &node, &port) == 2)
                diag_set_qrtr((uint32_t)node, (uint32_t)port);
        }
        else if (!strcmp(argv[i], "-version")) {
            printf("qcom-efsd %s\n", QEFSD_VERSION);
            return 0;
        }
    }

    if (peer_uid < 0) {
        fprintf(stderr, "qcom-efsd: -uid <peer_uid> is required "
                        "(refusing to start without peer authentication).\n");
        return 2;
    }

    signal(SIGPIPE, SIG_IGN);

    int sfd = listen_abstract(sockname);
    if (sfd < 0) {
        fprintf(stderr, "qcom-efsd: cannot bind the abstract socket '%s': %s\n",
                sockname, strerror(errno));
        return 3;
    }

    qlog("qcom-efsd %s listening on @%s for uid %ld", QEFSD_VERSION, sockname, peer_uid);
    fprintf(stderr, "qcom-efsd %s ready\n", QEFSD_VERSION);

    for (;;) {
        int cfd = accept(sfd, NULL, NULL);
        if (cfd < 0) {
            if (errno == EINTR) continue;
            break;
        }
        if (!peer_uid_ok(cfd, (uid_t)peer_uid)) {
            qlog("rejected a connection from an unexpected uid");
            close(cfd);
            continue;
        }
        int bye = serve(cfd);
        close(cfd);
        /* "shutdown" means what it says: stop listening and leave, so that
         * nothing keeps running as root after the client is done. */
        if (bye) {
            qlog("shutting down at the request of the client");
            break;
        }
    }

    if (g_open) diag_close(&g_diag);
    close(sfd);
    return 0;
}
