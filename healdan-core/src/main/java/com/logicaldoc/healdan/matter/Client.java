package com.logicaldoc.healdan.matter;

import com.logicaldoc.core.PersistentObject;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * A client: the first part of a matter's number.
 */
@Entity
@Table(name = "hd_client")
public class Client extends PersistentObject {

	private static final long serialVersionUID = 1L;

	@Column(name = "hd_number", nullable = false)
	private long number;

	@Column(name = "hd_name", length = 255, nullable = false)
	private String name;

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

	@Override
	public String toString() {
		// An escape: the build reads sources as ISO-8859-1
		return "%d \u2013 %s".formatted(number, name);
	}
}
