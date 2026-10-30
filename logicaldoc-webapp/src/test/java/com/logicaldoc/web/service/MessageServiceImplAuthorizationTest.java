package com.logicaldoc.web.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.communication.MessageTemplate;
import com.logicaldoc.core.communication.MessageTemplateDAO;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.gui.common.client.AccessDeniedException;
import com.logicaldoc.gui.common.client.ServerException;
import com.logicaldoc.gui.common.client.beans.GUIMessageTemplate;
import com.logicaldoc.web.AbstractWPTestCase;
import com.logicaldoc.web.AuthorMenus;

/**
 * Checks who may change the message templates through
 * {@link MessageServiceImpl}. Each change needs Outgoing email, whose Message
 * templates tab edits them. Reading a template needs only a session, because
 * any user's Send by email dialog lists the user templates.
 */
public class MessageServiceImplAuthorizationTest extends AbstractWPTestCase {

    // Settings > Outgoing email, whose Message templates tab edits the templates
    private static final long OUTGOING_EMAIL = 103L;

    // The fixture's user template, in English
    private static final String TEMPLATE = "test";

    private MessageServiceImpl testSubject = new MessageServiceImpl();

    @Test
    public void testSaveTemplatesNeedsTheOutgoingEmailMenu() throws ServerException, PersistenceException {
        AuthorMenus.revoke(OUTGOING_EMAIL);
        prepareSession("author", "admin");
        GUIMessageTemplate italian = italian();
        assertThrows(AccessDeniedException.class, () -> testSubject.saveTemplates(List.of(italian)));
        assertNull(italianId());

        AuthorMenus.grant(OUTGOING_EMAIL);
        testSubject.saveTemplates(List.of(italian));
        assertNotNull(italianId());
    }

    @Test
    public void testDeleteTemplatesNeedsTheOutgoingEmailMenu() throws ServerException, PersistenceException {
        // Deleting by ID leaves the English templates alone, so delete an
        // Italian one
        testSubject.saveTemplates(List.of(italian()));
        long id = italianId();

        AuthorMenus.revoke(OUTGOING_EMAIL);
        prepareSession("author", "admin");
        assertThrows(AccessDeniedException.class, () -> testSubject.deleteTemplates(List.of(id)));
        assertNotNull(MessageTemplateDAO.get().findById(id));

        AuthorMenus.grant(OUTGOING_EMAIL);
        testSubject.deleteTemplates(List.of(id));
        assertNull(MessageTemplateDAO.get().findById(id));
    }

    @Test
    public void testDeleteTemplatesByNameNeedsTheOutgoingEmailMenu() throws ServerException, PersistenceException {
        AuthorMenus.revoke(OUTGOING_EMAIL);
        prepareSession("author", "admin");
        assertThrows(AccessDeniedException.class, () -> testSubject.deleteTemplates(TEMPLATE));
        assertFalse(MessageTemplateDAO.get().findByName(TEMPLATE, Tenant.DEFAULT_ID).isEmpty());

        AuthorMenus.grant(OUTGOING_EMAIL);
        testSubject.deleteTemplates(TEMPLATE);
        assertTrue(MessageTemplateDAO.get().findByName(TEMPLATE, Tenant.DEFAULT_ID).isEmpty());
    }

    @Test
    public void testReadsNeedOnlyASession() throws ServerException, PersistenceException {
        AuthorMenus.revoke(OUTGOING_EMAIL);
        prepareSession("author", "admin");
        List<GUIMessageTemplate> templates = testSubject.loadTemplates("en", MessageTemplate.Type.USER.name());
        assertTrue(templates.stream().anyMatch(t -> TEMPLATE.equals(t.getName())));
        long id = MessageTemplateDAO.get().findByNameAndLanguage(TEMPLATE, "en", Tenant.DEFAULT_ID).getId();
        assertEquals(TEMPLATE, testSubject.getTemplate(id).getName());
    }

    // The lookup by name and language falls back to English, so look by name
    private static Long italianId() throws PersistenceException {
        return MessageTemplateDAO.get().findByName(TEMPLATE, Tenant.DEFAULT_ID).stream()
                .filter(t -> "it".equals(t.getLanguage())).map(MessageTemplate::getId).findFirst().orElse(null);
    }

    private static GUIMessageTemplate italian() {
        GUIMessageTemplate template = new GUIMessageTemplate();
        template.setName(TEMPLATE);
        template.setLanguage("it");
        template.setType(MessageTemplate.Type.USER.name());
        template.setSubject("Prova");
        template.setBody("Prova");
        return template;
    }
}
