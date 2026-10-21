package com.logicaldoc.gui.frontend.client.healdan.beans;

import java.io.Serializable;

/**
 * What a folder is in Healdan's layout, for the folder menu
 */
public class GUIFolderRole implements Serializable {

	private static final long serialVersionUID = 1L;

	/**
	 * Where the folder sits: STORE, MATTER_WORKSPACE, IN_MATTER,
	 * TEMPLATES_STORE, IN_TEMPLATES, DEFAULT_WORKSPACE, ROOT or OUTSIDE
	 */
	private String kind;

	/** The matter the folder is or is inside, null if none */
	private Long matterId;

	/** True if the folder is directly in a matter's workspace */
	private boolean topLevel;

	private boolean searchFolder;

	/** True if the folder is a matter's workspace in the user's My Matters */
	private boolean inMyMatters;

	public String getKind() {
		return kind;
	}

	public void setKind(String kind) {
		this.kind = kind;
	}

	public Long getMatterId() {
		return matterId;
	}

	public void setMatterId(Long matterId) {
		this.matterId = matterId;
	}

	public boolean isTopLevel() {
		return topLevel;
	}

	public void setTopLevel(boolean topLevel) {
		this.topLevel = topLevel;
	}

	public boolean isSearchFolder() {
		return searchFolder;
	}

	public void setSearchFolder(boolean searchFolder) {
		this.searchFolder = searchFolder;
	}

	public boolean isInMyMatters() {
		return inMyMatters;
	}

	public void setInMyMatters(boolean inMyMatters) {
		this.inMyMatters = inMyMatters;
	}
}
