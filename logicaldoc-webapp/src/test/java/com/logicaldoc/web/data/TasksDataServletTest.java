package com.logicaldoc.web.data;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.List;

import org.junit.Test;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.gui.common.client.ServerException;
import com.logicaldoc.util.io.FileUtil;
import com.logicaldoc.util.servlet.MockServletRequest;
import com.logicaldoc.util.servlet.MockServletResponse;
import com.logicaldoc.web.AuthorMenus;

/**
 * Checks who may list the scheduled tasks, with their status and schedule. It
 * needs System, whose Scheduled tasks screen lists them, as the task RPCs do.
 */
public class TasksDataServletTest extends AbstractDataServletTestCase {

    // System, whose Scheduled tasks screen has no menu of its own
    private static final long SYSTEM = 80L;

    private TasksDataServlet testSubject = new TasksDataServlet();

    @Override
    protected List<String> getPluginArchives() {
        // The core plugin declares the tasks
        return List.of("/logicaldoc-core-plugin.jar");
    }

    @Test
    public void testListingNeedsTheSystemMenu() throws IOException, ServerException, PersistenceException {
        prepareSession("author", "admin");
        assertFalse(list().contains("<task>"));

        AuthorMenus.grant(SYSTEM);
        assertTrue(list().contains("<name><![CDATA[IndexerTask]]></name>"));
    }

    @Test
    public void testAdministratorsListTheTasks() throws IOException {
        assertTrue(list().contains("<name><![CDATA[IndexerTask]]></name>"));
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
