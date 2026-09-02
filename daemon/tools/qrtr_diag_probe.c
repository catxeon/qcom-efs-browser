/* qrtr_diag_probe -- tries to hold a DIAG conversation over QRTR.
 *
 * On targets without a diagchar driver the modem publishes DIAG as QRTR
 * service 4097.  This sends a few harmless read-only requests in several
 * candidate framings and prints whatever comes back, so we can work out which
 * framing the modem expects.
 *
 *   qrtr_diag_probe <node> <port>
 */
#define _GNU_SOURCE

#include <errno.h>
#include <poll.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

#define AF_QIPCRTR 42

struct sockaddr_qrtr {
    unsigned short sq_family;
    uint32_t sq_node;
    uint32_t sq_port;
};

static uint16_t crc16(const uint8_t *d, size_t n)
{
    uint16_t crc = 0xFFFF;
    for (size_t i = 0; i < n; i++) {
        crc ^= d[i];
        for (int b = 0; b < 8; b++)
            crc = (crc & 1) ? (uint16_t)((crc >> 1) ^ 0x8408) : (uint16_t)(crc >> 1);
    }
    return crc;
}

static size_t hdlc(const uint8_t *in, size_t n, uint8_t *out)
{
    uint16_t c = crc16(in, n) ^ 0xFFFF;
    uint8_t tail[2] = { (uint8_t)(c & 0xFF), (uint8_t)(c >> 8) };
    size_t o = 0;
    for (size_t i = 0; i < n + 2; i++) {
        uint8_t b = (i < n) ? in[i] : tail[i - n];
        if (b == 0x7E || b == 0x7D) { out[o++] = 0x7D; out[o++] = b ^ 0x20; }
        else out[o++] = b;
    }
    out[o++] = 0x7E;
    return o;
}

static void dump(const char *label, const uint8_t *d, size_t n)
{
    printf("    %s (%zu bytes): ", label, n);
    for (size_t i = 0; i < n && i < 96; i++) printf("%02x", d[i]);
    if (n > 96) printf("...");
    printf("\n");
    printf("    ascii: ");
    for (size_t i = 0; i < n && i < 96; i++)
        putchar((d[i] >= 32 && d[i] < 127) ? d[i] : '.');
    printf("\n");
}

static int try_send(int sock, uint32_t node, uint32_t port,
                    const char *label, const uint8_t *payload, size_t len)
{
    struct sockaddr_qrtr to = { AF_QIPCRTR, node, port };

    printf("--> %s\n", label);
    dump("sent", payload, len);

    if (sendto(sock, payload, len, 0, (struct sockaddr *)&to, sizeof to) < 0) {
        printf("    sendto failed: %s\n\n", strerror(errno));
        return 0;
    }

    int got = 0;
    for (;;) {
        struct pollfd p = { sock, POLLIN, 0 };
        if (poll(&p, 1, 1500) <= 0) break;

        uint8_t buf[8192];
        struct sockaddr_qrtr from;
        socklen_t fl = sizeof from;
        ssize_t n = recvfrom(sock, buf, sizeof buf, 0, (struct sockaddr *)&from, &fl);
        if (n < 0) break;
        printf("    <-- from node %u port %u\n", from.sq_node, from.sq_port);
        dump("recv", buf, (size_t)n);
        got++;
    }
    if (!got) printf("    (no answer)\n");
    printf("\n");
    return got;
}

int main(int argc, char **argv)
{
    uint32_t node = (argc > 1) ? (uint32_t)strtoul(argv[1], NULL, 0) : 0;
    uint32_t port = (argc > 2) ? (uint32_t)strtoul(argv[2], NULL, 0) : 26;

    int sock = socket(AF_QIPCRTR, SOCK_DGRAM, 0);
    if (sock < 0) { printf("socket: %s\n", strerror(errno)); return 1; }

    struct sockaddr_qrtr me;
    socklen_t l = sizeof me;
    memset(&me, 0, sizeof me);
    getsockname(sock, (struct sockaddr *)&me, &l);
    printf("local node %u port %u -> talking to node %u port %u\n\n",
           me.sq_node, me.sq_port, node, port);

    uint8_t buf[256];

    /* 1. DIAG_VERNO_F, raw */
    uint8_t verno[] = { 0x00 };
    try_send(sock, node, port, "DIAG_VERNO_F raw", verno, sizeof verno);

    /* 2. DIAG_VERNO_F, HDLC framed */
    size_t n = hdlc(verno, sizeof verno, buf);
    try_send(sock, node, port, "DIAG_VERNO_F HDLC", buf, n);

    /* 3. non-HDLC framing: 7e 01 <len16> payload 7e */
    buf[0] = 0x7E; buf[1] = 0x01; buf[2] = 1; buf[3] = 0; buf[4] = 0x00; buf[5] = 0x7E;
    try_send(sock, node, port, "DIAG_VERNO_F non-HDLC", buf, 6);

    /* 4. DIAG_EXT_BUILD_ID_F, raw */
    uint8_t build[] = { 0x7C };
    try_send(sock, node, port, "DIAG_EXT_BUILD_ID_F raw", build, sizeof build);

    /* 5. EFS2 hello on the standard subsystem, raw */
    uint8_t hello[4 + 0x28];
    memset(hello, 0, sizeof hello);
    hello[0] = 0x4B; hello[1] = 0x13;
    for (int i = 0; i < 6; i++) { hello[4 + i * 4] = 0x00; hello[4 + i * 4 + 2] = 0x10; }
    hello[28] = 1; hello[32] = 1; hello[36] = 1;
    memset(hello + 40, 0xFF, 4);
    try_send(sock, node, port, "EFS2 HELLO subsys 0x13 raw", hello, sizeof hello);

    /* 6. same, HDLC framed */
    n = hdlc(hello, sizeof hello, buf);
    try_send(sock, node, port, "EFS2 HELLO subsys 0x13 HDLC", buf, n);

    close(sock);
    return 0;
}
