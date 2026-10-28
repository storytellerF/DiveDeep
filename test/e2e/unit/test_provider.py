"""Provider existence and IPC capability are independent preparation conditions."""
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[3] / 'scripts'))
from android_e2e import Runner
from e2e_device import DeviceError


class Provider:
    def __init__(self, installed, compatible):
        self.present, self.compatible = installed, compatible
        self.read_error = None
        self.probes = 0

    async def installed(self, package):
        return self.present

    async def read(self, *args, **kwargs):
        self.probes += 1
        if self.read_error:
            raise self.read_error
        return b'com.storytellerf.llmd.action.BIND_IPC LlmdIpcService' if self.compatible else b'old package'


class ProviderPreparationTest(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='divedeep-provider-test-')
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.apk = self.root / 'provider.apk'
        self.apk.touch()
        self.runner = Runner(SimpleNamespace(device='synthetic', adb='unused', timeout=30,
                                             state_dir=self.root / 'private', report_dir=self.root / 'report',
                                             llmd_variant='debug', llmd_apk=str(self.apk), llmd_repo=None))
        self.installs = []

    async def install_compatible(self, apk):
        self.installs.append(apk)
        self.runner.device.present = self.runner.device.compatible = True

    async def test_missing_or_incompatible_provider_uses_supplied_apk(self):
        for installed in (False, True):
            with self.subTest(installed=installed):
                self.installs.clear()
                self.runner.device = Provider(installed, False)
                self.runner.install = self.install_compatible
                await self.runner.ensure_llmd()
                self.assertEqual(self.installs, [self.apk])
                self.assertGreater(self.runner.device.probes, 0)

    async def test_compatible_provider_is_not_reinstalled(self):
        self.runner.device = Provider(True, True)
        self.runner.install = self.install_compatible
        await self.runner.ensure_llmd()
        self.assertEqual(self.installs, [])

    async def test_install_success_without_ipc_is_rejected(self):
        self.runner.device = Provider(True, False)
        async def incompatible_install(apk):
            self.installs.append(apk)
        self.runner.install = incompatible_install
        with self.assertRaisesRegex(DeviceError, 'after installation'):
            await self.runner.ensure_llmd()
        self.assertEqual(self.installs, [self.apk])

    async def test_unknown_capability_does_not_authorize_install(self):
        self.runner.device = Provider(True, False)
        self.runner.device.read_error = DeviceError('unreadable provider')
        self.runner.install = self.install_compatible
        with self.assertRaisesRegex(DeviceError, 'unreadable provider'):
            await self.runner.ensure_llmd()
        self.assertEqual(self.installs, [])
