#!/usr/bin/env python3
"""Walks a directory with raw EFS2 packets to see exactly where a listing ends.

    python3 readdir_probe.py [path] [max_seq]
"""

import json
import struct
import sys

sys.path.insert(0, __file__.rsplit("\\", 1)[0].rsplit("/", 1)[0])
from efsctl import Client                                    # noqa: E402


def hdr(op):
    return bytes([0x4B, 0x13]) + struct.pack("<H", op)


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else "/"
    max_seq = int(sys.argv[2]) if len(sys.argv) > 2 else 16

    c = Client()
    print(json.dumps(c.cmd(cmd="open"))[:120])
    c.cmd(cmd="readonly", on=False)          # `raw` sits behind the write guard

    def raw(payload):
        r = c.cmd(cmd="raw", hex=payload.hex())
        if not r.get("ok"):
            return None, r.get("error")
        return bytes.fromhex(r["response"]), None

    resp, err = raw(hdr(11) + path.encode() + b"\0")          # OPENDIR
    if resp is None:
        print("opendir failed:", err)
        return 1
    dirp, errno = struct.unpack_from("<ii", resp, 4)
    print("opendir %r -> dirp=%d errno=%d" % (path, dirp, errno))
    if errno:
        return 1

    for seq in range(1, max_seq + 1):
        resp, err = raw(hdr(12) + struct.pack("<iI", dirp, seq))   # READDIR
        if resp is None:
            print("seq %2d: transport error: %s" % (seq, err))
            break
        if len(resp) < 16:
            print("seq %2d: short reply (%d bytes): %s" % (seq, len(resp), resp.hex()))
            break
        d, s, errno = struct.unpack_from("<iIi", resp, 4)
        if len(resp) < 40:
            print("seq %2d: errno=%d, only %d bytes: %s" % (seq, errno, len(resp), resp.hex()))
            continue
        etype, mode, size = struct.unpack_from("<iii", resp, 16)
        name = resp[40:].split(b"\0")[0].decode("utf-8", "replace")
        print("seq %2d: errno=%d type=%d mode=%06o size=%-8d len=%-4d name=%r"
              % (seq, errno, etype, mode, size, len(resp), name))

    raw(hdr(13) + struct.pack("<i", dirp))                     # CLOSEDIR
    c.cmd(cmd="readonly", on=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
