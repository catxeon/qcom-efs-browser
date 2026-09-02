#!/usr/bin/env python3
"""Reversible check of recursive delete against a real modem.

Builds a small nested tree, removes it with one rmtree, and confirms the
parent listing is exactly what it was.

    python3 rmtree_test.py [base_dir]
"""

import base64
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from efsctl import Client                                     # noqa: E402

BASE = sys.argv[1] if len(sys.argv) > 1 else "/efs_rmtree_test"
PARENT = BASE.rsplit("/", 1)[0] or "/"

TREE = [
    (BASE, None),
    (BASE + "/f1.txt", b"one"),
    (BASE + "/sub", None),
    (BASE + "/sub/f2.txt", b"two"),
    (BASE + "/sub/deep", None),
    (BASE + "/sub/deep/f3.txt", b"three"),
]

ok, bad = [], []


def check(name, cond, detail=""):
    (ok if cond else bad).append(name)
    print("%-5s %s%s" % ("PASS" if cond else "FAIL", name,
                         ("  -- " + str(detail)) if detail and not cond else ""))


def main():
    c = Client()
    print("transport: %s\n" % c.cmd(cmd="open").get("transport"))

    before = sorted(e["name"] for e in c.cmd(cmd="ls", path=PARENT).get("entries", []))
    if os.path.basename(BASE) in before:
        print("refusing to run: %s already exists" % BASE)
        return 2

    c.cmd(cmd="readonly", on=False)
    try:
        for path, data in TREE:
            if data is None:
                r = c.cmd(cmd="mkdir", path=path, mode=0o777)
            else:
                r = c.cmd(cmd="write", path=path,
                          data=base64.b64encode(data).decode(), mode=0o644)
            if not r.get("ok"):
                check("create %s" % path, False, r)
        check("the tree was created", not bad)

        deep = c.cmd(cmd="ls", path=BASE + "/sub/deep")
        check("the deepest level is there",
              [e["name"] for e in deep.get("entries", [])] == ["f3.txt"], deep.get("entries"))

        print("\n--- rmtree ---")
        r = c.cmd(cmd="rmtree", path=BASE)
        check("rmtree", r.get("ok"), r)
        check("the tree is gone", c.cmd(cmd="stat", path=BASE).get("ok") is False)
        check("nothing is left below it",
              c.cmd(cmd="stat", path=BASE + "/sub/deep/f3.txt").get("ok") is False)

    finally:
        print("\n--- verifying ---")
        if c.cmd(cmd="stat", path=BASE).get("ok"):
            print("     tree still present, removing what is left")
            c.cmd(cmd="rmtree", path=BASE)
        after = sorted(e["name"] for e in c.cmd(cmd="ls", path=PARENT).get("entries", []))
        check("the parent listing is unchanged", after == before, set(after) ^ set(before))
        c.cmd(cmd="readonly", on=True)
        print("read-only restored")

    print("\n%d passed, %d failed" % (len(ok), len(bad)))
    if bad:
        print("failed: " + ", ".join(bad))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
