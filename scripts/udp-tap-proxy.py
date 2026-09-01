#!/usr/bin/env python3
"""UDP tap proxy: pass-through with per-second datagram stats.

Usage: udp-tap-proxy.py <listen-port> <target-host> <target-port>
Prints one line per second: up=N (bytes) down=M (bytes).
"""
import socket
import sys
import threading
import time

listen_port = int(sys.argv[1])
target_host = sys.argv[2]
target_port = int(sys.argv[3])

sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
sock.bind(("127.0.0.1", listen_port))
uplink = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)

lock = threading.Lock()
client_addr = None
up_count = 0
up_bytes = 0
down_count = 0
down_bytes = 0
up_sizes = {}
down_sizes = {}


def report():
    global up_count, up_bytes, down_count, down_bytes, up_sizes, down_sizes
    while True:
        time.sleep(5)
        with lock:
            print(f"up={up_count} ({up_bytes}B sizes={dict(sorted(up_sizes.items()))}) "
                  f"down={down_count} ({down_bytes}B sizes={dict(sorted(down_sizes.items()))})",
                  flush=True)
            up_count = up_bytes = down_count = down_bytes = 0
            up_sizes = {}
            down_sizes = {}


def from_client():
    global client_addr, up_count, up_bytes
    while True:
        data, addr = sock.recvfrom(65535)
        with lock:
            client_addr = addr
            up_count += 1
            up_bytes += len(data)
            up_sizes[len(data)] = up_sizes.get(len(data), 0) + 1
        uplink.sendto(data, (target_host, target_port))


def from_server():
    global down_count, down_bytes
    while True:
        data, _ = uplink.recvfrom(65535)
        with lock:
            down_count += 1
            down_bytes += len(data)
            down_sizes[len(data)] = down_sizes.get(len(data), 0) + 1
            addr = client_addr
        if addr is not None:
            sock.sendto(data, addr)


threading.Thread(target=report, daemon=True).start()
threading.Thread(target=from_server, daemon=True).start()
print(f"tap proxy :{listen_port} -> {target_host}:{target_port}", flush=True)
from_client()
