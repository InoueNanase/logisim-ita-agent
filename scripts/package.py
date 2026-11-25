"""Build the Windows ZIP with the native runtime, source and classroom examples."""

import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
from zipfile import ZIP_DEFLATED, ZIP_STORED, ZipFile

ROOT = Path(__file__).resolve().parents[1]
COMMIT = 'ad7c02a59e4dc3296423e16fd2d017b59ee4d7dc'
sys.path.insert(0, str(ROOT / 'client-python'))
from logisim_agent import SOURCE_CORE, jdk


def package(destination):
    upstream = ROOT / 'upstream'
    actual = subprocess.check_output(['git', '-C', str(upstream), 'rev-parse', 'HEAD'], text=True).strip()
    if actual != COMMIT:
        raise RuntimeError(f'Expected upstream {COMMIT}, found {actual}')
    if not SOURCE_CORE.is_file():
        raise FileNotFoundError('Build the pinned source JAR with scripts/build-upstream.ps1 first.')
    if not (ROOT / 'outputs' / 'half-adder.circ').is_file():
        raise FileNotFoundError('Run python examples/generate.py before packaging.')
    destination = destination.resolve()
    destination.parent.mkdir(parents=True, exist_ok=True)
    java = subprocess.run([jdk('java'), '-version'], check=True, capture_output=True, text=True)
    metadata = {'source_commit': COMMIT, 'source': f'https://github.com/Logisim-Ita/Logisim/tree/{COMMIT}',
                'runtime_sha256': hashlib.sha256(SOURCE_CORE.read_bytes()).hexdigest(),
                'runtime': 'runtime/Logisim-ITA-core.jar', 'jdk': (java.stderr or java.stdout).splitlines()[0]}
    with tempfile.TemporaryDirectory(prefix='logisim-package-', dir=destination.parent) as directory:
        directory = Path(directory)
        source = directory / 'Logisim-ITA-source.zip'
        staged = directory / 'windows.zip'
        subprocess.run(['git', '-C', str(upstream), 'archive', '--format=zip', f'--output={source}', COMMIT], check=True)
        metadata['source_archive_sha256'] = hashlib.sha256(source.read_bytes()).hexdigest()
        with ZipFile(staged, 'w', ZIP_DEFLATED) as archive:
            archive.write(SOURCE_CORE, metadata['runtime'], compress_type=ZIP_STORED)
            archive.write(source, 'source/Logisim-ITA-source.zip', compress_type=ZIP_STORED)
            archive.writestr('runtime/build.json', json.dumps(metadata, indent=2) + '\n')
            for filename in ('README.md', 'NOTICE.md', 'LICENSE', 'logisim-agent.cmd'):
                archive.write(ROOT / filename, filename)
            for folder in ('automation-java', 'client-python', 'docs', 'examples', 'schemas', 'scripts', 'outputs'):
                for path in sorted((ROOT / folder).rglob('*')):
                    if path.is_file() and '__pycache__' not in path.parts and path.suffix != '.zip':
                        archive.write(path, path.relative_to(ROOT).as_posix())
        staged.replace(destination)
    return metadata


if __name__ == '__main__':
    out = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / 'outputs' / 'logisim-agent-windows.zip'
    print(json.dumps(package(out), indent=2))
