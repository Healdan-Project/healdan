package com.logicaldoc.web.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;

import java.util.List;

import org.junit.Test;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.gui.common.client.AccessDeniedException;
import com.logicaldoc.gui.common.client.ServerException;
import com.logicaldoc.gui.common.client.beans.GUISearchEngine;
import com.logicaldoc.util.config.ContextProperties;
import com.logicaldoc.util.spring.Context;
import com.logicaldoc.web.AbstractFulltextTestCase;
import com.logicaldoc.web.AuthorMenus;

/**
 * Checks who may call the RPCs in {@link SearchEngineServiceImpl}. They all
 * belong to Settings > Search and indexing, so each needs that menu. The query
 * runs on the raw index with no permission filter, so without the check any
 * user could list every document the index holds.
 */
public class SearchEngineServiceImplAuthorizationTest extends AbstractFulltextTestCase {

    // Settings > Search and indexing, the screen that calls every RPC here
    private static final long SEARCH_AND_INDEXING = 101L;

    private SearchEngineServiceImpl testSubject = new SearchEngineServiceImpl();

    @Test
    public void testQueryNeedsTheSearchAndIndexingMenu() throws ServerException, PersistenceException {
        AuthorMenus.revoke(SEARCH_AND_INDEXING);
        prepareSession("author", "admin");
        assertThrows(AccessDeniedException.class, () -> testSubject.query("content: paper", 1, 10));

        AuthorMenus.grant(SEARCH_AND_INDEXING);
        assertFalse(testSubject.query("content: paper", 1, 10).getHits().isEmpty());
    }

    @Test
    public void testReadsNeedTheSearchAndIndexingMenu() throws ServerException, PersistenceException {
        AuthorMenus.revoke(SEARCH_AND_INDEXING);
        prepareSession("author", "admin");
        assertThrows(AccessDeniedException.class, testSubject::getInfo);
        assertThrows(AccessDeniedException.class, testSubject::check);
        assertThrows(AccessDeniedException.class, testSubject::countEntries);

        AuthorMenus.grant(SEARCH_AND_INDEXING);
        assertNotNull(testSubject.getInfo());
        assertNotNull(testSubject.check());
        assertEquals(engine.getCount(), testSubject.countEntries());
    }

    @Test
    public void testSettingChangesNeedTheSearchAndIndexingMenu() throws ServerException, PersistenceException {
        GUISearchEngine info = testSubject.getInfo();
        ContextProperties config = Context.get().getConfig();
        String excludes = config.getProperty("default.index.excludes");
        GUISearchEngine changed = testSubject.getInfo();
        changed.setExcludePatterns("*.changed");

        AuthorMenus.revoke(SEARCH_AND_INDEXING);
        prepareSession("author", "admin");
        assertThrows(AccessDeniedException.class, () -> testSubject.save(changed));
        assertEquals(excludes, config.getProperty("default.index.excludes"));
        assertThrows(AccessDeniedException.class, () -> testSubject.setLanguageStatus("en", false));
        assertThrows(AccessDeniedException.class, () -> testSubject.setAliases("txt", "changed"));
        assertThrows(AccessDeniedException.class, () -> testSubject.reorderTokenFilters(List.of("stopwords")));
        assertThrows(AccessDeniedException.class,
                () -> testSubject.saveTokenFilterSettings("stopwords", List.of()));
        assertThrows(AccessDeniedException.class, () -> testSubject.setTokenFilterStatus("stopwords", false));

        AuthorMenus.grant(SEARCH_AND_INDEXING);
        testSubject.save(info);
        testSubject.saveTokenFilterSettings("stopwords", List.of());
    }

    @Test
    public void testIndexChangesNeedTheSearchAndIndexingMenu() throws ServerException, PersistenceException {
        long count = engine.getCount();
        AuthorMenus.revoke(SEARCH_AND_INDEXING);
        prepareSession("author", "admin");
        assertThrows(AccessDeniedException.class, () -> testSubject.rescheduleAll(true));
        assertThrows(AccessDeniedException.class, () -> testSubject.remove(List.of(1L)));
        assertThrows(AccessDeniedException.class, testSubject::purge);
        assertThrows(AccessDeniedException.class, testSubject::unlock);
        assertEquals(count, engine.getCount());

        AuthorMenus.grant(SEARCH_AND_INDEXING);
        testSubject.unlock();
    }
}
