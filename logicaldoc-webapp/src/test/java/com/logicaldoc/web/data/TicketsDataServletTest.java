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
 * Checks who may list the tickets. The list holds each ticket's ID, which is
 * all a download link needs, so it needs Reports > Tickets, the menu of the
 * only screen that lists them.
 */
public class TicketsDataServletTest extends AbstractDataServletTestCase {

    // Reports > Tickets
    private static final long TICKETS = -7L;

    private TicketsDataServlet testSubject = new TicketsDataServlet();

    @Test
    public void testListingNeedsTheTicketsMenu() throws IOException, ServerException, PersistenceException {
        prepareSession("author", "admin");
        assertFalse(list().contains("<ticket>"));

        AuthorMenus.grant(TICKETS);
        assertTrue(list().contains("<ticketId><![CDATA[1]]></ticketId>"));
    }

    @Test
    public void testAdministratorsListTheTickets() throws IOException {
        assertTrue(list().contains("<ticketId><![CDATA[1]]></ticketId>"));
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
