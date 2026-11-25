// SPDX-License-Identifier: GPL-3.0-only
package com.cburch.logisim.agent;

import com.cburch.logisim.Main;
import com.cburch.logisim.LogisimVersion;
import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.CircuitState;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.comp.ComponentFactory;
import com.cburch.logisim.data.Attribute;
import com.cburch.logisim.data.AttributeSet;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.data.Value;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.std.wiring.Pin;
import com.cburch.logisim.tools.AddTool;
import com.cburch.logisim.tools.Library;
import com.cburch.logisim.tools.Tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class NativeCore {
    static class StrictLoader extends Loader {
        final List<String> errors = new ArrayList<>();

        StrictLoader() { super(null); }

        @Override public void showError(String error) { errors.add(error); }

        void check() {
            if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("\n", errors));
        }
    }

    static Project project(LogisimFile file) {
        Project project = new Project(file);
        // Use a synchronous CircuitState below. There is no GUI simulator work to schedule.
        project.getSimulator().shutDown();
        return project;
    }

    static LogisimFile create(String name) {
        StrictLoader loader = new StrictLoader();
        LogisimFile file = LogisimFile.createNew(loader);
        for (Library library : loader.getBuiltin().getLibraries()) {
            if (!file.getLibraries().contains(library)) file.addLibrary(library);
        }
        file.getMainCircuit().setName(name);
        return file;
    }

    static AddTool find(Library library, String name) {
        for (Tool tool : library.getTools()) {
            if (tool instanceof AddTool && tool.getName().equals(name)) return (AddTool) tool;
        }
        throw new IllegalArgumentException("Unknown component factory: " + name);
    }

    static AddTool find(LogisimFile file, String category, String name) {
        for (Library library : file.getLibraries()) {
            if (library.getName().equals(category)) return find(library, name);
        }
        throw new IllegalArgumentException("Unknown library: " + category);
    }

    static Component create(AddTool tool, Location location, Map<String, String> values) {
        return create(tool, location, values, Main.VERSION);
    }

    static Component create(AddTool tool, Location location, Map<String, String> values, LogisimVersion version) {
        ComponentFactory factory = tool.getFactory();
        return create(factory, factory.createAttributeSet(), location, values, version);
    }

    static Component create(ComponentFactory factory, AttributeSet attrs, Location location, Map<String, String> values,
                            LogisimVersion version) {
        boolean versionDefaults = !factory.isAllDefaultValues(attrs, version);
        List<String> remaining = new ArrayList<>(values.keySet());
        for (int i = 0; i < attrs.getAttributes().size(); i++) {
            Attribute<?> attr = attrs.getAttributes().get(i);
            String key = attr.getName();
            if (values.containsKey(key)) {
                set(attrs, attr, values.get(key));
                remaining.remove(key);
            } else if (versionDefaults) setDefault(factory, attrs, attr, version);
        }
        if (!remaining.isEmpty()) throw new IllegalArgumentException("Unknown attributes: " + remaining);
        return factory.createComponent(location, attrs);
    }

    @SuppressWarnings("unchecked")
    private static <T> void setDefault(ComponentFactory factory, AttributeSet attrs, Attribute<T> attribute, LogisimVersion version) {
        T defaultValue = (T) factory.getDefaultAttributeValue(attribute, version);
        if (defaultValue != null) attrs.setValue(attribute, defaultValue);
    }

    private static <T> void set(AttributeSet set, Attribute<T> attribute, String value) {
        T parsed = attribute.parse(value);
        if (parsed == null) throw new IllegalArgumentException("Invalid " + attribute.getName() + ": " + value);
        if (parsed instanceof Boolean && !value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
            throw new IllegalArgumentException("Invalid boolean " + attribute.getName() + ": " + value);
        }
        if (parsed instanceof com.cburch.logisim.data.BitWidth) {
            int width = ((com.cburch.logisim.data.BitWidth) parsed).getWidth();
            if (width < 1 || width > Value.MAX_WIDTH) throw new IllegalArgumentException("Invalid bit width: " + value);
        }
        set.setValue(attribute, parsed);
    }

    static CircuitState state(Project project, Circuit circuit) {
        CircuitState state = new CircuitState(project, circuit);
        state.getPropagator().propagate();
        return state;
    }

    static void input(CircuitState state, Component pin, Value value) {
        ((Pin) pin.getFactory()).setValue(state.getInstanceState(pin), value);
        state.markComponentAsDirty(pin);
    }

    static Value output(CircuitState state, Component pin) {
        return ((Pin) pin.getFactory()).getValue(state.getInstanceState(pin));
    }

    static String version() { return Main.VERSION_NAME; }

    private NativeCore() { }
}
