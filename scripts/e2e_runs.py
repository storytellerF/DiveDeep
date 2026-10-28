"""Private, durable run identities; device markers can locate recovery after host exit."""
import hashlib
import json
import os
from pathlib import Path
import re


def write_json(path, value):
    """Publish a complete record before the external side effect it authorizes."""
    write_bytes(path, json.dumps(value, indent=2).encode('utf-8'))


def write_bytes(path, value):
    path = Path(path)
    temporary = path.with_suffix('.tmp')
    with temporary.open('wb') as stream:
        stream.write(value)
        stream.flush()
        os.fsync(stream.fileno())
    temporary.replace(path)
    if os.name != 'nt':
        descriptor = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(descriptor)
        finally:
            os.close(descriptor)


class RunStore:
    def __init__(self, serial, root=None):
        if root is None:
            base = (Path(os.environ.get('LOCALAPPDATA', Path.home() / 'AppData/Local')) if os.name == 'nt'
                    else Path(os.environ.get('XDG_STATE_HOME', Path.home() / '.local/state')))
            root = base / 'DiveDeep/e2e/runs'
        self.serial = serial
        self.directory = Path(root).expanduser().resolve() / hashlib.sha256(serial.encode()).hexdigest()

    def path(self, run_id):
        if not re.fullmatch('[0-9a-f]{32}', run_id):
            raise ValueError('Invalid recovery run id')
        return self.directory / run_id / 'run.json'

    def create(self, run_id):
        self.directory.mkdir(parents=True, exist_ok=True, mode=0o700)
        directory = self.path(run_id).parent
        directory.mkdir(mode=0o700)
        return directory

    def pending(self):
        return sorted(self.directory.glob('*/run.json'))

    def discover(self, marker):
        if marker:
            journal = self.path(marker)
            if not journal.is_file():
                raise RuntimeError('Device has an unresolved run; its private journal is not on this host. '
                                   'Use --recover with the original journal path')
            return journal
        journals = self.pending()
        if len(journals) != 1:
            raise RuntimeError('Expected one pending recovery journal; found ' + str(len(journals)) +
                               '. Specify --recover PATH if needed. Registry: ' + str(self.directory))
        return journals[0]
