// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.web.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.List;

import org.junit.Test;

/**
 * What System > General > Environment shows of each variable and property.
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
    public void testLeavesOtherValuesAlone() {
        for (String name : List.of("DB_USER", "DB_HOST", "PATH", "PWD", "JAVA_HOME", "java.version", "TZ",
                "file.encoding"))
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
}
