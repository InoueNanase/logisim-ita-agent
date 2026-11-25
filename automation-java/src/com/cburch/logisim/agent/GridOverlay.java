// SPDX-License-Identifier: GPL-3.0-only
package com.cburch.logisim.agent;

import com.cburch.logisim.gui.generic.GridPainter;

import javax.swing.ImageIcon;
import javax.swing.JPanel;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.awt.image.ImageProducer;

final class GridOverlay {
    // GridPainter accentuates every fifth intersection when zoomed out.
    private static final int MAJOR_INTERVAL = 5;

    static void draw(Graphics2D graphics, int width, int height, double scale, double originX, double originY) {
        double step = Geometry.GRID * scale;
        int halfCell = (int) Math.floor(step / 2);
        int minorCenter = (int) Math.round(scale * Geometry.GRID);
        int majorCenter = (int) Math.round(scale * (MAJOR_INTERVAL * Geometry.GRID));
        BufferedImage sample = sample(scale, majorCenter + halfCell + 1);
        int dotArea = 2 * halfCell + 1;
        BufferedImage minor = sample.getSubimage(minorCenter - halfCell, minorCenter - halfCell, dotArea, dotArea);
        // Use the interior sample for both when there is no accent. At high zooms
        // the fifth dot sits at the native tile seam, where the bitmap is rounded.
        BufferedImage major = sample.getRGB(0, 0) == sample.getRGB(minorCenter, minorCenter) ? minor
                : sample.getSubimage(majorCenter - halfCell, majorCenter - halfCell, dotArea, dotArea);

        // A rounded bitmap tile drifts at fractional zooms. Position each native dot
        // with the same zoom and translated circuit origin used to draw the circuit.
        int firstCol = (int) Math.floor(-originX / step);
        int lastCol = (int) Math.ceil((width - originX) / step);
        int firstRow = (int) Math.floor(-originY / step);
        int lastRow = (int) Math.ceil((height - originY) / step);
        for (int row = firstRow; row <= lastRow; row++) {
            int y = (int) Math.round(originY + (row * Geometry.GRID) * scale) - halfCell;
            for (int col = firstCol; col <= lastCol; col++) {
                int x = (int) Math.round(originX + (col * Geometry.GRID) * scale) - halfCell;
                BufferedImage dot = row % MAJOR_INTERVAL == 0 && col % MAJOR_INTERVAL == 0 ? major : minor;
                graphics.drawImage(dot, x, y, null);
            }
        }
    }

    private static BufferedImage sample(double scale, int size) {
        GridPainter painter = new GridPainter(new JPanel() {
            @Override public Image createImage(ImageProducer producer) {
                // Wait for the GUI painter's MemoryImageSource in the offscreen renderer.
                return new ImageIcon(super.createImage(producer)).getImage();
            }
        });
        painter.setZoomFactor(scale);
        BufferedImage sample = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = sample.createGraphics();
        try {
            graphics.setClip(0, 0, size, size);
            painter.paintGrid(graphics);
        } finally {
            graphics.dispose();
        }
        return sample;
    }

    private GridOverlay() { }
}
