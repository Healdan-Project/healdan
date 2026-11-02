package com.logicaldoc.healdan.token;

import java.util.Date;

import com.logicaldoc.core.PersistentObject;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Temporal;
import jakarta.persistence.TemporalType;

/**
 * A device token: what an add-in keeps to sign in again. Only the hash of the
 * token is stored.
 */
@Entity
@Table(name = "hd_api_token")
public class ApiToken extends PersistentObject {

	private static final long serialVersionUID = 1L;

	@Column(name = "hd_userid", nullable = false)
	private long userId;

	@Column(name = "hd_hash", length = 64, nullable = false)
	private String hash;

	@Column(name = "hd_name", length = 255, nullable = false)
	private String name;

	@Column(name = "hd_lastused", nullable = false)
	@Temporal(TemporalType.TIMESTAMP)
	private Date lastUsed;

	@Column(name = "hd_expires", nullable = false)
	@Temporal(TemporalType.TIMESTAMP)
	private Date expires;

	@Column(name = "hd_sid", length = 255)
	private String sid;

	/** The id of the computer the token was issued to, or null if the client named none */
	@Column(name = "hd_device", length = 64)
	private String device;

	/** The id of the sign-in provider the token came from, or null for a token from a password sign-in */
	@Column(name = "hd_provider", length = 32)
	private String provider;

	/** The provider's refresh token, sealed by {@code SecretBox}; null when the token has none */
	@Column(name = "hd_refresh", length = 4000)
	private String refresh;

	/** When the provider last said the person may still sign in, or null before the first check */
	@Column(name = "hd_checked")
	@Temporal(TemporalType.TIMESTAMP)
	private Date checked;

	public long getUserId() {
		return userId;
	}

	public void setUserId(long userId) {
		this.userId = userId;
	}

	public String getHash() {
		return hash;
	}

	public void setHash(String hash) {
		this.hash = hash;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public Date getLastUsed() {
		return lastUsed;
	}

	public void setLastUsed(Date lastUsed) {
		this.lastUsed = lastUsed;
	}

	public Date getExpires() {
		return expires;
	}

	public void setExpires(Date expires) {
		this.expires = expires;
	}

	public String getSid() {
		return sid;
	}

	public void setSid(String sid) {
		this.sid = sid;
	}

	public String getDevice() {
		return device;
	}

	public void setDevice(String device) {
		this.device = device;
	}

	public String getProvider() {
		return provider;
	}

	public void setProvider(String provider) {
		this.provider = provider;
	}

	public String getRefresh() {
		return refresh;
	}

	public void setRefresh(String refresh) {
		this.refresh = refresh;
	}

	public Date getChecked() {
		return checked;
	}

	public void setChecked(Date checked) {
		this.checked = checked;
	}
}
