package com.logicaldoc.healdan.matter;

/**
 * Where a folder sits in Healdan's layout, as {@link MatterLocator} finds it.
 * 
 * @param kind what sort of place the folder is
 * @param storeId the Store the folder is, or is under; null for the other
 *        kinds
 * @param matter the matter the folder is, or is inside; null if there is no
 *        {@code hd_matter} row yet (a workspace being created) or the folder
 *        isn't in a matter
 * @param workspaceId the matter's workspace folder; null if the folder isn't
 *        in a matter
 * @param depth 0 for the matter's workspace, 1 for a top-level folder and so
 *        on; -1 if the folder isn't in a matter
 * @param searchFolder true if the folder is a search folder (type 1001)
 */
public record FolderPosition(Kind kind, Long storeId, Matter matter, Long workspaceId, int depth,
		boolean searchFolder) {

	/** The places a folder can be */
	public enum Kind {
		/** The root of the folder tree */
		ROOT,
		/** A workspace under the root that holds matters */
		STORE,
		/** CE's Default workspace */
		DEFAULT_WORKSPACE,
		/** The Store that holds the workspace templates */
		TEMPLATES_STORE,
		/** Anywhere below the Templates Store */
		IN_TEMPLATES,
		/** A folder directly in a Store: a matter's workspace */
		MATTER_WORKSPACE,
		/** Anywhere below a matter's workspace */
		IN_MATTER,
		/** Anywhere else, such as below the Default workspace */
		OUTSIDE
	}

	/**
	 * @return true if the folder is a matter's workspace or inside one
	 */
	public boolean isInsideMatter() {
		return kind == Kind.MATTER_WORKSPACE || kind == Kind.IN_MATTER;
	}

	/**
	 * @return true if the folder is directly in a matter's workspace
	 */
	public boolean isMatterTopLevel() {
		return kind == Kind.IN_MATTER && depth == 1;
	}

	/**
	 * @return true if the folder is a Store or anywhere in one: the Store, a
	 *         matter's workspace or a folder inside it
	 */
	public boolean isUnderAStore() {
		return kind == Kind.STORE || kind == Kind.MATTER_WORKSPACE || kind == Kind.IN_MATTER;
	}
}
