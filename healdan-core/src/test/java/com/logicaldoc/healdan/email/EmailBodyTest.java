// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.email;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Random;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.Test;

import com.logicaldoc.core.communication.EMail;
import com.logicaldoc.core.communication.MailUtil;

import jakarta.mail.internet.MimeMessage;

/**
 * M6 spec §9.4: an email's body made safe for a page of its own.
 */
public class EmailBodyTest {

	private static MimeMessage parse(byte[] eml) throws Exception {
		MimeMessage message = MailUtil.readMime(new ByteArrayInputStream(eml));
		assertNotNull(message);
		return message;
	}

	private static MimeMessage fixture() throws Exception {
		return parse(Files.readAllBytes(Path.of("src/test/resources/email/body-hostile.eml")));
	}

	private static Document page(String html) {
		return Jsoup.parse(html);
	}

	@Test
	public void testNothingThatRunsOrFetchesRemains() throws Exception {
		String html = EmailBody.clean(fixture(), false);
		Document page = page(html);

		assertTrue(page.select("script").isEmpty());
		assertTrue(page.select("form, input, button, iframe, object, embed, base").isEmpty());
		assertTrue(page.select("meta[http-equiv]").isEmpty());
		assertTrue(page.select("[onload], [onclick]").isEmpty());
		for (Element element : page.getAllElements())
			for (org.jsoup.nodes.Attribute attribute : element.attributes())
				assertFalse(attribute.getKey(), attribute.getKey().toLowerCase().startsWith("on"));
		assertFalse(html.contains("alert("));
		assertFalse(html.contains("evil.invalid"));
	}

	@Test
	public void testLinks() throws Exception {
		Document page = page(EmailBody.clean(fixture(), false));

		Element bad = page.selectFirst("a:containsOwn(Bad link)");
		assertNotNull(bad);
		assertFalse(bad.hasAttr("href"));
		for (String link : new String[] { "https://example.com/page", "mailto:ann@example.com" }) {
			Element good = page.selectFirst("a[href='" + link + "']");
			assertNotNull(link, good);
			assertEquals("_blank", good.attr("target"));
			assertEquals("noopener noreferrer", good.attr("rel"));
		}
	}

	@Test
	public void testTrackingPictureIsGoneUnlessAsked() throws Exception {
		String without = EmailBody.clean(fixture(), false);
		String with = EmailBody.clean(fixture(), true);

		assertFalse(without.contains("tracker.invalid"));
		assertNotNull(page(without).selectFirst("img[alt=tracker]"));
		assertFalse(page(without).selectFirst("img[alt=tracker]").hasAttr("src"));
		assertEquals("https://tracker.invalid/pixel.gif", page(with).selectFirst("img[alt=tracker]").attr("src"));
	}

	@Test
	public void testPictureTheMessageCarriesIsEmbedded() throws Exception {
		Element logo = page(EmailBody.clean(fixture(), false)).selectFirst("img[alt=Logo]");

		assertNotNull(logo);
		assertTrue(logo.attr("src"), logo.attr("src").startsWith("data:image/png;base64,iVBORw0KGgo"));
	}

	@Test
	public void testPictureTheMessageDoesNotCarryIsAPlaceholder() throws Exception {
		Document page = page(EmailBody.clean(fixture(), false));

		assertTrue(page.select("img[alt=Missing]").isEmpty());
		assertTrue(page.text().contains(EmailBody.PLACEHOLDER.substring(0, 9)));
	}

	/** A message whose HTML names these pictures by cid, each carried inline with this many bytes */
	private static MimeMessage withPictures(int... sizes) throws Exception {
		StringBuilder html = new StringBuilder("<html><body>");
		StringBuilder parts = new StringBuilder();
		Random random = new Random(7);
		for (int i = 0; i < sizes.length; i++) {
			html.append("<img alt=\"pic").append(i).append("\" src=\"cid:p").append(i).append("@x\">");
			byte[] bytes = new byte[sizes[i]];
			random.nextBytes(bytes);
			parts.append("--b\nContent-Type: image/png\nContent-Transfer-Encoding: base64\n")
					.append("Content-Disposition: inline\nContent-ID: <p").append(i).append("@x>\n\n")
					.append(Base64.getMimeEncoder().encodeToString(bytes)).append("\n");
		}
		html.append("</body></html>");
		String eml = "From: a@example.com\nTo: b@example.com\nSubject: Pictures\nMIME-Version: 1.0\n"
				+ "Content-Type: multipart/related; boundary=\"b\"\n\n--b\nContent-Type: text/html; charset=utf-8\n\n"
				+ html + "\n" + parts + "--b--\n";
		return parse(eml.getBytes(StandardCharsets.UTF_8));
	}

	@Test
	public void testOverLimitPictureIsAPlaceholder() throws Exception {
		Document page = page(EmailBody.clean(withPictures(EmailBody.INLINE_LIMIT + 1), false));

		assertTrue(page.select("img").isEmpty());
		assertTrue(page.text(), page.text().contains("[Picture not shown"));
	}

	@Test
	public void testLimitCountsAllThePicturesOfAMessage() throws Exception {
		int each = EmailBody.INLINE_LIMIT / 2 + 1000;

		Document page = page(EmailBody.clean(withPictures(each, each), false));

		assertEquals(1, page.select("img[src^=data:image/png]").size());
		assertTrue(page.text().contains("[Picture not shown"));
	}

	@Test
	public void testPictureAtTheLimitIsEmbedded() throws Exception {
		Document page = page(EmailBody.clean(withPictures(EmailBody.INLINE_LIMIT), false));

		assertEquals(1, page.select("img[src^=data:image/png]").size());
	}

	@Test
	public void testStylesStayWithNothingThatFetches() throws Exception {
		String eml = "From: a@example.com\nSubject: Styled\nMIME-Version: 1.0\nContent-Type: text/html; charset=utf-8\n\n"
				+ "<html><head><style>@import url(https://css.invalid/a.css); p { color: red }</style>"
				+ "<style>div { background: url(https://tracker.invalid/bg.png) } h1 { color: blue }</style>"
				+ "<style>.x { background: u\\72l(https://tracker.invalid/esc.png) }</style></head><body>"
				+ "<p style=\"color: green; background-image: url('https://tracker.invalid/p.png')\">Styled</p>"
				+ "<p style=\"background: url(data:image/png;base64,AAAA)\">Data</p></body></html>";

		String html = EmailBody.clean(parse(eml.getBytes(StandardCharsets.UTF_8)), false);

		assertFalse(html, html.contains("tracker.invalid"));
		assertFalse(html, html.contains("css.invalid"));
		assertFalse(html, html.toLowerCase().contains("@import"));
		assertTrue(html, html.contains("color: green") || html.contains("color:green"));
		assertTrue(html, html.contains("h1 { color: blue }"));
		assertTrue(html, html.contains("p { color: red }"));
		assertTrue(html, html.contains("data:image/png;base64,AAAA"));
	}

	@Test
	public void testInternetPicturesInStylesFollowThePictures() throws Exception {
		String eml = "From: a@example.com\nSubject: Styled\nMIME-Version: 1.0\nContent-Type: text/html; charset=utf-8\n\n"
				+ "<html><body><p style=\"background-image: url('https://tracker.invalid/p.png')\">Styled</p>"
				+ "</body></html>";

		assertTrue(EmailBody.clean(parse(eml.getBytes(StandardCharsets.UTF_8)), true).contains("tracker.invalid/p.png"));
	}

	@Test
	public void testPlainTextBecomesEscapedTextInAPre() throws Exception {
		String eml = "From: a@example.com\nSubject: Plain\nMIME-Version: 1.0\nContent-Type: text/plain; charset=utf-8\n\n"
				+ "Hello <b>Bob</b> & <script>alert(1)</script>\nSecond line\n";

		String html = EmailBody.clean(parse(eml.getBytes(StandardCharsets.UTF_8)), false);
		Document page = page(html);

		assertEquals(1, page.select("pre").size());
		assertTrue(page.selectFirst("pre").text().contains("Hello <b>Bob</b> & <script>alert(1)</script>"));
		assertTrue(page.select("script, b").isEmpty());
		assertTrue(html, html.contains("&lt;b&gt;Bob&lt;/b&gt; &amp; &lt;script&gt;"));
	}

	@Test
	public void testHtmlIsPreferredToTextInAnAlternative() throws Exception {
		String eml = "From: a@example.com\nSubject: Both\nMIME-Version: 1.0\n"
				+ "Content-Type: multipart/alternative; boundary=\"b\"\n\n--b\nContent-Type: text/plain\n\nthe text\n"
				+ "--b\nContent-Type: text/html\n\n<p>the <i>html</i></p>\n--b--\n";

		Document page = page(EmailBody.clean(parse(eml.getBytes(StandardCharsets.UTF_8)), false));

		assertEquals("the html", page.selectFirst("p").text());
		assertTrue(page.select("pre").isEmpty());
	}

	@Test
	public void testAnAttachedFileIsNotTheBody() throws Exception {
		String eml = "From: a@example.com\nSubject: Attached\nMIME-Version: 1.0\n"
				+ "Content-Type: multipart/mixed; boundary=\"b\"\n\n--b\nContent-Type: text/plain\n\nthe text\n"
				+ "--b\nContent-Type: text/plain\nContent-Disposition: attachment; filename=\"notes.txt\"\n\nnotes\n--b--\n";

		Document page = page(EmailBody.clean(parse(eml.getBytes(StandardCharsets.UTF_8)), false));

		assertEquals("the text", page.selectFirst("pre").text());
	}

	@Test
	public void testNoBodyShowsANote() throws Exception {
		String eml = "From: a@example.com\nSubject: Empty\nMIME-Version: 1.0\nContent-Type: application/pdf\n\n%PDF\n";

		assertTrue(page(EmailBody.clean(parse(eml.getBytes(StandardCharsets.UTF_8)), false)).text()
				.contains(EmailBody.NO_BODY));
	}

	@Test
	public void testPolicy() {
		assertEquals("sandbox allow-popups allow-popups-to-escape-sandbox; default-src 'none'; "
				+ "style-src 'unsafe-inline'; img-src data:; font-src data:; frame-ancestors 'self'",
				EmailBody.policy(false));
		assertEquals("sandbox allow-popups allow-popups-to-escape-sandbox; default-src 'none'; "
				+ "style-src 'unsafe-inline'; img-src data: https:; font-src data:; frame-ancestors 'self'",
				EmailBody.policy(true));
	}

	@Test
	public void testAMsgIsShownAsItsHtmlOrItsText() throws Exception {
		File msg = new File("src/test/resources/email/test.msg");
		EMail mail = MailUtil.msgToMail(msg, true);

		String html = EmailBody.clean(mail, false);
		Document page = page(html);

		assertTrue(html, page.text().length() > 0);
		assertTrue(page.select("script, iframe, form").isEmpty());
		if (mail.isHtml())
			assertTrue(page.select("pre").isEmpty());
		else
			assertEquals(1, page.select("pre").size());
	}

	@Test
	public void testAMsgsHtmlIsCleaned() {
		EMail mail = new EMail();
		mail.setHtml(true);
		mail.setMessageText("<html><body onload=\"x()\"><script>x()</script><p>Hi</p>"
				+ "<img src=\"https://tracker.invalid/p.gif\"><img src=\"cid:a@b\" alt=\"Inline\"></body></html>");

		String html = EmailBody.clean(mail, false);

		assertFalse(html, html.contains("script"));
		assertFalse(html, html.contains("onload"));
		assertFalse(html, html.contains("tracker.invalid"));
		assertTrue(html, html.contains("Hi"));
		assertTrue(html, html.contains("Inline"));
	}

	private static String emlOf(String html) {
		return "From: a@example.com\nSubject: Styled\nMIME-Version: 1.0\nContent-Type: text/html; charset=utf-8\n\n" + html;
	}

	@Test
	public void testAnUnclosedUrlInAStyleAttributeKeepsNoAddress() throws Exception {
		for (boolean pictures : new boolean[] { false, true }) {
			String html = EmailBody.clean(parse(emlOf(
					"<p style=\"color: red; background:url(https://tracker.invalid/p.gif\">Styled</p>").getBytes(
							StandardCharsets.UTF_8)), pictures);
			assertFalse(html, html.contains("tracker.invalid"));
		}
	}

	@Test
	public void testAnUnclosedUrlEndingAStyleElementKeepsNoAddress() throws Exception {
		String html = EmailBody.clean(parse(emlOf(
				"<html><head><style>p { color: red } div { background: url(https://tracker.invalid/x</style></head>"
						+ "<body><p>Hi</p></body></html>").getBytes(StandardCharsets.UTF_8)), false);

		assertFalse(html, html.contains("tracker.invalid"));
	}

	@Test
	public void testHeadStylesKeepTheirOrder() throws Exception {
		String html = EmailBody.clean(parse(emlOf("<html><head><style>.a{color:red}</style><style>.b{color:blue}</style>"
				+ "<style>.c{color:green}</style></head><body><p>Hi</p></body></html>").getBytes(StandardCharsets.UTF_8)),
				false);

		assertTrue(html, html.indexOf(".a{") < html.indexOf(".b{"));
		assertTrue(html, html.indexOf(".b{") < html.indexOf(".c{"));
	}

	@Test
	public void testAnFtpLinkHasNoHref() throws Exception {
		Document page = page(EmailBody.clean(parse(emlOf("<a href=\"ftp://files.example.com/a\">ftp</a>"
				+ "<a href=\"https://example.com/\">web</a>").getBytes(StandardCharsets.UTF_8)), false));

		assertFalse(page.selectFirst("a:containsOwn(ftp)").hasAttr("href"));
		assertTrue(page.selectFirst("a:containsOwn(web)").hasAttr("href"));
	}

	@Test
	public void testThePageSendsNoReferrer() throws Exception {
		Document page = page(EmailBody.clean(fixture(), true));

		assertEquals("no-referrer", page.selectFirst("meta[name=referrer]").attr("content"));
	}

	@Test
	public void testAnOpaqueSignedMessageShowsItsBody() throws Exception {
		byte[] entity = "Content-Type: text/html; charset=utf-8\r\n\r\n<p>The signed body</p>"
				.getBytes(StandardCharsets.US_ASCII);
		byte[] signature = new org.bouncycastle.cms.CMSSignedDataGenerator()
				.generate(new org.bouncycastle.cms.CMSProcessableByteArray(entity), true).getEncoded();
		java.io.ByteArrayOutputStream eml = new java.io.ByteArrayOutputStream();
		eml.writeBytes(("From: a@example.com\r\nSubject: Signed\r\nMIME-Version: 1.0\r\n"
				+ "Content-Type: application/pkcs7-mime; smime-type=signed-data; name=\"smime.p7m\"\r\n"
				+ "Content-Transfer-Encoding: binary\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
		eml.writeBytes(signature);

		Document page = page(EmailBody.clean(parse(eml.toByteArray()), false));

		assertEquals("The signed body", page.selectFirst("p").text());
	}
}
