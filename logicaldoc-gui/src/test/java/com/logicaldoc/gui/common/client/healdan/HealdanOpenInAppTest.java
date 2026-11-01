package com.logicaldoc.gui.common.client.healdan;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Which documents the web page's Open in App offers (M3 spec 8, M3.6 spec
 * 5.1): the helper's office kinds, and PDFs.
 */
public class HealdanOpenInAppTest {

	@Test
	public void testOffersPdfsInAnyCase() {
		assertTrue(HealdanOpenInApp.handles("Signed NDA.pdf"));
		assertTrue(HealdanOpenInApp.handles("SCAN.PDF"));
	}

	@Test
	public void testStillOffersTheOfficeKinds() {
		assertTrue(HealdanOpenInApp.handles("Lease.docx"));
		assertTrue(HealdanOpenInApp.handles("Plan.odg"));
	}

	@Test
	public void testOffersNothingElse() {
		assertFalse(HealdanOpenInApp.handles("Photo.png"));
		assertFalse(HealdanOpenInApp.handles("pdf"));
		assertFalse(HealdanOpenInApp.handles("a."));
		assertFalse(HealdanOpenInApp.handles(null));
	}
}
