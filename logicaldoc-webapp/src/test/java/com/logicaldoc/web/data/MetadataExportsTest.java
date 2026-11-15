// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.web.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.Test;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.gui.common.client.ServerException;
import com.logicaldoc.util.servlet.MockServletRequest;
import com.logicaldoc.util.servlet.MockServletResponse;
import com.logicaldoc.web.AuthorMenus;

/**
 * Checks who may export a template or an attribute set. Both exports need
 * Document metadata, the menu of the Templates screen whose tabs offer them,
 * as upstream's check means to.
 */
public class MetadataExportsTest extends AbstractDataServletTestCase {

    // Document metadata, whose Templates screen has the Attribute sets tab
    private static final long DOCUMENT_METADATA = 25L;

    // The fixture's template and attribute set, both named test1
    private static final long TEMPLATE = 5L;

    private static final long SET = 5L;

    @Test
    public void testTemplateExportNeedsTheDocumentMetadataMenu()
            throws IOException, ServerException, PersistenceException {
        prepareSession("author", "admin");
        assertEquals(0, export(new TemplatesDataServlet(), TEMPLATE).length);

        AuthorMenus.grant(DOCUMENT_METADATA);
        String xml = new String(export(new TemplatesDataServlet(), TEMPLATE), StandardCharsets.UTF_8);
        assertTrue(xml, xml.contains("<string>test1</string>"));
    }

    @Test
    public void testAttributeSetExportNeedsTheDocumentMetadataMenu()
            throws IOException, ServerException, PersistenceException {
        prepareSession("author", "admin");
        assertEquals(0, export(new AttributeSetsDataServlet(), SET).length);

        AuthorMenus.grant(DOCUMENT_METADATA);
        assertTrue(entries(export(new AttributeSetsDataServlet(), SET)).contains("object.xml"));
    }

    @Test
    public void testAdministratorsExport() throws IOException {
        String xml = new String(export(new TemplatesDataServlet(), TEMPLATE), StandardCharsets.UTF_8);
        assertTrue(xml, xml.contains("<string>test1</string>"));

        assertTrue(entries(export(new AttributeSetsDataServlet(), SET)).contains("object.xml"));
    }

    /**
     * Asks the servlet for an export and returns what it wrote. A refused
     * export writes nothing.
     */
    private byte[] export(AbstractDataServlet servlet, long id) throws IOException {
        clearResponseFile();
        MockServletRequest request = new MockServletRequest(servletSession);
        request.setParameter("export", Long.toString(id));
        MockServletResponse response = new MockServletResponse(responseFile);
        servlet.service(request, response);
        response.flushBuffer();
        byte[] written = responseFile.exists() ? Files.readAllBytes(responseFile.toPath()) : new byte[0];
        clearResponseFile();
        return written;
    }

    private static List<String> entries(byte[] zipFile) throws IOException {
        List<String> names = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null)
                names.add(entry.getName());
        }
        return names;
    }
}
