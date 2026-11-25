// SPDX-License-Identifier: GPL-3.0-only
package com.cburch.logisim.agent;

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.gui.main.Canvas;
import com.cburch.logisim.gui.main.Frame;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.util.StringGetter;

import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import javax.swing.ProgressMonitor;

/** Measures native drawable bounds or invokes Export Image below its file chooser. */
public final class OriginalImageProbe {
    public static void main(String[] args) {
        try {
            run(args);
            System.exit(0);
        } catch (Exception error) {
            error.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static void run(String[] args) throws Exception {
        var file = new Loader(null).openLogisimFile(new File(args[0]));
        Circuit circuit = file.getCircuit(args[1]);
        if (args.length == 2) {
            Graphics2D graphics = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics();
            try {
                graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                var bounds = circuit.getBounds(graphics);
                System.out.printf("bounds-origin=%d,%d%n", bounds.getX(), bounds.getY());
            } finally {
                graphics.dispose();
            }
            return;
        }
        Project project = new Project(file);
        project.getCircuitState(circuit).getPropagator().propagate();
        Canvas canvas = new Canvas(project) {
            @Override public Graphics getGraphics() {
                return new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).getGraphics();
            }
        };
        Class<?> action = Class.forName("com.cburch.logisim.gui.main.ExportImage");
        var pngFormat = action.getDeclaredField("FORMAT_PNG");
        pngFormat.setAccessible(true);
        Class<?> filterType = Class.forName(action.getName() + "$ImageFileFilter");
        Constructor<?> filter = filterType.getDeclaredConstructor(int.class, StringGetter.class, String[].class);
        filter.setAccessible(true);
        Class<?> task = Class.forName(action.getName() + "$ExportThread");
        Constructor<?> constructor = task.getDeclaredConstructor(Frame.class, Canvas.class, File.class, filterType,
                List.class, double.class, boolean.class, ProgressMonitor.class);
        constructor.setAccessible(true);
        Object instance = constructor.newInstance(null, canvas, new File(args[2]),
                filter.newInstance(pngFormat.getInt(null), null, new String[]{"png"}), List.of(circuit),
                Double.parseDouble(args[3]), true, new ProgressMonitor(null, "", "", 0, 1));
        Method export = task.getDeclaredMethod("export", Circuit.class);
        export.setAccessible(true);
        export.invoke(instance, circuit);
    }
}
