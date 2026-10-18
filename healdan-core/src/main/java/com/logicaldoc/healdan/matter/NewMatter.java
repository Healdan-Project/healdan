package com.logicaldoc.healdan.matter;

/**
 * What an admin asks for in the New Matter dialog (spec 5.3).
 *
 * @param clientId the client
 * @param number the matter number
 * @param name the matter name; it is trimmed
 * @param description an optional description
 * @param storeId the Store the workspace goes in
 * @param ownerId the user who owns the workspace
 * @param templateFolderId the workspace template, a folder directly under the
 *        Templates Store
 */
public record NewMatter(long clientId, long number, String name, String description, long storeId, long ownerId,
		long templateFolderId) {
}
