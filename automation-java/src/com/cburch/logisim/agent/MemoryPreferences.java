// SPDX-License-Identifier: GPL-3.0-only
package com.cburch.logisim.agent;

import java.util.HashMap;
import java.util.Map;
import java.util.prefs.AbstractPreferences;
import java.util.prefs.Preferences;
import java.util.prefs.PreferencesFactory;

/** Isolates automation from desktop settings and registry writes. */
public final class MemoryPreferences implements PreferencesFactory {
    private final Preferences user = new Node(null, "");
    private final Preferences system = new Node(null, "");

    public Preferences userRoot() { return user; }
    public Preferences systemRoot() { return system; }

    private static final class Node extends AbstractPreferences {
        private final Map<String, String> values = new HashMap<>();
        private final Map<String, Node> children = new HashMap<>();

        Node(Node parent, String name) { super(parent, name); }
        protected void putSpi(String key, String value) { values.put(key, value); }
        protected String getSpi(String key) { return values.get(key); }
        protected void removeSpi(String key) { values.remove(key); }
        protected void removeNodeSpi() {
            Node parent = (Node) parent();
            if (parent != null) parent.children.remove(name());
        }
        protected String[] keysSpi() { return values.keySet().toArray(new String[0]); }
        protected String[] childrenNamesSpi() { return children.keySet().toArray(new String[0]); }
        protected AbstractPreferences childSpi(String name) {
            return children.computeIfAbsent(name, key -> new Node(this, key));
        }
        protected void syncSpi() { }
        protected void flushSpi() { }
    }
}
