"""Example builders using factory geometry returned by Logisim-ITA."""


def point(x, y):
    return {'x': x, 'y': y}


def at(pin):
    return point(pin['x'], pin['y'])


def half_adder(client, path):
    project = client.call('create_project', path=str(path), top_circuit='HalfAdder')['project_id']

    def add(factory, x, y, **params):
        return client.call('add_component', project_id=project, factory=factory, x=x, y=y, **params)

    def join(*points):
        return client.call('connect', project_id=project, points=points, width=1)

    def pin(x, y, label, output=False):
        return add('Pin', x, y, label=label, facing='west' if output else 'east', attrs={
            'output': output, 'tristate': False, 'labelloc': 'east' if output else 'west', 'width': 1})

    a = pin(80, 120, 'A')
    b = pin(80, 240, 'B')
    total = pin(380, 140, 'S', True)
    carry = pin(380, 220, 'C', True)
    xor = add('XOR Gate', 300, 140, attrs={'inputs': 2, 'width': 1})
    and_gate = add('AND Gate', 300, 220, attrs={'inputs': 2, 'width': 1})

    def inputs(gate):
        return sorted((at(p) for p in gate['pins'] if p['direction'] == 'input'), key=lambda p: p['y'])

    x1, x2 = inputs(xor)
    g1, g2 = inputs(and_gate)
    join(at(a['pins'][0]), point(140, 120), point(140, x1['y']), x1)
    join(point(140, x1['y']), point(140, g1['y']), g1)
    join(at(b['pins'][0]), point(190, 240), point(190, x2['y']), x2)
    join(point(190, g2['y']), g2)
    join(xor['position'], total['position'])
    join(and_gate['position'], carry['position'])
    client.call('save', project_id=project)
    return project, {'A': a['id'], 'B': b['id'], 'S': total['id'], 'C': carry['id']}


def register(client, path):
    project = client.call('create_project', path=str(path), top_circuit='Register4')['project_id']

    def add(factory, x, y, **params):
        return client.call('add_component', project_id=project, factory=factory, x=x, y=y, **params)

    def join(width, *points):
        return client.call('connect', project_id=project, points=points, width=width)

    d = add('Pin', 80, 150, label='D', attrs={'width': 4, 'tristate': False})
    q = add('Pin', 420, 150, label='Q', facing='west', attrs={'width': 4, 'output': True, 'labelloc': 'east'})
    clock = add('Clock', 80, 220, label='CLK', attrs={'highDuration': 2, 'lowDuration': 3})
    storage = add('Register', 260, 150, attrs={'width': 4, 'trigger': 'rising', 'Select': 'low'})
    pins = [at(pin) for pin in storage['pins']]
    join(4, d['position'], pins[1])
    join(4, pins[0], q['position'])
    join(1, clock['position'], point(pins[2]['x'], clock['position']['y']), pins[2])

    for index, start, facing, value in ((3, (250, 200), 'north', 0), (4, (150, 160), 'east', 1),
                                        (5, (150, 140), 'east', 0), (6, (250, 100), 'south', 0)):
        constant = add('Constant', *start, facing=facing, attrs={'width': 1, 'value': f'0x{value}'})
        join(1, constant['position'], pins[index])

    client.call('save', project_id=project)
    return project, {'D': d['id'], 'Q': q['id'], 'CLK': clock['id']}


def multiplexer(client, path):
    project = client.call('create_project', path=str(path), top_circuit='Mux2x4')['project_id']

    def add(factory, x, y, **params):
        return client.call('add_component', project_id=project, factory=factory, x=x, y=y, **params)

    def join(width, *points):
        return client.call('connect', project_id=project, points=points, width=width)

    mux = add('Multiplexer', 300, 160, attrs={'select': 1, 'width': 4, 'enable': False})
    pins = [at(pin) for pin in mux['pins']]
    for i, y in enumerate((120, 200)):
        pin = add('Pin', 80, y, label=f'I{i}', attrs={'width': 4, 'tristate': False, 'labelloc': 'west'})
        join(4, pin['position'], point(170, y), point(170, pins[i]['y']), pins[i])
    select = add('Pin', 80, 260, label='SEL', attrs={'width': 1, 'tristate': False, 'labelloc': 'west'})
    join(1, select['position'], point(pins[2]['x'], 260), pins[2])
    sender = add('Tunnel', 380, 160, facing='west', label='MUX_BUS', attrs={'width': 4})
    receiver = add('Tunnel', 550, 160, facing='east', label='MUX_BUS', attrs={'width': 4})
    split = add('Splitter', 620, 160, attrs={'incoming': 4, 'fanout': 4})
    join(4, pins[3], sender['position'])
    join(4, receiver['position'], at(split['pins'][0]))
    for i, port in enumerate(split['pins'][1:]):
        output_y = (60, 100, 200, 240)[i]
        output = add('Pin', 880, output_y, facing='west', label=f'Q{i}', attrs={'output': True, 'labelloc': 'east'})
        x = (680, 700, 720, 700)[i]
        if port['y'] == output_y:
            join(1, at(port), output['position'])
        else:
            join(1, at(port), point(x, port['y']), point(x, output_y), output['position'])
    client.call('save', project_id=project)
    return project, {}
