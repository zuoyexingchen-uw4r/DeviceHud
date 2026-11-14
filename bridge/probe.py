#!/usr/bin/env python3
"""有线旁路桥的探针：走完整挑战应答握手，再发一条命令。

用法： probe.py <port> <token> [command]
退出码 0 = 握手成功；1 = 鉴权失败；2 = 连不上
"""
import hashlib
import hmac
import re
import socket
import sys


def read_frame(f):
    buf = b""
    while True:
        chunk = f.read(4096)
        if not chunk:
            break
        buf += chunk
        m = re.search(rb"\n__END__(-?\d+)\n$", buf)
        if m:
            return buf[: m.start()], int(m.group(1))
    return buf, -1


def main():
    if len(sys.argv) < 3:
        print("usage: probe.py <port> <token> [command]")
        return 2

    port = int(sys.argv[1])
    token = sys.argv[2]
    cmd = sys.argv[3] if len(sys.argv) > 3 else "__PING__"

    try:
        s = socket.create_connection(("127.0.0.1", port), timeout=5)
    except OSError as e:
        print("connect failed:", e)
        return 2

    f = s.makefile("rwb", buffering=0)
    line = f.readline().decode(errors="replace").strip()
    if not line.startswith("NONCE "):
        print("unexpected greeting:", line[:80])
        s.close()
        return 1

    nonce = bytes.fromhex(line.split(" ", 1)[1])
    sig = hmac.new(token.encode(), nonce, hashlib.sha256).hexdigest()
    f.write(("AUTH " + sig + "\n").encode())

    reply = f.readline().decode(errors="replace").strip()
    if not reply.startswith("AUTH_OK"):
        print("auth rejected:", reply[:80] or "(closed)")
        s.close()
        return 1

    f.write((cmd + "\n").encode())
    body, code = read_frame(f)
    out = body.decode(errors="replace")
    print(out[:1500].rstrip())
    print("[exit=%d]" % code)
    s.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
