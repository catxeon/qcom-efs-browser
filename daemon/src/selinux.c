#define _GNU_SOURCE

#include "selinux.h"
#include "util.h"

#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static const char *const NODES[] = {
    "/sys/fs/selinux/enforce",
    "/selinux/enforce",
};

static const char *g_forced_node = NULL;
static int  g_original = -1;   /* value before we touched anything */
static volatile sig_atomic_t g_lowered = 0;
static int  g_deadman = -1;    /* write end of the pipe the deadman watches */
static char g_error[192];

const char *se_last_error(void) { return g_error; }
int se_is_lowered(void) { return g_lowered; }
int se_original(void) { return g_original; }

void se_set_node(const char *path) { g_forced_node = path; }

const char *se_node(void)
{
    if (g_forced_node) return g_forced_node;
    for (size_t i = 0; i < sizeof NODES / sizeof NODES[0]; i++)
        if (access(NODES[i], F_OK) == 0) return NODES[i];
    return NULL;
}

int se_get_enforce(void)
{
    const char *node = se_node();
    if (!node) return -1;

    int fd = open(node, O_RDONLY);
    if (fd < 0) return -1;

    char c = 0;
    ssize_t n = read(fd, &c, 1);
    close(fd);
    if (n != 1) return -1;
    return (c == '1') ? 1 : 0;
}

/* Async-signal-safe: only open/write/close. */
static int se_write_enforce(int on)
{
    const char *node = se_node();
    if (!node) return -1;

    int fd = open(node, O_WRONLY);
    if (fd < 0) return -1;

    ssize_t n = write(fd, on ? "1" : "0", 1);
    close(fd);
    return (n == 1) ? 0 : -1;
}

/* Forks a child that does nothing but wait on a pipe.  If we exit without
 * writing to it -- crash, SIGKILL, OOM kill -- the read returns EOF and the
 * child puts SELinux back. */
static void arm_deadman(int restore_to)
{
    int fds[2];
    if (pipe(fds) < 0) {
        qlog("deadman: pipe failed (%s)", strerror(errno));
        return;
    }

    pid_t pid = fork();
    if (pid < 0) {
        close(fds[0]);
        close(fds[1]);
        qlog("deadman: fork failed (%s)", strerror(errno));
        return;
    }

    if (pid == 0) {
        /* Child.  Drop every inherited descriptor except the pipe so that a
         * dead parent's listening socket and /dev/diag handle do not linger. */
        for (int fd = 3; fd < 1024; fd++)
            if (fd != fds[0]) close(fd);

        char c;
        ssize_t r = read(fds[0], &c, 1);
        if (r <= 0) se_write_enforce(restore_to);   /* the parent vanished */
        _exit(0);
    }

    close(fds[0]);
    g_deadman = fds[1];
}

static void disarm_deadman(void)
{
    if (g_deadman < 0) return;
    ssize_t ignored = write(g_deadman, "x", 1);   /* "stand down" */
    (void)ignored;
    close(g_deadman);
    g_deadman = -1;
}

int se_lower(void)
{
    g_error[0] = 0;

    if (g_lowered) return 0;

    const char *node = se_node();
    if (!node) {
        snprintf(g_error, sizeof g_error, "no SELinux node under /sys/fs/selinux");
        return -1;
    }

    int cur = se_get_enforce();
    if (cur < 0) {
        snprintf(g_error, sizeof g_error, "cannot read %s: %s", node, strerror(errno));
        return -1;
    }
    if (cur == 0) {
        /* Already permissive and not by us -- leave it exactly as we found it. */
        g_original = 0;
        return 1;
    }

    g_original = 1;
    arm_deadman(1);

    if (se_write_enforce(0) < 0) {
        snprintf(g_error, sizeof g_error,
                 "cannot write %s: %s (the policy may forbid setenforce for this domain)",
                 node, strerror(errno));
        disarm_deadman();
        return -1;
    }

    g_lowered = 1;
    qlog("SELinux lowered to permissive (was enforcing)");
    return 0;
}

void se_restore(void)
{
    if (!g_lowered) {
        disarm_deadman();
        return;
    }
    g_lowered = 0;
    se_write_enforce(g_original < 0 ? 1 : g_original);
    disarm_deadman();
    qlog("SELinux restored to enforcing");
}

static void fatal_handler(int sig)
{
    se_restore();
    signal(sig, SIG_DFL);
    raise(sig);
}

static void atexit_handler(void) { se_restore(); }

void se_install_guards(void)
{
    static const int SIGS[] = {
        SIGTERM, SIGINT, SIGHUP, SIGQUIT,
        SIGSEGV, SIGBUS, SIGABRT, SIGILL, SIGFPE,
    };
    for (size_t i = 0; i < sizeof SIGS / sizeof SIGS[0]; i++)
        signal(SIGS[i], fatal_handler);

    atexit(atexit_handler);
}
