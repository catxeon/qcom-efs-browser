#!/usr/bin/env python3
"""Read-only check of the NV item interface.

Reads a handful of items through both the legacy command (0x26) and the
indexed subsystem call, and reports the status the modem returns.  Nothing is
written, so the daemon stays in read-only mode throughout.

Item 550 holds the IMEI on Qualcomm targets; its digits are masked here so a
transcript of this run does not carry the device identity around.
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from efsctl import Client                                     # noqa: E402

ITEMS = [0, 5, 550, 551, 906, 1877, 4204, 6873, 9999]
IDENTIFYING = {0, 5, 550, 551}     # ESN / IMEI / SVN -- mask these


def digits_of(hexstr):
    """IMEI in NV 550 is packed BCD with swapped nibbles after a length byte."""
    raw = bytes.fromhex(hexstr)
    if not raw or raw[0] not in (8, 9):
        return None
    out = []
    for b in raw[1:1 + raw[0]]:
        out.append(b & 0x0F)
        out.append(b >> 4)
    return "".join(str(d) for d in out if d <= 9)


def main():
    c = Client()
    print("transport: %s\n" % c.cmd(cmd="open").get("transport"))

    print("%-7s %-8s %-16s %s" % ("ITEM", "STATUS", "MEANING", "DATA"))
    for item in ITEMS:
        r = c.cmd(cmd="nv_read", item=item)
        if not r.get("ok"):
            print("%-7d %-8s %-16s %s" % (item, "-", "no answer", r.get("error", "")[:60]))
            continue

        hexdata = r.get("data", "")
        if item in IDENTIFYING:
            d = digits_of(hexdata)
            shown = ("%d digits, %s…%s (masked)" % (len(d), d[:2], d[-2:])) if d else \
                    ("%s… (masked)" % hexdata[:4])
        else:
            trimmed = hexdata.rstrip("0")
            shown = (trimmed[:48] + "…") if len(trimmed) > 48 else (trimmed or "all zero")

        print("%-7d %-8d %-16s %s" % (item, r.get("status"), r.get("status_text"), shown))

    print("\n--- the indexed variant (subsystem NV), item 550 per SIM ---")
    for index in (0, 1):
        r = c.cmd(cmd="nv_read", item=550, index=index)
        if not r.get("ok"):
            print("index %d: %s" % (index, r.get("error", "")[:70]))
            continue
        d = digits_of(r.get("data", ""))
        print("index %d: status %d (%s), %s"
              % (index, r.get("status"), r.get("status_text"),
                 ("%d digits, %s…%s (masked)" % (len(d), d[:2], d[-2:])) if d else "no digits"))

    print("\nread-only stayed on for the whole run: %s"
          % c.cmd(cmd="version").get("readonly"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
