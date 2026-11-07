// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage.backend;

import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Looks endpoints' hosts up in a map, for the S3 backends built while it is open, through {@link S3Backend}'s
 * resolver: no test needs real DNS. A host the map lacks can't be found, as when DNS is down.
 */
public final class FakeDns implements AutoCloseable {

	private final Map<String, String> names;

	private final List<String> looked = new CopyOnWriteArrayList<>();

	private FakeDns(Map<String, String> names) {
		this.names = Map.copyOf(names);
	}

	/**
	 * @param names each host's address; an empty map finds no host
	 * @return the resolver in use until {@link #close}
	 */
	public static FakeDns of(Map<String, String> names) {
		FakeDns dns = new FakeDns(names);
		S3Backend.resolver = dns::resolve;
		return dns;
	}

	private InetAddress[] resolve(String host) {
		looked.add(host);
		String address = names.get(host);
		if (address == null)
			throw new UncheckedIOException(new UnknownHostException(host));
		return new InetAddress[] { InetAddress.ofLiteral(address) };
	}

	/** @return the hosts looked up, in order */
	public List<String> looked() {
		return List.copyOf(looked);
	}

	/** Puts the JDK's resolver back */
	@Override
	public void close() {
		S3Backend.resolver = Endpoints.DNS;
	}
}
