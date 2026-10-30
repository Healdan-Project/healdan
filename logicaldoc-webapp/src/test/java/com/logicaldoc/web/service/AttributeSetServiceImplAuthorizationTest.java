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
import com.logicaldoc.core.metadata.AttributeOptionDAO;
import com.logicaldoc.core.metadata.AttributeSetDAO;
import com.logicaldoc.gui.common.client.AccessDeniedException;
import com.logicaldoc.gui.common.client.ServerException;
import com.logicaldoc.gui.common.client.beans.GUIAttributeSet;
import com.logicaldoc.gui.common.client.beans.GUIValue;
import com.logicaldoc.web.AbstractWPTestCase;
import com.logicaldoc.web.AuthorMenus;

/**
 * Checks who may change attribute sets through {@link AttributeSetServiceImpl}.
 * Attribute sets have no permissions of their own, unlike templates, so each
 * change needs Document metadata, the menu of the screen that edits them.
 * Reading a set needs only a session, because the document screens read them.
 */
public class AttributeSetServiceImplAuthorizationTest extends AbstractWPTestCase {

    // Document metadata, whose Templates screen has the Attribute sets tab
    private static final long DOCUMENT_METADATA = 25L;

    // The fixture's attribute set, with one attribute
    private static final long SET = 5L;

    private static final String ATTRIBUTE = "attr1";

    private AttributeSetServiceImpl testSubject = new AttributeSetServiceImpl();

    @Test
    public void testDeleteNeedsTheDocumentMetadataMenu() throws ServerException, PersistenceException {
        prepareSession("author", "admin");
        assertThrows(AccessDeniedException.class, () -> testSubject.delete(SET));
        assertNotNull(AttributeSetDAO.get().findById(SET));

        AuthorMenus.grant(DOCUMENT_METADATA);
        testSubject.delete(SET);
        assertNull(AttributeSetDAO.get().findById(SET));
    }

    @Test
    public void testSaveNeedsTheDocumentMetadataMenu() throws ServerException, PersistenceException {
        prepareSession("author", "admin");
        GUIAttributeSet set = testSubject.getAttributeSet(SET);
        set.setDescription("changed");
        assertThrows(AccessDeniedException.class, () -> testSubject.save(set));
        assertEquals("test1_desc", AttributeSetDAO.get().findById(SET).getDescription());

        AuthorMenus.grant(DOCUMENT_METADATA);
        testSubject.save(set);
        assertEquals("changed", AttributeSetDAO.get().findById(SET).getDescription());
    }

    @Test
    public void testOptionChangesNeedTheDocumentMetadataMenu() throws ServerException, PersistenceException {
        AttributeOptionDAO options = AttributeOptionDAO.get();
        prepareSession("author", "admin");
        List<GUIValue> values = List.of(new GUIValue("a", "alpha"), new GUIValue("b", "beta"));
        assertThrows(AccessDeniedException.class, () -> testSubject.saveOptions(SET, ATTRIBUTE, values));
        assertThrows(AccessDeniedException.class, () -> testSubject.parseOptions(SET, ATTRIBUTE));
        assertTrue(options.findByAttribute(SET, ATTRIBUTE).isEmpty());

        AuthorMenus.grant(DOCUMENT_METADATA);
        testSubject.saveOptions(SET, ATTRIBUTE, values);
        assertEquals(2, options.findByAttribute(SET, ATTRIBUTE).size());

        AuthorMenus.revoke(DOCUMENT_METADATA);
        assertThrows(AccessDeniedException.class,
                () -> testSubject.deleteOptions(SET, ATTRIBUTE, List.of("alpha")));
        assertEquals(2, options.findByAttribute(SET, ATTRIBUTE).size());

        AuthorMenus.grant(DOCUMENT_METADATA);
        testSubject.deleteOptions(SET, ATTRIBUTE, List.of("alpha"));
        assertEquals(1, options.findByAttribute(SET, ATTRIBUTE).size());
    }

    @Test
    public void testApplyingToTemplatesNeedsTheDocumentMetadataMenu() throws ServerException, PersistenceException {
        prepareSession("author", "admin");
        assertThrows(AccessDeniedException.class, () -> testSubject.applyValidationToTemplates(SET, ATTRIBUTE));
        assertThrows(AccessDeniedException.class,
                () -> testSubject.applyInitializationToTemplates(SET, ATTRIBUTE));
        assertThrows(AccessDeniedException.class, () -> testSubject.applyAllToTemplates(SET, ATTRIBUTE));

        AuthorMenus.grant(DOCUMENT_METADATA);
        testSubject.applyValidationToTemplates(SET, ATTRIBUTE);
        testSubject.applyInitializationToTemplates(SET, ATTRIBUTE);

        // The fixture's template has its own attr1, with no set, which
        // applyAllToTemplates can't handle, so ask for one the set lacks
        testSubject.applyAllToTemplates(SET, "unexisting");
    }

    @Test
    public void testReadsNeedOnlyASession() throws ServerException {
        prepareSession("author", "admin");
        assertFalse(testSubject.getAttributeSets().isEmpty());
        assertNotNull(testSubject.getAttributeSet(SET));
        assertNotNull(testSubject.getAttributeSet("test1"));
    }
}
