// SPDX-License-Identifier: GPL-3.0-only
package com.cburch.logisim.agent;

import com.cburch.logisim.circuit.CircuitState;
import com.google.gson.JsonObject;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadataNode;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static com.cburch.logisim.agent.Rpc.*;

final class Images {
    private static final long MAX_SHEET_PIXELS = 80_000_000;

    private static final class Options {
        final double scale;
        final String background;
        final Color color;
        final int padding;
        final int dpi;
        final boolean grid;
        final boolean includeState;

        Options(JsonObject params) {
            if (!"png".equals(text(params, "format", "png"))) throw fault(INVALID, "Only PNG export is supported");
            if (!"content".equals(text(params, "fit", "content"))) throw fault(INVALID, "Only content fitting is supported");
            var raw = params.get("scale");
            if (raw != null && (!raw.isJsonPrimitive() || !raw.getAsJsonPrimitive().isNumber())) {
                throw fault(INVALID, "Scale must be a number", "parameter", "scale");
            }
            scale = raw == null ? 2 : raw.getAsDouble();
            if (!Double.isFinite(scale) || scale < 0.25 || scale > 16) {
                throw fault(LIMIT, "Scale must be in the range 0.25 to 16", "scale", raw.getAsString());
            }
            background = text(params, "background", "white");
            if (background.equals("white")) color = Color.WHITE;
            else if (background.equals("transparent")) color = null;
            else if (background.matches("#[a-fA-F0-9]{6}")) color = Color.decode(background);
            else throw fault(INVALID, "Invalid background; use white, transparent or #RRGGBB", "background", background);
            padding = integer(params, "padding", 0, 256, 24);
            dpi = integer(params, "dpi", 72, 1200, 300);
            grid = bool(params, "show_grid", false);
            includeState = bool(params, "include_state", true);
        }

        Map<String, Object> metadata() {
            return object("format", "png", "renderer", "Circuit.draw", "scale", scale, "background", background,
                    "padding", padding, "dpi", dpi, "show_grid", grid, "include_state", includeState, "fit", "content");
        }

        BufferedImage draw(CircuitState state) {
            return NativeImage.draw(state.getCircuit(), state, scale, padding, color, grid, includeState);
        }
    }

    static Map<String, Object> image(Workspace workspace, Simulation.CircuitIO io, JsonObject params) throws IOException {
        Options options = new Options(params);
        Path target = workspace.path(text(params, "path"));
        if (!target.toString().toLowerCase(Locale.ROOT).endsWith(".png")) throw fault(INVALID, "Image path must end in .png");
        Path sidecar = workspace.path(target.toString() + ".json");
        if (target.equals(sidecar)) throw fault(CONFLICT, "Image and sidecar paths resolve to the same file");
        if (Files.isDirectory(target) || Files.isDirectory(sidecar)) throw fault(CONFLICT, "Image or sidecar path is a directory");
        CircuitState state = io.project.state(io.name);
        Simulation.simulate(io, new JsonObject());
        BufferedImage image = options.draw(state);
        Map<String, Object> result = metadata(io, image, options);
        result.put("path", target.getFileName().toString());
        result.put("sidecar", sidecar.getFileName().toString());
        result.put("label", text(params, "label", io.name));
        result.put("inputs", io.currentInputs(state));
        result.put("simulation", io.read(state, true));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        png(image, options.dpi, bytes);
        byte[] previous = Files.exists(target) ? Files.readAllBytes(target) : null;
        Workspace.atomicWrite(target, bytes.toByteArray());
        try {
            Workspace.atomicWrite(sidecar, json(result));
        } catch (IOException error) {
            try {
                if (previous == null) Files.deleteIfExists(target);
                else Workspace.atomicWrite(target, previous);
            } catch (IOException rollbackError) {
                error.addSuppressed(rollbackError);
            }
            throw error;
        }
        result.put("path", target.toString());
        result.put("sidecar", sidecar.toString());
        return result;
    }

    static Map<String, Object> sheet(Workspace workspace, Simulation.CircuitIO io, JsonObject params) throws IOException {
        Options options = new Options(params);
        Path target = workspace.path(text(params, "path"));
        if (Files.exists(target)) throw fault(CONFLICT, "State sheet destination already exists", "path", target.toString());
        boolean archive = target.toString().toLowerCase(Locale.ROOT).endsWith(".zip");
        String layout = text(params, "layout", "grid");
        if (!Set.of("grid", "list").contains(layout)) throw fault(INVALID, "Unknown state sheet layout", "layout", layout);
        boolean labels = bool(params, "labels", true);
        boolean independent = bool(params, "independent", true);
        List<Simulation.Vector> vectors = Simulation.vectors(io, array(params, "states"));
        Files.createDirectories(target.getParent());
        Path stage = Files.createTempDirectory(target.getParent(), ".logisim-states-");
        try {
            List<Map<String, Object>> images = new ArrayList<>();
            CircuitState state = null;
            long pixels = 0;
            boolean passed = true;
            for (Simulation.Vector vector : vectors) {
                if (state == null || vector.reset || independent) state = io.project.freshState(io.name);
                Map<String, Object> simulation = vector.execute(io, state);
                BufferedImage image = options.draw(state);
                pixels += (long) image.getWidth() * image.getHeight();
                if (pixels > MAX_SHEET_PIXELS) throw fault(LIMIT, "State sheet exceeds the total pixel budget", "limit", MAX_SHEET_PIXELS);
                String filename = String.format(Locale.ROOT, "state-%03d.png", images.size() + 1);
                try (OutputStream out = Files.newOutputStream(stage.resolve(filename))) { png(image, options.dpi, out); }
                Map<String, Object> entry = metadata(io, image, options);
                entry.put("image", filename);
                entry.put("sidecar", filename + ".json");
                entry.put("label", vector.label);
                entry.put("inputs", simulation.get("inputs"));
                entry.put("simulation", simulation);
                passed &= Boolean.TRUE.equals(simulation.get("passed"));
                Files.write(stage.resolve(filename + ".json"), json(entry));
                images.add(entry);
            }
            Map<String, Object> manifest = object("circuit", io.name, "revision", io.project.revision, "engine_version", NativeCore.version(),
                    "layout", layout, "independent", independent, "passed", passed, "images", images, "manifest", "manifest.json", "index", "index.html");
            Files.write(stage.resolve("manifest.json"), json(manifest));
            Files.writeString(stage.resolve("index.html"), index(io.name, layout, labels, images), StandardCharsets.UTF_8);
            if (archive) zip(stage, target);
            else Files.move(stage, target, StandardCopyOption.ATOMIC_MOVE);
            manifest.put("path", target.toString());
            return manifest;
        } finally {
            if (Files.exists(stage)) {
                try (var files = Files.list(stage)) {
                    for (Path file : files.collect(Collectors.toList())) Files.deleteIfExists(file);
                }
                Files.deleteIfExists(stage);
            }
        }
    }

    private static Map<String, Object> metadata(Simulation.CircuitIO io, BufferedImage image, Options options) {
        Map<String, Object> info = options.metadata();
        info.put("circuit", io.name);
        info.put("revision", io.project.revision);
        info.put("engine_version", NativeCore.version());
        info.put("width", image.getWidth());
        info.put("height", image.getHeight());
        return info;
    }

    private static void png(BufferedImage image, int dpi, OutputStream out) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("PNG").next();
        try (var encoded = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(encoded);
            var params = writer.getDefaultWriteParam();
            var metadata = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(image), params);
            String meters = Integer.toString((int) Math.round(dpi / 0.0254));
            IIOMetadataNode tree = new IIOMetadataNode("javax_imageio_png_1.0");
            IIOMetadataNode density = new IIOMetadataNode("pHYs");
            density.setAttribute("pixelsPerUnitXAxis", meters);
            density.setAttribute("pixelsPerUnitYAxis", meters);
            density.setAttribute("unitSpecifier", "meter");
            tree.appendChild(density);
            metadata.mergeTree(tree.getNodeName(), tree);
            writer.write(null, new IIOImage(image, null, metadata), params);
        } finally {
            writer.dispose();
        }
    }

    private static void zip(Path directory, Path target) throws IOException {
        Path stage = Files.createTempFile(directory.getParent(), ".logisim-sheet-", ".zip");
        try {
            try (var zip = new ZipOutputStream(Files.newOutputStream(stage)); var files = Files.list(directory)) {
                for (Path file : files.sorted().collect(Collectors.toList())) {
                    zip.putNextEntry(new ZipEntry(file.getFileName().toString()));
                    Files.copy(file, zip);
                    zip.closeEntry();
                }
            }
            Files.move(stage, target, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(stage);
        }
    }

    private static byte[] json(Object document) { return (JSON.toJson(document) + "\n").getBytes(StandardCharsets.UTF_8); }

    private static String escape(Object value) {
        return String.valueOf(value).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String index(String name, String layout, boolean labels, List<Map<String, Object>> images) {
        StringBuilder page = new StringBuilder("<!doctype html><html lang=\"en\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>")
                .append(escape(name)).append(" · Simulation states</title><style>body{font:16px system-ui;margin:2rem;color:#222;background:#fff}main{display:grid;gap:1.5rem;grid-template-columns:")
                .append(layout.equals("grid") ? "repeat(auto-fit,minmax(min(100%,25rem),1fr))" : "1fr")
                .append("}figure{margin:0}img{width:100%;height:auto;border:1px solid #ddd}figcaption{padding:.5rem 0}small{display:block}</style><body><h1>")
                .append(escape(name)).append("</h1><p><a href=\"manifest.json\">JSON manifest</a></p><main>");
        for (Map<String, Object> image : images) {
            String filename = (String) image.get("image");
            String label = escape(image.get("label"));
            page.append("<figure><a href=\"").append(filename).append("\"><img src=\"").append(filename)
                    .append("\" alt=\"").append(label).append("\"></a>");
            if (labels) {
                page.append("<figcaption>").append(label).append("<small>");
                List<String> summary = new ArrayList<>();
                Map<?, ?> inputs = (Map<?, ?>) image.get("inputs");
                for (var entry : inputs.entrySet()) {
                    var input = (Map<?, ?>) entry.getValue();
                    Object tag = input.get("label");
                    if (tag == null || tag.toString().isBlank()) tag = entry.getKey();
                    summary.add(escape(tag) + "=" + escape(((Map<?, ?>) input.get("value")).get("bits")));
                }
                page.append(String.join(", ", summary)).append("</small></figcaption>");
            }
            page.append("</figure>");
        }
        return page.append("</main></body></html>\n").toString();
    }

    private Images() { }
}
