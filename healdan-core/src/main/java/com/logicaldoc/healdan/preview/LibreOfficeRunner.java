package com.logicaldoc.healdan.preview;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.logicaldoc.util.exec.Exec;
import com.logicaldoc.util.spring.Context;

/**
 * Converts Office files to PDF with a headless {@code soffice}. Each run owns
 * one of a few profile "slots" (LibreOffice allows one process per profile),
 * has a timeout, and is followed by a sweep that kills anything still using
 * the slot's profile. Background work takes a single extra permit and there
 * are at least two slots, so that it never uses every slot.
 */
@Component
public class LibreOfficeRunner {

	private static final Logger log = LoggerFactory.getLogger(LibreOfficeRunner.class);

	private static final String PREFIX = "converter.OfficeConverter.";

	private static final String TEMPLATE = "/healdan/libreoffice/registrymodifications.xcu";

	/** Also the fewest: one for background work and at least one for interactive previews */
	private static final int DEFAULT_SLOTS = 2;

	/** Reads the timeout back from a failure's message, as {@link #timedOut} words it */
	private static final Pattern TIMED_OUT = Pattern.compile("timed out after (\\d+) s");

	private static final int DEFAULT_TIMEOUT = 120;

	private static final int KILL_WAIT_SECONDS = 5;

	/** How long an interactive conversion waits for a free slot */
	private static final Duration SLOT_WAIT = Duration.ofSeconds(30);

	private final Semaphore backgroundPermit = new Semaphore(1);

	private final ThreadLocal<Boolean> background = ThreadLocal.withInitial(() -> Boolean.FALSE);

	private BlockingQueue<Integer> freeSlots;

	private boolean[] slotsPrepared;

	private Duration slotWait = SLOT_WAIT;

	/** @return the runner bean */
	public static LibreOfficeRunner get() {
		return Context.get(LibreOfficeRunner.class);
	}

	/** Package-private for tests. */
	void setSlotWait(Duration slotWait) {
		this.slotWait = slotWait;
	}

	/**
	 * Runs the work as background work: while it runs on this thread,
	 * {@link #toPdf} first takes the single background permit.
	 * 
	 * @param <T> the result type
	 * @param work the work
	 * @return what the work returns
	 * @throws Exception whatever the work throws
	 */
	public <T> T inBackground(Callable<T> work) throws Exception {
		boolean before = background.get();
		background.set(Boolean.TRUE);
		try {
			return work.call();
		} finally {
			background.set(before);
		}
	}

	/**
	 * @return true while background work runs on this thread
	 */
	boolean isBackground() {
		return background.get();
	}

	/**
	 * Converts a file to PDF. An interactive conversion waits at most 30
	 * seconds for a free slot; background work waits as long as it takes.
	 * 
	 * @param src the Office file
	 * @param kind its family
	 * @param dest where to leave the PDF
	 * @throws IOException if LibreOffice is busy, can't be run, times out or
	 *         leaves no PDF; a {@link LibreOfficeUnavailableException} if it
	 *         can't be started at all
	 */
	public void toPdf(File src, OfficeFormats.Kind kind, File dest) throws IOException {
		boolean viaBackground = background.get();
		if (viaBackground)
			acquire(backgroundPermit);
		try {
			int slot = takeSlot(viaBackground);
			try {
				convert(src, kind, dest, slot);
			} finally {
				freeSlots.add(slot);
			}
		} finally {
			if (viaBackground)
				backgroundPermit.release();
		}
	}

	private static void acquire(Semaphore permit) throws IOException {
		try {
			permit.acquire();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while waiting to convert", e);
		}
	}

	private synchronized BlockingQueue<Integer> slots() {
		if (freeSlots == null) {
			int count = Context.get().getConfig().getInt(PREFIX + "slots", DEFAULT_SLOTS);
			if (count < DEFAULT_SLOTS) {
				log.warn("{}slots is {}; using {}, so that previews never wait behind background conversions",
						PREFIX, count, DEFAULT_SLOTS);
				count = DEFAULT_SLOTS;
			}
			freeSlots = new LinkedBlockingQueue<>();
			for (int i = 0; i < count; i++)
				freeSlots.add(i);
			slotsPrepared = new boolean[count];
		}
		return freeSlots;
	}

	private int takeSlot(boolean viaBackground) throws IOException {
		try {
			if (viaBackground)
				return slots().take();
			// Nothing is cached, so the next view tries again
			Integer slot = slots().poll(slotWait.toMillis(), TimeUnit.MILLISECONDS);
			if (slot == null)
				throw new IOException("LibreOffice is busy");
			return slot;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while waiting for a LibreOffice slot", e);
		}
	}

	private static File slotDir(int slot) {
		return new File(new File(System.getProperty("java.io.tmpdir"), "healdan-libreoffice"), "slot-" + slot);
	}

	/** On a slot's first use in this JVM, replaces whatever an earlier run left with a fresh profile. */
	private void prepareSlot(int slot) throws IOException {
		synchronized (this) {
			if (slotsPrepared[slot])
				return;
			File dir = slotDir(slot);
			deleteTree(dir.toPath());
			File user = new File(dir, "user");
			Files.createDirectories(user.toPath());
			try (InputStream in = LibreOfficeRunner.class.getResourceAsStream(TEMPLATE)) {
				if (in == null)
					throw new IOException("Missing resource " + TEMPLATE);
				Files.copy(in, new File(user, "registrymodifications.xcu").toPath(),
						StandardCopyOption.REPLACE_EXISTING);
			}
			slotsPrepared[slot] = true;
		}
	}

	private synchronized void unprepareSlot(int slot) {
		slotsPrepared[slot] = false;
	}

	private static void deleteTree(Path dir) throws IOException {
		if (!Files.exists(dir))
			return;
		try (Stream<Path> walk = Files.walk(dir)) {
			for (Path p : (Iterable<Path>) walk.sorted(Comparator.reverseOrder())::iterator)
				Files.deleteIfExists(p);
		}
	}

	private void convert(File src, OfficeFormats.Kind kind, File dest, int slot) throws IOException {
		int timeout = timeout();

		prepareSlot(slot);
		File slotDir = slotDir(slot);
		Path outDir = Files.createTempDirectory("healdan-lo-out");
		try {
			List<String> cmd = commandLine(configuredPath(), src, kind, slotDir, outDir.toFile());
			long started = System.nanoTime();
			int exit;
			long seconds;
			try {
				exit = new Exec().execPB(cmd, null, outDir.toFile(), timeout);
				seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started);
			} catch (IOException e) {
				// Exec throws only before the process runs: the command isn't allowed or can't start
				throw new LibreOfficeUnavailableException(e.getMessage(), e);
			} finally {
				sweep(slotDir);
			}
			// A run that was interrupted or hit the timeout fails even if it left a PDF: Exec killed
			// LibreOffice, so that file may be cut off mid-write. The profile may be half-written too:
			// rebuild it on next use
			if (Thread.currentThread().isInterrupted()) {
				unprepareSlot(slot);
				throw new IOException("Interrupted while converting " + src.getName());
			}
			if (seconds >= timeout) {
				unprepareSlot(slot);
				throw new IOException(timedOut(timeout, src.getName()));
			}

			String base = src.getName();
			int dot = base.lastIndexOf('.');
			if (dot > 0)
				base = base.substring(0, dot);
			File pdf = new File(outDir.toFile(), base + ".pdf");
			if (pdf.isFile() && pdf.length() > 0) {
				Files.move(pdf.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
				return;
			}
			throw new IOException("LibreOffice exited " + exit + " without a PDF for " + src.getName());
		} finally {
			deleteTree(outDir);
		}
	}

	/**
	 * Package-private for tests.
	 * 
	 * @return the timeout in seconds; the default when the setting isn't
	 *         positive, since Exec would then wait for ever
	 */
	static int timeout() {
		int timeout = Context.get().getConfig().getInt(PREFIX + "timeout", DEFAULT_TIMEOUT);
		return timeout > 0 ? timeout : DEFAULT_TIMEOUT;
	}

	/**
	 * @param timeout the timeout the run hit, in seconds
	 * @param fileName the file it was converting
	 * @return the failure's message, which {@link #timedOutAfter} reads back
	 */
	static String timedOut(int timeout, String fileName) {
		return "LibreOffice timed out after " + timeout + " s converting " + fileName;
	}

	/**
	 * @param error a failure's message, null if none
	 * @return the timeout the failure hit, in seconds; empty if it wasn't a
	 *         timeout or says no number
	 */
	static OptionalInt timedOutAfter(String error) {
		if (error == null)
			return OptionalInt.empty();
		Matcher m = TIMED_OUT.matcher(error);
		if (!m.find())
			return OptionalInt.empty();
		try {
			return OptionalInt.of(Integer.parseInt(m.group(1)));
		} catch (NumberFormatException e) {
			return OptionalInt.empty();
		}
	}

	private static String configuredPath() {
		String path = Context.get().getConfig().getProperty(PREFIX + "path");
		return path == null || path.isBlank() ? "soffice" : path;
	}

	/** Package-private for tests. */
	List<String> commandLine(File src, OfficeFormats.Kind kind, File slotDir, File outDir) {
		return commandLine(configuredPath(), src, kind, slotDir, outDir);
	}

	private static List<String> commandLine(String path, File src, OfficeFormats.Kind kind, File slotDir,
			File outDir) {
		List<String> cmd = new ArrayList<>();
		cmd.add(path);
		cmd.add("--headless");
		cmd.add("--norestore");
		cmd.add("--nologo");
		cmd.add("--nodefault");
		cmd.add("--nolockcheck");
		cmd.add("-env:UserInstallation=" + profileUri(slotDir));
		cmd.add("--convert-to");
		cmd.add("pdf:" + kind.pdfFilter() + ":{\"ExportNotes\":{\"type\":\"boolean\",\"value\":\"true\"}}");
		cmd.add("--outdir");
		cmd.add(outDir.getPath());
		cmd.add(src.getPath());
		return cmd;
	}

	/**
	 * @return the profile as the command line names it, with a closing slash
	 *         so that slot-1's never matches slot-10's
	 */
	private static String profileUri(File slotDir) {
		String uri = slotDir.toURI().toString();
		return uri.endsWith("/") ? uri : uri + "/";
	}

	/**
	 * Force-kills every process still using the slot's profile: soffice.bin can
	 * outlive the soffice launcher. It looks for the profile as the command
	 * line names it, a URI that escapes spaces and uses forward slashes on
	 * every system.
	 */
	private static void sweep(File slotDir) {
		String needle = profileUri(slotDir);
		List<ProcessHandle> victims = ProcessHandle.allProcesses()
				.filter(h -> h.info().commandLine().map(c -> c.contains(needle)).orElse(false)).toList();
		for (ProcessHandle h : victims) {
			log.warn("Killing leftover LibreOffice process {}", h.pid());
			h.destroyForcibly();
		}
		for (ProcessHandle h : victims) {
			try {
				h.onExit().get(KILL_WAIT_SECONDS, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			} catch (Exception e) {
				log.warn("Process {} did not exit after being killed", h.pid());
			}
		}
	}
}
