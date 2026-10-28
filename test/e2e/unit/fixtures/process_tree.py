"""Native descendants used to verify command-tree ownership on each host OS."""
from pathlib import Path
import os
import subprocess
import sys
import time

mode, directory = sys.argv[1:]
root = Path(directory)
detached = mode.startswith('detached-')
mode = mode.removeprefix('detached-')


def spawn(next_mode):
    options = ({'creationflags': subprocess.CREATE_NEW_PROCESS_GROUP} if os.name == 'nt'
               else {'start_new_session': True}) if detached else {}
    prefix = 'detached-' if detached else ''
    return subprocess.Popen([sys.executable, __file__, prefix + next_mode, directory], **options)


if mode == 'heartbeat':
    while True:
        (root / 'heartbeat').write_text(str(time.monotonic()))
        time.sleep(0.03)
elif mode == 'child':
    spawn('heartbeat')
    time.sleep(60)
else:
    spawn('child')
    while not (root / 'heartbeat').exists():
        time.sleep(0.03)
    if mode == 'hold':
        time.sleep(60)
