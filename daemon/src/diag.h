/* diag.h -- Qualcomm DIAG transport over the Android /dev/diag character device.
 *
 * The kernel diag driver multiplexes DIAG traffic between the modem and
 * userspace.  We switch it to MEMORY_DEVICE_MODE so that responses to the
 * commands we write are handed back to us instead of leaving over the USB
 * diag port.
 *
 * Wire format towards the driver (see QCSuper's adb_bridge.c):
 *   write: [u32 USER_SPACE_DATA_TYPE] ([i32 -1] only when targeting an MDM)
 *          [HDLC frame]
 *   read:  [u32 USER_SPACE_DATA_TYPE] [u32 num_msgs]
 *          num_msgs * ( ([i32 -1] when MDM) [u32 len] [HDLC frame] )
 */
#ifndef QEFS_DIAG_H
#define QEFS_DIAG_H

#include <stddef.h>
#include <stdint.h>

#define DIAG_MAX_PKT   16384
#define DIAG_DEV       "/dev/diag"

/* DIAG error responses that a target may return instead of a real answer. */
#define DIAG_BAD_CMD_F   0x13
#define DIAG_BAD_SPC_F   0x18

/* How we reach the modem.  Newer targets ship no diagchar driver at all and
 * publish DIAG as QRTR service 4097 instead; there the request is sent raw and
 * the answer arrives wrapped in the non-HDLC framing 7E 01 <len16> ... 7E. */
enum {
    DIAG_TP_CHARDEV = 0,   /* /dev/diag                        */
    DIAG_TP_MOCK,          /* a unix socket, for the test rig  */
    DIAG_TP_QRTR,          /* AF_QIPCRTR, service 4097         */
};

#define QRTR_DIAG_SERVICE 4097

typedef struct {
    int      fd;
    int      transport;
    uint32_t qrtr_node, qrtr_port;
    char     desc[64];         /* human-readable transport name */
    int      use_mdm;          /* 1 = talk to an external MDM through the bridge */
    int      logging_variant;  /* which SWITCH_LOGGING struct the kernel took   */
    uint32_t peripheral_mask;
    unsigned long rx_frames;
    unsigned long rx_dropped;
    unsigned long crc_errors;
    char     last_error[256];
} diag_t;

/* Overrides the device path.  A path that is a unix socket is connected to
 * with SOCK_SEQPACKET instead of being opened, which is how the test harness
 * substitutes a mock modem for /dev/diag. */
void diag_set_device(const char *path);
const char *diag_device(void);
const char *diag_transport_desc(const diag_t *d);

/* Pins the QRTR endpoint instead of discovering it (node:port). */
void diag_set_qrtr(uint32_t node, uint32_t port);

int  diag_open(diag_t *d, int use_mdm);
void diag_close(diag_t *d);

/* One raw DIAG payload out (HDLC framing is added here). */
int  diag_send(diag_t *d, const uint8_t *req, size_t len);

/* Waits for a matching answer.  want_cmd is the expected first byte; when
 * want_subsys >= 0 the subsystem id (byte 1) and 16-bit subsystem command
 * (bytes 2-3) are matched too.  Unrelated log/event traffic is dropped.
 * Returns the unframed payload length, or -1 on timeout/error. */
int  diag_recv(diag_t *d, uint8_t *out, size_t outsz, int timeout_ms,
               int want_cmd, int want_subsys, int want_subcmd);

/* send + recv in one go. */
int  diag_xfer(diag_t *d, const uint8_t *req, size_t reqlen,
               uint8_t *out, size_t outsz, int timeout_ms);

const char *diag_error(const diag_t *d);

/* Exposed for the "raw" debug command. */
uint16_t hdlc_crc16(uint16_t iv, const uint8_t *data, size_t len);
int  hdlc_encode(const uint8_t *in, size_t len, uint8_t *out, size_t outsz);
int  hdlc_decode(const uint8_t *frame, size_t len, uint8_t *out, size_t outsz);

#endif
