// SPDX-License-Identifier: GPL-3.0-only
package com.cburch.logisim.agent;

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.Wire;
import com.cburch.logisim.circuit.WireSet;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.comp.EndData;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.gui.main.Canvas;
import com.google.gson.JsonObject;
import org.w3c.dom.Element;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static com.cburch.logisim.agent.Rpc.*;

final class Geometry {
    private static final int COORD_LIMIT = 1_000_000;
    static final int GRID = gridStep();
    private static final String WIDTH = "agent-width";
    private static final String LABEL = "agent-label";

    static Location point(JsonObject params, boolean snap) {
        int x = integer(params, "x", -COORD_LIMIT, COORD_LIMIT);
        int y = integer(params, "y", -COORD_LIMIT, COORD_LIMIT);
        if (snap && (Canvas.snapXToGrid(x) != x || Canvas.snapYToGrid(y) != y)) {
            throw fault(INVALID, "Point is off the Logisim grid", "x", x, "y", y, "grid_step", GRID);
        }
        return Location.create(x, y);
    }

    private static int gridStep() {
        for (int point = 1; point <= 100; point++) {
            if (Canvas.snapXToGrid(point) == point) return point;
        }
        throw new IllegalStateException("Logisim grid step is unavailable");
    }

    static Map<String, Object> position(Location loc) { return object("x", loc.getX(), "y", loc.getY()); }

    static Map<String, Object> port(EndData end, int index) {
        return object("index", index, "x", end.getLocation().getX(), "y", end.getLocation().getY(),
                "width", end.getWidth().getWidth(), "direction", end.isInput()
                        ? (end.isOutput() ? "inout" : "input") : "output");
    }

    static Wire segment(Location a, Location b) {
        if (a.equals(b) || (a.getX() != b.getX() && a.getY() != b.getY())) {
            throw fault(INVALID, "Wire segments must be nonzero and orthogonal", "from", position(a), "to", position(b));
        }
        return Wire.create(a, b);
    }

    static String id(Wire wire) { return "w:" + wire.getEnd0() + ":" + wire.getEnd1(); }

    static final class Net {
        final List<Wire> wires;
        final Set<Location> points = new TreeSet<>(Comparator.comparingInt(Location::getY).thenComparingInt(Location::getX));
        final Set<Integer> widths = new TreeSet<>();
        final Set<String> labels = new TreeSet<>();
        final String id;

        Net(List<Wire> wires) {
            this.wires = wires;
            StringJoiner seed = new StringJoiner(";");
            for (Wire wire : wires) {
                seed.add(id(wire));
                points.add(wire.getEnd0());
                points.add(wire.getEnd1());
            }
            id = "n-" + UUID.nameUUIDFromBytes(seed.toString().getBytes(StandardCharsets.UTF_8));
        }

        Integer width() { return widths.size() == 1 ? widths.iterator().next() : null; }

        Map<String, Object> describe() {
            List<Object> nodes = new ArrayList<>();
            for (Location point : points) nodes.add(position(point));
            List<String> segments = new ArrayList<>();
            for (Wire wire : wires) segments.add(id(wire));
            return object("id", id, "width", width(), "width_candidates", widths, "labels", labels,
                    "points", nodes, "wires", segments);
        }
    }

    static final class Graph {
        final Map<Wire, Net> byWire = new LinkedHashMap<>();
        final Map<String, Net> nets = new LinkedHashMap<>();

        Graph(Circuit circuit, Element source) {
            Set<Wire> remaining = new LinkedHashSet<>();
            circuit.getWires().stream().sorted(Comparator.comparing(Geometry::id)).forEach(remaining::add);
            while (!remaining.isEmpty()) {
                Wire start = remaining.iterator().next();
                WireSet group = circuit.getWireSet(start);
                List<Wire> wires = new ArrayList<>();
                wires.add(start);
                remaining.remove(start);
                Iterator<Wire> scan = remaining.iterator();
                while (scan.hasNext()) {
                    Wire wire = scan.next();
                    if (group.containsWire(wire)) {
                        wires.add(wire);
                        scan.remove();
                    }
                }
                Net net = new Net(wires);
                nets.put(net.id, net);
                for (Wire wire : wires) byWire.put(wire, net);
                for (Location point : net.points) {
                    int width = circuit.getWidth(point).getWidth();
                    if (width > 0) net.widths.add(width);
                }
            }
            for (Element node : XmlDocument.children(source, "wire")) {
                if (!node.hasAttribute(WIDTH) && !node.hasAttribute(LABEL)) continue;
                Wire raw = wire(node);
                for (var entry : byWire.entrySet()) {
                    if (!overlap(raw, entry.getKey())) continue;
                    if (node.hasAttribute(WIDTH)) {
                        String text = node.getAttribute(WIDTH);
                        if (text.matches("[1-9]|[12][0-9]|3[0-2]")) entry.getValue().widths.add(Integer.parseInt(text));
                    }
                    if (node.hasAttribute(LABEL)) entry.getValue().labels.add(node.getAttribute(LABEL));
                }
            }
        }

        List<Map<String, Object>> wires() {
            List<Map<String, Object>> results = new ArrayList<>();
            for (var entry : byWire.entrySet()) {
                Wire wire = entry.getKey();
                results.add(object("id", id(wire), "from", position(wire.getEnd0()), "to", position(wire.getEnd1()),
                        "width", entry.getValue().width(), "net_id", entry.getValue().id));
            }
            return results;
        }

        List<Map<String, Object>> junctions(Circuit circuit) {
            List<Map<String, Object>> results = new ArrayList<>();
            Set<Location> allPoints = new LinkedHashSet<>();
            for (Net net : nets.values()) allPoints.addAll(net.points);
            for (Location point : allPoints) {
                int segments = circuit.getWires(point).size();
                int ports = circuit.getNonWires(point).size();
                if (segments + ports > 2) results.add(object("x", point.getX(), "y", point.getY(),
                        "segments", segments, "components", ports));
            }
            return results;
        }

        List<Wire> resolve(String reference) {
            Net net = nets.get(reference);
            if (net != null) return net.wires;
            for (Wire wire : byWire.keySet()) if (id(wire).equals(reference)) return List.of(wire);
            throw fault(MISSING, "Wire or net does not exist", "id", reference);
        }

        void checkNewSegments(List<Wire> segments, int width) {
            for (Wire requested : segments) {
                boolean located = false;
                for (var item : byWire.entrySet()) {
                    if (!overlap(requested, item.getKey())) continue;
                    located = true;
                    if (!item.getValue().widths.equals(Set.of(width))) {
                        throw fault(CONFLICT, "Wire width conflicts with the native net", "wire", id(requested),
                                "requested", width, "widths", item.getValue().widths);
                    }
                }
                if (!located) throw fault(CONFLICT, "Logisim did not retain the requested segment", "wire", id(requested));
            }
        }
    }

    static boolean anchor(Circuit circuit, Location point) {
        for (Component component : circuit.getNonWires(point)) {
            for (EndData end : component.getEnds()) if (end.getLocation().equals(point)) return true;
        }
        for (Wire wire : circuit.getWires()) if (contains(wire, point)) return true;
        return false;
    }

    static boolean contains(Wire wire, Location point) {
        boolean vertical = vertical(wire);
        if (vertical ? point.getX() != wire.getEnd0().getX() : point.getY() != wire.getEnd0().getY()) return false;
        int coordinate = axis(point, vertical);
        return coordinate >= low(wire) && coordinate <= high(wire);
    }

    static boolean overlap(Wire a, Wire b) {
        boolean vertical = vertical(a);
        if (vertical != vertical(b)) return false;
        if (axis(a.getEnd0(), !vertical) != axis(b.getEnd0(), !vertical)) return false;
        return Math.max(low(a), low(b)) < Math.min(high(a), high(b));
    }

    static boolean covers(Wire a, Wire b) {
        return overlap(a, b) && low(a) <= low(b) && high(a) >= high(b);
    }

    static void append(Element circuit, Wire wire, int width, String label) {
        Element raw = XmlDocument.element(circuit.getOwnerDocument(), "wire", "from", wire.getEnd0().toString(),
                "to", wire.getEnd1().toString(), WIDTH, Integer.toString(width));
        if (label != null) raw.setAttribute(LABEL, label);
        XmlDocument.append(circuit, raw);
    }

    static void subtract(Element circuit, Wire remove) {
        for (Element raw : XmlDocument.children(circuit, "wire")) {
            Wire wire = wire(raw);
            if (!overlap(remove, wire)) continue;
            if (low(remove) > low(wire)) fragment(raw, wire, low(wire), low(remove));
            if (high(remove) < high(wire)) fragment(raw, wire, high(remove), high(wire));
            circuit.removeChild(raw);
        }
    }

    private static void fragment(Element raw, Wire wire, int from, int to) {
        Element node = (Element) raw.cloneNode(true);
        boolean vertical = vertical(wire);
        int line = axis(wire.getEnd0(), !vertical);
        node.setAttribute("from", (vertical ? Location.create(line, from) : Location.create(from, line)).toString());
        node.setAttribute("to", (vertical ? Location.create(line, to) : Location.create(to, line)).toString());
        raw.getParentNode().insertBefore(node, raw);
    }

    private static boolean vertical(Wire wire) { return wire.getEnd0().getX() == wire.getEnd1().getX(); }
    private static int axis(Location loc, boolean vertical) { return vertical ? loc.getY() : loc.getX(); }
    private static int low(Wire wire) { return Math.min(axis(wire.getEnd0(), vertical(wire)), axis(wire.getEnd1(), vertical(wire))); }
    private static int high(Wire wire) { return Math.max(axis(wire.getEnd0(), vertical(wire)), axis(wire.getEnd1(), vertical(wire))); }
    private static Wire wire(Element raw) { return segment(Location.parse(raw.getAttribute("from")), Location.parse(raw.getAttribute("to"))); }

    private Geometry() { }
}
