package com.logicaldoc.core.searchengine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.common.SolrInputDocument;
import org.junit.Before;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.logicaldoc.core.AbstractCoreTestCase;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.util.plugin.PluginException;
import com.logicaldoc.util.spring.Context;

public class FulltextSearchTest extends AbstractCoreTestCase {

    private SearchEngine testSubject;

    @Before
    @Override
    public void setUp() throws IOException, SQLException, PluginException {
        super.setUp();
        testSubject = SearchEngine.get();
        try {
            addHits();
        } catch (Exception e) {
            e.printStackTrace();
            throw new IOException(e.getMessage(), e);
        }
    }

    @Test
    public void testWrite() throws IOException, ClassNotFoundException {
        File file = new File(tempDir, "query.ser");

        FulltextSearchOptions opt = new FulltextSearchOptions();

        opt.setLanguage("it");
        opt.setExpression("prova test");
        opt.setExpressionLanguage("it");
        opt.setTemplate(1L);
        opt.setSizeMax(3000L);
        opt.setSizeMin(2L);
        opt.setType(SearchOptions.TYPE_FULLTEXT);
        opt.setUserId(1);

        opt.write(file);

        FulltextSearchOptions opt2 = (FulltextSearchOptions) SearchOptions.read(file);

        assertEquals("prova test", opt2.getExpression());
        assertEquals("it", opt2.getExpressionLanguage());
        assertEquals(1, opt2.getTemplate().longValue());
        assertEquals(3000, opt2.getSizeMax().longValue());
        assertEquals(2, opt2.getSizeMin().longValue());
        assertEquals(SearchOptions.TYPE_FULLTEXT, opt2.getType());
        assertEquals(1, opt2.getUserId());
    }

    private void addHits() throws Exception {
        Document document = new Document();
        document.setId(1L);
        document.setFileName("test.doc");
        document.setLanguage("en");
        document.setDate(referenceInstant);
        document.setFolder(FolderDAO.get().findById(3000L, true));
        testSubject.addHit(document, "Questo e un documento di prova. Per fortuna che esistono i test. document");

        // Adding unexisting document 111
        document = new Document();
        document.setId(111L);
        document.setFileName("test.doc");
        document.setTemplateId(0L);
        document.setLanguage("en");
        document.setDate(referenceInstant);
        document.setFolder(FolderDAO.get().findById(6L, true));
        testSubject.addHit(document,
                "This is another test documents just for test insertion.Solr is an enterprise-ready, Lucene-based search server that supports faceted ... This is useful for retrieving and highlighting the documents contents for display but is not .... hl, When hl=true , highlight snippets in the query response.");

        document = new Document();
        document.setId(2L);
        document.setFileName("test.doc");
        document.setLanguage("en");
        document.setDate(referenceInstant);
        document.setFolder(FolderDAO.get().findById(1200L, true));
        testSubject.addHit(document, "Another document");

        document = new Document();
        document.setId(3L);
        document.setFileName("test.doc");
        document.setLanguage("en");
        document.setDate(referenceInstant);
        document.setFolder(FolderDAO.get().findById(1300L, true));
        testSubject.addHit(document,
                "Lorem ipsum dolor sit amet, consectetur 5568299afbX0 ZKBKCHZZ80A CH8900761016116097873 adipisicing elit");

        assertEquals(4, testSubject.getCount());
    }

    @Test
    public void testSearch() throws Exception {
        FulltextSearchOptions opt = new FulltextSearchOptions();
        opt.setLanguage("en");
        opt.setExpression("document");
        opt.setFields(Set.of("content", "title"));
        opt.setExpressionLanguage("en");
        opt.setType(SearchOptions.TYPE_FULLTEXT);
        opt.setUserId(1);

        Search search = new FulltextSearch();
        search.setOptions(opt);

        List<Hit> hits = null;
        hits = search.search();
        assertEquals(2, hits.size());

        opt.setMaxHits(1);
        hits = search.search();
        assertEquals(1, hits.size());
        assertTrue(search.isMoreHitsPresent());

        opt = new FulltextSearchOptions();
        opt.setLanguage("en");
        opt.setExpression("CH8900761016116097873");
        opt.setFields(Set.of("content", "title"));
        opt.setExpressionLanguage("en");
        opt.setType(SearchOptions.TYPE_FULLTEXT);
        opt.setUserId(1);

        search = new FulltextSearch();
        search.setOptions(opt);

        hits = search.search();
        assertEquals(1, hits.size());
        assertEquals(3L, hits.get(0).getId());
        assertEquals("en", hits.get(0).getLanguage());
    }

    @Test
    public void testSearchInFolder() throws Exception {
        // M,ake sure all folders have its path calculated
        for (Long folderId : FolderDAO.get().findAllIds())
            FolderDAO.get().computePath(folderId);

        // Search in a tree
        FulltextSearchOptions opt = new FulltextSearchOptions();
        opt.setLanguage("en");
        opt.setExpression("document");
        opt.setFields(Set.of("content", "title"));
        opt.setExpressionLanguage("en");
        opt.setType(SearchOptions.TYPE_FULLTEXT);
        opt.setUserId(1);
        opt.setFolderId(3000L);
        opt.setSearchInSubPath(true);

        Search search = new FulltextSearch();
        search.setOptions(opt);

        List<Hit> hits = search.search();
        assertEquals(2, hits.size());

        // Search in another tree
        opt.setFolderId(1301L);
        opt.setSearchInSubPath(true);

        search = new FulltextSearch();
        search.setOptions(opt);

        hits = search.search();
        assertEquals(0, hits.size());

        // Search in single folder
        opt.setFolderId(3000L);
        opt.setSearchInSubPath(false);

        search = new FulltextSearch();
        search.setOptions(opt);

        hits = search.search();
        assertEquals(0, hits.size());
    }

    /**
     * Healdan (M6 spec 9.5): a window past the search handler's default of
     * 1,000 rows reaches Solr
     */
    @Test
    public void testWindowPastAThousand() throws Exception {
        addCopies(1200, 100);

        Search search = new FulltextSearch();
        search.setOptions(kestrel(2000));
        List<Hit> hits = search.search();
        assertEquals(1100, hits.size());
        assertFalse(search.isMoreHitsPresent());
    }

    /**
     * Healdan (M6 spec 9.5): a maximum of 1,000 or less still reads the
     * handler's 1,000 rows, so the hits dropped after Solr answers don't
     * leave a page short
     */
    @Test
    public void testSmallMaxHitsAsBefore() throws Exception {
        addCopies(1200, 100);

        Search search = new FulltextSearch();
        search.setOptions(kestrel(40));
        List<Hit> hits = search.search();
        assertEquals(40, hits.size());
        assertTrue(search.isMoreHitsPresent());
        // The deleted copies, found first, are left out
        assertTrue(hits.stream().allMatch(hit -> hit.getId() >= 10100L));
    }

    private static FulltextSearchOptions kestrel(int maxHits) {
        FulltextSearchOptions opt = new FulltextSearchOptions();
        opt.setExpression("kestrel");
        opt.setExpressionLanguage("en");
        opt.setType(SearchOptions.TYPE_FULLTEXT);
        opt.setUserId(1);
        opt.setMaxHits(maxHits);
        return opt;
    }

    /**
     * Copies document 1 with JDBC as documents 10000 onwards, all holding
     * the word kestrel, and indexes them in one commit, in order, so that
     * the first ones, which are deleted, score as the others and come first
     */
    private void addCopies(int count, int deleted) throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(Context.get(DataSource.class));
        Map<String, Object> row = jdbc.queryForMap("select * from ld_document where ld_id = 1");
        List<String> columns = new ArrayList<>(row.keySet());
        List<Object[]> rows = new ArrayList<>();
        List<SolrInputDocument> hits = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            long id = 10000L + i;
            Map<String, Object> copy = new LinkedHashMap<>();
            for (String col : columns)
                copy.put(col.toUpperCase(), row.get(col));
            copy.put("LD_ID", id);
            copy.put("LD_CUSTOMID", "k" + id);
            copy.put("LD_FILENAME", "k%d.pdf".formatted(id));
            copy.put("LD_DELETED", i < deleted ? 1 : 0);
            rows.add(columns.stream().map(col -> copy.get(col.toUpperCase())).toArray());

            SolrInputDocument hit = new SolrInputDocument();
            hit.addField(HitField.ID.getName(), Long.toString(id));
            hit.addField(HitField.TENANT_ID.getName(), "1");
            hit.addField(HitField.LANGUAGE.getName(), "en");
            hit.addField(HitField.FILENAME.getName(), "k%d.pdf".formatted(id));
            hit.addField(HitField.SIZE.getName(), 1356L);
            hit.addField(HitField.FOLDER_ID.getName(), 6L);
            hit.addField(HitField.CONTENT.getName(), "A kestrel hovers");
            hits.add(hit);
        }
        jdbc.batchUpdate("insert into ld_document (%s) values (%s)".formatted(String.join(", ", columns),
                String.join(", ", columns.stream().map(col -> "?").toList())), rows);
        SolrClient server = (SolrClient) testSubject.getServer();
        server.add(hits);
        server.commit();
    }
}