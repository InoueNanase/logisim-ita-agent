// SPDX-License-Identifier: GPL-3.0-only
package com.cburch.logisim.file;

import java.io.IOException;
import java.io.OutputStream;
import java.io.File;
import com.cburch.logisim.tools.Library;

/** Same-package access to LogisimFile's native writer without GUI save dialogs. */
public final class AgentFileIO {
    private AgentFileIO() { }

    public static void write(LogisimFile file, OutputStream output) throws IOException {
        file.write(output, file.getLoader());
    }

    public static Library loadRelative(Loader loader, File fallback, String name) {
        File file = new File(name);
        File current = loader.getCurrentDirectory();
        if (!file.isAbsolute()) file = new File(current == null ? fallback : current, name);
        return loader.loadLogisimLibrary(file);
    }
}
