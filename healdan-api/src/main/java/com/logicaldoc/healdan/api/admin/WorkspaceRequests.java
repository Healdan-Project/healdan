// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.admin;

import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.healdan.admin.workspace.WorkspaceAdmin;
import com.logicaldoc.healdan.api.ApiProblem;
import com.logicaldoc.healdan.api.Caller;
import com.logicaldoc.healdan.api.FolderChanges;
import com.logicaldoc.healdan.api.FolderRenames;
import com.logicaldoc.healdan.api.FolderResource;
import com.logicaldoc.healdan.api.model.FieldError;
import com.logicaldoc.healdan.rules.StoredRows;
import com.logicaldoc.healdan.rules.StoredRows.FolderRow;
import com.logicaldoc.healdan.searchfolder.SearchFolderRule;
import com.logicaldoc.util.spring.Context;

/**
 * What the Workspace Editor's and the templates' routes share: reading a workspace's folder ({@code 404} for any
 * other), a body's members, a folder's name as every folder name is checked, and a search folder's rule.
 */
public final class WorkspaceRequests {

	private WorkspaceRequests() {
	}

	/**
	 * A search folder's rule as a body sends it, {@code openapi-admin.yaml}'s {@code SearchFolderRule}, each member
	 * read as JSON so that one left out (null here) is told apart from one sent as null ({@code NullNode}).
	 */
	public record RuleBody(JsonNode days, JsonNode onlyMine, JsonNode kind, JsonNode docClass, JsonNode docSubclass) {
	}

	/**
	 * @return the folder, a workspace's ({@link WorkspaceAdmin#workspaceFolder})
	 * @throws ApiProblem {@code 404 not-found} for any other
	 */
	static Folder folder(long id) {
		try {
			Folder folder = WorkspaceAdmin.get().workspaceFolder(id);
			if (folder == null)
				throw ApiProblem.notFound("There's no folder %d in a workspace.".formatted(id));
			return folder;
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't read folder #" + id, e);
		}
	}

	/**
	 * @return the folder changes, whose locks the public folder routes take too
	 */
	static FolderChanges changes() {
		return Context.get(FolderResource.class).changes();
	}

	/**
	 * @return the caller's session id, for the history; null for none
	 */
	static String sid(Caller caller) {
		return caller.session() != null ? caller.session().getSid() : null;
	}

	/**
	 * @param node a member of a body
	 * @param detail what to say when it isn't text
	 * @return its text; null when it's left out or null
	 * @throws ApiProblem {@code 400 bad-request} when it's something else
	 */
	static String text(JsonNode node, String detail) {
		if (node == null || node.isNull())
			return null;
		if (!node.isTextual())
			throw ApiProblem.badRequest(detail);
		return node.textValue();
	}

	/**
	 * @param node a member of a body
	 * @param detail what to say when it isn't an id
	 * @return the id; null when it's left out or null
	 * @throws ApiProblem {@code 400 bad-request} when it's something else
	 */
	static Long id(JsonNode node, String detail) {
		if (node == null || node.isNull())
			return null;
		if (!node.isIntegralNumber() || !node.canConvertToLong())
			throw ApiProblem.badRequest(detail);
		return node.longValue();
	}

	/**
	 * Checks a name as every folder name is checked ({@link FolderRenames#name}): a workspace's folder's, a search
	 * folder's and a template's
	 *
	 * @param sent the name sent; null for none
	 * @param errors where to add the field {@code name}'s errors: {@code required}, {@code too-long} or
	 *        {@code invalid}
	 * @return the name to store, without the whitespace at either end; null when it's refused
	 */
	@SuppressWarnings("unchecked")
	static String name(String sent, List<FieldError> errors) {
		if (sent == null) {
			errors.add(new FieldError("name", "required", "Give the folder a name."));
			return null;
		}
		try {
			return FolderRenames.name(sent);
		} catch (ApiProblem problem) {
			if (problem.status() != 422)
				throw problem;
			errors.addAll((List<FieldError>) problem.extras().get("fields"));
			return null;
		}
	}

	/**
	 * @throws ApiProblem {@code 409 refused} when the folder isn't in that parent any more: another request moved it
	 *         while this one waited for the parent's lock
	 */
	static void refuseMoved(long id, long parentId) {
		FolderRow row = StoredRows.get().folder(id);
		if (row == null || row.parentId() != parentId)
			throw ApiProblem.conflict("refused",
					"Folder %d moved while it was being changed. Try again.".formatted(id));
	}

	/**
	 * Refuses a rule whose members are of the wrong type, before anything else is checked
	 *
	 * @param rule the rule sent; null for none
	 * @throws ApiProblem {@code 400 bad-request} for a member of the wrong type, or a {@code kind} that isn't
	 *         {@code all}, {@code documents} or {@code emails}
	 */
	static void checkTypes(RuleBody rule) {
		if (rule == null)
			return;
		if (present(rule.days()) && !(rule.days().isIntegralNumber() && rule.days().canConvertToInt()))
			throw ApiProblem.badRequest("Give the rule's days as a whole number.");
		if (present(rule.onlyMine()) && !rule.onlyMine().isBoolean())
			throw ApiProblem.badRequest("Give the rule's onlyMine as true or false.");
		if (present(rule.kind()) && (!rule.kind().isTextual() || kind(rule.kind().textValue()) == null))
			throw ApiProblem.badRequest("Give the rule's kind as all, documents or emails.");
		text(rule.docClass(), "Give the rule's docClass as text, or null for any.");
		text(rule.docSubclass(), "Give the rule's docSubclass as text, or null for any.");
	}

	/**
	 * @param rule a rule whose types {@link #checkTypes} has checked; null for none
	 * @param errors where to add {@code rule}, or each member of it, {@code required}
	 * @return the rule; null when one of those is missing
	 */
	static SearchFolderRule rule(RuleBody rule, List<FieldError> errors) {
		if (rule == null) {
			errors.add(new FieldError("rule", "required", "Give the search folder a rule."));
			return null;
		}
		int before = errors.size();
		required(errors, "rule.days", present(rule.days()), "Say how many days back the rule looks.");
		required(errors, "rule.onlyMine", present(rule.onlyMine()), "Say whether only the viewer's changes count.");
		required(errors, "rule.kind", present(rule.kind()), "Say which documents the rule lists.");
		required(errors, "rule.docClass", rule.docClass() != null, "Give the Class, or null for any.");
		required(errors, "rule.docSubclass", rule.docSubclass() != null, "Give the Subclass, or null for any.");
		if (errors.size() > before)
			return null;
		return new SearchFolderRule(0L, rule.days().intValue(), rule.onlyMine().booleanValue(),
				kind(rule.kind().textValue()), text(rule.docClass(), null), text(rule.docSubclass(), null));
	}

	private static void required(List<FieldError> errors, String field, boolean given, String detail) {
		if (!given)
			errors.add(new FieldError(field, "required", detail));
	}

	/** A member that's there and not null */
	private static boolean present(JsonNode node) {
		return node != null && !node.isNull();
	}

	/**
	 * @return the kind a rule's {@code kind} names; null for none
	 */
	private static SearchFolderRule.Kind kind(String kind) {
		for (SearchFolderRule.Kind value : SearchFolderRule.Kind.values())
			if (value.name().toLowerCase(Locale.ROOT).equals(kind))
				return value;
		return null;
	}
}
