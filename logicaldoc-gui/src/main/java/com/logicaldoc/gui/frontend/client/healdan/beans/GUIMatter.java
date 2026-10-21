package com.logicaldoc.gui.frontend.client.healdan.beans;

import java.io.Serializable;

/**
 * A matter, as the New and Edit Matter dialogs show it (spec 5.3)
 */
public class GUIMatter implements Serializable {

	private static final long serialVersionUID = 1L;

	/** Zero for a matter not created yet */
	private long id;

	private long clientId;

	private long clientNumber;

	private String clientName;

	private long number;

	private String name;

	private String description;

	/** The Store the matter's workspace is in */
	private long storeId;

	private long ownerId;

	private String ownerName;

	/** The workspace template it was created from */
	private Long templateId;

	/** The matter's workspace */
	private long folderId;

	/** The client and matter numbers, an en dash and the name */
	private String display;

	public long getId() {
		return id;
	}

	public void setId(long id) {
		this.id = id;
	}

	public long getClientId() {
		return clientId;
	}

	public void setClientId(long clientId) {
		this.clientId = clientId;
	}

	public long getClientNumber() {
		return clientNumber;
	}

	public void setClientNumber(long clientNumber) {
		this.clientNumber = clientNumber;
	}

	public String getClientName() {
		return clientName;
	}

	public void setClientName(String clientName) {
		this.clientName = clientName;
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

	public long getStoreId() {
		return storeId;
	}

	public void setStoreId(long storeId) {
		this.storeId = storeId;
	}

	public long getOwnerId() {
		return ownerId;
	}

	public void setOwnerId(long ownerId) {
		this.ownerId = ownerId;
	}

	public String getOwnerName() {
		return ownerName;
	}

	public void setOwnerName(String ownerName) {
		this.ownerName = ownerName;
	}

	public Long getTemplateId() {
		return templateId;
	}

	public void setTemplateId(Long templateId) {
		this.templateId = templateId;
	}

	public long getFolderId() {
		return folderId;
	}

	public void setFolderId(long folderId) {
		this.folderId = folderId;
	}

	public String getDisplay() {
		return display;
	}

	public void setDisplay(String display) {
		this.display = display;
	}
}
