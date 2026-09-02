#!/usr/bin/env python3
"""Checks the classic EFS2 opcode numbering against this modem, and cleans up.

libopenpst and EfsTools both number the namespace calls 6 symlink, 7 readlink,
8 unlink, 9 mkdir, 10 rmdir, 14 rename.  qfenix's header instead has 7 unlink,
8 rmdir, 14 readlink.  This tries the classic numbers on the leftover test
files: if 8 removes a file and 10 removes the directory, the classic numbering
is the right one.
"""

import os
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from efsctl import Client                                     # noqa: E402

DIR = "/efs_write_test"


def hdr(op):
    return bytes([0x4B, 0x13]) + struct.pack("<H", op)


def main():
    c = Client()
    c.cmd(cmd="open")
    c.cmd(cmd="readonly", on=False)

    def raw(payload, label):
        r = c.cmd(cmd="raw", hex=payload.hex())
        if not r.get("ok"):
            print("  %-30s transport error: %s" % (label, r.get("error")))
            return None
        resp = bytes.fromhex(r["response"])
        err = struct.unpack_from("<i", resp, 4)[0] if len(resp) >= 8 else None
        print("  %-30s errno=%s  raw=%s" % (label, err, resp.hex()))
        return err

    def exists(p):
        return c.cmd(cmd="stat", path=p).get("ok", False)

    entries = [e["name"] for e in c.cmd(cmd="ls", path=DIR).get("entries", [])]
    print("before:", entries)

    for name in entries:
        path = DIR + "/" + name
        print("\nremoving %s" % path)
        raw(hdr(8) + path.encode() + b"\0", "opcode 8 (classic unlink)")
        print("  still there:", exists(path))

    print("\nremoving the directory")
    raw(hdr(10) + DIR.encode() + b"\0", "opcode 10 (classic rmdir)")
    print("  still there:", exists(DIR))

    root = [e["name"] for e in c.cmd(cmd="ls", path="/").get("entries", [])]
    print("\nroot entries: %d" % len(root))
    print("leftovers:", [n for n in root if "efs_write_test" in n] or "none")

    c.cmd(cmd="readonly", on=True)
    print("read-only restored")
    return 0


if __name__ == "__main__":
    sys.exit(main())
