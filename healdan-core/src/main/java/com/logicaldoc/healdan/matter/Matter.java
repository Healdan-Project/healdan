package com.logicaldoc.healdan.matter;

import com.logicaldoc.core.PersistentObject;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * A matter. It is its workspace folder: {@code folderId} is that folder.
 */
@Entity
@Table(name = "hd_matter")
public class Matter extends PersistentObject {

	private static final long serialVersionUID = 1L;

	@Column(name = "hd_clientid", nullable = false)
	private long clientId;

	@Column(name = "hd_number", nullable = false)
	private long number;

	@Column(name = "hd_name", length = 255, nullable = false)
	private String name;

	@Column(name = "hd_description", length = 4000)
	private String description;

	@Column(name = "hd_folderid", nullable = false)
	private long folderId;

	@Column(name = "hd_ownerid", nullable = false)
	private long ownerId;

	/** The folder the matter was copied from, if it was created from a template */
	@Column(name = "hd_templateid")
	private Long templateId;

	public long getClientId() {
		return clientId;
	}

	public void setClientId(long clientId) {
		this.clientId = clientId;
	}

	public long getNumber() {
		return number;
	}

	public void setNumber(long number) {
		this.number = number;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public long getFolderId() {
		return folderId;
	}

	public void setFolderId(long folderId) {
		this.folderId = folderId;
	}

	public long getOwnerId() {
		return ownerId;
	}

	public void setOwnerId(long ownerId) {
		this.ownerId = ownerId;
	}

	public Long getTemplateId() {
		return templateId;
	}

	public void setTemplateId(Long templateId) {
		this.templateId = templateId;
	}
}
