// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.web.data;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.gui.common.client.ServerException;
import com.logicaldoc.util.io.FileUtil;
import com.logicaldoc.util.servlet.MockServletRequest;
import com.logicaldoc.util.servlet.MockServletResponse;
import com.logicaldoc.web.AuthorMenus;

/**
 * Checks who may list the environment, and that it shows no secrets. It holds
 * the server's environment variables and Java system properties, which in
 * Healdan's container include the database password, so it needs System >
 * General, whose Environment tab lists them, and masks what looks secret.
 */
public class EnvironmentDataServletTest extends AbstractDataServletTestCase {

    // System > General, whose Environment tab has no menu of its own
    private static final long GENERAL = 70L;

    private EnvironmentDataServlet testSubject = new EnvironmentDataServlet();

    @Test
    public void testListingNeedsTheGeneralMenu() throws IOException, ServerException, PersistenceException {
        prepareSession("author", "admin");
        assertFalse(list().contains("<entry>"));

        AuthorMenus.grant(GENERAL);
        assertTrue(list().contains("<scope>java</scope>"));
    }

    @Test
    public void testListingMasksSecrets() throws IOException {
        // Environment variables can't be set from Java, and both scopes go
        // through the same masking
        Map<String, String> properties = Map.of("healdan.test.password", "hunter2", "healdan.test.opts",
                "-Xmx2g -Dhealdan.test.secret=s3cr3t -Dfile.encoding=UTF-8", "healdan.test.proxy",
                "http://bob:pr0xy-pw@proxy.example:3128");
        properties.forEach(System::setProperty);
        try {
            String xml = list();
            assertTrue(xml.contains("<scope>java</scope>"));
            for (String secret : List.of("hunter2", "s3cr3t", "pr0xy-pw"))
                assertFalse(secret, xml.contains(secret));
            assertTrue(xml.contains("-Xmx2g -Dhealdan.test.secret=******** -Dfile.encoding=UTF-8"));
            assertTrue(xml.contains("http://bob:********@proxy.example:3128"));
            // What isn't secret still shows
            assertTrue(xml.contains(System.getProperty("java.version")));
        } finally {
            properties.keySet().forEach(System::clearProperty);
        }
    }

    private String list() throws IOException {
        MockServletResponse response = new MockServletResponse(responseFile);
        testSubject.service(new MockServletRequest(servletSession), response);
        response.flushBuffer();
        // A refused listing writes nothing
        String xml = responseFile.exists() ? FileUtil.readFile(responseFile) : "";
        clearResponseFile();
        return xml;
    }
}
