// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.util.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Properties;

import org.junit.Test;

/**
 * {@link OrderedProperties#store} wrote a line break in a key or a value as
 * is, so it started a line of its own, which loaded as a property of its own.
 * A holder of one settings screen's menu could then write any key into
 * {@code context.properties}, say {@code default.tag.x\ndefault.smtp.host},
 * whose second line sorts after the real {@code default.smtp.host} and so wins.
 * The line breaks are now written as escapes, which load back as line breaks.
 */
public class OrderedPropertiesLineBreaksTest {

    @Test
    public void testALineBreakIsWrittenAsAnEscape() throws IOException {
        OrderedProperties properties = new OrderedProperties();
        properties.setProperty("default.tag.x\ndefault.smtp.host", "mail.example");
        properties.setProperty("default.tag.mode", "free\rdefault.anonymous.enabled=true");
        properties.setProperty("default.tag.select", "a\r\nb\\c");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        properties.store(out, "test");
        Properties loaded = new Properties();
        loaded.load(new ByteArrayInputStream(out.toByteArray()));

        assertEquals(3, loaded.size());
        assertEquals("mail.example", loaded.getProperty("default.tag.x\ndefault.smtp.host"));
        assertEquals("free\rdefault.anonymous.enabled=true", loaded.getProperty("default.tag.mode"));
        assertEquals("a\r\nb\\c", loaded.getProperty("default.tag.select"));
        assertNull(loaded.getProperty("default.smtp.host"));
        assertNull(loaded.getProperty("default.anonymous.enabled"));
    }

    @Test
    public void testContextPropertiesKeepTheirKeysAcrossAWrite() throws IOException {
        File file = new File("target/line-breaks/context.properties");
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), "default.anonymous.enabled=false\ndefault.smtp.host=localhost\n");

        ContextProperties config = new ContextProperties(file);
        config.setProperty("default.tag.x\ndefault.smtp.host", "mail.example");
        config.setProperty("default.tag.mode", "free\rdefault.anonymous.enabled=true");
        config.write();

        ContextProperties reloaded = new ContextProperties(file);
        assertEquals("localhost", reloaded.getProperty("default.smtp.host"));
        assertEquals("false", reloaded.getProperty("default.anonymous.enabled"));
        assertEquals("mail.example", reloaded.getProperty("default.tag.x\ndefault.smtp.host"));
        assertEquals("free\rdefault.anonymous.enabled=true", reloaded.getProperty("default.tag.mode"));
    }
}
