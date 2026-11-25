"""Generate classroom circuits, vector reports and PNG state galleries."""

import argparse
import json
from pathlib import Path
import sys

from circuits import half_adder, multiplexer, register

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / 'client-python'))
from logisim_agent import Client, write_vectors


def generate(destination, core=None):
    destination = destination.resolve()
    destination.mkdir(parents=True, exist_ok=True)
    plans = [
        ('half-adder.circ', half_adder, 'half-adder-states.json', 'half-adder', True),
        ('register4.circ', register, 'register-vectors.json', 'register', False),
        ('mux2x4.circ', multiplexer, 'mux-vectors.json', 'mux', True),
    ]
    if any((destination / path).exists() or (destination / f'{stem}-states').exists() for path, _, _, stem, _ in plans):
        raise FileExistsError(f'Choose a fresh example output directory: {destination}')
    with Client(destination, core) as client:
        for file, builder, spec, stem, independent in plans:
            project, _ = builder(client, destination / file)
            states = json.loads((ROOT / 'examples' / spec).read_text(encoding='utf-8'))
            vectors = client.call('run_vectors', project_id=project, vectors=states, independent=independent)
            if not vectors['passed']:
                raise RuntimeError(f'{stem} vectors failed: {vectors!r}')
            write_vectors(destination / f'{stem}-vectors.json', destination / f'{stem}-vectors.md', vectors)
            client.call('export_circuit_image', project_id=project, path=f'{stem}.png', scale=3, include_state=False)
            sheet = client.call('export_state_sheet', project_id=project, path=f'{stem}-states', states=states,
                                independent=independent, scale=3)
            if not sheet['passed']:
                raise RuntimeError(f'{stem} state sheet vectors failed')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=Path, nargs='?', default=ROOT / 'outputs')
    parser.add_argument('--core', type=Path)
    args = parser.parse_args()
    generate(args.directory, args.core)
