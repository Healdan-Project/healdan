// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** How the move matches the source's tables and columns with the target's (spec section 9.3, step 4). */
public class MovePlanTest {

    public static void main(String[] args) {
        matchesWhateverTheCase();
        aColumnOnlyTheTargetHasIsAProblem();
        aTableOnlyOneSideHas();
        neverCopiesTheTargetsOwnTables();
        namesThatMatchTwice();
        sourceOnlyTablesThatMatchTwice();
        Check.done("MovePlanTest");
    }

    private static void matchesWhateverTheCase() {
        Map<String, List<String>> source = tables("ld_user", List.of("ld_id", "ld_name"), "LD_UNIQUETAG",
                List.of("ld_tag", "ld_int1"));
        Map<String, List<String>> target = tables("ld_user", List.of("ld_id", "ld_name"), "ld_uniquetag",
                List.of("ld_tag"));
        MovePlan.Plan plan = MovePlan.plan(source, target);
        Check.eq(List.of(), plan.problems(), "no problem when every target column is in the source");
        Check.eq(List.of(), plan.sourceOnlyTables(), "both tables are on both sides");
        Check.eq(2, plan.copied().size(), "both tables are copied");
        Check.eq(new MovePlan.TablePlan("ld_user", "ld_user", List.of("ld_id", "ld_name"), List.of()),
                plan.copied().get(0), "ld_user, every column");
        Check.eq(new MovePlan.TablePlan("ld_uniquetag", "LD_UNIQUETAG", List.of("ld_tag"), List.of("ld_int1")),
                plan.copied().get(1), "ld_uniquetag in the target's spelling, read by the source's, with ld_int1"
                        + " listed as only the source's");

        MovePlan.Plan mixed = MovePlan.plan(tables("ld_tag", List.of("LD_TAG", "Ld_DocId")),
                tables("LD_TAG", List.of("ld_docid", "ld_tag")));
        Check.eq(List.of(new MovePlan.TablePlan("LD_TAG", "ld_tag", List.of("ld_docid", "ld_tag"), List.of())),
                mixed.copied(), "columns matched whatever their case, in the target's order and spelling");
        Check.eq(List.of(), mixed.problems(), "no problem from case alone");
    }

    private static void aColumnOnlyTheTargetHasIsAProblem() {
        MovePlan.Plan plan = MovePlan.plan(tables("ld_tenant", List.of("ld_id", "ld_name")),
                tables("ld_tenant", List.of("ld_id", "ld_name", "ld_pagesquotathr")));
        Check.eq(1, plan.problems().size(), "one problem: " + plan.problems());
        String problem = plan.problems().isEmpty() ? "" : plan.problems().get(0);
        Check.isTrue(problem.contains("ld_tenant") && problem.contains("ld_pagesquotathr"),
                "the problem names the table and the column: " + problem);
    }

    private static void aTableOnlyOneSideHas() {
        MovePlan.Plan plan = MovePlan.plan(
                tables("ld_user", List.of("ld_id"), "ld_oldthing", List.of("ld_id")),
                tables("ld_user", List.of("ld_id"), "hd_newthing", List.of("hd_id")));
        Check.eq(List.of("ld_oldthing"), plan.sourceOnlyTables(), "a table only the source has is listed");
        Check.eq(1, plan.problems().size(), "a table only the target has is the one problem: " + plan.problems());
        String problem = plan.problems().isEmpty() ? "" : plan.problems().get(0);
        Check.isTrue(problem.contains("hd_newthing"), "the problem names the table: " + problem);
        Check.isTrue(!problem.contains("ld_oldthing"), "a table only the source has isn't a problem: " + problem);
        Check.eq(List.of("ld_user"), plan.copied().stream().map(MovePlan.TablePlan::table).toList(),
                "only the table both sides have is copied");
    }

    private static void neverCopiesTheTargetsOwnTables() {
        MovePlan.Plan plan = MovePlan.plan(
                tables("hd_schema_history", List.of("installed_rank"), "docker_init", List.of("completed"),
                        "ld_user", List.of("ld_id")),
                tables("HD_SCHEMA_HISTORY", List.of("installed_rank", "checksum"), "docker_move",
                        List.of("started", "finished"), "Docker_Init", List.of("completed"), "ld_user",
                        List.of("ld_id")));
        Check.eq(List.of("ld_user"), plan.copied().stream().map(MovePlan.TablePlan::table).toList(),
                "the history and the markers aren't copied, whatever their case");
        Check.eq(List.of(), plan.sourceOnlyTables(), "nor listed as only the source's");
        Check.eq(List.of(), plan.problems(), "nor a problem, though the target's history has a column the source's"
                + " lacks and docker_move only the target has");
        Check.eq(java.util.Set.of("hd_schema_history", "docker_init", "docker_move"), MovePlan.OWN, "OWN");
    }

    private static void namesThatMatchTwice() {
        MovePlan.Plan tablesTwice = MovePlan.plan(
                tables("ld_user", List.of("ld_id"), "LD_USER", List.of("ld_id")),
                tables("ld_user", List.of("ld_id")));
        Check.isTrue(tablesTwice.problems().size() == 1 && tablesTwice.problems().get(0).contains("ld_user"),
                "two source tables one target table matches is a problem that names it: " + tablesTwice.problems());

        MovePlan.Plan columnsTwice = MovePlan.plan(tables("ld_user", List.of("ld_id")),
                tables("ld_user", List.of("ld_id", "LD_ID")));
        Check.isTrue(columnsTwice.problems().size() == 1 && columnsTwice.problems().get(0).contains("ld_user")
                && columnsTwice.problems().get(0).toLowerCase().contains("ld_id"),
                "two target columns that match one name is a problem that names the table and the column: "
                        + columnsTwice.problems());
        Check.eq(List.of(), columnsTwice.copied().get(0).columns(), "and neither is copied");

        MovePlan.Plan sameSpelling = MovePlan.plan(tables("ld_user", List.of("ld_id", "ld_name", "ld_id")),
                tables("ld_user", List.of("ld_id", "ld_name")));
        Check.isTrue(sameSpelling.problems().size() == 1 && sameSpelling.problems().get(0).contains("ld_id"),
                "a column listed twice, as a read of every schema's columns would give, is a problem: "
                        + sameSpelling.problems());
    }

    private static void sourceOnlyTablesThatMatchTwice() {
        MovePlan.Plan plan = MovePlan.plan(
                tables("ld_user", List.of("ld_id"), "ld_old", List.of("ld_id"), "LD_OLD", List.of("ld_id")),
                tables("ld_user", List.of("ld_id")));
        Check.eq(List.of(), plan.problems(), "two source tables only the source has, whose names differ only by case,"
                + " aren't a problem: neither is copied");
        Check.eq(List.of("ld_old", "LD_OLD"), plan.sourceOnlyTables(), "both are listed, in the source's spelling");
        Check.eq(List.of("ld_user"), plan.copied().stream().map(MovePlan.TablePlan::table).toList(),
                "the table both sides have is copied");

        MovePlan.Plan targetTwice = MovePlan.plan(tables("ld_user", List.of("ld_id")),
                tables("ld_user", List.of("ld_id"), "LD_USER", List.of("ld_id")));
        Check.isTrue(targetTwice.problems().size() == 1 && targetTwice.problems().get(0).contains("target"),
                "a name the target gives twice is still a problem: " + targetTwice.problems());
        Check.eq(List.of(), targetTwice.copied(), "and neither is copied");
    }

    /** Table names to their columns, in the order given */
    private static Map<String, List<String>> tables(Object... nameThenColumns) {
        Map<String, List<String>> tables = new LinkedHashMap<>();
        for (int i = 0; i < nameThenColumns.length; i += 2) {
            @SuppressWarnings("unchecked")
            List<String> columns = (List<String>) nameThenColumns[i + 1];
            tables.put((String) nameThenColumns[i], columns);
        }
        return tables;
    }
}
