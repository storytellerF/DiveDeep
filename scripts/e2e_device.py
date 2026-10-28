"""Validated device operations and the run's recoverable device transaction."""
import base64
from dataclasses import dataclass, replace
from enum import Enum
import hashlib
import json
from pathlib import Path
import re
import shlex

from e2e_runs import write_bytes, write_json

APP = 'com.storyteller_f.divedeep'
FIXTURE = APP + '.fixture'
SERVICE = APP + '/' + APP + '.DiveDeepAccessibilityService'
PREFERENCES = 'files/datastore/dive_deep_state.preferences_pb'
MARKER = '/data/local/tmp/divedeep-e2e.run'
SERVICES = 'enabled_accessibility_services'
ENABLED = 'accessibility_enabled'


class DeviceError(RuntimeError):
    pass


@dataclass(frozen=True)
class Absent:
    pass


@dataclass(frozen=True)
class Present:
    value: str


@dataclass(frozen=True)
class Uncaptured:
    pass


@dataclass(frozen=True)
class Saved:
    checksum: str


Setting = Present | Absent
PreferenceState = Saved | Absent | Uncaptured


def setting(key: str, value: str | None) -> Setting:
    if value is None:
        return Absent()
    if key == ENABLED and value not in ('0', '1'):
        raise DeviceError('Invalid accessibility_enabled value')
    if key == SERVICES and value and any(not re.fullmatch(r'[\w.]+/[\w.$]+', item) for item in value.split(':')):
        raise DeviceError('Invalid accessibility service list')
    return Present(value)


@dataclass(frozen=True)
class Snapshot:
    services: Setting
    accessibility: Setting
    preferences: PreferenceState = Uncaptured()


class Phase(Enum):
    CLAIMING = 'claiming'
    PREPARING = 'preparing'
    TESTING = 'testing'
    STOPPING = 'stopping'
    RESTORING_PREFERENCES = 'restoring-preferences'
    RESTORING_SERVICES = 'restoring-services'
    VERIFIED = 'verified'


class Device:
    def __init__(self, commands, serial, adb='adb'):
        self.commands, self.serial, self.adb = commands, serial, adb

    async def call(self, args, *, operation, allowed=(0,), timeout=30):
        return await self.commands.run([self.adb, '-s', self.serial, *args], operation=operation,
                                       allowed=allowed, timeout=timeout)

    async def shell(self, args, *, operation, allowed=(0,)):
        # One remote shell string preserves empty values and quoting on every host.
        return await self.call(['shell', shlex.join(args)], operation=operation, allowed=allowed)

    async def read(self, args, *, operation, exec_out=False):
        output = (await self.call(['exec-out', *args], operation=operation) if exec_out else
                  await self.shell(args, operation=operation))
        if output.stderr.strip():
            raise DeviceError('Unconfirmed device read: ' + operation)
        return output.stdout

    async def installed(self, package):
        output = await self.read(['pm', 'list', 'packages', package], operation='list-packages')
        lines = output.decode().splitlines()
        if any(not re.fullmatch(r'package:[\w.]+', line) for line in lines):
            raise DeviceError('Invalid package-list response')
        return 'package:' + package in lines

    async def read_setting(self, key):
        output = await self.read(['settings', 'get', 'secure', key], operation='read-' + key)
        value = output.decode().strip()
        return setting(key, None if value == 'null' else value)

    async def write_setting(self, key, value):
        if isinstance(value, Absent):
            args = ['settings', 'delete', 'secure', key]
        else:
            setting(key, value.value)
            args = ['settings', 'put', 'secure', key, value.value]
        await self.shell(args, operation='write-' + key)
        if await self.read_setting(key) != value:
            raise DeviceError('Secure setting verification failed: ' + key)

    async def disable_service(self):
        current = await self.read_setting(SERVICES)
        if isinstance(current, Absent):
            return
        own = {SERVICE, APP + '/.DiveDeepAccessibilityService'}
        remaining = [item for item in current.value.split(':') if item and item not in own]
        if len(remaining) != len([item for item in current.value.split(':') if item]):
            await self.write_setting(SERVICES, Present(':'.join(remaining)) if remaining else Absent())

    async def force_stop(self, package):
        output = await self.shell(['am', 'force-stop', package], operation='stop-' + package)
        if output.stdout.strip() or output.stderr.strip():
            raise DeviceError('Package stop was not acknowledged: ' + package)

    async def stop_tests(self):
        # Instrumentation runs in the target app's process. End that writer first;
        # an original accessibility service may rebind, so app quiescence is checked
        # separately after disabling that service.
        await self.force_stop(APP)

    async def stop_app(self, package):
        await self.force_stop(package)
        output = await self.shell(['pidof', package], operation='verify-stopped-' + package, allowed=(0, 1))
        if output.code != 1 or output.stdout.strip() or output.stderr.strip():
            raise DeviceError('Could not confirm that package stopped: ' + package)

    async def probe(self, path, package=None):
        body = (f'if [ -f {shlex.quote(path)} ]; then echo present; '
                f'elif [ -e {shlex.quote(path)} ]; then exit 2; else echo absent; fi')
        args = (['run-as', package] if package else []) + ['sh', '-c', body]
        value = (await self.read(args, operation='probe-file')).strip()
        if value not in (b'present', b'absent'):
            raise DeviceError('Invalid file probe response')
        return value == b'present'

    async def marker(self):
        if not await self.probe(MARKER):
            return None
        value = (await self.read(['cat', MARKER], operation='read-run-marker')).decode().strip()
        if not re.fullmatch('[0-9a-f]{32}', value):
            raise DeviceError('Unreadable pending-run marker; recover before reuse')
        return value

    async def claim(self, run_id):
        if await self.marker() is not None:
            raise DeviceError('Device has a pending DiveDeep run; recover it before reuse')
        await self.shell(['sh', '-c', f'printf %s {run_id} > {MARKER}'], operation='write-run-marker')
        if await self.marker() != run_id:
            raise DeviceError('Run-marker verification failed')

    async def remove_marker(self, run_id):
        if await self.marker() != run_id:
            raise DeviceError('Run-marker ownership changed')
        await self.shell(['rm', MARKER], operation='remove-run-marker')
        if await self.marker() is not None:
            raise DeviceError('Run marker remained after removal')

    async def copy_preferences(self, source, destination):
        await self.shell(['run-as', APP, 'cp', source, destination], operation='copy-private-preferences')

    async def export_preferences(self):
        output = await self.read(['run-as', APP, 'base64', PREFERENCES], operation='read-private-preferences', exec_out=True)
        encoded = b''.join(output.split())
        base64.b64decode(encoded, validate=True)
        return encoded

    async def remove_private(self, path):
        await self.shell(['run-as', APP, 'rm', '-f', path], operation='remove-owned-private-file')


class Transaction:
    """The only owner of pre-run state. Unconfirmed steps cannot authorize later writes."""
    def __init__(self, device, directory, run_id):
        if not re.fullmatch('[0-9a-f]{32}', run_id):
            raise ValueError('Invalid run id')
        self.device, self.directory, self.run_id = device, Path(directory), run_id
        self.snapshot: Snapshot | None = None
        self.phase = Phase.CLAIMING

    @property
    def backup(self):
        return f'files/datastore/divedeep-e2e-{self.run_id}.backup'

    @property
    def journal(self):
        return self.directory / 'run.json'

    def save(self, phase):
        snapshot = self.snapshot
        if snapshot is None:
            return
        pref = snapshot.preferences
        data = {
            'schema': 1, 'run_id': self.run_id, 'serial': self.device.serial, 'phase': phase.value,
            'services': snapshot.services.value if isinstance(snapshot.services, Present) else None,
            'accessibility': snapshot.accessibility.value if isinstance(snapshot.accessibility, Present) else None,
            'preferences': {'kind': 'saved', 'checksum': pref.checksum} if isinstance(pref, Saved) else
                           {'kind': 'absent' if isinstance(pref, Absent) else 'uncaptured'},
        }
        write_json(self.journal, data)
        self.phase = phase

    async def prepare(self):
        if await self.device.marker() is not None:
            raise DeviceError('Device has a pending run; use --recover before starting another')
        # Finish all reads before publishing intent or making a device mutation.
        services = await self.device.read_setting(SERVICES)
        enabled = await self.device.read_setting(ENABLED)
        installed = await self.device.installed(APP)
        self.snapshot = Snapshot(services, enabled)
        self.save(Phase.CLAIMING)
        await self.device.claim(self.run_id)
        self.save(Phase.PREPARING)
        await self.device.disable_service()
        await self.device.stop_app(APP)
        if installed and await self.device.probe(PREFERENCES, APP):
            await self.device.copy_preferences(PREFERENCES, self.backup)
            encoded = await self.device.export_preferences()
            write_bytes(self.directory / 'preferences.base64', encoded)
            checksum = hashlib.sha256(base64.b64decode(encoded, validate=True)).hexdigest()
            preferences = Saved(checksum)
        else:
            preferences = Absent()
        self.snapshot = replace(self.snapshot, preferences=preferences)
        self.save(Phase.PREPARING)

    async def restore(self):
        if self.snapshot is None:
            return
        marker = await self.device.marker()
        if self.phase == Phase.VERIFIED:
            # State is already restored. Finish only idempotent disposal after an interrupted close.
            if marker not in (None, self.run_id):
                raise DeviceError('Run-marker ownership changed')
            await self.dispose(marker is not None)
            return
        if marker is None and self.phase == Phase.CLAIMING:
            self.discard()
            return
        if marker != self.run_id:
            raise DeviceError('Cannot recover without matching run ownership')
        self.save(Phase.STOPPING)
        # Each confirmed step is a precondition for the next. Stop on the first failure.
        await self.device.stop_tests()
        await self.device.disable_service()
        await self.device.stop_app(APP)
        await self.device.stop_app(FIXTURE)
        self.save(Phase.RESTORING_PREFERENCES)
        preferences = self.snapshot.preferences
        if isinstance(preferences, Saved):
            await self.device.copy_preferences(self.backup, PREFERENCES)
            encoded = await self.device.export_preferences()
            if hashlib.sha256(base64.b64decode(encoded, validate=True)).hexdigest() != preferences.checksum:
                raise DeviceError('Preference restoration verification failed')
        elif isinstance(preferences, Absent) and await self.device.installed(APP):
            await self.device.remove_private(PREFERENCES)
            if await self.device.probe(PREFERENCES, APP):
                raise DeviceError('Test-created preferences remained')
        self.save(Phase.RESTORING_SERVICES)
        await self.device.write_setting(SERVICES, self.snapshot.services)
        await self.device.write_setting(ENABLED, self.snapshot.accessibility)
        if (await self.device.read_setting(SERVICES), await self.device.read_setting(ENABLED)) != (
                self.snapshot.services, self.snapshot.accessibility):
            raise DeviceError('Device settings did not restore')
        # Commit restored state before disposing anything needed to retry rollback.
        self.save(Phase.VERIFIED)
        await self.dispose(True)

    async def dispose(self, has_marker):
        if await self.device.installed(APP):
            await self.device.remove_private(self.backup)
        if has_marker:
            await self.device.remove_marker(self.run_id)
        self.discard()

    def discard(self):
        (self.directory / 'preferences.base64').unlink(missing_ok=True)
        (self.directory / 'preferences.tmp').unlink(missing_ok=True)
        self.journal.unlink(missing_ok=True)
        self.journal.with_suffix('.tmp').unlink(missing_ok=True)
        self.directory.rmdir()

    @classmethod
    def recover(cls, device, journal):
        journal = Path(journal).resolve()
        data = json.loads(journal.read_text(encoding='utf-8'))
        if data['schema'] != 1 or data['serial'] != device.serial:
            raise DeviceError('Recovery journal does not match the selected device')
        transaction = cls(device, journal.parent, data['run_id'])
        pref = data['preferences']
        if pref['kind'] == 'saved' and re.fullmatch('[0-9a-f]{64}', pref['checksum']):
            preferences = Saved(pref['checksum'])
        elif pref['kind'] == 'absent':
            preferences = Absent()
        elif pref['kind'] == 'uncaptured':
            preferences = Uncaptured()
        else:
            raise DeviceError('Invalid preference recovery state')
        transaction.snapshot = Snapshot(setting(SERVICES, data['services']), setting(ENABLED, data['accessibility']), preferences)
        transaction.phase = Phase(data['phase'])
        return transaction
