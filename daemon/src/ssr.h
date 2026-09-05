/* ssr.h -- modem subsystem restart through Xiaomi's proprietary vendor QMI
 * service (0xFFE4), a byte-for-byte replay of what `/vendor/bin/mtb 11 0`
 * sends.  Only Xiaomi modems publish that service; everywhere else the
 * lookup fails and the command reports it.
 */
#ifndef QEFS_SSR_H
#define QEFS_SSR_H

#include <stddef.h>

/* Tests point the SSR path at a unix socket ("<mock path>.ssr") instead of
 * the QRTR bus.  Call with the same path that went to diag_set_mock(). */
void ssr_set_mock(const char *path);

/* Sends the captured request and waits for the answer.  Returns 0 on a
 * response with the expected transaction, -1 with [err] filled in
 * otherwise ("no SSR service ..." / "ssr_no_response" / strerror). */
int ssr_trigger(char *err, size_t errsz);

#endif
