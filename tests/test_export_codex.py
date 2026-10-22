import datetime as dt
import importlib.util
import pathlib
import unittest

spec = importlib.util.spec_from_file_location('export_codex', pathlib.Path(__file__).resolve().parents[1] / 'tools/export_codex.py')
export = importlib.util.module_from_spec(spec)
spec.loader.exec_module(export)


class CodexMappingTests(unittest.TestCase):
    def bucket(self, used=59, minutes=10080):
        return {'limitId': 'codex', 'planType': 'pro', 'primary': {'usedPercent': used, 'windowDurationMins': minutes, 'resetsAt': 1900000000}, 'secondary': None}

    def test_converts_used_to_remaining(self):
        data = export.snapshot({'rateLimits': self.bucket()})
        self.assertEqual(data['providers']['codex']['windows'][0]['remainingPercent'], 41)
        self.assertEqual(data['providers']['codex']['windows'][0]['label'], '每週')
        self.assertIsNone(data['providers']['claude'])
        self.assertFalse(data['demo'])

    def test_prefers_codex_bucket_over_legacy(self):
        data = export.snapshot({'rateLimits': self.bucket(10), 'rateLimitsByLimitId': {'codex': self.bucket(100)}})
        self.assertEqual(data['providers']['codex']['windows'][0]['remainingPercent'], 0)

    def test_zero_usage_is_full_remaining(self):
        data = export.snapshot({'rateLimits': self.bucket(0, 300)})
        self.assertEqual(data['providers']['codex']['windows'][0]['remainingPercent'], 100)
        self.assertEqual(data['providers']['codex']['windows'][0]['label'], '5 小時')

    def test_unknown_duration_is_not_mislabeled_weekly(self):
        data = export.snapshot({'rateLimits': self.bucket(12.5, 15)})
        self.assertEqual(data['providers']['codex']['windows'][0]['label'], '15 分鐘')
        self.assertEqual(data['providers']['codex']['windows'][0]['remainingPercent'], 87.5)

    def test_missing_reset_remains_unknown(self):
        b = self.bucket(); b['primary']['resetsAt'] = None
        data = export.snapshot({'rateLimits': b})
        self.assertIsNone(data['providers']['codex']['windows'][0]['resetsAt'])

    def test_invalid_percent_rejected(self):
        for used in (-1, 101, float('nan'), float('inf'), None, True, '59'):
            with self.subTest(used=used), self.assertRaises(ValueError):
                export.snapshot({'rateLimits': self.bucket(used)})

    def test_missing_windows_rejected(self):
        with self.assertRaises(ValueError):
            export.snapshot({'rateLimits': {'primary': None, 'secondary': None}})

    def test_unrelated_bucket_rejected(self):
        b = self.bucket(); b['limitId'] = 'codex_other'
        with self.assertRaises(ValueError): export.snapshot({'rateLimits': b})

    def test_both_windows_preserved(self):
        b = self.bucket(); b['secondary'] = self.bucket(20, 300)['primary']
        data = export.snapshot({'rateLimits': b}, dt.datetime(2026, 10, 20, tzinfo=dt.timezone.utc))
        self.assertEqual(len(data['providers']['codex']['windows']), 2)
        self.assertEqual(data['generatedAt'], '2026-10-20T00:00:00Z')


if __name__ == '__main__': unittest.main()
