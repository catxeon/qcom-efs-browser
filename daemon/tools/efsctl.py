#!/usr/bin/env python3
"""Talks to the daemon over `adb forward tcp:<port> localabstract:<socket>`.

Handy for driving the helper on a real device without the app:

    adb push qcom-efsd /data/local/tmp/efsd
    adb shell su -c 'setsid /data/local/tmp/efsd -uid 2000 -socket qcom_efsd_dbg &'
    adb forward tcp:9999 localabstract:qcom_efsd_dbg
    python3 efsctl.py open
    python3 efsctl.py ls /
"""

import base64
import json
import socket
import sys

PORT = 9999


class Client:
    """Connects over the adb TCP forward, or straight to an abstract socket
    when EFSCTL_ABSTRACT names one (Linux only -- handy for the mock)."""

    def __init__(self, port=PORT):
        import os
        name = os.environ.get("EFSCTL_ABSTRACT")
        if name:
            self.s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
            self.s.settimeout(60)
            self.s.connect("\0" + name)
        else:
            self.s = socket.create_connection(("127.0.0.1", port), timeout=60)
        self.buf = b""

    def cmd(self, **kw):
        self.s.sendall((json.dumps(kw) + "\n").encode())
        while b"\n" not in self.buf:
            chunk = self.s.recv(1 << 20)
            if not chunk:
                raise RuntimeError("the daemon closed the connection")
            self.buf += chunk
        line, self.buf = self.buf.split(b"\n", 1)
        return json.loads(line)


def main():
    args = sys.argv[1:]
    if not args:
        print(__doc__)
        return 2

    c = Client()
    verb = args[0]

    if verb == "open":
        print(json.dumps(c.cmd(cmd="open"), indent=2))
    elif verb == "ls":
        c.cmd(cmd="open")
        r = c.cmd(cmd="ls", path=args[1] if len(args) > 1 else "/")
        for e in r.get("entries", []):
            print("%-5s %6o %10d  %s" % (e["type"], e["mode"] & 0o7777, e["size"], e["name"]))
        print("%d entries" % len(r.get("entries", [])))
    elif verb == "cat":
        c.cmd(cmd="open")
        r = c.cmd(cmd="read", path=args[1])
        data = base64.b64decode(r.get("data", ""))
        sys.stdout.buffer.write(data)
    elif verb == "stat":
        c.cmd(cmd="open")
        print(json.dumps(c.cmd(cmd="stat", path=args[1]), indent=2))
    elif verb == "raw":
        print(json.dumps(c.cmd(cmd=args[0], **json.loads(args[1])), indent=2))
    else:
        payload = json.loads(args[1]) if len(args) > 1 else {}
        if verb not in ("version", "readonly", "selinux", "stats", "shutdown", "close"):
            c.cmd(cmd="open")
        print(json.dumps(c.cmd(cmd=verb, **payload), indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
