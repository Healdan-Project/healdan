import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    private static final Pattern CREATE_TABLE = Pattern
            .compile("(?i)\\bcreate\\s+table\\s+(?:if\\s+not\\s+exists\\s+)?([A-Za-z0-9_]+)");

    private static final Pattern LINE_COMMENT = Pattern.compile("--[^\\n]*");

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

    /** Names of the tables a script creates, in order of first appearance, ignoring -- comments. */
    public static List<String> createdTables(String sql) {
        Matcher m = CREATE_TABLE.matcher(LINE_COMMENT.matcher(sql).replaceAll(""));
        LinkedHashSet<String> names = new LinkedHashSet<>();
        while (m.find())
            names.add(m.group(1));
        return new ArrayList<>(names);
    }
}
