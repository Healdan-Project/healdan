// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.web.data;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What System > General > Environment shows of an environment variable or a
 * Java system property, and what System > General > Log's Download the Logs
 * puts in its zip of them and of the configuration's {@code .properties}
 * files. Healdan's container keeps the database password in its environment,
 * {@code context.properties} keeps it with the SMTP and proxy passwords, and a
 * variable, an option or a setting can hold other passwords, keys and tokens,
 * so both mask a value whose name looks secret, a secret {@code -D} option
 * inside a value such as {@code CATALINA_OPTS}, and the password in an address.
 * They show the rest, and that a secret is empty.
 */
public final class HealdanSecrets {

    /** What the tab shows in place of a secret */
    public static final String MASK = "********";

    // A name with one of these in it, in any case, holds a secret. The index's
    // token filters are settings, not tokens.
    private static final String SECRET_WORDS = "password|passwd|secret|token(?!filter)|credential|api[._-]?key"
            + "|access[._-]?key|private[._-]?key|security[._-]?key|licen[cs]e[._-]?key";

    private static final Pattern SECRET_NAME = Pattern.compile("(?i).*(" + SECRET_WORDS + ").*");

    // -Dname=value, quoted or not, with a secret name
    private static final Pattern SECRET_OPTION = Pattern
            .compile("(?i)(-D[^\\s=]*(?:" + SECRET_WORDS + ")[^\\s=]*=)(\"[^\"]*\"|'[^']*'|\\S*)");

    // scheme://user:password@, as in a proxy's address
    private static final Pattern ADDRESS_PASSWORD = Pattern.compile("(://[^/\\s:@]*:)[^/\\s@]*(@)");

    // password=... in an address's query
    private static final Pattern QUERY_PASSWORD = Pattern.compile("(?i)([?&;](?:" + SECRET_WORDS + ")=)[^&;\\s]*");

    private HealdanSecrets() {
    }

    /**
     * @param name the variable's or the property's name
     * @param value its value
     *
     * @return what the Environment tab shows of it
     */
    public static String shown(String name, String value) {
        if (value == null || value.isEmpty())
            return value;
        if (name != null && SECRET_NAME.matcher(name).matches())
            return MASK;

        String shown = SECRET_OPTION.matcher(value).replaceAll("$1" + Matcher.quoteReplacement(MASK));
        shown = ADDRESS_PASSWORD.matcher(shown).replaceAll("$1" + Matcher.quoteReplacement(MASK) + "$2");
        return QUERY_PASSWORD.matcher(shown).replaceAll("$1" + Matcher.quoteReplacement(MASK));
    }

    /**
     * The environment as Download the Logs writes it, in place of CE's
     * {@code SystemUtil.printEnvironment()}: the variables, then the Java
     * system properties, each value as the Environment tab shows it
     *
     * @param variables the environment variables
     * @param properties the Java system properties
     *
     * @return the text
     */
    public static String environment(Map<String, String> variables, Properties properties) {
        StringBuilder text = new StringBuilder("#System Environment%n#%s%n".formatted(new Date()));
        variables.forEach((name, value) -> text.append("%s=%s%n".formatted(name, shown(name, value))));

        Properties shownProperties = new Properties();
        properties.forEach((name, value) -> shownProperties.put(name,
                Objects.toString(shown(name.toString(), Objects.toString(value, null)), "")));
        StringWriter java = new StringWriter();
        try {
            shownProperties.store(java, "Java Environment");
        } catch (IOException e) {
            // A StringWriter doesn't fail
        }
        return text.append("%n".formatted()).append(java).toString();
    }

    /**
     * Writes a copy of a {@code .properties} file with each value as the
     * Environment tab shows it, for Download the Logs, which copied the
     * configuration's files as they were. The copy has its keys in order and
     * no comments. A file that can't be read as properties is left out, with
     * a line saying so, rather than copied as it is.
     *
     * @param file the file
     * @param out where the copy goes, which is left open
     *
     * @throws IOException the file can't be read, or the copy written
     */
    public static void writeShown(File file, OutputStream out) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = new FileInputStream(file)) {
            properties.load(in);
        } catch (IllegalArgumentException e) {
            // A malformed Unicode escape
            out.write("# Left out: not readable as properties%n".formatted().getBytes(StandardCharsets.ISO_8859_1));
            return;
        }

        Properties shownProperties = new Properties();
        properties.forEach((key, value) -> shownProperties.put(key, shown(key.toString(), value.toString())));
        shownProperties.store(out, file.getName());
    }
}
