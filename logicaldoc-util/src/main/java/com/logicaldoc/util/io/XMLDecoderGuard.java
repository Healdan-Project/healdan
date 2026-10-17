package com.logicaldoc.util.io;

import java.io.ByteArrayInputStream;
import java.util.Collection;
import java.util.Set;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Checks a document before {@link java.beans.XMLDecoder} reads it. The decoder
 * instantiates any class and calls any method a document names, so a document
 * that didn't come from {@link java.beans.XMLEncoder} can run arbitrary code.
 * This guard accepts only what the encoder writes for plain beans: objects of
 * the allowed classes, their properties, additions to collections and maps,
 * and the no-argument getters the encoder uses to refer to a value again.
 * Static method calls are refused.
 *
 * @since Healdan
 */
public class XMLDecoderGuard {

    private static final Set<String> ELEMENTS = Set.of("object", "void", "array", "null", "string", "char",
            "boolean", "byte", "short", "int", "long", "float", "double");

    private static final Set<String> ATTRIBUTES = Set.of("class", "method", "property", "index", "id", "idref",
            "length", "code");

    private static final Pattern METHOD = Pattern.compile("add|put|(get|is)[A-Z]\\w*");

    private static final Pattern PROPERTY = Pattern.compile("[A-Za-z_]\\w*");

    private XMLDecoderGuard() {
    }

    /**
     * Checks a document as {@link IOUtil#deserialize(String)} would decode it
     *
     * @param xml the document
     * @param allowedClasses fully qualified names of the classes the document
     *        may instantiate
     *
     * @throws IllegalArgumentException if the document isn't well formed or
     *         names anything not allowed
     */
    public static void check(String xml, Collection<String> allowedClasses) {
        Element root;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler());

            // The same bytes IOUtil.deserialize() hands to the decoder
            root = builder.parse(new ByteArrayInputStream(xml.getBytes())).getDocumentElement();
        } catch (Exception e) {
            throw new IllegalArgumentException("Not an XMLEncoder document", e);
        }

        if (!"java".equals(root.getTagName()))
            throw new IllegalArgumentException("Root element <%s> not allowed".formatted(root.getTagName()));
        NamedNodeMap attributes = root.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            Node attribute = attributes.item(i);
            if (!"version".equals(attribute.getNodeName()) && !("class".equals(attribute.getNodeName())
                    && java.beans.XMLDecoder.class.getName().equals(attribute.getNodeValue())))
                throw new IllegalArgumentException("Attribute %s=\"%s\" not allowed in <java>"
                        .formatted(attribute.getNodeName(), attribute.getNodeValue()));
        }

        checkChildren(root, allowedClasses);
    }

    private static void checkChildren(Element parent, Collection<String> allowedClasses) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++)
            if (children.item(i) instanceof Element child)
                checkElement(child, allowedClasses);
    }

    private static void checkElement(Element element, Collection<String> allowedClasses) {
        String tag = element.getTagName();
        if (!ELEMENTS.contains(tag))
            throw new IllegalArgumentException("Element <%s> not allowed".formatted(tag));

        if (element.hasAttribute("class") && element.hasAttribute("method"))
            throw new IllegalArgumentException("Static method %s not allowed".formatted(element.getAttribute("method")));

        NamedNodeMap attributes = element.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            Node attribute = attributes.item(i);
            String name = attribute.getNodeName();
            String value = attribute.getNodeValue();
            if (!ATTRIBUTES.contains(name) || ("class".equals(name) && !allowedClasses.contains(value))
                    || ("method".equals(name) && (!METHOD.matcher(value).matches() || "getClass".equals(value)))
                    || ("property".equals(name) && (!PROPERTY.matcher(value).matches() || "class".equals(value))))
                throw new IllegalArgumentException("Attribute %s=\"%s\" not allowed in <%s>".formatted(name, value, tag));
        }

        checkChildren(element, allowedClasses);
    }
}
