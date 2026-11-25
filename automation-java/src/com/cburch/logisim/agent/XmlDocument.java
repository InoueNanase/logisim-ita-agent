// SPDX-License-Identifier: GPL-3.0-only
package com.cburch.logisim.agent;

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.LogisimVersion;
import com.cburch.logisim.Main;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.file.AgentFileIO;
import com.cburch.logisim.file.LogisimFile;
import org.w3c.dom.*;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import static com.cburch.logisim.agent.Rpc.*;

final class XmlDocument {
    static final long MAX_XML_BYTES = 8 * 1024 * 1024;
    static final String COMPONENT_ID = "agent-id";

    final Document xml;
    final LogisimFile core;
    final Path source;

    private XmlDocument(Document xml, LogisimFile core, Path source) {
        this.xml = xml;
        this.core = core;
        this.source = source;
    }

    LogisimVersion version() {
        String value = xml.getDocumentElement().getAttribute("source");
        return value.isEmpty() ? Main.VERSION : LogisimVersion.parse(value);
    }

    static XmlDocument load(byte[] original, Path source, Path root) {
        Document xml = parse(original);
        checkLibraries(xml, source, root, new LinkedHashSet<>());
        NativeCore.StrictLoader loader = new NativeCore.StrictLoader() {
            @Override public com.cburch.logisim.tools.Library loadLibrary(String descriptor) {
                if (descriptor.startsWith("file#")) {
                    // For an InputStream the first relative reference is based on this project.
                    // For nested references Loader is already tracking its current on-disk file.
                    return AgentFileIO.loadRelative(this, source.getParent().toFile(), descriptor.substring(5));
                }
                return super.loadLibrary(descriptor);
            }
        };
        try {
            LogisimFile nativeFile = loader.openLogisimFile(new ByteArrayInputStream(original));
            loader.check();
            if (nativeFile == null) throw fault(FORMAT, "Native loader returned no project", "path", source.toString());
            return new XmlDocument(xml, nativeFile, source);
        } catch (Fault error) {
            throw error;
        } catch (Exception error) {
            throw fault(FORMAT, "Logisim-ITA could not load the circuit", "path", source.toString(),
                    "diagnostic", error.toString(), "native_errors", loader.errors);
        }
    }

    static Document parse(byte[] data) {
        if (data.length > MAX_XML_BYTES) throw fault(LIMIT, "Circuit XML exceeds byte limit", "limit", MAX_XML_BYTES);
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler() {
                @Override public void error(SAXParseException error) throws SAXParseException { throw error; }
                @Override public void fatalError(SAXParseException error) throws SAXParseException { throw error; }
            });
            Document result = builder.parse(new ByteArrayInputStream(data));
            if (!result.getDocumentElement().getTagName().equals("project")) {
                throw fault(FORMAT, "Expected a Logisim project element");
            }
            return result;
        } catch (Fault error) {
            throw error;
        } catch (SAXParseException error) {
            throw fault(FORMAT, "Invalid circuit XML", "line", error.getLineNumber(), "column", error.getColumnNumber(),
                    "diagnostic", error.getMessage());
        } catch (Exception error) {
            throw fault(FORMAT, "Invalid circuit XML", "diagnostic", error.toString());
        }
    }

    static byte[] nativeBytes(LogisimFile file) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        AgentFileIO.write(file, output);
        return output.toByteArray();
    }

    static byte[] bytes(Document xml) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            TransformerFactory factory = TransformerFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            var writer = factory.newTransformer();
            writer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            writer.transform(new DOMSource(xml), new StreamResult(output));
            return output.toByteArray();
        } catch (Exception error) {
            throw fault(IO, "Circuit XML could not be serialized", "diagnostic", error.toString());
        }
    }

    static byte[] read(Path path) throws IOException {
        if (Files.size(path) > MAX_XML_BYTES) throw fault(LIMIT, "Circuit XML exceeds byte limit", "limit", MAX_XML_BYTES);
        return Files.readAllBytes(path);
    }

    private static void checkLibraries(Document xml, Path source, Path root, Set<Path> visiting) {
        if (!visiting.add(source)) throw fault(FORMAT, "Circular circuit library reference", "path", source.toString());
        if (visiting.size() > 16) throw fault(LIMIT, "Circuit library nesting exceeds limit", "limit", 16);
        for (Element lib : children(xml.getDocumentElement(), "lib")) {
            String descriptor = lib.getAttribute("desc");
            if (descriptor.startsWith("#")) continue;
            if (!descriptor.startsWith("file#")) {
                throw fault(FORMAT, "Unsupported circuit library descriptor", "library", descriptor, "project", source.toString());
            }
            try {
                Path library = source.getParent().resolve(descriptor.substring(5)).toRealPath();
                if (!library.startsWith(root)) throw fault(FORMAT, "Circuit library is outside the workspace", "library", descriptor);
                checkLibraries(parse(read(library)), library, root, visiting);
            } catch (IOException error) {
                throw fault(FORMAT, "Circuit library is missing or unreadable", "library", descriptor, "project", source.toString());
            }
        }
        visiting.remove(source);
    }

    static List<Element> children(Element parent, String tag) {
        List<Element> result = new ArrayList<>();
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element && (tag == null || child.getNodeName().equals(tag))) result.add((Element) child);
        }
        return result;
    }

    static Element circuit(Document doc, String name) {
        for (Element candidate : children(doc.getDocumentElement(), "circuit")) {
            if (candidate.getAttribute("name").equals(name)) return candidate;
        }
        throw fault(MISSING, "Circuit does not exist", "circuit", name);
    }

    static Map<String, String> attrs(Element owner) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Element attribute : children(owner, "a")) {
            result.put(attribute.getAttribute("name"), attribute.hasAttribute("val")
                    ? attribute.getAttribute("val") : attribute.getTextContent());
        }
        return result;
    }

    static void attr(Element owner, String key, String value) {
        Element target = null;
        for (Element attribute : children(owner, "a")) {
            if (key.equals(attribute.getAttribute("name"))) target = attribute;
        }
        if (target == null) {
            target = owner.getOwnerDocument().createElement("a");
            target.setAttribute("name", key);
            append(owner, target);
        }
        if (value.indexOf('\n') >= 0) {
            target.removeAttribute("val");
            target.setTextContent(value);
        } else {
            target.setAttribute("val", value);
        }
    }

    static String id(Element comp) {
        if (comp.hasAttribute(COMPONENT_ID)) return comp.getAttribute(COMPONENT_ID);
        String name = ((Element) comp.getParentNode()).getAttribute("name");
        String seed = String.join("\0", name, comp.getAttribute("lib"), comp.getAttribute("name"), comp.getAttribute("loc"));
        return "c-" + UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
    }

    static Component component(Circuit circuit, Element xml) {
        Location loc = Location.parse(xml.getAttribute("loc"));
        String factory = xml.getAttribute("name");
        for (Component component : circuit.getNonWires(loc)) {
            String nativeName = component.getFactory().getName();
            if (component.getLocation().equals(loc)
                    && (nativeName.equals(factory) || (factory.equals("Text") && nativeName.equals("Label")))) return component;
        }
        // Components like Text with no ends are absent from CircuitPoints.
        for (Component component : circuit.getNonWires()) {
            String nativeName = component.getFactory().getName();
            if (component.getLocation().equals(loc)
                    && (nativeName.equals(factory) || (factory.equals("Text") && nativeName.equals("Label")))) return component;
        }
        throw fault(FORMAT, "Component has no matching Logisim instance", "factory", factory, "location", loc.toString());
    }

    static Element element(Document document, String tag, String... properties) {
        Element element = document.createElement(tag);
        for (int i = 0; i < properties.length; i += 2) element.setAttribute(properties[i], properties[i + 1]);
        return element;
    }

    static void append(Element parent, Element child) {
        int depth = 0;
        for (Node node = parent.getParentNode(); node instanceof Element; node = node.getParentNode()) depth++;
        Document doc = parent.getOwnerDocument();
        Node tail = parent.getLastChild();
        if (tail == null || tail.getNodeType() != Node.TEXT_NODE || !tail.getTextContent().isBlank()) {
            tail = parent.appendChild(doc.createTextNode("\n" + "  ".repeat(depth)));
        }
        parent.insertBefore(doc.createTextNode("\n" + "  ".repeat(depth + 1)), tail);
        parent.insertBefore(child, tail);
    }
}
