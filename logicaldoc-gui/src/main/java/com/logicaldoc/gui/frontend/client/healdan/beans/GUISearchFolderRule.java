package com.logicaldoc.gui.frontend.client.healdan.beans;

import java.io.Serializable;

/**
 * A search folder: its name and its rule (spec 5.4)
 */
public class GUISearchFolderRule implements Serializable {

	private static final long serialVersionUID = 1L;

	/** Zero for a search folder not created yet */
	private long folderId;

	private String name;

	/** The window: documents changed in the last so many days */
	private int days = 30;

	/** True if only the viewer's own changes count */
	private boolean onlyMine;

	/** Which documents: ALL, DOCUMENTS or EMAILS */
	private String kind = "ALL";

	/** The Class the documents must have, null for any */
	private String docClass;

	/** The Subclass the documents must have, null for any; only with a Class */
	private String docSubclass;

	public long getFolderId() {
		return folderId;
	}

	public void setFolderId(long folderId) {
		this.folderId = folderId;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public int getDays() {
		return days;
	}

	public void setDays(int days) {
		this.days = days;
	}

	public boolean isOnlyMine() {
		return onlyMine;
	}

	public void setOnlyMine(boolean onlyMine) {
		this.onlyMine = onlyMine;
	}

	public String getKind() {
		return kind;
	}

	public void setKind(String kind) {
		this.kind = kind;
	}

	public String getDocClass() {
		return docClass;
	}

	public void setDocClass(String docClass) {
		this.docClass = docClass;
	}

	public String getDocSubclass() {
		return docSubclass;
	}

	public void setDocSubclass(String docSubclass) {
		this.docSubclass = docSubclass;
	}
}
