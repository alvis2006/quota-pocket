"""Export Codex subscription quota via the official app-server protocol.

Requires a Codex CLI already signed into the user's ChatGPT account.
Reads quota only. Does not read auth files or start a model turn.
"""
import argparse
import datetime as dt
import json
import math
import os
import pathlib
import queue
import shutil
import subprocess
import threading
import time


def snapshot(result, now=None):
    buckets = result.get('rateLimitsByLimitId')
    bucket = buckets.get('codex') if isinstance(buckets, dict) else None
    if bucket is None:
        bucket = result.get('rateLimits')
        if not isinstance(bucket, dict) or bucket.get('limitId') not in (None, 'codex'):
            raise ValueError('No Codex quota bucket is available.')
    windows = []
    for key in ('primary', 'secondary'):
        window = bucket.get(key)
        if window is None:
            continue
        used = window.get('usedPercent')
        if isinstance(used, bool) or not isinstance(used, (int, float)) or not math.isfinite(used) or not 0 <= used <= 100:
            raise ValueError('Invalid usage percentage from Codex.')
        duration = window.get('windowDurationMins')
        if isinstance(duration, bool) or not isinstance(duration, int) or duration <= 0:
            raise ValueError('Missing or invalid quota window duration.')
        label = '每週' if duration == 10080 else f'{duration // 60} 小時' if duration % 60 == 0 else f'{duration} 分鐘'
        reset = window.get('resetsAt')
        if reset is not None and (isinstance(reset, bool) or not isinstance(reset, (int, float)) or not math.isfinite(reset) or reset <= 0):
            raise ValueError('Invalid reset timestamp.')
        windows.append({'label': label, 'remainingPercent': round(100 - used, 4),
                        'resetsAt': dt.datetime.fromtimestamp(reset, dt.timezone.utc).isoformat().replace('+00:00', 'Z') if reset else None})
    if not windows:
        raise ValueError('No quota windows were returned; nothing was exported.')
    now = now or dt.datetime.now(dt.timezone.utc)
    return {'schemaVersion': 1, 'demo': False, 'generatedAt': now.isoformat().replace('+00:00', 'Z'),
            'providers': {'claude': None, 'codex': {'plan': bucket.get('planType') or 'Codex', 'windows': windows}}}


def read_quota(executable, timeout=25):
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    process = subprocess.Popen([executable, 'app-server'], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                               stderr=subprocess.DEVNULL, text=True, encoding='utf-8', creationflags=flags)
    messages = queue.Queue()

    def reader():
        try:
            for line in process.stdout:
                try:
                    messages.put(json.loads(line))
                except json.JSONDecodeError:
                    continue
        finally:
            messages.put(None)

    threading.Thread(target=reader, daemon=True).start()
    deadline = time.monotonic() + timeout

    def send(message):
        process.stdin.write(json.dumps(message) + '\n')
        process.stdin.flush()

    def receive(identifier):
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError('Codex quota request timed out.')
            try:
                message = messages.get(timeout=remaining)
            except queue.Empty:
                raise TimeoutError('Codex quota request timed out.') from None
            if message is None:
                raise RuntimeError('Codex app-server closed before returning quota.')
            if message.get('id') != identifier:
                continue
            if 'error' in message:
                raise RuntimeError('Codex rejected the request. Check CLI login and account access.')
            return message['result']

    try:
        send({'id': 1, 'method': 'initialize', 'params': {'clientInfo': {'name': 'quota_pocket', 'title': 'Quota Pocket', 'version': '0.1.0'}}})
        receive(1)
        send({'method': 'initialized', 'params': {}})
        send({'id': 2, 'method': 'account/rateLimits/read'})
        return receive(2)
    finally:
        process.terminate()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()
        process.stdin.close()
        process.stdout.close()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--codex', help='Path to the native Codex executable (codex.exe on Windows).')
    parser.add_argument('--output', type=pathlib.Path, default=pathlib.Path('output/codex-quota.json'))
    args = parser.parse_args()
    executable = args.codex or shutil.which('codex.exe' if os.name == 'nt' else 'codex')
    if not executable:
        parser.error('Codex executable not found. Pass --codex PATH to an already installed, signed-in CLI.')
    if pathlib.Path(executable).suffix.lower() in ('.cmd', '.bat'):
        parser.error('Use the native codex.exe path rather than a shell wrapper.')
    try:
        data = snapshot(read_quota(executable))
        args.output.parent.mkdir(parents=True, exist_ok=True)
        temporary = args.output.with_suffix('.tmp')
        temporary.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding='utf-8')
        temporary.replace(args.output)
    except Exception as error:
        parser.exit(1, f'Export failed: {error}\n')
    print(f'Exported quota only: {args.output}')


if __name__ == '__main__':
    main()
