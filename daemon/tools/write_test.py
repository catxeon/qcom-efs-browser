#!/usr/bin/env python3
"""Reversible write check against a real modem.

Creates a fresh directory, writes a small file into it, reads it back, then
removes both and confirms the root listing is unchanged.  Nothing that already
exists on the modem is touched, and the cleanup runs even when a step fails.

    python3 write_test.py [base_path]
"""

import base64
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from efsctl import Client                                     # noqa: E402

BASE = sys.argv[1] if len(sys.argv) > 1 else "/efs_write_test"
FILE = BASE + "/hello.txt"
PAYLOAD = b"written by qcom-efsd write_test\n"

ok = []
bad = []


def check(name, cond, detail=""):
    (ok if cond else bad).append(name)
    print("%-5s %s%s" % ("PASS" if cond else "FAIL", name,
                         ("  -- " + str(detail)) if detail and not cond else ""))


def main():
    c = Client()

    info = c.cmd(cmd="open")
    print("transport: %s, subsys 0x%x" % (info.get("transport"), info.get("subsys")))

    before = c.cmd(cmd="ls", path="/")
    root_before = sorted(e["name"] for e in before.get("entries", []))
    print("root has %d entries before the test" % len(root_before))

    if os.path.basename(BASE) in root_before:
        print("refusing to run: %s already exists" % BASE)
        return 2

    # The daemon refuses every mutation until this is turned off.
    r = c.cmd(cmd="write", path=FILE, data=base64.b64encode(PAYLOAD).decode())
    check("writes are refused while read-only", r.get("ok") is False, r)

    c.cmd(cmd="readonly", on=False)
    print("read-only disabled\n--- writing ---")

    try:
        r = c.cmd(cmd="mkdir", path=BASE, mode=0o777)
        check("mkdir", r.get("ok"), r)

        r = c.cmd(cmd="stat", path=BASE)
        check("the new directory is a directory", r.get("type") == "dir", r)

        r = c.cmd(cmd="write", path=FILE, data=base64.b64encode(PAYLOAD).decode(), mode=0o644)
        check("write", r.get("ok") and r.get("size") == len(PAYLOAD), r)

        r = c.cmd(cmd="stat", path=FILE)
        print("     stat: type=%s mode=%s size=%s"
              % (r.get("type"), oct(r.get("mode", 0)), r.get("size")))
        check("the file is stored as a regular file, not an item file",
              r.get("type") == "file", r.get("type"))
        check("size matches", r.get("size") == len(PAYLOAD), r.get("size"))

        r = c.cmd(cmd="read", path=FILE)
        got = base64.b64decode(r.get("data", ""))
        check("the bytes read back are identical", got == PAYLOAD, got)

        r = c.cmd(cmd="ls", path=BASE)
        check("the file shows up in the listing",
              [e["name"] for e in r.get("entries", [])] == ["hello.txt"], r.get("entries"))

    finally:
        print("--- cleaning up ---")
        r = c.cmd(cmd="unlink", path=FILE)
        check("unlink", r.get("ok"), r)
        r = c.cmd(cmd="rmdir", path=BASE)
        check("rmdir", r.get("ok"), r)

        after = c.cmd(cmd="ls", path="/")
        root_after = sorted(e["name"] for e in after.get("entries", []))
        check("the root listing is byte-for-byte what it was",
              root_after == root_before,
              set(root_after) ^ set(root_before))

        c.cmd(cmd="readonly", on=True)
        print("read-only restored")

    print("\n%d passed, %d failed" % (len(ok), len(bad)))
    if bad:
        print("failed: " + ", ".join(bad))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
