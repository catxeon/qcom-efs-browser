#!/usr/bin/env python3
"""A fake Qualcomm modem that speaks DIAG/EFS2 over a unix socket.

The daemon normally talks to the DIAG service on the QRTR bus; with
`-mock <path>` it connects to a SOCK_SEQPACKET socket instead, which is what
this script serves.  The framing is the one the modem uses on QRTR: the
request arrives as a bare payload and the answer is wrapped in

    7E 01 <len16> <payload> 7E

optionally preceded, as on SM8850 and newer, by a datagram header

    [u16 type = 8][u16 length of the rest]

which the fourth argument turns on.

The packet *layouts* are transcribed from qfenix's diag.c -- responses are
built the way qfenix parses them and requests are parsed the way qfenix builds
them, which cross-checks us against an implementation known to work on real
hardware.

The *opcode numbers*, however, follow libopenpst/EfsTools and a live SM8350
modem, not qfenix: qfenix numbers unlink 7, rmdir 8 and readlink 14, and with
those numbers a real modem rejects every delete (7 is readlink) while a
"readlink" lands on 14, which is RENAME.
"""

import os
import socket
import struct
import sys
import time

DIAG_SUBSYS_CMD_F = 0x4B
EFS_STD = 0x13
EFS_ALT = 0x3E
DIAG_BAD_CMD_F = 0x13
DIAG_SPC_F = 0x41
DIAG_BAD_SEC_MODE_F = 0x42
NV_READ_F = 0x26
NV_WRITE_F = 0x27

# Until the Service Programming Code is accepted, this modem refuses NV writes
# the way a production one does: 0x42, the rejected command, the item, zeros.
LOCKED_NV = 999
CORRECT_SPC = b"000000"

# Classic EFS2 numbering (libopenpst / EfsTools), verified on an SM8350 modem.
HELLO, QUERY, OPEN, CLOSE, READ, WRITE = 0, 1, 2, 3, 4, 5
SYMLINK, READLINK, UNLINK, MKDIR, RMDIR = 6, 7, 8, 9, 10
OPENDIR, READDIR, CLOSEDIR, RENAME = 11, 12, 13, 14
STAT, LSTAT, FSTAT, CHMOD, STATFS = 15, 16, 17, 18, 19
PUT_V1, GET_V1 = 26, 27
PUT, GET = 38, 39
SYNC_NO_WAIT, SYNC_GET_STATUS = 48, 49
FS_IMAGE_OPEN, FS_IMAGE_READ, FS_IMAGE_CLOSE = 54, 55, 56

S_IFDIR, S_IFREG, S_IFLNK, S_IFITM = 0o040000, 0o100000, 0o120000, 0o160000

ENOENT, EACCES, EEXIST, ENOTDIR, EPERM, ENOTEMPTY = 2, 13, 17, 20, 1, 39


# ------------------------------------------------------------- framing ----


def wrap(payload, datagram_header=False):
    frame = b"\x7e\x01" + struct.pack("<H", len(payload)) + payload + b"\x7e"
    if datagram_header:
        frame = struct.pack("<HH", 8, len(frame)) + frame
    return frame


# ------------------------------------------------------------ fake EFS ----

NOW = 1767225600   # 2026-01-01, fixed so tests are deterministic


class Node:
    def __init__(self, kind, mode, data=b"", target=None):
        self.kind = kind          # dir | file | link | item
        self.mode = mode
        self.data = data
        self.target = target

    @property
    def entry_type(self):
        """As reported by readdir on SM8350: 0 file, 1 dir, 2 link, 15 item."""
        return {"file": 0, "dir": 1, "link": 2, "item": 15}[self.kind]

    @property
    def ifmt(self):
        return {"dir": S_IFDIR, "file": S_IFREG, "link": S_IFLNK, "item": S_IFITM}[self.kind]

    @property
    def full_mode(self):
        return self.ifmt | (self.mode & 0o7777)

    @property
    def size(self):
        if self.kind == "dir":
            return 0
        if self.kind == "link":
            return len(self.target)
        return len(self.data)


def default_tree():
    t = {
        "/": Node("dir", 0o777),
        "/nv": Node("dir", 0o777),
        "/nv/item_files": Node("dir", 0o777),
        "/nv/item_files/modem": Node("dir", 0o777),
        "/nv/item_files/modem/nas": Node("dir", 0o777),
        "/nv/item_files/modem/nas/rk_hplmn": Node("item", 0o644, bytes(range(16))),
        "/policyman": Node("dir", 0o777),
        "/policyman/carrier_policy.xml": Node(
            "file", 0o644, b"<policy>\n  <carrier name='test'/>\n</policy>\n"),
        "/README.txt": Node("file", 0o644, b"hello from the fake modem\n"),
        "/link_to_readme": Node("link", 0o777, target="/README.txt"),
        # deliberately larger than one 1024-byte DIAG packet
        "/big.bin": Node("file", 0o644, bytes((i * 7 + 3) & 0xFF for i in range(5000))),
        "/rw": Node("dir", 0o777),
    }
    return t


class Modem:
    def __init__(self, log=None, reject_hellos=0):
        self.reject_hellos = reject_hellos
        self.hellos = 0
        self.tree = default_tree()
        self.dirs = {}        # dirp -> [names]
        self.files = {}       # fd -> path
        self.images = {}      # handle -> (data, offset)
        self.next_handle = 1
        self.nv = {550: bytes([0xAA] * 128)}
        self.unlocked = False
        self.log = log
        self.seen = []        # opcodes the daemon actually used

    def note(self, msg):
        if self.log:
            self.log.write(msg + "\n")
            self.log.flush()

    # -- helpers --

    def children(self, path):
        prefix = "/" if path == "/" else path + "/"
        out = []
        for p in self.tree:
            if p == path or not p.startswith(prefix):
                continue
            rest = p[len(prefix):]
            if "/" not in rest:
                out.append(rest)
        return sorted(out)

    def parent_ok(self, path):
        parent = path.rsplit("/", 1)[0] or "/"
        return parent in self.tree and self.tree[parent].kind == "dir"

    # -- dispatch --

    def handle(self, pkt):
        if not pkt:
            return None

        if pkt[0] == NV_READ_F:
            item = struct.unpack_from("<H", pkt, 1)[0]
            data = self.nv.get(item)
            self.seen.append(("nv_read", item))
            if data is None:
                return bytes([NV_READ_F]) + struct.pack("<H", item) + bytes(128) + struct.pack("<H", 8)
            return bytes([NV_READ_F]) + struct.pack("<H", item) + data + struct.pack("<H", 0)

        if pkt[0] == DIAG_SPC_F:
            ok = pkt[1:7] == CORRECT_SPC
            self.unlocked = self.unlocked or ok
            self.seen.append(("spc", ok))
            self.note("SPC %s" % ("accepted" if ok else "rejected"))
            return bytes([DIAG_SPC_F, 1 if ok else 0])

        if pkt[0] == NV_WRITE_F:
            item = struct.unpack_from("<H", pkt, 1)[0]
            if item == LOCKED_NV and not self.unlocked:
                self.note("NV %d write refused: security" % item)
                return (bytes([DIAG_BAD_SEC_MODE_F, NV_WRITE_F])
                        + struct.pack("<H", item) + bytes(13))
            self.nv[item] = pkt[3:131]
            self.seen.append(("nv_write", item))
            return bytes([NV_WRITE_F]) + struct.pack("<H", item) + pkt[3:131] + struct.pack("<H", 0)

        if pkt[0] != DIAG_SUBSYS_CMD_F:
            return bytes([DIAG_BAD_CMD_F]) + pkt

        subsys = pkt[1]
        op = struct.unpack_from("<H", pkt, 2)[0]

        if op == HELLO:
            self.hellos += 1
            if self.hellos <= self.reject_hellos:
                self.note("reject hello #%d on purpose" % self.hellos)
                return bytes([DIAG_BAD_CMD_F]) + pkt

        if subsys != EFS_STD:
            # Only the standard subsystem exists here, so the daemon has to
            # fall back from 0x3E -- exercising the error-response path.
            self.note("reject subsys 0x%02x op %d" % (subsys, op))
            return bytes([DIAG_BAD_CMD_F]) + pkt

        self.seen.append(("efs", op))
        fn = getattr(self, "op_%d" % op, None)
        if fn is None:
            self.note("unimplemented EFS op %d" % op)
            return bytes([DIAG_BAD_CMD_F]) + pkt
        return fn(pkt)

    def hdr(self, op):
        return bytes([DIAG_SUBSYS_CMD_F, EFS_STD]) + struct.pack("<H", op)

    @staticmethod
    def cstr(pkt, off):
        end = pkt.find(b"\0", off)
        if end < 0:
            end = len(pkt)
        return pkt[off:end].decode("utf-8", "replace"), end + 1

    # -- session --

    def op_0(self, pkt):                                   # HELLO
        return self.hdr(HELLO) + pkt[4:]

    def op_1(self, pkt):                                   # QUERY
        return self.hdr(QUERY) + struct.pack("<6i", 255, 255, 8, 1 << 20, 512, 8)

    # -- directories --

    def op_11(self, pkt):                                  # OPENDIR
        path, _ = self.cstr(pkt, 4)
        node = self.tree.get(path)
        if node is None or node.kind != "dir":
            return self.hdr(OPENDIR) + struct.pack("<ii", -1, ENOENT if node is None else ENOTDIR)
        dirp = self.next_handle
        self.next_handle += 1
        self.dirs[dirp] = self.children(path)
        self.note("opendir %s -> %d (%d entries)" % (path, dirp, len(self.dirs[dirp])))
        return self.hdr(OPENDIR) + struct.pack("<ii", dirp, 0)

    def op_12(self, pkt):                                  # READDIR
        dirp, seqno = struct.unpack_from("<iI", pkt, 4)
        names = self.dirs.get(dirp)
        if names is None:
            return self.hdr(READDIR) + struct.pack("<iII", dirp, seqno, 9)  # EBADF
        idx = seqno - 1                                    # the daemon starts at 1
        if idx < 0 or idx >= len(names):
            # End of directory: a zeroed entry with an empty name.  Note that
            # entry_type is 0 here *and* for ordinary files, so the name is
            # the only reliable terminator.
            return (self.hdr(READDIR) + struct.pack("<iIi", dirp, seqno, 0)
                    + struct.pack("<6i", 0, 0, 0, 0, 0, 0) + bytes(1))
        name = names[idx]
        # the directory this handle belongs to is not tracked separately; find
        # the node by matching the child name against every known path
        node = None
        for p, n in self.tree.items():
            if p.rsplit("/", 1)[-1] == name and name in self.children(p.rsplit("/", 1)[0] or "/"):
                node = n
                break
        assert node is not None, name
        body = struct.pack("<iIi", dirp, seqno, 0)
        body += struct.pack("<6i", node.entry_type, node.full_mode, node.size,
                            NOW, NOW, NOW)
        return self.hdr(READDIR) + body + name.encode()

    def op_13(self, pkt):                                  # CLOSEDIR
        dirp = struct.unpack_from("<i", pkt, 4)[0]
        self.dirs.pop(dirp, None)
        return self.hdr(CLOSEDIR) + struct.pack("<i", 0)

    # -- metadata --

    def stat_reply(self, op, path, follow):
        node = self.tree.get(path)
        # Real modems make stat() follow symlinks and lstat() not, so mirror it.
        for _ in range(8):
            if follow and node is not None and node.kind == "link":
                node = self.tree.get(node.target)
            else:
                break
        if node is None:
            return self.hdr(op) + struct.pack("<i", ENOENT) + bytes(24)
        return self.hdr(op) + struct.pack(
            "<7i", 0, node.full_mode, node.size, 1, NOW, NOW, NOW)

    def op_15(self, pkt):                                  # STAT (follows)
        path, _ = self.cstr(pkt, 4)
        return self.stat_reply(STAT, path, True)

    # No op_16: the SM8350 modem answers LSTAT with "bad command", so the mock
    # does the same and the daemon's readlink-based fallback stays under test.

    def op_19(self, pkt):                                  # STATFS
        return self.hdr(STATFS) + struct.pack("<i", 0) + struct.pack(
            "<6I", 1, 4096, 8192, 4096, 4096, 1024)

    # -- files --

    def op_2(self, pkt):                                   # OPEN
        oflag, mode = struct.unpack_from("<ii", pkt, 4)
        path, _ = self.cstr(pkt, 12)
        node = self.tree.get(path)

        if node is not None and node.kind == "item":
            # item files are invisible to the file interface, as on a real modem
            self.note("open %s refused (item file)" % path)
            return self.hdr(OPEN) + struct.pack("<ii", -1, EACCES)

        if node is None:
            if not (oflag & 0o100):                        # O_CREAT == 0x40
                return self.hdr(OPEN) + struct.pack("<ii", -1, ENOENT)
            if not self.parent_ok(path) and not (oflag & 0x80000):
                return self.hdr(OPEN) + struct.pack("<ii", -1, ENOENT)
            node = Node("item" if oflag & 0x40000 else "file", mode & 0o7777)
            self.tree[path] = node
        elif oflag & 0x200:                                # O_TRUNC
            node.data = b""

        fd = self.next_handle
        self.next_handle += 1
        self.files[fd] = path
        self.note("open %s flags=0x%x -> fd %d" % (path, oflag, fd))
        return self.hdr(OPEN) + struct.pack("<ii", fd, 0)

    def op_4(self, pkt):                                   # READ
        fd, nbytes, offset = struct.unpack_from("<iII", pkt, 4)
        path = self.files.get(fd)
        if path is None:
            return self.hdr(READ) + struct.pack("<iIii", fd, offset, -1, 9)
        data = self.tree[path].data[offset:offset + nbytes]
        return self.hdr(READ) + struct.pack("<iIii", fd, offset, len(data), 0) + data

    def op_5(self, pkt):                                   # WRITE
        fd, offset = struct.unpack_from("<iI", pkt, 4)
        payload = pkt[12:]
        path = self.files.get(fd)
        if path is None:
            return self.hdr(WRITE) + struct.pack("<iIii", fd, offset, -1, 9)
        node = self.tree[path]
        buf = bytearray(node.data)
        if len(buf) < offset:
            buf += bytes(offset - len(buf))
        buf[offset:offset + len(payload)] = payload
        node.data = bytes(buf)
        return self.hdr(WRITE) + struct.pack("<iIii", fd, offset, len(payload), 0)

    def op_3(self, pkt):                                   # CLOSE
        fd = struct.unpack_from("<i", pkt, 4)[0]
        self.files.pop(fd, None)
        return self.hdr(CLOSE) + struct.pack("<i", 0)

    # -- namespace --

    def op_9(self, pkt):                                   # MKDIR
        mode = struct.unpack_from("<h", pkt, 4)[0]
        path, _ = self.cstr(pkt, 6)
        if path in self.tree:
            return self.hdr(MKDIR) + struct.pack("<i", EEXIST)
        if not self.parent_ok(path):
            return self.hdr(MKDIR) + struct.pack("<i", ENOENT)
        self.tree[path] = Node("dir", mode & 0o7777)
        return self.hdr(MKDIR) + struct.pack("<i", 0)

    def op_18(self, pkt):                                  # CHMOD
        mode = struct.unpack_from("<h", pkt, 4)[0]
        path, _ = self.cstr(pkt, 6)
        if path not in self.tree:
            return self.hdr(CHMOD) + struct.pack("<i", ENOENT)
        self.tree[path].mode = mode & 0o7777
        return self.hdr(CHMOD) + struct.pack("<i", 0)

    def op_8(self, pkt):                                   # UNLINK
        path, _ = self.cstr(pkt, 4)
        node = self.tree.get(path)
        if node is None:
            return self.hdr(UNLINK) + struct.pack("<i", ENOENT)
        if node.kind == "dir":
            return self.hdr(UNLINK) + struct.pack("<i", EPERM)
        del self.tree[path]
        return self.hdr(UNLINK) + struct.pack("<i", 0)

    def op_10(self, pkt):                                  # RMDIR
        path, _ = self.cstr(pkt, 4)
        node = self.tree.get(path)
        if node is None:
            return self.hdr(RMDIR) + struct.pack("<i", ENOENT)
        if self.children(path):
            return self.hdr(RMDIR) + struct.pack("<i", ENOTEMPTY)
        del self.tree[path]
        return self.hdr(RMDIR) + struct.pack("<i", 0)

    def op_7(self, pkt):                                   # READLINK
        path, _ = self.cstr(pkt, 4)
        node = self.tree.get(path)
        if node is None or node.kind != "link":
            return self.hdr(READLINK) + struct.pack("<i", ENOENT)
        return self.hdr(READLINK) + struct.pack("<i", 0) + node.target.encode()

    def op_14(self, pkt):                                  # RENAME
        old, nxt = self.cstr(pkt, 4)
        new, _ = self.cstr(pkt, nxt)
        node = self.tree.get(old)
        if node is None:
            return self.hdr(RENAME) + struct.pack("<i", ENOENT)
        if new in self.tree:
            return self.hdr(RENAME) + struct.pack("<i", EEXIST)
        if not self.parent_ok(new):
            return self.hdr(RENAME) + struct.pack("<i", ENOENT)
        del self.tree[old]
        self.tree[new] = node
        self.note("rename %s -> %s" % (old, new))
        return self.hdr(RENAME) + struct.pack("<i", 0)

    def op_6(self, pkt):                                   # SYMLINK
        target, nxt = self.cstr(pkt, 4)
        link, _ = self.cstr(pkt, nxt)
        if link in self.tree:
            return self.hdr(SYMLINK) + struct.pack("<i", EEXIST)
        self.tree[link] = Node("link", 0o777, target=target)
        return self.hdr(SYMLINK) + struct.pack("<i", 0)

    # -- item interface --

    def op_39(self, pkt):                                  # GET
        path, _ = self.cstr(pkt, 4)
        node = self.tree.get(path)
        if node is None or node.kind == "dir":
            return self.hdr(GET) + struct.pack("<ii", 0, ENOENT)
        return self.hdr(GET) + struct.pack("<ii", len(node.data), 0) + node.data

    def op_38(self, pkt):                                  # PUT
        dlen = struct.unpack_from("<H", pkt, 4)[0]
        flags = struct.unpack_from("<i", pkt, 8)[0]
        mode = struct.unpack_from("<h", pkt, 12)[0]
        data = pkt[14:14 + dlen]
        path, _ = self.cstr(pkt, 14 + dlen)

        # Anything under /rw is refused so the open/write/close fallback runs.
        if path.startswith("/rw/"):
            self.note("put %s refused on purpose" % path)
            return self.hdr(PUT) + struct.pack("<hhh", mode, EPERM, 0)

        if not self.parent_ok(path) and not (flags & 0x80000):
            return self.hdr(PUT) + struct.pack("<hhh", mode, ENOENT, 0)

        kind = "item" if flags & 0x40000 else "file"
        self.tree[path] = Node(kind, mode & 0o7777, data)
        self.note("put %s (%d bytes, flags 0x%x)" % (path, dlen, flags))
        return self.hdr(PUT) + struct.pack("<hhh", mode, 0, dlen)

    # -- journal --

    def op_48(self, pkt):                                  # SYNC_NO_WAIT
        seq = struct.unpack_from("<H", pkt, 4)[0]
        return self.hdr(SYNC_NO_WAIT) + struct.pack("<HIi", seq, 0xC0FFEE, 0)

    def op_49(self, pkt):                                  # SYNC_GET_STATUS
        seq = struct.unpack_from("<H", pkt, 4)[0]
        return self.hdr(SYNC_GET_STATUS) + struct.pack("<H", seq) + bytes([0]) + struct.pack("<i", 0)

    # -- modem-generated tar --

    def op_54(self, pkt):                                  # FS_IMAGE_OPEN
        path, _ = self.cstr(pkt, 7)
        if path not in self.tree:
            return self.hdr(FS_IMAGE_OPEN) + struct.pack("<ii", -1, ENOENT)
        handle = self.next_handle
        self.next_handle += 1
        self.images[handle] = (self.fake_tar(path), 0)
        return self.hdr(FS_IMAGE_OPEN) + struct.pack("<ii", handle, 0)

    def op_55(self, pkt):                                  # FS_IMAGE_READ
        handle, seq = struct.unpack_from("<iH", pkt, 4)
        data, off = self.images.get(handle, (b"", 0))
        chunk = data[off:off + 900]
        off += len(chunk)
        self.images[handle] = (data, off)
        end = 1 if off >= len(data) else 0
        head = struct.pack("<iH", handle, seq) + struct.pack("<i", 0) + bytes([end])
        return self.hdr(FS_IMAGE_READ) + head + chunk

    def op_56(self, pkt):                                  # FS_IMAGE_CLOSE
        handle = struct.unpack_from("<i", pkt, 4)[0]
        self.images.pop(handle, None)
        return self.hdr(FS_IMAGE_CLOSE) + struct.pack("<i", 0)

    def fake_tar(self, root):
        """A minimal ustar stream of the regular files under `root`."""
        out = bytearray()
        prefix = "/" if root == "/" else root + "/"
        for path, node in sorted(self.tree.items()):
            if node.kind != "file" or not path.startswith(prefix):
                continue
            name = path[1:].encode()
            hdr = bytearray(512)
            hdr[0:len(name)] = name
            hdr[100:108] = b"0000644\0"
            hdr[108:116] = b"0000000\0"
            hdr[116:124] = b"0000000\0"
            hdr[124:136] = ("%011o\0" % len(node.data)).encode()
            hdr[136:148] = ("%011o\0" % NOW).encode()
            hdr[148:156] = b" " * 8
            hdr[156] = ord("0")
            hdr[257:263] = b"ustar\0"
            hdr[263:265] = b"00"
            chk = sum(hdr) & 0o7777777
            hdr[148:156] = ("%06o\0 " % chk).encode()
            out += hdr
            out += node.data
            pad = (-len(node.data)) % 512
            out += bytes(pad)
        out += bytes(1024)
        return bytes(out)


# --------------------------------------------------------------- server ----

def serve(path, logpath, reject_hellos=0, datagram_header=False):
    if os.path.exists(path):
        os.unlink(path)

    log = open(logpath, "w")
    modem = Modem(log, reject_hellos)

    srv = socket.socket(socket.AF_UNIX, socket.SOCK_SEQPACKET)
    srv.bind(path)
    srv.listen(1)
    log.write("mock modem listening on %s\n" % path)
    log.flush()
    print("ready", flush=True)

    conn, _ = srv.accept()
    log.write("daemon connected\n")
    log.flush()

    while True:
        try:
            blob = conn.recv(65536)
        except OSError:
            break
        if not blob:
            break

        reply = modem.handle(blob)
        if reply is None:
            continue

        conn.send(wrap(reply, datagram_header))

    log.write("connection closed; ops seen: %d\n" % len(modem.seen))
    log.flush()


def serve_ssr(path, logpath, mode):
    """A fake vendor QMI service (0xFFE4) on its own socket: <mock>.ssr.

    Accepts connections forever (the daemon opens one per ssr command) and,
    in mode "ssr", answers each request with the 508-byte response shape seen
    in mtb's log: the kernel-QMI response flag 0x02 plus the request's
    transaction and message ids echoed back.  The daemon discriminates on
    that echo alone, not on the flag byte.
    """
    if os.path.exists(path):
        os.unlink(path)
    log = open(logpath, "w")
    srv = socket.socket(socket.AF_UNIX, socket.SOCK_SEQPACKET)
    srv.bind(path)
    srv.listen(4)
    log.write("fake ssr service listening on %s (mode %s)\n" % (path, mode))
    log.flush()
    print("ssr-ready", flush=True)

    while True:
        conn, _ = srv.accept()
        while True:
            try:
                blob = conn.recv(65536)
            except OSError:
                break
            if not blob:
                break
            log.write("ssr request: %d bytes\n" % len(blob))
            log.flush()
            if mode != "ssr" or len(blob) < 5:
                continue
            rsp = bytearray(508)
            rsp[0] = 0x02                 # kernel-QMI response flag
            rsp[1:3] = blob[1:3]          # transaction id echo
            rsp[3:5] = blob[3:5]          # message id echo
            conn.send(bytes(rsp))


if __name__ == "__main__":
    import threading
    mode = sys.argv[4] if len(sys.argv) > 4 else "0"
    if mode in ("ssr", "ssr_silent"):
        threading.Thread(
            target=serve_ssr,
            args=(sys.argv[1] + ".ssr", sys.argv[2] + ".ssr.log", mode),
            daemon=True,
        ).start()
    serve(sys.argv[1], sys.argv[2],
          int(sys.argv[3]) if len(sys.argv) > 3 else 0,
          mode == "1")
