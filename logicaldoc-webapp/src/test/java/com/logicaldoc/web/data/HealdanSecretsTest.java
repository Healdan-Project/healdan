// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.web.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.commons.io.FileUtils;
import org.junit.Test;

/**
 * What System > General > Environment shows of each variable and property, and
 * what Download the Logs puts in its zip of them and of the configuration.
 */
public class HealdanSecretsTest {

    @Test
    public void testMasksValuesWhoseNamesLookSecret() {
        for (String name : List.of("DB_PASSWORD", "MARIADB_ROOT_PASSWORD", "javax.net.ssl.trustStorePassword",
                "ldap.passwd", "healdan.secretKey", "AWS_SECRET_ACCESS_KEY", "GITHUB_TOKEN", "OIDC_CLIENT_SECRET",
                "X_API_KEY", "apikey", "AZURE_CREDENTIALS", "ACCESS_KEY_ID"))
            assertEquals(name, HealdanSecrets.MASK, HealdanSecrets.shown(name, "value"));
    }

    @Test
    public void testMasksTheKeysContextPropertiesHolds() {
        // context.properties, which Download the Logs puts in its zip, keeps
        // these under names without the words above
        for (String name : List.of("converter.OnlyOfficeConverter.security.key", "saml.sp.privatekey",
                "converter.AcmeCadConverter.licenseKey", "adminpasswd"))
            assertEquals(name, HealdanSecrets.MASK, HealdanSecrets.shown(name, "value"));
    }

    @Test
    public void testLeavesOtherValuesAlone() {
        for (String name : List.of("DB_USER", "DB_HOST", "PATH", "PWD", "JAVA_HOME", "java.version", "TZ",
                "file.encoding", "index.tokenfilter.stemmer", "index.tokenfilter.ngram.maxGramSize",
                "ftp.keystore.file", "default.2fa.yubikey.enabled"))
            assertEquals(name, "value", HealdanSecrets.shown(name, "value"));
    }

    @Test
    public void testLeavesEmptyAndMissingValuesAlone() {
        // Shows that a secret isn't set
        assertEquals("", HealdanSecrets.shown("DB_PASSWORD", ""));
        assertNull(HealdanSecrets.shown("DB_PASSWORD", null));
    }

    @Test
    public void testMasksSecretOptionsInsideAValue() {
        assertEquals("-Xmx2g -Djavax.net.ssl.keyStorePassword=******** -Dfile.encoding=UTF-8",
                HealdanSecrets.shown("CATALINA_OPTS",
                        "-Xmx2g -Djavax.net.ssl.keyStorePassword=changeit -Dfile.encoding=UTF-8"));
        assertEquals("-Dapp.token=******** -Dapp.secret=********",
                HealdanSecrets.shown("JAVA_OPTS", "-Dapp.token=\"two words\" -Dapp.secret='more words'"));
    }

    @Test
    public void testMasksAPasswordInAnAddress() {
        assertEquals("http://bob:********@proxy.example:3128",
                HealdanSecrets.shown("HTTPS_PROXY", "http://bob:pa:ss@proxy.example:3128"));
        assertEquals("jdbc:mariadb://db:3306/logicaldoc?user=app&password=********&ssl=true",
                HealdanSecrets.shown("JDBC_URL", "jdbc:mariadb://db:3306/logicaldoc?user=app&password=pw&ssl=true"));
        // An address with a user and no password, and one with neither
        assertEquals("ssh://git@example.com/repo", HealdanSecrets.shown("REPO", "ssh://git@example.com/repo"));
        assertEquals("http://proxy.example:3128", HealdanSecrets.shown("HTTP_PROXY", "http://proxy.example:3128"));
    }

    @Test
    public void testTheLogsEnvironmentIsMasked() {
        Properties properties = new Properties();
        properties.setProperty("javax.net.ssl.keyStorePassword", "changeit");
        properties.setProperty("java.version", "25");
        String text = HealdanSecrets.environment(Map.of("DB_PASSWORD", "s3cret", "DB_USER", "ldoc"), properties);

        assertFalse(text, text.contains("s3cret"));
        assertFalse(text, text.contains("changeit"));
        assertTrue(text, text.contains("DB_PASSWORD=********"));
        assertTrue(text, text.contains("DB_USER=ldoc"));
        assertTrue(text, text.contains("java.version=25"));
    }

    @Test
    public void testAPropertiesFileIsCopiedMasked() throws IOException {
        File file = new File("target/healdan-secrets-test.properties");
        FileUtils.writeStringToFile(file, """
                # A comment
                jdbc.password = s3cret
                jdbc.url=jdbc:mariadb://db/x?password=s3cret
                jdbc.username=ldoc
                proxy.password=
                """, StandardCharsets.ISO_8859_1);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            HealdanSecrets.writeShown(file, out);

            String copy = out.toString(StandardCharsets.ISO_8859_1);
            assertFalse(copy, copy.contains("s3cret"));
            Properties copied = new Properties();
            copied.load(new StringReader(copy));
            assertEquals(HealdanSecrets.MASK, copied.getProperty("jdbc.password"));
            assertEquals("jdbc:mariadb://db/x?password=" + HealdanSecrets.MASK, copied.getProperty("jdbc.url"));
            assertEquals("ldoc", copied.getProperty("jdbc.username"));
            assertEquals("", copied.getProperty("proxy.password"));
        } finally {
            FileUtils.deleteQuietly(file);
        }
    }

    @Test
    public void testAPropertiesFileThatCantBeReadIsLeftOut() throws IOException {
        File file = new File("target/healdan-secrets-test.properties");
        FileUtils.writeStringToFile(file, "jdbc.password=s3cret\nbroken=\\uZZZZ\n", StandardCharsets.ISO_8859_1);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            HealdanSecrets.writeShown(file, out);

            String copy = out.toString(StandardCharsets.ISO_8859_1);
            assertFalse(copy, copy.contains("s3cret"));
            assertTrue(copy, copy.startsWith("# Left out"));
        } finally {
            FileUtils.deleteQuietly(file);
        }
    }
}
