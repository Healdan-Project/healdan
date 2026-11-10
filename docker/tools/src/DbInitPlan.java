// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Works out which DbInit SQL scripts to run, the way the setup wizard's PluginDbInit does: every plugin's DbInit
 * extensions against logicaldoc-core, in ascending position, preferring the &lt;sqlFile&gt;.&lt;dbms&gt; variant.
 * Pure logic: no database and no LogicalDOC classes.
 */
public final class DbInitPlan {

    /** One DbInit extension: the declaring plugin, its position and the SQL resource it names. */
    public record Entry(String pluginId, int position, String sqlFile) {
    }

    /**
     * A CREATE TABLE, whether it's OR REPLACE (group 1), and its table's name, backquoted (group 2) or not (group 3),
     * after the database's name if it has one. CREATE TEMPORARY TABLE's don't last.
     */
    private static final Pattern CREATE_TABLE = Pattern.compile("(?i)\\bcreate\\s+(or\\s+replace\\s+)?table\\s+"
            + "(?:if\\s+not\\s+exists\\s+)?(?:(?:`[^`]+`|[A-Za-z0-9_$]+)\\s*\\.\\s*)?(?:`([^`]+)`|([A-Za-z0-9_$]+))");

    private static final Pattern WORD = Pattern.compile("[A-Za-z0-9_$]+");

    /** The words that start an item of CREATE TABLE's parentheses that isn't a column, unless backquoted. */
    private static final Set<String> NOT_COLUMNS = Set.of("primary", "constraint", "unique", "foreign", "key", "index",
            "check", "fulltext", "spatial", "period", "like");

    private DbInitPlan() {
    }

    /** Reads the DbInit extensions declared against logicaldoc-core in one plugin.xml. */
    public static List<Entry> parse(InputStream pluginXml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setValidating(false);
        // Third-party jars can ship a plugin.xml too; never fetch a DTD over the network.
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        Element root = factory.newDocumentBuilder().parse(pluginXml).getDocumentElement();

        List<Entry> entries = new ArrayList<>();
        if (!"plugin".equals(root.getTagName()))
            return entries;
        String pluginId = root.getAttribute("id");
        NodeList extensions = root.getElementsByTagName("extension");
        for (int i = 0; i < extensions.getLength(); i++) {
            Element ext = (Element) extensions.item(i);
            if (!"logicaldoc-core".equals(ext.getAttribute("plugin-id"))
                    || !"DbInit".equals(ext.getAttribute("point-id")))
                continue;
            Map<String, String> params = new HashMap<>();
            NodeList parameters = ext.getElementsByTagName("parameter");
            for (int j = 0; j < parameters.getLength(); j++) {
                Element p = (Element) parameters.item(j);
                params.put(p.getAttribute("id"), p.getAttribute("value").trim());
            }
            String position = params.get("position");
            String sqlFile = params.get("sqlFile");
            if (position == null || position.isEmpty() || sqlFile == null || sqlFile.isEmpty())
                throw new IllegalArgumentException("Plugin " + pluginId + " declares DbInit extension '"
                        + ext.getAttribute("id") + "' without both position and sqlFile");
            entries.add(new Entry(pluginId, Integer.parseInt(position), sqlFile));
        }
        return entries;
    }

    /** Sorts by ascending position; entries with equal positions keep their order. */
    public static List<Entry> order(Collection<Entry> entries) {
        List<Entry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparingInt(Entry::position));
        return sorted;
    }

    /** The resource to run for an entry: the DBMS variant if present, else the generic script. */
    public static String resolve(Entry entry, String dbms, Predicate<String> exists) {
        String variant = entry.sqlFile() + "." + dbms.toLowerCase(Locale.ROOT);
        if (exists.test(variant))
            return variant;
        if (exists.test(entry.sqlFile()))
            return entry.sqlFile();
        throw new IllegalStateException("Plugin " + entry.pluginId() + " declares DbInit script "
                + entry.sqlFile() + " but neither it nor " + variant + " is on the classpath");
    }

    /**
     * The tables the scripts create, in order of first appearance, each with the columns its CREATE TABLE defines, in
     * order. Comments and the text of strings create nothing. A table's keys and constraints aren't columns, unless
     * the name is backquoted. A table created twice, in one script or two, is listed once, with the columns of its
     * first CREATE TABLE, as MariaDB keeps that one (a second fails, and IF NOT EXISTS does nothing), unless a later
     * one is CREATE OR REPLACE, whose columns replace them. Table names are matched as written, as MariaDB on Linux
     * matches them. A table made from another (LIKE, AS SELECT) is listed with no columns. The scripts create columns
     * only in CREATE TABLE: their ALTER TABLEs add constraints.
     */
    public static Map<String, List<String>> createdColumns(List<String> scripts) {
        Map<String, List<String>> tables = new LinkedHashMap<>();
        for (String sql : scripts) {
            String text = masked(sql);
            Matcher m = CREATE_TABLE.matcher(text);
            while (m.find()) {
                String table = m.group(2) != null ? m.group(2) : m.group(3);
                if (tables.containsKey(table) && m.group(1) == null)
                    continue;
                LinkedHashMap<String, String> columns = new LinkedHashMap<>();
                int open = skipSpace(text, m.end());
                if (open < text.length() && text.charAt(open) == '(')
                    for (String column : columnNames(sql, text, open))
                        columns.putIfAbsent(column.toLowerCase(Locale.ROOT), column);
                tables.put(table, List.copyOf(columns.values()));
            }
        }
        return tables;
    }

    /**
     * What a database lacks of what the scripts create: the tables it doesn't have, and for each table it has, the
     * columns that table lacks, both in the scripts' order. Tables and columns the database has besides don't count.
     */
    public record Missing(List<String> tables, Map<String, List<String>> columns) {

        public boolean isEmpty() {
            return tables.isEmpty() && columns.isEmpty();
        }

        /**
         * As "table ld_a lacks columns ld_x, ld_y; table ld_c lacks column ld_z; table ld_b is missing": the tables that
         * lack columns, then the missing ones, each in the scripts' order.
         */
        public String describe() {
            List<String> parts = new ArrayList<>();
            columns.forEach((table, names) -> parts.add("table " + table + " lacks column"
                    + (names.size() == 1 ? " " : "s ") + String.join(", ", names)));
            if (!tables.isEmpty())
                parts.add((tables.size() == 1 ? "table " : "tables ") + String.join(", ", tables)
                        + (tables.size() == 1 ? " is missing" : " are missing"));
            return String.join("; ", parts);
        }
    }

    /**
     * Compares what the scripts create (createdColumns) with the columns of each table the database has, matching
     * table names as written, as MariaDB on Linux matches them, and column names whatever their case, as MariaDB
     * matches those.
     */
    public static Missing missing(Map<String, List<String>> expected, Map<String, Set<String>> actual) {
        Map<String, Set<String>> has = new HashMap<>();
        actual.forEach((table, columns) -> {
            Set<String> lower = has.computeIfAbsent(table, t -> new HashSet<>());
            columns.forEach(column -> lower.add(column.toLowerCase(Locale.ROOT)));
        });
        List<String> tables = new ArrayList<>();
        Map<String, List<String>> columns = new LinkedHashMap<>();
        expected.forEach((table, names) -> {
            Set<String> present = has.get(table);
            if (present == null) {
                tables.add(table);
                return;
            }
            List<String> lacking = names.stream().filter(n -> !present.contains(n.toLowerCase(Locale.ROOT))).toList();
            if (!lacking.isEmpty())
                columns.put(table, lacking);
        });
        return new Missing(List.copyOf(tables), Collections.unmodifiableMap(columns));
    }

    /**
     * The script with each comment, and the text of each string, replaced by spaces, so a parenthesis, comma or CREATE
     * TABLE in them counts for nothing, and every character keeps its place. A -- or # comment runs to the end of its
     * line. A string is single- or double-quoted, as MariaDB reads double quotes without ANSI_QUOTES, and its doubled
     * quote or \ escape doesn't end it. Backquoted names are kept.
     */
    private static String masked(String sql) {
        char[] out = sql.toCharArray();
        int i = 0;
        while (i < out.length) {
            char c = out[i];
            if (c == '\'' || c == '"') {
                i++;
                while (i < out.length) {
                    if (out[i] == '\\' && i + 1 < out.length) {
                        out[i] = ' ';
                        out[i + 1] = ' ';
                        i += 2;
                    } else if (out[i] == c && i + 1 < out.length && out[i + 1] == c) {
                        out[i] = ' ';
                        out[i + 1] = ' ';
                        i += 2;
                    } else if (out[i] == c) {
                        i++;
                        break;
                    } else {
                        if (out[i] != '\n')
                            out[i] = ' ';
                        i++;
                    }
                }
            } else if (c == '`') {
                int end = sql.indexOf(c, i + 1);
                i = end < 0 ? out.length : end + 1;
            } else if (c == '#' || (c == '-' && i + 1 < out.length && out[i + 1] == '-')) {
                while (i < out.length && out[i] != '\n')
                    out[i++] = ' ';
            } else if (c == '/' && i + 1 < out.length && out[i + 1] == '*') {
                int end = sql.indexOf("*/", i + 2);
                int stop = end < 0 ? out.length : end + 2;
                for (; i < stop; i++)
                    if (out[i] != '\n')
                        out[i] = ' ';
            } else {
                i++;
            }
        }
        return new String(out);
    }

    /**
     * The names of the columns defined between the parenthesis at open and its match: the first word of each item
     * the top-level commas part, but for the items that are keys or constraints. Names come from the script itself,
     * as the masked text blanks what its strings hold, not its names.
     */
    private static List<String> columnNames(String sql, String text, int open) {
        List<String> names = new ArrayList<>();
        int depth = 0;
        int start = open + 1;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '`') {
                int end = text.indexOf(c, i + 1);
                i = end < 0 ? text.length() : end;
            } else if (c == '(') {
                depth++;
            } else if (c == ')' || (c == ',' && depth == 1)) {
                if (c == ')' && --depth > 0)
                    continue;
                String name = columnName(sql, skipSpace(text, start), i);
                if (name != null)
                    names.add(name);
                start = i + 1;
                if (c == ')')
                    break;
            }
        }
        return names;
    }

    /** The column an item between from and to defines, or null for a key, a constraint or an empty item. */
    private static String columnName(String sql, int from, int to) {
        if (from >= to)
            return null;
        char quote = sql.charAt(from);
        if (quote == '`') {
            int end = sql.indexOf(quote, from + 1);
            return end < 0 || end > to ? null : sql.substring(from + 1, end);
        }
        Matcher word = WORD.matcher(sql).region(from, to);
        if (!word.lookingAt())
            return null;
        String name = word.group();
        return NOT_COLUMNS.contains(name.toLowerCase(Locale.ROOT)) ? null : name;
    }

    private static int skipSpace(String text, int from) {
        int i = from;
        while (i < text.length() && Character.isWhitespace(text.charAt(i)))
            i++;
        return i;
    }
}
