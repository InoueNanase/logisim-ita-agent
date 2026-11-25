// SPDX-License-Identifier: GPL-3.0-only
package com.cburch.logisim.agent;

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.CircuitState;
import com.cburch.logisim.circuit.Wire;
import com.cburch.logisim.proj.Project;
import com.google.gson.JsonObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import static com.cburch.logisim.agent.Rpc.*;

final class Workspace {
    final Path root;
    private final Map<String, Session> sessions = new LinkedHashMap<>();

    Workspace(Path root) throws IOException { this.root = root.toRealPath(); }

    Path path(String value) throws IOException {
        if (value.isBlank()) throw fault(INVALID, "Path cannot be blank", "path", value);
        Path requested = root.resolve(value).normalize();
        Path existing = requested;
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) existing = existing.getParent();
        if (existing == null || !requested.startsWith(root)) throw fault(INVALID, "Path is outside the workspace", "path", value);
        Path resolved = existing.toRealPath().resolve(existing.relativize(requested));
        if (!resolved.startsWith(root)) throw fault(INVALID, "Path resolves outside the workspace", "path", value);
        return resolved;
    }

    Session open(JsonObject params, boolean create) throws IOException {
        Path path = path(text(params, "path"));
        if (!path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".circ")) {
            throw fault(INVALID, "Project path must end in .circ", "path", path.toString());
        }
        String mode = text(params, "mode", "read_write");
        if (!Set.of("read_write", "read_only").contains(mode) || (create && mode.equals("read_only"))) {
            throw fault(INVALID, "Invalid project access mode", "mode", mode);
        }
        for (Session existing : sessions.values()) {
            if (existing.path.equals(path)) throw fault(CONFLICT, "Project is already open", "project_id", existing.id);
        }
        if (create && Files.exists(path)) throw fault(CONFLICT, "Project file already exists", "path", path.toString());
        if (!create && !Files.isRegularFile(path)) throw fault(MISSING, "Project file does not exist", "path", path.toString());
        byte[] data;
        if (create) {
            String name = text(params, "top_circuit", "main");
            if (name.isBlank() || name.length() > 120) throw fault(INVALID, "Invalid top circuit name", "name", name);
            data = XmlDocument.nativeBytes(NativeCore.create(name));
        } else data = XmlDocument.read(path);
        Session session = new Session(path, mode, data, create ? null : data);
        sessions.put(session.id, session);
        return session;
    }

    Session project(JsonObject params) {
        String id = params.has("project_id") ? text(params, "project_id") : text(params, "project");
        Session session = sessions.get(id);
        if (session == null) throw fault(MISSING, "Project is not open", "project_id", id);
        return session;
    }

    Map<String, Object> close(JsonObject params) {
        Session session = project(params);
        if (session.dirty() && !bool(params, "discard", false)) {
            throw fault(CONFLICT, "Project has unsaved edits", "project_id", session.id, "revision", session.revision);
        }
        sessions.remove(session.id);
        return object("closed", session.id);
    }

    final class Session {
        final String id = UUID.randomUUID().toString();
        final Path path;
        final String mode;
        final Deque<byte[]> undo = new ArrayDeque<>();
        final Deque<byte[]> redo = new ArrayDeque<>();
        final Map<String, CircuitState> states = new HashMap<>();
        final Set<String> pendingInputs = new HashSet<>();
        byte[] data;
        byte[] saved;
        XmlDocument document;
        Project project;
        int revision;

        Session(Path path, String mode, byte[] data, byte[] saved) {
            this.path = path;
            this.mode = mode;
            this.saved = saved;
            install(data, XmlDocument.load(data, path, root));
        }

        void install(byte[] candidate, XmlDocument parsed) {
            data = candidate;
            document = parsed;
            project = null;
            states.clear();
            pendingInputs.clear();
        }

        boolean dirty() { return !Arrays.equals(data, saved); }

        List<Map<String, Object>> libraries() {
            List<Map<String, Object>> result = new ArrayList<>();
            for (Element library : XmlDocument.children(document.xml.getDocumentElement(), "lib")) {
                result.add(object("id", library.getAttribute("name"), "descriptor", library.getAttribute("desc")));
            }
            return result;
        }

        Map<String, Object> summary() {
            Element xml = document.xml.getDocumentElement();
            return object("project_id", id, "path", path.toString(), "mode", mode, "revision", revision,
                    "dirty", dirty(), "source_version", xml.getAttribute("source"), "format_version", xml.getAttribute("version"),
                    "engine_version", NativeCore.version(), "top_circuit", document.core.getMainCircuit().getName(),
                    "circuits", circuits(), "libraries", libraries(), "warnings", List.of());
        }

        List<String> circuits() {
            List<String> result = new ArrayList<>();
            for (Circuit circuit : document.core.getCircuits()) result.add(circuit.getName());
            return result;
        }

        String circuitName(JsonObject params) { return text(params, "circuit", document.core.getMainCircuit().getName()); }

        Circuit circuit(String name) {
            Circuit result = document.core.getCircuit(name);
            if (result == null) throw fault(MISSING, "Circuit does not exist", "circuit", name);
            return result;
        }

        CircuitState state(String name) {
            Circuit nativeCircuit = circuit(name);
            return states.computeIfAbsent(name, key -> NativeCore.state(project(), nativeCircuit));
        }

        private Project project() {
            if (project == null) project = NativeCore.project(document.core);
            return project;
        }

        CircuitState freshState(String name) { return NativeCore.state(project(), circuit(name)); }

        void checkWrite(JsonObject params) {
            if (!mode.equals("read_write")) throw fault(READ_ONLY, "Project is read-only", "project_id", id);
            if (params.has("if_revision")) {
                int expected = integer(params, "if_revision", 0, Integer.MAX_VALUE);
                if (revision != expected) throw fault(CONFLICT, "Project revision changed", "expected", expected, "actual", revision);
            }
        }

        Map<String, Object> save(JsonObject params) throws IOException {
            checkWrite(params);
            byte[] disk = Files.exists(path) ? XmlDocument.read(path) : null;
            if (!Arrays.equals(disk, saved)) throw fault(CONFLICT, "Project file changed since open or save", "path", path.toString());
            if (dirty()) {
                atomicWrite(path, data);
                saved = data;
            }
            return object("path", path.toString(), "revision", revision, "source_version",
                    document.xml.getDocumentElement().getAttribute("source"), "compatibility", "native_model_loaded");
        }

        Map<String, Object> history(JsonObject params, boolean backward) {
            checkWrite(params);
            Deque<byte[]> from = backward ? undo : redo;
            Deque<byte[]> to = backward ? redo : undo;
            if (from.isEmpty()) throw fault(CONFLICT, "No edits available", "direction", backward ? "undo" : "redo");
            byte[] candidate = from.peek();
            XmlDocument parsed = XmlDocument.load(candidate, path, root);
            to.push(data);
            from.pop();
            install(candidate, parsed);
            revision++;
            return object("revision", revision, "dirty", dirty());
        }

        Map<String, Object> edit(JsonObject params, BiFunction<Circuit, Element, Map<String, Object>> change,
                                 Consumer<XmlDocument> validate) {
            checkWrite(params);
            String name = circuitName(params);
            Circuit before = circuit(name);
            Document candidate = (Document) document.xml.cloneNode(true);
            Map<String, Object> result = change.apply(before, XmlDocument.circuit(candidate, name));
            byte[] bytes = XmlDocument.bytes(candidate);
            XmlDocument parsed = XmlDocument.load(bytes, path, root);
            Circuit after = parsed.core.getCircuit(name);
            if (after == null) throw fault(FORMAT, "Edited circuit was not retained", "circuit", name);
            if (Validate.widthConflicts(after).size() > Validate.widthConflicts(before).size()) {
                throw fault(CONFLICT, "Edit introduces a native width conflict", "circuit", name,
                        "count", Validate.widthConflicts(after).size());
            }
            Geometry.Graph nextGraph = new Geometry.Graph(after, XmlDocument.circuit(parsed.xml, name));
            if (nextGraph.nets.values().stream().anyMatch(net -> net.widths.size() > 1)) {
                Geometry.Graph oldGraph = new Geometry.Graph(before, XmlDocument.circuit(document.xml, name));
                for (Geometry.Net net : nextGraph.nets.values()) {
                    Geometry.Net old = oldGraph.nets.get(net.id);
                    if (net.widths.size() > 1 && (old == null || !old.widths.equals(net.widths))) {
                        throw fault(CONFLICT, "Edit conflicts with a declared net width", "net_id", net.id, "widths", net.widths);
                    }
                }
            }
            validate.accept(parsed);
            undo.push(data);
            if (undo.size() > 32) undo.removeLast();
            redo.clear();
            install(bytes, parsed);
            revision++;
            result.put("revision", revision);
            return result;
        }

        Map<String, Object> inspect(String name) { return Workspace.inspect(document, name, revision); }
    }

    static Map<String, Object> inspect(XmlDocument project, String name, int revision) {
        Element source = XmlDocument.circuit(project.xml, name);
        Circuit circuit = project.core.getCircuit(name);
        Geometry.Graph graph = new Geometry.Graph(circuit, source);
        List<Map<String, Object>> components = new ArrayList<>();
        for (Element item : XmlDocument.children(source, "comp")) components.add(Components.item(XmlDocument.component(circuit, item), item));
        List<Map<String, Object>> nets = new ArrayList<>();
        for (Geometry.Net net : graph.nets.values()) nets.add(net.describe());
        return object("name", name, "revision", revision, "grid_step", Geometry.GRID, "bounds", Components.bounds(circuit.getBounds()),
                "attrs", XmlDocument.attrs(source), "components", components, "wires", graph.wires(), "nets", nets,
                "junctions", graph.junctions(circuit));
    }

    static Element component(Element circuit, String id) {
        Element found = null;
        for (Element item : XmlDocument.children(circuit, "comp")) {
            if (XmlDocument.id(item).equals(id)) {
                if (found != null) throw fault(CONFLICT, "Non-unique component ID", "id", id);
                found = item;
            }
        }
        if (found == null) throw fault(MISSING, "Component does not exist", "id", id);
        return found;
    }

    static void atomicWrite(Path destination, byte[] data) throws IOException {
        Files.createDirectories(destination.getParent());
        Path stage = Files.createTempFile(destination.getParent(), ".logisim-", ".tmp");
        try {
            Files.write(stage, data);
            Files.move(stage, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(stage);
        }
    }
}
