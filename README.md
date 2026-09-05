# Qualcomm EFS — an Android app for the modem's filesystem

[Download from releases](https://github.com/catxeon/qcom-efs-browser/releases)

A browser and editor for EFS2, the internal filesystem of a Qualcomm modem,
for Android. The app is two parts:

* **`daemon/`** — a small aarch64 helper, `qcom-efsd`, started as root, that
  speaks DIAG (the EFS2 subsystem) to the modem over the QRTR bus. It exposes an
  abstract unix socket `@qcom_efsd` with a line-delimited JSON protocol and an
  `SO_PEERCRED` uid check.
* **`android/`** — the app itself (Kotlin, Jetpack Compose). It never runs with
  elevated privileges; it only sends commands to the helper.

The architecture mirrors `qcom-band-menu` (a helper in assets → `su … setsid …
-uid <uid>` → an abstract socket → JSON), but the transport is different: that
project is QMI over QRTR, this is DIAG, because QMI offers no access to the
modem's filesystem.

---

## What it does

| Capability | How |
|---|---|
| Browse the EFS tree | `EFS2_DIAG_OPENDIR/READDIR/CLOSEDIR` |
| Metadata, symlinks | `LSTAT` falling back to `STAT` + `READLINK` |
| View a file (text/hex) | `OPEN/READ/CLOSE`; item files via `GET` |
| Edit a file in place | built-in text editor for XML and the like, hex editor for item files; up to 64 KiB |
| Save a file to the phone | read → cache → SAF (`CreateDocument`) |
| Upload/replace a file, with a type choice | ordinary file — `OPEN/WRITE/CLOSE`; item file — atomic `PUT` |
| `mkdir`, `chmod`, `rename`, `symlink`, delete file and tree | `MKDIR`, `CHMOD`, `RENAME`, `SYMLINK`, `UNLINK`, `RMDIR` |
| Read/write NV items | `0x26`/`0x27` and the indexed `0x4B 0x30 0x01/0x02`; writes need SPC (below) |
| Unlock NV writes | the Service Programming Code (`0x41`) raises the DIAG access level for the session |
| Flush the EFS2 journal | `SYNC_NO_WAIT` / `SYNC_GET_STATUS` (48/49) |
| Arbitrary DIAG packet | the `raw` command (hex → hex) |
| Bulk-import NV changes from a JSON file | the mtbtool v2 format — [docs/bulk-import-format.md](docs/bulk-import-format.md) |
| Diagnostics | helper log, receive stats, the transport it found |

**Read-only is on by default** — the helper refuses every command that would
change the modem until the lock in the toolbar is cleared by hand.

---

## Requirements

* Root (Magisk/KernelSU). The helper is started through `su`.
* DIAG over **QRTR**, service 4097. Modern phones ship no `/dev/diag` character
  device — the `diagchar` driver is not built into the kernel (the vendor
  partition usually runs `vendor.diag-router` instead) — and the modem publishes
  DIAG straight onto QRTR. Nothing to configure: the helper enumerates the bus
  and finds the node itself. SELinux is not touched.
* arm64. The helper builds for `aarch64` only (32-bit Qualcomm devices with a
  current EFS are essentially gone; add a second target in `daemon/build.sh` if
  you need one).
* Android 8.0+ (minSdk 26).

---

## Build

### In CI (easiest)

`.github/workflows/build.yml` installs the NDK, builds the helper, drops it into
`android/app/src/main/assets/`, and builds the debug APK. The artifact is under
Actions.

### Locally

```bash
export ANDROID_NDK_HOME=$HOME/Android/Sdk/ndk/27.2.12479018
bash daemon/build.sh          # drops qcom-efsd into the app's assets
cd android && gradle assembleDebug
```

The `:app` module runs `daemon/build.sh` itself before packaging when
`ANDROID_NDK_HOME` is set and the asset is missing or older than the sources. In
Android Studio, just open the `android/` directory (Studio creates the Gradle
wrapper).

### Signed release build

The Gradle config holds no keys — signing is done separately so nothing secret
can leak into the repository:

```bash
cd android && gradle assembleRelease
BT=$ANDROID_HOME/build-tools/35.0.0
$BT/zipalign -p -f 4 app/build/outputs/apk/release/app-release-unsigned.apk aligned.apk
$BT/apksigner sign --ks <keystore>.jks --ks-key-alias <alias> --out qcom-efs-browser.apk aligned.apk
$BT/apksigner verify --print-certs qcom-efs-browser.apk
```

At `minSdk 26` the v2/v3 schemes are enough; v1 (JAR) is not needed. The release
build goes through R8 and carries no `debuggable` flag.

---

## Helper protocol

One JSON object per line is a request; one line is the reply. Every reply
carries `ok`; on failure it carries `error` and, for EFS operations,
`efs_errno`.

```jsonc
{"cmd":"open"}                                  // connect, detect EFS2
{"cmd":"ls","path":"/"}
{"cmd":"stat","path":"/nv/item_files"}
{"cmd":"read","path":"/policyman/carrier_policy.xml"}          // inline base64
{"cmd":"read","path":"/big.bin","out":"/data/.../cache/x.bin"} // to a file
{"cmd":"write","path":"/foo","src":"/data/.../upload.bin","mode":420}
{"cmd":"write","path":"/foo","data":"…","item":true}   // an item file, not an ordinary one
{"cmd":"mkdir","path":"/foo","mode":511}
{"cmd":"unlink","path":"/foo/bar"}
{"cmd":"rmtree","path":"/foo"}
{"cmd":"chmod","path":"/foo","mode":420}
{"cmd":"symlink","target":"/a","link":"/b"}
{"cmd":"rename","from":"/a","to":"/b"}
{"cmd":"stat","path":"/link","follow":true}   // attributes of the target, not the link
{"cmd":"pull_tree","path":"/","out":"/data/.../cache/tree","max_file":8388608}
{"cmd":"image","path":"/","out":"/data/.../cache/efs.tar"}
{"cmd":"nv_read","item":550}
{"cmd":"nv_write","item":550,"data":"00ff…"}
{"cmd":"spc","spc":"000000"}          // raise the DIAG access level; unlock NV writes
{"cmd":"ssr"}                          // Xiaomi-only modem subsystem restart; refused while read-only
{"cmd":"raw","hex":"4b1300000000"}
{"cmd":"raw","hex":"…","match":false} // return the first frame that arrives, whatever it is
{"cmd":"readonly","on":false}
{"cmd":"stats"} {"cmd":"close"} {"cmd":"shutdown"}
```

The daemon refuses to start without `-uid <uid>` and serves only connections
from that uid, so a running instance cannot be hijacked by another app.

---

## Tests

`-mock <path>` makes the daemon talk to a unix socket (`SOCK_SEQPACKET`) that
uses the same QRTR framing instead of the real bus. The end-to-end test is built
on it.

```bash
gcc -std=gnu11 -O2 -Wall -Wextra -o /tmp/qcom-efsd-host daemon/src/util.c daemon/src/diag.c daemon/src/efs2.c daemon/src/ssr.c daemon/src/main.c
python3 daemon/test/run_tests.py /tmp/qcom-efsd-host
```

`daemon/test/mock_modem.py` is a fake modem: EFS2 and NV wrapped in QRTR frames
over a socket, with an in-memory file tree. The packet *field layouts* are
transcribed from qfenix's sources — responses are built the way qfenix parses
them and requests are parsed the way qfenix builds them, cross-checking against
an implementation known to work on real hardware. The *opcode numbers*, though,
are the classic ones (libopenpst/EfsTools), verified against a live SM8350 — not
qfenix's (see below).

The mock reproduced qfenix's mistakes too, which is why two things slipped
through and only turned up on hardware: the end-of-directory marker and the
delete opcode numbering. Both are baked into the mock now, so a regression is
caught.

`daemon/test/run_tests.py` starts the mock and the daemon and drives the daemon
over the same socket and the same JSON the app uses: listing and entry types,
stat/readlink, reading a file longer than one packet, the item-file fallback,
recursive pull, the "modem" tar, the read-only guard, chunked writes, `PUT` for
an item file, mkdir/chmod/symlink/rmtree, NV items, sync, an arbitrary packet.
It also checks both datagram shapes seen on hardware: run with datagram_header
on, the mock prefixes every answer with the 4-byte header SM8850 adds, and the
test confirms the session still comes up and the data arrives intact. A silent
modem, a `shutdown` that must actually terminate the process, a security-locked
NV write, and the SPC unlock are covered too.

What the test does not cover: the availability of the QRTR bus and the modem's
real answers — only hardware. 75 end-to-end checks against the mock.

### Debugging on a live device without the app

The helper can be run by hand and driven from a computer; the socket is
forwarded over adb:

```bash
adb push qcom-efsd /data/local/tmp/efsd
adb shell su -c "setsid /data/local/tmp/efsd -uid 2000 -socket qcom_efsd_dbg -verbose &"
adb forward tcp:9999 localabstract:qcom_efsd_dbg
python3 daemon/tools/efsctl.py ls /
python3 daemon/tools/efsctl.py cat /policyman/policies.xml
```

`-uid 2000` because adbd (the shell) is what connects through `adb forward`.
Alongside it are `tools/qrtr_probe.c` (the services on the QRTR bus) and
`tools/qrtr_diag_probe.c` (a sweep of DIAG-exchange formats), which is how the
transport was found on SM8350.

---

## SELinux

The app **does not touch SELinux**. Enforcing is never lowered, the policy is
never extended, and no file outside the app's own directories is written. The
mode is read once at connect time, only to show it in Diagnostics.

An earlier version could briefly drop the system to permissive; that was for
`/dev/diag`, which some firmware closes to the `su` domain. Together with
`/dev/diag` itself, that machinery is gone — QRTR does not need it: the `su`
domain of Magisk and KernelSU is unrestricted, and the `AF_QIPCRTR` socket opens
under enforcing with no caveats. Verified on SM8350 and SM8850.

If some firmware does close this path too, the right fix is a narrow live rule,
not a global permissive; with Magisk that is
`magiskpolicy --live "allow magisk self qipcrtr_socket { create bind read write }"`
(fill in the domain and class from the denial in `dmesg`/`logcat`). It lasts
until reboot.

---

## How it works inside

**Transport.** The helper enumerates the QRTR bus, finds service `4097` on a
node other than its own (that is the modem; instance 1 is its command endpoint),
and talks to it there. The request goes out as a single datagram with no
framing; the answer comes back wrapped:

```
SM8350   7E 01 <len16> <payload> 7E
SM8850   08 00 <len16 of the rest>   7E 01 <len16> <payload> 7E
```

Targets newer than SM8350 add a further 4 bytes in front — `[u16 type = 8]` and
`[u16 length of the rest of the datagram]`. The helper strips that header only
when its length field accounts for exactly the rest of the datagram and the DIAG
wrapper really follows, so a payload that happens to begin with those bytes is
never mistaken for a header. There is no CRC and no escaping here — this is the
QRTR wrapper, not HDLC.

The transport needs no setup: no ioctls, no logging modes, no privileges beyond
what root already has.

Verified on an ASUS I007D (SM8350, Android 11) and a Galaxy S26 Ultra (SM8850,
Android 16). On both, `diagchar` is absent from the kernel; there is no
`/dev/diag` at all, which is why that path was removed from the helper.

Run on SM8350: listing and entry types, `stat`/`readlink`, reading files longer
than one packet, the item interface, a recursive backup (188 directories, 1270
files), `statfs`, `mkdir`, `chmod`, write, `rename`, `symlink`, `unlink`,
`rmdir`, `rmtree`, NV-item reads (including indexed, per SIM) — and the same
through the app's UI: create a directory, upload a file through the system file
picker, delete a file, recursively delete a directory, the NV console. Each
check is reversible and verified by a separate daemon session; after everything,
the EFS root matched the original. On SM8850, all of the read paths plus the
same reversible writes were run — including creating and deleting files,
directories, item files, and a same-value NV write after the SPC — and again the
root was unchanged.

**NV-item writes need the SPC.** Until it is unlocked, the modem answers `0x27`
with `42 27 <item16> 00…` — `DIAG_BAD_SEC_MODE_F`, "the current access level does
not allow this command"; the indexed write `0x4B 0x30 0x02` is the same. This is
not a helper defect: the command arrives, the modem understands it, and it
refuses on access level.

The level is raised with the **Service Programming Code** — command `0x41` with
six ASCII digits (`000000` on most devices). The modem answers `41 01` on
success and `41 00` on a wrong code; the raised level lasts for the session.
This is the standard, Qualcomm-documented mechanism — the SPC changes nothing by
itself, it only lifts the block on a subsequent write. In the app the NV console
has an "SPC" field and an "Unlock" button; in the protocol it is the `spc`
command. Verified on SM8350 and SM8850: `spc 000000` → `unlocked: true`, after
which the `0x27` write goes through with status 0 (reversibly, writing back the
same value that was read).

A `0x42` refusal used to look like a "timeout": `matches()` accepted only the
codes `0x13`–`0x18` as answers, the `0x42` frame was discarded as unsolicited,
and the call honestly waited four seconds before lying about the reason. Now
`0x42` with an echo of the rejected command is recognised and turned into a
readable error at once.

**EFS2.** Packets are `[0x4B][subsys][cmd_lo][cmd_hi][body]`. The subsystem is
detected automatically: `0x3E` (Quectel/Foxconn) is tried first, then the
standard `0x13`.

A few non-obvious things a naive implementation gets wrong:

* the `open` flags are **POSIX**, not the ARM ABI: `O_CREAT = 0x40`. With
  `0x100`, overwriting an existing file works but creating a new one silently
  does not;
* `mkdir`/`chmod` take `mode` as an **int16**, not int32;
* `PUT` (38) puts the data at offset **14**, and the `errno` in the reply is at
  offset **6** and is also 16-bit; the legacy `PUT` (26) hangs some modems, so
  there is no fallback to it;
* item files (`/nv/item_files/…`, `/nv/reg_files/…`) are invisible to a plain
  `open`, are read through `GET` (39, falling back to 27) and created with
  `O_ITEMFILE`; `O_ITEMFILE | O_AUTODIR` crashes some modems, so parent
  directories are created up front;
* EFS2 is journalled: a burst of quick writes hits `ENOSPC` even when there is
  room. `SYNC` ("Flush EFS journal") clears it.

Three more, found on hardware:

* **The end of a directory is an entry with an empty name, not
  `entry_type == 0`.** qfenix stops enumerating at `entry_type == 0`, but on
  SM8350 zero means "regular file" (1 is a directory, 15 an item file). With
  that check the root listing broke off at 8 of 40 entries, at the first file.
* **The helper cannot be linked statically.** An NDK static binary gets a TLS
  segment aligned to 8 bytes, which bionic on Android 11 and older refuses to
  start (`executable's TLS segment is underaligned`). Dynamic linking avoids it
  and shrinks the binary from 470 KB to 45 KB.
* **Unpacking the helper has to start with `unlink`.** If the previous instance
  is still executing from the same file, rewriting it fails with `ETXTBUSY`;
  deleting it first is allowed — the old process keeps the old inode.
* `FS_IMAGE` (a modem-side tar) is not supported everywhere: SM8350 and SM8850
  both reject opcode 54, so the backup there is done by a file-by-file walk.
* **`LSTAT` (16) is not on every modem, and `STAT` follows symlinks** — like
  POSIX `stat()`. On SM8350 opcode 16 is unimplemented, so a link looked like a
  regular file. The helper tries `LSTAT`, falls back to `STAT`, then probes the
  path with `READLINK`, which errors on anything that is not a link, so a
  success unambiguously identifies a symlink. The `stat` reply carries a
  `stat_call` field showing which call answered.
* Modems lay out the `STATFS` reply differently, so the fields are not read at a
  fixed offset: the helper looks for the first "block size (a power of two) +
  a plausible block count" pair. The raw reply is always returned as well.

The field layouts come from [qfenix](https://github.com/iamromulan/qfenix)
(BSD-3-Clause), where they were obtained on real modems. The code here is
written from scratch.

**But qfenix's opcode numbering must not be used.** Its header has
`7 = UNLINK, 8 = RMDIR, 14 = READLINK`, whereas a live SM8350 uses the classic
scheme (as in libopenpst and EfsTools):

```
6 symlink   7 readlink   8 unlink   9 mkdir   10 rmdir   14 rename
```

With qfenix's numbering the modem answers `EINVAL` to every delete — because 7
is readlink, and readlink on an ordinary file is invalid — and, far worse, a
"readlink" lands on opcode 14, which is **RENAME**. Confirmed by experiment:
after switching to the classic numbers, a file and a directory deleted with
`errno=0`.

---

## Caution

EFS holds radio calibration, the IMEI, keys and provisioning. Deleting or
corrupting files under `/nv/`, `/rfnv/`, `/policyman/` makes the modem
unusable, and some of that data cannot be recovered without a backup from
**this specific** device.

A sensible order of work: connect → back up `/` both ways (the modem's tar and
the file-by-file zip) → save both off the device → and only then clear the
read-only lock.

The app makes no attempt to edit the IMEI and contains no carrier-lock bypass;
it is a tool for viewing, backing up, and restoring your own firmware.

---

## Structure

```
daemon/
  build.sh              builds the aarch64 binary with the NDK
  src/diag.c|h          transport: DIAG over QRTR
  src/efs2.c|h          EFS2 operations and NV items
  src/ssr.c|h           Xiaomi modem restart via the vendor QMI service
  src/main.c            the unix socket, the JSON protocol, recursive operations
  test/mock_modem.py    a fake modem: EFS2 + NV in QRTR frames over a socket
  test/run_tests.py     the end-to-end test of the daemon against the mock
  tools/qrtr_probe.c    the services on the QRTR bus
  tools/qrtr_diag_probe.c  a sweep of DIAG-over-QRTR exchange formats
  tools/efsctl.py       a client to the daemon over adb forward, without the app
  tools/write_test.py           a reversible write check on a live modem
  tools/rename_symlink_test.py  the same for rename, symlink and readlink
  tools/rmtree_test.py          the same for recursive delete
  tools/nv_read_test.py         reading NV items (read-only)
  tools/nv_write_test.py        a reversible NV-item write check
  src/util.c|h          string buffer, JSON, base64/hex
android/
  app/src/main/assets/qcom-efsd     (dropped in by the helper build)
  app/src/main/java/dev/qcom/efs/
    RootDaemon.kt       unpacks and starts the helper through su
    EfsClient.kt        the abstract-socket client
    EfsRepository.kt    operations + SAF + zip
    MainViewModel.kt    screen state
    Ui.kt               the Compose interface
```

## License

BSD 3-Clause — see [LICENSE](LICENSE). Where the packet-format knowledge comes
from is in [NOTICE](NOTICE).

## Credits

* [qfenix](https://github.com/iamromulan/qfenix) (BSD-3-Clause) — the EFS2/NV
  field layouts and several details that would otherwise have to be found on
  hardware: the POSIX `open` flags, the 16-bit `mode` fields, the offsets in the
  `PUT` reply. The opcode numbering, on the other hand, had to come from
  elsewhere — see above.
* [libopenpst](https://github.com/openpst/libopenpst) and EfsTools — the classic
  EFS2 opcode numbering, which is what shipping modems agree with.
* [QCSuper](https://github.com/P1sec/QCSuper), MobileInsight's `diag_revealer` —
  for documenting how DIAG works on Android. Their `/dev/diag` container framing
  was reimplemented in earlier versions of the helper; that transport has since
  been dropped in favour of DIAG over QRTR. No code was copied.
* [Qualcomm QMI Band Control](https://github.com/Fronsipswu/qcom-band-menu) — the "root daemon + abstract socket + Compose client" shape.
