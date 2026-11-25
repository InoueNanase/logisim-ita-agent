"""Run milestone checks and retain their commands, raw logs and assertions."""

import argparse
import hashlib
import json
import os
from datetime import datetime, timezone
from pathlib import Path
import re
import shutil
import struct
import subprocess
import sys
from xml.etree import ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
REPORTS = ROOT / 'work' / 'reports'
CORE = ROOT / 'Logisim-ITA.exe'
SOURCES = ROOT / 'automation-java' / 'src'
CLASSES = ROOT / 'work' / 'classes'
COMMIT = 'ad7c02a59e4dc3296423e16fd2d017b59ee4d7dc'
sys.path[:0] = [str(ROOT / 'client-python'), str(ROOT / 'examples')]
from logisim_agent import Client, RpcError, core_path
from circuits import half_adder, multiplexer, point, register
REGISTER_VECTORS = json.loads((ROOT / 'examples' / 'register-vectors.json').read_text(encoding='utf-8'))


def jdk(tool):
    if os.environ.get('JAVA_HOME') and (p := Path(os.environ['JAVA_HOME']) / 'bin' / tool).with_suffix('.exe' if os.name == 'nt' else '').is_file():
        return str(p.with_suffix('.exe' if os.name == 'nt' else ''))
    executable = shutil.which(tool)
    if executable is None:
        raise RuntimeError(f'JDK tool unavailable: {tool}')
    return executable


def jvm(core=CORE):
    return [jdk('java'), '-Djava.awt.headless=true', '-Dfile.encoding=UTF-8',
            '-Djava.util.prefs.PreferencesFactory=com.cburch.logisim.agent.MemoryPreferences',
            '-cp', os.pathsep.join(map(str, (CLASSES, core)))]


def compile_args(core):
    CLASSES.mkdir(parents=True, exist_ok=True)
    return [jdk('javac'), '--release', '11', '-encoding', 'UTF-8', '-cp', str(core),
            '-d', str(CLASSES), *map(str, sorted(SOURCES.rglob('*.java'))),
            *map(str, sorted((ROOT / 'tests' / 'java').glob('*.java')))]


class Report:
    def __init__(self, stage, core):
        stamp = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
        self.directory = REPORTS / f'{stage}-{stamp}'
        self.directory.mkdir(parents=True)
        self.data = {'stage': stage, 'source_commit': COMMIT, 'os': sys.platform,
                     'python': sys.version.split()[0], 'core': core.name,
                     'core_sha256': hashlib.sha256(core.read_bytes()).hexdigest(), 'checks': []}

    def record(self, name, passed, expected, actual, **evidence):
        self.data['checks'].append(dict(name=name, passed=passed, expected=expected, actual=actual, **evidence))
        if not passed:
            raise AssertionError(f'{name}: expected {expected}, got {actual}')

    def run(self, name, args, timeout=60, expected_exit=0, cwd=ROOT, input_data=None):
        log = self.directory / f'{name}.log'
        try:
            proc = subprocess.run(args, cwd=cwd, capture_output=True, text=True, input=input_data,
                                  encoding='utf-8', errors='replace', timeout=timeout)
        except subprocess.TimeoutExpired as error:
            log.write_bytes((error.stdout or b'') + (error.stderr or b''))
            self.record(name, False, f'Exit code {expected_exit}', 'Process timed out', command=args, log=log.name)
        log.write_text(proc.stdout + proc.stderr, encoding='utf-8')
        self.record(name, proc.returncode == expected_exit, f'Exit code {expected_exit}', proc.returncode,
                    command=args, cwd=str(cwd), log=log.name)
        return proc.stdout

    def finish(self):
        path = self.directory / 'report.json'
        path.write_text(json.dumps(self.data, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
        lines = [f"# {self.data['stage']} acceptance", '', f'Upstream `{COMMIT}`', '']
        for check in self.data['checks']:
            status = 'PASS' if check['passed'] else 'FAIL'
            actual = str(check['actual']).replace('\n', ' ')
            if len(actual) > 180:
                actual = actual[:177] + '...'
            lines.append(f"- {status} · {check['name']} · expected `{check['expected']}` · actual `{actual}`")
        if 'failure' in self.data:
            lines.extend(['', '## Failure', '', self.data['failure'][:750]])
        (self.directory / 'report.md').write_text('\n'.join(lines) + '\n', encoding='utf-8')
        print(path.relative_to(ROOT))


def original(report, path, name, mode='stats'):
    # This invokes the shipped Main and native TTY implementation, without loading Agent code
    # apart from the isolated java.util.prefs provider.
    return report.run(name, [*jvm(), 'com.cburch.logisim.Main', '-locale', 'en', '-tty', mode, str(path)], 25)


def probe(report, core):
    target = ROOT / 'work' / 'm1'
    result = report.run('native-probe', [*jvm(core), 'com.cburch.logisim.agent.HalfAdderProbe', str(target)])
    table = ['0,0,0,0', '0,1,1,0', '1,0,1,0', '1,1,0,1']
    for phase in ('created', 'reopened'):
        rows = [line.split(',', 1)[1] for line in result.splitlines() if line.startswith(phase + ',')]
        report.record(f'{phase}-table', rows == table, table, rows)
    path = target / 'half-adder.circ'
    stats = original(report, path, 'original-reopen')
    rows = {label.strip(): int(count) for count, label in re.findall(r'^\s*(\d+)\t\s*\d+\t([^\t\n]+)\t', stats, re.M)}
    report.record('original-counts', rows == {'Pin': 4, 'AND Gate': 1, 'XOR Gate': 1},
                  {'Pin': 4, 'AND Gate': 1, 'XOR Gate': 1}, rows)
    for a, b in ((0, 0), (0, 1), (1, 0), (1, 1)):
        tree = ET.parse(path)
        circuit = tree.find('circuit')
        library = next(lib.get('name') for lib in tree.findall('lib') if lib.get('desc') == '#Wiring')
        for pin in circuit.findall('comp'):
            label = pin.find("a[@name='label']")
            if pin.get('name') == 'Pin' and label is not None and label.get('val') in ('A', 'B'):
                value = a if label.get('val') == 'A' else b
                pin.set('name', 'Constant')
                del pin[:]
                ET.SubElement(pin, 'a', name='value', val=f'0x{value}')
        stop = ET.SubElement(circuit, 'comp', loc='(130,320)', lib=library, name='Pin')
        ET.SubElement(stop, 'a', name='output', val='true')
        ET.SubElement(stop, 'a', name='facing', val='west')
        ET.SubElement(stop, 'a', name='label', val='halt')
        one = ET.SubElement(circuit, 'comp', loc='(80,320)', lib=library, name='Constant')
        ET.SubElement(one, 'a', name='value', val='0x1')
        ET.SubElement(circuit, 'wire', {'from': '(80,320)', 'to': '(130,320)'})
        variant = target / f'vector-{a}{b}.circ'
        tree.write(variant, encoding='utf-8', xml_declaration=True)
        observed = original(report, variant, f'original-vector-{a}{b}', 'table').splitlines()[-1].split()
        expected = [str(a ^ b), str(a & b)]
        report.record(f'original-vector-{a}{b}-values', observed == expected, expected, observed)
    png = (target / 'half-adder.png').read_bytes()
    size = struct.unpack('>II', png[16:24])
    report.record('png-header', png.startswith(b'\x89PNG\r\n\x1a\n') and all(v > 100 for v in size),
                  'PNG with nontrivial content dimensions', size)


def baseline(report, core):
    from baseline import fixture
    samples = report.directory / 'samples'
    samples.mkdir()
    for kind in ('wire', 'gate', 'memory'):
        fixture(samples / f'{kind}.circ', kind)
    archive = ROOT / 'CS220 Practical 2 partA_circuits.zip'
    if archive.exists():
        from zipfile import ZipFile
        with ZipFile(archive) as zipped:
            for name in zipped.namelist():
                if name.startswith('partA_circuits/') and name.endswith('.circ'):
                    (samples / Path(name).name).write_bytes(zipped.read(name))
    if (ROOT / 'practical4.circ').exists():
        shutil.copy2(ROOT / 'practical4.circ', samples)
    paths = sorted(samples.glob('*.circ'))
    upstream = ROOT / 'upstream'
    source = upstream / 'Compiled' / 'Logisim-ITA.exe'
    if source.is_file():
        commit = report.run('upstream-commit', ['git', '-C', str(upstream), 'rev-parse', 'HEAD']).strip()
        report.record('pinned-commit', commit == COMMIT, COMMIT, commit)
        report.record('installed-exe-matches-upstream', source.read_bytes() == CORE.read_bytes(),
                      'byte-identical original EXE', hashlib.sha256(CORE.read_bytes()).hexdigest())
    maven = ROOT / 'work' / 'reports' / 'm0-maven.log'
    if maven.is_file():
        report.record('maven-clean-package', 'BUILD SUCCESS' in maven.read_text(encoding='utf-8-sig'),
                      'BUILD SUCCESS', 'BUILD SUCCESS' if 'BUILD SUCCESS' in maven.read_text(encoding='utf-8-sig') else 'Failed',
                      log='../m0-maven.log', command=['mvn', '-B', 'clean', 'package'])
    else:
        report.data['unverified'] = ['Upstream Maven build log not present']
    # Startup prints the version and returns null; the original Main then calls System.exit(-1).
    version_exit = 2**32 - 1 if os.name == 'nt' else 255
    detected = report.run('installed-version', [*jvm(), 'com.cburch.logisim.Main', '-locale', 'en', '-version'],
                          expected_exit=version_exit).strip()
    report.record('installed-version-string', detected == '2.16.2.2.exe', '2.16.2.2.exe', detected)
    for path in paths:
        statistics = original(report, path, f'original-{path.stem}')
        report.record(f'original-{path.stem}-counts', 'TOTAL' in statistics, 'Original component statistics', statistics.strip())
    copies = report.directory / 'native-copies'
    report.run('native-roundtrip', [*jvm(core), 'com.cburch.logisim.agent.HalfAdderProbe', str(copies), *map(str, paths)])
    for path in paths:
        original(report, copies / path.name, f'original-saved-{path.stem}')


def assert_error(report, client, name, code, method, **params):
    try:
        client.call(method, **params)
    except RpcError as error:
        report.record(name, error.code == code and isinstance(error.details, dict) and error.recoverable,
                      code, error.code, details=error.details)
    else:
        report.record(name, False, code, 'No error')


def editing(report, core):
    target = report.directory
    path = target / 'half-adder-api.circ'
    with Client(ROOT, core) as client:
        project, pins = half_adder(client, path)
        params = {'project_id': project}
        before = client.call('get_circuit', **params)
        report.record('graph-counts', (len(before['components']), len(before['nets'])) == (6, 4),
                      [6, 4], [len(before['components']), len(before['nets'])])
        report.record('crossing-is-not-joined', not any((p['x'], p['y']) == (190, 210) for p in before['junctions']),
                      'No junction at nonconnecting crossing', before['junctions'])
        for factory in ('Pin', 'Constant', 'Splitter', 'Tunnel', 'Multiplexer', 'Decoder', 'XOR Gate', 'AND Gate', '7400'):
            result = client.call('describe_component', factory=factory)
            report.record(f'factory-{factory}', result['factory'] == factory and len(result['pins']) > 0,
                          'Native named factory with pins', result['factory'])
        assert_error(report, client, 'unknown-factory', -32001, 'add_component', **params, factory='fake', x=90, y=60)
        assert_error(report, client, 'off-grid', -32602, 'add_component', **params, factory='Pin', x=91, y=60)
        assert_error(report, client, 'invalid-width', -32602, 'add_component', **params, factory='Pin', x=90, y=60, attrs={'width': 33})
        assert_error(report, client, 'duplicate-placement', -32002, 'add_component', **params, factory='Pin', x=80, y=120)
        assert_error(report, client, 'diagonal-wire', -32602, 'connect', **params, points=[point(80, 120), point(90, 130)], width=1)
        assert_error(report, client, 'wrong-width', -32002, 'connect', **params, points=[point(80, 120), point(80, 100)],
                     width=2, allow_dangling=True)
        assert_error(report, client, 'stale-revision', -32002, 'remove_component', **params, id=pins['A'], if_revision=0)
        unchanged = client.call('get_circuit', **params)
        saved_before = path.read_bytes()
        client.call('save', **params)
        report.record('failed-edits-atomic', before == unchanged and saved_before == path.read_bytes(),
                      'Same revision, graph and file', unchanged['revision'])
        moved = client.call('move_component', **params, id=pins['S'], x=430, y=160)
        report.record('preserving-move', moved['id'] == pins['S'] and moved['position'] == point(430, 160),
                      point(430, 160), moved['position'])
        client.call('undo', **params)
        undone = client.call('get_circuit', **params)
        report.record('undo', graph_snapshot(before) == graph_snapshot(undone), 'Original graph', undone['revision'])
        client.call('redo', **params)
        client.call('set_attribute', **params, id=pins['S'], attribute='label', value='Sum')
        edited = client.call('get_circuit', **params)
        output = next(component for component in edited['components'] if component['id'] == pins['S'])
        report.record('attribute-update', output['label'] == 'Sum', 'Sum', output['label'])
        checked = client.call('validate', **params)
        report.record('native-reopen', checked['valid'] and checked['native_reopen'] == 'passed', 'passed', checked)
        client.call('save', **params)
        client.call('close_project', **params)
        project = client.call('open_project', path=str(path), mode='read_only')['project_id']
        reopened = client.call('get_circuit', project_id=project)
        report.record('saved-graph', graph_snapshot(reopened) == graph_snapshot(edited), 'Same graph and IDs', len(reopened['wires']))
        assert_error(report, client, 'read-only', -32005, 'save', project_id=project)
        client.call('close_project', project_id=project)
        for filename, contents, code in [('broken.circ', '<project', -32003), ('missing-lib.circ',
                '<project source="2.16.2.2.exe"><lib desc="#Missing" name="99"/><circuit name="main"/></project>', -32003)]:
            failing = target / filename
            failing.write_text(contents, encoding='utf-8')
            assert_error(report, client, filename, code, 'open_project', path=str(failing))
        preserved = target / 'preserved.circ'
        tree = ET.parse(path)
        root = tree.getroot()
        root.set('source', '2.7.1')
        root.set('future-project', '留存')
        root.find('lib').set('future-library', 'α')
        node = root.find('circuit/comp')
        node.set('future-component', 'beta')
        ET.SubElement(node, 'a', {'name': 'future-attribute', 'val': 'γ'})
        root.find('circuit/wire').set('future-wire', 'δ')
        tree.write(preserved, encoding='utf-8', xml_declaration=True)
        session = client.call('open_project', path=str(preserved))
        component_id = node.get('agent-id')
        client.call('update_component', project_id=session['project_id'], id=component_id, label='Input A')
        client.call('save', project_id=session['project_id'])
        root = ET.parse(preserved).getroot()
        actual = [root.get('source'), root.get('future-project'), root.find('lib').get('future-library'),
                  root.find('circuit/comp').get('future-component'), root.find("circuit/comp/a[@name='future-attribute']").get('val'),
                  root.find('circuit/wire').get('future-wire')]
        expected = ['2.7.1', '留存', 'α', 'beta', 'γ', 'δ']
        report.record('unknown-xml-preserved', actual == expected, expected, actual)
        mux = target / 'mux2x4.circ'
        mux_project, _ = multiplexer(client, mux)
        vectors = json.loads((ROOT / 'examples' / 'mux-vectors.json').read_text(encoding='utf-8'))
        readings = client.call('run_vectors', project_id=mux_project, vectors=vectors, independent=True)
        report.record('multiplexer-tunnel-splitter', readings['passed'], 'Both selected 4-bit buses reach their split outputs', readings)
        faults(report, client)
    original(report, path, 'original-api-reopen')
    original(report, preserved, 'original-preserved-reopen')
    original(report, mux, 'original-mux-reopen')


def graph_snapshot(circuit):
    return {key: value for key, value in circuit.items() if key != 'revision'}


def faults(report, client):
    path = report.directory / 'diagnostics.circ'
    summary = client.call('create_project', path=str(path))
    params = {'project_id': summary['project_id']}
    a = client.call('add_component', **params, factory='Pin', label='A', x=80, y=100, attrs={'tristate': False})
    client.call('add_component', **params, factory='Pin', label='B', x=80, y=150, attrs={'tristate': False})
    client.call('add_component', **params, factory='Pin', label='Y', facing='west', x=260, y=100, attrs={'output': True})
    warning = client.call('validate', **params)
    report.record('floating-diagnostic', any(issue['kind'] == 'floating_input' for issue in warning['diagnostics']),
                  'Floating input warning', warning['diagnostics'])
    client.call('connect', **params, width=1, points=[point(80, 100), point(260, 100)])
    assert_error(report, client, 'change-connected-width', -32002, 'set_attribute', **params, id=a['id'], attribute='width', value=2)
    client.call('connect', **params, width=1, points=[point(80, 150), point(170, 150), point(170, 100)])
    checked = client.call('validate', **params)
    report.record('multidriver-diagnostic', any(issue['kind'] == 'multiple_drivers' for issue in checked['diagnostics']),
                  'Multiple drivers warning', checked['diagnostics'])
    conflict = client.call('run_vectors', **params, vectors=[{'inputs': {'A': 0, 'B': 1}, 'expect': {'Y': 'E'}}])
    report.record('multidriver-value', conflict['passed'] and len(conflict['vectors'][0]['conflicts']) == 1,
                  'Native error value on driven pin', conflict['vectors'][0]['conflicts'])
    net = client.call('get_circuit', **params)['nets'][0]['id']
    client.call('disconnect', **params, wire_or_net_id=net)
    report.record('disconnect-net', not client.call('get_circuit', **params)['wires'], 'All net segments removed',
                  client.call('get_circuit', **params)['wires'])

    client.call('save', **params)
    modified = path.read_bytes() + b'\n<!-- external change -->\n'
    path.write_bytes(modified)
    client.call('set_attribute', **params, id=a['id'], attribute='label', value='Local edit')
    assert_error(report, client, 'external-write-conflict', -32002, 'save', **params)
    report.record('external-data-preserved', path.read_bytes() == modified, 'Exact external contents', path.read_bytes() == modified)


def timing(report, core):
    path = report.directory / 'register4.circ'
    with Client(ROOT, core) as client:
        project, pins = register(client, path)
        params = {'project_id': project}
        for factory in ('Clock', 'Register', 'Counter', 'D Flip-Flop', 'T Flip-Flop', 'J-K Flip-Flop',
                        'S-R Flip-Flop', 'RAM', 'ROM'):
            info = client.call('describe_component', factory=factory)
            report.record(f'timing-factory-{factory}', info['factory'] == factory and bool(info['pins']),
                          'Native factory with ports', info['factory'])
        invalid = dict(params, pin_id=pins['D'], value=16)
        assert_error(report, client, 'overwide-input', -32602, 'set_input', **invalid)
        assert_error(report, client, 'numeric-overflow', -32602, 'set_input', **params, pin_id=pins['D'], value=1e100)
        client.call('set_input', **params, pin_id=pins['D'], value='0101')
        pending = client.call('simulate', **params, settle=False)
        report.record('pending-unsettled', not pending['settled'], False, pending['settled'])
        stepped = client.call('clock_step', **params, clock_id=pins['CLK'], edges=1)
        report.record('live-clock-step', stepped['edges'] == [{'tick': 3, 'edge': 'rising'}]
                      and stepped['outputs'][pins['Q']]['value']['integer'] == 5, 'Rising at tick 3, Q=5', stepped)
        result = client.call('run_vectors', **params, vectors=REGISTER_VECTORS)
        (report.directory / 'register-vectors.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
        report.record('register-vectors', result['passed'] and [row['tick'] for row in result['vectors']] == [0, 3, 5, 8],
                      'Q=0,5,5,10 and native ticks 0,3,5,8', result)
        failed = client.call('run_vectors', **params, vectors=[{'expect': {'Q': 1}}])
        report.record('wrong-expectation-reported', not failed['passed'], False, failed['passed'])
        client.call('close_project', **params)
        reopened = client.call('open_project', path=str(path), mode='read_only')
        repeated = client.call('run_vectors', project_id=reopened['project_id'], vectors=REGISTER_VECTORS)
        report.record('register-reopened-vectors', repeated['passed'], True, repeated['passed'])
        memory_examples(report, client)

        archive = ROOT / 'CS220 Practical 2 partA_circuits.zip'
        if archive.exists():
            from zipfile import ZipFile
            legacy = report.directory / 'legacy-nand.circ'
            with ZipFile(archive) as zipped:
                legacy.write_bytes(zipped.read('partA_circuits/partA_c_nandhalves.circ'))
            old = client.call('open_project', path=str(legacy), mode='read_only')
            circuit = client.call('get_circuit', project_id=old['project_id'])
            pin_ids = {(pin['position']['x'], pin['position']['y']): pin['id'] for pin in circuit['components'] if pin['factory'] == 'Pin'}
            left_x, left_y = pin_ids[120, 100], pin_ids[120, 140]
            right_x, right_y = pin_ids[500, 100], pin_ids[500, 140]
            left_out, right_out = pin_ids[360, 60], pin_ids[730, 180]
            vectors = [{'inputs': {left_x: a, right_x: a, left_y: b, right_y: b},
                        'expect': {left_out: 0 if a and b else 'x', right_out: 'x' if a and b else 1}}
                       for a, b in ((0, 0), (0, 1), (1, 0), (1, 1))]
            measured = client.call('run_vectors', project_id=old['project_id'], vectors=vectors, independent=True)
            report.record('legacy-transistor-vectors', old['source_version'] == '2.7.1' and measured['passed'],
                          'PMOS/NMOS conduction and unknown high-impedance states', measured)
            original(report, legacy, 'original-legacy-reopen')
        else:
            report.data['unverified'] = ['Classroom legacy fixture unavailable']
    original(report, path, 'original-register-reopen')


def memory_examples(report, client):
    def setup(name, **attributes):
        path = report.directory / f'{name.lower()}.circ'
        project = client.call('create_project', path=str(path), top_circuit=f'{name}Demo')['project_id']
        component = client.call('add_component', project_id=project, factory=f'Memory:{name}', x=450, y=180,
                                attrs=dict(addrWidth=2, dataWidth=4, **attributes))
        return path, project, component['pins']

    def add(project, factory, x, y, label=None, facing='east', **attrs):
        params = dict(project_id=project, factory=factory, x=x, y=y, facing=facing, attrs=attrs)
        if label is not None:
            params['label'] = label
        return client.call('add_component', **params)

    def join(project, p, component):
        client.call('connect', project_id=project, width=p['width'], points=[component['position'], point(p['x'], p['y'])])

    rom_path, rom, ports = setup('ROM', contents='addr/data: 2 4\n1 5 9 f\n')
    join(rom, ports[0], add(rom, 'Pin', 600, 180, 'Q', 'west', width=4, output=True))
    join(rom, ports[1], add(rom, 'Pin', 80, 180, 'ADDR', width=2, tristate=False))
    join(rom, ports[2], add(rom, 'Constant', ports[2]['x'], 310, facing='north', value='0x0'))
    client.call('save', project_id=rom)
    rows = [{'inputs': {'ADDR': address}, 'expect': {'Q': value}} for address, value in enumerate((1, 5, 9, 15))]
    observed = client.call('run_vectors', project_id=rom, vectors=rows, independent=True)
    report.record('rom-image-vectors', observed['passed'], 'ROM maps 0..3 to 1, 5, 9, 15', observed)

    ram_path, ram, ports = setup('RAM', bus='separate')
    join(ram, ports[0], add(ram, 'Pin', 600, 180, 'Q', 'west', width=4, output=True))
    join(ram, ports[1], add(ram, 'Pin', 80, 180, 'ADDR', width=2, tristate=False))
    join(ram, ports[7], add(ram, 'Pin', 80, 200, 'DIN', width=4, tristate=False))
    join(ram, ports[2], add(ram, 'Constant', ports[2]['x'], 310, facing='north', value='0x0'))
    join(ram, ports[3], add(ram, 'Constant', ports[3]['x'], 310, facing='north', value='0x1'))
    join(ram, ports[4], add(ram, 'Pin', ports[4]['x'], 310, 'CLR', 'north', tristate=False))
    join(ram, ports[5], add(ram, 'Clock', ports[5]['x'], 310, facing='north'))
    join(ram, ports[6], add(ram, 'Pin', ports[6]['x'], 310, 'WE', 'north', tristate=False))
    client.call('save', project_id=ram)
    rows = [
        {'inputs': {'CLR': 1, 'WE': 0, 'ADDR': 1, 'DIN': 6}, 'expect': {'Q': 0}},
        {'inputs': {'CLR': 0, 'WE': 1}, 'edges': 1, 'expect': {'Q': 6}},
        {'inputs': {'ADDR': 2, 'WE': 0}, 'edges': 1, 'expect': {'Q': 0}},
        {'inputs': {'ADDR': 1}, 'edges': 1, 'expect': {'Q': 6}},
    ]
    observed = client.call('run_vectors', project_id=ram, vectors=rows)
    report.record('ram-read-write-vectors', observed['passed'], 'Clear, write, read untouched and read stored address', observed)
    original(report, rom_path, 'original-rom-reopen')
    original(report, ram_path, 'original-ram-reopen')


def render(report, core):
    from PIL import Image, ImageChops, ImageFilter
    from zipfile import ZipFile

    target = report.directory
    path = target / 'half-adder.circ'
    vectors = [{'label': f'A={a} · B={b}', 'inputs': {'A': a, 'B': b}, 'expect': {'S': a ^ b, 'C': a & b}}
               for a, b in ((0, 0), (0, 1), (1, 0), (1, 1))]
    with Client(ROOT, core) as client:
        project, pins = half_adder(client, path)
        params = {'project_id': project}
        folder = target / 'states'
        result = client.call('export_state_sheet', **params, path=str(folder), states=vectors, scale=3)
        manifest = json.loads((folder / 'manifest.json').read_text(encoding='utf-8'))
        report.record('state-manifest', len(manifest['images']) == 4 and result['passed'], '4 passed states', len(manifest['images']))
        hashes = set()
        for vector, entry in zip(vectors, manifest['images'], strict=True):
            png = folder / entry['image']
            sidecar = json.loads((folder / entry['sidecar']).read_text(encoding='utf-8'))
            received = {item['label']: item['value']['integer'] for item in sidecar['inputs'].values()}
            hashes.add(hashlib.sha256(png.read_bytes()).hexdigest())
            with Image.open(png) as image:
                image.load()
                bbox = ImageChops.difference(image.convert('RGB'), Image.new('RGB', image.size, 'white')).getbbox()
                width, height = image.size
                margins = [bbox[0], bbox[1], width - bbox[2], height - bbox[3]]
                labels_match = received == vector['inputs'] and entry == sidecar and vector['label'] == sidecar['label']
                report.record(png.stem, labels_match and all(72 <= m <= 74 for m in margins) and abs(image.info['dpi'][0] - 300) < 1,
                              'Matching label, 72 px white padding and 300 DPI', [received, margins, image.info['dpi']])
        report.record('distinct-state-pixels', len(hashes) == 4, 4, len(hashes))
        gallery = (folder / 'index.html').read_text(encoding='utf-8')
        report.record('state-index', all(row['label'] in gallery for row in vectors), 'All four labels', len(vectors))
        archive = target / 'states.zip'
        client.call('export_state_sheet', **params, path=str(archive), states=vectors, scale=1, layout='list')
        with ZipFile(archive) as zipped:
            report.record('zip-contents', zipped.testzip() is None and len(zipped.namelist()) == 10,
                          '4 PNG + 4 sidecars + index + manifest', zipped.namelist())
        grid_exports(report, client, core)
        for name, options in [('static', {'include_state': False}), ('transparent', {'background': 'transparent', 'show_grid': True}),
                              ('high-scale', {'scale': 8, 'background': '#eeeeff'})]:
            image_path = target / f'{name}.png'
            details = client.call('export_circuit_image', **params, path=str(image_path), **options)
            with Image.open(image_path) as image:
                expected_pixel = (0, 0, 0, 0) if name == 'transparent' else (238, 238, 255, 255) if name == 'high-scale' else (255, 255, 255, 255)
                if name == 'transparent':
                    valid = image.getchannel('A').getextrema() == (0, 255)
                else:
                    valid = image.getpixel((0, 0)) == expected_pixel
                report.record(name, valid and image.size == (details['width'], details['height'])
                              and (name != 'high-scale' or image.width > 3000), 'Background and matching dimensions', image.size)
        assert_error(report, client, 'excessive-scale', -32006, 'export_circuit_image', **params,
                     path=str(target / 'over.png'), scale=17)
        raw = '{"jsonrpc":"2.0","id":"huge-scale","method":"logisim.export_circuit_image","params":{' \
              f'"project_id":"{project}","path":"work/over.png","scale":1e400}}}}\n'
        client.process.stdin.write(raw)
        client.process.stdin.flush()
        reply = json.loads(client.process.stdout.readline())
        report.record('scale-overflow', reply['id'] == 'huge-scale' and reply['error']['code'] == -32006
                      and reply['error']['recoverable'], 'Recoverable image limit error', reply)
        assert_error(report, client, 'state-sheet-retry-conflict', -32002, 'export_state_sheet', **params,
                     path=str(folder), states=vectors)
        if (ROOT / 'practical4.circ').exists():
            classroom = client.call('open_project', path='practical4.circ', mode='read_only')
            large = client.call('export_circuit_image', project_id=classroom['project_id'], path=str(target / 'classroom.png'),
                                scale=2, include_state=False)
            report.record('classroom-text-export', large['width'] > 1000, 'Classroom layout exports at report scale', large['width'])
            assert_error(report, client, 'excessive-dimensions', -32006, 'export_circuit_image', project_id=classroom['project_id'],
                         path=str(target / 'over.png'), scale=16)
    native = target / 'original-export.png'
    report.run('original-export-action', [*jvm(), 'com.cburch.logisim.agent.OriginalImageProbe', str(path), 'HalfAdder', str(native), '2'])
    masks = []
    cropped_edges = []
    for sample in (native, target / 'static.png'):
        with Image.open(sample) as image:
            rgb = image.convert('RGB')
            box = ImageChops.difference(rgb, Image.new('RGB', rgb.size, 'white')).getbbox()
            cropped_edges.append((box[0], rgb.width - box[2]))
            masks.append(rgb.crop(box).convert('L').point(lambda level: 255 if level < 180 else 0))
    original_ink, agent_ink = masks
    # The upstream action clips the outer pin labels horizontally for this fixture.
    # Verify the clipping and compare the interior geometry after translating to a common origin.
    report.record('label-clipping-corrected', cropped_edges == [(0, 0), (48, 48)]
                  and agent_ink.width > original_ink.width, 'Original touches both edges; Agent retains both labels and 48 px pads',
                  {'margins': cropped_edges, 'native_width': original_ink.width, 'agent_width': agent_ink.width})
    interior = original_ink.crop((25, 4, original_ink.width - 25, original_ink.height - 4))
    interior_count = interior.histogram()[255]
    expanded = interior.filter(ImageFilter.MaxFilter(3))
    coverage = 0
    for x in range(-3, agent_ink.width - original_ink.width + 4):
        for y in range(-3, 4):
            comparison = agent_ink.crop((25 + x, 4 + y, original_ink.width - 25 + x, original_ink.height - 4 + y))
            near_original = ImageChops.multiply(comparison, expanded).histogram()[255] / comparison.histogram()[255]
            near_agent = ImageChops.multiply(interior, comparison.filter(ImageFilter.MaxFilter(3))).histogram()[255] / interior_count
            coverage = max(coverage, min(near_original, near_agent))
    report.record('native-export-interior-pixels', coverage >= 0.995,
                  '99.5% bidirectional ink coverage within 1 px', coverage)


def grid_exports(report, client, core):
    from math import ceil, floor
    from PIL import Image, ImageChops, ImageDraw

    target = report.directory
    path = target / 'grid.circ'
    session = client.call('create_project', path=str(path))
    project = session['project_id']
    params = {'project_id': project}
    source = client.call('add_component', **params, factory='Pin', x=-500, y=-30, attrs={'tristate': False})
    sink = client.call('add_component', **params, factory='Pin', x=490, y=40, facing='west', attrs={'output': True})
    client.call('connect', **params, points=[point(-500, -30), point(0, -30), point(0, 40), point(490, 40)])
    client.call('save', **params)
    output = report.run('grid-native-bounds', [*jvm(core), 'com.cburch.logisim.agent.OriginalImageProbe', str(path), session['top_circuit']])
    bounds_x, bounds_y = map(int, re.search(r'bounds-origin=(-?\d+),(-?\d+)', output).groups())

    for scale, padding in ((0.5, 17), (1, 0), (1.3125, 17), (1.3125, 49), (5.25, 49)):
        name = f'grid-{scale}x-{padding}'
        plain_path = target / f'{name}-plain.png'
        grid_path = target / f'{name}.png'
        options = dict(params, scale=scale, padding=padding, background='transparent')
        plain = client.call('export_circuit_image', **options, path=str(plain_path))
        grid = client.call('export_circuit_image', **options, path=str(grid_path), show_grid=True)
        with Image.open(plain_path) as circuit, Image.open(grid_path) as drawn:
            alpha = circuit.getchannel('A')
            uncovered = alpha.point(lambda value: 255 if value == 0 else 0)
            opaque = alpha.point(lambda value: 255 if value == 255 else 0)
            left_x, right_x = circuit.width // 4, 3 * circuit.width // 4
            upper = alpha.crop((left_x, 0, left_x + 1, circuit.height)).getbbox()
            lower = alpha.crop((right_x, 0, right_x + 1, circuit.height)).getbbox()
            mid_y = (upper[3] + lower[1]) // 2
            vertical = alpha.crop((0, mid_y, circuit.width, mid_y + 1)).getbbox()

            # Straight wire centers identify the circuit origin independently of the grid.
            # The measured centers are pixel samples. Recover the known fractional transform
            # phase before computing the exact rounded dot coordinates.
            guard = max(32, padding)
            phase_x = ((guard - bounds_x) * scale) % 1
            phase_y = ((guard - bounds_y) * scale) % 1
            measured_x = (vertical[0] + vertical[2]) / 2
            measured_y = (upper[1] + upper[3]) / 2 + 30 * scale
            step = 10 * scale
            size = int(scale + 0.001) if scale >= 2 else 1

            def difference(left, right, mask):
                return any(ImageChops.multiply(channel, mask).getbbox()
                           for channel in ImageChops.difference(left, right).split())

            def matches_grid(origin_x, origin_y):
                expected = Image.new('RGBA', circuit.size)
                painter = ImageDraw.Draw(expected)
                for row in range(floor(-origin_y / step), ceil((circuit.height - origin_y) / step) + 1):
                    y = floor(origin_y + row * step + 0.5) - size // 2
                    for col in range(floor(-origin_x / step), ceil((circuit.width - origin_x) / step) + 1):
                        x = floor(origin_x + col * step + 0.5) - size // 2
                        gray = 204 if scale <= 0.5 and (row % 5 or col % 5) else 136
                        painter.rectangle((x, y, x + size - 1, y + size - 1), fill=(gray, gray, gray, 255))
                pin_x = floor(origin_x - 500 * scale + 0.5)
                pin_y = floor(origin_y - 30 * scale + 0.5)
                return not difference(expected, drawn, uncovered) and circuit.getpixel((pin_x, pin_y))[3] > 0

            # Java2D normalizes antialiased strokes, so allow a one-pixel origin
            # uncertainty from measuring the wire. All dots must match that single origin.
            columns = range(ceil(measured_x - 1 - phase_x), floor(measured_x + 1 - phase_x) + 1)
            rows = range(ceil(measured_y - 1 - phase_y), floor(measured_y + 1 - phase_y) + 1)
            aligned = any(matches_grid(x + phase_x, y + phase_y) for x in columns for y in rows)
            report.record(name, not plain['show_grid'] and grid['show_grid'] and circuit.size == drawn.size
                          and aligned and not difference(circuit, drawn, opaque),
                          'Native grid colors and dot sizes at every unobscured coordinate; crop and circuit ink retained',
                          {'size': drawn.size, 'wire_origin': [measured_x, measured_y], 'dot_size': size, 'aligned': aligned})

    states = [{'inputs': {source['id']: value}, 'expect': {sink['id']: value}} for value in (0, 1)]
    options = dict(params, scale=1.3125, padding=49, show_grid=True, background='#eeddff')
    folder = target / 'grid-states'
    sheet = client.call('export_state_sheet', **options, path=str(folder), states=states)
    for value, entry in enumerate(sheet['images']):
        client.call('set_input', **params, pin_id=source['id'], value=value)
        single = target / f'grid-single-{value}.png'
        client.call('export_circuit_image', **options, path=str(single))
        report.record(f'grid-single-matches-sheet-{value}', sheet['passed'] and entry['show_grid']
                      and single.read_bytes() == (folder / entry['image']).read_bytes(),
                      'Identical single and sheet PNG bytes for the same state', entry['image'])


def compatibility(report, core):
    from zipfile import ZipFile
    target = report.directory
    schema = json.loads((ROOT / 'schemas' / 'rpc.schema.json').read_text(encoding='utf-8'))
    documented_methods = set(schema['$defs']['request']['properties']['method']['enum'])
    host = SOURCES / 'com' / 'cburch' / 'logisim' / 'agent' / 'Host.java'
    implementation_methods = set(re.findall(r'case "(logisim\.[a-z_]+)"', host.read_text(encoding='utf-8')))
    report.record('schema-methods', documented_methods == implementation_methods, sorted(implementation_methods), sorted(documented_methods))

    archive = ROOT / 'outputs' / 'logisim-agent-windows.zip'
    report.run('package', [sys.executable, 'scripts/package.py', str(archive)], 90)
    clean = target / 'clean workspace'
    with ZipFile(archive) as zipped:
        report.record('portable-zip-integrity', zipped.testzip() is None, 'All archive CRC values match', zipped.testzip())
        zipped.extractall(clean)
    launcher = ['cmd.exe', '/d', '/c', 'logisim-agent.cmd'] if os.name == 'nt' else [sys.executable, 'client-python/logisim_agent.py']
    result = json.loads(report.run('clean-capabilities', [*launcher, 'capabilities'], cwd=clean))
    report.record('installed-clean-runtime', result['engine_version'] == '2.16.2.2.jar' and result['grid_step'] == 10,
                  '2.16.2.2.jar, 10-unit grid', result)
    messages = '\n'.join([
        '{"jsonrpc":"2.0","method":"logisim.get_capabilities"}',
        '{"jsonrpc":"2.0","id":"a","method":"logisim.get_capabilities"}',
        '[{"jsonrpc":"2.0","id":2,"method":"logisim.unknown"},{"jsonrpc":"2.0","id":3,"method":"logisim.get_capabilities"}]',
        '{"invalid":',
        '',
    ])
    replies = [json.loads(line) for line in report.run('clean-stdio', [*launcher, 'serve'], cwd=clean, input_data=messages).splitlines()]
    report.record('stdio-notification-batch-errors', len(replies) == 3 and replies[0]['id'] == 'a'
                  and [item['id'] for item in replies[1]] == [2, 3] and replies[1][0]['error']['code'] == -32601
                  and replies[2]['error']['code'] == -32700 and all(isinstance(reply['details'], dict) and reply['recoverable']
                  for reply in (replies[1][0]['error'], replies[2]['error'])), 'One response, one batch, one parse error; notification silent',
                  [replies[0]['id'], replies[1][0]['error']['code'], replies[2]['error']['code']])
    report.run('clean-render', [*launcher, 'render', 'outputs/half-adder.circ', 'work/clean.png', '--scale', '3'], cwd=clean)
    result = json.loads(report.run('clean-states-render', [*launcher, 'render', 'outputs/half-adder.circ',
                                                          'work/clean-states.zip', '--grid', '--states',
                                                          'examples/half-adder-states.json'], cwd=clean))
    report.record('clean-states-grid', result['passed'] and len(result['images']) == 4
                  and all(image['show_grid'] for image in result['images']), 'Four state PNGs with the grid enabled', len(result['images']))
    report.run('clean-vectors', [*launcher, 'vectors', 'outputs/register4.circ', 'examples/register-vectors.json',
                                'work/vectors.json'], cwd=clean)
    measured = json.loads((clean / 'work/vectors.json').read_text(encoding='utf-8'))
    report.record('clean-vector-artifacts', measured['passed'] and (clean / 'work/vectors.md').is_file(),
                  'Passing JSON and Markdown reports', measured['passed'])

    for example in ('half-adder', 'register4', 'mux2x4'):
        original(report, clean / f'outputs/{example}.circ', f'portable-original-{example}')
    with Client(ROOT, core) as client:
        if (ROOT / 'practical4.circ').exists():
            sample = target / 'practical4-copy.circ'
            shutil.copy2(ROOT / 'practical4.circ', sample)
            opened = client.call('open_project', path=str(sample))
            project = opened['project_id']
            before = {name: graph_snapshot(client.call('get_circuit', project_id=project, circuit=name)) for name in opened['circuits']}
            report.record('practical4-circuits', set(before) == {'main', 'FullAdder', 'Adder4', 'Mux4'},
                          ['main', 'FullAdder', 'Adder4', 'Mux4'], list(before))
            before_xml = ET.parse(sample).getroot()
            pin = next(component for component in before['Mux4']['components'] if component['factory'] == 'Pin')
            client.call('update_component', project_id=project, circuit='Mux4', id=pin['id'], label='QA label')
            client.call('save', project_id=project)
            after_xml = ET.parse(sample).getroot()
            unchanged = all(ET.tostring(before_xml.find(f"circuit[@name='{name}']")) == ET.tostring(after_xml.find(f"circuit[@name='{name}']"))
                            for name in ('main', 'FullAdder', 'Adder4'))
            report.record('practical4-preserved-sibling-circuits', unchanged and after_xml.get('source') == '2.16.2.2.exe'
                          and [ET.tostring(lib) for lib in before_xml.findall('lib')] == [ET.tostring(lib) for lib in after_xml.findall('lib')],
                          'Three untouched circuit subtrees, file header and libraries retained', unchanged)
            original(report, sample, 'practical4-edited-original-reopen')

        library_path = target / 'library.circ'
        top = client.call('create_project', path=str(library_path), top_circuit='BufferSource')
        lib_id = top['project_id']
        client.call('create_circuit', project_id=lib_id, name='Unused')
        client.call('save', project_id=lib_id)
        external = target / 'external-reference.circ'
        external.write_text('''<?xml version="1.0" encoding="UTF-8"?>
<project source="2.16.2.2.exe" version="1.0"><lib desc="file#library.circ" name="2" future="visible"/>
<main name="Top"/><circuit name="Top"><comp lib="2" loc="(100,100)" name="BufferSource"/></circuit></project>''', encoding='utf-8')
        external_project = client.call('open_project', path=str(external))
        ext_id = external_project['project_id']
        source = client.call('get_circuit', project_id=ext_id)['components'][0]
        report.record('relative-library-component', source['factory'] == 'BufferSource', 'BufferSource', source['factory'])
        client.call('move_component', project_id=ext_id, id=source['id'], x=200, y=200)
        client.call('add_component', project_id=ext_id, factory='BufferSource', x=300, y=300)
        client.call('save', project_id=ext_id)
        reference = ET.parse(external).find('lib')
        report.record('relative-library-preserved', reference.attrib == {'desc': 'file#library.circ', 'name': '2', 'future': 'visible'},
                      'Original descriptor and extension attribute', reference.attrib)
        original(report, external, 'external-library-original-reopen')
        hierarchy = target / 'hierarchical.circ'
        nested = client.call('create_project', path=str(hierarchy), top_circuit='Main')['project_id']
        client.call('create_circuit', project_id=nested, name='Leaf')
        for is_output, x, label in ((False, 80, 'A'), (True, 280, 'Y')):
            client.call('add_component', project_id=nested, circuit='Leaf', factory='Pin', x=x, y=100, label=label,
                        facing='west' if is_output else 'east', attrs={'output': is_output, 'tristate': False})
        client.call('connect', project_id=nested, circuit='Leaf', width=1, points=[point(80, 100), point(280, 100)])
        child = client.call('add_component', project_id=nested, factory='Leaf', x=260, y=150)
        for pin in child['pins']:
            is_output = pin['direction'] == 'output'
            x = 420 if is_output else 80
            client.call('add_component', project_id=nested, factory='Pin', x=x, y=pin['y'], label='Y' if is_output else 'A',
                        facing='west' if is_output else 'east', attrs={'output': is_output, 'tristate': False})
            client.call('connect', project_id=nested, width=pin['width'], points=[point(x, pin['y']), point(pin['x'], pin['y'])])
        client.call('save', project_id=nested)
        observed = client.call('run_vectors', project_id=nested,
                               vectors=[{'inputs': {'A': a}, 'expect': {'Y': a}} for a in (0, 1)])
        report.record('nested-circuit-vectors', observed['passed'], 'Signals propagate through the generated subcircuit', observed)
        original(report, hierarchy, 'nested-circuit-original-reopen')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('stage', choices=['m0', 'm1', 'm2', 'm3', 'm4', 'm5'])
    parser.add_argument('--core', type=Path)
    args = parser.parse_args()
    core = core_path(args.core)
    report = Report(args.stage, core)
    try:
        report.run('jdk', [jdk('java'), '-version'])
        report.run('compile', compile_args(core))
        {'m0': baseline, 'm1': probe, 'm2': editing, 'm3': timing, 'm4': render, 'm5': compatibility}[args.stage](report, core)
    except Exception as error:
        report.data['failure'] = f'{type(error).__name__}: {error}'
        raise
    finally:
        report.finish()


if __name__ == '__main__':
    main()
