package com.logicaldoc.util.config;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;
import java.util.TreeSet;

/**
 * This is an extension of Java Properties that stores the properties
 * alphabetically
 * 
 * @author Brian Pipa - http://pipasoft.com
 * @version 1.0
 */
public class OrderedProperties extends Properties {

    private static final long serialVersionUID = 1L;

    @Override
    public synchronized Enumeration<Object> keys() {
        return Collections.enumeration(new TreeSet<Object>(super.keySet()));
    }

    /**
     * Overrides the original store() method and sorts the output
     * 
     * @param out a FileOutPutStream to send the output to
     * @param header a textual header for the top of the file
     * @exception IOException when things go wrong
     */
    @Override
    public void store(OutputStream out, String header) throws IOException {
        List<String> keys = getKeys();

        // Write the header
        DataOutputStream dataOutputStream = new DataOutputStream(out);
        dataOutputStream.writeBytes("#%s%n".formatted(header));

        // Write the date/time
        Date now = new Date();
        dataOutputStream.writeBytes("#%s%n".formatted(now));

        // Write the properties
        for (String key : keys) {
            // Healdan: a line break in a key or a value would start a line of
            // its own, which loads as a property of its own
            String value = escapeLineBreaks(doubleSlash(super.getProperty(key)));
            String line = "%s=%s%n".formatted(escapeLineBreaks(key), value);
            dataOutputStream.writeBytes(line);
        }

        dataOutputStream.flush();
        dataOutputStream.close();
    }

    /**
     * Private method to double slash paths
     * 
     * @param orig the string to double slash
     * @return a double-slashed string
     */
    private String doubleSlash(String orig) {
        StringBuilder buf = new StringBuilder();

        for (int i = 0; i < orig.length(); i++) {
            if (orig.charAt(i) == '\\') {
                buf.append("\\\\");
            } else {
                buf.append(orig.charAt(i));
            }
        }

        return buf.toString();
    }

    /**
     * Healdan: writes each line break as the escape {@link #load} reads back
     * as one
     * 
     * @param orig the string to escape
     * @return the string with its line breaks escaped
     */
    private static String escapeLineBreaks(String orig) {
        return orig.replace("\r", "\\r").replace("\n", "\\n");
    }

    /**
     * All the keys but alphabetically ordered
     * 
     * @return the ordered collection of all the keys
     */
    public List<String> getKeys() {
        ArrayList<String> keys = new ArrayList<>();

        for (Object key : keySet())
            keys.add(key.toString());

        // sort them
        Collections.sort(keys);

        return keys;
    }
}