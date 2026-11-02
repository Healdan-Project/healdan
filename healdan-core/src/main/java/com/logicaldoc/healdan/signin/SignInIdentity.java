package com.logicaldoc.healdan.signin;

import java.util.Date;

import com.logicaldoc.core.PersistentObject;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Temporal;
import jakarta.persistence.TemporalType;

/**
 * A link between an account and its identity at a sign-in provider (spec
 * section 7). A row stays when it is unlinked, as a record.
 * <p>
 * {@link #getLinkKey()} and {@link #getLinkUser()} are set only while the row
 * is linked, and the database holds a unique index on each. So among the links
 * in use an identity belongs to one account and an account has one identity,
 * while unlinked rows repeat: a database lets null repeat in a unique index.
 * {@link SignInIdentityDAO#store} sets the two from the rest of the row each
 * time, so a caller can't forget them.
 */
@Entity
@Table(name = "hd_signin_identity")
public class SignInIdentity extends PersistentObject {

	private static final long serialVersionUID = 1L;

	/** How an account was linked: by the email it shares with the provider's */
	public static final String HOW_EMAIL = "email";

	/** How an account was linked: made at its first sign-in */
	public static final String HOW_NEW = "new";

	/** The provider's id */
	@Column(name = "hd_provider", length = 32, nullable = false)
	private String provider;

	/** The identity at the provider: the value of its identity claim, such as {@code oid} or {@code sub} */
	@Column(name = "hd_identity", length = 255, nullable = false)
	private String identity;

	@Column(name = "hd_userid", nullable = false)
	private long userId;

	@Column(name = "hd_linked", nullable = false)
	@Temporal(TemporalType.TIMESTAMP)
	private Date linked;

	/** {@code email} or {@code new} */
	@Column(name = "hd_how", length = 10, nullable = false)
	private String how;

	@Column(name = "hd_lastsignin")
	@Temporal(TemporalType.TIMESTAMP)
	private Date lastSignIn;

	/** When an admin unlinked the account; null while it is linked */
	@Column(name = "hd_unlinked")
	@Temporal(TemporalType.TIMESTAMP)
	private Date unlinked;

	/** The id of the admin who unlinked it */
	@Column(name = "hd_unlinkedby")
	private Long unlinkedBy;

	/** {@code <provider>:<identity>} while linked, else null */
	@Column(name = "hd_linkkey", length = 300)
	private String linkKey;

	/** {@link #getUserId()} while linked, else null */
	@Column(name = "hd_linkuser")
	private Long linkUser;

	/**
	 * @return true while the account is linked, false once it was unlinked
	 */
	public boolean isLinked() {
		return unlinked == null;
	}

	/**
	 * Sets the two columns the unique indexes rest on from the rest of the row:
	 * both while it is linked, neither once it was unlinked.
	 */
	void syncLinkColumns() {
		if (isLinked()) {
			linkKey = provider + ":" + identity;
			linkUser = userId;
		} else {
			linkKey = null;
			linkUser = null;
		}
	}

	public String getProvider() {
		return provider;
	}

	public void setProvider(String provider) {
		this.provider = provider;
	}

	public String getIdentity() {
		return identity;
	}

	public void setIdentity(String identity) {
		this.identity = identity;
	}

	public long getUserId() {
		return userId;
	}

	public void setUserId(long userId) {
		this.userId = userId;
	}

	public Date getLinked() {
		return linked;
	}

	public void setLinked(Date linked) {
		this.linked = linked;
	}

	public String getHow() {
		return how;
	}

	public void setHow(String how) {
		this.how = how;
	}

	public Date getLastSignIn() {
		return lastSignIn;
	}

	public void setLastSignIn(Date lastSignIn) {
		this.lastSignIn = lastSignIn;
	}

	public Date getUnlinked() {
		return unlinked;
	}

	public void setUnlinked(Date unlinked) {
		this.unlinked = unlinked;
	}

	public Long getUnlinkedBy() {
		return unlinkedBy;
	}

	public void setUnlinkedBy(Long unlinkedBy) {
		this.unlinkedBy = unlinkedBy;
	}

	public String getLinkKey() {
		return linkKey;
	}

	public void setLinkKey(String linkKey) {
		this.linkKey = linkKey;
	}

	public Long getLinkUser() {
		return linkUser;
	}

	public void setLinkUser(Long linkUser) {
		this.linkUser = linkUser;
	}
}
