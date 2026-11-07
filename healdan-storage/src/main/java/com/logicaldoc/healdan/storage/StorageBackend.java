// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage;

import com.logicaldoc.core.PersistentObject;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * A place Healdan keeps files: a folder on disk or an S3 bucket (spec section 5). Backend 1, Original, is made by the
 * migration and is CE's own store folder ({@code store.1.dir}), so it has no path.
 * <p>
 * The three flags are {@code int} fields, as their columns are, with {@code boolean} accessors: PostgreSQL binds a Java
 * {@code boolean} as SQL {@code boolean} and won't store it in an {@code int} column. Healdan's entities annotate
 * fields, so Hibernate doesn't map the accessors.
 */
@Entity
@Table(name = "hd_storage_backend")
public class StorageBackend extends PersistentObject {

	private static final long serialVersionUID = 1L;

	/** The id of Original, which the migration makes */
	public static final long ORIGINAL = 1L;

	/** A folder on disk */
	public static final String LOCAL = "local";

	/** An S3 bucket */
	public static final String S3 = "s3";

	@Column(name = "hd_name", length = 100, nullable = false)
	private String name;

	/** {@code local} or {@code s3} */
	@Column(name = "hd_kind", length = 10, nullable = false)
	private String kind = LOCAL;

	/** A local backend's folder; null for Original, whose folder is {@code store.1.dir} */
	@Column(name = "hd_path", length = 1000)
	private String path;

	@Column(name = "hd_endpoint", length = 1000)
	private String endpoint;

	@Column(name = "hd_region", length = 100)
	private String region;

	@Column(name = "hd_bucket", length = 255)
	private String bucket;

	@Column(name = "hd_prefix", length = 255)
	private String prefix;

	@Column(name = "hd_pathstyle", nullable = false)
	private int pathStyle;

	@Column(name = "hd_accesskey", length = 255)
	private String accessKey;

	/** The S3 secret, sealed by SecretBox; never leaves the server */
	@Column(name = "hd_secret", length = 4000)
	private String secret;

	@Column(name = "hd_encrypted", nullable = false)
	private int encrypted;

	@Column(name = "hd_keyconfirmed", nullable = false)
	private int keyConfirmed;

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getKind() {
		return kind;
	}

	public void setKind(String kind) {
		this.kind = kind;
	}

	public String getPath() {
		return path;
	}

	public void setPath(String path) {
		this.path = path;
	}

	public String getEndpoint() {
		return endpoint;
	}

	public void setEndpoint(String endpoint) {
		this.endpoint = endpoint;
	}

	public String getRegion() {
		return region;
	}

	public void setRegion(String region) {
		this.region = region;
	}

	public String getBucket() {
		return bucket;
	}

	public void setBucket(String bucket) {
		this.bucket = bucket;
	}

	public String getPrefix() {
		return prefix;
	}

	public void setPrefix(String prefix) {
		this.prefix = prefix;
	}

	public int getPathStyle() {
		return pathStyle;
	}

	public void setPathStyle(int pathStyle) {
		this.pathStyle = pathStyle;
	}

	public boolean isPathStyleOn() {
		return pathStyle == 1;
	}

	public void setPathStyleOn(boolean on) {
		this.pathStyle = on ? 1 : 0;
	}

	public String getAccessKey() {
		return accessKey;
	}

	public void setAccessKey(String accessKey) {
		this.accessKey = accessKey;
	}

	public String getSecret() {
		return secret;
	}

	public void setSecret(String secret) {
		this.secret = secret;
	}

	public int getEncrypted() {
		return encrypted;
	}

	public void setEncrypted(int encrypted) {
		this.encrypted = encrypted;
	}

	public boolean isEncryptedOn() {
		return encrypted == 1;
	}

	public void setEncryptedOn(boolean on) {
		this.encrypted = on ? 1 : 0;
	}

	public int getKeyConfirmed() {
		return keyConfirmed;
	}

	public void setKeyConfirmed(int keyConfirmed) {
		this.keyConfirmed = keyConfirmed;
	}

	public boolean isKeyConfirmedOn() {
		return keyConfirmed == 1;
	}

	public void setKeyConfirmedOn(boolean on) {
		this.keyConfirmed = on ? 1 : 0;
	}
}
