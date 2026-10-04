import http.client
import socket
import threading
import time
import unittest
from qa_http_deadline import Deadline, request


class DeadlineTest(unittest.TestCase):
    def test_contract_at_suite_second479_has_only_one_second(self):
        now = [0.0]
        clock = lambda: now[0]
        suite = Deadline(480, clock=clock)
        now[0] = 479
        contract = Deadline(120, suite, clock)
        self.assertEqual(1, contract.remaining())
        now[0] = 480
        with self.assertRaises(TimeoutError):
            contract.remaining()

    def test_upload_poll_and_download_share_one_contract(self):
        now = [0.0]
        clock = lambda: now[0]
        contract = Deadline(120, clock=clock)
        for elapsed, expected in [(30, 30), (110, 10), (119, 1)]:
            now[0] = elapsed
            self.assertEqual(expected, Deadline(30, contract, clock).remaining())

    def serve(self, payloads, expected=None):
        # One owned thread, no subprocesses. Real slow headers/body defeat an
        # idle timeout but must still stop at the absolute socket deadline.
        listener = socket.socket()
        listener.bind(('127.0.0.1', 0));listener.listen()
        stop = threading.Event()
        def server():
            with listener:
                conn, _ = listener.accept()
                with conn:
                    conn.recv(65536)
                    try:
                        for data in payloads:
                            conn.sendall(data)
                            if stop.wait(.015):break
                    except OSError:pass
        worker = threading.Thread(target=server);worker.start()
        start = time.monotonic()
        try:
            if expected is None:
                with self.assertRaises((TimeoutError, OSError, http.client.HTTPException)):
                    request(listener.getsockname()[1], 'synthetic', '/', Deadline(.12))
            else:
                self.assertEqual(expected, request(listener.getsockname()[1], 'synthetic', '/', Deadline(1)))
            self.assertLess(time.monotonic()-start, .8)
        finally:
            stop.set();worker.join(timeout=1)
            self.assertFalse(worker.is_alive())

    def test_trickling_headers_are_bounded(self):
        self.serve([bytes([c]) for c in b'HTTP/1.1 200 OK\r\nContent-Length: 500\r\n\r\n'])

    def test_trickling_download_is_bounded(self):
        self.serve([b'HTTP/1.1 200 OK\r\nContent-Length: 500\r\n\r\n']+[b'x']*100)

    def test_successful_response(self):
        self.serve([b'HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok'], b'ok')


if __name__ == '__main__':unittest.main()
