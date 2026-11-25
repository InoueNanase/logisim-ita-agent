// SPDX-License-Identifier: GPL-3.0-only
package com.cburch.logisim.agent;

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.CircuitState;
import com.cburch.logisim.comp.ComponentDrawContext;
import com.cburch.logisim.data.Bounds;

import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Collections;

final class NativeImage {
    static final int MAX_DIMENSION = 8192;
    static final long MAX_PIXELS = 16_000_000;

    static BufferedImage draw(Circuit circuit, CircuitState state, double scale, int padding,
                              Color background, boolean grid, boolean showState) {
        if (!Double.isFinite(scale) || scale <= 0 || padding < 0) {
            throw new IllegalArgumentException("Invalid image scale or padding");
        }
        JPanel destination = new JPanel();
        Graphics2D measure = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics();
        Bounds bounds;
        try {
            hints(measure);
            bounds = circuit.getBounds(measure);
        } finally {
            measure.dispose();
        }
        int guard = Math.max(32, padding);
        int width = (int) Math.ceil((bounds.getWidth() + 2.0 * guard) * scale);
        int height = (int) Math.ceil((bounds.getHeight() + 2.0 * guard) * scale);
        checkSize(width, height);
        BufferedImage ink = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = ink.createGraphics();
        double xOrigin = bounds.getX() - (double) guard;
        double yOrigin = bounds.getY() - (double) guard;
        try {
            hints(graphics);
            graphics.setColor(Color.BLACK);
            graphics.scale(scale, scale);
            graphics.translate(-xOrigin, -yOrigin);
            ComponentDrawContext context = new ComponentDrawContext(destination, circuit, state,
                    graphics, graphics, !showState);
            context.setShowState(showState);
            context.setShowColor(showState);
            circuit.draw(context, Collections.emptySet());
        } finally {
            graphics.dispose();
        }
        int left = width, top = height, right = -1, bottom = -1;
        int[] pixels = ((DataBufferInt) ink.getRaster().getDataBuffer()).getData();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if ((pixels[y * width + x] >>> 24) != 0) {
                    left = Math.min(left, x);
                    top = Math.min(top, y);
                    right = Math.max(right, x);
                    bottom = Math.max(bottom, y);
                }
            }
        }
        if (right < 0) { left = top = right = bottom = 0; }
        if (left == 0 && right > 0 || top == 0 && bottom > 0 || right == width - 1 || bottom == height - 1) {
            throw new IllegalArgumentException("Native drawing reached the render guard");
        }
        int margin = (int) Math.ceil(padding * scale);
        int resultWidth = right - left + 1 + 2 * margin;
        int resultHeight = bottom - top + 1 + 2 * margin;
        checkSize(resultWidth, resultHeight);
        BufferedImage result = new BufferedImage(resultWidth, resultHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D out = result.createGraphics();
        try {
            if (background != null) {
                out.setColor(background);
                out.fillRect(0, 0, resultWidth, resultHeight);
            }
            if (grid) {
                GridOverlay.draw(out, resultWidth, resultHeight, scale,
                        margin - left - xOrigin * scale, margin - top - yOrigin * scale);
            }
            out.drawImage(ink, margin - left, margin - top, null);
        } finally {
            out.dispose();
        }
        return result;
    }

    private static void hints(Graphics2D graphics) {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    }

    private static void checkSize(int width, int height) {
        if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION
                || (long) width * height > MAX_PIXELS) {
            throw Rpc.fault(Rpc.LIMIT, "Image size exceeds limit", "width", width, "height", height,
                    "max_dimension", MAX_DIMENSION, "max_pixels", MAX_PIXELS);
        }
    }

    private NativeImage() { }
}
