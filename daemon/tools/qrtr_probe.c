/* qrtr_probe -- lists the services published on the QRTR bus.
 *
 * Read-only reconnaissance: on targets without a diagchar driver the modem's
 * DIAG endpoint is reachable over QRTR instead of /dev/diag, and this says
 * whether such an endpoint exists and where it lives.
 *
 *   aarch64-linux-android26-clang -static -o qrtr_probe qrtr_probe.c
 */
#define _GNU_SOURCE

#include <errno.h>
#include <poll.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

#define AF_QIPCRTR       42
#define QRTR_PORT_CTRL   0xfffffffeu
#define QRTR_PORT_AUTO   0xffffffffu

#define QRTR_TYPE_HELLO       2
#define QRTR_TYPE_BYE         3
#define QRTR_TYPE_NEW_SERVER  4
#define QRTR_TYPE_DEL_SERVER  5
#define QRTR_TYPE_NEW_LOOKUP  10

struct sockaddr_qrtr {
    unsigned short sq_family;
    uint32_t sq_node;
    uint32_t sq_port;
};

struct qrtr_ctrl_pkt {
    uint32_t cmd;
    union {
        struct {
            uint32_t service;
            uint32_t instance;
            uint32_t node;
            uint32_t port;
        } server;
        struct {
            uint32_t node;
            uint32_t port;
        } client;
    };
} __attribute__((packed));

/* The handful of service numbers worth naming; everything else is printed raw. */
static const char *service_name(uint32_t s)
{
    switch (s) {
    case 1:  return "WDS";
    case 2:  return "DMS";
    case 3:  return "NAS";
    case 4:  return "QOS";
    case 5:  return "WMS";
    case 9:  return "VOICE";
    case 11: return "UIM";
    case 12: return "PBM";
    case 14: return "RMTFS";
    case 16: return "LOC";
    case 17: return "SAR";
    case 20: return "CSD";
    case 21: return "IMS";
    case 26: return "WDA";
    case 36: return "PDC";
    case 47: return "DIAG?";
    case 4097: return "DIAG (0x1001)";
    default: return "";
    }
}

/* Android 11 and older refuse a static executable whose TLS segment is
 * aligned to less than 64 bytes; the NDK emits 8.  One over-aligned thread
 * local in our own object raises the whole PT_TLS alignment. */
__thread char qefs_tls_align __attribute__((aligned(64), used));

int main(void)
{
    int sock = socket(AF_QIPCRTR, SOCK_DGRAM, 0);
    if (sock < 0) {
        printf("socket(AF_QIPCRTR) failed: %s\n", strerror(errno));
        return 1;
    }

    /* No explicit bind: the kernel auto-binds on the first send, and
     * getsockname already reports our node id. */
    struct sockaddr_qrtr me;
    socklen_t len = sizeof me;
    memset(&me, 0, sizeof me);
    if (getsockname(sock, (struct sockaddr *)&me, &len) < 0) {
        printf("getsockname failed: %s\n", strerror(errno));
        return 1;
    }
    printf("local node %u, port %u\n\n", me.sq_node, me.sq_port);

    struct qrtr_ctrl_pkt pkt;
    memset(&pkt, 0, sizeof pkt);
    pkt.cmd = QRTR_TYPE_NEW_LOOKUP;          /* service 0 = everything */

    struct sockaddr_qrtr ctrl = { AF_QIPCRTR, me.sq_node, QRTR_PORT_CTRL };
    if (sendto(sock, &pkt, sizeof pkt, 0, (struct sockaddr *)&ctrl, sizeof ctrl) < 0) {
        printf("lookup request failed: %s\n", strerror(errno));
        return 1;
    }

    printf("%-10s %-10s %-8s %-8s %s\n", "SERVICE", "INSTANCE", "NODE", "PORT", "NAME");

    int found = 0;
    for (;;) {
        struct pollfd p = { sock, POLLIN, 0 };
        if (poll(&p, 1, 2000) <= 0) break;

        struct qrtr_ctrl_pkt in;
        struct sockaddr_qrtr from;
        socklen_t fl = sizeof from;
        ssize_t n = recvfrom(sock, &in, sizeof in, 0, (struct sockaddr *)&from, &fl);
        if (n < 0) break;
        if ((size_t)n < sizeof in) continue;
        if (in.cmd != QRTR_TYPE_NEW_SERVER) continue;
        if (!in.server.service && !in.server.instance && !in.server.node && !in.server.port)
            break;                            /* end of the list */

        printf("%-10u %-10u %-8u %-8u %s\n",
               in.server.service, in.server.instance,
               in.server.node, in.server.port,
               service_name(in.server.service));
        found++;
    }

    printf("\n%d services\n", found);
    close(sock);
    return 0;
}
