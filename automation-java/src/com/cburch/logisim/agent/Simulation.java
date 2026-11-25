// SPDX-License-Identifier: GPL-3.0-only
package com.cburch.logisim.agent;

import com.cburch.logisim.circuit.CircuitState;
import com.cburch.logisim.circuit.Propagator;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.data.BitWidth;
import com.cburch.logisim.data.Value;
import com.cburch.logisim.std.wiring.Clock;
import com.cburch.logisim.std.wiring.Pin;
import com.cburch.logisim.std.wiring.Probe;
import com.google.gson.*;
import org.w3c.dom.Element;

import java.math.BigDecimal;
import java.util.*;

import static com.cburch.logisim.agent.Rpc.*;

final class Simulation {
    private static final int MAX_EDGES = 1000;
    private static final int MAX_TICKS = 10_000;

    static final class Endpoint {
        final String id;
        final String label;
        final Component component;
        final String type;

        Endpoint(Element xml, Component component, String type) {
            id = XmlDocument.id(xml);
            label = Components.label(component);
            this.component = component;
            this.type = type;
        }

        int width() { return component.getEnd(0).getWidth().getWidth(); }

        Value observed(CircuitState state) {
            if (component.getFactory() instanceof Pin) return NativeCore.output(state, component);
            return state.getValue(component.getEnd(0).getLocation());
        }
    }

    static final class CircuitIO {
        final Workspace.Session project;
        final String name;
        final List<Endpoint> endpoints = new ArrayList<>();

        CircuitIO(Workspace.Session project, String name) {
            this.project = project;
            this.name = name;
            for (Element raw : XmlDocument.children(XmlDocument.circuit(project.document.xml, name), "comp")) {
                Component component = XmlDocument.component(project.circuit(name), raw);
                if (component.getFactory() instanceof Pin) endpoints.add(new Endpoint(raw, component,
                        component.getEnd(0).isOutput() ? "input" : "output"));
                else if (component.getFactory() instanceof Probe) endpoints.add(new Endpoint(raw, component, "probe"));
                else if (component.getFactory() instanceof Clock) endpoints.add(new Endpoint(raw, component, "clock"));
            }
        }

        Endpoint resolve(String reference, Set<String> types) {
            List<Endpoint> matches = new ArrayList<>();
            for (Endpoint endpoint : endpoints) {
                if (types.contains(endpoint.type) && endpoint.id.equals(reference)) return endpoint;
                if (types.contains(endpoint.type) && reference.equals(endpoint.label)) matches.add(endpoint);
            }
            if (matches.size() == 1) return matches.get(0);
            if (matches.size() > 1) throw fault(CONFLICT, "Pin or clock label is ambiguous; use its ID", "reference", reference);
            throw fault(MISSING, "Pin, probe or clock is unavailable for this operation", "reference", reference, "types", types);
        }

        Endpoint clock(String id) {
            if (id != null) return resolve(id, Set.of("clock"));
            List<Endpoint> clocks = new ArrayList<>();
            for (Endpoint endpoint : endpoints) if (endpoint.type.equals("clock")) clocks.add(endpoint);
            if (clocks.size() != 1) throw fault(INVALID, "Specify clock_id when the circuit does not have exactly one clock", "count", clocks.size());
            return clocks.get(0);
        }

        Map<String, Object> read(CircuitState state, boolean settled) {
            Propagator prop = state.getPropagator();
            Map<String, Object> result = new LinkedHashMap<>();
            List<String> unknown = new ArrayList<>();
            List<String> conflicts = new ArrayList<>();
            for (Endpoint endpoint : endpoints) {
                if (!endpoint.type.equals("output") && !endpoint.type.equals("probe")) continue;
                Value value = endpoint.observed(state);
                if (value.isErrorValue()) conflicts.add(endpoint.id);
                else if (!value.isFullyDefined()) unknown.add(endpoint.id);
                result.put(endpoint.id, object("label", endpoint.label, "kind", endpoint.type, "value", value(value)));
            }
            return object("circuit", name, "settled", settled, "stable", settled && !prop.isOscillating(),
                    "oscillating", prop.isOscillating(), "tick", prop.getTickCount(), "outputs", result,
                    "unknown", unknown, "conflicts", conflicts);
        }

        Map<String, Object> currentInputs(CircuitState state) {
            Map<String, Object> inputs = new LinkedHashMap<>();
            for (Endpoint pin : endpoints) {
                if (pin.type.equals("input")) inputs.put(pin.id, object("label", pin.label, "value", value(pin.observed(state))));
            }
            return inputs;
        }
    }

    static Map<String, Object> setInput(CircuitIO io, JsonObject params) {
        Endpoint pin = io.resolve(text(params, "pin_id"), Set.of("input"));
        Value value = inputValue(pin, params.get("value"));
        NativeCore.input(io.project.state(io.name), pin.component, value);
        io.project.pendingInputs.add(io.name);
        return object("pin_id", pin.id, "value", value(value), "settled", false);
    }

    static Map<String, Object> simulate(CircuitIO io, JsonObject params) {
        CircuitState state = io.project.state(io.name);
        if (bool(params, "settle", true)) {
            state.getPropagator().propagate();
            io.project.pendingInputs.remove(io.name);
        }
        return io.read(state, !io.project.pendingInputs.contains(io.name));
    }

    static Map<String, Object> clockStep(CircuitIO io, JsonObject params) {
        Endpoint clock = io.clock(text(params, "clock_id", null));
        int edges = integer(params, "edges", 1, MAX_EDGES, 1);
        checkTicks(clock, edges);
        CircuitState state = io.project.state(io.name);
        Map<String, Object> result = step(io, state, clock, edges);
        io.project.pendingInputs.remove(io.name);
        return result;
    }

    private static int checkTicks(Endpoint clock, int edges) {
        int high = clock.component.getAttributeSet().getValue(Clock.ATTR_HIGH);
        int low = clock.component.getAttributeSet().getValue(Clock.ATTR_LOW);
        long worst = (long) Math.max(high, low) * edges;
        if (high < 1 || low < 1 || worst > MAX_TICKS) throw fault(LIMIT, "Clock request exceeds native tick budget", "limit", MAX_TICKS, "worst_case", worst);
        return (int) worst;
    }

    private static Map<String, Object> step(CircuitIO io, CircuitState state, Endpoint clock, int edges) {
        int budget = checkTicks(clock, edges);
        Clock.ClockLogger logger = new Clock.ClockLogger();
        Propagator prop = state.getPropagator();
        prop.propagate();
        Value previous = logger.getLogValue(state.getInstanceState(clock.component), null);
        List<Map<String, Object>> transitions = new ArrayList<>();
        for (int i = 0; i < budget && transitions.size() < edges && !prop.isOscillating(); i++) {
            prop.tick();
            prop.propagate();
            Value current = logger.getLogValue(state.getInstanceState(clock.component), null);
            if (!current.equals(previous)) {
                transitions.add(object("tick", prop.getTickCount(), "edge", current.equals(Value.TRUE) ? "rising" : "falling"));
                previous = current;
            }
        }
        Map<String, Object> result = io.read(state, true);
        result.put("clock_id", clock.id);
        result.put("requested_edges", edges);
        result.put("edges", transitions);
        return result;
    }

    static final class Vector {
        final String label;
        final Map<Endpoint, Value> inputs = new LinkedHashMap<>();
        final Map<Endpoint, Value> expected = new LinkedHashMap<>();
        final Endpoint clock;
        final int edges;
        final boolean reset;

        Vector(CircuitIO io, JsonObject spec, int number) {
            label = text(spec, "label", "State " + number);
            reset = bool(spec, "reset", false);
            edges = integer(spec, "edges", 0, MAX_EDGES, 0);
            clock = edges == 0 ? null : io.clock(text(spec, "clock_id", null));
            if (clock != null) checkTicks(clock, edges);
            for (var item : fields(spec, "inputs").entrySet()) {
                Endpoint pin = io.resolve(item.getKey(), Set.of("input"));
                if (inputs.put(pin, inputValue(pin, item.getValue())) != null) {
                    throw fault(CONFLICT, "One input was specified more than once", "pin_id", pin.id);
                }
            }
            for (var item : fields(spec, "expect").entrySet()) {
                Endpoint pin = io.resolve(item.getKey(), Set.of("output", "probe"));
                int width = pin.width();
                if (width == 0) width = io.project.circuit(io.name).getWidth(pin.component.getEnd(0).getLocation()).getWidth();
                if (width == 0 && item.getValue().isJsonPrimitive() && item.getValue().getAsJsonPrimitive().isString()) {
                    width = item.getValue().getAsString().replace(" ", "").length();
                }
                if (expected.put(pin, parse(item.getValue(), width, true)) != null) {
                    throw fault(CONFLICT, "One output was specified more than once", "pin_id", pin.id);
                }
            }
        }

        Map<String, Object> execute(CircuitIO io, CircuitState state) {
            for (var item : inputs.entrySet()) {
                Endpoint pin = item.getKey();
                NativeCore.input(state, pin.component, item.getValue());
            }
            state.getPropagator().propagate();
            Map<String, Object> result = edges == 0 ? io.read(state, true) : step(io, state, clock, edges);
            boolean passed = Boolean.TRUE.equals(result.get("stable"))
                    && (edges == 0 || ((List<?>) result.get("edges")).size() == edges);
            Map<String, Object> comparisons = new LinkedHashMap<>();
            for (var check : expected.entrySet()) {
                Endpoint pin = check.getKey();
                Value actual = pin.observed(state);
                boolean matches = actual.equals(check.getValue());
                comparisons.put(pin.id, object("label", pin.label, "expected", value(check.getValue()),
                        "actual", value(actual), "passed", matches));
                passed &= matches;
            }
            result.put("label", label);
            result.put("inputs", io.currentInputs(state));
            result.put("comparisons", comparisons);
            result.put("passed", passed);
            return result;
        }
    }

    static List<Vector> vectors(CircuitIO io, JsonArray states) {
        if (states.size() < 1 || states.size() > 256) throw fault(LIMIT, "Expected 1 to 256 states", "count", states.size());
        List<Vector> result = new ArrayList<>();
        for (int i = 0; i < states.size(); i++) result.add(new Vector(io, fields(states.get(i), "states[]"), i + 1));
        return result;
    }

    static Map<String, Object> runVectors(CircuitIO io, JsonObject params) {
        boolean independent = bool(params, "independent", false);
        List<Object> results = new ArrayList<>();
        CircuitState state = io.project.freshState(io.name);
        boolean passed = true;
        for (Vector vector : vectors(io, array(params, "vectors"))) {
            if (independent || vector.reset) state = io.project.freshState(io.name);
            Map<String, Object> result = vector.execute(io, state);
            results.add(result);
            passed &= (boolean) result.get("passed");
        }
        return object("circuit", io.name, "revision", io.project.revision, "independent", independent,
                "passed", passed, "vectors", results);
    }

    private static Value inputValue(Endpoint pin, JsonElement element) {
        Value value = parse(element, pin.width(), false);
        if (!value.isFullyDefined() && !Boolean.TRUE.equals(pin.component.getAttributeSet().getValue(Pin.ATTR_TRISTATE))) {
            throw fault(INVALID, "Unknown input bits require a tristate pin", "pin_id", pin.id);
        }
        return value;
    }

    private static Value parse(JsonElement element, int width, boolean allowError) {
        if (width > 0 && width <= Value.MAX_WIDTH && element != null && element.isJsonPrimitive()) {
            JsonPrimitive primitive = element.getAsJsonPrimitive();
            if (primitive.isString()) {
                String bits = primitive.getAsString().replace(" ", "").toUpperCase(Locale.ROOT);
                if (bits.startsWith("0B")) bits = bits.substring(2);
                if (bits.length() == width && bits.matches(allowError ? "[01XE]+" : "[01X]+")) {
                    Value[] array = new Value[width];
                    for (int i = 0; i < width; i++) {
                        switch (bits.charAt(width - i - 1)) {
                            case '0': array[i] = Value.FALSE; break;
                            case '1': array[i] = Value.TRUE; break;
                            case 'X': array[i] = Value.UNKNOWN; break;
                            default: array[i] = Value.ERROR;
                        }
                    }
                    return Value.create(array);
                }
            } else if (primitive.isNumber()) {
                try {
                    BigDecimal number = new BigDecimal(primitive.getAsString());
                    if (number.signum() >= 0 && number.compareTo(BigDecimal.valueOf((1L << width) - 1)) <= 0) {
                        return Value.createKnown(BitWidth.create(width), (int) number.longValueExact());
                    }
                } catch (ArithmeticException | NumberFormatException error) {
                    throw fault(INVALID, "Input number must be an integer", "value", element, "width", width);
                }
            }
        }
        throw fault(INVALID, "Value does not fit the pin bit width", "value", element, "width", width);
    }

    private static Map<String, Object> value(Value value) {
        return object("bits", value.toString().replace(" ", ""), "width", value.getWidth(),
                "integer", value.isFullyDefined() ? Integer.toUnsignedLong(value.toIntValue()) : null,
                "status", value.isErrorValue() ? "conflict" : value.isFullyDefined() ? "known" : "unknown");
    }

    private Simulation() { }
}
