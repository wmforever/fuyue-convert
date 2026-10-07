"""Absolute work deadlines for loopback QA HTTP, including slow response bodies.

Socket idle timeouts alone do not bound trickling headers or downloads. A timer
shuts down the connected socket at the absolute deadline as well. Cleanup has
its own 15s ManagedProcess allowance; a blocked reaper is retained, never killed.
"""
import http.client
import socket
import threading
import time


class Deadline:
    def __init__(self, seconds, parent=None, clock=time.monotonic):
        self.clock = clock
        self.end = min(clock() + seconds, parent.end if parent else float('inf'))

    def remaining(self):
        value = self.end - self.clock()
        if value <= 0:
            raise TimeoutError('QA absolute work deadline exhausted')
        return value

    def sleep(self, seconds):
        time.sleep(min(seconds, self.remaining()))
        self.remaining()


def request(port, token, path, deadline, data=None, headers=None):
    operation = Deadline(30, deadline)
    connection = http.client.HTTPConnection('127.0.0.1', port, timeout=operation.remaining())
    timer = None
    try:
        connection.connect()
        sock = connection.sock

        def expire():
            try:
                sock.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass

        timer = threading.Timer(operation.remaining(), expire)
        timer.daemon = True
        timer.start()
        connection.request('POST' if data is not None else 'GET', path, body=data,
                           headers={'X-Format-Converter-Token': token, **(headers or {})})
        response = connection.getresponse()
        chunks = []
        while True:
            sock.settimeout(operation.remaining())
            chunk = response.read1(65536)
            operation.remaining()
            if not chunk:
                break
            chunks.append(chunk)
        if not 200 <= response.status < 300:
            raise RuntimeError('QA HTTP status ' + str(response.status))
        return b''.join(chunks)
    finally:
        if timer is not None:
            timer.cancel()
            timer.join(timeout=1)
        connection.close()
