"""Resource invariants for the host transaction; no physical device is accessed."""
import asyncio
import base64
from collections import Counter
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parents[3] / 'scripts'))
from e2e_device import (APP, FIXTURE, PREFERENCES, SERVICES, ENABLED, Absent, Present, Device,
                        DeviceError, Phase, Transaction)
from e2e_process import CommandError, Output

FOREIGN = 'example.reader/.AccessibilityService'
OWN = APP + '/.DiveDeepAccessibilityService'


class Fault(RuntimeError):
    pass


class ModelDevice(Device):
    """Stateful boundary: operations can fail before or after committing their side effect."""
    def __init__(self, preferences=True):
        self.serial = 'synthetic-device'
        self.settings = {SERVICES: Present(FOREIGN + ':' + OWN), ENABLED: Present('1')}
        self.original_settings = dict(self.settings)
        self.files = {PREFERENCES: b'original test settings\x1a\x00'} if preferences else {}
        self.original_preferences = dict(self.files)
        self.running = {APP: False, FIXTURE: False}
        self.run_marker = None
        self.events = []
        self.counts = Counter()
        self.fault = None

    def step(self, name, effect, write=False):
        self.counts[name] += 1
        point = (name, self.counts[name])
        self.events.append((name, 'start', write))
        if self.fault == (*point, 'before'):
            self.fault = None
            raise Fault(name)
        result = effect()
        self.events.append((name, 'committed', write))
        if self.fault == (*point, 'after'):
            self.fault = None
            raise Fault(name)
        return result

    async def marker(self):
        return self.step('marker', lambda: self.run_marker)

    async def claim(self, run_id):
        self.step('claim', lambda: setattr(self, 'run_marker', run_id), True)

    async def read_setting(self, key):
        return self.step('read-' + key, lambda: self.settings[key])

    async def write_setting(self, key, value):
        if key == SERVICES and (not isinstance(value, Present) or FOREIGN not in value.value.split(':')):
            raise AssertionError('A foreign accessibility service was removed')
        self.step('write-' + key, lambda: self.settings.__setitem__(key, value), True)

    async def installed(self, package):
        return self.step('installed', lambda: True)

    async def stop_app(self, package):
        self.step('stop-' + package, lambda: self.running.__setitem__(package, False), True)

    async def stop_tests(self):
        self.step('stop-tests', lambda: self.running.__setitem__(APP, False), True)

    async def probe(self, path, package=None):
        return self.step('probe', lambda: path in self.files)

    async def copy_preferences(self, source, destination):
        self.step('copy-' + destination, lambda: self.files.__setitem__(destination, self.files[source]), True)

    async def export_preferences(self):
        return self.step('export', lambda: base64.b64encode(self.files[PREFERENCES]))

    async def remove_private(self, path):
        self.step('remove-' + path, lambda: self.files.pop(path, None), True)

    async def remove_marker(self, run_id):
        if self.run_marker != run_id:
            raise DeviceError('Run ownership changed')
        self.step('remove-marker', lambda: setattr(self, 'run_marker', None), True)

    def run_tests(self):
        self.files[PREFERENCES] = b'temporary test settings'
        self.settings = {SERVICES: Present(FOREIGN + ':' + OWN), ENABLED: Present('1')}
        self.running = {APP: True, FIXTURE: True}


class TransactionTest(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='divedeep-transaction-test-')
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)

    def transaction(self, device):
        directory = self.directory / uuid.uuid4().hex
        directory.mkdir()
        return Transaction(device, directory, uuid.uuid4().hex)

    def assert_closed(self, device, transaction):
        self.assertEqual(device.settings, device.original_settings)
        self.assertEqual(device.files, device.original_preferences)
        self.assertFalse(any(device.running.values()))
        self.assertIsNone(device.run_marker)
        self.assertFalse(transaction.journal.exists())

    async def test_success_preserves_foreign_services_and_original_bytes(self):
        device = ModelDevice()
        transaction = self.transaction(device)
        await transaction.prepare()
        device.run_tests()
        start = len(device.events)
        await transaction.restore()
        self.assert_closed(device, transaction)
        committed = [name for name, phase, _ in device.events[start:] if phase == 'committed']
        self.assertLess(committed.index('stop-tests'), committed.index('stop-' + APP))
        self.assertLess(committed.index('stop-' + APP), committed.index('copy-' + PREFERENCES))
        restore_services = len(committed) - 1 - committed[::-1].index('write-' + SERVICES)
        self.assertLess(committed.index('copy-' + PREFERENCES), restore_services)
        self.assertLess(committed.index('write-' + ENABLED), committed.index('remove-marker'))

    async def test_confirmed_absence_removes_only_test_created_preferences(self):
        device = ModelDevice(preferences=False)
        transaction = self.transaction(device)
        await transaction.prepare()
        device.run_tests()
        await transaction.restore()
        self.assert_closed(device, transaction)

    async def test_failed_initial_reads_never_authorize_mutation(self):
        for key in (SERVICES, ENABLED):
            with self.subTest(key=key):
                device = ModelDevice()
                transaction = self.transaction(device)
                device.fault = ('read-' + key, 1, 'before')
                with self.assertRaises(Fault):
                    await transaction.prepare()
                await transaction.restore()
                self.assertFalse(any(write for _, _, write in device.events))
                self.assertEqual(device.files, device.original_preferences)

    async def test_preparation_faults_keep_recovery_evidence_and_restore_safely(self):
        for operation in ('claim', 'stop-' + APP, 'probe', 'export'):
            for boundary in ('before', 'after'):
                with self.subTest(operation=operation, boundary=boundary):
                    device = ModelDevice()
                    transaction = self.transaction(device)
                    device.fault = (operation, 1, boundary)
                    with self.assertRaises(Fault):
                        await transaction.prepare()
                    if transaction.snapshot is not None:
                        self.assertTrue(transaction.journal.is_file())
                    await transaction.restore()
                    self.assert_closed(device, transaction)

    async def test_rollback_faults_stop_dependent_writes_and_can_resume(self):
        operations = ('stop-tests', 'read-' + SERVICES, 'stop-' + APP, 'stop-' + FIXTURE,
                      'copy-' + PREFERENCES, 'export', 'write-' + SERVICES, 'write-' + ENABLED,
                      'remove-marker')
        for operation in operations:
            for boundary in ('before', 'after'):
                with self.subTest(operation=operation, boundary=boundary):
                    device = ModelDevice()
                    transaction = self.transaction(device)
                    await transaction.prepare()
                    device.run_tests()
                    device.fault = (operation, device.counts[operation] + 1, boundary)
                    start = len(device.events)
                    with self.assertRaises(Fault):
                        await transaction.restore()
                    self.assertTrue(transaction.journal.exists())
                    if transaction.phase != Phase.VERIFIED:
                        self.assertIn(transaction.backup, device.files)
                        self.assertTrue((transaction.directory / 'preferences.base64').exists())
                    # No operation is attempted after the failed step.
                    self.assertEqual(device.events[-1][0], operation)
                    if operation in ('stop-tests', 'read-' + SERVICES, 'stop-' + APP, 'stop-' + FIXTURE):
                        self.assertFalse(any(name == 'copy-' + PREFERENCES for name, _, _ in device.events[start:]))
                    recovered = Transaction.recover(device, transaction.journal)
                    await recovered.restore()
                    self.assert_closed(device, recovered)

    async def test_pending_run_blocks_new_preparation_without_writes(self):
        device = ModelDevice()
        transaction = self.transaction(device)
        await transaction.prepare()
        events = len(device.events)
        another = self.transaction(device)
        with self.assertRaises(DeviceError):
            await another.prepare()
        self.assertFalse(any(write for _, _, write in device.events[events:]))
        await transaction.restore()

    async def test_foreign_run_marker_prevents_rollback_mutations(self):
        device = ModelDevice()
        transaction = self.transaction(device)
        await transaction.prepare()
        device.run_marker = uuid.uuid4().hex
        events = len(device.events)
        with self.assertRaises(DeviceError):
            await transaction.restore()
        self.assertFalse(any(write for _, _, write in device.events[events:]))
        self.assertTrue(transaction.journal.exists())

    async def test_failed_journal_publication_keeps_previous_committed_phase(self):
        device = ModelDevice()
        transaction = self.transaction(device)
        await transaction.prepare()
        with patch('e2e_runs.os.fsync', side_effect=OSError('disk flush failed')):
            with self.assertRaises(OSError):
                transaction.save(Phase.VERIFIED)
        self.assertEqual(transaction.phase, Phase.PREPARING)
        self.assertEqual(json.loads(transaction.journal.read_text())['phase'], Phase.PREPARING.value)
        recovered = Transaction.recover(device, transaction.journal)
        await recovered.restore()
        self.assert_closed(device, recovered)


class QueueCommands:
    def __init__(self, outputs):
        self.outputs = iter(outputs)
        self.calls = []

    async def run(self, command, **kwargs):
        self.calls.append(command)
        output = next(self.outputs)
        if isinstance(output, Exception):
            raise output
        return output


class DeviceBoundaryTest(unittest.IsolatedAsyncioTestCase):
    async def test_failed_or_invalid_reads_are_never_empty_service_lists(self):
        for output in (CommandError('disconnected'), Output(0, b'', b'permission denied'),
                       Output(0, b'error response', b'')):
            with self.subTest(output=type(output).__name__):
                commands = QueueCommands([output])
                with self.assertRaises((CommandError, DeviceError)):
                    await Device(commands, 'synthetic-device').disable_service()
                self.assertEqual(len(commands.calls), 1)

    async def test_missing_key_and_empty_present_key_are_distinct(self):
        device = Device(QueueCommands([Output(0, b'null\n', b''), Output(0, b'\n', b'')]), 'synthetic-device')
        self.assertEqual(await device.read_setting(SERVICES), Absent())
        self.assertEqual(await device.read_setting(SERVICES), Present(''))

    async def test_failed_file_probe_is_not_absence(self):
        for output in (CommandError('disconnected'), Output(0, b'', b'')):
            commands = QueueCommands([output])
            with self.assertRaises((CommandError, DeviceError)):
                await Device(commands, 'synthetic-device').probe(PREFERENCES, APP)

    async def test_unconfirmed_stop_does_not_succeed(self):
        commands = QueueCommands([Output(0, b'', b''), Output(0, b'1234\n', b'')])
        with self.assertRaises(DeviceError):
            await Device(commands, 'synthetic-device').stop_app(APP)


if __name__ == '__main__':
    unittest.main()
