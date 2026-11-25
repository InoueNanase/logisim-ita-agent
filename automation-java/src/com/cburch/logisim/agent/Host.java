// SPDX-License-Identifier: GPL-3.0-only
package com.cburch.logisim.agent;

import com.cburch.logisim.data.Location;
import com.google.gson.*;
import com.google.gson.internal.Streams;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

import static com.cburch.logisim.agent.Rpc.*;

public final class Host {
    private final Workspace workspace;

    private Host(Path root) throws IOException { workspace = new Workspace(root); }

    public static void main(String[] args) throws IOException {
        PrintStream output = System.out;
        System.setOut(System.err);
        Host host = new Host(Path.of(args.length > 0 ? args[0] : "."));
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8), true);
        for (String line; (line = input.readLine()) != null;) {
            JsonElement response = host.line(line);
            if (response != null) writer.println(JSON.toJson(response));
        }
    }

    private JsonElement line(String line) {
        JsonElement parsed;
        try {
            JsonReader reader = new JsonReader(new StringReader(line));
            reader.setLenient(false);
            if (reader.peek() == JsonToken.END_DOCUMENT) throw new IOException("Empty message");
            parsed = Streams.parse(reader);
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("Trailing data");
        } catch (IOException | JsonParseException error) {
            return failure(JsonNull.INSTANCE, -32700, "Invalid JSON", object("diagnostic", error.getMessage()));
        }
        if (!parsed.isJsonArray()) return request(parsed);
        if (parsed.getAsJsonArray().size() == 0) return failure(JsonNull.INSTANCE, -32600, "Invalid empty batch", Map.of());
        JsonArray result = new JsonArray();
        for (JsonElement item : parsed.getAsJsonArray()) {
            JsonElement reply = request(item);
            if (reply != null) result.add(reply);
        }
        return result.size() == 0 ? null : result;
    }

    private JsonElement request(JsonElement message) {
        if (!message.isJsonObject()) return failure(JsonNull.INSTANCE, -32600, "Invalid request", Map.of());
        JsonObject request = message.getAsJsonObject();
        JsonElement id = request.has("id") ? request.get("id") : JsonNull.INSTANCE;
        if ((!id.isJsonNull() && (!id.isJsonPrimitive() || id.getAsJsonPrimitive().isBoolean()))
                || !new JsonPrimitive("2.0").equals(request.get("jsonrpc"))
                || !request.has("method") || !request.get("method").isJsonPrimitive()
                || !request.get("method").getAsJsonPrimitive().isString()) {
            return failure(JsonNull.INSTANCE, -32600, "Invalid JSON-RPC request envelope", Map.of());
        }
        boolean notification = !request.has("id");
        try {
            Object result = dispatch(request.get("method").getAsString(), fields(request, "params"));
            return notification ? null : JSON.toJsonTree(object("jsonrpc", "2.0", "id", id, "result", result));
        } catch (Fault error) {
            return notification ? null : failure(id, error.code, error.getMessage(), error.details);
        } catch (IOException error) {
            return notification ? null : failure(id, IO, "File operation failed", object("diagnostic", error.toString()));
        } catch (IllegalArgumentException error) {
            return notification ? null : failure(id, INVALID, "Invalid value", object("diagnostic", error.getMessage()));
        } catch (RuntimeException error) {
            error.printStackTrace(System.err);
            return notification ? null : failure(id, -32603, "Internal error", object("diagnostic", error.toString()));
        }
    }

    private static JsonElement failure(JsonElement id, int code, String message, Object details) {
        boolean recoverable = code != -32603;
        return JSON.toJsonTree(object("jsonrpc", "2.0", "id", id, "error", object("code", code, "message", message,
                "details", details, "recoverable", recoverable, "data", object("details", details, "recoverable", recoverable))));
    }

    private Object dispatch(String method, JsonObject params) throws IOException {
        switch (method) {
            case "logisim.get_capabilities":
                return object("protocol_version", "2.0", "engine_version", NativeCore.version(), "grid_step", Geometry.GRID,
                        "max_bit_width", 32, "image_formats", List.of("png"), "factory_catalog", "logisim.list_component_factories",
                        "workspace", workspace.root.toString());
            case "logisim.open_project": return workspace.open(params, false).summary();
            case "logisim.create_project": return workspace.open(params, true).summary();
            case "logisim.close_project": return workspace.close(params);
            case "logisim.list_component_factories": {
                XmlDocument document = params.has("project_id") || params.has("project") ? workspace.project(params).document : null;
                String category = text(params, "category", null);
                List<Object> catalog = new ArrayList<>();
                for (Components.Choice choice : Components.catalog(document)) {
                    if (category == null || choice.category().equals(category)) {
                        catalog.add(object("factory", choice.name(), "category", choice.category(), "qualified", choice.qualified()));
                    }
                }
                return catalog;
            }
            case "logisim.describe_component": {
                XmlDocument document = params.has("project_id") || params.has("project") ? workspace.project(params).document : null;
                Components.Choice choice = Components.find(document, text(params, "factory"));
                return Components.describe(choice, Components.create(choice, Location.create(0, 0), Components.requested(params), document));
            }
            case "logisim.list_circuits": return workspace.project(params).circuits();
            case "logisim.create_circuit": return Edits.addCircuit(workspace.project(params), params);
            case "logisim.get_circuit": {
                Workspace.Session project = workspace.project(params);
                return project.inspect(project.circuitName(params));
            }
            case "logisim.save": return workspace.project(params).save(params);
            case "logisim.validate": {
                Workspace.Session project = workspace.project(params);
                return Validate.circuit(workspace, project, project.circuitName(params), bool(params, "reopen", true));
            }
            case "logisim.undo": return workspace.project(params).history(params, true);
            case "logisim.redo": return workspace.project(params).history(params, false);
            case "logisim.add_component": return Edits.add(workspace.project(params), params);
            case "logisim.update_component":
            case "logisim.set_attribute": return Edits.update(workspace.project(params), params);
            case "logisim.move_component": {
                JsonObject moved = params.deepCopy();
                JsonObject position = new JsonObject();
                if (params.has("x")) position.add("x", params.get("x"));
                if (params.has("y")) position.add("y", params.get("y"));
                moved.add("position", position);
                return Edits.update(workspace.project(params), moved);
            }
            case "logisim.remove_component": return Edits.remove(workspace.project(params), params);
            case "logisim.connect": return Edits.connect(workspace.project(params), params);
            case "logisim.disconnect": return Edits.disconnect(workspace.project(params), params);
            case "logisim.set_input": return Simulation.setInput(io(params), params);
            case "logisim.simulate": return Simulation.simulate(io(params), params);
            case "logisim.clock_step": return Simulation.clockStep(io(params), params);
            case "logisim.run_vectors": return Simulation.runVectors(io(params), params);
            case "logisim.export_circuit_image": return Images.image(workspace, io(params), params);
            case "logisim.export_state_sheet": return Images.sheet(workspace, io(params), params);
            case "logisim.reset_simulation": {
                Workspace.Session project = workspace.project(params);
                String name = project.circuitName(params);
                project.states.remove(name);
                project.pendingInputs.remove(name);
                return Simulation.simulate(new Simulation.CircuitIO(project, name), params);
            }
            default: throw fault(-32601, "Method not found", "method", method);
        }
    }

    private Simulation.CircuitIO io(JsonObject params) {
        Workspace.Session project = workspace.project(params);
        return new Simulation.CircuitIO(project, project.circuitName(params));
    }
}
