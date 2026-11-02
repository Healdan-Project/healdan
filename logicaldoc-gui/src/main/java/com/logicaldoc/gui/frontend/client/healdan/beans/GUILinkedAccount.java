package com.logicaldoc.gui.frontend.client.healdan.beans;

import java.io.Serializable;
import java.util.Date;

/**
 * An account linked to a sign-in provider, as the Sign-In tab lists it (M4
 * spec section 10.3)
 */
public class GUILinkedAccount implements Serializable {

	private static final long serialVersionUID = 1L;

	private long userId;

	private String username;

	/** The account's full name */
	private String name;

	/** How it was linked: {@code email} or {@code new} */
	private String how;

	/** When it was linked */
	private Date linked;

	/** When it last signed in through the provider; null if it hasn't */
	private Date lastSignIn;

	public long getUserId() {
		return userId;
	}

	public void setUserId(long userId) {
		this.userId = userId;
	}

	public String getUsername() {
		return username;
	}

	public void setUsername(String username) {
		this.username = username;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getHow() {
		return how;
	}

	public void setHow(String how) {
		this.how = how;
	}

	public Date getLinked() {
		return linked;
	}

	public void setLinked(Date linked) {
		this.linked = linked;
	}

	public Date getLastSignIn() {
		return lastSignIn;
	}

	public void setLastSignIn(Date lastSignIn) {
		this.lastSignIn = lastSignIn;
	}
}
