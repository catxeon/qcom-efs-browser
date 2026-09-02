#!/usr/bin/env python3
"""Tries, in order of increasing intrusiveness, to remove a leftover EFS path.

    python3 cleanup_probe.py /efs_write_test/hello.txt /efs_write_test
"""

import json
import os
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from efsctl import Client                                     # noqa: E402


def hdr(op):
    return bytes([0x4B, 0x13]) + struct.pack("<H", op)


def main():
    target = sys.argv[1]
    parent = sys.argv[2] if len(sys.argv) > 2 else None

    c = Client()
    print("open:", json.dumps(c.cmd(cmd="open"))[:80])
    c.cmd(cmd="readonly", on=False)

    def raw(payload, label):
        r = c.cmd(cmd="raw", hex=payload.hex())
        if not r.get("ok"):
            print("  %-28s transport error: %s" % (label, r.get("error")))
            return None
        resp = bytes.fromhex(r["response"])
        print("  %-28s %s" % (label, resp.hex()))
        return resp

    def exists(path):
        return c.cmd(cmd="stat", path=path).get("ok", False)

    print("\ntarget %s exists: %s" % (target, exists(target)))

    # 1. plain unlink again, in case the first failure was transient
    r = c.cmd(cmd="unlink", path=target)
    print("1. unlink            ->", r.get("ok") or r.get("error"))
    if not exists(target):
        print("   gone")
        return finish(c, parent)

    # 2. DELTREE (opcode 37): header + sequence(u16) + path
    raw(hdr(37) + struct.pack("<H", 1) + target.encode() + b"\0", "2. deltree(file)")
    if not exists(target):
        print("   gone")
        return finish(c, parent)

    # 3. rewrite it as a plain file (no O_ITEMFILE), then unlink
    #    open flags: O_WRONLY|O_CREAT|O_TRUNC = 1 | 0x40 | 0x200
    resp = raw(hdr(2) + struct.pack("<ii", 0x241, 0o644) + target.encode() + b"\0",
               "3. open plain")
    if resp and len(resp) >= 12:
        fd, err = struct.unpack_from("<ii", resp, 4)
        print("     fd=%d errno=%d" % (fd, err))
        if fd >= 0 and err == 0:
            raw(hdr(3) + struct.pack("<i", fd), "   close")
            r = c.cmd(cmd="unlink", path=target)
            print("   unlink after rewrite ->", r.get("ok") or r.get("error"))
    if not exists(target):
        print("   gone")
        return finish(c, parent)

    print("\ncould not remove %s" % target)
    finish(c, parent)
    return 1


def finish(c, parent):
    if parent:
        r = c.cmd(cmd="rmdir", path=parent)
        print("rmdir %s -> %s" % (parent, r.get("ok") or r.get("error")))
        print("parent still there:", c.cmd(cmd="stat", path=parent).get("ok", False))
    root = [e["name"] for e in c.cmd(cmd="ls", path="/").get("entries", [])]
    print("root entries: %d" % len(root))
    print("leftovers in root:", [n for n in root if "efs_write_test" in n] or "none")
    c.cmd(cmd="readonly", on=True)
    print("read-only restored")
    return 0


if __name__ == "__main__":
    sys.exit(main())
