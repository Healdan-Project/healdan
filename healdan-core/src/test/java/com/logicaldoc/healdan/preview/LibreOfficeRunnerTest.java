package com.logicaldoc.healdan.preview;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.preview.OfficeFormats.Kind;
import com.logicaldoc.util.plugin.PluginException;
import com.logicaldoc.util.spring.Context;

public class LibreOfficeRunnerTest extends AbstractHealdanTestCase {

	private static final String PATH = "converter.OfficeConverter.path";

	private static final String TIMEOUT = "converter.OfficeConverter.timeout";

	private File dir;

	private File in;

	private File dest;

	@Override
	public void setUp() throws IOException, SQLException, PluginException {
		super.setUp();
		dir = Files.createTempDirectory(new File("target").toPath(), "runner").toFile();
		in = new File(dir, "in.docx");
		Files.writeString(in.toPath(), "x");
		dest = new File(dir, "dest.pdf");
		Context.get().getConfig().setProperty(TIMEOUT, "30");
	}

	private static void use(File fake) {
		Context.get().getConfig().setProperty(PATH, fake.getPath());
	}

	private static File slot0() {
		return new File(new File(System.getProperty("java.io.tmpdir"), "healdan-libreoffice"), "slot-0");
	}

	@Test
	public void testCommandLine() throws IOException {
		use(FakeSoffice.ok());
		File s = new File(dir, "s");
		File o = new File(dir, "o");
		assertEquals(List.of(System.getProperty("user.dir") + "/target/fake-soffice/ok.sh", "--headless",
				"--norestore", "--nologo", "--nodefault", "--nolockcheck", "-env:UserInstallation=" + s.toURI(),
				"--convert-to",
				"pdf:writer_pdf_Export:{\"ExportNotes\":{\"type\":\"boolean\",\"value\":\"true\"}}", "--outdir",
				o.getPath(), in.getPath()), new LibreOfficeRunner().commandLine(in, Kind.TEXT, s, o));
	}

	@Test
	public void testConverts() throws IOException {
		use(FakeSoffice.ok());
		new LibreOfficeRunner().toPdf(in, Kind.TEXT, dest);
		assertTrue(Files.readString(dest.toPath(), StandardCharsets.ISO_8859_1).startsWith("%PDF"));
	}

	@Test
	public void testProfileFromTemplate() throws IOException {
		use(FakeSoffice.ok());
		new LibreOfficeRunner().toPdf(in, Kind.TEXT, dest);
		byte[] template = getClass().getResourceAsStream("/healdan/libreoffice/registrymodifications.xcu")
				.readAllBytes();
		assertTrue(java.util.Arrays.equals(template,
				Files.readAllBytes(new File(slot0(), "user/registrymodifications.xcu").toPath())));
	}

	@Test
	public void testStaleSlotReplaced() throws IOException {
		File stale = new File(slot0(), "stale");
		Files.createDirectories(slot0().toPath());
		Files.writeString(stale.toPath(), "old");
		use(FakeSoffice.ok());
		new LibreOfficeRunner().toPdf(in, Kind.TEXT, dest);
		assertFalse(stale.exists());
	}

	@Test
	public void testFailureThrows() throws IOException {
		for (File fake : new File[] { FakeSoffice.fail(), FakeSoffice.empty() }) {
			use(fake);
			try {
				new LibreOfficeRunner().toPdf(in, Kind.TEXT, dest);
				fail("expected IOException for " + fake.getName());
			} catch (IOException expected) {
				assertFalse(dest.exists());
			}
		}
	}

	@Test
	public void testMissingCommandThrows() {
		Context.get().getConfig().setProperty(PATH, "/nonexistent/soffice");
		try {
			new LibreOfficeRunner().toPdf(in, Kind.TEXT, dest);
			fail("expected IOException");
		} catch (IOException expected) {
			assertFalse(dest.exists());
		}
	}

	@Test
	public void testTimeoutKillsEverything() throws IOException {
		use(FakeSoffice.hang());
		Context.get().getConfig().setProperty(TIMEOUT, "2");
		long start = System.currentTimeMillis();
		try {
			new LibreOfficeRunner().toPdf(in, Kind.TEXT, dest);
			fail("expected IOException");
		} catch (IOException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("timed out"));
		}
		assertTrue(System.currentTimeMillis() - start < 10000);
		String slot = slot0().getPath();
		assertEquals(List.of(), ProcessHandle.allProcesses()
				.filter(h -> h.info().commandLine().map(c -> c.contains(slot)).orElse(false)).toList());
	}

	@Test
	public void testTimeoutWithPartialOutputFails() throws IOException {
		use(FakeSoffice.partialThenHang());
		Context.get().getConfig().setProperty(TIMEOUT, "2");
		try {
			new LibreOfficeRunner().toPdf(in, Kind.TEXT, dest);
			fail("expected IOException");
		} catch (IOException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("timed out"));
		}
		assertFalse(dest.exists());
	}

	private Future<Void> inBackground(ExecutorService pool, LibreOfficeRunner runner, String name) {
		return pool.submit(() -> {
			File src = new File(dir, name + ".docx");
			Files.writeString(src.toPath(), "x");
			runner.inBackground((Callable<Void>) () -> {
				runner.toPdf(src, Kind.TEXT, new File(dir, name + ".pdf"));
				return null;
			});
			return null;
		});
	}

	private Future<Void> interactive(ExecutorService pool, LibreOfficeRunner runner, String name) {
		return pool.submit(() -> {
			File src = new File(dir, name + ".docx");
			Files.writeString(src.toPath(), "x");
			runner.toPdf(src, Kind.TEXT, new File(dir, name + ".pdf"));
			return null;
		});
	}

	private static void awaitStarts(File log, int count) throws Exception {
		long deadline = System.currentTimeMillis() + 10000;
		while ((!log.exists() || Files.readAllLines(log.toPath()).size() < count)
				&& System.currentTimeMillis() < deadline)
			Thread.sleep(50);
		assertEquals(count, Files.readAllLines(log.toPath()).size());
	}

	@Test
	public void testInteractiveWaitForASlotIsBounded() throws Exception {
		File log = new File(dir, "log.txt");
		use(FakeSoffice.slow(2, log));
		LibreOfficeRunner runner = new LibreOfficeRunner();
		runner.setSlotWait(Duration.ofMillis(200));
		ExecutorService pool = Executors.newFixedThreadPool(3);
		try {
			Future<Void> a = interactive(pool, runner, "a");
			Future<Void> b = interactive(pool, runner, "b");
			awaitStarts(log, 2);

			long start = System.currentTimeMillis();
			try {
				runner.toPdf(in, Kind.TEXT, dest);
				fail("expected IOException");
			} catch (IOException e) {
				assertEquals("LibreOffice is busy", e.getMessage());
			}
			assertTrue(System.currentTimeMillis() - start < 1500);
			assertFalse(dest.exists());

			// Background work waits for as long as it takes
			Future<Void> bg = inBackground(pool, runner, "bg");
			a.get(30, TimeUnit.SECONDS);
			b.get(30, TimeUnit.SECONDS);
			bg.get(30, TimeUnit.SECONDS);
			assertTrue(new File(dir, "bg.pdf").exists());
		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	public void testBackgroundWaitsForBackground() throws Exception {
		File log = new File(dir, "log.txt");
		use(FakeSoffice.slow(2, log));
		LibreOfficeRunner runner = new LibreOfficeRunner();
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Future<Void> a = inBackground(pool, runner, "a");
			Future<Void> b = inBackground(pool, runner, "b");
			a.get(30, TimeUnit.SECONDS);
			b.get(30, TimeUnit.SECONDS);
		} finally {
			pool.shutdownNow();
		}
		List<String> lines = Files.readAllLines(log.toPath());
		assertEquals(4, lines.size());
		List<String> kinds = new ArrayList<>();
		for (String l : lines)
			kinds.add(l.split(" ")[0]);
		assertEquals(List.of("start", "end", "start", "end"), kinds);
		assertTrue(Long.parseLong(lines.get(1).split(" ")[1]) <= Long.parseLong(lines.get(2).split(" ")[1]));
	}

	@Test
	public void testInteractiveNotBlockedByBackground() throws Exception {
		File log = new File(dir, "log.txt");
		LibreOfficeRunner runner = new LibreOfficeRunner();
		use(FakeSoffice.slow(3, log));
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Future<Void> bg = inBackground(pool, runner, "bg");
			long deadline = System.currentTimeMillis() + 10000;
			while ((!log.exists() || Files.readAllLines(log.toPath()).isEmpty())
					&& System.currentTimeMillis() < deadline)
				Thread.sleep(50);
			assertEquals(1, Files.readAllLines(log.toPath()).size());
			use(FakeSoffice.ok());
			long start = System.currentTimeMillis();
			pool.submit(() -> {
				runner.toPdf(in, Kind.TEXT, dest);
				return null;
			}).get(10, TimeUnit.SECONDS);
			assertTrue(System.currentTimeMillis() - start < 2000);
			// The background run was still going when the interactive one finished
			List<String> seen = Files.readAllLines(log.toPath());
			assertEquals(1, seen.size());
			assertTrue(seen.get(0).startsWith("start"));
			bg.get(30, TimeUnit.SECONDS);
		} finally {
			pool.shutdownNow();
		}
	}
}
