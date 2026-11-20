// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.admin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.logicaldoc.core.metadata.Template;
import com.logicaldoc.healdan.api.PropertyValues;
import com.logicaldoc.healdan.api.model.OptionView;
import com.logicaldoc.healdan.api.model.TrashItem;
import com.logicaldoc.healdan.profile.ProfileSchema;
import com.logicaldoc.healdan.profile.ProfileView.ProfileField;
import com.logicaldoc.healdan.searchfolder.SearchFolderRule;

/**
 * The Workspace Editor's objects in {@code openapi-admin.yaml}'s names: a folder's values, a search folder with its
 * rule, and what a folder's or a template's delete put in the Trash. The tree's nodes and the templates are
 * {@code healdan-core}'s own records, which already have the contract's shape.
 */
public final class WorkspaceView {

	private WorkspaceView() {
	}

	/**
	 * {@code openapi-admin.yaml}'s {@code DeletedFolder}: the Trash item a folder's or a template's delete made.
	 *
	 * @param trashItemId the item in the caller's Trash that holds the folder
	 * @param folders the folders in it that went with it, at every level, its own left out
	 * @param documents the documents that went with it
	 */
	public record DeletedFolder(long trashItemId, long folders, long documents) {

		static DeletedFolder of(TrashItem item) {
			return new DeletedFolder(item.id(), item.contents().folders(), item.contents().documents());
		}
	}

	/**
	 * {@code openapi-admin.yaml}'s {@code FolderBrief}: a folder named in another object.
	 */
	public record FolderBrief(long id, String name) {
	}

	/**
	 * {@code openapi-admin.yaml}'s {@code FolderProperties}: the values a workspace's folder sets.
	 *
	 * @param folderId the folder
	 * @param properties each property that passes down from folders, in the profile's order
	 */
	public record FolderProperties(long folderId, List<FolderProperty> properties) {

		/**
		 * @param folderId the folder
		 * @param fields its values, as {@code WorkspaceAdmin.properties} gives them
		 */
		static FolderProperties of(long folderId, List<ProfileField> fields) {
			Template template = ProfileSchema.get().template();
			List<FolderProperty> properties = new ArrayList<>();
			for (ProfileField field : fields) {
				List<OptionView> options = field.options() == null ? null
						: field.options().stream().map(o -> new OptionView(o.value(), o.label(), o.docClass()))
								.toList();
				properties.add(new FolderProperty(field.name(), field.label(), PropertyValues.typeName(field.type()),
						PropertyValues.toJson(template.getAttribute(field.name()), field.value()),
						field.source() == null ? null : field.source().name().toLowerCase(Locale.ROOT),
						field.inheritedFromId() == null ? null
								: new FolderBrief(field.inheritedFromId(), field.inheritedFromName()),
						options));
			}
			return new FolderProperties(folderId, properties);
		}
	}

	/**
	 * {@code openapi-admin.yaml}'s {@code FolderProperty}: one value a folder sets, or inherits.
	 *
	 * @param name the property's name
	 * @param label its label
	 * @param type the type of its value
	 * @param value the folder's value, in the form {@code openapi.yaml}'s {@code ProfileField} gives it; null for none
	 * @param source {@code local}, {@code inherited} or {@code default}; null with no value
	 * @param inheritedFrom the folder an inherited value comes from, or null
	 * @param options the values to pick from; null for a property not picked from a list
	 */
	public record FolderProperty(String name, String label, String type, Object value, String source,
			FolderBrief inheritedFrom, List<OptionView> options) {
	}

	/**
	 * {@code openapi-admin.yaml}'s {@code SearchFolder}: a search folder of a workspace, with its rule.
	 */
	public record SearchFolder(long id, String name, Rule rule) {
	}

	/**
	 * {@code openapi-admin.yaml}'s {@code SearchFolderRule}, every member given.
	 *
	 * @param days the window, documents changed in the last so many days
	 * @param onlyMine whether only the changes of whoever opens it count
	 * @param kind {@code all}, {@code documents} or {@code emails}
	 * @param docClass the Class the documents must have; null for any
	 * @param docSubclass the Subclass the documents must have; null for any
	 */
	public record Rule(int days, boolean onlyMine, String kind, String docClass, String docSubclass) {

		static Rule of(SearchFolderRule rule) {
			return new Rule(rule.days(), rule.onlyMine(), rule.kind().name().toLowerCase(Locale.ROOT), rule.docClass(),
					rule.docSubclass());
		}
	}
}
