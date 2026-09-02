#!/usr/bin/env python3
"""End-to-end test: real daemon binary <-> mock modem.

    python3 daemon/test/run_tests.py /path/to/qcom-efsd

Starts the mock modem on a unix socket, starts the daemon pointed at it with
`-dev`, then drives the daemon over its abstract socket exactly the way the
Android app does and checks the answers.
"""

import base64
import json
import os
import socket
import subprocess
import sys
import tarfile
import tempfile
import time

HERE = os.path.dirname(os.path.abspath(__file__))
SOCKET_NAME = "qcom_efsd_test_%d" % os.getpid()

passed = []
failed = []


def check(name, cond, detail=""):
    (passed if cond else failed).append(name)
    mark = "PASS" if cond else "FAIL"
    print("%-5s %s%s" % (mark, name, ("  -- " + str(detail)) if detail and not cond else ""))
    return cond


class Client:
    def __init__(self, name):
        self.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.sock.settimeout(30)
        self.sock.connect("\0" + name)
        self.buf = b""

    def cmd(self, **kw):
        self.sock.sendall((json.dumps(kw) + "\n").encode())
        while b"\n" not in self.buf:
            chunk = self.sock.recv(1 << 20)
            if not chunk:
                raise RuntimeError("daemon closed the connection")
            self.buf += chunk
        line, self.buf = self.buf.split(b"\n", 1)
        return json.loads(line)


def spawn(daemon_bin, tmp, tag, reject_hellos=0, extra=()):
    """Starts a mock modem plus a daemon wired to it; returns (mock, daemon, client)."""
    mock_sock = os.path.join(tmp, "modem-%s.sock" % tag)
    mock_log = os.path.join(tmp, "modem-%s.log" % tag)
    name = "%s_%s" % (SOCKET_NAME, tag)

    mock = subprocess.Popen(
        [sys.executable, os.path.join(HERE, "mock_modem.py"),
         mock_sock, mock_log, str(reject_hellos)],
        stdout=subprocess.PIPE)
    if mock.stdout.readline().strip() != b"ready":
        raise RuntimeError("mock modem failed to start")

    log = open(os.path.join(tmp, "daemon-%s.log" % tag), "w+")
    daemon = subprocess.Popen(
        [daemon_bin, "-uid", str(os.getuid()), "-socket", name,
         "-dev", mock_sock, "-verbose"] + list(extra),
        stdout=log, stderr=subprocess.STDOUT)

    deadline = time.time() + 5
    while time.time() < deadline:
        try:
            return mock, daemon, Client(name), log
        except OSError:
            time.sleep(0.1)
    raise RuntimeError("could not connect to the daemon")


def test_selinux(daemon_bin, tmp):
    """The permissive window: escalate only when needed, always come back."""
    node = os.path.join(tmp, "enforce")

    # -- it must not touch a policy it was never allowed to touch --
    open(node, "w").write("1")
    mock, daemon, c, _ = spawn(daemon_bin, tmp, "nose", reject_hellos=3,
                               extra=["-selinux-node", node])
    try:
        r = c.cmd(cmd="open")
        check("without -allow-permissive the modem stays unreachable", r.get("ok") is False, r)
        check("and enforcing is untouched", open(node).read() == "1", open(node).read())
    finally:
        daemon.kill(); mock.kill()

    # -- escalation, then restore on command --
    open(node, "w").write("1")
    mock, daemon, c, _ = spawn(daemon_bin, tmp, "esc", reject_hellos=3,
                               extra=["-allow-permissive", "-selinux-node", node])
    try:
        r = c.cmd(cmd="open")
        se = r.get("selinux", {})
        check("a blocked modem triggers the permissive retry", r.get("ok") and se.get("used_permissive"), r)
        check("the window is held for the session", se.get("held") is True, se)
        check("the node really went permissive", open(node).read() == "0", open(node).read())
        check("the original value was recorded", se.get("was_enforcing") == 1, se)

        r = c.cmd(cmd="selinux_restore")
        check("restore on demand", r.get("selinux", {}).get("held") is False, r)
        check("enforcing is back", open(node).read() == "1", open(node).read())
    finally:
        daemon.kill(); mock.kill()

    # -- the deadman: SIGKILL cannot be caught, so a child has to do it --
    open(node, "w").write("1")
    mock, daemon, c, _ = spawn(daemon_bin, tmp, "kill", reject_hellos=3,
                               extra=["-allow-permissive", "-selinux-node", node])
    try:
        r = c.cmd(cmd="open")
        check("held again before the kill", r.get("selinux", {}).get("held") is True, r.get("selinux"))
        check("permissive before the kill", open(node).read() == "0", open(node).read())

        daemon.kill()                      # SIGKILL -- no handler can run
        daemon.wait(timeout=5)
        deadline = time.time() + 5
        while time.time() < deadline and open(node).read() != "1":
            time.sleep(0.1)
        check("the deadman restores enforcing after SIGKILL",
              open(node).read() == "1", open(node).read())
    finally:
        try:
            daemon.kill()
        except Exception:
            pass
        mock.kill()

    # -- a graceful exit restores too --
    open(node, "w").write("1")
    mock, daemon, c, _ = spawn(daemon_bin, tmp, "term", reject_hellos=3,
                               extra=["-allow-permissive", "-selinux-node", node])
    try:
        c.cmd(cmd="open")
        daemon.terminate()                 # SIGTERM -- the handler runs
        daemon.wait(timeout=5)
        check("SIGTERM restores enforcing", open(node).read() == "1", open(node).read())
    finally:
        mock.kill()


def main():
    daemon_bin = sys.argv[1]
    tmp = tempfile.mkdtemp(prefix="efs-test-")
    mock_sock = os.path.join(tmp, "modem.sock")
    mock_log = os.path.join(tmp, "modem.log")

    mock = subprocess.Popen(
        [sys.executable, os.path.join(HERE, "mock_modem.py"), mock_sock, mock_log],
        stdout=subprocess.PIPE)
    if mock.stdout.readline().strip() != b"ready":
        print("mock modem failed to start")
        return 2

    daemon_log = open(os.path.join(tmp, "daemon.log"), "w+")
    daemon = subprocess.Popen(
        [daemon_bin, "-uid", str(os.getuid()), "-socket", SOCKET_NAME,
         "-dev", mock_sock, "-verbose"],
        stdout=daemon_log, stderr=subprocess.STDOUT)

    client = None
    deadline = time.time() + 5
    while time.time() < deadline:
        try:
            client = Client(SOCKET_NAME)
            break
        except OSError:
            time.sleep(0.1)
    if client is None:
        print("could not connect to the daemon")
        daemon.kill()
        mock.kill()
        return 2

    try:
        run(client, tmp)
        print()
        test_selinux(daemon_bin, tmp)
    finally:
        try:
            client.cmd(cmd="shutdown")
        except Exception:
            pass
        time.sleep(0.2)
        daemon.terminate()
        mock.terminate()
        daemon_log.seek(0)
        log_text = daemon_log.read()

    print("\n---- daemon log ----")
    print(log_text.strip()[-2000:])
    print("---- mock log ----")
    print(open(mock_log).read().strip()[-2000:])

    print("\n%d passed, %d failed" % (len(passed), len(failed)))
    if failed:
        print("failed: " + ", ".join(failed))
    return 1 if failed else 0


def run(c, tmp):
    # ---- session ----
    r = c.cmd(cmd="version")
    check("version", r.get("ok") and r.get("version"), r)

    r = c.cmd(cmd="open")
    check("open succeeds", r.get("ok"), r)
    check("falls back from subsys 0x3E to 0x13", r.get("subsys") == 0x13, r.get("subsys"))
    check("starts read-only", r.get("readonly") is True, r)
    check("selinux untouched", r.get("selinux", {}).get("used_permissive") is False, r.get("selinux"))

    # ---- listing ----
    r = c.cmd(cmd="ls", path="/")
    names = {e["name"]: e for e in r.get("entries", [])}
    check("ls / returns every entry", r.get("ok") and len(names) == 6, sorted(names))
    check("directories are typed", names.get("nv", {}).get("type") == "dir", names.get("nv"))
    check("symlinks are typed", names.get("link_to_readme", {}).get("type") == "link",
          names.get("link_to_readme"))
    check("sizes come through", names.get("big.bin", {}).get("size") == 5000, names.get("big.bin"))
    check("modes come through", names.get("README.txt", {}).get("mode", 0) & 0o777 == 0o644,
          oct(names.get("README.txt", {}).get("mode", 0)))

    r = c.cmd(cmd="ls", path="/nv/item_files/modem/nas")
    kinds = {e["name"]: e["type"] for e in r.get("entries", [])}
    check("item files are typed", kinds.get("rk_hplmn") == "item", kinds)

    r = c.cmd(cmd="ls", path="/nope")
    check("missing directory reports ENOENT", r.get("ok") is False and r.get("efs_errno") == 2, r)

    # ---- metadata ----
    r = c.cmd(cmd="stat", path="/README.txt")
    check("stat size", r.get("size") == 26, r)
    check("stat type", r.get("type") == "file", r)

    r = c.cmd(cmd="stat", path="/link_to_readme")
    check("stat resolves the symlink target", r.get("target") == "/README.txt", r)

    r = c.cmd(cmd="readlink", path="/link_to_readme")
    check("readlink", r.get("target") == "/README.txt", r)

    r = c.cmd(cmd="statfs", path="/")
    check("statfs decodes", r.get("ok") and r.get("block_size") == 4096, r)

    # ---- reads ----
    r = c.cmd(cmd="read", path="/README.txt")
    check("inline read returns base64",
          base64.b64decode(r.get("data", "")) == b"hello from the fake modem\n", r)

    out = os.path.join(tmp, "big.bin")
    r = c.cmd(cmd="read", path="/big.bin", out=out)
    expect = bytes((i * 7 + 3) & 0xFF for i in range(5000))
    got = open(out, "rb").read() if os.path.exists(out) else b""
    check("chunked read of 5000 bytes", r.get("size") == 5000 and got == expect,
          "%d bytes, match=%s" % (len(got), got == expect))

    r = c.cmd(cmd="read", path="/nv/item_files/modem/nas/rk_hplmn")
    check("item file falls back to the GET interface",
          base64.b64decode(r.get("data", "")) == bytes(range(16)), r)

    # ---- recursive pull ----
    tree = os.path.join(tmp, "tree")
    r = c.cmd(cmd="pull_tree", path="/", out=tree)
    check("pull_tree walks the whole tree", r.get("ok") and r.get("files") == 4,
          {k: r.get(k) for k in ("dirs", "files", "links", "bytes", "errors")})
    check("pull_tree writes nested files",
          os.path.exists(os.path.join(tree, "policyman", "carrier_policy.xml")),
          os.listdir(tree) if os.path.isdir(tree) else "missing")
    check("pull_tree records symlinks",
          os.path.exists(os.path.join(tree, "link_to_readme.symlink")))
    check("pull_tree byte count",
          r.get("bytes") == 5000 + 26 + 44 + 16, r.get("bytes"))

    # ---- modem-generated tar ----
    tar_path = os.path.join(tmp, "efs.tar")
    r = c.cmd(cmd="image", path="/", out=tar_path)
    ok = r.get("ok")
    members = []
    if ok and os.path.exists(tar_path):
        try:
            with tarfile.open(tar_path) as tf:
                members = tf.getnames()
        except Exception as exc:
            ok = False
            members = str(exc)
    check("fs-image produces a readable tar", ok and "README.txt" in members, members)

    # ---- the read-only guard ----
    r = c.cmd(cmd="write", path="/rw/blocked.bin", data=base64.b64encode(b"x").decode())
    check("writes are refused while read-only", r.get("ok") is False, r)
    r = c.cmd(cmd="unlink", path="/README.txt")
    check("deletes are refused while read-only", r.get("ok") is False, r)

    r = c.cmd(cmd="readonly", on=False)
    check("read-only can be turned off", r.get("ok") and r.get("readonly") is False, r)

    # ---- writes ----
    payload = bytes((i * 13 + 5) & 0xFF for i in range(3000))
    src = os.path.join(tmp, "upload.bin")
    open(src, "wb").write(payload)
    r = c.cmd(cmd="write", path="/rw/big_upload.bin", src=src, mode=0o644)
    check("chunked write via open/write/close", r.get("ok") and r.get("size") == 3000, r)

    r = c.cmd(cmd="read", path="/rw/big_upload.bin")
    check("written bytes read back identical",
          base64.b64decode(r.get("data", "")) == payload,
          len(base64.b64decode(r.get("data", ""))))

    small = base64.b64encode(b"0123456789").decode()
    r = c.cmd(cmd="write", path="/nv/item_files/modem/nas/rk_hplmn", data=small, mode=0o644)
    check("item file written through PUT", r.get("ok"), r)
    r = c.cmd(cmd="read", path="/nv/item_files/modem/nas/rk_hplmn")
    check("item file round-trips", base64.b64decode(r.get("data", "")) == b"0123456789", r)

    # ---- namespace ----
    r = c.cmd(cmd="mkdir", path="/rw/sub", mode=0o755)
    check("mkdir", r.get("ok"), r)
    r = c.cmd(cmd="chmod", path="/rw/sub", mode=0o700)
    check("chmod", r.get("ok"), r)
    r = c.cmd(cmd="stat", path="/rw/sub")
    check("chmod took effect", r.get("mode", 0) & 0o777 == 0o700, oct(r.get("mode", 0)))

    r = c.cmd(cmd="symlink", target="/README.txt", link="/rw/sub/readme")
    check("symlink", r.get("ok"), r)

    r = c.cmd(cmd="rmtree", path="/rw/sub")
    check("rmtree removes a populated directory", r.get("ok"), r)
    r = c.cmd(cmd="stat", path="/rw/sub")
    check("rmtree really deleted it", r.get("ok") is False, r)

    r = c.cmd(cmd="unlink", path="/rw/big_upload.bin")
    check("unlink", r.get("ok"), r)

    # ---- the type of a written file is a choice, not a guess ------------
    body = base64.b64encode(b"forced").decode()

    # An ordinary path asked to become an item file.
    r = c.cmd(cmd="write", path="/rw/forced_item", data=body, item=True)
    check("write reports the type it used", r.get("item") is True, r)
    r = c.cmd(cmd="stat", path="/rw/forced_item")
    check("an ordinary path can be stored as an item file", r.get("type") == "item", r)

    # An item path asked to stay an ordinary file.
    item_path = "/nv/item_files/modem/nas/plain_on_purpose"
    r = c.cmd(cmd="write", path=item_path, data=body, item=False)
    check("the item flag can be turned off too", r.get("item") is False, r)
    r = c.cmd(cmd="stat", path=item_path)
    check("an item path can be stored as an ordinary file", r.get("type") == "file", r)

    # Left unset, the path still decides.
    r = c.cmd(cmd="write", path="/nv/item_files/modem/nas/by_path", data=body)
    check("without the flag the path still decides", r.get("item") is True, r)

    c.cmd(cmd="unlink", path="/rw/forced_item")
    c.cmd(cmd="unlink", path=item_path)
    c.cmd(cmd="unlink", path="/nv/item_files/modem/nas/by_path")

    # ---- rename and symlinks -------------------------------------------
    # These are the calls qfenix's opcode numbering gets wrong: with its
    # numbers a readlink lands on RENAME and every unlink fails.
    body = base64.b64encode(b"rename me").decode()
    c.cmd(cmd="write", path="/rw/ren_a.txt", data=body, mode=0o644)

    r = c.cmd(cmd="rename", **{"from": "/rw/ren_a.txt", "to": "/rw/ren_b.txt"})
    check("rename", r.get("ok"), r)
    check("the old name is gone",
          c.cmd(cmd="stat", path="/rw/ren_a.txt").get("ok") is False)
    r = c.cmd(cmd="read", path="/rw/ren_b.txt")
    check("the renamed file keeps its contents",
          base64.b64decode(r.get("data", "")) == b"rename me", r)

    r = c.cmd(cmd="symlink", target="/rw/ren_b.txt", link="/rw/ren_link")
    check("symlink", r.get("ok"), r)

    r = c.cmd(cmd="stat", path="/rw/ren_link")
    check("a symlink stats as a link", r.get("type") == "link", r)
    check("stat reports the link target", r.get("target") == "/rw/ren_b.txt", r)

    r = c.cmd(cmd="readlink", path="/rw/ren_link")
    check("readlink", r.get("target") == "/rw/ren_b.txt", r)

    check("unlink removes a symlink",
          c.cmd(cmd="unlink", path="/rw/ren_link").get("ok"))
    check("unlink removes the renamed file",
          c.cmd(cmd="unlink", path="/rw/ren_b.txt").get("ok"))

    # ---- NV items ----
    r = c.cmd(cmd="nv_read", item=550)
    check("nv_read", r.get("ok") and r.get("data", "").startswith("aaaa"), r)
    check("nv_read reports status", r.get("status") == 0 and r.get("status_text") == "OK", r)

    r = c.cmd(cmd="nv_read", item=9999)
    check("undefined NV item reports its status", r.get("ok") and r.get("status") == 8, r)

    r = c.cmd(cmd="nv_write", item=550, data="00" * 128)
    check("nv_write", r.get("ok"), r)
    r = c.cmd(cmd="nv_read", item=550)
    check("nv value changed", r.get("data") == "00" * 128, r.get("data", "")[:16])

    # ---- journal + raw ----
    r = c.cmd(cmd="sync")
    check("efs sync", r.get("ok"), r)

    r = c.cmd(cmd="raw", hex="4b1300000000")
    check("raw packet round-trips", r.get("ok") and r.get("response", "").startswith("4b1300"), r)

    r = c.cmd(cmd="stats")
    check("stats report received frames", r.get("rx_frames", 0) > 0, r)


if __name__ == "__main__":
    sys.exit(main())
