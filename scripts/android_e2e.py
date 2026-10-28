#!/usr/bin/env python3
"""Build, run Kotlin cross-app tests, and verify restoration under an external device lease."""
import argparse
import asyncio
import json
import os
from pathlib import Path
import signal
import sys
import time
import uuid

from e2e_device import APP, FIXTURE, Device, DeviceError, Phase, Transaction
from e2e_process import Commands, CommandError, RunCancelled
from e2e_runs import RunStore, write_json

ROOT = Path(__file__).resolve().parent.parent


class Runner:
    def __init__(self, args):
        self.args = args
        self.commands = Commands(args.timeout)
        self.device = Device(self.commands, args.device, args.adb)
        self.run_id = uuid.uuid4().hex
        self.runs = RunStore(args.device, args.state_dir)
        self.report = Path(args.report_dir or ROOT / 'test/e2e/reports' / self.run_id).resolve()
        self.report.mkdir(parents=True, exist_ok=True)

    async def gradle(self, arguments, name, timeout=900):
        command = ['cmd.exe', '/d', '/c', 'gradlew.bat'] if os.name == 'nt' else [str(ROOT / 'gradlew')]
        try:
            output = await self.commands.run(command + arguments + ['--no-daemon'], cwd=ROOT, operation=name, timeout=timeout)
        except CommandError as error:
            if error.output:
                (self.report / (name + '.log')).write_bytes(error.output.stdout + error.output.stderr)
            raise
        (self.report / (name + '.log')).write_bytes(output.stdout + output.stderr)

    async def ensure_llmd(self):
        variant = self.args.llmd_variant
        package = 'com.storytellerf.llmd' + ('' if variant == 'release' else '.' + variant)
        if not await self.provider_available(package):
            apk = self.args.llmd_apk
            if not apk:
                repository = Path(self.args.llmd_repo) if self.args.llmd_repo else ROOT.parent / 'llmd'
                app = repository / 'app'
                if variant != 'debug' or not (app / 'package.json').is_file():
                    raise DeviceError('Install the selected LLMD variant or provide LLMD_APK/LLMD_REPO')
                npm = ['cmd.exe', '/d', '/c', 'npm.cmd'] if os.name == 'nt' else ['npm']
                if not (app / 'node_modules').is_dir():
                    await self.commands.run(npm + ['ci'], cwd=app, operation='llmd-dependencies', timeout=300)
                await self.commands.run(npm + ['run', 'tauri', 'android', 'build', '--', '--debug', '--apk'],
                                        cwd=app, operation='build-llmd', timeout=900)
                apks = list((app / 'src-tauri/gen/android/app/build/outputs/apk').glob('**/debug/*.apk'))
                if not apks:
                    raise DeviceError('No built LLMD Debug APK was found')
                apk = str(max(apks, key=lambda item: item.stat().st_mtime))
            await self.install(Path(apk))
        if not await self.provider_available(package):
            raise DeviceError('Selected LLMD variant does not expose its IPC service after installation')

    async def provider_available(self, package):
        if not await self.device.installed(package):
            return False
        output = await self.device.read(['dumpsys', 'package', package], operation='verify-llmd-service')
        return b'com.storytellerf.llmd.action.BIND_IPC' in output and b'LlmdIpcService' in output

    async def install(self, apk):
        if not apk.is_file():
            raise FileNotFoundError('Required APK does not exist: ' + str(apk))
        await self.device.call(['install', '-r', '-t', str(apk.resolve())], operation='install-apk', timeout=120)

    async def tests(self, transaction):
        await self.install(ROOT / 'androidApp/build/outputs/apk/debug/androidApp-debug.apk')
        await self.install(ROOT / 'apps/android-test-fixture/app/build/outputs/apk/debug/androidTestFixture-debug.apk')
        await self.device.shell(['input', 'keyevent', 'KEYCODE_WAKEUP'], operation='wake-device')
        await self.device.shell(['wm', 'dismiss-keyguard'], operation='dismiss-keyguard')
        transaction.save(Phase.TESTING)
        await self.gradle([
            ':androidApp:connectedDebugAndroidTest',
            '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true',
            '-Pandroid.testInstrumentationRunnerArguments.notAnnotation=org.junit.Ignore',
            '-Pandroid.testInstrumentationRunnerArguments.class=com.storyteller_f.divedeep.OverlayLlmdIntegrationTest',
            '-Pandroid.testInstrumentationRunnerArguments.realLlmd=true',
            '-Pandroid.testInstrumentationRunnerArguments.hostRunId=' + transaction.run_id,
            '-Pandroid.testInstrumentationRunnerArguments.llmdVariant=' + self.args.llmd_variant,
        ], 'integration')

    async def diagnostics(self, run_id):
        # Diagnostic reads are independent of rollback and never authorize further mutations.
        try:
            output = await self.device.shell(['logcat', '-d', '-t', '1000', '-s', 'DiveDeepOverlay', 'DiveDeepAccessibility'],
                                             operation='collect-diagnostics')
            (self.report / 'overlay.log').write_bytes(output.stdout)
            await self.device.call(['pull', f'/sdcard/Android/data/{APP}/files/e2e/{run_id}',
                                    str(self.report / 'device')], operation='collect-ui-artifacts')
        except Exception as error:
            (self.report / 'diagnostics-error.txt').write_text(type(error).__name__, encoding='utf-8')

    async def execute(self):
        started = time.monotonic()
        transaction = None
        failure = None
        recovery_failure = None
        try:
            if self.args.recover:
                journal = (self.runs.discover(await self.device.marker()) if self.args.recover == 'pending'
                           else Path(self.args.recover))
                transaction = Transaction.recover(self.device, journal)
            else:
                if self.runs.pending():
                    raise DeviceError('Unresolved run in ' + str(self.runs.directory) + '; use --recover')
                await self.gradle([':androidApp:assembleDebug', ':androidApp:assembleDebugAndroidTest',
                                   ':androidTestFixture:assembleDebug'], 'build')
                directory = self.runs.create(self.run_id)
                transaction = Transaction(self.device, directory, self.run_id)
                # Publish the recovery entry before any device mutation, including marker creation.
                write_json(self.report / 'run.json', {'status': 'running', 'run_id': self.run_id,
                                                     'recovery_journal': str(transaction.journal)})
                print('Recovery journal: ' + str(transaction.journal), flush=True)
                await transaction.prepare()
                await self.ensure_llmd()
                await self.tests(transaction)
        except Exception as error:
            failure = error
        finally:
            # Every command has joined its owned tree before this transition.
            try:
                self.commands.begin_cleanup()
            except Exception as error:
                recovery_failure = error
            if transaction and recovery_failure is None:
                try:
                    if transaction.snapshot is None:
                        transaction.discard()
                    else:
                        await transaction.restore()
                except Exception as error:
                    recovery_failure = error
                await self.diagnostics(transaction.run_id)
        status = 'needs-recovery' if recovery_failure else 'cancelled' if isinstance(failure, RunCancelled) else (
            'failed' if failure else 'passed')
        result = {'status': status, 'duration_seconds': round(time.monotonic() - started, 2),
                  'error': str(failure) if failure else None,
                  'recovery_error': str(recovery_failure) if recovery_failure else None}
        if recovery_failure:
            if transaction and transaction.journal.exists():
                result['recovery_journal'] = str(transaction.journal)
            if self.commands.active:
                result['host_process_closure'] = str(self.commands.state)
        write_json(self.report / 'run.json', result)
        print(json.dumps(result, ensure_ascii=False), flush=True)
        print('Reports: ' + str(self.report), flush=True)
        return 2 if recovery_failure else 130 if isinstance(failure, RunCancelled) else 1 if failure else 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--device', default=os.environ.get('DEVICE'))
    parser.add_argument('--adb', default=os.environ.get('ADB', 'adb'))
    parser.add_argument('--llmd-variant', choices=['release', 'daily', 'debug'], default=os.environ.get('LLMD_VARIANT', 'release'))
    parser.add_argument('--llmd-apk', default=os.environ.get('LLMD_APK'))
    parser.add_argument('--llmd-repo', default=os.environ.get('LLMD_REPO'))
    parser.add_argument('--report-dir', default=os.environ.get('E2E_REPORT_DIR'))
    parser.add_argument('--state-dir', default=os.environ.get('E2E_STATE_DIR'), help='Private persistent recovery registry')
    parser.add_argument('--recover', nargs='?', const='pending',
                        help='Discover pending recovery, or supply its private run.json; requires the device lease')
    parser.add_argument('--timeout', type=int, default=1200, help='Work deadline; cleanup has a separate 120-second budget')
    args = parser.parse_args()
    if not args.device or args.timeout <= 0 or args.llmd_variant not in ('release', 'daily', 'debug'):
        parser.error('An explicitly leased --device and a positive --timeout are required')
    # Both Gradle and ADB must use the same leased serial.
    os.environ['ANDROID_SERIAL'] = args.device
    runner = Runner(args)
    signal.signal(signal.SIGINT, runner.commands.cancel)
    signal.signal(signal.SIGTERM, runner.commands.cancel)
    if hasattr(signal, 'SIGBREAK'):
        signal.signal(signal.SIGBREAK, runner.commands.cancel)
    return asyncio.run(runner.execute())


if __name__ == '__main__':
    sys.exit(main())
