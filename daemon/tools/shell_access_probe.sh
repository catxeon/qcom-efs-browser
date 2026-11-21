#!/bin/sh
# shell_access_probe.sh -- what can a shell-uid process reach on this phone?
#
# Shizuku would run the helper as uid 2000 in the u:r:shell:s0 SELinux domain.
# Running the helper through `adb shell` WITHOUT su puts it in exactly that uid
# and that domain, so this script is how we find out -- before writing any
# Shizuku code -- whether such a process may touch DIAG at all, and if not,
# whether anything else on the device could stand in for it.
#
# Everything here is read-only: it opens DIAG (never switching its logging
# mode), lists device nodes and services, and reads the log.  Nothing is
# written to the device except the two files it pushes and then deletes.
#
# Run it from the machine the phone is attached to:
#
#     sh daemon/tools/shell_access_probe.sh
#
# It pushes itself plus the shell_access_probe binary, runs them on the phone,
# saves the report next to you and prints the verdict lines.  The binary is
# built from shell_access_probe.c; with ANDROID_NDK_HOME set this script builds
# it on the spot, otherwise it prints the one command that does.
#
# Options:
#   --force   run even though adbd is root (the result then proves nothing)
#   --keep    leave the pushed files in /data/local/tmp/qefs-probe
#   --device  the on-device half; the host half invokes this for you
#
# Notes:
#   * ADB=... overrides which adb is used.  On a Windows box, use the
#     platform-tools adb -- adb inside WSL does not see the USB device.
#   * MSYS_NO_PATHCONV is exported below because Git Bash otherwise rewrites
#     /data/local/tmp into a Windows path on its way to the phone.
set -u

DEV_DIR=/data/local/tmp/qefs-probe
PROBE=shell_access_probe
MSYS_NO_PATHCONV=1
export MSYS_NO_PATHCONV

# ---- the on-device half ------------------------------------------------

dev_head() {
    echo
    echo "## $1"
}

# ls -Z is what shows the SELinux label, and an old toybox lacks it.  Which
# flag works is decided once, so a missing file is not reported twice.
LS_FLAGS=-ld
dev_ls_init() {
    ls -ldZ / >/dev/null 2>&1 && LS_FLAGS=-ldZ
}
dev_ls() {
    ls $LS_FLAGS "$@" 2>&1
}

# Prints a command's filtered output, or says so when there is none.  Needed
# wherever the filter ends in `tail`, whose exit status hides grep's.
dev_or_none() {
    if [ -n "$1" ]; then echo "$1"; else echo "$2"; fi
}

# Denials logged since $1, one line each.  A denial is logged twice on most
# builds -- once by the SELinux tag and once by auditd -- so the timestamp and
# tag are cut off and the remainder de-duplicated.
dev_avc() {
    logcat -d -b all -t "$1" 2>/dev/null | grep -E 'avc: *denied' \
        | sed 's/^.*avc:/avc:/' | awk '!seen[$0]++'
}

device_main() {
    HERE=$(dirname "$0")
    dev_ls_init

    echo "# shell_access_probe.sh report"
    echo "generated=$(date 2>/dev/null)"

    dev_head "device"
    for p in ro.product.manufacturer ro.product.model ro.product.device \
             ro.board.platform ro.build.type ro.build.version.release \
             ro.build.version.security_patch ro.boot.verifiedbootstate; do
        echo "$p=$(getprop $p 2>/dev/null)"
    done

    dev_head "identity (shell side)"
    id 2>&1
    echo "context=$(cat /proc/self/attr/current 2>/dev/null | tr -d '\0')"
    echo "getenforce=$(getenforce 2>/dev/null)"

    dev_head "root on this phone (tells us whether the policy may be patched)"
    if command -v su >/dev/null 2>&1; then
        echo "su=present at $(command -v su) -- a Magisk-patched policy can let"
        echo "  shell do things a stock phone never would, so a good result here"
        echo "  has to be confirmed on a phone without root"
    else
        echo "su=absent (good: this is the environment Shizuku users will have)"
    fi
    echo "magisk=$(magisk -v 2>/dev/null || echo 'not callable from shell')"

    dev_head "native probe (QRTR and /dev/diag)"
    MARK=$(date +'%m-%d %H:%M:%S.000' 2>/dev/null || echo '')
    if [ -x "$HERE/$PROBE" ]; then
        "$HERE/$PROBE" 2>&1
    else
        echo "$PROBE is not next to this script, so QRTR could not be tested:"
        echo "  a shell has no way to create an AF_QIPCRTR socket."
        echo "falling back to what the shell itself can try:"
        if [ -e /dev/diag ]; then
            ( : < /dev/diag ) 2>&1 && echo "open /dev/diag for reading: ok"
        else
            echo "/dev/diag does not exist"
        fi
    fi

    dev_head "avc denials while the probe ran"
    if [ -n "$MARK" ]; then
        dev_or_none "$(dev_avc "$MARK" | head -40)" \
            "(none logged -- a refusal with no denial behind it was DAC, not policy)"
    else
        echo "(no usable timestamp, so the log could not be narrowed)"
    fi
    echo "-- dmesg (usually closed to shell, worth one try):"
    dev_or_none "$(dmesg 2>&1 | grep -E 'avc: *denied' | tail -20)" "(nothing readable)"

    dev_head "modem-related device nodes"
    ls -lZ /dev 2>/dev/null | grep -Ei 'diag|smd|mhi|qrtr|ipa|rmnet|modem|at_|ttyGS' \
        || echo "(nothing matched, or ls -Z is unavailable)"
    echo "-- /dev/diag itself:"
    dev_ls /dev/diag

    dev_head "binder and hwbinder surface visible to shell"
    dev_ls /dev/binder /dev/hwbinder /dev/vndbinder
    echo "-- service list (radio/diag/oem/telephony only):"
    service list 2>&1 | grep -Ei 'radio|diag|oem|qti|telephony|modem|qcril' \
        || echo "(no match)"
    # lshal walks every HAL and is refused most of them, which prints a wall of
    # warnings on stderr and a denial per interface in the log.  Hence last,
    # with stderr dropped: by now the denials that matter are already recorded.
    echo "-- lshal, same filter (its own denials are its own business):"
    dev_or_none "$(lshal --neat 2>/dev/null | grep -Ei 'radio|diag|oem' | head -40)" \
        "(no match, or lshal is not allowed for shell)"

    echo
    echo "# end of report"
}

# ---- the host half -----------------------------------------------------

die() { echo "$*" >&2; exit 1; }

# Under Git Bash, adb.exe is a Windows program: it needs a Windows path for a
# local file, while MSYS_NO_PATHCONV above is what keeps the phone's own paths
# from being rewritten.  cygpath exists only in that environment, so elsewhere
# this hands the path back untouched.
hostpath() {
    if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi
}

host_build_probe() {
    # Mirrors daemon/build.sh: the platform libc, not -static.
    ndk="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
    if [ -z "$ndk" ]; then
        echo "The probe binary is missing and ANDROID_NDK_HOME is unset." >&2
        echo "Build it wherever your NDK lives, then come back:" >&2
        echo >&2
        echo '  "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/clang" \' >&2
        echo '      --target=aarch64-linux-android26 -Os -Wall -Wextra \' >&2
        echo "      -o $here/$PROBE $here/$PROBE.c" >&2
        echo >&2
        die "then run this script again."
    fi
    case "$(uname -s)" in
        Linux)  host=linux-x86_64 ;;
        Darwin) host=darwin-x86_64 ;;
        *)      host=windows-x86_64 ;;
    esac
    cc="$ndk/toolchains/llvm/prebuilt/$host/bin/clang"
    [ -x "$cc" ] || cc="$cc.exe"
    [ -x "$cc" ] || die "clang not found under $ndk"

    echo "building $PROBE with $cc"
    "$cc" --target=aarch64-linux-android26 -Os \
          -fno-strict-aliasing -fstack-protector-strong \
          -Wall -Wextra -Wno-unused-parameter \
          -o "$here/$PROBE" "$here/$PROBE.c" || die "the probe did not build"
}

host_main() {
    force=0
    keep=0
    for a in "$@"; do
        case "$a" in
            --force) force=1 ;;
            --keep)  keep=1 ;;
            -h|--help) sed -n '2,40p' "$0"; exit 0 ;;
            *) die "unknown option '$a'" ;;
        esac
    done

    here=$(cd "$(dirname "$0")" && pwd)
    adb="${ADB:-adb}"
    command -v "$adb" >/dev/null 2>&1 \
        || die "adb not found; set ADB=/path/to/adb (on Windows use platform-tools, not WSL's)"

    "$adb" shell true >/dev/null 2>&1 || die "no phone answered adb"

    uid=$("$adb" shell id -u 2>/dev/null | tr -d '\r\n')
    if [ "$uid" = "0" ]; then
        echo "adbd is running as root, so this phone would answer every question" >&2
        echo "with 'yes' and tell us nothing about Shizuku.  Run 'adb unroot' (or" >&2
        echo "turn off root for adb) and try again; --force overrides this." >&2
        [ "$force" = "1" ] || exit 2
    fi

    [ -f "$here/$PROBE" ] || host_build_probe

    dev=$("$adb" shell getprop ro.product.device 2>/dev/null | tr -d '\r\n')
    [ -n "$dev" ] || dev=phone
    report="shell-access-$dev-$(date +%Y%m%d-%H%M%S).txt"

    echo "pushing the probe to $DEV_DIR"
    "$adb" shell mkdir -p "$DEV_DIR" >/dev/null 2>&1 \
        || die "cannot create $DEV_DIR on the phone"
    "$adb" push "$(hostpath "$here/$PROBE")" "$DEV_DIR/$PROBE" >/dev/null \
        || die "push failed"
    "$adb" push "$(hostpath "$here/$(basename "$0")")" "$DEV_DIR/" >/dev/null \
        || die "push failed"
    "$adb" shell chmod 700 "$DEV_DIR/$PROBE" >/dev/null 2>&1

    echo "running it on $dev as uid ${uid:-unknown}"
    "$adb" shell sh "$DEV_DIR/$(basename "$0")" --device 2>&1 | tr -d '\r' > "$report"

    if [ "$keep" = "1" ]; then
        echo "left the pushed files in $DEV_DIR"
    else
        "$adb" shell rm -rf "$DEV_DIR" >/dev/null 2>&1
    fi

    echo
    grep -E '^VERDICT|^su=|^getenforce=|^context=' "$report" || true
    echo
    echo "full report: $report"
}

case "${1:-}" in
    --device) device_main ;;
    *)        host_main "$@" ;;
esac
