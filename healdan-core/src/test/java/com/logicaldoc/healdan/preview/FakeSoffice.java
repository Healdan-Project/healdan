package com.logicaldoc.healdan.preview;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * Stand-ins for {@code soffice}. The scripts are written at run time because
 * Maven drops the execute bit of resource files.
 */
final class FakeSoffice {

	private static final String PARSE = """
			#!/bin/bash
			OUT=""; IN=""; UI=""
			while [ $# -gt 0 ]; do
			  case "$1" in
			    --outdir) OUT="$2"; shift 2;;
			    -env:UserInstallation=*) UI="$1"; shift;;
			    *) IN="$1"; shift;;
			  esac
			done
			BASE=$(basename "$IN"); BASE="${BASE%.*}"
			write_pdf() {
			  if [ -n "$FAKE_PDF" ]; then cp "$FAKE_PDF" "$OUT/$BASE.pdf"
			  else printf '%%PDF-1.4\\n1 0 obj<<>>endobj\\ntrailer<<>>\\n%%%%EOF\\n' > "$OUT/$BASE.pdf"; fi
			}
			""";

	private FakeSoffice() {
	}

	/** @return a converter that writes a PDF */
	static File ok() throws IOException {
		return script("ok", "write_pdf\n");
	}

	/**
	 * @param pdf the PDF to hand back
	 * @return a converter that writes a copy of the given PDF, whatever it converts
	 */
	static File copying(File pdf) throws IOException {
		String path = pdf.getAbsolutePath();
		return script("copy-" + Integer.toHexString(path.hashCode()), "cp '" + path + "' \"$OUT/$BASE.pdf\"\n");
	}

	/** @return a converter that exits 1 */
	static File fail() throws IOException {
		return script("fail", "exit 1\n");
	}

	/** @return a converter that exits 0 and writes nothing */
	static File empty() throws IOException {
		return script("empty", "exit 0\n");
	}

	/** @return a converter that leaves a child on the profile and never finishes */
	static File hang() throws IOException {
		return script("hang", "sh -c 'sleep 600; :' \"$UI\" &\nsleep 600\n");
	}

	/**
	 * @param marker text to look for in the input file
	 * @return a converter that hangs like {@link #hang()} on a file whose bytes contain the marker, and
	 *         otherwise writes a PDF
	 */
	static File hangIfContains(String marker) throws IOException {
		return script("hang-if-" + marker, "if grep -q '" + marker
				+ "' \"$IN\"; then\n  sh -c 'sleep 600; :' \"$UI\" &\n  sleep 600\nfi\nwrite_pdf\n");
	}

	/**
	 * @param log the file that gets a "written" line once the PDF is there
	 * @return a converter that writes a PDF, says so, and then never finishes
	 */
	static File partialThenHang(File log) throws IOException {
		String path = log.getAbsolutePath();
		return script("partial-hang-" + Integer.toHexString(path.hashCode()),
				"write_pdf\necho written >> '" + path + "'\nsleep 600\n");
	}

	/** @return a converter that writes a PDF and then never finishes */
	static File partialThenHang() throws IOException {
		return script("partial-hang", "write_pdf\nsleep 600\n");
	}

	/**
	 * Logs no times: the wall clock can step back between two runs, so the
	 * lines' order is what shows whether runs overlapped.
	 *
	 * @param seconds how long the conversion takes
	 * @param log the file that gets a "start" line as each run begins and an
	 *        "end" line just before it exits
	 * @return a converter that takes a while
	 */
	static File slow(int seconds, File log) throws IOException {
		String path = log.getAbsolutePath();
		return script("slow-" + seconds + "-" + Integer.toHexString(path.hashCode()),
				"echo start >> '" + path + "'\nsleep " + seconds + "\nwrite_pdf\necho end >> '" + path + "'\n");
	}

	private static File script(String name, String body) throws IOException {
		File dir = new File("target/fake-soffice");
		Files.createDirectories(dir.toPath());
		File f = new File(dir, name + ".sh").getAbsoluteFile();
		Files.writeString(f.toPath(), PARSE + body);
		Files.setPosixFilePermissions(f.toPath(), PosixFilePermissions.fromString("rwxr-xr-x"));
		return f;
	}
}
