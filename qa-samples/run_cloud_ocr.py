#!/usr/bin/env python3
"""Launch a production JAR with ephemeral auth and run the synthetic HTTP matrix.

Activate the intended JDK/OCR environment first. Linux containers without a
reaping PID 1 should provide --reaper path/to/reap-run.py. No token is persisted.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import resource
import secrets
import signal
import socket
import subprocess
import sys
import time
import threading
import urllib.request

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--samples', type=Path, default=ROOT / 'qa-samples/generated/cloud-handoff')
    parser.add_argument('--jar', type=Path, default=ROOT / 'web-api/target/web-api-0.1.5.jar')
    parser.add_argument('--reaper', type=Path)
    parser.add_argument('--provenance', type=Path, help='Verified build provenance; its JAR hash must match')
    args = parser.parse_args()
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=True)
    artifact = {'jarSha256':hashlib.sha256(args.jar.read_bytes()).hexdigest()}
    if args.provenance:
        artifact.update(json.loads(args.provenance.read_text()))
        assert artifact['jarSha256']==hashlib.sha256(args.jar.read_bytes()).hexdigest(), 'Provenance JAR mismatch'
    (out/'artifact-provenance.json').write_text(json.dumps(artifact,indent=2)+'\n')
    with socket.socket() as listener:
        listener.bind(('127.0.0.1', 0))
        port = listener.getsockname()[1]
    base = f'http://127.0.0.1:{port}'
    token = secrets.token_urlsafe(40)
    env = {**os.environ, 'SERVER_ADDRESS': '127.0.0.1', 'SERVER_PORT': str(port),
           'FORMAT_CONVERTER_API_TOKEN': token, 'FORMAT_CONVERTER_DATA_ROOT': str(out / 'data')}
    command = ['java', '-jar', str(args.jar.resolve())]
    if args.reaper:
        command = [sys.executable, str(args.reaper.resolve()), *command]
    with (out / 'server.log').open('w') as log:
        server = subprocess.Popen(command, env=env, stdout=log, stderr=subprocess.STDOUT,
                                  start_new_session=True)
        worker_pids = set()
        stopped = threading.Event()

        def observe_workers():
            while not stopped.wait(.1):
                children = {}
                for proc in Path('/proc').iterdir():
                    if not proc.name.isdigit():
                        continue
                    try:
                        fields = (proc / 'stat').read_text().rsplit(')', 1)[1].split()
                        children.setdefault(int(fields[1]), []).append(int(proc.name))
                    except (OSError, ValueError, IndexError):
                        pass
                pending = [server.pid]
                while pending:
                    pid = pending.pop()
                    pending.extend(children.get(pid, []))
                    try:
                        command_line = (Path('/proc') / str(pid) / 'cmdline').read_bytes()
                        if b'com.fuyue.formatconverter.task.ConversionWorkerMain' in command_line:
                            worker_pids.add(pid)
                    except OSError:
                        pass  # A child may exit between reads.

        observer = threading.Thread(target=observe_workers, daemon=True)
        observer.start()
        try:
            for _ in range(120):
                try:
                    request = urllib.request.Request(base + '/api/health', headers={'X-Format-Converter-Token': token})
                    with urllib.request.urlopen(request, timeout=2) as response:
                        health = json.load(response)
                    if health['status'] == 'UP':
                        break
                except OSError:
                    if server.poll() is not None:
                        raise RuntimeError('Backend exited; inspect server.log')
                    time.sleep(.25)
            else:
                raise TimeoutError('Backend startup timed out')
            subprocess.run([sys.executable, str(ROOT / 'qa-samples/verify_cloud_ocr.py'),
                            '--base-url', base, '--samples', str(args.samples.resolve()), '--out', str(out)], env=env, check=True)
            if not worker_pids:
                raise RuntimeError('No independent JVM worker observed; isolation acceptance is unverified')
        finally:
            if server.poll() is None:
                # Signal the Java child when a subreaper owns the session; let
                # that reaper collect Java and its descendants before it exits.
                if args.reaper:
                    subprocess.run(['pkill', '-TERM', '-P', str(server.pid)], check=False)
                else:
                    server.send_signal(signal.SIGTERM)
                try:
                    server.wait(timeout=30)
                except subprocess.TimeoutExpired:
                    os.killpg(server.pid, signal.SIGKILL)
                    server.wait(timeout=10)
            stopped.set()
            observer.join(timeout=2)
            usage = resource.getrusage(resource.RUSAGE_CHILDREN)
            (out / 'resources.json').write_text(json.dumps({
                'observedIndependentWorkerPids': sorted(worker_pids),
                'childrenPeakRssKiB': usage.ru_maxrss, 'childrenUserSeconds': usage.ru_utime,
                'childrenSystemSeconds': usage.ru_stime}, indent=2) + '\n')


if __name__ == '__main__':
    main()
