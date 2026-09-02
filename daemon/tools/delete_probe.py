#!/usr/bin/env python3
"""Works out what this modem will and will not let us delete.

Creates a plain file next to the stuck one and tries to unlink it, so we can
tell "unlink is broken" apart from "unlink cannot remove item files".
"""

import json
import os
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from efsctl import Client                                     # noqa: E402

DIR = "/efs_write_test"
STUCK = DIR + "/hello.txt"
PLAIN = DIR + "/plain.txt"


def hdr(op):
    return bytes([0x4B, 0x13]) + struct.pack("<H", op)


def main():
    c = Client()
    c.cmd(cmd="open")
    c.cmd(cmd="readonly", on=False)

    def raw(payload, label):
        r = c.cmd(cmd="raw", hex=payload.hex())
        if not r.get("ok"):
            print("  %-26s error: %s" % (label, r.get("error")))
            return None
        resp = bytes.fromhex(r["response"])
        print("  %-26s %s" % (label, resp.hex()))
        return resp

    def errno_of(resp):
        return struct.unpack_from("<i", resp, 4)[0] if resp and len(resp) >= 8 else None

    print("stuck file now:", json.dumps(c.cmd(cmd="stat", path=STUCK)))

    print("\n-- deltree on the directory --")
    r = raw(hdr(37) + struct.pack("<H", 2) + DIR.encode() + b"\0", "deltree(dir)")
    if r and len(r) >= 10:
        print("     seq=%d error=%d" % (struct.unpack_from("<H", r, 4)[0],
                                        struct.unpack_from("<i", r, 6)[0]))
    print("   dir still there:", c.cmd(cmd="stat", path=DIR).get("ok"))

    print("\n-- create a plain file with open/write/close --")
    # O_WRONLY|O_CREAT|O_TRUNC, no O_ITEMFILE and no O_AUTODIR
    resp = raw(hdr(2) + struct.pack("<ii", 0x241, 0o644) + PLAIN.encode() + b"\0", "open")
    fd = None
    if resp and len(resp) >= 12:
        fd, err = struct.unpack_from("<ii", resp, 4)
        print("     fd=%d errno=%d" % (fd, err))
        if err == 0 and fd >= 0:
            raw(hdr(5) + struct.pack("<iI", fd, 0) + b"probe", "write")
            raw(hdr(3) + struct.pack("<i", fd), "close")

    st = c.cmd(cmd="stat", path=PLAIN)
    print("     stat:", json.dumps({k: st.get(k) for k in ("ok", "type", "mode", "size")}))

    print("\n-- unlink the plain file --")
    r = c.cmd(cmd="unlink", path=PLAIN)
    print("   unlink ->", r.get("ok") or r.get("error"))
    print("   still there:", c.cmd(cmd="stat", path=PLAIN).get("ok"))

    print("\n-- unlink the stuck item file once more --")
    r = c.cmd(cmd="unlink", path=STUCK)
    print("   unlink ->", r.get("ok") or r.get("error"))

    print("\n-- final state --")
    print("   dir listing:", [e["name"] for e in c.cmd(cmd="ls", path=DIR).get("entries", [])])
    c.cmd(cmd="readonly", on=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
