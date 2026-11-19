// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Which tables and columns the move from MariaDB copies (spec section 9.3, step 4): those both sides have, matched by
 * name whatever their case. A table or column only the target has means the source isn't at the version the move asks
 * for (section 9.2), and stops the move; one only the source has, ld_uniquetag.ld_int1 the one known (recon section
 * 2.3), is listed in the report and not copied. A pure decision over both sides' information_schema.columns.
 */
final class MovePlan {

    /** Never copied: the target's own Flyway history, and the two markers */
    static final Set<String> OWN = Set.of("hd_schema_history", "docker_init", "docker_move");

    /**
     * A table both sides have.
     *
     * @param table the target's name for it
     * @param columns the columns both sides have, in the target's order and spelling: the order the move reads the
     *        source in, writes the target in and adds values to the checksum in
     * @param sourceOnlyColumns the columns only the source has, in its order and spelling, which aren't copied
     */
    record TablePlan(String table, List<String> columns, List<String> sourceOnlyColumns) {
        TablePlan {
            columns = List.copyOf(columns);
            sourceOnlyColumns = List.copyOf(sourceOnlyColumns);
        }
    }

    /**
     * @param copied the tables both sides have, in the target's order
     * @param sourceOnlyTables the tables only the source has, in its order and spelling, which aren't copied
     * @param problems what stops the move, each naming its table, and its column when it's about one: a table or
     *        column only the target has, and a table or column one side has more than once, whatever the case
     */
    record Plan(List<TablePlan> copied, List<String> sourceOnlyTables, List<String> problems) {
        Plan {
            copied = List.copyOf(copied);
            sourceOnlyTables = List.copyOf(sourceOnlyTables);
            problems = List.copyOf(problems);
        }
    }

    private MovePlan() {
    }

    /**
     * Matches tables and columns by name, whatever their case. A table or column only the target has is a problem: the
     * source isn't at the version the move asks for. One only the source has is listed, and not copied.
     *
     * @param source and target: table name to its column names, in the tables' own order
     */
    static Plan plan(Map<String, List<String>> source, Map<String, List<String>> target) {
        List<String> problems = new ArrayList<>();
        Map<String, String> sourceTables = byName(source.keySet(), true, "the source has more than one table",
                problems);
        Map<String, String> targetTables = byName(target.keySet(), true, "the target has more than one table",
                problems);

        List<TablePlan> copied = new ArrayList<>();
        for (Map.Entry<String, String> table : targetTables.entrySet()) {
            String name = table.getValue();
            if (name == null)
                continue; // a name given more than once, already a problem
            if (!sourceTables.containsKey(table.getKey())) {
                problems.add(name + " is a table the target has and the source lacks, so the source isn't at the"
                        + " version the move asks for");
                continue;
            }
            String sourceName = sourceTables.get(table.getKey());
            if (sourceName != null)
                copied.add(columns(name, source.get(sourceName), target.get(name), problems));
        }

        List<String> sourceOnly = new ArrayList<>();
        for (Map.Entry<String, String> table : sourceTables.entrySet())
            if (table.getValue() != null && !targetTables.containsKey(table.getKey()))
                sourceOnly.add(table.getValue());
        return new Plan(copied, sourceOnly, problems);
    }

    /** The columns of a table both sides have */
    private static TablePlan columns(String table, List<String> source, List<String> target, List<String> problems) {
        Map<String, String> sourceColumns = byName(source, false,
                "the source's table " + table + " has more than one column", problems);
        Map<String, String> targetColumns = byName(target, false,
                "the target's table " + table + " has more than one column", problems);
        List<String> copied = new ArrayList<>();
        for (Map.Entry<String, String> column : targetColumns.entrySet()) {
            String name = column.getValue();
            if (name == null)
                continue; // a name given more than once, already a problem
            if (!sourceColumns.containsKey(column.getKey()))
                problems.add(table + "." + name + " is a column the target has and the source lacks, so the source"
                        + " isn't at the version the move asks for");
            else if (sourceColumns.get(column.getKey()) != null)
                copied.add(name);
        }
        List<String> sourceOnly = new ArrayList<>();
        for (Map.Entry<String, String> column : sourceColumns.entrySet())
            if (column.getValue() != null && !targetColumns.containsKey(column.getKey()))
                sourceOnly.add(column.getValue());
        return new TablePlan(table, copied, sourceOnly);
    }

    /**
     * @param tables whether the names are tables, of which OWN's are left out, or a table's columns
     * @param whose the start of the problem for a name given more than once, whatever the case, which the name
     *        completes
     * @return each name in lower case to its spelling, in the order given; to null for a name given more than once,
     *         whatever the case, which is added to the problems
     */
    private static Map<String, String> byName(Iterable<String> names, boolean tables, String whose,
            List<String> problems) {
        Map<String, String> byName = new LinkedHashMap<>();
        Map<String, Set<String>> clashes = new LinkedHashMap<>();
        for (String name : names) {
            String key = name.toLowerCase(Locale.ROOT);
            if (tables && OWN.contains(key))
                continue;
            if (!byName.containsKey(key)) {
                byName.put(key, name);
                continue;
            }
            String first = byName.put(key, null);
            clashes.computeIfAbsent(key, k -> new LinkedHashSet<>(List.of(first))).add(name);
        }
        for (Map.Entry<String, Set<String>> clash : clashes.entrySet())
            problems.add(whose + " named " + clash.getKey() + ", whatever the case (" + String.join(", ",
                    clash.getValue()) + "), so the move can't tell which is which");
        return byName;
    }
}
