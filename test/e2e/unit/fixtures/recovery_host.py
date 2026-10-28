"""Real Runner/Transaction in a disposable host process, backed by a persistent synthetic device."""
import asyncio
import base64
import json
import os
from pathlib import Path
import sys
from types import SimpleNamespace

sys.path[:0] = [str(Path(__file__).resolve().parents[4] / 'scripts'), str(Path(__file__).resolve().parents[1])]
from android_e2e import Runner
from e2e_device import Absent, Present, PREFERENCES
from test_orchestration import ModelDevice


class PersistentDevice(ModelDevice):
    def __init__(self, path, mode):
        super().__init__()
        self.path, self.mode = path, mode
        self.testing = False
        if path.exists():
            data = json.loads(path.read_text())
            self.settings = {key: Absent() if value is None else Present(value) for key, value in data['settings'].items()}
            self.files = {key: base64.b64decode(value) for key, value in data['files'].items()}
            self.running, self.run_marker = data['running'], data['marker']
        else:
            self.persist()

    def persist(self):
        self.path.write_text(json.dumps({
            'settings': {key: value.value if isinstance(value, Present) else None for key, value in self.settings.items()},
            'files': {key: base64.b64encode(value).decode() for key, value in self.files.items()},
            'running': self.running, 'marker': self.run_marker,
        }))

    def step(self, name, effect, write=False):
        if self.mode == 'claim-crash' and name == 'claim':
            os._exit(77)
        result = super().step(name, effect, write)
        self.persist()
        if (self.testing and self.mode == 'restore-crash' and name == 'copy-' + PREFERENCES or
                self.mode == 'disposal-crash' and name == 'remove-marker'):
            os._exit(77)
        return result


class FixtureRunner(Runner):
    async def gradle(self, *args, **kwargs):
        pass

    async def ensure_llmd(self):
        pass

    async def diagnostics(self, run_id):
        pass

    async def tests(self, transaction):
        self.device.run_tests()
        self.device.testing = True
        self.device.persist()
        if self.device.mode == 'test-crash':
            os._exit(77)


mode, directory = sys.argv[1:]
root = Path(directory)
args = SimpleNamespace(device='synthetic-device', adb='unused', timeout=30,
                       state_dir=root / 'private', report_dir=root / mode,
                       recover='pending' if mode == 'recover' else None)
runner = FixtureRunner(args)
runner.device = PersistentDevice(root / 'device.json', mode)
raise SystemExit(asyncio.run(runner.execute()))
