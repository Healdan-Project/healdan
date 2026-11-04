// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Test;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;

import com.logicaldoc.util.config.ContextProperties;
import com.logicaldoc.util.config.LogConfigurator;
import com.logicaldoc.util.servlet.MockServletRequest;
import com.logicaldoc.util.servlet.MockServletResponse;
import com.logicaldoc.util.spring.Context;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;

/**
 * System > General > Log's Download the Logs zips the logs with the
 * environment and the configuration. CE wrote the environment as it is, the
 * database password among it in Healdan's container, and copied the
 * configuration's {@code .properties} files as they are, with the database,
 * SMTP and proxy passwords. The zip masks them as the Environment tab does.
 */
public class LogDownloadSecretsTest extends AbstractWPTestCase {

    private final File root = new File("target/logdownload");

    private final File home = new File(root, "home");

    private final File logs = new File(root, "logs");

    private final File tomcat = new File(root, "tomcat");

    private final File zip = new File(root, "response.zip");

    @Override
    @After
    public void tearDown() throws IOException {
        super.tearDown();
        FileUtils.deleteDirectory(root);
    }

    @Test
    public void testTheZipMasksSecrets() throws IOException, ServletException {
        layOut();

        // Environment variables can't be set from Java, and system properties
        // go through the same masking
        Map<String, String> properties = Map.of("healdan.test.password", "env-hunter2", "healdan.test.opts",
                "-Xmx2g -Dhealdan.test.secret=opt-s3cret -Dfile.encoding=UTF-8");
        properties.forEach(System::setProperty);

        ContextProperties config = Context.get().getConfig();
        String ldocHome = config.getProperty("LDOCHOME");
        config.setProperty("LDOCHOME", home.getPath());
        // CE's dump of the configuration left out only keys with "password"
        config.setProperty("healdan.test.clientSecret", "dump-s3cret");
        Set<String> leftBefore = environmentFilesLeft();
        try (MockedConstruction<LogConfigurator> logConfigurators = Mockito.mockConstruction(LogConfigurator.class,
                (configurator, context) -> when(configurator.getLogsRoot()).thenReturn(logs.getPath()))) {
            download();
        } finally {
            properties.keySet().forEach(System::clearProperty);
            config.setProperty("LDOCHOME", ldocHome);
            config.remove("healdan.test.clientSecret");
        }

        // CE wrote its dump into the environment's temp file once more, and
        // left it in the temp folder
        assertEquals(leftBefore, environmentFilesLeft());

        Map<String, String> entries = unzip();
        // The zip was made, with every part this test looks at
        for (String entry : List.of("environment.txt", "logicaldoc/conf/context.properties",
                "logicaldoc/conf/context.properties.bak", "logicaldoc/conf/context-dump.properties",
                "tomcat/conf/catalina.properties", "logicaldoc/logs/dms.log"))
            assertTrue(entry, entries.containsKey(entry));

        for (Map.Entry<String, String> entry : entries.entrySet())
            for (String secret : List.of("env-hunter2", "opt-s3cret", "db-s3cret", "smtp-s3cret", "proxy-s3cret",
                    "url-s3cret", "backup-s3cret", "tomcat-s3cret", "dump-s3cret", "enc-s3cret"))
                assertFalse(entry.getKey() + " holds " + secret, entry.getValue().contains(secret));

        String environment = entries.get("environment.txt");
        assertTrue(environment, environment.contains("healdan.test.password=********"));
        assertTrue(environment, environment.contains("java.version="));

        String context = entries.get("logicaldoc/conf/context.properties");
        assertTrue(context, context.contains("jdbc.password=********"));
        assertTrue(context, context.contains("smtp.password=********"));
        // What isn't secret is still there, and so is a secret left empty
        assertTrue(context, context.contains("jdbc.username=ldoc"));
        assertTrue(context, context.contains("index.tokenfilter.stemmer=enabled"));
        assertTrue(context, context.contains("proxy.password=\n") || context.contains("proxy.password=\r\n"));

        assertTrue(entries.get("logicaldoc/logs/dms.log").contains("started"));
    }

    /**
     * Lays out a home, its logs and a Tomcat as the zip finds them
     */
    private void layOut() throws IOException {
        FileUtils.deleteDirectory(root);
        write(new File(home, "conf/context.properties"), """
                # The database
                jdbc.username=ldoc
                jdbc.password=db-s3cret
                jdbc.url=jdbc:mariadb://db:3306/logicaldoc?user=ldoc&password=url-s3cret
                smtp.password = smtp-s3cret
                proxy.password=
                proxy.password.old=proxy-s3cret
                index.tokenfilter.stemmer=enabled
                encryption.key=enc-s3cret
                """);
        write(new File(home, "conf/context.properties.bak"), "jdbc.password:backup-s3cret\n");
        write(new File(home, "conf/build.properties"),
                "update.dir=%s%npatch.dir=%s%n".formatted(new File(home, "updates").getAbsolutePath(),
                        new File(home, "patches").getAbsolutePath()));
        write(new File(logs, "dms.log"), "started\n");
        write(new File(tomcat, "conf/catalina.properties"), "healdan.test.keystorePassword=tomcat-s3cret\n");
        write(new File(tomcat, "logs/catalina.log"), "started\n");
    }

    private void download() throws IOException, ServletException {
        ServletContext servletContext = mock(ServletContext.class);
        when(servletContext.getRealPath("/WEB-INF/web.xml"))
                .thenReturn(new File(tomcat, "webapps/ROOT/WEB-INF/web.xml").getPath());
        ServletConfig servletConfig = mock(ServletConfig.class);
        when(servletConfig.getServletContext()).thenReturn(servletContext);

        LogDownload servlet = new LogDownload();
        servlet.init(servletConfig);

        MockServletRequest request = new MockServletRequest(servletSession);
        request.setParameter("appender", "all");
        MockServletResponse response = new MockServletResponse(zip);
        servlet.doGet(request, response);
        response.flushBuffer();
    }

    private Map<String, String> unzip() throws IOException {
        Map<String, String> entries = new HashMap<>();
        if (!zip.exists())
            return entries;
        try (InputStream in = new FileInputStream(zip); ZipInputStream zin = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null)
                entries.put(entry.getName(), new String(zin.readAllBytes(), StandardCharsets.ISO_8859_1));
        }
        return entries;
    }

    /**
     * The environment files in the temp folder, where the download makes its
     * own
     */
    private static Set<String> environmentFilesLeft() {
        String[] names = new File(System.getProperty("java.io.tmpdir"))
                .list((dir, name) -> name.startsWith("environment") && name.endsWith(".txt"));
        return names != null ? Set.of(names) : Set.of();
    }

    private static void write(File file, String content) throws IOException {
        FileUtils.writeStringToFile(file, content, StandardCharsets.ISO_8859_1);
    }
}
