package com.logicaldoc.gui.frontend.client.healdan.beans;

import java.io.Serializable;

/**
 * A Healdan client: the first part of a matter's number.
 */
public class GUIClient implements Serializable {

	private static final long serialVersionUID = 1L;

	/** Zero for a client not saved yet */
	private long id;

	private long number;

	private String name;

	public long getId() {
		return id;
	}

	public void setId(long id) {
		this.id = id;
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
}
