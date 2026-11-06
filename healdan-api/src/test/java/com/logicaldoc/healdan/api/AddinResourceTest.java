// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.Before;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.logicaldoc.util.config.ContextProperties;
import com.logicaldoc.util.spring.Context;

public class AddinResourceTest extends ApiTestCase {

	private static final String SERVER_URL = "server.url";

	private String savedServerUrl;

	@Before
	public void saveTheAddress() {
		savedServerUrl = Context.get().getConfig().getProperty(SERVER_URL);
	}

	@Override
	public void tearDown() throws IOException {
		ContextProperties config = Context.get().getConfig();
		if (savedServerUrl == null)
			config.remove(SERVER_URL);
		else
			config.setProperty(SERVER_URL, savedServerUrl);
		super.tearDown();
	}

	@Test
	public void testManifestNamesThisServer() {
		// The test context's server.url is http://localhost:8080
		ApiResponse response = get("/api/addin/manifest.xml").send();

		assertEquals(response.toString(), 200, response.status());
		assertEquals("application/xml; charset=UTF-8", contentType(response));
		String manifest = response.text();
		assertTrue(manifest, manifest.contains("http://localhost:8080/api/addin/taskpane.html"));
		assertTrue(manifest, manifest.contains("http://localhost:8080/api/addin/commands.html"));
		assertTrue(manifest, manifest.contains("<AppDomain>http://localhost:8080</AppDomain>"));
		assertFalse(manifest, manifest.contains("{{"));
		assertTrue(manifest, manifest.contains("<Id>7b0f3a52-8d1e-4c4b-9a6e-2f5d0c8e4a91</Id>"));
		assertEquals("nosniff", response.header("X-Content-Type-Options"));
	}

	@Test
	public void testEveryResidNamesAResource() throws Exception {
		assertEveryResidNamesAResource("manifest.xml", 100);
		// The two version overrides each carry their own resources
		assertEveryResidNamesAResource("outlook-manifest.xml", 12);
	}

	private void assertEveryResidNamesAResource(String file, int least) throws Exception {
		// Office refuses a manifest whose resid names no resource, and only says so once it's uploaded, so the served
		// manifest is read as XML here: every resid is the id of a bt:Image, bt:Url or bt:String in its Resources.
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		factory.setNamespaceAware(true);
		factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
		Document manifest = factory.newDocumentBuilder()
				.parse(new ByteArrayInputStream(get("/api/addin/" + file).send().body()));

		// Each VersionOverrides has Resources of its own (Outlook's manifest nests a second, newer one in the first), and
		// its resids name only those.
		String basicTypes = "http://schemas.microsoft.com/office/officeappbasictypes/1.0";
		Map<Node, Map<String, String>> resources = new HashMap<>();
		for (String kind : new String[] { "Image", "Url", "String" }) {
			NodeList found = manifest.getElementsByTagNameNS(basicTypes, kind);
			for (int i = 0; i < found.getLength(); i++) {
				Element resource = (Element) found.item(i);
				if (resource.hasAttribute("id"))
					assertNull("two resources are " + resource.getAttribute("id"),
							resources.computeIfAbsent(overrides(resource), o -> new HashMap<>())
									.put(resource.getAttribute("id"), kind));
			}
		}

		NodeList all = manifest.getElementsByTagName("*");
		int resids = 0;
		for (int i = 0; i < all.getLength(); i++) {
			Element element = (Element) all.item(i);
			if (!element.hasAttribute("resid"))
				continue;
			String resid = element.getAttribute("resid");
			assertTrue(element.getTagName() + " names " + resid + ", which no bt: resource is",
					resources.getOrDefault(overrides(element), Map.of()).containsKey(resid));
			resids++;
		}
		// Each app's group, its buttons' labels, tips and icons, and the function file
		assertTrue(file + " resids: " + resids, resids > least);
	}

	/**
	 * @return the nearest VersionOverrides around the element
	 */
	private static Node overrides(Element element) {
		Node node = element.getParentNode();
		while (node != null && !"VersionOverrides".equals(node.getLocalName()))
			node = node.getParentNode();
		return node;
	}

	@Test
	public void testOutlookManifestNamesThisServer() {
		ApiResponse response = get("/api/addin/outlook-manifest.xml").send();

		assertEquals(response.toString(), 200, response.status());
		assertEquals("application/xml; charset=UTF-8", contentType(response));
		String manifest = response.text();
		assertTrue(manifest, manifest.contains("http://localhost:8080/api/addin/mail.html"));
		assertTrue(manifest, manifest.contains("<AppDomain>http://localhost:8080</AppDomain>"));
		assertFalse(manifest, manifest.contains("{{"));
		assertEquals("nosniff", response.header("X-Content-Type-Options"));
	}

	@Test
	public void testOutlookManifestUsesThePublicAddress() {
		Context.get().getConfig().setProperty(SERVER_URL, "https://healdan.example/");

		String manifest = get("/api/addin/outlook-manifest.xml").send().text();

		assertTrue(manifest, manifest.contains("https://healdan.example/api/addin/mail.html"));
		assertTrue(manifest, manifest.contains("<AppDomain>https://healdan.example</AppDomain>"));
		assertFalse(manifest, manifest.contains("https://healdan.example//"));
		assertFalse(manifest, manifest.contains("localhost"));
	}

	@Test
	public void testOutlookManifestRefusesPlainHttpElsewhere() {
		Context.get().getConfig().remove(SERVER_URL);

		ApiResponse response = get("/api/addin/outlook-manifest.xml").server("http", "healdan.example", 80).send();

		assertEquals(response.toString(), 409, response.status());
		assertEquals("no-public-address", response.code());
	}

	@Test
	public void testOutlookManifestIsAMailApp() {
		String manifest = get("/api/addin/outlook-manifest.xml").send().text();

		assertTrue(manifest, manifest.contains("xsi:type=\"MailApp\""));
		assertTrue(manifest, manifest.contains("<Permissions>ReadWriteMailbox</Permissions>"));
		// getAsFileAsync, which filing needs, is Mailbox 1.14: an Outlook without it doesn't show the add-in
		assertTrue(manifest, manifest.contains("<Set Name=\"Mailbox\" MinVersion=\"1.14\"/>"));
		assertTrue(manifest, manifest.contains("<Host Name=\"Mailbox\"/>"));
		assertTrue(manifest, manifest.contains("xsi:type=\"MessageReadCommandSurface\""));
		assertTrue(manifest, manifest.contains("<Action xsi:type=\"ShowTaskpane\">"));
		assertTrue(manifest, manifest.contains("<SupportsPinning>true</SupportsPinning>"));
		// The trial: new Outlook for Mac can't read selected messages (T4), so the add-in doesn't declare several.
		assertFalse(manifest, manifest.contains("SupportsMultiSelect"));
		// Pinning is in the V1_1 override's ShowTaskpane action, and the pane it opens is the V1_1 one.
		Matcher pinning = Pattern.compile("VersionOverridesV1_1.*<Action xsi:type=\"ShowTaskpane\">\\s*<SourceLocation resid=\"Pane.Url\"/>\\s*<SupportsPinning>true</SupportsPinning>\\s*</Action>", Pattern.DOTALL).matcher(manifest);
		assertTrue(manifest, pinning.find());
		assertTrue(manifest, manifest.contains("mail.html?v=1_1"));
		assertTrue(manifest, manifest.contains("See whether this message is in Healdan, and file it."));
		assertTrue(manifest, manifest.contains("DefaultValue=\"http://localhost:8080/api/addin/assets/healdan-128.png\""));
	}

	@Test
	public void testTheTwoManifestsHaveDifferentIds() {
		Pattern id = Pattern.compile("<Id>([^<]+)</Id>");
		Matcher word = id.matcher(get("/api/addin/manifest.xml").send().text());
		Matcher outlook = id.matcher(get("/api/addin/outlook-manifest.xml").send().text());

		assertTrue(word.find());
		assertTrue(outlook.find());
		assertFalse(word.group(1).equals(outlook.group(1)));
	}

	@Test
	public void testEveryAddressTheOutlookManifestNamesIsServed() {
		String manifest = get("/api/addin/outlook-manifest.xml").send().text();
		Matcher addresses = Pattern.compile("DefaultValue=\"http://localhost:8080/api/addin/([^\"?]+)[^\"]*\"")
				.matcher(manifest);

		int count = 0;
		while (addresses.find()) {
			assertEquals(addresses.group(1), 200, get("/api/addin/" + addresses.group(1)).send().status());
			count++;
		}
		assertTrue("addresses: " + count, count >= 8);
	}

	@Test
	public void testManifestHasTheButtonsOfEachApp() {
		String manifest = get("/api/addin/manifest.xml").send().text();

		for (String function : new String[] { "saveToHealdan", "openFromHealdan", "saveAsNewVersion",
				"saveAsNewDocument", "properties", "insertDocId" })
			assertTrue(function, manifest.contains("<FunctionName>" + function + "</FunctionName>"));
		for (String host : new String[] { "Document", "Workbook", "Presentation" }) {
			assertTrue(host, manifest.contains("<Host Name=\"" + host + "\"/>"));
			assertTrue(host, manifest.contains("<Host xsi:type=\"" + host + "\">"));
		}
		// Insert Doc ID is Word's only
		assertEquals(1, manifest.split("<FunctionName>insertDocId</FunctionName>", -1).length - 1);
		assertEquals(3, manifest.split("<FunctionName>saveToHealdan</FunctionName>", -1).length - 1);
	}

	@Test
	public void testManifestUsesThePublicAddress() {
		Context.get().getConfig().setProperty(SERVER_URL, "https://healdan.example/");

		String manifest = get("/api/addin/manifest.xml").send().text();

		assertTrue(manifest, manifest.contains("https://healdan.example/api/addin/commands.html"));
		assertTrue(manifest, manifest.contains("<AppDomain>https://healdan.example</AppDomain>"));
		assertFalse(manifest, manifest.contains("https://healdan.example//"));
		assertFalse(manifest, manifest.contains("localhost"));
	}

	@Test
	public void testManifestOfAnAddressWithAPath() {
		Context.get().getConfig().setProperty(SERVER_URL, "https://healdan.example:8443/dms/");

		String manifest = get("/api/addin/manifest.xml").send().text();

		assertTrue(manifest, manifest.contains("https://healdan.example:8443/dms/api/addin/taskpane.html"));
		// An app domain is an origin
		assertTrue(manifest, manifest.contains("<AppDomain>https://healdan.example:8443</AppDomain>"));
	}

	@Test
	public void testManifestFollowsTheRequestWithoutAPublicAddress() {
		Context.get().getConfig().remove(SERVER_URL);

		String behindTheProxy = get("/api/addin/manifest.xml").server("https", "healdan.example", 443).send().text();
		assertTrue(behindTheProxy, behindTheProxy.contains("https://healdan.example/api/addin/commands.html"));
		assertTrue(behindTheProxy, behindTheProxy.contains("<AppDomain>https://healdan.example</AppDomain>"));

		String withAPort = get("/api/addin/manifest.xml").server("https", "healdan.example", 8443).send().text();
		assertTrue(withAPort, withAPort.contains("<AppDomain>https://healdan.example:8443</AppDomain>"));

		// What a stack on one's own machine is
		ApiResponse local = get("/api/addin/manifest.xml").server("http", "127.0.0.1", 8080).send();
		assertEquals(local.toString(), 200, local.status());
		assertTrue(local.text(), local.text().contains("<AppDomain>http://127.0.0.1:8080</AppDomain>"));

		ApiResponse ipv6 = get("/api/addin/manifest.xml").server("http", "[::1]", 8080).send();
		assertEquals(ipv6.toString(), 200, ipv6.status());
		assertTrue(ipv6.text(), ipv6.text().contains("<AppDomain>http://[::1]:8080</AppDomain>"));
	}

	@Test
	public void testLogicalDOCsPlaceholderIsNoPublicAddress() {
		// An image's settings keep LogicalDOC's default until LOGICALDOC_PUBLIC_URL is set
		Context.get().getConfig().setProperty(SERVER_URL, "http://server:port/");

		ApiResponse local = get("/api/addin/manifest.xml").server("http", "127.0.0.1", 8080).send();
		assertEquals(local.toString(), 200, local.status());
		assertTrue(local.text(), local.text().contains("<AppDomain>http://127.0.0.1:8080</AppDomain>"));

		String behindTheProxy = get("/api/addin/manifest.xml").server("https", "healdan.example", 443).send().text();
		assertTrue(behindTheProxy, behindTheProxy.contains("<AppDomain>https://healdan.example</AppDomain>"));
	}

	@Test
	public void testManifestRefusesPlainHttpElsewhere() {
		Context.get().getConfig().remove(SERVER_URL);

		ApiResponse response = get("/api/addin/manifest.xml").server("http", "healdan.example", 80).send();

		assertEquals(response.toString(), 409, response.status());
		assertEquals("no-public-address", response.code());
		String detail = response.json().path("detail").textValue();
		assertTrue(detail, detail.contains("LOGICALDOC_PUBLIC_URL"));
		assertTrue(detail, detail.contains("https://"));
	}

	@Test
	public void testManifestRefusesAPlainHttpPublicAddress() {
		Context.get().getConfig().setProperty(SERVER_URL, "http://healdan.example/");

		ApiResponse response = get("/api/addin/manifest.xml").send();

		assertEquals(response.toString(), 409, response.status());
		assertEquals("no-public-address", response.code());
	}

	@Test
	public void testManifestRefusesAnAddressItCantWriteIntoXml() {
		Context.get().getConfig().setProperty(SERVER_URL, "https://healdan.example\"><x/>");

		ApiResponse response = get("/api/addin/manifest.xml").send();

		assertEquals(response.toString(), 409, response.status());
		assertEquals("no-public-address", response.code());
	}

	@Test
	public void testPages() {
		for (String page : new String[] { "taskpane.html", "commands.html", "mail.html" }) {
			ApiResponse response = get("/api/addin/" + page).send();

			assertEquals(page + " " + response, 200, response.status());
			assertEquals(page, "text/html; charset=UTF-8", contentType(response));
			String policy = response.header("Content-Security-Policy");
			// Outlook for Mac's Office.js loads MicrosoftAjax.js from Microsoft's ASP.NET CDN, and stops if refused
			assertTrue(page + " " + policy,
					policy.contains("script-src 'self' https://appsforoffice.microsoft.com https://ajax.aspnetcdn.com;"));
			assertTrue(page + " " + policy, policy.contains(
					"connect-src 'self' https://127.0.0.1:47615 https://127.0.0.1:47616 https://127.0.0.1:47617 https://127.0.0.1:47618"));
			assertTrue(page + " " + policy, policy.startsWith("default-src 'self';"));
			assertEquals(page, "nosniff", response.header("X-Content-Type-Options"));
			assertTrue(page, response.text().contains("<title>Healdan</title>"));
		}
	}

	@Test
	public void testScriptsAndStyles() {
		for (String file : new String[] { "healdan.js", "office-glue.js", "taskpane.js", "commands.js", "healdan.css" }) {
			ApiResponse response = get("/api/addin/" + file).send();

			assertEquals(file + " " + response, 200, response.status());
			assertEquals(file, file.endsWith(".css") ? "text/css; charset=UTF-8" : "text/javascript; charset=UTF-8",
					contentType(response));
			assertEquals(file, "nosniff", response.header("X-Content-Type-Options"));
			assertTrue(file, response.text().length() > 100);
		}
	}

	@Test
	public void testEverythingThePagesLoadIsServed() {
		int count = 0;
		for (String page : new String[] { "taskpane.html", "commands.html" }) {
			String html = get("/api/addin/" + page).send().text();
			Matcher references = Pattern.compile("(?:src|href)=\"([^\"]+)\"").matcher(html);
			while (references.find()) {
				String reference = references.group(1);
				// Office.js is Microsoft's, and the Content-Security-Policy allows its address
				if (reference.startsWith("https://appsforoffice.microsoft.com/"))
					continue;
				assertFalse(page + " " + reference, reference.contains("://") || reference.startsWith("/"));
				assertEquals(page + " " + reference, 200, get("/api/addin/" + reference).send().status());
				count++;
			}
		}
		// The pane: a stylesheet, the mark and two scripts; the commands: three scripts
		assertTrue("references: " + count, count >= 7);
	}

	@Test
	public void testIcons() {
		for (String icon : new String[] { "save-to-healdan-32.png", "healdan-64.png", "insert-doc-id-80.png",
				"properties-16.png" }) {
			ApiResponse response = get("/api/addin/assets/" + icon).send();

			assertEquals(icon + " " + response.status(), 200, response.status());
			assertEquals(icon, "image/png", response.header("Content-Type"));
			assertEquals(icon, "nosniff", response.header("X-Content-Type-Options"));
			// A PNG
			assertEquals(icon, (byte) 0x89, response.body()[0]);
			assertEquals(icon, 'P', response.body()[1]);
		}
	}

	@Test
	public void testEveryIconTheManifestNames() {
		assertEveryIconIsServed("manifest.xml", 3 + 6 * 3);
		// The mark for the app's two icons, and the button's three sizes, in each of two version overrides
		assertEveryIconIsServed("outlook-manifest.xml", 2 + 2 * 3);
	}

	private void assertEveryIconIsServed(String file, int least) {
		String manifest = get("/api/addin/" + file).send().text();
		Matcher icons = Pattern.compile("DefaultValue=\"[^\"]*/api/addin/(assets/[^\"]+)\"").matcher(manifest);

		int count = 0;
		while (icons.find()) {
			assertEquals(icons.group(1), 200, get("/api/addin/" + icons.group(1)).send().status());
			count++;
		}
		// The mark at three sizes for the group, six buttons at three, and the app's own two icons
		assertTrue(file + " icons: " + count, count >= least);
	}

	@Test
	public void testNothingElseIsServed() {
		for (String path : nothing())
			assertEquals(path, 404, get(path).send().status());
	}

	@Test
	public void testNoTokenNeeded() {
		// The pages are the same for everyone, and Office loads them with no sign-in
		for (String path : new String[] { "/api/addin/manifest.xml", "/api/addin/outlook-manifest.xml",
				"/api/addin/taskpane.html", "/api/addin/mail.html", "/api/addin/commands.html", "/api/addin/assets/healdan-32.png" }) {
			ApiResponse response = get(path).send();
			assertEquals(path, 200, response.status());
			assertNull(path, response.header("WWW-Authenticate"));
		}
	}

	/**
	 * @return the Content-Type, with the space after the semicolon that a
	 *         servlet container may leave out
	 */
	private static String contentType(ApiResponse response) {
		return response.header("Content-Type").replace(";", "; ").replace(";  ", "; ");
	}

	private static String[] nothing() {
		return new String[] { "/api/addin/..%2Fpom.xml", "/api/addin/..", "/api/addin/.hidden",
				"/api/addin/manifest.template.xml", "/api/addin/outlook-manifest.template.xml", "/api/addin/nothing.js", "/api/addin/assets/..%2Ftaskpane.html",
				"/api/addin/assets/nothing.png", "/api/addin/assets/healdan-32.svg" };
	}
}
