/* shell_access_probe -- can a shell-uid process reach the modem's DIAG?
 *
 * Shizuku hands a helper uid 2000 in the u:r:shell:s0 SELinux domain, which is
 * a different world from root: before the app grows a Shizuku mode we need to
 * know whether that domain is allowed near DIAG at all.  Run this through
 * `adb shell` WITHOUT su -- that is exactly the uid and the context Shizuku
 * would give the helper.
 *
 * Nothing here changes the device.  The QRTR lookup only reads what the name
 * service already publishes, /dev/diag is opened and asked which remote
 * processors are attached, and the memory-device logging switch that a real
 * session needs is deliberately NOT attempted: that one changes DIAG logging
 * for the whole system and would disturb anything else using it.
 *
 * Build with the same toolchain as daemon/build.sh:
 *   "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/clang" \
 *       --target=aarch64-linux-android26 -Os -Wall -Wextra \
 *       -o shell_access_probe shell_access_probe.c
 */
#define _GNU_SOURCE

#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <unistd.h>

#define AF_QIPCRTR            42
#define QRTR_PORT_CTRL        0xfffffffeu
#define QRTR_TYPE_NEW_SERVER  4
#define QRTR_TYPE_NEW_LOOKUP  10
#define QRTR_DIAG_SERVICE     4097
#define SCAN_MAX              512

/* The same constants the helper uses; see daemon/src/diag.c. */
#define DIAG_DEV_PATH         "/dev/diag"
#define DIAG_IOCTL_REMOTE_DEV 32

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

struct svc { uint32_t node, service, instance, port; };

/* Collected as we go and printed as one block at the end, so the lines that
 * matter are not buried in the middle of the report. */
static char g_verdict[8][256];
static int  g_verdicts;

static void verdict(const char *fmt, ...)
{
    if (g_verdicts >= (int)(sizeof g_verdict / sizeof g_verdict[0])) return;
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(g_verdict[g_verdicts], sizeof g_verdict[0], fmt, ap);
    va_end(ap);
    g_verdicts++;
}

static void read_small(const char *path, char *out, size_t outsz)
{
    out[0] = 0;
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        snprintf(out, outsz, "<%s>", strerror(errno));
        return;
    }
    ssize_t n = read(fd, out, outsz - 1);
    close(fd);
    if (n < 0) { snprintf(out, outsz, "<%s>", strerror(errno)); return; }
    out[n] = 0;
    for (char *p = out; *p; p++) if (*p == '\n' || *p == '\r') *p = 0;
}

/* ---- who am I --------------------------------------------------------- */

static void section_identity(void)
{
    char ctx[256], enforce[32];

    printf("## identity\n");
    printf("uid=%u euid=%u gid=%u egid=%u\n",
           (unsigned)getuid(), (unsigned)geteuid(),
           (unsigned)getgid(), (unsigned)getegid());

    gid_t gl[64];
    int ng = getgroups((int)(sizeof gl / sizeof gl[0]), gl);
    printf("groups=");
    for (int i = 0; i < ng; i++) printf("%s%u", i ? "," : "", (unsigned)gl[i]);
    if (ng <= 0) printf("(none)");
    printf("\n");

    read_small("/proc/self/attr/current", ctx, sizeof ctx);
    read_small("/sys/fs/selinux/enforce", enforce, sizeof enforce);
    printf("selinux_context=%s\n", ctx[0] ? ctx : "(unknown)");
    printf("selinux_enforce=%s\n", enforce[0] ? enforce : "(unreadable)");

    if (getuid() == 0)
        verdict("WARNING uid is 0 -- this ran as root, so it says nothing about "
                "what Shizuku would be allowed to do");
    printf("\n");
}

/* ---- QRTR ------------------------------------------------------------- */

static int cmp_svc(const void *a, const void *b)
{
    const struct svc *x = a, *y = b;
    if (x->node != y->node)         return x->node < y->node ? -1 : 1;
    if (x->service != y->service)   return x->service < y->service ? -1 : 1;
    if (x->instance != y->instance) return x->instance < y->instance ? -1 : 1;
    return 0;
}

static void section_qrtr(void)
{
    printf("## qrtr (AF_QIPCRTR; DIAG is service %u)\n", QRTR_DIAG_SERVICE);

    int sock = socket(AF_QIPCRTR, SOCK_DGRAM, 0);
    if (sock < 0) {
        int e = errno;
        printf("socket(AF_QIPCRTR, SOCK_DGRAM): %s (errno %d)\n", strerror(e), e);
        printf("  EPERM or EACCES here means the policy does not let this domain\n"
               "  create a QRTR socket at all; EAFNOSUPPORT means the kernel has no QRTR.\n");
        verdict("qrtr_socket=denied (%s)", strerror(e));
        printf("\n");
        return;
    }

    struct sockaddr_qrtr me;
    socklen_t l = sizeof me;
    memset(&me, 0, sizeof me);
    if (getsockname(sock, (struct sockaddr *)&me, &l) < 0) {
        printf("getsockname: %s\n", strerror(errno));
        verdict("qrtr_socket=open, but the local node is unknown");
        close(sock);
        printf("\n");
        return;
    }
    printf("socket created; this CPU is node %u, local port %u\n", me.sq_node, me.sq_port);
    verdict("qrtr_socket=ok (node %u)", me.sq_node);

    /* Service 0 is the wildcard: the name service replays every server it
     * knows and ends with an all-zero entry. */
    struct qrtr_ctrl_pkt pkt;
    memset(&pkt, 0, sizeof pkt);
    pkt.cmd = QRTR_TYPE_NEW_LOOKUP;
    struct sockaddr_qrtr ctrl = { AF_QIPCRTR, me.sq_node, QRTR_PORT_CTRL };
    if (sendto(sock, &pkt, sizeof pkt, 0, (struct sockaddr *)&ctrl, sizeof ctrl) < 0) {
        int e = errno;
        printf("the lookup could not be sent to the name service: %s (errno %d)\n",
               strerror(e), e);
        verdict("qrtr_lookup=denied (%s)", strerror(e));
        close(sock);
        printf("\n");
        return;
    }

    static struct svc svc[SCAN_MAX];
    size_t n = 0, dropped = 0;
    for (;;) {
        struct pollfd p = { sock, POLLIN, 0 };
        if (poll(&p, 1, 1500) <= 0) break;
        struct qrtr_ctrl_pkt in;
        ssize_t r = recv(sock, &in, sizeof in, 0);
        if (r < (ssize_t)sizeof in || in.cmd != QRTR_TYPE_NEW_SERVER) continue;
        if (!in.service && !in.instance && !in.node && !in.port) break;
        if (n < SCAN_MAX) {
            svc[n].node = in.node;
            svc[n].service = in.service;
            svc[n].instance = in.instance;
            svc[n].port = in.port;
            n++;
        } else dropped++;
    }
    close(sock);

    printf("the name service announced %zu service%s%s\n",
           n, n == 1 ? "" : "s", dropped ? " (list truncated)" : "");
    if (n == 0) {
        printf("  a socket that opens onto an empty bus means nobody answered the\n"
               "  lookup: no processor has registered a single service.\n");
        verdict("qrtr_diag=absent (the bus is empty)");
        printf("\n");
        return;
    }

    qsort(svc, n, sizeof svc[0], cmp_svc);

    int modem = 0, dsp = 0, local = 0;
    printf("DIAG endpoints:\n");
    for (size_t i = 0; i < n; i++) {
        if (svc[i].service != QRTR_DIAG_SERVICE) continue;
        const char *who;
        if (svc[i].node == me.sq_node)      { local++; who = "this CPU"; }
        else if (svc[i].instance / 64 == 0) { modem++; who = "the modem"; }
        else                                { dsp++;   who = "a DSP"; }
        printf("  node %u instance %u port %u -- %s\n",
               svc[i].node, svc[i].instance, svc[i].port, who);
    }
    if (!modem && !dsp && !local) printf("  (none)\n");

    if (modem)
        verdict("qrtr_diag=reachable -- the modem publishes DIAG and this uid can see it");
    else if (dsp)
        verdict("qrtr_diag=dsp_only -- DIAG comes from DSPs, so this target needs /dev/diag");
    else if (local)
        verdict("qrtr_diag=local_only -- only this CPU publishes DIAG, nothing to talk to");
    else
        verdict("qrtr_diag=absent -- %zu services on the bus, no DIAG anywhere", n);
    printf("\n");
}

/* ---- /dev/diag -------------------------------------------------------- */

static void section_chardev(void)
{
    printf("## %s (the path an external modem needs)\n", DIAG_DEV_PATH);

    struct stat st;
    if (stat(DIAG_DEV_PATH, &st) < 0) {
        int e = errno;
        printf("stat: %s (errno %d)\n", strerror(e), e);
        if (e == ENOENT)
            verdict("diag_node=absent (this kernel has no diagchar driver)");
        else
            verdict("diag_node=unstattable (%s) -- the directory itself may be closed",
                    strerror(e));
        printf("\n");
        return;
    }
    printf("mode=%04o owner=%u:%u\n", st.st_mode & 07777,
           (unsigned)st.st_uid, (unsigned)st.st_gid);

    int fd = open(DIAG_DEV_PATH, O_RDWR | O_CLOEXEC);
    if (fd < 0) {
        int e = errno;
        printf("open(O_RDWR): %s (errno %d)\n", strerror(e), e);

        int ro = open(DIAG_DEV_PATH, O_RDONLY | O_CLOEXEC);
        if (ro >= 0) {
            printf("open(O_RDONLY): ok -- reading is allowed and writing is not, "
                   "so a DIAG session is still impossible\n");
            close(ro);
            verdict("diag_node=read_only (%s on O_RDWR)", strerror(e));
        } else {
            printf("open(O_RDONLY): %s\n", strerror(errno));
            verdict("diag_node=denied (%s) -- the avc section says whether it was "
                    "DAC or policy", strerror(e));
        }
        printf("\n");
        return;
    }

    printf("open(O_RDWR): ok\n");
    uint16_t mask = 0;
    if (ioctl(fd, DIAG_IOCTL_REMOTE_DEV, &mask) < 0) {
        printf("ioctl(DIAG_IOCTL_REMOTE_DEV): %s\n", strerror(errno));
        verdict("diag_node=open_but_ioctl_denied (%s)", strerror(errno));
    } else {
        printf("remote processors mask=0x%04x%s\n", mask,
               (mask & 1) ? " -- an external modem is attached" : "");
        verdict("diag_node=usable (remote mask 0x%04x); the logging-mode switch was "
                "not attempted", mask);
    }
    close(fd);
    printf("note: the memory-device logging switch that a real session needs was NOT "
           "tried here, on purpose -- it changes DIAG logging system-wide.\n\n");
}

int main(void)
{
    printf("# shell_access_probe -- DIAG reachability for this uid\n\n");
    section_identity();
    section_qrtr();
    section_chardev();

    printf("## probe verdict\n");
    for (int i = 0; i < g_verdicts; i++) printf("VERDICT %s\n", g_verdict[i]);
    printf("\n");
    return 0;
}
