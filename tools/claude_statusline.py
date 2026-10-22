"""Claude Code status line that also saves Claude subscription quota for Quota Pocket.

Claude Code passes session JSON on stdin, including `rate_limits` for Pro/Max accounts.
This script only reads that JSON: it never reads Claude credentials or calls any API.

settings.json:
  "statusLine": {"type": "command", "command": "python /path/to/tools/claude_statusline.py --output /path/to/claude-quota.json"}
"""
import argparse
import datetime as dt
import json
import math
import pathlib
import sys

WINDOWS = (('five_hour', '5 小時', '5h'), ('seven_day', '每週', '週'))


def snapshot(payload, plan='Claude', now=None):
    limits = payload.get('rate_limits') if isinstance(payload, dict) else None
    if not isinstance(limits, dict):
        raise ValueError('No rate_limits in status line data (API key login, or no response yet).')
    windows = []
    for key, label, _ in WINDOWS:
        window = limits.get(key)
        if window is None:
            continue
        used = window.get('used_percentage') if isinstance(window, dict) else None
        if isinstance(used, bool) or not isinstance(used, (int, float)) or not math.isfinite(used) or not 0 <= used <= 100:
            raise ValueError(f'Invalid {key} usage percentage.')
        reset = window.get('resets_at')
        if reset is not None and (isinstance(reset, bool) or not isinstance(reset, (int, float)) or not math.isfinite(reset) or reset <= 0):
            raise ValueError(f'Invalid {key} reset time.')
        windows.append({'label': label, 'remainingPercent': round(100 - used, 4),
                        'resetsAt': dt.datetime.fromtimestamp(reset, dt.timezone.utc).isoformat().replace('+00:00', 'Z') if reset else None})
    if not windows:
        raise ValueError('rate_limits has no 5-hour or 7-day window.')
    now = now or dt.datetime.now(dt.timezone.utc)
    return {'schemaVersion': 1, 'demo': False, 'generatedAt': now.isoformat(timespec='seconds').replace('+00:00', 'Z'),
            'providers': {'claude': {'plan': plan, 'windows': windows}, 'codex': None}}


def line(payload, data):
    model = (payload.get('model') or {}).get('display_name') if isinstance(payload, dict) else None
    parts = [model] if model else []
    by_label = {w['label']: w for w in data['providers']['claude']['windows']} if data else {}
    for _, label, short in WINDOWS:
        if label in by_label:
            parts.append(f'{short} 剩 {by_label[label]["remainingPercent"]:.0f}%')
    return ' · '.join(parts) if parts else 'Claude'


def save(data, output):
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_suffix('.tmp')
    temporary.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding='utf-8')
    temporary.replace(output)


def main(argv=None, stdin=None, stdout=None):
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=pathlib.Path, default=pathlib.Path.home() / '.quota-pocket' / 'claude-quota.json')
    parser.add_argument('--plan', default='Claude', help='Plan name shown on the widget, e.g. "Max 5x" (1–32 characters).')
    args = parser.parse_args(argv)
    stdin = stdin or sys.stdin
    stdout = stdout or sys.stdout
    if hasattr(stdout, 'reconfigure'):
        stdout.reconfigure(encoding='utf-8')
    plan = args.plan.strip()[:32] or 'Claude'
    payload, data, problem = {}, None, None
    try:
        # Read bytes: Windows may hand over a BOM or decode stdin with the locale code page.
        raw = stdin.buffer.read().decode('utf-8-sig') if hasattr(stdin, 'buffer') else stdin.read().lstrip('﻿')
        payload = json.loads(raw or '{}')
        data = snapshot(payload, plan)
    except Exception as error:
        problem = ('額度：尚無資料', f'no quota: {error}')
    if data:
        try:
            save(data, args.output)
        except Exception as error:
            # A status line must always print; keep the last good file untouched.
            data, problem = None, ('額度檔寫入失敗', f'write failed: {type(error).__name__}: {error}')
    text = line(payload, data)
    if problem:
        text = (text + ' · ' if text != 'Claude' else '') + problem[0]
    stdout.write(text + '\n')
    log('ok: ' + str(args.output) if data else problem[1])


def log(message):
    """Keeps only the latest result in ~/.quota-pocket, for troubleshooting."""
    try:
        target = pathlib.Path.home() / '.quota-pocket' / 'statusline-last.txt'
        target.parent.mkdir(parents=True, exist_ok=True)
        stamp = dt.datetime.now().isoformat(timespec='seconds')
        target.write_text(f'{stamp} {message}\n', encoding='utf-8')
    except Exception:
        pass


if __name__ == '__main__':
    main()
