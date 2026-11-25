"""Small independently authored fixtures for the original application's CLI."""

from pathlib import Path
from xml.etree import ElementTree as ET


def fixture(path, kind):
    project = ET.Element('project', source='2.16.2.2.exe', version='1.0')
    for number, library in enumerate(('Wiring', 'Gates', 'Memory')):
        ET.SubElement(project, 'lib', name=str(number), desc=f'#{library}')
    ET.SubElement(project, 'main', name='main')
    circuit = ET.SubElement(project, 'circuit', name='main')

    def component(library, name, loc, **attributes):
        node = ET.SubElement(circuit, 'comp', lib=str(library), name=name, loc=loc)
        for key, value in attributes.items():
            ET.SubElement(node, 'a', name=key, val=value)

    def wire(start, end):
        ET.SubElement(circuit, 'wire', {'from': start, 'to': end})

    component(0, 'Pin', '(80,100)', label='A')
    component(0, 'Pin', '(240,100)', facing='west', output='true', label='Y')
    if kind == 'wire':
        wire('(80,100)', '(240,100)')
    elif kind == 'gate':
        component(1, 'NOT Gate', '(180,100)')
        wire('(80,100)', '(150,100)')
        wire('(180,100)', '(240,100)')
    elif kind == 'memory':
        component(2, 'Register', '(200,200)')
        wire('(80,100)', '(240,100)')
    else:
        raise ValueError(kind)
    ET.indent(project, space='  ')
    ET.ElementTree(project).write(path, encoding='utf-8', xml_declaration=True)


if __name__ == '__main__':
    directory = Path(__file__).resolve().parents[1] / 'work' / 'baseline'
    directory.mkdir(parents=True, exist_ok=True)
    for name in ('wire', 'gate', 'memory'):
        fixture(directory / f'{name}.circ', name)
