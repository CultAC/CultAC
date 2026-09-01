#!/usr/bin/env python3
"""UDP delay proxy for simulating Bedrock client latency.

Usage: udp-delay-proxy.py <listen-port> <target-host> <target-port> <delay-ms>
Forwards UDP datagrams in both directions, releasing each after delay-ms.
Single-client oriented: replies are sent back to the most recent client address.
"""
import socket
import sys
import threading
import time
from collections import deque

listen_port = int(sys.argv[1])
target_host = sys.argv[2]
target_port = int(sys.argv[3])
delay_ms = float(sys.argv[4])

sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
sock.bind(("127.0.0.1", listen_port))

uplink = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)  # client -> server
lock = threading.Lock()
client_addr = None
queue = deque()  # (release_time, data, direction)
cv = threading.Condition()


def pump():
    while True:
        with cv:
            while not queue:
                cv.wait()
            now = time.monotonic()
            release, data, direction = queue[0]
            if release > now:
                cv.wait(timeout=release - now)
                continue
            queue.popleft()
        if direction == "up":
            uplink.sendto(data, (target_host, target_port))
        else:
            with lock:
                addr = client_addr
            if addr is not None:
                sock.sendto(data, addr)


def from_client():
    global client_addr
    while True:
        data, addr = sock.recvfrom(65535)
        with lock:
            client_addr = addr
        with cv:
            queue.append((time.monotonic() + delay_ms / 1000.0, data, "up"))
            cv.notify()


def from_server():
    while True:
        data, _ = uplink.recvfrom(65535)
        with cv:
            queue.append((time.monotonic() + delay_ms / 1000.0, data, "down"))
            cv.notify()


threading.Thread(target=pump, daemon=True).start()
threading.Thread(target=from_server, daemon=True).start()
print(f"delay proxy :{listen_port} -> {target_host}:{target_port} delay={delay_ms}ms", flush=True)
from_client()
