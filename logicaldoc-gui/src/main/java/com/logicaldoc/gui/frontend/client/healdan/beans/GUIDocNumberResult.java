package com.logicaldoc.gui.frontend.client.healdan.beans;

import java.io.Serializable;

/**
 * What a doc # lookup found (spec 6.5), as the server's {@code LookupResult}
 */
public class GUIDocNumberResult implements Serializable {

	private static final long serialVersionUID = 1L;

	/** The document, null if nothing was found */
	private Long docId;

	/** The document's folder */
	private Long folderId;

	/** The version row asked for, null for the current version */
	private Long versionId;

	/** The version found, such as {@code 2.10} */
	private String version;

	/** The file version of that version */
	private String fileVersion;

	/** Why nothing was found, for the user; null when found */
	private String message;

	public Long getDocId() {
		return docId;
	}

	public void setDocId(Long docId) {
		this.docId = docId;
	}

	public Long getFolderId() {
		return folderId;
	}

	public void setFolderId(Long folderId) {
		this.folderId = folderId;
	}

	public Long getVersionId() {
		return versionId;
	}

	public void setVersionId(Long versionId) {
		this.versionId = versionId;
	}

	public String getVersion() {
		return version;
	}

	public void setVersion(String version) {
		this.version = version;
	}

	public String getFileVersion() {
		return fileVersion;
	}

	public void setFileVersion(String fileVersion) {
		this.fileVersion = fileVersion;
	}

	public String getMessage() {
		return message;
	}

	public void setMessage(String message) {
		this.message = message;
	}

	/**
	 * @return true if a document was found
	 */
	public boolean isFound() {
		return docId != null;
	}
}
