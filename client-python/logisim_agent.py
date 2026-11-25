"""Start Logisim-ITA's native model host or use it from a Python script."""

from __future__ import annotations

import argparse
import hashlib
import itertools
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
from threading import Lock

ROOT = Path(__file__).resolve().parent.parent
SOURCE_CORE = ROOT / 'upstream' / 'Logisim-Fork' / 'target' / 'Logisim-jar-with-dependencies.jar'
PACKAGED_CORE = ROOT / 'runtime' / 'Logisim-ITA-core.jar'
ORIGINAL_CORE = ROOT / 'Logisim-ITA.exe'


def jdk(tool: str) -> str:
    home = os.environ.get('JAVA_HOME')
    if home:
        candidate = Path(home) / 'bin' / (tool + ('.exe' if os.name == 'nt' else ''))
        if candidate.is_file():
            return str(candidate)
    command = shutil.which(tool)
    if command is None:
        raise RuntimeError(f'JDK tool {tool!r} is unavailable. Set JAVA_HOME to a JDK 11 or later.')
    return command


def core_path(explicit: str | Path | None = None) -> Path:
    core = explicit or os.environ.get('LOGISIM_ITA_CORE')
    path = Path(core).resolve() if core else next((item for item in (SOURCE_CORE, PACKAGED_CORE, ORIGINAL_CORE) if item.is_file()), ORIGINAL_CORE)
    if not path.is_file():
        raise RuntimeError('Logisim-ITA core missing. Build upstream or set LOGISIM_ITA_CORE to the target EXE/JAR.')
    return path


def build(core: Path) -> Path:
    source = ROOT / 'automation-java' / 'src'
    files = sorted(source.rglob('*.java'))
    digest = hashlib.sha256(core.read_bytes())
    for path in files:
        digest.update(path.relative_to(source).as_posix().encode())
        digest.update(b'\0')
        digest.update(hashlib.sha256(path.read_bytes()).digest())
    cache = ROOT / 'work' / 'host-cache'
    cache.mkdir(parents=True, exist_ok=True)
    output = cache / (digest.hexdigest() + '.jar')
    if output.is_file():
        return output
    with tempfile.TemporaryDirectory(prefix='build-', dir=cache) as directory:
        classes = Path(directory) / 'classes'
        classes.mkdir()
        subprocess.run([jdk('javac'), '--release', '11', '-encoding', 'UTF-8', '-classpath', str(core),
                        '-d', str(classes), *map(str, files)], check=True, stdout=sys.stderr)
        package = Path(directory) / 'host.jar'
        subprocess.run([jdk('jar'), 'cf', str(package), '-C', str(classes), '.'], check=True, stdout=sys.stderr)
        os.replace(package, output)
    return output


def host_command(workspace: str | Path = '.', core: str | Path | None = None) -> list[str]:
    runtime = core_path(core)
    return [jdk('java'), '-Xmx512m', '-Djava.awt.headless=true', '-Dfile.encoding=UTF-8',
            '-Djava.util.prefs.PreferencesFactory=com.cburch.logisim.agent.MemoryPreferences',
            '-classpath', os.pathsep.join(map(str, (build(runtime), runtime))),
            'com.cburch.logisim.agent.Host', str(Path(workspace).resolve(strict=True))]


class RpcError(Exception):
    def __init__(self, error):
        super().__init__(error['message'])
        self.code = error['code']
        self.details = error['details']
        self.recoverable = error['recoverable']


class Client:
    def __init__(self, workspace: str | Path = '.', core: str | Path | None = None):
        self.process = subprocess.Popen(host_command(workspace, core), stdin=subprocess.PIPE,
                                        stdout=subprocess.PIPE, text=True, encoding='utf-8', bufsize=1)
        self._ids = itertools.count(1)
        self._lock = Lock()

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()

    def close(self):
        with self._lock:
            self.process.stdin.close()
            try:
                self.process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait()
            finally:
                self.process.stdout.close()

    def call(self, method: str, **params):
        with self._lock:
            request_id = next(self._ids)
            request = dict(jsonrpc='2.0', id=request_id, method=method if method.startswith('logisim.') else f'logisim.{method}',
                           params=params)
            self.process.stdin.write(json.dumps(request, ensure_ascii=False, allow_nan=False) + '\n')
            self.process.stdin.flush()
            line = self.process.stdout.readline()
            if not line:
                raise RuntimeError(f'Logisim-ITA host closed stdout (exit {self.process.poll()})')
            reply = json.loads(line)
            if reply.get('jsonrpc') != '2.0' or reply.get('id') != request_id:
                raise RuntimeError(f'Logisim-ITA host returned the wrong response ID: {reply!r}')
            if 'error' in reply:
                raise RpcError(reply['error'])
            return reply['result']


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--workspace', default='.', type=Path)
    parser.add_argument('--core', type=Path)
    commands = parser.add_subparsers(dest='command')
    commands.add_parser('serve', help='Read and write JSON-RPC 2.0 on standard streams')
    commands.add_parser('capabilities', help='Print engine and protocol information')
    render = commands.add_parser('render', help='Export a PNG or independently rendered PNGs')
    render.add_argument('project')
    render.add_argument('path')
    render.add_argument('--circuit')
    render.add_argument('--states', type=Path, help='UTF-8 JSON array of input states or clock vectors')
    render.add_argument('--sequential', action='store_true')
    render.add_argument('--scale', type=float, default=2)
    render.add_argument('--background', default='white')
    render.add_argument('--padding', type=int, default=24)
    render.add_argument('--dpi', type=int, default=300)
    render.add_argument('--grid', action='store_true')
    render.add_argument('--static', action='store_true')
    vectors = commands.add_parser('vectors', help='Run test vectors and save JSON and Markdown results')
    vectors.add_argument('project')
    vectors.add_argument('spec', type=Path)
    vectors.add_argument('report', type=Path)
    vectors.add_argument('--circuit')
    vectors.add_argument('--independent', action='store_true')
    args = parser.parse_args()
    try:
        if args.command in (None, 'serve'):
            return subprocess.call(host_command(args.workspace, args.core))
        with Client(args.workspace, args.core) as client:
            if args.command == 'capabilities':
                result = client.call('get_capabilities')
            else:
                project = client.call('open_project', path=args.project, mode='read_only')
                params = {'project_id': project['project_id'], 'circuit': args.circuit or project['top_circuit']}
                result = render_command(client, args, params) if args.command == 'render' else vector_command(client, args, params)
        print(json.dumps(result, ensure_ascii=False, indent=2))
        if args.command == 'vectors' and not result['passed']:
            return 2
        return 0
    except RpcError as error:
        print(json.dumps(dict(code=error.code, message=str(error), details=error.details,
                              recoverable=error.recoverable), ensure_ascii=False), file=sys.stderr)
        return 1
    except (OSError, RuntimeError, ValueError, subprocess.CalledProcessError) as error:
        print(str(error), file=sys.stderr)
        return 1


def render_command(client, args, params):
    options = dict(path=args.path, scale=args.scale, background=args.background, padding=args.padding,
                   dpi=args.dpi, show_grid=args.grid, include_state=not args.static)
    if args.states is None:
        return client.call('export_circuit_image', **params, **options)
    states = json.loads((args.workspace / args.states).read_text(encoding='utf-8'))
    return client.call('export_state_sheet', **params, **options, states=states, independent=not args.sequential)


def vector_command(client, args, params):
    data = json.loads((args.workspace / args.spec).read_text(encoding='utf-8'))
    destination = (args.workspace / args.report).resolve()
    if not destination.is_relative_to(args.workspace.resolve()) or destination.suffix.lower() != '.json':
        raise ValueError('Report destination must be a .json file in the workspace.')
    companion = destination.with_suffix('.md').resolve()
    if not companion.is_relative_to(args.workspace.resolve()):
        raise ValueError('Markdown report destination must be in the workspace.')
    result = client.call('run_vectors', **params, vectors=data, independent=args.independent)
    write_vectors(destination, companion, result)
    return result


def write_vectors(destination, companion, result):
    def cell(value):
        return str(value).replace('|', r'\|').replace('\r', '').replace('\n', ' ')

    lines = [f"# {result['circuit']} · Test vectors", '',
             '| Vector | Tick | Result | Expected and observed |', '| --- | ---: | --- | --- |']
    for row in result['vectors']:
        comparisons = ', '.join(f"{value['label'] or key} {value['expected']['bits']} → {value['actual']['bits']}"
                                for key, value in row['comparisons'].items())
        lines.append(f"| {cell(row['label'])} | {row['tick']} | {'Pass' if row['passed'] else 'Fail'} | {cell(comparisons)} |")
    destination.parent.mkdir(parents=True, exist_ok=True)
    for path, contents in ((destination, json.dumps(result, ensure_ascii=False, indent=2) + '\n'),
                           (companion, '\n'.join(lines) + '\n')):
        with tempfile.NamedTemporaryFile('w', encoding='utf-8', dir=path.parent, delete=False) as stage:
            staged = Path(stage.name)
            stage.write(contents)
        try:
            os.replace(staged, path)
        finally:
            staged.unlink(missing_ok=True)


if __name__ == '__main__':
    raise SystemExit(main())
