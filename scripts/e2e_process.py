"""Owned command trees; device restoration starts only after this scope closes."""
import asyncio
import ctypes
from ctypes import wintypes
from dataclasses import dataclass
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time

from e2e_linux_scope import LinuxScope


class WindowsJob:
    """Keep descendants owned even if their immediate parent exits."""

    def __init__(self):
        class BasicLimits(ctypes.Structure):
            _fields_ = [
                ("process_time", ctypes.c_int64), ("job_time", ctypes.c_int64),
                ("flags", wintypes.DWORD), ("minimum", ctypes.c_size_t),
                ("maximum", ctypes.c_size_t), ("active_limit", wintypes.DWORD),
                ("affinity", ctypes.c_size_t), ("priority", wintypes.DWORD),
                ("scheduling", wintypes.DWORD),
            ]

        class Limits(ctypes.Structure):
            _fields_ = [
                ("basic", BasicLimits), ("io", ctypes.c_uint64 * 6),
                ("process_memory", ctypes.c_size_t), ("job_memory", ctypes.c_size_t),
                ("peak_process_memory", ctypes.c_size_t), ("peak_job_memory", ctypes.c_size_t),
            ]

        self.api = ctypes.WinDLL("kernel32", use_last_error=True)
        self.api.CreateJobObjectW.argtypes = [ctypes.c_void_p, wintypes.LPCWSTR]
        self.api.CreateJobObjectW.restype = wintypes.HANDLE
        self.api.SetInformationJobObject.argtypes = [wintypes.HANDLE, ctypes.c_int, ctypes.c_void_p, wintypes.DWORD]
        self.api.SetInformationJobObject.restype = wintypes.BOOL
        self.api.AssignProcessToJobObject.argtypes = [wintypes.HANDLE, wintypes.HANDLE]
        self.api.AssignProcessToJobObject.restype = wintypes.BOOL
        self.api.TerminateJobObject.argtypes = [wintypes.HANDLE, wintypes.UINT]
        self.api.TerminateJobObject.restype = wintypes.BOOL
        self.api.QueryInformationJobObject.argtypes = [
            wintypes.HANDLE, ctypes.c_int, ctypes.c_void_p, wintypes.DWORD, ctypes.c_void_p,
        ]
        self.api.QueryInformationJobObject.restype = wintypes.BOOL
        self.api.CloseHandle.argtypes = [wintypes.HANDLE]
        self.handle = self.api.CreateJobObjectW(None, None)
        if not self.handle:
            raise ctypes.WinError(ctypes.get_last_error())
        limits = Limits()
        limits.basic.flags = 0x2000  # JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
        if not self.api.SetInformationJobObject(self.handle, 9, ctypes.byref(limits), ctypes.sizeof(limits)):
            self.close()
            raise ctypes.WinError(ctypes.get_last_error())

    def assign(self, process):
        if not self.api.AssignProcessToJobObject(self.handle, int(process._handle)):
            raise ctypes.WinError(ctypes.get_last_error())

    def terminate(self):
        if not self.api.TerminateJobObject(self.handle, 143):
            raise ctypes.WinError(ctypes.get_last_error())

    def close(self):
        if self.handle:
            self.api.CloseHandle(self.handle)
            self.handle = None

    def active_processes(self):
        # JOBOBJECT_BASIC_ACCOUNTING_INFORMATION: four LARGE_INTEGERs and four DWORDs.
        accounting = ctypes.create_string_buffer(48)
        if not self.api.QueryInformationJobObject(self.handle, 1, accounting, len(accounting), None):
            raise ctypes.WinError(ctypes.get_last_error())
        return wintypes.DWORD.from_buffer(accounting, 40).value


@dataclass(frozen=True)
class Output:
    code: int
    stdout: bytes
    stderr: bytes


class CommandError(RuntimeError):
    def __init__(self, message, output=None):
        super().__init__(message)
        self.output = output


class RunCancelled(CommandError):
    pass


@dataclass(frozen=True)
class Idle:
    pass


@dataclass(frozen=True)
class Running:
    pid: int


@dataclass(frozen=True)
class Unconfirmed:
    pid: int
    reason: str


class Commands:
    def __init__(self, timeout=1200):
        self.cancelled = False
        self.deadline = time.monotonic() + timeout
        self.cleanup_deadline = None
        self.state = Idle()

    @property
    def active(self):
        return not isinstance(self.state, Idle)

    def cancel(self, _number=None, _frame=None):
        self.cancelled = True

    def begin_cleanup(self):
        if self.active:
            raise RuntimeError('Cannot restore devices before command-tree closure is confirmed')
        self.cleanup_deadline = time.monotonic() + 120

    async def join_tree(self, job, process):
        until = time.monotonic() + 10
        if process and process.stdin and not process.stdin.closed:
            process.stdin.close()
        if job:
            job.terminate()
            while job.active_processes():
                if time.monotonic() >= until:
                    raise RuntimeError('Native command tree closure could not be confirmed')
                await asyncio.sleep(0.05)
        if process:
            while process.poll() is None:
                if time.monotonic() >= until:
                    raise RuntimeError('Command root closure could not be confirmed')
                await asyncio.sleep(0.05)

    async def run(self, command, *, operation, cwd=None, timeout=30, allowed=(0,)):
        if self.active:
            raise RuntimeError('Commands must have one sequential owner')
        cleaning = self.cleanup_deadline is not None
        if self.cancelled and not cleaning:
            raise RunCancelled('Run cancelled')
        until = min(time.monotonic() + timeout, self.cleanup_deadline if cleaning else self.deadline)
        if time.monotonic() >= until:
            raise CommandError(f'{operation}: timed out before starting')
        if os.name == 'nt':
            job = WindowsJob()
        elif sys.platform == 'linux':
            job = LinuxScope()
        else:
            raise CommandError('Owned command scopes require Windows or Linux')
        process = None
        pending = None
        try:
            with tempfile.TemporaryFile() as stdout, tempfile.TemporaryFile() as stderr:
                try:
                    if os.name == 'nt':
                        process = subprocess.Popen(
                            [sys.executable, __file__, '--worker', *map(str, command)],
                            cwd=cwd, stdin=subprocess.PIPE, stdout=stdout, stderr=stderr,
                            creationflags=subprocess.CREATE_NEW_PROCESS_GROUP | subprocess.CREATE_NO_WINDOW,
                        )
                    else:
                        process = subprocess.Popen(
                            [sys.executable, str(Path(__file__).with_name('e2e_linux_scope.py')),
                             str(job.receipt.fileno()), *map(str, command)],
                            cwd=cwd, stdin=subprocess.PIPE, stdout=stdout, stderr=stderr,
                            start_new_session=True, pass_fds=(job.receipt.fileno(),),
                        )
                    self.state = Running(process.pid)
                    job.assign(process)
                    process.stdin.write(b'1')
                    process.stdin.flush()
                    if os.name == 'nt':
                        process.stdin.close()
                    while process.poll() is None:
                        if self.cancelled and not cleaning:
                            raise RunCancelled('Run cancelled')
                        if time.monotonic() >= until:
                            raise CommandError(f'{operation}: timed out')
                        await asyncio.sleep(0.05)
                except CommandError as error:
                    pending = error
                finally:
                    try:
                        await self.join_tree(job, process)
                    except Exception as error:
                        if process:
                            self.state = Unconfirmed(process.pid, type(error).__name__)
                        raise
                    self.state = Idle()
                stdout.seek(0)
                stderr.seek(0)
                output = Output(process.returncode, stdout.read(), stderr.read())
                if pending:
                    pending.output = output
                    raise pending
                if output.code not in allowed:
                    # Commands can read private preferences. Never put their output in exceptions/logs.
                    raise CommandError(f'{operation}: exit {output.code}', output)
                return output
        finally:
            if job:
                job.close()


def worker(command):
    # No descendants start until the host has assigned this worker to its Job.
    if sys.stdin.buffer.read(1) != b'1':
        return 1
    return subprocess.Popen(command, stdin=subprocess.DEVNULL, creationflags=subprocess.CREATE_NO_WINDOW).wait()


if __name__ == '__main__':
    if sys.argv[1:2] != ['--worker']:
        raise SystemExit('This module is an internal process adapter')
    raise SystemExit(worker(sys.argv[2:]))
