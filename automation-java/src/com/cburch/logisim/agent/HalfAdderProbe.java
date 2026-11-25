// SPDX-License-Identifier: GPL-3.0-only
package com.cburch.logisim.agent;

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.CircuitMutation;
import com.cburch.logisim.circuit.CircuitState;
import com.cburch.logisim.circuit.Wire;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.comp.EndData;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.data.Value;
import com.cburch.logisim.file.AgentFileIO;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.proj.Project;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class HalfAdderProbe {
    public static void main(String[] args) throws Exception {
        Path target = Paths.get(args[0]);
        Files.createDirectories(target);
        System.out.println("engine=" + NativeCore.version());
        if (args.length > 1) {
            for (int i = 1; i < args.length; i++) baseline(Paths.get(args[i]), target);
            return;
        }

        LogisimFile file = NativeCore.create("HalfAdder");
        Project project = NativeCore.project(file);
        Circuit circuit = file.getMainCircuit();
        Component a = pin(file, 80, 120, "A", false);
        Component b = pin(file, 80, 240, "B", false);
        Component s = pin(file, 380, 140, "S", true);
        Component c = pin(file, 380, 220, "C", true);
        Component xor = gate(file, 300, 140, "XOR Gate");
        Component and = gate(file, 300, 220, "AND Gate");
        List<Location> xorIn = inputs(xor);
        List<Location> andIn = inputs(and);
        CircuitMutation edit = new CircuitMutation(circuit);
        edit.addAll(List.of(a, b, xor, and, s, c));
        branch(edit, a.getLocation(), 140, xorIn.get(0), andIn.get(0));
        branch(edit, b.getLocation(), 190, xorIn.get(1), andIn.get(1));
        wire(edit, xor.getLocation(), s.getLocation());
        wire(edit, and.getLocation(), c.getLocation());
        edit.execute(project);
        CircuitState state = verify("created", project, circuit);

        Path path = target.resolve("half-adder.circ");
        try (OutputStream out = Files.newOutputStream(path)) { AgentFileIO.write(file, out); }
        LogisimFile reopened = open(path);
        verify("reopened", NativeCore.project(reopened), reopened.getMainCircuit());
        if (reopened.getMainCircuit().getComponents().size() != circuit.getComponents().size()) {
            throw new AssertionError("Component or wire count changed on reopen");
        }

        BufferedImage image = NativeImage.draw(circuit, state, 3, 24, Color.WHITE, false, true);
        if (!ImageIO.write(image, "PNG", target.resolve("half-adder.png").toFile())) {
            throw new AssertionError("PNG encoder missing");
        }
        System.out.printf("renderer=Circuit.draw width=%d height=%d%n", image.getWidth(), image.getHeight());
    }

    private static void baseline(Path path, Path target) throws Exception {
        LogisimFile original = open(path);
        Path saved = target.resolve(path.getFileName());
        try (OutputStream out = Files.newOutputStream(saved)) { AgentFileIO.write(original, out); }
        LogisimFile reopen = open(saved);
        if (original.getCircuitCount() != reopen.getCircuitCount()) throw new AssertionError("Circuit count changed");
        for (Circuit circuit : original.getCircuits()) {
            Circuit other = reopen.getCircuit(circuit.getName());
            if (other == null || circuit.getNonWires().size() != other.getNonWires().size()
                    || !circuit.getWires().containsAll(other.getWires())
                    || circuit.getWires().size() != other.getWires().size()) {
                throw new AssertionError("Circuit geometry changed: " + path + " / " + circuit.getName());
            }
        }
        System.out.printf("roundtrip=%s circuits=%d%n", path.getFileName(), reopen.getCircuitCount());
    }

    private static LogisimFile open(Path path) throws Exception {
        NativeCore.StrictLoader loader = new NativeCore.StrictLoader();
        LogisimFile file = loader.openLogisimFile(path.toFile());
        loader.check();
        return file;
    }

    private static Component pin(LogisimFile file, int x, int y, String label, boolean output) {
        return NativeCore.create(NativeCore.find(file, "Wiring", "Pin"), Location.create(x, y),
                Map.of("label", label, "output", Boolean.toString(output), "tristate", "false",
                        "facing", output ? "west" : "east", "labelloc", output ? "east" : "west"));
    }

    private static Component gate(LogisimFile file, int x, int y, String name) {
        return NativeCore.create(NativeCore.find(file, "Gates", name), Location.create(x, y),
                Map.of("facing", "east", "inputs", "2"));
    }

    private static List<Location> inputs(Component gate) {
        List<Location> ends = gate.getEnds().stream().filter(EndData::isInput).map(EndData::getLocation)
                .sorted(Comparator.comparingInt(Location::getY)).collect(Collectors.toList());
        if (ends.size() != 2) throw new AssertionError("Expected two gate inputs: " + ends);
        return ends;
    }

    private static void branch(CircuitMutation edit, Location pin, int x, Location first, Location second) {
        Location start = Location.create(x, pin.getY());
        Location upper = Location.create(x, first.getY());
        Location lower = Location.create(x, second.getY());
        wire(edit, pin, start);
        if (pin.getY() < first.getY()) wire(edit, start, upper);
        else if (pin.getY() > second.getY()) wire(edit, start, lower);
        else throw new AssertionError("Expected pin outside branch range");
        wire(edit, upper, lower);
        wire(edit, upper, first);
        wire(edit, lower, second);
    }

    private static void wire(CircuitMutation edit, Location from, Location to) {
        if (from.equals(to)) return;
        if (from.getX() != to.getX() && from.getY() != to.getY()) throw new AssertionError("Diagonal probe wire");
        edit.add(Wire.create(from, to));
    }

    private static CircuitState verify(String phase, Project project, Circuit circuit) {
        Map<String, Component> pins = circuit.getNonWires().stream()
                .filter(item -> item.getFactory().getName().equals("Pin"))
                .collect(Collectors.toMap(item -> item.getAttributeSet().getValue(StdAttr.LABEL), item -> item));
        CircuitState state = NativeCore.state(project, circuit);
        for (int a = 0; a < 2; a++) {
            for (int b = 0; b < 2; b++) {
                NativeCore.input(state, pins.get("A"), a == 1 ? Value.TRUE : Value.FALSE);
                NativeCore.input(state, pins.get("B"), b == 1 ? Value.TRUE : Value.FALSE);
                state.getPropagator().propagate();
                Value sum = NativeCore.output(state, pins.get("S"));
                Value carry = NativeCore.output(state, pins.get("C"));
                System.out.printf("%s,%d,%d,%s,%s%n", phase, a, b, sum, carry);
                if (state.getPropagator().isOscillating() || !sum.equals((a ^ b) == 1 ? Value.TRUE : Value.FALSE)
                        || !carry.equals((a & b) == 1 ? Value.TRUE : Value.FALSE)) {
                    throw new AssertionError("Half-adder truth table mismatch");
                }
            }
        }
        return state;
    }
}
