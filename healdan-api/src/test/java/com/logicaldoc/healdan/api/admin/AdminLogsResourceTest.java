// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.admin;

import static com.logicaldoc.healdan.admin.logs.LogTestHooks.append;
import static com.logicaldoc.healdan.admin.logs.LogTestHooks.entry;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.mock.web.MockHttpSession;

import com.fasterxml.jackson.databind.JsonNode;
import com.logicaldoc.healdan.admin.logs.LogTestHooks;
import com.logicaldoc.healdan.api.ApiResponse;
import com.logicaldoc.healdan.api.ApiTestCase;
import com.logicaldoc.healdan.api.ProblemMapper;
import com.logicaldoc.healdan.support.AdminRecord;
import com.logicaldoc.healdan.support.LogCapture;

/**
 * The Server Log's routes (Admin app spec section 8.8; rulings P5-R2, P5-R6 and P5-R27), each answer checked against
 * {@code openapi-admin.yaml}, over files of the tests' own that the test {@code log.xml} names: the logs, a log's
 * entries back and forward by position across the files it rolled over to, the download, a name taken only as one of
 * the logs' names, and the routes for administrators on the web session only.
 */
public class AdminLogsResourceTest extends ApiTestCase {

	private static final String LOGS = "/api/v1/admin/logs";

	private static final String LINES = LOGS + "/DMS/lines";

	private static final String SOURCE = "com.logicaldoc.healdan.preview.PreviewWorker";

	private final Instant start = Instant.parse("2026-11-20T09:30:00.250Z");

	private int written;

	private MockHttpSession web;

	private Path folder;

	private Path dms;

	@Before
	public void prepare() throws Exception {
		web = signInWeb(fx.user("boss", "admin"));
		folder = LogTestHooks.useNewFolder();
		dms = folder.resolve("dms.log");
	}

	@After
	public void putBack() throws IOException {
		LogTestHooks.reset();
	}

	@Test
	public void testTheLogs() throws Exception {
		append(dms, line("INFO", "Started"));
		append(folder.resolve("dms.log.1"), line("INFO", "Older"));
		append(folder.resolve("indexertask.log"), line("INFO", "Indexed"));
		append(folder.resolve("dms_web.log.html"), "<p>Started</p>");
		Files.createFile(folder.resolve("notifier.log"));

		ApiResponse response = get(LOGS).webSession(web).send();

		assertEquals(response.toString(), 200, response.status());
		JsonNode logs = response.json();
		assertEquals(2, logs.size());
		assertEquals("DMS", logs.path(0).path("name").textValue());
		assertEquals("Main Log", logs.path(0).path("label").textValue());
		assertEquals(Files.size(dms) + Files.size(folder.resolve("dms.log.1")), logs.path(0).path("size").asLong());
		assertTrue(logs.path(0).path("modified").isTextual());
		assertEquals("IndexerTask", logs.path(1).path("name").textValue());
		assertEquals("Indexer", logs.path(1).path("label").textValue());
	}

	@Test
	public void testTheMainLogWithNoFile() throws Exception {
		JsonNode logs = get(LOGS).webSession(web).send().json();

		assertEquals(1, logs.size());
		assertEquals(0, logs.path(0).path("size").asLong());
		assertTrue(logs.path(0).path("modified").isNull());

		JsonNode lines = get(LINES).webSession(web).send().json();
		assertEquals(0, lines.path("lines").size());
		assertTrue(lines.path("atStart").asBoolean());
		assertTrue(lines.path("atEnd").asBoolean());
		assertEquals(lines.path("before").textValue(), lines.path("after").textValue());
	}

	@Test
	public void testBackAndForward() throws Exception {
		Path rolled = folder.resolve("dms.log.1");
		for (int i = 0; i < 150; i++)
			append(rolled, line("INFO", "Old " + i));
		for (int i = 0; i < 100; i++)
			append(dms, line(i % 2 == 0 ? "INFO" : "WARN", "New " + i));
		append(dms, line("ERROR", "Can't reach https://alice:s3cret@backup.example/x"));
		append(dms, "java.io.IOException: ftp://bob:pa55@ftp.example/\n\tat a.B.c(B.java:12)\n");

		JsonNode newest = lines("");
		assertEquals(200, newest.path("lines").size());
		assertEquals("Old 51", newest.path("lines").path(0).path("message").textValue());
		assertFalse(newest.path("atStart").asBoolean());
		assertTrue(newest.path("atEnd").asBoolean());
		assertFalse(newest.path("rolled").asBoolean());
		JsonNode last = newest.path("lines").path(199);
		assertEquals("Can't reach https://backup.example/x", last.path("message").textValue());
		assertEquals(List.of("java.io.IOException: ftp://ftp.example/", "\tat a.B.c(B.java:12)"),
				texts(last.path("continuation")));
		assertEquals("ERROR", last.path("level").textValue());
		assertEquals(SOURCE + ":12", last.path("source").textValue());
		assertEquals(start.plusMillis(250).toString(), last.path("time").textValue());

		// Back into the file it rolled over to, to its start
		JsonNode older = lines("?before=" + newest.path("before").textValue());
		assertEquals(51, older.path("lines").size());
		assertEquals("Old 0", older.path("lines").path(0).path("message").textValue());
		assertTrue(older.path("atStart").asBoolean());

		// Forward from an entry, by level, then following
		String newFirst = newest.path("lines").path(99).path("position").textValue();
		assertEquals("New 0", newest.path("lines").path(99).path("message").textValue());
		JsonNode warnings = lines("?after=" + newFirst + "&level=warn");
		assertEquals(51, warnings.path("lines").size());
		assertEquals("New 1", warnings.path("lines").path(0).path("message").textValue());
		assertTrue(warnings.path("atEnd").asBoolean());
		assertEquals(List.of("Can't reach https://backup.example/x"),
				messages(lines("?after=" + newFirst + "&level=error")));
		assertEquals(List.of("New 10"), messages(lines("?after=" + newFirst + "&q=%20new%2010%20")));
		append(dms, line("WARN", "Something new"));
		JsonNode followed = lines("?after=" + newest.path("after").textValue());
		assertEquals(List.of("Something new"), messages(followed));
		assertFalse(followed.path("rolled").asBoolean());

		// The log rolls over: following goes on from the new file's start
		Files.move(dms, folder.resolve("dms.log.2"));
		append(dms, line("INFO", "Rolled"));
		JsonNode after = lines("?after=" + followed.path("after").textValue());
		assertTrue(after.path("rolled").asBoolean());
		assertEquals(List.of("Rolled"), messages(after));
	}

	@Test
	public void testARequestThatIsntOneIs400() throws Exception {
		append(dms, line("INFO", "Started"));
		String position = lines("").path("after").textValue();
		String unpadded = Base64.getUrlEncoder().withoutPadding()
				.encodeToString("0123456789abcdef0123456789abcdef.12".getBytes(StandardCharsets.US_ASCII));

		for (String query : List.of("?before=" + position + "&after=" + position, "?before=x", "?after=%21%21",
				"?after=" + position + "%3D", "?before=" + enc("0123456789abcdef.1"), "?after=" + enc("current.1"),
				"?after=" + enc("0123456789abcdef0123456789abcdef.-1"),
				"?after=" + enc("0123456789abcdef0123456789abcdef.99999999999999999999"),
				"?before=" + enc("0123456789ABCDEF0123456789ABCDEF.0"), "?level=WARN",
				"?level=info", "?level=")) {
			ApiResponse response = get(LINES + query).webSession(web).send();
			assertEquals(query + " " + response, 400, response.status());
			assertEquals("bad-request", response.code());
		}
		// A position this route gives, of a file that isn't there
		assertEquals(200, get(LINES + "?after=" + unpadded).webSession(web).send().status());
		// Before the path's 404
		ApiResponse both = get(LOGS + "/nope/lines?before=x").webSession(web).send();
		assertEquals(400, both.status());
	}

	@Test
	public void testANameIsOnlyOneOfTheLogsNames() throws Exception {
		append(dms, line("INFO", "Started"));
		append(folder.resolve("dms_web.log.html"), "<p>Started</p>");
		append(folder.resolve("dots.log"), line("INFO", "Dots"));
		Files.createFile(folder.resolve("notifier.log"));

		for (String name : List.of("..", "%2E%2E", "..%2Fdms.log", "..%2F..%2Fdms.log", "dms.log", "dms",
				URLEncoder.encode(dms.toString(), StandardCharsets.UTF_8), "%2Fetc%2Fpasswd", "DMS%00", "DMS%20",
				"DMS_WEB", "Notifier", "console", "nope")) {
			for (String route : List.of("/lines", "/file")) {
				ApiResponse response = get(LOGS + "/" + name + route).webSession(web).send();
				assertTrue(name + route + " " + response, response.status() == 404 || response.status() == 400);
				assertTrue(name + route, String.valueOf(response.header("Content-Type")).startsWith(
						"application/problem+json"));
				assertFalse(name + route, response.text().contains("Started"));
			}
		}
		assertEquals(200, get(LINES).webSession(web).send().status());
	}

	@Test
	public void testTheDownload() throws Exception {
		Path rolled = folder.resolve("dms.log.1");
		append(rolled, line("INFO", "Older https://alice:s3cret@backup.example/"));
		append(dms, line("INFO", "Current"));
		List<FileChannel> opened = LogTestHooks.keepOpened();

		// A HEAD has the answer's headers, and leaves no file open, as the body is never written
		ApiResponse head = head(LOGS + "/DMS/file").webSession(web).send();
		assertEquals(head.toString(), 200, head.status());
		assertEquals(0, head.body().length);
		assertTrue(head.header("Content-Disposition"), head.header("Content-Disposition").startsWith("attachment"));
		assertEquals("no-store", head.header("Cache-Control"));
		assertFalse(opened.isEmpty());
		assertTrue(opened.stream().noneMatch(FileChannel::isOpen));

		ApiResponse response = get(LOGS + "/DMS/file").webSession(web).send();
		assertTrue(opened.stream().noneMatch(FileChannel::isOpen));

		assertEquals(response.toString(), 200, response.status());
		assertTrue(response.header("Content-Type"), response.header("Content-Type").startsWith("application/zip"));
		assertTrue(response.header("Content-Disposition"),
				response.header("Content-Disposition").matches("attachment; filename=\"dms-\\d{8}-\\d{6}\\.zip\""));
		// The files hold what the server logged: no copy is kept
		assertEquals("no-store", response.header("Cache-Control"));
		// Nothing keeps the app's download frame from taking it (ruling P5-R38), as nothing keeps the User app's
		assertNull(response.header("X-Frame-Options"));
		assertNull(response.header("Content-Security-Policy"));
		Map<String, byte[]> entries = new LinkedHashMap<>();
		try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(response.body()))) {
			for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry())
				entries.put(entry.getName(), in.readAllBytes());
		}
		assertEquals(List.of("dms.log", "dms.log.1"), List.copyOf(entries.keySet()));
		// The files as they are
		assertArrayEquals(Files.readAllBytes(dms), entries.get("dms.log"));
		assertArrayEquals(Files.readAllBytes(rolled), entries.get("dms.log.1"));
		// A read records nothing
		assertEquals(List.of(), AdminRecord.events());
	}

	@Test
	public void testAFileThatCantBeReadIsAFault() throws Exception {
		append(dms, line("INFO", "Started"));
		LogTestHooks.refuseToOpen(dms);

		try (LogCapture capture = LogCapture.of(ProblemMapper.class)) {
			for (String route : List.of("/lines", "/file")) {
				ApiResponse response = get(LOGS + "/DMS" + route).webSession(web).send();
				assertEquals(response.toString(), 500, response.status());
				assertEquals("server-error", response.code());
			}
			assertEquals(List.of("ERROR API request failed", "ERROR API request failed"), capture.lines());
		}
	}

	@Test
	public void testEveryRouteIsForAdministratorsOnTheWebSession() throws Exception {
		append(dms, line("INFO", "Started"));
		MockHttpSession clerk = signInWeb(fx.user("clerk"));
		String token = signInAdmin();

		for (String path : List.of(LOGS, LINES, LOGS + "/DMS/file")) {
			ApiResponse refused = get(path).webSession(clerk).send();
			assertEquals(path + " " + refused, 403, refused.status());
			assertEquals("admins-only", refused.code());
			assertFalse(refused.text().contains("Started"));
			ApiResponse device = get(path).token(token).send();
			assertEquals(path + " " + device, 403, device.status());
			assertEquals("web-session-only", device.code());
		}
	}

	private String line(String level, String message) {
		return entry(start.plusMillis(written++), level, SOURCE, 12, message);
	}

	private JsonNode lines(String query) {
		ApiResponse response = get(LINES + query).webSession(web).send();
		assertEquals(query + " " + response, 200, response.status());
		return response.json();
	}

	private static String enc(String decoded) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(decoded.getBytes(StandardCharsets.US_ASCII));
	}

	private static List<String> messages(JsonNode lines) {
		List<String> messages = new ArrayList<>();
		for (JsonNode line : lines.path("lines"))
			messages.add(line.path("message").textValue());
		return messages;
	}

	private static List<String> texts(JsonNode array) {
		List<String> texts = new ArrayList<>();
		array.forEach(node -> texts.add(node.textValue()));
		return texts;
	}
}
