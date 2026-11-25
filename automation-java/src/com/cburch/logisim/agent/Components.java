// SPDX-License-Identifier: GPL-3.0-only
package com.cburch.logisim.agent;

import com.cburch.logisim.comp.Component;
import com.cburch.logisim.Main;
import com.cburch.logisim.comp.ComponentFactory;
import com.cburch.logisim.data.Attribute;
import com.cburch.logisim.data.AttributeSet;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.instance.Instance;
import com.cburch.logisim.tools.AddTool;
import com.cburch.logisim.tools.Library;
import com.cburch.logisim.tools.Tool;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.w3c.dom.Element;

import java.util.*;

import static com.cburch.logisim.agent.Rpc.*;

final class Components {
    static final class Choice {
        final Library library;
        final AddTool tool;

        Choice(Library library, AddTool tool) { this.library = library; this.tool = tool; }
        String category() { return library.getName(); }
        String name() { return tool.getFactory().getName(); }
        String qualified() { return category() + ":" + name(); }
    }

    static List<Choice> catalog(XmlDocument document) {
        Map<String, Library> libraries = new LinkedHashMap<>();
        for (Library library : new NativeCore.StrictLoader().getBuiltin().getLibraries()) libraries.put(library.getName(), library);
        if (document != null) {
            for (Library library : document.core.getLibraries()) libraries.put(library.getName(), library);
            libraries.put(document.core.getName(), document.core);
        }
        List<Choice> results = new ArrayList<>();
        for (Library library : libraries.values()) {
            for (Tool tool : library.getTools()) {
                if (tool instanceof AddTool && ((AddTool) tool).getFactory() != null) results.add(new Choice(library, (AddTool) tool));
            }
        }
        results.sort(Comparator.comparing(Choice::qualified));
        return results;
    }

    static Choice find(XmlDocument file, String name) {
        List<Choice> matches = new ArrayList<>();
        for (Choice choice : catalog(file)) {
            if (name.equals(choice.qualified()) || name.equals(choice.name()) || name.equals(choice.tool.getName())) matches.add(choice);
        }
        if (matches.size() == 1) return matches.get(0);
        if (matches.isEmpty()) throw fault(MISSING, "Component factory does not exist", "factory", name);
        throw fault(CONFLICT, "Factory is ambiguous; use category:factory", "factory", name,
                "matches", matches.stream().map(Choice::qualified).toArray(String[]::new));
    }

    static Map<String, Object> describe(Choice choice, Component component) {
        List<Map<String, Object>> attributes = new ArrayList<>();
        for (Attribute<?> attr : component.getAttributeSet().getAttributes()) {
            attributes.add(object("name", attr.getName(), "default", standard(component.getAttributeSet(), attr),
                    "read_only", component.getAttributeSet().isReadOnly(attr)));
        }
        return object("factory", choice.name(), "category", choice.category(), "qualified", choice.qualified(),
                "attributes", attributes, "pins", ports(component), "bounds", bounds(component.getBounds()));
    }

    static Component create(Choice choice, Location location, Map<String, String> attrs, XmlDocument project) {
        try {
            return NativeCore.create(choice.tool, location, attrs, project == null ? Main.VERSION : project.version());
        } catch (IllegalArgumentException error) {
            throw fault(INVALID, "Component attributes are invalid", "factory", choice.qualified(), "diagnostic", error.getMessage());
        }
    }

    static Map<String, String> requested(JsonObject params) {
        Map<String, String> values = new LinkedHashMap<>();
        for (var entry : fields(params, "attrs").entrySet()) {
            JsonElement value = entry.getValue();
            if (!value.isJsonPrimitive()) throw fault(INVALID, "Attribute value must be a string, number or boolean", "attribute", entry.getKey());
            values.put(entry.getKey(), value.getAsString());
        }
        for (String key : List.of("facing", "label")) if (params.has(key)) values.put(key, text(params, key));
        return values;
    }

    static List<Map<String, Object>> ports(Component comp) {
        List<Map<String, Object>> results = new ArrayList<>();
        Instance instance = Instance.getInstanceFor(comp);
        for (int i = 0; i < comp.getEnds().size(); i++) {
            Map<String, Object> port = Geometry.port(comp.getEnd(i), i);
            if (instance != null && i < instance.getPorts().size()) port.put("description", instance.getPorts().get(i).getToolTip());
            results.add(port);
        }
        return results;
    }

    static Map<String, Object> item(Component comp, Element raw) {
        Map<String, String> attrs = standards(comp.getAttributeSet());
        Map<String, String> unknown = XmlDocument.attrs(raw);
        unknown.keySet().removeAll(attrs.keySet());
        return object("id", XmlDocument.id(raw), "factory", raw.getAttribute("name"), "library_id", raw.getAttribute("lib"),
                "position", Geometry.position(comp.getLocation()), "attrs", attrs, "pins", ports(comp),
                "label", attrs.get("label"), "facing", attrs.get("facing"), "bounds", bounds(comp.getBounds()),
                "unknown_attributes", unknown);
    }

    static Map<String, Integer> bounds(com.cburch.logisim.data.Bounds bounds) {
        return Map.of("x", bounds.getX(), "y", bounds.getY(), "width", bounds.getWidth(), "height", bounds.getHeight());
    }

    static Map<String, String> standards(AttributeSet set) {
        Map<String, String> values = new LinkedHashMap<>();
        for (Attribute<?> attribute : set.getAttributes()) values.put(attribute.getName(), standard(set, attribute));
        return values;
    }

    static <T> String standard(AttributeSet set, Attribute<T> attribute) {
        T value = set.getValue(attribute);
        return value == null ? null : attribute.toStandardString(value);
    }

    static boolean snap(ComponentFactory factory, AttributeSet attrs) {
        return !Boolean.FALSE.equals(factory.getFeature(ComponentFactory.SHOULD_SNAP, attrs));
    }

    static String label(Component component) {
        return component.getAttributeSet().containsAttribute(StdAttr.LABEL)
                ? component.getAttributeSet().getValue(StdAttr.LABEL) : null;
    }

    private Components() { }
}
