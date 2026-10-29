package com.logicaldoc.healdan.seed.demo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.logicaldoc.healdan.seed.demo.DemoManifest.Step;

public class DemoManifestTest {

	private static DemoManifest read(String json) throws Exception {
		Path file = Files.createTempFile("manifest-", ".json");
		Files.writeString(file, json);
		try {
			return DemoManifest.read(file);
		} finally {
			Files.delete(file);
		}
	}

	@Test
	public void testMissingListsReadAsEmpty() throws Exception {
		DemoManifest manifest = read("{}");
		assertTrue(manifest.groups().isEmpty());
		assertTrue(manifest.users().isEmpty());
		assertTrue(manifest.timeline().isEmpty());
	}

	@Test
	public void testAStepReadsItsKeywordFields() throws Exception {
		DemoManifest manifest = read("""
				{"timeline": [{"day": -3, "time": "14:30", "as": "pnair", "do": "file", "class": "Correspondence",
				"tags": ["charter"], "release": true}]}
				""");
		Step step = manifest.timeline().get(0);
		assertEquals(-3, step.day());
		assertEquals("14:30", step.time());
		assertEquals("pnair", step.as());
		assertEquals("file", step.action());
		assertEquals("Correspondence", step.docClass());
		assertEquals("charter", step.tags().get(0));
		assertTrue(step.release());
		assertTrue(step.to().isEmpty());
	}

	@Test
	public void testAFolderWithoutAnAclInherits() throws Exception {
		DemoManifest manifest = read("""
				{"templates": [{"name": "Litigation", "folders": [{"name": "Drafts"}]}]}
				""");
		assertNull(manifest.templates().get(0).folders().get(0).acl());
		assertTrue(manifest.templates().get(0).searchFolders().isEmpty());
	}

	@Test
	public void testAMisspeltFieldIsRefused() {
		assertThrows(UnrecognizedPropertyException.class, () -> read("{\"timeline\": [{\"dya\": -3}]}"));
	}

	@Test
	public void testAMatterWithoutAnAclKeepsItsTemplates() throws Exception {
		DemoManifest manifest = read("{\"matters\": [{\"ref\": \"1001.1\", \"name\": \"Charter Dispute\"}]}");
		assertNull(manifest.matters().get(0).acl());
	}
}
