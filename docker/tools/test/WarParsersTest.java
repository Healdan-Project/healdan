// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.logicaldoc.core.parser.Parser;

/**
 * Parses Office files with the parsers the server uses for them, on the WAR's own libraries. Maven's test classpath
 * has every library's dependencies, but WEB-INF/lib has only what the plugin zips ship, so a dependency missing from
 * every zip fails here and nowhere else before the server (Healdan-Project/healdan#202: POI's SparseBitSet).
 * The fixtures were made with POI on a full classpath: slides.pptx is one slide whose text box holds "zqxwarslide";
 * accounts.xlsx has "zqxwarcell" in A1 and 1234.5 in B1 in Excel's Accounting format, whose four parts POI formats
 * with CellNumberFormatter rather than Java's; legacy.xls has "zqxwarlegacy" in A1.
 * Usage: WarParsersTest <fixtures dir>
 */
public class WarParsersTest {

    private static final String POWERPOINT = "com.logicaldoc.healdan.parser.PowerPointParser"; // pptx, ppsx

    private static final String EXCEL = "com.logicaldoc.healdan.parser.ExcelParser"; // xlsx, xlsm

    private static final String CE_EXCEL = "com.logicaldoc.core.parser.XLSParser"; // xls, xlt

    /** Under the parsers' own 120 s, whose timeout fails with no Spring context, so a hang is reported as one. */
    private static final int TIMEOUT_SECONDS = 60;

    public static void main(String[] args) throws Exception {
        // POI formats numbers in the JVM's default locale, so B1 reads 1,234.50 whatever the build machine's is
        Locale.setDefault(Locale.UK);
        Path fixtures = Path.of(args[0]);
        parses(POWERPOINT, fixtures.resolve("slides.pptx"), "zqxwarslide");
        parses(EXCEL, fixtures.resolve("accounts.xlsx"), "zqxwarcell", "1,234.50");
        parses(CE_EXCEL, fixtures.resolve("legacy.xls"), "zqxwarlegacy");
        Check.done("WarParsersTest");
    }

    private static void parses(String parserClass, Path file, String... expected)
            throws IOException, InterruptedException {
        String fileName = file.getFileName().toString();
        byte[] content = Files.readAllBytes(file);
        FutureTask<String> parsing = new FutureTask<>(() -> {
            Parser parser = (Parser) Class.forName(parserClass).getDeclaredConstructor().newInstance();
            try (InputStream in = new ByteArrayInputStream(content)) {
                return parser.parse(in, fileName, "UTF-8", Locale.ENGLISH, "default");
            }
        });
        // A daemon, so a parser that never returns can't keep the JVM running
        Thread thread = new Thread(parsing, "parse " + fileName);
        thread.setDaemon(true);
        thread.start();
        String text;
        try {
            text = parsing.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            Check.fail(fileName + " with " + parserClass + ": no text after " + TIMEOUT_SECONDS + " s");
            return;
        } catch (ExecutionException e) {
            Check.fail(fileName + " with " + parserClass + ": " + rootCause(e));
            return;
        }
        for (String fragment : expected)
            Check.isTrue(text.contains(fragment), fileName + ": text has \"" + fragment + "\": <" + text + ">");
    }

    /** The parsers report a failure in their parsing thread only as a message, which names the original error. */
    private static String rootCause(Throwable e) {
        while (e.getCause() != null && e.getCause() != e)
            e = e.getCause();
        return e.toString();
    }
}
