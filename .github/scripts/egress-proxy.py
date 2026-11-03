#!/usr/bin/env python3
"""HTTPS CONNECT proxy that is the Bedrock sandbox's only way out, to allowlisted hosts.

The pinned Geyser fetches Bedrock authentication discovery from minecraft-services.net when it
starts and shuts down without it; Paper and Geyser download their game jars from mojang.com.
"""
import asyncio
import sys

ALLOWED_SUFFIXES = ('minecraft-services.net', 'mojang.com')


def allowed(host: str, port: str) -> bool:
    host = host.lower().rstrip('.')
    return port == '443' and any(host == suffix or host.endswith('.' + suffix) for suffix in ALLOWED_SUFFIXES)


async def pipe(source: asyncio.StreamReader, target: asyncio.StreamWriter) -> None:
    try:
        while data := await source.read(65536):
            target.write(data)
            await target.drain()
    except ConnectionError:
        pass
    finally:
        target.close()


async def handle(reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
    try:
        head = await asyncio.wait_for(reader.readuntil(b'\r\n\r\n'), 30)
        method, target, _ = head.split(b'\r\n', 1)[0].decode('latin-1').split(' ', 2)
        host, _, port = target.rpartition(':')
        if method != 'CONNECT' or not allowed(host, port):
            print(f'denied {method} {target}', flush=True)
            writer.write(b'HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n')
            await writer.drain()
            writer.close()
            return
        upstream_reader, upstream_writer = await asyncio.wait_for(asyncio.open_connection(host, int(port)), 30)
        print(f'allowed {target}', flush=True)
        writer.write(b'HTTP/1.1 200 Connection established\r\n\r\n')
        await writer.drain()
        await asyncio.gather(pipe(reader, upstream_writer), pipe(upstream_reader, writer))
    except Exception as error:  # One broken tunnel must not stop the proxy.
        print(f'tunnel failed: {error!r}', flush=True)
        writer.close()


async def main(port: int) -> None:
    server = await asyncio.start_server(handle, '0.0.0.0', port)
    async with server:
        await server.serve_forever()


if __name__ == '__main__':
    asyncio.run(main(int(sys.argv[1])))
