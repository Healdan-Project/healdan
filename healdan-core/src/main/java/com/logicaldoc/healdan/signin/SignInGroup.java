package com.logicaldoc.healdan.signin;

import com.logicaldoc.core.PersistentObject;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * One row of a provider's group map (spec section 8): a person who has the
 * value in the provider's groups claim is put in the Healdan group. A value
 * may map to several groups, and a group may have several values.
 */
@Entity
@Table(name = "hd_signin_group")
public class SignInGroup extends PersistentObject {

	private static final long serialVersionUID = 1L;

	/** The provider's id */
	@Column(name = "hd_provider", length = 32, nullable = false)
	private String provider;

	/** A value of the provider's groups claim */
	@Column(name = "hd_value", length = 255, nullable = false)
	private String value;

	/** The id of the Healdan group */
	@Column(name = "hd_groupid", nullable = false)
	private long groupId;

	public String getProvider() {
		return provider;
	}

	public void setProvider(String provider) {
		this.provider = provider;
	}

	public String getValue() {
		return value;
	}

	public void setValue(String value) {
		this.value = value;
	}

	public long getGroupId() {
		return groupId;
	}

	public void setGroupId(long groupId) {
		this.groupId = groupId;
	}
}
