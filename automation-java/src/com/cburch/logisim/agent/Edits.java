// SPDX-License-Identifier: GPL-3.0-only
package com.cburch.logisim.agent;

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.Wire;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.comp.EndData;
import com.cburch.logisim.data.Location;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.w3c.dom.Element;

import java.nio.file.Path;
import java.util.*;

import static com.cburch.logisim.agent.Rpc.*;

final class Edits {
    static Map<String, Object> add(Workspace.Session project, JsonObject params) {
        project.checkWrite(params);
        String circuit = project.circuitName(params);
        Components.Choice choice = Components.find(project.document, text(params, "factory"));
        if (choice.tool.getFactory() == project.circuit(circuit).getSubcircuitFactory()) {
            throw fault(CONFLICT, "Subcircuit cannot contain itself", "circuit", circuit);
        }
        Location point = Geometry.point(params, false);
        Map<String, String> requested = Components.requested(params);
        Component nativeInstance = Components.create(choice, point, requested, project.document);
        if (Components.snap(nativeInstance.getFactory(), nativeInstance.getAttributeSet())) Geometry.point(params, true);
        Map<String, String> normalized = Components.standards(nativeInstance.getAttributeSet());
        String id = "c-" + UUID.randomUUID();
        Map<String, Object> result = object("change", "component_added", "id", id);
        return project.edit(params, (before, raw) -> {
            placement(before, nativeInstance, null);
            Element node = XmlDocument.element(raw.getOwnerDocument(), "comp", "name", choice.name(),
                    "loc", point.toString(), XmlDocument.COMPONENT_ID, id);
            String lib = ensureLibrary(raw, choice, project.document);
            if (lib != null) node.setAttribute("lib", lib);
            for (String key : requested.keySet()) XmlDocument.attr(node, key, normalized.get(key));
            XmlDocument.append(raw, node);
            return result;
        }, loaded -> result.putAll(Components.item(XmlDocument.component(loaded.core.getCircuit(circuit),
                Workspace.component(XmlDocument.circuit(loaded.xml, circuit), id)),
                Workspace.component(XmlDocument.circuit(loaded.xml, circuit), id))));
    }

    private static String ensureLibrary(Element circuit, Components.Choice choice, XmlDocument document) {
        if (choice.library == document.core) return null;
        Element root = circuit.getOwnerDocument().getDocumentElement();
        String descriptor = document.core.getLoader().getDescriptor(choice.library);
        if (descriptor == null || descriptor.isEmpty()) descriptor = "#" + choice.category();
        Set<String> used = new HashSet<>();
        for (Element lib : XmlDocument.children(root, "lib")) {
            String original = lib.getAttribute("desc");
            if (descriptor.equals(original) || sameLibraryFile(document, original, descriptor)) return lib.getAttribute("name");
            used.add(lib.getAttribute("name"));
        }
        if (!descriptor.startsWith("#")) throw fault(FORMAT, "External library declaration cannot be resolved", "descriptor", descriptor);
        int next = 0;
        while (used.contains(Integer.toString(next))) next++;
        String id = Integer.toString(next);
        root.insertBefore(XmlDocument.element(root.getOwnerDocument(), "lib", "name", id, "desc", descriptor), root.getFirstChild());
        return id;
    }

    private static boolean sameLibraryFile(XmlDocument document, String original, String nativeDescriptor) {
        if (!original.startsWith("file#") || !nativeDescriptor.startsWith("file#")) return false;
        Path folder = document.source.getParent();
        return folder.resolve(original.substring(5)).normalize().equals(folder.resolve(nativeDescriptor.substring(5)).normalize());
    }

    static Map<String, Object> addCircuit(Workspace.Session project, JsonObject params) {
        String name = text(params, "name");
        if (name.isBlank() || name.length() > 120) throw fault(INVALID, "Invalid circuit name", "name", name);
        if (project.circuits().contains(name)) throw fault(CONFLICT, "Circuit name already exists", "name", name);
        return project.edit(params, (before, raw) -> {
            var doc = raw.getOwnerDocument();
            XmlDocument.append(doc.getDocumentElement(), XmlDocument.element(doc, "circuit", "name", name));
            return object("change", "circuit_added", "name", name);
        }, loaded -> {
            if (loaded.core.getCircuit(name) == null) throw fault(FORMAT, "Native loader omitted the new circuit", "name", name);
        });
    }

    static Map<String, Object> update(Workspace.Session project, JsonObject params) {
        project.checkWrite(params);
        String name = project.circuitName(params);
        String id = text(params, "id");
        Circuit before = project.circuit(name);
        Element source = XmlDocument.circuit(project.document.xml, name);
        Element target = Workspace.component(source, id);
        Component original = XmlDocument.component(before, target);
        Map<String, String> requested = Components.requested(params);
        if (params.has("attribute")) {
            JsonElement value = params.get("value");
            if (value == null || !value.isJsonPrimitive()) throw fault(INVALID, "Attribute value is missing or invalid", "parameter", "value");
            requested.put(text(params, "attribute"), value.getAsString());
        }
        Location position = params.has("position") ? Geometry.point(fields(params, "position"),
                Components.snap(original.getFactory(), original.getAttributeSet())) : original.getLocation();
        String reroute = text(params, "reroute", "preserve");
        if (!Set.of("preserve", "detach").contains(reroute)) throw fault(INVALID, "Unknown reroute mode", "reroute", reroute);
        if (requested.isEmpty() && position.equals(original.getLocation())) throw fault(CONFLICT, "Component edit has no changes", "id", id);
        Map<String, Object> result = object("change", "component_updated", "id", id);
        Map<String, String> merged = Components.standards(original.getAttributeSet());
        merged.values().removeIf(Objects::isNull);
        merged.putAll(requested);
        Component candidate;
        try {
            var attrs = original.getFactory().createAttributeSet();
            Set<String> stored = XmlDocument.attrs(target).keySet();
            merged.keySet().removeIf(key -> !requested.containsKey(key) && !stored.contains(key));
            candidate = NativeCore.create(original.getFactory(), attrs, position, merged, project.document.version());
        } catch (IllegalArgumentException error) {
            throw fault(INVALID, "Component attributes are invalid", "id", id, "diagnostic", error.getMessage());
        }
        Map<String, String> normalized = Components.standards(candidate.getAttributeSet());
        Map<Location, Location> routes = new LinkedHashMap<>();
        return project.edit(params, (previous, raw) -> {
            placement(previous, candidate, original);
            Element node = Workspace.component(raw, id);
            node.setAttribute(XmlDocument.COMPONENT_ID, id);
            node.setAttribute("loc", position.toString());
            for (String key : requested.keySet()) XmlDocument.attr(node, key, normalized.get(key));
            if (reroute.equals("preserve")) preserve(previous, raw, original, candidate, routes);
            return result;
        }, loaded -> {
            Element raw = XmlDocument.circuit(loaded.xml, name);
            Element node = Workspace.component(raw, id);
            Circuit after = loaded.core.getCircuit(name);
            if (!routes.isEmpty()) verifyRoutes(before, after, source, raw, routes);
            result.putAll(Components.item(XmlDocument.component(after, node), node));
        });
    }

    private static void placement(Circuit circuit, Component candidate, Component original) {
        for (Component existing : circuit.getNonWires()) {
            if (existing == original) continue;
            if (existing.getLocation().equals(candidate.getLocation()) && existing.getFactory() == candidate.getFactory()) {
                throw fault(CONFLICT, "Same factory already exists at position", "position", Geometry.position(candidate.getLocation()),
                        "factory", candidate.getFactory().getName());
            }
        }
        if (original == null && circuit.hasConflict(candidate)) {
            throw fault(CONFLICT, "Component conflicts with an existing exclusive endpoint", "position", Geometry.position(candidate.getLocation()));
        }
    }

    private static void preserve(Circuit circuit, Element source, Component original, Component candidate,
                                 Map<Location, Location> routes) {
        for (int i = 0; i < original.getEnds().size(); i++) {
            EndData old = original.getEnd(i);
            Location from = old.getLocation();
            if (!circuit.isConnected(from, original)) continue;
            if (i >= candidate.getEnds().size()) throw fault(CONFLICT, "Edit removes a connected pin", "pin_index", i);
            EndData replacement = candidate.getEnd(i);
            Location to = replacement.getLocation();
            if (from.equals(to)) continue;
            if (!old.getWidth().equals(replacement.getWidth()) || circuit.getWires(from).isEmpty()) {
                throw fault(CONFLICT, "Connected pin cannot be rerouted safely", "pin_index", i);
            }
            Location corner = Location.create(to.getX(), from.getY());
            if (!from.equals(corner)) Geometry.append(source, Geometry.segment(from, corner), old.getWidth().getWidth(), null);
            if (!to.equals(corner)) Geometry.append(source, Geometry.segment(corner, to), old.getWidth().getWidth(), null);
            routes.put(from, to);
        }
    }

    private static void verifyRoutes(Circuit before, Circuit after, Element source, Element edited, Map<Location, Location> routes) {
        Geometry.Graph previous = new Geometry.Graph(before, source);
        Geometry.Graph current = new Geometry.Graph(after, edited);
        Map<String, Set<String>> upstreamNets = new HashMap<>();
        for (var link : current.byWire.entrySet()) {
            Set<String> origins = upstreamNets.computeIfAbsent(link.getValue().id, key -> new HashSet<>());
            for (var old : previous.byWire.entrySet()) {
                if (Geometry.overlap(link.getKey(), old.getKey())) origins.add(old.getValue().id);
            }
            if (origins.size() > 1) throw fault(CONFLICT, "Reroute would join existing nets", "net_ids", origins);
        }
        for (var route : routes.entrySet()) {
            if (current.nets.values().stream().noneMatch(net -> net.points.contains(route.getValue())
                    && net.wires.stream().anyMatch(wire -> Geometry.contains(wire, route.getKey())))) {
                throw fault(CONFLICT, "Reroute could not preserve connected pin", "from", Geometry.position(route.getKey()));
            }
        }
    }

    static Map<String, Object> remove(Workspace.Session project, JsonObject params) {
        String id = text(params, "id");
        boolean cascade = bool(params, "cascade", false);
        return project.edit(params, (before, raw) -> {
            Element target = Workspace.component(raw, id);
            Component component = XmlDocument.component(before, target);
            Set<Wire> attached = new LinkedHashSet<>();
            for (EndData end : component.getEnds()) attached.addAll(before.getWires(end.getLocation()));
            if (cascade) for (Wire wire : attached) Geometry.subtract(raw, wire);
            raw.removeChild(target);
            return object("change", "component_removed", "id", id, "removed_segments", cascade ? attached.size() : 0);
        }, ignored -> { });
    }

    static Map<String, Object> connect(Workspace.Session project, JsonObject params) {
        String name = project.circuitName(params);
        int width = integer(params, "width", 1, 32, 1);
        String label = text(params, "label", null);
        List<Location> points = new ArrayList<>();
        for (JsonElement point : array(params, "points")) points.add(Geometry.point(fields(point, "points[]"), true));
        if (points.size() < 2 || points.size() > 128) throw fault(INVALID, "Polyline requires 2 to 128 points");
        List<Wire> segments = new ArrayList<>();
        for (int i = 1; i < points.size(); i++) segments.add(Geometry.segment(points.get(i - 1), points.get(i)));
        Map<String, Object> result = object("change", "wire_connected");
        return project.edit(params, (before, raw) -> {
            if (!bool(params, "allow_dangling", false)) {
                for (Location point : List.of(points.get(0), points.get(points.size() - 1))) {
                    if (!Geometry.anchor(before, point)) throw fault(CONFLICT, "Wire endpoint has no pin or existing segment", "point", Geometry.position(point));
                }
            }
            if (segments.stream().allMatch(segment -> before.getWires().stream().anyMatch(existing -> Geometry.covers(existing, segment)))) {
                throw fault(CONFLICT, "Connection is already present");
            }
            for (Wire wire : segments) Geometry.append(raw, wire, width, label);
            return result;
        }, loaded -> {
            Geometry.Graph graph = new Geometry.Graph(loaded.core.getCircuit(name), XmlDocument.circuit(loaded.xml, name));
            graph.checkNewSegments(segments, width);
            List<Map<String, Object>> affected = new ArrayList<>();
            Set<String> nets = new LinkedHashSet<>();
            for (Map<String, Object> wire : graph.wires()) {
                for (Wire candidate : graph.byWire.keySet()) {
                    if (Geometry.id(candidate).equals(wire.get("id")) && segments.stream().anyMatch(part -> Geometry.overlap(candidate, part))) {
                        affected.add(wire);
                        nets.add(graph.byWire.get(candidate).id);
                        break;
                    }
                }
            }
            result.put("wires", affected);
            result.put("net_ids", nets);
        });
    }

    static Map<String, Object> disconnect(Workspace.Session project, JsonObject params) {
        String id = params.has("wire_or_net_id") ? text(params, "wire_or_net_id") : text(params, "id");
        return project.edit(params, (before, raw) -> {
            List<Wire> selected = new Geometry.Graph(before, raw).resolve(id);
            for (Wire wire : selected) Geometry.subtract(raw, wire);
            return object("change", "wire_disconnected", "id", id, "removed_segments", selected.size());
        }, ignored -> { });
    }

    private Edits() { }
}
