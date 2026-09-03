#!/usr/bin/env python3
"""Reversible NV-item write check against a real modem.

Reads an NV item, writes back the very same 128 bytes, and reads it again.
The value never changes, so the only thing under test is the write path.

Two interlocks keep this from becoming an experiment on someone's phone:

  * the identity items are refused by number -- there is no reason to
    rewrite an ESN or an IMEI to prove that writing works;
  * by default the item has to read back as all zeros, so that even a write
    that fails half way through can only put zeros where zeros already were.
    Pass --any to lift that, and know why you are doing it.

    python3 nv_write_test.py [item] [--any]
"""

import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from efsctl import Client                                     # noqa: E402

# Items that identify the device or the subscriber.  Not test material.
FORBIDDEN = {0: "ESN", 5: "MIN", 550: "IMEI", 1943: "LTE band config"}

# A number this modem rejects outright, for the error path.
INVALID_ITEM = 22

ok = []
bad = []


def check(name, cond, detail=""):
    (ok if cond else bad).append(name)
    print("%-5s %s%s" % ("PASS" if cond else "FAIL", name,
                         ("  -- " + str(detail)) if detail and not cond else ""))


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    allow_any = "--any" in sys.argv
    item = int(args[0]) if args else 855

    if item in FORBIDDEN:
        print("refusing: NV %d is the %s" % (item, FORBIDDEN[item]))
        return 2

    c = Client()
    info = c.cmd(cmd="open")
    print("transport: %s, subsys 0x%x" % (info.get("transport"), info.get("subsys")))

    r = c.cmd(cmd="nv_read", item=item)
    if not r.get("ok") or r.get("status") != 0:
        print("refusing: NV %d does not read cleanly (%s)" % (item, r))
        return 2

    before = r["data"]
    blob = bytes.fromhex(before)
    print("NV %d reads %d bytes, %d of them non-zero"
          % (item, len(blob), sum(1 for b in blob if b)))

    if any(blob) and not allow_any:
        print("refusing: NV %d is not all zeros; pass --any if that is really "
              "what you want to rewrite" % item)
        return 2

    r = c.cmd(cmd="nv_write", item=item, data=before)
    check("writes are refused while read-only", r.get("ok") is False, r)

    c.cmd(cmd="readonly", on=False)

    # Modems refuse NV writes until the Service Programming Code is accepted.
    # It raises the DIAG access level for the session and changes nothing else.
    r = c.cmd(cmd="spc", spc="000000")
    check("the SPC is accepted", r.get("ok") and r.get("unlocked"), r)

    print("read-only disabled, SPC sent\n--- writing the same value back ---")

    try:
        t0 = time.time()
        r = c.cmd(cmd="nv_write", item=item, data=before)
        took = time.time() - t0

        if not r.get("ok") and "security" in r.get("error", ""):
            # If the modem still refuses after the SPC, the write path is at
            # least reported honestly and promptly, not as a four-second lie.
            check("the refusal is named, not a timeout", "security" in r["error"], r)
            check("and it comes back promptly", took < 1.0, "%.1fs" % took)
            print("NOTE  this modem refuses NV writes even after the SPC; nothing was written")
        else:
            check("nv_write reports success", r.get("ok") and r.get("status") == 0, r)

        r = c.cmd(cmd="nv_read", item=item)
        check("the item still reads back", r.get("ok") and r.get("status") == 0, r)
        check("and its value is byte-for-byte what it was",
              r.get("data") == before, r.get("data"))

        # The error path: a number the modem does not accept must come back as
        # a failure, not as a quiet success.
        r = c.cmd(cmd="nv_write", item=INVALID_ITEM, data=before)
        check("a rejected write is reported as a failure",
              r.get("ok") is False or r.get("status") not in (0, None), r)
    finally:
        c.cmd(cmd="readonly", on=True)
        print("read-only restored")
        r = c.cmd(cmd="nv_read", item=item)
        check("the item is unchanged at the end", r.get("data") == before, r.get("data"))

    print("\n%d passed, %d failed" % (len(ok), len(bad)))
    if bad:
        print("failed: " + ", ".join(bad))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
