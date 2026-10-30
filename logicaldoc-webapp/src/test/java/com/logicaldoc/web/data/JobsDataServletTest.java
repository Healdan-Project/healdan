package com.logicaldoc.web.data;

import static org.junit.Assert.assertEquals;
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
 * Checks who may list the scheduled jobs and their triggers. It needs System,
 * whose Scheduled tasks screen lists them in its Jobs tab, as the jobs RPCs
 * do.
 */
public class JobsDataServletTest extends AbstractDataServletTestCase {

    // System, whose Scheduled tasks screen has no menu of its own
    private static final long SYSTEM = 80L;

    private JobsDataServlet testSubject = new JobsDataServlet();

    @Test
    public void testListingNeedsTheSystemMenu() throws IOException, ServerException, PersistenceException {
        prepareSession("author", "admin");
        assertEquals("", list(false));
        assertEquals("", list(true));

        AuthorMenus.grant(SYSTEM);
        assertTrue(list(false).startsWith("<list>"));
        assertTrue(list(true).startsWith("<list>"));
    }

    @Test
    public void testAdministratorsListTheJobs() throws IOException {
        assertTrue(list(false).startsWith("<list>"));
        assertTrue(list(true).startsWith("<list>"));
    }

    private String list(boolean groupsOnly) throws IOException {
        MockServletRequest request = new MockServletRequest(servletSession);
        if (groupsOnly)
            request.setParameter("groupsonly", "true");
        MockServletResponse response = new MockServletResponse(responseFile);
        testSubject.service(request, response);
        response.flushBuffer();
        // A refused listing writes nothing
        String xml = responseFile.exists() ? FileUtil.readFile(responseFile) : "";
        clearResponseFile();
        return xml;
    }
}
