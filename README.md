# Qualcomm EFS — an Android app for the modem's filesystem

[Download from releases](https://github.com/catxeon/qcom-efs-browser/releases)

A browser and editor for EFS2, the internal filesystem of a Qualcomm modem,
for Android. It is intended for inspecting modem configuration, exporting
files and applying known EFS/NV changes. The `/` shown in the browser is the
modem's root directory, separate from the Android filesystem.

The app is two parts:

* **`daemon/`** — a small aarch64 helper, `qcom-efsd`, started as root, that
  speaks DIAG to the modem over QRTR, with `/dev/diag` as a fallback. It exposes an
  abstract unix socket `@qcom_efsd` with a line-delimited JSON protocol and an
  `SO_PEERCRED` uid check.
* **`android/`** — the app itself (Kotlin, Jetpack Compose). It never runs with
  elevated privileges; it only sends commands to the helper.

The architecture follows the helper/client pattern used by `qcom-band-menu`:
a bundled helper → `su … setsid … -uid <uid>` → an abstract socket → JSON.
Here, EFS2 and NV operations use Qualcomm DIAG.

---

## What it does

| Capability | How |
|---|---|
| Browse the EFS tree | folder navigation, search within the current folder, sorting by name/size/date; `EFS2_DIAG_OPENDIR/READDIR/CLOSEDIR` underneath |
| Metadata, symlinks | `LSTAT` falling back to `STAT` + `READLINK` |
| View a file (text/hex) | inline detail sheet; `OPEN/READ/CLOSE`, with `GET` for item files; inline reads up to 512 KiB |
| Edit a file in place | built-in text editor for XML and the like, hex editor for item files; up to 64 KiB |
| Save a file to the phone | read → cache → SAF (`CreateDocument`) |
| Upload/replace a file, with a type choice | ordinary file — `OPEN/WRITE/CLOSE`; item file — atomic `PUT` |
| Create folders, change permissions, delete files and trees | `MKDIR`, `CHMOD`, `UNLINK`, `RMDIR`; the helper protocol also supports `rename` and `symlink`, which have no dedicated UI actions |
| Back up a folder | file-by-file ZIP export, or a modem-generated TAR where `FS_IMAGE` is supported |
| Read/write NV items | `0x26`/`0x27` and the indexed `0x4B 0x30 0x01/0x02`; writes need SPC (below) |
| Unlock NV writes | the Service Programming Code (`0x41`) raises the DIAG access level for the session |
| Flush the EFS2 journal | `SYNC_NO_WAIT` / `SYNC_GET_STATUS` (48/49) |
| Restart the modem | native DIAG request `4b250300`, with journal sync when needed and session recovery |
| Reconnect | restart the helper, reconnect to the modem and restore the current folder where possible; read-only is reset to on |
| Arbitrary DIAG packet | the `raw` command (hex → hex) |
| Tell you a new version is out | asks the GitHub releases API for the newest tag and offers it once, with a "skip this version" |
| Bulk-import EFS item changes from JSON | mtbtool v2 format: `sim0`/`sim1`/`dualsim` blocks containing writes and deletions; preview and per-operation results |
| Disable NR5G capabilities (UL MIMO, UL/DL CA, DSS, …) | 14 quick toggles (12 ported from mtbtool's features screen, plus TDD-only UL MIMO and NR 1024QAM TDD); per-SIM, with a confirm dialog warning to back up EFS first (one-way toggle) |
| Diagnostics | helper log, selected transport and SELinux state; verbose mode adds a QRTR service scan; receive counters are available through the helper's `stats` command |

**Read-only is on by default.** The helper gates writes, SPC, raw packets,
journal sync and modem restart. Enable writes with the toolbar lock or the
write-enable actions in the dialogs. Choosing **Restart modem** while locked
offers **Enable writes and restart**; accepting also enables other write
operations and leaves writes enabled afterwards.

### Saving changes and restarting

The app requests a journal sync after saving or importing a file, creating a
directory, changing permissions, deleting an entry, writing an NV item or
successfully applying a feature toggle. Bulk import syncs once after the batch
if at least one operation succeeded and the connection was not lost. A sync
failure is reported separately from the write that already happened.

The helper tracks possible pending changes. `ssr` first syncs a dirty journal
and aborts if starting that sync fails. It then sends `4b250300`, closes the old
DIAG session and reconnects. On QRTR it watches service announcements; without
that watcher, including `/dev/diag`, it waits eight seconds before retrying the
session. If recovery fails, use **Reconnect** from the menu.

Sync completion is best-effort: on the tested SM8350 the status query does not
reliably report completion. The helper requires the sync-start request to
succeed, then polls briefly. This is not a universal guarantee that flash has
finished committing on every modem.

Sending `4b250300` through **Raw DIAG packet** does not run the journal-sync and
reconnection sequence. Use **Restart modem** for that workflow. Direct helper
clients must request `sync` after their own changes; individual write commands
do not automatically sync.

### Feature toggles and bulk import

Feature toggles are per-SIM, one-way changes to specific EFS item files. After
a successful change, the app rereads all feature states: some toggles share a
file, so applying one can change the state of another. The displayed state is
derived from file contents, not from a live UE capability measurement. There
is no automatic restore of the original values.

The NR 1024QAM TDD toggle clears byte index 29 of `cap_feature_band_nr_v2` while
preserving the other existing bytes. An absent or shorter item is filled with
`FF` to at least 32 bytes before clearing that byte. Unit tests cover this byte
transformation; its effect on modem capabilities and these default-value
assumptions have not been independently verified on hardware.

Bulk import accepts the mtbtool JSON dialect with top-level `sim0`, `sim1` or
`dualsim` objects. Each contains directory keys and filenames with `op: "w"`
and a non-empty hex `data` string, or `op: "d"`. SIM1 paths receive the
`_Subscription01` suffix. The app previews the operations, performs an SPC
pre-flight when writes are present, then runs them in order. Ordinary failures
are recorded and execution continues; loss of the helper connection skips the
remaining operations. There is no batch rollback.

### Backup limits

The ZIP option walks the selected subtree and copies readable files. The app
uses an 8 MiB limit per file; skipped or unreadable entries are reported in the
summary and log. Symlink targets are exported as `.symlink` text files. This
is a file export, not a raw partition image or a complete restore package;
empty directories and original filesystem metadata are not preserved by the
ZIP exporter.

The modem-generated TAR is a separate option and may be unsupported. There is
no automatic fallback from TAR to ZIP and no whole-archive restore action.

---

## Requirements

* Root (Magisk/KernelSU). The helper is started through `su`.
* A Qualcomm modem with accessible DIAG and EFS2 services. Root alone does not
  supply a missing kernel driver or bypass restrictions imposed by the modem.
* **QRTR**, service 4097, is tried first. The helper discovers the modem's
  endpoint and skips DIAG services belonging to other processors. This path
  works on the tested SM8350 and SM8850 devices without `/dev/diag`.
* **`/dev/diag`** is tried if opening the QRTR transport fails. This covers the
  tested X55 setup on Poco F3 (Snapdragon 870), where modem DIAG is routed over
  PCIe through `diagchar` and is absent from QRTR. Driver and firmware support
  still matter; a successful QRTR connection followed by an EFS2 handshake
  failure does not trigger a second attempt through `/dev/diag`.
* arm64. The bundled helper builds for `aarch64` only.
* Android 8.0+ (minSdk 26).
* `INTERNET`, and only for the update check. The app asks
  `api.github.com/repos/catxeon/qcom-efs-browser/releases/latest` for the newest
  tag, sends nothing about the phone or the modem, and stays silent when it is
  offline. Nothing else in the app touches the network; deny it and everything
  but that one dialog still works.

---

## Build

### In CI (easiest)

`.github/workflows/build.yml` checks that app and helper versions match, runs
the host daemon tests, builds the aarch64 helper with the NDK, runs Android
unit tests and builds the debug APK. The APK and helper artifact are under
Actions.

### Locally

The build used for releases uses JDK 17, Gradle 8.9, Android SDK platform 35,
build-tools 35.0.0 and NDK 27.2.12479018. Set `ANDROID_HOME` to your SDK directory.

```bash
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/27.2.12479018"
bash daemon/build.sh          # drops qcom-efsd into the app's assets
gradle --project-dir android :app:testDebugUnitTest assembleDebug
```

The `:app` module runs `daemon/build.sh` itself before packaging when
`ANDROID_NDK_HOME` is set and the asset is missing or older than the sources. In
Android Studio, open the `android/` directory. The repository does not include
a Gradle wrapper; the command above uses an installed Gradle. Build the helper
explicitly before release packaging: without the NDK environment variable,
Gradle can warn about a missing/stale helper and still continue.

### Signed release build

The Gradle config holds no keys — signing is done separately so nothing secret
can leak into the repository:

```bash
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/27.2.12479018"
bash daemon/build.sh
gradle --project-dir android :app:testDebugUnitTest assembleRelease
BT="$ANDROID_HOME/build-tools/35.0.0"
"$BT/zipalign" -p -f 4 android/app/build/outputs/apk/release/app-release-unsigned.apk aligned.apk
"$BT/apksigner" sign --ks /path/to/release.jks --ks-key-alias release --out qcom-efs-browser.apk aligned.apk
"$BT/apksigner" verify --verbose --print-certs qcom-efs-browser.apk
```

Replace the example keystore path and alias with your own. Signing prompts for
the password; keep the key and password outside the repository. Use the same
signing key as the installed app when producing an update.

At `minSdk 26` the v2/v3 schemes are enough; v1 (JAR) is not needed. The release
build goes through R8 and carries no `debuggable` flag.

For a new release, increment `versionCode` and update `versionName` in
`android/app/build.gradle.kts`, and set `QEFSD_VERSION` in `daemon/src/main.c`
to the same version. CI rejects differing app/helper versions.

---

## Helper protocol

One JSON object per line is a request; one line is the reply. Every reply
carries `ok`; on failure it carries `error` and, for EFS operations,
`efs_errno`.

```jsonc
{"cmd":"open"}                                  // connect, detect EFS2
{"cmd":"readonly","on":false}                  // required before mutations, SPC, sync, raw or ssr
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
{"cmd":"nv_read","item":550,"index":1}          // indexed NV access
{"cmd":"nv_write","item":550,"data":"00ff…"}
{"cmd":"spc","spc":"000000"}          // raise the DIAG access level; unlock NV writes
{"cmd":"sync"}                        // commit pending EFS changes; skipped when the journal is clean
{"cmd":"ssr"}                          // native Qualcomm modem restart (DIAG subsys 0x25/cmd 3); refused while read-only
{"cmd":"raw","hex":"4b1300000000"}
{"cmd":"raw","hex":"…","match":false} // return the first frame that arrives, whatever it is
{"cmd":"readonly","on":true}
{"cmd":"stats"}
{"cmd":"close"}
{"cmd":"shutdown"}
```

These are individual command examples, not an import script. `write.data` is
base64; `nv_write.data` and `raw.hex` are hex. The `open` response includes
`transport`, `subsys`, `readonly` and `max_inline` (currently 512 KiB).

A successful `ssr` send returns `ok: true` with a separate `reconnected` flag;
if session recovery failed, the response also includes an `error`. Check
`reconnected` before issuing further EFS commands. `close` closes the modem
session; `shutdown` also terminates the helper.

The daemon refuses to start without `-uid <uid>` and accepts clients only when
their `SO_PEERCRED` UID matches that value. This excludes ordinary apps with
different UIDs; it is not a security boundary against another root process.

---

## Tests

`-mock <path>` makes the daemon talk to a Unix socket (`SOCK_SEQPACKET`) instead
of real hardware. `-mock-framing qrtr|chardev|mdm` selects QRTR, local
`/dev/diag`, or external-modem `/dev/diag` framing. The suite exercises all
three.

```bash
gcc -std=gnu11 -O2 -Wall -Wextra -Wno-unused-parameter -fno-strict-aliasing -Werror -o /tmp/qcom-efsd-host daemon/src/util.c daemon/src/diag.c daemon/src/efs2.c daemon/src/main.c
python3 daemon/test/run_tests.py /tmp/qcom-efsd-host
gradle --project-dir android :app:testDebugUnitTest
```

`daemon/test/mock_modem.py` is a fake modem with EFS2, NV and an in-memory file
tree, wrapped in the selected transport framing. The packet *field layouts* are
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
NV write, and the SPC unlock are covered too. The `/dev/diag` cases inject
unsolicited traffic and replies from the wrong processor to check filtering.

The native `ssr` test checks the write gate, sync-before-restart ordering and
session recovery. Android unit tests cover bulk-import parsing, feature
payloads/checking, sorting/filtering, byte colors and version comparison.
For v1.4.8, 223 daemon checks and 89 Android unit tests passed.

Mocks do not validate real QRTR discovery, kernel ioctl compatibility, actual
flash persistence or the effect of NV changes on advertised radio capabilities.
These require hardware testing. v1.4.8 was built and tested locally and in CI,
but was not tested on a physical device for that release.

### Debugging on a live device without the app

The helper can be run by hand and driven from a computer; the socket is
forwarded over adb:

```bash
adb push android/app/src/main/assets/qcom-efsd /data/local/tmp/efsd
adb shell "su -c 'chmod 700 /data/local/tmp/efsd'"
adb shell "su -c 'setsid /data/local/tmp/efsd -uid 2000 -socket qcom_efsd_dbg -verbose </dev/null >/data/local/tmp/efsd.log 2>&1 &'"
adb forward tcp:9999 localabstract:qcom_efsd_dbg
python3 daemon/tools/efsctl.py ls /
python3 daemon/tools/efsctl.py cat /policyman/policies.xml
python3 daemon/tools/efsctl.py shutdown
adb forward --remove tcp:9999
```

`-uid 2000` because adbd (the shell) is what connects through `adb forward`.
With `-verbose` the helper scans available QRTR services as it connects and
groups them by node, distinguishing modem DIAG from other processors' DIAG.
In the app, enable **Verbose helper log** before connecting; **Diagnostics**
shows the log and the selected transport. A missing modem DIAG service on QRTR
can be expected on an external-modem setup, so also check the `/dev/diag`
fallback result. Alongside it is `daemon/tools/qrtr_diag_probe.c` (a sweep of
DIAG-exchange formats), which is how the transport was found on SM8350.

---

## SELinux

The app **does not change SELinux mode or policy**, on either transport. It
reads the mode at connection time to display it in Diagnostics. The
`/dev/diag` fallback changes DIAG routing through driver ioctls; it does not
enable permissive mode or install policy rules.

The app stores its helper, logs and working files in its own directories;
user-requested exports go to the location selected through Android's file
picker. Modem writes go through DIAG. It does not patch Android system
partitions. The manual adb example above uses `/data/local/tmp` separately.

Operation under enforcing has been reported on the tested SM8350, SM8850 and
Poco F3/X55 setups. This does not establish compatibility with every custom
kernel or policy. For a connection failure, inspect the verbose transport log
and any relevant denial messages before attributing it to SELinux; missing
DIAG endpoints or drivers are separate causes.

---

## How it works inside

**QRTR transport.** The helper enumerates service `4097`, skips its own node
and instances attributed to other processors, and prefers modem instance 1.
A remote node alone is not enough to identify the modem. If opening this
transport fails, it tries `/dev/diag`. The QRTR request goes out as a single
datagram with no framing; the answer comes back wrapped:

```
SM8350   7E 01 <len16> <payload> 7E
SM8850   08 00 <len16 of the rest>   7E 01 <len16> <payload> 7E
```

The tested SM8850 adds a further 4 bytes in front — `[u16 type = 8]` and
`[u16 length of the rest of the datagram]`. The helper strips that header only
when its length field accounts for exactly the rest of the datagram and the DIAG
wrapper really follows, so a payload that happens to begin with those bytes is
never mistaken for a header. There is no CRC and no escaping here — this is the
QRTR wrapper, not HDLC.

The QRTR path needs no DIAG logging-mode ioctls. Access still depends on the
device's kernel and security policy.

Verified on an ASUS I007D (SM8350, Android 11) and a Galaxy S26 Ultra (SM8850,
Android 16). On both, `diagchar` is absent from the kernel and there is no
`/dev/diag` at all.

Only the modem's own endpoint is used. A DIAG instance reads as
`64 * processor + channel`, processor 0 being the modem: the modem answers on
instance 1, while on an SM8250 the audio, sensor and compute DSPs publish 65,
257 and 385. A DSP speaks DIAG but has no EFS behind it, so those are skipped.
(This is read off the numbers seen on real phones, not taken from
documentation.)

### `/dev/diag`, for an external modem

On the tested Poco F3 (Snapdragon 870), the X55 modem connects over PCIe. Its
QMI services are on the QRTR bus but its DIAG is not: the kernel's `diagchar`
driver owns the modem's DIAG channel and exposes it through `/dev/diag` as a
remote processor. On QRTR transport failure, the helper opens `/dev/diag`,
asks the driver which remote processors exist
(`DIAG_IOCTL_REMOTE_DEV`), switches its own descriptor to memory-device mode
for the phone and the external modem, and frames requests the way `diagchar`
expects: `[u32 type = 0x20][i32 token = -1][HDLC frame]`, the token addressing
the external modem. Answers come back as `[u32 type][i32 count]` followed by
entries, each `[len][data]`, or `[token][len][data]` when they come from the
external modem; only those are taken, since the phone's own processors keep
talking through the same device. HDLC frames use escaping and CRC-16/X.25,
unlike the QRTR wrapper. Closing the session closes the device descriptor;
cleanup of the driver's logging state is left to the driver.

Tested against the mock in both forms (the phone's own modem and an external
one, with decoy replies from the wrong side mixed in). A tester confirmed
connection and EFS directory listing on a Poco F3/X55 under enforcing for the
v1.4.7 work. Reading file contents, writes and modem restart through this path
have not been confirmed on that hardware in the recorded testing.

### Recorded hardware coverage

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

### NV access and EFS2 details

**NV-item writes need the SPC.** Until it is unlocked, the modem answers `0x27`
with `42 27 <item16> 00…` — `DIAG_BAD_SEC_MODE_F`, "the current access level does
not allow this command"; the indexed write `0x4B 0x30 0x02` is the same. This is
not a helper defect: the command arrives, the modem understands it, and it
refuses on access level.

The level is raised with the **Service Programming Code** — command `0x41` with
six ASCII digits (`000000` on most devices). The modem answers `41 01` on
success and `41 00` on a wrong code; the raised level lasts for the session.
The SPC changes the session's access level; it does not itself write an NV
value. In the app the NV console
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
* EFS2 is journalled: changes can roll back across a modem restart unless the
  journal is synced. The app requests sync after changes; see **Saving changes
  and restarting** for the completion-status limitation.

Further details found on hardware:

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
corrupting files under `/nv/`, `/rfnv/`, `/policyman/` can make the modem
unusable, and some of that data cannot be recovered without a backup from
**this specific** device.

A sensible order of work: connect → export the files you intend to change and
make a file-by-file backup → review any skipped/unreadable entries → save the
backup off the device → and only then enable writes. Also request a modem TAR
if the device supports it. Neither export should be assumed to be a complete
device-recovery image.

The app makes no attempt to edit the IMEI and contains no carrier-lock bypass;
it is a tool for inspecting, exporting and modifying modem configuration.

---

## Structure

```
daemon/
  build.sh              builds the aarch64 binary with the NDK
  src/diag.c|h          DIAG over QRTR or /dev/diag; discovery, framing, SSR watcher
  src/efs2.c|h          EFS2 operations and NV items
  src/main.c            the unix socket, the JSON protocol, recursive operations
  test/mock_modem.py    fake EFS2 + NV with QRTR/local/external /dev/diag framing
  test/run_tests.py     the end-to-end test of the daemon against the mock
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
    MainActivity.kt     activity setup and theme
    Model.kt            entries, metadata, formatting, sorting/filtering
    MainViewModel.kt    screen state, file operations, sync, import/features, restart
    Ui.kt               browser, menus and dialog coordination
    UiDetail.kt         detail sheet, text/hex preview
    UiDialogs.kt        editor, NV/raw consoles, diagnostics and other dialogs
    bulk/               the mtbtool JSON import parser and its dialog
    features/           feature definitions, read/check/write logic and dialog
    update/             the GitHub release check behind the "new version" dialog
  app/src/test/         JVM unit tests
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
  is reimplemented for the external-modem fallback. No code was copied.
* [Qualcomm QMI Band Control](https://github.com/Fronsipswu/qcom-band-menu) — the "root daemon + abstract socket + Compose client" shape.
