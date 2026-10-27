package com.logicaldoc.healdan.token;

import com.logicaldoc.core.PersistentObject;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * Which computer holds a document's check-out made through the API (spec
 * section 9.4): one row per document, which {@link DeviceHolds} keeps. A row
 * speaks only for the check-out it was written for: when it was last written
 * ({@link #getLastModified()}) tells it from a later one.
 */
@Entity
@Table(name = "hd_hold_device")
public class HoldDevice extends PersistentObject {

	private static final long serialVersionUID = 1L;

	@Column(name = "hd_docid", nullable = false)
	private long docId;

	/** Null once the check-out ended, or for a token that named no device */
	@Column(name = "hd_device", length = 64)
	private String device;

	/** The token the device last wrote through, whose name the device goes by */
	@Column(name = "hd_tokenid")
	private Long tokenId;

	/** The app the helper named when checking the document out, such as LibreOffice */
	@Column(name = "hd_app", length = 100)
	private String app;

	/** The last save's id, kept when the check-out ends, so that a repeated save is told */
	@Column(name = "hd_lastsaveid", length = 64)
	private String lastSaveId;

	/** The file version the last save stored, so that a repeat is told only while it's still the document's */
	@Column(name = "hd_lastsavefileversion", length = 10)
	private String lastSaveFileVersion;

	public long getDocId() {
		return docId;
	}

	public void setDocId(long docId) {
		this.docId = docId;
	}

	public String getDevice() {
		return device;
	}

	public void setDevice(String device) {
		this.device = device;
	}

	public Long getTokenId() {
		return tokenId;
	}

	public void setTokenId(Long tokenId) {
		this.tokenId = tokenId;
	}

	public String getApp() {
		return app;
	}

	public void setApp(String app) {
		this.app = app;
	}

	public String getLastSaveId() {
		return lastSaveId;
	}

	public void setLastSaveId(String lastSaveId) {
		this.lastSaveId = lastSaveId;
	}

	public String getLastSaveFileVersion() {
		return lastSaveFileVersion;
	}

	public void setLastSaveFileVersion(String lastSaveFileVersion) {
		this.lastSaveFileVersion = lastSaveFileVersion;
	}
}
