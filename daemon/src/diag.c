#define _GNU_SOURCE

/* diag.c -- DIAG transport over QRTR.
 *
 * The DIAG packet layer follows qfenix (BSD-3-Clause) and libopenpst; the
 * QRTR framing is what an SM8350 and an SM8850 modem actually put on the bus.
 */
#include "diag.h"
#include "util.h"

#include <errno.h>
#include <poll.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <sys/un.h>
#include <unistd.h>

const char *diag_error(const diag_t *d) { return d->last_error; }

static void seterr(diag_t *d, const char *fmt, ...)
{
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(d->last_error, sizeof d->last_error, fmt, ap);
    va_end(ap);
}

static char g_mock[512];

void diag_set_mock(const char *path) { snprintf(g_mock, sizeof g_mock, "%s", path); }

const char *diag_transport_desc(const diag_t *d) { return d->desc[0] ? d->desc : "qrtr"; }

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

/* ---- the bus scan, for the verbose log --------------------------------- */

/* A QRTR service id is the QMI service id, so the well-known ones have
 * names.  Only the ones that are certain are listed; the rest print as
 * bare numbers rather than as a guess. */
static const struct { uint32_t id; const char *name; } k_services[] = {
    {  1, "WDS" },   {  2, "DMS" },   {  3, "NAS" },   {  4, "QOS" },
    {  5, "WMS" },   {  9, "VOICE" }, { 11, "UIM" },   { 12, "PBM" },
    { 14, "RMTFS" }, { 16, "LOC" },   { 17, "SAR" },   { 26, "WDA" },
    { 36, "PDC" },   { 42, "DSD" },   { 43, "SSCTL" },
    { 64, "SERVREG_LOC" }, { 66, "SERVREG_NOTIF" }, { 69, "WLFW" },
    { 4096, "TFTP" }, { QRTR_DIAG_SERVICE, "DIAG" },
};

static const char *service_name(uint32_t id)
{
    for (size_t i = 0; i < sizeof k_services / sizeof k_services[0]; i++)
        if (k_services[i].id == id) return k_services[i].name;
    return NULL;
}

struct qrtr_svc { uint32_t node, service, instance, port; };

static int cmp_svc(const void *a, const void *b)
{
    const struct qrtr_svc *x = a, *y = b;
    if (x->node != y->node) return x->node < y->node ? -1 : 1;
    if (x->service != y->service) return x->service < y->service ? -1 : 1;
    if (x->instance != y->instance) return x->instance < y->instance ? -1 : 1;
    return 0;
}

#define SCAN_MAX 512

/* Lists everything on the QRTR bus into the log, one line per node, and ends
 * with a verdict on DIAG.  When a custom kernel or firmware leaves the helper
 * with nothing to talk to, this is what shows it -- "the modem is up but does
 * not publish DIAG" and "no remote processor is on the bus at all" read very
 * differently from a bug in the app.
 *
 * It uses a socket of its own: a lookup subscribes the socket to every later
 * announcement, and those must never land in the DIAG receive path. */
static void qrtr_scan_bus(void)
{
    int sock = socket(AF_QIPCRTR, SOCK_DGRAM, 0);
    if (sock < 0) return;              /* open_qrtr reports this itself */

    struct sockaddr_qrtr me;
    socklen_t l = sizeof me;
    memset(&me, 0, sizeof me);
    if (getsockname(sock, (struct sockaddr *)&me, &l) < 0) { close(sock); return; }

    /* Service 0 is the wildcard: the name service answers with every server
     * it knows, then an all-zero entry to mark the end. */
    struct qrtr_ctrl_pkt pkt;
    memset(&pkt, 0, sizeof pkt);
    pkt.cmd = QRTR_TYPE_NEW_LOOKUP;
    struct sockaddr_qrtr ctrl = { AF_QIPCRTR, me.sq_node, QRTR_PORT_CTRL };
    if (sendto(sock, &pkt, sizeof pkt, 0, (struct sockaddr *)&ctrl, sizeof ctrl) < 0) {
        qlog("QRTR bus scan: the lookup could not be sent: %s", strerror(errno));
        close(sock);
        return;
    }

    static struct qrtr_svc svc[SCAN_MAX];
    size_t n = 0, dropped = 0;
    for (;;) {
        struct pollfd p = { sock, POLLIN, 0 };
        if (poll(&p, 1, 1500) <= 0) break;
        struct qrtr_ctrl_pkt in;
        ssize_t r = recv(sock, &in, sizeof in, 0);
        if (r < (ssize_t)sizeof in || in.cmd != QRTR_TYPE_NEW_SERVER) continue;
        if (!in.service && !in.instance && !in.node && !in.port) break;   /* end */
        if (n < SCAN_MAX) svc[n++] = (struct qrtr_svc){ in.node, in.service, in.instance, in.port };
        else dropped++;
    }
    close(sock);

    if (n == 0) {
        qlog("QRTR bus scan: the bus is empty -- no processor has registered a "
             "single service, so there is nothing for the helper to reach");
        return;
    }
    qsort(svc, n, sizeof svc[0], cmp_svc);

    size_t nodes = 0;
    qlog("QRTR bus scan (this CPU is node %u):", me.sq_node);
    for (size_t i = 0; i < n; ) {
        uint32_t node = svc[i].node;
        size_t j = i, count = 0;
        char line[1024];
        int len = 0;
        while (j < n && svc[j].node == node) {
            /* One entry per service id, with a count when it is published
             * more than once (per SIM, per instance). */
            size_t k = j;
            while (k < n && svc[k].node == node && svc[k].service == svc[j].service) k++;
            const char *name = service_name(svc[j].service);
            if (len < (int)sizeof line - 48) {
                len += snprintf(line + len, sizeof line - len, "%s%u%s%s", count ? ", " : "",
                                svc[j].service, name ? " " : "", name ? name : "");
                if (k - j > 1) len += snprintf(line + len, sizeof line - len, " x%zu", k - j);
            }
            count++;
            j = k;
        }
        qlog("  node %u%s: %zu service%s - %s", node, node == me.sq_node ? " (this CPU)" : "",
             count, count == 1 ? "" : "s", line);
        nodes++;
        i = j;
    }
    if (dropped) qlog("  ... and %zu more not shown", dropped);

    /* The verdict.  The modem is whichever other node publishes DIAG. */
    int remote = 0, local = 0;
    for (size_t i = 0; i < n; i++) {
        if (svc[i].service != QRTR_DIAG_SERVICE) continue;
        if (svc[i].node == me.sq_node) local++; else remote++;
    }
    if (remote)
        qlog("QRTR bus scan: %zu services on %zu nodes; DIAG (%u) is published by another "
             "node, so the helper has a modem to talk to", n, nodes, QRTR_DIAG_SERVICE);
    else if (local)
        qlog("QRTR bus scan: %zu services on %zu nodes; DIAG (%u) is only published by this "
             "CPU, not by the modem -- nothing for the helper to talk to", n, nodes, QRTR_DIAG_SERVICE);
    else
        qlog("QRTR bus scan: %zu services on %zu nodes, but no DIAG (%u) anywhere -- this "
             "firmware/kernel does not expose the modem's DIAG over QRTR, so there is nothing "
             "for the helper to talk to", n, nodes, QRTR_DIAG_SERVICE);
}

/* Enumerates the QRTR bus and picks the DIAG service that lives on a node
 * other than ours -- that is the modem.  Instance 1 is the command endpoint
 * of the modem on every target seen so far; anything else is a fallback. */
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
        int e = errno;
        if (e == EAFNOSUPPORT)
            seterr(d, "this kernel has no QRTR support (socket(AF_QIPCRTR): %s), "
                      "so there is no bus to reach the modem through", strerror(e));
        else
            seterr(d, "socket(AF_QIPCRTR) failed: %s", strerror(e));
        return -1;
    }

    /* Once per run: every reconnect after a modem restart would repeat it. */
    static int scanned;
    if (g_verbose && !scanned) {
        scanned = 1;
        qrtr_scan_bus();
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

/* ---- the test rig ----------------------------------------------------- */

static int open_mock(diag_t *d)
{
    int fd = socket(AF_UNIX, SOCK_SEQPACKET, 0);
    if (fd < 0) { seterr(d, "socket(AF_UNIX): %s", strerror(errno)); return -1; }

    struct sockaddr_un sa;
    memset(&sa, 0, sizeof sa);
    sa.sun_family = AF_UNIX;
    if (strlen(g_mock) >= sizeof sa.sun_path) {
        seterr(d, "socket path is too long (max %zu)", sizeof sa.sun_path - 1);
        close(fd);
        return -1;
    }
    memcpy(sa.sun_path, g_mock, strlen(g_mock));

    if (connect(fd, (struct sockaddr *)&sa, sizeof sa) < 0) {
        seterr(d, "connect %s: %s", g_mock, strerror(errno));
        close(fd);
        return -1;
    }
    d->transport = DIAG_TP_MOCK;
    snprintf(d->desc, sizeof d->desc, "%.60s", g_mock);
    qlog("using the unix socket %s as the DIAG transport", g_mock);
    return fd;
}

int diag_open(diag_t *d)
{
    memset(d, 0, sizeof *d);
    d->fd = -1;

    int fd = g_mock[0] ? open_mock(d) : open_qrtr(d);
    if (fd < 0) return -1;
    d->fd = fd;
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
    ssize_t w;

    /* One datagram per request, and no framing: the modem parses the payload
     * as-is and answers with its own wrapper. */
    if (d->transport == DIAG_TP_QRTR) {
        struct sockaddr_qrtr to = { AF_QIPCRTR, d->qrtr_node, d->qrtr_port };
        w = sendto(d->fd, req, len, 0, (struct sockaddr *)&to, sizeof to);
    } else {
        w = send(d->fd, req, len, 0);
    }
    if (w < 0) {
        seterr(d, "sending to %s failed: %s", diag_transport_desc(d), strerror(errno));
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
        if (p[0] >= 0x13 && p[0] <= 0x18) return 1;
        /* A command the security state forbids comes back as 0x42 with the
         * refused command code right behind it -- that is our answer too,
         * and without this the caller would sit there until it timed out. */
        if (p[0] == DIAG_BAD_SEC_MODE_F && len >= 2 && p[1] == (uint8_t)want_cmd) return 1;
        return 0;
    }
    if (want_subsys >= 0) {
        if (len < 4) return 0;
        if (p[1] != (uint8_t)want_subsys) return 0;
        if (want_subcmd >= 0 && (p[2] | (p[3] << 8)) != want_subcmd) return 0;
    }
    return 1;
}

/* Peels the wrappers off a received datagram.  Returns the payload length, or
 * 0 for a datagram that claims a length it does not carry. */
static size_t unwrap(const uint8_t **pp, size_t plen)
{
    const uint8_t *p = *pp;

    /* Newer targets (SM8850 and up) put a 4-byte header in front of every
     * datagram: [u16 type = 8][u16 length of the rest].  SM8350 does not.
     * Strip it only when the length field agrees exactly and the DIAG wrapper
     * really follows, so a payload that happens to start with those bytes is
     * never mistaken for a header. */
    if (plen >= 8) {
        size_t declared = (size_t)p[2] | ((size_t)p[3] << 8);
        if (declared + 4 == plen && p[4] == 0x7E) {
            p += 4;
            plen -= 4;
        }
    }

    /* Then the wrapper itself: 7E 01 <len16> <payload> 7E.  A packet that
     * arrives raw is used whole. */
    if (plen >= 5 && p[0] == 0x7E && p[1] == 0x01) {
        size_t declared = (size_t)p[2] | ((size_t)p[3] << 8);
        if (declared == 0 || declared + 4 > plen) return 0;
        p += 4;
        plen = declared;
    }

    *pp = p;
    return plen;
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

        ssize_t n = recv(d->fd, rx, sizeof rx, 0);
        if (n <= 0) continue;
        d->rx_frames++;

        const uint8_t *p = rx;
        size_t plen = unwrap(&p, (size_t)n);
        if (plen == 0) { d->rx_dropped++; continue; }

        if (plen > outsz) plen = outsz;
        memcpy(out, p, plen);

        if (matches(out, (int)plen, want_cmd, want_subsys, want_subcmd))
            return (int)plen;

        d->rx_dropped++;
        qlog("dropping unsolicited packet cmd=0x%02x len=%zu", out[0], plen);
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
        const char *reason = (idx >= 0 && idx < 6) ? why[idx] : "unknown error";
        if (out[0] == DIAG_BAD_SEC_MODE_F)
            reason = "the security state of the target does not allow it";
        seterr(d, "the target rejected the command: %s (0x%02x)", reason, out[0]);
        return -1;
    }
    return n;
}
