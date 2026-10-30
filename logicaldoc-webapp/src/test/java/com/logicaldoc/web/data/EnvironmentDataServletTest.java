package com.logicaldoc.web.data;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;

import org.junit.Test;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.gui.common.client.ServerException;
import com.logicaldoc.util.io.FileUtil;
import com.logicaldoc.util.servlet.MockServletRequest;
import com.logicaldoc.util.servlet.MockServletResponse;
import com.logicaldoc.web.AuthorMenus;

/**
 * Checks who may list the environment. It holds the server's environment
 * variables and Java system properties, which in Healdan's container include
 * the database password, so it needs System > General, whose Environment tab
 * lists them.
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
