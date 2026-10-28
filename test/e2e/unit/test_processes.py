"""The native process owner joins children before allowing device recovery."""
import asyncio
from pathlib import Path
import sys
import subprocess
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[3] / 'scripts'))
from e2e_process import Commands, CommandError, RunCancelled, Unconfirmed
from e2e_linux_scope import LinuxScope

TREE = Path(__file__).parent / 'fixtures/process_tree.py'


class ProcessOwnershipTest(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='divedeep-process-test-')
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)

    async def assert_no_more_writes(self):
        heartbeat = (self.root / 'heartbeat').read_bytes()
        await asyncio.sleep(0.15)
        self.assertEqual((self.root / 'heartbeat').read_bytes(), heartbeat)

    async def test_successful_root_cannot_leave_running_descendants(self):
        commands = Commands()
        output = await commands.run([sys.executable, str(TREE), 'exit', str(self.root)], operation='tree', timeout=20)
        self.assertEqual(output.code, 0)
        commands.begin_cleanup()
        await self.assert_no_more_writes()

    async def test_successful_root_cannot_leave_detached_descendants(self):
        commands = Commands()
        output = await commands.run([sys.executable, str(TREE), 'detached-exit', str(self.root)],
                                    operation='detached-tree', timeout=20)
        self.assertEqual(output.code, 0)
        commands.begin_cleanup()
        await self.assert_no_more_writes()

    async def test_timeout_stops_detached_descendants(self):
        commands = Commands()
        with self.assertRaises(CommandError):
            await commands.run([sys.executable, str(TREE), 'detached-hold', str(self.root)],
                               operation='detached-tree', timeout=2)
        commands.begin_cleanup()
        self.assertTrue((self.root / 'heartbeat').exists())
        await self.assert_no_more_writes()

    async def test_host_crash_still_stops_detached_descendants(self):
        # The native child deliberately bypasses all Python finally blocks.
        process = subprocess.Popen([sys.executable, str(TREE.with_name('crash_process_owner.py')), str(self.root)])
        try:
            async def exited():
                while process.poll() is None:
                    await asyncio.sleep(0.03)
            await asyncio.wait_for(exited(), 15)
            self.assertEqual(process.returncode, 77)
            async def stopped():
                while True:
                    previous = (self.root / 'heartbeat').read_bytes()
                    await asyncio.sleep(0.25)
                    if (self.root / 'heartbeat').read_bytes() == previous:
                        return
            await asyncio.wait_for(stopped(), 10)
            await self.assert_no_more_writes()
        finally:
            if process.poll() is None:
                process.kill()
                process.wait(timeout=10)

    async def test_cancellation_joins_native_tree_before_cleanup(self):
        await self.verify_cancellation('hold')

    async def test_cancellation_joins_detached_tree_before_cleanup(self):
        await self.verify_cancellation('detached-hold')

    async def verify_cancellation(self, mode):
        commands = Commands()
        task = asyncio.create_task(commands.run([sys.executable, str(TREE), mode, str(self.root)],
                                                operation='tree', timeout=20))
        try:
            async def started():
                while not (self.root / 'heartbeat').exists():
                    await asyncio.sleep(0.03)
            await asyncio.wait_for(started(), 15)
            with self.assertRaises(RuntimeError):
                commands.begin_cleanup()
            commands.cancel()
            with self.assertRaises(RunCancelled):
                await task
            commands.begin_cleanup()
            await self.assert_no_more_writes()
        finally:
            if not task.done():
                commands.cancel()
                try:
                    await task
                except RunCancelled:
                    pass

    async def test_command_timeout_reaps_its_tree(self):
        commands = Commands()
        with self.assertRaises(CommandError):
            await commands.run([sys.executable, str(TREE), 'hold', str(self.root)], operation='tree', timeout=2)
        commands.begin_cleanup()
        if (self.root / 'heartbeat').exists():
            await self.assert_no_more_writes()

    async def test_failed_exec_has_no_descendants_and_allows_cleanup(self):
        commands = Commands()
        with self.assertRaises(CommandError):
            await commands.run([str(self.root / 'does-not-exist')], operation='missing-executable')
        commands.begin_cleanup()

    @unittest.skipUnless(sys.platform == 'linux', 'Linux supervisor acknowledgement boundary')
    async def test_supervisor_exit_without_acknowledgement_is_not_closure(self):
        scope = LinuxScope()
        process = subprocess.Popen([sys.executable, '-c', 'pass'], stdin=subprocess.PIPE)
        scope.assign(process)
        try:
            while process.poll() is None:
                await asyncio.sleep(0.03)
            with self.assertRaisesRegex(RuntimeError, 'without confirming'):
                scope.active_processes()
        finally:
            scope.terminate()
            scope.close()

    async def test_failed_closure_confirmation_cannot_authorize_recovery(self):
        class FailedConfirmation(Commands):
            async def join_tree(self, job, process):
                await super().join_tree(job, process)
                raise RuntimeError('Closure verification unavailable')

        commands = FailedConfirmation()
        with self.assertRaisesRegex(RuntimeError, 'Closure verification'):
            await commands.run([sys.executable, '-c', 'pass'], operation='process')
        self.assertIsInstance(commands.state, Unconfirmed)
        with self.assertRaises(RuntimeError):
            commands.begin_cleanup()


if __name__ == '__main__':
    unittest.main()
