// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.gui.frontend.client.healdan.beans;

import java.io.Serializable;

/**
 * A file the mover gave up on, as the Storage tab's stuck files list it (spec
 * section 11)
 */
public class GUIStuckFile implements Serializable {

	private static final long serialVersionUID = 1L;

	private long docId;

	/** The document's file name; null if the document is gone */
	private String fileName;

	/** The resource, such as {@code 1.2} or {@code 1.2-thumb.png} */
	private String resource;

	/** Its failed moves in a row */
	private int attempts;

	/** The last failure's message */
	private String lastError;

	public long getDocId() {
		return docId;
	}

	public void setDocId(long docId) {
		this.docId = docId;
	}

	public String getFileName() {
		return fileName;
	}

	public void setFileName(String fileName) {
		this.fileName = fileName;
	}

	public String getResource() {
		return resource;
	}

	public void setResource(String resource) {
		this.resource = resource;
	}

	public int getAttempts() {
		return attempts;
	}

	public void setAttempts(int attempts) {
		this.attempts = attempts;
	}

	public String getLastError() {
		return lastError;
	}

	public void setLastError(String lastError) {
		this.lastError = lastError;
	}
}
