"""Linux command scope: a dedicated subreaper owns even setsid/double-fork descendants."""
import ctypes
import os
from pathlib import Path
import selectors
import signal
import subprocess
import sys
import tempfile


class LinuxScope:
    def __init__(self):
        self.receipt = tempfile.TemporaryFile()
        self.process = None

    def assign(self, process):
        self.process = process

    def terminate(self):
        # EOF also arrives if the host crashes. Keep the supervisor alive to reap descendants.
        if self.process and not self.process.stdin.closed:
            self.process.stdin.close()

    def active_processes(self):
        if self.process is None:
            return 0
        if self.process.poll() is None:
            return 1
        self.receipt.seek(0)
        if self.receipt.read() != b'closed':
            raise RuntimeError('Linux supervisor exited without confirming descendant closure')
        return 0

    def close(self):
        self.receipt.close()


def supervise(command, receipt_fd):
    # Kernel adoption survives setsid() and intermediate-parent exit. This scope is
    # deliberately a separate process so it can never adopt unrelated host children.
    # https://man7.org/linux/man-pages/man2/PR_SET_CHILD_SUBREAPER.2const.html
    libc = ctypes.CDLL(None, use_errno=True)
    libc.prctl.argtypes = [ctypes.c_int, ctypes.c_ulong, ctypes.c_ulong, ctypes.c_ulong, ctypes.c_ulong]
    libc.prctl.restype = ctypes.c_int
    if libc.prctl(36, 1, 0, 0, 0) != 0:  # PR_SET_CHILD_SUBREAPER
        raise OSError(ctypes.get_errno(), 'Cannot establish Linux child subreaper')
    stopping = False

    def stop(_number, _frame):
        nonlocal stopping
        stopping = True

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    # The host must finish creating the scope before any command can start.
    if os.read(0, 1) != b'1':
        os.write(receipt_fd, b'closed')
        return 1
    try:
        child = subprocess.Popen(command, stdin=subprocess.DEVNULL)
    except OSError as error:
        # Popen has reaped its failed exec child; no command descendants were created.
        print('Could not start command: ' + type(error).__name__, file=sys.stderr)
        os.write(receipt_fd, b'closed')
        return 127
    root_code = None
    children_file = Path(f'/proc/self/task/{os.getpid()}/children')
    with selectors.DefaultSelector() as selector:
        selector.register(0, selectors.EVENT_READ)
        while True:
            try:
                while True:
                    pid, status = os.waitpid(-1, os.WNOHANG)
                    if pid == 0:
                        break
                    if pid == child.pid:
                        root_code = os.waitstatus_to_exitcode(status)
                        child.returncode = root_code
                        stopping = True
            except ChildProcessError:
                # ECHILD proves the entire adopted tree is gone, not just its root.
                os.write(receipt_fd, b'closed')
                return root_code if root_code is not None else 1
            if stopping:
                # Only unreaped direct children are signalled. Their PIDs cannot be
                # reused before this supervisor reaps them; their children are adopted.
                for pid in children_file.read_text().split():
                    try:
                        os.kill(int(pid), signal.SIGKILL)
                    except ProcessLookupError:
                        pass
            for key, _events in selector.select(0.03):
                if not os.read(key.fd, 1):
                    stopping = True
                    selector.unregister(key.fd)


if __name__ == '__main__':
    if sys.platform != 'linux':
        raise SystemExit('The subreaper adapter requires Linux')
    raise SystemExit(supervise(sys.argv[2:], int(sys.argv[1])))
