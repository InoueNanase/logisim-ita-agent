// SPDX-License-Identifier: GPL-3.0-only
package com.cburch.logisim.agent;

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.WidthIncompatibilityData;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.comp.EndData;
import com.cburch.logisim.data.Location;
import org.w3c.dom.Element;

import java.util.*;

import static com.cburch.logisim.agent.Rpc.*;

final class Validate {
    static Set<WidthIncompatibilityData> widthConflicts(Circuit circuit) {
        Set<WidthIncompatibilityData> conflicts = circuit.getWidthIncompatibilityData();
        return conflicts == null ? Set.of() : conflicts;
    }

    static Map<String, Object> circuit(Workspace workspace, Workspace.Session project, String name, boolean reopen) {
        Circuit circuit = project.circuit(name);
        Element source = XmlDocument.circuit(project.document.xml, name);
        Geometry.Graph graph = new Geometry.Graph(circuit, source);
        List<Map<String, Object>> diagnostics = new ArrayList<>();
        for (var conflict : widthConflicts(circuit)) {
            List<Object> ends = new ArrayList<>();
            for (int i = 0; i < conflict.size(); i++) {
                ends.add(object("location", Geometry.position(conflict.getPoint(i)), "width", conflict.getBitWidth(i).getWidth()));
            }
            diagnostics.add(object("severity", "error", "kind", "width_conflict", "ends", ends));
        }
        Map<Location, String> wiresAt = new HashMap<>();
        for (Geometry.Net net : graph.nets.values()) {
            for (Location node : net.points) wiresAt.put(node, net.id);
            if (net.widths.size() > 1) diagnostics.add(object("severity", "error", "kind", "width_conflict", "net_id", net.id, "widths", net.widths));
        }
        Map<String, List<Map<String, Object>>> drivers = new LinkedHashMap<>();
        Map<String, List<Map<String, Object>>> loads = new LinkedHashMap<>();
        Set<String> bidirectional = new HashSet<>();
        for (Element node : XmlDocument.children(source, "comp")) {
            Component component = XmlDocument.component(circuit, node);
            Map<String, Object> item = Components.item(component, node);
            var unknown = (Map<?, ?>) item.get("unknown_attributes");
            if (!unknown.isEmpty()) diagnostics.add(object("severity", "warning", "kind", "unknown_attributes", "id", item.get("id"), "attrs", unknown));
            for (EndData end : component.getEnds()) {
                Location point = end.getLocation();
                String group = wiresAt.getOrDefault(point, point.toString());
                var endpoint = object("id", item.get("id"), "location", Geometry.position(point), "width", end.getWidth().getWidth());
                if (end.isOutput() && end.isInput()) bidirectional.add(group);
                else if (end.isOutput()) drivers.computeIfAbsent(group, key -> new ArrayList<>()).add(endpoint);
                else if (end.isInput()) loads.computeIfAbsent(group, key -> new ArrayList<>()).add(endpoint);
            }
        }
        for (var entry : loads.entrySet()) {
            if (!drivers.containsKey(entry.getKey()) && !bidirectional.contains(entry.getKey())) {
                diagnostics.add(object("severity", "warning", "kind", "floating_input", "connection", entry.getKey(), "pins", entry.getValue()));
            }
        }
        for (var entry : drivers.entrySet()) {
            if (entry.getValue().size() > 1) diagnostics.add(object("severity", "warning", "kind", "multiple_drivers",
                    "connection", entry.getKey(), "pins", entry.getValue()));
        }
        String model = "not_run";
        if (reopen) {
            XmlDocument reloaded = XmlDocument.load(project.data, project.path, workspace.root);
            if (!project.inspect(name).equals(Workspace.inspect(reloaded, name, project.revision))) {
                throw fault(FORMAT, "Native reopen produced a different circuit", "circuit", name);
            }
            model = "passed";
        }
        return object("circuit", name, "valid", diagnostics.stream().noneMatch(item -> item.get("severity").equals("error")),
                "revision", project.revision, "diagnostics", diagnostics, "native_reopen", model, "original_program_reopen", "not_run");
    }

    private Validate() { }
}
