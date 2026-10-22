import datetime as dt
import importlib.util
import io
import json
import pathlib
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('claude_statusline', pathlib.Path(__file__).resolve().parents[1] / 'tools/claude_statusline.py')
statusline = importlib.util.module_from_spec(spec)
spec.loader.exec_module(statusline)


def payload(five=38, week=19):
    return {'model': {'display_name': 'Opus'}, 'rate_limits': {
        'five_hour': {'used_percentage': five, 'resets_at': 1900000000},
        'seven_day': {'used_percentage': week, 'resets_at': 1900300000}}}


class ClaudeStatuslineTests(unittest.TestCase):
    def setUp(self):
        self.logged = []
        original, statusline.log = statusline.log, self.logged.append
        self.addCleanup(setattr, statusline, 'log', original)

    def test_converts_used_to_remaining(self):
        data = statusline.snapshot(payload(), 'Max 5x', dt.datetime(2026, 10, 20, tzinfo=dt.timezone.utc))
        windows = data['providers']['claude']['windows']
        self.assertEqual([w['label'] for w in windows], ['5 小時', '每週'])
        self.assertEqual([w['remainingPercent'] for w in windows], [62, 81])
        self.assertEqual(windows[0]['resetsAt'], '2030-03-17T17:46:40Z')
        self.assertEqual(data['generatedAt'], '2026-10-20T00:00:00Z')
        self.assertIsNone(data['providers']['codex'])
        self.assertEqual(data['providers']['claude']['plan'], 'Max 5x')

    def test_single_window_kept(self):
        p = payload(); del p['rate_limits']['seven_day']
        self.assertEqual(len(statusline.snapshot(p)['providers']['claude']['windows']), 1)

    def test_missing_rate_limits_rejected(self):
        for p in ({}, {'rate_limits': None}, {'rate_limits': {}}, []):
            with self.subTest(p=p), self.assertRaises(ValueError):
                statusline.snapshot(p)

    def test_invalid_values_rejected(self):
        for used in (-1, 101, float('nan'), None, True, '38'):
            with self.subTest(used=used), self.assertRaises(ValueError):
                statusline.snapshot(payload(five=used))
        p = payload(); p['rate_limits']['five_hour']['resets_at'] = '2030-01-01'
        with self.assertRaises(ValueError):
            statusline.snapshot(p)

    def test_writes_file_and_prints_line(self):
        with tempfile.TemporaryDirectory() as folder:
            output = pathlib.Path(folder) / 'q' / 'claude-quota.json'
            out = io.StringIO()
            statusline.main(['--output', str(output), '--plan', 'Max'], io.StringIO(json.dumps(payload())), out)
            self.assertEqual(out.getvalue().strip(), 'Opus · 5h 剩 62% · 週 剩 81%')
            self.assertEqual(json.loads(output.read_text(encoding='utf-8'))['providers']['claude']['windows'][1]['remainingPercent'], 81)

    def test_accepts_bom_bytes(self):
        with tempfile.TemporaryDirectory() as folder:
            output = pathlib.Path(folder) / 'claude-quota.json'
            stdin = io.TextIOWrapper(io.BytesIO(b'\xef\xbb\xbf' + json.dumps(payload()).encode('utf-8')), encoding='cp936')
            statusline.main(['--output', str(output)], stdin, io.StringIO())
            self.assertTrue(output.exists())

    def test_bad_input_keeps_last_file(self):
        with tempfile.TemporaryDirectory() as folder:
            output = pathlib.Path(folder) / 'claude-quota.json'
            output.write_text('previous', encoding='utf-8')
            out = io.StringIO()
            statusline.main(['--output', str(output)], io.StringIO('{"model": {"display_name": "Opus"}}'), out)
            self.assertEqual(out.getvalue().strip(), 'Opus · 額度：尚無資料')
            self.assertTrue(self.logged[-1].startswith('no quota'))
            self.assertEqual(output.read_text(encoding='utf-8'), 'previous')
            statusline.main(['--output', str(output)], io.StringIO('not json'), io.StringIO())
            self.assertEqual(output.read_text(encoding='utf-8'), 'previous')


if __name__ == '__main__': unittest.main()
