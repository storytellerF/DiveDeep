"""Exit without finally while an owned detached tree is running."""
import asyncio
import os
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[4] / 'scripts'))
from e2e_process import Commands


async def main():
    root = Path(sys.argv[1])
    task = asyncio.create_task(Commands().run(
        [sys.executable, str(Path(__file__).with_name('process_tree.py')), 'detached-hold', str(root)],
        operation='crash-fixture', timeout=20,
    ))
    while not (root / 'heartbeat').exists():
        if task.done():
            await task
            raise RuntimeError('Command ended before the crash boundary')
        await asyncio.sleep(0.03)
    os._exit(77)


asyncio.run(main())
