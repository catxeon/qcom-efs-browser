#define _GNU_SOURCE

/* diag.c -- /dev/diag transport.
 *
 * The framing and the ioctl dance follow what is publicly documented by
 * QCSuper (adb_bridge.c) and MobileInsight's diag_revealer; the DIAG packet
 * layer itself follows qfenix (BSD-3-Clause) and libopenpst.
 */
#include "diag.h"
#include "util.h"

#include <errno.h>
#include <fcntl.h>
#include <stdarg.h>
#include <poll.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <sys/time.h>
#include <unistd.h>

/* ---- kernel constants (include/linux/diagchar.h) ----------------------- */

#define USER_SPACE_DATA_TYPE          0x00000020

#define DIAG_IOCTL_SWITCH_LOGGING     7
#define DIAG_IOCTL_REMOTE_DEV         32
#define DIAG_IOCTL_PERIPHERAL_BUF_CONFIG 35
#define DIAG_IOCTL_QUERY_CON_ALL      40

#define MEMORY_DEVICE_MODE            2

#define DIAG_CON_APSS                 0x0001
#define DIAG_CON_MPSS                 0x0002
#define DIAG_CON_LPASS                0x0004
#define DIAG_CON_WCNSS                0x0008
#define DIAG_CON_SENSORS              0x0010
#define DIAG_CON_ALL                  0x001F

#define DIAG_BUFFERING_MODE_STREAMING 0
#define DEFAULT_LOW_WM_VAL            15
#define DEFAULT_HIGH_WM_VAL           85

#define HDLC_FLAG    0x7E
#define HDLC_ESCAPE  0x7D
#define HDLC_XOR     0x20

/* Android 9+ */
struct logmode_pie {
    uint32_t req_mode;
    uint32_t peripheral_mask;
    uint32_t pd_mask;
    uint8_t  mode_param;
    uint8_t  diag_id;
    uint8_t  pd_val;
    uint8_t  reserved;
    int32_t  peripheral;
} __attribute__((packed));

/* Android 10/11/12+ */
struct logmode_q {
    uint32_t req_mode;
    uint32_t peripheral_mask;
    uint32_t pd_mask;
    uint8_t  mode_param;
    uint8_t  diag_id;
    uint8_t  pd_val;
    uint8_t  reserved;
    int32_t  peripheral;
    int32_t  device_mask;
} __attribute__((packed));

/* Android 7/8 */
struct logmode_legacy {
    uint32_t req_mode;
    uint32_t peripheral_mask;
    uint8_t  mode_param;
} __attribute__((packed));

struct con_all_param {
    uint32_t diag_con_all;
    uint32_t num_peripherals;
    uint32_t upd_map_supported;
};

struct buffering_mode {
    uint8_t peripheral;
    uint8_t mode;
    uint8_t high_wm_val;
    uint8_t low_wm_val;
} __attribute__((packed));

/* ---- HDLC ------------------------------------------------------------- */

uint16_t hdlc_crc16(uint16_t iv, const uint8_t *data, size_t len)
{
    uint16_t crc = iv;
    for (size_t i = 0; i < len; i++) {
        crc ^= data[i];
        for (int b = 0; b < 8; b++)
            crc = (crc & 1) ? (uint16_t)((crc >> 1) ^ 0x8408) : (uint16_t)(crc >> 1);
    }
    return crc;
}

int hdlc_encode(const uint8_t *in, size_t len, uint8_t *out, size_t outsz)
{
    uint16_t crc = hdlc_crc16(0xFFFF, in, len) ^ 0xFFFF;
    uint8_t tail[2] = { (uint8_t)(crc & 0xFF), (uint8_t)(crc >> 8) };
    size_t o = 0;

    for (size_t i = 0; i < len + 2; i++) {
        uint8_t b = (i < len) ? in[i] : tail[i - len];
        if (b == HDLC_FLAG || b == HDLC_ESCAPE) {
            if (o + 2 > outsz) return -1;
            out[o++] = HDLC_ESCAPE;
            out[o++] = b ^ HDLC_XOR;
        } else {
            if (o + 1 > outsz) return -1;
            out[o++] = b;
        }
    }
    if (o + 1 > outsz) return -1;
    out[o++] = HDLC_FLAG;
    return (int)o;
}

int hdlc_decode(const uint8_t *frame, size_t len, uint8_t *out, size_t outsz)
{
    size_t o = 0;

    for (size_t i = 0; i < len; i++) {
        uint8_t b = frame[i];
        if (b == HDLC_FLAG) continue;
        if (b == HDLC_ESCAPE) {
            if (++i >= len) return -1;
            b = frame[i] ^ HDLC_XOR;
        }
        if (o >= outsz) return -1;
        out[o++] = b;
    }
    if (o < 3) return -1;
    /* CRC over payload+CRC yields the residue 0xF0B8 for a good frame. */
    if (hdlc_crc16(0xFFFF, out, o) != 0xF0B8) return -2;
    return (int)(o - 2);
}

/* ---- device setup ----------------------------------------------------- */

const char *diag_error(const diag_t *d) { return d->last_error; }

static void seterr(diag_t *d, const char *fmt, ...)
{
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(d->last_error, sizeof d->last_error, fmt, ap);
    va_end(ap);
}

/* Determines how many bytes DIAG_IOCTL_SWITCH_LOGGING copies from userspace,
 * by placing the argument ever closer to an unmapped page and watching for
 * EFAULT.  The probe values are deliberately bogus so the kernel rejects the
 * request with EINVAL once the copy itself succeeds. */
static long probe_ioctl_arglen(int fd, int req, size_t maxlen)
{
    long pagesz = sysconf(_SC_PAGESIZE);
    if (pagesz <= 0 || maxlen > (size_t)pagesz) return -1;

    char *base = mmap(NULL, (size_t)pagesz * 2, PROT_READ | PROT_WRITE,
                      MAP_ANONYMOUS | MAP_PRIVATE, -1, 0);
    if (base == MAP_FAILED) return -1;

    char *edge = base + pagesz;      /* first byte of the second page */
    munmap(edge, (size_t)pagesz);    /* ... which we now unmap        */
    memset(edge - maxlen, 0x3F, maxlen);

    long len = 0;
    for (; (size_t)len <= maxlen; len++) {
        if (ioctl(fd, req, edge - len) >= 0) break;
        if (errno != EFAULT) break;
    }
    munmap(base, (size_t)pagesz);
    return len;
}

static char g_dev[512] = DIAG_DEV;
static int  g_dev_is_socket = 0;

void diag_set_device(const char *path) { snprintf(g_dev, sizeof g_dev, "%s", path); }
const char *diag_device(void) { return g_dev; }
const char *diag_transport_desc(const diag_t *d) { return d->desc[0] ? d->desc : g_dev; }

/* ---- QRTR ------------------------------------------------------------- */

#ifndef AF_QIPCRTR
#define AF_QIPCRTR            42
#endif
#define QRTR_PORT_CTRL        0xfffffffeu
#define QRTR_TYPE_NEW_SERVER  4
#define QRTR_TYPE_NEW_LOOKUP  10

struct sockaddr_qrtr {
    unsigned short sq_family;
    uint32_t sq_node;
    uint32_t sq_port;
};

struct qrtr_ctrl_pkt {
    uint32_t cmd;
    uint32_t service;
    uint32_t instance;
    uint32_t node;
    uint32_t port;
} __attribute__((packed));

static uint32_t g_qrtr_node, g_qrtr_port;
static int      g_qrtr_pinned;

void diag_set_qrtr(uint32_t node, uint32_t port)
{
    g_qrtr_node = node;
    g_qrtr_port = port;
    g_qrtr_pinned = 1;
}

/* Enumerates the QRTR bus and picks the DIAG service that lives on a node
 * other than ours -- that is the modem.  Instance 1 is the modem's command
 * endpoint on every target seen so far; anything else is a fallback. */
static int qrtr_find_diag(diag_t *d, int sock, uint32_t me, uint32_t *node, uint32_t *port)
{
    struct qrtr_ctrl_pkt pkt;
    memset(&pkt, 0, sizeof pkt);
    pkt.cmd = QRTR_TYPE_NEW_LOOKUP;
    pkt.service = QRTR_DIAG_SERVICE;

    struct sockaddr_qrtr ctrl = { AF_QIPCRTR, me, QRTR_PORT_CTRL };
    if (sendto(sock, &pkt, sizeof pkt, 0, (struct sockaddr *)&ctrl, sizeof ctrl) < 0) {
        seterr(d, "QRTR lookup failed: %s", strerror(errno));
        return -1;
    }

    int found = 0;
    uint32_t best_node = 0, best_port = 0, best_instance = 0;

    for (;;) {
        struct pollfd p = { sock, POLLIN, 0 };
        if (poll(&p, 1, 1500) <= 0) break;

        struct qrtr_ctrl_pkt in;
        ssize_t n = recv(sock, &in, sizeof in, 0);
        if (n < (ssize_t)sizeof in) continue;
        if (in.cmd != QRTR_TYPE_NEW_SERVER) continue;
        if (!in.service && !in.instance && !in.node && !in.port) break;   /* end */
        if (in.service != QRTR_DIAG_SERVICE) continue;
        if (in.node == me) continue;               /* our own side, not the modem */

        qlog("QRTR diag service: node %u port %u instance %u", in.node, in.port, in.instance);
        if (!found || (best_instance != 1 && in.instance == 1)) {
            best_node = in.node;
            best_port = in.port;
            best_instance = in.instance;
        }
        found = 1;
    }

    if (!found) {
        seterr(d, "no DIAG service (%d) published on the QRTR bus", QRTR_DIAG_SERVICE);
        return -1;
    }
    *node = best_node;
    *port = best_port;
    return 0;
}

static int open_qrtr(diag_t *d)
{
    int sock = socket(AF_QIPCRTR, SOCK_DGRAM, 0);
    if (sock < 0) {
        seterr(d, "socket(AF_QIPCRTR) failed: %s", strerror(errno));
        return -1;
    }

    struct sockaddr_qrtr me;
    socklen_t l = sizeof me;
    memset(&me, 0, sizeof me);
    if (getsockname(sock, (struct sockaddr *)&me, &l) < 0) {
        seterr(d, "QRTR getsockname failed: %s", strerror(errno));
        close(sock);
        return -1;
    }

    uint32_t node = g_qrtr_node, port = g_qrtr_port;
    if (!g_qrtr_pinned && qrtr_find_diag(d, sock, me.sq_node, &node, &port) < 0) {
        close(sock);
        return -1;
    }

    d->qrtr_node = node;
    d->qrtr_port = port;
    d->transport = DIAG_TP_QRTR;
    snprintf(d->desc, sizeof d->desc, "qrtr %u:%u", node, port);
    qlog("using QRTR node %u port %u as the DIAG transport", node, port);
    return sock;
}

/* Opens the transport.  A unix socket path behaves like the character device
 * for read/write/poll but has no ioctls, so the setup phase is skipped; when
 * there is no character device at all we fall back to QRTR. */
static int open_device(diag_t *d)
{
    struct stat st;

    g_dev_is_socket = 0;
    if (stat(g_dev, &st) == 0 && S_ISSOCK(st.st_mode)) {
        int fd = socket(AF_UNIX, SOCK_SEQPACKET, 0);
        if (fd < 0) { seterr(d, "socket(AF_UNIX): %s", strerror(errno)); return -1; }

        struct sockaddr_un sa;
        memset(&sa, 0, sizeof sa);
        sa.sun_family = AF_UNIX;
        if (strlen(g_dev) >= sizeof sa.sun_path) {
            seterr(d, "socket path is too long (max %zu)", sizeof sa.sun_path - 1);
            close(fd);
            return -1;
        }
        memcpy(sa.sun_path, g_dev, strlen(g_dev));

        if (connect(fd, (struct sockaddr *)&sa, sizeof sa) < 0) {
            seterr(d, "connect %s: %s", g_dev, strerror(errno));
            close(fd);
            return -1;
        }
        g_dev_is_socket = 1;
        d->transport = DIAG_TP_MOCK;
        snprintf(d->desc, sizeof d->desc, "%.60s", g_dev);
        qlog("using the unix socket %s as the DIAG transport", g_dev);
        return fd;
    }

    int fd = open(g_dev, O_RDWR | O_LARGEFILE);
    if (fd >= 0) {
        d->transport = DIAG_TP_CHARDEV;
        snprintf(d->desc, sizeof d->desc, "%.60s", g_dev);
        return fd;
    }

    /* No diagchar driver: newer targets route DIAG over QRTR instead. */
    int chardev_errno = errno;
    qlog("open %s failed (%s); trying QRTR", g_dev, strerror(chardev_errno));

    fd = open_qrtr(d);
    if (fd < 0) {
        char why[160];
        snprintf(why, sizeof why, "%.150s", d->last_error);
        seterr(d, "open %.80s failed: %.40s; %.120s",
               g_dev, strerror(chardev_errno), why);
    }
    return fd;
}

static uint32_t query_peripheral_mask(int fd)
{
    struct con_all_param p;
    memset(&p, 0, sizeof p);
    p.diag_con_all = 0xFF;
    if (ioctl(fd, DIAG_IOCTL_QUERY_CON_ALL, &p) == 0 && p.diag_con_all)
        return p.diag_con_all;
    return DIAG_CON_ALL;
}

static int switch_logging(diag_t *d)
{
    int fd = d->fd;
    long arglen = probe_ioctl_arglen(fd, DIAG_IOCTL_SWITCH_LOGGING, sizeof(struct logmode_q));

    qlog("SWITCH_LOGGING probed arglen=%ld", arglen);
    d->peripheral_mask = query_peripheral_mask(fd);

    /* Try the widest struct first: a kernel expecting a shorter one simply
     * ignores the tail, and the leading fields line up across versions. */
    if (arglen < 0 || (size_t)arglen >= sizeof(struct logmode_q)) {
        struct logmode_q m;
        memset(&m, 0, sizeof m);
        m.req_mode = MEMORY_DEVICE_MODE;
        m.peripheral_mask = d->peripheral_mask;
        m.mode_param = 1;
        m.device_mask = 1;              /* 1 << DIAG_MD_LOCAL */
        if (ioctl(fd, DIAG_IOCTL_SWITCH_LOGGING, &m) >= 0) { d->logging_variant = 24; return 0; }
    }
    if (arglen < 0 || (size_t)arglen >= sizeof(struct logmode_pie)) {
        struct logmode_pie m;
        memset(&m, 0, sizeof m);
        m.req_mode = MEMORY_DEVICE_MODE;
        m.peripheral_mask = d->peripheral_mask;
        if (ioctl(fd, DIAG_IOCTL_SWITCH_LOGGING, &m) >= 0) { d->logging_variant = 20; return 0; }
    }
    if (arglen < 0 || (size_t)arglen >= sizeof(struct logmode_legacy)) {
        struct logmode_legacy m;
        memset(&m, 0, sizeof m);
        m.req_mode = MEMORY_DEVICE_MODE;
        m.peripheral_mask = d->peripheral_mask;
        if (ioctl(fd, DIAG_IOCTL_SWITCH_LOGGING, &m) >= 0) { d->logging_variant = 9; return 0; }
    }
    {
        int mode = MEMORY_DEVICE_MODE;
        if (ioctl(fd, DIAG_IOCTL_SWITCH_LOGGING, &mode) >= 0) { d->logging_variant = 4; return 0; }
        if (ioctl(fd, DIAG_IOCTL_SWITCH_LOGGING, (long)MEMORY_DEVICE_MODE) >= 0) {
            d->logging_variant = 0;
            return 0;
        }
    }

    seterr(d, "DIAG_IOCTL_SWITCH_LOGGING failed: %s", strerror(errno));
    return -1;
}

int diag_open(diag_t *d, int use_mdm)
{
    memset(d, 0, sizeof *d);
    d->fd = -1;
    d->use_mdm = use_mdm ? 1 : 0;

    int fd = open_device(d);
    if (fd < 0) return -1;
    d->fd = fd;

    /* Only the character device has a logging mode to switch. */
    if (d->transport != DIAG_TP_CHARDEV) return 0;

    if (switch_logging(d) < 0) {
        /* Some kernels still deliver responses without the mode switch; keep
         * the descriptor and let the caller decide after the first exchange. */
        qlog("continuing without a logging-mode switch: %s", d->last_error);
    }

    struct buffering_mode bm = { 0, DIAG_BUFFERING_MODE_STREAMING,
                                 DEFAULT_HIGH_WM_VAL, DEFAULT_LOW_WM_VAL };
    if (ioctl(fd, DIAG_IOCTL_PERIPHERAL_BUF_CONFIG, &bm) < 0)
        qlog("PERIPHERAL_BUF_CONFIG not available (%s)", strerror(errno));

    uint16_t remote = 0;
    if (ioctl(fd, DIAG_IOCTL_REMOTE_DEV, &remote) >= 0)
        qlog("remote dev mask = 0x%04x", remote);

    return 0;
}

void diag_close(diag_t *d)
{
    if (d->fd >= 0) close(d->fd);
    d->fd = -1;
}

/* ---- transfers -------------------------------------------------------- */

int diag_send(diag_t *d, const uint8_t *req, size_t len)
{
    uint8_t buf[8 + 2 * DIAG_MAX_PKT + 8];
    size_t off = 0;

    if (d->transport == DIAG_TP_QRTR) {
        /* One datagram per request, and no framing: the modem parses the
         * payload as-is and answers with its own non-HDLC wrapper. */
        struct sockaddr_qrtr to = { AF_QIPCRTR, d->qrtr_node, d->qrtr_port };
        if (sendto(d->fd, req, len, 0, (struct sockaddr *)&to, sizeof to) < 0) {
            seterr(d, "sendto %s failed: %s", d->desc, strerror(errno));
            return -1;
        }
        return 0;
    }

    uint32_t type = USER_SPACE_DATA_TYPE;
    memcpy(buf, &type, 4);
    off = 4;
    if (d->use_mdm) {
        int32_t token = -1;
        memcpy(buf + off, &token, 4);
        off += 4;
    }

    int n = hdlc_encode(req, len, buf + off, sizeof buf - off);
    if (n < 0) { seterr(d, "request too large (%zu bytes)", len); return -1; }

    ssize_t w = write(d->fd, buf, off + (size_t)n);
    if (w < 0) {
        seterr(d, "write to %s failed: %s", g_dev, strerror(errno));
        return -1;
    }
    return 0;
}

static int64_t now_ms(void)
{
    struct timeval tv;
    gettimeofday(&tv, NULL);
    return (int64_t)tv.tv_sec * 1000 + tv.tv_usec / 1000;
}

static int matches(const uint8_t *p, int len, int want_cmd, int want_subsys, int want_subcmd)
{
    if (len < 1) return 0;
    if (want_cmd >= 0 && p[0] != (uint8_t)want_cmd) {
        /* A target that dislikes the request answers with an error code and
         * echoes the request; treat that as the answer. */
        return (p[0] >= 0x13 && p[0] <= 0x18);
    }
    if (want_subsys >= 0) {
        if (len < 4) return 0;
        if (p[1] != (uint8_t)want_subsys) return 0;
        if (want_subcmd >= 0 && (p[2] | (p[3] << 8)) != want_subcmd) return 0;
    }
    return 1;
}

int diag_recv(diag_t *d, uint8_t *out, size_t outsz, int timeout_ms,
              int want_cmd, int want_subsys, int want_subcmd)
{
    static uint8_t rx[512 * 1024];
    int64_t deadline = now_ms() + timeout_ms;

    for (;;) {
        int left = (int)(deadline - now_ms());
        if (left <= 0) { seterr(d, "timed out waiting for a DIAG response"); return -1; }

        struct pollfd pfd = { d->fd, POLLIN, 0 };
        int pr = poll(&pfd, 1, left);
        if (pr < 0) {
            if (errno == EINTR) continue;
            seterr(d, "poll failed: %s", strerror(errno));
            return -1;
        }
        if (pr == 0) { seterr(d, "timed out waiting for a DIAG response"); return -1; }

        if (d->transport == DIAG_TP_QRTR) {
            ssize_t n = recv(d->fd, rx, sizeof rx, 0);
            if (n <= 0) continue;
            d->rx_frames++;

            /* The answer is wrapped as 7E 01 <len16> <payload> 7E.  Some
             * packets arrive raw, so fall back to using them whole. */
            const uint8_t *p = rx;
            size_t plen = (size_t)n;
            if (n >= 5 && rx[0] == 0x7E && rx[1] == 0x01) {
                size_t declared = (size_t)rx[2] | ((size_t)rx[3] << 8);
                if (declared && 4 + declared <= (size_t)n) {
                    p = rx + 4;
                    plen = declared;
                } else {
                    d->rx_dropped++;
                    continue;
                }
            }
            if (plen > outsz) plen = outsz;
            memcpy(out, p, plen);

            if (matches(out, (int)plen, want_cmd, want_subsys, want_subcmd))
                return (int)plen;

            d->rx_dropped++;
            qlog("dropping unsolicited packet cmd=0x%02x len=%zu", out[0], plen);
            continue;
        }

        ssize_t n = read(d->fd, rx, sizeof rx);
        if (n < 4) continue;

        uint32_t type = 0, nmsg = 0;
        memcpy(&type, rx, 4);
        if (type != USER_SPACE_DATA_TYPE) continue;
        memcpy(&nmsg, rx + 4, 4);
        size_t off = 8;

        for (uint32_t i = 0; i < nmsg && off + 4 <= (size_t)n; i++) {
            if (d->use_mdm && off + 4 <= (size_t)n) {
                int32_t tok;
                memcpy(&tok, rx + off, 4);
                if (tok == -1) off += 4;
            }
            if (off + 4 > (size_t)n) break;

            uint32_t mlen = 0;
            memcpy(&mlen, rx + off, 4);
            off += 4;
            if (mlen == 0 || off + mlen > (size_t)n) break;

            d->rx_frames++;
            int plen = hdlc_decode(rx + off, mlen, out, outsz);
            off += mlen;

            if (plen == -2) { d->crc_errors++; continue; }
            if (plen < 0) { d->rx_dropped++; continue; }

            if (matches(out, plen, want_cmd, want_subsys, want_subcmd))
                return plen;

            d->rx_dropped++;
            qlog("dropping unsolicited packet cmd=0x%02x len=%d", out[0], plen);
        }
    }
}

int diag_xfer(diag_t *d, const uint8_t *req, size_t reqlen,
              uint8_t *out, size_t outsz, int timeout_ms)
{
    if (diag_send(d, req, reqlen) < 0) return -1;

    int want_subsys = -1, want_subcmd = -1;
    if (reqlen >= 4 && req[0] == 0x4B) {
        want_subsys = req[1];
        want_subcmd = req[2] | (req[3] << 8);
    }
    int n = diag_recv(d, out, outsz, timeout_ms, req[0], want_subsys, want_subcmd);
    if (n < 0) return -1;

    /* The target answers a command it dislikes with an error code plus an echo
     * of the request; that is a valid frame but not an answer. */
    if (n >= 1 && out[0] != req[0]) {
        static const char *why[] = {
            "bad command", "bad parameter", "bad length", "bad device",
            "bad mode", "bad SPC mode",
        };
        int idx = out[0] - 0x13;
        seterr(d, "the target rejected the command: %s (0x%02x)",
               (idx >= 0 && idx < 6) ? why[idx] : "unknown error", out[0]);
        return -1;
    }
    return n;
}
