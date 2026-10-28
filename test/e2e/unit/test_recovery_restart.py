"""A new host must discover and recover interrupted work without the old process's memory."""
import base64
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[3] / 'scripts'))
from e2e_device import PREFERENCES, SERVICES, ENABLED
from e2e_runs import RunStore
from test_orchestration import FOREIGN, OWN

FIXTURE = Path(__file__).parent / 'fixtures/recovery_host.py'


class RecoveryRestartTest(unittest.TestCase):
    def test_fresh_host_discovers_and_recovers_each_crash_boundary(self):
        for mode in ('claim-crash', 'test-crash', 'restore-crash', 'disposal-crash'):
            with self.subTest(mode=mode), tempfile.TemporaryDirectory(prefix='divedeep-restart-test-') as temporary:
                root = Path(temporary)
                crashed = subprocess.run([sys.executable, str(FIXTURE), mode, str(root)], capture_output=True, text=True, timeout=20)
                self.assertEqual(crashed.returncode, 77, crashed.stderr)
                report = json.loads((root / mode / 'run.json').read_text())
                journal = Path(report['recovery_journal'])
                self.assertTrue(journal.is_file())
                self.assertIn(str(journal), crashed.stdout)
                # A normal run cannot overwrite the interrupted transaction, even before marker publication.
                blocked = subprocess.run([sys.executable, str(FIXTURE), 'new-run', str(root)], capture_output=True, text=True, timeout=20)
                self.assertEqual(blocked.returncode, 1, blocked.stderr)
                self.assertTrue(journal.exists())
                recovered = subprocess.run([sys.executable, str(FIXTURE), 'recover', str(root)], capture_output=True, text=True, timeout=20)
                self.assertEqual(recovered.returncode, 0, recovered.stderr + recovered.stdout)
                device = json.loads((root / 'device.json').read_text())
                self.assertEqual(device['settings'], {SERVICES: FOREIGN + ':' + OWN, ENABLED: '1'})
                self.assertEqual(device['files'], {PREFERENCES: base64.b64encode(b'original test settings\x1a\x00').decode()})
                self.assertIsNone(device['marker'])
                self.assertFalse(any(device['running'].values()))
                self.assertEqual(RunStore('synthetic-device', root / 'private').pending(), [])
