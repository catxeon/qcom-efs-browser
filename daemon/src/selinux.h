/* selinux.h -- temporary, self-restoring permissive window.
 *
 * Some devices ship a policy that denies the su domain access to
 * /dev/diag.  When that happens the daemon may drop SELinux to permissive
 * for as long as it takes to open the device and run the setup ioctls, then
 * puts it straight back.
 *
 * Restoring is guarded three ways:
 *   1. se_restore() on every normal exit path (command, disconnect, atexit);
 *   2. fatal-signal handlers installed by se_install_guards();
 *   3. a forked deadman that holds a pipe to this process and restores the
 *      original value if we die without saying goodbye -- SIGKILL included.
 */
#ifndef QEFS_SELINUX_H
#define QEFS_SELINUX_H

/* Points the code at a different file instead of /sys/fs/selinux/enforce.
 * Only the test harness passes this; the app never does. */
void se_set_node(const char *path);

/* 1 = enforcing, 0 = permissive, -1 = SELinux node not readable. */
int  se_get_enforce(void);

/* Which node was found, or NULL. */
const char *se_node(void);

/* Drops to permissive, remembering the previous value and arming the deadman.
 * Returns 0 when it lowered, 1 when SELinux was already permissive (nothing
 * to do), -1 on failure -- se_last_error() then says why. */
int  se_lower(void);

/* Puts the original value back.  Idempotent and safe to call from a signal
 * handler. */
void se_restore(void);

int  se_is_lowered(void);
int  se_original(void);
const char *se_last_error(void);

/* atexit + fatal signal handlers.  Call once at startup. */
void se_install_guards(void);

#endif
