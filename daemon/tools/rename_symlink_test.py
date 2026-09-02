#!/usr/bin/env python3
"""Reversible check of rename, symlink and readlink against a real modem.

Everything happens inside a directory that does not exist yet, and the cleanup
runs even when a step fails.

    python3 rename_symlink_test.py [base_dir]
"""

import base64
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from efsctl import Client                                     # noqa: E402

BASE = sys.argv[1] if len(sys.argv) > 1 else "/efs_rs_test"
A = BASE + "/a.txt"
B = BASE + "/b.txt"
LINK = BASE + "/link"
PAYLOAD = b"rename and symlink probe\n"

ok, bad = [], []


def check(name, cond, detail=""):
    (ok if cond else bad).append(name)
    print("%-5s %s%s" % ("PASS" if cond else "FAIL", name,
                         ("  -- " + str(detail)) if detail and not cond else ""))


def main():
    c = Client()
    info = c.cmd(cmd="open")
    print("transport: %s\n" % info.get("transport"))

    root_before = sorted(e["name"] for e in c.cmd(cmd="ls", path="/").get("entries", []))
    if os.path.basename(BASE) in root_before:
        print("refusing to run: %s already exists" % BASE)
        return 2

    c.cmd(cmd="readonly", on=False)

    try:
        check("mkdir", c.cmd(cmd="mkdir", path=BASE, mode=0o777).get("ok"))
        r = c.cmd(cmd="write", path=A, data=base64.b64encode(PAYLOAD).decode(), mode=0o644)
        check("write the file to rename", r.get("ok"), r)

        print("\n--- rename ---")
        r = c.cmd(cmd="rename", **{"from": A, "to": B})
        check("rename", r.get("ok"), r)
        check("the old name is gone", c.cmd(cmd="stat", path=A).get("ok") is False)
        st = c.cmd(cmd="stat", path=B)
        check("the new name exists and is a file", st.get("type") == "file", st)
        got = base64.b64decode(c.cmd(cmd="read", path=B).get("data", ""))
        check("contents survived the rename", got == PAYLOAD, got)

        print("\n--- symlink ---")
        r = c.cmd(cmd="symlink", target=B, link=LINK)
        check("symlink", r.get("ok"), r)

        st = c.cmd(cmd="stat", path=LINK)
        print("     stat: %s" % json.dumps({k: st.get(k) for k in
                                            ("ok", "type", "mode", "size", "target", "stat_call")}))
        check("the link stats as a link", st.get("type") == "link", st.get("type"))
        check("stat resolves the target", st.get("target") == B, st.get("target"))

        r = c.cmd(cmd="readlink", path=LINK)
        check("readlink returns the target", r.get("target") == B, r)

        r = c.cmd(cmd="read", path=LINK)
        print("     reading through the link: %s"
              % ("ok, identical" if base64.b64decode(r.get("data", "")) == PAYLOAD
                 else json.dumps(r)[:120]))

        names = sorted(e["name"] for e in c.cmd(cmd="ls", path=BASE).get("entries", []))
        check("both entries are listed", names == ["b.txt", "link"], names)

    finally:
        print("\n--- cleaning up ---")
        check("unlink the symlink", c.cmd(cmd="unlink", path=LINK).get("ok"))
        check("unlink the file", c.cmd(cmd="unlink", path=B).get("ok"))
        # in case the rename never happened
        c.cmd(cmd="unlink", path=A)
        check("rmdir", c.cmd(cmd="rmdir", path=BASE).get("ok"))

        root_after = sorted(e["name"] for e in c.cmd(cmd="ls", path="/").get("entries", []))
        check("the root listing is unchanged", root_after == root_before,
              set(root_after) ^ set(root_before))
        c.cmd(cmd="readonly", on=True)
        print("read-only restored")

    print("\n%d passed, %d failed" % (len(ok), len(bad)))
    if bad:
        print("failed: " + ", ".join(bad))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
