/* diag.h -- Qualcomm DIAG transport over QRTR.
 *
 * Phones stopped shipping the diagchar driver (/dev/diag) years ago; on every
 * device this was tried on, the node is absent and the modem publishes DIAG as
 * QRTR service 4097 instead.  A request goes out as one raw datagram and the
 * answer comes back wrapped:
 *
 *   7E 01 <len16> <payload> 7E
 *
 * On SM8850 and newer there is a further datagram header in front of that:
 *
 *   [u16 type = 8][u16 length of the rest]
 */
#ifndef QEFS_DIAG_H
#define QEFS_DIAG_H

#include <stddef.h>
#include <stdint.h>

#define DIAG_MAX_PKT   16384

/* DIAG error responses that a target may return instead of a real answer.
 * The 0x13..0x18 block stands on its own; a security refusal instead echoes
 * the rejected command code in the byte after it. */
#define DIAG_BAD_CMD_F        0x13
#define DIAG_BAD_SPC_F        0x18
#define DIAG_BAD_SEC_MODE_F   0x42

enum {
    DIAG_TP_QRTR = 0,      /* AF_QIPCRTR, service 4097        */
    DIAG_TP_MOCK,          /* a unix socket, for the test rig */
};

#define QRTR_DIAG_SERVICE 4097

typedef struct {
    int      fd;
    int      transport;
    uint32_t qrtr_node, qrtr_port;
    char     desc[64];         /* human-readable transport name */
    unsigned long rx_frames;
    unsigned long rx_dropped;
    char     last_error[256];
} diag_t;

/* Talks to a unix SOCK_SEQPACKET socket instead of the QRTR bus, which is how
 * the test harness substitutes a mock modem.  Only the tests pass this. */
void diag_set_mock(const char *path);

const char *diag_transport_desc(const diag_t *d);

/* Pins the QRTR endpoint instead of discovering it (node:port). */
void diag_set_qrtr(uint32_t node, uint32_t port);

int  diag_open(diag_t *d);
void diag_close(diag_t *d);

/* One raw DIAG payload out. */
int  diag_send(diag_t *d, const uint8_t *req, size_t len);

/* Waits for a matching answer.  want_cmd is the expected first byte; when
 * want_subsys >= 0 the subsystem id (byte 1) and 16-bit subsystem command
 * (bytes 2-3) are matched too.  Unrelated log/event traffic is dropped.
 * Returns the unwrapped payload length, or -1 on timeout/error. */
int  diag_recv(diag_t *d, uint8_t *out, size_t outsz, int timeout_ms,
               int want_cmd, int want_subsys, int want_subcmd);

/* send + recv in one go. */
int  diag_xfer(diag_t *d, const uint8_t *req, size_t reqlen,
               uint8_t *out, size_t outsz, int timeout_ms);

const char *diag_error(const diag_t *d);

#endif
